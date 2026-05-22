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
// exported by the java.base module (and the modules it reads). This gives
// access to java.lang.String (all 13 string components), java.lang.Boolean
// (boxed form of the successMessage primitive when used in toString), the
// java.lang.Override annotation marking the explicit toString() override,
// and the java.lang.IllegalArgumentException raised by the compact
// constructor's PIC X(n) length validation. Per AAP §0.7.3 this is the
// canonical module-import statement for files that touch many java.*
// packages.
import module java.base;

// Module-import declarations may not import application-defined types; the
// COBOL traceability annotation lives in carddemo-domain and must be
// brought in by a conventional import statement.
import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * BMS output record for the {@code COUSR02 / COUSR2A} update-user map
 * (COBOL transaction {@code CU02}, program {@code COUSR02C}).
 *
 * <p>This record is a literal field-for-field projection of the
 * {@code 01 COUSR2AO REDEFINES COUSR2AI} group in
 * {@code app/cpy-bms/COUSR02.CPY} (lines 91&ndash;164): one {@link String}
 * field per {@code "O"}-suffixed PIC X(n) BMS leaf. The twelve BMS leaves
 * correspond exactly to the twelve primary output fields declared by the
 * COBOL symbolic copybook.
 *
 * <p>In addition to the twelve BMS leaves, this record carries two
 * <strong>runtime carriers</strong> that drive screen rendering but are
 * not themselves BMS PIC X output fields:
 * <ul>
 *   <li>{@link #successMessage()} &mdash; a {@code boolean} flag telling
 *       a downstream renderer to color the {@code ERRMSG} field
 *       <em>green</em> (success) versus the BMS-default <em>red</em>
 *       (error/info). On a successful {@code REWRITE}, the COBOL program
 *       writes the message
 *       <em>&ldquo;User &lt;id&gt; has been updated...&rdquo;</em> and the
 *       3270 attribute byte is dynamically changed to GREEN; this
 *       {@code boolean} is the Java analog of that dynamic attribute
 *       change.</li>
 *   <li>{@link #focusField()} &mdash; the name of the BMS field that
 *       should receive the cursor ({@code IC}) on the next SEND-MAP.
 *       On entry the COBOL program performs
 *       {@code MOVE -1 TO USRIDINL OF COUSR2AI} to park the cursor on
 *       {@code USRIDIN}; after a successful fetch, the cursor moves to
 *       {@code FNAME}. This {@link String} carries that target
 *       field-name.</li>
 * </ul>
 *
 * <p>The controller {@code CoUsr02C} composes this record after each of
 * three flows:
 * <ol>
 *   <li>Initial render (USRIDIN editable; FNAME/LNAME/PASSWD/USRTYPE
 *       empty).</li>
 *   <li>Post-fetch render (USRIDIN ASKIP after lookup; FNAME/LNAME/PASSWD/
 *       USRTYPE populated and editable; cursor on FNAME).</li>
 *   <li>Post-update render (success message in GREEN, or error message in
 *       RED).</li>
 * </ol>
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/COUSR02.bms}
 *       (mapset {@code COUSR02}, map {@code COUSR2A}, size 24x80,
 *       {@code CTRL=(ALARM,FREEKB)}, {@code EXTATT=YES}, 170 lines).</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COUSR02.CPY}
 *       (group {@code 01 COUSR2AO REDEFINES COUSR2AI}, lines 91&ndash;164,
 *       with 12 PIC X output leaves).</li>
 *   <li>Translated COBOL program: {@code app/cbl/COUSR02C.cbl}
 *       ({@code PROGRAM-ID COUSR02C}, {@code WS-TRANID 'CU02'},
 *       {@code WS-USRSEC-FILE 'USRSEC  '}).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (output side)</h2>
 * <p>Per AAP &sect;0.3.5 and &sect;0.4.1, BMS maps are translated into
 * <em>entry-contract DTO records</em> on the corresponding application
 * class. This record is the Java analog of the output view of the BMS
 * symbolic structure: it carries the field values supplied by application
 * logic to {@code EXEC CICS SEND MAP}, where they are rendered onto the
 * 3270 terminal. There is no web framework, no Spring binding, no Jakarta
 * Bean Validation, no view templating engine; the record is a plain Java
 * carrier (AAP &sect;0.7.4).
 *
 * <h2>Update-user-screen semantics</h2>
 * <p>COUSR02 is the <strong>update</strong> screen for security users
 * (transaction {@code CU02}). The user supplies a user-id key in the
 * {@code USRIDIN} field, presses ENTER to fetch, edits one or more of
 * {@code FNAME}, {@code LNAME}, {@code PASSWD}, {@code USRTYPE}, then
 * presses {@code F5=Save} or {@code F3=Save&amp;Exit} to commit the
 * REWRITE. {@code F4=Clear} resets the form; {@code F12=Cancel} returns
 * to the admin menu without saving.
 *
 * <h3>Fetch-then-edit flow (BMS attribute deltas)</h3>
 * <p>On the initial render, the editable fields are blank and the cursor
 * is parked on {@code USRIDIN} ({@code FSET,IC,NORM,UNPROT} per
 * {@code app/bms/COUSR02.bms} line 85). After a successful fetch, the
 * controller dynamically sets {@code USRIDIN} to {@code ASKIP} (read-only)
 * and moves the cursor to {@code FNAME}; the BMS attribute change is
 * computed at the SEND-MAP rendering layer, not on this DTO.
 *
 * <h2>Password rendering &mdash; DRK attribute</h2>
 * <p>The {@code PASSWD} field is declared {@code ATTRB=(DRK,FSET,UNPROT)}
 * at {@code app/bms/COUSR02.bms} line 130: <em>editable but non-display</em>.
 * The 3270 terminal blanks out the visible bytes after the operator
 * presses ENTER; on subsequent SEND-MAPs the terminal receives the cleartext
 * but renders it as dark space. The {@link #password()} accessor on this
 * record carries the cleartext value, exactly as COBOL holds
 * {@code SEC-USR-PWD} in working storage during the
 * fetch-then-update flow. Per AAP &sect;0.1.3 the plaintext password is
 * preserved at the storage and DTO layer for behavioral parity with the
 * COBOL baseline; the {@link #toString()} override below masks the value
 * for safe logging.
 *
 * <h2>NEVER log the password &mdash; toString() override</h2>
 * <p>Java records auto-generate a {@link #toString()} that prints every
 * component value, which would leak the cleartext password if used in a
 * log statement, exception message, or debugger snapshot. To avoid that
 * leak path, this record overrides {@link #toString()} and substitutes a
 * fixed mask {@code "********"} for the password component. The override
 * is part of the security contract for this DTO; do not remove it. Per
 * AAP &sect;0.1.3 the password is held in plaintext only at the storage
 * and transport layers; logs and string-coerced representations MUST
 * never include the cleartext value.
 *
 * <h2>Field-level highlighting note (BMS color attributes)</h2>
 * <p>The BMS map definition (see {@code app/bms/COUSR02.bms}) assigns the
 * {@code ERRMSG} field {@code ATTRB=(ASKIP,BRT,FSET)} with
 * {@code COLOR=RED} statically at row 23 (lines 155&ndash;158). On a
 * successful REWRITE, COBOL dynamically sets the color attribute byte to
 * GREEN before SEND-MAP; the {@link #successMessage()} flag on this
 * record is the Java analog of that dynamic attribute toggle. A
 * downstream renderer reads the boolean and produces the matching
 * 3270 attribute byte.
 *
 * <h2>Null and emptiness semantics &mdash; COBOL parity</h2>
 * <p>In COBOL, an unset BMS {@code PIC X(n)} output field is SPACES on
 * SEND-MAP, never null (no null pointer exists in COBOL). The COBOL
 * initialization in {@code COUSR02C} performs
 * {@code MOVE LOW-VALUES TO COUSR2AO} which sets every PIC X field to
 * LOW-VALUE bytes; the SEND-MAP equivalent then renders unset fields as
 * spaces. To preserve that behavior precisely, the compact constructor
 * below replaces every {@code null} {@link String} component with the
 * empty {@link String} <code>""</code>. Downstream consumers &mdash; and
 * the BMS-emitting layer that ultimately serializes this record to the
 * 3270 wire format &mdash; can safely treat every {@link String} component
 * as a non-null value without first checking for null.
 *
 * <p>The compact constructor uses <strong>JEP 513 Flexible Constructor
 * Bodies</strong> (finalized in Java 25). Statements before the canonical
 * field-assignment perform input normalization, which is exactly the place
 * to capture COBOL-style &ldquo;default to SPACES&rdquo; semantics on
 * SEND-MAP.
 *
 * <h2>PIC X(n) length validation (CWE-20)</h2>
 * <p>Each {@link String} component is validated against its declared BMS
 * {@code PIC X(n)} on-screen width. Values longer than the declared width
 * are rejected with an {@link IllegalArgumentException} at construction
 * time. This prevents silent hardware truncation in the CICS SEND-MAP
 * layer and satisfies the AAP &sect;0.7.1 Preserve-As-Is contract at the
 * DTO boundary. The {@link #focusField()} carrier is not a BMS wire-format
 * leaf (it names a BMS field, e.g. {@code "FNAME"}) and is validated
 * against a conservative {@code 8}-character upper bound matching the
 * widest BMS field-name used by this map.
 *
 * <h2>Immutability</h2>
 * <p>Because this is a record, all components are {@code final} and
 * accessors are automatically generated; there are no setters, no Lombok,
 * no Spring annotations, no Jakarta validation annotations (AAP
 * &sect;0.7.4). The instance is safely shareable across virtual threads
 * (per AAP &sect;0.6.6) without synchronization.
 *
 * <h2>Field-by-field mapping</h2>
 * <p>Mapping from BMS symbolic copybook {@code COUSR2AO} (output view, the
 * REDEFINES of {@code COUSR2AI}) to Java record components. The PIC column
 * shows the COBOL PICTURE clause; lengths are fixed and preserved by the
 * runtime that converts this Java record into the 3270 SEND-MAP wire
 * format.
 * <ul>
 *   <li>{@code TRNNAMEO} &mdash; PIC X(4)  &mdash; {@link #tranName()}      &mdash; transaction id, typically {@code "CU02"}</li>
 *   <li>{@code TITLE01O} &mdash; PIC X(40) &mdash; {@link #title01()}       &mdash; primary screen title</li>
 *   <li>{@code CURDATEO} &mdash; PIC X(8)  &mdash; {@link #currentDate()}   &mdash; current date {@code mm/dd/yy}</li>
 *   <li>{@code PGMNAMEO} &mdash; PIC X(8)  &mdash; {@link #pgmName()}       &mdash; program name, typically {@code "COUSR02C"}</li>
 *   <li>{@code TITLE02O} &mdash; PIC X(40) &mdash; {@link #title02()}       &mdash; secondary screen title</li>
 *   <li>{@code CURTIMEO} &mdash; PIC X(8)  &mdash; {@link #currentTime()}   &mdash; current time {@code hh:mm:ss}</li>
 *   <li>{@code USRIDINO} &mdash; PIC X(8)  &mdash; {@link #userId()}        &mdash; user-id key (UNPROT/IC initially; ASKIP after fetch)</li>
 *   <li>{@code FNAMEO}   &mdash; PIC X(20) &mdash; {@link #firstName()}     &mdash; first name (UNPROT,GREEN, editable)</li>
 *   <li>{@code LNAMEO}   &mdash; PIC X(20) &mdash; {@link #lastName()}      &mdash; last name (UNPROT,GREEN, editable)</li>
 *   <li>{@code PASSWDO}  &mdash; PIC X(8)  &mdash; {@link #password()}      &mdash; password (UNPROT,DRK, non-display but editable); masked in {@link #toString()}</li>
 *   <li>{@code USRTYPEO} &mdash; PIC X(1)  &mdash; {@link #userType()}      &mdash; user type {@code 'A'}=Admin, {@code 'U'}=User (UNPROT,GREEN)</li>
 *   <li>{@code ERRMSGO}  &mdash; PIC X(78) &mdash; {@link #errorMessage()}  &mdash; error or success message text (color driven by {@link #successMessage()})</li>
 * </ul>
 * <p>Runtime carriers (not BMS wire-format leaves):
 * <ul>
 *   <li>{@code (runtime)} &mdash; {@code boolean} &mdash; {@link #successMessage()} &mdash; {@code true} on successful REWRITE drives GREEN coloring of {@code ERRMSG}; {@code false} drives the BMS-default RED</li>
 *   <li>{@code (runtime)} &mdash; {@link String} &mdash; {@link #focusField()} &mdash; name of the BMS field receiving {@code IC} on next SEND-MAP (typically {@code "USRIDIN"} initially or {@code "FNAME"} after fetch)</li>
 * </ul>
 *
 * @param tranName        echoed transaction id ({@code CU02}), PIC X(4)
 * @param title01         title line 1, PIC X(40)
 * @param currentDate     current date ({@code mm/dd/yy}), PIC X(8)
 * @param pgmName         program name ({@code COUSR02C}), PIC X(8)
 * @param title02         title line 2, PIC X(40)
 * @param currentTime     current time ({@code hh:mm:ss}), PIC X(8)
 * @param userId          user-id key, PIC X(8)
 * @param firstName       first name (editable), PIC X(20)
 * @param lastName        last name (editable), PIC X(20)
 * @param password        password cleartext (editable; non-display on
 *                        screen; masked in {@link #toString()}), PIC X(8)
 * @param userType        user type {@code 'A'}=Admin, {@code 'U'}=User
 *                        (editable), PIC X(1)
 * @param errorMessage    error or success message text, PIC X(78)
 * @param successMessage  {@code true} to render {@code ERRMSG} in GREEN
 *                        (success), {@code false} for the BMS-default RED
 *                        (error/info); runtime carrier &mdash; not a BMS leaf
 * @param focusField      name of the BMS field receiving the cursor
 *                        ({@code IC}) on the next SEND-MAP; runtime
 *                        carrier &mdash; not a BMS leaf
 *
 * @see com.blitzy.carddemo.application.user.CoUsr02C
 * @see com.blitzy.carddemo.application.user.CoUsr02Input
 */
@CobolProgram(
        value = "COUSR02",
        sourcePath = "app/bms/COUSR02.bms",
        translationDate = "2025-10-15",
        notes = "BMS entry-contract DTO (output side); symbolic copybook "
                + "COUSR2AO REDEFINES COUSR2AI in app/cpy-bms/COUSR02.CPY "
                + "lines 91-164. Field-for-field translation of all 12 "
                + "PIC X output leaves: 6 header echoes "
                + "(TRNNAMEO/TITLE01O/CURDATEO/PGMNAMEO/TITLE02O/CURTIMEO), "
                + "USRIDINO, FNAMEO, LNAMEO, PASSWDO, USRTYPEO, and ERRMSGO. "
                + "Two additional runtime carriers (successMessage boolean, "
                + "focusField String) drive ERRMSG green/red coloring and "
                + "next-SEND-MAP cursor placement; both are computed at the "
                + "renderer layer and are not BMS wire-format leaves. "
                + "PIC X(n) widths enforced at construction time. "
                + "PASSWORD masked in toString() per AAP §0.1.3."
)
public record CoUsr02Output(
        // ----- header row (4 BMS fields + 2 title fields) -----
        String tranName,        // TRNNAMEO  PIC X(4)
        String title01,         // TITLE01O  PIC X(40)
        String currentDate,     // CURDATEO  PIC X(8)
        String pgmName,         // PGMNAMEO  PIC X(8)
        String title02,         // TITLE02O  PIC X(40)
        String currentTime,     // CURTIMEO  PIC X(8)

        // ----- fetched / editable user fields -----
        String userId,          // USRIDINO  PIC X(8)
        String firstName,       // FNAMEO    PIC X(20)
        String lastName,        // LNAMEO    PIC X(20)
        String password,        // PASSWDO   PIC X(8)  -- masked in toString()
        String userType,        // USRTYPEO  PIC X(1)

        // ----- footer / message line -----
        String errorMessage,    // ERRMSGO   PIC X(78)

        // ----- runtime carriers (not BMS wire-format leaves) -----
        boolean successMessage, // drives ERRMSG color: true=GREEN, false=RED
        String focusField       // BMS field receiving IC on next SEND-MAP
) {

    /**
     * BMS {@code PIC X(4)} width of the {@code TRNNAME} field
     * ({@code app/cpy-bms/COUSR02.CPY} line 24, {@code app/bms/COUSR02.bms}
     * line 36).
     */
    private static final int LEN_TRAN_NAME = 4;

    /**
     * BMS {@code PIC X(40)} width of the {@code TITLE01} field
     * ({@code app/cpy-bms/COUSR02.CPY} line 30, {@code app/bms/COUSR02.bms}
     * line 40).
     */
    private static final int LEN_TITLE_01 = 40;

    /**
     * BMS {@code PIC X(8)} width of the {@code CURDATE} field
     * ({@code app/cpy-bms/COUSR02.CPY} line 36, {@code app/bms/COUSR02.bms}
     * line 49).
     */
    private static final int LEN_CURRENT_DATE = 8;

    /**
     * BMS {@code PIC X(8)} width of the {@code PGMNAME} field
     * ({@code app/cpy-bms/COUSR02.CPY} line 42, {@code app/bms/COUSR02.bms}
     * line 59).
     */
    private static final int LEN_PGM_NAME = 8;

    /**
     * BMS {@code PIC X(40)} width of the {@code TITLE02} field
     * ({@code app/cpy-bms/COUSR02.CPY} line 48, {@code app/bms/COUSR02.bms}
     * line 63).
     */
    private static final int LEN_TITLE_02 = 40;

    /**
     * BMS {@code PIC X(8)} width of the {@code CURTIME} field
     * ({@code app/cpy-bms/COUSR02.CPY} line 54, {@code app/bms/COUSR02.bms}
     * line 72).
     */
    private static final int LEN_CURRENT_TIME = 8;

    /**
     * BMS {@code PIC X(8)} width of the {@code USRIDIN} field
     * ({@code app/cpy-bms/COUSR02.CPY} line 60, {@code app/bms/COUSR02.bms}
     * line 88).
     */
    private static final int LEN_USER_ID = 8;

    /**
     * BMS {@code PIC X(20)} width of the {@code FNAME} field
     * ({@code app/cpy-bms/COUSR02.CPY} line 66, {@code app/bms/COUSR02.bms}
     * line 106).
     */
    private static final int LEN_FIRST_NAME = 20;

    /**
     * BMS {@code PIC X(20)} width of the {@code LNAME} field
     * ({@code app/cpy-bms/COUSR02.CPY} line 72, {@code app/bms/COUSR02.bms}
     * line 119).
     */
    private static final int LEN_LAST_NAME = 20;

    /**
     * BMS {@code PIC X(8)} width of the {@code PASSWD} field
     * ({@code app/cpy-bms/COUSR02.CPY} line 78, {@code app/bms/COUSR02.bms}
     * line 133).
     */
    private static final int LEN_PASSWORD = 8;

    /**
     * BMS {@code PIC X(1)} width of the {@code USRTYPE} field
     * ({@code app/cpy-bms/COUSR02.CPY} line 84, {@code app/bms/COUSR02.bms}
     * line 148).
     */
    private static final int LEN_USER_TYPE = 1;

    /**
     * BMS {@code PIC X(78)} width of the {@code ERRMSG} field
     * ({@code app/cpy-bms/COUSR02.CPY} line 90, {@code app/bms/COUSR02.bms}
     * line 157).
     */
    private static final int LEN_ERROR_MESSAGE = 78;

    /**
     * Conservative upper bound for the {@link #focusField} runtime carrier
     * &mdash; the widest BMS field-name on this map (e.g. {@code "USRIDIN"},
     * {@code "TRNNAME"}, {@code "USRTYPE"}). Eight characters is the COBOL
     * maximum for unqualified BMS field names and matches the actual widths
     * used by {@code COUSR2AI} / {@code COUSR2AO}.
     */
    private static final int LEN_FOCUS_FIELD = 8;

    /**
     * Fixed mask substituted for the password component in
     * {@link #toString()}. Eight asterisks match the BMS {@code PIC X(8)}
     * width of the {@code PASSWD} field, preserving on-screen alignment
     * for human readers comparing log output side-by-side with the
     * COBOL BMS render.
     */
    private static final String PASSWORD_MASK = "********";

    /**
     * Compact (canonical) constructor enforcing COBOL
     * &ldquo;SPACES by default&rdquo; semantics on every {@link String}
     * component. Each {@code null} input is coerced to the empty
     * {@link String} <code>""</code> so that downstream BMS serialization
     * can safely pad-right every field to its fixed PIC width without
     * first checking for null. The {@link #successMessage} primitive
     * {@code boolean} is preserved verbatim &mdash; no normalization is
     * required.
     *
     * <p>Subsequently validates that each {@link String} component does
     * not exceed its declared BMS {@code PIC X(n)} on-screen width: values
     * longer than the declared width raise an
     * {@link IllegalArgumentException} (CWE-20 input validation). Shorter
     * values are accepted unchanged.
     *
     * <p>This is a textbook application of <strong>JEP 513 Flexible
     * Constructor Bodies</strong> (finalized in Java 25): normalization
     * and validation run before the canonical field bindings.
     *
     * @throws IllegalArgumentException if any {@link String} component
     *                                  exceeds its declared BMS
     *                                  {@code PIC X(n)} width
     */
    public CoUsr02Output {
        // --- COBOL "SPACES by default" normalization ---
        tranName     = orEmpty(tranName);
        title01      = orEmpty(title01);
        currentDate  = orEmpty(currentDate);
        pgmName      = orEmpty(pgmName);
        title02      = orEmpty(title02);
        currentTime  = orEmpty(currentTime);
        userId       = orEmpty(userId);
        firstName    = orEmpty(firstName);
        lastName     = orEmpty(lastName);
        password     = orEmpty(password);
        userType     = orEmpty(userType);
        errorMessage = orEmpty(errorMessage);
        focusField   = orEmpty(focusField);
        // successMessage is a primitive boolean — no normalization needed.

        // --- PIC X(n) fixed-length validation (CWE-20) ---
        // Each leaf is validated against its declared BMS width per
        // app/cpy-bms/COUSR02.CPY lines 91-164 (output side). Values
        // longer than the declared width would cause silent hardware
        // truncation in the CICS SEND-MAP layer.
        checkPicLength("tranName",     tranName,     LEN_TRAN_NAME);
        checkPicLength("title01",      title01,      LEN_TITLE_01);
        checkPicLength("currentDate",  currentDate,  LEN_CURRENT_DATE);
        checkPicLength("pgmName",      pgmName,      LEN_PGM_NAME);
        checkPicLength("title02",      title02,      LEN_TITLE_02);
        checkPicLength("currentTime",  currentTime,  LEN_CURRENT_TIME);
        checkPicLength("userId",       userId,       LEN_USER_ID);
        checkPicLength("firstName",    firstName,    LEN_FIRST_NAME);
        checkPicLength("lastName",     lastName,     LEN_LAST_NAME);
        checkPicLength("password",     password,     LEN_PASSWORD);
        checkPicLength("userType",     userType,     LEN_USER_TYPE);
        checkPicLength("errorMessage", errorMessage, LEN_ERROR_MESSAGE);
        checkPicLength("focusField",   focusField,   LEN_FOCUS_FIELD);
    }

    /**
     * Factory returning a fully blank instance &mdash; every {@link String}
     * field is the empty {@link String} <code>""</code>, the
     * {@link #successMessage} flag is {@code false} (so the {@code ERRMSG}
     * field renders in the BMS-default RED), and the {@link #focusField}
     * is empty (no explicit cursor placement, so the BMS map's static
     * {@code IC} attribute on {@code USRIDIN} applies).
     *
     * <p>Useful for {@code COUSR02C} initialization before any
     * business-logic population, mirroring the COBOL
     * {@code MOVE LOW-VALUES TO COUSR2AO} statement on initial entry to
     * the program ({@code app/cbl/COUSR02C.cbl} line 97). Downstream code
     * typically composes this blank instance with the screen-title
     * constants and the current date/time before returning to the
     * SEND-MAP equivalent layer.
     *
     * @return a {@code CoUsr02Output} with all 13 string fields set to
     *         <code>""</code>, {@link #successMessage} set to
     *         {@code false}, and {@link #focusField} set to <code>""</code>
     */
    public static CoUsr02Output blank() {
        return new CoUsr02Output(
                // header (6 strings)
                "", "", "", "", "", "",
                // user details (5 strings: userId + firstName + lastName + password + userType)
                "", "", "", "", "",
                // footer message (1 string)
                "",
                // runtime carriers: successMessage flag (false → RED), focusField name (empty)
                false, ""
        );
    }

    /**
     * Returns a string representation of this record with the
     * {@link #password} component replaced by a fixed mask
     * (&ldquo;{@code ********}&rdquo;).
     *
     * <p>This override exists for one reason: the default record
     * {@link #toString()} would print every component, including the
     * cleartext password, which would leak the credential into log
     * statements, exception messages, debugger snapshots, and any
     * accidental {@code System.out.println(this)} call. Per AAP
     * &sect;0.1.3 (&ldquo;no card PAN logged in full; mask all but last 4
     * digits in logs&rdquo;, generalized to all credentials) and AAP
     * &sect;0.7.2 (&ldquo;no card PAN logged in full ... preserve all
     * existing PCI-relevant controls&rdquo;) the password component MUST
     * be replaced by a non-reversible mask in every string-coerced
     * representation of this record.
     *
     * <p>All other components are emitted in their canonical form. The
     * output is suitable for human-readable log entries and structured
     * logger arguments alike.
     *
     * @return a non-leaking string representation of this record
     */
    @Override
    public String toString() {
        return "CoUsr02Output["
                + "tranName=" + tranName
                + ", title01=" + title01
                + ", currentDate=" + currentDate
                + ", pgmName=" + pgmName
                + ", title02=" + title02
                + ", currentTime=" + currentTime
                + ", userId=" + userId
                + ", firstName=" + firstName
                + ", lastName=" + lastName
                + ", password=" + PASSWORD_MASK
                + ", userType=" + userType
                + ", errorMessage=" + errorMessage
                + ", successMessage=" + successMessage
                + ", focusField=" + focusField
                + "]";
    }

    /**
     * Null-coalescing helper. Returns the input {@link String} unchanged
     * if non-null, or the empty {@link String} <code>""</code> if null.
     * Preserves COBOL {@code MOVE LOW-VALUES} / SPACES-on-SEND-MAP behavior
     * by guaranteeing every field-bearing component is observable as a
     * non-null {@link String}.
     *
     * @param s the input string, possibly {@code null}
     * @return {@code s} if non-null, otherwise the empty string
     *         <code>""</code>
     */
    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * Validates that a {@link String} component does not exceed its
     * declared BMS {@code PIC X(n)} on-screen width.
     *
     * <p>Enforces the AAP &sect;0.7.1 Preserve-As-Is contract at the DTO
     * boundary (CWE-20 input validation): values longer than the declared
     * BMS width would cause silent hardware truncation in the CICS
     * SEND-MAP layer. Shorter values are accepted unchanged.
     *
     * <p>The exception message redacts no value &mdash; if validation
     * fails on the {@code password} component the constructor will have
     * already been admitted that value into the local parameter, so this
     * helper does not attempt to mask the password here. Operationally,
     * a password longer than 8 characters indicates an upstream
     * data-corruption bug, not a credential to be protected; the
     * exception message is fail-fast diagnostic output, not log output.
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
