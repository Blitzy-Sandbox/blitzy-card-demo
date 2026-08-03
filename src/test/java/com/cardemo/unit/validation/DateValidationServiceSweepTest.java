/*
 * ******************************************************************
 * Program     : DateValidationServiceSweepTest.java
 * Component   : Unit test tier - validation suite, resident at
 *               src/test/java/com/cardemo/unit/validation
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM, no container, no Spring
 *               context, no database
 * Function    : Holds com.cardemo.service.shared.DateValidationService
 *               to the documented input domain of the IBM Language
 *               Environment CEEDAYS callable service for both picture
 *               strings the frozen corpus declares: leading blanks are
 *               skipped, leading zeroes may be omitted from the month
 *               and the day when and only when the picture carries
 *               delimiters, characters after a parsed date are
 *               ignored, and the representable range runs from
 *               15 October 1582 to 31 December 9999. Proves that all
 *               nine declared feedback outcomes survive, that no input
 *               previously rejected as a date is now accepted unless
 *               it denotes a real representable date, and that the
 *               delimited omitted form and the delimited padded form
 *               of the same date always agree.
 * Source      : app/cbl/CSUTLDTC.cbl:25-31,42-70,84-86,105-124,128-149
 *               + app/cpy/CSUTLDWY.cpy:5-34,58-59
 *               + app/cpy/CSUTLDPY.cpy:25-87,91-144,150-204,209-279,
 *                 284-321
 *               + app/cbl/COTRN02C.cbl:60,62-69,389,393,399-401,409,
 *                 413,419-421
 *               + app/cbl/CORPT00C.cbl:60-72,129-136,381-386,388,392,
 *                 398-400,408,412,418-420
 *               + app/cpy-bms/COTRN02.CPY:97-108 @ 7756d89
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

import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.DateValidationService.DateValidationResult;
import com.cardemo.service.shared.DateValidationService.FeedbackCode;
import com.cardemo.service.shared.DateValidationService.FeedbackCondition;
import com.cardemo.unit.model.FixedClockProvider;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit test for the {@code CEEDAYS} input domain of {@link DateValidationService}.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>{@link DateValidationService#validate(String, String)} stands in for
 * {@code CALL 'CSUTLDTC'}, which itself wraps the IBM Language Environment callable service
 * {@code CEEDAYS} at {@code app/cbl/CSUTLDTC.cbl:L116-L120}. That service does <strong>not</strong>
 * read its date argument at fixed offsets. Its documented input domain has four rules, and this class
 * asserts every one of them for both picture strings the frozen corpus declares:
 *
 * <ol>
 *   <li><strong>Parsing begins at the first non-blank character.</strong> Corroborated by
 *       {@code app/cbl/CSUTLDTC.cbl:L105-L106}, which moves {@code LENGTH OF LS-DATE} into
 *       {@code VSTRING-LENGTH} - the constant ten, the field's declared width, never a trimmed
 *       length. The service is therefore always handed ten bytes however few the caller
 *       populated.</li>
 *   <li><strong>Leading zeroes may be omitted from the month and the day when, and only when, the
 *       picture carries delimiters.</strong> Of the two masks in the corpus only
 *       {@code YYYY-MM-DD} qualifies - declared {@code PIC X(10)} at
 *       {@code app/cbl/CORPT00C.cbl:L72} and {@code app/cbl/COTRN02C.cbl:L60}. {@code YYYYMMDD},
 *       declared {@code PIC X(08)} at {@code app/cpy/CSUTLDWY.cpy:L58-L59}, has no delimiter and so
 *       requires all eight digits.</li>
 *   <li><strong>After a valid date is parsed, every remaining character is ignored</strong> -
 *       whatever it is, not merely a blank. Corroborated by the copybook call path existing at all:
 *       {@code app/cpy/CSUTLDPY.cpy:L293} passes the eight byte group
 *       {@code WS-EDIT-DATE-CCYYMMDD} into a {@code PIC X(10)} linkage item whose length is declared
 *       as ten, so bytes nine and ten are adjacent storage and are read, and the call nevertheless
 *       succeeds.</li>
 *   <li><strong>Representable dates run from 15 October 1582 through 31 December 9999.</strong></li>
 * </ol>
 *
 * <p>Why this matters rather than being pedantry: {@code app/cbl/COTRN02C.cbl:L389} and {@code :L409}
 * move {@code TORIGDTI} and {@code TPROCDTI} - single free form {@code PIC X(10)} screen fields
 * declared at {@code app/cpy-bms/COTRN02.CPY:L102} and {@code :L108} - <em>directly</em> into
 * {@code CSUTLDTC-DATE} under the delimited mask. A terminal operator who types {@code 2022-6-1}
 * into one of them produces an input that carries an omitted month zero, an omitted day zero and two
 * trailing blanks at once. A fixed offset reading rejects that input; the service accepts it.
 *
 * <p>By contrast {@code app/cbl/CORPT00C.cbl:L60-L71} assembles its two dates <em>positionally</em>,
 * as {@code X(04)} then a literal hyphen then {@code X(02)} then a literal hyphen then {@code X(02)},
 * filled from six discrete screen fields at {@code :L381-L386}. That group is always exactly ten
 * bytes with the hyphens fixed, so a half typed component there produces an <em>embedded</em> blank
 * rather than an omitted zero. Both behaviours are asserted: the first is accepted, the second is
 * rejected.
 *
 * <h2>2. The four movements</h2>
 *
 * <ol>
 *   <li><strong>The domain boundary is exhaustive and tabulated.</strong> Every accepted form and
 *       every rejected form named in the {@code callCeedays} documentation is asserted with its exact
 *       severity, message number and fifteen character result string. Blanks are carried in Java
 *       string literals through {@code @MethodSource} rather than {@code @CsvSource}, because
 *       {@code @CsvSource} trims leading and trailing whitespace by default and would silently
 *       destroy the very characters under test.</li>
 *   <li><strong>Nothing is loosened.</strong> Two rejections are deliberately reclassified from non
 *       numeric data to the condition that names their real defect, and both remain rejections. The
 *       safety property - acceptance widens only to inputs denoting a real representable date - is
 *       asserted over a bounded exhaustive sweep rather than a sample.</li>
 *   <li><strong>The omitted and padded forms agree.</strong> A differential assertion needing no
 *       oracle at all: across the same sweep, the delimited omitted form and the delimited padded
 *       form of every component triple produce the same acceptance and the same message number. This
 *       is the documented rule's own formulation, whose published examples assert that the omitted
 *       and padded spellings "would all assign the same value".</li>
 *   <li><strong>All nine feedback outcomes survive.</strong> Every constant keeps its exact severity,
 *       message number and result text including deliberate trailing spaces; eight are reached from
 *       real input; the ninth is reached through the public resolve path and its unreachability from
 *       input is proved structurally rather than asserted.</li>
 *   </ol>
 *
 * <h2>3. How to build, run and test</h2>
 *
 * <pre>
 * ./mvnw -B -ntp -o test -Dtest=DateValidationServiceSweepTest   # this class alone
 * ./mvnw -B -ntp clean test                                 # the whole unit tier
 * ./mvnw -B -ntp clean verify                               # unit tier plus coverage and dependency gates
 * </pre>
 *
 * <p>{@code maven-surefire-plugin} collects {@code **}{@code /*Test.java} outside the
 * {@code integration} and {@code e2e} package trees, so this class runs at the {@code test} phase.
 * Confirm the report exists rather than trusting the exit code, because a class collected by neither
 * plugin produces a green build with no error and no warning anywhere:
 *
 * <pre>
 * target/surefire-reports/TEST-com.cardemo.unit.validation.DateValidationServiceSweepTest.xml
 * </pre>
 *
 * <h2>4. Key configuration and defaults</h2>
 *
 * <p>No property, no fixture file, no network, no database and no container. The one collaborator
 * {@link DateValidationService} requires is a {@link java.time.Clock}, and although the
 * {@code CEEDAYS} path never reads it, {@link FixedClockProvider#canonicalClock()} is used rather
 * than an ambient clock so that this class cannot acquire a wall clock dependency by accident.
 *
 * <h2>5. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>A blank bearing case suddenly passes for the wrong reason.</strong> Symptom: a row
 *       that should be rejected for a blank is accepted. Cause: the literal lost its blanks, almost
 *       always by being moved into a {@code @CsvSource}. Remedy: keep every blank bearing input in a
 *       Java string literal supplied through {@code @MethodSource}.</li>
 *   <li><strong>The leap day rows fail.</strong> Symptom: {@code 2020-2-29} rejected or
 *       {@code 2022-2-29} accepted. Cause: the two years have been transposed. 2020 is a leap year
 *       and 2022 is not, which is exactly why both rows exist.</li>
 *   <li><strong>The sweep fails only for the undelimited mask.</strong> Symptom: an omitted form is
 *       accepted where the expectation is rejection. Cause: the delimiter precondition has been
 *       dropped, so omission is being honoured for a picture that cannot disambiguate it. Remedy: the
 *       permission is conditional on the picture carrying delimiters.</li>
 *   </ul>
 */
@DisplayName("DateValidationService: the documented CEEDAYS input domain")
class DateValidationServiceSweepTest {

    /** The delimited picture, the only one of the two that licenses omitted leading zeroes. */
    private static final String DELIMITED = DateValidationService.MASK_YYYY_MM_DD;

    /** The undelimited picture, which requires all eight digits. */
    private static final String UNDELIMITED = DateValidationService.MASK_YYYYMMDD;

    /** The rendered severity of the success token, {@code app/cbl/CSUTLDTC.cbl:L62}. */
    private static final String SEVERITY_OK = "0000";

    /** The rendered severity every rejecting token carries, {@code app/cbl/CSUTLDTC.cbl:L63-L70}. */
    private static final String SEVERITY_REJECTED = "0003";

    /** First representable day, the day after Lillian day zero. */
    private static final LocalDate FIRST_REPRESENTABLE = LocalDate.of(1582, 10, 15);

    /** Last representable day. */
    private static final LocalDate LAST_REPRESENTABLE = LocalDate.of(9999, 12, 31);

    /** Years chosen to straddle every boundary the range and the leap rules define. */
    private static final int[] SWEEP_YEARS = {0, 1581, 1582, 1583, 1900, 2000, 2020, 2022, 9999};

    /** The service under test. The clock is never read on the {@code CEEDAYS} path. */
    private final DateValidationService service =
            new DateValidationService(FixedClockProvider.canonicalClock());

    /**
     * Computes, independently of the service, whether a component triple denotes a real representable
     * date.
     *
     * <p>This is the oracle for the safety property. It is materialised from the three integers, never
     * from the string under test, so it cannot agree with the service by construction: the mapping
     * from characters to integers is precisely what the parser change alters, and this method never
     * sees a character.
     *
     * @param year  the four digit year
     * @param month the month
     * @param day   the day
     * @return {@code true} when the triple is a real date within the representable range
     */
    private static boolean realAndRepresentable(final int year, final int month, final int day) {
        if (year < 1 || month < 1 || month > 12 || day < 1) {
            return false;
        }
        final LocalDate date;
        try {
            date = LocalDate.of(year, month, day);
        } catch (final DateTimeException notARealDate) {
            return false;
        }
        return !date.isBefore(FIRST_REPRESENTABLE) && !date.isAfter(LAST_REPRESENTABLE);
    }

    /**
     * Renders a condition's result literal as the fifteen character field will carry it.
     *
     * <p>The distinction is easy to get wrong and worth stating, because it costs a false test
     * failure. {@code app/cbl/CSUTLDTC.cbl:L128-L149} moves ten literals into
     * {@code WS-RESULT PIC X(15)}. Eight of the ten are already fifteen characters, five of those
     * because they carry deliberate trailing spaces inside their COBOL quotes. The remaining two -
     * {@code 'Date is valid'} at thirteen characters and {@code 'Insufficient'} at twelve - are
     * padded on the right by the {@code MOVE} itself, since an alphanumeric receiving field is filled
     * from the left and space filled. The enum therefore holds the <em>literal</em>, while
     * {@code DateValidationResult.resultText()} returns the <em>field</em>. Both are correct and they
     * differ for exactly those two conditions.
     *
     * @param condition the condition whose field rendering is wanted
     * @return the literal padded on the right to fifteen characters
     */
    private static String asFifteenCharacterField(final FeedbackCondition condition) {
        final String literal = condition.resultText();
        return literal + " ".repeat(DateValidationService.RESULT_TEXT_LENGTH - literal.length());
    }

    /**
     * Every form the documentation records as accepted, with the mask it is read under.
     *
     * @return the accepted rows
     */
    private static Stream<Arguments> acceptedForms() {
        return Stream.of(
                Arguments.of("2022-06-10", DELIMITED, "fully padded, accepted before this change too"),
                Arguments.of("2022-6-1  ", DELIMITED, "omitted month and day zeroes, trailing blanks"),
                Arguments.of("2022-06-1 ", DELIMITED, "omitted day zero only"),
                Arguments.of("2022-6-01 ", DELIMITED, "omitted month zero only"),
                Arguments.of("  2022-6-1", DELIMITED, "leading blanks then omitted zeroes"),
                Arguments.of(" 2022-06-1", DELIMITED, "one leading blank, one omitted zero"),
                Arguments.of("2020-2-29 ", DELIMITED, "a real leap day in an omitted form"),
                Arguments.of("1582-10-15", DELIMITED, "first representable day"),
                Arguments.of("9999-12-31", DELIMITED, "last representable day"),
                Arguments.of("20220610  ", UNDELIMITED, "eight digits, trailing blanks"),
                Arguments.of(" 20220610 ", UNDELIMITED, "leading and trailing blanks"),
                Arguments.of(" 20220610X", UNDELIMITED, "leading blank and non blank trailing"),
                Arguments.of("15821015  ", UNDELIMITED, "first representable day"),
                Arguments.of("99991231  ", UNDELIMITED, "last representable day"));
    }

    /**
     * Every form the documentation records as rejected, with the condition it must report.
     *
     * @return the rejected rows
     */
    private static Stream<Arguments> rejectedForms() {
        return Stream.of(
                Arguments.of("          ", DELIMITED, FeedbackCondition.FC_INSUFFICIENT_DATA,
                        "nothing but blanks, so no component was supplied"),
                Arguments.of("          ", UNDELIMITED, FeedbackCondition.FC_INSUFFICIENT_DATA,
                        "nothing but blanks under the undelimited picture"),
                Arguments.of("2022-6 -10", DELIMITED, FeedbackCondition.FC_INSUFFICIENT_DATA,
                        "blank where the separator is required - the CORPT00C positional form"),
                Arguments.of("2022- 6-10", DELIMITED, FeedbackCondition.FC_INSUFFICIENT_DATA,
                        "blank where a digit is required - the CORPT00C positional form"),
                Arguments.of("2022-06   ", DELIMITED, FeedbackCondition.FC_INSUFFICIENT_DATA,
                        "the day component was never supplied"),
                Arguments.of("2022      ", DELIMITED, FeedbackCondition.FC_INSUFFICIENT_DATA,
                        "the year alone, with no separator"),
                Arguments.of("202206    ", UNDELIMITED, FeedbackCondition.FC_INSUFFICIENT_DATA,
                        "six digits of the eight the undelimited picture requires"),
                Arguments.of("2022061   ", UNDELIMITED, FeedbackCondition.FC_INSUFFICIENT_DATA,
                        "seven digits: omission is not licensed without delimiters"),
                Arguments.of("2022/06/10", DELIMITED, FeedbackCondition.FC_NON_NUMERIC_DATA,
                        "the wrong separator, present and not blank"),
                Arguments.of("20226-1   ", DELIMITED, FeedbackCondition.FC_NON_NUMERIC_DATA,
                        "a digit where the separator is required"),
                Arguments.of("22-6-1    ", DELIMITED, FeedbackCondition.FC_NON_NUMERIC_DATA,
                        "omission is deliberately not extended to the year"),
                Arguments.of("2022--10  ", DELIMITED, FeedbackCondition.FC_NON_NUMERIC_DATA,
                        "an empty month component blocked by a non blank character"),
                Arguments.of("2O220610  ", UNDELIMITED, FeedbackCondition.FC_NON_NUMERIC_DATA,
                        "a letter O for a zero inside the year"),
                Arguments.of("2022-6-1XY", DELIMITED, FeedbackCondition.FC_NON_NUMERIC_DATA,
                        "non blank residue still INSIDE the ten character picture width"),
                Arguments.of("2022-6-1-9", DELIMITED, FeedbackCondition.FC_NON_NUMERIC_DATA,
                        "a separator and a digit occupying the last two picture positions"),
                Arguments.of("0000-06-10", DELIMITED, FeedbackCondition.FC_YEAR_IN_ERA_ZERO,
                        "a year of zero has its own declared condition"),
                Arguments.of("00000610  ", UNDELIMITED, FeedbackCondition.FC_YEAR_IN_ERA_ZERO,
                        "a year of zero under the undelimited picture"),
                Arguments.of("2022-13-01", DELIMITED, FeedbackCondition.FC_INVALID_MONTH,
                        "a month above twelve"),
                Arguments.of("20221301  ", UNDELIMITED, FeedbackCondition.FC_INVALID_MONTH,
                        "a month above twelve under the undelimited picture"),
                Arguments.of("2022-02-30", DELIMITED, FeedbackCondition.FC_BAD_DATE_VALUE,
                        "thirty days in February"),
                Arguments.of("2022-2-29 ", DELIMITED, FeedbackCondition.FC_BAD_DATE_VALUE,
                        "2022 is not a leap year - contrast the accepted 2020-2-29"),
                Arguments.of("1582-10-14", DELIMITED, FeedbackCondition.FC_UNSUPP_RANGE,
                        "Lillian day zero itself, one day before the first representable day"),
                Arguments.of("1581-12-31", DELIMITED, FeedbackCondition.FC_UNSUPP_RANGE,
                        "a real date wholly before the representable range"));
    }

    /**
     * Masks that are not one of the two the corpus declares.
     *
     * @return the bad picture rows
     */
    private static Stream<Arguments> badPictures() {
        return Stream.of(
                Arguments.of("2022-06-10", "MM/DD/YY  ", "a picture the corpus never declares"),
                Arguments.of("2022-06-10", "YYYY/MM/DD", "the right components, the wrong separator"),
                Arguments.of("2022-06-10", " YYYY-MM-D", "a picture that itself begins with a blank"),
                Arguments.of("20220610  ", "          ", "an all blank picture"),
                Arguments.of("2022-06-10", "yyyy-mm-dd", "the right picture in the wrong case"));
    }

    /** The four documented rules of the input domain, asserted one at a time. */
    @Nested
    @DisplayName("The four documented rules")
    class DocumentedRules {

        @Test
        @DisplayName("Rule 1: parsing begins at the first non-blank character, under both pictures")
        void leadingBlanksAreSkipped() {
            // The date is identical in all four; only the leading blank count differs. Under a fixed
            // offset reading every blank bearing spelling is rejected.
            assertThat(service.validate("2022-06-10", DELIMITED).valid()).isTrue();
            assertThat(service.validate("  2022-6-1", DELIMITED).valid()).isTrue();
            assertThat(service.validate("20220610  ", UNDELIMITED).valid()).isTrue();
            assertThat(service.validate("  20220610", UNDELIMITED).valid()).isTrue();
        }

        @Test
        @DisplayName("Rule 1: a field of nothing but blanks supplied no component at all")
        void anAllBlankFieldIsInsufficientRatherThanNonNumeric() {
            final DateValidationResult result = service.validate("          ", DELIMITED);

            assertThat(result.valid()).isFalse();
            assertThat(result.feedbackCode().condition())
                    .contains(FeedbackCondition.FC_INSUFFICIENT_DATA);
        }

        @Test
        @DisplayName("Rule 1: a null argument is absorbed as an unpopulated field, never thrown")
        void aNullDateIsAbsorbedAsAnUnpopulatedField() {
            final DateValidationResult result = service.validate(null, DELIMITED);

            assertThat(result.valid()).isFalse();
            assertThat(result.feedbackCode().condition())
                    .contains(FeedbackCondition.FC_INSUFFICIENT_DATA);
        }

        @Test
        @DisplayName("Rule 2: omitted zeroes are licensed by the delimited picture")
        void omittedZeroesAreAcceptedUnderTheDelimitedPicture() {
            assertThat(service.validate("2022-6-1  ", DELIMITED).valid()).isTrue();
            assertThat(service.validate("2022-6-01 ", DELIMITED).valid()).isTrue();
            assertThat(service.validate("2022-06-1 ", DELIMITED).valid()).isTrue();
        }

        @Test
        @DisplayName("Rule 2: the same omission is refused by the undelimited picture")
        void omittedZeroesAreRefusedUnderTheUndelimitedPicture() {
            // 2022-06-01 spelled without delimiters and without the two leading zeroes is 202261,
            // which is six digits where the picture requires eight. There is no delimiter to
            // disambiguate it, which is exactly why the permission is conditional.
            assertThat(service.validate("202261    ", UNDELIMITED).valid()).isFalse();
            assertThat(service.validate("2022061   ", UNDELIMITED).valid()).isFalse();
            assertThat(service.validate("202206010 ", UNDELIMITED).valid()).isTrue();
        }

        @Test
        @DisplayName("Rule 2: omission is deliberately not extended to the year")
        void omissionIsNotExtendedToTheYear() {
            // A year shortened by omission can only denote 999 or less, which the representable range
            // rejects anyway, so widening it would add no accepted date while asserting undocumented
            // behaviour. The hyphen in the third year position is a present, non blank, wrong
            // character, so the outcome is non numeric data rather than insufficient data.
            final DateValidationResult result = service.validate("22-6-1    ", DELIMITED);

            assertThat(result.valid()).isFalse();
            assertThat(result.feedbackCode().condition())
                    .contains(FeedbackCondition.FC_NON_NUMERIC_DATA);
        }

        @Test
        @DisplayName("Rule 3: residue is ignored only BEYOND the picture width, never inside it")
        void trailingCharactersAreIgnoredOnlyBeyondThePictureWidth() {
            // The boundary is the picture width, not the end of the parse. LS-DATE is PIC X(10) at
            // app/cbl/CSUTLDTC.cbl:L84 and the length handed to the service is unconditionally
            // LENGTH OF LS-DATE at :L105-L106, while the caller at app/cpy/CSUTLDPY.cpy:L293-L294
            // passes an eight byte field under the eight character picture set at :L291. So the two
            // bytes past an eight character picture are adjacent storage the caller never supplied and
            // must be ignored, whereas every position inside the picture is the caller's own data.

            // Blanks inside the width are absent positions the omitted zero rule already licenses.
            assertThat(service.validate("2022-6-1  ", DELIMITED).valid()).isTrue();
            assertThat(service.validate("20220610  ", UNDELIMITED).valid()).isTrue();

            // Non blank residue inside the ten character delimited width is nonnumeric data.
            assertThat(service.validate("2022-6-1XY", DELIMITED).feedbackCode().condition())
                    .contains(FeedbackCondition.FC_NON_NUMERIC_DATA);
            assertThat(service.validate("2022-6-1-9", DELIMITED).feedbackCode().condition())
                    .contains(FeedbackCondition.FC_NON_NUMERIC_DATA);

            // Non blank residue BEYOND the eight character undelimited width is the adjacent storage
            // case and is ignored, which is what keeps every CSUTLDPY call site working.
            assertThat(service.validate("20220610XY", UNDELIMITED).valid()).isTrue();
            assertThat(service.validate(" 20220610X", UNDELIMITED).valid()).isTrue();
        }

        @Test
        @DisplayName("Rule 3: a component is capped at its picture width and cannot swallow the next")
        void aComponentNeverConsumesTheFollowingOne() {
            // The day is capped at two digits, so 2022-6-12 reads day 12 rather than swallowing more.
            assertThat(service.validate("2022-6-12 ", DELIMITED).valid()).isTrue();
            // The cap is proved by contrast rather than by tolerance: with a third digit present the
            // day is still read as 12, and the leftover 3 is then rejected as residue inside the
            // picture width. A greedy read would instead have reported a bad day VALUE of 123.
            assertThat(service.validate("2022-6-123", DELIMITED).feedbackCode().condition())
                    .contains(FeedbackCondition.FC_NON_NUMERIC_DATA);
            // A five digit year cannot borrow from the separator position either: the year stops at
            // four and the fifth digit is then found where the separator is required.
            assertThat(service.validate("20220-6-1 ", DELIMITED).feedbackCode().condition())
                    .contains(FeedbackCondition.FC_NON_NUMERIC_DATA);
        }

        @Test
        @DisplayName("Rule 4: the representable range boundaries are exact on both sides")
        void theRepresentableRangeBoundariesAreExact() {
            assertThat(service.validate("1582-10-14", DELIMITED).feedbackCode().condition())
                    .contains(FeedbackCondition.FC_UNSUPP_RANGE);
            assertThat(service.validate("1582-10-15", DELIMITED).valid()).isTrue();
            assertThat(service.validate("9999-12-31", DELIMITED).valid()).isTrue();
            assertThat(service.validate("15821014  ", UNDELIMITED).feedbackCode().condition())
                    .contains(FeedbackCondition.FC_UNSUPP_RANGE);
            assertThat(service.validate("15821015  ", UNDELIMITED).valid()).isTrue();
        }

        @Test
        @DisplayName("Rule 4: the tolerated message number 2513 is what the range failure reports")
        void theRangeFailureReportsTheOneMessageNumberEveryCallerTolerates() {
            // app/cbl/COTRN02C.cbl:L400 and :L420, and app/cbl/CORPT00C.cbl:L399 and :L419, all read
            // IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513' before reporting a failure. A date rejected only
            // for being outside the range therefore passes those four call sites.
            final DateValidationResult result = service.validate("1582-10-14", DELIMITED);

            assertThat(result.severityCode()).isEqualTo(SEVERITY_REJECTED);
            assertThat(result.messageNumber()).isEqualTo("2513");
        }
    }

    /** The tabulated domain boundary, asserted row by row. */
    @Nested
    @DisplayName("The domain boundary, row by row")
    class DomainBoundary {

        @ParameterizedTest(name = "[{index}] \"{0}\" under {1} is accepted: {2}")
        @MethodSource("com.cardemo.unit.validation.DateValidationServiceSweepTest#acceptedForms")
        @DisplayName("Every accepted form reports the success token exactly")
        void acceptedFormsReportTheSuccessToken(final String date, final String mask,
                                                final String why) {
            final DateValidationResult result = service.validate(date, mask);

            assertThat(result.valid()).as("accepted because %s", why).isTrue();
            assertThat(result.severityCode()).isEqualTo(SEVERITY_OK);
            assertThat(result.messageNumber()).isEqualTo(SEVERITY_OK);
            assertThat(result.returnCode()).isZero();
            assertThat(result.resultText())
                    .isEqualTo(asFifteenCharacterField(FeedbackCondition.FC_INVALID_DATE))
                    .isEqualTo("Date is valid  ");
            assertThat(result.feedbackCode().condition())
                    .contains(FeedbackCondition.FC_INVALID_DATE);
        }

        @ParameterizedTest(name = "[{index}] \"{0}\" under {1} reports {2}: {3}")
        @MethodSource("com.cardemo.unit.validation.DateValidationServiceSweepTest#rejectedForms")
        @DisplayName("Every rejected form reports its exact declared condition")
        void rejectedFormsReportTheirExactCondition(final String date, final String mask,
                                                    final FeedbackCondition expected,
                                                    final String why) {
            final DateValidationResult result = service.validate(date, mask);

            assertThat(result.valid()).as("rejected because %s", why).isFalse();
            assertThat(result.severityCode()).isEqualTo(SEVERITY_REJECTED);
            assertThat(result.feedbackCode().condition()).contains(expected);
            assertThat(result.messageNumber())
                    .isEqualTo("%04d".formatted(expected.messageNumber()));
            assertThat(result.resultText()).isEqualTo(asFifteenCharacterField(expected));
        }

        @ParameterizedTest(name = "[{index}] mask \"{1}\" is a bad picture: {2}")
        @MethodSource("com.cardemo.unit.validation.DateValidationServiceSweepTest#badPictures")
        @DisplayName("An unrecognised picture is rejected before the date is read at all")
        void anUnrecognisedPictureIsRejectedFirst(final String date, final String mask,
                                                  final String why) {
            final DateValidationResult result = service.validate(date, mask);

            assertThat(result.valid()).as("bad picture because %s", why).isFalse();
            assertThat(result.feedbackCode().condition())
                    .contains(FeedbackCondition.FC_BAD_PIC_STRING);
        }

        @Test
        @DisplayName("The two corpus pictures are the complete accepted census")
        void onlyTheTwoCorpusPicturesAreRecognised() {
            assertThat(DELIMITED).isEqualTo("YYYY-MM-DD");
            assertThat(UNDELIMITED).isEqualTo("YYYYMMDD");
            // Trailing blanks on the picture are insignificant, because both masks are declared
            // narrower than the PIC X(10) field that carries them on the program call path.
            assertThat(service.validate("2022-06-10", "YYYY-MM-DD").valid()).isTrue();
            assertThat(service.validate("20220610  ", "YYYYMMDD  ").valid()).isTrue();
        }

        @Test
        @DisplayName("The eighty byte result area is unaffected by the parser change")
        void theResultAreaKeepsItsGeometry() {
            for (final String date : List.of("2022-6-1  ", "  2022-6-1", "2022-6 -10")) {
                final DateValidationResult result = service.validate(date, DELIMITED);

                assertThat(result.result()).as("area for \"%s\"", date).hasSize(80);
                assertThat(result.resultText()).as("result text for \"%s\"", date).hasSize(15);
                assertThat(result.messageText()).as("message text for \"%s\"", date).hasSize(61);
            }
        }
    }

    /** The two deliberate reclassifications, and the proof that neither is a loosening. */
    @Nested
    @DisplayName("Reclassified outcomes, still rejected")
    class ReclassifiedOutcomes {

        @Test
        @DisplayName("A month of zero now reports invalid month instead of non numeric data")
        void aZeroMonthReportsTheConditionThatNamesIt() {
            // Under the fixed offset reading the hyphen at the second month position produced non
            // numeric data and the value was never reached. The form is now readable, so the value
            // specific condition is reported. It is still a rejection at severity three.
            final DateValidationResult result = service.validate("2022-0-01 ", DELIMITED);

            assertThat(result.valid()).isFalse();
            assertThat(result.severityCode()).isEqualTo(SEVERITY_REJECTED);
            assertThat(result.feedbackCode().condition())
                    .contains(FeedbackCondition.FC_INVALID_MONTH);
        }

        @Test
        @DisplayName("An impossible day now reports bad date value instead of non numeric data")
        void anImpossibleDayReportsTheConditionThatNamesIt() {
            final DateValidationResult result = service.validate("2022-6-31 ", DELIMITED);

            assertThat(result.valid()).isFalse();
            assertThat(result.severityCode()).isEqualTo(SEVERITY_REJECTED);
            assertThat(result.feedbackCode().condition())
                    .contains(FeedbackCondition.FC_BAD_DATE_VALUE);
        }

        @Test
        @DisplayName("Both reclassifications keep the rejection and only sharpen the message number")
        void reclassificationNeverTurnsARejectionIntoAnAcceptance() {
            for (final String date : List.of("2022-0-01 ", "2022-6-31 ", "2022-2-29 ")) {
                final DateValidationResult result = service.validate(date, DELIMITED);

                assertThat(result.valid()).as("\"%s\" must stay rejected", date).isFalse();
                assertThat(result.returnCode()).as("severity for \"%s\"", date).isEqualTo(3);
            }
        }
    }

    /**
     * The safety property and the differential property, over a bounded exhaustive sweep rather than a
     * sample.
     */
    @Nested
    @DisplayName("Bounded exhaustive sweep")
    class BoundedExhaustiveSweep {

        @Test
        @DisplayName("Acceptance holds exactly when the components denote a real representable date")
        void acceptanceCoincidesWithTheIndependentOracle() {
            final List<String> divergences = new ArrayList<>();

            for (final int year : SWEEP_YEARS) {
                for (int month = 0; month <= 13; month++) {
                    for (int day = 0; day <= 32; day++) {
                        final boolean expected = realAndRepresentable(year, month, day);
                        final String padded = "%04d-%02d-%02d".formatted(year, month, day);
                        final String omitted = "%04d-%d-%d".formatted(year, month, day);
                        final String undelimited = "%04d%02d%02d".formatted(year, month, day);

                        collectDivergence(divergences, padded, DELIMITED, expected);
                        collectDivergence(divergences, omitted, DELIMITED, expected);
                        collectDivergence(divergences, undelimited, UNDELIMITED, expected);
                    }
                }
            }

            assertThat(divergences).as("inputs whose acceptance disagreed with the oracle").isEmpty();
        }

        @Test
        @DisplayName("The omitted and padded delimited spellings always agree, with no oracle at all")
        void theOmittedAndPaddedSpellingsAgree() {
            final List<String> divergences = new ArrayList<>();

            for (final int year : SWEEP_YEARS) {
                for (int month = 0; month <= 13; month++) {
                    for (int day = 0; day <= 32; day++) {
                        final DateValidationResult padded = service.validate(
                                "%04d-%02d-%02d".formatted(year, month, day), DELIMITED);
                        final DateValidationResult omitted = service.validate(
                                "%04d-%d-%d".formatted(year, month, day), DELIMITED);

                        if (padded.valid() != omitted.valid()
                                || !padded.messageNumber().equals(omitted.messageNumber())) {
                            divergences.add("%04d-%02d-%02d padded=%s/%s omitted=%s/%s".formatted(
                                    year, month, day, padded.valid(), padded.messageNumber(),
                                    omitted.valid(), omitted.messageNumber()));
                        }
                    }
                }
            }

            assertThat(divergences).as("spellings of the same date that disagreed").isEmpty();
        }

        @Test
        @DisplayName("Omission without delimiters is refused across the whole sweep")
        void omissionWithoutDelimitersIsRefusedThroughout() {
            final List<String> wronglyAccepted = new ArrayList<>();

            for (final int year : SWEEP_YEARS) {
                for (int month = 0; month <= 13; month++) {
                    for (int day = 0; day <= 32; day++) {
                        // Concatenating unpadded components under the undelimited picture only
                        // reproduces the eight digit form when both components already occupy two
                        // characters; every other spelling is short and must be refused.
                        final String spelled = "%04d%d%d".formatted(year, month, day);
                        final boolean acceptable = month >= 10 && day >= 10
                                && realAndRepresentable(year, month, day);

                        if (service.validate(spelled, UNDELIMITED).valid() != acceptable) {
                            wronglyAccepted.add(spelled);
                        }
                    }
                }
            }

            assertThat(wronglyAccepted).as("undelimited spellings classified wrongly").isEmpty();
        }

        @Test
        @DisplayName("No sweep input escapes the nine declared tokens")
        void everySweepOutcomeIsOneOfTheNineDeclaredTokens() {
            final Set<FeedbackCondition> observed = EnumSet.noneOf(FeedbackCondition.class);
            final List<String> unresolved = new ArrayList<>();

            for (final int year : SWEEP_YEARS) {
                for (int month = 0; month <= 13; month++) {
                    for (int day = 0; day <= 32; day++) {
                        for (final String spelled : List.of(
                                "%04d-%02d-%02d".formatted(year, month, day),
                                "%04d-%d-%d".formatted(year, month, day))) {
                            final DateValidationResult result = service.validate(spelled, DELIMITED);
                            result.feedbackCode().condition()
                                    .ifPresentOrElse(observed::add, () -> unresolved.add(spelled));
                        }
                    }
                }
            }

            assertThat(unresolved).as("inputs that produced no declared token").isEmpty();
            // Every spelling in the sweep is well formed under the delimited picture - both the padded
            // and the omitted one - so no FORM error may arise from any of them. That the two malformed
            // input conditions are absent here is therefore a positive statement of the fix: the cursor
            // based scan reads every well formed omitted spelling successfully, and only the value
            // classifications remain. The two malformed conditions are covered by rejectedForms(), the
            // bad picture condition by badPictures(), and the era condition is structurally unreachable.
            assertThat(observed).containsExactlyInAnyOrder(
                    FeedbackCondition.FC_INVALID_DATE,
                    FeedbackCondition.FC_BAD_DATE_VALUE,
                    FeedbackCondition.FC_UNSUPP_RANGE,
                    FeedbackCondition.FC_INVALID_MONTH,
                    FeedbackCondition.FC_YEAR_IN_ERA_ZERO);
            assertThat(observed).doesNotContain(
                    FeedbackCondition.FC_INSUFFICIENT_DATA,
                    FeedbackCondition.FC_NON_NUMERIC_DATA,
                    FeedbackCondition.FC_BAD_PIC_STRING,
                    FeedbackCondition.FC_INVALID_ERA);
        }

        /**
         * Records a divergence between the service and the independent oracle, if there is one.
         *
         * @param divergences the accumulating report
         * @param spelled     the input handed to the service
         * @param mask        the picture it is read under
         * @param expected    what the oracle says
         */
        private void collectDivergence(final List<String> divergences, final String spelled,
                                       final String mask, final boolean expected) {
            if (service.validate(spelled, mask).valid() != expected) {
                divergences.add("\"%s\" under %s expected=%s".formatted(spelled, mask, expected));
            }
        }
    }

    /** All nine declared feedback outcomes, and the reachability of each. */
    @Nested
    @DisplayName("The nine declared feedback outcomes")
    class NineFeedbackOutcomes {

        @Test
        @DisplayName("Exactly nine constants, with byte exact severity, number and result text")
        void theNineConstantsKeepTheirExactValues() {
            assertThat(FeedbackCondition.values()).hasSize(9);

            assertThat(FeedbackCondition.FC_INVALID_DATE.severity()).isZero();
            assertThat(FeedbackCondition.FC_INVALID_DATE.messageNumber()).isZero();
            assertThat(FeedbackCondition.FC_INVALID_DATE.resultText()).isEqualTo("Date is valid");

            // The five literals carrying deliberate trailing spaces inside their COBOL quotes, so that
            // every result text occupies exactly fifteen characters.
            assertThat(FeedbackCondition.FC_INSUFFICIENT_DATA.resultText()).isEqualTo("Insufficient");
            assertThat(FeedbackCondition.FC_BAD_DATE_VALUE.resultText())
                    .isEqualTo("Datevalue error");
            assertThat(FeedbackCondition.FC_INVALID_ERA.resultText()).isEqualTo("Invalid Era    ");
            assertThat(FeedbackCondition.FC_UNSUPP_RANGE.resultText()).isEqualTo("Unsupp. Range  ");
            assertThat(FeedbackCondition.FC_INVALID_MONTH.resultText()).isEqualTo("Invalid month  ");
            assertThat(FeedbackCondition.FC_BAD_PIC_STRING.resultText()).isEqualTo("Bad Pic String ");
            assertThat(FeedbackCondition.FC_NON_NUMERIC_DATA.resultText())
                    .isEqualTo("Nonnumeric data");
            assertThat(FeedbackCondition.FC_YEAR_IN_ERA_ZERO.resultText())
                    .isEqualTo("YearInEra is 0 ");

            // The enum holds the COBOL literal, so eight are already fifteen characters and two are
            // shorter and padded by the MOVE into WS-RESULT PIC X(15). No literal may EXCEED fifteen,
            // because that would be silently truncated by the receiving field.
            for (final FeedbackCondition condition : FeedbackCondition.values()) {
                assertThat(condition.resultText()).as("literal width of %s", condition)
                        .hasSizeLessThanOrEqualTo(15);
                assertThat(asFifteenCharacterField(condition)).as("field width of %s", condition)
                        .hasSize(15);
                assertThat(condition.severity()).as("severity of %s", condition).isIn(0, 3);
            }
            assertThat(FeedbackCondition.FC_INVALID_DATE.resultText()).hasSize(13);
            assertThat(FeedbackCondition.FC_INSUFFICIENT_DATA.resultText()).hasSize(12);
        }

        @Test
        @DisplayName("Eight of the nine are reached from real input under the two corpus pictures")
        void eightConditionsAreReachedFromInput() {
            final Set<FeedbackCondition> reached = EnumSet.noneOf(FeedbackCondition.class);

            Stream.concat(Stream.concat(acceptedForms(), rejectedForms()), badPictures())
                    .forEach(row -> service
                            .validate((String) row.get()[0], (String) row.get()[1])
                            .feedbackCode().condition().ifPresent(reached::add));

            assertThat(reached).containsExactlyInAnyOrder(
                    FeedbackCondition.FC_INVALID_DATE,
                    FeedbackCondition.FC_INSUFFICIENT_DATA,
                    FeedbackCondition.FC_BAD_DATE_VALUE,
                    FeedbackCondition.FC_UNSUPP_RANGE,
                    FeedbackCondition.FC_INVALID_MONTH,
                    FeedbackCondition.FC_BAD_PIC_STRING,
                    FeedbackCondition.FC_NON_NUMERIC_DATA,
                    FeedbackCondition.FC_YEAR_IN_ERA_ZERO);
            assertThat(reached).doesNotContain(FeedbackCondition.FC_INVALID_ERA);
        }

        @Test
        @DisplayName("The ninth is reachable through the public resolve path even though no input hits it")
        void theEraConditionIsModelledAndResolvable() {
            // Retaining it keeps the enum a faithful image of the nine declared tokens rather than a
            // subset chosen by reachability, and the resolve path is how a caller presenting the pair
            // still obtains it.
            assertThat(new FeedbackCode(3, 2509).condition())
                    .contains(FeedbackCondition.FC_INVALID_ERA);
            assertThat(FeedbackCode.of(FeedbackCondition.FC_INVALID_ERA).resultText())
                    .isEqualTo("Invalid Era    ");
        }

        @Test
        @DisplayName("Its unreachability from input is structural: neither picture carries an era symbol")
        void theEraConditionIsUnreachableForAStatedStructuralReason() {
            // The era conditions arise only when the picture carries a Japanese era symbol <JJJJ> or a
            // Republic of China era symbol <CCCC>. Neither corpus picture contains an angle bracket at
            // all, so no input under either can reach them. That is the reason, asserted rather than
            // merely claimed.
            assertThat(DELIMITED).doesNotContain("<").doesNotContain(">");
            assertThat(UNDELIMITED).doesNotContain("<").doesNotContain(">");
        }

        @Test
        @DisplayName("The tenth result string still serves a code matching none of the nine")
        void theWhenOtherFallbackSurvives() {
            final FeedbackCode unmatched = new FeedbackCode(3, 9999);

            assertThat(unmatched.condition()).isEmpty();
            assertThat(unmatched.resultText())
                    .isEqualTo(DateValidationService.UNRECOGNISED_RESULT_TEXT)
                    .isEqualTo("Date is invalid");
        }
    }
}
