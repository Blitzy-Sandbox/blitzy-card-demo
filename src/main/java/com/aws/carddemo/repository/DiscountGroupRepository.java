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

import com.aws.carddemo.entity.DiscountGroup;
import com.aws.carddemo.entity.DiscountGroupKey;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link DiscountGroup} entities — the
 * Java replacement for COBOL
 * {@code EXEC CICS READ DATASET('DISCGRP') RIDFLD(DIS-GROUP-KEY)} lookups
 * against the {@code DISCGRP} VSAM KSDS reference catalog described by
 * {@code app/cpy/CVTRA02Y.cpy} and seeded from
 * {@code app/data/ASCII/discgrp.txt}.
 *
 * <h2>Reference Data (Read-Only Catalog)</h2>
 *
 * <p>Unlike the transactional repositories ({@code AccountRepository},
 * {@code CardRepository}, {@code TransactionRepository}) that participate
 * in business workflows with write paths, this repository fronts a pure
 * <em>reference-data</em> catalog. The 51 canonical rows in
 * {@code app/data/ASCII/discgrp.txt} are accessed by the migrated
 * {@code InterestCalculationProcessor} (CBACT04C migration) once per
 * account-balance bucket to look up the {@code DIS-INT-RATE} keyed by
 * the {@code (DIS-ACCT-GROUP-ID, DIS-TRAN-TYPE-CD, DIS-TRAN-CAT-CD)}
 * composite tuple.
 *
 * <h2>Special-Case Group Identifiers (CBACT04C branch logic)</h2>
 *
 * <p>Two 10-character {@code DIS-ACCT-GROUP-ID} sentinel values drive the
 * interest-calculator branch logic. Both appear as catalog rows with
 * specific semantic interpretation by the processor consumer:
 * <ul>
 *   <li>{@code "DEFAULT   "} (7 chars plus 3 trailing spaces, total 10
 *       characters) — fallback group consulted when an account's
 *       configured disclosure group is not present in the catalog. The
 *       COBOL paragraph {@code 1200-A-GET-DEFAULT-INT-RATE} re-reads the
 *       {@code DISCGRP-FILE} after moving {@code 'DEFAULT'} into
 *       {@code FD-DIS-ACCT-GROUP-ID} (line 437 of {@code CBACT04C.CBL}).</li>
 *   <li>{@code "ZEROAPR   "} (7 chars plus 3 trailing spaces, total 10
 *       characters) — zero-APR override group; every row keyed by this
 *       group ID carries a {@code DIS-INT-RATE} of {@code 0.00}, so the
 *       {@code IF DIS-INT-RATE NOT = 0} guard inside paragraph
 *       {@code 1300-COMPUTE-INTEREST} skips the interest calculation.</li>
 * </ul>
 *
 * <p>The fallback / skip business logic itself lives in
 * {@code InterestCalculationProcessor} (REFACTOR-flavor). This repository
 * only fronts the composite-key lookup mechanics — per AAP §0.10.1
 * Require Test Coverage Rule, no custom finder embeds the branch logic
 * (no {@code findByGroupIdOrDefault} convenience method); the consumer
 * is responsible for the two-step lookup sequence (account-specific
 * group first, DEFAULT group on miss).
 *
 * <h2>Primary Key Type — DiscountGroupKey</h2>
 *
 * <p>The {@link DiscountGroup} primary key is a composite value object
 * ({@link DiscountGroupKey}) rather than a single scalar because a
 * single account group references multiple interest-rate rows keyed by
 * transaction type and category (e.g. group {@code A000000001} appears
 * under type {@code 01} categories {@code 0001}, {@code 0002},
 * {@code 0003}, {@code 0004} in {@code app/data/ASCII/discgrp.txt}). The
 * COBOL {@code DIS-GROUP-KEY} group-level item captures this composite
 * identity exactly; the Java repository generic-type parameter
 * {@code <DiscountGroup, DiscountGroupKey>} mirrors that structural
 * choice so that {@link #findById(Object)} accepts a typed key rather
 * than a stringly-typed concatenation.
 *
 * <h2>Design Note — Stub Status</h2>
 *
 * <p>This interface is a <strong>minimum-viable JPA repository</strong>
 * created to satisfy the {@code DiscountGroupRepositoryIT} integration
 * test suite (AAP §0.5.1) and any downstream service that needs to
 * resolve the disclosure-group interest rate by composite key.
 * Subsequent migration agents (REFACTOR flavor) will:
 *
 * <ul>
 *   <li>Add the {@code @Entity} / {@code @EmbeddedId} / {@code @Column} /
 *       {@code @Table(name = "discount_groups")} JPA annotations on
 *       {@link DiscountGroup} and the {@code @Embeddable} annotation on
 *       {@link DiscountGroupKey} so Hibernate can map the entity onto
 *       the Flyway-created table.</li>
 *   <li>Author the Flyway scripts
 *       {@code src/main/resources/db/migration/V1__schema.sql} (CREATE
 *       TABLE {@code discount_groups} with composite primary key
 *       {@code (dis_acct_group_id CHAR(10), dis_tran_type_cd CHAR(2),
 *       dis_tran_cat_cd INTEGER)} and {@code dis_int_rate NUMERIC(6,2)})
 *       and {@code V3__seed.sql} (51 INSERT rows from
 *       {@code app/data/ASCII/discgrp.txt}).</li>
 *   <li>Optionally add a {@code @Repository} stereotype annotation if
 *       the project policy requires explicit stereotype marking — Spring
 *       Data infers the bean from the {@code JpaRepository} extension
 *       and a stereotype is not strictly necessary.</li>
 * </ul>
 *
 * <p>This interface intentionally exposes no custom query methods at
 * the stub stage. The inherited
 * {@link JpaRepository#findById(Object)},
 * {@link JpaRepository#findAll()}, {@link JpaRepository#save(Object)},
 * and {@link JpaRepository#count()} satisfy every documented business
 * workflow that consumes the disclosure-group catalog. Per AAP §0.10.2
 * Minimal Change Clause, no speculative
 * {@code findByDisAcctGroupId(String)} or
 * {@code findAllByDisIntRateGreaterThan(BigDecimal)} method is added —
 * the canonical access pattern is composite-key lookup, and downstream
 * consumers iterate the catalog via {@link JpaRepository#findAll()} when
 * a global pass is required (the 51-row catalog fits comfortably in
 * memory).
 *
 * @see DiscountGroup
 * @see DiscountGroupKey
 */
public interface DiscountGroupRepository
        extends JpaRepository<DiscountGroup, DiscountGroupKey> {
    // All required methods (findById, findAll, save, count, deleteById, ...)
    // are inherited from JpaRepository. Custom query methods are deliberately
    // omitted at the stub stage per AAP §0.10.2 Minimal Change Clause; the
    // DISCGRP catalog is small (51 rows) and downstream consumers iterate
    // the catalog via findAll() or look up individual rows via
    // findById(DiscountGroupKey) — no exotic query is required. The DEFAULT-
    // group fallback and ZEROAPR-group skip logic lives in
    // InterestCalculationProcessor (REFACTOR-flavor) per AAP §0.10.1 Require
    // Test Coverage Rule (no business logic in test code, and no business
    // logic in the repository surface either).
}
