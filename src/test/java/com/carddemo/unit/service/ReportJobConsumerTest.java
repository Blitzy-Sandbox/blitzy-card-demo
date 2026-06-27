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
package com.carddemo.unit.service;

import com.carddemo.service.ReportJobConsumer;
import com.carddemo.service.ReportService.ReportRequestMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.launch.JobLauncher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link ReportJobConsumer}, the consumer half of the
 * F-011 report bridge that launches the Spring Batch {@code transactionReportJob} when a report
 * request arrives on the SQS FIFO queue (the Java realization of the CICS internal-reader trigger
 * of {@code CORPT00C} at source commit {@code 27d6c6f}).
 *
 * <p>These tests pin the behaviours that make the bridge correct and resilient:</p>
 * <ul>
 *   <li><strong>Job-parameter contract.</strong> The message window must be mapped onto the exact
 *       keys the job requires &mdash; {@code reportStartDate} and {@code reportEndDate} &mdash; not
 *       the {@code startDate}/{@code endDate} payload field names; a wrong key would fail the job's
 *       {@code ReportJobParametersValidator}.</li>
 *   <li><strong>Unique JobInstance.</strong> Every message carries a fresh {@code launchId} so a
 *       repeated window does not collide with a completed {@code JobInstance}.</li>
 *   <li><strong>FIFO head-of-line safety.</strong> The listener never propagates an exception, so a
 *       single failing launch cannot wedge the single {@code report-jobs} FIFO group.</li>
 * </ul>
 *
 * <p>The collaborators ({@link JobLauncher}, the {@code transactionReportJob} {@link Job}) are
 * mocked: this is a unit test of the consumer's mapping and error handling, while the full
 * submit&nbsp;&rarr;&nbsp;SQS&nbsp;&rarr;&nbsp;consume&nbsp;&rarr;&nbsp;batch&nbsp;&rarr;&nbsp;S3
 * round trip is verified against real infrastructure by {@code ReportJobConsumerIT}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReportJobConsumer unit tests — F-011 SQS->batch trigger (job-parameter mapping, launch, FIFO safety)")
class ReportJobConsumerTest {

    private static final String REPORT_NAME = "Custom";
    private static final String START_DATE = "2022-01-01";
    private static final String END_DATE = "2022-07-06";

    @Mock
    private JobLauncher jobLauncher;

    @Mock
    private Job transactionReportJob;

    @Captor
    private ArgumentCaptor<JobParameters> jobParametersCaptor;

    private ReportJobConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ReportJobConsumer(jobLauncher, transactionReportJob);
    }

    /** Builds a completed {@link JobExecution} (a real object, avoiding over-stubbing). */
    private static JobExecution completedExecution() {
        JobExecution execution = new JobExecution(1L);
        execution.setStatus(BatchStatus.COMPLETED);
        execution.setExitStatus(ExitStatus.COMPLETED);
        return execution;
    }

    @Test
    @DisplayName("launches transactionReportJob exactly once with the message window")
    void launchesTransactionReportJobOnce() throws Exception {
        when(jobLauncher.run(eq(transactionReportJob), any(JobParameters.class)))
                .thenReturn(completedExecution());

        consumer.onReportRequest(new ReportRequestMessage(REPORT_NAME, START_DATE, END_DATE));

        verify(jobLauncher, times(1)).run(eq(transactionReportJob), any(JobParameters.class));
    }

    @Test
    @DisplayName("CRITICAL: maps startDate/endDate onto the reportStartDate/reportEndDate job-parameter keys")
    void mapsWindowOntoRequiredJobParameterKeys() throws Exception {
        when(jobLauncher.run(eq(transactionReportJob), any(JobParameters.class)))
                .thenReturn(completedExecution());

        consumer.onReportRequest(new ReportRequestMessage(REPORT_NAME, START_DATE, END_DATE));

        verify(jobLauncher).run(eq(transactionReportJob), jobParametersCaptor.capture());
        JobParameters parameters = jobParametersCaptor.getValue();

        assertThat(parameters.getString("reportStartDate"))
                .as("message.startDate() must be passed under the 'reportStartDate' key the job validates")
                .isEqualTo(START_DATE);
        assertThat(parameters.getString("reportEndDate"))
                .as("message.endDate() must be passed under the 'reportEndDate' key the job validates")
                .isEqualTo(END_DATE);
        // The payload field names must NOT leak through as job-parameter keys.
        assertThat(parameters.getString("startDate"))
                .as("'startDate' is the payload field name, never a job-parameter key")
                .isNull();
        assertThat(parameters.getString("endDate"))
                .as("'endDate' is the payload field name, never a job-parameter key")
                .isNull();
        assertThat(parameters.getString("reportName"))
                .as("the resolved report name is carried for traceability")
                .isEqualTo(REPORT_NAME);
        assertThat(parameters.getString("launchId"))
                .as("a unique launchId guarantees a fresh JobInstance per submission")
                .isNotBlank();
    }

    @Test
    @DisplayName("assigns a distinct launchId to each message (unique JobInstance per submission)")
    void assignsUniqueLaunchIdPerMessage() throws Exception {
        when(jobLauncher.run(eq(transactionReportJob), any(JobParameters.class)))
                .thenReturn(completedExecution());

        consumer.onReportRequest(new ReportRequestMessage(REPORT_NAME, START_DATE, END_DATE));
        consumer.onReportRequest(new ReportRequestMessage(REPORT_NAME, START_DATE, END_DATE));

        verify(jobLauncher, times(2)).run(eq(transactionReportJob), jobParametersCaptor.capture());
        List<JobParameters> launches = jobParametersCaptor.getAllValues();
        String firstLaunchId = launches.get(0).getString("launchId");
        String secondLaunchId = launches.get(1).getString("launchId");

        assertThat(firstLaunchId).isNotBlank();
        assertThat(secondLaunchId).isNotBlank();
        assertThat(firstLaunchId)
                .as("each submission must launch a distinct JobInstance even for an identical window")
                .isNotEqualTo(secondLaunchId);
    }

    @Test
    @DisplayName("FIFO safety: a JobExecutionException during launch is swallowed (never rethrown)")
    void swallowsJobExecutionExceptionWithoutRethrowing() throws Exception {
        when(jobLauncher.run(eq(transactionReportJob), any(JobParameters.class)))
                .thenThrow(new JobParametersInvalidException("invalid parameters"));

        assertThatCode(() ->
                consumer.onReportRequest(new ReportRequestMessage(REPORT_NAME, START_DATE, END_DATE)))
                .as("a launch failure must not propagate, or it would wedge the FIFO report-jobs group")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("FIFO safety: an unexpected RuntimeException is swallowed (never rethrown)")
    void swallowsUnexpectedRuntimeExceptionWithoutRethrowing() throws Exception {
        when(jobLauncher.run(eq(transactionReportJob), any(JobParameters.class)))
                .thenThrow(new IllegalStateException("unexpected"));

        assertThatCode(() ->
                consumer.onReportRequest(new ReportRequestMessage(REPORT_NAME, START_DATE, END_DATE)))
                .as("an unexpected error must not propagate (head-of-line safety for the single FIFO group)")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a non-COMPLETED batch status is handled without throwing")
    void handlesNonCompletedExecutionWithoutThrowing() throws Exception {
        JobExecution failed = new JobExecution(2L);
        failed.setStatus(BatchStatus.FAILED);
        failed.setExitStatus(ExitStatus.FAILED);
        when(jobLauncher.run(eq(transactionReportJob), any(JobParameters.class))).thenReturn(failed);

        assertThatCode(() ->
                consumer.onReportRequest(new ReportRequestMessage(REPORT_NAME, START_DATE, END_DATE)))
                .as("a FAILED job status is logged, not rethrown")
                .doesNotThrowAnyException();
        verify(jobLauncher).run(eq(transactionReportJob), any(JobParameters.class));
    }
}
