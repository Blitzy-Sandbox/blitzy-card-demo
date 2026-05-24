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
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.CreateStateMachineResponse;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LocalStack-backed integration coverage for
 * {@link StepFunctionsOrchestrator}.
 *
 * <p><b>// Replaces: JES/JCL job submission</b> with AWS Step Functions
 * executions. This test provisions a minimal ASL fixture in LocalStack
 * and verifies that the adapter's {@code startExecution} method performs
 * a real Step Functions API call.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("StepFunctionsOrchestrator — LocalStack Step Functions integration")
class StepFunctionsOrchestratorIntegrationTest {

    private static final DockerImageName LOCALSTACK_IMAGE =
            DockerImageName.parse("localstack/localstack:3.8");

    private static final String DUMMY_ROLE_ARN =
            "arn:aws:iam::000000000000:role/carddemo-stepfunctions-it";

    private static final String PASS_STATE_MACHINE = """
            {
              "Comment": "CardDemo adapter integration fixture",
              "StartAt": "Done",
              "States": {
                "Done": {
                  "Type": "Pass",
                  "Result": {"ok": true},
                  "End": true
                }
              }
            }
            """;

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(LOCALSTACK_IMAGE)
            .withServices(Service.STEPFUNCTIONS, Service.IAM);

    private static SfnClient sfnClient;
    private static StepFunctionsOrchestrator orchestrator;
    private static String stateMachineArn;

    @BeforeAll
    static void setUp() {
        sfnClient = SfnClient.builder()
                .endpointOverride(LOCALSTACK.getEndpointOverride(Service.STEPFUNCTIONS))
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                LOCALSTACK.getAccessKey(),
                                LOCALSTACK.getSecretKey())))
                .build();
        CreateStateMachineResponse response = sfnClient.createStateMachine(builder -> builder
                .name("carddemo-adapter-it-" + UUID.randomUUID().toString().substring(0, 8))
                .definition(PASS_STATE_MACHINE)
                .roleArn(DUMMY_ROLE_ARN));
        stateMachineArn = response.stateMachineArn();
        orchestrator = new StepFunctionsOrchestrator(sfnClient);
    }

    @AfterAll
    static void tearDown() {
        if (sfnClient != null) {
            if (stateMachineArn != null) {
                sfnClient.deleteStateMachine(builder -> builder.stateMachineArn(stateMachineArn));
            }
            sfnClient.close();
        }
    }

    @Test
    @DisplayName("startExecution starts a real LocalStack state-machine execution")
    void startExecutionStartsLocalStackStateMachine() {
        String executionArn = orchestrator.startExecution(
                stateMachineArn,
                "{\"batchRunId\":\"sfn-adapter-it\"}");

        DescribeExecutionResponse described = sfnClient.describeExecution(builder -> builder
                .executionArn(executionArn));
        assertThat(executionArn).startsWith("arn:aws:states:");
        assertThat(described.stateMachineArn()).isEqualTo(stateMachineArn);
        assertThat(described.input()).contains("sfn-adapter-it");
    }
}