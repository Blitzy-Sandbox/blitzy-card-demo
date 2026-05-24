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
// access to java.lang.String, the only application type referenced by the
// record components, plus java.lang.IllegalArgumentException (not used here
// since the input record clamps over-length inputs rather than rejecting).
// Replaces individual package imports per AAP §0.7.3 mandate.
import module java.base;

// AAP §0.7.1 traceability mandate: every translated artifact must cite its
// original COBOL source via the @CobolProgram annotation declared in the
// carddemo-domain module. carddemo-application declares carddemo-domain as a
// direct dependency in its pom.xml, so the annotation is on the classpath
// and resolvable here.
import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * BMS input record for the {@code COUSR03 / COUSR3A} delete-user map
 * (COBOL transaction {@code CU03}, program {@code COUSR03C}).
 *
 * <p>This record is a literal field-for-field projection of the input view
 * of the {@code 01 COUSR3AI} group in {@code app/cpy-bms/COUSR03.CPY}: one
 * {@link String} field per {@code "I"}-suffixed PIC X(n) BMS leaf that
 * carries application data (user-id, first name, last name, user type),
 * plus one {@link AidKey} discriminator capturing which CICS attention
 * identifier was pressed. The header-row leaves (TRNNAMEI / TITLE01I /
 * CURDATEI / PGMNAMEI / TITLE02I / CURTIMEI) and the error-message leaf
 * (ERRMSGI) are not carried on the input side: they are populated by the
 * program before SEND-MAP, not by the operator on RECEIVE-MAP, and are
 * represented on the {@code CoUsr03Output} record instead (AAP &sect;0.4.1
 * field-for-field DTO mandate).
 *
 * <h2>NO PASSWORD FIELD &mdash; AAP &sect;0.6.8 safety mandate</h2>
 * <p><strong>This record deliberately has no password component.</strong>
 * The COUSR03 BMS map and symbolic copybook do <em>not</em> include a
 * {@code PASSWD} field; the delete screen displays the user details for
 * <em>visual confirmation</em> only and never solicits or echoes a password
 * value. This is consistent with AAP &sect;0.6.8 (&ldquo;NO PASSWD field on
 * delete screen&rdquo;) and enforced at the BMS source level &mdash; no
 * field-level translation work introduces it. Because no credential is
 * carried, the auto-generated record {@link #toString()} is safe to use in
 * logs: it cannot leak any password. The deliberate absence of a
 * {@code toString()} override on this record is therefore part of the
 * security contract; <em>do not add one</em>.
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>BMS map definition: {@code app/bms/COUSR03.bms}
 *       (mapset {@code COUSR03}, map {@code COUSR3A}, size 24x80,
 *       {@code CTRL=(ALARM,FREEKB)}, {@code EXTATT=YES}). Function-key
 *       line at the bottom of the map (line 148):
 *       {@code ENTER=Fetch  F3=Back  F4=Clear  F5=Delete}.</li>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COUSR03.CPY}
 *       (group {@code 01 COUSR3AI}, lines 17-84). The named editable input
 *       leaf is {@code USRIDINI PIC X(8)}; the other three named PIC X
 *       input leaves ({@code FNAMEI}, {@code LNAMEI}, {@code USRTYPEI})
 *       are present in the COBOL input-half overlay even though the BMS
 *       attributes mark them {@code ASKIP,BLUE} read-only.</li>
 *   <li>Translated COBOL program: {@code app/cbl/COUSR03C.cbl}
 *       ({@code PROGRAM-ID COUSR03C}, {@code WS-TRANID 'CU03'},
 *       {@code WS-USRSEC-FILE 'USRSEC  '}). The {@code EVALUATE EIBAID}
 *       block at lines 108-130 enumerates the AID keys handled by the
 *       program: {@code DFHENTER} (fetch), {@code DFHPF3} (back),
 *       {@code DFHPF4} (clear), {@code DFHPF5} (delete), {@code DFHPF12}
 *       (cancel), and {@code OTHER} (invalid key error).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (input side)</h2>
 * <p>Per AAP &sect;0.3.5 and &sect;0.4.1, BMS maps are translated into
 * <em>entry-contract DTO records</em> on the corresponding application
 * class. This record is the Java analog of the input view of the BMS
 * symbolic structure ({@code 01 COUSR3AI} in {@code COUSR03.CPY}): it
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
 * <h2>Field-for-field translation</h2>
 * <ul>
 *   <li>{@code USRIDINI} &mdash; PIC X(8)  &mdash; {@link #userId()}     &mdash; user-id key (only editable field; {@code UNPROT,IC,GREEN} per {@code app/bms/COUSR03.bms} lines 85-89). On the first SEND-MAP after CICS XCTL into COUSR03C, this is the cursor-positioned field; the operator types the user-id of the user to be deleted and presses ENTER, which dispatches to {@code PROCESS-ENTER-KEY} to fetch the record. PF5 (delete) re-validates this field before committing the deletion.</li>
 *   <li>{@code FNAMEI}   &mdash; PIC X(20) &mdash; {@link #firstName()}  &mdash; first name (display only on the screen; {@code ASKIP,BLUE} per {@code app/bms/COUSR03.bms} lines 103-107). The operator cannot type into this field on the 3270 terminal; it is populated by the program after the fetch (see {@code COUSR03C.cbl} line 165). Carried on this record only because the symbolic-map input-half overlay includes it; expected to be empty or to echo back the previous SEND-MAP value on RECEIVE-MAP.</li>
 *   <li>{@code LNAMEI}   &mdash; PIC X(20) &mdash; {@link #lastName()}   &mdash; last name (display only; {@code ASKIP,BLUE} per {@code app/bms/COUSR03.bms} lines 116-120).</li>
 *   <li>{@code USRTYPEI} &mdash; PIC X(1)  &mdash; {@link #userType()}   &mdash; user type {@code 'A'}=Admin, {@code 'U'}=User (display only; {@code ASKIP,BLUE} per {@code app/bms/COUSR03.bms} lines 130-134).</li>
 *   <li>{@link AidKey}   &mdash; (not a BMS leaf) &mdash; {@link #aidKey()} &mdash; CICS attention identifier; replaces the COBOL {@code EVALUATE EIBAID ... WHEN DFHxxx} dispatch with a typed enum. The controller uses a pattern-matching switch on this value to invoke the corresponding action ({@code PROCESS-ENTER-KEY} / back / clear / delete / cancel / invalid).</li>
 * </ul>
 *
 * <h2>Null and emptiness semantics &mdash; COBOL parity</h2>
 * <p>In COBOL, an unset BMS {@code PIC X(n)} input field arrives as SPACES
 * or LOW-VALUES on RECEIVE-MAP, never null (no null pointer exists in
 * COBOL). To preserve that behavior precisely, the compact constructor
 * below replaces every {@code null} {@link String} component with the
 * empty {@link String} <code>""</code>. Downstream consumers can safely
 * treat every {@link String} component as a non-null value without first
 * checking for null. The compact constructor uses <strong>JEP 513
 * Flexible Constructor Bodies</strong> (finalized in Java 25):
 * normalization runs before the canonical field bindings.
 *
 * <h2>PIC X(n) length normalization &mdash; clamp not throw</h2>
 * <p>Unlike {@code CoUsr03Output}, which throws
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
 * the application via a long user-id. The clamp operation is byte-safe
 * because all four input fields are pure ASCII characters per the BMS
 * source.
 *
 * <h2>Default {@link AidKey}</h2>
 * <p>A {@code null} {@link AidKey} component is coerced to
 * {@link AidKey#ENTER}, which is the default action in {@code COUSR03C}
 * (the {@code EVALUATE EIBAID} block at lines 108-130 of
 * {@code app/cbl/COUSR03C.cbl} dispatches {@code DFHENTER} to
 * {@code PROCESS-ENTER-KEY}, which fetches the user record). This
 * mirrors the COBOL default behavior on first entry to the program after
 * an {@code XCTL} (where {@code EIBAID} is initialized to ENTER by CICS).
 *
 * <h2>Immutability</h2>
 * <p>Because this is a record, all components are {@code final} and
 * accessors are automatically generated; there are no setters, no Lombok,
 * no Spring annotations, no Jakarta validation annotations (AAP
 * &sect;0.7.4). The instance is safely shareable across virtual threads
 * (AAP &sect;0.6.6) without synchronization.
 *
 * @param userId    user-id key, PIC X(8) ({@code USRIDINI}; only editable
 *                  field)
 * @param firstName first name, PIC X(20) ({@code FNAMEI}; ASKIP, populated
 *                  by program after fetch)
 * @param lastName  last name, PIC X(20) ({@code LNAMEI}; ASKIP, populated
 *                  by program after fetch)
 * @param userType  user type, PIC X(1) ({@code USRTYPEI}; ASKIP, populated
 *                  by program after fetch; {@code 'A'}=Admin,
 *                  {@code 'U'}=User)
 * @param aidKey    CICS attention identifier; replaces COBOL
 *                  {@code EIBAID}; never {@code null} after construction
 *
 * @see com.blitzy.carddemo.application.user.CoUsr03C
 * @see com.blitzy.carddemo.application.user.CoUsr03Output
 */
@CobolProgram(
        value = "COUSR03",
        sourcePath = "app/bms/COUSR03.bms",
        translationDate = "2025-10-15",
        notes = "BMS entry-contract DTO (input side); symbolic copybook 01 COUSR3AI in "
                + "app/cpy-bms/COUSR03.CPY. Driven by online program "
                + "app/cbl/COUSR03C.cbl (transaction CU03 — delete user)."
)
public record CoUsr03Input(
        String userId,       // USRIDINI  PIC X(8)  — only editable field
        String firstName,    // FNAMEI    PIC X(20) — ASKIP, populated by program
        String lastName,     // LNAMEI    PIC X(20) — ASKIP, populated by program
        String userType,     // USRTYPEI  PIC X(1)  — ASKIP, populated by program
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
    public CoUsr03Input {
        userId    = clamp(orEmpty(userId),    8);   // USRIDINI PIC X(8)
        firstName = clamp(orEmpty(firstName), 20);  // FNAMEI   PIC X(20)
        lastName  = clamp(orEmpty(lastName),  20);  // LNAMEI   PIC X(20)
        userType  = clamp(orEmpty(userType),  1);   // USRTYPEI PIC X(1)
        if (aidKey == null) {
            aidKey = AidKey.ENTER;
        }
    }

    /**
     * AID-key alias enum scoped to this Input record.
     *
     * <p>Translates the {@code EVALUATE EIBAID} dispatch block at lines
     * 108-130 of {@code app/cbl/COUSR03C.cbl} into a typed enum that the
     * controller's pattern-matching switch can consume exhaustively.
     * Each constant maps one-to-one to a COBOL {@code DFHxxx} constant
     * from {@code COPY DFHAID} (included at line 67 of
     * {@code COUSR03C.cbl}):
     *
     * <ul>
     *   <li>{@link #ENTER}       &mdash; {@code DFHENTER}: fetch the user
     *       record identified by {@link #userId()} so the operator can
     *       confirm before deleting (paragraph {@code PROCESS-ENTER-KEY}
     *       in {@code COUSR03C.cbl}).</li>
     *   <li>{@link #PF03_BACK}   &mdash; {@code DFHPF3}: return to the
     *       previous screen (admin menu {@code COADM01C} if no
     *       {@code CDEMO-FROM-PROGRAM} is set, otherwise the
     *       referring program).</li>
     *   <li>{@link #PF04_CLEAR}  &mdash; {@code DFHPF4}: clear the
     *       current screen (paragraph
     *       {@code CLEAR-CURRENT-SCREEN}).</li>
     *   <li>{@link #PF05_DELETE} &mdash; {@code DFHPF5}: commit the
     *       deletion of the user identified by {@link #userId()}
     *       (paragraph {@code DELETE-USER-INFO}). This is the dangerous
     *       confirmation key; the application class double-checks that
     *       a fetch has occurred before allowing PF5 to actually
     *       execute the {@code DELETE}.</li>
     *   <li>{@link #PF12_CANCEL} &mdash; {@code DFHPF12}: cancel and
     *       return to the admin menu ({@code COADM01C}).</li>
     *   <li>{@link #OTHER}       &mdash; {@code WHEN OTHER}: any
     *       unmapped AID key; the controller surfaces the
     *       {@code CCDA-MSG-INVALID-KEY} system message.</li>
     * </ul>
     *
     * <p>The enum order mirrors the lexical order of the COBOL
     * {@code EVALUATE EIBAID} branches; downstream pattern-matching
     * switches should rely on exhaustiveness checking and must not
     * include a {@code default} branch (AAP &sect;0.7.3 mandate &mdash;
     * &ldquo;no {@code default} branches that hide missing cases&rdquo;).
     */
    public enum AidKey {
        /** {@code DFHENTER}: fetch the user record for delete confirmation. */
        ENTER,
        /** {@code DFHPF3}: return to the previous program. */
        PF03_BACK,
        /** {@code DFHPF4}: clear the current screen. */
        PF04_CLEAR,
        /** {@code DFHPF5}: commit the user deletion. */
        PF05_DELETE,
        /** {@code DFHPF12}: cancel and return to the admin menu. */
        PF12_CANCEL,
        /** {@code WHEN OTHER}: any unmapped AID key (invalid key error). */
        OTHER
    }

    /**
     * Factory returning a fully blank instance &mdash; every {@link String}
     * field is the empty {@link String} <code>""</code> and the AID key is
     * {@link AidKey#ENTER}.
     *
     * <p>Useful for {@code COUSR03C} initialization before any
     * RECEIVE-MAP has occurred, mirroring the COBOL state after
     * {@code MOVE LOW-VALUES TO COUSR3AO} on initial entry to the
     * program (see {@code app/cbl/COUSR03C.cbl} line 97).
     *
     * @return a {@code CoUsr03Input} with all four string fields set to
     *         <code>""</code> and {@link #aidKey()} = {@link AidKey#ENTER}
     */
    public static CoUsr03Input blank() {
        return new CoUsr03Input("", "", "", "", AidKey.ENTER);
    }

    /**
     * Returns a copy of this record with a different {@code userId}.
     *
     * <p>Records in finalized Java 25 do not have built-in {@code with}
     * syntax (the {@code with} expression is still a preview feature);
     * this hand-written copy method preserves immutability while
     * providing a fluent way to derive related instances. All other
     * components are carried through unchanged.
     *
     * <p>This method captures the COBOL pattern at lines 99-104 of
     * {@code app/cbl/COUSR03C.cbl} where the program pre-populates the
     * user-id field from a previously selected list entry:
     * {@code MOVE CDEMO-CU03-USR-SELECTED TO USRIDINI OF COUSR3AI}.
     * Composed with {@link #blank()} (i.e.
     * {@code CoUsr03Input.blank().withUserId("u01")}) it also serves as
     * the canonical way to construct a fresh input with only the
     * user-id populated.
     *
     * @param newUserId the new user id (may be {@code null}; coerced to
     *                  <code>""</code> and clamped to PIC X(8) by the
     *                  compact constructor of the returned record)
     * @return a new {@code CoUsr03Input} identical to {@code this} except
     *         {@link #userId()} is replaced with {@code newUserId}
     */
    public CoUsr03Input withUserId(String newUserId) {
        return new CoUsr03Input(newUserId, this.firstName, this.lastName, this.userType, this.aidKey);
    }

    /**
     * Returns a copy of this record with a different {@link AidKey}.
     *
     * <p>Used by test fixtures and by the controller's RECEIVE-MAP layer
     * to attach the dispatched AID key to an already-parsed input record.
     * All other components are carried through unchanged.
     *
     * @param newAidKey the new AID key (may be {@code null}; coerced to
     *                  {@link AidKey#ENTER} by the compact constructor of
     *                  the returned record)
     * @return a new {@code CoUsr03Input} identical to {@code this} except
     *         {@link #aidKey()} is replaced with {@code newAidKey}
     */
    public CoUsr03Input withAidKey(AidKey newAidKey) {
        return new CoUsr03Input(this.userId, this.firstName, this.lastName, this.userType, newAidKey);
    }

    // Note: NO toString() override is provided.
    //
    // This record has no password component (see §"NO PASSWORD FIELD" in
    // the class Javadoc) and no other sensitive component. The
    // auto-generated record toString() therefore cannot leak any
    // credential value and is safe to use in logs. Do NOT add a
    // toString() override — its absence is part of the security contract
    // for this DTO. (Distinct from CoUsr01Input / CoUsr02Input, which
    // carry a password component and override toString() to mask it.)

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
