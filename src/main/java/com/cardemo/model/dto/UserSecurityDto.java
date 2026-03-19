/*
 * UserSecurityDto.java — User Admin CRUD API Payload DTO
 *
 * Migrated from COBOL source artifacts:
 *   - app/cpy/CSUSR01Y.cpy    (SEC-USER-DATA record layout, 80 bytes)
 *   - app/cpy-bms/COUSR00.CPY (COUSR0AI — user list browse screen)
 *   - app/cpy-bms/COUSR01.CPY (COUSR1AI — user add screen)
 *   - app/cpy-bms/COUSR02.CPY (COUSR2AI — user update screen)
 *   - app/cpy-bms/COUSR03.CPY (COUSR3AI — user delete screen)
 *
 * This DTO consolidates user administration payloads for the REST API,
 * capturing fields from the four BMS symbolic maps (list, add, update,
 * delete) that correspond to the USRSEC VSAM dataset's record layout.
 *
 * COBOL record mapping (SEC-USER-DATA, 80 bytes total):
 *   SEC-USR-ID     PIC X(08) → secUsrId     (String, max 8)
 *   SEC-USR-FNAME  PIC X(20) → secUsrFname  (String, max 20)
 *   SEC-USR-LNAME  PIC X(20) → secUsrLname  (String, max 20)
 *   SEC-USR-PWD    PIC X(08) → secUsrPwd    (String, max 8, write-only)
 *   SEC-USR-TYPE   PIC X(01) → secUsrType   (UserType enum)
 *   SEC-USR-FILLER PIC X(23) → (not mapped — reserved filler)
 *
 * Security note: The COBOL source stores passwords in plaintext (constraint C-003).
 * In the Java target, passwords are hashed with BCrypt before persistence.
 * This DTO accepts the plaintext password on input (create/update) but
 * excludes it from JSON response output via @JsonProperty(access = WRITE_ONLY).
 *
 * Consumed by: UserAdminController (CRUD /api/admin/users/*)
 * Depends on:  UserType enum (com.cardemo.model.enums.UserType)
 */
package com.cardemo.model.dto;

import com.cardemo.model.enums.UserType;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Data Transfer Object for user security administration operations.
 *
 * <p>Maps the SEC-USER-DATA record layout from {@code CSUSR01Y.cpy} into a
 * Java DTO suitable for REST API request/response payloads. Field size
 * constraints match the original COBOL PIC clause lengths exactly.</p>
 *
 * <p>Used across all four user administration screens:</p>
 * <ul>
 *   <li>User list browse (COUSR00) — id, first name, last name, type per row</li>
 *   <li>User add (COUSR01) — all fields including password</li>
 *   <li>User update (COUSR02) — all fields including password</li>
 *   <li>User delete (COUSR03) — display fields only (id, names, type)</li>
 * </ul>
 */
public class UserSecurityDto {

    /**
     * User identifier — primary key.
     *
     * <p>Maps from COBOL: {@code SEC-USR-ID PIC X(08)} (CSUSR01Y.cpy line 18).
     * Also mapped from BMS fields: USERIDI (COUSR01), USRIDINI (COUSR02/03),
     * and USRID01I..USRID10I (COUSR00 list rows).</p>
     */
    @NotBlank(message = "User ID is required")
    @Size(max = 8, message = "User ID must not exceed 8 characters")
    @Pattern(regexp = "^[A-Za-z0-9]+$",
             message = "User ID must contain only alphanumeric characters")
    private String secUsrId;

    /**
     * User first name.
     *
     * <p>Maps from COBOL: {@code SEC-USR-FNAME PIC X(20)} (CSUSR01Y.cpy line 19).
     * Also mapped from BMS fields: FNAMEI (COUSR01/02/03) and
     * FNAME01I..FNAME10I (COUSR00 list rows).</p>
     */
    @Size(max = 20, message = "First name must not exceed 20 characters")
    private String secUsrFname;

    /**
     * User last name.
     *
     * <p>Maps from COBOL: {@code SEC-USR-LNAME PIC X(20)} (CSUSR01Y.cpy line 20).
     * Also mapped from BMS fields: LNAMEI (COUSR01/02/03) and
     * LNAME01I..LNAME10I (COUSR00 list rows).</p>
     */
    @Size(max = 20, message = "Last name must not exceed 20 characters")
    private String secUsrLname;

    /**
     * User password — write-only field for security.
     *
     * <p>Maps from COBOL: {@code SEC-USR-PWD PIC X(08)} (CSUSR01Y.cpy line 21).
     * Also mapped from BMS fields: PASSWDI (COUSR01/02).</p>
     *
     * <p>This field is accepted on create and update requests but is
     * <strong>never</strong> included in JSON response output. The COBOL source
     * stored passwords in plaintext; the Java target hashes them with BCrypt
     * before persistence. The max length of 8 matches the original PIC X(08)
     * constraint on the input password before hashing.</p>
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Size(max = 8, message = "Password must not exceed 8 characters")
    private String secUsrPwd;

    /**
     * User type/role indicator.
     *
     * <p>Maps from COBOL: {@code SEC-USR-TYPE PIC X(01)} (CSUSR01Y.cpy line 22).
     * Valid values: {@link UserType#ADMIN} ('A') for administrators,
     * {@link UserType#USER} ('U') for regular users. Also mapped from BMS
     * fields: USRTYPEI (COUSR01/02/03) and UTYPE01I..UTYPE10I (COUSR00 list rows).</p>
     */
    private UserType secUsrType;

    /**
     * Default no-argument constructor required for Jackson deserialization
     * and framework compatibility.
     */
    public UserSecurityDto() {
        // No-args constructor for Jackson/Spring deserialization
    }

    /**
     * All-arguments constructor for programmatic DTO creation.
     *
     * @param secUsrId    the user identifier (max 8 characters)
     * @param secUsrFname the user's first name (max 20 characters)
     * @param secUsrLname the user's last name (max 20 characters)
     * @param secUsrPwd   the user's plaintext password (max 8 characters, write-only)
     * @param secUsrType  the user's role type ({@link UserType#ADMIN} or {@link UserType#USER})
     */
    public UserSecurityDto(String secUsrId, String secUsrFname, String secUsrLname,
                           String secUsrPwd, UserType secUsrType) {
        this.secUsrId = secUsrId;
        this.secUsrFname = secUsrFname;
        this.secUsrLname = secUsrLname;
        this.secUsrPwd = secUsrPwd;
        this.secUsrType = secUsrType;
    }

    /**
     * Returns the user identifier.
     *
     * @return the user ID (up to 8 characters), or {@code null} if not set
     */
    public String getSecUsrId() {
        return secUsrId;
    }

    /**
     * Sets the user identifier.
     *
     * @param secUsrId the user ID (max 8 characters, matching COBOL PIC X(08))
     */
    public void setSecUsrId(String secUsrId) {
        this.secUsrId = secUsrId;
    }

    /**
     * Returns the user's first name.
     *
     * @return the first name (up to 20 characters), or {@code null} if not set
     */
    public String getSecUsrFname() {
        return secUsrFname;
    }

    /**
     * Sets the user's first name.
     *
     * @param secUsrFname the first name (max 20 characters, matching COBOL PIC X(20))
     */
    public void setSecUsrFname(String secUsrFname) {
        this.secUsrFname = secUsrFname;
    }

    /**
     * Returns the user's last name.
     *
     * @return the last name (up to 20 characters), or {@code null} if not set
     */
    public String getSecUsrLname() {
        return secUsrLname;
    }

    /**
     * Sets the user's last name.
     *
     * @param secUsrLname the last name (max 20 characters, matching COBOL PIC X(20))
     */
    public void setSecUsrLname(String secUsrLname) {
        this.secUsrLname = secUsrLname;
    }

    /**
     * Returns the user's plaintext password.
     *
     * <p>Note: Due to the {@link JsonProperty.Access#WRITE_ONLY} annotation,
     * this getter is effectively suppressed during JSON serialization. It
     * returns the password value only for internal service-layer consumption
     * (e.g., passing to BCrypt hashing in the user creation flow).</p>
     *
     * @return the plaintext password (up to 8 characters), or {@code null} if not set
     */
    public String getSecUsrPwd() {
        return secUsrPwd;
    }

    /**
     * Sets the user's plaintext password.
     *
     * <p>This value is accepted from JSON input (create/update requests) and
     * should be hashed with BCrypt before persistence to the database.</p>
     *
     * @param secUsrPwd the plaintext password (max 8 characters, matching COBOL PIC X(08))
     */
    public void setSecUsrPwd(String secUsrPwd) {
        this.secUsrPwd = secUsrPwd;
    }

    /**
     * Returns the user's role type.
     *
     * @return the {@link UserType} enum value ({@link UserType#ADMIN} or
     *         {@link UserType#USER}), or {@code null} if not set
     */
    public UserType getSecUsrType() {
        return secUsrType;
    }

    /**
     * Sets the user's role type.
     *
     * @param secUsrType the {@link UserType} enum value
     *                   ({@link UserType#ADMIN} or {@link UserType#USER})
     */
    public void setSecUsrType(UserType secUsrType) {
        this.secUsrType = secUsrType;
    }

    /**
     * Returns a string representation of this DTO for logging and debugging.
     *
     * <p><strong>Security:</strong> The password field ({@code secUsrPwd}) is
     * intentionally excluded from this output to prevent accidental credential
     * leakage in log files and console output.</p>
     *
     * @return a string containing the user ID, first name, last name, and user type
     */
    @Override
    public String toString() {
        return "UserSecurityDto{"
                + "secUsrId='" + secUsrId + '\''
                + ", secUsrFname='" + secUsrFname + '\''
                + ", secUsrLname='" + secUsrLname + '\''
                + ", secUsrType=" + secUsrType
                + '}';
    }
}
