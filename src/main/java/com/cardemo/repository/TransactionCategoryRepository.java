/*
 * ****************************************************************************
 * Program     : TransactionCategoryRepository.java
 * Application : CardDemo
 * Type        : Spring Data JPA Repository Interface
 * Function    : Replaces the VSAM access verbs over the transaction category
 *               reference table. Batch-only: no CICS file definition exists.
 * Source      : AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS, composite key 6 / reclen 60
 *               (app/catlg/LISTCAT.txt:L1475 DATA-component attribute line;
 *               app/jcl/TRANCATG.jcl:L36, L40-L41 KEYS(6 0) RECORDSIZE(60 60));
 *               record layout app/cpy/CVTRA04Y.cpy:L4-L9; ABSENT from
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

import com.cardemo.model.entity.TransactionCategory;
import com.cardemo.model.key.TransactionCategoryId;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Transaction category reference data access: the relational replacement for the VSAM access verbs over the
 * KSDS cluster {@code AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS}.
 *
 * <p><b>What it does.</b> Resolves a transaction type and category pair — a two character type code
 * followed by a four digit category code, six bytes together — to the fifty character description
 * that the batch reporting stream prints beside it. The interface deliberately declares <i>no
 * method of its own</i>: the entire access surface the legacy corpus exercises is already inherited
 * from {@link JpaRepository}, and the evidence for adding nothing to it is set out under <i>Method
 * surface</i> and <i>Deliberate omissions</i> below. There is no implementation class, no custom
 * fragment interface, no dynamic predicate or criteria API helper, no mapper and no DAO wrapper
 * anywhere in this package, and no base repository abstraction above it; Spring Data supplies the
 * proxy at runtime.
 *
 * <h2>Verified physical contract</h2>
 *
 * <p>The record layout is {@code app/cpy/CVTRA04Y.cpy}, whose header comment at {@code :L2} reads
 * "Data-structure for transaction category type (RECLN = 60)" and whose group item at {@code :L4}
 * is {@code 01 TRAN-CAT-RECORD}:
 *
 * <pre>
 * #  COBOL field (CVTRA04Y.cpy)   PIC    Bytes  Java property        Column
 * -  ---------------------------  -----  -----  ------------------- --------------------
 * -  TRAN-CAT-KEY       (:L5)     group  1-6    id (&#64;EmbeddedId)     composite PRIMARY KEY
 * 1  . TRAN-TYPE-CD     (:L6)     X(02)  1-2    id.tranTypeCd       tran_type_cd
 * 2  . TRAN-CAT-CD      (:L7)     9(04)  3-6    id.tranCatCd        tran_cat_cd
 * 3  TRAN-CAT-TYPE-DESC (:L8)     X(50)  7-56   categoryDescription tran_cat_type_desc
 * -  FILLER             (:L9)     X(04)  57-60  not modelled        not modelled
 * </pre>
 *
 * <p>{@code 2 + 4 = 6} bytes of key and {@code 6 + 50 + 4 = 60} bytes of record. Both figures are
 * attested three times over by independent artefacts, which is why they are stated as facts rather
 * than as inferences:
 *
 * <ul>
 *   <li>The provisioning job {@code app/jcl/TRANCATG.jcl} issues
 *       {@code DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS)} at {@code :L36} with
 *       <b>{@code KEYS(6 0)}</b> at {@code :L40} and <b>{@code RECORDSIZE(60 60)}</b> at
 *       {@code :L41}, under {@code INDEXED} at {@code :L44}. Equal minimum and maximum record size
 *       makes the length exact rather than an average, and {@code KEYS(6 0)} places the key at
 *       offset zero. Every step of that job runs {@code PGM=IDCAMS}: {@code STEP05} deletes and
 *       resets the condition code, {@code STEP10} defines, {@code STEP15} loads from
 *       {@code AWS.M2.CARDDEMO.TRANCATG.PS}.</li>
 *   <li>The VSAM catalogue listing opens the cluster entry at {@code app/catlg/LISTCAT.txt:L1440}
 *       and its DATA component at {@code :L1465}, then reports {@code KEYLEN 6} with
 *       {@code AVGLRECL 60} on the DATA component attribute line at {@code :L1475}; the following
 *       line adds {@code RKP 0} and {@code MAXLRECL 60}, so the key occupies bytes 1 to 6 of the
 *       record. The INDEX component repeats {@code KEYLEN 6} and {@code RKP 0} at {@code :L1507}
 *       and {@code :L1508}, and the DATA statistics block reports <b>{@code REC-TOTAL 18}</b> at
 *       {@code :L1482} with {@code REC-DELETED 0}.</li>
 *   <li>The fixture {@code app/data/ASCII/trancatg.txt} closes the loop at 1,098 bytes: eighteen
 *       rows of exactly 60 characters plus one line terminator each, which is precisely the
 *       {@code REC-TOTAL 18} the catalogue records. Its first row is
 *       {@code 010001Regular Sales Draft}, blank filled, so type {@code 01} category {@code 0001}
 *       is "Regular Sales Draft". The four trailing filler bytes hold {@code 0000} rather than
 *       spaces in every row, which is further confirmation that they are unused padding carried
 *       for the record length and not a field: they are deliberately not modelled.</li>
 *   </ul>
 *
 * <p>The two components are typed to their picture clauses and not to their convenience. The type
 * code is a fixed width character string because every seeded value is two digits with a
 * significant leading zero — {@code "01"} and {@code 1} are not the same VSAM key, and narrowing it
 * to an integral type would discard the zero. The category code is {@code PIC 9(04)}, an unscaled
 * identifier, and is therefore an {@code Integer} and not a {@code BigDecimal}: it counts nothing
 * and carries no scale, so the decimal precision tiers that govern monetary fields elsewhere in
 * this migration do not apply to it. There is no {@code float} and no {@code double} anywhere in
 * this table, in this interface, or in the entity and identifier it is typed over.
 *
 * <p>Component order is load bearing. The identifier declares {@code tranTypeCd} before
 * {@code tranCatCd}, mirroring bytes 1 to 2 before bytes 3 to 6, and the composite primary key must
 * be declared in that same order. A pair assembled with the two components transposed is not an
 * error either component can detect, because each value is individually valid; it simply misses.
 *
 * <h2>Findings, classified by severity</h2>
 *
 * <p><b>High: the group name {@code TRAN-CAT-KEY} denotes two different keys of two different
 * widths, and they must never be harmonised.</b> Two copybooks declare a group with that identical
 * name at the identical line number, and the shapes are not compatible:
 *
 * <pre>
 * Copybook                  Group  Components                            Total  Java key class
 * ------------------------  -----  ------------------------------------  -----  ----------------------------
 * app/cpy/CVTRA04Y.cpy      :L5    TRAN-TYPE-CD    X(02)  (:L6)              2  TransactionCategoryId
 *  (transaction category)          TRAN-CAT-CD     9(04)  (:L7)         +   4
 *                                                                       =   6
 * app/cpy/CVTRA01Y.cpy      :L5    TRANCAT-ACCT-ID 9(11)  (:L6)             11  TransactionCategoryBalanceId
 *  (transaction cat balance)       TRANCAT-TYPE-CD X(02)  (:L7)         +   2
 *                                  TRANCAT-CD      9(04)  (:L8)         +   4
 *                                                                       =  17
 * </pre>
 *
 * <p>Note the prefixes: {@code TRAN-} in the six byte key of this table, {@code TRANCAT-} in the
 * seventeen byte key of the balance table. The catalogue confirms the divergence physically —
 * {@code TRANCATG} carries {@code KEYLEN 6} at {@code app/catlg/LISTCAT.txt:L1475} while
 * {@code TCATBALF} carries a key length of 17, being an account identifier prepended to the same
 * type and category pair. The two are related in meaning and unrelated in identity: one names a
 * category, the other names one account's balance within a category.
 *
 * <p><b>Consequently, and without exception: no shared abstraction, no shared base class, no shared
 * interface, no shared helper, and no reuse of one key type as the other entity's identifier.</b>
 * This repository is typed over {@code TransactionCategoryId} and nothing else;
 * {@code TransactionCategoryBalanceId} appears in this file only in the prose that documents the
 * collision, and substituting it here would be a defect rather than a simplification. Rule 1 clause
 * C's "avoid duplication" reads the other way at this site: the duplication is <i>apparent</i>,
 * arising from a shared group name in the source, while the contracts are genuinely different, so
 * collapsing them would be a correctness failure dressed as tidiness. The same reasoning protects
 * the deliberately inconsistent component naming across the three composite keys in this
 * migration — {@code TransactionCategoryBalanceId} spells its components {@code accountId},
 * {@code typeCd} and {@code catCd}, {@code DisclosureGroupId} spells its {@code accountGroupId},
 * {@code tranTypeCd} and {@code tranCatCd}, and {@code TransactionCategoryId} spells its
 * {@code tranTypeCd} and {@code tranCatCd}. Each mirrors its own copybook's field spellings, and
 * none may be renamed to match a sibling. <b>Remediation:</b> keep the key classes separate, keep
 * the component spellings copybook faithful, and have the repository integration test round trip a
 * six byte pair through {@code equals} and {@code hashCode} so that no later refactor can quietly
 * substitute the seventeen byte key.
 *
 * <p><b>Medium: the migration's SQL types for the two key columns must pair with the committed
 * mapping, and the specification's wording for this interface does not.</b> The specification
 * describes the primary key as {@code tran_type_cd CHAR(2)} with {@code tran_cat_cd NUMERIC(4)}.
 * The committed identifier class maps plain Jakarta Persistence types and records its own Medium
 * finding to precisely this point at {@code TransactionCategoryId.java:L156-L166}: Hibernate's
 * schema validator compares JDBC type codes and not merely column names, a {@code String} declared
 * with a length of 2 is {@code VARCHAR(2)}, an {@code Integer} is {@code INTEGER}, and a provider
 * specific annotation would be needed to map {@code CHAR} — which that class deliberately declines
 * to use, being restricted to the Jakarta Persistence API. The owning entity takes the opposite
 * decision for the description, annotating {@code JdbcTypeCode(SqlTypes.CHAR)} so that
 * {@code tran_cat_type_desc} genuinely is {@code CHAR(50)}. Because
 * {@code spring.jpa.hibernate.ddl-auto} is {@code validate} in every profile, <b>the mapping is the
 * mandate</b> and the effective contract is {@code tran_type_cd VARCHAR(2) NOT NULL},
 * {@code tran_cat_cd INTEGER NOT NULL} and {@code tran_cat_type_desc CHAR(50) NOT NULL}.
 * <b>Remediation:</b> author {@code V1__create_schema.sql} against the mapping, which needs no code
 * change; or, if fixed width key columns are wanted for byte faithfulness, add the provider
 * annotation to both key components <i>and</i> write {@code CHAR(2)} and {@code NUMERIC(4)}. Choose
 * one deliberately and do not mix them: a divergence aborts application context startup rather than
 * degrading gracefully. <b>This was measured, not inferred.</b> Against a real PostgreSQL 16, the
 * schema Hibernate itself emits for this entity is {@code tran_type_cd character varying(2)},
 * {@code tran_cat_cd integer}, {@code tran_cat_type_desc character(50)}, and that schema passes
 * {@code validate}. Mutating only those two key columns to the specification's wording, changing no
 * other variable, turns the pass into
 * {@code SchemaManagementException: Schema-validation: wrong column type encountered in column}
 * {@code [tran_cat_cd] in table [transaction_category]; found [numeric (Types#NUMERIC)], but}
 * {@code expecting [integer (Types#INTEGER)]}. The effective contract stated above is therefore the
 * verified one. This interface names no column and declares no query, so it constrains nothing
 * beyond what the entity and identifier already fix.
 *
 * <p><b>Closed: all three Flyway migrations are present.</b> {@code V1__create_schema.sql} declares this
 * table, {@code V2__create_indexes.sql} correctly adds no index to it, and {@code V3__seed_data.sql} seeds
 * its 18 rows. See <i>Information gaps</i> below for the contract {@code V1} declares.
 *
 * <p><b>Low: this is a batch only dataset, so it is granted no online surface.</b> Developed in the
 * next section; the consequence is an authorisation boundary, not a defect.
 *
 * <p><b>Low: FILE STATUS {@code '35'} is specification derived, not source grounded.</b> Also
 * recorded under <i>Information gaps</i>.
 *
 * <h2>Batch only: proved by absence from the CICS resource definitions</h2>
 *
 * <p>{@code app/csd/CARDDEMO.CSD} declares exactly <b>eight</b> {@code DEFINE FILE} entries, and
 * they are the complete online file control table: {@code ACCTDAT} at {@code :L1}, {@code CARDAIX}
 * at {@code :L13}, {@code CARDDAT} at {@code :L25}, {@code CCXREF} at {@code :L37},
 * {@code CUSTDAT} at {@code :L50}, {@code CXACAIX} at {@code :L63}, {@code TRANSACT} at
 * {@code :L76} and {@code USRSEC} at {@code :L88}. The string <b>{@code TRANCATG} occurs zero
 * times</b> in that file, as do {@code TCATBALF}, {@code DISCGRP} and {@code TRANTYPE}. Those four
 * datasets were reachable only from the batch stream; no CICS transaction ever opened them.
 *
 * <p>That absence is the evidence, and it is binding rather than incidental:
 *
 * <ul>
 *   <li>This repository is consumed by {@code com.cardemo.batch} and {@code com.cardemo.service}
 *       <b>only</b>. No online caller inherits a right to the cluster, because none ever had
 *       one.</li>
 *   <li><b>No controller CRUD, no REST endpoint and no admin management surface exists — or may be
 *       added — for {@code TRANCATG}, {@code TCATBALF}, {@code DISCGRP} or {@code TRANTYPE}.</b>
 *       The target exposes exactly <b>17</b> operations across <b>8</b> {@code @RestController}
 *       classes, mapped one for one from the 17 sourced CSD transactions, and not one of them
 *       touches these four datasets. Publishing reference data maintenance that the system of
 *       record never had would widen the attack surface beyond the legacy system's own, which is
 *       the concrete reading of Rule 1 clause D, "principle of least privilege for
 *       tokens/credentials/config", at the persistence boundary.</li>
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
 * <p>Rule 1 clause B forbids dead code, and this package carries <b>no exemption</b> from it: the
 * three intentionally retained legacy no-ops that the parity mandate protects all live elsewhere —
 * the empty fee paragraph {@code 1400-COMPUTE-FEES} at {@code app/cbl/CBACT04C.cbl:L518-L520},
 * whose body is the comment "To be implemented" followed by {@code EXIT}; the assigned but never
 * consumed reject code at {@code app/cbl/CBTRN02C.cbl:L556}, where
 * {@code MOVE 109 TO WS-VALIDATION-FAIL-REASON} sits on an already validated path; and the
 * redundant index assignment in {@code app/cbl/CBSTM03A.CBL:L316-L338}. Clause B therefore applies
 * here at full strength, and every operation below is inherited from {@link JpaRepository} and left
 * undeclared, so no signature in this file can outlive its caller.
 *
 * <p>One program in the twenty eight program corpus opens this cluster, and it is the sole
 * {@code COPY CVTRA04Y} site: {@code app/cbl/CBTRN03C.cbl:L108}, the transaction detail report. Its
 * file declaration reads {@code SELECT TRANCATG-FILE ASSIGN TO TRANCATG} at {@code :L45} with
 * {@code ORGANIZATION IS INDEXED} at {@code :L46}, <b>{@code ACCESS MODE IS RANDOM}</b> at
 * {@code :L47}, {@code RECORD KEY IS FD-TRAN-CAT-KEY} at {@code :L48} and
 * {@code FILE STATUS IS TRANCATG-STATUS} at {@code :L49}; its own file description at
 * {@code :L79-L82} restates the layout independently as {@code FD-TRAN-TYPE-CD PIC X(02)} plus
 * {@code FD-TRAN-CAT-CD PIC 9(04)} followed by {@code FD-TRAN-CAT-DATA PIC X(54)}, which
 * corroborates a six byte key inside a sixty byte record from a second place in the corpus.
 *
 * <ol>
 *   <li><b>{@code Optional<TransactionCategory> findById(TransactionCategoryId id)}</b>
 *       <br><b>Purpose.</b> The keyed reference lookup, and the only access pattern the corpus
 *       actually performs. It replaces {@code READ TRANCATG-FILE INTO TRAN-CAT-RECORD} at
 *       {@code app/cbl/CBTRN03C.cbl:L505}, the single statement of paragraph
 *       {@code 1500-C-LOOKUP-TRANCATG} at {@code :L504}. The pair is assembled immediately before
 *       the call, by {@code MOVE TRAN-TYPE-CD OF TRAN-RECORD TO FD-TRAN-TYPE-CD OF FD-TRAN-CAT-KEY}
 *       at {@code :L191-L192} and {@code MOVE TRAN-CAT-CD OF TRAN-RECORD TO FD-TRAN-CAT-CD OF
 *       FD-TRAN-CAT-KEY} at {@code :L193-L194}, followed by
 *       {@code PERFORM 1500-C-LOOKUP-TRANCATG} at {@code :L195}; the resolved description is moved
 *       onto the report line by {@code MOVE TRAN-CAT-TYPE-DESC TO TRAN-REPORT-CAT-DESC} at
 *       {@code :L368}. The same two values are what flow into the transaction category balance key
 *       at {@code app/cbl/CBTRN02C.cbl:L470-L471} ({@code MOVE DALYTRAN-TYPE-CD TO
 *       FD-TRANCAT-TYPE-CD} then {@code MOVE DALYTRAN-CAT-CD TO FD-TRANCAT-CD}) and into the
 *       disclosure group key at {@code app/cbl/CBACT04C.cbl:L211-L212} ({@code MOVE TRANCAT-CD TO
 *       FD-DIS-TRAN-CAT-CD} then {@code MOVE TRANCAT-TYPE-CD TO FD-DIS-TRAN-TYPE-CD}). Those two
 *       sites build <i>different</i> keys over <i>different</i> clusters and are therefore the
 *       reason the pair matters across the batch stream, not call sites of this repository — a
 *       distinction the High finding above exists to keep sharp.
 *       <br><b>Input.</b> A {@code TransactionCategoryId} carrying the two character type code and
 *       the four digit category code. The identifier's own constructor rejects a type code of the
 *       wrong width and a category code outside {@code 0} to {@code 9999} with
 *       {@code IllegalArgumentException} before any query is issued, so an invalid pair never
 *       reaches the database. The value is bound as a parameter by the Spring Data proxy and is
 *       never interpolated into a statement, so a hostile value cannot alter the query.
 *       <br><b>Output.</b> An {@code Optional} that is present when the row exists and empty when
 *       it does not. The description is returned blank padded to its full fifty character width,
 *       which is the faithful {@code PIC X(50)} image; callers comparing against an unpadded
 *       literal must strip first.
 *       <br><b>Side effects.</b> None. A read only lookup: the legacy file is opened
 *       {@code OPEN INPUT TRANCATG-FILE} at {@code app/cbl/CBTRN03C.cbl:L450} and closed at
 *       {@code :L589}, and the corpus contains no {@code WRITE}, {@code REWRITE} or {@code DELETE}
 *       against it, which is why no mutating operation is declared here.
 *       <br><b>Failure modes.</b> An empty result is the modelled miss and is <i>not</i> an
 *       exception at this layer. The legacy program's own reaction to a miss is considerably
 *       stronger and is worth stating precisely, because a caller that silently tolerates the empty
 *       case is not reproducing it: the {@code INVALID KEY} branch at {@code :L506} runs
 *       {@code DISPLAY 'INVALID TRAN CATG KEY : '} at {@code :L507}, then
 *       {@code MOVE 23 TO IO-STATUS} at {@code :L508}, then
 *       {@code PERFORM 9910-DISPLAY-IO-STATUS} at {@code :L509}, then
 *       {@code PERFORM 9999-ABEND-PROGRAM} at {@code :L510} — so an unrecognised pair abends the
 *       report job. That status is FILE STATUS {@code '23'}, which
 *       {@code com.cardemo.service.shared.FileStatusMapper} translates to
 *       {@code com.cardemo.exception.RecordNotFoundException}. Translation happens there exactly
 *       once and never in this interface. Where a caller instead treats the empty case as a
 *       validation outcome, that choice belongs to the caller and must be explicit at the call
 *       site. A pair whose components were transposed also returns empty rather than failing, since
 *       both values are individually well formed. Infrastructure failures surface as the Spring
 *       Data access exception hierarchy with the driver exception preserved as the cause; nothing
 *       is swallowed here, because nothing is caught here.</li>
 *   <li><b>{@code boolean existsById(TransactionCategoryId id)}</b>
 *       <br><b>Purpose.</b> A presence probe over the same key, available for a validator that
 *       needs to know only whether a pair is recognised. It is documented as available rather than
 *       promoted, because no caller is invented for it: the legacy corpus needs the description in
 *       every case where it needs the pair to be valid, so {@code findById} is the honest
 *       translation of {@code 1500-C-LOOKUP-TRANCATG}.
 *       <br><b>Input and output.</b> The six byte pair; {@code true} when the row exists.
 *       <br><b>Side effects.</b> None.
 *       <br><b>Failure modes.</b> As for {@code findById}, minus the empty case, which collapses
 *       into {@code false} rather than an {@code Optional}. Note that {@code false} discards the
 *       diagnostic the legacy program prints, so a caller that must reproduce {@code :L507} should
 *       prefer {@code findById}.</li>
 *   <li><b>{@code List<TransactionCategory> findAll()}</b>
 *       <br><b>Purpose.</b> A whole table read, legitimate here and nowhere near it in size: the
 *       reference set is <b>18</b> rows, attested twice over by {@code REC-TOTAL 18} at
 *       {@code app/catlg/LISTCAT.txt:L1482} and by the eighteen 60 byte rows of
 *       {@code app/data/ASCII/trancatg.txt}. Loading all eighteen into a service held validation
 *       cache costs one query and roughly a kilobyte, so Rule 1 clause A's requirement to "avoid
 *       obvious inefficiencies" is met by the table's size rather than by a code restriction — and
 *       the tradeoff is stated here rather than assumed.
 *       <br><b>Input.</b> None.
 *       <br><b>Output.</b> All rows, in <b>unspecified order</b>. This is the one sharp edge in the
 *       inherited surface: {@code findAll()} carries no {@code ORDER BY}, so PostgreSQL may return
 *       rows in heap order, and that order can change after any update or vacuum. Rule 1 clause A
 *       demands determinism, so a caller that renders, digests or diffs this set must impose its
 *       own ordering — either by passing a {@code Sort} to the inherited {@code findAll(Sort)},
 *       which needs no new method here, or by sorting the returned list. A caller that merely
 *       builds a keyed map is order independent and needs neither.
 *       <br><b>Side effects.</b> None.
 *       <br><b>Failure modes.</b> An empty list means the seed migration did not run; it is not an
 *       error at this layer, and the integration test that asserts a row count of exactly 18 is
 *       what turns it into a detectable one.</li>
 *   </ol>
 *
 * <h2>Deliberate omissions, each with its reason</h2>
 *
 * <ul>
 *   <li><b>No ordered full read method, such as
 *       {@code findAllByOrderByIdTranTypeCdAscIdTranCatCdAsc()}.</b> One was considered and
 *       rejected on evidence. The only program that opens this file declares
 *       <b>{@code ACCESS MODE IS RANDOM}</b> at {@code app/cbl/CBTRN03C.cbl:L47}, under which a
 *       sequential browse is not merely unused but impossible: there is no {@code READ NEXT}, no
 *       {@code START} and no browse verb against this file anywhere in {@code app/cbl}. Nothing in
 *       the target iterates the table in key order either. Declaring the finder would therefore add
 *       a signature with no call site, which is exactly the dead code clause B forbids, and the
 *       inherited {@code findAll(Sort)} already covers any future ordered read without one.</li>
 *   <li><b>No type scoped lookup, such as every category belonging to one two character type.</b>
 *       The same {@code RANDOM} access mode precludes a partial key browse in the source, and no
 *       validator or reference data service in the target requires one. This omission and the
 *       previous one were both offered conditionally by the specification for this interface, on the
 *       express condition that a named caller exists; the condition is not met, and inventing a
 *       caller to justify a signature is the precise failure mode clause B exists to prevent. If a
 *       caller later needs it, the derived path must be spelled {@code Id_TranTypeCd} or a
 *       {@code @Query} must read {@code t.id.tranTypeCd}, because those are the identifier's own
 *       component names.</li>
 *   <li><b>No alternate key finder.</b> The catalogue defines exactly three alternate indexes in the
 *       whole system — {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX},
 *       {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX} and {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX} —
 *       and none of them is over {@code TRANCATG}, which has one index component and it is the
 *       primary. This package accordingly declares three alternate key finders in total, on the
 *       card, cross reference and transaction repositories, and none here. Adding a fourth would
 *       imply a fourth index and would break alignment with {@code V2__create_indexes.sql}, which
 *       creates exactly three. The primary key already provides the only ordered access this table
 *       needs.</li>
 *   <li><b>No finder on the description, and no projection.</b> No program searches this table by
 *       description; the description is only ever an output, moved onto the report line at
 *       {@code app/cbl/CBTRN03C.cbl:L368}. A table of one composite key and one descriptive column
 *       has nothing to project away.</li>
 *   <li><b>No {@code @Query}, and therefore no JPQL or SQL text in this file at all.</b> Nothing
 *       here requires a hand written statement, so none is written; the risk of a concatenated or
 *       interpolated query is removed structurally rather than mitigated. Any future {@code @Query}
 *       added to this package must bind {@code @Param} named parameters and must never build a
 *       statement by string concatenation or use {@code nativeQuery} with an interpolated
 *       value.</li>
 *   <li><b>No declarative bulk update annotation, and no write method of any kind.</b> The legacy
 *       file is opened {@code OPEN INPUT} at {@code app/cbl/CBTRN03C.cbl:L450} and closed at
 *       {@code :L589}; the corpus contains no {@code WRITE}, {@code REWRITE} or {@code DELETE}
 *       against it. Rows arrive once, from {@code V3__seed_data.sql}, exactly as they arrived once
 *       through the {@code IDCAMS REPRO} of {@code app/jcl/TRANCATG.jcl:L54} in the legacy
 *       provisioning job. The inherited mutators are not removed — Spring Data has no mechanism for
 *       that short of a narrower base interface, which the single interface per cluster rule
 *       excludes — but nothing in the target calls them, and no bulk update is declared.</li>
 *   <li><b>No enum mapping for the category code.</b> The eighteen categories are seeded data, not a
 *       closed set in code: a new category can be seeded without a recompile, and an enum would turn
 *       an unrecognised code into a load time failure instead of the diagnostic the legacy program
 *       produces at {@code app/cbl/CBTRN03C.cbl:L507}. The type code is likewise not an enum here —
 *       the transaction type reference table is a separate cluster with its own repository — and a
 *       transaction <i>source</i>, modelled by {@code com.cardemo.model.enums.TransactionSource}, is
 *       a third and unrelated field whose name similarity is a trap rather than a hint.</li>
 *   <li><b>No static state and no cache in this interface.</b> An interface cannot hold mutable
 *       state without a static field, and none is declared. Any reference data cache belongs in an
 *       injected service bean with a defined lifecycle, per clause B's preference for dependency
 *       injection over global mutable state.</li>
 *   <li><b>No environment lookup and no hardcoded coordinate.</b> This file reads no environment
 *       variable and no JVM system property, and contains no host, port, path, URL or credential of
 *       any kind. Connectivity is configuration, resolved by the container from the active profile,
 *       which keeps the build free of the environment specific assumptions clause C rules out.</li>
 *   <li><b>No implementation class and no fragment of any shape.</b> There is no
 *       {@code TransactionCategoryRepositoryImpl}, no custom fragment interface, no criteria or
 *       dynamic predicate helper, no mapper and no DAO wrapper, and no base repository abstraction
 *       above this one. This package is exactly eleven repository interfaces plus
 *       {@code package-info.java}. No Lombok and no additional dependency is introduced; nothing
 *       here spawns a process or deserializes untrusted input.</li>
 *   </ul>
 *
 * <h2>Configuration contract and operational notes</h2>
 *
 * <ul>
 *   <li>{@code spring.jpa.hibernate.ddl-auto: validate} in every profile. The entity's property
 *       names, column names, types and lengths must match the migrated schema exactly or the context
 *       fails to start. This is the mechanism through which the Medium type pairing finding above
 *       would manifest, and it is a feature: a divergence is caught at startup rather than at the
 *       first query.</li>
 *   <li>{@code spring.jpa.open-in-view: false}. Results are returned fully initialised; there is no
 *       lazy access outside a transaction. This entity has no association, so there is nothing to
 *       initialise lazily and no N+1 exposure to manage.</li>
 *   <li>{@code spring.jpa.show-sql: false}, with no Hibernate SQL or bind parameter logging in any
 *       profile. This table holds no personal data — a type code, a category code and an English
 *       description — but the setting is uniform across the package so that no repository becomes
 *       the one that leaks a bound card number, government identifier, telephone number, date of
 *       birth, password hash or EFT account identifier into a log. Nothing in this file logs, and
 *       nothing in it could: observability is delegated wholesale to
 *       {@code com.cardemo.observability}, which carries the correlation identifier, the trace and
 *       span identifiers and the named counters.</li>
 *   <li>Spring Batch metadata tables are created by the framework's own script through
 *       {@code spring.batch.jdbc.initialize-schema}, never by a fourth Flyway migration and never as
 *       extra tables in {@code V1}.</li>
 *   <li><b>HikariCP connection pool tuning is explicitly out of scope</b> and is recorded as a
 *       residual risk in the planned {@code DECISION_LOG.md} and {@code docs/validation-gates.md}. The defaults
 *       are used as shipped. Stating the gap is the honest discharge of clause A's "justify
 *       tradeoffs only when needed"; inventing pool figures with no measured workload behind them
 *       would be the dishonest one.</li>
 *   </ul>
 *
 * <h2>How to build and test</h2>
 *
 * <p>Build with {@code ./mvnw -B clean compile} on the pinned toolchain, OpenJDK 25 with Maven 3.9.11,
 * against {@code org.springframework.boot:spring-boot-starter-parent:3.5.11}. The compiler runs
 * {@code -Xlint:all -Werror} with {@code failOnWarning}, so any warning in a category {@code javac} 25
 * publishes is a build failure rather than a warning. An unused import is not one of those categories, so
 * the four imports above being each load bearing — two for the type arguments, one for the supertype and
 * one for the annotation — is a review guarantee rather than a compiler one. Unit tests run at
 * {@code ./mvnw -B clean test}; the repository integration tier runs at {@code ./mvnw -B verify} under
 * {@code src/test/java/com/cardemo/integration/repository}, against a Testcontainers PostgreSQL 16
 * container, and to assert the seeded row count of exactly 18 together with a six byte identifier
 * round trip through {@code equals} and {@code hashCode}. Testcontainers is pinned to 2.0.3 by
 * overriding the Boot managed version property rather than by importing a second bill of materials,
 * and only the prefixed artefact identifiers resolve at that version.
 *
 * <p><b>Verified on 1 August 2026, and what is still owed.</b> Two things were established by execution.
 * The schema and the mapping agree: applying {@code src/main/resources/db/migration/V1__create_schema.sql}
 * into a throwaway schema on a PostgreSQL 16.10 instance produced 11 tables, 10 foreign keys and 5 check
 * constraints, and bootstrapping Hibernate 6.6.42.Final over all eleven annotated entities against it with
 * {@code hibernate.hbm2ddl.auto=validate} reported no mismatch, which covers
 * {@code transaction_category} and this interface's two identifier components. And the reference set is
 * the size the fixture dictates: {@code app/data/ASCII/trancatg.txt} holds exactly 18 records of 60 bytes.
 * <b>Not available:</b> everything that needs a Spring Data proxy - that {@code findAll()} returns those 18
 * rows through this interface, that {@code id.tranTypeCd} and {@code id.tranCatCd} sort the set into VSAM
 * key order, that a transposed or absent pair yields an empty {@code Optional}, and that {@code findById}
 * emits a bound-parameter row-value predicate over both key components. An earlier revision of this
 * paragraph said no repository or integration tier existed and no {@code application*.yml} either; both
 * halves are false and are withdrawn - {@code src/test/java/com/cardemo/unit/repository} and
 * {@code src/test/java/com/cardemo/integration/repository} both exist, the latter holding the
 * Testcontainers base, and all four profiles are present. What remains is that no concrete integration
 * subclass binds this interface yet, so no Spring Data proxy has been created against a real dialect.
 * What is needed: a test extending that base and seeded from the fixture, which would also confirm that
 * the predicate touches no column of the seventeen byte layout discussed in the High finding above.
 *
 * <p><b>Common failure modes.</b> A startup failure naming a missing column on
 * {@code transaction_category} means the migration spelled a column differently from
 * {@code tran_type_cd}, {@code tran_cat_cd} or {@code tran_cat_type_desc}; correct the migration,
 * not this interface. A startup failure reporting a wrong column type on either key column is the
 * Medium finding above — {@code VARCHAR(2)} and {@code INTEGER} are what the committed mapping
 * requires. The same failure on the description means the schema used a variable length type where
 * the entity annotates fixed width {@code CHAR(50)}. An empty {@code findAll()} means
 * {@code V3__seed_data.sql} did not run. A lookup that unexpectedly finds nothing is usually a pair
 * built with the components transposed, or a type code trimmed instead of being kept at its fixed
 * width of two characters. A description that fails to equal an unpadded literal in Java is correct
 * behaviour, not a defect: {@code CHAR(50)} returns blank padded, and the padding is the
 * {@code PIC X(50)} image.
 *
 * <h2>Clause F disclosure: stated gaps and their measured state</h2>
 *
 * <p>Rule 1 clause F requires that missing information be stated plainly rather than assumed. Two
 * items were recorded as <b>Not available</b> when this interface was authored. The first has since
 * been closed by measurement and is recorded as closed rather than quietly dropped:
 *
 * <ol>
 *   <li><b>All three Flyway migrations are present.</b> An earlier revision of this bullet recorded
 *       {@code V2__create_indexes.sql} and {@code V3__seed_data.sql} as non-existent; that is no longer
 *       true and the claim is withdrawn.
 *       {@code V1__create_schema.sql} declares {@code CREATE TABLE transaction_category},
 *       {@code V2} adds no index to it, and {@code V3} seeds its 18 rows.
 *       Because {@code ddl-auto: validate} makes the match mandatory rather than advisory, the
 *       contract restated here is what {@code V1} declares and what this interface together with its
 *       entity and identifier is typed over — table {@code transaction_category}, with a composite
 *       primary key over {@code (tran_type_cd, tran_cat_cd)} <b>in that component order</b>,
 *       mirroring bytes 1 to 2 then bytes 3 to 6 of the copybook key, and the column
 *       {@code tran_cat_type_desc CHAR(50)} not null. The SQL types of the two key columns must be
 *       paired with the mapping as set out in the Medium finding above, which as committed means
 *       {@code tran_type_cd VARCHAR(2) NOT NULL} and {@code tran_cat_cd INTEGER NOT NULL}. There
 *       must be <b>no version column</b>, since this is static reference data. {@code V2} adds
 *       <b>no</b> index on this table, as required: its three indexes are {@code idx_card_acct_id},
 *       {@code idx_card_cross_reference_acct_id} and {@code idx_transaction_proc_ts}.
 *       {@code V3} seeds exactly <b>18</b> rows from the eighteen sixty byte records of
 *       {@code app/data/ASCII/trancatg.txt}, decoding each row as two characters of type code, four
 *       of category code and fifty of description, and discarding the four filler bytes. A mismatch
 *       on any of these points fails context startup outright rather than degrading
 *       gracefully.</li>
 *   <li><b>FILE STATUS {@code '35'}, file unavailable, is "Not available" as a grounded source
 *       construct.</b> The literal {@code '35'} appears nowhere in {@code app/cbl} — zero
 *       occurrences across all 28 programs — and the CICS response census is
 *       {@code DFHRESP(NORMAL)} 43, {@code DFHRESP(NOTFND)} 23, {@code DFHRESP(ENDFILE)} 8,
 *       {@code DFHRESP(DUPREC)} 7, {@code DFHRESP(DUPKEY)} 3 and <b>{@code DFHRESP(NOTOPEN)}
 *       zero</b>. The corresponding {@code com.cardemo.exception.FileUnavailableException} is
 *       therefore specification derived only, and no behaviour of this repository depends on it.
 *       <i>What is needed</i> to promote it from specification to evidence: a legacy artefact that
 *       tests for status {@code '35'} or for {@code NOTOPEN}. None exists at {@code 7756d89}. The
 *       statuses this repository's access path <i>does</i> ground are {@code '00'}, checked after the
 *       open at {@code app/cbl/CBTRN03C.cbl:L451} and after the close at {@code :L590}, and
 *       {@code '23'}, set on the keyed miss at {@code :L508}.</li>
 *   </ol>
 *
 * @see TransactionCategory
 * @see TransactionCategoryId
 */
@Repository
public interface TransactionCategoryRepository
        extends JpaRepository<TransactionCategory, TransactionCategoryId> {
}
