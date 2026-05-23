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
package com.aws.carddemo.service;

/**
 * Asynchronous report-job dispatcher — the Java replacement for the
 * mainframe TDQ (Transient Data Queue) submission flow that the COBOL
 * {@code SUBMIT-JOB-TO-INTRDR} (lines 462-510) and {@code WIRTE-JOBSUB-TDQ}
 * (lines 515-535) paragraphs of {@code app/cbl/CORPT00C.cbl} implemented via
 * {@code EXEC CICS WRITEQ TD QUEUE('JOBS')}. The dispatcher accepts a
 * {@link ReportJobParameters} value object and returns an opaque
 * {@link ReportJobHandle} identifying the submitted job for downstream
 * status tracking.
 *
 * <h2>COBOL Provenance — CORPT00C.cbl</h2>
 *
 * <p>The COBOL workflow assembles a multi-record JCL stream (the
 * {@code JOB-DATA} 88-level group at lines 81-127 of CORPT00C.cbl) and
 * writes each record to the {@code JOBS} extra-partition TDQ via
 * {@code EXEC CICS WRITEQ TD} (lines 517-523). The mainframe JES system
 * monitors the {@code JOBS} TDQ and forwards each JCL record to the
 * internal reader, where the {@code TRNRPT00} job is queued for batch
 * execution. The COBOL program does not wait for the job to complete — the
 * write-to-TDQ is a fire-and-then-acknowledge handoff, with the
 * {@code DFHRESP(NORMAL)} response code being the acknowledgement.
 *
 * <p>The Java migration models this handoff as a single method call:
 * {@link #dispatch(ReportJobParameters)} accepts the parameter object,
 * persists the job into whichever downstream system the deployment
 * environment uses (Spring Batch {@code JobLauncher}, an Amazon SQS queue
 * dispatched to an AWS Lambda batch worker, or a Kafka topic consumed by a
 * dedicated batch service), and returns an opaque handle the caller can
 * use to query job status. Whether the dispatch is truly asynchronous or
 * appears so only to the caller is an implementation detail of the
 * specific {@code ReportJobDispatcher} bean wired in at runtime.
 *
 * <h2>Mock Boundary Per AAP §0.10.1</h2>
 *
 * <p>This interface is the mock boundary for
 * {@code ReportSubmissionServiceTest}. Per AAP §0.10.1 "Mocks limited to
 * external boundaries: file I/O, downstream service calls, database",
 * the dispatcher counts as a downstream-service-call boundary because the
 * production implementation invokes either Spring Batch's
 * {@code JobLauncher}, the AWS SDK's {@code SqsClient}, or another external
 * system. The unit tests mock this interface with Mockito's {@code @Mock}
 * annotation; integration tests substitute a real Spring Batch
 * implementation against a Testcontainers PostgreSQL job repository.
 *
 * <h2>Single-Method Interface — Functional Style</h2>
 *
 * <p>The interface has a single abstract method so it can be replaced by
 * a lambda or method reference in non-Spring contexts (test harnesses,
 * Spring Batch JUnit tests). The {@link FunctionalInterface @FunctionalInterface}
 * marker is applied to make this contract explicit and to ensure any
 * future evolution that would add a second abstract method generates a
 * compiler error rather than silently breaking lambda-based call sites.
 *
 * @see ReportJobParameters
 * @see ReportJobHandle
 * @see ReportSubmissionService
 */
@FunctionalInterface
public interface ReportJobDispatcher {

    /**
     * Submit the transaction-report batch job described by {@code parameters}
     * to the downstream dispatcher (Spring Batch {@code JobLauncher}, SQS,
     * Kafka, etc.) and return an opaque handle the caller can use to query
     * job status.
     *
     * <p>The contract is fire-and-acknowledge: the dispatcher must accept
     * the job (analogous to a successful {@code DFHRESP(NORMAL)} from
     * {@code EXEC CICS WRITEQ TD}) before returning, but it does not wait
     * for the job to complete. Callers that need completion semantics must
     * poll the dispatcher (or its downstream registry) using the returned
     * handle.
     *
     * <p>Infrastructure failures (queue unreachable, downstream service
     * unavailable, etc.) surface as {@link RuntimeException} subclasses;
     * the dispatcher does not return a "failure handle" — the absence of a
     * return is itself the signal that the dispatch failed. The
     * {@link ReportSubmissionService} does not catch these exceptions,
     * letting the controller layer's exception-handler chain produce the
     * Java equivalent of the COBOL {@code 'Unable to Write TDQ (JOBS)...'}
     * response (line 531-532 of CORPT00C.cbl).
     *
     * @param parameters the report parameters (job name, start/end date)
     *                   to dispatch; must be non-{@code null} with non-null
     *                   fields (the factory
     *                   {@link ReportJobParameters#of(String, java.time.LocalDate, java.time.LocalDate)}
     *                   enforces this invariant)
     * @return an opaque, non-{@code null} handle identifying the submitted
     *         job for downstream status lookups
     */
    ReportJobHandle dispatch(ReportJobParameters parameters);
}
