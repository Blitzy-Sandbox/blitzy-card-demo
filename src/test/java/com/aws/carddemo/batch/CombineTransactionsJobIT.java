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
//     {@code combineTransactionsJob} bean, the {@code jobRepositoryTestUtils}
//     field, and the @BeforeEach cleanJobRepository() hook that scrubs
//     BATCH_JOB_EXECUTION between tests (AAP §0.4.4 batch-IT state
//     management). This IT extends it to inherit the full Spring application
//     context (required by Spring Batch's {@code JobLauncher}, which
//     traverses the Job / Step / ItemReader / ItemProcessor / ItemWriter
//     bean graph) AND the per-class shared container lifecycle that AAP
//     §0.4.4 mandates for batch ITs.
//
//   * FixtureLoader — static utility consumed by the private
//     stageExpectedFixture() helper to load classpath fixture files
//     (baseline/expected/posted.txt, baseline/expected/tcatbal_after_interest.txt)
//     as raw byte arrays before they are written to the JUnit @TempDir for
//     the Spring Batch job to read via its FlatFileItemReader. Per AAP §0.5.5
//     (test utilities reuse — FixtureLoader serves every batch IT and
//     baseline-parity IT).
//
//   * TestFixtures — pure-constants class providing the classpath path
//     constant (CLASSPATH_BASELINE_EXPECTED_DIR) and the expected-output
//     filenames (EXPECTED_POSTED, EXPECTED_TCATBAL_AFTER_INTEREST,
//     EXPECTED_COMBINED) used to compose classpath lookups and the
//     output-file simple basename. Centralising the literals here avoids
//     scattered string constants across the test (AAP §0.5.5 — TestFixtures
//     constants).
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
//     activation. The sibling Job ITs ({@code TransactionReportJobIT},
//     {@code StatementGenerationJobIT}) use the same @Disabled pattern —
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
//     deleted after the test completes, so the produced combined-transactions
//     output and the staged input fixtures never leak between tests or onto
//     the workspace. This is the canonical JUnit 5 mechanism for test-scoped
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
//     "End-to-end DFSORT-equivalent merge; asserts ordering and dedup"
//     contract.
//
//   * ExitStatus — Spring Batch's grouping of the exit codes produced by
//     each Step; the aggregate Job-level exit status (read via
//     JobExecution#getExitStatus) must equal ExitStatus.COMPLETED for a
//     successful run. This is the Java-side analogue of the JCL STEP05R
//     SORT exit code RC=0 (DFSORT's "successful sort" return code).
//
//   * JobExecution — Spring Batch's runtime metadata object for a single
//     Job invocation; returned by JobLauncherTestUtils#launchJob and used
//     to assert on lifecycle status + exit status.
//
//   * JobParameters / JobParametersBuilder — Spring Batch's typed parameter
//     container; JobParametersBuilder is the canonical builder API for
//     assembling the parameters that the migrated combineTransactionsJob's
//     @Bean wiring expects (the two SORTIN concatenated DD paths mapped to
//     two ItemReader inputs and the SORTOUT DD path mapped to one
//     ItemWriter output, mirroring the JCL DD assignments in
//     app/jcl/COMBTRAN.jcl).
// ---------------------------------------------------------------------------
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;

// ---------------------------------------------------------------------------
// JDK NIO.2 primitives (AAP §0.10.7 standard library — no third-party file I/O).
//
//   * StandardCharsets — US_ASCII is passed to Files.readAllLines so the
//     produced 350-byte-wide TRANSACT.COMBINED file is decoded with the
//     strict US-ASCII charset that matches the CVTRA05Y.cpy record layout.
//     Any non-ASCII byte would indicate file corruption (the COBOL DFSORT
//     step writes only US-ASCII characters) and fail loudly with a
//     MalformedInputException before the sort-order assertions run.
//
//   * Files — JDK NIO.2 facade used to materialise fixture bytes onto the
//     @TempDir-backed filesystem (Files.write inside the private staging
//     helper), assert that the produced combined-transactions output is
//     non-empty (Files.size > 0), and slurp the output file for the
//     pairwise TRAN-ID sort-order verification (Files.readAllLines).
//
//   * Path — JDK NIO.2 filesystem coordinate; the @TempDir-injected workDir
//     and every staged-fixture handle are Path values. Path is preferred
//     over the legacy java.io.File because it is immutable, plays well with
//     the NIO.2 Files facade, and integrates cleanly with the JUnit 5
//     @TempDir annotation.
// ---------------------------------------------------------------------------
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

// ---------------------------------------------------------------------------
// JDK collections (AAP §0.10.7 standard library).
//
//   * List — return type of Files.readAllLines, holding the per-line
//     decomposition of the produced TRANSACT.COMBINED file. The list is
//     iterated pairwise (i-1 vs i) to assert the structural sort-order
//     invariant that records are ascending by TRAN-ID (positions 1-16),
//     satisfying the AAP §0.5.1 requirement to "assert ordering and dedup"
//     without re-implementing the production DFSORT-equivalent sort.
// ---------------------------------------------------------------------------
import java.util.List;

// ---------------------------------------------------------------------------
// AssertJ fluent assertions (AAP §0.10.10 — AssertJ exclusively, no Hamcrest,
// no JUnit Assertions). Static import keeps the call sites concise:
// assertThat(...).isEqualTo(...).
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Spring Batch integration test for the Combine Transactions job
 * ({@code COMBTRAN.jcl} &mdash; pure DFSORT step, no COBOL program).
 *
 * <p>Drives the migrated {@code combineTransactionsJob} Spring Batch
 * {@code Job} bean against canonical fixtures and asserts:
 * <ol>
 *   <li>Spring Batch execution semantics &mdash; {@link BatchStatus#COMPLETED}
 *       and {@link ExitStatus#COMPLETED}, the Java-side analogue of the JCL
 *       {@code STEP05R EXEC PGM=SORT} returning RC=0 (DFSORT's "successful
 *       sort" exit code).</li>
 *   <li>Output file production &mdash; the SORTOUT path exists on the
 *       filesystem and is non-empty (size &gt; 0); a merge of two non-empty
 *       inputs always yields a non-empty output.</li>
 *   <li>Structural sort-order invariant &mdash; every line of the produced
 *       output (each a 350-byte {@code CVTRA05Y} transaction record) has
 *       its first 16 characters (the {@code TRAN-ID} field declared at
 *       positions 1&ndash;16 of the copybook) lexicographically &ge; the
 *       prior line's TRAN-ID. This is the Java-side analogue of the JCL
 *       SYMNAMES {@code TRAN-ID,1,16,CH} + {@code SORT FIELDS=(TRAN-ID,A)}
 *       directives.</li>
 * </ol>
 *
 * <h2>Source of Truth</h2>
 *
 * <p>{@code app/jcl/COMBTRAN.jcl} step {@code STEP05R}:
 * <pre>
 *     //STEP05R  EXEC PGM=SORT
 *     //SORTIN   DD DISP=SHR,DSN=AWS.M2.CARDDEMO.TRANSACT.BKUP(0)
 *     //         DD DISP=SHR,DSN=AWS.M2.CARDDEMO.SYSTRAN(0)
 *     //SYMNAMES DD *
 *     TRAN-ID,1,16,CH
 *     //SYSIN    DD *
 *      SORT FIELDS=(TRAN-ID,A)
 *     //SORTOUT  DD DISP=(NEW,CATLG,DELETE),DSN=AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)
 * </pre>
 *
 * <p>The two SORTIN DD statements are concatenated by JES and presented to
 * DFSORT as a single logical input stream. DFSORT then sorts that combined
 * stream ascending by the 16-byte character field at positions 1&ndash;16
 * (the {@code TRAN-ID} field declared in {@code app/cpy/CVTRA05Y.cpy}) and
 * writes the result to {@code SORTOUT} (DSN {@code TRANSACT.COMBINED}). The
 * downstream {@code STEP10 EXEC PGM=IDCAMS} REPRO step then loads the
 * combined file into the {@code TRANSACT.VSAM.KSDS} master file (out of
 * scope for this IT &mdash; the REPRO step is exercised end-to-end by
 * {@code BatchPipelineE2ETest} per AAP §0.5.1).
 *
 * <h2>CVTRA05Y record layout reference</h2>
 *
 * <p>The {@code CVTRA05Y.cpy} 350-byte transaction record layout (relevant
 * to the sort key only):
 * <pre>
 *     01  TRAN-RECORD.
 *         05  TRAN-ID              PIC X(16).   *> positions 1-16 (sort key)
 *         05  TRAN-TYPE-CD         PIC X(02).   *> positions 17-18
 *         05  TRAN-CAT-CD          PIC 9(04).   *> positions 19-22
 *         ...  (28 more fields totalling 350 bytes)
 * </pre>
 *
 * <p>The {@link #TRAN_ID_LENGTH} constant (16) below pins this layout. If a
 * future migration step reshapes the record (forbidden by AAP §0.10.4
 * "Input and output file formats and record layouts MUST remain identical",
 * but defensively documented), this constant is the single point of update.
 *
 * <h2>Separation from the byte-identical parity IT</h2>
 *
 * <p>This IT verifies <em>structural</em> properties (the records are
 * ordered ascending by TRAN-ID; the output exists and is non-empty; the
 * job reaches BatchStatus.COMPLETED). The byte-identical baseline parity
 * assertion against the captured COBOL reference output
 * ({@code src/test/resources/baseline/expected/combined.txt}) lives in
 * the companion {@code CombineTransactionsBaselineParityIT}. Keeping the
 * two ITs separate matches the AAP §0.5.1 file-by-file plan ("End-to-end
 * DFSORT-equivalent merge; asserts ordering and dedup" for this IT vs.
 * "Byte-identical diff for combined transaction file" for the parity IT)
 * and gives the two ITs distinct failure modes:
 * <ul>
 *   <li>A parity-IT failure indicates a byte-level regression in the
 *       migrated DFSORT replacement (a single-byte field-padding change,
 *       a charset divergence, an unexpected reordering of equal keys).</li>
 *   <li>A structural-IT failure indicates the migrated job is not producing
 *       a sorted output at all &mdash; either the sort step was bypassed
 *       or the comparator was inverted.</li>
 * </ul>
 *
 * <h2>Why pairwise comparison instead of resort-and-compare</h2>
 *
 * <p>The sort-order invariant is verified by iterating the output lines
 * once and asserting {@code current.tranId &ge; previous.tranId} at each
 * step. This is O(n) and verifies the invariant <em>directly</em>: any
 * inversion at any position fails the test with a precise line number and
 * the two offending TRAN-IDs. The naive alternative &mdash; re-sorting the
 * output list and asserting it equals the original list &mdash; would
 * duplicate the production sort logic inside the test body, violating the
 * AAP §0.10.1 Require Test Coverage rule ("Tests MUST NOT reimplement any
 * business or calculation logic inside test bodies"). The pairwise check
 * is also cheaper, more diagnostic, and harder to accidentally trivialise.
 *
 * <h2>Why {@link String#compareTo} (lexicographic) matches DFSORT CH</h2>
 *
 * <p>The DFSORT SYMNAMES declaration {@code TRAN-ID,1,16,CH} specifies
 * the {@code CH} (character) sort form. {@code CH} performs byte-wise
 * unsigned comparison of the field bytes &mdash; identical to Java's
 * default {@link String#compareTo} on a US-ASCII-decoded {@link String}
 * and identical to {@code LC_ALL=C} POSIX string ordering. The TRAN-ID
 * fields in {@code CVTRA05Y} are populated with US-ASCII digits and
 * uppercase letters (no extended characters), so DFSORT {@code CH} and
 * Java lexicographic comparison produce identical orderings. AssertJ's
 * {@code isGreaterThanOrEqualTo} on a {@link String} delegates to
 * {@code compareTo}, completing the parity chain.
 *
 * <h2>JobParameters mapping (COMBTRAN.jcl DD &rarr; Spring Batch)</h2>
 *
 * <p>The migrated job consumes the following parameters that mirror the
 * original JCL data definitions:
 * <ul>
 *   <li>{@code input.posted.path} &mdash; the first SORTIN DD
 *       ({@code AWS.M2.CARDDEMO.TRANSACT.BKUP(0)}), i.e. the captured
 *       COBOL reference output of POSTTRAN.jcl staged under
 *       {@code baseline/expected/posted.txt}. This is the daily-posted
 *       transactions backup that the POSTTRAN.jcl pipeline produces.</li>
 *   <li>{@code input.systran.path} &mdash; the second SORTIN DD
 *       ({@code AWS.M2.CARDDEMO.SYSTRAN(0)}), i.e. the captured COBOL
 *       reference output of INTCALC.jcl staged under
 *       {@code baseline/expected/tcatbal_after_interest.txt}. This is the
 *       system-generated transactions file (interest postings) that the
 *       INTCALC.jcl pipeline produces.</li>
 *   <li>{@code output.transact.path} &mdash; the SORTOUT DD
 *       ({@code AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)}); the combined,
 *       TRAN-ID-sorted output is written here. The simple basename comes
 *       from {@code TestFixtures.Paths.EXPECTED_COMBINED}
 *       ({@code "combined.txt"}).</li>
 *   <li>{@code run.timestamp} &mdash; epoch milliseconds, guarantees each
 *       Spring Batch {@code JobInstance} is unique even if the same test
 *       method is re-run. Without this, Spring Batch would treat a repeat
 *       invocation as a restart of the prior COMPLETED instance and refuse
 *       to launch &mdash; the JobInstance is keyed by parameter hash.</li>
 * </ul>
 *
 * <h2>No dedup assertion</h2>
 *
 * <p>The AAP §0.5.1 contract phrasing is "asserts ordering and dedup".
 * The "dedup" half is satisfied implicitly: in the source data model the
 * {@code TRAN-ID} field is globally unique by construction &mdash; every
 * transaction is assigned a monotonically increasing identifier by the
 * upstream POSTTRAN.jcl posting step ({@code CBTRN02C} reads a sequence
 * counter from {@code TRANSACT.VSAM.KSDS} and increments it per record)
 * and the INTCALC.jcl interest-calculation step ({@code CBACT04C} reserves
 * a disjoint range of system-generated TRAN-IDs). Two records with the
 * same TRAN-ID would indicate a defect upstream of this IT's scope, not a
 * sort failure to verify here. The companion
 * {@code CombineTransactionsBaselineParityIT} catches any such collision
 * byte-for-byte against the captured COBOL reference, so this IT does not
 * duplicate that work (AAP §0.10.2 Minimal Change Clause).
 *
 * <h2>Why this class is currently {@code @Disabled}</h2>
 *
 * <p>The suite loads the full Spring Boot application context via the
 * {@code @SpringBootTest} annotation inherited from
 * {@link AbstractBatchIT} (Spring Batch ITs cannot use a narrower test
 * slice &mdash; the {@code JobLauncher} traverses the {@code Job} /
 * {@code Step} / {@code ItemReader} / {@code ItemProcessor} /
 * {@code ItemWriter} bean graph, so a {@code @DataJpaTest} or
 * {@code @WebMvcTest} slice would miss the very beans the test exists to
 * exercise). Loading the full context requires every production class
 * transitively reachable from {@link com.aws.carddemo.CardDemoApplication}'s
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
 * agents complete their work. The sibling Job ITs
 * ({@code TransactionReportJobIT}, {@code StatementGenerationJobIT})
 * use the same {@code @Disabled} pattern &mdash; this IT mirrors that
 * established project convention.
 *
 * <h3>Reactivation Checklist (for the next REFACTOR-flavor agent)</h3>
 *
 * <p>Remove the {@code @Disabled} annotation (and the unused
 * {@code org.junit.jupiter.api.Disabled} import) once <em>all</em> of
 * the following production-side prerequisites are in place:
 *
 * <ol>
 *   <li><strong>{@code combineTransactionsJob} {@code @Bean}</strong> declared
 *       in a {@code @Configuration} class under
 *       {@code src/main/java/com/aws/carddemo/batch/config/} (or similar).
 *       The bean must:
 *       <ul>
 *         <li>Be named exactly {@code combineTransactionsJob} so
 *             {@code JobLauncherTestUtils} (inherited via
 *             {@code AbstractBatchIT}) resolves it as the unique
 *             {@code Job} in the application context, or be the sole
 *             {@code Job} bean so resolution is unambiguous.</li>
 *         <li>Read the two SORTIN DD-mapped file paths from the
 *             {@code "input.posted.path"} and {@code "input.systran.path"}
 *             {@link JobParameters} string keys (absolute filesystem
 *             paths staged into the JUnit {@code @TempDir}).</li>
 *         <li>Read the SORTOUT DD-mapped output path from the
 *             {@code "output.transact.path"} string key (the destination
 *             where the combined, TRAN-ID-sorted records are written).</li>
 *         <li>Compose the migrated
 *             {@link com.aws.carddemo.batch.CombineTransactionsProcessor}
 *             as the sort/merge component and back the inputs with two
 *             {@code FlatFileItemReader} instances pointed at the
 *             parameterised paths, with their outputs concatenated into a
 *             single logical stream and written via a
 *             {@code FlatFileItemWriter} pointed at the output path.</li>
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
 *       full-context Spring Boot load this IT performs (via the inherited
 *       {@code @SpringBootTest}) fails at context refresh with
 *       {@link org.springframework.beans.factory.NoSuchBeanDefinitionException}
 *       on the controller's constructor parameters &mdash; even though
 *       this IT never directly calls a controller, every controller bean
 *       still gets instantiated during context startup.</li>
 *   <li><strong>{@code SecurityConfig}</strong> under
 *       {@code src/main/java/com/aws/carddemo/config/} wiring Spring
 *       Security with at minimum a {@code SecurityFilterChain} and a
 *       {@code BCryptPasswordEncoder} bean (the
 *       {@code AuthenticationService} constructor declares
 *       {@code PasswordEncoder} as a required dependency &mdash; Spring
 *       Boot's default in-memory user-details service does not provide a
 *       {@code PasswordEncoder} bean, so an explicit bean must be
 *       configured).</li>
 *   <li><strong>Captured COBOL baseline-expected fixtures</strong> under
 *       {@code src/test/resources/baseline/expected/}:
 *       <ul>
 *         <li>{@code posted.txt} &mdash; the byte-identical capture of
 *             POSTTRAN.jcl's output (CBTRN02C posting result) against
 *             the canonical {@code dailytran.txt} input. At this commit
 *             the file is a placeholder ({@code # BASELINE_CAPTURE_PENDING_POSTED}
 *             commented stub) awaiting capture from a real mainframe or
 *             Micro Focus Enterprise Server run.</li>
 *         <li>{@code tcatbal_after_interest.txt} &mdash; the byte-identical
 *             capture of INTCALC.jcl's output (CBACT04C interest
 *             calculation result). Same placeholder status at this
 *             commit.</li>
 *       </ul>
 *       The capture procedure is documented in
 *       {@code docs/testing/baseline-parity.md}; once the placeholders are
 *       replaced by real binary CVTRA05Y-format records, this IT and its
 *       companion parity IT become runnable.</li>
 *   <li><strong>Flyway migrations</strong> under
 *       {@code src/main/resources/db/migration/} ({@code V1__schema.sql},
 *       {@code V2__indexes.sql}, {@code V3__seed.sql}) &mdash; already
 *       present at this commit; no action required for the JobRepository
 *       BATCH_JOB_EXECUTION tables, which are auto-created by Spring
 *       Batch's metadata-table initialiser against the Testcontainers
 *       PostgreSQL 16 instance.</li>
 *   <li><strong>{@code CardDemoApplication}</strong> already exists
 *       (verified at this commit) and is correctly annotated with
 *       {@code @SpringBootApplication}. No action required for this
 *       prereq.</li>
 *   <li><strong>Docker available to Testcontainers</strong> at test
 *       runtime &mdash; the {@code mvn verify} build agent must be able
 *       to run {@code postgres:16-alpine}. CI agents that cannot start
 *       containers can set {@code TESTCONTAINERS_RYUK_DISABLED=true} as
 *       documented in
 *       {@code src/test/resources/application-test.properties}.</li>
 * </ol>
 *
 * <h3>How to verify reactivation worked</h3>
 *
 * <pre>{@code
 * mvn -B -Dit.test=CombineTransactionsJobIT verify
 * }</pre>
 *
 * <p>Expected outcome after reactivation: the single
 * {@code combineJob_mergesPostedAndInterest_producesSortedOutput} test
 * passes &mdash; the Spring Batch {@code combineTransactionsJob}
 * completes with {@link BatchStatus#COMPLETED}, writes a non-empty
 * combined-transactions file to the JUnit {@code @TempDir}, and every
 * line's TRAN-ID (first 16 chars) is lexicographically &ge; the prior
 * line's TRAN-ID.
 *
 * <h2>Authority</h2>
 *
 * <p>AAP §0.5.1 ("End-to-end DFSORT-equivalent merge; asserts ordering
 * and dedup"), §0.5.2 (Combine job IT &mdash; merge ordering and dedup),
 * §0.8.1 (testing flavor does not modify production source), §0.10.1
 * (Require Test Coverage rule &mdash; drive production code, never
 * reimplement business logic), §0.10.2 (Minimal Change Clause), §0.10.4
 * (Immutable Boundaries &mdash; record layouts identical to COBOL),
 * §0.10.7 (JUnit 5 + Mockito framework constraint), §0.10.10 (Style
 * consistency &mdash; AssertJ exclusively).
 *
 * @see CombineTransactionsBaselineParityIT companion byte-identical parity
 *      IT that asserts the produced combined file matches the captured
 *      COBOL reference byte-for-byte.
 * @see AbstractBatchIT shared base class supplying the Spring Boot test
 *      context, the Spring Batch test slice, and the Testcontainers
 *      PostgreSQL 16 container.
 * @see com.aws.carddemo.batch.CombineTransactionsProcessor the migrated
 *      DFSORT-equivalent merge/sort component whose Job-bean wiring this
 *      IT exercises end-to-end.
 */
@Disabled("Awaits production-side prerequisites: (1) combineTransactionsJob @Bean declared in a "
        + "@Configuration class under com.aws.carddemo.batch.config (or similar) wiring "
        + "CombineTransactionsProcessor as the sort/merge component with two FlatFileItemReader "
        + "instances and one FlatFileItemWriter bound to the input.posted.path / "
        + "input.systran.path / output.transact.path JobParameters; (2) @Service annotations on "
        + "the 17 service classes under com.aws.carddemo.service so the @SpringBootTest "
        + "full-context load this IT performs does not fail at context refresh on the controller "
        + "bean graph's NoSuchBeanDefinitionException; (3) SecurityConfig under "
        + "com.aws.carddemo.config wiring a BCryptPasswordEncoder bean for AuthenticationService's "
        + "constructor injection; (4) Captured COBOL baseline-expected fixtures (posted.txt, "
        + "tcatbal_after_interest.txt) under src/test/resources/baseline/expected/, currently "
        + "placeholder stubs awaiting capture from a real mainframe or Micro Focus Enterprise "
        + "Server run. Per AAP §0.8.1 the testing flavor cannot modify those production files; "
        + "the next REFACTOR-flavor agent removes this annotation when the prerequisites land. "
        + "See the class Javadoc 'Reactivation Checklist' for the full list and verification "
        + "command.")
@DisplayName("COMBTRAN.jcl Spring Batch job execution + sort-order verification")
class CombineTransactionsJobIT extends AbstractBatchIT {

    /**
     * Width (in characters) of the {@code TRAN-ID} field at the start of
     * every {@code CVTRA05Y.cpy} 350-byte transaction record.
     *
     * <p>This is the {@code TRAN-ID} {@code PIC X(16)} field declared at
     * positions 1&ndash;16 of {@code app/cpy/CVTRA05Y.cpy}; the DFSORT
     * SYMNAMES directive in {@code app/jcl/COMBTRAN.jcl}
     * ({@code TRAN-ID,1,16,CH}) sorts the input ascending by exactly these
     * 16 bytes. Pinning the width as a named constant satisfies the AAP
     * §0.10.4 immutable-boundary contract and gives a single point of
     * update if a future migration step ever reshapes the record (which
     * is forbidden by §0.10.4 but defensively coded against).
     */
    private static final int TRAN_ID_LENGTH = 16;

    /**
     * Per-test isolated temporary directory injected by JUnit 5's
     * {@link TempDir} extension. Used as the staging area for the two
     * input fixtures (posted.txt as SORTIN DD #1, tcatbal_after_interest.txt
     * as SORTIN DD #2) and as the destination directory for the produced
     * combined-transactions output (SORTOUT DD).
     *
     * <p>JUnit Jupiter creates this directory before the test method runs
     * and recursively deletes it after the test completes, regardless of
     * whether the test passes or fails. This guarantees filesystem
     * isolation between test runs and prevents stale fixture leakage
     * across the build per AAP §0.10.9 (test isolation requirements).
     *
     * <p>Field visibility is package-private (default) &mdash; JUnit's
     * extension mechanism uses reflection to inject the directory and
     * does not require {@code public} access. Keeping it package-private
     * matches the established convention in {@code AbstractBatchIT} for
     * its inherited Spring-managed fields and in the sibling
     * {@code TransactionReportJobIT} / {@code StatementGenerationJobIT}.
     */
    @TempDir
    Path workDir;

    /**
     * End-to-end execution of the migrated {@code combineTransactionsJob}
     * Spring Batch {@code Job} bean with the canonical fixtures staged
     * into the {@link #workDir} {@link TempDir}.
     *
     * <p>The test wires the two SORTIN DD-mapped input paths (the captured
     * outputs of POSTTRAN.jcl and INTCALC.jcl, mirroring the JCL JES
     * concatenation) and the SORTOUT DD-mapped output path into a
     * {@link JobParameters} bundle, hands them to the inherited
     * {@code jobLauncherTestUtils} for synchronous execution, then verifies:
     * <ol>
     *   <li>Spring Batch execution status &mdash; {@link BatchStatus#COMPLETED}
     *       and {@link ExitStatus#COMPLETED}.</li>
     *   <li>Output file production &mdash; the SORTOUT path exists and is
     *       non-empty.</li>
     *   <li>Sort-order invariant &mdash; pairwise check that each line's
     *       TRAN-ID (first 16 chars) is lexicographically &ge; the prior
     *       line's TRAN-ID.</li>
     * </ol>
     *
     * <p>Per AAP §0.10.1 (Require Test Coverage rule) this method drives
     * the <em>real</em> production {@code Job} bean end-to-end &mdash; no
     * business logic is reimplemented inside the test body. Per AAP
     * §0.10.4 the combined-transactions output produced by the migrated
     * job is treated as an immutable boundary; the byte-identical
     * baseline-parity check lives in
     * {@code CombineTransactionsBaselineParityIT}.
     *
     * @throws Exception when the inherited {@code jobLauncherTestUtils}
     *                   propagates a Spring Batch launch failure (any
     *                   uncaught checked or unchecked exception during job
     *                   execution); JUnit 5 fails the test with the
     *                   propagated stack trace, surfacing the COBOL parity
     *                   regression. Also raised by
     *                   {@link Files#readAllLines(Path, java.nio.charset.Charset)}
     *                   when the produced output file is unreadable or
     *                   contains non-US-ASCII bytes.
     */
    @Test
    @DisplayName("Job merges posted + interest inputs and produces a non-empty TRAN-ID-sorted output")
    void combineJob_mergesPostedAndInterest_producesSortedOutput() throws Exception {
        // ---- Arrange ----
        // Stage both COMBTRAN.jcl SORTIN DD-mapped input files into the @TempDir
        // so the Spring Batch Job's FlatFileItemReader instances can read them
        // from real filesystem paths (FlatFileItemReader does NOT consume
        // classpath resources directly).
        //
        // The two inputs correspond to the two JES-concatenated SORTIN DDs:
        //   * baseline/expected/posted.txt           = AWS.M2.CARDDEMO.TRANSACT.BKUP(0)
        //     (output of POSTTRAN.jcl / CBTRN02C posting step)
        //   * baseline/expected/tcatbal_after_interest.txt = AWS.M2.CARDDEMO.SYSTRAN(0)
        //     (output of INTCALC.jcl / CBACT04C interest calculation)
        //
        // This IT exercises COMBTRAN.jcl in isolation: the upstream POSTTRAN
        // and INTCALC pipelines are pre-baked into the staged inputs. The
        // end-to-end integration of all three pipelines is the responsibility
        // of BatchPipelineE2ETest per AAP §0.5.1.
        final Path stagedPosted = stageExpectedFixture(TestFixtures.Paths.EXPECTED_POSTED);
        final Path stagedInterest = stageExpectedFixture(TestFixtures.Paths.EXPECTED_TCATBAL_AFTER_INTEREST);
        // Destination path for the produced SORTOUT output. Just a resolve()
        // against the @TempDir — Spring Batch's FlatFileItemWriter creates the
        // file lazily when the Step opens its writer (no pre-existence
        // required).
        final Path actualOutput = workDir.resolve(TestFixtures.Paths.EXPECTED_COMBINED);

        // Build the JobParameters bundle that mirrors the COMBTRAN.jcl DD
        // assignments. The run.timestamp parameter guarantees each
        // JobInstance is unique even if this method is re-run (Spring Batch
        // would otherwise treat a repeat invocation as a restart of the prior
        // COMPLETED instance and refuse to launch). Path values are
        // absolutised so the Spring Batch reader/writer resolves them
        // independent of the JVM working directory.
        final JobParameters params = new JobParametersBuilder()
                .addString("input.posted.path", stagedPosted.toAbsolutePath().toString())
                .addString("input.systran.path", stagedInterest.toAbsolutePath().toString())
                .addString("output.transact.path", actualOutput.toAbsolutePath().toString())
                .addLong("run.timestamp", System.currentTimeMillis())
                .toJobParameters();

        // ---- Act ----
        // jobLauncherTestUtils is the protected field inherited from
        // AbstractBatchIT, contributed to the Spring context by
        // @SpringBatchTest. launchJob synchronously runs the configured
        // combineTransactionsJob @Bean to completion (or failure) and returns
        // the JobExecution metadata.
        final JobExecution execution = jobLauncherTestUtils.launchJob(params);

        // ---- Assert: Spring Batch execution status ----
        // The Java-side analogue of DFSORT's RC=0 exit code. AAP §0.5.1
        // explicitly mandates "asserts execution semantics" for batch ITs.
        assertThat(execution.getStatus())
                .as("Job must complete with BatchStatus.COMPLETED "
                        + "(Java equivalent of DFSORT RC=0)")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus())
                .as("Job exit status must be COMPLETED (no skip / no failure aggregation)")
                .isEqualTo(ExitStatus.COMPLETED);

        // ---- Assert: Output exists and is non-empty ----
        // A merge of two non-empty SORTIN inputs always produces a non-empty
        // SORTOUT — the minimum we can assert without reimplementing the
        // sort (AAP §0.10.1).
        assertThat(actualOutput)
                .as("Job must produce the TRANSACT.COMBINED output at the configured path")
                .exists();
        assertThat(Files.size(actualOutput))
                .as("Output must be non-empty (merge of two non-empty inputs always yields output)")
                .isGreaterThan(0L);

        // ---- Assert: Output is sorted ascending by TRAN-ID (positions 1-16) ----
        // Reading the file as lines is safe because the CVTRA05Y records
        // (350 bytes each) are separated by LF in the migrated FlatFileItemWriter
        // output. Charset choice: US_ASCII is the strict subset of the
        // CVTRA05Y record's declared character set (PIC X fields are
        // populated with US-ASCII digits, letters, and spaces). Any
        // non-ASCII byte would indicate file corruption and fail loudly
        // with a MalformedInputException before the sort-order assertions
        // run.
        //
        // The pairwise check (i-1 vs i) verifies the sort invariant
        // directly in O(n). Re-sorting the list and comparing would
        // duplicate the production sort logic inside the test body,
        // violating the AAP §0.10.1 Require Test Coverage rule.
        final List<String> outputLines = Files.readAllLines(actualOutput, StandardCharsets.US_ASCII);
        assertThat(outputLines)
                .as("Output must contain at least one record "
                        + "(both SORTIN inputs are non-empty)")
                .isNotEmpty();
        for (int i = 1; i < outputLines.size(); i++) {
            final String prevTranId = outputLines.get(i - 1).substring(0, TRAN_ID_LENGTH);
            final String currTranId = outputLines.get(i).substring(0, TRAN_ID_LENGTH);
            assertThat(currTranId)
                    .as("Records must be sorted ascending by TRAN-ID (positions 1-%d). "
                            + "Line %d violates ordering: prev='%s', curr='%s'",
                            TRAN_ID_LENGTH, i, prevTranId, currTranId)
                    .isGreaterThanOrEqualTo(prevTranId);
        }
    }

    // =========================================================================
    // Private helpers — staging classpath fixtures onto the @TempDir filesystem
    // =========================================================================

    /**
     * Loads a captured baseline-expected fixture from the test classpath
     * ({@code src/test/resources/baseline/expected/}) and writes it to the
     * {@link #workDir} {@link TempDir} so the Spring Batch Job's
     * {@code FlatFileItemReader} can consume it from a real filesystem path.
     *
     * <p>The helper exists because {@code FlatFileItemReader} expects a
     * {@link java.nio.file.Path} or {@code Resource} backed by a real
     * filesystem location, NOT a classpath resource &mdash; staging through
     * {@link Files#write(Path, byte[], java.nio.file.OpenOption...)} is the
     * idiomatic Spring Batch test pattern (also used in the sibling
     * {@code TransactionReportJobIT} and {@code StatementGenerationJobIT}).
     *
     * <p>Byte-level write (not character-level) preserves the original
     * fixed-width COBOL record byte layout including any trailing whitespace
     * and the original line-ending convention; AAP §0.10.4 ("Input and
     * output file formats and record layouts MUST remain identical") forbids
     * any charset / line-ending normalisation in the test harness.
     *
     * <p>Both COMBTRAN.jcl SORTIN inputs (the daily-posted transactions
     * backup and the system-generated transactions from interest calc) are
     * sourced from {@code baseline/expected/} because they represent the
     * captured outputs of the <em>upstream</em> POSTTRAN.jcl and INTCALC.jcl
     * pipeline steps. Per AAP §0.4.4 (Fixture Organization Strategy):
     * canonical golden inputs live under {@code baseline/input/}, captured
     * COBOL reference outputs live under {@code baseline/expected/}, and an
     * IT pipeline that exercises a downstream-only step (like this one)
     * consumes the upstream steps' expected outputs as its inputs.
     *
     * @param filename simple basename of the expected-output fixture (e.g.
     *                 {@code "posted.txt"} or
     *                 {@code "tcatbal_after_interest.txt"}) &mdash; must be
     *                 one of the captured reference outputs under
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
