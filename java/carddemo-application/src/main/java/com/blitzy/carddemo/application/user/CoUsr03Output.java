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

// JEP 511 (finalized in Java 25): a single declaration imports all packages exported by the
// java.base module (and the modules it reads). This gives access to java.lang.String, the
// only type referenced by the components on this record. No other imports are required or
// permitted on this file (the file-level schema declares zero internal_imports and zero
// external_imports).
import module java.base;

/**
 * BMS output record for the {@code COUSR03 / COUSR3A} delete-user map
 * (COBOL transaction {@code CU03}, program {@code COUSR03C}).
 *
 * <p>This record is a literal projection of the {@code 01 COUSR3AO REDEFINES COUSR3AI}
 * group in {@code app/cpy-bms/COUSR03.CPY}: one {@link String} field per
 * {@code "O"}-suffixed BMS leaf, plus two runtime carriers
 * ({@code successMessage}, {@code focusField}) that capture state which COBOL
 * communicates implicitly through screen attribute bytes (color) and the
 * {@code IC} cursor-positioning marker.
 *
 * <p>The controller {@code CoUsr03C} composes this record after reading the
 * target user via {@code UserSecurityRepository#findById} (to populate the
 * confirmation fields) and after performing the delete (to populate either
 * the success message or an error message).
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/COUSR03.bms}
 *       (mapset {@code COUSR03}, map {@code COUSR3A}, size 24x80,
 *       {@code CTRL=(ALARM,FREEKB)}, {@code EXTATT=YES}).</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COUSR03.CPY}
 *       (group {@code 01 COUSR3AO REDEFINES COUSR3AI}, 153 lines).</li>
 *   <li>Translated COBOL program: {@code app/cbl/COUSR03C.cbl}
 *       ({@code PROGRAM-ID COUSR03C}, {@code WS-TRANID 'CU03'},
 *       {@code WS-USRSEC-FILE 'USRSEC  '}).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (output side)</h2>
 * <p>Per AAP &sect;0.3.5 and &sect;0.4.1, BMS maps are translated into <em>entry-contract DTO
 * records</em> on the corresponding application class. This record is the Java analog of
 * the output view of the BMS symbolic structure (the {@code COUSR3AO REDEFINES COUSR3AI}
 * overlay in {@code COUSR03.CPY}): it carries the field values supplied by application
 * logic to {@code EXEC CICS SEND MAP}, where they are rendered onto the 3270 terminal.
 * There is no web framework, no Spring binding, no Jakarta Bean Validation, no view
 * templating engine; the record is a plain Java carrier (AAP &sect;0.7.4).
 *
 * <p>It is constructed by {@code CoUsr03C} (returned to the caller wrapped in the
 * outcome record produced by the application class) and rendered by the SEND-MAP
 * equivalent layer.
 *
 * <h2>Delete-user-screen semantics</h2>
 * <p>COUSR03 is the <strong>delete</strong> screen for security users (transaction
 * {@code CU03}). The user supplies a user-id key in the {@code USRIDIN} field
 * (captured by {@code CoUsr03Input}); the controller looks up the corresponding
 * {@code SecUserData} row and populates this output record with the user's first
 * name, last name, and user type so the operator can <em>visually confirm</em> that
 * the right user is about to be deleted. The operator then presses
 * {@code F5=Delete} to commit the deletion, {@code F4=Clear} to abandon the
 * confirmation, or {@code F3=Back} to return to the menu.
 *
 * <h2>NO PASSWORD FIELD &mdash; safety mandate</h2>
 * <p><strong>This record deliberately has no password component.</strong> The
 * COUSR03 BMS map and symbolic copybook define <em>eleven</em> named output fields
 * (TRNNAMEO, TITLE01O, CURDATEO, PGMNAMEO, TITLE02O, CURTIMEO, USRIDINO, FNAMEO,
 * LNAMEO, USRTYPEO, ERRMSGO); there is no {@code PWDO} or equivalent password
 * leaf. The delete-confirmation screen shows the operator only the identifying
 * fields needed to confirm the target user (first name, last name, user type),
 * never the password value. This is consistent with AAP &sect;0.6.8
 * (&ldquo;password NOT displayed for deletion&rdquo;) and is enforced at the BMS
 * source level &mdash; no field-level translation work introduces it.
 *
 * <p>Because no password is carried, the auto-generated record {@link #toString()}
 * is safe to use in logs: it cannot leak any credential value. The deliberate
 * absence of a {@code toString()} override on this record is therefore part of
 * the security contract; do not add one.
 *
 * <h2>Field-level highlighting note</h2>
 * <p>The BMS map definition (see {@code app/bms/COUSR03.bms}) assigns
 * {@code COLOR=RED} statically to the {@code ERRMSG} field at row 23
 * (lines 140-143); no application logic is needed to colorize errors. To signal
 * an error, the controller simply populates {@link #errorMessage()} with a
 * human-readable message and leaves {@link #successMessage()} {@code false}; the
 * static map attribute renders the message in red.
 *
 * <p>Success messages reuse the same {@code ERRMSG} BMS field but the controller
 * sets {@link #successMessage()} to {@code true}, signalling the SEND-MAP
 * equivalent layer to override the BMS field color to {@code GREEN} when the
 * delete completes successfully (the typical message is
 * &ldquo;User &lt;id&gt; has been deleted ...&rdquo;). This pair-encoding
 * (message text + success flag) replaces the COBOL convention of writing
 * directly into {@code ERRMSGC} (the color attribute byte of the symbolic map);
 * see {@code app/cpy-bms/COUSR03.CPY} line 148 for the attribute byte.
 *
 * <h2>Field-level read-only display (FNAMEO, LNAMEO, USRTYPEO)</h2>
 * <p>Distinct from the COUSR01 (add) and COUSR02 (update) screens, where these
 * three fields are editable ({@code UNPROT,GREEN}), on COUSR03 they are
 * <em>read-only</em> ({@code ASKIP,BLUE}) per the BMS source &mdash; see
 * {@code app/bms/COUSR03.bms} lines 103, 116, 130. The fields display the
 * fetched user's identifying details for confirmation only; the operator cannot
 * edit them in place. The {@code IC} (cursor) marker stays on {@code USRIDIN}
 * across the delete flow so the operator can quickly look up another user after
 * a clear or successful delete.
 *
 * <h2>Cursor positioning &mdash; {@code focusField}</h2>
 * <p>The {@link #focusField()} component carries the name of the BMS field that
 * should receive {@code IC} (initial cursor) on the next {@code SEND-MAP}. On
 * COUSR03 the value is almost always the literal {@code "USRIDIN"} (mirroring
 * the static {@code IC} on the {@code USRIDIN} field in the BMS source at
 * {@code app/bms/COUSR03.bms} line 85). The COBOL convention is that the
 * application can override this by setting the {@code F} (flag) attribute byte
 * on the symbolic-map field; in Java this is hoisted to a first-class field on
 * this record so the SEND-MAP equivalent layer has a single place to look.
 *
 * <h2>Null and emptiness semantics &mdash; COBOL parity</h2>
 * <p>In COBOL, an unset BMS {@code PIC X(n)} output field is SPACES on
 * SEND-MAP, never null (no null pointer exists in COBOL). The COBOL
 * initialization in {@code COUSR03C} performs {@code MOVE LOW-VALUES TO COUSR3AO}
 * which sets every PIC X field to LOW-VALUE bytes; the SEND-MAP equivalent then
 * renders unset fields as spaces. To preserve that behavior precisely, the
 * compact constructor below replaces every {@code null} {@link String}
 * component with the empty {@link String} <code>""</code>. Downstream consumers
 * &mdash; and the BMS-emitting layer that ultimately serializes this record to
 * the 3270 wire format &mdash; can safely treat every {@link String} component
 * as a non-null value without first checking for null.
 *
 * <p>The compact constructor uses <strong>JEP 513 Flexible Constructor Bodies</strong>
 * (finalized in Java 25). Statements before the canonical field-assignment perform
 * input normalization, which is exactly the place to capture COBOL-style
 * &ldquo;default to SPACES&rdquo; semantics on SEND-MAP.
 *
 * <h2>Immutability</h2>
 * <p>Because this is a record, all components are {@code final} and accessors are
 * automatically generated; there are no setters, no Lombok, no Spring annotations,
 * no Jakarta validation annotations (AAP &sect;0.7.4). The instance is safely
 * shareable across virtual threads (per AAP &sect;0.6.6) without synchronization.
 *
 * <h2>Field-by-field mapping</h2>
 * <p>Mapping from BMS symbolic copybook {@code COUSR3AO} (output view, the
 * REDEFINES of {@code COUSR3AI}) to Java record components. The PIC column shows
 * the COBOL PICTURE clause; lengths are fixed and preserved by the runtime that
 * converts this Java record into the 3270 SEND-MAP wire format.
 * <ul>
 *   <li>{@code TRNNAMEO} &mdash; PIC X(4)  &mdash; {@link #tranName()}      &mdash; transaction id, typically {@code "CU03"}</li>
 *   <li>{@code TITLE01O} &mdash; PIC X(40) &mdash; {@link #title01()}       &mdash; primary screen title</li>
 *   <li>{@code CURDATEO} &mdash; PIC X(8)  &mdash; {@link #currentDate()}   &mdash; current date {@code mm/dd/yy}</li>
 *   <li>{@code PGMNAMEO} &mdash; PIC X(8)  &mdash; {@link #pgmName()}       &mdash; program name, typically {@code "COUSR03C"}</li>
 *   <li>{@code TITLE02O} &mdash; PIC X(40) &mdash; {@link #title02()}       &mdash; secondary screen title</li>
 *   <li>{@code CURTIMEO} &mdash; PIC X(8)  &mdash; {@link #currentTime()}   &mdash; current time {@code hh:mm:ss}</li>
 *   <li>{@code USRIDINO} &mdash; PIC X(8)  &mdash; {@link #userId()}        &mdash; user-id key (UNPROT,GREEN, editable)</li>
 *   <li>{@code FNAMEO}   &mdash; PIC X(20) &mdash; {@link #firstName()}     &mdash; first name (ASKIP,BLUE, read-only)</li>
 *   <li>{@code LNAMEO}   &mdash; PIC X(20) &mdash; {@link #lastName()}      &mdash; last name (ASKIP,BLUE, read-only)</li>
 *   <li>{@code USRTYPEO} &mdash; PIC X(1)  &mdash; {@link #userType()}      &mdash; user type (ASKIP,BLUE, read-only)</li>
 *   <li>{@code ERRMSGO}  &mdash; PIC X(78) &mdash; {@link #errorMessage()}  &mdash; error or success message text</li>
 * </ul>
 *
 * <p>Plus two runtime-only carriers (no direct BMS leaf):
 * <ul>
 *   <li>{@link #successMessage()} &mdash; {@code boolean} &mdash; {@code true}
 *       signals the SEND-MAP equivalent layer to render {@link #errorMessage()}
 *       in {@code GREEN} (success); {@code false} renders it in the BMS-default
 *       {@code RED} (error).</li>
 *   <li>{@link #focusField()} &mdash; {@link String} &mdash; name of the BMS
 *       field that should receive {@code IC} on the next SEND-MAP; typically
 *       {@code "USRIDIN"}.</li>
 * </ul>
 *
 * @param tranName       echoed transaction id ({@code CU03}), PIC X(4)
 * @param title01        title line 1, PIC X(40)
 * @param currentDate    current date ({@code mm/dd/yy}), PIC X(8)
 * @param pgmName        program name ({@code COUSR03C}), PIC X(8)
 * @param title02        title line 2, PIC X(40)
 * @param currentTime    current time ({@code hh:mm:ss}), PIC X(8)
 * @param userId         user-id key (editable), PIC X(8)
 * @param firstName      first name (display only), PIC X(20)
 * @param lastName       last name (display only), PIC X(20)
 * @param userType       user type {@code 'A'}=Admin, {@code 'U'}=User (display only), PIC X(1)
 * @param errorMessage   error or success message text, PIC X(78)
 * @param successMessage {@code true} if {@code errorMessage} carries a success
 *                       (render GREEN); {@code false} if it carries an error
 *                       (render RED)
 * @param focusField     BMS field name to receive {@code IC} on next SEND-MAP;
 *                       typically {@code "USRIDIN"}; may be empty for default
 *                       cursor placement
 *
 * @see com.blitzy.carddemo.application.user.CoUsr03C
 * @see com.blitzy.carddemo.application.user.CoUsr03Input
 */
public record CoUsr03Output(
        // ----- header row (4 BMS fields + 2 title fields) -----
        String tranName,        // TRNNAMEO  PIC X(4)
        String title01,         // TITLE01O  PIC X(40)
        String currentDate,     // CURDATEO  PIC X(8)
        String pgmName,         // PGMNAMEO  PIC X(8)
        String title02,         // TITLE02O  PIC X(40)
        String currentTime,     // CURTIMEO  PIC X(8)

        // ----- fetched user details (read-only display except userId) -----
        String userId,          // USRIDINO  PIC X(8)
        String firstName,       // FNAMEO    PIC X(20)
        String lastName,        // LNAMEO    PIC X(20)
        String userType,        // USRTYPEO  PIC X(1)

        // ----- footer / message line -----
        String errorMessage,    // ERRMSGO   PIC X(78)

        // ----- runtime carriers (not direct BMS leaves) -----
        boolean successMessage, // GREEN on successful delete; RED on error
        String focusField       // BMS field name for IC on next SEND-MAP
) {

    /**
     * Compact (canonical) constructor enforcing COBOL
     * &ldquo;SPACES by default&rdquo; semantics on every {@link String}
     * component. Each {@code null} input is coerced to the empty
     * {@link String} <code>""</code> so that downstream BMS serialization can
     * safely pad-right every field to its fixed PIC width without first
     * checking for null.
     *
     * <p>The {@link #successMessage()} {@code boolean} component is left as-is
     * (Java {@code boolean} cannot be null), so no normalization is performed
     * on it.
     *
     * <p>This is a textbook application of <strong>JEP 513 Flexible Constructor
     * Bodies</strong> (finalized in Java 25): normalization runs before the
     * canonical field bindings.
     */
    public CoUsr03Output {
        tranName     = orEmpty(tranName);
        title01      = orEmpty(title01);
        currentDate  = orEmpty(currentDate);
        pgmName      = orEmpty(pgmName);
        title02      = orEmpty(title02);
        currentTime  = orEmpty(currentTime);
        userId       = orEmpty(userId);
        firstName    = orEmpty(firstName);
        lastName     = orEmpty(lastName);
        userType     = orEmpty(userType);
        errorMessage = orEmpty(errorMessage);
        // successMessage: primitive boolean, no normalization needed
        focusField   = orEmpty(focusField);
    }

    /**
     * Factory returning a fully blank instance &mdash; every {@link String}
     * field is the empty {@link String} <code>""</code> and the
     * {@link #successMessage()} flag is {@code false}.
     *
     * <p>Useful for {@code COUSR03C} initialization before any business-logic
     * population, mirroring the COBOL {@code MOVE LOW-VALUES TO COUSR3AO}
     * statement on initial entry to the program. Downstream code typically
     * composes this blank instance with the screen-title constants and the
     * current date/time before returning to the SEND-MAP equivalent layer.
     *
     * @return a {@code CoUsr03Output} with all 12 string fields set to
     *         <code>""</code> and {@link #successMessage()} set to {@code false}
     */
    public static CoUsr03Output blank() {
        return new CoUsr03Output(
                // header (6 strings)
                "", "", "", "", "", "",
                // user details (4 strings)
                "", "", "", "",
                // footer message (1 string)
                "",
                // success flag
                false,
                // focus field
                ""
        );
    }

    // Note: NO toString() override is provided.
    //
    // Unlike CoUsr01Output and CoUsr02Output (which carry a password
    // component and override toString() to mask it), this record has no
    // sensitive component (see §"NO PASSWORD FIELD" in the class Javadoc).
    // The auto-generated record toString() therefore cannot leak any
    // credential value and is safe to use in logs. Do NOT add a toString()
    // override — its absence is part of the security contract for this DTO.

    /**
     * Null-coalescing helper. Returns the input {@link String} unchanged if
     * non-null, or the empty {@link String} <code>""</code> if null. Preserves
     * COBOL {@code MOVE LOW-VALUES} / SPACES-on-SEND-MAP behavior by
     * guaranteeing every field-bearing component is observable as a non-null
     * {@link String}.
     *
     * @param s the input string, possibly {@code null}
     * @return {@code s} if non-null, otherwise the empty string <code>""</code>
     */
    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
