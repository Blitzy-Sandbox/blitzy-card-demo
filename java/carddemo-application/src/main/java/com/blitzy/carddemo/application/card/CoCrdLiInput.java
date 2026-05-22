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
// type of every BMS field component on this record — and to java.util.Objects, used by the
// compact constructor's Objects.requireNonNull invocation on the aidKey field. No other
// imports are required or permitted on this file (per the file-level agent prompt).
import module java.base;

/**
 * BMS input record for the {@code COCRDLI / CCRDLIA} card-list map
 * (COBOL transaction {@code CCLI}, program {@code COCRDLIC}).
 *
 * <p>This record is a literal projection of the {@code 01 CCRDLIAI} group in
 * {@code app/cpy-bms/COCRDLI.CPY}: one {@link String} field per
 * {@code "I"}-suffixed BMS leaf. Field widths match the underlying COBOL
 * {@code PIC X(n)} declarations exactly.
 *
 * <p>The {@link AidKey} enum is a transaction-specific simplification listing
 * only the AID keys that {@code COCRDLIC} actually handles. The controller
 * still receives the full {@link com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey}
 * sealed type for exhaustive pattern matching.
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/COCRDLI.bms}
 *       (mapset {@code COCRDLI}, map {@code CCRDLIA}, size 24x80, FREEKB).</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COCRDLI.CPY}
 *       (input group {@code 01 CCRDLIAI}, lines 17-288).</li>
 *   <li>Translated COBOL program: {@code app/cbl/COCRDLIC.cbl}.</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (input side)</h2>
 * <p>Per AAP &sect;0.3.5 and &sect;0.4.1, BMS maps are translated into <em>entry-contract DTO
 * records</em> on the corresponding application class. This record is the Java analog of
 * the input view of the BMS symbolic structure: it carries the field values returned from
 * {@code EXEC CICS RECEIVE MAP} to the application code. There is no web framework, no
 * Spring binding, no Jakarta Bean Validation; the record is a plain Java carrier.
 *
 * <h2>Card-list-screen semantics</h2>
 * <p>COCRDLI is the <strong>list</strong> screen for credit cards (transaction
 * {@code CCLI}). The user may supply an account-id filter ({@code acctSidFilter}) and/or a
 * card-number filter ({@code cardSidFilter}); these are the only fields the user can
 * actually type into. The controller paginates through the card cross-reference file
 * using these filters, displaying up to 7 rows per page; for each visible row the user
 * may type a selection character ({@code crdSelN}) of {@code 'S'} (view) or {@code 'U'}
 * (update) to dispatch into {@code COCRDSL} (Card View) or {@code COCRDUP} (Card Update)
 * respectively. All other row leaves ({@code acctNoN}, {@code crdNumN}, {@code crdStsN})
 * are protected display-only echoes and arrive at RECEIVE-MAP time bearing the values
 * the prior SEND-MAP put there.
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
 * preserved here per the byte-fidelity rule.
 *
 * <h2>Null and emptiness semantics &mdash; COBOL parity</h2>
 * <p>In COBOL, an unfilled BMS {@code PIC X(n)} input field arrives as SPACES (a
 * fixed-length blank string), never as a null reference; there is no null pointer in
 * COBOL. To preserve that behavior precisely the compact constructor below replaces
 * every {@code null} {@link String} component with the empty {@link String}
 * <code>""</code>. This means downstream consumers can safely call
 * {@link String#isBlank()}, {@link String#trim()}, or comparison helpers without first
 * checking for null &mdash; matching the COBOL idiom where receive-map fields are
 * always defined character strings.
 *
 * <p>The compact constructor uses <strong>JEP 513 Flexible Constructor Bodies</strong>
 * (finalized in Java 25). Statements before the canonical field-assignment perform input
 * normalization, which is exactly the place to capture COBOL-style "default to SPACES"
 * semantics. This satisfies the file-level mandate to use JEP 513 for record validation
 * and normalization.
 *
 * <h2>Immutability</h2>
 * <p>Because this is a record, all components are {@code final} and accessors are
 * automatically generated; there are no setters, no Lombok, no Spring annotations, no
 * Jakarta validation annotations. The instance is safely shareable across virtual threads
 * (per AAP &sect;0.6.6) without synchronization.
 *
 * <h2>Forbidden idioms (per AAP)</h2>
 * <ul>
 *   <li>No Spring annotations &mdash; this record is plain Java.</li>
 *   <li>No Lombok &mdash; record components and accessors are explicit.</li>
 *   <li>No Jakarta Bean Validation &mdash; validation is hand-written in the compact
 *       constructor.</li>
 *   <li>No setters &mdash; records are immutable.</li>
 *   <li>No {@code java.util.Date} &mdash; date/time strings remain as raw BMS bytes on
 *       this DTO and are translated into {@code java.time} types only inside the
 *       application logic.</li>
 *   <li>No {@code default} branch on any {@code switch} expression that pattern-matches
 *       this record's components.</li>
 * </ul>
 *
 * @param trnName        echoed transaction id (CCLI), PIC X(4)
 * @param title01        title line 1 echoed from prior screen, PIC X(40)
 * @param curDate        current date echoed from prior screen, PIC X(8) &mdash; MM/DD/YY
 * @param pgmName        echoed program name (COCRDLIC), PIC X(8)
 * @param title02        title line 2 echoed from prior screen, PIC X(40)
 * @param curTime        current time echoed from prior screen, PIC X(8) &mdash; HH:MM:SS
 * @param pageNo         current page number, PIC X(3)
 * @param acctSidFilter  account-id filter (blank = no filter), PIC X(11)
 * @param cardSidFilter  card-number filter (blank = no filter), PIC X(16)
 * @param crdSel1        row 1 selection char ({@code 'S'} view / {@code 'U'} update / SPACE none), PIC X(1)
 * @param acctNo1        echoed account number for row 1, PIC X(11)
 * @param crdNum1        echoed card number for row 1, PIC X(16)
 * @param crdSts1        echoed status flag for row 1, PIC X(1)
 * @param crdSel2        row 2 selection char, PIC X(1)
 * @param crdStp2        row 2 COBOL-internal "copy" attribute echo, PIC X(1)
 * @param acctNo2        echoed account number for row 2, PIC X(11)
 * @param crdNum2        echoed card number for row 2, PIC X(16)
 * @param crdSts2        echoed status flag for row 2, PIC X(1)
 * @param crdSel3        row 3 selection char, PIC X(1)
 * @param crdStp3        row 3 COBOL-internal "copy" attribute echo, PIC X(1)
 * @param acctNo3        echoed account number for row 3, PIC X(11)
 * @param crdNum3        echoed card number for row 3, PIC X(16)
 * @param crdSts3        echoed status flag for row 3, PIC X(1)
 * @param crdSel4        row 4 selection char, PIC X(1)
 * @param crdStp4        row 4 COBOL-internal "copy" attribute echo, PIC X(1)
 * @param acctNo4        echoed account number for row 4, PIC X(11)
 * @param crdNum4        echoed card number for row 4, PIC X(16)
 * @param crdSts4        echoed status flag for row 4, PIC X(1)
 * @param crdSel5        row 5 selection char, PIC X(1)
 * @param crdStp5        row 5 COBOL-internal "copy" attribute echo, PIC X(1)
 * @param acctNo5        echoed account number for row 5, PIC X(11)
 * @param crdNum5        echoed card number for row 5, PIC X(16)
 * @param crdSts5        echoed status flag for row 5, PIC X(1)
 * @param crdSel6        row 6 selection char, PIC X(1)
 * @param crdStp6        row 6 COBOL-internal "copy" attribute echo, PIC X(1)
 * @param acctNo6        echoed account number for row 6, PIC X(11)
 * @param crdNum6        echoed card number for row 6, PIC X(16)
 * @param crdSts6        echoed status flag for row 6, PIC X(1)
 * @param crdSel7        row 7 selection char, PIC X(1)
 * @param crdStp7        row 7 COBOL-internal "copy" attribute echo, PIC X(1)
 * @param acctNo7        echoed account number for row 7, PIC X(11)
 * @param crdNum7        echoed card number for row 7, PIC X(16)
 * @param crdSts7        echoed status flag for row 7, PIC X(1)
 * @param infoMsg        informational message, PIC X(45)
 * @param errMsg         error message, PIC X(78)
 * @param aidKey         decoded transaction-level AID key, never null
 *
 * @see com.blitzy.carddemo.application.card.CoCrdLiOutput
 * @see CoCrdLiInput.AidKey
 * @since 1.0.0
 */
public record CoCrdLiInput(

        // ============================================================================
        // Header fields (rows 1-2 and page indicator on row 4 of the 24x80 BMS map).
        // The application class populates these on SEND-MAP; they are echoed back on
        // RECEIVE-MAP. The user does not edit any of these fields directly.
        // ============================================================================
        String trnName,         // TRNNAMEI   PIC X(4)
        String title01,         // TITLE01I   PIC X(40)
        String curDate,         // CURDATEI   PIC X(8)   — MM/DD/YY
        String pgmName,         // PGMNAMEI   PIC X(8)
        String title02,         // TITLE02I   PIC X(40)
        String curTime,         // CURTIMEI   PIC X(8)   — HH:MM:SS
        String pageNo,          // PAGENOI    PIC X(3)

        // ============================================================================
        // Filter fields — the only user-input fields on the screen. Empty values
        // (SPACES in COBOL) signal "no filter; return the global card list".
        // ============================================================================
        String acctSidFilter,   // ACCTSIDI   PIC X(11)
        String cardSidFilter,   // CARDSIDI   PIC X(16)

        // ============================================================================
        // Row 1 — selection + echoed list data. Row 1 has NO crdStp1 field (the
        // copybook lays it out with only 4 leaves; see COCRDLI.CPY lines 73-96).
        // ============================================================================
        String crdSel1,         // CRDSEL1I   PIC X(1)   — 'S' / 'U' / SPACE
        String acctNo1,         // ACCTNO1I   PIC X(11)
        String crdNum1,         // CRDNUM1I   PIC X(16)
        String crdSts1,         // CRDSTS1I   PIC X(1)

        // ============================================================================
        // Row 2 — selection, COBOL "copy" attribute echo, and echoed list data.
        // ============================================================================
        String crdSel2,         // CRDSEL2I   PIC X(1)
        String crdStp2,         // CRDSTP2I   PIC X(1)   — protected copy of CRDSEL2
        String acctNo2,         // ACCTNO2I   PIC X(11)
        String crdNum2,         // CRDNUM2I   PIC X(16)
        String crdSts2,         // CRDSTS2I   PIC X(1)

        // ============================================================================
        // Row 3
        // ============================================================================
        String crdSel3,         // CRDSEL3I   PIC X(1)
        String crdStp3,         // CRDSTP3I   PIC X(1)
        String acctNo3,         // ACCTNO3I   PIC X(11)
        String crdNum3,         // CRDNUM3I   PIC X(16)
        String crdSts3,         // CRDSTS3I   PIC X(1)

        // ============================================================================
        // Row 4
        // ============================================================================
        String crdSel4,         // CRDSEL4I   PIC X(1)
        String crdStp4,         // CRDSTP4I   PIC X(1)
        String acctNo4,         // ACCTNO4I   PIC X(11)
        String crdNum4,         // CRDNUM4I   PIC X(16)
        String crdSts4,         // CRDSTS4I   PIC X(1)

        // ============================================================================
        // Row 5
        // ============================================================================
        String crdSel5,         // CRDSEL5I   PIC X(1)
        String crdStp5,         // CRDSTP5I   PIC X(1)
        String acctNo5,         // ACCTNO5I   PIC X(11)
        String crdNum5,         // CRDNUM5I   PIC X(16)
        String crdSts5,         // CRDSTS5I   PIC X(1)

        // ============================================================================
        // Row 6
        // ============================================================================
        String crdSel6,         // CRDSEL6I   PIC X(1)
        String crdStp6,         // CRDSTP6I   PIC X(1)
        String acctNo6,         // ACCTNO6I   PIC X(11)
        String crdNum6,         // CRDNUM6I   PIC X(16)
        String crdSts6,         // CRDSTS6I   PIC X(1)

        // ============================================================================
        // Row 7
        // ============================================================================
        String crdSel7,         // CRDSEL7I   PIC X(1)
        String crdStp7,         // CRDSTP7I   PIC X(1)
        String acctNo7,         // ACCTNO7I   PIC X(11)
        String crdNum7,         // CRDNUM7I   PIC X(16)
        String crdSts7,         // CRDSTS7I   PIC X(1)

        // ============================================================================
        // Status messages (rows 23-24) — populated by the controller on SEND-MAP;
        // typically arrive as SPACES on RECEIVE-MAP unless the terminal echoed them.
        // ============================================================================
        String infoMsg,         // INFOMSGI   PIC X(45)
        String errMsg,          // ERRMSGI    PIC X(78)

        // ============================================================================
        // AID-key dispatch — how the user submitted the screen.
        // ============================================================================
        AidKey aidKey

) {

    /**
     * Compact (canonical) constructor.
     *
     * <p>Normalizes every {@link String} component so that a {@code null} reference is
     * converted to the empty {@link String} <code>""</code>. This mirrors COBOL
     * RECEIVE-MAP semantics where unfilled BMS {@code PIC X(n)} fields are SPACES,
     * never undefined. A {@code null} {@link AidKey} is rejected via
     * {@link java.util.Objects#requireNonNull(Object, String)} because the legacy
     * COBOL transaction handler always supplies a decoded AID key
     * (see {@code app/cbl/COCRDLIC.cbl} line 349 - the {@code YYYY-STORE-PFKEY}
     * paragraph stores {@code EIBAID} into {@code CCARD-AID} unconditionally).
     *
     * <p>Uses <strong>JEP 513 Flexible Constructor Bodies</strong> (finalized in Java
     * 25): the normalization statements run before the implicit canonical field
     * assignment, which is the appropriate location for COBOL-style "default to
     * SPACES" input cleansing.
     */
    public CoCrdLiInput {
        trnName       = orEmpty(trnName);
        title01       = orEmpty(title01);
        curDate       = orEmpty(curDate);
        pgmName       = orEmpty(pgmName);
        title02       = orEmpty(title02);
        curTime       = orEmpty(curTime);
        pageNo        = orEmpty(pageNo);
        acctSidFilter = orEmpty(acctSidFilter);
        cardSidFilter = orEmpty(cardSidFilter);
        crdSel1       = orEmpty(crdSel1);
        acctNo1       = orEmpty(acctNo1);
        crdNum1       = orEmpty(crdNum1);
        crdSts1       = orEmpty(crdSts1);
        crdSel2       = orEmpty(crdSel2);
        crdStp2       = orEmpty(crdStp2);
        acctNo2       = orEmpty(acctNo2);
        crdNum2       = orEmpty(crdNum2);
        crdSts2       = orEmpty(crdSts2);
        crdSel3       = orEmpty(crdSel3);
        crdStp3       = orEmpty(crdStp3);
        acctNo3       = orEmpty(acctNo3);
        crdNum3       = orEmpty(crdNum3);
        crdSts3       = orEmpty(crdSts3);
        crdSel4       = orEmpty(crdSel4);
        crdStp4       = orEmpty(crdStp4);
        acctNo4       = orEmpty(acctNo4);
        crdNum4       = orEmpty(crdNum4);
        crdSts4       = orEmpty(crdSts4);
        crdSel5       = orEmpty(crdSel5);
        crdStp5       = orEmpty(crdStp5);
        acctNo5       = orEmpty(acctNo5);
        crdNum5       = orEmpty(crdNum5);
        crdSts5       = orEmpty(crdSts5);
        crdSel6       = orEmpty(crdSel6);
        crdStp6       = orEmpty(crdStp6);
        acctNo6       = orEmpty(acctNo6);
        crdNum6       = orEmpty(crdNum6);
        crdSts6       = orEmpty(crdSts6);
        crdSel7       = orEmpty(crdSel7);
        crdStp7       = orEmpty(crdStp7);
        acctNo7       = orEmpty(acctNo7);
        crdNum7       = orEmpty(crdNum7);
        crdSts7       = orEmpty(crdSts7);
        infoMsg       = orEmpty(infoMsg);
        errMsg        = orEmpty(errMsg);
        Objects.requireNonNull(aidKey, "aidKey must not be null");
    }

    /**
     * Returns the argument if non-null, or the empty string {@code ""} otherwise.
     *
     * <p>Centralizing this single trivial helper keeps the compact constructor
     * uncluttered and removes any risk of inconsistent null-handling between
     * components.
     *
     * @param s the candidate string (may be {@code null})
     * @return {@code s} if non-null, otherwise {@code ""}
     */
    private static String orEmpty(String s) {
        return (s == null) ? "" : s;
    }

    /**
     * Factory: an input record with every {@link String} component empty and
     * {@link AidKey#OTHER} as the AID key.
     *
     * <p>This matches the initial state on the first dispatch into COCRDLI: the
     * user has not yet typed anything, all SEND-MAP outputs are still blank, and
     * no AID key has been pressed (the program treats this as the "show empty
     * form" path).
     *
     * @return a fully-blank input record (never {@code null})
     */
    public static CoCrdLiInput blank() {
        return new CoCrdLiInput(
                // header (7)
                "", "", "", "", "", "", "",
                // filters (2)
                "", "",
                // row 1 (4) — crdSel1, acctNo1, crdNum1, crdSts1
                "", "", "", "",
                // row 2 (5) — crdSel2, crdStp2, acctNo2, crdNum2, crdSts2
                "", "", "", "", "",
                // row 3 (5)
                "", "", "", "", "",
                // row 4 (5)
                "", "", "", "", "",
                // row 5 (5)
                "", "", "", "", "",
                // row 6 (5)
                "", "", "", "", "",
                // row 7 (5)
                "", "", "", "", "",
                // status messages (2) — infoMsg, errMsg
                "", "",
                // AID key (1)
                AidKey.OTHER
        );
    }

    /**
     * Factory: an input record carrying only the two filter values populated and
     * every other {@link String} component empty; AID key defaults to
     * {@link AidKey#OTHER}.
     *
     * <p>This is the typical entry path into COCRDLI from another program (e.g.,
     * from {@code COMEN01C} via the main menu, or from {@code CoCrdSlC} after a
     * single-card lookup that wants to navigate back to the listing pre-filtered
     * to a specific account or card). The caller seeds the filter values from the
     * {@code CardDemoCommarea} fields populated by the prior screen; this
     * factory then yields an input record ready to drive the first pagination
     * call.
     *
     * <p>{@code null} arguments are treated as empty strings (matching the compact
     * constructor's null-handling discipline), so this factory is safe to call
     * with any caller-provided value.
     *
     * @param acctSidFilter account-id filter to apply (may be {@code null}; treated
     *                      as empty &mdash; "no filter")
     * @param cardSidFilter card-number filter to apply (may be {@code null}; treated
     *                      as empty &mdash; "no filter")
     * @return an input record carrying just the filter values
     */
    public static CoCrdLiInput withFilter(String acctSidFilter, String cardSidFilter) {
        CoCrdLiInput b = blank();
        return new CoCrdLiInput(
                // header (7) — preserved from blank()
                b.trnName, b.title01, b.curDate, b.pgmName, b.title02, b.curTime, b.pageNo,
                // filters (2) — the caller-supplied search keys (compact constructor will null-coalesce)
                acctSidFilter, cardSidFilter,
                // row 1 (4)
                b.crdSel1, b.acctNo1, b.crdNum1, b.crdSts1,
                // row 2 (5)
                b.crdSel2, b.crdStp2, b.acctNo2, b.crdNum2, b.crdSts2,
                // row 3 (5)
                b.crdSel3, b.crdStp3, b.acctNo3, b.crdNum3, b.crdSts3,
                // row 4 (5)
                b.crdSel4, b.crdStp4, b.acctNo4, b.crdNum4, b.crdSts4,
                // row 5 (5)
                b.crdSel5, b.crdStp5, b.acctNo5, b.crdNum5, b.crdSts5,
                // row 6 (5)
                b.crdSel6, b.crdStp6, b.acctNo6, b.crdNum6, b.crdSts6,
                // row 7 (5)
                b.crdSel7, b.crdStp7, b.acctNo7, b.crdNum7, b.crdSts7,
                // status messages (2)
                b.infoMsg, b.errMsg,
                // AID key (1)
                AidKey.OTHER
        );
    }

    /**
     * AID-key state captured at BMS RECEIVE-MAP time for the COCRDLI screen.
     *
     * <p>The COBOL {@code CSSTRPFY} copybook decodes the {@code EIBAID} byte
     * returned by CICS into a set of 88-level conditions. Per AAP &sect;0.6.10
     * the <em>full</em> AID-key hierarchy (ENTER, CLEAR, PA1, PA2, PFK01-PFK12)
     * is defined as a sealed type in
     * {@code carddemo-domain.text.CcWorkAreas.AidKey}; that hierarchy supports
     * exhaustive pattern matching for screens that handle many function keys.
     *
     * <p>COCRDLI handles only four meaningful AID dispatches (see
     * {@code app/cbl/COCRDLIC.cbl} lines 370-375 where {@code CCARD-AID-ENTER},
     * {@code CCARD-AID-PFK03}, {@code CCARD-AID-PFK07}, and
     * {@code CCARD-AID-PFK08} are the only PF keys flagged as
     * {@code PFK-VALID}):
     * <ul>
     *   <li>{@link #ENTER} &mdash; submit the filter values and refresh
     *       the listing, OR process a row selection if a row has
     *       {@code 'S'} or {@code 'U'} in its {@code crdSelN} field.</li>
     *   <li>{@link #PF03_BACK} &mdash; return to the calling program
     *       (typically {@code COMEN01C} via {@code EXEC CICS XCTL}).</li>
     *   <li>{@link #PF07_BACKWARD} &mdash; paginate one screen backward.</li>
     *   <li>{@link #PF08_FORWARD} &mdash; paginate one screen forward.</li>
     * </ul>
     * Any other AID value is captured as {@link #OTHER} and yields the standard
     * "PF key not active" error message.
     *
     * <p><strong>Why an enum rather than a sealed interface here?</strong>
     * Sealed interfaces are AAP-mandated only for COBOL constructs that
     * partition a value space &mdash; in particular {@code REDEFINES} or
     * 88-level taxonomies on data fields. The AID key on COCRDLI is a closed
     * 5-state dispatch (ENTER / PF03 / PF07 / PF08 / fallback) with no
     * associated payload, which is exactly the case for which a plain
     * {@code enum} is idiomatic and sufficient. The agent prompt for this file
     * explicitly mandates {@code enum} here, mirroring the pattern already
     * established by {@code CoActVwInput.AidKey} and
     * {@code CoActUpInput.AidKey}.
     */
    public enum AidKey {

        /** The user pressed ENTER. */
        ENTER,

        /** PF03 &mdash; exit back to previous program (typically {@code COMEN01C}). */
        PF03_BACK,

        /** PF07 &mdash; paginate backward. */
        PF07_BACKWARD,

        /** PF08 &mdash; paginate forward. */
        PF08_FORWARD,

        /** Any other key. */
        OTHER
    }
}
