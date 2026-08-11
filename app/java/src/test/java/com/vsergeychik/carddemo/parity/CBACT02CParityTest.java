package com.vsergeychik.carddemo.parity;

import com.vsergeychik.carddemo.account.AccountBalanceReaderJob;
import com.vsergeychik.carddemo.account.AccountBalanceReaderJob.SysoutSink;
import com.vsergeychik.carddemo.card.CardRepository;
import com.vsergeychik.carddemo.card.CardRepository.BrowseDirection;
import com.vsergeychik.carddemo.card.CardRepository.CardBrowse;
import com.vsergeychik.carddemo.card.CardRepository.CardReadResult;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobContracts;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The twenty-case parity gate for {@code app/cbl/CBACT02C.cbl}, judged field by field against
 * {@link AccountBalanceReaderJob}.
 *
 * <h2>The baseline is statically derived, and was never captured</h2>
 * <p><strong>This is the single most important thing to know about every expected value behind this
 * class.</strong> The stated success criterion is a regression baseline produced by <em>running</em>
 * the twenty-eight legacy programs and diffing the Java against it. That is empirically impossible
 * in this environment, and the Agent Action Plan records eight independently verified blockers for
 * it in section 0.7.6: there is no z/OS runtime; the available COBOL compiler reports
 * {@code indexed file handler : disabled}, which rules out the seven programs declaring
 * {@code ORGANIZATION INDEXED} - {@code CBACT02C} among them, at
 * {@code app/cbl/CBACT02C.cbl:30}; a subprogram with a {@code PROCEDURE ... USING} clause cannot be
 * linked as an executable; {@code app/cpy/CUSTREC.cpy} fails to parse; no Language Environment
 * {@code CEE*} service exists, so {@code CALL 'CEE3ABD'} at {@code :158} could not be reached even
 * if the rest worked; there is no CICS emulator; the EBCDIC fixtures need binary handling the
 * compiler is not configured for; and no alternative compiler is installable.
 *
 * <p>So every expectation in {@code src/test/resources/parity/CBACT02C/} was <strong>derived by
 * reading the source</strong> - paragraph by paragraph through
 * {@code app/cbl/CBACT02C.cbl:70-174} - and cross-checked against four authoritative artefacts:
 * {@code app/cpy/CVACT02Y.cpy} for the byte layout, {@code app/jcl/READCARD.jcl} for the step, the
 * DD names and the absence of a {@code PARM}, {@code app/csd/CARDDEMO.CSD} for the dataset the DD
 * resolves to, and {@code app/data/ASCII/carddata.txt} for the input rows. Provenance is the only
 * thing that changed: twenty cases, field-by-field diffing and a diff count that must be zero all
 * survive intact. This is recorded as risk R-A and is escalated rather than absorbed
 * (practice B12).
 *
 * <p>A statically derived expectation can encode a misreading, which a captured one cannot, so three
 * mitigations are built into this class rather than left as intentions. The record bytes are the
 * fixture's own and are never retyped. Every literal the run is compared against - the banners, the
 * three error texts, the abend text, the status images - is asserted here against the constant the
 * production code emits it from, while the case files state the same literal as text, so the two
 * agree only if both readings of the COBOL agree. And {@link #aPerturbedRecordLineIsRejected()}
 * proves the comparison has teeth by breaking one byte of one expectation and requiring the gate to
 * fail.
 *
 * <h2>The class under test is named for something it does not do</h2>
 * <p>{@code CBACT02C}'s own header reads {@code Function : Read and print card data file}, and the
 * source bears that out: {@code app/cbl/CBACT02C.cbl:29} selects {@code CARDFILE},
 * {@code app/jcl/READCARD.jcl:25-26} binds that DD to the card master, and
 * {@code COPY CVACT02Y} at {@code :45} brings in the 150-byte card record. The prompt-mandated Java
 * name is {@code AccountBalanceReaderJob}. It reads the <strong>card</strong> file, not the account
 * file; it computes no balance; and it writes nothing at all, because {@code :120} opens the file
 * {@code INPUT} and the program contains no {@code WRITE}, {@code REWRITE} or {@code DELETE}.
 *
 * <p>The divergence is <strong>documented and preserved, never corrected</strong>. Rule R1 of the
 * plan is that a name comes from the prompt and behaviour comes from the source, and section 0.8.4
 * registers this entry among sixteen like it. Renaming the class would contradict the prompt;
 * implementing anything the name suggests would be a new feature and a parity violation. So this
 * class asserts the reading-and-printing behaviour the source has, and every one of the twenty cases
 * additionally proves the absence of a write through
 * {@link #requireNoWriteWasAttempted(CardRepository)} - the strongest available statement that the
 * name misdescribes the code rather than the other way round (practices B4 and B5).
 *
 * <h2>What the fingerprint is</h2>
 * <p>{@code CBACT02C} writes no dataset, so its entire observable output is the ordered sequence of
 * {@code DISPLAY} lines plus the {@code RETURN-CODE}. Both channels are compared, and
 * {@code expectedWrites} and {@code expectedFinalState} are empty in all twenty cases - not omitted
 * for convenience, but because {@link FieldDiffer} traverses each channel in both directions and
 * reports any dataset or row the case did not pin, so an empty expectation is the assertion that the
 * program produced nothing there.
 *
 * <p>The lines themselves are unusually load-bearing for a batch program. {@code DISPLAY CARD-RECORD}
 * at {@code :78} names the {@code 01} group item, so it emits the whole 150-byte record area
 * including the {@code FILLER PIC X(59)} that {@code app/cpy/CVACT02Y.cpy} ends with. Every record
 * line in every case is therefore an exact 150-character assertion that the width is right and that
 * the {@code FILLER} span is present - gates G19 and G21 - without a single dataset write to inspect.
 * {@code :96} is a <em>commented-out</em> second {@code DISPLAY} and contributes nothing; a
 * translation that revived it would emit two lines per record and fail every case here.
 *
 * <p>The status line is emitted through {@link FileStatus#toDisplayLine(String)}, which owns the
 * whole of {@code 9910-DISPLAY-IO-STATUS} ({@code :161-174}) for the sixteen sites across this
 * estate that emit it. The trailing {@code NNNN} of {@code "FILE STATUS IS: NNNN"} really is part of
 * the COBOL literal at {@code :168} and {@code :172}; the four characters that follow it are the
 * {@code IO-STATUS-04} image.
 *
 * <h2>Nothing sits between the assertion and the program</h2>
 * <p>The unit kind is {@link ParityCase.UnitKind#BATCH_JOB} and the program body is reached through
 * {@link AccountBalanceReaderJob#execute(SysoutSink)}, which is the tasklet's own body. There is no
 * {@code JobLauncher}, no job repository, no {@code ApplicationContext}, no HTTP and no backend
 * anywhere in the path, so step sequencing and line ordering are observed exactly as written
 * (gate G51). {@code SYSOUT} - {@code app/jcl/READCARD.jcl:27} - is the seam: the sink handed to
 * {@code execute} writes each line straight into the harness recorder, which is what makes the line
 * sequence survive the abend that ends eight of the twenty cases.
 *
 * <h2>Why the failure paths are driven by a stub</h2>
 * <p>Eight cases turn on a status the seeded data cannot produce: an {@code OPEN} that refuses, a
 * {@code READ} that reports {@code '23'}, a {@code CLOSE} that fails. A controller case would
 * declare those through {@code screenRequest.forcedOutcomes}, but {@link ParityCase} refuses a
 * {@code screenRequest} on a batch case - a batch job has no screen - so the only place the shape of
 * a run can be declared is here, in the class the harness delegates construction to. {@link Scenario}
 * therefore maps each {@code caseId} to the responses the repository reports. A case identifier is an
 * input identity and never an expectation: no expected line, record or return code is read on the
 * way in, and the {@link ParityHarness.Invocation} a unit receives structurally cannot reach one.
 *
 * <h2>Deliberately not sharing a base class</h2>
 * <p>{@code CBACT01C}, {@code CBACT02C}, {@code CBACT03C} and {@code CBCUS01C} are near-identical
 * read-and-print programs, and the production side does share a template for them. This test does
 * not, and that is a decision rather than an omission: each drives a different dataset at a
 * different record width - 300, 150, 50 and 500 bytes - and a shared base would report a broken
 * geometry against whichever program happened to be running rather than against the one whose
 * layout is wrong. A parity failure has to name its own program.
 *
 * <h2>Standards this class is held to</h2>
 * <p>{@code review_rules} reports <strong>no user rules provided</strong> for this project. Their
 * absence is not permission to lower the bar, so the binding standard is the enterprise practice set
 * B1-B12 of the plan: exact pinned dependency versions and nothing outside the closed set - JUnit 5,
 * AssertJ, Mockito and Spring Test only, with no Lombok and no Testcontainers (B1, B2); the
 * reference trees under {@code app/cbl}, {@code app/cpy}, {@code app/jcl} and {@code app/data} read
 * and never written (B3); the naming divergence documented rather than quietly fixed (B4, B5);
 * a deterministic, non-interactive run with no watch mode and no clock or ordering dependence
 * (B7, gate G54); the code page always named and never taken from the platform, no wildcard import,
 * no {@code double} or {@code float} anywhere near a {@code PIC 9(n)V99} span, and no dataset name
 * literal beyond the deliberately synthetic {@link #PARITY_DSNAME} (B8, gates G22, G46, G52); no
 * static mutable state, so the twenty cases are order-independent (B9, gate G53); the cases shipped
 * with the implementation rather than after it (B10); and the environmental limit on the baseline
 * documented and escalated rather than absorbed (B12).
 *
 * @see AccountBalanceReaderJob the translation under test
 * @see ParityHarness which seeds each case, invokes this class's adapter and captures the run
 * @see FieldDiffer which decides, field by field, whether the run matched the case
 */
@DisplayName("CBACT02C parity - read and print the card data file (app/jcl/READCARD.jcl STEP05)")
final class CBACT02CParityTest {

    /**
     * The program these twenty cases pin, taken from the class under test so the name, the
     * {@code parity/CBACT02C/} resource directory and the {@code PROGRAM-ID} at
     * {@code app/cbl/CBACT02C.cbl:23} cannot drift apart.
     */
    private static final String PROGRAM = AccountBalanceReaderJob.PROGRAM_ID;

    /**
     * A deliberately synthetic dataset name for the {@code CARDFILE} binding.
     *
     * <p>{@code app/jcl/READCARD.jcl:26} names the real dataset and
     * {@code src/main/resources/application.yml} carries it as configuration; neither value appears
     * in Java, here or anywhere else in the module (gate G46). This string exists only because
     * {@code AccountBalanceReaderJob}'s constructor proves that its own {@code CARDFILE} DD and the
     * {@code CARDDAT} key its repository is bound to resolve to one dataset, and that comparison
     * needs two resolvable names. Its qualifiers say what it is - a parity harness's stand-in - so it
     * can never be mistaken for the production name, and it deliberately reproduces none of the
     * high-level qualifiers the real datasets in {@code app/jcl} carry.
     */
    private static final String PARITY_DSNAME = "CARDDEMO.PARITY.CBACT02C.CARDFILE";

    /**
     * The record format the {@code CARDFILE} binding declares for this run: fixed blocked.
     *
     * <p>The CICS definition of the same dataset declares {@code RECORDFORMAT(V)} while the batch
     * JCL declares a fixed record, and the repository layer treats the length as copybook-fixed
     * either way, so the value affects nothing this class observes. It is stated rather than left
     * null because a binding that declared no format would be a shape the configuration never
     * produces.
     */
    private static final String RECORD_FORMAT = "FB";

    /**
     * The copybook the {@code CARDFILE} binding names: {@code app/cpy/CVACT02Y.cpy}, brought into the
     * program by {@code COPY CVACT02Y.} at {@code app/cbl/CBACT02C.cbl:45}.
     */
    private static final String COPYBOOK = "CVACT02Y";

    /**
     * The key offset of the {@code CARDDAT} base cluster: zero, because {@code CARD-NUM} is the first
     * field of {@code CVACT02Y} and {@code app/cbl/CBACT02C.cbl:32} declares
     * {@code RECORD KEY IS FD-CARD-NUM} over the leading sixteen bytes.
     */
    private static final Integer KEY_OFFSET = Integer.valueOf(CardRecord.CARD_NUM_OFFSET);

    /**
     * The sentinel meaning "deliver every seeded row before the terminating read", used by every
     * {@link Scenario} whose read loop runs to the end of its input.
     *
     * <p>Negative so it can never be mistaken for a row count, and resolved against the seeded
     * dataset rather than against a number written twice.
     */
    private static final int EVERY_SEEDED_ROW = -1;

    /**
     * The width of the {@code PIC S9(10)V99} image placed in the {@code FILLER} span of the
     * overpunch cases: eleven digit positions plus the trailing byte that carries both the sign and
     * the low-order digit.
     */
    private static final int MONETARY_IMAGE_LENGTH = 12;

    /**
     * The scale of a {@code PIC S9(10)V99} field, and of every monetary field in this estate: the
     * only three signed pictures in the twenty-eight programs are {@code S9(10)V99},
     * {@code S9(09)V99} and {@code S9(9)V99}.
     */
    private static final int MONETARY_SCALE = 2;

    /**
     * The value the negative overpunch image in {@code case18}'s {@code FILLER} denotes:
     * {@code "00000001940}"} is twelve digits, {@code 000000019400}, at scale 2 with a negative sign.
     *
     * <p>It is row 1 of {@code app/data/ASCII/acctdata.txt}'s {@code ACCT-CURR-BAL} image with the
     * sign inverted - the field {@code AccountBalanceReaderJob}'s name gestures at and
     * {@code CBACT02C} never reads. A {@link BigDecimal} and never a {@code double}: binary floating
     * point cannot hold a COBOL {@code V99} value exactly (practice B8, gate G22).
     */
    private static final BigDecimal NEGATIVE_OVERPUNCH_VALUE = new BigDecimal("-194.00");

    /**
     * The value the positive overpunch image in {@code case19}'s second row denotes, from the same
     * twelve digits closed by {@code '{'} instead of {@code '}'}.
     */
    private static final BigDecimal POSITIVE_OVERPUNCH_VALUE = new BigDecimal("194.00");

    /**
     * Zero-based index of {@code case12} - the first-read {@code '22'} failure - in the case list.
     *
     * <p>Used by the guard that perturbs an expected {@code RETURN-CODE}, which needs a case whose run
     * genuinely abends: perturbing a case that already completes with zero to zero would compare a case
     * against itself and report clean while claiming to prove the opposite. {@code case12} is the
     * lowest-numbered case that reaches {@code :101 MOVE 12 TO APPL-RESULT} on its very first read,
     * which is what makes it the natural choice now that {@code case11} pins a normal completion.
     */
    private static final int CASE12_INDEX = 11;

    /** Zero-based index of {@code case17} - the blank-{@code FILLER} geometry case. */
    private static final int CASE17_INDEX = 16;

    /** Zero-based index of {@code case18} - the negative-overpunch pass-through case. */
    private static final int CASE18_INDEX = 17;

    /** Zero-based index of {@code case19} - the boundary-field and positive-overpunch case. */
    private static final int CASE19_INDEX = 18;

    // =================================================================================================
    // The case set.  ParityHarness.casesOf already refuses anything other than exactly case01 through
    // case20, so this method is the single source of the parameterisation and the guard below merely
    // states the invariant a reader should be able to see without opening the harness.
    // =================================================================================================

    /**
     * Every case for {@code CBACT02C}, in {@code case01}..{@code case20} order.
     *
     * <p>{@link ParityHarness#casesOf(String)} enumerates the twenty by name, refuses a short set, and
     * refuses a stray resource in the directory that the enumeration would never read - so a
     * {@code case21.json} or a {@code Case07.json} fails the build rather than sitting there looking
     * like part of the gate.
     *
     * @return the twenty cases, never fewer and never more
     */
    static List<ParityCase> cases() {
        return ParityHarness.casesOf(PROGRAM);
    }

    // =================================================================================================
    // THE GATE.  One parameterised test over the twenty cases; the diff count must be zero.
    // =================================================================================================

    /**
     * The gate: for each of the twenty cases, seed it, run {@code CBACT02C}'s translation, and require
     * {@link FieldDiffer} to find nothing.
     *
     * <p>The assertion is on the count rather than on {@link FieldDiffer.DiffResult#isClean()} because
     * the count is the number the criterion names, and {@link FieldDiffer.DiffResult#render()} is
     * surfaced in the failure so a break reads as "3 differences on case07" with all three spelled
     * out rather than as a bare boolean.
     *
     * <p>A fresh {@link ParityHarness} per invocation, deliberately: the harness is immutable, but a
     * per-invocation instance makes it structurally impossible for one case to observe another's
     * state, which is what "the cases are order-independent" has to mean to be worth saying
     * (practice B9, gate G53).
     *
     * @param parityCase the case to judge, supplied by {@link #cases()}
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("the field-by-field diff count is zero")
    void theDiffCountIsZero(final ParityCase parityCase) {
        final FieldDiffer.DiffResult result = ParityHarness.usAscii()
                .judge(parityCase, ParityCase.UnitKind.BATCH_JOB, CBACT02CParityTest::driveCbact02c);

        assertThat(result.count())
                .withFailMessage(() -> "The parity gate for " + PROGRAM + '/' + parityCase.caseId()
                        + " found " + result.count() + " difference(s), and the criterion is a diff "
                        + "count of zero across all " + ParityHarness.CASES_PER_PROGRAM
                        + " cases - so this module is incomplete until every one of them is resolved. "
                        + "The expectations come from app/cbl/CBACT02C.cbl, app/cpy/CVACT02Y.cpy, "
                        + "app/jcl/READCARD.jcl and app/data/ASCII/carddata.txt, none of which may be "
                        + "edited to make a difference go away."
                        + System.lineSeparator() + result.render())
                .isZero();
    }

    // =================================================================================================
    // GUARDS.  Each one closes a way in which the gate above could pass while proving less than it
    // appears to: a short case set, a case set that drifted off CBACT02C's contract, a comparison with
    // no teeth, an abend that lost its RETURN-CODE, and a record line that was re-encoded rather than
    // passed through.
    // =================================================================================================

    @Nested
    @DisplayName("The case set is exactly the gate the criterion names")
    class TheCaseSet {

        @Test
        @DisplayName("exactly twenty cases arrive, case01 through case20, in ascending order")
        void theGateIsTwentyOrderedCases() {
            final List<ParityCase> loaded = cases();

            final List<String> expectedIds = new ArrayList<>(ParityHarness.CASES_PER_PROGRAM);
            for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
                expectedIds.add(ParityHarness.caseId(ordinal));
            }

            assertThat(loaded)
                    .as("the cases loaded from parity/%s/. The criterion is a diff count of zero "
                            + "across all %d cases, and a set of four satisfies that vacuously.",
                            PROGRAM, ParityHarness.CASES_PER_PROGRAM)
                    .hasSize(ParityHarness.CASES_PER_PROGRAM);
            assertThat(loaded.stream().map(ParityCase::caseId).toList())
                    .as("case order, which the parameterised test above relies on for its indices")
                    .containsExactlyElementsOf(expectedIds);
        }

        @Test
        @DisplayName("every case pins CBACT02C's own contract: BATCH_JOB, no PARM, one DD, no write")
        void everyCaseMatchesTheReadcardContract() {
            assertThat(cases()).allSatisfy(parityCase -> {
                assertThat(parityCase.program()).isEqualTo(PROGRAM);

                // app/cbl/CBACT02C.cbl contains no EXEC CICS, so there is no screen and no response.
                assertThat(parityCase.unitKind()).isEqualTo(ParityCase.UnitKind.BATCH_JOB);
                assertThat(parityCase.screenRequest()).isNull();
                assertThat(parityCase.expectedResponse()).isNull();

                // app/jcl/READCARD.jcl:22 is a bare EXEC PGM= with no PARM, and CBACT02C has no
                // LINKAGE SECTION to receive one into.
                assertThat(parityCase.jobParameters()).isEmpty();

                // :25-26 declares one input DD and the program SELECTs exactly one file at :29.
                assertThat(parityCase.inputs()).containsOnlyKeys(AccountBalanceReaderJob.DD_NAME);

                // :120 opens INPUT; there is no WRITE, REWRITE or DELETE in the program. An empty
                // expectation is an assertion here, because FieldDiffer reports any dataset or row a
                // case did not pin.
                assertThat(parityCase.expectedWrites()).isEmpty();
                assertThat(parityCase.expectedFinalState()).isEmpty();
                assertThat(parityCase.expectedDatasets()).isEmpty();

                // Neither of the two recorded fixture-to-copybook deviations touches carddata.txt:
                // its rows are 150 bytes, exactly what CVACT02Y declares.
                assertThat(parityCase.normalisations()).isEmpty();

                // The whole fingerprint is the DISPLAY sequence plus the return code, and :71 alone
                // guarantees at least one line.
                assertThat(parityCase.expectedMessages()).isNotEmpty()
                        .allSatisfy(message -> assertThat(message.channel())
                                .isEqualTo(ParityCase.MessageChannel.DISPLAY_LINE));

                // 0 for a normal completion, and the 12 that :101, :124 and :142 move into
                // APPL-RESULT before PERFORM 9999-ABEND-PROGRAM.
                assertThat(parityCase.expectedReturnCode())
                        .isIn(AbendException.RETURN_CODE_OK, AbendException.RETURN_CODE_IO_ERROR);
            });
        }

        @Test
        @DisplayName("every case's expected lines are the ones the production constants emit")
        void everyExpectedLineTracesToAConstant() {
            final List<String> permitted = List.of(
                    AccountBalanceReaderJob.START_BANNER,                    // :71
                    AccountBalanceReaderJob.END_BANNER,                      // :85
                    AccountBalanceReaderJob.OPEN_ERROR_TEXT,                 // :129
                    AccountBalanceReaderJob.READ_ERROR_TEXT,                 // :110
                    AccountBalanceReaderJob.CLOSE_ERROR_TEXT,                // :147
                    AbendException.ABEND_DISPLAY_TEXT,                       // :155
                    FileStatus.toDisplayLine(FileStatus.END_OF_FILE),        // :168 / :172
                    FileStatus.toDisplayLine(FileStatus.DUPLICATE),
                    FileStatus.toDisplayLine(FileStatus.NOT_FOUND),
                    FileStatus.toDisplayLine(AccountBalanceReaderJob.PERMANENT_ERROR_STATUS));

            // The case files state each line as literal text and this list derives it from the
            // constant the program emits it from. Neither is derived from the other, so a
            // transcription slip on either side is caught here rather than propagated.
            assertThat(cases()).allSatisfy(parityCase ->
                    assertThat(parityCase.expectedMessages()).allSatisfy(message -> {
                        if (message.text().length() == CardRecord.RECORD_LENGTH) {
                            // :78 DISPLAY CARD-RECORD - the 150-byte record area, checked below.
                            return;
                        }
                        assertThat(message.text())
                                .as("a diagnostic line of %s that no production constant emits",
                                        PROGRAM)
                                .isIn(permitted);
                    }));
        }

        @Test
        @DisplayName("every record line is a seeded row, verbatim, in file order, at the declared 150"
                + " bytes")
        void everyRecordLineIsASeededRowInFileOrder() {
            final ParityHarness harness = ParityHarness.usAscii();

            assertThat(cases()).allSatisfy(parityCase -> {
                final List<String> seeded = harness.seed(parityCase)
                        .get(AccountBalanceReaderJob.DD_NAME).rows();

                // CVACT02Y's seven spans sum to exactly 150, so every seeded row measures that -
                // including the two geometry cases, whose rows are written inline rather than taken
                // from carddata.txt.
                assertThat(seeded).allSatisfy(row ->
                        assertThat(row)
                                .as("a seeded %s row, which app/cpy/CVACT02Y.cpy declares as "
                                        + "RECLN %d", AccountBalanceReaderJob.DD_NAME,
                                        CardRecord.RECORD_LENGTH)
                                .hasSize(CardRecord.RECORD_LENGTH));

                // No expected line may exceed a record: DISPLAY CARD-RECORD at :78 writes the 01
                // group item and every diagnostic line is far shorter, so nothing wider can occur.
                assertThat(parityCase.expectedMessages()).allSatisfy(message ->
                        assertThat(message.text().length())
                                .as("expected line width in %s/%s", PROGRAM, parityCase.caseId())
                                .isLessThanOrEqualTo(CardRecord.RECORD_LENGTH));

                // Gates G19 and G21, and the ordering invariant, in one statement. :31 declares
                // ACCESS MODE IS SEQUENTIAL and :93 is a bare READ, so the file is walked from its
                // first record forward and :78 writes each row before :93 reads the next. The
                // displayed records are therefore a LEADING PREFIX of the seeded rows, byte for byte
                // and in order - complete for a run that reaches end-of-file, short for one the error
                // arm at :110-113 ends, and empty for a run that never got a record. A blanked
                // FILLER, a trimmed line, a re-encoded field, a transposed pair or an invented
                // record all break it.
                //
                // Compared as a prefix of a known length rather than through startsWith, because
                // startsWith reads an empty sequence against a non-empty actual as a failure - and
                // seven of these twenty cases legitimately display no record at all.
                final List<String> displayed = recordLinesOf(parityCase);
                assertThat(displayed.size())
                        .as("%s/%s displays more records than its input holds", PROGRAM,
                                parityCase.caseId())
                        .isLessThanOrEqualTo(seeded.size());
                assertThat(displayed)
                        .as("the records %s/%s displays must be the leading rows of its own input, "
                                + "byte for byte and in order", PROGRAM, parityCase.caseId())
                        .containsExactlyElementsOf(seeded.subList(0, displayed.size()));
            });
        }

        /**
         * The expected lines of a case that are record images rather than diagnostics, in order.
         *
         * @param parityCase the case
         * @return its {@value CardRecord#RECORD_LENGTH}-character expected lines
         */
        private List<String> recordLinesOf(final ParityCase parityCase) {
            return parityCase.expectedMessages().stream()
                    .map(ParityCase.EmittedMessage::text)
                    .filter(text -> text.length() == CardRecord.RECORD_LENGTH)
                    .toList();
        }
    }

    @Nested
    @DisplayName("The comparison has teeth")
    class TheComparisonHasTeeth {

        @Test
        @DisplayName("changing one byte of one expected record line makes the gate fail")
        void aPerturbedRecordLineIsRejected() {
            final ParityCase clean = cases().get(CASE17_INDEX);
            final ParityCase perturbed = withFirstRecordLinePerturbed(clean);

            final FieldDiffer.DiffResult result = ParityHarness.usAscii()
                    .judge(perturbed, ParityCase.UnitKind.BATCH_JOB,
                            CBACT02CParityTest::driveCbact02c);

            assertThat(result.count())
                    .as("a single altered byte inside the FILLER span must be reported. If this "
                            + "passes, the zero the gate reports for the other twenty cases is not "
                            + "evidence of anything.")
                    .isPositive();
            assertThat(result.render()).contains(clean.caseId());
        }

        @Test
        @DisplayName("changing the expected RETURN-CODE makes the gate fail")
        void aPerturbedReturnCodeIsRejected() {
            final ParityCase clean = cases().get(CASE12_INDEX);
            final ParityCase perturbed = withReturnCode(clean, AbendException.RETURN_CODE_OK);

            final FieldDiffer.DiffResult result = ParityHarness.usAscii()
                    .judge(perturbed, ParityCase.UnitKind.BATCH_JOB,
                            CBACT02CParityTest::driveCbact02c);

            assertThat(result.count())
                    .as("case12 abends with 12 from :101, so expecting 0 must be reported. A batch "
                            + "exit status carries that value out to the process, which is what makes "
                            + "a JCL COND test on a following step behave as it does today.")
                    .isPositive();
        }

        /**
         * The same case with the last byte of its first 150-character line replaced.
         *
         * <p>The byte chosen is the final one, which lies inside {@code CVACT02Y}'s
         * {@code FILLER PIC X(59)} - the span a translation is most likely to blank rather than pass
         * through, and therefore the one whose comparison most needs proving.
         *
         * @param original the clean case
         * @return a copy differing in exactly one character of one expected line
         */
        private ParityCase withFirstRecordLinePerturbed(final ParityCase original) {
            final List<ParityCase.EmittedMessage> messages =
                    new ArrayList<>(original.expectedMessages());
            for (int index = 0; index < messages.size(); index++) {
                final ParityCase.EmittedMessage message = messages.get(index);
                if (message.text().length() != CardRecord.RECORD_LENGTH) {
                    continue;
                }
                final String perturbed =
                        message.text().substring(0, CardRecord.RECORD_LENGTH - 1) + '!';
                messages.set(index, new ParityCase.EmittedMessage(message.channel(), perturbed));
                return copyOf(original, messages, original.expectedReturnCode());
            }
            throw new IllegalStateException("Case " + PROGRAM + '/' + original.caseId()
                    + " declares no " + CardRecord.RECORD_LENGTH + "-character record line, so there "
                    + "is nothing to perturb. Point this guard at a case that displays a record.");
        }

        /**
         * The same case with a different expected {@code RETURN-CODE}.
         *
         * @param original the clean case
         * @param returnCode the value to expect instead
         * @return a copy differing only in its expected return code
         */
        private ParityCase withReturnCode(final ParityCase original, final int returnCode) {
            return copyOf(original, original.expectedMessages(), returnCode);
        }

        /**
         * Rebuilds a case with new expectations, leaving every input member exactly as it was.
         *
         * <p>{@link ParityCase}'s own constructor is used rather than a mutated copy, so a perturbed
         * case is still a case that would have been accepted from disk - a guard that fabricated an
         * invalid case would prove nothing about the differ.
         *
         * @param original the case to copy
         * @param messages the expected lines to carry
         * @param returnCode the expected return code to carry
         * @return the rebuilt case
         */
        private ParityCase copyOf(final ParityCase original,
                                  final List<ParityCase.EmittedMessage> messages,
                                  final int returnCode) {
            return new ParityCase(original.program(), original.caseId(), original.description(),
                    original.unitKind(), original.inputs(), original.jobParameters(),
                    original.screenRequest(), original.expectedResponse(), original.expectedWrites(),
                    original.expectedFinalState(), Integer.valueOf(returnCode), messages,
                    original.normalisations());
        }
    }

    @Nested
    @DisplayName("The abend is 9999-ABEND-PROGRAM, byte for byte and code for code")
    class TheAbend {

        @Test
        @DisplayName("a failing read displays three lines and abends with RETURN-CODE 12, ABCODE 999,"
                + " TIMING 0")
        void theReadErrorArmAbendsExactlyAsTheSourceDoes() {
            final ParityHarness harness = ParityHarness.usAscii();
            final Charset datasetCharset = harness.charset();
            final List<String> emitted = new ArrayList<>();
            final SysoutSink sysout = emitted::add;

            // The seeded input is irrelevant on this path: the very first READ fails, so no row is
            // ever delivered.  An empty CARDFILE states that rather than leaving it to be inferred.
            //
            // The run shape is stated here rather than borrowed from whichever case file happens to
            // carry it.  This guard is about the arm at :110-113 and the abend at :154-158, not about a
            // case slot, and sourcing it from one meant that re-purposing that slot silently retargeted
            // the guard - which is how a test ends up proving something other than what it says.
            // '23' on the first read is the shape: case14 and case20 still pin it through the gate,
            // after three and forty-nine records respectively.
            final CardRepository cardRepository = stubbedCardMaster(
                    Scenario.readFailingAfter(0, Terminator.NOT_FOUND),
                    ParityHarness.SeededDataset.empty(AccountBalanceReaderJob.DD_NAME,
                            CardRecord.RECORD_LENGTH, datasetCharset),
                    datasetCharset);
            final AccountBalanceReaderJob job = jobOver(cardRepository, datasetCharset, sysout);

            assertThatExceptionOfType(AbendException.class)
                    .as("PERFORM 9999-ABEND-PROGRAM at :113 does not return")
                    .isThrownBy(() -> job.execute(sysout))
                    .satisfies(abend -> {
                        // :101 MOVE 12 TO APPL-RESULT, carried through unchanged and never re-derived.
                        assertThat(abend.getReturnCode())
                                .isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
                        assertThat(abend.getProgram()).isEqualTo(PROGRAM);
                        // :157 MOVE 999 TO ABCODE and :156 MOVE 0 TO TIMING - the two arguments the
                        // Language Environment abend service reads.
                        assertThat(abend.getAbendCode())
                                .hasValue(AbendException.STANDARD_ABEND_CODE);
                        assertThat(abend.getTiming()).hasValue(AbendException.STANDARD_TIMING);
                    });

            // :71, then the arm at :110-:113 in source order, and no end banner because :85 is past
            // the abend.
            assertThat(emitted).containsExactly(
                    AccountBalanceReaderJob.START_BANNER,
                    AccountBalanceReaderJob.READ_ERROR_TEXT,
                    FileStatus.toDisplayLine(FileStatus.NOT_FOUND),
                    AbendException.ABEND_DISPLAY_TEXT);

            requireNoWriteWasAttempted(cardRepository);
        }

        @Test
        @DisplayName("the status line is the COBOL literal plus the IO-STATUS-04 image, with the"
                + " trailing NNNN intact")
        void theStatusLineKeepsTheLiteralNnnn() {
            // 9910-DISPLAY-IO-STATUS's numeric arm at :170-172 moves '0000' into IO-STATUS-04 and
            // overlays the two status characters at position three.
            assertThat(FileStatus.toDisplayLine(FileStatus.NOT_FOUND))
                    .isEqualTo(FileStatus.DISPLAY_PREFIX + "0023");
            assertThat(FileStatus.toDisplayLine(FileStatus.DUPLICATE))
                    .isEqualTo(FileStatus.DISPLAY_PREFIX + "0022");
            assertThat(FileStatus.toDisplayLine(FileStatus.END_OF_FILE))
                    .isEqualTo(FileStatus.DISPLAY_PREFIX + "0010");

            // The other arm at :162-168: a status whose first byte is '9' carries a binary feedback
            // code in its second byte, rendered as three digits by a PIC 999 receiver.
            assertThat(FileStatus.toDisplayLine(AccountBalanceReaderJob.PERMANENT_ERROR_STATUS))
                    .isEqualTo(FileStatus.DISPLAY_PREFIX + "9000");

            assertThat(FileStatus.DISPLAY_PREFIX)
                    .as("the trailing NNNN is part of the literal at :168 and :172, not a placeholder")
                    .endsWith("NNNN");
        }
    }

    @Nested
    @DisplayName("A displayed record is the row's own bytes")
    class TheRecordImage {

        @Test
        @DisplayName("the blank-FILLER case displays 150 characters whose last 59 are spaces")
        void theFillerSpanIsPresentAndSpaceFilled() {
            final ParityCase geometry = cases().get(CASE17_INDEX);
            final String seeded = onlySeededRow(geometry);

            assertThat(seeded).hasSize(CardRecord.RECORD_LENGTH);
            assertThat(seeded.substring(CardRecord.FILLER_OFFSET))
                    .as("CVACT02Y's trailing FILLER PIC X(59), which declares no VALUE and is "
                            + "therefore space-filled")
                    .hasSize(CardRecord.FILLER_LENGTH)
                    .isBlank();

            // The expected line is the row itself, which is what makes the width assertion above an
            // assertion about the program's output rather than about the fixture alone.
            assertThat(recordLinesOf(geometry)).containsExactly(seeded);
        }

        @Test
        @DisplayName("a negative zoned overpunch in the FILLER survives, and a re-encode would destroy"
                + " it")
        void theNegativeOverpunchIsPassedThroughVerbatim() {
            final ParityCase overpunched = cases().get(CASE18_INDEX);
            final String seeded = onlySeededRow(overpunched);
            final Charset datasetCharset = ParityHarness.FIXTURE_CHARSET;
            final FixedWidthCodec codec = new FixedWidthCodec(datasetCharset);

            // CVACT02Y declares no signed field, so the only span whose content the copybook does not
            // constrain is the FILLER - and READ ... INTO at :93 fills it from the row.
            final String monetaryImage = seeded.substring(CardRecord.FILLER_OFFSET,
                    CardRecord.FILLER_OFFSET + MONETARY_IMAGE_LENGTH);
            final FixedWidthCodec.SignedZoned decoded =
                    codec.decodeSignedZoned(monetaryImage, MONETARY_SCALE);

            assertThat(decoded.negative())
                    .as("'}' closes the negative overpunch alphabet and carries digit zero with it")
                    .isTrue();
            assertThat(decoded.signedValue()).isEqualByComparingTo(NEGATIVE_OVERPUNCH_VALUE);
            assertThat(decoded.signedValue().scale())
                    .as("a PIC S9(10)V99 field reports exactly its declared scale")
                    .isEqualTo(MONETARY_SCALE);

            // The line the program writes is the stored row.  This is why: rendering it from the six
            // decoded fields agrees on all six and blanks the FILLER, which is a different line.
            assertThat(recordLinesOf(overpunched)).containsExactly(seeded);
            assertThat(CardRecord.decodeImage(seeded, datasetCharset).encodeToImage(datasetCharset))
                    .as("a re-encode of the decoded fields is NOT the row, so the pass-through at :78"
                            + " is load-bearing rather than incidental")
                    .isNotEqualTo(seeded)
                    .hasSize(CardRecord.RECORD_LENGTH)
                    .startsWith(seeded.substring(0, CardRecord.FILLER_OFFSET));
        }

        @Test
        @DisplayName("the boundary case carries the positive overpunch alphabet and full-width fields")
        void theBoundaryFieldsAndPositiveOverpunchAreCarried() {
            final ParityCase boundaries = cases().get(CASE19_INDEX);
            final List<String> seeded = boundaries.inputs()
                    .get(AccountBalanceReaderJob.DD_NAME).rows();
            final FixedWidthCodec codec = new FixedWidthCodec(ParityHarness.FIXTURE_CHARSET);

            assertThat(seeded).hasSize(2).allSatisfy(row ->
                    assertThat(row).hasSize(CardRecord.RECORD_LENGTH));

            // The largest value PIC 9(11) can hold, zero-filled to its declared width rather than
            // shortened, and the smallest.
            assertThat(seeded.get(0).substring(CardRecord.CARD_ACCT_ID_OFFSET,
                    CardRecord.CARD_ACCT_ID_OFFSET + CardRecord.CARD_ACCT_ID_LENGTH))
                    .isEqualTo("9".repeat(CardRecord.CARD_ACCT_ID_LENGTH));
            assertThat(seeded.get(1).substring(CardRecord.CARD_ACCT_ID_OFFSET,
                    CardRecord.CARD_ACCT_ID_OFFSET + CardRecord.CARD_ACCT_ID_LENGTH))
                    .isEqualTo("0".repeat(CardRecord.CARD_ACCT_ID_LENGTH));

            // An all-blank PIC X(50) is 50 spaces, never an empty string.
            assertThat(seeded.get(1).substring(CardRecord.CARD_EMBOSSED_NAME_OFFSET,
                    CardRecord.CARD_EMBOSSED_NAME_OFFSET + CardRecord.CARD_EMBOSSED_NAME_LENGTH))
                    .hasSize(CardRecord.CARD_EMBOSSED_NAME_LENGTH)
                    .isBlank();

            final FixedWidthCodec.SignedZoned decoded = codec.decodeSignedZoned(
                    seeded.get(1).substring(CardRecord.FILLER_OFFSET,
                            CardRecord.FILLER_OFFSET + MONETARY_IMAGE_LENGTH),
                    MONETARY_SCALE);
            assertThat(decoded.negative()).isFalse();
            assertThat(decoded.signedValue()).isEqualByComparingTo(POSITIVE_OVERPUNCH_VALUE);

            assertThat(recordLinesOf(boundaries)).containsExactlyElementsOf(seeded);
        }

        /**
         * The single inline row a geometry case seeds.
         *
         * @param parityCase the case
         * @return its only seeded row
         */
        private String onlySeededRow(final ParityCase parityCase) {
            final List<String> rows =
                    parityCase.inputs().get(AccountBalanceReaderJob.DD_NAME).rows();
            assertThat(rows)
                    .as("%s/%s is a single-row geometry case", PROGRAM, parityCase.caseId())
                    .hasSize(1);
            return rows.get(0);
        }

        /**
         * The expected lines of a case that are record images rather than diagnostics.
         *
         * @param parityCase the case
         * @return its {@value CardRecord#RECORD_LENGTH}-character expected lines, in order
         */
        private List<String> recordLinesOf(final ParityCase parityCase) {
            return parityCase.expectedMessages().stream()
                    .map(ParityCase.EmittedMessage::text)
                    .filter(text -> text.length() == CardRecord.RECORD_LENGTH)
                    .toList();
        }
    }

    @Nested
    @DisplayName("The wiring is READCARD's, and no dataset name reaches Java")
    class TheWiring {

        @Test
        @DisplayName("the job resolves its dataset through the CARDFILE binding and publishes SYSOUT")
        void theJobResolvesItsDdNameThroughConfiguration() {
            final List<String> emitted = new ArrayList<>();
            final SysoutSink sysout = emitted::add;
            final AccountBalanceReaderJob job = jobOver(mock(CardRepository.class),
                    ParityHarness.FIXTURE_CHARSET, sysout);

            // The JCL names a dataset at app/jcl/READCARD.jcl:26; Java names only the DD, and the
            // value comes back from the catalogue rather than from a literal in the job (gate G46).
            assertThat(job.cardfileDatasetName()).isEqualTo(PARITY_DSNAME);
            assertThat(job.sysoutSink())
                    .as("//SYSOUT DD SYSOUT=* at :27 - the seam a parity case captures through")
                    .isSameAs(sysout);
            assertThat(emitted).isEmpty();
        }

        @Test
        @DisplayName("the step sequence is READCARD's single ungated STEP05 running CBACT02C")
        void theStepSequenceIsTheOneTheJclDeclares() {
            assertThat(AccountBalanceReaderJob.DD_NAME).isEqualTo("CARDFILE");
            assertThat(AccountBalanceReaderJob.STEP_NAME).isEqualTo("STEP05");
            assertThat(AccountBalanceReaderJob.REQUIRED_STEPS)
                    .as("app/jcl/READCARD.jcl declares one step and nothing precedes it, so nothing "
                            + "carries COND=(0,NE)")
                    .singleElement()
                    .satisfies(step -> {
                        assertThat(step.name()).isEqualTo(AccountBalanceReaderJob.STEP_NAME);
                        assertThat(step.program()).isEqualTo(PROGRAM);
                        assertThat(step.requirePrecedingExitCodeZero()).isFalse();
                    });
        }

        @Test
        @DisplayName("neither a job repository nor a transaction manager is resolved on this path")
        void nothingResolvesTheBuilderCollaborators() {
            final ObjectProvider<Object> unresolvable = new UnresolvableProvider<>();

            assertThat(unresolvable.stream()).isEmpty();
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("a launcher in the path would relocate the commit boundaries the line "
                            + "ordering of this program depends on, so asking must fail loudly")
                    .isThrownBy(unresolvable::getObject)
                    .withMessageContaining(PROGRAM);
        }
    }

    // =================================================================================================
    // THE ADAPTER.  How this class reaches CBACT02C's PROCEDURE DIVISION - and nothing else.
    // =================================================================================================

    /**
     * Constructs {@link AccountBalanceReaderJob} over a stubbed card master and runs the whole program
     * once, recording every {@code DISPLAY} and the {@code RETURN-CODE}.
     *
     * <p>The three things this method must get right:
     * <ul>
     *   <li><strong>Nothing in the path.</strong> {@link AccountBalanceReaderJob#execute(SysoutSink)}
     *       is the tasklet's own body, so the pass runs with no launcher, no context and no HTTP
     *       (gate G51).</li>
     *   <li><strong>The abend is an observation.</strong> {@code PERFORM 9999-ABEND-PROGRAM} does not
     *       return, so eight of the twenty cases end in an {@link AbendException}. It is caught here
     *       only so the no-write proof runs on the failure paths too, and is then rethrown unchanged
     *       so the harness folds its {@code RETURN-CODE} into the fingerprint. Recording the lines
     *       into {@link ParityHarness.Invocation#recorder()} rather than returning them is what makes
     *       the four lines an abending run emits survive the exception.</li>
     *   <li><strong>The return code is stated.</strong> {@code GOBACK} at
     *       {@code app/cbl/CBACT02C.cbl:87} leaves {@code RETURN-CODE} at zero on the normal path;
     *       that is recorded explicitly rather than left to a default, because a default is the one
     *       value a wrong expectation is hardest to notice against.</li>
     * </ul>
     *
     * @param invocation the seeded {@code CARDFILE}, the case identity and the recorder; never
     *                   {@code null}
     * @return {@code null}, meaning the recorder holds everything this run produced
     * @throws AbendException if the open, a read or the close failed, exactly as
     *                        {@code 9999-ABEND-PROGRAM} does
     */
    private static ParityHarness.UnitOutcome driveCbact02c(final ParityHarness.Invocation invocation) {
        final Scenario scenario = Scenario.forCase(invocation.caseId());
        final Charset datasetCharset = invocation.charset();
        final CardRepository cardRepository = stubbedCardMaster(
                scenario, invocation.dataset(AccountBalanceReaderJob.DD_NAME), datasetCharset);

        // //SYSOUT DD SYSOUT=* - app/jcl/READCARD.jcl:27.  Every DISPLAY lands in the recorder in
        // emission order, so the sequence is intact whether the run finishes or abends.
        final SysoutSink sysout = line -> invocation.recorder().display(line);

        final AccountBalanceReaderJob job = jobOver(cardRepository, datasetCharset, sysout);

        AbendException abended = null;
        try {
            job.execute(sysout);
        } catch (final AbendException abend) {
            abended = abend;
        }

        // Asserted on both paths, so all twenty cases carry it: whatever the class is called, the
        // program opens its file INPUT at :120 and never writes.
        requireNoWriteWasAttempted(cardRepository);

        if (abended != null) {
            throw abended;
        }
        invocation.recorder().returnCode(AbendException.RETURN_CODE_OK);          // :87  GOBACK
        return null;
    }

    /**
     * Requires that the run attempted no write and no keyed read - the behavioural half of the naming
     * divergence, asserted rather than asserted-in-prose.
     *
     * <p>Six methods are named, and each is absent from {@code CBACT02C} for a reason worth stating
     * separately:
     * <ul>
     *   <li>{@code rewrite} is the repository's only mutating operation. {@code app/cbl/CBACT02C.cbl:120}
     *       opens {@code CARDFILE} {@code INPUT} and the program contains no {@code WRITE},
     *       {@code REWRITE} or {@code DELETE} at all, so a single call here would be a new
     *       side effect that the class name - {@code AccountBalanceReaderJob} - makes far too easy to
     *       add by mistake.</li>
     *   <li>{@code readForUpdateByCardNumber} is the read-for-update path, which acquires a lock. A
     *       program that never rewrites has no reason to take one.</li>
     *   <li>{@code readByCardNumber} is a keyed read. {@code :31} declares
     *       {@code ACCESS MODE IS SEQUENTIAL} and {@code :93} is a bare {@code READ}, so this program
     *       positions by nothing and walks the file.</li>
     *   <li>Both {@code readByAccountIdViaAltIndex} overloads travel the {@code CARDAIX} alternate
     *       index. {@code CBACT02C} reads the base cluster in key order and never addresses a card by
     *       account id; an alternate index is a second access path over the same data, never a second
     *       dataset (gate G45).</li>
     *   <li>{@code startBrowse} is the online entry point, which issues no backend call and reports no
     *       response because {@code COCRDLIC} discards its own {@code STARTBR} response. This program
     *       tests its {@code OPEN} at {@code :121}, so it must use the form that reports one -
     *       reading through {@code startBrowse} would leave the whole {@code :129-132} arm
     *       unreachable and make an unusable dataset surface as {@code 'ERROR READING CARDFILE'} on
     *       the following read.</li>
     * </ul>
     *
     * @param cardRepository the stub the run read through
     */
    private static void requireNoWriteWasAttempted(final CardRepository cardRepository) {
        verify(cardRepository, never()).rewrite(any());
        verify(cardRepository, never()).readForUpdateByCardNumber(any());
        verify(cardRepository, never()).readByCardNumber(any());
        verify(cardRepository, never()).readByAccountIdViaAltIndex(anyLong());
        verify(cardRepository, never()).readByAccountIdViaAltIndex(any(String.class));
        verify(cardRepository, never()).startBrowse(any(), any());
    }

    // =================================================================================================
    // THE STUBBED CARD MASTER.  One CardBrowse per run, answering exactly what the case's scenario says.
    // =================================================================================================

    /**
     * Builds the card master this run reads through: an {@code OPEN} that answers as the scenario says,
     * a {@code READ} sequence assembled from the seeded rows, and a {@code CLOSE} that either succeeds
     * or refuses.
     *
     * <p>Two stubs are matched on their arguments rather than on {@code any()}, and both are assertions
     * in disguise. {@code addressing} is matched on {@value AccountBalanceReaderJob#DD_NAME}, so a job
     * that re-bound its repository to some other DD name would receive {@code null} and fail loudly
     * instead of reading a dataset its own JCL never named. {@code openBrowse} is matched on the
     * lowest-key-forward pair, so a job that positioned anywhere other than at or below the first
     * record, or that browsed backwards, would receive {@code null} rather than a plausible-looking
     * subset of the file - which is exactly how a sequential read that silently starts in the middle
     * would otherwise pass.
     *
     * <p>Mockito rather than a hand-written fake because {@link CardBrowse} is {@code final}, which is
     * the established idiom for it in this module and needs no build change: the inline mock maker is
     * the default from Mockito 5 onward.
     *
     * @param scenario       what this case's run must encounter
     * @param cardfile       the seeded {@code CARDFILE}, whose rows become the delivered records
     * @param datasetCharset the code page the seeded rows are in
     * @return the stubbed repository, which hands itself back when the job re-binds it
     */
    private static CardRepository stubbedCardMaster(final Scenario scenario,
                                                    final ParityHarness.SeededDataset cardfile,
                                                    final Charset datasetCharset) {
        final CardRepository cardRepository = mock(CardRepository.class);
        when(cardRepository.addressing(any(), eq(AccountBalanceReaderJob.DD_NAME)))
                .thenReturn(cardRepository);

        if (scenario.openRefused()) {
            // OPEN has a FILE STATUS clause at :33 and no exception, so a refusal must reach the
            // program as a status.  The job translates it to the '9' permanent-error convention and
            // carries this throwable out as the abend's cause.
            when(cardRepository.openBrowse(eq(AccountBalanceReaderJob.LOWEST_CARD_NUMBER_KEY),
                    eq(BrowseDirection.FORWARD)))
                    .thenThrow(new IllegalStateException(
                            "The " + AccountBalanceReaderJob.DD_NAME + " binding could not be resolved "
                                    + "for this parity case, which is how a dataset that cannot be "
                                    + "reached at all reports itself"));
            return cardRepository;
        }

        final CardBrowse cardBrowse = mock(CardBrowse.class);
        when(cardRepository.openBrowse(eq(AccountBalanceReaderJob.LOWEST_CARD_NUMBER_KEY),
                eq(BrowseDirection.FORWARD))).thenReturn(cardBrowse);
        when(cardBrowse.openResp()).thenReturn(scenario.openResp());

        final List<CardReadResult> sequence = readSequence(scenario, cardfile, datasetCharset);
        when(cardBrowse.readNext()).thenReturn(sequence.get(0),
                sequence.subList(1, sequence.size()).toArray(CardReadResult[]::new));

        if (scenario.closeRefused()) {
            doThrow(new IllegalStateException(
                    "The " + AccountBalanceReaderJob.DD_NAME + " browse could not be released, which is "
                            + "how a CLOSE that fails reports itself"))
                    .when(cardBrowse).endBrowse();
        }
        return cardRepository;
    }

    /**
     * Assembles what the successive {@code READ}s at {@code app/cbl/CBACT02C.cbl:93} report: zero or
     * more normal reads over the seeded rows, then the read that ends the loop or abends it.
     *
     * <p>A delivered record carries both views of the same bytes, because {@code READ ... INTO} does:
     * the decoded {@link CardRecord} for anything that branches on a value, and the row's own image
     * for the {@code DISPLAY} at {@code :78}, which writes the area including its {@code FILLER}. They
     * are built from one string, so they cannot disagree.
     *
     * @param scenario       the case's shape
     * @param cardfile       the seeded rows
     * @param datasetCharset the code page to decode each row in - always named, never the platform
     *                       default
     * @return the read results in the order the browse reports them; never empty
     */
    private static List<CardReadResult> readSequence(final Scenario scenario,
                                                     final ParityHarness.SeededDataset cardfile,
                                                     final Charset datasetCharset) {
        final List<String> rows = cardfile.rows();
        final int delivered = scenario.recordsDelivered() == EVERY_SEEDED_ROW
                ? rows.size()
                : scenario.recordsDelivered();
        if (delivered > rows.size()) {
            throw new IllegalStateException("Scenario for " + PROGRAM + " asks for " + delivered
                    + " delivered record(s) but only " + rows.size() + " row(s) were seeded. The "
                    + "scenario and the case file's \"inputs\" describe the same run and must agree.");
        }

        final List<CardReadResult> sequence = new ArrayList<>(delivered + 1);
        for (int index = 0; index < delivered; index++) {
            final String image = rows.get(index);
            sequence.add(CardReadResult.normal(
                    CardRecord.decodeImage(image, datasetCharset), image));
        }
        sequence.add(terminatingRead(scenario, rows, delivered, datasetCharset));
        return sequence;
    }

    /**
     * The read that ends the loop: the orderly end of the file, or one of the three statuses that
     * reach {@code :101 MOVE 12 TO APPL-RESULT} and thence the error arm at {@code :110-113}.
     *
     * @param scenario       the case's shape
     * @param rows           the seeded rows, for the arm that reports a record alongside its status
     * @param delivered      how many rows the loop already consumed
     * @param datasetCharset the code page to decode the delivered row in
     * @return the terminating result
     */
    private static CardReadResult terminatingRead(final Scenario scenario,
                                                  final List<String> rows,
                                                  final int delivered,
                                                  final Charset datasetCharset) {
        return switch (scenario.terminator()) {
            // :98  CARDFILE-STATUS = '10' -> APPL-RESULT 16 -> :107 IF APPL-EOF -> :108 END-OF-FILE 'Y'
            case END_OF_FILE -> CardReadResult.endOfFile();
            // :101 everything else -> APPL-RESULT 12 -> the error arm.  '23'.
            case NOT_FOUND -> CardReadResult.notFound();
            // '22', and it carries a record with it - which :77 still refuses to display.
            case DUPLICATE_KEY -> duplicateKeyRead(rows, delivered, datasetCharset);
            // A response with no two-character batch equivalent -> the '9' permanent-error convention.
            case INVALID_REQUEST -> CardReadResult.failed(FileStatus.INVREQ);
        };
    }

    /**
     * A {@code '22'} duplicate-key read that delivers the next seeded row with it.
     *
     * <p>The record matters: this arm is the proof that the guard at {@code :77} is
     * {@code IF END-OF-FILE = 'N'} and not "if a record came back", so a record has to actually come
     * back for the case to mean anything.
     *
     * @param rows           the seeded rows
     * @param delivered      how many the loop already consumed, so this is the next one
     * @param datasetCharset the code page to decode it in
     * @return the duplicate-key result carrying that row
     */
    private static CardReadResult duplicateKeyRead(final List<String> rows,
                                                   final int delivered,
                                                   final Charset datasetCharset) {
        if (delivered >= rows.size()) {
            throw new IllegalStateException("A duplicate-key read reports a record alongside its "
                    + "status, so the case must seed at least " + (delivered + 1) + " row(s) for "
                    + PROGRAM + "; it seeded " + rows.size() + '.');
        }
        final String image = rows.get(delivered);
        return CardReadResult.duplicateKey(CardRecord.decodeImage(image, datasetCharset), image);
    }

    // =================================================================================================
    // WIRING.  The four collaborators AccountBalanceReaderJob's only public constructor requires.
    //
    // Five of the types below - BatchConfig with its two nested catalogue types, and DataSourceConfig's
    // DatasetBinding and DatasetBindings - are not on this file's declared dependency list. They are
    // reached anyway because they are FORCED by a dependency that is: the class under test has exactly
    // one public constructor and it takes a BatchConfig. Every one of them is a real, already-generated
    // type in this module's config package, in scope under the plan's app/java/**/config/**.java entry,
    // and every symbol used here was read from its own source before being written. Nothing is invented
    // and nothing is assumed - the list simply under-specifies what constructing this job takes, and
    // the alternative would be not exercising the unit the case names at all. Recorded here rather than
    // absorbed silently (practice B4).
    //
    // The surface is kept to its minimum on purpose. StepContract is not named, because the class under
    // test already publishes exactly the step sequence its JCL declares as REQUIRED_STEPS. JobRepository
    // and PlatformTransactionManager are not named either, because execute() reaches neither: only the
    // job and step BUILDERS do, and this class never asks for one.
    // =================================================================================================

    /**
     * The job under test, wired over a stubbed card master and a published {@code SYSOUT} sink.
     *
     * @param cardRepository the card master to read through
     * @param datasetCharset the dataset code page, stated explicitly and never taken from the platform
     * @param sysout         the sink to publish, so {@link AccountBalanceReaderJob#sysoutSink()}
     *                       resolves the same one this run writes through
     * @return a fresh job; nothing about it is shared with any other case
     */
    private static AccountBalanceReaderJob jobOver(final CardRepository cardRepository,
                                                   final Charset datasetCharset,
                                                   final SysoutSink sysout) {
        return new AccountBalanceReaderJob(parityBatchConfig(), cardRepository, datasetCharset,
                new PublishedSysoutSink(sysout));
    }

    /**
     * The batch scaffolding: this job's contract as {@code app/jcl/READCARD.jcl} declares it, and a
     * catalogue in which {@code CARDFILE} and {@code CARDDAT} name one dataset.
     *
     * <p>Both keys are required. {@code app/jcl/READCARD.jcl:25-26} names the DD {@code CARDFILE},
     * while the repository the job reads through is bound to the CICS file name {@code CARDDAT}
     * because the online programs address it that way, and each key carries an independent override in
     * configuration. The job's constructor proves the two resolve to one dataset before it will run at
     * all, so a catalogue declaring only one of them would fail construction.
     *
     * <p>The contract declares the program, the step sequence and no job parameter, which is exactly
     * what a bare {@code EXEC PGM=CBACT02C} at {@code app/jcl/READCARD.jcl:22} passes. The step
     * sequence is taken from {@link AccountBalanceReaderJob#REQUIRED_STEPS} rather than restated, so
     * the wiring cannot claim a shape the class under test would reject.
     *
     * @return the scaffolding; a real {@code BatchConfig} so the resolution the job performs is the
     *         production one rather than a mock of it
     */
    private static BatchConfig parityBatchConfig() {
        final DatasetBinding cardfile = new DatasetBinding(PARITY_DSNAME, DatasetBinding.KSDS, false,
                RECORD_FORMAT, null, CardRecord.RECORD_LENGTH, COPYBOOK, CardRecord.CARD_NUM_LENGTH,
                KEY_OFFSET, null, null);

        final DatasetBindings datasetBindings = new DatasetBindings();
        datasetBindings.put(AccountBalanceReaderJob.DD_NAME, cardfile);
        datasetBindings.put(CardRepository.BASE_DD_NAME, cardfile);

        final JobContracts jobContracts = new JobContracts();
        jobContracts.put(AccountBalanceReaderJob.JOB_KEY,
                new JobContract(AccountBalanceReaderJob.PROGRAM_ID, null,
                        AccountBalanceReaderJob.REQUIRED_STEPS, null, null));

        return new BatchConfig(new UnresolvableProvider<>(), new UnresolvableProvider<>(),
                jobContracts, datasetBindings);
    }

    /**
     * An {@link ObjectProvider} over one published value.
     *
     * <p>Written out rather than mocked or lambda'd because {@code ObjectProvider} declares no abstract
     * method at all - every member of it, {@code getObject()} included, is a default - so it is neither
     * a functional interface nor worth stubbing member by member. Overriding the two that matter is
     * sufficient: {@code getIfAvailable(Supplier)}, which the production code calls, resolves through
     * {@code getObject()}.
     *
     * @param sink the sink to publish; never {@code null} in this class's use
     */
    private record PublishedSysoutSink(SysoutSink sink) implements ObjectProvider<SysoutSink> {

        @Override
        public SysoutSink getObject() {
            return sink;
        }

        @Override
        public Stream<SysoutSink> stream() {
            return Stream.of(sink);
        }
    }

    /**
     * An {@link ObjectProvider} that refuses to resolve, and says why.
     *
     * <p>Used for the job repository and the transaction manager, which
     * {@link AccountBalanceReaderJob#execute(SysoutSink)} never reaches: they are consumed by the job
     * and step builders alone, and this class builds neither. A provider that answered {@code null}
     * would let a future change quietly acquire a dependency on one of them and surface as a
     * {@code NullPointerException} from inside the framework; this one names the situation instead.
     *
     * @param <T> the type nothing is published for
     */
    private static final class UnresolvableProvider<T> implements ObjectProvider<T> {

        @Override
        public T getObject() {
            throw new IllegalStateException("A parity run of " + PROGRAM + " reaches the program body "
                    + "directly through AccountBalanceReaderJob.execute(SysoutSink) and builds no Job "
                    + "and no Step, so it publishes neither a JobRepository nor a transaction manager. "
                    + "Something asked for one, which means the path under test is no longer the "
                    + "tasklet body - and a launcher in the path would relocate the commit boundaries "
                    + "the line ordering of this program depends on.");
        }

        @Override
        public Stream<T> stream() {
            return Stream.empty();
        }
    }

    // =================================================================================================
    // THE PER-CASE SCENARIO.  What each of the twenty runs encounters, and nothing about what it must
    // produce.  A case identifier is an input identity; every expectation lives in the case file.
    // =================================================================================================

    /**
     * How the read loop ends.
     *
     * <p>{@code 1000-CARDFILE-GET-NEXT} is three-armed: {@code '00'} at
     * {@code app/cbl/CBACT02C.cbl:94}, {@code '10'} at {@code :98}, and everything else at
     * {@code :101}. Only the first constant below is the orderly end; the other three are three
     * different ways of arriving at {@code :101}, and they are distinguished because they render three
     * different status images.
     */
    private enum Terminator {

        /** {@code '10'} - the end of the file, and the only terminator that is not an error. */
        END_OF_FILE,

        /** {@code '23'} - record not found, which {@code :101} treats as fatal. */
        NOT_FOUND,

        /** {@code '22'} - a duplicate key, delivered together with a record that is never displayed. */
        DUPLICATE_KEY,

        /** A response with no two-character batch equivalent, reported as {@code '9'} plus a feedback
         * byte of binary zero. */
        INVALID_REQUEST
    }

    /**
     * One case's run shape: how the {@code OPEN} answers, how many records the loop is given, how the
     * loop ends, and whether the {@code CLOSE} succeeds.
     *
     * <p>A record, so it is immutable and holds no state between cases (practice B9, gate G53).
     *
     * @param openResp          the CICS response the {@code OPEN} reports, translated by the job the
     *                          same way a read's response is; {@link FileStatus#NORMAL} for a
     *                          successful open
     * @param openRefused       whether the {@code OPEN} is refused outright instead of answering with
     *                          a response - a dataset that cannot be reached at all
     * @param recordsDelivered  how many seeded rows the loop reads successfully before the terminating
     *                          read, or {@link #EVERY_SEEDED_ROW} for all of them
     * @param terminator        how the loop ends
     * @param closeRefused      whether {@code 9000-CARDFILE-CLOSE} fails
     */
    private record Scenario(int openResp,
                            boolean openRefused,
                            int recordsDelivered,
                            Terminator terminator,
                            boolean closeRefused) {

        /**
         * A run in which the open succeeds, every seeded row is read, the file ends orderly and the
         * close succeeds.
         *
         * @return the ordinary run
         */
        private static Scenario wholeFile() {
            return new Scenario(FileStatus.NORMAL, false, EVERY_SEEDED_ROW, Terminator.END_OF_FILE,
                    false);
        }

        /**
         * A run whose {@code OPEN} reports a response other than {@code NORMAL}, so nothing is read.
         *
         * @param openResp the response the open reports
         * @return the failing-open run
         */
        private static Scenario openReporting(final int openResp) {
            return new Scenario(openResp, false, 0, Terminator.END_OF_FILE, false);
        }

        /**
         * A run whose {@code OPEN} is refused outright.
         *
         * @return the unreachable-dataset run
         */
        private static Scenario openRefusedOutright() {
            return new Scenario(FileStatus.NORMAL, true, 0, Terminator.END_OF_FILE, false);
        }

        /**
         * A run that reads {@code recordsDelivered} rows and then fails on the next read.
         *
         * @param recordsDelivered how many rows are read and displayed first
         * @param terminator       the failing status
         * @return the failing-read run
         */
        private static Scenario readFailingAfter(final int recordsDelivered,
                                                final Terminator terminator) {
            return new Scenario(FileStatus.NORMAL, false, recordsDelivered, terminator, false);
        }

        /**
         * A run that reads its whole input and then fails to close.
         *
         * @return the failing-close run
         */
        private static Scenario closeFailing() {
            return new Scenario(FileStatus.NORMAL, false, EVERY_SEEDED_ROW, Terminator.END_OF_FILE,
                    true);
        }

        /**
         * The shape of the named case.
         *
         * <p>Exhaustive over {@code case01}..{@code case20} with no default that guesses: an
         * unrecognised identifier is a case file that exists with no scenario behind it, and running
         * it as though it were an ordinary full-file read would report a plausible failure against
         * the wrong run.
         *
         * @param caseId the case identifier the harness is running
         * @return that case's run shape; never {@code null}
         * @throws IllegalStateException if the identifier has no declared shape
         */
        private static Scenario forCase(final String caseId) {
            return switch (caseId) {
                // --- Normal completions: RETURN-CODE 0, both banners, one line per record. ---
                case "case01" -> wholeFile();          // all 50 fixture rows
                case "case02" -> wholeFile();          // the dataset is empty, so no row is read
                case "case03" -> wholeFile();          // fixture row 0
                case "case04" -> wholeFile();          // fixture row 49 - the last-record boundary
                case "case05" -> wholeFile();          // fixture rows 43..49
                case "case06" -> wholeFile();          // fixture rows 0..1
                case "case07" -> wholeFile();          // fixture rows 45..49 - the tail window
                // Three inline rows declared in CARD-NUM sequence.  :30-32 make CARDFILE a KSDS read
                // sequentially on RECORD KEY IS FD-CARD-NUM, so the READ walks the key sequence and
                // not the load order; this run pins that the delivered order is the key order.  It is
                // a wholeFile() run and not a closeFailing() one because this folder's twenty fixtures
                // spend their four abending OPEN shapes and their four abending READ shapes elsewhere,
                // and this is the one run that can carry the ordering assertion.
                case "case15" -> wholeFile();          // inline rows, ascending CARD-NUM
                case "case17" -> wholeFile();          // inline row, blank FILLER
                case "case18" -> wholeFile();          // inline row, negative overpunch in FILLER
                case "case19" -> wholeFile();          // inline rows at the field boundaries

                // An inline row synthesized from carddata.txt row 1 with CARD-ACTIVE-STATUS set to
                // 'N'.  It reads and displays exactly as an active card does, which is the claim:
                // CBACT02C contains no reference to CARD-ACTIVE-STATUS, so there is no status filter
                // for a run shape to express.  All fifty fixture rows carry 'Y', which is why the row
                // is inline rather than a range over the fixture.
                case "case11" -> wholeFile();

                // --- 0000-CARDFILE-OPEN failures: :121 is two-armed, so anything but '00' abends.
                //     The remaining status the open can report, '23', is driven at unit level by
                //     AccountBalanceReaderJobTest.OpenStatusLadder, which walks '10', '22', '23' and an
                //     untranslatable response through this same two-armed guard (gate G47). ---
                case "case08" -> openReporting(FileStatus.ENDFILE);  // '10' - still a failure here
                case "case09" -> openReporting(FileStatus.LENGERR);  // no batch equivalent -> '9'
                case "case10" -> openRefusedOutright();              // unreachable -> '9'
                // DUPREC, not DUPKEY: :32 declares RECORD KEY IS FD-CARD-NUM over the base KSDS, so a
                // duplicate reported to this program is one on the base key. Both map to '22', and '22'
                // is the last status the open can report that no other case in this directory forces -
                // the open-side counterpart of case12, which reports the same status on the first READ.
                case "case16" -> openReporting(FileStatus.DUPREC);    // '22'

                // --- 1000-CARDFILE-GET-NEXT failures: :101, then the arm at :110-113. ---
                case "case12" -> readFailingAfter(0, Terminator.DUPLICATE_KEY);
                case "case13" -> readFailingAfter(0, Terminator.INVALID_REQUEST);
                case "case14" -> readFailingAfter(3, Terminator.NOT_FOUND);
                case "case20" -> readFailingAfter(49, Terminator.NOT_FOUND);

                // --- 9000-CARDFILE-CLOSE failure: :139 takes its ELSE, and :85 is never reached.
                //     No fixture in this folder forces it - every one of the twenty declares either a
                //     normal completion or an abending OPEN or READ - so the shape stays available in
                //     closeFailing() and the arm itself is pinned at unit level by
                //     AccountBalanceReaderJobTest.Close and .CloseArithmeticLadder, which drive :139's
                //     ELSE, the lost end banner and the 8 -> 12 ladder (gates G47 and G28). ---

                default -> throw new IllegalStateException("No run shape is declared for "
                        + PROGRAM + '/' + caseId + ". Every one of the "
                        + ParityHarness.CASES_PER_PROGRAM + " case files must name a scenario here, "
                        + "because a batch case cannot declare a forced outcome - ParityCase refuses a "
                        + "screenRequest on a BATCH_JOB case - so this switch is the only place the "
                        + "shape of a failing OPEN, READ or CLOSE can come from.");
            };
        }
    }
}
