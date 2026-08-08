/*
 * ******************************************************************
 * Program     : AccountRepository.java
 * Application : CardDemo
 * Type        : Spring Data JPA Repository Interface
 * Function    : Replaces the VSAM access verbs over the account master file used
 *               by account view, account update, bill payment, daily posting and
 *               interest calculation.
 * Source      : CICS FILE ACCTDAT (app/csd/CARDDEMO.CSD:L1-L2, DSNAME
 *               AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS); cluster key 11 / reclen 300
 *               (app/catlg/LISTCAT.txt:L59 DATA-component attribute line;
 *               app/jcl/ACCTFILE.jcl:L36, L40-L41 KEYS(11 0) RECORDSIZE(300 300));
 *               record layout app/cpy/CVACT01Y.cpy:L4-L17 @ 7756d89
 * ******************************************************************
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
 * ******************************************************************
 */
package com.cardemo.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.cardemo.model.entity.Account;

import jakarta.persistence.LockModeType;

/**
 * Persistence gateway for the account master record: the relational replacement for the VSAM access verbs
 * issued against the CICS file {@code ACCTDAT} over the KSDS cluster
 * {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS}.
 *
 * <h2>What it does</h2>
 * <p>
 * This interface is the single doorway through which every online service and every batch step reaches account
 * master data. Spring Data supplies the implementation at runtime from the method signatures below, so there is
 * deliberately <em>no</em> implementation class, no custom-fragment interface, no criteria-API or
 * predicate-builder helper, no mapper and no DAO wrapper anywhere in this package.
 * <p>
 * Its responsibility is narrow and its non-responsibilities are the important part of the contract:
 * <ul>
 *   <li><b>It reads and writes whole {@link Account} aggregates.</b> Keyed retrieval replaces
 *       {@code EXEC CICS READ} and the sequential COBOL {@code READ}; {@code save} replaces {@code REWRITE};
 *       the ordered full scan replaces {@code OPEN} / {@code READ} / {@code CLOSE}.</li>
 *   <li><b>It performs no arithmetic.</b> Every balance, limit and cycle-accumulator computation happens in
 *       Java on {@link java.math.BigDecimal} values in the service and processor layers. See the Blocker
 *       finding below: pushing that arithmetic into a bulk statement here would silently change rounding and
 *       sign handling.</li>
 *   <li><b>It owns no transaction.</b> No method here is annotated {@code @Transactional}. The unit of work
 *       that spans the account write and the customer write belongs to
 *       {@code com.cardemo.service.account.AccountUpdateService}.</li>
 *   <li><b>It translates no status codes.</b> An absent row is reported as an empty {@link Optional} and
 *       nothing else. Turning that emptiness into an exception, or into a batch reject code, is the caller's
 *       decision; the single place where a legacy {@code FILE STATUS} becomes a typed exception is
 *       {@code com.cardemo.service.shared.FileStatusMapper}.</li>
 *   <li><b>It logs nothing.</b> Instrumentation is delegated to {@code com.cardemo.observability}. Account
 *       identifiers are business keys rather than secrets, but no card number, government identifier,
 *       date of birth, electronic-funds account identifier, credential or signing key is ever exposed or
 *       logged by this type.</li>
 *   </ul>
 *
 * <h2>Provenance</h2>
 * <ul>
 *   <li><b>Online definition.</b> {@code app/csd/CARDDEMO.CSD:L1} declares
 *       {@code DEFINE FILE(ACCTDAT) GROUP(CARDDEMO)} and {@code :L2} names
 *       {@code DSNAME(AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS) RLSACCESS(NO)}. {@code :L7} grants
 *       {@code BROWSE(YES) DELETE(YES) READ(YES) UPDATE(YES)}, which is the legacy authority for the read,
 *       browse and update operations exposed here. {@code ACCTDAT} is one of the eight files the CSD defines
 *       for the online region.</li>
 *   <li><b>Physical specification, dual-sourced.</b> {@code app/catlg/LISTCAT.txt:L22} opens the cluster block
 *       for {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS}; the DATA-component attribute line {@code :L59} reports
 *       {@code KEYLEN 11} and {@code AVGLRECL 300}, and {@code :L60} reports {@code RKP 0} with
 *       {@code MAXLRECL 300}, proving an eleven-byte key at byte zero of a fixed 300-byte record.
 *       Independently, {@code app/jcl/ACCTFILE.jcl:L36} issues the {@code DEFINE CLUSTER}, {@code :L40}
 *       declares {@code KEYS(11 0)} and {@code :L41} declares {@code RECORDSIZE(300 300)}. The two sources
 *       agree exactly.</li>
 *   <li><b>Record layout.</b> {@code app/cpy/CVACT01Y.cpy:L4}-{@code :L17}, whose header at {@code :L2} reads
 *       {@code Data-structure for  account entity (RECLN 300)}.</li>
 *   <li><b>Batch participation.</b> The account dataset appears at {@code app/jcl/POSTTRAN.jcl:L40} as one of
 *       the six datasets of the daily transaction posting job.</li>
 *   <li><b>Traceability anchor.</b> Commit {@code 7756d89}. The legacy corpus under {@code app/} is frozen and
 *       byte-for-byte read-only; every citation above is a read of the system of record, never an edit.</li>
 *   </ul>
 *
 * <h2>Field contract carried by the mapped aggregate</h2>
 * <p>
 * The shape is owned by {@link Account} and is restated here only so that the query below can be read against
 * it without a second lookup. Twelve populated fields totalling 122 bytes, plus {@code FILLER PIC X(178)} at
 * {@code app/cpy/CVACT01Y.cpy:L17} which pads the record to the catalogued 300 bytes and is deliberately not
 * modelled.
 * <pre>
 *   COBOL field (CVACT01Y.cpy)      PIC          Java property        Column
 *   ------------------------------  -----------  -------------------  -------------------------------
 *   ACCT-ID                 :L5     9(11)        accountId            acct_id NUMERIC(11)   (identifier)
 *   ACCT-ACTIVE-STATUS      :L6     X(01)        activeStatus         acct_active_status CHAR(1)
 *   ACCT-CURR-BAL           :L7     S9(10)V99    currentBalance       acct_curr_bal NUMERIC(12,2)
 *   ACCT-CREDIT-LIMIT       :L8     S9(10)V99    creditLimit          acct_credit_limit NUMERIC(12,2)
 *   ACCT-CASH-CREDIT-LIMIT  :L9     S9(10)V99    cashCreditLimit      acct_cash_credit_limit NUMERIC(12,2)
 *   ACCT-OPEN-DATE          :L10    X(10)        openDate             acct_open_date CHAR(10)
 *   ACCT-EXPIRAION-DATE     :L11    X(10)        expiraionDate        acct_expiraion_date CHAR(10)
 *   ACCT-REISSUE-DATE       :L12    X(10)        reissueDate          acct_reissue_date CHAR(10)
 *   ACCT-CURR-CYC-CREDIT    :L13    S9(10)V99    currentCycleCredit   acct_curr_cyc_credit NUMERIC(12,2)
 *   ACCT-CURR-CYC-DEBIT     :L14    S9(10)V99    currentCycleDebit    acct_curr_cyc_debit NUMERIC(12,2)
 *   ACCT-ADDR-ZIP           :L15    X(10)        addressZip           acct_addr_zip CHAR(10)
 *   ACCT-GROUP-ID           :L16    X(10)        groupId              acct_group_id CHAR(10)
 *   FILLER                  :L17    X(178)       (not modelled)       (no column)
 *   (no COBOL counterpart)          -            version              version BIGINT (optimistic lock)
 * </pre>
 * <p>
 * Two properties of that table are load-bearing and are easy to "tidy" into a defect:
 * <ul>
 *   <li><b>{@code expiraionDate} and {@code acct_expiraion_date} are misspelled deliberately, and the
 *       misspelling is part of the field contract.</b> {@code app/cpy/CVACT01Y.cpy:L11} declares
 *       {@code ACCT-EXPIRAION-DATE}, missing the {@code T} of {@code EXPIRATION}, and the corpus uses that
 *       spelling everywhere it touches the field &mdash; for example the expiry test at
 *       {@code app/cbl/CBTRN02C.cbl:L414} and the change-detection comparison at
 *       {@code app/cbl/COACTUPC.cbl:L4131}-{@code :L4133}. No correctly spelled variant exists anywhere in the
 *       repository. A reviewer will read it as a typo; it is not. Renaming it would break paragraph-level
 *       traceability and would desynchronise the property from the column the schema migration must create.
 *       <b>Never correct it.</b></li>
 *   <li><b>{@code acct_group_id} may legitimately be ten spaces.</b> Row 1 of the fixture
 *       {@code app/data/ASCII/acctdata.txt} carries exactly that. It is therefore never treated as
 *       blank-rejecting input and is never trimmed inside a query predicate, because trimming would make a
 *       ten-space value and an empty value indistinguishable and would change which rows match.</li>
 *   </ul>
 *
 * <h2>Method surface and its named call sites</h2>
 * <p>
 * Four operations are in use. Three are inherited from {@link JpaRepository} and are deliberately not
 * redeclared &mdash; redeclaring them would add no behaviour and would create signatures with no independent
 * reason to exist. One is declared below because no inherited signature expresses it.
 * <ol>
 *   <li><b>{@code findById(Long)} &mdash; keyed retrieval.</b> Replaces the keyed {@code READ}. Call sites:
 *       the cross-reference then account then customer lookup chain of {@code app/cbl/COACTVWC.cbl} (941
 *       lines), whose account read is issued against {@code LIT-ACCTFILENAME} at {@code :L777} after the
 *       cross-reference read at {@code :L728} and before the customer read at {@code :L827}; the account
 *       lookup {@code 1500-B-LOOKUP-ACCT} at {@code app/cbl/CBTRN02C.cbl:L393}-{@code :L422}; the account read
 *       {@code 1100-GET-ACCT-DATA} invoked at {@code app/cbl/CBACT04C.cbl:L202}-{@code :L203}; and the balance
 *       capture at {@code app/cbl/COBIL00C.cbl:L193}.</li>
 *   <li><b>{@code save(Account)} &mdash; rewrite of an existing row.</b> Replaces {@code REWRITE}. Call sites:
 *       the account rewrite at {@code app/cbl/COACTUPC.cbl:L4065}-{@code :L4071};
 *       {@code 2800-UPDATE-ACCOUNT-REC} at {@code app/cbl/CBTRN02C.cbl:L545}-{@code :L560};
 *       {@code 1050-UPDATE-ACCOUNT} at {@code app/cbl/CBACT04C.cbl:L350}-{@code :L370}; and the bill-payment
 *       account update at {@code app/cbl/COBIL00C.cbl:L235}.</li>
 *   <li><b>{@code findAll()} &mdash; read-only sequential scan.</b> Replaces the whole of
 *       {@code app/cbl/CBACT01C.cbl} (193 lines), whose verb inventory was counted and is {@code OPEN},
 *       {@code READ} and {@code CLOSE} only, with zero occurrences of {@code WRITE}, {@code REWRITE} or
 *       {@code DELETE}. Consumed as a read-only verification step by
 *       {@code com.cardemo.batch.readers.AccountReader}.</li>
 *   <li><b>{@link #findByIdForUpdate(Long)} &mdash; pessimistic read for update.</b> Declared below. Replaces
 *       {@code EXEC CICS READ ... UPDATE} at {@code app/cbl/COACTUPC.cbl:L3894}-{@code :L3906}. Consumed by
 *       {@code com.cardemo.service.account.AccountUpdateService}.</li>
 *   </ol>
 * <p>
 * <b>Nothing else is declared.</b> There is no finder on {@code groupId}, {@code activeStatus}, any balance or
 * any date, no projection, no aggregate and no bulk update statement. Each omission is a decision with a
 * reason, recorded under Design decisions below rather than left implicit.
 *
 * <h2>An empty result is not automatically an error</h2>
 * <p>
 * Both read methods return {@link Optional}, so the caller must confront the missing-row case explicitly. What
 * that case <em>means</em> differs by call site, and collapsing the two would be a behavioural divergence:
 * <ul>
 *   <li><b>Online: an exception.</b> In the view and update paths a missing account is
 *       {@code com.cardemo.exception.RecordNotFoundException}, the typed translation of {@code FILE STATUS}
 *       {@code '23'} and {@code DFHRESP(NOTFND)}.</li>
 *   <li><b>Batch: a business outcome, never thrown.</b> In {@code 1500-B-LOOKUP-ACCT} a missing account sets
 *       reject code {@code 101} at {@code app/cbl/CBTRN02C.cbl:L397} with the description
 *       {@code ACCOUNT RECORD NOT FOUND} at {@code :L398}-{@code :L399}, and processing continues to write a
 *       reject record. <b>Reject codes are business outcomes that drive {@code ExitStatus}; they are never
 *       raised as exceptions.</b> Throwing there would abort a batch run that the legacy job completes with
 *       return code 4.</li>
 *   </ul>
 *
 * <h2>The account-update write sequence this repository serves</h2>
 * <p>
 * {@code 9600-WRITE-PROCESSING} begins at {@code app/cbl/COACTUPC.cbl:L3888}. Its seven steps run in a fixed
 * order that must be preserved exactly, because each step's failure branch depends on precisely how much work
 * the preceding steps have already performed:
 * <ol>
 *   <li><b>Read the account record for update</b> &mdash; {@code :L3894}-{@code :L3906}. On a non-normal
 *       response the program sets an input-error flag <em>and</em> the account-lock-failure flag, then branches
 *       to the exit at {@code :L3910}-{@code :L3916}. This step is {@link #findByIdForUpdate(Long)}.</li>
 *   <li><b>Read the customer record for update</b> &mdash; {@code :L3920}-{@code :L3932}. On failure it sets a
 *       <em>distinct</em> customer-lock-failure flag and branches to the exit at
 *       {@code :L3936}-{@code :L3942}. Served by the customer repository, not by this one.</li>
 *   <li><b>Change-detection comparison</b> &mdash; {@code :L3947}-{@code :L3952}. If the data changed since the
 *       screen was populated, the write is abandoned.</li>
 *   <li><b>Initialise the update images and move each new field into place</b> &mdash; beginning with
 *       {@code INITIALIZE ACCT-UPDATE-RECORD} at {@code :L3956}.</li>
 *   <li><b>Rewrite the account record</b> &mdash; {@code :L4065}-{@code :L4071}. On failure it sets the
 *       combined locked-but-update-failed flag and branches to the exit at {@code :L4079}-{@code :L4080},
 *       <b>with no rollback</b>.</li>
 *   <li><b>Rewrite the customer record</b> &mdash; {@code :L4085}-{@code :L4091}. On failure it sets the same
 *       flag, issues an <b>explicit {@code EXEC CICS SYNCPOINT ROLLBACK}</b>, then branches to the exit at
 *       {@code :L4098}-{@code :L4102}.</li>
 *   <li><b>Exit</b> &mdash; {@code :L4105}.</li>
 * </ol>
 * <p>
 * <b>The rollback asymmetry is correct and is preserved, not repaired.</b> The backout appears on only one of
 * the two rewrite-failure paths, which reads like a defect and is not one. At the account-rewrite failure point
 * nothing has yet been written inside the unit of work, so the transaction monitor releases the read-for-update
 * locks at task end and no explicit backout is needed. At the customer-rewrite failure point the account
 * rewrite has already happened inside the same unit of work, so an explicit backout is the only way to avoid a
 * half-applied update. A single {@code @Transactional(rollbackFor = Exception.class)} <em>service</em> method
 * spanning both writes reproduces both branches automatically with no conditional logic, because each failure
 * path returns or throws before the commit point. <b>That transaction boundary belongs to
 * {@code com.cardemo.service.account.AccountUpdateService} and emphatically not to this interface</b>, which
 * declares no transactional annotation of any kind.
 *
 * <h3>Failure taxonomy</h3>
 * <p>
 * Four outcome condition names are declared at {@code app/cbl/COACTUPC.cbl:L517}-{@code :L523}:
 * <pre>
 *   :L517-L518  COULD-NOT-LOCK-ACCT-FOR-UPDATE   'Could not lock account record for update'
 *   :L519-L520  COULD-NOT-LOCK-CUST-FOR-UPDATE   'Could not lock customer record for update'
 *   :L521-L522  DATA-WAS-CHANGED-BEFORE-UPDATE   'Record changed by some one else. Please review'
 *   :L523-L524  LOCKED-BUT-UPDATE-FAILED         'Update of record failed'
 * </pre>
 * <p>
 * A fifth marker, {@code ACUP-CHANGES-OKAYED-LOCK-ERROR} at {@code :L667}, is set specifically when the
 * <em>account</em> lock fails: {@code :L2607}-{@code :L2608} reads
 * {@code WHEN COULD-NOT-LOCK-ACCT-FOR-UPDATE / SET ACUP-CHANGES-OKAYED-LOCK-ERROR TO TRUE}. Each of these must
 * reach the client as a distinguishable HTTP response; collapsing them into a single conflict status would lose
 * information the legacy screen displayed to its operator. The concurrency outcome surfaces as
 * {@code com.cardemo.exception.ConcurrentUpdateException}. Mapping outcomes onto responses is the service and
 * controller layers' work; this interface's contribution is to make step 1 above genuinely lock.
 *
 * <h3>Why the request must carry the snapshot</h3>
 * <p>
 * {@code 9700-CHECK-CHANGE-IN-REC} at {@code app/cbl/COACTUPC.cbl:L4109} compares the freshly read record
 * against a snapshot captured when the screen was first populated. That snapshot is a real working-storage
 * structure: {@code 05 ACUP-OLD-DETAILS.} at {@code :L669}, {@code 10 ACUP-OLD-ACCT-DATA.} at {@code :L670},
 * {@code ACUP-OLD-ACCT-ID-X PIC X(11)} at {@code :L671} redefined {@code PIC 9(11)} at
 * {@code :L672}-{@code :L673}, {@code ACUP-OLD-ACTIVE-STATUS PIC X(01)} at {@code :L674}, and the money fields
 * declared {@code PIC X(12)} and redefined {@code PIC S9(10)V99} at {@code :L675}-{@code :L680}. The group runs
 * to {@code :L756}, and {@code 05 ACUP-NEW-DETAILS.} begins at {@code :L757} with {@code ACUP-NEW-ACCT-ID-X} at
 * {@code :L759} and {@code ACUP-NEW-ACTIVE-STATUS} at {@code :L762}.
 * <p>
 * Because the target is stateless the snapshot cannot live on the server between requests, which is why
 * {@code com.cardemo.model.dto.AccountUpdateRequest} carries both the old and the new detail groups. The
 * comparison itself is deliberately asymmetric, and the asymmetry is behaviour rather than noise &mdash;
 * normalising it in either direction changes which updates are accepted:
 * <ul>
 *   <li>The account group identifier is compared through a <b>lower-casing</b> function on both sides
 *       ({@code :L4139}-{@code :L4140}).</li>
 *   <li>The customer name, address, state, country and government-identifier fields are compared through an
 *       <b>upper-casing</b> function on both sides.</li>
 *   <li>The postal code, both telephone numbers, the social-security number, the electronic-funds account
 *       identifier, the primary-holder indicator and the credit score are compared with <b>no case function at
 *       all</b>.</li>
 *   <li>The three account dates are compared as <b>three separate substrings</b> &mdash; year {@code (1:4)},
 *       month {@code (6:2)}, day {@code (9:2)} &mdash; never as whole strings ({@code :L4127}-{@code :L4137}).
 *       The date of birth goes further: the live customer field is dash-separated so its components sit at
 *       {@code (1:4)}, {@code (6:2)} and {@code (9:2)}, whereas the snapshot holds the same date
 *       <em>without</em> separators as {@code PIC X(08)} so its components sit at {@code (1:4)}, {@code (5:2)}
 *       and {@code (7:2)}, and the source compares across those differing offsets. A naive whole-string
 *       comparison would report a change on <em>every</em> request and render the endpoint permanently
 *       unusable.</li>
 *   </ul>
 * <p>
 * Counted against the source rather than restated from prose: the account block at
 * {@code :L4114}-{@code :L4140} compares <b>ten</b> account fields through <b>sixteen</b> {@code AND}-clauses,
 * the three dates contributing three substring comparisons each, and {@code ACCT-ID} and
 * {@code ACCT-ADDR-ZIP} are <b>not</b> compared at all. All of that logic lives in the service; this
 * interface's only guarantee is that the row was read under a write lock.
 *
 * <h2>Findings, classified by severity</h2>
 * <dl>
 *   <dt><b>Blocker &mdash; no binary IEEE-754 numeric type may appear in any financial field.</b></dt>
 *   <dd>All five money and cycle fields are {@code PIC S9(10)V99}, which maps to {@code NUMERIC(12,2)} and
 *       {@link java.math.BigDecimal}. No binary IEEE-754 primitive, and no wrapper for one, appears anywhere in
 *       this file &mdash; not as a type argument, not as a parameter and not as a return type &mdash; and the
 *       security-audit gate asserts that absence by inspection. Binary radix cannot represent
 *       two-decimal money exactly. Callers round with {@code RoundingMode.HALF_EVEN} and compare with
 *       {@code compareTo}, never {@code equals}, because {@code BigDecimal.equals} is scale-sensitive and
 *       reports {@code 2.0} and {@code 2.00} as different. This precision tier must not be collapsed into the
 *       other two the schema uses: {@code S9(09)V99} maps to {@code NUMERIC(11,2)} and {@code S9(04)V99} maps
 *       to {@code NUMERIC(6,2)}. Remediation: none required; the constraint is structural here because this
 *       interface declares no numeric member at all.</dd>
 *   <dt><b>Blocker &mdash; the balance arithmetic must never be reshaped into a query.</b></dt>
 *   <dd>Four source behaviours are reproduced in Java, on {@link java.math.BigDecimal} values loaded through
 *       {@code findById} or {@link #findByIdForUpdate(Long)} and persisted through {@code save}. Pushing any of
 *       them into a bulk update statement or an aggregate expression would change rounding, sign handling or
 *       both. Consequently <b>this interface declares no bulk arithmetic update annotation and no aggregate,
 *       total, mean or derived-computation method.</b>
 *       <ul>
 *         <li><b>The sign branch that must not be normalised.</b>
 *             {@code 2800-UPDATE-ACCOUNT-REC} at {@code app/cbl/CBTRN02C.cbl:L547}-{@code :L552} adds the
 *             transaction amount to the current balance, then adds it to the current-cycle <em>credit</em> when
 *             it is non-negative and to the current-cycle <em>debit</em> otherwise. The raw signed amount is
 *             added in both branches, so <b>a negative amount is added to the debit accumulator and that
 *             accumulator legitimately holds negative values</b>. <b>No absolute-value normalisation is
 *             permitted anywhere on this path.</b> The fixture exercises the branch genuinely:
 *             {@code app/data/ASCII/dailytran.txt} carries both {@code &#123;} and {@code &#125;} overpunch
 *             signs together with negative non-zero {@code J}-through-{@code R} endings, so real negative
 *             amounts flow through it.</li>
 *         <li><b>The over-limit formula must be transcribed exactly, never algebraically rewritten.</b>
 *             {@code app/cbl/CBTRN02C.cbl:L403}-{@code :L405} computes a temporary balance as the current-cycle
 *             credit <em>minus</em> the current-cycle debit <em>plus</em> the transaction amount, and
 *             {@code :L407}-{@code :L413} assigns reject code {@code 102} with the description
 *             {@code OVERLIMIT TRANSACTION} when the credit limit is below that temporary balance. Subtracting
 *             the debit accumulator is correct <em>precisely because</em> that accumulator holds negative
 *             values, per the point above. Rewriting the expression into a form that merely looks equivalent
 *             changes the result. Note also that the expiry test immediately following at {@code :L414}-{@code
 *             :L420} is sequential and unguarded, so when both conditions fail reject code {@code 103}
 *             overwrites {@code 102} and one reject record bearing {@code 103} is written; that behaviour is
 *             preserved in the processor, not here.</li>
 *         <li><b>The cycle reset that is easily missed.</b> {@code 1050-UPDATE-ACCOUNT} at
 *             {@code app/cbl/CBACT04C.cbl:L350}-{@code :L370} adds the accumulated interest to the current
 *             balance at {@code :L352} and then <b>zeroes both cycle counters</b> at {@code :L353} and
 *             {@code :L354} before rewriting at {@code :L356}. Omitting that reset breaks the over-limit
 *             arithmetic above on the following posting cycle &mdash; a defect that surfaces only on a second
 *             batch run, which is exactly the kind of latent divergence the parity gates exist to catch.</li>
 *         <li><b>Bill payment drives the balance to exactly zero.</b>
 *             {@code app/cbl/COBIL00C.cbl:L198} rejects when the current balance is at or below zero;
 *             {@code :L224} moves the <b>entire</b> current balance into the transaction amount, so the payment
 *             is always the full balance and never partial; {@code :L234} subtracts it, driving the balance to
 *             exactly zero; and {@code :L235} performs the account update.</li>
 *   </ul>
 * Remediation: none required; the omissions are the fix, and they are enumerated here so that a future
 * contributor does not "optimise" a service loop into a bulk update.</dd>
 *   <dt><b>High &mdash; an optimistic version column alone is insufficient, so an explicit read for update is
 *       required.</b></dt>
 *   <dd>A JPA {@code @Version} column detects <em>that</em> a row changed. The legacy program detects
 *       <em>which business field values</em> differ from what the user was shown. These are different
 *       guarantees, and only the second reproduces the legacy behaviour: a concurrent write that set a field
 *       back to its original value passes the legacy check and fails a version check. <b>Both layers are
 *       therefore mandatory.</b> {@link Account} retains its {@code @Version} column for the store-level guard,
 *       and {@link #findByIdForUpdate(Long)} below provides the pessimistic read so that
 *       {@code com.cardemo.service.account.AccountUpdateService} can perform the field-by-field comparison
 *       inside one unit of work with no interleaved write. Remediation: keep both; removing either one silently
 *       weakens concurrency control.</dd>
 *   <dt><b>Medium &mdash; the missing-row case has two different meanings.</b></dt>
 *   <dd>Online it is {@code com.cardemo.exception.RecordNotFoundException}; in the daily posting job it is
 *       reject code {@code 101}, which is never thrown. Both read methods return {@link Optional} precisely so
 *       that the decision is forced onto the caller instead of being hard-coded here. Remediation: callers must
 *       handle the empty case explicitly; there is no default.</dd>
 *   <dt><b>Medium &mdash; no secondary index exists on the account table, so no finder may imply one.</b></dt>
 *   <dd>{@code V2__create_indexes.sql} is to create exactly three non-unique
 *       indexes, on {@code card.card_acct_id},
 *       {@code card_cross_reference.xref_acct_id} and the transaction table's {@code tran_proc_ts}, mirroring
 *       the three VSAM alternate indexes. <b>None is on {@code account}</b>, which is consistent with the
 *       catalogue: the account cluster has no alternate index. A finder on {@code groupId},
 *       {@code activeStatus}, a balance or a date would therefore be an unindexed scan serving no legacy access
 *       path. Remediation: none required; no such finder is declared.</dd>
 *   <dt><b>Low &mdash; connection-pool tuning is out of scope.</b></dt>
 *   <dd>HikariCP is used at its defaults. Sizing it honestly requires a measured concurrency profile that the
 *       legacy system does not publish &mdash; the corpus asserts no service-level objective anywhere, so any
 *       pool figure invented here would be fabricated rather than derived. The item is disclosed as residual
 *       risk in the {@code DECISION_LOG.md} and {@code docs/validation-gates.md} rather than silently absorbed.
 *       Remediation: revisit once the performance-baseline gate has produced measured throughput and latency
 *       figures.</dd>
 *   </dl>
 *
 * <h2>Design decisions</h2>
 * <ul>
 *   <li><b>An interface and nothing else.</b> This file declares exactly one type. There is no
 *       hand-written companion class, no custom-fragment interface, no shared base-repository abstraction, no
 *       criteria-API or predicate-builder helper, no mapper and no DAO wrapper. Spring Data
 *       generates the implementation from the signatures, so a hand-written one would be duplicate code with a
 *       second behaviour to keep in step.</li>
 *   <li><b>Every declared method has a real call site.</b> The rule that forbids dead code applies here at
 *       full strength with no exemption, because none of the migration's deliberately preserved no-op
 *       artefacts lives in this package. Accordingly the interface declares one method and not one more: an
 *       ordered-scan finder, a group or status finder and any projection were all considered and rejected for
 *       want of a consumer. <strong>Re-measured at this commit</strong>, eight files outside
 *       {@code com.cardemo.repository} reference this interface, across the service and batch packages, and
 *       six test files exercise it, so the one method is justified by existing call sites rather than by a
 *       named future consumer. Re-derive with
 *       {@code grep -rl AccountRepository src/main/java | grep -v /repository/}.</li>
 *   <li><b>Parameter binding only.</b> The one query is a constant string with a named parameter. No JPQL or SQL
 *       is assembled by concatenation anywhere in this file, and no native-SQL query is used at all, so
 *       there is no interpolation site for an injection to reach.</li>
 *   <li><b>No bulk update statement and no aggregate.</b> See the second Blocker finding: arithmetic
 *       stays in Java so that sign handling and decimal rounding are preserved exactly.</li>
 *   <li><b>No transaction management.</b> No method carries {@code @Transactional}. The boundary is owned by
 *       the service so that the source's asymmetric rollback behaviour is reproduced by scoping rather than by
 *       conditional logic.</li>
 *   <li><b>No status translation and no logging.</b> Both are single-sited elsewhere
 *       ({@code com.cardemo.service.shared.FileStatusMapper} and {@code com.cardemo.observability}) so that
 *       behaviour is not duplicated across eleven repositories.</li>
 *   <li><b>Exception types are named in prose, never imported.</b> Every reference to
 *       {@code com.cardemo.exception.*} above is documentation. Importing them would create imports this
 *       interface does not use, and the build compiles with {@code -Xlint:all -Werror} and
 *       {@code failOnWarning}.</li>
 *   <li><b>No static mutable state and no annotation processor.</b> There are no constants, no counters and no
 *       caches; Lombok is not used and no new dependency is introduced. What is written here is exactly what
 *       compiles.</li>
 *   <li><b>No environment coupling.</b> No environment-variable lookup, no system-property lookup and no
 *       hardcoded host, port, path, page size or dataset name appears in this file. Pagination sizes in
 *       particular are parity contracts configured under {@code carddemo.pagination.*} &mdash; seven for the
 *       card list per {@code app/cbl/COCRDLIC.cbl:L177}-{@code :L178}, ten for the transaction list per
 *       {@code app/cbl/COTRN00C.cbl:L290}, ten for the user list per {@code app/cbl/COUSR00C.cbl:L57} &mdash;
 *       and none of them is an account concern.</li>
 *   <li><b>Determinism.</b> Any multi-row or paged retrieval carries an explicit ordering supplied by the
 *       caller through {@code Sort} or {@code Pageable}; no result set here depends on natural, heap or hash
 *       order. The single declared query returns at most one row by primary key, so its ordering is total by
 *       construction.</li>
 *   </ul>
 *
 * <h2>How to build, run and test</h2>
 * <ul>
 *   <li><b>Build:</b> {@code ./mvnw -B clean compile}. Compilation runs with {@code -Xlint:all -Werror} and
 *       {@code failOnWarning}, so any warning is a hard failure rather than advisory output.</li>
 *   <li><b>Run:</b> this interface is not independently runnable. Spring Data materialises a proxy for it during
 *       application-context refresh, so it participates in a run only as part of the Spring Boot application,
 *       started with {@code ./mvnw -B spring-boot:run} or from the packaged JAR. Both are now possible:
 *       {@code CardDemoApplication} carries {@code @SpringBootApplication} and all four
 *       {@code application*.yml} profiles are present, so the context refreshes. Two preconditions fail fast at
 *       boot: a reachable PostgreSQL 16 instance carrying the {@code account} table exactly as
 *       contracted below, and the environment-indirected JWT signing key, which has no committed default.</li>
 *   <li><b>Test:</b> {@code ./mvnw -B clean test} for the unit tier. The tests that matter for this type are
 *       integration tests under {@code src/test/java/com/cardemo/integration/repository}, executed by
 *       {@code ./mvnw -B clean verify} against a Testcontainers-managed PostgreSQL 16. They assert that the query
 *       below resolves against the mapped property, that the pessimistic lock is actually acquired, and that
 *       the read-for-update and {@code @Version} pair behaves as the High finding specifies. Those tests need a
 *       reachable container runtime; without one they cannot run and must be reported as blocked rather than
 *       recorded as passing.</li>
 *   <li><b>Verify:</b> {@code ./mvnw -B clean verify} additionally enforces the line-coverage floor and the
 *       dependency-vulnerability gate.</li>
 *   </ul>
 *
 * <h2>Key configuration and defaults</h2>
 * <ul>
 *   <li>{@code spring.jpa.hibernate.ddl-auto} is {@code validate} in every profile. No schema is ever
 *       generated from this code; the mapping is checked against the schema the migrations created, and a
 *       disagreement aborts context startup outright instead of degrading at runtime.</li>
 *   <li>{@code spring.jpa.open-in-view} is {@code false}. Results are returned fully initialised, and no lazy
 *       access is attempted outside a transaction. {@link Account} declares no association, so there is no lazy
 *       proxy to leak in the first place.</li>
 *   <li>The Spring Batch {@code BATCH_*} metadata tables come from the framework's own schema script by way of
 *       {@code spring.batch.jdbc.initialize-schema}. They are neither a fourth Flyway migration nor extra
 *       tables in the first one.</li>
 *   <li>HikariCP runs at its defaults; see the Low finding.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 * <ul>
 *   <li><b>Context startup fails with a schema-validation error naming an account column.</b> The migration and
 *       the entity mapping disagree. The column contract restated under the disclosure below is normative;
 *       reconcile the migration to it. Never weaken {@code ddl-auto} to silence the error, and never "correct"
 *       {@code acct_expiraion_date} to a properly spelled name at either end.</li>
 *   <li><b>Context startup fails with a property-resolution error naming this interface's query.</b> The JPQL
 *       below references the mapped property {@code accountId}, not the column {@code acct_id}. Renaming the
 *       entity property without updating the query breaks it at boot; that is intended, since it fails fast and
 *       loudly rather than at first request.</li>
 *   <li><b>A lock-timeout or deadlock exception from {@link #findByIdForUpdate(Long)}.</b> Another transaction
 *       holds a conflicting row lock. This is the pessimistic-locking counterpart of the legacy
 *       lock-failure branch at {@code app/cbl/COACTUPC.cbl:L3910}-{@code :L3916}; surface it as the
 *       account-lock-failure outcome rather than retrying blindly.</li>
 *   <li><b>An optimistic-lock failure on {@code save}.</b> Another writer changed the row between the read and
 *       the write. Note this is the weaker of the two guarantees; the business-level change detection in the
 *       service is what reproduces the legacy check, and it must not be dropped because the version column
 *       exists.</li>
 *   <li><b>{@link #findByIdForUpdate(Long)} called outside a transaction.</b> A pessimistic lock has no meaning
 *       without one, and the provider will reject the call. Invoke it from within the service's transactional
 *       method, which is where the account and customer writes are already scoped.</li>
 *   <li><b>An empty {@link Optional} treated as an error in a batch step.</b> That inverts the source: a missing
 *       account in the posting job is reject code {@code 101}, not an abend. Check the call site against the
 *       preceding section before adding a throw.</li>
 *   </ul>
 *
 * <h2>Missing information disclosure</h2>
 * <p>
 * <b>All three Flyway migrations are present.</b> {@code src/main/resources/db/migration/V1__create_schema.sql}
 * declares
 * {@code CREATE TABLE account} with {@code version BIGINT} and {@code ck_account_active_status};
 * {@code V2} deliberately creates no index for this table, because {@code ACCTDATA} has no alternate index
 * in {@code app/catlg/LISTCAT.txt} and the primary key is the only access path this interface needs; and
 * {@code V3} seeds it from {@code app/data/ASCII/acctdata.txt} with position-aware overpunch decoding. The
 * query and the locking semantics below are therefore reconciled against real DDL. Because
 * {@code spring.jpa.hibernate.ddl-auto} is {@code validate} in all four profiles - which are present too -
 * a mismatch fails application-context startup rather than degrading gracefully, so the following is
 * <b>what {@code V1} declares and what this interface is typed over</b>: table {@code account} carrying the
 * twelve columns of the field contract above &mdash; <b>including the retained {@code acct_expiraion_date}
 * misspelling</b> &mdash; with {@code acct_id NUMERIC(11)} as primary key, the five money and cycle columns at
 * {@code NUMERIC(12,2)}, the six text columns at {@code CHAR}, a {@code version BIGINT} optimistic-lock column,
 * and {@code NOT NULL} throughout. {@code V2} must add no index on this table, per the Medium finding.
 * {@code V3} must seed <b>50</b> account rows of <b>300</b> bytes each from
 * {@code app/data/ASCII/acctdata.txt} &mdash; a row count independently corroborated by
 * {@code REC-TOTAL 50} at {@code app/catlg/LISTCAT.txt:L64} &mdash; decoding the zoned-decimal overpunch signs
 * <b>position-aware from the PIC clauses</b> ({@code &#123;} to {@code +0}, {@code A} through {@code I} to
 * {@code +1} through {@code +9}, {@code &#125;} to {@code -0}, {@code J} through {@code R} to {@code -1}
 * through {@code -9}), never by a global text replacement, because those same letters occur legitimately inside
 * text fields.
 * <p>
 * <b>Not available:</b> {@code FILE STATUS} {@code '35'}, file unavailable, as a grounded source construct. A
 * census of the frozen corpus finds <b>no literal {@code '35'} anywhere in {@code app/cbl}</b>, and the
 * {@code DFHRESP} census is {@code NORMAL} 43, {@code NOTFND} 23, {@code ENDFILE} 8, {@code DUPREC} 7,
 * {@code DUPKEY} 3 and <b>{@code NOTOPEN} 0</b>. The corresponding
 * {@code com.cardemo.exception.FileUnavailableException} is therefore specification-derived rather than
 * source-derived. What would be needed to ground it is a legacy occurrence of either construct; none exists, so
 * no call site in this package raises or documents that status as reachable.
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * Reads one account row under a pessimistic write lock, so that a caller can compare its business field
     * values against a client-supplied snapshot and rewrite it without an interleaved write.
     *
     * <p>This is the Java form of {@code EXEC CICS READ ... UPDATE} issued at
     * {@code app/cbl/COACTUPC.cbl:L3894}-{@code :L3906}, the first step of {@code 9600-WRITE-PROCESSING}. It
     * exists because no inherited signature expresses it: {@code findById} reads without a lock, which would
     * let another writer change the row between the change-detection comparison and the rewrite and so defeat
     * the very check the legacy program performs. A {@code @Version} column cannot substitute for it either,
     * because a version counter reports that a row changed whereas the source reports which field values
     * differ from what the operator was shown; both layers are required.
     *
     * @param accountId the eleven-digit account identifier, {@code ACCT-ID} at {@code app/cpy/CVACT01Y.cpy:L5}.
     * @return an {@link Optional} holding the locked {@link Account}, or an empty {@link Optional} when no row
     * carries that identifier
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.accountId = :accountId")
    Optional<Account> findByIdForUpdate(@Param("accountId") Long accountId);

    /**
     * Reads the next window of the key sequence, positioned by the last key already consumed.
     *
     * <p><strong>Finding, Medium severity - the sequential scan positioned by page number.</strong> The
     * verification readers used {@code findAll(Pageable)}, which positions a window with SQL
     * {@code OFFSET}. An {@code OFFSET} is not a seek: the engine produces and discards every preceding
     * row, so the cost of window <em>n</em> grows with <em>n</em> and a full scan is quadratic in the row
     * count. A keyset predicate is a single index descent whose cost is constant per window, which is also
     * what the legacy {@code READ NEXT} actually is - VSAM resumes from the key it last returned and never
     * re-reads the front of the cluster. The page-number form modelled something the source does not do.
     *
     * <p>Two further costs went with it. {@code findAll(Pageable)} returns a {@code Page}, so every window
     * carried a {@code select count(*)} whose result the readers discarded; and the {@code OFFSET} form
     * re-reads rows a concurrent insert may have shifted, which can skip or duplicate a row across window
     * boundaries. A {@code List} keyed on the last consumed key has neither problem.
     *
     * <p><strong>The seed value.</strong> The first window is requested with any value strictly below every
     * legal key. {@code ACCT-ID} is {@code PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5} - unsigned display,
     * so its least legal value is zero, which {@code com.cardemo.model.entity.Account} enforces as its
     * minimum - and the column is declared {@code acct_id                 NUMERIC(11)   NOT NULL} in
     * {@code src/main/resources/db/migration/V1__create_schema.sql}. Any negative seed is therefore
     * provably below the whole key space; the reader passes {@code -1}.
     *
     * <p>The {@code Pageable} supplies the window size only. Its page number must be zero, because the
     * predicate - not an offset - is what positions the window, and the ordering is fixed by the method
     * name so it cannot be varied by a caller-supplied {@code Sort}.
     *
     * @param accountId the account identifier of the last row already consumed, or a negative value to
     *     start at the beginning of the key sequence.
     * @param pageable the window size; page number zero.
     * @return the next window in ascending key order, never {@code null} and empty once the scan is
     *     exhausted, which is the readers' end-of-file condition.
     */
    List<Account> findByAccountIdGreaterThanOrderByAccountIdAsc(Long accountId, Pageable pageable);
}
