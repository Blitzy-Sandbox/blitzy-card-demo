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

import com.carddemo.entity.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for transaction-type reference codes
 * (copybook {@code CVTRA03Y}, {@code TRAN-TYPE-RECORD} @ {@code 27d6c6f}).
 *
 * <p>Replaces the legacy VSAM {@code TRANTYPE} KSDS keyed on
 * {@code TRAN-TYPE PIC X(02)}. The transaction-report enrichment step
 * {@code CBTRN03C} (paragraph {@code 1500-B-LOOKUP-TRANTYPE}) issues a keyed
 * {@code READ TRANTYPE-FILE} on the transaction-type code to resolve the
 * {@code TRAN-TYPE-DESC} description for each report line. The mainframe access
 * mechanics map to inherited repository operations as follows:</p>
 *
 * <ul>
 *   <li>Keyed {@code READ} by {@code TRAN-TYPE} →
 *       {@link JpaRepository#findById(Object)} with a {@link String} key.</li>
 *   <li>Full-table load for caching enrichment lookups →
 *       {@link JpaRepository#findAll()}.</li>
 *   <li>{@code WRITE}/{@code REWRITE} of {@code TRANTYPE} →
 *       {@link JpaRepository#save(Object)}.</li>
 * </ul>
 *
 * <p>The key type is {@link String}, matching the entity's
 * {@code @Id String tranType} ({@code CHAR(2)}). The composite-keyed
 * {@code TRANCATG} table is a separate concern served by
 * {@code TransactionCategoryRepository}.</p>
 */
@Repository
public interface TransactionTypeRepository extends JpaRepository<TransactionType, String> {
}
