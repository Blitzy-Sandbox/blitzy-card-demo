package com.cardemo.model.entity;

import com.cardemo.model.enums.UserType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.util.Objects;

/**
 * JPA entity mapping the COBOL USER-SECURITY-RECORD (80 bytes) from CSUSR01Y.cpy
 * to the PostgreSQL {@code user_security} table.
 *
 * <p>This entity represents the authentication and authorisation record for
 * CardDemo application users. The original COBOL record layout stores a
 * single-character type code ({@code SEC-USR-TYPE PIC X(01)}) that maps to
 * the {@link UserType} enum via its COBOL 88-level conditions
 * ({@code CDEMO-USRTYP-ADMIN VALUE 'A'}, {@code CDEMO-USRTYP-USER VALUE 'U'}).</p>
 *
 * <h3>COBOL Source Reference</h3>
 * <pre>
 * Source: app/cpy/CSUSR01Y.cpy (commit 27d6c6f)
 * Record Length: 80 bytes
 * VSAM Dataset: USRSEC (KSDS, keyed on SEC-USR-ID)
 *
 *   01  SEC-USER-DATA.
 *       05  SEC-USR-ID          PIC X(08).
 *       05  SEC-USR-FNAME       PIC X(20).
 *       05  SEC-USR-LNAME       PIC X(20).
 *       05  SEC-USR-PWD         PIC X(08).  → BCrypt hash (60 chars) in Java target
 *       05  SEC-USR-TYPE        PIC X(01).
 *       05  FILLER              PIC X(23).
 * </pre>
 *
 * <h3>Key Usage Contexts</h3>
 * <ul>
 *   <li>COSGN00C.cbl — Sign-on authentication: reads USRSEC by SEC-USR-ID,
 *       compares SEC-USR-PWD (migrated to BCrypt verification), routes by SEC-USR-TYPE</li>
 *   <li>COUSR00C.cbl — User list browse (paginated)</li>
 *   <li>COUSR01C.cbl — User add (new record creation with BCrypt password)</li>
 *   <li>COUSR02C.cbl — User update (modify fields including password)</li>
 *   <li>COUSR03C.cbl — User delete (record removal with confirmation)</li>
 * </ul>
 *
 * <h3>Security Note</h3>
 * <p>The original COBOL application stores passwords in plaintext (constraint C-003).
 * The Java migration upgrades to BCrypt hashing per AAP §0.8.1. The
 * {@code passwordHash} field is sized at 60 characters to accommodate the standard
 * BCrypt {@code $2a$10$...} output format.</p>
 *
 * <p>COBOL source reference: {@code app/cpy/CSUSR01Y.cpy} from commit {@code 27d6c6f}.</p>
 *
 * @see com.cardemo.model.enums.UserType
 * @see com.cardemo.service.auth.AuthenticationService
 */
@Entity
@Table(name = "user_security")
public class UserSecurity {

    /**
     * User identifier — maps COBOL {@code SEC-USR-ID PIC X(08)}.
     *
     * <p>The primary key for the user security record. Up to 8 characters,
     * matching the COBOL field size. Examples: {@code "ADMIN001"}, {@code "USER0001"}.</p>
     */
    @Id
    @Column(name = "usr_id", length = 8, nullable = false)
    private String usrId;

    /**
     * User first name — maps COBOL {@code SEC-USR-FNAME PIC X(20)}.
     *
     * <p>Up to 20 characters for the user's first (given) name.</p>
     */
    @Column(name = "usr_fname", length = 20)
    private String usrFirstName;

    /**
     * User last name — maps COBOL {@code SEC-USR-LNAME PIC X(20)}.
     *
     * <p>Up to 20 characters for the user's last (family) name.</p>
     */
    @Column(name = "usr_lname", length = 20)
    private String usrLastName;

    /**
     * BCrypt password hash — migrated from COBOL {@code SEC-USR-PWD PIC X(08)}.
     *
     * <p>The original COBOL field stores an 8-character plaintext password (constraint
     * C-003). In the Java migration, this field stores the BCrypt hash (60 characters)
     * of the password, using the standard {@code $2a$10$...} format.</p>
     *
     * <p>Important: This field must NEVER contain a plaintext password. All password
     * values must be hashed using BCrypt before persistence.</p>
     */
    @Column(name = "password_hash", length = 60, nullable = false)
    private String passwordHash;

    /**
     * User type / role — maps COBOL {@code SEC-USR-TYPE PIC X(01)}.
     *
     * <p>Single-character code stored as CHAR(1) in PostgreSQL to preserve
     * COBOL fixed-length field semantics. Values are constrained by the database
     * CHECK constraint to 'A' (admin) or 'U' (user), matching the COBOL 88-level
     * conditions in COCOM01Y.cpy.</p>
     *
     * <p>Stored as a raw String (not as {@link UserType} enum directly) because
     * the JPA column uses {@code CHAR(1)} type. The application layer converts
     * to/from {@link UserType} as needed.</p>
     */
    @Column(name = "usr_type", columnDefinition = "CHAR(1)", nullable = false)
    @JdbcTypeCode(Types.CHAR)
    private String usrType;

    /**
     * No-argument constructor required by JPA specification.
     *
     * <p>This constructor is used by the JPA provider (Hibernate) when
     * materializing entity instances from database query results.</p>
     */
    public UserSecurity() {
        // JPA-required default constructor
    }

    /**
     * All-argument constructor for programmatic entity creation.
     *
     * @param usrId         the user identifier (primary key); must not be {@code null},
     *                      up to 8 characters
     * @param usrFirstName  the user's first name; up to 20 characters
     * @param usrLastName   the user's last name; up to 20 characters
     * @param passwordHash  the BCrypt-hashed password; must not be {@code null},
     *                      exactly 60 characters for standard BCrypt output
     * @param usrType       the user type code ('A' for admin, 'U' for user);
     *                      must not be {@code null}
     */
    public UserSecurity(String usrId, String usrFirstName, String usrLastName,
                        String passwordHash, String usrType) {
        this.usrId = usrId;
        this.usrFirstName = usrFirstName;
        this.usrLastName = usrLastName;
        this.passwordHash = passwordHash;
        this.usrType = usrType;
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    /**
     * Returns the user identifier (primary key).
     *
     * @return the user ID, never {@code null} for persisted entities
     */
    public String getUsrId() {
        return usrId;
    }

    /**
     * Sets the user identifier (primary key).
     *
     * @param usrId the user ID to set; must not be {@code null}, up to 8 characters
     */
    public void setUsrId(String usrId) {
        this.usrId = usrId;
    }

    /**
     * Returns the user's first name.
     *
     * @return the first name, may be {@code null}
     */
    public String getUsrFirstName() {
        return usrFirstName;
    }

    /**
     * Sets the user's first name.
     *
     * @param usrFirstName the first name to set; up to 20 characters
     */
    public void setUsrFirstName(String usrFirstName) {
        this.usrFirstName = usrFirstName;
    }

    /**
     * Returns the user's last name.
     *
     * @return the last name, may be {@code null}
     */
    public String getUsrLastName() {
        return usrLastName;
    }

    /**
     * Sets the user's last name.
     *
     * @param usrLastName the last name to set; up to 20 characters
     */
    public void setUsrLastName(String usrLastName) {
        this.usrLastName = usrLastName;
    }

    /**
     * Returns the BCrypt password hash.
     *
     * @return the password hash, never {@code null} for persisted entities
     */
    public String getPasswordHash() {
        return passwordHash;
    }

    /**
     * Sets the BCrypt password hash.
     *
     * @param passwordHash the BCrypt hash to set; must not be {@code null}
     */
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /**
     * Returns the user type code ('A' or 'U').
     *
     * @return the user type code, never {@code null} for persisted entities
     */
    public String getUsrType() {
        return usrType;
    }

    /**
     * Sets the user type code.
     *
     * @param usrType the user type code to set; must be 'A' or 'U'
     */
    public void setUsrType(String usrType) {
        this.usrType = usrType;
    }

    /**
     * Convenience method to get the user type as a {@link UserType} enum value.
     *
     * @return the corresponding {@link UserType} enum constant
     * @throws IllegalArgumentException if the stored code is not a valid UserType
     */
    public UserType getUserTypeEnum() {
        return UserType.fromCode(usrType);
    }

    // -----------------------------------------------------------------------
    // equals, hashCode, toString
    // -----------------------------------------------------------------------

    /**
     * Compares this user security record to another object for equality.
     *
     * <p>Two {@code UserSecurity} instances are equal if and only if they
     * have the same {@code usrId} primary key value.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if the given object is a {@code UserSecurity}
     *         with the same {@code usrId} value; {@code false} otherwise
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
        return Objects.equals(usrId, that.usrId);
    }

    /**
     * Returns a hash code based on the {@code usrId} primary key.
     *
     * @return hash code derived from the user identifier
     */
    @Override
    public int hashCode() {
        return Objects.hash(usrId);
    }

    /**
     * Returns a string representation of this user security record.
     *
     * <p>The password hash is intentionally excluded from the output for
     * security reasons.</p>
     *
     * @return a formatted string containing user ID, names, and type
     */
    @Override
    public String toString() {
        return "UserSecurity{" +
                "usrId='" + usrId + '\'' +
                ", usrFirstName='" + usrFirstName + '\'' +
                ", usrLastName='" + usrLastName + '\'' +
                ", usrType='" + usrType + '\'' +
                '}';
    }
}
