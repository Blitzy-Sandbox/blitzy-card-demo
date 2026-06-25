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

import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.entity.TransactionCategoryBalanceId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for transaction-category running balances
 * (legacy VSAM {@code TCATBAL} KSDS, copybook {@code CVTRA01Y},
 * {@code TRAN-CAT-BAL-RECORD} @ source commit {@code 27d6c6f}).
 *
 * <p>The composite primary key is the embeddable
 * {@link TransactionCategoryBalanceId}, which translates the 17-byte COBOL
 * {@code TRAN-CAT-KEY} group ({@code acctId} from {@code TRANCAT-ACCT-ID PIC
 * 9(11)}, {@code typeCd} from {@code TRANCAT-TYPE-CD PIC X(02)}, and
 * {@code catCd} from {@code TRANCAT-CD PIC 9(04)}). The running balance
 * {@code TRAN-CAT-BAL PIC S9(09)V99} is modeled as a {@link java.math.BigDecimal}
 * on {@link TransactionCategoryBalance}, preserving packed-decimal precision.
 *
 * <p>This interface replaces two distinct mainframe access paths to the same
 * dataset, each served entirely by inherited {@link JpaRepository} operations
 * keyed on the complete composite key (there is no partial-key browse on this
 * KSDS, so no derived finder methods are declared):</p>
 *
 * <ul>
 *   <li>Random keyed {@code READ} then {@code REWRITE}/{@code WRITE} of the
 *       balance record &mdash; the posting engine {@code CBTRN02C}
 *       (paragraph {@code 2700-UPDATE-TCATBAL}) opens {@code TCATBAL-FILE}
 *       {@code ACCESS MODE IS RANDOM}, reads the row for the supplied
 *       {@code FD-TRAN-CAT-KEY}, and either rewrites it or, when the read
 *       returns {@code FILE STATUS} {@code 23} (not found), writes a new row.
 *       This maps to {@link JpaRepository#findById(Object)} with a
 *       {@link TransactionCategoryBalanceId} key and
 *       {@link JpaRepository#save(Object)}.</li>
 *   <li>Sequential balance scan &mdash; the interest-calculation program
 *       {@code CBACT04C} (paragraph {@code 1000-TCATBALF-GET-NEXT}) opens
 *       {@code TCATBAL-FILE} {@code ACCESS MODE IS SEQUENTIAL} and reads every
 *       category balance in turn to compute interest. This maps to
 *       {@link JpaRepository#findAll()} (or the paginated
 *       {@link JpaRepository#findAll(org.springframework.data.domain.Pageable)})
 *       consumed by the chunk-oriented Spring Batch interest reader.</li>
 * </ul>
 *
 * <p>Updates to balances participate in the service-level
 * {@code @Transactional} boundary that mirrors the COBOL {@code SYNCPOINT};
 * the balance record carries no {@code @Version} column, so no optimistic
 * locking is applied to this table.</p>
 */
@Repository
public interface TransactionCategoryBalanceRepository
        extends JpaRepository<TransactionCategoryBalance, TransactionCategoryBalanceId> {
}
