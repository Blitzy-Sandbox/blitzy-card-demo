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

import com.awsm2.carddemo.domain.TransactionCategoryBalance;
import com.awsm2.carddemo.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link TransactionCategoryBalance} entity
 * &mdash; the per-{@code (account, transaction-type, transaction-category)}
 * running-balance bucket table that is the central data structure for the
 * end-of-day interest-calculation batch job and the daily transaction-posting
 * balance-update flow in the CardDemo Java target.
 *
 * <p><strong>Replaces VSAM cluster:</strong>
 * {@code AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS}
 * (defined in {@code app/jcl/TCATBALF.jcl}:L36&ndash;L49 with
 * {@code KEYS(17 0)}, {@code RECORDSIZE(50 50)}, {@code SHAREOPTIONS(2 3)},
 * {@code ERASE}, {@code INDEXED} &mdash; composite 17-byte primary key
 * {@code TRAN-CAT-KEY} at relative key position 0). The 17-byte key
 * decomposes into three logical key columns whose widths sum exactly to
 * 17 bytes:</p>
 * <pre>
 *     TRANCAT-ACCT-ID  (PIC 9(11), 11 bytes)
 *   + TRANCAT-TYPE-CD  (PIC X(02),  2 bytes)
 *   + TRANCAT-CD       (PIC 9(04),  4 bytes)
 *   = 17-byte VSAM composite key
 * </pre>
 * <p>The COBOL {@code SHAREOPTIONS(2 3)} attribute has no PostgreSQL
 * equivalent: PostgreSQL handles concurrent reads/writes natively via MVCC
 * (multi-version concurrency control). The relational target is the
 * {@code tran_cat_bal} table created by Flyway migration
 * {@code V006__create_tcatbal.sql} (per AAP &sect;0.4.1, &sect;0.6.2). The
 * COBOL VSAM {@code READ}/{@code WRITE}/{@code REWRITE}/{@code DELETE}
 * verbs are replaced by the inherited Spring Data JPA methods
 * ({@link #findById(Object)}, {@link #save(Object)},
 * {@link #deleteById(Object)}, {@link #existsById(Object)}).</p>
 *
 * <p><strong>Source copybook:</strong> {@code app/cpy/CVTRA01Y.cpy}
 * ({@code TRAN-CAT-BAL-RECORD}, RECLN 50 bytes) &mdash; the layout
 * containing the 17-byte composite {@code TRAN-CAT-KEY} (3 sub-fields), a
 * {@code TRAN-CAT-BAL PIC S9(09)V99} running-balance field, and a trailing
 * 22-byte {@code FILLER PIC X(22)}. The FILLER has no relational equivalent
 * and is omitted from the JPA entity (see {@link TransactionCategoryBalance}
 * for the field-level mapping).</p>
 *
 * <h2>Purpose &mdash; central data structure for two batch jobs</h2>
 *
 * <p>This table holds the per-{@code (account, type, category)} running
 * balance for every active credit-card account. Two distinct COBOL batch
 * jobs read and write this table; both are migrated to Java services that
 * consume this repository:</p>
 * <ul>
 *   <li><strong>{@code app/cbl/CBACT04C.cbl}</strong> &mdash; end-of-day
 *       interest-calculation batch. Sequentially reads every row in the
 *       VSAM cluster (paragraph {@code 1000-TCATBALF-GET-NEXT} at
 *       {@code app/cbl/CBACT04C.cbl}:L325&ndash;L340 reads until
 *       {@code END-OF-FILE}), looks up the matching APR rate in
 *       {@code DISCGRP} (via {@code DisclosureGroupRepository} with the
 *       {@code DEFAULT} fallback), computes monthly interest using the
 *       formula
 *       {@code (TRAN-CAT-BAL &times; DIS-INT-RATE) / 1200}, writes the
 *       interest amount as a new {@code Transaction} row, and increments
 *       the running balance here.</li>
 *   <li><strong>{@code app/cbl/CBTRN02C.cbl}</strong> &mdash; daily
 *       transaction-posting batch. After the 4-stage validation cascade
 *       (XREF / account / credit limit / card expiration) succeeds for a
 *       posted transaction, paragraph {@code 2700-UPDATE-TCATBAL} at
 *       {@code app/cbl/CBTRN02C.cbl}:L467&ndash;L499 performs a
 *       {@code READ TCATBAL-FILE} for the matching
 *       {@code (account, type, category)} composite key, accumulates the
 *       posted {@code TRAN-AMT} into the running balance, then either
 *       {@code REWRITE}s an existing row or {@code WRITE}s a new row at
 *       zero starting balance (when {@code TCATBALF-STATUS = '23'}
 *       NOTFND on the read). The Java target maps both branches to the
 *       single inherited {@link #save(Object)} verb, which JPA dispatches
 *       to {@code INSERT} or {@code UPDATE} based on entity managed
 *       state.</li>
 * </ul>
 *
 * <h2>Composite Key</h2>
 *
 * <p>The {@code @EmbeddedId} on {@link TransactionCategoryBalance} is the
 * static nested class {@link TransactionCategoryBalanceId}. Callers
 * construct a key instance and pass it to {@link #findById(Object)}:</p>
 * <pre>
 *     TransactionCategoryBalanceId key =
 *         new TransactionCategoryBalanceId(acctId, tranTypeCd, tranCatCd);
 *     Optional&lt;TransactionCategoryBalance&gt; bucket =
 *         transactionCategoryBalanceRepository.findById(key);
 * </pre>
 *
 * <p>The 3-column declaration order matches the COBOL byte order
 * ({@code acct_id} leftmost, {@code type_cd} middle, {@code cd}
 * rightmost), preserving the implicit lexicographic ordering of the
 * VSAM KSDS and ensuring that the underlying PostgreSQL B-tree composite
 * primary-key index supports:</p>
 * <ol>
 *   <li>Random full-key lookup ({@code findById}) for per-row
 *       read-modify-write during transaction posting.</li>
 *   <li>Implicit leading-prefix scan by {@code (account)} alone for the
 *       {@code InterestCalculationService} end-of-cycle iteration that
 *       processes every {@code (type, category)} bucket of a single
 *       account in one logical unit of work (mirroring the COBOL pattern
 *       at {@code app/cbl/CBACT04C.cbl}:L194&ndash;L201, which detects
 *       account-boundary transitions via
 *       {@code IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM}).</li>
 *   <li>Implicit leading-prefix scan by {@code (account, type)} for any
 *       future per-account-per-type aggregation use case.</li>
 * </ol>
 *
 * <h2>Batch iteration &mdash; CBACT04C sequential VSAM browse equivalent</h2>
 *
 * <p>{@code app/cbl/CBACT04C.cbl} reads every balance row sequentially to
 * compute interest (paragraph {@code 1000-TCATBALF-GET-NEXT} reads forward
 * one record per call until file status {@code 10} END-OF-FILE). The Java
 * target replaces this VSAM sequential browse with one of the three
 * inherited iteration methods, selected by the consuming service based on
 * row-count:</p>
 * <ul>
 *   <li>{@link JpaRepository#findAll() findAll()} &mdash; loads every row
 *       into a single {@link java.util.List} for small reference data or
 *       warm-cache preloading at job startup. Suitable when the row count
 *       is bounded and fits comfortably in JVM heap.</li>
 *   <li>{@link JpaRepository#findAll(org.springframework.data.domain.Sort)
 *       findAll(Sort)} &mdash; loads every row in a specified order. The
 *       canonical order matching the COBOL VSAM sequential browse is
 *       {@code Sort.by("id.trancatAcctId", "id.trancatTypeCd", "id.trancatCd")},
 *       which produces the same lexicographic ordering as the underlying
 *       17-byte composite key.</li>
 *   <li>{@link JpaRepository#findAll(org.springframework.data.domain.Pageable)
 *       findAll(Pageable)} &mdash; loads rows in pages of a configurable
 *       chunk size, consumed by a Spring Batch
 *       {@code RepositoryItemReader} in {@code InterestCalculationJob}.
 *       This is the production-grade choice for large balance tables
 *       where streaming every row into JVM heap is infeasible; matches
 *       the COBOL {@code READ TCATBAL-FILE} one-record-at-a-time
 *       semantics most closely while allowing JDBC batch fetch tuning at
 *       the Spring Data layer.</li>
 * </ul>
 *
 * <h2>Source consumers (REFERENCE &mdash; never modified)</h2>
 *
 * <p>The following COBOL programs read or write this VSAM cluster on the
 * mainframe and are migrated to consume this repository in the Java
 * target:</p>
 * <ul>
 *   <li>{@code app/cbl/CBACT04C.cbl} &mdash; end-of-day interest
 *       calculation batch (sequential read of every row plus REWRITE of
 *       the updated running balance).</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl} &mdash; daily transaction posting
 *       batch (random read by composite key plus REWRITE or WRITE
 *       depending on existence; paragraph
 *       {@code 2700-UPDATE-TCATBAL}).</li>
 * </ul>
 *
 * <h2>JCL sources (REFERENCE &mdash; never modified)</h2>
 *
 * <ul>
 *   <li>{@code app/jcl/TCATBALF.jcl} &mdash; IDCAMS {@code DELETE CLUSTER}
 *       (STEP05), {@code DEFINE CLUSTER} (STEP10) for the source VSAM
 *       KSDS with {@code KEYS(17 0)} and {@code RECORDSIZE(50 50)}, and
 *       the {@code REPRO INFILE(TCATBAL) OUTFILE(TCATBALV)} load (STEP15)
 *       from {@code AWS.M2.CARDDEMO.TCATBALF.PS}. Replaced by Flyway
 *       migration {@code V006__create_tcatbal.sql} (DDL); seed data is
 *       loaded operationally via AWS Glue bulk-load jobs reading from
 *       S3-staged fixtures.</li>
 *   <li>{@code app/catlg/LISTCAT.txt} &mdash; documents the VSAM cluster
 *       inventory (cluster name
 *       {@code AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS}, {@code KEYLEN=17},
 *       {@code RKP=0}, {@code AVGLRECL=50}, {@code MAXLRECL=50}). No
 *       runtime equivalent is needed in the Java target.</li>
 * </ul>
 *
 * <h2>Primary Consumers (Java)</h2>
 *
 * <ul>
 *   <li>{@code InterestCalculationService} (Java target for COBOL
 *       {@code CBACT04C}) &mdash; end-of-day interest computation.
 *       Iterates over all balances via {@link #findAll(org.springframework.data.domain.Pageable)
 *       findAll(Pageable)}; for each row, looks up the rate in
 *       {@code disclosure_group} via {@code DisclosureGroupRepository}
 *       (with the {@code DEFAULT} fallback) and updates this running
 *       balance plus the {@code accounts.acct_curr_bal} field via the
 *       inherited {@link #save(Object) save(TransactionCategoryBalance)}.
 *       Implements the canonical
 *       {@code balance.multiply(rate).divide(BigDecimal.valueOf(1200), 2,
 *       RoundingMode.HALF_EVEN)} monthly-interest formula per AAP
 *       &sect;0.6.1, preserving the literal divisor {@code 1200}
 *       verbatim.</li>
 *   <li>{@code TransactionPostingService} (Java target for COBOL
 *       {@code CBTRN02C}) &mdash; daily transaction posting. After the
 *       4-stage validation cascade succeeds, calls
 *       {@link #findById(Object) findById(TransactionCategoryBalanceId)}
 *       for the matching {@code (account, type, category)} tuple, then
 *       either updates the existing running balance or constructs a new
 *       {@link TransactionCategoryBalance} at zero starting balance,
 *       accumulates the posted {@code TRAN-AMT}, and persists via
 *       {@link #save(Object)}. Both branches are wrapped in
 *       {@code @Transactional(rollbackFor = Exception.class)} at the
 *       service boundary per AAP &sect;0.7.1.</li>
 *   <li>{@code TransactionAddService} (Java target for COBOL
 *       {@code COTRN02C}) &mdash; online transaction creation. Updates
 *       the running balance under the same
 *       {@code (account, type, category)} tuple within a
 *       {@code @Transactional} boundary alongside the {@code transactions}
 *       INSERT and {@code accounts} UPDATE (mirroring the COBOL CICS
 *       {@code SYNCPOINT} semantics).</li>
 *   <li>{@code TransactionReportService} (Java target for COBOL
 *       {@code CBTRN03C}) &mdash; joins to produce a per-category
 *       breakdown on monthly transaction reports.</li>
 *   <li>{@code StatementGenerationService} (Java target for COBOL
 *       {@code CBSTM03A} / {@code CBSTM03B}) &mdash; joins to break down
 *       the cycle balance by category on the generated monthly statement
 *       (Purchase / Payment / Credit / Authorization sub-totals).</li>
 * </ul>
 *
 * <h2>Design notes</h2>
 *
 * <ul>
 *   <li><strong>No custom derived queries / {@code @Query} annotations.</strong>
 *       The access patterns required by all consumers &mdash;
 *       (a) composite-primary-key lookup by
 *       {@link TransactionCategoryBalanceId} for per-row read-modify-write,
 *       (b) full-table iteration via {@link #findAll(org.springframework.data.domain.Pageable)
 *       findAll(Pageable)} for chunked batch processing, and
 *       (c) existence check via {@link #existsById(Object)} &mdash; are
 *       satisfied entirely by the inherited methods. Adding custom
 *       methods (e.g., {@code findAllByAccountId(Long acctId)}) would
 *       violate the Minimal Change Clause (AAP &sect;0.7.3).</li>
 *   <li><strong>Per-account scan via leading-prefix index, not a derived
 *       query.</strong> The {@code CBACT04C} pattern of "process every
 *       {@code (type, category)} bucket of one account in one logical
 *       unit of work" is satisfied implicitly by sorting on the
 *       composite key &mdash; account-id is the leading sub-field, so
 *       {@link #findAll(org.springframework.data.domain.Sort)
 *       findAll(Sort.by("id.trancatAcctId", "id.trancatTypeCd",
 *       "id.trancatCd"))} groups every row by account in the same
 *       lexicographic order as the COBOL VSAM browse. Future developers
 *       MUST NOT add a {@code findAllByIdTrancatAcctId(Long)} derived
 *       query here; the leading-prefix index access pattern is
 *       sufficient and matches the established
 *       {@code DisclosureGroupRepository} /
 *       {@code TransactionCategoryRepository} composite-key
 *       repository convention.</li>
 *   <li><strong>No business logic.</strong> Per AAP &sect;0.7.1 /
 *       &sect;0.7.3, repositories are pure persistence-layer
 *       components; all balance arithmetic (with
 *       {@link java.math.RoundingMode#HALF_EVEN} banker's rounding per
 *       AAP &sect;0.6.1), {@code ON SIZE ERROR} overflow detection,
 *       branch logic, and error translation lives in the service layer
 *       ({@code InterestCalculationService},
 *       {@code TransactionPostingService},
 *       {@code TransactionAddService}).</li>
 *   <li><strong>No optimistic-locking {@code @Version}.</strong> AAP
 *       &sect;0.6.2 specifies optimistic locking only for
 *       {@code accounts} (V001) and {@code cards} (V002). Concurrent
 *       updates to {@code tran_cat_bal} are serialized by the
 *       {@code @Transactional} service boundary in
 *       {@code InterestCalculationService},
 *       {@code TransactionPostingService}, and
 *       {@code TransactionAddService}, which is sufficient because the
 *       running-balance accumulation is monotonic and the conflict
 *       window is short.</li>
 *   <li><strong>No AWS SDK injection.</strong> Per AAP &sect;0.7.1, all
 *       AWS service integrations live in dedicated adapter classes
 *       ({@code S3OutputService}, {@code CacheService}, etc.) &mdash;
 *       never inline in repositories or services. This interface
 *       contains zero AWS SDK references.</li>
 *   <li><strong>Composite {@code @EmbeddedId} second type parameter.</strong>
 *       The {@link JpaRepository} second type parameter is
 *       {@link TransactionCategoryBalanceId}, the static nested
 *       {@link jakarta.persistence.Embeddable @Embeddable} class
 *       declared inside {@link TransactionCategoryBalance}. Callers MUST
 *       construct a fully-populated
 *       {@link TransactionCategoryBalanceId} instance
 *       ({@code new TransactionCategoryBalanceId(acctId, tranTypeCd,
 *       tranCatCd)}) before calling {@link #findById(Object)}; passing
 *       any of the three sub-fields individually is a compile-time
 *       error.</li>
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
 *       across the CardDemo repository inventory and matches the
 *       established pattern in
 *       {@code DisclosureGroupRepository},
 *       {@code TransactionCategoryRepository},
 *       {@code TransactionTypeRepository}, etc.</li>
 * </ul>
 *
 * <h2>Exception translation</h2>
 *
 * <p>Repository operations propagate Spring's
 * {@code org.springframework.dao.DataAccessException} hierarchy on
 * persistence failures. Service-layer code consuming this repository MUST
 * translate {@code DataAccessException} subclasses into the CardDemo
 * exception hierarchy before propagating to the controller boundary (per
 * AAP &sect;0.7.1):</p>
 * <ul>
 *   <li>{@code EmptyResultDataAccessException} &rarr;
 *       {@code RecordNotFoundException} (HTTP 404) for keyed-lookup
 *       misses that the consumer treats as errors.</li>
 *   <li>{@code DataIntegrityViolationException} &rarr;
 *       {@code DuplicateRecordException} (HTTP 409) for composite-key
 *       collisions on {@link #save(Object)} (although the {@code CBTRN02C}
 *       upsert pattern handles the "row already exists" case as the
 *       expected success branch).</li>
 * </ul>
 *
 * <p>Note that for the {@code CBTRN02C 2700-UPDATE-TCATBAL} upsert
 * pattern (per {@code app/cbl/CBTRN02C.cbl}:L467&ndash;L499), an
 * {@link java.util.Optional#isEmpty() empty Optional} from
 * {@link #findById(Object)} is the <em>expected, non-error</em> signal
 * that the service should construct a new
 * {@link TransactionCategoryBalance} at zero starting balance before
 * accumulating the posted amount. This mirrors the COBOL file-status
 * {@code 23} (NOTFND) branch which the COBOL source treats as a routine
 * control-flow signal (not an abend condition) via the predicate
 * {@code IF TCATBALF-STATUS = '00' OR '23'} at
 * {@code app/cbl/CBTRN02C.cbl}:L481.</p>
 *
 * <h2>AAP traceability</h2>
 *
 * <ul>
 *   <li>AAP &sect;0.3.1 &mdash; target structure ({@code repository/}
 *       package).</li>
 *   <li>AAP &sect;0.4.1 &mdash; repository file inventory
 *       ({@code TransactionCategoryBalanceRepository} &mdash;
 *       "JpaRepository with composite key", derived from
 *       {@code app/cpy/CVTRA01Y.cpy} and {@code app/cbl/CBACT04C.cbl}).</li>
 *   <li>AAP &sect;0.6.1 &mdash; {@link java.math.BigDecimal} balance
 *       arithmetic with banker's rounding ({@code HALF_EVEN}) in the
 *       service layer; literal {@code 1200} divisor preserved verbatim
 *       in monthly-interest formula.</li>
 *   <li>AAP &sect;0.6.2 &mdash; VSAM-to-RDS migration strategy
 *       (KSDS &rarr; relational table; composite key &rarr; JPA
 *       {@code @EmbeddedId}; no {@code @Version} on this entity).</li>
 *   <li>AAP &sect;0.7.1 &mdash; refactor discipline (layered Controller
 *       &rarr; Service &rarr; Repository architecture; repositories
 *       contain no business logic; balance arithmetic lives in the
 *       service layer within a {@code @Transactional} boundary).</li>
 *   <li>AAP &sect;0.7.3 &mdash; inline traceability comments (this
 *       repository documents the source COBOL artifacts it replaces).</li>
 * </ul>
 *
 * <p>All required CRUD operations &mdash;
 * {@link #findById(Object) findById(TransactionCategoryBalanceId)},
 * {@link #findAll()},
 * {@link #findAll(org.springframework.data.domain.Sort)},
 * {@link #findAll(org.springframework.data.domain.Pageable)},
 * {@link #save(Object) save(TransactionCategoryBalance)},
 * {@link #saveAll(Iterable) saveAll(Iterable)},
 * {@link #existsById(Object) existsById(TransactionCategoryBalanceId)},
 * {@link #deleteById(Object) deleteById(TransactionCategoryBalanceId)},
 * {@link #delete(Object) delete(TransactionCategoryBalance)}, and
 * {@link #count()} &mdash; are inherited from
 * {@link org.springframework.data.jpa.repository.JpaRepository}; no
 * custom derived queries or {@code @Query} annotations are required or
 * permitted on this interface.</p>
 *
 * @see com.awsm2.carddemo.domain.TransactionCategoryBalance
 *      the JPA entity mapped to the {@code tran_cat_bal} table
 * @see TransactionCategoryBalanceId
 *      the {@code @EmbeddedId} composite-primary-key class
 */
// Replaces: AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS
// (app/jcl/TCATBALF.jcl IDCAMS DEFINE CLUSTER KEYS(17 0) RECORDSIZE(50 50),
//  layout app/cpy/CVTRA01Y.cpy TRAN-CAT-BAL-RECORD, primary consumers
//  app/cbl/CBACT04C.cbl paragraph 1000-TCATBALF-GET-NEXT (sequential read
//  of every row) and app/cbl/CBTRN02C.cbl paragraph 2700-UPDATE-TCATBAL
//  (random read by composite key + REWRITE/WRITE upsert)).
// All business logic (BigDecimal arithmetic with HALF_EVEN rounding,
// ON SIZE ERROR overflow detection, DEFAULT fallback to DisclosureGroup,
// @Transactional boundaries) lives in the service layer -- NOT here.
@Repository
public interface TransactionCategoryBalanceRepository
        extends JpaRepository<TransactionCategoryBalance, TransactionCategoryBalanceId> {
}
