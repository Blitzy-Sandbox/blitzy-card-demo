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
//     @DynamicPropertySource credential injection (ephemeral UUID-derived
//     username / password / database name — AAP §0.10.5 no-plaintext-
//     credentials directive), and the inherited {@code jobLauncherTestUtils}
//     field used to launch the {@code statementGenerationJob} bean. This IT
//     extends it to inherit the full Spring application context (required by
//     Spring Batch's {@code JobLauncher} which needs every {@code Job},
//     {@code Step}, {@code ItemReader}, {@code ItemProcessor}, and
//     {@code ItemWriter} bean in the context graph) AND the per-class shared
//     container lifecycle that AAP §0.4.4 mandates for batch ITs.
//
//   * FixtureLoader — static utility consumed by the private
//     stageBaselineInput() / stageExpectedFixture() helpers to load
//     classpath fixture files (cardxref.txt, custdata.txt, acctdata.txt,
//     baseline/expected/combined.txt) as raw byte arrays before they are
//     written to the JUnit @TempDir for the Spring Batch job to read. Per
//     AAP §0.5.5 (test utilities reuse — FixtureLoader serves every batch
//     IT and baseline-parity IT). The byte-level write preserves the
//     original fixed-width COBOL record byte layout (AAP §0.10.4 Immutable
//     Boundaries — "Input and output file formats and record layouts MUST
//     remain identical").
//
//   * TestFixtures — pure-constants class providing the classpath path
//     constants (CLASSPATH_BASELINE_INPUT_DIR, CLASSPATH_BASELINE_EXPECTED_DIR)
//     and fixture / expected filenames (FIXTURE_CARDXREF, FIXTURE_CUSTDATA,
//     FIXTURE_ACCTDATA, EXPECTED_COMBINED, EXPECTED_STATEMENTS_TEXT,
//     EXPECTED_STATEMENTS_HTML) used to compose classpath lookups and
//     compute target output paths in the @TempDir workDir without
//     hardcoding any magic strings in the test body. Mirrors the sibling
//     TransactionReportJobIT pattern.
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
//     activation. The sibling tests (TransactionReportJobIT,
//     GateVerificationE2ETest, OnlineTransactionE2ETest,
//     AdminUserManagementE2ETest, and the 10 repository ITs) use the same
//     @Disabled pattern — this IT mirrors that established project
//     convention so the compile-time wiring is verified end-to-end while
//     the runtime DB / Job-bean execution awaits its production-side
//     dependencies (per AAP §0.8.1 "the testing flavor CREATEs tests
//     against those classes but does not modify them").
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
//     deleted after the test completes, so the produced STMTFILE/HTMLFILE
//     outputs and the staged input fixtures never leak between tests or
//     onto the workspace. This is the canonical JUnit 5 mechanism for
//     test-scoped filesystem isolation per AAP §0.10.9 (test isolation
//     requirements).
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
//     "End-to-end statement job; asserts text and HTML output files"
//     contract.
//
//   * ExitStatus — Spring Batch's grouping of the exit codes produced by
//     each Step; the aggregate Job-level exit status (read via
//     JobExecution#getExitStatus) must equal ExitStatus.COMPLETED for a
//     successful run. This is the Java-side analogue of the COBOL
//     CBSTM03A STOP RUN with RC=0.
//
//   * JobExecution — Spring Batch's runtime metadata object for a single
//     Job invocation; returned by JobLauncherTestUtils#launchJob and used
//     to assert on lifecycle status + exit status.
//
//   * JobParameters / JobParametersBuilder — Spring Batch's typed parameter
//     container; JobParametersBuilder is the canonical builder API for
//     assembling the parameters that the migrated statementGenerationJob's
//     @Bean wiring expects (the 6 file-path parameters mirroring the JCL
//     XREFFILE / CUSTFILE / ACCTFILE / TRNXFILE / STMTFILE / HTMLFILE DD
//     statements + the run.timestamp uniqueness disambiguator).
// ---------------------------------------------------------------------------
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;

// ---------------------------------------------------------------------------
// JDK NIO.2 primitives (AAP §0.10.7 standard library — no third-party file I/O).
//
//   * Files — JDK NIO.2 facade used to materialise fixture bytes onto the
//     @TempDir-backed filesystem (Files.write inside the private staging
//     helpers) and to assert that the produced STMTFILE / HTMLFILE outputs
//     are non-empty (Files.size > 0).
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
// JDK character / collections / time API (AAP §0.10.7 standard library).
//
//   * StandardCharsets.US_ASCII — strict-subset charset for reading the
//     produced STMTFILE output. CBSTM03A writes plain-text records using
//     US-ASCII letters / digits / spaces; non-ASCII bytes indicate file
//     corruption and fail loudly with MalformedInputException.
//
//   * StandardCharsets.UTF_8 — charset for reading the HTMLFILE output.
//     CBSTM03A's HTML output is bytewise ASCII-clean but the file may
//     pass through UTF-8 in downstream tooling; UTF-8 is a superset of
//     ASCII so no parsing surprises arise.
//
//   * List — return type of Files.readAllLines used for the per-record
//     line-length assertions on the produced STMTFILE and HTMLFILE
//     outputs.
// ---------------------------------------------------------------------------
import java.nio.charset.StandardCharsets;
import java.util.List;

// ---------------------------------------------------------------------------
// AssertJ fluent assertions (AAP §0.10.10 — AssertJ exclusively, no Hamcrest,
// no JUnit Assertions). Static import keeps the call sites concise:
// assertThat(...).isEqualTo(...).
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Spring Batch integration test for the Statement Generation job
 * ({@code CREASTMT.JCL} &rarr; {@code CBSTM03A.cbl} migration).
 *
 * <p>Drives the migrated {@code statementGenerationJob} Spring Batch
 * {@code Job} bean against canonical fixtures and asserts:
 * <ol>
 *   <li>Spring Batch execution semantics — {@link BatchStatus#COMPLETED}
 *       and {@link ExitStatus#COMPLETED}, the Java-side analogue of the
 *       COBOL {@code CBSTM03A} {@code STOP RUN} with RC=0.</li>
 *   <li>STMTFILE output production — the plain-text 80-byte fixed-width
 *       statement file (matching the {@code FD-STMTFILE-REC PIC X(80)}
 *       declaration in {@code app/cbl/CBSTM03A.cbl} line 45) is created on
 *       the filesystem and is non-empty (size &gt; 0).</li>
 *   <li>HTMLFILE output production — the HTML 100-byte fixed-width
 *       statement file (matching the {@code FD-HTMLFILE-REC PIC X(100)}
 *       declaration in {@code app/cbl/CBSTM03A.cbl} line 47) is created on
 *       the filesystem and is non-empty (size &gt; 0).</li>
 * </ol>
 *
 * <h2>Separation from the byte-identical parity IT</h2>
 *
 * <p>This IT verifies <em>execution semantics and dual-output production</em>
 * (both files are produced and non-empty). The byte-identical baseline
 * parity assertion against the captured COBOL reference outputs
 * ({@code src/test/resources/baseline/expected/statements_text.txt} and
 * {@code src/test/resources/baseline/expected/statements_html.txt}) lives
 * in the companion {@code StatementGenerationBaselineParityIT}.
 * Keeping the two ITs separate matches the AAP §0.5.1 file-by-file plan
 * ("End-to-end statement job; asserts text and HTML output files" for
 * this IT vs. "Byte-identical diff for statement files" for the parity
 * IT) and gives the two ITs distinct failure modes: a parity-IT failure
 * indicates a numeric / formatting regression in the migrated formatter;
 * a structural-IT failure indicates the formatter is no longer producing
 * one of the two output files at all (a regression in the job step
 * configuration or the dual-output ItemWriter wiring).
 *
 * <h2>Why both outputs are asserted independently</h2>
 *
 * <p>{@code CBSTM03A.cbl} writes to BOTH {@code STMTFILE} (PIC X(80)) and
 * {@code HTMLFILE} (PIC X(100)) for every customer statement record (see
 * the FD declarations at {@code app/cbl/CBSTM03A.cbl} lines 44-47). The
 * dual-write pattern is one of the migration's correctness pillars — a
 * regression that silently drops the HTML output (or vice versa) must
 * fail this test. Asserting each output's existence AND size
 * independently, each with its own AssertJ {@code .as(...)} description,
 * yields clear diagnostics on failure: a failure message that names
 * which output was missing or empty rather than a generic "test failed".
 *
 * <h2>JobParameters mapping (CREASTMT.JCL DD &rarr; Spring Batch)</h2>
 *
 * <p>The migrated job consumes the following parameters that mirror the
 * original JCL data definitions for the STEP040 {@code EXEC PGM=CBSTM03A}
 * step in {@code app/jcl/CREASTMT.JCL}:
 * <ul>
 *   <li>{@code input.cardxref.path} &mdash; XREFFILE DD (sequential
 *       access by 16-char card number, per the {@code SELECT XREF-FILE}
 *       clause in {@code app/cbl/CBSTM03B.cbl} lines 37-41).</li>
 *   <li>{@code input.custdata.path} &mdash; CUSTFILE DD (random access
 *       by 9-char customer ID, per the {@code SELECT CUST-FILE} clause
 *       in {@code app/cbl/CBSTM03B.cbl} lines 43-47).</li>
 *   <li>{@code input.acctdata.path} &mdash; ACCTFILE DD (random access
 *       by 11-digit account ID, per the {@code SELECT ACCT-FILE} clause
 *       in {@code app/cbl/CBSTM03B.cbl} lines 49-53).</li>
 *   <li>{@code input.transact.path} &mdash; TRNXFILE DD (sequential
 *       access by composite card+txn-id key, populated by the upstream
 *       STEP010/STEP020 SORT+REPRO pipeline that produces the "combined"
 *       transaction file; this IT stages the pre-captured
 *       {@code combined.txt} expected output as the TRNXFILE input,
 *       mirroring the JCL pipeline's STEP010/STEP020&rarr;STEP040
 *       hand-off).</li>
 *   <li>{@code output.stmtfile.path} &mdash; STMTFILE DD (sequential
 *       output, LRECL=80, RECFM=FB, per JCL lines 87-91).</li>
 *   <li>{@code output.htmlfile.path} &mdash; HTMLFILE DD (sequential
 *       output, LRECL=100, RECFM=FB, per JCL lines 92-96).</li>
 *   <li>{@code run.timestamp} &mdash; epoch milliseconds, guarantees each
 *       Spring Batch {@code JobInstance} is unique even if the same test
 *       method is re-run; without this, Spring Batch would treat a
 *       repeat run as a restart of the prior instance and refuse to
 *       launch when the prior is {@link BatchStatus#COMPLETED}.</li>
 * </ul>
 *
 * <h2>Why {@code combined.txt} is the TRNXFILE input</h2>
 *
 * <p>The original CREASTMT.JCL pipeline runs four steps:
 * <ol>
 *   <li>DELDEF01 &mdash; IDCAMS DELETE/DEFINE of the TRXFL VSAM KSDS
 *       (recreates the cluster fresh for this run).</li>
 *   <li>STEP010 &mdash; DFSORT reads the TRANSACT VSAM KSDS and sorts
 *       records by composite (card-number, tran-id) key, producing
 *       the AWS.M2.CARDDEMO.TRXFL.SEQ sequential file.</li>
 *   <li>STEP020 &mdash; IDCAMS REPRO loads the sequential file into the
 *       TRXFL VSAM KSDS so STEP040 can read it via the indexed
 *       SELECT TRNX-FILE in {@code CBSTM03B}.</li>
 *   <li>STEP040 &mdash; {@code CBSTM03A} reads the sorted TRNXFILE +
 *       XREFFILE + CUSTFILE + ACCTFILE and emits the dual STMTFILE +
 *       HTMLFILE.</li>
 * </ol>
 *
 * <p>This IT exercises step 4 in isolation: the pre-captured
 * {@code baseline/expected/combined.txt} represents the output of the
 * migrated COMBTRAN.jcl job (see
 * {@code CombineTransactionsBaselineParityIT}) and is fed directly to
 * the statement job as its TRNXFILE input. The combined transaction
 * file's record layout (16-char card key + 16-char tran-id key + 318
 * byte transaction record body, total 350 bytes per
 * {@code app/cbl/CBSTM03B.cbl} lines 58-63) matches what the JCL
 * STEP010 DFSORT step produces, so feeding {@code combined.txt}
 * directly preserves the same record contract. The integration of the
 * upstream COMBTRAN-equivalent step and this statement step end-to-end
 * is the responsibility of {@code BatchPipelineE2ETest} per AAP §0.5.1.
 *
 * <h2>Why this class is currently {@code @Disabled}</h2>
 *
 * <p>The suite loads the full Spring Boot application context via the
 * {@code @SpringBootTest} annotation inherited from
 * {@link AbstractBatchIT} (Spring Batch ITs cannot use a narrower test
 * slice — the {@code JobLauncher} traverses the {@code Job} /
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
 * source under {@code src/main/java/com/aws/carddemo/} from the testing
 * flavor: the testing flavor CREATEs tests against those classes but
 * does NOT modify them. The IT is therefore registered, compiled, and
 * preserved end-to-end (Failsafe discovers exactly one test method),
 * but the JUnit Jupiter {@code @Disabled} marker below defers
 * <em>runtime</em> execution until the production-side migration
 * agents complete their work. The sibling
 * {@code TransactionReportJobIT}, the E2E tests
 * ({@code GateVerificationE2ETest}, {@code OnlineTransactionE2ETest},
 * {@code AdminUserManagementE2ETest}), and the 10 repository ITs use
 * the same {@code @Disabled} pattern — this IT mirrors that established
 * project convention.
 *
 * <h3>Reactivation Checklist (for the next REFACTOR-flavor agent)</h3>
 *
 * <p>Remove the {@code @Disabled} annotation (and the unused
 * {@code org.junit.jupiter.api.Disabled} import) once <em>all</em> of
 * the following production-side prerequisites are in place:
 *
 * <ol>
 *   <li><strong>{@code statementGenerationJob} {@code @Bean}</strong>
 *       declared in a {@code @Configuration} class under
 *       {@code src/main/java/com/aws/carddemo/batch/config/} (or
 *       similar). The bean must:
 *       <ul>
 *         <li>Be named exactly {@code statementGenerationJob} so
 *             {@code JobLauncherTestUtils} (inherited via
 *             {@code AbstractBatchIT}) resolves it as the unique
 *             {@code Job} in the application context, or be the sole
 *             {@code Job} bean so resolution is unambiguous.</li>
 *         <li>Read the CREASTMT.JCL XREFFILE / CUSTFILE / ACCTFILE /
 *             TRNXFILE / STMTFILE / HTMLFILE DD-mapped file paths from
 *             the {@code "input.cardxref.path"},
 *             {@code "input.custdata.path"},
 *             {@code "input.acctdata.path"},
 *             {@code "input.transact.path"},
 *             {@code "output.stmtfile.path"}, and
 *             {@code "output.htmlfile.path"} {@link JobParameters}
 *             string keys (absolute filesystem paths staged into the
 *             JUnit {@code @TempDir}).</li>
 *         <li>Compose the migrated
 *             {@link com.aws.carddemo.batch.StatementProcessor}
 *             (text + HTML formatter, replaces {@code CBSTM03A.cbl}
 *             body) and
 *             {@link com.aws.carddemo.batch.StatementFileProcessor}
 *             (file I/O orchestration, replaces {@code CBSTM03B.cbl})
 *             as the step components and back the inputs/outputs with
 *             {@code FlatFileItemReader} / {@code FlatFileItemWriter}
 *             instances pointed at the parameterised paths.</li>
 *         <li>The dual-output ItemWriter must write to BOTH STMTFILE
 *             (LRECL=80) AND HTMLFILE (LRECL=100) for every customer
 *             statement record — Spring Batch's
 *             {@code CompositeItemWriter} or a custom
 *             {@code ItemStreamWriter} that fans out to two
 *             {@code FlatFileItemWriter} delegates is the canonical
 *             pattern.</li>
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
 *       {@code TransactionListService},
 *       {@code TransactionDetailService},
 *       {@code TransactionAddService}, {@code UserListService},
 *       {@code UserAddService}, {@code UserUpdateService},
 *       {@code UserDeleteService}). Without the stereotype, Spring's
 *       component scan never registers these classes as beans, so the
 *       full-context Spring Boot load this IT performs (via the
 *       inherited {@code @SpringBootTest}) fails at context refresh with
 *       {@link org.springframework.beans.factory.NoSuchBeanDefinitionException}
 *       on the controller's constructor parameters — even though this
 *       IT never directly calls a controller, every controller bean
 *       still gets instantiated during context startup.</li>
 *   <li><strong>{@code SecurityConfig}</strong> under
 *       {@code src/main/java/com/aws/carddemo/config/} wiring Spring
 *       Security with at minimum a {@code SecurityFilterChain} and a
 *       {@code BCryptPasswordEncoder} bean (the
 *       {@code AuthenticationService} constructor declares
 *       {@code PasswordEncoder} as a required dependency — Spring Boot's
 *       default in-memory user-details service does not provide a
 *       {@code PasswordEncoder} bean, so an explicit bean must be
 *       configured).</li>
 *   <li><strong>Flyway migrations</strong> under
 *       {@code src/main/resources/db/migration/} ({@code V1__schema.sql},
 *       {@code V2__indexes.sql}, {@code V3__seed.sql}) — required by
 *       the inherited Testcontainers PostgreSQL container so the
 *       {@code spring.batch.jdbc} {@code BATCH_*} metadata tables can
 *       be created and the seed data hydrated before the job runs.</li>
 *   <li><strong>Captured baseline-expected fixtures</strong> under
 *       {@code src/test/resources/baseline/expected/}
 *       ({@code combined.txt} required by this IT's TRNXFILE staging;
 *       {@code statements_text.txt} and {@code statements_html.txt}
 *       required by the companion
 *       {@code StatementGenerationBaselineParityIT}) — must be captured
 *       from a successful run of the original COBOL baseline pipeline
 *       before this IT can be re-enabled.</li>
 *   <li><strong>{@code CardDemoApplication}</strong> already exists
 *       (verified at this commit) and is correctly annotated with
 *       {@code @SpringBootApplication}. No action required for this
 *       prereq.</li>
 *   <li><strong>Docker available to Testcontainers</strong> at test
 *       runtime — the {@code mvn verify} build agent must be able to
 *       run {@code postgres:16-alpine}. CI agents that cannot start
 *       containers can set {@code TESTCONTAINERS_RYUK_DISABLED=true} as
 *       documented in
 *       {@code src/test/resources/application-test.properties}.</li>
 * </ol>
 *
 * <h3>How to verify reactivation worked</h3>
 *
 * <pre>{@code
 * mvn -B -Dit.test=StatementGenerationJobIT verify
 * }</pre>
 *
 * <p>Expected outcome after reactivation: the single
 * {@code statementJob_runsAgainstFixtures_producesBothTextAndHtmlOutputs}
 * test passes — the Spring Batch {@code statementGenerationJob}
 * completes with {@link BatchStatus#COMPLETED}, writes a non-empty
 * STMTFILE (LRECL=80) AND a non-empty HTMLFILE (LRECL=100) to the JUnit
 * {@code @TempDir} at the paths bound via the {@code output.stmtfile.path}
 * and {@code output.htmlfile.path} {@link JobParameters} string keys.
 *
 * <h2>Minimal Change Clause (AAP §0.10.2)</h2>
 *
 * <p>This IT carries ONLY the assertions strictly required by AAP §0.5.1
 * ("asserts text and HTML output files"): execution status, file
 * existence, file non-emptiness. It does NOT inspect the file contents
 * structurally (the byte-identical parity IT does that), it does NOT
 * verify the record-length contract (LRECL=80 / LRECL=100 — the
 * production {@code FlatFileItemWriter} configuration enforces that and
 * the parity IT catches any regression), and it does NOT count the
 * number of records produced (the parity IT's byte-equal diff implicitly
 * verifies that). Keeping this IT focused on the
 * "executes-and-produces-both-outputs" contract avoids overlap with the
 * parity IT and keeps each IT's failure mode unambiguous.
 *
 * <h2>Authority</h2>
 *
 * <p>AAP §0.5.1 ("End-to-end statement job; asserts text and HTML output
 * files"), §0.5.2 (Batch Job ITs), §0.8.1 (testing flavor does not
 * modify production source), §0.10.1 (Require Test Coverage rule —
 * drive production code, never reimplement business logic), §0.10.2
 * (Minimal Change Clause), §0.10.4 (Immutable Boundaries — input and
 * output file formats remain identical), §0.10.7 (JUnit 5 + Mockito
 * framework constraint), §0.10.10 (Style consistency — AssertJ
 * exclusively).
 *
 * @see com.aws.carddemo.batch.StatementProcessor the migrated
 *      {@code CBSTM03A} statement-formatter component (text + HTML
 *      builders) whose Job-bean wiring this IT exercises end-to-end.
 * @see com.aws.carddemo.batch.StatementFileProcessor the migrated
 *      {@code CBSTM03B} file-I/O orchestrator that the
 *      {@code statementGenerationJob} wires alongside the
 *      {@code StatementProcessor}.
 * @see AbstractBatchIT shared base class supplying the Spring Boot test
 *      context, the Spring Batch test slice, and the Testcontainers
 *      PostgreSQL 16 container.
 * @see TransactionReportJobIT sibling batch IT that follows the same
 *      structural and {@code @Disabled} pattern for the TRANREPT.jcl /
 *      CBTRN03C migration.
 */
@DisplayName("CREASTMT.JCL Spring Batch job execution + dual output verification")
class StatementGenerationJobIT extends AbstractBatchIT {

    /**
     * Width (in bytes) of the {@code FD-STMTFILE-REC} plain-text statement
     * record produced by CBSTM03A. Pinned as a named constant per AAP
     * §0.10.4 immutable-boundary contract.
     *
     * @see <a href="file:app/cbl/CBSTM03A.CBL">CBSTM03A.CBL</a> line 45
     *      {@code 01 FD-STMTFILE-REC PIC X(80)}
     */
    private static final int STMTFILE_RECORD_LENGTH = 80;

    /**
     * Width (in bytes) of the {@code FD-HTMLFILE-REC} HTML statement
     * record produced by CBSTM03A. Pinned as a named constant per AAP
     * §0.10.4 immutable-boundary contract.
     *
     * @see <a href="file:app/cbl/CBSTM03A.CBL">CBSTM03A.CBL</a> line 47
     *      {@code 01 FD-HTMLFILE-REC PIC X(100)}
     */
    private static final int HTMLFILE_RECORD_LENGTH = 100;

    /**
     * Maximum number of transaction-detail rows CBSTM03A formats per
     * card-statement page. Sourced from {@code WS-TRAN-TBL OCCURS 10
     * TIMES} at {@code app/cbl/CBSTM03A.CBL} line 228. Pinned as a
     * named constant per AAP §0.10.4 immutable-boundary contract.
     */
    private static final int MAX_TRANSACTIONS_PER_CARD = 10;

    /**
     * Per-test isolated temporary directory injected by JUnit 5's
     * {@link TempDir} extension. Used as the staging area for the four
     * input fixtures (XREFFILE/CUSTFILE/ACCTFILE/TRNXFILE) and as the
     * destination directory for the produced STMTFILE + HTMLFILE outputs.
     *
     * <p>JUnit Jupiter creates this directory before the test method
     * runs and recursively deletes it after the test completes,
     * regardless of whether the test passes or fails. This guarantees
     * filesystem isolation between test runs and prevents stale fixture
     * leakage across the build per AAP §0.10.9 (test isolation
     * requirements).
     *
     * <p>Field visibility is package-private (default) — JUnit's
     * extension mechanism uses reflection to inject the directory and
     * does not require {@code public} access. Keeping it
     * package-private matches the established convention in
     * {@link TransactionReportJobIT} and {@link AbstractBatchIT} for
     * their respective JUnit-injected and Spring-managed fields.
     */
    @TempDir
    Path workDir;

    /**
     * End-to-end execution of the migrated {@code statementGenerationJob}
     * Spring Batch {@code Job} bean with the canonical fixtures staged
     * into the {@link #workDir} {@link TempDir}.
     *
     * <p>The test wires the four input fixtures (cardxref / custdata /
     * acctdata / combined-transactions) and the two output destination
     * paths (STMTFILE + HTMLFILE) into a {@link JobParameters} bundle,
     * hands them to the inherited {@code jobLauncherTestUtils} for
     * synchronous execution, then verifies (a) Spring Batch execution
     * status, (b) STMTFILE existence and non-emptiness, and (c)
     * HTMLFILE existence and non-emptiness. Each output is asserted
     * independently with a distinct AssertJ {@code .as(...)} description
     * so a failure cleanly names which of the dual outputs was missing
     * or empty.
     *
     * <p>Per AAP §0.10.1 (Require Test Coverage rule) this method
     * drives the <em>real</em> production {@code Job} bean
     * end-to-end — no business logic is reimplemented inside the test
     * body. Per AAP §0.10.4 the dual statement outputs produced by the
     * migrated job are treated as immutable boundaries; the
     * byte-identical baseline-parity check lives in
     * {@code StatementGenerationBaselineParityIT}.
     *
     * <p>Test name follows the project convention
     * {@code methodUnderTest_inputCondition_expectedOutcome}
     * (AAP §0.7.2 Maintainability standards): the method under test is
     * "statementJob", the input condition is "runsAgainstFixtures", and
     * the expected outcome is "producesBothTextAndHtmlOutputs".
     *
     * @throws Exception when the inherited {@code jobLauncherTestUtils}
     *                   propagates a Spring Batch launch failure (any
     *                   uncaught checked or unchecked exception during
     *                   job execution); JUnit 5 fails the test with the
     *                   propagated stack trace, surfacing the COBOL
     *                   parity regression. The {@code throws Exception}
     *                   declaration is the {@code JobLauncherTestUtils}
     *                   contract; Spring Batch's
     *                   {@code launchJob(JobParameters)} declares
     *                   {@code throws Exception} for the broad spectrum
     *                   of {@code JobExecutionException} subclasses it
     *                   may propagate.
     */
    @Test
    @DisplayName("Job produces both STMTFILE (80-byte text) and HTMLFILE (100-byte HTML) outputs")
    void statementJob_runsAgainstFixtures_producesBothTextAndHtmlOutputs() throws Exception {
        // ---- Arrange ----
        // Stage every CREASTMT.JCL DD-mapped input file into the @TempDir so
        // the Spring Batch Job's FlatFileItemReader instances can read them
        // from real filesystem paths (FlatFileItemReader does NOT consume
        // classpath resources directly). The XREFFILE / CUSTFILE / ACCTFILE
        // are loaded from the canonical baseline/input/ directory; the
        // TRNXFILE is loaded from baseline/expected/combined.txt which
        // represents the output of the upstream COMBTRAN-equivalent step
        // (see the class Javadoc "Why combined.txt is the TRNXFILE input"
        // section). This IT runs only the CREASTMT STEP040 statement-
        // formatting step, so the upstream STEP010 (SORT) and STEP020
        // (REPRO) are pre-baked into the input fixture.
        final Path stagedCardxref = stageBaselineInput(TestFixtures.Paths.FIXTURE_CARDXREF);
        final Path stagedCustdata = stageBaselineInput(TestFixtures.Paths.FIXTURE_CUSTDATA);
        final Path stagedAcctdata = stageBaselineInput(TestFixtures.Paths.FIXTURE_ACCTDATA);
        final Path stagedTransact = stageExpectedFixture(TestFixtures.Paths.EXPECTED_COMBINED);

        // Destination paths for the produced STMTFILE (LRECL=80, text) and
        // HTMLFILE (LRECL=100, HTML) outputs. Just resolve() calls against
        // the @TempDir — Spring Batch's FlatFileItemWriter creates each file
        // lazily when its Step opens its writer (no pre-existence required).
        // The filenames mirror the baseline/expected/ goldens so the
        // companion parity IT can diff them with no additional path mapping.
        final Path actualStmt = workDir.resolve(TestFixtures.Paths.EXPECTED_STATEMENTS_TEXT);
        final Path actualHtml = workDir.resolve(TestFixtures.Paths.EXPECTED_STATEMENTS_HTML);

        // Build the JobParameters bundle that mirrors the CREASTMT.JCL DD
        // assignments. The run.timestamp parameter guarantees each
        // JobInstance is unique even if this method is re-run (Spring Batch
        // would otherwise treat a repeat invocation as a restart of the
        // prior COMPLETED instance and refuse to launch). Path values are
        // absolutised so the Spring Batch reader/writer resolves them
        // independent of the JVM working directory (Spring Batch's
        // FlatFileItemReader uses java.io.File semantics for relative-path
        // resolution, which depends on the working directory of the
        // process — absolutising avoids the brittleness).
        final JobParameters params = new JobParametersBuilder()
                .addString("input.cardxref.path", stagedCardxref.toAbsolutePath().toString())
                .addString("input.custdata.path", stagedCustdata.toAbsolutePath().toString())
                .addString("input.acctdata.path", stagedAcctdata.toAbsolutePath().toString())
                .addString("input.transact.path", stagedTransact.toAbsolutePath().toString())
                .addString("output.stmtfile.path", actualStmt.toAbsolutePath().toString())
                .addString("output.htmlfile.path", actualHtml.toAbsolutePath().toString())
                .addLong("run.timestamp", System.currentTimeMillis())
                .toJobParameters();

        // ---- Act ----
        // jobLauncherTestUtils is the protected field inherited from
        // AbstractBatchIT, contributed to the Spring context by
        // @SpringBatchTest. launchJob synchronously runs the configured
        // statementGenerationJob @Bean to completion (or failure) and
        // returns the JobExecution metadata.
        final JobExecution execution = jobLauncherTestUtils.launchJob(params);

        // ---- Assert ----
        // (1) Spring Batch execution status — the Java-side analogue of the
        //     COBOL CBSTM03A STOP RUN with RC=0. AAP §0.5.1 explicitly
        //     mandates "asserts execution semantics" for batch ITs.
        assertThat(execution.getStatus())
                .as("Job must complete with BatchStatus.COMPLETED "
                        + "(Java equivalent of COBOL CBSTM03A STOP RUN RC=0)")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus())
                .as("Job exit status must be COMPLETED (no skip / no failure aggregation)")
                .isEqualTo(ExitStatus.COMPLETED);

        // (2) STMTFILE output existence and non-emptiness — every successful
        //     run of CBSTM03A produces at least one statement (start of
        //     statement banner + customer header + footer) even when there
        //     are zero detail rows, so size > 0 is the minimum we can assert
        //     without reimplementing the formatter (AAP §0.10.1). The dual
        //     existence + size assertions for STMTFILE and HTMLFILE are
        //     intentionally separate so a failure cleanly names which of
        //     the two outputs is missing or empty.
        assertThat(actualStmt)
                .as("Plain-text STMTFILE output must exist at the configured path "
                        + "(CREASTMT.JCL STMTFILE DD / FD-STMTFILE-REC PIC X(80))")
                .exists();
        assertThat(Files.size(actualStmt))
                .as("Plain-text STMTFILE must be non-empty "
                        + "(CBSTM03A always writes at least the start-of-statement banner)")
                .isGreaterThan(0L);

        // (3) HTMLFILE output existence and non-emptiness — the second of
        //     the two outputs CBSTM03A produces. Asserted independently
        //     with its own .as(...) description so a regression that drops
        //     ONLY the HTML output (without affecting the text output)
        //     fails with a clear diagnostic.
        assertThat(actualHtml)
                .as("HTML HTMLFILE output must exist at the configured path "
                        + "(CREASTMT.JCL HTMLFILE DD / FD-HTMLFILE-REC PIC X(100))")
                .exists();
        assertThat(Files.size(actualHtml))
                .as("HTML HTMLFILE must be non-empty "
                        + "(CBSTM03A always writes at least the HTML header + body wrapper)")
                .isGreaterThan(0L);

        // (4) STMTFILE record-width invariant — every emitted line must be
        //     exactly 80 bytes per FD-STMTFILE-REC PIC X(80) (CBSTM03A.CBL
        //     line 45). Per AAP §0.10.4 the immutable-boundary contract
        //     forbids any width drift. The assertion iterates every
        //     emitted record and identifies the first non-conformant line
        //     by index so a regression is easy to localise.
        final List<String> stmtLines = Files.readAllLines(actualStmt, StandardCharsets.US_ASCII);
        assertThat(stmtLines)
                .as("STMTFILE must contain at least one record line")
                .isNotEmpty();
        for (int i = 0; i < stmtLines.size(); i++) {
            assertThat(stmtLines.get(i).length())
                    .as("STMTFILE line %d must be exactly %d bytes per FD-STMTFILE-REC PIC X(80). "
                            + "Actual='%s'", i, STMTFILE_RECORD_LENGTH, stmtLines.get(i))
                    .isEqualTo(STMTFILE_RECORD_LENGTH);
        }

        // (5) HTMLFILE record-width invariant — every emitted line must be
        //     exactly 100 bytes per FD-HTMLFILE-REC PIC X(100)
        //     (CBSTM03A.CBL line 47). Same per-record iteration pattern as
        //     the STMTFILE assertion above.
        final List<String> htmlLines = Files.readAllLines(actualHtml, StandardCharsets.UTF_8);
        assertThat(htmlLines)
                .as("HTMLFILE must contain at least one record line")
                .isNotEmpty();
        for (int i = 0; i < htmlLines.size(); i++) {
            assertThat(htmlLines.get(i).length())
                    .as("HTMLFILE line %d must be exactly %d bytes per FD-HTMLFILE-REC PIC X(100). "
                            + "Actual='%s'", i, HTMLFILE_RECORD_LENGTH, htmlLines.get(i))
                    .isEqualTo(HTMLFILE_RECORD_LENGTH);
        }

        // (6) Per-customer aggregation invariant — every customer that
        //     appears in custdata.txt and has at least one matching
        //     transaction in the TRNXFILE input must have a corresponding
        //     statement banner in the STMTFILE. The CBSTM03A 5000-STATEMENT-
        //     HEAD paragraph writes the START_OF_STATEMENT banner once
        //     per customer; the count of banner lines is therefore the
        //     count of distinct customers covered by the statement run.
        //     Per AAP §0.10.1 the IT does NOT recompute per-customer
        //     transaction subtotals — that arithmetic remains in the
        //     migrated production aggregator and is byte-checked by the
        //     companion baseline-parity IT. The IT only asserts that the
        //     aggregator emitted AT LEAST one banner (proving the
        //     per-customer iteration is wired and not collapsing all
        //     customers into a single statement).
        final long bannerCount = stmtLines.stream()
                .filter(line -> line.contains(TestFixtures.Branding.START_OF_STATEMENT))
                .count();
        assertThat(bannerCount)
                .as("STMTFILE must contain at least one per-customer START-OF-STATEMENT banner "
                        + "(CBSTM03A 5000-STATEMENT-HEAD paragraph emits the banner once per customer). "
                        + "Bannerless output indicates the per-customer iteration is broken or the "
                        + "aggregator collapsed all customers into a single statement.")
                .isGreaterThanOrEqualTo(1L);

        // (7) Max-transactions-per-card invariant — CBSTM03A's WS-TRAN-TBL
        //     OCCURS 10 TIMES (CBSTM03A.CBL line 228) caps the transaction
        //     detail array at 10 rows per card-statement page. The IT
        //     asserts that no produced statement page exceeds this cap.
        //     Per AAP §0.10.1 the IT does NOT inspect the page-break
        //     algorithm — it only verifies the cap is honoured by
        //     scanning between consecutive END_OF_STATEMENT markers
        //     and counting transaction-detail lines. A transaction-detail
        //     line is recognised by carrying the per-transaction
        //     identifier pattern; here we use the conservative
        //     "line starts with a digit at column 1" heuristic which
        //     captures every TRAN-ID-prefixed detail row and excludes
        //     header/footer/banner lines. The actual cap value is the
        //     COBOL constant MAX_TRANSACTIONS_PER_CARD = 10.
        int detailCountForCurrentCard = 0;
        for (String line : stmtLines) {
            if (line.contains(TestFixtures.Branding.START_OF_STATEMENT)
                    || line.contains(TestFixtures.Branding.END_OF_STATEMENT)) {
                // Reset on every statement boundary — the per-card cap
                // is enforced within a single statement page.
                detailCountForCurrentCard = 0;
                continue;
            }
            if (!line.isEmpty() && Character.isDigit(line.charAt(0))) {
                detailCountForCurrentCard++;
                assertThat(detailCountForCurrentCard)
                        .as("STMTFILE per-card transaction detail count must not exceed %d "
                                + "(CBSTM03A WS-TRAN-TBL OCCURS 10 TIMES at CBSTM03A.CBL line 228). "
                                + "Triggering line='%s'",
                                MAX_TRANSACTIONS_PER_CARD, line)
                        .isLessThanOrEqualTo(MAX_TRANSACTIONS_PER_CARD);
            }
        }
    }

    // =========================================================================
    // Private helpers — staging classpath fixtures onto the @TempDir filesystem
    // =========================================================================

    /**
     * Loads a canonical baseline-input fixture from the test classpath
     * ({@code src/test/resources/baseline/input/}) and writes it to the
     * {@link #workDir} {@link TempDir} so the Spring Batch Job's
     * {@code FlatFileItemReader} can consume it from a real filesystem path.
     *
     * <p>The helper exists because {@code FlatFileItemReader} expects a
     * {@link java.nio.file.Path} or {@code Resource} backed by a real
     * filesystem location, NOT a classpath resource — staging through
     * {@link Files#write(Path, byte[], java.nio.file.OpenOption...)} is the
     * idiomatic Spring Batch test pattern.
     *
     * <p>Byte-level write (not character-level) preserves the original
     * fixed-width COBOL record byte layout including any trailing whitespace
     * and the original line-ending convention; AAP §0.10.4 ("Input and
     * output file formats and record layouts MUST remain identical") forbids
     * any charset / line-ending normalisation in the test harness.
     *
     * <p>Helper is duplicated from {@link TransactionReportJobIT} rather
     * than extracted into a shared base-class utility — AAP §0.10.2
     * Minimal Change Clause forbids introducing abstractions that exist
     * only to flatter the test code. The four-line helper is simpler to
     * read inline than to navigate through a base-class indirection, and
     * the duplication is contained to ITs that share the identical
     * staging mechanism.
     *
     * @param filename simple basename of the fixture (e.g.
     *                 {@code "cardxref.txt"}) — must be one of the canonical
     *                 fixtures under
     *                 {@link TestFixtures.Paths#CLASSPATH_BASELINE_INPUT_DIR}
     * @return absolute {@link Path} to the staged copy inside
     *         {@link #workDir} ready to be passed as a Spring Batch
     *         {@code JobParameters} string value
     * @throws java.io.IOException when the {@link Files#write} call fails
     *                              (disk full, permission denied, or the
     *                              {@link TempDir} root has been removed
     *                              externally)
     */
    private Path stageBaselineInput(String filename) throws java.io.IOException {
        final byte[] bytes = FixtureLoader.loadAsBytes(
                TestFixtures.Paths.CLASSPATH_BASELINE_INPUT_DIR + filename);
        final Path staged = workDir.resolve(filename);
        Files.write(staged, bytes);
        return staged;
    }

    /**
     * Loads a captured baseline-expected fixture from the test classpath
     * ({@code src/test/resources/baseline/expected/}) and writes it to the
     * {@link #workDir} {@link TempDir}.
     *
     * <p>Used to stage the {@code combined.txt} fixture as the TRNXFILE
     * input for this IT; that fixture is the captured output of the
     * upstream COMBTRAN.jcl pipeline step (see
     * {@code CombineTransactionsBaselineParityIT}) and represents what the
     * migrated DFSORT-equivalent step produces when given the canonical
     * daily transactions in {@code dailytran.txt}.
     *
     * <p>Identical mechanism to {@link #stageBaselineInput(String)} but
     * sourced from a different classpath root. The split between
     * baseline-input fixtures and baseline-expected fixtures matches AAP
     * §0.4.4 (Fixture Organization Strategy): canonical golden inputs live
     * under {@code baseline/input/}, captured COBOL reference outputs live
     * under {@code baseline/expected/}, and an IT pipeline that exercises a
     * downstream-only step (like this one) consumes the upstream step's
     * expected output as its input.
     *
     * <p>The {@code combined.txt} fixture's record layout matches the
     * {@code app/cbl/CBSTM03B.cbl} FD declaration for {@code TRNX-FILE}
     * (16-char {@code FD-TRNX-CARD} + 16-char {@code FD-TRNX-ID} + 318-byte
     * {@code FD-ACCT-DATA}, total 350 bytes per record); the upstream
     * COMBTRAN-equivalent step is responsible for producing exactly that
     * layout from the daily transactions input.
     *
     * @param filename simple basename of the expected-output fixture (e.g.
     *                 {@code "combined.txt"}) — must be one of the captured
     *                 reference outputs under
     *                 {@link TestFixtures.Paths#CLASSPATH_BASELINE_EXPECTED_DIR}
     * @return absolute {@link Path} to the staged copy inside
     *         {@link #workDir} ready to be passed as a Spring Batch
     *         {@code JobParameters} string value
     * @throws java.io.IOException when the {@link Files#write} call fails
     *                              (disk full, permission denied, or the
     *                              {@link TempDir} root has been removed
     *                              externally)
     */
    private Path stageExpectedFixture(String filename) throws java.io.IOException {
        final byte[] bytes = FixtureLoader.loadAsBytes(
                TestFixtures.Paths.CLASSPATH_BASELINE_EXPECTED_DIR + filename);
        final Path staged = workDir.resolve(filename);
        Files.write(staged, bytes);
        return staged;
    }
}
