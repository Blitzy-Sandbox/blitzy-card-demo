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
package com.aws.carddemo.validation;

import java.util.Objects;

/**
 * Immutable result type returned by every public method on
 * {@link DateValidationService}.
 *
 * <p>A {@code DateValidationResult} reports two facts about a date-validation
 * call: a boolean {@link #isValid()} flag indicating whether the input parsed
 * successfully against the supplied format mask, and a human-readable
 * {@link #reason()} string carrying the COBOL-equivalent feedback.
 *
 * <h2>Reason-string contract</h2>
 *
 * <p>The {@link #reason()} component is the contractual value asserted
 * verbatim by {@code DateValidationServiceTest} against the rows in
 * {@code src/test/resources/fixtures/edge/date_validation_variants.csv}. The
 * 9 contractual reason strings mirror the COBOL CSUTLDTC.cbl {@code WS-RESULT}
 * field, which carries one of these tokens after the LE {@code CEEDAYS} API
 * returns its {@code FEEDBACK-CODE} byte string (see {@code CSUTLDTC.cbl}
 * lines 122–147 and the {@code 88}-level {@code FC-*} conditions at
 * lines 64–72):
 *
 * <ul>
 *   <li>{@link #REASON_DATE_IS_VALID} — the happy-path sentinel emitted when
 *       the COBOL {@code FC-INVALID-DATE} condition fires (the historical
 *       COBOL token: a {@code FEEDBACK-CODE} of all-zero binary bytes means
 *       "no error" — a long-standing CEEDAYS API idiosyncrasy where
 *       "INVALID-DATE" is the name of the condition that fires when the
 *       date is NOT invalid).</li>
 *   <li>{@link #REASON_INSUFFICIENT} — the supplied input had fewer
 *       characters than the mask required (e.g., {@code "2024-01"} against
 *       {@code "YYYY-MM-DD"}).</li>
 *   <li>{@link #REASON_DATEVALUE_ERROR} — the input parsed structurally but
 *       carried an impossible date (e.g., {@code "2024-02-30"},
 *       {@code "2024-04-31"}, {@code "1900-02-29"} — 1900 was not a leap
 *       year per the Gregorian century rule).</li>
 *   <li>{@link #REASON_INVALID_MONTH} — the month component of the date is
 *       outside the {@code 01}–{@code 12} range (e.g., {@code "2024-13-01"}
 *       or {@code "2024-00-15"}).</li>
 *   <li>{@link #REASON_BAD_PIC_STRING} — the supplied mask is malformed,
 *       empty, or unsupported (the Java migration supports
 *       {@code "YYYY-MM-DD"} and {@code "MM/DD/YYYY"}).</li>
 *   <li>{@link #REASON_NONNUMERIC_DATA} — a numeric field of the date
 *       contained non-digit characters (e.g., {@code "abcd-01-15"} or
 *       {@code "2024-AB-15"}).</li>
 *   <li>{@link #REASON_YEAR_IN_ERA_IS_ZERO} — the year is {@code 0000}, which
 *       has no Gregorian-era meaning under the COBOL CEEDAYS implementation.</li>
 *   <li>{@link #REASON_INVALID_ERA} — reserved for non-Gregorian era inputs;
 *       included for parity with the COBOL {@code FC-INVALID-ERA} condition.</li>
 *   <li>{@link #REASON_UNSUPP_RANGE} — reserved for inputs outside the
 *       CEEDAYS-supported year range; included for parity with the COBOL
 *       {@code FC-UNSUPP-RANGE} condition.</li>
 * </ul>
 *
 * <p>Per the AAP §0.10.4 "Immutable Boundaries" mandate, the production code
 * must return these reason strings byte-for-byte; any modification breaks the
 * test contract embedded in {@code date_validation_variants.csv}.
 *
 * <h2>Invariants enforced by the canonical constructor</h2>
 *
 * <ul>
 *   <li>{@link #reason()} is never {@code null} (caller cannot construct a
 *       result without supplying a verdict)</li>
 *   <li>{@link #reason()} is never empty (every verdict carries actionable
 *       text so downstream callers can surface a meaningful message)</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 *
 * <p>{@code DateValidationResult} is an immutable Java {@code record} carrying
 * a {@code boolean} and a {@code String}; instances are inherently thread-safe
 * and safe to share across the JUnit 5 parallel test classes configured by
 * {@code junit-platform.properties}.
 *
 * <h2>Provenance</h2>
 *
 * <p>This type replaces the COBOL {@code WS-MESSAGE.WS-RESULT PIC X(15)}
 * field defined in {@code app/cbl/CSUTLDTC.cbl} lines 49–50. In COBOL the
 * 15-character result string was the only output channel for the
 * {@code FEEDBACK-CODE} verdict (the {@code RETURN-CODE} carried the
 * severity but not the diagnostic text); the Java migration makes the
 * verdict explicit (boolean) and the diagnostic text contractually fixed.
 *
 * @param isValid {@code true} if the date parsed successfully against the
 *                supplied mask; {@code false} otherwise
 * @param reason  non-null, non-empty diagnostic string corresponding to one
 *                of the {@code REASON_*} constants
 */
public record DateValidationResult(boolean isValid, String reason) {

    /**
     * Sentinel reason string returned when the date parses successfully.
     * Matches the COBOL {@code WS-RESULT} value set by the
     * {@code FC-INVALID-DATE} branch at {@code CSUTLDTC.cbl} line 130.
     */
    public static final String REASON_DATE_IS_VALID = "Date is valid";

    /** Reason: the input is shorter than the mask requires. */
    public static final String REASON_INSUFFICIENT = "Insufficient";

    /** Reason: the input parsed structurally but is not a valid calendar date. */
    public static final String REASON_DATEVALUE_ERROR = "Datevalue error";

    /** Reason: the month component is outside the 01–12 range. */
    public static final String REASON_INVALID_MONTH = "Invalid month";

    /** Reason: the supplied format mask is empty, malformed, or unsupported. */
    public static final String REASON_BAD_PIC_STRING = "Bad Pic String";

    /** Reason: a numeric field of the date contained non-digit characters. */
    public static final String REASON_NONNUMERIC_DATA = "Nonnumeric data";

    /** Reason: the year component is {@code 0000}. */
    public static final String REASON_YEAR_IN_ERA_IS_ZERO = "YearInEra is 0";

    /** Reason: the era is not Gregorian (parity with COBOL FC-INVALID-ERA). */
    public static final String REASON_INVALID_ERA = "Invalid Era";

    /** Reason: the year is outside the CEEDAYS-supported range. */
    public static final String REASON_UNSUPP_RANGE = "Unsupp. Range";

    /**
     * Canonical compact constructor enforcing the {@code reason} invariants.
     *
     * @throws IllegalArgumentException if {@code reason} is {@code null} or
     *                                  empty (zero-length string)
     */
    public DateValidationResult {
        Objects.requireNonNull(reason, "reason must not be null");
        if (reason.isEmpty()) {
            throw new IllegalArgumentException(
                "reason must not be empty (every DateValidationResult carries"
                    + " actionable verdict text — e.g., 'Date is valid' or a"
                    + " specific reject text)");
        }
    }

    /**
     * Convenience factory producing the canonical
     * {@code isValid=true, reason="Date is valid"} happy-path result. Callers
     * under {@link DateValidationService} use this factory to ensure every
     * positive result carries the identical contractual reason string
     * {@link #REASON_DATE_IS_VALID}.
     *
     * @return a new {@link DateValidationResult} with {@code isValid=true}
     *         and {@code reason="Date is valid"}
     */
    public static DateValidationResult valid() {
        return new DateValidationResult(true, REASON_DATE_IS_VALID);
    }

    /**
     * Convenience factory producing an {@code isValid=false} reject result
     * with the supplied diagnostic reason. The reason is preserved verbatim;
     * callers must supply the exact contractual reject text required by
     * their validation branch.
     *
     * @param reason non-null, non-empty diagnostic string identifying the
     *               specific validation branch that rejected the input;
     *               typically one of the {@code REASON_*} constants
     * @return a new {@link DateValidationResult} with {@code isValid=false}
     *         and the supplied {@code reason}
     * @throws IllegalArgumentException if {@code reason} is {@code null} or empty
     */
    public static DateValidationResult invalid(String reason) {
        return new DateValidationResult(false, reason);
    }
}
