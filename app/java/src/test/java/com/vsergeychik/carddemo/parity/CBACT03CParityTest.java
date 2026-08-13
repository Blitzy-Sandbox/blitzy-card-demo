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
 * The parity gate for {@code CBACT03C}: twenty declarative cases, judged field by field, with a required
 * diff count of zero on every one of them.
 */
@DisplayName("CBACT03C parity - 20 derived cases over AccountBalanceUpdateJob, which updates nothing")
class CBACT03CParityTest {
    private static final String PROGRAM = "CBACT03C";

    private static final String DD = AccountBalanceUpdateJob.XREFFILE_DD_NAME;

    private static final String OPEN_SITE = "OPEN-" + AccountBalanceUpdateJob.XREFFILE_DD_NAME;

    private static final String READ_SITE = "READ-" + AccountBalanceUpdateJob.XREFFILE_DD_NAME;

    private static final String CLOSE_SITE = "CLOSE-" + AccountBalanceUpdateJob.XREFFILE_DD_NAME;

    private static final int RECORD_LENGTH = CardXrefRecord.RECORD_LENGTH;

    private static final String TEST_DSNAME = "CARDDEMO.TEST.CARDXREF.VSAM.KSDS";

    private static final String ROW_SUBJECT = "a seeded " + PROGRAM + " cross-reference row";

    private static final Set<String> FORBIDDEN_WRITE_METHODS = Set.of(
        "write", "writeRecord", "rewrite", "rewriteRecord", "delete", "deleteRecord",
        "add", "addRecord", "insert", "update", "save", "upsert", "merge", "put");

    private record Scenario(String openStatus, String closeStatus, int failingRead,
                            String failingReadStatus) {
        private static final int NO_FAILING_READ = -1;

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

        private static Scenario clean() {
            return new Scenario(FileStatus.OK, FileStatus.OK, NO_FAILING_READ, null);
        }

        private static Scenario openFails(String status) {
            return new Scenario(status, FileStatus.OK, NO_FAILING_READ, null);
        }

        private static Scenario readFailsAfter(int afterRecords, String status) {
            return new Scenario(FileStatus.OK, FileStatus.OK, afterRecords, status);
        }

        private static Scenario closeFails(String status) {
            return new Scenario(FileStatus.OK, status, NO_FAILING_READ, null);
        }

        private boolean openSucceeds() {
            return FileStatus.isOk(openStatus);
        }

        private boolean readFails() {
            return failingRead != NO_FAILING_READ;
        }

        private boolean closeFails() {
            return !FileStatus.isOk(closeStatus);
        }

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

    private static Scenario scenarioFrom(ParityCase parityCase) {
        return scenarioFrom(parityCase.unitStimulus());
    }

    private static Scenario scenarioFrom(ParityCase.UnitStimulus stimulus) {
        if (!stimulus.operationScript().isEmpty() || !stimulus.linkage().isEmpty()
            || !stimulus.stepStatuses().isEmpty() || !stimulus.environment().isEmpty()) {
            throw new IllegalArgumentException(PROGRAM + " calls no subprogram, takes no linkage, "
                + "follows no conditional job step and runs under no environmental variant: its only "
                + "stimulus is its seeded rows and the outcome of one of its three call sites.");
        }
        String openStatus = FileStatus.OK;
        String closeStatus = FileStatus.OK;
        int failingRead = Scenario.NO_FAILING_READ;
        String failingReadStatus = null;
        for (Map.Entry<String, ParityCase.CallSiteOutcome> declared
            : stimulus.callSiteOutcomes().entrySet()) {
            String site = declared.getKey();
            ParityCase.CallSiteOutcome outcome = declared.getValue();
            String status = requireStatusOutcome(site, outcome);
            switch (site) {
                case OPEN_SITE -> openStatus = status;
                case CLOSE_SITE -> closeStatus = status;
                case READ_SITE -> {
                    failingRead = outcome.recordsBefore();
                    failingReadStatus = status;
                }
                default -> throw new IllegalArgumentException("Call site " + site + " is not one of "
                    + PROGRAM + "'s three: " + OPEN_SITE + ", " + READ_SITE + " and " + CLOSE_SITE
                    + ". A site nothing answers to would arrange nothing, and the case would assert "
                    + "the opposite of what it says.");
            }
        }
        return new Scenario(openStatus, closeStatus, failingRead, failingReadStatus);
    }

    private static String requireStatusOutcome(String site, ParityCase.CallSiteOutcome outcome) {
        if (outcome.status() == null) {
            throw new IllegalArgumentException("Call site " + site + " declares no FILE STATUS. "
                + PROGRAM + " is a batch program whose every I/O verb reports a two-character status "
                + "into XREFFILE-STATUS, so a RESP or a bare refusal has nothing to be read as.");
        }
        return outcome.status();
    }

    private static Map<String, Scenario> shippedScenarios() {
        Map<String, Scenario> declared = new LinkedHashMap<>();
        for (ParityCase parityCase : ParityHarness.casesOf(PROGRAM)) {
            declared.put(parityCase.caseId(), scenarioFrom(parityCase));
        }
        return Collections.unmodifiableMap(declared);
    }

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

    private static AccountBalanceUpdateJob jobOver(CardXrefRepository repository, Charset charset) {
        return new AccountBalanceUpdateJob(batchConfig(), repository, charset,
            new NoBeanPublished<>());
    }

    private static CardXrefRecord recordOf(String image, FixedWidthCodec codec) {
        return CardXrefRecord.decode(codec.encodeImage(image, ROW_SUBJECT), codec);
    }

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

    private static CardXrefRepository repositoryOver(BrowseCursor cursor) {
        CardXrefRepository repository = mock(CardXrefRepository.class);
        when(repository.addressing(any(), any(), any(), any())).thenReturn(repository);
        when(repository.openBrowse()).thenReturn(cursor);
        return repository;
    }

    private static UnitOutcome runProgram(Invocation invocation) {
        SeededDataset seeded = invocation.dataset(DD);
        UnitOutcome.Builder recorder = invocation.recorder();
        recorder.finalStateUnchanged(seeded, CardXrefRecord.LAYOUT);

        Scenario scenario = scenarioFrom(invocation.stimulus());
        BrowseCursor cursor = cursorFor(scenario, readsFor(scenario, seeded, invocation.codec()));
        ExecutionSummary summary = jobOver(repositoryOver(cursor), invocation.charset())
            .execute(recorder::display);

        recorder.returnCode(summary.returnCode());
        return null;
    }

    private static ParityHarness harness() {
        return ParityHarness.usAscii();
    }

    static List<ParityCase> cases() {
        List<ParityCase> loaded = ParityHarness.casesOf(PROGRAM);
        if (loaded.size() != ParityHarness.CASES_PER_PROGRAM) {
            throw new IllegalStateException("Program " + PROGRAM + " supplied " + loaded.size()
                + " case(s) where the gate requires exactly " + ParityHarness.CASES_PER_PROGRAM
                + ". A short set is not a smaller gate, it is a gate that passes without asking the "
                + "questions.");
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
            scenarioFrom(parityCase);
        }
        return loaded;
    }

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

    private static DecodedFingerprint fingerprintOf(ParityCase parityCase) {
        return harness().run(parityCase, UnitKind.BATCH_JOB, CBACT03CParityTest::runProgram);
    }

    private static List<String> expectedLines(ParityCase parityCase) {
        List<String> lines = new ArrayList<>(parityCase.expectedMessages().size());
        for (EmittedMessage message : parityCase.expectedMessages()) {
            lines.add(message.text());
        }
        return List.copyOf(lines);
    }

    private static boolean isDiagnosticLine(String line) {
        return AccountBalanceUpdateJob.START_OF_EXECUTION.equals(line)
            || AccountBalanceUpdateJob.END_OF_EXECUTION.equals(line)
            || AccountBalanceUpdateJob.ERROR_OPENING_XREFFILE.equals(line)
            || AccountBalanceUpdateJob.ERROR_READING_XREFFILE.equals(line)
            || AccountBalanceUpdateJob.ERROR_CLOSING_XREFFILE.equals(line)
            || AbendException.ABEND_DISPLAY_TEXT.equals(line)
            || line.startsWith(FileStatus.DISPLAY_PREFIX);
    }

    private static final class CapturingSysout implements SysoutSink {
        private final List<String> lines = new ArrayList<>();

        @Override
        public void display(String line) {
            lines.add(line);
        }

        private List<String> lines() {
            return List.copyOf(lines);
        }
    }

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
            Map<String, Scenario> scenarios = shippedScenarios();

            assertThat(scenarios).hasSize(ParityHarness.CASES_PER_PROGRAM);

            List<String> openFailures = new ArrayList<>();
            List<String> readFailures = new ArrayList<>();
            List<String> closeFailures = new ArrayList<>();
            List<String> extendedArm = new ArrayList<>();
            int clean = 0;
            for (Map.Entry<String, Scenario> entry : scenarios.entrySet()) {
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
                .as("app/cbl/CBACT03C.cbl:132 - the abend inside 0000-XREFFILE-OPEN, reached with "
                    + "three different statuses so the rendered image is proved to track the "
                    + "reported one")
                .containsExactly("case06", "case07", "case18");
            assertThat(readFailures)
                .as("app/cbl/CBACT03C.cbl:113 - the abend inside 1000-XREFFILE-GET-NEXT")
                .containsExactly("case08", "case09", "case10", "case11", "case15", "case16");
            assertThat(closeFailures)
                .as("app/cbl/CBACT03C.cbl:150 - the abend inside 9000-XREFFILE-CLOSE, reached after "
                    + "two records, after none, and after the whole fifty-row file")
                .containsExactly("case12", "case13", "case19");
            assertThat(extendedArm)
                .as("the IO-STAT1 = '9' arm of 9910-DISPLAY-IO-STATUS at :162-168, reached from the "
                    + "OPEN paragraph, the READ paragraph and the CLOSE paragraph so the shared "
                    + "paragraph is proved shared at every one of its three call sites rather than "
                    + "incidentally right at one")
                .containsExactly("case07", "case13", "case16");
            assertThat(clean)
                .as("the remaining cases run to completion and differ only in what they seed")
                .isEqualTo(ParityHarness.CASES_PER_PROGRAM
                    - openFailures.size() - readFailures.size() - closeFailures.size());
        }
    }

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

    @Nested
    @DisplayName("The abend at app/cbl/CBACT03C.cbl:158 - RETURN-CODE 12 (G35)")
    class TheAbend {
        @Test
        @DisplayName("the twelve arranged failures expect RETURN-CODE 12 and end with the abend "
            + "line")
        void theTwelveArrangedFailuresExpectTwelveAndEndWithTheAbendLine() {
            int failures = 0;
            for (ParityCase parityCase : cases()) {
                Scenario scenario = scenarioFrom(parityCase);
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
                .as("three failed opens, six failed reads and three failed closes - the whole of "
                    + "the "
                    + "scenario table's failure half")
                .isEqualTo(12);
        }

        @Test
        @DisplayName("the eight clean cases expect RETURN-CODE 0 and end with the closing banner")
        void theEightCleanCasesExpectZeroAndEndWithTheClosingBanner() {
            int cleanCases = 0;
            for (ParityCase parityCase : cases()) {
                if (scenarioFrom(parityCase).failingStatus() != null) {
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
                .as("twenty cases less the twelve that arrange a failure")
                .isEqualTo(8);
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

    @Nested
    @DisplayName("The FILE STATUS ladder - both arms of 9910-DISPLAY-IO-STATUS (G47)")
    class TheFileStatusLadder {
        @Test
        @DisplayName("every arranged status renders through the one owner of the line's shape")
        void everyArrangedStatusRendersThroughTheOneOwner() {
            for (ParityCase parityCase : cases()) {
                String failing = scenarioFrom(parityCase).failingStatus();
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
                String failing = scenarioFrom(parityCase).failingStatus();
                if (failing == null) {
                    continue;
                }
                assertThat(failing)
                    .as("only '00' at :94 and '10' at :98 are named; everything else falls to :101, "
                        + ":124 or :142")
                    .isNotEqualTo(FileStatus.OK)
                    .isNotEqualTo(FileStatus.END_OF_FILE);
            }
            assertThat(shippedScenarios().get("case10").failingReadStatus())
                .as("'23' is DFHRESP(NOTFND) online and a normal branch there; here it abends")
                .isEqualTo(FileStatus.NOT_FOUND);
            assertThat(shippedScenarios().get("case11").failingReadStatus())
                .as("'22' is DUPKEY online and a normal branch there; here it abends")
                .isEqualTo(FileStatus.DUPLICATE);
            assertThat(shippedScenarios().get("case09").failingReadStatus())
                .as("'04' is a record whose length disagrees with the copybook")
                .isEqualTo(FileStatus.RECORD_LENGTH_CONFLICT);
        }
    }

    @Nested
    @DisplayName("The doubled DISPLAY - :96 then :78, so every record appears twice")
    class TheDoubledDisplay {
        @Test
        @DisplayName("a clean run emits two banners and two lines per record")
        void aCleanRunEmitsTwoBannersAndTwoLinesPerRecord() {
            for (ParityCase parityCase : cases()) {
                if (scenarioFrom(parityCase).failingStatus() != null) {
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
                if (scenarioFrom(parityCase).failingStatus() != null) {
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
                Scenario scenario = scenarioFrom(parityCase);
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
