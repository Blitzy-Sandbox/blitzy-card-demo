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

import java.util.List;

import com.carddemo.dto.UserDto;
import com.carddemo.entity.User;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.UserRepository;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.ExampleMatcher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only service that lists security users a page at a time, backing
 * {@code GET /api/admin/users}.
 *
 * <p>Translates the CICS pseudo-conversational program {@code COUSR00C}
 * (transaction {@code CU00}, "List all users from USRSEC file") at source commit
 * {@code 27d6c6f}. The legacy {@code STARTBR}/{@code READNEXT}/{@code READPREV}
 * browse over the VSAM {@code USRSEC} KSDS (keyed on the 8-character
 * {@code SEC-USR-ID}) is expressed here with Spring Data {@link Pageable} paging,
 * and the legacy PF7/PF8 page navigation maps to a zero-based page number. Each
 * screen held ten rows ({@code USER-REC OCCURS 10}), so the page size is fixed
 * at {@value #PAGE_SIZE} and rows are ordered by {@code SEC-USR-ID} to preserve
 * the legacy browse order.</p>
 *
 * <p>Rows are returned as {@link UserDto.UserSummary} projections that carry the
 * user id, first and last name, and user type only; the BCrypt password hash is
 * never read into a response object.</p>
 *
 * <p>The component is stateless and therefore thread-safe: it holds only the
 * injected repository and immutable constants.</p>
 */
@Service
public class UserListService {

    /**
     * Page size, fixed at ten to match the {@code USER-REC OCCURS 10} array that
     * the {@code COUSR00} screen rendered per page at {@code 27d6c6f}.
     */
    private static final int PAGE_SIZE = 10;

    /**
     * Entity property used to order each page so the legacy {@code SEC-USR-ID}
     * ascending browse order of {@code USRSEC} is preserved; also the property
     * matched by the optional user-id prefix filter.
     */
    private static final String USER_ID_PROPERTY = "userId";

    /**
     * Selection code routing a row to the user-update flow ({@code COUSR02C}).
     */
    private static final String SELECTION_UPDATE = "U";

    /**
     * Selection code routing a row to the user-delete flow ({@code COUSR03C}).
     */
    private static final String SELECTION_DELETE = "D";

    /**
     * Message shown by {@code COUSR00C} when a row is marked with anything other
     * than {@code U} (update) or {@code D} (delete); preserved byte-for-byte.
     */
    public static final String MESSAGE_INVALID_SELECTION =
            "Invalid selection. Valid values are U and D";

    /**
     * Message shown by {@code COUSR00C} when paging forward past the last record
     * ({@code READNEXT} end-of-file); applicable when {@link Page#hasNext()} is
     * {@code false}. Preserved byte-for-byte.
     */
    public static final String MESSAGE_REACHED_BOTTOM =
            "You have reached the bottom of the page...";

    /**
     * Message shown by {@code COUSR00C} when paging backward past the first
     * record ({@code READPREV} end-of-file); applicable when
     * {@link Page#hasPrevious()} is {@code false}. Preserved byte-for-byte.
     */
    public static final String MESSAGE_REACHED_TOP =
            "You have reached the top of the page...";

    /**
     * Message shown by {@code COUSR00C} when PF7 (page up) is pressed while
     * already on the first page. Preserved byte-for-byte.
     */
    public static final String MESSAGE_ALREADY_AT_TOP =
            "You are already at the top of the page...";

    /**
     * Message shown by {@code COUSR00C} when PF8 (page down) is pressed while
     * already on the last page. Preserved byte-for-byte.
     */
    public static final String MESSAGE_ALREADY_AT_BOTTOM =
            "You are already at the bottom of the page...";

    private final UserRepository userRepository;

    /**
     * Creates the service with its required collaborator.
     *
     * @param userRepository repository over the {@link User} security records
     */
    public UserListService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Returns one page of users as a {@link UserDto.ListResponse}, mirroring a
     * single {@code COUSR00} screen.
     *
     * <p>Equivalent to the {@code PROCESS-PAGE-FORWARD} read loop of
     * {@code COUSR00C}: up to ten records are read in {@code SEC-USR-ID} order and
     * projected to password-free rows. An empty page is a normal result and is
     * returned without error. A repository {@link org.springframework.dao.DataAccessException}
     * (the legacy {@code 'Unable to lookup User...'} path) propagates to the
     * global exception handler.</p>
     *
     * @param userIdFilter optional case-sensitive user-id prefix; when blank the
     *                     whole file is browsed from the start
     * @param pageNumber   zero-based page index (PF7/PF8 navigation)
     * @return the page response carrying the page number, the echoed filter, and
     *         up to ten {@link UserDto.UserSummary} rows
     */
    @Transactional(readOnly = true)
    public UserDto.ListResponse listUsers(String userIdFilter, int pageNumber) {
        List<UserDto.UserSummary> users = fetchUsers(userIdFilter, pageNumber).getContent().stream()
                .map(UserListService::toSummary)
                .toList();
        return new UserDto.ListResponse(Integer.toString(pageNumber), userIdFilter, users);
    }

    /**
     * Returns one page of users as a Spring Data {@link Page} of password-free
     * projections, exposing the paging metadata ({@link Page#hasNext()},
     * {@link Page#hasPrevious()}, and the total counts) that a caller uses to
     * decide the {@code COUSR00C} page-boundary messages.
     *
     * @param userIdFilter optional case-sensitive user-id prefix; when blank the
     *                     whole file is browsed from the start
     * @param pageNumber   zero-based page index (PF7/PF8 navigation)
     * @return a page of {@link UserDto.UserSummary} rows with paging metadata
     */
    @Transactional(readOnly = true)
    public Page<UserDto.UserSummary> listUserSummaries(String userIdFilter, int pageNumber) {
        return fetchUsers(userIdFilter, pageNumber).map(UserListService::toSummary);
    }

    /**
     * Validates a per-row selection code, mirroring the {@code WHEN OTHER} arm of
     * the {@code PROCESS-ENTER-KEY} selection check in {@code COUSR00C}.
     *
     * <p>A {@code null} or blank selection means "no row selected" and is
     * accepted. {@code U}/{@code u} (update) and {@code D}/{@code d} (delete) are
     * accepted. Any other value raises a {@link ValidationException} carrying the
     * byte-for-byte legacy message {@value #MESSAGE_INVALID_SELECTION}.</p>
     *
     * @param selection the per-row selection code; may be {@code null} or blank
     * @throws ValidationException if the selection is neither blank nor one of
     *                             {@code U}, {@code u}, {@code D}, {@code d}
     */
    public void validateSelection(String selection) {
        if (selection == null || selection.isBlank()) {
            return;
        }
        if (!SELECTION_UPDATE.equalsIgnoreCase(selection)
                && !SELECTION_DELETE.equalsIgnoreCase(selection)) {
            throw new ValidationException(MESSAGE_INVALID_SELECTION);
        }
    }

    /**
     * Reads one page of {@link User} records in {@code SEC-USR-ID} order. When a
     * user-id prefix is supplied the page is restricted with a case-sensitive
     * "starts with" Query by Example probe; otherwise the whole file is browsed,
     * reproducing the legacy {@code STARTBR} positioning behaviour.
     *
     * @param userIdFilter optional user-id prefix; blank means no restriction
     * @param pageNumber   zero-based page index
     * @return the matching page of users (possibly empty)
     */
    private Page<User> fetchUsers(String userIdFilter, int pageNumber) {
        Pageable pageable =
                PageRequest.of(pageNumber, PAGE_SIZE, Sort.by(Sort.Direction.ASC, USER_ID_PROPERTY));
        if (userIdFilter == null || userIdFilter.isBlank()) {
            return userRepository.findAll(pageable);
        }
        User probe = new User();
        probe.setUserId(userIdFilter);
        ExampleMatcher matcher = ExampleMatcher.matching()
                .withMatcher(USER_ID_PROPERTY, ExampleMatcher.GenericPropertyMatchers.startsWith());
        return userRepository.findAll(Example.of(probe, matcher), pageable);
    }

    /**
     * Projects a {@link User} entity onto a password-free
     * {@link UserDto.UserSummary}. Field values are copied verbatim (no trimming)
     * to preserve the byte-accurate {@code SEC-USR-ID}, {@code SEC-USR-FNAME},
     * {@code SEC-USR-LNAME}, and {@code SEC-USR-TYPE} widths.
     *
     * @param user the source entity
     * @return a summary carrying the user id, first and last name, and user type
     */
    private static UserDto.UserSummary toSummary(User user) {
        return new UserDto.UserSummary(
                user.getUserId(),
                user.getFirstName(),
                user.getLastName(),
                user.getUserType());
    }
}
