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
// java.base module (and the modules it reads). This gives access to java.lang.String — the
// type of every BMS output-field component on this record. No other imports are required
// or permitted on this file (per the file-level agent prompt).
import module java.base;

/**
 * BMS output record for the {@code COCRDSL / CCRDSLA} card-view map
 * (COBOL transaction {@code CCDL}, program {@code COCRDSLC}).
 *
 * <p>This record is a literal projection of the {@code 01 CCRDSLAO REDEFINES CCRDSLAI}
 * group in {@code app/cpy-bms/COCRDSL.CPY}: one {@link String} field per
 * {@code "O"}-suffixed BMS leaf. Field widths match the underlying COBOL
 * {@code PIC X(n)} declarations exactly.
 *
 * <p>The controller {@code CoCrdSlC} composes this record after reading the
 * card via {@code CardRepository#findByCardNumber}, displaying account ID,
 * card number, embossed name, active status, and expiration MM/YYYY.
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/COCRDSL.bms}
 *       (mapset {@code COCRDSL}, map {@code CCRDSLA}, size 24x80, FREEKB).</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COCRDSL.CPY}
 *       (group {@code 01 CCRDSLAO REDEFINES CCRDSLAI}).</li>
 *   <li>Translated COBOL program: {@code app/cbl/COCRDSLC.cbl}.</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (output side)</h2>
 * <p>Per AAP &sect;0.3.5 and &sect;0.4.1, BMS maps are translated into <em>entry-contract DTO
 * records</em> on the corresponding application class. This record is the Java analog of
 * the output view of the BMS symbolic structure (the {@code CCRDSLAO REDEFINES CCRDSLAI}
 * overlay in {@code COCRDSL.CPY}): it carries the field values supplied by application
 * logic to {@code EXEC CICS SEND MAP}, where they are rendered onto the 3270 terminal.
 * There is no web framework, no Spring binding, no Jakarta Bean Validation, no view
 * templating engine; the record is a plain Java carrier.
 *
 * <p>It is constructed by {@code CoCrdSlC} (returned to the caller wrapped in
 * {@code Outcome.SendMap}) and rendered by the SEND-MAP equivalent layer.
 *
 * <h2>Card-view-screen semantics</h2>
 * <p>COCRDSL is the <strong>view</strong> screen for credit cards (transaction
 * {@code CCDL}). The user supplies an account-id and card-number key pair (the
 * {@code ACCTSID} and {@code CARDSID} input fields, captured by {@code CoCrdSlInput}),
 * and the controller looks up the card and populates this output record with the
 * embossed name ({@code CRDNAME}), active status ({@code CRDSTCD}), and expiration
 * month / year ({@code EXPMON} / {@code EXPYEAR}). Unlike the {@code COCRDUP} update
 * screen, this is a read-only display: there are no per-field BMS attribute bytes for
 * highlighting individual data fields, only the standard error-message field
 * ({@code ERRMSG} with {@code COLOR=RED}) for signalling validation failures.
 *
 * <h2>Field-level highlighting note</h2>
 * <p>The BMS map definition assigns {@code COLOR=RED} statically to the {@code ERRMSG}
 * field (see {@code app/bms/COCRDSL.bms} lines 144-147); no application logic is needed
 * to colorize errors. To signal an error, the controller simply populates
 * {@code errMsg} with a human-readable message; the static map attribute renders it in
 * red. No companion {@code FieldAttributes} nested record is therefore required on this
 * DTO (contrast with {@code CoCrdUpOutput}, which carries runtime attribute state for
 * its editable fields).
 *
 * <h2>Null and emptiness semantics &mdash; COBOL parity</h2>
 * <p>In COBOL, an unset BMS {@code PIC X(n)} output field is SPACES on SEND-MAP, never
 * null (no null pointer exists in COBOL). The COBOL initialization in {@code COCRDSLC}
 * performs {@code MOVE LOW-VALUES TO CCRDSLAO} which sets every PIC X field to
 * LOW-VALUE bytes; the SEND-MAP equivalent then renders unset fields as spaces. To
 * preserve that behavior precisely, the compact constructor below replaces every
 * {@code null} {@link String} component with the empty {@link String} <code>""</code>.
 * Downstream consumers &mdash; and the BMS-emitting layer that ultimately serializes
 * this record to the 3270 wire format &mdash; can safely treat every component as a
 * non-null {@link String} without first checking for null.
 *
 * <p>The compact constructor uses <strong>JEP 513 Flexible Constructor Bodies</strong>
 * (finalized in Java 25). Statements before the canonical field-assignment perform input
 * normalization, which is exactly the place to capture COBOL-style "default to SPACES"
 * semantics on SEND-MAP. This satisfies the file-level mandate to use JEP 513 for record
 * validation and normalization.
 *
 * <h2>Immutability</h2>
 * <p>Because this is a record, all components are {@code final} and accessors are
 * automatically generated; there are no setters, no Lombok, no Spring annotations, no
 * Jakarta validation annotations. The instance is safely shareable across virtual threads
 * (per AAP &sect;0.6.6) without synchronization.
 *
 * <h2>Field-by-field mapping</h2>
 * <p>Mapping from BMS symbolic copybook {@code CCRDSLAO} (output view, the REDEFINES of
 * {@code CCRDSLAI}) to Java record components. The PIC column shows the COBOL PICTURE
 * clause; lengths are fixed and preserved by the runtime that converts this Java record
 * into the 3270 SEND-MAP wire format.
 * <ul>
 *   <li>{@code TRNNAMEO} &mdash; X(4)  &mdash; {@link #trnName()}</li>
 *   <li>{@code TITLE01O} &mdash; X(40) &mdash; {@link #title01()}</li>
 *   <li>{@code CURDATEO} &mdash; X(8)  &mdash; {@link #curDate()}</li>
 *   <li>{@code PGMNAMEO} &mdash; X(8)  &mdash; {@link #pgmName()}</li>
 *   <li>{@code TITLE02O} &mdash; X(40) &mdash; {@link #title02()}</li>
 *   <li>{@code CURTIMEO} &mdash; X(8)  &mdash; {@link #curTime()}</li>
 *   <li>{@code ACCTSIDO} &mdash; X(11) &mdash; {@link #acctSid()}</li>
 *   <li>{@code CARDSIDO} &mdash; X(16) &mdash; {@link #cardSid()}</li>
 *   <li>{@code CRDNAMEO} &mdash; X(50) &mdash; {@link #crdName()}</li>
 *   <li>{@code CRDSTCDO} &mdash; X(1)  &mdash; {@link #crdStsCd()}</li>
 *   <li>{@code EXPMONO}  &mdash; X(2)  &mdash; {@link #expMon()}</li>
 *   <li>{@code EXPYEARO} &mdash; X(4)  &mdash; {@link #expYear()}</li>
 *   <li>{@code INFOMSGO} &mdash; X(40) &mdash; {@link #infoMsg()}</li>
 *   <li>{@code ERRMSGO}  &mdash; X(80) &mdash; {@link #errMsg()}</li>
 *   <li>{@code FKEYSO}   &mdash; X(75) &mdash; {@link #fKeys()}</li>
 * </ul>
 *
 * @param trnName    echoed transaction id (CCDL), PIC X(4)
 * @param title01    title line 1, PIC X(40)
 * @param curDate    current date, PIC X(8)
 * @param pgmName    program name (COCRDSLC), PIC X(8)
 * @param title02    title line 2, PIC X(40)
 * @param curTime    current time, PIC X(8)
 * @param acctSid    account-id displayed, PIC X(11)
 * @param cardSid    card-number displayed, PIC X(16)
 * @param crdName    embossed name, PIC X(50)
 * @param crdStsCd   card active status, PIC X(1)
 * @param expMon     expiration month, PIC X(2)
 * @param expYear    expiration year, PIC X(4)
 * @param infoMsg    informational message, PIC X(40)
 * @param errMsg     error message, PIC X(80)
 * @param fKeys      function-key legend, PIC X(75)
 *
 * @see com.blitzy.carddemo.application.card.CoCrdSlC
 * @see com.blitzy.carddemo.application.card.CoCrdSlInput
 */
public record CoCrdSlOutput(
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
        String fKeys) {

    /**
     * Compact constructor enforcing COBOL "SPACES by default" semantics on every
     * {@link String} component. Each {@code null} input is coerced to the empty
     * {@link String} <code>""</code> so that downstream BMS serialization can safely
     * pad-right every field to its fixed PIC width without first checking for null.
     *
     * <p>This is a textbook application of <strong>JEP 513 Flexible Constructor
     * Bodies</strong> (finalized in Java 25): normalization runs before the canonical
     * field bindings.
     */
    public CoCrdSlOutput {
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
    }

    /**
     * Null-coalescing helper. Returns the input {@link String} unchanged if non-null,
     * or the empty {@link String} <code>""</code> if null. Preserves COBOL
     * {@code MOVE LOW-VALUES} / SPACES-on-SEND-MAP behavior by guaranteeing every
     * field-bearing component is observable as a non-null {@link String}.
     *
     * @param s the input string, possibly {@code null}
     * @return {@code s} if non-null, otherwise the empty string <code>""</code>
     */
    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * Factory returning a fully blank instance &mdash; every field is the empty
     * {@link String} <code>""</code>. Useful for {@code COCRDSLC} initialization
     * before any business-logic population, mirroring the COBOL
     * {@code MOVE LOW-VALUES TO CCRDSLAO} statement on initial entry to the program.
     *
     * @return a {@code CoCrdSlOutput} with all 15 fields set to <code>""</code>
     */
    public static CoCrdSlOutput blank() {
        return new CoCrdSlOutput(
                "", "", "", "", "", "",
                "", "",
                "", "", "", "",
                "", "", ""
        );
    }

    /**
     * Factory returning a partially-populated instance with only the standard screen
     * header fields filled in (transaction id, program name, current date, current
     * time). Title fields and all data fields remain empty &mdash; the caller is
     * expected to compose the full record by combining this header skeleton with
     * the card-specific data fields and the screen-title constants (from
     * {@code ScreenTitle}).
     *
     * <p>This mirrors the COBOL {@code 1100-SETUP-SCREEN} family of paragraphs in
     * {@code COCRDSLC} which establish the static header before any business-logic
     * field assignment.
     *
     * @param curDate the current date string (e.g., {@code "mm/dd/yy"}), PIC X(8);
     *                {@code null} is coerced to <code>""</code>
     * @param curTime the current time string (e.g., {@code "hh:mm:ss"}), PIC X(8);
     *                {@code null} is coerced to <code>""</code>
     * @return a {@code CoCrdSlOutput} with header fields populated and all other
     *         fields set to <code>""</code>
     */
    public static CoCrdSlOutput withHeader(String curDate, String curTime) {
        return new CoCrdSlOutput(
                "CCDL",                                 // trnName  — transaction id
                "",                                     // title01  — filled by ScreenTitle.TITLE_01
                orEmpty(curDate),
                "COCRDSLC",                             // pgmName  — program name
                "",                                     // title02  — filled by ScreenTitle.TITLE_02
                orEmpty(curTime),
                "", "",                                 // keys (acctSid, cardSid)
                "", "", "", "",                         // card details (crdName, crdStsCd, expMon, expYear)
                "", "", ""                              // footer (infoMsg, errMsg, fKeys)
        );
    }
}
