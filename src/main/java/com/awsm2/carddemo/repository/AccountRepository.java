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

import com.awsm2.carddemo.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link Account} entity &mdash; the
 * foundational entity repository of the CardDemo system.
 *
 * <p><strong>Replaces VSAM cluster:</strong>
 * {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS} (defined in
 * {@code app/jcl/ACCTFILE.jcl} with {@code KEYS(11 0)} &mdash; 11-byte
 * primary key {@code ACCT-ID} at offset 0; {@code RECORDSIZE(300 300)};
 * {@code SHAREOPTIONS(2 3)}; {@code ERASE}; {@code INDEXED}). No alternate
 * index (AIX) or PATH exists over this cluster &mdash; all COBOL access is by
 * primary key (random read on {@code ACCT-ID}) or sequential scan
 * ({@code app/cbl/CBACT01C.cbl}); the PostgreSQL B-tree on the primary key
 * satisfies both access patterns.</p>
 *
 * <p><strong>Source copybook:</strong> {@code app/cpy/CVACT01Y.cpy}
 * ({@code ACCOUNT-RECORD}, RECLN 300; 12 business fields plus a 178-byte
 * trailing {@code FILLER PIC X(178)} that has no relational counterpart).</p>
 *
 * <p><strong>Schema:</strong> Flyway migration
 * {@code src/main/resources/db/migration/V001__create_account.sql} creates
 * the {@code accounts} table. Hibernate
 * {@code spring.jpa.hibernate.ddl-auto: validate} verifies that the
 * {@link Account} mapping aligns with the V001 DDL at application startup.</p>
 *
 * <h2>Monetary Precision (AAP &sect;0.6.1)</h2>
 *
 * <p>All FIVE monetary fields on the {@link Account} entity &mdash;
 * {@link Account#getAcctCurrBal() acctCurrBal},
 * {@link Account#getAcctCreditLimit() acctCreditLimit},
 * {@link Account#getAcctCashCreditLimit() acctCashCreditLimit},
 * {@link Account#getAcctCurrCycCredit() acctCurrCycCredit},
 * {@link Account#getAcctCurrCycDebit() acctCurrCycDebit} &mdash; are
 * {@link java.math.BigDecimal} mapped to PostgreSQL {@code NUMERIC(12,2)},
 * preserving the COBOL {@code PIC S9(10)V99} precision <em>exactly</em>
 * (precision = 10 integer digits + 2 fractional digits; value range
 * &#x2212;9,999,999,999.99 .. +9,999,999,999.99). All arithmetic in service-
 * layer code uses {@link java.math.RoundingMode#HALF_EVEN HALF_EVEN}
 * (banker's rounding) per AAP &sect;0.6.1 to match COBOL {@code PIC 9}
 * decimal-arithmetic semantics. <strong>This repository itself performs no
 * arithmetic</strong>; all balance computations live in
 * {@code AccountUpdateService}, {@code BillPaymentService},
 * {@code InterestCalculationService}, and {@code TransactionPostingService}
 * per the layered-architecture rule (AAP &sect;0.7.1). The PostgreSQL
 * {@code NUMERIC} type is arbitrary-precision exact arithmetic &mdash;
 * <strong>NEVER use {@code float} or {@code double}</strong> for monetary
 * values; floating-point breaks parity with the COBOL source.</p>
 *
 * <h2>Optimistic Locking (AAP &sect;0.6.2 / &sect;0.7.1)</h2>
 *
 * <p>The {@link Account} entity carries a JPA
 * {@link jakarta.persistence.Version &#64;Version} field
 * ({@code version BIGINT}). On {@link JpaRepository#save(Object) save(...)},
 * Hibernate:</p>
 * <ol>
 *   <li>Auto-increments the {@code version} column on the in-memory entity.</li>
 *   <li>Issues an {@code UPDATE} whose {@code WHERE} clause includes the
 *       previously-loaded {@code version} value.</li>
 *   <li>If another transaction has committed in the interim, the
 *       {@code WHERE} clause matches 0 rows; Hibernate detects this and
 *       throws
 *       {@link org.hibernate.StaleObjectStateException} which Spring's
 *       persistence exception translation (enabled by
 *       {@link Repository &#64;Repository}) wraps as
 *       {@link org.springframework.orm.ObjectOptimisticLockingFailureException}
 *       (around {@link jakarta.persistence.OptimisticLockException}).</li>
 *   <li>The {@code GlobalExceptionHandler}
 *       ({@code @RestControllerAdvice}) maps this to the domain
 *       {@code ConcurrentModificationException} and returns HTTP 409 Conflict
 *       to the REST caller, preserving the COBOL "snapshot mismatch" error
 *       semantics.</li>
 * </ol>
 *
 * <p>This {@code @Version} mechanism is the JPA-idiomatic replacement for
 * the COBOL before/after image comparison in {@code app/cbl/COACTUPC.cbl}
 * (CICS {@code READ UPDATE} &rarr; {@code SYNCPOINT} &rarr;
 * {@code REWRITE}, with manual snapshot comparison). It is the single most
 * important COBOL &rarr; JPA semantic translation for this entity &mdash;
 * future developers MUST NOT attempt to "re-implement" optimistic locking
 * with manual version-field arithmetic; {@code save(Account)} already does
 * the correct thing.</p>
 *
 * <h2>Transactional Boundaries</h2>
 *
 * <p>The only explicit {@code EXEC CICS SYNCPOINT ROLLBACK} in the entire
 * COBOL source is in {@code app/cbl/COACTUPC.cbl} (dual update of account
 * &amp; customer rows under a single CICS unit of work). In the Java target,
 * this is implemented by wrapping {@code AccountUpdateService} methods (and
 * any other service method that writes to more than one entity) with
 * {@code @Transactional(rollbackFor = Exception.class)} &mdash; declared on
 * the <strong>service</strong> layer, NOT here. The repository itself is
 * transaction-context-aware via Spring's transaction manager and inherits
 * the active transaction from its caller. Concretely:</p>
 * <ul>
 *   <li>{@code AccountUpdateService} (replacement for
 *       {@code app/cbl/COACTUPC.cbl}) is annotated
 *       {@code @Transactional(rollbackFor = Exception.class)} on the dual-
 *       entity update method.</li>
 *   <li>{@code BillPaymentService} (replacement for
 *       {@code app/cbl/COBIL00C.cbl}) is annotated
 *       {@code @Transactional(rollbackFor = Exception.class)} on the dual-
 *       write method (account balance update + transaction row insert +
 *       {@code account.updated} MSK event publish).</li>
 *   <li>{@code TransactionPostingService} (replacement for
 *       {@code app/cbl/CBTRN02C.cbl}) is annotated
 *       {@code @Transactional(rollbackFor = Exception.class)} on the
 *       4-stage validation cascade so a failed credit-limit / expiration
 *       check rolls back any in-flight balance change.</li>
 * </ul>
 *
 * <h2>Consumers</h2>
 *
 * <p>Every Java service that reads or writes an account row injects this
 * repository via constructor injection. The complete list of consumers
 * (each mapped to its COBOL source for traceability per AAP &sect;0.7.3):</p>
 * <ul>
 *   <li>{@code AccountViewService} (replacement for
 *       {@code app/cbl/COACTVWC.cbl}) &mdash; random read by
 *       {@code acctId}, then joins with {@code Customer} via
 *       {@code CardCrossReferenceRepository.findByXrefAcctId(...)} (which
 *       replaces the {@code CXACAIX} alternate index) to render the
 *       account-inquiry screen {@code GET /api/accounts/{id}}.</li>
 *   <li>{@code AccountUpdateService} (replacement for
 *       {@code app/cbl/COACTUPC.cbl}) &mdash; random read + update under a
 *       {@code @Transactional(rollbackFor = Exception.class)} boundary
 *       with {@code @Version} optimistic locking. This is the single
 *       COBOL program with explicit {@code EXEC CICS SYNCPOINT ROLLBACK}.
 *       PUT {@code /api/accounts/{id}}.</li>
 *   <li>{@code AccountFileReaderService} (replacement for
 *       {@code app/cbl/CBACT01C.cbl}) &mdash; batch sequential scan via
 *       {@link JpaRepository#findAll() findAll()} or
 *       {@link JpaRepository#findAll(org.springframework.data.domain.Pageable) findAll(Pageable)}
 *       for chunked iteration through the Spring Batch
 *       {@code RepositoryItemReader}.</li>
 *   <li>{@code TransactionPostingService} (replacement for
 *       {@code app/cbl/CBTRN01C.cbl} / {@code app/cbl/CBTRN02C.cbl} /
 *       {@code app/cbl/CBTRN03C.cbl}) &mdash; Stage 2 of the 4-stage
 *       validation cascade ({@link JpaRepository#findById(Object) findById(acctId)}
 *       after the XREF lookup resolves the card-number-to-account
 *       relationship), followed by the credit-limit check (Stage 3) and
 *       balance update on successful posting. Reject codes 100&ndash;109
 *       preserved verbatim per AAP &sect;0.1.1.</li>
 *   <li>{@code BillPaymentService} (replacement for
 *       {@code app/cbl/COBIL00C.cbl}) &mdash;
 *       {@code @Transactional}-wrapped dual write: read the account row,
 *       apply {@link java.math.BigDecimal} arithmetic with
 *       {@link java.math.RoundingMode#HALF_EVEN HALF_EVEN}, save the
 *       updated entity, then write the offsetting {@code transactions}
 *       row and publish the {@code account.updated} MSK event.</li>
 *   <li>{@code InterestCalculationService} (replacement for
 *       {@code app/cbl/CBACT04C.cbl}) &mdash; end-of-cycle batch job
 *       that scans every account row, joins against
 *       {@code TransactionCategoryBalance} +
 *       {@code DisclosureGroup} (with {@code DEFAULT} fallback per AAP
 *       &sect;0.4.1), computes interest via the literal COBOL formula
 *       {@code (balance * rate) / 1200} preserved without algebraic
 *       simplification (AAP &sect;0.6.1 / &sect;0.7.3 Minimal Change
 *       Clause), and updates {@code acctCurrBal} via this repository's
 *       {@link JpaRepository#save(Object) save(Account)}.</li>
 * </ul>
 *
 * <h2>Caching (AAP &sect;0.6.6)</h2>
 *
 * <p>The {@code CacheService} adapter (ElastiCache Redis, cache-aside
 * pattern with TTL aligned to transaction frequency and {@code allkeys-lru}
 * eviction) wraps high-frequency balance reads to reduce RDS load. The
 * cache lives in the service layer, NOT in this repository &mdash; the
 * repository proxy bean stays free of caching concerns to remain a thin,
 * type-safe data-access layer.</p>
 *
 * <h2>Method Inventory (Inherited from {@link JpaRepository})</h2>
 *
 * <p>All required CRUD and query methods are inherited from
 * {@link JpaRepository}; no custom derived queries or {@code @Query}
 * annotations are required at this time:</p>
 * <ul>
 *   <li>{@link JpaRepository#findById(Object) findById(Long)} &mdash;
 *       replaces CICS {@code READ DATASET('ACCTDAT') RIDFLD(ACCT-ID)}.</li>
 *   <li>{@link JpaRepository#findAll() findAll()} /
 *       {@link JpaRepository#findAll(org.springframework.data.domain.Sort) findAll(Sort)}
 *       /
 *       {@link JpaRepository#findAll(org.springframework.data.domain.Pageable) findAll(Pageable)}
 *       &mdash; replaces the COBOL sequential-scan pattern in
 *       {@code app/cbl/CBACT01C.cbl} (open INPUT, read NEXT until
 *       end-of-file).</li>
 *   <li>{@link JpaRepository#save(Object) save(Account)} /
 *       {@link JpaRepository#saveAll(Iterable) saveAll(Iterable&lt;Account&gt;)}
 *       /
 *       {@link JpaRepository#saveAndFlush(Object) saveAndFlush(Account)}
 *       &mdash; replaces CICS {@code WRITE} / {@code REWRITE}; the
 *       {@code @Version} field automatically increments and triggers
 *       optimistic-locking detection on snapshot mismatch.</li>
 *   <li>{@link JpaRepository#existsById(Object) existsById(Long)} &mdash;
 *       used by {@code TransactionPostingService} during the XREF &rarr;
 *       Account validation hand-off.</li>
 *   <li>{@link JpaRepository#deleteById(Object) deleteById(Long)} /
 *       {@link JpaRepository#delete(Object) delete(Account)} /
 *       {@link JpaRepository#deleteAll() deleteAll()} &mdash; available
 *       for completeness; production flows preserve the COBOL semantic
 *       that accounts are <em>closed</em> via {@code acctActiveStatus =
 *       'N'} rather than physically deleted.</li>
 *   <li>{@link JpaRepository#count() count()} &mdash; row-count for
 *       reporting and operational dashboards.</li>
 *   <li>{@link JpaRepository#getReferenceById(Object) getReferenceById(Long)}
 *       &mdash; lazy proxy reference, useful when only the {@code acctId}
 *       is needed as a JPA association target (no consumer service uses
 *       this today but it is part of the contract).</li>
 *   <li>{@link JpaRepository#flush() flush()} &mdash; explicit flush
 *       of pending changes, useful in Spring Batch chunk-oriented
 *       processing.</li>
 * </ul>
 *
 * <h2>Design Constraints (per AAP &sect;0.7.3 Minimal Change Clause)</h2>
 *
 * <p>This repository intentionally exposes <strong>only</strong> the
 * inherited {@link JpaRepository} contract. The following methods are
 * <strong>deliberately not</strong> declared, per the layered-architecture
 * and Minimal-Change rules:</p>
 * <ul>
 *   <li><strong>No {@code findByCustomerId(Long custId)}</strong> &mdash;
 *       the Account &harr; Customer relationship is mediated by the
 *       {@code CardCrossReference} (XREF) entity (V004 schema); there is no
 *       direct customer foreign key on the {@code accounts} table. Cross-
 *       entity lookups go through {@code CardCrossReferenceRepository} per
 *       the COBOL access patterns (see {@code app/cbl/COACTVWC.cbl}).</li>
 *   <li><strong>No {@code updateBalance(Long acctId, BigDecimal delta)}
 *       or similar arithmetic shortcut</strong> &mdash; balance updates
 *       use {@link JpaRepository#findById(Object) findById} &rarr; setter
 *       &rarr; {@link JpaRepository#save(Object) save} to preserve
 *       {@code @Version} semantics (a bulk
 *       {@code UPDATE accounts SET acct_curr_bal = ... WHERE acct_id = ?}
 *       would bypass JPA dirty-checking and defeat optimistic locking).</li>
 *   <li><strong>No business logic, no Jakarta Validation, no AWS SDK
 *       calls, no caching annotations.</strong> All business rules live in
 *       the service layer per AAP &sect;0.7.1.</li>
 *   <li><strong>No {@code findByActiveStatus} / {@code findByGroupId} /
 *       similar derived queries.</strong> The COBOL source enumerates
 *       accounts only via primary-key access ({@code findById}), batch
 *       iteration ({@code findAll}), or via {@code CardCrossReference}
 *       resolution. Adding extra finder methods now would violate the
 *       AAP &sect;0.7.3 Minimal Change Clause.</li>
 * </ul>
 *
 * <p>The {@link Repository &#64;Repository} stereotype annotation
 * &mdash; while not strictly required for {@link JpaRepository} subinterfaces
 * (Spring Data automatically registers the proxy bean) &mdash; is declared
 * here for clarity and to make the bean role explicit at the class
 * declaration site, and to ensure Spring's persistence exception
 * translation (DataAccessException hierarchy) is unambiguously applied.</p>
 *
 * @see com.awsm2.carddemo.domain.Account
 * @see org.springframework.data.jpa.repository.JpaRepository
 * @see org.springframework.stereotype.Repository
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
}
