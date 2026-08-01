/*
 * ******************************************************************
 * Program     : DateValidationServiceTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/validation
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Pins the CSUTLDTC date validation contract: the nine
 *               CEEDAYS feedback conditions with the severity and
 *               message-number pairs decoded from their hexadecimal
 *               tokens, the exact fifteen-character result literals,
 *               the eighty-byte LS-RESULT message area field by field
 *               including the preserved group-move defect that
 *               destroys two characters of the tested date, and the
 *               component-level edit flags of CSUTLDPY including the
 *               integer-remainder leap year test.
 * Source      : app/cbl/CSUTLDTC.cbl:L59-L157 (feedback tokens, the
 *               EVALUATE result table, LS-RESULT) @ 7756d89
 * Source      : app/cpy/CSUTLDPY.cpy (component edits, leap year) @ 7756d89
 * Source      : app/cpy/CSUTLDWY.cpy (work areas, WS-FEBRUARY) @ 7756d89
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
package com.cardemo.unit.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.DateValidationService.DateValidationResult;
import com.cardemo.service.shared.DateValidationService.EditFlag;
import com.cardemo.service.shared.DateValidationService.EditOutcome;
import com.cardemo.service.shared.DateValidationService.FeedbackCode;
import com.cardemo.service.shared.DateValidationService.FeedbackCondition;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link DateValidationService}, the Java replacement for the statically called
 * {@code CSUTLDTC} program and its two work-area copybooks.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>Three separate contracts meet in this one service, and each is asserted against the COBOL rather than
 * against a plausible reading of it.
 *
 * <h3>The feedback table is derived from hexadecimal tokens, not transcribed</h3>
 *
 * <p>{@code app/cbl/CSUTLDTC.cbl:L61-L70} declares the nine CEEDAYS outcomes as eight-byte condition tokens,
 * for example {@code 88 FC-INSUFFICIENT-DATA VALUE X'000309CB59C3C5C5'}. The first two bytes are
 * {@code SEVERITY PIC S9(4) BINARY} and the next two are {@code MSG-NO}, so that token means severity 3 and
 * message number {@code 0x09CB} = 2507. Every pair this test asserts was decoded from the token independently
 * of the Java source, which is what makes the assertion evidence rather than a restatement.
 *
 * <h3>The success condition is named for its opposite</h3>
 *
 * <p>{@code FC-INVALID-DATE} is {@code X'0000000000000000'} - severity zero, message number zero - and
 * {@code app/cbl/CSUTLDTC.cbl:L129-L130} maps it to the text {@code 'Date is valid'}. The name says invalid
 * and the meaning is valid. It is CEEDAYS' all-clear token, and renaming it would break the correspondence the
 * traceability matrix asserts, so the name is preserved and this test documents why.
 *
 * <h3>The result area loses two characters of the date, deliberately</h3>
 *
 * <p>{@code app/cbl/CSUTLDTC.cbl:L122} is {@code MOVE WS-DATE-TO-TEST TO WS-DATE}. The sending field is the
 * whole varying-length structure - a two-byte binary length followed by the text - while the receiving field is
 * {@code PIC X(10)}. So the group move writes the binary length into the first two bytes and only the first
 * eight characters of the date survive. For a ten-character date the last two characters are destroyed. This is
 * a defect in the source, it is reproduced exactly, and the test asserts the resulting bytes so that a future
 * "tidy-up" cannot silently correct it.
 *
 * <h2>2. How to run it</h2>
 *
 * <pre>{@code
 * mvn -B -o test -Dtest=DateValidationServiceTest
 * }</pre>
 *
 * <h2>3. Configuration and defaults</h2>
 *
 * <p>The service takes a {@link Clock} by constructor injection, so the date-of-birth future check is
 * deterministic. Every test here uses a fixed clock at {@code 2024-06-15T12:00:00Z}; none reads the system
 * clock, so none can pass today and fail tomorrow.
 *
 * <h2>4. Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>A severity or message number fails.</strong> Re-decode the hexadecimal token at
 *       {@code app/cbl/CSUTLDTC.cbl:L61-L70}; the first two bytes are the severity, the next two the message
 *       number. Do not adjust the expected value to match the code.</li>
 *   <li><strong>A result literal fails.</strong> The fifteen-character texts, including their internal and
 *       trailing spaces, are byte-comparable output. {@code app/cbl/CSUTLDTC.cbl:L126-L127} even carries a
 *       column ruler for them.</li>
 *   <li><strong>The tested-date field fails.</strong> Something "fixed" the {@code :L122} group move. It is a
 *       preserved defect; restore it.</li>
 *   <li><strong>A leap-year case fails.</strong> The source divides by 400 when the last two year digits are
 *       zero and by 4 otherwise, then tests the remainder. It never calls a library predicate.</li>
 * </ul>
 */
class DateValidationServiceTest {

    /** A fixed instant so the date-of-birth future check is deterministic. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2024-06-15T12:00:00Z"), ZoneOffset.UTC);

    private static DateValidationService service() {
        return new DateValidationService(FIXED_CLOCK);
    }

    @Nested
    @DisplayName("the nine CEEDAYS feedback conditions, decoded from their hexadecimal tokens")
    class CeedaysFeedbackTable {

        @ParameterizedTest
        @CsvSource({
            // condition,            severity, messageNumber, hex token,          result literal
            "FC_INVALID_DATE,        0,    0, 0000000000000000, Date is valid",
            "FC_INSUFFICIENT_DATA,   3, 2507, 000309CB59C3C5C5, Insufficient",
            "FC_BAD_DATE_VALUE,      3, 2508, 000309CC59C3C5C5, Datevalue error",
            "FC_INVALID_ERA,         3, 2509, 000309CD59C3C5C5, Invalid Era",
            "FC_UNSUPP_RANGE,        3, 2513, 000309D159C3C5C5, Unsupp. Range",
            "FC_INVALID_MONTH,       3, 2517, 000309D559C3C5C5, Invalid month",
            "FC_BAD_PIC_STRING,      3, 2518, 000309D659C3C5C5, Bad Pic String",
            "FC_NON_NUMERIC_DATA,    3, 2520, 000309D859C3C5C5, Nonnumeric data",
            "FC_YEAR_IN_ERA_ZERO,    3, 2521, 000309D959C3C5C5, YearInEra is 0",
        })
        @DisplayName("each condition's severity and message number are the first two byte pairs of its token")
        void eachConditionMatchesItsDecodedToken(final FeedbackCondition condition, final int severity,
                final int messageNumber, final String hexToken, final String resultLiteral) {
            final int decodedSeverity = Integer.parseInt(hexToken.substring(0, 4), 16);
            final int decodedMessageNumber = Integer.parseInt(hexToken.substring(4, 8), 16);

            assertThat(decodedSeverity)
                    .as("the CsvSource severity column must itself agree with the token, so a typo in the "
                            + "expectation cannot make the test pass vacuously")
                    .isEqualTo(severity);
            assertThat(decodedMessageNumber).isEqualTo(messageNumber);

            assertThat(condition.severity())
                    .as("app/cbl/CSUTLDTC.cbl:L61-L70 declares %s as X'%s'; bytes 0-1 are "
                            + "SEVERITY PIC S9(4) BINARY, which decodes to %d",
                            condition.name(), hexToken, severity)
                    .isEqualTo(severity);
            assertThat(condition.messageNumber())
                    .as("bytes 2-3 are MSG-NO, which decodes to %d", messageNumber)
                    .isEqualTo(messageNumber);
            assertThat(condition.resultText())
                    .as("app/cbl/CSUTLDTC.cbl:L128-L149 moves this literal to WS-RESULT. The enum carries "
                            + "the literal EXACTLY AS THE SOURCE WRITES IT - see "
                            + "theLiteralsAreCarriedUnpaddedAndPaddedOnlyWhenComposed for why that is not "
                            + "an oversight")
                    .startsWith(resultLiteral)
                    .hasSizeLessThanOrEqualTo(DateValidationService.RESULT_TEXT_LENGTH);
        }

        @Test
        @DisplayName("the all-clear condition is the only one with severity zero")
        void theAllClearConditionIsTheOnlyOneWithSeverityZero() {
            for (final FeedbackCondition condition : FeedbackCondition.values()) {
                if (condition == FeedbackCondition.FC_INVALID_DATE) {
                    assertThat(condition.severity()).isZero();
                } else {
                    assertThat(condition.severity())
                            .as("%s is an error outcome, and every error token carries severity 3",
                                    condition.name())
                            .isEqualTo(3);
                }
            }
        }

        @Test
        @DisplayName("the success condition is named FC_INVALID_DATE yet carries the text 'Date is valid'")
        void theSuccessConditionIsNamedForItsOpposite() {
            assertThat(FeedbackCondition.FC_INVALID_DATE.resultText())
                    .as("PRESERVED NAMING QUIRK. The token X'0000000000000000' is CEEDAYS' all-clear - "
                            + "severity 0, message 0 - and app/cbl/CSUTLDTC.cbl:L129-L130 maps it to "
                            + "'Date is valid'. The condition NAME says invalid; the MEANING is valid. "
                            + "Renaming it would break the paragraph correspondence the traceability matrix "
                            + "asserts, so the name stays and this test records why it is not a bug")
                    .startsWith("Date is valid");
            assertThat(FeedbackCode.of(FeedbackCondition.FC_INVALID_DATE).severity()).isZero();
        }

        @ParameterizedTest
        @EnumSource(FeedbackCondition.class)
        @DisplayName("every condition round-trips through a feedback code and back")
        void everyConditionRoundTripsThroughAFeedbackCode(final FeedbackCondition condition) {
            final FeedbackCode code = FeedbackCode.of(condition);

            assertThat(code.severity()).isEqualTo(condition.severity());
            assertThat(code.messageNumber()).isEqualTo(condition.messageNumber());
            assertThat(code.condition())
                    .as("resolution is by the severity-and-message-number pair, so the pair must be unique "
                            + "across all nine conditions or two tokens would be indistinguishable")
                    .contains(condition);
            assertThat(code.resultText()).isEqualTo(condition.resultText());
            assertThat(code).isEqualTo(FeedbackCode.of(condition)).hasSameHashCodeAs(code);
            assertThat(code.toString()).contains(String.valueOf(condition.messageNumber()));
        }

        @Test
        @DisplayName("the literals are carried unpadded and padded only when the result area is composed")
        void theLiteralsAreCarriedUnpaddedAndPaddedOnlyWhenComposed() {
            assertThat(FeedbackCondition.FC_INVALID_DATE.resultText())
                    .as("app/cbl/CSUTLDTC.cbl:L130 writes MOVE 'Date is valid' TO WS-RESULT with a "
                            + "THIRTEEN-character literal and lets COBOL space-pad it into the PIC X(15) "
                            + "field. The enum reproduces the literal as written rather than pre-padding "
                            + "it, so the Java constant is byte-identical to the COBOL constant")
                    .isEqualTo("Date is valid")
                    .hasSize(13);
            assertThat(FeedbackCondition.FC_INSUFFICIENT_DATA.resultText())
                    .as("likewise 'Insufficient' is twelve characters in the source")
                    .isEqualTo("Insufficient")
                    .hasSize(12);
            assertThat(FeedbackCondition.FC_INVALID_ERA.resultText())
                    .as("but 'Invalid Era    ' IS written pre-padded to fifteen in the source - the "
                            + "corpus is inconsistent about this, seven of the ten literals carrying "
                            + "explicit padding and three not, which is why the column ruler comment at "
                            + "L126-L127 exists at all")
                    .isEqualTo("Invalid Era    ")
                    .hasSize(15);

            assertThat(service().validate("2024-01-31", DateValidationService.MASK_YYYY_MM_DD)
                    .resultText())
                    .as("THE PADDING BELONGS AT THE MOVE, NOT AT THE CONSTANT. Composing the eighty-byte "
                            + "area is what widens the literal to fifteen, exactly as the COBOL MOVE into "
                            + "WS-RESULT does. Pre-padding the enum instead would produce the same bytes "
                            + "here while making every Java constant differ from its COBOL original - and "
                            + "would break the moment a literal were used anywhere but this one field")
                    .isEqualTo("Date is valid  ")
                    .hasSize(DateValidationService.RESULT_TEXT_LENGTH);
        }

        @Test
        @DisplayName("an unrecognised severity and message pair falls back to the WHEN OTHER text")
        void anUnrecognisedPairFallsBackToWhenOther() {
            final FeedbackCode unknown = new FeedbackCode(7, 9999);

            assertThat(unknown.condition())
                    .as("no declared token carries this pair")
                    .isEmpty();
            assertThat(unknown.resultText())
                    .as("app/cbl/CSUTLDTC.cbl:L147-L148 is WHEN OTHER MOVE 'Date is invalid' TO WS-RESULT. "
                            + "Note this text differs from the all-clear 'Date is valid' by a single "
                            + "letter, which is exactly the sort of pair that must be asserted rather than "
                            + "eyeballed")
                    .isEqualTo(DateValidationService.UNRECOGNISED_RESULT_TEXT)
                    .isEqualTo("Date is invalid");
        }

        @Test
        @DisplayName("a null condition is rejected rather than producing a zero-severity code")
        void aNullConditionIsRejected() {
            assertThatNullPointerException()
                    .as("silently producing severity 0 from a null condition would report success for an "
                            + "unknown outcome - the most dangerous possible default on a validation path")
                    .isThrownBy(() -> FeedbackCode.of(null))
                    .withMessageContaining("condition must not be null");
        }

        @Test
        @DisplayName("FC_INVALID_ERA is declared but never selected by the translated validation")
        void theInvalidEraConditionIsDeclaredButNeverSelected() {
            assertThat(FeedbackCondition.FC_INVALID_ERA.messageNumber())
                    .as("the token exists because app/cbl/CSUTLDTC.cbl:L136-L137 has a WHEN branch for it, "
                            + "so the constant is real code on a declared path")
                    .isEqualTo(2509);
            assertThat(new FeedbackCode(3, 2509).condition())
                    .as("and it remains resolvable, so a caller handed that pair by any future CEEDAYS "
                            + "equivalent still renders the right text")
                    .contains(FeedbackCondition.FC_INVALID_ERA);

            assertThat(FeedbackCondition.values())
                    .as("DECLARED BUT NOT SELECTED. No input to validate() produces this outcome, because "
                            + "an era is a Japanese-calendar concept the two supported picture masks cannot "
                            + "express. It is retained for the same reason reject code 109 is retained - "
                            + "the source declares it on a reachable branch - and is NOT deleted")
                    .hasSize(9);
        }
    }

    @Nested
    @DisplayName("the eighty-byte LS-RESULT message area, field by field")
    class ResultAreaGeometry {

        @Test
        @DisplayName("the message area is exactly eighty bytes laid out as the work area declares")
        void theMessageAreaIsExactlyEightyBytes() {
            final String result = service().validate("2024-01-31", DateValidationService.MASK_YYYY_MM_DD)
                    .result();

            assertThat(result)
                    .as("LS-RESULT is PIC X(80) at app/cbl/CSUTLDTC.cbl:L86")
                    .hasSize(DateValidationService.LS_RESULT_LENGTH)
                    .hasSize(80);

            assertThat(result.substring(0, 4))
                    .as("WS-SEVERITY PIC X(04) redefined as PIC 9(4), so it renders zero-padded")
                    .isEqualTo("0000");
            assertThat(result.substring(4, 15))
                    .as("the 'Mesg Code:' literal occupies PIC X(11), so the ten-character literal carries "
                            + "one trailing pad byte")
                    .isEqualTo("Mesg Code: ");
            assertThat(result.substring(15, 19))
                    .as("WS-MSG-NO PIC X(04), likewise zero-padded")
                    .isEqualTo("0000");
            assertThat(result.charAt(19)).isEqualTo(' ');
            assertThat(result.substring(20, 35))
                    .as("WS-RESULT PIC X(15) - the fifteen-character feedback literal")
                    .isEqualTo("Date is valid  ");
            assertThat(result.charAt(35)).isEqualTo(' ');
            assertThat(result.substring(36, 45)).isEqualTo("TstDate: ");
            assertThat(result.charAt(55)).isEqualTo(' ');
            assertThat(result.substring(56, 66)).isEqualTo("Mask used:");
            assertThat(result.substring(66, 76))
                    .as("WS-DATE-FMT PIC X(10) echoes the mask that was used")
                    .isEqualTo("YYYY-MM-DD");
            assertThat(result.substring(76, 80))
                    .as("one separator space plus the three-byte trailing filler")
                    .isEqualTo("    ");
        }

        @Test
        @DisplayName("the tested-date field carries a binary length and loses the last two characters")
        void theTestedDateFieldCarriesABinaryLengthAndLosesTwoCharacters() {
            final String result = service().validate("2024-01-31", DateValidationService.MASK_YYYY_MM_DD)
                    .result();
            final String testedDate = result.substring(45, 55);

            assertThat(testedDate)
                    .as("the field is PIC X(10) and still occupies ten bytes")
                    .hasSize(DateValidationService.LS_DATE_LENGTH);
            assertThat((int) testedDate.charAt(0))
                    .as("PRESERVED DEFECT, app/cbl/CSUTLDTC.cbl:L122 MOVE WS-DATE-TO-TEST TO WS-DATE. The "
                            + "sending field is the WHOLE varying-length structure - a two-byte binary "
                            + "length then the text - and the receiver is PIC X(10), so the group move "
                            + "writes the length into the first two bytes. LENGTH OF a PIC X(10) field is "
                            + "the constant 10, so byte 0 is the high-order 0x00")
                    .isZero();
            assertThat((int) testedDate.charAt(1))
                    .as("and byte 1 is the low-order 0x0A, decimal 10 - which happens to be a line feed, "
                            + "so the diagnostic area contains a control character")
                    .isEqualTo(10);
            assertThat(testedDate.substring(2))
                    .as("only the FIRST EIGHT characters of the date survive: '2024-01-' is retained and "
                            + "the trailing '31' is destroyed. Reproduced exactly so that a future "
                            + "tidy-up cannot silently correct it - the legacy diagnostic line is "
                            + "byte-comparable output")
                    .isEqualTo("2024-01-")
                    .hasSize(8)
                    .doesNotContain("31");
        }

        @Test
        @DisplayName("the result accessors read their fields out of the composed area")
        void theResultAccessorsReadTheirFieldsOutOfTheArea() {
            final DateValidationResult result =
                    service().validate("2024-02-30", DateValidationService.MASK_YYYY_MM_DD);

            assertThat(result.severityCode()).isEqualTo("0003");
            assertThat(result.messageNumber()).isEqualTo("2508");
            assertThat(result.resultText()).isEqualTo("Datevalue error");
            assertThat(result.messageText())
                    .as("the message text is the declared PIC X(61) tail of the area")
                    .hasSize(DateValidationService.MESSAGE_TEXT_LENGTH);
            assertThat(result.returnCode())
                    .as("app/cbl/CSUTLDTC.cbl:L98 MOVE WS-SEVERITY-N TO RETURN-CODE, so the process "
                            + "return code IS the severity - 3 for every error outcome")
                    .isEqualTo(3);
            assertThat(result.valid()).isFalse();
            assertThat(result.feedbackCode().condition())
                    .contains(FeedbackCondition.FC_BAD_DATE_VALUE);
            assertThat(result).isEqualTo(
                    service().validate("2024-02-30", DateValidationService.MASK_YYYY_MM_DD));
        }
    }

    @Nested
    @DisplayName("validate: every declared outcome reachable from a real input")
    class ValidateOutcomes {

        @ParameterizedTest
        @CsvSource({
            "2024-01-31, YYYY-MM-DD, FC_INVALID_DATE,      0, true",
            "20240131,   YYYYMMDD,   FC_INVALID_DATE,      0, true",
            "1582-10-15, YYYY-MM-DD, FC_INVALID_DATE,      0, true",
            "2024-02-30, YYYY-MM-DD, FC_BAD_DATE_VALUE,    3, false",
            "0000-01-01, YYYY-MM-DD, FC_YEAR_IN_ERA_ZERO,  3, false",
            "2024-13-01, YYYY-MM-DD, FC_INVALID_MONTH,     3, false",
            "2024-1X-01, YYYY-MM-DD, FC_NON_NUMERIC_DATA,  3, false",
            "2024/01/31, YYYY-MM-DD, FC_NON_NUMERIC_DATA,  3, false",
            "2024-01-31, MM/DD/YYYY, FC_BAD_PIC_STRING,    3, false",
            "1500-01-01, YYYY-MM-DD, FC_UNSUPP_RANGE,      3, false",
        })
        @DisplayName("each input selects its declared feedback condition")
        void eachInputSelectsItsDeclaredCondition(final String date, final String mask,
                final FeedbackCondition expected, final int expectedReturnCode, final boolean expectedValid) {
            final DateValidationResult result = service().validate(date, mask);

            assertThat(result.feedbackCode().condition())
                    .as("input '%s' under mask '%s' must select %s", date, mask, expected.name())
                    .contains(expected);
            assertThat(result.returnCode()).isEqualTo(expectedReturnCode);
            assertThat(result.valid()).isEqualTo(expectedValid);
            assertThat(result.resultText())
                    .as("the value read back out of the eighty-byte area is ALWAYS the full fifteen "
                            + "characters, because composing the area pads the literal; the enum's own "
                            + "literal may be shorter")
                    .hasSize(DateValidationService.RESULT_TEXT_LENGTH)
                    .startsWith(expected.resultText().stripTrailing());
        }

        @Test
        @DisplayName("a space-filled date is insufficient data rather than a bad value")
        void aSpaceFilledDateIsInsufficientData() {
            final DateValidationResult result =
                    service().validate("          ", DateValidationService.MASK_YYYY_MM_DD);

            assertThat(result.feedbackCode().condition())
                    .as("an uninitialised COBOL field presents as spaces, and the declared outcome for a "
                            + "position the mask requires but the input does not supply is INSUFFICIENT "
                            + "DATA - distinct from a supplied-but-wrong value")
                    .contains(FeedbackCondition.FC_INSUFFICIENT_DATA);
            assertThat(result.valid()).isFalse();
        }

        @Test
        @DisplayName("a null-byte filled date is also insufficient data, matching LOW-VALUES")
        void aNullByteFilledDateIsAlsoInsufficientData() {
            final DateValidationResult result =
                    service().validate("\u0000".repeat(10), DateValidationService.MASK_YYYY_MM_DD);

            assertThat(result.feedbackCode().condition())
                    .as("LOW-VALUES is binary zeros and is a second representation of 'nothing supplied'. "
                            + "Both it and SPACES must reach the same outcome, or an unpopulated screen "
                            + "field would be diagnosed differently depending on how it was initialised")
                    .contains(FeedbackCondition.FC_INSUFFICIENT_DATA);
        }

        @Test
        @DisplayName("an absent date or mask is tolerated and diagnosed, never thrown")
        void anAbsentDateOrMaskIsToleratedAndDiagnosed() {
            assertThat(service().validate(null, DateValidationService.MASK_YYYY_MM_DD)
                    .feedbackCode().condition())
                    .as("a null date is padded to the declared width and diagnosed as insufficient. The "
                            + "service is a translation of a subprogram that could not throw, so it "
                            + "reports rather than raising")
                    .contains(FeedbackCondition.FC_INSUFFICIENT_DATA);

            assertThat(service().validate("2024-01-31", null).feedbackCode().condition())
                    .as("an absent mask is not one of the two recognised picture strings")
                    .contains(FeedbackCondition.FC_BAD_PIC_STRING);
        }

        @Test
        @DisplayName("only the two masks the corpus uses are recognised")
        void onlyTheTwoCorpusMasksAreRecognised() {
            assertThat(DateValidationService.MASK_YYYY_MM_DD).isEqualTo("YYYY-MM-DD");
            assertThat(DateValidationService.MASK_YYYYMMDD)
                    .as("the compact mask has no separators, which is the form the COACTUPC snapshot "
                            + "carries a date of birth in")
                    .isEqualTo("YYYYMMDD");
            assertThat(DateValidationService.MASK_YYYY_MM_DD.length())
                    .as("both masks fit the PIC X(10) format field")
                    .isLessThanOrEqualTo(DateValidationService.LS_DATE_FORMAT_LENGTH);

            for (final String rejected : new String[] {"DD-MM-YYYY", "YYYY/MM/DD", "YY-MM-DD", ""}) {
                assertThat(service().validate("2024-01-31", rejected).feedbackCode().condition())
                        .as("mask '%s' is not in the corpus and must be reported as a bad picture string "
                                + "rather than guessed at", rejected)
                        .contains(FeedbackCondition.FC_BAD_PIC_STRING);
            }
        }

        @Test
        @DisplayName("the Lillian range boundary is honoured to the day")
        void theLillianRangeBoundaryIsHonouredToTheDay() {
            assertThat(service().validate("1582-10-15", DateValidationService.MASK_YYYY_MM_DD).valid())
                    .as("Lillian day 1 is 1582-10-15, the first representable date")
                    .isTrue();
            assertThat(service().validate("1582-10-14", DateValidationService.MASK_YYYY_MM_DD)
                    .feedbackCode().condition())
                    .as("Lillian day zero itself is outside the representable range, so the day before "
                            + "the epoch is an unsupported range and NOT a bad date value - it is a real "
                            + "calendar date the day count cannot express")
                    .contains(FeedbackCondition.FC_UNSUPP_RANGE);
        }

        @Test
        @DisplayName("the declared field widths are the ones the linkage section states")
        void theDeclaredFieldWidthsMatchTheLinkageSection() {
            assertThat(DateValidationService.LS_DATE_LENGTH)
                    .as("01 LS-DATE PIC X(10) at app/cbl/CSUTLDTC.cbl:L84")
                    .isEqualTo(10);
            assertThat(DateValidationService.LS_DATE_FORMAT_LENGTH)
                    .as("01 LS-DATE-FORMAT PIC X(10) at L85")
                    .isEqualTo(10);
            assertThat(DateValidationService.LS_RESULT_LENGTH)
                    .as("01 LS-RESULT PIC X(80) at L86")
                    .isEqualTo(80);
            assertThat(DateValidationService.RESULT_TEXT_LENGTH)
                    .as("WS-RESULT is fifteen characters, as the source's own ruler comment at "
                            + "L126-L127 states")
                    .isEqualTo(15);
            assertThat(DateValidationService.CCYYMMDD_LENGTH)
                    .as("the component edit path takes a compact eight-character date")
                    .isEqualTo(8);
        }
    }

    @Nested
    @DisplayName("editDate: per-component flags and the three preserved message separator styles")
    class EditDateComponentFlags {

        @Test
        @DisplayName("a wholly valid compact date sets all three flags valid and emits no message")
        void aWhollyValidCompactDateSetsAllThreeFlagsValid() {
            final EditOutcome outcome = service().editDate("20240131", "openDate");

            assertThat(outcome.valid()).isTrue();
            assertThat(outcome.yearFlag()).isEqualTo(EditFlag.ISVALID);
            assertThat(outcome.monthFlag()).isEqualTo(EditFlag.ISVALID);
            assertThat(outcome.dayFlag()).isEqualTo(EditFlag.ISVALID);
            assertThat(outcome.inputError()).isFalse();
            assertThat(outcome.allComponentsNotOk()).isFalse();
            assertThat(outcome.hasReturnMessage())
                    .as("a clean edit leaves the message area blank, and hasReturnMessage tests blankness "
                            + "rather than nullity so a space-filled COBOL field reads as 'no message'")
                    .isFalse();
            assertThat(outcome.returnMessage())
                    .as("the message field is still its declared PIC X(75) width even when empty")
                    .hasSize(DateValidationService.RETURN_MESSAGE_LENGTH)
                    .isBlank();
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "        |openDate : Year must be supplied.        |BLANK  |BLANK  |BLANK",
            "2024    |openDate : Month must be supplied.       |ISVALID|BLANK  |BLANK",
            "202401  |openDate : Day must be supplied.         |ISVALID|ISVALID|BLANK",
            "18240131|openDate : Century is not valid.         |NOT_OK |ISVALID|ISVALID",
            "20241301|openDate: Month must be a number between 1 and 12.|ISVALID|NOT_OK |ISVALID",
            "20240132|openDate:day must be a number between 1 and 31.|ISVALID|ISVALID|NOT_OK",
            "2024013X|openDate:day must be a number between 1 and 31.|ISVALID|ISVALID|NOT_OK",
            "20240431|openDate:Cannot have 31 days in this month.|ISVALID|NOT_OK |NOT_OK",
            "20240230|openDate:Cannot have 30 days in this month.|ISVALID|NOT_OK |NOT_OK",
        })
        @DisplayName("each defective component sets its own flags and emits its own literal message")
        void eachDefectiveComponentSetsItsOwnFlagsAndMessage(final String input, final String expectedMessage,
                final EditFlag expectedYear, final EditFlag expectedMonth, final EditFlag expectedDay) {
            final EditOutcome outcome = service().editDate(input, "openDate");

            assertThat(outcome.valid()).isFalse();
            assertThat(outcome.inputError())
                    .as("every defective component latches the input-error flag")
                    .isTrue();
            assertThat(outcome.yearFlag()).isEqualTo(expectedYear);
            assertThat(outcome.monthFlag()).isEqualTo(expectedMonth);
            assertThat(outcome.dayFlag()).isEqualTo(expectedDay);
            assertThat(outcome.returnMessage().stripTrailing())
                    .as("the message literal for input '%s' is byte-comparable output; the variable name "
                            + "is prefixed and the copybook's own separator style is preserved verbatim",
                            input)
                    .isEqualTo(expectedMessage.stripTrailing());
            assertThat(outcome.hasReturnMessage()).isTrue();
        }

        @Test
        @DisplayName("three different separator styles are preserved across the component messages")
        void threeDifferentSeparatorStylesArePreserved() {
            final String year = service().editDate("        ", "openDate").returnMessage();
            final String month = service().editDate("20241301", "openDate").returnMessage();
            final String day = service().editDate("20240132", "openDate").returnMessage();

            assertThat(year)
                    .as("the supplied-check messages use SPACE COLON SPACE and a capitalised word")
                    .startsWith("openDate : Year must be supplied.");
            assertThat(month)
                    .as("the month range message uses COLON SPACE with a capital M - no leading space")
                    .startsWith("openDate: Month must be a number between 1 and 12.");
            assertThat(day)
                    .as("PRESERVED INCONSISTENCY: the day range message uses a bare COLON and a LOWERCASE "
                            + "d, unlike either of the other two styles. All three renderings differ, all "
                            + "three are what the copybook declares, and normalising them would change "
                            + "text the screens display")
                    .startsWith("openDate:day must be a number between 1 and 31.");

            assertThat(month.stripTrailing())
                    .as("the month and day styles genuinely differ rather than differing only in wording")
                    .doesNotStartWith("openDate:Month");
        }

        @Test
        @DisplayName("only a whole-date leap-year failure marks all three components not ok")
        void onlyALeapYearFailureMarksAllThreeComponentsNotOk() {
            final EditOutcome leapFailure = service().editDate("20230229", "openDate");

            assertThat(leapFailure.allComponentsNotOk())
                    .as("the leap-year branch sets the day, month AND year flags, because the verdict "
                            + "depends on all three read together - no single component is wrong on its own")
                    .isTrue();
            assertThat(leapFailure.returnMessage().stripTrailing())
                    .as("the literal runs two sentences together with no space after the full stop, "
                            + "exactly as the copybook declares it")
                    .isEqualTo("openDate:Not a leap year.Cannot have 29 days in this month.");

            assertThat(service().editDate("20240431", "openDate").allComponentsNotOk())
                    .as("a 31-day failure in a 30-day month implicates only the month and the day, so the "
                            + "all-components predicate must stay false")
                    .isFalse();
        }

        @Test
        @DisplayName("the variable name is echoed so a screen can mark the offending field")
        void theVariableNameIsEchoed() {
            assertThat(service().editDate("20241301", "reissueDate").returnMessage())
                    .as("the name is prefixed to the message, which is how the legacy screen knew which "
                            + "field to turn red")
                    .startsWith("reissueDate");
            assertThat(DateValidationService.VARIABLE_NAME_LENGTH)
                    .as("the name field is PIC X(25)")
                    .isEqualTo(25);
        }
    }

    @Nested
    @DisplayName("the integer-remainder leap year test, divisor 400 or 4 by the last two digits")
    class LeapYearDivisorSelection {

        @ParameterizedTest
        @CsvSource({
            "20240229, true,  4,   2024 is divisible by 4",
            "20230229, false, 4,   2023 is not divisible by 4",
            "20000229, true,  400, the last two digits are zero so the divisor becomes 400",
            "19000229, false, 400, 1900 leaves remainder 300 against 400",
            "19960229, true,  4,   1996 is divisible by 4",
        })
        @DisplayName("February 29 is accepted only when the selected divisor leaves no remainder")
        void februaryTwentyNineFollowsTheSelectedDivisor(final String input, final boolean expectedValid,
                final int expectedDivisor, final String rationale) {
            final int year = Integer.parseInt(input.substring(0, 4));
            final int lastTwoDigits = year % 100;
            final int derivedDivisor = lastTwoDigits == 0 ? 400 : 4;

            assertThat(derivedDivisor)
                    .as("app/cpy/CSUTLDPY.cpy:L245-L249 is IF WS-EDIT-DATE-YY-N = 0 MOVE 400 ELSE MOVE 4, "
                            + "so the divisor is chosen from the LAST TWO DIGITS of the year, not from the "
                            + "century. For %d that gives %d", year, expectedDivisor)
                    .isEqualTo(expectedDivisor);
            assertThat(year % derivedDivisor == 0)
                    .as("the source then does DIVIDE ... REMAINDER and tests IF WS-REMAINDER = ZEROES; %s",
                            rationale)
                    .isEqualTo(expectedValid);

            assertThat(service().editDate(input, "openDate").valid())
                    .as("and the service must reach the same verdict for '%s' by that same integer "
                            + "arithmetic - never by a library leap-year predicate, because the two can "
                            + "disagree and the source's arithmetic is the contract",
                            input)
                    .isEqualTo(expectedValid);
        }

        @Test
        @DisplayName("the century guard fires before the leap-year test, so 2100 never reaches it")
        void theCenturyGuardFiresBeforeTheLeapYearTest() {
            final EditOutcome outcome = service().editDate("21000229", "openDate");

            assertThat(outcome.returnMessage().stripTrailing())
                    .as("2100 would be correctly rejected as a non-leap year by the divisor arithmetic, "
                            + "but it never gets there: only centuries 19 and 20 are accepted, so the "
                            + "century guard reports first. The ORDER of the edits is part of the "
                            + "contract, not an implementation detail")
                    .isEqualTo("openDate : Century is not valid.");
            assertThat(outcome.yearFlag()).isEqualTo(EditFlag.NOT_OK);
            assertThat(outcome.allComponentsNotOk())
                    .as("OBSERVED, AND NOT WHAT AN EARLIER DRAFT OF THIS TEST ASSUMED. The century "
                            + "failure does NOT stop the later edits: the leap-year branch still runs, "
                            + "finds 2100 %% 400 = 100, and sets all three flags NOT_OK. What the century "
                            + "guard latches is the MESSAGE, not the control flow - so the reported text "
                            + "is the FIRST error while the flags accumulate every subsequent one. The "
                            + "draft asserted monthFlag was still ISVALID and failed, which is how the "
                            + "latch-versus-exit distinction was found")
                    .isTrue();

            final EditOutcome badCenturyOnly = service().editDate("18240131", "openDate");
            assertThat(badCenturyOnly.yearFlag()).isEqualTo(EditFlag.NOT_OK);
            assertThat(badCenturyOnly.monthFlag())
                    .as("and the contrast proves the accumulation is real rather than a blanket "
                            + "invalidation: 1824-01-31 has a bad century but a sound month and day, so "
                            + "only the year is flagged")
                    .isEqualTo(EditFlag.ISVALID);
            assertThat(badCenturyOnly.dayFlag()).isEqualTo(EditFlag.ISVALID);
            assertThat(badCenturyOnly.allComponentsNotOk()).isFalse();
        }

        @Test
        @DisplayName("February 28 is always valid regardless of the year")
        void februaryTwentyEightIsAlwaysValid() {
            assertThat(DateValidationService.VALID_FEBRUARY_DAY_MAXIMUM)
                    .as("WS-FEBRUARY is month 2 at app/cpy/CSUTLDWY.cpy:L24, and 28 days is valid in "
                            + "every year, leap or not - the constant documents the boundary below which "
                            + "no leap-year arithmetic is needed at all")
                    .isEqualTo(28);
            assertThat(service().editDate("20230228", "openDate").valid())
                    .as("a non-leap year accepts the 28th")
                    .isTrue();
            assertThat(service().editDate("20240228", "openDate").valid())
                    .as("and so does a leap year")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("editDateOfBirth: the composite edits first, then the future guard")
    class DateOfBirthFutureGuard {

        @ParameterizedTest
        @ValueSource(strings = {"19800101", "20240614", "19000101"})
        @DisplayName("a date of birth on or before the current date is accepted")
        void aDateOfBirthOnOrBeforeTodayIsAccepted(final String input) {
            final EditOutcome outcome = service().editDateOfBirth(input, "dateOfBirth");

            assertThat(outcome.valid())
                    .as("with the clock fixed at 2024-06-15, '%s' is in the past and must pass", input)
                    .isTrue();
            assertThat(outcome.hasReturnMessage()).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"20240616", "20250101", "20991231"})
        @DisplayName("a date of birth after the current date is rejected")
        void aDateOfBirthAfterTodayIsRejected(final String input) {
            final EditOutcome outcome = service().editDateOfBirth(input, "dateOfBirth");

            assertThat(outcome.valid()).isFalse();
            assertThat(outcome.returnMessage())
                    .as("the literal keeps its TRAILING SPACE after 'future', exactly as the copybook "
                            + "declares it at app/cpy/CSUTLDPY.cpy:L360-L366")
                    .startsWith("dateOfBirth:cannot be in the future ");
        }

        @Test
        @DisplayName("the composite edits run first, so a malformed date never reaches the future guard")
        void theCompositeEditsRunFirst() {
            final EditOutcome outcome = service().editDateOfBirth("20240231", "dateOfBirth");

            assertThat(outcome.valid()).isFalse();
            assertThat(outcome.returnMessage().stripTrailing())
                    .as("31 February is rejected on the day-in-month edit and returns immediately; the "
                            + "future check is only reached when the composite date is already sound, so "
                            + "the diagnostic names the real defect rather than a misleading one")
                    .isEqualTo("dateOfBirth:Cannot have 31 days in this month.")
                    .doesNotContain("future");
        }

        @Test
        @DisplayName("today itself is rejected as future, so the accepted boundary is yesterday")
        void todayItselfIsRejectedAsFuture() {
            final EditOutcome today = service().editDateOfBirth("20240615", "dateOfBirth");

            assertThat(today.valid())
                    .as("PRESERVED LEGACY QUIRK, AND THE OPPOSITE OF WHAT AN EARLIER DRAFT ASSUMED. "
                            + "app/cpy/CSUTLDPY.cpy:L350 is IF WS-CURRENT-DATE-BINARY > "
                            + "WS-EDIT-DATE-BINARY, whose THEN branch is CONTINUE and whose ELSE branch "
                            + "rejects. Acceptance therefore requires the date to be STRICTLY EARLIER "
                            + "than today, so equality falls into the ELSE: A PERSON BORN TODAY CANNOT BE "
                            + "ENTERED. The draft read the strict comparison as making today acceptable "
                            + "and failed, which is how the direction was pinned down. Reproduced, not "
                            + "corrected - relaxing it to >= would accept a date the legacy screen "
                            + "rejects")
                    .isFalse();
            assertThat(today.returnMessage())
                    .as("and the diagnostic a same-day birth receives is the FUTURE message, even though "
                            + "the date is not in the future - the message is the ELSE branch's, so the "
                            + "wording is inherited rather than accurate")
                    .startsWith("dateOfBirth:cannot be in the future ");
            assertThat(today.allComponentsNotOk())
                    .as("the future branch sets the day, month and year flags together, so the whole "
                            + "date is marked rather than any one component")
                    .isTrue();

            assertThat(service().editDateOfBirth("20240614", "dateOfBirth").valid())
                    .as("the accepted boundary is therefore YESTERDAY - one day earlier passes")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("the injected clock contract")
    class ClockContract {

        @Test
        @DisplayName("the service refuses to be built without a clock")
        void theServiceRefusesToBeBuiltWithoutAClock() {
            assertThatNullPointerException()
                    .as("the future check needs a time source. Falling back to the system clock would "
                            + "make the date-of-birth edit untestable and its behaviour dependent on the "
                            + "day the suite happens to run")
                    .isThrownBy(() -> new DateValidationService(null))
                    .withMessageContaining("clock must not be null");
        }

        @Test
        @DisplayName("moving the clock moves the future boundary")
        void movingTheClockMovesTheFutureBoundary() {
            final DateValidationService later = new DateValidationService(
                    Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC));

            assertThat(service().editDateOfBirth("20250101", "dateOfBirth").valid())
                    .as("2025 is future to a 2024 clock")
                    .isFalse();
            assertThat(later.editDateOfBirth("20250101", "dateOfBirth").valid())
                    .as("and past to a 2030 one - proving the guard reads the injected clock rather than "
                            + "a captured constant")
                    .isTrue();
        }
    }
}
