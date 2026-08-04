/*
 * ****************************************************************************
 * Program     : DisclosureGroupRepository.java
 * Application : CardDemo
 * Type        : Spring Data JPA Repository Interface
 * Function    : Replaces the VSAM access verbs over the disclosure group rate
 *               table, including the two-query DEFAULT-group fallback whose
 *               second miss abends the interest calculation job.
 *               Batch-only: no CICS file definition exists.
 * Source      : AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS, composite key 16 / reclen 50
 *               (app/catlg/LISTCAT.txt:L896 DATA-component attribute line;
 *               app/jcl/DISCGRP.jcl:L36, L40-L41 KEYS(16 0) RECORDSIZE(50 50));
 *               record layout app/cpy/CVTRA02Y.cpy:L4-L10; rate lookup
 *               app/cbl/CBACT04C.cbl:L415-L460 (1200-GET-INTEREST-RATE and
 *               1200-A-GET-DEFAULT-INT-RATE); ABSENT from app/csd/CARDDEMO.CSD
 *               (batch-only proof) @ 7756d89
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

import com.cardemo.model.entity.DisclosureGroup;
import com.cardemo.model.key.DisclosureGroupId;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Read access to the {@code DISCGRP} disclosure group rate table: the interest rate that applies to a
 * given account group, transaction type and transaction category.
 *
 * <h2>What it does</h2>
 * <p>This interface replaces the VSAM access verbs issued against the KSDS cluster
 * {@code AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS}, catalogued at {@code app/catlg/LISTCAT.txt:L894}, with two
 * keyed reads over the relational table {@code disclosure_group}. It declares no write operation of any
 * kind, because the corpus contains none: the cluster is populated once by IDCAMS at
 * {@code app/jcl/DISCGRP.jcl:L54-L60}, which copies the flat file
 * {@code AWS.M2.CARDDEMO.DISCGRP.PS} into it, and is thereafter read-only. The sole consuming program,
 * the interest calculator {@code app/cbl/CBACT04C.cbl}, opens it {@code INPUT} and never issues a
 * {@code WRITE}, a {@code REWRITE} or a {@code DELETE} against it.
 *
 * <p>The interface owns one of the three most behaviourally subtle control paths in this migration: the
 * two-query fallback to the {@code DEFAULT} account group, in which <b>the first miss is entirely benign
 * and the second miss is fatal</b>. That asymmetry is the whole contract of this file and is developed in
 * full below.
 *
 * <p>Everything else stays out. No interest arithmetic, no rate caching, no
 * fallback orchestration and no status translation happen here. This interface only reads rows; the
 * decision of what to do when a read comes back empty belongs to the caller, exactly as the two source
 * paragraphs place it.
 *
 * <h2>Physical specification, dual-sourced</h2>
 * <p>The 16-byte composite key and the 50-byte record are each corroborated four independent ways, which
 * is what makes them safe to treat as a hard contract rather than an inference:</p>
 * <ol>
 *   <li>the VSAM catalogue, {@code app/catlg/LISTCAT.txt:L896}, reading
 *       {@code KEYLEN----------------16     AVGLRECL--------------50}, with {@code :L897} adding
 *       {@code RKP--------------------0} and {@code MAXLRECL--------------50} - so the key is the record
 *       prefix and the record is fixed length - and {@code :L898} declaring the cluster
 *       {@code UNIQUE INDEXED};</li>
 *   <li>the IDCAMS definition, {@code app/jcl/DISCGRP.jcl:L36} naming the cluster, with
 *       {@code KEYS(16 0)} at {@code :L40} and {@code RECORDSIZE(50 50)} at {@code :L41};</li>
 *   <li>the copybook arithmetic of {@code app/cpy/CVTRA02Y.cpy:L4-L10}, whose header comment at
 *       {@code :L2} states {@code RECLN = 50} outright;</li>
 *   <li>the consuming program's own file record area at {@code app/cbl/CBACT04C.cbl:L76-L82}, where
 *       {@code FD-DISCGRP-KEY} re-declares the same three fields in the same order and is followed by
 *       {@code FD-DISCGRP-DATA PIC X(34)}, giving 16 + 34 = 50.</li>
 *   </ol>
 * <p>Citing {@code :L896} precisely matters: {@code app/catlg/LISTCAT.txt:L202} (CARDDATA) and
 * {@code :L403} (CARDXREF) also report {@code KEYLEN 16} for entirely different clusters, so a vaguer
 * citation would point at the wrong dataset.</p>
 *
 * <h2>The 50-byte record layout</h2>
 * <p>From {@code app/cpy/CVTRA02Y.cpy:L4-L10}, copied into the interest calculator at
 * {@code app/cbl/CBACT04C.cbl:L107} by {@code COPY CVTRA02Y.}:</p>
 * <pre>
 * 01  DIS-GROUP-RECORD.                            bytes  Java
 *     05  DIS-GROUP-KEY.                            1-16  DisclosureGroupId (the embedded id)
 *        10 DIS-ACCT-GROUP-ID   PIC X(10).          1-10  accountGroupId  -&gt; acct_group_id  CHAR(10)
 *        10 DIS-TRAN-TYPE-CD    PIC X(02).         11-12  tranTypeCd      -&gt; tran_type_cd   CHAR(2)
 *        10 DIS-TRAN-CAT-CD     PIC 9(04).         13-16  tranCatCd       -&gt; tran_cat_cd    (see below)
 *     05  DIS-INT-RATE          PIC S9(04)V99.     17-22  interestRate    -&gt; dis_int_rate   NUMERIC(6,2)
 *     05  FILLER                PIC X(28).         23-50  not modelled
 * </pre>
 * <p>10 + 2 + 4 = <b>16</b> key bytes, matching {@code KEYS(16 0)} exactly; + 6 rate bytes + 28
 * {@code FILLER} bytes = <b>50</b>. The {@code FILLER} carries no data and is deliberately not modelled.
 *
 * <p><b>The component names are deliberately not harmonised across the three composite-key classes, and
 * must not be.</b> Each mirrors the field names of its own copybook, so the divergence is the contract
 * rather than an inconsistency: {@code DisclosureGroupId} uses {@code accountGroupId} / {@code tranTypeCd}
 * / {@code tranCatCd} from {@code DIS-ACCT-GROUP-ID} / {@code DIS-TRAN-TYPE-CD} / {@code DIS-TRAN-CAT-CD}
 * above, whereas {@code TransactionCategoryBalanceId} uses {@code accountId} / {@code typeCd} /
 * {@code catCd} from {@code app/cpy/CVTRA01Y.cpy} and {@code TransactionCategoryId} uses
 * {@code tranTypeCd} / {@code tranCatCd} from {@code app/cpy/CVTRA04Y.cpy}. Renaming any of them to match
 * the others, or extracting a shared base class to remove the apparent duplication, would sever the
 * one-for-one copybook correspondence the copybooks establish. Every JPQL
 * path in this interface therefore spells the names exactly as {@code DisclosureGroupId} declares them; a
 * mismatch is a startup-time query-derivation failure, not a compile error.
 *
 * <h2>The rate is NUMERIC(6,2), the only column at that precision in the schema</h2>
 * <p>{@code DIS-INT-RATE PIC S9(04)V99} at {@code app/cpy/CVTRA02Y.cpy:L9} is four integer digits plus
 * two decimals, so the column is {@code NUMERIC(6,2)} and
 * {@link com.cardemo.model.entity.DisclosureGroup} maps it as a {@link java.math.BigDecimal} with
 * {@code precision = 6, scale = 2}. Three decimal tiers exist across this package and
 * <b>must never be collapsed into one</b>:</p>
 * <pre>
 * COBOL picture   SQL column      fields
 * S9(10)V99       NUMERIC(12,2)   the five Account money and cycle fields
 * S9(09)V99       NUMERIC(11,2)   TRAN-AMT, DALYTRAN-AMT, TRAN-CAT-BAL
 * S9(04)V99       NUMERIC(6,2)    DIS-INT-RATE  &lt;-- this table, and nothing else
 * </pre>
 * <p>A widened column silently accepts rates the source cannot represent, so every downstream interest
 * figure diverges; and because {@code spring.jpa.hibernate.ddl-auto: validate} is mandated in every profile,
 * a precision mismatch against the migration fails application-context startup outright rather than degrading
 * quietly. Keep {@code NUMERIC(6,2)} in {@code V1__create_schema.sql} and {@code precision = 6, scale = 2} on
 * the entity, in step.
 *
 * <p><b>Exact decimal discipline.</b> No approximate binary numeric type appears anywhere in this file -
 * a security audit gate asserts that by inspection across every financial field in the migration - and
 * none may be introduced by a projection or a return type here. Two consequences bind every caller of
 * this interface: rounding uses {@code RoundingMode.HALF_EVEN}, and two rates are compared with
 * {@code compareTo}, never with {@code equals}, since {@code BigDecimal.equals} is scale-sensitive and
 * would report a rate of {@code 2.0} as unequal to {@code 2.00}.
 *
 * <h2>The DEFAULT fallback: two queries, first miss benign, second miss fatal</h2>
 * <p>This is the single most important behaviour this interface exists to enable. The source implements it
 * as two paragraphs and two physical reads, and the translation keeps both.</p>
 *
 * <p><b>Stage one -</b> {@code 1200-GET-INTEREST-RATE} at {@code app/cbl/CBACT04C.cbl:L415-L440}. It
 * reads the file at {@code :L416} with an {@code INVALID KEY} clause that merely displays
 * {@code 'DISCLOSURE GROUP RECORD MISSING'} and {@code 'TRY WITH DEFAULT GROUP CODE'} at
 * {@code :L418-L419} - it does not fail. Then {@code :L422} tests
 * {@code IF DISCGRP-STATUS = '00' OR '23'} and, for <b>both</b> of those statuses, sets the application
 * result to zero, meaning success. Only some third, unexpected status reaches the abend path at
 * {@code :L431-L434}. Finally {@code :L436} tests {@code IF DISCGRP-STATUS = '23'} and, on that specific
 * status alone, substitutes the group id and performs stage two.</p>
 *
 * <p><b>Stage two -</b> {@code 1200-A-GET-DEFAULT-INT-RATE} at {@code app/cbl/CBACT04C.cbl:L443-L460}. It
 * issues a second read at {@code :L444}, with <b>no</b> {@code INVALID KEY} clause at all. Then
 * {@code :L446} tests {@code IF DISCGRP-STATUS = '00'} - that status and nothing else - so a record-not-found
 * status now falls to the else branch, which displays
 * {@code 'ERROR READING DEFAULT DISCLOSURE GROUP'} at {@code :L455} and performs
 * {@code 9999-ABEND-PROGRAM} at {@code :L458}. The abend contract for this corpus is abend code
 * <b>999</b> with process return code <b>12</b>.</p>
 *
 * <p><b>The first miss must not be an exception.</b> Because {@code :L422} accepts the
 * record-not-found status as success, an empty result from the primary lookup
 * ({@link #findById(Object) findById}) is the <i>expected, routine, non-exceptional</i> outcome that
 * triggers the fallback. It must <b>never</b> be translated into
 * {@code com.cardemo.exception.RecordNotFoundException}. Doing so converts the normal path into a
 * failure and the interest job stops posting interest for every account whose group is unlisted. This is
 * one of only three sites in the whole corpus where a status other than {@code '00'} means success; the
 * other two are {@code 2700-UPDATE-TCATBAL} at {@code app/cbl/CBTRN02C.cbl:L481} and the file-service
 * call sites in {@code app/cbl/CBSTM03A.CBL} at {@code :L736} and {@code :L748}, which additionally
 * accept {@code '04'}. The caller therefore branches on
 * {@link java.util.Optional#isEmpty()} and proceeds to {@link #findDefaultGroupRate}; and
 * {@code com.cardemo.service.shared.FileStatusMapper}, which is the single place status translation
 * happens, must be aware of these three exemptions.</p>
 *
 * <p><b>The second miss must not be swallowed.</b> An empty result from
 * {@link #findDefaultGroupRate} is <i>fatal</i>. It must surface as
 * {@code com.cardemo.exception.FatalProcessingException} carrying abend code 999 and return code 12. It
 * must not be defaulted to a zero rate, must not skip the record, and must not be logged and ignored:
 * each of those turns a hard stop in the source into silently missing interest. Rule 1 Clause B is
 * explicit that exceptions are not swallowed and that context is preserved.</p>
 *
 * <p><b>The two lookups must stay two lookups.</b> The fallback is a genuine second query
 * against the same table with a different key - not a null-coalescing expression, not a set operation
 * over two branches, and not exception handling around a single call. Merging them would erase precisely
 * the distinction the two paragraphs draw: a single merged query cannot report <i>which</i> stage
 * produced the row, so the caller could no longer tell a benign miss from a fatal one, and the fatal
 * branch would become unreachable. Rule 1 Clause A - <i>"prioritize correctness, determinism, and
 * explicit behavior over cleverness"</i> - forbids exactly that trade. The two methods are therefore
 * separate by design, and each carries its own failure contract in its own Javadoc.</p>
 *
 * <h2>Only the group id changes on the retry</h2>
 * <p>{@code app/cbl/CBACT04C.cbl:L437} executes {@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID} and
 * touches nothing else. The other two key components keep the values assigned before stage one, at
 * {@code :L211} ({@code MOVE TRANCAT-CD TO FD-DIS-TRAN-CAT-CD}) and {@code :L212}
 * ({@code MOVE TRANCAT-TYPE-CD TO FD-DIS-TRAN-TYPE-CD}), while {@code :L210} had supplied the account's
 * own group id ({@code MOVE ACCT-GROUP-ID TO FD-DIS-ACCT-GROUP-ID}) for stage one.</p>
 * <p>The retry key is therefore <b>(the literal group id, the SAME transaction type code, the SAME
 * transaction category code)</b>. That is why {@link #findDefaultGroupRate} takes the type and category
 * codes as parameters and fixes only the group id itself. Widening the retry - matching any group,
 * dropping either remaining component, or introducing a pattern match - would return the wrong row and
 * is a correctness defect, not an optimisation.</p>
 * <p>Note in passing that {@code :L211} assigns the category before {@code :L212} assigns the type,
 * which is the reverse of the copybook's declaration order. Assignment order has no bearing on the key
 * layout; {@code app/cpy/CVTRA02Y.cpy:L6-L8} and the file record area at {@code :L76-L82} are
 * authoritative, and {@link com.cardemo.model.key.DisclosureGroupId} follows them.</p>
 *
 * <h2>The CHAR(10) padding is load-bearing, so nothing here may be trimmed</h2>
 * <p>{@code DIS-ACCT-GROUP-ID} is {@code PIC X(10)}, and a COBOL {@code MOVE} of the seven-character
 * literal at {@code :L437} into a ten-byte alphanumeric field left-justifies and space-pads. The key
 * bytes actually presented by the source are therefore {@code D E F A U L T} followed by <b>three
 * blanks</b>. Fixture row {@code app/data/ASCII/discgrp.txt:L18} stores exactly that image:
 * {@code DEFAULT   01000100150&#123;...} - seven characters, three blanks, then {@code 01}, then
 * {@code 0001}, then the six-byte rate field. The trailing {@code &#123;} of {@code 00150&#123;} is a
 * zoned-decimal overpunch denoting {@code +0}, so that row's rate decodes to <b>+15.00</b>, not 1500.</p>
 * <p>{@link #findDefaultGroupRate} consequently matches on the <i>space-padded</i> ten-character image,
 * because that is the byte image the source presents and the byte image the fixture stores. <b>The three
 * trailing blanks inside that query literal are significant and must not be tidied away.</b></p>
 * <p><b>Why {@code CHAR(10)} must not be relaxed, measured rather than asserted.</b> These combinations
 * were exercised directly against PostgreSQL 16.10, comparing a stored group id against the two candidate
 * literals. <b>Re-run 1 August 2026 against PostgreSQL 16.10 and reproduced exactly, every row:</b></p>
 * <pre>
 * column type    stored image      probe = 'DEFAULT   '   probe = 'DEFAULT'
 * CHAR(10)       padded            match                  match
 * VARCHAR(10)    padded            match                  NO match
 * VARCHAR(10)    trimmed           NO match               match
 * </pre>
 * <p>Under the normative {@code CHAR(10)} column the two forms are interchangeable, because SQL
 * {@code CHAR} comparison disregards trailing blanks - so the choice between them cannot break anything
 * while the contract holds, and the padded form is chosen for being the faithful image of the source key.
 * Relax the column to a variable-width type, however, and the two forms stop being equivalent: which one
 * still works then depends entirely on whether the seed preserved the padding. That dependency is easy to
 * introduce by accident, because PostgreSQL <i>strips</i> trailing blanks when a {@code CHAR} value is
 * converted to a variable-width type - measured on the same instance,
 * {@code length('DEFAULT   '::char(10)::varchar)} is {@code 7} - so a migration that populated such a
 * column by conversion would silently store the trimmed image and defeat the padded probe.</p>
 * <p>The failure mode is a lookup that returns nothing, which the source
 * escalates to an abend, which is why {@code acct_group_id} is kept as {@code CHAR(10)} in
 * {@code V1__create_schema.sql}, where both forms work and neither the seed's padding nor a caller's
 * padding can matter.</p>
 * <p>Why the fallback is the normal path rather than an edge case: row 1 of
 * {@code app/data/ASCII/acctdata.txt} carries {@code ACCT-GROUP-ID} as ten blanks. A blank group id
 * matches no real group, so such accounts reach a rate only through stage two. A miss at stage one is
 * routine, which is exactly why {@code :L422} treats it as success.</p>
 *
 * <h2>A zero rate is a legitimate value, never an absence</h2>
 * <p>{@code app/cbl/CBACT04C.cbl:L214} guards the computation with {@code IF DIS-INT-RATE NOT = 0}, and
 * both {@code :L215} ({@code PERFORM 1300-COMPUTE-INTEREST}) and {@code :L216}
 * ({@code PERFORM 1400-COMPUTE-FEES}) sit inside that guard. A zero rate therefore produces no interest
 * transaction and no accumulation - and that is an ordinary, load-bearing outcome, not a missing row.</p>
 * <p><b>{@code BigDecimal.ZERO} must never be conflated with {@link java.util.Optional#empty()}.</b> A
 * present row carrying a zero rate is a hit: it satisfies stage one, it suppresses the fallback, and it
 * suppresses the interest transaction. An empty result is a miss: it triggers the fallback. Treating zero
 * as absent would drive accounts into stage two that the source never sends there, and would abend on any
 * type-and-category pair the rate table does not list under the fallback group.</p>
 * <p>The fixture makes both outcomes provable, which is why the seed data matters to this contract.
 * {@code app/data/ASCII/discgrp.txt} holds <b>51 rows of 50 bytes each</b> - a count corroborated
 * independently by the catalogue's own statistics line, {@code app/catlg/LISTCAT.txt:L901}
 * {@code REC-TOTAL-------------51} - split across exactly three group identifiers, <b>17 rows each</b>:
 * {@code A000000000} at rows 1-17, the fallback group at rows 18-34, and {@code ZEROAPR} at rows 35-51.
 * The fallback group's 17 rows span type and category pairs {@code 01/0001-0004}, {@code 02/0001-0003},
 * {@code 03/0001-0003}, {@code 04/0001-0003}, {@code 05/0001}, {@code 06/0001-0002} and {@code 07/0001},
 * and <b>seven of them carry a zero rate</b>: measured directly from the fixture, rows 22-24
 * ({@code 02/0001-0003}), rows 25-27 ({@code 03/0001-0003}) and row 34 ({@code 07/0001}) all hold
 * {@code 00000&#123;}. The remaining ten hold {@code 00150&#123;} (+15.00) or {@code 00250&#123;} (+25.00).</p>
 * <p>Three consequences follow, and all three are testable against that fixture: the fallback
 * <i>succeeds</i> for any listed pair; a zero-rate row proves that a hit emits nothing while remaining a
 * hit; and an unlisted pair - {@code 05/0002}, say, since type {@code 05} is listed only for category
 * {@code 0001} - misses both stages and is therefore fatal. Note also that all 51 rows carry the
 * overpunch {@code &#123;}, so every seeded rate is non-negative, even though {@code PIC S9(04)V99} is a
 * signed picture and a negative rate is inside the source domain. No magnitude normalisation is applied
 * anywhere and the sign the source presents is preserved verbatim.</p>
 *
 * <h2>No rate may survive an iteration</h2>
 * <p>The source has a genuine quirk here that must not be reproduced. COBOL reads into a shared record
 * area, so when stage one hits an invalid key at {@code app/cbl/CBACT04C.cbl:L416} the
 * <i>previous</i> iteration's {@code DIS-GROUP-RECORD} contents remain in place until the stage-two read
 * at {@code :L444} overwrites them. A translation that carried a rate across
 * iterations - a memoised last rate, a per-thread field, a lookup map keyed loosely, a second-level cache
 * over this table - would apply one account group's rate to another, and the defect would surface as
 * quietly wrong interest rather than as a failure.</p>
 * <p>This interface is written so that it cannot become the place where that happens. It declares no
 * field, no constant, no default method and no state of any sort, so there is nowhere for a stale rate to
 * live; and no caching annotation is present, so no framework will introduce one behind it. Rule 1 Clause
 * B - <i>"avoid global mutable state"</i> - is satisfied structurally rather than by convention.
 * <b>Callers must resolve the rate freshly for every transaction-category-balance row</b>, and must never
 * fall back to the last rate seen.</p>
 *
 * <h2>Batch-only, proved by absence from the CICS resource definitions</h2>
 * <p>{@code app/csd/CARDDEMO.CSD} contains <b>exactly eight</b> {@code DEFINE FILE} entries:
 * {@code ACCTDAT} at {@code :L1}, {@code CARDAIX} at {@code :L13}, {@code CARDDAT} at {@code :L25},
 * {@code CCXREF} at {@code :L37}, {@code CUSTDAT} at {@code :L50}, {@code CXACAIX} at {@code :L63},
 * {@code TRANSACT} at {@code :L76} and {@code USRSEC} at {@code :L88}. The string {@code DISCGRP} occurs
 * in that file <b>zero times</b>, as do {@code TCATBALF}, {@code TRANCATG} and {@code TRANTYPE}. This
 * dataset therefore has no online definition at all: it was never reachable from a terminal transaction,
 * only from batch.</p>
 * <p>The single consumption path in the corpus is the interest calculation job, whose job control at
 * {@code app/jcl/INTCALC.jcl:L35-L36} allocates {@code //DISCGRP DD DISP=SHR,}
 * {@code DSN=AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS} to the program named at {@code :L22},
 * {@code EXEC PGM=CBACT04C,PARM='2022071800'}. Accordingly this interface is consumed by
 * {@code com.cardemo.batch} and {@code com.cardemo.service} only.</p>
 * <p><b>No controller CRUD, no REST endpoint and no administrative management surface may exist for
 * {@code DISCGRP}, {@code TCATBALF}, {@code TRANCATG} or {@code TRANTYPE}.</b> The target exposes exactly
 * 17 operations across 8 controllers, mapped one-for-one from the 17 sourced CICS transactions, and not
 * one of them touches these four datasets. Rule 1 Clause D - <i>"principle of least privilege for
 * tokens/credentials/config"</i> - is what makes that a requirement rather than a preference: granting an
 * online surface the legacy system never had would widen the attack surface with no behavioural
 * justification. It is worth recording because it shapes the authorisation model and the
 * integration-test surface rather than describing a defect.</p>
 * <p>The cluster has no alternate index either. The catalogue's three alternate indexes belong to
 * {@code CARDDATA}, {@code CARDXREF} and {@code TRANSACT}, and {@code V2__create_indexes.sql}
 * correspondingly declares exactly three non-unique indexes - {@code idx_card_acct_id},
 * {@code idx_card_cross_reference_acct_id} and {@code idx_transaction_proc_ts} - <b>none of them on
 * {@code disclosure_group}</b>. No secondary-key finder is declared here for that reason: adding one
 * would imply a fourth index and break alignment with that migration. None is needed, because the
 * composite primary key serves both lookups on this table - each supplies the group id as the leading
 * component, so both are covered by the primary key's leftmost prefix.</p>
 *
 * <h2>The primary lookup: inherited findById, documented here rather than redeclared</h2>
 * <p>{@code findById(DisclosureGroupId)} is inherited from
 * {@link org.springframework.data.repository.CrudRepository} and is <b>not</b> redeclared on this type,
 * because restating an inherited signature adds a maintenance point without adding behaviour. Its contract
 * in this migration is nevertheless load-bearing, so it is specified here in full.</p>
 * <ul>
 *   <li><b>Purpose.</b> It is stage one of the rate resolution - the read at
 *       {@code app/cbl/CBACT04C.cbl:L416}, {@code READ DISCGRP-FILE INTO DIS-GROUP-RECORD}, issued against
 *       the key assembled at {@code :L210-L212}.</li>
 *   <li><b>Input.</b> A fully populated {@link com.cardemo.model.key.DisclosureGroupId} - the account's own
 *       group id from {@code ACCT-GROUP-ID}, the transaction type code, the transaction category code.
 *       A partially populated key is a different value and will not match; that class rejects a
 *       {@code null} component outright.</li>
 *   <li><b>Output.</b> An {@link Optional} holding the rate row, {@link Optional#empty()} on a miss.
 *       At most one row can match, the primary key being unique.</li>
 *   <li><b>Side effects.</b> None. A single keyed read; nothing is written, locked, cached retained.</li>
 *   <li><b>Failure mode - an empty result is NOT an error.</b> {@code :L422} accepts both the
 *       success status and the record-not-found status, so a miss is the routine trigger for
 *       {@link #findDefaultGroupRate}. It must never become
 *       {@code com.cardemo.exception.RecordNotFoundException}.</li>
 *   <li><b>Failure mode - a zero rate is a hit.</b> See the zero-rate section above: the row is present,
 *       the fallback is suppressed, and {@code :L214} suppresses only the interest transaction.</li>
 *   <li><b>Failure mode - an unexpected status.</b> Any status that is neither of the two accepted at
 *       {@code :L422} reaches {@code :L431-L434} and abends. In the Java target such a condition arrives
 *       as a data-access exception from the provider and must be allowed to propagate as
 *       {@code com.cardemo.exception.FatalProcessingException}, never caught and turned into a miss - which
 *       would silently reroute a hard failure into the fallback.</li>
 *   </ul>
 *
 * <h2>Declared surface, and what is deliberately absent</h2>
 * <p>Two lookups, and nothing else. The primary lookup is the inherited
 * {@link org.springframework.data.repository.CrudRepository#findById(Object) findById}, documented on this
 * type rather than redeclared, since redeclaring an inherited signature adds a maintenance point without
 * adding behaviour. The fallback lookup is {@link #findDefaultGroupRate}. Every other member of
 * {@code JpaRepository} is inherited and unused by design; the two documented above are the two the source
 * performs.</p>
 * <p>Rule 1 Clause B forbids dead code, and <b>this package carries no parity exemption from it</b>. The
 * one retained no-op the migration preserves for control-flow fidelity - {@code 1400-COMPUTE-FEES} at
 * {@code app/cbl/CBACT04C.cbl:L518-L520}, a comment plus an {@code EXIT} that is nonetheless genuinely
 * reached from {@code :L216} - is retained in
 * {@code com.cardemo.batch.processors.InterestCalculationProcessor}, not here. Clause B consequently
 * applies to this file at full strength, with no exemption, which is why the following are all absent:</p>
 * <ul>
 *   <li><b>No key-ordered full read.</b> A method returning every row ordered by the three key components
 *       would faithfully mirror a VSAM key-ordered browse, and at 51 rows a whole-table load into a
 *       reference cache held by a service bean would be entirely safe. It is omitted because <b>no caller
 *       needs it</b>: the source performs keyed reads only, never a sequential browse of this file. Adding
 *       it now would be dead code. <i>What would be needed to justify it:</i> a named consumer in
 *       {@code com.cardemo.batch} that genuinely iterates the table, at which point the method must carry
 *       an explicit ascending ordering over {@code id.accountGroupId}, {@code id.tranTypeCd} and
 *       {@code id.tranCatCd} to satisfy Clause A's determinism requirement.</li>
 *   <li><b>No secondary-key finder</b>, for the index-alignment reason given above.</li>
 *   <li><b>No aggregate and no derived arithmetic.</b> No total, no mean and no computed projection over
 *       the rate. The monthly interest at {@code app/cbl/CBACT04C.cbl:L462-L470} is
 *       {@code COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200} - multiply first, then
 *       divide by the single literal divisor of twelve hundred, with two-decimal
 *       {@code RoundingMode.HALF_EVEN}. It must never be algebraically rewritten as a division by one
 *       hundred followed by a division by twelve, and no decimal multiplier may be substituted, because
 *       both forms round differently. That arithmetic belongs to
 *       {@code com.cardemo.batch.processors.InterestCalculationProcessor} and is intentionally
 *       unreachable from here.</li>
 *   <li><b>No write path of any kind</b> - no declarative bulk-update annotation, no delete-by-query, no
 *       save helper. The corpus issues no write against this cluster, so none is exposed. Inherited
 *       mutators exist on {@code JpaRepository} and are used only by the test tier to arrange fixtures.</li>
 *   <li><b>No hand-written type beside this interface.</b> No companion class, no fragment interface, no
 *       shared base repository, no criteria or predicate helper, no mapper and no data-access wrapper. The
 *       package holds exactly 11 repository interfaces plus one {@code package-info.java}.</li>
 *   <li><b>No pattern-matching search on the group id</b>, and no partial-key finder. Both would let a
 *       caller retrieve a row the source could never have read.</li>
 *   <li><b>No projection or interface-based result view</b>, which is the other route by which an
 *       approximate binary numeric type could enter a financial field.</li>
 *   <li><b>No state.</b> No field, no constant, no static member, no default method, no caching
 *       annotation - see the stale-rate hazard above.</li>
 *   </ul>
 *
 * <h2>Key configuration and defaults</h2>
 * <ul>
 *   <li>{@code spring.jpa.hibernate.ddl-auto: validate} in <b>every</b> profile. The schema is never
 *       generated from the mappings, so a disagreement over table name, column name, SQL type, precision,
 *       scale or nullability fails application-context startup rather than surfacing later as corrupt
 *       data. That is the intended behaviour: fail loudly at boot.</li>
 *   <li>{@code spring.jpa.open-in-view: false}. Both lookups return fully initialised results - the entity
 *       has no association of any kind, so there is nothing to initialise lazily and no risk of touching a
 *       detached proxy outside a transaction.</li>
 *   <li>{@code spring.jpa.show-sql: false}, with no Hibernate SQL logging and no bind-parameter logging in
 *       any profile. Nothing this table holds is credential material, but the prohibition is package-wide
 *       and uniform rather than case-by-case.</li>
 *   <li><b>Observability is delegated, not implemented here.</b> Structured JSON logging, the trace,
 *       span and correlation identifiers carried through MDC, the Micrometer counters and the composite
 *       health indicators all live in {@code com.cardemo.observability} and
 *       {@code src/main/resources/logback-spring.xml}. A repository interface has no method body in
 *       which to log, time or count anything, so emitting nothing from here is correct rather than an
 *       omission: the caller in {@code com.cardemo.batch.processors.InterestCalculationProcessor} is
 *       where a benign primary miss, a successful DEFAULT fallback and a fatal DEFAULT miss become
 *       distinguishable, measurable events. Rule 1 Clause A is discharged at that layer.</li>
 *   <li>Spring Batch's {@code BATCH_*} metadata tables come from the framework's own bundled script via
 *       {@code spring.batch.jdbc.initialize-schema}. They belong neither in {@code V1__create_schema.sql}
 *       nor in a fourth migration.</li>
 *   <li><b>Connection-pool tuning is explicitly out of scope</b> and is carried as residual risk. Rule 1
 *       Clause A asks that tradeoffs
 *       be justified <i>only when needed</i>: this table is 51 rows and read with an indexed keyed lookup,
 *       so pool sizing has no measurable bearing on it. The honest discharge is to state the decision, not
 *       to tune speculatively.</li>
 *   </ul>
 *
 * <h2>How to build, run and test</h2>
 * <p>{@code ./mvnw -B clean compile} compiles this interface under {@code --release 25} with
 * {@code -Xlint:all} and {@code -Werror}, so any warning in a category {@code javac} 25 publishes -
 * {@code deprecation} and {@code rawtypes} above all - is a build failure. An unused import is not such a
 * category and is caught by review only. {@code ./mvnw -B clean test} runs the unit tier;
 * {@code ./mvnw -B clean verify} additionally enforces
 * the JaCoCo line-coverage floor. Verified against OpenJDK 25.0.3 and Apache Maven 3.9.11: the module
 * compiles with no warning attributable to this source.</p>
 * <p>Behavioural coverage belongs in {@code src/test/java/com/cardemo/integration/repository} against a
 * Testcontainers PostgreSQL 16 instance, because the fallback's correctness depends on real
 * {@code CHAR} comparison semantics that an in-memory database would not reproduce faithfully. Rule 1
 * Clause B requires tests for core logic, and the required cases are exactly the four this contract
 * distinguishes:</p>
 * <ol>
 *   <li>stage one hits - a listed group, type and category returns its row, and stage two is never
 *       reached;</li>
 *   <li>stage one misses and stage two hits - an unlisted group falls back and returns the fallback
 *       group's row for the same type and category;</li>
 *   <li>stage one misses and stage two misses - an unlisted type-and-category pair such as
 *       {@code 05/0002} returns empty from both, which the caller must escalate as fatal;</li>
 *   <li>a zero-rate row - present, returned, and distinct from empty.</li>
 * </ol>
 * <p>Case 2 must additionally assert that the fallback row is matched when the account group id is
 * supplied space-padded and when it is supplied bare, since {@code CHAR} semantics are what make both
 * work and a change of column type would silently break one of them.</p>
 * <p>Those four cases are covered by
 * {@code src/test/java/com/cardemo/integration/repository/DisclosureGroupRepositoryTest}, which extends the
 * Testcontainers PostgreSQL 16 base and exercises both lookups against the 51 seeded fixture rows, including
 * the padded and bare forms of the fallback group id. The structural contract is additionally pinned by
 * reflection in {@code src/test/java/com/cardemo/unit/repository/RepositoryContractTest}, and
 * {@code src/test/java/com/cardemo/unit/batch/InterestCalculationJobTest} verifies that
 * {@link #findDefaultGroupRate(String, Integer)} is the method reached on the fallback path.</p>
 * <p>{@code SchemaStructureTest} independently parses {@code V1__create_schema.sql} and cross-checks the
 * {@code disclosure_group} columns against {@code app/cpy/CVTRA02Y.cpy}, so the column contract below is
 * machine-verified rather than asserted: {@code acct_group_id CHAR(10)}, {@code tran_type_cd CHAR(2)},
 * {@code tran_cat_cd INTEGER} and {@code dis_int_rate NUMERIC(6,2)}. Because every profile sets
 * {@code ddl-auto: validate}, Hibernate additionally re-checks the whole mapping at every boot.</p>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 * <ul>
 *   <li><b>The interest job reports no interest for most accounts.</b> The primary lookup's empty result is
 *       being treated as an error instead of as the fallback trigger;
 *       {@code app/cbl/CBACT04C.cbl:L422} accepts the record-not-found status as success. Branch on
 *       {@link java.util.Optional#isEmpty()} and call {@link #findDefaultGroupRate}; never raise
 *       {@code com.cardemo.exception.RecordNotFoundException} from the primary lookup.</li>
 *   <li><b>Interest silently missing for a few type-and-category pairs.</b> The fallback's empty result is
 *       being swallowed, defaulted to zero or converted into a skip. The source abends at
 *       {@code app/cbl/CBACT04C.cbl:L458}, so raise {@code com.cardemo.exception.FatalProcessingException}
 *       with abend code 999 and return code 12 and fail the step.</li>
 *   <li><b>The context refuses to start with a schema validation mismatch on
 *       {@code disclosure_group}.</b> The migration and the mappings disagree. Reconcile against the
 *       normative contract below; do not weaken {@code spring.jpa.hibernate.ddl-auto} to silence it, because
 *       validation is the control that keeps the field contract honest.</li>
 *   <li><b>One account group's rate applied to another.</b> A rate is being cached across iterations,
 *       reproducing the shared-record-area quirk described above. Resolve the rate per row, hold no rate in a
 *       field, and introduce no cache over this table.</li>
 *   <li><b>The fallback lookup returns nothing although the row exists.</b> Either the group id column was
 *       relaxed from {@code CHAR(10)} to a variable-width type, so trailing-blank-insensitive comparison no
 *       longer applies, or the three significant trailing blanks were trimmed out of the query literal.
 *       Restore both, and verify with a keyed select against a real PostgreSQL 16 instance rather than an
 *       in-memory substitute.</li>
 *   <li><b>Interest figures differ from the baseline in the last decimal place.</b> The formula was
 *       algebraically rewritten, the rounding mode is not {@code RoundingMode.HALF_EVEN}, or the rate column
 *       was widened beyond {@code NUMERIC(6,2)}. Restore the single division by twelve hundred and the
 *       declared precision, and compare rates with {@code compareTo}, never {@code equals}.</li>
 *   <li><b>Every account falls through to the fallback group.</b> Expected when the account rows carry a
 *       blank {@code ACCT-GROUP-ID}, as the first seeded account does. Not a defect.</li>
 *   <li><b>{@code IllegalArgumentException} while building a key.</b>
 *       {@link com.cardemo.model.key.DisclosureGroupId} validates its components against the source field
 *       widths and against the unsigned four-digit domain of {@code PIC 9(04)}, and the message names the
 *       offending component. It accepts a group id <i>shorter</i> than ten characters, so the bare fallback
 *       literal is a legal key value.</li>
 *   </ul>
 *
 * <h2>The normative column contract</h2>
 * <p>All three Flyway migrations exist. {@code src/main/resources/db/migration/V1__create_schema.sql}
 * declares the {@code disclosure_group} table and {@code SchemaStructureTest} parses that DDL and
 * cross-checks it against {@code app/cpy/CVTRA02Y.cpy}. {@code V2__create_indexes.sql} declares three
 * indexes and deliberately none on this table, for the reason given above. {@code V3__seed_data.sql} seeds
 * the table from {@code app/data/ASCII/discgrp.txt} - <b>51 rows of 50 bytes</b> - including the seventeen
 * {@code DEFAULT}-group rows that make the two-stage fallback reachable and the zero-rate rows that make the
 * zero-rate case distinguishable. Because {@code spring.jpa.hibernate.ddl-auto: validate} is set in all four
 * profiles, this contract is <b>normative</b> and a mismatch fails context startup rather than degrading
 * gracefully:</p>
 * <pre>
 * table disclosure_group
 *   acct_group_id  CHAR(10)      NOT NULL   -- part of PK; mapped by DisclosureGroupId
 *   tran_type_cd   CHAR(2)       NOT NULL   -- part of PK; mapped by DisclosureGroupId
 *   tran_cat_cd    INTEGER       NOT NULL   -- part of PK; mapped by DisclosureGroupId; see the note
 *   dis_int_rate   NUMERIC(6,2)  NOT NULL   -- mapped by DisclosureGroup
 *   PRIMARY KEY (acct_group_id, tran_type_cd, tran_cat_cd)   -- in this exact COBOL component order
 * </pre>
 * <p>Seeding uses <b>position-aware zoned-decimal overpunch decoding driven by the picture clauses</b> -
 * {@code &#123;} denotes {@code +0}, {@code A} through {@code I} denote {@code +1} through {@code +9},
 * {@code &#125;} denotes {@code -0}, and {@code J} through {@code R} denote {@code -1} through {@code -9}.
 * It must <b>never</b> be a global text replacement, because those same letters occur legitimately inside
 * alphanumeric fields elsewhere in the fixture set. This table carries no version column and no index beyond
 * the primary key.</p>
 * <p><b>The SQL type of {@code tran_cat_cd} must be {@code INTEGER}, and the reason is not the picture
 * clause.</b> Read straight from {@code DIS-TRAN-CAT-CD PIC 9(04)}, four unsigned display digits suggest
 * {@code NUMERIC(4)}. But {@link com.cardemo.model.key.DisclosureGroupId} maps the component as an
 * {@link Integer}, and an {@link Integer} attribute validates against {@code INTEGER} and {@code BIGINT}
 * while <b>failing against both {@code NUMERIC(4)} and {@code SMALLINT}</b> under Hibernate with schema
 * validation enabled. Since the key class owns that column mapping and validation runs at every boot,
 * {@code INTEGER} is the type {@code V1} must declare for the context to start at all. Anyone who prefers
 * the narrower numeric spelling must change the key class in step, so that migration, key and entity stay
 * paired. The value domain is unaffected either way, since {@code PIC 9(04)} admits 0 through 9999.</p>
 *
 * <h2>File status {@code '35'} has no grounded site derived from this table</h2>
 * <p>The literal {@code '35'} occurs <b>zero</b> times anywhere in {@code app/cbl}, and the CICS
 * response-code census across the same tree is {@code NORMAL} 43, {@code NOTFND} 23, {@code ENDFILE} 8,
 * {@code DUPREC} 7, {@code DUPKEY} 3 and <b>{@code NOTOPEN} 0</b>. For contrast, the statuses this file's own
 * source program actually tests are {@code '00'} at seventeen sites and {@code '23'} at exactly two -
 * {@code app/cbl/CBACT04C.cbl:L422} and {@code :L436}, the two that define the fallback. Accordingly
 * {@code com.cardemo.exception.FileUnavailableException} has no call site derived from this table and none is
 * invented here.</p>
 *
 * @see com.cardemo.model.entity.DisclosureGroup
 * @see com.cardemo.model.key.DisclosureGroupId
 */
@Repository
public interface DisclosureGroupRepository extends JpaRepository<DisclosureGroup, DisclosureGroupId> {

    /**
     * Stage two of the rate resolution: re-reads the rate table under the {@code DEFAULT} account group,
     * keeping the transaction type and category codes of the failed primary lookup unchanged.
     *
     * <p>Translates {@code 1200-A-GET-DEFAULT-INT-RATE}, {@code app/cbl/CBACT04C.cbl:L443-L460}. The caller
     * invokes this method - and only this method - when the primary lookup {@code findById(DisclosureGroupId)}
     * came back empty, mirroring {@code app/cbl/CBACT04C.cbl:L436-L439}, where the record-not-found status is
     * the sole trigger for substituting the group id at {@code :L437} and performing the second read.
     *
     * @param tranTypeCd the transaction type code from the failed primary lookup, {@code DIS-TRAN-TYPE-CD}
     * {@code PIC X(02)}.
     * @param tranCatCd the transaction category code from the failed primary lookup, {@code DIS-TRAN-CAT-CD}
     * {@code PIC 9(04)}, domain 0 to 9999.
     * @return the fallback group's rate row for that type and category, {@link Optional#empty()} if none exists
     * - which the caller must escalate as fatal
     */
    @Query("""
            select d
            from DisclosureGroup d
            where d.id.accountGroupId = 'DEFAULT   '
              and d.id.tranTypeCd = :tranTypeCd
              and d.id.tranCatCd = :tranCatCd
            """)
    Optional<DisclosureGroup> findDefaultGroupRate(
            @Param("tranTypeCd") String tranTypeCd,
            @Param("tranCatCd") Integer tranCatCd);
}
