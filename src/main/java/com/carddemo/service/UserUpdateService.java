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

import com.carddemo.dto.UserDto;
import com.carddemo.entity.User;
import com.carddemo.exception.FileAccessException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Updates a security user, backing {@code PUT /api/admin/users/{id}}.
 *
 * <p>This service replaces the CICS pseudo-conversational update program
 * {@code COUSR02C} (transaction {@code CU02}) @ {@code 27d6c6f}. The legacy
 * program validates the editable fields (paragraph {@code UPDATE-USER-INFO}),
 * reads the {@code USRSEC} record for update (paragraph
 * {@code READ-USER-SEC-FILE}, keyed {@code READ ... UPDATE}), applies the
 * changed fields, and rewrites the record (paragraph
 * {@code UPDATE-USER-SEC-FILE}, {@code REWRITE}). Those steps collapse into a
 * single transactional validate-fetch-apply-save here.</p>
 *
 * <p>The COBOL {@code SEC-USER-DATA} layout (copybook
 * {@code app/cpy/CSUSR01Y.cpy}, record length 80) is surfaced through
 * {@link User}. The legacy plaintext {@code SEC-USR-PWD} is upgraded to a BCrypt
 * hash per constraint C-003: when the request carries a new password it is
 * encoded through the injected {@link PasswordEncoder} before persistence;
 * when the request omits the password the stored hash is preserved unchanged.
 * The password is never logged and is never present on the returned
 * {@link UserDto.UserSummary}.</p>
 *
 * <p>The 3270 informational affordances the legacy screen displayed &mdash;
 * {@code 'Press PF5 key to save your updates ...'} after a successful read and
 * {@code 'Please modify to update ...'} when nothing changed &mdash; are
 * presentation concerns owned by the calling flow and are not modeled as
 * service outcomes. Because {@link User} carries no {@code @Version} attribute,
 * there is no optimistic-locking branch (unlike the account and card update
 * services); the {@code REWRITE} is a straightforward fetch-modify-save.</p>
 *
 * <p>The component is stateless and therefore thread-safe; its collaborators
 * are the injected {@link UserRepository}, which provides the keyed lookup and
 * save operations that replace the VSAM {@code USRSEC} KSDS access path, and
 * the {@link PasswordEncoder} used for credential hashing.</p>
 */
@Service
public class UserUpdateService {

    private static final Logger log = LoggerFactory.getLogger(UserUpdateService.class);

    /**
     * Message raised when the supplied user identifier is absent. Byte-exact
     * copy of the {@code COUSR02C} {@code 'User ID can NOT be empty...'}
     * literal @ {@code 27d6c6f}.
     */
    private static final String USER_ID_EMPTY_MESSAGE = "User ID can NOT be empty...";

    /**
     * Message raised when the first name is absent. Byte-exact copy of the
     * {@code COUSR02C} {@code 'First Name can NOT be empty...'} literal @
     * {@code 27d6c6f}.
     */
    private static final String FIRST_NAME_EMPTY_MESSAGE = "First Name can NOT be empty...";

    /**
     * Message raised when the last name is absent. Byte-exact copy of the
     * {@code COUSR02C} {@code 'Last Name can NOT be empty...'} literal @
     * {@code 27d6c6f}.
     */
    private static final String LAST_NAME_EMPTY_MESSAGE = "Last Name can NOT be empty...";

    /**
     * Message raised when the user type is absent. Byte-exact copy of the
     * {@code COUSR02C} {@code 'User Type can NOT be empty...'} literal @
     * {@code 27d6c6f}.
     */
    private static final String USER_TYPE_EMPTY_MESSAGE = "User Type can NOT be empty...";

    /**
     * Message raised when no user matches the supplied identifier. Byte-exact
     * copy of the {@code COUSR02C} {@code 'User ID NOT found...'} literal @
     * {@code 27d6c6f}.
     */
    private static final String USER_NOT_FOUND_MESSAGE = "User ID NOT found...";

    /**
     * Format string for the success confirmation. Reproduces the
     * {@code COUSR02C} {@code STRING 'User ' SEC-USR-ID ' has been updated ...'}
     * composition @ {@code 27d6c6f}; {@code %s} receives the trimmed identifier.
     */
    private static final String USER_UPDATED_MESSAGE_FORMAT = "User %s has been updated ...";

    /**
     * Message used when the rewrite fails unexpectedly. Byte-exact copy of the
     * {@code COUSR02C} {@code UPDATE-USER-SEC-FILE} {@code WHEN OTHER}
     * {@code 'Unable to Update User...'} literal @ {@code 27d6c6f}.
     */
    private static final String UPDATE_FAILURE_MESSAGE = "Unable to Update User...";

    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    /**
     * Creates the service with its repository and password-encoder
     * collaborators.
     *
     * @param userRepository  the repository providing keyed lookup and save of
     *                        {@link User} records
     * @param passwordEncoder the BCrypt encoder applied to a new password before
     *                        persistence (constraint C-003)
     */
    public UserUpdateService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Updates the user identified by {@code userId}, reproducing the
     * {@code COUSR02C} update flow as a single atomic unit of work.
     *
     * <p>Validation mirrors the legacy {@code UPDATE-USER-INFO} evaluation in
     * its original short-circuit order: the identifier, first name, last name,
     * and user type must each be present, and the first absent field raises a
     * {@link ValidationException} carrying the byte-exact legacy message. The
     * password is optional on {@link UserDto.UpdateRequest}: a blank value
     * leaves the stored BCrypt hash untouched, while a supplied value is encoded
     * before persistence.</p>
     *
     * <p>The legacy read-for-update followed by rewrite becomes a fetch followed
     * by a repository save inside one {@code @Transactional} boundary, so the
     * record is committed-or-rolled-back as a whole exactly as the CICS
     * {@code SYNCPOINT} guaranteed.</p>
     *
     * @param userId  the 8-character identifier of the user to update (the
     *                {@code {id}} path variable; authoritative key)
     * @param request the new field values; its {@code password} is applied only
     *                when present
     * @return a {@link UserDto.UserSummary} describing the updated user,
     *         excluding the password
     * @throws ValidationException     if {@code userId}, the first name, the last
     *                                 name, or the user type is {@code null} or
     *                                 blank
     * @throws RecordNotFoundException if no user matches {@code userId}
     *                                 ({@code "User ID NOT found..."})
     * @throws FileAccessException     if the save fails for any other,
     *                                 unexpected data-access reason
     */
    @Transactional(rollbackFor = Exception.class)
    public UserDto.UserSummary updateUser(String userId, UserDto.UpdateRequest request) {
        if (isBlank(userId)) {
            throw new ValidationException(USER_ID_EMPTY_MESSAGE);
        }
        if (isBlank(request.firstName())) {
            throw new ValidationException(FIRST_NAME_EMPTY_MESSAGE);
        }
        if (isBlank(request.lastName())) {
            throw new ValidationException(LAST_NAME_EMPTY_MESSAGE);
        }
        if (isBlank(request.userType())) {
            throw new ValidationException(USER_TYPE_EMPTY_MESSAGE);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RecordNotFoundException(USER_NOT_FOUND_MESSAGE));

        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setUserType(request.userType());

        String newPassword = request.password();
        if (!isBlank(newPassword)) {
            user.setPassword(passwordEncoder.encode(newPassword));
        }

        User saved;
        try {
            saved = userRepository.save(user);
        } catch (DataAccessException ex) {
            throw new FileAccessException(UPDATE_FAILURE_MESSAGE, ex);
        }

        log.info(String.format(USER_UPDATED_MESSAGE_FORMAT, userId.trim()));

        return new UserDto.UserSummary(
                saved.getUserId(),
                saved.getFirstName(),
                saved.getLastName(),
                saved.getUserType());
    }

    /**
     * Reports whether the supplied value is absent for validation purposes,
     * matching the COBOL {@code = SPACES OR LOW-VALUES} test.
     *
     * @param value the value to test
     * @return {@code true} when {@code value} is {@code null} or contains only
     *         whitespace
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
