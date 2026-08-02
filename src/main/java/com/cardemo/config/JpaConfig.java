/*
 * ******************************************************************
 * Program     : JpaConfig.java
 * Application : CardDemo
 * Type        : Java Spring Configuration (persistence layer)
 * Function    : Relational substrate configuration - entity scanning,
 *               naming strategy and transaction management replacing
 *               the VSAM catalogue and the IDCAMS DEFINE CLUSTER jobs.
 * Source      : app/catlg/LISTCAT.txt (3,956 lines; 10 base clusters,
 *               3 alternate indexes, 3 matching paths)
 *               + app/jcl/DUSRSECJ.jcl (USRSEC KEYS(8,0) RECORDSIZE(80,80))
 *               + app/cpy/CVTRA01Y.cpy + app/cpy/CVTRA02Y.cpy
 *               + app/cpy/CVTRA05Y.cpy (field precisions)
 *               + app/cbl/COACTUPC.cbl (4,236 lines; snapshot comparison,
 *                 asymmetric SYNCPOINT ROLLBACK)
 *               + app/cbl/CBTRN02C.cbl (731 lines; three-write posting unit)
 *               @ 7756d89
 * Replaces    : the VSAM KSDS clusters, alternate indexes and paths, and
 *               the 12 IDCAMS DEFINE CLUSTER provisioning jobs
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
package com.cardemo.config;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Persistence-layer configuration for the CardDemo modular monolith, and the single written record of the
 * relational substrate contract that replaced the VSAM catalogue.
 *
 * <p>Two distinct jobs live here, and separating them is the point of the class. The first is
 * <strong>executable</strong>: one bean that proves, at startup and before any request or batch step runs,
 * that the mechanisms which enforce field-contract parity are actually armed. The second is
 * <strong>documentary</strong>: the decisions that bind every entity, migration and repository in the tree
 * are recorded below with the catalogue, copybook, job-control and program locators that establish them, so
 * a reader never has to reverse-engineer a precision or a column type from the generated schema.
 *
 * <h2>What it does</h2>
 *
 * <p>It declares exactly one bean, {@link #persistenceContractGuard()}, which invokes
 * {@link #verifyPersistenceContract()} during context refresh. That method asserts nine configuration
 * invariants and throws {@link IllegalStateException} on the first violation, so a misconfigured
 * application refuses to start rather than starting and quietly reshaping the schema.
 *
 * <p>The guard exists because the enforcement mechanism it protects is itself only a property.
 * {@code spring.jpa.hibernate.ddl-auto} is set to {@code validate} in the base profile, and that single
 * value is what turns a divergence between an entity mapping and the migration-owned schema into a
 * deterministic startup failure. Nothing in the framework prevents a profile, an environment override or a
 * command-line argument from changing it to {@code update} or {@code create}, at which point the mapping
 * silently becomes authoritative over the copybook-derived column widths and parity is lost without a
 * single error being reported. The same argument applies to {@code spring.flyway.clean-disabled}, whose own
 * declaration site carries the comment that it is destructive and must stay true in every profile including
 * test. A guard that verifies the guard is therefore not ceremony; it is the only thing standing between an
 * override and a silent loss of the field contract.
 *
 * <h2>What it deliberately does not declare</h2>
 *
 * <p>Each omission below is a decision with a reason, not an oversight. Rule 1 Clause B forbids dead code,
 * and configuration that has no effect is dead code that merely looks authoritative.
 *
 * <ul>
 *   <li><strong>No {@code @EnableJpaRepositories}, {@code @EntityScan} or
 *       {@code @EnableTransactionManagement}.</strong> The entities sit in
 *       {@code com.cardemo.model.entity} and the repositories in {@code com.cardemo.repository}, both
 *       beneath the {@code com.cardemo} base package that {@code com.cardemo.CardDemoApplication}
 *       establishes. Auto-configuration discovers both without help, so each of those annotations would
 *       restate a default and none would change a single resolved bean.</li>
 *   <li><strong>No physical naming strategy bean.</strong> A naming strategy only ever resolves
 *       <em>implicit</em> names, and there are none: all eleven entities name their table with
 *       {@code @Table(name = ...)} and every persistent field names its column with
 *       {@code @Column(name = ...)}. The names are copybook-derived and are stated explicitly precisely so
 *       that no strategy sits between the copybook and the column. Installing one would be inert on today's
 *       mappings and would silently start deriving names the day someone omitted a {@code @Column}.</li>
 *   <li><strong>No second data source, entity manager factory, transaction manager or transaction
 *       template.</strong> Auto-configuration supplies all four from the resolved properties. A second one
 *       would introduce an ambiguity that has to be resolved with a primary marker, for no gain.</li>
 *   <li><strong>No schema, table, index, constraint or key definition of any kind.</strong> The schema is
 *       owned by {@code src/main/resources/db/migration} and by nothing else. Restating a column type here
 *       would create a second place to change it, which is the duplication Rule 1 Clause C prohibits. The
 *       precisions recorded below are cited as <em>documentation of</em> that schema, never as a definition
 *       of it.</li>
 *   <li><strong>No fourth migration.</strong> Exactly three exist. The Spring Batch metadata tables come
 *       from the framework's own schema script by way of
 *       {@code spring.batch.jdbc.initialize-schema}, and adding them as a migration would break the
 *       validation gate that counts eleven tables in the first migration.</li>
 *   <li><strong>No clock, no object-store client, no security filter chain, no observability registry and
 *       no web binding.</strong> Those belong to the sibling configuration classes of this package. This
 *       one is scoped to persistence.</li>
 * </ul>
 *
 * <h2>The ten base clusters this schema replaces</h2>
 *
 * <p>{@code app/catlg/LISTCAT.txt} is 3,956 lines and is the authoritative physical specification. It
 * catalogues exactly ten base clusters, each introduced by a {@code 0CLUSTER} header, and the key length
 * and average record length of each is the origin of the corresponding primary key and column budget. The
 * locators are the cluster header line, then the attribute line carrying {@code KEYLEN} and
 * {@code AVGLRECL}:
 *
 * <ul>
 *   <li>{@code ACCTDATA} header :L22, key 11 / record 300 :L59 - the account row</li>
 *   <li>{@code CARDDATA} header :L164, key 16 / record 150 :L202 - the card row</li>
 *   <li>{@code CARDXREF} header :L365, key 16 / record 50 :L403 - the card cross-reference row</li>
 *   <li>{@code CUSTDATA} header :L595, key 9 / record 500 :L632 - the customer row</li>
 *   <li>{@code DISCGRP} header :L859, key 16 / record 50 :L896 - the disclosure group row</li>
 *   <li>{@code TCATBALF} header :L1334, key 17 / record 50 :L1371 - the transaction category balance row,
 *       whose key length of 17 is the eleven-digit account identifier plus the two-character type code plus
 *       the four-digit category code</li>
 *   <li>{@code TRANCATG} header :L1440, key 6 / record 60 :L1475 - the transaction category row</li>
 *   <li>{@code TRANSACT} header :L3555, key 16 / record 350 :L3593 - the transaction row</li>
 *   <li>{@code TRANTYPE} header :L3742, key 2 / record 60 :L3779 - the transaction type row</li>
 *   <li>{@code USRSEC} header :L3846, key 8 / record 80 :L3883, with the same 80 restated as the maximum
 *       record length on the following line :L3884 - the user security row</li>
 * </ul>
 *
 * <p>Three of the ten carry independent job-control corroboration in the members named on this file's
 * banner: {@code app/jcl/ACCTFILE.jcl:L40-L41} declares {@code KEYS(11 0)} and
 * {@code RECORDSIZE(300 300)}, {@code app/jcl/TCATBALF.jcl:L40-L41} declares {@code KEYS(17 0)} and
 * {@code RECORDSIZE(50 50)}, and {@code app/jcl/DISCGRP.jcl:L40-L41} declares {@code KEYS(16 0)} and
 * {@code RECORDSIZE(50 50)}. Each agrees with the catalogue exactly, which is what makes the catalogue
 * usable as a specification rather than a report.
 *
 * <p><strong>Finding, severity Medium - a documentary correction.</strong> Narrative elsewhere in the
 * project states that {@code USRSEC} is defined in job control rather than catalogued. That is not what the
 * sources show. {@code USRSEC} <em>is</em> catalogued, at {@code app/catlg/LISTCAT.txt:L3846} with
 * {@code KEYLEN 8} and {@code AVGLRECL 80} at {@code :L3883-L3884}, and it is <em>also</em> defined in job
 * control at {@code app/jcl/DUSRSECJ.jcl:L64-L68}, which issues a cluster definition for
 * {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS} with {@code KEYS(8,0)}, {@code RECORDSIZE(80,80)},
 * {@code REUSE} and {@code INDEXED}. Both sources exist and they agree. Remediation: cite both, which this
 * paragraph does, and treat the eight-character key and eighty-byte record as doubly attested rather than
 * inferred.
 *
 * <h2>The three alternate indexes and their three paths</h2>
 *
 * <p>The catalogue records exactly three alternate indexes, each introduced by a {@code 0AIX} header, and
 * exactly three matching paths, each introduced by a {@code 0PATH} header. Three, not two. Each alternate
 * index becomes one non-unique index in the second migration, and the {@code AXRKP} value is the byte
 * offset of the alternate key within the base record, which is what identifies the column:
 *
 * <ul>
 *   <li>{@code CARDDATA.VSAM.AIX} :L254 - {@code KEYLEN 11} :L281, {@code RKP 5} :L282,
 *       <strong>{@code AXRKP 16}</strong> :L283. The alternate key sits at byte 16 of the 150-byte card
 *       record, which is the account identifier, so this becomes the card-by-account finder.</li>
 *   <li>{@code CARDXREF.VSAM.AIX} :L455 - {@code KEYLEN 11} :L482, {@code RKP 5} :L485,
 *       <strong>{@code AXRKP 25}</strong> :L486. This becomes the cross-reference-by-account finder.
 *       Narrative elsewhere gives a different offset for this one; 25 is what the catalogue says, and
 *       {@code :L486} is the line that says it. The catalogue governs.</li>
 *   <li>{@code TRANSACT.VSAM.AIX} :L3645 - {@code KEYLEN 26} :L3674, {@code RKP 5} :L3675,
 *       <strong>{@code AXRKP 304}</strong> :L3676. Offset 304 with a key length of 26 is the processing
 *       timestamp, which occupies bytes 305 to 330 of the 350-byte transaction record under the offset map
 *       recorded further below, so this becomes the processing-timestamp finder.</li>
 * </ul>
 *
 * <p>The paths are {@code CARDDATA.VSAM.AIX.PATH} :L150, {@code CARDXREF.VSAM.AIX.PATH} :L351 and
 * {@code TRANSACT.VSAM.AIX.PATH} :L3541. That the batch tier really consumed a path, rather than merely
 * having one defined, is corroborated at {@code app/jcl/INTCALC.jcl:L31-L32}: the interest job declares
 * {@code XREFFIL1} onto {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH} while {@code XREFFILE} on the two
 * preceding lines is already allocated to the base cluster. One job, one cluster, two allocations - the
 * second reached through the alternate-index path. That is the access pattern a secondary index has to
 * serve.
 *
 * <p>All three alternate indexes are marked {@code NONUNIQKEY} in the catalogue, so all three become
 * <strong>non-unique</strong> indexes. Exactly three exist and a fourth is not added anywhere: an index the
 * catalogue does not attest would be an invention, and the second migration's own census asserts the count.
 *
 * <h2>Numeric precision is bound to the source PIC clauses</h2>
 *
 * <p>Three distinct precisions occur, and they are not interchangeable. Each is read directly from the
 * picture clause of the field it carries, because a widened column accepts a value the legacy system would
 * have truncated and a narrowed one rejects a value the legacy system stored:
 *
 * <dl>
 *   <dt>The five account money fields, {@code NUMERIC(12,2)}</dt>
 *   <dd>{@code ACCT-CURR-BAL}, {@code ACCT-CREDIT-LIMIT} and {@code ACCT-CASH-CREDIT-LIMIT} at
 *       {@code app/cpy/CVACT01Y.cpy:L7-L9}, then {@code ACCT-CURR-CYC-CREDIT} and
 *       {@code ACCT-CURR-CYC-DEBIT} at {@code :L13-L14}, are each {@code PIC S9(10)V99}. Ten integer digits
 *       plus two decimal digits is a total precision of twelve at a scale of two.</dd>
 *   <dt>The transaction amount and the category balance, {@code NUMERIC(11,2)} and <em>not</em> 12,2</dt>
 *   <dd>{@code TRAN-AMT} at {@code app/cpy/CVTRA05Y.cpy:L10} and {@code TRAN-CAT-BAL} at
 *       {@code app/cpy/CVTRA01Y.cpy:L9} are both {@code PIC S9(09)V99}. Nine integer digits, not ten, so
 *       eleven and two. Reusing the account precision here would be the single easiest error to make in
 *       this schema and the hardest to notice, because every realistic test value fits comfortably in
 *       both.</dd>
 *   <dt>The disclosure interest rate, {@code NUMERIC(6,2)}</dt>
 *   <dd>{@code DIS-INT-RATE} at {@code app/cpy/CVTRA02Y.cpy:L9} is {@code PIC S9(04)V99} - four integer
 *       digits and two decimals. It is the only field in the corpus at this precision.</dd>
 * </dl>
 *
 * <p>Every one of those fields is a {@code java.math.BigDecimal} in Java and a {@code NUMERIC} column in
 * the schema. <strong>No binary IEEE-754 type appears in any financial field anywhere in the tree</strong>,
 * which a validation gate asserts by inspection. Rounding is {@code RoundingMode.HALF_EVEN} throughout, and
 * value equality is tested with {@code compareTo} and never with {@code equals}, because
 * {@code BigDecimal.equals} is scale-sensitive and reports a two-scale and a one-scale representation of the
 * same quantity as different.
 *
 * <p>The enforcement mechanism for all of this is {@code spring.jpa.hibernate.ddl-auto} at {@code validate},
 * and it matters to be exact about how much of the contract that mechanism actually covers. On every start
 * the provider compares the mapped columns against the migrated schema and refuses to build the entity
 * manager factory when a mapped table or column is absent, or when a column's type differs. The check is
 * deterministic and runs before the first request, which is why {@link #verifyPersistenceContract()} asserts
 * that the property still holds the value {@code validate}.
 *
 * <p>That coverage was measured rather than assumed. Against PostgreSQL 16.10 with the two shipped
 * migrations applied, all eleven entity mappings validate and the context starts; changing
 * {@code tran_orig_ts} on the transaction table from a fixed-width to a variable-width character type was
 * rejected; and dropping {@code tran_amt} outright was rejected.
 *
 * <h3>What validate does not cover - severity Medium</h3>
 *
 * <p><strong>Numeric precision and scale are not checked.</strong> In the same measurement, widening
 * {@code tran_amt} from eleven digits to twelve while leaving the entity mapping untouched was
 * <em>accepted</em>. The provider's schema validator compares a column's type, not its declared precision,
 * so the three precisions above are <strong>not</strong> machine-enforced at startup. This project's own
 * specification asserts that any precision divergence fails startup deterministically; for the existence
 * and type cases that holds, for precision and scale it does not, and the measured behaviour is recorded
 * here in preference to the unverified claim.
 *
 * <p>Consequence and remediation. A precision divergence would not announce itself at startup. It would
 * surface later and far more quietly, as a rounding difference or as an overflow on a value the copybook
 * field can represent and the column cannot - which is precisely the class of silent parity defect this
 * migration exists to avoid. Three controls carry that weight in place of the provider: the migration DDL is
 * the single source of truth for every column's precision; each entity declares its precision and scale
 * explicitly beside the PIC clause it derives from; and the parity tests compare computed money values
 * against the frozen fixtures. The residual risk is disclosed here rather than absorbed.
 *
 * <h2>The two 26-byte timestamp columns are character data - severity Blocker</h2>
 *
 * <p>{@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} are declared {@code PIC X(26)} at
 * {@code app/cpy/CVTRA05Y.cpy:L16-L17}, and {@code TRNX-ORIG-TS} and {@code TRNX-PROC-TS} likewise at
 * {@code app/cpy/COSTM01.CPY:L34-L35} - note the uppercase extension and that the member is
 * {@code COSTM01}, not {@code COSTM01Y}. They are mapped to {@code java.lang.String} over a 26-character
 * fixed-width column, and <strong>to no temporal type</strong>: not a date-time, not a timestamp, not a
 * point-on-the-timeline and not a zoned or offset variant of any of those.
 *
 * <p>The reason is that three mutually incompatible producers write into that one field, so no single parse
 * can round-trip all three:
 *
 * <ol>
 *   <li>The batch generator emits millisecond precision followed by four literal zero digits, in a form
 *       that separates the date from the time with a hyphen and the time components with dots.</li>
 *   <li>The online generator emits a space between date and time, colons between the time components, and
 *       six zero digits of sub-second text.</li>
 *   <li><strong>The posting job passes bytes straight through, unvalidated.</strong>
 *       {@code app/cbl/CBTRN02C.cbl:L436} moves the incoming daily-transaction originating timestamp into
 *       the transaction record verbatim. Whatever 26 bytes arrived in the input file are what get stored,
 *       whether or not they parse as a moment in time at all.</li>
 * </ol>
 *
 * <p>Mapping the column to a temporal type would force normalisation at the boundary, and normalisation is
 * exactly what parity forbids: the four trailing zero digits of form one are compared byte for byte against
 * the legacy baseline, and a temporal type would render them from whatever precision it happened to keep.
 * Remediation, and the standing instruction: leave these columns as fixed-width character data, and let the
 * only interpretation happen where a specific consumer needs it, on a value it has already validated.
 *
 * <p>The offset map of the 350-byte transaction record, one-based, is what makes that fixed-width boundary
 * reproducible. Derived field by field from {@code app/cpy/CVTRA05Y.cpy}: identifier 1-16, type 17-18,
 * category 19-22, source 23-32, description 33-132, amount 133-143, merchant identifier 144-152, merchant
 * name 153-202, merchant city 203-252, merchant postcode 253-262, card number 263-278, originating
 * timestamp 279-304, processing timestamp 305-330, filler 331-350. Two of those offsets are independently
 * corroborated by the sort symbol definitions at {@code app/proc/TRANREPT.prc:L39-L40}, which declare the
 * card number at offset 263 for 16 zoned-decimal characters and the processing date at offset 305 for 10
 * characters. The second of those also explains the alternate-index offset of 304 recorded above: a
 * zero-based relative key position of 304 is the one-based byte 305.
 *
 * <h2>Two-layer optimistic concurrency - a version column alone is insufficient</h2>
 *
 * <p>This is the one place in the migration where the obvious mechanical translation is wrong. The
 * comparison itself belongs to {@code com.cardemo.service.account.AccountUpdateService}; the decision that
 * both layers are required belongs here, because it is a property of the substrate.
 *
 * <p>{@code 9700-CHECK-CHANGE-IN-REC} at {@code app/cbl/COACTUPC.cbl:L4109-L4195} compares the freshly read
 * record against a snapshot captured when the screen was first populated. The snapshot lives in
 * {@code ACUP-OLD-DETAILS} at {@code :L669-L756}, alongside {@code ACUP-NEW-DETAILS} at {@code :L757}. On
 * any mismatch the paragraph sets a data-was-changed condition and abandons the write, at
 * {@code :L4143-L4144} for the account block and {@code :L4189-L4190} for the customer block.
 *
 * <p>A version column detects <em>that</em> a row changed. The source detects <em>which business fields</em>
 * changed, and in what representation. The two guarantees are genuinely different, and the difference is
 * observable: a concurrent write that sets a field and then restores its original value passes the legacy
 * check and fails a version check. <strong>Both layers are therefore mandatory and neither substitutes for
 * the other.</strong> Because the target is stateless the snapshot cannot live on the server between
 * requests, which is why {@code com.cardemo.model.dto.AccountUpdateRequest} carries both the old and the new
 * detail groups in the request body.
 *
 * <p>The store-level layer is a version column on exactly four entities - {@code com.cardemo.model.entity.Account},
 * {@code com.cardemo.model.entity.Card}, {@code com.cardemo.model.entity.Customer} and
 * {@code com.cardemo.model.entity.Transaction} - matching the four version columns the first migration
 * creates. {@code com.cardemo.model.entity.TransactionCategoryBalance} carries none, and
 * {@code com.cardemo.model.entity.UserSecurity} carries none.
 *
 * <p>Three characteristics of the business-level comparison are each easy to translate wrongly, and all
 * three are verified rather than assumed:
 *
 * <ul>
 *   <li><strong>Dates are compared as three separate substrings, never as whole strings.</strong> The open
 *       date at {@code :L4127-L4129}, the expiry date at {@code :L4131-L4133} and the reissue date at
 *       {@code :L4135-L4137} are each compared by year, then month, then day, against discrete snapshot
 *       fields. The expiry field name is misspelled in the copybook at
 *       {@code app/cpy/CVACT01Y.cpy:L11}; the misspelling is part of the field contract and is preserved.</li>
 *   <li><strong>Case handling is deliberately asymmetric.</strong> The account group identifier is compared
 *       through a lower-casing function on both sides at {@code :L4139-L4140}, and it is the only field
 *       treated that way. The customer first, middle and last names at {@code :L4152-L4157}, the three
 *       address lines at {@code :L4158-L4163}, the state code at {@code :L4164}, the country code at
 *       {@code :L4166} and the government-issued identifier at {@code :L4172} are compared through an
 *       upper-casing function on both sides. The postal code at {@code :L4168}, both telephone numbers at
 *       {@code :L4169-L4170}, the social security number at {@code :L4171}, the funds-transfer account
 *       identifier at {@code :L4181}, the primary-holder indicator at {@code :L4183} and the credit score
 *       at {@code :L4186} are compared with no case function at all. Normalising in either direction
 *       changes which updates are accepted.</li>
 *   <li><strong>The date-of-birth comparison uses different offsets on each side.</strong> The live
 *       customer record holds a separated date, so its components begin at offsets 1, 6 and 9. The snapshot
 *       holds the same date without separators, so its components begin at 1, 5 and 7. The source compares
 *       1 against 1, 6 against 5 and 9 against 7, at {@code :L4174-L4179}, and the snapshot is populated
 *       component-wise at {@code :L3857-L3859}. The whole-string alternative is present in the source as a
 *       commented-out line at {@code :L3856}, which is direct evidence that the author considered and
 *       rejected it. <strong>A naive whole-string comparison reports a change on every single request</strong>,
 *       making the endpoint permanently unusable.</li>
 * </ul>
 *
 * <p><strong>Finding, severity Low - a measurement correction.</strong> Narrative elsewhere describes this
 * paragraph as comparing twelve account predicates. Measured at {@code 7756d89} the account block spans
 * {@code :L4115-L4140} and compares <em>ten fields</em> rendered as <em>sixteen</em> conjoined clauses,
 * because three of the ten are dates compared component-wise; the customer block at {@code :L4152-L4186}
 * compares seventeen fields as nineteen clauses. Any single flat count rounds one of those two figures.
 * Remediation: cite the locators, which are the measurement, rather than a count.
 *
 * <h2>The transaction boundary and the asymmetric rollback</h2>
 *
 * <p>The standard boundary for a write path in this application is a service method annotated
 * {@code @Transactional(rollbackFor = Exception.class)}, scoped so that the source's asymmetric rollback is
 * reproduced automatically, with no conditional logic anywhere in Java.
 *
 * <p>{@code 9600-WRITE-PROCESSING} in {@code app/cbl/COACTUPC.cbl} rewrites the account and then the
 * customer, and handles the two failures differently:
 *
 * <ul>
 *   <li>The account rewrite failure branch at {@code :L4076-L4081} sets the locked-but-update-failed
 *       condition at {@code :L4079} and transfers to the exit at {@code :L4080}, <strong>with no
 *       rollback</strong>.</li>
 *   <li>The customer rewrite failure branch at {@code :L4095-L4102} sets the <em>same</em> condition at
 *       {@code :L4098}, then issues an explicit synchronisation-point rollback at {@code :L4099-L4101}, and
 *       only then transfers to the same exit at {@code :L4102}.</li>
 *   <li>That exit label is {@code 9600-WRITE-PROCESSING-EXIT} at {@code :L4104-L4105}.</li>
 * </ul>
 *
 * <p><strong>The asymmetry is correct and must not be tidied away.</strong> At the account-rewrite failure
 * point nothing has yet been written inside the unit of work, so the transaction monitor releases the
 * read-for-update locks at task end without any explicit action being needed. At the customer-rewrite
 * failure point the account rewrite has already occurred inside that same unit of work, so an explicit
 * backout is the only way to avoid leaving a half-applied update behind.
 *
 * <p>A single Java transactional method reproduces <em>both</em> branches automatically, because each
 * failure path returns or throws before the commit point: the first has nothing to undo and undoes nothing,
 * the second has the account write to undo and the boundary undoes it. This is recorded in the project
 * decision log as a <strong>mechanism substitution, not a behaviour change</strong>, for a specific reason.
 * A reviewer reading the two sources side by side will see a rollback statement in the source with no
 * literal counterpart in the Java, and without that entry the natural conclusion is that something was
 * lost. Remediation for that reviewer: the counterpart is the transaction boundary itself, and the
 * behaviour to verify is that a failed customer write leaves the account row unchanged.
 *
 * <p>Four outcome conditions are declared at {@code app/cbl/COACTUPC.cbl:L517-L523} - account lock failure,
 * customer lock failure, data changed before update, and locked but update failed - with a fifth marker at
 * {@code :L667}. Each is distinguishable at the interface layer, because collapsing them into one conflict
 * response would discard information the legacy screen displayed.
 *
 * <h2>Why a modular monolith and not microservices</h2>
 *
 * <p>Atomicity decides it. Two units of work in the source span more than one dataset, and neither can be
 * split across a service boundary without replacing a transaction with a compensating action, which changes
 * failure semantics and therefore forfeits parity:
 *
 * <ul>
 *   <li>The account update writes an account row and a customer row in one unit of work, at
 *       {@code app/cbl/COACTUPC.cbl:L4098-L4102}.</li>
 *   <li>The daily posting routine at {@code app/cbl/CBTRN02C.cbl:L424-L465} performs a
 *       transaction-category-balance upsert at {@code :L440}, an account update at {@code :L441} and a
 *       transaction insert at {@code :L442}, which the Java target commits together.</li>
 * </ul>
 *
 * <p>Hence one deployable artefact, one data source, one transaction manager.
 *
 * <p><strong>Finding, severity Medium - a labelled deviation, not parity.</strong> The source performs those
 * three posting writes as three independent commits. Reject code 109 is assigned on the account-rewrite
 * failure path at {@code app/cbl/CBTRN02C.cbl:L556-L558}, but that path lies inside the already-validated
 * posting routine, so no reject record is written, the reject count is not incremented, execution continues
 * to the transaction write, and the value is cleared on the next iteration at {@code :L208}. The legacy
 * outcome is therefore an orphaned category-balance row and an orphaned transaction row against an account
 * that was never updated. Collapsing the three commits into one atomic Java transaction closes that hazard
 * as a side effect. That is a genuine behavioural improvement rather than parity, so it is labelled a
 * deviation in the decision log rather than presented as equivalence. Remediation: none - the improvement is
 * intended; the requirement is that it be disclosed, which this paragraph does.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>Every key below is <strong>owned by</strong> {@code src/main/resources/application.yml} and its profile
 * siblings. This class reads them and asserts them; it defines none of them, and changing a value here would
 * be impossible by design because no value is written here. The "framework default" column matters: in six of
 * the nine cases the value this application needs is <em>not</em> the framework's default, which is precisely
 * why an override is dangerous and why the guard exists.
 *
 * <dl>
 *   <dt>{@code spring.jpa.hibernate.ddl-auto} - required value {@code validate}</dt>
 *   <dd>Asserted. The migrations own the schema; {@code validate} is what turns a missing table, a missing
 *       column or a changed column type into a startup failure. It does <em>not</em> cover numeric precision
 *       or scale, for the measured reason given above. {@code create}, {@code create-drop} and
 *       {@code update} are each forbidden, because each would let the provider reshape a table away from the
 *       copybook record layouts silently.</dd>
 *   <dt>{@code spring.jpa.open-in-view} - required value {@code false}</dt>
 *   <dd>Asserted. The framework default is {@code true}, which would hold a connection open for the whole
 *       request and hide lazy access behind the view layer.</dd>
 *   <dt>{@code spring.jpa.show-sql} - required value {@code false}</dt>
 *   <dd>Asserted. Statement and bind-parameter logging would expose the customer social security number and
 *       the stored password hashes of the ten seeded users. Rule 1 Clause D admits no secrets in logs. The
 *       provider's own statement and bind loggers are additionally held at warning level by the logging
 *       configuration, so raising the provider package to debug during an investigation still cannot print
 *       a bound value.</dd>
 *   <dt>{@code spring.jpa.properties.hibernate.jdbc.time_zone} - required value {@code UTC}</dt>
 *   <dd>Asserted. Sessions run in coordinated universal time so that a server's local zone can never shift
 *       a stored value. This affects genuine temporal columns only; it has no bearing on the two 26-byte
 *       character timestamp columns discussed above, which is worth stating because the key's name suggests
 *       otherwise.</dd>
 *   <dt>{@code spring.flyway.enabled} - required value {@code true}</dt>
 *   <dd>Asserted. With migration disabled there is no schema at all, and {@code validate} would then fail
 *       with a confusing missing-table diagnostic instead of the real cause.</dd>
 *   <dt>{@code spring.flyway.baseline-on-migrate} - required value {@code false}</dt>
 *   <dd>Asserted. Baselining would silently adopt an unknown pre-existing schema as the starting point,
 *       which defeats the entire field-contract argument.</dd>
 *   <dt>{@code spring.flyway.validate-on-migrate} - required value {@code true}</dt>
 *   <dd>Asserted. Detects a checksum change to an already-applied migration, which is the signal that
 *       someone edited history rather than adding to it.</dd>
 *   <dt>{@code spring.flyway.clean-disabled} - required value {@code true}</dt>
 *   <dd>Asserted. The operation it disables destroys every object in the schema. Its declaration site
 *       carries the standing instruction that it must remain enabled in every profile, test included.</dd>
 *   <dt>{@code spring.flyway.out-of-order} - required value {@code false}</dt>
 *   <dd>Asserted. Ordered application is load-bearing: the third migration seeds rows that rely on the
 *       indexes the second one creates.</dd>
 *   <dt>{@code spring.flyway.locations} - value {@code classpath:db/migration}</dt>
 *   <dd><strong>Documented but deliberately not asserted.</strong> It is a list-typed property, so its YAML
 *       shape may legitimately be a scalar or a sequence, and a scalar-only assertion would fail a
 *       perfectly valid sequence form. Asserting it would introduce a false-positive class into a guard
 *       whose whole value is that it never cries wolf. The related invariant - that exactly three
 *       migrations exist, no more - is asserted by the project's gate verification test, which can count
 *       classpath resources rather than inspect a single property.</dd>
 *   <dt>{@code spring.batch.jdbc.initialize-schema} - value {@code never} in the base and production
 *       profiles, {@code always} in the local and test profiles</dt>
 *   <dd><strong>Documented but deliberately not asserted</strong>, because it is legitimately
 *       profile-dependent and no single value is correct everywhere. It is recorded here because it is the
 *       reason there is no fourth migration: the batch metadata tables come from the framework's own schema
 *       script, so the migration set stays at three and the eleven-table count in the first migration
 *       stays provable.</dd>
 * </dl>
 *
 * <p>Related settings that are owned elsewhere and are not this class's to police: statement batching and
 * insert and update ordering on the provider, the migration history table name and encoding, the transaction
 * isolation used when the batch metadata tables are created, and the fact that jobs do not auto-launch on
 * startup. The data-source coordinates are supplied entirely through environment-indirected properties; no
 * connection string, user name or password is written, logged or quoted anywhere in this class.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>All commands run from the repository root and use the version-pinned wrapper, so no preinstalled build
 * tool is assumed. No command below contains an absolute host path.
 *
 * <ul>
 *   <li>Compile: {@code ./mvnw -B -ntp clean compile}. The compiler runs with all lint categories enabled
 *       and warnings escalated to errors, so a warning introduced here fails the build.</li>
 *   <li>Unit suite: {@code ./mvnw -B -ntp test}. The behaviour of {@link #verifyPersistenceContract()} is
 *       unit-testable without a container and without a Spring context: construct this class directly with
 *       the nine string arguments and invoke the method.</li>
 *   <li>Full verification: {@code ./mvnw -B -ntp clean verify}, which additionally runs the integration
 *       tier and the coverage and vulnerability gates.</li>
 *   <li>Runtime dependencies: {@code docker compose up -d} brings up the database and the remaining local
 *       services. The migrations run on first boot and take ownership of an empty schema, so the database
 *       must not be pre-migrated by hand - a hand-applied schema produces a checksum mismatch the moment a
 *       migration changes, and the disabled clean operation deliberately leaves no quick way out.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>Startup fails with a message from {@link #verifyPersistenceContract()} naming a property</dt>
 *   <dd>The named property has been overridden to a value the persistence contract forbids. The message
 *       states the key, the value found and the value required. Restore the required value rather than
 *       relaxing the guard: every one of the nine exists to stop a specific silent failure, each documented
 *       above. If the value came from an environment override or a command-line argument, that override is
 *       the defect.</dd>
 *   <dt>Startup fails with a message that a property is not configured</dt>
 *   <dd>The base configuration file was not on the classpath, or the key was removed from it. This is most
 *       often a narrowly sliced test that loaded a property source without the base profile. Loading the
 *       base configuration, or supplying the key explicitly in the slice, resolves it.</dd>
 *   <dt>The provider reports a missing table, a missing column, or a wrong column type at startup</dt>
 *   <dd>This is the validate mechanism working as designed: an entity mapping and the migrated schema have
 *       diverged. Decide which of the two is wrong by reading the copybook the field derives from, then fix
 *       that one. <strong>Never switch the mapping mode to update or create to make the message go
 *       away</strong> - that hides the divergence and lets the provider rewrite the column away from the
 *       record layout.</dd>
 *   <dt>A money value rounds or overflows unexpectedly, and startup reported nothing at all</dt>
 *   <dd>Suspect a precision or scale divergence, which the validate pass is measured not to catch. Almost
 *       always it is one of the three precisions above applied to the wrong field; check first whether an
 *       eleven-and-two field has been given the twelve-and-two of the account money columns. That is the
 *       most common instance and the hardest to see in a test, because ordinary values fit in both and only
 *       a boundary value separates them. Compare the entity's declared precision against the column in the
 *       schema migration and against the PIC clause in the copybook, in that order.</dd>
 *   <dt>Migration fails with a checksum mismatch</dt>
 *   <dd>An already-applied migration was edited. Migrations are append-only once applied. Revert the edit
 *       and add a new migration, or recreate the database from empty in a disposable local environment.</dd>
 *   <dt>An update silently does nothing, or reports a conflict on every attempt</dt>
 *   <dd>The business-level snapshot comparison, not the version column. Check the three characteristics
 *       recorded above: component-wise date comparison, the deliberate case asymmetry, and the
 *       date-of-birth offsets. A conflict on <em>every</em> request is the signature of a whole-string
 *       date-of-birth comparison.</dd>
 * </dl>
 *
 * <h2>Deferred hardening and residual risk</h2>
 *
 * <p>Disclosed rather than silently absorbed, as Rule 1 Clause A requires of a tradeoff. None of these is
 * implemented, and none is a defect - each is a scoped-out decision with a stated consequence:
 *
 * <ul>
 *   <li><strong>Connection-pool tuning.</strong> The pool ships at its own defaults, resolved through the
 *       parent dependency management. Sizing it needs a measured concurrency profile, and the consequence of
 *       leaving it is that pool exhaustion under an unmeasured load would present as request latency rather
 *       than as an error.</li>
 *   <li><strong>Table partitioning.</strong> The transaction table grows without bound. Partitioning needs a
 *       retention policy that the source does not state.</li>
 *   <li><strong>Read replicas.</strong> One data source serves reads and writes, which the single-transaction
 *       atomicity argument above makes the simplest correct arrangement.</li>
 *   <li><strong>Encryption at rest for personally identifiable data.</strong> The customer row carries a
 *       social security number and a date of birth in clear columns, mirroring the source layout. Column
 *       encryption would change the stored representation and therefore the parity comparison, so it is a
 *       deliberate deferral and not an oversight.</li>
 * </ul>
 *
 * <p><strong>Performance targets: {@code Not available}.</strong> No service-level objective for throughput,
 * latency or concurrency exists anywhere in the source corpus, so none is asserted here and none may be
 * invented. What would be needed to state one: a measured baseline from the target system under a defined
 * load, and a stakeholder-agreed objective to compare it against. Until both exist, the project records a
 * measurement rather than a target.
 *
 * <h2>Findings register</h2>
 *
 * <p>Collected for convenience; each is developed in full at its own section above.
 *
 * <ul>
 *   <li><strong>Blocker</strong> - the two 26-byte timestamp columns are character data and must never be
 *       mapped to a temporal type, because one of their three producers passes bytes through unvalidated.
 *       Remediation: keep the fixed-width character mapping.</li>
 *   <li><strong>Medium</strong> - the three-commit posting sequence becomes one atomic transaction, which
 *       closes an orphaned-row hazard the source has. Remediation: none; disclose as a deviation.</li>
 *   <li><strong>Medium</strong> - {@code USRSEC} is catalogued <em>and</em> defined in job control, contrary
 *       to narrative that says job control only. Remediation: cite both sources.</li>
 *   <li><strong>Medium</strong> - the {@code validate} pass does <em>not</em> enforce numeric precision or
 *       scale, contrary to the specification's claim that any precision divergence fails startup. Measured
 *       against PostgreSQL 16.10: a missing column and a changed column type are both rejected, while
 *       widening a money column from eleven digits to twelve is accepted. Remediation: rely on the migration
 *       DDL as the single source of truth, on the explicit precision and scale declared beside each PIC
 *       clause, and on the parity tests; disclose the residual gap rather than assume the provider covers
 *       it.</li>
 *   <li><strong>Low</strong> - the account snapshot comparison measures ten fields as sixteen clauses, not a
 *       flat twelve. Remediation: cite locators rather than a count.</li>
 *   <li><strong>Low</strong> - {@code app/jcl/DEFCUST.jcl:L35-L38} defines an orphan cluster
 *       {@code AWS.CUSTDATA.CLUSTER} with {@code KEYS(10 0)} and {@code RECORDSIZE(500 500)} that
 *       <strong>no program opens</strong>, and which the same member's earlier step pairs with a deletion of
 *       a differently named cluster at {@code :L25}. It has no target in this schema and is recorded so that
 *       its absence is a decision rather than an omission: the customer row derives from the catalogued
 *       {@code CUSTDATA} cluster, key 9 and record 500, not from this one. Remediation: none required; do
 *       not model it.</li>
 * </ul>
 */
@Configuration
public class JpaConfig {

    /** Property key: the schema management mode the provider applies at startup. */
    private static final String KEY_DDL_AUTO = "spring.jpa.hibernate.ddl-auto";

    /** Property key: whether a persistence session stays open for the whole web request. */
    private static final String KEY_OPEN_IN_VIEW = "spring.jpa.open-in-view";

    /** Property key: whether the provider echoes generated statements. */
    private static final String KEY_SHOW_SQL = "spring.jpa.show-sql";

    /** Property key: the time zone the provider uses for temporal binding. */
    private static final String KEY_HIBERNATE_TIME_ZONE = "spring.jpa.properties.hibernate.jdbc.time_zone";

    /** Property key: whether schema migration runs at all. */
    private static final String KEY_FLYWAY_ENABLED = "spring.flyway.enabled";

    /** Property key: whether an unknown pre-existing schema is adopted as a baseline. */
    private static final String KEY_FLYWAY_BASELINE_ON_MIGRATE = "spring.flyway.baseline-on-migrate";

    /** Property key: whether applied migrations are checksum-validated on every start. */
    private static final String KEY_FLYWAY_VALIDATE_ON_MIGRATE = "spring.flyway.validate-on-migrate";

    /** Property key: whether the destructive schema-clean operation is refused. */
    private static final String KEY_FLYWAY_CLEAN_DISABLED = "spring.flyway.clean-disabled";

    /** Property key: whether a migration may be applied after a higher-numbered one. */
    private static final String KEY_FLYWAY_OUT_OF_ORDER = "spring.flyway.out-of-order";

    /**
     * The only schema management mode this application tolerates. The migrations own the schema and the
     * provider's role is to disagree loudly, never to reshape a table away from its copybook layout.
     */
    private static final String REQUIRED_DDL_AUTO = "validate";

    /** The only provider time zone this application tolerates. */
    private static final String REQUIRED_TIME_ZONE = "UTC";

    /** Lower-cased literal for a true boolean, compared under {@link Locale#ROOT}. */
    private static final String TRUE_LITERAL = "true";

    /** Lower-cased literal for a false boolean, compared under {@link Locale#ROOT}. */
    private static final String FALSE_LITERAL = "false";

    private static final Logger LOG = LoggerFactory.getLogger(JpaConfig.class);

    private final String ddlAuto;
    private final String hibernateTimeZone;
    private final boolean openInView;
    private final boolean showSql;
    private final boolean flywayEnabled;
    private final boolean flywayBaselineOnMigrate;
    private final boolean flywayValidateOnMigrate;
    private final boolean flywayCleanDisabled;
    private final boolean flywayOutOfOrder;

    /**
     * Binds the nine persistence-contract properties and validates that each one is present and, where it is
     * a flag, parseable. Values are resolved by property binding alone; no environment variable is read
     * directly and no host path is consulted, so the resolved configuration is identical on every machine
     * that supplies the same property sources.
     *
     * <p>Each parameter is declared with an empty fallback rather than no fallback on purpose. Without a
     * fallback an absent key fails placeholder resolution with a generic message that names the placeholder
     * but explains nothing; with one, the absent key arrives here as an empty string and this constructor
     * raises a message that names the key, states that it is not configured, and says which file owns it.
     * That is the difference between a diagnosable failure and a puzzling one, and it is why the empty
     * fallback is not a way of tolerating absence - absence still stops the application, one frame earlier
     * and far more clearly.
     *
     * <p>Presence and parseability are checked here because that is where the value enters the object.
     * Whether a present, well-formed value is <em>permitted</em> is a separate question, decided by
     * {@link #verifyPersistenceContract()}: reading configuration and enforcing policy are different
     * concerns and are kept apart deliberately.
     *
     * <p>Only private static helpers are invoked, so no partially initialised instance is ever exposed to an
     * overridable method - a real consideration because a configuration class cannot be final.
     *
     * @param ddlAutoProperty                 raw value of {@code spring.jpa.hibernate.ddl-auto}
     * @param openInViewProperty              raw value of {@code spring.jpa.open-in-view}
     * @param showSqlProperty                 raw value of {@code spring.jpa.show-sql}
     * @param hibernateTimeZoneProperty       raw value of
     *                                        {@code spring.jpa.properties.hibernate.jdbc.time_zone}
     * @param flywayEnabledProperty           raw value of {@code spring.flyway.enabled}
     * @param flywayBaselineOnMigrateProperty raw value of {@code spring.flyway.baseline-on-migrate}
     * @param flywayValidateOnMigrateProperty raw value of {@code spring.flyway.validate-on-migrate}
     * @param flywayCleanDisabledProperty     raw value of {@code spring.flyway.clean-disabled}
     * @param flywayOutOfOrderProperty        raw value of {@code spring.flyway.out-of-order}
     * @throws IllegalStateException if any of the nine is absent, blank, or - for the seven flags - is
     *                               neither {@code true} nor {@code false} when compared under
     *                               {@link Locale#ROOT}
     */
    public JpaConfig(
            @Value("${" + KEY_DDL_AUTO + ":}") final String ddlAutoProperty,
            @Value("${" + KEY_OPEN_IN_VIEW + ":}") final String openInViewProperty,
            @Value("${" + KEY_SHOW_SQL + ":}") final String showSqlProperty,
            @Value("${" + KEY_HIBERNATE_TIME_ZONE + ":}") final String hibernateTimeZoneProperty,
            @Value("${" + KEY_FLYWAY_ENABLED + ":}") final String flywayEnabledProperty,
            @Value("${" + KEY_FLYWAY_BASELINE_ON_MIGRATE + ":}") final String flywayBaselineOnMigrateProperty,
            @Value("${" + KEY_FLYWAY_VALIDATE_ON_MIGRATE + ":}") final String flywayValidateOnMigrateProperty,
            @Value("${" + KEY_FLYWAY_CLEAN_DISABLED + ":}") final String flywayCleanDisabledProperty,
            @Value("${" + KEY_FLYWAY_OUT_OF_ORDER + ":}") final String flywayOutOfOrderProperty) {

        this.ddlAuto = requireConfigured(KEY_DDL_AUTO, ddlAutoProperty);
        this.hibernateTimeZone = requireConfigured(KEY_HIBERNATE_TIME_ZONE, hibernateTimeZoneProperty);
        this.openInView = requireFlag(KEY_OPEN_IN_VIEW, openInViewProperty);
        this.showSql = requireFlag(KEY_SHOW_SQL, showSqlProperty);
        this.flywayEnabled = requireFlag(KEY_FLYWAY_ENABLED, flywayEnabledProperty);
        this.flywayBaselineOnMigrate =
                requireFlag(KEY_FLYWAY_BASELINE_ON_MIGRATE, flywayBaselineOnMigrateProperty);
        this.flywayValidateOnMigrate =
                requireFlag(KEY_FLYWAY_VALIDATE_ON_MIGRATE, flywayValidateOnMigrateProperty);
        this.flywayCleanDisabled = requireFlag(KEY_FLYWAY_CLEAN_DISABLED, flywayCleanDisabledProperty);
        this.flywayOutOfOrder = requireFlag(KEY_FLYWAY_OUT_OF_ORDER, flywayOutOfOrderProperty);
    }

    /**
     * Asserts the nine persistence-contract invariants, and is the one piece of executable behaviour this
     * class contributes.
     *
     * <p>Inputs are the nine values bound by the constructor; there are no parameters and no other state is
     * read. The only side effect on success is a single informational log record naming every verified value,
     * which exists so that an operator reading a startup log can see what the substrate was actually
     * configured with rather than inferring it. Every value in that record is a configuration flag - a mode
     * name, a time-zone identifier or a boolean - and none is a credential, a connection coordinate or a
     * bound statement parameter, so the record is safe to emit at an ordinary logging level.
     *
     * <p>The checks run in a fixed order, from the mapping mode outward, so that the first message an
     * operator sees is the most consequential violation rather than whichever check happened to run first.
     * Each raises {@link IllegalStateException} with the key, the value found, the value required and the
     * reason the invariant exists. The exception is unchecked and is intentionally not one of the project's
     * own exception types: those model the translation of legacy file-status outcomes on data-access paths,
     * whereas this is a configuration defect detected before any data path exists, and it belongs to the
     * container's bean-initialisation failure channel where the framework will wrap it with the bean name and
     * the full context. Nothing is caught here, so no cause is ever discarded.
     *
     * <p>This method is idempotent and free of external interaction: it neither opens a connection nor reads
     * a file, which is what makes it callable directly from a unit test with plain string arguments.
     *
     * @throws IllegalStateException on the first invariant that does not hold
     */
    public void verifyPersistenceContract() {
        requireValue(KEY_DDL_AUTO, this.ddlAuto, REQUIRED_DDL_AUTO,
                "the migrations own the schema and the provider must reject a divergence, never reshape a "
                        + "table away from its copybook record layout");
        requireValue(KEY_HIBERNATE_TIME_ZONE, this.hibernateTimeZone, REQUIRED_TIME_ZONE,
                "temporal binding must not depend on the host time zone");
        requireState(KEY_OPEN_IN_VIEW, this.openInView, false,
                "a session must not stay open for the whole request, holding a connection and hiding lazy "
                        + "access behind the view layer");
        requireState(KEY_SHOW_SQL, this.showSql, false,
                "statement and bind-parameter logging would expose the customer social security number and "
                        + "the stored password hashes of the seeded users");
        requireState(KEY_FLYWAY_ENABLED, this.flywayEnabled, true,
                "with migration disabled there is no schema for the provider to validate against");
        requireState(KEY_FLYWAY_BASELINE_ON_MIGRATE, this.flywayBaselineOnMigrate, false,
                "baselining would silently adopt an unknown pre-existing schema as the starting point");
        requireState(KEY_FLYWAY_VALIDATE_ON_MIGRATE, this.flywayValidateOnMigrate, true,
                "an edit to an already-applied migration must be reported, not applied");
        requireState(KEY_FLYWAY_CLEAN_DISABLED, this.flywayCleanDisabled, true,
                "the operation it disables destroys every object in the schema and must stay refused in "
                        + "every profile, test included");
        requireState(KEY_FLYWAY_OUT_OF_ORDER, this.flywayOutOfOrder, false,
                "ordered application is load-bearing: the seed migration relies on the indexes the "
                        + "preceding one creates");

        LOG.info("Persistence contract verified: {}={} {}={} {}={} {}={} {}={} {}={} {}={} {}={} {}={}",
                KEY_DDL_AUTO, this.ddlAuto,
                KEY_HIBERNATE_TIME_ZONE, this.hibernateTimeZone,
                KEY_OPEN_IN_VIEW, this.openInView,
                KEY_SHOW_SQL, this.showSql,
                KEY_FLYWAY_ENABLED, this.flywayEnabled,
                KEY_FLYWAY_BASELINE_ON_MIGRATE, this.flywayBaselineOnMigrate,
                KEY_FLYWAY_VALIDATE_ON_MIGRATE, this.flywayValidateOnMigrate,
                KEY_FLYWAY_CLEAN_DISABLED, this.flywayCleanDisabled,
                KEY_FLYWAY_OUT_OF_ORDER, this.flywayOutOfOrder);
    }

    /**
     * Registers the persistence-contract guard as a singleton so that
     * {@link #verifyPersistenceContract()} runs during context refresh.
     *
     * <p>Returned as an {@link InitializingBean} because that gives the earliest deterministic hook the
     * container offers for a plain bean: the container invokes it as part of creating the singleton, before
     * any request can be served and before any batch step can be launched. In practice the bean definitions
     * contributed by this class are registered ahead of the auto-configured persistence beans, so the guard
     * normally reports first and an operator sees the precise cause rather than a downstream symptom.
     *
     * <p>That ordering is a diagnostic convenience and is deliberately <strong>not</strong> load-bearing: no
     * bean ordering is declared and none is relied upon. If the provider's own schema validation were to run
     * first it would raise its own failure, the application would still refuse to start, and the guard would
     * still report on the next attempt. Correctness therefore does not depend on which check fires first -
     * only the quality of the first message does.
     *
     * @return the guard, which verifies the contract when the container initialises it and has no other
     *         behaviour and no state of its own
     */
    @Bean
    public InitializingBean persistenceContractGuard() {
        return this::verifyPersistenceContract;
    }

    /**
     * Returns the trimmed value of a required property, rejecting an absent or blank one.
     *
     * @param key      the property key, used verbatim in the failure message so the message and the binding
     *                 cannot drift apart
     * @param rawValue the bound value, which is an empty string when the key is absent
     * @return the value with surrounding whitespace removed
     * @throws IllegalStateException if the value is {@code null} or contains only whitespace
     */
    private static String requireConfigured(final String key, final String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalStateException(
                    "Persistence contract violated: " + key + " is not configured. It is owned by "
                            + "src/main/resources/application.yml and must be present in every profile.");
        }
        return rawValue.trim();
    }

    /**
     * Returns the value of a required flag, accepting only an exact {@code true} or {@code false}.
     *
     * <p>The platform's own lenient parse is deliberately avoided: it maps every unrecognised value to
     * {@code false}, so a typo would silently disarm an invariant instead of reporting it. Case folding uses
     * {@link Locale#ROOT} rather than the default locale, so the result cannot vary with the host's regional
     * settings.
     *
     * @param key      the property key, used verbatim in the failure message
     * @param rawValue the bound value, which is an empty string when the key is absent
     * @return {@code true} or {@code false} as written
     * @throws IllegalStateException if the value is absent, blank, or is anything other than {@code true} or
     *                               {@code false}
     */
    private static boolean requireFlag(final String key, final String rawValue) {
        final String normalised = requireConfigured(key, rawValue).toLowerCase(Locale.ROOT);
        if (TRUE_LITERAL.equals(normalised)) {
            return true;
        }
        if (FALSE_LITERAL.equals(normalised)) {
            return false;
        }
        throw new IllegalStateException(
                "Persistence contract violated: " + key + " is '" + rawValue.trim() + "', which is neither "
                        + TRUE_LITERAL + " nor " + FALSE_LITERAL + ". A flag that cannot be read cannot be "
                        + "enforced, so it is rejected rather than assumed.");
    }

    /**
     * Asserts that a textual property holds the one value the contract permits.
     *
     * <p>Comparison is case-insensitive, normalised through {@link Locale#ROOT}, because the settings
     * concerned are themselves interpreted case-insensitively downstream; rejecting a differently cased but
     * behaviourally identical value would be a false positive, and a guard that raises those gets weakened
     * rather than heeded.
     *
     * @param key      the property key, used verbatim in the failure message
     * @param actual   the configured value, already known to be present and trimmed
     * @param required the only permitted value
     * @param reason   why the invariant exists, quoted into the failure message so the message explains
     *                 itself without reference to this file
     * @throws IllegalStateException if the configured value differs from the required one
     */
    private static void requireValue(final String key, final String actual, final String required,
            final String reason) {
        if (!required.toLowerCase(Locale.ROOT).equals(actual.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(
                    "Persistence contract violated: " + key + " is '" + actual + "' but must be '" + required
                            + "', because " + reason + ". Restore the required value rather than relaxing "
                            + "this check.");
        }
    }

    /**
     * Asserts that a flag property holds the one state the contract permits.
     *
     * @param key      the property key, used verbatim in the failure message
     * @param actual   the configured state
     * @param required the only permitted state
     * @param reason   why the invariant exists, quoted into the failure message so the message explains
     *                 itself without reference to this file
     * @throws IllegalStateException if the configured state differs from the required one
     */
    private static void requireState(final String key, final boolean actual, final boolean required,
            final String reason) {
        if (actual != required) {
            throw new IllegalStateException(
                    "Persistence contract violated: " + key + " is " + actual + " but must be " + required
                            + ", because " + reason + ". Restore the required value rather than relaxing "
                            + "this check.");
        }
    }
}
