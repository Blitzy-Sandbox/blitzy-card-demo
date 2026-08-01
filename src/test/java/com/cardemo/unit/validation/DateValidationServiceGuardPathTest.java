/*
 * ******************************************************************
 * Program     : DateValidationServiceGuardPathTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/validation
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Completes the coverage of DateValidationService by
 *               exercising the five guard paths that the behavioural
 *               suite never reaches - the eighty-byte result-area
 *               length contract, the fixed-width truncation branch,
 *               the LOW-VALUES detector's all-null-byte outcome, the
 *               variable-name leading-space trim, and the non-numeric
 *               year edit - and by recording, with a bounded exhaustive
 *               sweep, the four guards that the source's own ordering
 *               makes genuinely unreachable.
 * Source      : app/cbl/CSUTLDTC.cbl:L84-L86 (LS-DATE, LS-DATE-FORMAT
 *               and LS-RESULT declared PIC X(10), X(10) and X(80)) and
 *               :L105-L124 (A000-MAIN, the LENGTH OF move and the
 *               CEEDAYS call) @ 7756d89
 * Source      : app/cpy/CSUTLDPY.cpy:L25-L88 (EDIT-YEAR-CCYY, the
 *               LOW-VALUES/SPACES guard then the NUMERIC test then the
 *               century test), :L91-L145 (EDIT-MONTH, RANGE before
 *               NUMERIC), :L150-L205 (EDIT-DAY, NUMERIC before RANGE),
 *               :L209-L280 (EDIT-DAY-MONTH-YEAR) and :L284-L327
 *               (EDIT-DATE-LE) @ 7756d89
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
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.DateValidationService.DateValidationResult;
import com.cardemo.service.shared.DateValidationService.EditFlag;
import com.cardemo.service.shared.DateValidationService.EditOutcome;
import com.cardemo.service.shared.DateValidationService.FeedbackCode;
import com.cardemo.service.shared.DateValidationService.FeedbackCondition;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Guard-path unit test for {@link DateValidationService}, the Java replacement for the statically called
 * {@code CSUTLDTC} program and its two work-area copybooks.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>The behavioural suite in {@code DateValidationServiceTest} asserts the outcomes a caller cares about: the
 * nine CEEDAYS feedback conditions, the eighty-byte result area field by field, and the component edits with
 * their leap-year remainder test. That leaves five defensive paths inside the service that no ordinary date
 * exercises, and this class reaches all five. It also records, with evidence rather than assertion, the four
 * guards that cannot be reached at all - because the COBOL's own statement ordering makes them dead, and
 * reproducing that ordering faithfully reproduces the deadness.
 *
 * <h3>The five paths this test reaches</h3>
 *
 * <ul>
 *   <li><b>The result-area length contract.</b> {@code app/cbl/CSUTLDTC.cbl:L86} declares {@code LS-RESULT PIC
 *       X(80)}, so the record that carries it refuses any other width. Only a caller constructing the record
 *       directly can violate that, which is why no date input reaches the check.</li>
 *   <li><b>Fixed-width truncation.</b> {@code app/cbl/CSUTLDTC.cbl:L84-L85} declares both inputs {@code PIC
 *       X(10)}, and a COBOL {@code MOVE} into a shorter field truncates on the right. Every argument longer
 *       than its declared width takes that branch.</li>
 *   <li><b>The all-{@code LOW-VALUES} outcome.</b> {@code app/cpy/CSUTLDPY.cpy:L30-L31} tests {@code EQUAL
 *       LOW-VALUES OR EQUAL SPACES}. The spaces half is ordinary; the low-values half needs an argument built
 *       from null bytes, which is how an uninitialised COBOL field presents and what this test supplies.</li>
 *   <li><b>The variable-name leading-space trim.</b> The name is truncated to its declared width first and
 *       trimmed second, so a leading space both takes the skip loop and costs one payload character.</li>
 *   <li><b>The non-numeric year edit.</b> {@code app/cpy/CSUTLDPY.cpy:L48} is reached only after the
 *       low-values and spaces guard has passed, so the year has to be present but not wholly numeric.</li>
 * </ul>
 *
 * <h3>The four guards proven unreachable, and why they are kept</h3>
 *
 * <p>Rule 1 Clause B forbids <em>untracked</em> unreachable code. These four are tracked here, each with the
 * source ordering that makes it dead, and each is retained because deleting it would break the paragraph
 * correspondence the traceability matrix asserts - the same disposition the corpus already carries for
 * {@code CBACT04C.1400-COMPUTE-FEES} and for reject code 109 in {@code CBTRN02C}.
 *
 * <ul>
 *   <li><b>The month {@code NUMERIC} test.</b> {@code app/cpy/CSUTLDPY.cpy:L111} tests the range on the
 *       numeric view and {@code :L126} tests numericness afterwards, so a non-numeric month is already out of
 *       range and has already been rejected. {@link UnreachableGuardEvidence} proves the two branches are
 *       observationally identical.</li>
 *   <li><b>The {@code EDIT-DATE-LE} non-zero severity branch.</b> {@code app/cpy/CSUTLDPY.cpy:L284} runs only
 *       when the three component edits all passed, and in the accepted century window those edits already
 *       reject every date CEEDAYS would. The sweep below finds no counter-example.</li>
 *   <li><b>The {@code INTEGER-OF-DATE} conversion failure.</b> Same precondition, same conclusion.</li>
 *   <li><b>The insufficient-data-by-length return.</b> Its only caller hands it a value already fixed to the
 *       declared ten characters, and no recognised mask is longer than ten.</li>
 * </ul>
 *
 * <h2>2. How to run it</h2>
 *
 * <p>{@code mvn -o -B test -Dtest=DateValidationServiceGuardPathTest -DfailIfNoSpecifiedTests=false}, or as
 * part of {@code mvn -o -B clean verify -Ddependency-check.skip=true}. No container, no Docker socket, no
 * database and no network access are required; the service's only collaborator is a {@link Clock}, which is
 * fixed here.
 *
 * <h2>3. Configuration and defaults</h2>
 *
 * <p>The clock is pinned to {@code 2024-06-15T12:00:00Z} at {@link ZoneOffset#UTC} so that the date-of-birth
 * sweep has a stable notion of "today". Every declared width is read from the service's own public constants
 * rather than repeated as a literal, so a change to a width fails this test instead of silently passing it.
 * The exhaustive sweep is bounded, not unbounded: it walks every year the century edit accepts, together with
 * a set of years it rejects, so it covers the reachable state space without becoming a long-running test.
 *
 * <h2>4. Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li>A failure in {@link ResultAreaLengthContract} means the eighty-byte result area's width has been
 *       changed or its guard removed; the width is fixed by {@code LS-RESULT PIC X(80)} and is not adjustable.</li>
 *   <li>A failure in {@link FixedWidthTruncation} means an over-long argument is now rejected or propagated
 *       instead of truncated. COBOL truncates, so the service must too.</li>
 *   <li>A failure in {@link LowValuesDetection} means the null-byte half of the {@code LOW-VALUES OR SPACES}
 *       test has been dropped, which would let an uninitialised field through as a real value.</li>
 *   <li>A failure in {@link UnreachableGuardEvidence} is the interesting one: it means an input <em>has</em>
 *       been found that reaches a guard documented here as dead. That is not a test defect - it is a new fact
 *       about the translation, and the guard must then be covered by a behavioural test and this Javadoc
 *       corrected.</li>
 * </ul>
 */
@DisplayName("DateValidationService guard paths - CSUTLDTC and CSUTLDPY defensive branches")
final class DateValidationServiceGuardPathTest {

    /** The fixed instant the service's clock reports, so the date-of-birth sweep has a stable "today". */
    private static final Instant FIXED_INSTANT = Instant.parse("2024-06-15T12:00:00Z");

    /** The exact text the result-area length guard raises, minus the two numbers it interpolates. */
    private static final String LENGTH_GUARD_PREFIX = "result must be exactly ";

    /** The message {@code app/cpy/CSUTLDPY.cpy:L51-L57} builds for a year that is present but not numeric. */
    private static final String YEAR_NOT_NUMERIC = " must be 4 digit number.";

    /** The message {@code app/cpy/CSUTLDPY.cpy:L34-L40} builds for an absent year. */
    private static final String YEAR_ABSENT = " : Year must be supplied.";

    /** The message {@code app/cpy/CSUTLDPY.cpy:L98-L104} builds for an absent month. */
    private static final String MONTH_ABSENT = " : Month must be supplied.";

    /** The message {@code app/cpy/CSUTLDPY.cpy:L158-L164} builds for an absent day. */
    private static final String DAY_ABSENT = " : Day must be supplied.";

    /** The message {@code app/cpy/CSUTLDPY.cpy:L116-L122} and {@code :L133-L139} both build for a month. */
    private static final String MONTH_RANGE = ": Month must be a number between 1 and 12.";

    /** The variable name used wherever the name itself is not under test. */
    private static final String PROBE_FIELD = "probeField";

    /** A single null byte, the character {@code LOW-VALUES} denotes for a one-byte field. */
    private static final String NUL = "\u0000";

    /** The fragment the {@code INTEGER-OF-DATE} conversion failure names; used to recognise that outcome. */
    private static final String INTEGER_OF_DATE = "INTEGER-OF-DATE";

    /** The fragment {@code app/cpy/CSUTLDPY.cpy:L305-L314} splices when CEEDAYS reports a non-zero severity. */
    private static final String LE_SEVERITY_FRAGMENT = " validation error Sev code: ";

    /**
     * Every eight-character candidate the unreachability sweep evaluates.
     *
     * <p>Two bands. The first walks every year {@code app/cpy/CSUTLDPY.cpy:L70-L71} accepts - centuries 19 and
     * 20, so 1900 through 2099 - against months 0 through 13 and days 0 through 32, which spans both sides of
     * every boundary the month and day edits test. The second walks years the century edit rejects, against
     * the whole of the legal month and day ranges, so that a guard reached only by a rejected year would still
     * be found.
     */
    private static final List<String> SWEEP_CANDIDATES = buildSweepCandidates();

    private static List<String> buildSweepCandidates() {
        final List<String> candidates = new ArrayList<>();
        for (int year = 1900; year <= 2099; year++) {
            for (int month = 0; month <= 13; month++) {
                for (int day = 0; day <= 32; day++) {
                    candidates.add(String.format("%04d%02d%02d", year, month, day));
                }
            }
        }
        for (final int year : new int[] {0, 1, 999, 1581, 1582, 1583, 1899, 2100, 2400, 9999}) {
            for (int month = 1; month <= 12; month++) {
                for (int day = 1; day <= 31; day++) {
                    candidates.add(String.format("%04d%02d%02d", year, month, day));
                }
            }
        }
        return List.copyOf(candidates);
    }

    /** @return a service whose clock is pinned to {@link #FIXED_INSTANT}. */
    private static DateValidationService service() {
        return new DateValidationService(Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
    }

    /** @return a result-area payload of exactly the declared width, usable wherever the value is irrelevant. */
    private static String wellFormedResult() {
        return " ".repeat(DateValidationService.LS_RESULT_LENGTH);
    }

    /** @return the all-clear feedback code, usable wherever the code itself is not under test. */
    private static FeedbackCode allClear() {
        return FeedbackCode.of(FeedbackCondition.FC_INVALID_DATE);
    }

    @Nested
    @DisplayName("The result area refuses any width other than the eighty bytes LS-RESULT declares")
    class ResultAreaLengthContract {

        @Test
        @DisplayName("A result shorter than the declared eighty characters is rejected, naming both the "
                + "required width and the width supplied")
        void shortResultIsRejectedWithBothWidthsNamed() {
            final IllegalArgumentException thrown = catchThrowableOfType(IllegalArgumentException.class,
                    () -> new DateValidationResult(allClear(), "too short"));

            assertThat(thrown)
                    .as("app/cbl/CSUTLDTC.cbl:L86 declares LS-RESULT PIC X(80), so a nine-character payload "
                            + "cannot be a valid result area and the record must refuse it")
                    .isNotNull();
            assertThat(thrown.getMessage())
                    .as("the diagnostic has to name the required width so the caller can see what LS-RESULT "
                            + "demands, and the supplied width so it can see what it sent")
                    .isEqualTo(LENGTH_GUARD_PREFIX + DateValidationService.LS_RESULT_LENGTH
                            + " characters, was 9");
        }

        @Test
        @DisplayName("A result longer than the declared eighty characters is rejected on the same guard, "
                + "because the check is an equality and not a lower bound")
        void longResultIsRejectedBecauseTheGuardIsAnEquality() {
            final int oversize = DateValidationService.LS_RESULT_LENGTH + 1;

            final IllegalArgumentException thrown = catchThrowableOfType(IllegalArgumentException.class,
                    () -> new DateValidationResult(allClear(), " ".repeat(oversize)));

            assertThat(thrown.getMessage())
                    .as("a COBOL MOVE into PIC X(80) would truncate an over-long value, so accepting one here "
                            + "would let a payload of the wrong shape reach a caller that indexes it by offset")
                    .isEqualTo(LENGTH_GUARD_PREFIX + DateValidationService.LS_RESULT_LENGTH
                            + " characters, was " + oversize);
        }

        @Test
        @DisplayName("An empty result is rejected, which is the degenerate case of the same equality guard")
        void emptyResultIsRejected() {
            final IllegalArgumentException thrown = catchThrowableOfType(IllegalArgumentException.class,
                    () -> new DateValidationResult(allClear(), ""));

            assertThat(thrown.getMessage())
                    .as("an empty payload is the most likely accidental value and must not slip through the "
                            + "width check")
                    .isEqualTo(LENGTH_GUARD_PREFIX + DateValidationService.LS_RESULT_LENGTH
                            + " characters, was 0");
        }

        @Test
        @DisplayName("A result of exactly the declared eighty characters is accepted and retained verbatim")
        void exactlyDeclaredWidthIsAccepted() {
            final DateValidationResult accepted = new DateValidationResult(allClear(), wellFormedResult());

            assertThat(accepted.result())
                    .as("the guard is a width check and nothing else, so a correctly sized payload must be "
                            + "stored byte for byte rather than normalised")
                    .isEqualTo(wellFormedResult())
                    .hasSize(DateValidationService.LS_RESULT_LENGTH);
        }

        @Test
        @DisplayName("Every result the service itself produces satisfies the guard, so the check can only "
                + "ever fire for a caller that constructs the record directly")
        void everyServiceProducedResultSatisfiesTheGuard() {
            final DateValidationService service = service();

            final DateValidationResult separated = service.validate("2024-01-31",
                    DateValidationService.MASK_YYYY_MM_DD);
            final DateValidationResult packed = service.validate("20240131",
                    DateValidationService.MASK_YYYYMMDD);
            final DateValidationResult rejected = service.validate("20249999",
                    DateValidationService.MASK_YYYYMMDD);

            assertThat(List.of(separated.result(), packed.result(), rejected.result()))
                    .as("app/cbl/CSUTLDTC.cbl:L97 MOVE WS-MESSAGE TO LS-RESULT always moves a fully "
                            + "composed eighty-byte area, on the success path and on both failure paths, "
                            + "which is precisely why no date input can reach the length guard")
                    .allSatisfy(result -> assertThat(result)
                            .hasSize(DateValidationService.LS_RESULT_LENGTH));
        }
    }

    @Nested
    @DisplayName("Arguments longer than their declared PIC width are truncated on the right, as a COBOL "
            + "MOVE into a shorter field does")
    class FixedWidthTruncation {

        @Test
        @DisplayName("A date longer than the ten characters LS-DATE declares is truncated, and produces a "
                + "result byte-identical to passing the truncated value directly")
        void overLongDateIsTruncatedToTheDeclaredWidth() {
            final DateValidationService service = service();

            final DateValidationResult truncated = service.validate("2024-01-31EXTRA",
                    DateValidationService.MASK_YYYY_MM_DD);
            final DateValidationResult exact = service.validate("2024-01-31",
                    DateValidationService.MASK_YYYY_MM_DD);

            assertThat(truncated)
                    .as("app/cbl/CSUTLDTC.cbl:L84 declares LS-DATE PIC X(10); the five characters beyond it "
                            + "have to be discarded before CEEDAYS is reached, so the two calls cannot differ")
                    .isEqualTo(exact);
            assertThat(truncated.result())
                    .as("the discarded characters must not survive into the result area's TstDate field, "
                            + "which is itself only PIC X(10)")
                    .doesNotContain("EXTRA");
        }

        @Test
        @DisplayName("A mask longer than the ten characters LS-DATE-FORMAT declares is truncated, and a "
                + "trailing suffix that would otherwise make it unrecognised is discarded")
        void overLongMaskIsTruncatedToTheDeclaredWidth() {
            final DateValidationService service = service();

            final DateValidationResult truncatedMask = service.validate("2024-01-31", "YYYY-MM-DDZZZ");

            assertThat(truncatedMask.feedbackCode().severity())
                    .as("app/cbl/CSUTLDTC.cbl:L85 declares LS-DATE-FORMAT PIC X(10), so 'YYYY-MM-DDZZZ' "
                            + "arrives as 'YYYY-MM-DD' and is recognised; had the suffix survived, the "
                            + "picture string would have been rejected instead")
                    .isZero();
            assertThat(truncatedMask.result())
                    .as("the Mask used: field of the result area is PIC X(10) and must show the truncated "
                            + "mask, never the value the caller passed")
                    .contains("YYYY-MM-DD")
                    .doesNotContain("ZZZ");
        }

        @Test
        @DisplayName("A mask whose first ten characters are not a recognised picture string is still "
                + "rejected after truncation, proving truncation happens before recognition")
        void truncationHappensBeforeMaskRecognition() {
            final DateValidationResult rejected = service().validate("2024-01-31", "YYYY/MM/DDXX");

            assertThat(rejected.feedbackCode().condition())
                    .as("truncating 'YYYY/MM/DDXX' yields 'YYYY/MM/DD', which is neither corpus mask, so the "
                            + "bad-picture-string condition is the correct outcome and the truncation is what "
                            + "decides which ten characters are judged")
                    .contains(FeedbackCondition.FC_BAD_PIC_STRING);
        }

        @ParameterizedTest
        @ValueSource(strings = {"202401319", "2024013199", "20240131EXTRA", "20240131          "})
        @DisplayName("A composite date longer than the eight characters WS-EDIT-DATE-CCYYMMDD declares is "
                + "truncated, so the surplus cannot change the verdict")
        void overLongCompositeDateIsTruncated(final String overLong) {
            final EditOutcome outcome = service().editDate(overLong, PROBE_FIELD);

            assertThat(outcome.valid())
                    .as("WS-EDIT-DATE-CCYYMMDD is an eight-byte group, so %s must be judged as its first "
                            + "eight characters - 20240131, a real date - and accepted", overLong)
                    .isTrue();
            assertThat(outcome.returnMessage().trim())
                    .as("an accepted date leaves the return message blank; any text here would mean the "
                            + "surplus characters had reached an edit")
                    .isEmpty();
        }

        @Test
        @DisplayName("A variable name longer than the twenty-five characters WS-EDIT-VARIABLE-NAME declares "
                + "is truncated, and the truncated form is what the diagnostic quotes")
        void overLongVariableNameIsTruncated() {
            final String overLong = "aVariableNameLongerThanTwentyFiveCharacters";

            final EditOutcome outcome = service().editDate("20241332", overLong);

            assertThat(overLong.length())
                    .as("the fixture has to be longer than the declared width or the branch under test is "
                            + "never taken")
                    .isGreaterThan(DateValidationService.VARIABLE_NAME_LENGTH);
            assertThat(outcome.returnMessage().trim())
                    .as("the diagnostic splices the name at its declared width of %d, so the tail is lost "
                            + "and the message reads as the source's STRING statement would render it",
                            DateValidationService.VARIABLE_NAME_LENGTH)
                    .isEqualTo(overLong.substring(0, DateValidationService.VARIABLE_NAME_LENGTH)
                            + MONTH_RANGE);
        }

        @Test
        @DisplayName("A short value is padded rather than truncated, which is the other half of the same "
                + "fixed-width move and must not be confused with it")
        void shortValueIsPaddedNotTruncated() {
            final EditOutcome outcome = service().editDate("202401", PROBE_FIELD);

            assertThat(outcome.dayFlag())
                    .as("padding '202401' to eight characters leaves the day as two spaces, which the "
                            + "LOW-VALUES OR SPACES guard reports as absent rather than as out of range")
                    .isEqualTo(EditFlag.BLANK);
            assertThat(outcome.returnMessage().trim())
                    .as("the absent-day diagnostic is the proof that the pad branch ran and not the "
                            + "truncate branch")
                    .isEqualTo(PROBE_FIELD + DAY_ABSENT);
        }

        @Test
        @DisplayName("A null date is absorbed as spaces rather than propagated, which is the third arm of "
                + "the same fixed-width move")
        void nullDateIsAbsorbedAsSpaces() {
            final DateValidationResult result = service().validate(null, DateValidationService.MASK_YYYYMMDD);

            assertThat(result.feedbackCode().condition())
                    .as("a COBOL caller cannot pass a null, so the Java translation absorbs one into the "
                            + "declared width of spaces; the significant positions are then absent and the "
                            + "insufficient-data condition is the outcome")
                    .contains(FeedbackCondition.FC_INSUFFICIENT_DATA);
            assertThat(result.result())
                    .as("the result area is still composed to its declared width on this path")
                    .hasSize(DateValidationService.LS_RESULT_LENGTH);
        }
    }

    @Nested
    @DisplayName("The LOW-VALUES half of the CSUTLDPY absent-component guard recognises a field built "
            + "entirely from null bytes")
    class LowValuesDetection {

        @Test
        @DisplayName("A composite date of eight null bytes reports the year as absent, taking the "
                + "LOW-VALUES arm of the CSUTLDPY:L30-L31 test rather than the SPACES arm")
        void allNullBytesReportsTheYearAbsent() {
            final EditOutcome outcome = service().editDate(NUL.repeat(DateValidationService.CCYYMMDD_LENGTH),
                    PROBE_FIELD);

            assertThat(outcome.yearFlag())
                    .as("app/cpy/CSUTLDPY.cpy:L33 SETs FLG-YEAR-BLANK, not FLG-YEAR-NOT-OK, when the year is "
                            + "LOW-VALUES; the distinction is what tells a screen to prompt rather than to "
                            + "complain")
                    .isEqualTo(EditFlag.BLANK);
            assertThat(outcome.returnMessage().trim())
                    .as("the year is edited first, so its diagnostic is the one the message latch keeps")
                    .isEqualTo(PROBE_FIELD + YEAR_ABSENT);
            assertThat(outcome.valid())
                    .as("an absent year cannot yield a valid date")
                    .isFalse();
        }

        @Test
        @DisplayName("A month of two null bytes reports the month as absent while the year, being present "
                + "and well formed, is still accepted")
        void nullByteMonthReportsTheMonthAbsent() {
            final EditOutcome outcome = service().editDate("2024" + NUL.repeat(2) + "15", PROBE_FIELD);

            assertThat(outcome.yearFlag())
                    .as("the year bytes are untouched by the month's null bytes and must still pass their "
                            + "own edit, which is what makes the month diagnostic the one that surfaces")
                    .isEqualTo(EditFlag.ISVALID);
            assertThat(outcome.monthFlag())
                    .as("app/cpy/CSUTLDPY.cpy:L97 SETs FLG-MONTH-BLANK for a LOW-VALUES month")
                    .isEqualTo(EditFlag.BLANK);
            assertThat(outcome.returnMessage().trim())
                    .as("the message latch keeps the first diagnostic emitted, and with a valid year that is "
                            + "the month's")
                    .isEqualTo(PROBE_FIELD + MONTH_ABSENT);
        }

        @Test
        @DisplayName("An absent month combined with a thirty-first day has its BLANK flag overwritten to "
                + "NOT_OK by EDIT-DAY-MONTH-YEAR, an observed interaction that the day chosen in the "
                + "preceding test deliberately avoids")
        void absentMonthWithDayThirtyOneIsDowngradedToNotOk() {
            final EditOutcome outcome = service().editDate("2024" + NUL.repeat(2) + "31", PROBE_FIELD);

            assertThat(outcome.monthFlag())
                    .as("app/cpy/CSUTLDPY.cpy:L213-L217 tests NOT WS-31-DAY-MONTH AND WS-DAY-31 against the "
                            + "numeric month view, which for an absent month is the below-minimum sentinel, "
                            + "so the composite edit fires and re-SETs FLG-MONTH-NOT-OK over the BLANK "
                            + "set at :L97 - the flag a caller finally sees depends on the day")
                    .isEqualTo(EditFlag.NOT_OK);
            assertThat(outcome.dayFlag())
                    .as(":L216 SETs FLG-DAY-NOT-OK on the same statement, so a valid day is reported invalid "
                            + "because the month it belongs to is absent")
                    .isEqualTo(EditFlag.NOT_OK);
            assertThat(outcome.returnMessage().trim())
                    .as("the INPUT-ERROR latch was already set by the month edit, so the composite edit's "
                            + "own diagnostic is suppressed and the absent-month message survives even "
                            + "though the flags were changed underneath it")
                    .isEqualTo(PROBE_FIELD + MONTH_ABSENT);
        }

        @Test
        @DisplayName("A day of two null bytes reports the day as absent, so the LOW-VALUES arm is honoured "
                + "at all three component edits and not only at the first")
        void nullByteDayReportsTheDayAbsent() {
            final EditOutcome outcome = service().editDate("202401" + NUL.repeat(2), PROBE_FIELD);

            assertThat(outcome.dayFlag())
                    .as("app/cpy/CSUTLDPY.cpy:L157 SETs FLG-DAY-BLANK for a LOW-VALUES day; the day edit is "
                            + "otherwise optimistic, so this is the only way it reports absence")
                    .isEqualTo(EditFlag.BLANK);
            assertThat(outcome.returnMessage().trim())
                    .as("with a valid year and month the day's diagnostic is the one the latch keeps")
                    .isEqualTo(PROBE_FIELD + DAY_ABSENT);
        }

        @Test
        @DisplayName("A single non-null byte defeats the LOW-VALUES test, so a partly initialised year is "
                + "judged as text and not as absent")
        void oneNonNullByteDefeatsTheLowValuesTest() {
            final EditOutcome outcome = service().editDate("2" + NUL.repeat(3) + "0131", PROBE_FIELD);

            assertThat(outcome.yearFlag())
                    .as("the test is an equality against LOW-VALUES for the whole field, so '2' followed by "
                            + "three null bytes is present-but-not-numeric, which is a different diagnostic "
                            + "and a different flag from absent")
                    .isEqualTo(EditFlag.NOT_OK);
            assertThat(outcome.returnMessage().trim())
                    .as("present-but-not-numeric selects the four-digit-number diagnostic of "
                            + "app/cpy/CSUTLDPY.cpy:L51-L57")
                    .isEqualTo(PROBE_FIELD + YEAR_NOT_NUMERIC);
        }

        @Test
        @DisplayName("An all-spaces year reaches the same absent outcome as an all-null-byte year, because "
                + "CSUTLDPY:L30-L31 is a single OR over both representations")
        void allSpacesAndAllNullBytesAgree() {
            final DateValidationService service = service();

            final EditOutcome viaNullBytes = service.editDate(NUL.repeat(4) + "0131", PROBE_FIELD);
            final EditOutcome viaSpaces = service.editDate("    0131", PROBE_FIELD);

            assertThat(viaNullBytes.yearFlag())
                    .as("both representations denote an uninitialised field and the source treats them "
                            + "identically, so the flags must agree")
                    .isEqualTo(viaSpaces.yearFlag())
                    .isEqualTo(EditFlag.BLANK);
            assertThat(viaNullBytes.returnMessage())
                    .as("the diagnostic is emitted by the same statement on both arms, so the whole "
                            + "eighty-character return message must match byte for byte")
                    .isEqualTo(viaSpaces.returnMessage());
        }
    }

    @Nested
    @DisplayName("The variable name is truncated to its declared width first and trimmed of surrounding "
            + "spaces second")
    class VariableNameTrimming {

        @Test
        @DisplayName("A single leading space is removed, so the diagnostic reads as though the caller had "
                + "passed the bare name")
        void singleLeadingSpaceIsRemoved() {
            final EditOutcome outcome = service().editDate("20241332", " " + PROBE_FIELD);

            assertThat(outcome.returnMessage().trim())
                    .as("app/cpy/CSUTLDPY.cpy builds its diagnostics with STRING ... DELIMITED BY SIZE over "
                            + "a trimmed name, so a leading space must not appear in the message")
                    .isEqualTo(PROBE_FIELD + MONTH_RANGE);
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 2, 3, 7, 14})
        @DisplayName("Any number of leading spaces is removed, so the skip is a loop and not a single-space "
                + "special case")
        void anyNumberOfLeadingSpacesIsRemoved(final int leadingSpaces) {
            final EditOutcome outcome = service().editDate("20241332", " ".repeat(leadingSpaces) + PROBE_FIELD);

            assertThat(outcome.returnMessage().trim())
                    .as("%d leading spaces must all be skipped; a guard that removed only the first would "
                            + "leave the rest in the diagnostic", leadingSpaces)
                    .isEqualTo(PROBE_FIELD + MONTH_RANGE);
        }

        @Test
        @DisplayName("Leading and trailing spaces are both removed, and the result matches the bare name")
        void leadingAndTrailingSpacesAreBothRemoved() {
            final DateValidationService service = service();

            final EditOutcome padded = service.editDate("20241332", "  " + PROBE_FIELD + "  ");
            final EditOutcome bare = service.editDate("20241332", PROBE_FIELD);

            assertThat(padded.returnMessage())
                    .as("the two loops together are equivalent to trimming, so a symmetrically padded name "
                            + "must produce a byte-identical eighty-character return message")
                    .isEqualTo(bare.returnMessage());
        }

        @Test
        @DisplayName("A name of nothing but spaces collapses to the empty string, which is the case where "
                + "the leading-space loop consumes the whole field")
        void allSpacesNameCollapsesToEmpty() {
            final String allSpaces = " ".repeat(DateValidationService.VARIABLE_NAME_LENGTH);

            final EditOutcome outcome = service().editDate("20241332", allSpaces);

            assertThat(outcome.returnMessage().trim())
                    .as("with no name to splice, the diagnostic is the literal alone; this is the boundary "
                            + "where the skip loop's start index reaches the field's end")
                    .isEqualTo(MONTH_RANGE);
        }

        @Test
        @DisplayName("A name with only trailing spaces exercises the trailing loop alone and is unchanged "
                + "by the leading one")
        void trailingOnlyNameIsUnchangedByTheLeadingLoop() {
            final EditOutcome outcome = service().editDate("20241332", PROBE_FIELD + "     ");

            assertThat(outcome.returnMessage().trim())
                    .as("the name is already left-aligned, so only the trailing loop has work to do and the "
                            + "spliced name is identical to the bare one")
                    .isEqualTo(PROBE_FIELD + MONTH_RANGE);
        }

        @Test
        @DisplayName("Truncation precedes trimming, so a leading space on an over-long name costs one "
                + "character of payload")
        void truncationPrecedesTrimming() {
            final String bareOverLong = "aVariableNameLongerThanTwentyFiveCharacters";
            final int width = DateValidationService.VARIABLE_NAME_LENGTH;

            final DateValidationService service = service();
            final EditOutcome withoutSpace = service.editDate("20241332", bareOverLong);
            final EditOutcome withSpace = service.editDate("20241332", " " + bareOverLong);

            assertThat(withoutSpace.returnMessage().trim())
                    .as("without a leading space the whole declared width of %d is payload", width)
                    .startsWith(bareOverLong.substring(0, width));
            assertThat(withSpace.returnMessage().trim())
                    .as("with a leading space the fixed-width move keeps that space, so only %d payload "
                            + "characters survive the truncation and the trim then drops the space - the "
                            + "order of the two operations is observable and must not be swapped", width - 1)
                    .startsWith(bareOverLong.substring(0, width - 1) + MONTH_RANGE.charAt(0));
        }
    }

    @Nested
    @DisplayName("A year that is present but not wholly numeric is rejected by CSUTLDPY:L48, after the "
            + "absent-year guard and before the century test")
    class NonNumericYear {

        @ParameterizedTest
        @ValueSource(strings = {"202X0131", "X0240131", "2X240131", "20X40131", "20+40131", "2 240131",
                "20.40131", "----0131"})
        @DisplayName("A non-digit anywhere in the four year bytes selects the four-digit-number diagnostic, "
                + "whatever the position and whatever the character")
        void anyNonDigitInTheYearIsRejected(final String candidate) {
            final EditOutcome outcome = service().editDate(candidate, PROBE_FIELD);

            assertThat(outcome.yearFlag())
                    .as("app/cpy/CSUTLDPY.cpy:L50 re-SETs FLG-YEAR-NOT-OK on this path, so %s must report "
                            + "NOT_OK and not BLANK", candidate)
                    .isEqualTo(EditFlag.NOT_OK);
            assertThat(outcome.returnMessage().trim())
                    .as("the diagnostic at :L51-L57 is a fixed literal spliced after the trimmed name")
                    .isEqualTo(PROBE_FIELD + YEAR_NOT_NUMERIC);
            assertThat(outcome.inputError())
                    .as(":L49 SETs INPUT-ERROR, which is the latch that stops every later edit from "
                            + "overwriting this diagnostic")
                    .isTrue();
            assertThat(outcome.valid())
                    .as("a year that failed its edit cannot produce a valid date")
                    .isFalse();
        }

        @Test
        @DisplayName("The non-numeric year edit returns immediately, so the century test never runs and a "
                + "non-numeric century is not reported as an invalid century")
        void nonNumericYearReturnsBeforeTheCenturyTest() {
            final EditOutcome outcome = service().editDate("XX240131", PROBE_FIELD);

            assertThat(outcome.returnMessage().trim())
                    .as("app/cpy/CSUTLDPY.cpy:L58 is a GO TO EDIT-YEAR-CCYY-EXIT, so the century "
                            + "diagnostic at :L76-L82 is unreachable once the numeric test has failed; "
                            + "reading WS-EDIT-DATE-CC-N on a non-numeric field is exactly what that "
                            + "GO TO prevents")
                    .isEqualTo(PROBE_FIELD + YEAR_NOT_NUMERIC);
        }

        @Test
        @DisplayName("The month and day of a date with a non-numeric year are still edited, because the "
                + "source performs all three paragraphs unconditionally")
        void monthAndDayAreStillEditedWhenTheYearIsNonNumeric() {
            final EditOutcome outcome = service().editDate("202X0131", PROBE_FIELD);

            assertThat(outcome.monthFlag())
                    .as("app/cpy/CSUTLDPY.cpy:L91 PERFORMs EDIT-MONTH whatever the year edit concluded, so "
                            + "a valid month must still be flagged valid")
                    .isEqualTo(EditFlag.ISVALID);
            assertThat(outcome.dayFlag())
                    .as(":L150 PERFORMs EDIT-DAY on the same unconditional basis")
                    .isEqualTo(EditFlag.ISVALID);
            assertThat(outcome.allComponentsNotOk())
                    .as("only the year failed, so the all-components-failed predicate must be false; a true "
                            + "here would mean the latch had been applied to the wrong flags")
                    .isFalse();
        }

        @Test
        @DisplayName("The absent-year guard takes precedence over the non-numeric guard, which is the "
                + "statement order CSUTLDPY:L30 and :L48 fix")
        void absentYearTakesPrecedenceOverNonNumericYear() {
            final DateValidationService service = service();

            final EditOutcome absent = service.editDate("    0131", PROBE_FIELD);
            final EditOutcome nonNumeric = service.editDate("   X0131", PROBE_FIELD);

            assertThat(absent.yearFlag())
                    .as("all four year bytes are spaces, so :L30-L31 matches first and the flag is BLANK")
                    .isEqualTo(EditFlag.BLANK);
            assertThat(nonNumeric.yearFlag())
                    .as("one non-space byte defeats the absent test, so control reaches :L48 and the flag "
                            + "is NOT_OK - the two guards are ordered, not alternatives")
                    .isEqualTo(EditFlag.NOT_OK);
        }
    }

    @Nested
    @DisplayName("Evidence that four remaining guards cannot be reached, recorded rather than asserted away")
    class UnreachableGuardEvidence {

        @Test
        @DisplayName("A non-numeric month is observationally identical to an out-of-range numeric month, "
                + "which is why the NUMERIC test at CSUTLDPY:L126 is dead after the RANGE test at :L111")
        void nonNumericMonthIsIndistinguishableFromAnOutOfRangeMonth() {
            final DateValidationService service = service();

            final EditOutcome nonNumeric = service.editDate("2024X131", PROBE_FIELD);
            final EditOutcome outOfRange = service.editDate("20241331", PROBE_FIELD);

            assertThat(nonNumeric.monthFlag())
                    .as("the numeric view of a non-numeric month is the sentinel below the legal minimum, so "
                            + "the range test rejects it before the numeric test is ever evaluated")
                    .isEqualTo(outOfRange.monthFlag())
                    .isEqualTo(EditFlag.NOT_OK);
            assertThat(nonNumeric.returnMessage())
                    .as("app/cpy/CSUTLDPY.cpy:L116-L122 and :L133-L139 build the same literal, so the two "
                            + "paths are indistinguishable to any caller; the second is retained for "
                            + "paragraph correspondence and is tracked here rather than deleted")
                    .isEqualTo(outOfRange.returnMessage());
        }

        @Test
        @DisplayName("Across every year the century edit accepts, and a set it rejects, no input makes "
                + "EDIT-DATE-LE report a non-zero severity")
        void editDateLeNeverReportsANonZeroSeverity() {
            final DateValidationService service = service();
            final List<String> reached = new ArrayList<>();

            for (final String candidate : SWEEP_CANDIDATES) {
                if (service.editDate(candidate, PROBE_FIELD).returnMessage().contains(LE_SEVERITY_FRAGMENT)) {
                    reached.add(candidate);
                }
            }

            assertThat(reached)
                    .as("app/cpy/CSUTLDPY.cpy:L284 runs only when all three component edits passed, and in "
                            + "centuries 19 and 20 those edits already reject every date CEEDAYS would - "
                            + "including 31 in a short month, 30 in February and 29 in a non-leap year. "
                            + "%d candidates were evaluated and none reached the branch. If this list is "
                            + "ever non-empty the branch is reachable, and it must then be covered by a "
                            + "behavioural test rather than documented as dead.", SWEEP_CANDIDATES.size())
                    .isEmpty();
        }

        @Test
        @DisplayName("Across the same sweep, no input reaches the INTEGER-OF-DATE conversion failure in "
                + "the date-of-birth edit")
        void integerOfDateConversionNeverFails() {
            final DateValidationService service = service();
            final List<String> reached = new ArrayList<>();

            for (final String candidate : SWEEP_CANDIDATES) {
                try {
                    service.editDateOfBirth(candidate, PROBE_FIELD);
                } catch (final RuntimeException cause) {
                    if (String.valueOf(cause.getMessage()).contains(INTEGER_OF_DATE)) {
                        reached.add(candidate);
                    } else {
                        throw cause;
                    }
                }
            }

            assertThat(reached)
                    .as("app/cpy/CSUTLDPY.cpy:L345-L346 converts a date the composite edit has already "
                            + "accepted, so the conversion cannot fail; %d candidates were evaluated and "
                            + "none reached the catch. The guard is retained because the source's FUNCTION "
                            + "INTEGER-OF-DATE has no error path of its own and the Java translation must "
                            + "not swallow one silently.", SWEEP_CANDIDATES.size())
                    .isEmpty();
        }

        @Test
        @DisplayName("An under-length date reaches the insufficient-data condition through the absent "
                + "position scan, never through a length comparison, because both inputs are fixed to "
                + "their declared widths before CEEDAYS is called")
        void insufficientDataIsReachedByThePositionScanNotByLength() {
            final DateValidationService service = service();

            final DateValidationResult shortPacked = service.validate("20240",
                    DateValidationService.MASK_YYYYMMDD);
            final DateValidationResult shortSeparated = service.validate("2024-",
                    DateValidationService.MASK_YYYY_MM_DD);
            final DateValidationResult exactWidth = service.validate("20240131",
                    DateValidationService.MASK_YYYYMMDD);
            final DateValidationResult overWidth = service.validate("20240131XXXXX",
                    DateValidationService.MASK_YYYYMMDD);

            assertThat(shortPacked.feedbackCode().condition())
                    .as("app/cbl/CSUTLDTC.cbl:L105-L106 MOVEs LENGTH OF LS-DATE, the constant ten, so the "
                            + "value handed to CEEDAYS is always ten characters; a five-character date "
                            + "arrives padded and is rejected because positions six to eight are spaces")
                    .contains(FeedbackCondition.FC_INSUFFICIENT_DATA);
            assertThat(shortSeparated.feedbackCode().condition())
                    .as("the same holds for the separated mask, whose ten significant positions are all "
                            + "required")
                    .contains(FeedbackCondition.FC_INSUFFICIENT_DATA);
            assertThat(overWidth)
                    .as("an over-length date is truncated to the same ten characters, so it cannot be "
                            + "shorter than the mask either; that unconditional ten is what makes the "
                            + "length comparison inside the CEEDAYS stand-in unreachable, and it is kept "
                            + "because CEEDAYS itself declares that condition")
                    .isEqualTo(exactWidth);
        }
    }
}
