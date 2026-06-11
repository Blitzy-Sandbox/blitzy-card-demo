package com.cardemo.model.entity;

import com.cardemo.model.enums.UserType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * JPA entity mapping the legacy AWS CardDemo security/user record onto the
 * PostgreSQL {@code user_security} table.
 *
 * <p>This entity is the Java&nbsp;25 / Spring Data JPA replacement for the VSAM
 * KSDS dataset {@code USRSEC}, whose fixed 80-byte record layout is defined by
 * the COBOL copybook {@code app/cpy/CSUSR01Y.cpy} ({@code 01 SEC-USER-DATA},
 * record length&nbsp;80). On the mainframe {@code USRSEC} was read by the online
 * sign-on program {@code COSGN00C} (keyed read on the 8-byte user id) and was
 * created, browsed, updated and deleted by the user-administration programs
 * {@code COUSR00C}&ndash;{@code COUSR03C}. The legacy access path was a single
 * keyed (KSDS) read/write on {@code SEC-USR-ID}, so this entity carries a
 * <strong>plain single-column primary key</strong> ({@link #secUsrId}); the
 * downstream repository is therefore
 * {@code JpaRepository<UserSecurity, String>}.</p>
 *
 * <h2>Original COBOL layout (CSUSR01Y.cpy &mdash; RECLN 80)</h2>
 * <pre>{@code
 * 01  SEC-USER-DATA.
 *     05  SEC-USR-ID               PIC X(08).
 *     05  SEC-USR-FNAME            PIC X(20).
 *     05  SEC-USR-LNAME            PIC X(20).
 *     05  SEC-USR-PWD              PIC X(08).
 *     05  SEC-USR-TYPE             PIC X(01).
 *     05  SEC-USR-FILLER           PIC X(23).
 * }</pre>
 *
 * <h2>The single permitted behavioral change &mdash; BCrypt (AAP §0.7.2 / C-003)</h2>
 * <p><strong>This entity carries the migration's one and only sanctioned
 * behavioral change.</strong> The legacy {@code SEC-USR-PWD PIC X(08)} field
 * stored an 8-character <em>plaintext</em> password. Constraint&nbsp;C-003
 * mandates that plaintext credential storage be replaced by a salted
 * <strong>BCrypt</strong> hash, which is the sole deviation from strict
 * byte-for-byte parity that the Minimal Change Clause permits. Consequently the
 * mapped column is <em>renamed</em> to {@code password_hash} and <em>widened</em>
 * from 8 to {@code length = 60} characters to accommodate a BCrypt digest
 * (the standard {@code $2a$}/{@code $2b$} 60-character encoding).</p>
 * <p>The sign-on <em>flow</em> itself is preserved exactly: where {@code COSGN00C}
 * compared the entered password to the stored plaintext, the migrated
 * {@code AuthenticationService} now BCrypt-verifies the entered password against
 * this hash. <strong>This entity performs no hashing or verification of its
 * own</strong>: it is a passive carrier of the already-encoded hash. BCrypt
 * encoding happens in the service/security layer ({@code AuthenticationService}
 * on verify, {@code UserAddService} on create) using the Spring Security
 * {@code PasswordEncoder}. No other field is hashed, encrypted or otherwise
 * altered, and no credential literal is ever stored in code (AAP §0.7.2).</p>
 *
 * <h2>Technology-substitution notes (Minimal Change Clause &mdash; AAP §0.7.1)</h2>
 * <ul>
 *   <li><strong>VSAM KSDS keyed access &rarr; JPA.</strong> The physically keyed
 *       {@code USRSEC} cluster (8-byte key) becomes a relational table whose
 *       primary key is {@link #secUsrId}. CICS keyed file control (online sign-on
 *       and user admin) is replaced by a Spring Data {@code UserSecurityRepository}
 *       ({@code JpaRepository<UserSecurity, String>}).</li>
 *   <li><strong>User id &rarr; {@link String} primary key.</strong>
 *       {@code SEC-USR-ID PIC X(08)} is a fixed 8-character (alphanumeric) id. It
 *       is kept as a {@link String} (PostgreSQL {@code VARCHAR(8)}), not a numeric
 *       type, so the exact 8-byte width and any leading characters are preserved,
 *       matching the keyed VSAM access. It is this entity's sole {@code @Id}.</li>
 *   <li><strong>Password &rarr; BCrypt hash.</strong> See the dedicated section
 *       above: {@code SEC-USR-PWD PIC X(08)} (plaintext) is widened to the
 *       {@code password_hash} {@code VARCHAR(60)} column holding a BCrypt digest.
 *       This is the single permitted behavioral change (C-003).</li>
 *   <li><strong>User type &rarr; {@link UserType} via 1-character converter.</strong>
 *       {@code SEC-USR-TYPE PIC X(01)} ({@code 'A'}&nbsp;=&nbsp;admin,
 *       {@code 'U'}&nbsp;=&nbsp;user) is modelled as the type-safe
 *       {@link UserType} enum but is <strong>persisted as the literal single
 *       character</strong> {@code 'A'}/{@code 'U'} through the nested
 *       {@link UserTypeConverter}. Persisting the 1-byte code (rather than the
 *       enum {@code name()} or {@code ordinal()}) preserves the exact one-byte
 *       {@code USRSEC} contract and the admin/user role semantics that drive
 *       admin-menu routing in {@code COADM01C}.</li>
 *   <li><strong>No decimal fields.</strong> No field originates from a COBOL
 *       {@code PIC} clause with decimal positions, so this entity intentionally
 *       contains <strong>no</strong> {@code float}, {@code double} or
 *       {@code BigDecimal} (AAP §0.7.3).</li>
 *   <li><strong>No optimistic locking.</strong> Per AAP §0.7.5, JPA
 *       {@code @Version} is applied <strong>only</strong> to {@code Account} and
 *       {@code Card} (the read-update programs {@code COACTUPC} and
 *       {@code COCRDUPC}). The {@code USRSEC} admin programs perform single-record
 *       reads/writes without a read-update snapshot comparison, so this entity
 *       carries <strong>no</strong> {@code @Version} column.</li>
 *   <li><strong>No associations / no Bean Validation.</strong> Per the Minimal
 *       Change Clause this is a pure persistence type: it declares exactly the
 *       five mapped record fields, models no JPA associations, and carries no
 *       Jakarta Bean Validation annotations (input validation lives in the
 *       request-DTO layer, AAP §0.4.2).</li>
 *   <li><strong>FILLER not materialized.</strong> The trailing
 *       {@code SEC-USR-FILLER PIC X(23)} is reserved padding that pads the record
 *       to its 80-byte length ({@code 8 + 20 + 20 + 8 + 1 + 23 = 80}); it carries
 *       no business data and is intentionally <strong>not</strong> mapped to a
 *       column. The 80-byte record length is documented here for
 *       external-contract reference only (AAP §0.7.2).</li>
 * </ul>
 *
 * <h2>Primary-key &amp; column-name contract</h2>
 * <p>The {@link Column} names declared below are authoritative for the data
 * layer. The Flyway {@code V1__create_schema.sql} {@code user_security} table
 * must declare {@code user_id} as the {@code VARCHAR(8)} primary key,
 * {@code first_name} and {@code last_name} as {@code VARCHAR(20)},
 * {@code password_hash} as {@code VARCHAR(60)} {@code NOT NULL}, and
 * {@code user_type} as {@code CHAR(1)}/{@code VARCHAR(1)} {@code NOT NULL}.
 * Because {@code password_hash} now stores a BCrypt digest (never the original
 * 8-character plaintext), the {@code V3} seed must insert BCrypt-hashed fixture
 * passwords (or the application must hash them at seed time).</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL source is read-only reference material
 * and is never copied into this repository.</p>
 */
@Entity
@Table(name = "user_security")
public class UserSecurity {

    /**
     * Primary key &mdash; the 8-character user id.
     *
     * <p>Migrated from {@code SEC-USR-ID PIC X(08)}. Kept as a {@link String}
     * (PostgreSQL {@code VARCHAR(8)}) rather than a numeric type so the exact
     * 8-byte width and any leading characters are preserved, matching the keyed
     * VSAM access used by {@code COSGN00C} sign-on and the
     * {@code COUSR00C}&ndash;{@code COUSR03C} admin programs. It is the table's
     * sole {@code @Id}, so the downstream repository is
     * {@code JpaRepository<UserSecurity, String>}.</p>
     */
    // SEC-USR-ID PIC X(08) -> 8-char user id, primary VSAM key -> String(8) (VARCHAR(8)) @Id
    @Id
    @Column(name = "user_id", length = 8, nullable = false)
    private String secUsrId;

    /**
     * The user's first name.
     *
     * <p>Migrated from {@code SEC-USR-FNAME PIC X(20)}: a 20-character text field
     * mapped to {@link String} (PostgreSQL {@code VARCHAR(20)}) with its length
     * preserved exactly.</p>
     */
    // SEC-USR-FNAME PIC X(20) -> 20-char first name -> String(20) (VARCHAR(20))
    @Column(name = "first_name", length = 20)
    private String secUsrFname;

    /**
     * The user's last name.
     *
     * <p>Migrated from {@code SEC-USR-LNAME PIC X(20)}: a 20-character text field
     * mapped to {@link String} (PostgreSQL {@code VARCHAR(20)}) with its length
     * preserved exactly.</p>
     */
    // SEC-USR-LNAME PIC X(20) -> 20-char last name -> String(20) (VARCHAR(20))
    @Column(name = "last_name", length = 20)
    private String secUsrLname;

    /**
     * The user's credential, stored as a <strong>BCrypt hash</strong>.
     *
     * <p>Migrated from {@code SEC-USR-PWD PIC X(08)} &mdash; the legacy 8-character
     * <em>plaintext</em> password. This field is the migration's
     * <strong>single permitted behavioral change</strong> (constraint C-003, AAP
     * §0.7.2): the column is renamed {@code password_hash} and widened to
     * {@code VARCHAR(60)} to hold a BCrypt digest. The entity is a passive carrier
     * of the already-encoded hash &mdash; <strong>it performs no hashing</strong>.
     * BCrypt encoding and verification are done in the service/security layer
     * ({@code UserAddService} on create, {@code AuthenticationService} on
     * sign-on). The value is {@code NOT NULL}: every user record must carry a
     * credential hash.</p>
     */
    // SEC-USR-PWD PIC X(08) -> legacy 8-char PLAINTEXT password; SINGLE PERMITTED BEHAVIORAL
    //   CHANGE (C-003): stored as a BCrypt hash (~60 chars) -> String, column renamed
    //   password_hash and widened to VARCHAR(60). Entity holds the hash only; no hashing here.
    @Column(name = "password_hash", length = 60, nullable = false)
    private String secUsrPwd;

    /**
     * The user's role/type ({@link UserType#ADMIN} or {@link UserType#USER}).
     *
     * <p>Migrated from {@code SEC-USR-TYPE PIC X(01)} ({@code 'A'} = admin,
     * {@code 'U'} = user). Modelled as the type-safe {@link UserType} enum but
     * <strong>persisted as the literal single character</strong> {@code 'A'} or
     * {@code 'U'} via the nested {@link UserTypeConverter}, so the stored value
     * stays one byte &mdash; preserving the exact {@code USRSEC} byte contract and
     * the role semantics used for admin/main-menu routing. The converter is
     * applied explicitly through {@code @Convert}; the enum {@code name()} and
     * {@code ordinal()} are deliberately never persisted.</p>
     */
    // SEC-USR-TYPE PIC X(01) -> 1-char role code 'A'/'U' -> UserType enum, persisted as the
    //   literal 1-char code via UserTypeConverter (NOT @Enumerated ORDINAL/STRING)
    @Convert(converter = UserTypeConverter.class)
    @Column(name = "user_type", length = 1, nullable = false)
    private UserType secUsrType;

    // SEC-USR-FILLER PIC X(23) -> reserved trailing padding to the 80-byte record length;
    //   carries no business data and is intentionally NOT materialized as a column.

    /**
     * Default no-argument constructor required by the JPA provider (Hibernate)
     * to instantiate the entity reflectively before populating its fields.
     */
    public UserSecurity() {
        // Intentionally empty: JPA/Hibernate instantiates then sets fields.
    }

    /**
     * Returns the primary-key user id ({@code SEC-USR-ID}).
     *
     * @return the 8-character user id, or {@code null} if unset
     */
    public String getSecUsrId() {
        return secUsrId;
    }

    /**
     * Sets the primary-key user id ({@code SEC-USR-ID}).
     *
     * @param secUsrId the 8-character user id to set
     */
    public void setSecUsrId(String secUsrId) {
        this.secUsrId = secUsrId;
    }

    /**
     * Returns the user's first name ({@code SEC-USR-FNAME}).
     *
     * @return the first name, or {@code null} if unset
     */
    public String getSecUsrFname() {
        return secUsrFname;
    }

    /**
     * Sets the user's first name ({@code SEC-USR-FNAME}).
     *
     * @param secUsrFname the first name to set (up to 20 characters)
     */
    public void setSecUsrFname(String secUsrFname) {
        this.secUsrFname = secUsrFname;
    }

    /**
     * Returns the user's last name ({@code SEC-USR-LNAME}).
     *
     * @return the last name, or {@code null} if unset
     */
    public String getSecUsrLname() {
        return secUsrLname;
    }

    /**
     * Sets the user's last name ({@code SEC-USR-LNAME}).
     *
     * @param secUsrLname the last name to set (up to 20 characters)
     */
    public void setSecUsrLname(String secUsrLname) {
        this.secUsrLname = secUsrLname;
    }

    /**
     * Returns the BCrypt password hash ({@code SEC-USR-PWD} &rarr;
     * {@code password_hash}).
     *
     * <p>The returned value is an opaque BCrypt digest, not a plaintext password;
     * verification is performed by the security layer, never by this entity.</p>
     *
     * @return the BCrypt password hash, or {@code null} if unset
     */
    public String getSecUsrPwd() {
        return secUsrPwd;
    }

    /**
     * Sets the BCrypt password hash ({@code SEC-USR-PWD} &rarr;
     * {@code password_hash}).
     *
     * <p>The supplied value must already be a BCrypt-encoded hash produced by the
     * service/security layer; this entity does <strong>not</strong> hash, encode
     * or validate the argument.</p>
     *
     * @param secUsrPwd the BCrypt password hash to set
     */
    public void setSecUsrPwd(String secUsrPwd) {
        this.secUsrPwd = secUsrPwd;
    }

    /**
     * Returns the user's role/type ({@code SEC-USR-TYPE}).
     *
     * @return the {@link UserType}, or {@code null} if unset
     */
    public UserType getSecUsrType() {
        return secUsrType;
    }

    /**
     * Sets the user's role/type ({@code SEC-USR-TYPE}).
     *
     * @param secUsrType the {@link UserType} to set
     */
    public void setSecUsrType(UserType secUsrType) {
        this.secUsrType = secUsrType;
    }

    /**
     * Identity-based equality keyed on the user id ({@link #secUsrId}).
     *
     * <p>Two {@code UserSecurity} instances are equal when they are of the exact
     * same class and share the same {@link #secUsrId}. The primary key alone
     * defines entity identity; the mutable name, credential and role fields are
     * deliberately excluded so equality stays stable across updates (and so the
     * password hash never participates in equality). Exact-class comparison
     * (rather than {@code instanceof}) is used so a proxy/subclass is not treated
     * as equal to a different concrete type.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code UserSecurity} with an equal
     *         user id
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
     * Hash code derived solely from {@link #secUsrId}, consistent with
     * {@link #equals(Object)}.
     *
     * @return the hash code of the user id
     */
    @Override
    public int hashCode() {
        return Objects.hash(secUsrId);
    }

    /**
     * Diagnostic representation of this user record.
     *
     * <p><strong>The password hash is never emitted.</strong> To avoid leaking
     * credential material into logs or diagnostics, {@link #secUsrPwd} is masked
     * as {@code "[PROTECTED]"} regardless of whether it is set; only the
     * non-sensitive identity, name and role fields are shown verbatim.</p>
     *
     * @return a human-readable description of this user record with the password
     *         hash masked
     */
    @Override
    public String toString() {
        return "UserSecurity{"
                + "secUsrId='" + secUsrId + '\''
                + ", secUsrFname='" + secUsrFname + '\''
                + ", secUsrLname='" + secUsrLname + '\''
                + ", secUsrPwd=[PROTECTED]"
                + ", secUsrType=" + secUsrType
                + '}';
    }

    /**
     * JPA {@link AttributeConverter} that persists a {@link UserType} as its
     * single-character COBOL storage code and reconstructs it on read.
     *
     * <p>This converter is the mechanism that keeps {@code user_type} a one-byte
     * value, faithfully reproducing the {@code SEC-USR-TYPE PIC X(01)} contract
     * ({@code 'A'} / {@code 'U'}). It is applied <em>explicitly</em> to
     * {@link UserSecurity#secUsrType} via {@code @Convert}; {@code autoApply}
     * is left at its default ({@code false}) so the conversion is opt-in rather
     * than registered globally for every {@link UserType} attribute.</p>
     */
    @Converter
    static class UserTypeConverter implements AttributeConverter<UserType, String> {

        /**
         * Converts a {@link UserType} to the single character stored in the
         * {@code user_type} column.
         *
         * @param attribute the entity attribute, may be {@code null}
         * @return {@code "A"} for {@link UserType#ADMIN}, {@code "U"} for
         *         {@link UserType#USER}, or {@code null} when {@code attribute}
         *         is {@code null}
         */
        @Override
        public String convertToDatabaseColumn(UserType attribute) {
            // String.valueOf(char) yields the 1-char code 'A' / 'U' (never name()/ordinal()).
            return attribute == null ? null : String.valueOf(attribute.getCode());
        }

        /**
         * Reconstructs a {@link UserType} from the single character read from the
         * {@code user_type} column.
         *
         * <p>A {@code null} or blank database value maps to {@code null}; any
         * other value is trimmed (the column is a single byte, but seed/import
         * text can be space-padded) and resolved through
         * {@link UserType#fromCode(String)}, which is case-sensitive and rejects
         * any code other than {@code 'A'} or {@code 'U'}.</p>
         *
         * @param dbData the raw column value, may be {@code null}
         * @return the resolved {@link UserType}, or {@code null} when
         *         {@code dbData} is {@code null} or blank
         * @throws IllegalArgumentException if {@code dbData} is non-blank but does
         *         not resolve to a known user type
         */
        @Override
        public UserType convertToEntityAttribute(String dbData) {
            return (dbData == null || dbData.isBlank()) ? null : UserType.fromCode(dbData.trim());
        }
    }
}
