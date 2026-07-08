package com.carddemo.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for the <em>Update User</em> operation (CardDemo online transaction
 * {@code CU02}), handled by {@code UserController} / {@code UserService}.
 *
 * <p>This DTO is the idiomatic Spring translation of the {@code COUSR02} BMS
 * screen input fields (symbolic map {@code COUSR02.CPY}, structure
 * {@code COUSR2AI}) which are, in turn, backed by the {@code SEC-USER-DATA}
 * record layout ({@code CSUSR01Y.cpy}). The COBOL {@code PIC X(n)} field widths
 * are preserved here as Jakarta Validation {@link Size} bounds so the REST
 * contract enforces the same field edits the 3270 map did.</p>
 *
 * <h2>Why {@code userId} is not a component</h2>
 * <p>The user identifier ({@code COUSR02} field {@code USRIDIN}, {@code PIC X(8)},
 * mapped from {@code SEC-USR-ID}) selects <em>which</em> user is being updated.
 * In the REST design it is carried as the <strong>path variable</strong>
 * (for example {@code PUT /api/users/{userId}}) rather than in the request body,
 * so it is intentionally <strong>not</strong> a component of this record. Keeping
 * the identifier out of the body prevents a mismatch between the addressed
 * resource and its payload.</p>
 *
 * <h2>Password handling (Constraint C-003)</h2>
 * <p>{@link #password()} is <strong>optional</strong>: it is populated only when
 * the caller intends to change the password, and a {@code null} value leaves the
 * stored credential untouched. The value is accepted as <em>plaintext inbound</em>
 * and is hashed with BCrypt by the service layer before persistence; it is never
 * echoed back in any response. To avoid accidental disclosure through logs or
 * diagnostics, {@link #toString()} is overridden to redact the password.</p>
 *
 * <p>The record is immutable and therefore inherently stateless and thread-safe,
 * matching the stateless REST model that replaces the CICS pseudo-conversational
 * {@code COMMAREA} flow.</p>
 *
 * @param firstName the user's given name; maps to {@code COUSR02} field
 *                  {@code FNAME} / {@code SEC-USR-FNAME} ({@code PIC X(20)}).
 *                  May be {@code null} (field left unchanged); when supplied it
 *                  must not exceed 20 characters.
 * @param lastName  the user's family name; maps to {@code COUSR02} field
 *                  {@code LNAME} / {@code SEC-USR-LNAME} ({@code PIC X(20)}).
 *                  May be {@code null} (field left unchanged); when supplied it
 *                  must not exceed 20 characters.
 * @param password  the new plaintext password; maps to {@code COUSR02} field
 *                  {@code PASSWD} / {@code SEC-USR-PWD} ({@code PIC X(8)}).
 *                  Optional — {@code null} means "do not change". When supplied
 *                  it must not exceed 8 characters. Hashed to BCrypt by the
 *                  service (C-003) and never returned.
 * @param userType  the user role code; maps to {@code COUSR02} field
 *                  {@code USRTYPE} / {@code SEC-USR-TYPE} ({@code PIC X(1)}).
 *                  May be {@code null} (field left unchanged); when supplied it
 *                  must be a single character, either {@code 'A'} (Admin) or
 *                  {@code 'U'} (Regular User).
 */
public record UserUpdateRequest(

        @Size(max = 20, message = "firstName must be at most 20 characters")
        String firstName,

        @Size(max = 20, message = "lastName must be at most 20 characters")
        String lastName,

        @Size(max = 8, message = "password must be at most 8 characters")
        String password,

        @Size(max = 1, message = "userType must be a single character")
        @Pattern(regexp = "[AU]", message = "userType must be 'A' (Admin) or 'U' (Regular User)")
        String userType

) {

    /**
     * Placeholder rendered in {@link #toString()} in place of a non-null
     * {@link #password()} so the plaintext credential is never exposed through
     * logs, stack traces, or debugging output (Constraint C-003).
     */
    private static final String REDACTED_PASSWORD = "<redacted>";

    /**
     * Returns a diagnostic representation of this request with the
     * {@link #password()} value redacted.
     *
     * <p>The rendering mirrors the canonical record {@code toString()} layout for
     * the remaining components but substitutes a fixed placeholder for any
     * non-null password. A {@code null} password is rendered as {@code "null"} so
     * that "password not supplied" remains distinguishable from "password
     * supplied" without ever disclosing the plaintext value.</p>
     *
     * @return a string describing this request that never contains the plaintext
     *         password
     */
    @Override
    public String toString() {
        return "UserUpdateRequest["
                + "firstName=" + firstName
                + ", lastName=" + lastName
                + ", password=" + (password == null ? "null" : REDACTED_PASSWORD)
                + ", userType=" + userType
                + "]";
    }
}
