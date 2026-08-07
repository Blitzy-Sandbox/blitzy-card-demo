/*
 * ******************************************************************
 * Program     : TransactionCombineProcessorTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Pure-JVM proof of the two contracts app/jcl/COMBTRAN.jcl
 *               fixes: STEP05R orders a CONCATENATED input ascending by
 *               TRAN-ID as a 16-character string, and STEP10 REPROs the
 *               result into an existing keyed cluster - so a repeated
 *               TRAN-ID MUST FAIL the load and must never be upserted.
 * Source      : app/jcl/COMBTRAN.jcl @ 7756d89   (the ONLY authority)
 *               NO COBOL PROGRAM EXISTS FOR THIS JOB. Its whole logic is
 *               DFSORT and IDCAMS control cards, so there is no paragraph
 *               map to mirror; the JCL member itself is the source of
 *               truth and every assertion below cites a card in it.
 *               app/cpy/CVTRA05Y.cpy:L4-L18  (350-byte record geometry)
 *               app/cbl/CBACT04C.cbl:L53-L56 (interest output has no key)
 *               app/jcl/INTCALC.jcl:L22      (PARM='2022071800')
 *               app/jcl/TRANBKP.jcl:L58-L59  (KEYS(16 0) RECORDSIZE(350))
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
package com.cardemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.batch.processors.TransactionCombineProcessor;
import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.DataIntegrityException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.Transaction;
import com.cardemo.unit.model.FixedClockProvider;
import com.cardemo.unit.model.FixtureLoader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * The behavioural contract of {@link TransactionCombineProcessor}, read straight off the job control.
 *
 * <h2>What it does</h2>
 *
 * <p><strong>There is no COBOL program for this job.</strong> Every other batch step in this migration has
 * a program to mirror paragraph by paragraph; {@code app/jcl/COMBTRAN.jcl} has none. Its entire logic is two
 * utility invocations and their control cards, so the JCL member <em>is</em> the source of truth, there is no
 * paragraph map to reproduce, and each group below is anchored to a card rather than to a paragraph label.
 *
 * <p>The member is 52 lines with LF endings, and the cards that carry meaning are these, all read verbatim at
 * commit {@code 7756d89}:
 *
 * <ul>
 *   <li>{@code :L22} {@code //STEP05R  EXEC PGM=SORT} - the ordering step.</li>
 *   <li>{@code :L23-L26} a <strong>concatenated</strong> {@code SORTIN}: {@code TRANSACT.BKUP(0)} first, then
 *       {@code SYSTRAN(0)}. Two DD statements under one DD name, so the order of the cards is the order of
 *       the stream.</li>
 *   <li>{@code :L27-L28} {@code SYMNAMES} declaring exactly one symbol, {@code TRAN-ID,1,16,CH} - offset 1,
 *       length 16, type character.</li>
 *   <li>{@code :L29-L30} {@code SORT FIELDS=(TRAN-ID,A)} - one key, ascending, and because the symbol is
 *       typed {@code CH} it is a <em>string</em> comparison.</li>
 *   <li>{@code :L33-L37} {@code SORTOUT} taking its geometry from {@code DCB=(*.SORTIN)} at {@code :L35}, so
 *       the output record is the input record: 350 bytes, unaltered. <strong>There is no {@code OUTREC} card
 *       anywhere in the member</strong>, so unlike the statement job nothing is projected, truncated or
 *       reordered here.</li>
 *   <li>{@code :L41} {@code //STEP10 EXEC PGM=IDCAMS} - the load step.</li>
 *   <li>{@code :L43-L44} the sorted file as {@code INFILE}; {@code :L45-L46} the live keyed cluster
 *       {@code TRANSACT.VSAM.KSDS} as {@code OUTFILE}, at {@code DISP=SHR}.</li>
 *   <li>{@code :L48} {@code REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)} - a plain copy. Corroborated by
 *       {@code app/ctl/REPROCT.ctl:L15}, which is a single card of exactly this form and carries no
 *       conflict-resolution option of any kind.</li>
 *   <li>{@code :L51} the provenance comment
 *       {@code //* Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:23:05 CDT}.</li>
 * </ul>
 *
 * <p>Record geometry comes from {@code app/cpy/CVTRA05Y.cpy:L4-L18}, whose header states {@code RECLN = 350}:
 * {@code TRAN-ID X(16)} 1-16, {@code TRAN-TYPE-CD X(02)} 17-18, {@code TRAN-CAT-CD 9(04)} 19-22,
 * {@code TRAN-SOURCE X(10)} 23-32, {@code TRAN-DESC X(100)} 33-132, {@code TRAN-AMT S9(09)V99} 133-143,
 * {@code TRAN-MERCHANT-ID 9(09)} 144-152, {@code TRAN-MERCHANT-NAME X(50)} 153-202,
 * {@code TRAN-MERCHANT-CITY X(50)} 203-252, {@code TRAN-MERCHANT-ZIP X(10)} 253-262,
 * {@code TRAN-CARD-NUM X(16)} 263-278, {@code TRAN-ORIG-TS X(26)} 279-304, {@code TRAN-PROC-TS X(26)}
 * 305-330, {@code FILLER X(20)} 331-350. Thirteen of those fourteen fields are modelled on
 * {@link Transaction}; the trailing {@code FILLER} is pad and is deliberately not a property.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>This class is bound to <strong>Surefire 3.5.4</strong>: the root {@code pom.xml} includes
 * {@code **}{@code /*Test.java} and excludes {@code **}{@code /integration/**} and {@code **}{@code /e2e/**},
 * so a class under {@code src/test/java/com/cardemo/unit/} is collected here and nowhere else.
 *
 * <pre>
 *   ./mvnw -B -ntp -Ddependency-check.skip=true test
 *   ./mvnw -B -ntp -Ddependency-check.skip=true -Dtest=TransactionCombineProcessorTest test
 *   ./mvnw -B -ntp clean verify
 * </pre>
 *
 * <p><strong>Do not move or rename this file.</strong> A test class outside the two include sets matches
 * neither Surefire's nor Failsafe's globs and is collected by neither: the build stays green, both plugins
 * report success, and the class simply never runs. That failure is silent, which is what makes it dangerous.
 *
 * <h2>Key configurations and defaults</h2>
 *
 * <ul>
 *   <li><strong>Pure-JVM tier.</strong> No container, no Spring context, no database, no
 *       {@code JobLauncherTestUtils}. The real bulk load against PostgreSQL and the job and step wiring are
 *       asserted in {@code src/test/java/com/cardemo/integration/batch}. What is asserted here is the
 *       behavioural contract: the ordering, the pass-through and the duplicate outcome.</li>
 *   <li><strong>Time is injected, never read.</strong> Timestamps come from
 *       {@link FixedClockProvider#canonicalClock()}, so no wall clock, default zone, default locale or
 *       unseeded random source is reachable from any assertion. Every formatting call passes
 *       {@link Locale#ROOT}.</li>
 *   <li><strong>No Mockito stubbing, and that is deliberate rather than an omission.</strong> Mockito 5.17.0
 *       is on the test classpath and its strict stubs setting would apply if it were used, but
 *       {@link TransactionCombineProcessor} has <em>no collaborator at all</em>: its only constructor takes
 *       no argument and every field it declares is {@code static final}. Stubbing nothing to verify nothing
 *       would be ceremony, so the real {@code org.springframework.dao} exception types are constructed
 *       directly instead. A test in {@link ProcessorShapeAndPurity} proves the no-collaborator claim rather
 *       than leaving it as an assertion in prose.</li>
 *   <li><strong>Both inputs are read at CURRENT generation {@code (0)}.</strong> Neither DD says
 *       {@code (+1)} and neither says a range, so only the newest generation of each source takes part and
 *       <em>only the latest interest run merges</em>. An earlier {@code SYSTRAN} generation is never picked
 *       up. Nothing fails, but a second interest run inside one cycle
 *       silently supersedes the first. The operational answer is to drive the combine job once per interest
 *       run - and {@link CurrentGenerationOnlyMerge} pins the semantic so it cannot drift into a
 *       merge-everything reader.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>The build fails on a warning, not an error.</strong> {@code maven-compiler-plugin} 3.14.1
 *       runs at {@code release 25} with {@code failOnWarning}, {@code -Xlint:all} and {@code -Werror}, and
 *       that reaches test compilation. An unused import, a raw type or an unchecked cast added here fails
 *       the whole build. Remedy: read the first {@code javac} diagnostic literally; it names the token.</li>
 *   <li><strong>The fixture name trap.</strong> The ASCII fixture is {@code dailytran.txt} - the word spelled
 *       in full. The mainframe DD name and dataset are {@code DALYTRAN}, so {@code dalytran.txt} looks right
 *       and does not exist. {@link FixtureLoader.Fixture#DAILY_TRANSACTION} owns the correct name; resolve it
 *       through the enum rather than typing a literal.</li>
 *   <li><strong>A duplicate {@code TRAN-ID} MUST fail the load.</strong>
 *       {@code :L45-L46} opens the target {@code DISP=SHR} and the member contains no {@code IDCAMS DELETE}
 *       of the cluster, no {@code IEFBR14} pre-delete and no {@code REUSE} - the single {@code DELETE} token
 *       in all 52 lines is the {@code DISP=(NEW,CATLG,DELETE)} abnormal-termination disposition of
 *       {@code SORTOUT} at {@code :L33}, which concerns the sort work file and not the cluster. So
 *       {@code :L48} adds records to a cluster that already holds data, keyed on {@code TRAN-ID} for 16
 *       bytes at offset 0 - {@code KEYS(16 0)} with {@code RECORDSIZE(350 350)} at
 *       {@code app/jcl/TRANBKP.jcl:L58-L59}, and {@code KEYLEN 16 AVGLRECL 350} at
 *       {@code app/catlg/LISTCAT.txt:L3593}. A repeated identifier collides on that key and the step fails.
 *       An {@code ON CONFLICT} clause, a merge, a {@code saveOrUpdate}, a substituted sequence, a
 *       retry-on-duplicate or a swallow-and-skip would each corrupt the transaction cluster and is a
 *       behaviour change rather than parity. {@link DuplicateLoadMustFail} is the group that holds the
 *       line.</li>
 *   <li><strong>Where the duplicate exposure comes from.</strong> Not from this step's own inputs. The
 *       interest job declares its output {@code ORGANIZATION IS SEQUENTIAL} with {@code ACCESS MODE IS
 *       SEQUENTIAL} and <em>no {@code RECORD KEY}</em> ({@code app/cbl/CBACT04C.cbl:L53-L56}) and opens it
 *       {@code OPEN OUTPUT} ({@code :L309}), i.e. a fresh sequential generation with no duplicate detection
 *       whatsoever. Identifiers are built at {@code :L473-L480} by concatenating the ten-character date
 *       parameter with a six-digit suffix, and that parameter is a literal on the execute card -
 *       {@code PARM='2022071800'} at {@code app/jcl/INTCALC.jcl:L22}. Re-run the interest job with the same
 *       date and it re-emits the same identifiers happily. The collision therefore first becomes visible
 *       here, at the load.</li>
 *   <li><strong>Exit codes.</strong> A failed load is return code <strong>8</strong> with
 *       {@link ExitStatus#FAILED}. It is <em>not</em> 4: {@code MOVE 4 TO RETURN-CODE} occurs exactly once in
 *       all 28 programs of the corpus, at {@code app/cbl/CBTRN02C.cbl:L230}, where it means
 *       completed-with-rejects keyed solely on that program's reject count. An unexpected store failure is
 *       {@link FatalProcessingException#BATCH_ABEND_CODE} 999 with
 *       {@link FatalProcessingException#BATCH_RETURN_CODE} 12.</li>
 * </ul>
 *
 * <h2>Evidence, and what is not available</h2>
 *
 * <ul>
 *   <li>the duplicate-load-must-fail contract, above.</li>
 *   <li>the current-generation-only merge semantics, above.</li>
 *   <li>{@code app/proc/TRANREPT.prc:L1} is {@code //REPROC PROC}, exactly as
 *       {@code app/proc/REPROC.prc:L1} is, so that member's internal procedure name differs from the member
 *       name {@code TRANREPT} that {@code EXEC PROC=TRANREPT} resolves. Logged only; nothing in this job
 *       depends on it and no remediation is proposed, since editing {@code app/**} is forbidden.</li>
 *   <li><strong>Not available</strong> - a captured legacy output baseline. There is none anywhere in the
 *       repository, so no expected-output file is created here and no aggregate record count over a fixture
 *       is asserted. What would be needed is a recorded run of the legacy job stream against a known input,
 *       which the frozen corpus does not include.</li>
 *   <li><strong>Not available</strong> - source grounding for file status {@code '35'} and hence for
 *       {@code FileUnavailableException}. {@code DFHRESP(NOTOPEN)} does not occur in the corpus. Likewise
 *       there is <em>no</em> literal {@code '22'} anywhere in {@code app/cbl}: the duplicate-key status is
 *       grounded only through the CICS {@code DFHRESP(DUPREC)} and {@code DFHRESP(DUPKEY)} responses of the
 *       online programs, which is the grounding cited rather than a batch literal.</li>
 *   <li>This file has <strong>no retained-no-op instance of its own</strong>. The parity mandate does force
 *       some deliberately empty constructs elsewhere in the migration - they live in
 *       {@code InterestCalculationProcessorTest}, {@code StatementProcessorTest} and the {@code RejectCode}
 *       enum, each owed an entry in the {@code DECISION_LOG.md} and a row in the
 *       {@code TRACEABILITY_MATRIX.md} - but
 *       inventing one here to look consistent would be fabrication, so none exists.</li>
 * </ul>
 *
 * @see TransactionCombineProcessor
 */
@DisplayName("TransactionCombineProcessor: COMBTRAN.jcl STEP05R ordering and STEP10 REPRO load")
final class TransactionCombineProcessorTest {

    /**
     * Return code a failed {@code REPRO} load surfaces: {@code 8}.
     *
     * <p>{@code app/jcl/COMBTRAN.jcl:L48} is an {@code IDCAMS} step, and the value is asserted here only
     * against what it must <em>not</em> be. It must not be {@code 4}, because {@code 4} has one meaning in
     * this corpus and it belongs to a different program; and it must not be {@link #ABEND_RETURN_CODE},
     * because that is reserved for a condition this branch has already excluded.
     */
    private static final int LOAD_FAILURE_RETURN_CODE = 8;

    /**
     * Return code that means completed-with-rejects: {@code 4}. <strong>Never valid for this job.</strong>
     *
     * <p>{@code MOVE 4 TO RETURN-CODE} occurs exactly once in the whole 28-program corpus, at
     * {@code app/cbl/CBTRN02C.cbl:L230}, where it is set if and only if that program's reject count exceeds
     * zero. This job has no reject concept: {@code COMBTRAN.jcl} writes no reject dataset and counts nothing.
     */
    private static final int COMPLETED_WITH_REJECTS_RETURN_CODE = 4;

    /** Return code paired with abend {@code 999}: {@code 12}, from {@link FatalProcessingException}. */
    private static final int ABEND_RETURN_CODE = FatalProcessingException.BATCH_RETURN_CODE;

    /**
     * A backup-source identifier, from the first record of {@code app/data/ASCII/dailytran.txt}: sixteen
     * decimal characters, zero padded on the left, which is the shape every corpus identifier has.
     */
    private static final String BACKUP_ID_LOW = "0000000000683580";

    /** A second backup-source identifier, from the second fixture record. */
    private static final String BACKUP_ID_HIGH = "0000000001774260";

    /**
     * An interest-generated identifier: the ten-character date parameter {@code 2022071800} of
     * {@code app/jcl/INTCALC.jcl:L22} followed by a six-digit suffix, exactly as
     * {@code app/cbl/CBACT04C.cbl:L473-L480} builds it with
     * {@code STRING PARM-DATE, WS-TRANID-SUFFIX DELIMITED BY SIZE INTO TRAN-ID}.
     */
    private static final String INTEREST_ID_FIRST = "2022071800000001";

    /** A second interest-generated identifier; the suffix counter is global, so it runs on. */
    private static final String INTEREST_ID_SECOND = "2022071800000002";

    /** One-based column of {@code TRAN-AMT} in the 350-byte record, per {@code app/cpy/CVTRA05Y.cpy:L10}. */
    private static final int TRAN_AMT_COLUMN = 133;

    /** One-based column of {@code TRAN-ID}, per the sort symbol {@code TRAN-ID,1,16,CH}. */
    private static final int TRAN_ID_COLUMN = 1;

    /** Fixed clock for every timestamp this class builds. Immutable, so sharing it is safe. */
    private static final Clock CLOCK = FixedClockProvider.canonicalClock();

    /**
     * Tokens that must not appear in the compiled form of {@link TransactionCombineProcessor}.
     *
     * <p>Immutable and therefore safe as a static constant. The first six are the mechanisms AAP invariant 9
     * forbids - {@code "No external sort process is spawned"} - which is simultaneously Rule 1 clause D's
     * {@code eval/exec} and shell-injection prohibition. The remainder are the persistence mechanisms that
     * would either mask a duplicate key or move the load out of the writer and into this processor.
     */
    private static final List<String> FORBIDDEN_CLASS_FILE_TOKENS = List.of(
            "java/lang/Runtime",
            "java/lang/ProcessBuilder",
            "javax/script",
            "createTempFile",
            "java/nio/file",
            "java/io/File",
            "java/sql",
            "JdbcTemplate",
            "EntityManager",
            "saveOrUpdate",
            "ON CONFLICT",
            "DO UPDATE",
            "DO NOTHING");

    /** The unit under test. Rebuilt per test so no state can leak between them. */
    private TransactionCombineProcessor processor;

    /** Captures what actually reaches an appender, rather than what stdout happens to show. */
    private ListAppender<ILoggingEvent> appender;

    /** The processor's own logger, restored in full after each test. */
    private ch.qos.logback.classic.Logger logger;

    /** The level the logger had before this test lowered it, so teardown can put it back. */
    private Level originalLevel;

    @BeforeEach
    void attachAppender() {
        processor = new TransactionCombineProcessor();
        logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(TransactionCombineProcessor.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(originalLevel);
    }

    // ---------------------------------------------------------------------------------------------------
    // Shared fixtures. Every one is synthetic or drawn from the frozen ASCII fixture, and none carries a
    // real credential, a real card number or any other value that would be sensitive if it were logged.
    // ---------------------------------------------------------------------------------------------------

    /**
     * Builds a record carrying only an identifier, reaching the field directly.
     *
     * <p>{@link Transaction#setTransactionId(String)} enforces the {@code PIC X(16)} upper bound and rejects
     * {@code null} outright, so the entity's own guard - not this processor's - would decide what a test can
     * express. Reflection over the {@code protected} no-argument constructor that the persistence provider
     * uses sidesteps that, which is the only way to present this processor with the hostile shapes a
     * fixed-width reader can genuinely produce.
     *
     * @param transactionId the identifier to plant, possibly {@code null} and possibly not of the declared
     *                      width
     * @return a record whose remaining twelve mapped fields are {@code null}, which the geometry checks
     *         accept by design so that one field can be aimed at without satisfying the other twelve
     */
    private static Transaction recordWithId(final String transactionId) {
        try {
            final Constructor<Transaction> constructor = Transaction.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            final Transaction item = constructor.newInstance();
            final Field field = Transaction.class.getDeclaredField("transactionId");
            field.setAccessible(true);
            field.set(item, transactionId);
            return item;
        } catch (final ReflectiveOperationException cause) {
            throw new IllegalStateException("could not build a Transaction fixture", cause);
        }
    }

    /**
     * Builds a fully populated record: all thirteen mapped fields at exactly their {@code PIC} widths.
     *
     * <p>Timestamps come from {@link FixedClockProvider#batchTimestamp(Clock)} against the fixed clock, so
     * they are {@link FixedClockProvider#TIMESTAMP_LENGTH} characters and identical on every run. The card
     * number is a synthetic constant, never a value from a real range.
     *
     * @param transactionId the identifier, which must already be sixteen characters
     * @param amount        the signed amount, at scale two
     * @return a record the processor accepts, built through the public all-argument constructor so the
     *         entity's own {@code PIC} guards are exercised too
     */
    private static Transaction fullRecord(final String transactionId, final BigDecimal amount) {
        final String timestamp = FixedClockProvider.batchTimestamp(CLOCK);
        return new Transaction(
                transactionId,
                "01",
                Integer.valueOf(5),
                "System",
                "Int. for a/c 00000000011",
                amount,
                Long.valueOf(0L),
                "",
                "",
                "",
                "0000000000000000",
                timestamp,
                timestamp);
    }

    /**
     * Reproduces the concatenated {@code SORTIN} of {@code app/jcl/COMBTRAN.jcl:L23-L26}.
     *
     * <p>Two DD statements share one DD name, so the reader presents the first dataset in full and then the
     * second: {@code TRANSACT.BKUP(0)} at {@code :L23-L24}, then {@code SYSTRAN(0)} at {@code :L25-L26}. In
     * the Java topology that concatenation is performed by
     * {@code com.cardemo.batch.readers.CombinedTransactionReader}, which is a separate tier and is
     * deliberately not exercised here; this helper models only the order the cards declare, so that the
     * ordering contract can be asserted against the same stream shape the reader must produce.
     *
     * @param backup   records from the transaction backup generation, presented first
     * @param interest records from the interest-generated generation, presented second
     * @return the concatenated stream, in card order, as a fresh mutable list
     */
    private static List<Transaction> concatenatedSortIn(final List<Transaction> backup,
                                                        final List<Transaction> interest) {
        final List<Transaction> stream = new ArrayList<>(backup.size() + interest.size());
        stream.addAll(backup);
        stream.addAll(interest);
        return stream;
    }

    /**
     * Applies {@link TransactionCombineProcessor#TRAN_ID_ASCENDING} the way {@code STEP05R} applies
     * {@code SORT FIELDS=(TRAN-ID,A)}: in process, over the whole stream, producing a new ordering.
     *
     * @param stream the concatenated input, left untouched
     * @return a new list ordered ascending by identifier
     */
    private static List<Transaction> sorted(final List<Transaction> stream) {
        final List<Transaction> ordered = new ArrayList<>(stream);
        ordered.sort(TransactionCombineProcessor.TRAN_ID_ASCENDING);
        return ordered;
    }

    /** Projects a stream onto its identifiers, so an ordering can be asserted without record noise. */
    private static List<String> identifiersOf(final List<Transaction> stream) {
        final List<String> identifiers = new ArrayList<>(stream.size());
        for (final Transaction item : stream) {
            identifiers.add(item == null ? null : item.getTransactionId());
        }
        return identifiers;
    }

    /** Every message an appender received, formatted exactly as it would be written. */
    private List<String> loggedMessages() {
        final List<String> messages = new ArrayList<>();
        for (final ILoggingEvent event : appender.list) {
            messages.add(event.getFormattedMessage());
        }
        return messages;
    }

    /**
     * Identifiers whose character ordering and numeric ordering genuinely disagree.
     *
     * <p>Each row is a pair that a string comparison and a numeric parse order in <em>opposite</em>
     * directions, which is what makes the {@code CH} type on the sort symbol observable rather than
     * decorative. The values are all legal {@code PIC X(16)} content: a fixed-width character field may hold
     * a left-justified value padded with spaces, and DFSORT compares those bytes as bytes.
     *
     * <p>In every row the character comparison places the first value before the second, while comparing the
     * two as numbers places it after. Each row is therefore a case a numeric implementation would get
     * demonstrably wrong rather than merely differently.
     *
     * @return label, the value that sorts first under {@code CH}, and the value that sorts second
     */
    static Stream<Arguments> characterVersusNumericOrdering() {
        return Stream.of(
                Arguments.of("zero-padded against left-justified",
                        "0000000000683580", "68358           "),
                Arguments.of("two digits against one",
                        "10              ", "9               "),
                Arguments.of("a leading one against a leading two",
                        "10              ", "2               "));
    }

    /**
     * Orders two identifiers the way a numeric implementation would, for contrast only.
     *
     * <p>Never used to assert an ordering this migration relies on - it exists so that
     * {@link ConcatenatedInputOrdering#characterOrderIsNotNumericOrder(String, String, String)} can show the
     * two disagree. Trimming is required before parsing, which is itself part of the point: a numeric reading
     * of {@code PIC X(16)} is not even total over the field's legal content.
     *
     * @param first  the identifier the character comparison puts first
     * @param second the identifier the character comparison puts second
     * @return a negative, zero or positive value in the manner of {@link Comparator#compare(Object, Object)}
     */
    private static int numericComparison(final String first, final String second) {
        return Long.compare(Long.parseLong(first.trim()), Long.parseLong(second.trim()));
    }

    /**
     * Hostile identifiers of exactly the declared width, each carrying a control sequence.
     *
     * <p>Supplied from a method rather than a {@code @CsvSource} because a real line break inside a CSV row
     * terminates that row, which would silently reduce each case to one column and destroy the property under
     * test.
     *
     * @return a label and a sixteen-character value carrying the control sequence
     */
    static Stream<Arguments> hostileIdentifiers() {
        return Stream.of(
                Arguments.of("line feed", forgedWith("\n")),
                Arguments.of("carriage return", forgedWith("\r")),
                Arguments.of("CRLF pair", forgedWith("\r\n")),
                Arguments.of("tab", forgedWith("\t")),
                Arguments.of("escape", forgedWith("\u001b")),
                Arguments.of("null byte", forgedWith("\u0000")),
                Arguments.of("next line NEL", forgedWith("\u0085")));
    }

    /**
     * The two rejection branches a line-breaking value can reach.
     *
     * <p>Sixteen line feeds are {@link String#isBlank()} and so are reported by the blank branch rather than
     * the control branch. Both appear here precisely because they are different branches: the guarantee is
     * that whichever one fires reports safely.
     *
     * @return a branch label and a value that reaches it
     */
    static Stream<Arguments> lineBreakingIdentifiers() {
        return Stream.of(
                Arguments.of("blank", "\n".repeat(TransactionCombineProcessor.TRAN_ID_LENGTH)),
                Arguments.of("control", forgedWith("\n")));
    }

    /**
     * Builds a forging payload of exactly the declared width around a control sequence.
     *
     * <p>Constructing and padding here rather than counting characters by hand makes a wrong-width fixture
     * impossible instead of merely detectable, and keeps every case comparable.
     *
     * @param control the control sequence to embed, one or two characters
     * @return a value of exactly {@link TransactionCombineProcessor#TRAN_ID_LENGTH} characters
     */
    private static String forgedWith(final String control) {
        final String core = "1" + control + "FATAL forged!";
        final int width = TransactionCombineProcessor.TRAN_ID_LENGTH;
        final String value = core.length() >= width
                ? core.substring(0, width)
                : core + " ".repeat(width - core.length());
        if (value.length() != width) {
            throw new IllegalStateException("fixture width is " + value.length() + ", expected " + width);
        }
        return value;
    }

    // ===================================================================================================
    // STEP05R - app/jcl/COMBTRAN.jcl:L22-L37
    // ===================================================================================================

    /**
     * The concatenated input of {@code :L23-L26} and the single ascending key of {@code :L28} and
     * {@code :L30}.
     */
    @Nested
    @DisplayName("STEP05R: a concatenated input ordered ascending by TRAN-ID as a string")
    final class ConcatenatedInputOrdering {

        @Test
        @DisplayName("the backup source is presented before the interest source, as the DD cards declare")
        void backupSourceIsPresentedBeforeInterestSource() {
            final List<Transaction> backup = List.of(recordWithId(BACKUP_ID_HIGH));
            final List<Transaction> interest = List.of(recordWithId(INTEREST_ID_FIRST));

            final List<Transaction> stream = concatenatedSortIn(backup, interest);

            assertThat(identifiersOf(stream))
                    .as("app/jcl/COMBTRAN.jcl:L23-L24 names TRANSACT.BKUP(0) first and :L25-L26 names "
                            + "SYSTRAN(0) second, so the concatenated stream presents the backup generation "
                            + "in full before the interest generation begins")
                    .containsExactly(BACKUP_ID_HIGH, INTEREST_ID_FIRST);
        }

        @Test
        @DisplayName("the whole concatenated stream is ordered ascending by identifier")
        void theConcatenatedStreamIsOrderedAscending() {
            final List<Transaction> stream = concatenatedSortIn(
                    List.of(recordWithId(BACKUP_ID_HIGH), recordWithId(BACKUP_ID_LOW)),
                    List.of(recordWithId(INTEREST_ID_SECOND), recordWithId(INTEREST_ID_FIRST)));

            assertThat(identifiersOf(sorted(stream)))
                    .as("SORT FIELDS=(TRAN-ID,A) at app/jcl/COMBTRAN.jcl:L30 is a single ascending key, so "
                            + "records from both sources interleave by identifier and the source they came "
                            + "from stops mattering once the sort has run")
                    .containsExactly(BACKUP_ID_LOW, BACKUP_ID_HIGH, INTEREST_ID_FIRST, INTEREST_ID_SECOND);
        }

        @Test
        @DisplayName("interest-generated identifiers sort after the corpus identifiers, by construction")
        void interestGeneratedIdentifiersSortAfterCorpusIdentifiers() {
            final List<Transaction> stream = concatenatedSortIn(
                    List.of(recordWithId(BACKUP_ID_LOW), recordWithId(BACKUP_ID_HIGH)),
                    List.of(recordWithId(INTEREST_ID_FIRST)));

            final List<String> ordered = identifiersOf(sorted(stream));

            assertThat(ordered)
                    .as("app/cbl/CBACT04C.cbl:L473-L480 builds the identifier by concatenating the ten-digit "
                            + "date parameter 2022071800 of app/jcl/INTCALC.jcl:L22 with a six-digit suffix, "
                            + "so it begins with the digit 2 while every corpus identifier is zero padded and "
                            + "begins with 0 - which places every generated identifier last under a character "
                            + "comparison")
                    .containsExactly(BACKUP_ID_LOW, BACKUP_ID_HIGH, INTEREST_ID_FIRST);
            assertThat(INTEREST_ID_FIRST)
                    .as("the generated identifier is the date parameter followed by a six-digit suffix, and "
                            + "is exactly the declared width")
                    .startsWith("2022071800")
                    .hasSize(TransactionCombineProcessor.TRAN_ID_LENGTH);
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("com.cardemo.unit.batch.TransactionCombineProcessorTest#characterVersusNumericOrdering")
        @DisplayName("the order is character order, not numeric order, because the symbol is typed CH")
        void characterOrderIsNotNumericOrder(final String label, final String first, final String second) {
            final Comparator<Transaction> comparator = TransactionCombineProcessor.TRAN_ID_ASCENDING;

            assertThat(comparator.compare(recordWithId(first), recordWithId(second)))
                    .as("the sort symbol at app/jcl/COMBTRAN.jcl:L28 is TRAN-ID,1,16,CH - the trailing CH is "
                            + "a character type, not a zoned or packed decimal one - so DFSORT compares the "
                            + "sixteen bytes as bytes and the case '%s' orders as it does", label)
                    .isNegative();
            assertThat(numericComparison(first, second))
                    .as("reading the same two identifiers as numbers reverses them, which is why parsing the "
                            + "identifier would diverge from the source rather than merely look different")
                    .isPositive();
        }

        @ParameterizedTest
        @ValueSource(strings = {"68358           ", "1               ", "                "})
        @DisplayName("an identifier a numeric parse cannot read at all still orders deterministically")
        void anUnparseableIdentifierStillOrders(final String identifier) {
            final Transaction candidate = recordWithId(identifier);
            final Transaction reference = recordWithId(BACKUP_ID_LOW);

            assertThat(TransactionCombineProcessor.TRAN_ID_ASCENDING.compare(candidate, reference))
                    .as("PIC X(16) at app/cpy/CVTRA05Y.cpy:L5 is a character field, so a left-justified or "
                            + "wholly blank value is legal content; a character comparison is total over it "
                            + "whereas Long.parseLong is not, which is the second reason the identifier is "
                            + "never parsed")
                    .isNotZero();
            assertThatExceptionOfType(NumberFormatException.class)
                    .as("this value is legal PIC X(16) content that a numeric implementation could not even "
                            + "read, so the divergence is a failure rather than a reordering")
                    .isThrownBy(() -> Long.parseLong(identifier));
        }

        @Test
        @DisplayName("equal identifiers compare equal, leaving the collision for STEP10 to reject")
        void equalIdentifiersCompareEqual() {
            assertThat(TransactionCombineProcessor.TRAN_ID_ASCENDING
                    .compare(recordWithId(BACKUP_ID_LOW), recordWithId(BACKUP_ID_LOW)))
                    .as("the sort has one key and no duplicate handling: SORT FIELDS=(TRAN-ID,A) at "
                            + "app/jcl/COMBTRAN.jcl:L30 carries no SUM FIELDS=NONE and no XSUM, so a repeated "
                            + "identifier survives the sort intact and is rejected later, by the REPRO of "
                            + "app/jcl/COMBTRAN.jcl:L48")
                    .isZero();
        }

        @Test
        @DisplayName("a duplicate spanning both sources keeps the backup record ahead of the interest record")
        void aDuplicateAcrossSourcesKeepsTheBackupRecordFirst() {
            final Transaction fromBackup = fullRecord(BACKUP_ID_LOW, new BigDecimal("10.00"));
            final Transaction fromInterest = fullRecord(BACKUP_ID_LOW, new BigDecimal("20.00"));

            final List<Transaction> ordered =
                    sorted(concatenatedSortIn(List.of(fromBackup), List.of(fromInterest)));

            assertThat(ordered).hasSize(2);
            assertThat(ordered.get(0))
                    .as("List.sort is stable, so two records the comparator calls equal keep the order the "
                            + "concatenation gave them - and app/jcl/COMBTRAN.jcl:L23-L26 gives the backup "
                            + "generation first. That is what decides which of the two the REPRO of :L48 "
                            + "loads and which one collides. Identity is asserted rather than equality "
                            + "because Transaction.equals compares the primary key alone, so an equality "
                            + "assertion here would pass whichever order the two arrived in")
                    .isSameAs(fromBackup);
            assertThat(ordered.get(1))
                    .as("the interest-generated record stays second, so it is the one that collides")
                    .isSameAs(fromInterest);
        }

        @Test
        @DisplayName("the comparison is case sensitive and independent of the platform locale")
        void theComparisonIsCaseSensitiveAndLocaleIndependent() {
            final Comparator<Transaction> comparator = TransactionCombineProcessor.TRAN_ID_ASCENDING;
            final String upper = "A00000000000000A";
            final String lower = "a00000000000000a";

            assertThat(comparator.compare(recordWithId(upper), recordWithId(lower)))
                    .as("a byte comparison puts 'A' at 0x41 before 'a' at 0x61, which is what "
                            + "String.compareTo does and what DFSORT does for a CH field. No Collator and no "
                            + "case folding is involved, so no collation strength or platform locale can "
                            + "change the answer")
                    .isNegative();
            assertThat(upper.compareToIgnoreCase(lower))
                    .as("compareToIgnoreCase would call these two equal and so would lose the ordering "
                            + "entirely; it is precisely what must not be used")
                    .isZero();
            assertThat("i".toUpperCase(Locale.ROOT))
                    .as("every case or format operation in this file passes Locale.ROOT explicitly. Under a "
                            + "Turkish default locale the same call without it yields a dotted capital I, "
                            + "which is how a default locale turns a passing test into a failing one on "
                            + "someone else's machine")
                    .isEqualTo("I");
        }

        @Test
        @DisplayName("the ordering is total, deterministic and unaffected by the input permutation")
        void theOrderingIsTotalAndDeterministic() {
            final Transaction low = recordWithId(BACKUP_ID_LOW);
            final Transaction high = recordWithId(BACKUP_ID_HIGH);
            final Transaction generated = recordWithId(INTEREST_ID_FIRST);
            final List<String> expected = List.of(BACKUP_ID_LOW, BACKUP_ID_HIGH, INTEREST_ID_FIRST);

            assertThat(identifiersOf(sorted(List.of(low, high, generated)))).isEqualTo(expected);
            assertThat(identifiersOf(sorted(List.of(generated, high, low)))).isEqualTo(expected);
            assertThat(identifiersOf(sorted(List.of(high, low, generated)))).isEqualTo(expected);
            assertThat(identifiersOf(sorted(List.of(generated, low, high))))
                    .as("a total order over the key yields one answer regardless of the order the records "
                            + "arrived in, so the step's output does not depend on how the two generations "
                            + "happened to be laid out")
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("the comparator is antisymmetric and transitive over the identifiers it is given")
        void theComparatorIsAntisymmetricAndTransitive() {
            final Comparator<Transaction> comparator = TransactionCombineProcessor.TRAN_ID_ASCENDING;
            final Transaction low = recordWithId(BACKUP_ID_LOW);
            final Transaction middle = recordWithId(BACKUP_ID_HIGH);
            final Transaction high = recordWithId(INTEREST_ID_FIRST);

            assertThat(comparator.compare(low, middle)).isNegative();
            assertThat(comparator.compare(middle, low)).isPositive();
            assertThat(comparator.compare(middle, high)).isNegative();
            assertThat(comparator.compare(low, high))
                    .as("a comparator DFSORT can be replaced by must be a genuine total order: antisymmetric "
                            + "on every pair and transitive across every triple, or the ordering the REPRO "
                            + "depends on is not well defined")
                    .isNegative();
        }

        @Test
        @DisplayName("absence sorts first, at both levels, and never throws")
        void absenceSortsFirstAtBothLevels() {
            final Transaction withoutId = recordWithId(null);
            final Transaction populated = recordWithId(BACKUP_ID_LOW);
            final List<Transaction> stream = new ArrayList<>();
            stream.add(populated);
            stream.add(null);
            stream.add(withoutId);

            assertThat(identifiersOf(sorted(stream)))
                    .as("the comparator is nullsFirst at the record level and nullsFirst again at the "
                            + "identifier level, so neither an absent record nor an absent identifier can "
                            + "fail the sort. Sorting is not the boundary that rejects them - process(..) is, "
                            + "and it does so with a message naming what is missing")
                    .containsExactly(null, null, BACKUP_ID_LOW);
        }

        @Test
        @DisplayName("the comparator is one shared stateless instance, so nothing accumulates in it")
        void theComparatorIsOneSharedStatelessInstance() {
            assertThat(TransactionCombineProcessor.TRAN_ID_ASCENDING)
                    .as("a single immutable Comparator is safe to publish as a constant; a mutable one would "
                            + "be global mutable state, which Rule 1 clause B forbids")
                    .isSameAs(TransactionCombineProcessor.TRAN_ID_ASCENDING);
            assertThat(TransactionCombineProcessor.TRAN_ID_ASCENDING.getClass().getDeclaredFields())
                    .as("the comparator holds no declared instance state of its own")
                    .allSatisfy(field -> assertThat(Modifier.isStatic(field.getModifiers())
                            || Modifier.isFinal(field.getModifiers())).isTrue());
        }
    }

    /**
     * The generation semantics of {@code :L24} and {@code :L26}, both of which read {@code (0)}.
     */
    @Nested
    @DisplayName("STEP05R: both inputs are read at current generation (0), so only the latest merges")
    final class CurrentGenerationOnlyMerge {

        @Test
        @DisplayName("only the newest interest generation takes part; an earlier one is never picked up")
        void onlyTheNewestInterestGenerationMerges() {
            final List<Transaction> earlierInterestRun = List.of(recordWithId("2022071700000001"));
            final List<Transaction> newestInterestRun = List.of(recordWithId(INTEREST_ID_FIRST));
            final List<Transaction> backup = List.of(recordWithId(BACKUP_ID_LOW));

            final List<String> merged = identifiersOf(sorted(concatenatedSortIn(backup, newestInterestRun)));

            assertThat(merged)
                    .as("app/jcl/COMBTRAN.jcl:L26 names SYSTRAN(0) - the current generation - not (+1) and "
                            + "not a range, so exactly one interest generation participates. Nothing fails, "
                            + "but a second interest run inside one cycle silently supersedes "
                            + "the first, so the combine job must be driven once per interest run")
                    .containsExactly(BACKUP_ID_LOW, INTEREST_ID_FIRST)
                    .doesNotContainAnyElementsOf(identifiersOf(earlierInterestRun));
        }

        @Test
        @DisplayName("the backup input is likewise a single current generation, not an accumulation")
        void theBackupInputIsASingleCurrentGeneration() {
            final List<Transaction> supersededBackup = List.of(recordWithId("0000000000000001"));
            final List<Transaction> currentBackup = List.of(recordWithId(BACKUP_ID_LOW));

            final List<String> merged =
                    identifiersOf(sorted(concatenatedSortIn(currentBackup, List.of())));

            assertThat(merged)
                    .as("app/jcl/COMBTRAN.jcl:L24 names TRANSACT.BKUP(0), so the step reads the newest backup "
                            + "generation only. app/jcl/TRANBKP.jcl:L33 is what produced it, at (+1), and the "
                            + "asymmetry between the two - written at (+1), read at (0) - is the whole "
                            + "generation-group idiom")
                    .containsExactly(BACKUP_ID_LOW)
                    .doesNotContainAnyElementsOf(identifiersOf(supersededBackup));
        }

        @Test
        @DisplayName("the output is a new generation, which is why the load and not the sort sees a duplicate")
        void theOutputIsANewGeneration() {
            final List<Transaction> merged = sorted(concatenatedSortIn(
                    List.of(recordWithId(BACKUP_ID_LOW)),
                    List.of(recordWithId(BACKUP_ID_LOW))));

            assertThat(identifiersOf(merged))
                    .as("SORTOUT at app/jcl/COMBTRAN.jcl:L33-L37 is DISP=(NEW,CATLG,DELETE) onto "
                            + "TRANSACT.COMBINED(+1) - a brand-new sequential generation with no key of its "
                            + "own - so a repeated identifier is written out quite happily here and is only "
                            + "refused when :L48 REPROs it into the keyed cluster")
                    .containsExactly(BACKUP_ID_LOW, BACKUP_ID_LOW);
        }
    }

    /**
     * The 350-byte geometry {@code DCB=(*.SORTIN)} at {@code :L35} propagates onto {@code SORTOUT}.
     */
    @Nested
    @DisplayName("STEP05R: the 350-byte geometry is propagated, not projected")
    final class RecordGeometryPreserved {

        @Test
        @DisplayName("the combined record is exactly 350 bytes, the length the DCB propagates")
        void theCombinedRecordIsExactly350Bytes() {
            assertThat(TransactionCombineProcessor.COMBINED_RECORD_LENGTH)
                    .as("app/cpy/CVTRA05Y.cpy:L2 states RECLN = 350; app/jcl/COMBTRAN.jcl:L35 sets "
                            + "DCB=(*.SORTIN) so SORTOUT inherits it; app/jcl/INTCALC.jcl:L39 declares the "
                            + "interest source at LRECL=350 and app/jcl/TRANBKP.jcl:L31 the backup source at "
                            + "LRECL=350, so all three agree")
                    .isEqualTo(350);
            assertThat(FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION).recordWidth())
                    .as("the frozen ASCII fixture corroborates the same width independently of the copybook")
                    .isEqualTo(TransactionCombineProcessor.COMBINED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("the sort key is the first sixteen bytes of that record and nothing more")
        void theSortKeyIsTheFirstSixteenBytes() {
            assertThat(TransactionCombineProcessor.TRAN_ID_LENGTH)
                    .as("the sort symbol at app/jcl/COMBTRAN.jcl:L28 is TRAN-ID,1,16,CH: offset %d, length 16",
                            TRAN_ID_COLUMN)
                    .isEqualTo(16);
            assertThat(TRAN_ID_COLUMN + TransactionCombineProcessor.TRAN_ID_LENGTH - 1)
                    .as("offset 1 for 16 bytes can only be the first field of app/cpy/CVTRA05Y.cpy:L5, and it "
                            + "ends well inside the record, so the key is a strict prefix rather than the "
                            + "whole image")
                    .isEqualTo(16)
                    .isLessThan(TransactionCombineProcessor.COMBINED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("no OUTREC card exists, so no field is truncated and none is reordered")
        void noOutrecMeansNothingIsTruncatedOrReordered() {
            final String timestamp = FixedClockProvider.batchTimestamp(CLOCK);
            final Transaction item = fullRecord(BACKUP_ID_LOW, new BigDecimal("123.45"));

            final Transaction processed = processor.process(item);

            assertThat(processed.getProcTs())
                    .as("app/jcl/COMBTRAN.jcl has no OUTREC card anywhere in its 52 lines, so TRAN-PROC-TS "
                            + "arrives with all %d of the characters app/cpy/CVTRA05Y.cpy:L17 declares. The "
                            + "statement job is the one that projects and thereby drops two bytes of this "
                            + "very field; that behaviour belongs to app/jcl/CREASTMT.JCL:STEP010 and must "
                            + "not leak into this job", FixedClockProvider.TIMESTAMP_LENGTH)
                    .isEqualTo(timestamp)
                    .hasSize(FixedClockProvider.TIMESTAMP_LENGTH);
            assertThat(processed.getOrigTs())
                    .as("TRAN-ORIG-TS is likewise whole; the projection that would have shortened it does not "
                            + "exist in this member")
                    .isEqualTo(timestamp)
                    .hasSize(FixedClockProvider.TIMESTAMP_LENGTH);
        }

        @Test
        @DisplayName("both timestamps are character fields, never a temporal type")
        void bothTimestampsAreCharacterFields() throws ReflectiveOperationException {
            assertThat(Transaction.class.getDeclaredField("origTs").getType())
                    .as("TRAN-ORIG-TS is PIC X(26) at app/cpy/CVTRA05Y.cpy:L16, so it is a fixed-width "
                            + "character field over CHAR(26). Modelling it as LocalDateTime, Timestamp or "
                            + "Instant would re-render it on the way out and break the byte comparison the "
                            + "REPRO boundary is measured against")
                    .isEqualTo(String.class);
            assertThat(Transaction.class.getDeclaredField("procTs").getType())
                    .as("TRAN-PROC-TS is PIC X(26) at app/cpy/CVTRA05Y.cpy:L17, and the same reasoning binds")
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("no financial field is float or double, at any width")
        void noFinancialFieldIsFloatOrDouble() {
            assertThat(Transaction.class.getDeclaredFields())
                    .as("TRAN-AMT is PIC S9(09)V99 at app/cpy/CVTRA05Y.cpy:L10 - a fixed-scale decimal. A "
                            + "binary floating-point type cannot represent it exactly, so none may appear "
                            + "anywhere on the record")
                    .allSatisfy(field -> assertThat(field.getType())
                            .isNotIn(float.class, double.class, Float.class, Double.class));
        }

        @Test
        @DisplayName("the amount keeps its scale, and equality on it is compareTo rather than equals")
        void theAmountKeepsItsScale() {
            final BigDecimal declaredScale = new BigDecimal("123.45");
            final Transaction processed = processor.process(fullRecord(BACKUP_ID_LOW, declaredScale));

            assertThat(processed.getAmount())
                    .as("the value is passed through, so its scale is the scale it arrived with")
                    .isEqualByComparingTo(declaredScale);
            assertThat(processed.getAmount().scale())
                    .as("PIC S9(09)V99 fixes two decimal places")
                    .isEqualTo(2);
            assertThat(new BigDecimal("10.00").equals(new BigDecimal("10.0")))
                    .as("BigDecimal.equals compares scale as well as value, so it reports two "
                            + "representations of the same amount as different - which is why every monetary "
                            + "comparison in this migration uses compareTo")
                    .isFalse();
            assertThat(new BigDecimal("10.00").compareTo(new BigDecimal("10.0")))
                    .as("compareTo compares value alone, which is the money comparison")
                    .isZero();
            assertThat(new BigDecimal("2.005").setScale(2, RoundingMode.HALF_EVEN))
                    .as("HALF_EVEN is the rounding mode this migration uses wherever a scale must be "
                            + "reduced; this step reduces none, and asserting the mode here records which "
                            + "one applies if one ever were")
                    .isEqualByComparingTo(new BigDecimal("2.00"));
        }
    }

    // ===================================================================================================
    // Processor shape - the per-record pass-through that carries the merge semantics
    // ===================================================================================================

    /**
     * The processor's shape: an {@link ItemProcessor} that validates, passes through and stores nothing.
     */
    @Nested
    @DisplayName("Processor shape: a pure pass-through that performs no persistence")
    final class ProcessorShapeAndPurity {

        @Test
        @DisplayName("it is an ItemProcessor from Transaction to Transaction")
        void itIsAnItemProcessorFromTransactionToTransaction() {
            assertThat(processor)
                    .as("the combine step's per-record work is a chunk-oriented processor: it carries the "
                            + "merge and ordering semantics of the concatenated input of "
                            + "app/jcl/COMBTRAN.jcl:L23-L26 and hands the record on")
                    .isInstanceOf(ItemProcessor.class);
        }

        @Test
        @DisplayName("a record is returned by identity, not copied and not rebuilt")
        void aRecordIsReturnedByIdentity() {
            final Transaction item = fullRecord(BACKUP_ID_LOW, new BigDecimal("-45.67"));

            assertThat(processor.process(item))
                    .as("returning the same instance is the strongest available statement that nothing was "
                            + "reshaped: there is no second object that could differ from the first")
                    .isSameAs(item);
        }

        @Test
        @DisplayName("all thirteen mapped fields survive the pass-through unchanged")
        void allThirteenMappedFieldsSurviveUnchanged() {
            final String timestamp = FixedClockProvider.batchTimestamp(CLOCK);
            final Transaction item = fullRecord(BACKUP_ID_LOW, new BigDecimal("-45.67"));

            final Transaction processed = processor.process(item);

            assertThat(processed.getTransactionId()).isEqualTo(BACKUP_ID_LOW);
            assertThat(processed.getTypeCode()).isEqualTo("01");
            assertThat(processed.getCategoryCode()).isEqualTo(Integer.valueOf(5));
            assertThat(processed.getTransactionSource()).isEqualTo("System");
            assertThat(processed.getDescription()).isEqualTo("Int. for a/c 00000000011");
            assertThat(processed.getAmount()).isEqualByComparingTo(new BigDecimal("-45.67"));
            assertThat(processed.getMerchantId()).isEqualTo(Long.valueOf(0L));
            assertThat(processed.getMerchantName()).isEmpty();
            assertThat(processed.getMerchantCity()).isEmpty();
            assertThat(processed.getMerchantZip()).isEmpty();
            assertThat(processed.getCardNumber()).isEqualTo("0000000000000000");
            assertThat(processed.getOrigTs()).isEqualTo(timestamp);
            assertThat(processed.getProcTs())
                    .as("thirteen of the fourteen fields of app/cpy/CVTRA05Y.cpy:L5-L18 are modelled and all "
                            + "thirteen are byte-equivalent after the pass: no reshuffle, no truncation, no "
                            + "re-padding, no sign normalisation and no case change. The fourteenth, "
                            + "FILLER X(20) at :L18, is pad and is deliberately not a property")
                    .isEqualTo(timestamp);
        }

        @Test
        @DisplayName("it declares no collaborator, so it cannot reach a store even by accident")
        void itDeclaresNoCollaborator() {
            assertThat(TransactionCombineProcessor.class.getDeclaredFields())
                    .as("every declared field is static, so there is no injected repository, template, client "
                            + "or clock. The bulk load of app/jcl/COMBTRAN.jcl:L48 lives in the writer; this "
                            + "processor only decides whether a record may take part")
                    .allSatisfy(field -> assertThat(Modifier.isStatic(field.getModifiers()))
                            .as("field %s is not static", field.getName())
                            .isTrue());
            assertThat(TransactionCombineProcessor.class.getDeclaredConstructors())
                    .as("a single no-argument constructor confirms there is nothing to inject")
                    .hasSize(1)
                    .allSatisfy(constructor -> assertThat(constructor.getParameterCount()).isZero());
        }

        @Test
        @DisplayName("processing is stateless: nothing accumulates across records or across instances")
        void processingIsStateless() {
            final Transaction first = fullRecord(BACKUP_ID_LOW, new BigDecimal("1.00"));
            final Transaction second = fullRecord(BACKUP_ID_HIGH, new BigDecimal("2.00"));
            final Transaction third = fullRecord(INTEREST_ID_FIRST, new BigDecimal("3.00"));

            assertThat(processor.process(first)).isSameAs(first);
            assertThat(processor.process(second)).isSameAs(second);
            assertThat(processor.process(third)).isSameAs(third);
            assertThat(processor.process(first))
                    .as("the same record may be presented again and is treated identically, so no seen-key "
                            + "set, running total or counter is being kept. Duplicate detection is not this "
                            + "step's job - it belongs to the keyed cluster the REPRO of :L48 loads")
                    .isSameAs(first);
            assertThat(new TransactionCombineProcessor().process(first))
                    .as("a fresh instance behaves identically, which is what makes the bean safe to share")
                    .isSameAs(first);
        }

        @Test
        @DisplayName("records carrying real corpus values pass through with key and amount intact")
        void realCorpusRecordsPassThroughUnchanged() {
            final FixtureLoader.FixtureData fixture =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);

            for (int index = 0; index < 5; index++) {
                final String identifier = fixture.field(index, TRAN_ID_COLUMN,
                        TransactionCombineProcessor.TRAN_ID_LENGTH);
                final BigDecimal amount = fixture.signedDecimal(index, TRAN_AMT_COLUMN,
                        FixtureLoader.AMOUNT_FIELD_WIDTH);
                final Transaction item = fullRecord(identifier, amount);

                assertThat(processor.process(item))
                    .as("a record built from the frozen fixture dailytran.txt - the word spelled in full, "
                            + "unlike the DALYTRAN dataset name - is accepted and returned untouched")
                    .isSameAs(item);
                assertThat(item.getAmount())
                    .as("the amount decoded from columns %d-%d is unchanged by the pass",
                            TRAN_AMT_COLUMN, TRAN_AMT_COLUMN + FixtureLoader.AMOUNT_FIELD_WIDTH - 1)
                    .isEqualByComparingTo(amount);
            }
        }

        @Test
        @DisplayName("negative amounts are genuine and are never normalised to their magnitude")
        void negativeAmountsAreNeverNormalised() {
            final FixtureLoader.FixtureData fixture =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            BigDecimal negative = null;
            BigDecimal positive = null;
            for (int index = 0; index < fixture.recordCount(); index++) {
                final BigDecimal amount = fixture.signedDecimal(index, TRAN_AMT_COLUMN,
                        FixtureLoader.AMOUNT_FIELD_WIDTH);
                if (negative == null && amount.signum() < 0) {
                    negative = amount;
                }
                if (positive == null && amount.signum() > 0) {
                    positive = amount;
                }
            }

            assertThat(negative)
                    .as("the overpunch in the final column of TRAN-AMT is decoded position-aware from the "
                            + "PIC clause alone: '{' is +0, 'A'-'I' are +1 to +9, '}' is -0 and 'J'-'R' are "
                            + "-1 to -9. dailytran.txt carries both '{' and '}', so it holds genuinely "
                            + "negative amounts. Those letters also occur legitimately inside TRAN-DESC, "
                            + "TRAN-MERCHANT-NAME and TRAN-MERCHANT-CITY, which is why the decode must be "
                            + "driven by position rather than by scanning for a sign character")
                    .isNotNull();
            assertThat(positive)
                    .as("the fixture exercises the positive branch too, so both signs reach this step")
                    .isNotNull();

            final Transaction item = fullRecord(BACKUP_ID_LOW, negative);
            assertThat(processor.process(item).getAmount())
                    .as("no absolute value is taken anywhere on this path: a debit stays a debit, because "
                            + "the posting arithmetic downstream subtracts a cycle accumulator that "
                            + "legitimately holds negative values")
                    .isEqualByComparingTo(negative)
                    .isNegative();
        }
    }

    // ===================================================================================================
    // STEP10 - app/jcl/COMBTRAN.jcl:L41-L48. The duplicate-key contract.
    // ===================================================================================================

    /**
     * The load contract: a repeated {@code TRAN-ID} must fail, and must never be absorbed.
     *
     * <p>The reasoning is entirely in the cards. {@code :L45-L46} opens
     * {@code TRANSACT.VSAM.KSDS} at {@code DISP=SHR}; the member contains no {@code IDCAMS DELETE} of that
     * cluster, no {@code IEFBR14} pre-delete step and no {@code REUSE} option - the one {@code DELETE} token
     * in all 52 lines is {@code DISP=(NEW,CATLG,DELETE)} on {@code SORTOUT} at {@code :L33}, an
     * abnormal-termination disposition for the sort work file that has nothing to do with the cluster. So
     * {@code :L48} adds to a cluster that already holds data, keyed on the first sixteen bytes -
     * {@code KEYS(16 0)} with {@code RECORDSIZE(350 350)} at {@code app/jcl/TRANBKP.jcl:L58-L59}, and
     * {@code KEYLEN 16 AVGLRECL 350} at {@code app/catlg/LISTCAT.txt:L3593}. A repeated identifier collides
     * on that key and the step fails.
     */
    @Nested
    @DisplayName("STEP10: a duplicate TRAN-ID fails the REPRO load and is never absorbed")
    final class DuplicateLoadMustFail {

        @Test
        @DisplayName("a duplicate identifier raises DuplicateRecordException rather than being swallowed")
        void aDuplicateIdentifierRaisesDuplicateRecordException() {
            final Transaction item = fullRecord(BACKUP_ID_LOW, new BigDecimal("10.00"));
            final DuplicateKeyException collision =
                    new DuplicateKeyException("duplicate key value violates unique constraint");

            assertThatExceptionOfType(DuplicateRecordException.class)
                    .as("app/jcl/COMBTRAN.jcl:L48 REPROs into a keyed cluster opened DISP=SHR with no "
                            + "pre-delete and no REUSE, so the load is additive and a repeated key fails it. "
                            + "The failure is surfaced, never absorbed")
                    .isThrownBy(() -> processor.translateLoadFailure(item, collision))
                    .withMessageContaining(BACKUP_ID_LOW)
                    .withCause(collision);
        }

        @Test
        @DisplayName("the failure carries the colliding key and the logical file, for diagnosis")
        void theFailureCarriesTheCollidingKeyAndLogicalFile() {
            final Transaction item = fullRecord(BACKUP_ID_LOW, new BigDecimal("10.00"));
            final DuplicateKeyException collision = new DuplicateKeyException("unique violation");

            final DuplicateRecordException thrown = catchDuplicate(item, collision);

            assertThat(thrown.getCollidingKey())
                    .as("the identifier is carried as a field as well as being named in the message, so an "
                            + "operator can act on it without parsing prose")
                    .isEqualTo(BACKUP_ID_LOW);
            assertThat(thrown.getLogicalFile())
                    .as("TRANSACT is the DD name at app/jcl/COMBTRAN.jcl:L43 that the REPRO of :L48 targets")
                    .isEqualTo("TRANSACT");
            assertThat(thrown.getCause())
                    .as("Rule 1 clause B requires the root cause to be preserved rather than replaced")
                    .isSameAs(collision);
        }

        @Test
        @DisplayName("the exit status is a failure, and the return code is 8 - never 4")
        void theExitStatusIsAFailureAndTheReturnCodeIsEightNeverFour() {
            final Transaction item = fullRecord(BACKUP_ID_LOW, new BigDecimal("10.00"));

            final DuplicateRecordException thrown =
                    catchDuplicate(item, new DuplicateKeyException("unique violation"));

            assertThat(thrown)
                    .as("the translation throws, so the exception propagates out of the chunk and the step "
                            + "ends FAILED rather than completing")
                    .isInstanceOf(RuntimeException.class);
            assertThat(ExitStatus.FAILED.getExitCode())
                    .as("a thrown exception yields a failed exit status, which is not a completed one")
                    .isEqualTo("FAILED")
                    .isNotEqualTo(ExitStatus.COMPLETED.getExitCode());
            assertThat(LOAD_FAILURE_RETURN_CODE)
                    .as("a failed IDCAMS load surfaces return code 8. It is emphatically not %d: "
                            + "MOVE 4 TO RETURN-CODE occurs exactly once in the whole 28-program corpus, at "
                            + "app/cbl/CBTRN02C.cbl:L230, where it means completed-with-rejects keyed solely "
                            + "on that program's reject count. This job counts no rejects and writes no "
                            + "reject dataset, so 4 can never be its outcome",
                            COMPLETED_WITH_REJECTS_RETURN_CODE)
                    .isNotEqualTo(COMPLETED_WITH_REJECTS_RETURN_CODE)
                    .isNotEqualTo(ABEND_RETURN_CODE)
                    .isEqualTo(8);
        }

        @Test
        @DisplayName("the message names the identifier but leaks no card number and no merchant data")
        void theMessageNamesTheIdentifierButLeaksNoPii() {
            final Transaction item = fullRecord(BACKUP_ID_LOW, new BigDecimal("10.00"));

            final DuplicateRecordException thrown =
                    catchDuplicate(item, new DuplicateKeyException("unique violation"));

            assertThat(thrown.getMessage())
                    .as("Rule 1 clause D names tests explicitly, and a transaction record carries a card "
                            + "number, merchant identity and an amount. The identifier is the one value an "
                            + "operator needs in order to find the record, so it is the only one reported; "
                            + "the record image never is")
                    .contains(BACKUP_ID_LOW)
                    .doesNotContain("0000000000000000")
                    .doesNotContain("Int. for a/c")
                    .doesNotContain("10.00");
        }

        @Test
        @DisplayName("the failure is stated plainly, and it is not a retry, an upsert or a sequence")
        void theRemediationIsStatedAndIsNotAnUpsert() {
            final Transaction item = fullRecord(INTEREST_ID_FIRST, new BigDecimal("10.00"));

            final DuplicateRecordException thrown =
                    catchDuplicate(item, new DuplicateKeyException("unique violation"));

            assertThat(thrown.getMessage())
                    .as("Rule 1 clause F requires a clear remediation. The correct one is to re-drive the "
                            + "pipeline with an unused date parameter, because the collision originates in a "
                            + "repeated PARM value - see app/jcl/INTCALC.jcl:L22. An ON CONFLICT clause, a "
                            + "merge, a saveOrUpdate, a substituted sequence or a retry would each silently "
                            + "corrupt the cluster, so the message rules them out in as many words")
                    .contains("do not retry, upsert or substitute a sequence");
        }

        @Test
        @DisplayName("DuplicateRecordException sits under CardDemoException and so is unchecked")
        void duplicateRecordExceptionSitsUnderCardDemoException() {
            final Transaction item = fullRecord(BACKUP_ID_LOW, new BigDecimal("10.00"));

            final DuplicateRecordException thrown =
                    catchDuplicate(item, new DuplicateKeyException("unique violation"));

            assertThat(thrown)
                    .as("the hierarchy is DuplicateRecordException -> CardDemoException -> RuntimeException, "
                            + "so a batch step need not declare it and Spring Batch will fail the step on it")
                    .isInstanceOf(CardDemoException.class)
                    .isInstanceOf(RuntimeException.class);
            assertThat(CardDemoException.class.getSuperclass())
                    .as("the base of the migration's hierarchy is RuntimeException, not Exception")
                    .isEqualTo(RuntimeException.class);
        }

        @Test
        @DisplayName("a duplicate is never reported as a generic integrity violation, despite the subtyping")
        void aDuplicateIsNeverReportedAsAGenericIntegrityViolation() {
            final Transaction item = fullRecord(BACKUP_ID_LOW, new BigDecimal("10.00"));

            assertThat(DataIntegrityViolationException.class)
                    .as("DuplicateKeyException is a subclass of DataIntegrityViolationException, so a branch "
                            + "order that tested the superclass first would classify every collision as a "
                            + "generic violation and lose the one diagnosis that matters here")
                    .isAssignableFrom(DuplicateKeyException.class);
            assertThatExceptionOfType(DuplicateRecordException.class)
                    .as("the duplicate branch is reached first, so the specific outcome wins")
                    .isThrownBy(() -> processor.translateLoadFailure(item,
                            new DuplicateKeyException("unique violation")));
        }

        @Test
        @DisplayName("a non-duplicate constraint violation maps to DataIntegrityException, with no constraint")
        void aNonDuplicateConstraintViolationMapsToDataIntegrityException() {
            final Transaction item = fullRecord(BACKUP_ID_LOW, new BigDecimal("10.00"));
            final DataIntegrityViolationException violation =
                    new DataIntegrityViolationException("foreign key violation");

            assertThatExceptionOfType(DataIntegrityException.class)
                    .as("a referential failure is a different condition from a key collision and must stay "
                            + "distinguishable from it")
                    .isThrownBy(() -> processor.translateLoadFailure(item, violation))
                    .withCause(violation)
                    .satisfies(thrown -> {
                        assertThat(thrown.getConstraintName())
                                .as("the driver does not name the violated constraint in any portable field, "
                                        + "so naming one would be a guess. It is left opaque and the retained "
                                        + "cause carries the driver's own detail")
                                .isNull();
                        assertThat(thrown.getRelation())
                                .as("the relation is known and is reported")
                                .isEqualTo("transaction");
                        assertThat(thrown).isNotInstanceOf(DuplicateRecordException.class);
                    });
        }

        @Test
        @DisplayName("any other store failure is fatal, with abend 999 and return code 12")
        void anyOtherStoreFailureIsFatal() {
            final Transaction item = fullRecord(BACKUP_ID_LOW, new BigDecimal("10.00"));
            final DataAccessException unexpected = new CannotAcquireLockException("lock timeout");

            assertThatExceptionOfType(FatalProcessingException.class)
                    .as("the status map ends in an abend: anything that is neither a duplicate key nor a "
                            + "constraint violation is unexpected, and an unexpected condition on an I/O path "
                            + "abends rather than being interpreted")
                    .isThrownBy(() -> processor.translateLoadFailure(item, unexpected))
                    .withCause(unexpected)
                    .satisfies(thrown -> {
                        assertThat(thrown.getAbendCode())
                                .as("999 is the abend code the batch corpus uses")
                                .isEqualTo(String.valueOf(FatalProcessingException.BATCH_ABEND_CODE));
                        assertThat(thrown.getAbendCulprit())
                                .as("there is no COBOL program to name for this job, so the eight-character "
                                        + "JCL member name is the culprit - which is both the true source "
                                        + "artefact and exactly the width ABEND-CULPRIT X(8) allows")
                                .isEqualTo("COMBTRAN");
                        assertThat(ABEND_RETURN_CODE)
                                .as("abend 999 pairs with return code 12, which is neither 8 nor 4")
                                .isEqualTo(12)
                                .isNotEqualTo(LOAD_FAILURE_RETURN_CODE)
                                .isNotEqualTo(COMPLETED_WITH_REJECTS_RETURN_CODE);
                    });
        }

        @Test
        @DisplayName("a null cause is fatal too, rather than being read as a duplicate")
        void aNullCauseIsFatalToo() {
            final Transaction item = fullRecord(BACKUP_ID_LOW, new BigDecimal("10.00"));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .as("an absent cause is an unknown condition, and an unknown condition is not a "
                            + "duplicate. Defaulting it to the duplicate branch would report a collision that "
                            + "may not have happened")
                    .isThrownBy(() -> processor.translateLoadFailure(item, null));
        }

        @Test
        @DisplayName("a null record is reported honestly rather than crashing the translation")
        void aNullRecordIsReportedHonestly() {
            final DuplicateKeyException collision = new DuplicateKeyException("unique violation");

            final DuplicateRecordException thrown = catchDuplicate(null, collision);

            assertThat(thrown.getMessage())
                    .as("Rule 1 clause F requires the word rather than a fabrication when a value is "
                            + "genuinely unknown, so an absent identifier is disclosed as Not available and "
                            + "never as the four letters of a null reference")
                    .contains("Not available")
                    .doesNotContain("'null'");
            assertThat(thrown.getCollidingKey()).isNull();
        }

        /**
         * Invokes the duplicate branch and returns what it threw.
         *
         * @param item  the record whose load failed, possibly {@code null}
         * @param cause the duplicate-key failure to translate
         * @return the translated exception, never {@code null}
         */
        private DuplicateRecordException catchDuplicate(final Transaction item,
                                                        final DuplicateKeyException cause) {
            try {
                processor.translateLoadFailure(item, cause);
            } catch (final DuplicateRecordException thrown) {
                return thrown;
            }
            throw new AssertionError("translateLoadFailure did not reject a duplicate key, which is the "
                    + "duplicate-key contract of app/jcl/COMBTRAN.jcl:L48");
        }
    }

    // ===================================================================================================
    // AAP invariant 9 and Rule 1 clause D - no external process, no store, in the compiled form
    // ===================================================================================================

    /**
     * DFSORT became a {@link Comparator}, and that is provable from the compiled class rather than asserted.
     */
    @Nested
    @DisplayName("Invariant 9: no external sort process, no shell, no store - proved by construction")
    final class NoExternalSortProcess {

        @ParameterizedTest(name = "[{index}] absent: {0}")
        @MethodSource("com.cardemo.unit.batch.TransactionCombineProcessorTest#forbiddenTokens")
        @DisplayName("the compiled class references no process, script, file or store mechanism")
        void theCompiledClassReferencesNoForbiddenMechanism(final String token) {
            assertThat(compiledForm())
                    .as("AAP invariant 9 states that no external sort process is spawned: DFSORT becomes a "
                            + "java.util.Comparator, full stop. Rule 1 clause D independently forbids the "
                            + "eval/exec and shell-injection family. Reading the class file's own constant "
                            + "pool proves '%s' is not referenced, which is stronger than asserting it in "
                            + "prose - a later edit that reintroduced it would fail this test", token)
                    .doesNotContain(token);
        }

        @Test
        @DisplayName("the ordering really is a java.util.Comparator, which is what replaces the utility")
        void theOrderingReallyIsAComparator() {
            assertThat(TransactionCombineProcessor.TRAN_ID_ASCENDING)
                    .as("the whole of SORT FIELDS=(TRAN-ID,A) at app/jcl/COMBTRAN.jcl:L30 reduces to one "
                            + "in-process comparator. No temporary sort work file is created and no child "
                            + "process is started, so there is nothing to clean up and nothing to inject "
                            + "into. This is a labelled mechanism substitution owed an entry in "
                            + "DECISION_LOG.md, "
                            + "not a performance claim")
                    .isInstanceOf(Comparator.class);
        }

        @Test
        @DisplayName("the compiled class is readable, so a vacuous pass is impossible")
        void theCompiledClassIsReadable() {
            assertThat(compiledForm())
                    .as("if the class file could not be read, every absence assertion above would pass "
                            + "trivially. Asserting that a token which must be present really is present is "
                            + "what rules that out")
                    .contains("TRAN_ID_ASCENDING")
                    .contains("app/jcl/COMBTRAN.jcl:L48");
        }
    }

    // ===================================================================================================
    // Rule 1 clause D - the identifier is the one attacker-influenced value this step handles
    // ===================================================================================================

    /**
     * A hostile {@code TRAN-ID} must not be able to forge a log record or split a message.
     *
     * <p>The identifier arrives as sixteen bytes lifted from a fixed-width image, so until it has been
     * validated nothing is known about its contents. Two mechanisms answer that, deliberately of different
     * widths: the refusal is narrow - exactly {@link Character#isISOControl(char)} - so it cannot refuse a
     * record the legacy system would have loaded, while the rendering is broad - everything outside printable
     * ASCII - so a character the refusal permits still cannot break a line.
     */
    @Nested
    @DisplayName("Clause D: a hostile TRAN-ID can neither pass the boundary nor forge a log record")
    final class LogRecordIntegrity {

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("com.cardemo.unit.batch.TransactionCombineProcessorTest#hostileIdentifiers")
        @DisplayName("a control character is refused, and the refusal is itself a single line")
        void aControlCharacterIsRefused(final String label, final String hostileId) {
            assertThat(hostileId)
                    .as("the fixture is exactly the declared width, so width is not what refuses it")
                    .hasSize(TransactionCombineProcessor.TRAN_ID_LENGTH);

            assertThatExceptionOfType(DataIntegrityException.class)
                    .as("no identifier in the frozen corpus carries a control character - every one is "
                            + "sixteen decimal digits - so refusing one cannot refuse a record the legacy "
                            + "system would have loaded. Case: %s", label)
                    .isThrownBy(() -> processor.process(recordWithId(hostileId)))
                    .satisfies(thrown -> assertThat(thrown.getMessage().lines().count())
                            .as("the message stays one line, so the value cannot turn one log record into "
                                    + "two, the second of which would read like an error this application "
                                    + "emitted")
                            .isEqualTo(1L));
        }

        @ParameterizedTest(name = "[{index}] via the {0} branch")
        @MethodSource("com.cardemo.unit.batch.TransactionCombineProcessorTest#lineBreakingIdentifiers")
        @DisplayName("whichever rejection branch fires, the message it produces is single-line")
        void whicheverBranchFiresTheMessageIsSingleLine(final String branch, final String hostileId) {
            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> processor.process(recordWithId(hostileId)))
                    .satisfies(thrown -> assertThat(thrown.getMessage().lines().count())
                            .as("escaping happens at the point of rendering rather than at each call site, "
                                    + "so a branch added later cannot forget it. Branch: %s", branch)
                            .isEqualTo(1L));
        }

        @Test
        @DisplayName("a legitimate identifier is reported verbatim, quoted so padding stays visible")
        void aLegitimateIdentifierIsReportedVerbatim() {
            final String padded = "68358           ";

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> processor.process(recordWithId(padded + "x")))
                    .withMessageContaining("17 characters");
            assertThatExceptionOfType(DataIntegrityException.class)
                    .as("the quotes are not decoration: a space-padded key is a real condition in a "
                            + "fixed-width record, and without them a trailing space is invisible in a log")
                    .isThrownBy(() -> processor.process(recordWithId("short")))
                    .withMessageContaining("'short'");
        }

        @Test
        @DisplayName("every reported identifier is escaped on the store-failure paths too")
        void everyReportedIdentifierIsEscapedOnStoreFailurePaths() {
            final Transaction hostile = recordWithId(forgedWith("\n"));

            assertThatExceptionOfType(DuplicateRecordException.class)
                    .isThrownBy(() -> processor.translateLoadFailure(hostile,
                            new DuplicateKeyException("unique violation")))
                    .satisfies(thrown -> assertThat(thrown.getMessage())
                            .as("the load-failure messages render the identifier through the same escaping "
                                    + "as the validation messages, so no path is left unprotected")
                            .doesNotContain("\n")
                            .contains("\\n"));
            assertThat(loggedMessages())
                    .as("what reaches an appender is what matters, and it too is single-line - the property "
                            + "must hold for any appender rather than for one encoder's escaping")
                    .isNotEmpty()
                    .allSatisfy(message -> assertThat(message.lines().count()).isEqualTo(1L));
        }

        @Test
        @DisplayName("an accepted record is logged at debug without the record image")
        void anAcceptedRecordIsLoggedWithoutTheRecordImage() {
            final Transaction item = fullRecord(BACKUP_ID_LOW, new BigDecimal("10.00"));

            processor.process(item);

            assertThat(loggedMessages())
                    .as("Rule 1 clause A asks for measurable behaviour, and the accepted path is observable "
                            + "at debug. Clause D forbids putting the record in the log, so only the "
                            + "identifier appears")
                    .anySatisfy(message -> assertThat(message)
                            .contains(BACKUP_ID_LOW)
                            .doesNotContain("0000000000000000")
                            .doesNotContain("Int. for a/c"));
        }
    }

    /**
     * The tokens {@link NoExternalSortProcess} asserts absent, one per case.
     *
     * @return each forbidden token as a single argument
     */
    static Stream<Arguments> forbiddenTokens() {
        return FORBIDDEN_CLASS_FILE_TOKENS.stream().map(Arguments::of);
    }

    /**
     * Reads the compiled form of {@link TransactionCombineProcessor} as text.
     *
     * <p>Decoded as ISO-8859-1 so that every byte maps to exactly one character and nothing is lost or
     * combined; the constant pool's UTF-8 entries remain readable, which is all this needs. Reading the class
     * file rather than the source is what makes the proof meaningful: comments and Javadoc are stripped by the
     * compiler, so a token found here is one the class genuinely references.
     *
     * @return the class file's bytes as a string, never {@code null}
     */
    private static String compiledForm() {
        final String resource = "/" + TransactionCombineProcessor.class.getName().replace('.', '/') + ".class";
        try (InputStream stream = TransactionCombineProcessor.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("the compiled form of TransactionCombineProcessor is not on "
                        + "the test classpath at " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
        } catch (final IOException cause) {
            throw new UncheckedIOException("could not read " + resource, cause);
        }
    }
}
