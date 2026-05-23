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
package com.awsm2.carddemo.repository;

import com.awsm2.carddemo.domain.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link TransactionType} reference entity
 * &mdash; the 2-character transaction-type lookup that classifies every
 * financial transaction posted by the CardDemo Java target.
 *
 * <p><strong>Replaces VSAM cluster:</strong>
 * {@code AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS}
 * (defined in {@code app/jcl/TRANTYPE.jcl}:L36&ndash;L49 with
 * {@code KEYS(2 0)}, {@code RECORDSIZE(60 60)}, {@code SHAREOPTIONS(1 4)},
 * {@code INDEXED} &mdash; 2-byte primary key {@code TRAN-TYPE} at relative
 * key position 0). The COBOL {@code SHAREOPTIONS(1 4)} attribute has no
 * PostgreSQL equivalent: PostgreSQL handles concurrent reads/writes natively
 * via MVCC (multi-version concurrency control). The relational target is the
 * {@code tran_type} table created by Flyway migration
 * {@code V008__create_transaction_type.sql} and seeded by
 * {@code V013__seed_transaction_type.sql} (per AAP &sect;0.4.1, &sect;0.6.2).
 * The COBOL VSAM {@code READ}/{@code WRITE}/{@code REWRITE}/{@code DELETE}
 * verbs are replaced by the inherited Spring Data JPA methods
 * ({@link #findById(Object)}, {@link #save(Object)},
 * {@link #deleteById(Object)}, {@link #existsById(Object)}).</p>
 *
 * <p><strong>Source copybook:</strong> {@code app/cpy/CVTRA03Y.cpy} &mdash;
 * the {@code TRAN-TYPE-RECORD} layout (RECLN 60 bytes): 2-byte primary key
 * {@code TRAN-TYPE} ({@code PIC X(02)}), 50-byte description
 * {@code TRAN-TYPE-DESC} ({@code PIC X(50)}), and an 8-byte trailing
 * {@code FILLER PIC X(08)} that is omitted in the relational model (per
 * AAP &sect;0.4.1: relational layouts have no positional padding; the
 * 8-byte FILLER carries the literal {@code "00000000"} in
 * {@code app/data/ASCII/trantype.txt}, confirming it is padding rather than
 * data).</p>
 *
 * <h2>Canonical reference values (seeded by V013, RECREC-TOTAL = 7)</h2>
 *
 * <p>The table is loaded once at schema-creation time and is treated as
 * immutable static reference data thereafter. The seven canonical rows are
 * sourced verbatim from {@code app/data/ASCII/trantype.txt} (the IDCAMS
 * {@code REPRO INFILE(TRANTYPE) OUTFILE(TTYPVSAM)} step at
 * {@code app/jcl/TRANTYPE.jcl}:L54&ndash;L61 loads this same fixture into
 * the source VSAM cluster):</p>
 * <ul>
 *   <li>{@code "01"} &mdash; Purchase</li>
 *   <li>{@code "02"} &mdash; Payment</li>
 *   <li>{@code "03"} &mdash; Credit</li>
 *   <li>{@code "04"} &mdash; Authorization</li>
 *   <li>{@code "05"} &mdash; Refund</li>
 *   <li>{@code "06"} &mdash; Reversal</li>
 *   <li>{@code "07"} &mdash; Adjustment</li>
 * </ul>
 *
 * <h2>Source consumers (REFERENCE &mdash; never modified)</h2>
 *
 * <p>The following COBOL programs read this VSAM cluster on the mainframe
 * and are migrated to consume this repository in the Java target:</p>
 * <ul>
 *   <li>{@code app/cbl/CBTRN02C.cbl} &mdash; transaction-posting cascade.
 *       Each incoming {@code DALYTRAN-TRAN-TYPE-CD} is validated against the
 *       {@code TRANTYPE} cluster as part of the 4-stage validation cascade
 *       (XREF / Account / Credit limit / Card expiration &mdash; preserving
 *       reject codes 100&ndash;109). Replaced in the Java target by
 *       {@code TransactionPostingService} (per AAP &sect;0.4.1).</li>
 *   <li>{@code app/cbl/CBTRN03C.cbl} &mdash; transaction-report generator.
 *       The COBOL {@code READ TRANTYPE-FILE} call that joins the
 *       transaction-type code to its human-readable description for the
 *       printed report is replaced in the Java target by
 *       {@code TransactionReportService} calling
 *       {@link #findById(Object)}.</li>
 *   <li>{@code app/cbl/COTRN02C.cbl} &mdash; online transaction-add
 *       (CICS pseudo-conversational). User-supplied {@code TRAN-TYPE-CD}
 *       values are validated against this lookup before INSERT into the
 *       {@code transactions} journal. Replaced in the Java target by
 *       {@code TransactionAddService} calling {@link #existsById(Object)}
 *       or {@link #findById(Object)} via
 *       {@code ValidationLookupService}.</li>
 *   <li>{@code app/cbl/CBACT04C.cbl} &mdash; interest calculation. The
 *       composite-key lookup against {@code disclosure_group} uses
 *       {@code TRAN-TYPE-CD} as part of its key; this repository validates
 *       the type-code component before the lookup proceeds. Replaced in
 *       the Java target by {@code InterestCalculationService}.</li>
 * </ul>
 *
 * <h2>JCL sources (REFERENCE &mdash; never modified)</h2>
 *
 * <ul>
 *   <li>{@code app/jcl/TRANTYPE.jcl} &mdash; IDCAMS {@code DEFINE CLUSTER}
 *       (STEP10) for the source VSAM KSDS, plus the {@code REPRO} load
 *       (STEP15) from {@code AWS.M2.CARDDEMO.TRANTYPE.PS}. Replaced by
 *       Flyway migration {@code V008__create_transaction_type.sql}
 *       (DDL) and {@code V013__seed_transaction_type.sql} (DML seed).</li>
 *   <li>{@code app/catlg/LISTCAT.txt} &mdash; documents the VSAM cluster
 *       inventory (cluster name, key length, record size, record count).
 *       No runtime equivalent is needed in the Java target.</li>
 * </ul>
 *
 * <h2>Logical child tables (application-layer FK references)</h2>
 *
 * <p>The following child tables reference {@code tran_type.tran_type} via
 * scalar FK columns; database-level FK constraints are intentionally NOT
 * declared (per AAP &sect;0.4.1 / V008 commentary) because Flyway
 * lexicographic migration ordering interleaves consumer migrations
 * (V005 {@code transactions}, V006 {@code tran_cat_bal},
 * V009 {@code tran_category}, V011 {@code daily_transactions}) around
 * this V008 table. Application-layer validation enforces existence
 * consistently with the COBOL business rules of {@code CBTRN02C} /
 * {@code COTRN02C}:</p>
 * <ul>
 *   <li>{@code transactions.tran_type_cd} (V005; COBOL
 *       {@code TRAN-TYPE-CD} in {@code app/cpy/CVTRA05Y.cpy}).</li>
 *   <li>{@code tran_category} composite PK
 *       {@code (tran_type_cd, tran_cat_cd)} (V009; COBOL
 *       {@code TRAN-TYPE-CD} in {@code app/cpy/CVTRA04Y.cpy}).</li>
 *   <li>{@code tran_cat_bal.trancat_type_cd} (V006; COBOL
 *       {@code TRAN-CAT-BAL-TYPE-CD} in {@code app/cpy/CVTRA01Y.cpy}).</li>
 *   <li>{@code disclosure_group.dis_tran_type_cd} (V007; COBOL
 *       {@code DIS-TRAN-TYPE-CD} in {@code app/cpy/CVTRA02Y.cpy}).</li>
 *   <li>{@code daily_transactions.dalytran_type_cd} (V011; COBOL
 *       {@code DALYTRAN-TYPE-CD} in {@code app/cpy/CVTRA06Y.cpy}).</li>
 * </ul>
 *
 * <h2>Primary Consumers (Java)</h2>
 *
 * <ul>
 *   <li>{@code TransactionPostingService} &mdash; validates incoming
 *       {@code TRAN-TYPE-CD} values during the 4-stage cascade in the
 *       transaction-posting batch job (replacement for
 *       {@code app/cbl/CBTRN02C.cbl}). Uses
 *       {@link #existsById(Object)} or {@link #findById(Object)}.</li>
 *   <li>{@code TransactionReportService} &mdash; joins type-code
 *       descriptions for the transaction-report output (replacement for
 *       {@code app/cbl/CBTRN03C.cbl}). Uses {@link #findAll()} (loaded
 *       once and cached) or {@link #findById(Object)} per record.</li>
 *   <li>{@code TransactionAddService} &mdash; validates user-supplied
 *       type codes on incoming online transactions (replacement for
 *       {@code app/cbl/COTRN02C.cbl}). Uses {@link #existsById(Object)}.</li>
 *   <li>{@code ValidationLookupService} &mdash; centralized validation
 *       helper for transaction-type codes (per AAP &sect;0.3.3, &sect;0.4.1
 *       Validation Services).</li>
 *   <li>{@code InterestCalculationService} &mdash; resolves the
 *       {@code TRAN-TYPE-CD} component of {@code disclosure_group}
 *       composite-key lookups (replacement for {@code app/cbl/CBACT04C.cbl}).</li>
 * </ul>
 *
 * <h2>Design notes</h2>
 *
 * <ul>
 *   <li><strong>No custom derived queries / {@code @Query} annotations.</strong>
 *       The two access patterns required by all consumers &mdash; (a)
 *       primary-key lookup by 2-character {@code tran_type} code and (b)
 *       full-table dump &mdash; are satisfied entirely by the inherited
 *       {@link JpaRepository#findById(Object)} and
 *       {@link JpaRepository#findAll()} methods. Adding custom methods
 *       would violate the Minimal Change Clause (AAP &sect;0.7.3).</li>
 *   <li><strong>No {@code @Version} optimistic locking.</strong> This
 *       lookup is static reference data seeded once by V013 and not
 *       updated at runtime; there is no read-modify-write contention to
 *       guard against. The entity {@link TransactionType} accordingly
 *       does not declare a {@code @Version} column.</li>
 *   <li><strong>No business logic.</strong> Per AAP &sect;0.7.1 / &sect;0.7.3,
 *       repositories are pure persistence-layer components; all
 *       validation, branching, and error translation lives in the service
 *       layer ({@code TransactionPostingService},
 *       {@code TransactionAddService}, {@code ValidationLookupService}).</li>
 *   <li><strong>String primary key.</strong> The
 *       {@link JpaRepository} second type parameter is {@code String},
 *       matching the entity's {@code @Id String tranType} field
 *       (CHAR(2) at the DB layer). Service code passes the 2-character
 *       code directly &mdash; e.g.,
 *       {@code repository.findById("01")} for Purchase &mdash; with
 *       leading zeros preserved (the integer {@code 1} would be a
 *       semantic error).</li>
 *   <li><strong>Component scanning.</strong> The
 *       {@link Repository @Repository} stereotype enables Spring's
 *       {@code PersistenceExceptionTranslationPostProcessor} to translate
 *       JPA persistence exceptions into Spring's
 *       {@code DataAccessException} hierarchy, which the CardDemo
 *       {@code GlobalExceptionHandler} (per AAP &sect;0.7.1) further
 *       translates into the CardDemo exception types
 *       ({@code RecordNotFoundException} &rarr; HTTP 404, etc.).
 *       Although Spring Data JPA registers repository proxies even
 *       without an explicit {@code @Repository} annotation, declaring
 *       it on the interface is the canonical, self-documenting form
 *       across the CardDemo repository inventory.</li>
 * </ul>
 *
 * <h2>Exception translation</h2>
 *
 * <p>Repository operations propagate Spring's
 * {@code org.springframework.dao.DataAccessException} hierarchy on
 * persistence failures. Service-layer code consuming this repository
 * MUST translate {@code DataAccessException} subclasses into the
 * CardDemo exception hierarchy before propagating to the controller
 * boundary (per AAP &sect;0.7.1): {@code EmptyResultDataAccessException}
 * &rarr; {@code RecordNotFoundException} (HTTP 404),
 * {@code DataIntegrityViolationException} &rarr;
 * {@code DuplicateRecordException} (HTTP 409),
 * {@code OptimisticLockingFailureException} &rarr;
 * {@code ConcurrentModificationException} (HTTP 409 &mdash; not
 * applicable to this read-only lookup but listed for completeness).</p>
 *
 * <h2>AAP traceability</h2>
 *
 * <ul>
 *   <li>AAP &sect;0.3.1 &mdash; target structure ({@code repository/} package).</li>
 *   <li>AAP &sect;0.4.1 &mdash; repository file inventory
 *       ({@code TransactionTypeRepository}, derived from
 *       {@code app/cpy/CVTRA03Y.cpy}).</li>
 *   <li>AAP &sect;0.6.2 &mdash; VSAM-to-RDS migration strategy
 *       (KSDS &rarr; relational table; primary key &rarr; JPA {@code @Id}).</li>
 *   <li>AAP &sect;0.7.1 &mdash; refactor discipline (layered architecture;
 *       repositories contain no business logic).</li>
 *   <li>AAP &sect;0.7.3 &mdash; inline traceability comments (every
 *       repository documents the source COBOL artifacts it replaces).</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.domain.TransactionType
 *      the JPA entity mapped to the {@code tran_type} lookup table
 */
// Replaces: AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS
// (app/jcl/TRANTYPE.jcl IDCAMS DEFINE CLUSTER, layout app/cpy/CVTRA03Y.cpy)
@Repository
public interface TransactionTypeRepository extends JpaRepository<TransactionType, String> {
}
