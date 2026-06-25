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
package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity for the security/authentication user record.
 *
 * <p>Maps the COBOL {@code SEC-USER-DATA} structure (copybook
 * {@code app/cpy/CSUSR01Y.cpy}, record length 80) at source commit
 * {@code 27d6c6f} to the PostgreSQL {@code users} table. The legacy fixed-width
 * layout is:</p>
 *
 * <pre>
 * 01 SEC-USER-DATA.
 *    05 SEC-USR-ID      PIC X(08).   bytes 1-8   -&gt; user_id   CHAR(8) (primary key)
 *    05 SEC-USR-FNAME   PIC X(20).   bytes 9-28  -&gt; first_name VARCHAR(20)
 *    05 SEC-USR-LNAME   PIC X(20).   bytes 29-48 -&gt; last_name  VARCHAR(20)
 *    05 SEC-USR-PWD     PIC X(08).   bytes 49-56 -&gt; password   VARCHAR(60)
 *    05 SEC-USR-TYPE    PIC X(01).   byte  57    -&gt; user_type  CHAR(1) (A=admin, U=user)
 *    05 SEC-USR-FILLER  PIC X(23).   bytes 58-80 -&gt; (filler, never persisted)
 * </pre>
 *
 * <p>The {@code password} column is {@code VARCHAR(60)} rather than the legacy
 * {@code X(08)} width: the plaintext credential is widened to hold a BCrypt
 * hash per constraint C-003 (the single field that deviates from the COBOL
 * width). Hashing and verification are performed by the authentication/service
 * layer; this entity never derives or stores a plaintext value.</p>
 *
 * <p>{@code user_type} is preserved as a single-character {@link String} for
 * byte fidelity; role interpretation ({@code A} = admin, {@code U} = user) is a
 * Spring Security concern handled outside this entity.</p>
 *
 * <p>Column names, types, and lengths mirror {@code V1__create_schema.sql} table
 * {@code users} exactly so that Hibernate schema validation
 * ({@code ddl-auto: validate}) succeeds.</p>
 */
@Entity
@Table(name = "users")
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Primary key. COBOL {@code SEC-USR-ID PIC X(08)} mapped to a fixed-length
     * {@code CHAR(8)} column; not generated (assigned by the application). The
     * {@link JdbcTypeCode}{@code (SqlTypes.CHAR)} binding makes the Hibernate
     * {@code validate} schema check expect the JDBC {@code CHAR} type so it
     * matches the PostgreSQL {@code bpchar} column and the application boots
     * cleanly under {@code ddl-auto: validate}.
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", length = 8, columnDefinition = "char(8)")
    private String userId;

    /** COBOL {@code SEC-USR-FNAME PIC X(20)} -&gt; {@code first_name VARCHAR(20)}. */
    @Column(name = "first_name", length = 20)
    private String firstName;

    /** COBOL {@code SEC-USR-LNAME PIC X(20)} -&gt; {@code last_name VARCHAR(20)}. */
    @Column(name = "last_name", length = 20)
    private String lastName;

    /**
     * BCrypt password hash. COBOL {@code SEC-USR-PWD PIC X(08)} widened to
     * {@code VARCHAR(60)} per constraint C-003; never a plaintext value.
     */
    @Column(name = "password", length = 60)
    private String password;

    /**
     * Role discriminator. COBOL {@code SEC-USR-TYPE PIC X(01)} mapped to
     * {@code CHAR(1)} ({@code A} = admin, {@code U} = user). The
     * {@link JdbcTypeCode}{@code (SqlTypes.CHAR)} binding aligns this
     * {@code String} mapping with the fixed-length {@code CHAR(1)} column so
     * Hibernate schema validation accepts the PostgreSQL {@code bpchar} type.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_type", length = 1, columnDefinition = "char(1)")
    private String userType;

    /**
     * No-argument constructor required by the JPA provider.
     */
    public User() {
        // Required by JPA.
    }

    /**
     * Creates a fully populated user record.
     *
     * @param userId    the 8-character user identifier (primary key)
     * @param firstName the user's first name
     * @param lastName  the user's last name
     * @param password  the BCrypt password hash (never plaintext)
     * @param userType  the role discriminator ({@code A} = admin, {@code U} = user)
     */
    public User(String userId, String firstName, String lastName, String password, String userType) {
        this.userId = userId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.password = password;
        this.userType = userType;
    }

    /**
     * Returns the user identifier (primary key).
     *
     * @return the user identifier
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Sets the user identifier (primary key).
     *
     * @param userId the user identifier
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * Returns the user's first name.
     *
     * @return the first name
     */
    public String getFirstName() {
        return firstName;
    }

    /**
     * Sets the user's first name.
     *
     * @param firstName the first name
     */
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    /**
     * Returns the user's last name.
     *
     * @return the last name
     */
    public String getLastName() {
        return lastName;
    }

    /**
     * Sets the user's last name.
     *
     * @param lastName the last name
     */
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    /**
     * Returns the BCrypt password hash.
     *
     * @return the password hash
     */
    public String getPassword() {
        return password;
    }

    /**
     * Sets the BCrypt password hash. Callers must supply an already-hashed
     * value; plaintext credentials are never persisted.
     *
     * @param password the BCrypt password hash
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * Returns the role discriminator ({@code A} = admin, {@code U} = user).
     *
     * @return the user type
     */
    public String getUserType() {
        return userType;
    }

    /**
     * Sets the role discriminator ({@code A} = admin, {@code U} = user).
     *
     * @param userType the user type
     */
    public void setUserType(String userType) {
        this.userType = userType;
    }

    /**
     * Two users are equal when they share the same {@code userId} primary key.
     *
     * @param o the object to compare with
     * @return {@code true} if the other object is a {@code User} with an equal
     *     {@code userId}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof User)) {
            return false;
        }
        User other = (User) o;
        return Objects.equals(userId, other.userId);
    }

    /**
     * Hash code derived solely from the {@code userId} primary key.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(userId);
    }

    /**
     * Diagnostic representation that deliberately omits the {@code password}
     * field so the credential hash is never logged or otherwise exposed.
     *
     * @return a string containing the non-sensitive fields only
     */
    @Override
    public String toString() {
        return "User{"
            + "userId='" + userId + '\''
            + ", firstName='" + firstName + '\''
            + ", lastName='" + lastName + '\''
            + ", userType='" + userType + '\''
            + '}';
    }
}
