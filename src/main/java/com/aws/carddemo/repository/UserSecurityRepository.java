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
package com.aws.carddemo.repository;

import com.aws.carddemo.entity.SecurityUser;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link SecurityUser} entities — the Java
 * replacement for COBOL {@code EXEC CICS READ DATASET('USRSEC') RIDFLD(WS-USER-ID)}
 * from {@code app/cbl/COSGN00C.cbl} {@code READ-USER-SEC-FILE} paragraph.
 *
 * <h2>COBOL Provenance</h2>
 *
 * <p>The original COBOL {@code READ-USER-SEC-FILE} paragraph (COSGN00C.cbl
 * lines 209–257) consults the {@code USRSEC} VSAM KSDS file by the 8-character
 * {@code SEC-USR-ID} primary key. The CICS response code {@code WS-RESP-CD}
 * carries the lookup outcome:
 *
 * <ul>
 *   <li>{@code WS-RESP-CD = 0} (DFHRESP NORMAL) — record found, dispatch authentication</li>
 *   <li>{@code WS-RESP-CD = 13} (DFHRESP NOTFND) — user not found, reject sign-on</li>
 *   <li>{@code WS-RESP-CD = WHEN OTHER} — I/O error, fail with "Unable to verify..."</li>
 * </ul>
 *
 * <p>The Spring Data JPA equivalent is {@link #findById(Object)}: it returns
 * {@code Optional.of(user)} for the NORMAL case and {@code Optional.empty()} for
 * the NOTFND case. I/O errors surface as {@link org.springframework.dao.DataAccessException}
 * subclasses and propagate up to the caller (the production
 * {@link com.aws.carddemo.service.AuthenticationService} translates them into the
 * "Unable to verify..." reject message in production code).
 *
 * <h2>Design Note — Stub Status</h2>
 *
 * <p>This interface is a <strong>minimum-viable JPA repository</strong> created to
 * satisfy {@link com.aws.carddemo.service.AuthenticationService} compilation and
 * the authentication test suite. Subsequent migration agents (REFACTOR flavor)
 * will add a {@code @Repository} stereotype annotation (if the project policy
 * requires explicit stereotype marking — Spring Data infers the bean from the
 * {@code JpaRepository} extension and a stereotype is not strictly necessary) and
 * custom-query methods (e.g., {@code findByUserType(String userType)} for the
 * admin-only user-list endpoint).
 *
 * @see com.aws.carddemo.service.AuthenticationService
 * @see SecurityUser
 */
public interface UserSecurityRepository extends JpaRepository<SecurityUser, String> {
    // All required methods (findById, save, deleteById, ...) are inherited from
    // JpaRepository. Custom query methods will be added by subsequent migration
    // agents as additional COBOL programs (COUSR00C-COUSR03C) are migrated.
}
