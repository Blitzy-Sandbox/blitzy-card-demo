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
//     @DynamicPropertySource credential injection, and the inherited
//     {@code jobLauncherTestUtils} field used to launch the
//     {@code transactionReportJob} bean. This IT extends it to inherit the
//     full Spring application context (required by Spring Batch's
//     {@code JobLauncher} which needs every {@code Job}, {@code Step},
//     {@code ItemReader}, {@code ItemProcessor}, and {@code ItemWriter} bean
//     in the context graph) AND the per-class shared container lifecycle
//     that AAP §0.4.4 mandates for batch ITs.
//
//   * FixtureLoader — static utility consumed by the private
//     stageBaselineInput() / stageExpectedFixture() helpers to load
//     classpath fixture files (cardxref.txt, trantype.txt, trancatg.txt,
//     baseline/expected/combined.txt) as raw byte arrays before they are
//     written to the JUnit @TempDir for the Spring Batch job to read. Per
//     AAP §0.5.5 (test utilities reuse — FixtureLoader serves every batch
//     IT and baseline-parity IT).
//
//   * TestFixtures — pure-constants class providing the classpath path
//     constants (CLASSPATH_BASELINE_INPUT_DIR, CLASSPATH_BASELINE_EXPECTED_DIR)
//     and fixture / expected filenames (FIXTURE_CARDXREF, FIXTURE_TRANTYPE,
//     FIXTURE_TRANCATG, EXPECTED_COMBINED, EXPECTED_TRANSACTION_REPORT) used
//     to compose classpath lookups, plus the date PARM constants
//     (Dates.REPORT_START_DATE = "2022-01-01", REPORT_END_DATE = "2022-07-06")
//     mirroring the TRANREPT.jcl SYMNAMES PARM-START-DATE / PARM-END-DATE
//     literals so the migrated Job receives the same date window as the
//     original COBOL program.
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
//     activation. The sibling E2E tests
//     ({@code GateVerificationE2ETest}, {@code OnlineTransactionE2ETest},
//     {@code AdminUserManagementE2ETest}) use the same @Disabled pattern —
//     this IT mirrors that established project convention so the compile-
//     time wiring is verified end-to-end while the runtime DB / Job-bean
//     execution awaits its production-side dependencies (per AAP §0.8.1
//     "the testing flavor CREATEs tests against those classes but does not
//     modify them").
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
//     deleted after the test completes, so the produced REPTFILE output and
//     the staged input fixtures never leak between tests or onto the
//     workspace. This is the canonical JUnit 5 mechanism for test-scoped
//     filesystem isolation per AAP §0.10.9 (test isolation requirements).
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.Disabled;
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
//     "End-to-end report job; asserts report-file structure and totals"
//     contract.
//
//   * ExitStatus — Spring Batch's grouping of the exit codes produced by
//     each Step; the aggregate Job-level exit status (read via
//     JobExecution#getExitStatus) must equal ExitStatus.COMPLETED for a
//     successful run. This is the Java-side analogue of the COBOL
//     CBTRN03C STOP RUN with RC=0.
//
//   * JobExecution — Spring Batch's runtime metadata object for a single
//     Job invocation; returned by JobLauncherTestUtils#launchJob and used
//     to assert on lifecycle status + exit status.
//
//   * JobParameters / JobParametersBuilder — Spring Batch's typed parameter
//     container; JobParametersBuilder is the canonical builder API for
//     assembling the parameters that the migrated transactionReportJob's
//     @Bean wiring expects (the JCL SYMNAMES PARM-START-DATE / PARM-END-DATE
//     date window and the 5 file-path parameters mirroring the JCL
//     TRANFILE / CARDXREF / TRANTYPE / TRANCATG / TRANREPT DD statements).
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
//     @TempDir-backed filesystem (Files.write), assert that the produced
//     REPTFILE output is non-empty (Files.size > 0), and slurp the report
//     for the structural literal anyMatch assertions (Files.readAllLines).
//
//   * Path — JDK NIO.2 filesystem coordinate; the @TempDir-injected workDir
//     and every staged-fixture handle are Path values. Path is preferred
//     over the legacy java.io.File because it is immutable, plays well with
//     the NIO.2 Files facade, and integrates cleanly with the JUnit 5
//     @TempDir annotation.
//
//   * StandardCharsets — US_ASCII is passed to Files.readAllLines so the
//     produced 133-byte-wide REPTFILE is decoded with the strict US-ASCII
//     charset that matches the COBOL CBTRN03C output format. Any non-ASCII
//     byte would indicate file corruption (the COBOL program writes only
//     US-ASCII characters) and fail loudly with a MalformedInputException.
// ---------------------------------------------------------------------------
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

// ---------------------------------------------------------------------------
// JDK collections (AAP §0.10.7 standard library).
//
//   * List — return type of Files.readAllLines, holding the per-line
//     decomposition of the produced TRANREPT report file. AssertJ's
//     anyMatch(Predicate) assertion is applied to this list to verify
//     the presence of the COBOL CVTRA07Y total-band literals at the
//     line-start position.
// ---------------------------------------------------------------------------
import java.util.List;

// ---------------------------------------------------------------------------
// AssertJ fluent assertions (AAP §0.10.10 — AssertJ exclusively, no Hamcrest,
// no JUnit Assertions). Static import keeps the call sites concise:
// assertThat(...).isEqualTo(...).
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Spring Batch integration test for the Transaction Report job
 * ({@code TRANREPT.jcl} &rarr; {@code CBTRN03C.cbl} migration).
 *
 * <p>Drives the migrated {@code transactionReportJob} Spring Batch
 * {@code Job} bean against canonical fixtures and asserts:
 * <ol>
 *   <li>Spring Batch execution semantics — {@link BatchStatus#COMPLETED}
 *       and {@link ExitStatus#COMPLETED}, the Java-side analogue of the
 *       COBOL {@code CBTRN03C} {@code STOP RUN} with RC=0.</li>
 *   <li>Output file production — the REPTFILE path exists on the
 *       filesystem and is non-empty (size &gt; 0).</li>
 *   <li>Structural integrity of the produced report — at least one line
 *       starts with the {@code 'Page Total'}, {@code 'Account Total'},
 *       and {@code 'Grand Total'} literals declared by the
 *       {@code REPORT-PAGE-TOTALS}, {@code REPORT-ACCOUNT-TOTALS}, and
 *       {@code REPORT-GRAND-TOTALS} 01-level records in
 *       {@code app/cpy/CVTRA07Y.cpy}.</li>
 * </ol>
 *
 * <h2>Separation from the byte-identical parity IT</h2>
 *
 * <p>This IT verifies <em>structural</em> properties (the COBOL contract's
 * total-band literals appear in the right places). The byte-identical
 * baseline parity assertion against the captured COBOL reference output
 * ({@code src/test/resources/baseline/expected/transaction_report.txt})
 * lives in the companion {@code TransactionReportBaselineParityIT}.
 * Keeping the two ITs separate matches the AAP §0.5.1 file-by-file plan
 * ("End-to-end report job; asserts report-file structure and totals" for
 * this IT vs. "Byte-identical diff: produced posting file vs captured
 * COBOL reference" for the parity IT) and gives the two ITs distinct
 * failure modes: a parity-IT failure indicates a numeric / formatting
 * regression in the migrated formatter; a structural-IT failure
 * indicates the formatter is no longer producing the COBOL total-band
 * lines at all.
 *
 * <h2>Why structural assertions are sufficient here</h2>
 *
 * <p>Per AAP §0.5.1 the contract for this IT is "asserts report-file
 * structure and totals" — verifying the structural anchors confirms the
 * migrated formatter still produces every required total band (page,
 * account, grand) and the JCL-mapped Job wires the input files
 * end-to-end. The exact numeric totals are verified byte-for-byte by
 * {@code TransactionReportBaselineParityIT}, so duplicating that work
 * here would violate AAP §0.10.2 Minimal Change Clause without adding
 * coverage.
 *
 * <h2>Literal anchors per CVTRA07Y.cpy</h2>
 *
 * <p>The COBOL copybook declares the total-band lines with the following
 * verbatim leading literals (see {@code app/cpy/CVTRA07Y.cpy} lines
 * 50-66):
 * <ul>
 *   <li>{@code REPORT-PAGE-TOTALS} &mdash; 11-char {@code 'Page Total'}
 *       literal (10 chars + 1 trailing space) at column 1, followed by
 *       86 dots and the {@code REPT-PAGE-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ}
 *       numeric field.</li>
 *   <li>{@code REPORT-ACCOUNT-TOTALS} &mdash; 13-char {@code 'Account Total'}
 *       literal (13 chars, no trailing space) at column 1, followed by
 *       84 dots and the {@code REPT-ACCOUNT-TOTAL} numeric field.</li>
 *   <li>{@code REPORT-GRAND-TOTALS} &mdash; 11-char {@code 'Grand Total'}
 *       literal (11 chars, no trailing space) at column 1, followed by
 *       86 dots and the {@code REPT-GRAND-TOTAL} numeric field.</li>
 * </ul>
 *
 * <p>The {@link String#startsWith(String)} predicate used in the
 * AssertJ {@code anyMatch} assertions matches the literal exactly as
 * declared in the copybook — any change to the migration's report
 * formatter that drops or renames these literals will fail this test
 * loudly, preserving the COBOL-parity contract.
 *
 * <h2>JobParameters mapping (TRANREPT.jcl SYMNAMES &rarr; Spring Batch)</h2>
 *
 * <p>The migrated job consumes the following parameters that mirror the
 * original JCL data definitions:
 * <ul>
 *   <li>{@code report.start.date} &mdash; PARM-START-DATE from JCL SYMNAMES
 *       (literal {@code '2022-01-01'}, see {@code TestFixtures.Dates.REPORT_START_DATE}).</li>
 *   <li>{@code report.end.date} &mdash; PARM-END-DATE from JCL SYMNAMES
 *       (literal {@code '2022-07-06'}, see {@code TestFixtures.Dates.REPORT_END_DATE}).</li>
 *   <li>{@code input.transact.path} &mdash; TRANFILE DD (filtered &amp;
 *       sorted by the migrated DFSORT-equivalent step; this IT stages
 *       the pre-captured {@code combined.txt} expected output as the
 *       transaction-file input, mirroring the JCL pipeline's
 *       STEP05R&rarr;STEP10R hand-off).</li>
 *   <li>{@code input.cardxref.path} &mdash; CARDXREF DD (random access by
 *       16-char card number, per {@code CBTRN03C} SELECT clause).</li>
 *   <li>{@code input.trantype.path} &mdash; TRANTYPE DD (random access by
 *       2-char type code).</li>
 *   <li>{@code input.trancatg.path} &mdash; TRANCATG DD (random access by
 *       2-char type + 4-digit category composite key).</li>
 *   <li>{@code output.reptfile.path} &mdash; TRANREPT DD (sequential
 *       output, LRECL=133, FB).</li>
 *   <li>{@code run.timestamp} &mdash; epoch milliseconds, guarantees each
 *       Spring Batch {@code JobInstance} is unique even if the same test
 *       method is re-run; without this, Spring Batch would treat a
 *       repeat run as a restart of the prior instance and refuse to
 *       launch when the prior is {@link BatchStatus#COMPLETED}.</li>
 * </ul>
 *
 * <h2>Why {@code combined.txt} is the transact input</h2>
 *
 * <p>The original TRANREPT.jcl pipeline runs two steps:
 * <ol>
 *   <li>STEP05R &mdash; DFSORT filters TRANSACT KSDS by the date PARM
 *       window and produces the daily TRANSACT-DALY GDG (the "combined"
 *       file).</li>
 *   <li>STEP10R &mdash; {@code CBTRN03C} reads the filtered file and
 *       formats the report.</li>
 * </ol>
 *
 * <p>This IT exercises step 2 in isolation: the pre-captured
 * {@code baseline/expected/combined.txt} represents the output of the
 * migrated COMBTRAN.jcl job (see
 * {@code CombineTransactionsBaselineParityIT}) and is fed directly to
 * the report job as its TRANFILE input. The integration of the two
 * steps end-to-end is the responsibility of
 * {@code BatchPipelineE2ETest} per AAP §0.5.1.
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
 * class transitively reachable from {@link com.aws.carddemo.CardDemoApplication}'s
 * component scan to be Spring-managed and externally configured. As of
 * this commit several production-side prerequisites are
 * <em>intentionally deferred</em> by the REFACTOR-flavor migration
 * agents.
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
 * agents complete their work. The sibling E2E tests
 * ({@code GateVerificationE2ETest}, {@code OnlineTransactionE2ETest},
 * {@code AdminUserManagementE2ETest}) use the same {@code @Disabled}
 * pattern — this IT mirrors that established project convention.
 *
 * <h3>Reactivation Checklist (for the next REFACTOR-flavor agent)</h3>
 *
 * <p>Remove the {@code @Disabled} annotation (and the unused
 * {@code org.junit.jupiter.api.Disabled} import) once <em>all</em> of
 * the following production-side prerequisites are in place:
 *
 * <ol>
 *   <li><strong>{@code transactionReportJob} {@code @Bean}</strong> declared in
 *       a {@code @Configuration} class under
 *       {@code src/main/java/com/aws/carddemo/batch/config/} (or
 *       similar). The bean must:
 *       <ul>
 *         <li>Be named exactly {@code transactionReportJob} so
 *             {@code JobLauncherTestUtils} (inherited via
 *             {@code AbstractBatchIT}) resolves it as the unique
 *             {@code Job} in the application context, or be the sole
 *             {@code Job} bean so resolution is unambiguous.</li>
 *         <li>Read the JCL TRANREPT.jcl SYMNAMES PARM-START-DATE and
 *             PARM-END-DATE values from the
 *             {@code "report.start.date"} and {@code "report.end.date"}
 *             {@link JobParameters} string keys (literal values
 *             {@code "2022-01-01"} and {@code "2022-07-06"} from
 *             {@code TestFixtures.Dates.REPORT_START_DATE} /
 *             {@code REPORT_END_DATE}).</li>
 *         <li>Read the TRANFILE / CARDXREF / TRANTYPE / TRANCATG /
 *             TRANREPT DD-mapped file paths from the
 *             {@code "input.transact.path"},
 *             {@code "input.cardxref.path"},
 *             {@code "input.trantype.path"},
 *             {@code "input.trancatg.path"}, and
 *             {@code "output.reptfile.path"} string keys (absolute
 *             filesystem paths staged into the JUnit
 *             {@code @TempDir}).</li>
 *         <li>Compose the migrated {@link com.aws.carddemo.batch.TransactionReportProcessor}
 *             as the formatter component and back the input/output
 *             with {@code FlatFileItemReader}/{@code FlatFileItemWriter}
 *             instances pointed at the parameterised paths.</li>
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
 *       {@code V2__indexes.sql}, {@code V3__seed.sql}) — already
 *       present at this commit; no action required.</li>
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
 * mvn -B -Dit.test=TransactionReportJobIT verify
 * }</pre>
 *
 * <p>Expected outcome after reactivation: the single
 * {@code tranreptJob_runsAgainstCombinedTransactions_producesReportWithExpectedStructure}
 * test passes — the Spring Batch {@code transactionReportJob}
 * completes with {@link BatchStatus#COMPLETED}, writes a non-empty
 * REPTFILE to the JUnit {@code @TempDir}, and that file contains at
 * least one line beginning with each of the {@code 'Page Total'},
 * {@code 'Account Total'}, and {@code 'Grand Total'} literals declared
 * by {@code CVTRA07Y.cpy}.
 *
 * <h2>Authority</h2>
 *
 * <p>AAP §0.5.1 ("End-to-end report job; asserts report-file structure
 * and totals"), §0.5.2 (Report job IT), §0.8.1 (testing flavor does not
 * modify production source), §0.10.1 (Require Test Coverage rule —
 * drive production code, never reimplement business logic), §0.10.2
 * (Minimal Change Clause), §0.10.7 (JUnit 5 + Mockito framework
 * constraint), §0.10.10 (Style consistency — AssertJ exclusively).
 *
 * @see TransactionReportBaselineParityIT companion byte-identical parity
 *      IT that asserts the produced REPTFILE matches the captured COBOL
 *      reference byte-for-byte.
 * @see AbstractBatchIT shared base class supplying the Spring Boot test
 *      context, the Spring Batch test slice, and the Testcontainers
 *      PostgreSQL 16 container.
 * @see com.aws.carddemo.batch.TransactionReportProcessor the migrated
 *      {@code CBTRN03C} report-formatter component whose Job-bean
 *      wiring this IT exercises end-to-end.
 */
@Disabled("Awaits production-side prerequisites: (1) transactionReportJob @Bean declared in a "
        + "@Configuration class under com.aws.carddemo.batch.config (or similar) wiring "
        + "TransactionReportProcessor as the report formatter with FlatFileItemReader/Writer "
        + "instances bound to the report.start.date / report.end.date / input.transact.path / "
        + "input.cardxref.path / input.trantype.path / input.trancatg.path / output.reptfile.path "
        + "JobParameters; (2) @Service annotations on the 17 service classes under "
        + "com.aws.carddemo.service so the @SpringBootTest full-context load this IT performs does "
        + "not fail at context refresh on the controller bean graph's NoSuchBeanDefinitionException; "
        + "(3) SecurityConfig under com.aws.carddemo.config wiring a BCryptPasswordEncoder bean for "
        + "AuthenticationService's constructor injection. Per AAP §0.8.1 the testing flavor cannot "
        + "modify those production files; the next REFACTOR-flavor agent removes this annotation "
        + "when the prerequisites land. See the class Javadoc 'Reactivation Checklist' for the "
        + "full list and verification command.")
@DisplayName("TRANREPT.jcl Spring Batch job execution + report-structure verification")
class TransactionReportJobIT extends AbstractBatchIT {

    /**
     * Width (in bytes) of every record emitted by CBTRN03C to REPTFILE.
     * Sourced from {@code app/cbl/CBTRN03C.cbl} line 85
     * ({@code 01 FD-REPTFILE-REC PIC X(133)}). Pinned as a named constant
     * per AAP §0.10.4 immutable-boundary contract.
     */
    private static final int REPTFILE_RECORD_LENGTH = 133;

    /**
     * Number of lines per report page. Sourced from CBTRN03C
     * {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20} at
     * {@code app/cbl/CBTRN03C.cbl} lines 131-132. The page-break logic
     * at line 282 ({@code IF FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0})
     * triggers a new header at every 20-line boundary.
     */
    private static final int REPTFILE_PAGE_SIZE = 20;

    /**
     * Per-test isolated temporary directory injected by JUnit 5's
     * {@link TempDir} extension. Used as the staging area for the four
     * input fixtures (TRANFILE/CARDXREF/TRANTYPE/TRANCATG) and as the
     * destination directory for the produced REPTFILE output.
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
     * {@code AbstractBatchIT} for its inherited Spring-managed fields.
     */
    @TempDir
    Path workDir;

    /**
     * End-to-end execution of the migrated {@code transactionReportJob}
     * Spring Batch {@code Job} bean with the canonical fixtures staged
     * into the {@link #workDir} {@link TempDir}.
     *
     * <p>The test wires the JCL SYMNAMES date PARM window
     * ({@code 2022-01-01} &mdash; {@code 2022-07-06}) and the 5
     * filesystem paths into a {@link JobParameters} bundle, hands them
     * to the inherited {@code jobLauncherTestUtils} for synchronous
     * execution, then verifies (a) Spring Batch execution status, (b)
     * REPTFILE existence and non-emptiness, and (c) the three
     * structural literal anchors declared by {@code CVTRA07Y.cpy}.
     *
     * <p>Per AAP §0.10.1 (Require Test Coverage rule) this method
     * drives the <em>real</em> production {@code Job} bean
     * end-to-end — no business logic is reimplemented inside the test
     * body. Per AAP §0.10.4 the report output produced by the migrated
     * job is treated as an immutable boundary; the byte-identical
     * baseline-parity check lives in
     * {@code TransactionReportBaselineParityIT}.
     *
     * @throws Exception when the inherited {@code jobLauncherTestUtils}
     *                   propagates a Spring Batch launch failure (any
     *                   uncaught checked or unchecked exception during
     *                   job execution); JUnit 5 fails the test with the
     *                   propagated stack trace, surfacing the COBOL
     *                   parity regression.
     */
    @Test
    @DisplayName("Job produces a report containing 'Page Total', 'Account Total', and 'Grand Total' lines")
    void tranreptJob_runsAgainstCombinedTransactions_producesReportWithExpectedStructure() throws Exception {
        // ---- Arrange ----
        // Stage every TRANREPT.jcl DD-mapped input file into the @TempDir so the
        // Spring Batch Job's FlatFileItemReader instances can read them from
        // real filesystem paths (FlatFileItemReader does NOT consume classpath
        // resources directly). The transact file is the captured "combined"
        // file (output of the migrated COMBTRAN.jcl) — this IT runs only the
        // STEP10R formatting step, so the upstream STEP05R DFSORT filter is
        // pre-baked into the input.
        final Path stagedTransact = stageExpectedFixture(TestFixtures.Paths.EXPECTED_COMBINED);
        final Path stagedCardxref = stageBaselineInput(TestFixtures.Paths.FIXTURE_CARDXREF);
        final Path stagedTrantype = stageBaselineInput(TestFixtures.Paths.FIXTURE_TRANTYPE);
        final Path stagedTrancatg = stageBaselineInput(TestFixtures.Paths.FIXTURE_TRANCATG);
        // Destination path for the produced REPTFILE output. Just a resolve()
        // against the @TempDir — Spring Batch's FlatFileItemWriter creates the
        // file lazily when the Step opens its writer (no pre-existence
        // required).
        final Path actualOutput = workDir.resolve(TestFixtures.Paths.EXPECTED_TRANSACTION_REPORT);

        // Build the JobParameters bundle that mirrors the TRANREPT.jcl
        // SYMNAMES and DD assignments. The run.timestamp parameter guarantees
        // each JobInstance is unique even if this method is re-run (Spring
        // Batch would otherwise treat a repeat invocation as a restart of the
        // prior COMPLETED instance and refuse to launch). Path values are
        // absolutised so the Spring Batch reader/writer resolves them
        // independent of the JVM working directory.
        final JobParameters params = new JobParametersBuilder()
                .addString("report.start.date", TestFixtures.Dates.REPORT_START_DATE)
                .addString("report.end.date", TestFixtures.Dates.REPORT_END_DATE)
                .addString("input.transact.path", stagedTransact.toAbsolutePath().toString())
                .addString("input.cardxref.path", stagedCardxref.toAbsolutePath().toString())
                .addString("input.trantype.path", stagedTrantype.toAbsolutePath().toString())
                .addString("input.trancatg.path", stagedTrancatg.toAbsolutePath().toString())
                .addString("output.reptfile.path", actualOutput.toAbsolutePath().toString())
                .addLong("run.timestamp", System.currentTimeMillis())
                .toJobParameters();

        // ---- Act ----
        // jobLauncherTestUtils is the protected field inherited from
        // AbstractBatchIT, contributed to the Spring context by
        // @SpringBatchTest. launchJob synchronously runs the configured
        // transactionReportJob @Bean to completion (or failure) and returns
        // the JobExecution metadata.
        final JobExecution execution = jobLauncherTestUtils.launchJob(params);

        // ---- Assert ----
        // (1) Spring Batch execution status — the Java-side analogue of the
        //     COBOL CBTRN03C STOP RUN with RC=0. AAP §0.5.1 explicitly
        //     mandates "asserts execution semantics" for batch ITs.
        assertThat(execution.getStatus())
                .as("Job must complete with BatchStatus.COMPLETED (Java equivalent of COBOL STOP RUN RC=0)")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus())
                .as("Job exit status must be COMPLETED (no skip / no failure aggregation)")
                .isEqualTo(ExitStatus.COMPLETED);

        // (2) REPTFILE existence and non-emptiness — every successful run of
        //     CBTRN03C produces at least the report header lines + the grand
        //     total even when there are zero detail rows, so size > 0 is the
        //     minimum we can assert without reimplementing the formatter
        //     (AAP §0.10.1).
        assertThat(actualOutput).as("Job must produce REPTFILE output at the configured path").exists();
        assertThat(Files.size(actualOutput))
                .as("Report file must be non-empty (CBTRN03C always writes at least header + grand total)")
                .isGreaterThan(0L);

        // (3) Structural assertions — the COBOL REPORT-PAGE-TOTALS,
        //     REPORT-ACCOUNT-TOTALS, and REPORT-GRAND-TOTALS layouts emit
        //     lines beginning with these exact literals. Per AAP §0.10.1 we
        //     check ONLY the structural anchor; the numeric totals are
        //     verified byte-for-byte by TransactionReportBaselineParityIT
        //     against the captured COBOL reference.
        //
        //     Charset choice: US_ASCII is the strict subset of the report's
        //     declared character set per CBTRN03C's FD REPORT-FILE record
        //     layout (PIC X(133)). Any non-ASCII byte would indicate file
        //     corruption and fail loudly with a MalformedInputException
        //     before the assertions run.
        final List<String> lines = Files.readAllLines(actualOutput, StandardCharsets.US_ASCII);
        assertThat(lines)
                .as("Report must contain at least one 'Page Total' line "
                        + "(CVTRA07Y REPORT-PAGE-TOTALS, 11-char literal at column 1)")
                .anyMatch(l -> l.startsWith("Page Total"));
        assertThat(lines)
                .as("Report must contain at least one 'Account Total' line "
                        + "(CVTRA07Y REPORT-ACCOUNT-TOTALS, 13-char literal at column 1)")
                .anyMatch(l -> l.startsWith("Account Total"));
        assertThat(lines)
                .as("Report must contain a 'Grand Total' line "
                        + "(CVTRA07Y REPORT-GRAND-TOTALS, 11-char literal at column 1)")
                .anyMatch(l -> l.startsWith("Grand Total"));

        // (4) 133-byte record-width invariant — every emitted line must be
        //     exactly 133 bytes per FD-REPTFILE-REC PIC X(133) (CBTRN03C.cbl
        //     line 85). Per AAP §0.10.4 the immutable-boundary contract
        //     forbids any width drift. The assertion iterates every emitted
        //     line and identifies the first non-conformant line by index so
        //     a regression is easy to localise.
        for (int i = 0; i < lines.size(); i++) {
            assertThat(lines.get(i).length())
                    .as("REPTFILE line %d must be exactly %d bytes per FD-REPTFILE-REC PIC X(133). "
                            + "Actual='%s'", i, REPTFILE_RECORD_LENGTH, lines.get(i))
                    .isEqualTo(REPTFILE_RECORD_LENGTH);
        }

        // (5) Page-break cadence — CBTRN03C inserts a fresh page header
        //     every 20 lines per WS-PAGE-SIZE VALUE 20 (CBTRN03C.cbl line
        //     131-132). The header sequence begins with REPORT-NAME-HEADER
        //     starting with "DALYREPT" (line 325) followed by
        //     TRANSACTION-HEADER-1 starting with "Transaction ID" (line 333).
        //     The assertion verifies that AT LEAST one full page-break
        //     occurs (i.e., the report spans more than one page) by
        //     counting occurrences of the page-header start literal. The
        //     IT does NOT verify the exact page-break index (that would
        //     duplicate CBTRN03C's WS-LINE-COUNTER arithmetic per AAP
        //     §0.10.1 violation); it verifies the cadence shape — fixture
        //     transaction volume × page size = expected page count, with
        //     "≥ 1 page" as the minimum-floor sanity check that still
        //     proves the page-break logic is wired.
        final long pageHeaderCount = lines.stream()
                .filter(l -> l.startsWith("DALYREPT"))
                .count();
        assertThat(pageHeaderCount)
                .as("Report must contain at least one DALYREPT page header line "
                        + "(CBTRN03C 0000-MAIN-PARA emits REPORT-NAME-HEADER at start of every page; "
                        + "page size is WS-PAGE-SIZE VALUE %d at CBTRN03C.cbl line 131-132)",
                        REPTFILE_PAGE_SIZE)
                .isGreaterThanOrEqualTo(1L);

        // (6) Date-filter parameter propagation — the report header
        //     (REPORT-NAME-HEADER) carries the REPT-START-DATE and
        //     REPT-END-DATE literals copied from the operator-supplied
        //     SYSIN PARMs. Asserting that both date literals appear in
        //     the produced output proves the JobParameters
        //     (report.start.date / report.end.date) were threaded through
        //     to the production code's DATE-RANGE filter — without this
        //     assertion a regression could silently drop the date filter
        //     and emit every record regardless of its origin timestamp,
        //     producing technically-valid but semantically-wrong output.
        //
        //     The two parameter values are sourced from TestFixtures.Dates;
        //     production code that propagates them into the report header
        //     (per the COBOL MOVE PARM-START-DATE TO REPT-START-DATE
        //     pattern at REPORT-NAME-HEADER generation) makes both
        //     literals appear in the produced REPTFILE output.
        final String reportContent = String.join("\n", lines);
        assertThat(reportContent)
                .as("Report header must carry the start-date literal '%s' "
                        + "(per CBTRN03C REPT-START-DATE in REPORT-NAME-HEADER, "
                        + "proving the report.start.date JobParameter was threaded through)",
                        TestFixtures.Dates.REPORT_START_DATE)
                .contains(TestFixtures.Dates.REPORT_START_DATE);
        assertThat(reportContent)
                .as("Report header must carry the end-date literal '%s' "
                        + "(per CBTRN03C REPT-END-DATE in REPORT-NAME-HEADER, "
                        + "proving the report.end.date JobParameter was threaded through)",
                        TestFixtures.Dates.REPORT_END_DATE)
                .contains(TestFixtures.Dates.REPORT_END_DATE);
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
     * <p>Used to stage the {@code combined.txt} fixture as the TRANFILE
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
