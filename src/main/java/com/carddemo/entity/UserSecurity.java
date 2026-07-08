/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA entity representing a single security user in the file-based
 * {@code USRSEC} authentication store.
 *
 * <p>This class is a field-by-field migration of the legacy COBOL copybook
 * {@code CSUSR01Y} (record structure {@code SEC-USER-DATA}, fixed record
 * length {@code 80} bytes; referenced at source commit SHA {@code 27d6c6f}).
 * The file-based authentication model is preserved intact — RACF is not
 * introduced by this migration.</p>
 *
 * <p>The entity backs the sign-on flow (originally COBOL program
 * {@code COSGN00C}) and the user administration CRUD screens (originally
 * {@code COUSR00C} through {@code COUSR03C}). It is a pure persistence POJO:
 * it deliberately carries <em>no</em> business logic, <em>no</em> entity
 * relationships, and <em>no</em> password hashing. Password hashing and
 * verification are the sole responsibility of the security layer — a
 * {@code BCryptPasswordEncoder} wired in {@code SecurityConfig} and consumed
 * by {@code SignonService} — never this entity.</p>
 *
 * <h2>Field mapping ({@code CSUSR01Y} &rarr; table {@code user_security})</h2>
 * <table>
 *   <caption>SEC-USER-DATA to user_security column mapping</caption>
 *   <tr><th>COBOL field</th><th>PIC</th><th>Column</th><th>SQL type</th></tr>
 *   <tr><td>SEC-USR-ID</td><td>X(08)</td><td>sec_usr_id</td><td>VARCHAR(8), PK</td></tr>
 *   <tr><td>SEC-USR-FNAME</td><td>X(20)</td><td>sec_usr_fname</td><td>VARCHAR(20)</td></tr>
 *   <tr><td>SEC-USR-LNAME</td><td>X(20)</td><td>sec_usr_lname</td><td>VARCHAR(20)</td></tr>
 *   <tr><td>SEC-USR-PWD</td><td>X(08)</td><td>sec_usr_pwd</td><td>VARCHAR(60) &mdash; widened</td></tr>
 *   <tr><td>SEC-USR-TYPE</td><td>X(01)</td><td>sec_usr_type</td><td>VARCHAR(1)</td></tr>
 *   <tr><td>SEC-USR-FILLER</td><td>X(23)</td><td>&mdash;</td><td>not mapped (trailing padding)</td></tr>
 * </table>
 *
 * <p>Byte-origin sanity check against the copybook:
 * {@code 8 + 20 + 20 + 8 + 1 + 23 = 80}.</p>
 *
 * <p><strong>Deviation &mdash; password column widening (Constraint C-003 /
 * Decision Log D-002):</strong> the legacy {@code SEC-USR-PWD} field is
 * {@code PIC X(08)} (an 8-character plaintext password). This migration
 * upgrades plaintext passwords to BCrypt hashes, which are always 60
 * characters long. The {@code sec_usr_pwd} column is therefore intentionally
 * widened to {@code VARCHAR(60)}, diverging from the literal copybook width.
 * This is an explicitly logged deviation from byte-for-byte copybook fidelity;
 * the column length here must stay in lock-step with the Flyway
 * {@code V1__schema.sql} definition so that {@code hibernate.ddl-auto:
 * validate} succeeds.</p>
 *
 * <p>The table is named {@code user_security} (rather than the more literal
 * {@code user}) because {@code user} is a reserved word in PostgreSQL/SQL and
 * would require quoting on every reference.</p>
 */
@Entity
@Table(name = "user_security")
public class UserSecurity {

    /**
     * Primary key: the eight-character user identifier.
     *
     * <p>Maps COBOL {@code SEC-USR-ID PIC X(08)}. This is a natural key — no
     * {@code @GeneratedValue} is applied — because user IDs are assigned by
     * administrators exactly as in the legacy {@code USRSEC} file. The
     * corresponding repository declares
     * {@code JpaRepository<UserSecurity, String>}.</p>
     */
    @Id
    @Column(name = "sec_usr_id", length = 8, nullable = false)
    private String secUsrId;

    /**
     * The user's first name. Maps COBOL {@code SEC-USR-FNAME PIC X(20)}.
     */
    @Column(name = "sec_usr_fname", length = 20)
    private String secUsrFname;

    /**
     * The user's last name. Maps COBOL {@code SEC-USR-LNAME PIC X(20)}.
     */
    @Column(name = "sec_usr_lname", length = 20)
    private String secUsrLname;

    /**
     * The BCrypt password hash.
     *
     * <p>Maps COBOL {@code SEC-USR-PWD PIC X(08)} but is WIDENED to
     * {@code VARCHAR(60)} (Constraint C-003 / Decision Log D-002) to hold a
     * BCrypt hash. This field stores the hash string only; hashing and
     * verification are performed entirely by the security layer, never by this
     * entity.</p>
     */
    @Column(name = "sec_usr_pwd", length = 60)
    private String secUsrPwd;

    /**
     * The role indicator. Maps COBOL {@code SEC-USR-TYPE PIC X(01)}:
     * {@code 'A'} denotes an admin user and {@code 'U'} denotes a regular
     * user. Retained as a single-character {@code String} (no enum
     * conversion) to avoid scope expansion (Gate 7).
     */
    @Column(name = "sec_usr_type", length = 1)
    private String secUsrType;

    /**
     * Default no-argument constructor required by the JPA specification.
     */
    public UserSecurity() {
        // Intentionally empty: JPA requires a public/protected no-arg constructor.
    }

    /**
     * Returns the eight-character user identifier (primary key).
     *
     * @return the user ID, mapping COBOL {@code SEC-USR-ID}
     */
    public String getSecUsrId() {
        return secUsrId;
    }

    /**
     * Sets the eight-character user identifier (primary key).
     *
     * @param secUsrId the user ID, mapping COBOL {@code SEC-USR-ID}
     */
    public void setSecUsrId(String secUsrId) {
        this.secUsrId = secUsrId;
    }

    /**
     * Returns the user's first name.
     *
     * @return the first name, mapping COBOL {@code SEC-USR-FNAME}
     */
    public String getSecUsrFname() {
        return secUsrFname;
    }

    /**
     * Sets the user's first name.
     *
     * @param secUsrFname the first name, mapping COBOL {@code SEC-USR-FNAME}
     */
    public void setSecUsrFname(String secUsrFname) {
        this.secUsrFname = secUsrFname;
    }

    /**
     * Returns the user's last name.
     *
     * @return the last name, mapping COBOL {@code SEC-USR-LNAME}
     */
    public String getSecUsrLname() {
        return secUsrLname;
    }

    /**
     * Sets the user's last name.
     *
     * @param secUsrLname the last name, mapping COBOL {@code SEC-USR-LNAME}
     */
    public void setSecUsrLname(String secUsrLname) {
        this.secUsrLname = secUsrLname;
    }

    /**
     * Returns the stored BCrypt password hash.
     *
     * @return the BCrypt hash string, mapping COBOL {@code SEC-USR-PWD}
     *         (widened per C-003 / D-002)
     */
    public String getSecUsrPwd() {
        return secUsrPwd;
    }

    /**
     * Sets the stored BCrypt password hash. The caller is responsible for
     * supplying an already-hashed value; this entity never hashes.
     *
     * @param secUsrPwd the BCrypt hash string, mapping COBOL {@code SEC-USR-PWD}
     */
    public void setSecUsrPwd(String secUsrPwd) {
        this.secUsrPwd = secUsrPwd;
    }

    /**
     * Returns the role indicator ({@code 'A'} = admin, {@code 'U'} = regular).
     *
     * @return the single-character role code, mapping COBOL {@code SEC-USR-TYPE}
     */
    public String getSecUsrType() {
        return secUsrType;
    }

    /**
     * Sets the role indicator ({@code 'A'} = admin, {@code 'U'} = regular).
     *
     * @param secUsrType the single-character role code, mapping COBOL
     *                   {@code SEC-USR-TYPE}
     */
    public void setSecUsrType(String secUsrType) {
        this.secUsrType = secUsrType;
    }
}
