/*
 * UserSecurity.java — JPA Entity for User Security Records
 *
 * Migrated from COBOL source artifact:
 *   - app/cpy/CSUSR01Y.cpy (SEC-USER-DATA, 80-byte record, commit 27d6c6f)
 *
 * This entity maps the COBOL SEC-USER-DATA record from the USRSEC VSAM KSDS
 * dataset to a PostgreSQL table. It stores user authentication and authorization
 * data consumed by five COBOL programs:
 *   - COSGN00C.cbl — Sign-on: reads by SEC-USR-ID, verifies password, routes by type
 *   - COUSR00C.cbl — User list: paginated browse of all user records
 *   - COUSR01C.cbl — User add: creates new SEC-USER-DATA record
 *   - COUSR02C.cbl — User update: modifies existing SEC-USER-DATA record
 *   - COUSR03C.cbl — User delete: removes SEC-USER-DATA record with confirmation
 *
 * COBOL Source Layout (app/cpy/CSUSR01Y.cpy, lines 17-23):
 *   01 SEC-USER-DATA.
 *      05 SEC-USR-ID       PIC X(08).   -> secUsrId (String, 8 chars)
 *      05 SEC-USR-FNAME    PIC X(20).   -> secUsrFname (String, 20 chars)
 *      05 SEC-USR-LNAME    PIC X(20).   -> secUsrLname (String, 20 chars)
 *      05 SEC-USR-PWD      PIC X(08).   -> secUsrPwd (BCrypt hash, 72 chars)
 *      05 SEC-USR-TYPE     PIC X(01).   -> secUsrType (UserType enum)
 *      05 SEC-USR-FILLER   PIC X(23).   -> NOT MAPPED (padding only)
 *
 * SECURITY UPGRADE: The original COBOL application stores passwords in plaintext
 * (constraint C-003). The Java migration upgrades to BCrypt hashing per AAP §0.8.1.
 * The secUsrPwd column is sized at 72 characters to accommodate the standard BCrypt
 * output format ($2a$10$..., typically 60 chars, with margin for algorithm variants).
 * Password verification is performed in AuthenticationService using
 * BCryptPasswordEncoder.matches() — this entity only stores the hash.
 *
 * @see com.cardemo.model.enums.UserType
 */
package com.cardemo.model.entity;

import com.cardemo.model.enums.UserType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * JPA entity representing a user security record in the CardDemo application.
 *
 * <p>Maps the COBOL {@code SEC-USER-DATA} (80 bytes) from {@code CSUSR01Y.cpy}
 * to the {@code usr_sec} PostgreSQL table. The USRSEC VSAM KSDS dataset is keyed
 * on {@code SEC-USR-ID} (8-byte primary key).</p>
 *
 * <p>Field mapping preserves exact COBOL PIC clause semantics for alphanumeric
 * fields. The password field is upgraded from plaintext (PIC X(08)) to BCrypt
 * hash storage (72 chars). The user type field uses the {@link UserType} enum
 * with {@code @Enumerated(EnumType.STRING)} for readable database values.</p>
 *
 * <p>The 23-byte FILLER at the end of the COBOL record is not mapped — it
 * served only as padding to reach the 80-byte record length.</p>
 */
@Entity
@Table(name = "user_security")
public class UserSecurity {

    // -----------------------------------------------------------------------
    // Primary Key
    // -----------------------------------------------------------------------

    /**
     * User identifier — maps COBOL {@code SEC-USR-ID PIC X(08)}.
     *
     * <p>The primary key for the user security record. Fixed at 8 characters
     * matching the COBOL field length. Used by COSGN00C.cbl for keyed READ
     * on the USRSEC VSAM file during authentication.</p>
     *
     * <p>Examples: {@code "ADMIN001"}, {@code "USER0001"}.</p>
     */
    @Id
    @Column(name = "usr_id", length = 8, nullable = false)
    private String secUsrId;

    // -----------------------------------------------------------------------
    // Data Fields — Exact COBOL PIC Mapping
    // -----------------------------------------------------------------------

    /**
     * User first name — maps COBOL {@code SEC-USR-FNAME PIC X(20)}.
     *
     * <p>Up to 20 characters for the user's first (given) name. Displayed
     * in the user list (COUSR00C) and user detail screens (COUSR01C-03C).</p>
     */
    @Column(name = "usr_fname", length = 20)
    private String secUsrFname;

    /**
     * User last name — maps COBOL {@code SEC-USR-LNAME PIC X(20)}.
     *
     * <p>Up to 20 characters for the user's last (family) name. Displayed
     * in the user list (COUSR00C) and user detail screens (COUSR01C-03C).</p>
     */
    @Column(name = "usr_lname", length = 20)
    private String secUsrLname;

    /**
     * BCrypt password hash — migrated from COBOL {@code SEC-USR-PWD PIC X(08)}.
     *
     * <p><strong>SECURITY UPGRADE:</strong> The COBOL source stores an 8-character
     * plaintext password (constraint C-003). In the Java migration, this field
     * stores the BCrypt hash of the password. Standard BCrypt output is 60
     * characters ({@code $2a$10$...}), and the column is sized at 72 to
     * accommodate algorithm variants ({@code $2b$}, higher cost factors).</p>
     *
     * <p>Password verification is performed in {@code AuthenticationService}
     * using {@code BCryptPasswordEncoder.matches()}. This field must NEVER
     * contain a plaintext password.</p>
     */
    @Column(name = "password_hash", length = 60, nullable = false)
    private String secUsrPwd;

    /**
     * User type / role — maps COBOL {@code SEC-USR-TYPE PIC X(01)}.
     *
     * <p>Represents the user's role in the application. The COBOL 88-level
     * conditions define two valid values:</p>
     * <ul>
     *   <li>{@link UserType#ADMIN ADMIN} ({@code 'A'}) — admin menu access,
     *       user management (COADM01C routing)</li>
     *   <li>{@link UserType#USER USER} ({@code 'U'}) — regular user, main menu
     *       access (COMEN01C routing)</li>
     * </ul>
     *
     * <p>Stored as the enum name string ({@code "ADMIN"} or {@code "USER"}) in
     * PostgreSQL via {@code @Enumerated(EnumType.STRING)}. The column length of
     * 10 accommodates both enum name strings with margin.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "usr_type", length = 1)
    private UserType secUsrType;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * No-argument constructor required by the JPA specification.
     *
     * <p>Used by the JPA provider (Hibernate) when materializing entity
     * instances from database query results.</p>
     */
    public UserSecurity() {
        // JPA-required default constructor
    }

    /**
     * All-argument constructor for programmatic entity creation.
     *
     * <p>Creates a fully populated user security record. The password parameter
     * should contain a BCrypt hash, not a plaintext password.</p>
     *
     * @param secUsrId    the user identifier (primary key); up to 8 characters,
     *                    matching COBOL SEC-USR-ID PIC X(08)
     * @param secUsrFname the user's first name; up to 20 characters,
     *                    matching COBOL SEC-USR-FNAME PIC X(20)
     * @param secUsrLname the user's last name; up to 20 characters,
     *                    matching COBOL SEC-USR-LNAME PIC X(20)
     * @param secUsrPwd   the BCrypt-hashed password; should be a valid BCrypt
     *                    hash string (typically 60 characters)
     * @param secUsrType  the user type/role; {@link UserType#ADMIN} or
     *                    {@link UserType#USER}
     */
    public UserSecurity(String secUsrId, String secUsrFname, String secUsrLname,
                        String secUsrPwd, UserType secUsrType) {
        this.secUsrId = secUsrId;
        this.secUsrFname = secUsrFname;
        this.secUsrLname = secUsrLname;
        this.secUsrPwd = secUsrPwd;
        this.secUsrType = secUsrType;
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    /**
     * Returns the user identifier (primary key).
     *
     * @return the user ID; never {@code null} for persisted entities
     */
    public String getSecUsrId() {
        return secUsrId;
    }

    /**
     * Sets the user identifier (primary key).
     *
     * @param secUsrId the user ID to set; up to 8 characters
     */
    public void setSecUsrId(String secUsrId) {
        this.secUsrId = secUsrId;
    }

    /**
     * Returns the user's first name.
     *
     * @return the first name; may be {@code null}
     */
    public String getSecUsrFname() {
        return secUsrFname;
    }

    /**
     * Sets the user's first name.
     *
     * @param secUsrFname the first name to set; up to 20 characters
     */
    public void setSecUsrFname(String secUsrFname) {
        this.secUsrFname = secUsrFname;
    }

    /**
     * Returns the user's last name.
     *
     * @return the last name; may be {@code null}
     */
    public String getSecUsrLname() {
        return secUsrLname;
    }

    /**
     * Sets the user's last name.
     *
     * @param secUsrLname the last name to set; up to 20 characters
     */
    public void setSecUsrLname(String secUsrLname) {
        this.secUsrLname = secUsrLname;
    }

    /**
     * Returns the BCrypt password hash.
     *
     * <p>This value should never be exposed in logs, API responses, or
     * toString() output.</p>
     *
     * @return the BCrypt hash string; never {@code null} for persisted entities
     */
    public String getSecUsrPwd() {
        return secUsrPwd;
    }

    /**
     * Sets the BCrypt password hash.
     *
     * <p>The value must be a valid BCrypt hash string, not a plaintext
     * password. Use {@code BCryptPasswordEncoder.encode()} before calling
     * this method.</p>
     *
     * @param secUsrPwd the BCrypt hash to set
     */
    public void setSecUsrPwd(String secUsrPwd) {
        this.secUsrPwd = secUsrPwd;
    }

    /**
     * Returns the user type/role.
     *
     * @return the {@link UserType} enum value ({@link UserType#ADMIN ADMIN}
     *         or {@link UserType#USER USER}); may be {@code null} for
     *         transient entities
     */
    public UserType getSecUsrType() {
        return secUsrType;
    }

    /**
     * Sets the user type/role.
     *
     * @param secUsrType the user type to set; typically {@link UserType#ADMIN}
     *                   or {@link UserType#USER}
     */
    public void setSecUsrType(UserType secUsrType) {
        this.secUsrType = secUsrType;
    }

    // -----------------------------------------------------------------------
    // equals, hashCode, toString
    // -----------------------------------------------------------------------

    /**
     * Compares this user security record to another object for equality.
     *
     * <p>Two {@code UserSecurity} instances are equal if and only if they
     * have the same {@code secUsrId} primary key value. This follows the
     * JPA best practice of using the business/natural key for entity
     * identity comparison.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if the given object is a {@code UserSecurity}
     *         with the same {@code secUsrId}; {@code false} otherwise
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        UserSecurity that = (UserSecurity) o;
        return Objects.equals(secUsrId, that.secUsrId);
    }

    /**
     * Returns a hash code based on the {@code secUsrId} primary key.
     *
     * <p>Consistent with {@link #equals(Object)}: entities with equal
     * {@code secUsrId} values produce the same hash code.</p>
     *
     * @return hash code derived from the user identifier
     */
    @Override
    public int hashCode() {
        return Objects.hash(secUsrId);
    }

    /**
     * Returns a string representation of this user security record.
     *
     * <p><strong>Security:</strong> The password hash ({@code secUsrPwd})
     * is intentionally excluded from this output to prevent credential
     * leakage in logs, stack traces, and debug output.</p>
     *
     * @return a formatted string containing user ID, names, and type
     */
    @Override
    public String toString() {
        return "UserSecurity{" +
                "secUsrId='" + secUsrId + '\'' +
                ", secUsrFname='" + secUsrFname + '\'' +
                ", secUsrLname='" + secUsrLname + '\'' +
                ", secUsrType=" + secUsrType +
                '}';
    }
}
