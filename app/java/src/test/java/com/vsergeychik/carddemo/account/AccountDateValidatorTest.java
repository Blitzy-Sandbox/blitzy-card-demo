package com.vsergeychik.carddemo.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.vsergeychik.carddemo.account.AccountDateValidator.EditDateState;
import com.vsergeychik.carddemo.account.AccountDateValidator.EditFlag;
import com.vsergeychik.carddemo.account.AccountDateValidator.InputFlag;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.util.DateUtilityJob;
import com.vsergeychik.carddemo.util.DateUtilityJob.DateValidationResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Parity tests for {@link AccountDateValidator}, the translation of {@code app/cpy/CSUTLDPY.cpy} and
 * {@code app/cpy/CSUTLDWY.cpy}.
 */
@DisplayName("AccountDateValidator - CSUTLDPY/CSUTLDWY date-edit engine")
class AccountDateValidatorTest {
    private static final String PROCEDURE_COPYBOOK = "app/cpy/CSUTLDPY.cpy";

    private static final String WORKING_COPYBOOK = "app/cpy/CSUTLDWY.cpy";

    private static final String CALLED_SUBPROGRAM = "app/cbl/CSUTLDTC.cbl";

    private static final String CONSUMING_PROGRAM = "app/cbl/COACTUPC.cbl";

    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T10:15:30Z");

    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private static final String TODAY = "20220719";

    private static final String EIGHT_LOW_VALUES = "\u0000".repeat(8);

    private static final String EIGHT_SPACES = " ".repeat(8);

    private static final String OPEN_DATE = "Open Date";

    private static final String EXPIRY_DATE = "Expiry Date";

    private static final String REISSUE_DATE = "Reissue Date";

    private static final String DATE_OF_BIRTH = "Date of Birth";

    private static final String GOOD_DATE = "20220719";

    private static final String BAD_DATE_VALUE_DATE = "20220732";

    private static final String UNSUPPORTED_RANGE_DATE = "15000718";

    private static final String TOLERATED_ELSEWHERE_MESSAGE_NUMBER = "2513";

    private static final List<String> PROCEDURE_LINES = readReference(PROCEDURE_COPYBOOK);

    private static final List<String> WORKING_LINES = readReference(WORKING_COPYBOOK);

    private static final List<String> SUBPROGRAM_LINES = readReference(CALLED_SUBPROGRAM);

    private static final List<String> CONSUMER_LINES = readReference(CONSUMING_PROGRAM);

    private static final List<String> SUITE_LINES = readReference(
            "app/java/src/test/java/com/vsergeychik/carddemo/account/AccountDateValidatorTest.java");

    private DateUtilityJob dateUtility;

    private AccountDateValidator validator;

    private static List<String> readReference(String repositoryRelativePath) {
        return List.copyOf(readLines(repositoryRoot().resolve(repositoryRelativePath)));
    }

    @BeforeEach
    void createValidator() {
        dateUtility = Mockito.mock(DateUtilityJob.class);
        Mockito.when(dateUtility.validateDate(Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(call -> realService().validateDate(call.getArgument(0),
                        call.getArgument(1)));
        validator = new AccountDateValidator(new FixedWidthCodec(StandardCharsets.US_ASCII),
                dateUtility, FIXED_CLOCK);
    }

    private static DateUtilityJob realService() {
        return new DateUtilityJob();
    }

    private static DateValidationResult outcomeFor(String lsDate) {
        return realService().validateDate(lsDate, AccountDateValidator.WS_DATE_FORMAT_VALUE);
    }

    private void stubCsutldtc(DateValidationResult outcome) {
        Mockito.when(dateUtility.validateDate(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(outcome);
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve(PROCEDURE_COPYBOOK))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Cannot locate " + PROCEDURE_COPYBOOK
                + " from the working directory " + Path.of("").toAbsolutePath()
                + "; the reference copybooks are the only oracle these tests have");
    }

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Cannot read " + path, unreadable);
        }
    }

    private static String line(List<String> source, int lineNumber) {
        return source.get(lineNumber - 1);
    }

    private static int lineNumberOf(List<String> source, String fragment) {
        for (int index = 0; index < source.size(); index++) {
            if (source.get(index).contains(fragment)) {
                return index + 1;
            }
        }
        throw new IllegalStateException("No line of the copybook contains '" + fragment + "'");
    }

    private EditDateState given(String date, String fieldName) {
        EditDateState state = validator.newState();
        state.setEditVariableName(fieldName);
        state.setEditDateCcyymmdd(date);
        return state;
    }

    private EditDateState validate(String date) {
        EditDateState state = given(date, OPEN_DATE);
        validator.editDateCcyymmddThruExit(state);
        return state;
    }

    private static String flags(EditDateState state) {
        return state.flagsImage().replace('\u0000', '_');
    }

    @Nested
    @DisplayName("Provenance - re-read from app/cpy and app/cbl at test time")
    class Provenance {
        @Test
        @DisplayName("every message literal is byte-for-byte a literal of CSUTLDPY")
        void messageLiteralsAreVerbatim() {
            assertLiteralDeclaredAt(AccountDateValidator.MSG_YEAR_MUST_BE_SUPPLIED, 37);
            assertLiteralDeclaredAt(AccountDateValidator.MSG_YEAR_MUST_BE_4_DIGITS, 54);
            assertLiteralDeclaredAt(AccountDateValidator.MSG_CENTURY_NOT_VALID, 79);
            assertLiteralDeclaredAt(AccountDateValidator.MSG_MONTH_MUST_BE_SUPPLIED, 101);
            assertLiteralDeclaredAt(AccountDateValidator.MSG_MONTH_MUST_BE_1_TO_12, 119);
            assertLiteralDeclaredAt(AccountDateValidator.MSG_MONTH_MUST_BE_1_TO_12, 136);
            assertLiteralDeclaredAt(AccountDateValidator.MSG_DAY_MUST_BE_SUPPLIED, 161);
            assertLiteralDeclaredAt(AccountDateValidator.MSG_DAY_MUST_BE_1_TO_31, 180);
            assertLiteralDeclaredAt(AccountDateValidator.MSG_DAY_MUST_BE_1_TO_31, 195);
            assertLiteralDeclaredAt(AccountDateValidator.MSG_CANNOT_HAVE_31_DAYS, 221);
            assertLiteralDeclaredAt(AccountDateValidator.MSG_CANNOT_HAVE_30_DAYS, 236);
            assertLiteralDeclaredAt(AccountDateValidator.MSG_NOT_A_LEAP_YEAR, 266);
            assertLiteralDeclaredAt(AccountDateValidator.MSG_VALIDATION_ERROR_SEV_CODE, 308);
            assertLiteralDeclaredAt(AccountDateValidator.MSG_MESSAGE_CODE, 310);
            assertLiteralDeclaredAt(AccountDateValidator.MSG_CANNOT_BE_IN_THE_FUTURE, 363);
        }

        private void assertLiteralDeclaredAt(String literal, int lineNumber) {
            assertThat(line(PROCEDURE_LINES, lineNumber).trim())
                    .as("app/cpy/CSUTLDPY.cpy:L%d must declare '%s' exactly", lineNumber, literal)
                    .isEqualTo("'" + literal + "'");
        }

        @Test
        @DisplayName("L323-L328: the EXIT is a no-op and the SET is a separate sentence after it")
        void theFallThroughStructureIsWhatTheTranslationClaims() {
            assertThat(line(PROCEDURE_LINES, 323).trim()).isEqualTo("EDIT-DATE-LE-EXIT.");
            assertThat(line(PROCEDURE_LINES, 324).trim()).isEqualTo("EXIT");
            assertThat(line(PROCEDURE_LINES, 325).trim()).isEqualTo(".");
            assertThat(line(PROCEDURE_LINES, 327).trim())
                    .isEqualTo("SET WS-EDIT-DATE-IS-VALID        TO TRUE");
            assertThat(line(PROCEDURE_LINES, 329).trim())
                    .as("the next paragraph must not begin until L329, which is what puts the L327 "
                            + "SET inside EDIT-DATE-LE-EXIT and therefore on every path into it")
                    .isEqualTo("EDIT-DATE-CCYYMMDD-EXIT.");
        }

        @Test
        @DisplayName("EDIT-MONTH tests the range before TEST-NUMVAL; EDIT-DAY does the reverse")
        void theGuardOrderIsPerParagraphAndIsNotUniform() {
            int monthRange = lineNumberOf(PROCEDURE_LINES, "IF WS-VALID-MONTH");
            int monthNumeric = lineNumberOf(PROCEDURE_LINES, "TEST-NUMVAL (WS-EDIT-DATE-MM)");
            int dayNumeric = lineNumberOf(PROCEDURE_LINES, "TEST-NUMVAL (WS-EDIT-DATE-DD)");
            int dayRange = lineNumberOf(PROCEDURE_LINES, "IF WS-VALID-DAY");

            assertThat(monthRange)
                    .as("EDIT-MONTH: the range test at L%d must precede TEST-NUMVAL at L%d, which "
                            + "is why MM = '5 ' is rejected", monthRange, monthNumeric)
                    .isLessThan(monthNumeric);
            assertThat(dayNumeric)
                    .as("EDIT-DAY: TEST-NUMVAL at L%d must precede the range test at L%d, which is "
                            + "why DD = '5 ' is accepted", dayNumeric, dayRange)
                    .isLessThan(dayRange);
        }

        @Test
        @DisplayName("EDIT-DAY opens with FLG-DAY-ISVALID where the year and month open with NOT-OK")
        void theOpeningFlagStateDiffersBetweenParagraphs() {
            assertThat(line(PROCEDURE_LINES, 27)).contains("SET FLG-YEAR-NOT-OK");
            assertThat(line(PROCEDURE_LINES, 92)).contains("SET FLG-MONTH-NOT-OK");
            assertThat(line(PROCEDURE_LINES, 152)).contains("SET FLG-DAY-ISVALID");
        }

        @Test
        @DisplayName("the WS-DATE-VALIDATION-RESULT storage spans sum to exactly 80 bytes")
        void theResultAreaIsEightyBytes() {
            int declared = 0;
            for (int lineNumber = 60; lineNumber <= 85; lineNumber++) {
                String text = line(WORKING_LINES, lineNumber);
                int marker = text.indexOf("PIC X(");
                if (marker >= 0) {
                    declared += Integer.parseInt(
                            text.substring(marker + "PIC X(".length(), text.indexOf(')', marker)));
                }
            }
            assertThat(declared)
                    .as("app/cpy/CSUTLDWY.cpy L60-L85: only the PIC X spans occupy storage - the two "
                            + "PIC 9(4) items are REDEFINES overlays - and they must total 80")
                    .isEqualTo(AccountDateValidator.WS_DATE_VALIDATION_RESULT_LENGTH);
        }

        @Test
        @DisplayName("CSUTLDTC declares X(10)/X(10)/X(80), which is what the 8-to-10 widening is for")
        void theLinkageWidthsExceedTheArgumentWidths() {
            assertThat(line(SUBPROGRAM_LINES, 84)).contains("LS-DATE         PIC X(10)");
            assertThat(line(SUBPROGRAM_LINES, 85)).contains("LS-DATE-FORMAT  PIC X(10)");
            assertThat(line(SUBPROGRAM_LINES, 86)).contains("LS-RESULT       PIC X(80)");
            assertThat(line(WORKING_LINES, 58))
                    .as("the caller's mask is only eight characters wide")
                    .contains("WS-DATE-FORMAT                        PIC X(08)");
            assertThat(AccountDateValidator.WS_DATE_FORMAT_LENGTH).isEqualTo(8);
            assertThat(DateUtilityJob.LS_DATE_LENGTH).isEqualTo(10);
            assertThat(DateUtilityJob.LS_DATE_FORMAT_LENGTH).isEqualTo(10);
        }

        @Test
        @DisplayName("ROUNDED appears nowhere, so truncation is the only faithful store rule")
        void noStatementRounds() {
            assertThat(PROCEDURE_LINES).noneMatch(text -> text.contains("ROUNDED"));
            assertThat(WORKING_LINES).noneMatch(text -> text.contains("ROUNDED"));
        }

        @Test
        @DisplayName("the 88-level values are the copybook's own")
        void theConditionNameValuesAreDeclaredAsTranslated() {
            assertThat(line(WORKING_LINES, 9)).contains("THIS-CENTURY").contains("VALUE 20");
            assertThat(line(WORKING_LINES, 10)).contains("LAST-CENTURY").contains("VALUE 19");
            assertThat(line(WORKING_LINES, 24)).contains("WS-FEBRUARY").contains("VALUE 2");
            assertThat(line(WORKING_LINES, 30)).contains("WS-DAY-31").contains("VALUE 31");
            assertThat(line(WORKING_LINES, 31)).contains("WS-DAY-30").contains("VALUE 30");
            assertThat(line(WORKING_LINES, 32)).contains("WS-DAY-29").contains("VALUE 29");
            assertThat(line(WORKING_LINES, 44)).contains("WS-EDIT-DATE-IS-VALID")
                    .contains("LOW-VALUES");
            assertThat(line(WORKING_LINES, 45)).contains("WS-EDIT-DATE-IS-INVALID")
                    .contains("'000'");
            assertThat(line(PROCEDURE_LINES, 246)).contains("MOVE 400");
            assertThat(line(PROCEDURE_LINES, 248)).contains("MOVE 4");
        }

        @Test
        @DisplayName("WS-VALID-FEB-DAY is declared and never referenced, so it is kept unreferenced")
        void theUnreferencedConditionNameIsPreserved() {
            assertThat(line(WORKING_LINES, 33)).contains("WS-VALID-FEB-DAY");
            assertThat(PROCEDURE_LINES)
                    .as("no statement of CSUTLDPY tests WS-VALID-FEB-DAY; it is modelled anyway "
                            + "because it is part of the copybook's declared contract")
                    .noneMatch(text -> text.contains("WS-VALID-FEB-DAY"));
        }

        @Test
        @DisplayName("the commented-out FUNCTION FIND-DURATION alternative stays out of the Java")
        void theCommentedOutAlternativeIsNotCode() {
            assertThat(line(PROCEDURE_LINES, 351).stripLeading())
                    .startsWith("*")
                    .contains("FUNCTION FIND-DURATION");
        }

        @Test
        @DisplayName("L293 is a fifth CALL 'CSUTLDTC' site, in a copybook, that the plan's census misses")
        void theCopybookHoldsAFifthCallSite() {
            assertThat(line(PROCEDURE_LINES, 293))
                    .as("app/cpy/CSUTLDPY.cpy:L293 - the call this class mocks")
                    .contains("CALL 'CSUTLDTC'");
            assertThat(line(PROCEDURE_LINES, 294)).contains("USING WS-EDIT-DATE-CCYYMMDD");
            assertThat(line(PROCEDURE_LINES, 295)).contains("WS-DATE-FORMAT");
            assertThat(line(PROCEDURE_LINES, 296)).contains("WS-DATE-VALIDATION-RESULT");

            long callSites = PROCEDURE_LINES.stream()
                    .filter(text -> text.contains("CALL 'CSUTLDTC'"))
                    .count();
            assertThat(callSites)
                    .as("exactly one, so the count of five is four in app/cbl plus this one")
                    .isOne();
        }

        @Test
        @DisplayName("L251-L254 is a real DIVIDE ... GIVING ... REMAINDER, which the plan's census omits")
        void theCopybookHoldsTheOnlyDivideVerb() {
            assertThat(line(PROCEDURE_LINES, 251)).contains("DIVIDE WS-EDIT-DATE-CCYY-N");
            assertThat(line(PROCEDURE_LINES, 252)).contains("BY WS-DIV-BY");
            assertThat(line(PROCEDURE_LINES, 253)).contains("GIVING WS-DIVIDEND");
            assertThat(line(PROCEDURE_LINES, 254)).contains("REMAINDER WS-REMAINDER");
            assertThat(line(PROCEDURE_LINES, 256))
                    .as("and the verdict is taken from the remainder, not the quotient")
                    .contains("IF WS-REMAINDER = ZEROES");
        }

        @Test
        @DisplayName("COACTUPC supplies exactly these four labels, and performs the range five times")
        void theConsumerSuppliesFourLabelsAndFivePerforms() {
            assertThat(line(CONSUMER_LINES, 1478)).contains("MOVE '" + OPEN_DATE + "'")
                    .contains("TO WS-EDIT-VARIABLE-NAME");
            assertThat(line(CONSUMER_LINES, 1490)).contains("MOVE '" + EXPIRY_DATE + "'");
            assertThat(line(CONSUMER_LINES, 1503)).contains("MOVE '" + REISSUE_DATE + "'");
            assertThat(line(CONSUMER_LINES, 1533))
                    .as("the plan cites L1534 for this label; it is in fact L1533")
                    .contains("MOVE '" + DATE_OF_BIRTH + "'");

            for (int performLine : new int[] {1480, 1492, 1505, 1536}) {
                assertThat(line(CONSUMER_LINES, performLine).trim())
                        .isEqualTo("PERFORM EDIT-DATE-CCYYMMDD");
                assertThat(line(CONSUMER_LINES, performLine + 1).trim())
                        .as("a range perform, which is what makes the paragraphs fall through")
                        .isEqualTo("THRU EDIT-DATE-CCYYMMDD-EXIT");
            }
            assertThat(line(CONSUMER_LINES, 1539).trim())
                    .as("the birth-date check is gated on the flag group coming back ISVALID")
                    .isEqualTo("IF WS-EDIT-DT-OF-BIRTH-ISVALID");
            assertThat(line(CONSUMER_LINES, 1540).trim())
                    .as("two spaces after PERFORM, which is why a single-space search misses it")
                    .isEqualTo("PERFORM  EDIT-DATE-OF-BIRTH");
            assertThat(line(CONSUMER_LINES, 1541).trim())
                    .isEqualTo("THRU  EDIT-DATE-OF-BIRTH-EXIT");
        }

        @Test
        @DisplayName("COACTUPC is the only consumer of either copybook, and copies them in both forms")
        void theConsumerIsTheOnlyOne() {
            assertThat(line(CONSUMER_LINES, 166).trim())
                    .as("the working-storage copybook is copied in the QUOTED form")
                    .isEqualTo("COPY 'CSUTLDWY'.");
            assertThat(line(CONSUMER_LINES, 4232).trim())
                    .as("the procedure copybook is copied in the BARE form, and with no period")
                    .isEqualTo("COPY CSUTLDPY");
            assertThat(CONSUMER_LINES).anyMatch(text -> text.contains("COPY 'CSUTLDWY'"));
            assertThat(CONSUMER_LINES).anyMatch(text -> text.contains("COPY CSUTLDPY"));
            assertThat(line(CONSUMER_LINES, 151))
                    .as("the group the three DIVIDE operands belong to")
                    .contains("WS-CALCULATION-VARS");
            assertThat(line(CONSUMER_LINES, 152)).contains("WS-DIV-BY").contains("PIC S9(4) COMP-3");
            assertThat(line(CONSUMER_LINES, 153))
                    .as("and its VALUE 4, which is why a fresh state reports divisor 4")
                    .contains("VALUE 4");
            assertThat(line(CONSUMER_LINES, 154)).contains("WS-DIVIDEND")
                    .contains("PIC S9(4) COMP-3");
            assertThat(line(CONSUMER_LINES, 157)).contains("WS-REMAINDER")
                    .contains("PIC S9(4) COMP-3");
            assertThat(line(CONSUMER_LINES, 173))
                    .as("INPUT-ERROR belongs to the consumer, outside WS-EDIT-DATE-FLGS, which is "
                            + "exactly why the L327 group set cannot clear it")
                    .contains("88  INPUT-ERROR").contains("VALUE '1'");
            assertThat(line(CONSUMER_LINES, 480)).contains("88  WS-RETURN-MSG-OFF")
                    .contains("VALUE SPACES");
        }
    }

    @Nested
    @DisplayName("The L323/L327 fall-through - preserved, not corrected")
    class FallThrough {
        @Test
        @DisplayName("a rejected date comes back with all three flags ISVALID - the defect")
        void theGroupSetAtL327DiscardsTheNotOkFlags() {
            stubCsutldtc(outcomeFor(BAD_DATE_VALUE_DATE));
            EditDateState state = given(GOOD_DATE, OPEN_DATE);

            validator.editDateCcyymmddThruExit(state);

            assertThat(flags(state))
                    .as("L327 resets the three NOT-OK flags L301-L304 had just set")
                    .isEqualTo("___");
            assertThat(state.wsEditDateIsValid()).isTrue();
            assertThat(state.inputError())
                    .as("INPUT-ERROR is not part of WS-EDIT-DATE-FLGS, so the group set cannot "
                            + "clear it - the error survives while the flags deny it")
                    .isTrue();
            assertThat(state.returnMessage().trim())
                    .as("and so does the diagnostic")
                    .isEqualTo("Open Date validation error Sev code: 0003 Message code: 2508");
        }

        @Test
        @DisplayName("EDIT-DATE-LE alone leaves the flags NOT-OK; only the range applies L327")
        void theParagraphItselfDoesNotApplyTheGroupSet() {
            stubCsutldtc(outcomeFor(BAD_DATE_VALUE_DATE));
            EditDateState state = given(GOOD_DATE, OPEN_DATE);

            validator.editDateLe(state);

            assertThat(flags(state))
                    .as("L301-L304 set all three NOT-OK; L327 belongs to the next paragraph")
                    .isEqualTo("000");
            assertThat(state.wsEditDateIsInvalid()).isTrue();
        }

        @Test
        @DisplayName("a GO TO EDIT-DATE-CCYYMMDD-EXIT branch bypasses L327 and keeps its flags")
        void theFourEarlyExitsPreserveTheirFlags() {
            assertThat(flags(validate("20220431")))
                    .as("L225 leaves the range before EDIT-DATE-LE and before L327")
                    .isEqualTo("_00");
            assertThat(flags(validate("20220230")))
                    .as("L240")
                    .isEqualTo("_00");
            assertThat(flags(validate("20220229")))
                    .as("L270")
                    .isEqualTo("000");
            assertThat(flags(validate("20221319")))
                    .as("L277, reached because the month edit left a NOT-OK flag")
                    .isEqualTo("_0_");
        }
    }

    @Nested
    @DisplayName("The flag model - EditFlag, InputFlag and the two group conditions")
    class FlagModel {
        @Test
        @DisplayName("each flag state carries the byte its 88 level declares")
        void theFlagBytesAreTheDeclaredValues() {
            assertThat(EditFlag.ISVALID.flagByte()).isEqualTo('\u0000');
            assertThat(EditFlag.NOT_OK.flagByte()).isEqualTo('0');
            assertThat(EditFlag.BLANK.flagByte()).isEqualTo('B');
            assertThat(InputFlag.PENDING.flagByte()).isEqualTo('\u0000');
            assertThat(InputFlag.OK.flagByte()).isEqualTo('0');
            assertThat(InputFlag.ERROR.flagByte()).isEqualTo('1');
        }

        @ParameterizedTest
        @EnumSource(EditFlag.class)
        @DisplayName("EditFlag.ofByte inverts flagByte for every state")
        void editFlagRoundTrips(EditFlag flag) {
            assertThat(EditFlag.ofByte(flag.flagByte())).isSameAs(flag);
        }

        @ParameterizedTest
        @EnumSource(InputFlag.class)
        @DisplayName("InputFlag.ofByte inverts flagByte for every state")
        void inputFlagRoundTrips(InputFlag flag) {
            assertThat(InputFlag.ofByte(flag.flagByte())).isSameAs(flag);
        }

        @Test
        @DisplayName("a fourth flag byte has no meaning in the copybook and is rejected")
        void anUndeclaredByteIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> EditFlag.ofByte('X'))
                    .withMessageContaining("none of the three values CSUTLDWY declares");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> InputFlag.ofByte('X'))
                    .withMessageContaining("none of the three values COACTUPC declares");
        }

        @Test
        @DisplayName("a fresh state starts LOW-VALUES, INPUT-PENDING, spaces and VALUE 4")
        void theInitialStateIsWhatCoactupcPresents() {
            EditDateState state = validator.newState();

            assertThat(flags(state)).isEqualTo("___");
            assertThat(state.wsEditDateIsValid()).isTrue();
            assertThat(state.wsEditDateIsInvalid()).isFalse();
            assertThat(state.inputPending()).isTrue();
            assertThat(state.inputError()).isFalse();
            assertThat(state.inputOk()).isFalse();
            assertThat(state.inputFlag()).isSameAs(InputFlag.PENDING);
            assertThat(state.editDateCcyymmdd()).isEqualTo(EIGHT_SPACES);
            assertThat(state.editVariableName()).isEqualTo(" ".repeat(25));
            assertThat(state.returnMessage()).isEqualTo(" ".repeat(75));
            assertThat(state.returnMsgOff()).isTrue();
            assertThat(state.dateFormat()).isEqualTo("YYYYMMDD");
            assertThat(state.divBy()).isEqualTo(AccountDateValidator.LEAP_DIVISOR_ORDINARY);
            assertThat(state.dividend()).isZero();
            assertThat(state.remainder()).isZero();
            assertThat(state.editDateBinary()).isZero();
            assertThat(state.currentDateBinary()).isZero();
            assertThat(state.currentDateYyyymmdd()).isEqualTo(EIGHT_SPACES);
        }

        @Test
        @DisplayName("the nine per-flag predicates each answer for exactly one state")
        void everyPerFlagPredicateAnswersBothWays() {
            EditDateState state = validator.newState();
            for (EditFlag flag : EditFlag.values()) {
                state.setYearFlag(flag);
                state.setMonthFlag(flag);
                state.setDayFlag(flag);

                assertThat(state.yearFlag()).isSameAs(flag);
                assertThat(state.monthFlag()).isSameAs(flag);
                assertThat(state.dayFlag()).isSameAs(flag);
                assertThat(state.flgYearIsvalid()).isEqualTo(flag == EditFlag.ISVALID);
                assertThat(state.flgYearNotOk()).isEqualTo(flag == EditFlag.NOT_OK);
                assertThat(state.flgYearBlank()).isEqualTo(flag == EditFlag.BLANK);
                assertThat(state.flgMonthIsvalid()).isEqualTo(flag == EditFlag.ISVALID);
                assertThat(state.flgMonthNotOk()).isEqualTo(flag == EditFlag.NOT_OK);
                assertThat(state.flgMonthBlank()).isEqualTo(flag == EditFlag.BLANK);
                assertThat(state.flgDayIsvalid()).isEqualTo(flag == EditFlag.ISVALID);
                assertThat(state.flgDayNotOk()).isEqualTo(flag == EditFlag.NOT_OK);
                assertThat(state.flgDayBlank()).isEqualTo(flag == EditFlag.BLANK);
            }
        }

        @Test
        @DisplayName("the group conditions are over all three bytes, so one differing byte defeats them")
        void theGroupConditionsNeedEveryByte() {
            EditDateState state = validator.newState();
            state.setWsEditDateIsInvalid();
            assertThat(state.wsEditDateIsInvalid()).isTrue();
            assertThat(state.wsEditDateIsValid()).isFalse();
            assertThat(state.flagsImage()).isEqualTo("000");

            state.setYearFlag(EditFlag.ISVALID);
            assertThat(state.wsEditDateIsInvalid()).isFalse();
            assertThat(state.wsEditDateIsValid()).isFalse();

            state.setMonthFlag(EditFlag.ISVALID);
            assertThat(state.wsEditDateIsValid()).isFalse();

            state.setDayFlag(EditFlag.ISVALID);
            assertThat(state.wsEditDateIsValid()).isTrue();

            state.setWsEditDateIsValid();
            assertThat(state.flagsImage()).isEqualTo("\u0000\u0000\u0000");
        }

        @Test
        @DisplayName("WS-EDIT-DATE-IS-INVALID needs all three bytes to be '0', in any position")
        void theInvalidGroupConditionNeedsEveryByte() {
            EditDateState state = validator.newState();
            state.setWsEditDateIsInvalid();
            assertThat(state.wsEditDateIsInvalid()).isTrue();

            state.setDayFlag(EditFlag.BLANK);
            assertThat(state.wsEditDateIsInvalid())
                    .as("'00B' is not the declared VALUE '000'")
                    .isFalse();

            state.setMonthFlag(EditFlag.BLANK);
            assertThat(state.wsEditDateIsInvalid())
                    .as("nor is '0BB'")
                    .isFalse();

            state.setYearFlag(EditFlag.BLANK);
            assertThat(state.wsEditDateIsInvalid()).isFalse();
            assertThat(state.flagsImage()).isEqualTo("BBB");
        }

        @Test
        @DisplayName("the three input-flag states are mutually exclusive")
        void theInputFlagStatesAreExclusive() {
            EditDateState state = validator.newState();

            state.setInputError();
            assertThat(state.inputError()).isTrue();
            assertThat(state.inputOk()).isFalse();
            assertThat(state.inputPending()).isFalse();

            state.setInputOk();
            assertThat(state.inputOk()).isTrue();
            assertThat(state.inputError()).isFalse();
            assertThat(state.inputPending()).isFalse();

            state.setInputPending();
            assertThat(state.inputPending()).isTrue();
            assertThat(state.inputError()).isFalse();
            assertThat(state.inputOk()).isFalse();
        }

        @Test
        @DisplayName("the flag group moves out as three bytes in year, month, day order")
        void theGroupMoveIsOrdered() {
            EditDateState state = validator.newState();
            state.setYearFlag(EditFlag.BLANK);
            state.setMonthFlag(EditFlag.NOT_OK);
            state.setDayFlag(EditFlag.ISVALID);

            assertThat(state.flagsImage()).isEqualTo("B0\u0000");
            assertThat(state.flagsImage()).hasSize(AccountDateValidator.WS_EDIT_DATE_FLGS_LENGTH);
        }
    }

    @Nested
    @DisplayName("EDIT-YEAR-CCYY - L25-L87")
    class EditYear {
        @Test
        @DisplayName("L30: LOW-VALUES is not supplied, and the flag is BLANK rather than NOT-OK")
        void lowValuesIsBlank() {
            EditDateState state = given(EIGHT_LOW_VALUES, OPEN_DATE);

            validator.editYearCcyy(state);

            assertThat(state.yearFlag()).isSameAs(EditFlag.BLANK);
            assertThat(state.inputError()).isTrue();
            assertThat(state.returnMessage().trim())
                    .isEqualTo("Open Date : Year must be supplied.");
        }

        @Test
        @DisplayName("L31: SPACES is not supplied either - both figurative constants are tested")
        void spacesIsBlank() {
            EditDateState state = given(EIGHT_SPACES, EXPIRY_DATE);

            validator.editYearCcyy(state);

            assertThat(state.yearFlag()).isSameAs(EditFlag.BLANK);
            assertThat(state.returnMessage().trim())
                    .isEqualTo("Expiry Date : Year must be supplied.");
        }

        @ParameterizedTest
        @ValueSource(strings = {"19 4", "1a22", "+202", "20.2", "202 "})
        @DisplayName("L48: IS NOT NUMERIC demands four digits - no sign, point or embedded space")
        void theClassConditionIsStrict(String ccyy) {
            EditDateState state = given(ccyy + "0719", OPEN_DATE);

            validator.editYearCcyy(state);

            assertThat(state.yearFlag()).isSameAs(EditFlag.NOT_OK);
            assertThat(state.inputError()).isTrue();
            assertThat(state.returnMessage().trim())
                    .isEqualTo("Open Date must be 4 digit number.");
        }

        @ParameterizedTest
        @CsvSource({"1899, false", "1900, true", "1999, true", "2000, true", "2099, true",
                "2100, false", "0019, false", "1800, false"})
        @DisplayName("L70: only centuries 19 and 20 are valid, as L66-L68 says in so many words")
        void onlyTwoCenturiesAreAdmitted(String ccyy, boolean accepted) {
            EditDateState state = given(ccyy + "0715", OPEN_DATE);

            validator.editYearCcyy(state);

            assertThat(state.yearFlag())
                    .isSameAs(accepted ? EditFlag.ISVALID : EditFlag.NOT_OK);
            if (!accepted) {
                assertThat(state.returnMessage().trim())
                        .isEqualTo("Open Date : Century is not valid.");
            }
        }

        @Test
        @DisplayName("THIS-CENTURY and LAST-CENTURY answer for exactly their own value")
        void bothCenturyConditionNamesAnswerBothWays() {
            EditDateState state = given(GOOD_DATE, OPEN_DATE);
            assertThat(state.thisCentury()).isTrue();
            assertThat(state.lastCentury()).isFalse();

            state.setCc("19");
            assertThat(state.thisCentury()).isFalse();
            assertThat(state.lastCentury()).isTrue();

            state.setCc("18");
            assertThat(state.thisCentury()).isFalse();
            assertThat(state.lastCentury()).isFalse();
        }

        @Test
        @DisplayName("L34: an occupied message slot suppresses the text but never the flag")
        void anOccupiedMessageSlotStillSetsTheFlag() {
            EditDateState state = given(EIGHT_SPACES, OPEN_DATE);
            state.stringIntoReturnMessage("Account number not provided");

            validator.editYearCcyy(state);

            assertThat(state.yearFlag())
                    .as("IF WS-RETURN-MSG-OFF guards only the STRING, not the SET statements")
                    .isSameAs(EditFlag.BLANK);
            assertThat(state.inputError()).isTrue();
            assertThat(state.returnMessage().trim())
                    .as("the first failing field of the screen keeps the message")
                    .isEqualTo("Account number not provided");
        }

        @Test
        @DisplayName("L86: a clean year clears only the year flag, and leaves no message")
        void aCleanYearClearsItsOwnFlagOnly() {
            EditDateState state = given(GOOD_DATE, OPEN_DATE);
            state.setMonthFlag(EditFlag.NOT_OK);

            validator.editYearCcyy(state);

            assertThat(state.yearFlag()).isSameAs(EditFlag.ISVALID);
            assertThat(state.monthFlag()).isSameAs(EditFlag.NOT_OK);
            assertThat(state.inputError()).isFalse();
            assertThat(state.returnMsgOff()).isTrue();
        }
    }

    @Nested
    @DisplayName("EDIT-MONTH - L91-L144, range test first")
    class EditMonth {
        @Test
        @DisplayName("L94: a blank month is BLANK, not NOT-OK")
        void aBlankMonthIsBlank() {
            EditDateState state = given("2022  19", OPEN_DATE);

            validator.editMonth(state);

            assertThat(state.monthFlag()).isSameAs(EditFlag.BLANK);
            assertThat(state.returnMessage().trim())
                    .isEqualTo("Open Date : Month must be supplied.");
        }

        @Test
        @DisplayName("L94: LOW-VALUES in the month is blank too")
        void lowValuesInTheMonthIsBlank() {
            EditDateState state = given("2022" + "\u0000\u0000" + "19", OPEN_DATE);

            validator.editMonth(state);

            assertThat(state.monthFlag()).isSameAs(EditFlag.BLANK);
        }

        @ParameterizedTest
        @ValueSource(strings = {"01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11",
                "12"})
        @DisplayName("L111: every month 1 through 12 is accepted")
        void everyValidMonthIsAccepted(String mm) {
            EditDateState state = given("2022" + mm + "15", OPEN_DATE);

            validator.editMonth(state);

            assertThat(state.wsValidMonth()).isTrue();
            assertThat(state.monthFlag()).isSameAs(EditFlag.ISVALID);
            assertThat(state.inputError()).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"00", "13", "14", "20", "99"})
        @DisplayName("L111: a month outside 1 through 12 is rejected by the range test")
        void anOutOfRangeMonthIsRejected(String mm) {
            EditDateState state = given("2022" + mm + "15", EXPIRY_DATE);

            validator.editMonth(state);

            assertThat(state.wsValidMonth()).isFalse();
            assertThat(state.monthFlag()).isSameAs(EditFlag.NOT_OK);
            assertThat(state.inputError()).isTrue();
            assertThat(state.returnMessage().trim())
                    .isEqualTo("Expiry Date: Month must be a number between 1 and 12.");
        }

        @Test
        @DisplayName("L126: a month that passes the range but not TEST-NUMVAL is still rejected")
        void theNumericTestCatchesWhatTheRangeTestLetThrough() {
            EditDateState state = given("20220a15", OPEN_DATE);
            assertThat(state.wsValidMonth()).isTrue();

            validator.editMonth(state);

            assertThat(state.monthFlag()).isSameAs(EditFlag.NOT_OK);
            assertThat(AccountDateValidator.testNumval("0a")).isEqualTo(2);
            assertThat(state.returnMessage().trim())
                    .isEqualTo("Open Date: Month must be a number between 1 and 12.");
        }

        @Test
        @DisplayName("' 5' is accepted and normalised to '05' - the zoned read makes it 05")
        void aLeadingSpaceMonthIsAccepted() {
            EditDateState state = given("2022 515", OPEN_DATE);
            assertThat(state.mmN()).isEqualTo(5);

            validator.editMonth(state);

            assertThat(state.monthFlag()).isSameAs(EditFlag.ISVALID);
            assertThat(state.mm())
                    .as("L127-L129 rewrites the two bytes as zero-filled digits")
                    .isEqualTo("05");
            assertThat(state.editDateCcyymmdd()).isEqualTo("20220515");
        }

        @Test
        @DisplayName("'5 ' is REJECTED, because the range test reads it as 50 before normalisation")
        void aTrailingSpaceMonthIsRejected() {
            EditDateState state = given("20225 15", OPEN_DATE);
            assertThat(state.mmN())
                    .as("the zoned read of '5 ' is 50: the low nibble of a space is zero")
                    .isEqualTo(50);
            assertThat(AccountDateValidator.testNumval("5 "))
                    .as("TEST-NUMVAL would have accepted it - the guard order is what rejects it")
                    .isZero();

            validator.editMonth(state);

            assertThat(state.monthFlag()).isSameAs(EditFlag.NOT_OK);
            assertThat(state.mm())
                    .as("the normalising COMPUTE is never reached, so the bytes are untouched")
                    .isEqualTo("5 ");
        }

        @Test
        @DisplayName("WS-31-DAY-MONTH is a discrete list, and WS-FEBRUARY is only month 2")
        void theMonthConditionNamesAnswerBothWays() {
            EditDateState state = given("20220115", OPEN_DATE);
            for (int month = 0; month <= 13; month++) {
                state.setMm(String.format("%02d", month));
                boolean thirtyOne = month == 1 || month == 3 || month == 5 || month == 7
                        || month == 8 || month == 10 || month == 12;

                assertThat(state.ws31DayMonth())
                        .as("WS-31-DAY-MONTH for month %d", month)
                        .isEqualTo(thirtyOne);
                assertThat(state.wsFebruary())
                        .as("WS-FEBRUARY for month %d", month)
                        .isEqualTo(month == AccountDateValidator.WS_FEBRUARY);
                assertThat(state.wsValidMonth())
                        .as("WS-VALID-MONTH for month %d", month)
                        .isEqualTo(month >= 1 && month <= 12);
            }
        }
    }

    @Nested
    @DisplayName("EDIT-DAY - L150-L204, numeric test first")
    class EditDay {
        @Test
        @DisplayName("L152: the paragraph opens ISVALID, unlike the year and month edits")
        void theParagraphOpensValid() {
            EditDateState state = given("20220715", OPEN_DATE);
            state.setDayFlag(EditFlag.NOT_OK);

            validator.editDay(state);

            assertThat(state.dayFlag()).isSameAs(EditFlag.ISVALID);
        }

        @Test
        @DisplayName("L154: a blank day is BLANK")
        void aBlankDayIsBlank() {
            EditDateState state = given("202207  ", REISSUE_DATE);

            validator.editDay(state);

            assertThat(state.dayFlag()).isSameAs(EditFlag.BLANK);
            assertThat(state.returnMessage().trim())
                    .isEqualTo("Reissue Date : Day must be supplied.");
        }

        @Test
        @DisplayName("L154: LOW-VALUES in the day is blank too")
        void lowValuesInTheDayIsBlank() {
            EditDateState state = given("202207" + "\u0000\u0000", OPEN_DATE);

            validator.editDay(state);

            assertThat(state.dayFlag()).isSameAs(EditFlag.BLANK);
        }

        @ParameterizedTest
        @ValueSource(strings = {"ab", "1x", "x1", "a1"})
        @DisplayName("L170: TEST-NUMVAL runs before the range test and rejects first")
        void theNumericTestRunsFirst(String dd) {
            EditDateState state = given("202207" + dd, OPEN_DATE);

            validator.editDay(state);

            assertThat(state.dayFlag()).isSameAs(EditFlag.NOT_OK);
            assertThat(state.inputError()).isTrue();
            assertThat(state.returnMessage().trim())
                    .isEqualTo("Open Date:day must be a number between 1 and 31.");
        }

        @ParameterizedTest
        @ValueSource(strings = {"00", "32", "40", "99"})
        @DisplayName("L187: a numeric day outside 1 through 31 is rejected by the range test")
        void theRangeTestRunsSecond(String dd) {
            EditDateState state = given("202207" + dd, OPEN_DATE);

            validator.editDay(state);

            assertThat(state.wsValidDay()).isFalse();
            assertThat(state.dayFlag()).isSameAs(EditFlag.NOT_OK);
            assertThat(state.returnMessage().trim())
                    .isEqualTo("Open Date:day must be a number between 1 and 31.");
        }

        @ParameterizedTest
        @ValueSource(strings = {"01", "09", "15", "28", "29", "30", "31"})
        @DisplayName("L203: every day 1 through 31 is accepted")
        void everyValidDayIsAccepted(String dd) {
            EditDateState state = given("202207" + dd, OPEN_DATE);

            validator.editDay(state);

            assertThat(state.wsValidDay()).isTrue();
            assertThat(state.dayFlag()).isSameAs(EditFlag.ISVALID);
        }

        @Test
        @DisplayName("'5 ' IS accepted here - the mirror image of the month behaviour")
        void aTrailingSpaceDayIsAccepted() {
            EditDateState state = given("2022075 ", OPEN_DATE);
            assertThat(state.ddN())
                    .as("the raw zoned read is 50, which the range test would have rejected")
                    .isEqualTo(50);

            validator.editDay(state);

            assertThat(state.dayFlag())
                    .as("but TEST-NUMVAL runs first and normalises the bytes to '05'")
                    .isSameAs(EditFlag.ISVALID);
            assertThat(state.dd()).isEqualTo("05");
            assertThat(state.ddN()).isEqualTo(5);
        }

        @Test
        @DisplayName("'-1' stores its magnitude, because PIC 9 has no sign position")
        void aNegativeDayStoresItsMagnitude() {
            EditDateState state = given("202207-1", OPEN_DATE);

            validator.editDay(state);

            assertThat(AccountDateValidator.numval("-1")).isEqualByComparingTo("-1");
            assertThat(state.dd())
                    .as("COMPUTE into an unsigned PIC 9(2) receiver stores the absolute value")
                    .isEqualTo("01");
            assertThat(state.dayFlag()).isSameAs(EditFlag.ISVALID);
        }

        @Test
        @DisplayName("'.5' truncates to zero and is then out of range - no ROUNDED anywhere")
        void aFractionalDayTruncatesDown() {
            EditDateState state = given("202207.5", OPEN_DATE);

            validator.editDay(state);

            assertThat(AccountDateValidator.numval(".5")).isEqualByComparingTo("0.5");
            assertThat(state.dd())
                    .as("RoundingMode.DOWN, never HALF_UP: 0.5 stores as 00, not 01")
                    .isEqualTo("00");
            assertThat(state.dayFlag()).isSameAs(EditFlag.NOT_OK);
        }

        @Test
        @DisplayName("the four day condition names each answer for exactly their own values")
        void theDayConditionNamesAnswerBothWays() {
            EditDateState state = given("20220701", OPEN_DATE);
            for (int day = 0; day <= 32; day++) {
                state.setDd(String.format("%02d", day));

                assertThat(state.wsValidDay())
                        .as("WS-VALID-DAY for day %d", day)
                        .isEqualTo(day >= 1 && day <= 31);
                assertThat(state.wsDay31()).as("WS-DAY-31 for day %d", day).isEqualTo(day == 31);
                assertThat(state.wsDay30()).as("WS-DAY-30 for day %d", day).isEqualTo(day == 30);
                assertThat(state.wsDay29()).as("WS-DAY-29 for day %d", day).isEqualTo(day == 29);
                assertThat(state.wsValidFebDay())
                        .as("WS-VALID-FEB-DAY for day %d - declared but never referenced", day)
                        .isEqualTo(day >= 1 && day <= 28);
            }
        }
    }

    @Nested
    @DisplayName("EDIT-DAY-MONTH-YEAR - L209-L279, the combination checks")
    class DayMonthYear {
        @ParameterizedTest
        @ValueSource(strings = {"01", "03", "05", "07", "08", "10", "12"})
        @DisplayName("L213: the seven 31-day months accept day 31")
        void theThirtyOneDayMonthsAcceptDayThirtyOne(String mm) {
            EditDateState state = validate("2022" + mm + "31");

            assertThat(flags(state)).isEqualTo("___");
            assertThat(state.inputError()).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"02", "04", "06", "09", "11"})
        @DisplayName("L213: every other month rejects day 31, marking the day AND the month")
        void theOtherMonthsRejectDayThirtyOne(String mm) {
            EditDateState state = validate("2022" + mm + "31");

            assertThat(flags(state))
                    .as("either the day or the month could be the mistake, so both are marked")
                    .isEqualTo("_00");
            assertThat(state.inputError()).isTrue();
            assertThat(state.returnMessage().trim())
                    .isEqualTo("Open Date:Cannot have 31 days in this month.");
        }

        @ParameterizedTest
        @ValueSource(strings = {"04", "06", "09", "11"})
        @DisplayName("the 30-day months accept day 30")
        void theThirtyDayMonthsAcceptDayThirty(String mm) {
            assertThat(flags(validate("2022" + mm + "30"))).isEqualTo("___");
        }

        @Test
        @DisplayName("L228: February rejects day 30 with its own message")
        void februaryRejectsDayThirty() {
            EditDateState state = validate("20220230");

            assertThat(flags(state)).isEqualTo("_00");
            assertThat(state.returnMessage().trim())
                    .isEqualTo("Open Date:Cannot have 30 days in this month.");
        }

        @ParameterizedTest
        @CsvSource({"2000, true, 400, 5, 0", "1900, false, 400, 4, 300", "2020, true, 4, 505, 0",
                "2021, false, 4, 505, 1", "1996, true, 4, 499, 0"})
        @DisplayName("L243-L272: the leap-year division, its divisor, quotient and remainder")
        void theLeapYearMatrix(String ccyy, boolean leap, int divisor, int quotient, int remainder) {
            EditDateState state = given(ccyy + "0229", OPEN_DATE);

            validator.editDateCcyymmdd(state);
            validator.editYearCcyy(state);
            validator.editMonth(state);
            validator.editDay(state);
            boolean fellThrough = validator.editDayMonthYear(state);

            assertThat(state.divBy())
                    .as("L245-L249: 400 when WS-EDIT-DATE-YY-N is zero, otherwise 4")
                    .isEqualTo(divisor);
            assertThat(state.dividend()).as("GIVING WS-DIVIDEND").isEqualTo(quotient);
            assertThat(state.remainder()).as("REMAINDER WS-REMAINDER").isEqualTo(remainder);
            if (leap) {
                assertThat(fellThrough).isTrue();
                assertThat(flags(state)).isEqualTo("___");
            } else {
                assertThat(fellThrough)
                        .as("a non-zero remainder takes GO TO EDIT-DATE-CCYYMMDD-EXIT at L270")
                        .isFalse();
                assertThat(flags(state))
                        .as("all three flags, the year included, because the year is part of it")
                        .isEqualTo("000");
                assertThat(state.returnMessage().trim())
                        .isEqualTo("Open Date:Not a leap year.Cannot have 29 days in this month.");
            }
        }

        @Test
        @DisplayName("29 February in a leap year passes the whole range")
        void leapDayIsAccepted() {
            assertThat(flags(validate("20000229"))).isEqualTo("___");
            assertThat(flags(validate("20200229"))).isEqualTo("___");
        }

        @ParameterizedTest
        @CsvSource({"2000, 28, ___", "2000, 29, ___", "1900, 28, ___", "1900, 29, 000",
                "2020, 28, ___", "2020, 29, ___", "2021, 28, ___", "2021, 29, 000"})
        @DisplayName("the full leap matrix: a century leap year, a century common year, and both kinds "
                + "of ordinary year, against 28 and 29 February")
        void theLeapMatrixAcrossBothFebruaryLengths(String ccyy, String dd, String expectedFlags) {
            assertThat(flags(validate(ccyy + "02" + dd)))
                    .as("%s-02-%s", ccyy, dd)
                    .isEqualTo(expectedFlags);
        }

        @Test
        @DisplayName("28 February never reaches the leap-year division")
        void februaryTwentyEightIsAlwaysFine() {
            EditDateState state = given("20210228", OPEN_DATE);

            validator.editDateCcyymmdd(state);
            validator.editYearCcyy(state);
            validator.editMonth(state);
            validator.editDay(state);
            boolean fellThrough = validator.editDayMonthYear(state);

            assertThat(fellThrough).isTrue();
            assertThat(state.remainder())
                    .as("the division at L251 is never performed, so the work variable is untouched")
                    .isZero();
            assertThat(state.divBy()).isEqualTo(AccountDateValidator.LEAP_DIVISOR_ORDINARY);
        }

        @Test
        @DisplayName("L274: a flag left NOT-OK or BLANK by an earlier stage stops the range")
        void theGroupGateStopsTheRange() {
            EditDateState state = given("20220715", OPEN_DATE);
            state.setMonthFlag(EditFlag.NOT_OK);

            assertThat(validator.editDayMonthYear(state)).isFalse();

            state.setMonthFlag(EditFlag.BLANK);
            assertThat(validator.editDayMonthYear(state)).isFalse();

            state.setMonthFlag(EditFlag.ISVALID);
            assertThat(validator.editDayMonthYear(state)).isTrue();
        }

        @Test
        @DisplayName("31 February is caught by the 31-day guard, not by the February guards")
        void februaryThirtyOneIsCaughtFirst() {
            EditDateState state = validate("20220231");

            assertThat(state.returnMessage().trim())
                    .as("L213 comes before L228 and L243, so its message is the one that lands")
                    .isEqualTo("Open Date:Cannot have 31 days in this month.");
        }
    }

    @Nested
    @DisplayName("EDIT-DATE-LE - L284-L321, the CSUTLDTC call")
    class LanguageEnvironment {
        @Test
        @DisplayName("L290-L291: INITIALIZE blanks the named fields and keeps the three literals")
        void initializeLeavesTheFillersInPlace() {
            EditDateState state = validator.newState();

            state.initializeDateValidationResult();

            String image = state.dateValidationResult();
            assertThat(image).hasSize(80);
            assertThat(image.substring(0, 4)).as("WS-SEVERITY").isEqualTo("    ");
            assertThat(image.substring(4, 15)).as("FILLER 'Mesg Code:'").isEqualTo("Mesg Code: ");
            assertThat(image.substring(15, 19)).as("WS-MSG-NO").isEqualTo("    ");
            assertThat(image.substring(20, 35)).as("WS-RESULT").isEqualTo(" ".repeat(15));
            assertThat(image.substring(36, 45)).as("FILLER 'TstDate:'").isEqualTo("TstDate: ");
            assertThat(image.substring(56, 66)).as("FILLER 'Mask used:'").isEqualTo("Mask used:");
            assertThat(image.substring(77, 80)).as("trailing FILLER").isEqualTo("   ");
        }

        @Test
        @DisplayName("a valid date returns severity 0 and 'Date is valid', at the declared offsets")
        void aValidDateConverts() {
            EditDateState state = given(GOOD_DATE, OPEN_DATE);

            validator.editDateLe(state);

            assertThat(state.wsSeverity()).isEqualTo("0000");
            assertThat(state.wsSeverityN()).isZero();
            assertThat(state.wsMsgNo()).isEqualTo("0000");
            assertThat(state.wsMsgNoN()).isZero();
            assertThat(state.wsResult()).isEqualTo("Date is valid  ");
            assertThat(state.wsDateFmt())
                    .as("the eight-character mask reaches the callee as ten")
                    .isEqualTo("YYYYMMDD  ");
            assertThat(state.dateFormat()).isEqualTo("YYYYMMDD");
            assertThat(state.dateValidationResult()).hasSize(80);
            assertThat(state.dateValidationResultBytes()).hasSize(80);
            assertThat(state.dayFlag())
                    .as("L318-L319: no input error, so the day flag is cleared")
                    .isSameAs(EditFlag.ISVALID);
            assertThat(state.returnMsgOff()).isTrue();
        }

        @Test
        @DisplayName("the eighty-byte area this class declares is the one CSUTLDTC fills")
        void theTwoDeclarationsOfTheAreaAgree() {
            EditDateState state = given(GOOD_DATE, OPEN_DATE);

            validator.editDateLe(state);

            String image = state.dateValidationResult();
            assertThat(image.substring(4, 15)).isEqualTo("Mesg Code: ");
            assertThat(image.substring(36, 45)).isEqualTo("TstDate: ");
            assertThat(image.substring(56, 66)).isEqualTo("Mask used:");
            assertThat(image.substring(0, 4))
                    .as("read through this class's own WS-SEVERITY offset, not the service's")
                    .isEqualTo("0000");
            assertThat(image.substring(66, 76)).isEqualTo("YYYYMMDD  ");
        }

        @Test
        @DisplayName("L293-L296: the arguments reach CSUTLDTC widened from eight bytes to ten")
        void theCallCarriesTheWidenedDateAndMask() {
            EditDateState state = given(GOOD_DATE, OPEN_DATE);

            validator.editDateLe(state);

            ArgumentCaptor<String> date = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> mask = ArgumentCaptor.forClass(String.class);
            Mockito.verify(dateUtility).validateDate(date.capture(), mask.capture());
            Mockito.verifyNoMoreInteractions(dateUtility);

            assertThat(date.getValue())
                    .as("WS-EDIT-DATE-CCYYMMDD is eight bytes and LS-DATE is PIC X(10), so the "
                            + "argument is space-padded on the right to the linkage width")
                    .isEqualTo(GOOD_DATE + "  ")
                    .hasSize(DateUtilityJob.LS_DATE_LENGTH);
            assertThat(mask.getValue())
                    .as("L291 moves the eight-character literal 'YYYYMMDD' into WS-DATE-FORMAT "
                            + "PIC X(08); LS-DATE-FORMAT is PIC X(10), so it too is widened")
                    .isEqualTo("YYYYMMDD  ")
                    .hasSize(DateUtilityJob.LS_DATE_FORMAT_LENGTH);
            assertThat(state.dateFormat())
                    .as("the copybook's own field stays eight wide - the widening is at the CALL")
                    .isEqualTo(AccountDateValidator.WS_DATE_FORMAT_VALUE)
                    .hasSize(AccountDateValidator.WS_DATE_FORMAT_LENGTH);
        }

        @Test
        @DisplayName("L290-L291 run before the call, in that order, every time")
        void theCallIsPrecededByTheInitializeAndTheMove() {
            EditDateState state = given(GOOD_DATE, OPEN_DATE);
            state.setDateFormat("XXXXXXXX");

            validator.editDateLe(state);

            InOrder order = Mockito.inOrder(dateUtility);
            order.verify(dateUtility).validateDate(Mockito.anyString(), Mockito.anyString());
            order.verifyNoMoreInteractions();
            assertThat(state.wsDateFmt())
                    .as("L291 overwrote the stale mask before the call, so the callee saw 'YYYYMMDD'")
                    .isEqualTo("YYYYMMDD  ");
        }

        @Test
        @DisplayName("a rejected date sets all three flags NOT-OK and the five-operand message")
        void aRejectedDateReportsSeverityAndMessageNumber() {
            stubCsutldtc(outcomeFor(BAD_DATE_VALUE_DATE));
            EditDateState state = given(GOOD_DATE, DATE_OF_BIRTH);

            validator.editDateLe(state);

            assertThat(flags(state)).isEqualTo("000");
            assertThat(state.inputError()).isTrue();
            assertThat(state.wsSeverityN()).isEqualTo(3);
            assertThat(state.wsMsgNo()).isEqualTo("2508");
            assertThat(state.wsResult()).isEqualTo("Datevalue error");
            assertThat(state.returnMessage())
                    .as("trim(name) + L308 + WS-SEVERITY + L310 + WS-MSG-NO, space-filled to 75")
                    .isEqualTo(("Date of Birth validation error Sev code: 0003 Message code: 2508"
                            + " ".repeat(75)).substring(0, 75));
        }

        @Test
        @DisplayName("L298: message 2513 is rejected here, even though COTRN02C and CORPT00C accept it")
        void messageTwoFiveOneThreeIsRejectedByTheCopybookCallSite() {
            DateValidationResult unsupportedRange = outcomeFor(UNSUPPORTED_RANGE_DATE);
            assertThat(unsupportedRange.messageNumber())
                    .as("the service really does report FC-UNSUPP-RANGE for a pre-Lillian date")
                    .isEqualTo(TOLERATED_ELSEWHERE_MESSAGE_NUMBER);
            assertThat(unsupportedRange.result()).isEqualTo("Unsupp. Range  ");
            assertThat(unsupportedRange.severityCode()).isEqualTo("0003");
            stubCsutldtc(unsupportedRange);
            EditDateState state = given(GOOD_DATE, EXPIRY_DATE);

            validator.editDateLe(state);

            assertThat(state.wsSeverityN())
                    .as("severity 3, so IF WS-SEVERITY-N = 0 is false whatever the message number is")
                    .isEqualTo(3);
            assertThat(flags(state))
                    .as("all three flags NOT-OK, identically to message 2508")
                    .isEqualTo("000");
            assertThat(state.inputError()).isTrue();
            assertThat(state.returnMessage().trim())
                    .isEqualTo("Expiry Date validation error Sev code: 0003 Message code: 2513");
        }

        @ParameterizedTest
        @CsvSource({"20220732, 2508, 'Datevalue error'", "15000718, 2513, 'Unsupp. Range  '",
                "20221318, 2517, 'Invalid month  '", "202207 8, 2520, 'Nonnumeric data'"})
        @DisplayName("every severity-3 feedback token takes the same rejection arm, message and flags")
        void everyErrorTokenTakesTheSameArm(String badDate, String messageNumber, String resultText) {
            DateValidationResult outcome = outcomeFor(badDate);
            assertThat(outcome.messageNumber()).isEqualTo(messageNumber);
            assertThat(outcome.result()).isEqualTo(resultText);
            stubCsutldtc(outcome);
            EditDateState state = given(GOOD_DATE, OPEN_DATE);

            validator.editDateLe(state);

            assertThat(flags(state)).isEqualTo("000");
            assertThat(state.returnMessage().trim())
                    .isEqualTo("Open Date validation error Sev code: 0003 Message code: "
                            + messageNumber);
        }

        @Test
        @DisplayName("L318: an earlier field's error suppresses the day flag being cleared")
        void theSharedInputFlagCouplesTheFields() {
            EditDateState state = given(GOOD_DATE, OPEN_DATE);
            state.setDayFlag(EditFlag.NOT_OK);
            state.setInputError();

            validator.editDateLe(state);

            assertThat(state.wsSeverityN()).isZero();
            assertThat(state.dayFlag())
                    .as("IF NOT INPUT-ERROR is false because of a previous field on the screen")
                    .isSameAs(EditFlag.NOT_OK);
        }

        @Test
        @DisplayName("L305: only the first failing field of the screen claims the message")
        void theMessageSlotIsClaimedOnce() {
            stubCsutldtc(outcomeFor(BAD_DATE_VALUE_DATE));
            EditDateState state = given(GOOD_DATE, OPEN_DATE);
            state.stringIntoReturnMessage("Account number not provided");

            validator.editDateLe(state);

            assertThat(state.returnMessage().trim())
                    .as("WS-RETURN-MSG-OFF is false, so the STRING at L306 never runs")
                    .isEqualTo("Account number not provided");
            assertThat(flags(state))
                    .as("the SET statements at L301-L304 are outside the guard and still run")
                    .isEqualTo("000");
        }

        @Test
        @DisplayName("a result of any length other than eighty is a divergence, and is rejected")
        void aWrongLengthResultIsRejected() {
            EditDateState state = validator.newState();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> state.acceptDateValidationResult(new byte[79]))
                    .withMessageContaining("must agree byte for byte");
            assertThatNullPointerException()
                    .isThrownBy(() -> state.acceptDateValidationResult(null));
        }
    }

    @Nested
    @DisplayName("The range perform - fall-through granularity and the skipped CSUTLDTC call")
    class Chain {
        @Test
        @DisplayName("a year error does NOT suppress the month and day edits - they both still run")
        void aYearErrorLeavesTheMonthAndDayEditsToRun() {
            EditDateState state = validate("    1345");

            assertThat(state.yearFlag())
                    .as("L33: blank, from the first guard")
                    .isSameAs(EditFlag.BLANK);
            assertThat(state.monthFlag())
                    .as("EDIT-MONTH ran anyway and rejected 13 at L111")
                    .isSameAs(EditFlag.NOT_OK);
            assertThat(state.dayFlag())
                    .as("EDIT-DAY ran anyway and rejected 45 at L187")
                    .isSameAs(EditFlag.NOT_OK);
            assertThat(flags(state)).isEqualTo("B00");
            assertThat(state.returnMessage().trim())
                    .as("first error wins, and the year is edited first")
                    .isEqualTo("Open Date : Year must be supplied.");
        }

        @Test
        @DisplayName("a bad year with a good month and day still leaves the month and day ISVALID")
        void aYearErrorDoesNotTaintACleanMonthAndDay() {
            EditDateState state = validate("21000715");

            assertThat(state.yearFlag()).isSameAs(EditFlag.NOT_OK);
            assertThat(state.monthFlag())
                    .as("century 21 is rejected, but July is still July")
                    .isSameAs(EditFlag.ISVALID);
            assertThat(state.dayFlag()).isSameAs(EditFlag.ISVALID);
            assertThat(flags(state)).isEqualTo("0__");
            assertThat(state.returnMessage().trim())
                    .isEqualTo("Open Date : Century is not valid.");
        }

        @Test
        @DisplayName("a month error does not suppress the day edit either")
        void aMonthErrorLeavesTheDayEditToRun() {
            EditDateState state = validate("2022  45");

            assertThat(state.monthFlag()).isSameAs(EditFlag.BLANK);
            assertThat(state.dayFlag())
                    .as("EDIT-DAY still ran and rejected 45")
                    .isSameAs(EditFlag.NOT_OK);
            assertThat(state.returnMessage().trim())
                    .isEqualTo("Open Date : Month must be supplied.");
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "'    0715'|a blank year|B__",
            "'19a40715'|a non-numeric year|0__",
            "'21000715'|an invalid century|0__",
            "'2022  15'|a blank month|_B_",
            "'20221315'|an out-of-range month|_0_",
            "'2022  '|a short date, blank month and day|_BB",
            "'202207  '|a blank day|__B",
            "'20220745'|an out-of-range day|__0",
            "'202207ab'|a non-numeric day|__0",
            "'20220431'|31 days in a 30-day month|_00",
            "'20220230'|30 days in February|_00",
            "'20220229'|29 February in a common year|000"})
        @DisplayName("L274: any earlier failure skips EDIT-DATE-LE, so CSUTLDTC is never called")
        void anEarlierFailureMeansTheServiceIsNeverCalled(String date, String why,
                String expectedFlags) {
            EditDateState state = validate(date);

            Mockito.verifyNoInteractions(dateUtility);
            assertThat(flags(state))
                    .as("%s (%s)", why, date)
                    .isEqualTo(expectedFlags);
            assertThat(state.inputError()).as("%s", why).isTrue();
        }

        @Test
        @DisplayName("a date that clears every earlier edit does reach CSUTLDTC, exactly once")
        void aCleanDateReachesTheServiceOnce() {
            EditDateState state = validate(GOOD_DATE);

            Mockito.verify(dateUtility, Mockito.times(1))
                    .validateDate(GOOD_DATE + "  ", "YYYYMMDD  ");
            Mockito.verifyNoMoreInteractions(dateUtility);
            assertThat(flags(state)).isEqualTo("___");
            assertThat(state.inputError()).isFalse();
        }

        @Test
        @DisplayName("each of the four labels prefixes the message its own call site produced")
        void everyCallSiteLabelPrefixesItsOwnMessage() {
            for (String label : List.of(OPEN_DATE, EXPIRY_DATE, REISSUE_DATE, DATE_OF_BIRTH)) {
                EditDateState state = given("20220229", label);

                validator.editDateCcyymmddThruExit(state);

                assertThat(state.returnMessage().trim())
                        .as("STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME) ... - the label is trimmed "
                                + "of the padding that fills PIC X(25)")
                        .isEqualTo(label + ":Not a leap year.Cannot have 29 days in this month.");
                assertThat(state.editVariableName())
                        .hasSize(AccountDateValidator.WS_EDIT_VARIABLE_NAME_LENGTH)
                        .startsWith(label);
            }
        }

        @Test
        @DisplayName("first error wins across paragraphs: a date failing twice carries one message")
        void theFirstErrorClaimsTheMessageSlot() {
            EditDateState state = validate("    1315");

            assertThat(flags(state)).isEqualTo("B0_");
            assertThat(state.returnMessage().trim())
                    .isEqualTo("Open Date : Year must be supplied.");
            assertThat(state.returnMessage())
                    .as("the month's literal never reaches the slot")
                    .doesNotContain("Month must be");
        }

        @Test
        @DisplayName("the range is reusable: L19 wipes the previous verdict before anything reads it")
        void theRangeIsReusableAcrossDates() {
            EditDateState state = given("20220229", OPEN_DATE);
            validator.editDateCcyymmddThruExit(state);
            assertThat(flags(state)).isEqualTo("000");

            state.setEditDateCcyymmdd(GOOD_DATE);
            state.setReturnMsgOff();
            validator.editDateCcyymmddThruExit(state);

            assertThat(flags(state)).isEqualTo("___");
            assertThat(state.returnMsgOff())
                    .as("the message slot was released by the caller, not by this engine")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Arithmetic - the DIVIDE the census missed, and the four COMPUTEs")
    class Arithmetic {
        @ParameterizedTest
        @CsvSource({"2000, 400, 5, 0, true", "1900, 400, 4, 300, false", "2024, 4, 506, 0, true",
                "2023, 4, 505, 3, false", "1996, 4, 499, 0, true", "1999, 4, 499, 3, false",
                "2020, 4, 505, 0, true", "1904, 4, 476, 0, true"})
        @DisplayName("L251: the divisor is 400 when YY is 00 and 4 otherwise, and both results are exact")
        void theDivideProducesBothAQuotientAndARemainder(String ccyy, int divisor, int quotient,
                int remainder, boolean leap) {
            EditDateState state = given(ccyy + "0229", OPEN_DATE);

            validator.editDateCcyymmdd(state);
            validator.editYearCcyy(state);
            validator.editMonth(state);
            validator.editDay(state);
            boolean fellThrough = validator.editDayMonthYear(state);

            assertThat(state.divBy())
                    .as("L245-L249: MOVE 400 when WS-EDIT-DATE-YY-N = 0, else MOVE 4")
                    .isEqualTo(divisor);
            assertThat(state.dividend())
                    .as("GIVING WS-DIVIDEND - integer division, %s / %d", ccyy, divisor)
                    .isEqualTo(quotient);
            assertThat(state.remainder())
                    .as("REMAINDER WS-REMAINDER - %s mod %d", ccyy, divisor)
                    .isEqualTo(remainder);
            assertThat(state.dividend() * divisor + state.remainder())
                    .as("the two results must reconstruct the dividend exactly")
                    .isEqualTo(Integer.parseInt(ccyy));
            assertThat(fellThrough)
                    .as("L256 takes its verdict from the remainder alone")
                    .isEqualTo(leap);
        }

        @Test
        @DisplayName("L251: the division is integer division of COMP-3 operands, so nothing rounds")
        void theDivideIsExactAndNeverRounds() {
            EditDateState state = given("19000229", OPEN_DATE);

            validator.editDateCcyymmdd(state);
            validator.editYearCcyy(state);
            validator.editMonth(state);
            validator.editDay(state);
            validator.editDayMonthYear(state);

            assertThat(state.dividend())
                    .as("truncated towards zero, never rounded to 5")
                    .isEqualTo(4);
            assertThat(state.remainder()).isEqualTo(300);
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {"' 5'|05", "' 1'|01", "'12'|12", "'09'|09"})
        @DisplayName("L127: COMPUTE MM-N = NUMVAL(MM) rewrites the span as zero-filled digits")
        void theMonthComputeNormalisesTheSpan(String mm, String stored) {
            EditDateState state = given("2022" + mm + "15", OPEN_DATE);

            validator.editMonth(state);

            assertThat(state.mm()).isEqualTo(stored);
            assertThat(state.mmN()).isEqualTo(Integer.parseInt(stored));
            assertThat(state.monthFlag()).isSameAs(EditFlag.ISVALID);
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {"'5 '|05|true", "' 5'|05|true", "'-1'|01|true",
                "'+7'|07|true", "'.5'|00|false", "'1.9'|01|true", "'-0'|00|false"})
        @DisplayName("L171: COMPUTE DD-N = NUMVAL(DD) truncates DOWN and stores the magnitude")
        void theDayComputeTruncatesAndDropsTheSign(String dd, String stored, boolean accepted) {
            EditDateState state = given("202207" + (dd.length() == 2 ? dd : dd.substring(0, 2)),
                    OPEN_DATE);
            state.setDd(dd.length() >= 2 ? dd.substring(0, 2) : dd + " ");

            validator.editDay(state);

            assertThat(state.dd())
                    .as("no ROUNDED phrase exists anywhere in the twenty-eight programs, so the "
                            + "excess fractional digits are truncated - RoundingMode.DOWN")
                    .isEqualTo(stored);
            assertThat(state.dayFlag())
                    .isSameAs(accepted ? EditFlag.ISVALID : EditFlag.NOT_OK);
        }

        @Test
        @DisplayName("L345-L348: both INTEGER-OF-DATE computes land, and their difference is the verdict")
        void bothIntegerOfDateComputesLand() {
            EditDateState state = given("20220718", DATE_OF_BIRTH);

            validator.editDateOfBirth(state, FIXED_CLOCK);

            assertThat(state.editDateBinary())
                    .as("L345-L346, over WS-EDIT-DATE-CCYYMMDD-N")
                    .isEqualTo(AccountDateValidator.integerOfDate(20220718))
                    .isEqualTo(153966);
            assertThat(state.currentDateBinary())
                    .as("L347-L348, over WS-CURRENT-DATE-YYYYMMDD-N")
                    .isEqualTo(AccountDateValidator.integerOfDate(20220719))
                    .isEqualTo(153967);
            assertThat(state.currentDateBinary() - state.editDateBinary())
                    .as("one day apart, which is what makes the strict > at L350 true")
                    .isOne();
            assertThat(state.inputError()).isFalse();
        }

        @Test
        @DisplayName("no arithmetic site anywhere in this engine uses a binary floating-point type")
        void noArithmeticUsesFloatingPoint() {
            assertThat(AccountDateValidator.numval("0.1").add(AccountDateValidator.numval("0.2")))
                    .isEqualByComparingTo("0.3");
            assertThat(AccountDateValidator.numval("1.005"))
                    .as("three fractional digits, all of them kept")
                    .isEqualByComparingTo(new BigDecimal("1.005"))
                    .returns(3, BigDecimal::scale);
        }
    }

    @Nested
    @DisplayName("REDEFINES - all nine overlays of CSUTLDWY, round-tripped both ways")
    class Redefines {
        @Test
        @DisplayName("CSUTLDWY declares exactly nine REDEFINES, and every one is modelled")
        void thereAreExactlyNineOverlays() {
            long declared = WORKING_LINES.stream()
                    .filter(text -> text.contains("REDEFINES"))
                    .count();

            assertThat(declared)
                    .as("CC-N L7, YY-N L12, CCYY-N L14, MM-N L17, DD-N L26, CCYYMMDD-N L35, "
                            + "CURRENT-DATE-YYYYMMDD-N L40, WS-SEVERITY-N L62, WS-MSG-NO-N L67")
                    .isEqualTo(9);
            assertThat(line(WORKING_LINES, 7)).contains("WS-EDIT-DATE-CC-N").contains("REDEFINES");
            assertThat(line(WORKING_LINES, 12)).contains("WS-EDIT-DATE-YY-N").contains("REDEFINES");
            assertThat(line(WORKING_LINES, 14)).contains("WS-EDIT-DATE-CCYY-N")
                    .contains("REDEFINES");
            assertThat(line(WORKING_LINES, 17)).contains("WS-EDIT-DATE-MM-N").contains("REDEFINES");
            assertThat(line(WORKING_LINES, 26)).contains("WS-EDIT-DATE-DD-N").contains("REDEFINES");
            assertThat(line(WORKING_LINES, 35)).contains("WS-EDIT-DATE-CCYYMMDD-N")
                    .contains("REDEFINES");
            assertThat(line(WORKING_LINES, 40)).contains("WS-CURRENT-DATE-YYYYMMDD-N")
                    .contains("REDEFINES");
            assertThat(line(WORKING_LINES, 62)).contains("WS-SEVERITY-N").contains("REDEFINES");
            assertThat(line(WORKING_LINES, 67)).contains("WS-MSG-NO-N").contains("REDEFINES");
        }

        @Test
        @DisplayName("overlays 1-6: the eight-byte area writes through either view and reads through both")
        void theEightByteAreaIsOneStorageAreaSeenTwoWays() {
            EditDateState state = validator.newState();

            state.setEditDateCcyymmdd("19850312");
            assertThat(state.ccN()).as("overlay 1, WS-EDIT-DATE-CC-N").isEqualTo(19);
            assertThat(state.yyN()).as("overlay 2, WS-EDIT-DATE-YY-N").isEqualTo(85);
            assertThat(state.ccyyN()).as("overlay 3, WS-EDIT-DATE-CCYY-N").isEqualTo(1985);
            assertThat(state.mmN()).as("overlay 4, WS-EDIT-DATE-MM-N").isEqualTo(3);
            assertThat(state.ddN()).as("overlay 5, WS-EDIT-DATE-DD-N").isEqualTo(12);
            assertThat(state.ccyymmddN()).as("overlay 6, WS-EDIT-DATE-CCYYMMDD-N")
                    .isEqualTo(19850312);

            assertThat(state.ccN() * 100 + state.yyN()).isEqualTo(state.ccyyN());
            assertThat(state.ccyyN() * 10000L + state.mmN() * 100L + state.ddN())
                    .isEqualTo(state.ccyymmddN());

            state.setMmN(7);
            state.setDdN(4);
            assertThat(state.mm()).isEqualTo("07");
            assertThat(state.dd()).isEqualTo("04");
            assertThat(state.editDateCcyymmdd())
                    .as("a write through an overlay is a write to the shared bytes")
                    .isEqualTo("19850704");
            assertThat(state.ccyymmddN()).isEqualTo(19850704);

            state.setCc("20");
            state.setYy("22");
            assertThat(state.ccyy()).isEqualTo("2022");
            assertThat(state.ccyyN()).isEqualTo(2022);
            assertThat(state.editDateCcyymmdd()).isEqualTo("20220704");

            state.setCcyy("1999");
            assertThat(state.cc()).isEqualTo("19");
            assertThat(state.yy()).isEqualTo("99");
            assertThat(state.ccN()).isEqualTo(19);
            assertThat(state.yyN()).isEqualTo(99);
        }

        @Test
        @DisplayName("a non-numeric span reads as characters and as a zoned number, and never throws")
        void anOverlayToleratesNonNumericBytes() {
            EditDateState state = validator.newState();

            state.setEditDateCcyymmdd("19a4ab-1");

            assertThat(state.ccyy()).as("the characters survive intact").isEqualTo("19a4");
            assertThat(AccountDateValidator.isNumericClass(state.ccyy()))
                    .as("L48 IS NOT NUMERIC is therefore true, and the year is rejected")
                    .isFalse();
            assertThat(state.mm())
                    .as("and so do the month's")
                    .isEqualTo("ab");
            assertThat(state.mmN())
                    .as("read as zoned DISPLAY, 'a' is 0x61 and 'b' is 0x62, so the low nibbles "
                            + "give 12 - undefined in COBOL, defined and non-throwing here")
                    .isEqualTo(12);
            assertThat(state.wsValidMonth())
                    .as("which is why the range test at L111 passes a span TEST-NUMVAL rejects")
                    .isTrue();
            assertThat(state.dd()).isEqualTo("-1");
            assertThat(state.ddN())
                    .as("'-' is 0x2D, whose low nibble is 13 - an invalid zoned digit code that IBM "
                            + "leaves undefined. The nibble's value is contributed rather than "
                            + "thrown on, so the read is 13 then 1, that is 131. What matters is "
                            + "that it is deterministic and cannot abandon a screen edit; the span "
                            + "is rejected a statement later by TEST-NUMVAL either way.")
                    .isEqualTo(131);
            assertThat(state.wsValidDay())
                    .as("131 is outside 1 through 31, so even the range test would reject it")
                    .isFalse();
        }

        @Test
        @DisplayName("overlay 7: the current-date span is written as text and read as a number")
        void theCurrentDateOverlayRoundTrips() {
            EditDateState state = validator.newState();

            state.setCurrentDateYyyymmdd(validator.currentDateIntrinsic(FIXED_CLOCK));

            assertThat(state.currentDateYyyymmdd()).isEqualTo(TODAY);
            assertThat(state.currentDateYyyymmddN()).isEqualTo(20220719);

            state.setCurrentDateYyyymmdd("16010101");
            assertThat(state.currentDateYyyymmddN()).isEqualTo(16010101);
            assertThat(state.currentDateYyyymmdd()).isEqualTo("16010101");
        }

        @Test
        @DisplayName("overlays 8-9: severity and message number are read as text and as numbers")
        void theSeverityAndMessageNumberOverlaysRoundTrip() {
            stubCsutldtc(outcomeFor(UNSUPPORTED_RANGE_DATE));
            EditDateState state = given(GOOD_DATE, OPEN_DATE);

            validator.editDateLe(state);

            assertThat(state.wsSeverity()).as("overlay 8, character view").isEqualTo("0003");
            assertThat(state.wsSeverityN()).as("overlay 8, numeric view - read by L298").isEqualTo(3);
            assertThat(state.wsMsgNo()).as("overlay 9, character view - read by L311")
                    .isEqualTo("2513");
            assertThat(state.wsMsgNoN()).as("overlay 9, numeric view").isEqualTo(2513);

            assertThat(state.returnMessage().trim())
                    .endsWith("Sev code: " + state.wsSeverity()
                            + " Message code: " + state.wsMsgNo());
            assertThat(Integer.parseInt(state.wsSeverity())).isEqualTo(state.wsSeverityN());
            assertThat(Integer.parseInt(state.wsMsgNo())).isEqualTo(state.wsMsgNoN());
        }

        @Test
        @DisplayName("the eighty-byte area's overlays sit at the offsets the copybook's spans imply")
        void theOverlaysSitAtTheDeclaredOffsets() {
            stubCsutldtc(outcomeFor(BAD_DATE_VALUE_DATE));
            EditDateState state = given(GOOD_DATE, OPEN_DATE);

            validator.editDateLe(state);

            String image = state.dateValidationResult();
            assertThat(image.substring(0, 4))
                    .as("WS-SEVERITY and WS-SEVERITY-N both start at offset 0")
                    .isEqualTo(state.wsSeverity());
            assertThat(image.substring(15, 19))
                    .as("WS-MSG-NO and WS-MSG-NO-N both start at offset 15, after the 11-byte FILLER")
                    .isEqualTo(state.wsMsgNo());
        }
    }

    @Nested
    @DisplayName("EDIT-DATE-OF-BIRTH - L341-L368, the strict comparison")
    class DateOfBirth {
        @Test
        @DisplayName("yesterday is accepted")
        void yesterdayIsAccepted() {
            EditDateState state = birthDate("20220718");

            assertThat(flags(state)).isEqualTo("___");
            assertThat(state.inputError()).isFalse();
            assertThat(state.editDateBinary()).isEqualTo(153966);
            assertThat(state.currentDateBinary()).isEqualTo(153967);
        }

        @Test
        @DisplayName("L350: TODAY is REJECTED, because the comparison is a strict greater-than")
        void todayIsRejected() {
            EditDateState state = birthDate(TODAY);

            assertThat(state.editDateBinary()).isEqualTo(state.currentDateBinary());
            assertThat(flags(state))
                    .as("IF WS-CURRENT-DATE-BINARY > WS-EDIT-DATE-BINARY is false when they are "
                            + "equal, so today counts as the future")
                    .isEqualTo("000");
            assertThat(state.inputError()).isTrue();
            assertThat(state.returnMessage().trim())
                    .isEqualTo("Date of Birth:cannot be in the future");
            assertThat(state.returnMessage())
                    .as("the literal's single trailing space is significant")
                    .startsWith("Date of Birth:cannot be in the future ");
        }

        @Test
        @DisplayName("tomorrow is rejected")
        void tomorrowIsRejected() {
            EditDateState state = birthDate("20220720");

            assertThat(flags(state)).isEqualTo("000");
            assertThat(state.editDateBinary()).isEqualTo(153968);
        }

        @Test
        @DisplayName("L343: the twenty-one characters of CURRENT-DATE truncate to eight on the right")
        void theIntrinsicIsTruncatedOnTheRight() {
            String intrinsic = validator.currentDateIntrinsic(FIXED_CLOCK);

            assertThat(intrinsic)
                    .hasSize(AccountDateValidator.CURRENT_DATE_INTRINSIC_LENGTH)
                    .isEqualTo("2022071910153000+0000");

            EditDateState state = validator.newState();
            state.setCurrentDateYyyymmdd(intrinsic);

            assertThat(state.currentDateYyyymmdd())
                    .as("PIC X(8) keeps the leading eight and discards the other thirteen")
                    .isEqualTo(TODAY);
            assertThat(state.currentDateYyyymmddN()).isEqualTo(20220719);
        }

        @Test
        @DisplayName("a clock behind Greenwich renders a minus sign in the offset")
        void anOffsetBehindGreenwichIsSigned() {
            Clock behind = Clock.fixed(FIXED_INSTANT, ZoneOffset.ofHoursMinutes(-5, -30));

            assertThat(validator.currentDateIntrinsic(behind))
                    .hasSize(21)
                    .endsWith("-0530");
            assertThat(validator.currentDateIntrinsic(
                            Clock.fixed(FIXED_INSTANT, ZoneOffset.ofHoursMinutes(5, 45))))
                    .endsWith("+0545");
        }

        @Test
        @DisplayName("the no-clock overload reads the validator's own clock")
        void theOverloadUsesTheInjectedClock() {
            EditDateState state = given(TODAY, DATE_OF_BIRTH);
            validator.editDateCcyymmddThruExit(state);

            validator.editDateOfBirth(state);

            assertThat(validator.clock()).isSameAs(FIXED_CLOCK);
            assertThat(state.currentDateBinary()).isEqualTo(153967);
            assertThat(state.inputError()).isTrue();
        }

        @Test
        @DisplayName("a date the earlier edits could not have passed still yields a defined verdict")
        void anUnconvertibleDateDoesNotThrow() {
            EditDateState state = validator.newState();
            state.setEditVariableName(DATE_OF_BIRTH);
            state.setEditDateCcyymmdd("20220231");

            validator.editDateOfBirth(state);

            assertThat(state.editDateBinary())
                    .as("31 February is not a standard date; COBOL leaves the result undefined and "
                            + "this translation defines it as zero")
                    .isEqualTo(AccountDateValidator.INTEGER_OF_DATE_UNDEFINED);
            assertThat(state.inputError())
                    .as("a zero integer date is not in the future, so the verdict already reached "
                            + "by the earlier edits is left standing")
                    .isFalse();
        }

        private EditDateState birthDate(String date) {
            EditDateState state = given(date, DATE_OF_BIRTH);
            validator.editDateCcyymmddThruExit(state);
            if (state.wsEditDateIsValid()) {
                validator.editDateOfBirth(state, FIXED_CLOCK);
            }
            return state;
        }
    }

    @Nested
    @DisplayName("The COBOL intrinsics - TRIM, TEST-NUMVAL, NUMVAL, INTEGER-OF-DATE, class tests")
    class Intrinsics {
        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {"'Open Date  '|Open Date", "'  Open Date'|Open Date",
                "'  Open Date  '|Open Date", "'Open Date'|Open Date", "'   '|", "''|",
                "'a'|a", "'Open  Date'|Open  Date"})
        @DisplayName("FUNCTION TRIM removes spaces from both ends and nothing from the middle")
        void trimRemovesSpacesFromBothEnds(String argument, String expected) {
            assertThat(AccountDateValidator.trim(argument))
                    .isEqualTo(expected == null ? "" : expected);
        }

        @Test
        @DisplayName("FUNCTION TRIM removes only the space character")
        void trimIsSpaceOnly() {
            assertThat(AccountDateValidator.trim("\u0000x\u0000"))
                    .as("LOW-VALUE is not a space and is not removed")
                    .isEqualTo("\u0000x\u0000");
            assertThat(AccountDateValidator.trim("\tx\t")).isEqualTo("\tx\t");
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {"'05'|0", "' 5'|0", "'5 '|0", "'  5  '|0", "'+5'|0",
                "'-1'|0", "'5+'|0", "'5-'|0", "'5CR'|0", "'5DB'|0", "'1.2'|0", "'5.'|0", "'.5'|0",
                "'  '|3", "'1A'|2", "'A1'|3", "'.'|2", "'+'|2", "'+ '|3", "'+5-'|3", "'1.2.3'|4",
                "'5 x'|3", "'5cr'|2"})
        @DisplayName("FUNCTION TEST-NUMVAL reports zero, a character position, or the length plus one")
        void testNumvalReportsThePositionInError(String argument, int expected) {
            assertThat(AccountDateValidator.testNumval(argument)).isEqualTo(expected);
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {"'05'|5", "' 5'|5", "'5 '|5", "'+5'|5", "'-1'|-1",
                "'5+'|5", "'5-'|-5", "'5CR'|-5", "'5DB'|-5", "'1.2'|1.2", "'5.'|5", "'.5'|0.5",
                "'  '|0", "'1A'|0", "'99'|99"})
        @DisplayName("FUNCTION NUMVAL converts exactly, and yields zero for an invalid argument")
        void numvalConvertsExactly(String argument, String expected) {
            assertThat(AccountDateValidator.numval(argument))
                    .isEqualByComparingTo(new BigDecimal(expected));
        }

        @Test
        @DisplayName("NUMVAL never uses a binary floating-point type, so a fraction is exact")
        void numvalIsExact() {
            assertThat(AccountDateValidator.numval(".5")).isEqualByComparingTo("0.5");
            assertThat(AccountDateValidator.numval("1.2").scale()).isEqualTo(1);
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {"'0000'|true", "'2022'|true", "'0'|true", "' 5'|false",
                "'+5'|false", "'20.2'|false", "'19 4'|false", "''|false", "'\u0000\u0000'|false"})
        @DisplayName("the class condition IS NUMERIC holds only for a non-empty run of digits")
        void theClassConditionIsDigitsOnly(String argument, boolean numeric) {
            assertThat(AccountDateValidator.isNumericClass(argument)).isEqualTo(numeric);
        }

        @Test
        @DisplayName("SPACES and LOW-VALUES are distinct figurative constants")
        void theTwoFigurativeConstantsAreDistinct() {
            assertThat(AccountDateValidator.isAllSpaces("    ")).isTrue();
            assertThat(AccountDateValidator.isAllSpaces("   x")).isFalse();
            assertThat(AccountDateValidator.isAllSpaces("\u0000\u0000")).isFalse();
            assertThat(AccountDateValidator.isAllSpaces("")).isFalse();

            assertThat(AccountDateValidator.isAllLowValues("\u0000\u0000")).isTrue();
            assertThat(AccountDateValidator.isAllLowValues("\u0000x")).isFalse();
            assertThat(AccountDateValidator.isAllLowValues("  ")).isFalse();
            assertThat(AccountDateValidator.isAllLowValues("")).isFalse();
        }

        @ParameterizedTest
        @CsvSource({"16010101, 1", "16010102, 2", "16011231, 365", "16020101, 366",
                "19000101, 109208", "20000101, 145732", "20220719, 153967", "20991231, 182256",
                "99991231, 3067671"})
        @DisplayName("FUNCTION INTEGER-OF-DATE counts days from 1601-01-01 being day 1")
        void integerOfDateUsesTheCobolEpoch(int standardDate, int expected) {
            assertThat(AccountDateValidator.integerOfDate(standardDate)).isEqualTo(expected);
        }

        @Test
        @DisplayName("the epoch offset is the difference between the two day counts, not a guess")
        void theEpochOffsetIsDerived() {
            assertThat(AccountDateValidator.INTEGER_OF_DATE_EPOCH_OFFSET)
                    .isEqualTo(1 - (int) LocalDate.of(1601, 1, 1).toEpochDay());
            assertThat(AccountDateValidator.integerOfDate(20220719)
                    - AccountDateValidator.integerOfDate(20220718))
                    .as("consecutive days differ by one")
                    .isOne();
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 16001231, 16010100, 99991232, 100000000, 20220231, 20221301,
                20220000, 19000229})
        @DisplayName("an argument that is not a standard date yields the defined undefined value")
        void integerOfDateDefinesTheUndefinedCase(int standardDate) {
            assertThat(AccountDateValidator.integerOfDate(standardDate))
                    .isEqualTo(AccountDateValidator.INTEGER_OF_DATE_UNDEFINED);
        }

        @Test
        @DisplayName("the zoned numeric view agrees with the codec on digits and never throws")
        void theZonedViewAgreesWithTheCodecOnDigits() {
            FixedWidthCodec codec = new FixedWidthCodec(StandardCharsets.US_ASCII);
            EditDateState state = validator.newState();

            for (int value = 0; value <= 99; value++) {
                String image = String.format("%02d", value);
                state.setMm(image);
                assertThat(state.mmN())
                        .as("the zoned read of '%s' must equal FixedWidthCodec.decodePic9", image)
                        .isEqualTo(codec.decodePic9AsInt(image));
            }

            state.setMm("  ");
            assertThat(state.mmN()).as("a space contributes its low nibble, zero").isZero();
            state.setMm("\u0000\u0000");
            assertThat(state.mmN()).as("LOW-VALUE contributes zero as well").isZero();
            state.setMm("ab");
            assertThat(state.mmN())
                    .as("'a' is 0x61 and 'b' is 0x62, so the low nibbles read as 12 - undefined in "
                            + "COBOL, defined and non-throwing here")
                    .isEqualTo(12);
        }

        @Test
        @DisplayName("every numeric overlay of the eight-byte area reads its own span")
        void everyOverlayReadsItsOwnSpan() {
            EditDateState state = validator.newState();
            state.setEditDateCcyymmdd("19850312");

            assertThat(state.cc()).isEqualTo("19");
            assertThat(state.yy()).isEqualTo("85");
            assertThat(state.ccyy()).isEqualTo("1985");
            assertThat(state.mm()).isEqualTo("03");
            assertThat(state.dd()).isEqualTo("12");
            assertThat(state.ccN()).isEqualTo(19);
            assertThat(state.yyN()).isEqualTo(85);
            assertThat(state.ccyyN()).isEqualTo(1985);
            assertThat(state.mmN()).isEqualTo(3);
            assertThat(state.ddN()).isEqualTo(12);
            assertThat(state.ccyymmddN()).isEqualTo(19850312);
        }

        @Test
        @DisplayName("writing through a numeric overlay rewrites the character bytes zero-filled")
        void writingThroughAnOverlayRewritesTheBytes() {
            EditDateState state = validator.newState();
            state.setEditDateCcyymmdd("2022 7 5");

            state.setMmN(7);
            state.setDdN(5);

            assertThat(state.editDateCcyymmdd()).isEqualTo("20220705");
            assertThat(state.mm()).isEqualTo("07");
            assertThat(state.dd()).isEqualTo("05");
        }
    }

    @Nested
    @DisplayName("Contracts - wiring, null handling and the PICTURE move rules")
    class Contracts {
        @Test
        @DisplayName("the class is a final @Component whose container constructor is @Autowired")
        void theWiringIsWhatTheContainerNeeds() throws NoSuchMethodException {
            assertThat(AccountDateValidator.class.isAnnotationPresent(Component.class)).isTrue();
            assertThat(Modifier.isFinal(AccountDateValidator.class.getModifiers()))
                    .as("nothing subclasses a translation of a copybook")
                    .isTrue();
            assertThat(AccountDateValidator.class.getConstructors())
                    .as("four, and only one of them annotated")
                    .hasSize(4);
            assertThat(AccountDateValidator.class.getConstructors())
                    .filteredOn(candidate -> candidate.isAnnotationPresent(Autowired.class))
                    .as("with four constructors and no no-argument candidate, the container needs "
                            + "exactly one to be marked")
                    .singleElement()
                    .satisfies(annotated -> {
                        assertThat(annotated.getParameterTypes())
                                .containsExactly(Charset.class, DateUtilityJob.class);
                        Qualifier onValidator =
                                annotated.getParameters()[0].getAnnotation(Qualifier.class);
                        assertThat(onValidator)
                                .as("three Charset beans exist and none is primary, so the "
                                        + "injection point must name one")
                                .isNotNull();
                        assertThat(onValidator.value())
                                .isEqualTo(DateUtilityJob.class
                                        .getConstructor(Charset.class)
                                        .getParameters()[0]
                                        .getAnnotation(Qualifier.class)
                                        .value())
                                .isNotBlank();
                    });
        }

        @Test
        @DisplayName("no class in either file holds mutable static state - the suite included")
        void thereIsNoStaticMutableState() {
            List<Class<?>> underTest = List.of(AccountDateValidator.class, EditDateState.class,
                    EditFlag.class, InputFlag.class);
            List<Class<?>> suite = new ArrayList<>();
            suite.add(AccountDateValidatorTest.class);
            suite.addAll(List.of(AccountDateValidatorTest.class.getDeclaredClasses()));

            for (Class<?> type : Stream.concat(underTest.stream(), suite.stream()).toList()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                        assertThat(Modifier.isFinal(field.getModifiers()))
                                .as("%s.%s is static and must therefore be final: COBOL "
                                        + "WORKING-STORAGE must never become shared Java state, and "
                                        + "neither may a test fixture",
                                        type.getSimpleName(), field.getName())
                                .isTrue();
                    }
                }
            }
            assertThat(suite)
                    .as("the nested classes really were discovered, so the sweep is not vacuous")
                    .hasSizeGreaterThan(10);
        }

        @Test
        @DisplayName("no import in this suite is a wildcard, and none reaches outside its dependencies")
        void theSuiteImportsOnlyWhatItDeclares() {
            List<String> imports = SUITE_LINES.stream()
                    .filter(text -> text.startsWith("import "))
                    .toList();

            assertThat(imports)
                    .as("practice B8 and gate G52: every type is named explicitly so the "
                            + "copybook-to-type correspondence stays auditable")
                    .isNotEmpty()
                    .noneMatch(text -> text.endsWith(".*;"));
            assertThat(imports)
                    .as("no duplicate single-type import")
                    .doesNotHaveDuplicates();
            assertThat(imports)
                    .as("no Spring container: this is a plain JUnit and Mockito suite, so the only "
                            + "org.springframework imports permitted are the annotation types read "
                            + "reflectively above")
                    .allSatisfy(text -> assertThat(text)
                            .doesNotContain("org.springframework.boot")
                            .doesNotContain("org.springframework.test")
                            .doesNotContain("org.springframework.context"));
            assertThat(imports)
                    .filteredOn(text -> text.startsWith("import com.vsergeychik"))
                    .as("only the declared dependencies: AccountDateValidator, its two nested enums, "
                            + "FixedWidthCodec and DateUtilityJob with its result type")
                    .allSatisfy(text -> assertThat(text)
                            .containsAnyOf("carddemo.account.AccountDateValidator",
                                    "carddemo.common.FixedWidth",
                                    "carddemo.util.DateUtilityJob"));
            String disabledAnnotation = "@Dis" + "abled";
            String deferredMarker = "TO" + "DO";
            String defectMarker = "FIX" + "ME";
            assertThat(SUITE_LINES)
                    .as("practice B10: nothing is disabled and nothing is deferred to a later session")
                    .noneMatch(text -> text.contains(disabledAnnotation))
                    .noneMatch(text -> text.contains(deferredMarker) || text.contains(defectMarker));

            String instantNow = "Instant." + "now(";
            String localDateNow = "LocalDate." + "now(";
            String currentMillis = "System." + "currentTimeMillis(";
            String systemClock = "Clock." + "system";
            assertThat(SUITE_LINES)
                    .as("practice B7: the wall clock is never read, so the verdicts are reproducible")
                    .noneMatch(text -> text.contains(instantNow)
                            || text.contains(localDateNow)
                            || text.contains(currentMillis)
                            || text.contains(systemClock));
            assertThat(SUITE_LINES)
                    .as("and the only clock in the file is a fixed one")
                    .anyMatch(text -> text.contains("Clock.fixed("));
        }

        @Test
        @DisplayName("all three constructors reach the same verdict")
        void theConstructorsAreEquivalent() {
            AccountDateValidator oneArgument = new AccountDateValidator(new DateUtilityJob());
            AccountDateValidator twoArguments = new AccountDateValidator(
                    new FixedWidthCodec(StandardCharsets.US_ASCII), new DateUtilityJob());

            for (AccountDateValidator candidate : List.of(oneArgument, twoArguments, validator)) {
                EditDateState state = candidate.newState();
                state.setEditVariableName(OPEN_DATE);
                state.setEditDateCcyymmdd("20220229");
                candidate.editDateCcyymmddThruExit(state);

                assertThat(flags(state)).isEqualTo("000");
                assertThat(state.returnMessage().trim())
                        .isEqualTo("Open Date:Not a leap year.Cannot have 29 days in this month.");
            }
            assertThat(oneArgument.clock()).isNotNull();
            assertThat(twoArguments.clock()).isNotNull();
        }

        @Test
        @DisplayName("every constructor argument is required")
        void theConstructorsRejectNull() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new AccountDateValidator(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> new AccountDateValidator((FixedWidthCodec) null,
                            new DateUtilityJob()));
            assertThatNullPointerException()
                    .isThrownBy(() -> new AccountDateValidator((Charset) null, new DateUtilityJob()))
                    .withMessageContaining("selected by qualifier");
            assertThatNullPointerException()
                    .isThrownBy(() -> new AccountDateValidator(
                            new FixedWidthCodec(StandardCharsets.US_ASCII), null))
                    .withMessageContaining("DateUtilityJob is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> new AccountDateValidator(
                            new FixedWidthCodec(StandardCharsets.US_ASCII), new DateUtilityJob(),
                            null))
                    .withMessageContaining("Clock is required");
            assertThatNullPointerException().isThrownBy(() -> new EditDateState(null));
        }

        @Test
        @DisplayName("every paragraph rejects a null state, and the birth check rejects a null clock")
        void theParagraphsRejectNull() {
            assertThatNullPointerException()
                    .isThrownBy(() -> validator.editDateCcyymmddThruExit(null));
            assertThatNullPointerException().isThrownBy(() -> validator.editDateCcyymmdd(null));
            assertThatNullPointerException().isThrownBy(() -> validator.editYearCcyy(null));
            assertThatNullPointerException().isThrownBy(() -> validator.editMonth(null));
            assertThatNullPointerException().isThrownBy(() -> validator.editDay(null));
            assertThatNullPointerException().isThrownBy(() -> validator.editDayMonthYear(null));
            assertThatNullPointerException().isThrownBy(() -> validator.editDateLe(null));
            assertThatNullPointerException().isThrownBy(() -> validator.editDateOfBirth(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> validator.editDateOfBirth(validator.newState(), null));
            assertThatNullPointerException()
                    .isThrownBy(() -> validator.currentDateIntrinsic(null));
            assertThatNullPointerException().isThrownBy(() -> AccountDateValidator.trim(null));
            assertThatNullPointerException().isThrownBy(() -> AccountDateValidator.testNumval(null));
            assertThatNullPointerException().isThrownBy(() -> AccountDateValidator.numval(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountDateValidator.isNumericClass(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountDateValidator.isAllSpaces(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountDateValidator.isAllLowValues(null));
        }

        @Test
        @DisplayName("the state's setters reject null and its flag setters reject null states")
        void theStateRejectsNull() {
            EditDateState state = validator.newState();

            assertThatNullPointerException().isThrownBy(() -> state.setEditDateCcyymmdd(null));
            assertThatNullPointerException().isThrownBy(() -> state.setEditVariableName(null));
            assertThatNullPointerException().isThrownBy(() -> state.setDateFormat(null));
            assertThatNullPointerException().isThrownBy(() -> state.setCc(null));
            assertThatNullPointerException().isThrownBy(() -> state.setYy(null));
            assertThatNullPointerException().isThrownBy(() -> state.setCcyy(null));
            assertThatNullPointerException().isThrownBy(() -> state.setMm(null));
            assertThatNullPointerException().isThrownBy(() -> state.setDd(null));
            assertThatNullPointerException().isThrownBy(() -> state.setYearFlag(null));
            assertThatNullPointerException().isThrownBy(() -> state.setMonthFlag(null));
            assertThatNullPointerException().isThrownBy(() -> state.setDayFlag(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> state.setCurrentDateYyyymmdd(null));
        }

        @Test
        @DisplayName("the alphanumeric move pads and truncates on the right")
        void theMoveRuleIsRightJustifiedPadding() {
            EditDateState state = validator.newState();

            state.setEditDateCcyymmdd("2022");
            assertThat(state.editDateCcyymmdd()).isEqualTo("2022    ");

            state.setEditDateCcyymmdd("202207199");
            assertThat(state.editDateCcyymmdd())
                    .as("PIC X discards the overflow from the right, keeping the leading eight")
                    .isEqualTo("20220719");

            state.setEditVariableName("A very long field name indeed, far past twenty-five");
            assertThat(state.editVariableName())
                    .hasSize(AccountDateValidator.WS_EDIT_VARIABLE_NAME_LENGTH)
                    .isEqualTo("A very long field name in");

            state.setDateFormat("YYYY-MM-DD-EXTRA");
            assertThat(state.dateFormat())
                    .hasSize(AccountDateValidator.WS_DATE_FORMAT_LENGTH)
                    .isEqualTo("YYYY-MM-");
        }

        @Test
        @DisplayName("an unsigned numeric receiver rejects a negative value outright")
        void theNumericOverlayRejectsANegativeValue() {
            EditDateState state = validator.newState();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> state.setMmN(-1));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> state.setDdN(-1));
        }

        @Test
        @DisplayName("STRING ... INTO leaves the remainder of the receiver untouched")
        void stringIntoPreservesTheTail() {
            EditDateState state = validator.newState();

            state.stringIntoReturnMessage("X".repeat(75));
            assertThat(state.returnMessage()).isEqualTo("X".repeat(75));
            assertThat(state.returnMsgOff()).isFalse();

            state.stringIntoReturnMessage("abc");
            assertThat(state.returnMessage())
                    .as("COBOL STRING transfers from the left and does not space-fill the rest")
                    .isEqualTo("abc" + "X".repeat(72));

            state.setReturnMsgOff();
            assertThat(state.returnMessage()).isEqualTo(" ".repeat(75));
            assertThat(state.returnMsgOff()).isTrue();
        }

        @Test
        @DisplayName("an over-long message is truncated at the receiver's last character position")
        void stringIntoStopsAtTheSpanEnd() {
            EditDateState state = validator.newState();

            state.stringIntoReturnMessage("Y".repeat(100));

            assertThat(state.returnMessage())
                    .hasSize(AccountDateValidator.WS_RETURN_MSG_LENGTH)
                    .isEqualTo("Y".repeat(75));
        }

        @Test
        @DisplayName("the calculation variables and the binary dates are settable and readable")
        void theWorkVariablesRoundTrip() {
            EditDateState state = validator.newState();

            state.setDivBy(AccountDateValidator.LEAP_DIVISOR_CENTURY);
            state.setDivisionResult(5, 0);
            state.setEditDateBinary(153967);
            state.setCurrentDateBinary(153968);

            assertThat(state.divBy()).isEqualTo(400);
            assertThat(state.dividend()).isEqualTo(5);
            assertThat(state.remainder()).isZero();
            assertThat(state.editDateBinary()).isEqualTo(153967);
            assertThat(state.currentDateBinary()).isEqualTo(153968);
        }

        @Test
        @DisplayName("the default state constructor is equivalent to the validator's factory")
        void theDefaultStateConstructorWorks() {
            EditDateState standalone = new EditDateState();
            standalone.setEditVariableName(OPEN_DATE);
            standalone.setEditDateCcyymmdd(GOOD_DATE);

            validator.editDateCcyymmddThruExit(standalone);

            assertThat(flags(standalone)).isEqualTo("___");
            assertThat(standalone.inputError()).isFalse();
        }

        @Test
        @DisplayName("each state is independent, so two validations cannot interfere")
        void statesAreIndependent() {
            EditDateState first = validator.newState();
            EditDateState second = validator.newState();

            first.setEditDateCcyymmdd(GOOD_DATE);

            assertThat(second.editDateCcyymmdd()).isEqualTo(EIGHT_SPACES);
            assertThat(first).isNotSameAs(second);
        }

        @Test
        @DisplayName("a state may be reused, because L19 wipes the previous verdict first")
        void aStateMayBeReused() {
            EditDateState state = given("20221319", OPEN_DATE);
            validator.editDateCcyymmddThruExit(state);
            assertThat(flags(state)).isEqualTo("_0_");

            state.setEditDateCcyymmdd(GOOD_DATE);
            validator.editDateCcyymmddThruExit(state);

            assertThat(flags(state))
                    .as("SET WS-EDIT-DATE-IS-INVALID at L19 clears the stale flags before any test")
                    .isEqualTo("___");
        }

        @Test
        @DisplayName("toString renders the date, the flags and the message without hiding LOW-VALUE")
        void toStringIsDiagnostic() {
            EditDateState state = given(GOOD_DATE, OPEN_DATE);
            state.setYearFlag(EditFlag.ISVALID);
            state.setMonthFlag(EditFlag.NOT_OK);
            state.setDayFlag(EditFlag.BLANK);

            assertThat(state.toString())
                    .contains("date='20220719'")
                    .contains("flags=<LOW>0B")
                    .contains("inputFlag=PENDING")
                    .contains("name='Open Date'");
        }

        @Test
        @DisplayName("the declared widths are the copybook's, and the mask is 'YYYYMMDD'")
        void theDeclaredWidthsAreExposed() {
            assertThat(AccountDateValidator.WS_EDIT_DATE_CCYYMMDD_LENGTH).isEqualTo(8);
            assertThat(AccountDateValidator.WS_EDIT_DATE_PART_LENGTH).isEqualTo(2);
            assertThat(AccountDateValidator.WS_EDIT_DATE_CCYY_LENGTH).isEqualTo(4);
            assertThat(AccountDateValidator.WS_EDIT_DATE_FLGS_LENGTH).isEqualTo(3);
            assertThat(AccountDateValidator.WS_DATE_VALIDATION_RESULT_LENGTH).isEqualTo(80);
            assertThat(AccountDateValidator.WS_EDIT_VARIABLE_NAME_LENGTH).isEqualTo(25);
            assertThat(AccountDateValidator.WS_RETURN_MSG_LENGTH).isEqualTo(75);
            assertThat(AccountDateValidator.WS_DATE_FORMAT_VALUE).isEqualTo("YYYYMMDD");
            assertThat(AccountDateValidator.THIS_CENTURY).isEqualTo(20);
            assertThat(AccountDateValidator.LAST_CENTURY).isEqualTo(19);
            assertThat(AccountDateValidator.WS_VALID_MONTH_LOW).isEqualTo(1);
            assertThat(AccountDateValidator.WS_VALID_MONTH_HIGH).isEqualTo(12);
            assertThat(AccountDateValidator.WS_VALID_DAY_LOW).isEqualTo(1);
            assertThat(AccountDateValidator.WS_VALID_DAY_HIGH).isEqualTo(31);
            assertThat(AccountDateValidator.WS_VALID_FEB_DAY_LOW).isEqualTo(1);
            assertThat(AccountDateValidator.WS_VALID_FEB_DAY_HIGH).isEqualTo(28);
            assertThat(AccountDateValidator.WS_DAY_31).isEqualTo(31);
            assertThat(AccountDateValidator.WS_DAY_30).isEqualTo(30);
            assertThat(AccountDateValidator.WS_DAY_29).isEqualTo(29);
            assertThat(AccountDateValidator.WS_FEBRUARY).isEqualTo(2);
            assertThat(AccountDateValidator.LEAP_DIVISOR_CENTURY).isEqualTo(400);
            assertThat(AccountDateValidator.LEAP_DIVISOR_ORDINARY).isEqualTo(4);
            assertThat(AccountDateValidator.INTEGER_OF_DATE_LOWEST_ARGUMENT).isEqualTo(16010101);
            assertThat(AccountDateValidator.INTEGER_OF_DATE_HIGHEST_ARGUMENT).isEqualTo(99991231);
        }
    }

    @Nested
    @DisplayName("The injected code page - the work area follows the Charset the constructor is given")
    class CodePage {
        private static final String EBCDIC_NAME = "IBM037";

        private AccountDateValidator validatorFor(String charsetName) {
            Charset charset = Charset.forName(charsetName);
            return new AccountDateValidator(charset, new DateUtilityJob(charset));
        }

        @Test
        @DisplayName("under IBM037 the eighty-byte work area holds IBM037 bytes, not ASCII ones")
        void underIbm037TheWorkAreaHoldsEbcdicBytes() {
            AccountDateValidator ebcdicValidator = validatorFor(EBCDIC_NAME);
            EditDateState state = ebcdicValidator.newState();
            state.setEditVariableName(OPEN_DATE);
            state.setEditDateCcyymmdd(GOOD_DATE);

            ebcdicValidator.editDateLe(state);

            byte[] area = state.dateValidationResultBytes();
            Charset ebcdic = Charset.forName(EBCDIC_NAME);

            assertThat(area).hasSize(AccountDateValidator.WS_DATE_VALIDATION_RESULT_LENGTH);
            assertThat(Arrays.copyOfRange(area, 4, 15))
                    .isEqualTo("Mesg Code: ".getBytes(ebcdic))
                    .isNotEqualTo("Mesg Code: ".getBytes(StandardCharsets.US_ASCII));
            assertThat(Arrays.copyOfRange(area, 20, 35))
                    .as("WS-RESULT carries the CEEDAYS verdict text through the same code page")
                    .isEqualTo("Date is valid  ".getBytes(ebcdic));
            assertThat(Arrays.copyOfRange(area, 0, 4))
                    .as("WS-SEVERITY PIC 9(4) zero is x'F0' four times in IBM037")
                    .containsExactly((byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0);

            assertThat(state.wsSeverity()).isEqualTo("0000");
            assertThat(state.wsResult()).isEqualTo("Date is valid  ");
        }

        @Test
        @DisplayName("under US-ASCII it matches byte for byte what this suite's own validator produces")
        void underAsciiItMatchesTheSuitesValidator() {
            AccountDateValidator asciiValidator =
                    validatorFor(StandardCharsets.US_ASCII.name());
            EditDateState wiredState = asciiValidator.newState();
            wiredState.setEditVariableName(OPEN_DATE);
            wiredState.setEditDateCcyymmdd(GOOD_DATE);
            asciiValidator.editDateLe(wiredState);

            EditDateState localState = given(GOOD_DATE, OPEN_DATE);
            validator.editDateLe(localState);

            assertThat(wiredState.dateValidationResultBytes())
                    .as("the mocked collaborator delegates to the real service, so the two agree")
                    .isEqualTo(localState.dateValidationResultBytes());
        }

        @Test
        @DisplayName("the injected code page reaches newState(), so state and validator never disagree")
        void theInjectedCodePageReachesNewState() {
            EditDateState state = validatorFor(EBCDIC_NAME).newState();

            assertThat(Arrays.copyOfRange(state.dateValidationResultBytes(), 4, 6))
                    .as("the area is initialised through the injected codec")
                    .isEqualTo("Me".getBytes(Charset.forName(EBCDIC_NAME)));
        }

        @Test
        @DisplayName("the code page never changes the verdict, only the bytes that carry it")
        void theCodePageDoesNotChangeTheVerdict() {
            for (String charsetName : List.of(EBCDIC_NAME, StandardCharsets.US_ASCII.name())) {
                AccountDateValidator subject = validatorFor(charsetName);
                EditDateState state = subject.newState();
                state.setEditVariableName(OPEN_DATE);
                state.setEditDateCcyymmdd("20220229");

                subject.editDateCcyymmddThruExit(state);

                assertThat(state.flagsImage().replace('\u0000', '_'))
                        .as("29 February 2022 is not a leap day under %s either", charsetName)
                        .isEqualTo("000");
                assertThat(state.returnMessage().trim())
                        .isEqualTo("Open Date:Not a leap year.Cannot have 29 days in this month.");
            }
        }
    }
}
