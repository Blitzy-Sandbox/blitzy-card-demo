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

import com.carddemo.entity.TransactionCategory;
import com.carddemo.entity.TransactionCategoryId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for transaction-category reference codes
 * (copybook {@code CVTRA04Y}, {@code TRAN-CAT-RECORD} @ {@code 27d6c6f}).
 *
 * <p>Replaces the legacy VSAM {@code TRANCATG} KSDS keyed on the composite
 * {@code TRAN-CAT-KEY} group ({@code TRAN-TYPE-CD PIC X(02)} followed by
 * {@code TRAN-CAT-CD PIC 9(04)}). The transaction-report enrichment step
 * {@code CBTRN03C} (paragraph {@code 1500-C-LOOKUP-TRANCATG}) issues a keyed
 * {@code READ TRANCATG-FILE} on that composite key to resolve the
 * {@code TRAN-CAT-TYPE-DESC} description for each report line. The mainframe
 * access mechanics map to inherited repository operations as follows:</p>
 *
 * <ul>
 *   <li>Keyed {@code READ} by {@code TRAN-CAT-KEY} &rarr;
 *       {@link JpaRepository#findById(Object)} with a
 *       {@link TransactionCategoryId} composite key.</li>
 *   <li>Full-table load for caching enrichment lookups &rarr;
 *       {@link JpaRepository#findAll()}.</li>
 *   <li>{@code WRITE}/{@code REWRITE} of {@code TRANCATG} &rarr;
 *       {@link JpaRepository#save(Object)}.</li>
 * </ul>
 *
 * <p>The key type is the {@code @Embeddable} {@link TransactionCategoryId}
 * ({@code tranTypeCd} + {@code tranCatCd}), consumed by
 * {@link TransactionCategory} through {@code @EmbeddedId}. This is the
 * composite-keyed sibling of the simple-keyed {@code TransactionTypeRepository};
 * the report step joins both lookups for description enrichment. No custom
 * finder methods are declared because no COBOL access path reads
 * {@code TRANCATG} by anything other than the full composite key.</p>
 */
@Repository
public interface TransactionCategoryRepository
        extends JpaRepository<TransactionCategory, TransactionCategoryId> {
}
