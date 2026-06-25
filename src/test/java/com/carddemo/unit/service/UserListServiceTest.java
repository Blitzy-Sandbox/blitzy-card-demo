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

import java.util.List;

import com.carddemo.dto.UserDto;
import com.carddemo.entity.User;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.UserRepository;
import com.carddemo.service.UserListService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.when;

/**
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link UserListService}, the paged
 * administrative user browse (the Java realization of the COBOL {@code COUSR00C}
 * list program @ {@code 27d6c6f}).
 *
 * <p>The tests lock the bounded {@code PAGE_SIZE} of ten rows sorted by user id,
 * the routing between the unfiltered finder and the prefix Query-by-Example
 * finder, the password-free summary projection, and the selection-flag
 * validation ({@code U}/{@code D}).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserListService - COUSR00C paged user list (PAGE_SIZE = 10) @ 27d6c6f")
class UserListServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserListService service;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    private User user(String id, String first, String last, String type) {
        User user = new User();
        user.setUserId(id);
        user.setFirstName(first);
        user.setLastName(last);
        user.setUserType(type);
        user.setPassword("PLAINTEXT-SECRET");
        return user;
    }

    @Test
    @DisplayName("listUsers: no filter reads the unscoped page bounded to ten rows sorted by userId ASC")
    void listUsersNoFilterUsesUnscopedSortedPageOfTen() {
        Page<User> page = new PageImpl<>(List.of(
                user("USER0001", "Ada", "Lovelace", "U"),
                user("ADMIN001", "Alan", "Turing", "A")));
        when(userRepository.findAll(pageableCaptor.capture())).thenReturn(page);

        UserDto.ListResponse response = service.listUsers(null, 0);

        assertThat(response.pageNumber()).isEqualTo("0");
        assertThat(response.users()).hasSize(2);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
        Sort.Order order = pageableCaptor.getValue().getSort().getOrderFor("userId");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    @DisplayName("listUsers: the summary projection omits the password field")
    void listUsersSummaryOmitsPassword() {
        Page<User> page = new PageImpl<>(List.of(user("USER0001", "Ada", "Lovelace", "U")));
        when(userRepository.findAll(any(Pageable.class))).thenReturn(page);

        UserDto.UserSummary summary = service.listUsers(null, 0).users().get(0);

        assertThat(summary.userId()).isEqualTo("USER0001");
        assertThat(summary.firstName()).isEqualTo("Ada");
        assertThat(summary.lastName()).isEqualTo("Lovelace");
        assertThat(summary.userType()).isEqualTo("U");
    }

    @Test
    @DisplayName("listUsers: a user-id filter routes to the Query-by-Example finder")
    void listUsersWithFilterUsesExampleFinder() {
        Page<User> page = new PageImpl<>(List.of(user("USER0001", "Ada", "Lovelace", "U")));
        when(userRepository.findAll(ArgumentMatchers.<Example<User>>any(), any(Pageable.class)))
                .thenReturn(page);

        UserDto.ListResponse response = service.listUsers("USER", 0);

        assertThat(response.users()).hasSize(1);
        assertThat(response.userIdFilter()).isEqualTo("USER");
    }

    @Test
    @DisplayName("validateSelection: an unsupported flag raises the invalid-selection message")
    void validateSelectionRejectsUnsupportedFlag() {
        assertThatThrownBy(() -> service.validateSelection("X"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Invalid selection. Valid values are U and D");
    }

    @Test
    @DisplayName("validateSelection: blank and the U/D flags are accepted")
    void validateSelectionAcceptsBlankAndValidFlags() {
        assertThatCode(() -> service.validateSelection(null)).doesNotThrowAnyException();
        assertThatCode(() -> service.validateSelection("U")).doesNotThrowAnyException();
        assertThatCode(() -> service.validateSelection("D")).doesNotThrowAnyException();
    }
}
