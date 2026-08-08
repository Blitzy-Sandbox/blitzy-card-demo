package com.vsergeychik.carddemo.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.vsergeychik.carddemo.account.AccountDateValidator.EditDateState;
import com.vsergeychik.carddemo.account.AccountDateValidator.EditFlag;
import com.vsergeychik.carddemo.account.AccountDateValidator.InputFlag;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.util.DateUtilityJob;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Parity tests for {@link AccountDateValidator}, the translation of {@code app/cpy/CSUTLDPY.cpy} and
 * {@code app/cpy/CSUTLDWY.cpy}.
 *
 * <p>This engine is almost entirely branches: 21 {@code 88}-level condition names, eleven guarded
 * rejection paths, a leap-year division with two divisors, and one deliberately preserved
 * fall-through defect. The suite is organised around what can actually go wrong:
 *
 * <ol>
 *   <li>{@link Provenance} re-reads the two copybooks and {@code app/cbl/CSUTLDTC.cbl} at test time
 *       and proves the things the translation asserts about them: every message literal verbatim, the
 *       eighty-byte span arithmetic, the linkage widths that justify the 8&rarr;10 widening, the
 *       absence of {@code ROUNDED}, and - the important one - the physical order of the guards in
 *       {@code EDIT-MONTH} and {@code EDIT-DAY}, which are the reverse of each other.</li>
 *   <li>{@link FallThrough} pins the L323/L327 defect. If a future change "tidies" it, this fails.</li>
 *   <li>{@link EditYear}, {@link EditMonth}, {@link EditDay} and {@link DayMonthYear} drive every
 *       guard of every paragraph from both sides, including the guard-order asymmetry that makes
 *       {@code MM = '5 '} a rejection and {@code DD = '5 '} an acceptance.</li>
 *   <li>{@link LanguageEnvironment} drives the {@code CSUTLDTC} call and asserts the eighty-byte
 *       result through this class's own offsets, which is what proves the two declarations of that
 *       area agree.</li>
 *   <li>{@link DateOfBirth} pins the strict comparison - today is rejected - and the
 *       {@code INTEGER-OF-DATE} epoch.</li>
 *   <li>{@link FlagModel}, {@link Intrinsics} and {@link Contracts} cover the state model, the
 *       hand-written COBOL intrinsics and the null and wiring contracts.</li>
 * </ol>
 */
@DisplayName("AccountDateValidator - CSUTLDPY/CSUTLDWY date-edit engine")
class AccountDateValidatorTest {

    /** The procedure copybook this class translates. */
    private static final String PROCEDURE_COPYBOOK = "app/cpy/CSUTLDPY.cpy";

    /** The working-storage copybook this class models. */
    private static final String WORKING_COPYBOOK = "app/cpy/CSUTLDWY.cpy";

    /** The subprogram {@code EDIT-DATE-LE} calls, whose linkage widths differ from the arguments. */
    private static final String CALLED_SUBPROGRAM = "app/cbl/CSUTLDTC.cbl";

    /** A fixed instant so that {@code FUNCTION CURRENT-DATE} is assertable: 19 July 2022, 10:15:30. */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T10:15:30Z");

    /** The clock every date-of-birth test reads. */
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    /** Today, as the fixed clock reports it, in {@code 9(8)} form. */
    private static final String TODAY = "20220719";

    /** Eight {@code LOW-VALUE} bytes: what CICS delivers for a map field never transmitted. */
    private static final String EIGHT_LOW_VALUES = "\u0000".repeat(8);

    /** Eight spaces: what a cleared screen field delivers. */
    private static final String EIGHT_SPACES = " ".repeat(8);

    private static List<String> procedureCopybook;

    private static List<String> workingCopybook;

    private static List<String> calledSubprogram;

    /** The unit under test, with the real {@code CSUTLDTC} service and the fixed clock. */
    private AccountDateValidator validator;

    @BeforeAll
    static void readCopybooks() {
        Path root = repositoryRoot();
        procedureCopybook = readLines(root.resolve(PROCEDURE_COPYBOOK));
        workingCopybook = readLines(root.resolve(WORKING_COPYBOOK));
        calledSubprogram = readLines(root.resolve(CALLED_SUBPROGRAM));
    }

    @BeforeEach
    void createValidator() {
        validator = new AccountDateValidator(new FixedWidthCodec(StandardCharsets.US_ASCII),
                new DateUtilityJob(), FIXED_CLOCK);
    }

    /**
     * Locates the repository root by walking up from the working directory until the procedure
     * copybook is found, so the suite runs from the module directory, the repository root or an IDE.
     *
     * @return the repository root
     */
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

    /**
     * @param path the file to read
     * @return its lines, in order
     */
    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Cannot read " + path, unreadable);
        }
    }

    /**
     * @param source     the file's lines
     * @param lineNumber the one-based COBOL line number
     * @return that line
     */
    private static String line(List<String> source, int lineNumber) {
        return source.get(lineNumber - 1);
    }

    /**
     * @param source   the file's lines
     * @param fragment the text to find
     * @return the one-based number of the first line containing it
     */
    private static int lineNumberOf(List<String> source, String fragment) {
        for (int index = 0; index < source.size(); index++) {
            if (source.get(index).contains(fragment)) {
                return index + 1;
            }
        }
        throw new IllegalStateException("No line of the copybook contains '" + fragment + "'");
    }

    /**
     * Builds a state ready for the range perform, exactly as a {@code COACTUPC} call site does:
     * the field name first, then the eight date characters.
     *
     * @param date      the eight characters to validate
     * @param fieldName the value of {@code WS-EDIT-VARIABLE-NAME}
     * @return the prepared state
     */
    private EditDateState given(String date, String fieldName) {
        EditDateState state = validator.newState();
        state.setEditVariableName(fieldName);
        state.setEditDateCcyymmdd(date);
        return state;
    }

    /**
     * Runs the whole paragraph range over a date, under the field name {@code 'Open Date'}.
     *
     * @param date the eight characters to validate
     * @return the state after the range
     */
    private EditDateState validate(String date) {
        EditDateState state = given(date, "Open Date");
        validator.editDateCcyymmddThruExit(state);
        return state;
    }

    /**
     * The three flag bytes with {@code LOW-VALUE} rendered visibly, for readable assertions.
     *
     * @param state the state to render
     * @return the flag group as {@code _}, {@code 0} and {@code B}
     */
    private static String flags(EditDateState state) {
        return state.flagsImage().replace('\u0000', '_');
    }

    // =============================================================================================
    // 1. Provenance: what the translation asserts about the copybooks, re-verified from the files.
    // =============================================================================================

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

        /**
         * @param literal    the Java constant
         * @param lineNumber the copybook line that must declare it, single-quoted
         */
        private void assertLiteralDeclaredAt(String literal, int lineNumber) {
            assertThat(line(procedureCopybook, lineNumber).trim())
                    .as("app/cpy/CSUTLDPY.cpy:L%d must declare '%s' exactly", lineNumber, literal)
                    .isEqualTo("'" + literal + "'");
        }

        @Test
        @DisplayName("L323-L328: the EXIT is a no-op and the SET is a separate sentence after it")
        void theFallThroughStructureIsWhatTheTranslationClaims() {
            assertThat(line(procedureCopybook, 323).trim()).isEqualTo("EDIT-DATE-LE-EXIT.");
            assertThat(line(procedureCopybook, 324).trim()).isEqualTo("EXIT");
            assertThat(line(procedureCopybook, 325).trim()).isEqualTo(".");
            assertThat(line(procedureCopybook, 327).trim())
                    .isEqualTo("SET WS-EDIT-DATE-IS-VALID        TO TRUE");
            assertThat(line(procedureCopybook, 329).trim())
                    .as("the next paragraph must not begin until L329, which is what puts the L327 "
                            + "SET inside EDIT-DATE-LE-EXIT and therefore on every path into it")
                    .isEqualTo("EDIT-DATE-CCYYMMDD-EXIT.");
        }

        @Test
        @DisplayName("EDIT-MONTH tests the range before TEST-NUMVAL; EDIT-DAY does the reverse")
        void theGuardOrderIsPerParagraphAndIsNotUniform() {
            int monthRange = lineNumberOf(procedureCopybook, "IF WS-VALID-MONTH");
            int monthNumeric = lineNumberOf(procedureCopybook, "TEST-NUMVAL (WS-EDIT-DATE-MM)");
            int dayNumeric = lineNumberOf(procedureCopybook, "TEST-NUMVAL (WS-EDIT-DATE-DD)");
            int dayRange = lineNumberOf(procedureCopybook, "IF WS-VALID-DAY");

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
            assertThat(line(procedureCopybook, 27)).contains("SET FLG-YEAR-NOT-OK");
            assertThat(line(procedureCopybook, 92)).contains("SET FLG-MONTH-NOT-OK");
            assertThat(line(procedureCopybook, 152)).contains("SET FLG-DAY-ISVALID");
        }

        @Test
        @DisplayName("the WS-DATE-VALIDATION-RESULT storage spans sum to exactly 80 bytes")
        void theResultAreaIsEightyBytes() {
            int declared = 0;
            for (int lineNumber = 60; lineNumber <= 85; lineNumber++) {
                String text = line(workingCopybook, lineNumber);
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
            assertThat(line(calledSubprogram, 84)).contains("LS-DATE         PIC X(10)");
            assertThat(line(calledSubprogram, 85)).contains("LS-DATE-FORMAT  PIC X(10)");
            assertThat(line(calledSubprogram, 86)).contains("LS-RESULT       PIC X(80)");
            assertThat(line(workingCopybook, 58))
                    .as("the caller's mask is only eight characters wide")
                    .contains("WS-DATE-FORMAT                        PIC X(08)");
            assertThat(AccountDateValidator.WS_DATE_FORMAT_LENGTH).isEqualTo(8);
            assertThat(DateUtilityJob.LS_DATE_LENGTH).isEqualTo(10);
            assertThat(DateUtilityJob.LS_DATE_FORMAT_LENGTH).isEqualTo(10);
        }

        @Test
        @DisplayName("ROUNDED appears nowhere, so truncation is the only faithful store rule")
        void noStatementRounds() {
            assertThat(procedureCopybook).noneMatch(text -> text.contains("ROUNDED"));
            assertThat(workingCopybook).noneMatch(text -> text.contains("ROUNDED"));
        }

        @Test
        @DisplayName("the 88-level values are the copybook's own")
        void theConditionNameValuesAreDeclaredAsTranslated() {
            assertThat(line(workingCopybook, 9)).contains("THIS-CENTURY").contains("VALUE 20");
            assertThat(line(workingCopybook, 10)).contains("LAST-CENTURY").contains("VALUE 19");
            assertThat(line(workingCopybook, 24)).contains("WS-FEBRUARY").contains("VALUE 2");
            assertThat(line(workingCopybook, 30)).contains("WS-DAY-31").contains("VALUE 31");
            assertThat(line(workingCopybook, 31)).contains("WS-DAY-30").contains("VALUE 30");
            assertThat(line(workingCopybook, 32)).contains("WS-DAY-29").contains("VALUE 29");
            assertThat(line(workingCopybook, 44)).contains("WS-EDIT-DATE-IS-VALID")
                    .contains("LOW-VALUES");
            assertThat(line(workingCopybook, 45)).contains("WS-EDIT-DATE-IS-INVALID")
                    .contains("'000'");
            assertThat(line(procedureCopybook, 246)).contains("MOVE 400");
            assertThat(line(procedureCopybook, 248)).contains("MOVE 4");
        }

        @Test
        @DisplayName("WS-VALID-FEB-DAY is declared and never referenced, so it is kept unreferenced")
        void theUnreferencedConditionNameIsPreserved() {
            assertThat(line(workingCopybook, 33)).contains("WS-VALID-FEB-DAY");
            assertThat(procedureCopybook)
                    .as("no statement of CSUTLDPY tests WS-VALID-FEB-DAY; it is modelled anyway "
                            + "because it is part of the copybook's declared contract")
                    .noneMatch(text -> text.contains("WS-VALID-FEB-DAY"));
        }

        @Test
        @DisplayName("the commented-out FUNCTION FIND-DURATION alternative stays out of the Java")
        void theCommentedOutAlternativeIsNotCode() {
            assertThat(line(procedureCopybook, 351).stripLeading())
                    .startsWith("*")
                    .contains("FUNCTION FIND-DURATION");
        }
    }

    // =============================================================================================
    // 2. The L323/L327 fall-through. This is the highest-value test in the suite.
    // =============================================================================================

    @Nested
    @DisplayName("The L323/L327 fall-through - preserved, not corrected")
    class FallThrough {

        /**
         * {@code EDIT-DATE-LE-EXIT} (app/cpy/CSUTLDPY.cpy L323-L328) holds a no-op {@code EXIT} and
         * then, as a separate sentence at L327, {@code SET WS-EDIT-DATE-IS-VALID TO TRUE}. Because
         * the caller performs the paragraph <em>range</em>
         * ({@code COACTUPC:L1480-L1481}), that set runs on every path into the paragraph - the
         * {@code GO TO EDIT-DATE-LE-EXIT} at L315 included. So a date the Language Environment
         * rejects comes back with all three flags reading {@code ISVALID}.
         */
        @Test
        @DisplayName("a rejected date comes back with all three flags ISVALID - the defect")
        void theGroupSetAtL327DiscardsTheNotOkFlags() {
            AccountDateValidator withRejectingService = new AccountDateValidator(
                    new FixedWidthCodec(StandardCharsets.US_ASCII), new AlwaysRejectingDateUtility(),
                    FIXED_CLOCK);
            EditDateState state = withRejectingService.newState();
            state.setEditVariableName("Open Date");
            state.setEditDateCcyymmdd("20220719");

            withRejectingService.editDateCcyymmddThruExit(state);

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
            AccountDateValidator withRejectingService = new AccountDateValidator(
                    new FixedWidthCodec(StandardCharsets.US_ASCII), new AlwaysRejectingDateUtility(),
                    FIXED_CLOCK);
            EditDateState state = withRejectingService.newState();
            state.setEditVariableName("Open Date");
            state.setEditDateCcyymmdd("20220719");

            withRejectingService.editDateLe(state);

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

    // =============================================================================================
    // 3. The flag model - CSUTLDWY L43-L57 and COACTUPC L170-L174.
    // =============================================================================================

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

    // =============================================================================================
    // 4. EDIT-YEAR-CCYY - app/cpy/CSUTLDPY.cpy L25-L87.
    // =============================================================================================

    @Nested
    @DisplayName("EDIT-YEAR-CCYY - L25-L87")
    class EditYear {

        @Test
        @DisplayName("L30: LOW-VALUES is not supplied, and the flag is BLANK rather than NOT-OK")
        void lowValuesIsBlank() {
            EditDateState state = given(EIGHT_LOW_VALUES, "Open Date");

            validator.editYearCcyy(state);

            assertThat(state.yearFlag()).isSameAs(EditFlag.BLANK);
            assertThat(state.inputError()).isTrue();
            assertThat(state.returnMessage().trim())
                    .isEqualTo("Open Date : Year must be supplied.");
        }

        @Test
        @DisplayName("L31: SPACES is not supplied either - both figurative constants are tested")
        void spacesIsBlank() {
            EditDateState state = given(EIGHT_SPACES, "Expiry Date");

            validator.editYearCcyy(state);

            assertThat(state.yearFlag()).isSameAs(EditFlag.BLANK);
            assertThat(state.returnMessage().trim())
                    .isEqualTo("Expiry Date : Year must be supplied.");
        }

        @ParameterizedTest
        @ValueSource(strings = {"19 4", "1a22", "+202", "20.2", "202 "})
        @DisplayName("L48: IS NOT NUMERIC demands four digits - no sign, point or embedded space")
        void theClassConditionIsStrict(String ccyy) {
            EditDateState state = given(ccyy + "0719", "Open Date");

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
            EditDateState state = given(ccyy + "0715", "Open Date");

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
            EditDateState state = given("20220719", "Open Date");
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
            EditDateState state = given(EIGHT_SPACES, "Open Date");
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
            EditDateState state = given("20220719", "Open Date");
            state.setMonthFlag(EditFlag.NOT_OK);

            validator.editYearCcyy(state);

            assertThat(state.yearFlag()).isSameAs(EditFlag.ISVALID);
            assertThat(state.monthFlag()).isSameAs(EditFlag.NOT_OK);
            assertThat(state.inputError()).isFalse();
            assertThat(state.returnMsgOff()).isTrue();
        }
    }

    // =============================================================================================
    // 5. EDIT-MONTH - app/cpy/CSUTLDPY.cpy L91-L144. Range before numeric.
    // =============================================================================================

    @Nested
    @DisplayName("EDIT-MONTH - L91-L144, range test first")
    class EditMonth {

        @Test
        @DisplayName("L94: a blank month is BLANK, not NOT-OK")
        void aBlankMonthIsBlank() {
            EditDateState state = given("2022  19", "Open Date");

            validator.editMonth(state);

            assertThat(state.monthFlag()).isSameAs(EditFlag.BLANK);
            assertThat(state.returnMessage().trim())
                    .isEqualTo("Open Date : Month must be supplied.");
        }

        @Test
        @DisplayName("L94: LOW-VALUES in the month is blank too")
        void lowValuesInTheMonthIsBlank() {
            EditDateState state = given("2022" + "\u0000\u0000" + "19", "Open Date");

            validator.editMonth(state);

            assertThat(state.monthFlag()).isSameAs(EditFlag.BLANK);
        }

        @ParameterizedTest
        @ValueSource(strings = {"01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11",
                "12"})
        @DisplayName("L111: every month 1 through 12 is accepted")
        void everyValidMonthIsAccepted(String mm) {
            EditDateState state = given("2022" + mm + "15", "Open Date");

            validator.editMonth(state);

            assertThat(state.wsValidMonth()).isTrue();
            assertThat(state.monthFlag()).isSameAs(EditFlag.ISVALID);
            assertThat(state.inputError()).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"00", "13", "14", "20", "99"})
        @DisplayName("L111: a month outside 1 through 12 is rejected by the range test")
        void anOutOfRangeMonthIsRejected(String mm) {
            EditDateState state = given("2022" + mm + "15", "Expiry Date");

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
            // '0a' reads as zoned 01 - the low nibble of 'a' (0x61) is 1 - so the range test at L111
            // passes, and only FUNCTION TEST-NUMVAL at L126 rejects it. Both paths carry the same
            // literal, which is why the message alone cannot tell them apart.
            EditDateState state = given("20220a15", "Open Date");
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
            EditDateState state = given("2022 515", "Open Date");
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
            EditDateState state = given("20225 15", "Open Date");
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
            EditDateState state = given("20220115", "Open Date");
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

    // =============================================================================================
    // 6. EDIT-DAY - app/cpy/CSUTLDPY.cpy L150-L204. Numeric before range - the reverse of EDIT-MONTH.
    // =============================================================================================

    @Nested
    @DisplayName("EDIT-DAY - L150-L204, numeric test first")
    class EditDay {

        @Test
        @DisplayName("L152: the paragraph opens ISVALID, unlike the year and month edits")
        void theParagraphOpensValid() {
            EditDateState state = given("20220715", "Open Date");
            state.setDayFlag(EditFlag.NOT_OK);

            validator.editDay(state);

            assertThat(state.dayFlag()).isSameAs(EditFlag.ISVALID);
        }

        @Test
        @DisplayName("L154: a blank day is BLANK")
        void aBlankDayIsBlank() {
            EditDateState state = given("202207  ", "Reissue Date");

            validator.editDay(state);

            assertThat(state.dayFlag()).isSameAs(EditFlag.BLANK);
            assertThat(state.returnMessage().trim())
                    .isEqualTo("Reissue Date : Day must be supplied.");
        }

        @Test
        @DisplayName("L154: LOW-VALUES in the day is blank too")
        void lowValuesInTheDayIsBlank() {
            EditDateState state = given("202207" + "\u0000\u0000", "Open Date");

            validator.editDay(state);

            assertThat(state.dayFlag()).isSameAs(EditFlag.BLANK);
        }

        @ParameterizedTest
        @ValueSource(strings = {"ab", "1x", "x1", "a1"})
        @DisplayName("L170: TEST-NUMVAL runs before the range test and rejects first")
        void theNumericTestRunsFirst(String dd) {
            EditDateState state = given("202207" + dd, "Open Date");

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
            EditDateState state = given("202207" + dd, "Open Date");

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
            EditDateState state = given("202207" + dd, "Open Date");

            validator.editDay(state);

            assertThat(state.wsValidDay()).isTrue();
            assertThat(state.dayFlag()).isSameAs(EditFlag.ISVALID);
        }

        @Test
        @DisplayName("'5 ' IS accepted here - the mirror image of the month behaviour")
        void aTrailingSpaceDayIsAccepted() {
            EditDateState state = given("2022075 ", "Open Date");
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
            EditDateState state = given("202207-1", "Open Date");

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
            EditDateState state = given("202207.5", "Open Date");

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
            EditDateState state = given("20220701", "Open Date");
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

    // =============================================================================================
    // 7. EDIT-DAY-MONTH-YEAR - app/cpy/CSUTLDPY.cpy L209-L279.
    // =============================================================================================

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
            EditDateState state = given(ccyy + "0229", "Open Date");

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
            EditDateState state = given("20210228", "Open Date");

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
            EditDateState state = given("20220715", "Open Date");
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

    // =============================================================================================
    // 8. EDIT-DATE-LE - app/cpy/CSUTLDPY.cpy L284-L321, and the 80-byte shared area.
    // =============================================================================================

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
            EditDateState state = given("20220719", "Open Date");

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
            EditDateState state = given("20220719", "Open Date");

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
        @DisplayName("a rejected date sets all three flags NOT-OK and the five-operand message")
        void aRejectedDateReportsSeverityAndMessageNumber() {
            AccountDateValidator withRejectingService = new AccountDateValidator(
                    new FixedWidthCodec(StandardCharsets.US_ASCII), new AlwaysRejectingDateUtility(),
                    FIXED_CLOCK);
            EditDateState state = withRejectingService.newState();
            state.setEditVariableName("Date of Birth");
            state.setEditDateCcyymmdd("20220719");

            withRejectingService.editDateLe(state);

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
        @DisplayName("L318: an earlier field's error suppresses the day flag being cleared")
        void theSharedInputFlagCouplesTheFields() {
            EditDateState state = given("20220719", "Open Date");
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
            AccountDateValidator withRejectingService = new AccountDateValidator(
                    new FixedWidthCodec(StandardCharsets.US_ASCII), new AlwaysRejectingDateUtility(),
                    FIXED_CLOCK);
            EditDateState state = withRejectingService.newState();
            state.setEditVariableName("Open Date");
            state.setEditDateCcyymmdd("20220719");
            state.stringIntoReturnMessage("Account number not provided");

            withRejectingService.editDateLe(state);

            assertThat(state.returnMessage().trim())
                    .as("WS-RETURN-MSG-OFF is false, so the STRING at L306 never runs")
                    .isEqualTo("Account number not provided");
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

    // =============================================================================================
    // 9. EDIT-DATE-OF-BIRTH - app/cpy/CSUTLDPY.cpy L341-L368.
    // =============================================================================================

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
            EditDateState state = given(TODAY, "Date of Birth");
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
            state.setEditVariableName("Date of Birth");
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

        /**
         * Runs the whole range and then, exactly as {@code COACTUPC:L1536-L1543} does, the birth-date
         * check when and only when the flag group came back {@code ISVALID}.
         *
         * @param date the eight characters to validate
         * @return the state after both performs
         */
        private EditDateState birthDate(String date) {
            EditDateState state = given(date, "Date of Birth");
            validator.editDateCcyymmddThruExit(state);
            if (state.wsEditDateIsValid()) {
                validator.editDateOfBirth(state, FIXED_CLOCK);
            }
            return state;
        }
    }

    // =============================================================================================
    // 10. The hand-written COBOL intrinsics.
    // =============================================================================================

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

    // =============================================================================================
    // 11. Contracts: wiring, nulls, moves and the state's remaining accessors.
    // =============================================================================================

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
            assertThat(AccountDateValidator.class.getConstructor(DateUtilityJob.class)
                    .isAnnotationPresent(Autowired.class))
                    .as("with three constructors and no no-argument candidate, the container needs "
                            + "one to be marked")
                    .isTrue();
            assertThat(AccountDateValidator.class.getConstructors())
                    .as("exactly three, and only one of them annotated")
                    .hasSize(3);
        }

        @Test
        @DisplayName("no class in the file holds mutable static state")
        void thereIsNoStaticMutableState() {
            for (Class<?> type : List.of(AccountDateValidator.class, EditDateState.class,
                    EditFlag.class, InputFlag.class)) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                        assertThat(Modifier.isFinal(field.getModifiers()))
                                .as("%s.%s is static and must therefore be final: COBOL "
                                        + "WORKING-STORAGE must never become shared Java state",
                                        type.getSimpleName(), field.getName())
                                .isTrue();
                    }
                }
            }
        }

        @Test
        @DisplayName("all three constructors reach the same verdict")
        void theConstructorsAreEquivalent() {
            AccountDateValidator oneArgument = new AccountDateValidator(new DateUtilityJob());
            AccountDateValidator twoArguments = new AccountDateValidator(
                    new FixedWidthCodec(StandardCharsets.US_ASCII), new DateUtilityJob());

            for (AccountDateValidator candidate : List.of(oneArgument, twoArguments, validator)) {
                EditDateState state = candidate.newState();
                state.setEditVariableName("Open Date");
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
                    .isThrownBy(() -> new AccountDateValidator(null, new DateUtilityJob()));
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
            standalone.setEditVariableName("Open Date");
            standalone.setEditDateCcyymmdd("20220719");

            validator.editDateCcyymmddThruExit(standalone);

            assertThat(flags(standalone)).isEqualTo("___");
            assertThat(standalone.inputError()).isFalse();
        }

        @Test
        @DisplayName("each state is independent, so two validations cannot interfere")
        void statesAreIndependent() {
            EditDateState first = validator.newState();
            EditDateState second = validator.newState();

            first.setEditDateCcyymmdd("20220719");

            assertThat(second.editDateCcyymmdd()).isEqualTo(EIGHT_SPACES);
            assertThat(first).isNotSameAs(second);
        }

        @Test
        @DisplayName("a state may be reused, because L19 wipes the previous verdict first")
        void aStateMayBeReused() {
            EditDateState state = given("20221319", "Open Date");
            validator.editDateCcyymmddThruExit(state);
            assertThat(flags(state)).isEqualTo("_0_");

            state.setEditDateCcyymmdd("20220719");
            validator.editDateCcyymmddThruExit(state);

            assertThat(flags(state))
                    .as("SET WS-EDIT-DATE-IS-INVALID at L19 clears the stale flags before any test")
                    .isEqualTo("___");
        }

        @Test
        @DisplayName("toString renders the date, the flags and the message without hiding LOW-VALUE")
        void toStringIsDiagnostic() {
            EditDateState state = given("20220719", "Open Date");
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

    /**
     * A {@code CSUTLDTC} that rejects every date, built by asking the real service about a date it
     * genuinely rejects. This is the only way to reach the L323/L327 defect: the year, month, day and
     * combination edits between them reject every impossible date, so the Language Environment never
     * disagrees with them on any input a screen can supply. The defect is latent, not unreachable -
     * and a latent defect that a caller could hit still has to be reproduced.
     */
    private static final class AlwaysRejectingDateUtility extends DateUtilityJob {

        /** A genuine rejection: 32 July is a real {@code CEEDAYS} bad-date-value outcome. */
        private final DateValidationResult rejection =
                new DateUtilityJob().validateDate("20220732", AccountDateValidator.WS_DATE_FORMAT_VALUE);

        @Override
        public DateValidationResult validateDate(String lsDate, String lsDateFormat) {
            return rejection;
        }
    }
}
