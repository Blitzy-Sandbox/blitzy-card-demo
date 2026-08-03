/*
 * ****************************************************************************
 * Program     : DailyTransactionRepository.java
 * Application : CardDemo
 * Type        : Spring Data JPA Repository Interface
 * Function    : Deterministic sequential staging access over the daily
 *               transaction input records. Replaces the sequential OPEN / READ /
 *               CLOSE of a physical sequential dataset, not a VSAM browse.
 *               Identity and read order are the ingestion ordinal, never
 *               DALYTRAN-ID, which the unkeyed input may repeat.
 *               Batch-only: no CICS file definition exists.
 * Source      : AWS.M2.CARDDEMO.DALYTRAN.PS - PHYSICAL SEQUENTIAL, no KSDS, no
 *               catalogued cluster in app/catlg/LISTCAT.txt, no key, no
 *               alternate index; DD DALYTRAN app/jcl/POSTTRAN.jcl:L31 and the
 *               initial load target app/jcl/TRANFILE.jcl:L70
 *               (AWS.M2.CARDDEMO.DALYTRAN.PS.INIT); record layout
 *               app/cpy/CVTRA06Y.cpy:L4-L18 (350 bytes); sequential read
 *               app/cbl/CBTRN02C.cbl (1000-DALYTRAN-GET-NEXT) with read-only
 *               pre-flight app/cbl/CBTRN01C.cbl; ABSENT from
 *               app/csd/CARDDEMO.CSD (batch-only proof) @ 7756d89
 * ****************************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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

import com.cardemo.model.entity.DailyTransaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Deterministic sequential access to the daily-transaction staging table, replacing the
 * physical sequential dataset {@code AWS.M2.CARDDEMO.DALYTRAN.PS}.
 *
 * <h2>What this component does</h2>
 * <p>This interface is the single read door onto the staged daily-transaction input that the
 * daily posting job consumes. It reproduces exactly one legacy access pattern and nothing
 * else: open the file, read forward record by record until end of file, close. That pattern
 * is {@code 1000-DALYTRAN-GET-NEXT} at {@code app/cbl/CBTRN02C.cbl:L345}, driven by the
 * {@code PERFORM UNTIL END-OF-FILE = 'Y'} loop at {@code :L202-L219}.</p>
 *
 * <p>It declares no business logic. Validation, reject classification, balance arithmetic and
 * fixed-width record emission all live in {@code com.cardemo.batch} - specifically in
 * {@code TransactionPostingProcessor} and {@code RejectWriter} - and deliberately not here.
 * That split is the separation of concerns Rule 1 Clause A asks for: this type reads rows in a
 * defined order, and every judgement about those rows is made elsewhere.</p>
 *
 * <p><strong>This table is literally untrusted input.</strong> Its rows are raw, unvalidated
 * file content. A staged row may carry a card number that resolves to no cross-reference row
 * and an account identifier that resolves to no account row, and discovering that is the whole
 * purpose of the downstream validation sequence. Reject code 100
 * ({@code INVALID CARD NUMBER FOUND}) is assigned at {@code app/cbl/CBTRN02C.cbl:L385} and
 * reject code 101 ({@code ACCOUNT RECORD NOT FOUND}) at {@code :L397}. This interface therefore
 * hands back every staged row without judgement.</p>
 *
 * <h2>Provenance and physical facts</h2>
 * <p>Anchor commit {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec} (short {@code 7756d89}).
 * The legacy corpus under {@code app/} is frozen and byte-for-byte read-only; nothing in it was
 * altered to produce this file.</p>
 *
 * <ul>
 *   <li><strong>Dataset and DD name</strong> - {@code //DALYTRAN DD} resolving to
 *       {@code DSN=AWS.M2.CARDDEMO.DALYTRAN.PS} at {@code app/jcl/POSTTRAN.jcl:L31}. The step
 *       that opens it is {@code //STEP15 EXEC PGM=CBTRN02C} at {@code :L23}.</li>
 *   <li><strong>Initial-load companion</strong> - {@code AWS.M2.CARDDEMO.DALYTRAN.PS.INIT} at
 *       {@code app/jcl/TRANFILE.jcl:L70}, consumed by the
 *       {@code REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)} control card at {@code :L74}.</li>
 *   <li><strong>Record layout</strong> - {@code 01 DALYTRAN-RECORD.} at
 *       {@code app/cpy/CVTRA06Y.cpy:L4}, fields at {@code :L5-L18}, declared length 350 bytes
 *       by the copybook header at {@code :L2}.</li>
 *   <li><strong>Access verbs</strong> - the complete inventory against this dataset in
 *       {@code app/cbl/CBTRN02C.cbl} is {@code OPEN INPUT DALYTRAN-FILE} at {@code :L238},
 *       {@code READ DALYTRAN-FILE INTO DALYTRAN-RECORD} at {@code :L346} and
 *       {@code CLOSE DALYTRAN-FILE} at {@code :L584}. There is no {@code WRITE}, no
 *       {@code REWRITE}, no {@code DELETE}, no {@code STARTBR} and no {@code READPREV}
 *       anywhere against it. Note the {@code INPUT} open mode: the file is opened read-only at
 *       the source level, not merely used read-only.</li>
 *   <li><strong>Read-only pre-flight</strong> - {@code app/cbl/CBTRN01C.cbl} has no distinct
 *       JCL job and is to be folded into {@code DailyTransactionPostingJob} (<strong>planned</strong>;
 *       that class has not been authored yet) as an explicitly labelled pre-flight step. Its six
 *       {@code SELECT} statements sit at {@code :L29-L58} ({@code DALYTRAN} first, at
 *       {@code :L29}), and a verb census over that member returns
 *       {@code OPEN} 18, {@code READ} 17, {@code CLOSE} 18, {@code DISPLAY} 42 and zero each
 *       of {@code WRITE}, {@code REWRITE}, {@code DELETE}, {@code STARTBR} and
 *       {@code READPREV}.</li>
 *   </ul>
 *
 * <h2>Finding: HIGH - no VSAM cluster, no catalogued key length, no alternate index</h2>
 * <p>This is the one repository in {@code com.cardemo.repository} whose source is not a VSAM
 * KSDS, and the distinction is load-bearing rather than trivia. <strong>No cluster locator is
 * cited here because no cluster entry exists, and none is invented.</strong></p>
 *
 * <p>What the catalogue listing actually contains is the positive proof of the negative claim.
 * The dataset appears as a non-VSAM entry,
 * {@code 0NONVSAM ------- AWS.M2.CARDDEMO.DALYTRAN.PS} at {@code app/catlg/LISTCAT.txt:L786},
 * with its initial-load companion at {@code :L801}. A non-VSAM catalogue entry carries no key
 * length, no relative key position and no average or maximum record size; its attribute block
 * is empty and its associations read {@code (NULL)}. The catalogue totals corroborate this
 * directly: {@code :L3940} reports {@code CLUSTER --------------10} and {@code :L3944} reports
 * {@code NONVSAM -------------160}, and this dataset is one of the 160, not one of the 10. The
 * ten clusters are {@code ACCTDATA}, {@code CARDDATA}, {@code CARDXREF}, {@code CUSTDATA},
 * {@code DISCGRP}, {@code TCATBALF}, {@code TRANCATG}, {@code TRANSACT}, {@code TRANTYPE} and
 * {@code USRSEC}. Independently, a search of {@code app/jcl} for a
 * {@code DEFINE CLUSTER} carrying a {@code KEYS(...)} clause for this dataset returns nothing
 * at all.</p>
 *
 * <p>Consequences that follow from physical sequential organisation, each of which would be a
 * defect if reversed:</p>
 * <ul>
 *   <li>there is no key, so no primary-key browse semantics are reproduced;</li>
 *   <li>there is no alternate index, so this table gets no secondary index. The package
 *       declares exactly three alternate-key finders in total, over
 *       {@code card.card_acct_id}, {@code card_cross_reference.xref_acct_id} and
 *       {@code "transaction".tran_proc_ts}, and none of them belongs to this table;</li>
 *   <li>{@code V2__create_indexes.sql} is therefore to create no index for
 *       {@code daily_transaction};</li>
 *   <li><strong>{@code dalytran_id} is not the primary key and must never be made one.</strong>
 *       See the identity finding immediately below, which is the reason this repository's
 *       identifier type argument is {@code Long} rather than {@code String}.</li>
 *   </ul>
 *
 * <p>One adjacent trap deserves naming because the two are easy to conflate. The
 * {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)} at {@code app/jcl/POSTTRAN.jcl:L36}, whose
 * {@code DSN=AWS.M2.CARDDEMO.DALYREJS(+1)} follows at {@code :L38}, describes the <em>reject
 * output</em>, not this input. That 430-byte record is the 350-byte transaction image plus an
 * 80-byte trailer, declared at {@code app/cbl/CBTRN02C.cbl:L176-L182} as
 * {@code REJECT-TRAN-DATA PIC X(350)} plus {@code VALIDATION-TRAILER PIC X(80)}, the trailer
 * decomposing into {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} and
 * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}. Emitting it is
 * {@code com.cardemo.batch.writers.RejectWriter}'s job. This interface declares no reject
 * method and performs no fixed-width emission.</p>
 *
 * <h2>Finding: HIGH - the identifier is the ingestion ordinal, never {@code DALYTRAN-ID}</h2>
 * <p>Because the dataset is unkeyed, {@code DALYTRAN-ID} carries <strong>no uniqueness
 * guarantee of any kind</strong>, and nothing in the corpus supplies one. Exactly two programs
 * open the file, {@code app/cbl/CBTRN01C.cbl} and {@code app/cbl/CBTRN02C.cbl}; both declare it
 * {@code ORGANIZATION IS SEQUENTIAL} / {@code ACCESS MODE IS SEQUENTIAL} at
 * {@code CBTRN02C.cbl:L30-L31} and {@code CBTRN01C.cbl:L30-L31}; and their entire verb
 * inventory against it is {@code OPEN INPUT}, a bare {@code READ ... INTO} whose only accepted
 * statuses are {@code '00'} and {@code '10'}, and {@code CLOSE}. There is no {@code STARTBR},
 * no keyed {@code READ}, no {@code READ ... KEY IS} and no {@code INVALID KEY} path against it
 * anywhere in {@code app/cbl}. A flat file may therefore legitimately carry the same
 * transaction identifier twice, and the source posts both records.</p>
 *
 * <p><strong>Why this is High rather than cosmetic: the shipped fixture cannot detect the
 * error.</strong> {@code app/data/ASCII/dailytran.txt} is both unique on that field
 * <em>and</em> already ascending by it. So a unique key over {@code dalytran_id} passes every
 * test built from that fixture, and so does every assertion that the read comes back ordered by
 * identifier - and the invented constraint then rejects real input in production. Both defects
 * are invisible to the only fixture that exists, which is why this contract is stated
 * explicitly here instead of being left to the copybook to imply.</p>
 *
 * <p><strong>The identity is consequently an ingestion sequence.</strong> For a sequential
 * dataset the record's position in the file <em>is</em> its identity, and the source already
 * counts exactly that: {@code WS-TRANSACTION-COUNT}, declared {@code PIC 9(09) VALUE 0} at
 * {@code app/cbl/CBTRN02C.cbl:L185}, incremented once per accepted read by
 * {@code ADD 1 TO WS-TRANSACTION-COUNT} at {@code :L206}, and reported at {@code :L227}. The
 * entity's {@code ingestSequence} is that ordinal - one-based, dense, in read order, over a
 * {@code NUMERIC(9)} column whose precision is the declared precision of the counter being
 * modelled. It is assigned by the loader, never generated by the database, so the schema
 * declares no generated-value strategy and no sequence.</p>
 *
 * <p><strong>Three consequences bind every method here.</strong> First, the identifier type
 * argument of this interface is {@code Long}, so the inherited single-row and delete operations
 * address a row by its ordinal. Second, <strong>every multi-row read orders by the ordinal, not
 * by {@code dalytran_id}</strong>: only the ordinal reproduces the flat-file read order, and
 * only the ordinal is a total order, which paging soundness requires. Third, no finder by
 * {@code dalytran_id} is declared and none may be added as though it addressed a single row,
 * because it does not.</p>
 *
 * <h2>The normative column contract</h2>
 * <p>The full byte-offset map lives on {@link DailyTransaction}. Reproduced here are only the
 * facts this repository contract depends on, because {@code spring.jpa.hibernate.ddl-auto} is
 * {@code validate} in every profile and a divergence between these names or types and the
 * migration is a startup failure rather than a graceful degradation.</p>
 *
 * <pre>
 * table                       daily_transaction
 * primary key   (the @Id)     ingest_seq         NUMERIC(9)      Long    ingestSequence
 * business id   (NOT unique)  dalytran_id        CHAR(16)        String  transactionId
 * signed amount               dalytran_amt       NUMERIC(11,2)   BigDecimal amount
 * originating stamp           dalytran_orig_ts   CHAR(26)        String  origTs
 * processing stamp            dalytran_proc_ts   CHAR(26)        String  procTs
 * origin of the row           dalytran_source    CHAR(10)        String  transactionSource
 * card number (never shown)   dalytran_card_num  CHAR(16)        String  cardNumber
 * version column              none
 * foreign keys                none
 * unique constraints          none beyond the primary key
 * secondary indexes           none
 * </pre>
 *
 * <p>{@code ingest_seq} is the one column in that table with no copybook line, which is why it
 * alone lacks the {@code dalytran_} prefix. Because it is a {@code NUMERIC(9)} ordinal, the
 * identifier type argument of this repository is {@code Long}. {@code dalytran_id} is
 * {@code CHAR(16)} holding fixed-width text and is never converted to an integral type - leading
 * zeros are significant - and it carries no uniqueness, so it is not, and cannot be, the
 * {@code @Id}.</p>
 *
 * <h2>Finding: BLOCKER - the two 26-character stamps are text, never a temporal type</h2>
 * <p>{@code DALYTRAN-ORIG-TS} at {@code app/cpy/CVTRA06Y.cpy:L16} and
 * {@code DALYTRAN-PROC-TS} at {@code :L17} are both {@code PIC X(26)}. They are mapped to
 * {@code String} over {@code CHAR(26)}, and no {@code java.time} class and no JDBC date-time
 * wrapper appears anywhere on this path. Two independent proofs, either of which is
 * sufficient:</p>
 * <ul>
 *   <li><strong>The fixture cannot be parsed as a moment in time.</strong> Across all 300
 *       records of {@code app/data/ASCII/dailytran.txt}, bytes 305-330 hold exactly one
 *       distinct value: twenty-six spaces. The processing stamp is blank on input because the
 *       posting job generates it downstream. No temporal parser accepts a blank, so a temporal
 *       mapping would fail to load the entire fixture. Bytes 279-304 likewise hold exactly one
 *       distinct value, {@code 2022-06-10 19:27:53.000000}.</li>
 *   <li><strong>The source treats the stamp as characters.</strong> The account-expiry check
 *       at {@code app/cbl/CBTRN02C.cbl:L414} reads
 *       {@code IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)}. That
 *       {@code (1:10)} is COBOL reference modification - a substring of the first ten
 *       characters - and the comparison is a character comparison against a text field whose
 *       name is itself misspelled in the copybook. A date comparison is never performed.</li>
 *   </ul>
 *
 * <h2>Finding: BLOCKER - the amount is NUMERIC(11,2) and its sign is load-bearing</h2>
 * <p>{@code DALYTRAN-AMT} at {@code app/cpy/CVTRA06Y.cpy:L10} is {@code PIC S9(09)V99}, which
 * is {@code NUMERIC(11,2)} and {@code java.math.BigDecimal}. Precision tiers are never
 * collapsed into a single convenient width: {@code S9(10)V99} becomes {@code NUMERIC(12,2)}
 * for the account money and cycle fields, {@code S9(09)V99} becomes {@code NUMERIC(11,2)} for
 * this amount, and {@code S9(04)V99} becomes {@code NUMERIC(6,2)} for the disclosure interest
 * rate. No IEEE 754 binary approximation type appears in any monetary field; rounding is
 * {@code RoundingMode.HALF_EVEN}; equality is decided by {@code compareTo}, never by
 * {@code equals}, so that a scale difference is not mistaken for a value difference.</p>
 *
 * <p><strong>The staged data genuinely carries negative amounts, and they must survive
 * untouched.</strong> The trailing overpunch character fixes the final digit <em>and</em> the
 * sign of the field; it does not by itself make the value zero. Position-aware zoned-decimal
 * decoding of bytes 133-143 across {@code app/data/ASCII/dailytran.txt} yields 25 rows
 * terminated {@code &#123;} (final digit zero, positive), 6 terminated {@code &#125;} (final
 * digit zero, negative), 44 terminated {@code J} through {@code R} (negative one through nine)
 * and 225 terminated {@code A} through {@code I} (positive one through nine). Decoding all 300
 * rows therefore yields 250 positive and 50 negative amounts and none that is exactly zero:
 * {@code 0000003250&#123;} is {@code +325.00}, {@code 0000009190&#125;} is {@code -919.00} and
 * {@code 0000000567P} is {@code -56.77}. Those 50 signed-negative rows - the 44 plus the 6 -
 * exercise the cycle-debit branch at {@code app/cbl/CBTRN02C.cbl:L547-L552}, where a negative
 * amount is added to the debit accumulator, so that accumulator legitimately holds negative
 * values - which is in turn precisely why the over-limit expression at {@code :L403-L405}
 * subtracts it. No absolute-value normalisation is applied anywhere on this path, and the
 * fixture is never normalised.</p>
 *
 * <h2>Finding: BLOCKER - no version column, no foreign key, no shared supertype</h2>
 * <ul>
 *   <li><strong>No version column.</strong> This is write-once staging. The source never
 *       rewrites a staged record and never snapshot-compares one, so there is no lost-update
 *       hazard to guard and no optimistic-locking column to add.</li>
 *   <li><strong>No foreign key</strong> to {@code card}, {@code card_cross_reference} or
 *       {@code account}. This is the single most tempting mistake available here and it would
 *       break the feature outright. The entire point of the validation sequence is that a
 *       staged row may reference a card or account that does not exist - reject code 100 at
 *       {@code app/cbl/CBTRN02C.cbl:L385} and reject code 101 at {@code :L397} exist for no
 *       other reason. A referential constraint would reject those rows at insert time and make
 *       the reject path unreachable and untestable. {@code V1__create_schema.sql} declares ten
 *       foreign keys and not one of them is on this table.</li>
 *   <li><strong>No shared supertype</strong> with the posted-transaction entity, and no mapped
 *       superclass. The two layouts have identical width and field order, which makes
 *       consolidation look like the duplication Rule 1 Clause C asks to be avoided, but they
 *       are deliberately separate copybooks - {@code app/cpy/CVTRA06Y.cpy} here and
 *       {@code app/cpy/CVTRA05Y.cpy} there - describing a staging file and a keyed master
 *       respectively. Merging them would invent an abstraction the source does not have and
 *       would drag the master's version column and foreign-key set onto a staging table that
 *       must have neither. Clause C is honoured instead by following the conventions that do
 *       exist: the Apache-2.0 provenance banner every file in {@code app/cbl} carries, and the
 *       repository {@code .editorconfig} - UTF-8, LF, four-space indent, a final newline, no
 *       trailing whitespace and a 120-column ceiling.</li>
 *   </ul>
 *
 * <h2>Finding: BLOCKER - the origin column is text, never the origin enum</h2>
 * <p>{@code dalytran_source} is a plain {@code String} and is deliberately not mapped to
 * {@code com.cardemo.model.enums.TransactionSource}. That enum carries exactly the two values
 * the programs emit as literals: {@code 'System'} at {@code app/cbl/CBACT04C.cbl:L484} and
 * {@code 'POS TERM'} at {@code app/cbl/COBIL00C.cbl:L222}. The staged data contains a third
 * value that is not in the enum and never will be. A census of bytes 23-32 across
 * {@code app/data/ASCII/dailytran.txt} returns 250 rows of {@code POS TERM  } and 50 rows of
 * {@code OPERATOR  }; the parallel census of bytes 17-18 returns 250 rows of type {@code 01}
 * and 50 rows of type {@code 03}. {@code OPERATOR} is data, not a program literal. Binding
 * this column to the enum would fail to load 50 of the 300 records that the boundary-parity
 * gate depends on. The value is carried as text and interpreted, if at all, downstream.</p>
 *
 * <h2>Finding: BLOCKER - deterministic ordering is mandatory on every multi-row read</h2>
 * <p>Rule 1 Clause A opens with "Correctness first: prioritize correctness, determinism, and
 * explicit behavior over cleverness", and this is where that clause bites hardest. A flat file
 * read forward has exactly one order. A relational table has none until one is demanded:
 * without an explicit ordering the store may return rows in physical, heap or hash order, and
 * that order may change after a vacuum, a page split or a plan change, with no error and no
 * warning.</p>
 *
 * <p>Every multi-row method declared here therefore carries an explicit ascending ordering on
 * <strong>the ingestion ordinal</strong>, encoded in the method name so that it is visible at
 * every call site and cannot be forgotten by a caller. The consequence of omitting it would not
 * be a crash but something worse: the reject file and the posted-transaction output would come
 * out in a different order on different runs, and the field-level comparison against the legacy
 * baseline would fail intermittently and unreproducibly.</p>
 *
 * <p><strong>Ordering by {@code dalytran_id} instead would be wrong twice over</strong>, and it
 * is worth stating why, because the shipped fixture makes it look right. It is wrong on parity,
 * because the flat file has exactly one order - the order the records physically appear in - and
 * that order is only coincidentally the identifier order in the fixture; real input sorted by
 * identifier would be reordered relative to what the source read. And it is wrong on soundness,
 * because {@code dalytran_id} is not unique, so it is not a total order, and paging by a
 * non-total order can skip or repeat rows across chunk boundaries. The ordinal is the primary
 * key, so it is unique, dense and total, and it is the file position - it fixes both defects at
 * once.</p>
 *
 * <p>The guarantee is verifiable rather than assumed. In the Spring Data version this build
 * resolves, {@code AbstractQueryCreator} composes the effective sort as the static sort parsed
 * from the method name combined with, and ahead of, any dynamic sort supplied at call time.
 * The ordinal ordering is therefore the primary sort key and a caller cannot displace it;
 * a sort a caller adds can only act as a tie-breaker after it. Passing an unsorted pageable is
 * still the expected usage.</p>
 *
 * <h2>Finding: HIGH - batch-only, proved by absence from the CICS resource definitions</h2>
 * <p>{@code app/csd/CARDDEMO.CSD} declares exactly eight files, and the census is short enough
 * to state in full: {@code ACCTDAT} at {@code :L1}, {@code CARDAIX} at {@code :L13},
 * {@code CARDDAT} at {@code :L25}, {@code CCXREF} at {@code :L37}, {@code CUSTDAT} at
 * {@code :L50}, {@code CXACAIX} at {@code :L63}, {@code TRANSACT} at {@code :L76} and
 * {@code USRSEC} at {@code :L88}. <strong>{@code DALYTRAN} is not among them</strong>, and
 * neither are {@code TCATBALF}, {@code DISCGRP}, {@code TRANCATG} or {@code TRANTYPE}: a
 * search of that member for any of the five returns nothing. Having no online file definition
 * is what makes this dataset batch-only, and it is an absence in the system of record rather
 * than a policy choice made here.</p>
 *
 * <p>Two things follow, and the second is a standing constraint on other packages:</p>
 * <ul>
 *   <li>the only legitimate consumers are in {@code com.cardemo.batch} -
 *       {@code TransactionPostingProcessor}, which is authored, plus
 *       {@code DailyTransactionPostingJob} and {@code DailyTransactionReader}, which are
 *       <strong>planned</strong> and not yet authored;</li>
 *   <li><strong>no controller operation, no REST endpoint and no administrative management
 *       surface may be created for this staging table</strong>, nor for {@code TCATBALF},
 *       {@code DISCGRP}, {@code TRANCATG} or {@code TRANTYPE}. The online surface is exactly
 *       seventeen operations across eight controllers, mapped one-for-one from the CICS
 *       transaction definitions, and none of them touches any of these five. Exposing staging
 *       data over HTTP would grant reach the legacy system never granted, which is the
 *       least-privilege violation Rule 1 Clause D prohibits.</li>
 *   </ul>
 *
 * <h2>Finding: HIGH - the card number is never projected and never logged</h2>
 * <p>This table carries {@code dalytran_card_num}, a 16-character primary account number. No
 * method here returns it in isolation, no projection type exposes it, and this file contains
 * no logging statement of any kind - there is no logger field to misuse. Rows leave through
 * the entity, so redaction is decided once, at the point of rendering, and never here.
 * {@code spring.jpa.show-sql} is {@code false} in every profile and no Hibernate statement or
 * bind-parameter logging is enabled anywhere, so a card number cannot reach a log through
 * query tracing either.</p>
 *
 * <h2>Finding: MEDIUM - the fixture spells the word in full</h2>
 * <p>The mainframe DD name, the dataset qualifier and every column prefix use the abbreviated
 * {@code DALYTRAN}, but the checked-in fixture is {@code app/data/ASCII/dailytran.txt}, with
 * "daily" spelled out. The abbreviated spelling does not name any file in the repository. A
 * reference using it fails as a silently missing test resource rather than as a compile error,
 * which is what makes the mistake worth calling out; every citation in this file uses the real
 * name.</p>
 *
 * <h2>Method surface - deliberately minimal</h2>
 * <p><strong>Exactly one method is declared, and it has named callers.</strong> Rule 1 Clause B
 * forbids dead code, and no parity-preserved no-op artefact lives in this package, so that
 * clause applies here at full strength with no exemption. Speculative finders are therefore
 * absent by design: there is no finder by card number, none by type code or category code, none
 * by amount or amount range, none by either stamp, no hand-written aggregate, no projection and
 * no alternate-key finder. Nothing that mutates is declared either - no bulk update and no
 * delete operation - because the source never modifies this dataset. Staging rows arrive
 * through {@code V3__seed_data.sql} or a loader outside this interface.</p>
 *
 * <p><strong>There is deliberately no whole-file {@code java.util.List} form.</strong> One was
 * declared here previously and has been removed, because it materialised every staged row into
 * the heap at once - the opposite of what the source does, which is to hold exactly one record
 * live at a time between {@code OPEN INPUT} at {@code app/cbl/CBTRN02C.cbl:L238} and
 * {@code CLOSE} at {@code :L584}. A result set bounded only by the size of the table is unsafe
 * here in the specific way that is hardest to catch: the 300-record fixture makes it pass every
 * test, and only production volume reveals it. Callers that need to walk the whole file - the
 * posting job and the read-only pre-flight step alike - do so by iterating the slice below until
 * it reports no further content, which is the direct analogue of
 * {@code PERFORM UNTIL END-OF-FILE = 'Y'} at {@code :L202-L219}.</p>
 *
 * <p>Two inherited methods are relied upon and are deliberately <em>not</em> redeclared, since
 * redeclaring an inherited signature adds a maintenance surface without adding behaviour:</p>
 * <ul>
 *   <li>{@code findById(Long)} - single-row lookup <strong>by ingestion ordinal</strong>, that
 *       is, by position in the staged file. It is used to re-read a specific staged row when a
 *       diagnostic or a restart names one, and it returns an empty optional for an unknown
 *       ordinal, which must not be treated as an error path. <strong>It has no source
 *       analogue and must not be presented as one:</strong> the corpus never performs a keyed
 *       read against this dataset, because it has no key. There is correspondingly no lookup by
 *       {@code dalytran_id}, and none may be added, because that column identifies no single
 *       row.</li>
 *   <li>{@code count()} - the staged row volume. The pre-flight step uses it to report what it
 *       is about to process, and it feeds the records-processed counter that replaces the
 *       end-of-run {@code DISPLAY 'TRANSACTIONS PROCESSED :'} at
 *       {@code app/cbl/CBTRN02C.cbl:L227}. Note honestly that the source keeps that figure by
 *       incrementing {@code WS-TRANSACTION-COUNT} per record at {@code :L206} rather than by
 *       asking the file for a total; the count query is a diagnostic convenience the flat file
 *       could not offer, and it is not a substitute for the per-record tally. Since that same
 *       counter is what the ingestion ordinal models, the count of a fully staged file equals
 *       its highest ordinal.</li>
 *   </ul>
 *
 * <h2>Error modes</h2>
 * <ul>
 *   <li><strong>End of data is not an error.</strong> In the source, file status {@code '10'}
 *       moves 16 into the result field and sets the end-of-file flag at
 *       {@code app/cbl/CBTRN02C.cbl:L345-L369}, terminating the loop normally. Here the
 *       equivalent is an empty result: an empty staging table, or a chunk position past the
 *       end, yields a slice with no content that reports no further rows follow - never an
 *       exception and never a null.</li>
 *   <li><strong>Blank and negative values are valid, and so is a repeated business
 *       identifier.</strong> A 26-space processing stamp and a negative amount are both
 *       well-formed staged input, as the fixture census above proves, and two staged rows may
 *       legitimately carry the same {@code dalytran_id} because the input is unkeyed. None of
 *       the three is a data-quality failure and none may be rejected, defaulted, de-duplicated
 *       or normalised at this layer.</li>
 *   <li><strong>Infrastructure failure surfaces as an unchecked exception</strong> from the
 *       persistence provider, carrying its root cause. Nothing here catches it, wraps it into a
 *       success value or logs and swallows it. Translation into the project taxonomy happens
 *       exactly once, in {@code com.cardemo.service.shared.FileStatusMapper}, which maps
 *       {@code '00'} to success, {@code '10'} to loop termination, {@code '23'} to
 *       {@code com.cardemo.exception.RecordNotFoundException} except at the accepted-status
 *       sites, {@code '22'} to {@code com.cardemo.exception.DuplicateRecordException}, the
 *       {@code '9x'} family to {@code com.cardemo.exception.FileAccessException} carrying the
 *       four-character expanded status, and anything else to
 *       {@code com.cardemo.exception.FatalProcessingException} with abend code 999 and return
 *       code 12. Those types are named as prose on purpose and are not imported: importing an
 *       exception a repository never throws would be an unused import, which Rule 1 Clause B forbids.
 *       {@code -Xlint:all -Werror} cannot enforce that - {@code javac} 25 publishes no unused-import
 *       lint key - so the prohibition is enforced by review.</li>
 *   <li><strong>Reject codes are business outcomes, not exceptions.</strong> The five constants
 *       are 100 at {@code app/cbl/CBTRN02C.cbl:L385}, 101 at {@code :L397}, 102 at
 *       {@code :L410}, 103 at {@code :L417} and 109 at {@code :L556}. None is thrown, none is
 *       modelled here, and the preserved quirk that code 103 overwrites code 102 when both the
 *       over-limit and the expiry check fail - the two checks at {@code :L403-L420} being
 *       sequential and unguarded, so exactly one reject record bearing 103 is written - is
 *       reproduced in {@code TransactionPostingProcessor}, not in this interface.</li>
 *   </ul>
 *
 * <h2>Build, test and troubleshooting</h2>
 * <p>Rule 1 Clause E requires every component to document what it does, how it is built and
 * tested, its key configuration and defaults, and its common failure modes. The opening section
 * discharges the first of those; the four bullets below discharge the rest. Package-scope
 * documentation lives in {@code package-info.java}, and no README or Markdown file is added to
 * this package.</p>
 * <ul>
 *   <li><strong>Build</strong> - {@code ./mvnw -B clean compile} on Java 25 with Maven 3.9.11.
 *       The compiler runs {@code -Xlint:all -Werror} with {@code failOnWarning}, so a raw type or a
 *       deprecated call added to this file fails the build rather than warning. An unused import does
 *       not: {@code javac} 25 publishes no lint key for one, so that stays a review matter.</li>
 *   <li><strong>Test</strong> - {@code ./mvnw -B clean test} for the unit tier and
 *       {@code ./mvnw -B clean verify} for the integration tier. Repository coverage belongs in
 *       {@code src/test/java/com/cardemo/integration/repository} against a Testcontainers
 *       PostgreSQL 16, with the end-to-end pass belonging in the planned
 *       {@code src/test/java/com/cardemo/e2e/BatchPipelineE2ETest.java} driving all 300 fixture
 *       records. Those tests must assert six things specifically, because each corresponds to
 *       a finding above: that the read comes back ordered by <em>ingestion ordinal</em>, that
 *       two staged rows sharing one {@code dalytran_id} both persist, that a negative-amount
 *       row round-trips with its sign, that a row with a blank 26-space processing stamp loads,
 *       that a row whose origin is {@code OPERATOR} loads, and that slice iteration returns
 *       every row exactly once across chunk boundaries. The second and the sixth cannot be
 *       proved with the shipped fixture, because it is unique on the identifier and already
 *       ascending by it, so they need a purpose-built fixture carrying duplicate and
 *       out-of-order identifiers - see the "Not available" section below.</li>
 *   <li><strong>Key configuration and defaults</strong> -
 *       {@code spring.jpa.hibernate.ddl-auto: validate} in every profile, so schema drift is a
 *       startup failure; {@code spring.jpa.open-in-view: false}, so results are fully
 *       initialised before they leave a transaction and no lazy load can be triggered later;
 *       {@code spring.jpa.show-sql: false}. Spring Batch metadata tables come from the
 *       framework's own script through {@code spring.batch.jdbc.initialize-schema} and never
 *       from a fourth migration. Connection-pool tuning is out of scope and is recorded as
 *       residual risk in the planned {@code DECISION_LOG.md} and {@code docs/validation-gates.md}.</li>
 *   <li><strong>Troubleshooting</strong> - a startup failure whose message begins
 *       {@code Schema-validation:} and names {@code daily_transaction} means the migration
 *       disagrees with the column contract above; compare it against that table before
 *       changing anything here. That contract was checked against PostgreSQL 16.10 under
 *       {@code ddl-auto: validate} before this interface was committed, and the negative case
 *       was checked too: giving either 26-character stamp a temporal column type makes the
 *       context refuse to start, reporting a wrong column type for {@code dalytran_orig_ts}
 *       and naming {@code char(26)} as the type it expected. Output that
 *       differs from the baseline only in row order means a multi-row read lost its ordering.
 *       An amount that is correct in magnitude but wrong in sign means an
 *       absolute-value normalisation was introduced somewhere on the path. A load that drops
 *       exactly 50 of 300 rows means the origin column was bound to the enum. A load that
 *       drops all 300 means a stamp was bound to a temporal type.</li>
 *   </ul>
 *
 * <h2>Not available - information this contract still lacks</h2>
 * <p>Rule 1 Clause F requires that missing information be stated plainly rather than guessed
 * at, together with what would be needed. Three items qualify; a fourth, recorded below, has since
 * been closed.</p>
 *
 * <p><strong>Closed: all three Flyway migrations are present.</strong>
 * {@code src/main/resources/db/migration/V1__create_schema.sql} declares this
 * table exactly as the column contract above states - primary key {@code ingest_seq NUMERIC(9)},
 * {@code dalytran_id CHAR(16) NOT NULL} with no uniqueness, {@code dalytran_amt NUMERIC(11,2)},
 * both stamps {@code CHAR(26)}, no foreign key, no version column and no index. So that contract
 * is a reproduction rather than a specification, and it is retained here because
 * {@code ddl-auto} is {@code validate} and any disagreement fails context startup outright
 * rather than degrading quietly. An earlier revision of this paragraph said
 * {@code V2__create_indexes.sql} and {@code V3__seed_data.sql} did not yet exist; that is no longer
 * true and the claim is withdrawn. {@code V2} creates no index for this table, as required.
 * {@code V3} seeds 300 rows of 350 bytes each from
 * {@code app/data/ASCII/dailytran.txt}, a file of exactly 105,300 bytes, which is 300 records of
 * 350 bytes plus one line terminator each, assigning {@code ingest_seq} as the one-based row
 * ordinal in file order. Its signed amounts are decoded position-aware from the PIC clauses
 * - {@code &#123;} to positive zero, {@code A} through {@code I} to positive one through nine,
 * {@code &#125;} to negative zero, {@code J} through {@code R} to negative one through nine -
 * and never by a global text replacement, because those same letters occur legitimately inside
 * merchant names.</p>
 *
 * <p><strong>Not available: a fixture that can fail if uniqueness is reintroduced.</strong>
 * Severity Medium, and it becomes High the moment any artefact re-derives a unique key from
 * {@code dalytran_id}. The shipped 300-record fixture is both unique on that field and already
 * ascending by it, so it cannot distinguish a correct keyless mapping from an invented unique
 * one, and it cannot distinguish ordering by ordinal from ordering by identifier. Nothing
 * further is needed from the source, which has been read exhaustively on this point - what is
 * needed is a purpose-built regression fixture under {@code src/test/resources} carrying
 * <strong>duplicate</strong> and <strong>out-of-order</strong> transaction identifiers, plus a
 * row count larger than one chunk so that slice iteration is exercised across a boundary. That
 * fixture is the assigned responsibility of the batch and integration milestone that owns
 * {@code src/test/java/com/cardemo/integration/repository}; it is recorded here so the gap is
 * tracked rather than assumed closed.</p>
 *
 * <p><strong>Not available: a catalogued key length and record size for this dataset.</strong>
 * What would be needed is a cluster entry in {@code app/catlg/LISTCAT.txt} or an IDCAMS
 * {@code DEFINE CLUSTER} carrying a {@code KEYS(...)} clause. Neither exists, and neither is
 * expected to, because the dataset is physical sequential rather than a keyed cluster. The
 * geometry asserted here rests on {@code app/jcl/POSTTRAN.jcl:L31},
 * {@code app/jcl/TRANFILE.jcl:L70} and {@code app/cpy/CVTRA06Y.cpy:L4-L18} alone, plus the
 * non-VSAM catalogue entry at {@code app/catlg/LISTCAT.txt:L786} as corroboration.</p>
 *
 * <p><strong>Not available: file status {@code '35'} as a grounded source construct.</strong>
 * No literal {@code '35'} appears anywhere in {@code app/cbl}, and the CICS response census
 * across the corpus is {@code NORMAL} 43, {@code NOTFND} 23, {@code ENDFILE} 8,
 * {@code DUPREC} 7, {@code DUPKEY} 3 and {@code NOTOPEN} zero. The file-unavailable branch of
 * the status taxonomy is therefore derived from the target specification rather than observed
 * in the source, and is labelled as such wherever it appears.</p>
 *
 * @see DailyTransaction
 */
@Repository
public interface DailyTransactionRepository extends JpaRepository<DailyTransaction, Long> {

    /**
     * Reads one chunk of staged daily-transaction rows in file order - ascending by ingestion
     * ordinal - so a caller streams the input instead of loading it whole.
     *
     * <p><strong>Purpose.</strong> This is the forward sequential read, and it is the only
     * declared method of this interface. It is what
     * {@code com.cardemo.batch.readers.DailyTransactionReader} (<strong>planned</strong>; not yet
     * authored) will use to reproduce
     * {@code 1000-DALYTRAN-GET-NEXT} at {@code app/cbl/CBTRN02C.cbl:L345} chunk by chunk, which
     * is the closest available analogue to the legacy behaviour of holding one record live at a
     * time between {@code OPEN INPUT DALYTRAN-FILE} at {@code :L238} and
     * {@code CLOSE DALYTRAN-FILE} at {@code :L584}. There is no key, no reposition, no backward
     * read and no read-for-update anywhere against this dataset, so no such capability is
     * offered here.</p>
     *
     * <p><strong>Iterate it to walk the whole file.</strong> There is no whole-file
     * {@code java.util.List} form and there must not be one: a result set bounded only by the
     * size of the staging table would materialise every row into the heap at once, which is the
     * opposite of what the source does. Advance the page index until the returned slice reports
     * that no further rows follow - that loop is the direct analogue of
     * {@code PERFORM UNTIL END-OF-FILE = 'Y'} at {@code :L202-L219}, and it bounds heap use by
     * the chunk size rather than by the input volume.</p>
     *
     * <p><strong>Why a slice and not a page.</strong> A page would issue an extra counting
     * query per chunk to compute a total the job does not use. The source keeps its own tally
     * by incrementing {@code WS-TRANSACTION-COUNT} per record at
     * {@code app/cbl/CBTRN02C.cbl:L206} and never asks the file how many records it holds, so
     * paying for a total on every chunk would buy nothing and cost a full scan each time. A
     * slice reports only whether more data follows, which is precisely the end-of-data question
     * that same loop asks.</p>
     *
     * <p><strong>Ordering is part of the contract, and it is the ordinal.</strong> The ascending
     * ingestion-ordinal ordering is encoded in the method name so that it is applied on every
     * call and cannot be omitted by a caller. It restores the single deterministic sequence the
     * flat file had by construction and the table does not have at all - the ordinal
     * <em>is</em> the record's position in the file. Two properties follow, and both are
     * load-bearing. It is a <em>faithful</em> order, so the reject and posted-transaction output
     * come out in the sequence the source produced them, which the field-level comparison
     * against the legacy baseline depends on. And it is a <em>total</em> order, because the
     * ordinal is the primary key, so chunk boundaries are stable and no row can be skipped or
     * repeated across them. Ordering by {@code dalytran_id} instead would forfeit both: it is
     * only coincidentally the file order in the shipped fixture, and it is not unique, so it is
     * not total. A sort supplied on the pageable can only be appended after this ordering as a
     * tie-breaker and can never displace it; passing an unsorted pageable, for example one built
     * from a size alone, remains the expected usage.</p>
     *
     * <p><strong>Named call sites - all three still to be authored.</strong> This method has no
     * production caller yet, which is a statement about sequencing rather than about the method:
     * {@code com.cardemo.batch.readers.DailyTransactionReader}, feeding
     * {@code com.cardemo.batch.jobs.DailyTransactionPostingJob}, are both <strong>planned</strong>,
     * as is the read-only pre-flight step of that same job derived from
     * {@code app/cbl/CBTRN01C.cbl}, which iterates the staged
     * input to report on it without writing anything. On the test side,
     * {@code src/test/java/com/cardemo/integration/repository} exists and holds the Testcontainers
     * base, but no concrete subclass yet asserts the ordering, the
     * acceptance of a repeated {@code dalytran_id}, or the round-trip of a negative amount, a
     * blank processing stamp and an {@code OPERATOR} origin value. Those remain owed.</p>
     *
     * <p><strong>Side effects.</strong> None. The call is read-only: it inserts, updates and
     * deletes nothing, mutates no argument, holds no state between calls and writes no log
     * record.</p>
     *
     * <p><strong>Failure modes.</strong> An empty staging table, or a page index beyond the
     * staged data, yields an empty slice reporting no further content. That is the end-of-data
     * signal and not an error - the direct counterpart of file status {@code '10'}, which at
     * {@code app/cbl/CBTRN02C.cbl:L345-L369} sets the end-of-file flag and terminates the loop
     * rather than failing. The return value is never null. A negative amount, a 26-space
     * processing stamp and a {@code dalytran_id} that duplicates one on another staged row are
     * all valid content and are returned unchanged. A connectivity or schema fault propagates as
     * the persistence provider's unchecked exception with its root cause intact; it is neither
     * caught nor wrapped here, and is translated once in
     * {@code com.cardemo.service.shared.FileStatusMapper}.</p>
     *
     * @param pageable the chunk position and size to read; expected to be unsorted, since the
     *                 ascending ingestion-ordinal ordering is already fixed by this method and
     *                 any additional sort acts only as a tie-breaker after it
     * @return the requested chunk ordered ascending by {@code ingest_seq}, reporting whether
     *         further rows follow; empty when the position is past the end, never null
     */
    Slice<DailyTransaction> findAllByOrderByIngestSequenceAsc(Pageable pageable);
}
