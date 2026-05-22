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
// the compact-constructor null check on the nested AidKey component. No other imports are
// required or permitted on this file (per the file-level agent prompt).
import module java.base;

/**
 * BMS input record for the {@code COCRDSL / CCRDSLA} card-view map
 * (COBOL transaction {@code CCDL}, program {@code COCRDSLC}).
 *
 * <p>This record is a literal projection of the {@code 01 CCRDSLAI} group in
 * {@code app/cpy-bms/COCRDSL.CPY}: one {@link String} field per
 * {@code "I"}-suffixed BMS leaf. Field widths match the underlying COBOL
 * {@code PIC X(n)} declarations exactly.
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/COCRDSL.bms} (mapset {@code COCRDSL},
 *       map {@code CCRDSLA}, size 24x80, {@code CTRL=FREEKB}).</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COCRDSL.CPY},
 *       structure {@code 01 CCRDSLAI}.</li>
 *   <li>Translated COBOL program: {@code COCRDSLC}
 *       ({@code app/cbl/COCRDSLC.cbl}).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (input side)</h2>
 * <p>Per AAP &sect;0.3.5 and &sect;0.4.1, BMS maps are translated into <em>entry-contract DTO
 * records</em> on the corresponding application class. This record is the Java analog of the
 * input view of the BMS symbolic structure (the {@code CCRDSLAI} group in
 * {@code COCRDSL.CPY}): it carries the field values received from the 3270 terminal by
 * {@code EXEC CICS RECEIVE MAP}, where they were typed by the operator. There is no web
 * framework, no Spring binding, no Jakarta Bean Validation, no controller mapping; the
 * record is a plain Java carrier.
 *
 * <p>It is consumed by {@code CoCrdSlC.processInbound(...)} (the Java translation of the
 * COBOL {@code 1000-PROCESS-INPUTS} family of paragraphs &mdash; {@code 1100-RECEIVE-MAP},
 * {@code 1200-EDIT-MAP-INPUTS}, etc.). All string fields are <em>raw, untrimmed</em>
 * projections of the COBOL fields with their fixed {@code PIC X(n)} widths preserved by
 * the caller; null-safety is handled by the compact constructor below.
 *
 * <h2>AID-key handling (transaction-specific simplification)</h2>
 * <p>The {@link AidKey} enum is a transaction-specific simplification listing the AID keys
 * that {@code COCRDSLC} handles per {@code app/cbl/COCRDSLC.cbl}: ENTER (validate / show
 * card detail) and {@link AidKey#PF03_BACK PF03} (exit back to the previous program,
 * typically the {@code COCRDLIC} card list or the {@code COMEN01C} main menu). Any other
 * AID key falls through to the {@link AidKey#OTHER} bucket where the controller emits the
 * standard "Invalid key pressed" message and re-displays the screen.
 *
 * <h2>Echoed search-key fields</h2>
 * <p>{@code acctSid} and {@code cardSid} are echoed back from the 3270 terminal because
 * BMS sends back the full screen on every {@code RECEIVE MAP}, including the (otherwise
 * read-only) search keys. They are treated as identifying input on subsequent RECEIVE
 * cycles &mdash; see paragraph {@code 1200-EDIT-MAP-INPUTS} in
 * {@code app/cbl/COCRDSLC.cbl}.
 *
 * <h2>Echoed card-detail fields</h2>
 * <p>{@code crdName}, {@code crdStsCd}, {@code expMon}, and {@code expYear} are likewise
 * echoed back even though the card-view screen does not edit them; the BMS contract
 * requires every field be projected to the terminal and returned on the next RECEIVE.
 * The Java record preserves them for byte-for-byte parity with the COBOL symbolic-map
 * layout.
 *
 * <h2>Echoed function-key bar</h2>
 * <p>{@code fKeys} is a function-key bar literal echo from a prior {@code SEND MAP}
 * (typically "ENTER=Process F3=Exit" or similar). It is preserved on this record per the
 * byte-fidelity mandate (AAP &sect;0.7.1) so any unchanged screen state survives the
 * RECEIVE/SEND round-trip without modification.
 *
 * <h2>Immutability and null-safety</h2>
 * <p>This record is fully immutable. The compact canonical constructor (JEP 513
 * Flexible Constructor Bodies, finalized in Java 25) coalesces {@code null} String
 * values to the empty string (matching COBOL low-values / spaces semantics for unset
 * BMS input fields) and requires a non-null {@link AidKey} via
 * {@link java.util.Objects#requireNonNull(Object, String)}.
 *
 * @param trnName    echoed transaction id (CCDL), PIC X(4)
 * @param title01    title line 1, PIC X(40)
 * @param curDate    current date, PIC X(8)
 * @param pgmName    program name (COCRDSLC), PIC X(8)
 * @param title02    title line 2, PIC X(40)
 * @param curTime    current time, PIC X(8)
 * @param acctSid    account-id search key, PIC X(11) (must be 11 numeric)
 * @param cardSid    card-number search key (optional), PIC X(16)
 * @param crdName    echoed embossed name, PIC X(50)
 * @param crdStsCd   echoed card active status, PIC X(1) ('Y' or 'N')
 * @param expMon     echoed expiration month, PIC X(2) (01..12)
 * @param expYear    echoed expiration year, PIC X(4) (CCYY)
 * @param infoMsg    informational message, PIC X(40)
 * @param errMsg     error message, PIC X(80)
 * @param fKeys      function-key legend echo, PIC X(75)
 * @param aidKey     decoded transaction-level AID key, never null
 *
 * @see com.blitzy.carddemo.application.card.CoCrdSlC
 * @see com.blitzy.carddemo.application.card.CoCrdSlOutput
 */
public record CoCrdSlInput(
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
        String infoMsg,
        String errMsg,
        String fKeys,
        AidKey aidKey) {

    /**
     * Transaction-level AID-key taxonomy for the {@code COCRDSL / CCRDSLA} map.
     *
     * <p>This is a deliberately small enum &mdash; not the full DFHAID set &mdash; listing only
     * the AID keys that the {@code COCRDSLC} program handles. Per
     * {@code app/cbl/COCRDSLC.cbl} (the {@code WS-PFK-FLAG} / {@code CCARD-AID-ENTER} /
     * {@code CCARD-AID-PFK03} test in the main paragraph), the program only reacts to
     * ENTER (submit / fetch card detail) and PF03 (exit back). All other AID keys fall
     * through to the {@link #OTHER} bucket where the controller emits the standard
     * "Invalid key pressed" message and re-displays the screen.
     *
     * <p>This taxonomy is intentionally a flat Java {@code enum} (not a sealed interface)
     * because the AID-key set for this transaction is closed and exhaustive at three
     * members. Pattern-matching {@code switch} on this enum benefits from compiler
     * exhaustiveness checks without needing the heavier sealed-hierarchy machinery
     * described in AAP &sect;0.6.10 for cross-transaction AID-key handling.
     */
    public enum AidKey {
        /** The user pressed ENTER (submit). */
        ENTER,
        /** PF03 - exit back to previous program (COCRDLIC list or COMEN01C menu). */
        PF03_BACK,
        /** Any other key - produces "Invalid key pressed" error. */
        OTHER
    }

    /**
     * Compact canonical constructor.
     *
     * <p>Null-coalesces every {@link String} field to the empty string &mdash; this matches the
     * COBOL convention where an unset BMS input field is delivered as low-values / spaces
     * rather than as a true null. Requires a non-null {@link AidKey}; supplying
     * {@link AidKey#OTHER} is the correct sentinel for "no key recognised" rather than
     * passing {@code null}.
     *
     * <p>This uses JEP 513 Flexible Constructor Bodies (finalized in Java 25): the
     * normalisation logic runs <em>before</em> the implicit canonical field assignment
     * that closes the compact constructor, which is the right place for COBOL-style input
     * normalisation per AAP &sect;0.6.3.
     *
     * @throws NullPointerException if {@code aidKey} is null
     */
    public CoCrdSlInput {
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
        infoMsg = orEmpty(infoMsg);
        errMsg = orEmpty(errMsg);
        fKeys = orEmpty(fKeys);
        Objects.requireNonNull(aidKey, "aidKey must not be null");
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
     * Returns a "blank" input record &mdash; all string fields are empty strings, the AID key
     * is {@link AidKey#OTHER}. This is the canonical seed for the initial-entry path in
     * {@code CoCrdSlC} (the COBOL {@code FIRST-TIME} / {@code NOT CDEMO-PGM-REENTER}
     * branch where no user input is available yet).
     *
     * @return a fresh {@code CoCrdSlInput} with every text field set to {@code ""} and
     *         {@code aidKey} set to {@link AidKey#OTHER}
     */
    public static CoCrdSlInput blank() {
        return new CoCrdSlInput(
                "", "", "", "", "", "",
                "", "",
                "", "", "", "",
                "", "", "",
                AidKey.OTHER
        );
    }

    /**
     * Returns a "seeded" input record carrying only the search-key fields populated &mdash;
     * the account id and card number &mdash; with every other text field empty and the AID
     * key set to {@link AidKey#OTHER}. This is the helper used by {@code CoCrdSlC} when
     * the controller is dispatched with values pre-populated from
     * {@link com.blitzy.carddemo.domain.commarea.CardDemoCommarea} (XCTL hand-off from
     * {@code COMEN01C} / {@code COCRDLIC} carrying the user's prior selection).
     *
     * <p>The supplied {@code acctSid} and {@code cardSid} are not validated, trimmed, or
     * padded by this factory &mdash; the COBOL convention applies (the controller's edit
     * paragraphs are the single source of truth for input validation). When invoked with
     * a null argument, the compact canonical constructor's null-coalescing logic
     * normalises it to the empty string.
     *
     * @param acctSid the 11-character account id (typically already left-padded with zeros)
     * @param cardSid the 16-character card number (typically already left-padded with zeros);
     *                may be empty when the search is to be performed by account id alone
     *                using the {@code CARDAIX} alternate index
     * @return a fresh {@code CoCrdSlInput} carrying just the supplied keys
     */
    public static CoCrdSlInput withKeys(String acctSid, String cardSid) {
        return new CoCrdSlInput(
                "", "", "", "", "", "",
                acctSid, cardSid,
                "", "", "", "",
                "", "", "",
                AidKey.OTHER
        );
    }
}
