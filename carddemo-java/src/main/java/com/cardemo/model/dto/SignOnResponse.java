package com.cardemo.model.dto;

import com.cardemo.model.enums.UserType;
import jakarta.validation.constraints.Size;
import java.util.Objects;

/**
 * REST response payload returned on a successful CardDemo
 * <strong>Sign-On</strong>.
 *
 * <p>This Data Transfer Object is the Java 25 / Spring Boot 3.x replacement for
 * the work the legacy online program {@code app/cbl/COSGN00C.cbl} performed at
 * the <em>end</em> of a successful sign-on. After verifying the entered
 * credentials against the {@code USRSEC} VSAM file, {@code COSGN00C} populated
 * the routing/identity fields of the communication area
 * {@code 01 CARDDEMO-COMMAREA} ({@code app/cpy/COCOM01Y.cpy}) and then issued
 * {@code EXEC CICS XCTL} to transfer control to the correct menu program:
 * administrators ({@code CDEMO-USRTYP-ADMIN}, type {@code 'A'}) were routed to
 * the admin menu ({@code COADM01C}, transaction {@code CA00}); regular users
 * ({@code CDEMO-USRTYP-USER}, type {@code 'U'}) were routed to the main menu
 * ({@code COMEN01C}, transaction {@code CM00}).</p>
 *
 * <p>In the migrated system this DTO is the body returned by
 * {@code controller/AuthController} from {@code POST /api/auth/signin}. The
 * upstream {@code service/auth/AuthenticationService} (the translation of
 * {@code COSGN00C}) performs the {@code USRSEC} read, the BCrypt verification
 * and issues the session token; on success it builds and returns this object so
 * the caller learns <em>who</em> signed in ({@link #userId}/{@link #userType})
 * and <em>where</em> the legacy program would have transferred control next
 * ({@link #toTranId}/{@link #toProgram}). It is a pure boundary type &mdash; it
 * carries no business logic, no I/O and no static mutable state; the
 * {@code token} value is produced by the service, this DTO only conveys it.</p>
 *
 * <h2>Technology substitution &mdash; CICS COMMAREA &rarr; stateless REST + JWT (AAP &sect;0.1.2, &sect;0.7.1)</h2>
 * <p>On the mainframe the authenticated identity and the next-screen routing
 * were threaded forward by re-populating {@code CARDDEMO-COMMAREA} and relying
 * on the CICS pseudo-conversational idiom ({@code RETURN TRANSID COMMAREA} /
 * {@code XCTL}). The migrated application is stateless, so that carried control
 * block is replaced by <strong>token-based state</strong>: this response adds a
 * {@link #token} (a JWT) that the client presents on subsequent requests. This
 * single addition is the only modernization applied here; per the deterministic
 * rule {@code CICS RETURN TRANSID COMMAREA -> stateless REST with context
 * propagation} and the Minimal Change Clause (AAP &sect;0.7.1) no business field
 * is added, removed or reinterpreted &mdash; every other field is a faithful
 * projection of the {@code CARDDEMO-COMMAREA} fields {@code COSGN00C} populated
 * before its {@code XCTL}.</p>
 *
 * <h2>Original COBOL fields projected (app/cpy/COCOM01Y.cpy &mdash; populated by COSGN00C)</h2>
 * <pre>{@code
 * 01 CARDDEMO-COMMAREA.
 *    05 CDEMO-GENERAL-INFO.
 *       10 CDEMO-TO-TRANID    PIC X(04).   <-- toTranId   (CA00 admin / CM00 user)
 *       10 CDEMO-TO-PROGRAM   PIC X(08).   <-- toProgram  (COADM01C / COMEN01C)
 *       10 CDEMO-USER-ID      PIC X(08).   <-- userId
 *       10 CDEMO-USER-TYPE    PIC X(01).   <-- userType   (88 'A'=ADMIN / 'U'=USER)
 * }</pre>
 *
 * <h2>Technology-substitution notes (Minimal Change Clause &mdash; AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>{@link #token} &mdash; the one modern addition.</strong> A JWT
 *       string standing in for the CICS COMMAREA carry-over. It has no COBOL
 *       source field and therefore no {@code PIC} width to preserve, so it
 *       carries no {@link Size @Size} constraint. Its value is minted by
 *       {@code AuthenticationService}.</li>
 *   <li><strong>Byte-faithful field lengths (AAP &sect;0.7.2).</strong> Each
 *       projected textual field carries a {@link Size @Size(max = n)} equal to
 *       its COBOL {@code PIC X(n)} width, preserving the external-interface width
 *       exactly: {@code userId} {@code X(08)}, {@code toTranId} {@code X(04)},
 *       {@code toProgram} {@code X(08)}.</li>
 *   <li><strong>{@code CDEMO-USER-TYPE} &rarr; {@link UserType} enum.</strong>
 *       The 1-byte {@code PIC X(01)} field with condition names
 *       {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} and
 *       {@code 88 CDEMO-USRTYP-USER VALUE 'U'} maps to the shared
 *       {@link UserType} enum (constants {@code ADMIN('A')} / {@code USER('U')})
 *       rather than a raw {@code String}, giving type-safe role routing while
 *       preserving the byte-exact 'A'/'U' codes via {@link UserType#getCode()}.
 *       By default Jackson serializes it as the enum constant name
 *       ({@code "ADMIN"} / {@code "USER"}).</li>
 *   <li><strong>No floating point (AAP &sect;0.7.3).</strong> The sign-on
 *       response carries no monetary or decimal data, so there is no
 *       {@code float}, {@code double} or {@link java.math.BigDecimal} anywhere
 *       in this DTO.</li>
 * </ul>
 *
 * <h2>Deliberately excluded</h2>
 * <ul>
 *   <li><strong>Password.</strong> The credential is never echoed back; there is
 *       no password field on this response (it is accepted only on the inbound
 *       {@code SignOnRequest}).</li>
 *   <li><strong>AID / PF keys.</strong> The CICS {@code DFHAID} attention
 *       identifiers have no place in a REST response body; each AID maps to a
 *       distinct REST endpoint instead (AAP &sect;0.4.2).</li>
 *   <li><strong>Derived {@code menu} indicator.</strong> The agent specification
 *       lists an optional convenience string ({@code "ADMIN"} / {@code "MAIN"})
 *       derived from {@link #userType}. It is intentionally omitted: it would be
 *       wholly redundant with the already-present {@link #userType},
 *       {@link #toTranId} and {@link #toProgram}, and the Minimal Change Clause
 *       (AAP &sect;0.7.1) directs that no field be added beyond what the
 *       migration requires. Callers derive the destination directly from
 *       {@link #userType} (ADMIN &rarr; admin menu, USER &rarr; main menu) or
 *       from the explicit {@link #toTranId}/{@link #toProgram} routing target.</li>
 * </ul>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL source is read-only reference material
 * and is never copied into this repository.</p>
 *
 * @see com.cardemo.model.enums.UserType
 * @see com.cardemo.model.dto.SignOnRequest
 * @see com.cardemo.model.dto.CommArea
 * @see jakarta.validation.constraints.Size
 */
public class SignOnResponse {

    /**
     * The session token (JWT) issued on a successful sign-on.
     *
     * <p>This is the single modernization introduced by this response. It has no
     * COBOL source field: it replaces the CICS pseudo-conversational
     * {@code CARDDEMO-COMMAREA} carry-over (and the {@code RETURN TRANSID
     * COMMAREA} idiom) with token-based state that the client presents on
     * subsequent requests. Because it is server-produced and has no fixed
     * {@code PIC} width, it carries no length constraint. The value is minted by
     * {@code AuthenticationService}; this DTO only conveys it.</p>
     */
    // COBOL substitution: the CICS pseudo-conversational COMMAREA carry-over
    //  (RETURN TRANSID COMMAREA / XCTL state threading) -> a JWT token. This is
    //  the only modernization added on the response (AAP §0.1.2 / §0.7.1); it has
    //  no COCOM01Y source field and therefore no @Size width to preserve. It is
    //  serialized normally so the client receives it, but masked in toString().
    private String token;

    /**
     * The signed-in user id.
     *
     * <p>Migrated from {@code CDEMO-USER-ID PIC X(08)} &mdash; the 8-character
     * {@code USRSEC} user id that {@code COSGN00C} established at sign-on. The
     * {@link Size @Size(max = 8)} constraint preserves the original field width
     * byte-for-byte (AAP &sect;0.7.2).</p>
     */
    // CDEMO-USER-ID PIC X(08) -> fixed-length 8-char USRSEC user id -> String (max length 8)
    @Size(max = 8, message = "User ID must not exceed 8 characters")
    private String userId;

    /**
     * The signed-in user's role (type).
     *
     * <p>Migrated from {@code CDEMO-USER-TYPE PIC X(01)} with condition names
     * {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} and
     * {@code 88 CDEMO-USRTYP-USER VALUE 'U'}. {@code COSGN00C} branched on these
     * 88-levels to route administrators to the admin menu ({@code COADM01C}) and
     * regular users to the main menu ({@code COMEN01C}); the same decision is
     * conveyed here type-safely.</p>
     */
    // COBOL substitution: CDEMO-USER-TYPE PIC X(01) (88 'A'=ADMIN / 'U'=USER)
    //  -> type-safe com.cardemo.model.enums.UserType enum (NOT a raw String / local enum).
    private UserType userType;

    /**
     * The post-login routing target transaction id.
     *
     * <p>Migrated from {@code CDEMO-TO-TRANID PIC X(04)} &mdash; the 4-character
     * CICS transaction id {@code COSGN00C} set before its {@code XCTL}:
     * {@code CA00} for administrators (admin menu) or {@code CM00} for regular
     * users (main menu). The {@link Size @Size(max = 4)} constraint preserves the
     * original field width.</p>
     */
    // CDEMO-TO-TRANID PIC X(04) -> 4-char target CICS tranid (CA00 / CM00) -> String (max length 4)
    @Size(max = 4, message = "To-transaction id must not exceed 4 characters")
    private String toTranId;

    /**
     * The post-login routing target program name.
     *
     * <p>Migrated from {@code CDEMO-TO-PROGRAM PIC X(08)} &mdash; the 8-character
     * name of the COBOL program {@code COSGN00C} transferred control to via
     * {@code XCTL}: {@code COADM01C} for administrators or {@code COMEN01C} for
     * regular users. The {@link Size @Size(max = 8)} constraint preserves the
     * original field width.</p>
     */
    // CDEMO-TO-PROGRAM PIC X(08) -> 8-char target program (COADM01C / COMEN01C) -> String (max length 8)
    @Size(max = 8, message = "To-program name must not exceed 8 characters")
    private String toProgram;

    /**
     * Default no-argument constructor required by the JSON binder (Jackson)
     * to instantiate this DTO reflectively before populating its properties.
     */
    public SignOnResponse() {
        // Intentionally empty: Jackson instantiates then sets fields via setters.
    }

    /**
     * Constructs a fully-populated sign-on response.
     *
     * <p>Parameter order places the modern session {@code token} first, then the
     * signed-in identity ({@code userId}, {@code userType}), then the routing
     * target {@code COSGN00C} would have transferred to
     * ({@code toTranId}, {@code toProgram}).</p>
     *
     * @param token     the issued session token (JWT); never echoed in
     *                  {@link #toString()}
     * @param userId    the signed-in user id ({@code CDEMO-USER-ID}); up to 8
     *                  characters
     * @param userType  the signed-in user's role ({@code CDEMO-USER-TYPE})
     * @param toTranId  the routing target transaction id
     *                  ({@code CDEMO-TO-TRANID}); up to 4 characters
     * @param toProgram the routing target program name
     *                  ({@code CDEMO-TO-PROGRAM}); up to 8 characters
     */
    public SignOnResponse(String token, String userId, UserType userType,
                          String toTranId, String toProgram) {
        this.token = token;
        this.userId = userId;
        this.userType = userType;
        this.toTranId = toTranId;
        this.toProgram = toProgram;
    }

    /**
     * Returns the issued session token (JWT).
     *
     * @return the token, or {@code null} if unset
     */
    public String getToken() {
        return token;
    }

    /**
     * Sets the issued session token (JWT).
     *
     * @param token the token to set
     */
    public void setToken(String token) {
        this.token = token;
    }

    /**
     * Returns the signed-in user id ({@code CDEMO-USER-ID}).
     *
     * @return the user id, or {@code null} if unset
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Sets the signed-in user id ({@code CDEMO-USER-ID}).
     *
     * @param userId the user id to set (up to 8 characters)
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * Returns the signed-in user's role ({@code CDEMO-USER-TYPE}).
     *
     * @return the {@link UserType}, or {@code null} if unset
     */
    public UserType getUserType() {
        return userType;
    }

    /**
     * Sets the signed-in user's role ({@code CDEMO-USER-TYPE}).
     *
     * @param userType the {@link UserType} to set
     */
    public void setUserType(UserType userType) {
        this.userType = userType;
    }

    /**
     * Returns the post-login routing target transaction id
     * ({@code CDEMO-TO-TRANID}).
     *
     * @return the target transaction id, or {@code null} if unset
     */
    public String getToTranId() {
        return toTranId;
    }

    /**
     * Sets the post-login routing target transaction id
     * ({@code CDEMO-TO-TRANID}).
     *
     * @param toTranId the target transaction id to set (up to 4 characters)
     */
    public void setToTranId(String toTranId) {
        this.toTranId = toTranId;
    }

    /**
     * Returns the post-login routing target program name
     * ({@code CDEMO-TO-PROGRAM}).
     *
     * @return the target program name, or {@code null} if unset
     */
    public String getToProgram() {
        return toProgram;
    }

    /**
     * Sets the post-login routing target program name
     * ({@code CDEMO-TO-PROGRAM}).
     *
     * @param toProgram the target program name to set (up to 8 characters)
     */
    public void setToProgram(String toProgram) {
        this.toProgram = toProgram;
    }

    /**
     * Value-based equality across all response fields.
     *
     * <p>Two instances are equal only when {@code o} is exactly a
     * {@code SignOnResponse} and every carried field matches. The string fields
     * use {@link Objects#equals(Object, Object)} (null-safe) and the
     * {@link #userType} enum is compared by reference identity, which is the
     * correct value comparison for enum constants.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is an equal sign-on response
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SignOnResponse that = (SignOnResponse) o;
        return Objects.equals(token, that.token)
                && Objects.equals(userId, that.userId)
                && userType == that.userType
                && Objects.equals(toTranId, that.toTranId)
                && Objects.equals(toProgram, that.toProgram);
    }

    /**
     * Hash code derived from all response fields, consistent with
     * {@link #equals(Object)}.
     *
     * @return the hash code for this response
     */
    @Override
    public int hashCode() {
        return Objects.hash(token, userId, userType, toTranId, toProgram);
    }

    /**
     * Diagnostic representation that includes the identity and routing fields but
     * <strong>never</strong> the raw token value.
     *
     * <p>The token is a bearer credential, so it is masked: only its presence
     * ({@code "****"}) or absence ({@code "null"}) is shown, never its content.
     * This mirrors the security-conscious {@code toString} convention used
     * elsewhere in the model layer (for example the masked password on
     * {@code SignOnRequest} and the masked PAN on {@code CommArea}) and prevents
     * the token from leaking through logs or diagnostics.</p>
     *
     * @return a human-readable, token-safe description of this response
     */
    @Override
    public String toString() {
        return "SignOnResponse{"
                + "token=" + (token == null ? "null" : "****")
                + ", userId='" + userId + '\''
                + ", userType=" + userType
                + ", toTranId='" + toTranId + '\''
                + ", toProgram='" + toProgram + '\''
                + '}';
    }
}
