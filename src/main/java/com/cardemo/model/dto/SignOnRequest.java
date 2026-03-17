package com.cardemo.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Authentication request DTO capturing sign-on credentials.
 *
 * <p>Maps from the BMS symbolic map {@code COSGN00.CPY} (sign-on screen),
 * specifically the {@code USERIDI PIC X(8)} and {@code PASSWDI PIC X(8)}
 * input fields from the COSGN0AI input view.</p>
 *
 * <p>Authentication flow (migrated from COSGN00C.cbl):
 * <ol>
 *   <li>Client submits userId and password via POST /api/auth/signin</li>
 *   <li>AuthenticationService reads UserSecurity record by userId</li>
 *   <li>BCrypt.matches() verifies password against stored hash
 *       (replaces COBOL plaintext comparison)</li>
 *   <li>On success, returns SignOnResponse with token and user routing info</li>
 * </ol>
 *
 * <p>Field size constraints (max 8 characters each) preserve the original
 * COBOL PIC X(8) field boundaries from the 3270 terminal screen layout.
 * The input password is limited to 8 characters matching the original COBOL
 * constraint, even though the stored BCrypt hash is 60+ characters.</p>
 *
 * @see com.cardemo.model.dto.SignOnResponse
 */
public class SignOnRequest {

    /**
     * User identifier for authentication.
     * Maps to {@code USERIDI PIC X(8)} from COSGN00.CPY line 72.
     * Maximum 8 characters matching the original COBOL field width.
     */
    @NotBlank(message = "User ID is required")
    @Size(max = 8, message = "User ID must not exceed 8 characters")
    private String userId;

    /**
     * User password for authentication.
     * Maps to {@code PASSWDI PIC X(8)} from COSGN00.CPY line 78.
     * Maximum 8 characters matching the original COBOL field width.
     * The input password is constrained to 8 chars (COBOL PIC X(8)),
     * but the stored hash uses BCrypt (60+ characters).
     */
    @NotBlank(message = "Password is required")
    @Size(max = 8, message = "Password must not exceed 8 characters")
    private String password;

    /**
     * Default no-args constructor required for JSON deserialization
     * and framework instantiation.
     */
    public SignOnRequest() {
    }

    /**
     * All-args constructor for programmatic construction.
     *
     * @param userId   the user identifier (max 8 characters)
     * @param password the user password (max 8 characters)
     */
    public SignOnRequest(String userId, String password) {
        this.userId = userId;
        this.password = password;
    }

    /**
     * Returns the user identifier.
     *
     * @return the user ID string, or {@code null} if not set
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Sets the user identifier.
     *
     * @param userId the user ID string (max 8 characters)
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * Returns the user password.
     *
     * @return the password string, or {@code null} if not set
     */
    public String getPassword() {
        return password;
    }

    /**
     * Sets the user password.
     *
     * @param password the password string (max 8 characters)
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * Returns a string representation of this sign-on request.
     * The password field is intentionally excluded for security
     * to prevent credential leakage in logs, stack traces, or debug output.
     *
     * @return string representation containing only the userId
     */
    @Override
    public String toString() {
        return "SignOnRequest{userId='" + userId + "'}";
    }
}
