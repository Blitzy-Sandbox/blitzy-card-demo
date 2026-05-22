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
 * Mutable request DTO for {@link UserAddService#addUser(UserAddRequest)} — the Java
 * replacement for the {@code COUSR1AI} BMS-mapped input record carrying the new
 * user's identifier, name, plaintext password, and user type from
 * {@code app/bms/COUSR01.bms} as read by {@code app/cbl/COUSR01C.cbl} (TRANID
 * {@code CU01}, the admin-only user-add dispatcher).
 *
 * <h2>COBOL Provenance — COUSR01C.cbl</h2>
 *
 * <p>The COBOL {@code RECEIVE-USRADD-SCREEN} paragraph (lines 201–209) populates the
 * {@code COUSR1AI} input map with the operator's keystrokes. The
 * {@code PROCESS-ENTER-KEY} paragraph (lines 115–160) then consults five input
 * fields, each derived from the COBOL {@code SEC-USER-DATA} record layout
 * ({@code app/cpy/CSUSR01Y.cpy}):
 *
 * <ul>
 *   <li>{@code USERIDI OF COUSR1AI PIC X(08)} — the new user's 8-character
 *       identifier (line 154: {@code MOVE USERIDI OF COUSR1AI TO SEC-USR-ID}).
 *       Mapped to {@link #userId} on this DTO.</li>
 *   <li>{@code FNAMEI OF COUSR1AI PIC X(20)} — the new user's 20-character
 *       first name (line 155: {@code MOVE FNAMEI OF COUSR1AI TO SEC-USR-FNAME}).
 *       Mapped to {@link #firstName} on this DTO.</li>
 *   <li>{@code LNAMEI OF COUSR1AI PIC X(20)} — the new user's 20-character
 *       last name (line 156: {@code MOVE LNAMEI OF COUSR1AI TO SEC-USR-LNAME}).
 *       Mapped to {@link #lastName} on this DTO.</li>
 *   <li>{@code PASSWDI OF COUSR1AI PIC X(08)} — the new user's 8-character
 *       <strong>plaintext</strong> password (line 157: {@code MOVE PASSWDI OF
 *       COUSR1AI TO SEC-USR-PWD}). The COBOL original stored this plaintext;
 *       the Java migration hashes it via BCrypt before persisting (AAP §0.10.5
 *       "No plaintext credentials in any configuration file"). Mapped to
 *       {@link #password} on this DTO (still plaintext on the way in — the
 *       service hashes it before writing to the database).</li>
 *   <li>{@code USRTYPEI OF COUSR1AI PIC X(01)} — the new user's 1-character
 *       user type (line 158: {@code MOVE USRTYPEI OF COUSR1AI TO SEC-USR-TYPE}).
 *       Accepts {@code "U"} (regular user) or {@code "A"} (admin) per the
 *       {@code CDEMO-USRTYP-*} 88-level values. Mapped to {@link #userType}
 *       on this DTO.</li>
 * </ul>
 *
 * <h2>Mutability — Setter-Driven Population</h2>
 *
 * <p>Unlike sibling request DTOs in this package ({@link UserDeleteRequest},
 * {@link UserListRequest}) which expose immutable positional constructors,
 * this class is populated via a no-argument constructor plus per-field setters.
 * The rationale: the controller layer maps a JSON request body (or
 * {@code @ModelAttribute} form) onto this DTO via the Jackson / Spring MVC
 * standard JavaBeans contract, which requires both the default constructor
 * and the public setters. The mutability is confined to the request-binding
 * phase; once the controller passes the DTO to the service, the service
 * treats it as a read-only value object.
 *
 * <h2>No Validation in the DTO</h2>
 *
 * <p>This class deliberately performs no field validation in the constructor
 * or setters. Per the established convention used by every other request DTO
 * in this package, validation of the payload (empty {@code firstName},
 * {@code lastName}, {@code userId}, {@code password}, {@code userType}, plus
 * the user-type domain check {@code "U" | "A"}) is performed by the
 * {@link UserAddService} so the reject paths emit the COBOL-equivalent reject
 * messages rather than {@link IllegalArgumentException}. Carrying validation
 * in the service also keeps it visible to the test suite and countable for
 * JaCoCo coverage purposes (AAP §0.7.1).
 *
 * <h2>Stub Status</h2>
 *
 * <p>This class is a <strong>minimum-viable POJO</strong> created to satisfy
 * {@link UserAddService} compilation and the {@code UserAddServiceTest} unit
 * test suite. Subsequent migration agents (REFACTOR flavor) will add Bean
 * Validation constraints ({@code @NotBlank}, {@code @Size(max = 8)},
 * {@code @Pattern("[UA]")}, etc.) when the full Spring MVC controller layer
 * is wired up.
 *
 * @see UserAddService
 * @see UserAddResult
 */
public class UserAddRequest {

    /**
     * The new user's 8-character identifier — COBOL {@code SEC-USR-ID PIC X(08)}.
     * Maps from {@code USERIDI OF COUSR1AI} on the BMS map. May be {@code null}
     * or empty when the operator presses Enter without entering a user ID; the
     * service interprets either as the {@code 'User ID can NOT be empty...'}
     * reject (COUSR01C.cbl line 132).
     */
    private String userId;

    /**
     * The new user's 20-character first name — COBOL
     * {@code SEC-USR-FNAME PIC X(20)}. Maps from {@code FNAMEI OF COUSR1AI} on
     * the BMS map. May be {@code null} or empty; the service interprets either
     * as the {@code 'First Name can NOT be empty...'} reject (COUSR01C.cbl
     * line 120, the first validation in the cascade).
     */
    private String firstName;

    /**
     * The new user's 20-character last name — COBOL
     * {@code SEC-USR-LNAME PIC X(20)}. Maps from {@code LNAMEI OF COUSR1AI} on
     * the BMS map. May be {@code null} or empty; the service interprets either
     * as the {@code 'Last Name can NOT be empty...'} reject (COUSR01C.cbl
     * line 126).
     */
    private String lastName;

    /**
     * The new user's 8-character <strong>plaintext</strong> password — COBOL
     * {@code PASSWDI OF COUSR1AI PIC X(08)} on the input side; mapped to
     * {@code SEC-USR-PWD PIC X(08)} on the COBOL output side (which stored it
     * as plaintext on disk).
     *
     * <p><strong>Java migration:</strong> the service hashes this plaintext via
     * {@code passwordEncoder.encode(password)} before persisting it to the
     * {@link com.aws.carddemo.entity.SecurityUser#getPassword()} field; the
     * BCrypt hash (60 characters: {@code $2a$10$<22-char-salt><31-char-hash>})
     * is what gets stored, never the plaintext (AAP §0.10.5 "No plaintext
     * credentials").
     *
     * <p>May be {@code null} or empty; the service interprets either as the
     * {@code 'Password can NOT be empty...'} reject (COUSR01C.cbl line 138).
     */
    private String password;

    /**
     * The new user's 1-character user type — COBOL
     * {@code SEC-USR-TYPE PIC X(01)}. Maps from {@code USRTYPEI OF COUSR1AI}
     * on the BMS map. Accepts {@code "U"} (regular user, COBOL
     * {@code CDEMO-USRTYP-USER}) or {@code "A"} (admin, COBOL
     * {@code CDEMO-USRTYP-ADMIN}).
     *
     * <p>May be {@code null} or empty; the service interprets either as the
     * {@code 'User Type can NOT be empty...'} reject (COUSR01C.cbl line 144).
     * Values outside the {@code "U"} / {@code "A"} domain are rejected with
     * a Java-migration-added invalid-type reject; the COBOL original did not
     * enforce this because the BMS map's input attributes restricted the
     * operator to single-character entry, but the JSON REST controller layer
     * is reachable independently of any BMS attribute enforcement.
     */
    private String userType;

    /** Default no-argument constructor — required by JSON / form binding. */
    public UserAddRequest() {
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
     * Sets the new user's 8-character identifier.
     *
     * @param userId the user identifier; {@code null} and empty are accepted
     *               and trigger the {@code 'User ID can NOT be empty...'}
     *               reject in {@link UserAddService}
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
     * Sets the new user's 20-character first name.
     *
     * @param firstName the first name; {@code null} and empty are accepted and
     *                  trigger the {@code 'First Name can NOT be empty...'}
     *                  reject in {@link UserAddService}
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
     * Sets the new user's 20-character last name.
     *
     * @param lastName the last name; {@code null} and empty are accepted and
     *                 trigger the {@code 'Last Name can NOT be empty...'}
     *                 reject in {@link UserAddService}
     */
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    /**
     * @return the 8-character plaintext password (pre-hashing); may be
     *         {@code null} or empty (the service interprets either as the
     *         {@code 'Password can NOT be empty...'} reject)
     */
    public String getPassword() {
        return password;
    }

    /**
     * Sets the new user's plaintext password. The service will hash this value
     * via {@code passwordEncoder.encode(...)} before persisting it; the
     * plaintext form leaves this DTO only via the service's BCrypt-encode call.
     *
     * @param password the plaintext password; {@code null} and empty are
     *                 accepted and trigger the {@code 'Password can NOT be
     *                 empty...'} reject in {@link UserAddService}
     */
    public void setPassword(String password) {
        this.password = password;
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
     * Sets the new user's 1-character user type.
     *
     * @param userType the user type; expected {@code "U"} (regular) or
     *                 {@code "A"} (admin). {@code null} and empty trigger the
     *                 {@code 'User Type can NOT be empty...'} reject; other
     *                 values trigger the invalid-user-type reject.
     */
    public void setUserType(String userType) {
        this.userType = userType;
    }
}
