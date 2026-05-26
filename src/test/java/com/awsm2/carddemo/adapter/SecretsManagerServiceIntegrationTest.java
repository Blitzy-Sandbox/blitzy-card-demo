/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.awsm2.carddemo.adapter;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.localstack.LocalStackContainer.Service;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretResponse;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LocalStack-backed integration coverage for {@link SecretsManagerService}.
 *
 * <p><b>// Replaces: plaintext credential reads</b> from the source
 * mainframe security artifacts. This test verifies that the adapter reads
 * an actual AWS Secrets Manager compatible secret and extracts JSON fields
 * used by Spring Cloud AWS / refresh-scoped runtime configuration.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("SecretsManagerService — LocalStack Secrets Manager integration")
class SecretsManagerServiceIntegrationTest {

    private static final DockerImageName LOCALSTACK_IMAGE =
            DockerImageName.parse("localstack/localstack:3.8");

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(LOCALSTACK_IMAGE)
            .withServices(Service.SECRETSMANAGER);

    private static SecretsManagerClient secretsManagerClient;
    private static SecretsManagerService secretsManagerService;

    @BeforeAll
    static void setUp() {
        secretsManagerClient = SecretsManagerClient.builder()
                .endpointOverride(LOCALSTACK.getEndpointOverride(Service.SECRETSMANAGER))
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                LOCALSTACK.getAccessKey(),
                                LOCALSTACK.getSecretKey())))
                .build();
        secretsManagerService = new SecretsManagerService(secretsManagerClient);
    }

    @AfterAll
    static void tearDown() {
        if (secretsManagerClient != null) {
            secretsManagerClient.close();
        }
    }

    @Test
    @DisplayName("getSecret and getSecretJsonField read a LocalStack-managed secret")
    void readsSecretAndJsonFieldFromLocalStack() {
        String secretName = "carddemo/it/db/" + UUID.randomUUID();
        String secretJson = "{\"username\":\"carddemo\",\"password\":\"rotated-password\"}";
        CreateSecretResponse response = secretsManagerClient.createSecret(builder -> builder
                .name(secretName)
                .secretString(secretJson));

        String rawSecret = secretsManagerService.getSecret(response.arn());
        Optional<String> password =
                secretsManagerService.getSecretJsonField(response.arn(), "password");
        Optional<String> missing =
                secretsManagerService.getSecretJsonField(response.arn(), "notPresent");

        assertThat(rawSecret).isEqualTo(secretJson);
        assertThat(password).contains("rotated-password");
        assertThat(missing).isEmpty();
    }
}