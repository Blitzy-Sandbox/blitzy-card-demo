/*
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
 * language governing permissions and limitations under the License.
 */
package com.awsm2.carddemo.validation;

import com.awsm2.carddemo.validation.DateValidationService.DateValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JUnit 5 unit tests for {@link DateValidationService} — the Java
 * port of the COBOL date-validation paragraphs in
 * {@code app/cpy/CSUTLDPY.cpy} and {@code app/cbl/CSUTLDTC.cbl}
 * (LE {@code CEEDAYS} wrapper).
 *
 * <h2>QA Final Checkpoint 13 Maj-4 fix</h2>
 * <p>Prior to this test class, date validation was only exercised
 * indirectly through service-level tests. The QA CP13 report
 * (finding Maj-4) requires a dedicated test class per AAP &sect;0.4.1
 * one-test-per-service contract.</p>
 *
 * <h2>Coverage scope</h2>
 * <ul>
 *   <li><b>Structural date validation</b> ({@code validate}) — leap-year
 *       handling, invalid month/day, century checking (must be 19 or 20),
 *       blank input, malformed input.</li>
 *   <li><b>Date-of-birth validation</b> ({@code validateDateOfBirth}) —
 *       future-date rejection with deterministic {@code today} injection
 *       per the COBOL pattern.</li>
 *   <li><b>Format mask flexibility</b> — {@code YYYYMMDD}, {@code MM/DD/YYYY},
 *       {@code YYYY-MM-DD}, custom masks via {@code toJavaPattern}.</li>
 *   <li><b>Result codes</b> — 0000 VALID, 0009 INVALID, 0010 BLANK,
 *       0006 BAD_FORMAT_MASK, 0011 FUTURE_DATE, 0012 BAD_CENTURY.</li>
 *   <li><b>DateValidationResult record</b> — VALID sentinel, invalid factory,
 *       canonical-constructor result-code normalization,
 *       {@code isValid()/errorMessage()} compatibility accessors.</li>
 *   <li><b>{@code toJavaPattern}</b> — case translation YYYYMMDD →
 *       uuuuMMdd, preserving separators.</li>
 * </ul>
 *
 * @see DateValidationService
 */
@DisplayName("DateValidationService — QA CP13 Maj-4 dedicated test class")
class DateValidationServiceTest {

    private final DateValidationService service = new DateValidationService();

    /** Deterministic "today" reference for date-of-birth tests. */
    private static final LocalDate TODAY = LocalDate.of(2024, 6, 15);

    // -------------------------------------------------------------------------
    // Structural date validation — validate(String) / validate(String, String)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("validate(...) — structural date parsing")
    class StructuralValidateTests {

        @ParameterizedTest(name = "valid CCYYMMDD: {0}")
        @ValueSource(strings = {
                "19000101", // century boundary
                "19500615",
                "19990101",
                "20000101", // Y2K
                "20240615",
                "20991231",
                "20240229", // 2024 IS a leap year
                "20000229", // 2000 IS a leap year (divisible by 400)
                "19960229", // 1996 IS a leap year
                "20240131", // valid 31-day month edge
                "20240430", // valid 30-day month edge
                "20240228"  // non-leap-year-Feb-28 edge
        })
        @DisplayName("syntactically valid CCYYMMDD dates return VALID")
        void validDatesReturnValid(String date) {
            DateValidationResult result = service.validate(date);

            assertThat(result.isValid()).isTrue();
            assertThat(result.resultCode())
                    .isEqualTo(DateValidationService.RESULT_CODE_VALID);
            assertThat(result.errorMessage()).isEqualTo("Date is valid");
        }

        @ParameterizedTest(name = "invalid leap year: {0}")
        @ValueSource(strings = {
                "20230229",  // 2023 is NOT a leap year
                "19000229",  // 1900 is NOT a leap year (divisible by 100, not 400)
                "21000229",  // 2100 is NOT a leap year — but also bad century
                "19990229",  // 1999 not leap
                "20210229"   // 2021 not leap
        })
        @DisplayName("Feb 29 in non-leap year is rejected")
        void nonLeapFeb29Rejected(String date) {
            DateValidationResult result = service.validate(date);

            assertThat(result.isValid())
                    .as("Feb 29 in %s should be rejected", date)
                    .isFalse();
        }

        @ParameterizedTest(name = "invalid month: {0}")
        @ValueSource(strings = {
                "20240001", // month 00
                "20241301", // month 13
                "20241501", // month 15
                "20249901"  // month 99
        })
        @DisplayName("month outside 1-12 range is rejected")
        void invalidMonthRejected(String date) {
            DateValidationResult result = service.validate(date);

            assertThat(result.isValid()).isFalse();
            assertThat(result.resultCode())
                    .isEqualTo(DateValidationService.RESULT_CODE_INVALID);
        }

        @ParameterizedTest(name = "invalid day: {0}")
        @ValueSource(strings = {
                "20240100",  // day 00
                "20240132",  // Jan 32
                "20240431",  // Apr 31 (April only has 30 days)
                "20240631",  // Jun 31 (Jun only has 30 days)
                "20240931",  // Sep 31
                "20241131",  // Nov 31
                "20240230",  // Feb 30 (Feb never has 30 days)
                "20240199"   // day 99
        })
        @DisplayName("invalid day (32, Feb 30, Apr 31, etc.) is rejected")
        void invalidDayRejected(String date) {
            DateValidationResult result = service.validate(date);

            assertThat(result.isValid())
                    .as("Invalid day in %s should be rejected", date)
                    .isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "18991231", // century 18
                "21000101", // century 21
                "00000101", // century 00
                "21240615"  // century 21
        })
        @DisplayName("century outside (19, 20) is rejected with BAD_CENTURY")
        void badCenturyRejected(String date) {
            DateValidationResult result = service.validate(date);

            assertThat(result.isValid()).isFalse();
            assertThat(result.resultCode())
                    .isEqualTo(DateValidationService.RESULT_CODE_BAD_CENTURY);
            assertThat(result.errorMessage()).contains("Century");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "   ", "\t"})
        @DisplayName("null / empty / blank returns BLANK (0010)")
        void blankInputReturnsBlankResult(String input) {
            DateValidationResult result = service.validate(input);

            assertThat(result.isValid()).isFalse();
            assertThat(result.resultCode())
                    .isEqualTo(DateValidationService.RESULT_CODE_BLANK);
            assertThat(result.errorMessage()).contains("Date must be supplied");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "abcd1234",     // alpha mixed with digits
                "12345678",     // numeric but invalid format
                "yyyy0101",     // literal mask characters
                "2024-06-15",   // wrong separators for default YYYYMMDD
                "06/15/2024",   // wrong format for default mask
                "2024",         // too short
                "20240615a"     // extra trailing char
        })
        @DisplayName("malformed date strings are rejected as INVALID (0009)")
        void malformedDateRejected(String date) {
            DateValidationResult result = service.validate(date);

            assertThat(result.isValid()).isFalse();
            assertThat(result.resultCode())
                    .isEqualTo(DateValidationService.RESULT_CODE_INVALID);
        }
    }

    // -------------------------------------------------------------------------
    // Format mask flexibility
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("validate(date, mask) — custom format masks")
    class FormatMaskTests {

        @Test
        @DisplayName("MM/DD/YYYY mask accepts US-style dates")
        void usStyleMaskAccepts() {
            DateValidationResult result = service.validate("06/15/2024", "MM/DD/YYYY");

            assertThat(result.isValid()).isTrue();
        }

        @Test
        @DisplayName("YYYY-MM-DD ISO mask accepts ISO-style dates")
        void isoMaskAccepts() {
            DateValidationResult result = service.validate("2024-06-15", "YYYY-MM-DD");

            assertThat(result.isValid()).isTrue();
        }

        @Test
        @DisplayName("Empty mask defaults to YYYYMMDD")
        void emptyMaskUsesDefault() {
            DateValidationResult result = service.validate("20240615", "");

            assertThat(result.isValid()).isTrue();
        }

        @Test
        @DisplayName("Null mask defaults to YYYYMMDD")
        void nullMaskUsesDefault() {
            DateValidationResult result = service.validate("20240615", null);

            assertThat(result.isValid()).isTrue();
        }

        @Test
        @DisplayName("Invalid pattern (random nonsense) returns BAD_FORMAT_MASK or INVALID")
        void invalidPatternReturnsBadFormatMask() {
            DateValidationResult result = service.validate("20240615", "QQQQQQQQ");

            // Whatever the result, it must be invalid. Java's DateTimeFormatter
            // may treat unknown letters as a recognised pattern or as literals;
            // either way, this fails the structural date check.
            assertThat(result.isValid()).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // toJavaPattern — case-translation invariants
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("toJavaPattern — COBOL mask to Java pattern translation")
    class ToJavaPatternTests {

        @Test
        @DisplayName("YYYYMMDD → uuuuMMdd (Y→u, M→M, D→d)")
        void yyyymmddTranslatesCorrectly() {
            assertThat(DateValidationService.toJavaPattern("YYYYMMDD"))
                    .isEqualTo("uuuuMMdd");
        }

        @Test
        @DisplayName("MM/DD/YYYY → MM/dd/uuuu (separators preserved)")
        void usStylePreservesSeparators() {
            assertThat(DateValidationService.toJavaPattern("MM/DD/YYYY"))
                    .isEqualTo("MM/dd/uuuu");
        }

        @Test
        @DisplayName("YYYY-MM-DD → uuuu-MM-dd (hyphens preserved)")
        void isoStylePreservesHyphens() {
            assertThat(DateValidationService.toJavaPattern("YYYY-MM-DD"))
                    .isEqualTo("uuuu-MM-dd");
        }

        @Test
        @DisplayName("YYYY MM DD → uuuu MM dd (spaces preserved)")
        void spacedStylePreservesSpaces() {
            assertThat(DateValidationService.toJavaPattern("YYYY MM DD"))
                    .isEqualTo("uuuu MM dd");
        }

        @Test
        @DisplayName("Throws NPE for null mask")
        void nullThrowsNpe() {
            assertThatThrownBy(() ->
                    DateValidationService.toJavaPattern(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // -------------------------------------------------------------------------
    // Date-of-birth validation — validateDateOfBirth
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("validateDateOfBirth — future-date rejection")
    class DateOfBirthTests {

        @Test
        @DisplayName("DOB strictly in past returns VALID")
        void pastDobValid() {
            DateValidationResult result = service.validateDateOfBirth(
                    "19850315", "YYYYMMDD", TODAY);

            assertThat(result.isValid()).isTrue();
        }

        @Test
        @DisplayName("DOB equal to today is REJECTED (CSUTLDPY.cpy strict-greater-than)")
        void dobEqualToTodayRejected() {
            // COBOL: CSUTLDPY.cpy:L350
            //   IF WS-CURRENT-DATE-BINARY > WS-EDIT-DATE-BINARY CONTINUE
            //   ELSE SET INPUT-ERROR TO TRUE
            // → today == dob fails the strict-greater-than check.
            String todayStr = String.format("%04d%02d%02d",
                    TODAY.getYear(), TODAY.getMonthValue(), TODAY.getDayOfMonth());
            DateValidationResult result = service.validateDateOfBirth(
                    todayStr, "YYYYMMDD", TODAY);

            assertThat(result.isValid()).isFalse();
            assertThat(result.resultCode())
                    .isEqualTo(DateValidationService.RESULT_CODE_FUTURE_DATE);
        }

        @Test
        @DisplayName("DOB after today is REJECTED with FUTURE_DATE (0011)")
        void dobInFutureRejected() {
            DateValidationResult result = service.validateDateOfBirth(
                    "20300101", "YYYYMMDD", TODAY);

            assertThat(result.isValid()).isFalse();
            assertThat(result.resultCode())
                    .isEqualTo(DateValidationService.RESULT_CODE_FUTURE_DATE);
            assertThat(result.errorMessage()).contains("future");
        }

        @Test
        @DisplayName("Malformed DOB (delegated to structural validate) returns INVALID")
        void malformedDobReturnsInvalid() {
            DateValidationResult result = service.validateDateOfBirth(
                    "20240230", "YYYYMMDD", TODAY);

            assertThat(result.isValid()).isFalse();
            assertThat(result.resultCode())
                    .isEqualTo(DateValidationService.RESULT_CODE_INVALID);
        }

        @Test
        @DisplayName("Blank DOB returns BLANK (delegated to structural validate)")
        void blankDobReturnsBlank() {
            DateValidationResult result = service.validateDateOfBirth(
                    "", "YYYYMMDD", TODAY);

            assertThat(result.isValid()).isFalse();
            assertThat(result.resultCode())
                    .isEqualTo(DateValidationService.RESULT_CODE_BLANK);
        }

        @Test
        @DisplayName("validateDateOfBirth(String) overload uses LocalDate.now()")
        void singleArgUsesNow() {
            // Test that the convenience overload exists and delegates correctly.
            // We use a date guaranteed to be in the past (1900-01-01) so the test
            // is stable regardless of when it runs.
            DateValidationResult result = service.validateDateOfBirth("19000101");

            assertThat(result.isValid()).isTrue();
        }

        @Test
        @DisplayName("null today reference throws NullPointerException")
        void nullTodayThrows() {
            assertThatThrownBy(() ->
                    service.validateDateOfBirth("19850315", "YYYYMMDD", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("today");
        }
    }

    // -------------------------------------------------------------------------
    // DateValidationResult record — VALID sentinel & invalid factory
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("DateValidationResult — record contract")
    class DateValidationResultTests {

        @Test
        @DisplayName("VALID sentinel reports valid=true, resultCode=0000")
        void validSentinel() {
            DateValidationResult v = DateValidationResult.VALID;

            assertThat(v.isValid()).isTrue();
            assertThat(v.valid()).isTrue();
            assertThat(v.resultCode()).isEqualTo("0000");
            assertThat(v.message()).isEqualTo("Date is valid");
            assertThat(v.errorMessage()).isEqualTo("Date is valid");
        }

        @Test
        @DisplayName("invalid(code, msg) factory creates valid=false result")
        void invalidFactory() {
            DateValidationResult r = DateValidationResult.invalid("0009", "x");

            assertThat(r.isValid()).isFalse();
            assertThat(r.resultCode()).isEqualTo("0009");
            assertThat(r.message()).isEqualTo("x");
        }

        @Test
        @DisplayName("canonical constructor normalizes null resultCode → 0000/0009")
        void canonicalConstructorNormalizesResultCode() {
            // valid=true, null code → normalised to 0000
            DateValidationResult r1 = new DateValidationResult(true, null, "ok");
            assertThat(r1.resultCode()).isEqualTo("0000");

            // valid=false, null code → normalised to 0009
            DateValidationResult r2 = new DateValidationResult(false, null, "bad");
            assertThat(r2.resultCode()).isEqualTo("0009");
        }

        @Test
        @DisplayName("isValid() is alias for valid()")
        void isValidAliasesValid() {
            assertThat(DateValidationResult.VALID.isValid())
                    .isEqualTo(DateValidationResult.VALID.valid());

            DateValidationResult inv = DateValidationResult.invalid("0009", "x");
            assertThat(inv.isValid())
                    .isEqualTo(inv.valid());
        }

        @Test
        @DisplayName("errorMessage() is alias for message()")
        void errorMessageAliasesMessage() {
            DateValidationResult inv = DateValidationResult.invalid("0009", "test msg");
            assertThat(inv.errorMessage()).isEqualTo(inv.message());
        }
    }

    // -------------------------------------------------------------------------
    // Public constants — defensive structure checks
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Public constants — result codes")
    class PublicConstantsTests {

        @Test
        @DisplayName("All result codes are 4-character strings (CSUTLDTC.cbl parity)")
        void resultCodesAreFourChars() {
            assertThat(DateValidationService.RESULT_CODE_VALID).isEqualTo("0000");
            assertThat(DateValidationService.RESULT_CODE_INVALID).isEqualTo("0009");
            assertThat(DateValidationService.RESULT_CODE_BLANK).isEqualTo("0010");
            assertThat(DateValidationService.RESULT_CODE_BAD_FORMAT_MASK).isEqualTo("0006");
            assertThat(DateValidationService.RESULT_CODE_FUTURE_DATE).isEqualTo("0011");
            assertThat(DateValidationService.RESULT_CODE_BAD_CENTURY).isEqualTo("0012");
        }

        @Test
        @DisplayName("DEFAULT_FORMAT_MASK is YYYYMMDD")
        void defaultMaskIsCCYYMMDD() {
            assertThat(DateValidationService.DEFAULT_FORMAT_MASK).isEqualTo("YYYYMMDD");
        }
    }
}
