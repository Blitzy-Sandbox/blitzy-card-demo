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

import com.aws.carddemo.entity.TransactionCategory;
import com.aws.carddemo.entity.TransactionCategoryKey;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link TransactionCategory} entities — the
 * Java replacement for COBOL {@code EXEC CICS READ DATASET('TRANCATG')
 * RIDFLD(TRAN-CAT-KEY)} lookups against the {@code TRANCATG} VSAM KSDS
 * reference catalog described by {@code app/cpy/CVTRA04Y.cpy} and seeded
 * from {@code app/data/ASCII/trancatg.txt}.
 *
 * <h2>Reference Data (Read-Only Catalog)</h2>
 *
 * <p>Unlike the transactional repositories ({@code AccountRepository},
 * {@code CardRepository}, {@code TransactionRepository}) that participate
 * in business workflows with write paths, this repository fronts a pure
 * <em>reference-data</em> catalog. The 18 canonical rows in
 * {@code app/data/ASCII/trancatg.txt} are keyed by the composite
 * {@code (TRAN-TYPE-CD, TRAN-CAT-CD)} tuple — each row describes one
 * sub-category of a parent transaction type:
 *
 * <pre>
 *   (01, 0001)  Regular Sales Draft
 *   (01, 0002)  Regular Cash Advance
 *   (01, 0003)  Convenience Check Debit
 *   (01, 0004)  ATM Cash Advance
 *   (01, 0005)  Interest Amount
 *   (02, 0001)  Cash payment
 *   (02, 0002)  Electronic payment
 *   (02, 0003)  Check payment
 *   (03, 0001)  Credit to Account
 *   (03, 0002)  Credit to Purchase balance
 *   (03, 0003)  Credit to Cash balance
 *   (04, 0001)  Zero dollar authorization
 *   (04, 0002)  Online purchase authorization
 *   (04, 0003)  Travel booking authorization
 *   (05, 0001)  Refund credit
 *   (06, 0001)  Fraud reversal
 *   (06, 0002)  Non-fraud reversal
 *   (07, 0001)  Sales draft credit adjustment
 * </pre>
 *
 * <p>These rows are loaded by Flyway at application startup and remain
 * immutable thereafter. Business workflows that need to validate or
 * render the {@code (TRAN-TYPE-CD, TRAN-CAT-CD)} pair on a
 * {@link com.aws.carddemo.entity.Transaction} row (e.g. the
 * transaction-add validation cascade in {@code COTRN02C}, the
 * transaction-report rendering in {@code CBTRN03C}, the statement
 * rendering in {@code CBSTM03A}) call {@link #findById(Object)} to
 * resolve the human-readable description.
 *
 * <h2>Primary Key Type — TransactionCategoryKey</h2>
 *
 * <p>The {@code TransactionCategory} primary key is a composite value
 * object ({@link TransactionCategoryKey}) rather than a single scalar
 * because the same numeric category code repeats across different
 * transaction types (e.g. category {@code 0001} appears under types
 * {@code 01}, {@code 02}, {@code 03}, {@code 04}, {@code 05}, {@code 06},
 * and {@code 07}). The COBOL {@code TRAN-CAT-KEY} group-level item
 * captures this composite identity exactly; the Java repository
 * generic-type parameter {@code <TransactionCategory, TransactionCategoryKey>}
 * mirrors that structural choice so that {@link #findById(Object)} accepts
 * a typed key rather than a stringly-typed concatenation.
 *
 * <h2>Design Note — Stub Status</h2>
 *
 * <p>This interface is a <strong>minimum-viable JPA repository</strong>
 * created to satisfy the {@code TransactionCategoryRepositoryIT}
 * integration test suite (AAP §0.5.1) and any downstream service that
 * needs to look up the transaction-category description by composite key.
 * Subsequent migration agents (REFACTOR flavor) will:
 *
 * <ul>
 *   <li>Add the {@code @Entity} / {@code @EmbeddedId} / {@code @Column} /
 *       {@code @Table(name = "transaction_categories")} JPA annotations on
 *       {@link TransactionCategory} and the {@code @Embeddable} annotation
 *       on {@link TransactionCategoryKey} so Hibernate can map the entity
 *       onto the Flyway-created table.</li>
 *   <li>Author the Flyway scripts
 *       {@code src/main/resources/db/migration/V1__schema.sql} (CREATE
 *       TABLE {@code transaction_categories} with composite primary key
 *       {@code (tran_type_cd CHAR(2), tran_cat_cd INTEGER)}) and
 *       {@code V3__seed.sql} (18 INSERT rows from
 *       {@code app/data/ASCII/trancatg.txt}).</li>
 *   <li>Optionally add a {@code @Repository} stereotype annotation if the
 *       project policy requires explicit stereotype marking — Spring Data
 *       infers the bean from the {@code JpaRepository} extension and a
 *       stereotype is not strictly necessary.</li>
 * </ul>
 *
 * <p>This interface intentionally exposes no custom query methods at the
 * stub stage. The inherited {@link JpaRepository#findById(Object)},
 * {@link JpaRepository#findAll()}, {@link JpaRepository#save(Object)},
 * and {@link JpaRepository#count()} satisfy every documented business
 * workflow that consumes the transaction-category catalog. Per AAP
 * §0.10.2 Minimal Change Clause, no speculative
 * {@code findByTranTypeCd(String)} or
 * {@code findAllByTranCatTypeDescContaining(String)} method is added —
 * the canonical access pattern is composite-key lookup, and downstream
 * consumers iterate the catalog via {@link JpaRepository#findAll()} when
 * a global pass is required (which is rare given the 18-row catalog
 * fits comfortably in memory).
 *
 * @see TransactionCategory
 * @see TransactionCategoryKey
 * @see com.aws.carddemo.entity.Transaction#getTransactionTypeCode()
 * @see com.aws.carddemo.entity.Transaction#getTransactionCategoryCode()
 */
public interface TransactionCategoryRepository
        extends JpaRepository<TransactionCategory, TransactionCategoryKey> {
    // All required methods (findById, findAll, save, count, deleteById, ...)
    // are inherited from JpaRepository. Custom query methods are deliberately
    // omitted at the stub stage per AAP §0.10.2 Minimal Change Clause; the
    // TRANCATG catalog is small (18 rows) and downstream consumers iterate
    // the catalog via findAll() or look up individual rows via
    // findById(TransactionCategoryKey) — no exotic query is required.
}
