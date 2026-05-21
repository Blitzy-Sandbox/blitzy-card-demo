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
package com.aws.carddemo.entity;

import java.util.Objects;

/**
 * JPA entity that replaces the COBOL {@code USRSEC} VSAM KSDS file's
 * {@code SEC-USER-DATA} record described by {@code app/cpy/CSUSR01Y.cpy}.
 *
 * <h2>COBOL Provenance — CSUSR01Y.cpy</h2>
 *
 * <p>The original copybook layout is a fixed-width 80-byte record:
 * <pre>
 *   01 SEC-USER-DATA.
 *      05 SEC-USR-ID         PIC X(08).   --&gt; {@link #userId}      (primary key)
 *      05 SEC-USR-FNAME      PIC X(20).   --&gt; {@link #firstName}
 *      05 SEC-USR-LNAME      PIC X(20).   --&gt; {@link #lastName}
 *      05 SEC-USR-PWD        PIC X(08).   --&gt; {@link #password}    (BCrypt hash, not plaintext)
 *      05 SEC-USR-TYPE       PIC X(01).   --&gt; {@link #userType}    ('U' or 'A')
 *      05 FILLER             PIC X(23).
 * </pre>
 *
 * <h2>Java Migration Additions</h2>
 *
 * <ul>
 *   <li>{@link #password} stores the <strong>BCrypt hash</strong> (60 characters)
 *       instead of the plaintext {@code PIC X(08)} password the COBOL original held.
 *       Per AAP §0.10.5 "No plaintext credentials in any configuration file".</li>
 *   <li>{@link #locked} is a Java-migration addition (industry-standard account
 *       lockout); no equivalent column existed in the COBOL record.</li>
 *   <li>{@link #version} is a JPA {@code @Version} field for optimistic locking —
 *       the Java replacement for COBOL's before/after-image record comparison.</li>
 * </ul>
 *
 * <h2>Design Note — Stub Status</h2>
 *
 * <p>This class is a <strong>minimum-viable POJO</strong> created to satisfy
 * {@link com.aws.carddemo.service.AuthenticationService} compilation and the
 * authentication test suite. Subsequent migration agents (REFACTOR flavor) will
 * add JPA annotations ({@code @Entity}, {@code @Id}, {@code @Column},
 * {@code @Version}), Bean Validation constraints, and a proper equals/hashCode
 * contract once the entity is wired into the Hibernate {@code SessionFactory}.
 *
 * <h2>Security — toString() Excludes Password</h2>
 *
 * <p>The {@link #toString()} implementation deliberately omits {@link #password}
 * to prevent accidental credential disclosure when entity instances are logged or
 * printed during debugging (AAP §0.10.5 "No financial data written to logs at any
 * level" — applied to credentials).
 *
 * @see com.aws.carddemo.service.AuthenticationService
 * @see com.aws.carddemo.repository.UserSecurityRepository
 */
public class SecurityUser {

    /**
     * 8-character user identifier — the COBOL {@code SEC-USR-ID PIC X(08)} field
     * and the JPA primary key. Matches the {@code UserSecurityRepository<SecurityUser,
     * String>} ID parameter.
     */
    private String userId;

    /** 20-character first name — COBOL {@code SEC-USR-FNAME PIC X(20)}. */
    private String firstName;

    /** 20-character last name — COBOL {@code SEC-USR-LNAME PIC X(20)}. */
    private String lastName;

    /**
     * BCrypt-hashed password (60 characters: {@code $2a$10$<22-char-salt><31-char-hash>}).
     *
     * <p><strong>Never</strong> the plaintext password. The Java migration replaces
     * the COBOL {@code SEC-USR-PWD PIC X(08)} plaintext storage with BCrypt hashing
     * per AAP §0.10.5. The production
     * {@link com.aws.carddemo.service.AuthenticationService} verifies a candidate
     * plaintext against this field via {@code passwordEncoder.matches(plaintext, hash)}.
     */
    private String password;

    /**
     * 1-character user type — COBOL {@code SEC-USR-TYPE PIC X(01)}.
     * <ul>
     *   <li>{@code "U"} — regular user (drives COBOL {@code XCTL COMEN01C}; Java {@code MAIN_MENU} route)</li>
     *   <li>{@code "A"} — admin user (drives COBOL {@code XCTL COADM01C}; Java {@code ADMIN_MENU} route)</li>
     * </ul>
     */
    private String userType;

    /**
     * Account-lockout flag — a Java-migration addition (no COBOL equivalent column).
     * When {@code true}, the production {@link com.aws.carddemo.service.AuthenticationService}
     * rejects authentication BEFORE attempting the BCrypt verify, preventing CPU
     * exhaustion via repeated attempts against locked accounts.
     */
    private boolean locked;

    /**
     * Optimistic-locking version counter — JPA {@code @Version} field (Java-migration
     * addition; replaces COBOL before/after-image record comparison).
     */
    private Long version;

    /** Default constructor — required by JPA and the test-suite setters. */
    public SecurityUser() {
        // Intentionally empty — fields populated via setters or persistence framework.
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getUserType() {
        return userType;
    }

    public void setUserType(String userType) {
        this.userType = userType;
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SecurityUser)) {
            return false;
        }
        SecurityUser that = (SecurityUser) o;
        return Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId);
    }

    /**
     * String representation deliberately EXCLUDES {@link #password} to prevent
     * accidental credential disclosure in logs and debug output.
     *
     * <p>Per AAP §0.10.5 security constraint, applied transitively to credentials:
     * if {@code SecurityUser.toString()} were called in a log statement, no part of
     * the credential (hashed or plaintext) must appear in the log output.
     */
    @Override
    public String toString() {
        return "SecurityUser{"
                + "userId='" + userId + '\''
                + ", firstName='" + firstName + '\''
                + ", lastName='" + lastName + '\''
                + ", userType='" + userType + '\''
                + ", locked=" + locked
                + ", version=" + version
                + '}';
    }
}
