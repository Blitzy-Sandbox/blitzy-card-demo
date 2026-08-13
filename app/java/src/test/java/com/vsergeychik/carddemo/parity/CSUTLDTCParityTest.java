package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.ParityCase.EmittedMessage;
import com.vsergeychik.carddemo.parity.ParityCase.MessageChannel;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.util.DateUtilityJob;
import com.vsergeychik.carddemo.util.DateUtilityJob.DateValidationResult;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.stereotype.Service;

/**
 * The twenty-case parity gate for {@code CSUTLDTC}, the called date-validation subprogram translated to
 * {@code com.vsergeychik.carddemo.util.DateUtilityJob}.
 */
@DisplayName("CSUTLDTC parity - the called date-validation subprogram, 20 cases")
class CSUTLDTCParityTest {
    private static final String PROGRAM = "CSUTLDTC";

    private static final int EXPECTED_CASE_COUNT = 20;

    private static final String CASE_UNSUPPORTED_RANGE_BOUNDARY = "case10";

    private static final FieldSpan WS_SEVERITY = FieldSpan.alphanumeric("WS-SEVERITY", 0, 4);

    private static final FieldSpan WS_SEVERITY_N =
            FieldSpan.redefining("WS-SEVERITY-N", 0, 4, PictureKind.UNSIGNED_NUMERIC);

    private static final FieldSpan FILLER_MESG_CODE = FieldSpan.filler(4, 11, "Mesg Code:");

    private static final FieldSpan WS_MSG_NO = FieldSpan.alphanumeric("WS-MSG-NO", 15, 4);

    private static final FieldSpan WS_MSG_NO_N =
            FieldSpan.redefining("WS-MSG-NO-N", 15, 4, PictureKind.UNSIGNED_NUMERIC);

    private static final FieldSpan FILLER_AFTER_MSG_NO = FieldSpan.filler(19, 1);

    private static final FieldSpan WS_RESULT = FieldSpan.alphanumeric("WS-RESULT", 20, 15);

    private static final FieldSpan FILLER_AFTER_RESULT = FieldSpan.filler(35, 1);

    private static final FieldSpan FILLER_TST_DATE = FieldSpan.filler(36, 9, "TstDate:");

    private static final FieldSpan WS_DATE = FieldSpan.alphanumeric("WS-DATE", 45, 10);

    private static final FieldSpan FILLER_AFTER_DATE = FieldSpan.filler(55, 1);

    private static final FieldSpan FILLER_MASK_USED = FieldSpan.filler(56, 10, "Mask used:");

    private static final FieldSpan WS_DATE_FMT = FieldSpan.alphanumeric("WS-DATE-FMT", 66, 10);

    private static final FieldSpan FILLER_AFTER_FMT = FieldSpan.filler(76, 1);

    private static final FieldSpan FILLER_TRAILING = FieldSpan.filler(77, 3);

    private static final RecordLayout WS_MESSAGE_LAYOUT = RecordLayout.of(
            DateUtilityJob.LS_RESULT_LENGTH,
            WS_SEVERITY,
            WS_SEVERITY_N,
            FILLER_MESG_CODE,
            WS_MSG_NO,
            WS_MSG_NO_N,
            FILLER_AFTER_MSG_NO,
            WS_RESULT,
            FILLER_AFTER_RESULT,
            FILLER_TST_DATE,
            WS_DATE,
            FILLER_AFTER_DATE,
            FILLER_MASK_USED,
            WS_DATE_FMT,
            FILLER_AFTER_FMT,
            FILLER_TRAILING);

    private record EvaluateArm(int ordinal,
                               String conditionName,
                               String token,
                               String severityCode,
                               String messageNumber,
                               String resultText,
                               String lsDate,
                               String lsDateFormat) {
        boolean reachable() {
            return lsDate != null;
        }

        String paddedResultText() {
            return movePicX(resultText, WS_RESULT.length());
        }
    }

    private static final List<EvaluateArm> ARMS = List.of(
            new EvaluateArm(1, "FC-INVALID-DATE", "0000000000000000",
                    "0000", "0000", "Date is valid", "2022-07-19", "YYYY-MM-DD"),
            new EvaluateArm(2, "FC-INSUFFICIENT-DATA", "000309CB59C3C5C5",
                    "0003", "2507", "Insufficient", "2022-07-19", "          "),
            new EvaluateArm(3, "FC-BAD-DATE-VALUE", "000309CC59C3C5C5",
                    "0003", "2508", "Datevalue error", "2023-02-29", "YYYY-MM-DD"),
            new EvaluateArm(4, "FC-INVALID-ERA", "000309CD59C3C5C5",
                    "0003", "2509", "Invalid Era    ", "XX220719  ", "<XX>YYMMDD"),
            new EvaluateArm(5, "FC-UNSUPP-RANGE", "000309D159C3C5C5",
                    "0003", "2513", "Unsupp. Range  ", "1582-10-14", "YYYY-MM-DD"),
            new EvaluateArm(6, "FC-INVALID-MONTH", "000309D559C3C5C5",
                    "0003", "2517", "Invalid month  ", "2022-00-15", "YYYY-MM-DD"),
            new EvaluateArm(7, "FC-BAD-PIC-STRING", "000309D659C3C5C5",
                    "0003", "2518", "Bad Pic String ", "2022-07-19", "QQQQ-MM-DD"),
            new EvaluateArm(8, "FC-NON-NUMERIC-DATA", "000309D859C3C5C5",
                    "0003", "2520", "Nonnumeric data", "ABCD-EF-GH", "YYYY-MM-DD"),
            new EvaluateArm(9, "FC-YEAR-IN-ERA-ZERO", "000309D959C3C5C5",
                    "0003", "2521", "YearInEra is 0 ", "0000-01-01", "YYYY-MM-DD"),
            new EvaluateArm(10, "WHEN OTHER", null,
                    null, null, "Date is invalid", null, null));

    private static final String LS_DATE = "LS-DATE";

    private static final String LS_DATE_FORMAT = "LS-DATE-FORMAT";

    private record Linkage(String lsDate, String lsDateFormat) {
        private Linkage {
            if (lsDate.length() != DateUtilityJob.LS_DATE_LENGTH) {
                throw new IllegalArgumentException("LS-DATE is PIC X(10), so '" + lsDate + "' must be "
                        + DateUtilityJob.LS_DATE_LENGTH + " characters, not " + lsDate.length()
                        + ". Write the trailing spaces out - the declared width changes the outcome.");
            }
            if (lsDateFormat.length() != DateUtilityJob.LS_DATE_FORMAT_LENGTH) {
                throw new IllegalArgumentException("LS-DATE-FORMAT is PIC X(10), so '" + lsDateFormat
                        + "' must be " + DateUtilityJob.LS_DATE_FORMAT_LENGTH + " characters, not "
                        + lsDateFormat.length() + ". A longer mask is truncated by the receiving move "
                        + "and then reports a different feedback token than it appears to ask for.");
            }
        }
    }

    private static final int VSTRING_LENGTH_BYTES = 2;

    private static final int GROUP_MOVE_SURVIVING_TEXT_BYTES = 8;

    private static final int BYTE_MASK = 0xFF;

    private static final String TEN_SPACES = "          ";

    private static final String SPRING_BATCH_PACKAGE = "org.springframework.batch.";

    private static final String CONFIGURATION_ANNOTATION =
            "org.springframework.context.annotation.Configuration";

    private static final String BEAN_ANNOTATION = "org.springframework.context.annotation.Bean";

    private static List<ParityCase> cases() {
        return ParityHarness.casesOf(PROGRAM);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("every case diffs to zero against its statically derived 80-byte expectation")
    void everyCaseDiffsToZero(ParityCase parityCase) {
        DiffResult diff = ParityHarness.usAscii().judge(parityCase, UnitKind.SERVICE, invocation -> {
            Linkage linkage = linkageFrom(invocation.stimulus());
            DateValidationResult result = new DateUtilityJob(invocation.charset())
                    .validateDate(linkage.lsDate(), linkage.lsDateFormat());
            return invocation.recorder()
                    .message(new EmittedMessage(MessageChannel.WS_MESSAGE_80, result.message()))
                    .returnCode(result.returnCode())
                    .build();
        });

        assertThat(diff.count())
                .as("%s must diff to zero. The gate is a diff count of zero across all %d cases, so "
                        + "one difference on one case leaves the module incomplete.%n%s",
                        parityCase.caseId(), EXPECTED_CASE_COUNT, diff.render())
                .isZero();
    }

    @Test
    @DisplayName("the program declares exactly 20 cases, case01 through case20")
    void theProgramDeclaresExactlyTwentyCases() {
        List<ParityCase> loaded = cases();

        assertThat(loaded)
                .as("CSUTLDTC must declare exactly %d parity cases; a shorter set is not a smaller "
                        + "gate, it is a gate that passes without asking the questions",
                        EXPECTED_CASE_COUNT)
                .hasSize(EXPECTED_CASE_COUNT);

        List<String> caseIds = new ArrayList<>(loaded.size());
        for (ParityCase parityCase : loaded) {
            caseIds.add(parityCase.caseId());
            assertThat(parityCase.program())
                    .as("every case in parity/%s/ must name that program", PROGRAM)
                    .isEqualTo(PROGRAM);
        }
        assertThat(caseIds)
                .as("every shipped case declares the two CALL parameters this subprogram takes, so the "
                        + "set of cases and the set of declared linkage pairs are the same set")
                .containsExactlyElementsOf(shippedLinkage().keySet().stream().sorted().toList());
    }

    @Test
    @DisplayName("every case is a SERVICE case that seeds no dataset, writes nothing and paints no screen")
    void everyCaseIsAServiceCaseWithNoDatasetNoJobParameterAndNoScreen() {
        for (ParityCase parityCase : cases()) {
            assertThat(parityCase.unitKind())
                    .as("%s: CSUTLDTC is invoked as the @Service it became, never as a batch job",
                            parityCase.caseId())
                    .isEqualTo(UnitKind.SERVICE);
            assertThat(parityCase.inputs())
                    .as("%s: CSUTLDTC declares no SELECT and opens no file, so a seeded dataset would "
                            + "describe a different program", parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.jobParameters())
                    .as("%s: no EXEC PGM= anywhere invokes CSUTLDTC, so there is no PARM to parse",
                            parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.screenRequest())
                    .as("%s: CSUTLDTC issues no EXEC CICS and owns no BMS map", parityCase.caseId())
                    .isNull();
            assertThat(parityCase.expectedResponse())
                    .as("%s: a called subprogram returns bytes, not a screen", parityCase.caseId())
                    .isNull();
            assertThat(parityCase.expectedWrites())
                    .as("%s: CSUTLDTC writes no record", parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.expectedFinalState())
                    .as("%s: with nothing seeded there is no final state to state",
                            parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.expectedMessages())
                    .as("%s: the whole observable output is the one 80-byte LS-RESULT line",
                            parityCase.caseId())
                    .hasSize(1);
            assertThat(parityCase.expectedMessages().get(0).channel())
                    .as("%s: LS-RESULT is PIC X(80), so the line belongs on the 80-byte channel and "
                            + "not on the 78-byte screen channel", parityCase.caseId())
                    .isEqualTo(MessageChannel.WS_MESSAGE_80);
        }
    }

    @Test
    @DisplayName("each case description quotes the exact LS-DATE and LS-DATE-FORMAT it is run with")
    void eachCaseDescriptionStatesTheLinkageParametersItIsRunWith() {
        for (ParityCase parityCase : cases()) {
            Linkage linkage = linkageFor(parityCase);
            assertThat(parityCase.description())
                    .as("%s must quote LS-DATE='%s' so the case file and the LINKAGE table it is run "
                            + "from cannot drift apart", parityCase.caseId(), linkage.lsDate())
                    .contains("LS-DATE='" + linkage.lsDate() + "'");
            assertThat(parityCase.description())
                    .as("%s must quote LS-DATE-FORMAT='%s' for the same reason",
                            parityCase.caseId(), linkage.lsDateFormat())
                    .contains("LS-DATE-FORMAT='" + linkage.lsDateFormat() + "'");
        }
    }

    private static Linkage linkageFor(ParityCase parityCase) {
        return linkageFrom(parityCase.unitStimulus());
    }

    private static Linkage linkageFrom(ParityCase.UnitStimulus stimulus) {
        if (!stimulus.callSiteOutcomes().isEmpty() || !stimulus.operationScript().isEmpty()
                || !stimulus.stepStatuses().isEmpty() || !stimulus.environment().isEmpty()) {
            throw new IllegalArgumentException(PROGRAM + " opens no file, calls no subprogram of its "
                    + "own, follows no conditional job step and runs under no environmental variant: it "
                    + "is pure computation over two CALL parameters, so its only stimulus is its "
                    + "linkage.");
        }
        return new Linkage(requireLinkage(stimulus, LS_DATE), requireLinkage(stimulus, LS_DATE_FORMAT));
    }

    private static String requireLinkage(ParityCase.UnitStimulus stimulus, String name) {
        String value = stimulus.linkage().get(name);
        if (value == null) {
            throw new IllegalArgumentException("No " + name + " is declared in unitStimulus.linkage. "
                    + PROGRAM + "'s inputs are its two CALL parameters rather than seeded datasets, so a "
                    + "case that declares neither declares nothing and would validate whatever the "
                    + "default happened to be. Declared: " + stimulus.linkage().keySet() + '.');
        }
        return value;
    }

    private static Map<String, Linkage> shippedLinkage() {
        Map<String, Linkage> declared = new LinkedHashMap<>();
        for (ParityCase parityCase : ParityHarness.casesOf(PROGRAM)) {
            declared.put(parityCase.caseId(), linkageFor(parityCase));
        }
        return Collections.unmodifiableMap(declared);
    }

    @Test
    @DisplayName("the ten EVALUATE arms are transcribed in source order with WHEN OTHER last")
    void theTenArmsAreTranscribedInSourceOrderWithWhenOtherLast() {
        assertThat(ARMS)
                .as("the EVALUATE TRUE at :128-:149 has nine named WHENs plus WHEN OTHER")
                .hasSize(10);

        Set<String> tokens = new LinkedHashSet<>();
        Set<String> resultTexts = new LinkedHashSet<>();
        for (int index = 0; index < ARMS.size(); index++) {
            EvaluateArm arm = ARMS.get(index);
            assertThat(arm.ordinal())
                    .as("arm at list position %d must be arm %d - the list order IS the EVALUATE "
                            + "order, because the first matching WHEN wins", index, index + 1)
                    .isEqualTo(index + 1);
            assertThat(arm.paddedResultText())
                    .as("arm %d moves '%s' into WS-RESULT PIC X(15), which pads it to fifteen",
                            arm.ordinal(), arm.resultText())
                    .hasSize(WS_RESULT.length());
            assertThat(resultTexts.add(arm.paddedResultText()))
                    .as("arm %d shares its result text with an earlier arm, so the two would be "
                            + "indistinguishable in the 80-byte message", arm.ordinal())
                    .isTrue();

            if (arm.reachable()) {
                assertThat(tokens.add(arm.token()))
                        .as("arm %d repeats a feedback token already declared. The nine 88-levels are "
                                + "mutually exclusive precisely because their VALUEs differ, and that "
                                + "exclusivity is what makes 'first match wins' observable at all",
                                arm.ordinal())
                        .isTrue();
                assertThat(Integer.parseInt(arm.severityCode()))
                        .as("arm %d (%s): severity must equal the first halfword of %s",
                                arm.ordinal(), arm.conditionName(), arm.token())
                        .isEqualTo(halfword(arm.token(), 0));
                assertThat(Integer.parseInt(arm.messageNumber()))
                        .as("arm %d (%s): message number must equal the second halfword of %s",
                                arm.ordinal(), arm.conditionName(), arm.token())
                        .isEqualTo(halfword(arm.token(), 1));
            }
        }

        EvaluateArm last = ARMS.get(ARMS.size() - 1);
        assertThat(last.conditionName())
                .as("WHEN OTHER must be last: it is the default rather than a match, so moving it "
                        + "earlier would capture tokens the named arms own")
                .isEqualTo("WHEN OTHER");
        assertThat(last.reachable())
                .as("WHEN OTHER is selected only by a token no 88-level names, so no input reaches it")
                .isFalse();
    }

    @Test
    @DisplayName("each reachable arm is selected by its own input and by no other arm's")
    void eachReachableArmIsSelectedByItsOwnInputAndNoOther() {
        Charset charset = DateUtilityJob.DEFAULT_MESSAGE_CHARSET;

        for (EvaluateArm arm : ARMS) {
            if (!arm.reachable()) {
                continue;
            }
            DateValidationResult result = new DateUtilityJob(charset)
                    .validateDate(arm.lsDate(), arm.lsDateFormat());

            assertThat(result.result())
                    .as("arm %d (%s) must be selected by LS-DATE='%s' with mask '%s'",
                            arm.ordinal(), arm.conditionName(), arm.lsDate(), arm.lsDateFormat())
                    .isEqualTo(arm.paddedResultText());
            assertThat(result.severityCode())
                    .as("arm %d writes its severity through WS-SEVERITY-N at :123", arm.ordinal())
                    .isEqualTo(arm.severityCode());
            assertThat(result.messageNumber())
                    .as("arm %d writes its message number through WS-MSG-NO-N at :124", arm.ordinal())
                    .isEqualTo(arm.messageNumber());

            for (EvaluateArm other : ARMS) {
                if (other.ordinal() == arm.ordinal()) {
                    continue;
                }
                assertThat(result.result())
                        .as("the input for arm %d must not also select arm %d (%s) - the arms are "
                                + "mutually exclusive", arm.ordinal(), other.ordinal(),
                                other.conditionName())
                        .isNotEqualTo(other.paddedResultText());
            }
        }
    }

    @Test
    @DisplayName("the 20 cases cover all nine reachable arms, including 00 and 13 months and 2513")
    void theTwentyCasesCoverEveryReachableArm() {
        Charset charset = DateUtilityJob.DEFAULT_MESSAGE_CHARSET;
        Set<String> observedResults = new LinkedHashSet<>();
        Set<Integer> observedReturnCodes = new LinkedHashSet<>();

        for (ParityCase parityCase : cases()) {
            Linkage linkage = linkageFor(parityCase);
            DateValidationResult result = new DateUtilityJob(charset)
                    .validateDate(linkage.lsDate(), linkage.lsDateFormat());
            observedResults.add(result.result());
            observedReturnCodes.add(result.returnCode());
        }

        for (EvaluateArm arm : ARMS) {
            if (!arm.reachable()) {
                continue;
            }
            assertThat(observedResults)
                    .as("no case reaches arm %d (%s). Every reachable arm needs one, because an arm "
                            + "no case reaches is an arm nothing is asserting about",
                            arm.ordinal(), arm.conditionName())
                    .contains(arm.paddedResultText());
        }

        assertMessageNumber(charset, "case12", "2517");
        assertMessageNumber(charset, "case13", "2517");
        assertMessageNumber(charset, "case19", "2520");
        assertMessageNumber(charset, CASE_UNSUPPORTED_RANGE_BOUNDARY, "2513");

        assertThat(observedReturnCodes)
                .as("RETURN-CODE is the severity :98 moves, so the twenty cases must show both values: "
                        + "0 for a converted date and 3 for every named error")
                .containsExactlyInAnyOrder(0, 3);
    }

    @Test
    @DisplayName("RETURN-CODE is 0 for a valid date and 3 for every named error")
    void returnCodeIsZeroForAValidDateAndThreeForEveryNamedError() {
        Charset charset = DateUtilityJob.DEFAULT_MESSAGE_CHARSET;

        for (EvaluateArm arm : ARMS) {
            if (!arm.reachable()) {
                continue;
            }
            DateValidationResult result = new DateUtilityJob(charset)
                    .validateDate(arm.lsDate(), arm.lsDateFormat());

            int severityFromToken = halfword(arm.token(), 0);
            assertThat(result.returnCode())
                    .as("arm %d (%s): RETURN-CODE is WS-SEVERITY-N, which :123 took from the first "
                            + "halfword of %s", arm.ordinal(), arm.conditionName(), arm.token())
                    .isEqualTo(severityFromToken);
            assertThat(result.returnCode())
                    .as("CSUTLDTC sets only 0 or 3; it is not one of the nine CALL 'CEE3ABD' sites and "
                            + "never sets 4, 8 or 12")
                    .isIn(0, 3);
            assertThat(Integer.parseInt(result.severityCode()))
                    .as("arm %d: the character view at offset 0 and the numeric RETURN-CODE are two "
                            + "readings of the same four bytes", arm.ordinal())
                    .isEqualTo(result.returnCode());
        }
    }

    @Test
    @DisplayName("WHEN OTHER cannot be reached by any input - proved, not assumed")
    void theWhenOtherArmIsUnreachableThroughTheByteContract() {
        Charset charset = DateUtilityJob.DEFAULT_MESSAGE_CHARSET;
        DateUtilityJob service = new DateUtilityJob(charset);
        String whenOther = ARMS.get(ARMS.size() - 1).paddedResultText();

        Set<String> reachableResults = new LinkedHashSet<>();
        for (EvaluateArm arm : ARMS) {
            if (arm.reachable()) {
                reachableResults.add(arm.paddedResultText());
            }
        }

        List<String> masks = List.of(
                "YYYY-MM-DD", "DD/MM/YYYY", "YYYYMMDD  ", "YY-MM-DD  ", "YYYY-DDD  ",
                "YYYYMMMDD ", "<AD>YYMMDD", "<BC>YYMMDD", "<XX>YYMMDD", "          ",
                "YYYY      ", "QQQQ-MM-DD", "YYYY-MM-YY", "<AD-MM-DD ", "MM-DD-YYYY");
        List<String> dates = List.of(
                "2022-07-19", "0000-01-01", "9999-12-31", "1582-10-15", "1582-10-14",
                "2023-02-29", "2024-02-29", "2022-13-15", "2022-00-15", "2022-06-31",
                "ABCD-EF-GH", "          ", "----------", "0000000000", "2022JUL19 ",
                "XX220719  ", "BC220719  ", "AD220719  ", "2022-366  ", "2022-000  ");

        for (String mask : masks) {
            for (String date : dates) {
                DateValidationResult result = service.validateDate(date, mask);
                assertThat(result.result())
                        .as("LS-DATE='%s' with mask '%s' selected WHEN OTHER, so arm 10 IS reachable "
                                + "and this class must gain a twenty-first route to it", date, mask)
                        .isNotEqualTo(whenOther);
                assertThat(result.result())
                        .as("LS-DATE='%s' with mask '%s' produced a result text no arm of the "
                                + "EVALUATE declares, so the transcription in ARMS is incomplete",
                                date, mask)
                        .isIn(reachableResults);
            }
        }

        for (ParityCase parityCase : cases()) {
            Linkage linkage = linkageFor(parityCase);
            assertThat(service.validateDate(linkage.lsDate(), linkage.lsDateFormat()).result())
                    .as("%s selected WHEN OTHER", parityCase.caseId())
                    .isNotEqualTo(whenOther);
        }
    }

    @Test
    @DisplayName("the 13 storage spans of WS-MESSAGE sum to exactly 80 bytes; the 2 overlays add none")
    void theWsMessageLayoutAccountsForEveryOneOfTheEightyBytes() {
        assertThat(WS_MESSAGE_LAYOUT.recordLength())
                .as("LS-RESULT is PIC X(80) at :86, and WS-MESSAGE is what :97 moves into it")
                .isEqualTo(DateUtilityJob.LS_RESULT_LENGTH);

        int declared = 0;
        for (FieldSpan span : WS_MESSAGE_LAYOUT.storageSpans()) {
            declared += span.length();
        }
        assertThat(declared)
                .as("the storage spans must tile the record exactly; a dropped trailing FILLER is the "
                        + "usual cause of a short total and it shifts nothing, it merely truncates")
                .isEqualTo(DateUtilityJob.LS_RESULT_LENGTH);
        assertThat(WS_MESSAGE_LAYOUT.storageSpans()).hasSize(13);
        assertThat(WS_MESSAGE_LAYOUT.redefinitions())
                .as("WS-SEVERITY-N at :44 and WS-MSG-NO-N at :47 are alternative views of storage the "
                        + "layout has already counted, so they contribute no byte of their own")
                .hasSize(2);

        assertThat(FILLER_MESG_CODE.length())
                .as("the FILLER at :45 is PIC X(11)")
                .isEqualTo(11);
        assertThat(FILLER_MESG_CODE.initialValue())
                .as("its VALUE is 'Mesg Code:', ten characters, so the eleventh byte is a pad")
                .hasSize(10);
        assertThat(FILLER_TST_DATE.length())
                .as("the FILLER at :51 is PIC X(09)")
                .isEqualTo(9);
        assertThat(FILLER_TST_DATE.initialValue())
                .as("its VALUE is 'TstDate:', eight characters, so the ninth byte is a pad")
                .hasSize(8);
        assertThat(FILLER_MASK_USED.initialValue())
                .as("'Mask used:' fills its PIC X(10) exactly, which is why the other two are easy to "
                        + "get wrong")
                .hasSize(FILLER_MASK_USED.length());
    }

    @Test
    @DisplayName("shortening either short FILLER's pad is refused - the record no longer reaches 80")
    void shorteningEitherShortFillerLiteralsPadBreaksTheTotalWidth() {
        assertThatIllegalArgumentException()
                .as("a 'Mesg Code:' FILLER of 10 instead of 11 must be refused")
                .isThrownBy(() -> RecordLayout.of(
                        DateUtilityJob.LS_RESULT_LENGTH,
                        WS_SEVERITY,
                        WS_SEVERITY_N,
                        FieldSpan.filler(4, 10, "Mesg Code:"),
                        WS_MSG_NO,
                        WS_MSG_NO_N,
                        FILLER_AFTER_MSG_NO,
                        WS_RESULT,
                        FILLER_AFTER_RESULT,
                        FILLER_TST_DATE,
                        WS_DATE,
                        FILLER_AFTER_DATE,
                        FILLER_MASK_USED,
                        WS_DATE_FMT,
                        FILLER_AFTER_FMT,
                        FILLER_TRAILING));

        assertThatIllegalArgumentException()
                .as("a 'TstDate:' FILLER of 8 instead of 9 must be refused")
                .isThrownBy(() -> RecordLayout.of(
                        DateUtilityJob.LS_RESULT_LENGTH,
                        WS_SEVERITY,
                        WS_SEVERITY_N,
                        FILLER_MESG_CODE,
                        WS_MSG_NO,
                        WS_MSG_NO_N,
                        FILLER_AFTER_MSG_NO,
                        WS_RESULT,
                        FILLER_AFTER_RESULT,
                        FieldSpan.filler(36, 8, "TstDate:"),
                        WS_DATE,
                        FILLER_AFTER_DATE,
                        FILLER_MASK_USED,
                        WS_DATE_FMT,
                        FILLER_AFTER_FMT,
                        FILLER_TRAILING));

        assertThatIllegalArgumentException()
                .as("omitting the trailing PIC X(03) FILLER at :57 must be refused")
                .isThrownBy(() -> RecordLayout.of(
                        DateUtilityJob.LS_RESULT_LENGTH,
                        WS_SEVERITY,
                        WS_SEVERITY_N,
                        FILLER_MESG_CODE,
                        WS_MSG_NO,
                        WS_MSG_NO_N,
                        FILLER_AFTER_MSG_NO,
                        WS_RESULT,
                        FILLER_AFTER_RESULT,
                        FILLER_TST_DATE,
                        WS_DATE,
                        FILLER_AFTER_DATE,
                        FILLER_MASK_USED,
                        WS_DATE_FMT,
                        FILLER_AFTER_FMT));
    }

    @Test
    @DisplayName("WS-SEVERITY/WS-SEVERITY-N and WS-MSG-NO/WS-MSG-NO-N are two views of one span")
    void theTwoRedefinesPairsAreTwoViewsOfOneSpan() {
        assertThat(WS_SEVERITY_N.offset()).isEqualTo(WS_SEVERITY.offset());
        assertThat(WS_SEVERITY_N.length()).isEqualTo(WS_SEVERITY.length());
        assertThat(WS_SEVERITY_N.redefinition()).isTrue();
        assertThat(WS_SEVERITY.redefinition()).isFalse();
        assertThat(WS_MSG_NO_N.offset()).isEqualTo(WS_MSG_NO.offset());
        assertThat(WS_MSG_NO_N.length()).isEqualTo(WS_MSG_NO.length());
        assertThat(WS_MSG_NO_N.redefinition()).isTrue();
        assertThat(WS_MSG_NO.redefinition()).isFalse();

        FixedWidthCodec codec = new FixedWidthCodec(DateUtilityJob.DEFAULT_MESSAGE_CHARSET);
        for (EvaluateArm arm : ARMS) {
            if (!arm.reachable()) {
                continue;
            }
            FixedWidthRecord wsMessage = codec.newRecord(WS_MESSAGE_LAYOUT);
            codec.writePic9(wsMessage, WS_SEVERITY_N, halfword(arm.token(), 0));
            codec.writePic9(wsMessage, WS_MSG_NO_N, halfword(arm.token(), 1));

            assertThat(codec.readPicX(wsMessage, WS_SEVERITY))
                    .as("arm %d: the character view of the severity span", arm.ordinal())
                    .isEqualTo(arm.severityCode());
            assertThat(codec.readPic9AsInt(wsMessage, WS_SEVERITY_N))
                    .as("arm %d: the numeric view of the very same four bytes", arm.ordinal())
                    .isEqualTo(halfword(arm.token(), 0));
            assertThat(codec.readPicX(wsMessage, WS_MSG_NO))
                    .as("arm %d: the character view of the message-number span", arm.ordinal())
                    .isEqualTo(arm.messageNumber());
            assertThat(codec.readPic9AsInt(wsMessage, WS_MSG_NO_N))
                    .as("arm %d: the numeric view of the very same four bytes", arm.ordinal())
                    .isEqualTo(halfword(arm.token(), 1));
        }
    }

    @Test
    @DisplayName("the 80-byte image reassembled from the declared spans equals what the service emits")
    void theEightyByteImageIsAssembledFromTheDeclaredSpans() {
        Charset charset = DateUtilityJob.DEFAULT_MESSAGE_CHARSET;
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        DateUtilityJob service = new DateUtilityJob(charset);

        for (ParityCase parityCase : cases()) {
            Linkage linkage = linkageFor(parityCase);
            DateValidationResult result = service.validateDate(linkage.lsDate(),
                    linkage.lsDateFormat());
            String reassembled = assembleWsMessage(codec, result.severityCode(),
                    result.messageNumber(), result.result(), linkage.lsDate(),
                    linkage.lsDateFormat());

            assertThat(result.message())
                    .as("%s: the service's LS-RESULT must equal the image reassembled from the "
                            + "WS-MESSAGE declaration at :42-:57", parityCase.caseId())
                    .isEqualTo(reassembled);
            assertThat(result.message())
                    .as("%s: LS-RESULT is PIC X(80)", parityCase.caseId())
                    .hasSize(DateUtilityJob.LS_RESULT_LENGTH);
            assertThat(result.messageBytes())
                    .as("%s: eighty characters must be eighty bytes in %s, which is what makes an "
                            + "offset-addressed record area meaningful", parityCase.caseId(), charset)
                    .hasSize(DateUtilityJob.LS_RESULT_LENGTH);
            assertThat(result.charset())
                    .as("%s: the code page is named by the caller and never defaulted",
                            parityCase.caseId())
                    .isEqualTo(charset);

            String wsDateSpan = result.message()
                    .substring(WS_DATE.offset(), WS_DATE.endOffsetExclusive());
            assertThat(wsDateSpan.substring(VSTRING_LENGTH_BYTES))
                    .as("%s: only the first %d bytes of Vstring-text survive the group move into "
                            + "WS-DATE PIC X(10)", parityCase.caseId(),
                            GROUP_MOVE_SURVIVING_TEXT_BYTES)
                    .isEqualTo(linkage.lsDate().substring(0, GROUP_MOVE_SURVIVING_TEXT_BYTES));
            assertThat(result.message()
                    .substring(WS_DATE_FMT.offset(), WS_DATE_FMT.endOffsetExclusive()))
                    .as("%s: WS-DATE-FMT is filled at :113 and never overwritten afterwards",
                            parityCase.caseId())
                    .isEqualTo(linkage.lsDateFormat());

            DateValidationResult again = service.validateDate(linkage.lsDate(),
                    linkage.lsDateFormat());
            assertThat(again.message())
                    .as("%s: a second call on the same instance must return the same 80 bytes - "
                            + "nothing in WORKING-STORAGE survives between invocations",
                            parityCase.caseId())
                    .isEqualTo(result.message());
            assertThat(again.returnCode()).isEqualTo(result.returnCode());
            assertThat(again).isNotSameAs(result);
            assertThat(again.messageBytes())
                    .as("%s: messageBytes() must hand out a copy, so a caller mutating it cannot "
                            + "reach the value another caller holds", parityCase.caseId())
                    .isNotSameAs(again.messageBytes())
                    .isEqualTo(result.messageBytes());
        }
    }

    @Test
    @DisplayName("no Spring Batch Job, Step or Tasklet originates from DateUtilityJob - it is a @Service")
    void noSpringBatchJobStepOrTaskletOriginatesFromThisType() {
        Class<?> unit = DateUtilityJob.class;

        assertThat(unit.getAnnotation(Service.class))
                .as("CSUTLDTC is a called subprogram, so its translation is a @Service - the shape the "
                        + "plan's gate requires despite the mandated ...Job class name")
                .isNotNull();

        for (Annotation annotation : unit.getAnnotations()) {
            String name = annotation.annotationType().getName();
            assertThat(name)
                    .as("DateUtilityJob carries @%s. A batch or configuration annotation here would "
                            + "make the class a source of Spring Batch beans", name)
                    .doesNotStartWith(SPRING_BATCH_PACKAGE)
                    .isNotEqualTo(CONFIGURATION_ANNOTATION);
        }

        assertThat(unit.getInterfaces())
                .as("DateUtilityJob implements no interface at all, so it cannot be a Tasklet, a Job "
                        + "or a StepExecutionListener")
                .isEmpty();
        assertThat(unit.getSuperclass())
                .as("DateUtilityJob extends nothing, so it inherits no batch behaviour either")
                .isEqualTo(Object.class);

        for (Method method : unit.getDeclaredMethods()) {
            assertThat(method.getReturnType().getName())
                    .as("%s.%s returns a Spring Batch type, so a Job, Step or Tasklet DOES originate "
                            + "from this class", unit.getSimpleName(), method.getName())
                    .doesNotStartWith(SPRING_BATCH_PACKAGE);
            for (Annotation annotation : method.getAnnotations()) {
                assertThat(annotation.annotationType().getName())
                        .as("%s.%s is annotated @%s; a @Bean factory method is the other way a batch "
                                + "bean could originate here", unit.getSimpleName(), method.getName(),
                                annotation.annotationType().getSimpleName())
                        .isNotEqualTo(BEAN_ANNOTATION)
                        .doesNotStartWith(SPRING_BATCH_PACKAGE);
            }
        }

        assertThat(new DateUtilityJob(DateUtilityJob.DEFAULT_MESSAGE_CHARSET)
                .validateDate("2022-07-19", "YYYY-MM-DD").returnCode())
                .as("constructing the service directly and calling it must work with no Spring context")
                .isZero();
    }

    private static String movePicX(String source, int width) {
        if (source.length() >= width) {
            return source.substring(0, width);
        }
        return source + " ".repeat(width - source.length());
    }

    private static int halfword(String token, int position) {
        int firstDigit = position * 4;
        return Integer.parseInt(token.substring(firstDigit, firstDigit + 4), 16);
    }

    private static void assertMessageNumber(Charset charset, String caseId, String messageNumber) {
        Linkage linkage = shippedLinkage().get(caseId);
        assertThat(new DateUtilityJob(charset)
                .validateDate(linkage.lsDate(), linkage.lsDateFormat()).messageNumber())
                .as("%s must report message %s", caseId, messageNumber)
                .isEqualTo(messageNumber);
    }

    private static String assembleWsMessage(FixedWidthCodec codec,
                                            String severityCode,
                                            String messageNumber,
                                            String resultText,
                                            String lsDate,
                                            String lsDateFormat) {
        FixedWidthRecord wsMessage = codec.newRecord(WS_MESSAGE_LAYOUT);
        codec.writePicX(wsMessage, WS_DATE, TEN_SPACES);

        String vstringText = codec.movePicX(lsDate, DateUtilityJob.LS_DATE_LENGTH);
        codec.writePicX(wsMessage, WS_DATE, vstringText);
        codec.writePicX(wsMessage, WS_DATE_FMT,
                codec.movePicX(lsDateFormat, DateUtilityJob.LS_DATE_FORMAT_LENGTH));

        wsMessage.writeBytes(WS_DATE.offset(), groupMoveImage(codec, vstringText));

        codec.writePic9(wsMessage, WS_SEVERITY_N, Integer.parseInt(severityCode));
        codec.writePic9(wsMessage, WS_MSG_NO_N, Integer.parseInt(messageNumber));

        codec.writePicX(wsMessage, WS_RESULT, resultText);

        return wsMessage.readString(0, wsMessage.recordLength());
    }

    private static byte[] groupMoveImage(FixedWidthCodec codec, String vstringText) {
        byte[] image = new byte[WS_DATE.length()];
        image[0] = (byte) ((DateUtilityJob.LS_DATE_LENGTH >> Byte.SIZE) & BYTE_MASK);
        image[1] = (byte) (DateUtilityJob.LS_DATE_LENGTH & BYTE_MASK);
        System.arraycopy(codec.encodeImage(vstringText, "VSTRING-TEXT OF WS-DATE-TO-TEST"), 0,
                image, VSTRING_LENGTH_BYTES, GROUP_MOVE_SURVIVING_TEXT_BYTES);
        return image;
    }
}
