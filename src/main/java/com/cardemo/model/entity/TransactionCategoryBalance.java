/*
 * ******************************************************************
 * Program     : TransactionCategoryBalance.java
 * Application : CardDemo
 * Type        : Java JPA Entity
 * Function    : Replaces the VSAM KSDS cluster
 *               AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS, the per account, per
 *               transaction type, per transaction category running balance
 *               that the daily posting job upserts and that the interest
 *               calculation job browses in key order. Batch only: the
 *               cluster has no CICS file definition, so no online screen
 *               program ever opens it.
 * Source      : app/cpy/CVTRA01Y.cpy   (50 B, composite key 17)
 *               app/jcl/PRTCATBL.jcl   (SYMNAMES offset map, no COBOL
 *                                       program; corroborates the 17-byte
 *                                       key and the 11-digit balance)
 *                                                              @ 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
package com.cardemo.model.entity;

import com.cardemo.model.key.TransactionCategoryBalanceId;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreType;

import java.math.BigDecimal;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Transaction category balance: the relational replacement for the VSAM KSDS cluster
 * {@code AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS}.
 *
 * <p>Each row carries exactly one running balance, held against the triple (account, transaction type,
 * transaction category). The row is the accumulation point of the daily posting job and the input of the
 * interest calculation job, and it holds nothing else: one composite identifier and one signed decimal amount.
 * This class is a pure data holder. It performs no I/O, runs no business rule, emits no log, holds no
 * collaborator and has no static mutable state.
 *
 * <p>The two jobs that use it approach it differently, and both behaviours are properties of the row
 * rather than of this class:
 *
 * <ul>
 *   <li><b>Write side.</b> {@code app/cbl/CBTRN02C.cbl:L57-L61} declares the file
 *       {@code ORGANIZATION IS INDEXED} with {@code ACCESS MODE IS RANDOM}, and paragraph
 *       {@code 2700-UPDATE-TCATBAL} ({@code :L467-L501}) performs a keyed upsert per posted
 *       transaction. See the accepted-control-path section below, which is the single most important
 *       behavioural constraint on this file.</li>
 *   <li><b>Read side.</b> {@code app/cbl/CBACT04C.cbl:L28-L30} declares the same file with
 *       {@code ACCESS MODE IS SEQUENTIAL} and browses it in key order, detecting an account level
 *       control break at {@code :L194} ({@code IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM}). That break
 *       is the reason the composite key order is load bearing rather than cosmetic.</li>
 *   </ul>
 *
 * <p><b>How it is built, run and tested.</b> {@code ./mvnw -B clean compile} compiles this class under
 * {@code -Xlint:all -Werror} with {@code failOnWarning} set, so a lint finding in any category
 * {@code javac} 25 publishes is a hard build failure rather than a warning. An unused import is not such a
 * category and is forbidden by review instead. {@code ./mvnw -B clean test} runs
 * {@code unit/model/TransactionCategoryBalanceTest}, which pins the column contract, the precision tier
 * and the free-constructibility this class depends on, alongside
 * {@code unit/model/TransactionCategoryBalanceIdTest} for the composite key and the posting and interest
 * processor tests that exercise the upsert as a behaviour. {@code ./mvnw -B clean verify} adds
 * {@code integration/repository/TransactionCategoryBalanceRepositoryTest}, which validates the mapping
 * against a Testcontainers PostgreSQL 16 instance, and enforces the JaCoCo line coverage floor. There is
 * nothing to run directly: this type has no entry point and is reached only through the repository and
 * batch layers.
 *
 * <p><b>Key configuration and defaults.</b> This class configures nothing and reads no property. It
 * depends on exactly one setting owned elsewhere, {@code spring.jpa.hibernate.ddl-auto}, which the
 * project sets to {@code validate} in every profile. That choice is what turns any drift between the
 * column contract below and the Flyway schema into an application context startup failure instead of a
 * latent data fault, and it is why the column names, types, precisions and nullability in this file are
 * stated exactly rather than left to a naming strategy. No member has a default value: see the
 * accepted-control-path section for why inventing one would be wrong.
 *
 * <h2>Source contract</h2>
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
 * <p>Note the third key component is spelled {@code TRANCAT-CD} and <em>not</em> {@code TRANCAT-CAT-CD},
 * verified verbatim at {@code app/cpy/CVTRA01Y.cpy:L8}. The abbreviated spelling is a source quirk: the
 * sibling copybook {@code app/cpy/CVTRA04Y.cpy} spells its analogous field {@code TRAN-CAT-CD} in full.
 * The quirk is recorded rather than tidied, because the copybook is the field contract of record and the
 * frozen corpus is never edited.
 *
 * <h2>Record geometry: 17 byte key inside a 50 byte record</h2>
 *
 * <p>The arithmetic closes exactly, with no unexplained bytes:
 *
 * <pre>
 * key      TRANCAT-ACCT-ID  9(11)        11 bytes
 *          TRANCAT-TYPE-CD  X(02)      +  2 bytes
 *          TRANCAT-CD       9(04)      +  4 bytes
 *                                      = 17 bytes  composite key
 * payload  TRAN-CAT-BAL     S9(09)V99  + 11 bytes
 *                                      = 28 bytes  populated
 * padding  FILLER           X(22)      + 22 bytes
 *                                      = 50 bytes  catalogued record
 * </pre>
 *
 * <p>Four independent sources corroborate that geometry, so it is settled fact rather than inference:
 *
 * <ol>
 *   <li>Copybook arithmetic, as above, and the {@code RECLN = 50} header comment at
 *       {@code app/cpy/CVTRA01Y.cpy:L2}.</li>
 *   <li>{@code app/catlg/LISTCAT.txt:L1371} reads {@code KEYLEN 17} with {@code AVGLRECL 50} under the
 *       cluster {@code AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS} declared at
 *       {@code app/catlg/LISTCAT.txt:L1369}; {@code :L1372} reads {@code RKP 0} and
 *       {@code MAXLRECL 50}, so the key is the record prefix and the record is fixed width, not merely
 *       50 bytes on average.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L90-L97} declares the file description independently of the
 *       copybook: {@code FD-TRAN-CAT-KEY} with the identical 11 / 2 / 4 composition, followed by
 *       {@code FD-FD-TRAN-CAT-DATA PIC X(33)}. That trailing 33 is 11 balance bytes plus 22 filler
 *       bytes, and 17 + 33 = 50. This is a FILE-CONTROL level proof of the key width, the component
 *       order and the payload width at once. The doubled {@code FD-FD-} prefix is another source quirk,
 *       recorded and not corrected.</li>
 *   <li>The seed fixture {@code app/data/ASCII/tcatbal.txt} was measured, not assumed: 50 rows, 2550
 *       bytes, every line exactly 50 characters. Row 1 splits as
 *
 *       <pre>
 *       &lt;11 acct id&gt; &lt;2 type&gt; &lt;4 cat&gt; &lt;10 digits&gt;&#123; &lt;22 filler&gt;
 *       |            |        |       |              |
 *       11 acct      2        4       11 balance      22 filler       = 50
 *       </pre>
 *
 *       The trailing {@code &#123;} on the balance is a zoned decimal trailing sign overpunch meaning
 *       positive zero, so the eleven character field decodes to {@code +0.00}. An eleven character
 *       {@code S9(09)V99} field is nine integer digits plus two decimals, which independently confirms
 *       the precision tier asserted below. A census of the whole fixture found exactly 50 occurrences of
 *       that one overpunch character and no other, so every seeded balance is {@code +0.00} and the
 *       accumulation genuinely starts from zero.</li>
 *   </ol>
 *
 * <p>{@code FILLER} is deliberately <em>not</em> modelled. It carries no data; it exists only to pad the
 * record to the catalogued 50 bytes, and a relational row has no such requirement. Its 22 byte width is
 * recorded here so the arithmetic above can be checked, and nowhere else.
 *
 * <h2>Field mapping</h2>
 *
 * <pre>
 * COBOL field       PIC          this class    Java type                     column        SQL
 * ----------------  -----------  ------------  ----------------------------  ------------  -------------
 * TRAN-CAT-KEY      (group)      id            TransactionCategoryBalanceId  (see below)   composite PK
 *   TRANCAT-ACCT-ID 9(11)        id.accountId  Long                          acct_id       declared by
 *   TRANCAT-TYPE-CD X(02)        id.typeCd     String                        tran_type_cd  the key class,
 *   TRANCAT-CD      9(04)        id.catCd      Integer                       tran_cat_cd   not here
 * TRAN-CAT-BAL      S9(09)V99    balance       BigDecimal                    tran_cat_bal  NUMERIC(11,2)
 * FILLER            X(22)        -             -                             -             not modelled
 * </pre>
 *
 * <p>The table is {@code transaction_category_balance}. That name is not a free choice: it is already
 * fixed by the documented contract of {@link TransactionCategoryBalanceId}, and it must match character
 * for character or {@code ddl-auto: validate} fails at context startup.

 * <h2>Independent corroboration from {@code app/jcl/PRTCATBL.jcl}</h2>
 *
 * <p>The offset map and the balance precision above are confirmed a second time, by a member that is not a
 * copybook. {@code app/jcl/PRTCATBL.jcl} prints this cluster, and its {@code SYMNAMES} control cards declare
 * the record positions outright:
 *
 * <pre>
 * TRANCAT-ACCT-ID,1,11,ZD     bytes  1-11   eleven zoned-decimal digits
 * TRANCAT-TYPE-CD,12,2,CH     bytes 12-13   two characters
 * TRANCAT-CD,14,4,ZD          bytes 14-17   four zoned-decimal digits
 * TRAN-CAT-BAL,18,11,ZD       bytes 18-28   ELEVEN zoned-decimal digits
 * </pre>
 *
 * <p>Two facts follow that are worth more than the copybook alone. The three key components occupy bytes 1
 * through 17, which is the composite key length of 17 that {@code app/catlg/LISTCAT.txt} catalogues, derived
 * here from positions rather than from addition. And {@code TRAN-CAT-BAL} is <b>eleven</b> positions wide, not
 * twelve, which is the same {@code NUMERIC(11,2)} the precision section below insists on - stated by a DFSORT
 * symbol in a different file from the picture clause, so the two cannot both be wrong in the same way. The job's
 * {@code OUTREC} then edits that field as {@code EDIT=(TTTTTTTTT.TT)}, nine integer digits and two decimals,
 * which is the third independent statement of the same precision.
 *
 * <p><b>The job's own report step is implemented, in
 * {@link com.cardemo.batch.jobs.TransactionReportJob}.</b> {@code PRTCATBL.jcl} is three steps and no COBOL
 * program: {@code DELDEF} pre-deletes the output with {@code IEFBR14}, {@code STEP05R} unloads the cluster to
 * a {@code TCATBALF.BKUP(+1)} generation at {@code LRECL=50} through the shared {@code REPROC} procedure, and
 * {@code STEP10R} sorts by the composite key and emits a 40-byte edited line. Recording the member as having
 * no Java analogue, on the ground that a job, a reader and a writer outside the authored inventory would be
 * needed, is finding M-05: no new file is needed, because the member's
 * shape - back up, sort, print - is the shape of the report job already, so it is one gated step there and
 * {@code TCATBALF.BKUP} has a real producer and a real consumer rather than a declared prefix nothing
 * writes. Nothing in the member reads or writes any field this entity does not already declare, so it also
 * authorises the mapping above and independently corroborates the offsets and the precision.
 *
 * <h2>The balance is NUMERIC(11,2), never NUMERIC(12,2)</h2>
 *
 * <p>{@code TRAN-CAT-BAL} is {@code PIC S9(09)V99}: nine integer digits and two decimal digits, so
 * {@code precision = 11, scale = 2}. Widening it to {@code precision = 12} is the single most likely
 * error in this file, because the five money and cycle fields on the account entity are
 * {@code PIC S9(10)V99} and therefore genuinely are {@code NUMERIC(12,2)}. Three distinct precision
 * tiers exist across this package and must never be collapsed into one:
 *
 * <pre>
 * PIC clause    SQL             fields
 * ------------  --------------  -----------------------------------------------------------------
 * S9(10)V99     NUMERIC(12,2)   the five Account money and cycle fields
 * S9(09)V99     NUMERIC(11,2)   TRAN-AMT, DALYTRAN-AMT, and TRAN-CAT-BAL  &lt;-- this file
 * S9(04)V99     NUMERIC(6,2)    DIS-INT-RATE
 * </pre>
 *
 * <p>Under {@code ddl-auto: validate} a precision or scale mismatch aborts application context startup,
 * so the fault surfaces as a total outage on first boot. Where validation is not in force it is worse,
 * because it degrades into silent scale divergence: values round at a different digit than the source
 * system did, and no test that does not assert the scale will notice. Keep
 * {@code precision = 11, scale = 2} on the {@code balance} column and {@code tran_cat_bal NUMERIC(11,2)}
 * in the migration, both derived from the picture clause at {@code app/cpy/CVTRA01Y.cpy:L9} and never
 * from a neighbouring entity.
 *
 * <h2>Decimal discipline</h2>
 *
 * <p>The balance is a {@link BigDecimal} and nothing else. There is no {@code float} and no
 * {@code double} anywhere in this file, in any member, parameter, return type or local, because binary
 * floating point cannot represent ordinary decimal money exactly and the security audit gate asserts
 * their absence by inspection. Two further rules bind every caller and are stated here because this is
 * where the value is declared:
 *
 * <ul>
 *   <li><b>Compare with {@code compareTo}, never with {@code equals}.</b>
 *       {@link BigDecimal#equals(Object)} is scale sensitive, so a balance of {@code 2.0} is
 *       <em>not</em> equal to a balance of {@code 2.00} even though the amounts are identical. Only
 *       {@link BigDecimal#compareTo(BigDecimal)} answers the question a financial comparison is
 *       actually asking. This class does not compare the balance at all, precisely so that no such
 *       comparison is hidden inside it; see the equality section below.</li>
 *   <li><b>Round with {@code RoundingMode.HALF_EVEN}.</b> No arithmetic and therefore no rounding
 *       happens in this class, so {@code RoundingMode} is deliberately not imported. The rule is
 *       recorded here because the accumulation performed on this value by the batch layer must apply
 *       it, and because a reader looking for the project's rounding convention will look at the field
 *       that holds the money.</li>
 *   </ul>
 *
 * <p><b>The balance is signed and may legitimately be negative.</b> The posting job adds the transaction
 * amount to it at {@code app/cbl/CBTRN02C.cbl:L508} on the create path and {@code :L527} on the update
 * path, and transaction amounts are themselves signed: the daily transaction fixture carries both
 * positive and negative zoned decimal overpunch signs. There is consequently no {@code abs()}, no
 * {@code negate()}, no {@code @Positive}, no {@code @PositiveOrZero}, no {@code @Min} and no
 * absolute-value normalisation anywhere in this file. Any of those would silently discard the sign of a
 * credit and put the row permanently out of agreement with the source system.
 *
 * <h2>The key class owns every key column; this class restates none of them</h2>
 *
 * <p>{@link TransactionCategoryBalanceId} is an {@code @Embeddable} that already declares all three of
 * its own column mappings, in COBOL declaration order and each with an explicit
 * {@code nullable = false}. This class mounts that type through {@code @EmbeddedId} and adds exactly one
 * non-key column. It therefore deliberately does <em>not</em>:
 *
 * <ul>
 *   <li>declare {@code @AttributeOverride} or {@code @AttributeOverrides} for any component, because the
 *       key class's own column names are already the intended ones and an override would create a second
 *       place where they are defined;</li>
 *   <li>repeat a {@code @Column} for {@code acct_id}, {@code tran_type_cd} or {@code tran_cat_cd};</li>
 *   <li>re-declare the components as separate scalar fields, which would map the same three columns twice
 *       and fail either Hibernate's mapping phase or schema validation;</li>
 *   <li>add {@code @MapsId}, {@code @IdClass} or any second identity mechanism, because
 *       {@code @EmbeddedId} is already the whole identity;</li>
 *   <li>expose pass-through accessors such as a {@code getAccountId()} that reaches into the embedded
 *       identifier. Those would duplicate the key class's public surface and invite the two to diverge;
 *       callers read a component through {@code getId().getAccountId()} instead.</li>
 *   </ul>
 *
 * <p>Double mapping a column is a startup failure, not a runtime nuisance. The single import from a
 * sibling model package, {@code com.cardemo.model.key.TransactionCategoryBalanceId}, is the only coupling
 * this file needs; every key concern belongs behind it.
 *
 * <p>There is deliberately <b>no scalar {@code accountId} property here</b>, and that is a considered
 * difference from two sibling entities rather than an inconsistency. On the card and card cross reference
 * entities the account identifier is ordinary record payload, and the repository layer's derived finder
 * {@code findByAccountId} forces a scalar property spelled exactly that way. Here the account identifier
 * is a <em>key component</em>, so a repository reaches it through the embedded identifier path
 * ({@code id.accountId}) and no scalar duplicate is required or wanted. The two patterns look different
 * because the underlying records are different; they must not be unified.
 *
 * <p>The composite key's component order, account then type then category, is likewise not cosmetic. A
 * VSAM browse proceeds in key order, so the byte layout of the key <em>is</em> its sort order, and the
 * interest job's account level control break at {@code app/cbl/CBACT04C.cbl:L194} is correct only because
 * the account identifier leads. The order is fixed by the key class and by the primary key declaration in
 * the migration; nothing in this file may reorder it.
 *
 * <h2>A missing row is an accepted control path, so instances must be freely constructible</h2>
 *
 * <p>Paragraph {@code 2700-UPDATE-TCATBAL} at {@code app/cbl/CBTRN02C.cbl:L467-L501} is an upsert, not a
 * strict read. The sequence is exact and worth following, because it dictates this class's constructors:
 *
 * <ol>
 *   <li>{@code :L469-L471} populate the three key fields from the cross reference and the incoming daily
 *       transaction, <em>before</em> any read.</li>
 *   <li>{@code :L473} clears the create flag: {@code MOVE 'N' TO WS-CREATE-TRANCAT-REC}.</li>
 *   <li>{@code :L474-L479} read the record. On {@code INVALID KEY} the program displays
 *       {@code TCATBAL record not found for key : } followed by the key and {@code .. Creating.}, then
 *       sets {@code MOVE 'Y' TO WS-CREATE-TRANCAT-REC}.</li>
 *   <li>{@code :L481} is the decisive line: {@code IF TCATBALF-STATUS = '00' OR '23'}. Both "success"
 *       and "record not found" are treated as success. Any other status falls through to the abend
 *       guard.</li>
 *   <li>{@code :L495-L499} dispatch on the flag: {@code 2700-A-CREATE-TCATBAL-REC} for a new row,
 *       {@code 2700-B-UPDATE-TCATBAL-REC} for an existing one. The create branch initialises the record
 *       at {@code :L504}, moves the key at {@code :L505-L507} and adds the amount at {@code :L508}; the
 *       update branch adds the amount at {@code :L527}. Both branches accumulate identically.</li>
 *   </ol>
 *
 * <p><b>The leniency is scoped to the read guard alone, and this must not be overstated.</b> The write
 * verification at {@code app/cbl/CBTRN02C.cbl:L512} and the rewrite verification at {@code :L530} each
 * accept <em>only</em> {@code '00'}; anything else displays an error, renders the I/O status and abends.
 * This row is therefore tolerant of a missing record and of nothing else whatsoever.
 *
 * <p>Three consequences bind this file:
 *
 * <ul>
 *   <li>A <b>public all-argument constructor</b> exists, so an absent row can be constructed complete
 *       and inserted in one step, exactly as the create branch does.</li>
 *   <li>There is <b>no existence-dependent behaviour</b>: no {@code @PrePersist}, {@code @PreUpdate} or
 *       {@code @PostLoad} callback, and no "is new" flag. Whether a key resolves to an existing row is a
 *       question about the database at a moment in time, so the decision belongs to the batch or service
 *       layer that performs the lookup, never to the row itself.</li>
 *   <li>The balance is <b>not initialised to a default</b>, neither in the field declaration nor in the
 *       no-argument constructor. The caller supplies it. A field initialiser here would fabricate a value
 *       the source system never produced and would mask a caller that forgot to set one.</li>
 *   </ul>
 *
 * <p>This is one of only three places in the whole migration where "not found" is a legitimate outcome
 * rather than an error. The other two are the interest job's fallback to the default disclosure group
 * when the specific group is absent, and the statement file service's accepted secondary status.
 * Everywhere else a not-found status maps to an exception, which is why the exception mapper must know
 * about these three sites specifically rather than applying one blanket rule.
 *
 * <h2>Batch only, and no index beyond the primary key</h2>
 *
 * <p>{@code app/csd/CARDDEMO.CSD} defines exactly eight CICS file names: {@code ACCTDAT},
 * {@code CARDAIX}, {@code CARDDAT}, {@code CCXREF}, {@code CUSTDAT}, {@code CXACAIX}, {@code TRANSACT}
 * and {@code USRSEC}. {@code TCATBALF} is not among them, and a search of that file for the name returns
 * nothing. This dataset therefore has no online definition at all and is reached only from batch, as are
 * {@code DISCGRP}, {@code TRANCATG} and {@code TRANTYPE}. That shapes the authorisation model, since no
 * online role needs access to it, and the integration test surface, which is accordingly the batch and
 * repository tiers: {@code integration/repository/TransactionCategoryBalanceRepositoryTest} reaches this
 * table, and no REST path does.
 *
 * <p>It also has no alternate index. {@code app/catlg/LISTCAT.txt:L3938} reports {@code AIX 3} for the
 * whole catalogue, and all three belong to {@code CARDDATA}, {@code CARDXREF} and {@code TRANSACT}. The
 * migration consequently creates no index for this table beyond its primary key, and that is sufficient
 * rather than merely acceptable: because the composite key is ordered account, then type, then category,
 * primary key order alone already delivers the account-grouped sequence that the interest job's control
 * break at {@code app/cbl/CBACT04C.cbl:L188-L222} depends on. A secondary index would be dead weight
 * that slowed every posting write to speed up nothing.
 *
 * <h2>No associations, by design</h2>
 *
 * <p>This entity declares no {@code @ManyToOne} to the account, transaction type or transaction category
 * entities, no {@code @OneToMany}, no {@code @JoinColumn} and no cascade. The reasoning is threefold. The
 * legacy programs perform explicit keyed reads against separate files and never traverse a relationship,
 * so a mapped association would model a navigation the source system does not have. Referential integrity
 * belongs to the schema, where the migration's foreign keys enforce it for every writer rather than only
 * for this one. And an entity with no association cannot produce an N+1 select pattern, which matters
 * because the interest job walks every row of this table on every run: a lazy association here would turn
 * one sequential scan into one query per row.
 *
 * <h2>Serialization is deliberately not implemented</h2>
 *
 * <p>This entity does <b>not</b> implement {@code java.io.Serializable}, and that is a decision rather
 * than an omission. Jakarta Persistence requires serializability of a composite identifier class, which
 * is why {@link TransactionCategoryBalanceId} implements it and declares an explicit
 * {@code serialVersionUID}; it imposes no such requirement on the entity itself. Three reasons keep it
 * off this class. Java deserialization does not run constructors, so it can enforce none of the
 * invariants documented here, which is the classic insecure deserialization exposure and exactly the kind
 * of risky pattern worth naming explicitly. A serializable entity invites Java serialization to be used
 * as a cache or wire format, where a schema checked encoding such as JSON belongs instead. And adding the
 * interface without a {@code serialVersionUID} would trip the compiler's {@code serial} lint, which this
 * build promotes to a hard failure through {@code -Xlint:all -Werror}. No code path anywhere may feed
 * externally supplied bytes into an object stream that reconstructs this type.
 *
 * <h2>Equality, and why it is identifier based</h2>
 *
 * <p>{@code equals} and {@code hashCode} consider the {@code id} and nothing else. The balance is
 * excluded deliberately: it is mutable state that the posting job changes in place, so including it would
 * let an entity's hash code change while it sits in a hash based collection, and would make two
 * representations of the same database row compare unequal purely because one had been updated. Excluding
 * it also keeps the scale-sensitivity of {@link BigDecimal#equals(Object)} out of this class entirely,
 * which is why no relaxed {@code compareTo} based equality is offered here either. The identifier's own
 * value based semantics are supplied by {@link TransactionCategoryBalanceId}, so this class simply
 * delegates through {@link Objects#equals(Object, Object)} and adds nothing of its own.
 *
 * <h2>Rendering: this row carries no secret and no personal data</h2>
 *
 * <p>{@code toString} includes both the identifier and the balance, in full and unmasked. That is a
 * deliberate, checked decision rather than an oversight. The row contains an account identifier, a two
 * character type code, a four digit category code and a monetary amount; it holds no credential, no
 * password hash, no social security number, no cardholder name and no address, so there is nothing here
 * for the logging configuration's masking rules to protect. This is the opposite of the card, customer
 * and user security entities, whose renderings are deliberately restricted because those rows do carry
 * cardholder and credential material. The rendering is produced by plain concatenation, so it consults no
 * locale, charset or time zone and is byte identical on every machine.
 *
 * <h2>Schema reconciliation</h2>
 *
 * <p>{@code src/main/resources/db/migration/V1__create_schema.sql} declares
 * {@code CREATE TABLE transaction_category_balance}
 * with 4 columns whose names are identical, as a set, to this class's {@code @Column(name = ...)}
 * declaration taken together with the three components of
 * {@link com.cardemo.model.key.TransactionCategoryBalanceId}; the declared types and the component order
 * match the block reproduced below line for line. Because {@code spring.jpa.hibernate.ddl-auto} is
 * {@code validate} in all four profiles, any mismatch of column name, type, precision or nullability
 * fails application context startup outright, so the agreement matters and is not a formality.
 *
 * <p>Both later migrations exist. {@code V2__create_indexes.sql} creates nothing for this table beyond its
 * composite primary key, correctly, because {@code TCATBALF} has no alternate index in
 * {@code app/catlg/LISTCAT.txt} and the account-level control break of {@code CBACT04C} rides the primary
 * key's leading component. {@code V3__seed_data.sql} seeds it from {@code app/data/ASCII/tcatbal.txt}. What
 * {@code V1__create_schema.sql} declares, and what this mapping asserts, is precisely:
 *
 * <pre>
 * CREATE TABLE transaction_category_balance (
 *     acct_id       BIGINT        NOT NULL,   -- TRANCAT-ACCT-ID 9(11),     declared by the key class
 *     tran_type_cd  VARCHAR(2)    NOT NULL,   -- TRANCAT-TYPE-CD X(02),     declared by the key class
 *     tran_cat_cd   INTEGER       NOT NULL,   -- TRANCAT-CD      9(04),     declared by the key class
 *     tran_cat_bal  NUMERIC(11,2) NOT NULL,   -- TRAN-CAT-BAL    S9(09)V99, declared by THIS class
 *     CONSTRAINT pk_transaction_category_balance
 *         PRIMARY KEY (acct_id, tran_type_cd, tran_cat_cd)
 * );
 * </pre>
 *
 * <p>with, explicitly:
 *
 * <ul>
 *   <li>the composite primary key in <b>that exact COBOL field order</b>, account then type then
 *       category, which is what makes the interest job's account level control break work on primary key
 *       order alone and therefore what makes the absence of any further index correct. The order must be
 *       written out in the migration and never taken from provider generated DDL: Hibernate resolves an
 *       embeddable's attributes alphabetically, so the mapping metadata reports this key as
 *       {@code (acct_id, tran_cat_cd, tran_type_cd)}. That is harmless, because schema validation ignores
 *       primary key column order and the leading component is the account identifier under either
 *       ordering, and {@link TransactionCategoryBalanceId} records the measurement behind that
 *       conclusion;</li>
 *   <li><b>no version column</b>, because this entity carries no {@code @Version}. Optimistic locking is
 *       applied to exactly four entities in this package, the account, card, customer and transaction
 *       rows, and this is not one of them;</li>
 *   <li><b>no index beyond the primary key</b>, for the reason given above;</li>
 *   <li>seeding by {@code V3__seed_data.sql} from {@code app/data/ASCII/tcatbal.txt}, 50 rows of exactly
 *       50 bytes each, decoded with <b>position-aware zoned decimal overpunch handling driven by the
 *       picture clause</b> rather than by scanning for sign characters. The trailing {@code &#123;} in
 *       {@code 0000000000&#123;} means positive zero. Position awareness is not optional: the same letters
 *       that encode signs occur legitimately inside text fields elsewhere in the fixture set, so a
 *       character-scanning decoder corrupts data that a picture-clause-driven decoder handles correctly.
 *       In this particular fixture every one of the 50 rows carries that same positive zero overpunch, so
 *       a correct load produces 50 rows of {@code 0.00}, which is a cheap and exact assertion for the
 *       seed migration's own test.</li>
 *   </ul>
 *
 * <p>Two boundaries of that migration are worth stating so this contract is not read too broadly.
 * {@code V1__create_schema.sql} creates exactly 11 tables with 10 foreign keys and 5 check constraints;
 * this table is one of the 11. The Spring Batch {@code BATCH_*} metadata tables are <em>not</em> among
 * them and must not be added to {@code V1}: they come from the framework's own schema script. There is no
 * fourth migration.
 *
 * <p><b>One divergence between sources must be recorded rather than silently resolved.</b> A literal
 * reading of the picture clauses suggests {@code acct_id NUMERIC(11)}, {@code tran_type_cd CHAR(2)} and
 * {@code tran_cat_cd NUMERIC(4)} for the three key columns, and that is how the widths were originally
 * specified. The DDL above instead states {@code BIGINT}, {@code VARCHAR(2)} and {@code INTEGER},
 * following the contract that {@link TransactionCategoryBalanceId} declares and documents, because that
 * class records the outcome of executing schema validation against each pairing rather than predicting
 * it: {@code Long} over {@code NUMERIC(11)} fails validation where {@code Long} over {@code BIGINT}
 * passes, and {@code String} of length 2 over {@code CHAR(2)} fails, reported as
 * {@code found [bpchar (Types#CHAR)], but expecting [varchar(2) (Types#VARCHAR)]}, where
 * {@code VARCHAR(2)} passes. Either literal type would abort startup on first boot, and the failure would
 * appear to be in this entity while its actual cause is the column type, so the key class is
 * authoritative for the three key columns - exactly as this file treats it by declaring none of them -
 * and the picture-clause widths are reconciled only in documentation. The one column
 * this class owns is unaffected either way: {@code tran_cat_bal} is {@code NUMERIC(11,2)} under every
 * reading, since a {@link BigDecimal} of {@code precision = 11, scale = 2} pairs with
 * {@code NUMERIC(11,2)} and with nothing else.
 *
 * <p>That reconciliation was confirmed first hand rather than taken on trust. Building the persistence
 * mapping metadata for this entity against the PostgreSQL dialect resolves the four columns to
 * {@code acct_id bigint}, {@code tran_type_cd varchar(2)}, {@code tran_cat_cd integer} and
 * {@code tran_cat_bal numeric(11,2)}, reports the entity as not versioned, and reports exactly one
 * non-key property. Those are precisely the types the DDL above declares, so the contract in this file is
 * the one the provider will actually validate against.
 *
 * <h2>Error modes</h2>
 *
 * <p>No operation on this class throws, and none swallows anything either, because none of them can fail:
 * every member is a plain field read or write over an already constructed value. In particular the
 * accessors accept and return {@code null} rather than rejecting it, which is a deliberate, explicit
 * choice and not an omission. A key or a balance may legitimately be absent for an instance that is still
 * being assembled for the create branch described above, so rejecting {@code null} here would break the
 * accepted control path that this entity exists to support. The not-null requirement is real, but it
 * belongs at the boundary where it is actually enforceable and enforced: {@code nullable = false} on the
 * mapping and {@code NOT NULL} in the schema, which turn a missing value into a constraint violation the
 * database raises with full context, rather than into an exception thrown from a setter far from the
 * caller that mattered.
 *
 * <p><b>JSON serialisation barrier.</b> This class is structurally unserialisable by Jackson.
 * {@link JsonIgnoreType} removes any property whose declared type is this class from an enclosing object's
 * JSON, and {@link JsonAutoDetect} with every visibility set to {@code NONE} switches off bean
 * introspection entirely, so no getter, no setter, no field and no creator is discoverable. An entity is a
 * bean with public accessors, so without the barrier the default behaviour of returning this type from a
 * controller, or holding a field of it on a response object, is to publish a per-account, per-category
 * balance. The barrier is applied here even though this row carries no credential and no personal data,
 * for two reasons: the balance is customer financial data in its own right, and applying the barrier to
 * every entity in the package without exception is what makes the control checkable by inspection rather
 * than a judgement to be re-made each time an entity is added. With the barrier in place Jackson finds no
 * properties and its default {@code FAIL_ON_EMPTY_BEANS} setting turns the mistake into a loud failure
 * rather than a silent disclosure. Nothing legitimate is lost: this row has no outbound representation, it
 * is read and upserted by the posting and interest jobs, and persistence is unaffected because Hibernate
 * reads and writes the annotated fields reflectively and never consults Jackson visibility.
 *
 * @see TransactionCategoryBalanceId
 */
@Entity
@Table(name = "transaction_category_balance")
@JsonIgnoreType
@JsonAutoDetect(
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        setterVisibility = JsonAutoDetect.Visibility.NONE,
        creatorVisibility = JsonAutoDetect.Visibility.NONE,
        fieldVisibility = JsonAutoDetect.Visibility.NONE)
public class TransactionCategoryBalance {

    /**
     * Composite primary key: the COBOL group {@code TRAN-CAT-KEY} at {@code app/cpy/CVTRA01Y.cpy:L5}, 17 bytes
     * made of {@code TRANCAT-ACCT-ID PIC 9(11)}, {@code TRANCAT-TYPE-CD PIC X(02)} and
     * {@code TRANCAT-CD PIC 9(04)} in that order.
     */
    @EmbeddedId
    private TransactionCategoryBalanceId id;

    /**
     * Integer digit count of {@code TRAN-CAT-BAL PIC S9(09)V99} at {@code app/cpy/CVTRA01Y.cpy:L9}: nine.
     * This is the {@code S9(09)V99} tier, not the {@code S9(10)V99} tier the account entity's money fields
     * use, and the two must never be conflated.
     */
    private static final int BALANCE_INTEGER_DIGITS = 9;

    /**
     * Decimal digit count of {@code TRAN-CAT-BAL PIC S9(09)V99}: two, the digits after the implied
     * {@code V}.
     */
    private static final int BALANCE_SCALE = 2;

    /**
     * Total precision of the mapped column, nine integer digits plus two decimal digits, giving
     * {@code NUMERIC(11,2)}.
     */
    private static final int BALANCE_PRECISION = BALANCE_INTEGER_DIGITS + BALANCE_SCALE;

    /**
     * Largest value {@code TRAN-CAT-BAL PIC S9(09)V99} can represent, and equally the largest
     * {@code NUMERIC(11,2)} can hold. Constructed from a string literal rather than a {@code double} so
     * that the bound is exact.
     */
    private static final BigDecimal MAX_BALANCE = new BigDecimal("999999999.99");

    /**
     * Smallest value {@code TRAN-CAT-BAL PIC S9(09)V99} can represent. The picture clause carries an
     * {@code S}, so the domain is symmetric about zero and a negative balance is a legitimate outcome of
     * posting a credit.
     */
    private static final BigDecimal MIN_BALANCE = MAX_BALANCE.negate();

    /**
     * Running balance for this account, type and category triple:
     * {@code TRAN-CAT-BAL PIC S9(09)V99} at {@code app/cpy/CVTRA01Y.cpy:L9}, occupying 11 of the record's
     * 50 bytes.
     *
     * <p>Nine integer digits and two decimal digits give {@code precision = 11, scale = 2}, mapping to
     * {@code NUMERIC(11,2)}. This is the {@code S9(09)V99} tier and not the {@code S9(10)V99} tier that
     * the account entity's money fields use; the two must never be conflated, for the reasons set out on
     * the class.
     *
     * <p>The value is signed and may be negative, so no sign normalisation and no positivity constraint
     * is applied. It is deliberately left uninitialised: the daily posting job's create branch at
     * {@code app/cbl/CBTRN02C.cbl:L504-L508} initialises the record and then adds the transaction amount,
     * so the caller always supplies the opening value and a field initialiser here would fabricate one.
     */
    @Column(name = "tran_cat_bal", nullable = false,
            precision = BALANCE_PRECISION, scale = BALANCE_SCALE)
    private BigDecimal balance;

    /**
     * Creates an empty instance with both members unset.
     */
    protected TransactionCategoryBalance() {
        // Intentionally empty: the provider writes the mapped fields reflectively after construction.
    }

    /**
     * Creates a fully populated row from its composite key and its balance.
     *
     * <p>This constructor is what makes the daily posting job's accepted "record not found" control path
     * expressible in one step. When the keyed read at {@code app/cbl/CBTRN02C.cbl:L474-L479} reports
     * {@code INVALID KEY}, {@code :L481} accepts that status as success and {@code :L495-L499} dispatch
     * to the create branch, which builds a complete record and writes it. The Java equivalent constructs
     * an instance here and hands it to the repository, with no intermediate half-built state and no "is
     * new" flag anywhere.
     *
     * <p>Both arguments are validated, and the validation is bounded by the record layout and by nothing
     * else. {@code null} is rejected on both, because both columns are {@code NOT NULL} and because the
     * source cannot produce a null: {@code TRAN-CAT-KEY} is 17 fixed bytes and {@code TRAN-CAT-BAL} is a
     * packed numeric that always holds a value. The balance is additionally required to fit
     * {@code S9(09)V99} - nine integer digits, two decimal digits - because a wider value would be
     * silently rounded or rejected at the {@code NUMERIC(11,2)} column and the resulting diagnostic would
     * name only a column. <strong>The sign is untouched and zero and negative balances are accepted</strong>,
     * the picture clause being signed.
     *
     * <p>Rejecting {@code null} does not narrow the accepted "record not found" control path by one row.
     * That path concerns whether the <em>row</em> exists, not whether the <em>instance</em> is complete:
     * {@code app/cbl/CBTRN02C.cbl:L504-L508} initialises a complete record and writes it, so a half-built
     * instance is not something the source produces at any point.
     *
     * @param id      the composite key, {@code TRAN-CAT-KEY} 17 bytes as account, type and category; must
     *                not be {@code null}
     * @param balance the running balance, {@code TRAN-CAT-BAL PIC S9(09)V99}, signed and possibly
     *                negative; must not be {@code null}, must have at most two decimal digits, and must
     *                lie between -999999999.99 and 999999999.99 inclusive
     * @throws IllegalArgumentException if either argument is {@code null}, or if {@code balance} carries
     *                                  more than two decimal digits or falls outside the range
     *                                  {@code S9(09)V99} can represent
     */
    public TransactionCategoryBalance(TransactionCategoryBalanceId id, BigDecimal balance) {
        // Both checks are private static helpers, so this constructor invokes no overridable method and
        // cannot publish a partially built instance; JPA forbids a final entity, so -Xlint:all -Werror
        // would otherwise report this-escape.
        this.id = requireId(id);
        this.balance = requireBalance(balance);
    }

    /**
     * Returns the composite key, the 17-byte COBOL group {@code TRAN-CAT-KEY}.
     *
     * @return the composite key, never {@code null} on an instance built through
     *         {@link #TransactionCategoryBalance(TransactionCategoryBalanceId, BigDecimal)};
     *         {@code null} only on an instance the persistence provider has not finished populating
     */
    public TransactionCategoryBalanceId getId() {
        return id;
    }

    /**
     * Replaces the composite key.
     *
     * @param id the composite key, {@code TRAN-CAT-KEY} as account, type and category.
     */
    public void setId(TransactionCategoryBalanceId id) {
        this.id = requireId(id);
    }

    /**
     * Returns the running balance, {@code TRAN-CAT-BAL PIC S9(09)V99}, mapped as {@code NUMERIC(11,2)}.
     *
     * @return the running balance, never {@code null} on an instance built through
     *         {@link #TransactionCategoryBalance(TransactionCategoryBalanceId, BigDecimal)};
     *         {@code null} only on an instance the persistence provider has not finished populating
     */
    public BigDecimal getBalance() {
        return balance;
    }

    /**
     * Replaces the running balance.
     *
     * @param balance the running balance, {@code TRAN-CAT-BAL PIC S9(09)V99}, signed and possibly negative.
     */
    public void setBalance(BigDecimal balance) {
        this.balance = requireBalance(balance);
    }

    /**
     * Compares two rows by composite key alone.
     *
     * @param other the object to compare against, possibly {@code null}
     * @return {@code true} if {@code other} is a row of exactly this class with an equal composite key
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        return Objects.equals(id, ((TransactionCategoryBalance) other).id);
    }

    /**
     * Returns a hash derived from the composite key alone, consistent with {@link #equals(Object)}.
     *
     * @return the key based hash code
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    /**
     * Returns a diagnostic rendering of the composite key alone.
     *
     * <p>The key appears in full and unmasked, which is safe and checked: it is an account identifier, a
     * two character type code and a four digit category code, and it carries no credential, no password
     * hash, no social security number, no cardholder name and no address. It is also the only value here
     * that identifies which row a log line refers to, which is the sole purpose of this rendering.
     *
     * <p><b>The balance is excluded, and a masking rule is not what keeps it out.</b> {@code balance} and
     * {@code TRAN-CAT-BAL} are masked paths in {@code src/main/resources/logback-spring.xml}, but that
     * rule keys on a JSON field name while this rendering interpolates the value into free text, where
     * there is no field name to key on. A value-shaped rule cannot close the gap either: an unlabelled
     * decimal is indistinguishable from the preserved counter and total {@code DISPLAY} reproductions the
     * same file protects from over-redaction. Omission at source has to do the work, because the value is
     * customer financial data and this rendering already carries the account identifier - a log estate
     * holding both holds a per-account, per-category balance ledger that joins straight back to the row,
     * reconstructable with no database access and no authorisation. A reader who needs a balance should
     * read the row, where the access is authorised and audited.
     *
     * <p>Intended for diagnostics only and not a stable log or wire format. The rendering is plain
     * concatenation, so it consults no locale, charset or time zone and is identical on every machine.
     *
     * @return a rendering of the form {@code TransactionCategoryBalance[id=...]}, never containing the
     *         balance
     */
    @Override
    public String toString() {
        return "TransactionCategoryBalance[id=" + id + "]";
    }

    /**
     * Validates a candidate composite key and returns it unchanged.
     *
     * <p>Rejects {@code null} only. The three components inside the key are validated by
     * {@link TransactionCategoryBalanceId} itself, which is the single definition of its own surface, so
     * nothing about them is re-checked here.
     *
     * <p>Declared {@code private static} so that the constructor can call it without invoking an
     * overridable method, which would otherwise publish a partially initialised instance; the JPA
     * specification forbids a final entity, so the hazard is real and {@code -Xlint:all -Werror} reports
     * it as {@code this-escape}.
     *
     * @param value the candidate key, possibly {@code null}
     * @return {@code value}, unchanged
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    private static TransactionCategoryBalanceId requireId(TransactionCategoryBalanceId value) {
        if (value == null) {
            throw new IllegalArgumentException("id (TRAN-CAT-KEY, 17 bytes at app/cpy/CVTRA01Y.cpy:L5) "
                    + "must not be null: it is the composite primary key of table "
                    + "transaction_category_balance");
        }
        return value;
    }

    /**
     * Validates a candidate balance against the domain {@code TRAN-CAT-BAL PIC S9(09)V99} can represent
     * and returns it unchanged.
     *
     * <p>Three things are checked and nothing else. {@code null} is rejected, because the column is
     * {@code NOT NULL} and a packed numeric field always holds a value. A scale greater than two is
     * rejected, because {@code V99} declares exactly two decimal positions and the
     * {@code NUMERIC(11,2)} column would round a wider value half away from zero instead of refusing it.
     * A magnitude outside -999999999.99 through 999999999.99 is rejected, because nine unsigned integer
     * digits cannot hold it.
     *
     * <p><strong>What is deliberately not checked:</strong> the sign, since the picture clause carries an
     * {@code S} and the daily posting job legitimately drives this balance negative; zero, which is the
     * opening value of every newly created row; and a scale smaller than two, since {@code 2} and
     * {@code 2.00} denote the same amount and the column stores either as two decimal places. The value
     * is never rescaled, normalised or rounded here.
     *
     * <p>Declared {@code private static} for the reason given on
     * {@link #requireId(TransactionCategoryBalanceId)}.
     *
     * @param value the candidate balance, possibly {@code null}
     * @return {@code value}, unchanged and unrescaled
     * @throws IllegalArgumentException if {@code value} is {@code null}, has more than two decimal digits,
     *                                  or falls outside the representable range
     */
    private static BigDecimal requireBalance(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("balance (TRAN-CAT-BAL PIC S9(09)V99 at "
                    + "app/cpy/CVTRA01Y.cpy:L9) must not be null: it maps to a NOT NULL NUMERIC("
                    + BALANCE_PRECISION + "," + BALANCE_SCALE + ") column of table "
                    + "transaction_category_balance");
        }
        if (value.scale() > BALANCE_SCALE) {
            throw new IllegalArgumentException("balance (TRAN-CAT-BAL PIC S9(09)V99) must carry at most "
                    + BALANCE_SCALE + " decimal digits but had a scale of " + value.scale()
                    + "; rescale it explicitly with RoundingMode.HALF_EVEN rather than letting the "
                    + "NUMERIC(" + BALANCE_PRECISION + "," + BALANCE_SCALE + ") column round it");
        }
        if (value.compareTo(MIN_BALANCE) < 0 || value.compareTo(MAX_BALANCE) > 0) {
            throw new IllegalArgumentException("balance (TRAN-CAT-BAL PIC S9(09)V99) must be between "
                    + MIN_BALANCE.toPlainString() + " and " + MAX_BALANCE.toPlainString()
                    + " inclusive, which is what " + BALANCE_INTEGER_DIGITS
                    + " signed integer digits can hold, but was " + value.toPlainString());
        }
        return value;
    }
}
