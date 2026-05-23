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

import com.aws.carddemo.entity.TransactionCategoryBalance;
import com.aws.carddemo.entity.TransactionCategoryBalanceKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Spring Data JPA repository for {@link TransactionCategoryBalance}
 * entities — the Java replacement for COBOL {@code EXEC CICS READ
 * DATASET('TCATBAL') RIDFLD(TRAN-CAT-KEY)} lookups against the
 * {@code TCATBAL} VSAM KSDS catalog described by
 * {@code app/cpy/CVTRA01Y.cpy} and seeded from
 * {@code app/data/ASCII/tcatbal.txt}.
 *
 * <h2>CBACT04C — Interest Calculation Update Path (AAP §0.5.1)</h2>
 *
 * <p>The {@code CBACT04C} interest calculator (migrated to
 * {@code com.aws.carddemo.batch.InterestCalculationProcessor}) is the
 * principal consumer of this repository:
 * <ol>
 *   <li>The processor reads each {@link TransactionCategoryBalance} row
 *       via {@link #findById(Object)} keyed by the composite
 *       {@link TransactionCategoryBalanceKey}.</li>
 *   <li>The processor looks up the matching {@code DIS-INT-RATE} in the
 *       {@code DISCGRP} catalog
 *       ({@link com.aws.carddemo.repository.DiscountGroupRepository})
 *       using the account's {@code ACCT-GROUP-ID} plus the type and
 *       category codes from the composite key.</li>
 *   <li>The processor computes the monthly interest in production code
 *       (AAP §0.10.3 financial-precision contract — {@code BigDecimal}
 *       with {@code RoundingMode.HALF_EVEN}, scale 2).</li>
 *   <li>The processor REWRITEs the row via {@link #save(Object)},
 *       resetting {@code TRAN-CAT-BAL} to {@code 0.00} after the
 *       interest is captured in a {@link com.aws.carddemo.entity.Transaction}
 *       record.</li>
 * </ol>
 *
 * <p>The account-scoped batch processing loop (CBACT04C's "iterate all
 * categories for one account" pattern) uses {@link #findByAccountId(String)}
 * to retrieve every {@code TCATBAL} row for a single account in one
 * query, instead of streaming the entire catalog and filtering in
 * Java memory.
 *
 * <h2>Primary Key Type — TransactionCategoryBalanceKey</h2>
 *
 * <p>The {@link TransactionCategoryBalance} primary key is a composite
 * value object ({@link TransactionCategoryBalanceKey}) rather than a
 * single scalar because the same {@code (TRANCAT-TYPE-CD, TRANCAT-CD)}
 * pair repeats across many accounts (one TCATBAL row per category per
 * account), and the same {@code (TRANCAT-ACCT-ID, TRANCAT-CD)} pair can
 * repeat across different transaction types (one account can have a
 * purchase row and a payment row for the same category). The COBOL
 * {@code TRAN-CAT-KEY} group-level item captures this composite
 * identity exactly; the Java repository generic-type parameter
 * {@code <TransactionCategoryBalance, TransactionCategoryBalanceKey>}
 * mirrors that structural choice so that {@link #findById(Object)}
 * accepts a typed key rather than a stringly-typed concatenation.
 *
 * <h2>Custom Query — findByAccountId</h2>
 *
 * <p>The {@link #findByAccountId(String)} method carries an explicit
 * {@code @Query} annotation that selects every
 * {@link TransactionCategoryBalance} row whose composite-key
 * {@code TRANCAT-ACCT-ID} component matches the supplied account
 * identifier. The explicit JPQL is preferred over the Spring Data
 * derived-query convention {@code findByKeyTrancatAcctId(String)}
 * because the consumer-facing method name {@code findByAccountId}
 * makes the call sites in {@code InterestCalculationProcessor} and
 * {@code TransactionCategoryBalanceRepositoryIT} read naturally — the
 * {@code Key} prefix is an implementation detail of the
 * {@code @EmbeddedId} pattern that should not leak into call sites.
 *
 * <p>This method is invoked from {@code InterestCalculationProcessor}
 * when the processor iterates every category balance for one account
 * (the COBOL {@code READ NEXT until IF TRANCAT-ACCT-ID NOT EQUAL}
 * loop). It is also invoked by the
 * {@code TransactionCategoryBalanceRepositoryIT} account-scoped
 * aggregation test that asserts on every row for a single account.
 *
 * <h2>Design Note — Stub Status</h2>
 *
 * <p>This interface is a <strong>minimum-viable JPA repository</strong>
 * created to satisfy the {@code TransactionCategoryBalanceRepositoryIT}
 * integration test suite (AAP §0.5.1) and the
 * {@code InterestCalculationProcessor} batch processor (REFACTOR-flavor,
 * AAP §0.5.1). Subsequent migration agents (REFACTOR flavor) will:
 *
 * <ul>
 *   <li>Add the {@code @Entity} / {@code @EmbeddedId} / {@code @Column} /
 *       {@code @Table(name = "transaction_category_balances")} JPA
 *       annotations on {@link TransactionCategoryBalance} and the
 *       {@code @Embeddable} annotation on
 *       {@link TransactionCategoryBalanceKey} so Hibernate can map the
 *       entity onto the Flyway-created table.</li>
 *   <li>Author the Flyway scripts
 *       {@code src/main/resources/db/migration/V1__schema.sql} (CREATE
 *       TABLE {@code transaction_category_balances} with composite
 *       primary key {@code (trancat_acct_id CHAR(11), trancat_type_cd
 *       CHAR(2), trancat_cd INTEGER)} and balance column
 *       {@code tran_cat_bal NUMERIC(11, 2) NOT NULL}) and
 *       {@code V3__seed.sql} (50 INSERT rows from
 *       {@code app/data/ASCII/tcatbal.txt}).</li>
 *   <li>Optionally add a {@code @Repository} stereotype annotation if
 *       the project policy requires explicit stereotype marking —
 *       Spring Data infers the bean from the {@code JpaRepository}
 *       extension and a stereotype is not strictly necessary.</li>
 * </ul>
 *
 * <p>This interface intentionally exposes only the one custom query
 * method ({@link #findByAccountId(String)}) that {@code CBACT04C}'s
 * account-scoped loop requires. Per AAP §0.10.2 Minimal Change Clause,
 * no speculative {@code findByTrancatTypeCd(String)} or
 * {@code findAllByTranCatBalGreaterThan(BigDecimal)} methods are added
 * — the canonical access patterns are composite-key lookup
 * ({@link #findById(Object)}), account-scoped iteration
 * ({@link #findByAccountId(String)}), single-row update
 * ({@link #save(Object)}), and bulk catalog count ({@link #count()}).
 *
 * @see TransactionCategoryBalance
 * @see TransactionCategoryBalanceKey
 * @see com.aws.carddemo.repository.DiscountGroupRepository
 */
public interface TransactionCategoryBalanceRepository
        extends JpaRepository<TransactionCategoryBalance, TransactionCategoryBalanceKey> {

    /**
     * Retrieves every {@link TransactionCategoryBalance} row whose
     * composite-key {@code TRANCAT-ACCT-ID} component matches the
     * supplied account identifier.
     *
     * <p>The explicit {@code @Query} annotation carries the JPQL that
     * navigates from the entity to the embedded-key nested property:
     * <pre>
     *   SELECT t FROM TransactionCategoryBalance t
     *   WHERE t.key.trancatAcctId = :accountId
     * </pre>
     *
     * <p>The explicit JPQL is preferred over the Spring Data derived-query
     * convention {@code findByKeyTrancatAcctId(String)} because the
     * consumer-facing method name {@code findByAccountId} makes the call
     * sites in {@code InterestCalculationProcessor} and the
     * {@code TransactionCategoryBalanceRepositoryIT} account-scoped
     * aggregation test read naturally — the {@code Key} prefix is an
     * implementation detail of the {@code @EmbeddedId} pattern that
     * should not leak into call sites.
     *
     * <p>This method is the migrated equivalent of COBOL {@code CBACT04C}'s
     * "iterate all categories for one account" loop:
     * <pre>
     *   READ TCATBAL-FILE.
     *   PERFORM UNTIL TCATBAL-EOF = 'Y'
     *     IF TRANCAT-ACCT-ID OF TCATBAL-RECORD = WS-CURRENT-ACCOUNT
     *       ...process category balance...
     *     ELSE
     *       MOVE 'Y' TO TCATBAL-EOF
     *     END-IF
     *     READ NEXT TCATBAL-FILE
     *   END-PERFORM.
     * </pre>
     *
     * @param accountId the 11-digit zero-padded {@code TRANCAT-ACCT-ID}
     *                  value to filter on (e.g., {@code "00000000010"}).
     *                  Per the COBOL {@code PIC 9(11)} byte-for-byte
     *                  parity contract, the value must be exactly 11
     *                  characters wide; shorter inputs would not match
     *                  the {@code CHAR(11)} column values and would
     *                  silently return an empty result.
     * @return a possibly-empty {@link List} of
     *         {@link TransactionCategoryBalance} rows for the supplied
     *         account. The returned list is independent of any other
     *         account's rows; the ordering is unspecified at the
     *         repository layer (callers that require a specific order
     *         can sort downstream or extend this signature with a
     *         {@link org.springframework.data.domain.Sort} parameter).
     */
    @Query("SELECT t FROM TransactionCategoryBalance t WHERE t.key.trancatAcctId = :accountId")
    List<TransactionCategoryBalance> findByAccountId(@Param("accountId") String accountId);
}
