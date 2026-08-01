/*
 * ****************************************************************************
 * Program     : TransactionRepository.java
 * Application : CardDemo
 * Type        : Spring Data JPA Repository Interface
 * Function    : Replaces the VSAM access verbs over the posted transaction
 *               cluster, including the descending maximum-key identifier
 *               generation idiom (race preserved), the lexical processing-
 *               timestamp finder that replaces the alternate index, and the
 *               ten-row forward/backward browse of the online transaction list.
 * Source      : CICS FILE TRANSACT (app/csd/CARDDEMO.CSD:L76-L77, base cluster
 *               only - the alternate-index PATH has NO CICS definition);
 *               AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS key 16 / reclen 350
 *               (app/catlg/LISTCAT.txt:L3593 DATA-component attribute line;
 *               app/jcl/TRANFILE.jcl:L49, L53-L54 KEYS(16 0)
 *               RECORDSIZE(350 350)); TRANSACT.VSAM.AIX KEYLEN 26 RKP 5
 *               AXRKP 304 NONUNIQKEY (app/catlg/LISTCAT.txt:L3674-L3678),
 *               defined by app/jcl/TRANFILE.jcl:L82-L84 KEYS(26 304) and
 *               app/jcl/TRANIDX.jcl:L25-L27 KEYS(26 304); record layout
 *               app/cpy/CVTRA05Y.cpy:L4-L18 @ 7756d89
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

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.cardemo.model.entity.Transaction;

/**
 * Persistence access to the posted transaction master.
 *
 * <h2>What it does</h2>
 * Replaces the VSAM access verbs issued against the keyed cluster
 * {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS} - {@code READ}, {@code WRITE}, {@code STARTBR},
 * {@code READNEXT}, {@code READPREV} and {@code ENDBR} - with a Spring Data JPA repository over
 * {@link Transaction}. Four access paths existed in the legacy corpus and all four are reproduced here:
 *
 * <ol>
 *   <li>a keyed read by transaction identifier, served by the inherited {@code findById};</li>
 *   <li>a keyed write, served by the inherited {@code save};</li>
 *   <li>a descending browse of the maximum key, used to generate the next identifier, served by
 *       {@link #findFirstByOrderByTransactionIdDesc()};</li>
 *   <li>a forward and backward browse of the keyed cluster, served by the three
 *       {@code Slice}-returning methods below, plus a processing-date range scan standing in for the
 *       alternate index, served by
 *       {@link #findByProcessingDateRangeOrderByCardNumberAsc(String, String)}.</li>
 * </ol>
 *
 * <p>Everything else stays out. This interface parses no text, generates no timestamp, performs no
 * balance arithmetic, computes no control break, emits no fixed-width record, declares no transaction
 * boundary and writes no log line. Those concerns belong to the service and batch layers, and the
 * sections below say precisely where each one lives so that nobody is tempted to pull it in here.
 *
 * <h2>The physical contract, dual-sourced</h2>
 * Every figure below was read from the frozen corpus rather than inferred, and each is corroborated by
 * a second, independent source so that a single mistranscription cannot propagate.
 *
 * <h3>Base cluster - key 16, record length 350</h3>
 * <ul>
 *   <li>{@code app/catlg/LISTCAT.txt:L3555} opens the cluster block for
 *       {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}. The figures themselves come from
 *       <strong>{@code :L3593}, which is the DATA-component attribute line</strong> - not the cluster
 *       header line - and reads {@code KEYLEN 16} with {@code AVGLRECL 350}. The adjacent
 *       {@code :L3594} adds {@code RKP 0} and {@code MAXLRECL 350}: the key begins at the very start of
 *       the record, and because the average and maximum record lengths agree the record is fixed width
 *       rather than merely averaging 350.</li>
 *   <li>{@code app/jcl/TRANFILE.jcl} corroborates it in IDCAMS: {@code DEFINE CLUSTER} at {@code :L49},
 *       {@code KEYS(16 0)} at {@code :L53}, {@code RECORDSIZE(350 350)} at {@code :L54} and
 *       {@code INDEXED} at {@code :L57}.</li>
 * </ul>
 *
 * <p>A 16-byte key at relative byte position zero can only be {@code TRAN-ID PIC X(16)}, declared at
 * {@code app/cpy/CVTRA05Y.cpy:L5} as the first field of {@code 01 TRAN-RECORD.} at {@code :L4}. That is
 * why the identifier type argument of this repository is {@code String}: the values are zero-padded
 * sixteen-character text such as {@code "0000000000000001"}, and a numeric key would discard the
 * padding that makes the descending browse of section
 * {@link #findFirstByOrderByTransactionIdDesc()} order correctly.
 *
 * <h3>Alternate index - KEYLEN 26 at AXRKP 304, NONUNIQKEY</h3>
 * <ul>
 *   <li>{@code app/catlg/LISTCAT.txt:L3672} names the alternate index
 *       {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX}, with its matching PATH block at {@code :L3663}. The
 *       figures come from <strong>{@code :L3674}, which is likewise the DATA-component attribute
 *       line</strong>, reading {@code KEYLEN 26}; then {@code :L3675} reports {@code RKP 5},
 *       {@code :L3676} reports {@code AXRKP 304}, and {@code :L3678} declares the index
 *       {@code SPANNED  NONUNIQKEY}.</li>
 *   <li>IDCAMS corroborates it <strong>twice</strong>: {@code app/jcl/TRANFILE.jcl:L82-L84} declares
 *       {@code KEYS(26 304)} with its {@code DEFINE PATH} at {@code :L99-L101}, and
 *       {@code app/jcl/TRANIDX.jcl:L25-L27} declares the same {@code KEYS(26 304)} with its
 *       {@code DEFINE PATH} at {@code :L42-L44}. Both members also spell the non-uniqueness out as
 *       {@code NONUNIQUEKEY}, at {@code TRANFILE.jcl:L85} and {@code TRANIDX.jcl:L28}.</li>
 * </ul>
 *
 * <h3>Medium - discrepancy #7: TRANIDX.jcl belongs to this repository, not to the card repository</h3>
 * <strong>Severity Medium.</strong> The transformation plan's file-by-file table maps
 * {@code app/jcl/TRANIDX.jcl} to the card repository. That mapping is wrong.
 * {@code app/jcl/TRANIDX.jcl} defines the <em>transaction</em> alternate index: its
 * {@code DEFINE ALTERNATEINDEX} at {@code :L25} names {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX},
 * {@code RELATE} at {@code :L26} names {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}, {@code :L27} declares
 * {@code KEYS(26 304)}, and the {@code BLDINDEX} at {@code :L52-L54} builds it from the transaction
 * cluster. The job's own title at {@code :L1} reads
 * {@code 'Define AIX on Transaction Master'} and its step comment at {@code :L20} reads
 * {@code CREATE ALTERNATE INDEX ON PROCESSED TIMESTAMP}. The card alternate index is a different
 * member entirely: {@code app/jcl/CARDFILE.jcl:L83-L85} declares {@code KEYS(11 16)} with its PATH at
 * {@code :L100-L102}.
 *
 * <p><strong>Remediation, applied here:</strong> this file cites <em>both</em>
 * {@code app/jcl/TRANFILE.jcl} and {@code app/jcl/TRANIDX.jcl} as the IDCAMS provisioning sources for
 * its alternate index, in the banner above and on
 * {@link #findByProcessingDateRangeOrderByCardNumberAsc(String, String)} below. Correspondingly the card
 * repository must cite {@code app/jcl/CARDFILE.jcl} and must <em>not</em> cite
 * {@code app/jcl/TRANIDX.jcl}. Recorded as Medium in {@code DECISION_LOG.md}.
 *
 * <h3>Medium - discrepancy #9: AXRKP is zero-based, DFSORT offsets are one-based</h3>
 * <strong>Severity Medium.</strong> The folder requirements mix zero-based {@code AXRKP} values with
 * one-based "record byte" prose, which invites an off-by-one. This file therefore states the convention
 * explicitly for every offset it cites, and does so once, here:
 *
 * <pre>
 *   AXRKP 304   zero-based displacement   app/catlg/LISTCAT.txt:L3676
 *             = record byte 305           one-based, inclusive
 *             = the first byte of TRAN-PROC-TS X(26), bytes 305-330
 * </pre>
 *
 * The two figures differ by exactly one because they count from different origins, and both are
 * consistent: {@code KEYLEN 26} at {@code :L3674} matches the width of {@code TRAN-PROC-TS} exactly, and
 * the DFSORT symbol {@code TRAN-PROC-DT,305,10,CH} at {@code app/proc/TRANREPT.prc:L40} uses one-based
 * numbering and lands on the same byte. Recorded as Medium in {@code DECISION_LOG.md}.
 *
 * <p>The identification is unambiguous. Only two 26-byte fields exist in the record -
 * {@code TRAN-ORIG-TS} at bytes 279-304 and {@code TRAN-PROC-TS} at bytes 305-330, declared at
 * {@code app/cpy/CVTRA05Y.cpy:L16-L17} - and byte 305 is the first byte of the second one. The alternate
 * key is the processing timestamp.
 *
 * <h3>The offset map this repository is contracted against</h3>
 * Byte positions are one-based and inclusive, exactly as DFSORT expresses them. The map is the
 * entity's normative contract; it is reproduced here only for the three fields this interface names in a
 * query.
 *
 * <pre>
 *   COBOL field     Line  PIC        Bytes    Java property   Column          SQL type
 *   TRAN-ID         :L5   X(16)       1-16    transactionId   tran_id         CHAR(16)
 *   TRAN-AMT        :L10  S9(09)V99  133-143  amount          tran_amt        NUMERIC(11,2)
 *   TRAN-CARD-NUM   :L15  X(16)      263-278  cardNumber      tran_card_num   CHAR(16)
 *   TRAN-ORIG-TS    :L16  X(26)      279-304  origTs          tran_orig_ts    CHAR(26)
 *   TRAN-PROC-TS    :L17  X(26)      305-330  procTs          tran_proc_ts    CHAR(26)
 * </pre>
 *
 * Two independent DFSORT symbol sets corroborate the two offsets that matter to the queries below:
 * {@code app/proc/TRANREPT.prc:L39} declares {@code TRAN-CARD-NUM,263,16,ZD} and {@code :L40} declares
 * {@code TRAN-PROC-DT,305,10,CH}, while {@code app/jcl/CREASTMT.JCL:STEP010} sorts
 * {@code FIELDS=(263,16,CH,A,1,16,CH,A)}.
 *
 * <h2>Blocker - the amount is NUMERIC(11,2) and the tiers are never collapsed</h2>
 * <strong>Severity Blocker.</strong> {@code TRAN-AMT} is {@code PIC S9(09)V99} at
 * {@code app/cpy/CVTRA05Y.cpy:L10} - nine integer digits plus two decimals - so the column is
 * {@code NUMERIC(11,2)} and the Java type is {@code java.math.BigDecimal}. Three distinct precision
 * tiers exist across the model and collapsing them is a real and easy mistake:
 *
 * <pre>
 *   COBOL PIC     SQL type        Fields
 *   S9(10)V99     NUMERIC(12,2)   the five Account money and cycle fields
 *   S9(09)V99     NUMERIC(11,2)   TRAN-AMT (this table), DALYTRAN-AMT, TRAN-CAT-BAL
 *   S9(04)V99     NUMERIC(6,2)    DIS-INT-RATE
 * </pre>
 *
 * <p>This interface contains no IEEE-754 binary numeric type of any kind, in either precision, boxed or
 * unboxed, and it declares no aggregate over the amount because no caller needs one and because
 * an aggregate computed in SQL would bypass the rounding discipline the parity comparison depends on.
 * Any rounding applied to an amount read through this interface must use
 * {@code java.math.RoundingMode.HALF_EVEN}, and monetary equality must be tested with
 * {@code BigDecimal.compareTo} and never with {@code BigDecimal.equals}, which is scale-sensitive.
 *
 * <p><strong>Negative amounts are legitimate and must never be normalised.</strong>
 * {@code app/cbl/CBTRN02C.cbl} routes a non-negative amount to the account's cycle-credit accumulator
 * and a negative amount to the cycle-<em>debit</em> accumulator, which therefore legitimately holds
 * negative values - and that is precisely why the over-limit formula elsewhere subtracts it. No
 * absolute-value normalisation is permitted anywhere on this path, and none appears here.
 *
 * <h2>Blocker - the source column is a String, never the TransactionSource enum</h2>
 * <strong>Severity Blocker.</strong> {@code Transaction.transactionSource} is a plain {@code String}
 * over {@code CHAR(10)}, never the {@code TransactionSource} enum, and this interface neither imports
 * that enum nor accepts it as a parameter. Two source-grounded literals exist -
 * {@code 'System'} at {@code app/cbl/CBACT04C.cbl:L484}, six characters of mixed case stored padded as
 * {@code "System    "}, and {@code 'POS TERM'} at {@code app/cbl/COBIL00C.cbl:L222}, stored as
 * {@code "POS TERM  "} - but the column is not a closed set. {@code app/cbl/CBTRN02C.cbl} copies the
 * staged record's source through without examining it, so the combine job legitimately loads rows whose
 * source is {@code OPERATOR}, a value with no literal assignment site anywhere in the corpus. Narrowing
 * the column to an enumerated type would fail to load valid rows.
 *
 * <h2>Package invariants this interface upholds</h2>
 * <ul>
 *   <li><strong>Determinism.</strong> Every multi-row and paged method carries an explicit ordering,
 *       expressed either in the method name or in the {@code order by} clause of its query. There is no
 *       method here whose row order is left to the database.</li>
 *   <li><strong>Parameter binding only.</strong> Every query is a compile-time constant string with
 *       {@code @Param}-named bind parameters. There is no string concatenation, no native-SQL query
 *       mode and no interpolation, so no caller-supplied value can reach the query structure.</li>
 *   <li><strong>Exactly one alternate-key finder.</strong> This file declares one, and the package
 *       declares three in total: the card repository's account finder at {@code AXRKP} 16, the
 *       cross-reference repository's account finder at {@code AXRKP} 25, and the processing-timestamp
 *       finder here at {@code AXRKP} 304. That count is load-bearing: {@code V2__create_indexes.sql}
 *       creates exactly three non-unique B-tree indexes, so a fourth finder would break the alignment.
 *       <strong>No second index may be added for this table.</strong></li>
 *   <li><strong>No transaction boundary and no bulk mutation.</strong> There is no transaction-boundary
 *       annotation and no bulk-mutation annotation anywhere in this file. The legacy posting routine's
 *       three independent commits - the transaction-category-balance insert-or-update, the account
 *       update and the transaction write - become one declarative transactional unit at the service
 *       layer, scoped to roll back for any exception, which is the only place that can see all
 *       three.</li>
 *   <li><strong>No global mutable state.</strong> There is no static field of any kind. In particular
 *       the interest job's run-sequential identifier suffix, {@code WS-TRANID-SUFFIX} incremented at
 *       {@code app/cbl/CBACT04C.cbl:L474} and never reset per account, is COBOL working storage
 *       belonging to that job's step scope. A counter of that kind must never be hosted in a Spring
 *       bean, least of all a singleton repository proxy.</li>
 *   <li><strong>No card number is ever surfaced.</strong> No method here projects
 *       {@code tran_card_num} in isolation, and this file contains no logging statement at all. A
 *       primary account number must not reach a log line, and never emitting it is the primary
 *       defence rather than masking after the fact.</li>
 *   <li><strong>Status translation happens elsewhere, exactly once.</strong> The legacy
 *       {@code FILE STATUS} and {@code DFHRESP} taxonomy is translated in
 *       {@code com.cardemo.service.shared.FileStatusMapper}, not here. Exception types are referenced in
 *       this documentation as prose and are deliberately not imported, so that the repository layer
 *       depends on nothing but the model and the framework.</li>
 * </ul>
 *
 * <h2>How to build, run and test</h2>
 * <pre>
 *   mvn -B clean compile   compiles this interface under -Xlint:all -Werror on Java 25
 *   mvn -B clean test      runs the unit tier
 *   mvn -B clean verify    adds the coverage floor and the dependency vulnerability scan
 *   docker compose up -d   brings up PostgreSQL 16 so Flyway and schema validation have a target
 * </pre>
 *
 * The integration tier for this interface lives in
 * {@code src/test/java/com/cardemo/integration/repository} and runs against a Testcontainers-managed
 * PostgreSQL 16. It must cover: an empty table yielding an empty top-one lookup and therefore a first
 * identifier of {@code 1}; a populated table yielding the true maximum key; a duplicate insert
 * surfacing as a duplicate-record failure; the lexical range finder returning <em>several</em> rows for
 * one timestamp prefix; and the ascending and descending paged browses at a page size supplied from
 * configuration.
 *
 * <h2>Key configuration and defaults</h2>
 * <ul>
 *   <li>{@code spring.jpa.hibernate.ddl-auto: validate} in every profile. Every property name, column,
 *       type and precision asserted here must match the Flyway migrations exactly or the application
 *       context fails to start outright.</li>
 *   <li>{@code spring.jpa.open-in-view: false}. Results returned from this interface are fully
 *       initialised; there is no lazy state to touch outside a transaction, which is consistent with
 *       the entity declaring no associations.</li>
 *   <li>{@code spring.jpa.show-sql: false}, with no Hibernate SQL or bind-parameter logging in any
 *       profile - this table carries a card number.</li>
 *   <li>Page sizes are bound from {@code carddemo.pagination.*} configuration and arrive as a
 *       {@link Pageable}. <strong>No page size is hardcoded in this interface</strong>, in a signature,
 *       a default or an annotation.</li>
 *   <li>The Spring Batch {@code BATCH_*} metadata tables come from the framework's own script via
 *       {@code spring.batch.jdbc.initialize-schema}. They are never a fourth Flyway migration and never
 *       extra tables in {@code V1}.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 * <ul>
 *   <li><em>Context fails to start naming {@code tran_amt}</em> - the migration declared
 *       {@code NUMERIC(12,2)} by analogy with the account money fields. The correct tier for
 *       {@code S9(09)V99} is {@code NUMERIC(11,2)}.</li>
 *   <li><em>Context fails to start naming {@code transaction} as an unknown table</em> - the migration
 *       quoted the identifier. The entity does not quote it, so the migration must not either.</li>
 *   <li><em>Context fails to start reporting an unresolvable property on a query method</em> - a
 *       property was renamed on the entity. The names this interface depends on are exactly
 *       {@code transactionId}, {@code procTs} and {@code cardNumber}.</li>
 *   <li><em>A generated identifier collides</em> - that is the preserved race described on
 *       {@link #findFirstByOrderByTransactionIdDesc()} behaving as designed. It must surface as a
 *       duplicate-record failure, not be retried away.</li>
 *   <li><em>The range finder returns nothing for a date that exists</em> - the bounds were passed as
 *       something other than ten-character text, or a temporal type was converted to a string in a
 *       different format. The comparison is lexical over the first ten characters; see
 *       {@link #findByProcessingDateRangeOrderByCardNumberAsc(String, String)}.</li>
 *   <li><em>The range finder returns one row where several were expected</em> - the return type was
 *       narrowed to {@code Optional}. The alternate key is {@code NONUNIQKEY}.</li>
 *   <li><em>A page repeats its last row, or skips one</em> - an inclusive paged finder was used where
 *       an exclusive one was required, or the reverse. The three paged methods below are not
 *       interchangeable and each documents the source path it reproduces.</li>
 *   <li><em>Text comes back space-padded</em> - that is {@code CHAR(n)} behaving correctly. The columns
 *       are fixed width by design; trim at the point of comparison rather than widening the column.</li>
 * </ul>
 *
 * <h2>Not available - what could not be read when this interface was authored</h2>
 * Stated plainly rather than assumed, with what is needed in each case.
 *
 * <ul>
 *   <li><strong>Not available:</strong> {@code src/main/resources/db/migration/V1__create_schema.sql},
 *       {@code V2__create_indexes.sql} and {@code V3__seed_data.sql} did not exist when this interface
 *       was written. <em>What is needed:</em> those three Flyway migration files. Because
 *       {@code ddl-auto: validate} is set in every profile, the following constitute the
 *       <strong>normative contract the migrations must satisfy</strong>, and a mismatch fails context
 *       startup rather than degrading gracefully - the table name {@code transaction} (written
 *       {@code "transaction"} only where a quoted identifier is required in SQL), the primary key
 *       {@code tran_id CHAR(16)}, the columns {@code tran_amt NUMERIC(11,2)},
 *       {@code tran_card_num CHAR(16)}, {@code tran_orig_ts CHAR(26)} and
 *       {@code tran_proc_ts CHAR(26)}, the presence of a version column, the <strong>absence of any
 *       identity or sequence default on {@code tran_id}</strong>, and a <strong>non-unique</strong>
 *       B-tree index on {@code tran_proc_ts} in {@code V2} - one index for this table, not two.</li>
 *   <li><strong>Not available:</strong> {@code FILE STATUS '35'}, file unavailable, as a grounded source
 *       construct. No literal {@code '35'} exists anywhere in {@code app/cbl}, and the
 *       {@code DFHRESP} census across the corpus is {@code NORMAL} 43, {@code NOTFND} 23,
 *       {@code ENDFILE} 8, {@code DUPREC} 7, {@code DUPKEY} 3 and <strong>{@code NOTOPEN} 0</strong>.
 *       It is specification-derived only. <em>What is needed:</em> nothing further - the gap is
 *       disclosed rather than filled, and no code path here depends on it. The same applies to
 *       {@code FILE STATUS '22'}: there is <strong>zero</strong> literal {@code '22'} in
 *       {@code app/cbl}, so duplicate detection is grounded solely in {@code DFHRESP(DUPREC)} and
 *       {@code DFHRESP(DUPKEY)}, canonically at {@code app/cbl/COUSR01C.cbl:L260-L261}.</li>
 *   <li><strong>Not available:</strong> any service-level objective for this table. The legacy corpus
 *       publishes no throughput and no latency target anywhere, so the performance gate records a
 *       <em>measured baseline</em> and no threshold may be invented. <em>What is needed:</em> a
 *       stakeholder-supplied objective, if one is ever to be asserted.</li>
 * </ul>
 *
 * @see Transaction
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    /*
     * ------------------------------------------------------------------------------------------------
     * Inherited operations - documented here, deliberately NOT redeclared
     * ------------------------------------------------------------------------------------------------
     *
     * findById(String transactionId) -> Optional<Transaction>
     *     Replaces the keyed read. app/cbl/COTRN01C.cbl:L269-L273 issues
     *     EXEC CICS READ DATASET(WS-TRANSACT-FILE) ... RIDFLD(TRAN-ID), the single-record retrieval
     *     behind the online transaction-detail screen; the same verb repositions the browse of
     *     app/cbl/COTRN00C.cbl. Callers pass the zero-padded sixteen-character identifier.
     *     An absent row arrives as an empty Optional - never as an exception - which is what lets the
     *     service layer decide between the not-found path and the two accepted-status control paths
     *     that the legacy corpus treats as success. Redeclaring it would add nothing and would
     *     duplicate a contract JpaRepository already states.
     *
     * save(Transaction entity) -> Transaction
     *     Replaces the keyed write: EXEC CICS WRITE in app/cbl/COTRN02C.cbl (transaction add) and
     *     app/cbl/COBIL00C.cbl:L233 (bill payment), and the batch WRITE of
     *     app/cbl/CBTRN02C.cbl 2900-WRITE-TRANSACTION-FILE at :L562, performed from the posting
     *     routine at :L442.
     *     A primary-key collision surfaces as a duplicate-key violation, which the service layer
     *     translates to com.cardemo.exception.DuplicateRecordException preserving the root cause.
     *     That outcome is INTENDED, not defensive: it is the designed failure mode of the preserved
     *     identifier-generation race described on findFirstByOrderByTransactionIdDesc() below, and of
     *     a combine run replaying an interest date parameter. It must never be swallowed, retried
     *     away, or turned into a silent insert-or-update.
     * ------------------------------------------------------------------------------------------------
     */

    /**
     * Returns the single row bearing the highest transaction identifier, or an empty result when the
     * table is empty.
     *
     * <p>This is the replacement for the descending maximum-key browse that the legacy corpus uses to
     * generate the next transaction identifier. Two programs perform it identically.
     * {@code app/cbl/COTRN02C.cbl:L444-L451} reads:
     *
     * <pre>
     *   444:  MOVE HIGH-VALUES TO TRAN-ID
     *   445:  PERFORM STARTBR-TRANSACT-FILE
     *   446:  PERFORM READPREV-TRANSACT-FILE
     *   447:  PERFORM ENDBR-TRANSACT-FILE
     *   448:  MOVE TRAN-ID     TO WS-TRAN-ID-N
     *   449:  ADD 1 TO WS-TRAN-ID-N
     *   450:  INITIALIZE TRAN-RECORD
     *   451:  MOVE WS-TRAN-ID-N TO TRAN-ID
     * </pre>
     *
     * and {@code app/cbl/COBIL00C.cbl:L212-L219} repeats the same seven steps inside the bill-payment
     * path. Positioning the browse at {@code HIGH-VALUES} and reading <em>backwards</em> once yields the
     * last record in key order - the maximum key - which is exactly a top-one descending-ordered query.
     *
     * <h4>The empty-table boundary: zero, then plus one, so the first identifier is 1</h4>
     * The empty-table case is not an inference. {@code app/cbl/COBIL00C.cbl:L487-L488}, inside
     * {@code READPREV-TRANSACT-FILE} at {@code :L472}, is the definitive proof:
     *
     * <pre>
     *   485:  WHEN DFHRESP(NORMAL)
     *   486:      CONTINUE
     *   487:  WHEN DFHRESP(ENDFILE)
     *   488:      MOVE ZEROS TO TRAN-ID
     * </pre>
     *
     * An end-of-file response moves <em>zeros</em> into the identifier, and the caller then adds one
     * unconditionally at {@code :L217}. The full chain the calling service must reproduce is therefore:
     *
     * <pre>
     *   empty result  =&gt;  numeric value 0  =&gt;  +1  =&gt;  1  =&gt;  "0000000000000001"
     *   present row   =&gt;  parse its identifier  =&gt;  +1  =&gt;  zero-pad to sixteen characters
     * </pre>
     *
     * <p>The zero-padding is not cosmetic: the identifier column is {@code CHAR(16)} and the ordering
     * this method relies on is lexical, so an unpadded value would sort into the wrong place and the
     * next lookup would return the wrong maximum.
     *
     * <h4>The race is PRESERVED - deliberately, and it must not be engineered away</h4>
     * Read-then-increment-then-insert is inherently racy under concurrency: two callers can read the
     * same maximum and compute the same next identifier. <strong>The VSAM browse it replaces was racy
     * in exactly the same way, and preserving the race is the parity-preserving choice.</strong> The
     * collision is meant to surface: the primary-key constraint rejects the second insert, and the
     * service layer translates that to {@code com.cardemo.exception.DuplicateRecordException} with the
     * root cause preserved.
     *
     * <p>Consequently, and by design, this repository has <strong>no JPA generated-value annotation, no
     * sequence-generator annotation, no database sequence, no identity column, no retry loop, no
     * pessimistic or serialising lock, and no insert-or-update fallback</strong>. Each is a Blocker-level
     * violation here, not a stylistic preference. A sequence would change the generated values outright
     * and break the field-level comparison against the legacy baseline, which is the acceptance
     * contract; a retry would mask the very collision the source lets fail; a lock would change the
     * concurrency behaviour of a path whose behaviour is being reproduced. Recorded as a deliberate
     * decision in {@code DECISION_LOG.md}.
     *
     * <h4>Interaction with interest generation - source behaviour, not a defect</h4>
     * {@code app/cbl/CBACT04C.cbl} builds its identifiers differently. At {@code :L474} it increments
     * {@code WS-TRANID-SUFFIX}, a counter that is <strong>never reset per account</strong>, and at
     * {@code :L476-L479} it concatenates the ten-character date parameter with that six-digit suffix to
     * form a sixteen-digit identifier. Because the date leads, those identifiers are numerically large
     * and therefore <strong>dominate this descending lookup once an interest run has occurred</strong>:
     * subsequent online identifiers continue from the interest run's high-water mark rather than from
     * the previous online value. That is the source's behaviour and it must not be "fixed" - and the
     * never-reset counter itself must never be hosted in Java as mutable state on a bean.
     *
     * @return the row with the greatest {@code transactionId} in descending lexical order, limited to a
     *         single row; {@code Optional#empty()} when the table holds no rows, which the caller must
     *         treat as the numeric value zero so that the first generated identifier is {@code 1}
     * @implNote A derived query rather than an annotated one: Spring Data applies the {@code First}
     *         keyword as a query limit, whereas a {@code @Query} would ignore it and could return every
     *         row. Only {@code Transaction#getTransactionId()} is intended to be consumed from the
     *         result; nothing else is read from it and, in particular, nothing is logged from it.
     */
    Optional<Transaction> findFirstByOrderByTransactionIdDesc();

    /**
     * Returns every transaction whose processing timestamp begins with a date inside an inclusive
     * ten-character range, ordered by card number ascending.
     *
     * <p>This is <strong>the package's third and final alternate-key finder</strong>, and the only one
     * declared in this file. It replaces the alternate index
     * {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX}, whose key is {@code KEYLEN 26} at
     * <strong>{@code AXRKP 304} (zero-based) = record byte 305 (one-based)</strong> - the first byte of
     * {@code TRAN-PROC-TS X(26)}, which occupies bytes 305-330.
     *
     * <h4>Evidence</h4>
     * <ul>
     *   <li>{@code app/catlg/LISTCAT.txt:L3674-L3678} - the DATA-component attribute lines of the
     *       alternate index: {@code KEYLEN 26} at {@code :L3674}, {@code RKP 5} at {@code :L3675},
     *       {@code AXRKP 304} at {@code :L3676} and {@code SPANNED  NONUNIQKEY} at {@code :L3678}. The
     *       index itself is named at {@code :L3672} and its PATH block sits at {@code :L3663}.</li>
     *   <li>{@code app/jcl/TRANFILE.jcl:L82-L84} - IDCAMS {@code DEFINE ALTERNATEINDEX} with
     *       {@code KEYS(26 304)}, {@code NONUNIQUEKEY} at {@code :L85}, PATH at {@code :L99-L101}.</li>
     *   <li>{@code app/jcl/TRANIDX.jcl:L25-L27} - the same {@code KEYS(26 304)} declared a second time,
     *       {@code NONUNIQUEKEY} at {@code :L28}, PATH at {@code :L42-L44}. Citing this member here is
     *       the remediation for discrepancy #7; see the class documentation.</li>
     *   <li>{@code app/proc/TRANREPT.prc:STEP05R} - the consuming sort step, whose {@code SYMNAMES} at
     *       {@code :L40} declares {@code TRAN-PROC-DT,305,10,CH} and whose {@code SYSIN} at
     *       {@code :L44-L46} reads
     *       {@code SORT FIELDS=(TRAN-CARD-NUM,A)} followed by
     *       {@code INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,TRAN-PROC-DT,LE,PARM-END-DATE)}.</li>
     * </ul>
     *
     * <h4>Blocker - the comparison is LEXICAL over CHAR(26), never temporal</h4>
     * <strong>Severity Blocker.</strong> {@code TRAN-PROC-TS} is {@code PIC X(26)} at
     * {@code app/cpy/CVTRA05Y.cpy:L17} - character data. The property is a {@code String}, the column is
     * {@code CHAR(26)}, the bounds are {@code String}, and the comparison is a string comparison. This
     * file names no temporal type anywhere.
     *
     * <p>That is transcription, not preference. {@code app/cbl/CBTRN03C.cbl} declares
     * {@code WS-START-DATE PIC X(10)} at {@code :L123} and {@code WS-END-DATE PIC X(10)} at
     * {@code :L125}, then re-applies the sort step's filter inside the processor at {@code :L173-L174}:
     *
     * <pre>
     *   173:  IF TRAN-PROC-TS (1:10) &gt;= WS-START-DATE
     *   174:     AND TRAN-PROC-TS (1:10) &lt;= WS-END-DATE
     * </pre>
     *
     * <p>Both operands are alphanumeric, so COBOL compares them character by character in the native
     * collating sequence. The reference modification {@code (1:10)} takes the first ten characters,
     * which is why the query applies {@code substring(t.procTs, 1, 10)} - JPQL {@code substring} is
     * one-based, so {@code (1, 10)} is the same ten characters. Both bounds are
     * <strong>inclusive</strong>, matching {@code GE} and {@code LE} in the {@code INCLUDE COND} above
     * and {@code &gt;=} and {@code &lt;=} in the processor.
     *
     * <p>A temporal conversion would be actively wrong here rather than merely unidiomatic: the column
     * carries at least three mutually incompatible producer formats, and in the reference fixture
     * {@code app/data/ASCII/dailytran.txt} it is twenty-six spaces in every row - a value no date parser
     * accepts. A blank value simply falls outside any real date range under lexical comparison, because
     * a space collates below a digit, so it is excluded from the result exactly as it is by the legacy
     * filter.
     *
     * <h4>High - the key is NONUNIQKEY, so this returns a collection and never an Optional</h4>
     * <strong>Severity High</strong> if narrowed. {@code app/catlg/LISTCAT.txt:L3678} declares the
     * alternate index {@code NONUNIQKEY} in so many words, and the fixture bears it out - all three
     * hundred staged rows share a single processing-timestamp value. Returning an {@code Optional} would
     * silently drop rows, or fail outright on the second one. The backing index in
     * {@code V2__create_indexes.sql} must likewise be <strong>non-unique</strong>, and it is the only
     * index this table gets.
     *
     * <h4>Least privilege: this finder is batch-only</h4>
     * The alternate index has <strong>no CICS definition</strong>. {@code app/csd/CARDDEMO.CSD} contains
     * exactly eight {@code DEFINE FILE} entries, and the only two that name an {@code AIX.PATH} dataset
     * are {@code CARDAIX} at {@code :L13-L14} and {@code CXACAIX} at {@code :L63-L65}. The transaction
     * entry at {@code :L76-L77} names the base cluster
     * {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS} only, so no online program could ever reach this access
     * path. It is consumed exclusively by the report and combine jobs and <strong>must not be exposed
     * through any controller</strong>. Recorded as discrepancy #10, severity Low, in
     * {@code DECISION_LOG.md}.
     *
     * <h4>Ordering</h4>
     * The primary ordering is {@code cardNumber} ascending, reproducing
     * {@code SORT FIELDS=(TRAN-CARD-NUM,A)} at {@code app/proc/TRANREPT.prc:L44}. That ordering is not
     * incidental - the report processor's control break fires on the card number at
     * {@code app/cbl/CBTRN03C.cbl:L181} comparing {@code WS-CURR-CARD-NUM} (declared {@code X(16)} at
     * {@code :L137}) against {@code TRAN-CARD-NUM}, so records for one card must arrive contiguously or
     * the break fires repeatedly.
     *
     * <p>{@code transactionId} ascending is appended as a tie-breaker. The legacy deck sorts on the card
     * number alone and specifies no {@code OPTION EQUALS}, which means DFSORT leaves the relative order
     * of equal-keyed records <em>unspecified</em>; pinning it to the primary key therefore cannot
     * contradict the source, and it makes the result a total order so that a parity comparison against a
     * baseline is reproducible run to run.
     *
     * <h4>Performance tradeoff, stated rather than hidden</h4>
     * A predicate over {@code substring(tran_proc_ts, 1, 10)} is not served directly by a plain B-tree
     * index on {@code tran_proc_ts}. That cost is accepted deliberately: faithfulness to the source's
     * ten-character comparison governs, a bare-column range would require the caller to synthesise an
     * upper-bound sentinel and so introduce a divergence risk for no behavioural gain, and this is a
     * batch path over a bounded backup generation - {@code app/proc/TRANREPT.prc:L21-L31} unloads the
     * cluster to {@code TRANSACT.BKUP(+1)} before the range is ever applied. Adding a second, functional
     * index for this table is explicitly out of scope: {@code V2__create_indexes.sql} creates exactly
     * three non-unique indexes across the whole schema.
     *
     * <h4>What this method does not do</h4>
     * It returns rows and nothing more. The twenty-lines-per-page pagination
     * ({@code app/cbl/CBTRN03C.cbl:L131-L132}, {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20}), the
     * page and account and grand totals, and the preserved quirk whereby the control break fires on the
     * <em>card number</em> while the emitted label reads {@code 'Account Total'} - a literal that lives
     * in {@code app/cpy/CVTRA07Y.cpy:L58} - all belong to the report processor. None of it is
     * reproduced, or reproducible, here.
     *
     * @param startDateInclusive the inclusive lower bound, the first ten characters of a processing
     *        timestamp as text, in the same form the legacy sort symbol supplies at
     *        {@code app/proc/TRANREPT.prc:L41} ({@code PARM-START-DATE,C'2022-01-01'}); compared
     *        lexically, so it must be exactly the ten-character prefix and must not be a formatted
     *        temporal value of any other shape; must not be {@code null}
     * @param endDateInclusive the inclusive upper bound, in the same ten-character form as
     *        {@code app/proc/TRANREPT.prc:L42} ({@code PARM-END-DATE,C'2022-07-06'}); must not be
     *        {@code null}
     * @return every matching row ordered by card number ascending then identifier ascending; an
     *         <strong>empty list</strong> when nothing matches, which is the ordinary no-data outcome and
     *         never an error - a bound pair in the wrong order simply yields no rows, exactly as the
     *         legacy {@code INCLUDE COND} does
     * @implNote The query text is a text block, which is a compile-time constant, so the whole statement
     *         is fixed at compile time and both bounds arrive strictly as named bind parameters. There
     *         is no concatenation and no native-SQL query mode, so neither bound can influence the
     *         structure of the statement.
     */
    @Query("""
            select t
            from Transaction t
            where substring(t.procTs, 1, 10) >= :startDateInclusive
              and substring(t.procTs, 1, 10) <= :endDateInclusive
            order by t.cardNumber asc, t.transactionId asc
            """)
    List<Transaction> findByProcessingDateRangeOrderByCardNumberAsc(
            @Param("startDateInclusive") String startDateInclusive,
            @Param("endDateInclusive") String endDateInclusive);

    /*
     * ------------------------------------------------------------------------------------------------
     * The online browse - three methods, because the source has three boundary semantics
     * ------------------------------------------------------------------------------------------------
     *
     * app/cbl/COTRN00C.cbl browses the keyed cluster in both directions and pages TEN rows at a time.
     *
     * LOW - discrepancy #8: the page size is NOT at app/cbl/COTRN00C.cbl:L65-L68.
     *     Those lines are COMMAREA page-STATE fields - CDEMO-CT00-TRNID-FIRST at :L63,
     *     CDEMO-CT00-TRNID-LAST at :L64, CDEMO-CT00-PAGE-NUM PIC 9(08) at :L65 and
     *     CDEMO-CT00-NEXT-PAGE-FLG with its two 88-levels at :L66-L68. None of them is a page size.
     *     The load-bearing evidence is the loop bounds and row-population logic:
     *         :L290  PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10   (forward, clear the array)
     *         :L297  PERFORM UNTIL WS-IDX >= 11 OR ...                      (forward, fill rows 1..10)
     *         :L344  PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10   (backward, clear)
     *         :L349  MOVE 10 TO WS-IDX                                      (backward, start at row 10)
     *         :L351  PERFORM UNTIL WS-IDX <= 0 OR ...                       (backward, fill down to 1)
     *     Corroborated on the presentation side by app/cpy-bms/COTRN00.CPY, which generates ten row
     *     groups TRNID01 through TRNID10 across 110 TRNID-matching lines, the first quintuple being
     *     TRNIDINL / TRNIDINF / TRNIDINA / TRNIDINI at :L61-L66. Recorded as Low in DECISION_LOG.md.
     *
     *     The three parity page sizes across the application are card list 7
     *     (app/cbl/COCRDLIC.cbl:L177-L178, WS-MAX-SCREEN-LINES ... VALUE 7), transaction list 10 and
     *     user list 10 (app/cbl/COUSR00C.cbl:L57, USER-REC OCCURS 10 TIMES). NONE of them is hardcoded
     *     in this interface: every page size is bound from carddemo.pagination.* configuration and
     *     arrives as a Pageable, so this file contains no page-size literal in any signature, default
     *     or annotation.
     *
     * Why THREE methods and not two. EXEC CICS STARTBR positions with GTEQ - the GTEQ operand is
     * commented out in STARTBR-TRANSACT-FILE, so the CICS default applies - and the browse then reads
     * the positioned record first. Whether that record is consumed or skipped depends on the attention
     * identifier, which yields three distinct bound semantics that no two methods can express without
     * synthesising predecessor or successor keys:
     *
     *   (a) ENTER / first display. :L206-L210 positions at LOW-VALUES when the search field is blank,
     *       otherwise at the entered numeric identifier. PROCESS-PAGE-FORWARD then tests
     *       :L285 IF EIBAID NOT = DFHENTER AND DFHPF7 AND DFHPF3, which is FALSE for DFHENTER, so no
     *       record is skipped => the lower bound is INCLUSIVE.
     *   (b) PF8, page forward. :L259-L262 positions at CDEMO-CT00-TRNID-LAST, the last key of the page
     *       just displayed, captured at :L437-L439 when WS-IDX is 10. The same test at :L285 is TRUE for
     *       DFHPF8, so :L286 performs one extra READNEXT that consumes the positioned record
     *       => the lower bound is EXCLUSIVE.
     *   (c) PF7, page backward. :L236-L239 positions at CDEMO-CT00-TRNID-FIRST, the first key of the
     *       page just displayed, captured at :L391-L393 when WS-IDX is 1. PROCESS-PAGE-BACKWARD tests
     *       :L339 IF EIBAID NOT = DFHENTER AND DFHPF8, TRUE for DFHPF7, so :L340 performs one extra
     *       READPREV that consumes the positioned record => the upper bound is EXCLUSIVE, descending.
     *
     * Each method below reproduces exactly one of those. They are NOT interchangeable: using an
     * inclusive finder for (b) repeats a row, and using an exclusive finder for (a) drops the very
     * record the user searched for.
     *
     * All three return Slice rather than Page on purpose. Slice#hasNext() is the precise analogue of
     * CDEMO-CT00-NEXT-PAGE-FLG (:L66-L68), which is what the source consults at :L267 before honouring
     * a page-forward request, and Slice avoids a COUNT query that the source never issues. Pagination
     * state is carried in request parameters and response metadata - com.cardemo.model.dto.PageResponse -
     * so no server-side cursor and no session state exists here or anywhere else.
     * ------------------------------------------------------------------------------------------------
     */

    /**
     * Reads one page forward from a starting identifier <strong>inclusive</strong>, in ascending key
     * order.
     *
     * <p>Reproduces path (a) above: the initial display of the online transaction list and the
     * search-by-identifier path. {@code app/cbl/COTRN00C.cbl:L206-L210} positions the browse at
     * {@code LOW-VALUES} when the search field is blank, or at the entered identifier when one was
     * supplied and passed the numeric edit; because the attention identifier is {@code DFHENTER} the
     * test at {@code :L285} is false, no record is skipped, and the positioned record is the first row
     * of the page. Hence inclusive.
     *
     * <p>For the blank-search case the caller supplies the lowest bound of the identifier domain, which
     * is the {@code LOW-VALUES} equivalent for a zero-padded {@code CHAR(16)} key; every real identifier
     * is greater than or equal to it, so the page starts at the true beginning of the cluster.
     *
     * @param startTransactionIdInclusive the identifier to start at, included in the result when it
     *        exists; the zero-padded sixteen-character form; must not be {@code null}
     * @param pageable the page size, bound from {@code carddemo.pagination.*} configuration - ten for
     *        this list. Pass an <strong>unsorted</strong> {@code Pageable} such as
     *        {@code Pageable.ofSize(pageSize)}: the ordering is part of this method's contract, not the
     *        caller's, and a sort supplied here would only be appended after it. Must not be
     *        {@code null}
     * @return the page of rows ordered by identifier ascending; empty when nothing is at or beyond the
     *         bound. {@code Slice#hasNext()} carries the next-page indication that the source keeps in
     *         {@code CDEMO-CT00-NEXT-PAGE-FLG}
     */
    Slice<Transaction> findByTransactionIdGreaterThanEqualOrderByTransactionIdAsc(
            String startTransactionIdInclusive, Pageable pageable);

    /**
     * Reads the next page forward, strictly <strong>after</strong> a given identifier, in ascending key
     * order.
     *
     * <p>Reproduces path (b) above: the {@code PF8} page-forward request.
     * {@code app/cbl/COTRN00C.cbl:L259-L262} positions the browse at {@code CDEMO-CT00-TRNID-LAST} - the
     * identifier of the tenth and last row of the page just displayed, captured at {@code :L437-L439} -
     * and {@code :L285-L287} then performs one extra {@code READNEXT} which consumes that positioned
     * record. The rows the user sees therefore begin strictly after it, which is what the exclusive
     * bound expresses. The source guards the request with the next-page flag at {@code :L267},
     * responding {@code 'You are already at the bottom of the page...'} when it is not set; the
     * equivalent guard is {@code Slice#hasNext()} on the previously returned page.
     *
     * @param afterTransactionIdExclusive the last identifier already displayed; excluded from the
     *        result; must not be {@code null}
     * @param pageable the page size, bound from configuration; pass an unsorted {@code Pageable}; must
     *        not be {@code null}
     * @return the next page of rows ordered by identifier ascending; empty when the previous page was
     *         the last one
     */
    Slice<Transaction> findByTransactionIdGreaterThanOrderByTransactionIdAsc(
            String afterTransactionIdExclusive, Pageable pageable);

    /**
     * Reads the previous page, strictly <strong>before</strong> a given identifier, in descending key
     * order.
     *
     * <p>Reproduces path (c) above: the {@code PF7} page-backward request.
     * {@code app/cbl/COTRN00C.cbl:L236-L239} positions the browse at {@code CDEMO-CT00-TRNID-FIRST} -
     * the identifier of the first row of the page just displayed, captured at {@code :L391-L393} - and
     * {@code :L339-L341} then performs one extra {@code READPREV} which consumes that positioned record,
     * so the rows returned lie strictly before it. The source fills its screen array from index ten down
     * to one ({@code :L349} and {@code :L351}), which is why the rows arrive here in descending order:
     * the caller reverses them for display, exactly as the descending fill does.
     *
     * <p>The source guards this request too, at {@code :L245}, responding
     * {@code 'You are already at the top of the page...'} when the page number is not greater than one;
     * and {@code STARTBR-TRANSACT-FILE} itself answers a {@code DFHRESP(NOTFND)} with
     * {@code 'You are at the top of the page...'}. Both correspond to an empty result here, which is an
     * ordinary boundary outcome and never an error.
     *
     * @param beforeTransactionIdExclusive the first identifier already displayed; excluded from the
     *        result; must not be {@code null}
     * @param pageable the page size, bound from configuration; pass an unsorted {@code Pageable}; must
     *        not be {@code null}
     * @return the previous page of rows ordered by identifier <strong>descending</strong>, matching the
     *         source's backward fill; empty when the caller is already at the beginning of the cluster
     */
    Slice<Transaction> findByTransactionIdLessThanOrderByTransactionIdDesc(
            String beforeTransactionIdExclusive, Pageable pageable);
}
