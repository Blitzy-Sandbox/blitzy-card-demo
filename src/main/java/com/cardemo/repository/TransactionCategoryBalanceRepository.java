/*
 * ******************************************************************
 * Program     : TransactionCategoryBalanceRepository.java
 * Application : CardDemo
 * Type        : Spring Data JPA Repository Interface
 * Function    : Replaces the VSAM access verbs over the per-account, per-type,
 *               per-category balance table, including the scoped create/update
 *               path in which FILE STATUS '23' is an accepted control branch
 *               rather than an error. Batch-only: no CICS file definition.
 * Source      : AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS, composite key 17 / reclen 50
 *               (app/catlg/LISTCAT.txt:L1371 DATA-component attribute line;
 *               app/jcl/TCATBALF.jcl:L36, L40-L41 KEYS(17 0) RECORDSIZE(50 50));
 *               record layout app/cpy/CVTRA01Y.cpy:L4-L10; upsert path
 *               app/cbl/CBTRN02C.cbl:L467-L542 (2700-UPDATE-TCATBAL and its two
 *               branches); key-ordered browse app/cbl/CBACT04C.cbl:L186-L224;
 *               ABSENT from app/csd/CARDDEMO.CSD (batch-only proof) @ 7756d89
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

import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.key.TransactionCategoryBalanceId;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Transaction category balance data access: the relational replacement for the VSAM access verbs over the KSDS
 * cluster {@code AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS}.
 *
 * <h2>What it does</h2>
 *
 * <p>This interface is the whole persistence surface of the per-account, per-transaction-type,
 * per-transaction-category running balance. It carries no state, no arithmetic and no business rule: it
 * declares the reads and writes that two batch programs perform against one 50-byte VSAM record, and
 * nothing else. Two access patterns exist in the source corpus and both are served here:
 *
 * <ul>
 *   <li><b>A keyed create-or-update, one row per posted transaction.</b> {@code app/cbl/CBTRN02C.cbl:L57-L61}
 *       declares the file {@code ORGANIZATION IS INDEXED} with {@code ACCESS MODE IS RANDOM}, and
 *       paragraph {@code 2700-UPDATE-TCATBAL} ({@code :L467-L501}) reads one row by key and then
 *       branches to either a create or an update. This is served by the inherited
 *       {@code findById} and {@code save}, documented below. It is the single most delicate contract in
 *       this file, because a not-found result on that read is a <em>success</em> path.</li>
 *   <li><b>A key-ordered sequential browse of the whole table.</b>
 *       {@code app/cbl/CBACT04C.cbl:L28-L32} declares the same file with
 *       {@code ACCESS MODE IS SEQUENTIAL} and {@code :L188-L222} walks it in key order, detecting an
 *       account-level control break at {@code :L194}. This is served by the one method this interface
 *       declares, and its ordering is not negotiable.</li>
 *   </ul>
 *
 * <p>The arithmetic that both patterns exist to perform - adding a transaction amount to a balance -
 * deliberately does <em>not</em> live here. It belongs to {@code com.cardemo.batch.processors}, so that
 * this interface stays a data-access boundary with a single responsibility and the accumulation stays
 * unit-testable without a database.
 *
 * <h2>How it is built, run and tested</h2>
 *
 * <p>{@code ./mvnw -B clean compile} compiles this interface under {@code -Xlint:all -Werror} with
 * {@code failOnWarning} enabled, so any lint finding in a category {@code javac} 25 publishes is a hard
 * build failure rather than a warning. An unused import is not such a category - {@code javac} at
 * release 25 publishes no key for one - so it must be spotted by hand.
 * {@code ./mvnw -B clean test} runs the unit tier; {@code ./mvnw -B clean verify} additionally
 * enforces the JaCoCo line-coverage floor. There is nothing to run: a Spring Data repository is an
 * interface with no implementation in the source tree, and the proxy is created by the container at
 * startup.
 *
 * <p>Two things are worth knowing about how this file fails. First, <b>the derived query name below is
 * validated at application startup, not at compile time.</b> A misspelled property inside a derived name
 * compiles perfectly and then aborts context creation, so a green {@code compile} proves nothing about
 * it; only booting a Spring Data context does. Second, that startup validation is precisely what makes
 * the method name a useful assertion: it is a machine-checked statement that the entity really does
 * expose {@code id.accountId}, {@code id.typeCd} and {@code id.catCd}.
 *
 * <p>Integration coverage is authored in {@code src/test/java/com/cardemo/integration/repository}, in
 * {@code TransactionCategoryBalanceRepositoryTest} against a Testcontainers PostgreSQL 16 instance, and four
 * cases are required rather than optional, because each one pins a behaviour that a plausible wrong
 * implementation would break:
 *
 * <ol>
 *   <li>the create branch, where the key is absent and {@code findById} yields an empty result;</li>
 *   <li>the update branch, where the key is present and the amount is added to the value just read;</li>
 *   <li>a <b>negative</b> amount, which must legitimately reduce the balance;</li>
 *   <li>the key-ordered read, which must return all of one account's rows contiguously.</li>
 * </ol>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>This interface reads no property and configures nothing. It depends on four settings owned
 * elsewhere, and each one changes what a fault here looks like:
 *
 * <ul>
 *   <li>{@code spring.jpa.hibernate.ddl-auto: validate} in every profile. Any drift between the mapping
 *       this interface is typed over and the Flyway schema becomes an application-context startup
 *       failure instead of a latent data fault. That is the intended behaviour: fail loudly at boot.</li>
 *   <li>{@code spring.jpa.open-in-view: false}. Results are returned fully initialised and there is no
 *       lazy access outside a transaction. Nothing on this entity is lazy - it has one embedded
 *       identifier and one decimal column, and no association at all - so this setting costs nothing
 *       here and is stated so that no future association is added casually.</li>
 *   <li>{@code spring.jpa.show-sql: false}, with no Hibernate SQL or bind-parameter logging in any
 *       profile. Bind parameters on this table include account identifiers, and nothing that identifies
 *       an account belongs in a log.</li>
 *   <li>{@code spring.batch.jdbc.initialize-schema}, which creates the framework's own
 *       {@code BATCH_*} metadata tables from its packaged script. Those tables are never a fourth Flyway
 *       migration and never appear in {@code V1__create_schema.sql}.</li>
 *   </ul>
 *
 * <p>No connection-pool tuning is applied. HikariCP defaults are in force deliberately: the source
 * system publishes no throughput or latency objective anywhere, so there is no target to tune towards
 * and inventing one would be fabrication. That is stated plainly rather than silently omitted.
 *
 * <h2>Batch only: proved by absence from the CICS CSD</h2>
 *
 * <p>{@code app/csd/CARDDEMO.CSD} contains <b>exactly eight</b> {@code DEFINE FILE} entries, and they are
 * the complete online file-control table: {@code ACCTDAT} ({@code :L1}), {@code CARDAIX} ({@code :L13}),
 * {@code CARDDAT} ({@code :L25}), {@code CCXREF} ({@code :L37}), {@code CUSTDAT} ({@code :L50}),
 * {@code CXACAIX} ({@code :L63}), {@code TRANSACT} ({@code :L76}) and {@code USRSEC} ({@code :L88}).
 *
 * <p>The string {@code TCATBALF} occurs <b>zero</b> times in that file, as do {@code DISCGRP},
 * {@code TRANCATG} and {@code TRANTYPE}. That absence is not an oversight to be corrected - it is the
 * evidence that these four clusters are batch-only datasets. No CICS transaction can open a file the
 * region has never defined, so no screen program in the corpus reads or writes this table, and there is
 * no legacy online behaviour to reproduce.
 *
 * <p>Two consequences bind every consumer of this interface:
 *
 * <ul>
 *   <li><b>Its only callers are {@code com.cardemo.batch} and {@code com.cardemo.service}.</b> Two jobs
 *       use it: the daily transaction posting job, whose sixth dataset is this cluster
 *       ({@code app/jcl/POSTTRAN.jcl:L41-L42}), and the interest calculation job
 *       ({@code app/jcl/INTCALC.jcl} driving {@code app/cbl/CBACT04C.cbl}).</li>
 *   <li><b>No controller CRUD, no REST endpoint and no administrative management surface exists for
 *       {@code TCATBALF}, {@code DISCGRP}, {@code TRANCATG} or {@code TRANTYPE}.</b> The target exposes
 *       exactly seventeen operations across eight controllers, mapped one-for-one from the seventeen
 *       sourced CICS transactions, and not one of them touches these four tables. Adding an endpoint
 *       here would not be an enhancement; it would be new attack surface over financial accumulators
 *       that the system of record never exposed, in breach of least privilege.</li>
 *   </ul>
 *
 * <h2>Source contract: a 17 byte composite key inside a 50 byte record</h2>
 *
 * <p>Reproduced verbatim from {@code app/cpy/CVTRA01Y.cpy}, whose header comment at {@code :L2} reads
 * {@code Data-structure for transaction category balance (RECLN = 50)}:
 *
 * <pre>{@code
 * 01  TRAN-CAT-BAL-RECORD.
 *     05  TRAN-CAT-KEY.
 *        10 TRANCAT-ACCT-ID                       PIC 9(11).
 *        10 TRANCAT-TYPE-CD                       PIC X(02).
 *        10 TRANCAT-CD                            PIC 9(04).
 *     05  TRAN-CAT-BAL                            PIC S9(09)V99.
 *     05  FILLER                                  PIC X(22).
 * }</pre>
 *
 * <p>The geometry closes exactly, with no unexplained bytes, and four independent sources corroborate
 * it, so the key width is settled fact rather than inference:
 *
 * <pre>
 * TRANCAT-ACCT-ID  9(11)        11 bytes  &lt;- leads the key; this is why the browse works
 * TRANCAT-TYPE-CD  X(02)      +  2 bytes
 * TRANCAT-CD       9(04)      +  4 bytes
 *                             = 17 bytes  composite key
 * TRAN-CAT-BAL     S9(09)V99  + 11 bytes
 * FILLER           X(22)      + 22 bytes
 *                             = 50 bytes  catalogued record
 * </pre>
 *
 * <ol>
 *   <li><b>The catalogue.</b> {@code app/catlg/LISTCAT.txt:L1371} reads {@code KEYLEN 17} with
 *       {@code AVGLRECL 50} under the cluster declared at {@code :L1334}; {@code :L1372} reads
 *       {@code RKP 0} with {@code MAXLRECL 50}, so the key is the record prefix and the record is fixed
 *       width rather than 50 bytes on average.</li>
 *   <li><b>The definition job.</b> {@code app/jcl/TCATBALF.jcl} defines the cluster through IDCAMS at
 *       {@code :L36} with {@code KEYS(17 0)} at {@code :L40} and {@code RECORDSIZE(50 50)} at
 *       {@code :L41}.</li>
 *   <li><b>The file description, independent of the copybook.</b>
 *       {@code app/cbl/CBTRN02C.cbl:L91-L97} redeclares the record in the FILE SECTION as
 *       {@code FD-TRAN-CAT-KEY} with the identical 11 / 2 / 4 composition followed by
 *       {@code FD-FD-TRAN-CAT-DATA PIC X(33)}. That trailing 33 is 11 balance bytes plus 22 filler
 *       bytes, and 17 + 33 = 50. The repeated {@code FD-FD-} prefix is a source quirk, recorded and not
 *       corrected.</li>
 *   <li><b>The seed fixture, measured rather than assumed.</b> {@code app/data/ASCII/tcatbal.txt} is
 *       2,550 bytes holding 50 rows of exactly 50 data characters each plus a line feed. Each row splits
 *       into an eleven-character account identifier, a two-character type code, a four-character category
 *       code, an eleven-character balance field, then 22 filler characters. The overpunch character
 *       terminating the eleven-character balance field is &#123;, a zoned-decimal trailing sign meaning
 *       positive zero, so the field decodes to {@code +0.00}. A census of all 50 rows found that same
 *       overpunch in every one and no other, so every seeded balance is {@code +0.00} and the
 *       accumulation genuinely begins at zero. Note the precision of that statement: the overpunch ends
 *       the <em>balance field</em> at byte 28, not the physical row, whose last 22 bytes are filler.</li>
 *   </ol>
 *
 * <p>{@code FILLER} is deliberately not modelled. It carries no data and exists only to pad the record
 * to the catalogued 50 bytes, which a relational row has no need of. Its width is recorded above so the
 * arithmetic can be checked, and nowhere else.
 *
 * <p>The identifier this interface is typed over, {@link TransactionCategoryBalanceId}, holds the three
 * key components in <b>COBOL declaration order</b>: {@code accountId}, then {@code typeCd}, then
 * {@code catCd}. That order is load-bearing rather than cosmetic - see the browse section below - and it
 * is why the composite primary key in {@code V1__create_schema.sql} must be declared over
 * {@code (acct_id, tran_type_cd, tran_cat_cd)} in exactly that sequence.
 *
 * <h2>The balance is NUMERIC(11,2), never NUMERIC(12,2)</h2>
 *
 * <p>{@code TRAN-CAT-BAL} is {@code PIC S9(09)V99} at {@code app/cpy/CVTRA01Y.cpy:L9}: nine integer
 * digits and two decimal digits, so precision 11 and scale 2. Widening it to precision 12 is the single
 * most likely error in this area of the schema, because the five money and cycle fields on the account
 * entity are {@code PIC S9(10)V99} and therefore genuinely are {@code NUMERIC(12,2)}. Three distinct
 * precision tiers exist and must never be collapsed into one:
 *
 * <pre>
 * PIC clause    SQL             fields
 * ------------  --------------  ---------------------------------------------------------------
 * S9(10)V99     NUMERIC(12,2)   the five Account money and cycle fields
 * S9(09)V99     NUMERIC(11,2)   TRAN-AMT, DALYTRAN-AMT, and TRAN-CAT-BAL  &lt;-- this table
 * S9(04)V99     NUMERIC(6,2)    DIS-INT-RATE
 * </pre>
 *
 * <p><b>Why this is load-bearing.</b> Under {@code ddl-auto: validate} a precision or scale mismatch aborts
 * context startup, so the fault presents as a total outage on first boot. Where validation is not in
 * force it is worse, because it degrades into silent scale divergence: values round at a different digit
 * than the source system did and no test that fails to assert the scale will notice.
 * <b>The rule:</b> keep {@code tran_cat_bal NUMERIC(11,2)} in the migration and derive it from the
 * picture clause at {@code app/cpy/CVTRA01Y.cpy:L9}, never from a neighbouring entity.
 *
 * <h2>Decimal and sign discipline</h2>
 *
 * <p>The balance is a {@code java.math.BigDecimal} and nothing else. Neither IEEE-754 binary primitive,
 * of either width, appears in this file or in the entity it is typed over - not as a member, a parameter, a
 * return type or a local - because a binary radix cannot represent ordinary decimal money exactly, and the
 * security audit gate asserts the absence of such types across every financial field by
 * inspection. Two rules bind every caller:
 *
 * <ul>
 *   <li><b>Compare with {@code compareTo}, never with {@code equals}.</b>
 *       {@code BigDecimal.equals} is scale sensitive, so a balance of {@code 2.0} is not equal to a
 *       balance of {@code 2.00} even though the amounts are identical. Only {@code compareTo} answers the
 *       question a financial comparison is actually asking. This matters directly to tests against this
 *       interface: a round trip through {@code NUMERIC(11,2)} returns a value at scale 2, so asserting
 *       equality against a literal written at a different scale fails for no real reason.</li>
 *   <li><b>Round with {@code RoundingMode.HALF_EVEN}.</b> No arithmetic happens in this interface, so no
 *       rounding happens here either. The rule is recorded because the accumulation the batch layer
 *       performs on this value must apply it.</li>
 *   </ul>
 *
 * <p><b>The balance is signed and may legitimately be negative, and no absolute-value normalisation is
 * permitted anywhere on this path.</b> The posting job adds the transaction amount at
 * {@code app/cbl/CBTRN02C.cbl:L508} on the create branch and {@code :L527} on the update branch, and
 * {@code DALYTRAN-AMT} is itself {@code PIC S9(09)V99}. The daily transaction fixture
 * {@code app/data/ASCII/dailytran.txt} carries genuinely negative zoned-decimal overpunch signs across
 * its 300 rows, so the balance really does move in both directions and the negative branch is exercised
 * rather than theoretical. The same source truth appears one paragraph later at
 * {@code app/cbl/CBTRN02C.cbl:L548-L552}, where a negative amount is added to the current-cycle
 * <em>debit</em> accumulator, which is exactly why the over-limit test elsewhere subtracts that
 * accumulator. Consequently there is no {@code abs}, no {@code negate} and no positivity constraint in
 * this file. Normalising the sign would silently discard credits and put the
 * table permanently out of agreement with the system of record, and because the row would still look
 * plausible, no schema check and no smoke test would catch it.
 *
 * <h2>Inherited operations: the scoped create-or-update on FILE STATUS '23'</h2>
 *
 * <p>The keyed create-or-update is served entirely by two inherited methods. Neither is redeclared - redeclaring
 * them would add no behaviour and would create a second place for the contract to drift - so both are
 * documented here instead. The source paragraph, reproduced from
 * {@code app/cbl/CBTRN02C.cbl:L467-L501}:
 *
 * <pre>{@code
 * 2700-UPDATE-TCATBAL.
 *     MOVE XREF-ACCT-ID     TO FD-TRANCAT-ACCT-ID        <- :L469  from the cross-reference record
 *     MOVE DALYTRAN-TYPE-CD TO FD-TRANCAT-TYPE-CD        <- :L470  from the daily transaction input
 *     MOVE DALYTRAN-CAT-CD  TO FD-TRANCAT-CD             <- :L471  from the daily transaction input
 *     MOVE 'N' TO WS-CREATE-TRANCAT-REC
 *     READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD         <- :L474  the keyed read
 *        INVALID KEY
 *          DISPLAY 'TCATBAL record not found for key : ' FD-TRAN-CAT-KEY '.. Creating.'
 *          MOVE 'Y' TO WS-CREATE-TRANCAT-REC
 *     END-READ.
 *     IF  TCATBALF-STATUS = '00'  OR '23'                <- :L481  BOTH statuses accepted
 *         MOVE 0 TO APPL-RESULT
 *     ELSE
 *         MOVE 12 TO APPL-RESULT                                   anything else abends
 *     END-IF
 *     ...
 *     IF WS-CREATE-TRANCAT-REC = 'Y'
 *        PERFORM 2700-A-CREATE-TCATBAL-REC               <- :L496  create branch
 *     ELSE
 *        PERFORM 2700-B-UPDATE-TCATBAL-REC               <- :L498  update branch
 *     END-IF
 * }</pre>
 *
 * <h3>{@code findById(TransactionCategoryBalanceId)} - the keyed read at {@code :L474}</h3>
 *
 * <p><b>Purpose.</b> Retrieve the single row for one account / type / category triple, so the caller can
 * decide between creating and updating it.
 *
 * <p><b>Input.</b> A fully populated {@link TransactionCategoryBalanceId}. Its three components come from
 * <b>two different records</b>, and the Javadoc says so because substituting either is a silent parity
 * break: the account identifier is taken from the <em>cross-reference</em> record
 * ({@code XREF-ACCT-ID}, {@code :L469}), while the type and category codes are taken from the
 * <em>daily transaction input</em> record ({@code DALYTRAN-TYPE-CD} and {@code DALYTRAN-CAT-CD},
 * {@code :L470-L471}). A caller must not substitute the posted transaction's own codes after any
 * transformation, and must not derive the account identifier from the transaction.
 *
 * <p><b>Output and side effects.</b> A {@code java.util.Optional} holding the row, or an empty
 * {@code Optional}. No side effects; nothing is written and nothing is locked.
 *
 * <p><b>Failure modes.</b> <b>An empty result is not a failure.</b> It is the accepted create branch, and
 * it is the reason {@code :L481} accepts {@code '00'} or {@code '23'} where every other guard in the
 * program accepts {@code '00'} alone. A caller must therefore <b>never</b> translate the empty
 * {@code Optional} on this path into {@code com.cardemo.exception.RecordNotFoundException}, and
 * {@code com.cardemo.service.shared.FileStatusMapper} must exempt this call site from its normal
 * not-found translation. If it throws, the very first transaction for a new
 * account, type and category combination would abend the posting job, which is the ordinary case on a
 * freshly seeded system rather than an edge case. A genuine failure on this path is an infrastructure
 * fault - the provider raising a data-access exception - which is the counterpart of the
 * {@code MOVE 12 TO APPL-RESULT} branch and must propagate, never be swallowed.
 *
 * <h3>{@code save(TransactionCategoryBalance)} - both write branches</h3>
 *
 * <p><b>Purpose.</b> Persist the accumulated row. This one method serves <b>both</b> legacy branches,
 * which are distinct verbs in the source: {@code 2700-A-CREATE-TCATBAL-REC} at {@code :L503} issues a
 * VSAM {@code WRITE} ({@code :L510}), and {@code 2700-B-UPDATE-TCATBAL-REC} at {@code :L526} issues a
 * VSAM {@code REWRITE} ({@code :L528}). JPA selects between insert and update from the persistence
 * context and the identifier, so the branch is chosen for the caller rather than by it.
 *
 * <p><b>Input.</b> On the create branch, an instance built through the entity's public all-arguments
 * constructor {@code (TransactionCategoryBalanceId, BigDecimal)} with the transaction amount as the
 * initial balance. That constructor is the exact Java counterpart of
 * {@code INITIALIZE TRAN-CAT-BAL-RECORD} ({@code :L504}) followed by the three key moves
 * ({@code :L505-L507}) and the single {@code ADD} ({@code :L508}). On the update branch, the instance
 * returned by {@code findById} with the amount added to the balance it already carried
 * ({@code :L527}).
 *
 * <p><b>Both branches add the amount.</b> The create branch adds it to the zero balance produced by
 * {@code INITIALIZE}, so a newly created row's balance <em>is</em> the amount; the update branch adds it
 * to the value just read. The two are therefore the same accumulation with different starting points,
 * and a correct implementation reads, then either constructs with the amount or adds to the existing
 * balance, then saves.
 *
 * <p><b>Failure modes.</b> <b>The {@code '00' OR '23'} leniency is scoped to the read guard alone.</b>
 * The write guard at {@code :L512} and the rewrite guard at {@code :L530} each accept
 * <b>only {@code '00'}</b>; anything else displays
 * {@code 'ERROR WRITING TRANSACTION BALANCE FILE'} or
 * {@code 'ERROR REWRITING TRANSACTION BALANCE FILE'} and abends through
 * {@code 9999-ABEND-PROGRAM}. A persistence failure here is consequently <b>fatal</b> and must surface
 * as {@code com.cardemo.exception.FatalProcessingException} carrying abend code 999 and process return
 * code 12, exactly as {@code app/cbl/CBTRN02C.cbl:L707-L711} specifies. If the
 * read guard's leniency is generalised to the writes, a tolerated failed write loses money silently,
 * which is the worst possible failure mode for an accumulator and the one hardest to detect after the
 * fact.
 *
 * <h2>The three sites corpus wide where a non '00' status means success</h2>
 *
 * <p>Across the whole corpus a file status other than {@code '00'} means an error, with three exceptions.
 * {@code com.cardemo.service.shared.FileStatusMapper} performs the status-to-exception translation
 * exactly once, centrally, and it must exempt all three - a blanket rule that maps not-found to an
 * exception would abend two ordinary control paths:
 *
 * <ol>
 *   <li><b>This table's keyed read</b> - {@code app/cbl/CBTRN02C.cbl:L481} accepts {@code '00'} or
 *       {@code '23'}, and {@code '23'} drives the create branch.</li>
 *   <li><b>The disclosure group primary lookup</b> - {@code app/cbl/CBACT04C.cbl:L422} accepts
 *       {@code '00'} or {@code '23'}, and {@code '23'} drives the retry against the default group
 *       identifier.</li>
 *   <li><b>The statement file-service call sites</b> - {@code app/cbl/CBSTM03A.CBL:L736} and
 *       {@code :L748} accept {@code '00'} or {@code '04'}.</li>
 *   </ol>
 *
 * <p>Note what is <em>not</em> on that list, because it is a trap. The key-ordered browse in the interest
 * job is a different read against this same file, and its guard is <b>not</b> lenient: paragraph
 * {@code 1000-TCATBALF-GET-NEXT} accepts {@code '00'} alone, treats {@code '10'} as end of file by way of
 * {@code 88 APPL-EOF VALUE 16}, and abends on anything else. The leniency belongs to the random keyed
 * read, not to the file.
 *
 * <p>One further preserved quirk is worth recording where the statuses are discussed, because it looks
 * like a defect and must not be tidied. {@code app/cbl/CBTRN02C.cbl:L138-L140} renders a status through
 * {@code IO-STATUS-04}, a group of {@code PIC 9} followed by {@code PIC 999}, so the rendering is always
 * exactly four characters. Status {@code '23'} therefore renders as {@code 0023} and the emitted
 * diagnostic reads {@code FILE STATUS IS: NNNN0023}. That is reproduced rather than corrected.
 *
 * <h2>Atomicity belongs to the caller, not to this interface</h2>
 *
 * <p>In the source, {@code 2000-POST-TRANSACTION} ({@code app/cbl/CBTRN02C.cbl:L424-L444}) performs three
 * updates in sequence - {@code 2700-UPDATE-TCATBAL} ({@code :L440}), {@code 2800-UPDATE-ACCOUNT-REC}
 * ({@code :L441}) and {@code 2900-WRITE-TRANSACTION-FILE} ({@code :L442}) - as <b>three independent
 * commits</b>. In Java they become <b>one</b> declarative transaction boundary at the processor or service
 * layer, configured to roll back for every exception rather than only for unchecked ones.
 *
 * <p><b>This interface declares no transaction annotation of its own</b>, and that is deliberate rather
 * than an omission: the unit of work spans three repositories, so it can only be defined by the
 * collaborator that calls all three. Declaring a boundary here would either be redundant or, worse, would
 * commit the balance independently of the account update and recreate the very hazard the single
 * transaction removes.
 *
 * <p>That hazard is real and specific. On the failure path at {@code app/cbl/CBTRN02C.cbl:L554-L559} the
 * account rewrite fails, reject code 109 is assigned, and execution continues - leaving an orphaned
 * category-balance row and an orphaned transaction row committed against an account that was never
 * updated. A single transaction closes that hole as a side effect. This is a genuine <b>improvement</b>
 * over the source rather than parity with it, so it is labelled here as a deviation and must not be
 * presented as equivalence.
 *
 * <h2>Reject codes are business outcomes, never exceptions</h2>
 *
 * <p>{@code com.cardemo.model.enums.RejectCode} carries exactly five constants - 100, 101, 102, 103 and
 * 109 - and none of them is modelled here, thrown here, or returned here. They are validation outcomes
 * that drive a reject record and an exit status, not error conditions. Code 109 in particular is assigned
 * at {@code app/cbl/CBTRN02C.cbl:L556} on a path that has already passed validation, so no reject record
 * is written and the value is cleared on the next iteration: it is retained as a constant because the
 * assignment is real code on a reachable path, and it is never consumed as a reject outcome.
 *
 * <h2>The key ordered browse the interest job depends on</h2>
 *
 * <p>{@code app/cbl/CBACT04C.cbl:L188-L222} browses this file sequentially and performs an account-level
 * control break. The load-bearing line is {@code :L194}:
 *
 * <pre>{@code
 * IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM              <- :L194  the control break
 *   IF WS-FIRST-TIME NOT = 'Y'
 *      PERFORM 1050-UPDATE-ACCOUNT                      <- :L196  flush the PREVIOUS account
 *   ELSE
 *      MOVE 'N' TO WS-FIRST-TIME
 *   END-IF
 *   MOVE 0 TO WS-TOTAL-INT                              <- :L200  reset the running total
 *   MOVE TRANCAT-ACCT-ID TO WS-LAST-ACCT-NUM
 *   MOVE TRANCAT-ACCT-ID TO FD-ACCT-ID
 *   PERFORM 1100-GET-ACCT-DATA                          <- :L203  read the account record
 *   MOVE TRANCAT-ACCT-ID TO FD-XREF-ACCT-ID
 *   PERFORM 1110-GET-XREF-DATA                          <- :L205  read the cross-reference record
 * END-IF
 * }</pre>
 *
 * <p>A control break of that shape is correct <b>only</b> because {@code TRANCAT-ACCT-ID} leads the
 * 17-byte composite key, so VSAM key order necessarily groups all of one account's type and category rows
 * contiguously. The ordering is not a presentation preference; it is the precondition of the algorithm.
 *
 * <p><b>Therefore every sequential or multi-row method on this interface orders by
 * {@code id.accountId}, then {@code id.typeCd}, then {@code id.catCd}</b> - the exact composite-key
 * component order. Omitting it breaks the job silently. Relying on natural, heap or hash order is not
 * merely untidy: rows for one account would arrive interleaved with rows for another, the break would fire
 * repeatedly for the same account, the running total would be reset mid-account, and interest would be
 * lost for whole accounts. Nothing would throw, no constraint would be violated, and the job would report
 * success - which is precisely why the ordering is stated as an invariant and expressed in the method
 * name, where a caller cannot forget to supply it.
 *
 * <p>Two related facts complete the picture:
 *
 * <ul>
 *   <li><b>There is no final flush, and none may be added.</b> The source <em>appears</em> to update the
 *       account one last time at end of data, at {@code app/cbl/CBACT04C.cbl:L219-L220}
 *       ({@code ELSE PERFORM 1050-UPDATE-ACCOUNT}), but that branch is <b>unreachable as written</b>: the
 *       {@code ELSE} belongs to {@code IF END-OF-FILE = 'N'} at {@code :L189}, while the enclosing
 *       {@code PERFORM UNTIL END-OF-FILE = 'Y'} at {@code :L188} is a test-before loop that has already
 *       exited by the time the condition could be false, so control never re-enters the body to reach it.
 *       <b>The consequence is that the last account of every run is never updated</b> - its accumulated
 *       interest is never added to {@code ACCT-CURR-BAL} and its two cycle counters are never reset - and
 *       that is the behaviour of the system of record, reproduced exactly.
 *       <p>Performing the flush anyway,
 *       triggered by the end-of-data condition rather than by translating the dead {@code ELSE}, is
 *       <b>forbidden here</b>, because it contradicts both the parity mandate and the troubleshooting
 *       entry further down this document, and following it would silently add interest the frozen system
 *       never posts. {@code InterestCalculationProcessor.updateAccountAtEndOfFile()} retains the branch as a
 *       marked, unreachable no-op so the paragraph map stays provable, and calls it from nowhere.</p></li>
 *   <li><b>Paging over this table inside the interest job is stable.</b> The job never mutates the table
 *       it browses: its only write verbs are {@code REWRITE FD-ACCTFILE-REC} at
 *       {@code app/cbl/CBACT04C.cbl:L356} and {@code WRITE FD-TRANFILE-REC} at {@code :L500}, both
 *       against other datasets. A chunked read therefore cannot skip or repeat a row through concurrent
 *       modification by its own job, which is what makes the paged shape below a faithful substitute for
 *       the one-record-at-a-time {@code 1000-TCATBALF-GET-NEXT} browse.</li>
 *   </ul>
 *
 * <p><b>No index is created for this table, and none is needed.</b>
 * {@code V2__create_indexes.sql} declares exactly three non-unique indexes -
 * {@code card.card_acct_id}, {@code card_cross_reference.xref_acct_id} and
 * {@code "transaction".tran_proc_ts} - each replacing one of the three VSAM alternate indexes catalogued
 * in {@code app/catlg/LISTCAT.txt}. <b>There is no alternate index over {@code TCATBALF}</b>, so this
 * interface declares no alternate-key finder, and the composite primary key already provides every access
 * path the source performs: keyed retrieval by the full triple, and ordered traversal by its leading
 * component. Adding an index here would be dead weight and would break the gate that asserts exactly
 * three.
 *
 * <h2>The TRAN-CAT-KEY name collision that is deliberately not resolved</h2>
 *
 * <p>The group name {@code TRAN-CAT-KEY} appears in two copybooks with two different widths and two
 * different component prefixes. They are two distinct contracts that happen to share a label:
 *
 * <pre>
 * app/cpy/CVTRA01Y.cpy:L5   TRAN-CAT-KEY  17 bytes  TRANCAT-ACCT-ID + TRANCAT-TYPE-CD + TRANCAT-CD
 * app/cpy/CVTRA04Y.cpy:L5   TRAN-CAT-KEY   6 bytes  TRAN-TYPE-CD + TRAN-CAT-CD
 * </pre>
 *
 * <p>Merging them would be a mistake. {@link TransactionCategoryBalanceId} and
 * {@code com.cardemo.model.key.TransactionCategoryId} therefore remain separate classes with <b>no shared
 * abstraction, no common base type and no shared interface</b>, and their deliberately inconsistent
 * component names are <b>not harmonised</b>: {@code accountId} / {@code typeCd} / {@code catCd} here,
 * against {@code tranTypeCd} / {@code tranCatCd} there. Unifying them would be a plausible-looking
 * refactor that quietly implies the 6-byte key is a prefix of the 17-byte one. It is not - the 6-byte key
 * has no account component at all - and a shared type would invite exactly the substitution that breaks
 * both.
 *
 * <p>The abbreviated third component is a source quirk of the same family: this copybook spells it
 * {@code TRANCAT-CD} at {@code app/cpy/CVTRA01Y.cpy:L8} while {@code app/cpy/CVTRA04Y.cpy:L7} spells its
 * analogue {@code TRAN-CAT-CD} in full. Recorded, not tidied.
 *
 * <h2>What this interface deliberately does not declare</h2>
 *
 * <p>Each omission is a decision with a reason, listed so that a future reader does not read absence as
 * oversight and add one back:
 *
 * <ul>
 *   <li><b>No hand-written implementation, custom fragment or DAO wrapper</b> - no companion class beside
 *       this interface, no custom repository fragment, no shared base-repository abstraction, no
 *       criteria-API or predicate-builder helper, no mapper. Every operation this table needs is either
 *       inherited or derivable, so a hand-written body would be code with no reason to exist. This package
 *       holds exactly eleven interfaces plus its {@code package-info.java}.</li>
 *   <li><b>No aggregate query.</b> No database-side total over the balance: the interest job accumulates
 *       in memory per account so that the accumulation is unit-testable and matches {@code WS-TOTAL-INT}
 *       exactly, and pushing it into SQL would change both.</li>
 *   <li><b>No modifying bulk-update declaration and no database-resolved insert-or-update statement.</b>
 *       A single statement that inserted a row and fell back to updating it on key collision would
 *       collapse the two branches into one, but the branches are observably distinct in the source - they
 *       guard different verbs and emit different diagnostics - and the scoped create-or-update semantics
 *       must stay expressible, and therefore testable, at the service layer.</li>
 *   <li><b>No optimistic-locking version column and no method that depends on one.</b> The source performs
 *       no snapshot comparison against this table; the two-layer concurrency design applies to the account
 *       update path, not here. The entity carries no version member, so such a method could not work.</li>
 *   <li><b>No declarative transaction demarcation</b> - see the atomicity section above.</li>
 *   <li><b>No projection and no alternate-key finder</b> - the row has exactly two mapped members, so a
 *       projection could only restate it, and no alternate index exists.</li>
 *   <li><b>No JPQL or SQL string at all.</b> The one declared method is derived, so this file contains no
 *       query text, no string concatenation, no native-SQL escape hatch and nothing to bind. That is the
 *       strongest available form of "parameter binding only": there is no statement here for a value to be
 *       interpolated into.</li>
 *   </ul>
 *
 * <h2>The schema contract, and one construct the corpus does not ground</h2>
 *
 * <ol>
 *   <li><b>The three Flyway migrations fix this table's shape.</b>
 *       {@code V1__create_schema.sql} declares
 *       {@code CREATE TABLE transaction_category_balance} with {@code fk07_tcatbal_account}
 *       and {@code fk08_tcatbal_category}.
 *       Because {@code ddl-auto: validate} is mandated in every profile, the mapping this
 *       interface is typed over must match the DDL exactly, and it is restated here because it is what
 *       {@code V1} declares: table {@code transaction_category_balance}; a
 *       composite primary key over {@code (acct_id NUMERIC(11), tran_type_cd CHAR(2),
 *       tran_cat_cd NUMERIC(4))} in <b>that component order</b>; and the column
 *       {@code tran_cat_bal NUMERIC(11,2)}. Every column is {@code NOT NULL}. {@code V2} adds <b>no</b>
 *       index for this table. {@code V3} seeds <b>50 rows of 50 bytes each</b> from
 *       {@code app/data/ASCII/tcatbal.txt} with <b>position-aware</b> zoned-decimal overpunch decoding
 *       driven by the picture clauses - &#123; maps to +0, {@code A} through {@code I} map to +1 through
 *       +9, &#125; maps to -0, and {@code J} through {@code R} map to -1 through -9. That decoding must
 *       never be a global text replacement, because those same letters occur legitimately inside text
 *       fields elsewhere in the fixture set. A mismatch against any of the above aborts context startup
 *       rather than degrading gracefully, which is the intended and safest outcome.</li>
 *   <li><b>FILE STATUS {@code '35'} (file unavailable) has no grounding</b> as a source
 *       construct. No literal {@code '35'} occurs anywhere in {@code app/cbl}, and the {@code DFHRESP}
 *       census across the corpus is {@code NORMAL} 43, {@code NOTFND} 23, {@code ENDFILE} 8,
 *       {@code DUPREC} 7, {@code DUPKEY} 3 and <b>{@code NOTOPEN} 0</b>. The status is therefore
 *       specification-derived only, and {@code com.cardemo.exception.FileUnavailableException} has no
 *       originating call site in the corpus - including on this path.
 *       What would ground it is a source construct that actually raises it. There is none, so it is not
 *       asserted here.</li>
 *   </ol>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <table border="1">
 *   <caption>Diagnosing failures involving this repository</caption>
 *   <tr><th>Symptom</th><th>Cause</th><th>Remedy</th></tr>
 *   <tr>
 *     <td>Context fails at startup with a schema-validation error naming
 *         {@code transaction_category_balance}</td>
 *     <td>The migration disagrees with the mapping - most often {@code tran_cat_bal} widened to
 *         {@code NUMERIC(12,2)}, or the primary-key columns declared in a different order</td>
 *     <td>Correct the migration to the normative contract above. Do not "fix" the entity to match a wrong
 *         migration</td>
 *   </tr>
 *   <tr>
 *     <td>Context fails at startup with a property-resolution error naming this interface</td>
 *     <td>The derived method name no longer matches the identifier's properties, because a component of
 *         {@link TransactionCategoryBalanceId} was renamed</td>
 *     <td>Rename the segments of the method name to track {@code id.accountId}, {@code id.typeCd} and
 *         {@code id.catCd}, keeping all three and their order</td>
 *   </tr>
 *   <tr>
 *     <td>The posting job abends on the first transaction for a new account, type and category triple</td>
 *     <td>An empty {@code findById} result was translated into a not-found exception</td>
 *     <td>Treat the empty result as the create branch; exempt this call site in
 *         {@code FileStatusMapper}</td>
 *   </tr>
 *   <tr>
 *     <td>Balances drift high and never fall</td>
 *     <td>Absolute-value normalisation was applied to the amount somewhere on the posting path</td>
 *     <td>Remove it. Negative amounts must reduce the balance</td>
 *   </tr>
 *   <tr>
 *     <td>Interest is missing or wrong for some accounts, yet the job reports success</td>
 *     <td>The sequential read lost its ordering, so the control break fired mid-account. If the loss is
 *         confined to the <em>last</em> account of the run it is not a defect at all - see the
 *         no-final-flush note above</td>
 *     <td>Restore the three-component ordering. Do <b>not</b> add an end-of-data flush: the last account's
 *         interest is discarded by the frozen source too, and adding one breaks parity</td>
 *   </tr>
 *   <tr>
 *     <td>An equality assertion on a balance fails although the amounts look identical</td>
 *     <td>{@code BigDecimal.equals} compared a scale-2 value from the database against a literal at a
 *         different scale</td>
 *     <td>Assert with {@code compareTo}</td>
 *   </tr>
 * </table>
 *
 * @see TransactionCategoryBalance
 * @see TransactionCategoryBalanceId
 */
@Repository
public interface TransactionCategoryBalanceRepository
        extends JpaRepository<TransactionCategoryBalance, TransactionCategoryBalanceId> {

    /**
     * Reads the table in composite-key order, one chunk at a time: the replacement for the key-ordered VSAM
     * browse that the interest calculation job performs.
     *
     * <p>Reproduces {@code app/cbl/CBACT04C.cbl:L188-L222}, where the file is opened
     * {@code ACCESS MODE IS SEQUENTIAL} ({@code :L28-L32}) and read one record at a time through
     * {@code 1000-TCATBALF-GET-NEXT} until end of file, so that the account-level control break at
     * {@code :L194} can group each account's rows together.
     *
     * @param pageable the chunk index and size to read, unsorted because this method fixes its own ordering.
     * @return a {@link Slice} of rows in {@code id.accountId}, {@code id.typeCd}, {@code id.catCd} order, empty
     * once the traversal is exhausted
     */
    Slice<TransactionCategoryBalance> findAllByOrderByIdAccountIdAscIdTypeCdAscIdCatCdAsc(Pageable pageable);
}
