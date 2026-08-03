/*
 * ****************************************************************************
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
 * ****************************************************************************
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
 * ****************************************************************************
 */
package com.cardemo.repository;

import com.cardemo.model.entity.TransactionType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Transaction type reference data access: the relational replacement for the VSAM access verbs over the KSDS
 * cluster {@code AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS}.
 *
 * <p><b>What it does.</b> Resolves a two character transaction type code to the fifty character
 * description that the batch reporting stream prints beside it. The interface deliberately
 * declares <i>no method of its own</i>: the entire access surface the legacy corpus exercises is
 * already inherited from {@link JpaRepository}, and the evidence for adding nothing to it is set
 * out under <i>Method surface</i> and <i>Deliberate omissions</i> below. There is no
 * implementation class, no custom fragment interface, no dynamic predicate or criteria API
 * helper, no mapper and no DAO wrapper anywhere in this package, and no base repository
 * abstraction above it; Spring Data supplies the proxy at runtime.
 *
 * <h2>Verified physical contract</h2>
 *
 * <p>The record layout is {@code app/cpy/CVTRA03Y.cpy}, whose header comment at {@code :L2} reads
 * "Data-structure for transaction type (RECLN = 60)" and whose group item at {@code :L4} is
 * {@code 01 TRAN-TYPE-RECORD}:
 *
 * <pre>
 * #  COBOL field (CVTRA03Y.cpy)  PIC    Bytes  Java property    Column          SQL type
 * -  --------------------------  -----  -----  ---------------  --------------  -------------------
 * 1  TRAN-TYPE       (:L5)       X(02)  1-2    typeCode         tran_type       CHAR(2) PRIMARY KEY
 * 2  TRAN-TYPE-DESC  (:L6)       X(50)  3-52   typeDescription  tran_type_desc  CHAR(50) NOT NULL
 * -  FILLER          (:L7)       X(08)  53-60  not modelled     not modelled    not modelled
 * </pre>
 *
 * <p>{@code 2 + 50 + 8 = 60} bytes. The key length and record length are each attested three
 * times over, by independent artefacts, which is why they are stated as facts rather than as
 * inferences:
 *
 * <ul>
 *   <li>The provisioning job {@code app/jcl/TRANTYPE.jcl} issues
 *       {@code DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS)} at {@code :L36} with
 *       {@code KEYS(2 0)} at {@code :L40} and {@code RECORDSIZE(60 60)} at {@code :L41}, under
 *       {@code INDEXED} at {@code :L44}. Equal minimum and maximum record size makes the length
 *       exact rather than an average. Every step of that job runs {@code PGM=IDCAMS}.</li>
 *   <li>The VSAM catalogue listing opens the cluster entry at {@code app/catlg/LISTCAT.txt:L3742}
 *       and reports {@code KEYLEN 2} with {@code AVGLRECL 60} on the DATA component attribute
 *       line at {@code :L3779}; the following line adds {@code RKP 0} and {@code MAXLRECL 60}, so
 *       the key occupies bytes 1 to 2 of the record. The statistics block at {@code :L3784}
 *       reports {@code REC-TOTAL 7}.</li>
 *   <li>The fixture {@code app/data/ASCII/trantype.txt} closes the loop at 427 bytes: seven rows
 *       of exactly 60 characters plus one line terminator each, which is precisely the
 *       {@code REC-TOTAL 7} the catalogue records. The seven codes are {@code 01} Purchase,
 *       {@code 02} Payment, {@code 03} Credit, {@code 04} Authorization, {@code 05} Refund,
 *       {@code 06} Reversal and {@code 07} Adjustment.</li>
 *   </ul>
 *
 * <p>Every seeded code is exactly two digits, zero padded, so the identifier is a fixed width
 * character string and not a number. That is why this repository is typed
 * {@code JpaRepository<TransactionType, String>}: narrowing the key to an integral type would
 * discard the leading zero, and {@code "01"} and {@code 1} are not the same VSAM key.
 *
 * <h2>Findings, classified by severity</h2>
 *
 * <p><b>High: the primary key column is {@code tran_type}, not {@code tran_type_cd}.</b> The key
 * field at {@code app/cpy/CVTRA03Y.cpy:L5} is declared {@code 05 TRAN-TYPE PIC X(02)} — named
 * plainly, with no {@code -CD} suffix. This table is the sole exception in the corpus: every
 * other place that holds a transaction type code does carry the suffix, namely
 * {@code TRAN-TYPE-CD} at {@code app/cpy/CVTRA05Y.cpy:L6} and in {@code app/cpy/CVTRA06Y.cpy},
 * {@code TRAN-TYPE-CD} at {@code app/cpy/CVTRA04Y.cpy:L6}, {@code TRANCAT-TYPE-CD} at
 * {@code app/cpy/CVTRA01Y.cpy:L7} and {@code DIS-TRAN-TYPE-CD} at
 * {@code app/cpy/CVTRA02Y.cpy:L7}. Because {@code spring.jpa.hibernate.ddl-auto} is set to
 * {@code validate} in every profile, generalising from those five neighbours to a
 * {@code tran_type_cd} column here does not degrade gracefully — it aborts application context
 * startup with a missing column. <b>Remediation:</b> use {@code tran_type}, take the copybook
 * rather than the sibling copybooks as authoritative, and assert the spelling in the repository
 * integration test so a later schema edit cannot silently reintroduce the suffix. No derived
 * query method or {@code @Query} in this file targets {@code tran_type_cd}, and none may.
 *
 * <p><b>Closed: all three Flyway migrations are present.</b> {@code V1__create_schema.sql} declares this
 * table, {@code V2__create_indexes.sql} correctly adds no index to it, and {@code V3__seed_data.sql} seeds
 * its 7 rows. See <i>Information gaps</i> below for the contract {@code V1} declares.
 *
 * <p><b>Low: this is a batch only dataset, so it is granted no online surface.</b> Developed in
 * the next section; the consequence is an authorisation boundary, not a defect.
 *
 * <p><b>Low: FILE STATUS {@code '35'} is specification derived, not source grounded.</b> Also
 * recorded under <i>Information gaps</i>.
 *
 * <h2>Batch only: proved by absence from the CICS resource definitions</h2>
 *
 * <p>{@code app/csd/CARDDEMO.CSD} declares exactly <b>eight</b> {@code DEFINE FILE} entries, and
 * they are the complete online file control table: {@code ACCTDAT} at {@code :L1},
 * {@code CARDAIX} at {@code :L13}, {@code CARDDAT} at {@code :L25}, {@code CCXREF} at
 * {@code :L37}, {@code CUSTDAT} at {@code :L50}, {@code CXACAIX} at {@code :L63},
 * {@code TRANSACT} at {@code :L76} and {@code USRSEC} at {@code :L88}. The string
 * {@code TRANTYPE} occurs <b>zero</b> times in that file, as do {@code TCATBALF},
 * {@code DISCGRP} and {@code TRANCATG}.
 *
 * <p>That absence is the evidence, and it is binding rather than incidental:
 *
 * <ul>
 *   <li>This repository is consumed by {@code com.cardemo.batch} and {@code com.cardemo.service}
 *       <b>only</b>. No CICS transaction ever opened the underlying cluster, so no online caller
 *       inherits a right to it.</li>
 *   <li><b>No controller CRUD, no REST endpoint and no admin management surface exists — or may
 *       be added — for {@code TRANTYPE}, {@code TCATBALF}, {@code DISCGRP} or
 *       {@code TRANCATG}.</b> The target exposes exactly <b>17</b> operations across <b>8</b>
 *       {@code @RestController} classes, mapped one for one from the 17 sourced CSD transactions,
 *       and not one of them touches these four datasets. Publishing reference data maintenance
 *       that the system of record never had would widen the attack surface beyond the legacy
 *       system's own, which is the concrete reading of Rule 1 clause D, "principle of least
 *       privilege for tokens/credentials/config", at the persistence boundary.</li>
 *   <li>The integration surface is correspondingly the batch tier: this repository is to be exercised
 *       through the transaction report job against a Testcontainers PostgreSQL 16 instance, not
 *       through the REST surface, because the REST surface has no path that reaches it.
 *       <strong>Partly not available:</strong> {@code com.cardemo.batch.jobs.TransactionReportJob} still
 *       does not exist, so the report-job surface above remains the surface the tests are <em>to</em> take.
 *       {@code src/test/java/com/cardemo/integration} does exist now, however, and
 *       {@code RepositorySchemaAndFinderIntegrationTest} exercises this repository against a containerised
 *       PostgreSQL 16 instance, so it is no longer true that no tier reaches it at all. An earlier revision
 *       recorded both as absent.</li>
 *   </ul>
 *
 * <h2>Method surface: three inherited operations, nothing declared</h2>
 *
 * <p>Rule 1 clause B forbids dead code, and this package carries <b>no exemption</b> from it:
 * the three intentionally retained legacy no-ops that the parity mandate protects all live
 * elsewhere — the empty fee paragraph at {@code app/cbl/CBACT04C.cbl:L518-L520}, the assigned
 * but never consumed reject code at {@code app/cbl/CBTRN02C.cbl:L556} and the redundant index
 * assignment in {@code app/cbl/CBSTM03A.CBL:L316-L338}. Clause B therefore applies here at full
 * strength, and every operation below is inherited from {@link JpaRepository} and left
 * undeclared, so no signature in this file can outlive its caller.
 *
 * <ol>
 *   <li><b>{@code Optional<TransactionType> findById(String typeCode)}</b>
 *       <br><b>Purpose.</b> The keyed reference lookup, and the only access pattern the corpus
 *       actually performs. It replaces {@code READ TRANTYPE-FILE INTO TRAN-TYPE-RECORD} at
 *       {@code app/cbl/CBTRN03C.cbl:L495}, reached from
 *       {@code MOVE TRAN-TYPE-CD OF TRAN-RECORD TO FD-TRAN-TYPE} at {@code :L189} followed by
 *       {@code PERFORM 1500-B-LOOKUP-TRANTYPE} at {@code :L190}, whose resolved description is
 *       moved onto the report line at {@code :L366}. The same two character code is the value
 *       that flows into the category balance key at {@code app/cbl/CBTRN02C.cbl:L470}
 *       ({@code MOVE DALYTRAN-TYPE-CD TO FD-TRANCAT-TYPE-CD}) and into the disclosure group key
 *       at {@code app/cbl/CBACT04C.cbl:L212}
 *       ({@code MOVE TRANCAT-TYPE-CD TO FD-DIS-TRAN-TYPE-CD}).
 *       <br><b>Input.</b> The two character type code, exactly as read from the transaction
 *       record. It is bound as a parameter by the Spring Data proxy and is never interpolated
 *       into a statement, so a hostile value cannot alter the query.
 *       <br><b>Output.</b> An {@code Optional} that is present when the row exists and empty
 *       when it does not. The description is returned blank padded to its full {@code CHAR(50)}
 *       width, which is the faithful {@code PIC X(50)} image; callers comparing against an
 *       unpadded literal must strip first.
 *       <br><b>Side effects.</b> None. A read only lookup: the legacy file is opened
 *       {@code OPEN INPUT} at {@code app/cbl/CBTRN03C.cbl:L432} and never written, which is why
 *       no mutating operation is declared here.
 *       <br><b>Failure modes.</b> An empty result is the modelled miss and is <i>not</i> an
 *       exception — the legacy program reacts to it explicitly with
 *       {@code DISPLAY 'INVALID TRANSACTION TYPE : '} at {@code :L497} and
 *       {@code MOVE 23 TO IO-STATUS} at {@code :L498} before abending at {@code :L500}. That
 *       status is FILE STATUS {@code '23'}, which
 *       {@code com.cardemo.service.shared.FileStatusMapper} translates to
 *       {@code com.cardemo.exception.RecordNotFoundException}. Translation happens there exactly
 *       once and never in this interface. Where a caller instead treats the empty case as a
 *       validation outcome, that choice belongs to the caller and must be explicit at the call
 *       site. Infrastructure failures surface as the Spring Data access exception hierarchy with
 *       the driver exception preserved as the cause; nothing is swallowed here, because nothing
 *       is caught here.</li>
 *   <li><b>{@code boolean existsById(String typeCode)}</b>
 *       <br><b>Purpose.</b> A presence probe over the same key, available for a validator that
 *       needs to know only whether a code is recognised. It is documented as available rather
 *       than promoted, because no caller is invented for it: the legacy corpus needs the
 *       description in every case where it needs the code to be valid, so
 *       {@code findById} is the honest translation of {@code 1500-B-LOOKUP-TRANTYPE}.
 *       <br><b>Input and output.</b> The two character code; {@code true} when the row exists.
 *       <br><b>Side effects.</b> None.
 *       <br><b>Failure modes.</b> As for {@code findById}, minus the empty case, which collapses
 *       into {@code false} rather than an {@code Optional}.</li>
 *   <li><b>{@code List<TransactionType> findAll()}</b>
 *       <br><b>Purpose.</b> A whole table read, legitimate here and nowhere near it in size:
 *       the reference set is <b>7</b> rows, attested twice over by {@code REC-TOTAL 7} at
 *       {@code app/catlg/LISTCAT.txt:L3784} and by the seven 60 byte rows of
 *       {@code app/data/ASCII/trantype.txt}. Loading all seven into a service held validation
 *       cache costs one query and a few hundred bytes, so Rule 1 clause A's requirement to
 *       "avoid obvious inefficiencies" is met by the table's size rather than by a code
 *       restriction — and the tradeoff is stated here rather than assumed.
 *       <br><b>Input.</b> None.
 *       <br><b>Output.</b> All rows, in <b>unspecified order</b>. This is the one sharp edge in
 *       the inherited surface: {@code findAll()} carries no {@code ORDER BY}, so PostgreSQL may
 *       return rows in heap order, and that order can change after any update or vacuum. Rule 1
 *       clause A demands determinism, so a caller that renders, digests or diffs this set must
 *       impose its own ordering — either by passing a {@code Sort} to the inherited
 *       {@code findAll(Sort)}, which needs no new method here, or by sorting the returned list.
 *       Callers that merely build a keyed map are order independent and need neither.
 *       <br><b>Side effects.</b> None.
 *       <br><b>Failure modes.</b> An empty list means the seed migration did not run; it is not
 *       an error at this layer, and the integration test that asserts a row count of exactly 7
 *       is what turns it into a detectable one.</li>
 *   </ol>
 *
 * <h2>Deliberate omissions, each with its reason</h2>
 *
 * <ul>
 *   <li><b>No ordered full read method, such as {@code findAllByOrderByTypeCodeAsc()}.</b> One
 *       was considered and rejected on evidence. {@code app/cbl/CBTRN03C.cbl} is the only
 *       program in the 28 program corpus that opens this file at all — it is the sole
 *       {@code COPY CVTRA03Y} site, at {@code :L103} — and its file declaration reads
 *       {@code ORGANIZATION IS INDEXED} at {@code :L40} with <b>{@code ACCESS MODE IS RANDOM}</b>
 *       at {@code :L41}. Under {@code RANDOM} access a sequential browse is not merely unused
 *       but impossible: there is no {@code READ NEXT}, no {@code START} and no browse verb
 *       against this file anywhere in {@code app/cbl}. Nothing in the target iterates the table
 *       in key order either. Declaring the finder would therefore add a signature with no call
 *       site, which is exactly the dead code clause B forbids, and the inherited
 *       {@code findAll(Sort)} already covers any future ordered read without one.</li>
 *   <li><b>No alternate key finder.</b> The catalogue defines exactly three alternate indexes in
 *       the whole system — {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX},
 *       {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX} and
 *       {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX} — and none of them is over
 *       {@code TRANTYPE}. This package accordingly declares three alternate key finders in
 *       total, on the card, cross reference and transaction repositories, and none here. Adding a
 *       fourth would imply a fourth index and would break alignment with
 *       {@code V2__create_indexes.sql}, which creates exactly three.</li>
 *   <li><b>No finder on the description, and no projection.</b> No program searches this table
 *       by description; the description is only ever an output, moved onto the report line at
 *       {@code app/cbl/CBTRN03C.cbl:L366}. A two column entity has nothing to project away.</li>
 *   <li><b>No {@code @Query}, and therefore no JPQL or SQL text in this file at all.</b> Nothing
 *       here requires a hand written statement, so none is written; the risk of a concatenated
 *       or interpolated query is removed structurally rather than mitigated. Any future
 *       {@code @Query} added to this package must bind {@code @Param} named parameters and must
 *       never build a statement by string concatenation or use {@code nativeQuery} with an
 *       interpolated value.</li>
 *   <li><b>No declarative bulk update annotation, and no write method of any kind.</b> The legacy
 *       file
 *       is opened {@code OPEN INPUT} at {@code app/cbl/CBTRN03C.cbl:L432} and closed at
 *       {@code :L571}; the corpus contains no {@code WRITE}, {@code REWRITE} or {@code DELETE}
 *       against it. Rows arrive once, from {@code V3__seed_data.sql}. The inherited mutators are
 *       not removed — Spring Data has no mechanism for that short of a narrower base interface,
 *       which the single interface per cluster rule excludes — but nothing in the target calls
 *       them, and no bulk update is declared.</li>
 *   <li><b>No enum mapping, and specifically no mapping to
 *       {@code com.cardemo.model.enums.TransactionSource}.</b> That enum holds exactly two
 *       constants, the literal {@code 'System'} moved to {@code TRAN-SOURCE} at
 *       {@code app/cbl/CBACT04C.cbl:L484} for generated interest transactions and
 *       {@code 'POS TERM'} at {@code app/cbl/COBIL00C.cbl:L222}. A transaction <i>source</i> is
 *       not a transaction <i>type</i>: the two are unrelated fields with unrelated domains, and
 *       the name similarity is a trap rather than a hint. Nor is the type code itself an enum —
 *       the seven rows are seeded data, so a new type can be seeded without a recompile, and an
 *       enum would turn an unrecognised code into a load time failure instead of the diagnostic
 *       the legacy program produces at {@code app/cbl/CBTRN03C.cbl:L497}.</li>
 *   <li><b>No static state and no cache in this interface.</b> An interface cannot hold mutable
 *       state without a static field, and none is declared. Any reference data cache belongs in
 *       an injected service bean with a defined lifecycle, per clause B's preference for
 *       dependency injection over global mutable state.</li>
 *   <li><b>No environment lookup and no hardcoded coordinate.</b> This file reads no environment
 *       variable and no JVM system property, and contains no host, port, path, URL or credential
 *       of any kind. Connectivity is configuration, resolved by the container from the active
 *       profile, which keeps the build free of the environment specific assumptions clause C
 *       rules out.</li>
 *   </ul>
 *
 * <h2>Configuration contract and operational notes</h2>
 *
 * <ul>
 *   <li>{@code spring.jpa.hibernate.ddl-auto: validate} in every profile. The entity's derived
 *       property names, column names, types and lengths must match the migrated schema exactly
 *       or the context fails to start. This is the mechanism through which the {@code tran_type}
 *       finding above would manifest, and it is a feature: a naming divergence is caught at
 *       startup rather than at the first query.</li>
 *   <li>{@code spring.jpa.open-in-view: false}. Results are returned fully initialised; there is
 *       no lazy access outside a transaction. This entity has no association, so there is
 *       nothing to initialise lazily and no N+1 exposure to manage.</li>
 *   <li>{@code spring.jpa.show-sql: false}, with no Hibernate SQL or bind parameter logging in
 *       any profile. This table holds no personal data, but the setting is uniform across the
 *       package so that no repository becomes the one that leaks a bound card number or
 *       government identifier into a log. Nothing in this file logs, and nothing in it could:
 *       observability is delegated wholesale to {@code com.cardemo.observability}, which carries
 *       the correlation identifier, the trace and span identifiers and the four named counters.</li>
 *   <li>Spring Batch metadata tables are created by the framework's own script through
 *       {@code spring.batch.jdbc.initialize-schema}, never by a fourth Flyway migration and
 *       never as extra tables in {@code V1}.</li>
 *   <li><b>HikariCP connection pool tuning is explicitly out of scope</b> and is recorded as a
 *       residual risk in the planned {@code DECISION_LOG.md} and {@code docs/validation-gates.md}. The
 *       defaults are used as shipped. Stating the gap is the honest discharge of clause A's
 *       "justify tradeoffs only when needed"; inventing pool figures with no measured workload
 *       behind them would be the dishonest one.</li>
 *   </ul>
 *
 * <h2>How to build and test</h2>
 *
 * <p>Build with {@code ./mvnw -B clean compile} on the pinned toolchain, OpenJDK 25 with Maven
 * 3.9.11, against {@code org.springframework.boot:spring-boot-starter-parent:3.5.11}. The
 * compiler runs {@code -Xlint:all -Werror} with {@code failOnWarning}, so any warning in a category
 * {@code javac} 25 publishes is a build failure rather than a warning. An unused import is not one of
 * those categories, so the three imports above being each load bearing is a review guarantee rather
 * than a compiler one. Unit tests run at {@code ./mvnw -B test}; the repository integration tier runs at
 * {@code ./mvnw -B verify} under
 * {@code src/test/java/com/cardemo/integration/repository}, against a Testcontainers PostgreSQL
 * 16 container, and to assert the seeded row count of exactly 7 together with the
 * {@code tran_type} column spelling. Testcontainers is pinned to 2.0.3 by overriding the
 * Boot managed version property rather than by importing a second bill of materials, and only
 * the prefixed artefact identifiers resolve at that version.
 *
 * <p><b>Common failure modes.</b> A startup failure naming a missing column on
 * {@code transaction_type} means the migration spelled the key {@code tran_type_cd}; correct the
 * migration, not this interface. A startup failure reporting a type mismatch on either column
 * means the schema used a variable length type where the copybook specifies fixed width
 * {@code CHAR}. An empty {@code findAll()} means {@code V3__seed_data.sql} did not run. A
 * description that fails to equal an unpadded literal in Java is correct behaviour, not a defect:
 * {@code CHAR(50)} returns blank padded, and the padding is the {@code PIC X(50)} image.
 *
 * <h2>Information gaps: "Not available"</h2>
 *
 * <p>Rule 1 clause F requires that missing information be stated plainly rather than assumed.
 * Two items were recorded as <b>Not available</b> when this interface was authored. The first has
 * since been closed by measurement and is recorded as closed rather than quietly dropped:
 *
 * <ol>
 *   <li><b>All three Flyway migrations are present.</b> An earlier revision of this bullet recorded
 *       {@code V2__create_indexes.sql} and {@code V3__seed_data.sql} as non-existent; that is no longer
 *       true and the claim is withdrawn.
 *       {@code V1__create_schema.sql} declares {@code CREATE TABLE transaction_type},
 *       {@code V2} adds no index to it, and {@code V3} seeds its 7 rows.
 *       Because {@code ddl-auto: validate} makes the match mandatory rather than advisory,
 *       the contract restated here is what {@code V1} declares at that line and what this interface
 *       and its entity are typed over — table {@code transaction_type}, with columns
 *       <b>{@code tran_type CHAR(2)}</b> as the primary key, <i>not</i> {@code tran_type_cd},
 *       and {@code tran_type_desc CHAR(50)} not null, and with no version column, since this is
 *       static reference data. {@code V2} adds <b>no</b> index on this table, as required: its three
 *       indexes are {@code idx_card_acct_id}, {@code idx_card_cross_reference_acct_id} and
 *       {@code idx_transaction_proc_ts}. {@code V3} seeds exactly <b>7</b> rows from the
 *       7 sixty byte records of {@code app/data/ASCII/trantype.txt}. A mismatch on any of these
 *       points fails context startup outright rather than degrading gracefully.</li>
 *   <li><b>FILE STATUS {@code '35'}, file unavailable, is "Not available" as a grounded source
 *       construct.</b> The literal {@code '35'} appears nowhere in {@code app/cbl} — zero
 *       occurrences across all 28 programs — and the CICS response census is
 *       {@code DFHRESP(NORMAL)} 43, {@code DFHRESP(NOTFND)} 23, {@code DFHRESP(ENDFILE)} 8,
 *       {@code DFHRESP(DUPREC)} 7, {@code DFHRESP(DUPKEY)} 3 and <b>{@code DFHRESP(NOTOPEN)}
 *       zero</b>. The corresponding {@code com.cardemo.exception.FileUnavailableException} is
 *       therefore specification derived only, and no behaviour of this repository depends on it.
 *       <i>What is needed</i> to promote it from specification to evidence: a legacy artefact
 *       that tests for status {@code '35'} or for {@code NOTOPEN}. None exists at {@code 7756d89}.
 *       The statuses this repository's access path <i>does</i> ground are {@code '00'}, checked
 *       after the open at {@code app/cbl/CBTRN03C.cbl:L433}, and {@code '23'}, set on the keyed
 *       miss at {@code :L498}.</li>
 *   </ol>
 *
 * @see TransactionType
 */
@Repository
public interface TransactionTypeRepository extends JpaRepository<TransactionType, String> {
}
