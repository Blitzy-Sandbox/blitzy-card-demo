package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedResponse;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedSend;
import com.vsergeychik.carddemo.parity.ParityCase.EmittedMessage;
import com.vsergeychik.carddemo.parity.ParityCase.MessageChannel;
import com.vsergeychik.carddemo.parity.ParityCase.RepositoryOperation;
import com.vsergeychik.carddemo.parity.ParityCase.Termination;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.transaction.DateParmReader;
import com.vsergeychik.carddemo.transaction.DateParmReader.DateParm;
import com.vsergeychik.carddemo.transaction.ReportRequestController;
import com.vsergeychik.carddemo.transaction.ReportRequestController.JobSubmissionPort;
import com.vsergeychik.carddemo.transaction.ReportRequestController.ProgramState;
import com.vsergeychik.carddemo.transaction.ReportRequestController.WriteQueueOutcome;
import com.vsergeychik.carddemo.transaction.dto.ReportRequestRequest;
import com.vsergeychik.carddemo.transaction.dto.ReportRequestResponse;
import com.vsergeychik.carddemo.util.DateUtilityJob;
import com.vsergeychik.carddemo.util.DateUtilityJob.DateValidationResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The parity gate for {@code app/cbl/CORPT00C.cbl} - twenty declarative cases, judged field by field, with
 * a required diff count of zero.
 */
@DisplayName("CORPT00C parity - transaction CR00, and seventeen eighty-byte records for the reader")
final class CORPT00CParityTest {
    private static final String PROGRAM = "CORPT00C";

    private static final UnitKind UNIT_KIND = UnitKind.CONTROLLER_POJO;

    private static final String QUEUE_DATASET = "JOBS";

    private static final String JCL_RECORD_FIELD = "JCL-RECORD";

    private static final RecordLayout JCL_RECORD_LAYOUT = RecordLayout.of(
            ReportRequestController.JCL_RECORD_LENGTH,
            FieldSpan.alphanumeric(JCL_RECORD_FIELD, 0, ReportRequestController.JCL_RECORD_LENGTH));

    private static final String LENGTH_ITEM_SUFFIX = "L";

    private static final int COMMAREA_LENGTH = NavigationContext.COMMAREA_LENGTH;

    private static final String MONTHLY_CONFIRMED_CASE = ParityHarness.caseId(10);

    private static final String MONTHLY_CONFIRMED_START = "2022-12-01";

    private static final String MONTHLY_CONFIRMED_END = "2022-12-31";

    private static final String CONFIRMED = ReportRequestController.CONFIRM_YES_UPPER;

    static List<ParityCase> cases() {
        List<ParityCase> loaded = ParityHarness.casesOf(PROGRAM);

        assertThat(loaded)
                .as("the gate is stated as twenty declarative cases per program, and "
                        + "'diff count is zero across all twenty' is satisfied vacuously by a shorter "
                        + "set - so the count is asserted before a single case runs")
                .hasSize(ParityHarness.CASES_PER_PROGRAM);

        List<String> ids = new ArrayList<>(loaded.size());
        for (ParityCase parityCase : loaded) {
            ids.add(parityCase.caseId());
            assertThat(parityCase.program())
                    .as("every case in parity/%s/ must name that program, or it is judging a "
                            + "different one", PROGRAM)
                    .isEqualTo(PROGRAM);
            assertThat(parityCase.unitKind())
                    .as("case %s declares unitKind %s; CORPT00C is an online program reached as a "
                            + "plain controller object, so every one of its cases is %s",
                            parityCase.caseId(), parityCase.unitKind(), UNIT_KIND)
                    .isEqualTo(UNIT_KIND);
            assertThat(parityCase.inputs())
                    .as("case %s seeds a dataset. CORPT00C performs no file command at all - every "
                            + "EXEC CICS in app/cbl/CORPT00C.cbl is WRITEQ TD, SEND, RECEIVE, RETURN or "
                            + "XCTL - so a seeded dataset would be data no path can reach",
                            parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.screenRequest())
                    .as("case %s declares no screenRequest; an online program is driven by its "
                            + "communication area, its AID and its received map, and all three travel "
                            + "there", parityCase.caseId())
                    .isNotNull();
            assertThat(parityCase.expectedResponse())
                    .as("case %s declares no expectedResponse. Several paths of this program write no "
                            + "record at all and are entirely response, so without one they would "
                            + "assert nothing", parityCase.caseId())
                    .isNotNull();
        }

        List<String> expectedIds = new ArrayList<>(ParityHarness.CASES_PER_PROGRAM);
        for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
            expectedIds.add(ParityHarness.caseId(ordinal));
        }
        assertThat(ids)
                .as("the twenty cases must be case01 through case20 in order, so that a mis-numbered "
                        + "fixture cannot silently replace another")
                .containsExactlyElementsOf(expectedIds);

        return loaded;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("field-for-field identical to app/cbl/CORPT00C.cbl")
    void isFieldForFieldIdenticalToTheCobol(ParityCase parityCase) {
        DiffResult diff =
                ParityHarness.usAscii().judge(parityCase, UNIT_KIND, CORPT00CParityTest::execute);

        assertThat(diff.count())
                .as("%s/%s must produce a diff count of zero. A module is not complete until the count "
                        + "is zero across all twenty of its cases, so a single difference here is a "
                        + "failed gate rather than a tolerance.%n%s",
                        parityCase.program(), parityCase.caseId(), diff.render())
                .isZero();
        assertThat(diff.isClean())
                .as("the differ reported a clean result and a non-zero count, or the reverse - the two "
                        + "must agree.%n%s", diff.render())
                .isTrue();
    }

    private static ParityHarness.UnitOutcome execute(ParityHarness.Invocation invocation) {
        CapturedJobsQueue queue = new CapturedJobsQueue(invocation);
        ReportRequestController controller = new ReportRequestController(
                new DateUtilityJob(),
                queue,
                invocation.clock(),
                invocation.charset());

        ProgramState state = controller.mainPara(requestOf(invocation));

        requireQueueAgreesWithProgram(queue, state);
        return fingerprintOf(invocation, state);
    }

    private static ReportRequestRequest requestOf(ParityHarness.Invocation invocation) {
        ReportRequestRequest request = ReportRequestRequest.empty();
        FixedWidthCodec codec = invocation.codec();

        if (invocation.eibcalen() > 0) {
            request = request.withNavigationContext(NavigationContext.fromFixedWidth(codec,
                    codec.serialise(NavigationContext.LAYOUT, invocation.commarea())));
        } else {
            request = request.withoutNavigationContext();
        }

        String token = aidTokenOf(invocation.aid());
        if (token != null) {
            request = request.withAid(token);
        }

        for (Map.Entry<String, String> field : invocation.mapFields().entrySet()) {
            request = request.withValue(screenFieldOf(field.getKey()), field.getValue());
        }
        return request;
    }

    private static String aidTokenOf(String mnemonic) {
        if (mnemonic == null) {
            return null;
        }
        for (Map.Entry<Byte, String> entry : CicsAid.mnemonicsByAid().entrySet()) {
            if (entry.getValue().equals(mnemonic)) {
                return PfKeyResolver.aidImage(entry.getKey());
            }
        }
        throw new IllegalArgumentException('"' + mnemonic + "\" is not a DFHAID mnemonic that "
                + CicsAid.class.getName() + " reproduces, yet ParityCase accepted it. The two read the "
                + "same map, so this means they have drifted apart.");
    }

    private static ReportRequestRequest.ScreenField screenFieldOf(String inputItem) {
        List<String> declared = new ArrayList<>(ReportRequestRequest.FIELD_COUNT);
        for (ReportRequestRequest.ScreenField field : ReportRequestRequest.ScreenField.values()) {
            if (field.inputItem().equals(inputItem)) {
                return field;
            }
            declared.add(field.inputItem());
        }
        throw new IllegalArgumentException('"' + inputItem + "\" is not one of the "
                + ReportRequestRequest.FIELD_COUNT + " xxxI items app/cpy-bms/CORPT00.CPY declares. The "
                + "declared set is " + declared + ". The xxxL, xxxF and xxxA items are length, flag and "
                + "attribute metadata and are not payload fields, so they are not settable from a case.");
    }

    private static void requireQueueAgreesWithProgram(CapturedJobsQueue queue, ProgramState state) {
        assertThat(queue.records())
                .as("the records the queue ACCEPTED and the records WIRTE-JOBSUB-TDQ recorded must be "
                        + "the same list in the same order: app/cbl/CORPT00C.cbl:517 hands one record to "
                        + "the queue per iteration of the loop at :498-508, nothing else writes, and only "
                        + "the DFHRESP(NORMAL) arm of the EVALUATE at :525-535 appended anything")
                .containsExactlyElementsOf(state.submittedRecords());
        assertThat(queue.attempts())
                .as("and every hand-off is accounted for: a refused write is an attempt that appended "
                        + "nothing, so the attempt count is the accepted count plus the refusals")
                .isGreaterThanOrEqualTo(state.submittedRecords().size());
    }

    private static ParityHarness.UnitOutcome fingerprintOf(ParityHarness.Invocation invocation,
                                                           ProgramState state) {
        ParityHarness.UnitOutcome.Builder recorder = invocation.recorder();

        recorder.response(observedResponseOf(invocation.codec(), state));

        if (!state.submittedRecords().isEmpty()) {
            recorder.wroteAll(QUEUE_DATASET, JCL_RECORD_LAYOUT, state.submittedRecords());
        }

        recorder.message(new EmittedMessage(MessageChannel.WS_MESSAGE_80, state.message()));
        recorder.message(new EmittedMessage(MessageChannel.SCREEN_ERRMSG_78,
                state.response().getErrmsgo()));
        for (String line : state.displayLines()) {
            recorder.display(line);
        }

        recorder.returnCode(0);
        return recorder.build();
    }

    private static ObservedResponse observedResponseOf(FixedWidthCodec codec, ProgramState state) {
        ReportRequestResponse response = state.response();

        Map<String, String> navigation = new LinkedHashMap<>(codec.deserialise(
                NavigationContext.LAYOUT, response.getNavigationContext().toFixedWidth(codec)));

        List<ObservedSend> sends = new ArrayList<>(1);
        if (state.screenSent()) {
            sends.add(new ObservedSend(response.fieldImages(),
                    Map.of(ReportRequestResponse.ScreenField.ERRMSG.colourItemName(),
                            BmsAttributes.colourMnemonic(response.getErrmsgc()))));
        }

        return new ObservedResponse(
                response.getNextProgram(),
                namedOrNone(response.getNextMapset()),
                namedOrNone(response.getNextMap()),
                navigation,
                sends,
                cursorLengthItemOf(state),
                terminationOf(state));
    }

    private static String namedOrNone(String reference) {
        return reference == null || reference.isBlank() ? null : reference;
    }

    private static String cursorLengthItemOf(ProgramState state) {
        for (ReportRequestRequest.ScreenField field : ReportRequestRequest.ScreenField.values()) {
            if (state.cursorRequestedOn(field)) {
                return field.lengthItem();
            }
        }
        return null;
    }

    private static Termination terminationOf(ProgramState state) {
        if (state.transferred() && state.returned()) {
            throw new IllegalStateException("The run reported both EXEC CICS XCTL and EXEC CICS RETURN. "
                    + "An XCTL transfers control and never comes back, so app/cbl/CORPT00C.cbl cannot "
                    + "reach both in one task and a translation that did has invented a behaviour.");
        }
        if (state.transferred()) {
            return Termination.XCTL;
        }
        if (state.returned()) {
            return Termination.RETURN_TRANSID;
        }
        throw new IllegalStateException("The run reported neither EXEC CICS XCTL nor EXEC CICS RETURN. "
                + "Every arm of MAIN-PARA ends in one of the two - :174 and :189 transfer, and every "
                + "other arm reaches SEND-TRNRPT-SCREEN, which ends GO TO RETURN-TO-CICS at :580 - so a "
                + "task that ended in neither has lost its navigation entirely.");
    }

    /**
     * The capturing {@link JobSubmissionPort} every case runs through: the test-side reproduction of
     * {@code EXEC CICS WRITEQ TD QUEUE('JOBS')}.
     */
    private static final class CapturedJobsQueue implements JobSubmissionPort {
        private final ParityHarness.Invocation invocation;

        /**
         * Every record the queue <strong>accepted</strong>, in order. Never truncated -
         * {@code DISPOSITION(MOD)} - and never added to by a write the queue refused, because a refused
         * {@code WRITEQ TD} appended nothing.
         */
        private final List<String> records = new ArrayList<>();

        /**
         * How many records have been handed over, accepted or not.
         *
         * <p>Kept apart from {@link #records} so a diagnostic still names the ordinal of the hand-off it
         * is describing after a refusal has left the accepted list one shorter than the attempt count.
         */
        private int attempts;

        /**
         * @param invocation the invocation being run; never {@code null}
         */
        CapturedJobsQueue(ParityHarness.Invocation invocation) {
            this.invocation = Objects.requireNonNull(invocation, "An Invocation is required: the queue "
                    + "takes its code page and its forced outcome from the case being run");
        }

        /**
         * {@code EXEC CICS WRITEQ TD QUEUE('JOBS') FROM(JCL-RECORD) LENGTH(LENGTH OF JCL-RECORD)}.
         *
         * @param jclRecord the record the program has just filled
         * @return the {@code RESP} and {@code RESP2} the caller evaluates
         */
        @Override
        public WriteQueueOutcome writeQueueTd(String jclRecord) {
            int ordinal = ++attempts;
            assertThat(jclRecord)
                    .as("record %d handed to TDQUEUE(JOBS), which app/csd/CARDDEMO.CSD:499-505 declares "
                            + "RECORDSIZE(80) RECORDFORMAT(FIXED) BLOCKFORMAT(UNBLOCKED); JCL-RECORD is "
                            + "PIC X(80) at app/cbl/CORPT00C.cbl:79, so a record of any other length "
                            + "cannot go to this queue", ordinal)
                    .isNotNull()
                    .hasSize(ReportRequestController.JCL_RECORD_LENGTH);
            assertThat(invocation.codec().encodeImage(jclRecord,
                            "record " + ordinal + " of the " + QUEUE_DATASET + " queue"))
                    .as("record %d must encode to exactly %d bytes in the case's own code page (%s), "
                            + "because the queue's RECORDSIZE is a byte count and the code page is "
                            + "stated rather than assumed", ordinal,
                            ReportRequestController.JCL_RECORD_LENGTH, invocation.charset().name())
                    .hasSize(ReportRequestController.JCL_RECORD_LENGTH);

            WriteQueueOutcome outcome = invocation.hasForcedOutcome(RepositoryOperation.WRITE)
                    ? refusal(invocation.forcedOutcome(RepositoryOperation.WRITE))
                    : WriteQueueOutcome.NORMAL;

            // The width and encoding checks above run on every hand-off, accepted or not: they are the
            // transport's contract and a caller that offers a wrong-width record has broken it whatever
            // the queue then reports. The record itself is appended only on DFHRESP(NORMAL), because that
            // is the only arm of the EVALUATE at :525-535 on which the queue was appended to. Appending
            // it regardless would make this stand-in disagree with the queue it stands in for, and would
            // make the case files describe a queue holding a record it does not hold.
            if (outcome.normal()) {
                records.add(jclRecord);
            }
            return outcome;
        }

        List<String> records() {
            return Collections.unmodifiableList(records);
        }

        /**
         * How many records were handed over, accepted or not.
         *
         * @return the hand-off count
         */
        int attempts() {
            return attempts;
        }

        /**
         * Translates a case's declared {@link ParityCase.ForcedOutcome} into the outcome the queue
         * reports.
         *
         * <p>Only {@link com.vsergeychik.carddemo.common.FileStatus.Outcome#OTHER} is accepted, and the
         * reason is that the {@code EVALUATE WS-RESP-CD} at {@code :525-535} has exactly two arms:
         * {@code DFHRESP(NORMAL)} and {@code WHEN OTHER}. A normal write needs no forcing - it is what
         * every unforced case already does - and there is no third arm for any other outcome to reach, so
         * forcing one would be a declaration that could not affect the run.
         *
         * @param forced what the case declared
         * @return the corresponding {@code RESP} and {@code RESP2}
         */
        private WriteQueueOutcome refusal(ParityCase.ForcedOutcome forced) {
            return switch (forced.outcome()) {
                case OTHER -> new WriteQueueOutcome(
                        forced.resp() == null ? WriteQueueOutcome.notOpen().resp() : forced.resp(),
                        forced.resp2() == null ? WriteQueueOutcome.notOpen().resp2() : forced.resp2());
                case OK, END_OF_FILE, NOT_FOUND, DUPLICATE -> throw new IllegalArgumentException(
                        "A CORPT00C case forced the " + forced.outcome() + " outcome for the queue "
                                + "write. EVALUATE WS-RESP-CD at app/cbl/CORPT00C.cbl:525-535 has two "
                                + "arms - DFHRESP(NORMAL) and WHEN OTHER - so a normal write needs no "
                                + "forcing and no other outcome has an arm to reach. Declare OTHER with "
                                + "the RESP the condition reports, or remove the declaration.");
            };
        }
    }

    @Nested
    @DisplayName("the seventeen eighty-byte records and their four substitution points")
    class TheSkeleton {
        @Test
        @DisplayName("seventeen records, every one exactly eighty characters and eighty bytes")
        void theTemplateGeometryIsTheDeclaredGeometry() {
            assertThat(ReportRequestController.JOB_DATA_TEMPLATE)
                    .as("02 JOB-DATA-1 declares seventeen PIC X(80) items at app/cbl/CORPT00C.cbl:83-125")
                    .hasSize(ReportRequestController.JOB_LINE_COUNT)
                    .allSatisfy(record -> assertThat(record)
                            .hasSize(ReportRequestController.JCL_RECORD_LENGTH));
            assertThat(ReportRequestController.JOB_DATA_LENGTH)
                    .as("the group is seventeen records of eighty bytes, which is what JOB-DATA-2 "
                            + "REDEFINES at :126-127")
                    .isEqualTo(ReportRequestController.JOB_LINE_COUNT
                            * ReportRequestController.JCL_RECORD_LENGTH);

            FixedWidthCodec codec = ParityHarness.usAscii().codec();
            for (String record : ReportRequestController.JOB_DATA_TEMPLATE) {
                assertThat(codec.encodeImage(record, "a " + QUEUE_DATASET + " queue record"))
                        .as("the queue's RECORDSIZE is a byte count, so the width has to hold in the "
                                + "code page as well as in characters")
                        .hasSize(ReportRequestController.JCL_RECORD_LENGTH);
            }
        }

        @Test
        @DisplayName("the last record is the '/*EOF' sentinel, space filled to eighty")
        void theSentinelIsItselfARecord() {
            assertThat(ReportRequestController.EOF_MARKER_TEXT).isEqualTo("/*EOF");
            assertThat(ReportRequestController.JOB_LINE_17)
                    .as("record seventeen is the sentinel :502 recognises, and :507 writes it anyway")
                    .isEqualTo(ReportRequestController.EOF_MARKER_RECORD)
                    .startsWith(ReportRequestController.EOF_MARKER_TEXT)
                    .hasSize(ReportRequestController.JCL_RECORD_LENGTH);
            assertThat(ReportRequestController.JOB_DATA_TEMPLATE
                            .get(ReportRequestController.JOB_LINE_COUNT - 1))
                    .isEqualTo(ReportRequestController.JOB_LINE_17);
        }

        @Test
        @DisplayName("JOB-LINES is subscripted from one, and outside its declared items is refused")
        void theOccursTableIsOneBased() {
            List<String> lines = ReportRequestController.JOB_DATA_TEMPLATE;

            assertThat(ReportRequestController.jobLine(lines, 1))
                    .as("OCCURS subscripts are one-based and Java list indices are zero-based; entry 1 "
                            + "is the job card")
                    .isEqualTo(ReportRequestController.JOB_LINE_01);
            assertThat(ReportRequestController.jobLine(lines, ReportRequestController.JOB_LINE_COUNT))
                    .isEqualTo(ReportRequestController.JOB_LINE_17);
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .as("subscript zero is not an entry of a one-based table")
                    .isThrownBy(() -> ReportRequestController.jobLine(lines, 0));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .as("the table says OCCURS %d TIMES but redefines only %d bytes, so entry %d reads "
                            + "storage 02 JOB-DATA-1 does not own",
                            ReportRequestController.JOB_LINES_OCCURS,
                            ReportRequestController.JOB_DATA_LENGTH,
                            ReportRequestController.JOB_LINE_COUNT + 1)
                    .isThrownBy(() -> ReportRequestController.jobLine(lines,
                            ReportRequestController.JOB_LINE_COUNT + 1));
        }

        @Test
        @DisplayName("a mis-sized prefix or date is refused rather than padded into place")
        void malformedRecordPartsAreRefused() {
            assertThatIllegalArgumentException()
                    .as("FILLER-1's prefix is declared PIC X(18); a shorter one would leave the "
                            + "composed record short of eighty")
                    .isThrownBy(() -> ReportRequestController.symnamesLine("X", "2026-01-01",
                            ReportRequestController.SYMNAMES_START_DATE_TAIL_LENGTH));
            assertThatIllegalArgumentException()
                    .as("PARM-START-DATE-1 is declared PIC X(10)")
                    .isThrownBy(() -> ReportRequestController.symnamesLine(
                            ReportRequestController.SYMNAMES_START_DATE_PREFIX, "2026-01-011",
                            ReportRequestController.SYMNAMES_START_DATE_TAIL_LENGTH));
            assertThatIllegalArgumentException()
                    .as("PARM-START-DATE-2 is declared PIC X(10) too")
                    .isThrownBy(() -> ReportRequestController.dateParmRecord("2026-01-011",
                            "2026-12-31"));
        }

        @Test
        @DisplayName("the class-initialisation self-checks reject drift in either file")
        void theSelfChecksActuallyGuard() {
            String widths = (ReportRequestController.JCL_RECORD_LENGTH + " ")
                    .repeat(ReportRequestController.JOB_LINE_COUNT);
            ReportRequestController.requireSkeletonWidths(widths);
            assertThatIllegalStateException()
                    .isThrownBy(() -> ReportRequestController.requireSkeletonWidths(
                            (ReportRequestController.JCL_RECORD_LENGTH + " ")
                                    .repeat(ReportRequestController.JOB_LINE_COUNT - 1)))
                    .withMessageContaining("02 JOB-DATA-1 declares");

            ReportRequestController.requireDateParmLayout("0:10/10:1/11:10/21:59@80");
            assertThatIllegalStateException()
                    .isThrownBy(() -> ReportRequestController
                            .requireDateParmLayout("0:10/10:1/11:10/21:58@80"))
                    .withMessageContaining("DateParmReader consumes");

            ReportRequestController.requireMatchingProjections(ReportRequestRequest.FIELD_COUNT,
                    ReportRequestResponse.ScreenField.values().length);
            assertThatIllegalStateException()
                    .isThrownBy(() -> ReportRequestController.requireMatchingProjections(
                            ReportRequestRequest.FIELD_COUNT,
                            ReportRequestRequest.FIELD_COUNT - 1))
                    .withMessageContaining("has drifted");
        }

        @Test
        @DisplayName("a record the port is handed short by one byte is refused by the port itself")
        void thePortEnforcesTheEightyByteContract() {
            ParityCase parityCase = ParityHarness.usAscii().load(PROGRAM, ParityHarness.caseId(1));

            assertThatExceptionOfType(AssertionError.class)
                    .as("gate G42 has to be a property of the transport and not only of the twenty case "
                            + "files, or a translation that emitted a seventy-nine byte record would be "
                            + "caught by an expectation rather than by the queue that cannot accept it")
                    .isThrownBy(() -> ParityHarness.usAscii().run(parityCase, UNIT_KIND, invocation -> {
                        CapturedJobsQueue queue = new CapturedJobsQueue(invocation);
                        queue.writeQueueTd(ReportRequestController.JOB_LINE_01.substring(0,
                                ReportRequestController.JCL_RECORD_LENGTH - 1));
                        return invocation.recorder().returnCode(0).build();
                    }))
                    .withMessageContaining("RECORDSIZE(80)");
        }
    }

    @Nested
    @DisplayName("skeleton record fifteen is the DATEPARM record CBTRN03C reads")
    class TheDateParmHandOff {
        @ParameterizedTest(name = "{0} .. {1} survives the hand-off")
        @CsvSource({
            "2026-08-01, 2026-08-31",
            "2026-01-01, 2026-12-31",
            "1582-10-14, 1582-10-14"
        })
        @DisplayName("read back through the reader's twenty-one byte receiver it is the same range")
        void recordFifteenRoundTripsThroughTheReadersOwnLayout(String startDate, String endDate) {
            String record = ReportRequestController.dateParmRecord(startDate, endDate);

            assertThat(record)
                    .as("05 FILLER-3 at app/cbl/CORPT00C.cbl:117-121 is eighty bytes, and DateParmReader "
                            + "declares the same record length")
                    .hasSize(DateParmReader.RECORD_LENGTH)
                    .hasSize(ReportRequestController.JCL_RECORD_LENGTH);

            DateParm parsed = new DateParm(
                    record.substring(DateParmReader.START_DATE_OFFSET,
                            DateParmReader.START_DATE_OFFSET + DateParmReader.START_DATE_LENGTH),
                    record.substring(DateParmReader.SEPARATOR_OFFSET,
                            DateParmReader.SEPARATOR_OFFSET + DateParmReader.SEPARATOR_LENGTH),
                    record.substring(DateParmReader.END_DATE_OFFSET,
                            DateParmReader.END_DATE_OFFSET + DateParmReader.END_DATE_LENGTH));

            assertThat(parsed.startDate()).isEqualTo(startDate);
            assertThat(parsed.endDate()).isEqualTo(endDate);
            assertThat(parsed.separator())
                    .as("10 FILLER PIC X VALUE SPACE at :119 - it is a byte of the record, so it is "
                            + "asserted rather than assumed")
                    .isEqualTo(" ");
            assertThat(parsed.receiverImage())
                    .as("the twenty-one bytes CBTRN03C's READ ... INTO receiver holds")
                    .isEqualTo(record.substring(0, DateParmReader.RECEIVER_LENGTH));
            assertThat(record.substring(DateParmReader.DISCARDED_TAIL_OFFSET))
                    .as("10 FILLER PIC X(59) VALUE SPACES at :121, the span the reader discards")
                    .hasSize(DateParmReader.DISCARDED_TAIL_LENGTH)
                    .isBlank();
        }

        @Test
        @DisplayName("record fifteen of a real run is that same record")
        void theEmittedRecordIsTheOneTheReaderParses() {
            ParityCase parityCase =
                    ParityHarness.usAscii().load(PROGRAM, MONTHLY_CONFIRMED_CASE);

            assertThat(parityCase.screenRequest().mapFields())
                    .as("%s has to select the monthly report AND confirm it: the guard at :464 sends the "
                            + "confirmation prompt and returns to CICS, so an unconfirmed case never "
                            + "reaches the loop that hands record fifteen to the queue",
                            MONTHLY_CONFIRMED_CASE)
                    .containsEntry(ReportRequestRequest.ScreenField.CONFIRM.inputItem(), CONFIRMED)
                    .hasEntrySatisfying(ReportRequestRequest.ScreenField.MONTHLY.inputItem(),
                            selection -> assertThat(selection)
                                    .as("the monthly guard at :213 is 'NOT = SPACES AND LOW-VALUES', so "
                                            + "any non-blank selection takes the arm")
                                    .isNotBlank());

            ParityHarness.DecodedFingerprint fingerprint =
                    ParityHarness.usAscii().run(parityCase, UNIT_KIND, CORPT00CParityTest::execute);

            List<ParityHarness.DecodedRecord> emitted = fingerprint.findWrites(QUEUE_DATASET)
                    .orElseThrow(() -> new AssertionError(MONTHLY_CONFIRMED_CASE + " confirms a monthly "
                            + "report, so SUBMIT-JOB-TO-INTRDR must hand seventeen records to the queue"));
            assertThat(emitted).hasSize(ReportRequestController.JOB_LINE_COUNT);

            String recordFifteen = emitted.get(ReportRequestController.DATEPARM_ENTRY - 1)
                    .field(JCL_RECORD_FIELD)
                    .orElseThrow(() -> new AssertionError("the JCL-RECORD span is the whole record"));
            assertThat(recordFifteen)
                    .isEqualTo(ReportRequestController.dateParmRecord(
                            MONTHLY_CONFIRMED_START, MONTHLY_CONFIRMED_END));
        }
    }

    @Nested
    @DisplayName("the ordered EVALUATEs, and the inline EIBAID tests")
    class TheOrderedDecisions {
        @Test
        @DisplayName("EVALUATE EIBAID names exactly two keys and defaults everything else")
        void theTwoNamedAidsAndTheDefault() {
            assertThat(ReportRequestController.eibAidOf(PfKeyResolver.aidImage(CicsAid.DFHENTER)))
                    .as("WHEN DFHENTER at app/cbl/CORPT00C.cbl:185")
                    .isEqualTo(CicsAid.DFHENTER);
            assertThat(ReportRequestController.eibAidOf(PfKeyResolver.aidImage(CicsAid.DFHPF3)))
                    .as("WHEN DFHPF3 at :187, compared as the byte the payload carried")
                    .isEqualTo(CicsAid.DFHPF3);
            assertThat(ReportRequestController.eibAidOf(PfKeyResolver.aidImage(CicsAid.DFHPF5)))
                    .as("PF5 arrives as itself, and :190's WHEN OTHER is where it lands")
                    .isEqualTo(CicsAid.DFHPF5);
            assertThat(PfKeyResolver.isEnter(CicsAid.DFHPF5) || PfKeyResolver.isPf3(CicsAid.DFHPF5))
                    .as("WHEN OTHER at :190 - PF5 is named by neither arm")
                    .isFalse();
            assertThat(ReportRequestController.eibAidOf(PfKeyResolver.aidImage(CicsAid.DFHCLEAR)))
                    .as("CLEAR arrives as itself too, and is named by neither arm")
                    .isEqualTo(CicsAid.DFHCLEAR);
            assertThat(ReportRequestController.eibAidOf("PFK03"))
                    .as("a five-character CCARD-AID token is not one byte, so it names no key at all")
                    .isEqualTo(CicsAid.DFHNULL);
            assertThat(ReportRequestController.eibAidOf(null))
                    .as("no key pressed is DFHNULL, which also defaults")
                    .isEqualTo(CicsAid.DFHNULL);

            assertThat(PfKeyResolver.isEnter(CicsAid.DFHENTER)).isTrue();
            assertThat(PfKeyResolver.isPf3(CicsAid.DFHPF3)).isTrue();
            assertThat(PfKeyResolver.isEnter(CicsAid.DFHPF3)).isFalse();
            assertThat(PfKeyResolver.isPf3(CicsAid.DFHENTER)).isFalse();
        }

        @Test
        @DisplayName("PF15 arrives as PF15 and takes WHEN OTHER: the fold is not this program's")
        void theFoldIsNotAppliedAtAll() {
            assertThat(aidTokenOf(CicsAid.mnemonicsByAid().get(CicsAid.DFHPF15)))
                    .as("app/cpy/CSSTRPFY.cpy folds PF13 to PF24 back onto PFK01 to PFK12, but CORPT00C "
                            + "does not copy it - it compares EIBAID inline at :187 - so PF15 travels as "
                            + "itself and is neither of the two bytes this program names")
                    .isEqualTo(PfKeyResolver.aidImage(CicsAid.DFHPF15))
                    .isNotEqualTo(PfKeyResolver.aidImage(CicsAid.DFHPF3));
            assertThat(ReportRequestController.eibAidOf(
                    aidTokenOf(CicsAid.mnemonicsByAid().get(CicsAid.DFHPF15))))
                    .isEqualTo(CicsAid.DFHPF15);
            assertThat(aidTokenOf(CicsAid.mnemonicsByAid().get(CicsAid.DFHPA3)))
                    .as("a key CSSTRPFY has no branch for is still a key a terminal can send")
                    .isEqualTo(PfKeyResolver.aidImage(CicsAid.DFHPA3));
        }

        @Test
        @DisplayName("an AID mnemonic the reproduction does not define is refused")
        void anUnknownMnemonicIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> aidTokenOf("DFHPF99"))
                    .withMessageContaining("is not a DFHAID mnemonic");
        }

        @Test
        @DisplayName("an xxxI item the symbolic map does not declare is refused")
        void anUnknownMapItemIsRefused() {
            assertThat(screenFieldOf(ReportRequestRequest.ScreenField.SDTMM.inputItem()))
                    .isEqualTo(ReportRequestRequest.ScreenField.SDTMM);
            assertThatIllegalArgumentException()
                    .as("the xxxL, xxxF and xxxA items are metadata and are not settable from a case")
                    .isThrownBy(() -> screenFieldOf(
                            ReportRequestRequest.ScreenField.SDTMM.lengthItem()))
                    .withMessageContaining("app/cpy-bms/CORPT00.CPY declares");
        }

        @Test
        @DisplayName("the report-type EVALUATE is ordered: monthly, then yearly, then custom")
        void theReportTypeArmsAreTakenInSourceOrder() {
            assertThat(reportOf("Y", "Y", "Y"))
                    .as("all three selected: :213 wins because EVALUATE takes the FIRST matching WHEN")
                    .isEqualTo(ReportRequestController.REPORT_NAME_MONTHLY);
            assertThat(reportOf(" ", "Y", "Y"))
                    .as("monthly blank: :239 wins over :256")
                    .isEqualTo(ReportRequestController.REPORT_NAME_YEARLY);
            assertThat(reportOf(" ", " ", "Y"))
                    .as("only custom selected: :256")
                    .isEqualTo(ReportRequestController.REPORT_NAME_CUSTOM);
            assertThat(reportOf(" ", " ", " "))
                    .as("WHEN OTHER at :437 assigns no report name at all, so WS-REPORT-NAME stays at "
                            + "the spaces :58 gives it")
                    .isBlank();
        }

        private String reportOf(String monthly, String yearly, String custom) {
            ProgramState state = runEnter(request -> request
                    .withMonthly(monthly).withYearly(yearly).withCustom(custom)
                    .withSdtmm("07").withSdtdd("01").withSdtyyyy("2026")
                    .withEdtmm("07").withEdtdd("31").withEdtyyyy("2026")
                    .withConfirm("Y"));
            return state.reportName().trim();
        }
    }

    @Nested
    @DisplayName("the two date intrinsics and the month-end derivation")
    class TheDateIntrinsics {
        @Test
        @DisplayName("the anchors in both directions, and the undefined results")
        void theAnchorsAndTheUndefinedResults() {
            assertThat(ReportRequestController.integerOfDate(16010101))
                    .as("FUNCTION INTEGER-OF-DATE counts days from 31 December 1600, so 1601-01-01 is "
                            + "day one")
                    .isEqualTo(1);
            assertThat(ReportRequestController.integerOfDate(16011231))
                    .as("1601 was a common year")
                    .isEqualTo(365);
            assertThat(ReportRequestController.dateOfInteger(1)).isEqualTo(16010101);
            assertThat(ReportRequestController.dateOfInteger(365)).isEqualTo(16011231);
            assertThat(ReportRequestController.dateOfInteger(
                    ReportRequestController.integerOfDate(20220719)))
                    .as("the pair is an involution over the supported range")
                    .isEqualTo(20220719);

            assertThat(ReportRequestController.integerOfDate(16001231))
                    .as("below the lowest supported argument the intrinsic is undefined, and this "
                            + "implementation is deterministic about it")
                    .isEqualTo(ReportRequestController.DATE_INTRINSIC_UNDEFINED);
            assertThat(ReportRequestController.integerOfDate(20260231))
                    .as("31 February is not a calendar date")
                    .isEqualTo(ReportRequestController.DATE_INTRINSIC_UNDEFINED);
            assertThat(ReportRequestController.dateOfInteger(
                    ReportRequestController.DATE_OF_INTEGER_LOWEST_ARGUMENT - 1))
                    .isEqualTo(ReportRequestController.DATE_INTRINSIC_UNDEFINED);
            assertThat(ReportRequestController.dateOfInteger(
                    ReportRequestController.DATE_OF_INTEGER_HIGHEST_ARGUMENT))
                    .isEqualTo(ReportRequestController.INTEGER_OF_DATE_HIGHEST_ARGUMENT);
            assertThat(ReportRequestController.dateOfInteger(
                    ReportRequestController.DATE_OF_INTEGER_HIGHEST_ARGUMENT + 1))
                    .isEqualTo(ReportRequestController.DATE_INTRINSIC_UNDEFINED);
        }

        @Test
        @DisplayName("WS-CURDATE-N is composed and read back through its own redefinition")
        void theStandardDateFormAndItsThreeParts() {
            int standard = ReportRequestController.standardDate(2026, 8, 9);
            assertThat(standard).isEqualTo(20260809);
            assertThat(ReportRequestController.yearOfStandardDate(standard)).isEqualTo(2026);
            assertThat(ReportRequestController.monthOfStandardDate(standard)).isEqualTo(8);
            assertThat(ReportRequestController.dayOfStandardDate(standard)).isEqualTo(9);
            assertThat(ReportRequestController.MONTHS_PER_YEAR)
                    .as("IF WS-CURDATE-MONTH > 12 at :225")
                    .isEqualTo(12);
        }

        @ParameterizedTest(name = "on {0} the monthly range is {1} .. {2}")
        @CsvSource({
            "2026-01-15T00:00:00, 2026-01-01, 2026-01-31",
            "2026-04-15T00:00:00, 2026-04-01, 2026-04-30",
            "2024-02-15T00:00:00, 2024-02-01, 2024-02-29",
            "2023-02-15T00:00:00, 2023-02-01, 2023-02-28",
            "2026-12-15T00:00:00, 2026-12-01, 2026-12-31"
        })
        @DisplayName("the composite yields the last day of the current month, December included")
        void theMonthEndDerivation(String pinned, String expectedStart, String expectedEnd) {
            ProgramState state = runEnterAt(pinned,
                    request -> request.withMonthly("Y").withConfirm("Y"));

            assertThat(state.parmStartDate2()).isEqualTo(expectedStart);
            assertThat(state.parmEndDate2()).isEqualTo(expectedEnd);
            assertThat(state.parmStartDate1())
                    .as("PARM-START-DATE-1 and PARM-START-DATE-2 are distinct storage at :106 and :118, "
                            + "and :220-221 moves the same value into both")
                    .isEqualTo(state.parmStartDate2());
            assertThat(state.parmEndDate1()).isEqualTo(state.parmEndDate2());
        }
    }

    @Nested
    @DisplayName("FUNCTION NUMVAL-C: what it accepts, what it rejects, and what a rejection stores")
    class TheNumvalCIntrinsic {
        @ParameterizedTest(name = "NUMVAL-C(\"{0}\") = {1}")
        @CsvSource(quoteCharacter = '`', value = {
            "`07`, 7",
            "`$ 1,234.56`, 1234.56",
            "`$1,234,567.89`, 1234567.89",
            "` 7`, 7",
            "`7 `, 7",
            "`- $12`, -12",
            "`+7`, 7",
            "`-7`, -7",
            "`7-`, -7",
            "`7CR`, -7",
            "`7DB`, -7",
            "`.5`, 0.5",
            "`12.`, 12"
        })
        @DisplayName("accepts every argument form the intrinsic documents")
        void theAcceptedForms(String image, String expected) {
            assertThat(ReportRequestController.numvalC(image))
                    .as("FUNCTION NUMVAL-C returns the value the character representation denotes, as a "
                            + "BigDecimal so every digit survives - never as double or float")
                    .isEqualByComparingTo(expected);
            assertThat(ReportRequestController.testNumvalC(image))
                    .as("and the conformance test agrees that it conformed")
                    .isEqualTo(ReportRequestController.NUMVAL_CONFORMS);
        }

        @ParameterizedTest(name = "NUMVAL-C(\"{0}\") does not conform and yields zero")
        @CsvSource(quoteCharacter = '`', value = {
            "``",
            "`  `",
            "`ab`",
            "`1.2.3`",
            "`(5)`",
            "`$`",
            "`1a`",
            "`-5-`",
            "`1,,2`",
            "`,5`"
        })
        @DisplayName("rejects every malformed argument, and a rejection is zero")
        void theRejectedForms(String image) {
            String argument = image == null ? "" : image;
            assertThat(ReportRequestController.numvalC(argument))
                    .as("a non-conforming argument yields zero, which is the whole reason the month "
                            + "'ab' becomes '00' at :307 and is then rejected by CSUTLDTC rather than by "
                            + "the month edit")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ReportRequestController.testNumvalC(argument))
                    .as("and the conformance test says so, which is what distinguishes the zero of "
                            + "\"%s\" from the zero of \"00\"", argument)
                    .isNotEqualTo(ReportRequestController.NUMVAL_CONFORMS);
        }

        @Test
        @DisplayName("TEST-NUMVAL-C reports the offending character position, not merely a failure")
        void theConformanceCodesArePositions() {
            assertThat(ReportRequestController.testNumvalC("00"))
                    .as("'00' conforms; it is a valid representation of zero")
                    .isEqualTo(ReportRequestController.NUMVAL_CONFORMS);
            assertThat(ReportRequestController.testNumvalC("1a"))
                    .as("the one-based position of the first character in error")
                    .isEqualTo(2);
            assertThat(ReportRequestController.testNumvalC("  "))
                    .as("an argument holding no digit at all reports its length plus one")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("the store is back through PIC 99 and PIC 9999, low-order digits first")
        void theNormalisationInPlace() {
            ReportRequestController controller = controllerAt("2026-08-09T14:05:06");

            assertThat(controller.computeIntoPic99(" 7")).isEqualTo("07");
            assertThat(controller.computeIntoPic99("7 ")).isEqualTo("07");
            assertThat(controller.computeIntoPic99("13")).isEqualTo("13");
            assertThat(controller.computeIntoPic99("ab"))
                    .as("a rejected argument is zero, and zero in PIC 99 is '00'")
                    .isEqualTo("00");
            assertThat(controller.computeIntoPic99("-5"))
                    .as("WS-NUM-99 is declared PIC 99 - unsigned - so the sign is dropped on the store")
                    .isEqualTo("05");
            assertThat(controller.computeIntoPic99("123"))
                    .as("a value too wide for the receiver keeps its low-order digits, which is what a "
                            + "COBOL store without ON SIZE ERROR does")
                    .isEqualTo("23");
            assertThat(controller.computeIntoPic9999("2026")).isEqualTo("2026");
            assertThat(controller.computeIntoPic9999(" 26")).isEqualTo("0026");
            assertThat(controller.computeIntoPic9999("abcd")).isEqualTo("0000");
        }

        @Test
        @DisplayName("the store truncates the fraction rather than rounding it away")
        void theStoreTruncatesBecauseROUNDEDIsNeverWritten() {
            assertThat(CobolDecimal.COBOL_ROUNDING)
                    .as("the keyword ROUNDED appears zero times in all twenty-eight programs and zero "
                            + "times in app/cbl/CORPT00C.cbl, so a COBOL store truncates the excess "
                            + "fractional digits - which makes DOWN the only faithful mode and HALF_UP "
                            + "or HALF_EVEN a silent behaviour change (rule R2, gate G24)")
                    .isEqualTo(java.math.RoundingMode.DOWN);

            assertThat(ReportRequestController.numvalC("7.9"))
                    .as("FUNCTION NUMVAL-C keeps the fraction; it is the STORE that discards it")
                    .isEqualByComparingTo("7.9");
            assertThat(CobolDecimal.storeAtPicture(ReportRequestController.numvalC("7.9"),
                            ReportRequestController.WS_NUM_99_DIGITS,
                            ReportRequestController.INTEGER_SCALE))
                    .as("WS-NUM-99 is declared PIC 99 at :74 - two digits and no V - so COMPUTE "
                            + "WS-NUM-99 = FUNCTION NUMVAL-C(...) at :305 stores 7, never 8")
                    .isEqualByComparingTo("7");

            ReportRequestController controller = controllerAt(DEFAULT_INSTANT);
            assertThat(controller.computeIntoPic99("7.9"))
                    .as("and the two-digit image the map item receives is '07', which a rounding store "
                            + "would have made '08'")
                    .isEqualTo("07");
            assertThat(controller.computeIntoPic9999("2026.99"))
                    .as("WS-NUM-9999 is declared PIC 9999 at :75, and the same rule applies to it")
                    .isEqualTo("2026");
        }

        @Test
        @DisplayName("the text edits compare characters, not values")
        void theTextEditsAreAlphanumericComparisons() {
            assertThat(ReportRequestController.isNotValidTwoDigitPart("12",
                    ReportRequestController.HIGHEST_MONTH))
                    .as("SDTMMI > '12' at :330 is false for '12' itself")
                    .isFalse();
            assertThat(ReportRequestController.isNotValidTwoDigitPart("13",
                    ReportRequestController.HIGHEST_MONTH))
                    .isTrue();
            assertThat(ReportRequestController.isNotValidTwoDigitPart("31",
                    ReportRequestController.HIGHEST_DAY))
                    .isFalse();
            assertThat(ReportRequestController.isNotValidTwoDigitPart("32",
                    ReportRequestController.HIGHEST_DAY))
                    .isTrue();
            assertThat(ReportRequestController.isNotValidTwoDigitPart("a1",
                    ReportRequestController.HIGHEST_MONTH))
                    .as("IS NOT NUMERIC is the first of the two conditions at :329")
                    .isTrue();
            assertThat(ReportRequestController.isNumericClass("0000")).isTrue();
            assertThat(ReportRequestController.isNumericClass("20a6")).isFalse();
        }
    }

    @Nested
    @DisplayName("the CSUTLDTC acceptance rule: '0000', or message number '2513', and nothing else")
    class TheCsutldtcAcceptanceRule {
        private final DateUtilityJob dateUtility = new DateUtilityJob();

        @Test
        @DisplayName("a converted date reports severity '0000' and the arm CONTINUEs")
        void severityZeroIsAccepted() {
            DateValidationResult result = validate("2026-07-01");

            assertThat(result.severityCode())
                    .isEqualTo(ReportRequestController.CSUTLDTC_SEVERITY_OK);
            assertThat(result.returnCode()).isZero();
            assertThat(result.message())
                    .as("the eighty-byte WS-MESSAGE image CSUTLDTC composes; CORPT00C reads two spans "
                            + "of it and puts none of it on the screen")
                    .hasSize(DateUtilityJob.LS_RESULT_LENGTH);
        }

        @Test
        @DisplayName("message number '2513' is tolerated even though the severity is not '0000'")
        void theOneToleratedError() {
            DateValidationResult result = validate("1582-10-14");

            assertThat(result.severityCode())
                    .as("14 October 1582 is the day before the Lillian epoch, so it does not convert")
                    .isNotEqualTo(ReportRequestController.CSUTLDTC_SEVERITY_OK);
            assertThat(result.messageNumber())
                    .as("FC-UNSUPP-RANGE - the one non-zero outcome both call sites accept, because the "
                            + "inner IF at :399 and :419 is a NOT = test")
                    .isEqualTo(ReportRequestController.CSUTLDTC_TOLERATED_MESSAGE_NUMBER);
        }

        @ParameterizedTest(name = "{0} is rejected with message number {1}")
        @CsvSource({
            "2026-00-15, 2517",
            "0000-07-01, 2521",
            "2023-02-29, 2508"
        })
        @DisplayName("every other non-zero severity rejects")
        void everyOtherErrorRejects(String date, String messageNumber) {
            DateValidationResult result = validate(date);

            assertThat(result.severityCode())
                    .isNotEqualTo(ReportRequestController.CSUTLDTC_SEVERITY_OK);
            assertThat(result.messageNumber())
                    .isEqualTo(messageNumber)
                    .isNotEqualTo(ReportRequestController.CSUTLDTC_TOLERATED_MESSAGE_NUMBER);
        }

        @Test
        @DisplayName("the rule is applied by the controller at both call sites")
        void bothCallSitesApplyTheSameRule() {
            ProgramState tolerated = runEnter(request -> request.withCustom("Y")
                    .withSdtmm("10").withSdtdd("14").withSdtyyyy("1582")
                    .withEdtmm("10").withEdtdd("14").withEdtyyyy("1582")
                    .withConfirm("Y"));
            assertThat(tolerated.errFlagOff())
                    .as("both dates are the tolerated outcome, so neither :400 nor :420 rejects")
                    .isTrue();
            assertThat(tolerated.submittedRecords())
                    .hasSize(ReportRequestController.JOB_LINE_COUNT);

            ProgramState endRejected = runEnter(request -> request.withCustom("Y")
                    .withSdtmm("07").withSdtdd("01").withSdtyyyy("2026")
                    .withEdtmm("02").withEdtdd("30").withEdtyyyy("2026")
                    .withConfirm("Y"));
            assertThat(endRejected.message())
                    .as("30 February is rejected by the SECOND call site at :412, and :420 carries its "
                            + "own literal rather than the start date's")
                    .startsWith(ReportRequestController.MSG_END_DATE_INVALID);
            assertThat(endRejected.cursorRequestedOn(ReportRequestRequest.ScreenField.EDTMM))
                    .as("MOVE -1 TO EDTMML at :423")
                    .isTrue();
            assertThat(endRejected.submittedRecords()).isEmpty();
        }

        private DateValidationResult validate(String date) {
            return dateUtility.validateDate(date, ReportRequestController.WS_DATE_FORMAT);
        }
    }

    @Nested
    @DisplayName("statelessness: the conversation travels in the payload and nowhere else")
    class TheStatelessConversation {
        @Test
        @DisplayName("CARDDEMO-COMMAREA is exactly 160 bytes on the wire, in both directions")
        void theCommareaIsOneHundredAndSixtyBytes() {
            FixedWidthCodec codec = ParityHarness.usAscii().codec();

            assertThat(COMMAREA_LENGTH)
                    .as("app/cpy/COCOM01Y.cpy; CORPT00C declares no extension of its own, so the area "
                            + ":549 and :589 pass is the copybook's own width")
                    .isEqualTo(160);
            assertThat(NavigationContext.LAYOUT.recordLength()).isEqualTo(COMMAREA_LENGTH);

            ProgramState state = runEnter(request -> request.withMonthly("Y").withConfirm("Y"));
            byte[] outbound = state.response().getNavigationContext().toFixedWidth(codec);
            assertThat(outbound).hasSize(COMMAREA_LENGTH);
            assertThat(codec.deserialise(NavigationContext.LAYOUT, outbound))
                    .as("all sixteen fields are named and comparable, which is what makes statelessness "
                            + "assertable at all")
                    .hasSize(NavigationContext.LAYOUT.spans().size());
        }

        @Test
        @DisplayName("two invocations of one controller share no working storage")
        void oneControllerServesTwoRequestsIndependently() {
            ReportRequestController controller = controllerAt("2026-08-09T14:05:06");

            ProgramState first = controller.mainPara(reenter()
                    .withMonthly("Y").withConfirm("Y"));
            ProgramState second = controller.mainPara(reenter());

            assertThat(first.submittedRecords())
                    .as("the first request confirmed a monthly report")
                    .hasSize(ReportRequestController.JOB_LINE_COUNT);
            assertThat(second.submittedRecords())
                    .as("the second selected no report type, so it must emit nothing - if the two "
                            + "shared working storage the first request's records would still be there")
                    .isEmpty();
            assertThat(second.message())
                    .startsWith(ReportRequestController.MSG_SELECT_REPORT_TYPE);
            assertThat(first.message())
                    .as("and the first request's own message must not have been overwritten by the "
                            + "second")
                    .startsWith("Monthly");
        }

        @Test
        @DisplayName("nothing in this test class or in the controller is static and mutable")
        void noStaticMutableStateAnywhere() {
            requireEveryStaticFieldFinal(CORPT00CParityTest.class);
            requireEveryStaticFieldFinal(CapturedJobsQueue.class);
            requireEveryStaticFieldFinal(ReportRequestController.class);
            requireEveryStaticFieldFinal(ProgramState.class);
        }

        private void requireEveryStaticFieldFinal(Class<?> type) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        || field.isSynthetic()) {
                    continue;
                }
                assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers()))
                        .as("%s.%s is static and not final. WORKING-STORAGE is per-task storage, so a "
                                + "static mutable field would leak one request's screen into another's",
                                type.getSimpleName(), field.getName())
                        .isTrue();
            }
        }
    }

    @Nested
    @DisplayName("the seventeen-field projection and the fifteen rejection sites")
    class TheScreenContract {
        @Test
        @DisplayName("both projections of the symbolic map declare seventeen fields, in one order")
        void theTwoProjectionsAgree() {
            assertThat(ReportRequestRequest.FIELD_COUNT)
                    .as("app/cpy-bms/CORPT00.CPY declares seventeen xxxI items, and app/bms/CORPT00.bms "
                            + "seventeen DFHMDF fields")
                    .isEqualTo(17);
            assertThat(ReportRequestRequest.ScreenField.values())
                    .hasSameSizeAs(ReportRequestResponse.ScreenField.values());

            for (int field = 0; field < ReportRequestRequest.FIELD_COUNT; field++) {
                ReportRequestRequest.ScreenField in = ReportRequestRequest.ScreenField.values()[field];
                ReportRequestResponse.ScreenField out =
                        ReportRequestResponse.ScreenField.values()[field];
                assertThat(out.baseName())
                        .as("01 CORPT0AO REDEFINES CORPT0AI, so the two views describe the same field in "
                                + "the same position")
                        .isEqualTo(in.bmsName());
                assertThat(out.payloadLength())
                        .as("%s and %s are the same storage, so they are the same width",
                                in.inputItem(), out.payloadItemName())
                        .isEqualTo(in.declaredLength());
                assertThat(in.lengthItem())
                        .as("the cursor is the length item, and its name is the field's plus '%s'",
                                LENGTH_ITEM_SUFFIX)
                        .isEqualTo(in.bmsName() + LENGTH_ITEM_SUFFIX);
            }
        }

        @Test
        @DisplayName("a send carries all seventeen xxxO items and the one attribute item :448 writes")
        void aSendIsSeventeenFieldsAndOneColour() {
            ProgramState state = runEnter(request -> request.withMonthly("Y").withConfirm("Y"));
            ObservedResponse observed =
                    observedResponseOf(ParityHarness.usAscii().codec(), state);

            assertThat(observed.sends())
                    .as("SEND-TRNRPT-SCREEN ends GO TO RETURN-TO-CICS at :580, so no path sends twice")
                    .hasSize(1);
            assertThat(observed.sends().get(0).fields())
                    .hasSize(ReportRequestRequest.FIELD_COUNT)
                    .containsKey(ReportRequestResponse.ScreenField.ERRMSG.payloadItemName());
            assertThat(observed.sends().get(0).attributes())
                    .as("MOVE DFHGREEN TO ERRMSGC at :448 is the program's only attribute assignment; "
                            + "CORPT00C copies neither CSSETATY nor CSSTRPFY, so no other item is set")
                    .containsExactly(Map.entry(
                            ReportRequestResponse.ScreenField.ERRMSG.colourItemName(),
                            BmsAttributes.colourMnemonic(BmsAttributes.DFHGREEN)));
            assertThat(observed.termination()).isEqualTo(Termination.RETURN_TRANSID);
            assertThat(observed.nextProgram())
                    .as("EXEC CICS RETURN TRANSID('CR00') routes the next input back to this program, "
                            + "which app/csd/CARDDEMO.CSD:409-410 binds to CR00")
                    .isEqualTo(ReportRequestController.PROGRAM_NAME);
            assertThat(observed.nextMapset()).isEqualTo(ReportRequestRequest.MAPSET_NAME);
            assertThat(observed.nextMap()).isEqualTo(ReportRequestRequest.MAP_NAME);
        }

        @Test
        @DisplayName("ERRMSGO is two bytes narrower than WS-MESSAGE, and the move loses them")
        void theEightyByteMessageIsTruncatedToSeventyEight() {
            ProgramState state = runEnter(request -> request);

            assertThat(state.message())
                    .as("WS-MESSAGE PIC X(80) at :39")
                    .hasSize(ReportRequestController.WS_MESSAGE_LENGTH)
                    .startsWith(ReportRequestController.MSG_SELECT_REPORT_TYPE);
            assertThat(state.response().getErrmsgo())
                    .as("MOVE WS-MESSAGE TO ERRMSGO at :560 moves eighty characters into ERRMSGO "
                            + "PIC X(78), truncating on the right")
                    .hasSize(ReportRequestRequest.ERRMSG_LENGTH)
                    .isEqualTo(state.message().substring(0, ReportRequestRequest.ERRMSG_LENGTH));
        }

        @ParameterizedTest(name = "{0} rejects with the literal at :{1} and the cursor on {2}")
        @CsvSource(delimiter = '|', value = {
            "SDTDD | 268 | SDTDDL",
            "SDTYYYY | 275 | SDTYYYYL",
            "EDTMM | 282 | EDTMML",
            "EDTDD | 289 | EDTDDL",
            "EDTYYYY | 296 | EDTYYYYL"
        })
        @DisplayName("the five arms of the blank chain below the first, each with its literal and cursor")
        void theRemainingBlankArms(String blanked, int sourceLine, String cursorItem) {
            ReportRequestRequest.ScreenField field =
                    ReportRequestRequest.ScreenField.valueOf(blanked);
            ProgramState state = runEnter(request -> customRange(request)
                    .withValue(field, " ".repeat(field.declaredLength())));

            assertThat(state.message())
                    .as("app/cbl/CORPT00C.cbl:%d", sourceLine)
                    .startsWith(blankLiteralOf(blanked));
            assertThat(cursorLengthItemOf(state)).isEqualTo(cursorItem);
            assertThat(state.submittedRecords())
                    .as("a rejected arm sends and the send ends the task, so nothing is emitted")
                    .isEmpty();
        }

        @Test
        @DisplayName("the end-date text edits carry their own literals, distinct from the start date's")
        void theEndDateTextEdits() {
            ProgramState badMonth = runEnter(request -> customRange(request).withEdtmm("13"));
            assertThat(badMonth.message()).startsWith(ReportRequestController.MSG_END_DATE_INVALID_MONTH);
            assertThat(cursorLengthItemOf(badMonth)).isEqualTo("EDTMML");

            ProgramState badDay = runEnter(request -> customRange(request).withEdtdd("32"));
            assertThat(badDay.message()).startsWith(ReportRequestController.MSG_END_DATE_INVALID_DAY);
            assertThat(cursorLengthItemOf(badDay)).isEqualTo("EDTDDL");

            ProgramState badStartDay = runEnter(request -> customRange(request).withSdtdd("32"));
            assertThat(badStartDay.message())
                    .startsWith(ReportRequestController.MSG_START_DATE_INVALID_DAY);
            assertThat(cursorLengthItemOf(badStartDay)).isEqualTo("SDTDDL");
        }

        @Test
        @DisplayName("an unrecognised confirm value is quoted back in the message")
        void theInvalidConfirmArm() {
            ProgramState state = runEnter(request -> request.withMonthly("Y").withConfirm("X"));

            assertThat(state.message())
                    .as("STRING '\"' DELIMITED BY SIZE, CONFIRMI DELIMITED BY SPACE and the rest at "
                            + ":485-490")
                    .isEqualTo(ReportRequestController.stringInto(" ".repeat(80),
                            "\"X" + ReportRequestController.MSG_NOT_A_VALID_CONFIRM_VALUE));
            assertThat(cursorLengthItemOf(state)).isEqualTo("CONFIRML");
            assertThat(state.submittedRecords()).isEmpty();

            ProgramState declined = runEnter(request -> request.withMonthly("Y").withConfirm("n"));
            assertThat(declined.message())
                    .as("the 'n' arm at :480 clears WS-MESSAGE through INITIALIZE-ALL-FIELDS and sends "
                            + "an empty form with no message at all")
                    .isBlank();
            assertThat(declined.submittedRecords()).isEmpty();
        }

        private String blankLiteralOf(String blanked) {
            return switch (blanked) {
                case "SDTMM" -> ReportRequestController.MSG_START_DATE_MONTH_EMPTY;
                case "SDTDD" -> ReportRequestController.MSG_START_DATE_DAY_EMPTY;
                case "SDTYYYY" -> ReportRequestController.MSG_START_DATE_YEAR_EMPTY;
                case "EDTMM" -> ReportRequestController.MSG_END_DATE_MONTH_EMPTY;
                case "EDTDD" -> ReportRequestController.MSG_END_DATE_DAY_EMPTY;
                case "EDTYYYY" -> ReportRequestController.MSG_END_DATE_YEAR_EMPTY;
                default -> throw new IllegalArgumentException(blanked + " is not one of the six date "
                        + "parts the blank chain at app/cbl/CORPT00C.cbl:258-303 tests");
            };
        }
    }

    private static final String DEFAULT_INSTANT = "2026-08-09T14:05:06";

    private static ReportRequestController controllerAt(String instant) {
        ParityHarness harness = ParityHarness.usAscii();
        return new ReportRequestController(new DateUtilityJob(),
                new AcceptingJobsQueue(harness.codec()),
                ParityHarness.fixedClockAt(java.time.LocalDateTime.parse(instant)),
                harness.charset());
    }

    private static ReportRequestRequest reenter() {
        ReportRequestRequest initial = ReportRequestRequest.empty();
        return initial
                .withNavigationContext(initial.navigationContext().withPgmReenter())
                .withAid(PfKeyResolver.aidImage(CicsAid.DFHENTER));
    }

    private static ReportRequestRequest customRange(ReportRequestRequest request) {
        return request.withCustom("Y")
                .withSdtmm("07").withSdtdd("01").withSdtyyyy("2026")
                .withEdtmm("07").withEdtdd("31").withEdtyyyy("2026")
                .withConfirm("Y");
    }

    private static ProgramState runEnter(
            java.util.function.UnaryOperator<ReportRequestRequest> screen) {
        return runEnterAt(DEFAULT_INSTANT, screen);
    }

    private static ProgramState runEnterAt(String instant,
            java.util.function.UnaryOperator<ReportRequestRequest> screen) {
        return controllerAt(instant).mainPara(screen.apply(reenter()));
    }

    /**
     * The queue the structural assertions run through: it enforces the eighty-byte contract and accepts
     * every record, which is what {@code DFHRESP(NORMAL)} means.
     */
    private static final class AcceptingJobsQueue implements JobSubmissionPort {
        private final FixedWidthCodec codec;

        AcceptingJobsQueue(FixedWidthCodec codec) {
            this.codec = Objects.requireNonNull(codec, "A codec is required: the queue's RECORDSIZE is a "
                    + "byte count, so the code page has to be named");
        }

        @Override
        public WriteQueueOutcome writeQueueTd(String jclRecord) {
            assertThat(codec.encodeImage(Objects.requireNonNull(jclRecord, "A record is required"),
                            "a " + QUEUE_DATASET + " queue record"))
                    .as("TDQUEUE(JOBS) declares RECORDSIZE(80) RECORDFORMAT(FIXED)")
                    .hasSize(ReportRequestController.JCL_RECORD_LENGTH);
            return WriteQueueOutcome.NORMAL;
        }
    }

    private static final Locale COMPARISON_LOCALE = Locale.ROOT;

    @Test
    @DisplayName("the source's own inconsistent capitalisation is preserved, not folded away")
    void localeIsNotConsulted() {
        assertThat(ReportRequestController.MSG_START_DATE_INVALID_MONTH)
                .as("app/cbl/CORPT00C.cbl:331 - capital M")
                .isEqualTo("Start Date - Not a valid Month...")
                .isNotEqualTo(ReportRequestController.MSG_START_DATE_INVALID_MONTH
                        .toLowerCase(COMPARISON_LOCALE));
        assertThat(ReportRequestController.MSG_START_DATE_INVALID)
                .as("app/cbl/CORPT00C.cbl:400 - lower-case d, from a different edit; the inconsistency "
                        + "is the source's and is not corrected")
                .isEqualTo("Start Date - Not a valid date...");
    }
}
