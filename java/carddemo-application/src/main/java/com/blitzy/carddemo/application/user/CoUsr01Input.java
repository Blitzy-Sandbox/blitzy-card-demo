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
// access to:
//   - java.lang.String (all five PIC X(n) input fields: firstName, lastName,
//     userId, password, userType, plus the internal helpers orEmpty/clamp);
//   - java.lang.Override (used to mark the explicit toString() override that
//     masks the plaintext password to satisfy the AAP §0.1.3 no-credential-
//     in-logs security mandate);
//   - java.lang.Enum (the nested AidKey enum scoped to this Input).
// Per AAP §0.7.3 this is the canonical module-import statement for files
// that touch many java.* packages, replacing individual java.lang.* imports.
import module java.base;

/**
 * BMS input record for the {@code COUSR01 / COUSR1A} add-user map
 * (COBOL transaction {@code CU01}, program {@code COUSR01C}).
 *
 * <p>This record is a literal field-for-field projection of the input view
 * (the {@code 01 COUSR1AI} group at {@code app/cpy-bms/COUSR01.CPY}
 * lines 17&ndash;90) of the COUSR01 symbolic map: one {@link String}
 * component per {@code "I"}-suffixed PIC X(n) BMS leaf that carries
 * operator-supplied data (first name, last name, user-id, password,
 * user-type), plus one {@link AidKey} discriminator capturing which CICS
 * attention identifier was pressed. The header-row leaves
 * ({@code TRNNAMEI}, {@code TITLE01I}, {@code CURDATEI}, {@code PGMNAMEI},
 * {@code TITLE02I}, {@code CURTIMEI}) and the error-message leaf
 * ({@code ERRMSGI}) are not carried on the input side: they are populated
 * by the program before SEND-MAP, not by the operator on RECEIVE-MAP, and
 * are represented on the {@link CoUsr01Output} record instead (AAP
 * &sect;0.4.1 field-for-field DTO mandate).
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/COUSR01.bms}
 *       (mapset {@code COUSR01}, map {@code COUSR1A}, size 24x80,
 *       {@code CTRL=(ALARM,FREEKB)}, {@code EXTATT=YES}). The five
 *       editable input fields are declared at:
 *       <ul>
 *         <li>line 84: {@code FNAME   DFHMDF ATTRB=(FSET,IC,NORM,UNPROT)} LENGTH=20</li>
 *         <li>line 97: {@code LNAME   DFHMDF ATTRB=(FSET,NORM,UNPROT)} LENGTH=20</li>
 *         <li>line 111: {@code USERID  DFHMDF ATTRB=(FSET,NORM,UNPROT)} LENGTH=8</li>
 *         <li>line 126: {@code PASSWD  DFHMDF ATTRB=(DRK,FSET,UNPROT)} LENGTH=8 (non-display)</li>
 *         <li>line 141: {@code USRTYPE DFHMDF ATTRB=(FSET,NORM,UNPROT)} LENGTH=1</li>
 *       </ul>
 *       Function-key footer at line 159:
 *       {@code ENTER=Add User  F3=Back  F4=Clear  F12=Exit}.</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COUSR01.CPY}
 *       (group {@code 01 COUSR1AI}, lines 17&ndash;90). The five named
 *       PIC X input leaves carrying operator data are
 *       {@code FNAMEI PIC X(20)}, {@code LNAMEI PIC X(20)},
 *       {@code USERIDI PIC X(8)}, {@code PASSWDI PIC X(8)},
 *       {@code USRTYPEI PIC X(1)}.</li>
 *   <li>Translated COBOL program: {@code app/cbl/COUSR01C.cbl}
 *       ({@code PROGRAM-ID COUSR01C}, {@code WS-TRANID 'CU01'},
 *       {@code WS-USRSEC-FILE 'USRSEC  '}). The {@code EVALUATE EIBAID}
 *       block at lines 90&ndash;103 enumerates the AID keys handled by
 *       the program: {@code DFHENTER} (add user), {@code DFHPF3} (back to
 *       admin menu), {@code DFHPF4} (clear), and {@code OTHER} (invalid
 *       key error). The BMS footer additionally advertises {@code F12=Exit}
 *       which the COBOL routes through {@code WHEN OTHER}; the
 *       {@link AidKey} enum below exposes a dedicated
 *       {@link AidKey#PF12_EXIT} constant so the translated controller can
 *       implement the user-visible F12 affordance without resorting to
 *       the catch-all branch.</li>
 *   <li>USRSEC layout: {@code app/cpy/CSUSR01Y.cpy} (group
 *       {@code 01 SEC-USER-DATA}). The five operator-supplied input
 *       fields map one-to-one onto the corresponding {@code SEC-USR-*}
 *       fields on a successful add, as performed by
 *       {@code app/cbl/COUSR01C.cbl} lines 154&ndash;158:
 *       {@code MOVE USERIDI / FNAMEI / LNAMEI / PASSWDI / USRTYPEI ... TO
 *       SEC-USR-ID / SEC-USR-FNAME / SEC-USR-LNAME / SEC-USR-PWD /
 *       SEC-USR-TYPE}.</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (input side)</h2>
 * <p>Per AAP &sect;0.3.5 and &sect;0.4.1, BMS maps are translated into
 * <em>entry-contract DTO records</em> on the corresponding application
 * class. This record is the Java analog of the input view of the BMS
 * symbolic structure ({@code 01 COUSR1AI} in {@code COUSR01.CPY}): it
 * carries the field values supplied by the operator and received via
 * {@code EXEC CICS RECEIVE MAP}. The {@link AidKey} component captures
 * the CICS attention identifier ({@code EIBAID}) that triggered the
 * receive, allowing the controller to dispatch on it without re-deriving
 * it from a raw byte value.
 *
 * <p>There is no web framework, no Spring binding, no Jakarta Bean
 * Validation, no view templating engine; the record is a plain Java
 * carrier (AAP &sect;0.7.4).
 *
 * <h2>Add-user-screen semantics</h2>
 * <p>COUSR01 is the <strong>add</strong> screen for security users
 * (transaction {@code CU01}). All five editable fields ({@code FNAME},
 * {@code LNAME}, {@code USERID}, {@code PASSWD}, {@code USRTYPE}) are
 * unprotected on initial render. The operator fills them in and presses
 * ENTER to validate; on success the program performs
 * {@code EXEC CICS WRITE} of a {@code SEC-USER-DATA} record to the
 * {@code USRSEC} file (see {@code app/cbl/COUSR01C.cbl} lines 238&ndash;248).
 * The function keys are: {@code F3=Back} (return to admin menu),
 * {@code F4=Clear} (reset the form), {@code F12=Exit} (return to admin
 * menu without saving).
 *
 * <h2>Password handling &mdash; DRK attribute and plaintext preservation</h2>
 * <p>The {@code PASSWD} field is declared {@code ATTRB=(DRK,FSET,UNPROT)}
 * at {@code app/bms/COUSR01.bms} line 126 &mdash; <em>editable but
 * non-display</em>. The 3270 terminal does not echo the keystrokes back
 * to the operator; the wire-format bytes still travel from terminal to
 * application with the cleartext value, however, and arrive at the
 * application as plain PIC X(8) characters. The {@link #password()}
 * accessor on this record carries the cleartext value, exactly as the
 * COBOL program receives it at line 157
 * ({@code MOVE PASSWDI OF COUSR1AI TO SEC-USR-PWD}).
 *
 * <p>Per AAP &sect;0.1.3 the plaintext password is preserved at the
 * storage layer ({@code SEC-USR-PWD PIC X(08)} in
 * {@code app/cpy/CSUSR01Y.cpy} line 21) and at the DTO transport layer
 * for behavioral parity with the COBOL baseline. <strong>Any move to
 * BCrypt / Argon2 hashing is a separate effort that would constitute a
 * behavior change beyond migration scope</strong> and is flagged for
 * follow-up in {@code java/MIGRATION_NOTES.md}.
 *
 * <h2>NEVER log the password &mdash; toString() override</h2>
 * <p>Java records auto-generate a {@link #toString()} that prints every
 * component value, which would leak the cleartext password if used in a
 * log statement, exception message, debugger snapshot, or any
 * {@code String.format("%s", input)} expression. To avoid that leak path,
 * this record overrides {@link #toString()} and substitutes the fixed
 * mask {@code "********"} for the password component while preserving the
 * other field values verbatim. The override is part of the security
 * contract for this DTO; <strong>do not remove it</strong>. This mirrors
 * the {@code CoUsr01Output#toString()} override on the output-side DTO and
 * applies the same "no credential in logs" rule that AAP &sect;0.1.3
 * mandates for card PAN values, extended to passwords by parity (AAP
 * &sect;0.7.2 security note).
 *
 * <h2>Null and emptiness semantics &mdash; COBOL parity</h2>
 * <p>In COBOL, an unset BMS {@code PIC X(n)} input field arrives as
 * SPACES or LOW-VALUES on RECEIVE-MAP, never null (no null pointer exists
 * in COBOL). To preserve that behavior precisely, the compact constructor
 * below replaces every {@code null} {@link String} component with the
 * empty {@link String} <code>""</code>. Downstream consumers can safely
 * treat every {@link String} component as a non-null value without first
 * checking for null. The compact constructor uses <strong>JEP 513
 * Flexible Constructor Bodies</strong> (finalized in Java 25):
 * normalization runs before the canonical field bindings.
 *
 * <h2>PIC X(n) length normalization &mdash; clamp not throw</h2>
 * <p>Unlike {@link CoUsr01Output}, which throws
 * {@link IllegalArgumentException} on PIC-X overflow to surface
 * application-side bugs at the SEND-MAP boundary, this <em>input</em>
 * record <strong>silently clamps</strong> over-length values to the
 * declared BMS width. The rationale: the 3270 terminal already enforces
 * field widths at the hardware level, so an over-length input value in
 * Java code can only originate from a programmatic test harness or a
 * defective adapter &mdash; not from a real RECEIVE-MAP. Clamping at the
 * DTO boundary preserves field-for-field parity with the COBOL
 * {@code MOVE} semantics (which truncates to the receiving field width)
 * and prevents a downstream side-channel where a fuzz-test could crash
 * the application via a long user-id, an over-long password, or any
 * other oversized input.
 *
 * <p>The clamp operation is byte-safe because all five input fields are
 * pure ASCII characters per the BMS source: the COUSR01 map uses
 * {@code COLOR=GREEN} display on the unprotected fields and accepts only
 * the standard 3270 input character set. No multi-byte character escape
 * is involved.
 *
 * <h2>Default {@link AidKey}</h2>
 * <p>A {@code null} {@link AidKey} component is coerced to
 * {@link AidKey#ENTER}, which is the default action in {@code COUSR01C}
 * (the {@code EVALUATE EIBAID} block at lines 90&ndash;103 of
 * {@code app/cbl/COUSR01C.cbl} dispatches {@code DFHENTER} to
 * {@code PROCESS-ENTER-KEY}, which validates each editable field and on
 * success writes a new {@code SEC-USER-DATA} record). This mirrors the
 * COBOL default behavior on first entry to the program after an
 * {@code XCTL} (where {@code EIBAID} is initialized to ENTER by CICS).
 *
 * <h2>Immutability</h2>
 * <p>Because this is a record, all components are {@code final} and
 * accessors are automatically generated; there are no setters, no Lombok
 * ({@code @ToString} would not be able to selectively mask the password
 * field anyway), no Spring annotations, no Jakarta validation annotations
 * (AAP &sect;0.7.4). The instance is safely shareable across virtual
 * threads (AAP &sect;0.6.6) without synchronization.
 *
 * <h2>Field-by-field mapping</h2>
 * <p>Mapping from BMS symbolic copybook {@code COUSR1AI} (input view) to
 * Java record components. The PIC column shows the COBOL PICTURE clause
 * preserved verbatim at the DTO boundary.
 * <ul>
 *   <li>{@code FNAMEI}   &mdash; PIC X(20) &mdash; {@link #firstName()} &mdash; first name (UNPROT,IC,GREEN initially editable; cursor target on initial entry per {@code MOVE -1 TO FNAMEL OF COUSR1AI} at {@code COUSR01C.cbl} line 86)</li>
 *   <li>{@code LNAMEI}   &mdash; PIC X(20) &mdash; {@link #lastName()}  &mdash; last name (UNPROT,GREEN editable)</li>
 *   <li>{@code USERIDI}  &mdash; PIC X(8)  &mdash; {@link #userId()}    &mdash; user id (UNPROT,GREEN editable, 8 chars)</li>
 *   <li>{@code PASSWDI}  &mdash; PIC X(8)  &mdash; {@link #password()}  &mdash; password (UNPROT,DRK non-display but editable); masked in {@link #toString()}</li>
 *   <li>{@code USRTYPEI} &mdash; PIC X(1)  &mdash; {@link #userType()}  &mdash; user type {@code 'A'}=Admin, {@code 'U'}=User (UNPROT,GREEN)</li>
 *   <li>{@link AidKey}   &mdash; (not a BMS leaf) &mdash; {@link #aidKey()} &mdash; CICS attention identifier; replaces the COBOL {@code EVALUATE EIBAID ... WHEN DFHxxx} dispatch with a typed enum. The controller uses a pattern-matching switch on this value to invoke the corresponding action ({@code PROCESS-ENTER-KEY} / back / clear / exit / invalid).</li>
 * </ul>
 *
 * @param firstName first name, PIC X(20) ({@code FNAMEI}; UNPROT, IC,
 *                  GREEN; first editable field on the screen)
 * @param lastName  last name, PIC X(20) ({@code LNAMEI}; UNPROT, GREEN)
 * @param userId    user id, PIC X(8) ({@code USERIDI}; UNPROT, GREEN;
 *                  key into the {@code USRSEC} file)
 * @param password  password cleartext, PIC X(8) ({@code PASSWDI};
 *                  UNPROT, DRK non-display); masked as {@code "********"}
 *                  in {@link #toString()} per AAP &sect;0.1.3
 * @param userType  user type, PIC X(1) ({@code USRTYPEI}; UNPROT, GREEN;
 *                  {@code 'A'}=Admin, {@code 'U'}=User)
 * @param aidKey    CICS attention identifier; replaces COBOL
 *                  {@code EIBAID}; never {@code null} after construction
 *
 * @see com.blitzy.carddemo.application.user.CoUsr01C
 * @see com.blitzy.carddemo.application.user.CoUsr01Output
 */
public record CoUsr01Input(
        String firstName,    // FNAMEI    PIC X(20) — UNPROT,IC,GREEN  (first editable field)
        String lastName,     // LNAMEI    PIC X(20) — UNPROT,GREEN
        String userId,       // USERIDI   PIC X(8)  — UNPROT,GREEN     (USRSEC key)
        String password,     // PASSWDI   PIC X(8)  — UNPROT,DRK       (plaintext per AAP §0.1.3)
        String userType,     // USRTYPEI  PIC X(1)  — UNPROT,GREEN     ('A'=Admin / 'U'=User)
        AidKey aidKey
) {

    /**
     * Compact (canonical) constructor enforcing COBOL
     * &ldquo;SPACES by default&rdquo; semantics on every {@link String}
     * component and clamping over-length values to their declared BMS
     * {@code PIC X(n)} widths.
     *
     * <p>Each {@code null} {@link String} input is coerced to the empty
     * {@link String} <code>""</code>; each over-length value is truncated
     * to its declared width (see class Javadoc &sect;&ldquo;PIC X(n)
     * length normalization&rdquo; for rationale). A {@code null}
     * {@link AidKey} is coerced to {@link AidKey#ENTER}, mirroring the
     * COBOL default behavior on first program entry.
     *
     * <p>This is a textbook application of <strong>JEP 513 Flexible
     * Constructor Bodies</strong> (finalized in Java 25): normalization
     * runs before the canonical field bindings.
     */
    public CoUsr01Input {
        firstName = clamp(orEmpty(firstName), 20);  // FNAMEI    PIC X(20)
        lastName  = clamp(orEmpty(lastName),  20);  // LNAMEI    PIC X(20)
        userId    = clamp(orEmpty(userId),     8);  // USERIDI   PIC X(8)
        password  = clamp(orEmpty(password),   8);  // PASSWDI   PIC X(8)
        userType  = clamp(orEmpty(userType),   1);  // USRTYPEI  PIC X(1)
        if (aidKey == null) {
            aidKey = AidKey.ENTER;
        }
    }

    /**
     * AID-key alias enum scoped to this Input record.
     *
     * <p>Translates the {@code EVALUATE EIBAID} dispatch block at lines
     * 90&ndash;103 of {@code app/cbl/COUSR01C.cbl} into a typed enum that
     * the controller's pattern-matching switch can consume exhaustively.
     * Each constant maps one-to-one to a COBOL {@code DFHxxx} constant
     * from {@code COPY DFHAID} (included at line 55 of
     * {@code COUSR01C.cbl}):
     *
     * <ul>
     *   <li>{@link #ENTER}      &mdash; {@code DFHENTER}: validate the
     *       editable fields and, on success, write a new
     *       {@code SEC-USER-DATA} record to the {@code USRSEC} file
     *       (paragraph {@code PROCESS-ENTER-KEY} in
     *       {@code COUSR01C.cbl}).</li>
     *   <li>{@link #PF03_BACK}  &mdash; {@code DFHPF3}: return to the
     *       admin menu ({@code COADM01C}) without saving (lines
     *       93&ndash;95 of {@code COUSR01C.cbl}).</li>
     *   <li>{@link #PF04_CLEAR} &mdash; {@code DFHPF4}: reset the form
     *       to its initial state (paragraph
     *       {@code CLEAR-CURRENT-SCREEN}).</li>
     *   <li>{@link #PF12_EXIT}  &mdash; {@code DFHPF12}: exit and return
     *       to the admin menu ({@code COADM01C}) without saving. The
     *       BMS footer at line 159 of {@code app/bms/COUSR01.bms}
     *       advertises this affordance; the COBOL program currently
     *       routes PF12 through the {@code WHEN OTHER} branch (i.e.
     *       displays the invalid-key error). The translated Java
     *       controller honors the advertised affordance via this
     *       dedicated constant rather than dispatching it through
     *       {@link #OTHER}.</li>
     *   <li>{@link #OTHER}      &mdash; {@code WHEN OTHER}: any other
     *       AID key; the controller surfaces the
     *       {@code CCDA-MSG-INVALID-KEY} system message (lines
     *       98&ndash;102 of {@code COUSR01C.cbl}).</li>
     * </ul>
     *
     * <p>The enum order mirrors the lexical order of the COBOL
     * {@code EVALUATE EIBAID} branches (with {@link #PF12_EXIT} inserted
     * between {@link #PF04_CLEAR} and {@link #OTHER} to honor the BMS
     * footer's advertised affordance); downstream pattern-matching
     * switches should rely on exhaustiveness checking and must not
     * include a {@code default} branch (AAP &sect;0.7.3 mandate &mdash;
     * &ldquo;no {@code default} branches that hide missing cases&rdquo;).
     */
    public enum AidKey {
        /** {@code DFHENTER}: validate and add the user. */
        ENTER,
        /** {@code DFHPF3}: return to the admin menu without saving. */
        PF03_BACK,
        /** {@code DFHPF4}: clear the form and re-render. */
        PF04_CLEAR,
        /** {@code DFHPF12}: exit to the admin menu without saving. */
        PF12_EXIT,
        /** {@code WHEN OTHER}: any unmapped AID key (invalid key error). */
        OTHER
    }

    /**
     * Factory returning a fully blank instance &mdash; every {@link String}
     * field is the empty {@link String} <code>""</code> and the AID key is
     * {@link AidKey#ENTER}.
     *
     * <p>Useful for {@code COUSR01C} initialization before any
     * RECEIVE-MAP has occurred, mirroring the COBOL state after
     * {@code MOVE LOW-VALUES TO COUSR1AO} on initial entry to the
     * program (see {@code app/cbl/COUSR01C.cbl} line 85).
     *
     * @return a {@code CoUsr01Input} with all five string fields set to
     *         <code>""</code> and {@link #aidKey()} = {@link AidKey#ENTER}
     */
    public static CoUsr01Input blank() {
        return new CoUsr01Input("", "", "", "", "", AidKey.ENTER);
    }

    /**
     * Returns a string representation of this record with the
     * {@link #password()} component <strong>masked</strong> as the fixed
     * literal {@code "********"}.
     *
     * <p>This override is part of the <strong>security contract</strong>
     * for this DTO and is mandatory per AAP &sect;0.1.3 (no credential
     * value in logs, error messages, debugger snapshots, or any
     * string-coerced representation). Java records normally auto-generate
     * a {@link #toString()} method that prints every component value
     * verbatim; calling {@code log.info("Input: {}", input)} on such an
     * auto-generated representation would leak the plaintext password
     * directly into the log stream. The override below substitutes the
     * fixed mask {@code "********"} (eight asterisks &mdash; matching the
     * PIC X(8) on-screen width of the field for visual consistency) for
     * the {@link #password()} value while preserving every other field
     * verbatim.
     *
     * <p>The eight-character mask is a deliberate UX choice: it matches
     * the field's BMS {@code LENGTH=8} declaration and the
     * {@code DRK}-attribute screen rendering, giving log readers a
     * recognizable visual cue that the value is a redacted password
     * without disclosing the actual length of the supplied secret (any
     * password of any length always renders as exactly eight asterisks,
     * preventing a side-channel length leak).
     *
     * <p>The output format mirrors the auto-generated record syntax
     * (record name in square brackets, components as
     * {@code name=value} pairs separated by {@code ", "}) so that any
     * existing log parser or human reader sees a familiar structure
     * &mdash; only the password value is substituted.
     *
     * @return a string of the form
     *         {@code "CoUsr01Input[firstName=..., lastName=..., userId=..., password=********, userType=..., aidKey=...]"}
     *         that never contains the cleartext password value
     */
    @Override
    public String toString() {
        return "CoUsr01Input["
                + "firstName=" + firstName
                + ", lastName=" + lastName
                + ", userId=" + userId
                + ", password=********"
                + ", userType=" + userType
                + ", aidKey=" + aidKey
                + "]";
    }

    /**
     * Null-coalescing helper. Returns the input {@link String} unchanged
     * if non-null, or the empty {@link String} <code>""</code> if null.
     * Preserves COBOL {@code MOVE LOW-VALUES} / SPACES-on-RECEIVE-MAP
     * behavior by guaranteeing every field-bearing component is
     * observable as a non-null {@link String}.
     *
     * @param s the input string, possibly {@code null}
     * @return {@code s} if non-null, otherwise the empty string
     *         <code>""</code>
     */
    private static String orEmpty(String s) {
        return (s == null) ? "" : s;
    }

    /**
     * Length-clamping helper. Returns the input {@link String} unchanged
     * if its length is &le; {@code maxLen}, otherwise returns the leading
     * {@code maxLen}-character prefix.
     *
     * <p>Preserves COBOL {@code MOVE} truncation semantics at the DTO
     * boundary (see class Javadoc &sect;&ldquo;PIC X(n) length
     * normalization&rdquo;): over-length input from a programmatic test
     * harness is silently truncated rather than throwing, matching the
     * behavior the 3270 hardware enforces on real RECEIVE-MAP traffic.
     *
     * @param s      the input string (never {@code null}: the caller
     *               guarantees normalization via
     *               {@link #orEmpty(String)})
     * @param maxLen the declared BMS {@code PIC X(n)} width
     * @return {@code s} if {@code s.length() <= maxLen}, otherwise
     *         {@code s.substring(0, maxLen)}
     */
    private static String clamp(String s, int maxLen) {
        return (s.length() > maxLen) ? s.substring(0, maxLen) : s;
    }
}
