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
package com.carddemo.repository;

import com.carddemo.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the account master {@code ACCTFILE} /
 * {@code ACCOUNT-RECORD} ({@code CVACT01Y}) at source commit {@code 27d6c6f},
 * keyed by {@code ACCT-ID}.
 *
 * <p>The legacy VSAM KSDS access paths map to inherited {@link JpaRepository}
 * operations: the keyed {@code READ} ({@code COACTVWC}, {@code COACTUPC},
 * {@code COBIL00C}) maps to {@code findById(Long)}; the keyed {@code REWRITE}
 * ({@code COACTUPC}, {@code COBIL00C}) maps to {@code save(Account)}; and the
 * sequential master read in key order ({@code CBACT01C}) maps to
 * {@code findAll(Sort)} with {@code Sort.by("acctId")}. Account access is
 * entirely by the primary key, so no derived or {@code @Query} methods are
 * required.</p>
 *
 * <p>Optimistic locking (the COBOL {@code 9300-CHECK-CHANGE-IN-REC}
 * re-read-and-compare guard) is carried by the {@link Account} entity's
 * {@code @Version} field; the unit-of-work boundary (CICS {@code SYNCPOINT}
 * parity) is owned by the service layer's {@code @Transactional} scope.</p>
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
}
