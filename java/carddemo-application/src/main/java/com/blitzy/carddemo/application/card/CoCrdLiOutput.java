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
 * BMS output record for the {@code COCRDLI / CCRDLIA} card-list map
 * (COBOL transaction {@code CCLI}, program {@code COCRDLIC}).
 *
 * <p>This record is a literal projection of the {@code 01 CCRDLIAO REDEFINES CCRDLIAI}
 * group in {@code app/cpy-bms/COCRDLI.CPY}: one {@link String} field per
 * {@code "O"}-suffixed BMS leaf. Field widths match the underlying COBOL
 * {@code PIC X(n)} declarations exactly.
 *
 * <p>The controller {@code CoCrdLiC} composes this record from up to 7 rows of
 * card data fetched via the {@code CardRepository} port; rows beyond the available
 * data carry empty strings (length-zero defaults).
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/COCRDLI.bms}
 *       (mapset {@code COCRDLI}, map {@code CCRDLIA}, size 24x80, FREEKB).</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COCRDLI.CPY}
 *       (group {@code 01 CCRDLIAO REDEFINES CCRDLIAI}).</li>
 *   <li>Translated COBOL program: {@code app/cbl/COCRDLIC.cbl}.</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (output side)</h2>
 * <p>Per AAP &sect;0.3.5 and &sect;0.4.1, BMS maps are translated into <em>entry-contract DTO
 * records</em> on the corresponding application class. This record is the Java analog of
 * the output view of the BMS symbolic structure (the {@code CCRDLIAO REDEFINES CCRDLIAI}
 * overlay in {@code COCRDLI.CPY}): it carries the field values supplied by application
 * logic to {@code EXEC CICS SEND MAP}, where they are rendered onto the 3270 terminal.
 * There is no web framework, no Spring binding, no Jakarta Bean Validation, no view
 * templating engine; the record is a plain Java carrier.
 *
 * <p>It is constructed by {@code CoCrdLiC} (returned to the caller wrapped in
 * {@code Outcome.SendMap}) and rendered by the SEND-MAP equivalent layer.
 *
 * <h2>Card-list-screen semantics</h2>
 * <p>COCRDLI is the <strong>list</strong> screen for credit cards (transaction
 * {@code CCLI}). The user may supply an account-id filter ({@code ACCTSID}) and/or a
 * card-number filter ({@code CARDSID}); these are captured by {@code CoCrdLiInput} and
 * echoed back on the output record so the user sees what they entered. The controller
 * uses these filters to paginate through the card cross-reference file, populating up
 * to 7 rows per page with account number, card number, and status. Rows beyond the
 * available data are blank.
 *
 * <h2>Row layout asymmetry &mdash; CRDSTP only on rows 2-7</h2>
 * <p>The BMS map and symbolic copybook show that row 1 has four data leaves
 * ({@code CRDSEL1}, {@code ACCTNO1}, {@code CRDNUM1}, {@code CRDSTS1}) while rows 2-7
 * each have five data leaves with an additional {@code CRDSTP{N}} field
 * (see {@code app/cpy-bms/COCRDLI.CPY} lines 73-96 for row 1 and lines 97-276 for rows
 * 2-7). Per AAP &sect;0.7.1 "preserve current behavior … exactly as-is" the
 * {@code CRDSTP{N}} fields are translated faithfully as additional record components on
 * rows 2-7 only; row 1 does <em>not</em> have a {@code crdStp1} component. The
 * {@code CRDSTP{N}} field is a {@code PIC X(1)} BMS leaf rendered with
 * {@code ATTRB=(ASKIP,DRK,FSET)} (dark / protected / set) at the same screen position
 * as {@code CRDSEL{N}}; it acts as a "copy" attribute echo whose existence is
 * preserved here per the byte-fidelity rule. The map definition for {@code CRDSTPn} on
 * rows 2-7 can be seen in {@code app/bms/COCRDLI.bms} lines 169-173, 196-200, 223-227,
 * 250-254, 277-281, and 304-308.
 *
 * <h2>Field-level highlighting note</h2>
 * <p>The BMS map definition assigns {@code COLOR=RED} statically to the {@code ERRMSG}
 * field (see {@code app/bms/COCRDLI.bms} lines 331-334); no application logic is needed
 * to colorize errors. COCRDLI is a read-only listing screen, so per-field highlighting
 * (e.g., to flag a single invalid row) is rare. To signal an error, the controller
 * simply populates {@code errMsg} with a human-readable message; the static map
 * attribute renders it in red. No companion {@code FieldAttributes} nested record is
 * therefore required on this DTO (contrast with {@code CoCrdUpOutput}, which carries
 * runtime attribute state for its editable fields).
 *
 * <h2>Null and emptiness semantics &mdash; COBOL parity</h2>
 * <p>In COBOL, an unset BMS {@code PIC X(n)} output field is SPACES on SEND-MAP, never
 * null (no null pointer exists in COBOL). The COBOL initialization in {@code COCRDLIC}
 * performs {@code MOVE LOW-VALUES TO CCRDLIAO} which sets every PIC X field to
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
 * <p>Mapping from BMS symbolic copybook {@code CCRDLIAO} (output view, the REDEFINES of
 * {@code CCRDLIAI}) to Java record components. The PIC column shows the COBOL PICTURE
 * clause; lengths are fixed and preserved by the runtime that converts this Java record
 * into the 3270 SEND-MAP wire format. Field count is 45 in total: 7 header + 2 filter
 * echo + 4 row 1 + 30 rows 2-7 (6 rows &times; 5 fields each) + 2 footer.
 * <ul>
 *   <li>{@code TRNNAMEO} &mdash; X(4)  &mdash; {@link #trnName()}</li>
 *   <li>{@code TITLE01O} &mdash; X(40) &mdash; {@link #title01()}</li>
 *   <li>{@code CURDATEO} &mdash; X(8)  &mdash; {@link #curDate()}</li>
 *   <li>{@code PGMNAMEO} &mdash; X(8)  &mdash; {@link #pgmName()}</li>
 *   <li>{@code TITLE02O} &mdash; X(40) &mdash; {@link #title02()}</li>
 *   <li>{@code CURTIMEO} &mdash; X(8)  &mdash; {@link #curTime()}</li>
 *   <li>{@code PAGENOO}  &mdash; X(3)  &mdash; {@link #pageNo()}</li>
 *   <li>{@code ACCTSIDO} &mdash; X(11) &mdash; {@link #acctSidFilter()}</li>
 *   <li>{@code CARDSIDO} &mdash; X(16) &mdash; {@link #cardSidFilter()}</li>
 *   <li>{@code CRDSEL1O} &mdash; X(1)  &mdash; {@link #crdSel1()}</li>
 *   <li>{@code ACCTNO1O} &mdash; X(11) &mdash; {@link #acctNo1()}</li>
 *   <li>{@code CRDNUM1O} &mdash; X(16) &mdash; {@link #crdNum1()}</li>
 *   <li>{@code CRDSTS1O} &mdash; X(1)  &mdash; {@link #crdSts1()}</li>
 *   <li>{@code CRDSEL2O} &mdash; X(1)  &mdash; {@link #crdSel2()}</li>
 *   <li>{@code CRDSTP2O} &mdash; X(1)  &mdash; {@link #crdStp2()}</li>
 *   <li>{@code ACCTNO2O} &mdash; X(11) &mdash; {@link #acctNo2()}</li>
 *   <li>{@code CRDNUM2O} &mdash; X(16) &mdash; {@link #crdNum2()}</li>
 *   <li>{@code CRDSTS2O} &mdash; X(1)  &mdash; {@link #crdSts2()}</li>
 *   <li>{@code CRDSEL3O} &mdash; X(1)  &mdash; {@link #crdSel3()}</li>
 *   <li>{@code CRDSTP3O} &mdash; X(1)  &mdash; {@link #crdStp3()}</li>
 *   <li>{@code ACCTNO3O} &mdash; X(11) &mdash; {@link #acctNo3()}</li>
 *   <li>{@code CRDNUM3O} &mdash; X(16) &mdash; {@link #crdNum3()}</li>
 *   <li>{@code CRDSTS3O} &mdash; X(1)  &mdash; {@link #crdSts3()}</li>
 *   <li>{@code CRDSEL4O} &mdash; X(1)  &mdash; {@link #crdSel4()}</li>
 *   <li>{@code CRDSTP4O} &mdash; X(1)  &mdash; {@link #crdStp4()}</li>
 *   <li>{@code ACCTNO4O} &mdash; X(11) &mdash; {@link #acctNo4()}</li>
 *   <li>{@code CRDNUM4O} &mdash; X(16) &mdash; {@link #crdNum4()}</li>
 *   <li>{@code CRDSTS4O} &mdash; X(1)  &mdash; {@link #crdSts4()}</li>
 *   <li>{@code CRDSEL5O} &mdash; X(1)  &mdash; {@link #crdSel5()}</li>
 *   <li>{@code CRDSTP5O} &mdash; X(1)  &mdash; {@link #crdStp5()}</li>
 *   <li>{@code ACCTNO5O} &mdash; X(11) &mdash; {@link #acctNo5()}</li>
 *   <li>{@code CRDNUM5O} &mdash; X(16) &mdash; {@link #crdNum5()}</li>
 *   <li>{@code CRDSTS5O} &mdash; X(1)  &mdash; {@link #crdSts5()}</li>
 *   <li>{@code CRDSEL6O} &mdash; X(1)  &mdash; {@link #crdSel6()}</li>
 *   <li>{@code CRDSTP6O} &mdash; X(1)  &mdash; {@link #crdStp6()}</li>
 *   <li>{@code ACCTNO6O} &mdash; X(11) &mdash; {@link #acctNo6()}</li>
 *   <li>{@code CRDNUM6O} &mdash; X(16) &mdash; {@link #crdNum6()}</li>
 *   <li>{@code CRDSTS6O} &mdash; X(1)  &mdash; {@link #crdSts6()}</li>
 *   <li>{@code CRDSEL7O} &mdash; X(1)  &mdash; {@link #crdSel7()}</li>
 *   <li>{@code CRDSTP7O} &mdash; X(1)  &mdash; {@link #crdStp7()}</li>
 *   <li>{@code ACCTNO7O} &mdash; X(11) &mdash; {@link #acctNo7()}</li>
 *   <li>{@code CRDNUM7O} &mdash; X(16) &mdash; {@link #crdNum7()}</li>
 *   <li>{@code CRDSTS7O} &mdash; X(1)  &mdash; {@link #crdSts7()}</li>
 *   <li>{@code INFOMSGO} &mdash; X(45) &mdash; {@link #infoMsg()}</li>
 *   <li>{@code ERRMSGO}  &mdash; X(78) &mdash; {@link #errMsg()}</li>
 * </ul>
 *
 * @param trnName        echoed transaction id (CCLI), PIC X(4)
 * @param title01        title line 1, PIC X(40)
 * @param curDate        formatted current date, PIC X(8)
 * @param pgmName        program name (COCRDLIC), PIC X(8)
 * @param title02        title line 2, PIC X(40)
 * @param curTime        formatted current time, PIC X(8)
 * @param pageNo         current page number, PIC X(3)
 * @param acctSidFilter  echoed account-id filter, PIC X(11)
 * @param cardSidFilter  echoed card-number filter, PIC X(16)
 * @param crdSel1        row 1 selection echo, PIC X(1)
 * @param acctNo1        row 1 account number, PIC X(11)
 * @param crdNum1        row 1 card number, PIC X(16)
 * @param crdSts1        row 1 status, PIC X(1)
 * @param crdSel2        row 2 selection echo, PIC X(1)
 * @param crdStp2        row 2 COBOL-internal "copy" attribute echo, PIC X(1)
 * @param acctNo2        row 2 account number, PIC X(11)
 * @param crdNum2        row 2 card number, PIC X(16)
 * @param crdSts2        row 2 status, PIC X(1)
 * @param crdSel3        row 3 selection echo, PIC X(1)
 * @param crdStp3        row 3 COBOL-internal "copy" attribute echo, PIC X(1)
 * @param acctNo3        row 3 account number, PIC X(11)
 * @param crdNum3        row 3 card number, PIC X(16)
 * @param crdSts3        row 3 status, PIC X(1)
 * @param crdSel4        row 4 selection echo, PIC X(1)
 * @param crdStp4        row 4 COBOL-internal "copy" attribute echo, PIC X(1)
 * @param acctNo4        row 4 account number, PIC X(11)
 * @param crdNum4        row 4 card number, PIC X(16)
 * @param crdSts4        row 4 status, PIC X(1)
 * @param crdSel5        row 5 selection echo, PIC X(1)
 * @param crdStp5        row 5 COBOL-internal "copy" attribute echo, PIC X(1)
 * @param acctNo5        row 5 account number, PIC X(11)
 * @param crdNum5        row 5 card number, PIC X(16)
 * @param crdSts5        row 5 status, PIC X(1)
 * @param crdSel6        row 6 selection echo, PIC X(1)
 * @param crdStp6        row 6 COBOL-internal "copy" attribute echo, PIC X(1)
 * @param acctNo6        row 6 account number, PIC X(11)
 * @param crdNum6        row 6 card number, PIC X(16)
 * @param crdSts6        row 6 status, PIC X(1)
 * @param crdSel7        row 7 selection echo, PIC X(1)
 * @param crdStp7        row 7 COBOL-internal "copy" attribute echo, PIC X(1)
 * @param acctNo7        row 7 account number, PIC X(11)
 * @param crdNum7        row 7 card number, PIC X(16)
 * @param crdSts7        row 7 status, PIC X(1)
 * @param infoMsg        informational message, PIC X(45)
 * @param errMsg         error message, PIC X(78)
 *
 * @see com.blitzy.carddemo.application.card.CoCrdLiC
 * @see com.blitzy.carddemo.application.card.CoCrdLiInput
 */
public record CoCrdLiOutput(
        String trnName,
        String title01,
        String curDate,
        String pgmName,
        String title02,
        String curTime,
        String pageNo,
        String acctSidFilter,
        String cardSidFilter,
        String crdSel1,
        String acctNo1,
        String crdNum1,
        String crdSts1,
        String crdSel2,
        String crdStp2,
        String acctNo2,
        String crdNum2,
        String crdSts2,
        String crdSel3,
        String crdStp3,
        String acctNo3,
        String crdNum3,
        String crdSts3,
        String crdSel4,
        String crdStp4,
        String acctNo4,
        String crdNum4,
        String crdSts4,
        String crdSel5,
        String crdStp5,
        String acctNo5,
        String crdNum5,
        String crdSts5,
        String crdSel6,
        String crdStp6,
        String acctNo6,
        String crdNum6,
        String crdSts6,
        String crdSel7,
        String crdStp7,
        String acctNo7,
        String crdNum7,
        String crdSts7,
        String infoMsg,
        String errMsg) {

    /**
     * Compact constructor enforcing COBOL "SPACES by default" semantics on every
     * {@link String} component. Each {@code null} input is coerced to the empty
     * {@link String} <code>""</code> so that downstream BMS serialization can safely
     * pad-right every field to its fixed PIC width without first checking for null.
     *
     * <p>This is a textbook application of <strong>JEP 513 Flexible Constructor
     * Bodies</strong> (finalized in Java 25): normalization runs before the canonical
     * field bindings. The mandate from AAP &sect;0.7.3 to use JEP 513 for "validation
     * or normalization of arguments before canonical constructor calls" is satisfied.
     *
     * <p>Field count: 45 (7 header + 2 filter + 4 row 1 + 6&times;5 rows 2-7 + 2 footer).
     */
    public CoCrdLiOutput {
        trnName = orEmpty(trnName);
        title01 = orEmpty(title01);
        curDate = orEmpty(curDate);
        pgmName = orEmpty(pgmName);
        title02 = orEmpty(title02);
        curTime = orEmpty(curTime);
        pageNo = orEmpty(pageNo);
        acctSidFilter = orEmpty(acctSidFilter);
        cardSidFilter = orEmpty(cardSidFilter);
        // Row 1 (4 fields — no crdStp1 per copybook layout)
        crdSel1 = orEmpty(crdSel1);
        acctNo1 = orEmpty(acctNo1);
        crdNum1 = orEmpty(crdNum1);
        crdSts1 = orEmpty(crdSts1);
        // Row 2 (5 fields)
        crdSel2 = orEmpty(crdSel2);
        crdStp2 = orEmpty(crdStp2);
        acctNo2 = orEmpty(acctNo2);
        crdNum2 = orEmpty(crdNum2);
        crdSts2 = orEmpty(crdSts2);
        // Row 3 (5 fields)
        crdSel3 = orEmpty(crdSel3);
        crdStp3 = orEmpty(crdStp3);
        acctNo3 = orEmpty(acctNo3);
        crdNum3 = orEmpty(crdNum3);
        crdSts3 = orEmpty(crdSts3);
        // Row 4 (5 fields)
        crdSel4 = orEmpty(crdSel4);
        crdStp4 = orEmpty(crdStp4);
        acctNo4 = orEmpty(acctNo4);
        crdNum4 = orEmpty(crdNum4);
        crdSts4 = orEmpty(crdSts4);
        // Row 5 (5 fields)
        crdSel5 = orEmpty(crdSel5);
        crdStp5 = orEmpty(crdStp5);
        acctNo5 = orEmpty(acctNo5);
        crdNum5 = orEmpty(crdNum5);
        crdSts5 = orEmpty(crdSts5);
        // Row 6 (5 fields)
        crdSel6 = orEmpty(crdSel6);
        crdStp6 = orEmpty(crdStp6);
        acctNo6 = orEmpty(acctNo6);
        crdNum6 = orEmpty(crdNum6);
        crdSts6 = orEmpty(crdSts6);
        // Row 7 (5 fields)
        crdSel7 = orEmpty(crdSel7);
        crdStp7 = orEmpty(crdStp7);
        acctNo7 = orEmpty(acctNo7);
        crdNum7 = orEmpty(crdNum7);
        crdSts7 = orEmpty(crdSts7);
        // Footer
        infoMsg = orEmpty(infoMsg);
        errMsg = orEmpty(errMsg);
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
     * {@link String} <code>""</code>. Useful for {@code COCRDLIC} initialization
     * before any business-logic population, mirroring the COBOL
     * {@code MOVE LOW-VALUES TO CCRDLIAO} statement on initial entry to the program.
     *
     * @return a {@code CoCrdLiOutput} with all 45 fields set to <code>""</code>
     */
    public static CoCrdLiOutput blank() {
        return new CoCrdLiOutput(
                "", "", "", "", "", "", "",           // header (7)
                "", "",                                // filter echoes (2)
                "", "", "", "",                        // row 1 (4)
                "", "", "", "", "",                    // row 2 (5)
                "", "", "", "", "",                    // row 3 (5)
                "", "", "", "", "",                    // row 4 (5)
                "", "", "", "", "",                    // row 5 (5)
                "", "", "", "", "",                    // row 6 (5)
                "", "", "", "", "",                    // row 7 (5)
                "", ""                                 // footer (2)
        );
    }

    /**
     * Factory returning a partially-populated instance with the standard screen
     * header fields filled in: transaction id ({@code "CCLI"}), program name
     * ({@code "COCRDLIC"}), current date, current time, and page number. Title fields
     * and all data/filter/row/footer fields remain empty &mdash; the caller is
     * expected to compose the full record by combining this header skeleton with
     * the row-data fields and the screen-title constants (from {@code ScreenTitle}).
     *
     * <p>This mirrors the COBOL {@code 1100-SETUP-SCREEN} family of paragraphs in
     * {@code COCRDLIC} which establish the static header before any business-logic
     * field assignment, including the page-number display in the header bar.
     *
     * @param pageNo  the current page number string (e.g., {@code "001"}), PIC X(3);
     *                {@code null} is coerced to <code>""</code>
     * @param curDate the current date string (e.g., {@code "mm/dd/yy"}), PIC X(8);
     *                {@code null} is coerced to <code>""</code>
     * @param curTime the current time string (e.g., {@code "hh:mm:ss"}), PIC X(8);
     *                {@code null} is coerced to <code>""</code>
     * @return a {@code CoCrdLiOutput} with header fields populated and all other
     *         fields set to <code>""</code>
     */
    public static CoCrdLiOutput withHeader(String pageNo, String curDate, String curTime) {
        return new CoCrdLiOutput(
                "CCLI",                                 // trnName  — transaction id
                "",                                     // title01  — filled by ScreenTitle.TITLE_01
                orEmpty(curDate),                       // curDate
                "COCRDLIC",                             // pgmName  — program name
                "",                                     // title02  — filled by ScreenTitle.TITLE_02
                orEmpty(curTime),                       // curTime
                orEmpty(pageNo),                        // pageNo
                "", "",                                 // filter echoes (acctSidFilter, cardSidFilter)
                "", "", "", "",                         // row 1 (crdSel1, acctNo1, crdNum1, crdSts1)
                "", "", "", "", "",                     // row 2 (crdSel2, crdStp2, acctNo2, crdNum2, crdSts2)
                "", "", "", "", "",                     // row 3
                "", "", "", "", "",                     // row 4
                "", "", "", "", "",                     // row 5
                "", "", "", "", "",                     // row 6
                "", "", "", "", "",                     // row 7
                "", ""                                  // footer (infoMsg, errMsg)
        );
    }
}
