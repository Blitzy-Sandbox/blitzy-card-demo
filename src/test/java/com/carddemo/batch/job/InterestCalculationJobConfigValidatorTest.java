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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.batch.job.InterestCalculationJobConfig.InterestJobParametersValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;

/**
 * Unit tests for the interest-calculation job-parameter validation in
 * {@link InterestCalculationJobConfig}.
 *
 * <p>The {@code parmDate} parameter ({@code PARM-DATE PIC X(10)}) must be exactly
 * ten digits in {@code yyyyMMddHH} form: a strict eight-digit calendar date
 * followed by a two-digit hour {@code 00-23}. These tests assert that valid
 * values pass and that blank, missing, wrong-length, non-digit, impossible-date,
 * and out-of-range-hour values are rejected with a
 * {@link JobParametersInvalidException} before launch.</p>
 */
@DisplayName("InterestCalculationJobConfig — parmDate (yyyyMMddHH) validation")
class InterestCalculationJobConfigValidatorTest {

    private final InterestJobParametersValidator validator = new InterestJobParametersValidator();

    private static JobParameters parmDate(String value) {
        return new JobParametersBuilder()
                .addString(InterestCalculationJobConfig.RUN_DATE_PARAMETER_KEY, value)
                .toJobParameters();
    }

    @Test
    @DisplayName("accepts a well-formed yyyyMMddHH value")
    void acceptsWellFormedValue() {
        assertThatCode(() -> InterestCalculationJobConfig.validateRunDateParameter("2022071800"))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(parmDate("2022071800")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("accepts the boundary hour 23")
    void acceptsBoundaryHour() {
        assertThatCode(() -> InterestCalculationJobConfig.validateRunDateParameter("2022071823"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects a null value")
    void rejectsNull() {
        assertThatExceptionOfType(JobParametersInvalidException.class)
                .isThrownBy(() -> InterestCalculationJobConfig.validateRunDateParameter(null));
    }

    @Test
    @DisplayName("rejects a blank value")
    void rejectsBlank() {
        assertThatExceptionOfType(JobParametersInvalidException.class)
                .isThrownBy(() -> InterestCalculationJobConfig.validateRunDateParameter("   "));
    }

    @Test
    @DisplayName("rejects a missing parameter via the validator")
    void rejectsMissingViaValidator() {
        JobParameters none = new JobParametersBuilder().toJobParameters();
        assertThatExceptionOfType(JobParametersInvalidException.class)
                .isThrownBy(() -> validator.validate(none));
    }

    @Test
    @DisplayName("rejects a value shorter than ten digits")
    void rejectsTooShort() {
        assertThatExceptionOfType(JobParametersInvalidException.class)
                .isThrownBy(() -> InterestCalculationJobConfig.validateRunDateParameter("20220718"));
    }

    @Test
    @DisplayName("rejects a value longer than ten digits")
    void rejectsTooLong() {
        assertThatExceptionOfType(JobParametersInvalidException.class)
                .isThrownBy(() -> InterestCalculationJobConfig.validateRunDateParameter("202207180000"));
    }

    @Test
    @DisplayName("rejects a non-digit value")
    void rejectsNonDigits() {
        assertThatExceptionOfType(JobParametersInvalidException.class)
                .isThrownBy(() -> InterestCalculationJobConfig.validateRunDateParameter("20220718AB"));
    }

    @Test
    @DisplayName("rejects an impossible calendar date")
    void rejectsImpossibleDate() {
        assertThatExceptionOfType(JobParametersInvalidException.class)
                .isThrownBy(() -> InterestCalculationJobConfig.validateRunDateParameter("2022023000"));
    }

    @Test
    @DisplayName("rejects an out-of-range hour")
    void rejectsOutOfRangeHour() {
        assertThatExceptionOfType(JobParametersInvalidException.class)
                .isThrownBy(() -> InterestCalculationJobConfig.validateRunDateParameter("2022071824"));
    }
}
