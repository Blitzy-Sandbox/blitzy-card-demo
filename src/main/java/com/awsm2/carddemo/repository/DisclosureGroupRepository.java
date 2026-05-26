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

import com.awsm2.carddemo.domain.DisclosureGroup;
import com.awsm2.carddemo.domain.DisclosureGroup.DisclosureGroupId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link DisclosureGroup} reference entity
 * &mdash; the disclosure-group interest-rate lookup that maps each
 * {@code (account-group-id, transaction-type-code, transaction-category-code)}
 * composite-key tuple to an annual interest-rate percentage (APR). This table
 * is the heart of the end-of-day interest-calculation batch job in the
 * CardDemo Java target.
 *
 * <p><strong>Replaces VSAM cluster:</strong>
 * {@code AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS}
 * (defined in {@code app/jcl/DISCGRP.jcl}:L36&ndash;L49 with
 * {@code KEYS(16 0)}, {@code RECORDSIZE(50 50)}, {@code SHAREOPTIONS(2 3)},
 * {@code ERASE}, {@code INDEXED} &mdash; composite 16-byte primary key
 * {@code DIS-GROUP-KEY} at relative key position 0). The 16-byte key
 * decomposes into three logical key columns whose widths sum exactly to
 * 16 bytes:</p>
 * <pre>
 *     DIS-ACCT-GROUP-ID (PIC X(10), 10 bytes)
 *   + DIS-TRAN-TYPE-CD  (PIC X(02),  2 bytes)
 *   + DIS-TRAN-CAT-CD   (PIC 9(04),  4 bytes)
 *   = 16-byte VSAM composite key
 * </pre>
 * <p>The COBOL {@code SHAREOPTIONS(2 3)} attribute has no PostgreSQL
 * equivalent: PostgreSQL handles concurrent reads/writes natively via MVCC
 * (multi-version concurrency control). The relational target is the
 * {@code disclosure_group} table created by Flyway migration
 * {@code V007__create_disclosure_group.sql} and seeded by
 * {@code V012__seed_disclosure_group.sql} (per AAP &sect;0.4.1,
 * &sect;0.6.2). The COBOL VSAM {@code READ}/{@code WRITE}/{@code REWRITE}/
 * {@code DELETE} verbs are replaced by the inherited Spring Data JPA
 * methods ({@link #findById(Object)}, {@link #save(Object)},
 * {@link #deleteById(Object)}, {@link #existsById(Object)}).</p>
 *
 * <p><strong>Source copybook:</strong> {@code app/cpy/CVTRA02Y.cpy}
 * ({@code DIS-GROUP-RECORD}, RECLN 50 bytes) &mdash; the layout containing
 * the 16-byte composite {@code DIS-GROUP-KEY} (3 sub-fields), a
 * {@code DIS-INT-RATE PIC S9(04)V99} rate field, and a trailing 28-byte
 * {@code FILLER PIC X(28)}. The FILLER has no relational equivalent and
 * is omitted from the JPA entity (see {@link DisclosureGroup} for the
 * field-level mapping).</p>
 *
 * <h2>Canonical reference values (seeded by V012, REC-TOTAL = 51)</h2>
 *
 * <p>51 reference rows are seeded once at schema-creation time by
 * {@code V012__seed_disclosure_group.sql} from
 * {@code app/data/ASCII/discgrp.txt} (the IDCAMS
 * {@code REPRO INFILE(DISCGRP) OUTFILE(DISCVSAM)} step at
 * {@code app/jcl/DISCGRP.jcl}:L54&ndash;L61 loads this same fixture into
 * the source VSAM cluster). The 51 rows are organized into three blocks
 * of 17 rows each &mdash; one row per {@code (type-cd, cat-cd)} tuple:</p>
 * <ul>
 *   <li>{@code "A000000000"} &mdash; the standard operational account
 *       group (a literal fully-numeric-looking string with leading
 *       {@code 'A'}; stored exactly as 10 chars).</li>
 *   <li>{@code "DEFAULT"} &mdash; the fallback group used when an
 *       account's specific group lookup misses (see <em>DEFAULT Fallback
 *       Pattern</em> below); stored <em>trimmed</em> of trailing padding
 *       spaces &mdash; the COBOL fixture pads it with 3 trailing spaces
 *       to fill the 10-char field, but V012 stores the trimmed 7-char
 *       value for query ergonomics (the {@code VARCHAR(10)} column
 *       tolerates this).</li>
 *   <li>{@code "ZEROAPR"} &mdash; the promotional zero-APR group; all
 *       17 rows have {@code dis_int_rate = 0.00}, so when an account has
 *       {@code acct_group_id = 'ZEROAPR'} the lookup succeeds on the
 *       first try and returns rate 0 (no DEFAULT fallback is taken);
 *       stored <em>trimmed</em>.</li>
 * </ul>
 *
 * <h2>DEFAULT Fallback Pattern (per CBACT04C.cbl:L416&ndash;L438)</h2>
 *
 * <p>The original COBOL program {@code app/cbl/CBACT04C.cbl} implements a
 * two-stage lookup in paragraph {@code 1200-GET-INTEREST-RATE}:</p>
 * <ol>
 *   <li>Read {@code DISCGRP-FILE} with composite key
 *       {@code (acctGroupId, tranTypeCd, tranCatCd)}.</li>
 *   <li>On {@code INVALID KEY} (file status {@code 23} &mdash; NOTFND):
 *       execute {@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID} and
 *       re-read via paragraph {@code 1200-A-GET-DEFAULT-INT-RATE}.</li>
 * </ol>
 *
 * <p><strong>In the Java target, this two-stage lookup is implemented by
 * {@code InterestCalculationService} (the service layer) &mdash; NOT by
 * this repository.</strong> Per AAP &sect;0.7.1 (layered Controller
 * &rarr; Service &rarr; Repository architecture), repositories are pure
 * persistence-layer components and contain <em>no business logic</em>.
 * The service code performs the two-stage lookup by calling the inherited
 * {@link JpaRepository#findById(Object) findById(...)} twice when needed
 * &mdash; first with the account's specific group ID, then with the
 * literal {@code "DEFAULT"} string on the first miss. This deliberate
 * separation of concerns is the single most important behavioral note for
 * this repository: future developers MUST NOT add a
 * {@code findWithDefaultFallback(...)} helper here; the fallback logic
 * lives in {@code InterestCalculationService} alongside the related
 * {@code ZEROAPR} short-circuit and the
 * {@code balance.multiply(rate).divide(BigDecimal.valueOf(1200), 2,
 * RoundingMode.HALF_EVEN)} monthly-interest computation (per AAP
 * &sect;0.6.1).</p>
 *
 * <h2>Composite Key</h2>
 *
 * <p>The {@code @EmbeddedId} on {@link DisclosureGroup} is the static
 * nested class {@link DisclosureGroupId}. Callers construct a key
 * instance and pass it to {@link #findById(Object)}:</p>
 * <pre>
 *     DisclosureGroupId key =
 *         new DisclosureGroupId(acctGroupId, tranTypeCd, tranCatCd);
 *     Optional&lt;DisclosureGroup&gt; group = disclosureGroupRepository.findById(key);
 *     if (group.isEmpty()) {
 *         // Stage 2 of the COBOL two-stage lookup &mdash; service layer only.
 *         DisclosureGroupId defaultKey =
 *             new DisclosureGroupId("DEFAULT", tranTypeCd, tranCatCd);
 *         group = disclosureGroupRepository.findById(defaultKey);
 *     }
 * </pre>
 *
 * <p>The {@code dis_acct_group_id} column is {@code VARCHAR(10)} (not
 * {@code CHAR(10)}) precisely so that the trimmed value {@code "DEFAULT"}
 * (7 chars) and {@code "ZEROAPR"} (7 chars) can be looked up without
 * space-padding to 10 chars. Service code passes the trimmed string
 * directly to {@code findById(...)} &mdash; no trailing-space
 * normalization is required.</p>
 *
 * <h2>Source consumers (REFERENCE &mdash; never modified)</h2>
 *
 * <p>The following COBOL program reads this VSAM cluster on the mainframe
 * and is migrated to consume this repository in the Java target:</p>
 * <ul>
 *   <li>{@code app/cbl/CBACT04C.cbl} &mdash; end-of-day interest
 *       calculation batch. The composite-key lookup in paragraph
 *       {@code 1200-GET-INTEREST-RATE} (with the DEFAULT fallback in
 *       paragraph {@code 1200-A-GET-DEFAULT-INT-RATE} per
 *       {@code app/cbl/CBACT04C.cbl}:L416&ndash;L450) is replaced by
 *       service-layer code in the Java target that calls
 *       {@link #findById(Object)} twice as needed.</li>
 * </ul>
 *
 * <h2>JCL sources (REFERENCE &mdash; never modified)</h2>
 *
 * <ul>
 *   <li>{@code app/jcl/DISCGRP.jcl} &mdash; IDCAMS {@code DEFINE CLUSTER}
 *       (STEP10) for the source VSAM KSDS, plus the {@code REPRO} load
 *       (STEP15) from {@code AWS.M2.CARDDEMO.DISCGRP.PS}. Replaced by
 *       Flyway migration {@code V007__create_disclosure_group.sql} (DDL)
 *       and {@code V012__seed_disclosure_group.sql} (DML seed).</li>
 *   <li>{@code app/catlg/LISTCAT.txt} &mdash; documents the VSAM cluster
 *       inventory (cluster name {@code AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS},
 *       {@code KEYLEN=16}, {@code RKP=0}, {@code AVGLRECL=50},
 *       {@code MAXLRECL=50}, {@code REC-TOTAL=51}). No runtime equivalent
 *       is needed in the Java target.</li>
 * </ul>
 *
 * <h2>Primary Consumers (Java)</h2>
 *
 * <ul>
 *   <li>{@code InterestCalculationService} &mdash; end-of-day interest
 *       computation (replacement for {@code app/cbl/CBACT04C.cbl}).
 *       Implements the DEFAULT fallback pattern described above by
 *       calling {@link #findById(Object)} twice when the first lookup
 *       misses (file status {@code 23} in COBOL terms; an empty
 *       {@link java.util.Optional} return in Java terms). Additionally
 *       relies on the inherited {@link #findAll()} method for warm-cache
 *       loading at job startup to avoid per-record round-trips against
 *       PostgreSQL during the high-volume interest pass.</li>
 *   <li>{@code StatementGenerationService} &mdash; joins to embed the
 *       applied APR percentage onto generated statements alongside
 *       per-category balance totals (replacement for
 *       {@code app/cbl/CBSTM03A.CBL} / {@code app/cbl/CBSTM03B.CBL}).
 *       Uses {@link #findById(Object)} per statement line, or
 *       {@link #findAll()} for bulk preloading.</li>
 * </ul>
 *
 * <h2>Design notes</h2>
 *
 * <ul>
 *   <li><strong>No custom derived queries / {@code @Query} annotations.</strong>
 *       The three access patterns required by all consumers &mdash;
 *       (a) composite-primary-key lookup by {@link DisclosureGroupId},
 *       (b) full-table dump for warm-cache preloading, and
 *       (c) existence check &mdash; are satisfied entirely by the
 *       inherited {@link JpaRepository#findById(Object)},
 *       {@link JpaRepository#findAll()}, and
 *       {@link JpaRepository#existsById(Object)} methods. Adding custom
 *       methods would violate the Minimal Change Clause (AAP
 *       &sect;0.7.3).</li>
 *   <li><strong>No {@code findWithDefaultFallback(...)} helper.</strong>
 *       The DEFAULT fallback is intentionally <em>not</em> declared on
 *       this repository &mdash; it is service-layer business logic that
 *       lives in {@code InterestCalculationService} per AAP &sect;0.7.1
 *       (layered architecture). Future developers MUST resist the
 *       temptation to "encapsulate" the two-stage lookup here, because
 *       doing so would bury a behavioral requirement (DEFAULT fallback)
 *       in a persistence-layer artifact and break the source-to-target
 *       traceability chain.</li>
 *   <li><strong>No business logic.</strong> Per AAP &sect;0.7.1 /
 *       &sect;0.7.3, repositories are pure persistence-layer
 *       components; all validation, branching, fallback,
 *       {@code BigDecimal} arithmetic, and error translation lives in
 *       the service layer ({@code InterestCalculationService},
 *       {@code StatementGenerationService}).</li>
 *   <li><strong>No optimistic-locking</strong> {@code @Version}.
 *       {@link DisclosureGroup} is static reference data seeded once by
 *       {@code V012__seed_disclosure_group.sql} and not updated at
 *       runtime; there is no read-modify-write contention to guard
 *       against. The {@code save(...)} and {@code saveAll(...)} methods
 *       are inherited for completeness (and exercised by the
 *       {@code @DataJpaTest} integration suite) but are not invoked by
 *       any production code path.</li>
 *   <li><strong>Composite {@code @EmbeddedId} second type parameter.</strong>
 *       The {@link JpaRepository} second type parameter is
 *       {@link DisclosureGroupId}, the static nested
 *       {@link jakarta.persistence.Embeddable @Embeddable} class
 *       declared inside {@link DisclosureGroup}. Callers MUST construct
 *       a fully-populated {@link DisclosureGroupId} instance
 *       ({@code new DisclosureGroupId(acctGroupId, tranTypeCd,
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
 * {@code DuplicateRecordException} (HTTP 409).</p>
 *
 * <p>Note that for the DEFAULT fallback pattern, an
 * {@link java.util.Optional#isEmpty() empty Optional} from the first
 * {@link #findById(Object)} call is the <em>expected, non-error</em>
 * signal that the service should retry with the {@code "DEFAULT"}
 * group ID. This mirrors the COBOL file-status {@code 23} (NOTFND)
 * branch in {@code CBACT04C.cbl}:L436&ndash;L439, which is
 * <em>not</em> an abend condition but a routine control-flow signal
 * per the {@code IF DISCGRP-STATUS = '00' OR '23'} predicate at
 * {@code app/cbl/CBACT04C.cbl}:L422.</p>
 *
 * <h2>AAP traceability</h2>
 *
 * <ul>
 *   <li>AAP &sect;0.3.1 &mdash; target structure ({@code repository/} package).</li>
 *   <li>AAP &sect;0.4.1 &mdash; repository file inventory
 *       ({@code DisclosureGroupRepository} &mdash; "supports DEFAULT
 *       fallback lookup", derived from {@code app/cpy/CVTRA02Y.cpy}
 *       and {@code app/cbl/CBACT04C.cbl}).</li>
 *   <li>AAP &sect;0.6.1 &mdash; {@link java.math.BigDecimal} interest
 *       calculations with banker's rounding ({@code HALF_EVEN}) in the
 *       service layer.</li>
 *   <li>AAP &sect;0.6.2 &mdash; VSAM-to-RDS migration strategy
 *       (KSDS &rarr; relational table; composite key &rarr; JPA
 *       {@code @EmbeddedId}).</li>
 *   <li>AAP &sect;0.7.1 &mdash; refactor discipline (layered Controller
 *       &rarr; Service &rarr; Repository architecture; repositories
 *       contain no business logic; DEFAULT fallback lives in the
 *       service layer).</li>
 *   <li>AAP &sect;0.7.3 &mdash; inline traceability comments (every
 *       repository documents the source COBOL artifacts it replaces).</li>
 * </ul>
 *
 * <p>All required CRUD operations &mdash;
 * {@link #findById(Object) findById(DisclosureGroupId)},
 * {@link #findAll()}, {@link #findAll(org.springframework.data.domain.Sort)},
 * {@link #findAll(org.springframework.data.domain.Pageable)},
 * {@link #save(Object) save(DisclosureGroup)},
 * {@link #saveAll(Iterable) saveAll(Iterable)},
 * {@link #existsById(Object) existsById(DisclosureGroupId)},
 * {@link #deleteById(Object) deleteById(DisclosureGroupId)},
 * {@link #delete(Object) delete(DisclosureGroup)}, and
 * {@link #count()} &mdash; are inherited from
 * {@link org.springframework.data.jpa.repository.JpaRepository}; no
 * custom derived queries or {@code @Query} annotations are required or
 * permitted on this interface.</p>
 *
 * @see com.awsm2.carddemo.domain.DisclosureGroup
 *      the JPA entity mapped to the {@code disclosure_group} lookup table
 * @see DisclosureGroupId
 *      the {@code @EmbeddedId} composite-primary-key class
 */
// Replaces: AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS
// (app/jcl/DISCGRP.jcl IDCAMS DEFINE CLUSTER KEYS(16 0) RECORDSIZE(50 50),
//  layout app/cpy/CVTRA02Y.cpy DIS-GROUP-RECORD, primary consumer
//  app/cbl/CBACT04C.cbl paragraph 1200-GET-INTEREST-RATE).
// DEFAULT fallback per CBACT04C.cbl:L416-L438 lives in
// InterestCalculationService (service layer) -- NOT here.
@Repository
public interface DisclosureGroupRepository extends JpaRepository<DisclosureGroup, DisclosureGroupId> {
}
