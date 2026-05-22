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

import com.aws.carddemo.entity.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link TransactionType} entities — the
 * Java replacement for COBOL {@code EXEC CICS READ DATASET('TRANTYPE')
 * RIDFLD(TRAN-TYPE)} lookups against the {@code TRANTYPE} VSAM KSDS
 * reference catalog described by {@code app/cpy/CVTRA03Y.cpy} and seeded
 * from {@code app/data/ASCII/trantype.txt}.
 *
 * <h2>Reference Data (Read-Only Catalog)</h2>
 *
 * <p>Unlike the transactional repositories ({@code AccountRepository},
 * {@code CardRepository}, {@code TransactionRepository}) that participate
 * in business workflows with write paths, this repository fronts a pure
 * <em>reference-data</em> catalog. The 7 canonical rows in
 * {@code app/data/ASCII/trantype.txt}:
 *
 * <pre>
 *   01  Purchase
 *   02  Payment
 *   03  Credit
 *   04  Authorization
 *   05  Refund
 *   06  Reversal
 *   07  Adjustment
 * </pre>
 *
 * <p>are loaded by Flyway at application startup and remain immutable
 * thereafter. Business workflows that need to validate or render the
 * {@code TRAN-TYPE-CD} on a {@link com.aws.carddemo.entity.Transaction}
 * row (e.g. the transaction-add validation cascade in {@code COTRN02C},
 * the transaction-report rendering in {@code CBTRN03C}, the statement
 * rendering in {@code CBSTM03A}) call {@link #findById(Object)} to
 * resolve the human-readable description.
 *
 * <h2>Primary Key Type — String</h2>
 *
 * <p>The {@code TransactionType} primary key
 * {@link TransactionType#getTranType()} is a {@link String} (2-character
 * {@code PIC X(02)}) rather than an {@link Integer} or {@link Long} so
 * that the byte-for-byte VSAM key format is preserved across the
 * migration. The COBOL {@code TRAN-TYPE} field is alphanumeric (the
 * values happen to be zero-padded numeric strings {@code "01"}–{@code "07"}
 * in the canonical seed, but the COBOL type allows future expansion to
 * non-numeric codes without re-keying — using {@link String} preserves
 * that flexibility).
 *
 * <h2>Design Note — Stub Status</h2>
 *
 * <p>This interface is a <strong>minimum-viable JPA repository</strong>
 * created to satisfy the {@code TransactionTypeRepositoryIT} integration
 * test suite (AAP §0.5.1) and any downstream service that needs to look
 * up the transaction-type description by code. Subsequent migration
 * agents (REFACTOR flavor) will:
 *
 * <ul>
 *   <li>Add the {@code @Entity} / {@code @Id} / {@code @Column} /
 *       {@code @Table(name = "transaction_types")} JPA annotations on
 *       {@link TransactionType} so Hibernate can map the entity onto the
 *       Flyway-created table.</li>
 *   <li>Author the Flyway scripts
 *       {@code src/main/resources/db/migration/V1__schema.sql} (CREATE
 *       TABLE) and {@code V3__seed.sql} (7 INSERT rows from
 *       {@code app/data/ASCII/trantype.txt}).</li>
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
 * workflow that consumes the transaction-type catalog. Per AAP §0.10.2
 * Minimal Change Clause, no speculative {@code findByDescription(...)} or
 * similar method is added.
 *
 * @see TransactionType
 * @see com.aws.carddemo.entity.Transaction#getTransactionTypeCode()
 */
public interface TransactionTypeRepository extends JpaRepository<TransactionType, String> {
    // All required methods (findById, findAll, save, count, deleteById, ...)
    // are inherited from JpaRepository. Custom query methods are deliberately
    // omitted at the stub stage per AAP §0.10.2 Minimal Change Clause; the
    // TRANTYPE catalog is small (7 rows) and downstream consumers iterate the
    // catalog via findAll() or look up individual rows via findById(String)
    // — no exotic query is required.
}
