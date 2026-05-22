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
// type of every BMS output-field component on this record — and to java.util.Objects for
// the compact-constructor null check on the nested FieldAttributes component, plus enum
// support required by the nested AttributeMode taxonomy. No other imports are required or
// permitted on this file (per the file-level agent prompt).
import module java.base;

/**
 * BMS output DTO record carrying all fields sent to the 3270 terminal for the
 * <strong>COCRDUP</strong> (Card Update) screen.
 *
 * <p>Source artifacts:
 * <ul>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COCRDUP.CPY},
 *       structure {@code CCRDUPAO} (REDEFINES of {@code CCRDUPAI}).</li>
 *   <li>BMS map definition: {@code app/bms/COCRDUP.bms},
 *       map {@code CCRDUPA} (size 24x80, FREEKB), mapset {@code COCRDUP}.</li>
 *   <li>Translated COBOL program: {@code COCRDUPC}
 *       ({@code app/cbl/COCRDUPC.cbl}).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (output side)</h2>
 * <p>Per AAP &sect;0.3.5 and &sect;0.4.1, BMS maps are translated into <em>entry-contract DTO
 * records</em> on the corresponding application class. This record is the Java analog of the
 * output view of the BMS symbolic structure (the {@code CCRDUPAO REDEFINES CCRDUPAI}
 * overlay in {@code COCRDUP.CPY}): it carries the field values supplied by application
 * logic to {@code EXEC CICS SEND MAP}, where they are rendered onto the 3270 terminal.
 * There is no web framework, no Spring binding, no Jakarta Bean Validation, no view
 * templating engine; the record is a plain Java carrier.
 *
 * <p>It is constructed by {@code CoCrdUpC.processOutbound(...)} (the Java translation of
 * the COBOL {@code 3000-SEND-MAP} family of paragraphs &mdash; {@code 3100-SCREEN-INIT},
 * {@code 3200-SETUP-SCREEN-VARS}, {@code 3250-SETUP-INFOMSG},
 * {@code 3300-SETUP-SCREEN-ATTRS}, {@code 3400-SEND-SCREEN}) and returned to the caller
 * (wrapped in {@code Outcome.SendMap}) for terminal rendering by the SEND-MAP equivalent
 * layer.
 *
 * <h2>Update-screen semantics</h2>
 * <p>COCRDUP is the <strong>update</strong> screen for credit cards. The card name,
 * status code, expiration month, and expiration year are editable by the operator; the
 * account-id and card-number keys are display-only by the time the operator reaches this
 * screen (they are populated by COCRDLIC or COCRDSLC and passed in via the commarea).
 * The COBOL paragraph {@code 3300-SETUP-SCREEN-ATTRS} sets per-field BMS attribute bytes
 * (the {@code C}/{@code P}/{@code H}/{@code V}-suffix slots in {@code CCRDUPAO}) to flag
 * fields in error and to lock fields the user cannot change in the current state.
 *
 * <p>This Java DTO captures that runtime attribute state in a single companion record
 * {@link FieldAttributes}, which is the 18th component of this record. A field's mode is
 * one of {@link AttributeMode#UNPROTECTED}, {@link AttributeMode#PROTECTED},
 * {@link AttributeMode#PROTECTED_HIGHLIGHTED}, or {@link AttributeMode#ERROR}. The four
 * COBOL attribute bytes (C/P/H/V) are consolidated into this single mode because the
 * SEND-MAP equivalent layer is the only consumer that needs to decompose them back into
 * the wire-format BMS attribute bytes; application code reasons about mode rather than
 * individual attribute bytes.
 *
 * <h2>Hidden {@code expDay} field &mdash; byte-fidelity preservation</h2>
 * <p>The BMS map definition declares {@code EXPDAY} with attribute
 * {@code ATTRB=(DRK,FSET,PROT)} (see {@code app/bms/COCRDUP.bms} lines 142-146):
 * dark (not visually rendered), protected (cursor cannot land), with FSET so the field is
 * transmitted on every RECEIVE MAP. The corresponding output field {@code EXPDAYO PIC X(2)}
 * is preserved here as the {@link #expDay()} component even though it never appears on
 * screen. This is required by the AAP byte-for-byte fidelity rule (&sect;0.6.5): the BMS
 * symbolic copybook layout is identical in COBOL and Java, and the wire-format SEND MAP
 * payload must contain the same byte at the same offset for {@code EXPDAYO} as the COBOL
 * baseline.
 *
 * <h2>Two function-key bars</h2>
 * <p>COCRDUP carries <strong>two</strong> function-key legend fields (the COBOL output
 * field names are {@code FKEYSO} and {@code FKEYSCO}), reflecting the update-screen UX:
 * {@code FKEYSO} carries "ENTER=Process F3=Exit" (21 chars) and {@code FKEYSCO} carries
 * "F5=Save F12=Cancel" (18 chars). The COBOL initial values are defined statically on the
 * BMS map ({@code app/bms/COCRDUP.bms} lines 158-167) and copied through to the output
 * fields on every SEND-MAP. The Java DTO carries them as plain String components so the
 * SEND-MAP equivalent layer can serialize them onto the wire exactly as the BMS map
 * would.
 *
 * <h2>Field formatting expectations</h2>
 * <p>All output fields arrive on this DTO <em>pre-formatted as {@link String}</em>. The
 * application class ({@code CoCrdUpC}) is responsible for converting underlying domain
 * values (e.g., the card-record name, status, expiration month/year, and any informational
 * or error messages) into the appropriate fixed-width display strings before placing them
 * on this DTO. This DTO does not perform numeric, date, or message formatting itself; it
 * is a pure carrier of pre-formatted display strings.
 *
 * <h2>Null and emptiness semantics &mdash; COBOL parity</h2>
 * <p>In COBOL, an unset BMS {@code PIC X(n)} output field is SPACES on SEND-MAP, never
 * null (no null pointer exists in COBOL). The {@code COCRDUPC} initialization paragraph
 * {@code 3100-SCREEN-INIT} performs {@code MOVE LOW-VALUES TO CCRDUPAO} which sets every
 * PIC X field to LOW-VALUE bytes; the SEND-MAP equivalent then renders unset fields as
 * spaces. To preserve that behavior precisely, the compact constructor below replaces
 * every {@code null} {@link String} component with the empty {@link String} <code>""</code>.
 * This means downstream consumers &mdash; and the BMS-emitting layer that ultimately
 * serializes this record to the 3270 wire format &mdash; can safely treat every component
 * as a non-null {@link String} without first checking for null. The {@link FieldAttributes}
 * component is required (never null) because COCRDUP <em>always</em> has runtime
 * attribute decisions to make for the editable fields; a missing {@link FieldAttributes}
 * indicates an application-class defect and is rejected eagerly via
 * {@link java.util.Objects#requireNonNull(Object, String)}.
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
 * <table>
 *   <caption>Mapping from BMS symbolic copybook {@code CCRDUPAO} (output view, the
 *            REDEFINES of {@code CCRDUPAI}) to Java record components. PIC column shows
 *            the COBOL PICTURE clause; lengths are fixed and preserved by the runtime
 *            that converts this Java record into the 3270 SEND-MAP wire format.</caption>
 *   <tr><th>Symbolic BMS field</th><th>COBOL PIC</th><th>Java component</th></tr>
 *   <tr><td>TRNNAMEO</td><td>X(4)</td> <td>{@link #trnName()}</td></tr>
 *   <tr><td>TITLE01O</td><td>X(40)</td><td>{@link #title01()}</td></tr>
 *   <tr><td>CURDATEO</td><td>X(8)</td> <td>{@link #curDate()}</td></tr>
 *   <tr><td>PGMNAMEO</td><td>X(8)</td> <td>{@link #pgmName()}</td></tr>
 *   <tr><td>TITLE02O</td><td>X(40)</td><td>{@link #title02()}</td></tr>
 *   <tr><td>CURTIMEO</td><td>X(8)</td> <td>{@link #curTime()}</td></tr>
 *   <tr><td>ACCTSIDO</td><td>X(11)</td><td>{@link #acctSid()}</td></tr>
 *   <tr><td>CARDSIDO</td><td>X(16)</td><td>{@link #cardSid()}</td></tr>
 *   <tr><td>CRDNAMEO</td><td>X(50)</td><td>{@link #crdName()}</td></tr>
 *   <tr><td>CRDSTCDO</td><td>X(1)</td> <td>{@link #crdStsCd()}</td></tr>
 *   <tr><td>EXPMONO</td> <td>X(2)</td> <td>{@link #expMon()}</td></tr>
 *   <tr><td>EXPYEARO</td><td>X(4)</td> <td>{@link #expYear()}</td></tr>
 *   <tr><td>EXPDAYO</td> <td>X(2)</td> <td>{@link #expDay()} &mdash; HIDDEN (DRK)</td></tr>
 *   <tr><td>INFOMSGO</td><td>X(40)</td><td>{@link #infoMsg()}</td></tr>
 *   <tr><td>ERRMSGO</td> <td>X(80)</td><td>{@link #errMsg()}</td></tr>
 *   <tr><td>FKEYSO</td>  <td>X(21)</td><td>{@link #fKeys()}</td></tr>
 *   <tr><td>FKEYSCO</td> <td>X(18)</td><td>{@link #fKeysC()}</td></tr>
 * </table>
 *
 * <p>Plus {@link #attributes()} &mdash; the runtime field-attribute control structure
 * populated by the COBOL {@code 3300-SETUP-SCREEN-ATTRS} translation. Not derived from a
 * BMS leaf; this is the Java consolidation of the {@code C}/{@code P}/{@code H}/{@code V}
 * attribute bytes the COBOL paragraph would set on each of the editable
 * {@code CCRDUPAO} fields.
 *
 * <h2>Forbidden idioms (per AAP)</h2>
 * <ul>
 *   <li>No Spring annotations &mdash; this record is plain Java.</li>
 *   <li>No Lombok &mdash; record components and accessors are explicit.</li>
 *   <li>No Jakarta Bean Validation &mdash; null-coalescing is hand-written in the compact
 *       constructor; no semantic validation is performed here.</li>
 *   <li>No setters &mdash; records are immutable.</li>
 *   <li>No {@code java.util.Date} &mdash; date strings stay as raw display bytes on
 *       this DTO and are formatted upstream by the application class from
 *       {@code java.time.LocalDate} values.</li>
 * </ul>
 *
 * @param trnName    echoed transaction id (CCUP), PIC X(4)
 * @param title01    title line 1, PIC X(40)
 * @param curDate    current date, PIC X(8) &mdash; {@code MM/DD/YY}
 * @param pgmName    program name (COCRDUPC), PIC X(8)
 * @param title02    title line 2, PIC X(40)
 * @param curTime    current time, PIC X(8) &mdash; {@code HH:MM:SS}
 * @param acctSid    account-id key display, PIC X(11)
 * @param cardSid    card-number key display, PIC X(16)
 * @param crdName    embossed name (editable), PIC X(50)
 * @param crdStsCd   card active status (editable), PIC X(1) &mdash; Y/N
 * @param expMon     expiration month (editable), PIC X(2)
 * @param expYear    expiration year (editable), PIC X(4)
 * @param expDay     hidden day field, PIC X(2) &mdash; DRK attribute, preserved per
 *                   byte-fidelity rule
 * @param infoMsg    informational message, PIC X(40)
 * @param errMsg     error message, PIC X(80)
 * @param fKeys      function-key legend (ENTER=Process F3=Exit), PIC X(21)
 * @param fKeysC     additional function-key legend (F5=Save F12=Cancel), PIC X(18)
 * @param attributes per-field BMS attribute settings, never {@code null}
 *
 * @see com.blitzy.carddemo.application.card.CoCrdUpC
 * @see com.blitzy.carddemo.application.card.CoCrdUpInput
 * @see CoCrdUpOutput.FieldAttributes
 * @see CoCrdUpOutput.AttributeMode
 * @since 1.0.0
 */
public record CoCrdUpOutput(

        // ============================================================================
        // Header fields (rows 1-2 of the 24x80 BMS map). Populated by the application
        // class on every SEND-MAP so the operator always sees the current transaction id
        // ("CCUP"), program id ("COCRDUPC"), date, and time. The COBOL paragraph that
        // does this is 3100-SCREEN-INIT (lines 1052-1075 of COCRDUPC.cbl), which moves
        // LIT-THISTRANID, LIT-THISPGM, CCDA-TITLE01, CCDA-TITLE02, WS-CURDATE-MM-DD-YY,
        // and WS-CURTIME-HH-MM-SS into the corresponding *O output fields.
        // ============================================================================
        String trnName,   // TRNNAMEO  PIC X(4)   — "CCUP"
        String title01,   // TITLE01O  PIC X(40)  — from CCDA-TITLE01
        String curDate,   // CURDATEO  PIC X(8)   — MM/DD/YY
        String pgmName,   // PGMNAMEO  PIC X(8)   — "COCRDUPC"
        String title02,   // TITLE02O  PIC X(40)  — from CCDA-TITLE02
        String curTime,   // CURTIMEO  PIC X(8)   — HH:MM:SS

        // ============================================================================
        // Key fields (rows 7-8). Account id (11 digits) and card number (16 digits)
        // identify the card under update. The account-id field is rendered PROT,IC,FSET
        // on the BMS map (operator cannot edit) and the card-number field is rendered
        // UNPROT,FSET on the BMS map but is typically populated and locked once the
        // card has been selected upstream by COCRDLIC.
        // ============================================================================
        String acctSid,   // ACCTSIDO  PIC X(11)
        String cardSid,   // CARDSIDO  PIC X(16)

        // ============================================================================
        // Card detail fields (rows 11-15). Embossed name (50), card-active status (1),
        // expiration month (2), expiration year (4), and the hidden expiration day (2).
        // Editable except for expDay which is DRK,FSET,PROT on the BMS map and is
        // preserved here only to round-trip the symbolic-map layout byte-for-byte.
        // ============================================================================
        String crdName,   // CRDNAMEO  PIC X(50)
        String crdStsCd,  // CRDSTCDO  PIC X(1)   — Y/N
        String expMon,    // EXPMONO   PIC X(2)
        String expYear,   // EXPYEARO  PIC X(4)
        String expDay,    // EXPDAYO   PIC X(2)   — HIDDEN (DRK attribute); preserved for parity

        // ============================================================================
        // Footer fields (rows 20, 23, 24). Information and error messages on rows 20
        // and 23 respectively; two function-key legends on row 24. The COBOL paragraph
        // 3250-SETUP-INFOMSG populates INFOMSGO and ERRMSGO; the FKEYSO and FKEYSCO
        // fields carry the static legends from the BMS map INITIAL clauses.
        // ============================================================================
        String infoMsg,   // INFOMSGO  PIC X(40)
        String errMsg,    // ERRMSGO   PIC X(80)
        String fKeys,     // FKEYSO    PIC X(21)  — "ENTER=Process F3=Exit"
        String fKeysC,    // FKEYSCO   PIC X(18)  — "F5=Save F12=Cancel"

        // ============================================================================
        // Runtime field attributes — populated by the 3300-SETUP-SCREEN-ATTRS
        // translation. Controls which editable fields are UNPROT (editable) vs PROT
        // (display only) and which are flagged in ERROR state on this SEND-MAP. The
        // 7-field FieldAttributes record covers exactly the editable / display-keys
        // surface: acctSid, cardSid, crdName, crdStsCd, expMon, expYear, expDay.
        // ============================================================================
        FieldAttributes attributes

) {

    /**
     * Compact (canonical) constructor.
     *
     * <p>Normalizes every {@link String} component so that a {@code null} reference is
     * converted to the empty {@link String} <code>""</code>. This mirrors COBOL
     * SEND-MAP semantics where unfilled BMS {@code PIC X(n)} fields are SPACES,
     * never undefined. The {@link FieldAttributes} component is required (never null);
     * a {@code null} argument throws {@link NullPointerException} eagerly via
     * {@link java.util.Objects#requireNonNull(Object, String)}, signalling an
     * application-class defect at the point of construction rather than letting it
     * propagate to the SEND-MAP equivalent layer.
     *
     * <p>Uses <strong>JEP 513 Flexible Constructor Bodies</strong> (finalized in Java
     * 25): the normalization statements run before the implicit canonical field
     * assignment, which is the appropriate location for COBOL-style "default to
     * SPACES" output cleansing.
     *
     * <p>No semantic validation (PIC-length clamping, numeric checks, calendar
     * validity, etc.) is performed here. The application class ({@code CoCrdUpC})
     * is the single point of responsibility for producing correctly formatted strings
     * before constructing this record; this record is a pure carrier.
     *
     * @throws NullPointerException if {@code attributes} is {@code null}
     */
    public CoCrdUpOutput {
        // Header (6) — see 3100-SCREEN-INIT translation
        trnName  = orEmpty(trnName);
        title01  = orEmpty(title01);
        curDate  = orEmpty(curDate);
        pgmName  = orEmpty(pgmName);
        title02  = orEmpty(title02);
        curTime  = orEmpty(curTime);
        // Keys (2)
        acctSid  = orEmpty(acctSid);
        cardSid  = orEmpty(cardSid);
        // Card detail (5)
        crdName  = orEmpty(crdName);
        crdStsCd = orEmpty(crdStsCd);
        expMon   = orEmpty(expMon);
        expYear  = orEmpty(expYear);
        expDay   = orEmpty(expDay);
        // Footer (4)
        infoMsg  = orEmpty(infoMsg);
        errMsg   = orEmpty(errMsg);
        fKeys    = orEmpty(fKeys);
        fKeysC   = orEmpty(fKeysC);
        // Attributes — required, never null
        Objects.requireNonNull(attributes, "attributes must not be null");
    }

    /**
     * Returns the argument if non-null, or the empty string {@code ""} otherwise.
     *
     * <p>Centralizing this single trivial helper keeps the compact constructor
     * uncluttered and removes any risk of inconsistent null-handling between
     * components. Marked {@code private static} so it is not part of the public
     * surface area of the record. Also re-used by {@link #withHeader(String, String)}
     * to keep header-string handling uniform with the compact constructor's
     * normalization rules.
     *
     * @param s the candidate string (may be {@code null})
     * @return {@code s} if non-null, otherwise {@code ""}
     */
    private static String orEmpty(String s) {
        return (s == null) ? "" : s;
    }

    /**
     * Factory: an output record with every {@link String} component empty and the
     * nested {@link FieldAttributes} initialized via
     * {@link FieldAttributes#allUnprotected()}.
     *
     * <p>This matches the initial state produced by the COBOL paragraph
     * {@code 3100-SCREEN-INIT}, which performs {@code MOVE LOW-VALUES TO CCRDUPAO}:
     * every PIC X field is LOW-VALUE bytes (rendered as spaces by SEND-MAP), and the
     * symbolic-map attribute bytes default to defining the field as freshly editable
     * until {@code 3300-SETUP-SCREEN-ATTRS} overrides them.
     *
     * <p>The 17-string-plus-{@link FieldAttributes} component count is intentional
     * and matches the symbolic copybook {@code CCRDUPAO} (the REDEFINES of
     * {@code CCRDUPAI} in {@code app/cpy-bms/COCRDUP.CPY}) field count exactly. If
     * the symbolic map changes upstream, both this factory and the record header
     * above must change together.
     *
     * @return a fully-blank output record (never {@code null})
     */
    public static CoCrdUpOutput blank() {
        return new CoCrdUpOutput(
                // header (6)
                "", "", "", "", "", "",
                // keys (2)
                "", "",
                // card detail (5)
                "", "", "", "", "",
                // footer (4)
                "", "", "", "",
                // attributes (1)
                FieldAttributes.allUnprotected()
        );
    }

    /**
     * Factory: an output record with the four header constants populated and every
     * other field empty.
     *
     * <p>Convenience for the {@code CoCrdUpC} controller (the Java translation of
     * the COBOL {@code 3100-SCREEN-INIT} paragraph), which is responsible on every
     * SEND-MAP for echoing the transaction id ({@code "CCUP"}), the program id
     * ({@code "COCRDUPC"}), the current date, and the current time at the top of
     * the screen. The remaining fields are left empty and are typically populated
     * by subsequent paragraphs ({@code 3200-SETUP-SCREEN-VARS},
     * {@code 3250-SETUP-INFOMSG}, {@code 3300-SETUP-SCREEN-ATTRS}) before the record
     * reaches the SEND-MAP equivalent layer.
     *
     * <p>The {@code title01} and {@code title02} slots are left empty here; the
     * controller fills them from {@code ScreenTitle.TITLE_01} and
     * {@code ScreenTitle.TITLE_02} (the Java translation of {@code COTTL01Y}
     * constants) using a subsequent record-rebuild step.
     *
     * <p>Both arguments are null-tolerant (a {@code null} is coerced to {@code ""}
     * via {@link #orEmpty(String)}), matching the compact constructor's behavior.
     *
     * @param curDate the current date pre-formatted as {@code MM/DD/YY} (8 chars);
     *                may be {@code null}, in which case it becomes {@code ""}
     * @param curTime the current time pre-formatted as {@code HH:MM:SS} (8 chars);
     *                may be {@code null}, in which case it becomes {@code ""}
     * @return an output record with header fields populated and all other fields
     *         empty (never {@code null})
     */
    public static CoCrdUpOutput withHeader(String curDate, String curTime) {
        return new CoCrdUpOutput(
                "CCUP",              // trnName  — LIT-THISTRANID
                "",                  // title01  — filled by controller from CCDA-TITLE01
                orEmpty(curDate),    // curDate  — MM/DD/YY
                "COCRDUPC",          // pgmName  — LIT-THISPGM
                "",                  // title02  — filled by controller from CCDA-TITLE02
                orEmpty(curTime),    // curTime  — HH:MM:SS
                "", "",              // acctSid, cardSid — keys, populated later
                "", "", "", "", "",  // crdName, crdStsCd, expMon, expYear, expDay
                "", "", "", "",      // infoMsg, errMsg, fKeys, fKeysC
                FieldAttributes.allUnprotected()
        );
    }

    // ========================================================================
    // Nested record for runtime field attributes (CSSETATY translation).
    // ========================================================================
    /**
     * Per-field BMS attribute settings for the card-update map.
     *
     * <p>Each component corresponds to an editable or key BMS field on
     * {@code CCRDUPA}. The controller sets {@link AttributeMode#ERROR} on fields
     * that failed validation (translated from the COBOL pattern of moving
     * {@code DFHRED} to the {@code *C} suffix field in the symbolic map structure)
     * and {@link AttributeMode#PROTECTED} on fields that are display-only (e.g.,
     * the key fields after the operator has selected a card, or every editable
     * field after a successful commit).
     *
     * <p><strong>Field coverage.</strong> Only fields whose attributes vary at
     * runtime are listed here (7 in total): the two display-only keys
     * ({@code acctSidAttr}, {@code cardSidAttr}) which need a runtime decision
     * between PROTECTED (typical) and ERROR (if the upstream lookup found nothing),
     * and the five card-detail fields ({@code crdNameAttr}, {@code crdStsCdAttr},
     * {@code expMonAttr}, {@code expYearAttr}, {@code expDayAttr}) which are
     * editable or in error depending on validation. Fields with statically defined
     * attributes &mdash; the header rows, the message lines, and the function-key
     * legends &mdash; inherit defaults from the BMS map definition in
     * {@code app/bms/COCRDUP.bms} and are not represented on this attribute record.
     *
     * <p><strong>Immutability.</strong> Like its parent record,
     * {@link FieldAttributes} is a Java record: its components are {@code final},
     * accessors are auto-generated, there are no setters, and the instance is safe
     * to share across virtual threads without synchronization.
     *
     * <p><strong>Validation.</strong> The compact constructor rejects {@code null}
     * arguments eagerly via {@link java.util.Objects#requireNonNull(Object, String)}.
     * An attribute mode is always required &mdash; there is no "unset" state on a
     * 3270 attribute byte; the choice between editable and display-only must be
     * explicit at every SEND-MAP.
     *
     * @param acctSidAttr  mode for ACCTSIDO (account-id key display)
     * @param cardSidAttr  mode for CARDSIDO (card-number key display)
     * @param crdNameAttr  mode for CRDNAMEO (embossed name)
     * @param crdStsCdAttr mode for CRDSTCDO (card active status Y/N)
     * @param expMonAttr   mode for EXPMONO  (expiration month)
     * @param expYearAttr  mode for EXPYEARO (expiration year)
     * @param expDayAttr   mode for EXPDAYO  (hidden expiration day)
     */
    public record FieldAttributes(
            AttributeMode acctSidAttr,
            AttributeMode cardSidAttr,
            AttributeMode crdNameAttr,
            AttributeMode crdStsCdAttr,
            AttributeMode expMonAttr,
            AttributeMode expYearAttr,
            AttributeMode expDayAttr
    ) {

        /**
         * Compact (canonical) constructor.
         *
         * <p>Rejects {@code null} arguments eagerly. Each component is a required
         * {@link AttributeMode}; there is no "default" attribute on a 3270 wire
         * byte, so a missing argument is treated as an application-class defect
         * rather than silently coerced.
         *
         * <p>Uses <strong>JEP 513 Flexible Constructor Bodies</strong> (finalized
         * in Java 25); the {@link java.util.Objects#requireNonNull(Object, String)}
         * checks run before the implicit canonical field assignment.
         *
         * @throws NullPointerException if any component is {@code null}; the
         *         {@code message} on the exception names the offending parameter
         */
        public FieldAttributes {
            Objects.requireNonNull(acctSidAttr,  "acctSidAttr");
            Objects.requireNonNull(cardSidAttr,  "cardSidAttr");
            Objects.requireNonNull(crdNameAttr,  "crdNameAttr");
            Objects.requireNonNull(crdStsCdAttr, "crdStsCdAttr");
            Objects.requireNonNull(expMonAttr,   "expMonAttr");
            Objects.requireNonNull(expYearAttr,  "expYearAttr");
            Objects.requireNonNull(expDayAttr,   "expDayAttr");
        }

        /**
         * Factory: every field {@link AttributeMode#UNPROTECTED} (initial display,
         * no errors).
         *
         * <p>This is the initial-display state for the COCRDUP screen before the
         * card has been looked up. All keys and editable fields are editable;
         * once the operator types the keys and presses ENTER, the controller
         * typically transitions to {@link #keysProtectedEditableOpen()} for the
         * actual editing pass.
         *
         * @return a {@link FieldAttributes} instance where every component is
         *         {@link AttributeMode#UNPROTECTED} (never {@code null})
         */
        public static FieldAttributes allUnprotected() {
            return uniform(AttributeMode.UNPROTECTED);
        }

        /**
         * Factory: every field {@link AttributeMode#PROTECTED} (post-commit
         * display, no edits).
         *
         * <p>This is the post-commit state for the COCRDUP screen: after the
         * operator has saved the card details, every field is locked from further
         * editing until the controller restarts the flow.
         *
         * @return a {@link FieldAttributes} instance where every component is
         *         {@link AttributeMode#PROTECTED} (never {@code null})
         */
        public static FieldAttributes allProtected() {
            return uniform(AttributeMode.PROTECTED);
        }

        /**
         * Factory: keys ({@code acctSid}, {@code cardSid}) and the hidden
         * {@code expDay} field {@link AttributeMode#PROTECTED}; the four editable
         * card-detail fields ({@code crdName}, {@code crdStsCd}, {@code expMon},
         * {@code expYear}) {@link AttributeMode#UNPROTECTED}.
         *
         * <p>This is the typical view-then-edit state for COCRDUP: the operator
         * has already selected the card upstream (via COCRDLIC), so the account-id
         * and card-number key fields are display-only, and the operator's edit
         * cursor lands on the embossed-name field. The {@code expDay} field is
         * permanently dark/protected on the BMS map and is therefore
         * {@link AttributeMode#PROTECTED} in this and every other factory state.
         *
         * @return a {@link FieldAttributes} instance with keys protected and
         *         editable fields open (never {@code null})
         */
        public static FieldAttributes keysProtectedEditableOpen() {
            return new FieldAttributes(
                    AttributeMode.PROTECTED,    // acctSidAttr  — key, display-only
                    AttributeMode.PROTECTED,    // cardSidAttr  — key, display-only
                    AttributeMode.UNPROTECTED,  // crdNameAttr  — editable
                    AttributeMode.UNPROTECTED,  // crdStsCdAttr — editable
                    AttributeMode.UNPROTECTED,  // expMonAttr   — editable
                    AttributeMode.UNPROTECTED,  // expYearAttr  — editable
                    AttributeMode.PROTECTED     // expDayAttr   — HIDDEN/DRK on BMS
            );
        }

        /**
         * Internal factory: a {@link FieldAttributes} instance where every
         * component is the given {@link AttributeMode}.
         *
         * <p>Centralizes the 7-argument constructor call so that the public
         * {@link #allUnprotected()} and {@link #allProtected()} factories stay
         * concise. The argument count (7) matches the field count of the enclosing
         * record exactly; if the field set changes, both must change together.
         *
         * @param m the {@link AttributeMode} to apply to every component (must be
         *          non-null; callers always pass an enum literal)
         * @return a {@link FieldAttributes} instance with every component set to
         *         {@code m}
         */
        private static FieldAttributes uniform(AttributeMode m) {
            return new FieldAttributes(m, m, m, m, m, m, m);
        }
    }

    // ========================================================================
    // Nested enum for BMS attribute modes (3300-SETUP-SCREEN-ATTRS translation).
    // ========================================================================
    /**
     * Enumeration of BMS attribute modes set by the
     * {@code 3300-SETUP-SCREEN-ATTRS} paragraph translation.
     *
     * <p>Translated from COBOL constants {@code DFHBMPRO} (PROTECTED),
     * {@code DFHBMFSE} (UNPROTECTED with FSET), {@code DFHRED} (ERROR red
     * highlight), and {@code DFHBMDAR} (DARK / hidden).
     *
     * <p>The COBOL symbolic-map convention encodes four attribute bytes per field
     * (C={@code COLOR}, P={@code PS}, H={@code HILIGHT}, V={@code VALIDN}). The
     * combinations actually used by {@code COCRDUPC} reduce to four conceptual
     * modes, captured here. The BMS-emitting layer (responsible for serializing
     * this record to the 3270 wire format) is the single point that re-decomposes
     * a mode back into the four wire-format bytes.
     *
     * <p>This is a closed taxonomy &mdash; a plain Java {@code enum} provides the
     * exhaustiveness guarantees required by AAP &sect;0.7.3: pattern-matching
     * {@code switch} on this enum is checked for exhaustiveness by the Java
     * compiler with no need for a {@code default} branch, which preserves the AAP
     * mandate that closed taxonomies must never use {@code default}.
     *
     * <p>Modes:
     * <ul>
     *   <li>{@link #UNPROTECTED} &mdash; editable field; cursor accepts input
     *       (BMS {@code UNPROT}).</li>
     *   <li>{@link #PROTECTED} &mdash; display-only field; cursor cannot land
     *       (BMS {@code ASKIP,NORM}).</li>
     *   <li>{@link #PROTECTED_HIGHLIGHTED} &mdash; display-only with high-brightness
     *       highlighting (BMS {@code ASKIP,BRT}); used to draw attention to a key
     *       value the operator should see.</li>
     *   <li>{@link #ERROR} &mdash; editable field flagged in error state
     *       (BMS {@code UNPROT,BRT} with red color); marks a field that failed
     *       validation in the previous edit pass.</li>
     * </ul>
     */
    public enum AttributeMode {
        /** Editable field; cursor accepts input. BMS {@code UNPROT}. */
        UNPROTECTED,
        /** Display-only field; cursor cannot land. BMS {@code ASKIP,NORM}. */
        PROTECTED,
        /**
         * Like {@link #PROTECTED} but rendered with high intensity for emphasis.
         * BMS {@code ASKIP,BRT}.
         */
        PROTECTED_HIGHLIGHTED,
        /**
         * Validation-error highlight ({@code DFHRED}); editable field flagged in
         * error state. BMS {@code UNPROT,BRT} with red color.
         */
        ERROR
    }
}
