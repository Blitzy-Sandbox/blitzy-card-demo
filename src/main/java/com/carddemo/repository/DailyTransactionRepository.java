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

import com.carddemo.entity.DailyTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for daily transaction staging records, modeling the
 * COBOL {@code DALYTRAN-RECORD} layout from copybook {@code app/cpy/CVTRA06Y.cpy}
 * (record length 350, keyed on {@code DALYTRAN-ID PIC X(16)}) at source commit
 * {@code 27d6c6f}.
 *
 * <p>This interface replaces the legacy {@code DALYTRAN-FILE} access driving the
 * {@code CBTRN02C} posting engine. That file is declared {@code SELECT
 * DALYTRAN-FILE ... FILE STATUS IS DALYTRAN-STATUS}, opened {@code OPEN INPUT},
 * and consumed record-by-record through the sequential {@code READ DALYTRAN-FILE
 * INTO DALYTRAN-RECORD} in paragraph {@code 1000-DALYTRAN-GET-NEXT}; each record
 * is then driven through the four-stage validation cascade and either posted or
 * rejected. There is no keyed lookup by a non-key field, so no derived or
 * {@code @Query} finder methods are declared. The mainframe access mechanics map
 * to inherited {@link JpaRepository} operations as follows:</p>
 *
 * <ul>
 *   <li>Sequential {@code READ} in key order (the {@code CBTRN02C} staging scan)
 *       &rarr; {@code findAll()} / {@code findAll(Pageable)} /
 *       {@code findAll(Sort)} (for example {@code Sort.by("tranId")}), as
 *       consumed by the chunk-oriented Spring Batch posting reader.</li>
 *   <li>Keyed {@code READ} by {@code DALYTRAN-ID} &rarr; {@code findById(String)}
 *       with a {@code String} key matching the entity {@code @Id}.</li>
 *   <li>{@code WRITE}/{@code REWRITE} of the staging feed &rarr;
 *       {@code save(DailyTransaction)}.</li>
 * </ul>
 *
 * <p>The {@code DailyTransaction} staging entity carries no {@code @Version}
 * column; optimistic locking applies only to the posted master records, not to
 * this transient input feed.</p>
 */
@Repository
public interface DailyTransactionRepository extends JpaRepository<DailyTransaction, String> {
}
