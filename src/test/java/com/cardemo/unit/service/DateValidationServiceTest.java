/*
 * ******************************************************************
 * Program     : DateValidationServiceTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the replacement for the statically-called
 *               date utility: the eighty-byte result area it returns,
 *               the CEEDAYS feedback codes it reproduces, the zoned
 *               severity that became the return code, and the
 *               composite field edits including the date-of-birth
 *               future test evaluated against an injected clock.
 * Source      : app/cbl/CSUTLDTC.cbl:L60-L152 (whole program)
 *               app/cbl/CSUTLDTC.cbl:L43-L57  (WS-MESSAGE, 80 bytes)
 *               app/cbl/CSUTLDTC.cbl:L61-L70  (feedback code tokens)
 *               app/cpy/CSUTLDPY.cpy          (composite edits)
 *               app/cpy/CSUTLDWY.cpy          (edit work areas)
 *               frozen at commit 7756d89
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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 * ******************************************************************
 */
package com.cardemo.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.DateValidationService.DateValidationResult;
import com.cardemo.service.shared.DateValidationService.EditFlag;
import com.cardemo.service.shared.DateValidationService.FeedbackCode;
import com.cardemo.service.shared.DateValidationService.EditOutcome;
import com.cardemo.service.shared.DateValidationService.FeedbackCondition;
import com.cardemo.unit.model.FixedClockProvider;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Executable specification for the date utility that every screen in the corpus calls statically.
 *
 * <p>The legacy program is short - 157 lines - and almost all of its behaviour is contract rather than
 * logic: it packs a fixed eighty-byte result area, maps a CEEDAYS feedback token onto one of ten fifteen-
 * character result strings, and moves the severity into the return code. That makes it unusually amenable
 * to exact assertion, and unusually unforgiving, because a caller reads the result by offset.
 *
 * <p>The eighty-byte layout is asserted here character by character rather than by length alone. It is
 * built from thirteen fields whose widths sum to exactly eighty - {@code 4 + 11 + 4 + 1 + 15 + 1 + 9 + 10
 * + 1 + 10 + 10 + 1 + 3} - and three of those fields are literal text with deliberate padding that is easy
 * to get wrong: {@code 'Mesg Code:'} is ten characters in an eleven-character field, {@code 'TstDate:'} is
 * eight in a nine-character field, and {@code 'Mask used:'} exactly fills its ten. The expected string in
 * {@link ResultArea} was derived by hand from the copybook's own field declarations, not read back from the
 * implementation, so it is an independent check rather than a restatement.
 *
 * <p>One preserved quirk deserves flagging because it reads as a bug in both languages. The condition name
 * {@code FC-INVALID-DATE} is defined as the all-zeros feedback token, and the EVALUATE that consumes it
 * moves the text {@code 'Date is valid'}. The name therefore means the opposite of what it says: an
 * all-zeros token is CEEDAYS reporting success. The Java enumeration reproduces the misnomer verbatim as
 * {@code FC_INVALID_DATE(0, 0, "Date is valid")}, which is correct under the parity mandate - renaming it
 * would be a readability improvement that silently broke the traceability the matrix asserts. It is pinned
 * below so that nobody later "fixes" it.
 *
 * <p>Every numeric token was cross-checked against the copybook's hexadecimal literals rather than trusted:
 * the severity and message number are the first two halfwords of the eight-byte token, so
 * {@code X'000309CB...'} is severity 3, message 2507, and the remaining eight conditions decode to 2508,
 * 2509, 2513, 2517, 2518, 2520 and 2521 in the order the copybook declares them.
 *
 * <p>The date-of-birth edit is the only part of this service that reads a clock, so it is exercised through
 * an injected fixed clock. That also lets the equality boundary be pinned: the source tests
 * {@code current > underEdit} and treats equality as a failure, so a date of birth of <em>today</em> is
 * reported as being in the future. That is surprising, it is what the source does, and it is asserted.
 */
@DisplayName("DateValidationService: the eighty-byte contract, the feedback codes, and the composite edits")
class DateValidationServiceTest {

    /** The canonical fixed instant, 2022-06-10T19:27:53Z, shared with the other deterministic tests. */
    private static final Instant NOW = FixedClockProvider.CANONICAL_INSTANT;

    private DateValidationService service;

    @BeforeEach
    void createServiceOnAFixedClock() {
        service = new DateValidationService(FixedClockProvider.canonicalClock());
    }

    @Nested
    @DisplayName("1. The eighty-byte WS-MESSAGE result area")
    class ResultArea {

        @Test
        @DisplayName("a valid separated date produces the exact eighty-byte area, field by field")
        void exactEightyByteAreaForAValidDate() {
            // Derived by hand from CSUTLDTC.cbl:L43-L57, not read back from the implementation:
            //   X(04) severity | X(11) 'Mesg Code:' | X(04) msg no | X(01) space | X(15) result
            // | X(01) space | X(09) 'TstDate:' | X(10) date | X(01) space | X(10) 'Mask used:'
            // | X(10) mask | X(01) space | X(03) spaces
            // The TstDate field is NOT the supplied date. CSUTLDTC.cbl:L107-L108 moves LS-DATE into
            // WS-DATE correctly, and then :L122 overwrites it with MOVE WS-DATE-TO-TEST TO WS-DATE - a
            // GROUP move. WS-DATE-TO-TEST is twelve bytes (Vstring-length PIC S9(4) BINARY, then ten
            // characters of text) and WS-DATE is PIC X(10), so the move truncates to the first ten bytes:
            // the two binary length bytes followed by only the FIRST EIGHT characters of the date. With a
            // length of ten those bytes are X'00' and X'0A', the second of which is a line feed. The
            // eighty-byte area a caller receives therefore carries a corrupted date field. That is a
            // legacy defect and it is reproduced rather than repaired, because the parity comparison is
            // measured against the legacy output.
            String corruptedDate = "\u0000\n" + "2022-06-";
            String expected = "0000" + "Mesg Code: " + "0000" + " " + "Date is valid  " + " "
                    + "TstDate: " + corruptedDate + " " + "Mask used:" + "YYYY-MM-DD" + " " + "   ";

            DateValidationResult result = service.validate("2022-06-10", DateValidationService.MASK_YYYY_MM_DD);

            assertThat(corruptedDate).as("the group move still yields ten bytes").hasSize(10);
            assertThat(expected).as("the hand-derived expectation is itself eighty bytes").hasSize(80);
            assertThat(result.result()).isEqualTo(expected);
        }

        @Test
        @DisplayName("the area is always exactly eighty characters, whatever the outcome")
        void areaIsAlwaysEightyCharacters() {
            for (String candidate : new String[] {
                "2022-06-10", "2022-13-01", "not-a-date", "          ", "0000-01-01", "1500-01-01",
            }) {
                assertThat(service.validate(candidate, DateValidationService.MASK_YYYY_MM_DD).result())
                        .as("candidate '%s'", candidate)
                        .hasSize(DateValidationService.LS_RESULT_LENGTH);
            }
        }

        @Test
        @DisplayName("the three literal fillers carry their copybook padding exactly")
        void literalFillersCarryTheirPadding() {
            String area = service.validate("2022-06-10", DateValidationService.MASK_YYYY_MM_DD).result();

            assertThat(area.substring(4, 15))
                    .as("'Mesg Code:' is ten characters in an eleven-character field")
                    .isEqualTo("Mesg Code: ");
            assertThat(area.substring(36, 45))
                    .as("'TstDate:' is eight characters in a nine-character field")
                    .isEqualTo("TstDate: ");
            assertThat(area.substring(56, 66))
                    .as("'Mask used:' exactly fills its ten-character field")
                    .isEqualTo("Mask used:");
        }

        @Test
        @DisplayName("the mask is echoed intact; the date is not, because only WS-DATE is group-moved")
        void maskIsEchoedIntactButDateIsNot() {
            // WS-DATE-FMT is set once, at :L112-L113, and never overwritten, so the mask survives intact.
            // WS-DATE is set at :L107-L108 and then clobbered at :L122. The asymmetry between the two
            // adjacent fields is the clearest evidence that the corruption is the group move and not a
            // width mistake, so both are asserted together.
            String area = service.validate("20220610", DateValidationService.MASK_YYYYMMDD).result();

            assertThat(area.substring(66, 76))
                    .as("the mask field is untouched: eight characters padded to ten")
                    .isEqualTo("YYYYMMDD  ");
            assertThat(area.substring(45, 55))
                    .as("the date field carries the binary length bytes plus eight of ten characters")
                    .isEqualTo("\u0000\n" + "20220610".substring(0, 8));
            assertThat(area.charAt(46))
                    .as("the low-order length byte for a ten-character field is a line feed")
                    .isEqualTo('\n');
        }

        @Test
        @DisplayName("an absent date is space-filled rather than rejected, as a MOVE would leave it")
        void absentDateIsSpaceFilled() {
            DateValidationResult result = service.validate(null, DateValidationService.MASK_YYYY_MM_DD);

            assertThat(result.result()).hasSize(DateValidationService.LS_RESULT_LENGTH);
            assertThat(result.result().substring(45, 55))
                    .as("a space-filled date is group-moved too, so the length bytes still lead")
                    .isEqualTo("\u0000\n" + " ".repeat(8));
            assertThat(result.feedbackCode().messageNumber())
                    .as("a blank field is insufficient data, not a crash")
                    .isEqualTo(2507);
        }
    }

    @Nested
    @DisplayName("2. Severity and message number are rendered as zoned four-digit fields")
    class ZonedRendering {

        @Test
        @DisplayName("a valid date renders severity 0000 and message 0000")
        void validDateRendersZeros() {
            DateValidationResult result =
                    service.validate("2022-06-10", DateValidationService.MASK_YYYY_MM_DD);

            assertThat(result.result()).startsWith("0000");
            assertThat(result.result().substring(15, 19))
                    .as("the message-number field is also zero-filled on success")
                    .isEqualTo("0000");
        }

        @Test
        @DisplayName("a failing date renders severity 0003 and its four-digit message number")
        void failingDateRendersSeverityThree() {
            DateValidationResult result =
                    service.validate("2022-13-01", DateValidationService.MASK_YYYY_MM_DD);

            assertThat(result.result().substring(0, 4))
                    .as("WS-SEVERITY-N is PIC 9(4), so 3 renders as 0003")
                    .isEqualTo("0003");
            assertThat(result.result().substring(15, 19))
                    .as("invalid month is message 2517")
                    .isEqualTo("2517");
        }

        @Test
        @DisplayName("the severity is the value the legacy program moved into RETURN-CODE")
        void severityIsTheReturnCode() {
            // CSUTLDTC.cbl:L100 does MOVE WS-SEVERITY-N TO RETURN-CODE, so a caller's step return code is
            // the severity. Exposing the severity on the feedback code preserves that relationship.
            assertThat(service.validate("2022-06-10", DateValidationService.MASK_YYYY_MM_DD)
                    .feedbackCode().severity()).isZero();
            assertThat(service.validate("2022-13-01", DateValidationService.MASK_YYYY_MM_DD)
                    .feedbackCode().severity()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("3. The CEEDAYS feedback conditions, decoded from the copybook's hex tokens")
    class FeedbackConditions {

        @Test
        @DisplayName("FC_INVALID_DATE is the all-zeros token and means VALID, a preserved misnomer")
        void theMisnomerIsPreserved() {
            // 88 FC-INVALID-DATE VALUE X'0000000000000000' and the EVALUATE moves 'Date is valid'. The
            // name is wrong in the source and is kept wrong here on purpose.
            assertThat(FeedbackCondition.FC_INVALID_DATE.severity()).isZero();
            assertThat(FeedbackCondition.FC_INVALID_DATE.messageNumber()).isZero();
            assertThat(FeedbackCondition.FC_INVALID_DATE.resultText()).isEqualTo("Date is valid");
        }

        @ParameterizedTest(name = "{0} is severity 3, message {1}")
        @CsvSource({
            "FC_INSUFFICIENT_DATA, 2507", "FC_BAD_DATE_VALUE, 2508", "FC_INVALID_ERA, 2509",
            "FC_UNSUPP_RANGE, 2513", "FC_INVALID_MONTH, 2517", "FC_BAD_PIC_STRING, 2518",
            "FC_NON_NUMERIC_DATA, 2520", "FC_YEAR_IN_ERA_ZERO, 2521",
        })
        @DisplayName("every failing condition decodes to the halfwords of its hex token")
        void failingConditionsDecodeFromTheirTokens(final String name, final int messageNumber) {
            FeedbackCondition condition = FeedbackCondition.valueOf(name);

            assertThat(condition.severity())
                    .as("X'0003....' is the first halfword of every failing token")
                    .isEqualTo(3);
            assertThat(condition.messageNumber()).isEqualTo(messageNumber);
        }

        @Test
        @DisplayName("the message numbers are exactly the copybook's hex literals in declaration order")
        void messageNumbersMatchTheHexLiterals() {
            // X'09CB' X'09CC' X'09CD' X'09D1' X'09D5' X'09D6' X'09D8' X'09D9'
            assertThat(new int[] {0x09CB, 0x09CC, 0x09CD, 0x09D1, 0x09D5, 0x09D6, 0x09D8, 0x09D9})
                    .containsExactly(2507, 2508, 2509, 2513, 2517, 2518, 2520, 2521);
        }

        @Test
        @DisplayName("every result text fits the fifteen-character WS-RESULT field")
        void everyResultTextFitsTheField() {
            for (FeedbackCondition condition : FeedbackCondition.values()) {
                assertThat(condition.resultText())
                        .as("%s", condition)
                        .hasSizeLessThanOrEqualTo(DateValidationService.RESULT_TEXT_LENGTH);
            }
        }

        @Test
        @DisplayName("the unrecognised-token text is the EVALUATE's WHEN OTHER literal")
        void unrecognisedTextIsTheWhenOtherLiteral() {
            assertThat(DateValidationService.UNRECOGNISED_RESULT_TEXT).isEqualTo("Date is invalid");
        }
    }

    @Nested
    @DisplayName("4. validate() outcomes for each reachable branch")
    class ValidateOutcomes {

        @ParameterizedTest(name = "{0} with the separated mask is valid")
        @ValueSource(strings = {"2022-06-10", "2000-02-29", "1582-10-15", "9999-12-31", "2024-02-29"})
        @DisplayName("well-formed in-range dates are accepted, including leap days")
        void validDates(final String candidate) {
            assertThat(service.validate(candidate, DateValidationService.MASK_YYYY_MM_DD).feedbackCode()
                    .severity()).isZero();
        }

        @Test
        @DisplayName("both supported masks are accepted; anything else is a bad picture string")
        void maskHandling() {
            assertThat(service.validate("20220610", DateValidationService.MASK_YYYYMMDD).feedbackCode()
                    .messageNumber()).isZero();
            assertThat(service.validate("10/06/2022", "DD/MM/YYYY").feedbackCode().messageNumber())
                    .as("an unsupported mask is 2518, not an attempt to parse it anyway")
                    .isEqualTo(2518);
        }

        @ParameterizedTest(name = "{0} yields message {1}")
        @CsvSource({
            "2022-13-01, 2517",
            "2022-00-01, 2517",
            "2022-02-30, 2508",
            "2022-06-32, 2508",
            "0000-01-01, 2521",
            "1582-10-14, 2513",
            "1500-06-10, 2513",
            "2022-06-1a, 2520",
            "2022/06/10, 2520",
        })
        @DisplayName("each failing shape yields its own message number, not a generic rejection")
        void failingShapes(final String candidate, final int expected) {
            assertThat(service.validate(candidate, DateValidationService.MASK_YYYY_MM_DD).feedbackCode()
                    .messageNumber()).isEqualTo(expected);
        }

        @Test
        @DisplayName("a short or blank value is insufficient data rather than non-numeric")
        void insufficientData() {
            // The order of the checks matters: a blank field is reported as insufficient, and only a
            // present-but-wrong character reaches the numeric test. Reversing them would relabel every
            // empty screen field.
            assertThat(service.validate("", DateValidationService.MASK_YYYY_MM_DD).feedbackCode()
                    .messageNumber()).isEqualTo(2507);
            assertThat(service.validate("2022-06", DateValidationService.MASK_YYYY_MM_DD).feedbackCode()
                    .messageNumber()).isEqualTo(2507);
            assertThat(service.validate("          ", DateValidationService.MASK_YYYY_MM_DD).feedbackCode()
                    .messageNumber()).isEqualTo(2507);
        }

        @Test
        @DisplayName("the Lillian lower bound is exclusive of day zero and inclusive of the next day")
        void lillianBoundary() {
            assertThat(service.validate("1582-10-14", DateValidationService.MASK_YYYY_MM_DD).feedbackCode()
                    .messageNumber())
                    .as("Lillian day zero itself is out of range")
                    .isEqualTo(2513);
            assertThat(service.validate("1582-10-15", DateValidationService.MASK_YYYY_MM_DD).feedbackCode()
                    .severity())
                    .as("the day after is the first representable date")
                    .isZero();
        }

        @Test
        @DisplayName("a non-leap-year 29 February is a date-value error, not an invalid month")
        void nonLeapFebruary() {
            assertThat(service.validate("2023-02-29", DateValidationService.MASK_YYYY_MM_DD).feedbackCode()
                    .messageNumber()).isEqualTo(2508);
            assertThat(service.validate("1900-02-29", DateValidationService.MASK_YYYY_MM_DD).feedbackCode()
                    .messageNumber())
                    .as("1900 is not a leap year under the Gregorian century rule")
                    .isEqualTo(2508);
        }
    }

    @Nested
    @DisplayName("4b. The result record's offset accessors and its own guards")
    class ResultAccessors {

        @Test
        @DisplayName("the accessors read the same offsets the copybook layout dictates")
        void accessorsReadTheCopybookOffsets() {
            // These accessors are an independent statement of the same layout derived by hand in group 1:
            // severity at 0, message number at 15, result text at 20 for fifteen characters. Asserting
            // them against literal expectations - rather than against each other - is what makes the
            // agreement meaningful.
            DateValidationResult valid =
                    service.validate("2022-06-10", DateValidationService.MASK_YYYY_MM_DD);

            assertThat(valid.severityCode()).isEqualTo("0000");
            assertThat(valid.messageNumber()).isEqualTo("0000");
            assertThat(valid.resultText()).isEqualTo("Date is valid  ");
            assertThat(valid.returnCode()).isZero();
            assertThat(valid.valid()).isTrue();
        }

        @Test
        @DisplayName("a failing result exposes severity 0003 and its message number through the accessors")
        void failingResultAccessors() {
            DateValidationResult invalid =
                    service.validate("2022-13-01", DateValidationService.MASK_YYYY_MM_DD);

            assertThat(invalid.severityCode()).isEqualTo("0003");
            assertThat(invalid.messageNumber()).isEqualTo("2517");
            assertThat(invalid.resultText()).isEqualTo("Invalid month  ");
            assertThat(invalid.returnCode()).isEqualTo(3);
            assertThat(invalid.valid()).isFalse();
        }

        @Test
        @DisplayName("messageText is the trailing sixty-one characters of the area")
        void messageTextIsTheTrailingField() {
            DateValidationResult valid =
                    service.validate("2022-06-10", DateValidationService.MASK_YYYY_MM_DD);

            assertThat(valid.messageText())
                    .hasSize(DateValidationService.MESSAGE_TEXT_LENGTH)
                    .isEqualTo(valid.result()
                            .substring(DateValidationService.LS_RESULT_LENGTH
                                    - DateValidationService.MESSAGE_TEXT_LENGTH));
        }

        @Test
        @DisplayName("the record refuses a result area that is not exactly eighty characters")
        void recordRefusesAWrongWidthArea() {
            // The accessors index by absolute offset, so a short area would throw an obscure
            // StringIndexOutOfBounds far from the cause. Refusing at construction names the real problem.
            FeedbackCode code = FeedbackCode.of(FeedbackCondition.FC_INVALID_DATE);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new DateValidationResult(code, "too short"))
                    .withMessageContaining("exactly 80");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new DateValidationResult(code, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new DateValidationResult(null, " ".repeat(80)));
        }
    }

    @Nested
    @DisplayName("5. The composite CCYYMMDD edits from CSUTLDPY")
    class CompositeEdits {

        @Test
        @DisplayName("a well-formed date sets all three flags valid and reports no input error")
        void wellFormedCompositeDate() {
            EditOutcome outcome = service.editDate("20220610", "Test Date");

            assertThat(outcome.yearFlag()).isEqualTo(EditFlag.ISVALID);
            assertThat(outcome.monthFlag()).isEqualTo(EditFlag.ISVALID);
            assertThat(outcome.dayFlag()).isEqualTo(EditFlag.ISVALID);
            assertThat(outcome.inputError()).isFalse();
            assertThat(outcome.valid()).isTrue();
        }

        @Test
        @DisplayName("a blank date is BLANK rather than NOT_OK, which is a distinct legacy outcome")
        void blankIsNotTheSameAsInvalid() {
            // The copybook distinguishes an unsupplied field from a supplied-but-wrong one, and the screen
            // showed different messages for each. Collapsing the two would lose that.
            EditOutcome outcome = service.editDate("        ", "Test Date");

            assertThat(outcome.yearFlag()).isEqualTo(EditFlag.BLANK);
            assertThat(outcome.valid()).isFalse();
        }

        @Test
        @DisplayName("an invalid month is flagged on the month, and the message names the field")
        void invalidMonthIsFlaggedOnTheMonth() {
            EditOutcome outcome = service.editDate("20221310", "Test Date");

            assertThat(outcome.monthFlag()).isEqualTo(EditFlag.NOT_OK);
            assertThat(outcome.inputError()).isTrue();
            assertThat(outcome.returnMessage()).contains("Test Date");
        }

        @Test
        @DisplayName("the return message is never null, so a caller can always render it")
        void returnMessageIsNeverNull() {
            for (String candidate : new String[] {"20220610", "        ", "20221310", "00000000"}) {
                assertThat(service.editDate(candidate, "Test Date").returnMessage())
                        .as("candidate '%s'", candidate)
                        .isNotNull();
            }
        }
    }

    @Nested
    @DisplayName("6. The date-of-birth future test, on an injected clock")
    class DateOfBirthEdit {

        @Test
        @DisplayName("a past date of birth is accepted")
        void pastDateOfBirthIsAccepted() {
            EditOutcome outcome = service.editDateOfBirth("19800115", "Date of Birth");

            assertThat(outcome.valid()).isTrue();
            assertThat(outcome.inputError()).isFalse();
        }

        @Test
        @DisplayName("a future date of birth sets all three flags NOT_OK with the legacy message")
        void futureDateOfBirthIsRefused() {
            EditOutcome outcome = service.editDateOfBirth("20300115", "Date of Birth");

            assertThat(outcome.yearFlag()).isEqualTo(EditFlag.NOT_OK);
            assertThat(outcome.monthFlag()).isEqualTo(EditFlag.NOT_OK);
            assertThat(outcome.dayFlag()).isEqualTo(EditFlag.NOT_OK);
            assertThat(outcome.inputError()).isTrue();
            assertThat(outcome.returnMessage())
                    .as("the copybook's literal, trailing space included")
                    .contains("cannot be in the future");
        }

        @Test
        @DisplayName("TODAY is refused, because the source tests strict inequality")
        void todayIsRefused() {
            // CSUTLDPY.cpy:L354 continues only when current > underEdit, so equality falls through to the
            // error branch. A date of birth of today is therefore reported as being in the future. This is
            // the kind of boundary an ambient clock would make untestable; the injected clock pins it.
            EditOutcome outcome = service.editDateOfBirth("20220610", "Date of Birth");

            assertThat(outcome.valid()).isFalse();
            assertThat(outcome.returnMessage()).contains("cannot be in the future");
        }

        @Test
        @DisplayName("yesterday is accepted, which brackets the boundary from below")
        void yesterdayIsAccepted() {
            assertThat(service.editDateOfBirth("20220609", "Date of Birth").valid()).isTrue();
        }

        @Test
        @DisplayName("the outcome moves with the clock, proving no ambient time is read")
        void outcomeMovesWithTheClock() {
            // The same input flips outcome purely because the clock advanced past it. If the service read
            // ambient time this test would be either always-green or flaky depending on the run date.
            DateValidationService asOf2019 = new DateValidationService(
                    FixedClockProvider.fixedClock(Instant.parse("2019-01-01T00:00:00Z")));
            DateValidationService asOf2031 = new DateValidationService(
                    FixedClockProvider.fixedClock(Instant.parse("2031-01-01T00:00:00Z")));

            assertThat(asOf2019.editDateOfBirth("20300115", "Date of Birth").valid())
                    .as("in 2019 a 2030 date of birth is in the future")
                    .isFalse();
            assertThat(asOf2031.editDateOfBirth("20300115", "Date of Birth").valid())
                    .as("in 2031 the same date is in the past")
                    .isTrue();
        }

        @Test
        @DisplayName("a composite failure short-circuits before the future test is reached")
        void compositeFailureShortCircuits() {
            // editDateOfBirth returns the composite outcome unchanged when it is already invalid, so an
            // impossible date is reported as such rather than as a future date.
            EditOutcome outcome = service.editDateOfBirth("20221310", "Date of Birth");

            assertThat(outcome.valid()).isFalse();
            assertThat(outcome.returnMessage()).doesNotContain("cannot be in the future");
        }

        @Test
        @DisplayName("the clock the service was built with is the only time source")
        void clockIsTheOnlyTimeSource() {
            Clock canonical = FixedClockProvider.canonicalClock();

            assertThat(canonical.instant()).isEqualTo(NOW);
            assertThat(new DateValidationService(canonical)
                    .editDateOfBirth("20220610", "Date of Birth").valid()).isFalse();
        }
    }
}
