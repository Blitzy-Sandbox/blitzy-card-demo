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
package com.carddemo.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.batch.job.BatchPipelineOrchestrator.PipelineJobParametersValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;

/**
 * Unit tests for the master-pipeline job-parameter contract in
 * {@link BatchPipelineOrchestrator}.
 *
 * <p>{@link BatchPipelineOrchestrator#pipelineJobParameters(String, String, String)}
 * validates the {@code parmDate} ({@code yyyyMMddHH}) and the
 * {@code reportStartDate}/{@code reportEndDate} window ({@code yyyy-MM-dd},
 * {@code start <= end}) before assembly, failing fast with an
 * {@link IllegalArgumentException}; the {@link PipelineJobParametersValidator}
 * re-applies the identical checks on the master job, raising a
 * {@link JobParametersInvalidException}. These tests assert both the fast-fail
 * builder behavior and the validator behavior across valid and malformed input.</p>
 */
@DisplayName("BatchPipelineOrchestrator — pipeline job-parameter validation")
class BatchPipelineOrchestratorValidatorTest {

    private final PipelineJobParametersValidator validator = new PipelineJobParametersValidator();

    @Test
    @DisplayName("assembles parameters when all bounds are valid")
    void assemblesValidParameters() {
        JobParameters parameters =
                BatchPipelineOrchestrator.pipelineJobParameters("2022071800", "2022-07-01", "2022-07-31");
        assertThat(parameters.getString(InterestCalculationJobConfig.RUN_DATE_PARAMETER_KEY))
                .isEqualTo("2022071800");
        assertThat(parameters.getString(TransactionReportJobConfig.START_DATE_PARAM))
                .isEqualTo("2022-07-01");
        assertThat(parameters.getString(TransactionReportJobConfig.END_DATE_PARAM))
                .isEqualTo("2022-07-31");
    }

    @Test
    @DisplayName("fails fast with IllegalArgumentException on malformed parmDate")
    void rejectsMalformedParmDate() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> BatchPipelineOrchestrator.pipelineJobParameters(
                        "20220718", "2022-07-01", "2022-07-31"));
    }

    @Test
    @DisplayName("fails fast with IllegalArgumentException on an inverted report window")
    void rejectsInvertedWindow() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> BatchPipelineOrchestrator.pipelineJobParameters(
                        "2022071800", "2022-07-31", "2022-07-01"));
    }

    @Test
    @DisplayName("fails fast with IllegalArgumentException on a malformed report bound")
    void rejectsMalformedReportBound() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> BatchPipelineOrchestrator.pipelineJobParameters(
                        "2022071800", "2022/07/01", "2022-07-31"));
    }

    @Test
    @DisplayName("fails fast with IllegalArgumentException on a null parmDate")
    void rejectsNullParmDate() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> BatchPipelineOrchestrator.pipelineJobParameters(
                        null, "2022-07-01", "2022-07-31"));
    }

    @Test
    @DisplayName("validator accepts a fully valid parameter set")
    void validatorAcceptsValidParameters() {
        JobParameters parameters =
                BatchPipelineOrchestrator.pipelineJobParameters("2022071800", "2022-07-01", "2022-07-31");
        assertThatCode(() -> validator.validate(parameters)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validator rejects null parameters")
    void validatorRejectsNull() {
        assertThatExceptionOfType(JobParametersInvalidException.class)
                .isThrownBy(() -> validator.validate(null));
    }
}
