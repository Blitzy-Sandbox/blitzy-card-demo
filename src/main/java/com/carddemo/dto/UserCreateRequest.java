package com.carddemo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Immutable, validated request payload for creating a new application user.
 *
 * <p>This record is the modernized, headless-REST replacement for the input side of the
 * legacy CICS "Add User" screen (transaction {@code CU01}, program {@code COUSR01C},
 * BMS mapset {@code COUSR01}). Its five request fields correspond one-to-one to the
 * editable input fields of the {@code COUSR1AI} symbolic map and to the
 * {@code SEC-USER-DATA} record layout ({@code CSUSR01Y}) that backs the file-based
 * {@code USRSEC} security store. Field lengths mirror the original COBOL {@code PIC X(n)}
 * pictures so the external contract stays byte-compatible with the mainframe source
 * (traceability anchor: commit {@code 27d6c6f}).</p>
 *
 * <h2>COBOL &rarr; Java field mapping</h2>
 * <table border="1">
 *   <caption>Source-to-DTO field mapping</caption>
 *   <tr>
 *     <th>DTO field</th><th>BMS input ({@code COUSR01})</th>
 *     <th>Record field ({@code CSUSR01Y})</th><th>COBOL picture</th>
 *   </tr>
 *   <tr><td>{@code userId}</td><td>{@code USERIDI}</td><td>{@code SEC-USR-ID}</td><td>{@code PIC X(08)}</td></tr>
 *   <tr><td>{@code firstName}</td><td>{@code FNAMEI}</td><td>{@code SEC-USR-FNAME}</td><td>{@code PIC X(20)}</td></tr>
 *   <tr><td>{@code lastName}</td><td>{@code LNAMEI}</td><td>{@code SEC-USR-LNAME}</td><td>{@code PIC X(20)}</td></tr>
 *   <tr><td>{@code password}</td><td>{@code PASSWDI}</td><td>{@code SEC-USR-PWD}</td><td>{@code PIC X(08)}</td></tr>
 *   <tr><td>{@code userType}</td><td>{@code USRTYPEI}</td><td>{@code SEC-USR-TYPE}</td><td>{@code PIC X(01)}</td></tr>
 * </table>
 *
 * <h2>Security &amp; observability contract</h2>
 * <ul>
 *   <li><strong>Plaintext inbound only.</strong> {@code password} carries the caller-supplied
 *       plaintext credential. It is <em>never</em> persisted in this form: the user service
 *       hashes it with BCrypt before it reaches the datastore (Constraint C-003 /
 *       Decision Log D-002). This DTO neither stores nor exposes the hashed value.</li>
 *   <li><strong>Redacted logging.</strong> {@link #toString()} is overridden to emit
 *       {@code password=***} so the plaintext credential can never leak into structured
 *       logs, traces, or exception messages while all non-sensitive fields remain visible
 *       for troubleshooting.</li>
 * </ul>
 *
 * <p>The type is a stateless, immutable {@code record}. Validation is declarative via Jakarta
 * Bean Validation constraints and is triggered when a caller annotates the bound parameter
 * with {@code @Valid}; the {@code @Size} and {@code @Pattern} rules reproduce the field edits
 * enforced by the original {@code COUSR01} map.</p>
 *
 * @param userId    unique user identifier; required, at most 8 characters ({@code SEC-USR-ID}).
 * @param firstName user's first name; required, at most 20 characters ({@code SEC-USR-FNAME}).
 * @param lastName  user's last name; required, at most 20 characters ({@code SEC-USR-LNAME}).
 * @param password  plaintext password to be BCrypt-hashed by the service; required, at most
 *                  8 characters ({@code SEC-USR-PWD}). Redacted from {@link #toString()}.
 * @param userType  role code; required single character, {@code A} (administrator) or
 *                  {@code U} (regular user) ({@code SEC-USR-TYPE}).
 */
public record UserCreateRequest(

        @NotBlank(message = "User ID is required")
        @Size(max = 8, message = "User ID must be at most 8 characters")
        String userId,

        @NotBlank(message = "First name is required")
        @Size(max = 20, message = "First name must be at most 20 characters")
        String firstName,

        @NotBlank(message = "Last name is required")
        @Size(max = 20, message = "Last name must be at most 20 characters")
        String lastName,

        @NotBlank(message = "Password is required")
        @Size(max = 8, message = "Password must be at most 8 characters")
        String password,

        @NotBlank(message = "User type is required")
        @Size(max = 1, message = "User type must be a single character")
        @Pattern(regexp = "[AU]", message = "User type must be 'A' (admin) or 'U' (user)")
        String userType

) {

    /**
     * Masking token rendered in place of the real password in any string representation.
     */
    private static final String REDACTED_PASSWORD = "***";

    /**
     * Returns a diagnostic string representation with the {@code password} field redacted.
     *
     * <p>The compiler-generated {@code record} {@code toString()} would expose the plaintext
     * password verbatim; this override substitutes {@code ***} so the credential never appears
     * in logs, traces, or exception messages. All non-sensitive fields are retained to keep the
     * representation useful for troubleshooting.</p>
     *
     * @return a string representation of this request with the password masked.
     */
    @Override
    public String toString() {
        return "UserCreateRequest["
                + "userId=" + userId
                + ", firstName=" + firstName
                + ", lastName=" + lastName
                + ", password=" + REDACTED_PASSWORD
                + ", userType=" + userType
                + "]";
    }
}
