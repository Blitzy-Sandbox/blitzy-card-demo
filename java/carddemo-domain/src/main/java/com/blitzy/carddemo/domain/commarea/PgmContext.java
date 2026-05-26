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
package com.blitzy.carddemo.domain.commarea;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * Sealed hierarchy translating the COBOL 88-level conditions on
 * {@code CDEMO-PGM-CONTEXT} declared in {@code app/cpy/COCOM01Y.cpy}.
 *
 * <p>The COBOL definition (verbatim from the copybook, lines 29&ndash;31):
 * <pre>
 * 10 CDEMO-PGM-CONTEXT             PIC 9(01).
 *    88 CDEMO-PGM-ENTER            VALUE 0.
 *    88 CDEMO-PGM-REENTER          VALUE 1.
 * </pre>
 *
 * <p>This sealed interface partitions the closed value space {@code {0, 1}}
 * exhaustively. Callers MUST use pattern-matching {@code switch} expressions
 * with all permits enumerated; the Java 25 compiler enforces exhaustiveness so
 * adding a new permit (which would only happen if the COBOL source adds a new
 * 88-level condition) is a compile-time error at every call site until handled.
 *
 * <p><strong>NO {@code default} branch is permitted</strong> in a switch over
 * {@code PgmContext} (per AAP &sect;0.6.2 and &sect;0.6.7): exhaustiveness
 * checking IS the safety guarantee. The single {@code default} branch in
 * {@link #fromIndicator(int)} is over a primitive {@code int}, not over this
 * sealed type, and exists to surface invalid input rather than to mask missing
 * cases.
 *
 * <h2>Encoding</h2>
 * The {@link #indicator()} method returns the integer value that the COBOL
 * field holds in the underlying byte buffer ({@code 0} for {@link Enter},
 * {@code 1} for {@link Reenter}). Persistence of this value as a single ASCII
 * digit within the {@code CARDDEMO-COMMAREA} buffer is the responsibility of
 * the enclosing commarea encoder; this type is a pure value object and
 * performs no I/O of its own.
 *
 * <h2>Singleton Instances</h2>
 * Because both permits are zero-component records, all {@link Enter} instances
 * are {@code .equals()}-equal and all {@link Reenter} instances are
 * {@code .equals()}-equal. Use the canonical {@link #ENTER} and {@link #REENTER}
 * constants instead of allocating fresh instances; use
 * {@link #fromIndicator(int)} to map from a raw integer to a canonical
 * instance.
 *
 * <h2>Pattern-Matching Switch Example</h2>
 * <pre>{@code
 * String label = switch (context) {
 *     case PgmContext.Enter   e -> "first entry";
 *     case PgmContext.Reenter r -> "re-entry";
 *     // NO default branch — the compiler enforces exhaustiveness
 * };
 * }</pre>
 *
 * <h2>Convenience Predicates</h2>
 * For call sites where pattern matching is overkill ({@code if/else} style
 * dispatch), {@link #isEnter()} and {@link #isReenter()} return the matching
 * boolean. These predicates do NOT replace exhaustive pattern matching; they
 * are convenience accessors on top of the sealed hierarchy.
 *
 * <h2>Value-Space Strictness</h2>
 * The COBOL {@code PIC 9(01)} field nominally permits any digit {@code 0}-{@code 9},
 * but the 88-level conditions on this field define exactly two valid values:
 * {@code 0} (Enter) and {@code 1} (Reenter). The Java translation preserves
 * that strictness: {@link #fromIndicator(int)} rejects any value outside
 * {@code {0, 1}} with {@link IllegalArgumentException}, matching the
 * closed-set semantics of the 88-level conditions per AAP &sect;0.7.1
 * (preserve all edge-case behavior including error codes).
 *
 * @see com.blitzy.carddemo.domain.annotation.CobolProgram
 * @see UserType
 * @since 1.0.0
 */
@CobolProgram(
        value = "COCOM01Y",
        sourcePath = "app/cpy/COCOM01Y.cpy",
        translationDate = "2025-10-15",
        notes = "Sealed hierarchy for 88-level conditions on CDEMO-PGM-CONTEXT: "
                + "CDEMO-PGM-ENTER (0) and CDEMO-PGM-REENTER (1). "
                + "Closed value space {0, 1}; pattern-matching switch must be exhaustive."
)
public sealed interface PgmContext permits PgmContext.Enter, PgmContext.Reenter {

    /**
     * Returns the integer indicator value held in the COBOL
     * {@code CDEMO-PGM-CONTEXT PIC 9(01)} field. Returns {@code 0} for
     * {@link Enter}, {@code 1} for {@link Reenter}.
     *
     * @return {@code 0} for Enter, {@code 1} for Reenter
     */
    int indicator();

    /**
     * @return {@code true} if this is an {@link Enter} (first-entry) context;
     *         {@code false} otherwise
     */
    default boolean isEnter() {
        return this instanceof Enter;
    }

    /**
     * @return {@code true} if this is a {@link Reenter} (re-entry) context;
     *         {@code false} otherwise
     */
    default boolean isReenter() {
        return this instanceof Reenter;
    }

    /**
     * First-entry context: {@code CDEMO-PGM-ENTER VALUE 0}. Indicates a program
     * is being entered for the first time, with no prior screen state to
     * restore. In COBOL/CICS terms this corresponds to the initial XCTL/LINK
     * into a program where {@code EIBCALEN} is either zero or carries a
     * commarea whose {@code CDEMO-PGM-CONTEXT} byte was set to {@code '0'} by
     * the caller.
     *
     * <p>This permit is a zero-component record; all {@code Enter} instances
     * are {@code .equals()}-equal. Prefer the canonical {@link #ENTER} constant
     * over fresh allocations.
     */
    @CobolProgram(
            value = "COCOM01Y",
            sourcePath = "app/cpy/COCOM01Y.cpy",
            translationDate = "2025-10-15",
            notes = "88-level CDEMO-PGM-ENTER VALUE 0"
    )
    record Enter() implements PgmContext {
        @Override
        public int indicator() {
            return 0;
        }
    }

    /**
     * Re-entry context: {@code CDEMO-PGM-REENTER VALUE 1}. Indicates a program
     * is being re-entered with prior screen state to restore. In COBOL/CICS
     * terms this corresponds to a return-with-commarea round-trip in which the
     * program previously sent a screen, the user responded, and CICS dispatched
     * the same program a second time with {@code CDEMO-PGM-CONTEXT} byte set
     * to {@code '1'} so the program knows to restore its prior working state
     * (e.g., from {@code CDEMO-LAST-MAP} / {@code CDEMO-LAST-MAPSET} in
     * {@link CardDemoCommarea.CdemoMoreInfo}) instead of starting over.
     *
     * <p>This permit is a zero-component record; all {@code Reenter} instances
     * are {@code .equals()}-equal. Prefer the canonical {@link #REENTER}
     * constant over fresh allocations.
     */
    @CobolProgram(
            value = "COCOM01Y",
            sourcePath = "app/cpy/COCOM01Y.cpy",
            translationDate = "2025-10-15",
            notes = "88-level CDEMO-PGM-REENTER VALUE 1"
    )
    record Reenter() implements PgmContext {
        @Override
        public int indicator() {
            return 1;
        }
    }

    /**
     * Canonical singleton instance for {@link Enter} (indicator {@code 0}).
     * Prefer this constant to {@code new Enter()} to avoid unnecessary
     * allocation. Interface fields are implicitly {@code public static final},
     * so this is a true constant.
     */
    PgmContext ENTER = new Enter();

    /**
     * Canonical singleton instance for {@link Reenter} (indicator {@code 1}).
     * Prefer this constant to {@code new Reenter()} to avoid unnecessary
     * allocation. Interface fields are implicitly {@code public static final},
     * so this is a true constant.
     */
    PgmContext REENTER = new Reenter();

    /**
     * Returns the canonical {@code PgmContext} instance for the given indicator
     * value. This is the discriminator-based factory that maps a single
     * digit/integer from a fixed-width COBOL record buffer to the
     * corresponding sealed-type permit.
     *
     * <p>The {@code default} branch below is over the primitive {@code int}
     * type, not over the sealed {@code PgmContext}, and exists to surface
     * invalid input rather than to mask missing cases. Per AAP &sect;0.6.2 this
     * is the correct idiom for parsing untrusted input: switches over the
     * sealed type itself remain exhaustive with no {@code default}.
     *
     * @param indicator {@code 0} for {@link Enter}, {@code 1} for
     *                  {@link Reenter}
     * @return {@link #ENTER} if {@code indicator == 0}; {@link #REENTER} if
     *         {@code indicator == 1}
     * @throws IllegalArgumentException if {@code indicator} is neither
     *                                  {@code 0} nor {@code 1}; the message
     *                                  includes the offending value for
     *                                  diagnostic purposes
     */
    static PgmContext fromIndicator(int indicator) {
        return switch (indicator) {
            case 0 -> ENTER;
            case 1 -> REENTER;
            default -> throw new IllegalArgumentException(
                    "Invalid PgmContext indicator: " + indicator
                            + " (expected 0 for Enter or 1 for Reenter)");
        };
    }
}
