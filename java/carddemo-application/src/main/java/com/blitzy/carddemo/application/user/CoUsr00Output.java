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
package com.blitzy.carddemo.application.user;

// JEP 511 (finalized in Java 25): a single declaration imports all packages
// exported by the java.base module (and the modules it reads). This supplies:
//   - java.lang.String for all 9 string-typed top-level fields and the 4
//     string-typed fields on the nested UserRow record
//   - java.util.List for the rows field (List<UserRow>)
//   - java.util.ArrayList for the mutable working buffer used in the
//     compact constructor's normalization/padding/truncation logic
//   - java.lang.IllegalArgumentException for PIC X(n) length validation
// Per AAP §0.7.3 this is the canonical module-import statement for files
// that touch many java.* packages, replacing individual java.lang.* and
// java.util.* import statements.
import module java.base;

/**
 * BMS output record for the {@code COUSR00 / COUSR0A} list-users map
 * (COBOL transaction {@code CU00}, program {@code COUSR00C}).
 *
 * <p>This record is a literal field-for-field projection of the
 * output side ({@code "O"}-suffixed leaves) of the
 * {@code 01 COUSR0AO REDEFINES COUSR0AI} group in
 * {@code app/cpy-bms/COUSR00.CPY}: one {@link String} field per
 * scalar {@code PIC X(n)} BMS leaf at the top level, plus a fixed-size
 * {@code List<UserRow>} of 10 row records carrying the per-row
 * {@code USRID0nO}/{@code FNAME0nO}/{@code LNAME0nO}/{@code UTYPE0nO}
 * leaves for {@code n} = 1..10. The output-side symbolic copybook carries
 * {@code xC} (color), {@code xP} (paint), {@code xH} (highlight),
 * {@code xV} (validation), and {@code xO} (output value) overlays for
 * each named field; this record translates <strong>only the {@code xO}
 * value leaves</strong> &mdash; the attribute-byte overlays are not part
 * of the entry-contract DTO surface.
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/COUSR00.bms}
 *       (mapset {@code COUSR00}, map {@code COUSR0A}, size 24x80,
 *       {@code CTRL=(ALARM,FREEKB)}, {@code EXTATT=YES}, 464 lines).</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COUSR00.CPY}
 *       (group {@code 01 COUSR0AO REDEFINES COUSR0AI}, 729 lines, with
 *       7 header/footer PIC X output leaves plus 40 per-row PIC X output
 *       leaves &mdash; 10 rows &times; 4 fields).</li>
 *   <li>Translated COBOL program: {@code app/cbl/COUSR00C.cbl}
 *       ({@code PROGRAM-ID COUSR00C}, {@code WS-TRANID 'CU00'},
 *       {@code WS-USRSEC-FILE 'USRSEC  '}; populates this output map from
 *       paragraphs {@code POPULATE-HEADER-INFO} (lines 562-581) and
 *       {@code POPULATE-USER-DATA} (lines 384-441) under driver
 *       {@code PROCESS-PAGE-FORWARD} / {@code PROCESS-PAGE-BACKWARD}).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (output side)</h2>
 * <p>Per AAP &sect;0.3.5 and &sect;0.4.1, BMS maps are translated into
 * <em>entry-contract DTO records</em> on the corresponding application
 * class. This record is the Java analog of the output view of the BMS
 * symbolic structure (the {@code COUSR0AO REDEFINES COUSR0AI} overlay in
 * {@code COUSR00.CPY}): it carries the field values supplied by application
 * logic to {@code EXEC CICS SEND MAP}, where they are rendered onto the
 * 3270 terminal. There is no web framework, no Spring binding, no Jakarta
 * Bean Validation, no view templating engine; the record is a plain Java
 * carrier (AAP &sect;0.7.4).
 *
 * <h2>List-user-screen semantics</h2>
 * <p>COUSR00 is the <strong>list</strong> screen for security users
 * (transaction {@code CU00}). A single page renders 10 user rows, each
 * with a selection column, user id, first name, last name, and user-type
 * (admin {@code 'A'} or regular {@code 'U'}). The COBOL controller browses
 * the {@code USRSEC} file via {@code STARTBR}/{@code READNEXT} (or
 * {@code READPREV} on PF7), populating up to 10 row records before
 * SEND-MAP. The operator chooses a row by typing {@code 'U'} (update) or
 * {@code 'D'} (delete) in the {@code SELxxxx} column; the controller then
 * {@code XCTL}s to {@code COUSR02C} or {@code COUSR03C}. Function keys
 * are: {@code ENTER}=Continue, {@code F3}=Back to admin menu,
 * {@code F7}=Backward (previous page), {@code F8}=Forward (next page).
 *
 * <h2>Fixed 10-row layout</h2>
 * <p>The COBOL map allocates 10 fixed row slots regardless of how many
 * users were actually loaded; trailing slots are blanked. This Java record
 * preserves that contract literally: the {@link #rows()} list is
 * <strong>always exactly 10 elements long</strong> after construction,
 * regardless of the input list length. The compact constructor:
 * <ul>
 *   <li>Treats {@code null} as an empty list (yielding 10 blank rows).</li>
 *   <li>Pads short lists with {@link UserRow#empty()} sentinels until 10
 *       elements are present.</li>
 *   <li>Truncates long lists to exactly 10 elements (keeping the first
 *       10), mirroring the COBOL {@code POPULATE-USER-DATA} loop that
 *       stops at {@code WS-IDX > 10}.</li>
 *   <li>Wraps the final list in {@link List#copyOf(java.util.Collection)}
 *       to produce a deep-immutable view.</li>
 * </ul>
 *
 * <h2>NO attribute carriers required</h2>
 * <p>COUSR00 is a read-only list display. The only attribute change at
 * runtime is on the {@code ERRMSG} field, whose color is statically
 * {@code RED} per {@code app/bms/COUSR00.bms} line 450 &mdash; no
 * application logic toggles it. Consequently this DTO carries no
 * {@code FieldAttributes} nested record and no runtime {@code boolean}
 * flags (cf. {@code CoUsr01Output} / {@code CoUsr02Output} which DO carry
 * a {@code successMessage} flag because their add/update flows toggle
 * {@code ERRMSG} between RED and GREEN at runtime).
 *
 * <h2>Field-for-field translation</h2>
 * <p>Each component below maps directly to a single BMS {@code PIC X(n)}
 * output leaf. The {@code n} value in parentheses is the declared on-screen
 * width; the compact constructor enforces that no component exceeds its
 * declared width.
 * <ul>
 *   <li>{@code TRNNAMEO} &mdash; PIC X(4)  &mdash; {@link #tranName()}     &mdash; echoed transaction id, typically {@code "CU00"}</li>
 *   <li>{@code TITLE01O} &mdash; PIC X(40) &mdash; {@link #title01()}      &mdash; primary screen title</li>
 *   <li>{@code CURDATEO} &mdash; PIC X(8)  &mdash; {@link #currentDate()}  &mdash; current date {@code mm/dd/yy}</li>
 *   <li>{@code PGMNAMEO} &mdash; PIC X(8)  &mdash; {@link #pgmName()}      &mdash; echoed program name, typically {@code "COUSR00C"}</li>
 *   <li>{@code TITLE02O} &mdash; PIC X(40) &mdash; {@link #title02()}      &mdash; secondary screen title (e.g. {@code "Admin User List"})</li>
 *   <li>{@code CURTIMEO} &mdash; PIC X(8)  &mdash; {@link #currentTime()}  &mdash; current time {@code hh:mm:ss}</li>
 *   <li>{@code PAGENUMO} &mdash; PIC X(8)  &mdash; {@link #pageNum()}      &mdash; page number (space-padded; caller formats)</li>
 *   <li>{@code (group)}  &mdash; List(10) &mdash; {@link #rows()}         &mdash; 10 fixed-size {@link UserRow} slots</li>
 *   <li>{@code ERRMSGO}  &mdash; PIC X(78) &mdash; {@link #errorMessage()} &mdash; error/info message line</li>
 * </ul>
 *
 * @param tranName     echoed transaction id ({@code CU00}), PIC X(4)
 * @param title01      title line 1, PIC X(40)
 * @param currentDate  current date ({@code mm/dd/yy}), PIC X(8)
 * @param pgmName      program name ({@code COUSR00C}), PIC X(8)
 * @param title02      title line 2 ({@code "Admin User List"}), PIC X(40)
 * @param currentTime  current time ({@code hh:mm:ss}), PIC X(8)
 * @param pageNum      page number (space-padded by caller), PIC X(8)
 * @param rows         exactly 10 user rows after construction (padded or
 *                     truncated as needed); never {@code null} elements
 * @param errorMessage error or info message text, PIC X(78)
 *
 * @see com.blitzy.carddemo.application.user.CoUsr00C
 * @see com.blitzy.carddemo.application.user.CoUsr00Input
 */
public record CoUsr00Output(
        // ----- Header row 1 -----
        String tranName,            // TRNNAMEO  PIC X(4)   — echoed transaction id
        String title01,             // TITLE01O  PIC X(40)  — primary title
        String currentDate,         // CURDATEO  PIC X(8)   — "mm/dd/yy"

        // ----- Header row 2 -----
        String pgmName,             // PGMNAMEO  PIC X(8)   — echoed program name
        String title02,             // TITLE02O  PIC X(40)  — secondary title
        String currentTime,         // CURTIMEO  PIC X(8)   — "hh:mm:ss"

        // ----- Row 4 (page indicator) -----
        String pageNum,             // PAGENUMO  PIC X(8)   — page number

        // ----- 10 fixed user-list rows (rows 10-19 on the 3270) -----
        List<UserRow> rows,

        // ----- Footer / message line (row 23) -----
        String errorMessage         // ERRMSGO   PIC X(78)  — error/info message
) {

    // ------------------------------------------------------------------
    //  Static constants: BMS PIC X(n) widths per app/bms/COUSR00.bms
    //  and app/cpy-bms/COUSR00.CPY. These widths drive the
    //  fail-fast PIC X(n) length validation performed by the compact
    //  constructor (AAP §0.7.1 Preserve-As-Is — values exceeding the
    //  declared width would cause silent hardware truncation in the
    //  CICS SEND-MAP layer).
    // ------------------------------------------------------------------

    /**
     * Fixed number of user-row slots rendered on every page of the
     * COUSR00 BMS map. The COBOL controller iterates
     * {@code WS-IDX} from 1 to 10 inclusive both when populating row
     * data ({@code POPULATE-USER-DATA} paragraph at
     * {@code app/cbl/COUSR00C.cbl} lines 384-441) and when blanking
     * unused trailing slots ({@code INITIALIZE-USER-DATA} paragraph at
     * lines 446-501). This DTO preserves the contract literally: the
     * {@link #rows} list is always exactly this length after
     * construction.
     */
    public static final int ROW_COUNT = 10;

    /** BMS {@code PIC X(4)} width of the {@code TRNNAME} field. */
    private static final int LEN_TRAN_NAME = 4;

    /** BMS {@code PIC X(40)} width of the {@code TITLE01} field. */
    private static final int LEN_TITLE_01 = 40;

    /** BMS {@code PIC X(8)} width of the {@code CURDATE} field. */
    private static final int LEN_CURRENT_DATE = 8;

    /** BMS {@code PIC X(8)} width of the {@code PGMNAME} field. */
    private static final int LEN_PGM_NAME = 8;

    /** BMS {@code PIC X(40)} width of the {@code TITLE02} field. */
    private static final int LEN_TITLE_02 = 40;

    /** BMS {@code PIC X(8)} width of the {@code CURTIME} field. */
    private static final int LEN_CURRENT_TIME = 8;

    /** BMS {@code PIC X(8)} width of the {@code PAGENUM} field. */
    private static final int LEN_PAGE_NUM = 8;

    /** BMS {@code PIC X(78)} width of the {@code ERRMSG} field. */
    private static final int LEN_ERROR_MESSAGE = 78;

    /**
     * Compact (canonical) constructor enforcing COBOL
     * &ldquo;SPACES by default&rdquo; semantics and the fixed
     * 10-row layout of the COUSR00 BMS map.
     *
     * <p>Normalization steps (applied in order, JEP 513 Flexible
     * Constructor Bodies):
     * <ol>
     *   <li>Every {@code null} {@link String} component is coerced to the
     *       empty string {@code ""} so that downstream BMS serialization
     *       can pad-right every field to its fixed PIC width without
     *       first checking for null.</li>
     *   <li>Every {@link String} component is validated against its
     *       declared BMS {@code PIC X(n)} width; values longer than the
     *       declared width raise an {@link IllegalArgumentException}
     *       (CWE-20 input validation).</li>
     *   <li>The {@link #rows} list is normalized to exactly
     *       {@value #ROW_COUNT} elements:
     *       <ul>
     *         <li>{@code null} input becomes a fresh empty list.</li>
     *         <li>Short lists are padded with {@link UserRow#empty()}
     *             sentinels.</li>
     *         <li>Long lists are truncated to the first
     *             {@value #ROW_COUNT} elements (matching the COBOL
     *             {@code WS-IDX &gt; 10} loop guard).</li>
     *       </ul>
     *       The final list is wrapped in
     *       {@link List#copyOf(java.util.Collection)} to produce a
     *       deep-immutable view; any subsequent attempt to mutate
     *       {@code rows()} throws {@link UnsupportedOperationException}.</li>
     * </ol>
     *
     * <p>This is a textbook application of <strong>JEP 513 Flexible
     * Constructor Bodies</strong> (finalized in Java 25): normalization
     * and validation run before the canonical field bindings.
     *
     * @throws IllegalArgumentException if any {@link String} component
     *                                  exceeds its declared BMS
     *                                  {@code PIC X(n)} width
     */
    public CoUsr00Output {
        // --- COBOL "SPACES by default" normalization on every PIC X scalar ---
        tranName     = orEmpty(tranName);
        title01      = orEmpty(title01);
        currentDate  = orEmpty(currentDate);
        pgmName      = orEmpty(pgmName);
        title02      = orEmpty(title02);
        currentTime  = orEmpty(currentTime);
        pageNum      = orEmpty(pageNum);
        errorMessage = orEmpty(errorMessage);

        // --- PIC X(n) fixed-length validation per app/bms/COUSR00.bms ---
        // Values longer than the declared width would cause silent hardware
        // truncation in the CICS SEND-MAP layer; fail fast here instead.
        checkPicLength("tranName",     tranName,     LEN_TRAN_NAME);
        checkPicLength("title01",      title01,      LEN_TITLE_01);
        checkPicLength("currentDate",  currentDate,  LEN_CURRENT_DATE);
        checkPicLength("pgmName",      pgmName,      LEN_PGM_NAME);
        checkPicLength("title02",      title02,      LEN_TITLE_02);
        checkPicLength("currentTime",  currentTime,  LEN_CURRENT_TIME);
        checkPicLength("pageNum",      pageNum,      LEN_PAGE_NUM);
        checkPicLength("errorMessage", errorMessage, LEN_ERROR_MESSAGE);

        // --- Fixed 10-row layout normalization ---
        // The COBOL map allocates 10 fixed row slots regardless of how
        // many users were loaded; trailing slots are blanked. Preserve
        // that contract literally: rows is always exactly ROW_COUNT
        // elements long after construction.
        List<UserRow> working;
        if (rows == null) {
            working = new ArrayList<>(ROW_COUNT);
        } else {
            working = new ArrayList<>(rows);
        }
        // Pad short lists with blank UserRow sentinels.
        while (working.size() < ROW_COUNT) {
            working.add(UserRow.empty());
        }
        // Truncate long lists to the first ROW_COUNT elements (matches the
        // COBOL "WS-IDX > 10" guard in POPULATE-USER-DATA, lines 384-441).
        if (working.size() > ROW_COUNT) {
            working = new ArrayList<>(working.subList(0, ROW_COUNT));
        }
        // Substitute any null row references (defensive) with blank rows.
        for (int i = 0; i < working.size(); i++) {
            if (working.get(i) == null) {
                working.set(i, UserRow.empty());
            }
        }
        // Deep-immutable wrap. List.copyOf returns an unmodifiable list.
        rows = List.copyOf(working);
    }

    /**
     * One row of the user-list display, translating four sibling BMS
     * leaves from {@code app/cpy-bms/COUSR00.CPY} (for row index
     * {@code n} = 1..10):
     * <ul>
     *   <li>{@code USRID0nO}  &mdash; PIC X(8)  &mdash; {@link #userId()}     &mdash; user id</li>
     *   <li>{@code FNAME0nO}  &mdash; PIC X(20) &mdash; {@link #firstName()}  &mdash; first name</li>
     *   <li>{@code LNAME0nO}  &mdash; PIC X(20) &mdash; {@link #lastName()}   &mdash; last name</li>
     *   <li>{@code UTYPE0nO}  &mdash; PIC X(1)  &mdash; {@link #userType()}   &mdash; user type ({@code 'A'}=Admin, {@code 'U'}=User, or space)</li>
     * </ul>
     *
     * <p>Each PIC X(n) width is enforced at construction time; values
     * exceeding the declared width raise an
     * {@link IllegalArgumentException}.
     *
     * <p>Blank rows (trailing slots when fewer than 10 users were loaded,
     * or when no users were loaded at all) are represented by
     * {@link #empty()}, which carries empty strings in all four
     * components. The COBOL {@code INITIALIZE-USER-DATA} paragraph
     * ({@code app/cbl/COUSR00C.cbl} lines 446-501) renders these as
     * spaces on the 3270 terminal.
     *
     * @param userId    user id (8 chars max), {@code USRID0nO} PIC X(8)
     * @param firstName first name (20 chars max), {@code FNAME0nO} PIC X(20)
     * @param lastName  last name (20 chars max), {@code LNAME0nO} PIC X(20)
     * @param userType  user type (1 char), {@code UTYPE0nO} PIC X(1)
     */
    public record UserRow(
            String userId,           // USRID0nO  PIC X(8)
            String firstName,        // FNAME0nO  PIC X(20)
            String lastName,         // LNAME0nO  PIC X(20)
            String userType          // UTYPE0nO  PIC X(1)
    ) {

        /** BMS {@code PIC X(8)} width of the {@code USRID0n} field. */
        private static final int LEN_USER_ID = 8;

        /** BMS {@code PIC X(20)} width of the {@code FNAME0n} field. */
        private static final int LEN_FIRST_NAME = 20;

        /** BMS {@code PIC X(20)} width of the {@code LNAME0n} field. */
        private static final int LEN_LAST_NAME = 20;

        /** BMS {@code PIC X(1)} width of the {@code UTYPE0n} field. */
        private static final int LEN_USER_TYPE = 1;

        /**
         * Compact (canonical) constructor enforcing
         * &ldquo;SPACES by default&rdquo; semantics and PIC X(n) width
         * validation on each of the four row leaves.
         *
         * @throws IllegalArgumentException if any component exceeds its
         *                                  declared BMS width
         */
        public UserRow {
            userId    = orEmpty(userId);
            firstName = orEmpty(firstName);
            lastName  = orEmpty(lastName);
            userType  = orEmpty(userType);

            checkPicLength("userId",    userId,    LEN_USER_ID);
            checkPicLength("firstName", firstName, LEN_FIRST_NAME);
            checkPicLength("lastName",  lastName,  LEN_LAST_NAME);
            checkPicLength("userType",  userType,  LEN_USER_TYPE);
        }

        /**
         * Factory returning a fully blank row &mdash; every component is
         * the empty string {@code ""}. Used by
         * {@link CoUsr00Output#blank()} and by the compact constructor's
         * row-list padding logic to fill trailing slots when fewer than
         * {@link CoUsr00Output#ROW_COUNT} users were loaded.
         *
         * <p>The empty-string contract mirrors the COBOL
         * {@code INITIALIZE-USER-DATA} paragraph ({@code app/cbl/COUSR00C.cbl}
         * lines 446-501), which performs {@code MOVE SPACES TO USRID0nI,
         * FNAME0nI, LNAME0nI, UTYPE0nI} before the next SEND-MAP. The
         * 3270 terminal renders these as spaces.
         *
         * @return a {@code UserRow} with all four components set to
         *         the empty string {@code ""}
         */
        public static UserRow empty() {
            return new UserRow("", "", "", "");
        }
    }

    /**
     * Factory returning a fully blank output &mdash; every string field
     * is the empty string {@code ""} and the {@link #rows} list contains
     * exactly {@value #ROW_COUNT} blank {@link UserRow} instances.
     *
     * <p>Useful for {@code CoUsr00C} initialization before any
     * business-logic population, mirroring the COBOL
     * {@code MOVE LOW-VALUES TO COUSR0AO} statement on initial entry to
     * the program ({@code app/cbl/COUSR00C.cbl} line 117). Downstream
     * code typically composes this blank instance with the screen-title
     * constants and the current date/time from
     * {@code POPULATE-HEADER-INFO} before returning to the SEND-MAP
     * equivalent layer.
     *
     * @return a {@code CoUsr00Output} with all 8 PIC X string fields
     *         set to {@code ""} and 10 blank rows
     */
    public static CoUsr00Output blank() {
        List<UserRow> emptyRows = new ArrayList<>(ROW_COUNT);
        for (int i = 0; i < ROW_COUNT; i++) {
            emptyRows.add(UserRow.empty());
        }
        return new CoUsr00Output(
                "",          // tranName
                "",          // title01
                "",          // currentDate
                "",          // pgmName
                "",          // title02
                "",          // currentTime
                "",          // pageNum
                emptyRows,   // rows
                ""           // errorMessage
        );
    }

    // ------------------------------------------------------------------
    //  Private helpers (shared by the outer record and the nested
    //  UserRow record via the nesting rule that a top-level static
    //  member is visible to all enclosed records).
    // ------------------------------------------------------------------

    /**
     * Null-coalescing helper. Returns the input {@link String} unchanged
     * if non-null, or the empty string {@code ""} if null. Preserves
     * COBOL {@code MOVE LOW-VALUES} / SPACES-on-SEND-MAP behavior by
     * guaranteeing every field-bearing component is observable as a
     * non-null {@link String}.
     *
     * @param s the input string, possibly {@code null}
     * @return {@code s} if non-null, otherwise the empty string {@code ""}
     */
    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * Validates that a {@link String} component does not exceed its
     * declared BMS {@code PIC X(n)} on-screen width.
     *
     * <p>Enforces the AAP &sect;0.7.1 Preserve-As-Is contract at the
     * DTO boundary (CWE-20 input validation): values longer than the
     * declared BMS width would cause silent hardware truncation in the
     * CICS SEND-MAP layer. Shorter values are accepted unchanged
     * &mdash; the BMS renderer pads-right with spaces to the declared
     * width before transmission.
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
}
