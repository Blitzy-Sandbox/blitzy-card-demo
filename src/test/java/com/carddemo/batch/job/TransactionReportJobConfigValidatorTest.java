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

import com.carddemo.batch.job.TransactionReportJobConfig.ReportJobParametersValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;

/**
 * Unit tests for the transaction-report job-parameter validation in
 * {@link TransactionReportJobConfig}.
 *
 * <p>The {@code reportStartDate} and {@code reportEndDate} parameters carry the
 * inclusive reporting window and must both be strict {@code yyyy-MM-dd} calendar
 * dates with {@code start <= end}. These tests assert that valid windows
 * (including a single-day window) pass and that blank, missing, malformed,
 * impossible-date, and inverted ({@code start > end}) windows are rejected with a
 * {@link JobParametersInvalidException} before launch.</p>
 */
@DisplayName("TransactionReportJobConfig — reportStartDate/reportEndDate (yyyy-MM-dd) validation")
class TransactionReportJobConfigValidatorTest {

    private final ReportJobParametersValidator validator = new ReportJobParametersValidator();

    private static JobParameters window(String start, String end) {
        JobParametersBuilder builder = new JobParametersBuilder();
        if (start != null) {
            builder.addString(TransactionReportJobConfig.START_DATE_PARAM, start);
        }
        if (end != null) {
            builder.addString(TransactionReportJobConfig.END_DATE_PARAM, end);
        }
        return builder.toJobParameters();
    }

    @Test
    @DisplayName("accepts a well-formed ascending window")
    void acceptsWellFormedWindow() {
        assertThatCode(() -> TransactionReportJobConfig.validateReportDateWindow("2022-07-01", "2022-07-31"))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(window("2022-07-01", "2022-07-31")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("accepts a single-day window (start == end)")
    void acceptsSingleDayWindow() {
        assertThatCode(() -> TransactionReportJobConfig.validateReportDateWindow("2022-07-15", "2022-07-15"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects a null start bound")
    void rejectsNullStart() {
        assertThatExceptionOfType(JobParametersInvalidException.class)
                .isThrownBy(() -> TransactionReportJobConfig.validateReportDateWindow(null, "2022-07-31"));
    }

    @Test
    @DisplayName("rejects a blank end bound")
    void rejectsBlankEnd() {
        assertThatExceptionOfType(JobParametersInvalidException.class)
                .isThrownBy(() -> TransactionReportJobConfig.validateReportDateWindow("2022-07-01", "   "));
    }

    @Test
    @DisplayName("rejects a missing parameter via the validator")
    void rejectsMissingViaValidator() {
        assertThatExceptionOfType(JobParametersInvalidException.class)
                .isThrownBy(() -> validator.validate(window("2022-07-01", null)));
    }

    @Test
    @DisplayName("rejects a malformed (non-ISO) bound")
    void rejectsMalformedBound() {
        assertThatExceptionOfType(JobParametersInvalidException.class)
                .isThrownBy(() -> TransactionReportJobConfig.validateReportDateWindow("2022/07/01", "2022-07-31"));
    }

    @Test
    @DisplayName("rejects an eight-digit compact date")
    void rejectsCompactDate() {
        assertThatExceptionOfType(JobParametersInvalidException.class)
                .isThrownBy(() -> TransactionReportJobConfig.validateReportDateWindow("20220701", "2022-07-31"));
    }

    @Test
    @DisplayName("rejects an impossible month")
    void rejectsImpossibleMonth() {
        assertThatExceptionOfType(JobParametersInvalidException.class)
                .isThrownBy(() -> TransactionReportJobConfig.validateReportDateWindow("2022-13-01", "2022-12-31"));
    }

    @Test
    @DisplayName("rejects an impossible day")
    void rejectsImpossibleDay() {
        assertThatExceptionOfType(JobParametersInvalidException.class)
                .isThrownBy(() -> TransactionReportJobConfig.validateReportDateWindow("2022-02-30", "2022-03-31"));
    }

    @Test
    @DisplayName("rejects an inverted window (start > end)")
    void rejectsInvertedWindow() {
        assertThatExceptionOfType(JobParametersInvalidException.class)
                .isThrownBy(() -> TransactionReportJobConfig.validateReportDateWindow("2022-07-31", "2022-07-01"));
    }
}
