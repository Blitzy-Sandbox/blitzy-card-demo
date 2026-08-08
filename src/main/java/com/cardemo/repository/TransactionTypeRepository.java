/*
 * ******************************************************************
 * Program     : TransactionTypeRepository.java
 * Application : CardDemo
 * Type        : Spring Data JPA Repository Interface
 * Function    : Replaces the VSAM access verbs over the transaction type
 *               reference table. Batch-only: no CICS file definition exists.
 * Source      : AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS, key 2 / reclen 60
 *               (app/catlg/LISTCAT.txt:L3779 DATA-component attribute line;
 *               app/jcl/TRANTYPE.jcl:L36, L40-L41 KEYS(2 0) RECORDSIZE(60 60));
 *               record layout app/cpy/CVTRA03Y.cpy:L4-L7; ABSENT from
 *               app/csd/CARDDEMO.CSD (batch-only proof) @ 7756d89
 * ******************************************************************
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
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.repository;

import com.cardemo.model.entity.TransactionType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Transaction type reference data access: the relational replacement for the VSAM access verbs over the
 * KSDS cluster {@code AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS}.
 *
 * <p><b>What it does.</b> Resolves a two character transaction type code to the fifty character
 * description that the batch reporting stream prints beside it. The interface declares <i>no method of
 * its own</i>: the whole access surface the corpus exercises is inherited from {@link JpaRepository}, so
 * there is no implementation class, no custom fragment, no criteria helper, no mapper and no DAO wrapper
 * in this package. Spring Data supplies the proxy at runtime.
 *
 * <h2>Physical contract</h2>
 *
 * <p>The record layout is {@code app/cpy/CVTRA03Y.cpy}, whose header at {@code :L2} reads
 * "Data-structure for transaction type (RECLN = 60)" and whose group item at {@code :L4} is
 * {@code 01 TRAN-TYPE-RECORD}:
 *
 * <pre>
 * #  COBOL field (CVTRA03Y.cpy)  PIC    Bytes  Java property    Column          SQL type
 * -  --------------------------  -----  -----  ---------------  --------------  -------------------
 * 1  TRAN-TYPE       (:L5)       X(02)  1-2    typeCode         tran_type       CHAR(2) PRIMARY KEY
 * 2  TRAN-TYPE-DESC  (:L6)       X(50)  3-52   typeDescription  tran_type_desc  CHAR(50) NOT NULL
 * </pre>
 *
 * <p>The remaining 8 bytes of the 60 byte record are {@code FILLER} at {@code :L7} and are not
 * modelled. The cluster is catalogued with key length 2 and record length 60
 * ({@code app/catlg/LISTCAT.txt:L3779}; {@code app/jcl/TRANTYPE.jcl:L36, L40-L41}), and holds exactly
 * <b>7</b> rows, attested by {@code REC-TOTAL 7} at {@code app/catlg/LISTCAT.txt:L3784} and by the seven
 * 60 byte records of {@code app/data/ASCII/trantype.txt}.
 *
 * <p><b>The primary key column is {@code tran_type}, not {@code tran_type_cd}.</b> The key field at
 * {@code app/cpy/CVTRA03Y.cpy:L5} is declared {@code 05 TRAN-TYPE PIC X(02)} — with no {@code -CD}
 * suffix. This table is the sole exception in the corpus: every other holder of a transaction type code
 * carries the suffix ({@code TRAN-TYPE-CD} at {@code app/cpy/CVTRA05Y.cpy:L6} and
 * {@code app/cpy/CVTRA04Y.cpy:L6}, {@code TRANCAT-TYPE-CD} at {@code app/cpy/CVTRA01Y.cpy:L7},
 * {@code DIS-TRAN-TYPE-CD} at {@code app/cpy/CVTRA02Y.cpy:L7}). Because
 * {@code spring.jpa.hibernate.ddl-auto} is {@code validate} in every profile, generalising from those
 * neighbours does not degrade gracefully — it aborts context startup with a missing column. The
 * copybook, not its siblings, is authoritative, and {@code V1__create_schema.sql} declares
 * {@code tran_type} accordingly. No query in this file targets {@code tran_type_cd}, and none may.
 *
 * <p>Every seeded code is exactly two digits, zero padded, so the identifier is a fixed width character
 * string and not a number. That is why the repository is typed
 * {@code JpaRepository<TransactionType, String>}: narrowing the key to an integral type would discard
 * the leading zero, and {@code "01"} and {@code 1} are not the same VSAM key. The table carries no
 * version column, being static reference data, and {@code V2__create_indexes.sql} adds no index to it —
 * the catalogue's three alternate indexes are all over other clusters.
 *
 * <h2>Batch only: proved by absence from the CICS resource definitions</h2>
 *
 * <p>{@code app/csd/CARDDEMO.CSD} declares exactly <b>eight</b> {@code DEFINE FILE} entries, the
 * complete online file control table: {@code ACCTDAT}, {@code CARDAIX}, {@code CARDDAT},
 * {@code CCXREF}, {@code CUSTDAT}, {@code CXACAIX}, {@code TRANSACT} and {@code USRSEC}. The string
 * {@code TRANTYPE} occurs <b>zero</b> times in that file, as do {@code TCATBALF}, {@code DISCGRP} and
 * {@code TRANCATG}.
 *
 * <p>That absence is binding rather than incidental. This repository is consumed by
 * {@code com.cardemo.batch} and {@code com.cardemo.service} only; no CICS transaction ever opened the
 * underlying cluster, so no online caller inherits a right to it. <b>No controller, REST endpoint or
 * admin management surface exists — or may be added — for {@code TRANTYPE}, {@code TCATBALF},
 * {@code DISCGRP} or {@code TRANCATG}.</b> The target exposes exactly 17 operations across 8
 * {@code @RestController} classes, mapped one for one from the 17 sourced CSD transactions, and none of
 * them touches these four datasets. Publishing reference data maintenance that the system of record
 * never had would widen the attack surface beyond the legacy system's own, which is the concrete reading
 * of Rule 1 clause D, least privilege, at the persistence boundary.
 *
 * <h2>Method surface: three inherited operations, nothing declared</h2>
 *
 * <ol>
 *   <li><b>{@code Optional<TransactionType> findById(String typeCode)}</b> — the keyed reference lookup,
 *       and the only access pattern the corpus performs. It replaces
 *       {@code READ TRANTYPE-FILE INTO TRAN-TYPE-RECORD} at {@code app/cbl/CBTRN03C.cbl:L495}, reached
 *       from {@code MOVE TRAN-TYPE-CD OF TRAN-RECORD TO FD-TRAN-TYPE} at {@code :L189} followed by
 *       {@code PERFORM 1500-B-LOOKUP-TRANTYPE} at {@code :L190}, whose resolved description is moved onto
 *       the report line at {@code :L366}.
 *       <br><b>Input.</b> The two character code, exactly as read from the transaction record. It is
 *       bound as a parameter by the Spring Data proxy and never interpolated into a statement.
 *       <br><b>Output.</b> An {@code Optional}, present when the row exists. The description is returned
 *       blank padded to its full {@code CHAR(50)} width, which is the faithful {@code PIC X(50)} image;
 *       callers comparing against an unpadded literal must strip first.
 *       <br><b>Side effects.</b> None. The legacy file is opened {@code OPEN INPUT} at
 *       {@code app/cbl/CBTRN03C.cbl:L432} and never written.
 *       <br><b>Failure modes.</b> An empty result is the modelled miss and is <i>not</i> an exception —
 *       the legacy program reacts explicitly with {@code DISPLAY 'INVALID TRANSACTION TYPE : '} at
 *       {@code :L497} and {@code MOVE 23 TO IO-STATUS} at {@code :L498} before abending at {@code :L500}.
 *       That status is FILE STATUS {@code '23'}, which
 *       {@code com.cardemo.service.shared.FileStatusMapper} translates to
 *       {@code com.cardemo.exception.RecordNotFoundException} — there exactly once, never in this
 *       interface. Where a caller instead treats the empty case as a validation outcome, that choice
 *       belongs to the caller and must be explicit at the call site. Infrastructure failures surface as
 *       the Spring Data access exception hierarchy with the driver exception preserved as the cause;
 *       nothing is swallowed here, because nothing is caught here.</li>
 *   <li><b>{@code boolean existsById(String typeCode)}</b> — a presence probe over the same key, for a
 *       validator that needs only to know whether a code is recognised. Documented as available rather
 *       than promoted: the corpus needs the description in every case where it needs the code to be
 *       valid, so {@code findById} is the honest translation of {@code 1500-B-LOOKUP-TRANTYPE}. No side
 *       effects; the empty case collapses into {@code false}.</li>
 *   <li><b>{@code List<TransactionType> findAll()}</b> — a whole table read, legitimate here because the
 *       reference set is 7 rows: one query and a few hundred bytes for a service held validation cache.
 *       <br><b>Output.</b> All rows, in <b>unspecified order</b>. This is the one sharp edge in the
 *       inherited surface: {@code findAll()} carries no {@code ORDER BY}, so PostgreSQL may return rows
 *       in heap order, and that order can change after any update or vacuum. A caller that renders,
 *       digests or diffs this set must impose its own ordering, either by passing a {@code Sort} to the
 *       inherited {@code findAll(Sort)} or by sorting the returned list. Callers that build a keyed map
 *       are order independent and need neither.
 *       <br><b>Failure modes.</b> An empty list means {@code V3__seed_data.sql} did not run; the
 *       integration assertion of exactly 7 rows is what turns that into a detectable condition.</li>
 * </ol>
 *
 * <h2>Deliberate omissions, each with its reason</h2>
 *
 * <ul>
 *   <li><b>No ordered full read method.</b> {@code app/cbl/CBTRN03C.cbl} is the only program in the 28
 *       program corpus that opens this file — the sole {@code COPY CVTRA03Y} site, at {@code :L103} — and
 *       its file declaration reads {@code ORGANIZATION IS INDEXED} at {@code :L40} with
 *       <b>{@code ACCESS MODE IS RANDOM}</b> at {@code :L41}. Under {@code RANDOM} access a sequential
 *       browse is impossible: there is no {@code READ NEXT}, no {@code START} and no browse verb against
 *       this file anywhere in {@code app/cbl}, and nothing in the target iterates the table in key order.
 *       The inherited {@code findAll(Sort)} covers any ordered read without a declared signature.</li>
 *   <li><b>No alternate key finder.</b> The catalogue defines exactly three alternate indexes in the
 *       whole system — over {@code CARDDATA}, {@code CARDXREF} and {@code TRANSACT} — and none over
 *       {@code TRANTYPE}. This package declares three alternate key finders in total, and none here; a
 *       fourth would imply a fourth index and break alignment with {@code V2__create_indexes.sql}.</li>
 *   <li><b>No finder on the description, and no projection.</b> No program searches by description; it is
 *       only ever an output, moved onto the report line at {@code app/cbl/CBTRN03C.cbl:L366}. A two
 *       column entity has nothing to project away.</li>
 *   <li><b>No {@code @Query}, and therefore no JPQL or SQL text in this file at all.</b> The risk of a
 *       concatenated or interpolated query is removed structurally rather than mitigated. Any future
 *       {@code @Query} in this package must bind {@code @Param} named parameters and must never build a
 *       statement by concatenation or use {@code nativeQuery} with an interpolated value.</li>
 *   <li><b>No write method of any kind.</b> The legacy file is opened {@code OPEN INPUT} at
 *       {@code app/cbl/CBTRN03C.cbl:L432} and closed at {@code :L571}; the corpus contains no
 *       {@code WRITE}, {@code REWRITE} or {@code DELETE} against it. Rows arrive once, from
 *       {@code V3__seed_data.sql}. The inherited mutators cannot be removed short of a narrower base
 *       interface, which the single interface per cluster rule excludes, but nothing in the target calls
 *       them and no bulk update is declared.</li>
 *   <li><b>No enum mapping, and specifically none to
 *       {@code com.cardemo.model.enums.TransactionSource}.</b> That enum holds two constants, the literal
 *       {@code 'System'} moved to {@code TRAN-SOURCE} at {@code app/cbl/CBACT04C.cbl:L484} and
 *       {@code 'POS TERM'} at {@code app/cbl/COBIL00C.cbl:L222}. A transaction <i>source</i> is not a
 *       transaction <i>type</i>: unrelated fields with unrelated domains, and the name similarity is a
 *       trap rather than a hint. Nor is the type code itself an enum — the seven rows are seeded, so a new
 *       type can be seeded without a recompile, whereas an enum would turn an unrecognised code into a
 *       load time failure instead of the diagnostic at {@code app/cbl/CBTRN03C.cbl:L497}.</li>
 *   <li><b>No static state, no cache, no environment lookup and no hardcoded coordinate.</b> Any
 *       reference data cache belongs in an injected service bean with a defined lifecycle. This file
 *       reads no environment variable or system property and contains no host, port, path, URL or
 *       credential; connectivity is configuration, resolved by the container from the active profile.</li>
 * </ul>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li>{@code spring.jpa.hibernate.ddl-auto: validate} in every profile. The entity's property names,
 *       column names, types and lengths must match the migrated schema exactly or the context fails to
 *       start. That is the mechanism through which a {@code tran_type} misspelling manifests, and it is a
 *       feature: a naming divergence is caught at startup rather than at the first query.</li>
 *   <li>{@code spring.jpa.open-in-view: false}. Results are returned fully initialised. This entity has
 *       no association, so there is nothing to initialise lazily and no N+1 exposure to manage.</li>
 *   <li>{@code spring.jpa.show-sql: false}, with no Hibernate SQL or bind parameter logging in any
 *       profile. This table holds no personal data, but the setting is uniform across the package so that
 *       no repository becomes the one that leaks a bound card number or government identifier into a log.
 *       Nothing in this file logs; observability is delegated wholesale to
 *       {@code com.cardemo.observability}.</li>
 *   <li>Spring Batch metadata tables are created by the framework's own script through
 *       {@code spring.batch.jdbc.initialize-schema}, never by a fourth Flyway migration and never as
 *       extra tables in {@code V1}.</li>
 *   <li>HikariCP connection pool tuning is out of scope; the defaults are used as shipped. Inventing pool
 *       figures with no measured workload behind them would be the dishonest discharge of clause A.</li>
 * </ul>
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Build with {@code ./mvnw -B clean compile} on the pinned toolchain, OpenJDK 25 with Maven 3.9.11,
 * against {@code org.springframework.boot:spring-boot-starter-parent:3.5.11}. The compiler runs
 * {@code -Xlint:all -Werror} with {@code failOnWarning}, so any warning in a category {@code javac} 25
 * publishes fails the build. Unit tests run at {@code ./mvnw -B test}; the repository integration tier
 * runs at {@code ./mvnw -B verify}, where
 * {@code src/test/java/com/cardemo/integration/repository/TransactionTypeRepositoryTest} and
 * {@code RepositorySchemaAndFinderIntegrationTest} exercise this repository against a Testcontainers
 * PostgreSQL 16 container and assert both the seeded row count of exactly 7 and the {@code tran_type}
 * column spelling. Testcontainers is pinned to 2.0.3 by overriding the Boot managed version property
 * rather than by importing a second bill of materials, and only the prefixed artefact identifiers resolve
 * at that version.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li>A startup failure naming a missing column on {@code transaction_type} means the migration spelled
 *       the key {@code tran_type_cd}; correct the migration, not this interface.</li>
 *   <li>A startup failure reporting a type mismatch on either column means the schema used a variable
 *       length type where the copybook specifies fixed width {@code CHAR}.</li>
 *   <li>An empty {@code findAll()} means {@code V3__seed_data.sql} did not run.</li>
 *   <li>A description that fails to equal an unpadded literal in Java is correct behaviour, not a defect:
 *       {@code CHAR(50)} returns blank padded, and the padding is the {@code PIC X(50)} image.</li>
 *   <li>A rendered or digested {@code findAll()} result that differs between runs is the unordered read
 *       above; impose an explicit {@code Sort} at the call site.</li>
 * </ul>
 *
 * @see TransactionType
 */
@Repository
public interface TransactionTypeRepository extends JpaRepository<TransactionType, String> {
}
