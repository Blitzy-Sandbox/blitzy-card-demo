package com.cardemo.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Objects;

/**
 * REST request payload for the CardDemo <strong>Sign-On</strong> screen.
 *
 * <p>This Data Transfer Object is the Java 25 / Spring Boot 3.x replacement for the
 * two <em>input</em> fields that the legacy 3270 sign-on map received. On the
 * mainframe the sign-on screen was BMS map {@code COSGN0A} (mapset {@code COSGN00},
 * online transaction {@code CC00}); its symbolic map is defined by the copybook
 * {@code app/cpy-bms/COSGN00.CPY}. The online program {@code app/cbl/COSGN00C.cbl}
 * issued {@code RECEIVE MAP('COSGN0A')} to read the operator's keystrokes into the
 * symbolic input structure {@code COSGN0AI} and then validated the entered user id
 * and password against the {@code USRSEC} VSAM file.</p>
 *
 * <p>In the migrated system this DTO is bound by {@code controller/AuthController}
 * to {@code POST /api/auth/signin}: Jackson deserializes the JSON request body into
 * an instance, Jakarta Bean Validation enforces the field constraints declared
 * below (the {@code @Valid} gate on the controller method), and the validated
 * payload is handed to {@code service/auth/AuthenticationService} (the translation
 * of {@code COSGN00C}), which performs the credential check and issues the session
 * token. This DTO is therefore a pure boundary type &mdash; it carries no business
 * logic, no I/O and no static state.</p>
 *
 * <h2>Original COBOL symbolic input map (COSGN00.CPY &mdash; {@code 01 COSGN0AI})</h2>
 * <p>The 3270 symbolic map declared eleven on-screen fields, but only two of them
 * were operator <em>inputs</em>; the remainder were presentation chrome, the error
 * line, or BMS per-field control bytes. The two modeled inputs are:</p>
 * <pre>{@code
 * 01  COSGN0AI.
 *     ...
 *     02  USERIDI  PIC X(8).   <-- modeled here as userId
 *     ...
 *     02  PASSWDI  PIC X(8).   <-- modeled here as password
 *     ...
 * }</pre>
 *
 * <h2>Scope &mdash; exactly two fields are modeled (AAP &sect;0.4.2)</h2>
 * <p>The following symbolic-map members are <strong>deliberately excluded</strong>
 * because they are 3270 presentation/control artifacts with no REST equivalent:</p>
 * <ul>
 *   <li><strong>Screen chrome (output-only):</strong> {@code TRNNAMEI} ({@code X(4)}),
 *       {@code TITLE01I}/{@code TITLE02I} ({@code X(40)}), {@code CURDATEI}
 *       ({@code X(8)}), {@code CURTIMEI} ({@code X(9)}), {@code PGMNAMEI}/
 *       {@code APPLIDI}/{@code SYSIDI} ({@code X(8)}) &mdash; banner, titles,
 *       date/time and environment identifiers painted by the program, never typed
 *       by the user.</li>
 *   <li><strong>Error line (output-only):</strong> {@code ERRMSGI} ({@code X(78)})
 *       &mdash; the message the program wrote back on a failed sign-on; in REST this
 *       becomes the response/error body, not a request field.</li>
 *   <li><strong>BMS per-field control bytes:</strong> the {@code ...L} (length),
 *       {@code ...F}/{@code ...A} (attribute/flag), and the redefined output bytes
 *       {@code ...C}/{@code ...P}/{@code ...H}/{@code ...V} in the {@code COSGN0AO}
 *       output redefinition &mdash; 3270 datastream metadata with no REST analogue.</li>
 *   <li><strong>AID / PF keys:</strong> the {@code DFHAID} attention identifiers
 *       (ENTER, PF3, PF12, &hellip;) do not map onto a request body; each AID maps to
 *       a distinct REST endpoint instead (AAP &sect;0.4.2).</li>
 * </ul>
 *
 * <h2>Technology-substitution notes (Minimal Change Clause &mdash; AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>{@code RECEIVE MAP} &rarr; REST request DTO.</strong> The CICS
 *       map-receive that populated {@code COSGN0AI} is replaced by Jackson binding a
 *       JSON body to this object; the two character inputs become {@link String}
 *       properties.</li>
 *   <li><strong>Byte-faithful field lengths.</strong> Both inputs were
 *       {@code PIC X(8)} (fixed eight-character alphanumeric). The
 *       {@link Size @Size(max = 8)} constraints preserve that external-interface
 *       width exactly (AAP &sect;0.7.2), and {@link NotBlank @NotBlank} reproduces
 *       the COBOL guard in {@code COSGN00C} that rejected an empty user id
 *       (&quot;Please enter User ID&nbsp;&hellip;&quot;) or an empty password.</li>
 *   <li><strong>No floating point.</strong> Neither field has decimal positions, so
 *       no numeric type is involved; consistent with AAP &sect;0.7.3 there is no
 *       {@code float}/{@code double} (nor {@code BigDecimal}) anywhere in this DTO.</li>
 * </ul>
 *
 * <h2>Security contract</h2>
 * <p>The password travels in <em>plaintext on the wire</em> exactly as the legacy
 * screen sent it; the single permitted behavioral change &mdash; hashing the stored
 * credential with BCrypt (constraint C-003, AAP &sect;0.7.2) &mdash; happens
 * downstream in {@code AuthenticationService}, <strong>not</strong> in this DTO. To
 * keep the secret from leaking back out, {@link #password} is annotated
 * {@link JsonProperty @JsonProperty(access = WRITE_ONLY)} so Jackson will accept it
 * on deserialization but will <strong>never serialize it</strong> into a response
 * body, and {@link #toString()} masks it. Callers and logging frameworks must never
 * record the raw password value.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at commit
 * SHA {@code 27d6c6f}. The COBOL source is read-only reference material and is never
 * copied into this repository.</p>
 *
 * @see jakarta.validation.constraints.NotBlank
 * @see jakarta.validation.constraints.Size
 * @see com.fasterxml.jackson.annotation.JsonProperty
 */
public class SignOnRequest {

    /**
     * The user id typed on the sign-on screen.
     *
     * <p>Migrated from {@code USERIDI PIC X(8)} &mdash; a fixed-length, eight
     * character alphanumeric field. {@link NotBlank @NotBlank} reproduces the COBOL
     * edit in {@code COSGN00C} that rejected an empty user id, and
     * {@link Size @Size(max = 8)} preserves the original field width byte-for-byte.</p>
     */
    // USERIDI PIC X(8) -> fixed-length 8-char alphanumeric -> String (max length 8)
    @NotBlank(message = "User ID must be supplied")
    @Size(max = 8, message = "User ID must not exceed 8 characters")
    private String userId;

    /**
     * The password typed on the sign-on screen.
     *
     * <p>Migrated from {@code PASSWDI PIC X(8)} &mdash; a fixed-length, eight
     * character alphanumeric field. {@link NotBlank @NotBlank} reproduces the COBOL
     * edit that rejected an empty password, and {@link Size @Size(max = 8)} preserves
     * the original field width.</p>
     *
     * <p><strong>Security:</strong> annotated
     * {@link JsonProperty @JsonProperty(access = WRITE_ONLY)} so it can be supplied on
     * an inbound request but is never written back out by Jackson. It must never be
     * logged; {@link #toString()} masks it. The plaintext value is consumed only by
     * {@code AuthenticationService}, which performs the BCrypt verification.</p>
     */
    // PASSWDI PIC X(8) -> fixed-length 8-char alphanumeric -> String (max length 8)
    // WRITE_ONLY: accepted on deserialization, never serialized into a response.
    @NotBlank(message = "Password must be supplied")
    @Size(max = 8, message = "Password must not exceed 8 characters")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    /**
     * Default no-argument constructor required by the JSON binder (Jackson)
     * to instantiate this DTO reflectively before populating its properties.
     */
    public SignOnRequest() {
        // Intentionally empty: Jackson instantiates then sets fields via setters.
    }

    /**
     * Constructs a fully-populated sign-on request.
     *
     * <p>Parameter order mirrors the COBOL symbolic-map field order: user id
     * ({@code USERIDI}) first, then password ({@code PASSWDI}).</p>
     *
     * @param userId   the user id ({@code USERIDI}); up to 8 characters, not blank
     * @param password the password ({@code PASSWDI}); up to 8 characters, not blank
     */
    public SignOnRequest(String userId, String password) {
        this.userId = userId;
        this.password = password;
    }

    /**
     * Returns the entered user id ({@code USERIDI}).
     *
     * @return the user id, or {@code null} if unset
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Sets the user id ({@code USERIDI}).
     *
     * @param userId the user id to set
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * Returns the entered password ({@code PASSWDI}).
     *
     * <p>The raw plaintext value is returned for the sole use of
     * {@code AuthenticationService}; callers must never log it.</p>
     *
     * @return the password, or {@code null} if unset
     */
    public String getPassword() {
        return password;
    }

    /**
     * Sets the password ({@code PASSWDI}).
     *
     * @param password the password to set
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * Value-based equality across both request fields.
     *
     * <p>Two instances are equal only when {@code o} is exactly a
     * {@code SignOnRequest} and both {@code userId} and {@code password} match.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is an equal sign-on request
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SignOnRequest that = (SignOnRequest) o;
        return Objects.equals(userId, that.userId)
                && Objects.equals(password, that.password);
    }

    /**
     * Hash code derived from both request fields, consistent with
     * {@link #equals(Object)}.
     *
     * @return the hash code for this request
     */
    @Override
    public int hashCode() {
        return Objects.hash(userId, password);
    }

    /**
     * Diagnostic representation that includes the user id but
     * <strong>never</strong> the password value.
     *
     * <p>The password is masked: only its presence ({@code "****"}) or absence
     * ({@code "null"}) is shown, never its length or content. This guarantees the
     * secret cannot leak through logs or diagnostics.</p>
     *
     * @return a human-readable, password-safe description of this request
     */
    @Override
    public String toString() {
        return "SignOnRequest{"
                + "userId='" + userId + '\''
                + ", password=" + (password == null ? "null" : "****")
                + '}';
    }
}
