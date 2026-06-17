package com.carddemo.exception;

/**
 * Sign-on authentication failure. Raised by the authentication service when a sign-in
 * attempt cannot be authenticated <em>after</em> the blank-field edits have passed -
 * that is, when the user id is unknown or the supplied password does not match the
 * stored BCrypt hash. Models the failure branches of the CICS sign-on program
 * {@code COSGN00C} ({@code WHEN 13} not-found and the wrong-password edit; source
 * commit {@code 27d6c6f}, reference only).
 *
 * <p><strong>Generalized by design (anti-enumeration).</strong> A single exception type
 * with one client-safe message is used for both the unknown-user and the wrong-password
 * cases so the public sign-in contract returns an identical {@code 401 UNAUTHORIZED}
 * response regardless of which check failed. Distinguishing the two (the original COBOL
 * surfaced "User not found" vs. "Wrong Password") would let a caller enumerate valid user
 * ids (CWE-204 "Observable Response Discrepancy" / CWE-203). The specific outcome is
 * retained only in server-side metrics and logs, never returned to the client.</p>
 *
 * <p>Belongs to the {@link CardDemoException} hierarchy that replaces COBOL
 * {@code FILE STATUS} checking and implicit paragraph fall-through with explicit,
 * exception-driven error flow. It is mapped to {@code 401 UNAUTHORIZED} by
 * {@code controller/GlobalExceptionHandler}; the mapping rationale is recorded in
 * {@code DECISION_LOG.md} rather than in code comments.</p>
 */
public class AuthenticationFailedException extends CardDemoException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an authentication-failure exception with a client-safe, generalized message.
     *
     * @param message the generalized, non-enumerable detail message returned to the client
     */
    public AuthenticationFailedException(String message) {
        super(message);
    }

    /**
     * Creates an authentication-failure exception with a generalized message and an
     * underlying cause (for example, a credential-verification error).
     *
     * @param message the generalized, non-enumerable detail message returned to the client
     * @param cause   the underlying cause
     */
    public AuthenticationFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
