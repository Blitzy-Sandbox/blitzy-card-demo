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
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LocalStack-backed integration coverage for {@link S3OutputService}.
 *
 * <p><b>// Replaces: sequential PS/GDG writes</b> to DALYREJS, SYSTRAN,
 * TRANREPT, STMTFILE, and TRANSACT.BKUP. This test verifies that the
 * adapter performs an actual S3 PUT against an AWS-compatible endpoint
 * and applies the required SSE-KMS request headers.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("S3OutputService — LocalStack S3 integration")
class S3OutputServiceIntegrationTest {

    private static final DockerImageName LOCALSTACK_IMAGE =
            DockerImageName.parse("localstack/localstack:3.8");

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(LOCALSTACK_IMAGE)
            .withServices(Service.S3, Service.KMS);

    private static S3Client s3Client;
    private static String bucketName;
    private static S3OutputService s3OutputService;

    @BeforeAll
    static void setUp() {
        s3Client = S3Client.builder()
                .endpointOverride(LOCALSTACK.getEndpointOverride(Service.S3))
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                LOCALSTACK.getAccessKey(),
                                LOCALSTACK.getSecretKey())))
                .forcePathStyle(true)
                .build();
        bucketName = "carddemo-s3-output-it-" + UUID.randomUUID();
        s3Client.createBucket(builder -> builder.bucket(bucketName));
        s3OutputService = new S3OutputService(
                s3Client,
                bucketName,
                "arn:aws:kms:us-east-1:000000000000:key/carddemo-s3-test");
    }

    @AfterAll
    static void tearDown() {
        if (s3Client != null) {
            s3Client.close();
        }
    }

    @Test
    @DisplayName("copyTransactionBackup writes retrievable SSE-KMS object with COBOL metadata")
    void copyTransactionBackupWritesSseKmsObject() {
        byte[] payload = "TRANSACT BACKUP RECORD\n".getBytes(StandardCharsets.US_ASCII);

        s3OutputService.copyTransactionBackup("it-generation-001", payload);

        ListObjectsV2Response listResponse = s3Client.listObjectsV2(builder -> builder
                .bucket(bucketName)
                .prefix("transact-bkup/"));
        assertThat(listResponse.contents()).hasSize(1);
        String key = listResponse.contents().get(0).key();

        HeadObjectResponse head = s3Client.headObject(builder -> builder
                .bucket(bucketName)
                .key(key));
        assertThat(head.serverSideEncryption()).isEqualTo(ServerSideEncryption.AWS_KMS);
        assertThat(head.metadata())
                .containsEntry("jcl-dd", "TRANSACT.BKUP")
                .containsEntry("batch-run-id", "it-generation-001");

        ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(builder -> builder
                .bucket(bucketName)
                .key(key));
        assertThat(objectBytes.asByteArray()).isEqualTo(payload);
    }
}