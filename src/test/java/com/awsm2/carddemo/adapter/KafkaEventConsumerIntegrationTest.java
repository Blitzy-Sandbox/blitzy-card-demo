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

import com.awsm2.carddemo.dto.ReportRequestDto;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.util.ReflectionTestUtils;
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
import software.amazon.awssdk.services.sfn.model.ListExecutionsResponse;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * LocalStack-backed integration coverage for {@link KafkaEventConsumer}.
 *
 * <p><b>// Replaces: CORPT00C TDQ JOBS consumer bridge</b>. The test
 * drives the report-request listener with an actual
 * {@link StepFunctionsOrchestrator} backed by LocalStack Step Functions,
 * verifying that successful processing starts a state-machine execution
 * and acknowledges the Kafka offset.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("KafkaEventConsumer — LocalStack Step Functions bridge integration")
class KafkaEventConsumerIntegrationTest {

    private static final DockerImageName LOCALSTACK_IMAGE =
            DockerImageName.parse("localstack/localstack:3.8");

    private static final String DUMMY_ROLE_ARN =
            "arn:aws:iam::000000000000:role/carddemo-consumer-it";

    private static final String PASS_STATE_MACHINE = """
            {
              "StartAt": "Accepted",
              "States": {
                "Accepted": {
                  "Type": "Pass",
                  "End": true
                }
              }
            }
            """;

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(LOCALSTACK_IMAGE)
            .withServices(Service.STEPFUNCTIONS, Service.IAM);

    private static SfnClient sfnClient;
    private static String stateMachineArn;
    private static KafkaEventConsumer consumer;
    private static AuditLogService auditLogService;

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
                .name("carddemo-consumer-it-" + UUID.randomUUID().toString().substring(0, 8))
                .definition(PASS_STATE_MACHINE)
                .roleArn(DUMMY_ROLE_ARN));
        stateMachineArn = response.stateMachineArn();

        auditLogService = Mockito.mock(AuditLogService.class);
        consumer = new KafkaEventConsumer(new StepFunctionsOrchestrator(sfnClient), auditLogService);
        ReflectionTestUtils.setField(consumer, "reportPipelineStateMachineArn", stateMachineArn);
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
    @DisplayName("onReportRequested starts Step Functions execution and acknowledges offset")
    void onReportRequestedStartsExecutionAndAcknowledges() {
        AtomicBoolean acknowledged = new AtomicBoolean(false);
        Acknowledgment acknowledgment = () -> acknowledged.set(true);
        ReportRequestDto request = new ReportRequestDto(
                "CUSTOM",
                LocalDate.parse("2026-05-01"),
                LocalDate.parse("2026-05-31"),
                "Y");

        consumer.onReportRequested(
                request,
                "report-123",
                "report.requested",
                0,
                42L,
                acknowledgment);

        ListExecutionsResponse executions = sfnClient.listExecutions(builder -> builder
                .stateMachineArn(stateMachineArn));
        assertThat(acknowledged.get()).isTrue();
        assertThat(executions.executions()).hasSize(1);
        verify(auditLogService, atLeastOnce()).logAuditEvent(
                eq("REPORT_REQUESTED_RECEIVED"),
                eq("REPORT"),
                eq("report-123"),
                eq("KAFKA_CONSUMER"),
                any(),
                any());
    }
}