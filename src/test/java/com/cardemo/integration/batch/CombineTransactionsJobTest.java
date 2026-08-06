/*
 * ******************************************************************
 * Program     : CombineTransactionsJobTest.java
 * Application : CardDemo
 * Type        : JUnit 5 integration test (Spring Boot 3.5.11,
 *               Testcontainers 2.0.3) - Failsafe tier
 * Function    : Executes the combine-transactions job end to end and
 *               asserts the contract of the ONE batch job in the
 *               corpus that has NO COBOL PROGRAM AT ALL: the ordered
 *               concatenation of the two current generations, the
 *               globally ascending TRAN-ID sort, the carry-forward of
 *               the concrete generation key from the sort step to the
 *               load step, the parameterised bulk load into the
 *               posted-transaction relation, and the mandatory
 *               duplicate-identifier FAILURE that must never become a
 *               silent upsert.
 * Source      : app/jcl/COMBTRAN.jcl:L22 (STEP05R EXEC PGM=SORT),
 *               :L24 and :L26 (the concatenated SORTIN, both at
 *               current generation (0)), :L28 (SYMNAMES
 *               TRAN-ID,1,16,CH), :L30 (SORT FIELDS=(TRAN-ID,A)),
 *               :L33-L37 (SORTOUT, one TRANSACT.COMBINED(+1)
 *               generation with DCB=(*.SORTIN)), :L41 (STEP10 EXEC
 *               PGM=IDCAMS), :L43-L44 (TRANSACT re-references the
 *               SAME (+1) generation) and :L48 (REPRO INFILE(TRANSACT)
 *               OUTFILE(TRANVSAM)); app/cpy/CVTRA05Y.cpy (the
 *               350-byte TRAN-RECORD offset map every image obeys);
 *               app/jcl/DEFGDGB.jcl:L49-L51 and :L55-L57 (the SYSTRAN
 *               and TRANSACT.COMBINED generation-data-group bases);
 *               app/ctl/REPROCT.ctl:L15 (the parameterised form of the
 *               same REPRO card) @ 7756d89
 * ******************************************************************
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
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.integration.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cardemo.batch.processors.TransactionCombineProcessor;
import com.cardemo.exception.DataIntegrityException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.repository.TransactionRepository;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Integration test for the combine-transactions job, the only batch job in the migrated stream whose entire
 * behaviour is specified by job control rather than by a program.
 *
 * <h2>What it does</h2>
 *
 * <p><strong>There is no COBOL program for this job, and that is the single most important fact about
 * it.</strong> {@code app/jcl/COMBTRAN.jcl} has two steps and neither invokes a compiled module of the
 * corpus: {@code :L22} is {@code EXEC PGM=SORT} and {@code :L41} is {@code EXEC PGM=IDCAMS}. Every other
 * batch job in this package can be checked against a program's paragraphs; this one can only be checked
 * against the job control, so the assertions below cite the JCL directly and nothing else.
 *
 * <p>The contract this class executes, step by step, is:
 *
 * <ul>
 *   <li><strong>{@code SORTIN} is a concatenation, in card order.</strong> {@code :L23-L26} declares two
 *       {@code DD} statements under one name - {@code AWS.M2.CARDDEMO.TRANSACT.BKUP(0)} at {@code :L24}
 *       then {@code AWS.M2.CARDDEMO.SYSTRAN(0)} at {@code :L26}. <strong>Both are the current generation,
 *       {@code (0)}</strong>, which is precisely why only the latest interest run is ever merged: an
 *       earlier {@code SYSTRAN} generation is never read and its interest transactions never reach the
 *       master.</li>
 *   <li><strong>The sort key is the transaction identifier, ascending.</strong> {@code :L28} declares the
 *       symbol {@code TRAN-ID,1,16,CH} and {@code :L30} sorts on it with {@code SORT FIELDS=(TRAN-ID,A)}.
 *       Offset 1 for 16 characters can only be {@code TRAN-ID PIC X(16)}, the first field of the 350-byte
 *       record of {@code app/cpy/CVTRA05Y.cpy}.</li>
 *   <li><strong>{@code SORTOUT} is exactly one generation.</strong> {@code :L33-L37} allocates a single
 *       {@code TRANSACT.COMBINED(+1)} with {@code DCB=(*.SORTIN)}, so the output inherits the input's
 *       fixed 350-byte geometry.</li>
 *   <li><strong>{@code STEP10} re-references {@code (+1)}, not {@code (0)}.</strong> {@code :L43-L44}
 *       names the generation the sort step created <em>in the same job</em>, and {@code :L48} copies it
 *       with {@code REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)} into the keyed cluster
 *       {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}.</li>
 * </ul>
 *
 * <p>Two translations are asserted as behaviour rather than taken on trust. The external sort becomes an
 * in-process {@code java.util.Comparator}, so <strong>no external process is spawned</strong>; and the
 * {@code REPRO} card - whose parameterised form is {@code app/ctl/REPROCT.ctl:L15} - becomes a fully
 * parameterised batched insert, so <strong>content lifted from a generation object is bound as data and
 * never interpolated into a statement</strong>. Both are exercised below.
 *
 * <p><strong>A duplicate identifier must fail the load.</strong> {@code REPRO} into a keyed cluster is
 * rejected by a duplicate key, so the migrated load must be rejected too. The exposure is real rather than
 * theoretical: the interest calculator builds identifiers at {@code app/cbl/CBACT04C.cbl:L476-L479} by
 * concatenating the ten-character date parameter of {@code app/jcl/INTCALC.jcl:L22} with the six-digit
 * suffix of {@code app/cbl/CBACT04C.cbl:L173}, a counter that is never reset per account, and it writes to
 * a fresh sequential generation ({@code app/cbl/CBACT04C.cbl:L309},
 * {@code app/jcl/INTCALC.jcl:L37-L41}) so it performs no duplicate detection of its own. A second interest
 * run with the same date parameter therefore produces colliding identifiers whose collision surfaces
 * <em>here</em>, at this job's load step, for the first time. The required outcome is a failure carrying
 * {@code com.cardemo.exception.DuplicateRecordException} - never an upsert, never a merge, never a retry
 * and never a substituted sequence, each of which would change generated identifier values and break the
 * boundary comparison the migration is accepted against.
 *
 * <h2>What it deliberately does not assert</h2>
 *
 * <ul>
 *   <li><strong>No gating between the two steps.</strong> {@code app/jcl/COMBTRAN.jcl} carries no
 *       {@code COND} parameter on either {@code EXEC}; the corpus's {@code COND=(0,NE)} occurrences are in
 *       {@code app/jcl/CREASTMT.JCL}. Asserting a gate here would assert invented control flow, so what is
 *       asserted instead is that the two steps run in source order with nothing between them.</li>
 *   <li><strong>No return-code locator is claimed for 8 or 12.</strong> With no program there is no
 *       {@code RETURN-CODE} statement to cite; the corpus's one numeric-literal assignment is
 *       {@code MOVE 4 TO RETURN-CODE} at {@code app/cbl/CBTRN02C.cbl:L230}, which belongs to the posting
 *       job's reject counter and has nothing to do with this member. A duplicate identifier is therefore
 *       asserted as a failed exit status and not as a fabricated citation.</li>
 *   <li><strong>No byte-level parity baseline.</strong> The repository holds dataset <em>definition</em>
 *       job control and zero captured output: an exhaustive search for expected, baseline, golden,
 *       system-output, reject, report, statement and HTML captures returned only definitions.
 *       <strong>Not available</strong>. What would be needed is a combined generation captured from a real
 *       run at a known input state. No baseline file is created here, no expected bytes are invented, and a
 *       baseline produced by running this implementation would be circular and is forbidden.</li>
 *   <li><strong>No test for file status {@code '35'}.</strong> Neither that literal nor the
 *       file-unavailable response code occurs anywhere in {@code app/cbl}, so the absent-generation path is
 *       <strong>Not available</strong> as a parity claim. The boundary conditions exercised below are
 *       <em>empty</em> generations, which are real states, not absent ones.</li>
 *   <li><strong>No re-testing of the processor.</strong> The per-record validation of
 *       {@code com.cardemo.batch.processors.TransactionCombineProcessor} is owned by the sibling unit tier;
 *       only its two published geometry constants are consumed here, so the record width and identifier
 *       width cannot drift between the two tiers.</li>
 * </ul>
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Build and run everything with the pinned wrapper: {@code ./mvnw clean verify}.
 *
 * <p><strong>Failsafe collects this class, Surefire does not.</strong> Failsafe is bound to
 * {@code src/test/java/com/cardemo/integration/**} and {@code .../e2e/**} and runs them at
 * {@code integration-test} and {@code verify}, even though the classes keep the {@code Test} suffix;
 * Surefire is bound to {@code .../unit/**} and excludes both trees. A class moved out of
 * {@code integration/**} matches neither include set and is collected by <em>neither</em> plugin - the
 * build stays green and the assertions silently never run. Do not rename or relocate this class.
 *
 * <p><strong>A reachable container runtime is a prerequisite.</strong> This tier starts a real PostgreSQL
 * container and a real object-store and queue emulator; there is no in-memory substitute, because an
 * in-memory database would not validate the migrated schema and an in-memory object store would not
 * exercise generation keys. Where no daemon or socket is available the correct report is that the gate is
 * blocked together with the prerequisite, never an untested pass.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li>Profile {@code test} is active, inherited from the harness. No address, port, URL or credential
 *       appears in this file: the datasource is bound from injected connection details and every
 *       object-store value is read from a property or from a container accessor.</li>
 *   <li><strong>{@code carddemo.batch.combined-transaction-reader.source} is set to
 *       {@code object-storage} for this class, and the choice is load-bearing.</strong> The reader's
 *       default is {@code repository}, where the first source is the posted-transaction relation rather
 *       than a generation object. That default is right for the pipeline but wrong for testing
 *       <em>this</em> member, for two reasons. First, {@code :L24} names a dataset, so a generation object
 *       is the faithful analogue of the first {@code DD} card and the only substrate on which "the
 *       concatenation is honoured" is a statement about the JCL rather than about a relation. Second, on
 *       the repository path the first source <em>is</em> the load target, so every record read from it
 *       would collide on the primary key by construction and a successful two-source load could not be
 *       observed at all. Selecting the property puts this class in its own application context; the
 *       harness's containers are static and shared, so only the context is additional.</li>
 *   <li>{@code carddemo.aws.s3.batch-output-bucket} holds all three generation prefixes, and it is the one
 *       versioned bucket. The prefixes are {@code carddemo.aws.s3.gdg-prefixes.transact-bkup},
 *       {@code .systran} and {@code .transact-combined}; each is bound below with the same default the
 *       production component binds, so a test and a running application cannot disagree about which prefix
 *       holds which dataset.</li>
 *   <li>{@code carddemo.batch.combtran.chunk-size} sizes the batched insert, defaulting to 100 through
 *       {@code carddemo.batch.chunk-size}. Every case below fits in one batch except the full-fixture
 *       cases, which deliberately span four.</li>
 *   <li>{@code spring.batch.job.enabled} is {@code false}, so nothing runs at startup and every launch
 *       here is explicit. The three Flyway migrations own the schema, the three non-unique alternate
 *       indexes and the seed data; this class adds no migration and asserts nothing about them beyond the
 *       one fact it depends on - that the posted-transaction relation is seeded <strong>empty</strong>.</li>
 *   <li>Time comes only from the harness's injected clock, pinned to {@code 2022-06-10T19:27:53Z} in UTC.
 *       Nothing here reads a wall clock, and the one timestamp assertion renders that instant in the
 *       legacy 26-character form so that the expected value cannot drift from the pinned one.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>Every test in the tier is skipped, or container startup fails</dt>
 *   <dd>No container runtime is reachable. Start the daemon and re-run; report the gate as blocked rather
 *       than as a pass if it cannot be started.</dd>
 *
 *   <dt>{@code Could not resolve dependencies ... org.testcontainers:localstack:2.0.3}</dt>
 *   <dd>The 2.x line renamed every module artefact and the bare {@code postgresql}, {@code localstack} and
 *       {@code junit-jupiter} ids do not exist at that version. <strong>Blocker.</strong> The remedy has
 *       two halves and both are required: pin the managed version by overriding the version property -
 *       never by importing a second bill of materials, since one is already imported at a 1.x version and
 *       a competing import resolves in whichever order it likes - and use only the four prefixed
 *       coordinates. Both halves are already in place in the build file; neither is to be edited from
 *       here.</dd>
 *
 *   <dt>{@code warnings found and -Werror specified}</dt>
 *   <dd>Compilation escalates every warning to an error, so one raw type, one unchecked cast or one
 *       deprecated call fails the whole build. An unused import is not among the warnings {@code javac}
 *       emits, so that prohibition is review-enforced: every import in this file is used.</dd>
 *
 *   <dt>{@code Existing transaction detected in JobRepository}</dt>
 *   <dd>A launching method lost its {@code @Transactional(propagation = Propagation.NOT_SUPPORTED)}. Every
 *       method here that launches carries it, because the batch framework refuses job repository work
 *       inside an existing transaction and commits each chunk on its own transaction regardless.</dd>
 *
 *   <dt>The load step reports a record the sort step did not write</dt>
 *   <dd>The generation was re-resolved mid-job. {@code :L44} is {@code (+1)}, so the object to load is the
 *       one this job's sort step created; listing the prefix for its greatest key instead would load
 *       whatever a concurrent writer had left there. One case below plants exactly such an object, with a
 *       key that sorts above every real one, and proves it is not the object loaded.</dd>
 *
 *   <dt>A duplicate identifier is absorbed instead of failing</dt>
 *   <dd><strong>Blocker.</strong> The load has acquired an on-conflict clause, a merge, a retry or a
 *       generated identity. Remove it: the failure is the intended signal that the interest job ran twice
 *       with the same date parameter, and relaxing the load hides the defect it exists to expose.</dd>
 *
 *   <dt>A money assertion fails although the number looks right</dt>
 *   <dd>An equality matcher was used on a {@code java.math.BigDecimal}. A {@code NUMERIC(n,2)} column
 *       always reads back at scale 2 and {@code new BigDecimal("194.00").equals(new BigDecimal("194.0"))}
 *       is {@code false} while their {@code compareTo} is {@code 0}. Every money assertion here compares
 *       by value.</dd>
 * </dl>
 *
 * <h2>Thread safety and state</h2>
 *
 * <p>Not thread safe and not required to be: one instance per test method, no shared mutable state, no
 * second thread and no second connection. <strong>This class declares no {@code static} field of any
 * kind</strong> - the harness permits exactly two in this package and both are its containers - so every
 * constant below is an immutable instance field initialised at its declaration.
 */
@DisplayName("Combine transactions job: the concatenated SORTIN, the TRAN-ID sort, the (+1) carry-forward "
        + "and the duplicate-identifier failure of app/jcl/COMBTRAN.jcl - a job with no COBOL program")
@TestPropertySource(properties = "carddemo.batch.combined-transaction-reader.source=object-storage")
class CombineTransactionsJobTest extends AbstractBatchIntegrationTest {

    /** The assembled job under test, injected by the bean name its configuration registers. */
    @Autowired
    @Qualifier("combineTransactionsJob")
    private Job combineTransactionsJob;

    /**
     * Counts committed rows and probes identifier presence, through the production repository surface.
     *
     * <p>Only {@code count()} and {@code existsById(String)} are used, both of which answer with a
     * primitive and therefore need no entity type named here. Column-level reads go through the template
     * below instead, because the relation's name is a reserved word and the quoting is part of what is
     * being checked.
     */
    @Autowired
    private TransactionRepository transactionRepository;

    /**
     * Reads committed columns of the posted-transaction relation and the referential state around it.
     *
     * <p><strong>Reads only.</strong> The connection pool runs with auto-commit switched off, so a write
     * issued through this object outside a transaction reports its affected-row count and is then rolled
     * back when the connection returns to the pool - silently, because the count is returned before the
     * rollback happens. Nothing in this class writes to the database at all: the job is the only writer,
     * which is the whole point.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Writes the two synthetic input generations and reads the combined generation back byte for byte. */
    @Autowired
    private S3Client s3Client;

    /** The one versioned generation bucket, bound from the same key every production component binds. */
    @Value("${carddemo.aws.s3.batch-output-bucket}")
    private String batchOutputBucket;

    /** The {@code TRANSACT.BKUP} prefix of {@code app/jcl/COMBTRAN.jcl:L24}, with the reader's default. */
    @Value("${carddemo.aws.s3.gdg-prefixes.transact-bkup:gdg/transact-bkup}")
    private String backupPrefix;

    /** The {@code SYSTRAN} prefix of {@code app/jcl/COMBTRAN.jcl:L26}, with the reader's default. */
    @Value("${carddemo.aws.s3.gdg-prefixes.systran:gdg/systran}")
    private String systranPrefix;

    /** The {@code TRANSACT.COMBINED} prefix of {@code app/jcl/COMBTRAN.jcl:L37}, with the job's default. */
    @Value("${carddemo.aws.s3.gdg-prefixes.transact-combined:gdg/transact-combined}")
    private String combinedPrefix;

    // =================================================================================================
    // Contract values. Each is an immutable instance field rather than a static constant, because the
    // harness documents a hard limit of two static fields in this package and both of them are containers.
    // =================================================================================================

    /**
     * Job execution-context entry carrying the concrete key the sort step created.
     *
     * <p>This is the {@code (+1)} handoff of {@code app/jcl/COMBTRAN.jcl:L44}. The entry name is restated
     * here as a literal rather than referenced, because the job declares it package-private in
     * {@code com.cardemo.batch.jobs} and this class sits in a different package. The two are kept in step
     * by the first assertion in {@link #stepsRunInSourceOrderWithNoGateBetweenThem()}, which fails loudly
     * if the entry is absent instead of quietly reading {@code null}.
     */
    private final String publishedKeyContextEntry = "carddemo.transact.combined.object.key";

    /** Job execution-context entry carrying the record count the sort step wrote, alongside the key. */
    private final String publishedCountContextEntry = "carddemo.transact.combined.record.count";

    /** Bean name of {@code STEP05R} - {@code app/jcl/COMBTRAN.jcl:L22}. */
    private final String sortStepName = "combineTransactionsSortStep";

    /** Bean name of {@code STEP10} - {@code app/jcl/COMBTRAN.jcl:L41}. */
    private final String loadStepName = "combineTransactionsLoadStep";

    /**
     * The frozen fixture supplying every input record image, consumed by classpath resource name.
     *
     * <p>There is no posted-transaction fixture in {@code app/data/ASCII} and none may be created, so the
     * input images come from the staged daily transaction dataset, which carries the identical 350-byte
     * geometry. Three measured properties of that file are what make it usable verbatim: its 300
     * identifiers are already ascending, so the ordered-merge precondition holds without editing a byte;
     * its type and category codes are only {@code ('01', 0001)} and {@code ('03', 0001)}, both of which the
     * seed data declares, so every foreign key is satisfied; and its card numbers are drawn from the fifty
     * seeded cards. Nothing here trims, pads, re-encodes or rewrites a record - trailing spaces are payload
     * in a fixed-width image, and the name is spelled in full because a name one letter short resolves to
     * no resource at all.
     */
    private final String dailyTransactionFixture = "dailytran.txt";

    /**
     * The record width, taken from the processor rather than restated.
     *
     * <p>{@code app/cpy/CVTRA05Y.cpy:L2} declares {@code RECLN = 350} and
     * {@code app/jcl/COMBTRAN.jcl:L35} carries it onto {@code SORTOUT} through {@code DCB=(*.SORTIN)}.
     * Borrowing the constant means the two tiers cannot drift.
     */
    private final int recordLength = TransactionCombineProcessor.COMBINED_RECORD_LENGTH;

    /** The identifier width of the sort symbol {@code TRAN-ID,1,16,CH} at {@code app/jcl/COMBTRAN.jcl:L28}. */
    private final int identifierLength = TransactionCombineProcessor.TRAN_ID_LENGTH;

    /**
     * The ten-character date parameter of {@code app/jcl/INTCALC.jcl:L22}, {@code PARM='2022071800'}.
     *
     * <p>Eight date digits then two zeros, and character data throughout - never a temporal type. It leads
     * every interest identifier ({@code app/cbl/CBACT04C.cbl:L476-L479}), which is why an interest
     * identifier sorts above every identifier the fixture carries and why the concatenation and the sort
     * agree with each other on real data.
     */
    private final String interestDateParameter = "2022071800";

    /** Positive zoned-decimal overpunch alphabet: index is the final digit, so {@code '{'} is {@code +0}. */
    private final String positiveOverpunch = "{ABCDEFGHI";

    /** Negative zoned-decimal overpunch alphabet: {@code '}'} is {@code -0} and {@code 'R'} is {@code -9}. */
    private final String negativeOverpunch = "}JKLMNOPQR";

    /** Scale of {@code TRAN-AMT PIC S9(09)V99}, matching the {@code NUMERIC(11,2)} column. */
    private final int amountScale = 2;

    /** One-based start of {@code TRAN-DESC PIC X(100)} - {@code app/cpy/CVTRA05Y.cpy:L9}. */
    private final int descriptionStart = 33;

    /** Width of {@code TRAN-DESC}. */
    private final int descriptionLength = 100;

    /** One-based start of {@code TRAN-AMT PIC S9(09)V99} - {@code app/cpy/CVTRA05Y.cpy:L10}. */
    private final int amountStart = 133;

    /** Width of the zoned-decimal {@code TRAN-AMT} field, sign overpunch included. */
    private final int amountLength = 11;

    /** One-based start of {@code TRAN-MERCHANT-NAME PIC X(50)} - {@code app/cpy/CVTRA05Y.cpy:L12}. */
    private final int merchantNameStart = 153;

    /** Width of {@code TRAN-MERCHANT-NAME}. */
    private final int merchantNameLength = 50;

    /**
     * One-based start of {@code TRAN-CARD-NUM PIC X(16)} - {@code app/cpy/CVTRA05Y.cpy:L15}.
     *
     * <p>Independently corroborated by {@code app/proc/TRANREPT.prc:L39}, which declares the sort symbol
     * {@code TRAN-CARD-NUM,263,16,ZD} at the same offset.
     */
    private final int cardNumberStart = 263;

    /** One-based start of {@code TRAN-ORIG-TS PIC X(26)} - {@code app/cpy/CVTRA05Y.cpy:L16}. */
    private final int originatingTimestampStart = 279;

    /**
     * Width of both timestamp fields, which are character data and are never parsed.
     *
     * <p>Corroborated for the processing timestamp by {@code app/proc/TRANREPT.prc:L40}, whose symbol
     * {@code TRAN-PROC-DT,305,10,CH} takes the first ten characters of the field beginning at 305.
     */
    private final int timestampLength = 26;

    /**
     * Renderer for the legacy 26-character timestamp: hundredths of a second, then four literal zeros.
     *
     * <p>Nineteen characters of date and time, a point, two fractional digits and four zeros is 26. The
     * four zeros are literal rather than significant, which is why the value must not be produced at
     * nanosecond precision. {@code Locale#ROOT} is explicit so the digits cannot be localised and the zone
     * is stated so a differently configured host renders the same string.
     */
    private final DateTimeFormatter legacyTimestampFormat =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SS", Locale.ROOT).withZone(ZoneOffset.UTC);

    /** The four literal zeros the legacy generator always appends. */
    private final String legacyTimestampTrailer = "0000";

    /** Content type of a fixed-width generation: binary, because trailing padding is data. */
    private final String generationContentType = "application/octet-stream";

    /** Object name under the {@code TRANSACT.BKUP} generation prefix. */
    private final String backupObjectName = "TRANSACT.BKUP";

    /** Object name under the {@code SYSTRAN} generation prefix. */
    private final String systranObjectName = "SYSTRAN";

    /**
     * Generation segment of the one current generation each input carries in a test.
     *
     * <p>The harness empties all three buckets before and after every test method, so exactly one object
     * exists under each input prefix while a test runs and the "lexicographically greatest key" rule that
     * resolves a {@code (0)} reference has an unambiguous answer.
     */
    private final String currentGenerationSegment = "0000000000000000001";

    /**
     * Nineteen nines: a generation segment that sorts above every real one.
     *
     * <p>Used only to plant a decoy under the combined prefix. A real key carries a zero-padded job
     * instance identifier in this position, so nothing the job produces can sort above this.
     */
    private final String greatestGenerationSegment = "9999999999999999999";

    /** Identifier carried by the decoy record, chosen so its presence in the relation is unmistakable. */
    private final String decoyIdentifier = "9999999999999999";

    /** Ordered identifiers of the posted-transaction relation. Constant text, no parameter, read-only. */
    private final String orderedIdentifierQuery =
            "select tran_id from \"transaction\" order by tran_id";

    /** One row of the posted-transaction relation by identifier. Parameterised, never interpolated. */
    private final String rowByIdentifierQuery =
            "select tran_type_cd, tran_cat_cd, tran_source, tran_desc, tran_amt, tran_merchant_id,"
                    + " tran_merchant_name, tran_merchant_city, tran_merchant_zip, tran_card_num,"
                    + " tran_orig_ts, tran_proc_ts, version from \"transaction\" where tran_id = ?";

    /** Loaded rows whose card number has no card: the {@code fk04_transaction_card} obligation. */
    private final String orphanCardQuery =
            "select count(*) from \"transaction\" t where not exists"
                    + " (select 1 from card c where c.card_num = t.tran_card_num)";

    /** Loaded rows whose type code has no type: the {@code fk05_transaction_type} obligation. */
    private final String orphanTypeQuery =
            "select count(*) from \"transaction\" t where not exists"
                    + " (select 1 from transaction_type y where y.tran_type = t.tran_type_cd)";

    /** Loaded rows whose type and category pair has no category: {@code fk06_transaction_category}. */
    private final String orphanCategoryQuery =
            "select count(*) from \"transaction\" t where not exists (select 1 from transaction_category g"
                    + " where g.tran_type_cd = t.tran_type_cd and g.tran_cat_cd = t.tran_cat_cd)";

    /** Rows of the three relations the posted-transaction foreign keys point at, in one constant query. */
    private final String referenceCensusQuery =
            "select (select count(*) from card) as cards,"
                    + " (select count(*) from transaction_type) as types,"
                    + " (select count(*) from transaction_category) as categories";

    /** Cards the seed migration loads from {@code app/data/ASCII/carddata.txt}. */
    private final long seededCardCount = 50L;

    /** Transaction types the seed migration loads from {@code app/data/ASCII/trantype.txt}. */
    private final long seededTypeCount = 7L;

    /** Transaction categories the seed migration loads from {@code app/data/ASCII/trancatg.txt}. */
    private final long seededCategoryCount = 18L;

    /**
     * Statement and lookup metacharacters, plus the two characters a Java object stream begins with.
     *
     * <p>This is <strong>data</strong>. It is placed in a description field of a planted generation record
     * and asserted to arrive in the relation unchanged; nothing here is ever executed, evaluated,
     * deserialized or concatenated into statement text, and the value's arriving intact is precisely what
     * demonstrates that. The final two characters are the object-stream header expressed as their
     * single-byte code points, so the uploaded object genuinely carries those two bytes.
     */
    private final String statementMetacharacters =
            "'); DROP TABLE \"transaction\"; -- ${jndi:ldap://nowhere} \u00AC\u00ED";

    /**
     * Shell metacharacters, likewise data.
     *
     * <p>Placed in a merchant-name field of the same planted record. Nothing in the migrated path passes a
     * record field to a command interpreter, and this value's arriving unchanged is the observation that
     * says so.
     */
    private final String shellMetacharacters = "; id && $(id) | `id` > /nowhere";

    // =================================================================================================
    // STEP TOPOLOGY - app/jcl/COMBTRAN.jcl:L22 and :L41, and the absence of COND between them
    // =================================================================================================

    /**
     * The member's two steps run in source order with nothing between them, and publish the handoff.
     *
     * <p>{@code app/jcl/COMBTRAN.jcl} declares exactly two steps - {@code //STEP05R  EXEC PGM=SORT} at
     * {@code :L22} and {@code //STEP10 EXEC PGM=IDCAMS} at {@code :L41} - and <strong>neither carries a
     * {@code COND} parameter</strong>. Absent {@code COND} a step runs regardless of what preceded it, so
     * the correct translation has no gate between the two and this test asserts the absence rather than
     * inventing a gate to assert. What it checks is therefore deliberately narrow: two step executions, in
     * source order, both completed, and the {@code (+1)} handoff present on the job execution context.
     *
     * <p>The handoff check is here rather than only in the case that needs it, because every later
     * assertion in this class reads the published key. If the entry name ever changes on the production
     * side, this test says so directly instead of the whole class failing with a null key.
     */
    @Test
    @DisplayName("1. app/jcl/COMBTRAN.jcl:L22 then :L41 - exactly two steps, in source order, with no COND "
            + "gate between them, and the (+1) generation handoff published for :L44")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void stepsRunInSourceOrderWithNoGateBetweenThem() {
        final List<String> backup = seedBackupGeneration(firstFixtureRecords(3));
        seedSystranGeneration(interestRecords(backup, 1));

        final JobExecution execution = launchCombine();
        assertCombineCompleted(execution);

        final List<StepExecution> steps = new ArrayList<>(execution.getStepExecutions());
        steps.sort(Comparator.comparing(StepExecution::getId));
        final List<String> names = new ArrayList<>(steps.size());
        for (final StepExecution step : steps) {
            names.add(step.getStepName());
            assertThat(step.getStatus())
                    .as("step %s must complete; app/jcl/COMBTRAN.jcl carries no COND, so neither step is "
                            + "conditional and neither may be skipped", step.getStepName())
                    .isEqualTo(BatchStatus.COMPLETED);
        }

        assertThat(names)
                .as("app/jcl/COMBTRAN.jcl declares two EXEC statements and no more: PGM=SORT at :L22 then "
                        + "PGM=IDCAMS at :L41. A third step execution would be invented work, and a "
                        + "different order would invert the (+1) handoff of :L43-L44")
                .containsExactly(sortStepName, loadStepName);

        assertThat(execution.getExecutionContext().containsKey(publishedKeyContextEntry))
                .as("the sort step must publish the concrete key it created under '%s'. :L44 re-references "
                        + "TRANSACT.COMBINED(+1) - the generation created earlier in the same job - so the "
                        + "load step has to be handed that key rather than resolving one of its own",
                        publishedKeyContextEntry)
                .isTrue();
        assertThat(execution.getExecutionContext().containsKey(publishedCountContextEntry))
                .as("the record count travels with the key under '%s', so the load step can prove the "
                        + "object it downloaded holds exactly what the sort step wrote",
                        publishedCountContextEntry)
                .isTrue();
    }

    // =================================================================================================
    // CONCATENATION AND SORT - app/jcl/COMBTRAN.jcl:L23-L26, :L28 and :L30
    // =================================================================================================

    /**
     * Every {@code TRANSACT.BKUP(0)} record is presented before every {@code SYSTRAN(0)} record.
     *
     * <p>{@code SORTIN} at {@code app/jcl/COMBTRAN.jcl:L23-L26} is a concatenation of two {@code DD}
     * statements in card order: the transaction backup at {@code :L24}, then the interest-generated
     * transactions at {@code :L26}. This test proves the order is honoured on the real corpus data rather
     * than on a contrived pair, by driving all 300 records of the frozen daily transaction fixture through
     * the first source and three interest-shaped records through the second, and then reading the emitted
     * generation back.
     *
     * <p><strong>The two properties coincide here for a reason worth stating, not by luck.</strong> An
     * interest identifier is the ten-character date parameter followed by a six-digit suffix
     * ({@code app/cbl/CBACT04C.cbl:L476-L479}), so it begins with a {@code 2}, while every identifier in
     * the fixture begins with a {@code 0}. Card order and ascending {@code TRAN-ID} order therefore agree
     * on this input, which is what makes "all of the first source, then all of the second" a checkable
     * statement about a stream that is also sorted.
     *
     * <p>The record images are compared byte for byte as well as by identifier, because
     * {@code DCB=(*.SORTIN)} at {@code :L35} makes the output inherit the input's geometry: the sort orders
     * records, it does not reshape them, and a 350-byte image that came out different would break the
     * boundary comparison the migration is measured on.
     */
    @Test
    @DisplayName("2. app/jcl/COMBTRAN.jcl:L24 then :L26 - the concatenated SORTIN presents all 300 "
            + "TRANSACT.BKUP(0) records before every SYSTRAN(0) record, each byte for byte")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concatenatedSortinPresentsEveryBackupRecordBeforeEverySystranRecord() {
        final List<String> backup = seedBackupGeneration(fixtureRecords());
        final List<String> systran = seedSystranGeneration(interestRecords(backup, 3));

        final JobExecution execution = launchCombine();
        assertCombineCompleted(execution);

        final List<String> combined = combinedRecords(execution);
        assertThat(combined)
                .as("the single SORTOUT generation of :L33-L37 must hold every record of both concatenated "
                        + "sources: %d from TRANSACT.BKUP(0) and %d from SYSTRAN(0)",
                        Integer.valueOf(backup.size()), Integer.valueOf(systran.size()))
                .hasSize(backup.size() + systran.size());

        assertRecordsIdentical(combined.subList(0, backup.size()), backup, "TRANSACT.BKUP(0)");
        assertRecordsIdentical(
                combined.subList(backup.size(), combined.size()), systran, "SYSTRAN(0)");
    }

    /**
     * The emitted generation is ascending by {@code TRAN-ID} across both sources, not merely within each.
     *
     * <p>{@code app/jcl/COMBTRAN.jcl:L28} declares the symbol {@code TRAN-ID,1,16,CH} and {@code :L30}
     * sorts on it ascending. The symbol's {@code CH} is a character comparison, and the identifiers are
     * zero-padded fixed-width digits, so lexical order and numeric order coincide - <em>provided</em> the
     * padding is respected.
     *
     * <p>That proviso is what the second half of this test guards. The two lowest identifiers in the
     * fixture are ordered one way as sixteen zero-padded characters and the <em>other</em> way once their
     * leading zeros are removed, so an implementation that trimmed or numerically reinterpreted the field
     * would place them in the wrong order. The guard first asserts that this really is such a pair - so it
     * cannot pass vacuously if the fixture ever changes - and then asserts the emitted order is the
     * zero-padded one. A pair of adjacent interest identifiers ending {@code 000009} and {@code 000010} is
     * checked for the same reason at the other end of the stream.
     */
    @Test
    @DisplayName("3. app/jcl/COMBTRAN.jcl:L28 and :L30 - SORT FIELDS=(TRAN-ID,A) leaves the combined "
            + "generation globally ascending, with the zero-padded order preserved")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void combinedGenerationIsGloballyAscendingByTransactionIdAcrossBothSources() {
        final List<String> backup = seedBackupGeneration(fixtureRecords());
        seedSystranGeneration(interestRecords(backup, 3));

        final JobExecution execution = launchCombine();
        assertCombineCompleted(execution);

        final List<String> identifiers = identifiersOf(combinedRecords(execution));
        for (int index = 1; index < identifiers.size(); index++) {
            final String previous = identifiers.get(index - 1);
            final String current = identifiers.get(index);
            assertThat(current.compareTo(previous) >= 0)
                    .as("record %d of the combined generation carries an identifier below its predecessor, "
                            + "so SORT FIELDS=(TRAN-ID,A) at app/jcl/COMBTRAN.jcl:L30 was not honoured "
                            + "across the concatenation. Equal identifiers are permitted and are resolved "
                            + "at the load, but a descent is not", Integer.valueOf(index))
                    .isTrue();
        }

        final String lowest = identifiersOf(backup).get(0);
        final String nextLowest = identifiersOf(backup).get(1);
        assertThat(stripLeadingZeros(lowest).compareTo(stripLeadingZeros(nextLowest)) > 0)
                .as("this guard needs a pair whose order flips when the padding is discarded, or it proves "
                        + "nothing. The two lowest fixture identifiers were such a pair when measured; if "
                        + "the fixture has changed, pick another pair rather than deleting the guard")
                .isTrue();
        assertThat(identifiers.indexOf(lowest))
                .as("the two lowest fixture identifiers must appear in zero-padded character order. Coming "
                        + "out the other way round means the sixteen-character field of :L28 was trimmed or "
                        + "reinterpreted as a number somewhere on the path")
                .isLessThan(identifiers.indexOf(nextLowest));

        final String ninth = interestIdentifier("000009");
        final String tenth = interestIdentifier("000010");
        assertThat(identifiers.indexOf(ninth))
                .as("adjacent interest identifiers %s and %s must stay in that order; the suffix at "
                        + "app/cbl/CBACT04C.cbl:L173 is a zero-padded six-digit counter, so the ninth "
                        + "precedes the tenth in both character and numeric order", ninth, tenth)
                .isLessThan(identifiers.indexOf(tenth));
    }

    // =================================================================================================
    // THE BULK LOAD - app/jcl/COMBTRAN.jcl:L41 to :L48, REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)
    // =================================================================================================

    /**
     * The load copies the whole combined generation into the relation, and every row satisfies every key.
     *
     * <p>{@code app/jcl/COMBTRAN.jcl:L48} is {@code REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)}, the
     * unconditional copy of a sequential generation into the keyed cluster
     * {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS} named at {@code :L45-L46}. The migrated relation stands in
     * for that cluster, and the seed migration leaves it <strong>empty</strong> - there is no posted
     * transaction fixture in {@code app/data/ASCII} and none is invented - so the starting state is clean
     * by construction and is asserted rather than assumed.
     *
     * <p>The relation carries four of the schema's ten foreign keys, so an insert that reached it at all
     * has already satisfied them; the three residual scans below are nevertheless issued, because a
     * constraint that had been dropped would make the load pass while the parity claim quietly became
     * false. They count orphans and never select an identifying value, so no card number reaches an
     * assertion message.
     */
    @Test
    @DisplayName("4. app/jcl/COMBTRAN.jcl:L48 - REPRO copies the whole combined generation into the "
            + "relation the seed leaves empty, and every loaded row satisfies all four of its keys")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void loadStepInsertsExactlyTheCombinedGenerationIntoTheEmptyRelation() {
        assertThat(transactionRepository.count())
                .as("V3__seed_data.sql seeds the posted-transaction relation with zero rows on purpose, "
                        + "because no fixture in app/data/ASCII is a posted transaction file. If rows are "
                        + "present here, an earlier test committed them and the harness reset did not run")
                .isZero();

        final List<String> backup = seedBackupGeneration(fixtureRecords());
        final List<String> systran = seedSystranGeneration(interestRecords(backup, 3));

        final JobExecution execution = launchCombine();
        assertCombineCompleted(execution);

        final List<String> expected = identifiersOf(combinedRecords(execution));
        assertThat(transactionRepository.count())
                .as("REPRO copies every record of the generation and drops none")
                .isEqualTo(backup.size() + systran.size());
        assertThat(jdbcTemplate.queryForList(orderedIdentifierQuery, String.class))
                .as("the loaded identifiers, read back in key order, must be exactly the identifiers the "
                        + "generation held. The relation name is quoted because 'transaction' is a reserved "
                        + "word, which is how V1__create_schema.sql declares it")
                .isEqualTo(expected);

        assertEveryForeignKeySatisfied();
    }

    /**
     * Every field of the 350-byte offset map survives the generation, the load, and the read back.
     *
     * <p>The offset map of {@code app/cpy/CVTRA05Y.cpy} is what makes a fixed-width image addressable, and
     * a single wrong offset shifts every field after it without changing any length. This test therefore
     * takes one record whose bytes are known, follows it through the object boundary into the relation, and
     * compares field by field.
     *
     * <p>Three of the comparisons are the ones that break silently if they are written the obvious way.
     * The amount is decoded here from the field's own zoned-decimal representation, with the trailing byte
     * read as a <strong>sign overpunch</strong> rather than a digit, and compared <strong>by value</strong>
     * - a {@code NUMERIC(11,2)} column always reads back at scale 2, so an equality matcher on a
     * {@code java.math.BigDecimal} would reject an arithmetically correct value. A record carrying a
     * genuinely negative amount is followed through as well, because no absolute value may be taken
     * anywhere on this path. And the originating timestamp is compared against the harness's pinned instant
     * rendered in the legacy 26-character form - nineteen characters of date and time, a point, two
     * fractional digits and <strong>four literal zeros</strong> - which is both the field contract and the
     * proof that the value travelled as text and was never parsed into a temporal type.
     */
    @Test
    @DisplayName("5. app/cpy/CVTRA05Y.cpy - every field of the 350-byte offset map survives the generation "
            + "and the load, including the sign overpunch, a negative amount and the 26-character text "
            + "timestamps")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void everyFieldOfTheOffsetMapSurvivesTheRoundTripIntoTheRelation() {
        final List<String> backup = seedBackupGeneration(firstFixtureRecords(3));
        final List<String> systran = seedSystranGeneration(interestRecords(backup, 1));
        final String probe = systran.get(0);
        final String probeIdentifier = identifierOf(probe);

        final JobExecution execution = launchCombine();
        assertCombineCompleted(execution);

        final Map<String, Object> row = jdbcTemplate.queryForMap(rowByIdentifierQuery, probeIdentifier);
        assertFieldPreserved(row, "tran_type_cd", field(probe, 17, 2), probeIdentifier);
        assertThat(((Number) row.get("tran_cat_cd")).intValue())
                .as("TRAN-CAT-CD 9(04) at bytes 19-22 of app/cpy/CVTRA05Y.cpy:L7")
                .isEqualTo(Integer.parseInt(field(probe, 19, 4)));
        assertFieldPreserved(row, "tran_source", field(probe, 23, 10), probeIdentifier);
        assertFieldPreserved(row, "tran_desc", field(probe, descriptionStart, descriptionLength),
                probeIdentifier);
        assertThat((BigDecimal) row.get("tran_amt"))
                .as("TRAN-AMT S9(09)V99 at bytes 133-143 of app/cpy/CVTRA05Y.cpy:L10 must arrive with its "
                        + "value and sign intact, compared by value because the column reads back at scale "
                        + "%d", Integer.valueOf(amountScale))
                .isEqualByComparingTo(decodeAmount(field(probe, amountStart, amountLength)));
        assertThat(((Number) row.get("tran_merchant_id")).longValue())
                .as("TRAN-MERCHANT-ID 9(09) at bytes 144-152 of app/cpy/CVTRA05Y.cpy:L11")
                .isEqualTo(Long.parseLong(field(probe, 144, 9)));
        assertFieldPreserved(row, "tran_merchant_name", field(probe, merchantNameStart, merchantNameLength),
                probeIdentifier);
        assertFieldPreserved(row, "tran_merchant_city", field(probe, 203, 50), probeIdentifier);
        assertFieldPreserved(row, "tran_merchant_zip", field(probe, 253, 10), probeIdentifier);
        assertCardNumberPreserved(row, field(probe, cardNumberStart, identifierLength), probeIdentifier);
        assertThat(((Number) row.get("version")).longValue())
                .as("the load binds the optimistic-lock column explicitly, because it is NOT NULL and the "
                        + "bulk insert bypasses the provider that would otherwise seed it")
                .isZero();

        assertThat((String) row.get("tran_orig_ts"))
                .as("TRAN-ORIG-TS X(26) at bytes 279-304 of app/cpy/CVTRA05Y.cpy:L16 is text and is never "
                        + "parsed. The fixture carries one distinct originating timestamp on all 300 "
                        + "records, and it is the instant the harness pins its clock to, rendered as "
                        + "hundredths of a second followed by four literal zeros")
                .isEqualTo(legacyTimestamp());
        assertThat((String) row.get("tran_proc_ts"))
                .as("TRAN-PROC-TS X(26) at bytes 305-330 of app/cpy/CVTRA05Y.cpy:L17 is blank on every "
                        + "fixture record and must survive as %d spaces - not null, not the empty string "
                        + "and not trimmed", Integer.valueOf(timestampLength))
                .isEqualTo(" ".repeat(timestampLength));

        final String negative = firstNegativeAmountRecord(backup);
        final Map<String, Object> negativeRow =
                jdbcTemplate.queryForMap(rowByIdentifierQuery, identifierOf(negative));
        final BigDecimal loadedNegative = (BigDecimal) negativeRow.get("tran_amt");
        assertThat(loadedNegative)
                .as("fifty of the fixture's records carry a negative sign overpunch, and a negative amount "
                        + "is a real amount. No absolute value is taken and no sign is normalised anywhere "
                        + "on this path")
                .isEqualByComparingTo(decodeAmount(field(negative, amountStart, amountLength)));
        assertThat(loadedNegative.signum())
                .as("this guard needs a genuinely negative record or it proves nothing")
                .isNegative();
    }

    /**
     * The load reads the key the sort step published, and not the greatest key under the prefix.
     *
     * <p>This is the hazard the {@code (+1)} at {@code app/jcl/COMBTRAN.jcl:L44} exists to rule out.
     * {@code :L37} creates a generation and {@code :L44} names {@code (+1)} again - the same generation,
     * inside the same job - whereas a {@code (0)} reference would mean "the current generation" and would
     * be resolved afresh. An implementation that resolved the latest generation in the load step would pass
     * every isolated test and load a stranger's object the first time another writer intervened.
     *
     * <p>The test plants exactly such a stranger: an object under the combined prefix whose generation
     * segment is nineteen nines, so it sorts above every key the job can produce, holding one otherwise
     * valid record whose identifier could have come from nowhere else. The load must ignore it completely.
     * The planted record is deliberately made to satisfy every foreign key, so that if it ever <em>were</em>
     * loaded the failure would be this assertion rather than an unrelated constraint error several frames
     * away.
     */
    @Test
    @DisplayName("6. app/jcl/COMBTRAN.jcl:L44 - the load consumes exactly the generation the sort step "
            + "created, ignoring a planted object whose key sorts above every real one")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void loadStepConsumesTheKeyTheSortStepPublishedAndNotALaterGeneration() {
        final List<String> backup = seedBackupGeneration(firstFixtureRecords(2));
        final List<String> systran = seedSystranGeneration(interestRecords(backup, 1));

        final String decoyKey = greatestCombinedKey();
        putGeneration(decoyKey, List.of(withIdentifier(backup.get(0), decoyIdentifier)));

        final JobExecution execution = launchCombine();
        assertCombineCompleted(execution);

        final String publishedKey = publishedKey(execution);
        assertThat(publishedKey)
                .as("the sort step must publish the key it created, and it cannot be the planted one")
                .isNotEqualTo(decoyKey);
        assertThat(publishedKey.compareTo(decoyKey) < 0)
                .as("the planted key %s has to sort above the published key %s, or the test cannot tell a "
                        + "carried-forward reference from a re-resolved one", decoyKey, publishedKey)
                .isTrue();

        assertThat(transactionRepository.existsById(decoyIdentifier))
                .as("identifier %s exists only in the planted object. Its presence in the relation means "
                        + "the load resolved the greatest key under the combined prefix instead of reading "
                        + "the key app/jcl/COMBTRAN.jcl:L44 hands it", decoyIdentifier)
                .isFalse();
        assertThat(transactionRepository.count())
                .as("only the records of the published generation are loaded: %d from TRANSACT.BKUP(0) and "
                        + "%d from SYSTRAN(0)", Integer.valueOf(backup.size()),
                        Integer.valueOf(systran.size()))
                .isEqualTo(backup.size() + systran.size());
        assertThat(publishedRecordCount(execution))
                .as("the published record count describes the generation the sort step wrote, not the "
                        + "planted object")
                .isEqualTo(backup.size() + systran.size());
    }

    // =================================================================================================
    // THE DUPLICATE-IDENTIFIER FAILURE - the outcome that must never be smoothed over
    // =================================================================================================

    /**
     * A repeated identifier fails the load, as a typed duplicate, with its cause preserved and no row left.
     *
     * <p>The scenario is the one the corpus makes reachable rather than a contrived collision. The interest
     * calculator concatenates the ten-character date parameter of {@code app/jcl/INTCALC.jcl:L22} with the
     * never-reset six-digit counter of {@code app/cbl/CBACT04C.cbl:L173} to form its identifiers
     * ({@code :L476-L479}), and writes them to a fresh sequential generation
     * ({@code app/cbl/CBACT04C.cbl:L309}) which performs no duplicate detection at all. Run it twice with
     * the same date parameter and the same identifiers are produced twice: once already carried in the
     * transaction backup that {@code app/jcl/COMBTRAN.jcl:L24} reads, and once in the current
     * {@code SYSTRAN} generation that {@code :L26} reads. Both records reach the load, because
     * {@code SORT} emits both and it is the {@code REPRO} of {@code :L48} that rejects the second.
     *
     * <p>Both planted records therefore carry the <em>same</em> identifier and no other, which also makes
     * the assertion on the reported identifier exact: whichever record of the batch a driver names, the
     * identifier it names is that one.
     *
     * <p>The required outcome is a failed step carrying
     * {@code com.cardemo.exception.DuplicateRecordException} - not an on-conflict clause, not a merge, not
     * a retry, not a regenerated identifier and not a substituted sequence. The relation is asserted empty
     * afterwards, which is the observable difference between a failure and an upsert: an upsert would have
     * left one row behind.
     */
    @Test
    @DisplayName("7. app/jcl/COMBTRAN.jcl:L48 - a repeated TRAN-ID fails the load as a typed duplicate "
            + "with its cause preserved, and leaves no row behind: never an upsert")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void duplicateIdentifierFailsTheLoadRatherThanBecomingAnUpsert() {
        final List<String> fixture = firstFixtureRecords(2);
        final String repeated = interestIdentifier("000001");
        seedBackupGeneration(List.of(withIdentifier(fixture.get(0), repeated)));
        seedSystranGeneration(List.of(withIdentifier(fixture.get(1), repeated)));

        final JobExecution execution = launchCombine();

        assertThat(execution.getStatus())
                .as("REPRO into a keyed cluster is rejected by a duplicate key, so the migrated load must "
                        + "be rejected too and the step must fail with it")
                .isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getExitStatus().getExitCode())
                .as("app/jcl/COMBTRAN.jcl has no program, so there is no RETURN-CODE statement to cite for "
                        + "this outcome and none is fabricated. What is asserted is the failed exit status "
                        + "itself")
                .isEqualTo(ExitStatus.FAILED.getExitCode());

        final DuplicateRecordException duplicate = duplicateFailure(execution);
        assertThat(duplicate.getMessage())
                .as("the message must name the colliding identifier, or an operator cannot find the record "
                        + "that caused the failure")
                .contains(repeated);
        assertThat(duplicate.getMessage())
                .as("and it must say where the obligation comes from, so the reader is not left guessing "
                        + "why a duplicate is fatal")
                .contains("app/jcl/COMBTRAN.jcl:L48");
        assertThat(duplicate.getLogicalFile())
                .as("the logical file is the DD name of :L43, the input side of the REPRO")
                .isEqualTo("TRANSACT");
        assertThat(duplicate.getCollidingKey())
                .as("the colliding key is carried as data on the exception, unrendered, so a caller can "
                        + "act on it without parsing a message")
                .isEqualTo(repeated);
        assertThat(duplicate.getCause())
                .as("the store's own report is preserved as the cause rather than swallowed or replaced; "
                        + "its text is the driver's and is never copied into the message above")
                .isInstanceOf(DataAccessException.class);

        assertThat(transactionRepository.count())
                .as("this is the assertion that separates a failure from an upsert. An on-conflict clause, "
                        + "a merge or a retry would have left one row behind; a rejected load inside the "
                        + "step's transaction leaves none")
                .isZero();
    }

    // =================================================================================================
    // BOUNDARY CONDITIONS - the three ways the concatenated SORTIN can be short of records
    // =================================================================================================

    /**
     * An empty {@code SYSTRAN(0)} generation leaves the backup records to be combined on their own.
     *
     * <p>{@code app/jcl/INTCALC.jcl} is a separate job that need not have run, and
     * {@code app/jcl/COMBTRAN.jcl} carries no {@code COND} obliging it to have run, so a current interest
     * generation with nothing in it is an ordinary state and not a failure. The combine must still produce
     * its one {@code SORTOUT} generation and still load it - which is exactly the run in which no interest
     * transaction is merged.
     */
    @Test
    @DisplayName("8. an empty SYSTRAN(0) generation is an ordinary state - app/jcl/INTCALC.jcl need not "
            + "have run - so only the TRANSACT.BKUP(0) records are combined and loaded")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void anEmptySystranGenerationLeavesOnlyTheBackupRecordsToCombine() {
        final List<String> backup = seedBackupGeneration(firstFixtureRecords(4));
        putGeneration(systranGenerationKey(), List.of());

        final JobExecution execution = launchCombine();
        assertCombineCompleted(execution);

        assertThat(publishedRecordCount(execution))
                .as("the combined generation holds the first source and nothing else")
                .isEqualTo(backup.size());
        assertThat(identifiersOf(combinedRecords(execution)))
                .as("and it holds them in the order SORT FIELDS=(TRAN-ID,A) leaves them in")
                .isEqualTo(identifiersOf(backup));
        assertThat(transactionRepository.count())
                .as("REPRO copies the generation as it stands")
                .isEqualTo(backup.size());
    }

    /**
     * An empty {@code TRANSACT.BKUP(0)} generation leaves the interest records to be combined on their own.
     *
     * <p>The mirror boundary. The distinction that matters is between <em>empty</em> and <em>absent</em>: an
     * empty generation is a real state and is exercised here, and the absent-generation state is exercised by
     * {@link #anAbsentBackupGenerationPrefixCombinesTheSystranRecordsAlone()} immediately below.
     *
     * <p>An earlier revision of this class recorded the absent case as <strong>Not available</strong> as a
     * parity claim and declined to test it, on the grounds that a file-unavailable status occurs nowhere in
     * the COBOL corpus. The premise was sound but the conclusion inverted the consequence: because no such
     * status exists in the corpus, the reader had no business <em>producing</em> one, and the untested path
     * was the one that occurs on every clean environment. Both states are now asserted.
     */
    @Test
    @DisplayName("9. an empty TRANSACT.BKUP(0) generation leaves only the SYSTRAN(0) records to combine, "
            + "and empty is a different state from absent")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void anEmptyBackupGenerationLeavesOnlyTheSystranRecordsToCombine() {
        final List<String> systran = seedSystranGeneration(interestRecords(firstFixtureRecords(3), 3));
        putGeneration(backupGenerationKey(), List.of());

        final JobExecution execution = launchCombine();
        assertCombineCompleted(execution);

        assertThat(identifiersOf(combinedRecords(execution)))
                .as("the second source is combined on its own, in identifier order")
                .isEqualTo(identifiersOf(systran));
        assertThat(transactionRepository.count())
                .as("and every one of its records is loaded")
                .isEqualTo(systran.size());
    }

    /**
     * An <em>absent</em> {@code TRANSACT.BKUP} prefix is the clean-environment state, not a failure.
     *
     * <p>This is the state every first run of the pipeline is in. Nothing in this application produces a
     * {@code TRANSACT.BKUP} generation before the combine stage: the sole producer is
     * {@code TransactionReportJob}'s {@code STEP01R} ({@code app/proc/TRANREPT.prc:L21}), which runs in
     * <strong>stage 4</strong>, downstream of the stage 3 combine that reads the base. The mainframe's
     * producer for that leg is {@code app/jcl/TRANBKP.jcl}, a separate operator member with no Java analogue
     * by recorded decision. So on a clean environment the first leg is absent <em>by construction</em>.
     *
     * <p>An earlier revision reported this as file status {@code '35'} from
     * {@code CombinedTransactionReader}, which made the authored five-stage topology
     * {@code POSTTRAN -> INTCALC -> COMBTRAN -> (CREASTMT || TRANREPT)} unsatisfiable: the pipeline abended in
     * stage 3 and stages 4's two branches never received a {@code StepExecution} at all. Nothing in the
     * corpus justified it - {@code app/jcl/COMBTRAN.jcl} carries no {@code COND=} on either {@code :L22} or
     * {@code :L41}, so the member asserts no precondition on either DD - and the same reader already treated
     * an absent {@code SYSTRAN} generation as an ordinary empty read. The two legs are now symmetric.
     *
     * <p>The precondition is asserted rather than assumed: the case proves the prefix holds no object at all,
     * so that a future change to per-test bucket cleanup cannot quietly turn this into a re-run of the
     * empty-object case above.
     */
    @Test
    @DisplayName("9a. an ABSENT TRANSACT.BKUP prefix is the clean-environment state and combines the "
            + "SYSTRAN(0) records alone, rather than abending on file status 35")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void anAbsentBackupGenerationPrefixCombinesTheSystranRecordsAlone() {
        final List<String> systran = seedSystranGeneration(interestRecords(firstFixtureRecords(3), 3));

        assertThat(s3Client.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(batchOutputBucket)
                        .prefix(backupPrefix)
                        .build())
                        .contents())
                .as("the precondition is a genuinely absent prefix - no generation object, not an empty one")
                .isEmpty();

        final JobExecution execution = launchCombine();
        assertCombineCompleted(execution);

        assertThat(identifiersOf(combinedRecords(execution)))
                .as("the absent first leg contributes nothing and the second is combined on its own")
                .isEqualTo(identifiersOf(systran));
        assertThat(transactionRepository.count())
                .as("and every one of its records is loaded")
                .isEqualTo(systran.size());
    }

    /**
     * Two objects under one generation are refused by name, never silently reduced to one.
     *
     * <p><b>A GDG generation is one dataset, so it is one object.</b> {@code RejectWriter} states that
     * invariant, but nothing enforced it on the reading side: {@code (0)} resolution kept the lexicographically
     * greatest key and discarded every sibling without a log, an exception or a count check. Planting two
     * 350-byte objects under one {@code TRANSACT.BKUP} generation plus one {@code SYSTRAN} generation
     * therefore produced a run that reported {@code COMPLETED} with return code 0 and a combined record count
     * of 2, with the first planted identifier simply absent from the relation.
     *
     * <p>That is the worst available outcome, and it is why this test asserts a refusal rather than a warning.
     * A subset loaded under a success status cannot be distinguished from a correct run by any consumer: the
     * record count looks plausible, the exit status is clean, and the missing identifier is discoverable only
     * by comparing against a source nobody retained. Reading every sibling instead would be no better, since
     * their concatenation order is undefined - no convention says which of {@code PART-A} and {@code PART-B}
     * precedes the other - so it would trade a dropped record for an arbitrary order.
     *
     * <p>The diagnostic must name <b>every</b> candidate key rather than merely reporting that contention
     * exists, because the operator's next action is to decide which object to remove. Both keys are asserted
     * present in the message for that reason, and the relation is asserted empty so that a partial load
     * followed by a refusal cannot pass as a refusal.
     *
     * <p>A restart is one way this shape arises rather than a contrived one, which is what makes the case
     * worth holding: a chunk-oriented step that writes a generation and then fails can leave one object
     * behind and write another on the next attempt.
     */
    @Test
    @DisplayName("7a. two objects under one generation prefix are refused with every candidate key named, "
            + "rather than resolved to the greatest and the rest dropped in silence")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void siblingObjectsUnderOneGenerationAreRefusedByNameRatherThanSilentlyDropped() {
        final List<String> fixture = firstFixtureRecords(3);
        final String partAKey = generationKey(backupPrefix, currentGenerationSegment, "PART-A");
        final String partBKey = generationKey(backupPrefix, currentGenerationSegment, "PART-B");
        putGeneration(partAKey, List.of(withIdentifier(fixture.get(0), "0000000000000011")));
        putGeneration(partBKey, List.of(withIdentifier(fixture.get(1), "0000000000000012")));
        seedSystranGeneration(List.of(withIdentifier(fixture.get(2), "0000000000000013")));

        final JobExecution execution = launchCombine();

        assertThat(execution.getStatus())
                .as("an ambiguous generation must fail the step; the previous behaviour was RC0 COMPLETED "
                        + "with one of the three records missing")
                .isEqualTo(BatchStatus.FAILED);

        final DataIntegrityException ambiguity = generationAmbiguityFailure(execution);
        assertThat(ambiguity.getMessage())
                .as("the operator's next action is to remove one object, so every candidate key must be "
                        + "named - reporting only that contention exists is not actionable")
                .contains(partAKey)
                .contains(partBKey);

        assertThat(transactionRepository.count())
                .as("a refusal must leave nothing behind, or a partial load would pass as a refusal")
                .isZero();
    }

    /**
     * Two empty generations produce an empty generation, load nothing, and still complete.
     *
     * <p>The far boundary, and the one an implementation is most likely to get wrong by treating "nothing
     * to do" as an error or by skipping the output altogether. {@code app/jcl/COMBTRAN.jcl:L33-L37}
     * allocates {@code SORTOUT} unconditionally, so an empty combined generation is created, and
     * {@code :L48} copies it - a copy of nothing, which is a copy that succeeds.
     */
    @Test
    @DisplayName("10. two empty generations still allocate the SORTOUT of app/jcl/COMBTRAN.jcl:L33-L37, "
            + "load nothing, and complete")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void twoEmptyGenerationsProduceAnEmptyCombinedGenerationAndLoadNothing() {
        putGeneration(backupGenerationKey(), List.of());
        putGeneration(systranGenerationKey(), List.of());

        final JobExecution execution = launchCombine();
        assertCombineCompleted(execution);

        assertThat(publishedRecordCount(execution))
                .as("nothing was read, so nothing was written")
                .isZero();
        assertThat(generationPayload(publishedKey(execution)))
                .as("the generation is still created, and it is empty rather than absent")
                .isEmpty();
        assertThat(transactionRepository.count())
                .as("an empty copy leaves the relation as the seed left it")
                .isZero();
    }

    // =================================================================================================
    // RISKY-PATTERN DISCHARGE - generation content is data, and the sort is in-process
    // =================================================================================================

    /**
     * Content lifted from a generation object is bound as data and is never interpreted.
     *
     * <p>An object in the generation bucket is untrusted input: it arrives as bytes and nothing about its
     * content is known until it has been validated. This test plants a record whose text fields carry the
     * three families of metacharacter that a naive implementation would hand to something that interprets
     * them - statement syntax, shell syntax, and the two-byte header that a Java object stream begins with -
     * and then asserts that all of it arrives in the relation <strong>verbatim</strong>.
     *
     * <p>That single observation discharges three obligations at once. The statement payload could only
     * round-trip intact through a fully parameterised insert; interpolated into statement text it would
     * either fail to parse or execute, and the {@code count} assertions below would see a missing relation.
     * The shell payload could only round-trip intact if nothing passed the field to a shell. And the
     * serialization header could only round-trip intact if the object was consumed as fixed-width text
     * rather than handed to an object-input stream. The reference census afterwards is the negative control:
     * the three relations the posted-transaction foreign keys point at still hold exactly what the seed
     * migration put in them.
     */
    @Test
    @DisplayName("11. statement, shell and object-stream metacharacters lifted from a generation object "
            + "arrive in the relation verbatim - REPRO becomes a parameterised bind, never interpolation")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void generationContentIsBoundAsDataAndNeverInterpretedAsCodeOrStatementText() {
        final String template = interestRecords(firstFixtureRecords(1), 1).get(0);
        final String hostile = withField(
                withField(template, descriptionStart, padded(statementMetacharacters, descriptionLength)),
                merchantNameStart, padded(shellMetacharacters, merchantNameLength));
        putGeneration(backupGenerationKey(), List.of());
        seedSystranGeneration(List.of(hostile));

        final JobExecution execution = launchCombine();
        assertCombineCompleted(execution);

        final Map<String, Object> row =
                jdbcTemplate.queryForMap(rowByIdentifierQuery, identifierOf(hostile));
        assertThat((String) row.get("tran_desc"))
                .as("statement metacharacters and an object-stream header occupy TRAN-DESC. Arriving intact "
                        + "is what proves the load of app/jcl/COMBTRAN.jcl:L48 binds every field as a "
                        + "parameter and assembles no statement text from record content")
                .isEqualTo(padded(statementMetacharacters, descriptionLength));
        assertThat((String) row.get("tran_merchant_name"))
                .as("shell metacharacters occupy TRAN-MERCHANT-NAME. Arriving intact is what proves no "
                        + "field is handed to a command interpreter on the way")
                .isEqualTo(padded(shellMetacharacters, merchantNameLength));

        final Map<String, Object> census = jdbcTemplate.queryForMap(referenceCensusQuery);
        assertThat(((Number) census.get("cards")).longValue())
                .as("the negative control: the card relation still holds every row the seed migration "
                        + "loaded from app/data/ASCII/carddata.txt")
                .isEqualTo(seededCardCount);
        assertThat(((Number) census.get("types")).longValue())
                .as("and the transaction types of app/data/ASCII/trantype.txt are all still there")
                .isEqualTo(seededTypeCount);
        assertThat(((Number) census.get("categories")).longValue())
                .as("and so are the categories of app/data/ASCII/trancatg.txt")
                .isEqualTo(seededCategoryCount);
        assertThat(transactionRepository.count())
                .as("exactly the one planted record is loaded, so nothing else ran")
                .isEqualTo(1L);
    }

    /**
     * The run spawns no child process, because the external sort became an in-process comparator.
     *
     * <p>{@code app/jcl/COMBTRAN.jcl:L22} is {@code EXEC PGM=SORT}, an invocation of the platform's sort
     * utility, and the migration replaces it with a {@code java.util.Comparator} applied inside the step.
     * The observable consequence is that the job launches nothing: no sort utility, no helper binary, no
     * script. This test measures that directly, by comparing the process's children before and after the
     * run rather than by reading the implementation, so a future revision that reached for an external
     * command would be caught by behaviour.
     *
     * <p>It is the same measurement that answers the standing prohibition on evaluation and command
     * execution, which is why the two are checked together here rather than argued separately.
     */
    @Test
    @DisplayName("12. app/jcl/COMBTRAN.jcl:L22 EXEC PGM=SORT becomes an in-process comparator, so the run "
            + "spawns no child process at all")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theCombineRunSpawnsNoExternalProcess() {
        final List<String> backup = seedBackupGeneration(firstFixtureRecords(3));
        seedSystranGeneration(interestRecords(backup, 2));
        final Set<Long> before = childProcessIds();

        final JobExecution execution = launchCombine();
        assertCombineCompleted(execution);

        final Set<Long> spawned = childProcessIds();
        spawned.removeAll(before);
        assertThat(spawned)
                .as("the sort of app/jcl/COMBTRAN.jcl:L30 is performed by a comparator inside this process. "
                        + "A child process appearing across the run means an external utility was invoked, "
                        + "which would reintroduce the very dependency the migration removed")
                .isEmpty();
    }

    // =================================================================================================
    // INPUT CONSTRUCTION - frozen fixture bytes, spliced only where a test needs a different identifier
    // =================================================================================================

    /**
     * Reads every record of the frozen daily transaction fixture through the harness's loader.
     *
     * @return the 300 records in file order, each exactly {@link #recordLength} characters with trailing
     *     spaces intact, never {@code null}
     */
    private List<String> fixtureRecords() {
        final List<String> records = readFixture(dailyTransactionFixture);
        assertThat(records)
                .as("the fixture must be non-empty, or every case built on it would pass vacuously")
                .isNotEmpty();
        return records;
    }

    /**
     * Reads the leading records of the frozen fixture, for cases that do not need all 300.
     *
     * @param count how many records to take, must not exceed the fixture's size
     * @return the first {@code count} records in file order, never {@code null}
     */
    private List<String> firstFixtureRecords(final int count) {
        final List<String> records = fixtureRecords();
        assertThat(records.size())
                .as("the fixture must hold at least the %d records this case asks for",
                        Integer.valueOf(count))
                .isGreaterThanOrEqualTo(count);
        return List.copyOf(records.subList(0, count));
    }

    /**
     * Derives interest-shaped records by replacing only the identifier of frozen fixture records.
     *
     * <p>Every other byte of each template record is left exactly as the fixture holds it - the real card
     * number, the real amount with its real sign overpunch, the real type and category codes and the real
     * timestamps - so the record is a genuine 350-byte image from the corpus that merely arrives under an
     * identifier of the shape the interest calculator produces. The identifiers begin at suffix
     * {@code 000009} so that the ninth and tenth are adjacent inside the generation, which is what makes
     * the zero-padding guard in the ordering case meaningful.
     *
     * @param templates frozen fixture records to reshape, must hold at least {@code count} entries
     * @param count how many interest records to derive
     * @return the derived records in ascending identifier order, never {@code null}
     */
    private List<String> interestRecords(final List<String> templates, final int count) {
        assertThat(templates.size())
                .as("one template record is needed per derived record")
                .isGreaterThanOrEqualTo(count);
        final List<String> records = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            records.add(withIdentifier(
                    templates.get(index), interestIdentifier(sixDigitSuffix(9 + index))));
        }
        return records;
    }

    /**
     * Composes an identifier the way {@code app/cbl/CBACT04C.cbl:L476-L479} composes one.
     *
     * @param suffix the six-digit counter value, zero padded
     * @return the sixteen-character identifier, never {@code null}
     */
    private String interestIdentifier(final String suffix) {
        final String identifier = interestDateParameter + suffix;
        assertThat(identifier.length())
                .as("the ten-character date parameter and a six-digit suffix make exactly the sixteen "
                        + "characters the sort symbol at app/jcl/COMBTRAN.jcl:L28 fixes")
                .isEqualTo(identifierLength);
        return identifier;
    }

    /**
     * Renders the six-digit, zero-padded counter of {@code app/cbl/CBACT04C.cbl:L173}.
     *
     * @param ordinal the counter value
     * @return six digits, never {@code null}
     */
    private String sixDigitSuffix(final int ordinal) {
        return String.format(Locale.ROOT, "%06d", Integer.valueOf(ordinal));
    }

    /**
     * Reads one field of a record image by its one-based offset in {@code app/cpy/CVTRA05Y.cpy}.
     *
     * @param record a record image of exactly {@link #recordLength} characters
     * @param oneBasedStart the field's first byte, counting from one as the copybook does
     * @param length the field's width
     * @return the field exactly as held, padding included, never {@code null}
     */
    private String field(final String record, final int oneBasedStart, final int length) {
        assertThat(record.length())
                .as("a field can only be read from a whole record image")
                .isEqualTo(recordLength);
        return record.substring(oneBasedStart - 1, oneBasedStart - 1 + length);
    }

    /**
     * Replaces one field of a record image, leaving its length and every other byte untouched.
     *
     * @param record a record image of exactly {@link #recordLength} characters
     * @param oneBasedStart the field's first byte, counting from one
     * @param value the replacement, whose length is the field's width
     * @return the reshaped image, still exactly {@link #recordLength} characters, never {@code null}
     */
    private String withField(final String record, final int oneBasedStart, final String value) {
        final String reshaped = record.substring(0, oneBasedStart - 1) + value
                + record.substring(oneBasedStart - 1 + value.length());
        assertThat(reshaped.length())
                .as("splicing a field must not change the geometry app/jcl/COMBTRAN.jcl:L35 fixes through "
                        + "DCB=(*.SORTIN)")
                .isEqualTo(recordLength);
        return reshaped;
    }

    /**
     * Replaces the identifier of a record image.
     *
     * @param record a record image of exactly {@link #recordLength} characters
     * @param identifier the replacement identifier, exactly {@link #identifierLength} characters
     * @return the reshaped image, never {@code null}
     */
    private String withIdentifier(final String record, final String identifier) {
        assertThat(identifier.length())
                .as("TRAN-ID is fixed at %d characters by the sort symbol of app/jcl/COMBTRAN.jcl:L28, so "
                        + "neither a shorter nor a longer value is that field",
                        Integer.valueOf(identifierLength))
                .isEqualTo(identifierLength);
        return withField(record, 1, identifier);
    }

    /**
     * Right pads a value with spaces to a fixed field width, as a fixed-width record requires.
     *
     * @param value the value, which must fit the field
     * @param width the field's width
     * @return the padded value, exactly {@code width} characters, never {@code null}
     */
    private String padded(final String value, final int width) {
        assertThat(value.length())
                .as("a value wider than its field cannot be placed in a fixed-width record")
                .isLessThanOrEqualTo(width);
        return value + " ".repeat(width - value.length());
    }

    // =================================================================================================
    // GENERATION KEYS AND OBJECTS - (0) resolves the greatest key, (+1) creates a new one
    // =================================================================================================

    /**
     * Writes the {@code TRANSACT.BKUP(0)} generation of {@code app/jcl/COMBTRAN.jcl:L24}.
     *
     * @param records the record images to place in it, possibly empty
     * @return the same records, so a case can name its input and its expectation once
     */
    private List<String> seedBackupGeneration(final List<String> records) {
        putGeneration(backupGenerationKey(), records);
        return records;
    }

    /**
     * Writes the {@code SYSTRAN(0)} generation of {@code app/jcl/COMBTRAN.jcl:L26}.
     *
     * @param records the record images to place in it, possibly empty
     * @return the same records
     */
    private List<String> seedSystranGeneration(final List<String> records) {
        putGeneration(systranGenerationKey(), records);
        return records;
    }

    /**
     * Writes one fixed-block object with no record delimiter, the shape {@code RECFM=F} declares.
     *
     * <p>An empty list produces a zero-length object, which is how an empty generation is expressed: the
     * generation exists, and holds nothing. Encoding is single byte throughout, so one character of a
     * record image is one byte of the object and the record boundary is an index rather than a search.
     *
     * @param key the object key, which must lie under a generation prefix
     * @param records the record images, each exactly {@link #recordLength} characters
     */
    private void putGeneration(final String key, final List<String> records) {
        final StringBuilder payload = new StringBuilder(records.size() * recordLength);
        for (final String record : records) {
            assertThat(record.length())
                    .as("app/cpy/CVTRA05Y.cpy:L2 declares RECLN = 350 and app/jcl/COMBTRAN.jcl:L35 carries "
                            + "it onto the output, so a synthetic generation must carry the same geometry "
                            + "or the reader is right to reject it")
                    .isEqualTo(recordLength);
            payload.append(record);
        }
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(batchOutputBucket)
                        .key(key)
                        .contentType(generationContentType)
                        .build(),
                RequestBody.fromBytes(payload.toString().getBytes(StandardCharsets.ISO_8859_1)));
    }

    /**
     * The key of the one {@code TRANSACT.BKUP} generation a case writes.
     *
     * @return the key, never {@code null}
     */
    private String backupGenerationKey() {
        return generationKey(backupPrefix, currentGenerationSegment, backupObjectName);
    }

    /**
     * The key of the one {@code SYSTRAN} generation a case writes.
     *
     * @return the key, never {@code null}
     */
    private String systranGenerationKey() {
        return generationKey(systranPrefix, currentGenerationSegment, systranObjectName);
    }

    /**
     * A key under the combined prefix that sorts above every key the job can produce.
     *
     * <p>It borrows the job's own key shape deliberately, so that an implementation which resolved the
     * greatest key would find something that looks exactly like a newer generation rather than something it
     * would reject on sight.
     *
     * @return the planted key, never {@code null}
     */
    private String greatestCombinedKey() {
        return stripTrailingSeparators(combinedPrefix) + "/" + greatestGenerationSegment
                + "/transact-combined-" + greatestGenerationSegment + ".dat";
    }

    /**
     * Composes a generation key from a prefix, a generation segment and an object name.
     *
     * @param prefix the configured generation prefix, with or without a trailing separator
     * @param generation the zero-padded generation segment
     * @param objectName the object's own name
     * @return the key, never {@code null}
     */
    private String generationKey(final String prefix, final String generation, final String objectName) {
        return stripTrailingSeparators(prefix) + "/generation=" + generation + "/" + objectName;
    }

    /**
     * Removes any trailing separators from a configured prefix.
     *
     * <p>Necessary rather than tidy: a doubled separator makes a key the reader refuses, and whether the
     * configured value ends in one is a property of the configuration rather than of this class.
     *
     * @param prefix the configured prefix
     * @return the prefix with no trailing separator, never {@code null} and never empty
     */
    private String stripTrailingSeparators(final String prefix) {
        int end = prefix.length();
        while (end > 0 && prefix.charAt(end - 1) == '/') {
            end--;
        }
        assertThat(end)
                .as("a generation prefix must name a prefix and not consist only of separators")
                .isPositive();
        return prefix.substring(0, end);
    }

    /**
     * Reads one object of the generation bucket in full.
     *
     * @param key the object key
     * @return the object's bytes, never {@code null} and possibly empty
     */
    private byte[] generationPayload(final String key) {
        return s3Client.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(batchOutputBucket)
                        .key(key)
                        .build())
                .asByteArray();
    }

    /**
     * Splits a fixed-block payload into its records.
     *
     * <p>The stream carries no delimiter, so boundaries are found by counting bytes and by nothing else. A
     * remainder means the object is not a whole number of records, after which no offset in it can be
     * trusted, so it is refused rather than parsed.
     *
     * @param payload the object's bytes
     * @return the records in order, never {@code null}
     */
    private List<String> fixedWidthRecords(final byte[] payload) {
        assertThat(payload.length % recordLength)
                .as("a fixed-block generation holds a whole number of %d-byte records; a remainder means "
                        + "the geometry of app/jcl/COMBTRAN.jcl:L35 was not preserved",
                        Integer.valueOf(recordLength))
                .isZero();
        final String image = new String(payload, StandardCharsets.ISO_8859_1);
        final List<String> records = new ArrayList<>(payload.length / recordLength);
        for (int offset = 0; offset < image.length(); offset += recordLength) {
            records.add(image.substring(offset, offset + recordLength));
        }
        return records;
    }

    // =================================================================================================
    // LAUNCHING AND READING BACK
    // =================================================================================================

    /**
     * Launches the job with the harness's per-test discriminator and no parameter of its own.
     *
     * <p>{@code app/jcl/COMBTRAN.jcl} declares no {@code PARM}, no parameter card and no symbolic
     * substitution, so the job takes nothing beyond the identifier that keeps one test's job instance
     * distinct from another's.
     *
     * @return the completed or failed execution, never {@code null}
     */
    private JobExecution launchCombine() {
        return launchJob(combineTransactionsJob, runIdParameters(Map.of()));
    }

    /**
     * Asserts a run reached the normal outcome with nothing reported against it.
     *
     * @param execution the execution to inspect
     */
    private void assertCombineCompleted(final JobExecution execution) {
        assertThat(execution.getAllFailureExceptions())
                .as("neither step of app/jcl/COMBTRAN.jcl is conditional and this input carries no repeated "
                        + "identifier, so the run must complete with nothing reported against it")
                .isEmpty();
        assertThat(execution.getStatus())
                .as("the normal outcome of the member is a completed run")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .as("and the exit status the orchestrated pipeline reads must say so")
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
    }

    /**
     * Reads the concrete key the sort step published for {@code app/jcl/COMBTRAN.jcl:L44}.
     *
     * @param execution the execution whose job context carries the handoff
     * @return the key, never {@code null} and never blank
     */
    private String publishedKey(final JobExecution execution) {
        final String key = execution.getExecutionContext().getString(publishedKeyContextEntry, null);
        assertThat(key)
                .as("the sort step publishes the generation it created under '%s'; without it the load step "
                        + "would have nothing to read and must not fall back to resolving a key of its own",
                        publishedKeyContextEntry)
                .isNotNull()
                .isNotBlank();
        return key;
    }

    /**
     * Reads the record count the sort step published alongside the key.
     *
     * @param execution the execution whose job context carries the handoff
     * @return the count
     */
    private int publishedRecordCount(final JobExecution execution) {
        assertThat(execution.getExecutionContext().containsKey(publishedCountContextEntry))
                .as("the record count travels with the key under '%s'", publishedCountContextEntry)
                .isTrue();
        return execution.getExecutionContext().getInt(publishedCountContextEntry);
    }

    /**
     * Downloads the combined generation a run produced and splits it into records.
     *
     * @param execution the execution whose job context carries the key
     * @return the records in the order the generation holds them, never {@code null}
     */
    private List<String> combinedRecords(final JobExecution execution) {
        final List<String> records = fixedWidthRecords(generationPayload(publishedKey(execution)));
        assertThat(records)
                .as("the generation must hold exactly the number of records the sort step published, or the "
                        + "object read back is not the object written")
                .hasSize(publishedRecordCount(execution));
        return records;
    }

    /**
     * Extracts the identifier of every record, in order.
     *
     * @param records the record images
     * @return the identifiers, never {@code null}
     */
    private List<String> identifiersOf(final List<String> records) {
        final List<String> identifiers = new ArrayList<>(records.size());
        for (final String record : records) {
            identifiers.add(identifierOf(record));
        }
        return identifiers;
    }

    /**
     * Extracts one record's identifier.
     *
     * @param record a record image of exactly {@link #recordLength} characters
     * @return the sixteen-character identifier, never {@code null}
     */
    private String identifierOf(final String record) {
        return field(record, 1, identifierLength);
    }

    /**
     * Finds the first record carrying a negative sign overpunch.
     *
     * @param records the record images to search
     * @return the first record whose amount is negative, never {@code null}
     */
    private String firstNegativeAmountRecord(final List<String> records) {
        String found = null;
        for (final String record : records) {
            if (found == null && decodeAmount(field(record, amountStart, amountLength)).signum() < 0) {
                found = record;
            }
        }
        assertThat(found)
                .as("a negative record is needed to prove no sign is normalised; fifty of the fixture's "
                        + "three hundred carry a negative overpunch, so a selection that contains none is "
                        + "too narrow")
                .isNotNull();
        return found;
    }

    /**
     * Decodes {@code TRAN-AMT PIC S9(09)V99} from its eleven-byte zoned-decimal form.
     *
     * <p>An independent decoder, deliberately: the point of comparing against it is that it is not the
     * encoder under test. The field's <strong>final byte is a sign overpunch and not a digit</strong>, and
     * decoding is position aware for that reason - the same letters occur legitimately inside the
     * description, the merchant name and the merchant city, and reading one of those as a sign would be a
     * silent corruption. Arithmetic is decimal throughout at the column's own scale, with no floating-point
     * type anywhere on the path.
     *
     * @param amountField exactly {@link #amountLength} characters
     * @return the signed amount at scale {@link #amountScale}, never {@code null}
     */
    private BigDecimal decodeAmount(final String amountField) {
        assertThat(amountField.length())
                .as("TRAN-AMT occupies exactly %d bytes of app/cpy/CVTRA05Y.cpy:L10",
                        Integer.valueOf(amountLength))
                .isEqualTo(amountLength);
        final String leadingDigits = amountField.substring(0, amountField.length() - 1);
        final char overpunch = amountField.charAt(amountField.length() - 1);

        int finalDigit = positiveOverpunch.indexOf(overpunch);
        boolean negative = false;
        if (finalDigit < 0) {
            finalDigit = negativeOverpunch.indexOf(overpunch);
            negative = true;
        }
        assertThat(finalDigit)
                .as("the final byte of TRAN-AMT must be a positive or negative zoned-decimal overpunch, or "
                        + "neither the sign nor the last digit can be recovered")
                .isNotNegative();

        final BigDecimal unscaled = new BigDecimal(leadingDigits + finalDigit);
        final BigDecimal amount =
                unscaled.movePointLeft(amountScale).setScale(amountScale, RoundingMode.HALF_EVEN);
        return negative ? amount.negate() : amount;
    }

    /**
     * Removes leading zeros, to express what a value would look like if its padding were discarded.
     *
     * <p>Used only to establish that the ordering guard has a pair whose order the padding decides.
     *
     * @param value the padded value
     * @return the value with leading zeros removed, keeping at least one character, never {@code null}
     */
    private String stripLeadingZeros(final String value) {
        int index = 0;
        while (index < value.length() - 1 && value.charAt(index) == '0') {
            index++;
        }
        return value.substring(index);
    }

    /**
     * Renders the harness's pinned instant in the legacy 26-character form.
     *
     * <p>Hundredths of a second then four literal zeros. The instant comes from the injected clock, which is
     * the only source of time this class has, so the expected value cannot drift from the one the pinned
     * clock represents.
     *
     * @return the 26-character timestamp, never {@code null}
     */
    private String legacyTimestamp() {
        final String rendered = legacyTimestampFormat.format(fixedInstant()) + legacyTimestampTrailer;
        assertThat(rendered.length())
                .as("both timestamp fields of app/cpy/CVTRA05Y.cpy are PIC X(26)")
                .isEqualTo(timestampLength);
        return rendered;
    }

    // =================================================================================================
    // COMPOUND ASSERTIONS
    // =================================================================================================

    /**
     * Asserts two record lists hold byte-identical images, naming only the position and the identifier.
     *
     * <p>The images are compared through a boolean so that a mismatch reports the record's position and its
     * identifier and nothing else. A record carries a sixteen-digit card number at bytes 263-278, and no
     * assertion message in this class may quote one.
     *
     * @param actual the images the generation holds
     * @param expected the images the source held
     * @param sourceName the concatenated source being compared, for the message
     */
    private void assertRecordsIdentical(
            final List<String> actual, final List<String> expected, final String sourceName) {

        assertThat(actual)
                .as("%s contributed %d records, so that many must appear", sourceName,
                        Integer.valueOf(expected.size()))
                .hasSameSizeAs(expected);
        for (int index = 0; index < expected.size(); index++) {
            assertThat(actual.get(index).equals(expected.get(index)))
                    .as("record %d of the %s run of the combined generation, identifier %s, differs from "
                            + "the image that source held. SORT orders records and never reshapes them, and "
                            + "DCB=(*.SORTIN) at app/jcl/COMBTRAN.jcl:L35 fixes the output geometry to the "
                            + "input's, so any difference breaks the byte-level contract at the object "
                            + "boundary", Integer.valueOf(index), sourceName,
                            identifierOf(expected.get(index)))
                    .isTrue();
        }
    }

    /**
     * Asserts one column of a loaded row holds the bytes the record image held at that field's offset.
     *
     * <p>The card-number column is deliberately not routed through here; it has an assertion of its own
     * that names no value.
     *
     * @param row the loaded row
     * @param column the column name
     * @param expected the field as the record image held it, padding included
     * @param identifier the row's identifier, for the message
     */
    private void assertFieldPreserved(final Map<String, Object> row, final String column,
            final String expected, final String identifier) {

        assertThat((String) row.get(column))
                .as("column %s of the row loaded for identifier %s must hold exactly the bytes the record "
                        + "image carried at that field's offset in app/cpy/CVTRA05Y.cpy, trailing padding "
                        + "included, because padding is data in a fixed-width record", column, identifier)
                .isEqualTo(expected);
    }

    /**
     * Asserts the card number survived, without placing it in a message.
     *
     * @param row the loaded row
     * @param expected the field as the record image held it
     * @param identifier the row's identifier, for the message
     */
    private void assertCardNumberPreserved(
            final Map<String, Object> row, final String expected, final String identifier) {

        assertThat(expected.equals(row.get("tran_card_num")))
                .as("TRAN-CARD-NUM X(16) at bytes 263-278 of app/cpy/CVTRA05Y.cpy:L15 must survive the "
                        + "round trip for identifier %s. The value itself is a primary account number and "
                        + "is deliberately absent from this message", identifier)
                .isTrue();
    }

    /**
     * Asserts no loaded row violates any of the four keys the posted-transaction relation carries.
     *
     * <p>Three residual scans, each counting orphans and selecting no identifying value, so a dropped
     * constraint is caught without a card number reaching a message.
     */
    private void assertEveryForeignKeySatisfied() {
        assertThat(committedCount(orphanCardQuery))
                .as("every loaded row must name a card that exists: the obligation of "
                        + "fk04_transaction_card")
                .isZero();
        assertThat(committedCount(orphanTypeQuery))
                .as("and a transaction type that exists: fk05_transaction_type. Note the column names are "
                        + "asymmetric - the referencing column is the type code while the referenced column "
                        + "is the type, which is how app/cpy/CVTRA03Y.cpy declares it")
                .isZero();
        assertThat(committedCount(orphanCategoryQuery))
                .as("and a type-and-category pair that exists: fk06_transaction_category")
                .isZero();
    }

    /**
     * Issues one constant counting query and answers with its single number.
     *
     * @param query a constant query carrying no parameter
     * @return the count
     */
    private long committedCount(final String query) {
        final Long value = jdbcTemplate.queryForObject(query, Long.class);
        assertThat(value)
                .as("a counting query answers with a number; a null here means the query shape changed")
                .isNotNull();
        return value.longValue();
    }

    /**
     * Finds the typed duplicate among a failed run's reported exceptions.
     *
     * <p>Each reported throwable's cause chain is walked, because a framework may wrap what a step threw,
     * and the walk is depth bounded so a self-referencing cause cannot spin.
     *
     * @param execution the failed execution
     * @return the duplicate the load reported, never {@code null}
     */
    private DuplicateRecordException duplicateFailure(final JobExecution execution) {
        DuplicateRecordException found = null;
        for (final Throwable reported : execution.getAllFailureExceptions()) {
            Throwable candidate = reported;
            for (int depth = 0; candidate != null && depth < 32; depth++) {
                if (found == null && candidate instanceof DuplicateRecordException duplicate) {
                    found = duplicate;
                }
                candidate = candidate.getCause();
            }
        }
        assertThat(found)
                .as("a repeated identifier must surface as the migration's typed duplicate. Any other "
                        + "outcome - an absorbed conflict, an untyped store error, or no failure at all - "
                        + "means the load of app/jcl/COMBTRAN.jcl:L48 no longer reproduces a REPRO into a "
                        + "keyed cluster")
                .isNotNull();
        return found;
    }

    /**
     * Extracts the generation-ambiguity failure from an execution, failing the test when none is present.
     *
     * <p>The cause chain is walked rather than the top-level exception inspected, because the refusal is
     * raised inside the reader's {@code open} and reaches the execution wrapped by the step's own reporting.
     *
     * @param execution the failed execution
     * @return the reported {@link DataIntegrityException}, never {@code null}
     */
    private DataIntegrityException generationAmbiguityFailure(final JobExecution execution) {
        DataIntegrityException found = null;
        for (final Throwable reported : execution.getAllFailureExceptions()) {
            Throwable candidate = reported;
            for (int depth = 0; candidate != null && depth < 32; depth++) {
                if (found == null && candidate instanceof DataIntegrityException ambiguity) {
                    found = ambiguity;
                }
                candidate = candidate.getCause();
            }
        }
        assertThat(found)
                .as("an ambiguous generation must surface as the migration's typed data-integrity failure. "
                        + "Any other outcome - a warning, a silently chosen object, or no failure at all - "
                        + "means a GDG generation is no longer being treated as one dataset")
                .isNotNull();
        return found;
    }

    /**
     * Snapshots the identifiers of this process's direct children.
     *
     * @return the child process identifiers, never {@code null} and mutable so a caller can difference it
     */
    private Set<Long> childProcessIds() {
        final Set<Long> identifiers = new HashSet<>();
        ProcessHandle.current().children().forEach(child -> identifiers.add(Long.valueOf(child.pid())));
        return identifiers;
    }
}
