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
package com.carddemo.unit.service;

import java.lang.reflect.RecordComponent;
import java.util.List;

import com.carddemo.dto.UserDto;
import com.carddemo.entity.User;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.UserRepository;
import com.carddemo.service.UserListService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link UserListService}, the Java
 * realization of the CICS pseudo-conversational program {@code COUSR00C}
 * (transaction {@code CU00}, "List all users from USRSEC file") at source commit
 * {@code 27d6c6f}, backing {@code GET /api/admin/users} (AAP&nbsp;&sect;0.4.1.1).
 *
 * <p>The legacy program browsed the VSAM {@code USRSEC} KSDS with
 * {@code STARTBR}/{@code READNEXT}/{@code READPREV} and rendered ten rows per
 * screen ({@code USER-REC OCCURS 10 TIMES}). The service expresses that as Spring
 * Data {@link Pageable} paging with a fixed page size of ten ordered by
 * {@code SEC-USR-ID} ({@code userId}); PF8/PF7 navigation maps to a
 * <em>zero-based</em> page index that is forwarded verbatim to
 * {@link org.springframework.data.domain.PageRequest}. Each row is projected to a
 * {@link UserDto.UserSummary} carrying the user id, first/last name and user type
 * only &mdash; the BCrypt password hash is never read into a response object.</p>
 *
 * <p>These tests lock the compiled contract exactly:</p>
 * <ul>
 *   <li>the captured {@link Pageable} always requests a page size of {@code 10},
 *       the supplied zero-based index, and an ascending sort on {@code userId};</li>
 *   <li>{@link UserDto.UserSummary} has exactly the four password-free components
 *       and no password is ever copied into a summary;</li>
 *   <li>an empty page is a normal result (no exception);</li>
 *   <li>a non-blank user-id filter is applied with a case-sensitive
 *       {@code startsWith} Query-by-Example probe (the repository declares
 *       <strong>no</strong> derived finder); and</li>
 *   <li>{@code validateSelection} accepts blank/{@code null}/{@code U}/{@code D}
 *       (any case) and rejects everything else with the byte-for-byte legacy
 *       message {@code "Invalid selection. Valid values are U and D"}
 *       ({@code COUSR00C.cbl} line&nbsp;212).</li>
 * </ul>
 *
 * <p>The suite is deliberately framework-free: no {@code @SpringBootTest}, no
 * Spring {@code ApplicationContext}, no Testcontainers, and no database. The
 * single collaborator {@link UserRepository} is Mockito-mocked, and only its
 * inherited {@code findAll(Pageable)} and {@code findAll(Example, Pageable)}
 * methods are exercised. {@link MockitoExtension} runs with its default
 * strict-stubs policy, so every stub declared below is consumed by the code
 * under test.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserListService - COUSR00C (CU00) user-list paging @ 27d6c6f")
class UserListServiceTest {

    /**
     * Obviously-synthetic, BCrypt-shaped password placeholders. They are never
     * real credentials (the embedded {@code DoNotUse} marker makes that explicit)
     * and exist only to prove that no hash value reaches a {@link UserDto.UserSummary}.
     */
    private static final String HASH_ADMIN =
            "$2a$10$DoNotUseAdmin001PlaceholderHashValueAAAAAAAAAAAAAAAAAAAA";
    private static final String HASH_USER1 =
            "$2a$10$DoNotUseUser0001PlaceholderHashValueBBBBBBBBBBBBBBBBBBBB";
    private static final String HASH_USER2 =
            "$2a$10$DoNotUseUser0002PlaceholderHashValueCCCCCCCCCCCCCCCCCCCC";

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserListService service;

    /**
     * Captures the typed {@link Example} probe handed to the Query-by-Example
     * overload. Declared as a field (rather than via
     * {@code ArgumentCaptor.forClass(Example.class)}) so the generic type is
     * retained without a raw-type warning under {@code -Xlint:all -Werror}.
     */
    @Captor
    private ArgumentCaptor<Example<User>> exampleCaptor;

    // -----------------------------------------------------------------
    // Phase 1 - a single list page (no filter): row mapping + no password
    // -----------------------------------------------------------------

    @Test
    @DisplayName("listUsers: maps each USRSEC row to a password-free UserSummary, in browse order")
    void listUsersMapsEachUserToSummary() {
        Page<User> page = new PageImpl<>(sampleUsers());
        when(userRepository.findAll(any(Pageable.class))).thenReturn(page);

        UserDto.ListResponse response = service.listUsers(null, 0);

        assertThat(response.users())
                .containsExactly(
                        new UserDto.UserSummary("ADMIN001", "Adam", "Admin", "A"),
                        new UserDto.UserSummary("USER0001", "Uma", "User", "U"),
                        new UserDto.UserSummary("USER0002", "Ulf", "User", "U"));
    }

    @Test
    @DisplayName("listUsers: UserSummary has exactly four fields and never carries the password")
    void listUsersNeverExposesPassword() {
        Page<User> page = new PageImpl<>(sampleUsers());
        when(userRepository.findAll(any(Pageable.class))).thenReturn(page);

        UserDto.ListResponse response = service.listUsers(null, 0);

        // Structural guarantee: the projection record exposes only these four
        // components, so a password accessor cannot exist at all.
        assertThat(UserDto.UserSummary.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("userId", "firstName", "lastName", "userType");

        // Behavioural guarantee: no hash value leaked into any summary field.
        List<String> hashes = List.of(HASH_ADMIN, HASH_USER1, HASH_USER2);
        assertThat(response.users()).isNotEmpty().allSatisfy(summary -> {
            assertThat(summary.userId()).isNotIn(hashes);
            assertThat(summary.firstName()).isNotIn(hashes);
            assertThat(summary.lastName()).isNotIn(hashes);
            assertThat(summary.userType()).isNotIn(hashes);
        });
    }

    // -----------------------------------------------------------------
    // Phase 2 - paging metadata (page size 10, zero-based index, sort)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("listUsers: requests page size 10, the zero-based index, sorted by userId ASC")
    void listUsersRequestsTenRowsZeroBasedSortedByUserId() {
        when(userRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(sampleUsers()));

        service.listUsers(null, 3);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository).findAll(pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getPageSize()).isEqualTo(10);
        assertThat(pageable.getPageNumber()).isEqualTo(3);
        Sort.Order order = pageable.getSort().getOrderFor("userId");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    @DisplayName("listUsers: echoes the zero-based page number as a String and the null filter unchanged")
    void listUsersEchoesPageNumberAndFilter() {
        when(userRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(sampleUsers()));

        UserDto.ListResponse response = service.listUsers(null, 7);

        assertThat(response.pageNumber()).isEqualTo("7");
        assertThat(response.userIdFilter()).isNull();
    }

    // -----------------------------------------------------------------
    // Phase 3 - an empty page is a normal (non-error) result
    // -----------------------------------------------------------------

    @Test
    @DisplayName("listUsers: an empty page yields an empty users list without throwing")
    void listUsersEmptyPageYieldsEmptyList() {
        List<User> none = List.of();
        when(userRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(none));

        UserDto.ListResponse response = service.listUsers(null, 0);

        assertThat(response.users()).isEmpty();
        assertThat(response.pageNumber()).isEqualTo("0");
    }

    // -----------------------------------------------------------------
    // Filter path - a non-blank user-id prefix uses Query by Example
    // (the repository declares no derived finder, so QBE replaces it)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("listUsers: a user-id filter restricts the page with a startsWith QBE probe on userId")
    void listUsersWithFilterQueriesByUserIdPrefix() {
        List<User> filtered = List.of(user("USER0001", "Uma", "User", HASH_USER1, "U"));
        when(userRepository.findAll(ArgumentMatchers.<Example<User>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(filtered));

        UserDto.ListResponse response = service.listUsers("USER", 0);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository).findAll(exampleCaptor.capture(), pageableCaptor.capture());

        // The probe carries the supplied prefix on the userId property.
        assertThat(exampleCaptor.getValue().getProbe().getUserId()).isEqualTo("USER");
        // Paging metadata is preserved on the filtered path as well.
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        // The filter is echoed back, and the single matching row is projected.
        assertThat(response.userIdFilter()).isEqualTo("USER");
        assertThat(response.users())
                .containsExactly(new UserDto.UserSummary("USER0001", "Uma", "User", "U"));
    }

    // -----------------------------------------------------------------
    // listUserSummaries - the paged-projection variant of the same browse
    // -----------------------------------------------------------------

    @Test
    @DisplayName("listUserSummaries: returns a Page of password-free projections with paging metadata")
    void listUserSummariesReturnsPagedProjections() {
        when(userRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(sampleUsers()));

        Page<UserDto.UserSummary> result = service.listUserSummaries(null, 0);

        assertThat(result.getTotalElements()).isEqualTo(3L);
        assertThat(result.getContent())
                .containsExactly(
                        new UserDto.UserSummary("ADMIN001", "Adam", "Admin", "A"),
                        new UserDto.UserSummary("USER0001", "Uma", "User", "U"),
                        new UserDto.UserSummary("USER0002", "Ulf", "User", "U"));
    }

    // -----------------------------------------------------------------
    // Phase 4 - per-row selection validation (COUSR00C PROCESS-ENTER-KEY)
    // -----------------------------------------------------------------

    @ParameterizedTest(name = "selection=\"{0}\"")
    @ValueSource(strings = {"X", "A", "Z", "1", "ud", "UU"})
    @DisplayName("validateSelection: any value other than U/D raises the verbatim COUSR00C message")
    void validateSelectionRejectsInvalidCodes(String selection) {
        assertThatThrownBy(() -> service.validateSelection(selection))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Invalid selection. Valid values are U and D");
        verifyNoInteractions(userRepository);
    }

    @ParameterizedTest(name = "selection=\"{0}\"")
    @NullSource
    @ValueSource(strings = {"", "   ", "U", "u", "D", "d"})
    @DisplayName("validateSelection: blank, null and U/D (any case) are accepted without error")
    void validateSelectionAcceptsBlankNullAndUpdateDelete(String selection) {
        assertThatCode(() -> service.validateSelection(selection)).doesNotThrowAnyException();
        verifyNoInteractions(userRepository);
    }

    // -----------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------

    /**
     * Three security users in ascending {@code SEC-USR-ID} order: one admin
     * ({@code A}) and two regular users ({@code U}). Each carries a synthetic
     * BCrypt-shaped password placeholder used only to prove non-exposure.
     *
     * @return an immutable, browse-ordered list of users
     */
    private static List<User> sampleUsers() {
        return List.of(
                user("ADMIN001", "Adam", "Admin", HASH_ADMIN, "A"),
                user("USER0001", "Uma", "User", HASH_USER1, "U"),
                user("USER0002", "Ulf", "User", HASH_USER2, "U"));
    }

    /**
     * Builds a fully populated {@link User} via its all-args constructor.
     *
     * @param id    the 8-character user id (primary key, {@code SEC-USR-ID})
     * @param first the first name ({@code SEC-USR-FNAME})
     * @param last  the last name ({@code SEC-USR-LNAME})
     * @param hash  the BCrypt-shaped password placeholder ({@code SEC-USR-PWD})
     * @param type  the user type ({@code SEC-USR-TYPE}; {@code A} or {@code U})
     * @return the constructed user
     */
    private static User user(String id, String first, String last, String hash, String type) {
        return new User(id, first, last, hash, type);
    }
}
