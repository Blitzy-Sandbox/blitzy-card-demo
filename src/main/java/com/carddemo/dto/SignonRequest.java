package com.carddemo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Sign-on (authentication) request payload accepted by the authentication endpoint
 * (an HTTP {@code POST} handled by {@code AuthController} / {@code SignonService}).
 *
 * <p>This DTO is the REST translation of the legacy CardDemo {@code COSGN00} BMS sign-on
 * screen (CICS transaction {@code CC00}, backing program {@code COSGN00C}). The two BMS
 * input fields {@code USERIDI} and {@code PASSWDI} &mdash; each an 8-character alphanumeric
 * field ({@code PIC X(8)}) &mdash; map to {@link #userId()} and {@link #password()}
 * respectively. The fixed legacy width of 8 is enforced with {@link Size}, and the
 * mandatory-field edit performed by {@code CICS RECEIVE MAP} is reproduced with
 * {@link NotBlank}, so an empty submission is rejected with a {@code 400}-style validation
 * error rather than being silently accepted.</p>
 *
 * <p>Credentials are verified downstream against the migrated {@code SEC-USER-DATA} record
 * (copybook {@code CSUSR01Y}: {@code SEC-USR-ID PIC X(08)}, {@code SEC-USR-PWD PIC X(08)}).
 * The submitted password is compared against a stored BCrypt hash (Decision Log D-002 /
 * Constraint C-003 &mdash; the sole logged deviation from a literal translation of the
 * plaintext legacy scheme). Consequently this record carries the
 * <strong>plaintext password only in transit</strong> (over TLS): it must never be written
 * to a log, echoed in a response body, or embedded in an error message. To guarantee this,
 * {@link #toString()} is overridden so the password value is never rendered.</p>
 *
 * <p>The request is fully stateless: there is no CICS pseudo-conversational {@code COMMAREA}
 * equivalent to retain. On successful authentication the service layer issues a JWT that
 * carries the session state for subsequent calls.</p>
 *
 * @param userId   the user identifier (legacy {@code USERIDI} / {@code SEC-USR-ID});
 *                 must be non-blank and at most 8 characters
 * @param password the plaintext password (legacy {@code PASSWDI} / {@code SEC-USR-PWD});
 *                 must be non-blank and at most 8 characters; never logged or echoed
 */
public record SignonRequest(

        @NotBlank(message = "User ID is required")
        @Size(max = 8, message = "User ID must be at most 8 characters")
        String userId,

        @NotBlank(message = "Password is required")
        @Size(max = 8, message = "Password must be at most 8 characters")
        String password) {

    /**
     * Fixed mask substituted for the password in {@link #toString()} so that the secret is
     * never exposed through logging, stack traces, or diagnostic output.
     */
    private static final String REDACTED_PASSWORD = "***REDACTED***";

    /**
     * Returns a diagnostic string representation that deliberately <strong>omits the
     * password</strong>.
     *
     * <p>A record's compiler-generated {@code toString()} includes every component, which for
     * this type would leak the plaintext password into any log line or exception message that
     * renders the object. This override exposes only the non-sensitive {@code userId} and
     * substitutes a fixed mask for the password, satisfying the "never log secrets"
     * requirement of the migration.</p>
     *
     * @return a representation containing the user id and a masked (redacted) password
     */
    @Override
    public String toString() {
        return "SignonRequest[userId=" + userId + ", password=" + REDACTED_PASSWORD + "]";
    }
}
