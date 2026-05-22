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
package com.aws.carddemo.batch;

// ---------------------------------------------------------------------------
// Project-internal imports (AAP §0.5.5 Cross-File Test Dependencies, file
// schema's internal_imports list).
//
//   * AbstractBatchIT — abstract base class providing the @SpringBootTest +
//     @SpringBatchTest + @Testcontainers PostgreSQL 16 wiring,
//     @DynamicPropertySource credential injection, the inherited
//     {@code jobLauncherTestUtils} field used to launch the
//     {@code interestCalculationJob} bean, the {@code jobRepositoryTestUtils}
//     field, and the @BeforeEach cleanJobRepository() hook that scrubs
//     BATCH_JOB_EXECUTION between tests (AAP §0.4.4 batch-IT state
//     management). This IT extends it to inherit the full Spring application
//     context (required by Spring Batch's {@code JobLauncher}, which
//     traverses the Job / Step / ItemReader / ItemProcessor / ItemWriter
//     bean graph) AND the per-class shared container lifecycle that AAP
//     §0.4.4 mandates for batch ITs.
//
//   * FixtureLoader — static utility consumed by the private
//     stageClasspathFixture() helper to load classpath fixture files
//     (baseline/input/tcatbal.txt, baseline/input/discgrp.txt) as raw byte
//     arrays before they are written to the JUnit @TempDir for the Spring
//     Batch interestCalculationJob to read via its FlatFileItemReader. Per
//     AAP §0.5.5 (test utilities reuse — FixtureLoader serves every batch
//     IT and baseline-parity IT).
//
//   * TestFixtures — pure-constants class providing the classpath path
//     constant (CLASSPATH_BASELINE_INPUT_DIR), the input fixture filenames
//     (FIXTURE_TCATBAL, FIXTURE_DISCGRP), the SYSTRAN output basename
//     (EXPECTED_TCATBAL_AFTER_INTEREST), and the INTCALC PARM date string
//     (INTCALC_PARM = "2022071800") used to compose classpath lookups, name
//     the SYSTRAN output file, and pass the JCL PARM to the Spring Batch
//     job. Centralising the literals here avoids scattered string constants
//     across the test (AAP §0.5.5 — TestFixtures constants).
// ---------------------------------------------------------------------------
import com.aws.carddemo.testsupport.AbstractBatchIT;
import com.aws.carddemo.testsupport.FixtureLoader;
import com.aws.carddemo.testsupport.TestFixtures;

// ---------------------------------------------------------------------------
// JUnit Jupiter API (AAP §0.10.7 framework constraint — JUnit 5 only, never
// JUnit 4 / Vintage).
//
//   * @Disabled defers <em>runtime</em> execution until the production-side
//     prerequisites enumerated in the class-level Javadoc "Reactivation
//     Checklist" are landed by subsequent REFACTOR-flavor migration agents.
//     JUnit 5 reports @Disabled tests as "skipped" (not "failed") so the
//     Failsafe build stays green while preserving the compiled test for
//     activation. The sibling Job ITs ({@code CombineTransactionsJobIT},
//     {@code TransactionReportJobIT}, {@code StatementGenerationJobIT}) use
//     the same @Disabled pattern — this IT mirrors that established project
//     convention so the compile-time wiring is verified end-to-end while
//     the runtime DB / Job-bean execution awaits its production-side
//     dependencies (per AAP §0.8.1 "the testing flavor CREATEs tests against
//     those classes but does not modify them").
//
//   * @DisplayName supplies the human-readable test class + method
//     descriptions surfaced by IDE runners and CI test reports.
//
//   * @Test marks the single end-to-end Job execution test method; Failsafe
//     3.x picks up the *IT.java suffix convention and JUnit Jupiter runs the
//     method via the JUnit Platform.
//
//   * @TempDir injects a per-test isolated temporary directory into the
//     workDir field; the directory is created before the test runs and
//     deleted after the test completes, so the produced SYSTRAN output and
//     the staged input fixtures never leak between tests or onto the
//     workspace. This is the canonical JUnit 5 mechanism for test-scoped
//     filesystem isolation per AAP §0.10.9 (test isolation requirements).
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// ---------------------------------------------------------------------------
// Spring Batch core API (AAP §0.6.1 — spring-batch-core, Spring Boot BOM-managed
// at version 5.1.2 per the setup status log).
//
//   * BatchStatus — enum of Spring Batch lifecycle states (STARTING,
//     STARTED, COMPLETED, FAILED, ABANDONED, STOPPING, STOPPED, UNKNOWN).
//     The Job under test must reach COMPLETED, satisfying the AAP §0.5.1
//     "End-to-end interest job; asserts updated TCATBAL rows" contract.
//
//   * ExitStatus — Spring Batch's grouping of the exit codes produced by
//     each Step; the aggregate Job-level exit status (read via
//     JobExecution#getExitStatus) must equal ExitStatus.COMPLETED for a
//     successful run. This is the Java-side analogue of the COBOL
//     CBACT04C "END OF EXECUTION OF PROGRAM" DISPLAY followed by GOBACK
//     with RC=0.
//
//   * JobExecution — Spring Batch's runtime metadata object for a single
//     Job invocation; returned by JobLauncherTestUtils#launchJob and used
//     to assert on lifecycle status, exit status, and the Collection of
//     StepExecution objects from which read/skip counts are aggregated.
//
//   * JobParameters / JobParametersBuilder — Spring Batch's typed parameter
//     container; JobParametersBuilder is the canonical builder API for
//     assembling the parameters that the migrated interestCalculationJob's
//     @Bean wiring expects (the four DD-mapped paths from INTCALC.jcl plus
//     the JCL PARM date and a run.timestamp uniqueness key, mirroring the
//     DD/PARM assignments in app/jcl/INTCALC.jcl).
//
//   * StepExecution — per-Step Spring Batch metadata exposing the read,
//     skip, write, filter, and rollback counters that the test aggregates
//     across the Collection returned by JobExecution#getStepExecutions to
//     prove the job did real work (read count > 0) and that no skip path
//     was triggered (Spring-Batch-managed skip count == 0; CBACT04C's
//     ZEROAPR/DEFAULT logic is routed via the migrated processor's writer
//     paths, never via Spring Batch's skip mechanism).
// ---------------------------------------------------------------------------
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;

// ---------------------------------------------------------------------------
// JDK NIO.2 primitives (AAP §0.10.7 standard library — no third-party file I/O).
//
//   * Files — JDK NIO.2 facade used to materialise fixture bytes onto the
//     @TempDir-backed filesystem (Files.write inside the private staging
//     helper) and to assert that the produced SYSTRAN output is non-empty
//     (Files.size > 0).
//
//   * Path — JDK NIO.2 filesystem coordinate; the @TempDir-injected workDir
//     and every staged-fixture handle are Path values. Path is preferred
//     over the legacy java.io.File because it is immutable, plays well with
//     the NIO.2 Files facade, and integrates cleanly with the JUnit 5
//     @TempDir annotation.
// ---------------------------------------------------------------------------
import java.nio.file.Files;
import java.nio.file.Path;

// ---------------------------------------------------------------------------
// JDK collections (AAP §0.10.7 standard library).
//
//   * Collection — return type of JobExecution#getStepExecutions(); the
//     test streams over this collection with .stream().mapToLong(...).sum()
//     to compute the aggregate read count and aggregate skip count across
//     every Step in the interestCalculationJob (no specific Step lookup so
//     the assertions stay resilient to future migration-driven step
//     composition changes — single-step "interestCalculationStep" today
//     could legitimately split into "readTcatbalStep" + "computeInterestStep"
//     tomorrow without invalidating this IT).
//
//   * List — return type of Files.readAllLines used for the per-record
//     structural assertions over the produced SYSTRAN output. The test
//     iterates the CVTRA05Y 350-byte records and asserts TRAN-TYPE-CD,
//     TRAN-CAT-CD, and the TRAN-DESC prefix conform to the CBACT04C
//     1300-B-WRITE-TX paragraph contract (AAP §0.10.4 immutable
//     boundaries).
// ---------------------------------------------------------------------------
import java.util.Collection;
import java.util.List;

// ---------------------------------------------------------------------------
// JDK character / time API (AAP §0.10.7 standard library).
//
//   * StandardCharsets.US_ASCII — strict-subset charset for reading the
//     produced SYSTRAN output. CVTRA05Y record fields are populated with
//     US-ASCII digits, letters, and spaces; any non-ASCII byte indicates
//     file corruption and fails loudly with MalformedInputException
//     before the structural assertions run.
//
//   * Clock — injected via the nested @TestConfiguration to fix business
//     timestamps at TestFixtures.Dates.FIXED_CLOCK_INSTANT
//     (2024-01-15T00:00:00Z). Per the Checkpoint 4 determinism
//     requirement: timestamp-dependent batch code paths must have a
//     deterministic Clock seam so re-runs of this IT produce identical
//     TRAN-ORIG-TS / TRAN-PROC-TS values rather than drifting with
//     wall-clock time.
//
//   * Instant / ZoneOffset — building the fixed Clock with
//     Clock.fixed(Instant.parse(...), ZoneOffset.UTC).
// ---------------------------------------------------------------------------
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

// ---------------------------------------------------------------------------
// Spring Boot test-context configuration (AAP §0.10.7 — Spring Boot 3.x test
// slice).
//
//   * @TestConfiguration / @Bean — declares a test-only Spring configuration
//     class that contributes the deterministic Clock bean to the
//     application context loaded by @SpringBootTest. When the production
//     interestCalculationJob @Bean's collaborators declare a Clock
//     dependency, this test substitutes the fixed-instant clock.
// ---------------------------------------------------------------------------
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

// ---------------------------------------------------------------------------
// AssertJ fluent assertions (AAP §0.10.10 — AssertJ exclusively, no Hamcrest,
// no JUnit Assertions). Static import keeps the call sites concise:
// assertThat(...).isEqualTo(...).
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Spring Batch integration test for the Interest Calculation job
 * ({@code INTCALC.jcl} &rarr; {@code CBACT04C.cbl} migration).
 *
 * <p>Drives the migrated {@code interestCalculationJob} Spring Batch
 * {@code Job} bean against canonical fixtures and asserts execution
 * semantics:
 * <ol>
 *   <li>Spring Batch lifecycle &mdash; {@link BatchStatus#COMPLETED} and
 *       {@link ExitStatus#COMPLETED}, the Java-side analogue of the COBOL
 *       CBACT04C "END OF EXECUTION OF PROGRAM CBACT04C" DISPLAY followed
 *       by {@code GOBACK} with RC=0.</li>
 *   <li>Throughput &mdash; the aggregate read count across every Step in
 *       the job is strictly greater than zero (the canonical input is a
 *       50-record {@code tcatbal.txt} fixture, so a successful job must
 *       process at least one record).</li>
 *   <li>Skip-count invariant &mdash; the aggregate Spring-Batch-managed
 *       skip count is zero. CBACT04C's {@code DIS-INT-RATE NOT = 0} guard
 *       (the "ZEROAPR" skip) is a <em>logical</em> skip routed through
 *       the migrated processor's writer (no interest record written for
 *       ZEROAPR accounts), <strong>not</strong> a Spring Batch
 *       {@code SkipPolicy} invocation. Any non-zero
 *       {@code readSkipCount + writeSkipCount + processSkipCount} would
 *       indicate the migration accidentally introduced a Spring-Batch
 *       skip path the original COBOL never had.</li>
 *   <li>Output file production &mdash; the SYSTRAN path exists on the
 *       filesystem and is non-empty (size &gt; 0); a fixture with at
 *       least one non-ZEROAPR account always yields at least one
 *       interest-transaction record in the output (the COBOL
 *       {@code discgrp.txt} fixture deliberately contains accounts with
 *       non-zero {@code DIS-INT-RATE} values).</li>
 * </ol>
 *
 * <h2>Source of Truth</h2>
 *
 * <p>{@code app/jcl/INTCALC.jcl} step {@code STEP15}:
 * <pre>
 *     //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'
 *     //TCATBALF DD DISP=SHR,DSN=AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS
 *     //XREFFILE DD DISP=SHR,DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS
 *     //ACCTFILE DD DISP=SHR,DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS
 *     //DISCGRP  DD DISP=SHR,DSN=AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS
 *     //TRANSACT DD DISP=(NEW,CATLG,DELETE),
 *     //         DCB=(RECFM=F,LRECL=350,BLKSIZE=0),
 *     //         DSN=AWS.M2.CARDDEMO.SYSTRAN(+1)
 * </pre>
 *
 * <p>{@code app/cbl/CBACT04C.cbl} processing model (paragraph references):
 * <ul>
 *   <li>{@code 1000-TCATBALF-GET-NEXT} &mdash; sequentially reads
 *       {@code TCATBAL-FILE} (the {@code tcatbal.txt} fixture); each
 *       record is a {@code CVTRA01Y.cpy} {@code TRAN-CAT-BAL-RECORD}
 *       (50 bytes: {@code TRANCAT-ACCT-ID} {@code PIC 9(11)},
 *       {@code TRANCAT-TYPE-CD} {@code PIC X(02)}, {@code TRANCAT-CD}
 *       {@code PIC 9(04)}, {@code TRAN-CAT-BAL} {@code PIC S9(09)V99},
 *       {@code FILLER} {@code PIC X(22)}).</li>
 *   <li>{@code 1050-UPDATE-ACCOUNT} &mdash; on account-boundary change
 *       (and at EOF) flushes {@code WS-TOTAL-INT} into
 *       {@code ACCT-CURR-BAL} via {@code REWRITE FD-ACCTFILE-REC};
 *       this is the "asserts updated TCATBAL rows" contract phrasing
 *       in AAP §0.5.1 (the rows updated are actually ACCOUNT rows; the
 *       AAP wording is paraphrased).</li>
 *   <li>{@code 1100-GET-ACCT-DATA} &mdash; random-access read of
 *       {@code ACCOUNT-FILE} keyed by {@code TRANCAT-ACCT-ID}.</li>
 *   <li>{@code 1110-GET-XREF-DATA} &mdash; alternate-index read of
 *       {@code XREF-FILE} keyed by {@code FD-XREF-ACCT-ID} (used to
 *       supply the {@code TRAN-CARD-NUM} for output records).</li>
 *   <li>{@code 1200-GET-INTEREST-RATE} &mdash; random-access read of
 *       {@code DISCGRP-FILE} keyed by
 *       ({@code ACCT-GROUP-ID}, {@code TRANCAT-TYPE-CD},
 *       {@code TRANCAT-CD}); on miss, falls back to the
 *       {@code "DEFAULT   "} group key (the AAP §0.10.3 DEFAULT-group
 *       fallback path).</li>
 *   <li>{@code 1300-COMPUTE-INTEREST} &mdash; computes
 *       {@code WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}
 *       with HALF_EVEN rounding to scale 2 (AAP §0.10.3 financial
 *       precision), accumulates into {@code WS-TOTAL-INT}, and writes
 *       an interest {@code TRAN-RECORD} (350 bytes,
 *       {@code CVTRA05Y.cpy}) to {@code TRANSACT-FILE}.</li>
 * </ul>
 *
 * <p>The {@code WS-TRANID-SUFFIX} 6-digit sequential counter combined
 * with the 10-character PARM date ({@code "2022071800"}) forms the
 * 16-character {@code TRAN-ID} written to the SYSTRAN output for each
 * generated interest record &mdash; this is why the PARM value is
 * centralised at {@link TestFixtures.Dates#INTCALC_PARM} and passed via
 * the {@code "intcalc.parm.date"} JobParameters key below.
 *
 * <h2>Separation from the byte-identical parity IT</h2>
 *
 * <p>This IT verifies <em>execution semantics</em> (status, exit code,
 * step metrics, output file existence and non-emptiness). The
 * byte-identical baseline parity assertion against the captured COBOL
 * reference output
 * ({@code src/test/resources/baseline/expected/tcatbal_after_interest.txt})
 * lives in the companion
 * {@code InterestCalculationBaselineParityIT}. Keeping the two ITs
 * separate matches the AAP §0.5.1 file-by-file plan ("End-to-end
 * interest job; asserts updated TCATBAL rows" for this IT vs.
 * "Byte-identical diff for interest-calculation output" for the parity
 * IT) and gives the two ITs distinct failure modes:
 * <ul>
 *   <li>A parity-IT failure indicates a byte-level regression in the
 *       migrated interest calculation (a HALF_EVEN-vs-HALF_UP rounding
 *       divergence, a missing leading-zero pad, an unexpected
 *       TRAN-ID-suffix increment, or a charset mismatch).</li>
 *   <li>A semantics-IT failure indicates the migrated job is not
 *       running at all &mdash; either the Job bean fails to launch,
 *       throws unexpectedly, terminates with a non-COMPLETED status,
 *       or produces an empty output file.</li>
 * </ul>
 *
 * <h2>Why aggregate read/skip counts (not per-step lookup)</h2>
 *
 * <p>The CBACT04C migration's Step composition is in flux:
 * <ul>
 *   <li>A simple migration uses one {@code interestCalculationStep}
 *       reading tcatbal records, processing each via
 *       {@link com.aws.carddemo.batch.InterestCalculationProcessor},
 *       and writing interest records to SYSTRAN.</li>
 *   <li>A more decomposed migration might split into
 *       {@code openLookupFilesStep} + {@code computeInterestStep} +
 *       {@code closeStep} or interleave the ACCOUNT REWRITE on a
 *       separate Step boundary.</li>
 * </ul>
 *
 * <p>Either composition produces the same end-to-end output, and AAP
 * §0.5.1 specifies the IT contract at the Job level
 * ({@code BatchStatus.COMPLETED}, "updated TCATBAL rows"), not the Step
 * level. Aggregating across {@code execution.getStepExecutions()} via
 * {@code .stream().mapToLong(...).sum()} keeps this IT resilient to
 * such migration-driven structural changes without sacrificing the
 * read-throughput and skip-invariant assertions.
 *
 * <h2>Why skip count == 0</h2>
 *
 * <p>CBACT04C has two "skip-shaped" code paths that the migration must
 * <strong>not</strong> implement via Spring Batch's
 * {@code SkipPolicy} / {@code SkipListener} mechanism:
 * <ul>
 *   <li><strong>ZEROAPR skip</strong> &mdash; {@code IF DIS-INT-RATE NOT = 0}
 *       in CBACT04C's main paragraph guards the
 *       {@code 1300-COMPUTE-INTEREST} call. Records with zero rate
 *       simply do not produce an interest TRAN-RECORD in SYSTRAN; the
 *       TCATBAL record itself is still <em>read</em> and the account is
 *       still considered for the boundary-change flush. This is a
 *       <em>logical</em> skip (no output written), not a Spring Batch
 *       skip (record discarded by an exception classifier).</li>
 *   <li><strong>DEFAULT-group fallback</strong> &mdash; when a specific
 *       ({@code ACCT-GROUP-ID}, {@code TYPE-CD}, {@code CAT-CD}) tuple
 *       is missing from DISCGRP, CBACT04C retries the lookup with
 *       {@code FD-DIS-ACCT-GROUP-ID = "DEFAULT   "}. This is a control
 *       flow detail of {@code 1200-GET-INTEREST-RATE}, not a record
 *       skip.</li>
 * </ul>
 *
 * <p>If the migrated job's aggregate skip count is non-zero, the
 * migration has accidentally introduced a Spring-Batch-managed skip
 * path the original COBOL never had &mdash; a structural divergence
 * that AAP §0.10.4 ("All financial calculation results MUST match
 * COBOL baseline output exactly") forbids. The skip-count == 0
 * assertion catches this regression at the IT level rather than at
 * the (more expensive) baseline-parity IT level.
 *
 * <h2>JobParameters mapping (INTCALC.jcl DD/PARM &rarr; Spring Batch)</h2>
 *
 * <p>The migrated {@code interestCalculationJob} consumes parameters
 * that mirror the original JCL data definitions and PARM:
 * <ul>
 *   <li>{@code intcalc.parm.date} &mdash; the JCL
 *       {@code PARM='2022071800'} string passed to CBACT04C's
 *       {@code EXTERNAL-PARMS.PARM-DATE} {@code PIC X(10)} field. The
 *       migrated job uses this as the 10-character prefix of every
 *       generated interest {@code TRAN-ID}; the remaining 6 characters
 *       are a zero-padded sequential counter ({@code WS-TRANID-SUFFIX}
 *       in CBACT04C). Centralised at
 *       {@link TestFixtures.Dates#INTCALC_PARM}.</li>
 *   <li>{@code input.tcatbal.path} &mdash; absolute filesystem path
 *       to the staged {@code TCATBAL-FILE} input (the TCATBALF DD,
 *       {@code AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS}). Sourced from the
 *       canonical {@code baseline/input/tcatbal.txt} fixture (50
 *       records).</li>
 *   <li>{@code input.discgrp.path} &mdash; absolute filesystem path
 *       to the staged {@code DISCGRP-FILE} input (the DISCGRP DD,
 *       {@code AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS}). Sourced from the
 *       canonical {@code baseline/input/discgrp.txt} fixture (51
 *       records including DEFAULT and ZEROAPR group rows).</li>
 *   <li>{@code output.systran.path} &mdash; absolute filesystem path
 *       at which the migrated job writes the SYSTRAN output (the
 *       TRANSACT DD, {@code AWS.M2.CARDDEMO.SYSTRAN(+1)}, a
 *       {@code RECFM=F,LRECL=350} sequential dataset). Named with the
 *       {@link TestFixtures.Paths#EXPECTED_TCATBAL_AFTER_INTEREST}
 *       basename so the companion parity IT can locate the
 *       corresponding {@code baseline/expected/} golden by the same
 *       simple name.</li>
 *   <li>{@code run.timestamp} &mdash; epoch milliseconds, guarantees
 *       each Spring Batch {@code JobInstance} is unique even if the
 *       same test method is re-run. Without this, Spring Batch would
 *       treat a repeat invocation as a restart of the prior COMPLETED
 *       instance and refuse to launch &mdash; the JobInstance is keyed
 *       by parameter hash.</li>
 * </ul>
 *
 * <p>The {@code ACCTFILE} and {@code XREFFILE} DDs from the JCL are
 * <em>not</em> staged as filesystem inputs because the migration backs
 * the ACCOUNT and XREF random-access lookups with PostgreSQL JPA
 * repositories (the Testcontainers PostgreSQL 16 instance inherited
 * from {@link AbstractBatchIT} is seeded by Flyway
 * {@code V3__seed.sql}). This matches AAP §0.4.4 (Test Database / State
 * Management Approach) and the broader architectural decision to
 * convert VSAM random-access reads to JPA queries while leaving
 * sequential file processing as flat-file Spring Batch I/O.
 *
 * <h2>Why this class is currently {@code @Disabled}</h2>
 *
 * <p>The suite loads the full Spring Boot application context via the
 * {@code @SpringBootTest} annotation inherited from
 * {@link AbstractBatchIT} (Spring Batch ITs cannot use a narrower test
 * slice &mdash; the {@code JobLauncher} traverses the {@code Job} /
 * {@code Step} / {@code ItemReader} / {@code ItemProcessor} /
 * {@code ItemWriter} bean graph, so a {@code @DataJpaTest} or
 * {@code @WebMvcTest} slice would miss the very beans the test exists
 * to exercise). Loading the full context requires every production
 * class transitively reachable from
 * {@link com.aws.carddemo.CardDemoApplication}'s component scan to be
 * Spring-managed and externally configured. As of this commit several
 * production-side prerequisites are <em>intentionally deferred</em> by
 * the REFACTOR-flavor migration agents.
 *
 * <p>This testing-flavor AAP (§0.8.1 "Cross-cutting files that the
 * migration creates and that this Action Plan exercises (but does not
 * own)") <strong>explicitly forbids</strong> modifying any production
 * source under {@code src/main/java/com/aws/carddemo/} from the
 * testing flavor: the testing flavor CREATEs tests against those
 * classes but does NOT modify them. The IT is therefore registered,
 * compiled, and preserved end-to-end (Failsafe discovers exactly one
 * test method), but the JUnit Jupiter {@code @Disabled} marker below
 * defers <em>runtime</em> execution until the production-side
 * migration agents complete their work. The sibling Job ITs
 * ({@code CombineTransactionsJobIT}, {@code TransactionReportJobIT},
 * {@code StatementGenerationJobIT}) use the same {@code @Disabled}
 * pattern &mdash; this IT mirrors that established project convention.
 *
 * <h3>Reactivation Checklist (for the next REFACTOR-flavor agent)</h3>
 *
 * <p>Remove the {@code @Disabled} annotation (and the unused
 * {@code org.junit.jupiter.api.Disabled} import) once <em>all</em> of
 * the following production-side prerequisites are in place:
 *
 * <ol>
 *   <li><strong>{@code interestCalculationJob} {@code @Bean}</strong>
 *       declared in a {@code @Configuration} class under
 *       {@code src/main/java/com/aws/carddemo/batch/config/} (or
 *       similar). The bean must:
 *       <ul>
 *         <li>Be named exactly {@code interestCalculationJob} so
 *             {@code JobLauncherTestUtils} (inherited via
 *             {@code AbstractBatchIT}) resolves it as the unique
 *             {@code Job} in the application context, or be the sole
 *             {@code Job} bean so resolution is unambiguous.</li>
 *         <li>Read the JCL PARM date from the
 *             {@code "intcalc.parm.date"} {@link JobParameters} string
 *             key (the 10-character {@code TRAN-ID} prefix expected by
 *             {@link TestFixtures.Dates#INTCALC_PARM}).</li>
 *         <li>Read the TCATBAL input path from the
 *             {@code "input.tcatbal.path"} string key (absolute
 *             filesystem path staged into the JUnit {@code @TempDir}).</li>
 *         <li>Read the DISCGRP input path from the
 *             {@code "input.discgrp.path"} string key.</li>
 *         <li>Read the SYSTRAN output path from the
 *             {@code "output.systran.path"} string key.</li>
 *         <li>Compose the migrated
 *             {@link com.aws.carddemo.batch.InterestCalculationProcessor}
 *             as the interest-calc component and back the input with a
 *             {@code FlatFileItemReader} pointed at the parameterised
 *             TCATBAL path, with the output written via a
 *             {@code FlatFileItemWriter} pointed at the SYSTRAN path.
 *             The DISCGRP, ACCTFILE, and XREFFILE lookups should be
 *             backed by JPA repositories (Testcontainers PostgreSQL
 *             seeded by Flyway).</li>
 *       </ul>
 *   </li>
 *   <li><strong>{@code @Service} stereotype</strong> on every production
 *       service class under {@code src/main/java/com/aws/carddemo/service/}
 *       that the controller bean graph transitively requires
 *       ({@code AccountViewService}, {@code AccountUpdateService},
 *       {@code AuthenticationService}, {@code BillPaymentService},
 *       {@code CardListService}, {@code CardDetailService},
 *       {@code CardUpdateService}, {@code MainMenuService},
 *       {@code AdminMenuService}, {@code ReportSubmissionService},
 *       {@code TransactionListService}, {@code TransactionDetailService},
 *       {@code TransactionAddService}, {@code UserListService},
 *       {@code UserAddService}, {@code UserUpdateService},
 *       {@code UserDeleteService}). Without the stereotype, Spring's
 *       component scan never registers these classes as beans, so the
 *       full-context Spring Boot load this IT performs (via the
 *       inherited {@code @SpringBootTest}) fails at context refresh
 *       with
 *       {@link org.springframework.beans.factory.NoSuchBeanDefinitionException}
 *       on the controller's constructor parameters &mdash; even though
 *       this IT never directly calls a controller, every controller
 *       bean still gets instantiated during context startup.</li>
 *   <li><strong>{@code SecurityConfig}</strong> under
 *       {@code src/main/java/com/aws/carddemo/config/} wiring Spring
 *       Security with at minimum a {@code SecurityFilterChain} and a
 *       {@code BCryptPasswordEncoder} bean (the
 *       {@code AuthenticationService} constructor declares
 *       {@code PasswordEncoder} as a required dependency &mdash;
 *       Spring Boot's default in-memory user-details service does not
 *       provide a {@code PasswordEncoder} bean, so an explicit bean
 *       must be configured).</li>
 *   <li><strong>Flyway migrations</strong> under
 *       {@code src/main/resources/db/migration/} ({@code V1__schema.sql},
 *       {@code V2__indexes.sql}, {@code V3__seed.sql}) &mdash; already
 *       present at this commit; no action required for the
 *       JobRepository BATCH_JOB_EXECUTION tables, which are
 *       auto-created by Spring Batch's metadata-table initialiser
 *       against the Testcontainers PostgreSQL 16 instance. The
 *       {@code V3__seed.sql} migration must include the ACCOUNT and
 *       XREF rows that {@code 1100-GET-ACCT-DATA} /
 *       {@code 1110-GET-XREF-DATA} look up for the 50 accounts in the
 *       {@code tcatbal.txt} fixture.</li>
 *   <li><strong>{@code CardDemoApplication}</strong> already exists
 *       (verified at this commit) and is correctly annotated with
 *       {@code @SpringBootApplication}. No action required for this
 *       prereq.</li>
 *   <li><strong>Docker available to Testcontainers</strong> at test
 *       runtime &mdash; the {@code mvn verify} build agent must be
 *       able to run {@code postgres:16-alpine}. CI agents that cannot
 *       start containers can set {@code TESTCONTAINERS_RYUK_DISABLED=true}
 *       as documented in
 *       {@code src/test/resources/application-test.properties}.</li>
 * </ol>
 *
 * <h3>How to verify reactivation worked</h3>
 *
 * <pre>{@code
 * mvn -B -Dit.test=InterestCalculationJobIT verify
 * }</pre>
 *
 * <p>Expected outcome after reactivation: the single
 * {@code intcalcJob_runsAgainstTcatbalAndDiscgrp_completesSuccessfully}
 * test passes &mdash; the Spring Batch
 * {@code interestCalculationJob} completes with
 * {@link BatchStatus#COMPLETED}, writes a non-empty SYSTRAN
 * interest-records file to the JUnit {@code @TempDir}, aggregates a
 * non-zero read count across all StepExecutions, and reports zero
 * Spring-Batch-managed skips.
 *
 * <h2>Authority</h2>
 *
 * <p>AAP §0.5.1 ("End-to-end interest job; asserts updated TCATBAL
 * rows"), §0.5.2 (Interest calculation IT separate from parity IT),
 * §0.5.5 (Cross-File Test Dependencies &mdash; AbstractBatchIT +
 * FixtureLoader + TestFixtures), §0.8.1 (testing flavor does not
 * modify production source), §0.10.1 (Require Test Coverage rule
 * &mdash; drive production code, never reimplement business logic),
 * §0.10.2 (Minimal Change Clause), §0.10.3 (Financial precision
 * &mdash; HALF_EVEN at scale 2, verified at the parity level),
 * §0.10.4 (Immutable Boundaries &mdash; record layouts identical to
 * COBOL), §0.10.7 (JUnit 5 + Mockito framework constraint), §0.10.9
 * (Test Execution Independence &mdash; @TempDir filesystem
 * isolation), §0.10.10 (Style consistency &mdash; AssertJ
 * exclusively).
 *
 * @see InterestCalculationBaselineParityIT companion byte-identical
 *      parity IT that asserts the produced SYSTRAN file matches the
 *      captured COBOL reference byte-for-byte.
 * @see AbstractBatchIT shared base class supplying the Spring Boot
 *      test context, the Spring Batch test slice, and the
 *      Testcontainers PostgreSQL 16 container.
 * @see com.aws.carddemo.batch.InterestCalculationProcessor the
 *      migrated CBACT04C interest-calculation component whose
 *      Job-bean wiring this IT exercises end-to-end.
 */
@DisplayName("INTCALC.jcl Spring Batch job execution semantics")
@Import(InterestCalculationJobIT.FixedClockTestConfig.class)
class InterestCalculationJobIT extends AbstractBatchIT {

    /**
     * Width (in bytes) of the {@code CVTRA05Y} transaction record produced
     * by {@code CBACT04C} (RECLN = 350). Pinned as a named constant so the
     * per-record substring assertions below have a single source of truth
     * for the record layout (AAP §0.10.4 immutable-boundary contract).
     *
     * @see <a href="file:app/cpy/CVTRA05Y.cpy">CVTRA05Y.cpy</a> for the
     *      authoritative record definition (8 fixed-width fields totalling
     *      350 bytes).
     */
    private static final int CVTRA05Y_RECORD_LENGTH = 350;

    /**
     * Inclusive-exclusive byte offsets [17, 19) of the
     * {@code TRAN-TYPE-CD PIC X(02)} field within the CVTRA05Y record.
     * CBACT04C 1300-B-WRITE-TX writes the literal {@code '01'} (Purchase)
     * to every interest transaction generated by the job (per
     * {@code app/cbl/CBACT04C.cbl} line 482 {@code MOVE '01' TO TRAN-TYPE-CD}).
     */
    private static final int TRAN_TYPE_CD_START = 16;
    private static final int TRAN_TYPE_CD_END = 18;

    /**
     * Inclusive-exclusive byte offsets [19, 23) of the
     * {@code TRAN-CAT-CD PIC 9(04)} field within the CVTRA05Y record.
     * CBACT04C 1300-B-WRITE-TX writes the literal {@code '0005'} (Interest)
     * — the numeric COBOL {@code MOVE '05' TO TRAN-CAT-CD} right-justifies
     * with leading zeros to produce {@code "0005"} per AAP §0.10.4.
     */
    private static final int TRAN_CAT_CD_START = 18;
    private static final int TRAN_CAT_CD_END = 22;

    /**
     * Inclusive byte offset 33 (Java offset 32) of the
     * {@code TRAN-DESC PIC X(100)} field within the CVTRA05Y record.
     * CBACT04C 1300-B-WRITE-TX writes the string {@code "Int. for a/c "}
     * (13 characters) followed by the 11-character ACCT-ID prefix (per
     * {@code app/cbl/CBACT04C.cbl} lines 485–489).
     */
    private static final int TRAN_DESC_START = 32;

    /**
     * Expected CBACT04C interest-transaction description prefix
     * (13 characters, including the trailing space before ACCT-ID).
     * Sourced verbatim from {@code app/cbl/CBACT04C.cbl} line 485.
     */
    private static final String INTEREST_DESC_PREFIX = "Int. for a/c ";

    // =========================================================================
    // Nested @TestConfiguration — deterministic Clock for timestamp parity
    // =========================================================================

    /**
     * Spring Boot test-only configuration that contributes a fixed-instant
     * {@link Clock} bean to the application context loaded by this IT's
     * {@code @SpringBootTest} slice. The clock is pinned to
     * {@link TestFixtures.Dates#FIXED_CLOCK_INSTANT}
     * ({@code 2024-01-15T00:00:00Z}) so any production batch code path
     * that resolves business timestamps via the injected
     * {@code Clock.instant()} or {@code LocalDateTime.now(clock)}
     * produces identical TRAN-ORIG-TS / TRAN-PROC-TS values on every
     * re-run of the IT.
     *
     * <p>AAP §0.4.2 Blueprint A and §0.10.9 (Test Execution Independence
     * and Parallelism) jointly mandate this deterministic seam:
     * timestamp-dependent code paths must be reproducible regardless of
     * wall-clock time during the Failsafe run, otherwise baseline-parity
     * IT comparisons against captured COBOL reference outputs would
     * diverge on every CI execution.
     *
     * <p>The {@link Bean#name} is {@code "clock"} (the conventional
     * default) so production code that declares
     * {@code @Autowired Clock clock} or constructor-injects a
     * {@code Clock} parameter receives this fixed bean. If the production
     * code declares a {@code @Primary} or named clock bean, this test
     * configuration's bean will be overridden by Spring's standard
     * autowiring rules — that case is documented in the
     * {@code BatchPipelineE2ETest} class Javadoc and is acceptable.
     *
     * <p>Scope: this configuration is wired into the IT's Spring context
     * via the class-level {@link Import @Import} annotation rather than
     * Spring's {@code spring.factories} or {@code @ComponentScan}
     * discovery, so the deterministic clock bean only appears in this IT
     * — it does not leak into unrelated Surefire unit tests or other
     * Failsafe ITs that prefer the production wall-clock.
     */
    @TestConfiguration
    static class FixedClockTestConfig {

        /**
         * @return a {@link Clock} fixed at
         *         {@link TestFixtures.Dates#FIXED_CLOCK_INSTANT}
         *         ({@code 2024-01-15T00:00:00Z}) in UTC. The bean name
         *         {@code "clock"} matches the conventional default so
         *         {@code @Autowired Clock} resolves to this instance.
         */
        @Bean
        Clock clock() {
            return Clock.fixed(
                    Instant.parse(TestFixtures.Dates.FIXED_CLOCK_INSTANT),
                    ZoneOffset.UTC);
        }
    }

    /**
     * Per-test isolated temporary directory injected by JUnit 5's
     * {@link TempDir} extension. Used as the staging area for the two
     * input fixtures (tcatbal.txt as the TCATBALF DD,
     * discgrp.txt as the DISCGRP DD) and as the destination directory
     * for the produced SYSTRAN output (the TRANSACT DD).
     *
     * <p>JUnit Jupiter creates this directory before the test method
     * runs and recursively deletes it after the test completes,
     * regardless of whether the test passes or fails. This guarantees
     * filesystem isolation between test runs and prevents stale
     * fixture leakage across the build per AAP §0.10.9 (test isolation
     * requirements).
     *
     * <p>Field visibility is package-private (default) &mdash; JUnit's
     * extension mechanism uses reflection to inject the directory and
     * does not require {@code public} access. Keeping it
     * package-private matches the established convention in
     * {@code AbstractBatchIT} for its inherited Spring-managed fields
     * and in the sibling {@code CombineTransactionsJobIT},
     * {@code TransactionReportJobIT}, and
     * {@code StatementGenerationJobIT}.
     */
    @TempDir
    Path workDir;

    /**
     * End-to-end execution of the migrated {@code interestCalculationJob}
     * Spring Batch {@code Job} bean with the canonical fixtures staged
     * into the {@link #workDir} {@link TempDir}.
     *
     * <p>The test wires the JCL PARM date
     * ({@link TestFixtures.Dates#INTCALC_PARM}, {@code "2022071800"})
     * and the three DD-mapped filesystem paths into a
     * {@link JobParameters} bundle, hands them to the inherited
     * {@code jobLauncherTestUtils} for synchronous execution, then
     * verifies:
     * <ol>
     *   <li>Spring Batch execution status &mdash;
     *       {@link BatchStatus#COMPLETED} and
     *       {@link ExitStatus#COMPLETED}.</li>
     *   <li>StepExecution presence &mdash; at least one Step ran
     *       (defensive check against a Job bean wired with zero Steps,
     *       which would still report COMPLETED but is structurally
     *       broken).</li>
     *   <li>Aggregate read count &mdash; sum across every Step's
     *       {@code getReadCount()} is strictly greater than zero,
     *       proving the FlatFileItemReader consumed records from the
     *       50-record tcatbal.txt fixture.</li>
     *   <li>Aggregate skip count &mdash; sum across every Step's
     *       {@code getReadSkipCount() + getWriteSkipCount() + getProcessSkipCount()}
     *       is exactly zero, proving the migration routes
     *       ZEROAPR/DEFAULT logic through the processor's writer paths
     *       (the COBOL parity) and NOT through Spring Batch's skip
     *       mechanism (a structural divergence the parity IT would
     *       catch byte-for-byte but is cheaper to surface here at the
     *       Spring Batch metadata level).</li>
     *   <li>Output file production &mdash; the SYSTRAN path exists on
     *       the filesystem and is non-empty (size &gt; 0).</li>
     * </ol>
     *
     * <p>Per AAP §0.10.1 (Require Test Coverage rule) this method
     * drives the <em>real</em> production {@code Job} bean end-to-end
     * &mdash; no business logic is reimplemented inside the test
     * body. The interest formula
     * {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} with HALF_EVEN
     * rounding to scale 2 is NEVER recomputed in this test; the
     * byte-identical baseline-parity check that asserts the
     * arithmetic result lives in
     * {@code InterestCalculationBaselineParityIT}.
     *
     * @throws Exception when the inherited {@code jobLauncherTestUtils}
     *                   propagates a Spring Batch launch failure (any
     *                   uncaught checked or unchecked exception during
     *                   job execution); JUnit 5 fails the test with
     *                   the propagated stack trace, surfacing the
     *                   COBOL parity regression. Also raised by the
     *                   private {@link #stageClasspathFixture(String)}
     *                   helper's {@link Files#write(Path, byte[],
     *                   java.nio.file.OpenOption...)} call when the
     *                   {@link TempDir} cannot be written (disk full
     *                   or permission denied).
     */
    @Test
    @DisplayName("Job completes with BatchStatus.COMPLETED against tcatbal.txt + discgrp.txt")
    void intcalcJob_runsAgainstTcatbalAndDiscgrp_completesSuccessfully() throws Exception {
        // ---- Arrange ----
        // Stage both INTCALC.jcl DD-mapped sequential input files into the
        // @TempDir so the Spring Batch Job's FlatFileItemReader can read them
        // from real filesystem paths (FlatFileItemReader does NOT consume
        // classpath resources directly).
        //
        // The two staged inputs correspond to the two flat-file DDs in
        // app/jcl/INTCALC.jcl:
        //   * baseline/input/tcatbal.txt  = AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS
        //     (the 50-record TRAN-CAT-BAL master, CVTRA01Y.cpy 50-byte layout)
        //   * baseline/input/discgrp.txt  = AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS
        //     (the 51-record disclosure-group master including DEFAULT and
        //     ZEROAPR group rows)
        //
        // The ACCTFILE and XREFFILE DDs from the JCL are not staged here —
        // those random-access lookups are backed by PostgreSQL JPA
        // repositories seeded by Flyway V3__seed.sql (per AAP §0.4.4).
        final Path stagedTcatbal = stageClasspathFixture(TestFixtures.Paths.FIXTURE_TCATBAL);
        final Path stagedDiscgrp = stageClasspathFixture(TestFixtures.Paths.FIXTURE_DISCGRP);
        // Destination path for the produced SYSTRAN output (TRANSACT DD). Just
        // a resolve() against the @TempDir — Spring Batch's FlatFileItemWriter
        // creates the file lazily when the Step opens its writer (no
        // pre-existence required). The basename matches the captured-baseline
        // golden filename so the companion parity IT can locate the
        // corresponding baseline/expected/ file by the same simple name.
        final Path actualOutput = workDir.resolve(TestFixtures.Paths.EXPECTED_TCATBAL_AFTER_INTEREST);

        // Build the JobParameters bundle that mirrors the INTCALC.jcl PARM
        // and DD assignments. The run.timestamp parameter guarantees each
        // JobInstance is unique even if this method is re-run (Spring Batch
        // would otherwise treat a repeat invocation as a restart of the prior
        // COMPLETED instance and refuse to launch). Path values are
        // absolutised so the Spring Batch reader/writer resolves them
        // independent of the JVM working directory.
        final JobParameters params = new JobParametersBuilder()
                .addString("intcalc.parm.date", TestFixtures.Dates.INTCALC_PARM)
                .addString("input.tcatbal.path", stagedTcatbal.toAbsolutePath().toString())
                .addString("input.discgrp.path", stagedDiscgrp.toAbsolutePath().toString())
                .addString("output.systran.path", actualOutput.toAbsolutePath().toString())
                .addLong("run.timestamp", System.currentTimeMillis())
                .toJobParameters();

        // ---- Act ----
        // jobLauncherTestUtils is the protected field inherited from
        // AbstractBatchIT, contributed to the Spring context by
        // @SpringBatchTest. launchJob synchronously runs the configured
        // interestCalculationJob @Bean to completion (or failure) and returns
        // the JobExecution metadata.
        final JobExecution execution = jobLauncherTestUtils.launchJob(params);

        // ---- Assert: Spring Batch execution status ----
        // The Java-side analogue of CBACT04C's "END OF EXECUTION OF PROGRAM
        // CBACT04C" DISPLAY + GOBACK with RC=0. AAP §0.5.1 explicitly mandates
        // "asserts execution semantics" for batch ITs.
        assertThat(execution.getStatus())
                .as("Job must complete with BatchStatus.COMPLETED")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus())
                .as("Job exit status must be COMPLETED")
                .isEqualTo(ExitStatus.COMPLETED);

        // ---- Assert: At least one Step ran ----
        // Defensive against a Job bean wired with zero Steps (which would
        // still report COMPLETED but is structurally broken — Spring Batch
        // does not catch this misconfiguration on its own).
        final Collection<StepExecution> steps = execution.getStepExecutions();
        assertThat(steps).as("Job must have at least one StepExecution").isNotEmpty();

        // ---- Assert: Aggregate read count > 0 ----
        // Aggregating across all Steps in the JobExecution keeps the
        // assertion resilient to future Step composition changes (single
        // "interestCalculationStep" today, potentially split into multiple
        // Steps tomorrow). The 50-record tcatbal.txt fixture means a
        // correctly-wired ItemReader produces a strictly positive read count.
        // Re-implementing the record count (e.g., asserting == 50) would
        // duplicate fixture knowledge inside the test, violating AAP §0.10.1
        // — the more robust assertion is simply "did the reader do work?".
        final long totalReadCount = steps.stream().mapToLong(StepExecution::getReadCount).sum();
        assertThat(totalReadCount)
                .as("Total read count must be > 0 (input is the 50-record tcatbal.txt)")
                .isGreaterThan(0L);

        // ---- Assert: Aggregate skip count == 0 ----
        // CBACT04C has two "skip-shaped" code paths (ZEROAPR rate guard,
        // DEFAULT-group fallback) that the migration must route through the
        // processor's writer logic, NOT through Spring Batch's SkipPolicy /
        // SkipListener mechanism. A non-zero skip count would indicate a
        // structural divergence from the COBOL parity. Summing the three
        // skip-count counters (read / write / process) catches the
        // divergence regardless of which Step phase triggered it.
        final long totalSkipCount = steps.stream()
                .mapToLong(s -> s.getReadSkipCount() + s.getWriteSkipCount() + s.getProcessSkipCount())
                .sum();
        assertThat(totalSkipCount)
                .as("ZEROAPR skip is logical (no interest record written); Spring Batch skip mechanism is not used")
                .isZero();

        // ---- Assert: Output exists and is non-empty ----
        // The discgrp.txt fixture contains accounts with non-zero
        // DIS-INT-RATE values, so a correctly-wired job MUST produce at
        // least one interest TRAN-RECORD in the SYSTRAN output. Reading the
        // file size (rather than parsing the contents) keeps this IT focused
        // on execution semantics; the parity IT performs the byte-level
        // content comparison.
        assertThat(actualOutput)
                .as("Job must produce the SYSTRAN output file")
                .exists();
        assertThat(Files.size(actualOutput))
                .as("Output must contain at least one interest record (most non-ZEROAPR accounts)")
                .isGreaterThan(0L);

        // ---- Assert: Per-record structural conformance (CBACT04C
        //              1300-B-WRITE-TX paragraph parity) ----
        // Read every emitted TRAN-RECORD line and assert the three
        // CBACT04C-mandated literal fields conform to the COBOL
        // contract. This is the AAP §0.10.4 immutable-boundary
        // assertion: the migrated job must emit byte-identical
        // TRAN-TYPE-CD / TRAN-CAT-CD / TRAN-DESC prefix values
        // because downstream consumers (POSTTRAN / COMBTRAN / reports)
        // rely on the literal positions. Per the AAP §0.10.1 Require
        // Test Coverage rule the test does NOT recompute the values —
        // it asserts the literal sentinels copied directly from
        // app/cbl/CBACT04C.cbl lines 482-489.
        //
        // The byte-level HALF_EVEN financial-precision contract is
        // exercised at unit-test level by
        // InterestCalculationProcessorTest (which drives the formula
        // (TRAN-CAT-BAL * DIS-INT-RATE) / 1200 with deterministic
        // BigDecimal inputs and asserts the output scale and rounding
        // mode); the byte-identical end-to-end HALF_EVEN parity check
        // lives in the companion InterestCalculationBaselineParityIT,
        // which diffs the produced SYSTRAN output against the captured
        // COBOL reference under baseline/expected/. Re-deriving the
        // HALF_EVEN amounts inside this IT body would duplicate
        // production arithmetic (AAP §0.10.1 violation).
        final List<String> outputLines = Files.readAllLines(actualOutput, StandardCharsets.US_ASCII);
        assertThat(outputLines)
                .as("Output must contain at least one CVTRA05Y record line")
                .isNotEmpty();
        for (int i = 0; i < outputLines.size(); i++) {
            final String line = outputLines.get(i);

            // 350-byte record-length invariant. Per CVTRA05Y RECLN=350
            // and AAP §0.10.4 the byte length is immutable. The
            // FlatFileItemWriter strips the LF terminator before
            // returning lines via readAllLines, so the in-memory
            // string is exactly 350 characters.
            assertThat(line.length())
                    .as("Line %d must be exactly %d bytes per CVTRA05Y RECLN. Actual='%s'",
                            i, CVTRA05Y_RECORD_LENGTH, line)
                    .isEqualTo(CVTRA05Y_RECORD_LENGTH);

            // TRAN-TYPE-CD at positions 17-18 must be the CBACT04C
            // literal '01' (Purchase) per CBACT04C line 482.
            final String tranTypeCd = line.substring(TRAN_TYPE_CD_START, TRAN_TYPE_CD_END);
            assertThat(tranTypeCd)
                    .as("Line %d TRAN-TYPE-CD (positions %d-%d) must equal '%s' per CBACT04C line 482",
                            i, TRAN_TYPE_CD_START + 1, TRAN_TYPE_CD_END,
                            TestFixtures.Transactions.TRAN_TYPE_PURCHASE)
                    .isEqualTo(TestFixtures.Transactions.TRAN_TYPE_PURCHASE);

            // TRAN-CAT-CD at positions 19-22 must be the CBACT04C
            // literal '0005' (Interest) per CBACT04C line 483.
            final String tranCatCd = line.substring(TRAN_CAT_CD_START, TRAN_CAT_CD_END);
            assertThat(tranCatCd)
                    .as("Line %d TRAN-CAT-CD (positions %d-%d) must equal '%s' per CBACT04C line 483",
                            i, TRAN_CAT_CD_START + 1, TRAN_CAT_CD_END,
                            TestFixtures.Transactions.TRAN_CAT_INTEREST)
                    .isEqualTo(TestFixtures.Transactions.TRAN_CAT_INTEREST);

            // TRAN-DESC at positions 33-onwards must begin with the
            // CBACT04C literal "Int. for a/c " (13 characters) per
            // CBACT04C lines 485-488.
            assertThat(line.substring(TRAN_DESC_START))
                    .as("Line %d TRAN-DESC (positions %d-) must begin with '%s' per CBACT04C lines 485-488",
                            i, TRAN_DESC_START + 1, INTEREST_DESC_PREFIX)
                    .startsWith(INTEREST_DESC_PREFIX);
        }
    }

    // =========================================================================
    // Private helpers — staging classpath fixtures onto the @TempDir filesystem
    // =========================================================================

    /**
     * Loads a canonical baseline-input fixture from the test classpath
     * ({@code src/test/resources/baseline/input/}) and writes it to the
     * {@link #workDir} {@link TempDir} so the Spring Batch Job's
     * {@code FlatFileItemReader} can consume it from a real filesystem
     * path.
     *
     * <p>The helper exists because {@code FlatFileItemReader} expects a
     * {@link java.nio.file.Path} or {@code Resource} backed by a real
     * filesystem location, NOT a classpath resource &mdash; staging
     * through {@link Files#write(Path, byte[],
     * java.nio.file.OpenOption...)} is the idiomatic Spring Batch test
     * pattern (also used in the sibling {@code CombineTransactionsJobIT},
     * {@code TransactionReportJobIT}, and
     * {@code StatementGenerationJobIT}).
     *
     * <p>Byte-level write (not character-level) preserves the original
     * fixed-width COBOL record byte layout including any trailing
     * whitespace and the original line-ending convention; AAP §0.10.4
     * ("Input and output file formats and record layouts MUST remain
     * identical") forbids any charset / line-ending normalisation in
     * the test harness.
     *
     * <p>Both INTCALC.jcl sequential inputs (TCATBALF and DISCGRP) are
     * sourced from {@code baseline/input/} because they are the
     * canonical golden inputs to the interest calculation pipeline.
     * Per AAP §0.4.4 (Fixture Organization Strategy): canonical golden
     * inputs live under {@code baseline/input/}; the captured COBOL
     * reference outputs live under {@code baseline/expected/} (used by
     * the companion parity IT, not by this semantics IT).
     *
     * @param filename simple basename of the input fixture (e.g.
     *                 {@code "tcatbal.txt"} or {@code "discgrp.txt"})
     *                 &mdash; must be one of the canonical golden inputs
     *                 under
     *                 {@link TestFixtures.Paths#CLASSPATH_BASELINE_INPUT_DIR}
     * @return absolute {@link Path} to the staged copy inside
     *         {@link #workDir} ready to be passed as a Spring Batch
     *         {@code JobParameters} string value
     * @throws java.io.IOException when the {@link Files#write} call
     *                              fails (disk full, permission denied,
     *                              or the {@link TempDir} root has been
     *                              removed externally)
     */
    private Path stageClasspathFixture(String filename) throws java.io.IOException {
        final byte[] bytes = FixtureLoader.loadAsBytes(
                TestFixtures.Paths.CLASSPATH_BASELINE_INPUT_DIR + filename);
        final Path staged = workDir.resolve(filename);
        Files.write(staged, bytes);
        return staged;
    }
}
