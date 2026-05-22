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
 * {@link ValidationLookupService}.
 *
 * <p>A {@code LookupValidationResult} reports two facts about a lookup-table
 * validation: a boolean {@link #isValid()} flag indicating whether the input
 * passed validation, and a human-readable {@link #reason()} string carrying
 * the specific verdict.
 *
 * <h2>Reason-string contract</h2>
 *
 * <p>The {@link #reason()} component is the contractual value asserted
 * verbatim by {@code ValidationLookupServiceTest} against the rows in
 * {@code src/test/resources/fixtures/edge/lookup_invalid_keys.csv}:
 *
 * <ul>
 *   <li>{@code "VALID"} - the canonical positive-control sentinel returned
 *       when {@link #isValid()} is {@code true}. The string is uppercase and
 *       contains no trailing whitespace.</li>
 *   <li>Specific reject text - e.g.,
 *       {@code "Area code starts with 0"},
 *       {@code "State code must be 2 characters"},
 *       {@code "ZIP prefix must be numeric"}. Each reject reason is a single
 *       short sentence (no period, no trailing whitespace) identifying which
 *       validation branch rejected the input.</li>
 * </ul>
 *
 * <p>Per the AAP §0.10.4 "Immutable Boundaries" mandate, the production code
 * must return these reason strings byte-for-byte; any modification breaks the
 * test contract embedded in {@code lookup_invalid_keys.csv}.
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
 * <p>These invariants are enforced by the compact canonical constructor; any
 * attempt to construct a {@code LookupValidationResult} with a {@code null}
 * or empty {@code reason} raises {@link IllegalArgumentException} eagerly.
 *
 * <h2>Thread safety</h2>
 *
 * <p>{@code LookupValidationResult} is an immutable Java {@code record} carrying
 * a {@code boolean} and a {@code String}; instances are inherently thread-safe
 * and safe to share across the JUnit 5 parallel test classes configured by
 * {@code junit-platform.properties}.
 *
 * <h2>Provenance</h2>
 *
 * <p>This type replaces the COBOL 88-level boolean conditions defined in
 * {@code app/cpy/CSLKPCDY.cpy}
 * ({@code VALID-PHONE-AREA-CODE}, {@code VALID-US-STATE-CODE},
 * {@code VALID-US-STATE-ZIP-CD2-COMBO}). In COBOL the validity of an input
 * was implicit in the truth-value of the 88-level condition; the Java
 * migration makes the verdict explicit (boolean) and adds a reason string for
 * caller-facing diagnostics.
 *
 * @param isValid {@code true} if the validated input passed every check in the
 *                originating service method; {@code false} otherwise
 * @param reason  non-null, non-empty diagnostic string; the literal
 *                {@code "VALID"} on the happy path or a specific reject reason
 *                otherwise
 */
public record LookupValidationResult(boolean isValid, String reason) {

    /**
     * Sentinel reason string returned by every method on
     * {@link ValidationLookupService} when the input is accepted as valid.
     *
     * <p>Exposed as a constant so tests and downstream callers can reference
     * the value symbolically rather than hardcoding the literal. The canonical
     * positive-control fixture {@code lookup_invalid_keys.csv} asserts the
     * literal string {@code "VALID"} on every happy-path row; this constant
     * keeps the production code aligned with that fixture.
     */
    public static final String REASON_VALID = "VALID";

    /**
     * Canonical compact constructor enforcing the {@code reason} invariants.
     * Records normally accept any value for any component; this constructor
     * adds defensive checks so that the production methods cannot construct
     * a result with a {@code null} or empty reason.
     *
     * @throws IllegalArgumentException if {@code reason} is {@code null} or
     *                                  empty (zero-length string)
     */
    public LookupValidationResult {
        Objects.requireNonNull(reason, "reason must not be null");
        if (reason.isEmpty()) {
            throw new IllegalArgumentException(
                "reason must not be empty (every LookupValidationResult carries"
                    + " actionable verdict text — e.g., 'VALID' or a specific reject text)");
        }
    }

    /**
     * Convenience factory producing the canonical {@code isValid=true, reason="VALID"}
     * happy-path result. Callers under {@link ValidationLookupService} use this
     * factory to ensure every positive result carries the identical contractual
     * reason string {@link #REASON_VALID}.
     *
     * @return a new {@link LookupValidationResult} with {@code isValid=true} and
     *         {@code reason="VALID"}
     */
    public static LookupValidationResult valid() {
        return new LookupValidationResult(true, REASON_VALID);
    }

    /**
     * Convenience factory producing an {@code isValid=false} reject result with
     * the supplied diagnostic reason. The reason is preserved verbatim; callers
     * must supply the exact contractual reject text required by their validation
     * branch.
     *
     * @param reason non-null, non-empty diagnostic string identifying the
     *               specific validation branch that rejected the input
     * @return a new {@link LookupValidationResult} with {@code isValid=false} and
     *         the supplied {@code reason}
     * @throws IllegalArgumentException if {@code reason} is {@code null} or empty
     */
    public static LookupValidationResult invalid(String reason) {
        return new LookupValidationResult(false, reason);
    }
}
