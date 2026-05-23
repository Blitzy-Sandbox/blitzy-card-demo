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
package com.aws.carddemo.service;

/**
 * Mutable request DTO for
 * {@link UserUpdateService#updateUser(UserUpdateRequest)} — the Java
 * replacement for the {@code COUSR2AI} BMS-mapped input record carrying the
 * target user's identifier, modified name fields, optional new plaintext
 * password, and modified user-type from {@code app/bms/COUSR02.bms} as read
 * by {@code app/cbl/COUSR02C.cbl} (TRANID {@code CU02}, the admin-only
 * user-update dispatcher).
 *
 * <h2>COBOL Provenance — COUSR02C.cbl</h2>
 *
 * <p>The COBOL {@code RECEIVE-USRUPD-SCREEN} paragraph (lines 283–291)
 * populates the {@code COUSR2AI} input map with the operator's keystrokes.
 * The {@code UPDATE-USER-INFO} paragraph (lines 177–245) then consults the
 * input fields, each derived from the COBOL {@code SEC-USER-DATA} record
 * layout ({@code app/cpy/CSUSR01Y.cpy}):
 *
 * <ul>
 *   <li>{@code USRIDINI OF COUSR2AI PIC X(08)} — the target user's
 *       8-character identifier (line 216: {@code MOVE USRIDINI OF COUSR2AI
 *       TO SEC-USR-ID}). Immutable primary key — the COBOL workflow uses it
 *       for the {@code READ-USER-SEC-FILE} (line 217) lookup and never
 *       updates it. Mapped to {@link #userId} on this DTO.</li>
 *   <li>{@code FNAMEI OF COUSR2AI PIC X(20)} — the target user's
 *       20-character first name (line 220: {@code MOVE FNAMEI OF COUSR2AI
 *       TO SEC-USR-FNAME} when changed). Mapped to {@link #firstName} on
 *       this DTO.</li>
 *   <li>{@code LNAMEI OF COUSR2AI PIC X(20)} — the target user's
 *       20-character last name (line 224: {@code MOVE LNAMEI OF COUSR2AI
 *       TO SEC-USR-LNAME} when changed). Mapped to {@link #lastName} on
 *       this DTO.</li>
 *   <li>{@code PASSWDI OF COUSR2AI PIC X(08)} — the target user's
 *       optional new 8-character <strong>plaintext</strong> password
 *       (line 228: {@code MOVE PASSWDI OF COUSR2AI TO SEC-USR-PWD} when
 *       changed). The COBOL original stored this plaintext; the Java
 *       migration hashes it via BCrypt before persisting (AAP §0.10.5
 *       "No plaintext credentials in any configuration file"). Mapped to
 *       {@link #newPassword} on this DTO (still plaintext on the way in —
 *       the service hashes it before writing to the database). Per the
 *       Java-migration mechanical divergence documented on
 *       {@link UserUpdateService}, when this field is {@code null} or
 *       empty the existing hash is preserved unchanged.</li>
 *   <li>{@code USRTYPEI OF COUSR2AI PIC X(01)} — the target user's
 *       1-character user type (line 232: {@code MOVE USRTYPEI OF COUSR2AI
 *       TO SEC-USR-TYPE} when changed). Accepts {@code "U"} (regular user)
 *       or {@code "A"} (admin) per the {@code CDEMO-USRTYP-*} 88-level
 *       values. Mapped to {@link #userType} on this DTO.</li>
 * </ul>
 *
 * <h2>Java Migration: newPassword Semantics</h2>
 *
 * <p>The Java migration introduces a subtle behavioural divergence from the
 * COBOL original on the password field. The COBOL workflow at lines 227–230
 * compares the operator's input {@code PASSWDI} against the loaded
 * {@code SEC-USR-PWD} (a plaintext-vs-plaintext comparison) to decide
 * whether to update the field; if the operator did not modify the password
 * input area, both values are identical and no update happens. With BCrypt
 * the loaded value is a 60-character hash, and the operator's input remains
 * plaintext — there is no symmetric comparison the service can perform
 * without first hashing the input (and the BCrypt salt randomisation means
 * even an identical plaintext produces a different hash, so equality of
 * hashes is the wrong predicate). The Java migration therefore re-purposes
 * the field with the following contract:
 * <ul>
 *   <li>{@code null} or empty {@link #newPassword} → preserve the existing
 *       BCrypt hash. This is the canonical "operator did not change the
 *       password" path.</li>
 *   <li>Non-empty {@link #newPassword} → BCrypt-encode and overwrite the
 *       persisted hash. This is the canonical "operator entered a new
 *       password" path.</li>
 * </ul>
 *
 * <p>This divergence is documented per AAP §0.10.2 "All deviations from
 * literal COBOL logic must be documented with the original COBOL paragraph
 * name and reason for divergence" (paragraph: {@code UPDATE-USER-INFO}
 * lines 227–230). The reject taxonomy (no "Password can NOT be empty..."
 * reject in the Java migration) is documented on {@link UserUpdateService}.
 *
 * <h2>Optimistic Locking — JPA @Version Field</h2>
 *
 * <p>{@link #version} carries the JPA optimistic-locking version counter
 * loaded by the controller layer when it served the user's edit screen.
 * The Java migration uses this field to detect concurrent updates: when the
 * service persists the entity, JPA compares the supplied version against
 * the persisted version and raises
 * {@link org.springframework.dao.OptimisticLockingFailureException} on a
 * mismatch. This is the Java replacement for the COBOL READ UPDATE / REWRITE
 * idiom (which serialised concurrent updates through CICS file locks).
 *
 * <h2>Mutability — Setter-Driven Population</h2>
 *
 * <p>Unlike sibling request DTOs in this package ({@link UserDeleteRequest},
 * {@link UserListRequest}) which expose immutable positional constructors,
 * this class is populated via a no-argument constructor plus per-field
 * setters. The rationale matches {@link UserAddRequest}: the controller
 * layer maps a JSON request body (or {@code @ModelAttribute} form) onto
 * this DTO via the Jackson / Spring MVC standard JavaBeans contract, which
 * requires both the default constructor and the public setters. The
 * mutability is confined to the request-binding phase; once the controller
 * passes the DTO to the service, the service treats it as a read-only
 * value object.
 *
 * <h2>No Validation in the DTO</h2>
 *
 * <p>This class deliberately performs no field validation in the constructor
 * or setters. Per the established convention used by every other request
 * DTO in this package, validation of the payload (empty {@code userId},
 * {@code firstName}, {@code lastName}, {@code userType}, plus the
 * user-type domain check {@code "U" | "A"}) is performed by the
 * {@link UserUpdateService} so the reject paths emit the COBOL-equivalent
 * reject messages rather than {@link IllegalArgumentException}. Carrying
 * validation in the service also keeps it visible to the test suite and
 * countable for JaCoCo coverage purposes (AAP §0.7.1).
 *
 * <h2>Stub Status</h2>
 *
 * <p>This class is a <strong>minimum-viable POJO</strong> created to satisfy
 * {@link UserUpdateService} compilation and the {@code UserUpdateServiceTest}
 * unit test suite. Subsequent migration agents (REFACTOR flavor) will add
 * Bean Validation constraints ({@code @NotBlank} on userId, firstName,
 * lastName, userType; {@code @Size(max = 8)} on userId; {@code @Pattern("[UA]")}
 * on userType; etc.) when the full Spring MVC controller layer is wired up.
 *
 * @see UserUpdateService
 * @see UserUpdateResult
 */
public class UserUpdateRequest {

    /**
     * The target user's 8-character identifier — COBOL {@code SEC-USR-ID
     * PIC X(08)}. Maps from {@code USRIDINI OF COUSR2AI} on the BMS map.
     * Immutable primary key — the service uses this value as the lookup key
     * to {@link com.aws.carddemo.repository.UserSecurityRepository#findById}
     * and does NOT change the persisted {@code userId} field on the entity.
     * May be {@code null} or empty when the operator submits without entering
     * a user ID; the service interprets either as the {@code 'User ID can NOT
     * be empty...'} reject (COBOL parity, line 182).
     */
    private String userId;

    /**
     * The target user's 20-character first name — COBOL {@code SEC-USR-FNAME
     * PIC X(20)}. Maps from {@code FNAMEI OF COUSR2AI} on the BMS map. May
     * be {@code null} or empty when the operator clears the field; the
     * service interprets either as the {@code 'First Name can NOT be
     * empty...'} reject (COBOL parity, line 188).
     */
    private String firstName;

    /**
     * The target user's 20-character last name — COBOL {@code SEC-USR-LNAME
     * PIC X(20)}. Maps from {@code LNAMEI OF COUSR2AI} on the BMS map. May
     * be {@code null} or empty when the operator clears the field; the
     * service interprets either as the {@code 'Last Name can NOT be
     * empty...'} reject (COBOL parity, line 194).
     */
    private String lastName;

    /**
     * The target user's optional new 8-character plaintext password —
     * COBOL {@code SEC-USR-PWD PIC X(08)}. Maps from {@code PASSWDI OF
     * COUSR2AI} on the BMS map. The COBOL original stored this plaintext;
     * the Java migration hashes it via {@code passwordEncoder.encode(...)}
     * before persisting.
     *
     * <p>Per the {@link UserUpdateService} Java-migration mechanical
     * divergence (see class-level Javadoc on this DTO):
     * <ul>
     *   <li>{@code null} or empty → preserve the existing BCrypt hash
     *       (operator did not change the password).</li>
     *   <li>Non-empty → BCrypt-encode and overwrite the persisted hash
     *       (operator entered a new password).</li>
     * </ul>
     */
    private String newPassword;

    /**
     * The target user's 1-character user type — COBOL {@code SEC-USR-TYPE
     * PIC X(01)}. Maps from {@code USRTYPEI OF COUSR2AI} on the BMS map.
     * Accepts {@code "U"} (regular user, COBOL {@code CDEMO-USRTYP-USER})
     * or {@code "A"} (admin, COBOL {@code CDEMO-USRTYP-ADMIN}).
     *
     * <p>May be {@code null} or empty; the service interprets either as the
     * {@code 'User Type can NOT be empty...'} reject (COBOL parity, line
     * 206). Values outside the {@code "U"} / {@code "A"} domain are rejected
     * with a Java-migration-added invalid-type reject; the COBOL original
     * did not enforce this because the BMS map's input attributes
     * restricted the operator to single-character entry, but the JSON REST
     * controller layer is reachable independently of any BMS attribute
     * enforcement.
     */
    private String userType;

    /**
     * JPA optimistic-locking version counter loaded by the controller layer
     * when it served the user's edit screen. Java-migration addition (no
     * COBOL equivalent — replaces the COBOL READ UPDATE / REWRITE idiom).
     * On {@code save()}, JPA compares this value against the persisted
     * version and raises
     * {@link org.springframework.dao.OptimisticLockingFailureException}
     * on a mismatch. May be {@code null} when the controller layer omits
     * the field (in which case JPA treats the save as a fresh insert,
     * which the service guards against by requiring the user record to
     * already exist via the {@code findById} lookup).
     */
    private Long version;

    /** Default no-argument constructor — required by JSON / form binding. */
    public UserUpdateRequest() {
        // Intentionally empty — fields populated via setters by the controller's
        // JSON or @ModelAttribute binder.
    }

    /**
     * @return the 8-character user identifier; may be {@code null} or empty
     *         (the service interprets either as the {@code 'User ID can NOT
     *         be empty...'} reject)
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Sets the target user's 8-character identifier.
     *
     * @param userId the user identifier; {@code null} and empty are accepted
     *               and trigger the {@code 'User ID can NOT be empty...'}
     *               reject in {@link UserUpdateService}
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * @return the 20-character first name; may be {@code null} or empty (the
     *         service interprets either as the {@code 'First Name can NOT be
     *         empty...'} reject)
     */
    public String getFirstName() {
        return firstName;
    }

    /**
     * Sets the target user's 20-character first name.
     *
     * @param firstName the first name; {@code null} and empty are accepted and
     *                  trigger the {@code 'First Name can NOT be empty...'}
     *                  reject in {@link UserUpdateService}
     */
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    /**
     * @return the 20-character last name; may be {@code null} or empty (the
     *         service interprets either as the {@code 'Last Name can NOT be
     *         empty...'} reject)
     */
    public String getLastName() {
        return lastName;
    }

    /**
     * Sets the target user's 20-character last name.
     *
     * @param lastName the last name; {@code null} and empty are accepted and
     *                 trigger the {@code 'Last Name can NOT be empty...'}
     *                 reject in {@link UserUpdateService}
     */
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    /**
     * @return the optional new 8-character plaintext password; {@code null}
     *         or empty signals "preserve the existing hash" (Java-migration
     *         mechanical divergence — see class-level Javadoc)
     */
    public String getNewPassword() {
        return newPassword;
    }

    /**
     * Sets the target user's optional new plaintext password. The service
     * will hash this value via {@code passwordEncoder.encode(...)} before
     * persisting it; the plaintext form leaves this DTO only via the
     * service's BCrypt-encode call.
     *
     * @param newPassword the new plaintext password, or {@code null}/empty to
     *                    preserve the existing BCrypt hash (Java-migration
     *                    mechanical divergence from COBOL); non-empty
     *                    values are BCrypt-encoded by {@link UserUpdateService}
     *                    before being persisted
     */
    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }

    /**
     * @return the 1-character user type ({@code "U"} or {@code "A"}); may be
     *         {@code null} or empty (the service interprets either as the
     *         {@code 'User Type can NOT be empty...'} reject), or any other
     *         single character (the service interprets that as the
     *         invalid-user-type reject)
     */
    public String getUserType() {
        return userType;
    }

    /**
     * Sets the target user's 1-character user type.
     *
     * @param userType the user type; expected {@code "U"} (regular) or
     *                 {@code "A"} (admin). {@code null} and empty trigger the
     *                 {@code 'User Type can NOT be empty...'} reject; other
     *                 values trigger the invalid-user-type reject.
     */
    public void setUserType(String userType) {
        this.userType = userType;
    }

    /**
     * @return the JPA optimistic-locking version counter loaded by the
     *         controller layer; may be {@code null} when the controller
     *         layer omits the field
     */
    public Long getVersion() {
        return version;
    }

    /**
     * Sets the JPA optimistic-locking version counter.
     *
     * @param version the version counter loaded by the controller layer when
     *                it served the user's edit screen; may be {@code null}
     */
    public void setVersion(Long version) {
        this.version = version;
    }
}
