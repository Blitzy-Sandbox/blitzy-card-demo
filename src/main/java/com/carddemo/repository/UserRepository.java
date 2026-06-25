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
package com.carddemo.repository;

import com.carddemo.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the security {@link User} record, modeling the
 * COBOL {@code SEC-USER-DATA} layout from copybook {@code app/cpy/CSUSR01Y.cpy}
 * (record length 80) at source commit {@code 27d6c6f}.
 *
 * <p>This interface replaces the legacy VSAM {@code USRSEC} KSDS keyed on the
 * 8-character {@code SEC-USR-ID}. Because every access path is by the primary
 * key, all operations are served by inherited {@link JpaRepository} methods and
 * no derived or {@code @Query} methods are required. The mainframe access
 * mechanics map to repository operations as follows:</p>
 *
 * <ul>
 *   <li>Keyed {@code READ} of {@code USRSEC} by {@code SEC-USR-ID} (sign-on
 *       authentication in {@code COSGN00C}, and the read-before-update /
 *       read-before-delete in {@code COUSR02C} and {@code COUSR03C}) &rarr;
 *       inherited {@code findById(String)}. Plaintext password comparison is
 *       upgraded to BCrypt verification in the authentication service
 *       (constraint C-003); this repository only loads the record and performs
 *       no credential logic.</li>
 *   <li>{@code STARTBR}/{@code READNEXT}/{@code READPREV} browse of {@code USRSEC}
 *       in {@code SEC-USR-ID} order (user-list screen {@code COUSR00C}, PF8
 *       forward / PF7 backward) &rarr; inherited {@code findAll(Pageable)}. The
 *       controller supplies {@code Sort.by("userId")} so the legacy
 *       {@code SEC-USR-ID} browse order is preserved.</li>
 *   <li>{@code WRITE} of a new user ({@code COUSR01C} add) &rarr; inherited
 *       {@code save(User)}.</li>
 *   <li>{@code REWRITE} of an existing user ({@code COUSR02C} update) &rarr;
 *       inherited {@code save(User)}.</li>
 *   <li>{@code DELETE} of a user ({@code COUSR03C} delete) &rarr; inherited
 *       {@code deleteById(String)}.</li>
 * </ul>
 */
@Repository
public interface UserRepository extends JpaRepository<User, String> {
}
