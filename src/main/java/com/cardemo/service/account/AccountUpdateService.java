/*
 * ******************************************************************
 * Program     : AccountUpdateService.java
 * Application : CardDemo
 * Type        : Spring Service Bean (online)
 * Function    : Account update - dual-dataset write with snapshot
 *               change detection for CICS transaction CAUP.
 * Source      : app/cbl/COACTUPC.cbl (4,236 lines, 88 paragraphs) @ 7756d89
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
package com.cardemo.service.account;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.ConcurrentUpdateException;
import com.cardemo.exception.DataIntegrityException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.AccountUpdateRequest;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.FileStatusMapper;
import com.cardemo.service.shared.ValidationLookupService;

/**
 * Account update: the Java replacement for CICS program {@code COACTUPC}, transaction {@code CAUP}.
 *
 * <p>Every citation of the form {@code :NNN} in this class and in its members refers to a line of
 * {@code app/cbl/COACTUPC.cbl} at anchor commit {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}
 * (short {@code 7756d89}). That file carries a carriage return on every one of its 4,236 lines; the
 * line numbers used here are the CR-stripped logical numbers, which after stripping coincide with the
 * physical ones. Citations of other artefacts always name the path in full.
 *
 * <h2>1. What it does</h2>
 *
 * <p>It validates a complete account-and-customer update payload, detects concurrent modification
 * against a caller-supplied snapshot of the values the user was shown, and then writes the account
 * record and the customer record <strong>atomically</strong>. The legacy program is the largest in the
 * frozen corpus and drives a pseudo-conversational 3270 screen; this bean keeps the program's control
 * flow and its outcomes while discarding the terminal. Concretely it:
 *
 * <ul>
 *   <li>resolves an eleven-digit account filter through the card cross-reference to a customer
 *       identifier, then reads the account master and the customer master ({@code 9000-READ-ACCT} at
 *       {@code :3608} and the three read paragraphs it performs);</li>
 *   <li>captures the "as displayed" snapshot ({@code 9500-STORE-FETCHED-DATA} at {@code :3801});</li>
 *   <li>edits all fifty-four screen inputs in the source's order, field by field
 *       ({@code 1200-EDIT-MAP-INPUTS} at {@code :1429});</li>
 *   <li>decides whether the user actually changed anything ({@code 1205-COMPARE-OLD-NEW} at
 *       {@code :1681});</li>
 *   <li>dispatches on the pseudo-conversational marker and the attention identifier
 *       ({@code 2000-DECIDE-ACTION} at {@code :2562});</li>
 *   <li>re-reads both records for update, re-checks the snapshot against the live rows, and rewrites
 *       them in one unit of work ({@code 9600-WRITE-PROCESSING} at {@code :3888} and
 *       {@code 9700-CHECK-CHANGE-IN-REC} at {@code :4109}).</li>
 *   </ul>
 *
 * <p>It performs no HTTP concern of its own. It returns {@link AccountUpdateResult}, and the
 * controller decides the status code; there is deliberately no {@code @ExceptionHandler} and no
 * {@code @ControllerAdvice} here.
 *
 * <h2>2. How to build, run and test</h2>
 *
 * <p>Build and test the whole module with {@code ./mvnw -B -ntp clean verify} from the repository
 * root, on JDK 25 with Maven 3.9.11. The compiler runs with {@code -Xlint:all -Werror} and
 * {@code failOnWarning}, so any warning fails the build. A run that needs the environment sources the local
 * file inside a subshell that also carries the command,
 * {@code ( set -a; . ./.env; set +a; ./mvnw -B -ntp verify )}, rather than exporting it into the shell where
 * every later child would inherit it. Unit tests for this bean live in
 * {@code src/test/java/com/cardemo/unit/service/}; they need no container, because every collaborator
 * is constructor-injected and the clock is injected too. Integration coverage that touches PostgreSQL
 * lives in {@code src/test/java/com/cardemo/integration/}, which does need a container runtime.
 *
 * <h2>3. Key configurations and defaults</h2>
 *
 * <p>This bean reads no property directly - there is no {@code @Value} and no {@code System.getenv}
 * anywhere in it. Its behaviour nevertheless depends on four settings owned elsewhere:
 *
 * <ul>
 *   <li>{@code spring.jpa.open-in-view: false} - every entity this bean touches is read and written
 *       inside a transaction, so nothing is lazily loaded after the boundary closes;</li>
 *   <li>{@code spring.jpa.hibernate.ddl-auto: validate} - the column widths the entities declare must
 *       match the Flyway schema, which is what makes the {@code PIC} widths reproduced here
 *       meaningful;</li>
 *   <li>the injected {@code java.time.Clock} - the only source of "now", used for the recurring screen
 *       header of {@code 3100-SCREEN-INIT} ({@code :2668}). No no-argument
 *       {@code LocalDateTime.now()} call exists in this class;</li>
 *   <li>the three classpath JSON lookup resources loaded by
 *       {@code com.cardemo.service.shared.ValidationLookupService} - the North American area codes,
 *       the state codes and the state-plus-ZIP-prefix pairs of {@code app/cpy/CSLKPCDY.cpy}.</li>
 *   </ul>
 *
 * <p>Defaults inside this class are all COBOL literals rather than configuration: the transaction
 * identifier {@code CAUP}, the program name {@code COACTUPC}, the mapset {@code COACTUP} and map
 * {@code CACTUPA}, the file names {@code ACCTDAT}, {@code CUSTDAT} and {@code CXACAIX}, and the two
 * screen titles. Each is hoisted to a documented constant citing its {@code WS-LITERALS} line.
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <table>
 *   <caption>Outcomes, the type each raises, and what to do about it</caption>
 *   <tr><th>Failure mode</th><th>Type or outcome</th><th>Remediation</th></tr>
 *   <tr>
 *     <td>Account filter absent, blank or not an eleven-digit non-zero number
 *         ({@code 1210-EDIT-ACCOUNT}, {@code :1783})</td>
 *     <td>{@code ValidationException} with field {@code accountId}</td>
 *     <td>Supply exactly eleven digits, not all zeroes.</td>
 *   </tr>
 *   <tr>
 *     <td>Any of the fifty-four field edits rejects its input
 *         ({@code 1200-EDIT-MAP-INPUTS}, {@code :1429})</td>
 *     <td>{@code ValidationException}; the per-field state is carried on the result as
 *         {@link FieldState}</td>
 *     <td>Read {@link AccountUpdateResult#errorMessage()} - it is the legacy message for the first
 *         failing field - and the {@link AccountUpdateResult#fieldAttributes()} list for the rest.</td>
 *   </tr>
 *   <tr>
 *     <td>No cross-reference row for the account ({@code 9200-GETCARDXREF-BYACCT}, {@code :3650})</td>
 *     <td>{@code RecordNotFoundException} with record type {@code CARD-XREF-RECORD}</td>
 *     <td>Seed the cross-reference; the account cannot be updated without a customer identifier.</td>
 *   </tr>
 *   <tr>
 *     <td>Account or customer master row missing ({@code :3701}, {@code :3752})</td>
 *     <td>{@code RecordNotFoundException}</td>
 *     <td>See defect D2 below: the source's own guards for these two are dead, so the request
 *         continues and the failure surfaces later. Check the seeded data.</td>
 *   </tr>
 *   <tr>
 *     <td>Database read or write error ({@code WHEN OTHER} on any of the four I/O paragraphs)</td>
 *     <td>{@code FileAccessException} carrying the expanded four-character status</td>
 *     <td>Check connectivity and the Flyway schema; the cause is chained, never swallowed.</td>
 *   </tr>
 *   <tr>
 *     <td>Account row could not be locked ({@code :3907-3915})</td>
 *     <td>{@code ConcurrentUpdateException} with outcome {@code COULD_NOT_LOCK_ACCOUNT}; marker
 *         {@link ChangeAction#CHANGES_OKAYED_LOCK_ERROR}</td>
 *     <td>Retry; another transaction holds the pessimistic lock.</td>
 *   </tr>
 *   <tr>
 *     <td>Customer row could not be locked ({@code :3934-3942})</td>
 *     <td><strong>Reported as success.</strong> See defect D1 - this is preserved legacy behaviour,
 *         not a bug in this class</td>
 *     <td>Nothing was written. Inspect the log line tagged {@code customer-lock-failure} and re-submit
 *         the update.</td>
 *   </tr>
 *   <tr>
 *     <td>The live rows no longer match the snapshot ({@code 9700}, {@code :4109})</td>
 *     <td>{@code ConcurrentUpdateException} with outcome {@code DATA_CHANGED_BEFORE_UPDATE}; marker
 *         {@link ChangeAction#SHOW_DETAILS}</td>
 *     <td>Re-fetch with {@link #fetchForUpdate(String)}, show the current values, and resubmit with a
 *         fresh snapshot.</td>
 *     </tr>
 *   <tr>
 *     <td>Snapshot group absent from the request</td>
 *     <td>{@code ValidationException} with field {@code oldDetails}</td>
 *     <td>A stateless caller must echo the snapshot; a missing one is rejected rather than treated as
 *         "no change detected".</td>
 *   </tr>
 *   <tr>
 *     <td>A rewrite failed after both rows were locked ({@code :4079-4080}, {@code :4098-4102})</td>
 *     <td>{@code ConcurrentUpdateException} with outcome {@code LOCKED_BUT_UPDATE_FAILED}; marker
 *         {@link ChangeAction#CHANGES_OKAYED_BUT_FAILED}</td>
 *     <td>The whole unit of work rolls back. Inspect the chained cause, then retry.</td>
 *   </tr>
 *   <tr>
 *     <td>Optimistic version conflict on either row</td>
 *     <td>{@code ConcurrentUpdateException} with outcome {@code LOCKED_BUT_UPDATE_FAILED}</td>
 *     <td>Re-fetch and resubmit. The version column is the second of two concurrency layers; see
 *         section 7.</td>
 *   </tr>
 *   <tr>
 *     <td>Referential failure while writing</td>
 *     <td>{@code DataIntegrityException}</td>
 *     <td>Check the ten foreign keys of {@code V1__create_schema.sql}.</td>
 *   </tr>
 *   <tr>
 *     <td>A value bound for a {@code NOT NULL} column arrived absent or empty, so the write image cannot
 *         be built ({@code 9600-WRITE-PROCESSING} step four, {@code :3956-4059})</td>
 *     <td>{@code ValidationException} naming the BMS field, with {@code FailureKind.BLANK} - or
 *         {@code INVALID} for a monetary field that was transmitted but is unreadable; marker
 *         {@link ChangeAction#SHOW_DETAILS}, and nothing written. A value that is present but unstorable
 *         for any other reason - too wide, wrong scale, out of range - answers the same way at request
 *         level, naming no field</td>
 *     <td>Re-fetch with {@link #fetchForUpdate(String)} and resubmit <em>every</em> member of both detail
 *         groups. A stateless caller must echo the whole map: {@code receiveField} normalises an absent
 *         member and an empty string alike to {@code null}, exactly as an untransmitted 3270 field
 *         arrived as {@code LOW-VALUES}, and a {@code CHAR} column cannot hold that. This is the one row
 *         in this table that is a deviation rather than parity - the source stored {@code LOW-VALUES} and
 *         the target's schema has no such value - and it is the deviation that replaced an abend on an
 *         otherwise well-formed request. See {@link #requireStorableUpdateImage(UpdateContext)}, which
 *         also records why the confirm turn still does not re-run the edit cascade.</td>
 *   </tr>
 *   <tr>
 *     <td>A marker and attention identifier combination the dispatch does not recognise
 *         ({@code 2000-DECIDE-ACTION} {@code WHEN OTHER}, {@code :2633-2640})</td>
 *     <td>{@code FatalProcessingException} with abend code {@code 0001} and message
 *         {@code UNEXPECTED DATA SCENARIO}</td>
 *     <td>The caller sent an unreachable state. Echo {@link AccountUpdateResult#changeAction()} back
 *         unmodified rather than synthesising one.</td>
 *   </tr>
 * </table>
 *
 * <h2>5. Provenance</h2>
 *
 * <table>
 *   <caption>What this class was derived from</caption>
 *   <tr><th>Property</th><th>Value</th></tr>
 *   <tr><td>Source program</td><td>{@code app/cbl/COACTUPC.cbl}</td></tr>
 *   <tr><td>Lines</td><td>4,236 (carriage return on every line)</td></tr>
 *   <tr><td>Paragraphs</td><td>88 = 85 Area A labels in the program, plus 2 from
 *       {@code app/cpy/CSSTRPFY.cpy}, plus the {@code COPY 'CSSTRPFY'} construct itself</td></tr>
 *   <tr><td>Mapped Java methods</td><td>87 - one per label; the two {@code COPY} constructs are not
 *       methods</td></tr>
 *   <tr><td>Anchor commit</td><td>{@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}</td></tr>
 *   <tr><td>CICS definitions</td><td>{@code DEFINE PROGRAM(COACTUPC)} and
 *       {@code DEFINE TRANSACTION(CAUP)} in {@code app/csd/CARDDEMO.CSD}</td></tr>
 *   <tr><td>Record layouts</td><td>{@code app/cpy/CVACT01Y.cpy} (account, 300 bytes),
 *       {@code app/cpy/CVCUS01Y.cpy} (customer, 500 bytes), {@code app/cpy/CVACT03Y.cpy}
 *       (cross-reference, 50 bytes)</td></tr>
 *   <tr><td>Screen contract</td><td>{@code app/cpy-bms/COACTUP.CPY}, 54 input fields</td></tr>
 * </table>
 *
 * <p>The fourteen date-editing labels of {@code app/cpy/CSUTLDPY.cpy} are deliberately <em>not</em>
 * mapped here. They are owned once, by {@code com.cardemo.service.shared.DateValidationService}, and
 * this class delegates to it at the five {@code PERFORM} sites {@code :1480-1481}, {@code :1492-1493},
 * {@code :1505-1506}, {@code :1536-1537} and {@code :1540-1541}. Re-mapping them would duplicate a
 * component, which Rule 1 Clause C forbids, and would double-count in the scope-coverage gate. The
 * date work area {@code COPY 'CSUTLDWY'} at {@code :166} folds into the same collaborator and
 * likewise contributes no method here.
 *
 * <h2>6. Preserved-defect register</h2>
 *
 * <p>These are faults of the system of record. Behavioural parity is the contract of this migration, so each is
 * <strong>reproduced, not repaired</strong>. Each is tracked here, and is owed an entry in the
 * {@code DECISION_LOG.md} and a row in the {@code TRACEABILITY_MATRIX.md}, which is what distinguishes
 * it from the untracked dead code Rule 1 Clause B forbids.
 *
 * <ul>
 *   <li><strong>D1 - BLOCKER, correctness. A customer-lock failure is reported as success.</strong>
 *       The post-write dispatch at {@code :2606-2615} is an {@code EVALUATE TRUE} with exactly four
 *       {@code WHEN} clauses: {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE}, {@code LOCKED-BUT-UPDATE-FAILED},
 *       {@code DATA-WAS-CHANGED-BEFORE-UPDATE} and {@code OTHER}. The condition name
 *       {@code COULD-NOT-LOCK-CUST-FOR-UPDATE} is never tested anywhere in the program - a
 *       repository-wide census finds it exactly twice, at its declaration {@code :519} and at its one
 *       {@code SET} in {@code :3939}. A customer read-for-update failure therefore falls through
 *       {@code WHEN OTHER} and is reported as {@code ACUP-CHANGES-OKAYED-AND-DONE}, that is as
 *       success, <em>even though nothing was written</em>. This class reproduces the same four-clause
 *       dispatch and the same success-shaped outcome. Adding a fifth clause would be a behaviour
 *       change and is forbidden. The internal outcome remains distinguishable: the flag is set, is
 *       observable on the result, and is logged with personally identifiable data masked.
 *       <em>Remediation for an operator:</em> nothing was written, so re-submit the update.</li>
 *   <li><strong>D2 - HIGH, correctness. Both post-read guards in {@code 9000-READ-ACCT} are
 *       dead.</strong> {@code DID-NOT-FIND-ACCT-IN-ACCTDAT} is declared at {@code :499} and tested at
 *       {@code :3627}, but its only {@code SET} - at {@code :3719} - is commented out. The same holds
 *       for {@code DID-NOT-FIND-CUST-IN-CUSTDAT}: declared {@code :501}, tested {@code :3636},
 *       {@code SET} commented out at {@code :3769}. The read chain therefore continues past an
 *       account-master miss and past a customer-master miss. Only the cross-reference guard, which
 *       tests {@code FLG-ACCTFILTER-NOT-OK}, actually stops it. Reproduced: the two tests are present
 *       and are never satisfied. <em>Remediation:</em> check the seeded data rather than expecting the
 *       guard to fire.</li>
 *   <li><strong>D3 - HIGH, scope. An unbalanced {@code END-IF} makes the second and third social
 *       security number parts conditional on the first.</strong> In {@code 1265-EDIT-US-SSN} the
 *       {@code IF FLG-EDIT-US-SSN-PART1-ISVALID} opened at {@code :2448} is never closed: the
 *       {@code END-IF} at {@code :2463} closes the {@code IF WS-RETURN-MSG-OFF} of {@code :2454} and
 *       the one at {@code :2464} closes the {@code IF INVALID-SSN-PART1} of {@code :2450}, so the
 *       outer condition runs to the paragraph's terminating period at {@code :2488}. Parts two and
 *       three are consequently edited only when part one is valid. Reproduced exactly.
 *       <em>Remediation:</em> correct part one first; the later parts are not reported until it
 *       passes.</li>
 *   <li><strong>D4 - MEDIUM, correctness. A copy-paste fault in the telephone blank test.</strong>
 *       The three-way test at {@code :2225-2244} reads, in its third clause,
 *       {@code WS-EDIT-US-PHONE-NUMA EQUAL SPACES OR WS-EDIT-US-PHONE-NUMC EQUAL LOW-VALUES} where
 *       the first two clauses establish the pattern {@code NUMA/NUMA}, {@code NUMB/NUMB}. The third
 *       tests {@code NUMA} where {@code NUMC} was plainly intended. Reproduced.</li>
 *   <li><strong>D5 - MEDIUM, hygiene. A duplicated condition name with two different
 *       literals.</strong> {@code DID-NOT-FIND-ACCT-IN-CARDXREF} is declared twice on the same
 *       {@code PIC X(75)} field: at {@code :497} with
 *       {@code 'Did not find this account in account card xref file'} and at {@code :513} with
 *       {@code 'Did not find this account in cards database'}. Both literals are reproduced as
 *       separate constants; the second is the one the read paragraph sets.</li>
 *   <li><strong>D6 - MEDIUM, citation accuracy. {@code COPY CSUTLDPY} at {@code :4232}, not
 *       {@code COPY 'CSSTRPFY'} at {@code :4199}, is the final Area A construct of the
 *       program.</strong> Verified by reading {@code :4227-4236}: after {@code :4232} only the
 *       terminating period at {@code :4233} and a version comment remain. The widely repeated claim
 *       that {@code :4199} is last is wrong and is owed a correction in the {@code DECISION_LOG.md}.</li>
 *   <li><strong>D7 - LOW, hygiene. Three declared condition names are never referenced.</strong>
 *       {@code DID-NOT-FIND-ACCTCARD-COMBO} ({@code :515}), {@code XREF-READ-ERROR} ({@code :525})
 *       and {@code CODING-TO-BE-DONE} ({@code :527}) each occur exactly once, at their declaration.
 *       Their literals are reproduced as constants so the message vocabulary stays complete and the
 *       parity comparison has something to compare against.</li>
 *   <li><strong>D8 - LOW, hygiene. {@code 3100-SCREEN-INIT} reads the clock twice.</strong>
 *       {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} appears twice in succession at
 *       {@code :2673-2674}. Reproduced as two reads of the injected clock, which is also why the
 *       clock must be injected: two real reads could otherwise disagree.</li>
 *   <li><strong>D9 - LOW, documentation. A stale comment claims the customer comparison is
 *       split.</strong> {@code :4148} reads
 *       {@code *    Customer  data - Split into 2 IFs for easier reading}, continuing at
 *       {@code :4149-4150} with a note about updating only one file. It is a single {@code IF}. The
 *       comment's intent is recorded; it is deliberately not acted upon.</li>
 *   <li><strong>D10 - LOW, coverage. {@code ACUP-NEW-CUST-ADDR-LINE-2} is never edited.</strong>
 *       {@code 1200-EDIT-MAP-INPUTS} has no {@code 'Address Line 2'} label, yet the field is compared
 *       in both {@code 1205} and {@code 9700} and has a screen-attribute expansion. It is therefore
 *       accepted unvalidated and written as received.</li>
 *   <li><strong>D11 - LOW, hygiene. {@code 1250-EDIT-SIGNED-9V2}'s second {@code STRING} lacks
 *       {@code END-STRING} and its message lacks a trailing period.</strong> Every sibling edit
 *       message ends {@code '.'}; this one is {@code ' is not valid'}. Reproduced verbatim, because the
 *       parity gates compare message text byte for byte.</li>
 *   </ul>
 *
 * <h2>7. Mechanism substitutions</h2>
 *
 * <p>Each of these replaces a legacy construct with a framework mechanism. None changes behaviour, and
 * each is owed an entry in the {@code DECISION_LOG.md} so that a reviewer comparing the two sources does not
 * conclude something was lost.
 *
 * <ul>
 *   <li><strong>One transaction reproduces the asymmetric rollback.</strong> The source rolls back
 *       explicitly on the customer-rewrite failure ({@code EXEC CICS SYNCPOINT ROLLBACK} at
 *       {@code :4099-4101}) but <em>not</em> on the account-rewrite failure ({@code :4079-4080}). That
 *       asymmetry is correct rather than defective: at the earlier point nothing has yet been written
 *       inside the unit of work, whereas at the later point the account rewrite has already happened.
 *       A single {@code @Transactional(rollbackFor = Exception.class)} method spanning both writes
 *       reproduces both branches automatically, because each failure path leaves the method before the
 *       commit point. Nothing here is conditional: there is no second rollback, no conditional
 *       boundary and no selective rollback-only marking.</li>
 *   <li><strong>The bare {@code SYNCPOINT} at {@code :952-954} is a commit, not a rollback.</strong>
 *       It sits on the exit-to-menu path, immediately before {@code EXEC CICS XCTL}, and is a second
 *       and entirely distinct syncpoint site from the rollback above. On that path no unit of work is
 *       pending in the Java target, so it is a documented no-op substitution. The two are never
 *       conflated.</li>
 *   <li><strong>{@code EXEC CICS HANDLE ABEND LABEL(ABEND-ROUTINE)} at {@code :862-864}</strong>
 *       becomes a catch-all that funnels into {@link #abendRoutine} and hence into
 *       {@code FatalProcessingException}.</li>
 *   <li><strong>The COMMAREA becomes request parameters plus token claims.</strong>
 *       {@code CDEMO-USER-ID PIC X(08)} is the subject claim and {@code CDEMO-USER-TYPE PIC X(01)},
 *       with its {@code 'A'} and {@code 'U'} condition names from {@code app/cpy/COCOM01Y.cpy}, is the
 *       role claim. The routing fields {@code CDEMO-FROM-TRANID}, {@code CDEMO-TO-PROGRAM} and their
 *       neighbours have no counterpart, because navigation is by URL.</li>
 *   <li><strong>{@code EXEC CICS READ ... UPDATE} becomes a pessimistic-write finder.</strong>
 *       {@code AccountRepository.findByIdForUpdate} and {@code CustomerRepository.findByIdForUpdate}
 *       carry {@code @Lock(LockModeType.PESSIMISTIC_WRITE)}, which is the read-for-update the two
 *       reads of {@code 9600-WRITE-PROCESSING} perform.</li>
 *   <li><strong>Two concurrency layers, both mandatory.</strong> The JPA version column detects
 *       <em>that</em> a row changed; {@code 9700-CHECK-CHANGE-IN-REC} detects <em>which business field
 *       values</em> differ from what the user was shown. A concurrent write that restored a field to
 *       its original value passes the legacy check and fails a version check, so neither layer
 *       substitutes for the other. Because the target is stateless the snapshot cannot live on the
 *       server between requests, which is why the request carries both
 *       {@code AccountUpdateRequest.OldDetails} and {@code AccountUpdateRequest.NewDetails}.</li>
 *   <li><strong>The thirty-nine {@code COPY CSSETATY REPLACING} expansions become a tri-state.</strong>
 *       {@code app/cpy/CSSETATY.cpy} colours a field red when it is {@code NOT-OK} <em>or</em>
 *       {@code BLANK} and writes an asterisk into it only when it is {@code BLANK}. A single boolean
 *       per field cannot express that, so {@link FieldState} has three values. The
 *       {@code CDEMO-PGM-REENTER} gate in the template has no stateless equivalent, so the marker is
 *       emitted whenever a submitted field is blank.</li>
 *   <li><strong>The self-modifying pushbutton store becomes an enumeration.</strong> The
 *       twenty-eight-arm {@code EVALUATE TRUE} of {@code app/cpy/CSSTRPFY.cpy} folds program function
 *       keys thirteen to twenty-four back onto one to twelve; {@link AidKey} has the resulting fifteen
 *       constants and {@link #storePfKey} performs the fold.</li>
 *   </ul>
 *
 * <h2>8. Not available</h2>
 *
 * <p>Rule 1 Clause F requires that missing information be stated plainly rather than invented.
 *
 * <ul>
 *   <li><strong>The counting convention that yields "twelve account predicates" is not
 *       available.</strong> The migration brief describes the account comparison of {@code 9700} as
 *       twelve predicates. Direct inspection of {@code :4115-4145} finds <strong>sixteen comparison
 *       clauses over ten logical fields</strong>. What is needed to reconcile the two is an
 *       authoritative definition of "predicate" - per clause, per logical field, or per
 *       {@code IF}-operand pair. For context, {@code app/cpy/CVACT01Y.cpy} declares twelve
 *       non-{@code FILLER} fields of which {@code 9700} compares ten, omitting {@code ACCT-ID} and
 *       {@code ACCT-ADDR-ZIP}; that is a hypothesis, not the convention. Pending an answer, this class
 *       implements all sixteen clauses over all ten fields, because that is what the source does.</li>
 *   <li><strong>No service-level objective for this transaction is available.</strong> Nothing in the
 *       corpus states a latency or throughput target for {@code CAUP}: there is no SLA, no timeout
 *       budget and no throughput figure anywhere in {@code app/}. The performance gate therefore
 *       records a measured baseline rather than asserting a target. What is needed is a
 *       stakeholder-supplied objective.</li>
 *   </ul>
 *
 * <h2>9. Thread safety and state</h2>
 *
 * <p>The bean is a stateless singleton. Every collaborator field is {@code private final} and there is
 * no static mutable state of any kind. All of the legacy {@code WORKING-STORAGE} - the return-message
 * latch, the {@code ACUP} marker, the four outcome conditions, the per-field tri-states, the
 * response and reason codes and the loop index - lives on {@code UpdateContext}, which is created once
 * per invocation and never escapes as a field. A bean field would be an outright concurrency defect on
 * a singleton.
 *
 * <p>Personally identifiable data is never logged. The customer layout carries a social security
 * number, a date of birth, a government-issued identifier, two telephone numbers and an
 * electronic-funds account identifier, and the cross-reference carries a card number; the request
 * carries all of them twice, once in each snapshot group. Nothing here logs the request object, no
 * {@code toString} exposes any of those fields, and the few places that must mention an identifier log
 * it masked.
 *
 * @see AccountUpdateRequest
 * @see ConcurrentUpdateException
 * @see DateValidationService
 * @see ValidationLookupService
 * @see FileStatusMapper
 */
@Service
public class AccountUpdateService {

    /**
     * Structured log sink. Replaces the {@code DISPLAY}-only instrumentation of the legacy corpus; the
     * online programs had no instrumentation at all beyond the terminal.
     */
    private static final Logger LOG = LoggerFactory.getLogger(AccountUpdateService.class);

    // ------------------------------------------------------------------------------------------------
    // WS-LITERALS, declared at :532-612. Every literal the program uses is hoisted here with the line
    // that declares it, so that a parity comparison has a single place to check and so that no literal
    // is spelled twice.
    // ------------------------------------------------------------------------------------------------

    /** {@code LIT-THISPGM PIC X(8) VALUE 'COACTUPC'}, {@code :533-534}. */
    private static final String PROGRAM_NAME = "COACTUPC";

    /** {@code LIT-THISTRANID PIC X(4) VALUE 'CAUP'}, {@code :535-536}. */
    private static final String TRANSACTION_ID = "CAUP";

    /** {@code LIT-THISMAPSET PIC X(8) VALUE 'COACTUP '}, {@code :537-538}; the trailing blank is real. */
    private static final String THIS_MAPSET = "COACTUP ";

    /** {@code LIT-THISMAP PIC X(7) VALUE 'CACTUPA'}, {@code :539-540}. */
    private static final String THIS_MAP = "CACTUPA";

    /** {@code LIT-MENUPGM PIC X(8) VALUE 'COMEN01C'}, {@code :557}. */
    private static final String MENU_PROGRAM = "COMEN01C";

    /** {@code LIT-MENUTRANID PIC X(4) VALUE 'CM00'}, {@code :559-560}. */
    private static final String MENU_TRANSACTION_ID = "CM00";

    /** {@code LIT-MENUMAPSET PIC X(7) VALUE 'COMEN01'}, {@code :561-562}. */
    private static final String MENU_MAPSET = "COMEN01";

    /** {@code LIT-MENUMAP PIC X(7) VALUE 'COMEN1A'}, {@code :563-564}. */
    private static final String MENU_MAP = "COMEN1A";

    /** {@code LIT-ACCTFILENAME PIC X(8) VALUE 'ACCTDAT '}, {@code :573}; the trailing blank is real. */
    private static final String ACCOUNT_FILE_NAME = "ACCTDAT ";

    /** {@code LIT-CUSTFILENAME PIC X(8) VALUE 'CUSTDAT '}, {@code :575}. */
    private static final String CUSTOMER_FILE_NAME = "CUSTDAT ";

    /**
     * {@code LIT-CARDXREFNAME-ACCT-PATH PIC X(8) VALUE 'CXACAIX '}, {@code :581}. The account path over
     * the cross-reference alternate index, which is what {@code 9200-GETCARDXREF-BYACCT} reads.
     */
    private static final String XREF_ACCOUNT_PATH_NAME = "CXACAIX ";

    /**
     * {@code LIT-UPPER PIC X(26)} then {@code LIT-LOWER PIC X(26)}, {@code :585-590}: the fifty-two
     * character alphabet that {@code INSPECT ... CONVERTING} blanks out in the alphabetic edits.
     */
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    /** {@code LIT-NUMBERS PIC X(10) VALUE '0123456789'}, {@code :591-592}. */
    private static final String DIGITS = "0123456789";

    // ------------------------------------------------------------------------------------------------
    // Screen titles, from app/cpy/COTTL01Y.cpy which the program COPYs at :620. Both are PIC X(40).
    // ------------------------------------------------------------------------------------------------

    /** {@code CCDA-TITLE01}: the product banner, forty characters exactly. */
    private static final String SCREEN_TITLE_01 = "      AWS Mainframe Modernization       ";

    /** {@code CCDA-TITLE02}: the application banner, forty characters exactly. */
    private static final String SCREEN_TITLE_02 = "              CardDemo                  ";

    // ------------------------------------------------------------------------------------------------
    // WS-INFO-MSG PIC X(40) and its condition names, :463-477. These are the informational prompts
    // 3250-SETUP-INFOMSG selects between.
    // ------------------------------------------------------------------------------------------------

    /** {@code 88 FOUND-ACCOUNT-DATA}, {@code :466-467}. */
    private static final String INFO_FOUND_ACCOUNT_DATA = "Details of selected account shown above";

    /** {@code 88 PROMPT-FOR-SEARCH-KEYS}, {@code :468-469}. */
    private static final String INFO_PROMPT_FOR_SEARCH_KEYS = "Enter or update id of account to update";

    /** {@code 88 PROMPT-FOR-CHANGES}, {@code :470-471}; the trailing period is part of the literal. */
    private static final String INFO_PROMPT_FOR_CHANGES = "Update account details presented above.";

    /** {@code 88 PROMPT-FOR-CONFIRMATION}, {@code :472-473}; no space after the period, as written. */
    private static final String INFO_PROMPT_FOR_CONFIRMATION = "Changes validated.Press F5 to save";

    /** {@code 88 CONFIRM-UPDATE-SUCCESS}, {@code :474-475}. */
    private static final String INFO_CONFIRM_UPDATE_SUCCESS = "Changes committed to database";

    /** {@code 88 INFORM-FAILURE}, {@code :476-477}. */
    private static final String INFO_INFORM_FAILURE = "Changes unsuccessful. Please try again";

    // ------------------------------------------------------------------------------------------------
    // WS-RETURN-MSG PIC X(75) and its condition names, :479-528.
    //
    // These condition names are NOT boolean flags. They are eighty-eight levels on a single PIC X(75)
    // message field, so SET <name> TO TRUE moves the literal into the field and WHEN <name> compares
    // the field against the literal. That is why the post-write dispatch of :2606-2615 is a sequence of
    // string comparisons and why the first-error-wins latch of IF WS-RETURN-MSG-OFF matters: a second
    // SET would otherwise overwrite the first message and change which condition the dispatch matches.
    // ------------------------------------------------------------------------------------------------

    /** {@code 88 WS-RETURN-MSG-OFF VALUE SPACES}, {@code :480}: the latch's "no message pending" state. */
    private static final String RETURN_MESSAGE_OFF = "";

    /**
     * {@code 88 WS-EXIT-MESSAGE}, {@code :481-482}. The literal carries fourteen trailing spaces inside
     * the quotes; they are reproduced because the parity comparison is byte for byte.
     */
    private static final String EXIT_MESSAGE = "PF03 pressed.Exiting              ";

    /** {@code 88 WS-PROMPT-FOR-ACCT}, {@code :483-484}. */
    private static final String PROMPT_FOR_ACCOUNT_MESSAGE = "Account number not provided";

    /** {@code 88 WS-PROMPT-FOR-LASTNAME}, {@code :485-486}. Declared but never set by this program. */
    private static final String PROMPT_FOR_LAST_NAME_MESSAGE = "Last name not provided";

    /** {@code 88 WS-NAME-MUST-BE-ALPHA}, {@code :487-488}. Declared but never set by this program. */
    private static final String NAME_MUST_BE_ALPHA_MESSAGE = "Name can only contain alphabets and spaces";

    /** {@code 88 NO-SEARCH-CRITERIA-RECEIVED}, {@code :489-490}. */
    private static final String NO_SEARCH_CRITERIA_MESSAGE = "No input received";

    /** {@code 88 NO-CHANGES-DETECTED}, {@code :491-492}; the trailing period is part of the literal. */
    private static final String NO_CHANGES_DETECTED_MESSAGE =
            "No change detected with respect to values fetched.";

    /**
     * {@code 88 SEARCHED-ACCT-ZEROES}, {@code :493-494}, and {@code 88 SEARCHED-ACCT-NOT-NUMERIC},
     * {@code :495-496}. The source declares two condition names carrying the <em>same</em> literal, so
     * one constant serves both; the distinction exists only in the condition names.
     */
    private static final String SEARCHED_ACCOUNT_NOT_ELEVEN_DIGITS =
            "Account number must be a non zero 11 digit number";

    /**
     * The <em>first</em> {@code 88 DID-NOT-FIND-ACCT-IN-CARDXREF}, {@code :497-498}. Defect D5: the same
     * condition name is declared again at {@code :513-514} with a different literal. This one is not
     * reachable, because a duplicated condition name resolves to the later declaration; it is retained so
     * the message vocabulary is complete.
     */
    private static final String DID_NOT_FIND_ACCOUNT_IN_CARDXREF_FIRST_DECLARATION =
            "Did not find this account in account card xref file";

    /**
     * {@code 88 DID-NOT-FIND-ACCT-IN-ACCTDAT}, {@code :499-500}. Defect D2: tested at {@code :3627} but
     * its only {@code SET} is commented out at {@code :3719}, so the condition can never hold.
     */
    private static final String DID_NOT_FIND_ACCOUNT_IN_ACCTDAT =
            "Did not find this account in account master file";

    /**
     * {@code 88 DID-NOT-FIND-CUST-IN-CUSTDAT}, {@code :501-502}. Defect D2: tested at {@code :3636} but
     * its only {@code SET} is commented out at {@code :3769}.
     */
    private static final String DID_NOT_FIND_CUSTOMER_IN_CUSTDAT =
            "Did not find associated customer in master file";

    /** {@code 88 ACCT-STATUS-MUST-BE-YES-NO}, {@code :503-504}. */
    private static final String ACCOUNT_STATUS_MUST_BE_YES_NO = "Account Active Status must be Y or N";

    /** {@code 88 CRED-LIMIT-IS-BLANK}, {@code :505-506}. Declared but never set by this program. */
    private static final String CREDIT_LIMIT_IS_BLANK_MESSAGE = "Credit Limit must be supplied";

    /** {@code 88 CRED-LIMIT-IS-NOT-VALID}, {@code :507-508}. Declared but never set by this program. */
    private static final String CREDIT_LIMIT_IS_NOT_VALID_MESSAGE = "Credit Limit is not valid";

    /** {@code 88 THIS-MONTH-NOT-VALID}, {@code :509-510}. Declared but never set by this program. */
    private static final String EXPIRY_MONTH_NOT_VALID_MESSAGE = "Card expiry month must be between 1 and 12";

    /** {@code 88 THIS-YEAR-NOT-VALID}, {@code :511-512}. Declared but never set by this program. */
    private static final String EXPIRY_YEAR_NOT_VALID_MESSAGE = "Invalid card expiry year";

    /**
     * The <em>second</em> {@code 88 DID-NOT-FIND-ACCT-IN-CARDXREF}, {@code :513-514}. This is the one
     * {@code 9200-GETCARDXREF-BYACCT} effectively sets, being the later of the two declarations.
     */
    private static final String DID_NOT_FIND_ACCOUNT_IN_CARDXREF =
            "Did not find this account in cards database";

    /** {@code 88 DID-NOT-FIND-ACCTCARD-COMBO}, {@code :515-516}. Defect D7: declaration only. */
    private static final String DID_NOT_FIND_ACCOUNT_CARD_COMBINATION =
            "Did not find cards for this search condition";

    /**
     * {@code 88 COULD-NOT-LOCK-ACCT-FOR-UPDATE}, {@code :517-518}. Set at {@code :3912} and tested at
     * {@code :2607}.
     */
    private static final String COULD_NOT_LOCK_ACCOUNT_FOR_UPDATE =
            "Could not lock account record for update";

    /**
     * {@code 88 COULD-NOT-LOCK-CUST-FOR-UPDATE}, {@code :519-520}. <strong>Defect D1, BLOCKER.</strong>
     * Set at {@code :3939} and tested nowhere. The literal is reproduced because it reaches the screen
     * through {@code WS-RETURN-MSG} even though the dispatch never matches it.
     */
    private static final String COULD_NOT_LOCK_CUSTOMER_FOR_UPDATE =
            "Could not lock customer record for update";

    /**
     * {@code 88 DATA-WAS-CHANGED-BEFORE-UPDATE}, {@code :521-522}. Note "some one" as two words, exactly
     * as the source spells it.
     */
    private static final String DATA_WAS_CHANGED_BEFORE_UPDATE =
            "Record changed by some one else. Please review";

    /** {@code 88 LOCKED-BUT-UPDATE-FAILED}, {@code :523-524}. Set at {@code :4079} and {@code :4098}. */
    private static final String LOCKED_BUT_UPDATE_FAILED = "Update of record failed";

    /** {@code 88 XREF-READ-ERROR}, {@code :525-526}. Defect D7: declaration only. */
    private static final String XREF_READ_ERROR_MESSAGE = "Error reading Card Data File";

    /**
     * {@code 88 CODING-TO-BE-DONE}, {@code :527-528}. Defect D7: declaration only. Four consecutive
     * periods, exactly as written.
     */
    private static final String CODING_TO_BE_DONE_MESSAGE = "Looks Good.... so far";

    // ------------------------------------------------------------------------------------------------
    // Edit-message fragments. The source builds each message with
    //   STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME) '<suffix>' DELIMITED BY SIZE INTO WS-RETURN-MSG
    // so the suffixes below are concatenated onto the trimmed field label.
    // ------------------------------------------------------------------------------------------------

    /** {@code 1215-EDIT-MANDATORY}, {@code :1838-1842}. */
    private static final String SUFFIX_MUST_BE_SUPPLIED = " must be supplied.";

    /**
     * The phrase every <em>blank</em> arm of the edit cascade puts in {@code WS-RETURN-MSG}, {@value}.
     *
     * <p>Shared by {@link #SUFFIX_MUST_BE_SUPPLIED}, {@link #SUFFIX_AREA_CODE_SUPPLIED},
     * {@link #SUFFIX_PREFIX_SUPPLIED}, {@link #SUFFIX_LINE_NUMBER_SUPPLIED} and
     * {@link #CREDIT_LIMIT_IS_BLANK_MESSAGE}, and used by no other arm, which is what makes it a sound
     * discriminator for {@link #isUnsuppliedFieldMessage(String)}.
     */
    private static final String UNSUPPLIED_FIELD_PHRASE = "must be supplied";

    /** {@code 1220-EDIT-YESNO}, {@code :1885-1889}. */
    private static final String SUFFIX_MUST_BE_Y_OR_N = " must be Y or N.";

    /** {@code 1225-EDIT-ALPHA-REQD} and {@code 1235-EDIT-ALPHA-OPT}. */
    private static final String SUFFIX_ALPHABETS_ONLY = " can have alphabets only.";

    /** {@code 1230-EDIT-ALPHANUM-REQD} and {@code 1240-EDIT-ALPHANUM-OPT}. */
    private static final String SUFFIX_ALPHANUMERIC_ONLY = " can have numbers or alphabets only.";

    /** {@code 1245-EDIT-NUM-REQD}, the non-numeric branch. */
    private static final String SUFFIX_MUST_BE_ALL_NUMERIC = " must be all numeric.";

    /** {@code 1245-EDIT-NUM-REQD}, the all-zeroes branch. */
    private static final String SUFFIX_MUST_NOT_BE_ZERO = " must not be zero.";

    /**
     * {@code 1250-EDIT-SIGNED-9V2}, the invalid branch. Defect D11: unlike every sibling message this one
     * has no trailing period, and its {@code STRING} has no {@code END-STRING}. Reproduced verbatim.
     */
    private static final String SUFFIX_IS_NOT_VALID = " is not valid";

    /**
     * {@code 1210-EDIT-ACCOUNT}, {@code :1800-1806} and {@code :1812-1818}: a two-part literal, emitted
     * without a field-label prefix.
     */
    private static final String ACCOUNT_FILTER_ELEVEN_DIGIT_MESSAGE =
            "Account Number if supplied must be a 11 digit Non-Zero Number";

    /** {@code EDIT-AREA-CODE}, the blank branch. */
    private static final String SUFFIX_AREA_CODE_SUPPLIED = ": Area code must be supplied.";

    /** {@code EDIT-AREA-CODE}, the non-numeric branch. */
    private static final String SUFFIX_AREA_CODE_THREE_DIGITS = ": Area code must be A 3 digit number.";

    /** {@code EDIT-AREA-CODE}, the zero branch; no trailing period, as written. */
    private static final String SUFFIX_AREA_CODE_NOT_ZERO = ": Area code cannot be zero";

    /** {@code EDIT-AREA-CODE}, the lookup branch; no trailing period, as written. */
    private static final String SUFFIX_AREA_CODE_NOT_NORTH_AMERICAN =
            ": Not valid North America general purpose area code";

    /** {@code EDIT-US-PHONE-PREFIX}, the blank branch. */
    private static final String SUFFIX_PREFIX_SUPPLIED = ": Prefix code must be supplied.";

    /** {@code EDIT-US-PHONE-PREFIX}, the non-numeric branch. */
    private static final String SUFFIX_PREFIX_THREE_DIGITS = ": Prefix code must be A 3 digit number.";

    /** {@code EDIT-US-PHONE-PREFIX}, the zero branch; no trailing period, as written. */
    private static final String SUFFIX_PREFIX_NOT_ZERO = ": Prefix code cannot be zero";

    /** {@code EDIT-US-PHONE-LINENUM}, the blank branch. */
    private static final String SUFFIX_LINE_NUMBER_SUPPLIED = ": Line number code must be supplied.";

    /** {@code EDIT-US-PHONE-LINENUM}, the non-numeric branch. */
    private static final String SUFFIX_LINE_NUMBER_FOUR_DIGITS =
            ": Line number code must be A 4 digit number.";

    /** {@code EDIT-US-PHONE-LINENUM}, the zero branch; no trailing period, as written. */
    private static final String SUFFIX_LINE_NUMBER_NOT_ZERO = ": Line number code cannot be zero";

    /** {@code 1265-EDIT-US-SSN}, the part-one label. */
    private static final String SSN_PART1_LABEL = "SSN: First 3 chars";

    /** {@code 1265-EDIT-US-SSN}, the part-one lookup branch. */
    private static final String SUFFIX_SSN_PART1_RANGE =
            ": should not be 000, 666, or between 900 and 999";

    /** {@code 1265-EDIT-US-SSN}, the part-two label; the ampersand is part of the literal. */
    private static final String SSN_PART2_LABEL = "SSN 4th & 5th chars";

    /** {@code 1265-EDIT-US-SSN}, the part-three label. */
    private static final String SSN_PART3_LABEL = "SSN Last 4 chars";

    /** {@code 1270-EDIT-US-STATE-CD}, the lookup branch; no trailing period, as written. */
    private static final String SUFFIX_NOT_A_VALID_STATE = ": is not a valid state code";

    /** {@code 1275-EDIT-FICO-SCORE}, the range branch; no trailing period, as written. */
    private static final String SUFFIX_FICO_RANGE = ": should be between 300 and 850";

    /**
     * {@code 1280-EDIT-US-STATE-ZIP-CD}, the combination branch. Emitted with no field-label prefix, and
     * it marks <em>both</em> the state and the ZIP code as not valid.
     */
    private static final String INVALID_ZIP_FOR_STATE_MESSAGE = "Invalid zip code for state";

    // ------------------------------------------------------------------------------------------------
    // WS-EDIT-VARIABLE-NAME PIC X(25), :53. The field labels 1200-EDIT-MAP-INPUTS moves into the shared
    // edit variable before each PERFORM, in the exact order the source performs them.
    // ------------------------------------------------------------------------------------------------

    /** Label used for the account status edit, {@code :1451}. */
    private static final String LABEL_ACCOUNT_STATUS = "Account Status";

    /** Label used for the open-date edit, {@code :1476}. */
    private static final String LABEL_OPEN_DATE = "Open Date";

    /** Label used for the credit-limit edit, {@code :1485}. */
    private static final String LABEL_CREDIT_LIMIT = "Credit Limit";

    /** Label used for the expiry-date edit, {@code :1489}. */
    private static final String LABEL_EXPIRY_DATE = "Expiry Date";

    /** Label used for the cash-credit-limit edit, {@code :1498}. */
    private static final String LABEL_CASH_CREDIT_LIMIT = "Cash Credit Limit";

    /** Label used for the reissue-date edit, {@code :1502}. */
    private static final String LABEL_REISSUE_DATE = "Reissue Date";

    /** Label used for the current-balance edit, {@code :1511}. */
    private static final String LABEL_CURRENT_BALANCE = "Current Balance";

    /** Label used for the cycle-credit edit, {@code :1517}. */
    private static final String LABEL_CURRENT_CYCLE_CREDIT = "Current Cycle Credit Limit";

    /** Label used for the cycle-debit edit, {@code :1523}. */
    private static final String LABEL_CURRENT_CYCLE_DEBIT = "Current Cycle Debit Limit";

    /** Label used for the social security number edit, {@code :1529}. */
    private static final String LABEL_SSN = "SSN";

    /** Label used for the date-of-birth edit, {@code :1533}. */
    private static final String LABEL_DATE_OF_BIRTH = "Date of Birth";

    /** Label used for the FICO score edit, {@code :1546}. */
    private static final String LABEL_FICO_SCORE = "FICO Score";

    /** Label used for the first-name edit, {@code :1556}. */
    private static final String LABEL_FIRST_NAME = "First Name";

    /** Label used for the middle-name edit, {@code :1566}. */
    private static final String LABEL_MIDDLE_NAME = "Middle Name";

    /** Label used for the last-name edit, {@code :1573}. */
    private static final String LABEL_LAST_NAME = "Last Name";

    /** Label used for the first address line edit, {@code :1581}. */
    private static final String LABEL_ADDRESS_LINE_1 = "Address Line 1";

    /** Label used for the state edit, {@code :1588}. */
    private static final String LABEL_STATE = "State";

    /** Label used for the ZIP edit, {@code :1602}. */
    private static final String LABEL_ZIP = "Zip";

    /** Label used for the city edit, {@code :1609}; the city occupies address line three. */
    private static final String LABEL_CITY = "City";

    /** Label used for the country edit, {@code :1616}. */
    private static final String LABEL_COUNTRY = "Country";

    /** Label used for the first telephone edit, {@code :1623}. */
    private static final String LABEL_PHONE_NUMBER_1 = "Phone Number 1";

    /** Label used for the second telephone edit, {@code :1636}. */
    private static final String LABEL_PHONE_NUMBER_2 = "Phone Number 2";

    /** Label used for the electronic-funds account edit, {@code :1649}. */
    private static final String LABEL_EFT_ACCOUNT_ID = "EFT Account Id";

    /** Label used for the primary-card-holder edit, {@code :1656}. */
    private static final String LABEL_PRIMARY_CARD_HOLDER = "Primary Card Holder";

    /**
     * Label for the second address line. The source carries this literal at {@code :1614} but
     * <strong>commented out</strong>, under the explanatory comment {@code * Address Line 2 is optional}
     * at {@code :1613} - so the field has no edit and never reaches
     * {@code 1200-EDIT-MAP-INPUTS}'s shared label slot. The literal is reproduced verbatim from that
     * commented line rather than invented, and is used only by
     * {@link #requireStorableUpdateImage(UpdateContext)}.
     */
    private static final String LABEL_ADDRESS_LINE_2 = "Address Line 2";

    /**
     * Label for the account group identifier. Unlike every label above it, this text is
     * <strong>not</strong> in the source: {@code ACCT-GROUP-ID} has no edit at all - it is absent from the
     * edit cascade and from the thirty-nine {@code COPY CSSETATY REPLACING} marker expansions at
     * {@code :3208-3437} - and {@code :4002} moves whatever arrived straight into the record. The text is
     * derived from the copybook field name {@code ACCT-GROUP-ID PIC X(10)} at
     * {@code app/cpy/CVACT01Y.cpy:L19} so that the diagnostic reads like its twenty-four siblings, and it
     * is used only by {@link #requireStorableUpdateImage(UpdateContext)}.
     */
    private static final String LABEL_ACCOUNT_GROUP_ID = "Account Group Id";

    /**
     * Label for the government-issued identifier. As with the group identifier above, the source carries
     * no literal for it: {@code CUST-GOVT-ISSUED-ID} has no edit, and {@code :4046} moves the submitted
     * value in unexamined. Derived from {@code CUST-GOVT-ISSUED-ID PIC X(20)} at
     * {@code app/cpy/CVCUS01Y.cpy}, and used only by
     * {@link #requireStorableUpdateImage(UpdateContext)}.
     */
    private static final String LABEL_GOVERNMENT_ISSUED_ID = "Government Issued Id";

    /**
     * Request-level diagnostic for the safety net of {@link #writeProcessing9600(UpdateContext)}.
     *
     * <p>Deliberately names no field and reproduces no part of the entity's own refusal text. Those
     * messages name the Java property, the COBOL picture clause, the column width and the table - for
     * example {@code "groupId (ACCT-GROUP-ID PIC X(10)) must not be null: it maps to a NOT NULL CHAR(10)
     * column of table account"} - and relaying that to a caller would publish the schema. The same
     * CWE-209 posture is why {@code HttpMessageNotReadableException} answers with a fixed envelope and
     * keeps the offending property in the log only.
     */
    private static final String UNSTORABLE_UPDATE_IMAGE_MESSAGE =
            "One or more submitted values cannot be stored. Re-fetch the account and resubmit every field.";

    // ------------------------------------------------------------------------------------------------
    // Abend vocabulary. app/cpy/CSMSG02Y.cpy - internally titled CABENDD.CPY - declares
    // ABEND-CODE PIC X(4), ABEND-CULPRIT PIC X(8), ABEND-REASON PIC X(50) and ABEND-MSG PIC X(72),
    // together 134 bytes, all VALUE SPACES. The program COPYs it at :632.
    // ------------------------------------------------------------------------------------------------

    /** {@code MOVE 'UNEXPECTED DATA SCENARIO' TO ABEND-MSG}, {@code :2637-2638}. */
    private static final String UNEXPECTED_DATA_SCENARIO_MESSAGE = "UNEXPECTED DATA SCENARIO";

    /** {@code MOVE '0001' TO ABEND-CODE}, {@code :2635}: the CABENDD payload code, four characters. */
    private static final String UNEXPECTED_SCENARIO_ABEND_CODE = "0001";

    /**
     * {@code MOVE SPACES TO ABEND-REASON}, {@code :2636}. Sized from
     * {@code FileStatusMapper.ABEND_REASON_WIDTH} so the fifty-byte {@code PIC X(50)} width comes from
     * one place rather than being spelled again here.
     */
    private static final String ABEND_REASON_SPACES = " ".repeat(FileStatusMapper.ABEND_REASON_WIDTH);

    /**
     * {@code EXEC CICS ABEND ABCODE('9999')}, {@code :4222-4224}: the terminal CICS abend code, four
     * characters exactly filling {@code PIC X(4)}. This is <em>not</em> the batch abend code 999 nor the
     * batch return code 12; those belong to the {@code CEE3ABD} path of {@code CBTRN02C} and are already
     * declared by {@code FatalProcessingException}. An online task has no process return code at all.
     */
    private static final String ONLINE_ABEND_CODE = "9999";

    // ------------------------------------------------------------------------------------------------
    // WS-FILE-ERROR-MESSAGE, the eighty-byte group declared at :383-407. Twelve, eight, four, nine,
    // fifteen, ten, seven, ten and five bytes, summing to exactly eighty. The MOVE into
    // WS-RETURN-MSG PIC X(75) truncates the trailing five.
    // ------------------------------------------------------------------------------------------------

    /** {@code FILLER PIC X(12) VALUE 'File Error: '}, {@code :384-385}. */
    private static final String FILE_ERROR_PREFIX = "File Error: ";

    /** {@code FILLER PIC X(4) VALUE ' on '}, {@code :388-389}. */
    private static final String FILE_ERROR_ON = " on ";

    /** {@code FILLER PIC X(15) VALUE ' returned RESP '}, {@code :392-395}. */
    private static final String FILE_ERROR_RETURNED_RESP = " returned RESP ";

    /** {@code FILLER PIC X(7) VALUE ',RESP2 '}, {@code :398-399}. */
    private static final String FILE_ERROR_RESP2 = ",RESP2 ";

    /** The trailing {@code FILLER PIC X(5) VALUE SPACES}, {@code :404-405}. */
    private static final int FILE_ERROR_TRAILING_BLANKS = 5;

    /** {@code ERROR-OPNAME PIC X(8)}, {@code :386-387}. */
    private static final int ERROR_OPERATION_LENGTH = 8;

    /** {@code ERROR-FILE PIC X(9)}, {@code :390-391}. */
    private static final int ERROR_FILE_LENGTH = 9;

    /** {@code ERROR-RESP PIC X(10)} and {@code ERROR-RESP2 PIC X(10)}, {@code :396} and {@code :400}. */
    private static final int ERROR_RESPONSE_LENGTH = 10;

    /** {@code WS-RESP-CD PIC S9(09) COMP}, {@code :40}: nine digits when rendered as text. */
    private static final int RESPONSE_CODE_DIGITS = 9;

    /** {@code MOVE 'READ' TO ERROR-OPNAME}, at each of the three read paragraphs' {@code WHEN OTHER}. */
    private static final String OPERATION_READ = "READ";

    /**
     * The operation name attached to the structured log line emitted when one of the two
     * {@code REWRITE} verbs of {@code 9600-WRITE-PROCESSING} fails. The source records no operation name
     * on those paths - its failure branches at {@code :4076-4081} and {@code :4095-4102} set
     * {@code LOCKED-BUT-UPDATE-FAILED} and nothing else, and the only {@code ERROR-OPNAME} literal in the
     * whole program is {@code 'READ'} - so this name exists purely for the observability layer the legacy
     * system does not have. It never reaches a screen field and never alters a message.
     */
    private static final String OPERATION_REWRITE = "REWRITE";

    // ------------------------------------------------------------------------------------------------
    // Diagnostic message fragments assembled by the three read paragraphs' DFHRESP(NOTFND) branches.
    // ------------------------------------------------------------------------------------------------

    /** {@code 9200} and {@code 9300}, the leading fragment. */
    private static final String MESSAGE_ACCOUNT_PREFIX = "Account:";

    /** {@code 9200} and {@code 9300}, the middle fragment. */
    private static final String MESSAGE_NOT_FOUND_IN = " not found in";

    /** {@code 9200}, the file fragment; note the two spaces before {@code Resp:}. */
    private static final String MESSAGE_XREF_FILE = " Cross ref file.  Resp:";

    /** {@code 9300}, the file fragment; note no space after the period. */
    private static final String MESSAGE_ACCOUNT_MASTER_FILE = " Acct Master file.Resp:";

    /** {@code 9200} and {@code 9300}, the reason fragment. */
    private static final String MESSAGE_REASON = " Reas:";

    /** {@code 9400}, the leading fragment. */
    private static final String MESSAGE_CUSTOMER_PREFIX = "CustId:";

    /** {@code 9400}, the middle fragment. */
    private static final String MESSAGE_NOT_FOUND = " not found";

    /** {@code 9400}, the file fragment; note the trailing space after {@code Resp:}. */
    private static final String MESSAGE_CUSTOMER_MASTER = " in customer master.Resp: ";

    /** {@code 9400}, the reason fragment; upper case here, unlike {@code 9200} and {@code 9300}. */
    private static final String MESSAGE_REASON_UPPER = " REAS:";

    // ------------------------------------------------------------------------------------------------
    // CICS RESP ordinals and the file statuses they bridge to. FileStatusMapper is keyed on the
    // two-character FILE STATUS of the batch corpus, and this program reports the online RESP instead,
    // so each read records both: the ordinal for the diagnostic message and the status for the mapper.
    // ------------------------------------------------------------------------------------------------

    /** {@code DFHRESP(NORMAL)}, ordinal zero. */
    private static final int CICS_RESP_NORMAL = 0;

    /** {@code DFHRESP(NOTFND)}, ordinal thirteen. */
    private static final int CICS_RESP_NOTFND = 13;

    /** {@code DFHRESP(IOERR)}, ordinal seventeen: the {@code WHEN OTHER} arm's representative value. */
    private static final int CICS_RESP_IOERR = 17;

    /**
     * {@code WS-REAS-CD} is left at zero for every condition these paragraphs handle, so
     * {@code RESP2} is always rendered as zero.
     */
    private static final int CICS_REASON_NONE = 0;

    /** The file status a normal response bridges to. */
    private static final String IO_STATUS_SUCCESS = "00";

    /** The file status a not-found response bridges to. */
    private static final String IO_STATUS_RECORD_NOT_FOUND = "23";

    /**
     * The file status an unexpected response bridges to. A leading {@code '9'} is what makes
     * {@code FileStatusMapper} classify it as the I/O-error family and produce a
     * {@code FileAccessException} carrying the expanded four-character rendering.
     */
    private static final String IO_STATUS_IO_ERROR = "90";

    // ------------------------------------------------------------------------------------------------
    // Field widths, all from the record layouts and the symbolic map. Reproduced as constants because a
    // MOVE truncates silently at the receiving field's width and Java does not.
    // ------------------------------------------------------------------------------------------------

    /** {@code ACCT-ID PIC 9(11)}, {@code app/cpy/CVACT01Y.cpy:L5}. */
    private static final int ACCOUNT_ID_LENGTH = 11;

    /** {@code CUST-ID PIC 9(09)}, {@code app/cpy/CVCUS01Y.cpy:L5}. */
    private static final int CUSTOMER_ID_LENGTH = 9;

    /** {@code XREF-CARD-NUM PIC X(16)}, {@code app/cpy/CVACT03Y.cpy:L5}. */
    private static final int CARD_NUMBER_LENGTH = 16;

    /** {@code WS-RETURN-MSG PIC X(75)}, {@code :479}. */
    private static final int RETURN_MESSAGE_LENGTH = 75;

    /** {@code WS-INFO-MSG PIC X(40)}, {@code :463}. */
    private static final int INFO_MESSAGE_LENGTH = 40;

    /** {@code WS-LONG-MSG PIC X(500)}, {@code :462}. */
    private static final int LONG_MESSAGE_LENGTH = 500;

    /** {@code WS-EDIT-VARIABLE-NAME PIC X(25)}, {@code :53}. */
    private static final int EDIT_VARIABLE_NAME_LENGTH = 25;

    /** {@code ACCT-ACTIVE-STATUS PIC X(01)}, {@code app/cpy/CVACT01Y.cpy:L6}. */
    private static final int ACTIVE_STATUS_LENGTH = 1;

    /** {@code ACCT-OPEN-DATE PIC X(10)} and its two siblings. */
    private static final int DATE_TEXT_LENGTH = 10;

    /** {@code ACUP-OLD-OPEN-DATE PIC X(08)}, {@code :690}: the compact snapshot form. */
    private static final int COMPACT_DATE_LENGTH = 8;

    /** {@code ACCT-GROUP-ID PIC X(10)}, {@code app/cpy/CVACT01Y.cpy:L16}. */
    private static final int GROUP_ID_LENGTH = 10;

    /** {@code CUST-FIRST-NAME PIC X(25)} and its two siblings. */
    private static final int NAME_LENGTH = 25;

    /** {@code CUST-ADDR-LINE-1 PIC X(50)} and its two siblings. */
    private static final int ADDRESS_LINE_LENGTH = 50;

    /** {@code CUST-ADDR-STATE-CD PIC X(02)}. */
    private static final int STATE_CODE_LENGTH = 2;

    /** {@code CUST-ADDR-COUNTRY-CD PIC X(03)}. */
    private static final int COUNTRY_CODE_LENGTH = 3;

    /** {@code CUST-ADDR-ZIP PIC X(10)} in the record; the screen field {@code ACSZIPCI} is {@code X(5)}. */
    private static final int ZIP_RECORD_LENGTH = 10;

    /** {@code ACSZIPCI PIC X(5)}, {@code app/cpy-bms/COACTUP.CPY:246}. */
    private static final int ZIP_SCREEN_LENGTH = 5;

    /** {@code CUST-PHONE-NUM-1 PIC X(15)}. */
    private static final int PHONE_NUMBER_LENGTH = 15;

    /** {@code WS-EDIT-US-PHONE-NUMA PIC X(3)}, {@code :86}. */
    private static final int PHONE_AREA_CODE_LENGTH = 3;

    /** {@code WS-EDIT-US-PHONE-NUMB PIC X(3)}, {@code :91}. */
    private static final int PHONE_PREFIX_LENGTH = 3;

    /** {@code WS-EDIT-US-PHONE-NUMC PIC X(4)}, {@code :96}. */
    private static final int PHONE_LINE_NUMBER_LENGTH = 4;

    /** {@code CUST-SSN PIC 9(09)}, {@code app/cpy/CVCUS01Y.cpy:L17}. */
    private static final int SSN_LENGTH = 9;

    /** {@code WS-EDIT-US-SSN-PART1 PIC X(3)}, {@code :118}. */
    private static final int SSN_PART1_LENGTH = 3;

    /** {@code WS-EDIT-US-SSN-PART2 PIC X(2)}, {@code :124}. */
    private static final int SSN_PART2_LENGTH = 2;

    /** {@code WS-EDIT-US-SSN-PART3 PIC X(4)}, {@code :127}. */
    private static final int SSN_PART3_LENGTH = 4;

    /** {@code CUST-GOVT-ISSUED-ID PIC X(20)}. */
    private static final int GOVERNMENT_ID_LENGTH = 20;

    /** {@code CUST-EFT-ACCOUNT-ID PIC X(10)}. */
    private static final int EFT_ACCOUNT_ID_LENGTH = 10;

    /** {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}. */
    private static final int CARD_HOLDER_INDICATOR_LENGTH = 1;

    /** {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}. */
    private static final int FICO_SCORE_LENGTH = 3;

    /** {@code ACRDLIMI PIC X(15)} and the four other monetary screen fields. */
    private static final int MONEY_DISPLAY_LENGTH = 15;

    /** {@code CDEMO-LAST-MAP PIC X(7)} and {@code CDEMO-LAST-MAPSET PIC X(7)}. */
    private static final int LAST_MAP_LENGTH = 7;

    // ------------------------------------------------------------------------------------------------
    // Numeric and date shape constants.
    // ------------------------------------------------------------------------------------------------

    /** {@code ACCT-CURR-BAL PIC S9(10)V99} and its four siblings: two decimal places. */
    private static final int MONEY_SCALE = 2;

    /** Ten signed integer digits, from {@code PIC S9(10)V99}. */
    private static final int MONEY_INTEGER_DIGITS = 10;

    /** The rounding the migration mandates wherever a monetary value is rescaled. */
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_EVEN;

    /** {@code WS-EDIT-CURRENCY-9-2-F PIC +ZZZ,ZZZ,ZZZ.99}, {@code :372}: three-digit grouping. */
    private static final int MONEY_GROUP_SIZE = 3;

    /** {@code FICO-RANGE-IS-VALID VALUES 300 THROUGH 850}, {@code :848-849}: the lower bound. */
    private static final int FICO_SCORE_MINIMUM = 300;

    /** {@code FICO-RANGE-IS-VALID VALUES 300 THROUGH 850}, {@code :848-849}: the upper bound. */
    private static final int FICO_SCORE_MAXIMUM = 850;

    /** {@code INVALID-SSN-PART1 VALUES 0, 666, 900 THRU 999}, {@code :121-123}: the zero value. */
    private static final int SSN_PART1_INVALID_ZERO = 0;

    /** {@code INVALID-SSN-PART1 VALUES 0, 666, 900 THRU 999}, {@code :121-123}: the singleton value. */
    private static final int SSN_PART1_INVALID_SIX_SIX_SIX = 666;

    /** {@code INVALID-SSN-PART1 VALUES 0, 666, 900 THRU 999}, {@code :121-123}: range lower bound. */
    private static final int SSN_PART1_INVALID_RANGE_FROM = 900;

    /** {@code INVALID-SSN-PART1 VALUES 0, 666, 900 THRU 999}, {@code :121-123}: range upper bound. */
    private static final int SSN_PART1_INVALID_RANGE_TO = 999;

    /** The {@code (1:4)} year slice of a {@code PIC X(10)} date, used on the live record side. */
    private static final int DATE_YEAR_END = 4;

    /** The {@code (6:2)} month slice of a dash-separated date: begin index five, zero-based. */
    private static final int DASHED_DATE_MONTH_BEGIN = 5;

    /** The {@code (6:2)} month slice of a dash-separated date: end index seven, zero-based. */
    private static final int DASHED_DATE_MONTH_END = 7;

    /** The {@code (9:2)} day slice of a dash-separated date: begin index eight, zero-based. */
    private static final int DASHED_DATE_DAY_BEGIN = 8;

    /** The {@code (9:2)} day slice of a dash-separated date: end index ten, zero-based. */
    private static final int DASHED_DATE_DAY_END = 10;

    /** The {@code (1:3)} area slice of a nine-digit social security number. */
    private static final int SSN_AREA_END = 3;

    /** The {@code (4:2)} group slice of a nine-digit social security number: end index five. */
    private static final int SSN_GROUP_END = 5;

    /** How many trailing characters of a masked identifier stay visible in a log line. */
    private static final int MASK_VISIBLE_DIGITS = 4;

    // ------------------------------------------------------------------------------------------------
    // Screen attribute names, from the CICS-supplied copybooks DFHBMSCA (:615) and DFHAID (:616). Those
    // copybooks are supplied by the transaction monitor, are absent from this repository and get no Java
    // import; their values travel as names so a client can render them without a 3270.
    // ------------------------------------------------------------------------------------------------

    /** {@code DFHBMFSE}: unprotected with the modified-data tag forced on. */
    private static final String ATTRIBUTE_UNPROTECTED_FSET = "DFHBMFSE";

    /** {@code DFHBMPRF}: protected with the modified-data tag forced on. */
    private static final String ATTRIBUTE_PROTECTED_FSET = "DFHBMPRF";

    /** {@code DFHBMASB}: autoskip, bright. */
    private static final String ATTRIBUTE_BRIGHT = "DFHBMASB";

    /** {@code DFHBMDAR}: autoskip, dark. */
    private static final String ATTRIBUTE_DARK = "DFHBMDAR";

    /** {@code DFHRED}: the colour {@code CSSETATY} moves into a field in error. */
    private static final String COLOUR_RED = "DFHRED";

    /** {@code DFHDFCOL}: the default colour a field carries when it is not in error. */
    private static final String COLOUR_DEFAULT = "DFHDFCOL";

    /** The single asterisk {@code CSSETATY} writes into a field that is blank rather than wrong. */
    private static final String ASTERISK = "*";

    /**
     * The affirmative half of {@code 88 FLG-YES-NO-ISVALID VALUES 'Y', 'N'} at
     * {@code app/cbl/COACTUPC.cbl:78}, repeated as {@code 88 FLG-ACCT-STATUS-ISVALID} at {@code :193}.
     * Both condition names test the received character itself rather than a separate flag byte, which is
     * why the literal is hoisted here and compared against the upper-cased input.
     */
    private static final String YES_INDICATOR = "Y";

    /**
     * The negative half of {@code 88 FLG-YES-NO-ISVALID VALUES 'Y', 'N'} at
     * {@code app/cbl/COACTUPC.cbl:78}. Note that {@code WS-EDIT-YES-NO} is initialised
     * {@code VALUE 'N'} at {@code :77}, so an unset field already reads as a valid negative.
     */
    private static final String NO_INDICATOR = "N";

    /** The cursor-position value a symbolic map length field carries to request the cursor. */
    private static final int CURSOR_HERE = -1;

    /** {@code DFHENTER} from the CICS-supplied {@code DFHAID} copybook, {@code COPY} at {@code :616}. */
    private static final String ATTENTION_IDENTIFIER_ENTER = "DFHENTER";

    /** {@code DFHPF5} from {@code DFHAID}: the key that commits a validated payload at {@code :2603}. */
    private static final String ATTENTION_IDENTIFIER_PFK05 = "DFHPF5";

    /**
     * The {@code DFH} prefix every {@code DFHAID} symbol carries. Stripped before matching so that a REST
     * caller may send either the CICS spelling {@code DFHPF5} or the bare {@code PF5}.
     */
    private static final String AID_SYMBOL_PREFIX = "DFH";

    /**
     * {@code 88 CDEMO-USRTYP-USER VALUE 'U'} of {@code app/cpy/COCOM01Y.cpy:28}, the value
     * {@code :947} moves into {@code CDEMO-USER-TYPE} on the exit path.
     */
    private static final String COMMAREA_USER_TYPE_USER = "U";

    /** {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} of {@code app/cpy/COCOM01Y.cpy:27}. */
    private static final String COMMAREA_USER_TYPE_ADMIN = "A";

    /** The field name reported when the whole symbolic-map area is absent from a request. */
    private static final String REQUEST_FIELD = "request";

    /**
     * The field name reported when {@code ACUP-OLD-DETAILS} of {@code :669} is absent from a write
     * request. The group is mandatory because {@code 9700-CHECK-CHANGE-IN-REC} has nothing to compare
     * against without it.
     */
    private static final String OLD_DETAILS_FIELD = "oldDetails";

    /** The field name reported for screen field {@code ACCTSIDI}, edited by {@code 1210-EDIT-ACCOUNT}. */
    private static final String ACCOUNT_FILTER_FIELD = "accountId";

    // ------------------------------------------------------------------------------------------------
    // Header formats. app/cpy/CSDAT01Y.cpy, COPYed at :626, separates the date parts with '/' and the
    // time parts with ':'; CURDATE is eight bytes and CURTIME is eight.
    // ------------------------------------------------------------------------------------------------

    /** {@code WS-CURDATE-MONTH '/' WS-CURDATE-DAY '/' WS-CURDATE-YEAR(3:2)}, {@code :2681-2686}. */
    private static final DateTimeFormatter HEADER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT);

    /** {@code WS-CURTIME-HOURS ':' WS-CURTIME-MINUTE ':' WS-CURTIME-SECOND}, {@code :2688-2693}. */
    private static final DateTimeFormatter HEADER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    // ------------------------------------------------------------------------------------------------
    // Date component offsets. The three account dates and the live date of birth are PIC X(10) text in
    // YYYY-MM-DD form, so 9500-STORE-FETCHED-DATA and 9700-CHECK-CHANGE-IN-REC address them at (1:4),
    // (6:2) and (9:2). Expressed here as zero-based Java offsets.
    // ------------------------------------------------------------------------------------------------

    /** COBOL {@code (1:4)} - the four-digit year. {@code :3832}, {@code :4127}. */
    private static final int DATE_YEAR_OFFSET = 0;

    /** COBOL {@code (6:2)} - the month, skipping the first separator. {@code :3833}, {@code :4128}. */
    private static final int DATE_MONTH_OFFSET = 5;

    /** COBOL {@code (9:2)} - the day, skipping the second separator. {@code :3834}, {@code :4129}. */
    private static final int DATE_DAY_OFFSET = 8;

    /** The reference length of a {@code (1:4)} year component. */
    private static final int DATE_YEAR_LENGTH = 4;

    /** The reference length of a {@code (6:2)} or {@code (9:2)} month or day component. */
    private static final int DATE_PART_LENGTH = 2;


    // ------------------------------------------------------------------------------------------------
    // The terminal CICS abend code of ABEND-ROUTINE, distinct from the CABENDD payload ABEND-CODE that
    // 2000-DECIDE-ACTION sets to '0001', and distinct again from the batch 999 / RC 12 that
    // FatalProcessingException declares for CBTRN02C's CEE3ABD path.
    // ------------------------------------------------------------------------------------------------

    /** {@code EXEC CICS ABEND ABCODE('9999')}, {@code :4221}. Four characters, filling {@code PIC X(4)}. */
    private static final String TERMINAL_ABEND_CODE = "9999";

    /**
     * Refusal text for a write whose carried customer identifier is not the one the cross-reference binds
     * to the account. It names no identifier, neither the submitted one nor the derived one.
     */
    private static final String CUSTOMER_ID_NOT_BOUND_MESSAGE =
            "The customer identifier submitted with this update is not the customer this account is bound "
                    + "to. Re-read the account and resubmit the update.";

    /**
     * Refusal text for a write against an account that no cross-reference record binds to a customer, so
     * there is no customer row this update may lawfully write.
     */
    private static final String CUSTOMER_NOT_BOUND_TO_ACCOUNT_MESSAGE =
            "No cross-reference record binds this account to a customer, so the customer half of the "
                    + "update has no target. Re-read the account and resubmit the update.";

    /** Remediation text for a write submitted without the {@code ACUP-OLD-DETAILS} snapshot. */
    private static final String OLD_DETAILS_REQUIRED_MESSAGE =
            "The ACUP-OLD-DETAILS snapshot of app/cbl/COACTUPC.cbl:669 is required: "
                    + "9700-CHECK-CHANGE-IN-REC compares the live record against it field by field.";

    /** {@code LIT-CCLISTMAPSET}, tested at {@code :3171} to restore the account field's default colour. */
    private static final String MENU_CARD_LIST_MAPSET = "COCRDLI";


    // ------------------------------------------------------------------------------------------------
    // Screen field names, taken verbatim from the symbolic map app/cpy-bms/COACTUP.CPY so that the
    // attribute, colour and cursor maps this bean returns are keyed exactly as the legacy map names them.
    // The trailing suffix letter of the COBOL name - I, O, A, C or L - identifies the sub-field within
    // each generated quintuple and is deliberately NOT part of these keys: one key names one screen
    // field, and the attribute, colour and cursor maps distinguish the aspect.
    // ------------------------------------------------------------------------------------------------

    /** {@code ACCTSID} - the account filter, the only field enterable on a first turn. */
    private static final String FIELD_ACCOUNT_ID = "ACCTSID";

    /** {@code ACSTTUS} - the account active status. */
    private static final String FIELD_ACCOUNT_STATUS = "ACSTTUS";

    /** {@code ACRDLIM} - the credit limit. */
    private static final String FIELD_CREDIT_LIMIT = "ACRDLIM";

    /** {@code ACSHLIM} - the cash credit limit. */
    private static final String FIELD_CASH_CREDIT_LIMIT = "ACSHLIM";

    /** {@code ACURBAL} - the current balance. */
    private static final String FIELD_CURRENT_BALANCE = "ACURBAL";

    /** {@code ACRCYCR} - the current cycle credit. */
    private static final String FIELD_CURRENT_CYCLE_CREDIT = "ACRCYCR";

    /** {@code ACRCYDB} - the current cycle debit. */
    private static final String FIELD_CURRENT_CYCLE_DEBIT = "ACRCYDB";

    /** {@code OPNYEAR} - the account open date's year component. */
    private static final String FIELD_OPEN_DATE_YEAR = "OPNYEAR";

    /** {@code OPNMON} - the account open date's month component. */
    private static final String FIELD_OPEN_DATE_MONTH = "OPNMON";

    /** {@code OPNDAY} - the account open date's day component. */
    private static final String FIELD_OPEN_DATE_DAY = "OPNDAY";

    /** {@code EXPYEAR} - the expiry date's year component. */
    private static final String FIELD_EXPIRY_DATE_YEAR = "EXPYEAR";

    /** {@code EXPMON} - the expiry date's month component. */
    private static final String FIELD_EXPIRY_DATE_MONTH = "EXPMON";

    /** {@code EXPDAY} - the expiry date's day component. */
    private static final String FIELD_EXPIRY_DATE_DAY = "EXPDAY";

    /** {@code RISYEAR} - the reissue date's year component. */
    private static final String FIELD_REISSUE_DATE_YEAR = "RISYEAR";

    /** {@code RISMON} - the reissue date's month component. */
    private static final String FIELD_REISSUE_DATE_MONTH = "RISMON";

    /** {@code RISDAY} - the reissue date's day component. */
    private static final String FIELD_REISSUE_DATE_DAY = "RISDAY";

    /** {@code AADDGRP} - the account group identifier. Never edited, never cursored. */
    private static final String FIELD_ACCOUNT_GROUP_ID = "AADDGRP";

    /** {@code ACSTNUM} - the customer identifier; re-protected by {@code 3320} at {@code :3531}. */
    private static final String FIELD_CUSTOMER_ID = "ACSTNUM";

    /** {@code ACTSSN1} - the social security number's three-digit area part. */
    private static final String FIELD_SSN_PART1 = "ACTSSN1";

    /** {@code ACTSSN2} - the social security number's two-digit group part. */
    private static final String FIELD_SSN_PART2 = "ACTSSN2";

    /** {@code ACTSSN3} - the social security number's four-digit serial part. */
    private static final String FIELD_SSN_PART3 = "ACTSSN3";

    /** {@code ACSTFCO} - the FICO credit score. */
    private static final String FIELD_FICO_SCORE = "ACSTFCO";

    /** {@code DOBYEAR} - the date of birth's year component. */
    private static final String FIELD_DATE_OF_BIRTH_YEAR = "DOBYEAR";

    /** {@code DOBMON} - the date of birth's month component. */
    private static final String FIELD_DATE_OF_BIRTH_MONTH = "DOBMON";

    /** {@code DOBDAY} - the date of birth's day component. */
    private static final String FIELD_DATE_OF_BIRTH_DAY = "DOBDAY";

    /** {@code ACSFNAM} - the customer's first name. */
    private static final String FIELD_FIRST_NAME = "ACSFNAM";

    /** {@code ACSMNAM} - the customer's middle name; the only optional-alpha field. */
    private static final String FIELD_MIDDLE_NAME = "ACSMNAM";

    /** {@code ACSLNAM} - the customer's last name. */
    private static final String FIELD_LAST_NAME = "ACSLNAM";

    /** {@code ACSADL1} - address line one. */
    private static final String FIELD_ADDRESS_LINE_1 = "ACSADL1";

    /** {@code ACSADL2} - address line two. Attribute-mapped at {@code :3371} but never edited. */
    private static final String FIELD_ADDRESS_LINE_2 = "ACSADL2";

    /** {@code ACSCITY} - the city field, which carries {@code CUST-ADDR-LINE-3}. */
    private static final String FIELD_CITY = "ACSCITY";

    /** {@code ACSSTTE} - the two-character state code. */
    private static final String FIELD_STATE_CODE = "ACSSTTE";

    /** {@code ACSZIPC} - the postal code. */
    private static final String FIELD_ZIP = "ACSZIPC";

    /** {@code ACSCTRY} - the country code; re-protected by {@code 3320} at {@code :3547}. */
    private static final String FIELD_COUNTRY_CODE = "ACSCTRY";

    /** {@code ACSPH1A} - the first telephone number's area code. */
    private static final String FIELD_PHONE_1_AREA_CODE = "ACSPH1A";

    /** {@code ACSPH1B} - the first telephone number's prefix. */
    private static final String FIELD_PHONE_1_PREFIX = "ACSPH1B";

    /** {@code ACSPH1C} - the first telephone number's line number. */
    private static final String FIELD_PHONE_1_LINE_NUMBER = "ACSPH1C";

    /** {@code ACSPH2A} - the second telephone number's area code. */
    private static final String FIELD_PHONE_2_AREA_CODE = "ACSPH2A";

    /** {@code ACSPH2B} - the second telephone number's prefix. */
    private static final String FIELD_PHONE_2_PREFIX = "ACSPH2B";

    /** {@code ACSPH2C} - the second telephone number's line number. */
    private static final String FIELD_PHONE_2_LINE_NUMBER = "ACSPH2C";

    /** {@code ACSGOVT} - the government-issued identifier. Unprotected at {@code :3557}, never edited. */
    private static final String FIELD_GOVERNMENT_ISSUED_ID = "ACSGOVT";

    /** {@code ACSEFTC} - the electronic funds transfer account identifier. */
    private static final String FIELD_EFT_ACCOUNT_ID = "ACSEFTC";

    /** {@code ACSPFLG} - the primary card holder indicator. */
    private static final String FIELD_PRIMARY_CARD_HOLDER = "ACSPFLG";

    /** {@code INFOMSG} - the forty-character information message. */
    private static final String FIELD_INFORMATION_MESSAGE = "INFOMSG";

    /** {@code FKEY05} - the PF05 legend, brightened by {@code 3390} at {@code :3579}. */
    private static final String FIELD_FUNCTION_KEY_05 = "FKEY05";

    /** {@code FKEY12} - the PF12 legend, brightened by {@code 3390} at {@code :3575} and {@code :3580}. */
    private static final String FIELD_FUNCTION_KEY_12 = "FKEY12";

    /**
     * The forty-four attribute bytes {@code 3310-PROTECT-ALL-ATTRS} sets to {@code DFHBMPRF} at
     * {@code :3442-3494}, in the source's own order. Note that {@code ACCTSID} heads the list and
     * {@code INFOMSG} closes it, which is why every branch of {@code 3300}'s first decider must re-enable
     * the account filter explicitly.
     */
    private static final List<String> ALL_PROTECTABLE_FIELDS = List.of(
            FIELD_ACCOUNT_ID, FIELD_ACCOUNT_STATUS, FIELD_CREDIT_LIMIT, FIELD_CASH_CREDIT_LIMIT,
            FIELD_CURRENT_BALANCE, FIELD_CURRENT_CYCLE_CREDIT, FIELD_CURRENT_CYCLE_DEBIT,
            FIELD_OPEN_DATE_YEAR, FIELD_OPEN_DATE_MONTH, FIELD_OPEN_DATE_DAY,
            FIELD_EXPIRY_DATE_YEAR, FIELD_EXPIRY_DATE_MONTH, FIELD_EXPIRY_DATE_DAY,
            FIELD_REISSUE_DATE_YEAR, FIELD_REISSUE_DATE_MONTH, FIELD_REISSUE_DATE_DAY,
            FIELD_ACCOUNT_GROUP_ID, FIELD_CUSTOMER_ID,
            FIELD_SSN_PART1, FIELD_SSN_PART2, FIELD_SSN_PART3, FIELD_FICO_SCORE,
            FIELD_DATE_OF_BIRTH_YEAR, FIELD_DATE_OF_BIRTH_MONTH, FIELD_DATE_OF_BIRTH_DAY,
            FIELD_FIRST_NAME, FIELD_MIDDLE_NAME, FIELD_LAST_NAME,
            FIELD_ADDRESS_LINE_1, FIELD_ADDRESS_LINE_2, FIELD_CITY, FIELD_STATE_CODE,
            FIELD_ZIP, FIELD_COUNTRY_CODE,
            FIELD_PHONE_1_AREA_CODE, FIELD_PHONE_1_PREFIX, FIELD_PHONE_1_LINE_NUMBER,
            FIELD_PHONE_2_AREA_CODE, FIELD_PHONE_2_PREFIX, FIELD_PHONE_2_LINE_NUMBER,
            FIELD_GOVERNMENT_ISSUED_ID, FIELD_EFT_ACCOUNT_ID, FIELD_PRIMARY_CARD_HOLDER,
            FIELD_INFORMATION_MESSAGE);

    /**
     * The fields {@code 3320-UNPROTECT-FEW-ATTRS} sets to {@code DFHBMFSE}, gathered from its three
     * {@code MOVE} runs at {@code :3502-3529}, {@code :3532-3545} and {@code :3549-3559}.
     * <p>Four fields are deliberately absent, and each absence is behaviour. {@code ACCTSID} never appears
     * in any of the three runs, so the account filter stays protected once details are on display and the
     * operator cannot switch accounts mid-edit. {@code ACSTNUM}, {@code ACSCTRY} and {@code INFOMSG} are
     * re-protected immediately afterwards at {@code :3531}, {@code :3547} and {@code :3560}, which
     * {@link #unprotectFewAttributes3320} applies as three explicit statements rather than by omitting them
     * here, mirroring the source's own interleaving.</p>
     */
    private static final List<String> UNPROTECTABLE_FIELDS = List.of(
            FIELD_ACCOUNT_STATUS, FIELD_CREDIT_LIMIT, FIELD_CASH_CREDIT_LIMIT,
            FIELD_CURRENT_BALANCE, FIELD_CURRENT_CYCLE_CREDIT, FIELD_CURRENT_CYCLE_DEBIT,
            FIELD_OPEN_DATE_YEAR, FIELD_OPEN_DATE_MONTH, FIELD_OPEN_DATE_DAY,
            FIELD_EXPIRY_DATE_YEAR, FIELD_EXPIRY_DATE_MONTH, FIELD_EXPIRY_DATE_DAY,
            FIELD_REISSUE_DATE_YEAR, FIELD_REISSUE_DATE_MONTH, FIELD_REISSUE_DATE_DAY,
            FIELD_DATE_OF_BIRTH_YEAR, FIELD_DATE_OF_BIRTH_MONTH, FIELD_DATE_OF_BIRTH_DAY,
            FIELD_ACCOUNT_GROUP_ID, FIELD_CUSTOMER_ID,
            FIELD_SSN_PART1, FIELD_SSN_PART2, FIELD_SSN_PART3, FIELD_FICO_SCORE,
            FIELD_FIRST_NAME, FIELD_MIDDLE_NAME, FIELD_LAST_NAME,
            FIELD_ADDRESS_LINE_1, FIELD_ADDRESS_LINE_2, FIELD_CITY, FIELD_STATE_CODE, FIELD_ZIP,
            FIELD_COUNTRY_CODE,
            FIELD_PHONE_1_AREA_CODE, FIELD_PHONE_1_PREFIX, FIELD_PHONE_1_LINE_NUMBER,
            FIELD_PHONE_2_AREA_CODE, FIELD_PHONE_2_PREFIX, FIELD_PHONE_2_LINE_NUMBER,
            FIELD_GOVERNMENT_ISSUED_ID, FIELD_EFT_ACCOUNT_ID, FIELD_PRIMARY_CARD_HOLDER,
            FIELD_INFORMATION_MESSAGE);



    // ------------------------------------------------------------------------------------------------
    // Collaborators. Eight, all final, all constructor-injected, none static.
    // ------------------------------------------------------------------------------------------------

    /**
     * Access point for {@code CCXREF} through its account path {@code CXACAIX}, resolving the account
     * filter to a customer identifier before either master record is read.
     */
    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    /**
     * Access point for {@code ACCTDAT}. Its read-for-update finder is the first of the two locked reads of
     * {@code 9600-WRITE-PROCESSING} at {@code app/cbl/COACTUPC.cbl:L3894-L3906}.
     */
    private final AccountRepository accountRepository;

    /**
     * Access point for {@code CUSTDAT}. Its read-for-update finder is the second locked read, at
     * {@code app/cbl/COACTUPC.cbl:L3920-L3932}, and the one whose rewrite failure triggers the explicit
     * backout.
     */
    private final CustomerRepository customerRepository;

    /** The sole owner of the status-to-exception decision; never re-implemented here. */
    private final FileStatusMapper fileStatusMapper;

    /**
     * The single bean replacing {@code CALL 'CSUTLDTC'} together with both of its work-area copybooks,
     * consulted by each of the three date edits.
     */
    private final DateValidationService dateValidationService;

    /**
     * The lookup tables of {@code app/cpy/CSLKPCDY.cpy}, consulted for the state code, the ZIP-prefix
     * combination and the three telephone area codes.
     */
    private final ValidationLookupService validationLookupService;

    /**
     * Injected time source replacing {@code FUNCTION CURRENT-DATE}, including the deliberately duplicated
     * read at {@code app/cbl/COACTUPC.cbl:2671} and {@code :2678}.
     */
    private final Clock clock;

    /**
     * Constructs the bean. Constructor injection is the only injection form used: there is no field
     * {@code @Autowired}, no setter injection, no service-locator lookup and no {@code ApplicationContext}
     * access anywhere in this class, which is what Rule 1 Clause B requires when it asks that global mutable
     * state be avoided in favour of dependency injection.
     * <p>The body is pure field assignment. No overridable method is invoked and {@code this} does not
     * escape, so the compiler's {@code this-escape} analysis - fatal under {@code -Werror} - has nothing to
     * report.</p>
     * <p><strong>Seven collaborators are taken, two more than the sibling
     * {@code AccountViewService}, and the difference is grounded in the two programs' copybook sets rather
     * than in preference.</strong> A census of the {@code WORKING-STORAGE SECTION} of
     * {@code app/cbl/COACTUPC.cbl} finds {@code COPY 'CSUTLDWY'} at {@code :166} and
     * {@code COPY CSUTLDPY} at {@code :4232}, and the {@code PROCEDURE DIVISION} performs
     * {@code EDIT-DATE-CCYYMMDD THRU EDIT-DATE-CCYYMMDD-EXIT} at {@code :1480-1481}, {@code :1492-1493},
     * {@code :1505-1506} and {@code :1536-1537} and {@code EDIT-DATE-OF-BIRTH THRU
     * EDIT-DATE-OF-BIRTH-EXIT} at {@code :1540-1541}; that is what makes
     * {@code DateValidationService} necessary. {@code COPY CSLKPCDY} at {@code :602} brings in the five
     * eighty-eight-level lookup tables consumed by {@code EDIT-AREA-CODE},
     * {@code 1270-EDIT-US-STATE-CD} and {@code 1280-EDIT-US-STATE-ZIP-CD}; that is what makes
     * {@code ValidationLookupService} necessary. {@code AccountViewService} references none of the four
     * copybooks and injects neither bean. The two constructors are deliberately not harmonised.</p>
     * <p>No {@code Card} collaborator and no {@code Card} entity appears, because this program does not
     * {@code COPY CVACT02Y} at all; the card number it displays comes from the cross-reference record
     * ({@code XREF-CARD-NUM}) and never from the card master.</p>
     * @param cardCrossReferenceRepository the {@code CCXREF} accessor, reached through the account-keyed
     *                                     alternate-index path {@code CXACAIX} that
     *                                     {@code 9200-GETCARDXREF-BYACCT} reads at {@code :3655-3667};
     *                                     must not be {@code null}
     * @param accountRepository            the {@code ACCTDAT} accessor used by
     *                                     {@code 9300-GETACCTDATA-BYACCT} for the browse read and by
     *                                     {@code 9600-WRITE-PROCESSING} for the read-for-update at
     *                                     {@code :3894-3903} and the rewrite at {@code :4065-4071};
     *                                     must not be {@code null}
     * @param customerRepository           the {@code CUSTDAT} accessor used by
     *                                     {@code 9400-GETCUSTDATA-BYCUST} and, for the read-for-update at
     *                                     {@code :3921-3930} and the rewrite at {@code :4085-4091}, by
     *                                     {@code 9600-WRITE-PROCESSING}; must not be {@code null}
     * @param fileStatusMapper             the sole owner of the file-status-to-exception decision and of
     *                                     the {@code FILE STATUS IS: NNNN} rendering, neither of which is
     *                                     reimplemented here; must not be {@code null}
     * @param dateValidationService        the single bean that subsumes {@code CALL 'CSUTLDTC'},
     *                                     {@code app/cpy/CSUTLDPY.cpy} and {@code app/cpy/CSUTLDWY.cpy},
     *                                     and which owns all fourteen date-editing labels; must not be
     *                                     {@code null}
     * @param validationLookupService      the single bean that owns the five {@code CSLKPCDY} lookup
     *                                     tables, externalised as three classpath JSON resources; must not
     *                                     be {@code null}
     * @param clock                        the time source for the two {@code FUNCTION CURRENT-DATE}
     *                                     invocations of {@code 3100-SCREEN-INIT} at {@code :2673} and
     *                                     {@code :2674}; must not be {@code null}. A constructor-injected
     *                                     clock is what makes the header projection deterministic and
     *                                     testable; {@code LocalDate.now()} and
     *                                     {@code LocalDateTime.now()} with no argument are never called
     */
    public AccountUpdateService(final CardCrossReferenceRepository cardCrossReferenceRepository,
                                final AccountRepository accountRepository,
                                final CustomerRepository customerRepository,
                                final FileStatusMapper fileStatusMapper,
                                final DateValidationService dateValidationService,
                                final ValidationLookupService validationLookupService,
                                final Clock clock) {
        this.cardCrossReferenceRepository = cardCrossReferenceRepository;
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.fileStatusMapper = fileStatusMapper;
        this.dateValidationService = dateValidationService;
        this.validationLookupService = validationLookupService;
        this.clock = clock;
    }

    /**
     * The screen-parity entry point: one complete pass of the CICS pseudo-conversational transaction
     * {@code CAUP}, conducted from attention-identifier remapping through the edit cascade, the dispatch
     * decider and - when the caller pressed PF05 on a validated payload - the dual-dataset write, ending in
     * the projected screen.
     * <p>A lookup miss and a rejected edit are <strong>not</strong> signalled by an exception on this path.
     * The legacy program treats each as a control path that sets a field-error state and renders a
     * diagnostic into {@code WS-RETURN-MSG}, and that is reproduced exactly. Every typed exception the
     * situation warrants is nonetheless constructed and retained on the request context, so nothing is
     * swallowed; {@link #updateAccount(AccountUpdateRequest)} rethrows it.</p>
     * <p>The transaction boundary is declared here rather than deeper, and it is unconditional. It spans
     * the account rewrite of {@code :4065-4071} and the customer rewrite of {@code :4085-4091} as one unit
     * of work, which is what reproduces the source's asymmetric rollback without a single conditional: on
     * the earlier failure path nothing has been written yet and the source issues no backout, while on the
     * later path the account rewrite has already happened and the source issues
     * {@code EXEC CICS SYNCPOINT ROLLBACK} at {@code :4099-4101}. Returning or throwing before the commit
     * point produces both behaviours automatically.</p>
     * @param request             the symbolic-map area as received, carrying the fifty-four
     *                            {@code app/cpy-bms/COACTUP.CPY} input fields plus the two snapshot groups
     *                            {@code ACUP-OLD-DETAILS} of {@code :669} and {@code ACUP-NEW-DETAILS} of
     *                            {@code :757}. Must not be {@code null}
     * @param attentionIdentifier the raw {@code EIBAID} symbol, for example {@code DFHENTER},
     *                            {@code DFHPF5}, {@code PF12} or {@code PFK03}. Matching is
     *                            case-insensitive under {@code Locale.ROOT}. Per {@code :905-916} the
     *                            valid set is four conditions wide - Enter, PF03, PF05 when the payload is
     *                            already validated but unconfirmed, and PF12 when details have been
     *                            fetched - and anything else, {@code null} included, is silently coerced
     *                            to Enter
     * @param changeAction        the {@code ACUP-CHANGE-ACTION} marker of {@code :656-668} as it stood at
     *                            the end of the previous turn, echoed back by the client because a
     *                            stateless server holds no COMMAREA. {@code null} is legal and reproduces
     *                            {@code ACUP-DETAILS-NOT-FETCHED}, whose eighty-eight level is
     *                            {@code VALUES LOW-VALUES, SPACES}
     * @param entryMode           the reconstructed {@code CDEMO-PGM-CONTEXT} of
     *                            {@code app/cpy/COCOM01Y.cpy:29-31}. {@code null} is legal and is treated
     *                            as first entry, the state {@code :880-886} reaches when
     *                            {@code EIBCALEN = 0}
     * @return the projected outcome; never {@code null}
     * @throws ValidationException      when {@code request} is {@code null}
     * @throws FatalProcessingException if any unmodelled runtime failure reaches the abend handler
     *                                  registered at {@code :862-864}, carrying the terminal abend code
     *                                  {@code 9999}, culprit {@code COACTUPC} and the original throwable
     *                                  as its cause
     */
    @Transactional(rollbackFor = Exception.class)
    public AccountUpdateResult processRequest(final AccountUpdateRequest request,
                                             final String attentionIdentifier,
                                             final ChangeAction changeAction,
                                             final EntryMode entryMode) {
        if (request == null) {
            throw ValidationException.missingField(REQUEST_FIELD,
                    "The symbolic-map area of app/cpy-bms/COACTUP.CPY is required; a CICS RECEIVE MAP"
                            + " always delivers one.");
        }
        // The in-process entry point takes the snapshot from the request, because its caller is this
        // application reproducing one screen turn rather than a client asserting a precondition.
        return mainLine0000(new UpdateContext(request, attentionIdentifier, changeAction, entryMode,
                request == null ? null : request.getOldDetails()));
    }

    /**
     * The REST write entry point, surfaced by {@code com.cardemo.controller.AccountController} under
     * {@code /api/accounts/*}. It drives the identical conversation as
     * {@link #processRequest(AccountUpdateRequest, String, ChangeAction, EntryMode)}, and it drives
     * <strong>both</strong> of the screen turns the source needs to write, because one stateless call is
     * all the caller gets.
     *
     * <h4>Why two turns, and why one is not enough</h4>
     * <p>{@code COACTUPC} is pseudo-conversational and validates on a different turn from the one it writes
     * on. Turn one arrives with {@code ACUP-SHOW-DETAILS} and Enter: {@code 1200-EDIT-MAP-INPUTS} runs
     * {@code 1205-COMPARE-OLD-NEW} and then, when the operator changed something, the twenty-four field
     * edits of {@code 1210} through {@code 1280}, and {@code :1671-1675} promotes the marker to
     * {@code ACUP-CHANGES-OK-NOT-CONFIRMED} only when no edit failed. Turn two arrives with that promoted
     * marker and PF05, and {@code :2602-2603} writes. The guard at {@code :1463-1468} -
     * {@code IF NO-CHANGES-FOUND OR ACUP-CHANGES-OK-NOT-CONFIRMED OR ACUP-CHANGES-OKAYED-AND-DONE GO TO
     * 1200-EDIT-MAP-INPUTS-EXIT} - is what stops turn two from re-running edits that turn one already
     * performed.</p>
     * <p>Entering the conversation directly at turn two, as this method once did, made that guard fire on
     * <em>every</em> request: the edit cascade became unreachable, so an out-of-range FICO score, an
     * impossible date, an unlisted state, country or NANPA area code and a negative credit limit were all
     * written to the two datasets, and the {@code NO-CHANGES-DETECTED} outcome of {@code :2588} could never
     * be reported because the decider's {@code ACUP-SHOW-DETAILS} arm was never evaluated. This method
     * therefore performs turn one, inspects the marker the source itself would have carried forward, and
     * performs turn two only when the source would have offered PF05. The guard is untouched; what changed
     * is that both turns now happen, which is what collapsing a two-turn conversation onto one call
     * means.</p>
     * <p>Turn one is a pure computation over the submitted map and its {@code oldDetails} group - it performs no
     * read for update and no write - so running it costs one comparison pass and cannot affect the store.
     * Both turns share the single {@code @Transactional} boundary, so the seven-step write sequence and its
     * asymmetric rollback behave exactly as documented on {@link #writeProcessing9600}.</p>
     *
     * <p>The order of the checks below is dictated by the source. {@code 1200-EDIT-MAP-INPUTS} runs before
     * {@code 2000-DECIDE-ACTION}, so an edit failure precludes a write; but the write's own failures also
     * raise {@code INPUT-ERROR} at {@code :3910} and {@code :3937}. Testing the retained typed failure
     * first therefore reports a lock or concurrency outcome as such rather than mislabelling it as bad
     * input.</p>
     * <p><strong>Defect D1, severity BLOCKER, is visible here.</strong> A customer read-for-update failure
     * sets {@code COULD-NOT-LOCK-CUST-FOR-UPDATE} at {@code :3939} and returns with nothing written, yet
     * the post-write dispatch at {@code :2606-2615} never tests that condition, so the outcome falls
     * through {@code WHEN OTHER} and is reported as {@code ACUP-CHANGES-OKAYED-AND-DONE} - a top-level
     * success. This method reproduces that, because parity is the contract. The internal outcome stays
     * distinguishable on the result and in the structured log; the reported outcome does not.</p>
     * @param request the submitted map - the fifty-four screen fields and <b>both</b> snapshot groups; must
     *                not be {@code null}. Its {@code oldDetails} group carries {@code ACUP-OLD-DETAILS} as
     *                the preceding read projected it and is what {@code 9700-CHECK-CHANGE-IN-REC} compares
     *                the live record against, field by field. Without it the change detection has nothing
     *                to compare against, and silently skipping the comparison would forfeit the guarantee
     *                the source provides, so an absent group is reported as a {@code ValidationException}
     *                naming {@code oldDetails} rather than treated as "nothing changed"
     * @return the projected outcome, whose {@link AccountUpdateResult#changeAction()} carries the
     *         {@code ACUP} marker the next turn must echo; never {@code null}
     * @throws ValidationException        when the payload or its snapshot group is missing, or when any of
     *                                    the fifty-four field edits rejects its input. The exception
     *                                    carries the offending field name and, through
     *                                    {@code FailureKind}, whether the value was blank or wrong
     * @throws ConcurrentUpdateException  with {@code Outcome.COULD_NOT_LOCK_ACCOUNT} when the account
     *                                    read-for-update fails at {@code :3907-3915}, with
     *                                    {@code Outcome.DATA_CHANGED_BEFORE_UPDATE} when the snapshot
     *                                    comparison of {@code :4109-4193} detects a change, and with
     *                                    {@code Outcome.LOCKED_BUT_UPDATE_FAILED} when either rewrite
     *                                    fails at {@code :4079-4080} or {@code :4098-4102}
     * @throws RecordNotFoundException    when the cross-reference, the account master or the customer
     *                                    master has no matching record
     * @throws DataIntegrityException     when a write violates one of the schema's referential constraints
     * @throws FileAccessException        for a physical or logical input-output failure - the
     *                                    {@code FILE STATUS '9x'} family - carrying the expanded
     *                                    four-character rendering that {@code FileStatus} produces
     * @throws CardDemoException          for any other file status, the concrete subtype being chosen by
     *                                    {@code FileStatusMapper}
     */
    @Transactional(rollbackFor = Exception.class)
    public AccountUpdateResult updateAccount(final AccountUpdateRequest request) {
        if (request == null) {
            throw ValidationException.missingField(REQUEST_FIELD,
                    "The symbolic-map area of app/cpy-bms/COACTUP.CPY is required.");
        }
        // The snapshot is the request body's own ACUP-OLD-DETAILS group and comes from nowhere else.
        // Transformation Rule 7 moves the storage lifetime WS-THIS-PROGCOMMAREA held at :652 onto the
        // request, so the caller returns the group the preceding read projected. Nothing is normalised on
        // the way in: compareOldNew1205 and checkChangeInRecord9700 each report an absent group as a
        // ValidationException on oldDetails rather than skipping the comparison, which is what keeps the
        // guarantee the source provides from being silently forfeited.
        final AccountUpdateRequest.OldDetails authenticOldDetails = request.getOldDetails();

        // TURN ONE - the ENTER turn on a displayed screen. ACUP-SHOW-DETAILS is the marker the source
        // carries into it, which is precisely the marker :1463-1468 does NOT skip, so 1205-COMPARE-OLD-NEW
        // runs and, when the user changed something, the whole 1210-1280 cascade runs behind it. On the way
        // out :1671-1675 promotes the marker to ACUP-CHANGES-OK-NOT-CONFIRMED when no edit failed, and
        // 2000-DECIDE-ACTION's :2585-2591 arm leaves it at ACUP-SHOW-DETAILS when an edit failed or when
        // the comparison found nothing changed.
        final UpdateContext validation = new UpdateContext(request,
                ATTENTION_IDENTIFIER_ENTER,
                ChangeAction.SHOW_DETAILS,
                EntryMode.REENTER,
                authenticOldDetails);
        final AccountUpdateResult validated = mainLine0000(validation);
        if (validation.pendingFailure != null) {
            throw validation.pendingFailure;
        }
        if (validation.inputError) {
            throw validationFailure(validation);
        }
        if (validation.changeAction != ChangeAction.CHANGES_OK_NOT_CONFIRMED) {
            // NO-CHANGES-DETECTED. :1682 and :1769 set it, :2588's CONTINUE keeps ACUP-SHOW-DETAILS, and
            // the write branch at :2602-2603 is never reached, so nothing is written. Returning the turn-one
            // projection reports the source's own 'No change detected with respect to values fetched.'
            // literal with applied false, rather than committing a rewrite the source declined to make.
            return validated;
        }

        // TURN TWO - the PF05 confirmation turn, the one combination that reaches 9600-WRITE-PROCESSING
        // through :2602-2603. A fresh context is used rather than the first one because the source's second
        // turn re-receives the map into a re-initialised working storage: :1047 INITIALIZE ACUP-NEW-DETAILS
        // and :1466 MOVE LOW-VALUES TO WS-NON-KEY-FLAGS both run again, and reusing the first context would
        // carry the edit flags of a turn the source had already discarded into the write.
        final UpdateContext write = new UpdateContext(request,
                ATTENTION_IDENTIFIER_PFK05,
                ChangeAction.CHANGES_OK_NOT_CONFIRMED,
                EntryMode.REENTER,
                authenticOldDetails);
        final AccountUpdateResult result = mainLine0000(write);
        if (write.pendingFailure != null) {
            throw write.pendingFailure;
        }
        if (write.inputError) {
            throw validationFailure(write);
        }
        return result;
    }

    /**
     * The REST read entry point, surfaced by {@code com.cardemo.controller.AccountController} under
     * {@code /api/accounts/*}. It drives the conversation with Enter as the attention identifier and a
     * re-entered context still carrying {@code ACUP-DETAILS-NOT-FETCHED}, which is the combination that
     * reaches {@code WHEN OTHER} at {@code :996} and so takes the {@code :2568} arm of the decider,
     * performs {@code 9000-READ-ACCT} and populates the screen with the fetched values and the snapshot
     * the subsequent write must echo back.
     * <p>No padding, trimming or case folding is applied to {@code accountFilter} on the caller's behalf.
     * {@code 1210-EDIT-ACCOUNT} at {@code :1783-1820} requires all eleven bytes of the {@code X(11)}
     * screen field to be digits and rejects all-zeroes, so {@code "1"} is invalid input rather than
     * account one. That is preserved.</p>
     * @param accountFilter the account identifier as typed into screen field {@code ACCTSIDI}.
     *                      {@code null}, empty, all blanks and the single character {@code *} all mean
     *                      "not supplied"; a run of asterisks such as {@code ***} does not, and is
     *                      rejected as invalid
     * @return the projected screen with the account, customer and cross-reference values in place and both
     *         snapshot groups populated; never {@code null}
     * @throws ValidationException     when the account filter is blank - {@code FailureKind.BLANK} - or is
     *                                 non-numeric, short or all-zeroes - {@code FailureKind.INVALID}
     * @throws RecordNotFoundException when the cross-reference, the account master or the customer master
     *                                 has no matching record
     * @throws FileAccessException     for a physical or logical input-output failure - the
     *                                 {@code FILE STATUS '9x'} family - carrying the expanded
     *                                 four-character rendering that {@code FileStatus} produces
     * @throws CardDemoException       for any other file status, the concrete subtype being chosen by
     *                                 {@code FileStatusMapper}
     */
    @Transactional(readOnly = true)
    public AccountUpdateResult fetchForUpdate(final String accountFilter) {
        final AccountUpdateRequest request = emptyScreenRequest(accountFilter);
        // The seeded state is the SECOND turn of the legacy conversation, not the first. On the first
        // turn :880-886 sets CDEMO-PGM-ENTER and ACUP-DETAILS-NOT-FETCHED, and the :964-965 arm then
        // paints an empty screen and sets CDEMO-PGM-REENTER at :971 without reading anything. The read
        // happens on the turn after that, when ACUP-DETAILS-NOT-FETCHED is still true but the program is
        // re-entered: the :964 arm no longer matches, WHEN OTHER at :996 runs 1000-PROCESS-INPUTS then
        // 2000-DECIDE-ACTION, and the decider's :2568 arm performs 9000-READ-ACCT. Seeding
        // EntryMode.ENTER here would reproduce the empty first paint instead and never read the account.
        final UpdateContext context = new UpdateContext(request,
                ATTENTION_IDENTIFIER_ENTER,
                ChangeAction.DETAILS_NOT_FETCHED,
                EntryMode.REENTER,
                null);
        final AccountUpdateResult result = mainLine0000(context);
        if (context.pendingFailure != null) {
            throw context.pendingFailure;
        }
        if (context.inputError) {
            throw validationFailure(context);
        }
        return result;
    }

    /**
     * Reads one account and returns the {@code ACUP-OLD-DETAILS} group its update will require.
     *
     * <p><b>What it does.</b> It drives exactly the conversation {@link #fetchForUpdate(String)} drives -
     * Enter, re-entered, {@code ACUP-DETAILS-NOT-FETCHED}, so that the {@code :2568} arm of the decider
     * performs {@code 9000-READ-ACCT} - and then returns what {@code 9500-STORE-FETCHED-DATA} stored at
     * {@code :3805-3813}. It exists because {@code fetchForUpdate} projects the whole screen, of which the
     * snapshot group is one part, and the read operation needs that part on its own to hand to the client.</p>
     *
     * <p><b>Why the group and not a derived form.</b> Transformation Rule 7 carries the group in the request
     * body of the matching write, because {@code WS-THIS-PROGCOMMAREA} at {@code :652} held it between the two
     * turns of the pseudo-conversation and a stateless server has nowhere to put it. The caller echoes this
     * value back unaltered, and returning the very type the write binds is what makes that possible without
     * the caller reconstructing anything. Reconstruction would not be safe: the comparison at
     * {@code :4109-4193} reads the date of birth from the live record at offsets {@code 1}, {@code 6} and
     * {@code 9} and from the snapshot at offsets {@code 1}, {@code 5} and {@code 7}, because the live value is
     * dash-separated and the snapshot value is not, so a group assembled from displayed text would differ on
     * every request.</p>
     *
     * <p><b>Side effects.</b> None. This is a read.</p>
     *
     * @param accountFilter the account identifier as typed into screen field {@code ACCTSIDI}; relayed
     *                      verbatim, so {@code null}, empty, all blanks and {@code *} all mean "not
     *                      supplied" and the source's own edits decide
     * @return the as-displayed group, never {@code null}
     * @throws ValidationException      when the account filter is blank or is non-numeric, short or
     *                                  all-zeroes, exactly as {@link #fetchForUpdate(String)} reports it, and
     *                                  when the read reached no populated group to return
     * @throws RecordNotFoundException  when any link of the three-dataset chain has no matching record
     * @throws FileAccessException      for a physical or logical input-output failure
     */
    @Transactional(readOnly = true)
    public AccountUpdateRequest.OldDetails fetchSnapshotForUpdate(final String accountFilter) {
        final AccountUpdateResult fetched = fetchForUpdate(accountFilter);
        final AccountUpdateRequest screen = fetched.screen();
        final AccountUpdateRequest.OldDetails snapshot =
                screen == null ? null : screen.getOldDetails();
        if (snapshot == null) {
            // The fetch reached neither an exception nor a populated snapshot, which is the state
            // INITIALIZE ACUP-OLD-DETAILS leaves at :981-983 when nothing was stored back. There is nothing
            // to return and no write could be confirmed against it, so this is reported rather than answered
            // with an empty group that would fail every later comparison for an unexplained reason.
            throw ValidationException.missingField(OLD_DETAILS_FIELD, OLD_DETAILS_REQUIRED_MESSAGE);
        }
        return snapshot;
    }


    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 0000-MAIN.}, logical line {@code :859}. The
     * control-flow spine: registers the abend handler, initialises the work areas, reconstructs the caller
     * context, remaps the attention identifier, validates it and dispatches.
     * <p>{@code :862-864} issues {@code EXEC CICS HANDLE ABEND LABEL(ABEND-ROUTINE)}. That is a catch-all
     * for abends, so a catch-all is the faithful translation, and it is the <em>only</em> route to
     * {@link #abendRoutine(UpdateContext, Throwable)} - the label is never {@code PERFORM}ed. Conditions
     * the program handles itself through {@code RESP} never reach a CICS abend handler, so the typed
     * {@code CardDemoException} family is rethrown untouched ahead of the catch-all; everything else is
     * funnelled to the handler with its cause preserved. No exception is swallowed and no {@code catch}
     * block is empty.</p>
     * <p>{@code :866-868} {@code INITIALIZE CC-WORK-AREA WS-MISC-STORAGE WS-COMMAREA}: all three are
     * method-local here, carried on the per-invocation {@link UpdateContext} whose field initialisers
     * reproduce the {@code INITIALIZE} semantics. Nothing is a bean field, so nothing survives between
     * requests and the singleton stays thread-safe.</p>
     * <p>{@code :880-893} either initialises the COMMAREA - additionally setting
     * {@code CDEMO-PGM-ENTER} at {@code :885} and {@code ACUP-DETAILS-NOT-FETCHED} at {@code :886} - or
     * slices {@code DFHCOMMAREA} into its two halves. The slicing has no stateless counterpart:
     * {@code CDEMO-USER-ID PIC X(08)} becomes the token subject claim and
     * {@code CDEMO-USER-TYPE PIC X(01)} - with {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} and
     * {@code 88 CDEMO-USRTYP-USER VALUE 'U'} in {@code app/cpy/COCOM01Y.cpy:26-28} - becomes the role
     * claim, both owned by the security layer, while the navigation and re-entry fields have no
     * counterpart at all.</p>
     * <p>{@code :905-916} is <strong>four conditions wide</strong>, two wider than the sibling
     * {@code AccountViewService}: Enter, PF03, PF05 when the marker is
     * {@code ACUP-CHANGES-OK-NOT-CONFIRMED}, and PF12 when the marker is anything other than
     * {@code ACUP-DETAILS-NOT-FETCHED}. Every other identifier is <strong>silently coerced to
     * Enter</strong> at {@code :914-916}; no validation error is raised, and that silence is the
     * behaviour.</p>
     * <p>{@code :921-1004} is the dispatch, and it contains <strong>two fall-through {@code WHEN}
     * pairs</strong>. {@code :964-965} and {@code :966-967} share the body at {@code :968-973}, and
     * {@code :979} and {@code :980} share the body at {@code :981-989}. Each pair becomes a single
     * {@code if} with a disjunctive condition rather than duplicated logic, which is what "fall-through"
     * means in COBOL: one body, two entry conditions.</p>
     * @param context the per-invocation state carrier; must not be {@code null}
     * @return the projected outcome; never {@code null}
     * @throws CardDemoException        rethrown untouched when the situation is one the program models
     *                                  through {@code RESP} rather than through an abend
     * @throws FatalProcessingException when any other runtime failure reaches the registered handler
     */
    private AccountUpdateResult mainLine0000(final UpdateContext context) {
        try {
            // :872 MOVE LIT-THISTRANID TO WS-TRANID
            context.transactionId = TRANSACTION_ID;
            // :876 SET WS-RETURN-MSG-OFF TO TRUE
            context.returnMessage = RETURN_MESSAGE_OFF;
            // :880-893 first entry, or arrival from the menu without a re-entered context
            if (context.entryMode == EntryMode.FIRST_ENTRY
                    || (MENU_PROGRAM.equals(context.fromProgram)
                        && context.entryMode != EntryMode.REENTER)) {
                // :883-884 INITIALIZE CARDDEMO-COMMAREA WS-THIS-PROGCOMMAREA
                context.fromTransactionId = null;
                context.fromProgram = null;
                // :885 SET CDEMO-PGM-ENTER TO TRUE
                context.entryMode = EntryMode.ENTER;
                // :886 SET ACUP-DETAILS-NOT-FETCHED TO TRUE
                context.changeAction = ChangeAction.DETAILS_NOT_FETCHED;
            } else if (context.authenticOldDetails != null) {
                // :887-893 ELSE - the two COMMAREA halves are sliced back out of DFHCOMMAREA:
                //   MOVE DFHCOMMAREA(1:LENGTH OF CARDDEMO-COMMAREA) TO CARDDEMO-COMMAREA
                //   MOVE DFHCOMMAREA(LENGTH OF CARDDEMO-COMMAREA + 1 : LENGTH OF
                //                    WS-THIS-PROGCOMMAREA) TO WS-THIS-PROGCOMMAREA
                // Statelessly there is no DFHCOMMAREA to slice, so the carrier of the first half is the
                // ACUP-OLD-DETAILS group the caller echoes back. These are exactly the six COMMAREA
                // members 9500-STORE-FETCHED-DATA populates at :3805-3810; without this restore
                // 9600-WRITE-PROCESSING would read CDEMO-CUST-ID as LOW-VALUES at :3919 and every write
                // would fail the customer lock guard. XREF-CARD-NUM (:3811) has no ACUP-OLD-DETAILS
                // member, so commAreaCardNumber legitimately stays unset on this path.
                final AccountUpdateRequest.OldDetails carried = context.authenticOldDetails;
                context.commAreaAccountId = carried.getAccountId();
                context.commAreaCustomerId = carried.getCustomerId();
                context.commAreaAccountStatus = carried.getActiveStatus();
                context.commAreaCustomerFirstName = carried.getFirstName();
                context.commAreaCustomerMiddleName = carried.getMiddleName();
                context.commAreaCustomerLastName = carried.getLastName();
                // :890-892 the second half - WS-THIS-PROGCOMMAREA, which holds ACUP-OLD-DETAILS (:669)
                restoreSnapshotFromRequest(context);
            }
            // :898-899 PERFORM YYYY-STORE-PFKEY THRU YYYY-STORE-PFKEY-EXIT
            storePfKey(context);
            storePfKeyExit();
            // :905 SET PFK-INVALID TO TRUE
            context.pfKeyValid = false;
            // :906-912 the four-condition valid-AID set
            if (context.aidKey == AidKey.ENTER
                    || context.aidKey == AidKey.PFK03
                    || (context.aidKey == AidKey.PFK05
                        && context.changeAction == ChangeAction.CHANGES_OK_NOT_CONFIRMED)
                    || (context.aidKey == AidKey.PFK12
                        && context.changeAction != ChangeAction.DETAILS_NOT_FETCHED)) {
                // :911 SET PFK-VALID TO TRUE
                context.pfKeyValid = true;
            }
            // :914-916 IF PFK-INVALID / SET CCARD-AID-ENTER TO TRUE - silent coercion, no error
            if (!context.pfKeyValid) {
                context.aidKey = AidKey.ENTER;
            }
            // :921-1004 EVALUATE TRUE
            if (context.aidKey == AidKey.PFK03) {
                // :927-959 exit to the caller, or to the main menu when no caller is known
                return exitToCallerAtLine927(context);
            }
            if ((context.changeAction == ChangeAction.DETAILS_NOT_FETCHED
                        && context.entryMode == EntryMode.ENTER)
                    || (MENU_PROGRAM.equals(context.fromProgram)
                        && context.entryMode != EntryMode.REENTER)) {
                // :964-967 the first fall-through pair; one shared body at :968-973
                context.screen = new ScreenBuffer();
                sendMap3000(context);
                sendMap3000Exit();
                // :971-972
                context.entryMode = EntryMode.REENTER;
                context.changeAction = ChangeAction.DETAILS_NOT_FETCHED;
                // :973 GO TO COMMON-RETURN
                return commonReturn(context);
            }
            if (context.changeAction == ChangeAction.CHANGES_OKAYED_AND_DONE
                    || context.changeAction.isChangesFailed()) {
                // :979-980 the second fall-through pair; one shared body at :981-989
                // :981-983 INITIALIZE WS-THIS-PROGCOMMAREA WS-MISC-STORAGE CDEMO-ACCT-ID
                context.screen = new ScreenBuffer();
                context.resetMiscellaneousStorage();
                context.commAreaAccountId = null;
                // :984 SET CDEMO-PGM-ENTER TO TRUE
                context.entryMode = EntryMode.ENTER;
                sendMap3000(context);
                sendMap3000Exit();
                // :987-988
                context.entryMode = EntryMode.REENTER;
                context.changeAction = ChangeAction.DETAILS_NOT_FETCHED;
                // :989 GO TO COMMON-RETURN
                return commonReturn(context);
            }
            // :996-1003 WHEN OTHER - the order of these three performs is load-bearing
            processInputs1000(context);
            processInputs1000Exit();
            decideAction2000(context);
            decideAction2000Exit();
            sendMap3000(context);
            sendMap3000Exit();
            // :1003 GO TO COMMON-RETURN
            return commonReturn(context);
        } catch (final CardDemoException typed) {
            // RESP-handled conditions bypass EXEC CICS HANDLE ABEND; rethrown with cause intact.
            throw typed;
        } catch (final RuntimeException unexpected) {
            throw abendRoutine(context, unexpected);
        }
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, the {@code WHEN CCARD-AID-PFK03} arm of {@code 0000-MAIN}, logical lines
     * {@code :927-959}. Resolves the transfer target, stamps this program as the caller, and hands control away.
     * <p>{@code :930-935} and {@code :937-942} apply {@code LOW-VALUES}-or-{@code SPACES} fallbacks: an unknown
     * caller transaction resolves to {@code CM00} and an unknown caller program to {@code COMEN01C}. Because a
     * stateless request carries no COMMAREA, both fallbacks always fire, which makes the transfer target constant in
     * practice - an observable consequence of statelessness rather than a change to the rule.</p> <p>{@code :947}
     * {@code SET CDEMO-USRTYP-USER TO TRUE} downgrades the user type to {@code 'U'} on the way out, unconditionally,
     * even for an administrator. It is reproduced because it is the behaviour; statelessly the role travels in the
     * token and the security layer, not here, decides authority, so the value is reported as navigation metadata
     * only.</p> <p><strong>{@code :952-954} is {@code EXEC CICS SYNCPOINT} - a COMMIT, not a rollback.</strong> It is
     * a second and entirely distinct syncpoint site from the {@code EXEC CICS SYNCPOINT ROLLBACK} at
     * {@code :4099-4101}, and the two must never be conflated. On this path no unit of work is pending: no write has
     * been issued anywhere in the dispatch arm, so committing nothing is a no-op, and the declarative transaction of
     * the entry point commits on normal return regardless. The substitution is therefore a documented no-op and is
     * owed an entry in the {@code DECISION_LOG.md}.</p>
     * @param context the per-invocation state carrier
     * @return a {@link ResponseKind#TRANSFER} outcome carrying the navigation metadata; never {@code null}
     */
    private AccountUpdateResult exitToCallerAtLine927(final UpdateContext context) {
        // :928 SET CCARD-AID-PFK03 TO TRUE - already true; retained for control-flow parity
        context.aidKey = AidKey.PFK03;
        // :930-935 IF CDEMO-FROM-TRANID EQUAL LOW-VALUES OR SPACES
        final String toTransactionId = isBlankOrLowValues(context.fromTransactionId)
                ? MENU_TRANSACTION_ID
                : context.fromTransactionId;
        // :937-942 IF CDEMO-FROM-PROGRAM EQUAL LOW-VALUES OR SPACES
        final String toProgram = isBlankOrLowValues(context.fromProgram)
                ? MENU_PROGRAM
                : context.fromProgram;
        // :944-945 stamp this program as the caller of the target
        context.fromTransactionId = TRANSACTION_ID;
        context.fromProgram = PROGRAM_NAME;
        // :947 SET CDEMO-USRTYP-USER TO TRUE - the unconditional downgrade
        context.userType = COMMAREA_USER_TYPE_USER;
        // :948 SET CDEMO-PGM-ENTER TO TRUE
        context.entryMode = EntryMode.ENTER;
        // :949-950 record the map and mapset this program leaves behind
        context.lastMapset = THIS_MAPSET;
        context.lastMap = THIS_MAP;
        // :952-954 EXEC CICS SYNCPOINT - a commit of an empty unit of work; documented no-op
        // :956-958 EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)
        // XCTL never returns, so COMMON-RETURN is not reached on this path.
        context.returnMessage = EXIT_MESSAGE;
        return new AccountUpdateResult(ResponseKind.TRANSFER,
                context.changeAction,
                null,
                new Navigation(toTransactionId,
                        toProgram,
                        context.fromTransactionId,
                        context.fromProgram,
                        context.lastMapset,
                        context.lastMap),
                List.of(),
                context.informationMessage,
                moveAlphanumeric(context.returnMessage, RETURN_MESSAGE_LENGTH));
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code COMMON-RETURN.}, logical line {@code :1007}. The
     * single exit point every non-transfer arm of the dispatch reaches through {@code GO TO}.
     * <p>{@code :1008} {@code MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG}: both are {@code PIC X(75)}, so the
     * projection is width-preserving. {@code :1010-1013} reassembles the two COMMAREA halves and
     * {@code :1015-1019} issues {@code EXEC CICS RETURN TRANSID(LIT-THISTRANID) COMMAREA(WS-COMMAREA)
     * LENGTH(LENGTH OF WS-COMMAREA)}.</p>
     * <p>The {@code RETURN TRANSID ... COMMAREA} construct is what makes the legacy transaction
     * pseudo-conversational, and it has <strong>no</strong> stateless counterpart: no server-side session
     * state is retained. What the COMMAREA carried is redistributed - identity into the token claims,
     * business keys into the response body, and the {@code ACUP} marker into
     * {@link AccountUpdateResult#changeAction()} for the client to echo on the next turn.</p>
     * @param context the per-invocation state carrier
     * @return a {@link ResponseKind#MAP} outcome carrying the projected screen; never {@code null}
     */
    private AccountUpdateResult commonReturn(final UpdateContext context) {
        // :1008 MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG
        final String errorMessage = moveAlphanumeric(context.returnMessage, RETURN_MESSAGE_LENGTH);
        // :1010-1013 reassemble the COMMAREA halves - no stateless counterpart
        // :1015-1019 EXEC CICS RETURN TRANSID(CAUP) COMMAREA(...) - no session state is retained
        mainExit0000();
        return new AccountUpdateResult(ResponseKind.MAP,
                context.changeAction,
                context.screen.project(context, errorMessage),
                new Navigation(context.transactionId,
                        PROGRAM_NAME,
                        context.fromTransactionId,
                        context.fromProgram,
                        context.lastMapset,
                        context.lastMap),
                List.copyOf(context.fieldAttributes),
                context.informationMessage,
                errorMessage);
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 0000-MAIN-EXIT.}, logical lines {@code :1021-1023}.
     * A bare {@code EXIT} statement.
     * <p>Retained rather than removed because the paragraph is a real Area-A label and the
     * traceability matrix asserts paragraph-level correspondence. Unlike the sibling
     * {@code AccountViewService}, which carries two textually identical exit labels and therefore two
     * methods, this program declares the label exactly once. The method body is intentionally empty; it is
     * a tracked, cited no-op rather than untracked dead code, which is how Rule 1 Clause B is satisfied
     * without abandoning parity.</p>
     */
    private void mainExit0000() {
        // EXIT - intentional no-op preserved for control-flow parity with :1021-1023.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1000-PROCESS-INPUTS.}, logical lines
     * {@code :1025-1034}. Receives the map, edits every field, then stamps the next-screen metadata.
     * <p>The order is load-bearing: {@code 1100-RECEIVE-MAP} at {@code :1026-1027} normalises the raw
     * screen fields and only then does {@code 1200-EDIT-MAP-INPUTS} at {@code :1028-1029} validate them.
     * {@code :1030} re-projects {@code WS-RETURN-MSG} into {@code CCARD-ERROR-MSG} - the same move
     * {@code COMMON-RETURN} makes at {@code :1008}, performed a second time here because the edits may
     * have set a message since the dispatch began.</p>
     * <p>{@code :1031-1033} move this program's own name, mapset and map into the next-screen fields, so
     * the conversation stays on {@code CAUP} unless the exit arm fires.</p>
     * @param context the per-invocation state carrier
     */
    private void processInputs1000(final UpdateContext context) {
        // :1026-1027 PERFORM 1100-RECEIVE-MAP THRU 1100-RECEIVE-MAP-EXIT
        receiveMap1100(context);
        receiveMap1100Exit();
        // :1028-1029 PERFORM 1200-EDIT-MAP-INPUTS THRU 1200-EDIT-MAP-INPUTS-EXIT
        editMapInputs1200(context);
        editMapInputs1200Exit();
        // :1030 MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG
        context.errorMessage = moveAlphanumeric(context.returnMessage, RETURN_MESSAGE_LENGTH);
        // :1031-1033 MOVE LIT-THISPGM / LIT-THISMAPSET / LIT-THISMAP to the next-screen fields
        context.nextProgram = PROGRAM_NAME;
        context.nextMapset = THIS_MAPSET;
        context.nextMap = THIS_MAP;
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1000-PROCESS-INPUTS-EXIT.}, logical lines
     * {@code :1036-1038}. A bare {@code EXIT}; a tracked, cited no-op retained for paragraph-level
     * correspondence.
     */
    private void processInputs1000Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :1036-1038.
    }

    /**
     * {@code app/cpy/CSSTRPFY.cpy}, paragraph {@code YYYY-STORE-PFKEY.}, copybook lines {@code :17-79},
     * expanded into this program by the Area-A construct {@code COPY 'CSSTRPFY'} at
     * {@code app/cbl/COACTUPC.cbl:4199}.
     * <p>A twenty-eight-arm {@code EVALUATE TRUE} on {@code EIBAID} that populates
     * {@code CCARD-AID PIC X(5)} of {@code app/cpy/CVCRD01Y.cpy:3-19}. Twenty-eight arms produce only
     * fifteen distinct states, because <strong>{@code DFHPF13} through {@code DFHPF24} fold back onto
     * {@code PFK01} through {@code PFK12}</strong> in the copybook itself - {@code :54-55} sets
     * {@code PFK01} from {@code DFHPF13}, and so on through {@code :76-77}. The fold is reproduced here
     * rather than in a lookup table so the correspondence stays inspectable arm by arm.</p>
     * <p>The {@code EVALUATE} has <strong>no {@code WHEN OTHER}</strong>. An unrecognised identifier
     * therefore leaves {@code CCARD-AID} at the value {@code INITIALIZE} left, which satisfies none of the
     * fifteen condition names; {@code :905-916} then reports {@code PFK-INVALID} and coerces to Enter. A
     * {@code null} identifier takes the same route. Matching is case-insensitive under
     * {@code Locale.ROOT}, and both the {@code DFH}-prefixed symbol and the bare form are accepted so a
     * REST caller need not know the CICS spelling.</p>
     * @param context the per-invocation state carrier; its {@code aidKey} field is the target
     */
    private void storePfKey(final UpdateContext context) {
        final String aid = context.attentionIdentifier == null
                ? ""
                : context.attentionIdentifier.trim().toUpperCase(Locale.ROOT);
        final String key = aid.startsWith(AID_SYMBOL_PREFIX)
                ? aid.substring(AID_SYMBOL_PREFIX.length())
                : aid;
        // :21-77 EVALUATE TRUE - twenty-eight arms, fifteen distinct outcomes
        switch (key) {
            case "ENTER" -> context.aidKey = AidKey.ENTER;                       // :22-23  DFHENTER
            case "CLEAR" -> context.aidKey = AidKey.CLEAR;                       // :24-25  DFHCLEAR
            case "PA1" -> context.aidKey = AidKey.PA1;                           // :26-27  DFHPA1
            case "PA2" -> context.aidKey = AidKey.PA2;                           // :28-29  DFHPA2
            case "PF1", "PFK01", "PF13" -> context.aidKey = AidKey.PFK01;        // :30-31, :54-55
            case "PF2", "PFK02", "PF14" -> context.aidKey = AidKey.PFK02;        // :32-33, :56-57
            case "PF3", "PFK03", "PF15" -> context.aidKey = AidKey.PFK03;        // :34-35, :58-59
            case "PF4", "PFK04", "PF16" -> context.aidKey = AidKey.PFK04;        // :36-37, :60-61
            case "PF5", "PFK05", "PF17" -> context.aidKey = AidKey.PFK05;        // :38-39, :62-63
            case "PF6", "PFK06", "PF18" -> context.aidKey = AidKey.PFK06;        // :40-41, :64-65
            case "PF7", "PFK07", "PF19" -> context.aidKey = AidKey.PFK07;        // :42-43, :66-67
            case "PF8", "PFK08", "PF20" -> context.aidKey = AidKey.PFK08;        // :44-45, :68-69
            case "PF9", "PFK09", "PF21" -> context.aidKey = AidKey.PFK09;        // :46-47, :70-71
            case "PF10", "PFK10", "PF22" -> context.aidKey = AidKey.PFK10;       // :48-49, :72-73
            case "PF11", "PFK11", "PF23" -> context.aidKey = AidKey.PFK11;       // :50-51, :74-75
            case "PF12", "PFK12", "PF24" -> context.aidKey = AidKey.PFK12;       // :52-53, :76-77
            default -> context.aidKey = null;   // no WHEN OTHER: CCARD-AID keeps its INITIALIZEd value
        }
    }

    /**
     * {@code app/cpy/CSSTRPFY.cpy}, paragraph {@code YYYY-STORE-PFKEY-EXIT.}, copybook lines
     * {@code :80-82}. A bare {@code EXIT}; a tracked, cited no-op retained for paragraph-level
     * correspondence, and the eighty-eighth paragraph of the published count.
     */
    private void storePfKeyExit() {
        // EXIT - intentional no-op preserved for control-flow parity with app/cpy/CSSTRPFY.cpy:80-82.
    }


    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1100-RECEIVE-MAP.}, logical lines
     * {@code :1039-1424}. Receives the symbolic map and normalises all fifty-four input fields into the
     * {@code ACUP-NEW-DETAILS} group declared at {@code :757}.
     * <p>{@code :1040-1045} is {@code EXEC CICS RECEIVE MAP(LIT-THISMAP) MAPSET(LIT-THISMAPSET)
     * INTO(CACTUPAI)}, which statelessly is the deserialised request body and needs no code.
     * {@code :1047} {@code INITIALIZE ACUP-NEW-DETAILS} then clears the group, which is why every value
     * below is re-derived from the screen fields rather than taken from
     * {@code AccountUpdateRequest.getNewDetails()}: the echoed group from the previous turn is
     * deliberately <strong>not</strong> trusted, exactly as the source does not trust it.</p>
     * <p>The normalisation rule is uniform across every field and is applied fifty-four times in the
     * source: {@code IF field = '*' OR field = SPACES / MOVE LOW-VALUES / ELSE MOVE field}. A single
     * asterisk is the map's "field cleared" convention and, like all-blanks, means not supplied; a run of
     * asterisks such as {@code ***} does <em>not</em> and is carried through as data. That single rule is
     * factored into {@link #receiveField(String)} rather than written out fifty-four times, which is what
     * Rule 1 Clause C asks for when it says to avoid duplication - the control flow is unchanged.</p>
     * <p>{@code :1060-1062} short-circuits: {@code IF ACUP-DETAILS-NOT-FETCHED GO TO
     * 1100-RECEIVE-MAP-EXIT}. On the first turn only the account filter is received and every other field
     * is left at {@code LOW-VALUES}, because there is nothing on the screen yet to receive.</p>
     * <p>The five monetary fields at {@code :1073-1140} carry an extra step the other forty-nine do not:
     * {@code IF FUNCTION TEST-NUMVAL-C(x) = 0 / COMPUTE n = FUNCTION NUMVAL-C(x) / ELSE CONTINUE}. The
     * currency-aware intrinsic tolerates a currency symbol and thousands separators, and the
     * {@code ELSE CONTINUE} means an unparsable value leaves the numeric field at whatever
     * {@code INITIALIZE} left - zero - while the alphanumeric image keeps the raw text for
     * {@code 1250-EDIT-SIGNED-9V2} to reject. Both halves are reproduced.</p>
     * <p>{@code :1329-1334} is worth noting because the mapping is not one-to-one with the field name:
     * screen field {@code ACSCITYI} feeds {@code ACUP-NEW-CUST-ADDR-LINE-3}. The city occupies the third
     * address line in the record layout, and {@code 1200-EDIT-MAP-INPUTS} edits it under the label
     * {@code City} at {@code :1615-1621}.</p>
     * @param context the per-invocation state carrier; its {@code new*} fields are the targets
     */
    private void receiveMap1100(final UpdateContext context) {
        final AccountUpdateRequest map = context.request;
        // :1051-1058 the account filter feeds both CC-ACCT-ID and ACUP-NEW-ACCT-ID-X
        context.accountFilter = receiveField(map.getAccountId());
        context.newAccountId = context.accountFilter;
        // :1060-1062 IF ACUP-DETAILS-NOT-FETCHED GO TO 1100-RECEIVE-MAP-EXIT
        if (context.changeAction == ChangeAction.DETAILS_NOT_FETCHED) {
            return;
        }
        // :1065-1070 account status
        context.newActiveStatus = receiveField(map.getAccountStatus());
        // :1073-1084 credit limit: text image plus the currency-aware numeric
        context.newCreditLimitText = receiveField(map.getCreditLimit());
        context.newCreditLimit = numericImage(context.newCreditLimitText);
        // :1087-1098 cash credit limit
        context.newCashCreditLimitText = receiveField(map.getCashCreditLimit());
        context.newCashCreditLimit = numericImage(context.newCashCreditLimitText);
        // :1101-1112 current balance
        context.newCurrentBalanceText = receiveField(map.getCurrentBalance());
        context.newCurrentBalance = numericImage(context.newCurrentBalanceText);
        // :1115-1126 current cycle credit
        context.newCurrentCycleCreditText = receiveField(map.getCurrentCycleCredit());
        context.newCurrentCycleCredit = numericImage(context.newCurrentCycleCreditText);
        // :1129-1140 current cycle debit
        context.newCurrentCycleDebitText = receiveField(map.getCurrentCycleDebit());
        context.newCurrentCycleDebit = numericImage(context.newCurrentCycleDebitText);
        // :1144-1163 open date, three discrete components
        context.newOpenYear = receiveField(map.getOpenDateYear());
        context.newOpenMonth = receiveField(map.getOpenDateMonth());
        context.newOpenDay = receiveField(map.getOpenDateDay());
        // :1167-1186 expiry date; ACUP-NEW-EXP-* feeds ACUP-NEW-EXPIRAION-DATE, misspelling and all
        context.newExpiryYear = receiveField(map.getExpiryDateYear());
        context.newExpiryMonth = receiveField(map.getExpiryDateMonth());
        context.newExpiryDay = receiveField(map.getExpiryDateDay());
        // :1190-1209 reissue date
        context.newReissueYear = receiveField(map.getReissueDateYear());
        context.newReissueMonth = receiveField(map.getReissueDateMonth());
        context.newReissueDay = receiveField(map.getReissueDateDay());
        // :1213-1218 account group identifier
        context.newGroupId = receiveField(map.getAccountGroupId());
        // :1224-1229 customer identifier
        context.newCustomerId = receiveField(map.getCustomerId());
        // :1233-1252 social security number, three discrete parts
        context.newSsnPart1 = receiveField(map.getCustomerSsnPart1());
        context.newSsnPart2 = receiveField(map.getCustomerSsnPart2());
        context.newSsnPart3 = receiveField(map.getCustomerSsnPart3());
        // :1256-1275 date of birth, three discrete components of a PIC X(08) compact group
        context.newDateOfBirthYear = receiveField(map.getDateOfBirthYear());
        context.newDateOfBirthMonth = receiveField(map.getDateOfBirthMonth());
        context.newDateOfBirthDay = receiveField(map.getDateOfBirthDay());
        // :1279-1284 FICO score
        context.newFicoScore = receiveField(map.getCustomerFicoScore());
        // :1288-1311 the three name fields
        context.newFirstName = receiveField(map.getCustomerFirstName());
        context.newMiddleName = receiveField(map.getCustomerMiddleName());
        context.newLastName = receiveField(map.getCustomerLastName());
        // :1315-1327 address lines one and two
        context.newAddressLine1 = receiveField(map.getAddressLine1());
        context.newAddressLine2 = receiveField(map.getAddressLine2());
        // :1329-1334 ACSCITYI feeds ADDR-LINE-3: the city occupies the third address line
        context.newAddressLine3 = receiveField(map.getAddressCity());
        // :1336-1355 state, country and postal code
        context.newStateCode = receiveField(map.getAddressStateCode());
        context.newCountryCode = receiveField(map.getAddressCountryCode());
        context.newZip = receiveField(map.getAddressZip());
        // :1357-1376 first telephone number, three discrete components
        context.newPhone1AreaCode = receiveField(map.getPhone1AreaCode());
        context.newPhone1Prefix = receiveField(map.getPhone1Prefix());
        context.newPhone1LineNumber = receiveField(map.getPhone1LineNumber());
        // :1378-1397 second telephone number
        context.newPhone2AreaCode = receiveField(map.getPhone2AreaCode());
        context.newPhone2Prefix = receiveField(map.getPhone2Prefix());
        context.newPhone2LineNumber = receiveField(map.getPhone2LineNumber());
        // :1401-1406 government-issued identifier
        context.newGovernmentIssuedId = receiveField(map.getGovernmentIssuedId());
        // :1410-1415 electronic funds transfer account identifier
        context.newEftAccountId = receiveField(map.getEftAccountId());
        // :1419-1424 primary card holder indicator
        context.newPrimaryCardHolderIndicator = receiveField(map.getPrimaryCardHolderIndicator());
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1100-RECEIVE-MAP-EXIT.}, logical lines
     * {@code :1426-1428}. A bare {@code EXIT}; a tracked, cited no-op retained for paragraph-level
     * correspondence.
     */
    private void receiveMap1100Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :1426-1428.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1200-EDIT-MAP-INPUTS.}, logical lines
     * {@code :1429-1676}. The edit cascade: twenty-four labelled field edits performed in a fixed order,
     * each preceded by a {@code MOVE} of its label into the shared {@code WS-EDIT-VARIABLE-NAME} and
     * followed by a {@code MOVE} of the shared result flag into that field's own tri-state.
     * <p><strong>The order is load-bearing and no edit may be reordered or consolidated</strong>, because
     * every message-setting branch is latched behind {@code IF WS-RETURN-MSG-OFF}: the first field to fail
     * owns the message, and a different order would surface a different message for the same payload.</p>
     * <p>{@code :1431} {@code SET INPUT-OK TO TRUE}. The flag has three declared states -
     * {@code INPUT-OK VALUE '0'} at {@code :172}, {@code INPUT-ERROR VALUE '1'} at {@code :173} and
     * {@code INPUT-PENDING VALUE LOW-VALUES} at {@code :174} - but a census shows {@code INPUT-OK} is set
     * exactly once, here, and {@code INPUT-PENDING} is never tested anywhere. A single boolean therefore
     * carries every distinction the program actually draws.</p>
     * <p>{@code :1433-1449} short-circuits the first turn: only {@code 1210-EDIT-ACCOUNT} runs, the
     * snapshot account group is cleared at {@code :1438}, a blank filter raises
     * {@code NO-SEARCH-CRITERIA-RECEIVED} at {@code :1441-1443}, and control leaves at {@code :1446}.</p>
     * <p>{@code :1452-1457} then optimistically asserts success across five conditions before any
     * comparison has been made. {@code SET FOUND-ACCOUNT-DATA} at {@code :1452} is a message assignment,
     * not a flag, and it is unlatched, so it overwrites whatever message the previous turn left.</p>
     * <p>{@code :1463-1468} is the second short-circuit, and it is the one that makes the legacy
     * conversation two turns long: when nothing changed, or the payload is already validated but
     * unconfirmed, or the update has already been done, every per-field flag is cleared and the whole edit
     * cascade is skipped. On the confirming turn the edits do not re-run - they ran on the turn that
     * produced {@code ACUP-CHANGES-OK-NOT-CONFIRMED}.</p>
     * <p>Five edits delegate their date work to {@code DateValidationService}, which owns the fourteen
     * labels of {@code app/cpy/CSUTLDPY.cpy}: {@code :1480-1481}, {@code :1492-1493},
     * {@code :1505-1506} and {@code :1536-1537} perform {@code EDIT-DATE-CCYYMMDD THRU
     * EDIT-DATE-CCYYMMDD-EXIT}, and {@code :1540-1541} performs {@code EDIT-DATE-OF-BIRTH THRU
     * EDIT-DATE-OF-BIRTH-EXIT}. <strong>Those labels are deliberately not re-mapped here</strong>;
     * re-mapping them would duplicate work another bean owns and would double-count in the scope-coverage
     * gate.</p>
     * <p>Three edits are conditional on an earlier one having passed: the state lookup at
     * {@code :1599-1602}, the FICO range check at {@code :1553-1556}, the birth-date plausibility check at
     * {@code :1539-1543}, and the state-and-postal-code combination at {@code :1665-1669}. Each guard is
     * reproduced exactly; none is widened.</p>
     * <p><strong>Defect D10, severity LOW.</strong> Five received fields are never edited at all:
     * {@code ACUP-NEW-CUST-ADDR-LINE-2}, {@code ACUP-NEW-CUST-GOVT-ISSUED-ID},
     * {@code ACUP-NEW-GROUP-ID}, {@code ACUP-NEW-CUST-ID} and, on this path,
     * {@code ACUP-NEW-ACCT-ID-X}. They are nonetheless written to the record by
     * {@code 9600-WRITE-PROCESSING}, so an unvalidated value reaches storage. The omission is preserved,
     * because adding an edit the source does not perform would reject payloads the source accepts.</p>
     * <p>{@code :1671-1675} closes the paragraph: when no edit failed, the marker advances to
     * {@code ACUP-CHANGES-OK-NOT-CONFIRMED}, which is the state that makes PF05 a valid key at
     * {@code :908} and reaches the write at {@code :2602-2603}.</p>
     * @param context the per-invocation state carrier
     */
    private void editMapInputs1200(final UpdateContext context) {
        // :1431 SET INPUT-OK TO TRUE
        context.inputError = false;
        // :1433-1449 first turn: the account filter is the only field there is
        if (context.changeAction == ChangeAction.DETAILS_NOT_FETCHED) {
            editAccount1210(context);
            editAccount1210Exit();
            // :1438 MOVE LOW-VALUES TO ACUP-OLD-ACCT-DATA - the ACCOUNT HALF only, 10 ACUP-OLD-ACCT-DATA
            // at :670; the customer half at 10 ACUP-OLD-CUST-DATA :709 is deliberately left intact
            context.clearSnapshotAccountData();
            // :1441-1443 IF FLG-ACCTFILTER-BLANK / SET NO-SEARCH-CRITERIA-RECEIVED TO TRUE
            if (context.accountFilterState == FieldState.BLANK) {
                context.returnMessage = NO_SEARCH_CRITERIA_MESSAGE;
            }
            // :1446 GO TO 1200-EDIT-MAP-INPUTS-EXIT
            return;
        }
        // :1452-1457 optimistic assertions made before any comparison; :1452 is an unlatched message
        context.informationMessage = INFO_FOUND_ACCOUNT_DATA;
        context.foundAccountInMaster = true;
        context.accountFilterState = FieldState.VALID;
        context.foundCustomerInMaster = true;
        context.customerFilterState = FieldState.VALID;
        // :1460-1461 PERFORM 1205-COMPARE-OLD-NEW THRU 1205-COMPARE-OLD-NEW-EXIT
        compareOldNew1205(context);
        compareOldNew1205Exit();
        // :1463-1468 nothing changed, or already validated, or already written: skip every edit
        if (!context.changeHasOccurred
                || context.changeAction == ChangeAction.CHANGES_OK_NOT_CONFIRMED
                || context.changeAction == ChangeAction.CHANGES_OKAYED_AND_DONE) {
            // :1466 MOVE LOW-VALUES TO WS-NON-KEY-FLAGS
            context.clearNonKeyFlags();
            // :1467 GO TO 1200-EDIT-MAP-INPUTS-EXIT
            return;
        }
        // :1470 SET ACUP-CHANGES-NOT-OK TO TRUE
        context.changeAction = ChangeAction.CHANGES_NOT_OK;
        // :1472-1476 account status, a required Y-or-N field
        context.editVariableName = LABEL_ACCOUNT_STATUS;
        context.editYesNo = context.newActiveStatus;
        editYesNo1220(context);
        editYesNo1220Exit();
        context.accountStatusState = context.yesNoState;
        // :1478-1482 open date; the fourteen CSUTLDPY labels are owned by DateValidationService
        context.editVariableName = LABEL_OPEN_DATE;
        final DateValidationService.EditOutcome openDate = editDateCcyymmdd(context,
                context.newOpenYear, context.newOpenMonth, context.newOpenDay);
        // :1482 MOVE WS-EDIT-DATE-FLGS TO WS-EDIT-OPEN-DATE-FLGS - a three-byte group move
        context.openYearState = toFieldState(openDate.yearFlag());
        context.openMonthState = toFieldState(openDate.monthFlag());
        context.openDayState = toFieldState(openDate.dayFlag());
        // :1484-1488 credit limit
        context.editVariableName = LABEL_CREDIT_LIMIT;
        context.editSignedNumberText = context.newCreditLimitText;
        editSignedAmount1250(context);
        editSignedAmount1250Exit();
        context.creditLimitState = context.signedNumberState;
        // :1490-1494 expiry date; the record field name carries the copybook's own misspelling
        context.editVariableName = LABEL_EXPIRY_DATE;
        final DateValidationService.EditOutcome expiryDate = editDateCcyymmdd(context,
                context.newExpiryYear, context.newExpiryMonth, context.newExpiryDay);
        // :1494 MOVE WS-EDIT-DATE-FLGS TO WS-EXPIRY-DATE-FLGS
        context.expiryYearState = toFieldState(expiryDate.yearFlag());
        context.expiryMonthState = toFieldState(expiryDate.monthFlag());
        context.expiryDayState = toFieldState(expiryDate.dayFlag());
        // :1496-1501 cash credit limit
        context.editVariableName = LABEL_CASH_CREDIT_LIMIT;
        context.editSignedNumberText = context.newCashCreditLimitText;
        editSignedAmount1250(context);
        editSignedAmount1250Exit();
        context.cashCreditLimitState = context.signedNumberState;
        // :1503-1507 reissue date
        context.editVariableName = LABEL_REISSUE_DATE;
        final DateValidationService.EditOutcome reissueDate = editDateCcyymmdd(context,
                context.newReissueYear, context.newReissueMonth, context.newReissueDay);
        // :1507 MOVE WS-EDIT-DATE-FLGS TO WS-EDIT-REISSUE-DATE-FLGS
        context.reissueYearState = toFieldState(reissueDate.yearFlag());
        context.reissueMonthState = toFieldState(reissueDate.monthFlag());
        context.reissueDayState = toFieldState(reissueDate.dayFlag());
        // :1509-1513 current balance
        context.editVariableName = LABEL_CURRENT_BALANCE;
        context.editSignedNumberText = context.newCurrentBalanceText;
        editSignedAmount1250(context);
        editSignedAmount1250Exit();
        context.currentBalanceState = context.signedNumberState;
        // :1515-1520 current cycle credit
        context.editVariableName = LABEL_CURRENT_CYCLE_CREDIT;
        context.editSignedNumberText = context.newCurrentCycleCreditText;
        editSignedAmount1250(context);
        editSignedAmount1250Exit();
        context.currentCycleCreditState = context.signedNumberState;
        // :1522-1527 current cycle debit
        context.editVariableName = LABEL_CURRENT_CYCLE_DEBIT;
        context.editSignedNumberText = context.newCurrentCycleDebitText;
        editSignedAmount1250(context);
        editSignedAmount1250Exit();
        context.currentCycleDebitState = context.signedNumberState;
        // :1529-1531 social security number, all three parts in one edit
        context.editVariableName = LABEL_SSN;
        editUsSsn1265(context);
        editUsSsn1265Exit();
        // :1533-1543 date of birth, then the plausibility check only when the date itself is valid
        context.editVariableName = LABEL_DATE_OF_BIRTH;
        DateValidationService.EditOutcome birthDate = editDateCcyymmdd(context,
                context.newDateOfBirthYear, context.newDateOfBirthMonth,
                context.newDateOfBirthDay);
        // :1538 MOVE WS-EDIT-DATE-FLGS TO WS-EDIT-DT-OF-BIRTH-FLGS
        context.dateOfBirthYearState = toFieldState(birthDate.yearFlag());
        context.dateOfBirthMonthState = toFieldState(birthDate.monthFlag());
        context.dateOfBirthDayState = toFieldState(birthDate.dayFlag());
        // :1539 IF WS-EDIT-DT-OF-BIRTH-ISVALID - the whole three-byte group must read LOW-VALUES,
        // which is the group-level 88 at :218, so all three components must individually be valid.
        if (context.dateOfBirthYearState == FieldState.VALID
                && context.dateOfBirthMonthState == FieldState.VALID
                && context.dateOfBirthDayState == FieldState.VALID) {
            // :1540-1541 PERFORM EDIT-DATE-OF-BIRTH THRU EDIT-DATE-OF-BIRTH-EXIT
            birthDate = editDateOfBirth(context, context.newDateOfBirthYear,
                    context.newDateOfBirthMonth, context.newDateOfBirthDay);
            // :1542 MOVE WS-EDIT-DATE-FLGS TO WS-EDIT-DT-OF-BIRTH-FLGS
            context.dateOfBirthYearState = toFieldState(birthDate.yearFlag());
            context.dateOfBirthMonthState = toFieldState(birthDate.monthFlag());
            context.dateOfBirthDayState = toFieldState(birthDate.dayFlag());
        }
        // :1545-1556 FICO score: a required three-digit number, then the range check when it parses
        context.editVariableName = LABEL_FICO_SCORE;
        context.editAlphanumericText = context.newFicoScore;
        context.editAlphanumericLength = FICO_SCORE_LENGTH;
        editNumericRequired1245(context);
        editNumericRequired1245Exit();
        context.ficoScoreState = context.alphanumericState;
        if (context.ficoScoreState == FieldState.VALID) {
            editFicoScore1275(context);
            editFicoScore1275Exit();
        }
        // :1560-1566 first name, required alphabetic, twenty-five bytes
        context.editVariableName = LABEL_FIRST_NAME;
        context.editAlphanumericText = context.newFirstName;
        context.editAlphanumericLength = NAME_LENGTH;
        editAlphaRequired1225(context);
        editAlphaRequired1225Exit();
        context.firstNameState = context.alphaState;
        // :1568-1574 middle name, optional alphabetic
        context.editVariableName = LABEL_MIDDLE_NAME;
        context.editAlphanumericText = context.newMiddleName;
        context.editAlphanumericLength = NAME_LENGTH;
        editAlphaOptional1235(context);
        editAlphaOptional1235Exit();
        context.middleNameState = context.alphaState;
        // :1576-1582 last name, required alphabetic
        context.editVariableName = LABEL_LAST_NAME;
        context.editAlphanumericText = context.newLastName;
        context.editAlphanumericLength = NAME_LENGTH;
        editAlphaRequired1225(context);
        editAlphaRequired1225Exit();
        context.lastNameState = context.alphaState;
        // :1584-1590 first address line: presence only, no character-class test
        context.editVariableName = LABEL_ADDRESS_LINE_1;
        context.editAlphanumericText = context.newAddressLine1;
        context.editAlphanumericLength = ADDRESS_LINE_LENGTH;
        editMandatory1215(context);
        editMandatory1215Exit();
        context.addressLine1State = context.mandatoryState;
        // :1592-1602 state code, required alphabetic, then the lookup only when it is alphabetic
        context.editVariableName = LABEL_STATE;
        context.editAlphanumericText = context.newStateCode;
        context.editAlphanumericLength = STATE_CODE_LENGTH;
        editAlphaRequired1225(context);
        editAlphaRequired1225Exit();
        context.stateState = context.alphaState;
        if (context.alphaState == FieldState.VALID) {
            editUsStateCode1270(context);
            editUsStateCode1270Exit();
        }
        // :1605-1611 postal code: five required digits, the screen width rather than the record width
        context.editVariableName = LABEL_ZIP;
        context.editAlphanumericText = context.newZip;
        context.editAlphanumericLength = ZIP_SCREEN_LENGTH;
        editNumericRequired1245(context);
        editNumericRequired1245Exit();
        context.zipState = context.alphanumericState;
        // :1615-1621 city, held in the third address line, edited as required alphabetic
        context.editVariableName = LABEL_CITY;
        context.editAlphanumericText = context.newAddressLine3;
        context.editAlphanumericLength = ADDRESS_LINE_LENGTH;
        editAlphaRequired1225(context);
        editAlphaRequired1225Exit();
        context.cityState = context.alphaState;
        // :1623-1630 country code, required alphabetic, three bytes
        context.editVariableName = LABEL_COUNTRY;
        context.editAlphanumericText = context.newCountryCode;
        context.editAlphanumericLength = COUNTRY_CODE_LENGTH;
        editAlphaRequired1225(context);
        editAlphaRequired1225Exit();
        context.countryState = context.alphaState;
        // :1632-1638 first telephone number, assembled into the fifteen-byte edit image
        context.editVariableName = LABEL_PHONE_NUMBER_1;
        context.editPhoneAreaCode = context.newPhone1AreaCode;
        context.editPhonePrefix = context.newPhone1Prefix;
        context.editPhoneLineNumber = context.newPhone1LineNumber;
        editUsPhoneNumber1260(context);
        editUsPhoneNumber1260Exit();
        context.phone1AreaCodeState = context.phoneAreaCodeState;
        context.phone1PrefixState = context.phonePrefixState;
        context.phone1LineNumberState = context.phoneLineNumberState;
        // :1640-1646 second telephone number, through the same edit
        context.editVariableName = LABEL_PHONE_NUMBER_2;
        context.editPhoneAreaCode = context.newPhone2AreaCode;
        context.editPhonePrefix = context.newPhone2Prefix;
        context.editPhoneLineNumber = context.newPhone2LineNumber;
        editUsPhoneNumber1260(context);
        editUsPhoneNumber1260Exit();
        context.phone2AreaCodeState = context.phoneAreaCodeState;
        context.phone2PrefixState = context.phonePrefixState;
        context.phone2LineNumberState = context.phoneLineNumberState;
        // :1648-1655 electronic funds account identifier: ten required digits
        context.editVariableName = LABEL_EFT_ACCOUNT_ID;
        context.editAlphanumericText = context.newEftAccountId;
        context.editAlphanumericLength = EFT_ACCOUNT_ID_LENGTH;
        editNumericRequired1245(context);
        editNumericRequired1245Exit();
        context.eftAccountIdState = context.alphanumericState;
        // :1657-1662 primary card holder indicator, a required Y-or-N field
        context.editVariableName = LABEL_PRIMARY_CARD_HOLDER;
        context.editYesNo = context.newPrimaryCardHolderIndicator;
        editYesNo1220(context);
        editYesNo1220Exit();
        context.primaryCardHolderState = context.yesNoState;
        // :1665-1669 the combination check, only when both components are individually valid
        if (context.stateState == FieldState.VALID && context.zipState == FieldState.VALID) {
            editUsStateZipCode1280(context);
            editUsStateZipCode1280Exit();
        }
        // :1671-1675 IF INPUT-ERROR CONTINUE ELSE SET ACUP-CHANGES-OK-NOT-CONFIRMED TO TRUE
        if (!context.inputError) {
            context.changeAction = ChangeAction.CHANGES_OK_NOT_CONFIRMED;
        }
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1200-EDIT-MAP-INPUTS-EXIT.}, logical lines
     * {@code :1678-1680}. A bare {@code EXIT}; a tracked, cited no-op retained for paragraph-level
     * correspondence.
     */
    private void editMapInputs1200Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :1678-1680.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1205-COMPARE-OLD-NEW.}, logical lines
     * {@code :1681-1775}. Answers "did the <em>user</em> change anything on the screen?" by comparing the
     * received {@code ACUP-NEW-*} group against the {@code ACUP-OLD-*} snapshot.
     * <p><strong>This is a different question from the one {@code 9700-CHECK-CHANGE-IN-REC} asks</strong>,
     * and the two use different case rules. {@code 9700} compares the <em>live</em> record against the
     * snapshot - "did someone else change it while we were out" - and does so with
     * {@code FUNCTION LOWER-CASE} on the group identifier and no {@code FUNCTION TRIM} anywhere. This
     * paragraph compares the <em>submitted</em> values against the snapshot with
     * {@code FUNCTION UPPER-CASE(FUNCTION TRIM(...))} on both sides of most text fields. A census confirms
     * the split: every {@code FUNCTION TRIM} in the program sits at {@code :1698-1766} inside this
     * paragraph or at {@code :1833-1885} inside the edit routines, and none at all appears in
     * {@code 9700}. Neither asymmetry may be normalised in either direction.</p>
     * <p>Two {@code IF} statements, not one. {@code :1684-1705} covers the account, and on mismatch it
     * sets {@code CHANGE-HAS-OCCURRED} and leaves through {@code GO TO 1205-COMPARE-OLD-NEW-EXIT};
     * {@code :1708-1773} covers the customer and does the same. Only when both pass does {@code :1769} set
     * {@code NO-CHANGES-DETECTED} - which is a {@code WS-RETURN-MSG} literal, not a flag, and is set
     * <em>unlatched</em>, so it overwrites any message already present.</p>
     * <p>The account {@code IF} applies {@code FUNCTION UPPER-CASE} without {@code TRIM} to the active
     * status at {@code :1685-1688}, and {@code UPPER-CASE(TRIM(...))} to the group identifier at
     * {@code :1697-1700}. Its five monetary comparisons at {@code :1689-1696} are on the numeric
     * redefinitions, so they become {@code BigDecimal.compareTo} - never {@code equals}, which would make
     * {@code 100} and {@code 100.00} differ. Its three date comparisons are on the whole eight-byte
     * compact groups, unlike {@code 9700}, which compares date components one at a time.</p>
     * <p>The customer {@code IF} applies {@code UPPER-CASE(TRIM(...))} to the customer identifier, the
     * three names, the three address lines, the state, the country, the postal code, the
     * government-issued identifier and the primary-holder indicator, and applies no case function at all
     * to the six telephone components, the social security number, the date of birth, the electronic funds
     * account identifier and the FICO score.</p>
     * @param context the per-invocation state carrier; sets {@code changeHasOccurred} on any difference
     */
    private void compareOldNew1205(final UpdateContext context) {
        // :1682 SET NO-CHANGES-FOUND TO TRUE
        context.changeHasOccurred = false;
        final AccountUpdateRequest.OldDetails old = context.authenticOldDetails;
        if (old == null) {
            // A stateless request without the snapshot group cannot answer the question at all. The
            // source always has the group because it lives in WORKING-STORAGE; here its absence is
            // reported rather than silently treated as "nothing changed".
            context.changeHasOccurred = true;
            retainFailure(context, ValidationException.missingField(OLD_DETAILS_FIELD,
                    "ACUP-OLD-DETAILS of app/cbl/COACTUPC.cbl:669 is required for the comparison at"
                            + " :1684-1773."));
            context.inputError = true;
            return;
        }
        // :1684-1705 the account IF
        if (!equalText(context.newAccountId, old.getAccountId())
                || !equalUpperCase(context.newActiveStatus, old.getActiveStatus())
                || !equalAmount(context.newCurrentBalance, old.currentBalanceAmount())
                || !equalAmount(context.newCreditLimit, old.creditLimitAmount())
                || !equalAmount(context.newCashCreditLimit, old.cashCreditLimitAmount())
                || !equalText(compactDate(context.newOpenYear, context.newOpenMonth,
                        context.newOpenDay), old.getOpenDate())
                || !equalText(compactDate(context.newExpiryYear, context.newExpiryMonth,
                        context.newExpiryDay), old.getExpiraionDate())
                || !equalText(compactDate(context.newReissueYear, context.newReissueMonth,
                        context.newReissueDay), old.getReissueDate())
                || !equalAmount(context.newCurrentCycleCredit, old.currentCycleCreditAmount())
                || !equalAmount(context.newCurrentCycleDebit, old.currentCycleDebitAmount())
                || !equalUpperCaseTrimmed(context.newGroupId, old.getGroupId())) {
            // :1703-1704 SET CHANGE-HAS-OCCURRED TO TRUE / GO TO 1205-COMPARE-OLD-NEW-EXIT
            context.changeHasOccurred = true;
            return;
        }
        // :1708-1773 the customer IF
        if (!equalUpperCaseTrimmed(context.newCustomerId, old.getCustomerId())
                || !equalUpperCaseTrimmed(context.newFirstName, old.getFirstName())
                || !equalUpperCaseTrimmed(context.newMiddleName, old.getMiddleName())
                || !equalUpperCaseTrimmed(context.newLastName, old.getLastName())
                || !equalUpperCaseTrimmed(context.newAddressLine1, old.getAddressLine1())
                || !equalUpperCaseTrimmed(context.newAddressLine2, old.getAddressLine2())
                || !equalUpperCaseTrimmed(context.newAddressLine3, old.getAddressLine3())
                || !equalUpperCaseTrimmed(context.newStateCode, old.getAddressStateCode())
                || !equalUpperCaseTrimmed(context.newCountryCode, old.getAddressCountryCode())
                || !equalUpperCaseTrimmed(context.newZip, old.getAddressZip())
                || !equalText(context.newPhone1AreaCode, old.phoneNumber1AreaCode())
                || !equalText(context.newPhone1Prefix, old.phoneNumber1Prefix())
                || !equalText(context.newPhone1LineNumber, old.phoneNumber1LineNumber())
                || !equalText(context.newPhone2AreaCode, old.phoneNumber2AreaCode())
                || !equalText(context.newPhone2Prefix, old.phoneNumber2Prefix())
                || !equalText(context.newPhone2LineNumber, old.phoneNumber2LineNumber())
                || !equalText(assembledSsn(context), old.getSsn())
                || !equalUpperCaseTrimmed(context.newGovernmentIssuedId,
                        old.getGovernmentIssuedId())
                || !equalText(compactDate(context.newDateOfBirthYear, context.newDateOfBirthMonth,
                        context.newDateOfBirthDay), old.getDateOfBirth())
                || !equalText(context.newEftAccountId, old.getEftAccountId())
                || !equalUpperCaseTrimmed(context.newPrimaryCardHolderIndicator,
                        old.getPrimaryCardHolderIndicator())
                || !equalText(context.newFicoScore, old.getFicoScore())) {
            // :1771-1772 SET CHANGE-HAS-OCCURRED TO TRUE / GO TO 1205-COMPARE-OLD-NEW-EXIT
            context.changeHasOccurred = true;
            return;
        }
        // :1769 SET NO-CHANGES-DETECTED TO TRUE - a message literal, assigned without the latch
        context.returnMessage = NO_CHANGES_DETECTED_MESSAGE;
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1205-COMPARE-OLD-NEW-EXIT.}, logical lines
     * {@code :1777-1779}. A bare {@code EXIT}; a tracked, cited no-op retained for paragraph-level
     * correspondence.
     */
    private void compareOldNew1205Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :1777-1779.
    }


    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1210-EDIT-ACCOUNT.}, logical lines
     * {@code :1783-1818}. Edits the eleven-digit account filter, the only field received on the first
     * turn.
     * <p>{@code :1784} {@code SET FLG-ACCTFILTER-NOT-OK TO TRUE} - the pessimistic opening every edit
     * routine shares. {@code :1787-1797} rejects a blank or low-values filter with
     * {@code WS-PROMPT-FOR-ACCT} behind the first-error-wins latch, zeroes both the COMMAREA account
     * identifier and {@code ACUP-NEW-ACCT-ID}, and leaves.</p>
     * <p>{@code :1801} moves the filter into {@code ACUP-NEW-ACCT-ID} <em>before</em> validating it, so a
     * malformed filter still reaches the new-details group. {@code :1802-1813} then rejects a non-numeric
     * or zero filter; note the message is <strong>two source literals concatenated by
     * {@code STRING ... DELIMITED BY SIZE}</strong> at {@code :1807-1808}, which is why the Java constant
     * is a single joined string rather than two.</p>
     * <p>{@code :1815-1816} is the only success path: the filter is copied to the COMMAREA and the
     * tri-state advances to valid.</p>
     * @param context the per-invocation state carrier
     */
    private void editAccount1210(final UpdateContext context) {
        // :1784 SET FLG-ACCTFILTER-NOT-OK TO TRUE
        context.accountFilterState = FieldState.NOT_OK;
        // :1787-1797 blank or low-values
        if (isBlankOrLowValues(context.accountFilter)) {
            context.inputError = true;
            context.accountFilterState = FieldState.BLANK;
            if (isReturnMessageOff(context)) {
                context.returnMessage = PROMPT_FOR_ACCOUNT_MESSAGE;
            }
            // :1794-1795 MOVE ZEROES TO CDEMO-ACCT-ID, ACUP-NEW-ACCT-ID
            context.commAreaAccountId = zeroes(ACCOUNT_ID_LENGTH);
            context.newAccountId = zeroes(ACCOUNT_ID_LENGTH);
            return;
        }
        // :1801 the filter is copied into the new-details group before it has been validated
        context.newAccountId = context.accountFilter;
        // :1802-1813 not numeric, or numerically zero
        if (!isAllDigits(context.accountFilter, ACCOUNT_ID_LENGTH)
                || isNumericallyZero(context.accountFilter)) {
            context.inputError = true;
            if (isReturnMessageOff(context)) {
                context.returnMessage = ACCOUNT_FILTER_ELEVEN_DIGIT_MESSAGE;
            }
            context.commAreaAccountId = zeroes(ACCOUNT_ID_LENGTH);
            return;
        }
        // :1815-1816 the only success path
        context.commAreaAccountId = context.accountFilter;
        context.accountFilterState = FieldState.VALID;
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1210-EDIT-ACCOUNT-EXIT.}, logical lines
     * {@code :1820-1822}. A bare {@code EXIT}; a tracked, cited no-op.
     */
    private void editAccount1210Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :1820-1822.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1215-EDIT-MANDATORY.}, logical lines
     * {@code :1824-1850}. Presence-only edit: the field must be non-blank, and no character-class test is
     * applied. Performed once, for the first address line at {@code :1587}.
     * <p>The blank test is three-way at {@code :1826-1831}: {@code EQUAL LOW-VALUES},
     * {@code EQUAL SPACES}, or {@code FUNCTION LENGTH(FUNCTION TRIM(...)) = 0}. All three collapse to one
     * predicate in Java because a Java {@code String} has no distinct low-values representation; the
     * distinction is preserved semantically by treating {@code null} as low-values.</p>
     * @param context the per-invocation state carrier; reads {@code editAlphanumericText} and
     *     {@code editAlphanumericLength}, writes {@code mandatoryState}
     */
    private void editMandatory1215(final UpdateContext context) {
        // :1825 SET FLG-MANDATORY-NOT-OK TO TRUE
        context.mandatoryState = FieldState.NOT_OK;
        final String value = reference(context.editAlphanumericText, context.editAlphanumericLength);
        // :1826-1840 blank in any of its three representations
        if (isBlankOrLowValues(value)) {
            context.inputError = true;
            context.mandatoryState = FieldState.BLANK;
            if (isReturnMessageOff(context)) {
                context.returnMessage = labelledMessage(context, SUFFIX_MUST_BE_SUPPLIED);
            }
            return;
        }
        // :1844 SET FLG-MANDATORY-ISVALID TO TRUE
        context.mandatoryState = FieldState.VALID;
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1215-EDIT-MANDATORY-EXIT.}, logical lines
     * {@code :1852-1854}. A bare {@code EXIT}; a tracked, cited no-op.
     */
    private void editMandatory1215Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :1852-1854.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1220-EDIT-YESNO.}, logical lines
     * {@code :1856-1892}. Requires the field to be exactly {@code Y} or {@code N}.
     * <p>Two source details are worth recording. First, the opening
     * {@code SET FLG-YES-NO-NOT-OK TO TRUE} is <strong>commented out</strong>, unlike every other edit
     * routine, so on entry the flag still holds whatever the previous field left. That matters only
     * because {@code WS-EDIT-YES-NO} is <em>both</em> the data field and the flag field:
     * {@code 88 FLG-YES-NO-ISVALID VALUES 'Y', 'N'} at {@code :78} tests the received character itself,
     * while the failure paths overwrite it with {@code '0'} or {@code 'B'}. The Java tri-state reproduces
     * that by deriving the state from the value rather than storing a separate flag.</p>
     * <p>Second, the blank test at {@code :1859-1861} adds {@code OR ZEROS} to the usual low-values and
     * spaces pair, so a field of ASCII zeroes counts as not supplied.</p>
     * @param context the per-invocation state carrier; reads {@code editYesNo}, writes {@code yesNoState}
     */
    private void editYesNo1220(final UpdateContext context) {
        // :1857 the pessimistic SET FLG-YES-NO-NOT-OK is commented out in the source; not reinstated.
        final String value = context.editYesNo;
        // :1859-1870 low-values, spaces or zeroes
        if (isBlankOrLowValues(value) || isAllZeroCharacters(value)) {
            context.inputError = true;
            context.yesNoState = FieldState.BLANK;
            if (isReturnMessageOff(context)) {
                context.returnMessage = labelledMessage(context, SUFFIX_MUST_BE_SUPPLIED);
            }
            return;
        }
        // :1874-1888 IF FLG-YES-NO-ISVALID CONTINUE ELSE ... - the 88 tests the character itself
        final String upper = value.trim().toUpperCase(Locale.ROOT);
        if (YES_INDICATOR.equals(upper) || NO_INDICATOR.equals(upper)) {
            context.yesNoState = FieldState.VALID;
            return;
        }
        context.inputError = true;
        context.yesNoState = FieldState.NOT_OK;
        if (isReturnMessageOff(context)) {
            context.returnMessage = labelledMessage(context, SUFFIX_MUST_BE_Y_OR_N);
        }
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1220-EDIT-YESNO-EXIT.}, logical lines
     * {@code :1894-1896}. A bare {@code EXIT}; a tracked, cited no-op.
     */
    private void editYesNo1220Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :1894-1896.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1225-EDIT-ALPHA-REQD.}, logical lines
     * {@code :1898-1950}. A required field that may contain only letters.
     * <p>The character-class test at {@code :1925-1947} is an
     * {@code INSPECT ... CONVERTING LIT-ALL-ALPHA-FROM TO LIT-ALPHA-SPACES-TO} followed by
     * {@code IF FUNCTION LENGTH(FUNCTION TRIM(...)) = 0}. {@code LIT-ALL-ALPHA-FROM} is the
     * fifty-two-character upper-and-lower alphabet declared at {@code :588-590}, and the target is
     * fifty-two spaces, so every letter is blanked and the field passes only if what remains is entirely
     * blank. <strong>Embedded spaces therefore pass</strong> - {@code "VAN DER BERG"} is accepted - which
     * a naive "every character is a letter" test would reject. The Java predicate reproduces the
     * letters-and-spaces rule, not the letters-only one.</p>
     * @param context the per-invocation state carrier; reads {@code editAlphanumericText} and
     *     {@code editAlphanumericLength}, writes {@code alphaState}
     */
    private void editAlphaRequired1225(final UpdateContext context) {
        // :1900 SET FLG-ALPHA-NOT-OK TO TRUE
        context.alphaState = FieldState.NOT_OK;
        final String value = reference(context.editAlphanumericText, context.editAlphanumericLength);
        // :1903-1922 blank in any of its three representations
        if (isBlankOrLowValues(value)) {
            context.inputError = true;
            context.alphaState = FieldState.BLANK;
            if (isReturnMessageOff(context)) {
                context.returnMessage = labelledMessage(context, SUFFIX_MUST_BE_SUPPLIED);
            }
            return;
        }
        // :1925-1947 INSPECT CONVERTING the alphabet to spaces, then require an all-blank remainder
        if (!containsOnlyLettersAndSpaces(value)) {
            context.inputError = true;
            context.alphaState = FieldState.NOT_OK;
            if (isReturnMessageOff(context)) {
                context.returnMessage = labelledMessage(context, SUFFIX_ALPHABETS_ONLY);
            }
            return;
        }
        // :1949 SET FLG-ALPHA-ISVALID TO TRUE
        context.alphaState = FieldState.VALID;
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1225-EDIT-ALPHA-REQD-EXIT.}, logical lines
     * {@code :1951-1953}. A bare {@code EXIT}; a tracked, cited no-op.
     */
    private void editAlphaRequired1225Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :1951-1953.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1230-EDIT-ALPHANUM-REQD.}, logical lines {@code :1955-2008}. A
     * required field that may contain only letters, digits and spaces. <p><strong>Defect D12, severity LOW: this
     * paragraph is never performed.</strong> A repository-wide census of {@code PERFORM 1230} returns zero call
     * sites, so the routine is declared and complete but unreachable. It is nonetheless mapped, because deleting it
     * would break the paragraph correspondence that {@code TRACEABILITY_MATRIX.md} is proved
     * against, and Rule 1 Clause B forbids <em>untracked</em> dead code rather than tracked-and-cited dead code. The
     * tracking reference is this Javadoc plus the register entry in the class documentation.</p> <p>Its
     * character-class test at {@code :1982-2005} uses {@code LIT-ALL-ALPHANUM-FROM}, the sixty-two-character
     * alphabet-plus-digits set, so the accepted class is letters, digits and spaces.</p>
     * @param context the per-invocation state carrier; reads {@code editAlphanumericText} and
     *     {@code editAlphanumericLength}, writes {@code alphanumericState}
     */
    private void editAlphanumericRequired1230(final UpdateContext context) {
        // :1957 SET FLG-ALPHNANUM-NOT-OK TO TRUE
        context.alphanumericState = FieldState.NOT_OK;
        final String value = reference(context.editAlphanumericText, context.editAlphanumericLength);
        // :1960-1979 blank in any of its three representations
        if (isBlankOrLowValues(value)) {
            context.inputError = true;
            context.alphanumericState = FieldState.BLANK;
            if (isReturnMessageOff(context)) {
                context.returnMessage = labelledMessage(context, SUFFIX_MUST_BE_SUPPLIED);
            }
            return;
        }
        // :1982-2005 letters, digits and spaces only
        if (!containsOnlyLettersDigitsAndSpaces(value)) {
            context.inputError = true;
            context.alphanumericState = FieldState.NOT_OK;
            if (isReturnMessageOff(context)) {
                context.returnMessage = labelledMessage(context, SUFFIX_ALPHANUMERIC_ONLY);
            }
            return;
        }
        // :2007 SET FLG-ALPHNANUM-ISVALID TO TRUE
        context.alphanumericState = FieldState.VALID;
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1230-EDIT-ALPHANUM-REQD-EXIT.}, logical lines
     * {@code :2009-2011}. A bare {@code EXIT}; a tracked, cited no-op belonging to an unreachable
     * paragraph (defect D12).
     */
    private void editAlphanumericRequired1230Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :2009-2011.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1235-EDIT-ALPHA-OPT.}, logical lines
     * {@code :2012-2056}. An optional field that, when supplied, may contain only letters and spaces.
     * Performed once, for the middle name at {@code :1571}.
     * <p>The one structural difference from {@code 1225-EDIT-ALPHA-REQD} is at {@code :2024}: a blank
     * field sets {@code FLG-ALPHA-ISVALID}, not {@code FLG-ALPHA-BLANK}. The {@code BLANK} tri-state is
     * therefore <strong>unreachable</strong> through this routine, which in turn means the {@code '*'}
     * marker of {@code app/cpy/CSSETATY.cpy} is never emitted for the middle name. That asymmetry is
     * preserved rather than harmonised.</p>
     * @param context the per-invocation state carrier; reads {@code editAlphanumericText} and
     *     {@code editAlphanumericLength}, writes {@code alphaState}
     */
    private void editAlphaOptional1235(final UpdateContext context) {
        // :2014 SET FLG-ALPHA-NOT-OK TO TRUE
        context.alphaState = FieldState.NOT_OK;
        final String value = reference(context.editAlphanumericText, context.editAlphanumericLength);
        // :2017-2025 blank is ACCEPTED here, and sets ISVALID rather than BLANK
        if (isBlankOrLowValues(value)) {
            context.alphaState = FieldState.VALID;
            return;
        }
        // :2031-2053 letters and spaces only
        if (!containsOnlyLettersAndSpaces(value)) {
            context.inputError = true;
            context.alphaState = FieldState.NOT_OK;
            if (isReturnMessageOff(context)) {
                context.returnMessage = labelledMessage(context, SUFFIX_ALPHABETS_ONLY);
            }
            return;
        }
        // :2055 SET FLG-ALPHA-ISVALID TO TRUE
        context.alphaState = FieldState.VALID;
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1235-EDIT-ALPHA-OPT-EXIT.}, logical lines
     * {@code :2057-2059}. A bare {@code EXIT}; a tracked, cited no-op.
     */
    private void editAlphaOptional1235Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :2057-2059.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1240-EDIT-ALPHANUM-OPT.}, logical lines
     * {@code :2061-2104}. An optional field that, when supplied, may contain only letters, digits and
     * spaces.
     * <p><strong>Defect D13, severity LOW: this paragraph is never performed.</strong> A census of
     * {@code PERFORM 1240} returns zero call sites. It is mapped and tracked for the same reason as
     * {@code 1230-EDIT-ALPHANUM-REQD}; see defect D12.</p>
     * <p>Like {@code 1235-EDIT-ALPHA-OPT}, a blank field sets {@code ISVALID} at {@code :2072} rather than
     * {@code BLANK}.</p>
     * @param context the per-invocation state carrier; reads {@code editAlphanumericText} and
     *     {@code editAlphanumericLength}, writes {@code alphanumericState}
     */
    private void editAlphanumericOptional1240(final UpdateContext context) {
        // :2063 SET FLG-ALPHNANUM-NOT-OK TO TRUE
        context.alphanumericState = FieldState.NOT_OK;
        final String value = reference(context.editAlphanumericText, context.editAlphanumericLength);
        // :2066-2073 blank is accepted and sets ISVALID
        if (isBlankOrLowValues(value)) {
            context.alphanumericState = FieldState.VALID;
            return;
        }
        // :2079-2101 letters, digits and spaces only
        if (!containsOnlyLettersDigitsAndSpaces(value)) {
            context.inputError = true;
            context.alphanumericState = FieldState.NOT_OK;
            if (isReturnMessageOff(context)) {
                context.returnMessage = labelledMessage(context, SUFFIX_ALPHANUMERIC_ONLY);
            }
            return;
        }
        // :2103 SET FLG-ALPHNANUM-ISVALID TO TRUE
        context.alphanumericState = FieldState.VALID;
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1240-EDIT-ALPHANUM-OPT-EXIT.}, logical lines
     * {@code :2105-2107}. A bare {@code EXIT}; a tracked, cited no-op belonging to an unreachable
     * paragraph (defect D13).
     */
    private void editAlphanumericOptional1240Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :2105-2107.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1245-EDIT-NUM-REQD.}, logical lines
     * {@code :2109-2175}. A required, all-digit, non-zero field. Performed for the FICO score, the postal
     * code, the electronic funds account identifier and all three social security number parts.
     * <p>Three tests in sequence, each with its own message and its own early exit: blank at
     * {@code :2114-2133} yielding {@code BLANK} and {@code " must be supplied."}; not numeric at
     * {@code :2137-2152} yielding {@code NOT_OK} and {@code " must be all numeric."}; and
     * {@code FUNCTION NUMVAL(...) = 0} at {@code :2156-2171} yielding {@code NOT_OK} and
     * {@code " must not be zero."}.</p>
     * <p>The zero test uses the <em>plain</em> {@code FUNCTION NUMVAL}, not the currency-aware
     * {@code NUMVAL-C} that {@code 1250-EDIT-SIGNED-9V2} uses. The two intrinsics are deliberately
     * different: identifiers and codes are parsed strictly, monetary amounts tolerantly.</p>
     * @param context the per-invocation state carrier; reads {@code editAlphanumericText} and
     *     {@code editAlphanumericLength}, writes {@code alphanumericState}
     */
    private void editNumericRequired1245(final UpdateContext context) {
        // :2111 SET FLG-ALPHNANUM-NOT-OK TO TRUE
        context.alphanumericState = FieldState.NOT_OK;
        final String value = reference(context.editAlphanumericText, context.editAlphanumericLength);
        // :2114-2133 blank
        if (isBlankOrLowValues(value)) {
            context.inputError = true;
            context.alphanumericState = FieldState.BLANK;
            if (isReturnMessageOff(context)) {
                context.returnMessage = labelledMessage(context, SUFFIX_MUST_BE_SUPPLIED);
            }
            return;
        }
        // :2137-2152 IS NUMERIC on a PIC X reference-modified span: every character must be a digit
        if (!isAllDigits(value, context.editAlphanumericLength)) {
            context.inputError = true;
            context.alphanumericState = FieldState.NOT_OK;
            if (isReturnMessageOff(context)) {
                context.returnMessage = labelledMessage(context, SUFFIX_MUST_BE_ALL_NUMERIC);
            }
            return;
        }
        // :2156-2171 FUNCTION NUMVAL(...) = 0 - the plain intrinsic, not the currency-aware one
        if (isNumericallyZero(value)) {
            context.inputError = true;
            context.alphanumericState = FieldState.NOT_OK;
            if (isReturnMessageOff(context)) {
                context.returnMessage = labelledMessage(context, SUFFIX_MUST_NOT_BE_ZERO);
            }
            return;
        }
        // :2174 SET FLG-ALPHNANUM-ISVALID TO TRUE
        context.alphanumericState = FieldState.VALID;
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1245-EDIT-NUM-REQD-EXIT.}, logical lines
     * {@code :2176-2178}. A bare {@code EXIT}; a tracked, cited no-op.
     */
    private void editNumericRequired1245Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :2176-2178.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1250-EDIT-SIGNED-9V2.}, logical lines
     * {@code :2180-2219}. A required, currency-aware signed amount with two decimal places. Performed for
     * all five monetary fields.
     * <p>{@code :2184-2199} rejects blank with {@code " must be supplied."}. {@code :2201-2215} then
     * applies {@code FUNCTION TEST-NUMVAL-C}, which returns zero only when the whole fifteen-byte field
     * is a valid currency-formatted number; anything else yields {@code " is not valid"}. Note the message
     * has <strong>no trailing period</strong>, unlike every other suffix in the program - that is the
     * source literal at {@code :2209} and is reproduced byte-for-byte.</p>
     * <p>{@code :2206-2212} also carries a small source irregularity: the {@code IF WS-RETURN-MSG-OFF}
     * guard around the {@code STRING} is closed by {@code END-IF} but the {@code STRING} itself has no
     * {@code END-STRING}, unlike its siblings. That is a formatting difference with no behavioural
     * consequence and is noted only so a reader diffing the two does not look for a missing branch.</p>
     * @param context the per-invocation state carrier; reads {@code editSignedNumberText}, writes
     *     {@code signedNumberState}
     */
    private void editSignedAmount1250(final UpdateContext context) {
        // :2181 SET FLG-SIGNED-NUMBER-NOT-OK TO TRUE
        context.signedNumberState = FieldState.NOT_OK;
        final String value = context.editSignedNumberText;
        // :2184-2199 low-values or spaces
        if (isBlankOrLowValues(value)) {
            context.inputError = true;
            context.signedNumberState = FieldState.BLANK;
            if (isReturnMessageOff(context)) {
                context.returnMessage = labelledMessage(context, SUFFIX_MUST_BE_SUPPLIED);
            }
            return;
        }
        // :2201-2215 FUNCTION TEST-NUMVAL-C, the currency-aware validity test
        if (numvalC(value) == null) {
            context.inputError = true;
            context.signedNumberState = FieldState.NOT_OK;
            if (isReturnMessageOff(context)) {
                context.returnMessage = labelledMessage(context, SUFFIX_IS_NOT_VALID);
            }
            return;
        }
        // :2218 SET FLG-SIGNED-NUMBER-ISVALID TO TRUE
        context.signedNumberState = FieldState.VALID;
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1250-EDIT-SIGNED-9V2-EXIT.}, logical lines
     * {@code :2221-2223}. A bare {@code EXIT}; a tracked, cited no-op.
     */
    private void editSignedAmount1250Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :2221-2223.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1260-EDIT-US-PHONE-NUM.}, logical lines
     * {@code :2225-2245}. The entry point of a four-paragraph fall-through chain that validates a North
     * American telephone number supplied as three separate screen fields.
     * <p>{@code :2232} {@code SET WS-EDIT-US-PHONE-IS-INVALID TO TRUE}, then {@code :2234-2244} tests
     * whether <em>all three</em> components are absent; if so the whole number is accepted as
     * not-supplied and control jumps straight to {@code EDIT-US-PHONE-EXIT} at {@code :2241}. Otherwise
     * control <strong>falls through</strong> into {@code EDIT-AREA-CODE} - there is no {@code PERFORM},
     * the paragraphs are simply adjacent, which is why each is mapped to its own method and this one
     * calls the next explicitly.</p>
     * <p><strong>Defect D14, severity MEDIUM - a copy-paste error in the all-absent test.</strong> The
     * third conjunct at {@code :2238-2239} reads
     * {@code (WS-EDIT-US-PHONE-NUMA EQUAL SPACES OR WS-EDIT-US-PHONE-NUMC EQUAL LOW-VALUES)}. The first
     * operand should be {@code NUMC}: as written, the line-number component's spaces case is tested
     * against the <em>area code</em>. The observable consequence is that a number whose area code is
     * spaces and whose line number is spaces-but-not-low-values still short-circuits as
     * "nothing supplied", while a number with a low-values area code and a spaces line number does not.
     * <strong>The defect is reproduced exactly</strong>: correcting it would reject payloads the source
     * accepts. Remediation, if parity is ever relaxed, is to substitute {@code NUMC} for the first
     * {@code NUMA} at {@code :2238}.</p>
     * @param context the per-invocation state carrier; reads the three {@code editPhone*} fields, writes
     *     the three {@code phone*State} fields
     */
    private void editUsPhoneNumber1260(final UpdateContext context) {
        // :2232 SET WS-EDIT-US-PHONE-IS-INVALID TO TRUE
        context.phoneNumberValid = false;
        // The three per-component tri-states start from the group's cleared state.
        context.phoneAreaCodeState = FieldState.VALID;
        context.phonePrefixState = FieldState.VALID;
        context.phoneLineNumberState = FieldState.VALID;
        // :2234-2244 all three components absent. D14: the third conjunct tests NUMA where the source
        // author plainly meant NUMC; the defect is reproduced rather than corrected.
        final boolean areaCodeAbsent = isBlankOrLowValues(context.editPhoneAreaCode);
        final boolean prefixAbsent = isBlankOrLowValues(context.editPhonePrefix);
        final boolean areaCodeSpaces = isSpaces(context.editPhoneAreaCode);
        final boolean lineNumberLowValues = isLowValues(context.editPhoneLineNumber);
        if (areaCodeAbsent && prefixAbsent && (areaCodeSpaces || lineNumberLowValues)) {
            // :2240-2241 SET WS-EDIT-US-PHONE-IS-VALID TO TRUE / GO TO EDIT-US-PHONE-EXIT
            context.phoneNumberValid = true;
            editUsPhoneExit();
            return;
        }
        // :2245 falls through into EDIT-AREA-CODE
        editAreaCode(context);
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code EDIT-AREA-CODE.}, logical lines
     * {@code :2246-2315}. One of the four un-numbered Area-A labels inside the telephone chain; each is a
     * genuine paragraph and each is mapped to its own method.
     * <p>Four tests in sequence, and <strong>every failure jumps forward to
     * {@code EDIT-US-PHONE-PREFIX}</strong> rather than abandoning the number, so the prefix and line
     * number are still validated after an area-code failure: blank at {@code :2247-2262}; not numeric at
     * {@code :2264-2278}; numerically zero at {@code :2280-2294}; and not a recognised general-purpose
     * North American area code at {@code :2296-2312}, checked through
     * {@code ValidationLookupService}, which owns the {@code CSLKPCDY} tables copied in at
     * {@code :602}.</p>
     * <p>{@code :2296-2297} applies {@code FUNCTION TRIM} before the lookup, so a right-padded area code
     * still matches.</p>
     * @param context the per-invocation state carrier
     */
    private void editAreaCode(final UpdateContext context) {
        final String areaCode = context.editPhoneAreaCode;
        // :2247-2262 blank
        if (isBlankOrLowValues(areaCode)) {
            context.inputError = true;
            context.phoneAreaCodeState = FieldState.BLANK;
            if (isReturnMessageOff(context)) {
                context.returnMessage = labelledMessage(context, SUFFIX_AREA_CODE_SUPPLIED);
            }
            editUsPhonePrefix(context);
            return;
        }
        // :2264-2278 IS NUMERIC over the three-byte field
        if (!isAllDigits(areaCode, PHONE_AREA_CODE_LENGTH)) {
            context.inputError = true;
            context.phoneAreaCodeState = FieldState.NOT_OK;
            if (isReturnMessageOff(context)) {
                context.returnMessage = labelledMessage(context, SUFFIX_AREA_CODE_THREE_DIGITS);
            }
            editUsPhonePrefix(context);
            return;
        }
        // :2280-2294 the numeric redefinition equals zero
        if (isNumericallyZero(areaCode)) {
            context.inputError = true;
            context.phoneAreaCodeState = FieldState.NOT_OK;
            if (isReturnMessageOff(context)) {
                context.returnMessage = labelledMessage(context, SUFFIX_AREA_CODE_NOT_ZERO);
            }
            editUsPhonePrefix(context);
            return;
        }
        // :2296-2312 the CSLKPCDY general-purpose area-code table, owned by ValidationLookupService
        if (!validationLookupService.isValidGeneralPurposeAreaCode(areaCode.trim())) {
            context.inputError = true;
            context.phoneAreaCodeState = FieldState.NOT_OK;
            if (isReturnMessageOff(context)) {
                context.returnMessage = labelledMessage(context, SUFFIX_AREA_CODE_NOT_NORTH_AMERICAN);
            }
            editUsPhonePrefix(context);
            return;
        }
        // :2314 SET FLG-EDIT-US-PHONEA-ISVALID TO TRUE, then fall through
        context.phoneAreaCodeState = FieldState.VALID;
        editUsPhonePrefix(context);
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code EDIT-US-PHONE-PREFIX.}, logical lines
     * {@code :2316-2368}. Validates the three-digit exchange prefix.
     * <p>Three tests, each jumping forward to {@code EDIT-US-PHONE-LINENUM} on failure: blank at
     * {@code :2318-2333}, not numeric at {@code :2335-2349}, numerically zero at {@code :2351-2365}.
     * There is deliberately <strong>no lookup table for the prefix</strong> - only the area code is
     * checked against {@code CSLKPCDY}.</p>
     * @param context the per-invocation state carrier
     */
    private void editUsPhonePrefix(final UpdateContext context) {
        final String prefix = context.editPhonePrefix;
        // :2318-2333 blank
        if (isBlankOrLowValues(prefix)) {
            context.inputError = true;
            context.phonePrefixState = FieldState.BLANK;
            if (isReturnMessageOff(context)) {
                context.returnMessage = labelledMessage(context, SUFFIX_PREFIX_SUPPLIED);
            }
            editUsPhoneLineNumber(context);
            return;
        }
        // :2335-2349 IS NUMERIC
        if (!isAllDigits(prefix, PHONE_PREFIX_LENGTH)) {
            context.inputError = true;
            context.phonePrefixState = FieldState.NOT_OK;
            if (isReturnMessageOff(context)) {
                context.returnMessage = labelledMessage(context, SUFFIX_PREFIX_THREE_DIGITS);
            }
            editUsPhoneLineNumber(context);
            return;
        }
        // :2351-2365 equals zero
        if (isNumericallyZero(prefix)) {
            context.inputError = true;
            context.phonePrefixState = FieldState.NOT_OK;
            if (isReturnMessageOff(context)) {
                context.returnMessage = labelledMessage(context, SUFFIX_PREFIX_NOT_ZERO);
            }
            editUsPhoneLineNumber(context);
            return;
        }
        // :2367 SET FLG-EDIT-US-PHONEB-ISVALID TO TRUE, then fall through
        context.phonePrefixState = FieldState.VALID;
        editUsPhoneLineNumber(context);
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code EDIT-US-PHONE-LINENUM.}, logical lines
     * {@code :2370-2422}. Validates the four-digit line number.
     * <p>Three tests, each jumping forward to {@code EDIT-US-PHONE-EXIT} on failure: blank at
     * {@code :2371-2386}, not numeric at {@code :2388-2402} - note the message says
     * {@code "A 4 digit number"} where the area code and prefix say three - and numerically zero at
     * {@code :2404-2418}.</p>
     * @param context the per-invocation state carrier
     */
    private void editUsPhoneLineNumber(final UpdateContext context) {
        final String lineNumber = context.editPhoneLineNumber;
        // :2371-2386 blank
        if (isBlankOrLowValues(lineNumber)) {
            context.inputError = true;
            context.phoneLineNumberState = FieldState.BLANK;
            if (isReturnMessageOff(context)) {
                context.returnMessage = labelledMessage(context,
                        SUFFIX_LINE_NUMBER_SUPPLIED);
            }
            editUsPhoneExit();
            return;
        }
        // :2388-2402 IS NUMERIC over the four-byte field
        if (!isAllDigits(lineNumber, PHONE_LINE_NUMBER_LENGTH)) {
            context.inputError = true;
            context.phoneLineNumberState = FieldState.NOT_OK;
            if (isReturnMessageOff(context)) {
                context.returnMessage = labelledMessage(context, SUFFIX_LINE_NUMBER_FOUR_DIGITS);
            }
            editUsPhoneExit();
            return;
        }
        // :2404-2418 equals zero
        if (isNumericallyZero(lineNumber)) {
            context.inputError = true;
            context.phoneLineNumberState = FieldState.NOT_OK;
            if (isReturnMessageOff(context)) {
                context.returnMessage = labelledMessage(context, SUFFIX_LINE_NUMBER_NOT_ZERO);
            }
            editUsPhoneExit();
            return;
        }
        // :2421 SET FLG-EDIT-US-PHONEC-ISVALID TO TRUE, then fall through to the exit
        context.phoneLineNumberState = FieldState.VALID;
        editUsPhoneExit();
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code EDIT-US-PHONE-EXIT.}, logical lines
     * {@code :2424-2426}. A bare {@code EXIT} and the common landing point of every jump in the telephone
     * chain; a tracked, cited no-op.
     */
    private void editUsPhoneExit() {
        // EXIT - intentional no-op preserved for control-flow parity with :2424-2426.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1260-EDIT-US-PHONE-NUM-EXIT.}, logical lines
     * {@code :2427-2429}. A bare {@code EXIT}; a tracked, cited no-op. Distinct from
     * {@code EDIT-US-PHONE-EXIT} above, which is the chain's internal landing label.
     */
    private void editUsPhoneNumber1260Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :2427-2429.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1265-EDIT-US-SSN.}, logical lines
     * {@code :2431-2488}. Validates the social security number, supplied as three screen fields in the
     * {@code xxx-xx-xxxx} shape documented by the comment at {@code :2432-2435}.
     * <p>Each part is edited by re-entering {@code 1245-EDIT-NUM-REQD} with its own label and its own
     * length: {@code "SSN: First 3 chars"} and length three at {@code :2439-2445},
     * {@code "SSN 4th &amp; 5th chars"} and length two at {@code :2469-2475}, and
     * {@code "SSN Last 4 chars"} and length four at {@code :2481-2487}. Part one additionally goes
     * through the administrative-range test at {@code :2448-2464}: the {@code 88 INVALID-SSN-PART1} at
     * {@code :121-123} rejects {@code 000}, {@code 666} and the {@code 900}-{@code 999} range.</p>
     * <p><strong>Defect D15, severity MEDIUM - an unbalanced {@code IF} silently scopes two whole
     * edits.</strong> The {@code IF FLG-EDIT-US-SSN-PART1-ISVALID} opened at {@code :2448} has
     * <em>no</em> matching {@code END-IF}: the {@code END-IF} at {@code :2463} closes
     * {@code IF WS-RETURN-MSG-OFF} from {@code :2454}, and the one at {@code :2464} closes
     * {@code IF INVALID-SSN-PART1} from {@code :2450}. The outer {@code IF} is therefore terminated only
     * by the sentence period at {@code :2488}, which places the part-two edit at {@code :2469-2475}
     * <strong>and</strong> the part-three edit at {@code :2481-2487} inside its true branch. The
     * observable consequence is that <strong>when part one fails its numeric edit, parts two and three
     * are never validated at all</strong> and their tri-states keep whatever the group's cleared state
     * left. <strong>The scoping is reproduced exactly.</strong> Remediation, if parity is ever relaxed,
     * is to insert an {@code END-IF} after {@code :2464}; doing so here would reject payloads the source
     * accepts and would change which message the screen shows.</p>
     * <p>One further irregularity, behaviourally inert: the {@code IF WS-RETURN-MSG-OFF} at {@code :2454}
     * has an {@code ELSE CONTINUE} at {@code :2461-2462} that no sibling latch has. It is preserved as
     * written, which in Java simply means the {@code if} has no {@code else}.</p>
     * @param context the per-invocation state carrier
     */
    private void editUsSsn1265(final UpdateContext context) {
        // :2439-2445 part one: three required digits
        context.editVariableName = SSN_PART1_LABEL;
        context.editAlphanumericText = context.newSsnPart1;
        context.editAlphanumericLength = SSN_PART1_LENGTH;
        editNumericRequired1245(context);
        editNumericRequired1245Exit();
        context.ssnPart1State = context.alphanumericState;
        // :2448 IF FLG-EDIT-US-SSN-PART1-ISVALID - D15: this IF is closed only by the period at :2488,
        // so the part-two and part-three edits below sit inside its true branch.
        if (context.ssnPart1State == FieldState.VALID) {
            // :2450-2464 the administrative-range test: 000, 666 and 900 through 999 are invalid
            if (isAdministrativelyInvalidSsnPart1(context.newSsnPart1)) {
                context.inputError = true;
                context.ssnPart1State = FieldState.NOT_OK;
                if (isReturnMessageOff(context)) {
                    context.returnMessage = labelledMessage(context, SUFFIX_SSN_PART1_RANGE);
                }
            }
            // :2469-2475 part two: two required digits
            context.editVariableName = SSN_PART2_LABEL;
            context.editAlphanumericText = context.newSsnPart2;
            context.editAlphanumericLength = SSN_PART2_LENGTH;
            editNumericRequired1245(context);
            editNumericRequired1245Exit();
            context.ssnPart2State = context.alphanumericState;
            // :2481-2487 part three: four required digits
            context.editVariableName = SSN_PART3_LABEL;
            context.editAlphanumericText = context.newSsnPart3;
            context.editAlphanumericLength = SSN_PART3_LENGTH;
            editNumericRequired1245(context);
            editNumericRequired1245Exit();
            context.ssnPart3State = context.alphanumericState;
        }
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1265-EDIT-US-SSN-EXIT.}, logical lines
     * {@code :2489-2491}. A bare {@code EXIT}; a tracked, cited no-op.
     */
    private void editUsSsn1265Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :2489-2491.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1270-EDIT-US-STATE-CD.}, logical lines
     * {@code :2493-2510}. Checks the two-character state code against the {@code CSLKPCDY} state table,
     * whose {@code 88 VALID-US-STATE-CODE} begins at {@code app/cpy/CSLKPCDY.cpy:1013}.
     * <p>Performed only when {@code 1225-EDIT-ALPHA-REQD} has already accepted the field, per the guard
     * at {@code :1599}. The lookup itself is delegated to {@code ValidationLookupService}, which loads the
     * membership from a classpath resource rather than regenerating a thousand Java constants.</p>
     * <p>{@code :2494} moves the field without trimming, so the lookup sees the padded value; the service
     * normalises. On failure {@code :2499} sets only {@code FLG-STATE-NOT-OK} - the postal-code
     * tri-state is untouched here, unlike {@code 1280-EDIT-US-STATE-ZIP-CD}, which sets both.</p>
     * @param context the per-invocation state carrier
     */
    private void editUsStateCode1270(final UpdateContext context) {
        // :2494-2495 MOVE ACUP-NEW-CUST-ADDR-STATE-CD TO US-STATE-CODE-TO-EDIT / IF VALID-US-STATE-CODE
        if (validationLookupService.isValidUsStateCode(context.newStateCode)) {
            return;
        }
        // :2498-2508 SET INPUT-ERROR / SET FLG-STATE-NOT-OK / latched message / GO TO ...-EXIT
        context.inputError = true;
        context.stateState = FieldState.NOT_OK;
        if (isReturnMessageOff(context)) {
            context.returnMessage = labelledMessage(context, SUFFIX_NOT_A_VALID_STATE);
        }
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1270-EDIT-US-STATE-CD-EXIT.}, logical lines
     * {@code :2511-2513}. A bare {@code EXIT}; a tracked, cited no-op.
     */
    private void editUsStateCode1270Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :2511-2513.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1275-EDIT-FICO-SCORE.}, logical lines
     * {@code :2514-2530}. Range-checks the FICO score against the {@code 88 FICO-RANGE-IS-VALID} declared
     * at {@code :848-849}, whose bounds are {@code 300 THROUGH 850} inclusive.
     * <p>Performed only when {@code 1245-EDIT-NUM-REQD} has already accepted the field as three non-zero
     * digits, per the guard at {@code :1553}. Because that guard has already established the value is
     * numeric, the parse here cannot fail; the score is nonetheless read defensively so that a future
     * caller cannot induce a runtime failure.</p>
     * @param context the per-invocation state carrier
     */
    private void editFicoScore1275(final UpdateContext context) {
        // :2515 IF FICO-RANGE-IS-VALID - the 88 at :848-849, bounds 300 through 850 inclusive
        final Integer score = parseUnsignedDigits(context.newFicoScore);
        if (score != null && score >= FICO_SCORE_MINIMUM && score <= FICO_SCORE_MAXIMUM) {
            return;
        }
        // :2518-2528 SET INPUT-ERROR / SET FLG-FICO-SCORE-NOT-OK / latched message / GO TO ...-EXIT
        context.inputError = true;
        context.ficoScoreState = FieldState.NOT_OK;
        if (isReturnMessageOff(context)) {
            context.returnMessage = labelledMessage(context, SUFFIX_FICO_RANGE);
        }
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1275-EDIT-FICO-SCORE-EXIT.}, logical lines
     * {@code :2531-2533}. A bare {@code EXIT}; a tracked, cited no-op.
     */
    private void editFicoScore1275Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :2531-2533.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1280-EDIT-US-STATE-ZIP-CD.}, logical lines
     * {@code :2536-2557}. Cross-checks the state code against the first two digits of the postal code.
     * <p>{@code :2537-2540} builds a four-character key by concatenating the two-character state code
     * with {@code ACUP-NEW-CUST-ADDR-ZIP(1:2)}, matching
     * {@code 02 US-STATE-AND-FIRST-ZIP2 PIC X(4)} at {@code app/cpy/CSLKPCDY.cpy:1072}, and
     * {@code :2542} tests it against {@code 88 VALID-US-STATE-ZIP-CD2-COMBO} from
     * {@code app/cpy/CSLKPCDY.cpy:1073}. Only the first two postal digits participate; the remaining
     * three are never validated anywhere in the program.</p>
     * <p>Two behaviours distinguish this edit from {@code 1270-EDIT-US-STATE-CD}. It sets
     * <strong>both</strong> tri-states to {@code NOT_OK} at {@code :2546-2547}, so a mismatch highlights
     * the state field and the postal-code field together. And its message at {@code :2550} is a bare
     * literal with <strong>no label prefix</strong> - the only edit message in the program that does not
     * begin with the field label.</p>
     * <p>Performed only when both components are individually valid, per the guard at
     * {@code :1665-1667}.</p>
     * @param context the per-invocation state carrier
     */
    private void editUsStateZipCode1280(final UpdateContext context) {
        // :2537-2540 STRING state-code + zip(1:2) INTO US-STATE-AND-FIRST-ZIP2
        final String stateCode = padRight(context.newStateCode, STATE_CODE_LENGTH);
        final String zipPrefix = padRight(context.newZip, ZIP_SCREEN_LENGTH)
                .substring(0, ValidationLookupService.ZIP_PREFIX_LENGTH);
        final String key = stateCode + zipPrefix;
        // :2542 IF VALID-US-STATE-ZIP-CD2-COMBO
        if (validationLookupService.isValidStateZipCodeCombination(key)) {
            return;
        }
        // :2545-2555 both tri-states fail, and the message carries no label prefix
        context.inputError = true;
        context.stateState = FieldState.NOT_OK;
        context.zipState = FieldState.NOT_OK;
        if (isReturnMessageOff(context)) {
            context.returnMessage = INVALID_ZIP_FOR_STATE_MESSAGE;
        }
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 1280-EDIT-US-STATE-ZIP-CD-EXIT.}, logical lines
     * {@code :2558-2560}. A bare {@code EXIT}; a tracked, cited no-op.
     */
    private void editUsStateZipCode1280Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :2558-2560.
    }


    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 2000-DECIDE-ACTION.}, logical lines
     * {@code :2562-2642}. The conversation's state machine: an eight-branch {@code EVALUATE TRUE} that
     * decides, from the current marker and the pressed key, whether to fetch, to prompt, to write, or to
     * abend.
     * <p><strong>The branch order is load-bearing.</strong> The specific branch at {@code :2602-2603},
     * {@code WHEN ACUP-CHANGES-OK-NOT-CONFIRMED AND CCARD-AID-PFK05}, must be evaluated
     * <em>before</em> the general branch at {@code :2620}, {@code WHEN
     * ACUP-CHANGES-OK-NOT-CONFIRMED}. A first-match {@code EVALUATE} takes the general branch if it comes
     * first, and the write becomes unreachable. The Java {@code if}/{@code else if} chain below preserves
     * the order exactly and must never be reordered.</p>
     * <p>{@code :2568} {@code WHEN ACUP-DETAILS-NOT-FETCHED} <strong>falls through</strong> into
     * {@code :2572} {@code WHEN CCARD-AID-PFK12}, sharing the single body at {@code :2573-2580}: when the
     * account filter is valid, clear the message, read the account chain, and advance to
     * {@code ACUP-SHOW-DETAILS} <em>only</em> if the customer was found. In Java that is one condition
     * with a disjunction, not two duplicated bodies.</p>
     * <p>{@code :2585-2591} decides whether a displayed screen has become a valid change set:
     * {@code IF INPUT-ERROR OR NO-CHANGES-DETECTED CONTINUE ELSE SET
     * ACUP-CHANGES-OK-NOT-CONFIRMED}. {@code NO-CHANGES-DETECTED} is a {@code WS-RETURN-MSG} message
     * literal rather than a flag, so the test is a string comparison against the message the comparison
     * paragraph left.</p>
     * <p>{@code :2596-2597} and {@code :2620-2621} are deliberate {@code CONTINUE} bodies - the screen is
     * simply redisplayed with its error markers. They are retained as explicit, cited empty branches
     * because deleting them would collapse the eight-branch structure the traceability matrix is proved
     * against.</p>
     * <p>{@code :2625-2632} handles the post-write turn: revert to {@code ACUP-SHOW-DETAILS} and, when the
     * conversation was not entered from another transaction, zero the COMMAREA account and card
     * identifiers and clear the status.</p>
     * <p>{@code :2633-2640} is the catch-all: {@code ABEND-CODE '0001'} with
     * {@code ABEND-MSG 'UNEXPECTED DATA SCENARIO'}, culprit {@code COACTUPC}, reason spaces, then the
     * abend routine. Note this program <em>does</em> perform the routine with that payload, where its
     * sibling discards it - and note the payload code {@code '0001'} is distinct from the terminal CICS
     * abend code {@code '9999'} that the routine itself issues.</p>
     * @param context the per-invocation state carrier
     */
    private void decideAction2000(final UpdateContext context) {
        // :2568/:2572 the fall-through pair, sharing one body at :2573-2580
        if (context.changeAction == ChangeAction.DETAILS_NOT_FETCHED
                || context.aidKey == AidKey.PFK12) {
            // :2573 IF FLG-ACCTFILTER-ISVALID
            if (context.accountFilterState == FieldState.VALID) {
                // :2574 SET WS-RETURN-MSG-OFF TO TRUE - unlatched, the message is cleared outright
                context.returnMessage = RETURN_MESSAGE_OFF;
                readAccount9000(context);
                readAccount9000Exit();
                // :2577-2579 the advance is gated on the CUSTOMER read, not the account read
                if (context.foundCustomerInMaster) {
                    context.changeAction = ChangeAction.SHOW_DETAILS;
                }
            }
            return;
        }
        // :2585-2591 a displayed screen becomes a candidate change set
        if (context.changeAction == ChangeAction.SHOW_DETAILS) {
            if (context.inputError || NO_CHANGES_DETECTED_MESSAGE.equals(context.returnMessage)) {
                // :2588 CONTINUE - redisplay with the existing message
                return;
            }
            context.changeAction = ChangeAction.CHANGES_OK_NOT_CONFIRMED;
            return;
        }
        // :2596-2597 CONTINUE - the edits failed, redisplay with the field markers
        if (context.changeAction == ChangeAction.CHANGES_NOT_OK) {
            return;
        }
        // :2602-2603 THE WRITE BRANCH. This specific branch MUST precede the general one at :2620.
        if (context.changeAction == ChangeAction.CHANGES_OK_NOT_CONFIRMED
                && context.aidKey == AidKey.PFK05) {
            // :2604-2605 PERFORM 9600-WRITE-PROCESSING THRU 9600-WRITE-PROCESSING-EXIT
            writeProcessing9600(context);
            writeProcessing9600Exit();
            classifyWriteOutcome2606(context);
            return;
        }
        // :2620-2621 CONTINUE - a validated but unconfirmed change set awaiting PF05
        if (context.changeAction == ChangeAction.CHANGES_OK_NOT_CONFIRMED) {
            return;
        }
        // :2625-2632 the turn after a successful write
        if (context.changeAction == ChangeAction.CHANGES_OKAYED_AND_DONE) {
            context.changeAction = ChangeAction.SHOW_DETAILS;
            if (isBlankOrLowValues(context.fromTransactionId)) {
                context.commAreaAccountId = zeroes(ACCOUNT_ID_LENGTH);
                context.commAreaCardNumber = zeroes(CARD_NUMBER_LENGTH);
                context.commAreaAccountStatus = null;
            }
            return;
        }
        // :2633-2640 WHEN OTHER: an unexpected marker is a programming error, not user error
        context.abendCulprit = PROGRAM_NAME;
        context.abendCode = UNEXPECTED_SCENARIO_ABEND_CODE;
        context.abendReason = ABEND_REASON_SPACES;
        context.abendMessage = UNEXPECTED_DATA_SCENARIO_MESSAGE;
        final FatalProcessingException fatal = abendRoutine(context, null);
        abendRoutineExit();
        retainFailure(context, fatal);
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, the inner {@code EVALUATE TRUE} at logical lines
     * {@code :2606-2615}, nested inside {@code 2000-DECIDE-ACTION}'s PF05 branch. It is an inline
     * construct rather than a paragraph, so it does not consume one of the eighty-seven label methods; it
     * is factored out solely so that the defect below can carry its own documentation and its own tests.
     *
     * <h4>BLOCKER - the customer-lock failure is reported as success</h4>
     * <p>The source reads, verbatim:</p>
     * <pre>
     * 2606  EVALUATE TRUE
     * 2607     WHEN COULD-NOT-LOCK-ACCT-FOR-UPDATE
     * 2608          SET ACUP-CHANGES-OKAYED-LOCK-ERROR TO TRUE
     * 2609     WHEN LOCKED-BUT-UPDATE-FAILED
     * 2610        SET ACUP-CHANGES-OKAYED-BUT-FAILED TO TRUE
     * 2611     WHEN DATA-WAS-CHANGED-BEFORE-UPDATE
     * 2612         SET ACUP-SHOW-DETAILS            TO TRUE
     * 2613     WHEN OTHER
     * 2614        SET ACUP-CHANGES-OKAYED-AND-DONE   TO TRUE
     * 2615  END-EVALUATE
     * </pre>
     * <p><strong>{@code COULD-NOT-LOCK-CUST-FOR-UPDATE} is never tested.</strong> An exhaustive census
     * finds it at exactly two places in the whole program: its declaration at {@code :519-520} and its
     * single {@code SET} at {@code :3939}. It appears in no {@code WHEN}, no {@code IF} and no
     * {@code EVALUATE} anywhere. A customer read-for-update failure therefore falls through
     * {@code WHEN OTHER} at {@code :2613} and is reported to the operator as
     * {@code ACUP-CHANGES-OKAYED-AND-DONE} - <strong>top-level success, even though nothing whatsoever
     * was written</strong>, because {@code 9600-WRITE-PROCESSING} left at {@code :3941} before reaching
     * either rewrite.</p>
     * <p><strong>Severity: Blocker. This is reproduced exactly and is not fixed.</strong> Behavioural
     * parity is the contract of this migration, and adding a fifth {@code WHEN} for the customer-lock flag
     * would change which response the caller receives for a real, reachable input - a behaviour change,
     * not a bug fix. Remediation, should parity ever be relaxed by the owning stakeholder, is a single
     * inserted branch: {@code WHEN COULD-NOT-LOCK-CUST-FOR-UPDATE / SET ACUP-CHANGES-OKAYED-LOCK-ERROR}
     * between {@code :2607} and {@code :2609}.</p>
     * <p>What this implementation <em>does</em> add, because it costs no behavioural change, is
     * observability: the internal outcome remains distinguishable on
     * {@link UpdateContext#customerLockFailed}, and the fall-through is logged at warning level with the
     * account and customer identifiers only - never the payload. That satisfies Rule 1 Clause A's
     * observability requirement without touching the response.</p>
     * @param context the per-invocation state carrier, carrying the write outcome flags
     */
    private void classifyWriteOutcome2606(final UpdateContext context) {
        // :2607-2608 the ACCOUNT lock failure is tested
        if (context.accountLockFailed) {
            context.changeAction = ChangeAction.CHANGES_OKAYED_LOCK_ERROR;
            return;
        }
        // :2609-2610 a rewrite failure on either file
        if (context.lockedButUpdateFailed) {
            context.changeAction = ChangeAction.CHANGES_OKAYED_BUT_FAILED;
            return;
        }
        // :2611-2612 someone else changed the record while the screen was displayed
        if (context.dataWasChangedBeforeUpdate) {
            context.changeAction = ChangeAction.SHOW_DETAILS;
            return;
        }
        // :2613-2614 WHEN OTHER. BLOCKER: the customer lock failure lands here and is reported as
        // success. There are deliberately only four branches, and the customer-lock flag is deliberately
        // absent from all of them. Do not add a fifth branch - see this method's documentation.
        if (context.customerLockFailed) {
            // The two identifiers this event used to carry are withheld. The condition being reported is that
            // the decider does not test the customer-lock flag, which is a property of the control flow rather
            // than of any particular pair of records - and this is the one outcome where the caller is told
            // nothing went wrong, so an operator reading it needs the discrepancy explained, not the keys. The
            // correlation identifier in the MDC ties the event to the request that produced it.
            LOG.warn("CAUP customer record could not be locked for update; the legacy decider at"
                    + " app/cbl/COACTUPC.cbl:2606-2615 never tests this outcome, so the caller is"
                    + " told the update succeeded although nothing was written.");
        }
        context.changeAction = ChangeAction.CHANGES_OKAYED_AND_DONE;
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 2000-DECIDE-ACTION-EXIT.}, logical lines
     * {@code :2643-2645}. A bare {@code EXIT}; a tracked, cited no-op.
     */
    private void decideAction2000Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :2643-2645.
    }


    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 3000-SEND-MAP.}, logical lines
     * {@code :2649-2662}. Composes the outbound screen by performing six sub-paragraphs in a fixed order:
     * initialise, populate variables, choose the information message, set field attributes, set the
     * information-message attribute, and send.
     * <p>The order matters in one specific way: {@code 3250-SETUP-INFOMSG} runs <em>before</em>
     * {@code 3300-SETUP-SCREEN-ATTRS}, and the attribute paragraph's very first cursor branch at
     * {@code :3010-3011} tests the information and error messages that {@code 3250} has just assigned.
     * Swapping the two would change where the cursor lands.</p>
     * @param context the per-invocation state carrier
     */
    private void sendMap3000(final UpdateContext context) {
        // :2650-2651
        screenInit3100(context);
        screenInit3100Exit();
        // :2652-2653
        setupScreenVars3200(context);
        setupScreenVars3200Exit();
        // :2654-2655
        setupInfoMessage3250(context);
        setupInfoMessage3250Exit();
        // :2656-2657
        setupScreenAttributes3300(context);
        setupScreenAttributes3300Exit();
        // :2658-2659
        setupInfoMessageAttributes3390(context);
        setupInfoMessageAttributes3390Exit();
        // :2660-2661
        sendScreen3400(context);
        sendScreen3400Exit();
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 3000-SEND-MAP-EXIT.}, logical lines
     * {@code :2664-2666}. A bare {@code EXIT}; a tracked, cited no-op.
     */
    private void sendMap3000Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :2664-2666.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 3100-SCREEN-INIT.}, logical lines
     * {@code :2668-2692}. Clears the whole output map and stamps the six-field header that every one of
     * the seventeen legacy screens carries.
     * <p>{@code :2669} {@code MOVE LOW-VALUES TO CACTUPAO} discards the previous turn's output entirely,
     * which is why the buffer is replaced rather than mutated. {@code :2673-2676} stamp the two titles
     * from {@code app/cpy/COTTL01Y.cpy}, the transaction identifier and the program name.</p>
     * <p>{@code :2671} and {@code :2678} both execute {@code MOVE FUNCTION CURRENT-DATE TO
     * WS-CURDATE-DATA} - the same statement twice, seven lines apart. The duplicate is inert and is
     * preserved as a single cited read of the injected {@link java.time.Clock}, because issuing two reads
     * would let the two differ across a second boundary, which the source's single-instruction
     * {@code CURRENT-DATE} cannot do.</p>
     * <p>{@code :2680-2690} render the header date and time through the {@code app/cpy/CSDAT01Y.cpy}
     * edited groups: {@code WS-CURDATE-MM-DD-YY} with {@code '/'} separators and
     * {@code WS-CURTIME-HH-MM-SS} with {@code ':'} separators. Note {@code :2682}
     * {@code MOVE WS-CURDATE-YEAR(3:2)} - the header shows a <strong>two-digit</strong> year taken from
     * the third and fourth characters of the four-digit year, so the format is {@code MM/dd/yy}.</p>
     * <p><strong>All date and time values come from the injected {@code Clock}</strong>, never from a
     * no-argument {@code now()}. That is what makes this method testable at all, and Rule 1 Clause A's
     * determinism requirement makes it mandatory.</p>
     * @param context the per-invocation state carrier; replaces {@code context.screen}
     */
    private void screenInit3100(final UpdateContext context) {
        // :2669 MOVE LOW-VALUES TO CACTUPAO
        context.screen = new ScreenBuffer();
        context.fieldAttributes.clear();
        // :2671 and :2678 both read CURRENT-DATE; one read is taken so the two cannot disagree.
        final LocalDateTime now = LocalDateTime.now(clock);
        // :2673-2676 the four constant header fields
        context.screen.title01 = SCREEN_TITLE_01;
        context.screen.title02 = SCREEN_TITLE_02;
        context.screen.transactionName = TRANSACTION_ID;
        context.screen.programName = PROGRAM_NAME;
        // :2680-2684 MM/DD/YY, the year taken as WS-CURDATE-YEAR(3:2)
        context.screen.currentDate = HEADER_DATE_FORMAT.format(now);
        // :2686-2690 HH:MM:SS
        context.screen.currentTime = HEADER_TIME_FORMAT.format(now);
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 3100-SCREEN-INIT-EXIT.}, logical lines
     * {@code :2694-2696}. A bare {@code EXIT}; a tracked, cited no-op.
     */
    private void screenInit3100Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :2694-2696.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 3200-SETUP-SCREEN-VARS.}, logical lines
     * {@code :2698-2726}. Chooses which of the three value-population paragraphs runs, and echoes the
     * account filter.
     * <p>{@code :2700-2701} short-circuits the whole paragraph on a first entry:
     * {@code IF CDEMO-PGM-ENTER CONTINUE}. A freshly entered screen is left entirely at the
     * {@code LOW-VALUES} that {@code 3100-SCREEN-INIT} wrote, so nothing at all is populated - not even
     * the account filter.</p>
     * <p>{@code :2703-2708} echoes the filter, with one twist: when the filter is numerically zero
     * <em>and</em> its tri-state says valid, {@code LOW-VALUES} is echoed instead of the zeroes. That
     * combination arises after {@code :1452-1457} optimistically asserts validity, so the effect is to
     * blank a zero filter rather than display {@code 00000000000}.</p>
     * <p>{@code :2710-2724} is a five-branch decider with one fall-through pair at {@code :2711-2712}:
     * an unfetched screen or a zero filter shows initial values; a displayed screen shows original
     * values; any {@code ACUP-CHANGES-MADE} state - the group 88 at {@code :660-662} covering
     * {@code 'E'}, {@code 'N'}, {@code 'C'}, {@code 'L'} and {@code 'F'} - shows updated values; and
     * {@code WHEN OTHER} shows original values, the same body as the {@code ACUP-SHOW-DETAILS} branch.
     * The duplicated body is deliberate in the source and is expressed here as a single condition.</p>
     * @param context the per-invocation state carrier
     */
    private void setupScreenVars3200(final UpdateContext context) {
        // :2700-2701 a first entry leaves the whole map at LOW-VALUES
        if (context.entryMode == EntryMode.ENTER) {
            return;
        }
        // :2703-2708 echo the filter, blanking a zero filter that the optimistic assertions marked valid
        if (isNumericallyZero(context.accountFilter)
                && context.accountFilterState == FieldState.VALID) {
            context.screen.accountId = null;
        } else {
            context.screen.accountId = context.accountFilter;
        }
        // :2711-2714 the fall-through pair: unfetched, or a zero filter
        if (context.changeAction == ChangeAction.DETAILS_NOT_FETCHED
                || isNumericallyZero(context.accountFilter)) {
            showInitialValues3201(context);
            showInitialValues3201Exit();
            return;
        }
        // :2715-2717 a displayed screen shows the snapshot
        if (context.changeAction == ChangeAction.SHOW_DETAILS) {
            showOriginalValues3202(context);
            showOriginalValues3202Exit();
            return;
        }
        // :2718-2720 any of the five ACUP-CHANGES-MADE codes shows what the user submitted
        if (context.changeAction.isChangesMade()) {
            showUpdatedValues3203(context);
            showUpdatedValues3203Exit();
            return;
        }
        // :2721-2723 WHEN OTHER - the same body as the ACUP-SHOW-DETAILS branch
        showOriginalValues3202(context);
        showOriginalValues3202Exit();
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 3200-SETUP-SCREEN-VARS-EXIT.}, logical lines
     * {@code :2727-2729}. A bare {@code EXIT}; a tracked, cited no-op.
     */
    private void setupScreenVars3200Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :2727-2729.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 3201-SHOW-INITIAL-VALUES.}, logical lines
     * {@code :2731-2781}. One {@code MOVE LOW-VALUES} to a list of forty-two output fields - every field
     * on the screen except the account filter, the messages and the function-key legends.
     * <p>Because {@code 3100-SCREEN-INIT} has already cleared the whole map at {@code :2669}, this
     * paragraph is <strong>idempotent and has no observable effect</strong> on the path that reaches it.
     * It is retained and cited rather than deleted: it is reachable, it is performed at {@code :2713}, and
     * removing it would break the paragraph correspondence. The list is nonetheless written out so that
     * the field roster is provable against the source.</p>
     * @param context the per-invocation state carrier
     */
    private void showInitialValues3201(final UpdateContext context) {
        // :2732-2780 MOVE LOW-VALUES TO the forty-two data fields, in source order
        final ScreenBuffer screen = context.screen;
        screen.accountStatus = null;
        screen.creditLimit = null;
        screen.currentBalance = null;
        screen.cashCreditLimit = null;
        screen.currentCycleCredit = null;
        screen.currentCycleDebit = null;
        screen.openDateYear = null;
        screen.openDateMonth = null;
        screen.openDateDay = null;
        screen.expiryDateYear = null;
        screen.expiryDateMonth = null;
        screen.expiryDateDay = null;
        screen.reissueDateYear = null;
        screen.reissueDateMonth = null;
        screen.reissueDateDay = null;
        screen.accountGroupId = null;
        screen.customerId = null;
        screen.customerSsnPart1 = null;
        screen.customerSsnPart2 = null;
        screen.customerSsnPart3 = null;
        screen.customerFicoScore = null;
        screen.dateOfBirthYear = null;
        screen.dateOfBirthMonth = null;
        screen.dateOfBirthDay = null;
        screen.customerFirstName = null;
        screen.customerMiddleName = null;
        screen.customerLastName = null;
        screen.addressLine1 = null;
        screen.addressLine2 = null;
        screen.addressCity = null;
        screen.addressStateCode = null;
        screen.addressZip = null;
        screen.addressCountryCode = null;
        screen.phone1AreaCode = null;
        screen.phone1Prefix = null;
        screen.phone1LineNumber = null;
        screen.phone2AreaCode = null;
        screen.phone2Prefix = null;
        screen.phone2LineNumber = null;
        screen.governmentIssuedId = null;
        screen.eftAccountId = null;
        screen.primaryCardHolderIndicator = null;
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 3201-SHOW-INITIAL-VALUES-EXIT.}, logical lines
     * {@code :2783-2785}. A bare {@code EXIT}; a tracked, cited no-op.
     */
    private void showInitialValues3201Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :2783-2785.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 3202-SHOW-ORIGINAL-VALUES.}, logical lines
     * {@code :2787-2865}. Populates the screen from the {@code ACUP-OLD-*} snapshot - the record as it
     * was read, not as the user submitted it.
     * <p>{@code :2789} {@code MOVE LOW-VALUES TO WS-NON-KEY-FLAGS} clears every per-field tri-state,
     * which as established by the flag declarations at {@code :197-234} means <em>valid</em>, not unset.
     * {@code :2791} then sets the information message to the prompt-for-changes literal, overwriting
     * whatever {@code 3250-SETUP-INFOMSG} will later choose - the two paragraphs both write
     * {@code WS-INFO-MSG} and {@code 3250} runs afterwards, so this assignment is superseded on every
     * path. It is preserved because it is real, reachable code.</p>
     * <p>Two separate guards, not one. {@code :2793-2825} populates the account block when
     * <em>either</em> master record was found; {@code :2827-2864} populates the customer block only when
     * the customer was found. The disjunction in the first guard means a found-account-but-missing-customer
     * state still shows the account fields.</p>
     * <p>The five monetary fields go through {@code WS-EDIT-CURRENCY-9-2-F}, declared
     * {@code PIC +ZZZ,ZZZ,ZZZ.99} at {@code :371} - a fixed leading sign, zero-suppressed digits, group
     * separators and exactly two decimals, fifteen characters wide. {@link #formatCurrency} reproduces
     * that mask exactly, because the parity comparison is byte-for-byte.</p>
     * <p>The three dates are moved as their stored components; the social security number is
     * <strong>sliced from the nine-character snapshot</strong> at {@code :2829-2831} using
     * {@code (1:3)}, {@code (4:2)} and {@code (6:4)}; and the two telephone numbers are sliced from the
     * fifteen-character stored form at {@code :2846-2857} using {@code (2:3)}, {@code (6:3)} and
     * {@code (10:4)} - offsets that skip the stored punctuation, so the stored shape is
     * {@code (nnn)nnn-nnnn}.</p>
     * @param context the per-invocation state carrier
     */
    private void showOriginalValues3202(final UpdateContext context) {
        // :2789 MOVE LOW-VALUES TO WS-NON-KEY-FLAGS - which means "valid", per :197-234
        context.clearNonKeyFlags();
        // :2791 SET PROMPT-FOR-CHANGES - superseded by 3250, which runs after this paragraph
        context.informationMessage = INFO_PROMPT_FOR_CHANGES;
        // :2793-2866 every MOVE in this paragraph sources ACUP-OLD-*, the WORKING-STORAGE snapshot
        // group at :669 - NOT anything the caller transmitted. On the read turn that group was
        // populated moments earlier by 9500-STORE-FETCHED-DATA (:3801) from the live account and
        // customer records, and the request carries no snapshot at all; reading the request here
        // would paint an empty screen on precisely the turn whose job is to display the record just
        // read. On a re-entered turn the two agree, because 0000-MAIN's :887-893 COMMAREA slice has
        // already restored the transmitted snapshot into the same group. projectOldDetails is the
        // view over that group, so this one expression is correct on both paths.
        final AccountUpdateRequest.OldDetails old = projectOldDetails(context);
        if (old == null) {
            return;
        }
        final ScreenBuffer screen = context.screen;
        // :2793-2825 the account block, shown when EITHER master record was found
        if (context.foundAccountInMaster || context.foundCustomerInMaster) {
            screen.accountStatus = old.getActiveStatus();
            screen.currentBalance = formatCurrency(old.currentBalanceAmount());
            screen.creditLimit = formatCurrency(old.creditLimitAmount());
            screen.cashCreditLimit = formatCurrency(old.cashCreditLimitAmount());
            screen.currentCycleCredit = formatCurrency(old.currentCycleCreditAmount());
            screen.currentCycleDebit = formatCurrency(old.currentCycleDebitAmount());
            screen.openDateYear = old.openDateYear();
            screen.openDateMonth = old.openDateMonth();
            screen.openDateDay = old.openDateDay();
            screen.expiryDateYear = old.expiraionDateYear();
            screen.expiryDateMonth = old.expiraionDateMonth();
            screen.expiryDateDay = old.expiraionDateDay();
            screen.reissueDateYear = old.reissueDateYear();
            screen.reissueDateMonth = old.reissueDateMonth();
            screen.reissueDateDay = old.reissueDateDay();
            screen.accountGroupId = old.getGroupId();
        }
        // :2827-2864 the customer block, shown only when the customer was found
        if (context.foundCustomerInMaster) {
            screen.customerId = old.getCustomerId();
            // :2829-2831 the nine-character SSN sliced (1:3) (4:2) (6:4)
            final String ssn = padRight(old.getSsn(), SSN_LENGTH);
            screen.customerSsnPart1 = ssn.substring(0, SSN_AREA_END);
            screen.customerSsnPart2 = ssn.substring(SSN_AREA_END, SSN_GROUP_END);
            screen.customerSsnPart3 = ssn.substring(SSN_GROUP_END, SSN_LENGTH);
            screen.customerFicoScore = old.getFicoScore();
            screen.dateOfBirthYear = old.dateOfBirthYear();
            screen.dateOfBirthMonth = old.dateOfBirthMonth();
            screen.dateOfBirthDay = old.dateOfBirthDay();
            screen.customerFirstName = old.getFirstName();
            screen.customerMiddleName = old.getMiddleName();
            screen.customerLastName = old.getLastName();
            screen.addressLine1 = old.getAddressLine1();
            screen.addressLine2 = old.getAddressLine2();
            // :2841 ADDR-LINE-3 is the city field on the screen
            screen.addressCity = old.getAddressLine3();
            screen.addressStateCode = old.getAddressStateCode();
            screen.addressZip = old.getAddressZip();
            screen.addressCountryCode = old.getAddressCountryCode();
            // :2846-2851 (2:3) (6:3) (10:4) - the offsets skip the stored '(', ')' and '-'
            screen.phone1AreaCode = old.phoneNumber1AreaCode();
            screen.phone1Prefix = old.phoneNumber1Prefix();
            screen.phone1LineNumber = old.phoneNumber1LineNumber();
            // :2852-2857 the same slicing for the second number
            screen.phone2AreaCode = old.phoneNumber2AreaCode();
            screen.phone2Prefix = old.phoneNumber2Prefix();
            screen.phone2LineNumber = old.phoneNumber2LineNumber();
            screen.governmentIssuedId = old.getGovernmentIssuedId();
            screen.eftAccountId = old.getEftAccountId();
            screen.primaryCardHolderIndicator = old.getPrimaryCardHolderIndicator();
        }
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 3202-SHOW-ORIGINAL-VALUES-EXIT.}, logical lines
     * {@code :2867-2869}. A bare {@code EXIT}; a tracked, cited no-op.
     */
    private void showOriginalValues3202Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :2867-2869.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 3203-SHOW-UPDATED-VALUES.}, logical lines
     * {@code :2870-2949}. Populates the screen from the {@code ACUP-NEW-*} group - what the user
     * submitted - so that a rejected payload is echoed back for correction rather than discarded.
     * <p>The five monetary fields are the only conditional moves, at {@code :2874-2909}. Each tests its
     * own tri-state: when the amount parsed, the <em>numeric</em> value is rendered through the
     * fifteen-character currency mask; when it did not, the <strong>raw text the user typed</strong> is
     * echoed unchanged so they can see and correct their own keystrokes. Rendering the numeric value in
     * both cases would silently replace an unparsable entry with zero, which is precisely the behaviour
     * this branch exists to avoid.</p>
     * <p>Everything else - the three dates, the group identifier, the customer identifier, the three
     * social security parts, the FICO score, the three names, the three address lines, the state, postal
     * code and country, the six telephone components, the government-issued identifier, the electronic
     * funds account identifier and the primary-holder indicator - is echoed verbatim at
     * {@code :2911-2947}. Note {@code :2935}: {@code ACUP-NEW-CUST-ADDR-LINE-3} goes to the city field,
     * matching the receive-side mapping at {@code :1329-1334}.</p>
     * @param context the per-invocation state carrier
     */
    private void showUpdatedValues3203(final UpdateContext context) {
        final ScreenBuffer screen = context.screen;
        // :2872 the account status is echoed unconditionally
        screen.accountStatus = context.newActiveStatus;
        // :2874-2879 credit limit: the mask when it parsed, the raw keystrokes when it did not
        screen.creditLimit = context.creditLimitState == FieldState.VALID
                ? formatCurrency(context.newCreditLimit)
                : context.newCreditLimitText;
        // :2881-2888 cash credit limit
        screen.cashCreditLimit = context.cashCreditLimitState == FieldState.VALID
                ? formatCurrency(context.newCashCreditLimit)
                : context.newCashCreditLimitText;
        // :2890-2895 current balance
        screen.currentBalance = context.currentBalanceState == FieldState.VALID
                ? formatCurrency(context.newCurrentBalance)
                : context.newCurrentBalanceText;
        // :2897-2902 current cycle credit
        screen.currentCycleCredit = context.currentCycleCreditState == FieldState.VALID
                ? formatCurrency(context.newCurrentCycleCredit)
                : context.newCurrentCycleCreditText;
        // :2904-2909 current cycle debit
        screen.currentCycleDebit = context.currentCycleDebitState == FieldState.VALID
                ? formatCurrency(context.newCurrentCycleDebit)
                : context.newCurrentCycleDebitText;
        // :2911-2947 every remaining field is echoed verbatim
        screen.openDateYear = context.newOpenYear;
        screen.openDateMonth = context.newOpenMonth;
        screen.openDateDay = context.newOpenDay;
        screen.expiryDateYear = context.newExpiryYear;
        screen.expiryDateMonth = context.newExpiryMonth;
        screen.expiryDateDay = context.newExpiryDay;
        screen.reissueDateYear = context.newReissueYear;
        screen.reissueDateMonth = context.newReissueMonth;
        screen.reissueDateDay = context.newReissueDay;
        screen.accountGroupId = context.newGroupId;
        screen.customerId = context.newCustomerId;
        screen.customerSsnPart1 = context.newSsnPart1;
        screen.customerSsnPart2 = context.newSsnPart2;
        screen.customerSsnPart3 = context.newSsnPart3;
        screen.customerFicoScore = context.newFicoScore;
        screen.dateOfBirthYear = context.newDateOfBirthYear;
        screen.dateOfBirthMonth = context.newDateOfBirthMonth;
        screen.dateOfBirthDay = context.newDateOfBirthDay;
        screen.customerFirstName = context.newFirstName;
        screen.customerMiddleName = context.newMiddleName;
        screen.customerLastName = context.newLastName;
        screen.addressLine1 = context.newAddressLine1;
        screen.addressLine2 = context.newAddressLine2;
        // :2935 ADDR-LINE-3 is the city field, mirroring the receive-side mapping at :1329-1334
        screen.addressCity = context.newAddressLine3;
        screen.addressStateCode = context.newStateCode;
        screen.addressZip = context.newZip;
        screen.addressCountryCode = context.newCountryCode;
        screen.phone1AreaCode = context.newPhone1AreaCode;
        screen.phone1Prefix = context.newPhone1Prefix;
        screen.phone1LineNumber = context.newPhone1LineNumber;
        screen.phone2AreaCode = context.newPhone2AreaCode;
        screen.phone2Prefix = context.newPhone2Prefix;
        screen.phone2LineNumber = context.newPhone2LineNumber;
        screen.governmentIssuedId = context.newGovernmentIssuedId;
        screen.eftAccountId = context.newEftAccountId;
        screen.primaryCardHolderIndicator = context.newPrimaryCardHolderIndicator;
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 3203-SHOW-UPDATED-VALUES-EXIT.}, logical lines
     * {@code :2951-2953}. A bare {@code EXIT}; a tracked, cited no-op.
     */
    private void showUpdatedValues3203Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :2951-2953.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 3250-SETUP-INFOMSG.}, logical lines
     * {@code :2955-2982}. Chooses the forty-character information message from the current marker, then
     * copies it and the seventy-five-character return message onto the screen.
     * <p>A nine-branch first-match {@code EVALUATE TRUE} at {@code :2957-2977}. Three pairs of branches
     * share a message: an unentered or unfetched screen prompts for search keys ({@code :2958-2961}); a
     * displayed screen and a rejected change set both prompt for changes ({@code :2962-2965}); and both
     * failure markers inform failure ({@code :2971-2974}). The last branch at {@code :2975-2976} is the
     * default: if no message has been chosen, prompt for search keys.</p>
     * <p><strong>Note what is absent.</strong> There is no branch for {@code ACUP-CHANGES-OKAYED-LOCK}
     * beyond the two failure markers, and - consistent with the blocker documented on
     * {@link #classifyWriteOutcome2606} - a customer-lock failure has by this point already been
     * relabelled {@code ACUP-CHANGES-OKAYED-AND-DONE}, so it reaches {@code :2968-2969} and the operator
     * is told {@code "Changes committed to database"}. That is the observable face of the blocker and it
     * is reproduced deliberately.</p>
     * <p>{@code :2979} and {@code :2981} then copy both messages onto the map. The return message is
     * <strong>not</strong> chosen here - it was latched by whichever edit failed first.</p>
     * @param context the per-invocation state carrier
     */
    private void setupInfoMessage3250(final UpdateContext context) {
        // :2958-2959 a first entry
        if (context.entryMode == EntryMode.ENTER) {
            context.informationMessage = INFO_PROMPT_FOR_SEARCH_KEYS;
        // :2960-2961 nothing fetched yet
        } else if (context.changeAction == ChangeAction.DETAILS_NOT_FETCHED) {
            context.informationMessage = INFO_PROMPT_FOR_SEARCH_KEYS;
        // :2962-2965 a displayed screen, or one whose edits failed
        } else if (context.changeAction == ChangeAction.SHOW_DETAILS
                || context.changeAction == ChangeAction.CHANGES_NOT_OK) {
            context.informationMessage = INFO_PROMPT_FOR_CHANGES;
        // :2966-2967 validated and awaiting PF05
        } else if (context.changeAction == ChangeAction.CHANGES_OK_NOT_CONFIRMED) {
            context.informationMessage = INFO_PROMPT_FOR_CONFIRMATION;
        // :2968-2969 written. A customer-lock failure also lands here - see classifyWriteOutcome2606.
        } else if (context.changeAction == ChangeAction.CHANGES_OKAYED_AND_DONE) {
            context.informationMessage = INFO_CONFIRM_UPDATE_SUCCESS;
        // :2971-2974 both failure markers share one message
        } else if (context.changeAction == ChangeAction.CHANGES_OKAYED_LOCK_ERROR
                || context.changeAction == ChangeAction.CHANGES_OKAYED_BUT_FAILED) {
            context.informationMessage = INFO_INFORM_FAILURE;
        // :2975-2976 WHEN WS-NO-INFO-MESSAGE - the default when nothing has been chosen
        } else if (isBlankOrLowValues(context.informationMessage)) {
            context.informationMessage = INFO_PROMPT_FOR_SEARCH_KEYS;
        }
        // :2979 and :2981 copy both messages onto the map
        context.screen.informationMessage =
                moveAlphanumeric(context.informationMessage, INFO_MESSAGE_LENGTH);
        context.screen.errorMessage =
                moveAlphanumeric(context.returnMessage, RETURN_MESSAGE_LENGTH);
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 3250-SETUP-INFOMSG-EXIT.}, logical lines
     * {@code :2983-2985}. A bare {@code EXIT}; a tracked, cited no-op.
     */
    private void setupInfoMessage3250Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :2983-2985.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 3300-SETUP-SCREEN-ATTRS.}, logical lines
     * {@code :2986-3435}. The largest paragraph in the program: it decides which fields are enterable,
     * where the cursor lands, and which fields are highlighted as being in error.
     * <p><strong>Four distinct stages, in order.</strong></p>
     * <p>Stage one, {@code :2989-3006}: protect everything through
     * {@code 3310-PROTECT-ALL-ATTRS}, then selectively re-enable. The five-branch decider has two
     * fall-through pairs - {@code ACUP-SHOW-DETAILS} with {@code ACUP-CHANGES-NOT-OK} at
     * {@code :2997-2998} sharing {@code 3320-UNPROTECT-FEW-ATTRS}, and
     * {@code ACUP-CHANGES-OK-NOT-CONFIRMED} with {@code ACUP-CHANGES-OKAYED-AND-DONE} at
     * {@code :3001-3002} sharing a {@code CONTINUE}. Note the consequence of that {@code CONTINUE}: once
     * a change set is validated, <strong>every field is protected</strong>, so the confirming PF05 turn
     * cannot alter the payload.</p>
     * <p>Stage two, {@code :3009-3167}: cursor placement, as a first-match cascade of forty-four
     * branches over the tri-states, ending in {@code WHEN OTHER} placing the cursor on the account
     * filter. Two details matter. The cascade opens at {@code :3010-3012} with
     * {@code WHEN FOUND-ACCOUNT-DATA} and {@code WHEN NO-CHANGES-DETECTED} - message-literal tests, not
     * flags - which is why {@code 3250-SETUP-INFOMSG} must have run first. And
     * {@code :3110-3111} has <strong>no {@code -BLANK} companion</strong> for the middle name, alone
     * among the forty-four, because {@code 1235-EDIT-ALPHA-OPT} can never set that tri-state to
     * {@code BLANK}.</p>
     * <p>Stage three, {@code :3171-3192}: the account filter's own colour and marker.
     * {@code :3171-3173} restores the default colour when the conversation arrived from the card-list
     * screen; {@code :3176-3178} reddens a rejected filter; {@code :3180-3184} additionally writes the
     * {@code '*'} marker for a <em>blank</em> filter on a re-entry. Then {@code :3186-3192} leaves the
     * paragraph early whenever the screen is unfetched or the filter itself is bad - so
     * <strong>none of the thirty-nine per-field markers below is applied when the filter is the
     * problem</strong>.</p>
     * <p>Stage four, {@code :3208-3435}: thirty-nine expansions of
     * {@code COPY CSSETATY REPLACING}, each three lines of substitution parameters. The copybook body is
     * an inline {@code IF} fragment with no Area-A label of its own, so it contributes no method; all
     * thirty-nine belong to this one paragraph. Its logic is
     * {@code IF (FLG-x-NOT-OK OR FLG-x-BLANK) AND CDEMO-PGM-REENTER / MOVE DFHRED / IF FLG-x-BLANK /
     * MOVE '*'} - which is exactly why the field-error model must be <strong>tri-state</strong>: a single
     * boolean cannot distinguish {@code BLANK} from {@code NOT_OK}, and the {@code '*'} marker is emitted
     * only for {@code BLANK}.</p>
     * <p>The {@code CDEMO-PGM-REENTER} gate has no stateless equivalent, because a REST request carries no
     * notion of "the same screen being redisplayed". The marker is therefore emitted whenever the field
     * state is {@code BLANK} on a submitted request, which is the closest faithful reading; that
     * substitution is recorded in the mechanism register in this class's documentation.</p>
     * @param context the per-invocation state carrier
     */
    private void setupScreenAttributes3300(final UpdateContext context) {
        // :2989-2990 protect every field first
        protectAllAttributes3310(context);
        protectAllAttributes3310Exit();
        // :2993-3006 then selectively re-enable
        if (context.changeAction == ChangeAction.DETAILS_NOT_FETCHED) {
            // :2996 only the account filter is enterable
            context.putAttribute(FIELD_ACCOUNT_ID, ATTRIBUTE_UNPROTECTED_FSET);
        } else if (context.changeAction == ChangeAction.SHOW_DETAILS
                || context.changeAction == ChangeAction.CHANGES_NOT_OK) {
            // :2997-3000 the fall-through pair sharing 3320-UNPROTECT-FEW-ATTRS
            unprotectFewAttributes3320(context);
            unprotectFewAttributes3320Exit();
        } else if (context.changeAction == ChangeAction.CHANGES_OK_NOT_CONFIRMED
                || context.changeAction == ChangeAction.CHANGES_OKAYED_AND_DONE) {
            // :3001-3003 CONTINUE - everything stays protected, so PF05 cannot alter the payload
            LOG.debug("CAUP screen fully protected for changeAction={}", context.changeAction);
        } else {
            // :3004-3005 WHEN OTHER - the same body as the unfetched branch
            context.putAttribute(FIELD_ACCOUNT_ID, ATTRIBUTE_UNPROTECTED_FSET);
        }
        // :3009-3167 cursor placement, a first-match cascade ending on the account filter
        context.cursorField = resolveCursorField3009(context);
        // :3171-3173 arriving from the card-list screen restores the default colour
        if (MENU_CARD_LIST_MAPSET.equals(context.lastMapset)) {
            context.putColour(FIELD_ACCOUNT_ID, COLOUR_DEFAULT);
        }
        // :3176-3178 a rejected filter is reddened
        if (context.accountFilterState == FieldState.NOT_OK) {
            context.putColour(FIELD_ACCOUNT_ID, COLOUR_RED);
        }
        // :3180-3184 a blank filter on a re-entry also gets the '*' marker
        if (context.accountFilterState == FieldState.BLANK
                && context.entryMode == EntryMode.REENTER) {
            context.screen.accountId = ASTERISK;
            context.putColour(FIELD_ACCOUNT_ID, COLOUR_RED);
        }
        // :3186-3192 when the filter itself is the problem, none of the 39 field markers is applied
        if (context.changeAction == ChangeAction.DETAILS_NOT_FETCHED
                || context.accountFilterState == FieldState.BLANK
                || context.accountFilterState == FieldState.NOT_OK) {
            return;
        }
        // :3208-3435 the thirty-nine COPY CSSETATY REPLACING expansions, in source order
        applyFieldErrorAttribute(context, FIELD_ACCOUNT_STATUS, context.accountStatusState);
        applyFieldErrorAttribute(context, FIELD_OPEN_DATE_YEAR, context.openYearState);
        applyFieldErrorAttribute(context, FIELD_OPEN_DATE_MONTH, context.openMonthState);
        applyFieldErrorAttribute(context, FIELD_OPEN_DATE_DAY, context.openDayState);
        applyFieldErrorAttribute(context, FIELD_CREDIT_LIMIT, context.creditLimitState);
        applyFieldErrorAttribute(context, FIELD_EXPIRY_DATE_YEAR, context.expiryYearState);
        applyFieldErrorAttribute(context, FIELD_EXPIRY_DATE_MONTH, context.expiryMonthState);
        applyFieldErrorAttribute(context, FIELD_EXPIRY_DATE_DAY, context.expiryDayState);
        applyFieldErrorAttribute(context, FIELD_CASH_CREDIT_LIMIT, context.cashCreditLimitState);
        applyFieldErrorAttribute(context, FIELD_REISSUE_DATE_YEAR, context.reissueYearState);
        applyFieldErrorAttribute(context, FIELD_REISSUE_DATE_MONTH, context.reissueMonthState);
        applyFieldErrorAttribute(context, FIELD_REISSUE_DATE_DAY, context.reissueDayState);
        applyFieldErrorAttribute(context, FIELD_CURRENT_BALANCE, context.currentBalanceState);
        applyFieldErrorAttribute(context, FIELD_CURRENT_CYCLE_CREDIT,
                context.currentCycleCreditState);
        applyFieldErrorAttribute(context, FIELD_CURRENT_CYCLE_DEBIT, context.currentCycleDebitState);
        applyFieldErrorAttribute(context, FIELD_SSN_PART1, context.ssnPart1State);
        applyFieldErrorAttribute(context, FIELD_SSN_PART2, context.ssnPart2State);
        applyFieldErrorAttribute(context, FIELD_SSN_PART3, context.ssnPart3State);
        applyFieldErrorAttribute(context, FIELD_DATE_OF_BIRTH_YEAR, context.dateOfBirthYearState);
        applyFieldErrorAttribute(context, FIELD_DATE_OF_BIRTH_MONTH, context.dateOfBirthMonthState);
        applyFieldErrorAttribute(context, FIELD_DATE_OF_BIRTH_DAY, context.dateOfBirthDayState);
        applyFieldErrorAttribute(context, FIELD_FICO_SCORE, context.ficoScoreState);
        applyFieldErrorAttribute(context, FIELD_FIRST_NAME, context.firstNameState);
        applyFieldErrorAttribute(context, FIELD_MIDDLE_NAME, context.middleNameState);
        applyFieldErrorAttribute(context, FIELD_LAST_NAME, context.lastNameState);
        applyFieldErrorAttribute(context, FIELD_ADDRESS_LINE_1, context.addressLine1State);
        applyFieldErrorAttribute(context, FIELD_STATE_CODE, context.stateState);
        // :3371-3373 address line two is in the attribute list although no edit ever sets its state
        applyFieldErrorAttribute(context, FIELD_ADDRESS_LINE_2, context.addressLine2State);
        applyFieldErrorAttribute(context, FIELD_ZIP, context.zipState);
        applyFieldErrorAttribute(context, FIELD_CITY, context.cityState);
        applyFieldErrorAttribute(context, FIELD_COUNTRY_CODE, context.countryState);
        applyFieldErrorAttribute(context, FIELD_PHONE_1_AREA_CODE, context.phone1AreaCodeState);
        applyFieldErrorAttribute(context, FIELD_PHONE_1_PREFIX, context.phone1PrefixState);
        applyFieldErrorAttribute(context, FIELD_PHONE_1_LINE_NUMBER, context.phone1LineNumberState);
        applyFieldErrorAttribute(context, FIELD_PHONE_2_AREA_CODE, context.phone2AreaCodeState);
        applyFieldErrorAttribute(context, FIELD_PHONE_2_PREFIX, context.phone2PrefixState);
        applyFieldErrorAttribute(context, FIELD_PHONE_2_LINE_NUMBER, context.phone2LineNumberState);
        applyFieldErrorAttribute(context, FIELD_PRIMARY_CARD_HOLDER, context.primaryCardHolderState);
        applyFieldErrorAttribute(context, FIELD_EFT_ACCOUNT_ID, context.eftAccountIdState);
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, the cursor-placement {@code EVALUATE TRUE} at logical lines
     * {@code :3009-3167}, nested inside {@code 3300-SETUP-SCREEN-ATTRS}. An inline construct, not a
     * paragraph, so it consumes none of the eighty-seven label methods; it is factored out because a
     * forty-four-branch first-match cascade is unreadable inline and because its order is load-bearing.
     * <p>First match wins, so the cascade must be evaluated top to bottom exactly as written. The
     * opening two branches at {@code :3010-3012} test the information and error messages rather than any
     * tri-state, and both place the cursor on the account-status field - which means that on a freshly
     * displayed screen the cursor sits on the first enterable field rather than on any errored one.</p>
     * <p>{@code :3110-3111}, the middle name, is the only branch in the cascade with a single condition
     * instead of a {@code -NOT-OK}/{@code -BLANK} pair. That is not an omission in this translation: it is
     * the source, and it follows from {@code 1235-EDIT-ALPHA-OPT} never producing a {@code BLANK}
     * state.</p>
     * <p>Note also which fields are <strong>absent</strong> from the cascade: the account group
     * identifier, the customer identifier, address line two and the government-issued identifier. None
     * can ever receive the cursor, because none is ever edited.</p>
     * @param context the per-invocation state carrier
     * @return the name of the field that receives the cursor; never {@code null}
     */
    private String resolveCursorField3009(final UpdateContext context) {
        // :3010-3012 the two message-literal branches come FIRST
        if (INFO_FOUND_ACCOUNT_DATA.equals(context.informationMessage)
                || NO_CHANGES_DETECTED_MESSAGE.equals(context.returnMessage)) {
            return FIELD_ACCOUNT_STATUS;
        }
        // :3013-3015 the account filter itself
        if (isErrored(context.accountFilterState)) {
            return FIELD_ACCOUNT_ID;
        }
        // :3017-3164, in source order
        if (isErrored(context.accountStatusState)) {
            return FIELD_ACCOUNT_STATUS;
        }
        if (isErrored(context.openYearState)) {
            return FIELD_OPEN_DATE_YEAR;
        }
        if (isErrored(context.openMonthState)) {
            return FIELD_OPEN_DATE_MONTH;
        }
        if (isErrored(context.openDayState)) {
            return FIELD_OPEN_DATE_DAY;
        }
        if (isErrored(context.creditLimitState)) {
            return FIELD_CREDIT_LIMIT;
        }
        if (isErrored(context.expiryYearState)) {
            return FIELD_EXPIRY_DATE_YEAR;
        }
        if (isErrored(context.expiryMonthState)) {
            return FIELD_EXPIRY_DATE_MONTH;
        }
        if (isErrored(context.expiryDayState)) {
            return FIELD_EXPIRY_DATE_DAY;
        }
        if (isErrored(context.cashCreditLimitState)) {
            return FIELD_CASH_CREDIT_LIMIT;
        }
        if (isErrored(context.reissueYearState)) {
            return FIELD_REISSUE_DATE_YEAR;
        }
        if (isErrored(context.reissueMonthState)) {
            return FIELD_REISSUE_DATE_MONTH;
        }
        if (isErrored(context.reissueDayState)) {
            return FIELD_REISSUE_DATE_DAY;
        }
        if (isErrored(context.currentBalanceState)) {
            return FIELD_CURRENT_BALANCE;
        }
        if (isErrored(context.currentCycleCreditState)) {
            return FIELD_CURRENT_CYCLE_CREDIT;
        }
        if (isErrored(context.currentCycleDebitState)) {
            return FIELD_CURRENT_CYCLE_DEBIT;
        }
        if (isErrored(context.ssnPart1State)) {
            return FIELD_SSN_PART1;
        }
        if (isErrored(context.ssnPart2State)) {
            return FIELD_SSN_PART2;
        }
        if (isErrored(context.ssnPart3State)) {
            return FIELD_SSN_PART3;
        }
        if (isErrored(context.dateOfBirthYearState)) {
            return FIELD_DATE_OF_BIRTH_YEAR;
        }
        if (isErrored(context.dateOfBirthMonthState)) {
            return FIELD_DATE_OF_BIRTH_MONTH;
        }
        if (isErrored(context.dateOfBirthDayState)) {
            return FIELD_DATE_OF_BIRTH_DAY;
        }
        if (isErrored(context.ficoScoreState)) {
            return FIELD_FICO_SCORE;
        }
        if (isErrored(context.firstNameState)) {
            return FIELD_FIRST_NAME;
        }
        // :3110-3111 the middle name is the ONLY branch with no -BLANK companion
        if (context.middleNameState == FieldState.NOT_OK) {
            return FIELD_MIDDLE_NAME;
        }
        if (isErrored(context.lastNameState)) {
            return FIELD_LAST_NAME;
        }
        if (isErrored(context.addressLine1State)) {
            return FIELD_ADDRESS_LINE_1;
        }
        if (isErrored(context.stateState)) {
            return FIELD_STATE_CODE;
        }
        if (isErrored(context.zipState)) {
            return FIELD_ZIP;
        }
        if (isErrored(context.cityState)) {
            return FIELD_CITY;
        }
        if (isErrored(context.countryState)) {
            return FIELD_COUNTRY_CODE;
        }
        if (isErrored(context.phone1AreaCodeState)) {
            return FIELD_PHONE_1_AREA_CODE;
        }
        if (isErrored(context.phone1PrefixState)) {
            return FIELD_PHONE_1_PREFIX;
        }
        if (isErrored(context.phone1LineNumberState)) {
            return FIELD_PHONE_1_LINE_NUMBER;
        }
        if (isErrored(context.phone2AreaCodeState)) {
            return FIELD_PHONE_2_AREA_CODE;
        }
        if (isErrored(context.phone2PrefixState)) {
            return FIELD_PHONE_2_PREFIX;
        }
        if (isErrored(context.phone2LineNumberState)) {
            return FIELD_PHONE_2_LINE_NUMBER;
        }
        if (isErrored(context.eftAccountIdState)) {
            return FIELD_EFT_ACCOUNT_ID;
        }
        if (isErrored(context.primaryCardHolderState)) {
            return FIELD_PRIMARY_CARD_HOLDER;
        }
        // :3165-3166 WHEN OTHER
        return FIELD_ACCOUNT_ID;
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 3300-SETUP-SCREEN-ATTRS-EXIT.}, logical lines
     * {@code :3437-3439}. A bare {@code EXIT} and the target of the early {@code GO TO} at
     * {@code :3189}; a tracked, cited no-op.
     */
    private void setupScreenAttributes3300Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :3437-3439.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 3310-PROTECT-ALL-ATTRS.}, logical lines
     * {@code :3441-3495}. One {@code MOVE DFHBMPRF} to forty-four attribute bytes: every enterable field
     * on the screen plus the information-message field.
     * <p>{@code DFHBMPRF} is protected-with-modified-data-tag-set, the CICS standard attribute supplied by
     * {@code COPY DFHBMSCA} at {@code :615}. Because that copybook comes from the transaction monitor
     * rather than from this repository, it gets no Java import; the attribute values are carried as named
     * string constants and are surfaced to the caller as {@link FieldAttribute} records, leaving the HTTP
     * representation to the controller.</p>
     * <p>The account filter is included in the protect list at {@code :3442}, which is why every branch of
     * {@code 3300-SETUP-SCREEN-ATTRS}'s first decider has to re-enable it explicitly.</p>
     * @param context the per-invocation state carrier
     */
    private void protectAllAttributes3310(final UpdateContext context) {
        // :3442-3494 MOVE DFHBMPRF TO the forty-four attribute bytes, in source order
        for (final String field : ALL_PROTECTABLE_FIELDS) {
            context.putAttribute(field, ATTRIBUTE_PROTECTED_FSET);
        }
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 3310-PROTECT-ALL-ATTRS-EXIT.}, logical lines
     * {@code :3496-3498}. A bare {@code EXIT}; a tracked, cited no-op.
     */
    private void protectAllAttributes3310Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :3496-3498.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 3320-UNPROTECT-FEW-ATTRS.}, logical lines
     * {@code :3500-3561}. Re-enables the fields the operator may change, and deliberately leaves four
     * protected.
     * <p>The paragraph is <strong>not</strong> a blanket unprotect: it interleaves six separate
     * {@code MOVE} statements so that four fields stay at {@code DFHBMPRF} while everything around them
     * becomes enterable. Those four are the <strong>customer identifier</strong> at {@code :3531}, the
     * <strong>country code</strong> at {@code :3547}, the <strong>information message</strong> at
     * {@code :3560}, and - by simple omission from every {@code MOVE DFHBMFSE} list - the
     * <strong>account filter</strong>, which stays protected once details are on display so the operator
     * cannot switch accounts mid-edit.</p>
     * <p>One further omission worth naming: {@code ACSGOVTA} is unprotected at {@code :3557}, so the
     * government-issued identifier is enterable even though no edit routine ever validates it. That is the
     * mechanism by which defect D10 - unvalidated fields reaching storage - becomes reachable from the
     * screen.</p>
     * @param context the per-invocation state carrier
     */
    private void unprotectFewAttributes3320(final UpdateContext context) {
        // :3502-3529 and :3532-3545 and :3549-3559 MOVE DFHBMFSE to the enterable fields
        for (final String field : UNPROTECTABLE_FIELDS) {
            context.putAttribute(field, ATTRIBUTE_UNPROTECTED_FSET);
        }
        // :3531 the customer identifier stays protected
        context.putAttribute(FIELD_CUSTOMER_ID, ATTRIBUTE_PROTECTED_FSET);
        // :3547 the country code stays protected
        context.putAttribute(FIELD_COUNTRY_CODE, ATTRIBUTE_PROTECTED_FSET);
        // :3560 the information message stays protected
        context.putAttribute(FIELD_INFORMATION_MESSAGE, ATTRIBUTE_PROTECTED_FSET);
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 3320-UNPROTECT-FEW-ATTRS-EXIT.}, logical lines
     * {@code :3562-3564}. A bare {@code EXIT}; a tracked, cited no-op.
     */
    private void unprotectFewAttributes3320Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :3562-3564.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 3390-SETUP-INFOMSG-ATTRS.}, logical lines
     * {@code :3566-3583}. Sets the highlight on the information-message field and on the two function-key
     * legends.
     * <p>{@code :3567-3571} darkens the information-message field when there is no message and brightens
     * it when there is - {@code DFHBMDAR} against {@code DFHBMASB}. {@code :3573-3576} brightens the PF12
     * legend whenever changes have been made but not yet completed, so the operator can see that a cancel
     * is available. {@code :3578-3581} brightens <strong>both</strong> the PF05 and PF12 legends when the
     * screen is prompting for confirmation, which is the only state in which PF05 is a valid key - see the
     * four-condition valid-key test at {@code :906-912}.</p>
     * <p>The two conditions are independent {@code IF} statements rather than a decider, so on a
     * prompting-for-confirmation screen the PF12 legend is brightened twice. The second assignment is
     * idempotent and is preserved as written.</p>
     * @param context the per-invocation state carrier
     */
    private void setupInfoMessageAttributes3390(final UpdateContext context) {
        // :3567-3571 dark when there is no message, bright when there is
        if (isBlankOrLowValues(context.informationMessage)) {
            context.putAttribute(FIELD_INFORMATION_MESSAGE, ATTRIBUTE_DARK);
        } else {
            context.putAttribute(FIELD_INFORMATION_MESSAGE, ATTRIBUTE_BRIGHT);
        }
        // :3573-3576 changes made but not yet completed: PF12 (cancel) is available
        if (context.changeAction.isChangesMade()
                && context.changeAction != ChangeAction.CHANGES_OKAYED_AND_DONE) {
            context.putAttribute(FIELD_FUNCTION_KEY_12, ATTRIBUTE_BRIGHT);
        }
        // :3578-3581 prompting for confirmation: both PF05 and PF12 are available
        if (INFO_PROMPT_FOR_CONFIRMATION.equals(context.informationMessage)) {
            context.putAttribute(FIELD_FUNCTION_KEY_05, ATTRIBUTE_BRIGHT);
            context.putAttribute(FIELD_FUNCTION_KEY_12, ATTRIBUTE_BRIGHT);
        }
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 3390-SETUP-INFOMSG-ATTRS-EXIT.}, logical lines
     * {@code :3584-3586}. A bare {@code EXIT}; a tracked, cited no-op.
     */
    private void setupInfoMessageAttributes3390Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :3584-3586.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 3400-SEND-SCREEN.}, logical lines
     * {@code :3589-3602}. Records the next mapset and map, then issues
     * {@code EXEC CICS SEND MAP ... CURSOR ERASE FREEKB}.
     * <p>The terminal write itself has no Java counterpart - the response body <em>is</em> the screen -
     * so what this method preserves is the two assignments at {@code :3591-3592} that tell the next turn
     * which map it is reading, and the recording of the cursor position that {@code CURSOR} would have
     * honoured. {@code ERASE} and {@code FREEKB} are terminal-hardware concerns with no stateless
     * meaning.</p>
     * <p>Note that {@code :3600} captures {@code RESP} but the source never tests it: a failed
     * {@code SEND} is silently ignored. That absent guard is preserved - no exception is raised here -
     * and is recorded in this class's defect register.</p>
     * @param context the per-invocation state carrier
     */
    private void sendScreen3400(final UpdateContext context) {
        // :3591-3592 tell the next turn which map it is reading
        context.nextMapset = THIS_MAPSET;
        context.nextMap = THIS_MAP;
        // :3594-3601 EXEC CICS SEND MAP ... CURSOR ERASE FREEKB. RESP is captured and never tested.
        if (context.cursorField != null) {
            context.putCursor(context.cursorField);
        }
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 3400-SEND-SCREEN-EXIT.}, logical lines
     * {@code :3603-3605}. A bare {@code EXIT}; a tracked, cited no-op.
     */
    private void sendScreen3400Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :3603-3605.
    }


    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 9000-READ-ACCT.}, logical lines
     * {@code :3608-3644}. The three-step retrieval chain: cross-reference by account, then the account
     * master, then the customer master, then snapshot what was read.
     * <p>{@code :3610} discards any previous snapshot and {@code :3612} clears the information message.
     * {@code :3614-3615} moves the filter into <em>both</em> the snapshot's account identifier and the
     * read key, so the snapshot carries the requested identifier even when nothing is found.</p>
     * <p><strong>Three guards, of which only the first can ever fire.</strong> {@code :3620-3622} tests
     * {@code FLG-ACCTFILTER-NOT-OK}, which {@code 9200-GETCARDXREF-BYACCT} really does set, so a missing
     * cross-reference genuinely stops the chain. But {@code :3627-3629} and {@code :3636-3638} test
     * {@code DID-NOT-FIND-ACCT-IN-ACCTDAT} and {@code DID-NOT-FIND-CUST-IN-CUSTDAT}, and those are
     * message literals on {@code WS-RETURN-MSG} whose only {@code SET} statements are
     * <strong>commented out</strong>, at {@code :3719} and {@code :3769} respectively. Both guards are
     * therefore permanently false.</p>
     * <p>The consequence is behavioural, not cosmetic: when the account master read misses, the chain does
     * <em>not</em> stop. It proceeds to read the customer and then to {@code 9500-STORE-FETCHED-DATA},
     * which snapshots whatever the record areas happen to hold. Preserved exactly; see the defect register
     * in this class's documentation for the severity classification and remediation.</p>
     * @param context the per-invocation state carrier
     */
    private void readAccount9000(final UpdateContext context) {
        // :3610 INITIALIZE ACUP-OLD-DETAILS
        context.clearSnapshot();
        context.readAccount = null;
        context.readCustomer = null;
        context.readCrossReference = null;
        // :3612 SET WS-NO-INFO-MESSAGE TO TRUE
        context.informationMessage = null;
        // :3614-3615 the filter becomes both the snapshot identifier and the read key
        context.snapshotAccountId = context.accountFilter;
        context.readKeyAccountId = context.accountFilter;
        // :3617-3618
        getCardXrefByAccount9200(context);
        getCardXrefByAccount9200Exit();
        // :3620-3622 the only guard in this paragraph that can fire
        if (context.accountFilterState == FieldState.NOT_OK) {
            return;
        }
        // :3624-3625
        getAccountDataByAccount9300(context);
        getAccountDataByAccount9300Exit();
        // :3627-3629 DEAD GUARD - the literal is never assigned; its SET is commented out at :3719.
        if (DID_NOT_FIND_ACCOUNT_IN_ACCTDAT.equals(context.returnMessage)) {
            return;
        }
        // :3631
        context.readKeyCustomerId = context.commAreaCustomerId;
        // :3633-3634
        getCustomerDataByCustomer9400(context);
        getCustomerDataByCustomer9400Exit();
        // :3636-3638 DEAD GUARD - the literal is never assigned; its SET is commented out at :3769.
        if (DID_NOT_FIND_CUSTOMER_IN_CUSTDAT.equals(context.returnMessage)) {
            return;
        }
        // :3642-3643
        storeFetchedData9500(context);
        storeFetchedData9500Exit();
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 9000-READ-ACCT-EXIT.}, logical lines
     * {@code :3647-3649}. A bare {@code EXIT} and the target of the three {@code GO TO} statements above;
     * a tracked, cited no-op.
     */
    private void readAccount9000Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :3647-3649.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 9200-GETCARDXREF-BYACCT.}, logical lines
     * {@code :3650-3697}. Reads the card cross-reference by account identifier through the alternate-index
     * path {@code CXACAIX}, and carries the customer identifier and card number it yields into the
     * conversation context.
     * <p>{@code :3654-3662} is an {@code EXEC CICS READ} against the <em>path</em> rather than the base
     * cluster. Per {@code app/catlg/LISTCAT.txt:L254-L270} the cross-reference alternate key is
     * non-unique, so the read returns the first record in key order - which is why the Java equivalent is
     * a repository finder ordered ascending by card number, with the first element taken.</p>
     * <p>{@code :3664-3696} is a three-way {@code EVALUATE} on {@code RESP}. {@code NORMAL} carries the
     * customer identifier and card number forward. {@code NOTFND} at {@code :3668-3685} sets the input
     * error and the filter tri-state, then latches a diagnostic <strong>only</strong> when no message is
     * already pending - the first-error-wins rule. {@code WHEN OTHER} at {@code :3686-3693} assembles
     * {@code WS-FILE-ERROR-MESSAGE} and, note, does <strong>not</strong> apply the latch: an I/O error
     * overwrites whatever message was there.</p>
     * @param context the per-invocation state carrier
     */
    private void getCardXrefByAccount9200(final UpdateContext context) {
        final Long accountKey = parseKey(context.readKeyAccountId);
        int responseCode;
        String ioStatus;
        Throwable cause = null;
        // LIMIT 1 at the database: a keyed read through the CXACAIX path yields one record, and only the
        // first was ever used below. See CardCrossReferenceRepository for the full reasoning.
        Optional<CardCrossReference> found = Optional.empty();
        if (accountKey == null) {
            // A non-numeric key cannot address the path; CICS reports it as a miss.
            responseCode = CICS_RESP_NOTFND;
            ioStatus = IO_STATUS_RECORD_NOT_FOUND;
        } else {
            try {
                found = this.cardCrossReferenceRepository
                        .findFirstByAccountIdOrderByCardNumberAsc(accountKey);
                if (found.isEmpty()) {
                    responseCode = CICS_RESP_NOTFND;
                    ioStatus = IO_STATUS_RECORD_NOT_FOUND;
                } else {
                    responseCode = CICS_RESP_NORMAL;
                    ioStatus = IO_STATUS_SUCCESS;
                }
            } catch (final DataAccessException failure) {
                responseCode = CICS_RESP_IOERR;
                ioStatus = IO_STATUS_IO_ERROR;
                cause = failure;
            }
        }
        context.responseCode = responseCode;
        context.reasonCode = CICS_REASON_NONE;
        // :3665-3667 WHEN DFHRESP(NORMAL)
        if (responseCode == CICS_RESP_NORMAL) {
            final CardCrossReference crossReference = found.orElseThrow();
            context.readCrossReference = crossReference;
            context.commAreaCustomerId = formatNumericKey(crossReference.getCustomerId(),
                    CUSTOMER_ID_LENGTH);
            context.commAreaCardNumber = crossReference.getCardNumber();
            return;
        }
        // :3668-3685 WHEN DFHRESP(NOTFND)
        if (responseCode == CICS_RESP_NOTFND) {
            context.inputError = true;
            context.accountFilterState = FieldState.NOT_OK;
            // :3671-3685 the diagnostic is latched: first error wins
            if (isReturnMessageOff(context)) {
                context.returnMessage = truncateReturnMessage(MESSAGE_ACCOUNT_PREFIX
                        + padRight(context.readKeyAccountId, ACCOUNT_ID_LENGTH)
                        + MESSAGE_NOT_FOUND_IN
                        + MESSAGE_XREF_FILE
                        + renderResponseCode(responseCode)
                        + MESSAGE_REASON
                        + renderResponseCode(CICS_REASON_NONE));
            }
            retainFailure(context, classify(context, ioStatus, XREF_ACCOUNT_PATH_NAME,
                    OPERATION_READ, cause));
            return;
        }
        // :3686-3693 WHEN OTHER - no latch, so this diagnostic overwrites any pending message
        context.inputError = true;
        context.accountFilterState = FieldState.NOT_OK;
        context.returnMessage = fileErrorMessage(OPERATION_READ, XREF_ACCOUNT_PATH_NAME,
                responseCode, CICS_REASON_NONE);
        retainFailure(context, classify(context, ioStatus, XREF_ACCOUNT_PATH_NAME,
                OPERATION_READ, cause));
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 9200-GETCARDXREF-BYACCT-EXIT.}, logical lines
     * {@code :3698-3700}. A bare {@code EXIT}; a tracked, cited no-op.
     */
    private void getCardXrefByAccount9200Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :3698-3700.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 9300-GETACCTDATA-BYACCT.}, logical lines
     * {@code :3701-3747}. Reads the account master by its eleven-digit key.
     * <p>{@code :3714-3715} sets {@code FOUND-ACCT-IN-MASTER} - value {@code '1'}, declared at
     * {@code :386} - on success. {@code :3716-3734} handles a miss with the same latched-diagnostic shape
     * as {@code 9200}, and {@code :3736-3743} handles any other condition without the latch.</p>
     * <p><strong>Note the commented-out statement at {@code :3719}.</strong> Immediately before the latch,
     * the source once set {@code DID-NOT-FIND-ACCT-IN-ACCTDAT}; that line is now a comment. Its absence is
     * what makes the caller's guard at {@code :3627} dead, and it is why a missing account does not stop
     * the retrieval chain. The comment is preserved as evidence rather than reinstated - reinstating it
     * would change behaviour.</p>
     * @param context the per-invocation state carrier
     */
    private void getAccountDataByAccount9300(final UpdateContext context) {
        final Long accountKey = parseKey(context.readKeyAccountId);
        int responseCode;
        String ioStatus;
        Throwable cause = null;
        Account account = null;
        if (accountKey == null) {
            responseCode = CICS_RESP_NOTFND;
            ioStatus = IO_STATUS_RECORD_NOT_FOUND;
        } else {
            try {
                final Optional<Account> found = this.accountRepository.findById(accountKey);
                if (found.isEmpty()) {
                    responseCode = CICS_RESP_NOTFND;
                    ioStatus = IO_STATUS_RECORD_NOT_FOUND;
                } else {
                    account = found.get();
                    responseCode = CICS_RESP_NORMAL;
                    ioStatus = IO_STATUS_SUCCESS;
                }
            } catch (final DataAccessException failure) {
                responseCode = CICS_RESP_IOERR;
                ioStatus = IO_STATUS_IO_ERROR;
                cause = failure;
            }
        }
        context.responseCode = responseCode;
        context.reasonCode = CICS_REASON_NONE;
        // :3714-3715 WHEN DFHRESP(NORMAL)
        if (responseCode == CICS_RESP_NORMAL) {
            context.readAccount = account;
            context.foundAccountInMaster = true;
            return;
        }
        // :3716-3734 WHEN DFHRESP(NOTFND). :3719 SET DID-NOT-FIND-ACCT-IN-ACCTDAT is COMMENTED OUT
        // in the source; it is deliberately not reinstated, which is what leaves :3627 dead.
        if (responseCode == CICS_RESP_NOTFND) {
            context.inputError = true;
            context.accountFilterState = FieldState.NOT_OK;
            if (isReturnMessageOff(context)) {
                context.returnMessage = truncateReturnMessage(MESSAGE_ACCOUNT_PREFIX
                        + padRight(context.readKeyAccountId, ACCOUNT_ID_LENGTH)
                        + MESSAGE_NOT_FOUND_IN
                        + MESSAGE_ACCOUNT_MASTER_FILE
                        + renderResponseCode(responseCode)
                        + MESSAGE_REASON
                        + renderResponseCode(CICS_REASON_NONE));
            }
            retainFailure(context, classify(context, ioStatus, ACCOUNT_FILE_NAME,
                    OPERATION_READ, cause));
            return;
        }
        // :3736-3743 WHEN OTHER - unlatched
        context.inputError = true;
        context.accountFilterState = FieldState.NOT_OK;
        context.returnMessage = fileErrorMessage(OPERATION_READ, ACCOUNT_FILE_NAME,
                responseCode, CICS_REASON_NONE);
        retainFailure(context, classify(context, ioStatus, ACCOUNT_FILE_NAME,
                OPERATION_READ, cause));
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 9300-GETACCTDATA-BYACCT-EXIT.}, logical lines
     * {@code :3748-3750}. A bare {@code EXIT}; a tracked, cited no-op.
     */
    private void getAccountDataByAccount9300Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :3748-3750.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 9400-GETCUSTDATA-BYCUST.}, logical lines
     * {@code :3752-3796}. Reads the customer master by its nine-digit key.
     * <p>Structurally the twin of {@code 9300}, with three differences worth naming. It sets
     * {@code FLG-CUSTFILTER-NOT-OK} rather than the account filter's tri-state, so a missing customer does
     * not redden the account field. Its {@code SET DID-NOT-FIND-CUST-IN-CUSTDAT} at {@code :3769} is
     * likewise commented out, leaving the caller's guard at {@code :3636} dead. And its
     * {@code MOVE ... TO ERROR-RESP} pair sits at {@code :3770-3771} <strong>outside</strong> the
     * {@code IF WS-RETURN-MSG-OFF} latch, whereas {@code 9200} and {@code 9300} place the equivalent moves
     * inside it. That reordering is inert - the values are only read inside the latch - and is preserved as
     * written.</p>
     * <p>Note also the message text differences that the parity comparison depends on:
     * {@code ' in customer master.Resp: '} carries a trailing space, and the reason label is
     * {@code ' REAS:'} in upper case here against {@code ' Reas:'} in the other two paragraphs.</p>
     * @param context the per-invocation state carrier
     */
    private void getCustomerDataByCustomer9400(final UpdateContext context) {
        final Long customerKey = parseKey(context.readKeyCustomerId);
        int responseCode;
        String ioStatus;
        Throwable cause = null;
        Customer customer = null;
        if (customerKey == null) {
            responseCode = CICS_RESP_NOTFND;
            ioStatus = IO_STATUS_RECORD_NOT_FOUND;
        } else {
            try {
                final Optional<Customer> found = this.customerRepository.findById(customerKey);
                if (found.isEmpty()) {
                    responseCode = CICS_RESP_NOTFND;
                    ioStatus = IO_STATUS_RECORD_NOT_FOUND;
                } else {
                    customer = found.get();
                    responseCode = CICS_RESP_NORMAL;
                    ioStatus = IO_STATUS_SUCCESS;
                }
            } catch (final DataAccessException failure) {
                responseCode = CICS_RESP_IOERR;
                ioStatus = IO_STATUS_IO_ERROR;
                cause = failure;
            }
        }
        context.responseCode = responseCode;
        context.reasonCode = CICS_REASON_NONE;
        // :3764-3765 WHEN DFHRESP(NORMAL)
        if (responseCode == CICS_RESP_NORMAL) {
            context.readCustomer = customer;
            context.foundCustomerInMaster = true;
            return;
        }
        // :3766-3784 WHEN DFHRESP(NOTFND). :3769 SET DID-NOT-FIND-CUST-IN-CUSTDAT is COMMENTED OUT,
        // which is what leaves :3636 dead. :3770-3771 sit OUTSIDE the latch, unlike 9200 and 9300.
        if (responseCode == CICS_RESP_NOTFND) {
            context.inputError = true;
            context.customerFilterState = FieldState.NOT_OK;
            if (isReturnMessageOff(context)) {
                context.returnMessage = truncateReturnMessage(MESSAGE_CUSTOMER_PREFIX
                        + padRight(context.readKeyCustomerId, CUSTOMER_ID_LENGTH)
                        + MESSAGE_NOT_FOUND
                        + MESSAGE_CUSTOMER_MASTER
                        + renderResponseCode(responseCode)
                        + MESSAGE_REASON_UPPER
                        + renderResponseCode(CICS_REASON_NONE));
            }
            retainFailure(context, classify(context, ioStatus, CUSTOMER_FILE_NAME,
                    OPERATION_READ, cause));
            return;
        }
        // :3785-3792 WHEN OTHER - unlatched
        context.inputError = true;
        context.customerFilterState = FieldState.NOT_OK;
        context.returnMessage = fileErrorMessage(OPERATION_READ, CUSTOMER_FILE_NAME,
                responseCode, CICS_REASON_NONE);
        retainFailure(context, classify(context, ioStatus, CUSTOMER_FILE_NAME,
                OPERATION_READ, cause));
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 9400-GETCUSTDATA-BYCUST-EXIT.}, logical lines
     * {@code :3797-3799}. A bare {@code EXIT}; a tracked, cited no-op.
     */
    private void getCustomerDataByCustomer9400Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :3797-3799.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 9500-STORE-FETCHED-DATA.}, logical lines
     * {@code :3801-3884}. Builds the {@code ACUP-OLD-DETAILS} snapshot that
     * {@code 9700-CHECK-CHANGE-IN-REC} will later compare against, and copies conversation context into
     * the COMMAREA.
     * <p>{@code :3805-3811} stores the account identifier, customer identifier, the three customer names,
     * the account status and the card number. In a stateless target those become response metadata rather
     * than retained state.</p>
     * <p><strong>The decisive detail: every whole-date {@code MOVE} in this paragraph is commented out and
     * replaced by three component moves.</strong> {@code :3831} is a commented
     * {@code MOVE ACCT-OPEN-DATE TO ACUP-OLD-OPEN-DATE}, superseded by {@code :3832-3834} moving
     * {@code (1:4)}, {@code (6:2)} and {@code (9:2)} into {@code ACUP-OLD-OPEN-YEAR}, {@code -MON} and
     * {@code -DAY}. The same pattern appears at {@code :3836}/{@code :3837-3839} for the expiry date - the
     * snapshot field name carrying the copybook's own {@code EXPIRAION} misspelling - at
     * {@code :3842}/{@code :3843-3845} for the reissue date, and at
     * {@code :3856}/{@code :3857-3859} for the date of birth.</p>
     * <p>That last one proves the snapshot's date-of-birth format. The three subfields
     * {@code ACUP-OLD-CUST-DOB-YEAR}, {@code -MON} and {@code -DAY} are contiguous inside the group
     * {@code ACUP-OLD-CUST-DOB-YYYY-MM-DD}, so four plus two plus two is <strong>eight bytes with no
     * separators</strong> - which is exactly why {@code 9700} addresses that group at {@code (1:4)},
     * {@code (5:2)} and {@code (7:2)} while addressing the live ten-character field at {@code (1:4)},
     * {@code (6:2)} and {@code (9:2)}. The snapshot is therefore stored in compact form here; see
     * {@link #checkChangeInRecord9700} for the comparison that depends on it.</p>
     * @param context the per-invocation state carrier
     */
    private void storeFetchedData9500(final UpdateContext context) {
        final Account account = context.readAccount;
        final Customer customer = context.readCustomer;
        // :3805-3811 store conversation context
        if (account != null) {
            context.commAreaAccountId = formatNumericKey(account.getAccountId(), ACCOUNT_ID_LENGTH);
            context.commAreaAccountStatus = account.getActiveStatus();
        }
        if (customer != null) {
            context.commAreaCustomerId = formatNumericKey(customer.getCustomerId(),
                    CUSTOMER_ID_LENGTH);
            context.commAreaCustomerFirstName = customer.getFirstName();
            context.commAreaCustomerMiddleName = customer.getMiddleName();
            context.commAreaCustomerLastName = customer.getLastName();
        }
        if (context.readCrossReference != null) {
            context.commAreaCardNumber = context.readCrossReference.getCardNumber();
        }
        // :3813 INITIALIZE ACUP-OLD-DETAILS
        context.clearSnapshot();
        if (account != null) {
            // :3817-3847 the account block
            context.snapshotAccountId = formatNumericKey(account.getAccountId(), ACCOUNT_ID_LENGTH);
            context.snapshotActiveStatus = account.getActiveStatus();
            context.snapshotCurrentBalance = account.getCurrentBalance();
            context.snapshotCreditLimit = account.getCreditLimit();
            context.snapshotCashCreditLimit = account.getCashCreditLimit();
            context.snapshotCurrentCycleCredit = account.getCurrentCycleCredit();
            context.snapshotCurrentCycleDebit = account.getCurrentCycleDebit();
            // :3832-3834 the whole-date MOVE at :3831 is commented out; components are moved instead
            context.snapshotOpenYear = datePart(account.getOpenDate(), DATE_YEAR_OFFSET,
                    DATE_YEAR_LENGTH);
            context.snapshotOpenMonth = datePart(account.getOpenDate(), DATE_MONTH_OFFSET,
                    DATE_PART_LENGTH);
            context.snapshotOpenDay = datePart(account.getOpenDate(), DATE_DAY_OFFSET,
                    DATE_PART_LENGTH);
            // :3837-3839 the EXPIRAION misspelling is the copybook's own and is preserved
            context.snapshotExpiraionYear = datePart(account.getExpiraionDate(), DATE_YEAR_OFFSET,
                    DATE_YEAR_LENGTH);
            context.snapshotExpiraionMonth = datePart(account.getExpiraionDate(), DATE_MONTH_OFFSET,
                    DATE_PART_LENGTH);
            context.snapshotExpiraionDay = datePart(account.getExpiraionDate(), DATE_DAY_OFFSET,
                    DATE_PART_LENGTH);
            // :3843-3845
            context.snapshotReissueYear = datePart(account.getReissueDate(), DATE_YEAR_OFFSET,
                    DATE_YEAR_LENGTH);
            context.snapshotReissueMonth = datePart(account.getReissueDate(), DATE_MONTH_OFFSET,
                    DATE_PART_LENGTH);
            context.snapshotReissueDay = datePart(account.getReissueDate(), DATE_DAY_OFFSET,
                    DATE_PART_LENGTH);
            // :3847
            context.snapshotGroupId = account.getGroupId();
        }
        if (customer != null) {
            // :3852-3883 the customer block
            context.snapshotCustomerId = formatNumericKey(customer.getCustomerId(),
                    CUSTOMER_ID_LENGTH);
            context.snapshotSsn = customer.getSsn();
            // :3857-3859 stored COMPACT - four plus two plus two, no separators. See 9700.
            context.snapshotDateOfBirthYear = datePart(customer.getDateOfBirth(), DATE_YEAR_OFFSET,
                    DATE_YEAR_LENGTH);
            context.snapshotDateOfBirthMonth = datePart(customer.getDateOfBirth(),
                    DATE_MONTH_OFFSET, DATE_PART_LENGTH);
            context.snapshotDateOfBirthDay = datePart(customer.getDateOfBirth(), DATE_DAY_OFFSET,
                    DATE_PART_LENGTH);
            context.snapshotFicoScore = customer.getFicoCreditScore();
            context.snapshotFirstName = customer.getFirstName();
            context.snapshotMiddleName = customer.getMiddleName();
            context.snapshotLastName = customer.getLastName();
            context.snapshotAddressLine1 = customer.getAddressLine1();
            context.snapshotAddressLine2 = customer.getAddressLine2();
            context.snapshotAddressLine3 = customer.getAddressLine3();
            context.snapshotAddressStateCode = customer.getAddressStateCode();
            context.snapshotAddressCountryCode = customer.getAddressCountryCode();
            context.snapshotAddressZip = customer.getAddressZip();
            context.snapshotPhoneNumber1 = customer.getPhoneNumber1();
            context.snapshotPhoneNumber2 = customer.getPhoneNumber2();
            context.snapshotGovernmentIssuedId = customer.getGovernmentIssuedId();
            context.snapshotEftAccountId = customer.getEftAccountId();
            context.snapshotPrimaryCardHolderIndicator = customer.getPrimaryCardHolderIndicator();
        }
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 9500-STORE-FETCHED-DATA-EXIT.}, logical lines
     * {@code :3885-3887}. A bare {@code EXIT}; a tracked, cited no-op.
     */
    private void storeFetchedData9500Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :3885-3887.
    }


    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 9600-WRITE-PROCESSING.}, logical lines
     * {@code :3888-4104}. The seven-step dual-dataset write, and the single most important method in this
     * class.
     *
     * <h4>The seven steps, in the source's order</h4>
     * <ol>
     *   <li>{@code :3892-3903} read the account <strong>for update</strong>. The guard at
     *       {@code :3907-3915} sets the input error, then - inside an
     *       {@code IF WS-RETURN-MSG-OFF} latch at {@code :3911-3913} - the
     *       {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE} message, and leaves.</li>
     *   <li>{@code :3919-3930} read the customer <strong>for update</strong>. The guard at
     *       {@code :3934-3942} has the same shape but sets the <em>distinct</em>
     *       {@code COULD-NOT-LOCK-CUST-FOR-UPDATE} message at {@code :3939}, and leaves with
     *       <strong>nothing written</strong>.</li>
     *   <li>{@code :3947-3952} run the change detection and leave if the record moved underneath us.</li>
     *   <li>{@code :3956-4059} build the two update images field by field.</li>
     *   <li>{@code :4065-4071} rewrite the account. On failure {@code :4079-4080} set
     *       {@code LOCKED-BUT-UPDATE-FAILED} and leave - <strong>with no rollback</strong>.</li>
     *   <li>{@code :4085-4091} rewrite the customer. On failure {@code :4098-4102} set the same message,
     *       issue {@code EXEC CICS SYNCPOINT ROLLBACK}, and leave.</li>
     *   <li>{@code :4105} exit.</li>
     * </ol>
     *
     * <h4>Why the rollback asymmetry is correct, and why one transaction reproduces it</h4>
     * <p>At the account-rewrite failure point <strong>nothing has yet been written inside the unit of
     * work</strong>, so the transaction monitor releases the read-for-update locks at task end and no
     * explicit backout is needed. At the customer-rewrite failure point the account rewrite
     * <strong>has already occurred inside the same unit of work</strong>, so an explicit backout is the
     * only way to avoid a half-applied update.</p>
     * <p>A single {@code @Transactional(rollbackFor = Exception.class)} boundary on
     * {@link #updateAccount} therefore reproduces <strong>both</strong> branches automatically, because
     * every failure path returns or throws before the commit point. Nothing here is conditional: there is
     * no rollback on the account path, no selective {@code setRollbackOnly()}, and no conditional
     * transaction boundary. That is a <em>mechanism substitution</em>, not a behaviour change, and it is
     * recorded as such in the register in this class's documentation, citing {@code :4079-4080} and
     * {@code :4098-4102} - otherwise a reviewer comparing the two sources sees a
     * {@code SYNCPOINT ROLLBACK} with no Java counterpart and wrongly concludes something was lost.</p>
     * <p>Because both writes share one transaction, the legacy hazard of a half-applied update on the
     * account-rewrite path is closed as a side effect. That is a genuine improvement rather than parity and
     * is labelled as a deviation, not presented as equivalence.</p>
     *
     * <h4>Two details easy to lose</h4>
     * <p>Both lock-failure guards latch their message behind {@code IF WS-RETURN-MSG-OFF} but set their
     * outcome flag unconditionally, so a pending edit message survives while the outcome still changes.
     * The two flags are <strong>distinct</strong> and are kept distinct here - which matters entirely,
     * because {@link #classifyWriteOutcome2606} never tests the customer one.</p>
     * <p>{@code :3993} moves the live {@code ACCT-REISSUE-DATE} into the update image and
     * {@code :3994-4000} immediately overwrites it by assembling the submitted components. The
     * {@code MOVE} is inert; it is cited and skipped rather than reproduced, because reproducing an
     * assignment that is unconditionally overwritten on the next statement would add a write with no
     * observable effect.</p>
     *
     * @param context the per-invocation state carrier
     */
    private void writeProcessing9600(final UpdateContext context) {
        // :3892 MOVE CC-ACCT-ID TO WS-CARD-RID-ACCT-ID
        context.readKeyAccountId = context.accountFilter;
        final Long accountKey = parseKey(context.readKeyAccountId);
        // :3894-3903 EXEC CICS READ FILE(ACCTDAT) UPDATE
        Account account = null;
        Throwable accountReadCause = null;
        if (accountKey != null) {
            try {
                account = this.accountRepository.findByIdForUpdate(accountKey).orElse(null);
            } catch (final DataAccessException failure) {
                accountReadCause = failure;
            }
        }
        // :3907-3915 the account lock guard
        if (account == null) {
            context.inputError = true;
            // :3911-3913 the message is latched; the flag below is not
            if (isReturnMessageOff(context)) {
                context.returnMessage = COULD_NOT_LOCK_ACCOUNT_FOR_UPDATE;
            }
            // :3912 SET COULD-NOT-LOCK-ACCT-FOR-UPDATE - the outcome this method reports
            context.accountLockFailed = true;
            // The source cannot tell these two apart - any non-normal RESP on the READ UPDATE sets the
            // same flag - but the store can, and the two need different answers. An EMPTY result means the
            // row is not there, which stays RecordNotFoundException. A THROWN result whose SQLSTATE says
            // the lock could not be taken means the row IS there and is held by someone else, which is
            // what Outcome.COULD_NOT_LOCK_ACCOUNT and its 423 exist for. Reporting "not found" for a row
            // that demonstrably exists tells a caller to stop retrying when retrying is the remedy.
            final Throwable accountLockCause = accountReadCause;
            retainFailure(context, lockAware(accountLockCause,
                    ConcurrentUpdateException.Outcome.COULD_NOT_LOCK_ACCOUNT,
                    COULD_NOT_LOCK_ACCOUNT_FOR_UPDATE,
                    () -> classify(context, IO_STATUS_RECORD_NOT_FOUND, ACCOUNT_FILE_NAME,
                            OPERATION_READ, accountLockCause)));
            return;
        }
        // :3919 MOVE CDEMO-CUST-ID TO WS-CARD-RID-CUST-ID
        //
        // In CICS, CDEMO-CUST-ID was SERVER-owned state: 9500-STORE-FETCHED-DATA had put it in the
        // COMMAREA at :3805-3810 from the cross-reference the region itself read, and the terminal could
        // not touch it. Statelessly the caller echoes that value back, so the MOVE alone would let a
        // request nominate ANY customer row and have this method's 17-field write applied to it - and
        // 9700-CHECK-CHANGE-IN-REC cannot catch it, because CUST-ID is deliberately not one of the fields
        // it compares (see :4152-4186). Re-deriving the identifier from the persisted relationship
        // RESTORES the legacy guarantee rather than adding a new rule; the equality check exists so that a
        // caller is refused rather than silently having a different row updated than the one it named.
        //
        // Placed here, after the account lock guard and before the customer read: every legacy outcome
        // ordering is preserved, and nothing is read for update or written on a refused request.
        if (!bindCustomerToAccount(context)) {
            return;
        }
        context.readKeyCustomerId = context.commAreaCustomerId;
        final Long customerKey = parseKey(context.readKeyCustomerId);
        // :3921-3930 EXEC CICS READ FILE(CUSTDAT) UPDATE
        Customer customer = null;
        Throwable customerReadCause = null;
        if (customerKey != null) {
            try {
                customer = this.customerRepository.findByIdForUpdate(customerKey).orElse(null);
            } catch (final DataAccessException failure) {
                customerReadCause = failure;
            }
        }
        // :3934-3942 the customer lock guard - a DISTINCT flag, and NOTHING has been written
        if (customer == null) {
            context.inputError = true;
            if (isReturnMessageOff(context)) {
                context.returnMessage = COULD_NOT_LOCK_CUSTOMER_FOR_UPDATE;
            }
            // :3939 SET COULD-NOT-LOCK-CUST-FOR-UPDATE - set here, tested nowhere. See BLOCKER 5.2.
            context.customerLockFailed = true;
            // The same distinction as the account guard above, and the same reason. Note what does NOT
            // change: BLOCKER 5.2 is untouched, because classifyWriteOutcome2606 still never tests
            // customerLockFailed, so the SCREEN outcome remains the WHEN OTHER success the source reports
            // at :2613-2614. Only the typed failure the REST entry point rethrows becomes accurate, and
            // Outcome.COULD_NOT_LOCK_CUSTOMER - declared with its own 409 mapping and until now
            // unreachable - is what it was declared for.
            final Throwable customerLockCause = customerReadCause;
            retainFailure(context, lockAware(customerLockCause,
                    ConcurrentUpdateException.Outcome.COULD_NOT_LOCK_CUSTOMER,
                    COULD_NOT_LOCK_CUSTOMER_FOR_UPDATE,
                    () -> classify(context, IO_STATUS_RECORD_NOT_FOUND, CUSTOMER_FILE_NAME,
                            OPERATION_READ, customerLockCause)));
            return;
        }
        context.lockedAccount = account;
        context.lockedCustomer = customer;
        // :3947-3948 PERFORM 9700-CHECK-CHANGE-IN-REC
        checkChangeInRecord9700(context);
        checkChangeInRecord9700Exit();
        // :3950-3952 the cross-paragraph GO TO of 9700 lands here
        if (context.dataWasChangedBeforeUpdate) {
            return;
        }
        // Every value the two images below are about to move must be storable. In CICS an untransmitted
        // field arrived as LOW-VALUES and :3956-4059 moved it in unexamined, because a VSAM record holds
        // binary zeros perfectly well. A PostgreSQL NOT NULL CHAR column does not, so the entity setters
        // refuse it - and that refusal, left to itself, is an IllegalArgumentException on a path whose
        // only handler is the abend funnel. Screening first turns it into the field-level answer the edit
        // cascade would have given, which is the outcome the substrate change removed rather than a rule
        // the source lacked. See requireStorableUpdateImage for the full reasoning.
        if (!requireStorableUpdateImage(context)) {
            return;
        }
        try {
            // :3956-4002 build the account update image
            applyAccountUpdateImage(context, account);
            // :4007-4059 build the customer update image
            applyCustomerUpdateImage(context, customer);
        } catch (final IllegalArgumentException refusal) {
            // The net behind the screen above, and the reason the abend path can no longer be reached
            // from here at all: any width, scale or range refusal an entity setter can raise - not only
            // the null case the screen covers - is reported rather than thrown. Nothing has been written
            // at this point, so the single transaction boundary has nothing to back out, and the outcome
            // is the same refused-write outcome the screen uses.
            LOG.warn("CAUP refused an update: a submitted value cannot be stored in the column it maps "
                    + "to. The property and the constraint are on the cause; neither is relayed to the "
                    + "caller.", refusal);
            context.dataWasChangedBeforeUpdate = true;
            context.returnMessage = truncateReturnMessage(UNSTORABLE_UPDATE_IMAGE_MESSAGE);
            retainFailure(context, new ValidationException(UNSTORABLE_UPDATE_IMAGE_MESSAGE, refusal));
            return;
        }
        // :4065-4071 EXEC CICS REWRITE FILE(ACCTDAT)
        try {
            this.accountRepository.save(account);
            this.accountRepository.flush();
        } catch (final DataAccessException failure) {
            // :4076-4081 NO ROLLBACK on this path - see the class documentation.
            // :4079 SET LOCKED-BUT-UPDATE-FAILED TO TRUE. The condition name is an 88-level on
            // WS-RETURN-MSG PIC X(75) (:479, :523-524), so the SET *moves the literal* into the
            // return message, which 3250-SETUP-INFOMSG then moves to ERRMSGO at :2981. Unlike the
            // two lock guards at :3911-3913 and :3936-3938, this site carries NO
            // IF WS-RETURN-MSG-OFF latch, so it overwrites any message already pending.
            context.returnMessage = truncateReturnMessage(LOCKED_BUT_UPDATE_FAILED);
            context.lockedButUpdateFailed = true;
            retainFailure(context, new DataIntegrityException(
                    fileErrorMessage(OPERATION_READ, ACCOUNT_FILE_NAME, CICS_RESP_IOERR,
                            CICS_REASON_NONE), failure));
            return;
        }
        // :4085-4091 EXEC CICS REWRITE FILE(CUSTDAT)
        try {
            this.customerRepository.save(customer);
            this.customerRepository.flush();
        } catch (final DataAccessException failure) {
            // :4095-4103 SET LOCKED-BUT-UPDATE-FAILED then EXEC CICS SYNCPOINT ROLLBACK. The single
            // transaction boundary on updateAccount performs the backout; nothing is conditional here.
            // :4098 the SET moves the :523-524 literal into WS-RETURN-MSG, exactly as at :4079, and
            // likewise with no IF WS-RETURN-MSG-OFF latch.
            context.returnMessage = truncateReturnMessage(LOCKED_BUT_UPDATE_FAILED);
            context.lockedButUpdateFailed = true;
            retainFailure(context, new DataIntegrityException(
                    fileErrorMessage(OPERATION_READ, CUSTOMER_FILE_NAME, CICS_RESP_IOERR,
                            CICS_REASON_NONE), failure));
        }
        // :4105 9600-WRITE-PROCESSING-EXIT
    }

    /**
     * Binds the customer row this write may touch to the account being updated, and refuses the write when
     * the request nominates a different one.
     *
     * <p>Not a paragraph of {@code app/cbl/COACTUPC.cbl}: it is the guard that replaces a guarantee CICS
     * gave structurally. {@code CDEMO-CUST-ID} at {@code :3919} was server-owned COMMAREA state, written by
     * {@code 9500-STORE-FETCHED-DATA} at {@code :3805-3810} from the cross-reference the region had just
     * read for the account in {@code 9200-GETCARDXREF-BYACCT} at {@code :3654-3662}. A 3270 could not alter
     * it. A stateless caller echoes it back, so without this method the identifier used to select and lock
     * the customer row - and therefore the row that receives the seventeen-field image of
     * {@link #applyCustomerUpdateImage} - would be chosen by the caller.
     *
     * <p>The snapshot comparison does not close this. {@code 9700-CHECK-CHANGE-IN-REC} at
     * {@code :4152-4186} deliberately does <strong>not</strong> compare {@code CUST-ID}, so a substituted
     * identifier reaches the write with every compared field matching the substituted row's own values.
     *
     * <p>The derivation is the same one the read path uses - the {@code CXACAIX} path ordered ascending by
     * card number, first record taken - so the value bound here is exactly the value the read path would
     * have placed in the COMMAREA. Three carried spellings are then required to agree with it: the
     * top-level screen field, the {@code ACUP-OLD-DETAILS} snapshot member and the {@code ACUP-NEW-DETAILS}
     * member. A carried value that is absent or blank contradicts nothing and is accepted; a value that is
     * present and different refuses the write.
     *
     * <p>A refusal reports {@code DATA-WAS-CHANGED-BEFORE-UPDATE}, which
     * {@link #classifyWriteOutcome2606} maps to {@code SHOW-DETAILS}: nothing is written, and the caller is
     * told to review the record and resubmit - which re-fetches the account and re-derives the identifier
     * server-side, so a legitimate stale-screen caller succeeds on the second attempt. The customer-lock
     * outcome is deliberately <em>not</em> reused for this: {@code :2613-2614} never tests that flag and
     * reports it as success, which is exactly the wrong answer for a refused write. The retained failure
     * carries no identifier value, only the field name, so nothing about the account's real customer is
     * disclosed.
     *
     * <p>Side effects: reads the cross-reference path; on success replaces
     * {@code context.commAreaCustomerId} with the derived value, so the read at {@code :3921-3930} uses the
     * server's identifier and never the caller's. Nothing is written, nothing is locked.
     *
     * @param context the per-invocation state carrier
     * @return {@code true} when the write may proceed, {@code false} when it has been refused and the
     *         caller must return immediately
     */
    private boolean bindCustomerToAccount(final UpdateContext context) {
        final Long accountKey = parseKey(context.readKeyAccountId);
        if (accountKey == null) {
            // Unreachable in practice: the account lock guard above has already returned for a key that
            // does not parse. Retained so this method never derives from a key it cannot read, and so the
            // legacy outcome for a malformed account identifier stays the account-lock outcome.
            return true;
        }

        final Long boundCustomerId;
        try {
            // The lowest card number bound to the account, fetched as one row rather than as a list whose
            // tail is discarded: the derivation needs the first match only, and every cross-reference row
            // for an account carries the same customer, so a wider read could not change the answer.
            boundCustomerId = this.cardCrossReferenceRepository
                    .findFirstByAccountIdOrderByCardNumberAsc(accountKey)
                    .map(CardCrossReference::getCustomerId)
                    .orElse(null);
        } catch (final DataAccessException failure) {
            LOG.error("CAUP refused an update: the cross-reference path could not be read to establish "
                    + "which customer the account is bound to.", failure);
            context.dataWasChangedBeforeUpdate = true;
            context.returnMessage = truncateReturnMessage(XREF_READ_ERROR_MESSAGE);
            retainFailure(context, classify(context, IO_STATUS_IO_ERROR, XREF_ACCOUNT_PATH_NAME,
                    OPERATION_READ, failure));
            return false;
        }

        if (boundCustomerId == null) {
            LOG.warn("CAUP refused an update: no cross-reference record binds the account to a customer, "
                    + "so no customer row may be written.");
            context.dataWasChangedBeforeUpdate = true;
            context.returnMessage = truncateReturnMessage(DID_NOT_FIND_ACCOUNT_IN_CARDXREF);
            retainFailure(context, ValidationException.invalidField(FIELD_CUSTOMER_ID,
                    CUSTOMER_NOT_BOUND_TO_ACCOUNT_MESSAGE));
            return false;
        }

        final AccountUpdateRequest.NewDetails submitted = context.request.getNewDetails();
        if (!carriedCustomerIdAgrees(context.commAreaCustomerId, boundCustomerId)
                || !carriedCustomerIdAgrees(context.request.getCustomerId(), boundCustomerId)
                || (submitted != null
                    && !carriedCustomerIdAgrees(submitted.getCustomerId(), boundCustomerId))) {
            LOG.warn("CAUP refused an update: the customer identifier submitted with the request is not "
                    + "the customer the account is bound to.");
            context.dataWasChangedBeforeUpdate = true;
            context.returnMessage = truncateReturnMessage(DATA_WAS_CHANGED_BEFORE_UPDATE);
            retainFailure(context, ValidationException.invalidField(FIELD_CUSTOMER_ID,
                    CUSTOMER_ID_NOT_BOUND_MESSAGE));
            return false;
        }

        // The server's value replaces whatever was carried, so the read below cannot address another row
        // even if a carried spelling differed only in padding.
        context.commAreaCustomerId = formatNumericKey(boundCustomerId, CUSTOMER_ID_LENGTH);
        return true;
    }

    /**
     * Tests one carried customer identifier against the identifier the cross-reference binds to the
     * account.
     *
     * <p>Compared as numbers rather than as text, because the three carried spellings reach this class in
     * different forms - zero-padded to {@value #CUSTOMER_ID_LENGTH} from the snapshot, and as the operator
     * typed it from the screen field - and a padding difference is not a mismatch. A value that is absent,
     * blank or non-numeric carries no assertion about which customer is meant and therefore contradicts
     * nothing; the derived value stands. A value that parses and differs is a mismatch.
     *
     * @param carried the identifier as carried by the request; may be {@code null} or blank
     * @param bound   the identifier derived from the persisted cross-reference; never {@code null}
     * @return {@code true} when the carried value does not contradict the derived one
     */
    private static boolean carriedCustomerIdAgrees(final String carried, final Long bound) {
        if (carried == null || carried.isBlank()) {
            return true;
        }
        final Long parsed = parseKey(carried);
        return parsed != null && parsed.equals(bound);
    }

    /**
     * Screens the twenty values the two update images are about to move for the one condition a
     * PostgreSQL {@code NOT NULL} column cannot represent, and refuses the write with a field-level
     * failure rather than letting it become an abend.
     *
     * <p>Not a paragraph of {@code app/cbl/COACTUPC.cbl}: like
     * {@link #bindCustomerToAccount(UpdateContext)} it consumes none of the eighty-seven label methods.
     * It exists because the substrate changed underneath step four of {@code 9600-WRITE-PROCESSING}.
     *
     * <h4>Why the source needs no such screen and this method does</h4>
     * <p>On a 3270 an untransmitted field arrived as {@code LOW-VALUES}, and {@code :3956-4059} moved it
     * into the update record unexamined - a VSAM record holds binary zeros in a {@code PIC X} field
     * perfectly well, so the write succeeded. {@code receiveField} reproduces that arrival faithfully:
     * {@code isLowValues} answers {@code true} for an absent member and for an all-{@code NUL} value, both
     * of which normalise to {@code null} exactly as {@code LOW-VALUES}. An <em>empty</em> string is
     * deliberately not one of them - {@code docs/api-contracts.md} section 11.2.1 publishes {@code ""} and
     * {@code "*"} as the same instruction, this field was cleared, so it is received as the cleared value
     * rather than as an absence. A {@code CHAR} column in PostgreSQL cannot store {@code null},
     * so {@code Account.requireWidth} and {@code Customer.requireWidth} refuse it - correctly, and by
     * design. What was wrong is where that refusal landed: an {@code IllegalArgumentException} raised
     * inside the write is not a {@code CardDemoException}, so the funnel at {@code :862-864} took it to
     * {@link #abendRoutine(UpdateContext, Throwable)} and the caller received abend {@code 9999} for
     * having omitted one member of the payload.
     *
     * <p><strong>The five amount values fail differently, and worse.</strong> For them
     * {@code 1100-RECEIVE-MAP} writes {@code LOW-VALUES} only into the working-storage edit field
     * {@code ACUP-NEW-CURR-BAL-X PIC X(15)} at {@code :414} and performs the {@code COMPUTE} into the image
     * only when {@code TEST-NUMVAL-C} passes - {@code :1101-1112} - so the image field
     * {@code ACUP-NEW-CURR-BAL PIC X(12)} keeps the spaces {@code INITIALIZE ACUP-NEW-DETAILS} at
     * {@code :1047} left it, and {@code :3964} moves twelve spaces read through the {@code S9(10)V99}
     * redefinition at {@code :763-765} into a packed-decimal field. That is a move of invalid digits, whose
     * result the language does not define, so for these five there is no legacy value to reproduce at all.
     *
     * <p>No answer is parity, because the legacy answer is unavailable in both of its forms: the value the
     * source stored cannot exist in the target's schema, and an undefined result cannot be reproduced.
     * <strong>Three answers were available and the third is the one worth naming.</strong> Letting the write
     * proceed answers abend {@code 9999} to a well-formed request. Substituting the closest storable
     * analogue - blanks for the character columns, zero for the amount columns - is not parity either, since
     * blanks are a different byte value from {@code NUL}, and for the amounts it would silently overwrite a
     * balance, a credit limit or a cycle total with zero, which is the worst outcome on offer. A field-level
     * refusal is the remaining answer, and it is the one the program's own vocabulary already gives -
     * {@code 1215-EDIT-MANDATORY} at {@code :1824-1852} and {@code 1225-EDIT-ALPHA-REQD} at
     * {@code :1898-1951} answer an unsupplied required field with {@code BLANK} and
     * {@code ' must be supplied.'} - so this method reuses that exact literal and that exact per-field
     * tri-state rather than inventing a diagnostic. It is recorded as a deviation forced by the substrate,
     * under the identifier {@code DL-DV-06}, and not presented as equivalence; the entry carries all four
     * rejected alternatives, and
     * {@code AccountUpdateServiceTest.anAbsentCreditScoreIsADeviationBecauseTheSourceStoresLowValues}
     * fails if it ceases to exist.
     *
     * <h4>What is deliberately NOT changed</h4>
     * <p>The screen runs <strong>only here</strong>, inside the confirm turn's write, and adds no edit to
     * {@code 1200-EDIT-MAP-INPUTS}. That matters: {@code :1463-1468} abandons the whole cascade
     * <em>before its first field edit</em> when {@code ACUP-CHANGES-OK-NOT-CONFIRMED} is set, because the
     * 3270 still held values the previous turn had already validated. The locator is worth stating exactly,
     * because the neighbouring {@code :1433-1449} exit is a different one - it fires when the details have
     * not been fetched - and citing it here would send a reader to the wrong branch. Digits in a name, an
     * all-blank group identifier, a non-numeric credit score and a state code the lookup refuses are
     * therefore accepted on the confirm turn, and they remain accepted: that is preserved parity, it is not
     * what this method addresses, and {@code AccountUpdateServiceTest.theConfirmTurnAbandonsTheWholeEditCascade}
     * asserts it directly rather than leaving it to inference. Only the abend changes.
     *
     * <p>The three assembled values cannot reach here {@code null} and are not screened:
     * {@code assembleDate}, {@code assemblePhoneNumber} and {@code assembledSsn} all run their components
     * through {@code moveAlphanumeric}, which renders a {@code null} component as blanks exactly as a
     * {@code MOVE} to a {@code PIC X} field would. Screening them would add an unreachable branch.
     *
     * <p>Order follows the two images statement for statement, so the field reported is the first one
     * {@code :3956-4059} would have failed on - the same first-failure-wins discipline the edit cascade
     * keeps through its {@code IF WS-RETURN-MSG-OFF} latches.
     *
     * <p>Side effects: nothing is read, locked or written. On a refusal the outcome flag, the return
     * message and the retained typed failure are recorded on the context by
     * {@link #refuseUnstorableField(UpdateContext, String, String, ValidationException)}, which is what
     * makes {@code updateAccount} answer a field-level {@code 400} while the screen-parity
     * {@code processRequest} keeps projecting the outcome instead of throwing.
     *
     * @param context the per-invocation state carrier
     * @return {@code true} when every value is storable and the write may proceed, {@code false} when the
     *         write has been refused and the caller must return immediately
     */
    private static boolean requireStorableUpdateImage(final UpdateContext context) {
        // The account image, in the order of :3962-4002.
        return requireSupplied(context, context.newActiveStatus,
                        FIELD_ACCOUNT_STATUS, LABEL_ACCOUNT_STATUS)
                && requireSuppliedAmount(context, context.newCurrentBalance,
                        context.newCurrentBalanceText, FIELD_CURRENT_BALANCE, LABEL_CURRENT_BALANCE)
                && requireSuppliedAmount(context, context.newCreditLimit,
                        context.newCreditLimitText, FIELD_CREDIT_LIMIT, LABEL_CREDIT_LIMIT)
                && requireSuppliedAmount(context, context.newCashCreditLimit,
                        context.newCashCreditLimitText, FIELD_CASH_CREDIT_LIMIT,
                        LABEL_CASH_CREDIT_LIMIT)
                && requireSuppliedAmount(context, context.newCurrentCycleCredit,
                        context.newCurrentCycleCreditText, FIELD_CURRENT_CYCLE_CREDIT,
                        LABEL_CURRENT_CYCLE_CREDIT)
                && requireSuppliedAmount(context, context.newCurrentCycleDebit,
                        context.newCurrentCycleDebitText, FIELD_CURRENT_CYCLE_DEBIT,
                        LABEL_CURRENT_CYCLE_DEBIT)
                && requireSupplied(context, context.newGroupId,
                        FIELD_ACCOUNT_GROUP_ID, LABEL_ACCOUNT_GROUP_ID)
                // The customer image, in the order of :4010-4059.
                && requireSupplied(context, context.newFirstName,
                        FIELD_FIRST_NAME, LABEL_FIRST_NAME)
                && requireSupplied(context, context.newMiddleName,
                        FIELD_MIDDLE_NAME, LABEL_MIDDLE_NAME)
                && requireSupplied(context, context.newLastName,
                        FIELD_LAST_NAME, LABEL_LAST_NAME)
                && requireSupplied(context, context.newAddressLine1,
                        FIELD_ADDRESS_LINE_1, LABEL_ADDRESS_LINE_1)
                && requireSupplied(context, context.newAddressLine2,
                        FIELD_ADDRESS_LINE_2, LABEL_ADDRESS_LINE_2)
                // :1329-1334 ACSCITYI feeds ADDR-LINE-3, so the city label owns the third line.
                && requireSupplied(context, context.newAddressLine3, FIELD_CITY, LABEL_CITY)
                && requireSupplied(context, context.newStateCode, FIELD_STATE_CODE, LABEL_STATE)
                && requireSupplied(context, context.newCountryCode, FIELD_COUNTRY_CODE, LABEL_COUNTRY)
                && requireSupplied(context, context.newZip, FIELD_ZIP, LABEL_ZIP)
                && requireSupplied(context, context.newGovernmentIssuedId,
                        FIELD_GOVERNMENT_ISSUED_ID, LABEL_GOVERNMENT_ISSUED_ID)
                && requireSupplied(context, context.newEftAccountId,
                        FIELD_EFT_ACCOUNT_ID, LABEL_EFT_ACCOUNT_ID)
                && requireSupplied(context, context.newPrimaryCardHolderIndicator,
                        FIELD_PRIMARY_CARD_HOLDER, LABEL_PRIMARY_CARD_HOLDER)
                && requireSupplied(context, context.newFicoScore,
                        FIELD_FICO_SCORE, LABEL_FICO_SCORE);
    }

    /**
     * Refuses one unsupplied character value on behalf of
     * {@link #requireStorableUpdateImage(UpdateContext)}.
     *
     * <p>{@code FailureKind.BLANK} is the deliberate choice over {@code INVALID}: it is the same
     * distinction {@code app/cpy/CSSETATY.cpy} draws when it emits {@code '*'} for
     * {@code FLG-(TESTVAR1)-BLANK} but not for {@code FLG-(TESTVAR1)-NOT-OK}, so an absent value stays
     * distinguishable from a wrong one all the way out to the caller.
     *
     * @param context the per-invocation state carrier, on which the refusal is recorded
     * @param value the value bound for a {@code NOT NULL CHAR} column, possibly {@code null}
     * @param field the BMS symbolic-map field name of {@code app/cpy-bms/COACTUP.CPY}, which is the
     *              vocabulary every other field-level failure from this bean already uses
     * @param label the edit-cascade label, used to compose the source's own diagnostic
     * @return {@code true} when the value is present, {@code false} when the write has been refused
     */
    private static boolean requireSupplied(final UpdateContext context,
                                           final String value,
                                           final String field,
                                           final String label) {
        if (value != null) {
            return true;
        }
        return refuseUnstorableField(context, field, label + SUFFIX_MUST_BE_SUPPLIED,
                ValidationException.missingField(field,
                        truncateReturnMessage(label + SUFFIX_MUST_BE_SUPPLIED)));
    }

    /**
     * Refuses one unusable monetary value on behalf of
     * {@link #requireStorableUpdateImage(UpdateContext)}.
     *
     * <p>Two conditions reach {@code requireMoney} as {@code null} and they are not the same failure, so
     * they are reported differently. The screen field absent altogether is {@code BLANK} and
     * {@code ' must be supplied.'}, exactly as {@code 1250-EDIT-SIGNED-9V2} at {@code :2184-2199} reports
     * it. A field that was transmitted but that {@code numvalC} could not read is {@code INVALID} and
     * {@code ' is not valid'}, exactly as {@code :2201-2215} reports it. Collapsing the two would tell a
     * caller who sent {@code "12.3.4"} that they sent nothing.
     *
     * @param context the per-invocation state carrier, on which the refusal is recorded
     * @param amount the parsed amount, possibly {@code null}
     * @param text   the screen image the amount was parsed from, possibly {@code null}
     * @param field  the BMS symbolic-map field name
     * @param label  the edit-cascade label
     * @return {@code true} when the amount is usable, {@code false} when the write has been refused
     */
    private static boolean requireSuppliedAmount(final UpdateContext context,
                                                 final BigDecimal amount,
                                                 final String text,
                                                 final String field,
                                                 final String label) {
        if (amount != null) {
            return true;
        }
        final String message = text == null
                ? label + SUFFIX_MUST_BE_SUPPLIED
                : label + SUFFIX_IS_NOT_VALID;
        final ValidationException failure = text == null
                ? ValidationException.missingField(field, truncateReturnMessage(message))
                : ValidationException.invalidField(field, truncateReturnMessage(message));
        return refuseUnstorableField(context, field, message, failure);
    }

    /**
     * Records one refused field on behalf of the two screens above and reports that the write must stop.
     *
     * <p>The outcome recorded is {@code DATA-WAS-CHANGED-BEFORE-UPDATE}, which
     * {@link #classifyWriteOutcome2606(UpdateContext)} maps to {@code ACUP-SHOW-DETAILS}: nothing has been
     * written and the caller is told to review the record and resubmit, which is exactly the remedy for a
     * payload that omitted a field. The choice is the same one {@link #bindCustomerToAccount(UpdateContext)}
     * makes and for the same reason - the alternative is the {@code WHEN OTHER} arm at {@code :2613-2614},
     * which reports {@code CHANGES-OKAYED-AND-DONE}, and reporting success for a write that did not happen
     * is the one answer that must not be given.
     *
     * <p>Reporting rather than throwing is what keeps the two public entry points' contracts intact:
     * {@code updateAccount} rethrows the retained failure at {@code :1766-1768} and so answers a
     * field-level {@code 400}, while the screen-parity {@code processRequest} continues to project the
     * outcome instead of throwing. {@link #retainFailure(UpdateContext, CardDemoException)} keeps the
     * first failure, matching the {@code IF WS-RETURN-MSG-OFF} first-error-wins latch of the edit cascade.
     *
     * @param context the per-invocation state carrier
     * @param field   the BMS symbolic-map field name, logged but never the value it holds
     * @param message the composed diagnostic, which names the field's label and no submitted value
     * @param failure the typed failure to retain
     * @return {@code false} always, so a caller can {@code return} it directly
     */
    private static boolean refuseUnstorableField(final UpdateContext context,
                                                 final String field,
                                                 final String message,
                                                 final ValidationException failure) {
        LOG.warn("CAUP refused an update: screen field {} arrived unusable, so the value bound for its "
                + "NOT NULL column cannot be stored. {}", field, message);
        context.dataWasChangedBeforeUpdate = true;
        context.returnMessage = truncateReturnMessage(message);
        retainFailure(context, failure);
        return false;
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, logical lines {@code :3956-4002}, the account half of step four of
     * {@code 9600-WRITE-PROCESSING}. An inline block, not a paragraph, so it consumes none of the
     * eighty-seven label methods; it is factored out only so that the seven-step spine above stays
     * readable.
     * <p>{@code :3956} {@code INITIALIZE ACCT-UPDATE-RECORD} then {@code :3960-4002} move the submitted
     * values in. The three dates are <strong>assembled</strong> from their components with {@code '-'}
     * separators at {@code :3976-3982}, {@code :3984-3990} and {@code :3994-4000}, producing the
     * ten-character {@code YYYY-MM-DD} text the copybook declares.</p>
     * <p>{@code :3993}'s {@code MOVE ACCT-REISSUE-DATE TO ACCT-UPDATE-REISSUE-DATE} is unconditionally
     * overwritten by the {@code STRING} on the very next statement, so it is cited and not reproduced.</p>
     * <p>All five monetary fields are {@code BigDecimal} throughout - {@code PIC S9(10)V99} maps to
     * {@code NUMERIC(12,2)} - with no {@code float} or {@code double} anywhere on the path.</p>
     * @param context the per-invocation state carrier
     * @param account the locked account entity to mutate
     */
    private void applyAccountUpdateImage(final UpdateContext context, final Account account) {
        // :3962
        account.setActiveStatus(context.newActiveStatus);
        // :3964-3974 the five monetary fields, BigDecimal throughout
        account.setCurrentBalance(scaleAmount(context.newCurrentBalance));
        account.setCreditLimit(scaleAmount(context.newCreditLimit));
        account.setCashCreditLimit(scaleAmount(context.newCashCreditLimit));
        account.setCurrentCycleCredit(scaleAmount(context.newCurrentCycleCredit));
        account.setCurrentCycleDebit(scaleAmount(context.newCurrentCycleDebit));
        // :3976-3982 assemble YYYY-MM-DD
        account.setOpenDate(assembleDate(context.newOpenYear, context.newOpenMonth,
                context.newOpenDay));
        // :3984-3990 the EXPIRAION misspelling is the copybook's own and is preserved
        account.setExpiraionDate(assembleDate(context.newExpiryYear, context.newExpiryMonth,
                context.newExpiryDay));
        // :3993 is inert - overwritten by :3994-4000 on the next statement
        account.setReissueDate(assembleDate(context.newReissueYear, context.newReissueMonth,
                context.newReissueDay));
        // :4002
        account.setGroupId(context.newGroupId);
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, logical lines {@code :4007-4059}, the customer half of step four of
     * {@code 9600-WRITE-PROCESSING}. An inline block, not a paragraph, so it consumes none of the
     * eighty-seven label methods.
     * <p>{@code :4007} {@code INITIALIZE CUST-UPDATE-RECORD} then {@code :4009-4059} move the submitted
     * values in. Two assemblies matter. The telephone numbers are built at {@code :4027-4033} and
     * {@code :4035-4041} as {@code '(' area ')' prefix '-' line} - thirteen characters inside a
     * {@code PIC X(15)} field, which is precisely the layout that lets
     * {@code 3202-SHOW-ORIGINAL-VALUES} slice them back out at offsets two, six and ten. The date of
     * birth is assembled at {@code :4047-4052} with {@code '-'} separators, giving the
     * <strong>ten-character</strong> live form - deliberately unlike the eight-character compact snapshot
     * form, which is the whole origin of the offset asymmetry documented on
     * {@link #checkChangeInRecord9700}.</p>
     * @param context the per-invocation state carrier
     * @param customer the locked customer entity to mutate
     */
    private void applyCustomerUpdateImage(final UpdateContext context, final Customer customer) {
        // :4010-4025
        customer.setFirstName(context.newFirstName);
        customer.setMiddleName(context.newMiddleName);
        customer.setLastName(context.newLastName);
        customer.setAddressLine1(context.newAddressLine1);
        customer.setAddressLine2(context.newAddressLine2);
        customer.setAddressLine3(context.newAddressLine3);
        customer.setAddressStateCode(context.newStateCode);
        customer.setAddressCountryCode(context.newCountryCode);
        customer.setAddressZip(context.newZip);
        // :4027-4033 and :4035-4041 assemble '(' area ')' prefix '-' line
        customer.setPhoneNumber1(assemblePhoneNumber(context.newPhone1AreaCode,
                context.newPhone1Prefix, context.newPhone1LineNumber));
        customer.setPhoneNumber2(assemblePhoneNumber(context.newPhone2AreaCode,
                context.newPhone2Prefix, context.newPhone2LineNumber));
        // :4044-4046
        customer.setSsn(assembledSsn(context));
        customer.setGovernmentIssuedId(context.newGovernmentIssuedId);
        // :4047-4052 the LIVE form is ten characters with separators, unlike the compact snapshot
        customer.setDateOfBirth(assembleDate(context.newDateOfBirthYear,
                context.newDateOfBirthMonth, context.newDateOfBirthDay));
        // :4054-4059
        customer.setEftAccountId(context.newEftAccountId);
        customer.setPrimaryCardHolderIndicator(context.newPrimaryCardHolderIndicator);
        // :4058-4059 ACUP-NEW-CUST-FICO-SCORE is the PIC 9(03) REDEFINES of the X(03) screen image
        // (:845-847), and CUST-UPDATE-FICO-CREDIT-SCORE is PIC 9(03), so this MOVE is numeric. The
        // guarded parse keeps a non-numeric screen value out of the entity - which cannot happen on this
        // path, because 1275-EDIT-FICO-SCORE has already set INPUT-ERROR for it.
        customer.setFicoCreditScore(moveNumericText(context.newFicoScore, FICO_SCORE_LENGTH));
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 9600-WRITE-PROCESSING-EXIT.}, logical lines
     * {@code :4105-4107}. A bare {@code EXIT} and the target of five {@code GO TO} statements - three
     * inside {@code 9600} itself and two <strong>from inside {@code 9700}</strong>; a tracked, cited
     * no-op.
     */
    private void writeProcessing9600Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :4105-4107.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 9700-CHECK-CHANGE-IN-REC.}, logical lines
     * {@code :4109-4192}. Detects whether <strong>somebody else</strong> changed the record while the
     * operator was looking at it, by comparing the freshly locked rows against the caller-supplied
     * snapshot.
     *
     * <h4>A different question from {@code 1205-COMPARE-OLD-NEW}</h4>
     * <p>{@link #compareOldNew1205} compares {@code ACUP-NEW-*} against {@code ACUP-OLD-*} and asks
     * "did the user change anything on screen". This paragraph compares the <em>live</em> record against
     * {@code ACUP-OLD-*} and asks "did someone else change it while we were out". The two use
     * <strong>different case rules</strong>, and conflating them breaks both.</p>
     *
     * <h4>Exactly two {@code IF} statements, despite the comment</h4>
     * <p>The comment at {@code :4148} reads
     * {@code "Customer  data - Split into 2 IFs for easier reading"} and continues at
     * {@code :4149-4150}. It describes an intention that was never carried out: the customer test is a
     * <strong>single</strong> {@code IF} with nineteen conjuncts. The comment's intent is recorded here;
     * it is deliberately <strong>not acted on</strong>, because splitting the condition would change the
     * short-circuit order.</p>
     *
     * <h4>The account test: 16 comparison clauses over 10 logical fields</h4>
     * <p>{@code :4115-4140}. Active status; the five monetary fields at {@code :4117-4125}, compared by
     * {@code compareTo} rather than {@code equals} so that scale differences do not read as changes; then
     * the three dates, each as <strong>three separate substring comparisons</strong> at
     * {@code :4127-4129}, {@code :4131-4133} and {@code :4135-4137} - never as whole strings - against
     * discrete named snapshot subfields; and finally the group identifier at {@code :4139-4140} through
     * {@code FUNCTION LOWER-CASE} on both sides, <strong>with no {@code FUNCTION TRIM}</strong>.</p>
     * <p>{@code ACCT-ID} and {@code ACCT-ADDR-ZIP} are <strong>not</strong> compared. They are not added
     * here.</p>
     *
     * <h4>The customer test: 19 comparison clauses over 17 logical fields</h4>
     * <p>{@code :4152-4186}. {@code FUNCTION UPPER-CASE} on both sides for exactly <strong>nine</strong>
     * fields - first, middle and last name, the three address lines, state, country and the
     * government-issued identifier. <strong>Ten</strong> clauses use no case function at all: postal code,
     * both telephone numbers, the social security number, the three date-of-birth substrings, the
     * electronic funds account identifier, the primary-holder indicator and the credit score. No
     * {@code TRIM} appears anywhere in this paragraph - every occurrence in the program is at
     * {@code :1698-1766} inside {@code 1205} or in the edit routines.</p>
     * <p>{@code CUST-ID} is <strong>not</strong> compared. It is not added here.</p>
     *
     * <h4>The date-of-birth offset asymmetry - the trap that would break every request</h4>
     * <p>{@code :4174-4179} compares the live field at {@code (1:4)}, {@code (6:2)} and {@code (9:2)}
     * against the snapshot group at {@code (1:4)}, <strong>{@code (5:2)}</strong> and
     * <strong>{@code (7:2)}</strong>. The live customer record holds a dash-separated
     * {@code PIC X(10)} date; the snapshot holds the same date compacted to eight bytes with no
     * separators, as {@link #storeFetchedData9500} proves. So the source compares one against one, six
     * against five, and nine against seven.</p>
     * <p><strong>A naive whole-string comparison would report a change on every single request</strong>,
     * making the endpoint permanently unusable. Because {@code 9500} stores the snapshot as three discrete
     * components, slicing the compact group at {@code (1:4)}, {@code (5:2)} and {@code (7:2)} yields
     * exactly those three components - so the component-wise comparison below is the offset asymmetry,
     * expressed without arithmetic. The asymmetry applies to the <strong>date of birth alone</strong>: the
     * three account dates put offsets on the live side only and compare against discrete named subfields,
     * so there is no right-hand offset there.</p>
     *
     * <h4>The cross-paragraph {@code GO TO}</h4>
     * <p>Both {@code ELSE} branches, at {@code :4142-4144} and {@code :4188-4190}, set
     * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} and then
     * {@code GO TO 9600-WRITE-PROCESSING-EXIT} - a jump into the <em>caller's</em> exit label, not this
     * paragraph's. That is modelled as an early return whose outcome the caller propagates immediately:
     * this method sets the flag and returns, and {@code 9600} tests it at {@code :3950} and leaves.</p>
     *
     * <h4>Why {@code @Version} alone is not enough</h4>
     * <p>A version column detects <em>that</em> a row changed; this paragraph detects <em>which business
     * field values</em> differ from what the operator was shown. A concurrent write that restored a field
     * to its original value passes this check and fails a version check. Both layers are therefore
     * required and neither substitutes for the other.</p>
     *
     * @param context the per-invocation state carrier; sets {@code dataWasChangedBeforeUpdate}
     */
    private void checkChangeInRecord9700(final UpdateContext context) {
        final Account account = context.lockedAccount;
        final Customer customer = context.lockedCustomer;
        // Clause B: a missing snapshot is an explicit validation failure, never a skipped comparison.
        if (context.authenticOldDetails == null) {
            context.dataWasChangedBeforeUpdate = true;
            retainFailure(context, ValidationException.missingField(OLD_DETAILS_FIELD,
                    OLD_DETAILS_REQUIRED_MESSAGE));
            return;
        }
        // :4115-4140 the account IF: 16 comparison clauses over 10 logical fields
        final boolean accountUnchanged =
                // :4115
                equalText(account.getActiveStatus(), context.snapshotActiveStatus)
                // :4117 compareTo, never equals
                && equalAmount(account.getCurrentBalance(), context.snapshotCurrentBalance)
                // :4119
                && equalAmount(account.getCreditLimit(), context.snapshotCreditLimit)
                // :4121
                && equalAmount(account.getCashCreditLimit(), context.snapshotCashCreditLimit)
                // :4123
                && equalAmount(account.getCurrentCycleCredit(), context.snapshotCurrentCycleCredit)
                // :4125
                && equalAmount(account.getCurrentCycleDebit(), context.snapshotCurrentCycleDebit)
                // :4127-4129 three substrings, never a whole string
                && equalText(datePart(account.getOpenDate(), DATE_YEAR_OFFSET, DATE_YEAR_LENGTH),
                        context.snapshotOpenYear)
                && equalText(datePart(account.getOpenDate(), DATE_MONTH_OFFSET, DATE_PART_LENGTH),
                        context.snapshotOpenMonth)
                && equalText(datePart(account.getOpenDate(), DATE_DAY_OFFSET, DATE_PART_LENGTH),
                        context.snapshotOpenDay)
                // :4131-4133 EXPIRAION - the copybook's own misspelling, preserved
                && equalText(datePart(account.getExpiraionDate(), DATE_YEAR_OFFSET,
                        DATE_YEAR_LENGTH), context.snapshotExpiraionYear)
                && equalText(datePart(account.getExpiraionDate(), DATE_MONTH_OFFSET,
                        DATE_PART_LENGTH), context.snapshotExpiraionMonth)
                && equalText(datePart(account.getExpiraionDate(), DATE_DAY_OFFSET,
                        DATE_PART_LENGTH), context.snapshotExpiraionDay)
                // :4135-4137
                && equalText(datePart(account.getReissueDate(), DATE_YEAR_OFFSET, DATE_YEAR_LENGTH),
                        context.snapshotReissueYear)
                && equalText(datePart(account.getReissueDate(), DATE_MONTH_OFFSET,
                        DATE_PART_LENGTH), context.snapshotReissueMonth)
                && equalText(datePart(account.getReissueDate(), DATE_DAY_OFFSET, DATE_PART_LENGTH),
                        context.snapshotReissueDay)
                // :4139-4140 LOWER-CASE both sides, NO TRIM
                && equalLowerCase(account.getGroupId(), context.snapshotGroupId);
        // :4142-4144 the cross-paragraph GO TO into the caller's exit
        if (!accountUnchanged) {
            context.dataWasChangedBeforeUpdate = true;
            context.returnMessage = DATA_WAS_CHANGED_BEFORE_UPDATE;
            retainFailure(context, concurrentUpdateFailure(ACCOUNT_FILE_NAME));
            return;
        }
        // :4152-4186 the customer IF: 19 comparison clauses over 17 logical fields. A SINGLE IF,
        // despite the :4148 comment claiming it was split into two. Not acted on - see the Javadoc.
        final String liveDateOfBirth = customer.getDateOfBirth();
        final boolean customerUnchanged =
                // :4152-4153 UPPER-CASE 1 of 9
                equalUpperCase(customer.getFirstName(), context.snapshotFirstName)
                // :4154-4155 UPPER-CASE 2 of 9
                && equalUpperCase(customer.getMiddleName(), context.snapshotMiddleName)
                // :4156-4157 UPPER-CASE 3 of 9
                && equalUpperCase(customer.getLastName(), context.snapshotLastName)
                // :4158-4159 UPPER-CASE 4 of 9
                && equalUpperCase(customer.getAddressLine1(), context.snapshotAddressLine1)
                // :4160-4161 UPPER-CASE 5 of 9
                && equalUpperCase(customer.getAddressLine2(), context.snapshotAddressLine2)
                // :4162-4163 UPPER-CASE 6 of 9
                && equalUpperCase(customer.getAddressLine3(), context.snapshotAddressLine3)
                // :4164-4165 UPPER-CASE 7 of 9
                && equalUpperCase(customer.getAddressStateCode(), context.snapshotAddressStateCode)
                // :4166-4167 UPPER-CASE 8 of 9
                && equalUpperCase(customer.getAddressCountryCode(),
                        context.snapshotAddressCountryCode)
                // :4168 no case function 1 of 10
                && equalText(customer.getAddressZip(), context.snapshotAddressZip)
                // :4169 no case function 2 of 10
                && equalText(customer.getPhoneNumber1(), context.snapshotPhoneNumber1)
                // :4170 no case function 3 of 10
                && equalText(customer.getPhoneNumber2(), context.snapshotPhoneNumber2)
                // :4171 no case function 4 of 10
                && equalText(customer.getSsn(), context.snapshotSsn)
                // :4172-4173 UPPER-CASE 9 of 9
                && equalUpperCase(customer.getGovernmentIssuedId(),
                        context.snapshotGovernmentIssuedId)
                // :4174-4175 live (1:4) against snapshot (1:4) - no case function 5 of 10
                && equalText(datePart(liveDateOfBirth, DATE_YEAR_OFFSET, DATE_YEAR_LENGTH),
                        context.snapshotDateOfBirthYear)
                // :4176-4177 live (6:2) against snapshot (5:2) - THE OFFSET ASYMMETRY
                && equalText(datePart(liveDateOfBirth, DATE_MONTH_OFFSET, DATE_PART_LENGTH),
                        context.snapshotDateOfBirthMonth)
                // :4178-4179 live (9:2) against snapshot (7:2) - THE OFFSET ASYMMETRY
                && equalText(datePart(liveDateOfBirth, DATE_DAY_OFFSET, DATE_PART_LENGTH),
                        context.snapshotDateOfBirthDay)
                // :4181-4182 no case function 8 of 10
                && equalText(customer.getEftAccountId(), context.snapshotEftAccountId)
                // :4183-4185 no case function 9 of 10
                && equalText(customer.getPrimaryCardHolderIndicator(),
                        context.snapshotPrimaryCardHolderIndicator)
                // :4186 no case function 10 of 10. CUST-FICO-CREDIT-SCORE is PIC 9(03) and
                // ACUP-OLD-CUST-FICO-SCORE redefines its X(03) image as PIC 9(03) (:app/cbl/COACTUPC.cbl
                // snapshot group at :757), so this single clause is a NUMERIC comparison, not a text one.
                && equalScore(customer.getFicoCreditScore(), context.snapshotFicoScore);
        // :4188-4190 the same cross-paragraph GO TO
        if (!customerUnchanged) {
            context.dataWasChangedBeforeUpdate = true;
            context.returnMessage = DATA_WAS_CHANGED_BEFORE_UPDATE;
            retainFailure(context, concurrentUpdateFailure(CUSTOMER_FILE_NAME));
        }
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code 9700-CHECK-CHANGE-IN-REC-EXIT.}, logical lines
     * {@code :4193-4195}. A bare {@code EXIT}. Note that neither {@code ELSE} branch of the paragraph
     * actually reaches this label - both jump past it into {@code 9600-WRITE-PROCESSING-EXIT} - so it is
     * reached only on the unchanged path. A tracked, cited no-op.
     */
    private void checkChangeInRecord9700Exit() {
        // EXIT - intentional no-op preserved for control-flow parity with :4193-4195.
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code ABEND-ROUTINE.}, logical lines
     * {@code :4203-4224}. The catch-all abend path registered by {@code EXEC CICS HANDLE ABEND LABEL} at
     * {@code :862-864}.
     * <p>Verified body: {@code IF ABEND-MSG EQUAL LOW-VALUES} substitute
     * {@code 'UNEXPECTED ABEND OCCURRED.'} - trailing period included;
     * {@code MOVE LIT-THISPGM TO ABEND-CULPRIT}; {@code EXEC CICS SEND FROM(ABEND-DATA) ... }
     * <strong>{@code ERASE}</strong> {@code NOHANDLE} - this program adds {@code ERASE}, which its
     * sibling does not; {@code EXEC CICS HANDLE ABEND CANCEL}; and
     * {@code EXEC CICS ABEND ABCODE('9999')}.</p>
     * <p>The payload is the four abend work-area fields of {@code app/cpy/CSMSG02Y.cpy} - internally
     * titled {@code CABENDD.CPY} - namely {@code ABEND-CODE PIC X(4)},
     * {@code ABEND-CULPRIT PIC X(8)}, {@code ABEND-REASON PIC X(50)} and
     * {@code ABEND-MSG PIC X(72)}, one hundred and thirty-four bytes, all {@code VALUE SPACES}.</p>
     * <p><strong>Two distinct abend identifiers exist in this one program and both are carried.</strong>
     * The CABENDD payload field {@code ABEND-CODE} is set to {@code '0001'} by
     * {@code 2000-DECIDE-ACTION}'s {@code WHEN OTHER} at {@code :2635}, together with
     * {@code ABEND-MSG = 'UNEXPECTED DATA SCENARIO'} at {@code :2637-2638}. The terminal CICS abend code
     * is {@code '9999'}, four characters exactly filling {@code PIC X(4)}. Neither is the batch
     * {@code 999} with return code {@code 12}: {@code FatalProcessingException} already declares those
     * for the {@code CEE3ABD} path in {@code CBTRN02C}, they are not redeclared or repurposed here, and an
     * online path has <strong>no process return code</strong> at all.</p>
     * <p>This method <strong>returns</strong> the exception rather than throwing it, matching the
     * convention its sibling in this package established, so that the caller decides whether to throw or
     * to record. The terminal send and the abend-handler cancel have no stateless counterpart.</p>
     * @param context the per-invocation state carrier
     * @param cause the unexpected throwable that triggered the abend, or {@code null} for a deliberate
     *              abend raised by {@code 2000-DECIDE-ACTION}
     * @return the fatal exception carrying the four abend work-area fields; never {@code null}
     */
    private FatalProcessingException abendRoutine(final UpdateContext context, final Throwable cause) {
        // :4205-4207 substitute the default message when none was supplied
        if (isBlankOrLowValues(context.abendMessage)) {
            context.abendMessage = FatalProcessingException.DEFAULT_ABEND_MESSAGE;
        }
        // :4209 MOVE LIT-THISPGM TO ABEND-CULPRIT
        context.abendCulprit = PROGRAM_NAME;
        // :4211-4218 EXEC CICS SEND FROM(ABEND-DATA) ... ERASE NOHANDLE, then HANDLE ABEND CANCEL.
        // Terminal I/O and handler cancellation have no stateless counterpart.
        // :4220-4222 EXEC CICS ABEND ABCODE('9999') - the terminal code, distinct from ABEND-CODE.
        // Resolved BEFORE the diagnostic is written, and this ordering is the point.
        //
        // FINDING, severity Informational - remediated here. The diagnostic used to be written first and
        // to print the raw work-area field, so an abend that never moved a value into ABEND-CODE logged
        // 'CAUP abend: code=null culprit=COACTUPC' while the response the same request received reported
        // 9999. An operator correlating the two had no way to tell they were the same event, and a null
        // where a four-character code belongs reads like a second, separate defect. The substitution the
        // next three lines perform is exactly what the caller is told, so reporting the substituted value
        // is reporting the truth; the raw field carried no information to lose, because the only value it
        // can hold at this point is the blank the substitution replaces.
        final String payloadCode = isBlankOrLowValues(context.abendCode)
                ? TERMINAL_ABEND_CODE
                : context.abendCode;
        LOG.error("CAUP abend: code={} culprit={} reason={} message={}",
                payloadCode, context.abendCulprit, context.abendReason, context.abendMessage);
        // The payload order is (code, culprit, reason, message), matching CABENDD.CPY's own field order.
        if (cause == null) {
            return new FatalProcessingException(payloadCode, context.abendCulprit,
                    context.abendReason, context.abendMessage);
        }
        return new FatalProcessingException(payloadCode, context.abendCulprit,
                context.abendReason, context.abendMessage, cause);
    }

    /**
     * {@code app/cbl/COACTUPC.cbl}, paragraph {@code ABEND-ROUTINE-EXIT.}, logical lines
     * {@code :4226-4228}. A bare {@code EXIT}. Unlike its sibling program, this one <strong>does</strong>
     * declare an exit label for the abend routine, so it is mapped. A tracked, cited no-op.
     */
    private void abendRoutineExit() {
        // EXIT - intentional no-op preserved for control-flow parity with :4226-4228.
    }


    // ------------------------------------------------------------------------------------------------
    // Private helpers. None of these corresponds to a source paragraph, so none of them consumes any of
    // the 87 mapped label methods. They exist because COBOL expresses at the language level - fixed-width
    // MOVE semantics, LOW-VALUES, reference modification, FUNCTION NUMVAL-C, edited pictures - what Java
    // has to express as a method call. Every one of them cites the source construct it reproduces.
    // ------------------------------------------------------------------------------------------------

    /**
     * Reproduces a COBOL {@code MOVE} of an alphanumeric field into a fixed-width receiving field: the
     * value is truncated on the right when it is too long and space-padded on the right when it is too
     * short. {@code null} - the Java stand-in for {@code LOW-VALUES} in a received screen field - yields
     * an all-space field, because {@code INITIALIZE} of an alphanumeric group sets it to spaces.
     *
     * @param value the sending field, possibly {@code null}
     * @param width the receiving field's {@code PIC X(n)} width; must be positive
     * @return exactly {@code width} characters; never {@code null}
     */
    private static String moveAlphanumeric(final String value, final int width) {
        if (value == null) {
            return " ".repeat(width);
        }
        if (value.length() >= width) {
            return value.substring(0, width);
        }
        return value + " ".repeat(width - value.length());
    }

    /**
     * Reproduces COBOL reference modification of the form {@code WS-FIELD (1:n)} as used throughout the
     * edit routines, for example at {@code app/cbl/COACTUPC.cbl:1833}. The field is first widened or
     * truncated to its declared width and then the leading {@code length} characters are taken, so a
     * short input is padded rather than throwing.
     *
     * @param value the sending field, possibly {@code null}
     * @param length the reference-modification length; a non-positive value yields an empty string
     * @return the requested slice, space padded when the input was shorter; never {@code null}
     */
    private static String reference(final String value, final int length) {
        if (length <= 0) {
            return "";
        }
        return moveAlphanumeric(value, length);
    }

    /**
     * Right-pads a value to the given width without truncating, used where the source compares or
     * concatenates a field at its declared width - for example the eleven-character account key inside the
     * {@code STRING} at {@code app/cbl/COACTUPC.cbl:3676-3684}.
     *
     * @param value the value to pad, possibly {@code null}
     * @param width the target width
     * @return the value padded on the right with spaces; longer values are returned unchanged
     */
    private static String padRight(final String value, final int width) {
        final String source = value == null ? "" : value;
        if (source.length() >= width) {
            return source;
        }
        return source + " ".repeat(width - source.length());
    }

    /**
     * Builds a run of ASCII zero characters, reproducing {@code MOVE ZEROES TO} a display-usage numeric
     * field - for example {@code app/cbl/COACTUPC.cbl:2628-2630}, where the account and card identifiers
     * are zeroed before control returns to the menu.
     *
     * @param width the field width
     * @return exactly {@code width} {@code '0'} characters; never {@code null}
     */
    private static String zeroes(final int width) {
        return "0".repeat(Math.max(0, width));
    }

    /**
     * Reproduces the received-field convention of {@code 1100-RECEIVE-MAP}: a screen field that the
     * terminal did not transmit arrives as {@code LOW-VALUES}, which the source then tests with
     * {@code IF ... = LOW-VALUES OR SPACES}. The Java equivalent normalises the absent case to
     * {@code null} so the downstream blank tests have a single representation to look for, and trims
     * nothing - trailing spaces are significant to the fixed-width comparisons in {@code 9700}.
     *
     * @param value the raw screen field as it arrived on the request, possibly {@code null}
     * @return the value unchanged, or {@code null} when it was absent or entirely {@code NUL} bytes
     */
    private static String receiveField(final String value) {
        if (value == null || isLowValues(value)) {
            return null;
        }
        return value;
    }

    /**
     * Tests for COBOL {@code LOW-VALUES}: a {@code null} reference, or a non-empty value composed entirely
     * of {@code NUL} characters. A JSON client that faithfully echoes an untransmitted field sends either
     * shape, and both must read as "not supplied".
     *
     * <p><strong>A zero-length string is deliberately excluded.</strong> The empty string is the wire
     * spelling of a field the operator <em>cleared</em>, which in a fixed-width symbolic map arrives as
     * {@code SPACES}, not as {@code LOW-VALUES} - and {@link #isSpaces(String)} already reports it as such,
     * so {@link #isBlankOrLowValues(String)} and every edit routine that depends on it keep their
     * behaviour unchanged. Treating {@code ""} as {@code LOW-VALUES} here made {@link #receiveField(String)}
     * answer {@code null}, and a {@code null} reaching a {@code PIC X(n)} entity setter is rejected as a
     * {@code NOT NULL} violation - so clearing an unedited optional field such as {@code AADDGRPI},
     * {@code ACSADL2I} or {@code ACSGOVTI} abended instead of blanking the column, even though every one of
     * the fifty seeded accounts legitimately stores an all-blank {@code ACCT-GROUP-ID}. The NUL loop below
     * is vacuously satisfied by an empty string, which is why the length test is explicit rather than
     * implied.
     *
     * @param value the value to test, possibly {@code null}
     * @return {@code true} when the value stands for {@code LOW-VALUES}
     */
    private static boolean isLowValues(final String value) {
        if (value == null) {
            return true;
        }
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != '\u0000') {
                return false;
            }
        }
        return true;
    }

    /**
     * Tests for COBOL {@code SPACES}. An empty string counts, because a zero-length JSON string and an
     * all-blank fixed-width field are indistinguishable once the field has been received.
     *
     * @param value the value to test, possibly {@code null}
     * @return {@code true} when the value is non-{@code null} and contains only blanks
     */
    private static boolean isSpaces(final String value) {
        return value != null && value.isBlank();
    }

    /**
     * The composite blank test the edit routines use, for example
     * {@code IF WS-EDIT-ALPHANUM-ONLY (1:WS-EDIT-ALPHANUM-LENGTH) EQUAL SPACES OR LOW-VALUES} at
     * {@code app/cbl/COACTUPC.cbl:1826-1840}.
     *
     * @param value the value to test, possibly {@code null}
     * @return {@code true} when the value is {@code LOW-VALUES} or {@code SPACES}
     */
    private static boolean isBlankOrLowValues(final String value) {
        return isLowValues(value) || isSpaces(value);
    }

    /**
     * Tests for a field of ASCII zero characters, the third arm of the blank test in
     * {@code 1220-EDIT-YESNO} at {@code app/cbl/COACTUPC.cbl:1859-1861}, which adds {@code OR ZEROS} to
     * the usual low-values and spaces pair. A blank field is <strong>not</strong> zeroes, so this test is
     * deliberately narrower than {@link #isNumericallyZero(String)}.
     *
     * @param value the value to test, possibly {@code null}
     * @return {@code true} when the value is non-empty and every character is {@code '0'}
     */
    private static boolean isAllZeroCharacters(final String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != '0') {
                return false;
            }
        }
        return true;
    }

    /**
     * Tests whether a display-usage numeric field evaluates to zero, reproducing
     * {@code IF CC-ACCT-ID EQUAL ZEROS} at {@code app/cbl/COACTUPC.cbl:1789}. Leading and trailing blanks
     * are tolerated because the screen field is space padded, but a wholly blank field is not zero - the
     * source reaches this test only after the blank test has already passed.
     *
     * @param value the value to test, possibly {@code null}
     * @return {@code true} when every non-blank character is {@code '0'} and at least one digit is present
     */
    private static boolean isNumericallyZero(final String value) {
        if (value == null) {
            return false;
        }
        final String trimmed = value.trim();
        return isAllZeroCharacters(trimmed);
    }

    /**
     * Reproduces {@code IF ... IS NUMERIC} against a field of a declared width, as used by
     * {@code 1245-EDIT-NUM-REQD} at {@code app/cbl/COACTUPC.cbl:2121-2139}. A COBOL display-usage numeric
     * field is numeric only when every one of its {@code n} positions holds a digit, so a short value is
     * padded to the declared width first and the padding then fails the digit test - which is exactly the
     * legacy outcome for a partially typed number.
     *
     * @param value the value to test, possibly {@code null}
     * @param width the declared field width
     * @return {@code true} when all {@code width} positions hold ASCII digits
     */
    private static boolean isAllDigits(final String value, final int width) {
        if (value == null || width <= 0) {
            return false;
        }
        final String field = padRight(value, width);
        if (field.length() > width) {
            return false;
        }
        for (int index = 0; index < width; index++) {
            final char character = field.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Reproduces the alphabetic-and-space class test of {@code 1225-EDIT-ALPHA-REQD} at
     * {@code app/cbl/COACTUPC.cbl:1917-1943}, which inspects the field for characters outside
     * {@code ALPHABET} plus the space. Case is irrelevant to the class test, matching the source's use of
     * both alphabet halves.
     *
     * @param value the value to test, possibly {@code null}
     * @return {@code true} when every character is a Latin letter or a space
     */
    private static boolean containsOnlyLettersAndSpaces(final String value) {
        if (value == null) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            final boolean letter = (character >= 'A' && character <= 'Z')
                    || (character >= 'a' && character <= 'z');
            if (!letter && character != ' ') {
                return false;
            }
        }
        return true;
    }

    /**
     * Reproduces the alphanumeric-and-space class test of {@code 1230-EDIT-ALPHANUM-REQD} at
     * {@code app/cbl/COACTUPC.cbl:1974-2000} and {@code 1240-EDIT-ALPHANUM-OPT} at {@code :2078-2100}.
     * <p>Both of those paragraphs are dead in the source - defects D12 and D13 - because neither is ever
     * {@code PERFORM}ed. The class test is nonetheless implemented rather than stubbed, because the
     * paragraphs themselves are mapped and must behave correctly if they are ever reached.</p>
     *
     * @param value the value to test, possibly {@code null}
     * @return {@code true} when every character is a Latin letter, an ASCII digit or a space
     */
    private static boolean containsOnlyLettersDigitsAndSpaces(final String value) {
        if (value == null) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            final boolean letter = (character >= 'A' && character <= 'Z')
                    || (character >= 'a' && character <= 'z');
            final boolean digit = character >= '0' && character <= '9';
            if (!letter && !digit && character != ' ') {
                return false;
            }
        }
        return true;
    }

    /**
     * Reproduces the administratively invalid social security area-number tests of
     * {@code 1265-EDIT-US-SSN} at {@code app/cbl/COACTUPC.cbl:2454-2470}: the first three digits may not
     * be {@code 000}, may not be {@code 666} and may not fall in the {@code 900}-{@code 999} range.
     *
     * @param part1 the first three characters of the social security number, possibly {@code null}
     * @return {@code true} when the value parses to a digit run that the source rejects
     */
    private static boolean isAdministrativelyInvalidSsnPart1(final String part1) {
        final Integer area = parseUnsignedDigits(part1);
        if (area == null) {
            return false;
        }
        return area == SSN_PART1_INVALID_ZERO
                || area == SSN_PART1_INVALID_SIX_SIX_SIX
                || (area >= SSN_PART1_INVALID_RANGE_FROM && area <= SSN_PART1_INVALID_RANGE_TO);
    }

    /**
     * Parses an unsigned display-usage numeric field, returning {@code null} when the value is not a pure
     * digit run. This is the guarded form the source obtains from {@code IF ... IS NUMERIC} followed by a
     * {@code MOVE} into a numeric field; the two-step shape is preserved so that a non-numeric field never
     * reaches arithmetic.
     *
     * @param value the value to parse, possibly {@code null}
     * @return the parsed value, or {@code null} when the input is absent, blank or not all digits
     */
    private static Integer parseUnsignedDigits(final String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        for (int index = 0; index < trimmed.length(); index++) {
            final char character = trimmed.charAt(index);
            if (character < '0' || character > '9') {
                return null;
            }
        }
        return Integer.valueOf(trimmed);
    }

    /**
     * Parses a display-usage numeric key into the {@code Long} the repositories expect. Returns
     * {@code null} rather than throwing when the key is absent or non-numeric, because the source reaches
     * its {@code EXEC CICS READ} only after the corresponding edit has already set {@code INPUT-ERROR};
     * a {@code null} here therefore means "the read cannot be attempted", which the caller reports as a
     * not-found outcome rather than as an arithmetic failure.
     *
     * @param value the key as received, possibly {@code null}
     * @return the parsed key, or {@code null} when the value cannot be a key
     */
    private static Long parseKey(final String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        for (int index = 0; index < trimmed.length(); index++) {
            final char character = trimmed.charAt(index);
            if (character < '0' || character > '9') {
                return null;
            }
        }
        return Long.valueOf(trimmed);
    }

    /**
     * Renders a numeric key back into the zero-padded display form the screen and the COMMAREA carry, for
     * example {@code MOVE ACCT-ID TO CDEMO-ACCT-ID} where the receiving field is {@code PIC 9(11)}.
     *
     * @param value the key, possibly {@code null}
     * @param width the receiving field's digit count
     * @return the zero-padded rendering, or a run of zeroes when the key is {@code null}
     */
    private static String formatNumericKey(final Long value, final int width) {
        if (value == null) {
            return zeroes(width);
        }
        final String digits = Long.toString(value);
        if (digits.length() >= width) {
            return digits;
        }
        return "0".repeat(width - digits.length()) + digits;
    }


    /**
     * Reproduces {@code FUNCTION NUMVAL-C}, the currency-aware numeric conversion the source applies to
     * money fields - for example {@code COMPUTE WS-EDIT-SIGNED-9V2 = FUNCTION NUMVAL-C (...)} inside
     * {@code 1250-EDIT-SIGNED-9V2} at {@code app/cbl/COACTUPC.cbl:2196-2199}. The intrinsic tolerates a
     * leading or trailing sign, a currency symbol, thousands separators and surrounding blanks, and it is
     * deliberately <strong>not</strong> the same conversion the source uses for identifiers, which go
     * through the plain digit test of {@link #isAllDigits(String, int)}.
     * <p>Returning {@code null} rather than zero on an unconvertible value is what lets
     * {@code 1250-EDIT-SIGNED-9V2} distinguish "not a number" from "the number zero"; the source obtains
     * the same distinction from its preceding {@code IS NUMERIC} class test.</p>
     *
     * @param value the raw screen text, possibly {@code null}
     * @return the converted amount scaled to two decimals, or {@code null} when the text is not a number
     */
    private static BigDecimal numvalC(final String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        boolean negative = false;
        final StringBuilder digits = new StringBuilder(trimmed.length());
        for (int index = 0; index < trimmed.length(); index++) {
            final char character = trimmed.charAt(index);
            if (character == '-') {
                // NUMVAL-C accepts the sign in either position; a second sign is a conversion failure.
                if (negative || digits.length() > 0 && index != trimmed.length() - 1) {
                    return null;
                }
                negative = true;
            } else if (character == '+') {
                if (digits.length() > 0 && index != trimmed.length() - 1) {
                    return null;
                }
            } else if (character == ',' || character == '$' || character == ' ') {
                // Thousands separators, the currency symbol and embedded blanks are ignored, per NUMVAL-C.
                continue;
            } else if (character == '.') {
                if (digits.indexOf(".") >= 0) {
                    return null;
                }
                digits.append('.');
            } else if (character >= '0' && character <= '9') {
                digits.append(character);
            } else {
                return null;
            }
        }
        if (digits.length() == 0 || ".".contentEquals(digits)) {
            return null;
        }
        final BigDecimal magnitude = new BigDecimal(digits.toString());
        return scaleAmount(negative ? magnitude.negate() : magnitude);
    }

    /**
     * The guarded numeric image {@code 1100-RECEIVE-MAP} builds at
     * {@code app/cbl/COACTUPC.cbl:1136-1146} and its four siblings: the received text is converted when it
     * is a valid amount and left as zero when it is not, while the alphanumeric image keeps the raw text so
     * that {@code 1250-EDIT-SIGNED-9V2} can still reject it and name it in the message. Both halves are
     * reproduced - this method supplies the numeric half.
     *
     * @param value the raw screen text, possibly {@code null}
     * @return the converted amount, or {@code null} when the text is not a number
     */
    private static BigDecimal numericImage(final String value) {
        return numvalC(value);
    }

    /**
     * Applies the two-decimal scale every account money field carries. The account layout declares
     * {@code PIC S9(10)V99} for the current balance, both credit limits and both cycle accumulators
     * ({@code app/cpy/CVACT01Y.cpy}), which maps to {@code NUMERIC(12,2)}; {@link RoundingMode#HALF_EVEN}
     * is used wherever rounding can occur, per the migration's financial-arithmetic invariant.
     *
     * @param amount the amount to scale, possibly {@code null}
     * @return the amount at scale two, or {@code null} when the input was {@code null}
     */
    private static BigDecimal scaleAmount(final BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        return amount.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }

    /**
     * Renders an amount through the edited picture {@code WS-EDIT-CURRENCY-9-2-F PIC +ZZZ,ZZZ,ZZZ.99}
     * declared at {@code app/cbl/COACTUPC.cbl:371} and used by {@code 3203-SHOW-UPDATED-VALUES} and
     * {@code 3202-SHOW-ORIGINAL-VALUES}. The picture is fifteen characters wide with a mandatory leading
     * sign, zero-suppressed and comma-grouped integer digits and two fixed decimals, so {@code 194.00}
     * renders as {@code "+        194.00"} - right-aligned in the field with the sign in column one.
     * <p>The rendering is byte-exact because the parity gates compare screen output against the legacy
     * baseline. A {@code null} amount yields an all-blank field, matching an uninitialised edited field.</p>
     *
     * @param amount the amount to render, possibly {@code null}
     * @return exactly {@link #MONEY_DISPLAY_LENGTH} characters; never {@code null}
     */
    private static String formatCurrency(final BigDecimal amount) {
        if (amount == null) {
            return " ".repeat(MONEY_DISPLAY_LENGTH);
        }
        final BigDecimal scaled = scaleAmount(amount);
        final BigDecimal magnitude = scaled.abs();
        final String plain = magnitude.toPlainString();
        final int point = plain.indexOf('.');
        final String integerDigits = point < 0 ? plain : plain.substring(0, point);
        final String decimalDigits = point < 0 ? "00" : plain.substring(point + 1);
        // ZZZ,ZZZ,ZZZ suppresses leading zeroes and groups the remaining digits in threes from the right.
        final StringBuilder grouped = new StringBuilder(MONEY_DISPLAY_LENGTH);
        final String significant = integerDigits.length() > MONEY_INTEGER_DIGITS
                ? integerDigits.substring(integerDigits.length() - MONEY_INTEGER_DIGITS)
                : integerDigits;
        final String unpadded = significant.replaceFirst("^0+(?=.)", "");
        for (int index = 0; index < unpadded.length(); index++) {
            final int remaining = unpadded.length() - index;
            if (index > 0 && remaining % MONEY_GROUP_SIZE == 0) {
                grouped.append(',');
            }
            grouped.append(unpadded.charAt(index));
        }
        final String sign = scaled.signum() < 0 ? "-" : "+";
        final String body = grouped + "." + decimalDigits;
        final int padding = MONEY_DISPLAY_LENGTH - 1 - body.length();
        return sign + (padding > 0 ? " ".repeat(padding) : "") + body;
    }

    /**
     * Assembles the ten-character dashed date the record layouts carry, reproducing the {@code STRING}
     * statements at {@code app/cbl/COACTUPC.cbl:3976-3982} (open date), {@code :3984-3990} (expiry) and
     * {@code :3994-4000} (reissue), each of which is {@code STRING year '-' month '-' day DELIMITED BY
     * SIZE}. {@code ACCT-OPEN-DATE}, {@code ACCT-EXPIRAION-DATE} and {@code ACCT-REISSUE-DATE} are all
     * {@code PIC X(10)} text in {@code app/cpy/CVACT01Y.cpy} and are carried as {@code String}, never as a
     * temporal type.
     *
     * @param year the four-character year component, possibly {@code null}
     * @param month the two-character month component, possibly {@code null}
     * @param day the two-character day component, possibly {@code null}
     * @return exactly {@link #DATE_TEXT_LENGTH} characters; never {@code null}
     */
    private static String assembleDate(final String year, final String month, final String day) {
        final String assembled = moveAlphanumeric(year, DATE_YEAR_LENGTH)
                + "-"
                + moveAlphanumeric(month, DATE_PART_LENGTH)
                + "-"
                + moveAlphanumeric(day, DATE_PART_LENGTH);
        return moveAlphanumeric(assembled, DATE_TEXT_LENGTH);
    }

    /**
     * Assembles the eight-character separator-free date the <em>snapshot</em> carries. This is the compact
     * form proven by {@code 9500-STORE-FETCHED-DATA} at {@code app/cbl/COACTUPC.cbl:3857-3859}, where the
     * three date-of-birth components are moved into the contiguous subfields of
     * {@code ACUP-OLD-CUST-DOB-YYYY-MM-DD}, giving {@code 4 + 2 + 2 = 8} bytes with no separators - which
     * is precisely why {@code 9700} addresses that group at {@code (1:4)}, {@code (5:2)} and {@code (7:2)}
     * while addressing the live record at {@code (1:4)}, {@code (6:2)} and {@code (9:2)}.
     * <p>Also used by {@code 1205-COMPARE-OLD-NEW} to compare the newly typed date components against the
     * snapshot on equal terms.</p>
     *
     * @param year the four-character year component, possibly {@code null}
     * @param month the two-character month component, possibly {@code null}
     * @param day the two-character day component, possibly {@code null}
     * @return exactly {@link #COMPACT_DATE_LENGTH} characters; never {@code null}
     */
    private static String compactDate(final String year, final String month, final String day) {
        final String assembled = moveAlphanumeric(year, DATE_YEAR_LENGTH)
                + moveAlphanumeric(month, DATE_PART_LENGTH)
                + moveAlphanumeric(day, DATE_PART_LENGTH);
        return moveAlphanumeric(assembled, COMPACT_DATE_LENGTH);
    }

    /**
     * Extracts a component from a live, dash-separated date field by reference modification, reproducing
     * {@code ACCT-OPEN-DATE (1:4)}, {@code (6:2)} and {@code (9:2)} as they appear in
     * {@code 9500-STORE-FETCHED-DATA} at {@code app/cbl/COACTUPC.cbl:3832-3845} and in
     * {@code 9700-CHECK-CHANGE-IN-REC} at {@code :4127-4137}.
     * <p>The offsets are supplied by the caller as the zero-based {@link #DATE_YEAR_OFFSET},
     * {@link #DATE_MONTH_OFFSET} and {@link #DATE_DAY_OFFSET} constants rather than being derived here,
     * so that the one place the offsets differ - the snapshot side of the date-of-birth comparison - stays
     * visible at the call site instead of being hidden inside a helper.</p>
     *
     * @param date the live ten-character date, possibly {@code null}
     * @param offset the zero-based start offset of the component
     * @param length the component length
     * @return the component, space padded when the field was short; never {@code null}
     */
    private static String datePart(final String date, final int offset, final int length) {
        final String field = moveAlphanumeric(date, DATE_TEXT_LENGTH);
        if (offset < 0 || offset >= field.length()) {
            return " ".repeat(Math.max(0, length));
        }
        final int end = Math.min(field.length(), offset + length);
        return moveAlphanumeric(field.substring(offset, end), length);
    }

    /**
     * Assembles the fifteen-character telephone field the customer layout carries, reproducing the
     * {@code STRING '(' area ')' prefix '-' line DELIMITED BY SIZE} statements at
     * {@code app/cbl/COACTUPC.cbl:4027-4033} and {@code :4035-4041}. The assembled value occupies thirteen
     * of the fifteen declared positions, which is exactly the layout that
     * {@code 3202-SHOW-ORIGINAL-VALUES} slices back apart at offsets two, six and ten
     * ({@code :2846-2857}).
     *
     * @param areaCode the three-character area code, possibly {@code null}
     * @param prefix the three-character exchange prefix, possibly {@code null}
     * @param lineNumber the four-character line number, possibly {@code null}
     * @return exactly {@link #PHONE_NUMBER_LENGTH} characters; never {@code null}
     */
    private static String assemblePhoneNumber(final String areaCode,
                                              final String prefix,
                                              final String lineNumber) {
        final String assembled = "("
                + moveAlphanumeric(areaCode, PHONE_AREA_CODE_LENGTH)
                + ")"
                + moveAlphanumeric(prefix, PHONE_PREFIX_LENGTH)
                + "-"
                + moveAlphanumeric(lineNumber, PHONE_LINE_NUMBER_LENGTH);
        return moveAlphanumeric(assembled, PHONE_NUMBER_LENGTH);
    }

    /**
     * Assembles the nine-digit social security number from the three screen parts, reproducing
     * {@code app/cbl/COACTUPC.cbl:4044} where {@code ACUP-NEW-CUST-SSN} - itself the concatenation of the
     * three received parts - is moved into {@code CUST-UPDATE-SSN PIC 9(09)}.
     * <p>The value is <strong>personally identifiable</strong> and must never reach a log sink. It is
     * returned only for assignment into the entity and for the untransformed comparison at
     * {@code :4171}.</p>
     *
     * @param context the per-invocation state carrier; reads the three received parts
     * @return exactly {@link #SSN_LENGTH} characters; never {@code null}
     */
    private static String assembledSsn(final UpdateContext context) {
        final String assembled = moveAlphanumeric(context.newSsnPart1, SSN_PART1_LENGTH)
                + moveAlphanumeric(context.newSsnPart2, SSN_PART2_LENGTH)
                + moveAlphanumeric(context.newSsnPart3, SSN_PART3_LENGTH);
        return moveAlphanumeric(assembled, SSN_LENGTH);
    }

    /**
     * Compares two alphanumeric fields the way COBOL does: shorter operands are space padded to a common
     * width before the comparison, so {@code "AB"} and {@code "AB "} are equal. {@code LOW-VALUES} and
     * {@code SPACES} are also equal to one another under this rule, which matches the source's habit of
     * testing {@code EQUAL LOW-VALUES OR SPACES} interchangeably.
     *
     * @param left the first operand, possibly {@code null}
     * @param right the second operand, possibly {@code null}
     * @return {@code true} when the two fields compare equal
     */
    private static boolean equalText(final String left, final String right) {
        final String first = left == null ? "" : left;
        final String second = right == null ? "" : right;
        final int width = Math.max(first.length(), second.length());
        return padRight(first, width).equals(padRight(second, width));
    }

    /**
     * Compares two alphanumeric fields after {@code FUNCTION UPPER-CASE} is applied to both sides, the
     * exact shape of the nine customer clauses in {@code 9700-CHECK-CHANGE-IN-REC} at
     * {@code app/cbl/COACTUPC.cbl:4152-4167} and {@code :4172-4173}.
     * <p>{@link Locale#ROOT} is used deliberately: the default locale would fold {@code i} differently in
     * a Turkish locale and make the comparison environment dependent, which Rule 1 Clause A forbids. No
     * {@code FUNCTION TRIM} is applied, because {@code 9700} contains none - see
     * {@link #equalUpperCaseTrimmed(String, String)} for the {@code 1205} variant that does.</p>
     *
     * @param left the first operand, possibly {@code null}
     * @param right the second operand, possibly {@code null}
     * @return {@code true} when the two fields compare equal once both are upper cased
     */
    private static boolean equalUpperCase(final String left, final String right) {
        final String first = left == null ? "" : left.toUpperCase(Locale.ROOT);
        final String second = right == null ? "" : right.toUpperCase(Locale.ROOT);
        return equalText(first, second);
    }

    /**
     * Compares two alphanumeric fields after {@code FUNCTION LOWER-CASE} is applied to both sides, the
     * shape of the single account clause at {@code app/cbl/COACTUPC.cbl:4139-4140}:
     * {@code FUNCTION LOWER-CASE (ACCT-GROUP-ID) EQUAL FUNCTION LOWER-CASE (ACUP-OLD-GROUP-ID)}.
     * <p>This asymmetry - lower case here, upper case for the customer text fields, and no case function at
     * all for the remaining ten clauses - is <strong>deliberate source behaviour</strong> and is not
     * normalised in either direction, because normalising it changes which updates are accepted.</p>
     *
     * @param left the first operand, possibly {@code null}
     * @param right the second operand, possibly {@code null}
     * @return {@code true} when the two fields compare equal once both are lower cased
     */
    private static boolean equalLowerCase(final String left, final String right) {
        final String first = left == null ? "" : left.toLowerCase(Locale.ROOT);
        final String second = right == null ? "" : right.toLowerCase(Locale.ROOT);
        return equalText(first, second);
    }

    /**
     * Compares two alphanumeric fields after {@code FUNCTION UPPER-CASE (FUNCTION TRIM (...))} is applied
     * to both sides - the shape used throughout {@code 1205-COMPARE-OLD-NEW} at
     * {@code app/cbl/COACTUPC.cbl:1697-1766}, including the group identifier at {@code :1697-1700}.
     * <p>Note the contrast with {@code 9700}: the group identifier is upper cased <em>and</em> trimmed
     * here, but lower cased with <em>no</em> trim there. The two paragraphs answer different questions -
     * "did the user change anything on screen" versus "did someone else change it while we were out" - and
     * their case rules genuinely differ.</p>
     *
     * @param left the first operand, possibly {@code null}
     * @param right the second operand, possibly {@code null}
     * @return {@code true} when the two fields compare equal once both are trimmed and upper cased
     */
    private static boolean equalUpperCaseTrimmed(final String left, final String right) {
        final String first = left == null ? "" : left.trim().toUpperCase(Locale.ROOT);
        final String second = right == null ? "" : right.trim().toUpperCase(Locale.ROOT);
        return first.equals(second);
    }

    /**
     * Compares two monetary values by <strong>numeric value</strong> using
     * {@link BigDecimal#compareTo(BigDecimal)}, never {@link BigDecimal#equals(Object)}. This matters
     * directly at {@code app/cbl/COACTUPC.cbl:4117}, {@code :4119}, {@code :4121}, {@code :4123} and
     * {@code :4125}, where the live account amounts are compared against the snapshot: COBOL compares
     * packed and zoned decimals by value, so {@code 194.0} and {@code 194.00} are equal, whereas
     * {@code BigDecimal.equals} would call them different because their scales differ.
     *
     * @param left the first amount, possibly {@code null}
     * @param right the second amount, possibly {@code null}
     * @return {@code true} when both are {@code null} or both are numerically equal
     */
    private static boolean equalAmount(final BigDecimal left, final BigDecimal right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return left.compareTo(right) == 0;
    }

    /**
     * Compares two credit scores numerically. This is the single clause at
     * {@code app/cbl/COACTUPC.cbl:4186}, {@code CUST-FICO-CREDIT-SCORE EQUAL ACUP-OLD-CUST-FICO-SCORE},
     * and it is a <strong>numeric</strong> comparison rather than a text one because both operands are
     * {@code PIC 9(03)} - the snapshot side through the {@code REDEFINES} at {@code :845-847}'s customer
     * counterpart. A leading-zero difference such as {@code 720} against {@code 0720} therefore compares
     * equal, which a text comparison would get wrong.
     *
     * <p>Both operands arrive as the fixed three-byte display image that
     * {@code com.cardemo.model.entity.Customer} stores, so the numeric intent is honoured by parsing
     * rather than by declaring an integer member. When either side does not parse - which the
     * {@code PIC 9(03)} contract makes unreachable, and which is therefore implemented rather than
     * asserted - the comparison degrades to the fixed-width text comparison of
     * {@link #equalText(String, String)} rather than reporting a spurious change.</p>
     *
     * @param left the first score image, possibly {@code null}
     * @param right the second score image, possibly {@code null}
     * @return {@code true} when both are {@code null} or both hold the same value
     */
    private static boolean equalScore(final String left, final String right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        final Integer leftValue = parseUnsignedDigits(left);
        final Integer rightValue = parseUnsignedDigits(right);
        if (leftValue == null || rightValue == null) {
            return equalText(left, right);
        }
        return leftValue.intValue() == rightValue.intValue();
    }

    /**
     * Reproduces a COBOL {@code MOVE} between two unsigned display numerics of the same picture, such as
     * {@code MOVE ACUP-NEW-CUST-FICO-SCORE TO CUST-UPDATE-FICO-CREDIT-SCORE} at
     * {@code app/cbl/COACTUPC.cbl:4058-4059}, where both items are {@code PIC 9(03)}.
     *
     * <p>A numeric {@code MOVE} aligns on the decimal point, so it right-justifies and zero-fills rather
     * than left-justifying and space-filling the way {@link #moveAlphanumeric(String, int)} does for an
     * alphanumeric item. High-order digits beyond the receiving width are truncated, exactly as the
     * source truncates them.</p>
     *
     * <p>A value that is not a run of digits cannot be moved numerically. Rather than raise - the source
     * has no such path, because the edit routine has already set {@code INPUT-ERROR} for it - the raw
     * text is passed through at the declared width, so nothing is silently invented.</p>
     *
     * @param value the sending item, possibly {@code null}
     * @param width the receiving item's declared number of digits
     * @return the moved image at exactly {@code width} characters, or {@code null} when {@code value} is
     *     {@code null}, so that "no value" stays distinct from "the value zero"
     */
    private static String moveNumericText(final String value, final int width) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.strip();
        if (trimmed.isEmpty() || !isAllDigits(trimmed, trimmed.length())) {
            return moveAlphanumeric(value, width);
        }
        if (trimmed.length() >= width) {
            return trimmed.substring(trimmed.length() - width);
        }
        return "0".repeat(width - trimmed.length()) + trimmed;
    }

    /**
     * Translates a {@link DateValidationService.EditFlag} into this bean's field-error tri-state. The
     * translation exists because the three-byte group {@code MOVE}s at
     * {@code app/cbl/COACTUPC.cbl:1482}, {@code :1494}, {@code :1507}, {@code :1538} and {@code :1542}
     * copy the date service's per-component flags into this program's own flag group, one component at a
     * time - which is why there are twelve date tri-states here (four dates times year, month and day) and
     * not four.
     *
     * @param flag the flag the date service returned; must not be {@code null}
     * @return the equivalent field state; never {@code null}
     */
    private static FieldState toFieldState(final DateValidationService.EditFlag flag) {
        return switch (flag) {
            case ISVALID -> FieldState.VALID;
            case NOT_OK -> FieldState.NOT_OK;
            case BLANK -> FieldState.BLANK;
        };
    }

    /**
     * Tests whether a field state is one of the two the {@code CSSETATY} template reacts to. The template
     * body at {@code app/cpy/CSSETATY.cpy} opens
     * {@code IF (FLG-(TESTVAR1)-NOT-OK OR FLG-(TESTVAR1)-BLANK) AND CDEMO-PGM-REENTER}, so {@code VALID}
     * fields are left untouched while both failure states attract the red attribute.
     *
     * @param state the field state, possibly {@code null}
     * @return {@code true} when the state is {@code NOT_OK} or {@code BLANK}
     */
    private static boolean isErrored(final FieldState state) {
        return state == FieldState.NOT_OK || state == FieldState.BLANK;
    }


    /**
     * Tests the {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} condition declared at
     * {@code app/cbl/COACTUPC.cbl:480}. Every diagnostic in the program is stamped under
     * {@code IF WS-RETURN-MSG-OFF}, which makes the message latch <strong>first error wins</strong>: once a
     * field has claimed the seventy-five-byte message area, no later field overwrites it.
     *
     * @param context the per-invocation state carrier
     * @return {@code true} when no message has been claimed yet
     */
    private static boolean isReturnMessageOff(final UpdateContext context) {
        return context.returnMessage == null || context.returnMessage.isBlank();
    }

    /**
     * Assembles the labelled diagnostic the edit routines produce, reproducing
     * {@code STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME) '<suffix>' DELIMITED BY SIZE INTO WS-RETURN-MSG}
     * - the shape at {@code app/cbl/COACTUPC.cbl:1839-1844} and repeated in every edit paragraph.
     * <p>{@code FUNCTION TRIM} is applied to the label but <strong>not</strong> to the suffix, and the
     * suffix literals are hoisted with their own leading space or colon so that the concatenation is
     * byte-exact against the source. {@code STRING} does not blank-fill the remainder of the receiving
     * field, so no padding is applied here; the field was already {@code SPACES} - that is what
     * {@code WS-RETURN-MSG-OFF} asserted - and {@link #moveAlphanumeric(String, int)} pads it at the
     * projection boundary.</p>
     *
     * @param context the per-invocation state carrier; reads {@code editVariableName}
     * @param suffix the hoisted message suffix, already carrying its own separator
     * @return the assembled message truncated to the field width; never {@code null}
     */
    private static String labelledMessage(final UpdateContext context, final String suffix) {
        final String label = context.editVariableName == null
                ? ""
                : reference(context.editVariableName, EDIT_VARIABLE_NAME_LENGTH).trim();
        return truncateReturnMessage(label + suffix);
    }

    /**
     * Applies the {@code MOVE ... TO WS-RETURN-MSG} truncation. {@code WS-RETURN-MSG} is
     * {@code PIC X(75)} at {@code app/cbl/COACTUPC.cbl:479}, so anything longer - notably the eighty-byte
     * {@code WS-FILE-ERROR-MESSAGE} group and the {@code STRING}-assembled not-found diagnostics - loses
     * its low-order bytes. That truncation is observable, so it is reproduced rather than avoided.
     *
     * @param message the assembled message, possibly {@code null}
     * @return the message truncated to {@link #RETURN_MESSAGE_LENGTH}; never {@code null}
     */
    private static String truncateReturnMessage(final String message) {
        if (message == null) {
            return "";
        }
        if (message.length() <= RETURN_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, RETURN_MESSAGE_LENGTH);
    }

    /**
     * Renders a CICS response ordinal into {@code ERROR-RESP PIC X(10)}, reproducing
     * {@code MOVE WS-RESP-CD TO ERROR-RESP} at {@code app/cbl/COACTUPC.cbl:3672}, {@code :3691},
     * {@code :3721}, {@code :3740}, {@code :3770} and {@code :3789}.
     * <p>The sending field is {@code WS-RESP-CD PIC S9(09) COMP} ({@code :40}). Moving a signed binary
     * integer into an alphanumeric item drops the sign and treats the sender as nine unsigned digits, zero
     * filled on the left; the alphanumeric move then left-justifies those nine characters into the
     * ten-byte receiver and blank-fills the last position. A {@code RESP} of {@code 13}
     * ({@code DFHRESP(NOTFND)}) therefore renders as {@code "000000013 "}, and that exact image is what the
     * assembled diagnostic carries.</p>
     *
     * @param responseCode the response ordinal; the absolute value is rendered, matching the sign drop
     * @return exactly {@link #ERROR_RESPONSE_LENGTH} characters; never {@code null}
     */
    private static String renderResponseCode(final int responseCode) {
        final String digits = Integer.toString(Math.abs(responseCode));
        final String zeroFilled = digits.length() >= RESPONSE_CODE_DIGITS
                ? digits.substring(digits.length() - RESPONSE_CODE_DIGITS)
                : "0".repeat(RESPONSE_CODE_DIGITS - digits.length()) + digits;
        return moveAlphanumeric(zeroFilled, ERROR_RESPONSE_LENGTH);
    }

    /**
     * Assembles {@code WS-FILE-ERROR-MESSAGE}, the eighty-byte group declared at
     * {@code app/cbl/COACTUPC.cbl:389-408}: {@code 'File Error: '} (12) then {@code ERROR-OPNAME X(8)},
     * {@code ' on '} (4), {@code ERROR-FILE X(9)}, {@code ' returned RESP '} (15),
     * {@code ERROR-RESP X(10)}, {@code ',RESP2 '} (7), {@code ERROR-RESP2 X(10)} and five trailing blanks -
     * summing to exactly eighty. The {@code MOVE WS-FILE-ERROR-MESSAGE TO WS-RETURN-MSG} at {@code :3693},
     * {@code :3742} and {@code :3791} then truncates the low-order five bytes, and the callers apply that
     * truncation through {@link #truncateReturnMessage(String)}.
     * <p>Every component is blank padded to its declared width, because a fixed-width group is what the
     * three {@code WHEN OTHER} arms surface and a parity comparison examines the byte image. Note that
     * {@code ERROR-FILE} is {@code X(9)} while every {@code LIT-*FILENAME} literal is {@code X(8)}, so each
     * name carries one extra trailing blank - and {@code 'CXACAIX '} therefore renders with two.</p>
     * <p>Unlike the {@code STRING}-assembled not-found diagnostics, this message is stamped
     * <strong>outside</strong> the {@code IF WS-RETURN-MSG-OFF} latch, so it overwrites any pending
     * message. That asymmetry is source behaviour and is preserved at the call sites.</p>
     *
     * @param operation the attempted operation; always {@code READ} in this program
     * @param logicalFileName the CICS file or path name the operation used
     * @param responseCode the CICS {@code RESP} ordinal
     * @param reasonCode the CICS {@code RESP2} ordinal
     * @return the eighty-byte diagnostic; never {@code null}
     */
    private static String fileErrorMessage(final String operation,
                                           final String logicalFileName,
                                           final int responseCode,
                                           final int reasonCode) {
        return FILE_ERROR_PREFIX
                + padRight(operation, ERROR_OPERATION_LENGTH)
                + FILE_ERROR_ON
                + padRight(logicalFileName, ERROR_FILE_LENGTH)
                + FILE_ERROR_RETURNED_RESP
                + padRight(renderResponseCode(responseCode), ERROR_RESPONSE_LENGTH)
                + FILE_ERROR_RESP2
                + padRight(renderResponseCode(reasonCode), ERROR_RESPONSE_LENGTH)
                + " ".repeat(FILE_ERROR_TRAILING_BLANKS);
    }

    /**
     * Hands the file status to {@code FileStatusMapper}, which owns the status-to-exception decision and the
     * {@code FILE STATUS IS: NNNN} rendering; neither is reimplemented here. When a
     * {@link DataAccessException} produced the status it is passed as the cause, so the root cause is
     * preserved rather than swallowed.
     * <p>{@code DFHRESP(NOTOPEN)} - and therefore {@code FileUnavailableException} - has no locator in this
     * program: a repository-wide census of {@code NOTOPEN} in {@code app/cbl/COACTUPC.cbl} returns nothing.
     * The mapping is reachable only at runtime, through a file status of {@code '35'}, and is documented as
     * such rather than attributed to an invented line. The mapper's {@code '04'}-accepted carve-out is
     * scoped to the {@code CBSTM03B} call sites and is never reached from this bean.</p>
     * <p>Callers reach this method only on a failure arm, so a status the mapper regards as success would be
     * a contradiction. Rather than return {@code null} into a {@code retainFailure} call, that case is
     * reported as a {@code FatalProcessingException} carrying the abend payload the program itself would
     * have raised - nothing is swallowed and no branch returns silently.</p>
     *
     * @param context the per-invocation state carrier; supplies the abend culprit for the fallback
     * @param ioStatus the two-character file status
     * @param logicalFileName the CICS file or path name the operation used
     * @param operation the attempted operation, always {@code READ} on these paths
     * @param cause the originating data-access failure, or {@code null} when the status was synthesised
     * @return the typed exception the status maps to; never {@code null}
     */
    private CardDemoException classify(final UpdateContext context,
                                       final String ioStatus,
                                       final String logicalFileName,
                                       final String operation,
                                       final Throwable cause) {
        final Optional<CardDemoException> mapped = cause == null
                ? this.fileStatusMapper.toException(ioStatus, logicalFileName, operation)
                : this.fileStatusMapper.toException(ioStatus, logicalFileName, operation, cause);
        if (mapped.isPresent()) {
            return mapped.get();
        }
        // A success status on a failure arm cannot happen through any source path; surface it rather than
        // returning null, so the contradiction is diagnosable instead of becoming a NullPointerException.
        return new FatalProcessingException(ONLINE_ABEND_CODE,
                PROGRAM_NAME,
                padRight(logicalFileName, ERROR_FILE_LENGTH),
                truncateReturnMessage(fileErrorMessage(operation,
                        logicalFileName,
                        context.responseCode,
                        context.reasonCode)));
    }

    /**
     * Chooses between the authored lock-failure outcome and the ordinary file-status mapping for a
     * read-for-update that did not yield a row.
     *
     * <p>Not a paragraph of {@code app/cbl/COACTUPC.cbl}. The source has no such choice to make: a CICS
     * {@code READ ... UPDATE} that cannot take the lock and one that finds nothing both return a non-normal
     * {@code RESP}, and the guards at {@code :3907-3915} and {@code :3934-3942} set the same flag either
     * way. A relational store distinguishes them, and the two need different answers - a row held by
     * another transaction is worth retrying, a row that does not exist is not - so the distinction is
     * honoured rather than discarded.
     *
     * <p>{@code PessimisticLockingFailureException} is the whole family Spring translates a failed lock
     * acquisition into: {@code CannotAcquireLockException} for PostgreSQL's {@code lock_not_available}
     * ({@code SQLSTATE 55P03}, which is what the bounded {@code lock_timeout} on the datasource produces),
     * plus the deadlock and serialisation members. Anything else - a lost connection, a syntax fault, a
     * constraint - is not a lock problem and falls through to the supplier, so no genuine I/O failure is
     * relabelled as contention.
     *
     * <p>The outcome literals are the {@code 88}-level values of {@code :517-520} and are passed in by the
     * caller so that each guard keeps its own, byte for byte.
     *
     * @param cause    the throwable the read raised, or {@code null} when the read simply found nothing
     * @param outcome  the authored lock outcome for this guard
     * @param message  the legacy literal for this guard
     * @param fallback the ordinary file-status mapping, evaluated only when this was not a lock failure
     * @return the failure to retain, never {@code null}
     */
    private static CardDemoException lockAware(final Throwable cause,
                                               final ConcurrentUpdateException.Outcome outcome,
                                               final String message,
                                               final Supplier<CardDemoException> fallback) {
        if (cause instanceof PessimisticLockingFailureException) {
            return new ConcurrentUpdateException(outcome, message, cause);
        }
        return fallback.get();
    }


    /**
     * Retains the first typed failure of the request, mirroring the {@code IF WS-RETURN-MSG-OFF}
     * first-error-wins latch the read paragraphs apply to their messages. The legacy program cannot throw:
     * it keeps the message, sets {@code INPUT-ERROR} and carries on to redisplay the screen. A stateless
     * caller needs the typed exception as well, so it is retained here and rethrown by the public entry
     * points once the screen projection is complete. Nothing is discarded and no {@code catch} block is
     * empty.
     *
     * @param context the per-invocation state carrier
     * @param failure the typed failure to retain; the first one wins, exactly as the first message does
     */
    private static void retainFailure(final UpdateContext context, final CardDemoException failure) {
        if (context.pendingFailure == null) {
            context.pendingFailure = failure;
        }
    }

    /**
     * Builds the {@link ValidationException} a public entry point throws when
     * {@code 1200-EDIT-MAP-INPUTS} set {@code INPUT-ERROR} but no file status was involved. The message
     * carried is the one the screen would have displayed, so the caller sees exactly the legacy diagnostic.
     * <p>{@code WS-RETURN-MSG} is the single message area, so only the first failing field is named - which
     * is the legacy behaviour and not a loss of information: the remaining failing fields are still
     * surfaced individually through the per-field attribute list.</p>
     * <p>The field the exception carries is the one {@code 3009-SETUP-CURSOR-FIELD} resolved, because that
     * paragraph's whole purpose is to answer "which field is wrong" and it answers it in the source's own
     * evaluation order. Naming it turns the single message area into an addressable rejection without
     * inventing a field the source does not identify; when the cursor was never resolved - a write-path
     * {@code INPUT-ERROR} rather than an edit failure - the request as a whole is named instead.</p>
     *
     * @param context the per-invocation state carrier; reads the retained message and the resolved cursor
     * @return the typed validation failure; never {@code null}
     */
    private static ValidationException validationFailure(final UpdateContext context) {
        final String message = isReturnMessageOff(context)
                ? UNEXPECTED_DATA_SCENARIO_MESSAGE
                : context.returnMessage;
        final String field = context.cursorField == null ? REQUEST_FIELD : context.cursorField;
        // An ABSENT value and a WRONG one are two different refusals and the corpus keeps them apart:
        // app/cpy/CSSETATY.cpy emits '*' for FLG-(TESTVAR1)-BLANK but NOT for FLG-(TESTVAR1)-NOT-OK, so a
        // field the operator never filled in is marked differently on the screen from one they filled in
        // badly. Every blank arm of the cascade composes its diagnostic from the " must be supplied."
        // family - :2114-2133 for a required alphanumeric, :2184-2199 for a signed amount, and the three
        // telephone part suffixes - and no other arm uses that phrasing, so the latched message is what
        // carries the distinction out to the caller. Reporting every refusal as INVALID would tell a
        // caller who omitted a field that the value they did not send was wrong.
        return isUnsuppliedFieldMessage(message)
                ? ValidationException.missingField(field, message)
                : ValidationException.invalidField(field, message);
    }

    /**
     * Reports whether a latched diagnostic is one the cascade's <em>blank</em> arms compose, so that
     * {@link #validationFailure(UpdateContext)} can preserve the distinction
     * {@code app/cpy/CSSETATY.cpy} draws between {@code FLG-(TESTVAR1)-BLANK} and
     * {@code FLG-(TESTVAR1)-NOT-OK}.
     *
     * <p>Decided on the message rather than on {@code alphanumericState}, and deliberately: that field is
     * a per-field working variable which a later edit in the same cascade overwrites, whereas the message
     * is latched once under {@code IF WS-RETURN-MSG-OFF} and is therefore the one artefact that still
     * describes the <em>first</em> refusal at the point this method runs - which is the refusal the source
     * parks its cursor on.
     *
     * @param message the latched {@code WS-RETURN-MSG} text, possibly {@code null}
     * @return {@code true} when the message is one of the {@code " must be supplied."} family
     */
    private static boolean isUnsuppliedFieldMessage(final String message) {
        return message != null && message.contains(UNSUPPLIED_FIELD_PHRASE);
    }

    /**
     * Builds the {@link ConcurrentUpdateException} that surfaces
     * {@code SET DATA-WAS-CHANGED-BEFORE-UPDATE TO TRUE} from either {@code ELSE} branch of
     * {@code 9700-CHECK-CHANGE-IN-REC} ({@code app/cbl/COACTUPC.cbl:4142-4144} and {@code :4188-4190}).
     * <p>The outcome is {@code DATA_CHANGED_BEFORE_UPDATE}, whose text is the byte-exact
     * {@code 'Record changed by some one else. Please review'} literal declared at {@code :521-522} -
     * including the space in "some one". The marker the source then sets in
     * {@code 2000-DECIDE-ACTION} is {@code ACUP-SHOW-DETAILS} ({@code :2612}), so this is a
     * <strong>distinguishable</strong> outcome and not a generic conflict.</p>
     *
     * <p>The affected record is reported as the <em>logical file name</em> rather than as a key, matching
     * the contract of {@code com.cardemo.exception.ConcurrentUpdateException}, whose documentation names
     * {@code ACCTDAT} and {@code CUSTDAT} - the two literals declared at
     * {@code app/cbl/COACTUPC.cbl:573-576}. Naming the file is what makes the two {@code ELSE} branches
     * of {@code 9700} distinguishable to a caller: the account {@code IF} at {@code :4115-4145} fires
     * before the customer {@code IF} at {@code :4152-4191}, and the source's single shared message cannot
     * say which one it was.</p>
     *
     * <p>There is no {@code cause}: nothing threw. A field comparison found a difference, which is a
     * business outcome rather than a failure of the underlying store.</p>
     *
     * @param logicalFileName the eight-character CICS file name whose record differs, one of
     *     {@link #ACCOUNT_FILE_NAME} or {@link #CUSTOMER_FILE_NAME}
     * @return the typed concurrency failure; never {@code null}
     */
    private static ConcurrentUpdateException concurrentUpdateFailure(final String logicalFileName) {
        return new ConcurrentUpdateException(ConcurrentUpdateException.Outcome.DATA_CHANGED_BEFORE_UPDATE,
                DATA_WAS_CHANGED_BEFORE_UPDATE,
                logicalFileName,
                null);
    }

    /**
     * Delegates the composite date edit to {@code DateValidationService}, which owns the fourteen
     * {@code app/cpy/CSUTLDPY.cpy} labels ({@code EDIT-DATE-CCYYMMDD}, {@code EDIT-YEAR-CCYY},
     * {@code EDIT-MONTH}, {@code EDIT-DAY}, {@code EDIT-DAY-MONTH-YEAR}, {@code EDIT-DATE-LE},
     * {@code EDIT-DATE-OF-BIRTH} and their {@code -EXIT} counterparts). Those labels are mapped
     * <strong>once</strong>, in that service, and are deliberately <strong>not</strong> re-mapped here:
     * re-mapping would duplicate logic and would double-count in the scope-coverage gate.
     * <p>The source shape being reproduced is {@code MOVE ACUP-NEW-OPEN-DATE TO WS-EDIT-DATE-CCYYMMDD}
     * followed by {@code PERFORM EDIT-DATE-CCYYMMDD THRU EDIT-DATE-CCYYMMDD-EXIT} - see
     * {@code app/cbl/COACTUPC.cbl:1479-1481}, {@code :1491-1493}, {@code :1504-1506} and
     * {@code :1534-1537}. {@code WS-EDIT-DATE-CCYYMMDD} is the eight-character separator-free form, which
     * is why the three components are recombined by {@link #compactDate(String, String, String)} before
     * the call.</p>
     * <p>The copybook stamps {@code INPUT-ERROR} and {@code WS-RETURN-MSG} itself, under its own
     * {@code IF WS-RETURN-MSG-OFF} latch. Because the service returns rather than mutating shared state,
     * those two effects are propagated back into the context here - still honouring the latch, so the
     * first error continues to win.</p>
     *
     * @param context the per-invocation state carrier; reads {@code editVariableName}, may write
     *     {@code inputError} and {@code returnMessage}
     * @param year the four-character year component, possibly {@code null}
     * @param month the two-character month component, possibly {@code null}
     * @param day the two-character day component, possibly {@code null}
     * @return the per-component outcome the service produced; never {@code null}
     */
    private DateValidationService.EditOutcome editDateCcyymmdd(final UpdateContext context,
                                                               final String year,
                                                               final String month,
                                                               final String day) {
        final DateValidationService.EditOutcome outcome = this.dateValidationService.editDate(
                compactDate(year, month, day),
                reference(context.editVariableName, EDIT_VARIABLE_NAME_LENGTH));
        return propagateDateOutcome(context, outcome);
    }

    /**
     * Delegates the date-of-birth edit to {@code DateValidationService}. The source runs the composite edit
     * first and only then, {@code IF WS-EDIT-DT-OF-BIRTH-ISVALID}, the reasonableness check - see
     * {@code app/cbl/COACTUPC.cbl:1536-1543}. {@code DateValidationService.editDateOfBirth} performs that
     * same two-stage sequence internally, so the guard is expressed once, in the owning service, rather
     * than duplicated here.
     *
     * @param context the per-invocation state carrier; reads {@code editVariableName}, may write
     *     {@code inputError} and {@code returnMessage}
     * @param year the four-character year component, possibly {@code null}
     * @param month the two-character month component, possibly {@code null}
     * @param day the two-character day component, possibly {@code null}
     * @return the per-component outcome the service produced; never {@code null}
     */
    private DateValidationService.EditOutcome editDateOfBirth(final UpdateContext context,
                                                              final String year,
                                                              final String month,
                                                              final String day) {
        final DateValidationService.EditOutcome outcome = this.dateValidationService.editDateOfBirth(
                compactDate(year, month, day),
                reference(context.editVariableName, EDIT_VARIABLE_NAME_LENGTH));
        return propagateDateOutcome(context, outcome);
    }

    /**
     * Copies the two shared effects of a delegated date edit back into this program's work areas:
     * {@code SET INPUT-ERROR TO TRUE} and the latched {@code STRING ... INTO WS-RETURN-MSG}. In the source
     * both are side effects of the copied-in paragraphs, because {@code INPUT-ERROR} and
     * {@code WS-RETURN-MSG} live in this program's WORKING-STORAGE and {@code app/cpy/CSUTLDPY.cpy} is
     * expanded inline at {@code :4232}. With the labels owned by an injected bean, the effects travel back
     * as return values instead - and the {@code IF WS-RETURN-MSG-OFF} latch is re-applied here so that the
     * first error still wins across the whole edit sequence.
     *
     * @param context the per-invocation state carrier
     * @param outcome the outcome the date service produced; must not be {@code null}
     * @return {@code outcome} unchanged, so call sites read as a single expression
     */
    private static DateValidationService.EditOutcome propagateDateOutcome(
            final UpdateContext context,
            final DateValidationService.EditOutcome outcome) {
        if (outcome.inputError()) {
            context.inputError = true;
        }
        if (outcome.hasReturnMessage() && isReturnMessageOff(context)) {
            context.returnMessage = truncateReturnMessage(outcome.returnMessage());
        }
        return outcome;
    }

    /**
     * Applies one expansion of {@code COPY CSSETATY REPLACING}, the parameterised template at
     * {@code app/cpy/CSSETATY.cpy} that {@code 3300-SETUP-SCREEN-ATTRS} expands <strong>thirty-nine</strong>
     * times between {@code app/cbl/COACTUPC.cbl:3208} and {@code :3435}. The template body is:
     * <pre>
     * IF (FLG-(TESTVAR1)-NOT-OK OR FLG-(TESTVAR1)-BLANK) AND CDEMO-PGM-REENTER
     *    MOVE DFHRED TO (SCRNVAR2)C OF (MAPNAME3)O
     *    IF FLG-(TESTVAR1)-BLANK
     *       MOVE '*' TO (SCRNVAR2)O OF (MAPNAME3)O
     * </pre>
     * <p>The copybook is thirty lines with <strong>zero</strong> Area-A labels - it is an inline {@code IF}
     * fragment - so it contributes no mapped method of its own, and all thirty-nine expansions belong to
     * {@code 3300-SETUP-SCREEN-ATTRS}.</p>
     * <p>Two properties of the template drive the design here. First, the field-error state must be
     * <strong>tri-state</strong>: a single boolean is insufficient, because {@code BLANK} has to stay
     * distinguishable from {@code NOT_OK} - the {@code '*'} marker is emitted <em>only</em> for
     * {@code BLANK}, while both states attract {@code DFHRED}. Second, the {@code CDEMO-PGM-REENTER} gate
     * has no stateless equivalent, so the marker is emitted whenever the field state is {@code BLANK} on a
     * submitted request, which is precisely the set of turns on which the legacy flag was on.</p>
     *
     * @param context the per-invocation state carrier; the presentation lists are appended to
     * @param field the BMS symbolic-map field name the expansion names as {@code (SCRNVAR2)}
     * @param state the field's tri-state error state, possibly {@code null} for a field never edited
     */
    private static void applyFieldErrorAttribute(final UpdateContext context,
                                                 final String field,
                                                 final FieldState state) {
        if (!isErrored(state) || context.entryMode != EntryMode.REENTER) {
            return;
        }
        // MOVE DFHRED TO (SCRNVAR2)C OF CACTUPAO
        context.putColour(field, COLOUR_RED);
        if (state == FieldState.BLANK) {
            // MOVE '*' TO (SCRNVAR2)O OF CACTUPAO - emitted for BLANK only, never for NOT_OK
            context.putMarker(field, ASTERISK);
        }
    }

    /**
     * Builds the minimal request the read-only fetch entry point needs: only the account filter is
     * supplied, because {@code 0000-MAIN} reaches {@code 9000-READ-ACCT} through the
     * {@code ACUP-DETAILS-NOT-FETCHED} arm of {@code 2000-DECIDE-ACTION} ({@code :2568-2580}), where
     * {@code 1100-RECEIVE-MAP} has already returned at {@code :1060-1062} without reading any other field.
     * <p>Every remaining screen field is {@code null}, which is this bean's stand-in for the
     * {@code LOW-VALUES} an untransmitted field carries - see {@link #receiveField(String)}. The snapshot
     * and new-detail groups are {@code null} too, matching {@code INITIALIZE WS-THIS-PROGCOMMAREA} at
     * {@code :968}.</p>
     *
     * @param accountFilter the eleven-digit account key the caller is fetching, possibly {@code null}
     * @return a request carrying only the account filter; never {@code null}
     */
    private static AccountUpdateRequest emptyScreenRequest(final String accountFilter) {
        return new AccountUpdateRequest(
                null, null, null, null, null, null,
                accountFilter,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null);
    }


    // ------------------------------------------------------------------------------------------------
    // Nested types. These replace the COBOL constructs that have no Java counterpart at all: the
    // WORKING-STORAGE flag bytes, the symbolic map's output group, the COMMAREA and the pseudo-conversational
    // screen state. They are declared last, mirroring the layout of the sibling AccountViewService, so the
    // eighty-seven mapped label methods read in source order without interruption.
    // ------------------------------------------------------------------------------------------------

    /**
     * What the caller must do with the outcome, replacing the two mutually exclusive terminations of
     * {@code 0000-MAIN}: {@code EXEC CICS XCTL} at {@code app/cbl/COACTUPC.cbl:956-958}, which transfers
     * control away and never returns, versus {@code EXEC CICS RETURN TRANSID ... COMMAREA} at
     * {@code :1015-1019}, which redisplays this program's own map.
     */
    public enum ResponseKind {

        /**
         * {@code EXEC CICS RETURN TRANSID(LIT-THISTRANID)} at {@code app/cbl/COACTUPC.cbl:1015-1019}: the
         * account update screen is redisplayed, and {@link AccountUpdateResult#screen()} carries it.
         */
        MAP,

        /**
         * {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)} at {@code app/cbl/COACTUPC.cbl:956-958}: control
         * passes to another program, and {@link AccountUpdateResult#navigation()} names the target. No
         * screen is projected, because {@code XCTL} never returns to this program.
         */
        TRANSFER
    }

    /**
     * The three entry states {@code 0000-MAIN} distinguishes at {@code app/cbl/COACTUPC.cbl:880-893},
     * derived from {@code EIBCALEN}, {@code CDEMO-FROM-PROGRAM} and the
     * {@code 88 CDEMO-PGM-ENTER}/{@code CDEMO-PGM-REENTER} pair declared in {@code app/cpy/COCOM01Y.cpy}.
     */
    public enum EntryMode {

        /**
         * {@code IF EIBCALEN = 0} at {@code app/cbl/COACTUPC.cbl:880}: no COMMAREA was passed at all, so
         * the program is being started cold. Statelessly this is a request that carries no marker.
         */
        FIRST_ENTRY,

        /**
         * {@code SET CDEMO-PGM-ENTER TO TRUE} at {@code app/cbl/COACTUPC.cbl:885}: the screen has not yet
         * been displayed on this conversation, so nothing is received and nothing is edited.
         */
        ENTER,

        /**
         * {@code SET CDEMO-PGM-REENTER TO TRUE} at {@code app/cbl/COACTUPC.cbl:971} and {@code :987}: the
         * operator is submitting a screen this program previously sent. This is the state the
         * {@code COPY CSSETATY} expansions gate on - see
         * {@link AccountUpdateService#applyFieldErrorAttribute(UpdateContext, String, FieldState)}.
         */
        REENTER
    }

    /**
     * The attention identifier, after {@code app/cpy/CSSTRPFY.cpy}'s twenty-eight-arm
     * {@code EVALUATE TRUE} on {@code EIBAID} has folded it. The copybook maps {@code PF13} through
     * {@code PF24} back onto {@code PFK01} through {@code PFK12} - lines {@code :54-77} repeat the
     * assignments of {@code :30-53} - so exactly sixteen distinct outcomes exist, and the terminal keys
     * that the copybook has no arm for leave {@code CCARD-AID} at its {@code INITIALIZE}d value, modelled
     * here as {@code null}.
     */
    public enum AidKey {

        /** {@code WHEN EIBAID = DFHENTER} at {@code app/cpy/CSSTRPFY.cpy:22-23}. */
        ENTER,

        /** {@code WHEN EIBAID = DFHCLEAR} at {@code app/cpy/CSSTRPFY.cpy:24-25}. */
        CLEAR,

        /** {@code WHEN EIBAID = DFHPA1} at {@code app/cpy/CSSTRPFY.cpy:26-27}. */
        PA1,

        /** {@code WHEN EIBAID = DFHPA2} at {@code app/cpy/CSSTRPFY.cpy:28-29}. */
        PA2,

        /** {@code DFHPF1} at {@code app/cpy/CSSTRPFY.cpy:30-31}, or {@code DFHPF13} at {@code :54-55}. */
        PFK01,

        /** {@code DFHPF2} at {@code app/cpy/CSSTRPFY.cpy:32-33}, or {@code DFHPF14} at {@code :56-57}. */
        PFK02,

        /**
         * {@code DFHPF3} at {@code app/cpy/CSSTRPFY.cpy:34-35}, or {@code DFHPF15} at {@code :58-59}. This
         * is the exit key {@code 0000-MAIN} dispatches on at {@code app/cbl/COACTUPC.cbl:927}.
         */
        PFK03,

        /** {@code DFHPF4} at {@code app/cpy/CSSTRPFY.cpy:36-37}, or {@code DFHPF16} at {@code :60-61}. */
        PFK04,

        /**
         * {@code DFHPF5} at {@code app/cpy/CSSTRPFY.cpy:38-39}, or {@code DFHPF17} at {@code :62-63}. This
         * is the confirmation key that reaches the write at {@code app/cbl/COACTUPC.cbl:2602-2605}.
         */
        PFK05,

        /** {@code DFHPF6} at {@code app/cpy/CSSTRPFY.cpy:40-41}, or {@code DFHPF18} at {@code :64-65}. */
        PFK06,

        /** {@code DFHPF7} at {@code app/cpy/CSSTRPFY.cpy:42-43}, or {@code DFHPF19} at {@code :66-67}. */
        PFK07,

        /** {@code DFHPF8} at {@code app/cpy/CSSTRPFY.cpy:44-45}, or {@code DFHPF20} at {@code :68-69}. */
        PFK08,

        /** {@code DFHPF9} at {@code app/cpy/CSSTRPFY.cpy:46-47}, or {@code DFHPF21} at {@code :70-71}. */
        PFK09,

        /** {@code DFHPF10} at {@code app/cpy/CSSTRPFY.cpy:48-49}, or {@code DFHPF22} at {@code :72-73}. */
        PFK10,

        /** {@code DFHPF11} at {@code app/cpy/CSSTRPFY.cpy:50-51}, or {@code DFHPF23} at {@code :74-75}. */
        PFK11,

        /**
         * {@code DFHPF12} at {@code app/cpy/CSSTRPFY.cpy:52-53}, or {@code DFHPF24} at {@code :76-77}. The
         * cancel key, admitted by {@code app/cbl/COACTUPC.cbl:905-916} only when the details have already
         * been fetched.
         */
        PFK12
    }

    /**
     * The {@code ACUP-CHANGE-ACTION} marker declared at {@code app/cbl/COACTUPC.cbl:655-668}, which is the
     * one piece of conversation state the client must echo back. Every outcome below maps to its own
     * response, because collapsing them would destroy information the legacy screen displayed.
     * <p>The source stores the marker as a single character and layers six overlapping {@code 88} levels
     * over it: {@code ACUP-DETAILS-NOT-FETCHED VALUES LOW-VALUES, SPACES} ({@code :656-658}),
     * {@code ACUP-SHOW-DETAILS VALUE 'S'} ({@code :659}), {@code ACUP-CHANGES-MADE VALUES 'E','N','C','L','F'}
     * ({@code :660-662}), {@code ACUP-CHANGES-NOT-OK VALUE 'E'} ({@code :663}),
     * {@code ACUP-CHANGES-OK-NOT-CONFIRMED VALUE 'N'} ({@code :664}),
     * {@code ACUP-CHANGES-OKAYED-AND-DONE VALUE 'C'} ({@code :665}),
     * {@code ACUP-CHANGES-FAILED VALUES 'L','F'} ({@code :666}),
     * {@code ACUP-CHANGES-OKAYED-LOCK-ERROR VALUE 'L'} ({@code :667}) and
     * {@code ACUP-CHANGES-OKAYED-BUT-FAILED VALUE 'F'} ({@code :668}). The two set-valued conditions become
     * {@link #isChangesMade()} and {@link #isChangesFailed()} rather than separate constants, because they
     * are predicates over the marker and not markers in their own right.</p>
     */
    public enum ChangeAction {

        /**
         * {@code ACUP-DETAILS-NOT-FETCHED}, {@code app/cbl/COACTUPC.cbl:656-658}, whose values are
         * {@code LOW-VALUES} and {@code SPACES}. Nothing has been read yet, so the account filter is the
         * only meaningful field on the request.
         */
        DETAILS_NOT_FETCHED(' '),

        /**
         * {@code ACUP-SHOW-DETAILS VALUE 'S'}, {@code app/cbl/COACTUPC.cbl:659}. The record has been
         * fetched and is on display. This is also the marker a detected concurrent change lands on, set at
         * {@code :2612}.
         */
        SHOW_DETAILS('S'),

        /**
         * {@code ACUP-CHANGES-NOT-OK VALUE 'E'}, {@code app/cbl/COACTUPC.cbl:663}. At least one field
         * failed its edit, so the screen is redisplayed with the failing fields marked.
         */
        CHANGES_NOT_OK('E'),

        /**
         * {@code ACUP-CHANGES-OK-NOT-CONFIRMED VALUE 'N'}, {@code app/cbl/COACTUPC.cbl:664}. Every edit
         * passed and the operator is being asked to confirm. Only from this marker, and only with
         * {@link AidKey#PFK05}, is the write reachable - see {@code :2602-2605}.
         */
        CHANGES_OK_NOT_CONFIRMED('N'),

        /**
         * {@code ACUP-CHANGES-OKAYED-AND-DONE VALUE 'C'}, {@code app/cbl/COACTUPC.cbl:665}. The write committed.
         * <p><strong>BLOCKER:</strong> a customer read-for-update failure also lands here, because
         * {@code COULD-NOT-LOCK-CUST-FOR-UPDATE} is never tested by the post-write {@code EVALUATE} at
         * {@code :2606-2615} and therefore falls through {@code WHEN OTHER}. The preserved legacy defect is
         * documented in full on {@link AccountUpdateService} and in the {@code DECISION_LOG.md}.</p>
         */
        CHANGES_OKAYED_AND_DONE('C'),

        /**
         * {@code ACUP-CHANGES-OKAYED-LOCK-ERROR VALUE 'L'}, {@code app/cbl/COACTUPC.cbl:667}. The
         * <em>account</em> read-for-update failed, set at {@code :2608}. Nothing was written.
         */
        CHANGES_OKAYED_LOCK_ERROR('L'),

        /**
         * {@code ACUP-CHANGES-OKAYED-BUT-FAILED VALUE 'F'}, {@code app/cbl/COACTUPC.cbl:668}. A rewrite
         * failed, set at {@code :2610}. The single transaction boundary means nothing was left half
         * applied.
         */
        CHANGES_OKAYED_BUT_FAILED('F');

        /** The single character the source stores in {@code ACUP-CHANGE-ACTION}. */
        private final char marker;

        /**
         * Binds the constant to the single character the source stores.
         *
         * @param marker the byte {@code ACUP-CHANGE-ACTION} holds for this action
         */
        ChangeAction(final char marker) {
            this.marker = marker;
        }

        /**
         * The marker character, so a client can echo the exact byte the legacy COMMAREA carried.
         *
         * @return the {@code ACUP-CHANGE-ACTION} character for this outcome
         */
        public char marker() {
            return this.marker;
        }

        /**
         * The set-valued condition {@code 88 ACUP-CHANGES-MADE VALUES 'E','N','C','L','F'} declared at
         * {@code app/cbl/COACTUPC.cbl:660-662}. Tested at {@code :3671} and {@code :4379} to decide whether
         * the {@code PF12} legend is lit.
         *
         * @return {@code true} for every marker except {@code DETAILS_NOT_FETCHED} and {@code SHOW_DETAILS}
         */
        public boolean isChangesMade() {
            return this == CHANGES_NOT_OK
                    || this == CHANGES_OK_NOT_CONFIRMED
                    || this == CHANGES_OKAYED_AND_DONE
                    || this == CHANGES_OKAYED_LOCK_ERROR
                    || this == CHANGES_OKAYED_BUT_FAILED;
        }

        /**
         * The set-valued condition {@code 88 ACUP-CHANGES-FAILED VALUES 'L','F'} declared at
         * {@code app/cbl/COACTUPC.cbl:666}. Tested at {@code :980}, where it shares one body with
         * {@code WHEN ACUP-CHANGES-OKAYED-AND-DONE}.
         *
         * @return {@code true} for the two failure markers
         */
        public boolean isChangesFailed() {
            return this == CHANGES_OKAYED_LOCK_ERROR || this == CHANGES_OKAYED_BUT_FAILED;
        }
    }

    /**
     * The tri-state every {@code FLG-*} field carries. The values come straight from the source's own
     * three-way {@code 88} sets - for example {@code FLG-MANDATORY-ISVALID}, {@code FLG-MANDATORY-NOT-OK}
     * {@code VALUE '0'} and {@code FLG-MANDATORY-BLANK} {@code VALUE 'B'}.
     * <p>A single boolean per field would be <strong>insufficient</strong>: {@code app/cpy/CSSETATY.cpy}
     * emits the {@code '*'} marker for {@code BLANK} only, while both failure states attract
     * {@code DFHRED}, so {@code BLANK} must stay distinguishable from {@code NOT_OK}.</p>
     * <p>Note also that most {@code FLG-*-ISVALID} conditions are {@code VALUE LOW-VALUES}, which is why
     * {@code MOVE LOW-VALUES TO WS-NON-KEY-FLAGS} at {@code app/cbl/COACTUPC.cbl:1466} and {@code :2789}
     * sets every field to {@code VALID} rather than clearing it to an unknown state.</p>
     */
    public enum FieldState {

        /**
         * {@code FLG-*-ISVALID}, usually {@code VALUE LOW-VALUES}. The two exceptions are
         * {@code 88 FLG-YES-NO-ISVALID VALUES 'Y', 'N'} at {@code app/cbl/COACTUPC.cbl:78} and
         * {@code 88 FLG-ACCT-STATUS-ISVALID VALUES 'Y', 'N'} at {@code :193}, where the flag byte and the
         * data byte are the same field.
         */
        VALID,

        /** {@code FLG-*-NOT-OK}, {@code VALUE '0'}. The field was supplied but failed its edit. */
        NOT_OK,

        /**
         * {@code FLG-*-BLANK}, {@code VALUE 'B'}. The field was not supplied. This is the only state for
         * which {@code app/cpy/CSSETATY.cpy} emits the {@code '*'} marker.
         */
        BLANK
    }

    /**
     * One presentation instruction for one screen field, replacing a single
     * {@code MOVE <attribute> TO <field>A OF CACTUPAO} statement. The legacy program writes the attribute
     * byte, the colour byte and the {@code '*'} marker straight into the symbolic map's output group; a
     * JSON response has no such group, so the instructions are surfaced as a list and the caller performs
     * the rendering.
     * <p>Three aspects exist, matching the three distinct things the source writes. The
     * {@link #ASPECT_ATTRIBUTE} instructions come from {@code 3310-PROTECT-ALL-ATTRS} and
     * {@code 3320-UNPROTECT-FEW-ATTRS}; {@link #ASPECT_COLOUR} and {@link #ASPECT_MARKER} come from the
     * thirty-nine {@code COPY CSSETATY} expansions and from the account-filter block at
     * {@code app/cbl/COACTUPC.cbl:3171-3184}; {@link #ASPECT_CURSOR} comes from
     * {@code EXEC CICS SEND ... CURSOR} by way of the forty-four-arm cascade at {@code :3009-3167}.</p>
     *
     * @param field the BMS symbolic-map field name, without the {@code I}/{@code O}/{@code A}/{@code C}
     *     suffix that distinguishes the aspect - the aspect is carried separately
     * @param aspect one of {@link #ASPECT_ATTRIBUTE}, {@link #ASPECT_COLOUR}, {@link #ASPECT_MARKER} or
     *     {@link #ASPECT_CURSOR}
     * @param value the value the source moves: a {@code DFH*} symbol for the attribute and colour
     *     aspects, {@code "*"} for the marker aspect, and the field's own name for the cursor aspect
     */
    public record FieldAttribute(String field, String aspect, String value) {

        /** The attribute byte, written as {@code MOVE DFHBMPRF TO <field>A OF CACTUPAO} and its siblings. */
        public static final String ASPECT_ATTRIBUTE = "attribute";

        /** The colour byte, written as {@code MOVE DFHRED TO <field>C OF CACTUPAO}. */
        public static final String ASPECT_COLOUR = "colour";

        /** The {@code '*'} marker, written as {@code MOVE '*' TO <field>O OF CACTUPAO}. */
        public static final String ASPECT_MARKER = "marker";

        /** The cursor position, supplied to {@code EXEC CICS SEND ... CURSOR}. */
        public static final String ASPECT_CURSOR = "cursor";
    }

    /**
     * Where control goes next, replacing the six COMMAREA navigation fields of
     * {@code app/cpy/COCOM01Y.cpy}. {@code CDEMO-FROM-TRANID}, {@code CDEMO-TO-TRANID},
     * {@code CDEMO-FROM-PROGRAM} and {@code CDEMO-TO-PROGRAM} have no stateless equivalent as
     * <em>routing</em> - routing is URL based - so they are reported rather than obeyed, which is what lets
     * a caller reproduce the legacy screen flow without the server holding any session state.
     *
     * @param toTransactionId {@code CDEMO-TO-TRANID}, resolved with the {@code LOW-VALUES}/{@code SPACES}
     *     fallbacks at {@code app/cbl/COACTUPC.cbl:929-940}
     * @param toProgram {@code CDEMO-TO-PROGRAM}, resolved with the same fallbacks at {@code :942-945}
     * @param fromTransactionId {@code CDEMO-FROM-TRANID} as this program leaves it
     * @param fromProgram {@code CDEMO-FROM-PROGRAM} as this program leaves it
     * @param lastMapset {@code CDEMO-LAST-MAPSET}, recorded at {@code :949}
     * @param lastMap {@code CDEMO-LAST-MAP}, recorded at {@code :950}
     */
    public record Navigation(String toTransactionId,
                             String toProgram,
                             String fromTransactionId,
                             String fromProgram,
                             String lastMapset,
                             String lastMap) {
    }

    /**
     * The outcome of one invocation, replacing what the legacy program leaves in the COMMAREA and on the
     * 3270 screen at {@code EXEC CICS RETURN} or {@code EXEC CICS XCTL}.
     *
     * <p><strong>This type is the in-process contract and is never an HTTP response body.</strong> Three of
     * its members must not cross a wire. {@link #screen()} is the submitted map together with the snapshot
     * group, so it carries the social security number, the date of birth, the government-issued identifier,
     * both telephone numbers and the electronic funds account identifier. {@link #navigation()} describes a
     * CICS screen flow that a URL-routed target does not have. {@link #fieldAttributes()} carries
     * {@code DFHBMPRF} attribute bytes and a cursor position, which are 3270 presentation instructions. The
     * API-native projections are {@code com.cardemo.model.dto.AccountViewResponse} for a read and
     * {@code com.cardemo.model.dto.AccountUpdateResponse} for a write, and the operation builds them from
     * this record rather than serialising it.</p>
     *
     * @param responseKind whether the caller redisplays this program's map or transfers away
     * @param changeAction the {@code ACUP-CHANGE-ACTION} marker the caller must echo on its next turn;
     *     never {@code null}, and each value maps to its own documented response
     * @param screen the projected symbolic map, or {@code null} on a {@link ResponseKind#TRANSFER}
     *     outcome because {@code EXEC CICS XCTL} never returns to send one
     * @param navigation where control goes next; never {@code null}
     * @param fieldAttributes the per-field presentation instructions, in the order the source issues them;
     *     never {@code null}, and empty when no field needs marking
     * @param informationMessage {@code WS-INFO-MSG PIC X(40)} as {@code 3250-SETUP-INFOMSG} left it
     * @param errorMessage {@code WS-RETURN-MSG PIC X(75)} as {@code COMMON-RETURN} moved it into
     *     {@code CCARD-ERROR-MSG}
     */
    public record AccountUpdateResult(ResponseKind responseKind,
                                      ChangeAction changeAction,
                                      AccountUpdateRequest screen,
                                      Navigation navigation,
                                      List<FieldAttribute> fieldAttributes,
                                      String informationMessage,
                                      String errorMessage) {
    }

    // -----------------------------------------------------------------------------------------------
    // Snapshot projection support.
    //
    // ACUP-OLD-DETAILS at app/cbl/COACTUPC.cbl:669 is a group of DISPLAY-usage fields that the legacy
    // program keeps in its own COMMAREA half across the pseudo-conversation. The stateless target has no
    // such half, so 9500-STORE-FETCHED-DATA's snapshot has to travel back to the caller inside the
    // response and return on the caller's next turn - which is exactly why
    // com.cardemo.model.dto.AccountUpdateRequest carries an oldDetails group at all. These two helpers
    // encode the snapshot into that group's declared wire shapes. Neither corresponds to a source
    // paragraph, so neither consumes any of the 87 mapped methods.
    // -----------------------------------------------------------------------------------------------

    /**
     * Number of bytes in a {@code PIC S9(10)V99} zoned-decimal money image: ten integer digits plus two
     * decimal digits, the low-order digit of which carries the sign as an overpunch. Confirmed by
     * {@code 05 ACUP-OLD-CURR-BAL PIC X(12)} at {@code app/cbl/COACTUPC.cbl:675} redefined as
     * {@code ACUP-OLD-CURR-BAL-N PIC S9(10)V99} at {@code :676-677} - the same twelve bytes under two
     * names.
     */
    private static final int MONEY_ZONED_LENGTH = MONEY_INTEGER_DIGITS + MONEY_SCALE;

    /** Overpunch character for a positive value whose low-order digit is zero: {@code +0}. */
    private static final char OVERPUNCH_POSITIVE_ZERO = '{';

    /** Overpunch character for a negative value whose low-order digit is zero: {@code -0}. */
    private static final char OVERPUNCH_NEGATIVE_ZERO = '}';

    /** First overpunch character of the positive run, encoding {@code +1}; the run ends at {@code 'I'}. */
    private static final char OVERPUNCH_POSITIVE_FIRST = 'A';

    /** First overpunch character of the negative run, encoding {@code -1}; the run ends at {@code 'R'}. */
    private static final char OVERPUNCH_NEGATIVE_FIRST = 'J';

    /**
     * Encodes a scaled decimal amount into the twelve-byte zoned-decimal image with a trailing sign
     * overpunch that {@code com.cardemo.model.dto.AccountUpdateRequest.OldDetails} declares for every
     * money member, and that {@code app/data/ASCII/acctdata.txt} records on disk. This is the exact
     * inverse of that DTO's own decoder, and the pair round-trips.
     *
     * <p>The encoding reproduces what the legacy program's {@code MOVE ACCT-CURR-BAL TO
     * ACUP-OLD-CURR-BAL-N} at {@code app/cbl/COACTUPC.cbl:3821} leaves in storage. Because
     * {@code ACUP-OLD-CURR-BAL-N} is {@code PIC S9(10)V99} with {@code DISPLAY} usage, the twelve bytes
     * hold eleven ordinary digit characters followed by one character that encodes both the twelfth digit
     * and the sign. The mapping is {@code '&#123;'} for {@code +0}, {@code 'A'} through {@code 'I'} for
     * {@code +1} through {@code +9}, {@code '&#125;'} for {@code -0} and {@code 'J'} through {@code 'R'} for
     * {@code -1} through {@code -9}.</p>
     *
     * <p>Worked example, taken verbatim from the first seed account record
     * {@code app/data/ASCII/acctdata.txt:1}: a current balance of {@code +194.00} scales to an unscaled
     * value of {@code 19400}, left-pads to {@code 000000019400}, and its low-order digit {@code 0} with a
     * positive sign becomes {@code '&#123;'}, giving {@code 00000001940&#123;}.</p>
     *
     * <p>Over-long values are truncated on the left, keeping the low-order {@link #MONEY_ZONED_LENGTH}
     * digits, because that is what a COBOL {@code MOVE} into a shorter numeric field does. The truncation
     * is unreachable from this program's own data - every source field is itself
     * {@code S9(10)V99} - but is implemented rather than asserted so that no caller-supplied value can
     * produce a malformed image.</p>
     *
     * @param amount the amount to encode; may be {@code null}
     * @return the twelve-character zoned-decimal image, or {@code null} when {@code amount} is
     *     {@code null}, so that "no value" stays distinct from "the value zero"
     */
    private static String zonedDecimalImage(final BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        final BigDecimal scaled = amount.setScale(MONEY_SCALE, MONEY_ROUNDING);
        final boolean negative = scaled.signum() < 0;
        String digits = scaled.unscaledValue().abs().toString();
        if (digits.length() > MONEY_ZONED_LENGTH) {
            digits = digits.substring(digits.length() - MONEY_ZONED_LENGTH);
        } else if (digits.length() < MONEY_ZONED_LENGTH) {
            digits = "0".repeat(MONEY_ZONED_LENGTH - digits.length()) + digits;
        }
        final int lowOrder = digits.charAt(MONEY_ZONED_LENGTH - 1) - '0';
        final char overpunch;
        if (lowOrder == 0) {
            overpunch = negative ? OVERPUNCH_NEGATIVE_ZERO : OVERPUNCH_POSITIVE_ZERO;
        } else {
            final char runStart = negative ? OVERPUNCH_NEGATIVE_FIRST : OVERPUNCH_POSITIVE_FIRST;
            overpunch = (char) (runStart + lowOrder - 1);
        }
        return digits.substring(0, MONEY_ZONED_LENGTH - 1) + overpunch;
    }

    /**
     * Projects the snapshot that {@code 9500-STORE-FETCHED-DATA} stored into the wire shape of
     * {@code com.cardemo.model.dto.AccountUpdateRequest.OldDetails}, so that the caller can echo it
     * unaltered on its next turn and {@code 9700-CHECK-CHANGE-IN-REC} can compare against it.
     *
     * <p>Three encodings are load-bearing and are the reason this projection cannot be a field-for-field
     * copy:</p>
     * <ul>
     *   <li><strong>The four dates are emitted in their compact eight-character form</strong>, assembled
     *       by {@link #compactDate(String, String, String)}. This is not a convenience: the snapshot group
     *       declares {@code ACUP-OLD-CUST-DOB-YYYY-MM-DD PIC X(08)} at {@code app/cbl/COACTUPC.cbl:746}
     *       with {@code ACUP-OLD-CUST-DOB-PARTS REDEFINES} at {@code :747-751} splitting it
     *       {@code X(4)}, {@code X(2)}, {@code X(2)} - eight bytes with no separators - which is precisely
     *       why {@code 9700} addresses the snapshot at {@code (1:4)}, {@code (5:2)} and {@code (7:2)} while
     *       addressing the live, dash-separated record at {@code (1:4)}, {@code (6:2)} and {@code (9:2)}.
     *       Emitting the dashed form here would silently break the comparison at {@code :4174-4179} on
     *       every single request.</li>
     *   <li><strong>The five money members are emitted as twelve-byte zoned-decimal images</strong> by
     *       {@link #zonedDecimalImage(BigDecimal)}, matching {@code PIC X(12)} redefined as
     *       {@code PIC S9(10)V99} at {@code :675-677}.</li>
     *   <li><strong>The two telephone numbers are emitted in their punctuated fifteen-byte form</strong>,
     *       {@code (nnn)nnn-nnnn}, matching {@code ACUP-OLD-CUST-PHONE-NUM-1 PIC X(15)} at {@code :722}
     *       redefined at {@code :723-731} with {@code FILLER} bytes at positions one, five and nine. They
     *       are stored that way on the customer record, so the projection widens rather than reformats
     *       them.</li>
     * </ul>
     *
     * <p>Side effects: none. This method reads the context and allocates one value object.</p>
     *
     * <p><strong>Privacy:</strong> the returned object carries the date of birth, the social security
     * number, both telephone numbers, the government-issued identifier and the electronic funds account
     * identifier. It must never be logged, and no {@code toString} in this class exposes it.</p>
     *
     * @param context the per-invocation state carrier
     * @return the snapshot group, or {@code null} when {@code INITIALIZE ACUP-OLD-DETAILS} cleared the
     *     group and nothing was stored back into it - the state the program is in on its very first turn,
     *     when the account filter is the only field there is
     */
    private static AccountUpdateRequest.OldDetails projectOldDetails(final UpdateContext context) {
        if (context.snapshotAccountCleared && context.snapshotAccountId == null) {
            return null;
        }
        return new AccountUpdateRequest.OldDetails(
                context.snapshotAccountId,
                context.snapshotActiveStatus,
                zonedDecimalImage(context.snapshotCurrentBalance),
                zonedDecimalImage(context.snapshotCreditLimit),
                zonedDecimalImage(context.snapshotCashCreditLimit),
                compactDate(context.snapshotOpenYear, context.snapshotOpenMonth, context.snapshotOpenDay),
                // the snapshot subfield carries the copybook's own misspelling; never corrected
                compactDate(context.snapshotExpiraionYear,
                        context.snapshotExpiraionMonth,
                        context.snapshotExpiraionDay),
                compactDate(context.snapshotReissueYear,
                        context.snapshotReissueMonth,
                        context.snapshotReissueDay),
                zonedDecimalImage(context.snapshotCurrentCycleCredit),
                zonedDecimalImage(context.snapshotCurrentCycleDebit),
                context.snapshotGroupId,
                context.snapshotCustomerId,
                context.snapshotFirstName,
                context.snapshotMiddleName,
                context.snapshotLastName,
                context.snapshotAddressLine1,
                context.snapshotAddressLine2,
                context.snapshotAddressLine3,
                context.snapshotAddressStateCode,
                context.snapshotAddressCountryCode,
                context.snapshotAddressZip,
                moveAlphanumeric(context.snapshotPhoneNumber1, PHONE_NUMBER_LENGTH),
                moveAlphanumeric(context.snapshotPhoneNumber2, PHONE_NUMBER_LENGTH),
                context.snapshotSsn,
                context.snapshotGovernmentIssuedId,
                compactDate(context.snapshotDateOfBirthYear,
                        context.snapshotDateOfBirthMonth,
                        context.snapshotDateOfBirthDay),
                context.snapshotEftAccountId,
                context.snapshotPrimaryCardHolderIndicator,
                context.snapshotFicoScore);
    }

    /**
     * Restores the {@code ACUP-OLD-DETAILS} snapshot from the request, the exact inverse of
     * {@link #projectOldDetails(UpdateContext)}.
     * <p>This is the second half of the COMMAREA slice at {@code app/cbl/COACTUPC.cbl:890-892}:
     * {@code MOVE DFHCOMMAREA(LENGTH OF CARDDEMO-COMMAREA + 1 : LENGTH OF WS-THIS-PROGCOMMAREA) TO
     * WS-THIS-PROGCOMMAREA}. {@code 05 ACUP-OLD-DETAILS} at {@code :669} is a member of that second
     * half, so in the source it simply survives the pseudo-conversation inside the terminal's own
     * storage. There is no such storage here, which is why
     * {@code com.cardemo.model.dto.AccountUpdateRequest} carries an {@code oldDetails} group and why the
     * caller echoes it back on the confirmation turn.</p>
     * <p><strong>Why this is load-bearing.</strong> On the write turn
     * {@code 9500-STORE-FETCHED-DATA} does not run - the decider reaches
     * {@code 9600-WRITE-PROCESSING} from {@code :2602-2605} without a fresh read - so nothing else
     * populates the snapshot work area. Without this restore every clause of
     * {@code 9700-CHECK-CHANGE-IN-REC} would compare a populated live value against an unset snapshot,
     * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} would be set on every single request, and the endpoint
     * would be permanently unusable - the stateless analogue of the whole-string date-comparison trap
     * documented on {@link #checkChangeInRecord9700(UpdateContext)}.</p>
     * <p><strong>The decoders are chosen to reproduce the source's offsets exactly.</strong> The five
     * monetary members arrive as {@code PIC S9(10)V99} zoned-decimal images with a trailing overpunch
     * sign, so the group's own {@code currentBalanceAmount()}-style views decode them to
     * {@code BigDecimal} - no {@code float} or {@code double} is involved, and {@code :4117-4125}
     * compares them with {@code compareTo}. The four dates arrive in the separator-free eight-character
     * form, so the group's {@code openDateYear()}-style views slice them at COBOL offsets
     * {@code (1:4)}, {@code (5:2)} and {@code (7:2)}. That is deliberately <em>not</em> the
     * {@code (1:4)}, {@code (6:2)}, {@code (9:2)} slicing applied to the live dash-separated record: the
     * asymmetry at {@code :4174-4179} is realised precisely by using the two different decoders, and a
     * caller who supplies a dash-separated date here will correctly be told the record changed, because
     * {@code (5:2)} of {@code 1980-01-15} is {@code 0-} rather than {@code 01}.</p>
     * <p>{@code snapshotAccountCleared} is cleared last: the snapshot is now populated, so
     * {@link #projectOldDetails(UpdateContext)} must echo it back rather than return {@code null}.</p>
     * <p>No paragraph corresponds to this method - it is the stateless substitution for a storage
     * lifetime - so it consumes none of the eighty-seven mapped methods. It reads only the request and
     * writes only the context; nothing is logged, which matters because the group carries the date of
     * birth, the social security number, the government-issued identifier, both telephone numbers and
     * the electronic funds account identifier.</p>
     *
     * @param context the per-invocation state carrier; must carry a non-{@code null}
     *                {@code request.getOldDetails()}
     */
    private static void restoreSnapshotFromRequest(final UpdateContext context) {
        final AccountUpdateRequest.OldDetails old = context.authenticOldDetails;
        // :671-673 ACUP-OLD-ACCT-ID-X PIC X(11)
        context.snapshotAccountId = old.getAccountId();
        // :674 ACUP-OLD-ACTIVE-STATUS PIC X(01)
        context.snapshotActiveStatus = old.getActiveStatus();
        // :675-677, :678-680, :681-682 and the two cycle members: X(12) images -> S9(10)V99
        context.snapshotCurrentBalance = old.currentBalanceAmount();
        context.snapshotCreditLimit = old.creditLimitAmount();
        context.snapshotCashCreditLimit = old.cashCreditLimitAmount();
        context.snapshotCurrentCycleCredit = old.currentCycleCreditAmount();
        context.snapshotCurrentCycleDebit = old.currentCycleDebitAmount();
        // the three account dates as discrete components, matching :3832-3834, :3837-3839, :3843-3845
        context.snapshotOpenYear = old.openDateYear();
        context.snapshotOpenMonth = old.openDateMonth();
        context.snapshotOpenDay = old.openDateDay();
        // the copybook's own misspelling, carried through unchanged
        context.snapshotExpiraionYear = old.expiraionDateYear();
        context.snapshotExpiraionMonth = old.expiraionDateMonth();
        context.snapshotExpiraionDay = old.expiraionDateDay();
        context.snapshotReissueYear = old.reissueDateYear();
        context.snapshotReissueMonth = old.reissueDateMonth();
        context.snapshotReissueDay = old.reissueDateDay();
        // :3847 - compared at :4139-4140 through LOWER-CASE on both sides, with no TRIM
        context.snapshotGroupId = old.getGroupId();
        // :709 ACUP-OLD-CUST-DATA, the customer half
        context.snapshotCustomerId = old.getCustomerId();
        context.snapshotFirstName = old.getFirstName();
        context.snapshotMiddleName = old.getMiddleName();
        context.snapshotLastName = old.getLastName();
        context.snapshotAddressLine1 = old.getAddressLine1();
        context.snapshotAddressLine2 = old.getAddressLine2();
        context.snapshotAddressLine3 = old.getAddressLine3();
        context.snapshotAddressStateCode = old.getAddressStateCode();
        context.snapshotAddressCountryCode = old.getAddressCountryCode();
        context.snapshotAddressZip = old.getAddressZip();
        // :722-731 PIC X(15) whole-field members, compared at :4169-4170 with no case function
        context.snapshotPhoneNumber1 = old.getPhoneNumber1();
        context.snapshotPhoneNumber2 = old.getPhoneNumber2();
        // :742-744 PIC 9(09), compared at :4171 with no case function
        context.snapshotSsn = old.getSsn();
        context.snapshotGovernmentIssuedId = old.getGovernmentIssuedId();
        // :746-751 the compact eight-character group, sliced at (1:4) (5:2) (7:2) - see :4174-4179
        context.snapshotDateOfBirthYear = old.dateOfBirthYear();
        context.snapshotDateOfBirthMonth = old.dateOfBirthMonth();
        context.snapshotDateOfBirthDay = old.dateOfBirthDay();
        context.snapshotEftAccountId = old.getEftAccountId();
        context.snapshotPrimaryCardHolderIndicator = old.getPrimaryCardHolderIndicator();
        // :3861 - PIC 9(03) display text; :4186 compares it numerically
        context.snapshotFicoScore = old.getFicoScore();
        context.snapshotAccountCleared = false;
    }



    /**
     * The symbolic map buffer, replacing {@code CACTUPAO} - the output half of the {@code COACTUP}
     * symbolic map generated from {@code app/bms/COACTUP.bms} and declared in
     * {@code app/cpy-bms/COACTUP.CPY}.
     *
     * <p>Every field the legacy program can {@code MOVE} into the map before {@code EXEC CICS SEND MAP}
     * has one mutable {@code String} member here, and only those: fifty-one members, matching the
     * {@code MOVE ... TO <field>O OF CACTUPAO} sites in {@code 3100-SCREEN-INIT},
     * {@code 3200-SETUP-SCREEN-VARS}, {@code 3201-SHOW-INITIAL-VALUES},
     * {@code 3202-SHOW-ORIGINAL-VALUES}, {@code 3203-SHOW-UPDATED-VALUES} and
     * {@code 3250-SETUP-INFOMSG}. The corresponding <em>attribute</em> bytes - the {@code <field>A} and
     * {@code <field>C} aliases that {@code 3300-SETUP-SCREEN-ATTRS}, {@code 3310-PROTECT-ALL-ATTRS},
     * {@code 3320-UNPROTECT-FEW-ATTRS} and {@code 3390-SETUP-INFOMSG-ATTRS} manipulate - are deliberately
     * <em>not</em> members: they are emitted as an ordered list of {@link FieldAttribute} instructions
     * instead, because a REST response has no terminal buffer to poke and a caller needs to know the
     * order in which the instructions were issued.</p>
     *
     * <p>Members are package-private and mutable so that the twenty-four screen-building methods can
     * assign them exactly where the source performs its {@code MOVE}. The instance is created per
     * invocation - see {@link UpdateContext} - so there is no shared mutable state and no thread-safety
     * hazard on this singleton bean.</p>
     *
     * <p>All members are {@code String} without exception, including the five money fields and the
     * credit-score field. That is not a simplification: {@code CACTUPAO}'s data fields are
     * {@code PIC X(n)} in the generated symbolic map, the program edits them as text
     * ({@code 1250-EDIT-SIGNED-9V2} works on {@code WS-EDIT-SIGNED-NUMBER-9V2-X}), and
     * {@code 3203-SHOW-UPDATED-VALUES} echoes the raw received text back whenever the corresponding
     * {@code FLG-*} state is not valid. Modelling them as {@code BigDecimal} here would lose the
     * unparsable input the source is required to redisplay.</p>
     */
    private static final class ScreenBuffer {
        /**
         * Creates the work area with every member at its post-{@code INITIALIZE} value, which is the state
         * the legacy {@code WORKING-STORAGE SECTION} begins each task in. Declared explicitly rather than
         * left implicit so the surface is documented; it takes no argument and performs no work.
         */
        private ScreenBuffer() {
            // Every member carries its initial value in its own declaration above, exactly as a COBOL
            // VALUE clause does, so there is nothing for this constructor to assign.
        }

        /** {@code TRNNAME} - the four-character transaction identifier header field. */
        private String transactionName;

        /** {@code TITLE01} - the first forty-character title line, from {@code app/cpy/COTTL01Y.cpy}. */
        private String title01;

        /** {@code CURDATE} - the eight-character {@code MM/DD/YY} header date built in {@code 3100}. */
        private String currentDate;

        /** {@code PGMNAME} - the eight-character program-name header field. */
        private String programName;

        /** {@code TITLE02} - the second forty-character title line. */
        private String title02;

        /** {@code CURTIME} - the nine-character {@code HH:MM:SS} header time built in {@code 3100}. */
        private String currentTime;

        /** {@code ACCTSID} - the eleven-digit account filter, {@code ACCT-ID PIC 9(11)}. */
        private String accountId;

        /** {@code ACSTTUS} - {@code ACCT-ACTIVE-STATUS PIC X(1)}, the {@code 'Y'}/{@code 'N'} indicator. */
        private String accountStatus;

        /** {@code OPNYEAR} - the four-character year component of {@code ACCT-OPEN-DATE}. */
        private String openDateYear;

        /** {@code OPNMON} - the two-character month component of {@code ACCT-OPEN-DATE}. */
        private String openDateMonth;

        /** {@code OPNDAY} - the two-character day component of {@code ACCT-OPEN-DATE}. */
        private String openDateDay;

        /** {@code ACRDLIM} - {@code ACCT-CREDIT-LIMIT}, masked or echoed raw per {@code 3203}. */
        private String creditLimit;

        /** {@code EXPYEAR} - the four-character year component of {@code ACCT-EXPIRAION-DATE}. */
        private String expiryDateYear;

        /** {@code EXPMON} - the two-character month component of {@code ACCT-EXPIRAION-DATE}. */
        private String expiryDateMonth;

        /** {@code EXPDAY} - the two-character day component of {@code ACCT-EXPIRAION-DATE}. */
        private String expiryDateDay;

        /** {@code ACSHLIM} - {@code ACCT-CASH-CREDIT-LIMIT}, masked or echoed raw per {@code 3203}. */
        private String cashCreditLimit;

        /** {@code RISYEAR} - the four-character year component of {@code ACCT-REISSUE-DATE}. */
        private String reissueDateYear;

        /** {@code RISMON} - the two-character month component of {@code ACCT-REISSUE-DATE}. */
        private String reissueDateMonth;

        /** {@code RISDAY} - the two-character day component of {@code ACCT-REISSUE-DATE}. */
        private String reissueDateDay;

        /** {@code ACURBAL} - {@code ACCT-CURR-BAL}, masked or echoed raw per {@code 3203}. */
        private String currentBalance;

        /** {@code ACRCYCR} - {@code ACCT-CURR-CYC-CREDIT}, masked or echoed raw per {@code 3203}. */
        private String currentCycleCredit;

        /** {@code AADDGRP} - {@code ACCT-GROUP-ID PIC X(10)}. */
        private String accountGroupId;

        /** {@code ACRCYDB} - {@code ACCT-CURR-CYC-DEBIT}, masked or echoed raw per {@code 3203}. */
        private String currentCycleDebit;

        /** {@code ACSTNUM} - {@code CUST-ID PIC 9(09)}, protected again at {@code :3531}. */
        private String customerId;

        /** {@code ACTSSN1} - the three-character area part of {@code CUST-SSN}. */
        private String customerSsnPart1;

        /** {@code ACTSSN2} - the two-character group part of {@code CUST-SSN}. */
        private String customerSsnPart2;

        /** {@code ACTSSN3} - the four-character serial part of {@code CUST-SSN}. */
        private String customerSsnPart3;

        /** {@code DOBYEAR} - the four-character year component of {@code CUST-DOB-YYYY-MM-DD}. */
        private String dateOfBirthYear;

        /** {@code DOBMON} - the two-character month component of {@code CUST-DOB-YYYY-MM-DD}. */
        private String dateOfBirthMonth;

        /** {@code DOBDAY} - the two-character day component of {@code CUST-DOB-YYYY-MM-DD}. */
        private String dateOfBirthDay;

        /** {@code ACSTFCO} - {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}. */
        private String customerFicoScore;

        /** {@code ACSFNAM} - {@code CUST-FIRST-NAME PIC X(25)}. */
        private String customerFirstName;

        /** {@code ACSMNAM} - {@code CUST-MIDDLE-NAME PIC X(25)}. */
        private String customerMiddleName;

        /** {@code ACSLNAM} - {@code CUST-LAST-NAME PIC X(25)}. */
        private String customerLastName;

        /** {@code ACSADL1} - {@code CUST-ADDR-LINE-1 PIC X(50)}. */
        private String addressLine1;

        /** {@code ACSSTTE} - {@code CUST-ADDR-STATE-CD PIC X(2)}. */
        private String addressStateCode;

        /** {@code ACSADL2} - {@code CUST-ADDR-LINE-2 PIC X(50)}; received but never edited, see D10. */
        private String addressLine2;

        /** {@code ACSZIPC} - {@code CUST-ADDR-ZIP PIC X(10)}, shown five characters wide. */
        private String addressZip;

        /** {@code ACSCITY} - {@code CUST-ADDR-LINE-3 PIC X(50)}, the city line. */
        private String addressCity;

        /** {@code ACSCTRY} - {@code CUST-ADDR-COUNTRY-CD PIC X(3)}, re-protected at {@code :3547}. */
        private String addressCountryCode;

        /** {@code ACSPH1A} - the three-character area code of {@code CUST-PHONE-NUM-1}. */
        private String phone1AreaCode;

        /** {@code ACSPH1B} - the three-character prefix of {@code CUST-PHONE-NUM-1}. */
        private String phone1Prefix;

        /** {@code ACSPH1C} - the four-character line number of {@code CUST-PHONE-NUM-1}. */
        private String phone1LineNumber;

        /**
         * {@code ACSGOVT} - {@code CUST-GOVT-ISSUED-ID PIC X(20)}. Unprotected at {@code :3557} yet never
         * edited, which is the mechanism by which defect D10 becomes reachable.
         */
        private String governmentIssuedId;

        /** {@code ACSPH2A} - the three-character area code of {@code CUST-PHONE-NUM-2}. */
        private String phone2AreaCode;

        /** {@code ACSPH2B} - the three-character prefix of {@code CUST-PHONE-NUM-2}. */
        private String phone2Prefix;

        /** {@code ACSPH2C} - the four-character line number of {@code CUST-PHONE-NUM-2}. */
        private String phone2LineNumber;

        /** {@code ACSEFTC} - {@code CUST-EFT-ACCOUNT-ID PIC X(10)}. */
        private String eftAccountId;

        /** {@code ACSPFLG} - {@code CUST-PRI-CARD-HOLDER-IND PIC X(1)}. */
        private String primaryCardHolderIndicator;

        /**
         * {@code INFOMSG} - {@code WS-INFO-MSG PIC X(40)}, moved in by
         * {@code MOVE WS-INFO-MSG TO INFOMSGO OF CACTUPAO} at {@code app/cbl/COACTUPC.cbl:2979}.
         */
        private String informationMessage;

        /**
         * {@code ERRMSG} - {@code WS-RETURN-MSG PIC X(75)} as {@code COMMON-RETURN} moves it into
         * {@code CCARD-ERROR-MSG} at {@code app/cbl/COACTUPC.cbl:1008}.
         */
        private String errorMessage;

        /**
         * Projects this buffer plus the snapshot into the response DTO, replacing
         * {@code EXEC CICS SEND MAP MAPSET(LIT-THISMAPSET) MAP(LIT-THISMAP)} in
         * {@code 3400-SEND-SCREEN} at {@code app/cbl/COACTUPC.cbl:3591-3599}.
         *
         * <p>The fifty-six constructor arguments are supplied in the exact declaration order of
         * {@code com.cardemo.model.dto.AccountUpdateRequest}, which follows the field order of the
         * generated symbolic map rather than any logical grouping - hence the apparent oddities of
         * {@code accountGroupId} sitting between the two cycle amounts and {@code governmentIssuedId}
         * sitting between the two telephone numbers. Both reproduce the screen layout in
         * {@code app/bms/COACTUP.bms}.</p>
         *
         * <p>Four arguments are deliberately {@code null}:</p>
         * <ul>
         *   <li>{@code functionKeys}, {@code functionKey05} and {@code functionKey12} - the source never
         *       performs a data {@code MOVE} into {@code FKEY05} or {@code FKEY12}. A repository-wide
         *       census of this program finds only <em>attribute</em> moves,
         *       {@code MOVE DFHBMASB TO FKEY12A} at {@code :3575} and {@code :3580} and
         *       {@code MOVE DFHBMASB TO FKEY05A} at {@code :3579}. The legend text is a BMS constant, and
         *       the brightness changes travel in {@link AccountUpdateResult#fieldAttributes()}.</li>
         *   <li>{@code newDetails} - {@code ACUP-NEW-DETAILS} at {@code :757} is inbound only. The
         *       program's own new values are already visible in this buffer's fields, which is what the
         *       terminal operator sees; echoing them a second time would invite a caller to resubmit a
         *       server-derived value as if it were user input.</li>
         * </ul>
         *
         * <p>Side effects: none; this method neither mutates the buffer nor the context.</p>
         *
         * <p><strong>Privacy:</strong> the returned object carries the date of birth, the social security
         * number, both telephone numbers, the government-issued identifier and the electronic funds
         * account identifier, both directly and inside the projected snapshot. It must never be logged.</p>
         *
         * @param context the per-invocation state carrier, read for the information message and the
         *     snapshot group
         * @param errorMessage {@code WS-RETURN-MSG} as {@code COMMON-RETURN} leaves it; passed explicitly
         *     rather than read from the buffer because {@code COMMON-RETURN} at {@code :1008} moves it at
         *     the very last moment, after {@code 3400-SEND-SCREEN} has already run
         * @return the projected symbolic map; never {@code null}
         */
        private AccountUpdateRequest project(final UpdateContext context, final String errorMessage) {
            return new AccountUpdateRequest(
                    transactionName,
                    title01,
                    currentDate,
                    programName,
                    title02,
                    currentTime,
                    accountId,
                    accountStatus,
                    openDateYear,
                    openDateMonth,
                    openDateDay,
                    creditLimit,
                    expiryDateYear,
                    expiryDateMonth,
                    expiryDateDay,
                    cashCreditLimit,
                    reissueDateYear,
                    reissueDateMonth,
                    reissueDateDay,
                    currentBalance,
                    currentCycleCredit,
                    accountGroupId,
                    currentCycleDebit,
                    customerId,
                    customerSsnPart1,
                    customerSsnPart2,
                    customerSsnPart3,
                    dateOfBirthYear,
                    dateOfBirthMonth,
                    dateOfBirthDay,
                    customerFicoScore,
                    customerFirstName,
                    customerMiddleName,
                    customerLastName,
                    addressLine1,
                    addressStateCode,
                    addressLine2,
                    addressZip,
                    addressCity,
                    addressCountryCode,
                    phone1AreaCode,
                    phone1Prefix,
                    phone1LineNumber,
                    governmentIssuedId,
                    phone2AreaCode,
                    phone2Prefix,
                    phone2LineNumber,
                    eftAccountId,
                    primaryCardHolderIndicator,
                    // :2979 MOVE WS-INFO-MSG TO INFOMSGO OF CACTUPAO - the map's own copy
                    informationMessage,
                    // :2981 puts WS-RETURN-MSG in ERRMSGO; :1008 moves the same value into
                    // CCARD-ERROR-MSG. The COMMON-RETURN value is taken because it is the one the
                    // source guarantees at return time, and it is defined even on a path where
                    // 3250-SETUP-INFOMSG did not run.
                    errorMessage,
                    null,
                    null,
                    null,
                    projectOldDetails(context),
                    null);
        }
    }


    /**
     * The per-invocation state carrier, replacing every {@code WORKING-STORAGE} and {@code LINKAGE}
     * item that {@code app/cbl/COACTUPC.cbl} declares: {@code 01 WS-MISC-STORAGE} at {@code :35},
     * {@code 01 WS-THIS-PROGCOMMAREA} at {@code :652}, {@code 01 WS-COMMAREA} at {@code :850} and the
     * sliced {@code 01 DFHCOMMAREA} at {@code :854}.
     *
     * <p><strong>Why this class exists at all.</strong> Rule 1 Clause B forbids global mutable state, and
     * this bean is a singleton. The legacy program's eighty-eight paragraphs communicate almost entirely
     * through shared {@code WORKING-STORAGE}, which in COBOL is process-global and in Java would have to
     * be either bean fields - an outright concurrency defect - or an unwieldy parameter list threaded
     * through eighty-seven methods. One carrier, <em>instantiated per invocation</em> and passed by
     * reference, preserves the source's paragraph-to-paragraph communication exactly while keeping every
     * byte of it confined to a single request. It is never a field of the enclosing bean; the three
     * construction sites are the three public entry points.</p>
     *
     * <p><strong>Deliberate design choices.</strong> Members are package-private and mutable, with no getters and no
     * setters, so that each of the eighty-seven mapped methods can assign exactly the item its source paragraph
     * assigns, at exactly the point the source assigns it. Adding accessors would obscure the correspondence that the
     * {@code TRACEABILITY_MATRIX.md} has to be provable against, and would add three hundred methods to
     * a class that already carries eighty-seven mandated ones.</p>
     *
     * <p><strong>Privacy.</strong> This object carries the most personally identifiable information in
     * the online layer: the date of birth, the social security number in three parts and assembled, both
     * telephone numbers, the government-issued identifier and the electronic funds account identifier,
     * in the received values, the snapshot and the screen buffer alike. It therefore declares no
     * {@code toString}, so that no accidental string interpolation of a context can leak a payload into
     * a log sink. Callers log identifiers and outcomes, never this object.</p>
     */
    private static final class UpdateContext {

        // -------------------------------------------------------------------------------------------
        // Request, identity and control flow.
        // -------------------------------------------------------------------------------------------

        /**
         * The inbound payload, replacing the {@code EXEC CICS RECEIVE MAP} buffer of
         * {@code 1100-RECEIVE-MAP}. It supplies the fifty-four submitted screen fields and nothing else:
         * the snapshot half of the conversation is carried by {@link #authenticOldDetails}, never by this
         * object. May be {@code null} on the read-only fetch entry point.
         */
        private final AccountUpdateRequest request;

        /**
         * {@code ACUP-OLD-DETAILS} at {@code app/cbl/COACTUPC.cbl:669} as the <em>server</em> established
         * it, and the only source the comparison reads.
         *
         * <p>Two callers supply it and they differ in kind. {@link #processRequest} passes the request's own
         * group, because that entry point reproduces one screen turn in process and its caller is this
         * application rather than a client. {@link #updateAccount} passes the group recovered from a sealed
         * token, so that on the REST path the values compared are the values a preceding read displayed and
         * not values a caller chose - a caller-composed snapshot would make {@code 9700-CHECK-CHANGE-IN-REC}
         * answerable to the caller and the lost-update guard would be no guard at all.</p>
         *
         * <p>{@code null} means "no snapshot", which is a legitimate state on the fetch turn and a refusal on
         * a write turn. It is never logged: the group carries the date of birth, the social security number,
         * the government-issued identifier, both telephone numbers and the electronic funds account
         * identifier.</p>
         */
        private final AccountUpdateRequest.OldDetails authenticOldDetails;

        /**
         * {@code EIBAID} as the caller reports it, consumed by {@code YYYY-STORE-PFKEY} from
         * {@code app/cpy/CSSTRPFY.cpy:17}. May be {@code null}, which the source treats as no key.
         */
        private final String attentionIdentifier;

        /** {@code WS-TRANID PIC X(4)}, set from {@code LIT-THISTRANID} at {@code :872}. */
        private String transactionId;

        /**
         * {@code ACUP-CHANGE-ACTION PIC X(1)} at {@code :655}, the marker whose eight condition names
         * occupy {@code :656-668}. Never {@code null}; the caller echoes it on its next turn, which is
         * how a stateless request reproduces the pseudo-conversational state machine.
         */
        private ChangeAction changeAction;

        /**
         * {@code CDEMO-PGM-CONTEXT} of {@code app/cpy/COCOM01Y.cpy} - the enter versus re-enter flag,
         * plus the first-entry case that {@code IF EIBCALEN = 0} at {@code :880} detects. Never
         * {@code null}; the thirty-nine {@code app/cpy/CSSETATY.cpy} expansions gate on
         * {@link EntryMode#REENTER}.
         */
        private EntryMode entryMode;

        /**
         * {@code CCARD-AID-*} as {@code YYYY-STORE-PFKEY} resolves it. {@code null} reproduces the
         * copybook's missing {@code WHEN OTHER}: an unrecognised terminal key leaves {@code CCARD-AID}
         * at its {@code INITIALIZE}d value rather than raising anything.
         */
        private AidKey aidKey;

        /**
         * {@code WS-PFK-FLAG PIC X(1)} at {@code :178}, with {@code 88 PFK-VALID} and
         * {@code 88 PFK-INVALID}. Set by the four-condition-wide admissibility test at {@code :905-916},
         * after which an inadmissible key is silently coerced to {@code ENTER} at {@code :914-916}.
         */
        private boolean pfKeyValid;

        /**
         * {@code CDEMO-USER-TYPE PIC X(01)} of {@code app/cpy/COCOM01Y.cpy}, carried as a token role
         * claim rather than in a COMMAREA. Set to the user value at {@code :946}.
         */
        private String userType;

        // -------------------------------------------------------------------------------------------
        // Screen buffer and presentation instructions.
        // -------------------------------------------------------------------------------------------

        /**
         * {@code CACTUPAO}, the output half of the symbolic map. Initialised eagerly so that no path can
         * dereference a null buffer; {@code 3100-SCREEN-INIT} replaces it wholesale at {@code :2669}
         * where the source performs {@code MOVE LOW-VALUES TO CACTUPAO}.
         */
        private ScreenBuffer screen = new ScreenBuffer();

        /**
         * The ordered presentation instructions that replace the {@code <field>A} attribute bytes and
         * {@code <field>C} colour aliases of the symbolic map. Mutable and appended to in source order by
         * {@code 3300-SETUP-SCREEN-ATTRS}, {@code 3310-PROTECT-ALL-ATTRS},
         * {@code 3320-UNPROTECT-FEW-ATTRS} and {@code 3390-SETUP-INFOMSG-ATTRS}; cleared by
         * {@code 3100-SCREEN-INIT}; copied defensively into the result. Never {@code null}.
         */
        private final List<FieldAttribute> fieldAttributes = new ArrayList<>();

        /**
         * The field the cursor is positioned on, resolved by the first-match forty-four-arm
         * {@code EVALUATE} at {@code :3009-3167}. {@code null} means no explicit position, which is what
         * the source leaves when no arm matches.
         */
        private String cursorField;

        // -------------------------------------------------------------------------------------------
        // Messages. Note that the COBOL 88-levels the specification calls "flags" are message literals
        // on 05 WS-RETURN-MSG PIC X(75) at :479 and 05 WS-INFO-MSG PIC X(40) at :463: SET X TO TRUE
        // moves the literal in and WHEN X compares it. That is why these are Strings and not booleans,
        // and why the post-write EVALUATE at :2606-2615 is a string comparison.
        // -------------------------------------------------------------------------------------------

        /**
         * {@code WS-RETURN-MSG PIC X(75)} at {@code :479}, with {@code 88 WS-RETURN-MSG-OFF VALUE SPACES}
         * at {@code :480}. Guarded throughout by the first-error-wins latch {@code IF WS-RETURN-MSG-OFF}.
         */
        private String returnMessage;

        /** {@code WS-INFO-MSG PIC X(40)} at {@code :463}, chosen by {@code 3250-SETUP-INFOMSG}. */
        private String informationMessage;

        /**
         * {@code CCARD-ERROR-MSG} as {@code COMMON-RETURN} leaves it after
         * {@code MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG} at {@code :1008}.
         */
        private String errorMessage;

        /**
         * {@code WS-INPUT-FLAG PIC X(1)} at {@code :171}, with {@code 88 INPUT-ERROR}. {@code 88 INPUT-OK}
         * at {@code :172} and {@code 88 INPUT-PENDING} at {@code :174} are declared and never tested; both
         * are tracked in the defect register rather than deleted.
         */
        private boolean inputError;

        // -------------------------------------------------------------------------------------------
        // CICS response plumbing and the abend work area.
        // -------------------------------------------------------------------------------------------

        /** {@code WS-RESP-CD PIC S9(09) COMP} at {@code :40}, the {@code RESP} of the last file request. */
        private int responseCode;

        /** {@code WS-REAS-CD PIC S9(09) COMP} at {@code :42}, the matching {@code RESP2}. */
        private int reasonCode;

        /**
         * {@code ABEND-CODE PIC X(4)} of {@code app/cpy/CSMSG02Y.cpy} - internally titled
         * {@code CABENDD.CPY}. Set to {@code '0001'} by {@code 2000-DECIDE-ACTION}'s {@code WHEN OTHER}
         * at {@code :2635}; distinct from the terminal {@code EXEC CICS ABEND ABCODE('9999')}.
         */
        private String abendCode;

        /** {@code ABEND-CULPRIT PIC X(8)}, always {@code 'COACTUPC'} here, set at {@code :2634}. */
        private String abendCulprit;

        /** {@code ABEND-REASON PIC X(50)}, set to spaces at {@code :2636}. */
        private String abendReason;

        /**
         * {@code ABEND-MSG PIC X(72)}, set to {@code 'UNEXPECTED DATA SCENARIO'} at {@code :2637-2638} and
         * defaulted to {@code 'UNEXPECTED ABEND OCCURRED.'} by {@code ABEND-ROUTINE} at {@code :4205-4207}.
         */
        private String abendMessage;

        /**
         * The first typed failure raised on this turn, retained rather than thrown so that the source's
         * {@code GO TO ...-EXIT} control flow is reproducible. First one wins, mirroring the
         * {@code IF WS-RETURN-MSG-OFF} latch. {@code null} when nothing has failed.
         */
        private CardDemoException pendingFailure;

        // -------------------------------------------------------------------------------------------
        // COMMAREA context (app/cpy/COCOM01Y.cpy) and navigation.
        // -------------------------------------------------------------------------------------------

        /** {@code CDEMO-ACCT-ID}, copied out by {@code 9500-STORE-FETCHED-DATA} at {@code :3805}. */
        private String commAreaAccountId;

        /** {@code CDEMO-CUST-ID}, copied out at {@code :3806}. */
        private String commAreaCustomerId;

        /** {@code CDEMO-CARD-NUM}, copied from {@code XREF-CARD-NUM} at {@code :3811}. */
        private String commAreaCardNumber;

        /** {@code CDEMO-ACCT-STATUS}, copied from {@code ACCT-ACTIVE-STATUS} at {@code :3810}. */
        private String commAreaAccountStatus;

        /** {@code CDEMO-CUST-FNAME}, copied at {@code :3807}. */
        private String commAreaCustomerFirstName;

        /** {@code CDEMO-CUST-MNAME}, copied at {@code :3808}. */
        private String commAreaCustomerMiddleName;

        /** {@code CDEMO-CUST-LNAME}, copied at {@code :3809}. */
        private String commAreaCustomerLastName;

        /** {@code CDEMO-FROM-PROGRAM} as received; the menu-program test at {@code :882} reads it. */
        private String fromProgram;

        /** {@code CDEMO-FROM-TRANID} as received; the {@code LOW-VALUES}/{@code SPACES} test reads it. */
        private String fromTransactionId;

        /** {@code CDEMO-TO-PROGRAM}, resolved on the exit path at {@code :942-945}. */
        private String nextProgram;

        /** {@code CDEMO-LAST-MAPSET} as this turn leaves it, recorded at {@code :949}. */
        private String lastMapset;

        /** {@code CDEMO-LAST-MAP} as this turn leaves it, recorded at {@code :950}. */
        private String lastMap;

        /** The mapset the caller should send next; {@code LIT-THISMAPSET} on a redisplay. */
        private String nextMapset;

        /** The map the caller should send next; {@code LIT-THISMAP} on a redisplay. */
        private String nextMap;


        // -------------------------------------------------------------------------------------------
        // Received values - 05 ACUP-NEW-DETAILS at app/cbl/COACTUPC.cbl:757, populated field by field by
        // 1100-RECEIVE-MAP at :1039-1426. Every member is text as received, because the source edits the
        // characters the operator typed and 3203-SHOW-UPDATED-VALUES has to redisplay them verbatim when
        // they fail to convert. The five money fields additionally carry a converted BigDecimal, which is
        // exactly the -X / -N redefinition pair the source declares, for example
        // ACUP-NEW-CURR-BAL-X PIC X(12) redefined as ACUP-NEW-CURR-BAL-N PIC S9(10)V99.
        // -------------------------------------------------------------------------------------------

        /**
         * {@code CC-ACCT-ID} - the account filter received from {@code ACCTSIDI}, the only field the first
         * turn has. {@code 1210-EDIT-ACCOUNT} at {@code :1783-1819} is its sole validator.
         */
        private String accountFilter;

        /**
         * {@code ACUP-NEW-ACCT-ID-X PIC X(11)}. Received at {@code :1155} and never edited; one of the
         * five received-but-unedited fields recorded as defect D10.
         */
        private String newAccountId;

        /** {@code ACUP-NEW-ACTIVE-STATUS PIC X(1)}, edited by {@code 1220-EDIT-YESNO}. */
        private String newActiveStatus;

        /** {@code ACUP-NEW-CREDIT-LIMIT-X PIC X(15)} as received, before conversion. */
        private String newCreditLimitText;

        /** {@code ACUP-NEW-CREDIT-LIMIT-N PIC S9(10)V99}; {@code null} when the text did not convert. */
        private BigDecimal newCreditLimit;

        /** {@code ACUP-NEW-CASH-CREDIT-LIMIT-X PIC X(15)} as received, before conversion. */
        private String newCashCreditLimitText;

        /** {@code ACUP-NEW-CASH-CREDIT-LIMIT-N PIC S9(10)V99}; {@code null} when it did not convert. */
        private BigDecimal newCashCreditLimit;

        /** {@code ACUP-NEW-CURR-BAL-X PIC X(15)} as received, before conversion. */
        private String newCurrentBalanceText;

        /** {@code ACUP-NEW-CURR-BAL-N PIC S9(10)V99}; {@code null} when it did not convert. */
        private BigDecimal newCurrentBalance;

        /** {@code ACUP-NEW-CURR-CYC-CREDIT-X PIC X(15)} as received, before conversion. */
        private String newCurrentCycleCreditText;

        /** {@code ACUP-NEW-CURR-CYC-CREDIT-N PIC S9(10)V99}; {@code null} when it did not convert. */
        private BigDecimal newCurrentCycleCredit;

        /** {@code ACUP-NEW-CURR-CYC-DEBIT-X PIC X(15)} as received, before conversion. */
        private String newCurrentCycleDebitText;

        /** {@code ACUP-NEW-CURR-CYC-DEBIT-N PIC S9(10)V99}; {@code null} when it did not convert. */
        private BigDecimal newCurrentCycleDebit;

        /** {@code ACUP-NEW-OPEN-YEAR PIC X(4)}, edited through {@code DateValidationService}. */
        private String newOpenYear;

        /** {@code ACUP-NEW-OPEN-MON PIC X(2)}. */
        private String newOpenMonth;

        /** {@code ACUP-NEW-OPEN-DAY PIC X(2)}. */
        private String newOpenDay;

        /**
         * {@code ACUP-NEW-EXP-YEAR PIC X(4)}. The snapshot counterpart and the record field both carry the
         * copybook's own misspelling {@code ACCT-EXPIRAION-DATE}; it is never corrected.
         */
        private String newExpiryYear;

        /** {@code ACUP-NEW-EXP-MON PIC X(2)}. */
        private String newExpiryMonth;

        /** {@code ACUP-NEW-EXP-DAY PIC X(2)}. */
        private String newExpiryDay;

        /** {@code ACUP-NEW-REISSUE-YEAR PIC X(4)}. */
        private String newReissueYear;

        /** {@code ACUP-NEW-REISSUE-MON PIC X(2)}. */
        private String newReissueMonth;

        /** {@code ACUP-NEW-REISSUE-DAY PIC X(2)}. */
        private String newReissueDay;

        /**
         * {@code ACUP-NEW-GROUP-ID PIC X(10)}. Received and never edited; one of the five fields recorded
         * as defect D10.
         */
        private String newGroupId;

        /**
         * {@code ACUP-NEW-CUST-ID-X PIC X(9)}. Received at {@code :1174} and never edited; one of the five
         * fields recorded as defect D10.
         */
        private String newCustomerId;

        /** {@code ACUP-NEW-CUST-SSN-1 PIC X(3)}; personally identifiable, never logged. */
        private String newSsnPart1;

        /** {@code ACUP-NEW-CUST-SSN-2 PIC X(2)}; personally identifiable, never logged. */
        private String newSsnPart2;

        /** {@code ACUP-NEW-CUST-SSN-3 PIC X(4)}; personally identifiable, never logged. */
        private String newSsnPart3;

        /** {@code ACUP-NEW-CUST-DOB-YEAR PIC X(4)}; personally identifiable, never logged. */
        private String newDateOfBirthYear;

        /** {@code ACUP-NEW-CUST-DOB-MON PIC X(2)}; personally identifiable, never logged. */
        private String newDateOfBirthMonth;

        /** {@code ACUP-NEW-CUST-DOB-DAY PIC X(2)}; personally identifiable, never logged. */
        private String newDateOfBirthDay;

        /**
         * {@code ACUP-NEW-CUST-FICO-SCORE-X PIC X(03)} at {@code :845}, redefined as
         * {@code ACUP-NEW-CUST-FICO-SCORE PIC 9(03)} at {@code :846-847}. Held as text and converted only
         * where the source moves the numeric view, at {@code :4058-4059}.
         */
        private String newFicoScore;

        /** {@code ACUP-NEW-CUST-FIRST-NAME PIC X(25)}. */
        private String newFirstName;

        /** {@code ACUP-NEW-CUST-MIDDLE-NAME PIC X(25)}, optional per {@code 1235-EDIT-ALPHA-OPT}. */
        private String newMiddleName;

        /** {@code ACUP-NEW-CUST-LAST-NAME PIC X(25)}. */
        private String newLastName;

        /** {@code ACUP-NEW-CUST-ADDR-LINE-1 PIC X(50)}. */
        private String newAddressLine1;

        /**
         * {@code ACUP-NEW-CUST-ADDR-LINE-2 PIC X(50)}. Received and never edited; one of the five fields
         * recorded as defect D10.
         */
        private String newAddressLine2;

        /** {@code ACUP-NEW-CUST-ADDR-LINE-3 PIC X(50)} - the city line on the screen. */
        private String newAddressLine3;

        /** {@code ACUP-NEW-CUST-ADDR-STATE-CD PIC X(2)}, checked against the state lookup table. */
        private String newStateCode;

        /** {@code ACUP-NEW-CUST-ADDR-COUNTRY-CD PIC X(3)}. */
        private String newCountryCode;

        /** {@code ACUP-NEW-CUST-ADDR-ZIP PIC X(10)}, checked against the state-and-prefix lookup table. */
        private String newZip;

        /** {@code ACUP-NEW-CUST-PHONE-NUM-1A PIC X(3)}; personally identifiable, never logged. */
        private String newPhone1AreaCode;

        /** {@code ACUP-NEW-CUST-PHONE-NUM-1B PIC X(3)}; personally identifiable, never logged. */
        private String newPhone1Prefix;

        /** {@code ACUP-NEW-CUST-PHONE-NUM-1C PIC X(4)}; personally identifiable, never logged. */
        private String newPhone1LineNumber;

        /** {@code ACUP-NEW-CUST-PHONE-NUM-2A PIC X(3)}; personally identifiable, never logged. */
        private String newPhone2AreaCode;

        /** {@code ACUP-NEW-CUST-PHONE-NUM-2B PIC X(3)}; personally identifiable, never logged. */
        private String newPhone2Prefix;

        /** {@code ACUP-NEW-CUST-PHONE-NUM-2C PIC X(4)}; personally identifiable, never logged. */
        private String newPhone2LineNumber;

        /**
         * {@code ACUP-NEW-CUST-GOVT-ISSUED-ID PIC X(20)}. Unprotected at {@code :3557} yet never edited;
         * one of the five fields recorded as defect D10. Personally identifiable, never logged.
         */
        private String newGovernmentIssuedId;

        /** {@code ACUP-NEW-CUST-EFT-ACCOUNT-ID PIC X(10)}; personally identifiable, never logged. */
        private String newEftAccountId;

        /** {@code ACUP-NEW-CUST-PRI-HOLDER-IND PIC X(1)}, edited by {@code 1220-EDIT-YESNO}. */
        private String newPrimaryCardHolderIndicator;

        // -------------------------------------------------------------------------------------------
        // Generic edit work fields - 05 WS-GENERIC-EDITS at app/cbl/COACTUPC.cbl:52-150. The twelve
        // 1210-1280 edit routines are parameterless in COBOL: the caller moves a value and a label into
        // these shared items and then PERFORMs. That indirection is preserved rather than replaced by
        // Java parameters, because the source's own paragraphs read these items by name and the
        // paragraph-to-method correspondence has to stay one-to-one.
        // -------------------------------------------------------------------------------------------

        /**
         * {@code WS-EDIT-VARIABLE-NAME PIC X(25)}, the human-readable label that
         * {@code 1215-EDIT-MANDATORY} interpolates into its message at {@code :1839-1844} and that
         * {@code DateValidationService} receives as its second argument.
         */
        private String editVariableName;

        /** {@code WS-EDIT-ALPHANUM-ONLY} - the value under test in the alpha and alphanumeric routines. */
        private String editAlphanumericText;

        /**
         * {@code WS-EDIT-ALPHANUM-LENGTH} - the declared width of the value under test, so that a short
         * value fails the digits-only and class tests exactly as a fixed-width COBOL field does.
         */
        private int editAlphanumericLength;

        /**
         * {@code WS-EDIT-YES-NO PIC X(1)} at {@code :76}, initialised {@code VALUE 'N'} at {@code :77}.
         * Note that {@code 88 FLG-YES-NO-ISVALID VALUES 'Y', 'N'} at {@code :78} tests this data item
         * itself - the flag and the value are the same byte, unlike every other {@code FLG-*} in the
         * program.
         */
        private String editYesNo;

        /** {@code WS-EDIT-SIGNED-NUMBER-9V2-X PIC X(15)} - the value under test in {@code 1250}. */
        private String editSignedNumberText;

        /** {@code WS-EDIT-US-PHONE-NUMA PIC X(3)} - the area code under test in {@code EDIT-AREA-CODE}. */
        private String editPhoneAreaCode;

        /** {@code WS-EDIT-US-PHONE-NUMB PIC X(3)} - the prefix under test. */
        private String editPhonePrefix;

        /** {@code WS-EDIT-US-PHONE-NUMC PIC X(4)} - the line number under test. */
        private String editPhoneLineNumber;


        // -------------------------------------------------------------------------------------------
        // Per-field edit outcomes. Every one is a TRI-STATE, never a boolean: app/cpy/CSSETATY.cpy:17-27
        // emits DFHRED when a field is NOT-OK *or* BLANK but emits the '*' marker only when it is BLANK,
        // so collapsing the two would lose the marker. See FieldState.
        //
        // Note the flag-value convention, which is the reason FieldState.VALID maps to COBOL LOW-VALUES:
        // almost every 88 FLG-*-ISVALID is VALUE LOW-VALUES, -NOT-OK is '0' and -BLANK is 'B'. That is why
        // MOVE LOW-VALUES TO WS-NON-KEY-FLAGS at :1466 and :2789 sets every non-key field to VALID in one
        // statement - reproduced by clearNonKeyFlags().
        // -------------------------------------------------------------------------------------------

        /**
         * {@code WS-EDIT-ACCT-FLAG PIC X(1)} at {@code :183}. A <em>key</em> flag: it uses
         * {@code 88 FLG-ACCTFILTER-ISVALID VALUE '1'} rather than {@code LOW-VALUES}, and it is
         * deliberately outside {@code WS-NON-KEY-FLAGS}, so {@link #clearNonKeyFlags()} leaves it alone.
         */
        private FieldState accountFilterState;

        /**
         * {@code WS-EDIT-CUST-FLAG PIC X(1)} at {@code :187}, with
         * {@code 88 FLG-CUSTFILTER-ISVALID VALUE '1'} at {@code :188}, {@code -NOT-OK VALUE '0'} at
         * {@code :189} and {@code -BLANK VALUE ' '} at {@code :190}. Also a key flag.
         */
        private FieldState customerFilterState;

        /** {@code WS-EDIT-MANDATORY-FLAG} - the shared outcome of {@code 1215-EDIT-MANDATORY}. */
        private FieldState mandatoryState;

        /**
         * The shared outcome of {@code 1220-EDIT-YESNO}. Backed in the source by {@code WS-EDIT-YES-NO}
         * itself, whose {@code 88 FLG-YES-NO-ISVALID VALUES 'Y', 'N'} at {@code :78} is the one place a
         * flag and its data field are the same byte.
         */
        private FieldState yesNoState;

        /** The shared outcome of {@code 1225-EDIT-ALPHA-REQD} and {@code 1235-EDIT-ALPHA-OPT}. */
        private FieldState alphaState;

        /**
         * The shared outcome of {@code 1230-EDIT-ALPHANUM-REQD} and {@code 1240-EDIT-ALPHANUM-OPT}. Both
         * of those paragraphs are dead - defects D12 and D13 - so this item is only ever left at its
         * initial value; retained because the paragraphs themselves are retained.
         */
        private FieldState alphanumericState;

        /** The shared outcome of {@code 1250-EDIT-SIGNED-9V2}. */
        private FieldState signedNumberState;

        /** The shared outcome of {@code EDIT-AREA-CODE} at {@code :2246}. */
        private FieldState phoneAreaCodeState;

        /** The shared outcome of {@code EDIT-US-PHONE-PREFIX} at {@code :2316}. */
        private FieldState phonePrefixState;

        /** The shared outcome of {@code EDIT-US-PHONE-LINENUM} at {@code :2370}. */
        private FieldState phoneLineNumberState;

        /** {@code WS-EDIT-ACCT-STATUS PIC X(1)} at {@code :192}; expansion 1 of 39 in {@code 3300}. */
        private FieldState accountStatusState;

        /** {@code WS-EDIT-OPEN-DATE-FLGS} year byte at {@code :235}; expansion 2 of 39. */
        private FieldState openYearState;

        /** {@code WS-EDIT-OPEN-DATE-FLGS} month byte; expansion 3 of 39. */
        private FieldState openMonthState;

        /** {@code WS-EDIT-OPEN-DATE-FLGS} day byte; expansion 4 of 39. */
        private FieldState openDayState;

        /** {@code WS-EDIT-CREDIT-LIMIT PIC X(1)} at {@code :196}; expansion 5 of 39. */
        private FieldState creditLimitState;

        /** {@code WS-EXPIRY-DATE-FLGS} year byte at {@code :249}; expansion 6 of 39. */
        private FieldState expiryYearState;

        /** {@code WS-EXPIRY-DATE-FLGS} month byte; expansion 7 of 39. */
        private FieldState expiryMonthState;

        /** {@code WS-EXPIRY-DATE-FLGS} day byte; expansion 8 of 39. */
        private FieldState expiryDayState;

        /** {@code WS-EDIT-CASH-CREDIT-LIMIT PIC X(1)} at {@code :200}; expansion 9 of 39. */
        private FieldState cashCreditLimitState;

        /** {@code WS-EDIT-REISSUE-DATE-FLGS} year byte at {@code :263}; expansion 10 of 39. */
        private FieldState reissueYearState;

        /** {@code WS-EDIT-REISSUE-DATE-FLGS} month byte; expansion 11 of 39. */
        private FieldState reissueMonthState;

        /** {@code WS-EDIT-REISSUE-DATE-FLGS} day byte; expansion 12 of 39. */
        private FieldState reissueDayState;

        /** {@code WS-EDIT-CURR-BAL PIC X(1)} at {@code :204}; expansion 13 of 39. */
        private FieldState currentBalanceState;

        /** {@code WS-EDIT-CURR-CYC-CREDIT PIC X(1)} at {@code :208}; expansion 14 of 39. */
        private FieldState currentCycleCreditState;

        /** {@code WS-EDIT-CURR-CYC-DEBIT PIC X(1)} at {@code :212}; expansion 15 of 39. */
        private FieldState currentCycleDebitState;

        /** {@code WS-EDIT-US-SSN-PART1-FLGS PIC X(01)} at {@code :135}; expansion 16 of 39. */
        private FieldState ssnPart1State;

        /**
         * {@code WS-EDIT-US-SSN-PART2-FLGS PIC X(01)} at {@code :139}; expansion 17 of 39. Left at its
         * initial value whenever part 1 fails, because of the unbalanced {@code IF} at {@code :2448} that
         * has no {@code END-IF} - defect D15, preserved.
         */
        private FieldState ssnPart2State;

        /**
         * {@code WS-EDIT-US-SSN-PART3-FLGS PIC X(01)} at {@code :143}; expansion 18 of 39. Subject to the
         * same defect D15 scope as part 2.
         */
        private FieldState ssnPart3State;

        /** {@code WS-EDIT-DT-OF-BIRTH-FLGS} year byte at {@code :216}; expansion 19 of 39. */
        private FieldState dateOfBirthYearState;

        /** {@code WS-EDIT-DT-OF-BIRTH-FLGS} month byte; expansion 20 of 39. */
        private FieldState dateOfBirthMonthState;

        /** {@code WS-EDIT-DT-OF-BIRTH-FLGS} day byte; expansion 21 of 39. */
        private FieldState dateOfBirthDayState;

        /** {@code WS-EDIT-FICO-SCORE-FLGS PIC X(01)} at {@code :231}; expansion 22 of 39. */
        private FieldState ficoScoreState;

        /** {@code WS-EDIT-FIRST-NAME-FLGS PIC X(01)} at {@code :278}; expansion 23 of 39. */
        private FieldState firstNameState;

        /** {@code WS-EDIT-MIDDLE-NAME-FLGS PIC X(01)} at {@code :282}; expansion 24 of 39. */
        private FieldState middleNameState;

        /** {@code WS-EDIT-LAST-NAME-FLGS PIC X(01)} at {@code :286}; expansion 25 of 39. */
        private FieldState lastNameState;

        /** {@code WS-EDIT-ADDRESS-LINE-1-FLGS PIC X(01)} at {@code :291}; expansion 26 of 39. */
        private FieldState addressLine1State;

        /** {@code WS-EDIT-STATE-FLGS PIC X(01)} at {@code :303}; expansion 27 of 39. */
        private FieldState stateState;

        /**
         * {@code WS-EDIT-ADDRESS-LINE-2-FLGS PIC X(01)} at {@code :295}; expansion 28 of 39. Never set,
         * because address line 2 is received and never edited - defect D10.
         */
        private FieldState addressLine2State;

        /** {@code WS-EDIT-ZIPCODE-FLGS PIC X(01)} at {@code :307}; expansion 29 of 39. */
        private FieldState zipState;

        /** {@code WS-EDIT-CITY-FLGS PIC X(01)} at {@code :299}; expansion 30 of 39. */
        private FieldState cityState;

        /** {@code WS-EDIT-COUNTRY-FLGS PIC X(01)} at {@code :311}; expansion 31 of 39. */
        private FieldState countryState;

        /** {@code WS-EDIT-PHONE-NUM-1A-FLG PIC X(01)} at {@code :318}; expansion 32 of 39. */
        private FieldState phone1AreaCodeState;

        /** {@code WS-EDIT-PHONE-NUM-1B PIC X(01)} at {@code :322}; expansion 33 of 39. */
        private FieldState phone1PrefixState;

        /** {@code WS-EDIT-PHONE-NUM-1C PIC X(01)} at {@code :326}; expansion 34 of 39. */
        private FieldState phone1LineNumberState;

        /** {@code WS-EDIT-PHONE-NUM-2A-FLG PIC X(01)} at {@code :333}; expansion 35 of 39. */
        private FieldState phone2AreaCodeState;

        /** {@code WS-EDIT-PHONE-NUM-2B PIC X(01)} at {@code :337}; expansion 36 of 39. */
        private FieldState phone2PrefixState;

        /** {@code WS-EDIT-PHONE-NUM-2C PIC X(01)} at {@code :341}; expansion 37 of 39. */
        private FieldState phone2LineNumberState;

        /** {@code WS-EDIT-PRI-CARDHOLDER PIC X(1)} at {@code :349}; expansion 38 of 39. */
        private FieldState primaryCardHolderState;

        /** {@code WS-EFT-ACCOUNT-ID-FLGS PIC X(01)} at {@code :345}; expansion 39 of 39. */
        private FieldState eftAccountIdState;

        // -------------------------------------------------------------------------------------------
        // Retrieval and write outcome flags.
        // -------------------------------------------------------------------------------------------

        /**
         * {@code 88 FOUND-ACCT-IN-MASTER VALUE '1'} at {@code :386}. Set optimistically at {@code :1453}
         * before any read, and again on a successful read at {@code :3715}.
         */
        private boolean foundAccountInMaster;

        /**
         * {@code 88 FOUND-CUST-IN-MASTER VALUE '1'} at {@code :388}. Set optimistically at {@code :1456}
         * and again on a successful read at {@code :3765}; tested by {@code 2000-DECIDE-ACTION} at
         * {@code :2578}.
         */
        private boolean foundCustomerInMaster;

        /**
         * {@code WS-DATACHANGED-FLAG PIC X(1)} at {@code :168}, with
         * {@code 88 CHANGE-HAS-OCCURRED} at {@code :170} and {@code 88 NO-CHANGES-DETECTED}. Set by
         * {@code 1205-COMPARE-OLD-NEW}, which answers "did the <em>user</em> change anything" - a
         * different question from {@code 9700}'s "did <em>someone else</em> change it".
         */
        private boolean changeHasOccurred;

        /**
         * {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE} at {@code :517-518}, set at {@code :3913}. Tested by the
         * post-write {@code EVALUATE} at {@code :2607}.
         */
        private boolean accountLockFailed;

        /**
         * {@code COULD-NOT-LOCK-CUST-FOR-UPDATE} at {@code :519-520}, set once at {@code :3939}.
         *
         * <p><strong>BLOCKER.</strong> An exhaustive census of the program finds this condition tested
         * <em>nowhere</em>: its only occurrences are the declaration and the single {@code SET}. A
         * customer read-for-update failure therefore falls through {@code WHEN OTHER} of the
         * {@code EVALUATE} at {@code :2606-2615} and is reported as top-level success, even though nothing
         * was written. The flag is kept here precisely so the internal outcome stays distinguishable,
         * observable and traceable while the <em>reported</em> outcome reproduces the source exactly. See
         * the defect register in this class's documentation. No fifth {@code WHEN} may be added.</p>
         */
        private boolean customerLockFailed;

        /**
         * {@code LOCKED-BUT-UPDATE-FAILED} at {@code :523-524}, set at {@code :4079} on the account
         * rewrite failure and at {@code :4098} on the customer rewrite failure. Unlike the two lock
         * flags, neither {@code SET} sits inside the {@code IF WS-RETURN-MSG-OFF} latch.
         */
        private boolean lockedButUpdateFailed;

        /**
         * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} at {@code :521-522}, set by both {@code ELSE} branches of
         * {@code 9700-CHECK-CHANGE-IN-REC} at {@code :4143} and {@code :4189}, each of which then executes
         * a cross-paragraph {@code GO TO 9600-WRITE-PROCESSING-EXIT}.
         */
        private boolean dataWasChangedBeforeUpdate;

        /**
         * {@code 88 FLG-PHONE-NUM-BLANK} / valid aggregate used by {@code 1260-EDIT-US-PHONE-NUM} to gate
         * its three component edits.
         */
        private boolean phoneNumberValid;

        /**
         * Records that {@code INITIALIZE ACUP-OLD-DETAILS} - or {@code MOVE LOW-VALUES TO
         * ACUP-OLD-ACCT-DATA} at {@code :1438} - has run on this turn, at {@code :3610} and
         * {@code :3813}. Read by {@link AccountUpdateService#projectOldDetails(UpdateContext)} to
         * distinguish "the snapshot group was cleared and nothing was stored back" from "there is a
         * snapshot to echo".
         */
        private boolean snapshotAccountCleared;

        // -------------------------------------------------------------------------------------------
        // Record areas. The read-only retrieval chain of 9000/9200/9300/9400 and the read-for-update
        // pair of 9600 are kept apart, because the source declares distinct record areas and because a
        // 9600 read must never silently reuse whatever 9000 left behind.
        // -------------------------------------------------------------------------------------------

        /** {@code WS-CARD-RID-ACCT-ID} - the eleven-character account key the retrieval chain used. */
        private String readKeyAccountId;

        /** {@code WS-CARD-RID-CUST-ID} - the nine-character customer key the retrieval chain used. */
        private String readKeyCustomerId;

        /**
         * {@code CARD-XREF-RECORD} of {@code app/cpy/CVACT03Y.cpy} as {@code 9200-GETCARDXREF-BYACCT}
         * leaves it. {@code null} when the read missed.
         */
        private CardCrossReference readCrossReference;

        /**
         * {@code ACCOUNT-RECORD} of {@code app/cpy/CVACT01Y.cpy} as {@code 9300-GETACCTDATA-BYACCT}
         * leaves it. {@code null} when the read missed - which, because of the dead guard at {@code :3627}
         * (defect D16), does <em>not</em> stop the chain.
         */
        private Account readAccount;

        /**
         * {@code CUSTOMER-RECORD} of {@code app/cpy/CVCUS01Y.cpy} as {@code 9400-GETCUSTDATA-BYCUST}
         * leaves it. {@code null} when the read missed.
         */
        private Customer readCustomer;

        /**
         * The account record read {@code UPDATE} by step 1 of {@code 9600-WRITE-PROCESSING} at
         * {@code :3894-3903}. {@code null} when the read-for-update failed.
         */
        private Account lockedAccount;

        /**
         * The customer record read {@code UPDATE} by step 2 of {@code 9600-WRITE-PROCESSING} at
         * {@code :3921-3930}. {@code null} when the read-for-update failed - the BLOCKER path.
         */
        private Customer lockedCustomer;

        // -------------------------------------------------------------------------------------------
        // The snapshot - 05 ACUP-OLD-DETAILS at app/cbl/COACTUPC.cbl:669, populated by
        // 9500-STORE-FETCHED-DATA at :3801-3884 and compared against the live record by
        // 9700-CHECK-CHANGE-IN-REC at :4109-4193.
        //
        // Two encodings here are not cosmetic and must not be "tidied":
        //
        //   1. The five money members are BigDecimal because the source populates them through the
        //      SIGNED NUMERIC view of the redefinition pair - MOVE ACCT-CURR-BAL TO ACUP-OLD-CURR-BAL-N
        //      at :3821 - and 9700 compares them numerically at :4117-4125. compareTo is therefore used,
        //      never equals.
        //
        //   2. The four dates are held as DISCRETE year/month/day components, never as whole strings.
        //      9500 has every whole-date MOVE commented out and replaced by three component moves - see
        //      :3831-3834, :3836-3839, :3842-3845 and :3856-3859. For the date of birth this is decisive:
        //      ACUP-OLD-CUST-DOB-YYYY-MM-DD is PIC X(08) at :746 with ACUP-OLD-CUST-DOB-PARTS REDEFINES
        //      at :747-751 splitting it X(4)/X(2)/X(2) - eight bytes with NO separators - whereas the live
        //      CUST-DOB-YYYY-MM-DD is a dash-separated PIC X(10). That is why :4174-4179 compares live
        //      (1:4)/(6:2)/(9:2) against snapshot (1:4)/(5:2)/(7:2), and why a whole-string comparison
        //      would report a change on EVERY SINGLE REQUEST and make the endpoint permanently unusable.
        //
        // Privacy: the date of birth, the social security number, both telephone numbers, the
        // government-issued identifier and the electronic funds account identifier all live here.
        // -------------------------------------------------------------------------------------------

        /** {@code ACUP-OLD-ACCT-ID}, moved at {@code :3818}; eleven display characters. */
        private String snapshotAccountId;

        /** {@code ACUP-OLD-ACTIVE-STATUS}, moved at {@code :3820}; compared without a case function. */
        private String snapshotActiveStatus;

        /** {@code ACUP-OLD-CURR-BAL-N PIC S9(10)V99} at {@code :676-677}, moved at {@code :3821}. */
        private BigDecimal snapshotCurrentBalance;

        /** {@code ACUP-OLD-CREDIT-LIMIT-N PIC S9(10)V99}, moved at {@code :3823}. */
        private BigDecimal snapshotCreditLimit;

        /** {@code ACUP-OLD-CASH-CREDIT-LIMIT-N PIC S9(10)V99}, moved at {@code :3825}. */
        private BigDecimal snapshotCashCreditLimit;

        /** {@code ACUP-OLD-CURR-CYC-CREDIT-N PIC S9(10)V99}, moved at {@code :3827}. */
        private BigDecimal snapshotCurrentCycleCredit;

        /** {@code ACUP-OLD-CURR-CYC-DEBIT-N PIC S9(10)V99}, moved at {@code :3829}. */
        private BigDecimal snapshotCurrentCycleDebit;

        /** {@code ACUP-OLD-OPEN-YEAR}, from {@code ACCT-OPEN-DATE (1:4)} at {@code :3832}. */
        private String snapshotOpenYear;

        /** {@code ACUP-OLD-OPEN-MON}, from {@code ACCT-OPEN-DATE (6:2)} at {@code :3833}. */
        private String snapshotOpenMonth;

        /** {@code ACUP-OLD-OPEN-DAY}, from {@code ACCT-OPEN-DATE (9:2)} at {@code :3834}. */
        private String snapshotOpenDay;

        /**
         * {@code ACUP-OLD-EXP-YEAR}, from {@code ACCT-EXPIRAION-DATE (1:4)} at {@code :3837}. The
         * misspelling is in the copybook itself, {@code app/cpy/CVACT01Y.cpy:11}, and occurs seven times
         * in this program. It is preserved in the field contract, in the derived Java identifiers and in
         * every citation, and is never corrected.
         */
        private String snapshotExpiraionYear;

        /** {@code ACUP-OLD-EXP-MON}, from {@code ACCT-EXPIRAION-DATE (6:2)} at {@code :3838}. */
        private String snapshotExpiraionMonth;

        /** {@code ACUP-OLD-EXP-DAY}, from {@code ACCT-EXPIRAION-DATE (9:2)} at {@code :3839}. */
        private String snapshotExpiraionDay;

        /** {@code ACUP-OLD-REISSUE-YEAR}, from {@code ACCT-REISSUE-DATE (1:4)} at {@code :3843}. */
        private String snapshotReissueYear;

        /** {@code ACUP-OLD-REISSUE-MON}, from {@code ACCT-REISSUE-DATE (6:2)} at {@code :3844}. */
        private String snapshotReissueMonth;

        /** {@code ACUP-OLD-REISSUE-DAY}, from {@code ACCT-REISSUE-DATE (9:2)} at {@code :3845}. */
        private String snapshotReissueDay;

        /**
         * {@code ACUP-OLD-GROUP-ID}, moved at {@code :3847}. Compared at {@code :4139-4140} through
         * {@code FUNCTION LOWER-CASE} on both sides and with <em>no</em> {@code FUNCTION TRIM} - unlike
         * {@code 1205-COMPARE-OLD-NEW}, which upper-cases and trims the same field at {@code :1697-1700}.
         * The asymmetry is deliberate source behaviour and is not normalised in either direction.
         */
        private String snapshotGroupId;

        /** {@code ACUP-OLD-CUST-ID}, moved at {@code :3850}; not compared by {@code 9700}. */
        private String snapshotCustomerId;

        /** {@code ACUP-OLD-CUST-FIRST-NAME}, moved at {@code :3852}; upper-cased in {@code 9700}. */
        private String snapshotFirstName;

        /** {@code ACUP-OLD-CUST-MIDDLE-NAME}, moved at {@code :3853}; upper-cased in {@code 9700}. */
        private String snapshotMiddleName;

        /** {@code ACUP-OLD-CUST-LAST-NAME}, moved at {@code :3854}; upper-cased in {@code 9700}. */
        private String snapshotLastName;

        /** {@code ACUP-OLD-CUST-ADDR-LINE-1}, upper-cased in {@code 9700} at {@code :4158-4159}. */
        private String snapshotAddressLine1;

        /** {@code ACUP-OLD-CUST-ADDR-LINE-2}, upper-cased in {@code 9700} at {@code :4160-4161}. */
        private String snapshotAddressLine2;

        /** {@code ACUP-OLD-CUST-ADDR-LINE-3}, upper-cased in {@code 9700} at {@code :4162-4163}. */
        private String snapshotAddressLine3;

        /** {@code ACUP-OLD-CUST-ADDR-STATE-CD}, upper-cased in {@code 9700} at {@code :4164-4165}. */
        private String snapshotAddressStateCode;

        /** {@code ACUP-OLD-CUST-ADDR-COUNTRY-CD}, upper-cased in {@code 9700} at {@code :4166-4167}. */
        private String snapshotAddressCountryCode;

        /**
         * {@code ACUP-OLD-CUST-ADDR-ZIP}. Compared at {@code :4168} with <em>no</em> case function, which
         * is one of the ten no-case clauses.
         */
        private String snapshotAddressZip;

        /**
         * {@code ACUP-OLD-CUST-PHONE-NUM-1 PIC X(15)} at {@code :722}, redefined at {@code :723-731} as
         * {@code FILLER X(1)}, area {@code X(3)}, {@code FILLER X(1)}, prefix {@code X(3)},
         * {@code FILLER X(1)}, line {@code X(4)}, {@code FILLER X(2)} - the punctuated
         * {@code (nnn)nnn-nnnn} form. Compared whole at {@code :4169} with no case function. Personally
         * identifiable; never logged.
         */
        private String snapshotPhoneNumber1;

        /**
         * {@code ACUP-OLD-CUST-PHONE-NUM-2 PIC X(15)}, same layout as number one. Compared whole at
         * {@code :4170}. Personally identifiable; never logged.
         */
        private String snapshotPhoneNumber2;

        /**
         * {@code ACUP-OLD-CUST-SSN-X PIC X(09)} at {@code :742}, redefined as
         * {@code ACUP-OLD-CUST-SSN PIC 9(09)} at {@code :743-744}. Compared at {@code :4171} with no case
         * function. Personally identifiable; never logged.
         */
        private String snapshotSsn;

        /**
         * {@code ACUP-OLD-CUST-GOVT-ISSUED-ID}, upper-cased in {@code 9700} at {@code :4172-4173}.
         * Personally identifiable; never logged.
         */
        private String snapshotGovernmentIssuedId;

        /**
         * {@code ACUP-OLD-CUST-DOB-YEAR X(4)} at {@code :749}, from
         * {@code CUST-DOB-YYYY-MM-DD (1:4)} at {@code :3857}. Compared against live {@code (1:4)} at
         * {@code :4174-4175}. Personally identifiable; never logged.
         */
        private String snapshotDateOfBirthYear;

        /**
         * {@code ACUP-OLD-CUST-DOB-MON X(2)} at {@code :750}, from
         * {@code CUST-DOB-YYYY-MM-DD (6:2)} at {@code :3858}. Compared against live {@code (6:2)} at
         * {@code :4176-4177}, where the snapshot side is addressed at {@code (5:2)} because the compact
         * eight-byte group has no separators. Personally identifiable; never logged.
         */
        private String snapshotDateOfBirthMonth;

        /**
         * {@code ACUP-OLD-CUST-DOB-DAY X(2)} at {@code :751}, from
         * {@code CUST-DOB-YYYY-MM-DD (9:2)} at {@code :3859}. Compared against live {@code (9:2)} at
         * {@code :4178-4179}, where the snapshot side is addressed at {@code (7:2)}. Personally
         * identifiable; never logged.
         */
        private String snapshotDateOfBirthDay;

        /**
         * {@code ACUP-OLD-CUST-EFT-ACCOUNT-ID}. Compared at {@code :4181-4182} with no case function.
         * Personally identifiable; never logged.
         */
        private String snapshotEftAccountId;

        /**
         * {@code ACUP-OLD-CUST-PRI-CARD-IND}. Compared at {@code :4183-4185} with no case function.
         */
        private String snapshotPrimaryCardHolderIndicator;

        /**
         * {@code ACUP-OLD-CUST-FICO-SCORE}, moved at {@code :3861} from
         * {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}.
         *
         * <p>Held as {@code String}, matching {@code com.cardemo.model.entity.Customer}'s own
         * {@code ficoCreditScore} member, because the underlying column preserves the fixed three-byte
         * display image. The <em>comparison</em> at {@code :4186} is nonetheless numeric - both operands
         * are {@code PIC 9(03)} items, {@code ACUP-OLD-CUST-FICO-SCORE} being the numeric
         * {@code REDEFINES} of the {@code X(03)} screen image at {@code :845-847} - so
         * {@link AccountUpdateService#equalScore(String, String)} compares the values numerically and
         * {@code 720} matches {@code 0720}, which a plain text comparison would get wrong.</p>
         */
        private String snapshotFicoScore;

        /**
         * Builds the carrier for one invocation, replacing
         * {@code INITIALIZE CC-WORK-AREA WS-MISC-STORAGE WS-COMMAREA} at
         * {@code app/cbl/COACTUPC.cbl:866-868}. Every other member starts at its Java default, which is
         * exactly what {@code INITIALIZE} leaves: {@code null} for the alphanumeric items,
         * {@code false} for the condition-name flags and zero for the two binary response codes.
         *
         * <p>{@code changeAction} and {@code entryMode} are supplied rather than defaulted because the
         * source resolves both before any paragraph runs - {@code entryMode} from
         * {@code IF EIBCALEN = 0 OR (CDEMO-FROM-PROGRAM = LIT-MENUPGM AND NOT CDEMO-PGM-REENTER)} at
         * {@code :880-886}, and {@code changeAction} from the caller's echo of
         * {@code ACUP-CHANGE-ACTION}, which is how a stateless request carries the pseudo-conversational
         * state that the COMMAREA used to carry.</p>
         *
         * @param request the inbound payload; may be {@code null} on the read-only fetch entry point
         * @param attentionIdentifier {@code EIBAID} as reported; may be {@code null}, which
         *     {@code YYYY-STORE-PFKEY} treats as no recognised key
         * @param changeAction the echoed {@code ACUP-CHANGE-ACTION} marker; must not be {@code null}
         * @param entryMode the resolved {@code CDEMO-PGM-CONTEXT}; must not be {@code null}
         * @param authenticOldDetails the as-displayed snapshot opened from the sealed token, which is what
         *     {@code 9700-CHECK-CHANGE-IN-REC} compares the live row against; may be {@code null} on the
         *     read-only fetch entry point, where no snapshot has been issued yet. It is deliberately NOT the
         *     copy carried on the request: a caller could edit that one, and comparing against an edited
         *     snapshot would accept an update the source refuses
         */
        private UpdateContext(final AccountUpdateRequest request,
                              final String attentionIdentifier,
                              final ChangeAction changeAction,
                              final EntryMode entryMode,
                              final AccountUpdateRequest.OldDetails authenticOldDetails) {
            this.request = request;
            this.attentionIdentifier = attentionIdentifier;
            this.changeAction = changeAction;
            this.entryMode = entryMode;
            this.authenticOldDetails = authenticOldDetails;
        }

        /**
         * Reproduces {@code INITIALIZE WS-THIS-PROGCOMMAREA WS-MISC-STORAGE} at
         * {@code app/cbl/COACTUPC.cbl:981-983}, executed by the second fall-through {@code WHEN} pair of
         * {@code 0000-MAIN} once an update has either completed or failed, so that the next turn starts
         * from a clean search screen.
         *
         * <p>Scope is exactly the two {@code 01} groups the source names. {@code WS-MISC-STORAGE} at
         * {@code :35} contributes the two message fields, the input flag, the two binary response codes,
         * the abend work area, the generic edit work fields, <em>both</em> key flags at {@code :183} and
         * {@code :187}, all of {@code WS-NON-KEY-FLAGS} at {@code :191-352} and the record identification
         * areas. {@code WS-THIS-PROGCOMMAREA} at {@code :652} contributes {@code ACUP-CHANGE-ACTION} and
         * the {@code ACUP-OLD-DETAILS} and {@code ACUP-NEW-DETAILS} groups.</p>
         *
         * <p>Two things are deliberately left alone. {@code CDEMO-ACCT-ID} is named separately in the
         * source statement and is cleared by the caller, mirroring the source's own separation. And
         * {@code entryMode} is set by the caller immediately afterwards at {@code :984}, so resetting it
         * here would be overwritten and would obscure the correspondence.</p>
         *
         * <p>Side effects: mutates this carrier only. No I/O, no logging.</p>
         */
        private void resetMiscellaneousStorage() {
            // WS-MISC-STORAGE - messages and the input flag
            returnMessage = null;
            informationMessage = null;
            errorMessage = null;
            inputError = false;
            pfKeyValid = false;
            // WS-CICS-PROCESSNG-VARS at :39-51
            responseCode = 0;
            reasonCode = 0;
            // the CABENDD work area
            abendCode = null;
            abendCulprit = null;
            abendReason = null;
            abendMessage = null;
            pendingFailure = null;
            // WS-GENERIC-EDITS at :52-150
            editVariableName = null;
            editAlphanumericText = null;
            editAlphanumericLength = 0;
            editYesNo = null;
            editSignedNumberText = null;
            editPhoneAreaCode = null;
            editPhonePrefix = null;
            editPhoneLineNumber = null;
            mandatoryState = null;
            yesNoState = null;
            alphaState = null;
            alphanumericState = null;
            signedNumberState = null;
            phoneAreaCodeState = null;
            phonePrefixState = null;
            phoneLineNumberState = null;
            ssnPart1State = null;
            ssnPart2State = null;
            ssnPart3State = null;
            phoneNumberValid = false;
            // the two key flags at :183 and :187 - both inside WS-MISC-STORAGE
            accountFilterState = null;
            customerFilterState = null;
            // WS-NON-KEY-FLAGS at :191-352
            clearNonKeyFlagsToNull();
            // retrieval and write outcomes
            foundAccountInMaster = false;
            foundCustomerInMaster = false;
            changeHasOccurred = false;
            accountLockFailed = false;
            customerLockFailed = false;
            lockedButUpdateFailed = false;
            dataWasChangedBeforeUpdate = false;
            // record identification areas and record areas
            readKeyAccountId = null;
            readKeyCustomerId = null;
            readCrossReference = null;
            readAccount = null;
            readCustomer = null;
            lockedAccount = null;
            lockedCustomer = null;
            // WS-THIS-PROGCOMMAREA at :652 - the change marker and both detail groups
            changeAction = ChangeAction.DETAILS_NOT_FETCHED;
            clearSnapshot();
            clearNewDetails();
        }

        /**
         * Reproduces {@code MOVE LOW-VALUES TO WS-NON-KEY-FLAGS}, performed at
         * {@code app/cbl/COACTUPC.cbl:1466} - where {@code 1200-EDIT-MAP-INPUTS} skips editing entirely
         * because the change action says nothing was submitted - and at {@code :2789}, where
         * {@code 3202-SHOW-ORIGINAL-VALUES} redisplays a freshly fetched record.
         *
         * <p>{@code LOW-VALUES} means <strong>valid</strong>, not "cleared": almost every
         * {@code 88 FLG-*-ISVALID} in the group is declared {@code VALUE LOW-VALUES}, with
         * {@code -NOT-OK VALUE '0'} and {@code -BLANK VALUE 'B'}. One statement therefore marks all
         * thirty-six members of the group valid, which is why no field is flagged red or starred on a
         * turn that submitted nothing.</p>
         *
         * <p>The group boundary is load-bearing and is honoured exactly. The two key flags at
         * {@code :183} and {@code :187} sit <em>outside</em> {@code WS-NON-KEY-FLAGS} - hence its name -
         * and so does the whole of {@code WS-GENERIC-EDITS}, which is why the three social-security
         * states are untouched here even though they are among the thirty-nine
         * {@code app/cpy/CSSETATY.cpy} expansions of {@code 3300-SETUP-SCREEN-ATTRS}.</p>
         *
         * <p>Side effects: mutates this carrier only.</p>
         */
        private void clearNonKeyFlags() {
            accountStatusState = FieldState.VALID;
            creditLimitState = FieldState.VALID;
            cashCreditLimitState = FieldState.VALID;
            currentBalanceState = FieldState.VALID;
            currentCycleCreditState = FieldState.VALID;
            currentCycleDebitState = FieldState.VALID;
            dateOfBirthYearState = FieldState.VALID;
            dateOfBirthMonthState = FieldState.VALID;
            dateOfBirthDayState = FieldState.VALID;
            ficoScoreState = FieldState.VALID;
            openYearState = FieldState.VALID;
            openMonthState = FieldState.VALID;
            openDayState = FieldState.VALID;
            expiryYearState = FieldState.VALID;
            expiryMonthState = FieldState.VALID;
            expiryDayState = FieldState.VALID;
            reissueYearState = FieldState.VALID;
            reissueMonthState = FieldState.VALID;
            reissueDayState = FieldState.VALID;
            firstNameState = FieldState.VALID;
            middleNameState = FieldState.VALID;
            lastNameState = FieldState.VALID;
            addressLine1State = FieldState.VALID;
            addressLine2State = FieldState.VALID;
            cityState = FieldState.VALID;
            stateState = FieldState.VALID;
            zipState = FieldState.VALID;
            countryState = FieldState.VALID;
            phone1AreaCodeState = FieldState.VALID;
            phone1PrefixState = FieldState.VALID;
            phone1LineNumberState = FieldState.VALID;
            phone2AreaCodeState = FieldState.VALID;
            phone2PrefixState = FieldState.VALID;
            phone2LineNumberState = FieldState.VALID;
            eftAccountIdState = FieldState.VALID;
            primaryCardHolderState = FieldState.VALID;
        }

        /**
         * The {@code INITIALIZE} counterpart of {@link #clearNonKeyFlags()}, distinguished from it because
         * the two source statements are genuinely different. {@code MOVE LOW-VALUES TO WS-NON-KEY-FLAGS}
         * sets the {@code ISVALID} condition, whereas {@code INITIALIZE WS-MISC-STORAGE} at {@code :982}
         * restores each item to its {@code VALUE} clause or to spaces where it has none - and the two
         * {@code 'Y'}/{@code 'N'} flags at {@code :78} and {@code :193} do not have {@code LOW-VALUES} as
         * their valid value, so conflating the statements would mark them valid when the source does not.
         * Modelled as "no state determined yet".
         *
         * <p>Side effects: mutates this carrier only.</p>
         */
        private void clearNonKeyFlagsToNull() {
            accountStatusState = null;
            creditLimitState = null;
            cashCreditLimitState = null;
            currentBalanceState = null;
            currentCycleCreditState = null;
            currentCycleDebitState = null;
            dateOfBirthYearState = null;
            dateOfBirthMonthState = null;
            dateOfBirthDayState = null;
            ficoScoreState = null;
            openYearState = null;
            openMonthState = null;
            openDayState = null;
            expiryYearState = null;
            expiryMonthState = null;
            expiryDayState = null;
            reissueYearState = null;
            reissueMonthState = null;
            reissueDayState = null;
            firstNameState = null;
            middleNameState = null;
            lastNameState = null;
            addressLine1State = null;
            addressLine2State = null;
            cityState = null;
            stateState = null;
            zipState = null;
            countryState = null;
            phone1AreaCodeState = null;
            phone1PrefixState = null;
            phone1LineNumberState = null;
            phone2AreaCodeState = null;
            phone2PrefixState = null;
            phone2LineNumberState = null;
            eftAccountIdState = null;
            primaryCardHolderState = null;
        }

        /**
         * Reproduces {@code INITIALIZE ACUP-OLD-DETAILS}, executed at
         * {@code app/cbl/COACTUPC.cbl:3610} by {@code 9000-READ-ACCT} before the retrieval chain runs and
         * at {@code :3813} by {@code 9500-STORE-FETCHED-DATA} before the snapshot is stored.
         *
         * <p>Side effects: mutates this carrier only. Sets {@link #snapshotAccountCleared} so that the
         * projection can tell an emptied group from a populated one.</p>
         */
        private void clearSnapshot() {
            snapshotAccountCleared = true;
            snapshotAccountId = null;
            snapshotActiveStatus = null;
            snapshotCurrentBalance = null;
            snapshotCreditLimit = null;
            snapshotCashCreditLimit = null;
            snapshotCurrentCycleCredit = null;
            snapshotCurrentCycleDebit = null;
            snapshotOpenYear = null;
            snapshotOpenMonth = null;
            snapshotOpenDay = null;
            snapshotExpiraionYear = null;
            snapshotExpiraionMonth = null;
            snapshotExpiraionDay = null;
            snapshotReissueYear = null;
            snapshotReissueMonth = null;
            snapshotReissueDay = null;
            snapshotGroupId = null;
            snapshotCustomerId = null;
            snapshotFirstName = null;
            snapshotMiddleName = null;
            snapshotLastName = null;
            snapshotAddressLine1 = null;
            snapshotAddressLine2 = null;
            snapshotAddressLine3 = null;
            snapshotAddressStateCode = null;
            snapshotAddressCountryCode = null;
            snapshotAddressZip = null;
            snapshotPhoneNumber1 = null;
            snapshotPhoneNumber2 = null;
            snapshotSsn = null;
            snapshotGovernmentIssuedId = null;
            snapshotDateOfBirthYear = null;
            snapshotDateOfBirthMonth = null;
            snapshotDateOfBirthDay = null;
            snapshotEftAccountId = null;
            snapshotPrimaryCardHolderIndicator = null;
            snapshotFicoScore = null;
        }

        /**
         * Reproduces {@code MOVE LOW-VALUES TO ACUP-OLD-ACCT-DATA} at
         * {@code app/cbl/COACTUPC.cbl:1438}, executed by {@code 1200-EDIT-MAP-INPUTS} on the very first
         * turn, when the account filter is the only field there is.
         *
         * <p>The scope is narrower than {@link #clearSnapshot()} and deliberately so:
         * {@code 10 ACUP-OLD-ACCT-DATA} at {@code :670} is only the account half of the group, and
         * {@code 10 ACUP-OLD-CUST-DATA} at {@code :709} is a sibling subgroup that this statement does not
         * touch. Widening it to the whole group would discard customer values the source retains.</p>
         *
         * <p>Side effects: mutates this carrier only.</p>
         */
        private void clearSnapshotAccountData() {
            snapshotAccountCleared = true;
            snapshotAccountId = null;
            snapshotActiveStatus = null;
            snapshotCurrentBalance = null;
            snapshotCreditLimit = null;
            snapshotCashCreditLimit = null;
            snapshotCurrentCycleCredit = null;
            snapshotCurrentCycleDebit = null;
            snapshotOpenYear = null;
            snapshotOpenMonth = null;
            snapshotOpenDay = null;
            snapshotExpiraionYear = null;
            snapshotExpiraionMonth = null;
            snapshotExpiraionDay = null;
            snapshotReissueYear = null;
            snapshotReissueMonth = null;
            snapshotReissueDay = null;
            snapshotGroupId = null;
        }

        /**
         * Reproduces the {@code ACUP-NEW-DETAILS} half of
         * {@code INITIALIZE WS-THIS-PROGCOMMAREA} at {@code app/cbl/COACTUPC.cbl:981}, discarding the
         * received values once an update has completed or failed so that they cannot be resubmitted
         * implicitly on the next turn.
         *
         * <p>Side effects: mutates this carrier only.</p>
         */
        private void clearNewDetails() {
            accountFilter = null;
            newAccountId = null;
            newActiveStatus = null;
            newCreditLimitText = null;
            newCreditLimit = null;
            newCashCreditLimitText = null;
            newCashCreditLimit = null;
            newCurrentBalanceText = null;
            newCurrentBalance = null;
            newCurrentCycleCreditText = null;
            newCurrentCycleCredit = null;
            newCurrentCycleDebitText = null;
            newCurrentCycleDebit = null;
            newOpenYear = null;
            newOpenMonth = null;
            newOpenDay = null;
            newExpiryYear = null;
            newExpiryMonth = null;
            newExpiryDay = null;
            newReissueYear = null;
            newReissueMonth = null;
            newReissueDay = null;
            newGroupId = null;
            newCustomerId = null;
            newSsnPart1 = null;
            newSsnPart2 = null;
            newSsnPart3 = null;
            newDateOfBirthYear = null;
            newDateOfBirthMonth = null;
            newDateOfBirthDay = null;
            newFicoScore = null;
            newFirstName = null;
            newMiddleName = null;
            newLastName = null;
            newAddressLine1 = null;
            newAddressLine2 = null;
            newAddressLine3 = null;
            newStateCode = null;
            newCountryCode = null;
            newZip = null;
            newPhone1AreaCode = null;
            newPhone1Prefix = null;
            newPhone1LineNumber = null;
            newPhone2AreaCode = null;
            newPhone2Prefix = null;
            newPhone2LineNumber = null;
            newGovernmentIssuedId = null;
            newEftAccountId = null;
            newPrimaryCardHolderIndicator = null;
        }

        /**
         * Records a change to a field's attribute byte, replacing
         * {@code MOVE DFHBMPRF TO <field>A OF CACTUPAI} and its unprotected counterpart as issued by
         * {@code 3310-PROTECT-ALL-ATTRS} at {@code app/cbl/COACTUPC.cbl:3441-3495} and
         * {@code 3320-UNPROTECT-FEW-ATTRS} at {@code :3500-3561}.
         *
         * <p>Instructions are appended, never merged, because the source issues them in a specific order
         * and deliberately re-protects three fields after unprotecting them - {@code ACSTNUMA} at
         * {@code :3531}, {@code ACSCTRYA} at {@code :3547} and {@code INFOMSGA} at {@code :3560}. Merging
         * would erase evidence of that ordering; a caller applies the list in order and reaches the same
         * final state.</p>
         *
         * <p>Side effects: appends to {@link #fieldAttributes}.</p>
         *
         * @param field the symbolic-map field name, without the {@code I}/{@code O}/{@code A}/{@code C}
         *     suffix; ignored when {@code null}
         * @param value the attribute value, one of the {@code ATTRIBUTE_*} constants
         */
        private void putAttribute(final String field, final String value) {
            if (field == null) {
                return;
            }
            fieldAttributes.add(new FieldAttribute(field, FieldAttribute.ASPECT_ATTRIBUTE, value));
        }

        /**
         * Records a change to a field's colour alias, replacing {@code MOVE DFHRED TO <field>C OF
         * CACTUPAO} as issued by the account-filter branch at {@code app/cbl/COACTUPC.cbl:3171-3184} and
         * by each of the thirty-nine {@code app/cpy/CSSETATY.cpy} expansions at {@code :3208-3435}.
         *
         * <p>Side effects: appends to {@link #fieldAttributes}.</p>
         *
         * @param field the symbolic-map field name; ignored when {@code null}
         * @param value the colour value, one of the {@code COLOUR_*} constants
         */
        private void putColour(final String field, final String value) {
            if (field == null) {
                return;
            }
            fieldAttributes.add(new FieldAttribute(field, FieldAttribute.ASPECT_COLOUR, value));
        }

        /**
         * Records the {@code '*'} marker that {@code app/cpy/CSSETATY.cpy:23} writes into a field's output
         * area, reproducing {@code MOVE '*' TO (SCRNVAR2)O OF (MAPNAME3)O}.
         *
         * <p>This is emitted for the {@link FieldState#BLANK} state <strong>only</strong>, never for
         * {@link FieldState#NOT_OK}, which is precisely why a per-field boolean would be insufficient and
         * the state has to be a tri-state.</p>
         *
         * <p>Side effects: appends to {@link #fieldAttributes}.</p>
         *
         * @param field the symbolic-map field name; ignored when {@code null}
         * @param value the marker text, {@code "*"}
         */
        private void putMarker(final String field, final String value) {
            if (field == null) {
                return;
            }
            fieldAttributes.add(new FieldAttribute(field, FieldAttribute.ASPECT_MARKER, value));
        }

        /**
         * Records the cursor position, replacing {@code MOVE -1 TO <field>L OF CACTUPAI} as issued by the
         * first-match forty-four-arm {@code EVALUATE} at {@code app/cbl/COACTUPC.cbl:3009-3167}. The
         * recorded value is the literal {@code -1} the source moves, which is the 3270 convention for
         * "place the cursor here".
         *
         * <p>A {@code null} field is ignored rather than reported, because that is exactly the state the
         * source leaves when no arm of the {@code EVALUATE} matches: no {@code MOVE} is performed and the
         * terminal applies its default position.</p>
         *
         * <p>Side effects: appends to {@link #fieldAttributes}.</p>
         *
         * @param field the symbolic-map field name to place the cursor on; ignored when {@code null}
         */
        private void putCursor(final String field) {
            if (field == null) {
                return;
            }
            fieldAttributes.add(new FieldAttribute(field,
                    FieldAttribute.ASPECT_CURSOR,
                    Integer.toString(CURSOR_HERE)));
        }
    }



}
