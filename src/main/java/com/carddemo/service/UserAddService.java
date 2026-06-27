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
package com.carddemo.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.carddemo.dto.UserDto;
import com.carddemo.entity.User;
import com.carddemo.exception.DuplicateRecordException;
import com.carddemo.exception.FileAccessException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds a new security user, backing {@code POST /api/admin/users}.
 *
 * <p>This service translates the CICS pseudo-conversational add program
 * {@code COUSR01C} (transaction {@code CU01}, "Add a new Regular/Admin user to
 * USRSEC file") at source commit {@code 27d6c6f}. The legacy program validated
 * the screen fields in {@code PROCESS-ENTER-KEY} and then issued an
 * {@code EXEC CICS WRITE} of {@code SEC-USER-DATA} keyed on {@code SEC-USR-ID}
 * (paragraph {@code WRITE-USER-SEC-FILE}). Those two paragraphs collapse here
 * into a single transactional {@link #addUser(UserDto.CreateRequest)} call: the
 * ordered empty-field cascade, a user-type domain edit (canonical {@code A}/{@code U}
 * role set, DECISION_LOG D-072), a duplicate-key guard, BCrypt password hashing,
 * and the keyed insert.</p>
 *
 * <p>The legacy {@code COUSR1A} screen flow flagged the <em>first</em> empty
 * field via an {@code EVALUATE TRUE} short-circuit; that ordering is preserved
 * exactly (first name, last name, user id, password, user type) and each
 * rejection carries the byte-for-byte legacy message. The COBOL {@code WRITE}
 * returned {@code DUPKEY}/{@code DUPREC} when the key already existed; that
 * becomes an explicit {@link UserRepository#existsById(Object)} pre-check plus a
 * defensive translation of any {@link DataIntegrityViolationException} surfaced
 * by the insert, both yielding the same {@link DuplicateRecordException}.</p>
 *
 * <p>The plaintext {@code SEC-USR-PWD PIC X(08)} credential is upgraded to a
 * BCrypt hash (constraint C-003) before persistence using the injected
 * {@link PasswordEncoder} bean; the raw password is never persisted, never
 * returned, and never logged. The returned {@link UserDto.UserSummary} carries
 * the user id, names, and user type only.</p>
 *
 * <p>The component is stateless and therefore thread-safe; its only
 * collaborators are the injected {@link UserRepository} (the keyed insert that
 * replaces the VSAM {@code USRSEC} KSDS {@code WRITE}) and the
 * {@link PasswordEncoder}.</p>
 */
@Service
public class UserAddService {

    private static final Logger log = LoggerFactory.getLogger(UserAddService.class);

    /**
     * Message raised when the first name is absent. Byte-exact copy of the
     * {@code COUSR01C} {@code 'First Name can NOT be empty...'} literal @
     * {@code 27d6c6f}.
     */
    private static final String FIRST_NAME_EMPTY_MESSAGE = "First Name can NOT be empty...";

    /**
     * Message raised when the last name is absent. Byte-exact copy of the
     * {@code COUSR01C} {@code 'Last Name can NOT be empty...'} literal @
     * {@code 27d6c6f}.
     */
    private static final String LAST_NAME_EMPTY_MESSAGE = "Last Name can NOT be empty...";

    /**
     * Message raised when the user id is absent. Byte-exact copy of the
     * {@code COUSR01C} {@code 'User ID can NOT be empty...'} literal @
     * {@code 27d6c6f}.
     */
    private static final String USER_ID_EMPTY_MESSAGE = "User ID can NOT be empty...";

    /**
     * Message raised when the password is absent. Byte-exact copy of the
     * {@code COUSR01C} {@code 'Password can NOT be empty...'} literal @
     * {@code 27d6c6f}.
     */
    private static final String PASSWORD_EMPTY_MESSAGE = "Password can NOT be empty...";

    /**
     * Message raised when the user type is absent. Byte-exact copy of the
     * {@code COUSR01C} {@code 'User Type can NOT be empty...'} literal @
     * {@code 27d6c6f}.
     */
    private static final String USER_TYPE_EMPTY_MESSAGE = "User Type can NOT be empty...";

    /**
     * Message raised when the user type is present but lies outside the canonical
     * {@code A}/{@code U} role domain.
     *
     * <p>Unlike the empty-field messages above, this is deliberately <em>not</em>
     * a byte-exact {@code COUSR01C} literal: the legacy {@code PROCESS-ENTER-KEY}
     * {@code EVALUATE TRUE} cascade validated {@code USRTYPE} for emptiness only
     * ({@code = SPACES OR LOW-VALUES}) and then moved its value through verbatim,
     * so a strictly-literal port would persist any single character. The canonical
     * user-type domain is defined by the {@code COCOM01Y} condition names
     * {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} and
     * {@code 88 CDEMO-USRTYP-USER VALUE 'U'} (on which {@code COSGN00C} branches
     * admin-vs-user at signon), and {@code docs/api-contracts.md} documents the
     * field as {@code A} or {@code U}. Enforcing that domain here closes the
     * "undefined persisted role" gap (QA MAJOR finding) while keeping the
     * empty-field cascade and its byte-exact literals intact. The deviation from
     * the strictly-literal empty-only edit is recorded in DECISION_LOG D-072.
     */
    private static final String USER_TYPE_INVALID_MESSAGE = "User Type must be A or U...";

    /**
     * Message raised when the user id already exists. Byte-exact copy of the
     * {@code COUSR01C} {@code WRITE-USER-SEC-FILE} {@code DUPKEY}/{@code DUPREC}
     * {@code 'User ID already exist...'} literal @ {@code 27d6c6f}.
     */
    private static final String USER_ALREADY_EXISTS_MESSAGE = "User ID already exist...";

    /**
     * Message used when the insert fails unexpectedly. Byte-exact copy of the
     * {@code COUSR01C} {@code WRITE-USER-SEC-FILE} {@code WHEN OTHER}
     * {@code 'Unable to Add User...'} literal @ {@code 27d6c6f}.
     */
    private static final String ADD_FAILURE_MESSAGE = "Unable to Add User...";

    /**
     * Format string for the success confirmation. Reproduces the
     * {@code COUSR01C} {@code STRING 'User ' SEC-USR-ID ' has been added ...'}
     * composition @ {@code 27d6c6f}; the {@code %s} receives the trimmed user
     * id, mirroring the legacy {@code DELIMITED BY SPACE} truncation.
     */
    private static final String USER_ADDED_MESSAGE_FORMAT = "User %s has been added ...";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Creates the service with its required collaborators.
     *
     * @param userRepository  repository providing the keyed existence check and
     *                        insert that replace the VSAM {@code USRSEC} KSDS
     *                        {@code WRITE}
     * @param passwordEncoder the BCrypt encoder (supplied by {@code SecurityConfig})
     *                        used to hash the credential before persistence
     */
    public UserAddService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Adds a new user, reproducing the {@code COUSR01C} add flow as a single
     * atomic unit of work.
     *
     * <p>The validation cascade, duplicate guard, and insert run inside one
     * {@code @Transactional} boundary so the record is committed-or-rolled-back
     * as a whole, exactly as the implicit CICS {@code WRITE} commit guaranteed.
     * Processing proceeds in the legacy order:</p>
     *
     * <ol>
     *   <li>Empty-field checks (first name, last name, user id, password, user
     *       type) reproducing the {@code PROCESS-ENTER-KEY} {@code EVALUATE TRUE}
     *       short-circuit: the first absent field raises a
     *       {@link ValidationException} carrying its byte-exact legacy message.</li>
     *   <li>A duplicate-key guard ({@link UserRepository#existsById(Object)})
     *       reproducing the {@code DUPKEY}/{@code DUPREC} outcome.</li>
     *   <li>BCrypt hashing of the credential followed by the keyed insert; any
     *       persistence duplicate is translated to {@link DuplicateRecordException}
     *       and any other data-access failure to {@link FileAccessException}.</li>
     * </ol>
     *
     * @param request the user-add request; its {@code password} is plaintext on
     *                the wire only and is stored BCrypt-hashed
     * @return a {@link UserDto.UserSummary} describing the created user,
     *         excluding the password
     * @throws ValidationException        if any required field is {@code null} or
     *                                    blank (byte-exact legacy message, with a
     *                                    single-entry field-error map), or if the
     *                                    user type is present but outside the
     *                                    canonical {@code A}/{@code U} domain
     *                                    (DECISION_LOG D-072)
     * @throws DuplicateRecordException   if the user id already exists
     *                                    ({@code "User ID already exist..."})
     * @throws FileAccessException        if the insert fails for any other,
     *                                    unexpected data-access reason
     *                                    ({@code "Unable to Add User..."})
     */
    @Transactional(rollbackFor = Exception.class)
    public UserDto.UserSummary addUser(UserDto.CreateRequest request) {
        validateRequest(request);

        String userId = request.userId().trim();
        if (userRepository.existsById(userId)) {
            throw new DuplicateRecordException(USER_ALREADY_EXISTS_MESSAGE);
        }

        User user = new User(
                userId,
                request.firstName(),
                request.lastName(),
                passwordEncoder.encode(request.password().toUpperCase(Locale.ROOT)),
                request.userType());

        try {
            userRepository.save(user);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateRecordException(USER_ALREADY_EXISTS_MESSAGE, ex);
        } catch (DataAccessException ex) {
            throw new FileAccessException(ADD_FAILURE_MESSAGE, ex);
        }

        log.info(String.format(USER_ADDED_MESSAGE_FORMAT, userId));

        return new UserDto.UserSummary(
                userId,
                request.firstName(),
                request.lastName(),
                request.userType());
    }

    /**
     * Validates the request fields in the legacy {@code PROCESS-ENTER-KEY} order,
     * raising on the first absent field.
     *
     * <p>A {@code null} request is treated as "no field supplied", which makes
     * the first field (first name) the first empty one; this preserves the
     * first-field semantics of the {@code EVALUATE TRUE} cascade without
     * dereferencing a {@code null}.</p>
     *
     * <p>After the ordered empty-field cascade confirms the user type is present,
     * a final domain edit rejects any value outside the canonical {@code A}/{@code U}
     * role set (see {@link #requireValidUserType(String)} and DECISION_LOG D-072).
     * Because the domain edit runs only once presence is established, it never
     * shadows the byte-exact {@code "User Type can NOT be empty..."} literal.</p>
     *
     * @param request the request to validate; may be {@code null}
     * @throws ValidationException carrying the byte-exact message of the first
     *                             absent field, or the domain message when the
     *                             user type is present but not {@code A}/{@code U}
     */
    private void validateRequest(UserDto.CreateRequest request) {
        if (request == null) {
            throw emptyFieldException("firstName", FIRST_NAME_EMPTY_MESSAGE);
        }
        requireNonEmpty(request.firstName(), "firstName", FIRST_NAME_EMPTY_MESSAGE);
        requireNonEmpty(request.lastName(), "lastName", LAST_NAME_EMPTY_MESSAGE);
        requireNonEmpty(request.userId(), "userId", USER_ID_EMPTY_MESSAGE);
        requireNonEmpty(request.password(), "password", PASSWORD_EMPTY_MESSAGE);
        requireNonEmpty(request.userType(), "userType", USER_TYPE_EMPTY_MESSAGE);
        requireValidUserType(request.userType());
    }

    /**
     * Rejects a present-but-out-of-domain user type, enforcing the canonical
     * {@code COCOM01Y} role set ({@code A} = admin, {@code U} = user).
     *
     * <p>This guard runs only after {@link #requireNonEmpty} has confirmed the
     * value is present, so the byte-exact {@code "User Type can NOT be empty..."}
     * empty message is never shadowed. The comparison is exact and
     * case-sensitive against the uppercase {@code 88}-level {@code VALUE}s; the
     * value is never upper-cased because the legacy
     * {@code MOVE USRTYPEI TO SEC-USR-TYPE} stored it verbatim, so a lowercase
     * {@code 'a'} is correctly rejected as an undefined role rather than silently
     * persisted (the security gap the QA MAJOR finding flagged). See
     * DECISION_LOG D-072.</p>
     *
     * @param value the already-present (non-blank) user-type value to test
     * @throws ValidationException if {@code value} is not exactly {@code "A"} or
     *                             {@code "U"}, carrying the domain message and a
     *                             single-entry {@code userType} field-error map
     */
    private static void requireValidUserType(String value) {
        if (!"A".equals(value) && !"U".equals(value)) {
            throw emptyFieldException("userType", USER_TYPE_INVALID_MESSAGE);
        }
    }

    /**
     * Raises a {@link ValidationException} when the supplied value is
     * {@code null} or blank, reproducing the COBOL {@code = SPACES OR LOW-VALUES}
     * empty test.
     *
     * @param value   the field value to test
     * @param field   the logical field name placed in the field-error map
     * @param message the byte-exact legacy message
     * @throws ValidationException if {@code value} is {@code null} or blank
     */
    private static void requireNonEmpty(String value, String field, String message) {
        if (value == null || value.isBlank()) {
            throw emptyFieldException(field, message);
        }
    }

    /**
     * Builds a {@link ValidationException} whose detail message is the byte-exact
     * legacy message and whose field-error map carries a single
     * {@code field -> message} entry in insertion order.
     *
     * @param field   the logical field name
     * @param message the byte-exact legacy message
     * @return the validation exception to throw
     */
    private static ValidationException emptyFieldException(String field, String message) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        fieldErrors.put(field, message);
        return new ValidationException(message, fieldErrors);
    }
}
