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
package com.blitzy.carddemo.application.card;

// JEP 511 (finalized in Java 25): a single declaration imports all packages exported by the
// java.base module (and the modules it reads). This gives access to java.lang.String -- the
// type of every BMS input-field component on this record -- and to java.util.Objects for
// the compact-constructor null check on the nested AidKey component.
import module java.base;

// Module-import declarations may not import application-defined types; the COBOL traceability
// annotation lives in carddemo-domain and must be brought in by a conventional import.
import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * BMS input record for the {@code COCRDUP / CCRDUPA} card-update map
 * (COBOL transaction {@code CCUP}, program {@code COCRDUPC}).
 *
 * <p>This record is a literal projection of the {@code 01 CCRDUPAI} group in
 * {@code app/cpy-bms/COCRDUP.CPY}: one {@link String} field per
 * {@code "I"}-suffixed BMS leaf. Field widths match the underlying COBOL
 * {@code PIC X(n)} declarations exactly.
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/COCRDUP.bms} (mapset {@code COCRDUP},
 *       map {@code CCRDUPA}, size 24x80, {@code CTRL=FREEKB}).</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COCRDUP.CPY},
 *       structure {@code 01 CCRDUPAI}.</li>
 *   <li>Translated COBOL program: {@code COCRDUPC}
 *       ({@code app/cbl/COCRDUPC.cbl}).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (input side)</h2>
 * <p>Per AAP &sect;0.3.5 and &sect;0.4.1, BMS maps are translated into <em>entry-contract DTO
 * records</em> on the corresponding application class. This record is the Java analog of the
 * input view of the BMS symbolic structure (the {@code CCRDUPAI} group in
 * {@code COCRDUP.CPY}): it carries the field values received from the 3270 terminal by
 * {@code EXEC CICS RECEIVE MAP}, where they were typed by the operator. There is no web
 * framework, no Spring binding, no Jakarta Bean Validation, no controller mapping; the
 * record is a plain Java carrier.
 *
 * <p>It is consumed by {@code CoCrdUpC.processInbound(...)} (the Java translation of the
 * COBOL {@code 1000-PROCESS-INPUTS} family of paragraphs &mdash; {@code 1100-RECEIVE-MAP},
 * {@code 1200-EDIT-MAP-INPUTS}, etc.). All string fields are <em>raw, untrimmed</em>
 * projections of the COBOL fields with their fixed {@code PIC X(n)} widths preserved by
 * the caller; null-safety is handled by the compact constructor below.
 *
 * <h2>AID-key handling (transaction-specific simplification)</h2>
 * <p>The {@link AidKey} enum is a transaction-specific simplification listing the AID keys
 * that {@code COCRDUPC} handles per {@code app/cbl/COCRDUPC.cbl} (paragraph
 * {@code 0000-MAIN}, lines 414&ndash;489): ENTER (validate / commit),
 * {@link AidKey#PF03_BACK PF03} (exit), {@link AidKey#PF04_CLEAR PF04} (clear),
 * {@link AidKey#PF05_SAVE PF05} (save &mdash; only valid in {@code CHANGES-OK-NOT-CONFIRMED}
 * state), {@link AidKey#PF12_CANCEL PF12} (cancel &mdash; only valid when
 * {@code DETAILS-NOT-FETCHED} is false). State-machine validity of the AID keys is
 * enforced by {@code CoCrdUpC}, not by this record (this record only carries the decoded
 * value).
 *
 * <h2>Hidden {@code expDay} field</h2>
 * <p>The {@code expDay} field is a <em>hidden</em> attribute on the COBOL screen
 * (BMS attribute {@code DFHBMDAR / DRK} on the {@code EXPDAY} field per
 * {@code app/bms/COCRDUP.bms}, line 142), used internally to construct a 10-char date
 * string with {@code MM/DD/CCYY} formatting. By convention the COBOL program holds it at
 * "01" so that the user only sees and edits month and year. The Java translation preserves
 * the field for byte-for-byte parity with the COBOL symbolic-map layout.
 *
 * <h2>Two function-key bars</h2>
 * <p>The COBOL map defines two function-key legends on row 24 of the 3270 screen:
 * {@code FKEYS} ({@code PIC X(21)}) and {@code FKEYSC} ({@code PIC X(18)}) per
 * {@code app/bms/COCRDUP.bms} lines 158&ndash;167. The first is the always-shown
 * "ENTER=Process F3=Exit" legend; the second is the conditional
 * "F5=Save F12=Cancel" legend that the application toggles visible/dark depending on
 * the program state. Both echoes are preserved as input fields for symmetry with the
 * symbolic map structure.
 *
 * <h2>Immutability and null-safety</h2>
 * <p>This record is fully immutable. The compact canonical constructor (JEP 513
 * Flexible Constructor Bodies, finalized in Java 25) coalesces {@code null} String
 * values to the empty string (matching COBOL low-values / spaces semantics for unset
 * BMS input fields) and requires a non-null {@link AidKey} via
 * {@link java.util.Objects#requireNonNull(Object, String)}.
 *
 * @param trnName    echoed transaction id (CCUP), PIC X(4)
 * @param title01    title line 1, PIC X(40)
 * @param curDate    current date, PIC X(8)
 * @param pgmName    program name (COCRDUPC), PIC X(8)
 * @param title02    title line 2, PIC X(40)
 * @param curTime    current time, PIC X(8)
 * @param acctSid    account-id key, PIC X(11) (must be 11 numeric, non-zero)
 * @param cardSid    card-number key, PIC X(16) (must be 16 numeric, mandatory)
 * @param crdName    editable embossed name, PIC X(50) (alpha + spaces only)
 * @param crdStsCd   editable card active status, PIC X(1) ('Y' or 'N')
 * @param expMon     editable expiration month, PIC X(2) (01..12)
 * @param expYear    editable expiration year, PIC X(4) (valid CCYY)
 * @param expDay     hidden day field, PIC X(2) (held at "01" by convention)
 * @param infoMsg    informational message, PIC X(40)
 * @param errMsg     error message, PIC X(80)
 * @param fKeys      function-key legend echo, PIC X(21)
 * @param fKeysC     additional function-key legend echo, PIC X(18)
 * @param aidKey     decoded transaction-level AID key, never null
 *
 * @see com.blitzy.carddemo.application.card.CoCrdUpC
 * @see com.blitzy.carddemo.application.card.CoCrdUpOutput
 */
@CobolProgram(
        value = "COCRDUP",
        sourcePath = "app/bms/COCRDUP.bms",
        translationDate = "2025-10-15",
        notes = "BMS entry-contract DTO (input side); symbolic copybook CCRDUPAI "
                + "in app/cpy-bms/COCRDUP.CPY lines 16-121. Field-for-field "
                + "translation of all 17 PIC X leaves plus decoded AidKey."
)
public record CoCrdUpInput(
        String trnName,
        String title01,
        String curDate,
        String pgmName,
        String title02,
        String curTime,
        String acctSid,
        String cardSid,
        String crdName,
        String crdStsCd,
        String expMon,
        String expYear,
        String expDay,
        String infoMsg,
        String errMsg,
        String fKeys,
        String fKeysC,
        AidKey aidKey) {

    /**
     * Transaction-level AID-key taxonomy for the {@code COCRDUP / CCRDUPA} map.
     *
     * <p>This is a deliberately small enum &mdash; not the full DFHAID set &mdash; listing only
     * the AID keys that the {@code COCRDUPC} program handles. Per
     * {@code app/cbl/COCRDUPC.cbl} (paragraph {@code 0000-MAIN}, lines 414&ndash;489), the
     * program reacts to ENTER, PF03, PF05 (state-gated by {@code CCUP-CHANGES-OK-NOT-CONFIRMED}),
     * and PF12 (state-gated by {@code NOT CCUP-DETAILS-NOT-FETCHED}); all other AID keys
     * fall through to the {@link #OTHER} bucket where the controller emits the standard
     * "Invalid key pressed" message.
     *
     * <p>PF04 (clear) is reserved &mdash; it is not currently routed by {@code COCRDUPC},
     * but the constant is kept here for symmetry with the broader CardDemo
     * function-key convention and to allow controllers to map PF04 explicitly should
     * they choose to do so without changing this record's surface area.
     */
    public enum AidKey {
        /** The user pressed ENTER (validate / fetch / show). */
        ENTER,
        /** PF03 - exit back to previous program (typically COCRDLIC list). */
        PF03_BACK,
        /** PF04 - clear screen (rarely used, but reserved). */
        PF04_CLEAR,
        /** PF05 - SAVE / commit pending changes (only in ChangesOkNotConfirmed state). */
        PF05_SAVE,
        /** PF12 - cancel changes and re-display old values (only when DETAILS-NOT-FETCHED == false). */
        PF12_CANCEL,
        /** Any other key. */
        OTHER
    }

    /**
     * Compact canonical constructor.
     *
     * <p>Null-coalesces every {@link String} field to the empty string &mdash; this matches the
     * COBOL convention where an unset BMS input field is delivered as low-values / spaces
     * rather than as a true null. Requires a non-null {@link AidKey}; supplying
     * {@code AidKey.OTHER} is the correct sentinel for "no key recognised" rather than
     * passing {@code null}.
     *
     * <p>This uses JEP 513 Flexible Constructor Bodies (finalized in Java 25): the
     * normalisation logic runs <em>before</em> the implicit canonical field assignment
     * that closes the compact constructor, which is the right place for COBOL-style input
     * normalisation per AAP &sect;0.6.3.
     *
     * @throws NullPointerException if {@code aidKey} is null
     */
    public CoCrdUpInput {
        trnName = orEmpty(trnName);
        title01 = orEmpty(title01);
        curDate = orEmpty(curDate);
        pgmName = orEmpty(pgmName);
        title02 = orEmpty(title02);
        curTime = orEmpty(curTime);
        acctSid = orEmpty(acctSid);
        cardSid = orEmpty(cardSid);
        crdName = orEmpty(crdName);
        crdStsCd = orEmpty(crdStsCd);
        expMon = orEmpty(expMon);
        expYear = orEmpty(expYear);
        expDay = orEmpty(expDay);
        infoMsg = orEmpty(infoMsg);
        errMsg = orEmpty(errMsg);
        fKeys = orEmpty(fKeys);
        fKeysC = orEmpty(fKeysC);
        Objects.requireNonNull(aidKey, "aidKey must not be null");

        // PIC X(n) fixed-length validation per app/cpy-bms/COCRDUP.CPY lines 16-121.
        // CICS RECEIVE-MAP hardware-truncates BMS input strings at the declared PIC
        // X(n) width; any value longer than that width indicates an upstream adapter
        // defect (CWE-20 input validation). Shorter values are accepted unchanged.
        checkPicLength("trnName",  trnName,   4);  // TRNNAMEI  PIC X(4)
        checkPicLength("title01",  title01,  40);  // TITLE01I  PIC X(40)
        checkPicLength("curDate",  curDate,   8);  // CURDATEI  PIC X(8)
        checkPicLength("pgmName",  pgmName,   8);  // PGMNAMEI  PIC X(8)
        checkPicLength("title02",  title02,  40);  // TITLE02I  PIC X(40)
        checkPicLength("curTime",  curTime,   8);  // CURTIMEI  PIC X(8)
        checkPicLength("acctSid",  acctSid,  11);  // ACCTSIDI  PIC X(11)
        checkPicLength("cardSid",  cardSid,  16);  // CARDSIDI  PIC X(16)
        checkPicLength("crdName",  crdName,  50);  // CRDNAMEI  PIC X(50)
        checkPicLength("crdStsCd", crdStsCd,  1);  // CRDSTCDI  PIC X(1)
        checkPicLength("expMon",   expMon,    2);  // EXPMONI   PIC X(2)
        checkPicLength("expYear",  expYear,   4);  // EXPYEARI  PIC X(4)
        checkPicLength("expDay",   expDay,    2);  // EXPDAYI   PIC X(2)
        checkPicLength("infoMsg",  infoMsg,  40);  // INFOMSGI  PIC X(40)
        checkPicLength("errMsg",   errMsg,   80);  // ERRMSGI   PIC X(80)
        checkPicLength("fKeys",    fKeys,    21);  // FKEYSI    PIC X(21)
        checkPicLength("fKeysC",   fKeysC,   18);  // FKEYSCI   PIC X(18)
    }

    /**
     * Null-coalescing helper: returns the empty string when the argument is {@code null},
     * otherwise returns the argument unchanged.
     *
     * <p>This deliberately does <em>not</em> trim or pad the string &mdash; preserving COBOL
     * {@code PIC X(n)} fixed-width semantics is the caller's responsibility (the BMS
     * reader populates this record with already-correctly-sized strings).
     *
     * @param s the string to coalesce; may be null
     * @return {@code ""} if {@code s} is null, otherwise {@code s}
     */
    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * Validates that a {@link String} component does not exceed its declared BMS
     * {@code PIC X(n)} on-screen width.
     *
     * <p>Enforces the AAP &sect;0.7.1 Preserve-As-Is contract at the DTO boundary
     * (CWE-20 input validation): values longer than the declared BMS width would
     * cause silent hardware truncation in the CICS RECEIVE-MAP layer. Shorter
     * values are accepted unchanged.
     *
     * @param name      the component name (used in the exception message)
     * @param value     the component value (never {@code null}: the caller
     *                  guarantees normalization via {@link #orEmpty(String)})
     * @param maxLength the declared BMS {@code PIC X(n)} width
     * @throws IllegalArgumentException if {@code value.length() > maxLength}
     */
    private static void checkPicLength(String name, String value, int maxLength) {
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(
                    name + " exceeds BMS PIC X(" + maxLength
                            + ") declared length; received length="
                            + value.length() + " value=\"" + value + "\"");
        }
    }

    /**
     * Returns a "blank" input record &mdash; all string fields are empty strings, the AID key
     * is {@link AidKey#OTHER}. This is the canonical seed for the initial-entry path in
     * {@code CoCrdUpC} (the COBOL {@code FIRST-TIME} / {@code REENTER-AT-INIT} branch
     * where no user input is available yet).
     *
     * @return a fresh {@code CoCrdUpInput} with every text field set to {@code ""} and
     *         {@code aidKey} set to {@link AidKey#OTHER}
     */
    public static CoCrdUpInput blank() {
        return new CoCrdUpInput(
                "", "", "", "", "", "",
                "", "",
                "", "", "", "", "",
                "", "", "", "",
                AidKey.OTHER
        );
    }

    /**
     * Returns a "seeded" input record carrying only the search-key fields populated &mdash;
     * the account id and card number &mdash; with every other text field empty and the AID
     * key set to {@link AidKey#OTHER}. This is the helper used by {@code CoCrdUpC} when
     * the controller is dispatched with values pre-populated from
     * {@link com.blitzy.carddemo.domain.commarea.CardDemoCommarea} (XCTL hand-off from
     * {@code COMEN01C} / {@code COCRDLIC} carrying the user's prior selection).
     *
     * <p>The supplied {@code acctSid} and {@code cardSid} are not validated, trimmed, or
     * padded by this factory &mdash; the COBOL convention applies (the controller's edit
     * paragraphs are the single source of truth for input validation).
     *
     * @param acctSid the 11-character account id (typically already left-padded with zeros)
     * @param cardSid the 16-character card number (typically already left-padded with zeros)
     * @return a fresh {@code CoCrdUpInput} carrying just the supplied keys
     */
    public static CoCrdUpInput withKeys(String acctSid, String cardSid) {
        return new CoCrdUpInput(
                "", "", "", "", "", "",
                acctSid, cardSid,
                "", "", "", "", "",
                "", "", "", "",
                AidKey.OTHER
        );
    }
}
