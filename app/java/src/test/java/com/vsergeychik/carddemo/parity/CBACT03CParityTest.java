package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vsergeychik.carddemo.account.AccountBalanceUpdateJob;
import com.vsergeychik.carddemo.account.AccountBalanceUpdateJob.ExecutionSummary;
import com.vsergeychik.carddemo.account.AccountBalanceUpdateJob.SysoutSink;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.CardXrefRepository.BrowseCursor;
import com.vsergeychik.carddemo.card.CardXrefRepository.ReadResult;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobContracts;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetChannel;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetInput;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetNormalisation;
import com.vsergeychik.carddemo.parity.ParityCase.EmittedMessage;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedDataset;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedRecord;
import com.vsergeychik.carddemo.parity.ParityCase.MessageChannel;
import com.vsergeychik.carddemo.parity.ParityCase.Normalisation;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.parity.ParityHarness.DecodedFingerprint;
import com.vsergeychik.carddemo.parity.ParityHarness.DecodedRecord;
import com.vsergeychik.carddemo.parity.ParityHarness.Invocation;
import com.vsergeychik.carddemo.parity.ParityHarness.SeededDataset;
import com.vsergeychik.carddemo.parity.ParityHarness.UnitOutcome;

import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The parity gate for {@code CBACT03C}: twenty declarative cases, judged field by field, with a
 * required diff count of zero on every one of them.
 *
 * <h2>The baseline is DERIVED, never captured</h2>
 * <p>Every expected value in {@code src/test/resources/parity/CBACT03C/} was obtained by reading
 * {@code app/cbl/CBACT03C.cbl} statement by statement and cross-checking it against four
 * authoritative artefacts: {@code app/cpy/CVACT03Y.cpy} for the byte layout,
 * {@code app/jcl/READXREF.jcl} for the step and {@code DD} contract, and
 * {@code app/data/ASCII/cardxref.txt} for the input rows. <strong>Nothing here was recorded from a
 * running COBOL program, because no COBOL program can be run in this environment.</strong> That is
 * not an omission, it is a documented and escalated deviation - AAP 0.7.6 lists eight independently
 * verified blockers (no z/OS runtime, the available compiler's indexed file handler is disabled, it
 * refuses {@code PROCEDURE ... USING} under {@code -x}, {@code app/cpy/CUSTREC.cpy} will not parse,
 * no Language Environment {@code CEE*} services exist, no CICS emulator, the EBCDIC datasets need
 * binary handling the compiler is not configured for, and no alternative compiler is installable) -
 * and AAP 0.9.11 records it as risk R-A, awaiting explicit user confirmation. Every substantive
 * requirement survives the substitution: twenty cases, field-for-field diffing, a diff count of
 * zero, and the branch-coverage bar. Only the <em>provenance</em> of the expected values changes.
 *
 * <p>The practical consequence for a reader is this: a difference reported by this class means the
 * Java disagrees with a <em>reading</em> of the COBOL, and the reading is cited line by line in each
 * case's {@code description} so that it can be checked against the source rather than trusted.
 *
 * <h2>The class name says Update. The program updates nothing, and that is preserved</h2>
 * <p>{@link AccountBalanceUpdateJob} is the prompt-mandated name for {@code CBACT03C}, whose own
 * header reads {@code Function : Read and print account cross reference data file}. The program
 * issues {@code OPEN INPUT} at {@code :120} and contains no {@code WRITE}, no {@code REWRITE} and no
 * {@code DELETE} anywhere in its 178 lines, so no update is possible even in principle - and
 * {@link CardXrefRepository} publishes no write operation for one to reach. The divergence between
 * the name and the behaviour is resolved by AAP rule R1: <em>the name comes from the prompt, the
 * behaviour comes from the source</em>. It is registered in AAP 0.8.4 and honoured here rather than
 * corrected, because adding write logic to match a class name would be a new feature and therefore a
 * parity violation (practices B4 and B5). Concretely: <strong>an expectation in this file that
 * asserted an output record would be wrong</strong>, so {@code case19} instead asserts the absence of
 * one on both channels at once, and {@link NoWrites} proves the same thing structurally.
 *
 * <h2>The cross-reference fixture is 36 bytes wide where the copybook declares 50</h2>
 * <p>{@code app/cpy/CVACT03Y.cpy} declares {@code CARD-XREF-RECORD} as
 * {@code XREF-CARD-NUM PIC X(16)} + {@code XREF-CUST-ID PIC 9(09)} +
 * {@code XREF-ACCT-ID PIC 9(11)} + {@code FILLER PIC X(14)}, which is 50 bytes. Every one of the 50
 * rows of {@code app/data/ASCII/cardxref.txt} measures exactly 36 - {@code 16 + 9 + 11}, with the
 * trailing {@code FILLER} span simply absent from the data. This class is therefore the <strong>primary
 * exerciser of the 36-to-50 normalisation</strong> (gate G16, risk R-F): a case that seeds those rows
 * declares {@link Normalisation#CARDXREF_FILLER_PAD_36_TO_50}, and the pad is applied <em>once</em>,
 * at seed time, by its single owner. Nothing here re-pads, and nothing compensates at comparison
 * time. Three assertions make the pad's participation impossible to fake:
 * {@link TheCardxrefPad#everySeededRowArrivesAtTheFullFiftyBytes()} measures every seeded row,
 * {@link TheCardxrefPad#aThirtySixByteSeedWithoutTheNormalisationIsRefused()} shows that removing the
 * declaration fails the seed outright, and {@link RecordGeometry} pins the 50-byte total width so a
 * dropped {@code FILLER} - which leaves a record short by exactly that span - fails immediately.
 *
 * <p>{@code CVACT03Y} has <strong>twelve consumers</strong>, more than any other data copybook in
 * the migration. A width or offset difference found here is a codebase-wide signal, not a local one:
 * the same record type reaches {@code CBACT04C}, {@code CBTRN01C}, {@code CBTRN02C},
 * {@code CBTRN03C}, {@code CBSTM03B} and several online programs.
 *
 * <h2>How the unit is reached</h2>
 * <p>{@link UnitKind#BATCH_JOB}, driven through {@link AccountBalanceUpdateJob#execute(SysoutSink)},
 * which is the program body as a plain method. That <em>is</em> the step logic and not a paraphrase of
 * it: {@code accountBalanceUpdateTasklet()} is a two-line adapter whose whole body resolves the
 * {@code SYSOUT} destination and calls the same method, so calling it here exercises exactly what a
 * running step exercises. <strong>No {@code JobLauncher}, no job repository, no asynchronous executor,
 * no application context and no HTTP layer sits between an assertion and the code</strong> (gate
 * G51). The {@code SYSOUT} sink is the harness's own recorder, so the ordered {@code DISPLAY} list is
 * captured exactly as emitted - including the lines a run emits <em>before</em> it abends, which is
 * why the recorder is used rather than a return value.
 *
 * <p>The three I/O outcomes the seeded data cannot produce - a failed {@code OPEN}, a failed
 * {@code READ}, a failed {@code CLOSE} - are arranged by the {@linkplain #SCENARIOS scenario table},
 * keyed by case identifier. A batch case cannot carry a {@code ForcedOutcome}: that member lives on
 * {@code ParityCase.ScreenRequest}, which {@link ParityCase} refuses for a {@link UnitKind#BATCH_JOB}
 * case because a batch job has no screen. The arrangement is an <em>input</em> - what the backend does
 * - and it is declared here, beside the doubles it configures; the resulting output is declared
 * independently in the case file. Neither side can see the other, so the two agreeing is evidence
 * rather than tautology.
 *
 * <h2>Rules</h2>
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - so <strong>no user
 * rule governs this file</strong>. Its absence is not treated as permission to lower the bar (UR4):
 * the binding standard is AAP 0.10.2 practices B1 to B12. Directly enforced here: B1 and B2 (JUnit 5,
 * AssertJ and Mockito only, all at the versions the parent BOM manages, with no new coordinate), B3
 * (every reference input is read-only - the fixture is read from the classpath copy under
 * {@code src/test/resources/fixtures} and {@code app/data} is never opened), B4 and B5 (the Update
 * divergence and the no-writes behaviour are documented and preserved), B7 (non-interactive and
 * deterministic - no watch mode, no clock, no locale and no filesystem write), B8 (the code page is
 * named, never defaulted; no wildcard import), B9 (no static mutable state - every static member is a
 * deeply immutable constant) and B10 (the cases ship with the implementation). The AAP gates it
 * carries are G15, G16, G18, G19, G21, G22, G24, G35, G46, G47, G51, G52, G53 and G54.
 */
@DisplayName("CBACT03C parity - 20 derived cases over AccountBalanceUpdateJob, which updates nothing")
class CBACT03CParityTest {

    /**
     * The COBOL program name, which is also this class's stem and the resource directory it reads.
     *
     * <p>The three spellings are deliberately identical - {@code CBACT03CParityTest} reads
     * {@code parity/CBACT03C/} - so no case conversion happens anywhere. The mixed filename casing of
     * the source tree is a trap that does not reach here: {@code app/cbl} holds 26 lower-case
     * {@code .cbl} files and two upper-case {@code .CBL} ones, but the resource directories are
     * uniformly the upper-case program name.
     */
    private static final String PROGRAM = "CBACT03C";

    /** The one {@code DD} {@code app/jcl/READXREF.jcl:25-26} declares for this step. */
    private static final String DD = AccountBalanceUpdateJob.XREFFILE_DD_NAME;

    /** {@code app/cpy/CVACT03Y.cpy}'s declared record width: 16 + 9 + 11 + 14. */
    private static final int RECORD_LENGTH = CardXrefRecord.RECORD_LENGTH;

    /**
     * A dataset name for the binding the job validates at construction.
     *
     * <p>Deliberately <strong>not</strong> {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS}. Gate G46
     * requires that no production dataset name appears in Java source, and a test is source: the
     * name a run resolves comes from {@code application.yml}, and this value exists only so the
     * constructor's binding checks have something to check.
     */
    private static final String TEST_DSNAME = "CARDDEMO.TEST.CARDXREF.VSAM.KSDS";

    /** What a codec diagnostic names when a seeded row is encoded for decoding. */
    private static final String ROW_SUBJECT = "a seeded " + PROGRAM + " cross-reference row";

    /**
     * The public method names {@link CardXrefRepository} must not have, checked by
     * {@link NoWrites#theRepositoryPublishesNoWriteOperationAtAll()}.
     *
     * <p>{@code addressing} is absent from the list on purpose: it re-binds a repository to a
     * {@code DD} and writes nothing, and a prefix test would have caught it while catching nothing
     * else.
     */
    private static final Set<String> FORBIDDEN_WRITE_METHODS = Set.of(
        "write", "writeRecord", "rewrite", "rewriteRecord", "delete", "deleteRecord",
        "add", "addRecord", "insert", "update", "save", "upsert", "merge", "put");

    // =============================================================================================
    //  THE SCENARIO TABLE - the I/O outcomes seeded data cannot produce
    // =============================================================================================

    /**
     * What the backend does during one case: how the {@code OPEN} answers, how the {@code CLOSE}
     * answers, and which {@code READ} - if any - fails and with what status.
     *
     * <p>This is an <strong>input</strong>, in exactly the sense the seeded rows are. The three
     * paragraphs of {@code CBACT03C} each test a {@code FILE STATUS} and abend on the arm they do not
     * name, and no arrangement of rows can produce a failed {@code OPEN}, a permanent read error or a
     * failed {@code CLOSE} - those come from the dataset being unavailable, from a hardware or catalogue
     * fault, or from a record whose length disagrees with the copybook. A case therefore states its
     * expected output in {@code parity/CBACT03C/caseNN.json} and its arranged input here, and the two
     * are written independently.
     *
     * <p>Every component is validated on construction, so a table entry that says two contradictory
     * things - a failed open that also fails a read, a failing read index with no status - cannot be
     * written at all. That matters more than it looks: an entry that quietly did nothing would leave
     * the run on its ordinary path while the case file described a failure, and the case would then
     * fail with a message about lines rather than about the table.
     *
     * @param openStatus the two-character status {@code OPEN INPUT} at {@code :120} reports
     * @param closeStatus the two-character status {@code CLOSE} at {@code :138} reports
     * @param failingRead the zero-based index of the {@code READ} that fails, or
     *     {@link #NO_FAILING_READ} when every read succeeds until end of file. It may equal the
     *     seeded row count, which arranges the failure at the position end of file would otherwise
     *     have been reported
     * @param failingReadStatus the status that read reports, or {@code null} when no read fails
     */
    private record Scenario(String openStatus, String closeStatus, int failingRead,
                            String failingReadStatus) {

        /** The {@link #failingRead} value meaning "no read fails". */
        private static final int NO_FAILING_READ = -1;

        /** Validates the entry against the three shapes {@code CBACT03C} can actually take. */
        private Scenario {
            requireStatus(openStatus, "openStatus");
            requireStatus(closeStatus, "closeStatus");
            if (failingRead < NO_FAILING_READ) {
                throw new IllegalStateException("A failing read index of " + failingRead
                    + " is neither a zero-based position nor " + NO_FAILING_READ + ", which is how "
                    + "this table says that no read fails");
            }
            boolean readFails = failingRead != NO_FAILING_READ;
            if (readFails != (failingReadStatus != null)) {
                throw new IllegalStateException("A scenario must either name a failing read index AND "
                    + "the status that read reports, or neither. This one names index " + failingRead
                    + " and status " + (failingReadStatus == null ? "none" : failingReadStatus)
                    + ", which would arrange a failure nothing reports or a status nothing carries.");
            }
            if (failingReadStatus != null) {
                requireStatus(failingReadStatus, "failingReadStatus");
            }
            if (!FileStatus.isOk(openStatus) && (readFails || !FileStatus.isOk(closeStatus))) {
                throw new IllegalStateException("A scenario whose OPEN fails cannot also arrange a "
                    + "read or a close outcome: app/cbl/CBACT03C.cbl:132 abends inside "
                    + "0000-XREFFILE-OPEN, so neither :93 nor :138 is ever reached and an arrangement "
                    + "for them would assert a path that does not exist.");
            }
            if (readFails && !FileStatus.isOk(closeStatus)) {
                throw new IllegalStateException("A scenario whose READ fails cannot also arrange a "
                    + "failing CLOSE: :113 abends inside 1000-XREFFILE-GET-NEXT, so "
                    + "9000-XREFFILE-CLOSE at :136 is never performed. The cursor is still released "
                    + "silently on the way out, which is why the close status is stated at all.");
            }
        }

        /** Refuses anything that is not a two-character COBOL {@code FILE STATUS}. */
        private static void requireStatus(String status, String member) {
            Objects.requireNonNull(status, "Scenario." + member + " is required; a COBOL FILE STATUS "
                + "is always two characters, and '" + FileStatus.OK + "' is how a successful "
                + "operation reports itself");
            if (status.length() != FileStatus.STATUS_LENGTH) {
                throw new IllegalStateException("Scenario." + member + " is '" + status + "', which is "
                    + status.length() + " character(s); IO-STATUS at app/cbl/CBACT03C.cbl:50-52 is two "
                    + "PIC X items and holds exactly " + FileStatus.STATUS_LENGTH);
            }
        }

        /** Everything succeeds: the whole seeded dataset is read to end of file and closed. */
        private static Scenario clean() {
            return new Scenario(FileStatus.OK, FileStatus.OK, NO_FAILING_READ, null);
        }

        /**
         * {@code OPEN INPUT} at {@code :120} reports something other than {@code '00'}.
         *
         * @param status the status it reports
         * @return the scenario
         */
        private static Scenario openFails(String status) {
            return new Scenario(status, FileStatus.OK, NO_FAILING_READ, null);
        }

        /**
         * The {@code READ} at {@code :93} succeeds for a number of records and then fails.
         *
         * @param afterRecords how many records are read and displayed first, which is also the
         *     zero-based index of the failing read
         * @param status the status the failing read reports
         * @return the scenario
         */
        private static Scenario readFailsAfter(int afterRecords, String status) {
            return new Scenario(FileStatus.OK, FileStatus.OK, afterRecords, status);
        }

        /**
         * Every read succeeds and {@code CLOSE} at {@code :138} then fails.
         *
         * @param status the status it reports
         * @return the scenario
         */
        private static Scenario closeFails(String status) {
            return new Scenario(FileStatus.OK, status, NO_FAILING_READ, null);
        }

        /** @return whether {@code 0000-XREFFILE-OPEN} completes and the loop is reached at all */
        private boolean openSucceeds() {
            return FileStatus.isOk(openStatus);
        }

        /** @return whether one of the reads is arranged to fail */
        private boolean readFails() {
            return failingRead != NO_FAILING_READ;
        }

        /** @return whether {@code 9000-XREFFILE-CLOSE} is arranged to fail */
        private boolean closeFails() {
            return !FileStatus.isOk(closeStatus);
        }

        /**
         * The status whose {@code IO-STATUS-04} image the run is expected to display, or {@code null}
         * for a run that displays none.
         *
         * @return the failing status, whichever paragraph reported it
         */
        private String failingStatus() {
            if (!openSucceeds()) {
                return openStatus;
            }
            if (readFails()) {
                return failingReadStatus;
            }
            return closeFails() ? closeStatus : null;
        }
    }

    /**
     * One scenario per case, in case order - the arranged input side of all twenty cases.
     *
     * <p>Twelve cases run everything cleanly and differ only in what they seed; eight arrange a
     * failure, and between them they reach all three abend sites and both arms of
     * {@code 9910-DISPLAY-IO-STATUS}:
     * <ul>
     *   <li>{@code case06} and {@code case07} fail the {@code OPEN} at {@code :120}, the second with
     *       the extended status {@code '92'} that drives the {@code IO-STAT1 = '9'} arm at
     *       {@code :162-168};</li>
     *   <li>{@code case08} fails the very first {@code READ}; {@code case09} fails the fourth with
     *       {@code '04'}; {@code case10} fails the third with {@code '23'} and {@code case11} the
     *       fifth with {@code '22'} - two statuses that are ordinary branches in the online programs
     *       and fatal here, because {@code :94} tests only {@code '00'} and {@code :98} only
     *       {@code '10'};</li>
     *   <li>{@code case12} and {@code case13} fail the {@code CLOSE} at {@code :138}, the second with
     *       the extended status {@code '96'} and on a run that read nothing at all.</li>
     * </ul>
     *
     * <p>Deeply immutable: an unmodifiable view over a map of records built once by
     * {@link #declaredScenarios()}. Declaration order is preserved so a diagnostic lists the entries
     * as a reader expects, and no test can perturb what another test reads (practice B9, gate G53).
     */
    private static final Map<String, Scenario> SCENARIOS = declaredScenarios();

    /**
     * Builds the scenario table.
     *
     * @return the twenty entries in case order
     */
    private static Map<String, Scenario> declaredScenarios() {
        Map<String, Scenario> declared = new LinkedHashMap<>();
        declared.put("case01", Scenario.clean());
        declared.put("case02", Scenario.clean());
        declared.put("case03", Scenario.clean());
        declared.put("case04", Scenario.clean());
        declared.put("case05", Scenario.clean());
        declared.put("case06", Scenario.openFails("35"));
        declared.put("case07", Scenario.openFails("92"));
        declared.put("case08", Scenario.readFailsAfter(0, "30"));
        declared.put("case09", Scenario.readFailsAfter(3, FileStatus.RECORD_LENGTH_CONFLICT));
        declared.put("case10", Scenario.readFailsAfter(2, FileStatus.NOT_FOUND));
        declared.put("case11", Scenario.readFailsAfter(4, FileStatus.DUPLICATE));
        declared.put("case12", Scenario.closeFails("42"));
        declared.put("case13", Scenario.closeFails("96"));
        declared.put("case14", Scenario.clean());
        declared.put("case15", Scenario.clean());
        declared.put("case16", Scenario.clean());
        declared.put("case17", Scenario.clean());
        declared.put("case18", Scenario.clean());
        declared.put("case19", Scenario.clean());
        declared.put("case20", Scenario.clean());
        return Collections.unmodifiableMap(declared);
    }

    /**
     * The scenario a case runs under.
     *
     * @param caseId the case identifier the harness handed the adapter
     * @return the arranged backend behaviour, never {@code null}
     * @throws IllegalStateException if the table has no entry, which means a case file exists that
     *     nothing arranged - it would then run cleanly while its expectations described a failure, and
     *     the failure message would be about lines rather than about the omission
     */
    private static Scenario scenarioFor(String caseId) {
        Scenario scenario = SCENARIOS.get(caseId);
        if (scenario == null) {
            throw new IllegalStateException("No scenario is declared for " + PROGRAM + '/' + caseId
                + ". Every one of the " + ParityHarness.CASES_PER_PROGRAM + " cases states the backend "
                + "behaviour it runs under, because a batch case cannot carry a ForcedOutcome - that "
                + "member belongs to ParityCase.ScreenRequest, which a BATCH_JOB case may not declare. "
                + "Declared: " + SCENARIOS.keySet() + '.');
        }
        return scenario;
    }

    // =============================================================================================
    //  TEST DOUBLES AND WIRING - everything the unit's only public constructor demands
    // =============================================================================================

    /**
     * An {@link ObjectProvider} over a container that publishes no bean of the requested type.
     *
     * <p>One generic double serves all three providers this file has to supply - the
     * {@code SysoutSink} the job falls back to, and the {@code JobRepository} and transaction manager
     * {@link BatchConfig} holds for its builder methods - and it deliberately resolves to nothing in
     * every case. None of the three is ever consulted on the path under test: the sink is passed
     * explicitly to {@link AccountBalanceUpdateJob#execute(SysoutSink)}, and the batch plumbing is
     * touched only by the {@code @Bean} methods, which a parity run must not call because doing so
     * would put a job repository between the assertion and the program body.
     *
     * <p>Because the type argument is inferred from each constructor parameter, this also keeps
     * {@code JobRepository} and {@code PlatformTransactionManager} out of this file's imports - they
     * are Spring Batch plumbing that a parity test has no business naming.
     *
     * @param <T> the bean type the container does not publish
     */
    private static final class NoBeanPublished<T> implements ObjectProvider<T> {

        @Override
        public T getObject() {
            throw new NoSuchBeanDefinitionException("a parity run publishes no bean of this type: "
                + "the SYSOUT sink is passed explicitly and the batch plumbing is never reached");
        }

        @Override
        public T getIfAvailable() {
            return null;
        }

        @Override
        public T getIfUnique() {
            return null;
        }
    }

    /**
     * The batch seam, carrying the contract {@code app/jcl/READXREF.jcl} actually declares.
     *
     * <p>Every value is source-derived, and the job's constructor checks all of them: the program is
     * {@code CBACT03C}, there is exactly one step and it is named {@code STEP05}, the step is
     * <strong>not</strong> gated on a preceding exit code because {@code READXREF.jcl} declares no
     * {@code COND}, no job parameter is declared because it declares no {@code PARM}, and the
     * {@value #DD} binding's record length is the {@value #RECORD_LENGTH} bytes
     * {@code app/cpy/CVACT03Y.cpy} declares. A parity run therefore exercises those guards rather
     * than bypassing them.
     *
     * <p>{@value #DD} and {@link CardXrefRepository#BASE_DD_NAME} are bound to the same dataset
     * because they are two names for one cluster - the batch {@code DD} of
     * {@code app/jcl/READXREF.jcl:25-26} and the CICS file name the repository is constructed
     * against - and the job proves at startup that the two agree.
     *
     * @return a freshly built seam, so no two runs share one
     */
    private static BatchConfig batchConfig() {
        JobContracts contracts = new JobContracts();
        contracts.put(AccountBalanceUpdateJob.JOB_KEY, new JobContract(
            AccountBalanceUpdateJob.PROGRAM_NAME,
            List.of(),
            List.of(new StepContract(AccountBalanceUpdateJob.STEP_NAME,
                AccountBalanceUpdateJob.PROGRAM_NAME, false)),
            null,
            Map.of()));

        DatasetBinding binding = new DatasetBinding(TEST_DSNAME, DatasetBinding.KSDS, false, "FB",
            null, RECORD_LENGTH, "CVACT03Y", CardXrefRecord.XREF_CARD_NUM_LENGTH, null, null, null);
        DatasetBindings bindings = new DatasetBindings();
        bindings.put(DD, binding);
        bindings.put(CardXrefRepository.BASE_DD_NAME, binding);

        return new BatchConfig(new NoBeanPublished<>(), new NoBeanPublished<>(), contracts, bindings);
    }

    /**
     * The unit under test, over a caller-supplied repository.
     *
     * @param repository the cross-reference repository double the browse comes from
     * @param charset the code page the harness seeds and compares under, handed in rather than chosen
     *     here so the job's codec and the harness's cannot disagree (practice B8)
     * @return the job, constructed through its only public constructor
     */
    private static AccountBalanceUpdateJob jobOver(CardXrefRepository repository, Charset charset) {
        return new AccountBalanceUpdateJob(batchConfig(), repository, charset,
            new NoBeanPublished<>());
    }

    /**
     * Decodes one seeded row into the record a successful {@code READ} delivers.
     *
     * <p>The row is encoded and decoded through the harness's own codec, so the code page is the one
     * the case was seeded under and never the platform default. A row that is not
     * {@value #RECORD_LENGTH} bytes is refused by {@link CardXrefRecord#decode(byte[], FixedWidthCodec)}
     * rather than partially decoded - which is exactly what makes the 36-to-50 normalisation's
     * participation impossible to skip.
     *
     * @param image the seeded row, at its full copybook width
     * @param codec the harness's codec
     * @return the decoded record
     */
    private static CardXrefRecord recordOf(String image, FixedWidthCodec codec) {
        return CardXrefRecord.decode(codec.encodeImage(image, ROW_SUBJECT), codec);
    }

    /**
     * The sequence of {@code READ} outcomes the browse delivers for one case.
     *
     * <p>Each successful read carries <strong>both</strong> views of the row: the decoded record, for
     * anything that branches on a value, and the row's own characters as the stored image, because
     * {@code READ XREFFILE-FILE INTO CARD-XREF-RECORD} at {@code :93} moves the whole 50 bytes into
     * the record area and {@code DISPLAY CARD-XREF-RECORD} then writes that area - {@code FILLER}
     * included. Handing over a re-encoded image instead would silently blank the filler span and
     * {@code case05} is the case that would catch it.
     *
     * <p>An empty list is returned when the {@code OPEN} is arranged to fail. That is deliberate and
     * load-bearing: {@link #cursorFor(Scenario, List)} then leaves {@code readNext()} and
     * {@code closeBrowse()} unstubbed, so a translation that read or closed after a failed open would
     * fail loudly on the {@code null} a bare mock returns rather than pass quietly.
     *
     * @param scenario the arranged backend behaviour
     * @param seeded the rows the case seeded, already at copybook width
     * @param codec the harness's codec
     * @return the reads in order, ending in the failure or in end of file
     * @throws IllegalStateException if the scenario arranges more successful reads than the case
     *     seeded rows, which would make the failing read arrive at a position that does not exist
     */
    private static List<ReadResult> readsFor(Scenario scenario, SeededDataset seeded,
                                             FixedWidthCodec codec) {
        if (!scenario.openSucceeds()) {
            return List.of();
        }
        int successfulReads = scenario.readFails() ? scenario.failingRead() : seeded.rowCount();
        if (successfulReads > seeded.rowCount()) {
            throw new IllegalStateException("The scenario arranges " + successfulReads
                + " successful read(s) before it fails, but the case seeds only " + seeded.rowCount()
                + " row(s) into " + DD + ". The failing read may sit at the end-of-file position, "
                + "which is index " + seeded.rowCount() + ", but never beyond it.");
        }
        List<ReadResult> reads = new ArrayList<>(successfulReads + 1);
        for (int index = 0; index < successfulReads; index++) {
            String image = seeded.row(index);
            reads.add(ReadResult.found(DD, recordOf(image, codec), image));
        }
        reads.add(scenario.readFails()
            ? failingRead(scenario.failingReadStatus(), seeded, codec)
            : ReadResult.endOfFile(DD));
        return List.copyOf(reads);
    }

    /**
     * The one failing {@code READ} outcome, built through the factory that owns its status.
     *
     * <p>The status is not stamped onto a generic outcome: each of the four is produced by the
     * factory {@link CardXrefRepository} publishes for it, so the {@code RESP} value, the
     * {@code RESP2} value and the presence or absence of a record are the ones the production
     * repository would report. That matters because {@code CBACT03C} branches on the status alone -
     * {@code :94} tests {@code '00'} and {@code :98} tests {@code '10'}, and everything else falls
     * to {@code :101} - so a case that arranged the right status through the wrong outcome would
     * still reach the right arm while asserting a condition that cannot occur.
     *
     * @param status the status the read reports
     * @param seeded the seeded rows, needed only for the duplicate outcome, which returns a record
     * @param codec the harness's codec
     * @return the failing outcome
     * @throws IllegalStateException if a duplicate is arranged for a case that seeded no row
     */
    private static ReadResult failingRead(String status, SeededDataset seeded,
                                          FixedWidthCodec codec) {
        if (FileStatus.isNotFound(status)) {
            return ReadResult.notFound(DD);
        }
        if (FileStatus.isRecordLengthConflict(status)) {
            return ReadResult.lengthError(DD);
        }
        if (FileStatus.isDuplicate(status)) {
            if (seeded.isEmpty()) {
                throw new IllegalStateException("A duplicate-key read returns the first matching "
                    + "record alongside the condition, so it cannot be arranged for a case that seeds "
                    + "no row into " + DD);
            }
            String image = seeded.row(seeded.rowCount() - 1);
            return ReadResult.duplicate(DD, recordOf(image, codec), image, FileStatus.DUPKEY);
        }
        return ReadResult.other(DD, status);
    }

    /**
     * The browse cursor for one case.
     *
     * @param scenario the arranged backend behaviour
     * @param reads the read sequence, empty when the open fails
     * @return the cursor double, with only the operations the arranged path reaches stubbed
     */
    private static BrowseCursor cursorFor(Scenario scenario, List<ReadResult> reads) {
        BrowseCursor cursor = mock(BrowseCursor.class);
        when(cursor.openStatus()).thenReturn(scenario.openStatus());
        if (!reads.isEmpty()) {
            when(cursor.readNext()).thenReturn(reads.get(0),
                reads.subList(1, reads.size()).toArray(new ReadResult[0]));
            when(cursor.closeBrowse()).thenReturn(scenario.closeStatus());
        }
        return cursor;
    }

    /**
     * A cross-reference repository whose browse is the given cursor.
     *
     * <p>{@code addressing} is stubbed to hand the same instance back, which is what the real
     * repository does whenever the {@code DD} resolves to the dataset it already addresses - the
     * shipped configuration, where {@value #DD} and {@link CardXrefRepository#BASE_DD_NAME} name one
     * cluster. Without it the job would open a browse on an instance nothing stubbed.
     *
     * <p>The cursor is built before this method is called rather than inside a {@code when(...)} for
     * this mock, because stubbing one mock inside another's unfinished stubbing is what Mockito
     * reports as unfinished stubbing.
     *
     * @param cursor the cursor its open returns
     * @return the repository double
     */
    private static CardXrefRepository repositoryOver(BrowseCursor cursor) {
        CardXrefRepository repository = mock(CardXrefRepository.class);
        when(repository.addressing(any(), any(), any(), any())).thenReturn(repository);
        when(repository.openBrowse()).thenReturn(cursor);
        return repository;
    }

    // =============================================================================================
    //  THE ADAPTER - one pass of CBACT03C, with nothing between the assertion and the code
    // =============================================================================================

    /**
     * Runs {@code CBACT03C} once for one case and records everything it observably produced.
     *
     * <p>Three properties of this method are deliberate and each closes a way the gate could pass
     * over nothing.
     *
     * <p><strong>The final state is recorded before the program runs.</strong> The seed is immutable
     * and this program cannot write - {@code OPEN INPUT} at {@code :120}, and no write operation on
     * the repository to reach - so "the dataset still holds exactly what it held" is true from the
     * outset. Recording it first is what puts it in the fingerprint on the abend path as well as the
     * normal one: {@code CALL 'CEE3ABD'} at {@code :158} does not return, so anything recorded after
     * {@code execute} would be absent from precisely the eight cases that need it most.
     *
     * <p><strong>Output goes to the recorder, not to a return value.</strong> A run that displays six
     * lines and then abends has emitted those six lines, and they belong in the fingerprint. The
     * harness drains the recorder when it catches an {@link AbendException} and takes the
     * {@code RETURN-CODE} from the abend, so {@code null} is returned here and the return code is
     * stated only on the path that completes.
     *
     * <p><strong>The program body is called directly.</strong>
     * {@link AccountBalanceUpdateJob#execute(SysoutSink)} is an ordinary method; no
     * {@code JobLauncher}, no job repository, no step scope and no HTTP layer is involved (gate G51).
     *
     * @param invocation the seeded datasets, the case identifier, the codec and the recorder
     * @return {@code null}, meaning the recorder holds the observations
     */
    private static UnitOutcome runProgram(Invocation invocation) {
        SeededDataset seeded = invocation.dataset(DD);
        UnitOutcome.Builder recorder = invocation.recorder();
        recorder.finalStateUnchanged(seeded, CardXrefRecord.LAYOUT);

        Scenario scenario = scenarioFor(invocation.caseId());
        BrowseCursor cursor = cursorFor(scenario, readsFor(scenario, seeded, invocation.codec()));
        ExecutionSummary summary = jobOver(repositoryOver(cursor), invocation.charset())
            .execute(recorder::display);

        recorder.returnCode(summary.returnCode());
        return null;
    }

    // =============================================================================================
    //  CASE SUPPLY AND THE GATE ITSELF
    // =============================================================================================

    /**
     * A harness seeding and comparing under {@code US-ASCII}, the code page of the nine fixtures.
     *
     * <p>A fresh instance per call. It holds no per-run state, but a shared one would be a static
     * mutable field in all but name, and practice B9 admits none.
     *
     * @return the harness
     */
    private static ParityHarness harness() {
        return ParityHarness.usAscii();
    }

    /**
     * The twenty cases, loaded from {@code parity/CBACT03C/} - the {@code @MethodSource} the gate
     * runs over.
     *
     * <p>{@link ParityHarness#casesOf(String)} already refuses a short set, a set with a stray file
     * and a mis-numbered case, so a nineteen-case run cannot reach this method. What is checked here
     * is the part it cannot know: that the files describe <em>this</em> program, as batch cases, in
     * ascending case order, and that every one of them has an arranged scenario. A case with no
     * scenario would run cleanly while its expectations described a failure, and the resulting
     * message would be about lines rather than about the omission (gate G15).
     *
     * @return the twenty cases in ascending case order
     * @throws IllegalStateException if the set is not exactly the declared twenty
     */
    static List<ParityCase> cases() {
        List<ParityCase> loaded = ParityHarness.casesOf(PROGRAM);
        if (loaded.size() != ParityHarness.CASES_PER_PROGRAM) {
            throw new IllegalStateException("Program " + PROGRAM + " supplied " + loaded.size()
                + " case(s) where the gate requires exactly " + ParityHarness.CASES_PER_PROGRAM
                + ". A short set is not a smaller gate, it is a gate that passes without asking the "
                + "questions.");
        }
        if (SCENARIOS.size() != ParityHarness.CASES_PER_PROGRAM) {
            throw new IllegalStateException("The scenario table declares " + SCENARIOS.size()
                + " entr(ies) where the gate requires exactly " + ParityHarness.CASES_PER_PROGRAM
                + ": " + SCENARIOS.keySet());
        }
        for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
            String expectedId = ParityHarness.caseId(ordinal);
            ParityCase parityCase = loaded.get(ordinal - 1);
            if (!expectedId.equals(parityCase.caseId()) || !PROGRAM.equals(parityCase.program())) {
                throw new IllegalStateException("Position " + (ordinal - 1) + " of the case list is "
                    + parityCase.program() + '/' + parityCase.caseId() + " where " + PROGRAM + '/'
                    + expectedId + " was required. The list is addressed by position in several "
                    + "assertions below, so ascending case order is part of the contract.");
            }
            if (parityCase.unitKind() != UnitKind.BATCH_JOB) {
                throw new IllegalStateException(PROGRAM + '/' + parityCase.caseId() + " declares "
                    + "unitKind " + parityCase.unitKind() + ". " + PROGRAM + " contains zero EXEC "
                    + "CICS statements and is invoked by EXEC PGM= in app/jcl/READXREF.jcl, so every "
                    + "case is a " + UnitKind.BATCH_JOB + " case.");
            }
            scenarioFor(parityCase.caseId());
        }
        return loaded;
    }

    /**
     * <strong>The gate.</strong> Runs one case and requires a diff count of zero.
     *
     * <p>The run and the judgement are separate calls so the fingerprint is in hand when the
     * assertion is written: a failure then reports the differences <em>and</em> what the run actually
     * produced, which is the difference between a message a reviewer can act on and one that only
     * says a number was not zero. {@link DiffResult#render()} lists every difference in the differ's
     * fully determined traversal order - the writes, the final state, the return code and then each
     * emitted line by position - and never invents a byte or pads one away.
     *
     * <p>AAP gate G18 is stated per module, not per build: this program is not complete until all
     * twenty of its cases report zero. Nineteen clean cases and one difference is an incomplete
     * module, which is why the count is asserted here, per case, rather than aggregated.
     *
     * <p>The failure text is supplied lazily. Rendering a diff and a whole fingerprint costs real
     * work - {@code case01} carries fifty records and 102 lines - and doing it eagerly would pay that
     * cost twenty times per run to produce a message nineteen or twenty of them never use.
     *
     * @param parityCase one of the twenty cases
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("diff count is zero - field for field, per case (G18)")
    void theDiffCountIsZero(ParityCase parityCase) {
        ParityHarness harness = harness();

        DecodedFingerprint fingerprint =
            harness.run(parityCase, UnitKind.BATCH_JOB, CBACT03CParityTest::runProgram);
        DiffResult diffs = harness.judge(parityCase, fingerprint);

        assertThat(diffs.count())
            .withFailMessage(() -> renderFailure(parityCase, diffs, fingerprint))
            .isZero();
        assertThat(diffs.isClean())
            .withFailMessage(() -> "A diff count of zero and a clean result are the same statement, "
                + "so the two accessors must agree for " + parityCase.program() + '/'
                + parityCase.caseId() + ", and they do not.")
            .isTrue();
    }

    /**
     * The whole story of one failed case: the differences, then what the run produced.
     *
     * <p>Both halves matter. The differences say what disagreed; the fingerprint says what the Java
     * actually did, which is what a reviewer needs in order to decide whether the translation is wrong
     * or the derived expectation is - and with a statically derived baseline (AAP risk R-A) that
     * second possibility is real and must be easy to check. The expectations themselves are not
     * reprinted, because {@link DiffResult#render()} already quotes both sides of every difference,
     * through {@code Redaction} where a value could be sensitive.
     *
     * @param parityCase the case that failed
     * @param diffs what the differ found
     * @param fingerprint what the run produced
     * @return the message, composed only when the assertion actually fails
     */
    private static String renderFailure(ParityCase parityCase, DiffResult diffs,
                                        DecodedFingerprint fingerprint) {
        return "The parity gate for " + parityCase.program() + '/' + parityCase.caseId() + " found "
            + diffs.count() + " difference(s), and the criterion is a diff count of ZERO across all "
            + ParityHarness.CASES_PER_PROGRAM + " cases - so this program is incomplete until every "
            + "one of them is resolved. The expectations are derived from app/cbl/CBACT03C.cbl, "
            + "app/cpy/CVACT03Y.cpy, app/jcl/READXREF.jcl and app/data/ASCII/cardxref.txt, none of "
            + "which may be edited to make a difference go away."
            + System.lineSeparator() + diffs.render()
            + System.lineSeparator() + System.lineSeparator() + "What the run produced:"
            + System.lineSeparator() + fingerprint.render();
    }

    /**
     * Runs one case and hands back what it produced, for the assertions that look at a run rather
     * than at a diff.
     *
     * @param parityCase the case to run
     * @return the decoded fingerprint
     */
    private static DecodedFingerprint fingerprintOf(ParityCase parityCase) {
        return harness().run(parityCase, UnitKind.BATCH_JOB, CBACT03CParityTest::runProgram);
    }

    /**
     * The lines a case expects, in order, as plain text.
     *
     * @param parityCase the case
     * @return the expected {@code DISPLAY} lines
     */
    private static List<String> expectedLines(ParityCase parityCase) {
        List<String> lines = new ArrayList<>(parityCase.expectedMessages().size());
        for (EmittedMessage message : parityCase.expectedMessages()) {
            lines.add(message.text());
        }
        return List.copyOf(lines);
    }

    /**
     * Every line {@code CBACT03C} can emit that is <em>not</em> a record image.
     *
     * <p>Used to partition a case's expected lines: anything outside this set must be a displayed
     * record and therefore exactly {@value #RECORD_LENGTH} characters wide. The three
     * {@code FILE STATUS} lines are excluded by their prefix rather than enumerated, because the
     * four-character image varies with the status.
     *
     * @param line one expected line
     * @return {@code true} when the line is a banner, a paragraph message or a status line
     */
    private static boolean isDiagnosticLine(String line) {
        return AccountBalanceUpdateJob.START_OF_EXECUTION.equals(line)
            || AccountBalanceUpdateJob.END_OF_EXECUTION.equals(line)
            || AccountBalanceUpdateJob.ERROR_OPENING_XREFFILE.equals(line)
            || AccountBalanceUpdateJob.ERROR_READING_XREFFILE.equals(line)
            || AccountBalanceUpdateJob.ERROR_CLOSING_XREFFILE.equals(line)
            || AbendException.ABEND_DISPLAY_TEXT.equals(line)
            || line.startsWith(FileStatus.DISPLAY_PREFIX);
    }

    /**
     * A {@link SysoutSink} that keeps every line, in order, exactly as it was given.
     *
     * <p>It never deduplicates: consecutive identical lines are the correct output of this program,
     * because the record area is displayed twice per record.
     */
    private static final class CapturingSysout implements SysoutSink {

        /** The lines in emission order. Instance state, so no two tests can see each other's. */
        private final List<String> lines = new ArrayList<>();

        @Override
        public void display(String line) {
            lines.add(line);
        }

        /** @return an unmodifiable snapshot of what was displayed */
        private List<String> lines() {
            return List.copyOf(lines);
        }
    }

    // =============================================================================================
    //  THE CASE SET
    // =============================================================================================

    /** What the twenty case files must be, before any of them is run. */
    @Nested
    @DisplayName("The case set - exactly twenty batch cases for CBACT03C, each with a scenario (G15)")
    class TheCaseSet {

        @Test
        @DisplayName("all twenty name this program, in order, as BATCH_JOB cases over the one DD")
        void allTwentyNameThisProgramAsBatchCasesOverTheOneDd() {
            List<ParityCase> loaded = cases();

            assertThat(loaded).as("gate G15 requires exactly twenty cases per program")
                .hasSize(ParityHarness.CASES_PER_PROGRAM);
            for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
                ParityCase parityCase = loaded.get(ordinal - 1);
                assertThat(parityCase.caseId()).isEqualTo(ParityHarness.caseId(ordinal));
                assertThat(parityCase.program()).isEqualTo(PROGRAM);
                assertThat(parityCase.unitKind()).isEqualTo(UnitKind.BATCH_JOB);
                assertThat(parityCase.inputs().keySet())
                    .as("app/cbl/CBACT03C.cbl:29 declares ONE SELECT and app/jcl/READXREF.jcl:25 "
                        + "ONE input DD, so %s seeds %s and nothing else", parityCase.caseId(), DD)
                    .containsExactly(DD);
            }
        }

        @Test
        @DisplayName("every description is substantive, because a derived expectation must be "
            + "reviewable against the source")
        void everyDescriptionIsSubstantive() {
            for (ParityCase parityCase : cases()) {
                assertThat(parityCase.description())
                    .as("%s must say what it exercises and cite the COBOL it was derived from - "
                        + "that citation is the only thing standing in for a captured baseline "
                        + "(AAP risk R-A)", parityCase.caseId())
                    .isNotBlank()
                    .hasSizeGreaterThan(120);
            }
        }

        @Test
        @DisplayName("no case declares a job parameter, because READXREF.jcl declares no PARM")
        void noCaseDeclaresAJobParameter() {
            for (ParityCase parityCase : cases()) {
                assertThat(parityCase.jobParameters())
                    .as("app/jcl/READXREF.jcl STEP05 is a bare EXEC PGM=CBACT03C with no PARM, and "
                        + "AccountBalanceUpdateJob's constructor refuses a contract that declares "
                        + "one, so %s declares none", parityCase.caseId())
                    .isEmpty();
            }
        }

        @Test
        @DisplayName("no case declares a screen, because CBACT03C contains zero EXEC CICS statements")
        void noCaseDeclaresAScreen() {
            for (ParityCase parityCase : cases()) {
                assertThat(parityCase.screenRequest())
                    .as("%s is a batch case: a batch job has no screen and no AID", parityCase.caseId())
                    .isNull();
                assertThat(parityCase.expectedResponse()).isNull();
            }
        }

        @Test
        @DisplayName("the scenario table reaches all three abend sites and both arms of 9910")
        void theScenarioTableReachesAllThreeAbendSitesAndBothStatusArms() {
            assertThat(SCENARIOS).hasSize(ParityHarness.CASES_PER_PROGRAM);

            List<String> openFailures = new ArrayList<>();
            List<String> readFailures = new ArrayList<>();
            List<String> closeFailures = new ArrayList<>();
            List<String> extendedArm = new ArrayList<>();
            int clean = 0;
            for (Map.Entry<String, Scenario> entry : SCENARIOS.entrySet()) {
                Scenario scenario = entry.getValue();
                String failing = scenario.failingStatus();
                if (failing == null) {
                    clean++;
                } else if (!scenario.openSucceeds()) {
                    openFailures.add(entry.getKey());
                } else if (scenario.readFails()) {
                    readFailures.add(entry.getKey());
                } else {
                    closeFailures.add(entry.getKey());
                }
                if (failing != null && failing.charAt(0) == '9') {
                    extendedArm.add(entry.getKey());
                }
            }

            assertThat(openFailures)
                .as("app/cbl/CBACT03C.cbl:132 - the abend inside 0000-XREFFILE-OPEN")
                .containsExactly("case06", "case07");
            assertThat(readFailures)
                .as("app/cbl/CBACT03C.cbl:113 - the abend inside 1000-XREFFILE-GET-NEXT")
                .containsExactly("case08", "case09", "case10", "case11");
            assertThat(closeFailures)
                .as("app/cbl/CBACT03C.cbl:150 - the abend inside 9000-XREFFILE-CLOSE")
                .containsExactly("case12", "case13");
            assertThat(extendedArm)
                .as("the IO-STAT1 = '9' arm of 9910-DISPLAY-IO-STATUS at :162-168, reached from the "
                    + "OPEN paragraph and from the CLOSE paragraph so the shared paragraph is proved "
                    + "shared rather than incidentally right at one site")
                .containsExactly("case07", "case13");
            assertThat(clean)
                .as("the remaining cases run to completion and differ only in what they seed")
                .isEqualTo(ParityHarness.CASES_PER_PROGRAM
                    - openFailures.size() - readFailures.size() - closeFailures.size());
        }
    }

    // =============================================================================================
    //  THE 36-TO-50 PAD
    // =============================================================================================

    /**
     * The cross-reference fixture's width deviation - gate G16, AAP risk R-F.
     *
     * <p>This is the deviation this class exists to exercise, and the assertions here are what stop
     * it being exercised only by accident.
     */
    @Nested
    @DisplayName("The 36-to-50 FILLER pad - gate G16, risk R-F")
    class TheCardxrefPad {

        @Test
        @DisplayName("the normalisation describes CVACT03Y's absent FILLER and reaches this DD")
        void theNormalisationDescribesCvact03ysAbsentFillerAndReachesThisDd() {
            Normalisation pad = Normalisation.CARDXREF_FILLER_PAD_36_TO_50;

            assertThat(pad.sourceWidth())
                .as("every row of app/data/ASCII/cardxref.txt measures 16 + 9 + 11")
                .isEqualTo(CardXrefRecord.XREF_CARD_NUM_LENGTH + CardXrefRecord.XREF_CUST_ID_LENGTH
                    + CardXrefRecord.XREF_ACCT_ID_LENGTH);
            assertThat(pad.targetWidth())
                .as("app/cpy/CVACT03Y.cpy declares CARD-XREF-RECORD as 50 bytes")
                .isEqualTo(RECORD_LENGTH);
            assertThat(pad.padWidth())
                .as("the shortfall is exactly the FILLER PIC X(14) the data omits")
                .isEqualTo(CardXrefRecord.FILLER_LENGTH);
            assertThat(pad.copybook()).isEqualTo("CVACT03Y");
            assertThat(pad.absentSpan()).contains("FILLER");
            assertThat(pad.appliesTo(DD))
                .as("app/jcl/READXREF.jcl binds the cross-reference cluster to DD %s, so the pad "
                    + "must reach it", DD)
                .isTrue();
        }

        @Test
        @DisplayName("every seeded row of every case arrives at the full fifty bytes")
        void everySeededRowArrivesAtTheFullFiftyBytes() {
            ParityHarness harness = harness();
            for (ParityCase parityCase : cases()) {
                SeededDataset seeded = harness.seed(parityCase).get(DD);
                assertThat(seeded)
                    .as("%s seeds %s, so the harness must have a dataset for it",
                        parityCase.caseId(), DD)
                    .isNotNull();
                assertThat(seeded.recordLength())
                    .as("%s seeds %s at its copybook width", parityCase.caseId(), DD)
                    .isEqualTo(RECORD_LENGTH);
                for (int row = 0; row < seeded.rowCount(); row++) {
                    assertThat(seeded.row(row))
                        .as("row %d of %s is what CardXrefRecord.decode will be handed, and it "
                            + "refuses a short row rather than decoding part of it",
                            row, parityCase.caseId())
                        .hasSize(RECORD_LENGTH);
                }
            }
        }

        @Test
        @DisplayName("the pad supplies fourteen spaces and nothing else")
        void thePadSuppliesFourteenSpacesAndNothingElse() {
            ParityCase padProof = cases().get(13);
            assertThat(padProof.caseId()).isEqualTo("case14");

            List<String> declared = padProof.inputs().get(DD).rows();
            assertThat(declared)
                .as("case14 writes its rows out literally at the 36 characters the shipped data "
                    + "measures, so the pad has something to do")
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row)
                    .hasSize(Normalisation.CARDXREF_FILLER_PAD_36_TO_50.sourceWidth()));

            SeededDataset seeded = harness().seed(padProof).get(DD);
            for (int row = 0; row < declared.size(); row++) {
                assertThat(seeded.row(row))
                    .as("the pad is on the RIGHT and the pad character is a space, which is what "
                        + "COBOL writes into an unset PIC X span")
                    .isEqualTo(declared.get(row) + " ".repeat(CardXrefRecord.FILLER_LENGTH));
            }
        }

        @Test
        @DisplayName("a 36-byte seed WITHOUT the normalisation is refused - the pad is genuinely "
            + "exercised, not incidental")
        void aThirtySixByteSeedWithoutTheNormalisationIsRefused() {
            List<String> shortRows = cases().get(13).inputs().get(DD).rows();

            ParityCase withoutTheNormalisation = new ParityCase(PROGRAM, "case14",
                "The same rows as case14 with the normalisation deleted, which must be refused at "
                    + "seed time rather than repaired: a 36-character row reaching a decoder looks "
                    + "like a decoder defect, and this is what proves the declared pad is what "
                    + "widens the shipped data.",
                UnitKind.BATCH_JOB,
                Map.of(DD, DatasetInput.ofRows(shortRows)),
                Map.of(), null, null, List.of(), List.of(), 0, List.of(), List.of());

            assertThatIllegalArgumentException()
                .as("deleting the normalisation from a case file must fail the case immediately and "
                    + "loudly - which is exactly what makes case14 a proof rather than a decoration")
                .isThrownBy(() -> harness().seed(withoutTheNormalisation))
                .withMessageContaining(Normalisation.CARDXREF_FILLER_PAD_36_TO_50.name())
                .withMessageContaining("CVACT03Y");
        }

        @Test
        @DisplayName("every case that seeds short rows declares the normalisation, and no other case "
            + "declares one")
        void everyCaseThatSeedsShortRowsDeclaresTheNormalisation() {
            int declaringCases = 0;
            for (ParityCase parityCase : cases()) {
                List<DatasetNormalisation> declared = parityCase.normalisations();
                for (DatasetNormalisation normalisation : declared) {
                    assertThat(normalisation.dataset()).isEqualTo(DD);
                    assertThat(normalisation.kind())
                        .isEqualTo(Normalisation.CARDXREF_FILLER_PAD_36_TO_50);
                }
                assertThat(declared)
                    .as("at most one normalisation may apply to one dataset")
                    .hasSizeLessThanOrEqualTo(1);
                if (!declared.isEmpty()) {
                    declaringCases++;
                }
            }
            assertThat(declaringCases)
                .as("sixteen cases seed rows at the shipped 36 bytes and need the pad; the four that "
                    + "do not are case05 and case20, whose rows are written out at the full 50, and "
                    + "case02 and case13, which seed an empty dataset with no row to widen")
                .isEqualTo(16);
        }
    }

    // =============================================================================================
    //  RECORD GEOMETRY
    // =============================================================================================

    /**
     * The 50-byte record, pinned whole - gates G19 and G21.
     *
     * <p>A record missing {@code CVACT03Y}'s trailing {@code FILLER PIC X(14)} is short by exactly
     * that span, so pinning the total width is the only mechanical way to prove the span was emitted
     * at all.
     */
    @Nested
    @DisplayName("Record geometry - 50 bytes with the FILLER present (G19, G21)")
    class RecordGeometry {

        @Test
        @DisplayName("every expected record pins the whole fifty-byte image, not a subset of fields")
        void everyExpectedRecordPinsTheWholeFiftyByteImage() {
            for (ParityCase parityCase : cases()) {
                assertThat(parityCase.expectedFinalState())
                    .as("%s pins one expectation per seeded row", parityCase.caseId())
                    .hasSize(parityCase.expectedDatasets().get(0).rowCount());
                for (ExpectedRecord expectation : parityCase.expectedFinalState()) {
                    assertThat(expectation.dataset()).isEqualTo(DD);
                    assertThat(expectation.expectedBytes())
                        .as("%s row %d must pin the complete image: a byte no expectation covers is a "
                            + "byte that can be wrong while the diff count is zero",
                            parityCase.caseId(), expectation.rowIndex())
                        .isNotNull()
                        .hasSize(RECORD_LENGTH);
                }
            }
        }

        @Test
        @DisplayName("every dataset-level expectation pins the fifty-byte width on the final-state "
            + "channel")
        void everyDatasetExpectationPinsTheFiftyByteWidth() {
            for (ParityCase parityCase : cases()) {
                assertThat(parityCase.expectedDatasets())
                    .as("%s states the dataset's identity independently of any row, which is the only "
                        + "way to assert a width when there is no row to measure - case02 and case13 "
                        + "hold none at all", parityCase.caseId())
                    .hasSize(1);
                ExpectedDataset expectation = parityCase.expectedDatasets().get(0);
                assertThat(expectation.dataset()).isEqualTo(DD);
                assertThat(expectation.channel()).isEqualTo(DatasetChannel.FINAL_STATE);
                assertThat(expectation.recordLength())
                    .as("app/cpy/CVACT03Y.cpy declares 50 bytes and the JCL binds the cluster at that "
                        + "width")
                    .isEqualTo(RECORD_LENGTH);
                assertThat(expectation.rowCount()).isNotNegative();
            }
        }

        @Test
        @DisplayName("the FILLER span is addressable by name and is pinned by the cases that vary it")
        void theFillerSpanIsAddressableByNameAndIsPinned() {
            int pinning = 0;
            for (ParityCase parityCase : cases()) {
                for (ExpectedRecord expectation : parityCase.expectedFinalState()) {
                    String filler = expectation.fields().get("FILLER");
                    if (filler == null) {
                        continue;
                    }
                    pinning++;
                    assertThat(filler)
                        .as("CVACT03Y's single FILLER is addressed as FILLER - not FILLER-1 - and "
                            + "occupies bytes 36 to 49")
                        .hasSize(CardXrefRecord.FILLER_LENGTH);
                    assertThat(expectation.expectedBytes()
                            .substring(CardXrefRecord.FILLER_OFFSET))
                        .as("a field expectation and the whole-image expectation address the same "
                            + "bytes, so they must agree")
                        .isEqualTo(filler);
                }
            }
            assertThat(pinning)
                .as("the FILLER content is a property of the shipped data rather than of the "
                    + "copybook, so at least the cases that vary it must pin it by name")
                .isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("every displayed record line is exactly fifty characters wide")
        void everyDisplayedRecordLineIsExactlyFiftyCharacters() {
            for (ParityCase parityCase : cases()) {
                for (EmittedMessage message : parityCase.expectedMessages()) {
                    assertThat(message.channel())
                        .as("a COBOL DISPLAY is variable width and is its own channel; the 80-byte "
                            + "WS-MESSAGE and the 78-byte ERRMSGO belong to the online programs")
                        .isEqualTo(MessageChannel.DISPLAY_LINE);
                    if (isDiagnosticLine(message.text())) {
                        continue;
                    }
                    assertThat(message.text())
                        .as("DISPLAY CARD-XREF-RECORD names the 01 group item, so it writes all %d "
                            + "bytes of the record area - a translation that dropped the FILLER would "
                            + "emit %d here (%s)", RECORD_LENGTH,
                            RECORD_LENGTH - CardXrefRecord.FILLER_LENGTH, parityCase.caseId())
                        .hasSize(RECORD_LENGTH);
                }
            }
        }
    }

    // =============================================================================================
    //  NO WRITES
    // =============================================================================================

    /**
     * The positive proof that the job whose name says Update writes nothing - AAP rule R1, register
     * 0.8.4, practices B4 and B5.
     */
    @Nested
    @DisplayName("No writes - the name says Update and the source updates nothing")
    class NoWrites {

        @Test
        @DisplayName("no case expects a written record, on any dataset or any channel")
        void noCaseExpectsAWrittenRecord() {
            for (ParityCase parityCase : cases()) {
                assertThat(parityCase.expectedWrites())
                    .as("app/cbl/CBACT03C.cbl:120 issues OPEN INPUT and the program has no WRITE, "
                        + "REWRITE or DELETE in its 178 lines, so %s expects no written record - and "
                        + "the differ reports any observed write no expectation accounts for",
                        parityCase.caseId())
                    .isEmpty();
                for (ExpectedDataset expectation : parityCase.expectedDatasets()) {
                    assertThat(expectation.channel())
                        .as("this program opens no output dataset at all, so asserting one on the "
                            + "writes channel would assert something false")
                        .isNotEqualTo(DatasetChannel.WRITES);
                }
            }
        }

        @Test
        @DisplayName("no run produces a write, and every dataset is left exactly as it was seeded")
        void noRunProducesAWriteAndEveryDatasetIsLeftExactlyAsSeeded() {
            ParityHarness harness = harness();
            for (ParityCase parityCase : cases()) {
                SeededDataset seeded = harness.seed(parityCase).get(DD);
                DecodedFingerprint fingerprint = fingerprintOf(parityCase);

                assertThat(fingerprint.writes())
                    .as("%s must produce no write on any dataset", parityCase.caseId())
                    .isEmpty();

                List<DecodedRecord> left = fingerprint.findFinalState(DD).orElseThrow(
                    () -> new AssertionError("the run must report the state of " + DD
                        + " so that 'unchanged' is an assertion rather than a silence"));
                assertThat(left)
                    .as("%s left %s holding a different number of rows", parityCase.caseId(), DD)
                    .hasSize(seeded.rowCount());
                for (int row = 0; row < seeded.rowCount(); row++) {
                    assertThat(left.get(row).image())
                        .as("row %d of %s must be byte-identical to the seed after %s: a row "
                            + "rewritten in place is invisible to an empty write list",
                            row, DD, parityCase.caseId())
                        .isEqualTo(seeded.row(row));
                    assertThat(left.get(row).length()).isEqualTo(RECORD_LENGTH);
                }
            }
        }

        @Test
        @DisplayName("the repository publishes no write operation for the job to reach")
        void theRepositoryPublishesNoWriteOperationAtAll() {
            List<String> published = new ArrayList<>();
            for (Method method : CardXrefRepository.class.getMethods()) {
                if (FORBIDDEN_WRITE_METHODS.contains(method.getName())) {
                    published.add(method.getName());
                }
            }
            assertThat(published)
                .as("the class name's promise of an update has no implementation to reach for, and "
                    + "that is the structural half of the no-writes proof: no arrangement of this "
                    + "test could make the job write, because there is no operation to call")
                .isEmpty();
        }
    }

    // =============================================================================================
    //  THE ABEND
    // =============================================================================================

    /**
     * {@code 9999-ABEND-PROGRAM} at {@code app/cbl/CBACT03C.cbl:154-158} - gate G35.
     *
     * <p>{@code CALL 'CEE3ABD'} becomes an {@link AbendException} carrying the COBOL
     * {@code RETURN-CODE}, and an abend is an <em>observation</em> here rather than a failure: the
     * lines emitted before it are still part of the fingerprint and the return code is compared like
     * any other expectation.
     */
    @Nested
    @DisplayName("The abend at app/cbl/CBACT03C.cbl:158 - RETURN-CODE 12 (G35)")
    class TheAbend {

        @Test
        @DisplayName("the eight arranged failures expect RETURN-CODE 12 and end with the abend line")
        void theEightArrangedFailuresExpectTwelveAndEndWithTheAbendLine() {
            int failures = 0;
            for (ParityCase parityCase : cases()) {
                Scenario scenario = scenarioFor(parityCase.caseId());
                if (scenario.failingStatus() == null) {
                    continue;
                }
                failures++;
                List<String> lines = expectedLines(parityCase);
                assertThat(parityCase.expectedReturnCode())
                    .as("all three paragraphs move 12 into APPL-RESULT on their failing arm - :101, "
                        + ":124 and :142 - and %s abends carrying it", parityCase.caseId())
                    .isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
                assertThat(lines)
                    .as("%s must end with DISPLAY 'ABENDING PROGRAM' at :155, and must NOT reach the "
                        + "closing banner at :85", parityCase.caseId())
                    .endsWith(AbendException.ABEND_DISPLAY_TEXT)
                    .doesNotContain(AccountBalanceUpdateJob.END_OF_EXECUTION);
                assertThat(lines.get(lines.size() - 2))
                    .as("the status line at :168 or :172 comes immediately before the abend line")
                    .isEqualTo(FileStatus.toDisplayLine(scenario.failingStatus()));
                assertThat(lines.get(0))
                    .as("the opening banner at :71 precedes everything, including a failed OPEN")
                    .isEqualTo(AccountBalanceUpdateJob.START_OF_EXECUTION);
            }
            assertThat(failures)
                .as("two failed opens, four failed reads and two failed closes - the whole of the "
                    + "scenario table's failure half")
                .isEqualTo(8);
        }

        @Test
        @DisplayName("the twelve clean cases expect RETURN-CODE 0 and end with the closing banner")
        void theTwelveCleanCasesExpectZeroAndEndWithTheClosingBanner() {
            int cleanCases = 0;
            for (ParityCase parityCase : cases()) {
                if (scenarioFor(parityCase.caseId()).failingStatus() != null) {
                    continue;
                }
                cleanCases++;
                assertThat(parityCase.expectedReturnCode())
                    .as("RETURN-CODE is never moved anywhere in the program, so :87 GOBACK returns "
                        + "zero for %s", parityCase.caseId())
                    .isEqualTo(AbendException.RETURN_CODE_OK);
                assertThat(expectedLines(parityCase))
                    .as("%s runs to :85", parityCase.caseId())
                    .startsWith(AccountBalanceUpdateJob.START_OF_EXECUTION)
                    .endsWith(AccountBalanceUpdateJob.END_OF_EXECUTION)
                    .doesNotContain(AbendException.ABEND_DISPLAY_TEXT);
            }
            assertThat(cleanCases)
                .as("twenty cases less the eight that arrange a failure")
                .isEqualTo(12);
        }

        @Test
        @DisplayName("a failed OPEN raises AbendException carrying RETURN-CODE 12, ABCODE 999 and "
            + "TIMING 0")
        void aFailedOpenRaisesAbendExceptionCarryingTheCobolReturnCode() {
            BrowseCursor cursor = cursorFor(Scenario.openFails("35"), List.of());
            AccountBalanceUpdateJob job =
                jobOver(repositoryOver(cursor), ParityHarness.FIXTURE_CHARSET);
            CapturingSysout sysout = new CapturingSysout();

            assertThatExceptionOfType(AbendException.class)
                .as("app/cbl/CBACT03C.cbl:132 performs 9999-ABEND-PROGRAM, which does not return")
                .isThrownBy(() -> job.execute(sysout))
                .satisfies(abend -> {
                    assertThat(abend.getReturnCode())
                        .as(":124 moves 12 into APPL-RESULT before the abend")
                        .isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
                    assertThat(abend.getAbendCode())
                        .as(":157 moves 999 into ABCODE")
                        .hasValue(AbendException.STANDARD_ABEND_CODE);
                    assertThat(abend.getTiming())
                        .as(":156 moves 0 into TIMING")
                        .hasValue(AbendException.STANDARD_TIMING);
                    assertThat(abend.getProgram()).isEqualTo(PROGRAM);
                });

            assertThat(sysout.lines())
                .as("the three lines :129 to :132 emit, after the opening banner - and nothing else, "
                    + "because a failed OPEN reaches neither the loop nor the closing banner")
                .containsExactly(
                    AccountBalanceUpdateJob.START_OF_EXECUTION,
                    AccountBalanceUpdateJob.ERROR_OPENING_XREFFILE,
                    FileStatus.toDisplayLine("35"),
                    AbendException.ABEND_DISPLAY_TEXT);
        }

        @Test
        @DisplayName("a failed OPEN never reads and never closes")
        void aFailedOpenNeverReadsAndNeverCloses() {
            BrowseCursor cursor = cursorFor(Scenario.openFails("35"), List.of());
            AccountBalanceUpdateJob job =
                jobOver(repositoryOver(cursor), ParityHarness.FIXTURE_CHARSET);

            assertThatExceptionOfType(AbendException.class)
                .isThrownBy(() -> job.execute(new CapturingSysout()));

            verify(cursor, never())
                .readNext();
            verify(cursor, never())
                .closeBrowse();
        }

        @Test
        @DisplayName("a failed READ releases the cursor exactly once, and silently")
        void aFailedReadReleasesTheCursorExactlyOnceAndSilently() {
            String image = cases().get(2).expectedFinalState().get(0).expectedBytes();
            FixedWidthCodec codec = new FixedWidthCodec(ParityHarness.FIXTURE_CHARSET);
            BrowseCursor cursor = cursorFor(Scenario.readFailsAfter(1, "30"), List.of(
                ReadResult.found(DD, recordOf(image, codec), image),
                ReadResult.other(DD, "30")));
            AccountBalanceUpdateJob job =
                jobOver(repositoryOver(cursor), ParityHarness.FIXTURE_CHARSET);
            CapturingSysout sysout = new CapturingSysout();

            assertThatExceptionOfType(AbendException.class)
                .isThrownBy(() -> job.execute(sysout));

            verify(cursor, times(1))
                .closeBrowse();
            assertThat(sysout.lines())
                .as("the release emits no line: app/cbl/CBACT03C.cbl abends outright without "
                    + "performing 9000-XREFFILE-CLOSE, and the record it did read is still displayed "
                    + "twice")
                .containsExactly(
                    AccountBalanceUpdateJob.START_OF_EXECUTION,
                    image,
                    image,
                    AccountBalanceUpdateJob.ERROR_READING_XREFFILE,
                    FileStatus.toDisplayLine("30"),
                    AbendException.ABEND_DISPLAY_TEXT);
        }
    }

    // =============================================================================================
    //  THE FILE STATUS LADDER
    // =============================================================================================

    /**
     * {@code 9910-DISPLAY-IO-STATUS} at {@code app/cbl/CBACT03C.cbl:161-174} and the two-status
     * ladders that reach it - gate G47.
     */
    @Nested
    @DisplayName("The FILE STATUS ladder - both arms of 9910-DISPLAY-IO-STATUS (G47)")
    class TheFileStatusLadder {

        @Test
        @DisplayName("every arranged status renders through the one owner of the line's shape")
        void everyArrangedStatusRendersThroughTheOneOwner() {
            for (ParityCase parityCase : cases()) {
                String failing = scenarioFor(parityCase.caseId()).failingStatus();
                List<String> statusLines = new ArrayList<>();
                for (String line : expectedLines(parityCase)) {
                    if (line.startsWith(FileStatus.DISPLAY_PREFIX)) {
                        statusLines.add(line);
                    }
                }
                if (failing == null) {
                    assertThat(statusLines)
                        .as("%s reaches no failing arm, so 9910-DISPLAY-IO-STATUS is never performed",
                            parityCase.caseId())
                        .isEmpty();
                    continue;
                }
                assertThat(statusLines)
                    .as("%s performs 9910-DISPLAY-IO-STATUS exactly once", parityCase.caseId())
                    .containsExactly(FileStatus.toDisplayLine(failing));
                assertThat(statusLines.get(0))
                    .as("the NNNN in the literal is part of the COBOL text at :168 and :172, not a "
                        + "placeholder awaiting substitution")
                    .hasSize(FileStatus.DISPLAY_PREFIX.length() + FileStatus.STATUS_IMAGE_LENGTH)
                    .startsWith(FileStatus.DISPLAY_PREFIX);
            }
        }

        @Test
        @DisplayName("the extended arm renders the second byte as its code point, not as its digit")
        void theExtendedArmRendersTheSecondByteAsItsCodePoint() {
            assertThat(expectedLines(cases().get(6)))
                .as("case07 fails the OPEN with '92': IO-STAT1 is '9', so :162 takes the extended "
                    + "arm, IO-STAT2 goes through the TWO-BYTES-ALPHA REDEFINES at :54-56, and the "
                    + "code point of '2' is 50 - so the image is 9050 and NOT 0092")
                .contains(FileStatus.DISPLAY_PREFIX + "9050")
                .doesNotContain(FileStatus.DISPLAY_PREFIX + "0092");
            assertThat(expectedLines(cases().get(12)))
                .as("case13 fails the CLOSE with '96', reaching the same arm from a different "
                    + "paragraph: the code point of '6' is 54, so the image is 9054")
                .contains(FileStatus.DISPLAY_PREFIX + "9054")
                .doesNotContain(FileStatus.DISPLAY_PREFIX + "0096");
        }

        @Test
        @DisplayName("every status the program does not name is fatal, including two that are "
            + "ordinary branches online")
        void everyStatusTheProgramDoesNotNameIsFatal() {
            for (ParityCase parityCase : cases()) {
                String failing = scenarioFor(parityCase.caseId()).failingStatus();
                if (failing == null) {
                    continue;
                }
                assertThat(failing)
                    .as("only '00' at :94 and '10' at :98 are named; everything else falls to :101, "
                        + ":124 or :142")
                    .isNotEqualTo(FileStatus.OK)
                    .isNotEqualTo(FileStatus.END_OF_FILE);
            }
            assertThat(scenarioFor("case10").failingReadStatus())
                .as("'23' is DFHRESP(NOTFND) online and a normal branch there; here it abends")
                .isEqualTo(FileStatus.NOT_FOUND);
            assertThat(scenarioFor("case11").failingReadStatus())
                .as("'22' is DUPKEY online and a normal branch there; here it abends")
                .isEqualTo(FileStatus.DUPLICATE);
            assertThat(scenarioFor("case09").failingReadStatus())
                .as("'04' is a record whose length disagrees with the copybook")
                .isEqualTo(FileStatus.RECORD_LENGTH_CONFLICT);
        }
    }

    // =============================================================================================
    //  THE DOUBLED DISPLAY
    // =============================================================================================

    /**
     * The two {@code DISPLAY CARD-XREF-RECORD} statements - {@code :96} inside
     * {@code 1000-XREFFILE-GET-NEXT} and {@code :78} in the mainline loop.
     *
     * <p>A translation that emitted one line per record would look entirely reasonable and be wrong
     * for every record of every case, which is why the count, the pairing and the byte identity of
     * each pair are all pinned.
     */
    @Nested
    @DisplayName("The doubled DISPLAY - :96 then :78, so every record appears twice")
    class TheDoubledDisplay {

        @Test
        @DisplayName("a clean run emits two banners and two lines per record")
        void aCleanRunEmitsTwoBannersAndTwoLinesPerRecord() {
            for (ParityCase parityCase : cases()) {
                if (scenarioFor(parityCase.caseId()).failingStatus() != null) {
                    continue;
                }
                int rows = parityCase.expectedFinalState().size();
                assertThat(parityCase.expectedMessages())
                    .as("%s seeds %d row(s), so it emits %d banner(s) plus %d display(s) per record",
                        parityCase.caseId(), rows, AccountBalanceUpdateJob.BANNER_LINES,
                        AccountBalanceUpdateJob.DISPLAYS_PER_RECORD)
                    .hasSize(AccountBalanceUpdateJob.BANNER_LINES
                        + AccountBalanceUpdateJob.DISPLAYS_PER_RECORD * rows);
            }
        }

        @Test
        @DisplayName("the two lines of each pair are byte-identical and are the row's own bytes")
        void theTwoLinesOfEachPairAreByteIdenticalAndAreTheRowsOwnBytes() {
            ParityHarness harness = harness();
            for (ParityCase parityCase : cases()) {
                if (scenarioFor(parityCase.caseId()).failingStatus() != null) {
                    continue;
                }
                SeededDataset seeded = harness.seed(parityCase).get(DD);
                List<String> lines = expectedLines(parityCase);
                for (int row = 0; row < seeded.rowCount(); row++) {
                    int first = 1 + AccountBalanceUpdateJob.DISPLAYS_PER_RECORD * row;
                    assertThat(lines.get(first))
                        .as("line %d of %s is the :96 display of row %d, which READ ... INTO filled "
                            + "with the row's own 50 bytes", first, parityCase.caseId(), row)
                        .isEqualTo(seeded.row(row));
                    assertThat(lines.get(first + 1))
                        .as("line %d is the :78 display of the same unchanged record area",
                            first + 1)
                        .isEqualTo(lines.get(first));
                }
            }
        }

        @Test
        @DisplayName("a failing read contributes no line, because :96 is on the '00' arm only")
        void aFailingReadContributesNoLine() {
            for (ParityCase parityCase : cases()) {
                Scenario scenario = scenarioFor(parityCase.caseId());
                if (!scenario.readFails()) {
                    continue;
                }
                assertThat(expectedLines(parityCase))
                    .as("%s reads %d record(s) successfully before the failure, so it emits one "
                        + "banner, %d record line(s) and the three failure lines - the failing read "
                        + "itself adds none, because it never reaches :96 and the abend never "
                        + "reaches :78", parityCase.caseId(), scenario.failingRead(),
                        AccountBalanceUpdateJob.DISPLAYS_PER_RECORD * scenario.failingRead())
                    .hasSize(1 + AccountBalanceUpdateJob.DISPLAYS_PER_RECORD * scenario.failingRead()
                        + 3);
            }
        }
    }
}
