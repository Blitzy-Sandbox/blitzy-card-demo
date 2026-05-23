/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.awsm2.carddemo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.util.Objects;

/**
 * JPA {@link Entity} mapped to the {@code user_security} table (Flyway
 * migration {@code V010__create_user_security.sql}). This entity is the
 * Java target for the COBOL {@code SEC-USER-DATA} layout defined in
 * {@code app/cpy/CSUSR01Y.cpy} (RECLN = 80 bytes), and replaces the
 * mainframe VSAM KSDS cluster {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS}.
 *
 * <h2>Purpose</h2>
 * <p>Represents an authenticated user identity (administrator or regular
 * user) along with the credentials used to authenticate. Read by
 * {@code SignonService} (Java target for COBOL {@code COSGN00C}) at
 * signon time and read/written by {@code UserListService} /
 * {@code UserAddService} / {@code UserUpdateService} /
 * {@code UserDeleteService} (Java targets for COBOL
 * {@code COUSR00C}&ndash;{@code COUSR03C}) from the admin menu.
 *
 * <h2>SECURITY UPGRADE &mdash; BCrypt password storage (AAP &sect;0.1.1, &sect;0.7.1)</h2>
 * <p>The original COBOL source stores plaintext passwords in
 * {@code SEC-USR-PWD PIC X(08)} &mdash; exactly 8 plaintext characters
 * fixed-width inside the 80-byte SEC-USER-DATA record. The Java target
 * stores a <b>BCrypt hash</b> (60 characters) in the corresponding
 * {@code VARCHAR(60)} column. This is a deliberate, AAP-mandated
 * security improvement made within the scope of the COBOL &rarr; Java
 * migration to satisfy PCI-DSS at-rest credential-protection
 * requirements (AAP &sect;0.6.6). The Java {@code secUsrPwd} field is
 * widened from {@code length = 8} (the COBOL PIC clause) to
 * {@code length = 60} (the BCrypt output length) so that the
 * Spring Security {@code BCryptPasswordEncoder} (strength 12) hash
 * format
 * <pre>
 *     $2[abxy]$&lt;cost&gt;$&lt;22-char-salt&gt;&lt;31-char-hash&gt;  = 60 chars total
 * </pre>
 * fits exactly without truncation. Truncating the hash would silently
 * break authentication. Password hashing is performed in
 * {@code UserAddService} and {@code UserUpdateService}; this entity
 * stores only the already-hashed value and never participates in
 * plaintext password handling.
 *
 * <h2>Role discriminator &mdash; {@code sec_usr_type}</h2>
 * <p>{@link #secUsrType} is the role discriminator inherited from
 * the COBOL {@code SEC-USR-TYPE PIC X(01)} field. The COBOL business
 * rule (enforced implicitly in {@code COSGN00C.cbl} signon routing) is
 * that only two values are valid:
 * <ul>
 *   <li>{@code 'A'} &mdash; ADMIN: routes to the admin menu
 *       (COBOL {@code COADM01C}; Java {@code MenuController}
 *       {@code GET /api/menu/admin}).</li>
 *   <li>{@code 'U'} &mdash; USER: routes to the main menu
 *       (COBOL {@code COMEN01C}; Java {@code MenuController}
 *       {@code GET /api/menu/main}).</li>
 * </ul>
 * The V010 migration adds a defense-in-depth {@code CHECK} constraint
 * (
 * {@code chk_user_security_type CHECK (sec_usr_type IN ('A', 'U'))}
 * ) that rejects invalid values at write time. This entity does NOT
 * store roles or authorities directly &mdash; Spring Security's
 * {@code SecurityConfig} (in
 * {@code src/main/java/com/awsm2/carddemo/security/}) derives the
 * granted authorities from {@code secUsrType}:
 * {@code 'A'} &rarr; {@code "ROLE_ADMIN"};
 * {@code 'U'} &rarr; {@code "ROLE_USER"}.
 *
 * <h2>Source provenance (per AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL copybook:</b> {@code app/cpy/CSUSR01Y.cpy} &mdash;
 *       80-byte fixed-width record layout with 5 business fields
 *       plus a 23-byte trailing FILLER. The FILLER has no relational
 *       equivalent and is omitted from this entity.</li>
 *   <li><b>VSAM cluster:</b> {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS}
 *       &mdash; KEYS(8,0), RECORDSIZE(80,80), REUSE, INDEXED
 *       (per {@code app/jcl/DUSRSECJ.jcl}:L62-L73 and
 *       {@code app/catlg/LISTCAT.txt}).</li>
 *   <li><b>COBOL consumers:</b>
 *       {@code app/cbl/COSGN00C.cbl} (signon authentication),
 *       {@code app/cbl/COUSR00C.cbl} (user list),
 *       {@code app/cbl/COUSR01C.cbl} (user add),
 *       {@code app/cbl/COUSR02C.cbl} (user update),
 *       {@code app/cbl/COUSR03C.cbl} (user delete) &mdash; replaced
 *       respectively by Java {@code SignonService},
 *       {@code UserListService}, {@code UserAddService},
 *       {@code UserUpdateService}, and {@code UserDeleteService}.</li>
 *   <li><b>JCL DD allocation:</b> {@code app/jcl/DUSRSECJ.jcl}
 *       (IDCAMS DEFINE CLUSTER + in-stream REPRO of 10 default
 *       users). Reference-data seeding is performed by the companion
 *       Flyway migration {@code V015__seed_default_users.sql}, which
 *       embeds pre-computed BCrypt(v2b, strength=12) hashes derived
 *       offline from the original COBOL plaintext defaults
 *       ({@code 'PASSWORDA'} for admins, {@code 'PASSWORDU'} for
 *       regular users).</li>
 *   <li><b>Flyway DDL:</b>
 *       {@code src/main/resources/db/migration/V010__create_user_security.sql}.</li>
 * </ul>
 *
 * <h2>Field-to-column mapping</h2>
 * <table>
 *   <caption>COBOL CSUSR01Y.cpy &harr; PostgreSQL user_security</caption>
 *   <tr><th>COBOL field</th><th>PIC clause</th><th>Java field</th>
 *       <th>JPA column</th><th>Notes</th></tr>
 *   <tr><td>SEC-USR-ID</td><td>X(08)</td><td>{@link #secUsrId}</td>
 *       <td>{@code sec_usr_id VARCHAR(8) PK}</td>
 *       <td>VSAM KSDS primary key (RKP=0, KEYLEN=8)</td></tr>
 *   <tr><td>SEC-USR-FNAME</td><td>X(20)</td><td>{@link #secUsrFname}</td>
 *       <td>{@code sec_usr_fname VARCHAR(20)}</td>
 *       <td>Display-only first name</td></tr>
 *   <tr><td>SEC-USR-LNAME</td><td>X(20)</td><td>{@link #secUsrLname}</td>
 *       <td>{@code sec_usr_lname VARCHAR(20)}</td>
 *       <td>Display-only last name</td></tr>
 *   <tr><td>SEC-USR-PWD</td><td>X(08) &rarr; BCrypt</td>
 *       <td>{@link #secUsrPwd}</td>
 *       <td>{@code sec_usr_pwd VARCHAR(60)}</td>
 *       <td><b>BCrypt hash (length widened from 8 to 60)</b></td></tr>
 *   <tr><td>SEC-USR-TYPE</td><td>X(01)</td><td>{@link #secUsrType}</td>
 *       <td>{@code sec_usr_type CHAR(1)}</td>
 *       <td>{@code CHECK ('A','U')} defense-in-depth</td></tr>
 *   <tr><td>SEC-USR-FILLER</td><td>X(23)</td><td>&mdash;</td>
 *       <td>&mdash;</td>
 *       <td>OMITTED (relational layouts have no positional padding)</td></tr>
 * </table>
 *
 * <h2>JPA contract</h2>
 * <ul>
 *   <li>{@code @Entity} + {@code @Table(name = "user_security")}
 *       &mdash; table name matches V010 DDL exactly so that Hibernate
 *       {@code ddl-auto: validate} accepts this entity at startup.</li>
 *   <li>{@code @Id} on {@link #secUsrId} &mdash; the entity's primary
 *       key; no {@code @GeneratedValue} because the identifier
 *       originates upstream (administratively assigned, e.g.,
 *       {@code "ADMIN001"}, {@code "USER0001"}) and must be preserved
 *       verbatim.</li>
 *   <li>Implements {@link Serializable} with an explicit
 *       {@code serialVersionUID} so instances can cross persistence-
 *       context boundaries, Spring Session caches, and JPA L2
 *       caches deterministically.</li>
 *   <li><b>No {@code @Version}</b> &mdash; the {@code user_security}
 *       table does not require optimistic locking; concurrent updates
 *       are handled by {@code @Transactional} boundaries in the
 *       user-admin service layer (AAP &sect;0.7.1).</li>
 *   <li><b>No JSON-binding annotations</b> &mdash; this entity is a
 *       pure persistence model. REST DTOs (e.g., {@code UserAddDto},
 *       {@code UserUpdateDto}) under {@code com.awsm2.carddemo.dto}
 *       carry the API contracts.</li>
 *   <li><b>No business logic, no AWS SDK, no Lombok</b> &mdash;
 *       enforced by AAP &sect;0.7.1 (one-to-one COBOL-to-Java service
 *       mapping; AWS adapters isolate SDK calls; explicit code without
 *       generated boilerplate).</li>
 * </ul>
 *
 * <h2>PCI-DSS &mdash; password is never logged</h2>
 * <p>The {@link #toString()} implementation intentionally OMITS the
 * {@code secUsrPwd} field so that a stray {@code log.info(user)} or
 * implicit string concatenation cannot leak the BCrypt hash into
 * CloudWatch Logs or OpenSearch. The hash alone is not as sensitive as
 * plaintext, but the AAP &sect;0.6.6 PCI-DSS posture mandates that no
 * credential material appears in application logs. Tests verify this
 * omission.
 *
 * @see com.awsm2.carddemo.repository.UserSecurityRepository for
 *      Spring Data JPA access patterns
 */
@Entity
@Table(name = "user_security")
public class UserSecurity implements Serializable {

    /**
     * Serial-version UID for this JPA entity. Kept stable across
     * minor field additions so that Hibernate L2 cache entries and
     * Spring Session data survive non-breaking entity evolution.
     * Bump this value if (and only if) the wire/cache layout changes
     * incompatibly (e.g., a field removed, renamed, or its type
     * changed in a way that breaks Java {@link Serializable}
     * round-trip).
     */
    private static final long serialVersionUID = 1L;

    // -------------------------------------------------------------------------
    // Field declarations
    //
    // Order matches the COBOL copybook (CSUSR01Y.cpy) line order so that the
    // Java source remains visually parallel to the source-of-truth COBOL
    // record layout. This eases code review for COBOL SMEs during the
    // parallel-run validation window.
    // -------------------------------------------------------------------------

    // COBOL: CSUSR01Y.cpy:L18 SEC-USR-ID PIC X(08) - primary key, user identifier
    // Maps to V010 column: sec_usr_id VARCHAR(8) NOT NULL (pk_user_security)
    /**
     * 8-character alphanumeric user identifier. Primary key. Sample
     * values from the V015 seed migration include {@code "ADMIN001"}
     * (administrator) and {@code "USER0001"} (regular user). Stored as
     * a VSAM KSDS key (RKP=0, KEYLEN=8 per
     * {@code app/catlg/LISTCAT.txt}) in the COBOL source and as a
     * {@code VARCHAR(8)} PRIMARY KEY in the PostgreSQL target.
     * {@code @Id} is on the field (per JPA best practice for single-PK
     * string keys); no {@code @GeneratedValue} because the identifier
     * is administratively assigned and must be preserved verbatim.
     */
    @Id
    @Column(name = "sec_usr_id", nullable = false, length = 8)
    private String secUsrId;

    // COBOL: CSUSR01Y.cpy:L19 SEC-USR-FNAME PIC X(20) - display-only first name
    // Maps to V010 column: sec_usr_fname VARCHAR(20) NOT NULL
    /**
     * User first name. 20-character fixed-width in COBOL, stored
     * TRIMMED of trailing padding spaces in PostgreSQL (idiomatic
     * relational storage). Display-only field; not used for
     * authentication or authorization decisions.
     */
    @Column(name = "sec_usr_fname", nullable = false, length = 20)
    private String secUsrFname;

    // COBOL: CSUSR01Y.cpy:L20 SEC-USR-LNAME PIC X(20) - display-only last name
    // Maps to V010 column: sec_usr_lname VARCHAR(20) NOT NULL
    /**
     * User last name. 20-character fixed-width in COBOL, stored
     * TRIMMED of trailing padding spaces in PostgreSQL. Display-only
     * field; not used for authentication or authorization decisions.
     */
    @Column(name = "sec_usr_lname", nullable = false, length = 20)
    private String secUsrLname;

    // COBOL: CSUSR01Y.cpy:L21 SEC-USR-PWD PIC X(08) [plaintext in COBOL]
    // SECURITY UPGRADE per AAP §0.1.1, §0.7.1: stored as BCrypt hash (60 chars).
    // length widened from 8 to 60 to fit Spring Security BCryptPasswordEncoder output.
    // Hashing performed in UserAddService / UserUpdateService via BCryptPasswordEncoder(strength=12).
    // Maps to V010 column: sec_usr_pwd VARCHAR(60) NOT NULL
    /**
     * <b>BCrypt password hash &mdash; SECURITY UPGRADE from COBOL
     * plaintext per AAP &sect;0.1.1 and &sect;0.7.1.</b>
     *
     * <p>The COBOL source field {@code SEC-USR-PWD PIC X(08)} stored
     * exactly 8 characters of plaintext password. The Java target
     * column {@code sec_usr_pwd} is widened to {@code VARCHAR(60)} so
     * it can hold a Spring Security BCrypt hash:
     * <pre>
     *     $2[abxy]$&lt;cost&gt;$&lt;22-char-salt&gt;&lt;31-char-hash&gt; = 60 chars
     * </pre>
     *
     * <p><b>Invariants enforced upstream (in {@code UserAddService}
     * and {@code UserUpdateService}):</b>
     * <ul>
     *   <li>Plaintext passwords MUST be hashed via
     *       {@code BCryptPasswordEncoder} (strength 12, per AAP
     *       &sect;0.7.1 PCI-DSS requirements) before being assigned
     *       to this field.</li>
     *   <li>NEVER assign a plaintext password to this field.</li>
     *   <li>NEVER store fewer than 60 characters here &mdash;
     *       truncation would silently break authentication on the
     *       next {@code SignonService} lookup.</li>
     * </ul>
     *
     * <p>This field is intentionally OMITTED from
     * {@link #toString()} so the hash cannot leak into CloudWatch
     * Logs or OpenSearch via a stray log statement (AAP &sect;0.6.6
     * PCI-DSS).
     */
    @Column(name = "sec_usr_pwd", nullable = false, length = 60)
    private String secUsrPwd;

    // COBOL: CSUSR01Y.cpy:L22 SEC-USR-TYPE PIC X(01) - 'A'=admin, 'U'=user
    // V010 CHECK constraint (chk_user_security_type) enforces sec_usr_type IN ('A','U').
    // Maps to V010 column: sec_usr_type CHAR(1) NOT NULL
    /**
     * Role discriminator. Exactly one character:
     * <ul>
     *   <li>{@code 'A'} &mdash; ADMIN (routes to COBOL {@code COADM01C}
     *       / Java admin menu)</li>
     *   <li>{@code 'U'} &mdash; USER (routes to COBOL
     *       {@code COMEN01C} / Java main menu)</li>
     * </ul>
     * The V010 migration adds the {@code chk_user_security_type
     * CHECK (sec_usr_type IN ('A', 'U'))} constraint as a
     * defense-in-depth safeguard; the COBOL programs never validated
     * this value at the data layer, but corrupted or unknown values
     * would silently break signon routing.
     *
     * <p>This field does NOT carry Spring Security authorities
     * directly &mdash; the mapping from {@code 'A'} / {@code 'U'} to
     * {@code "ROLE_ADMIN"} / {@code "ROLE_USER"} is performed in
     * {@code SecurityConfig}'s {@code UserDetailsService}
     * implementation. The {@code columnDefinition = "CHAR(1)"}
     * matches V010's {@code char(1)} column type exactly so Hibernate
     * {@code ddl-auto: validate} accepts this entity.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "sec_usr_type", nullable = false, length = 1, columnDefinition = "CHAR(1)")
    private String secUsrType;

    // -------------------------------------------------------------------------
    // Constructors
    //
    // 1. No-arg constructor (required by JPA / Hibernate proxy creation).
    // 2. All-args constructor accepting the 5 source fields in COBOL order
    //    (id, fname, lname, pwd, type). Useful in tests and seed fixtures
    //    where the caller has all fields in hand.
    // -------------------------------------------------------------------------

    /**
     * Default no-arg constructor required by the JPA specification
     * for entity proxy creation by Hibernate. Leaves all fields at
     * their Java defaults (all {@code null} for {@code String}
     * fields) so that Hibernate can populate them from a result set.
     * Application code SHOULD prefer the all-args constructor when
     * building new entities programmatically.
     */
    public UserSecurity() {
        // intentionally empty - JPA requires a public/protected no-arg constructor
    }

    /**
     * All-args constructor for programmatic entity creation. Accepts
     * the 5 source fields in COBOL declaration order (id, fname,
     * lname, pwd, type) per AAP &sect;0.7.3 refactor discipline,
     * which keeps the Java source visually parallel to the COBOL
     * copybook.
     *
     * <p><b>Important:</b> the {@code secUsrPwd} argument MUST already
     * be a BCrypt hash &mdash; this constructor does not perform any
     * hashing. Plaintext-to-hash conversion is the responsibility of
     * {@code UserAddService} and {@code UserUpdateService} (per AAP
     * &sect;0.7.1).
     *
     * @param secUsrId    8-character user identifier (PK; e.g.,
     *                    {@code "ADMIN001"} or {@code "USER0001"})
     * @param secUsrFname user first name (max 20 characters)
     * @param secUsrLname user last name (max 20 characters)
     * @param secUsrPwd   BCrypt hash of the user's password
     *                    (exactly 60 characters; NEVER plaintext)
     * @param secUsrType  role discriminator: {@code "A"} for ADMIN
     *                    or {@code "U"} for USER
     */
    public UserSecurity(String secUsrId,
                        String secUsrFname,
                        String secUsrLname,
                        String secUsrPwd,
                        String secUsrType) {
        this.secUsrId = secUsrId;
        this.secUsrFname = secUsrFname;
        this.secUsrLname = secUsrLname;
        this.secUsrPwd = secUsrPwd;
        this.secUsrType = secUsrType;
    }

    // -------------------------------------------------------------------------
    // Accessors (getters and setters)
    //
    // Plain JavaBean accessors, one per field. Required by Hibernate's
    // property access mode and consumed by Spring Data JPA derived queries,
    // Jackson serialization at the DTO boundary, and the JPA validation
    // framework.
    // -------------------------------------------------------------------------

    /**
     * @return the 8-character user identifier (primary key)
     */
    public String getSecUsrId() {
        return secUsrId;
    }

    /**
     * @param secUsrId the 8-character user identifier to set
     */
    public void setSecUsrId(String secUsrId) {
        this.secUsrId = secUsrId;
    }

    /**
     * @return the user's first name (display-only)
     */
    public String getSecUsrFname() {
        return secUsrFname;
    }

    /**
     * @param secUsrFname the user's first name (max 20 characters)
     */
    public void setSecUsrFname(String secUsrFname) {
        this.secUsrFname = secUsrFname;
    }

    /**
     * @return the user's last name (display-only)
     */
    public String getSecUsrLname() {
        return secUsrLname;
    }

    /**
     * @param secUsrLname the user's last name (max 20 characters)
     */
    public void setSecUsrLname(String secUsrLname) {
        this.secUsrLname = secUsrLname;
    }

    /**
     * Returns the stored BCrypt password hash (60 characters).
     *
     * <p><b>WARNING:</b> the return value is credential material
     * even though it is hashed. Callers MUST NOT log it or include
     * it in HTTP responses. The intended consumers are:
     * <ul>
     *   <li>{@code SignonService} &mdash; passes the hash to
     *       {@code BCryptPasswordEncoder.matches(plaintext, hash)}
     *       for authentication.</li>
     *   <li>{@code UserAddService} / {@code UserUpdateService}
     *       &mdash; persists a freshly-generated hash to this field.</li>
     * </ul>
     *
     * @return the BCrypt password hash
     */
    public String getSecUsrPwd() {
        return secUsrPwd;
    }

    /**
     * Stores a BCrypt password hash. The argument MUST already be a
     * 60-character BCrypt hash; plaintext-to-hash conversion is the
     * responsibility of {@code UserAddService} and
     * {@code UserUpdateService} (per AAP &sect;0.7.1). Storing any
     * other value will break authentication.
     *
     * @param secUsrPwd the 60-character BCrypt hash to set
     */
    public void setSecUsrPwd(String secUsrPwd) {
        this.secUsrPwd = secUsrPwd;
    }

    /**
     * @return the role discriminator ({@code "A"} for ADMIN or
     *         {@code "U"} for USER)
     */
    public String getSecUsrType() {
        return secUsrType;
    }

    /**
     * @param secUsrType the role discriminator to set
     *                   (must be {@code "A"} or {@code "U"} &mdash;
     *                   enforced by V010's
     *                   {@code chk_user_security_type} CHECK constraint)
     */
    public void setSecUsrType(String secUsrType) {
        this.secUsrType = secUsrType;
    }

    // -------------------------------------------------------------------------
    // equals / hashCode / toString
    //
    // equals/hashCode follow the JPA recommended contract: based on the
    // primary-key field only. This contract is STABLE across the entity
    // lifecycle states (transient, managed, detached, removed).
    //
    // toString is non-sensitive: explicitly OMITS the BCrypt password hash
    // per PCI-DSS (AAP §0.6.6) so that a stray log statement cannot leak
    // credential material into CloudWatch Logs or OpenSearch.
    // -------------------------------------------------------------------------

    /**
     * Equality is defined on the primary key ({@link #secUsrId})
     * only, matching the standard JPA entity contract. Two
     * {@code UserSecurity} instances are equal iff they have the
     * same {@code secUsrId} value (both {@code null} is treated as
     * equal &mdash; common during transient-state comparisons within
     * a test fixture but should not occur in persisted state).
     *
     * @param o the reference object with which to compare
     * @return {@code true} if this object is the same as the
     *         {@code o} argument; {@code false} otherwise
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserSecurity)) {
            return false;
        }
        UserSecurity that = (UserSecurity) o;
        return Objects.equals(secUsrId, that.secUsrId);
    }

    /**
     * Hash code derived from {@link #secUsrId} only, consistent
     * with the {@link #equals(Object)} contract above. Safe for use
     * as a hash-set or hash-map key once {@code secUsrId} has been
     * assigned (which is required before
     * {@code EntityManager.persist} by virtue of the
     * {@code nullable = false} primary-key constraint).
     *
     * @return the hash-code value for this object
     */
    @Override
    public int hashCode() {
        return Objects.hash(secUsrId);
    }

    /**
     * String representation suitable for log statements and debug
     * output.
     *
     * <p><b>PCI-DSS compliance (AAP &sect;0.6.6, &sect;0.7.1):</b>
     * the {@link #secUsrPwd} BCrypt hash is intentionally OMITTED
     * from this output so that {@code log.info(user)} or implicit
     * string concatenation cannot leak credential material into
     * CloudWatch Logs or OpenSearch. Even though a BCrypt hash is
     * computationally infeasible to reverse, the AAP &sect;0.6.6
     * PCI-DSS posture mandates that NO credential material appears
     * in application logs.
     *
     * @return a non-sensitive string representation of this entity
     */
    @Override
    public String toString() {
        return "UserSecurity{"
                + "secUsrId='" + secUsrId + '\''
                + ", secUsrFname='" + secUsrFname + '\''
                + ", secUsrLname='" + secUsrLname + '\''
                + ", secUsrType='" + secUsrType + '\''
                // NOTE: secUsrPwd is intentionally omitted per PCI-DSS
                // (AAP §0.6.6) and AAP §0.7.1 (no credential material in logs).
                + '}';
    }
}
