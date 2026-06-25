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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes a security user, backing {@code DELETE /api/admin/users/{id}}.
 *
 * <p>This service replaces the CICS pseudo-conversational delete program
 * {@code COUSR03C} (transaction {@code CU03}) @ {@code 27d6c6f}. The legacy
 * program reads the {@code USRSEC} record for update and then deletes it; the
 * keyed {@code READ ... UPDATE} (paragraph {@code READ-USER-SEC-FILE}) and the
 * {@code DELETE} (paragraph {@code DELETE-USER-SEC-FILE}) collapse into a single
 * transactional fetch-then-delete here.</p>
 *
 * <p>The 3270 confirmation affordance &mdash; the {@code Press PF5 key to delete
 * this user ...} prompt the legacy screen showed after a successful read &mdash;
 * is a presentation concern owned by the calling flow and is not modeled as a
 * service outcome. The COBOL {@code SEC-USER-DATA} layout (copybook
 * {@code app/cpy/CSUSR01Y.cpy}) is surfaced through {@link User}, and the
 * password is never exposed on the returned {@link UserDto.DeleteResponse}.</p>
 *
 * <p>The component is stateless and therefore thread-safe; its only collaborator
 * is the injected {@link UserRepository}, which provides the keyed lookup and
 * delete operations that replace the VSAM {@code USRSEC} KSDS access path.</p>
 */
@Service
public class UserDeleteService {

    private static final Logger log = LoggerFactory.getLogger(UserDeleteService.class);

    /**
     * Message raised when the supplied user identifier is absent. Byte-exact
     * copy of the {@code COUSR03C} {@code 'User ID can NOT be empty...'}
     * literal @ {@code 27d6c6f}.
     */
    private static final String USER_ID_EMPTY_MESSAGE = "User ID can NOT be empty...";

    /**
     * Message raised when no user matches the supplied identifier. Byte-exact
     * copy of the {@code COUSR03C} {@code 'User ID NOT found...'} literal @
     * {@code 27d6c6f}.
     */
    private static final String USER_NOT_FOUND_MESSAGE = "User ID NOT found...";

    /**
     * Format string for the success confirmation. Reproduces the
     * {@code COUSR03C} {@code STRING 'User ' SEC-USR-ID ' has been deleted ...'}
     * composition @ {@code 27d6c6f}; {@code %s} receives the trimmed identifier.
     */
    private static final String USER_DELETED_MESSAGE_FORMAT = "User %s has been deleted ...";

    /**
     * Message used when the delete operation fails unexpectedly. Byte-exact copy
     * of the {@code COUSR03C} {@code DELETE-USER-SEC-FILE} {@code WHEN OTHER}
     * {@code 'Unable to Update User...'} literal @ {@code 27d6c6f}.
     */
    private static final String DELETE_FAILURE_MESSAGE = "Unable to Update User...";

    private final UserRepository userRepository;

    /**
     * Creates the service with its repository collaborator.
     *
     * @param userRepository the repository providing keyed lookup and delete of
     *                        {@link User} records
     */
    public UserDeleteService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Deletes the user identified by {@code userId}, reproducing the
     * {@code COUSR03C} delete flow as a single atomic unit of work.
     *
     * <p>The legacy read-for-update followed by delete becomes a fetch followed
     * by a repository delete inside one {@code @Transactional} boundary, so the
     * record is committed-or-rolled-back as a whole exactly as the CICS
     * {@code SYNCPOINT} guaranteed. The user's display fields are captured into
     * the response before the record is removed.</p>
     *
     * @param userId the 8-character identifier of the user to delete
     * @return a {@link UserDto.DeleteResponse} describing the deleted user,
     *         excluding the password
     * @throws ValidationException     if {@code userId} is {@code null} or blank
     *                                 ({@code "User ID can NOT be empty..."})
     * @throws RecordNotFoundException if no user matches {@code userId}
     *                                 ({@code "User ID NOT found..."})
     * @throws FileAccessException     if the delete fails for any other,
     *                                 unexpected data-access reason
     */
    @Transactional(rollbackFor = Exception.class)
    public UserDto.DeleteResponse deleteUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ValidationException(USER_ID_EMPTY_MESSAGE);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RecordNotFoundException(USER_NOT_FOUND_MESSAGE));

        UserDto.DeleteResponse response = new UserDto.DeleteResponse(
                user.getUserId(),
                user.getFirstName(),
                user.getLastName(),
                user.getUserType());

        try {
            userRepository.delete(user);
        } catch (DataAccessException ex) {
            throw new FileAccessException(DELETE_FAILURE_MESSAGE, ex);
        }

        log.info(String.format(USER_DELETED_MESSAGE_FORMAT, userId.trim()));
        return response;
    }
}
