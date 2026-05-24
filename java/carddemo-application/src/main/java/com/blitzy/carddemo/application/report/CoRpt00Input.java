/*
 * Copyright 2022 The CardDemo Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.blitzy.carddemo.application.report;

// JEP 511 (finalized in Java 25): single-statement import of every package
// exported by the java.base module (and the modules java.base reads). This
// gives access to java.lang.String for every PIC X(n) component plus
// java.lang.IllegalArgumentException (not used here — the input record
// clamps over-length inputs rather than rejecting them, matching CICS
// RECEIVE-MAP semantics on the 3270 hardware).
import module java.base;

// AAP §0.7.1 traceability mandate: every translated artifact cites its
// original COBOL source via the @CobolProgram annotation declared in the
// carddemo-domain module. carddemo-application declares carddemo-domain as
// a direct dependency in its pom.xml, so the annotation is on the
// classpath and resolvable here.
import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * BMS input record for the {@code CORPT0A / CORPT00} print-transaction-
 * report screen (COBOL transaction {@code CR00}, program
 * {@code CORPT00C}).
 *
 * <p>This record is a field-for-field projection of the input view of
 * the {@code 01 CORPT0AI} group in {@code app/cpy-bms/CORPT00.CPY}: one
 * {@link String} component per {@code "I"}-suffixed PIC X(n) BMS leaf
 * that carries operator data (the three report-type radio buttons —
 * monthly / yearly / custom — the six date pieces, and the
 * confirmation field), plus one {@link AidKey} discriminator capturing
 * which CICS attention identifier was pressed at RECEIVE-MAP time.
 * The header-row leaves (TRNNAMEI / TITLE01I / CURDATEI / PGMNAMEI /
 * TITLE02I / CURTIMEI) and the error-message leaf (ERRMSGI) are not
 * carried on the input side; they are populated by the program before
 * SEND-MAP, not by the operator on RECEIVE-MAP, and live on
 * {@link CoRpt00Output} instead.
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/CORPT00.bms}
 *       (mapset {@code CORPT00}, map {@code CORPT0A}, size 24x80,
 *       {@code CTRL=(ALARM,FREEKB)}, {@code EXTATT=YES}).</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/CORPT00.CPY}
 *       (group {@code 01 CORPT0AI}, lines 18-120). The editable PIC X
 *       input leaves are {@code MONTHLYI PIC X(1)},
 *       {@code YEARLYI PIC X(1)}, {@code CUSTOMI PIC X(1)},
 *       {@code SDTMMI PIC X(2)}, {@code SDTDDI PIC X(2)},
 *       {@code SDTYYYYI PIC X(4)}, {@code EDTMMI PIC X(2)},
 *       {@code EDTDDI PIC X(2)}, {@code EDTYYYYI PIC X(4)}, and
 *       {@code CONFIRMI PIC X(1)}.</li>
 *   <li>Translated COBOL program: {@code app/cbl/CORPT00C.cbl}
 *       ({@code PROGRAM-ID CORPT00C}, {@code WS-TRANID 'CR00'}). The
 *       {@code EVALUATE EIBAID} block at lines 184-196 enumerates the
 *       AID keys handled by the program: {@code DFHENTER}
 *       (process selection), {@code DFHPF3} (back to main menu), and
 *       {@code WHEN OTHER} (invalid key error).</li>
 * </ul>
 *
 * <h2>PIC X(n) length normalization &mdash; clamp not throw</h2>
 * <p>The canonical (compact) constructor coerces {@code null} {@link String}
 * components to the empty {@link String} (matching COBOL SPACES default
 * for an unset {@code PIC X(n)} field) and truncates over-length values
 * to their declared BMS width. This mirrors what the 3270 hardware
 * enforces on real RECEIVE-MAP traffic and matches the lenient pattern
 * documented in {@code MIGRATION_NOTES.md}.
 *
 * @param transactionName  TRNNAMEI  PIC X(4)   — not normally edited by operator
 * @param title01          TITLE01I  PIC X(40)  — header field
 * @param currentDate      CURDATEI  PIC X(8)   — header field
 * @param programName      PGMNAMEI  PIC X(8)   — header field
 * @param title02          TITLE02I  PIC X(40)  — header field
 * @param currentTime      CURTIMEI  PIC X(8)   — header field
 * @param monthly          MONTHLYI  PIC X(1)   — "Y" to select Monthly report
 * @param yearly           YEARLYI   PIC X(1)   — "Y" to select Yearly report
 * @param custom           CUSTOMI   PIC X(1)   — "Y" to select Custom report
 * @param startMonth       SDTMMI    PIC X(2)   — Custom: start month
 * @param startDay         SDTDDI    PIC X(2)   — Custom: start day
 * @param startYear        SDTYYYYI  PIC X(4)   — Custom: start year
 * @param endMonth         EDTMMI    PIC X(2)   — Custom: end month
 * @param endDay           EDTDDI    PIC X(2)   — Custom: end day
 * @param endYear          EDTYYYYI  PIC X(4)   — Custom: end year
 * @param confirmation     CONFIRMI  PIC X(1)   — Y/N confirmation prior to submit
 * @param aidKey           AID key (DFHENTER / DFHPF3 / OTHER)
 */
@CobolProgram(
        value = "CORPT00C",
        sourcePath = "app/cpy-bms/CORPT00.CPY",
        notes = "BMS input DTO for the print-transaction-report screen. " +
                "Three report-type radio buttons (monthly/yearly/custom), " +
                "six custom-range date pieces, and a Y/N confirmation. " +
                "Header-row fields are present for symmetry with the output " +
                "DTO but are normally written by the program, not the operator."
)
public record CoRpt00Input(
        String transactionName,  // TRNNAMEI PIC X(4)
        String title01,          // TITLE01I PIC X(40)
        String currentDate,      // CURDATEI PIC X(8)
        String programName,      // PGMNAMEI PIC X(8)
        String title02,          // TITLE02I PIC X(40)
        String currentTime,      // CURTIMEI PIC X(8)
        String monthly,          // MONTHLYI PIC X(1)
        String yearly,           // YEARLYI  PIC X(1)
        String custom,           // CUSTOMI  PIC X(1)
        String startMonth,       // SDTMMI   PIC X(2)
        String startDay,         // SDTDDI   PIC X(2)
        String startYear,        // SDTYYYYI PIC X(4)
        String endMonth,         // EDTMMI   PIC X(2)
        String endDay,           // EDTDDI   PIC X(2)
        String endYear,          // EDTYYYYI PIC X(4)
        String confirmation,     // CONFIRMI PIC X(1)
        AidKey aidKey
) {

    /**
     * Compact (canonical) constructor enforcing COBOL &ldquo;SPACES by
     * default&rdquo; semantics on every {@link String} component and
     * clamping over-length values to their declared BMS {@code PIC X(n)}
     * widths.
     *
     * <p>JEP 513 (finalized in Java 25) Flexible Constructor Bodies
     * permits this normalization to run before the canonical field
     * bindings.
     */
    public CoRpt00Input {
        transactionName = clamp(orEmpty(transactionName),  4);
        title01         = clamp(orEmpty(title01),         40);
        currentDate     = clamp(orEmpty(currentDate),      8);
        programName     = clamp(orEmpty(programName),      8);
        title02         = clamp(orEmpty(title02),         40);
        currentTime     = clamp(orEmpty(currentTime),      8);
        monthly         = clamp(orEmpty(monthly),          1);
        yearly          = clamp(orEmpty(yearly),           1);
        custom          = clamp(orEmpty(custom),           1);
        startMonth      = clamp(orEmpty(startMonth),       2);
        startDay        = clamp(orEmpty(startDay),         2);
        startYear       = clamp(orEmpty(startYear),        4);
        endMonth        = clamp(orEmpty(endMonth),         2);
        endDay          = clamp(orEmpty(endDay),           2);
        endYear         = clamp(orEmpty(endYear),          4);
        confirmation    = clamp(orEmpty(confirmation),     1);
        if (aidKey == null) {
            aidKey = AidKey.ENTER;
        }
    }

    /**
     * AID-key alias enum scoped to this Input record.
     *
     * <p>Translates the {@code EVALUATE EIBAID} dispatch block at lines
     * 184-196 of {@code app/cbl/CORPT00C.cbl} into a typed enum that the
     * controller's pattern-matching switch can consume exhaustively.
     */
    public enum AidKey {
        /** {@code DFHENTER}: process the operator's selection. */
        ENTER,
        /** {@code DFHPF3}: return to the previous program (default COMEN01C). */
        PF03_BACK,
        /** {@code WHEN OTHER}: any unmapped AID key. */
        OTHER
    }

    /**
     * Factory returning a fully blank instance &mdash; every {@link String}
     * field is the empty {@link String} <code>""</code> and the AID key is
     * {@link AidKey#ENTER}.
     *
     * <p>Used by {@code CoRpt00C} on initial entry to the program before
     * any RECEIVE-MAP has occurred, mirroring the COBOL state after
     * {@code MOVE LOW-VALUES TO CORPT0AO} (see {@code app/cbl/CORPT00C.cbl}
     * line 179).
     *
     * @return a {@code CoRpt00Input} with all 16 string fields set to
     *         {@code ""} and {@link #aidKey()} = {@link AidKey#ENTER}
     */
    public static CoRpt00Input blank() {
        return new CoRpt00Input(
                "", "", "", "", "", "",   // header row
                "", "", "",               // monthly/yearly/custom
                "", "", "",               // start month/day/year
                "", "", "",               // end month/day/year
                "",                       // confirmation
                AidKey.ENTER
        );
    }

    /**
     * Returns a copy with a different {@code monthly} component.
     *
     * <p>Records in finalized Java 25 do not have built-in {@code with}
     * syntax (the {@code with} expression is still a preview feature).
     */
    public CoRpt00Input withMonthly(String newMonthly) {
        return new CoRpt00Input(
                transactionName, title01, currentDate, programName, title02, currentTime,
                newMonthly, yearly, custom,
                startMonth, startDay, startYear,
                endMonth, endDay, endYear,
                confirmation, aidKey);
    }

    /** Returns a copy with a different {@link AidKey}. */
    public CoRpt00Input withAidKey(AidKey newAidKey) {
        return new CoRpt00Input(
                transactionName, title01, currentDate, programName, title02, currentTime,
                monthly, yearly, custom,
                startMonth, startDay, startYear,
                endMonth, endDay, endYear,
                confirmation, newAidKey);
    }

    /** Returns a copy with a different {@code confirmation} component. */
    public CoRpt00Input withConfirmation(String newConfirmation) {
        return new CoRpt00Input(
                transactionName, title01, currentDate, programName, title02, currentTime,
                monthly, yearly, custom,
                startMonth, startDay, startYear,
                endMonth, endDay, endYear,
                newConfirmation, aidKey);
    }

    /**
     * Null-to-empty coercion helper. Returns the input {@link String}
     * unchanged unless it is {@code null}, in which case it returns the
     * empty {@link String} <code>""</code> &mdash; matching COBOL
     * &ldquo;SPACES by default&rdquo; semantics.
     */
    private static String orEmpty(String s) {
        return (s == null) ? "" : s;
    }

    /**
     * Length-clamping helper. Returns the input {@link String} unchanged
     * if its length is &le; {@code maxLen}, otherwise returns the leading
     * {@code maxLen}-character prefix &mdash; matching CICS RECEIVE-MAP
     * truncation semantics on the 3270 hardware.
     */
    private static String clamp(String s, int maxLen) {
        return (s.length() > maxLen) ? s.substring(0, maxLen) : s;
    }
}
