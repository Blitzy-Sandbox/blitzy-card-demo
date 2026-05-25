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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.sfn.SfnClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lightweight Spring property-resolution test that asserts the
 * {@link StepFunctionsOrchestrator} class binds its
 * {@code reportPipelineArn} field to the configuration property key
 * {@code carddemo.aws.stepfunctions.report-pipeline-arn} (and explicitly
 * <strong>not</strong> to {@code carddemo.aws.stepfunctions.file-provisioning-arn}).
 *
 * <p>Regression coverage for CP7 Code Review Finding (Major):
 * "{@code StepFunctionsOrchestrator.reportPipelineArn} is populated from
 * {@code ${carddemo.aws.stepfunctions.file-provisioning-arn:}} and
 * {@code startReportPipeline()} reports the file-provisioning key as
 * missing." The remediation rewires the {@code @Value} annotation to the
 * correct {@code report-pipeline-arn} property and updates the error
 * message string. This test guarantees that the wire-up cannot
 * regress without the test failing.</p>
 *
 * <h2>Test strategy</h2>
 * <p>The test uses {@link SpringBootTest} with a minimal context
 * import so the @Value annotation is actually evaluated by the Spring
 * environment (the standalone Mockito unit test in
 * {@link StepFunctionsOrchestratorTest} sets the field directly via
 * {@code ReflectionTestUtils} and therefore cannot detect a property-
 * key regression).</p>
 *
 * <h2>Why a separate file</h2>
 * <p>The Mockito unit test purposely avoids loading any Spring context to
 * preserve its strict-stubbing semantics and sub-second runtime. This
 * file complements that test with a tiny @SpringBootTest slice scoped
 * solely to the {@code StepFunctionsOrchestrator} bean and a stub
 * {@code SfnClient}, so the slice loads in &lt; 1s and adds no real
 * AWS interaction.</p>
 *
 * @see StepFunctionsOrchestrator
 * @see StepFunctionsOrchestratorTest
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(
    classes = StepFunctionsOrchestratorPropertyResolutionTest.MinimalConfig.class,
    properties = {
        // Distinct sentinel values for the two ARN properties so the
        // test can prove the @Value field is wired to the correct
        // property key (a confusion would map the report field to the
        // file-provisioning sentinel and fail the assertion).
        "carddemo.aws.stepfunctions.eod-batch-pipeline-arn=arn:aws:states:us-east-1:000000000000:stateMachine:carddemo-eod",
        "carddemo.aws.stepfunctions.file-provisioning-arn=arn:aws:states:us-east-1:000000000000:stateMachine:carddemo-file-provisioning",
        "carddemo.aws.stepfunctions.report-pipeline-arn=arn:aws:states:us-east-1:000000000000:stateMachine:carddemo-report-pipeline"
    }
)
class StepFunctionsOrchestratorPropertyResolutionTest {

    @Autowired
    private StepFunctionsOrchestrator orchestrator;

    @MockBean
    private SfnClient sfnClient;

    /**
     * Asserts that the {@code @Value}-injected {@code reportPipelineArn}
     * field resolves to the report-pipeline property value (NOT the
     * file-provisioning property value).
     *
     * <p>If a future contributor accidentally rewires the {@code @Value}
     * annotation back to the file-provisioning key (the original CP7
     * defect), this assertion fails because the field value would be
     * the file-provisioning ARN sentinel, not the report-pipeline ARN
     * sentinel.</p>
     */
    @Test
    @DisplayName("reportPipelineArn field is wired to carddemo.aws.stepfunctions.report-pipeline-arn property key")
    void reportPipelineArn_isWiredToReportPipelineProperty() {
        Object actual = ReflectionTestUtils.getField(orchestrator, "reportPipelineArn");
        assertThat(actual)
                .as("reportPipelineArn must be wired to the report-pipeline-arn property key, not file-provisioning-arn")
                .isEqualTo("arn:aws:states:us-east-1:000000000000:stateMachine:carddemo-report-pipeline");
    }

    /**
     * Asserts that the {@code @Value}-injected {@code eodPipelineArn}
     * field resolves to the EOD property value &mdash; defensive coverage
     * to ensure the two property keys remain distinct and the
     * resolution mechanism is intact.
     */
    @Test
    @DisplayName("eodPipelineArn field is wired to carddemo.aws.stepfunctions.eod-batch-pipeline-arn property key")
    void eodPipelineArn_isWiredToEodProperty() {
        Object actual = ReflectionTestUtils.getField(orchestrator, "eodPipelineArn");
        assertThat(actual)
                .as("eodPipelineArn must be wired to the eod-batch-pipeline-arn property key")
                .isEqualTo("arn:aws:states:us-east-1:000000000000:stateMachine:carddemo-eod");
    }

    /**
     * Minimal Spring configuration that registers only the
     * {@link StepFunctionsOrchestrator} bean (and brings the
     * {@link MockBean}-supplied {@link SfnClient} into the context).
     * No other configuration class is loaded, keeping the slice tiny
     * and fast.
     */
    @org.springframework.boot.SpringBootConfiguration
    @Import(StepFunctionsOrchestrator.class)
    static class MinimalConfig {
        // Intentionally empty. @Import declares the bean to test;
        // @MockBean above injects the SfnClient collaborator.
    }
}
