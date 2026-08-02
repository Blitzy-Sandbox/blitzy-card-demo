/*
 * ******************************************************************
 * Component   : BillPaymentService
 * Application : CardDemo
 * Type        : Spring @Service (online bill payment)
 * Function    : Bill Payment - pay the account balance in full and
 *               record the transaction
 * Source      : app/cbl/COBIL00C.cbl (572 lines, 16 paragraphs) @ 7756d89
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
package com.cardemo.service.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.BillPaymentRequest;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.model.enums.TransactionSource;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;

/**
 * Online bill payment: the Java replacement for CICS transaction {@code CB00} and the program it fronts,
 * {@code app/cbl/COBIL00C.cbl}.
 *
 * <p>Every legacy claim below cites a path and a line or line range in the frozen corpus. The transaction and
 * program definitions are {@code app/csd/CARDDEMO.CSD:L337-L338}
 * ({@code DEFINE TRANSACTION(CB00) GROUP(CARDDEMO) PROGRAM(COBIL00C)}, carrying {@code ACTION(BACKOUT)}) and
 * {@code app/csd/CARDDEMO.CSD:L196} ({@code DEFINE PROGRAM(COBIL00C) GROUP(CARDDEMO) LANGUAGE(COBOL)}).</p>
 *
 * <h2>1. What it does</h2>
 *
 * <p>It settles a credit-card account balance in a single operation and records the settlement as one
 * transaction row. Three datasets participate and no others: {@code ACCTDAT} through
 * {@code AccountRepository}, the {@code CXACAIX} alternate-index path through
 * {@code CardCrossReferenceRepository}, and {@code TRANSACT} through {@code TransactionRepository}. The
 * batch-only datasets {@code TCATBALF}, {@code DISCGRP}, {@code TRANCATG} and {@code TRANTYPE} have no CICS
 * {@code FILE} definition anywhere in {@code app/csd/CARDDEMO.CSD} and are never reached from here.</p>
 *
 * <p>Three properties of the operation are contractual, not incidental:</p>
 *
 * <ul>
 *   <li><strong>The payment is always the entire balance.</strong>
 *       {@code MOVE ACCT-CURR-BAL TO TRAN-AMT} at {@code app/cbl/COBIL00C.cbl:224} derives the amount from
 *       the account; there is no partial-payment path anywhere in the 572 lines, and
 *       {@code BillPaymentRequest} correspondingly carries no amount member. The amount is
 *       <em>derived, never supplied</em>.</li>
 *   <li><strong>The resulting balance is exactly zero.</strong>
 *       {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT} at {@code app/cbl/COBIL00C.cbl:234}
 *       subtracts the balance from itself. The expression is transcribed as written rather than folded to a
 *       constant zero.</li>
 *   <li><strong>A zero balance is rejected.</strong> The guard at {@code app/cbl/COBIL00C.cbl:198} reads
 *       {@code ACCT-CURR-BAL &lt;= ZEROS}, so nothing-to-pay covers zero as well as negative.</li>
 * </ul>
 *
 * <p>Sixteen private methods correspond one-to-one to the sixteen {@code PROCEDURE DIVISION} paragraphs of
 * the source, enumerated in section 5. No paragraph is consolidated with another, and no seventeenth is
 * invented.</p>
 *
 * <h2>2. How to build, run and test</h2>
 *
 * <p>Java 25 ({@code maven.compiler.release} 25, no preview features), Maven 3.9.11, parent
 * {@code spring-boot-starter-parent:3.5.11}. The compiler runs {@code -Xlint:all -Werror}, so every warning
 * is a build failure; {@code jacoco-maven-plugin:0.8.12} enforces an 80 percent line-coverage floor at
 * {@code verify}. Build with {@code ./mvnw -B -ntp clean compile} and verify with
 * {@code ./mvnw -B -ntp clean verify}.</p>
 *
 * <p>Unit tests belong under {@code src/test/java/com/cardemo/unit/**} and never in this package, which
 * contains exactly one source file. The bean is surfaced over HTTP by
 * {@code com.cardemo.controller.BillingController} under {@code /api/billing/*}; this class performs no HTTP
 * translation of its own.</p>
 *
 * <h2>3. Key configurations and defaults</h2>
 *
 * <p>No property is specific to this bean. Its behaviour is fixed by its collaborators and by the constants
 * declared below.</p>
 *
 * <ul>
 *   <li><strong>Time source.</strong> A constructor-injected {@code java.time.Clock}. Every reading goes
 *       through {@code LocalDateTime.now(clock)}; no no-argument {@code now()} is called and no default
 *       time zone is consulted, which is what makes the timestamps deterministic and testable.</li>
 *   <li><strong>Transaction timestamp.</strong> {@code yyyy-MM-dd HH:mm:ss.000000} - twenty-six characters,
 *       space separator, and a six-digit fraction that is <em>always</em> zeros because
 *       {@code app/cbl/COBIL00C.cbl:266} moves {@code ZEROS} into {@code WS-TIMESTAMP-TM-MS6}. This is the
 *       online form and it is deliberately not the batch form; see section 4.</li>
 *   <li><strong>Header projection.</strong> {@code MM/dd/yy} into {@code CURDATEO X(8)} and
 *       {@code HH:mm:ss} into {@code CURTIMEO X(8)}, from {@code WS-CURDATE-MM-DD-YY} and
 *       {@code WS-CURTIME-HH-MM-SS} in {@code app/cpy/CSDAT01Y.cpy}. Two-digit year, because
 *       {@code app/cbl/COBIL00C.cbl:330} takes {@code WS-CURDATE-YEAR(3:2)}. These formatters are separate
 *       from the transaction formatter and are never interchanged.</li>
 *   <li><strong>Balance echo mask.</strong> {@code +9999999999.99} - fourteen characters, mandatory sign,
 *       ten integer digits, point, two decimals - from {@code WS-CURR-BAL} at
 *       {@code app/cbl/COBIL00C.cbl:56}, matching {@code CURBALI PIC X(14)} exactly.</li>
 *   <li><strong>Precisions.</strong> {@code TRAN-AMT} is {@code PIC S9(09)V99} and therefore
 *       {@code NUMERIC(11,2)}; account money is {@code PIC S9(10)V99} and therefore {@code NUMERIC(12,2)}.
 *       The assignment at {@code :224} crosses those two precisions, so the value is asserted to survive
 *       rather than being silently widened or truncated. All arithmetic is {@code BigDecimal}; there is no
 *       {@code float} or {@code double} anywhere in this file.</li>
 *   <li><strong>Fixed transaction literals.</strong> Eight of them, each a {@code static final} constant
 *       padded to its declared picture width: {@code '02'} into {@code TRAN-TYPE-CD X(02)}, {@code 2} into
 *       the numeric {@code TRAN-CAT-CD 9(04)} (stored as {@code 0002} at any fixed-width boundary),
 *       {@code TransactionSource.POS_TERMINAL} into {@code TRAN-SOURCE X(10)},
 *       {@code 'BILL PAYMENT - ONLINE'} into {@code TRAN-DESC X(100)}, {@code 999999999} into
 *       {@code TRAN-MERCHANT-ID 9(09)}, {@code 'BILL PAYMENT'} into {@code TRAN-MERCHANT-NAME X(50)}, and
 *       {@code 'N/A'} into both {@code TRAN-MERCHANT-CITY X(50)} and {@code TRAN-MERCHANT-ZIP X(10)}.</li>
 *   <li><strong>Transaction boundary.</strong> {@code @Transactional(rollbackFor = Exception.class)} on both
 *       public entry points. Transaction management itself is registered by {@code JpaConfig}; this class
 *       declares no {@code @EnableTransactionManagement}. Because {@code spring.jpa.open-in-view} is
 *       {@code false}, nothing is lazily loaded outside that boundary.</li>
 * </ul>
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <p>Each entry names the mistake, its severity under Rule 1 Clause F, and the one-line remedy.</p>
 *
 * <ul>
 *   <li><strong>Blocker - a partial payment amount is accepted or honoured.</strong> Remedy: delete it; the
 *       amount comes from {@code ACCT-CURR-BAL} at {@code :224} and from nowhere else.</li>
 *   <li><strong>Blocker - the write-then-account-update order at {@code :233}-{@code :235} is
 *       reordered.</strong> Remedy: insert the transaction first, then compute, then save the account.</li>
 *   <li><strong>Blocker - the batch timestamp format {@code yyyy-MM-dd-HH.mm.ss.SS0000} is used.</strong>
 *       That form belongs to {@code app/cbl/CBTRN02C.cbl} and is produced by
 *       {@code TransactionPostingProcessor}. Remedy: use {@code TRANSACTION_TIMESTAMP_FORMAT} here.</li>
 *   <li><strong>High - two timestamps are generated instead of one.</strong>
 *       {@code app/cbl/COBIL00C.cbl:230} generates once and {@code :231}-{@code :232} move that one value
 *       into both {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS}. Remedy: read the clock once.</li>
 *   <li><strong>High - the max-plus-one browse is replaced by a sequence, an identity column,
 *       {@code @GeneratedValue}, a retry loop or an upsert.</strong> Remedy: keep the top-one descending
 *       query; the race is deliberate and a collision must surface as {@code DuplicateRecordException}.</li>
 *   <li><strong>High - {@code &lt;= ZEROS} is changed to {@code &lt; ZEROS}.</strong> Remedy: restore it; a
 *       zero balance is rejected.</li>
 *   <li><strong>High - the compound guard at {@code :198}-{@code :199} is simplified.</strong> Remedy:
 *       restore the account-identifier-not-blank conjunct even though {@code :159} already rejected a blank
 *       identifier.</li>
 *   <li><strong>High - the six confirmation branches are merged or lose their identity.</strong> Remedy:
 *       keep all seven arms of {@code ConfirmationBranch}; blank and {@code 'Y'} both read the account but
 *       reach different outcomes.</li>
 *   <li><strong>High - {@code equals()} is used instead of {@code compareTo()} on a
 *       {@code BigDecimal}.</strong> Remedy: {@code compareTo}, always; {@code 0} and {@code 0.00} are
 *       equal in value and unequal under {@code equals}.</li>
 *   <li><strong>High - a card number is logged or serialized.</strong> {@code XREF-CARD-NUM X(16)} flows
 *       into {@code TRAN-CARD-NUM} at {@code :225}, so this file handles a live sixteen-digit card number.
 *       Remedy: log identifiers and outcomes only; every {@code toString()} here is shape-only.</li>
 *   <li><strong>Medium - the single transaction boundary is dropped.</strong> That would permit an orphaned
 *       transaction row when the account update fails. Remedy: restore
 *       {@code @Transactional(rollbackFor = Exception.class)}.</li>
 *   <li><strong>Medium - the {@code :193}-{@code :194} balance capture is hoisted out of its enclosing
 *       {@code IF NOT ERR-FLG-ON}.</strong> Remedy: leave it inside; it runs on the invalid-confirmation arm
 *       too.</li>
 *   <li><strong>Medium - the deep-link truncation at {@code :116}-{@code :121} is "fixed".</strong> Remedy:
 *       keep the eleven-character truncation; parity is the contract.</li>
 *   <li><strong>Low - the double space in the success message is tidied.</strong> Remedy: restore it; the
 *       {@code STRING} at {@code :527}-{@code :531} concatenates a trailing and a leading blank.</li>
 *   <li><strong>Low - a retained parity artefact is deleted to please a linter.</strong> Remedy: restore
 *       it; the four are listed in section 6 and each is tracked in {@code DECISION_LOG.md}.</li>
 *   <li><strong>Low - {@code PIC X(26)} timestamps are converted to a temporal type.</strong> Remedy: keep
 *       them as text; three mutually incompatible producers write those columns and the fixture's
 *       {@code TRAN-PROC-TS} is twenty-six blanks, which no format parses.</li>
 * </ul>
 *
 * <h2>5. Provenance</h2>
 *
 * <p>{@code grep -nE '^ {7}[A-Z0-9][A-Z0-9-]*\.' app/cbl/COBIL00C.cbl} returns eighteen matches. Two are
 * {@code IDENTIFICATION DIVISION} entries - {@code PROGRAM-ID.} at {@code :24} and {@code AUTHOR.} at
 * {@code :25} - so the {@code PROCEDURE DIVISION} paragraph count is exactly sixteen, and each has its own
 * private method here:</p>
 *
 * <ol>
 *   <li>{@code MAIN-PARA.} {@code :99} - {@link #mainPara}</li>
 *   <li>{@code PROCESS-ENTER-KEY.} {@code :154} - {@link #processEnterKey}</li>
 *   <li>{@code GET-CURRENT-TIMESTAMP.} {@code :249} - {@link #getCurrentTimestamp}</li>
 *   <li>{@code RETURN-TO-PREV-SCREEN.} {@code :273} - {@link #returnToPrevScreen}</li>
 *   <li>{@code SEND-BILLPAY-SCREEN.} {@code :289} - {@link #sendBillpayScreen}</li>
 *   <li>{@code RECEIVE-BILLPAY-SCREEN.} {@code :306} - {@link #receiveBillpayScreen}</li>
 *   <li>{@code POPULATE-HEADER-INFO.} {@code :319} - {@link #populateHeaderInfo}</li>
 *   <li>{@code READ-ACCTDAT-FILE.} {@code :343} - {@link #readAcctdatFile}</li>
 *   <li>{@code UPDATE-ACCTDAT-FILE.} {@code :377} - {@link #updateAcctdatFile}</li>
 *   <li>{@code READ-CXACAIX-FILE.} {@code :408} - {@link #readCxacaixFile}</li>
 *   <li>{@code STARTBR-TRANSACT-FILE.} {@code :441} - {@link #startbrTransactFile}</li>
 *   <li>{@code READPREV-TRANSACT-FILE.} {@code :472} - {@link #readprevTransactFile}</li>
 *   <li>{@code ENDBR-TRANSACT-FILE.} {@code :501} - {@link #endbrTransactFile}</li>
 *   <li>{@code WRITE-TRANSACT-FILE.} {@code :510} - {@link #writeTransactFile}</li>
 *   <li>{@code CLEAR-CURRENT-SCREEN.} {@code :552} - {@link #clearCurrentScreen}</li>
 *   <li>{@code INITIALIZE-ALL-FIELDS.} {@code :560} - {@link #initializeAllFields}</li>
 * </ol>
 *
 * <p>The paragraph-level correspondence is deliberate and overrides the industry guidance against literal
 * transliteration. Behavioural parity is the contract of this migration and {@code TRACEABILITY_MATRIX.md}
 * must be mechanically provable against the correspondence. The readability concern that guidance raises is
 * answered by the source-citing Javadoc on every method and by the matrix, not by restructuring. Where the
 * guidance can be honoured without touching control flow it is: naming is idiomatic, {@code BigDecimal}
 * replaces packed decimal, and injected collaborators replace static linkage.</p>
 *
 * <p>The source's own header comment at {@code app/cbl/COBIL00C.cbl:1}-{@code :22} contains the typo
 * {@code tractionsaction}. It is cited here as evidence and deliberately not propagated into the banner
 * above.</p>
 *
 * <h2>6. Preserved-defect and parity-artefact register</h2>
 *
 * <p>Each of the following is a faithful reproduction of something the system of record does. None is an
 * oversight of this migration, none may be deleted to satisfy a linter, and every one carries a tracking
 * reference in {@code DECISION_LOG.md}. Rule 1 Clause B forbids <em>untracked</em> dead code and work items
 * without an owner or tracking reference; a tracked, cited, justified parity artefact satisfies it.</p>
 *
 * <ul>
 *   <li><strong>P1 - Low. {@code WS-USR-MODIFIED} is written and never read.</strong> Declared at
 *       {@code :48}-{@code :50} with {@code 88 USR-MODIFIED-YES} and {@code 88 USR-MODIFIED-NO}, and set by
 *       {@code SET USR-MODIFIED-NO TO TRUE} at {@code :102}. It is tested nowhere in the program.
 *       Reproduced as a method-local assignment in {@link #mainPara}.</li>
 *   <li><strong>P2 - Low. Two working-storage items are declared and never referenced.</strong>
 *       {@code WS-TRAN-AMT PIC +99999999.99} at {@code :55} and
 *       {@code WS-TRAN-DATE PIC X(08) VALUE '00/00/00'} at {@code :58}. Neither appears in the
 *       {@code PROCEDURE DIVISION}. Reproduced as initialised, never-read fields of the request
 *       context.</li>
 *   <li><strong>P3 - Low. {@code ENDBR-TRANSACT-FILE} has no error handling at all.</strong>
 *       {@code :501}-{@code :505} is a bare {@code EXEC CICS ENDBR DATASET(WS-TRANSACT-FILE) END-EXEC.}
 *       with no {@code RESP}, no {@code RESP2} and no {@code EVALUATE}. This is an absent guard and it is
 *       preserved as absent: {@link #endbrTransactFile} adds none.</li>
 *   <li><strong>P4 - Low. Five COMMAREA extension fields are declared and never referenced.</strong>
 *       {@code CDEMO-CB00-TRNID-FIRST}, {@code CDEMO-CB00-TRNID-LAST}, {@code CDEMO-CB00-PAGE-NUM},
 *       {@code CDEMO-CB00-NEXT-PAGE-FLG} and {@code CDEMO-CB00-TRN-SEL-FLG} at {@code :65}-{@code :71}. No
 *       pagination is modelled here, and {@code app/cpy/COCOM01Y.cpy} itself carries neither a page number
 *       nor a next-page flag.</li>
 *   <li><strong>P5 - Medium, behavioural. The deep-link assignment truncates and mistypes.</strong>
 *       {@code CDEMO-CB00-TRN-SELECTED} is {@code PIC X(16)} at {@code :72} while
 *       {@code ACTIDINI OF COBIL0AI} is {@code PIC X(11)}, so {@code MOVE} at {@code :118}-{@code :119}
 *       silently keeps the leading eleven characters - and it places a <em>transaction</em> identifier into
 *       an <em>account</em> identifier field. {@code PROCESS-ENTER-KEY} is then performed at {@code :120}
 *       before the screen has ever been sent. Reproduced exactly, truncation included.</li>
 *   <li><strong>P6 - Medium, behavioural. The write sequence has no error gate between its steps.</strong>
 *       {@code :210}-{@code :235} contains no {@code IF NOT ERR-FLG-ON} between the cross-reference read,
 *       the browse and the write, so a failed read or browse still falls through into building and writing
 *       the transaction. Reproduced: {@link #processEnterKey} performs the steps in order without an early
 *       return. See D1 in section 7 for the one place this cannot be reproduced literally.
 *       <br>Two consequences are observable, and are therefore assertable rather than merely described.
 *       First, a cross-reference read that finds nothing leaves {@code XREF-CARD-NUM} unpopulated, so the
 *       posted transaction carries a card number of sixteen spaces while the balance is still decremented
 *       at {@code :234} and rewritten at {@code :235}. Second, the failing paragraph sends its error screen
 *       and the write-success arm then sends a second screen in the same turn, so the send count exceeds one
 *       and the last message an operator sees is the success text even though the read failed. The retained
 *       outcome is nonetheless the first failure, because the failure latch is first-error-wins and
 *       {@link #markSuccess} refuses to overwrite it - which is what keeps the failure outcomes
 *       distinguishable while the source's single {@code WS-MESSAGE} field is being overwritten.</li>
 *   <li><strong>P7 - Low. The identifier-generation race is retained.</strong> Reading the maximum
 *       identifier and adding one is not atomic, exactly as {@code STARTBR}/{@code READPREV}/{@code ENDBR}
 *       at {@code :212}-{@code :216} was not. Substituting a database sequence would change generated
 *       values and break comparison against the legacy baseline, so a collision is allowed to surface as
 *       {@code DuplicateRecordException} from the primary-key constraint.</li>
 *   <li><strong>P8 - Low. The success message contains a double space.</strong> The {@code STRING} at
 *       {@code :527}-{@code :531} concatenates {@code 'Payment successful. '}, which ends with a blank,
 *       with {@code ' Your Transaction ID is '}, which begins with one. Reproduced byte-for-byte.</li>
 *   <li><strong>P9 - Low. One arm of the back-navigation branch is unreachable here.</strong>
 *       {@code :129}-{@code :133} resolves the transfer target from {@code CDEMO-FROM-PROGRAM} when that
 *       field is populated and from {@code 'COMEN01C'} when it is blank. The field arrives in the COMMAREA,
 *       which has no Java counterpart, so it is always absent on entry and the main-menu arm always wins.
 *       <strong>Both arms are implemented anyway</strong>, because deleting the unreachable one would break
 *       the paragraph correspondence the scope-coverage gate reads - and because a caller that later chooses
 *       to supply the originating program would find the arm already correct.</li>
 *   <li><strong>P10 - Low. {@code RECEIVE-BILLPAY-SCREEN} captures response codes it never evaluates.</strong>
 *       {@code :312}-{@code :313} binds {@code RESP} and {@code RESP2}, and no statement anywhere tests
 *       either for this paragraph - unlike every other I/O paragraph, which follows its command with an
 *       {@code EVALUATE}. This is a second absent guard and it is preserved as absent:
 *       {@link #receiveBillpayScreen} records a normal response and checks nothing.</li>
 * </ul>
 *
 * <h2>7. Labelled deviations</h2>
 *
 * <p>Three places cannot be reproduced literally. All three are labelled here rather than absorbed silently,
 * because a reviewer comparing the two sources side by side would otherwise conclude that semantics were
 * altered. Each is a place where the source's behaviour is either undefined or destructive, so faithful
 * reproduction is not available and the closest defensible analogue is taken instead.</p>
 *
 * <ul>
 *   <li><strong>D1 - Medium. A non-numeric browse key becomes a typed abend rather than undefined
 *       behaviour.</strong> Because of P6, {@code MOVE TRAN-ID TO WS-TRAN-ID-NUM} at {@code :216} can run
 *       with {@code TRAN-ID} still holding the {@code HIGH-VALUES} that {@code :212} moved into it, when the
 *       browse failed on its {@code WHEN OTHER} arm. Moving {@code X(16)} high values into a
 *       {@code PIC 9(16)} display field is undefined in COBOL and would raise a data exception on the
 *       following {@code ADD}. Undefined behaviour cannot be reproduced, and writing a garbage transaction
 *       would be worse than either alternative, so the closest faithful analogue is taken: a
 *       {@code FatalProcessingException} carrying abend code {@code 9999}. The enclosing transaction then
 *       rolls back.</li>
 *   <li><strong>D2 - Low. Two independent commits become one.</strong> In CICS the transaction write at
 *       {@code :233} and the account rewrite at {@code :235} were two separate units of work. Here they
 *       share one {@code @Transactional(rollbackFor = Exception.class)} boundary. This is a
 *       <em>mechanism substitution</em> and not a behaviour change in the success case; in the failure case
 *       it closes an orphaned-transaction-row hazard that the legacy had, which is a strict improvement and
 *       is labelled as such rather than passed off as equivalence.</li>
 *   <li><strong>D3 - Low. An oversized balance becomes a typed abend rather than a silently wrong
 *       amount.</strong> {@code MOVE ACCT-CURR-BAL TO TRAN-AMT} at {@code :224} moves
 *       {@code PIC S9(10)V99} into {@code PIC S9(09)V99}, so a balance of a billion or more loses its
 *       high-order digit without diagnostic - recording a wrong amount and then, through {@code :234},
 *       leaving a wrong balance behind. That is silent ledger corruption rather than a behaviour worth
 *       preserving, so the condition is asserted and raised as a {@code FatalProcessingException} carrying
 *       abend code {@code 9999}. Classified Low because the account entity bounds the balance at ten integer
 *       digits, making the condition reachable only between one and ten billion.</li>
 * </ul>
 *
 * <h2>8. Reconciliations</h2>
 *
 * <ul>
 *   <li><strong>The pseudo-conversational re-entry flag has no server-side counterpart.</strong>
 *       {@code CDEMO-PGM-CONTEXT PIC 9(01)} with {@code 88 CDEMO-PGM-ENTER VALUE 0} and
 *       {@code 88 CDEMO-PGM-REENTER VALUE 1} in {@code app/cpy/COCOM01Y.cpy} collapses into stateless
 *       request handling: {@link EntryMode} arrives as a parameter and leaves as response metadata. Nothing
 *       is stored between requests.</li>
 *   <li><strong>Navigation collapses to a hint.</strong> {@code CDEMO-FROM-TRANID},
 *       {@code CDEMO-TO-TRANID}, {@code CDEMO-FROM-PROGRAM}, {@code CDEMO-TO-PROGRAM},
 *       {@code CDEMO-LAST-MAP X(7)} and {@code CDEMO-LAST-MAPSET X(7)} have no Java equivalent, because
 *       routing is URL-based and there is no {@code EXEC CICS XCTL}. The target-resolution logic of
 *       {@code :275}-{@code :279} is still evaluated and returned as {@link Navigation}; no screen state is
 *       retained.</li>
 *   <li><strong>The map is a field contract, not a user interface.</strong> {@code app/cpy-bms/COBIL00.CPY}
 *       declares exactly ten input fields - {@code TRNNAMEI X(4)}, {@code TITLE01I X(40)},
 *       {@code CURDATEI X(8)}, {@code PGMNAMEI X(8)}, {@code TITLE02I X(40)}, {@code CURTIMEI X(8)},
 *       {@code ACTIDINI X(11)}, {@code CURBALI X(14)}, {@code CONFIRMI X(1)} and {@code ERRMSGI X(78)} -
 *       and they fix the shape of {@code BillPaymentRequest} and {@link BillPaymentScreen}. No BMS, no
 *       3270, no HTML is emitted.</li>
 *   <li><strong>Cursor positioning becomes a marker.</strong> The {@code MOVE -1 TO} <em>field</em>{@code L}
 *       idiom and the {@code CURSOR} option of {@code SEND MAP} become {@link CursorField}, so that
 *       {@code CONFIRML} and {@code ACTIDINL} stay distinguishable on the response.</li>
 *   <li><strong>The colour attribute becomes a message kind.</strong> {@code MOVE DFHGREEN TO ERRMSGC} at
 *       {@code :526} becomes {@link MessageKind#INFORMATIONAL}; no 3270 attribute byte is emitted.</li>
 *   <li><strong>Multiple sends per pass are possible and the last one wins.</strong> {@code :120} can send
 *       from inside {@code PROCESS-ENTER-KEY} before {@code :122} sends again, and the write success path
 *       sends at {@code :532} before {@code :242} sends again. Every {@code EXEC CICS SEND MAP} carries
 *       {@code ERASE}, so each overwrites the terminal. {@link BillPaymentResult#sendCount()} reports how
 *       many occurred and the returned screen is the last of them.</li>
 *   <li><strong>{@code STARTBR}'s {@code NOTFND} arm is preserved but unreachable here.</strong> The arm at
 *       {@code :454}-{@code :459} is implemented and status-driven, but the relational positioning never
 *       produces it: the empty-table case must reach {@code READPREV}'s {@code ENDFILE} arm at
 *       {@code :487}-{@code :488} so that {@code MOVE ZEROS TO TRAN-ID} yields a first identifier of one.
 *       Routing an empty table to {@code STARTBR}'s arm instead would make a first payment impossible.</li>
 * </ul>
 *
 * <h2>9. Performance</h2>
 *
 * <p><strong>Not available.</strong> No throughput, latency or capacity objective for bill payment exists
 * anywhere in the frozen corpus - the COBOL publishes no service level and none may be invented - so the
 * performance gate records a measured baseline rather than a target. What would be needed to state one is a
 * source construct that does not exist. Within that constraint the implementation avoids obvious
 * inefficiency: identifier generation is one top-one descending query rather than a scan, the account read
 * is a single primary-key lookup, and all fixed literals are {@code static final} rather than allocated per
 * call.</p>
 *
 * <p><strong>Not available</strong> likewise for any idempotency or duplicate-submission contract. The
 * source defines none; see P7.</p>
 *
 * <h2>10. Thread safety and state</h2>
 *
 * <p>The bean is immutable and stateless after construction. Its five fields are {@code final} collaborator
 * references; there is no static mutable state and no field {@code @Autowired} or setter injection. Every
 * item of the source's {@code WORKING-STORAGE} - {@code WS-ERR-FLG}, {@code WS-USR-MODIFIED},
 * {@code WS-CONF-PAY-FLG}, {@code WS-MESSAGE}, {@code WS-CURR-BAL}, {@code WS-TRAN-ID-NUM},
 * {@code WS-TIMESTAMP}, {@code WS-RESP-CD}, {@code WS-REAS-CD} and the record areas - lives on a
 * {@code PaymentContext} allocated per invocation inside the public entry point, so concurrent requests
 * share nothing. No mutable state escapes: {@link BillPaymentResult} and {@link BillPaymentScreen} are
 * records of immutable components.</p>
 *
 * <p>No field of the request context is ever logged as a payload. {@code XREF-CARD-NUM} is personally
 * identifiable and financial; the balance and the amount are financial. Identifiers and outcomes are logged
 * and nothing else, and every {@code toString()} declared in this file reports shapes rather than values so
 * that no accidental interpolation can expose a card number or a balance.</p>
 *
 * @see BillPaymentRequest
 * @see FileStatusMapper
 */
@Service
public class BillPaymentService {

    /**
     * Diagnostic sink. It replaces the {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} statements at
     * {@code app/cbl/COBIL00C.cbl:366}, {@code :397}, {@code :430}, {@code :461}, {@code :490} and
     * {@code :541}. Only identifiers, operations and rendered I/O statuses are ever emitted; no card number,
     * no balance and no amount reaches a log line.
     */
    private static final Logger LOG = LoggerFactory.getLogger(BillPaymentService.class);

    /** {@code WS-TRANID PIC X(04) VALUE 'CB00'} at {@code app/cbl/COBIL00C.cbl:38}. */
    private static final String TRANSACTION_ID = "CB00";

    /** {@code WS-PGMNAME PIC X(08) VALUE 'COBIL00C'} at {@code app/cbl/COBIL00C.cbl:37}. */
    private static final String PROGRAM_NAME = "COBIL00C";

    /** {@code MAPSET('COBIL00')} at {@code app/cbl/COBIL00C.cbl:297} and {@code :310}. */
    private static final String MAPSET_NAME = "COBIL00";

    /** {@code MAP('COBIL0A')} at {@code app/cbl/COBIL00C.cbl:296} and {@code :309}. */
    private static final String MAP_NAME = "COBIL0A";

    /** The no-context and blank-target navigation fallback at {@code :108} and {@code :276}. */
    private static final String SIGN_ON_PROGRAM = "COSGN00C";

    /** The attention-key-three fallback when {@code CDEMO-FROM-PROGRAM} is blank, at {@code :130}. */
    private static final String MAIN_MENU_PROGRAM = "COMEN01C";

    /** {@code WS-ACCTDAT-FILE PIC X(08) VALUE 'ACCTDAT '} at {@code :41} - note the trailing blank. */
    private static final String ACCTDAT_FILE = "ACCTDAT ";

    /** {@code WS-CXACAIX-FILE PIC X(08) VALUE 'CXACAIX '} at {@code :42} - note the trailing blank. */
    private static final String CXACAIX_FILE = "CXACAIX ";

    /** {@code WS-TRANSACT-FILE PIC X(08) VALUE 'TRANSACT'} at {@code :40}. */
    private static final String TRANSACT_FILE = "TRANSACT";

    /** The {@code READ ... UPDATE} of {@code READ-ACCTDAT-FILE} at {@code :345}-{@code :354}. */
    private static final String OPERATION_READ_UPDATE = "READ UPDATE";

    /** The {@code REWRITE} of {@code UPDATE-ACCTDAT-FILE} at {@code :379}-{@code :385}. */
    private static final String OPERATION_REWRITE = "REWRITE";

    /** The plain {@code READ} of {@code READ-CXACAIX-FILE} at {@code :410}-{@code :418}. */
    private static final String OPERATION_READ = "READ";

    /** The {@code STARTBR} of {@code STARTBR-TRANSACT-FILE} at {@code :443}-{@code :449}. */
    private static final String OPERATION_STARTBR = "STARTBR";

    /** The {@code READPREV} of {@code READPREV-TRANSACT-FILE} at {@code :474}-{@code :482}. */
    private static final String OPERATION_READPREV = "READPREV";

    /** The {@code ENDBR} of {@code ENDBR-TRANSACT-FILE} at {@code :503}-{@code :505}. */
    private static final String OPERATION_ENDBR = "ENDBR";

    /** The {@code WRITE} of {@code WRITE-TRANSACT-FILE} at {@code :512}-{@code :520}. */
    private static final String OPERATION_WRITE = "WRITE";

    /*
     * The DFHRESP-to-FILE-STATUS translation table.
     *
     * A CICS program has no FILE STATUS at all - app/cbl/COBIL00C.cbl branches on DFHRESP values through
     * WS-RESP-CD, not on the two-character status that a batch program's SELECT clause would populate. The
     * translation exists so that the diagnostic this class emits can go through the shared FileStatusMapper,
     * which owns the FILE STATUS IS: NNNN literal and the four-character rendering. Choosing which status
     * stands for which response is therefore this program's own decision and belongs here.
     *
     * The four exact values are taken from com.cardemo.model.enums.FileStatus rather than restated, so the
     * two-character codes are single-sourced and Clause C's no-duplication requirement is met on the one part
     * of this table that another type already owns.
     */

    /** {@code FILE STATUS} {@code '00'} - the only value that continues without a branch. */
    private static final String IO_STATUS_SUCCESS = exactCode(FileStatus.SUCCESS);

    /** {@code FILE STATUS} {@code '10'} - the {@code DFHRESP(ENDFILE)} analogue at {@code :487}. */
    private static final String IO_STATUS_END_OF_FILE = exactCode(FileStatus.END_OF_FILE);

    /** {@code FILE STATUS} {@code '22'} - the {@code DFHRESP(DUPKEY)} and {@code DUPREC} analogue. */
    private static final String IO_STATUS_DUPLICATE_KEY = exactCode(FileStatus.DUPLICATE_KEY);

    /** {@code FILE STATUS} {@code '23'} - the {@code DFHRESP(NOTFND)} analogue. */
    private static final String IO_STATUS_RECORD_NOT_FOUND = exactCode(FileStatus.RECORD_NOT_FOUND);

    /**
     * {@code FILE STATUS} {@code '90'} - the {@code WHEN OTHER} physical-or-logical error analogue.
     *
     * <p>Stated as a literal rather than taken from {@link FileStatus#IO_ERROR}, because that constant is a
     * <em>family</em> covering every status whose first byte is {@code '9'} and deliberately carries no single
     * canonical code. A concrete member of the family has to be chosen here for the mapper to render, and
     * {@code '90'} is the general physical-or-logical error of the family.</p>
     */
    private static final String IO_STATUS_IO_ERROR = FileStatus.IO_ERROR_FIRST_BYTE + "0";

    /** {@code DFHRESP(NORMAL)}. */
    private static final int CICS_RESP_NORMAL = 0;

    /** {@code DFHRESP(NOTFND)}. */
    private static final int CICS_RESP_NOTFND = 13;

    /** {@code DFHRESP(DUPREC)}. */
    private static final int CICS_RESP_DUPREC = 14;

    /** {@code DFHRESP(DUPKEY)}. */
    private static final int CICS_RESP_DUPKEY = 15;

    /** {@code DFHRESP(IOERR)}. */
    private static final int CICS_RESP_IOERR = 17;

    /** {@code DFHRESP(ENDFILE)}. */
    private static final int CICS_RESP_ENDFILE = 20;

    /** {@code WS-REAS-CD} when the operation carries no secondary reason. */
    private static final int CICS_REAS_NONE = 0;

    /** {@code app/cbl/COBIL00C.cbl:161} - note the capitalised {@code NOT} and the three trailing dots. */
    private static final String MSG_ACCOUNT_ID_EMPTY = "Acct ID can NOT be empty...";

    /** {@code app/cbl/COBIL00C.cbl:187} - the {@code WHEN OTHER} arm of the confirmation gate. */
    private static final String MSG_CONFIRMATION_INVALID = "Invalid value. Valid values are (Y/N)...";

    /** {@code app/cbl/COBIL00C.cbl:237} - the {@code ELSE} arm of the write gate. */
    private static final String MSG_CONFIRMATION_REQUIRED = "Confirm to make a bill payment...";

    /** {@code app/cbl/COBIL00C.cbl:201} - emitted when the balance is at or below zero. */
    private static final String MSG_NOTHING_TO_PAY = "You have nothing to pay...";

    /** {@code app/cbl/COBIL00C.cbl:361}, {@code :392} and {@code :425} - three independent sites. */
    private static final String MSG_ACCOUNT_NOT_FOUND = "Account ID NOT found...";

    /** {@code app/cbl/COBIL00C.cbl:368}. */
    private static final String MSG_ACCOUNT_LOOKUP_FAILED = "Unable to lookup Account...";

    /** {@code app/cbl/COBIL00C.cbl:399} - note the capitalised {@code Update}. */
    private static final String MSG_ACCOUNT_UPDATE_FAILED = "Unable to Update Account...";

    /** {@code app/cbl/COBIL00C.cbl:432}. */
    private static final String MSG_XREF_LOOKUP_FAILED = "Unable to lookup XREF AIX file...";

    /** {@code app/cbl/COBIL00C.cbl:456}. */
    private static final String MSG_TRANSACTION_NOT_FOUND = "Transaction ID NOT found...";

    /** {@code app/cbl/COBIL00C.cbl:463} and {@code :492} - two independent sites, one literal. */
    private static final String MSG_TRANSACTION_LOOKUP_FAILED = "Unable to lookup Transaction...";

    /** {@code app/cbl/COBIL00C.cbl:536} - reached from both the duplicate-key and duplicate-record arms. */
    private static final String MSG_TRANSACTION_DUPLICATE = "Tran ID already exist...";

    /** {@code app/cbl/COBIL00C.cbl:543} - note the capitalised {@code Add} and the lower-case {@code pay}. */
    private static final String MSG_TRANSACTION_WRITE_FAILED = "Unable to Add Bill pay Transaction...";

    /**
     * {@code CCDA-MSG-INVALID-KEY PIC X(50)} from {@code app/cpy/CSMSG01Y.cpy}, moved to {@code WS-MESSAGE}
     * by the {@code WHEN OTHER} arm of the attention-key evaluation at {@code app/cbl/COBIL00C.cbl:140}.
     * The declared value is trailing-space padded to fifty characters, and the padding is part of the
     * literal: nine blanks follow the third dot.
     */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...         ";

    /**
     * The first {@code DELIMITED BY SIZE} operand of the {@code STRING} at
     * {@code app/cbl/COBIL00C.cbl:527}. It <strong>ends with a blank</strong>, and
     * {@link #MSG_SUCCESS_INFIX} <strong>begins with one</strong>, so the composed message contains a
     * double space. That is the system of record's behaviour and it is reproduced rather than tidied.
     */
    private static final String MSG_SUCCESS_PREFIX = "Payment successful. ";

    /** The second {@code DELIMITED BY SIZE} operand at {@code app/cbl/COBIL00C.cbl:528}. */
    private static final String MSG_SUCCESS_INFIX = " Your Transaction ID is ";

    /** The fourth {@code DELIMITED BY SIZE} operand at {@code app/cbl/COBIL00C.cbl:530}. */
    private static final String MSG_SUCCESS_SUFFIX = ".";

    /** {@code CCDA-TITLE01 PIC X(40)} from {@code app/cpy/COTTL01Y.cpy}, padded exactly as declared. */
    private static final String TITLE_LINE_01 = "      AWS Mainframe Modernization       ";

    /** {@code CCDA-TITLE02 PIC X(40)} from {@code app/cpy/COTTL01Y.cpy}, padded exactly as declared. */
    private static final String TITLE_LINE_02 = "              CardDemo                  ";

    /** {@code ACTIDINI PIC X(11)} from {@code app/cpy-bms/COBIL00.CPY}. */
    private static final int ACCOUNT_ID_FIELD_WIDTH = 11;

    /** {@code CURBALI PIC X(14)} from {@code app/cpy-bms/COBIL00.CPY}, matching {@code WS-CURR-BAL}. */
    private static final int BALANCE_ECHO_WIDTH = 14;

    /** The ten integer digits of the {@code +9999999999.99} mask at {@code app/cbl/COBIL00C.cbl:56}. */
    private static final int BALANCE_ECHO_INTEGER_DIGITS = 10;

    /** {@code CONFIRMI PIC X(1)} from {@code app/cpy-bms/COBIL00.CPY} - a one-character gate. */
    private static final int CONFIRMATION_FIELD_WIDTH = 1;

    /** {@code ERRMSGI PIC X(78)} from {@code app/cpy-bms/COBIL00.CPY} - the screen message field. */
    private static final int MESSAGE_FIELD_WIDTH = 78;

    /** {@code TRAN-ID PIC X(16)} from {@code app/cpy/CVTRA05Y.cpy}. */
    private static final int TRANSACTION_ID_WIDTH = 16;

    /** {@code TRAN-TYPE-CD PIC X(02)} from {@code app/cpy/CVTRA05Y.cpy}. */
    private static final int TRANSACTION_TYPE_WIDTH = 2;

    /** {@code TRAN-CARD-NUM PIC X(16)} from {@code app/cpy/CVTRA05Y.cpy}, and {@code XREF-CARD-NUM}. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** {@code TRAN-DESC PIC X(100)} from {@code app/cpy/CVTRA05Y.cpy}. */
    private static final int TRANSACTION_DESCRIPTION_WIDTH = 100;

    /** {@code TRAN-MERCHANT-NAME PIC X(50)} from {@code app/cpy/CVTRA05Y.cpy}. */
    private static final int MERCHANT_NAME_WIDTH = 50;

    /** {@code TRAN-MERCHANT-CITY PIC X(50)} from {@code app/cpy/CVTRA05Y.cpy}. */
    private static final int MERCHANT_CITY_WIDTH = 50;

    /** {@code TRAN-MERCHANT-ZIP PIC X(10)} from {@code app/cpy/CVTRA05Y.cpy}. */
    private static final int MERCHANT_ZIP_WIDTH = 10;

    /** {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS}, both {@code PIC X(26)}, and {@code WS-TIMESTAMP}. */
    private static final int TIMESTAMP_WIDTH = 26;

    /** The scale of {@code TRAN-AMT PIC S9(09)V99} and of {@code ACCT-CURR-BAL PIC S9(10)V99}. */
    private static final int MONEY_SCALE = 2;

    /** {@code TRAN-TYPE-CD} at {@code app/cbl/COBIL00C.cbl:220} - textual, two characters. */
    private static final String TRANSACTION_TYPE_CODE_BILL_PAYMENT = "02";

    /**
     * {@code TRAN-CAT-CD} at {@code app/cbl/COBIL00C.cbl:221}. The receiving field is
     * <strong>numeric</strong> {@code PIC 9(04)}, so {@code MOVE 2} stores {@code 0002} and any
     * fixed-width rendering must zero-pad to four digits.
     */
    private static final int TRANSACTION_CATEGORY_CODE_BILL_PAYMENT = 2;

    /** {@code TRAN-DESC} at {@code app/cbl/COBIL00C.cbl:223}, space-padded to one hundred characters. */
    private static final String TRANSACTION_DESCRIPTION_BILL_PAYMENT = "BILL PAYMENT - ONLINE";

    /** {@code TRAN-MERCHANT-ID} at {@code app/cbl/COBIL00C.cbl:226} - numeric {@code PIC 9(09)}. */
    private static final long MERCHANT_ID_BILL_PAYMENT = 999_999_999L;

    /** {@code TRAN-MERCHANT-NAME} at {@code app/cbl/COBIL00C.cbl:227}, space-padded to fifty. */
    private static final String MERCHANT_NAME_BILL_PAYMENT = "BILL PAYMENT";

    /** {@code TRAN-MERCHANT-CITY} at {@code app/cbl/COBIL00C.cbl:228}, space-padded to fifty. */
    private static final String MERCHANT_CITY_NOT_APPLICABLE = "N/A";

    /** {@code TRAN-MERCHANT-ZIP} at {@code app/cbl/COBIL00C.cbl:229}, space-padded to ten. */
    private static final String MERCHANT_ZIP_NOT_APPLICABLE = "N/A";

    /**
     * The largest magnitude {@code TRAN-AMT PIC S9(09)V99} can hold. The assignment at
     * {@code app/cbl/COBIL00C.cbl:224} crosses from account money, {@code PIC S9(10)V99}, into this
     * narrower picture, so the value is asserted against this bound rather than silently truncated.
     */
    private static final BigDecimal TRANSACTION_AMOUNT_LIMIT = new BigDecimal("999999999.99");

    /**
     * Zero at the money scale. It stands for a numeric record area that no read has populated - the state of
     * {@code ACCT-CURR-BAL} on the paths where {@code :193} runs without a preceding successful read - and for
     * the zeroed {@code TRAN-AMT} that {@code INITIALIZE TRAN-RECORD} at {@code :218} produces.
     */
    private static final BigDecimal ZERO_BALANCE = BigDecimal.ZERO.setScale(MONEY_SCALE);

    /**
     * The {@code MOVE HIGH-VALUES TO TRAN-ID} sentinel of {@code app/cbl/COBIL00C.cbl:212}. Sixteen
     * occurrences of the highest code point stand in for the sixteen {@code x'FF'} bytes the source moves.
     * The value is deliberately non-numeric so that the browse-failure path of deviation D1 is detected
     * rather than silently reinterpreted.
     */
    private static final String HIGH_VALUES_KEY =
            String.valueOf(Character.MAX_VALUE).repeat(TRANSACTION_ID_WIDTH);

    /** The {@code MOVE ZEROS TO TRAN-ID} value of {@code app/cbl/COBIL00C.cbl:488}. */
    private static final String TRANSACTION_ID_ZEROS = "0".repeat(TRANSACTION_ID_WIDTH);

    /** The abend code raised by deviation D1; see the class documentation, section 7. */
    private static final String ABEND_CODE_DATA_EXCEPTION = "9999";

    /** {@code WS-CUR-DATE-X10} with {@code DATESEP('-')}, from {@code app/cbl/COBIL00C.cbl:257}. */
    private static final DateTimeFormatter TIMESTAMP_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    /** {@code WS-CUR-TIME-X08} with {@code TIMESEP(':')}, from {@code app/cbl/COBIL00C.cbl:259}. */
    private static final DateTimeFormatter TIMESTAMP_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /** {@code FILLER PIC X(01) VALUE ' '} at offset 11 of {@code WS-TIMESTAMP} in CSDAT01Y. */
    private static final String TIMESTAMP_DATE_TIME_SEPARATOR = " ";

    /** {@code FILLER PIC X(01) VALUE '.'} at offset 20 of {@code WS-TIMESTAMP} in CSDAT01Y. */
    private static final String TIMESTAMP_FRACTION_SEPARATOR = ".";

    /**
     * {@code WS-TIMESTAMP-TM-MS6 PIC 9(06)} at offsets 21 to 26. {@code app/cbl/COBIL00C.cbl:266} moves
     * {@code ZEROS} into it, so the six-digit fraction of an online timestamp is <em>always</em> zeros.
     */
    private static final String TIMESTAMP_FRACTION_ZEROS = "000000";

    /** {@code WS-CURDATE-MM-DD-YY} from {@code app/cpy/CSDAT01Y.cpy} - a two-digit year, per {@code :330}. */
    private static final DateTimeFormatter HEADER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT);

    /** {@code WS-CURTIME-HH-MM-SS} from {@code app/cpy/CSDAT01Y.cpy}. */
    private static final DateTimeFormatter HEADER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /** The {@code ACTIDINI} field name reported by a validation failure raised against it. */
    private static final String FIELD_ACCOUNT_ID = "ACTIDINI";

    /** The {@code CONFIRMI} field name reported by a validation failure raised against it. */
    private static final String FIELD_CONFIRMATION = "CONFIRMI";

    /**
     * The {@code EIBAID} name reported by the unrecognised-key failure of {@code app/cbl/COBIL00C.cbl:138}.
     * Named for the exchange-interface block field the source evaluates, because the fault is in which key
     * ended the turn rather than in any member of the map.
     */
    private static final String FIELD_ATTENTION_KEY = "EIBAID";

    /** The logical record type reported by a not-found failure raised against {@code ACCTDAT}. */
    private static final String RECORD_TYPE_ACCOUNT = "ACCOUNT";

    /** The logical record type reported by a not-found failure raised against {@code CXACAIX}. */
    private static final String RECORD_TYPE_CARD_XREF = "CARD-XREF";

    /** The logical record type reported by a not-found failure raised against {@code TRANSACT}. */
    private static final String RECORD_TYPE_TRANSACTION = "TRANSACTION";

    /** {@code WS-TRAN-AMT PIC +99999999.99} at {@code app/cbl/COBIL00C.cbl:55} - parity artefact P2. */
    private static final String UNREFERENCED_TRANSACTION_AMOUNT_MASK = "+00000000.00";

    /** {@code WS-TRAN-DATE PIC X(08) VALUE '00/00/00'} at {@code :58} - parity artefact P2. */
    private static final String UNREFERENCED_TRANSACTION_DATE = "00/00/00";

    /**
     * {@code ACCTDAT}, the CICS file the source reads for update at {@code app/cbl/COBIL00C.cbl:345} and
     * rewrites at {@code :379}. Supplies the authoritative balance the payment amount is derived from.
     */
    private final AccountRepository accountRepository;

    /**
     * {@code TRANSACT}, the CICS file the source browses at {@code app/cbl/COBIL00C.cbl:443}-{@code :479} to
     * find the maximum identifier and writes at {@code :512}.
     */
    private final TransactionRepository transactionRepository;

    /**
     * {@code CXACAIX}, the alternate-index path the source reads at {@code app/cbl/COBIL00C.cbl:410} to
     * resolve the account's card number.
     */
    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    /**
     * The shared translator that owns the {@code FILE STATUS IS: NNNN} literal and its four-character
     * expansion. Consumed, never reimplemented: neither the literal nor the rendering is redeclared here.
     */
    private final FileStatusMapper fileStatusMapper;

    /**
     * The time source behind {@code EXEC CICS ASKTIME} at {@code app/cbl/COBIL00C.cbl:251} and
     * {@code FUNCTION CURRENT-DATE} at {@code :321}.
     *
     * <p>Injected rather than read from the system clock so the twenty-six-character transaction timestamp is
     * deterministic under test, as Rule 1 Clause C requires of anything that would otherwise be an
     * environment-specific assumption. No no-argument {@code now()} is called anywhere in this class.</p>
     */
    private final Clock clock;

    /**
     * Wires the three datasets this program opens, the shared I/O status translator and the time source.
     *
     * <p>Constructor injection is the only injection form used: there is no field {@code @Autowired}, no
     * setter injection and no service locator, so the bean is fully initialised and immutable the moment it
     * is constructed. The three repositories correspond exactly to the three CICS files named in
     * {@code app/cbl/COBIL00C.cbl:40}-{@code :42} and to nothing else.</p>
     *
     * @param accountRepository            the {@code ACCTDAT} dataset; supplies the read-for-update of
     *                                     {@code READ-ACCTDAT-FILE} and the rewrite of
     *                                     {@code UPDATE-ACCTDAT-FILE}
     * @param transactionRepository        the {@code TRANSACT} dataset; supplies the descending browse that
     *                                     generates the identifier and the insert that records the payment
     * @param cardCrossReferenceRepository the {@code CXACAIX} alternate-index path; supplies the card number
     *                                     that {@code app/cbl/COBIL00C.cbl:225} moves into the transaction
     * @param fileStatusMapper             the shared translator that owns the {@code FILE STATUS IS: NNNN}
     *                                     diagnostic literal and its four-character status rendering; this
     *                                     class calls it and never reimplements it
     * @param clock                        the injected time source; every reading goes through it so that
     *                                     the twenty-six-character timestamp is deterministic and testable
     */
    public BillPaymentService(final AccountRepository accountRepository,
                             final TransactionRepository transactionRepository,
                             final CardCrossReferenceRepository cardCrossReferenceRepository,
                             final FileStatusMapper fileStatusMapper,
                             final Clock clock) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.cardCrossReferenceRepository = cardCrossReferenceRepository;
        this.fileStatusMapper = fileStatusMapper;
        this.clock = clock;
    }

    /**
     * Runs one pass of CICS transaction {@code CB00} exactly as {@code MAIN-PARA} runs it, and reports
     * everything the pass produced.
     *
     * <p>This is the screen-parity entry point. It mirrors a single pseudo-conversational turn: the caller
     * supplies the bound map, the attention identifier and whether this is a first display or a re-entry,
     * and the method returns the screen that was last sent together with the outcome, the navigation hint
     * and any retained typed failure. <strong>It does not throw for a business or an I/O outcome.</strong>
     * The source does not abandon its flow on a failed read - it sets an error flag, sends the screen and
     * continues - so throwing would change control flow. Failures are retained on the result instead, and
     * {@link #payBill} is the variant that rethrows.</p>
     *
     * <p>The two exceptions to that rule are deliberate and documented in section 7 of the class
     * documentation: deviation D1 raises {@code FatalProcessingException} where the source would suffer an
     * undefined-behaviour data exception, and deviation D3 raises it where the source would silently lose a
     * transaction amount's high-order digit.</p>
     *
     * <p><strong>Side effects.</strong> On the confirmed-payment path exactly two rows change: one
     * transaction row is inserted and one account row is updated to a zero balance. Both happen inside this
     * method's single transaction, so they commit together or not at all. Every other path is read-only.</p>
     *
     * @param request                       the bound symbolic map, whose ten members are the ten input
     *                                       fields of {@code app/cpy-bms/COBIL00.CPY}. It is read only when
     *                                       {@code entryMode} is {@link EntryMode#REENTER}, because
     *                                       {@code app/cbl/COBIL00C.cbl:124} performs
     *                                       {@code RECEIVE-BILLPAY-SCREEN} on that path alone. May be
     *                                       {@code null} on a first display
     * @param attentionIdentifier           the {@code EIBAID} analogue evaluated at
     *                                       {@code app/cbl/COBIL00C.cbl:125}. {@code ENTER}, {@code PF3} and
     *                                       {@code PF4} are recognised, case-insensitively under
     *                                       {@code Locale.ROOT}; every other value, {@code null} included,
     *                                       takes the {@code WHEN OTHER} arm and yields the invalid-key
     *                                       message. Ignored unless {@code entryMode} is
     *                                       {@link EntryMode#REENTER}
     * @param entryMode                     the {@code CDEMO-PGM-CONTEXT} analogue. {@code null} is treated
     *                                       as {@link EntryMode#ENTER}, matching
     *                                       {@code IF NOT CDEMO-PGM-REENTER} at
     *                                       {@code app/cbl/COBIL00C.cbl:112}
     * @param selectedTransactionIdentifier the {@code CDEMO-CB00-TRN-SELECTED} deep link of
     *                                       {@code app/cbl/COBIL00C.cbl:116}-{@code :121}. When present on a
     *                                       first display it is truncated to eleven characters and processed
     *                                       immediately; see parity artefact P5
     * @return the outcome of the pass, never {@code null}
     * @throws FatalProcessingException on deviation D1 or D3 only; see section 7 of the class documentation
     */
    @Transactional(rollbackFor = Exception.class)
    public BillPaymentResult processRequest(final BillPaymentRequest request,
                                           final String attentionIdentifier,
                                           final EntryMode entryMode,
                                           final String selectedTransactionIdentifier) {
        final PaymentContext context =
                new PaymentContext(request, attentionIdentifier, entryMode, selectedTransactionIdentifier);
        mainPara(context);
        return context.toResult();
    }

    /**
     * Settles an account balance in full and returns the screen the source would have sent, throwing the
     * retained typed failure instead of reporting it.
     *
     * <p>This is the convenience entry point for a caller that wants an exception-based contract rather than
     * the outcome-based one of {@link #processRequest}. It synthesises a re-entry pass carrying the
     * {@code ENTER} attention key, which is the only combination that reaches {@code PROCESS-ENTER-KEY} with
     * a bound map, and it consequently exercises the identical flow: the same guards, the same confirmation
     * gate, the same write sequence and the same single transaction.</p>
     *
     * <p><strong>The payment amount is not a parameter and cannot be one.</strong>
     * {@code app/cbl/COBIL00C.cbl:224} derives it from {@code ACCT-CURR-BAL}, so the settlement is always
     * the entire balance. A caller cannot request a partial payment and cannot influence the amount.</p>
     *
     * @param accountIdentifier the eleven-character {@code ACTIDINI} value. {@code null} or blank takes the
     *                          empty-identifier guard of {@code app/cbl/COBIL00C.cbl:159} and therefore
     *                          raises a validation failure rather than reading anything
     * @param confirmation      the one-character {@code CONFIRMI} value. {@code Y} or {@code y} commits;
     *                          {@code N} or {@code n} declines; {@code null} or blank prompts; anything else
     *                          is invalid. All six source branches remain distinguishable
     * @return the screen the pass sent last, never {@code null}
     * @throws ValidationException       when the identifier is empty, the confirmation is invalid, the
     *                                  confirmation is declined, the confirmation is still required, or the
     *                                  balance is at or below zero
     * @throws RecordNotFoundException  when the account, the cross-reference or the browse target is absent
     * @throws DuplicateRecordException when the generated identifier collides, which the retained
     *                                  generation race of parity artefact P7 permits
     * @throws CardDemoException        for any other retained I/O failure
     */
    @Transactional(rollbackFor = Exception.class)
    public BillPaymentScreen payBill(final String accountIdentifier, final String confirmation) {
        final BillPaymentRequest request = new BillPaymentRequest(TRANSACTION_ID, TITLE_LINE_01, null,
                PROGRAM_NAME, TITLE_LINE_02, null, accountIdentifier, null, confirmation, null);
        final PaymentContext context =
                new PaymentContext(request, AidKey.ENTER.name(), EntryMode.REENTER, null);
        mainPara(context);
        if (context.pendingFailure != null) {
            throw context.pendingFailure;
        }
        if (context.lastSentScreen == null) {
            throw new FatalProcessingException(ABEND_CODE_DATA_EXCEPTION, PROGRAM_NAME,
                    "NO SCREEN WAS SENT DURING THE PASS",
                    "Bill payment completed no send; the outcome is " + context.outcome + ".");
        }
        return context.lastSentScreen;
    }

    /* ---------------------------------------------------------------------------------------------------
     * The sixteen PROCEDURE DIVISION paragraphs of app/cbl/COBIL00C.cbl, in source order, one to one.
     * ------------------------------------------------------------------------------------------------- */

    /**
     * {@code MAIN-PARA.} at {@code app/cbl/COBIL00C.cbl:99}-{@code :149} - entry orchestration.
     *
     * <p>Resets the flags, decides between a first display and a re-entry, and dispatches the attention key.
     * The statement sequence is preserved exactly:</p>
     *
     * <ol>
     *   <li>{@code :101} {@code SET ERR-FLG-OFF TO TRUE}.</li>
     *   <li>{@code :102} {@code SET USR-MODIFIED-NO TO TRUE} - <strong>parity artefact P1, an intentional
     *       no-op.</strong> {@code WS-USR-MODIFIED} and its two condition names at {@code :48}-{@code :50}
     *       are written here and tested nowhere in the program's 572 lines. The assignment is reproduced
     *       because deleting it would break the statement-level correspondence that
     *       {@code TRACEABILITY_MATRIX.md} proves; it is tracked in {@code DECISION_LOG.md} and is
     *       classified Low.</li>
     *   <li>{@code :104}-{@code :105} blank {@code WS-MESSAGE} and the screen message field.</li>
     *   <li>{@code :107} the no-context test. {@code EIBCALEN = 0} means the program was reached with no
     *       communication area, so there is no caller identity and nothing to work with; {@code :108} points
     *       navigation at the sign-on program and {@code :109} transfers. There is no {@code XCTL} here, so
     *       the pass yields {@link ResponseKind#TRANSFER} with a navigation hint and sends no screen.</li>
     *   <li>{@code :111} copies the communication area. It has no Java counterpart: the pseudo-conversational
     *       carrier is replaced by the request itself plus the entry mode, and nothing is stored between
     *       requests.</li>
     *   <li>{@code :112} first display versus re-entry. On a first display {@code :113} flips the flag,
     *       {@code :114} wipes the output area to low values and {@code :115} parks the cursor on the
     *       account field; then {@code :116}-{@code :121} honours the deep link if one was supplied and
     *       {@code :122} sends. On a re-entry {@code :124} binds the map and {@code :125} dispatches.</li>
     *   <li>{@code :146}-{@code :149} {@code EXEC CICS RETURN TRANSID(WS-TRANID)} - the pseudo-conversational
     *       hand-back, recorded as result metadata.</li>
     * </ol>
     *
     * <p><strong>Parity artefact P5, the deep-link truncation, lives at {@code :116}-{@code :121}.</strong>
     * {@code CDEMO-CB00-TRN-SELECTED} is {@code PIC X(16)} at {@code :72} and {@code ACTIDINI} is
     * {@code PIC X(11)}, so the move keeps only the leading eleven characters - and it puts a transaction
     * identifier into an account identifier field. {@code PROCESS-ENTER-KEY} then runs immediately, before
     * any screen has been sent, which is why a deep-linked pass can send twice. Classified Medium and
     * reproduced exactly; it is not repaired.</p>
     *
     * <p><strong>Parity artefact P9, the attention-key-three originating-program arm.</strong> The
     * {@code ELSE} at {@code :131}-{@code :133} copies {@code CDEMO-FROM-PROGRAM} into the transfer target,
     * and is reached only when that communication-area field is populated. A stateless invocation carries no
     * communication area, so the field is blank and the {@code :130} arm is the one that runs - exactly as it
     * would for a CICS caller that transferred in with a zero-filled area. Both arms are implemented so the
     * resolution logic is complete and provable; classified Low and tracked in
     * {@code DECISION_LOG.md}.</p>
     *
     * @param context the per-invocation working storage, mutated in place
     */
    private void mainPara(final PaymentContext context) {
        context.errFlagOn = false;
        context.userModified = PaymentContext.USR_MODIFIED_NO;
        context.wsMessage = "";
        context.screen.errorMessage = "";

        if (context.entryMode == EntryMode.NO_CONTEXT) {
            context.toProgram = SIGN_ON_PROGRAM;
            returnToPrevScreen(context);
        } else {
            if (context.entryMode != EntryMode.REENTER) {
                context.nextEntryMode = EntryMode.REENTER;
                context.screen.moveLowValues();
                context.screen.cursor = CursorField.ACCOUNT_ID;
                if (!isBlankOrLowValues(context.selectedTransactionIdentifier)) {
                    context.screen.accountId =
                            truncateLeft(context.selectedTransactionIdentifier, ACCOUNT_ID_FIELD_WIDTH);
                    context.deepLinkApplied = true;
                    processEnterKey(context);
                }
                sendBillpayScreen(context);
                markSuccess(context, PaymentOutcome.PROMPT);
            } else {
                receiveBillpayScreen(context);
                context.aidKey = classifyAttentionKey(context.attentionIdentifier);
                switch (context.aidKey) {
                    case ENTER -> processEnterKey(context);
                    case PF3 -> {
                        if (isBlankOrLowValues(context.fromProgram)) {
                            context.toProgram = MAIN_MENU_PROGRAM;
                        } else {
                            context.toProgram = context.fromProgram;
                        }
                        returnToPrevScreen(context);
                    }
                    case PF4 -> {
                        clearCurrentScreen(context);
                        markSuccess(context, PaymentOutcome.SCREEN_CLEARED);
                    }
                    case OTHER -> {
                        context.errFlagOn = true;
                        context.wsMessage = MSG_INVALID_KEY;
                        context.messageKind = MessageKind.ERROR;
                        retainFailure(context, PaymentOutcome.INVALID_KEY,
                                new ValidationException(MSG_INVALID_KEY.strip(), FIELD_ATTENTION_KEY,
                                        ValidationException.FailureKind.INVALID));
                        sendBillpayScreen(context);
                    }
                }
            }
        }

        context.returnTransactionId = TRANSACTION_ID;
    }

    /**
     * {@code PROCESS-ENTER-KEY.} at {@code app/cbl/COBIL00C.cbl:154}-{@code :244} - the whole business flow.
     *
     * <p>Four sequential guard blocks, each gated on the error flag, followed by the write sequence. Their
     * boundaries are load-bearing and none may be merged, hoisted or short-circuited.</p>
     *
     * <ol>
     *   <li><strong>{@code :156}</strong> initialises the confirmation flag <em>pessimistically</em> to
     *       {@code CONF-PAY-NO}, so an unrecognised path can never fall through into a payment.</li>
     *   <li><strong>{@code :158}-{@code :167}, the empty-identifier guard.</strong> An account field that is
     *       blank or low values yields the literal {@code Acct ID can NOT be empty...} with the cursor on the
     *       account field. This guard runs <em>before</em> the confirmation gate. The {@code WHEN OTHER
     *       CONTINUE} arm at {@code :165}-{@code :166} is a deliberate no-statement arm and is represented as
     *       the absence of an {@code else}.</li>
     *   <li><strong>{@code :169}-{@code :195}, the identifier move and the confirmation gate.</strong> One
     *       {@code MOVE} at {@code :170}-{@code :171} populates <em>both</em> the account key and the
     *       cross-reference key from the one validated value. The gate at {@code :173}-{@code :191} has six
     *       arms; see {@link #classifyConfirmation}. The balance capture and echo at {@code :193}-{@code :194}
     *       sit <em>inside</em> this block, after the gate, so they run on every arm - including the invalid
     *       arm, which set the error flag at {@code :186} but did not prevent the enclosing block from having
     *       been entered at {@code :169}. Hoisting them out, or guarding them with a fresh error test, is
     *       classified Medium and is not done.</li>
     *   <li><strong>{@code :197}-{@code :206}, the at-or-below-zero rejection.</strong> The test is
     *       {@code ACCT-CURR-BAL &lt;= ZEROS} conjoined with the account field not being blank. Both halves
     *       are reproduced: the balance half because a zero balance is rejected, and the identifier half even
     *       though {@code :159} already rejected a blank identifier. Simplifying either is classified
     *       High.</li>
     *   <li><strong>{@code :208}-{@code :244}, the write sequence.</strong> Eleven ordered steps on the
     *       confirmed arm, a prompt on the unconfirmed arm, and a send at {@code :242} that runs on
     *       <em>both</em>.</li>
     * </ol>
     *
     * <p>The confirmed arm's order is exactly: read the cross-reference at {@code :211} - it is first because
     * it supplies the card number that {@code :225} needs; move high values into the browse key at
     * {@code :212}; begin, read previous, end the browse at {@code :213}-{@code :215}; move the retrieved
     * identifier to the numeric work field at {@code :216} and add one at {@code :217}; clear the record area
     * at {@code :218}, which is why the clear comes <em>after</em> the browse has been consumed; populate the
     * fields at {@code :219}-{@code :229}; generate one timestamp at {@code :230} and move it into
     * <em>both</em> timestamp fields at {@code :231}-{@code :232}; insert the transaction at {@code :233};
     * subtract the amount from the balance at {@code :234}; rewrite the account at {@code :235}.</p>
     *
     * <p><strong>Parity artefact P6: there is no error gate between those steps.</strong> A failed
     * cross-reference read or a failed browse sets the error flag and sends, but control still falls through
     * into building and writing the transaction, because {@code :210} tested the confirmation flag and
     * nothing re-tests the error flag until {@code :244} has already been passed. That is reproduced: no
     * early return is inserted. Deviation D1 is the one consequence that cannot be reproduced literally; see
     * {@link #moveTransactionIdToNumeric}.</p>
     *
     * <p>Both writes share the single transaction opened by the public entry point, so a failure at
     * {@code :235} rolls back the insert of {@code :233}. In CICS these were two independent commits, which
     * is deviation D2 - a mechanism substitution that additionally closes an orphaned-row hazard.</p>
     *
     * @param context the per-invocation working storage, mutated in place
     */
    private void processEnterKey(final PaymentContext context) {
        context.confPayYes = false;

        if (isBlankOrLowValues(context.screen.accountId)) {
            context.errFlagOn = true;
            context.wsMessage = MSG_ACCOUNT_ID_EMPTY;
            context.screen.cursor = CursorField.ACCOUNT_ID;
            context.messageKind = MessageKind.ERROR;
            retainFailure(context, PaymentOutcome.ACCOUNT_ID_EMPTY,
                    ValidationException.missingField(FIELD_ACCOUNT_ID, MSG_ACCOUNT_ID_EMPTY));
            sendBillpayScreen(context);
        }

        if (!context.errFlagOn) {
            final Long accountKey = moveAccountIdentifier(context.screen.accountId);
            context.acctId = accountKey;
            context.xrefAcctId = accountKey;

            context.confirmationBranch = classifyConfirmation(context.screen.confirmation);
            switch (context.confirmationBranch) {
                case YES_UPPER, YES_LOWER -> {
                    context.confPayYes = true;
                    readAcctdatFile(context);
                }
                case NO_UPPER, NO_LOWER -> {
                    clearCurrentScreen(context);
                    context.errFlagOn = true;
                    retainFailure(context, PaymentOutcome.CONFIRMATION_DECLINED,
                            new ValidationException("Bill payment was declined at the confirmation prompt.",
                                    FIELD_CONFIRMATION, ValidationException.FailureKind.INVALID));
                }
                case BLANK, LOW_VALUES -> readAcctdatFile(context);
                case INVALID -> {
                    context.errFlagOn = true;
                    context.wsMessage = MSG_CONFIRMATION_INVALID;
                    context.screen.cursor = CursorField.CONFIRMATION;
                    context.messageKind = MessageKind.ERROR;
                    retainFailure(context, PaymentOutcome.CONFIRMATION_INVALID,
                            ValidationException.invalidField(FIELD_CONFIRMATION, MSG_CONFIRMATION_INVALID));
                    sendBillpayScreen(context);
                }
            }

            context.wsCurrBal = formatBalanceEcho(context.acctCurrBal);
            context.screen.currentBalance = context.wsCurrBal;
        }

        if (!context.errFlagOn) {
            if (context.acctCurrBal.compareTo(BigDecimal.ZERO) <= 0
                    && !isBlankOrLowValues(context.screen.accountId)) {
                context.errFlagOn = true;
                context.wsMessage = MSG_NOTHING_TO_PAY;
                context.screen.cursor = CursorField.ACCOUNT_ID;
                context.messageKind = MessageKind.ERROR;
                retainFailure(context, PaymentOutcome.NOTHING_TO_PAY,
                        ValidationException.invalidField(FIELD_ACCOUNT_ID, MSG_NOTHING_TO_PAY));
                sendBillpayScreen(context);
            }
        }

        if (!context.errFlagOn) {
            if (context.confPayYes) {
                readCxacaixFile(context);
                context.tranRecord.transactionId = HIGH_VALUES_KEY;
                startbrTransactFile(context);
                readprevTransactFile(context);
                endbrTransactFile(context);
                long wsTranIdNum = moveTransactionIdToNumeric(context);
                wsTranIdNum = wsTranIdNum + 1L;
                context.tranRecord.initialize();
                context.tranRecord.transactionId = formatTransactionIdentifier(wsTranIdNum);
                context.tranRecord.typeCode = TRANSACTION_TYPE_CODE_BILL_PAYMENT;
                context.tranRecord.categoryCode = TRANSACTION_CATEGORY_CODE_BILL_PAYMENT;
                context.tranRecord.source = TransactionSource.POS_TERMINAL.getFixedWidthValue();
                context.tranRecord.description =
                        padRight(TRANSACTION_DESCRIPTION_BILL_PAYMENT, TRANSACTION_DESCRIPTION_WIDTH);
                context.tranRecord.amount = moveBalanceToTransactionAmount(context.acctCurrBal);
                context.tranRecord.cardNumber = context.crossReference == null
                        ? spaces(CARD_NUMBER_WIDTH)
                        : padRight(context.crossReference.getCardNumber(), CARD_NUMBER_WIDTH);
                context.tranRecord.merchantId = MERCHANT_ID_BILL_PAYMENT;
                context.tranRecord.merchantName =
                        padRight(MERCHANT_NAME_BILL_PAYMENT, MERCHANT_NAME_WIDTH);
                context.tranRecord.merchantCity =
                        padRight(MERCHANT_CITY_NOT_APPLICABLE, MERCHANT_CITY_WIDTH);
                context.tranRecord.merchantZip =
                        padRight(MERCHANT_ZIP_NOT_APPLICABLE, MERCHANT_ZIP_WIDTH);
                getCurrentTimestamp(context);
                context.tranRecord.originatingTimestamp = context.wsTimestamp;
                context.tranRecord.processingTimestamp = context.wsTimestamp;
                writeTransactFile(context);
                context.acctCurrBal = context.acctCurrBal.subtract(context.tranRecord.amount);
                if (context.accountRecord != null) {
                    context.accountRecord.setCurrentBalance(context.acctCurrBal);
                }
                updateAcctdatFile(context);
                buildReceipt(context);
            } else {
                context.wsMessage = MSG_CONFIRMATION_REQUIRED;
                context.screen.cursor = CursorField.CONFIRMATION;
                context.messageKind = MessageKind.INFORMATIONAL;
                retainFailure(context, PaymentOutcome.CONFIRMATION_REQUIRED,
                        ValidationException.missingField(FIELD_CONFIRMATION, MSG_CONFIRMATION_REQUIRED));
            }

            sendBillpayScreen(context);
        }
    }

    /**
     * {@code GET-CURRENT-TIMESTAMP.} at {@code app/cbl/COBIL00C.cbl:249}-{@code :267} - the online
     * twenty-six-character timestamp.
     *
     * <p>{@code :251}-{@code :253} reads the absolute time and {@code :255}-{@code :261} formats it with
     * {@code DATESEP('-')} into a ten-character date and {@code TIMESEP(':')} into an eight-character time.
     * {@code :263} initialises the target, which in COBOL leaves {@code FILLER} items untouched so their
     * {@code VALUE} clauses survive, and {@code :264}-{@code :266} then place the date at offset 1, the time
     * at offset 12 and <strong>zeros</strong> at offsets 21 to 26.</p>
     *
     * <p>The resulting geometry, derived field by field from {@code WS-TIMESTAMP} in
     * {@code app/cpy/CSDAT01Y.cpy}, is: year 1 to 4, {@code '-'} at 5, month 6 to 7, {@code '-'} at 8, day 9
     * to 10, {@code ' '} at 11, hour 12 to 13, {@code ':'} at 14, minute 15 to 16, {@code ':'} at 17, second
     * 18 to 19, {@code '.'} at 20, and a six-digit fraction at 21 to 26. <strong>Twenty-six characters
     * exactly, of the form {@code yyyy-MM-dd HH:mm:ss.000000}, with the fraction always zeros.</strong></p>
     *
     * <p><strong>This is the online form and it is not the batch form.</strong>
     * {@code app/cbl/CBTRN02C.cbl} produces {@code yyyy-MM-dd-HH.mm.ss.SS0000} - hyphen separator, dotted
     * time, two significant hundredths - and conflating the two producers is classified Blocker. The value is
     * carried as text into {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS}, both {@code PIC X(26)}, and is
     * never converted to a temporal type: three mutually incompatible producers write those columns, and the
     * seeded fixture's processing timestamp is twenty-six blanks, which no format parses.</p>
     *
     * <p>The reading goes through the injected clock, so the value is deterministic under test and depends on
     * no ambient time zone. Formatting is pinned to {@code Locale.ROOT}, because a locale with non-Arabic
     * digits or a different numbering system would otherwise corrupt a fixed-width field.</p>
     *
     * @param context the per-invocation working storage; {@code wsTimestamp} is assigned
     */
    private void getCurrentTimestamp(final PaymentContext context) {
        final LocalDateTime absoluteTime = LocalDateTime.now(this.clock);
        final String currentDate = TIMESTAMP_DATE_FORMAT.format(absoluteTime);
        final String currentTime = TIMESTAMP_TIME_FORMAT.format(absoluteTime);
        context.wsTimestamp = padRight(currentDate
                + TIMESTAMP_DATE_TIME_SEPARATOR
                + currentTime
                + TIMESTAMP_FRACTION_SEPARATOR
                + TIMESTAMP_FRACTION_ZEROS, TIMESTAMP_WIDTH);
    }

    /**
     * {@code RETURN-TO-PREV-SCREEN.} at {@code app/cbl/COBIL00C.cbl:273}-{@code :284} - navigation target
     * resolution.
     *
     * <p>{@code :275}-{@code :277} defaults a blank or low-values target to the sign-on program;
     * {@code :278}-{@code :280} stamps this transaction and this program as the origin and zeroes the
     * program-context flag, which sets it to {@code CDEMO-PGM-ENTER} so that the receiving program treats its
     * next pass as a first display; {@code :281}-{@code :284} transfers control.</p>
     *
     * <p><strong>There is no {@code XCTL} in the target.</strong> Routing is URL-based, so the transfer
     * collapses into a returned {@link Navigation} hint and the pass is reported as
     * {@link ResponseKind#TRANSFER} with no screen sent. {@code CDEMO-FROM-TRANID},
     * {@code CDEMO-FROM-PROGRAM}, {@code CDEMO-TO-PROGRAM} and {@code CDEMO-PGM-CONTEXT} have no persisted
     * counterpart and no server-side navigation state is retained; neither do {@code CDEMO-LAST-MAP} and
     * {@code CDEMO-LAST-MAPSET}, which this program never writes in any case.</p>
     *
     * <p>The resolution <em>logic</em> is nonetheless evaluated in full, because it is the observable part:
     * the caller learns which screen the legacy would have reached.</p>
     *
     * @param context the per-invocation working storage; the navigation fields and the response kind are set
     */
    private void returnToPrevScreen(final PaymentContext context) {
        if (isBlankOrLowValues(context.toProgram)) {
            context.toProgram = SIGN_ON_PROGRAM;
        }
        context.fromTransactionId = TRANSACTION_ID;
        context.fromProgram = PROGRAM_NAME;
        context.programContext = PaymentContext.PGM_CONTEXT_ENTER;
        context.responseKind = ResponseKind.TRANSFER;
        context.nextEntryMode = EntryMode.ENTER;
        markSuccess(context, context.entryMode == EntryMode.NO_CONTEXT
                ? PaymentOutcome.NO_CONTEXT
                : PaymentOutcome.NAVIGATED_BACK);
        LOG.debug("{} transferring to {} from {}", TRANSACTION_ID, context.toProgram, PROGRAM_NAME);
    }

    /**
     * {@code SEND-BILLPAY-SCREEN.} at {@code app/cbl/COBIL00C.cbl:289}-{@code :301} - response assembly.
     *
     * <p>{@code :291} repopulates the header on <em>every</em> send, {@code :293} moves the working-storage
     * message into the screen's message field, and {@code :295}-{@code :301} sends the map with {@code ERASE}
     * and {@code CURSOR}. No BMS, no 3270 datastream and no markup is produced here: the map is a field
     * contract and the send becomes an immutable snapshot of the buffer.</p>
     *
     * <p><strong>The snapshot is what makes multiple sends per pass behave correctly.</strong> A pass can
     * send more than once - a deep-linked first display sends from inside {@code PROCESS-ENTER-KEY} at
     * {@code :120} and again at {@code :122}, and a successful payment sends at {@code :532} and again at
     * {@code :242}. Because every send carries {@code ERASE}, each overwrites the terminal, so the last send
     * wins. Snapshotting also means a buffer mutation that happens <em>after</em> a send cannot leak into the
     * screen that was already sent, which is exactly the behaviour of {@code :193}-{@code :194} writing the
     * balance echo after the declined and invalid arms have already sent.</p>
     *
     * <p>{@code WS-MESSAGE} is {@code PIC X(80)} and the receiving field {@code ERRMSGO} is
     * {@code PIC X(78)}, so the move at {@code :293} loses the final two characters. That truncation is
     * reproduced. The receiving field's trailing blank padding is not materialised in the projection because
     * it carries no information and the width is documented here; a literal's <em>own</em> declared padding,
     * such as the nine trailing blanks of {@link #MSG_INVALID_KEY}, is reproduced exactly.</p>
     *
     * @param context the per-invocation working storage; the snapshot and the send counter are updated
     */
    private void sendBillpayScreen(final PaymentContext context) {
        populateHeaderInfo(context);
        context.screen.errorMessage = truncateLeft(context.wsMessage, MESSAGE_FIELD_WIDTH);
        context.screen.messageKind = context.messageKind;
        context.lastSentScreen = context.screen.toScreen();
        context.sendCount = context.sendCount + 1;
        context.responseKind = ResponseKind.MAP;
        LOG.debug("{} sent map {} mapset {} send={} cursor={}",
                TRANSACTION_ID, MAP_NAME, MAPSET_NAME, context.sendCount, context.screen.cursor);
    }

    /**
     * {@code RECEIVE-BILLPAY-SCREEN.} at {@code app/cbl/COBIL00C.cbl:306}-{@code :314} - request binding.
     *
     * <p>{@code :308}-{@code :314} receives the map into the input area, capturing the response and reason
     * codes. <strong>Parity artefact P10: the program never evaluates them.</strong> There is no
     * {@code EVALUATE WS-RESP-CD} after this receive anywhere in the 572 lines, so a map that could not be
     * bound is silently accepted and the input area is left holding whatever it held. That absent guard is
     * preserved as absent: a {@code null} request leaves the buffer untouched rather than raising, and the
     * blank account field it presents then takes the empty-identifier guard of {@code :159} and yields
     * {@code Acct ID can NOT be empty...} - which is precisely the legacy outcome. Classified Low and tracked
     * in {@code DECISION_LOG.md}.</p>
     *
     * <p>Only the three input-bearing members are bound. The six header members and the message member of
     * {@code app/cpy-bms/COBIL00.CPY} are output fields that the map round-trips; {@code :291} overwrites
     * every one of them on the next send, so binding them would be inert. The displayed balance is
     * deliberately <em>not</em> trusted: {@code :193} re-reads the authoritative value from the account
     * record, and honouring a submitted balance would let a caller choose its own payment amount.</p>
     *
     * @param context the per-invocation working storage; the screen buffer and the response codes are set
     */
    private void receiveBillpayScreen(final PaymentContext context) {
        if (context.request != null) {
            context.screen.bindFrom(context.request);
        }
        recordResponse(context, CICS_RESP_NORMAL, IO_STATUS_SUCCESS);
        LOG.debug("{} received map {} mapset {}", TRANSACTION_ID, MAP_NAME, MAPSET_NAME);
    }

    /**
     * {@code POPULATE-HEADER-INFO.} at {@code app/cbl/COBIL00C.cbl:319}-{@code :338} - the six header fields.
     *
     * <p>{@code :321} reads the current date; {@code :323}-{@code :324} move the two application titles;
     * {@code :325}-{@code :326} move the transaction and program identifiers; {@code :328}-{@code :332}
     * assemble the date as {@code MM/DD/YY}, taking the two-digit year from {@code WS-CURDATE-YEAR(3:2)}; and
     * {@code :334}-{@code :338} assemble the time as {@code HH:MM:SS}.</p>
     *
     * <p>The titles are {@code CCDA-TITLE01} and {@code CCDA-TITLE02} from {@code app/cpy/COTTL01Y.cpy}, both
     * {@code PIC X(40)} and both carrying their declared leading and trailing padding, which is reproduced
     * byte for byte because the header fields are fixed-width.</p>
     *
     * <p><strong>These two formats are not the transaction timestamp format.</strong> The header carries an
     * eight-character date and an eight-character time; the transaction carries a twenty-six-character
     * composite. The formatters are separate constants and are never interchanged. Both read the injected
     * clock and both are pinned to {@code Locale.ROOT}.</p>
     *
     * @param context the per-invocation working storage; the six header members of the buffer are assigned
     */
    private void populateHeaderInfo(final PaymentContext context) {
        final LocalDateTime currentDateTime = LocalDateTime.now(this.clock);
        context.screen.title01 = TITLE_LINE_01;
        context.screen.title02 = TITLE_LINE_02;
        context.screen.transactionName = TRANSACTION_ID;
        context.screen.programName = PROGRAM_NAME;
        context.screen.currentDate = HEADER_DATE_FORMAT.format(currentDateTime);
        context.screen.currentTime = HEADER_TIME_FORMAT.format(currentDateTime);
    }

    /**
     * {@code READ-ACCTDAT-FILE.} at {@code app/cbl/COBIL00C.cbl:343}-{@code :372} - read the account for
     * update.
     *
     * <p>{@code :345}-{@code :354} is an {@code EXEC CICS READ} against {@code ACCTDAT} keyed on
     * {@code ACCT-ID} and carrying the <strong>{@code UPDATE}</strong> option, which acquires the record lock
     * that the later rewrite depends on. Its Java counterpart is the pessimistic-write finder on the account
     * repository, invoked inside the transaction opened by the public entry point.</p>
     *
     * <p>The three response arms are reproduced exactly. {@code :357}-{@code :358} continues without a
     * statement. {@code :359}-{@code :364} yields {@code Account ID NOT found...} with the cursor on the
     * account field - one of three independent sites emitting that literal, the others being {@code :392} and
     * {@code :425}. {@code :365}-{@code :371} logs the response and reason codes and yields
     * {@code Unable to lookup Account...}, also with the cursor on the account field.</p>
     *
     * <p>An account identifier that cannot form a key - non-numeric, over-long or otherwise unusable - takes
     * the not-found arm, because that is what the source produces: {@code :170} moves the text field into a
     * numeric key without validating it, and a key that matches no row reads as not found.</p>
     *
     * @param context the per-invocation working storage; on success the account record and the working
     *                balance are loaded, otherwise the error flag, message and cursor are set and the screen
     *                is sent
     */
    private void readAcctdatFile(final PaymentContext context) {
        final String ioStatus = execAcctdatReadUpdate(context);
        switch (context.wsRespCd) {
            case CICS_RESP_NORMAL -> {
                // :358 CONTINUE - the success arm carries no statement in the source and none is added.
            }
            case CICS_RESP_NOTFND -> {
                context.errFlagOn = true;
                context.wsMessage = MSG_ACCOUNT_NOT_FOUND;
                context.screen.cursor = CursorField.ACCOUNT_ID;
                context.messageKind = MessageKind.ERROR;
                retainFailure(context, PaymentOutcome.ACCOUNT_NOT_FOUND,
                        notFound(context, ioStatus, ACCTDAT_FILE, OPERATION_READ_UPDATE,
                                MSG_ACCOUNT_NOT_FOUND, RECORD_TYPE_ACCOUNT, context.screen.accountId));
                sendBillpayScreen(context);
            }
            default -> {
                context.errFlagOn = true;
                context.wsMessage = MSG_ACCOUNT_LOOKUP_FAILED;
                context.screen.cursor = CursorField.ACCOUNT_ID;
                context.messageKind = MessageKind.ERROR;
                retainFailure(context, PaymentOutcome.ACCOUNT_LOOKUP_FAILED,
                        accessFailure(context, ioStatus, ACCTDAT_FILE, OPERATION_READ_UPDATE,
                                MSG_ACCOUNT_LOOKUP_FAILED));
                sendBillpayScreen(context);
            }
        }
    }

    /**
     * {@code UPDATE-ACCTDAT-FILE.} at {@code app/cbl/COBIL00C.cbl:377}-{@code :403} - rewrite the account.
     *
     * <p>{@code :379}-{@code :385} is an {@code EXEC CICS REWRITE} of the record that
     * {@code READ-ACCTDAT-FILE} locked. It carries no {@code RIDFLD}, because the rewrite applies to the
     * record held under the read-for-update lock - which is why the read must have succeeded for this to be
     * reached, and why the two operations belong to one unit of work.</p>
     *
     * <p>The three arms are reproduced: continue at {@code :388}-{@code :389};
     * {@code Account ID NOT found...} at {@code :390}-{@code :395}; and
     * {@code Unable to Update Account...} - capital {@code U} on {@code Update} - at
     * {@code :396}-{@code :402}. All three place the cursor on the account field.</p>
     *
     * <p>The write is flushed immediately rather than deferred to commit, so a constraint or concurrency
     * failure surfaces here, at the source's own line, instead of escaping the paragraph and appearing at the
     * transaction boundary. That keeps the {@code WHEN OTHER} arm genuinely reachable.</p>
     *
     * @param context the per-invocation working storage; on failure the error flag, message and cursor are
     *                set and the screen is sent
     */
    private void updateAcctdatFile(final PaymentContext context) {
        final String ioStatus = execAcctdatRewrite(context);
        switch (context.wsRespCd) {
            case CICS_RESP_NORMAL -> {
                // :389 CONTINUE - the success arm carries no statement in the source and none is added.
            }
            case CICS_RESP_NOTFND -> {
                context.errFlagOn = true;
                context.wsMessage = MSG_ACCOUNT_NOT_FOUND;
                context.screen.cursor = CursorField.ACCOUNT_ID;
                context.messageKind = MessageKind.ERROR;
                retainFailure(context, PaymentOutcome.ACCOUNT_UPDATE_NOT_FOUND,
                        notFound(context, ioStatus, ACCTDAT_FILE, OPERATION_REWRITE,
                                MSG_ACCOUNT_NOT_FOUND, RECORD_TYPE_ACCOUNT, context.screen.accountId));
                sendBillpayScreen(context);
            }
            default -> {
                context.errFlagOn = true;
                context.wsMessage = MSG_ACCOUNT_UPDATE_FAILED;
                context.screen.cursor = CursorField.ACCOUNT_ID;
                context.messageKind = MessageKind.ERROR;
                retainFailure(context, PaymentOutcome.ACCOUNT_UPDATE_FAILED,
                        accessFailure(context, ioStatus, ACCTDAT_FILE, OPERATION_REWRITE,
                                MSG_ACCOUNT_UPDATE_FAILED));
                sendBillpayScreen(context);
            }
        }
    }

    /**
     * {@code READ-CXACAIX-FILE.} at {@code app/cbl/COBIL00C.cbl:408}-{@code :436} - read the cross-reference
     * by account.
     *
     * <p>{@code :410}-{@code :418} is a <em>plain</em> {@code EXEC CICS READ} - no {@code UPDATE} option -
     * against {@code CXACAIX}, which is the alternate-index path over the card cross-reference cluster keyed
     * on {@code XREF-ACCT-ID}. The alternate key is non-unique, so a plain read returns the first record in
     * alternate-key sequence; the Java counterpart is therefore the account-based finder ordered ascending by
     * card number, of which the first element is taken.</p>
     *
     * <p>This is the first step of the confirmed-payment sequence at {@code :211}, and it is first because it
     * supplies the {@code XREF-CARD-NUM} value that {@code :225} moves into the transaction record.</p>
     *
     * <p>The arms are: continue at {@code :421}-{@code :422}; {@code Account ID NOT found...} at
     * {@code :423}-{@code :428} - the third site emitting that literal; and
     * {@code Unable to lookup XREF AIX file...} at {@code :429}-{@code :435}. The retrieved card number is
     * personally identifiable and never appears in a log line, an exception message or a rendered
     * screen.</p>
     *
     * @param context the per-invocation working storage; on success the cross-reference record is loaded
     */
    private void readCxacaixFile(final PaymentContext context) {
        final String ioStatus = execCxacaixRead(context);
        switch (context.wsRespCd) {
            case CICS_RESP_NORMAL -> {
                // :422 CONTINUE - the success arm carries no statement in the source and none is added.
            }
            case CICS_RESP_NOTFND -> {
                context.errFlagOn = true;
                context.wsMessage = MSG_ACCOUNT_NOT_FOUND;
                context.screen.cursor = CursorField.ACCOUNT_ID;
                context.messageKind = MessageKind.ERROR;
                retainFailure(context, PaymentOutcome.CROSS_REFERENCE_NOT_FOUND,
                        notFound(context, ioStatus, CXACAIX_FILE, OPERATION_READ,
                                MSG_ACCOUNT_NOT_FOUND, RECORD_TYPE_CARD_XREF, context.screen.accountId));
                sendBillpayScreen(context);
            }
            default -> {
                context.errFlagOn = true;
                context.wsMessage = MSG_XREF_LOOKUP_FAILED;
                context.screen.cursor = CursorField.ACCOUNT_ID;
                context.messageKind = MessageKind.ERROR;
                retainFailure(context, PaymentOutcome.CROSS_REFERENCE_LOOKUP_FAILED,
                        accessFailure(context, ioStatus, CXACAIX_FILE, OPERATION_READ,
                                MSG_XREF_LOOKUP_FAILED));
                sendBillpayScreen(context);
            }
        }
    }

    /**
     * {@code STARTBR-TRANSACT-FILE.} at {@code app/cbl/COBIL00C.cbl:441}-{@code :467} - begin the descending
     * browse.
     *
     * <p>{@code :443}-{@code :449} positions a browse over {@code TRANSACT} using the record identification
     * field that {@code :212} has just filled with high values, which places the cursor past the highest
     * existing key so that the following read-previous returns the maximum. The Java counterpart opens the
     * browse state that {@link #readprevTransactFile} requires and {@link #endbrTransactFile} closes;
     * positioning against a table always succeeds, including against an empty one.</p>
     *
     * <p><strong>The {@code DFHRESP(NOTFND)} arm at {@code :454}-{@code :459} is implemented and
     * unreachable, deliberately.</strong> It emits {@code Transaction ID NOT found...} with the cursor on the
     * account field. The empty-table case must <em>not</em> arrive here: it has to reach the end-of-file arm
     * of {@code :487}-{@code :488}, because that arm is what zeroes the identifier and so yields a first
     * generated identifier of one. Routing an empty table to this arm instead would make a first payment
     * impossible. The arm is retained so the response taxonomy is complete and provable.</p>
     *
     * <p>The {@code WHEN OTHER} arm at {@code :460}-{@code :466} emits
     * {@code Unable to lookup Transaction...}, which is one of two sites carrying that literal; the other is
     * {@code :492}.</p>
     *
     * @param context the per-invocation working storage; the browse state is opened
     */
    private void startbrTransactFile(final PaymentContext context) {
        final String ioStatus = execTransactStartbr(context);
        switch (context.wsRespCd) {
            case CICS_RESP_NORMAL -> {
                // :453 CONTINUE - the success arm carries no statement in the source and none is added.
            }
            case CICS_RESP_NOTFND -> {
                context.errFlagOn = true;
                context.wsMessage = MSG_TRANSACTION_NOT_FOUND;
                context.screen.cursor = CursorField.ACCOUNT_ID;
                context.messageKind = MessageKind.ERROR;
                retainFailure(context, PaymentOutcome.TRANSACTION_BROWSE_NOT_FOUND,
                        notFound(context, ioStatus, TRANSACT_FILE, OPERATION_STARTBR,
                                MSG_TRANSACTION_NOT_FOUND, RECORD_TYPE_TRANSACTION, null));
                sendBillpayScreen(context);
            }
            default -> {
                context.errFlagOn = true;
                context.wsMessage = MSG_TRANSACTION_LOOKUP_FAILED;
                context.screen.cursor = CursorField.ACCOUNT_ID;
                context.messageKind = MessageKind.ERROR;
                retainFailure(context, PaymentOutcome.TRANSACTION_LOOKUP_FAILED,
                        accessFailure(context, ioStatus, TRANSACT_FILE, OPERATION_STARTBR,
                                MSG_TRANSACTION_LOOKUP_FAILED));
                sendBillpayScreen(context);
            }
        }
    }

    /**
     * {@code READPREV-TRANSACT-FILE.} at {@code app/cbl/COBIL00C.cbl:472}-{@code :496} - read the maximum
     * identifier, or zeros.
     *
     * <p>{@code :474}-{@code :482} reads the previous record into {@code TRAN-RECORD}. It reads the
     * <em>whole</em> record, not just the key, which is exactly why {@code :218} must clear the record area
     * afterwards and why that clear cannot be moved earlier. The Java counterpart is the top-one
     * descending-ordered finder on the transaction repository.</p>
     *
     * <p>Three arms, and the middle one is the important one:</p>
     *
     * <ul>
     *   <li>{@code :485}-{@code :486} continue.</li>
     *   <li><strong>{@code :487}-{@code :488} {@code WHEN DFHRESP(ENDFILE) MOVE ZEROS TO TRAN-ID}.</strong>
     *       An empty table zeroes the identifier and - critically - <em>does not</em> set the error flag, so
     *       the flow continues and {@code :217} adds one. <strong>The first payment on an empty transaction
     *       table therefore receives the identifier {@code 0000000000000001}.</strong></li>
     *   <li>{@code :489}-{@code :495} {@code Unable to lookup Transaction...} with the cursor on the account
     *       field - the second of the two sites carrying that literal.</li>
     * </ul>
     *
     * @param context the per-invocation working storage; the transaction record area is loaded or zeroed
     */
    private void readprevTransactFile(final PaymentContext context) {
        final String ioStatus = execTransactReadprev(context);
        switch (context.wsRespCd) {
            case CICS_RESP_NORMAL -> {
                // :486 CONTINUE - the success arm carries no statement in the source and none is added.
            }
            case CICS_RESP_ENDFILE -> context.tranRecord.transactionId = TRANSACTION_ID_ZEROS;
            default -> {
                context.errFlagOn = true;
                context.wsMessage = MSG_TRANSACTION_LOOKUP_FAILED;
                context.screen.cursor = CursorField.ACCOUNT_ID;
                context.messageKind = MessageKind.ERROR;
                retainFailure(context, PaymentOutcome.TRANSACTION_LOOKUP_FAILED,
                        accessFailure(context, ioStatus, TRANSACT_FILE, OPERATION_READPREV,
                                MSG_TRANSACTION_LOOKUP_FAILED));
                sendBillpayScreen(context);
            }
        }
    }

    /**
     * {@code ENDBR-TRANSACT-FILE.} at {@code app/cbl/COBIL00C.cbl:501}-{@code :505} - end the browse.
     *
     * <p><strong>Parity artefact P3: this paragraph has no error handling whatsoever.</strong> The source is a
     * bare {@code EXEC CICS ENDBR DATASET(WS-TRANSACT-FILE) END-EXEC.} with no {@code RESP}, no
     * {@code RESP2} and no {@code EVALUATE} - the only I/O paragraph in the program that captures no response
     * at all. That is an <em>absent guard</em>, and absent guards are preserved as absent: <strong>no
     * {@code try}, no status check, no exception and no logging of a failure is added here.</strong> Adding
     * any would be a behaviour change, because a failing {@code ENDBR} in the source is silently ignored and
     * the flow continues into identifier generation.</p>
     *
     * <p>Classified Low and tracked in {@code DECISION_LOG.md}. The single statement closes the browse state
     * that {@link #startbrTransactFile} opened, which is the whole of the operation's observable effect.</p>
     *
     * @param context the per-invocation working storage; the browse state is closed
     */
    private void endbrTransactFile(final PaymentContext context) {
        context.browseOpen = false;
        LOG.debug("{} {} on {}", TRANSACTION_ID, OPERATION_ENDBR, TRANSACT_FILE);
    }

    /**
     * {@code WRITE-TRANSACT-FILE.} at {@code app/cbl/COBIL00C.cbl:510}-{@code :547} - insert the transaction.
     *
     * <p>{@code :512}-{@code :520} writes the assembled three-hundred-and-fifty-byte record to
     * {@code TRANSACT} keyed on the generated identifier. The write is flushed immediately, so a primary-key
     * collision surfaces at this line rather than at the transaction boundary - which is what keeps the
     * duplicate arm reachable and what makes the retained generation race of parity artefact P7 observable
     * exactly where the source observes it.</p>
     *
     * <p>Three arms:</p>
     *
     * <ul>
     *   <li><strong>{@code :523}-{@code :532}, success.</strong> The order is load-bearing: {@code :524}
     *       clears every input field and the message, {@code :525} blanks the message again, {@code :526}
     *       marks the message informational, and only then does {@code :527}-{@code :531} compose the
     *       confirmation. <strong>The successful response therefore carries a blank account field and a blank
     *       balance echo</strong>, because the clear ran before the compose.</li>
     *   <li><strong>{@code :533}-{@code :539}, duplicate.</strong> {@code WHEN DFHRESP(DUPKEY)} and
     *       {@code WHEN DFHRESP(DUPREC)} are two separate arms sharing one body, and both are retained as
     *       distinct labels. The relational translation produces the duplicate-record condition; the
     *       duplicate-key label is preserved so the taxonomy is complete. Both emit
     *       {@code Tran ID already exist...} with the cursor on the account field.</li>
     *   <li>{@code :540}-{@code :546}, any other response: {@code Unable to Add Bill pay Transaction...}
     *       with the cursor on the account field.</li>
     * </ul>
     *
     * <p><strong>The success message contains a double space.</strong> The {@code STRING} concatenates
     * {@code 'Payment successful. '}, which ends with a blank, then {@code ' Your Transaction ID is '}, which
     * begins with one, then the identifier {@code DELIMITED BY SPACE}, then a full stop. A sixteen-digit
     * zero-padded identifier contains no blank, so the whole of it is emitted. The composed form is therefore
     * {@code Payment successful.  Your Transaction ID is 0000000000000001.} and the double space is
     * reproduced.</p>
     *
     * @param context the per-invocation working storage; on success the fields are cleared and the
     *                confirmation composed, otherwise the error flag, message and cursor are set
     */
    private void writeTransactFile(final PaymentContext context) {
        final String ioStatus = execTransactWrite(context);
        switch (context.wsRespCd) {
            case CICS_RESP_NORMAL -> {
                initializeAllFields(context);
                context.wsMessage = "";
                context.messageKind = MessageKind.INFORMATIONAL;
                context.wsMessage = MSG_SUCCESS_PREFIX
                        + MSG_SUCCESS_INFIX
                        + delimitBySpace(context.tranRecord.transactionId)
                        + MSG_SUCCESS_SUFFIX;
                markSuccess(context, PaymentOutcome.PAYMENT_SUCCESSFUL);
                sendBillpayScreen(context);
            }
            case CICS_RESP_DUPKEY, CICS_RESP_DUPREC -> {
                context.errFlagOn = true;
                context.wsMessage = MSG_TRANSACTION_DUPLICATE;
                context.screen.cursor = CursorField.ACCOUNT_ID;
                context.messageKind = MessageKind.ERROR;
                retainFailure(context, PaymentOutcome.TRANSACTION_DUPLICATE,
                        duplicate(context, ioStatus, TRANSACT_FILE, OPERATION_WRITE,
                                MSG_TRANSACTION_DUPLICATE, context.tranRecord.transactionId));
                sendBillpayScreen(context);
            }
            default -> {
                context.errFlagOn = true;
                context.wsMessage = MSG_TRANSACTION_WRITE_FAILED;
                context.screen.cursor = CursorField.ACCOUNT_ID;
                context.messageKind = MessageKind.ERROR;
                retainFailure(context, PaymentOutcome.TRANSACTION_WRITE_FAILED,
                        accessFailure(context, ioStatus, TRANSACT_FILE, OPERATION_WRITE,
                                MSG_TRANSACTION_WRITE_FAILED));
                sendBillpayScreen(context);
            }
        }
    }

    /**
     * {@code CLEAR-CURRENT-SCREEN.} at {@code app/cbl/COBIL00C.cbl:552}-{@code :555} - clear, then send.
     *
     * <p>Two statements and nothing else: {@code :554} performs {@code INITIALIZE-ALL-FIELDS} and {@code :555}
     * performs {@code SEND-BILLPAY-SCREEN}. It is a separate paragraph from
     * {@link #initializeAllFields} because the source keeps them separate and because the clear is invoked
     * from a second call site that does <em>not</em> send.</p>
     *
     * <p>Reached from two places: the attention-key-four arm at {@code :137}, and the declined-confirmation
     * arm at {@code :180}. On the declined arm the clear runs <strong>before</strong> the error flag is set at
     * {@code :181}; that order is preserved, which is why the screen the caller receives on a decline is a
     * blank one with no message rather than an error screen.</p>
     *
     * @param context the per-invocation working storage; the input fields are cleared and the screen is sent
     */
    private void clearCurrentScreen(final PaymentContext context) {
        initializeAllFields(context);
        sendBillpayScreen(context);
    }

    /**
     * {@code INITIALIZE-ALL-FIELDS.} at {@code app/cbl/COBIL00C.cbl:560}-{@code :566} - reset the inputs and
     * the message.
     *
     * <p>{@code :562} parks the cursor on the account field and {@code :563}-{@code :566} blanks the account
     * field, the balance echo, the confirmation field and the working-storage message. The six header members
     * are untouched, because the next send repopulates them anyway.</p>
     *
     * <p>Invoked from two call sites, both of which are retained: {@link #clearCurrentScreen} at {@code :554},
     * and the write-success arm at {@code :524}. The second is why a successful payment returns a screen whose
     * account field and balance echo are blank.</p>
     *
     * <p><b>The three map members are blanked to SPACES AT THEIR DECLARED WIDTH, not to the empty string.</b>
     * {@code MOVE SPACES} to an alphanumeric item fills the whole item, so {@code ACTIDINI PIC X(11)} becomes
     * eleven spaces, {@code CURBALI PIC X(14)} fourteen and {@code CONFIRMI PIC X(1)} one, per
     * {@code app/cpy-bms/COBIL00.CPY:60}, {@code :66} and {@code :72}. Control flow is indifferent - every
     * downstream test goes through {@link #isBlankOrLowValues}, which accepts empty and all-blank alike - but
     * the field geometry the symbolic map fixes is load-bearing at the boundary, so the widths are honoured
     * rather than collapsed.</p>
     *
     * <p>{@code WS-MESSAGE} is deliberately the exception. It is {@code PIC X(80)} at
     * {@code app/cbl/COBIL00C.cbl:39}, but the host item's padding is not part of the message contract: the
     * source's own message literals are unpadded, and the one message that does carry trailing spaces,
     * {@code CCDA-MSG-INVALID-KEY}, carries them inside the copybook literal itself and is reproduced with
     * them by {@code MSG_INVALID_KEY}. Blanking the message to the empty string therefore loses nothing an
     * observer can see, whereas padding it to eighty would corrupt every literal comparison.</p>
     *
     * @param context the per-invocation working storage; four screen members, the message and the cursor are
     *                reset
     */
    private void initializeAllFields(final PaymentContext context) {
        context.screen.cursor = CursorField.ACCOUNT_ID;
        context.screen.accountId = spaces(ACCOUNT_ID_FIELD_WIDTH);
        context.screen.currentBalance = spaces(BALANCE_ECHO_WIDTH);
        context.screen.confirmation = spaces(CONFIRMATION_FIELD_WIDTH);
        context.wsMessage = "";
    }

    /* ---------------------------------------------------------------------------------------------------
     * The EXEC CICS verbs. One private executor per verb, each returning the FILE STATUS its paragraph
     * evaluates and recording the response and reason codes exactly as RESP and RESP2 would.
     * ------------------------------------------------------------------------------------------------- */

    /**
     * Performs the {@code EXEC CICS READ ... UPDATE} of {@code app/cbl/COBIL00C.cbl:345}-{@code :354} against
     * {@code ACCTDAT} and reports its file status.
     *
     * <p>The pessimistic-write finder is the read-for-update: it takes the row lock that the later rewrite of
     * {@code UPDATE-ACCTDAT-FILE} depends on, inside the transaction the public entry point opened. On success
     * the account record and the working balance - the {@code ACCT-CURR-BAL} mirror that {@code :193},
     * {@code :198}, {@code :224} and {@code :234} all read - are loaded.</p>
     *
     * <p>An identifier that cannot form a key reads as not found without touching the database, which is the
     * observable behaviour of moving an unusable text field into a numeric key at {@code :170}.</p>
     *
     * @param context the per-invocation working storage
     * @return {@code '00'} on success, {@code '23'} when no such row exists, {@code '90'} on an access failure
     */
    private String execAcctdatReadUpdate(final PaymentContext context) {
        context.ioFailureCause = null;
        if (context.acctId == null) {
            return recordResponse(context, CICS_RESP_NOTFND, IO_STATUS_RECORD_NOT_FOUND);
        }
        try {
            final Optional<Account> located = this.accountRepository.findByIdForUpdate(context.acctId);
            if (located.isEmpty()) {
                return recordResponse(context, CICS_RESP_NOTFND, IO_STATUS_RECORD_NOT_FOUND);
            }
            context.accountRecord = located.get();
            context.acctCurrBal = normaliseMoney(context.accountRecord.getCurrentBalance());
            return recordResponse(context, CICS_RESP_NORMAL, IO_STATUS_SUCCESS);
        } catch (final DataAccessException failure) {
            context.ioFailureCause = failure;
            return recordResponse(context, CICS_RESP_IOERR, IO_STATUS_IO_ERROR);
        }
    }

    /**
     * Performs the {@code EXEC CICS REWRITE} of {@code app/cbl/COBIL00C.cbl:379}-{@code :385} against
     * {@code ACCTDAT} and reports its file status.
     *
     * <p>The write is flushed rather than deferred, so an optimistic-lock or constraint failure is reported at
     * this operation instead of escaping to the transaction boundary. Without the record the read-for-update
     * should have supplied, the operation reports not found - which is how the source's own
     * {@code DFHRESP(NOTFND)} arm at {@code :390} is reached.</p>
     *
     * @param context the per-invocation working storage
     * @return {@code '00'} on success, {@code '23'} when there is no locked record, {@code '90'} otherwise
     */
    private String execAcctdatRewrite(final PaymentContext context) {
        context.ioFailureCause = null;
        if (context.accountRecord == null) {
            return recordResponse(context, CICS_RESP_NOTFND, IO_STATUS_RECORD_NOT_FOUND);
        }
        try {
            this.accountRepository.saveAndFlush(context.accountRecord);
            return recordResponse(context, CICS_RESP_NORMAL, IO_STATUS_SUCCESS);
        } catch (final DataAccessException failure) {
            context.ioFailureCause = failure;
            return recordResponse(context, CICS_RESP_IOERR, IO_STATUS_IO_ERROR);
        } catch (final IllegalArgumentException failure) {
            context.ioFailureCause = failure;
            return recordResponse(context, CICS_RESP_IOERR, IO_STATUS_IO_ERROR);
        }
    }

    /**
     * Performs the plain {@code EXEC CICS READ} of {@code app/cbl/COBIL00C.cbl:410}-{@code :418} against the
     * {@code CXACAIX} alternate-index path and reports its file status.
     *
     * <p>The alternate key is the account identifier and is non-unique, so a plain read returns the first
     * record in alternate-key sequence. The account-based finder ordered ascending by card number reproduces
     * that, and its first element is taken. The retrieved card number is never logged.</p>
     *
     * @param context the per-invocation working storage
     * @return {@code '00'} on success, {@code '23'} when the account has no cross-reference, {@code '90'}
     *         on an access failure
     */
    private String execCxacaixRead(final PaymentContext context) {
        context.ioFailureCause = null;
        if (context.xrefAcctId == null) {
            return recordResponse(context, CICS_RESP_NOTFND, IO_STATUS_RECORD_NOT_FOUND);
        }
        try {
            final List<CardCrossReference> matches =
                    this.cardCrossReferenceRepository.findByAccountIdOrderByCardNumberAsc(context.xrefAcctId);
            if (matches.isEmpty()) {
                return recordResponse(context, CICS_RESP_NOTFND, IO_STATUS_RECORD_NOT_FOUND);
            }
            context.crossReference = matches.get(0);
            return recordResponse(context, CICS_RESP_NORMAL, IO_STATUS_SUCCESS);
        } catch (final DataAccessException failure) {
            context.ioFailureCause = failure;
            return recordResponse(context, CICS_RESP_IOERR, IO_STATUS_IO_ERROR);
        }
    }

    /**
     * Performs the {@code EXEC CICS STARTBR} of {@code app/cbl/COBIL00C.cbl:443}-{@code :449} against
     * {@code TRANSACT} and reports its file status.
     *
     * <p>The record identification field holds the high-values sentinel that {@code :212} moved into it, which
     * positions the browse past the highest existing key. A relational table needs no cursor to be
     * established for that positioning to be meaningful - the descending finder of
     * {@link #execTransactReadprev} embodies it - so this operation opens the browse state and always
     * succeeds, including against an empty table. That is required: the empty case has to reach the
     * end-of-file arm of the read-previous, not the not-found arm of this one.</p>
     *
     * @param context the per-invocation working storage
     * @return {@code '00'} always, the browse state now being open
     */
    private String execTransactStartbr(final PaymentContext context) {
        context.ioFailureCause = null;
        context.browseOpen = true;
        return recordResponse(context, CICS_RESP_NORMAL, IO_STATUS_SUCCESS);
    }

    /**
     * Performs the {@code EXEC CICS READPREV} of {@code app/cbl/COBIL00C.cbl:474}-{@code :482} against
     * {@code TRANSACT} and reports its file status.
     *
     * <p>The top-one descending finder returns the highest existing identifier, which is what reading the
     * previous record from a cursor positioned past the end yields. The whole record is loaded into the record
     * area, exactly as {@code INTO(TRAN-RECORD)} at {@code :476} does, which is why the area must be cleared
     * at {@code :218} before the payment's own fields are placed into it.</p>
     *
     * <p>An empty table reports end of file, and only that arm zeroes the identifier - the mechanism by which
     * the first ever payment receives {@code 0000000000000001}. Reading without an open browse reports an
     * access failure, mirroring the invalid-request condition CICS would raise.</p>
     *
     * @param context the per-invocation working storage
     * @return {@code '00'} on success, {@code '10'} at end of file, {@code '90'} on an access failure
     */
    private String execTransactReadprev(final PaymentContext context) {
        context.ioFailureCause = null;
        if (!context.browseOpen) {
            return recordResponse(context, CICS_RESP_IOERR, IO_STATUS_IO_ERROR);
        }
        try {
            final Optional<Transaction> previous =
                    this.transactionRepository.findFirstByOrderByTransactionIdDesc();
            if (previous.isEmpty()) {
                return recordResponse(context, CICS_RESP_ENDFILE, IO_STATUS_END_OF_FILE);
            }
            context.tranRecord.loadFrom(previous.get());
            return recordResponse(context, CICS_RESP_NORMAL, IO_STATUS_SUCCESS);
        } catch (final DataAccessException failure) {
            context.ioFailureCause = failure;
            return recordResponse(context, CICS_RESP_IOERR, IO_STATUS_IO_ERROR);
        }
    }

    /**
     * Performs the {@code EXEC CICS WRITE} of {@code app/cbl/COBIL00C.cbl:512}-{@code :520} against
     * {@code TRANSACT} and reports its file status.
     *
     * <p>The insert is flushed at once so that a primary-key collision is reported here, at
     * {@code WRITE-TRANSACT-FILE}'s own evaluation, rather than at the transaction boundary. That is what
     * makes the retained identifier-generation race of parity artefact P7 observable exactly where the source
     * observes it, and it is why no sequence, identity column, retry or upsert is used: a collision must
     * surface as the duplicate condition and nothing else.</p>
     *
     * <p>A record that the entity's own field-width or amount contract rejects reports an access failure with
     * the rejection preserved as the cause, which routes it to the source's {@code WHEN OTHER} arm rather than
     * letting an untyped exception escape.</p>
     *
     * @param context the per-invocation working storage
     * @return {@code '00'} on success, {@code '22'} on a duplicate, {@code '90'} on any other failure
     */
    private String execTransactWrite(final PaymentContext context) {
        context.ioFailureCause = null;
        try {
            this.transactionRepository.saveAndFlush(context.tranRecord.toEntity());
            return recordResponse(context, CICS_RESP_NORMAL, IO_STATUS_SUCCESS);
        } catch (final DataIntegrityViolationException failure) {
            context.ioFailureCause = failure;
            return recordResponse(context, CICS_RESP_DUPREC, IO_STATUS_DUPLICATE_KEY);
        } catch (final DataAccessException failure) {
            context.ioFailureCause = failure;
            return recordResponse(context, CICS_RESP_IOERR, IO_STATUS_IO_ERROR);
        } catch (final IllegalArgumentException failure) {
            context.ioFailureCause = failure;
            return recordResponse(context, CICS_RESP_IOERR, IO_STATUS_IO_ERROR);
        }
    }

    /**
     * Records the {@code RESP} and {@code RESP2} values an {@code EXEC CICS} verb would have returned and
     * yields the equivalent two-character {@code FILE STATUS}.
     *
     * <p>{@code WS-RESP-CD} and {@code WS-REAS-CD} are declared {@code PIC S9(09) COMP} at
     * {@code app/cbl/COBIL00C.cbl:46}-{@code :47} and are the values every {@code EVALUATE} in the program
     * branches on. No operation in this program supplies a secondary reason, so it is always recorded as
     * zero - which is exactly what the source's own {@code DISPLAY 'REAS:'} statements emit.</p>
     *
     * @param context  the per-invocation working storage
     * @param respCode the {@code DFHRESP} value the {@code EVALUATE} arms compare against
     * @param ioStatus the equivalent file status, for the diagnostic and the typed exception
     * @return {@code ioStatus} unchanged, so a caller can bind it in one statement
     */
    private static String recordResponse(final PaymentContext context, final int respCode,
                                        final String ioStatus) {
        context.wsRespCd = respCode;
        context.wsReasCd = CICS_REAS_NONE;
        return ioStatus;
    }

    /**
     * Emits the diagnostic that replaces {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD}.
     *
     * <p>Six paragraphs carry that statement - {@code :366}, {@code :397}, {@code :430}, {@code :461},
     * {@code :490} and {@code :541} - and all six route here. The rendered status text comes from the shared
     * {@code FileStatusMapper}, which owns the {@code FILE STATUS IS: NNNN} literal and its four-character
     * expansion; neither is reformatted or reimplemented in this class.</p>
     *
     * <p>Only the transaction identifier, the operation, the declared dataset name, the two response codes and
     * the rendered status are emitted. No account identifier, balance, amount or card number reaches a log
     * line from here.</p>
     *
     * @param context         the per-invocation working storage
     * @param ioStatus        the two-character file status
     * @param logicalFileName the eight-character dataset name exactly as declared
     * @param operation       the CICS verb that failed
     */
    private void logIoDiagnostic(final PaymentContext context, final String ioStatus,
                                 final String logicalFileName, final String operation) {
        LOG.warn("{} {} on dataset '{}' failed: resp={} reas={} {}", TRANSACTION_ID, operation,
                logicalFileName, context.wsRespCd, context.wsReasCd,
                this.fileStatusMapper.displayIoStatus(ioStatus));
    }

    /**
     * Builds the typed exception for a not-found response, carrying the screen literal the source displays.
     *
     * @param context         the per-invocation working storage
     * @param ioStatus        the two-character file status
     * @param logicalFileName the eight-character dataset name exactly as declared
     * @param operation       the CICS verb that failed
     * @param screenMessage   the byte-exact literal the source moves into {@code WS-MESSAGE}
     * @param recordType      the logical record type, for the caller's diagnostics
     * @param recordKey       the key that matched nothing; never a card number
     * @return the exception to retain, with the underlying access failure preserved as the cause when there
     *         was one
     */
    private CardDemoException notFound(final PaymentContext context, final String ioStatus,
                                      final String logicalFileName, final String operation,
                                      final String screenMessage, final String recordType,
                                      final String recordKey) {
        logIoDiagnostic(context, ioStatus, logicalFileName, operation);
        if (context.ioFailureCause == null) {
            return new RecordNotFoundException(screenMessage, recordType, recordKey);
        }
        return new RecordNotFoundException(screenMessage, recordType, recordKey, context.ioFailureCause);
    }

    /**
     * Builds the typed exception for a duplicate response, carrying the screen literal the source displays.
     *
     * @param context         the per-invocation working storage
     * @param ioStatus        the two-character file status
     * @param logicalFileName the eight-character dataset name exactly as declared
     * @param operation       the CICS verb that failed
     * @param screenMessage   the byte-exact literal the source moves into {@code WS-MESSAGE}
     * @param collidingKey    the generated identifier that already existed
     * @return the exception to retain, with the underlying constraint violation preserved as the cause
     */
    private CardDemoException duplicate(final PaymentContext context, final String ioStatus,
                                        final String logicalFileName, final String operation,
                                        final String screenMessage, final String collidingKey) {
        logIoDiagnostic(context, ioStatus, logicalFileName, operation);
        if (context.ioFailureCause == null) {
            return new DuplicateRecordException(screenMessage, logicalFileName, collidingKey);
        }
        return new DuplicateRecordException(screenMessage, logicalFileName, collidingKey,
                context.ioFailureCause);
    }

    /**
     * Builds the typed exception for any other response, carrying the screen literal the source displays.
     *
     * @param context         the per-invocation working storage
     * @param ioStatus        the two-character file status
     * @param logicalFileName the eight-character dataset name exactly as declared
     * @param operation       the CICS verb that failed
     * @param screenMessage   the byte-exact literal the source moves into {@code WS-MESSAGE}
     * @return the exception to retain, with the underlying failure preserved as the cause when there was one
     */
    private CardDemoException accessFailure(final PaymentContext context, final String ioStatus,
                                            final String logicalFileName, final String operation,
                                            final String screenMessage) {
        logIoDiagnostic(context, ioStatus, logicalFileName, operation);
        if (context.ioFailureCause == null) {
            return new FileAccessException(screenMessage, ioStatus, logicalFileName, operation);
        }
        return new FileAccessException(screenMessage, ioStatus, logicalFileName, operation,
                context.ioFailureCause);
    }

    /* ---------------------------------------------------------------------------------------------------
     * Outcome bookkeeping. Result metadata only; no observable behaviour of the source depends on it.
     * ------------------------------------------------------------------------------------------------- */

    /**
     * Records a non-failing outcome, unless a failure has already been retained.
     *
     * <p>Guarded because the source keeps going after a failure: a pass that failed deep inside the write
     * sequence still reaches the unconditional send at {@code :242}, and a deep-linked first display still
     * reaches the send at {@code :122}. Without the guard those later, successful-looking steps would mask the
     * real outcome.</p>
     *
     * @param context the per-invocation working storage
     * @param outcome the outcome to record
     */
    private static void markSuccess(final PaymentContext context, final PaymentOutcome outcome) {
        if (context.pendingFailure == null) {
            context.outcome = outcome;
        }
    }

    /**
     * Retains the first failure of the pass, together with the outcome that names it.
     *
     * <p>First-error-wins, matching the source: {@code WS-ERR-FLG} is set once and every subsequent guard tests
     * it rather than overwriting the message. Retaining rather than throwing is what preserves the control
     * flow, because the source does not abandon the paragraph on a failed read - it sets the flag, sends the
     * screen and continues. {@link #payBill} is the entry point that converts the retained failure into a
     * throw.</p>
     *
     * <p>A successful write followed by a failed account rewrite therefore reports the rewrite failure, not
     * the write success - which is correct, because the shared transaction rolls the write back.</p>
     *
     * @param context the per-invocation working storage
     * @param outcome the outcome that names the failure
     * @param failure the typed exception carrying the byte-exact screen literal and the root cause
     */
    private static void retainFailure(final PaymentContext context, final PaymentOutcome outcome,
                                     final CardDemoException failure) {
        if (context.pendingFailure == null) {
            context.pendingFailure = failure;
            context.outcome = outcome;
        }
    }

    /**
     * Assembles the receipt for a payment that completed end to end.
     *
     * <p>Pure result metadata, built after the account rewrite of {@code :235} and only when nothing failed.
     * A pass whose write succeeded but whose rewrite failed produces no receipt, because the shared transaction
     * rolls the write back and no payment occurred.</p>
     *
     * @param context the per-invocation working storage
     */
    private static void buildReceipt(final PaymentContext context) {
        if (context.pendingFailure != null) {
            return;
        }
        context.receipt = new PaymentReceipt(context.tranRecord.transactionId, context.tranRecord.amount,
                context.acctCurrBal, context.tranRecord.originatingTimestamp,
                context.tranRecord.processingTimestamp);
    }

    /* ---------------------------------------------------------------------------------------------------
     * COBOL figurative-constant, MOVE and numeric-conversion semantics.
     * ------------------------------------------------------------------------------------------------- */

    /**
     * Tests a screen field against the COBOL figurative constants {@code SPACES} and {@code LOW-VALUES}.
     *
     * <p>Three sites need exactly this test, and abbreviated combined relations make all three the same
     * predicate. {@code :117} reads {@code NOT = SPACES AND LOW-VALUES}, which expands to <em>not equal to
     * spaces and not equal to low values</em>. {@code :159} reads {@code = SPACES OR LOW-VALUES}, which expands
     * to <em>equal to spaces or equal to low values</em>. {@code :199} repeats the {@code :117} form, and
     * {@code :129} and {@code :275} apply it to a program name.</p>
     *
     * <p>{@code null} is the {@code LOW-VALUES} analogue for an absent member - it is what
     * {@code MOVE LOW-VALUES TO COBIL0AO} at {@code :114} leaves behind - and an explicit NUL character is
     * treated the same way, so a caller that submits one is not mistaken for a caller that submitted data.</p>
     *
     * @param value the field to test, possibly {@code null}
     * @return {@code true} when the field is absent, empty, all blanks or all NUL characters
     */
    private static boolean isBlankOrLowValues(final String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (character != ' ' && character != '\0') {
                return false;
            }
        }
        return true;
    }

    /**
     * Applies the truncating half of a COBOL alphanumeric {@code MOVE} into a narrower field.
     *
     * <p>Used at two sites. {@code :118}-{@code :119} moves a {@code PIC X(16)} transaction identifier into a
     * {@code PIC X(11)} account field, keeping the leading eleven characters - parity artefact P5. {@code :293}
     * moves the {@code PIC X(80)} working-storage message into the {@code PIC X(78)} screen field, losing the
     * final two characters.</p>
     *
     * <p>Deliberately does <em>not</em> pad a shorter value: the receiving field's trailing blanks carry no
     * information in a JSON projection, whereas a literal's own declared padding is reproduced verbatim in the
     * literal itself.</p>
     *
     * @param value the sending field, possibly {@code null}
     * @param width the receiving field's declared width
     * @return the leading {@code width} characters, or the value unchanged when it already fits, or
     *         {@code null} when the sending field was absent
     */
    private static String truncateLeft(final String value, final int width) {
        if (value == null) {
            return null;
        }
        return value.length() <= width ? value : value.substring(0, width);
    }

    /**
     * Applies a complete COBOL alphanumeric {@code MOVE} into a fixed-width field: blank-pad on the right when
     * the sender is shorter, truncate on the right when it is longer.
     *
     * <p>This is the operation that preserves record geometry. The transaction record is three hundred and
     * fifty bytes and every one of its alphanumeric fields is space-padded to its declared picture, so a value
     * placed into it must be exactly as wide as the picture declares - not merely no wider.</p>
     *
     * @param value the sending field, {@code null} being treated as blanks
     * @param width the receiving field's declared width
     * @return a string of exactly {@code width} characters
     */
    private static String padRight(final String value, final int width) {
        final String sender = value == null ? "" : value;
        if (sender.length() >= width) {
            return sender.substring(0, width);
        }
        return sender + " ".repeat(width - sender.length());
    }

    /**
     * Produces the blank fill an {@code INITIALIZE} gives an alphanumeric field of the given width.
     *
     * @param width the field's declared width
     * @return a string of exactly {@code width} blanks
     */
    private static String spaces(final int width) {
        return " ".repeat(width);
    }

    /**
     * Reads the exact two-character code off one of {@link FileStatus}'s exact constants.
     *
     * <p>Exists so the DFHRESP translation table above can single-source its codes from the type that owns
     * them instead of restating them as literals. Fails fast rather than degrading: a constant that stopped
     * being an exact value would silently feed an empty status to the shared mapper, which would then render a
     * status this program never observed.</p>
     *
     * @param status one of the exact constants, never the {@code IO_ERROR} family
     * @return the two-character code
     * @throws IllegalStateException when the constant carries no exact code, which means the translation table
     *         in this class has to be revisited rather than patched at the call site
     */
    private static String exactCode(final FileStatus status) {
        return status.code().orElseThrow(() -> new IllegalStateException("FileStatus." + status.name()
                + " no longer carries an exact two-character code, so the DFHRESP translation table in "
                + "BillPaymentService can no longer be derived from it"));
    }

    /**
     * Tests whether every character is an ASCII digit.
     *
     * <p>Deliberately narrower than {@code Character.isDigit}, which accepts digits from other numbering
     * systems. A zoned-decimal field cannot hold those, so accepting them would let a value through that no
     * fixed-width representation could carry and that no baseline comparison would match.</p>
     *
     * @param value the candidate, possibly {@code null}
     * @return {@code true} only for a non-empty run of {@code '0'} through {@code '9'}
     */
    private static boolean isAllDigits(final String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Brings a money value to the scale its picture declares, or supplies the zero of an unpopulated record
     * area.
     *
     * <p>Rounding is stated explicitly even though it cannot occur: the account entity already refuses a scale
     * greater than two, so the operation is a widening at most. Stating the mode is the invariant, because a
     * default-mode {@code setScale} would throw rather than round if the premise ever changed.</p>
     *
     * @param value the value read from a record, possibly {@code null}
     * @return a value at the money scale, never {@code null}
     */
    private static BigDecimal normaliseMoney(final BigDecimal value) {
        return value == null ? ZERO_BALANCE : value.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * Performs the single {@code MOVE} of {@code app/cbl/COBIL00C.cbl:170}-{@code :171}, which populates
     * <em>both</em> the account key and the cross-reference key from one screen field.
     *
     * <p>The sending field is {@code ACTIDINI PIC X(11)}; the receiving fields are {@code ACCT-ID PIC 9(11)} in
     * {@code app/cpy/CVACT01Y.cpy} and {@code XREF-ACCT-ID PIC 9(11)} in {@code app/cpy/CVACT03Y.cpy}. The
     * source performs no validation whatsoever on that text-to-numeric move, so a value that is not eleven or
     * fewer ASCII digits leaves an unusable key behind and the subsequent read matches nothing.</p>
     *
     * <p>The validation is therefore made explicit here, as Rule 1 Clause B requires, and its outcome is made
     * explicit too: an unusable value yields {@code null}, which the read operations report as not found and
     * which surfaces as {@code Account ID NOT found...}. That is the source's observable behaviour, arrived at
     * deliberately rather than by accident.</p>
     *
     * @param screenValue the raw eleven-character screen field, possibly {@code null}
     * @return the numeric key, or {@code null} when the field cannot form one
     */
    private static Long moveAccountIdentifier(final String screenValue) {
        if (screenValue == null) {
            return null;
        }
        final String candidate = screenValue.strip();
        if (candidate.isEmpty() || candidate.length() > ACCOUNT_ID_FIELD_WIDTH || !isAllDigits(candidate)) {
            return null;
        }
        return Long.valueOf(candidate);
    }

    /**
     * Performs the {@code MOVE TRAN-ID TO WS-TRAN-ID-NUM} of {@code app/cbl/COBIL00C.cbl:216}.
     *
     * <p>The sender is {@code TRAN-ID PIC X(16)} and the receiver is {@code WS-TRAN-ID-NUM PIC 9(16)} at
     * {@code :57}. On the ordinary paths the sender holds either the sixteen-digit maximum identifier the
     * read-previous loaded or the sixteen zeros its end-of-file arm moved, and both convert cleanly.</p>
     *
     * <p><strong>Deviation D1, classified Medium.</strong> Parity artefact P6 means this line is reached even
     * when the browse failed, in which case the sender still holds the high-values sentinel that {@code :212}
     * moved into it. Moving high values into a numeric display field is undefined in COBOL and the following
     * {@code ADD} would raise a data exception, terminating the task. Undefined behaviour cannot be reproduced,
     * and inventing a value would write a garbage transaction, so the closest faithful analogue is taken: a
     * fatal abend. The shared transaction then rolls back, so nothing is written.</p>
     *
     * @param context the per-invocation working storage
     * @return the numeric value of the browse key
     * @throws FatalProcessingException when the browse key is not a usable numeric value
     */
    private long moveTransactionIdToNumeric(final PaymentContext context) {
        final String browseKey = context.tranRecord.transactionId;
        if (browseKey != null && browseKey.length() <= TRANSACTION_ID_WIDTH && isAllDigits(browseKey)) {
            return Long.parseLong(browseKey);
        }
        LOG.error("{} cannot convert the browse key after {} on dataset '{}'; abend {}",
                TRANSACTION_ID, OPERATION_READPREV, TRANSACT_FILE, ABEND_CODE_DATA_EXCEPTION);
        throw new FatalProcessingException(ABEND_CODE_DATA_EXCEPTION, PROGRAM_NAME,
                "NON-NUMERIC BROWSE KEY AT COBIL00C:216",
                "Bill payment cannot generate a transaction identifier: the descending browse left a "
                        + "non-numeric value in TRAN-ID, which app/cbl/COBIL00C.cbl:216 moves into a "
                        + "PIC 9(16) work field.");
    }

    /**
     * Performs the {@code MOVE ACCT-CURR-BAL TO TRAN-AMT} of {@code app/cbl/COBIL00C.cbl:224}.
     *
     * <p><strong>This is the line that makes the payment the whole balance.</strong> The amount is derived from
     * the account and can be neither supplied nor influenced by a caller; the source contains no
     * partial-payment path.</p>
     *
     * <p>The move crosses two pictures: {@code ACCT-CURR-BAL PIC S9(10)V99}, which is
     * {@code NUMERIC(12,2)}, into {@code TRAN-AMT PIC S9(09)V99}, which is {@code NUMERIC(11,2)}. The scale is
     * preserved and the magnitude is asserted rather than truncated.</p>
     *
     * <p><strong>Deviation D3, classified Low.</strong> A balance that does not fit the narrower picture would
     * silently lose its high-order digit in COBOL, recording a wrong amount and driving the balance to a wrong
     * figure. Reproducing that would corrupt the ledger, so the condition is detected and raised as a fatal
     * abend instead. The account entity's own contract bounds the balance at ten integer digits, so the
     * condition is reachable only for a balance between one and ten billion.</p>
     *
     * @param balance the authoritative balance read from the account record
     * @return the amount at the transaction scale
     * @throws FatalProcessingException when the balance does not fit the transaction amount picture
     */
    private BigDecimal moveBalanceToTransactionAmount(final BigDecimal balance) {
        final BigDecimal amount = normaliseMoney(balance);
        if (amount.abs().compareTo(TRANSACTION_AMOUNT_LIMIT) > 0) {
            LOG.error("{} balance does not fit TRAN-AMT PIC S9(09)V99; abend {}",
                    TRANSACTION_ID, ABEND_CODE_DATA_EXCEPTION);
            throw new FatalProcessingException(ABEND_CODE_DATA_EXCEPTION, PROGRAM_NAME,
                    "BALANCE EXCEEDS TRAN-AMT PIC S9(09)V99",
                    "Bill payment cannot be recorded: the account balance does not fit the transaction "
                            + "amount field declared at app/cpy/CVTRA05Y.cpy.");
        }
        return amount;
    }

    /**
     * Renders a balance on the {@code WS-CURR-BAL PIC +9999999999.99} mask of
     * {@code app/cbl/COBIL00C.cbl:56}.
     *
     * <p>Fourteen characters: a mandatory sign, ten integer digits, a point and two decimals - which matches
     * {@code CURBALI PIC X(14)} exactly, so the echo of {@code :193}-{@code :194} fits its receiving field with
     * nothing to spare. A positive or zero value carries {@code '+'}, as the mask's mandatory sign requires;
     * {@code +0000000194.00} is the rendering of the first seeded account's balance.</p>
     *
     * <p>An integer part wider than the mask keeps its low-order ten digits, which is the truncating behaviour
     * of a numeric-edited {@code MOVE}. Formatting is assembled from the value's own plain string rather than a
     * locale-sensitive formatter, so no locale can substitute a comma for the point or a non-ASCII digit.</p>
     *
     * @param value the balance to render
     * @return exactly fourteen characters
     */
    private static String formatBalanceEcho(final BigDecimal value) {
        final BigDecimal scaled = normaliseMoney(value);
        final String sign = scaled.signum() < 0 ? "-" : "+";
        final String magnitude = scaled.abs().toPlainString();
        final int pointIndex = magnitude.indexOf('.');
        final String integerDigits = magnitude.substring(0, pointIndex);
        final String fractionDigits = magnitude.substring(pointIndex + 1);
        final String boundedIntegerDigits = integerDigits.length() <= BALANCE_ECHO_INTEGER_DIGITS
                ? "0".repeat(BALANCE_ECHO_INTEGER_DIGITS - integerDigits.length()) + integerDigits
                : integerDigits.substring(integerDigits.length() - BALANCE_ECHO_INTEGER_DIGITS);
        return padRight(sign + boundedIntegerDigits + "." + fractionDigits, BALANCE_ECHO_WIDTH);
    }

    /**
     * Performs the {@code MOVE WS-TRAN-ID-NUM TO TRAN-ID} of {@code app/cbl/COBIL00C.cbl:219}.
     *
     * <p>A {@code PIC 9(16)} display field moved into a {@code PIC X(16)} field yields sixteen zero-padded
     * digits, which is why the first ever payment is identified {@code 0000000000000001} and not {@code 1}.
     * The seeded identifiers are zero-padded the same way, so the padding is required for a key to match.</p>
     *
     * <p>A value that has overflowed the sixteen-digit picture keeps its low-order sixteen digits, reproducing
     * what {@code ADD 1 TO WS-TRAN-ID-NUM} at {@code :217} does when the field is already at its maximum.</p>
     *
     * @param value the numeric identifier
     * @return exactly sixteen digits
     */
    private static String formatTransactionIdentifier(final long value) {
        final String digits = Long.toString(value);
        if (digits.length() >= TRANSACTION_ID_WIDTH) {
            return digits.substring(digits.length() - TRANSACTION_ID_WIDTH);
        }
        return "0".repeat(TRANSACTION_ID_WIDTH - digits.length()) + digits;
    }

    /**
     * Applies the {@code DELIMITED BY SPACE} clause of {@code app/cbl/COBIL00C.cbl:529}.
     *
     * <p>The {@code STRING} statement stops copying the identifier at its first blank. A sixteen-digit
     * zero-padded identifier contains none, so the whole of it reaches the message; the clause is nonetheless
     * reproduced because it is what the source specifies and because a shorter, blank-padded identifier would
     * be cut exactly here.</p>
     *
     * @param value the sending field, possibly {@code null}
     * @return the characters before the first blank, or an empty string when the field was absent
     */
    private static String delimitBySpace(final String value) {
        if (value == null) {
            return "";
        }
        final int blankIndex = value.indexOf(' ');
        return blankIndex < 0 ? value : value.substring(0, blankIndex);
    }

    /**
     * Maps the attention identifier onto the four arms of the {@code EVALUATE EIBAID} at
     * {@code app/cbl/COBIL00C.cbl:125}.
     *
     * <p>{@code DFHENTER}, {@code DFHPF3} and {@code DFHPF4} are the three named arms; everything else takes
     * {@code WHEN OTHER} and yields the invalid-key message. An absent identifier takes {@code WHEN OTHER}
     * too, which is the safe default - it never proceeds with a payment. Folding is pinned to
     * {@code Locale.ROOT} so that no locale-specific case mapping can turn a recognised key into an
     * unrecognised one.</p>
     *
     * @param attentionIdentifier the raw identifier, possibly {@code null}
     * @return the arm to take, never {@code null}
     */
    private static AidKey classifyAttentionKey(final String attentionIdentifier) {
        if (attentionIdentifier == null) {
            return AidKey.OTHER;
        }
        final String folded = attentionIdentifier.strip().toUpperCase(Locale.ROOT);
        return switch (folded) {
            case "ENTER", "DFHENTER" -> AidKey.ENTER;
            case "PF3", "F3", "DFHPF3" -> AidKey.PF3;
            case "PF4", "F4", "DFHPF4" -> AidKey.PF4;
            default -> AidKey.OTHER;
        };
    }

    /**
     * Maps the confirmation field onto the six arms of the {@code EVALUATE CONFIRMI} at
     * {@code app/cbl/COBIL00C.cbl:173}-{@code :191}.
     *
     * <p>The source declares six {@code WHEN} clauses - {@code 'Y'}, {@code 'y'}, {@code 'N'}, {@code 'n'},
     * {@code SPACES} and {@code LOW-VALUES} - plus {@code WHEN OTHER}, and each retains its own identity here.
     * <strong>They are not folded case-insensitively and blank is not merged with yes</strong>, because the two
     * pairs that share a body still differ downstream: blank and low values read the account without setting
     * the confirmation flag, so they fall through to the prompt at {@code :236}, whereas {@code 'Y'} and
     * {@code 'y'} set the flag and reach the write sequence at {@code :210}.</p>
     *
     * <p>An absent member is the {@code LOW-VALUES} arm, an empty or blank member is the {@code SPACES} arm,
     * and a member holding an explicit NUL is the {@code LOW-VALUES} arm. Because the field is
     * {@code PIC X(1)}, a member longer than one character cannot be a value the map could have carried and
     * takes {@code WHEN OTHER}; an unrecognised value never proceeds with a payment.</p>
     *
     * @param confirmation the raw one-character field, possibly {@code null}
     * @return the arm to take, never {@code null}
     */
    private static ConfirmationBranch classifyConfirmation(final String confirmation) {
        if (confirmation == null) {
            return ConfirmationBranch.LOW_VALUES;
        }
        if (isBlankOrLowValues(confirmation)) {
            return confirmation.indexOf('\0') >= 0
                    ? ConfirmationBranch.LOW_VALUES
                    : ConfirmationBranch.BLANK;
        }
        if (confirmation.length() != CONFIRMATION_FIELD_WIDTH) {
            return ConfirmationBranch.INVALID;
        }
        return switch (confirmation.charAt(0)) {
            case 'Y' -> ConfirmationBranch.YES_UPPER;
            case 'y' -> ConfirmationBranch.YES_LOWER;
            case 'N' -> ConfirmationBranch.NO_UPPER;
            case 'n' -> ConfirmationBranch.NO_LOWER;
            default -> ConfirmationBranch.INVALID;
        };
    }

    /* ---------------------------------------------------------------------------------------------------
     * Disclosure-free rendering. Used by the two published records that carry sensitive or free-text members.
     * ------------------------------------------------------------------------------------------------- */

    /**
     * Describes a member's shape without disclosing any of its characters.
     *
     * <p>This class handles an account identifier, a balance, a payment amount and a sixteen-digit card
     * number, and a record's compiler-generated rendering would emit every component verbatim through the
     * ordinary interpolation path that a parameterised log statement, an exception message, a debugger or a
     * telemetry capture takes. Reporting a shape instead removes the disclosure and, because free-text members
     * are bound from an untrusted request body, it also removes the log-forgery vector that an embedded
     * carriage return or escape sequence would otherwise open.</p>
     *
     * @param value the member, possibly {@code null}
     * @return {@code absent}, {@code empty}, or a character count; never any submitted character
     */
    private static String shapeOf(final String value) {
        if (value == null) {
            return "absent";
        }
        if (value.isEmpty()) {
            return "empty";
        }
        return value.length() + " chars";
    }

    /**
     * Describes a closed-domain single-character gate by its code point rather than its character.
     *
     * <p>The confirmation field has a small, known domain, so its code point discloses nothing a caller did not
     * already know - while still keeping the submitted byte itself out of the output, so that no control
     * character can reach a log file or a terminal.</p>
     *
     * @param value the gate member, possibly {@code null}
     * @return {@code absent}, {@code empty}, or the decimal code point of the first character
     */
    private static String codePointOf(final String value) {
        if (value == null) {
            return "absent";
        }
        if (value.isEmpty()) {
            return "empty";
        }
        return "U+" + Integer.toHexString(value.codePointAt(0)).toUpperCase(Locale.ROOT);
    }

    /**
     * Describes a money member's shape without disclosing the figure.
     *
     * @param value the amount, possibly {@code null}
     * @return {@code absent}, or the sign and scale only
     */
    private static String shapeOfMoney(final BigDecimal value) {
        if (value == null) {
            return "absent";
        }
        final int signum = value.signum();
        final String signText = signum < 0 ? "negative" : signum == 0 ? "zero" : "positive";
        return signText + "@scale" + value.scale();
    }

    /* ===================================================================================================
     * Published types.
     *
     * Every supporting type is NESTED. app/cbl/COBIL00C.cbl is one program and its Java replacement is one
     * file: com.cardemo.service.billing holds exactly this class and no package-info.java, so the vocabulary
     * the published API needs is declared inside the class rather than beside it. Nesting adds no file.
     * ================================================================================================ */

    /**
     * The four arms of the {@code EVALUATE EIBAID} at {@code app/cbl/COBIL00C.cbl:125}-{@code :142}.
     *
     * <p>A 3270 attention identifier tells the program which key ended the terminal's turn. HTTP has no
     * equivalent, so the caller states the intent explicitly and this enum keeps the four source arms
     * distinguishable rather than collapsing them into one request shape.</p>
     */
    public enum AidKey {

        /** {@code WHEN DFHENTER} at {@code :126}: run the business flow. */
        ENTER,

        /** {@code WHEN DFHPF3} at {@code :128}: leave the screen and resolve a navigation target. */
        PF3,

        /** {@code WHEN DFHPF4} at {@code :136}: clear the input fields and re-display. */
        PF4,

        /**
         * {@code WHEN OTHER} at {@code :138}: an unrecognised key. Yields {@link #MSG_INVALID_KEY} and never
         * proceeds with a payment, which is why an absent identifier is classified here.
         */
        OTHER
    }

    /**
     * The pseudo-conversational entry state that {@code EIBCALEN} and {@code CDEMO-PGM-REENTER} together
     * decide at {@code app/cbl/COBIL00C.cbl:107} and {@code :112}.
     *
     * <p>The source keeps this state in the COMMAREA between turns. Nothing keeps it here: the caller states
     * which of the three source paths it wants and the service holds no state between calls. The enum exists
     * so the three paths stay reachable and testable, not to reintroduce the flag.</p>
     */
    public enum EntryMode {

        /**
         * First display. {@code IF NOT CDEMO-PGM-REENTER} at {@code :112} clears the map, homes the cursor and
         * sends the empty screen, optionally pre-populating the account field from a deep link.
         */
        ENTER,

        /**
         * Subsequent turn. {@code :124} binds the submitted fields and dispatches on the attention key.
         */
        REENTER,

        /**
         * No context at all. {@code IF EIBCALEN = 0} at {@code :107} means the program was reached without a
         * COMMAREA; the source transfers to the sign-on program. Here it means no request context, and the
         * pass yields a navigation hint and sends no screen.
         */
        NO_CONTEXT
    }

    /**
     * Which of the two things the source does at the end of a turn actually happened.
     *
     * <p>{@code EXEC CICS SEND MAP} at {@code app/cbl/COBIL00C.cbl:295} paints a screen and returns to the
     * terminal; {@code EXEC CICS XCTL} at {@code :281} hands control to another program and never comes back.
     * The distinction survives because a caller has to know whether it received a screen or a redirect.</p>
     */
    public enum ResponseKind {

        /** A screen was assembled and sent: the {@code SEND MAP} path. */
        MAP,

        /** Control was transferred: the {@code XCTL} path, which sends no screen. */
        TRANSFER
    }

    /**
     * How the message field is meant to read, standing in for the 3270 colour attribute.
     *
     * <p>{@code MOVE DFHGREEN TO ERRMSGC OF COBIL0AO} at {@code app/cbl/COBIL00C.cbl:526} turns the message
     * green on the successful write, and every failure path leaves the field at its default. An attribute byte
     * is meaningless over JSON, so the intent is published instead of the byte - the substitution recorded as
     * reconciliation R2 in the class documentation.</p>
     */
    public enum MessageKind {

        /** No message: {@code MOVE SPACES TO WS-MESSAGE} at {@code :104} and {@code :525}. */
        NONE,

        /**
         * A message that is not a failure. Covers the green success text of {@code :527}-{@code :531} and the
         * {@code Confirm to make a bill payment...} prompt of {@code :237}, which sets no error flag.
         */
        INFORMATIONAL,

        /** A failure message: every path that moves {@code 'Y'} into {@code WS-ERR-FLG}. */
        ERROR
    }

    /**
     * Where the source parks the cursor, expressed as the field it belongs to.
     *
     * <p>{@code MOVE -1 TO <field>L} sets a negative length in a symbolic map's length member, which is the BMS
     * idiom for "put the cursor here", and {@code SEND MAP ... CURSOR} honours it. The service publishes the
     * field name so a caller can highlight the same input the terminal would have, and so the two distinct
     * targets - the account field and the confirmation field - stay distinguishable.</p>
     */
    public enum CursorField {

        /** No cursor request. */
        NONE,

        /**
         * {@code ACTIDINL}: set at {@code :115}, {@code :163}, {@code :203}, {@code :361}, {@code :368},
         * {@code :392}, {@code :399}, {@code :425}, {@code :432}, {@code :458}, {@code :465}, {@code :494},
         * {@code :538}, {@code :545} and {@code :562}.
         */
        ACCOUNT_ID,

        /** {@code CONFIRML}: set at {@code :189} on the invalid branch and {@code :239} on the prompt. */
        CONFIRMATION
    }

    /**
     * The seven arms of the {@code EVALUATE CONFIRMI} at {@code app/cbl/COBIL00C.cbl:173}-{@code :191}: the
     * six declared {@code WHEN} clauses plus {@code WHEN OTHER}.
     *
     * <p><strong>The arms are never merged.</strong> {@code 'Y'} and {@code 'y'} are two clauses that share one
     * body, as are {@code 'N'}/{@code 'n'} and {@code SPACES}/{@code LOW-VALUES}, and identity is kept for each
     * because the pairs behave differently downstream: yes sets {@code CONF-PAY-YES} and reaches the write
     * sequence, whereas blank reads the account and falls through to the prompt at {@code :236}. Folding the
     * gate case-insensitively, or treating blank as a yes, changes which requests move money.</p>
     */
    public enum ConfirmationBranch {

        /** {@code WHEN 'Y'} at {@code :174}: confirmed. */
        YES_UPPER,

        /** {@code WHEN 'y'} at {@code :175}: confirmed. */
        YES_LOWER,

        /** {@code WHEN 'N'} at {@code :178}: declined; clears the screen, then flags the pass. */
        NO_UPPER,

        /** {@code WHEN 'n'} at {@code :179}: declined; clears the screen, then flags the pass. */
        NO_LOWER,

        /**
         * {@code WHEN SPACES} at {@code :182}: reads the account but leaves the confirmation flag unset, so the
         * pass ends on the {@code Confirm to make a bill payment...} prompt.
         */
        BLANK,

        /** {@code WHEN LOW-VALUES} at {@code :183}: identical body to {@link #BLANK}, distinct clause. */
        LOW_VALUES,

        /**
         * {@code WHEN OTHER} at {@code :185}: yields {@code Invalid value. Valid values are (Y/N)...} with the
         * cursor on the confirmation field. An unrecognised value never proceeds with a payment.
         */
        INVALID
    }

    /**
     * Every distinguishable way a pass through {@code app/cbl/COBIL00C.cbl} can end.
     *
     * <p>The source encodes its outcome in a screen literal, a flag and a cursor position, and the terminal
     * operator reads all three. A caller of this service cannot, so each outcome is named. Collapsing them -
     * to one conflict status, say - would discard information the legacy screen displayed, which is why there
     * are twenty-one constants and not a handful.</p>
     */
    public enum PaymentOutcome {

        /** {@code IF EIBCALEN = 0} at {@code :107}: no request context; navigation only, no screen. */
        NO_CONTEXT,

        /** {@code :122}: the empty first display was sent. */
        PROMPT,

        /** {@code WHEN OTHER} at {@code :138}: an unrecognised attention key. */
        INVALID_KEY,

        /** {@code WHEN DFHPF4} at {@code :136}: the input fields were cleared and the screen re-sent. */
        SCREEN_CLEARED,

        /** {@code WHEN DFHPF3} at {@code :128}: the pass resolved a navigation target and transferred. */
        NAVIGATED_BACK,

        /** {@code :159}-{@code :164}: {@code Acct ID can NOT be empty...} */
        ACCOUNT_ID_EMPTY,

        /** {@code :185}-{@code :190}: {@code Invalid value. Valid values are (Y/N)...} */
        CONFIRMATION_INVALID,

        /** {@code :178}-{@code :181}: the operator answered no; the screen was cleared and the pass flagged. */
        CONFIRMATION_DECLINED,

        /**
         * {@code :236}-{@code :239}: {@code Confirm to make a bill payment...} - the account was read and the
         * balance echoed, but no confirmation was given, so no payment happened. Sets no error flag.
         */
        CONFIRMATION_REQUIRED,

        /** {@code :198}-{@code :204}: {@code You have nothing to pay...} A zero balance lands here. */
        NOTHING_TO_PAY,

        /** {@code :361}: {@code Account ID NOT found...} on the read for update. */
        ACCOUNT_NOT_FOUND,

        /** {@code :368}: {@code Unable to lookup Account...} */
        ACCOUNT_LOOKUP_FAILED,

        /** {@code :392}: {@code Account ID NOT found...} on the rewrite. */
        ACCOUNT_UPDATE_NOT_FOUND,

        /** {@code :399}: {@code Unable to Update Account...} */
        ACCOUNT_UPDATE_FAILED,

        /** {@code :425}: {@code Account ID NOT found...} on the cross-reference read. */
        CROSS_REFERENCE_NOT_FOUND,

        /** {@code :432}: {@code Unable to lookup XREF AIX file...} */
        CROSS_REFERENCE_LOOKUP_FAILED,

        /** {@code :458}: {@code Transaction ID NOT found...} on the browse start. */
        TRANSACTION_BROWSE_NOT_FOUND,

        /** {@code :465} and {@code :492}: {@code Unable to lookup Transaction...} */
        TRANSACTION_LOOKUP_FAILED,

        /** {@code :536}: {@code Tran ID already exist...} - the retained max-plus-one race, surfacing. */
        TRANSACTION_DUPLICATE,

        /** {@code :543}: {@code Unable to Add Bill pay Transaction...} */
        TRANSACTION_WRITE_FAILED,

        /**
         * {@code :527}-{@code :531}: the transaction was written, the balance driven to exactly zero and the
         * account rewritten. The only outcome under which money moved.
         */
        PAYMENT_SUCCESSFUL
    }

    /**
     * The navigation hint that replaces {@code EXEC CICS XCTL} at {@code app/cbl/COBIL00C.cbl:281}-{@code :284}.
     *
     * <p>{@code RETURN-TO-PREV-SCREEN} resolves a target program, stamps the COMMAREA with where control came
     * from, zeroes the pseudo-conversational context flag and transfers. None of that has a Java counterpart:
     * routing is URL-based, there is no {@code XCTL}, and no server-side navigation state is retained. What is
     * reproduced is the <em>resolution logic</em> - the target defaults to the sign-on program when the
     * requested one is blank at {@code :275}-{@code :277}, and to the main menu when the originating program is
     * blank at {@code :129}-{@code :133} - published as a hint for a caller that wants to mirror the legacy
     * flow. A caller free to route differently may ignore it entirely.</p>
     *
     * <p>Every member is one of this class's own fixed program-name constants, so no submitted character can
     * reach the record and the compiler-generated {@code toString} is safe. That is why this type, unlike
     * {@link BillPaymentScreen} and {@link BillPaymentResult}, does not override it.</p>
     *
     * @param fromTransactionId {@code CDEMO-FROM-TRANID} at {@code :278}: always {@code CB00}
     * @param fromProgram       {@code CDEMO-FROM-PROGRAM} at {@code :279}: always {@code COBIL00C}
     * @param toProgram         {@code CDEMO-TO-PROGRAM} at {@code :281}: the resolved target
     * @param programContext    {@code CDEMO-PGM-CONTEXT} at {@code :280}: zeroed, meaning the target should
     *                          treat its next turn as a first display. Carried for provenance only, because
     *                          the flag it mirrors has no Java counterpart
     */
    public record Navigation(String fromTransactionId, String fromProgram, String toProgram,
                             int programContext) {
    }

    /**
     * Evidence that a payment completed: what was recorded, and what the balance became.
     *
     * <p>Assembled after the account rewrite of {@code app/cbl/COBIL00C.cbl:235} and only when nothing failed.
     * The source has no counterpart - a 3270 operator reads the outcome off the screen - so this is additive
     * result metadata rather than a translated construct. It exists because a programmatic caller cannot read a
     * screen literal, and because the two invariants a reviewer most needs to check are exactly the ones it
     * publishes: that the amount equals the whole pre-payment balance, and that the resulting balance is
     * exactly zero.</p>
     *
     * <p><strong>Compare {@code resultingBalance} with {@code compareTo}, never {@code equals}.</strong> The
     * two are not the same test on a {@code BigDecimal}: {@code 0.00} and {@code 0} compare equal and are not
     * {@code equals}, because the scales differ.</p>
     *
     * @param transactionId        the generated sixteen-digit identifier from {@code :219}, zero-padded, as it
     *                             appears in the success message
     * @param amountPaid           {@code TRAN-AMT} from {@code :224}: the whole balance the account carried
     *                             before the payment, at scale two. Never a partial figure - the source has no
     *                             partial-payment path
     * @param resultingBalance     {@code ACCT-CURR-BAL} after {@code :234}: exactly zero, at scale two
     * @param originatingTimestamp {@code TRAN-ORIG-TS} from {@code :231}: twenty-six characters
     * @param processingTimestamp  {@code TRAN-PROC-TS} from {@code :232}: the identical twenty-six characters,
     *                             because {@code :230} generates one value and {@code :231}-{@code :232} move
     *                             it into both fields
     */
    public record PaymentReceipt(String transactionId, BigDecimal amountPaid, BigDecimal resultingBalance,
                                 String originatingTimestamp, String processingTimestamp) {

        /**
         * Renders the receipt without disclosing either money member.
         *
         * <p>The compiler-generated form emitted the amount and the resulting balance verbatim, through the
         * ordinary interpolation path that a parameterised log statement, an exception message, a debugger or a
         * telemetry capture takes - and an amount paired with an identifier is account-level financial
         * disclosure. Both are reduced to a sign and a scale, which is what actually diagnoses a fault here:
         * a wrong scale breaks the fixed-width record, and a wrong sign breaks the balance arithmetic.</p>
         *
         * <p>The identifier and the two timestamps survive verbatim because they are generated by this class
         * from digits and fixed separators. Neither can carry a submitted character, so neither opens a
         * log-forgery vector, and the identifier is in any case published to the operator by the success
         * message at {@code app/cbl/COBIL00C.cbl:527}-{@code :531}.</p>
         *
         * @return a rendering safe to write to any log sink
         */
        @Override
        public String toString() {
            return "PaymentReceipt[transactionId=" + transactionId
                    + ", amountPaid=" + shapeOfMoney(amountPaid)
                    + ", resultingBalance=" + shapeOfMoney(resultingBalance)
                    + ", originatingTimestamp=" + originatingTimestamp
                    + ", processingTimestamp=" + processingTimestamp + "]";
        }
    }

    /**
     * The screen {@code EXEC CICS SEND MAP('COBIL0A') MAPSET('COBIL00')} at
     * {@code app/cbl/COBIL00C.cbl:295}-{@code :301} would have painted.
     *
     * <p>An immutable projection of the {@code COBIL0AO} output map, its ten members taken from
     * {@code app/cpy-bms/COBIL00.CPY} with that copybook's declared widths. <strong>The map is consumed as a
     * field contract only</strong> - there is no BMS, no 3270 and no HTML anywhere in this class, and the
     * length, attribute and reserved members of each BMS quintuple have no counterpart because they describe
     * terminal mechanics rather than data.</p>
     *
     * <p>{@link #sendBillpayScreen} snapshots the mutable buffer into one of these on every send, so a
     * subsequent mutation cannot alter an already-sent screen. That immutability is what makes the balance echo
     * of {@code :193}-{@code :194} behave correctly on the declined and invalid branches, where the source
     * assigns the echo <em>after</em> the screen has gone to the terminal.</p>
     *
     * @param transactionName {@code TRNNAMEO}, {@code PIC X(4)}: always {@code CB00}
     * @param title01         {@code TITLE01O}, {@code PIC X(40)}: {@code CCDA-TITLE01} from
     *                        {@code app/cpy/COTTL01Y.cpy}, blank-padded exactly as declared
     * @param currentDate     {@code CURDATEO}, {@code PIC X(8)}: {@code MM/DD/YY} from {@code :328}-{@code :332}
     * @param programName     {@code PGMNAMEO}, {@code PIC X(8)}: always {@code COBIL00C}
     * @param title02         {@code TITLE02O}, {@code PIC X(40)}: {@code CCDA-TITLE02}, blank-padded
     * @param currentTime     {@code CURTIMEO}, {@code PIC X(8)}: {@code HH:MM:SS} from {@code :334}-{@code :338}
     * @param accountId       {@code ACTIDINI}, {@code PIC X(11)}: the account field, blank after a successful
     *                        write because {@code :524} runs {@code INITIALIZE-ALL-FIELDS} first
     * @param currentBalance  {@code CURBALI}, {@code PIC X(14)}: the balance on the
     *                        {@code +9999999999.99} mask, blank after a successful write for the same reason
     * @param confirmation    {@code CONFIRMI}, {@code PIC X(1)}: the confirmation gate as submitted
     * @param errorMessage    {@code ERRMSGO}, {@code PIC X(78)}: the byte-exact screen literal from
     *                        {@code WS-MESSAGE}, truncated to the receiving field's width at {@code :293}
     * @param messageKind     how {@code errorMessage} is meant to read, standing in for the colour attribute
     *                        of {@code :526}
     * @param cursor          where {@code SEND MAP ... CURSOR} would have parked the cursor
     */
    public record BillPaymentScreen(String transactionName, String title01, String currentDate,
                                    String programName, String title02, String currentTime,
                                    String accountId, String currentBalance, String confirmation,
                                    String errorMessage, MessageKind messageKind, CursorField cursor) {

        /**
         * Renders the screen without disclosing the account identifier or the balance.
         *
         * <p>Follows the convention {@code com.cardemo.model.dto.BillPaymentRequest} establishes, and for the
         * same two reasons. Disclosure: an account identifier and a balance together are exactly the pairing an
         * account-enumeration attempt needs. Log forgery: the account, balance and confirmation members are
         * bound from an untrusted request body, so a carriage return inside any of them could terminate a log
         * line and let the caller compose the next one.</p>
         *
         * <p>No submitted character appears in the output at all, which is a stronger guarantee than escaping
         * and a far easier one to verify. The six presentation members are omitted rather than shaped, because
         * they are constants of this class and carry no diagnostic signal. {@code errorMessage} survives
         * verbatim because it is always one of this class's own fixed literals, and it is the single most
         * useful thing in a diagnostic - it names the outcome in the source's own words.</p>
         *
         * @return a rendering containing no submitted character, safe to write to any log sink
         */
        @Override
        public String toString() {
            return "BillPaymentScreen[accountId=" + shapeOf(accountId)
                    + ", currentBalance=" + shapeOf(currentBalance)
                    + ", confirmation=" + codePointOf(confirmation)
                    + ", errorMessage=" + errorMessage
                    + ", messageKind=" + messageKind
                    + ", cursor=" + cursor
                    + ", header=<6 presentation members omitted>]";
        }
    }

    /**
     * Everything one pass through {@code app/cbl/COBIL00C.cbl} produced.
     *
     * <p>The source's turn ends in one of two ways and communicates entirely through a painted screen. A
     * programmatic caller needs the same information in a form it can branch on, so the outcome, the two
     * pseudo-conversational states, the attention key, the confirmation arm, the cursor target, the message and
     * its kind are all published alongside the screen itself - and a failure is <em>retained</em> rather than
     * thrown, so the caller sees the whole pass rather than only its first fault.</p>
     *
     * <p><strong>{@code outcome} is the member to branch on.</strong> It names all twenty-one distinguishable
     * endings, so no information the legacy screen displayed is lost to a collapsed status.</p>
     *
     * @param outcome            which of the twenty-one endings this pass reached
     * @param responseKind       whether a screen was sent or control was transferred
     * @param aidKey             the arm of {@code EVALUATE EIBAID} that was taken, or {@code null} on a first
     *                           display, where the source never evaluates the attention key
     * @param entryMode          the entry state this pass was invoked in
     * @param nextEntryMode      the state the source would have left in the COMMAREA for the next turn:
     *                           {@link EntryMode#REENTER} after a first display per {@code :113},
     *                           {@link EntryMode#ENTER} after a transfer per {@code :280}
     * @param screen             the screen last sent, or {@code null} when control was transferred instead
     * @param message            {@code WS-MESSAGE}: the byte-exact screen literal, before the truncation the
     *                           narrower map field imposes
     * @param messageKind        how {@code message} is meant to read
     * @param cursor             where the cursor would have been parked
     * @param sendCount          how many times {@code SEND-BILLPAY-SCREEN} ran. The source paints with
     *                           {@code ERASE}, so each send overwrites the last and only the final screen was
     *                           ever seen; the count is published because a pass that sends twice is following
     *                           a different route through the paragraphs than one that sends once
     * @param inputError         whether {@code WS-ERR-FLG} was set. Deliberately <em>not</em> the same as
     *                           {@code outcome != PAYMENT_SUCCESSFUL}: the prompt of {@code :236}-{@code :239}
     *                           sets no flag, so an unconfirmed pass reports no input error while still not
     *                           having moved any money
     * @param confirmationBranch which arm of the confirmation gate was taken, or {@code null} when the pass
     *                           never reached it
     * @param deepLinkApplied    whether the deep-link move of {@code :116}-{@code :121} was applied, which is
     *                           what makes preserved defect P5 - a sixteen-character transaction identifier
     *                           truncated to eleven characters and placed in the account field - observable
     *                           rather than merely documented
     * @param navigation         the navigation hint, or {@code null} when no transfer was resolved
     * @param receipt            the receipt, or {@code null} unless the payment completed
     * @param retainedFailure    the typed exception the source's failure path corresponds to, or {@code null}
     * @param returnTransactionId {@code TRANSID(WS-TRANID)} on the {@code EXEC CICS RETURN} at
     *                           {@code :146}-{@code :149}: {@code CB00} when the pass ended at the terminal,
     *                           {@code null} when it transferred instead
     */
    public record BillPaymentResult(PaymentOutcome outcome, ResponseKind responseKind, AidKey aidKey,
                                    EntryMode entryMode, EntryMode nextEntryMode, BillPaymentScreen screen,
                                    String message, MessageKind messageKind, CursorField cursor,
                                    int sendCount, boolean inputError,
                                    ConfirmationBranch confirmationBranch, boolean deepLinkApplied,
                                    Navigation navigation, PaymentReceipt receipt,
                                    CardDemoException retainedFailure, String returnTransactionId) {

        /**
         * Returns the retained failure, if the pass had one.
         *
         * @return the typed exception, or an empty optional when nothing failed
         */
        public Optional<CardDemoException> failure() {
            return Optional.ofNullable(retainedFailure);
        }

        /**
         * Returns the receipt, if the payment completed.
         *
         * <p>Present only for {@link PaymentOutcome#PAYMENT_SUCCESSFUL}. A pass whose transaction insert
         * succeeded but whose account rewrite then failed yields no receipt, because the shared transaction
         * rolls the insert back and no payment occurred.</p>
         *
         * @return the receipt, or an empty optional
         */
        public Optional<PaymentReceipt> receiptOptional() {
            return Optional.ofNullable(receipt);
        }

        /**
         * Renders the result without disclosing anything the nested members protect.
         *
         * <p>The compiler-generated form would have emitted the screen and the receipt through their own
         * overrides, which are safe, but it would also have emitted the retained exception - whose cause chain
         * can carry a provider-generated message quoting row values. Only the exception's type is reported, and
         * the screen and receipt are delegated to their own disclosure-free renderings.</p>
         *
         * @return a rendering safe to write to any log sink
         */
        @Override
        public String toString() {
            return "BillPaymentResult[outcome=" + outcome
                    + ", responseKind=" + responseKind
                    + ", entryMode=" + entryMode
                    + ", nextEntryMode=" + nextEntryMode
                    + ", aidKey=" + aidKey
                    + ", confirmationBranch=" + confirmationBranch
                    + ", deepLinkApplied=" + deepLinkApplied
                    + ", messageKind=" + messageKind
                    + ", cursor=" + cursor
                    + ", sendCount=" + sendCount
                    + ", inputError=" + inputError
                    + ", message=" + message
                    + ", screen=" + screen
                    + ", receipt=" + receipt
                    + ", retainedFailure="
                    + (retainedFailure == null ? "absent" : retainedFailure.getClass().getSimpleName())
                    + ", returnTransactionId=" + returnTransactionId + "]";
        }
    }

    /* ===================================================================================================
     * Internal working storage. One instance per invocation, never shared, never a bean field.
     * ================================================================================================ */

    /**
     * The {@code WORKING-STORAGE SECTION} and {@code LINKAGE SECTION} of {@code app/cbl/COBIL00C.cbl}, scoped
     * to one invocation.
     *
     * <p><strong>This is the mechanism that makes the bean stateless and thread-safe.</strong> A CICS program
     * owns its working storage for the life of a task; a Spring singleton is shared by every concurrent request,
     * so the translated storage lives here and is created fresh on entry. Nothing that the source keeps in
     * working storage - not {@code WS-ERR-FLG}, not {@code WS-TRAN-ID-NUM}, not {@code WS-CURR-BAL}, not
     * {@code WS-TIMESTAMP}, not the confirmation flag - is a field of the enclosing service.</p>
     *
     * <p>The fields are package-private and mutable by design: they are a faithful translation of a COBOL data
     * division, which the procedure division mutates in place, and accessors around single-invocation storage
     * would obscure the correspondence the traceability matrix has to be provable against. The class is
     * {@code private static final} so the visibility cannot leak beyond this file, and it is a class rather than
     * a record precisely because the source mutates every one of these items.</p>
     *
     * <p>No {@code toString} is declared. The inherited identity rendering carries no field value, so the
     * default is already disclosure-free - which matters, because this type holds the account identifier, the
     * balance and the whole transaction image at once.</p>
     */
    private static final class PaymentContext {

        /**
         * {@code SET USR-MODIFIED-NO TO TRUE} at {@code app/cbl/COBIL00C.cbl:102} sets the condition name whose
         * {@code VALUE} is {@code 'N'} on {@code WS-USR-MODIFIED PIC X(01)} at {@code :48}-{@code :50}.
         *
         * <p>Declared alone: {@code USR-MODIFIED-YES} exists in the source but is never set, so a Java constant
         * for it would be dead code that no citation could justify.</p>
         */
        private static final String USR_MODIFIED_NO = "N";

        /**
         * {@code MOVE ZEROS TO CDEMO-PGM-CONTEXT} at {@code app/cbl/COBIL00C.cbl:280}, which is the value of
         * {@code 88 CDEMO-PGM-ENTER} on {@code CDEMO-PGM-CONTEXT PIC 9(01)} at
         * {@code app/cpy/COCOM01Y.cpy:29}-{@code :31}.
         *
         * <p>Declared alone for the same reason: this program never sets the re-enter value into the field it is
         * handing on, so a constant for it would be dead.</p>
         */
        private static final int PGM_CONTEXT_ENTER = 0;

        /** The bound request, or {@code null} on the no-context path. Read by {@link #receiveBillpayScreen}. */
        private final BillPaymentRequest request;

        /** {@code EIBAID} as the caller stated it, classified once by {@link #classifyAttentionKey}. */
        private final String attentionIdentifier;

        /** Which of the three source entry paths this invocation takes. */
        private final EntryMode entryMode;

        /**
         * {@code CDEMO-CB00-TRN-SELECTED}, {@code PIC X(16)} at {@code app/cbl/COBIL00C.cbl:71}: the deep-link
         * value that {@code :116}-{@code :121} moves into the eleven-character account field.
         */
        private final String selectedTransactionIdentifier;

        /** The symbolic map, standing in for {@code COBIL0AI} and {@code COBIL0AO}. */
        private final ScreenBuffer screen = new ScreenBuffer();

        /** {@code TRAN-RECORD} from {@code app/cpy/CVTRA05Y.cpy}: the three-hundred-and-fifty-byte image. */
        private final TransactionBuffer tranRecord = new TransactionBuffer();

        /** {@code WS-ERR-FLG PIC X(01)} at {@code :43}-{@code :45}, as its {@code ERR-FLG-ON} condition. */
        private boolean errFlagOn;

        /**
         * {@code WS-USR-MODIFIED} at {@code :48}-{@code :50}.
         *
         * <p><strong>Parity artefact P1, classified Low - an intentional no-op, tracked in
         * {@code DECISION_LOG.md}.</strong> {@code :102} assigns it and no statement anywhere in the five
         * hundred and seventy-two lines ever tests it. It is retained because deleting the assignment would
         * break the one-to-one paragraph correspondence that the scope-coverage gate reads, and because Rule 1
         * Clause B forbids <em>untracked</em> dead code rather than a cited, justified parity artefact. Never
         * read here either - deliberately, because reading it would be the divergence.</p>
         */
        private String userModified;

        /**
         * {@code CONF-PAY-YES} on {@code WS-CONF-PAY-FLG} at {@code :51}-{@code :53}.
         *
         * <p>Initialised pessimistically by {@code SET CONF-PAY-NO TO TRUE} at {@code :156} and set true only by
         * the two explicit yes arms, which is what stops a blank confirmation from moving money.</p>
         */
        private boolean confPayYes;

        /** {@code WS-MESSAGE PIC X(80)} at {@code :39}: the byte-exact screen literal awaiting a send. */
        private String wsMessage = "";

        /** {@code WS-CURR-BAL PIC +9999999999.99} at {@code :56}: the fourteen-character balance echo. */
        private String wsCurrBal;

        /** {@code WS-TIMESTAMP} from {@code app/cpy/CSDAT01Y.cpy}: twenty-six characters. */
        private String wsTimestamp;

        /** {@code WS-RESP-CD PIC S9(09) COMP} at {@code :46}: the response of the last CICS command. */
        private int wsRespCd;

        /** {@code WS-REAS-CD PIC S9(09) COMP} at {@code :47}: its reason code. */
        private int wsReasCd;

        /** {@code ACCT-ID PIC 9(11)} from {@code app/cpy/CVACT01Y.cpy}, or {@code null} if unusable. */
        private Long acctId;

        /** {@code XREF-ACCT-ID PIC 9(11)} from {@code app/cpy/CVACT03Y.cpy}: the same value, per {@code :171}. */
        private Long xrefAcctId;

        /** {@code ACCT-CURR-BAL PIC S9(10)V99}: the authoritative balance, at scale two. */
        private BigDecimal acctCurrBal = ZERO_BALANCE;

        /** {@code ACCOUNT-RECORD}: the row read for update at {@code :345}-{@code :354}. */
        private Account accountRecord;

        /** {@code CARD-XREF-RECORD}: the row read through the alternate index at {@code :410}-{@code :418}. */
        private CardCrossReference crossReference;

        /** Whether {@code STARTBR} succeeded and {@code ENDBR} has not yet run. */
        private boolean browseOpen;

        /** The last screen actually sent, snapshotted so a later mutation cannot alter it. */
        private BillPaymentScreen lastSentScreen;

        /** How many times {@code SEND-BILLPAY-SCREEN} ran during the pass. */
        private int sendCount;

        /** Whether the pass ended at the terminal or transferred. */
        private ResponseKind responseKind;

        /** The arm of {@code EVALUATE EIBAID} taken, or {@code null} on a first display. */
        private AidKey aidKey;

        /** The state the next turn would have been entered in. */
        private EntryMode nextEntryMode;

        /** The arm of {@code EVALUATE CONFIRMI} taken, or {@code null} if the gate was never reached. */
        private ConfirmationBranch confirmationBranch;

        /** How the pending message is meant to read. */
        private MessageKind messageKind = MessageKind.NONE;

        /** Which of the twenty-one endings the pass reached. */
        private PaymentOutcome outcome;

        /** The first typed failure of the pass, retained rather than thrown. */
        private CardDemoException pendingFailure;

        /**
         * The provider exception behind the current response code, cached so the typed exception can preserve
         * it as its cause. Cleared at the start of every verb executor, so a later success cannot inherit an
         * earlier fault's cause.
         */
        private RuntimeException ioFailureCause;

        /** {@code CDEMO-TO-PROGRAM} at {@code :281}: the resolved transfer target. */
        private String toProgram;

        /**
         * {@code CDEMO-FROM-PROGRAM} at {@code :129}-{@code :133} on input, {@code :279} on output.
         *
         * <p><strong>Parity artefact P9, classified Low.</strong> It arrives in the COMMAREA in the source and
         * has no Java counterpart, so it is always absent on entry and the attention-key-three arm always
         * resolves the main menu. Both arms of {@code :129}-{@code :133} are nonetheless implemented, because
         * deleting the unreachable one would break the paragraph correspondence.</p>
         */
        private String fromProgram;

        /** {@code CDEMO-FROM-TRANID} at {@code :278}. */
        private String fromTransactionId;

        /** {@code CDEMO-PGM-CONTEXT} at {@code :280}. */
        private int programContext;

        /** {@code TRANSID(WS-TRANID)} on the {@code EXEC CICS RETURN} at {@code :146}-{@code :149}. */
        private String returnTransactionId;

        /** Whether the deep-link truncation of {@code :116}-{@code :121} was applied on this pass. */
        private boolean deepLinkApplied;

        /** The receipt, populated only when the payment completed end to end. */
        private PaymentReceipt receipt;

        /**
         * {@code WS-TRAN-AMT PIC +99999999.99} at {@code app/cbl/COBIL00C.cbl:55}.
         *
         * <p><strong>Parity artefact P2, classified Low - an intentional no-op, tracked in
         * {@code DECISION_LOG.md}.</strong> The item is declared in working storage and referenced by no
         * statement in the procedure division; the amount echo the program actually performs goes through
         * {@code WS-CURR-BAL}. Retained, seeded and never read, for the reason given on {@link #userModified}.
         * Deleting it would misrepresent the data division that the traceability matrix cites.</p>
         */
        private String unreferencedTransactionAmount;

        /**
         * {@code WS-TRAN-DATE PIC X(08) VALUE '00/00/00'} at {@code app/cbl/COBIL00C.cbl:58}.
         *
         * <p><strong>Parity artefact P2, classified Low - an intentional no-op, tracked in
         * {@code DECISION_LOG.md}.</strong> Declared with an initial value and referenced by no statement; the
         * dates the program actually renders come from {@code app/cpy/CSDAT01Y.cpy}. Retained, seeded with its
         * declared {@code VALUE} and never read.</p>
         */
        private String unreferencedTransactionDate;

        /**
         * Establishes the working storage a task begins with.
         *
         * <p>The two parity artefacts of P2 are seeded here so that the {@code VALUE} clause of {@code :58} is
         * represented rather than merely commented, and the initial balance is the scale-two zero an
         * unpopulated numeric record area holds - which is what {@code :193} would copy on the declined branch,
         * where no account was ever read.</p>
         *
         * @param request                       the bound request, or {@code null} on the no-context path
         * @param attentionIdentifier           the caller's stated attention key
         * @param entryMode                     which of the three source entry paths to take
         * @param selectedTransactionIdentifier the deep-link value, or {@code null}
         */
        PaymentContext(final BillPaymentRequest request, final String attentionIdentifier,
                       final EntryMode entryMode, final String selectedTransactionIdentifier) {
            this.request = request;
            this.attentionIdentifier = attentionIdentifier;
            this.entryMode = entryMode;
            this.selectedTransactionIdentifier = selectedTransactionIdentifier;
            this.unreferencedTransactionAmount = UNREFERENCED_TRANSACTION_AMOUNT_MASK;
            this.unreferencedTransactionDate = UNREFERENCED_TRANSACTION_DATE;
        }

        /**
         * Projects the finished pass onto the published result.
         *
         * <p>Two members are conditional, and both conditions mirror the source rather than adding logic. The
         * navigation hint exists only when control was transferred, because {@code RETURN-TO-PREV-SCREEN} is
         * the only paragraph that resolves a target. The return transaction identifier exists only when it was
         * <em>not</em> transferred: {@code :146}-{@code :149} is written unconditionally, but
         * {@code EXEC CICS XCTL} at {@code :281} never returns, so a transferred pass never reaches it.</p>
         *
         * @return the immutable result of the pass
         */
        BillPaymentResult toResult() {
            final boolean transferred = this.responseKind == ResponseKind.TRANSFER;
            final Navigation navigation = transferred
                    ? new Navigation(this.fromTransactionId, this.fromProgram, this.toProgram,
                            this.programContext)
                    : null;
            return new BillPaymentResult(this.outcome, this.responseKind, this.aidKey, this.entryMode,
                    this.nextEntryMode, this.lastSentScreen, this.wsMessage, this.messageKind,
                    this.screen.cursor, this.sendCount, this.errFlagOn, this.confirmationBranch,
                    this.deepLinkApplied, navigation, this.receipt, this.pendingFailure,
                    transferred ? null : this.returnTransactionId);
        }
    }

    /**
     * The symbolic map, standing in for {@code COBIL0AI} and {@code COBIL0AO} of
     * {@code app/cpy-bms/COBIL00.CPY}.
     *
     * <p>One buffer serves both directions, which is faithful rather than a shortcut: a BMS-generated symbolic
     * map declares the output area as a redefinition of the input area, so the two share storage and
     * {@code MOVE LOW-VALUES TO COBIL0AO} at {@code app/cbl/COBIL00C.cbl:114} clears what a subsequent
     * {@code RECEIVE} would read.</p>
     *
     * <p>Only the ten data members of the copybook appear. The length, attribute, redefined-attribute-alias and
     * reserved members of each BMS quintuple describe terminal mechanics - field width on the wire, colour,
     * intensity, cursor position - and have no counterpart over JSON; the cursor request they carry survives as
     * {@link #cursor} and the colour attribute as {@link #messageKind}.</p>
     *
     * <p>No {@code toString} is declared, so the inherited identity rendering keeps the account identifier and
     * the balance out of any output. {@link #toScreen()} is what produces a shareable value, and the record it
     * builds carries the disclosure-free rendering.</p>
     */
    private static final class ScreenBuffer {

        /** {@code TRNNAMEI}/{@code TRNNAMEO}, {@code PIC X(4)}. */
        private String transactionName;

        /** {@code TITLE01I}/{@code TITLE01O}, {@code PIC X(40)}. */
        private String title01;

        /** {@code CURDATEI}/{@code CURDATEO}, {@code PIC X(8)}. */
        private String currentDate;

        /** {@code PGMNAMEI}/{@code PGMNAMEO}, {@code PIC X(8)}. */
        private String programName;

        /** {@code TITLE02I}/{@code TITLE02O}, {@code PIC X(40)}. */
        private String title02;

        /** {@code CURTIMEI}/{@code CURTIMEO}, {@code PIC X(8)}. */
        private String currentTime;

        /** {@code ACTIDINI}, {@code PIC X(11)}: the account identifier the operator types. */
        private String accountId;

        /** {@code CURBALI}, {@code PIC X(14)}: the balance echo on the {@code +9999999999.99} mask. */
        private String currentBalance;

        /** {@code CONFIRMI}, {@code PIC X(1)}: the confirmation gate. */
        private String confirmation;

        /** {@code ERRMSGI}/{@code ERRMSGO}, {@code PIC X(78)}: the message the operator reads. */
        private String errorMessage;

        /** Standing in for {@code ERRMSGC}, the message field's colour attribute set at {@code :526}. */
        private MessageKind messageKind;

        /** Standing in for the {@code MOVE -1 TO <field>L} cursor idiom and {@code SEND MAP ... CURSOR}. */
        private CursorField cursor;

        /**
         * Establishes the map area a task begins with.
         *
         * <p>Every data member starts at the {@code null} that stands for {@code LOW-VALUES}, which is the
         * state a BMS symbolic map occupies before the first {@code SEND} or {@code RECEIVE} - so a pass that
         * reads a member before populating it sees low values, exactly as the source would. The two attribute
         * analogues start at their neutral values because the source's corresponding attribute bytes are only
         * ever set explicitly, at {@code :526} for the colour and by the cursor idiom for the position.</p>
         */
        ScreenBuffer() {
            this.messageKind = MessageKind.NONE;
            this.cursor = CursorField.NONE;
        }

        /**
         * {@code MOVE LOW-VALUES TO COBIL0AO} at {@code app/cbl/COBIL00C.cbl:114}.
         *
         * <p>Every member goes to the {@code null} that stands for low values, and the two attribute analogues
         * return to their defaults. Because the input and output areas share storage, this is also what leaves
         * the confirmation gate at low values on a first display, so a deep-linked pass takes the
         * {@link ConfirmationBranch#LOW_VALUES} arm and ends on the prompt rather than moving money.</p>
         */
        void moveLowValues() {
            this.transactionName = null;
            this.title01 = null;
            this.currentDate = null;
            this.programName = null;
            this.title02 = null;
            this.currentTime = null;
            this.accountId = null;
            this.currentBalance = null;
            this.confirmation = null;
            this.errorMessage = null;
            this.messageKind = MessageKind.NONE;
            this.cursor = CursorField.NONE;
        }

        /**
         * {@code EXEC CICS RECEIVE MAP('COBIL0A') ... INTO(COBIL0AI)} at
         * {@code app/cbl/COBIL00C.cbl:308}-{@code :314}.
         *
         * <p><strong>Three members are bound and seven are not, deliberately.</strong> The six presentation
         * members are overwritten by {@code POPULATE-HEADER-INFO} on every send and the message field by
         * {@code :293}, so binding them would be inert - and binding the submitted balance would be worse than
         * inert: the payment amount comes from {@code ACCT-CURR-BAL} at {@code :224}, read from the account
         * record, so a submitted figure must never be able to influence it. The balance is bound only because
         * the source's shared storage would carry whatever the terminal returned, and it is overwritten by the
         * authoritative echo at {@code :194} before any send that matters.</p>
         *
         * @param source the bound request
         */
        void bindFrom(final BillPaymentRequest source) {
            this.accountId = source.accountId();
            this.currentBalance = source.currentBalance();
            this.confirmation = source.confirmation();
        }

        /**
         * Snapshots the buffer into the immutable screen a send produces.
         *
         * <p>Taking a copy rather than publishing the buffer is what makes {@code :193}-{@code :194} behave
         * correctly: on the declined and invalid branches the source assigns the balance echo <em>after</em> the
         * screen has already gone to the terminal, so the sent screen must not see it.</p>
         *
         * @return an immutable projection of the current buffer
         */
        BillPaymentScreen toScreen() {
            return new BillPaymentScreen(this.transactionName, this.title01, this.currentDate,
                    this.programName, this.title02, this.currentTime, this.accountId, this.currentBalance,
                    this.confirmation, this.errorMessage, this.messageKind, this.cursor);
        }
    }

    /**
     * {@code TRAN-RECORD} of {@code app/cpy/CVTRA05Y.cpy}: the three-hundred-and-fifty-byte transaction image.
     *
     * <p>The geometry is load-bearing, and it sums exactly: {@code TRAN-ID X(16)} plus
     * {@code TRAN-TYPE-CD X(02)} plus {@code TRAN-CAT-CD 9(04)} plus {@code TRAN-SOURCE X(10)} plus
     * {@code TRAN-DESC X(100)} plus {@code TRAN-AMT S9(09)V99} at eleven digits plus
     * {@code TRAN-MERCHANT-ID 9(09)} plus {@code TRAN-MERCHANT-NAME X(50)} plus
     * {@code TRAN-MERCHANT-CITY X(50)} plus {@code TRAN-MERCHANT-ZIP X(10)} plus
     * {@code TRAN-CARD-NUM X(16)} plus {@code TRAN-ORIG-TS X(26)} plus {@code TRAN-PROC-TS X(26)} plus
     * {@code FILLER X(20)} is three hundred and fifty. Every alphanumeric member is held blank-padded to its
     * declared picture, which is why the assignments run through {@link #padRight}.</p>
     *
     * <p>{@code FILLER X(20)} has no member here: it is unnamed in the copybook, no statement addresses it, and
     * the relational target has no column for it. It reappears only where a fixed-width image is emitted, and
     * no such image is emitted from this class.</p>
     *
     * <p>A mutable buffer rather than the entity, because the write sequence at {@code :212}-{@code :232}
     * addresses the record thirteen times in a fixed order and moves a browse key through the very field the
     * identifier will later occupy. The entity's constructor validates every width, so it can only be built
     * once the record is complete - which is exactly what {@link #toEntity()} does, at the moment
     * {@code EXEC CICS WRITE} would have taken the record.</p>
     *
     * <p>No {@code toString} is declared: this type holds a sixteen-digit card number and an amount, and the
     * inherited identity rendering discloses neither.</p>
     */
    private static final class TransactionBuffer {

        /** {@code TRAN-ID}, {@code PIC X(16)}, bytes 1-16. Also the browse key between {@code :212}
         * and {@code :219}. */
        private String transactionId;

        /** {@code TRAN-TYPE-CD}, {@code PIC X(02)}, bytes 17-18. */
        private String typeCode;

        /** {@code TRAN-CAT-CD}, {@code PIC 9(04)}, bytes 19-22. Numeric, so {@code MOVE 2} stores {@code 0002}. */
        private int categoryCode;

        /** {@code TRAN-SOURCE}, {@code PIC X(10)}, bytes 23-32. */
        private String source;

        /** {@code TRAN-DESC}, {@code PIC X(100)}, bytes 33-132. */
        private String description;

        /** {@code TRAN-AMT}, {@code PIC S9(09)V99}, bytes 133-143: {@code NUMERIC(11,2)}. */
        private BigDecimal amount;

        /** {@code TRAN-MERCHANT-ID}, {@code PIC 9(09)}, bytes 144-152. */
        private long merchantId;

        /** {@code TRAN-MERCHANT-NAME}, {@code PIC X(50)}, bytes 153-202. */
        private String merchantName;

        /** {@code TRAN-MERCHANT-CITY}, {@code PIC X(50)}, bytes 203-252. */
        private String merchantCity;

        /** {@code TRAN-MERCHANT-ZIP}, {@code PIC X(10)}, bytes 253-262. */
        private String merchantZip;

        /** {@code TRAN-CARD-NUM}, {@code PIC X(16)}, bytes 263-278. Never logged and never serialized. */
        private String cardNumber;

        /** {@code TRAN-ORIG-TS}, {@code PIC X(26)}, bytes 279-304. */
        private String originatingTimestamp;

        /** {@code TRAN-PROC-TS}, {@code PIC X(26)}, bytes 305-330. */
        private String processingTimestamp;

        /**
         * Establishes the record area a task begins with, in the state {@code INITIALIZE} would leave it.
         */
        TransactionBuffer() {
            initialize();
        }

        /**
         * {@code INITIALIZE TRAN-RECORD} at {@code app/cbl/COBIL00C.cbl:218}.
         *
         * <p>Alphanumeric members go to blanks at their declared widths and numeric members to zero, which is
         * what the COBOL verb does. <strong>Its position in the write sequence is load-bearing.</strong> It runs
         * <em>after</em> {@code :216} has copied the browse key out of {@code TRAN-ID} into the work field and
         * <em>before</em> {@code :219} moves the incremented identifier back in, so it is what clears the whole
         * record that {@code READPREV} loaded at {@code :476}. Moving it earlier would destroy the browse key;
         * moving it later would erase the freshly assigned identifier.</p>
         */
        void initialize() {
            this.transactionId = spaces(TRANSACTION_ID_WIDTH);
            this.typeCode = spaces(TRANSACTION_TYPE_WIDTH);
            this.categoryCode = 0;
            this.source = spaces(TransactionSource.FIELD_LENGTH);
            this.description = spaces(TRANSACTION_DESCRIPTION_WIDTH);
            this.amount = ZERO_BALANCE;
            this.merchantId = 0L;
            this.merchantName = spaces(MERCHANT_NAME_WIDTH);
            this.merchantCity = spaces(MERCHANT_CITY_WIDTH);
            this.merchantZip = spaces(MERCHANT_ZIP_WIDTH);
            this.cardNumber = spaces(CARD_NUMBER_WIDTH);
            this.originatingTimestamp = spaces(TIMESTAMP_WIDTH);
            this.processingTimestamp = spaces(TIMESTAMP_WIDTH);
        }

        /**
         * {@code EXEC CICS READPREV ... INTO(TRAN-RECORD)} at {@code app/cbl/COBIL00C.cbl:474}-{@code :482}.
         *
         * <p>The whole record is loaded, not merely its key, because that is what {@code INTO} does - and it is
         * why {@code INITIALIZE TRAN-RECORD} at {@code :218} is necessary before the new record is assembled.
         * Every member is brought to its declared width, so a stored value that a different producer left
         * unpadded cannot silently narrow the record.</p>
         *
         * @param previous the row the descending browse positioned on
         */
        void loadFrom(final Transaction previous) {
            this.transactionId = padRight(previous.getTransactionId(), TRANSACTION_ID_WIDTH);
            this.typeCode = padRight(previous.getTypeCode(), TRANSACTION_TYPE_WIDTH);
            this.categoryCode = previous.getCategoryCode() == null ? 0 : previous.getCategoryCode().intValue();
            this.source = padRight(previous.getTransactionSource(), TransactionSource.FIELD_LENGTH);
            this.description = padRight(previous.getDescription(), TRANSACTION_DESCRIPTION_WIDTH);
            this.amount = normaliseMoney(previous.getAmount());
            this.merchantId = previous.getMerchantId() == null ? 0L : previous.getMerchantId().longValue();
            this.merchantName = padRight(previous.getMerchantName(), MERCHANT_NAME_WIDTH);
            this.merchantCity = padRight(previous.getMerchantCity(), MERCHANT_CITY_WIDTH);
            this.merchantZip = padRight(previous.getMerchantZip(), MERCHANT_ZIP_WIDTH);
            this.cardNumber = padRight(previous.getCardNumber(), CARD_NUMBER_WIDTH);
            this.originatingTimestamp = padRight(previous.getOrigTs(), TIMESTAMP_WIDTH);
            this.processingTimestamp = padRight(previous.getProcTs(), TIMESTAMP_WIDTH);
        }

        /**
         * Builds the row that {@code EXEC CICS WRITE DATASET('TRANSACT') FROM(TRAN-RECORD)} at
         * {@code app/cbl/COBIL00C.cbl:512}-{@code :520} would have written.
         *
         * <p>Deliberately built at the moment of the write rather than incrementally. The entity validates
         * every picture width in its constructor, so it cannot represent the half-assembled states the source
         * legitimately passes through - notably the interval between {@code :212} and {@code :219}, when the
         * identifier field holds a browse sentinel rather than an identifier.</p>
         *
         * <p>No optimistic-locking version is set: the row is new, so the provider assigns the initial counter
         * itself, and a value supplied here would make Spring Data route the insert as a merge.</p>
         *
         * @return the transaction to insert
         * @throws IllegalArgumentException when any member fails the entity's own width or range contract; the
         *         caller translates it into the source's {@code WHEN OTHER} arm
         */
        Transaction toEntity() {
            return new Transaction(this.transactionId, this.typeCode, Integer.valueOf(this.categoryCode),
                    this.source, this.description, this.amount, Long.valueOf(this.merchantId),
                    this.merchantName, this.merchantCity, this.merchantZip, this.cardNumber,
                    this.originatingTimestamp, this.processingTimestamp);
        }
    }
}
