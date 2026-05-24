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
package com.blitzy.carddemo.application.util;

import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.validation.DateValidationWork.ValidityFlag;

import java.util.Objects;

/**
 * Static helper translating the COBOL procedure-template copybook
 * {@code CSSETATY.cpy} (BMS field-highlighting template). The original COBOL
 * source is a Procedure Division template &mdash; <strong>not</strong> a record
 * layout &mdash; with three substitution tokens
 * ({@code (TESTVAR1)}, {@code (SCRNVAR2)}, {@code (MAPNAME3)}) intended to be
 * instantiated repeatedly via {@code COPY REPLACING}, once per BMS field that
 * requires highlight-on-error during program re-entry.
 *
 * <h2>Verbatim COBOL source</h2>
 * <pre>{@code
 * IF (FLG-(TESTVAR1)-NOT-OK
 * OR  FLG-(TESTVAR1)-BLANK)
 * AND CDEMO-PGM-REENTER
 *     MOVE DFHRED             TO
 *          (SCRNVAR2)C OF (MAPNAME3)O
 *     IF  FLG-(TESTVAR1)-BLANK
 *         MOVE '*'            TO
 *          (SCRNVAR2)O OF (MAPNAME3)O
 *     END-IF
 * END-IF
 * }</pre>
 *
 * <h2>Java translation strategy</h2>
 * In Java the per-field {@code COPY REPLACING} template instantiation collapses
 * to a single generic helper method
 * {@link #applyFieldHighlight(ValidityFlag, boolean, String, String)
 * applyFieldHighlight}. The helper takes the field's current validity flag,
 * the re-entry indicator, and the field's current BMS attribute and value
 * Strings; it returns a new {@link FieldHighlightResult} carrying the
 * (possibly updated) attribute and value Strings, which the caller assigns
 * back to the BMS DTO output record.
 *
 * <p>The three COBOL substitution tokens map to method parameters as follows:
 * <ul>
 *   <li>{@code (TESTVAR1)} (the flag-suffix token) becomes the
 *       {@code flag} parameter (a {@link ValidityFlag} permit).</li>
 *   <li>{@code (SCRNVAR2)} (the symbolic-field token) becomes the
 *       {@code currentAttribute}/{@code currentValue} pair of parameters
 *       &mdash; the caller passes the current values from the BMS output
 *       record, and the result carries the new values back.</li>
 *   <li>{@code (MAPNAME3)} (the map-structure token) is fully eliminated:
 *       the map structure is already implicit in the caller's choice of
 *       BMS DTO record.</li>
 * </ul>
 *
 * <h2>COBOL semantics preserved</h2>
 * <ul>
 *   <li>If the field flag is {@link ValidityFlag.NotOk NotOk} OR
 *       {@link ValidityFlag.Blank Blank}, AND the program is in re-entry mode,
 *       the field's attribute is changed to {@link #DFHRED}.</li>
 *   <li>If the field flag is {@link ValidityFlag.Blank Blank} (additionally),
 *       the field's display value is replaced by the single character
 *       {@code "*"} ({@link #ASTERISK}).</li>
 *   <li>Otherwise (flag is {@link ValidityFlag.Valid Valid}, or program is on
 *       first entry), the attribute and value are returned unchanged.</li>
 * </ul>
 *
 * <h2>Translation rationale</h2>
 * Per the user mandate (AAP &sect;0.7.1) this helper preserves COBOL behavior
 * exactly &mdash; including the re-entry guard ({@code AND CDEMO-PGM-REENTER})
 * which prevents fields from being highlighted during initial program entry,
 * and the asterisk substitution for blank fields which is the COBOL idiom for
 * "tell the user this required field was empty." When the BMS terminal
 * renders the screen, the asterisk visibly marks the offending field even
 * before the red color attribute takes visual effect because some 3270
 * terminals support only monochrome.
 *
 * <h2>ValidityFlag reuse</h2>
 * The COBOL {@code FLG-…-NOT-OK} / {@code FLG-…-BLANK} 88-level conditions in
 * {@code CSSETATY} share the exact same byte-value semantics ({@code LOW-VALUE}
 * / {@code '0'} / {@code 'B'}) as the {@code FLG-YEAR-*} / {@code FLG-MONTH-*}
 * / {@code FLG-DAY-*} 88-level conditions from {@code CSUTLDWY}. Reusing the
 * {@link ValidityFlag} sealed hierarchy from {@code carddemo-domain.validation}
 * is the correct semantic match and avoids type proliferation.
 *
 * <h2>Reentry indicator delegation</h2>
 * Per AAP &sect;0.6.10, the COBOL 88-level {@code CDEMO-PGM-REENTER}
 * translates to a {@code PgmContext.Reenter} sealed permit (defined in
 * {@code CardDemoCommarea}). This helper accepts a plain {@code boolean
 * isReenter} so that callers can derive the flag from any source &mdash; a
 * pattern-match against {@code PgmContext.Reenter}, a commarea inspection,
 * or a test-supplied value. This keeps {@code ScreenAttributeSetter} free of
 * any commarea or BMS DTO dependency, which is the correct separation of
 * concerns for a pure utility class.
 *
 * <h2>Provenance annotation</h2>
 * This class is a procedural copybook translation, not a {@code PROGRAM-ID}
 * translation; the {@link CobolProgram} annotation cites {@code CSSETATY} as
 * the copybook name and {@code app/cpy/CSSETATY.cpy} as the source path
 * (AAP &sect;0.7.1).
 *
 * <h2>Utility-class invariant</h2>
 * This class is intentionally a utility class: it has a private constructor
 * that throws {@link UnsupportedOperationException} and exposes only static
 * methods. It has no mutable state.
 *
 * @see ValidityFlag
 * @see FieldHighlightResult
 * @see <a href="file:../../../../../../../../../../../app/cpy/CSSETATY.cpy">app/cpy/CSSETATY.cpy</a>
 */
@CobolProgram(
        value = "CSSETATY",
        sourcePath = "app/cpy/CSSETATY.cpy",
        translationDate = "2025-09-16",
        notes = "Procedure Division template for BMS field highlighting; translated"
                + " as static helper methods. The three COBOL substitution tokens"
                + " (TESTVAR1), (SCRNVAR2), (MAPNAME3) collapse to method parameters."
                + " Reuses ValidityFlag sealed hierarchy from carddemo-domain.validation"
                + " (DateValidationWork.ValidityFlag) because CSSETATY's FLG-*-NOT-OK"
                + " / FLG-*-BLANK 88-level conditions share the exact byte-value"
                + " semantics (LOW-VALUE / '0' / 'B') with CSUTLDWY."
)
public final class ScreenAttributeSetter {

    // ------------------------------------------------------------------
    // BMS attribute constants
    // ------------------------------------------------------------------

    /**
     * BMS red color attribute character (translation of CICS {@code DFHRED}
     * from the CICS-supplied {@code DFHBMSCA} copybook). Per the CICS BMS
     * reference, {@code DFHRED} has the binary value {@code X'08'} when stored
     * in the BMS extended-color attribute byte plane.
     *
     * <p>This constant is the Java single-character {@code String}
     * representation of the COBOL byte value. When the destination BMS DTO
     * output record (translated from {@code app/cpy-bms/*.CPY}) stores its
     * color attribute as a {@code String}, this constant is assigned to the
     * field's {@code *C} (color) companion field to signal "render this field
     * in red on the terminal."
     *
     * <p>Per AAP &sect;0.4.2, {@code COPY DFHBMSCA} has no Java equivalent;
     * BMS attribute constants are defined here on
     * {@code ScreenAttributeSetter}.
     *
     * @see <a href="file:../../../../../../../../../../../app/cpy/CSSETATY.cpy">app/cpy/CSSETATY.cpy</a>
     */
    public static final String DFHRED = "\u0008";

    /**
     * Single-character {@code "*"} (asterisk) used to mark a required field
     * that the user left blank during initial entry. The COBOL source
     * statement {@code MOVE '*' TO (SCRNVAR2)O OF (MAPNAME3)O} writes this
     * byte into the field's display-value slot when the field was both
     * required and blank.
     *
     * <p>This visible marker is critical on monochrome 3270 terminals where
     * the {@link #DFHRED red color attribute} has no visual effect; the
     * asterisk is therefore the universal "this required field was empty"
     * indicator across all terminal types.
     *
     * @see <a href="file:../../../../../../../../../../../app/cpy/CSSETATY.cpy">app/cpy/CSSETATY.cpy</a>
     */
    public static final String ASTERISK = "*";

    // ------------------------------------------------------------------
    // Private constructor (utility-class invariant)
    // ------------------------------------------------------------------

    /**
     * Private constructor &mdash; this is a utility class with only static
     * helpers. The constructor throws {@link UnsupportedOperationException}
     * to defend against reflective instantiation: even a caller that obtains
     * the private constructor via {@code getDeclaredConstructor()} and calls
     * {@code setAccessible(true)} will receive an
     * {@link java.lang.reflect.InvocationTargetException
     * InvocationTargetException} wrapping
     * {@link UnsupportedOperationException}.
     *
     * @throws UnsupportedOperationException always &mdash; this class must
     *                                       never be instantiated
     */
    private ScreenAttributeSetter() {
        throw new UnsupportedOperationException(
                "ScreenAttributeSetter is a utility class and must not be instantiated"
        );
    }

    // ------------------------------------------------------------------
    // Public static API
    // ------------------------------------------------------------------

    /**
     * Applies COBOL {@code CSSETATY} semantics: if the field is in error or
     * blank and the program is in re-entry mode, set its BMS attribute to red;
     * if the field is also blank, replace its display value with {@code "*"}
     * (asterisk).
     *
     * <p>This is the single-method translation of the COBOL procedure template
     * at {@code app/cpy/CSSETATY.cpy} (lines 17&ndash;27). The COBOL template
     * uses three substitution tokens that here become arguments:
     * <ul>
     *   <li>{@code (TESTVAR1)} (the flag-suffix token) becomes the
     *       {@code flag} parameter (a {@link ValidityFlag} permit).</li>
     *   <li>{@code (SCRNVAR2)} (the symbolic-field token) becomes the
     *       {@code currentAttribute}/{@code currentValue} pair of parameters.
     *       The caller passes the current values from the BMS output record,
     *       and the result carries the new values back.</li>
     *   <li>{@code (MAPNAME3)} (the map-structure token) is fully eliminated;
     *       the map structure is already implicit in the caller's choice of
     *       BMS DTO record.</li>
     * </ul>
     *
     * <p>The re-entry guard {@code AND CDEMO-PGM-REENTER} from the COBOL
     * source becomes the {@code isReenter} parameter. Per AAP &sect;0.6.10,
     * the COBOL 88-level {@code CDEMO-PGM-REENTER} translates to the
     * {@code PgmContext.Reenter} sealed permit (defined in
     * {@code CardDemoCommarea}). Callers are expected to derive
     * {@code isReenter} from a pattern match such as
     * {@code commarea.cdemoGeneralInfo().pgmContext() instanceof PgmContext.Reenter}.
     *
     * <h3>Behavior table</h3>
     * <table border="1">
     *   <caption>Outcome of {@code applyFieldHighlight} for every
     *            ({@code flag} &times; {@code isReenter}) combination</caption>
     *   <tr>
     *     <th>{@code flag}</th>
     *     <th>{@code isReenter}</th>
     *     <th>Result attribute</th>
     *     <th>Result value</th>
     *   </tr>
     *   <tr>
     *     <td>{@link ValidityFlag.Valid Valid}</td>
     *     <td>{@code false}</td>
     *     <td>{@code currentAttribute} (unchanged)</td>
     *     <td>{@code currentValue} (unchanged)</td>
     *   </tr>
     *   <tr>
     *     <td>{@link ValidityFlag.Valid Valid}</td>
     *     <td>{@code true}</td>
     *     <td>{@code currentAttribute} (unchanged)</td>
     *     <td>{@code currentValue} (unchanged)</td>
     *   </tr>
     *   <tr>
     *     <td>{@link ValidityFlag.NotOk NotOk}</td>
     *     <td>{@code false}</td>
     *     <td>{@code currentAttribute} (unchanged &mdash; re-entry guard)</td>
     *     <td>{@code currentValue} (unchanged)</td>
     *   </tr>
     *   <tr>
     *     <td>{@link ValidityFlag.NotOk NotOk}</td>
     *     <td>{@code true}</td>
     *     <td>{@link #DFHRED}</td>
     *     <td>{@code currentValue} (unchanged)</td>
     *   </tr>
     *   <tr>
     *     <td>{@link ValidityFlag.Blank Blank}</td>
     *     <td>{@code false}</td>
     *     <td>{@code currentAttribute} (unchanged &mdash; re-entry guard)</td>
     *     <td>{@code currentValue} (unchanged)</td>
     *   </tr>
     *   <tr>
     *     <td>{@link ValidityFlag.Blank Blank}</td>
     *     <td>{@code true}</td>
     *     <td>{@link #DFHRED}</td>
     *     <td>{@link #ASTERISK}</td>
     *   </tr>
     * </table>
     *
     * <h3>Exhaustiveness guarantee</h3>
     * The pattern-matching {@code switch} expression over {@link ValidityFlag}
     * is exhaustive with <strong>no</strong> {@code default} branch (per AAP
     * &sect;0.7.3). The Java 25 compiler enforces this because
     * {@code ValidityFlag} is a sealed interface with exactly three permits
     * ({@code Valid}, {@code NotOk}, {@code Blank}). If a future maintenance
     * adds a permit to {@code ValidityFlag}, this method will fail to compile
     * &mdash; which is the intended safety net.
     *
     * @param flag             the field's current validity flag
     *                         ({@link ValidityFlag.Valid Valid},
     *                         {@link ValidityFlag.NotOk NotOk}, or
     *                         {@link ValidityFlag.Blank Blank}); must be
     *                         non-null
     * @param isReenter        {@code true} if the program is in re-entry mode
     *                         (the COBOL {@code CDEMO-PGM-REENTER} 88-level
     *                         from {@code COCOM01Y.cpy} is set);
     *                         {@code false} on first entry
     * @param currentAttribute the field's current BMS attribute String
     *                         (typically the value of the {@code *C} companion
     *                         field on the BMS output record); must be
     *                         non-null
     * @param currentValue     the field's current display value (typically the
     *                         value of the {@code *O} field on the BMS output
     *                         record); must be non-null
     * @return a {@link FieldHighlightResult} carrying the new attribute and
     *         new value Strings; both are unchanged from input unless the
     *         conditions for highlighting apply
     * @throws NullPointerException if {@code flag}, {@code currentAttribute},
     *                              or {@code currentValue} is {@code null}
     */
    public static FieldHighlightResult applyFieldHighlight(
            ValidityFlag flag,
            boolean isReenter,
            String currentAttribute,
            String currentValue
    ) {
        Objects.requireNonNull(flag, "flag");
        Objects.requireNonNull(currentAttribute, "currentAttribute");
        Objects.requireNonNull(currentValue, "currentValue");

        // Mirror the COBOL guard: IF (NOT-OK OR BLANK) AND CDEMO-PGM-REENTER
        // The re-entry check is the outer gate; without re-entry no highlight
        // ever applies, regardless of the flag state. This matches the COBOL
        // behavior where the AND CDEMO-PGM-REENTER clause short-circuits the
        // entire IF block on first entry.
        if (!isReenter) {
            return new FieldHighlightResult(currentAttribute, currentValue);
        }

        // Pattern-matching switch over ValidityFlag — exhaustive, NO default
        // branch per AAP §0.7.3. The Java 25 compiler enforces exhaustiveness
        // because ValidityFlag is sealed (permits exactly Valid, NotOk, Blank).
        return switch (flag) {
            case ValidityFlag.Valid v -> new FieldHighlightResult(currentAttribute, currentValue);
            case ValidityFlag.NotOk n -> new FieldHighlightResult(DFHRED, currentValue);
            case ValidityFlag.Blank b -> new FieldHighlightResult(DFHRED, ASTERISK);
        };
    }

    // ==================================================================
    // NESTED TYPES
    // ==================================================================

    /**
     * Result of
     * {@link ScreenAttributeSetter#applyFieldHighlight(ValidityFlag, boolean,
     * String, String) applyFieldHighlight}. Carries the (possibly changed)
     * BMS attribute byte and the (possibly changed) BMS display value, both
     * as immutable {@code String}s. The caller assigns these back to the
     * corresponding BMS DTO output record fields.
     *
     * <p>This record is deeply immutable: both components are {@code String}
     * (themselves immutable) and the compact canonical constructor refuses
     * {@code null}. Two {@code FieldHighlightResult} instances with the same
     * attribute and value Strings are {@link #equals(Object) equal} and have
     * the same {@link #hashCode() hash code} (record equality semantics).
     *
     * @param attribute the new BMS attribute byte (a single character
     *                  {@code String}); this is the value that should be
     *                  assigned to the {@code *C} (color/attribute) companion
     *                  field of the BMS output record. Unchanged from input
     *                  when no highlight is applied; equal to
     *                  {@link ScreenAttributeSetter#DFHRED} when the field is
     *                  highlighted in red.
     * @param value     the new BMS display value {@code String}; this is the
     *                  value that should be assigned to the {@code *O}
     *                  (output) field of the BMS output record. Unchanged
     *                  from input when the field is not blank, OR equal to
     *                  {@link ScreenAttributeSetter#ASTERISK} when the field
     *                  was blank during re-entry.
     */
    public record FieldHighlightResult(String attribute, String value) {

        /**
         * Compact canonical constructor enforcing non-null components.
         * Both {@code attribute} and {@code value} are required &mdash; the
         * BMS DTO output record fields they bind to are non-null by contract,
         * so a {@code null} component here would silently corrupt the BMS
         * output downstream.
         *
         * @throws NullPointerException if {@code attribute} or {@code value}
         *                              is {@code null}
         */
        public FieldHighlightResult {
            Objects.requireNonNull(attribute, "attribute");
            Objects.requireNonNull(value, "value");
        }
    }
}
