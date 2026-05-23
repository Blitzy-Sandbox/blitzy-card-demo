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

import com.awsm2.carddemo.domain.TransactionCategory;
import com.awsm2.carddemo.domain.TransactionCategory.TransactionCategoryId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link TransactionCategory} reference
 * entity &mdash; the {@code (transaction-type-code, transaction-category-code)}
 * lookup that maps every composite-key tuple to a human-readable category
 * description. This is the small static reference table consulted by the
 * transaction-posting cascade, the transaction-report generator, and the
 * interest-calculation batch in the CardDemo Java target.
 *
 * <p><strong>Replaces VSAM cluster:</strong>
 * {@code AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS}
 * (defined in {@code app/jcl/TRANCATG.jcl}:L36&ndash;L48 with
 * {@code KEYS(6 0)}, {@code RECORDSIZE(60 60)}, {@code SHAREOPTIONS(2 3)},
 * {@code ERASE}, {@code INDEXED}, {@code CYLINDERS(1 5)} &mdash; composite
 * 6-byte primary key {@code TRAN-CAT-KEY} at relative key position 0). The
 * 6-byte key decomposes into two logical key columns whose widths sum
 * exactly to 6 bytes:</p>
 * <pre>
 *     TRAN-TYPE-CD (PIC X(02), 2 bytes)
 *   + TRAN-CAT-CD  (PIC 9(04), 4 bytes)
 *   = 6-byte VSAM composite key
 * </pre>
 * <p>The COBOL {@code SHAREOPTIONS(2 3)} attribute has no PostgreSQL
 * equivalent: PostgreSQL handles concurrent reads/writes natively via MVCC
 * (multi-version concurrency control). The relational target is the
 * {@code tran_category} table created by Flyway migration
 * {@code V009__create_transaction_category.sql} and seeded by
 * {@code V014__seed_transaction_category.sql} (per AAP &sect;0.4.1,
 * &sect;0.6.2). The COBOL VSAM {@code READ}/{@code WRITE}/{@code REWRITE}/
 * {@code DELETE} verbs are replaced by the inherited Spring Data JPA
 * methods ({@link #findById(Object)}, {@link #save(Object)},
 * {@link #deleteById(Object)}, {@link #existsById(Object)}).</p>
 *
 * <p><strong>Source copybook:</strong> {@code app/cpy/CVTRA04Y.cpy}
 * ({@code TRAN-CAT-RECORD}, RECLN 60 bytes) &mdash; the layout containing
 * the 6-byte composite {@code TRAN-CAT-KEY} (2 sub-fields:
 * {@code TRAN-TYPE-CD PIC X(02)} and {@code TRAN-CAT-CD PIC 9(04)}), a
 * 50-byte description field {@code TRAN-CAT-TYPE-DESC PIC X(50)}, and a
 * trailing 4-byte {@code FILLER PIC X(04)}. The FILLER has no relational
 * equivalent and is omitted from the JPA entity (positions 57-60 of
 * {@code app/data/ASCII/trancatg.txt} contain the literal {@code "0000"}
 * for every row, confirming padding rather than data &mdash; see
 * {@link TransactionCategory} for the field-level mapping).</p>
 *
 * <h2>Canonical reference values (seeded by V014, REC-TOTAL = 18)</h2>
 *
 * <p>18 reference rows are seeded once at schema-creation time by
 * {@code V014__seed_transaction_category.sql} from
 * {@code app/data/ASCII/trancatg.txt} (the IDCAMS
 * {@code REPRO INFILE(TRANCATG) OUTFILE(TCATVSAM)} step at
 * {@code app/jcl/TRANCATG.jcl}:L54&ndash;L62 loads this same fixture into
 * the source VSAM cluster). The 18 rows are organized by transaction-type
 * code:</p>
 * <ul>
 *   <li>{@code "01"} (Purchase) &mdash; 5 categories
 *       (Regular Sales Draft, Online Purchases, Recurring Purchase, etc.).</li>
 *   <li>{@code "02"} (Payment) &mdash; 3 categories
 *       (Cash payment, Check payment, Auto Payment).</li>
 *   <li>{@code "03"} (Credit) &mdash; 3 categories.</li>
 *   <li>{@code "04"} (Authorization) &mdash; 3 categories.</li>
 *   <li>{@code "05"} (Refund) &mdash; 1 category.</li>
 *   <li>{@code "06"} (Reversal) &mdash; 2 categories.</li>
 *   <li>{@code "07"} (Adjustment) &mdash; 1 category.</li>
 * </ul>
 *
 * <p>Description text is preserved verbatim from
 * {@code app/data/ASCII/trancatg.txt} per the Minimal Change Clause (AAP
 * &sect;0.7.3). Capitalization (e.g., lowercase {@code "payment"} in
 * {@code "Cash payment"}) is intentional and must not be normalized
 * because regulatory output formats &mdash; statement generation,
 * {@code TransactionReportService} output &mdash; depend on the exact
 * text.</p>
 *
 * <h2>Composite Key</h2>
 *
 * <p>The {@code @EmbeddedId} on {@link TransactionCategory} is the static
 * nested class {@link TransactionCategoryId}. Callers construct a key
 * instance and pass it to {@link #findById(Object)}:</p>
 * <pre>
 *     TransactionCategoryId key =
 *         new TransactionCategoryId(tranTypeCd, tranCatCd);
 *     Optional&lt;TransactionCategory&gt; category =
 *         transactionCategoryRepository.findById(key);
 * </pre>
 *
 * <p>Sub-field encoding mirrors the COBOL source: {@code tranTypeCd} is a
 * 2-character {@link String} (CHAR(2); leading zeros significant &mdash;
 * pass {@code "01"}, NOT the integer {@code 1}), and {@code tranCatCd} is
 * a 4-digit {@link Integer} (NUMERIC(4); range 1..9999; the COBOL
 * {@code PIC 9(04)} value {@code "0001"} becomes the Java {@code Integer}
 * {@code 1} in PostgreSQL). Application-layer formatting in
 * {@code TransactionReportService} re-pads to 4 digits when emitting
 * regulatory-format output, preserving byte-for-byte parity with the
 * COBOL source per AAP &sect;0.7.2.</p>
 *
 * <h2>Source consumers (REFERENCE &mdash; never modified)</h2>
 *
 * <p>The following COBOL programs read this VSAM cluster on the mainframe
 * and are migrated to consume this repository in the Java target:</p>
 * <ul>
 *   <li>{@code app/cbl/CBTRN02C.cbl} &mdash; transaction-posting batch.
 *       Validates the incoming {@code (DALYTRAN-TRAN-TYPE-CD,
 *       DALYTRAN-CAT-CD)} pair against this lookup during the 4-stage
 *       validation cascade (XREF / Account / Credit limit / Card
 *       expiration &mdash; preserving reject codes 100&ndash;109).
 *       Replaced in the Java target by {@code TransactionPostingService}
 *       (per AAP &sect;0.4.1).</li>
 *   <li>{@code app/cbl/CBTRN03C.cbl} &mdash; transaction-report
 *       generator. The COBOL {@code READ TRANCATG-FILE} call that joins
 *       the (type, category) pair to its human-readable description for
 *       the printed report is replaced in the Java target by
 *       {@code TransactionReportService} calling
 *       {@link #findById(Object)} or {@link #findAll()}.</li>
 *   <li>{@code app/cbl/COTRN02C.cbl} &mdash; online transaction-add
 *       (CICS pseudo-conversational). User-supplied {@code (TRAN-TYPE-CD,
 *       TRAN-CAT-CD)} values are validated against this lookup before
 *       INSERT into the {@code transactions} journal. Replaced in the
 *       Java target by {@code TransactionAddService} calling
 *       {@link #existsById(Object)} or {@link #findById(Object)} via
 *       {@code ValidationLookupService}.</li>
 *   <li>{@code app/cbl/CBACT04C.cbl} &mdash; interest-calculation batch.
 *       The composite-key lookup against {@code disclosure_group} uses
 *       {@code (DIS-ACCT-GROUP-ID, DIS-TRAN-TYPE-CD, DIS-TRAN-CAT-CD)}
 *       as its key; the (type, category) component is validated against
 *       this lookup. Replaced in the Java target by
 *       {@code InterestCalculationService}.</li>
 * </ul>
 *
 * <h2>JCL sources (REFERENCE &mdash; never modified)</h2>
 *
 * <ul>
 *   <li>{@code app/jcl/TRANCATG.jcl} &mdash; IDCAMS {@code DEFINE CLUSTER}
 *       (STEP10) for the source VSAM KSDS, plus the {@code REPRO} load
 *       (STEP15) from {@code AWS.M2.CARDDEMO.TRANCATG.PS}. Replaced by
 *       Flyway migration {@code V009__create_transaction_category.sql}
 *       (DDL) and {@code V014__seed_transaction_category.sql} (DML
 *       seed).</li>
 *   <li>{@code app/catlg/LISTCAT.txt} &mdash; documents the VSAM cluster
 *       inventory (cluster name {@code AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS},
 *       {@code KEYLEN=6}, {@code RKP=0}, {@code AVGLRECL=60},
 *       {@code MAXLRECL=60}, {@code REC-TOTAL=18}). No runtime
 *       equivalent is needed in the Java target.</li>
 * </ul>
 *
 * <h2>Logical relationships (application-layer references)</h2>
 *
 * <p>The composite key tuple {@code (tran_type_cd, tran_cat_cd)} is
 * referenced as a dimensional lookup by several downstream tables. The
 * V009 DDL declares a database-level FK from
 * {@code tran_category.tran_type_cd} to {@code tran_type.tran_type}
 * (FK constraint {@code fk_tran_category_tran_type} with
 * {@code ON DELETE NO ACTION}) for defensive referential integrity. The
 * JPA mapping intentionally does NOT mirror this as a {@code @ManyToOne}
 * association per the Minimal Change Clause (AAP &sect;0.7.3) &mdash; the
 * COBOL source treats this as a scalar key, and the Java target preserves
 * that shape.</p>
 *
 * <p>The following downstream tables consult this lookup via their own
 * {@code (tran_type_cd, tran_cat_cd)} pair; FK enforcement at the
 * database level varies (see each migration for details) and the
 * application layer validates existence consistently with the COBOL
 * business rules of {@code CBTRN02C} / {@code COTRN02C}:</p>
 * <ul>
 *   <li>{@code transactions} (V005; COBOL {@code TRAN-TYPE-CD} +
 *       {@code TRAN-CAT-CD} in {@code app/cpy/CVTRA05Y.cpy}).</li>
 *   <li>{@code tran_cat_bal} (V006; COBOL {@code TRAN-CAT-BAL-TYPE-CD}
 *       + {@code TRAN-CAT-BAL-CAT-CD} in {@code app/cpy/CVTRA01Y.cpy}).</li>
 *   <li>{@code disclosure_group} (V007; COBOL {@code DIS-TRAN-TYPE-CD}
 *       + {@code DIS-TRAN-CAT-CD} in {@code app/cpy/CVTRA02Y.cpy}).</li>
 *   <li>{@code daily_transactions} (V011; COBOL {@code DALYTRAN-TYPE-CD}
 *       + {@code DALYTRAN-CAT-CD} in {@code app/cpy/CVTRA06Y.cpy}).</li>
 * </ul>
 *
 * <h2>Primary Consumers (Java)</h2>
 *
 * <ul>
 *   <li>{@code TransactionPostingService} &mdash; validates incoming
 *       {@code (TRAN-TYPE-CD, TRAN-CAT-CD)} composite values during the
 *       4-stage cascade in the transaction-posting batch job
 *       (replacement for {@code app/cbl/CBTRN02C.cbl}). Uses
 *       {@link #existsById(Object)} or {@link #findById(Object)}.</li>
 *   <li>{@code TransactionReportService} &mdash; joins category
 *       descriptions for the transaction-report output (replacement for
 *       {@code app/cbl/CBTRN03C.cbl}). Uses {@link #findAll()} (loaded
 *       once and cached) or {@link #findById(Object)} per record.</li>
 *   <li>{@code TransactionAddService} &mdash; validates user-supplied
 *       (type, category) pairs on incoming online transactions
 *       (replacement for {@code app/cbl/COTRN02C.cbl}). Uses
 *       {@link #existsById(Object)}.</li>
 *   <li>{@code ValidationLookupService} &mdash; centralized validation
 *       helper for {@code (transaction-type, category)} composites
 *       (per AAP &sect;0.3.3, &sect;0.4.1 Validation Services).</li>
 *   <li>{@code InterestCalculationService} &mdash; resolves the
 *       {@code (TRAN-TYPE-CD, TRAN-CAT-CD)} component of
 *       {@code disclosure_group} composite-key lookups (replacement for
 *       {@code app/cbl/CBACT04C.cbl}).</li>
 * </ul>
 *
 * <h2>Design notes</h2>
 *
 * <ul>
 *   <li><strong>No custom derived queries / {@code @Query} annotations.</strong>
 *       The three access patterns required by all consumers &mdash;
 *       (a) composite-primary-key lookup by {@link TransactionCategoryId},
 *       (b) full-table dump for warm-cache preloading, and
 *       (c) existence check &mdash; are satisfied entirely by the
 *       inherited {@link JpaRepository#findById(Object)},
 *       {@link JpaRepository#findAll()}, and
 *       {@link JpaRepository#existsById(Object)} methods. Adding custom
 *       methods would violate the Minimal Change Clause (AAP
 *       &sect;0.7.3).</li>
 *   <li><strong>No {@code findByIdTranTypeCdAndIdTranCatCd(...)} helper.</strong>
 *       Derived queries on the composite-key sub-fields are intentionally
 *       NOT declared &mdash; callers construct a
 *       {@link TransactionCategoryId} instance
 *       ({@code new TransactionCategoryId(typeCd, catCd)}) and pass it
 *       to the inherited {@link #findById(Object)}. This single
 *       canonical access pattern keeps the persistence-layer API minimal
 *       and aligned with the COBOL VSAM {@code READ DATASET('TRANCATG')
 *       RIDFLD(6-byte-key)} semantics.</li>
 *   <li><strong>No business logic.</strong> Per AAP &sect;0.7.1 /
 *       &sect;0.7.3, repositories are pure persistence-layer
 *       components; all validation, branching, and error translation
 *       lives in the service layer ({@code TransactionPostingService},
 *       {@code TransactionAddService}, {@code ValidationLookupService},
 *       {@code TransactionReportService},
 *       {@code InterestCalculationService}).</li>
 *   <li><strong>No optimistic-locking</strong> {@code @Version}.
 *       {@link TransactionCategory} is static reference data seeded once
 *       by {@code V014__seed_transaction_category.sql} and not updated
 *       at runtime; there is no read-modify-write contention to guard
 *       against. The {@code save(...)} and {@code saveAll(...)} methods
 *       are inherited for completeness (and exercised by the
 *       {@code @DataJpaTest} integration suite) but are not invoked by
 *       any production code path.</li>
 *   <li><strong>Composite {@code @EmbeddedId} second type parameter.</strong>
 *       The {@link JpaRepository} second type parameter is
 *       {@link TransactionCategoryId}, the static nested
 *       {@link jakarta.persistence.Embeddable @Embeddable} class
 *       declared inside {@link TransactionCategory}. Callers MUST
 *       construct a fully-populated {@link TransactionCategoryId}
 *       instance ({@code new TransactionCategoryId(typeCd, catCd)})
 *       before calling {@link #findById(Object)}; passing either of the
 *       two sub-fields individually is a compile-time error.</li>
 *   <li><strong>Component scanning.</strong> The
 *       {@link Repository @Repository} stereotype enables Spring's
 *       {@code PersistenceExceptionTranslationPostProcessor} to translate
 *       JPA persistence exceptions ({@code JpaSystemException},
 *       {@code OptimisticLockException}) into Spring's
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
 * &rarr; {@code RecordNotFoundException} (HTTP 404; VSAM file status
 * {@code 23}/NOTFND equivalent), {@code DataIntegrityViolationException}
 * &rarr; {@code DuplicateRecordException} (HTTP 409; VSAM file status
 * {@code 22}/DUPKEY equivalent).</p>
 *
 * <p>For services that perform {@code (type, category)} existence
 * validation (e.g., {@code TransactionAddService},
 * {@code TransactionPostingService}), an
 * {@link java.util.Optional#isEmpty() empty Optional} from
 * {@link #findById(Object)} (or {@code false} from
 * {@link #existsById(Object)}) is the expected, non-error signal that
 * the service should reject the incoming transaction with the
 * appropriate validation exception &mdash; mirroring the COBOL
 * file-status {@code 23} (NOTFND) reject branch in {@code CBTRN02C.cbl}
 * (reject codes 100&ndash;109).</p>
 *
 * <h2>AAP traceability</h2>
 *
 * <ul>
 *   <li>AAP &sect;0.3.1 &mdash; target structure ({@code repository/}
 *       package).</li>
 *   <li>AAP &sect;0.4.1 &mdash; repository file inventory
 *       ({@code TransactionCategoryRepository}, derived from
 *       {@code app/cpy/CVTRA04Y.cpy}).</li>
 *   <li>AAP &sect;0.6.2 &mdash; VSAM-to-RDS migration strategy
 *       (KSDS &rarr; relational table; composite key &rarr; JPA
 *       {@code @EmbeddedId}).</li>
 *   <li>AAP &sect;0.7.1 &mdash; refactor discipline (layered Controller
 *       &rarr; Service &rarr; Repository architecture; repositories
 *       contain no business logic).</li>
 *   <li>AAP &sect;0.7.3 &mdash; inline traceability comments (every
 *       repository documents the source COBOL artifacts it replaces).</li>
 * </ul>
 *
 * <p>All required CRUD operations &mdash;
 * {@link #findById(Object) findById(TransactionCategoryId)},
 * {@link #findAll()},
 * {@link #findAll(org.springframework.data.domain.Sort)},
 * {@link #findAll(org.springframework.data.domain.Pageable)},
 * {@link #save(Object) save(TransactionCategory)},
 * {@link #saveAll(Iterable) saveAll(Iterable)},
 * {@link #existsById(Object) existsById(TransactionCategoryId)},
 * {@link #deleteById(Object) deleteById(TransactionCategoryId)},
 * {@link #delete(Object) delete(TransactionCategory)}, and
 * {@link #count()} &mdash; are inherited from
 * {@link org.springframework.data.jpa.repository.JpaRepository}; no
 * custom derived queries or {@code @Query} annotations are required or
 * permitted on this interface.</p>
 *
 * @see com.awsm2.carddemo.domain.TransactionCategory
 *      the JPA entity mapped to the {@code tran_category} lookup table
 * @see TransactionCategoryId
 *      the {@code @EmbeddedId} composite-primary-key class
 *      ({@code (tranTypeCd, tranCatCd)})
 */
// Replaces: AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS
// (app/jcl/TRANCATG.jcl IDCAMS DEFINE CLUSTER KEYS(6 0) RECORDSIZE(60 60),
//  layout app/cpy/CVTRA04Y.cpy TRAN-CAT-RECORD, primary consumers
//  app/cbl/CBTRN02C.cbl + app/cbl/CBTRN03C.cbl + app/cbl/COTRN02C.cbl).
// COBOL READ DATASET('TRANCATG') RIDFLD(6-byte composite TYPE-CD + CAT-CD)
// is replaced by the inherited findById(new TransactionCategoryId(...)).
@Repository
public interface TransactionCategoryRepository
        extends JpaRepository<TransactionCategory, TransactionCategoryId> {
}
