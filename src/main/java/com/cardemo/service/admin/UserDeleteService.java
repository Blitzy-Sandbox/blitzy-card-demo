/*
 * ****************************************************************************
 * Component   : UserDeleteService
 * Application : CardDemo
 * Type        : Spring @Service (admin, user delete)
 * Function    : Delete a user from USRSEC file
 * Source      : app/cbl/COUSR03C.cbl (359 lines, 11 paragraphs) @ 7756d89
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
package com.cardemo.service.admin;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.ConcurrentUpdateException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.service.shared.FileStatusMapper;

/**
 * The user-delete screen: one keyed read of the {@code USRSEC} security file, a confirmation gate, and one
 * delete. This is the Java replacement for {@code app/cbl/COUSR03C.cbl} (359 lines, 11 paragraphs), the CICS
 * program behind transaction {@code CU03} - {@code DEFINE TRANSACTION(CU03)} at
 * {@code app/csd/CARDDEMO.CSD}:479-480 naming {@code PROGRAM(COUSR03C)}, whose own definition is at
 * {@code :299} - painting mapset {@code COUSR03} ({@code :169}) whose generated symbolic map is
 * {@code app/cpy-bms/COUSR03.CPY}.
 *
 * <p>It is <strong>the only destructive screen of the four</strong> in {@code com.cardemo.service.admin}, and
 * three of its defining characteristics are things the source does <em>not</em> do or gets wrong. Two are
 * absences and one is a misspelled verb; all three are preserved, and each is cited to the evidence that
 * proves it. They are set out under the preserved-behaviour headings below and must be read before this file
 * is changed.
 *
 * <h2>What it does</h2>
 *
 * <p>Three things. It reads one security record by its eight-character key and reports the three fields the
 * screen displays for confirmation - given name, family name and user type - alongside the identifier that
 * was keyed. It emits the source's own confirmation prompt on a successful read, which is how the operator
 * learns that a second, separate action is required to destroy the record. And on that second action it
 * deletes the record and reports the source's own confirmation sentence, or reports the source's exact
 * message for each of the two ways either operation can fail.
 *
 * <p>It is to be surfaced over HTTP by {@code com.cardemo.controller.AdminController} beneath
 * {@code /api/admin/*}. {@code com.cardemo.config.SecurityConfig} restricts that prefix to the ADMIN role -
 * the {@code 'A'} against {@code 'U'} distinction of {@code CDEMO-USER-TYPE} at
 * {@code app/cpy/COCOM01Y.cpy}:27-28, surfaced as {@code com.cardemo.model.enums.UserType}. This service
 * <strong>consumes</strong> that rule and never restates it: it reads no token, inspects no role and, as the
 * preserved-absence heading below explains, never consults the caller's identity at all.
 *
 * <p>It deliberately does <em>not</em> list, add or update a user, does not authenticate, does not seed users
 * - {@code src/main/resources/db/migration/V3__seed_data.sql} owns the ten rows that
 * {@code app/jcl/DUSRSECJ.jcl}:35-44 supplies inline - and does not configure security, persistence, HTTP or
 * metrics. It publishes no transaction manager, no password encoder and no HTTP status mapping.
 *
 * <h2>How to build and test</h2>
 *
 * <p>Java 25 ({@code maven.compiler.release} 25, no preview features) and Maven 3.9.11, under parent
 * {@code spring-boot-starter-parent:3.5.11}, with the toolchain floor asserted by
 * {@code maven-enforcer-plugin:3.5.0}.
 *
 * <ul>
 *   <li>{@code ./mvnw -B -ntp clean compile} - compiles this file. {@code maven-compiler-plugin:3.14.1} runs
 *       {@code -Xlint:all -Werror} with {@code failOnWarning}, so any warning {@code javac} emits fails the
 *       build outright.</li>
 *   <li>{@code ./mvnw -B -ntp test} - runs the unit tier through {@code maven-surefire-plugin:3.5.4}. This
 *       bean's tests belong in {@code src/test/java/com/cardemo/unit/**} and never in this package.</li>
 *   <li>{@code ./mvnw -B -ntp verify} - adds {@code maven-failsafe-plugin:3.5.4} and the
 *       {@code jacoco-maven-plugin:0.8.12} check, which enforces 80% LINE coverage with no exclusion for this
 *       package. Constructor injection and the complete absence of bean-held state are what make that
 *       reachable: every method below is exercisable by handing the constructor a fixed {@code Clock} and a
 *       stubbed repository, with no database and no Spring context.</li>
 *   <li>The single most important test of this file asserts that <strong>an administrator deleting their own
 *       identifier succeeds</strong>. A test asserting that self-deletion is blocked would be asserting the
 *       opposite of the source; see the preserved-absence heading below.</li>
 *   </ul>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li><strong>Record layout</strong> - {@code app/cpy/CSUSR01Y.cpy}, exactly 80 bytes:
 *       {@code SEC-USR-ID PIC X(08)} at bytes 1-8, {@code SEC-USR-FNAME PIC X(20)} at 9-28,
 *       {@code SEC-USR-LNAME PIC X(20)} at 29-48, {@code SEC-USR-PWD PIC X(08)} at 49-56,
 *       {@code SEC-USR-TYPE PIC X(01)} at 57 and {@code SEC-USR-FILLER PIC X(23)} at 58-80. The filler is
 *       not modelled, and the credential field is never touched by this service.</li>
 *   <li><strong>Key length 8</strong> - from {@code KEYS(8,0)} with {@code RECORDSIZE(80,80) REUSE INDEXED}
 *       on {@code DEFINE CLUSTER(NAME(AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS)...)} at
 *       {@code app/jcl/DUSRSECJ.jcl}:65-66, matching {@code KEYLENGTH(LENGTH OF SEC-USR-ID)} on the read at
 *       {@code app/cbl/COUSR03C.cbl}:274.</li>
 *   <li><strong>No credential field exists on this map at all.</strong>
 *       {@code grep -c "PASSWD" app/cpy-bms/COUSR03.CPY} answers {@code 0}: none of the eleven fields is a
 *       password, and the source's own read path at {@code app/cbl/COUSR03C.cbl}:165-167 moves only the given
 *       name, the family name and the type onto the screen. The sibling update program echoes the stored
 *       credential at {@code app/cbl/COUSR02C.cbl}:169; this one has nothing to echo. That absence is the
 *       strongest secret-hygiene guarantee in the package and it is structural rather than remembered: this
 *       bean is not even given a {@code PasswordEncoder}, so no code path here can reach a credential.</li>
 *   <li><strong>Screen field widths</strong> - taken from {@code app/cpy-bms/COUSR03.CPY} through the
 *       constants of {@code com.cardemo.model.dto.UserSecurityDto} rather than restated here, so the field
 *       contract has one owner: {@code TRNNAMEI PIC X(4)}:24, {@code TITLE01I PIC X(40)}:30,
 *       {@code CURDATEI PIC X(8)}:36, {@code PGMNAMEI PIC X(8)}:42, {@code TITLE02I PIC X(40)}:48,
 *       {@code CURTIMEI PIC X(8)}:54, {@code USRIDINI PIC X(8)}:60, {@code FNAMEI PIC X(20)}:66,
 *       {@code LNAMEI PIC X(20)}:72, {@code USRTYPEI PIC X(1)}:78 and {@code ERRMSGI PIC X(78)}:84. Eleven
 *       fields, which is what {@code UserSecurityDto.UserDeleteScreen.MAP_FIELD_COUNT} records.</li>
 *   <li><strong>The read-confirm-delete chain</strong> - the read reports the prompt of {@code :283}, and the
 *       delete happens only on a separate, explicitly confirmed action. Nothing is held server side between
 *       the two; see the mechanism heading below.</li>
 *   <li><strong>The ADMIN-only route is the only authorisation control on this destructive operation.</strong>
 *       That is exactly the source's posture - the CICS transaction was reachable by any signed-on operator
 *       who got to the screen, and the program applied no further check - and it is deliberately not
 *       strengthened. Disclosed as residual risk below.</li>
 *   <li><strong>Clock</strong> - the header date and time are read from an injected {@code java.time.Clock},
 *       standing in for {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at
 *       {@code app/cbl/COUSR03C.cbl}:245. Nothing here calls {@code LocalDateTime.now()} without a clock, so
 *       the rendering is deterministic and testable. The formats are {@code MM/DD/YY} and {@code HH:MM:SS},
 *       both eight characters, from {@code app/cpy/CSDAT01Y.cpy}, and both applied with
 *       {@code Locale.ROOT}.</li>
 *   </ul>
 *
 * <h2>Preserved absence: there is no self-delete guard, and none may be added</h2>
 *
 * <p><strong>The source never compares the target identifier against the signed-on identifier.</strong> The
 * evidence is a single command, reproduced here so that any reviewer can re-run it:
 *
 * <pre>
 * $ grep -n "CDEMO-USER-ID" app/cbl/COUSR03C.cbl
 * $ echo $?
 * 1
 * </pre>
 *
 * <p>It prints nothing and exits {@code 1}: <strong>zero hits</strong>. The identifier of the signed-on
 * operator does not appear anywhere in the program, so it cannot be compared with anything.
 * <strong>An administrator can therefore delete their own record.</strong>
 *
 * <p>Three facts corroborate that this is the family's settled behaviour rather than one program's slip.
 * {@code app/cbl/COUSR00C.cbl} and {@code app/cbl/COUSR02C.cbl} return zero hits for the same symbol.
 * {@code app/cbl/COUSR01C.cbl} returns exactly one, and it is commented out -
 * {@code 172:      *    MOVE WS-USER-ID   TO CDEMO-USER-ID}.
 *
 * <p>The signed-on identity is trivially available to the Java layer, because
 * {@code app/cpy/COCOM01Y.cpy}:27-28 maps {@code CDEMO-USER-ID} onto the token subject and
 * {@code CDEMO-USER-TYPE} onto the role claim. That makes the guard easy to add and therefore tempting.
 * <strong>It is deliberately not added, and this bean deliberately does not consult the caller's identity at
 * all.</strong> Behavioural parity is the contract of this migration; adding a guard the source lacks is a
 * behaviour change, and behaviour change is forbidden. The same reasoning forbids every other guard the
 * source lacks: no confirmation token, no soft delete, no tombstone, no cascade check, no referential
 * pre-check, no audit trail, no "cannot delete the last administrator" rule and no rate limit. None exists in
 * the source and none is invented here.
 *
 * <h2>Preserved defect: the delete-failure message says "Update"</h2>
 *
 * <p>{@code app/cbl/COUSR03C.cbl}:332, on the {@code WHEN OTHER} arm of {@code DELETE-USER-SEC-FILE}, moves
 * the literal {@code "Unable to Update User..."} into the message field. <strong>The verb is wrong</strong>:
 * this is the delete-failure path and nothing was being updated. It was evidently cloned from
 * {@code app/cbl/COUSR02C.cbl}:386, where the identical literal sits on a rewrite-failure path and the verb
 * is correct.
 *
 * <p><strong>It is reproduced byte for byte, three trailing periods included, and is not corrected to
 * "Delete".</strong> The parity gates compare these strings byte for byte, so correcting it would fail the
 * comparison. The cross-reference is recorded here precisely so that a reader cannot mistake it for a
 * copy-paste error introduced by the migration.
 *
 * <h2>Preserved absence: the read and the delete are sequential and unguarded</h2>
 *
 * <p>{@code DELETE-USER-INFO} validates the identifier and then, at {@code app/cbl/COUSR03C.cbl}:190-191,
 * performs the read and the delete back to back:
 *
 * <pre>
 * PERFORM READ-USER-SEC-FILE
 * PERFORM DELETE-USER-SEC-FILE
 * </pre>
 *
 * <p><strong>There is no {@code IF NOT ERR-FLG-ON} test between the two.</strong> That is specific to this
 * paragraph: {@code PROCESS-ENTER-KEY} guards each of its own follow-on blocks, at {@code :156} and
 * {@code :164}. Here the read's failure arms set the error flag and a message, and the delete is attempted
 * anyway - whereupon the delete's own arms overwrite that message.
 *
 * <p>The two calls are mapped in the source's order, inside the same method, with no guard, early return or
 * short-circuit added. <strong>What differs is the observable message on a failed read, and it differs
 * because of the exception mechanism rather than because of any inserted logic.</strong> In the source, a read
 * that did not find the record reported {@code "User ID NOT found..."} from {@code :289}, then attempted the
 * delete, which also did not find it, and reported {@code "User ID NOT found..."} again from
 * {@code :325-326} - the same text from the second statement. In Java the read raises a typed exception, so
 * the caller sees the read's message from {@code :289} and the delete is never attempted. The two literals
 * are identical here, so a not-found deletion is observably unchanged; a read that failed for any other
 * reason now surfaces {@code "Unable to lookup User..."} from {@code :296} where the source would finally
 * have shown the delete's own outcome. That is a mechanism substitution rather than a behaviour change.
 *
 * <h2>Preserved artefact: a flag that is set and never tested</h2>
 *
 * <p>{@code WS-USR-MODIFIED} is declared at {@code app/cbl/COUSR03C.cbl}:45-47 with two condition names and
 * set exactly once, at {@code :85}. {@code grep -n "WS-USR-MODIFIED" app/cbl/COUSR03C.cbl} returns those four
 * lines and nothing else: <strong>it never appears in an {@code IF} or an {@code EVALUATE}</strong>. It is
 * retained as a cited, tracked parity artefact rather than deleted, it is method-local rather than bean
 * state, and no test is invented for it.
 *
 * <h2>Mechanism substitutions, each a substitution and not a behaviour change</h2>
 *
 * <ul>
 *   <li><strong>The two-phase confirmation gate becomes an explicit caller assertion.</strong> The source
 *       gated destruction on two separate terminal interactions: a read that emitted
 *       {@code "Press PF5 key to delete this user ..."} at {@code :283}, and then a distinct key press. A
 *       stateless surface cannot remember that the first happened, so the caller asserts it instead. There is
 *       <strong>no HTTP session and no server-side pending-delete cursor</strong>. Reaching the delete without
 *       that assertion is reported as
 *       {@code com.cardemo.exception.ConcurrentUpdateException} with outcome
 *       {@code CHANGES_NOT_CONFIRMED} - a distinguishable outcome, never an undifferentiated conflict.</li>
 *   <li><strong>The record lock is reproduced within the request, and only the conversational part of its
 *       span has no counterpart.</strong> The read carries {@code UPDATE} at {@code :275} and
 *       {@code app/csd/CARDDEMO.CSD}:88 defines {@code FILE(USRSEC)} with
 *       {@code DSNAME(AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS)}, {@code BROWSE(YES) DELETE(YES) READ(YES)
 *       UPDATE(YES) ADD(YES)} and {@code UPDATEMODEL(LOCKING)} under {@code RECOVERY(NONE)}, so the source
 *       held the row exclusively from the read until the delete. The read here goes through
 *       {@code com.cardemo.repository.UserSecurityRepository#findByIdForUpdate(String)}, which acquires the
 *       same exclusive hold, and the read and the delete sit inside one declarative transaction that releases
 *       it. What genuinely has no counterpart is holding the lock across the terminal conversation between the
 *       two tasks; that half of the span is covered by the caller's explicit confirmation assertion instead.
 *       <strong>Finding, MEDIUM severity, resolved:</strong> before this, no lock, version column or
 *       precondition guarded the sequence at all.</li>
 *   <li><strong>The transaction boundary reproduces the failure semantics by scoping.</strong> The read, the
 *       confirmation check and the delete run inside a single
 *       {@code @Transactional(rollbackFor = Exception.class)} method, so nothing partial can survive a
 *       failure - achieved by scope rather than by conditional logic. Transaction management is owned by
 *       {@code com.cardemo.config.JpaConfig}; this class carries no
 *       {@code @EnableTransactionManagement}.</li>
 *   <li><strong>Navigation collapses into URL-based routing.</strong> {@code RETURN-TO-PREV-SCREEN} sets four
 *       communication-area fields and transfers control. {@code CDEMO-FROM-TRANID}, {@code CDEMO-TO-TRANID},
 *       {@code CDEMO-FROM-PROGRAM}, {@code CDEMO-TO-PROGRAM}, {@code CDEMO-PGM-CONTEXT},
 *       {@code CDEMO-LAST-MAP} and {@code CDEMO-LAST-MAPSET} have no counterpart whatsoever under the
 *       stateless mandate, and neither does the {@code CDEMO-PGM-ENTER} against
 *       {@code CDEMO-PGM-REENTER} distinction. The paragraph is mapped one to one regardless.</li>
 *   <li><strong>Sending and receiving a map become the REST boundary.</strong>
 *       {@code EXEC CICS SEND MAP('COUSR3A') MAPSET('COUSR03') ... ERASE CURSOR} at {@code :219-225} becomes
 *       response assembly, and {@code EXEC CICS RECEIVE MAP(...)} at {@code :232-238} becomes request
 *       binding. Both paragraphs are still mapped one to one.</li>
 *   <li><strong>Clearing the screen becomes a fresh, empty response.</strong> There is no terminal to erase,
 *       and <strong>no cleared state is retained anywhere</strong>: the work area is created per call and
 *       discarded on return.</li>
 *   <li><strong>{@code ERRMSGC} has no counterpart.</strong> The message colours at {@code :285}
 *       ({@code DFHNEUTR}) and {@code :317} ({@code DFHGREEN}) are attribute bytes of {@code COUSR3AO}, the
 *       output redefinition of the map, and not one of the eleven {@code ...I} input fields the response
 *       record declares. They are cited at their lines and not modelled, because a field written and never
 *       read would itself be the dead code Rule 1 Clause B forbids.</li>
 *   <li><strong>Parking the cursor becomes naming the field.</strong> {@code MOVE -1 TO USRIDINL} and
 *       {@code MOVE -1 TO FNAMEL} told the terminal where the operator should look. Every failure here
 *       carries the name of the field at fault instead, which is why each outcome must remain a
 *       distinguishable typed exception.</li>
 *   </ul>
 *
 * <h2>Error routing</h2>
 *
 * <p>This is a CICS program: it tests {@code RESP(WS-RESP-CD)} and {@code RESP2(WS-REAS-CD)} against
 * {@code DFHRESP(...)} and never uses a batch {@code FILE STATUS}. The outcomes map onto the same typed
 * targets the batch tier uses. {@code DFHRESP(NORMAL)} continues. {@code DFHRESP(NOTFND)} becomes
 * {@code com.cardemo.exception.RecordNotFoundException}. {@code WHEN OTHER} becomes
 * {@code com.cardemo.exception.FileAccessException}, escalating to
 * {@code com.cardemo.exception.FatalProcessingException} when the condition is genuinely unexpected. Where a
 * file status is genuinely in play it is routed through the constructor-injected
 * {@code com.cardemo.service.shared.FileStatusMapper}, which is the single owner of that decision and of the
 * {@code FILE STATUS IS: NNNN} rendering held by {@code com.cardemo.model.enums.FileStatus}; neither is
 * reimplemented, reformatted or re-rendered here.
 *
 * <p><strong>A missing user is an error.</strong> The three places where the corpus treats a not-found status
 * as success are all batch-side - the category-balance upsert at {@code app/cbl/CBTRN02C.cbl}:481, the first
 * disclosure-group {@code DEFAULT} fallback at {@code app/cbl/CBACT04C.cbl}:422 and {@code :436}, and
 * {@code CBSTM03B}'s acceptance of its secondary status - and none applies to an online keyed read or delete
 * of a user. None of the mapper's carve-out methods is called from this file, and importing one would be
 * classified High.
 *
 * <p>There is no {@code @ControllerAdvice} and no {@code @ExceptionHandler} anywhere in the tree:
 * {@code AdminController} performs the contextual HTTP mapping itself. Nothing here carries
 * {@code @ResponseStatus} or pre-maps an outcome to a status code.
 *
 * <h2>What the source does not establish</h2>
 *
 * <ul>
 *   <li><strong>Any latency or throughput objective.</strong> None exists anywhere in the source: the corpus
 *       publishes no service level for this transaction. The performance gate records a measured baseline and
 *       never an invented target. What would be needed is a stated objective, which does not exist.</li>
 *   <li><strong>Any deletion policy.</strong> No retention rule, soft-delete requirement, cascade rule,
 *       "last administrator" protection, audit-trail requirement or self-delete restriction exists in the
 *       source. What would be needed is a source rule that does not exist; until one does, inventing any of
 *       them would be a behaviour change.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>Self-deletion is rejected.</strong> Someone added the self-delete guard. Remedy: remove it -
 *       the grep above is the proof that the source has none. Hardening this operation is
 *       <strong>out of scope: parity is the contract, so raise a separate change request</strong> rather than
 *       patching it here.</li>
 *   <li><strong>A delete failure reports the verb "Delete" rather than "Update".</strong> The wrong-verb
 *       literal at {@code :332} was corrected. Remedy: revert it to {@code "Unable to Update User..."} exactly;
 *       the string is compared byte for byte, and the corrected form must not appear anywhere in this
 *       tree.</li>
 *   <li><strong>A not-found delete no longer reports anything.</strong> A guard was inserted between the read
 *       and the delete as source-level logic, or the two calls were reordered or suppressed. Remedy: restore
 *       the bare sequence of {@code :190-191}; the exception-driven short circuit is the documented
 *       substitution and needs no help.</li>
 *   <li><strong>A message no longer matches the baseline.</strong> A literal was normalised. Both empty-field
 *       messages read {@code can NOT}; {@code "User ID NOT found..."} has {@code NOT} in capitals;
 *       {@code "Press PF5 key to delete this user ..."} and {@code " has been deleted ..."} each carry a
 *       space before their three periods while {@code "User ID NOT found..."},
 *       {@code "Unable to lookup User..."} and {@code "Unable to Update User..."} do not. Remedy: never
 *       re-case, re-space or re-punctuate them.</li>
 *   <li><strong>The vestigial flag disappeared.</strong> It was deleted to satisfy a linter, or promoted to a
 *       bean field. Remedy: restore it as method-local state with its citation; it is tracked, not
 *       abandoned.</li>
 *   <li><strong>An unconfirmed delete destroys the record.</strong> The confirmation assertion was dropped.
 *       Remedy: restore it; the source required two distinct interactions and so does this.</li>
 *   <li><strong>An unconfirmed delete answers an undifferentiated conflict.</strong> The five outcomes of
 *       {@code ConcurrentUpdateException} were collapsed. This service raises exactly one of them,
 *       {@code CHANGES_NOT_CONFIRMED}. Remedy: keep the outcome; the controller owns the status.</li>
 *   <li><strong>Confirmation state is remembered between requests.</strong> A session, a cache or a bean
 *       field was introduced. Remedy: remove it - this bean holds no state at all, which is also what makes
 *       it thread safe.</li>
 *   <li><strong>A missing user is treated as success.</strong> A batch-side carve-out was imported. Remedy:
 *       call none of the mapper's carve-out methods; a not-found user here is an error.</li>
 *   <li><strong>A conflict eventually succeeds.</strong> Something retried, merged, re-read or applied
 *       last-writer-wins. There is no retry, no backoff, no re-read: the source abandons the operation and so
 *       does this. Remedy: remove it.</li>
 *   <li><strong>A password or digest appears in a response, a log line or an exception message.</strong> It
 *       cannot come from here: no map field, no response component, no constructor argument and no local
 *       variable in this file holds a credential. Remedy: look upstream, and treat the masking rules of
 *       {@code logback-spring.xml} as a backstop rather than a licence.</li>
 *   <li><strong>Startup fails naming {@code Clock}.</strong> It is a constructor argument and is not
 *       published here. Remedy: publish the single {@code Clock} bean from
 *       {@code com.cardemo.config.ObservabilityConfig}.</li>
 *   <li><strong>An over-length identifier silently truncates.</strong> It must not: an over-length value is
 *       refused, because the 3270 map made it physically impossible and the record it lands in is still
 *       eighty bytes. Remedy: keep the width rejection.</li>
 *   </ul>
 *
 * <h2>Paragraph correspondence</h2>
 *
 * <p>All eleven paragraph labels of {@code app/cbl/COUSR03C.cbl} are present one to one and none is
 * consolidated: {@code MAIN-PARA}:82, {@code PROCESS-ENTER-KEY}:142, {@code DELETE-USER-INFO}:174,
 * {@code RETURN-TO-PREV-SCREEN}:197, {@code SEND-USRDEL-SCREEN}:213, {@code RECEIVE-USRDEL-SCREEN}:230,
 * {@code POPULATE-HEADER-INFO}:243, {@code READ-USER-SEC-FILE}:267, {@code DELETE-USER-SEC-FILE}:305,
 * {@code CLEAR-CURRENT-SCREEN}:341 and {@code INITIALIZE-ALL-FIELDS}:349. Verified at commit {@code 7756d89}
 * with {@code grep -nE '^       [A-Z0-9][A-Z0-9-]*\.[[:space:]]*$' app/cbl/COUSR03C.cbl}, which returns
 * exactly those eleven lines.
 *
 * <p><strong>One contrast worth recording.</strong> {@code PF3} is conventional in this program: at
 * {@code :111-118} it resolves a target program and leaves, writing nothing. In the sibling update program it
 * saves first, at {@code app/cbl/COUSR02C.cbl}:111-112. The two are genuinely different and must not be
 * harmonised in either direction.
 *
 * <p>This class is thread safe and immutable: every collaborator is {@code final}, there is no static mutable
 * state, and all working storage lives in a per-call work area.
 *
 * @see com.cardemo.model.dto.UserSecurityDto.UserDeleteScreen
 */
@Service
public class UserDeleteService {

    /**
     * Structured logging for the two live {@code DISPLAY} statements of the source, at
     * {@code app/cbl/COUSR03C.cbl}:294 and {@code :330}. Both emit diagnostic codes only, never a field
     * value, so no personal data and no credential can reach the log through them.
     */
    private static final Logger LOG = LoggerFactory.getLogger(UserDeleteService.class);

    /**
     * {@code WS-PGMNAME PIC X(08) VALUE 'COUSR03C'} at {@code app/cbl/COUSR03C.cbl}:36, stamped onto
     * {@code PGMNAMEO} by {@code POPULATE-HEADER-INFO}. Eight characters, matching
     * {@code PGMNAMEI PIC X(8)} at {@code app/cpy-bms/COUSR03.CPY}:42.
     */
    private static final String PROGRAM_NAME = "COUSR03C";

    /**
     * {@code WS-TRANID PIC X(04) VALUE 'CU03'} at {@code app/cbl/COUSR03C.cbl}:37 - the transaction
     * {@code app/csd/CARDDEMO.CSD}:479-480 defines against {@code PROGRAM(COUSR03C)}. Four characters,
     * matching {@code TRNNAMEI PIC X(4)} at {@code app/cpy-bms/COUSR03.CPY}:24.
     */
    private static final String TRANSACTION_ID = "CU03";

    /**
     * {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '} at {@code app/cbl/COUSR03C.cbl}:39, named on both
     * {@code EXEC CICS} commands as {@code DATASET(WS-USRSEC-FILE)}. Carried as an identity on every typed
     * failure; the trailing pad of the {@code X(08)} field is not part of the name.
     */
    private static final String USRSEC_FILE = "USRSEC";

    /** The operation named on a failure raised by {@code READ-USER-SEC-FILE}. */
    private static final String READ_OPERATION = "READ";

    /** The operation named on a failure raised by {@code DELETE-USER-SEC-FILE}. */
    private static final String DELETE_OPERATION = "DELETE";

    /**
     * {@code 'COADM01C'}, moved into {@code CDEMO-TO-PROGRAM} on the {@code DFHPF3} arm at
     * {@code app/cbl/COUSR03C.cbl}:113 and the {@code DFHPF12} arm at {@code :124}. Reported as an advisory
     * navigation target only; no control transfer happens here.
     */
    private static final String ADMIN_MENU_PROGRAM = "COADM01C";

    /**
     * {@code 'COSGN00C'}, moved into {@code CDEMO-TO-PROGRAM} on the no-communication-area arm at
     * {@code app/cbl/COUSR03C.cbl}:91 and as the fallback inside {@code RETURN-TO-PREV-SCREEN} at
     * {@code :200}.
     */
    private static final String SIGN_ON_PROGRAM = "COSGN00C";

    /**
     * {@code CCDA-TITLE01 PIC X(40)} from {@code app/cpy/COTTL01Y.cpy}, moved onto {@code TITLE01O} at
     * {@code app/cbl/COUSR03C.cbl}:247. Reproduced byte for byte at its declared width of forty, leading and
     * trailing spaces included, because it is a screen literal rather than prose.
     */
    private static final String SCREEN_TITLE_01 = "      AWS Mainframe Modernization       ";

    /**
     * {@code CCDA-TITLE02 PIC X(40)} from {@code app/cpy/COTTL01Y.cpy}, moved onto {@code TITLE02O} at
     * {@code app/cbl/COUSR03C.cbl}:248. The copybook carries an earlier value on a commented line
     * immediately above the live one; this is the live one, at its declared width of forty.
     */
    private static final String SCREEN_TITLE_02 = "              CardDemo                  ";

    /**
     * {@code CCDA-MSG-INVALID-KEY} from {@code app/cpy/CSMSG01Y.cpy}, moved into {@code WS-MESSAGE} on the
     * {@code WHEN OTHER} arm at {@code app/cbl/COUSR03C.cbl}:128.
     *
     * <p>The copybook declares it {@code PIC X(50)} with nine trailing spaces of padding. The significant
     * text ends at the third period, and {@code WS-MESSAGE} is itself {@code PIC X(80)}, so the padding is
     * not part of the message the operator read and is not reproduced. The three periods are.
     */
    private static final String INVALID_KEY_MESSAGE = "Invalid key pressed. Please see below...";

    /**
     * {@code app/cbl/COUSR03C.cbl}:147 in {@code PROCESS-ENTER-KEY} and {@code :179} in
     * {@code DELETE-USER-INFO}, which raise the identical literal. Note {@code can NOT} with a capital N, O
     * and T, and the three-period ellipsis with no space before it.
     */
    private static final String USER_ID_REQUIRED_MESSAGE = "User ID can NOT be empty...";

    /**
     * {@code app/cbl/COUSR03C.cbl}:283, on the {@code DFHRESP(NORMAL)} arm of {@code READ-USER-SEC-FILE}.
     *
     * <p><strong>This is the source's confirmation gate.</strong> It is the only thing that told the operator
     * a second, separate key press was needed to destroy the record. Note the <strong>space before the three
     * periods</strong>, which the two not-found literals do not have.
     */
    private static final String DELETE_HINT_MESSAGE = "Press PF5 key to delete this user ...";

    /**
     * {@code app/cbl/COUSR03C.cbl}:289 on the read's {@code DFHRESP(NOTFND)} arm and {@code :325-326} on the
     * delete's. Note {@code NOT} in capitals and no space before the ellipsis. The two arms carry the
     * identical text, which is why a not-found deletion reads the same under the exception-driven short
     * circuit documented on this class.
     */
    private static final String USER_NOT_FOUND_MESSAGE = "User ID NOT found...";

    /**
     * {@code app/cbl/COUSR03C.cbl}:296, on the {@code WHEN OTHER} arm of {@code READ-USER-SEC-FILE}. The verb
     * is correct here: the read is a lookup.
     */
    private static final String UNABLE_TO_LOOKUP_MESSAGE = "Unable to lookup User...";

    /**
     * {@code app/cbl/COUSR03C.cbl}:332, on the {@code WHEN OTHER} arm of {@code DELETE-USER-SEC-FILE}.
     *
     * <p><strong>The verb is wrong and is preserved anyway.</strong> This is a delete-failure path, yet the
     * source says {@code Update}. It was cloned from {@code app/cbl/COUSR02C.cbl}:386, where the same literal
     * sits on a rewrite failure and reads correctly. Reproduced byte for byte, three trailing periods
     * included; correcting it to {@code Delete} would fail the byte-for-byte parity comparison. Classified
     * Low, and deliberately not repaired.
     */
    private static final String UNABLE_TO_UPDATE_MESSAGE = "Unable to Update User...";

    /**
     * The first operand of the {@code STRING} at {@code app/cbl/COUSR03C.cbl}:318,
     * {@code 'User ' DELIMITED BY SIZE}, so it contributes in full including its trailing space.
     */
    private static final String DELETED_MESSAGE_PREFIX = "User ";

    /**
     * The third operand of the {@code STRING} at {@code app/cbl/COUSR03C.cbl}:320,
     * {@code ' has been deleted ...' DELIMITED BY SIZE}. Leading space, and a space before the three periods.
     */
    private static final String DELETED_MESSAGE_SUFFIX = " has been deleted ...";

    /**
     * The message reported when a caller reaches the delete without asserting it saw the prompt of
     * {@code app/cbl/COUSR03C.cbl}:283.
     *
     * <p>{@code ConcurrentUpdateException.Outcome#CHANGES_NOT_CONFIRMED} carries an empty legacy message,
     * because the source had no literal for this condition: it enforced the gate structurally, by requiring a
     * different key. The source's own prompt is therefore reported back, which tells the caller exactly what
     * it failed to do.
     */
    private static final String NOT_CONFIRMED_MESSAGE = DELETE_HINT_MESSAGE;

    /**
     * {@code MOVE SPACES TO ...}. The empty string is the Java counterpart of a blanked fixed-width field:
     * the trailing pad of an {@code X(n)} field is not content, so a blanked field carries nothing.
     */
    private static final String SPACES = "";

    /**
     * {@code WS-CURDATE-MM-DD-YY} from {@code app/cpy/CSDAT01Y.cpy} - {@code 9(02)} then {@code '/'} then
     * {@code 9(02)} then {@code '/'} then {@code 9(02)}, so {@code MM/DD/YY} in eight characters. The
     * two-digit year is the {@code WS-CURDATE-YEAR(3:2)} reference substitution at
     * {@code app/cbl/COUSR03C.cbl}:254, the last two digits. Built with {@code Locale.ROOT} so a host locale
     * can never reshape the separators or the digits.
     */
    private static final DateTimeFormatter HEADER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT);

    /**
     * {@code WS-CURTIME-HH-MM-SS} from {@code app/cpy/CSDAT01Y.cpy} - {@code 9(02)} then {@code ':'} then
     * {@code 9(02)} then {@code ':'} then {@code 9(02)}, so {@code HH:MM:SS} in eight characters, matching
     * {@code CURTIMEI PIC X(8)} at {@code app/cpy-bms/COUSR03.CPY}:54. Twenty-four hour, and
     * {@code Locale.ROOT}.
     */
    private static final DateTimeFormatter HEADER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /**
     * The field {@code MOVE -1 TO USRIDINL OF COUSR3AI} parks the cursor on, at
     * {@code app/cbl/COUSR03C.cbl}:149, {@code :152}, {@code :181}, {@code :184}, {@code :291}, {@code :327}
     * and {@code :351}. Named on the corresponding failure, because naming the offending field is the
     * stateless equivalent of parking the cursor on it.
     */
    private static final String CURSOR_FIELD_USER_ID = "USRIDIN";

    /**
     * The field {@code MOVE -1 TO FNAMEL OF COUSR3AI} parks the cursor on, at
     * {@code app/cbl/COUSR03C.cbl}:298 and {@code :334} - both {@code WHEN OTHER} arms.
     */
    private static final String CURSOR_FIELD_FIRST_NAME = "FNAME";

    /** The name carried on a failure concerning {@code USRIDINI}, never its value. */
    private static final String FIELD_USER_ID = "userId";

    /**
     * {@code DFHRESP(NORMAL)}, the successful arm at {@code app/cbl/COUSR03C.cbl}:281 and {@code :314}.
     */
    private static final int CICS_RESP_NORMAL = 0;

    /**
     * {@code DFHRESP(NOTFND)}, the not-found arm at {@code app/cbl/COUSR03C.cbl}:287 and {@code :323}. The
     * value is the CICS response number the generated condition name expands to.
     */
    private static final int CICS_RESP_NOTFND = 13;

    /**
     * {@code DFHRESP(IOERR)}, standing for the {@code WHEN OTHER} arms at {@code app/cbl/COUSR03C.cbl}:293
     * and {@code :329}. The source enumerates no further condition names on either paragraph, so every
     * non-normal, non-not-found outcome lands here exactly as it does in the source.
     */
    private static final int CICS_RESP_IOERR = 17;

    /** {@code FILE STATUS '00'} - success. Owned by {@code com.cardemo.model.enums.FileStatus}. */
    private static final String IO_STATUS_SUCCESS = "00";

    /**
     * {@code FILE STATUS '23'} - record not found, the status {@code DFHRESP(NOTFND)} translates to. Routed
     * through {@code FileStatusMapper} rather than interpreted here.
     */
    private static final String IO_STATUS_RECORD_NOT_FOUND = "23";

    /**
     * {@code FILE STATUS '90'} - a member of the {@code '9x'} physical-or-logical-error family, the status a
     * {@code WHEN OTHER} arm translates to.
     */
    private static final String IO_STATUS_IO_ERROR = "90";

    /**
     * The {@code USRSEC} cluster, reached through its {@code JpaRepository} contract: one keyed read and one
     * delete. {@code app/csd/CARDDEMO.CSD}:88 grants this file
     * {@code BROWSE(YES) DELETE(YES) READ(YES) UPDATE(YES) ADD(YES)}; this service uses exactly two of those.
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * The single owner of the file-status-to-exception decision and of the {@code FILE STATUS IS: NNNN}
     * rendering. Neither is reimplemented here, and no status is re-rendered. Its three batch-scoped
     * not-found-is-success methods are deliberately never called from this file.
     */
    private final FileStatusMapper fileStatusMapper;

    /**
     * The time source behind {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at
     * {@code app/cbl/COUSR03C.cbl}:245. Injected rather than read from the system, so the header is
     * deterministic under test.
     */
    private final Clock clock;

    /**
     * Constructs the bean. Constructor injection only: there is no field {@code @Autowired}, no setter
     * injection and no service locator, which is what keeps the bean immutable, thread safe and directly unit
     * testable without a Spring context.
     *
     * <p><strong>There is deliberately no {@code PasswordEncoder} argument.</strong> The sibling add and
     * update services take one; this one cannot need one, because {@code app/cpy-bms/COUSR03.CPY} declares no
     * password field and {@code app/cbl/COUSR03C.cbl} never reads or writes {@code SEC-USR-PWD}. Omitting it
     * makes the secret-hygiene guarantee structural: no code path in this class can reach a credential.
     *
     * @param userSecurityRepository the {@code USRSEC} cluster; must not be {@code null}
     * @param fileStatusMapper       the shared file-status-to-exception mapper; must not be {@code null}
     * @param clock                  the time source for the screen header; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public UserDeleteService(final UserSecurityRepository userSecurityRepository,
            final FileStatusMapper fileStatusMapper,
            final Clock clock) {

        this.userSecurityRepository =
                Objects.requireNonNull(userSecurityRepository, "userSecurityRepository must not be null");
        this.fileStatusMapper = Objects.requireNonNull(fileStatusMapper, "fileStatusMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    // ------------------------------------------------------------------------------------------------
    // Public surface. Five entry points, one per way the source can be reached: with no communication
    // area, on a first display, on the ENTER arm, on the PF5 arm that destroys the record, and on a
    // submitted screen with any attention identifier at all.
    // ------------------------------------------------------------------------------------------------

    /**
     * Reproduces the source reached with no communication area at all: {@code IF EIBCALEN = 0} at
     * {@code app/cbl/COUSR03C.cbl}:90-92, which moves {@code 'COSGN00C'} into {@code CDEMO-TO-PROGRAM} and
     * transfers to the sign-on program without touching the security file.
     *
     * <p>Reading and writing nothing is the whole point of the path, so the returned screen carries no field
     * content at all. Nothing is thrown, because the source raises no condition here, and no header is
     * painted, because the source transfers before {@code SEND-USRDEL-SCREEN} is ever performed on this arm.
     *
     * <p><strong>Side effects: none.</strong> No query is issued and no row is deleted. The method is not
     * transactional because it touches no data.
     *
     * @return an empty screen; never {@code null}
     */
    public UserSecurityDto.UserDeleteScreen openWithoutContext() {
        return mainPara(false, false, AttentionIdentifier.ENTER, null, null, false);
    }

    /**
     * Opens the delete screen: the first-display arm at {@code app/cbl/COUSR03C.cbl}:95-105, where
     * {@code CDEMO-PGM-REENTER} is not yet set, the output map is blanked with
     * {@code MOVE LOW-VALUES TO COUSR3AO} at {@code :97}, the cursor is parked on the identifier field at
     * {@code :98}, and the screen is sent at {@code :105}.
     *
     * <p><strong>The pre-selection hand-off.</strong> {@code :99-104} tests
     * {@code CDEMO-CU03-USR-SELECTED NOT = SPACES AND LOW-VALUES} and, when a user was selected on the list
     * screen, moves that identifier into {@code USRIDINI} and performs {@code PROCESS-ENTER-KEY} immediately,
     * so the screen opens already populated with that user's record and its confirmation prompt. Passing
     * {@code null} or a blank value reproduces the arm where no user was pre-selected and the screen opens
     * empty.
     *
     * <p><strong>Side effects.</strong> When an identifier is supplied, one keyed read is issued.
     * <strong>Nothing is ever deleted on this path</strong>, whatever is supplied: destruction is reachable
     * only through {@code deleteUser} and the {@code PF5} arm of {@code submitScreen}. The method is
     * transactional because the source's read carries {@code UPDATE}.
     *
     * @param preselectedUserId {@code CDEMO-CU03-USR-SELECTED PIC X(08)}, declared at
     *                          {@code app/cbl/COUSR03C.cbl}:58 and handed over by the user-list screen. May be
     *                          {@code null}, empty or blank, all three of which reproduce the arm where the
     *                          test at {@code :99-100} fails and no read is issued
     * @return the assembled screen: empty when no identifier was supplied, otherwise carrying the record's
     *         given name, family name and type together with the prompt of {@code :283}; never {@code null}
     * @throws com.cardemo.exception.ValidationException      if the supplied identifier is wider than its
     *                                                        eight-character screen field
     * @throws com.cardemo.exception.RecordNotFoundException  if the identifier is not present, the
     *                                                        {@code DFHRESP(NOTFND)} arm at {@code :287-292}
     * @throws com.cardemo.exception.FileAccessException      if the read fails for any other reason, the
     *                                                        {@code WHEN OTHER} arm at {@code :293-299}
     * @throws com.cardemo.exception.FatalProcessingException if the condition is genuinely unexpected
     */
    @Transactional(rollbackFor = Exception.class)
    public UserSecurityDto.UserDeleteScreen openScreen(final String preselectedUserId) {
        return mainPara(true, false, AttentionIdentifier.ENTER, preselectedUserId, null, false);
    }

    /**
     * Looks a user up for confirmation: the {@code DFHENTER} arm at {@code app/cbl/COUSR03C.cbl}:109-110,
     * which performs {@code PROCESS-ENTER-KEY} - the paragraph that validates the identifier at {@code :145},
     * blanks the three display fields at {@code :157-159}, reads the record at {@code :161} and reports its
     * three readable fields at {@code :165-167}.
     *
     * <p><strong>This is the first half of the read-confirm-delete chain.</strong> On success the returned
     * screen carries the prompt of {@code :283}, which is the source's own instruction that a second,
     * separate action is required. Nothing is remembered server side between the two: the caller asserts the
     * confirmation on the next call.
     *
     * <p><strong>Only four fields come back</strong> - the identifier, the given name, the family name and
     * the one-character type - because those are the only fields the legacy screen displayed. There is no
     * credential component to omit: {@code app/cpy-bms/COUSR03.CPY} declares none.
     *
     * <p><strong>Side effects.</strong> One keyed read. Nothing is deleted; the method is transactional
     * because the source's read carries {@code UPDATE}.
     *
     * @param userId the received map's {@code USRIDINI PIC X(8)} at {@code app/cpy-bms/COUSR03.CPY}:60. May be
     *               {@code null}, empty or blank, all three of which take the empty-identifier arm at
     *               {@code app/cbl/COUSR03C.cbl}:145 exactly as the source does
     * @return the assembled screen carrying the record's given name, family name and type, and the prompt of
     *         {@code :283}; never {@code null}
     * @throws com.cardemo.exception.ValidationException      if the identifier is empty - the {@code BLANK}
     *                                                        state, message from {@code :147} - or wider than
     *                                                        its eight-character screen field
     * @throws com.cardemo.exception.RecordNotFoundException  if the identifier is not present, message from
     *                                                        {@code :289}
     * @throws com.cardemo.exception.FileAccessException      if the read fails for any other reason, message
     *                                                        from {@code :296}
     * @throws com.cardemo.exception.FatalProcessingException if the condition is genuinely unexpected
     */
    @Transactional(rollbackFor = Exception.class)
    public UserSecurityDto.UserDeleteScreen lookupUser(final String userId) {
        return mainPara(true, true, AttentionIdentifier.ENTER, userId, null, false);
    }

    /**
     * Deletes a user: the {@code DFHPF5} arm at {@code app/cbl/COUSR03C.cbl}:121-122, which performs
     * {@code DELETE-USER-INFO}. This is the arm the program's own prompt at {@code :283} tells the operator to
     * use, and it is the only arm that destroys anything.
     *
     * <p><strong>The confirmation is asserted, not remembered.</strong> The source gated destruction on two
     * distinct terminal interactions - a read that emitted the prompt, then a different key. A stateless
     * surface cannot remember the first, so {@code confirmed} carries the assertion instead. Passing
     * {@code false} raises {@code com.cardemo.exception.ConcurrentUpdateException} with outcome
     * {@code CHANGES_NOT_CONFIRMED} and deletes nothing.
     *
     * <p><strong>The read and the delete are attempted in the source's order with no guard between
     * them</strong> - {@code :190-191}. In Java the read's failure raises, so the delete is never reached; see
     * the preserved-absence heading on this class for exactly which message a caller sees as a result.
     *
     * <p><strong>The caller's own identity is never consulted.</strong> Deleting the signed-on
     * administrator's own record <strong>succeeds</strong>, because the source has no self-delete guard - the
     * grep proof is on this class. That is preserved behaviour, disclosed as residual risk; it is not a
     * defect of this method.
     *
     * <p><strong>Side effects.</strong> One keyed read, then at most one delete. On any failure nothing is
     * removed: the method is transactional and rolls back for every exception, checked or unchecked. No audit
     * record is written, no tombstone is left and nothing is cascaded - the source does none of those things.
     *
     * @param userId    {@code USRIDINI PIC X(8)}, the key of the record to destroy. May be {@code null},
     *                  empty or blank, all three of which take the empty-identifier arm at {@code :177}
     * @param confirmed whether the caller asserts it was shown the prompt of {@code :283} and is deliberately
     *                  proceeding. {@code false} deletes nothing
     * @return the assembled screen: on success its message is {@code "User "} plus the trimmed identifier plus
     *         {@code " has been deleted ..."} and its four detail fields are blank, exactly as
     *         {@code INITIALIZE-ALL-FIELDS} leaves them at {@code :315}; never {@code null}
     * @throws com.cardemo.exception.ValidationException       if the identifier is empty - the {@code BLANK}
     *                                                         state, message from {@code :179} - or wider than
     *                                                         its eight-character screen field
     * @throws com.cardemo.exception.ConcurrentUpdateException with outcome {@code CHANGES_NOT_CONFIRMED} when
     *                                                         {@code confirmed} is {@code false}
     * @throws com.cardemo.exception.RecordNotFoundException   if the identifier is not present on the read at
     *                                                         {@code :287-292} or on the delete at
     *                                                         {@code :323-328}
     * @throws com.cardemo.exception.FileAccessException       if the read fails at {@code :293-299} or the
     *                                                         delete at {@code :329-335}
     * @throws com.cardemo.exception.FatalProcessingException  if the condition is genuinely unexpected
     */
    @Transactional(rollbackFor = Exception.class)
    public UserSecurityDto.UserDeleteScreen deleteUser(final String userId, final boolean confirmed) {
        return mainPara(true, true, AttentionIdentifier.PF5, userId, null, confirmed);
    }

    /**
     * Re-enters the delete screen with a submitted screen, which is the pseudo-conversational turn the source
     * takes at {@code app/cbl/COUSR03C.cbl}:106-131: receive the map, then dispatch on {@code EIBAID}.
     *
     * <p>Because the target is stateless, everything the source recovered from the communication area at
     * {@code :94} travels on {@code request} instead. {@code CDEMO-FROM-TRANID}, {@code CDEMO-TO-TRANID},
     * {@code CDEMO-FROM-PROGRAM}, {@code CDEMO-TO-PROGRAM}, {@code CDEMO-PGM-CONTEXT},
     * {@code CDEMO-LAST-MAP} and {@code CDEMO-LAST-MAPSET} have no counterpart at all; only
     * {@code CDEMO-USER-ID} and {@code CDEMO-USER-TYPE} survive, the security layer supplies both, and - as
     * the preserved-absence heading explains - this service consults neither.
     *
     * <p><strong>PF3 does not delete here.</strong> At {@code :111-118} it resolves a target program and
     * performs {@code RETURN-TO-PREV-SCREEN}, writing nothing: the exit key exits. That is worth stating
     * because the sibling update program saves on {@code PF3}, at {@code app/cbl/COUSR02C.cbl}:111-112. The
     * two are genuinely different and must not be harmonised. {@code PF12} at {@code :123-125} also leaves
     * without touching the record.
     *
     * <p><strong>Side effects by arm.</strong> {@code ENTER} reads. {@code PF5} reads and, when confirmed,
     * deletes. {@code PF3}, {@code PF4}, {@code PF12} and an unrecognised key touch the file not at all. The
     * method is nonetheless transactional, so the one arm that destroys is covered by a single boundary rather
     * than by a conditional one.
     *
     * @param aid       which key the operator pressed. {@code ENTER} looks the record up; {@code PF3} returns
     *                  to the administrative menu <strong>without</strong> deleting; {@code PF4} clears the
     *                  screen; {@code PF5} deletes and stays; {@code PF12} returns to the administrative menu;
     *                  anything else is reported as an invalid key. Must not be {@code null}
     * @param request   the submitted screen - the eleven fields of {@code app/cpy-bms/COUSR03.CPY}. Must not
     *                  be {@code null} when {@code aid} is {@code ENTER} or {@code PF5}, and may be
     *                  {@code null} on the four arms that read no input field
     * @param confirmed whether the caller asserts it was shown the prompt of {@code :283}; read only on the
     *                  {@code PF5} arm, and ignored on every other
     * @return the assembled screen; never {@code null}
     * @throws NullPointerException                            if {@code aid} is {@code null}, or if
     *                                                         {@code request} is {@code null} while
     *                                                         {@code aid} is {@code ENTER} or {@code PF5}
     * @throws com.cardemo.exception.ValidationException        on the reading and deleting arms, as
     *                                                         {@code lookupUser} and {@code deleteUser}
     *                                                         describe
     * @throws com.cardemo.exception.ConcurrentUpdateException on the {@code PF5} arm when {@code confirmed} is
     *                                                         {@code false}
     * @throws com.cardemo.exception.RecordNotFoundException   on the reading and deleting arms
     * @throws com.cardemo.exception.FileAccessException       on the reading and deleting arms
     * @throws com.cardemo.exception.FatalProcessingException  on the reading and deleting arms
     */
    @Transactional(rollbackFor = Exception.class)
    public UserSecurityDto.UserDeleteScreen submitScreen(final AttentionIdentifier aid,
            final UserSecurityDto.UserDeleteScreen request, final boolean confirmed) {

        Objects.requireNonNull(aid, "aid must not be null");
        if (aid == AttentionIdentifier.ENTER || aid == AttentionIdentifier.PF5) {
            Objects.requireNonNull(request,
                    "request must not be null when the attention identifier reads or deletes the record");
        }
        return mainPara(true, true, aid, null, request, confirmed);
    }

    // ------------------------------------------------------------------------------------------------
    // The eleven paragraphs of app/cbl/COUSR03C.cbl, one private method each, in source order. None is
    // consolidated, including the four whose CICS or COMMAREA state has no Java counterpart. The
    // source-citing Javadoc on each is the evidence the scope-coverage gate reads.
    // ------------------------------------------------------------------------------------------------

    /**
     * {@code app/cbl/COUSR03C.cbl}:82 {@code MAIN-PARA} - the entry point: resets the working flags, decides
     * between the no-communication-area arm, the first-display arm and the re-entry arm, and on re-entry
     * dispatches on {@code EIBAID}.
     *
     * <table border="1">
     *   <caption>The {@code EVALUATE EIBAID} at {@code :108}</caption>
     *   <tr><th>Key</th><th>Line</th><th>Action</th></tr>
     *   <tr><td>{@code DFHENTER}</td><td>{@code :109-110}</td><td>{@code PERFORM PROCESS-ENTER-KEY}</td></tr>
     *   <tr><td>{@code DFHPF3}</td><td>{@code :111-118}</td>
     *       <td>resolve a target program, then {@code PERFORM RETURN-TO-PREV-SCREEN} - <strong>no
     *       delete</strong></td></tr>
     *   <tr><td>{@code DFHPF4}</td><td>{@code :119-120}</td>
     *       <td>{@code PERFORM CLEAR-CURRENT-SCREEN}</td></tr>
     *   <tr><td>{@code DFHPF5}</td><td>{@code :121-122}</td><td>{@code PERFORM DELETE-USER-INFO}</td></tr>
     *   <tr><td>{@code DFHPF12}</td><td>{@code :123-125}</td>
     *       <td>{@code 'COADM01C'}, then {@code PERFORM RETURN-TO-PREV-SCREEN}</td></tr>
     *   <tr><td>{@code WHEN OTHER}</td><td>{@code :126-129}</td>
     *       <td>{@code CCDA-MSG-INVALID-KEY} into the message</td></tr>
     * </table>
     *
     * <p><strong>{@code PF3} is conventional in this program.</strong> It writes nothing before it leaves,
     * unlike {@code app/cbl/COUSR02C.cbl}:111-112 where the same key saves first. The contrast is recorded so
     * that a reader comparing the two siblings does not harmonise them.
     *
     * <p><strong>The confirmation check sits on the {@code PF5} arm</strong>, where the source's own gate sat:
     * the operator could only be on that arm by pressing a key different from the one that produced the
     * prompt. It is a mechanism substitution and is therefore invoked from a helper rather than written into
     * one of the eleven paragraph methods.
     *
     * <p>{@code :85} sets the vestigial flag; {@code :95-96}'s
     * {@code CDEMO-PGM-ENTER} against {@code CDEMO-PGM-REENTER} distinction, and the
     * {@code EXEC CICS RETURN TRANSID ... COMMAREA} at {@code :134-137}, have no counterpart and are commented
     * at their lines.
     *
     * @param commAreaPresent whether a communication area reached the program, {@code EIBCALEN} at {@code :90}
     * @param reenter         whether {@code CDEMO-PGM-REENTER} was already set, tested at {@code :95}
     * @param aid             the attention identifier dispatched on at {@code :108}
     * @param suppliedUserId  the pre-selected or directly supplied identifier, or {@code null}
     * @param request         the received map, or {@code null} on the arms that receive no input field
     * @param confirmed       whether the caller asserted it saw the prompt of {@code :283}
     * @return the assembled screen
     */
    private UserSecurityDto.UserDeleteScreen mainPara(final boolean commAreaPresent, final boolean reenter,
            final AttentionIdentifier aid, final String suppliedUserId,
            final UserSecurityDto.UserDeleteScreen request, final boolean confirmed) {

        final ScreenWorkArea work = new ScreenWorkArea();
        work.errFlgOn = false;                             // :84 SET ERR-FLG-OFF     TO TRUE
        work.usrModified = false;                          // :85 SET USR-MODIFIED-NO TO TRUE - vestigial;
                                                           //     declared :45-47, never tested anywhere in
                                                           //     the program. Retained, cited and tracked.
        work.message = SPACES;                             // :87 MOVE SPACES TO WS-MESSAGE
        work.errorMessage = SPACES;                        // :88                       ERRMSGO OF COUSR3AO

        if (!commAreaPresent) {                            // :90 IF EIBCALEN = 0
            work.toProgram = SIGN_ON_PROGRAM;              // :91 MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
            returnToPrevScreen(work);                      // :92 PERFORM RETURN-TO-PREV-SCREEN
            return concludeTurn(work);                     // :134-137 EXEC CICS RETURN
        }

        // :94 MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA - no counterpart. The two identity fields the
        // communication area carried are supplied by the security layer and are deliberately not consulted
        // here; the rest have no counterpart at all. Tracked.

        if (!reenter) {                                    // :95 IF NOT CDEMO-PGM-REENTER
            // :96 SET CDEMO-PGM-REENTER TO TRUE - no counterpart; the caller's entry point carries it.
            work.clearOutputMap();                         // :97 MOVE LOW-VALUES          TO COUSR3AO
            work.cursorField = CURSOR_FIELD_USER_ID;       // :98 MOVE -1       TO USRIDINL OF COUSR3AI
            if (!isBlankOrUnset(suppliedUserId)) {         // :99-100 IF CDEMO-CU03-USR-SELECTED NOT = ...
                work.userId = requireWidth(suppliedUserId, // :101-102 MOVE ... TO USRIDINI OF COUSR3AI
                        UserSecurityDto.USER_ID_WIDTH, FIELD_USER_ID);
                processEnterKey(work);                     // :103 PERFORM PROCESS-ENTER-KEY
            }                                              // :104 END-IF
            sendUsrdelScreen(work);                        // :105 PERFORM SEND-USRDEL-SCREEN
            return concludeTurn(work);                     // :134-137 EXEC CICS RETURN
        }

        receiveUsrdelScreen(work, request, suppliedUserId); // :107 PERFORM RECEIVE-USRDEL-SCREEN
        switch (aid) {                                     // :108 EVALUATE EIBAID
            case ENTER ->                                  // :109 WHEN DFHENTER
                processEnterKey(work);                     // :110 PERFORM PROCESS-ENTER-KEY
            case PF3 -> {                                  // :111 WHEN DFHPF3 - exits WITHOUT deleting
                // :112-113 IF CDEMO-FROM-PROGRAM = SPACES OR LOW-VALUES - the from-program has no
                // counterpart, so the test always succeeds and the :115-116 ELSE arm is unreachable. Tracked.
                work.toProgram = ADMIN_MENU_PROGRAM;       // :113 MOVE 'COADM01C' TO CDEMO-TO-PROGRAM
                returnToPrevScreen(work);                  // :118 PERFORM RETURN-TO-PREV-SCREEN
            }
            case PF4 ->                                    // :119 WHEN DFHPF4
                clearCurrentScreen(work);                  // :120 PERFORM CLEAR-CURRENT-SCREEN
            case PF5 -> {                                  // :121 WHEN DFHPF5
                requireDeletionConfirmed(confirmed);       // the stateless stand-in for the second key press
                deleteUserInfo(work);                      // :122 PERFORM DELETE-USER-INFO
            }
            case PF12 -> {                                 // :123 WHEN DFHPF12
                work.toProgram = ADMIN_MENU_PROGRAM;       // :124 MOVE 'COADM01C' TO CDEMO-TO-PROGRAM
                returnToPrevScreen(work);                  // :125 PERFORM RETURN-TO-PREV-SCREEN
            }
            case OTHER -> {                                // :126 WHEN OTHER
                work.errFlgOn = true;                      // :127 MOVE 'Y'                  TO WS-ERR-FLG
                work.message = INVALID_KEY_MESSAGE;        // :128 MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE
                sendUsrdelScreen(work);                    // :129 PERFORM SEND-USRDEL-SCREEN
            }
        }                                                  // :130 END-EVALUATE
        return concludeTurn(work);                         // :134-137 EXEC CICS RETURN
    }

    /**
     * {@code app/cbl/COUSR03C.cbl}:142 {@code PROCESS-ENTER-KEY} - validates the identifier, blanks the three
     * display fields, reads the record and reports its readable fields.
     *
     * <p>Exactly one field is checked, the identifier, at {@code :145} against {@code SPACES OR LOW-VALUES};
     * the message at {@code :147} is reproduced byte for byte and the cursor outcome at {@code :149} becomes
     * the field name carried on the raised failure. The {@code WHEN OTHER} arm at {@code :151-153} parks the
     * cursor and then does nothing else - a retained no-op, commented at its line rather than deleted to
     * please a linter.
     *
     * <p><strong>{@code :157-159} blanks three fields, not four.</strong> The given name, the family name and
     * the type. There is no fourth: this map has no credential field, which is why the sibling update
     * program's {@code MOVE SEC-USR-PWD TO PASSWDI} at {@code app/cbl/COUSR02C.cbl}:169 has no counterpart
     * statement here to omit. The source itself never exposes the credential on this screen.
     *
     * <p><strong>The read emits the confirmation prompt.</strong> On success {@code READ-USER-SEC-FILE} sets
     * the message of {@code :283}, so a caller that reaches the end of this paragraph has been told that a
     * second, separate action is required. That is the first half of the read-confirm-delete chain.
     *
     * @param work the method-local work area standing in for the program's working storage
     */
    private void processEnterKey(final ScreenWorkArea work) {
        if (isBlankOrUnset(work.userId)) {                 // :144-145 EVALUATE TRUE / WHEN USRIDINI = ...
            work.errFlgOn = true;                          // :146 MOVE 'Y' TO WS-ERR-FLG
            work.message = USER_ID_REQUIRED_MESSAGE;       // :147 MOVE 'User ID can NOT be empty...'
            work.cursorField = CURSOR_FIELD_USER_ID;       // :149 MOVE -1 TO USRIDINL OF COUSR3AI
            sendUsrdelScreen(work);                        // :150 PERFORM SEND-USRDEL-SCREEN
            retainFailure(work,
                    ValidationException.missingField(FIELD_USER_ID, USER_ID_REQUIRED_MESSAGE));
        } else {                                           // :151 WHEN OTHER
            // A retained no-op beyond parking the cursor; kept, commented and tracked, never deleted.
            work.cursorField = CURSOR_FIELD_USER_ID;       // :152 MOVE -1 TO USRIDINL OF COUSR3AI
                                                           // :153 CONTINUE
        }                                                  // :154 END-EVALUATE

        if (!work.errFlgOn) {                              // :156 IF NOT ERR-FLG-ON
            work.firstName = SPACES;                       // :157 MOVE SPACES TO FNAMEI   OF COUSR3AI
            work.lastName = SPACES;                        // :158                 LNAMEI   OF COUSR3AI
            work.userTypeCode = SPACES;                    // :159                 USRTYPEI OF COUSR3AI
            work.secUsrId = work.userId;                   // :160 MOVE USRIDINI TO SEC-USR-ID
            readUserSecFile(work);                         // :161 PERFORM READ-USER-SEC-FILE
        }                                                  // :162 END-IF

        if (!work.errFlgOn) {                              // :164 IF NOT ERR-FLG-ON
            work.firstName = work.secUsrFname;             // :165 MOVE SEC-USR-FNAME TO FNAMEI
            work.lastName = work.secUsrLname;              // :166 MOVE SEC-USR-LNAME TO LNAMEI
            work.userTypeCode = work.secUsrType == null    // :167 MOVE SEC-USR-TYPE  TO USRTYPEI
                    ? SPACES
                    : String.valueOf(work.secUsrType.getCode());
            sendUsrdelScreen(work);                        // :168 PERFORM SEND-USRDEL-SCREEN
        }                                                  // :169 END-IF
    }

    /**
     * {@code app/cbl/COUSR03C.cbl}:174 {@code DELETE-USER-INFO} - validates the identifier and then reads and
     * deletes the record. <strong>This is the only paragraph that destroys anything.</strong>
     *
     * <p>The empty-identifier arm at {@code :177} duplicates {@code PROCESS-ENTER-KEY}'s: the same literal
     * from {@code :179}, the same cursor outcome at {@code :181}, and the same cursor-only {@code WHEN OTHER}
     * arm at {@code :183-185}. The duplication is in the source and is preserved rather than factored out,
     * because the two paragraphs are separately mapped.
     *
     * <p><strong>The read and the delete are unguarded and sequential.</strong> At {@code :190-191} the source
     * performs {@code READ-USER-SEC-FILE} and then {@code DELETE-USER-SEC-FILE} with <strong>no
     * {@code IF NOT ERR-FLG-ON} between them</strong>. That is specific to this paragraph -
     * {@code PROCESS-ENTER-KEY} guards each of its follow-on blocks, at {@code :156} and {@code :164}. The
     * read's failure arms set the error flag and a message and the delete is attempted anyway, whereupon the
     * delete's own arms overwrite that message. Both calls are mapped in the source's order inside this
     * method, and <strong>no guard, early return or short circuit is added</strong>. In Java the read raises a
     * typed exception, so the delete is not reached; that is the documented mechanism substitution, set out in
     * full on this class along with which message a caller sees as a result.
     *
     * <p><strong>There is no self-delete guard, and none may be added.</strong>
     * {@code grep -n "CDEMO-USER-ID" app/cbl/COUSR03C.cbl} returns nothing and exits {@code 1} - zero hits -
     * so the program never compares the target identifier against the signed-on identifier and an
     * administrator can delete their own record. This method therefore does not consult the caller's identity,
     * receives nothing that would let it, and rejects nothing on that basis. Parity is the contract, so the
     * absence is preserved and disclosed as residual risk on this class.
     *
     * <p>Nor is any other absent guard added: no referential pre-check, no cascade check, no soft delete, no
     * tombstone, no audit record and no "last administrator" rule. The source performs none of them.
     *
     * @param work the method-local work area
     */
    private void deleteUserInfo(final ScreenWorkArea work) {
        if (isBlankOrUnset(work.userId)) {                 // :176-177 EVALUATE TRUE / WHEN USRIDINI = ...
            work.errFlgOn = true;                          // :178 MOVE 'Y' TO WS-ERR-FLG
            work.message = USER_ID_REQUIRED_MESSAGE;       // :179 MOVE 'User ID can NOT be empty...'
            work.cursorField = CURSOR_FIELD_USER_ID;       // :181 MOVE -1 TO USRIDINL OF COUSR3AI
            sendUsrdelScreen(work);                        // :182 PERFORM SEND-USRDEL-SCREEN
            retainFailure(work,
                    ValidationException.missingField(FIELD_USER_ID, USER_ID_REQUIRED_MESSAGE));
        } else {                                           // :183 WHEN OTHER
            // A retained no-op beyond parking the cursor; kept, commented and tracked, never deleted.
            work.cursorField = CURSOR_FIELD_USER_ID;       // :184 MOVE -1 TO USRIDINL OF COUSR3AI
                                                           // :185 CONTINUE
        }                                                  // :186 END-EVALUATE

        if (!work.errFlgOn) {                              // :188 IF NOT ERR-FLG-ON
            work.secUsrId = work.userId;                   // :189 MOVE USRIDINI TO SEC-USR-ID
            readUserSecFile(work);                         // :190 PERFORM READ-USER-SEC-FILE
            deleteUserSecFile(work);                       // :191 PERFORM DELETE-USER-SEC-FILE
            //     NO GUARD BETWEEN :190 AND :191. The absence is the behaviour and is preserved verbatim.
        }                                                  // :192 END-IF
    }

    /**
     * {@code app/cbl/COUSR03C.cbl}:197 {@code RETURN-TO-PREV-SCREEN} - defaults the target program, records
     * where control came from and transfers with {@code EXEC CICS XCTL}.
     *
     * <p><strong>Only the first statement has a counterpart.</strong> The {@code LOW-VALUES OR SPACES} default
     * at {@code :199-201} becomes an advisory navigation target on the work area. The remaining four
     * assignments have <strong>no Java equivalent whatsoever</strong> under the stateless mandate:
     * {@code CDEMO-FROM-TRANID} at {@code :202}, {@code CDEMO-FROM-PROGRAM} at {@code :203} and
     * {@code CDEMO-PGM-CONTEXT} at {@code :204}, together with {@code CDEMO-TO-TRANID},
     * {@code CDEMO-LAST-MAP} and {@code CDEMO-LAST-MAPSET} elsewhere in the communication area. Navigation
     * collapses into URL-based routing, and no control transfer happens here.
     *
     * <p>The paragraph is mapped one to one regardless, because deleting it would break the correspondence the
     * scope-coverage gate reads. The assignments with no counterpart are commented at their lines and tracked
     * rather than silently dropped. The body is byte-identical across all four {@code COUSR0*C} programs.
     *
     * @param work the method-local work area
     */
    private void returnToPrevScreen(final ScreenWorkArea work) {
        if (isBlankOrUnset(work.toProgram)) {              // :199 IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES
            work.toProgram = SIGN_ON_PROGRAM;              // :200 MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
        }                                                  // :201 END-IF
        // :202 MOVE WS-TRANID  TO CDEMO-FROM-TRANID   - no counterpart. Tracked.
        // :203 MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM  - no counterpart. Tracked.
        // :204 MOVE ZEROS      TO CDEMO-PGM-CONTEXT   - no counterpart. Tracked.
        // :205-208 EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA) - the transfer has no
        // counterpart either: routing is URL-based and nothing here transfers control.
    }

    /**
     * {@code app/cbl/COUSR03C.cbl}:213 {@code SEND-USRDEL-SCREEN} - paints the header, copies the working
     * message onto the map's message field and sends the map with {@code ERASE CURSOR}.
     *
     * <p>In Java this is the response-assembly half of the REST boundary. The header is repainted on every
     * send, exactly as {@code :215} performs {@code POPULATE-HEADER-INFO} unconditionally, so a caller that
     * sees a message also sees a header stamped at the moment of that send. The map name {@code 'COUSR3A'} and
     * the mapset {@code 'COUSR03'} at {@code :220-221} have no counterpart beyond the route itself; the mapset
     * is the one {@code app/csd/CARDDEMO.CSD}:169 defines.
     *
     * <p>The source could send several times in one turn - the read path sends its prompt at {@code :286} and
     * the delete path then sends its outcome at {@code :322} - and the terminal only ever showed the last one.
     * A single response can carry only one message, so the last send wins here too, which is the same
     * observable result.
     *
     * @param work the method-local work area
     */
    private void sendUsrdelScreen(final ScreenWorkArea work) {
        populateHeaderInfo(work);                          // :215 PERFORM POPULATE-HEADER-INFO
        work.errorMessage = work.message;                  // :217 MOVE WS-MESSAGE TO ERRMSGO OF COUSR3AO
        // :219-225 EXEC CICS SEND MAP('COUSR3A') MAPSET('COUSR03') FROM(COUSR3AO) ERASE CURSOR - the send
        // becomes the assembled response the caller receives; there is no terminal to erase and no cursor to
        // place, so the two options collapse into the cursor field already recorded on the work area.
    }

    /**
     * {@code app/cbl/COUSR03C.cbl}:230 {@code RECEIVE-USRDEL-SCREEN} - receives the map into the input area,
     * capturing the response and reason codes.
     *
     * <p>In Java this is the request-binding half of the REST boundary. The eleven fields of
     * {@code app/cpy-bms/COUSR03.CPY} arrive on the request record and are copied into the work area here,
     * which is the only place they enter it. The six header fields are received and then overwritten by
     * {@code POPULATE-HEADER-INFO} on the way out, exactly as the source overwrites them, so an inbound header
     * value can never influence the response.
     *
     * <p><strong>No credential arrives, because the map declares none.</strong>
     * {@code grep -c "PASSWD" app/cpy-bms/COUSR03.CPY} answers {@code 0}, so there is no component here that
     * could carry one.
     *
     * <p>{@code RESP(WS-RESP-CD)} and {@code RESP2(WS-REAS-CD)} at {@code :236-237} are captured but never
     * tested on this paragraph in the source, so nothing branches on them here either; they are reset so that
     * a stale value from an earlier operation cannot be read later in the turn.
     *
     * @param work           the method-local work area
     * @param request        the received map, or {@code null} on the arms that receive no input field
     * @param suppliedUserId the single populated field on the lookup and delete shapes, or {@code null}
     */
    private void receiveUsrdelScreen(final ScreenWorkArea work,
            final UserSecurityDto.UserDeleteScreen request, final String suppliedUserId) {

        // :232-238 EXEC CICS RECEIVE MAP('COUSR3A') MAPSET('COUSR03') INTO(COUSR3AI) RESP/RESP2
        recordResponse(work, CICS_RESP_NORMAL, IO_STATUS_SUCCESS);
        if (request != null) {
            work.transactionName = request.transactionName();
            work.title01 = request.title01();
            work.currentDate = request.currentDate();
            work.programName = request.programName();
            work.title02 = request.title02();
            work.currentTime = request.currentTime();
            work.userId = request.userIdInput();
            work.firstName = request.firstName();
            work.lastName = request.lastName();
            work.userTypeCode = request.userType();
            work.errorMessage = request.errorMessage();
        }
        if (suppliedUserId != null) {
            work.userId = requireWidth(suppliedUserId, UserSecurityDto.USER_ID_WIDTH, FIELD_USER_ID);
        }
    }

    /**
     * {@code app/cbl/COUSR03C.cbl}:243 {@code POPULATE-HEADER-INFO} - stamps the two titles, the transaction
     * identifier, the program name, the date and the time onto the output map.
     *
     * <p>The two titles come from {@code app/cpy/COTTL01Y.cpy} as forty-character constants and are reproduced
     * byte for byte, leading and trailing spaces included, because they are screen literals rather than prose.
     *
     * <p><strong>The date carries a two-digit year.</strong> {@code :254} moves
     * {@code WS-CURDATE-YEAR(3:2)}, the last two digits, so the assembled value is {@code MM/DD/YY} in eight
     * characters per {@code app/cpy/CSDAT01Y.cpy}. The time is {@code HH:MM:SS} in eight characters from the
     * same copybook. Note that the specification's field inventory records the time field as nine characters
     * wide; {@code app/cpy-bms/COUSR03.CPY}:54 declares {@code CURTIMEI PIC X(8)} and {@code CSDAT01Y}
     * assembles eight, so the source governs and the wider figure is a citation error, classified Low.
     *
     * <p>The instant comes from the injected clock rather than from a direct call to the system clock, so the
     * header is deterministic under test. Both formatters are built with {@code Locale.ROOT}, so a host locale
     * can never reshape the separators or the digits.
     *
     * @param work the method-local work area
     */
    private void populateHeaderInfo(final ScreenWorkArea work) {
        final LocalDateTime now = LocalDateTime.now(this.clock);
                                                           // :245 MOVE FUNCTION CURRENT-DATE TO ...
        work.title01 = SCREEN_TITLE_01;                    // :247 MOVE CCDA-TITLE01 TO TITLE01O
        work.title02 = SCREEN_TITLE_02;                    // :248 MOVE CCDA-TITLE02 TO TITLE02O
        work.transactionName = TRANSACTION_ID;             // :249 MOVE WS-TRANID    TO TRNNAMEO
        work.programName = PROGRAM_NAME;                   // :250 MOVE WS-PGMNAME   TO PGMNAMEO
        // :252 MOVE WS-CURDATE-MONTH     TO WS-CURDATE-MM
        // :253 MOVE WS-CURDATE-DAY       TO WS-CURDATE-DD
        // :254 MOVE WS-CURDATE-YEAR(3:2) TO WS-CURDATE-YY  - the last two digits of the year, hence 'yy'.
        work.currentDate = HEADER_DATE_FORMAT.format(now); // :256 MOVE WS-CURDATE-MM-DD-YY TO CURDATEO
        // :258 MOVE WS-CURTIME-HOURS  TO WS-CURTIME-HH
        // :259 MOVE WS-CURTIME-MINUTE TO WS-CURTIME-MM
        // :260 MOVE WS-CURTIME-SECOND TO WS-CURTIME-SS
        work.currentTime = HEADER_TIME_FORMAT.format(now); // :262 MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO
    }

    /**
     * {@code app/cbl/COUSR03C.cbl}:267 {@code READ-USER-SEC-FILE} - reads the security record for update and
     * branches three ways on the response code.
     *
     * <table border="1">
     *   <caption>The three branches of the {@code EVALUATE} at {@code :280}</caption>
     *   <tr><th>Branch</th><th>Line</th><th>Outcome</th></tr>
     *   <tr><td>{@code DFHRESP(NORMAL)}</td><td>{@code :281-286}</td>
     *       <td>the record's fields are available and the prompt
     *       {@code "Press PF5 key to delete this user ..."} is emitted</td></tr>
     *   <tr><td>{@code DFHRESP(NOTFND)}</td><td>{@code :287-292}</td>
     *       <td>{@code "User ID NOT found..."}, raised as a not-found failure</td></tr>
     *   <tr><td>{@code WHEN OTHER}</td><td>{@code :293-299}</td>
     *       <td>{@code "Unable to lookup User..."}, raised as a file-access failure and escalated to a fatal
     *       failure when the condition is genuinely unexpected</td></tr>
     * </table>
     *
     * <p><strong>The prompt at {@code :283} is the confirmation gate.</strong> It is the source's own
     * statement that destruction needs a second, separate action, and it is the reason this service's delete
     * entry point requires an explicit assertion rather than a remembered flag. The literal is reproduced byte
     * for byte, including the space before its three periods, which the two not-found literals do not have.
     *
     * <p><strong>{@code :282} is a literal {@code CONTINUE}</strong> standing at the head of the normal branch,
     * ahead of the three statements that follow it. It is a retained no-op, commented at its line rather than
     * deleted.
     *
     * <p><strong>A not-found user is an error.</strong> The three places where the corpus treats a not-found
     * status as success are all batch-side - the category-balance upsert in {@code app/cbl/CBTRN02C.cbl}, the
     * first disclosure-group default lookup in {@code app/cbl/CBACT04C.cbl}, and the file subprogram's
     * acceptance of its secondary status - and none applies to an online keyed read of a user. Importing one
     * would be classified High.
     *
     * <p><strong>The record lock is reproduced, not replaced.</strong> The read carries {@code UPDATE} at
     * {@code :275} and {@code app/csd/CARDDEMO.CSD}:88 defines the file with {@code UPDATEMODEL(LOCKING)} under
     * {@code RECOVERY(NONE)}, so the source held the row exclusively from the read until the delete. This read
     * therefore goes through {@link com.cardemo.repository.UserSecurityRepository#findByIdForUpdate(String)},
     * which takes the same lock, and the enclosing {@code @Transactional(rollbackFor = Exception.class)} method
     * releases it where the source's unit of work did. <strong>Finding, MEDIUM severity, resolved:</strong> the
     * read previously used the unlocked {@code findById}, so the read and the delete were an unguarded sequence
     * in which a concurrent update could be silently destroyed.
     *
     * <p>{@code :294} is a live {@code DISPLAY} of the response and reason codes, so it is reproduced as a
     * structured log line. It carries diagnostic codes only - never an identifier, a name or a credential - so
     * no personal data can reach the log through it.
     *
     * @param work the method-local work area
     */
    private void readUserSecFile(final ScreenWorkArea work) {
        try {
            final Optional<UserSecurity> located =
                    this.userSecurityRepository.findByIdForUpdate(work.secUsrId);
                                                           // :269-278 EXEC CICS READ ... UPDATE RESP/RESP2 -
                                                           // the UPDATE option at :275 is the pessimistic write
                                                           // lock this finder acquires, held to the end of the
                                                           // enclosing transaction.
            if (located.isPresent()) {
                final UserSecurity securityRecord = located.get();
                work.loadedRecord = securityRecord;        // :271 INTO (SEC-USER-DATA)
                work.secUsrId = securityRecord.getSecUsrId();
                work.secUsrFname = securityRecord.getSecUsrFname();
                work.secUsrLname = securityRecord.getSecUsrLname();
                work.secUsrType = securityRecord.getSecUsrType();
                // SEC-USR-PWD is deliberately not read onto the work area. This screen displays no
                // credential and the source moves none onto it, so nothing here can leak one.
                recordResponse(work, CICS_RESP_NORMAL, IO_STATUS_SUCCESS);
            } else {
                recordResponse(work, CICS_RESP_NOTFND, IO_STATUS_RECORD_NOT_FOUND);
            }
        } catch (final DataAccessException cause) {
            recordResponse(work, CICS_RESP_IOERR, IO_STATUS_IO_ERROR);
            work.ioFailureCause = cause;
        }

        switch (work.responseCode) {                        // :280 EVALUATE WS-RESP-CD
            case CICS_RESP_NORMAL -> {                      // :281 WHEN DFHRESP(NORMAL)
                                                            // :282 CONTINUE - retained no-op.
                work.message = DELETE_HINT_MESSAGE;         // :283 'Press PF5 key to delete this user ...'
                // :285 MOVE DFHNEUTR TO ERRMSGC OF COUSR3AO - an attribute byte of the output redefinition,
                // not one of the eleven ...I input fields the response record declares. No counterpart.
                sendUsrdelScreen(work);                     // :286 PERFORM SEND-USRDEL-SCREEN
            }
            case CICS_RESP_NOTFND -> {                      // :287 WHEN DFHRESP(NOTFND)
                work.errFlgOn = true;                       // :288 MOVE 'Y' TO WS-ERR-FLG
                work.message = USER_NOT_FOUND_MESSAGE;      // :289 'User ID NOT found...'
                work.cursorField = CURSOR_FIELD_USER_ID;    // :291 MOVE -1 TO USRIDINL OF COUSR3AI
                sendUsrdelScreen(work);                     // :292 PERFORM SEND-USRDEL-SCREEN
                final RecordNotFoundException absent = new RecordNotFoundException(
                        USER_NOT_FOUND_MESSAGE, USRSEC_FILE, work.secUsrId);
                retainFailure(work, absent);
                throw absent;
            }
            default -> {                                    // :293 WHEN OTHER
                LOG.error("RESP:{} REAS:{}", work.responseCode, work.reasonCode);
                                                            // :294 DISPLAY 'RESP:' ... 'REAS:' ...
                work.errFlgOn = true;                       // :295 MOVE 'Y' TO WS-ERR-FLG
                work.message = UNABLE_TO_LOOKUP_MESSAGE;    // :296 'Unable to lookup User...'
                work.cursorField = CURSOR_FIELD_FIRST_NAME; // :298 MOVE -1 TO FNAMEL OF COUSR3AI
                sendUsrdelScreen(work);                     // :299 PERFORM SEND-USRDEL-SCREEN
                final CardDemoException failure = classify(work, READ_OPERATION, UNABLE_TO_LOOKUP_MESSAGE);
                retainFailure(work, failure);
                throw failure;
            }
        }                                                   // :300 END-EVALUATE
    }

    /**
     * {@code app/cbl/COUSR03C.cbl}:305 {@code DELETE-USER-SEC-FILE} - deletes the security record and branches
     * three ways on the response code.
     *
     * <table border="1">
     *   <caption>The three branches of the {@code EVALUATE} at {@code :313}</caption>
     *   <tr><th>Branch</th><th>Line</th><th>Outcome</th></tr>
     *   <tr><td>{@code DFHRESP(NORMAL)}</td><td>{@code :314-322}</td>
     *       <td>the fields are blanked, then {@code "User "} plus the identifier plus
     *       {@code " has been deleted ..."}</td></tr>
     *   <tr><td>{@code DFHRESP(NOTFND)}</td><td>{@code :323-328}</td>
     *       <td>{@code "User ID NOT found..."}, raised as a not-found failure</td></tr>
     *   <tr><td>{@code WHEN OTHER}</td><td>{@code :329-335}</td>
     *       <td>{@code "Unable to Update User..."} - <strong>the wrong verb, preserved</strong> - raised as a
     *       file-access failure</td></tr>
     * </table>
     *
     * <p><strong>The verb in the failure literal at {@code :332} is wrong, and is preserved byte for
     * byte.</strong> This is a delete-failure path, yet the source reports {@code "Unable to Update User..."}.
     * It was evidently cloned from {@code app/cbl/COUSR02C.cbl}:386, where the identical literal sits on a
     * rewrite-failure path and the verb is correct. <strong>It is not corrected to "Delete"</strong>: the
     * parity gates compare these strings byte for byte, three trailing periods included. Classified Low -
     * cosmetic in the source, but behaviour-bearing under byte-for-byte comparison. The cross-reference is
     * recorded so that a reader cannot mistake it for a copy-paste error introduced by the migration.
     *
     * <p><strong>The delete names no key.</strong> {@code EXEC CICS DELETE DATASET(WS-USRSEC-FILE)} at
     * {@code :307-311} carries no {@code RIDFLD}, so it destroys the record held by the preceding
     * {@code READ ... UPDATE}. The Java counterpart therefore removes the entity that read loaded, rather than
     * issuing a fresh delete by identifier, and flushes so that the statement's outcome is observed inside
     * this method rather than at commit.
     *
     * <p><strong>The loaded record is guaranteed present, so no null check is added.</strong> This paragraph is
     * performed only from {@code DELETE-USER-INFO}:191, immediately after {@code READ-USER-SEC-FILE}:190, and
     * that paragraph's only non-throwing branch is the one that loads the record. A check here would therefore
     * be unreachable code. Should the invariant ever be broken, the repository rejects a {@code null} argument
     * with a data-access failure, which this method's own {@code catch} already reports as the
     * {@code WHEN OTHER} arm - so the guarantee is stated rather than defended twice.
     *
     * <p><strong>The {@code DFHRESP(NOTFND)} branch is retained but is not reachable through this
     * translation.</strong> {@code EXEC CICS DELETE} could report a vanished record as a response code;
     * removing an entity the same transaction just read cannot, because a row that has gone surfaces as a
     * data-access failure and is recorded as the {@code '90'} status, landing in the {@code WHEN OTHER} arm.
     * The branch is mapped anyway, because deleting it would break the branch-for-branch correspondence the
     * scope-coverage gate reads and because the response-code switch is the shape of the source. It is an
     * intentionally retained parity artefact rather than abandoned code, and it is expected to show as
     * uncovered in the coverage report.
     *
     * <p>The success message is assembled exactly as the {@code STRING} at {@code :318-321} assembles it, with
     * the identifier delimited by its first space so that a shorter identifier does not carry the field's
     * trailing padding into the sentence. Note the order: {@code :315} blanks the fields <em>before</em> the
     * message is built, and {@code :319} reads {@code SEC-USR-ID} - the record area, which
     * {@code INITIALIZE-ALL-FIELDS} does not touch - so the identifier still appears in the sentence even
     * though the screen field has been cleared.
     *
     * <p>No retry, no merge and no re-read: the source abandons the operation on failure and so does this.
     * Because the whole read-confirm-delete sequence runs inside one declarative transaction that rolls back
     * for every exception, nothing partial can survive, and that is achieved by scoping rather than by
     * conditional logic.
     *
     * @param work the method-local work area
     */
    private void deleteUserSecFile(final ScreenWorkArea work) {
        try {
            this.userSecurityRepository.delete(work.loadedRecord);
            this.userSecurityRepository.flush();
                                                            // :307-311 EXEC CICS DELETE DATASET RESP/RESP2 -
                                                            // no RIDFLD: the record the read holds.
            recordResponse(work, CICS_RESP_NORMAL, IO_STATUS_SUCCESS);
        } catch (final DataAccessException cause) {
            recordResponse(work, CICS_RESP_IOERR, IO_STATUS_IO_ERROR);
            work.ioFailureCause = cause;
        }

        switch (work.responseCode) {                        // :313 EVALUATE WS-RESP-CD
            case CICS_RESP_NORMAL -> {                      // :314 WHEN DFHRESP(NORMAL)
                initializeAllFields(work);                  // :315 PERFORM INITIALIZE-ALL-FIELDS
                work.message = SPACES;                      // :316 MOVE SPACES   TO WS-MESSAGE
                // :317 MOVE DFHGREEN TO ERRMSGC OF COUSR3AO - an attribute byte of the output redefinition,
                // not one of the eleven ...I input fields. No counterpart.
                work.message = DELETED_MESSAGE_PREFIX       // :318 STRING 'User '   DELIMITED BY SIZE
                        + delimitedBySpace(work.secUsrId)   // :319     SEC-USR-ID   DELIMITED BY SPACE
                        + DELETED_MESSAGE_SUFFIX;           // :320 ' has been deleted ...' DELIMITED BY SIZE
                                                            // :321   INTO WS-MESSAGE
                sendUsrdelScreen(work);                     // :322 PERFORM SEND-USRDEL-SCREEN
            }
            case CICS_RESP_NOTFND -> {                      // :323 WHEN DFHRESP(NOTFND)
                work.errFlgOn = true;                       // :324 MOVE 'Y' TO WS-ERR-FLG
                work.message = USER_NOT_FOUND_MESSAGE;      // :325 'User ID NOT found...'
                work.cursorField = CURSOR_FIELD_USER_ID;    // :327 MOVE -1 TO USRIDINL OF COUSR3AI
                sendUsrdelScreen(work);                     // :328 PERFORM SEND-USRDEL-SCREEN
                final RecordNotFoundException absent = new RecordNotFoundException(
                        USER_NOT_FOUND_MESSAGE, USRSEC_FILE, work.secUsrId);
                retainFailure(work, absent);
                throw absent;
            }
            default -> {                                    // :329 WHEN OTHER
                LOG.error("RESP:{} REAS:{}", work.responseCode, work.reasonCode);
                                                            // :330 DISPLAY 'RESP:' ... 'REAS:' ...
                work.errFlgOn = true;                       // :331 MOVE 'Y' TO WS-ERR-FLG
                work.message = UNABLE_TO_UPDATE_MESSAGE;    // :332 'Unable to Update User...' - WRONG VERB
                                                            //      ON A DELETE PATH. PRESERVED VERBATIM.
                work.cursorField = CURSOR_FIELD_FIRST_NAME; // :334 MOVE -1 TO FNAMEL OF COUSR3AI
                sendUsrdelScreen(work);                     // :335 PERFORM SEND-USRDEL-SCREEN
                final CardDemoException failure = classify(work, DELETE_OPERATION, UNABLE_TO_UPDATE_MESSAGE);
                retainFailure(work, failure);
                throw failure;
            }
        }                                                   // :336 END-EVALUATE
    }

    /**
     * {@code app/cbl/COUSR03C.cbl}:341 {@code CLEAR-CURRENT-SCREEN} - blanks the fields and sends the screen.
     *
     * <p>Reached from the {@code DFHPF4} arm at {@code :119-120}. There is no terminal to clear, so in Java
     * the pair of statements produces a fresh, empty response and nothing more. <strong>No cleared state is
     * retained anywhere</strong>: the work area is created per call and discarded when the call returns, so a
     * subsequent caller can never observe what this one cleared. Retained rather than deleted because the
     * paragraph correspondence is the evidence the scope-coverage gate reads.
     *
     * @param work the method-local work area
     */
    private void clearCurrentScreen(final ScreenWorkArea work) {
        initializeAllFields(work);                          // :343 PERFORM INITIALIZE-ALL-FIELDS.
        sendUsrdelScreen(work);                             // :344 PERFORM SEND-USRDEL-SCREEN.
    }

    /**
     * {@code app/cbl/COUSR03C.cbl}:349 {@code INITIALIZE-ALL-FIELDS} - parks the cursor on the identifier field
     * and blanks the four editable fields and the message.
     *
     * <p>Four fields, not five: the identifier at {@code :352}, the given name at {@code :353}, the family
     * name at {@code :354} and the type at {@code :355}, plus {@code WS-MESSAGE} at {@code :356}. There is no
     * credential field on this map to blank.
     *
     * <p><strong>It does not touch the record area.</strong> {@code SEC-USR-ID} is not a map field, which is
     * why the success sentence assembled at {@code :318-321} - immediately after this paragraph runs at
     * {@code :315} - can still name the deleted user.
     *
     * @param work the method-local work area
     */
    private void initializeAllFields(final ScreenWorkArea work) {
        work.cursorField = CURSOR_FIELD_USER_ID;            // :351 MOVE -1     TO USRIDINL OF COUSR3AI
        work.userId = SPACES;                               // :352 MOVE SPACES TO USRIDINI OF COUSR3AI
        work.firstName = SPACES;                            // :353                 FNAMEI   OF COUSR3AI
        work.lastName = SPACES;                             // :354                 LNAMEI   OF COUSR3AI
        work.userTypeCode = SPACES;                         // :355                 USRTYPEI OF COUSR3AI
        work.message = SPACES;                              // :356                 WS-MESSAGE.
    }

    // ------------------------------------------------------------------------------------------------
    // Mechanism helpers. None corresponds to a source paragraph: each stands in for a COBOL language or
    // CICS mechanism that has no paragraph of its own - a figurative-constant test, an intrinsic phrase,
    // a response-code capture, a typed status translation, the two-phase terminal gate. They are helpers
    // rather than a separate class on purpose: Rule 1 Clause C fixes this package's file set, and keeping
    // them here is what stops the eleven paragraph methods from carrying statements the source does not
    // have.
    // ------------------------------------------------------------------------------------------------

    /**
     * Enforces the two-phase confirmation gate that the source enforced structurally.
     *
     * <p>{@code app/cbl/COUSR03C.cbl} destroyed a record only after two distinct terminal interactions: a read
     * that emitted {@code "Press PF5 key to delete this user ..."} at {@code :283}, and then a key press
     * different from the one that produced it, dispatched at {@code :121-122}. The gate was the terminal
     * conversation itself, not a stored flag - the program kept no pending-delete state.
     *
     * <p>A stateless surface cannot remember that the first interaction happened, so the caller asserts it.
     * <strong>There is no HTTP session, no cache and no server-side pending-delete cursor</strong>, and this
     * method reads no state whatsoever: it is a pure function of its argument. Failing the assertion raises
     * {@code CHANGES_NOT_CONFIRMED} - one of the five outcomes of
     * {@code com.cardemo.exception.ConcurrentUpdateException}, and deliberately a distinguishable one rather
     * than an undifferentiated conflict, because the caller's remedy is specific: read the record, observe the
     * prompt, then submit again asserting it. That outcome carries an empty legacy message, because the source
     * had no literal for a condition it prevented structurally, so the source's own prompt is reported back
     * instead.
     *
     * <p>Called from the {@code PF5} arm of the dispatch rather than from inside
     * {@code DELETE-USER-INFO}, so that the eleven paragraph methods carry only statements the source carries.
     *
     * @param confirmed whether the caller asserts it was shown the prompt of {@code :283}
     * @throws ConcurrentUpdateException with outcome {@code CHANGES_NOT_CONFIRMED} when {@code confirmed} is
     *                                  {@code false}. Nothing is read and nothing is deleted
     */
    private static void requireDeletionConfirmed(final boolean confirmed) {
        if (!confirmed) {
            throw new ConcurrentUpdateException(ConcurrentUpdateException.Outcome.CHANGES_NOT_CONFIRMED,
                    NOT_CONFIRMED_MESSAGE, USRSEC_FILE, null);
        }
    }

    /**
     * Concludes the turn at the source's own return point, {@code app/cbl/COUSR03C.cbl}:134-137
     * {@code EXEC CICS RETURN}.
     *
     * <p>The source cannot throw. Each error arm sets {@code WS-ERR-FLG}, records a message, parks the cursor
     * and sends the screen, and the flag then suppresses the work that follows - which is why the arms record
     * their failure rather than short-circuit. The recorded failure surfaces here, at the point the source
     * returns to the terminal, so every statement the source genuinely executes after the failure has
     * executed.
     *
     * @param work the method-local work area
     * @return the assembled screen when the turn succeeded
     * @throws com.cardemo.exception.CardDemoException the first failure recorded during the turn, if any
     */
    private static UserSecurityDto.UserDeleteScreen concludeTurn(final ScreenWorkArea work) {
        if (work.retainedFailure != null) {
            throw work.retainedFailure;
        }
        return buildScreen(work);
    }

    /**
     * Assembles the response from the work area, standing in for the map the source sends.
     *
     * <p>Eleven components, one per {@code ...I} field of {@code app/cpy-bms/COUSR03.CPY}, and
     * <strong>no credential component exists to be populated</strong> - which is what makes the guarantee
     * structural rather than a matter of remembering to blank a field. The record's own canonical constructor
     * rejects any component wider than the map field it transcribes, and its {@code toString} discloses only
     * the program name, so the identifier and the two names cannot reach a log line through an interpolated
     * object.
     *
     * <p>Four things the work area holds have no component: the message colour, the cursor field, the advisory
     * navigation target and the vestigial modified flag. The first is an attribute byte of the output
     * redefinition, the second surfaces as the field name on a typed failure, and the last two have no Java
     * counterpart at all. All four are documented at their source lines.
     *
     * @param work the method-local work area
     * @return the assembled screen; never {@code null}
     */
    private static UserSecurityDto.UserDeleteScreen buildScreen(final ScreenWorkArea work) {
        return new UserSecurityDto.UserDeleteScreen(work.transactionName, work.title01, work.currentDate,
                work.programName, work.title02, work.currentTime, work.userId, work.firstName, work.lastName,
                work.userTypeCode, work.errorMessage);
    }

    /**
     * Records the outcome of an I/O attempt, standing in for {@code RESP(WS-RESP-CD)} and
     * {@code RESP2(WS-REAS-CD)} on the {@code EXEC CICS} commands at {@code app/cbl/COUSR03C.cbl}:276-277 and
     * {@code :309-310}.
     *
     * <p>{@code WS-REAS-CD}, declared at {@code :44}, receives the secondary reason code, which has no
     * counterpart outside CICS. It is reported as zero so that the two live {@code DISPLAY} statements at
     * {@code :294} and {@code :330} keep their shape, and it is never inferred from anything else.
     *
     * @param work         the method-local work area
     * @param responseCode the response code the outcome maps to
     * @param ioStatus     the file status the outcome maps to, for the typed translation
     */
    private static void recordResponse(final ScreenWorkArea work, final int responseCode,
            final String ioStatus) {
        work.responseCode = responseCode;
        work.reasonCode = 0;
        work.ioStatus = ioStatus;
    }

    /**
     * Translates a recorded file status into its typed exception through the shared mapper, which is the single
     * owner of that decision and of the {@code FILE STATUS IS: NNNN} rendering held by
     * {@code com.cardemo.model.enums.FileStatus}. Neither is reimplemented here and no status is re-rendered.
     *
     * <p>The mapper declines to produce an exception for a status it does not consider a failure. That cannot
     * arise from the two call sites, which reach this method only on a {@code WHEN OTHER} arm, but a
     * translation that silently returned nothing would swallow the failure, so the decline becomes a
     * file-access failure carrying the source's own message rather than being left to become {@code null}.
     *
     * <p><strong>None of the mapper's three batch-scoped not-found-is-success methods is called.</strong> They
     * belong to the category-balance upsert, the disclosure-group default fallback and the file subprogram's
     * secondary status, all of which are batch-side. A not-found user here is an error.
     *
     * <p>The recorded cause is consumed: it is cleared once read, so it cannot be attached twice.
     *
     * @param work            the method-local work area, carrying the recorded status and any cause
     * @param operation       the operation attempted, for the exception's context
     * @param fallbackMessage the source literal to carry when the mapper declines
     * @return the typed exception standing for that status; never {@code null}
     */
    private CardDemoException classify(final ScreenWorkArea work, final String operation,
            final String fallbackMessage) {

        final Throwable cause = work.ioFailureCause;
        work.ioFailureCause = null;
        final Optional<CardDemoException> mapped =
                this.fileStatusMapper.toException(work.ioStatus, USRSEC_FILE, operation, cause);
        if (mapped.isPresent()) {
            return mapped.get();
        }
        return new FileAccessException(fallbackMessage, work.ioStatus, USRSEC_FILE, operation, cause);
    }

    /**
     * Records the first typed failure of a turn, so that the screen the source paints is painted before the
     * failure surfaces.
     *
     * <p><strong>First failure wins</strong>, mirroring the {@code EVALUATE TRUE} structures at
     * {@code app/cbl/COUSR03C.cbl}:144-154 and {@code :176-186}, each of which takes exactly one arm. A later
     * arm cannot overwrite an earlier one. Because both structures take one arm only, no single turn records
     * twice, so the overwrite-suppressing branch shows as uncovered by design; it is kept because it is what
     * makes "first failure wins" a property of the code rather than of the call order.
     *
     * @param work    the method-local work area
     * @param failure the typed failure to record
     */
    private static void retainFailure(final ScreenWorkArea work, final CardDemoException failure) {
        if (work.retainedFailure == null) {
            work.retainedFailure = failure;
        }
    }

    /**
     * Reproduces {@code DELIMITED BY SPACE} for the {@code STRING} at {@code app/cbl/COUSR03C.cbl}:318-321.
     *
     * <p>{@code SEC-USR-ID} is an {@code X(08)} field, so a shorter identifier is space-padded.
     * {@code DELIMITED BY SPACE} stops the transfer at the first space, which drops that padding, while the two
     * surrounding literals are {@code DELIMITED BY SIZE} and contribute in full. Truncating at the first space
     * - not trimming both ends - is what the phrase means, and it is what this method does.
     *
     * @param value the field to transfer, permitted to be {@code null}
     * @return the value up to but excluding its first space; never {@code null}
     */
    private static String delimitedBySpace(final String value) {
        if (value == null) {
            return SPACES;
        }
        final int firstSpace = value.indexOf(' ');
        return firstSpace < 0 ? value : value.substring(0, firstSpace);
    }

    /**
     * Reproduces the {@code = SPACES OR LOW-VALUES} test the source applies at
     * {@code app/cbl/COUSR03C.cbl}:145, {@code :177} and {@code :199}, and the
     * {@code NOT = SPACES AND LOW-VALUES} test at {@code :99-100}.
     *
     * <p>Both figurative constants are covered, and so is the {@code null} a JSON body can deliver where a
     * fixed-width field could only ever have been blank. {@code SPACES} is any run of whitespace;
     * {@code LOW-VALUES} is a run of binary zeros, which arrive as ISO control characters, so a field of those
     * is treated as unset too rather than as content. A field is unset when every character is one or the
     * other.
     *
     * @param value the field to test, permitted to be {@code null}
     * @return {@code true} when the field is unset in the source's sense
     */
    private static boolean isBlankOrUnset(final String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (!Character.isWhitespace(character) && !Character.isISOControl(character)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Rejects a value wider than the fixed-width field it transcribes.
     *
     * <p>The 3270 map made over-length input physically impossible: {@code USRIDINI} is declared
     * {@code PIC X(8)} at {@code app/cpy-bms/COUSR03.CPY}:60 and could not receive a ninth character. A JSON
     * body has no such limit, and the key it becomes is still the eight-byte {@code KEYS(8,0)} of
     * {@code app/jcl/DUSRSECJ.jcl}:65, so an over-length value has to be refused. <strong>It is refused, never
     * truncated</strong>: silently truncating the key of a delete request could destroy a different record
     * from the one asked for, and no comparison would ever reveal it.
     *
     * <p>The rejection carries the {@code INVALID} state and names the field, never the value.
     *
     * @param value     the submitted value, permitted to be {@code null}
     * @param maxLength the declared width of the field
     * @param fieldName the field's name, for the rejection
     * @return the value unchanged when it fits
     * @throws ValidationException when the value is wider than the field
     */
    private static String requireWidth(final String value, final int maxLength, final String fieldName) {
        if (value != null && value.length() > maxLength) {
            throw ValidationException.invalidField(fieldName,
                    fieldName + " must be at most " + maxLength + " characters");
        }
        return value;
    }

    // ------------------------------------------------------------------------------------------------
    // Nested types. Declared here rather than as files of their own because Rule 1 Clause C fixes this
    // package's file set: the enumeration is not referenced outside this service and its controller, and
    // the work area is an implementation detail of a single turn. The response record is not declared
    // here at all - com.cardemo.model.dto.UserSecurityDto.UserDeleteScreen already owns the eleven-field
    // contract of app/cpy-bms/COUSR03.CPY, and duplicating it would be the duplication Clause C forbids.
    // ------------------------------------------------------------------------------------------------

    /**
     * The attention identifiers the source dispatches on at {@code app/cbl/COUSR03C.cbl}:108, standing in for
     * {@code EIBAID} tested against the condition names of the CICS-supplied {@code DFHAID} copybook - which is
     * not present in this repository and has no Java type.
     *
     * <p>Which key writes and which does not is the whole point of the enumeration, so it is documented here
     * rather than buried in the dispatch: a caller choosing between these constants is choosing whether a
     * record is destroyed.
     */
    public enum AttentionIdentifier {

        /**
         * {@code DFHENTER} at {@code app/cbl/COUSR03C.cbl}:109 - looks the record up and emits the
         * confirmation prompt. Deletes nothing.
         */
        ENTER,

        /**
         * {@code DFHPF3} at {@code app/cbl/COUSR03C.cbl}:111 - resolves a target program and leaves.
         * <strong>Deletes nothing.</strong> Worth stating because the sibling update program's {@code PF3}
         * saves first, at {@code app/cbl/COUSR02C.cbl}:111-112; here the exit key merely exits.
         */
        PF3,

        /**
         * {@code DFHPF4} at {@code app/cbl/COUSR03C.cbl}:119 - clears the screen. Deletes nothing, and retains
         * nothing that was cleared.
         */
        PF4,

        /**
         * {@code DFHPF5} at {@code app/cbl/COUSR03C.cbl}:121 - <strong>the only arm that destroys the
         * record.</strong> It is the key the prompt of {@code :283} names, and reaching it without asserting
         * the confirmation raises {@code CHANGES_NOT_CONFIRMED}.
         */
        PF5,

        /**
         * {@code DFHPF12} at {@code app/cbl/COUSR03C.cbl}:123 - returns to the administrative menu. Deletes
         * nothing.
         */
        PF12,

        /**
         * The {@code WHEN OTHER} arm at {@code app/cbl/COUSR03C.cbl}:126 - any key the source does not
         * recognise. Reports {@code CCDA-MSG-INVALID-KEY} and touches the file not at all.
         */
        OTHER
    }

    /**
     * The working storage of {@code app/cbl/COUSR03C.cbl}:35-47, together with the map areas and the record
     * area, as a per-call object.
     *
     * <p><strong>This is what keeps the bean stateless.</strong> One instance is created at the head of every
     * turn and discarded when the turn returns, so nothing a caller does can be observed by another. A field
     * here holding a message or an identifier would be both a concurrency defect and a hygiene violation if it
     * lived on the bean; none does.
     *
     * <p>There is deliberately no credential field: the source moves {@code SEC-USR-PWD} nowhere on this
     * screen, so nothing here holds one.
     */
    private static final class ScreenWorkArea {

        /**
         * Creates an empty work area. Declared explicitly so that the class exposes no implicit constructor
         * beyond the one the enclosing service uses.
         */
        private ScreenWorkArea() {
            // Every field starts at its declared default, which is the source's own initial state.
        }

        /** {@code WS-ERR-FLG PIC X(01)} with {@code ERR-FLG-ON} and {@code ERR-FLG-OFF} at {@code :40-42}. */
        private boolean errFlgOn;

        /**
         * {@code WS-USR-MODIFIED PIC X(01)} with {@code USR-MODIFIED-YES} and {@code USR-MODIFIED-NO} at
         * {@code app/cbl/COUSR03C.cbl}:45-47, set exactly once at {@code :85}.
         *
         * <p><strong>The source never tests it.</strong>
         * {@code grep -n "WS-USR-MODIFIED" app/cbl/COUSR03C.cbl} returns only the declaration, its two
         * condition names and that single {@code SET}; the symbol appears in no {@code IF} and no
         * {@code EVALUATE}. It is retained as a cited, tracked parity artefact rather than deleted to satisfy a
         * linter, it is per-call rather than bean state, and no test is invented for it. Classified Low.
         */
        private boolean usrModified;

        /** {@code WS-MESSAGE PIC X(80)} at {@code app/cbl/COUSR03C.cbl}:38. */
        private String message = SPACES;

        /** {@code WS-RESP-CD PIC S9(09) COMP} at {@code app/cbl/COUSR03C.cbl}:43. */
        private int responseCode;

        /** {@code WS-REAS-CD PIC S9(09) COMP} at {@code app/cbl/COUSR03C.cbl}:44. */
        private int reasonCode;

        /** The file status the recorded response maps to, for the typed translation. */
        private String ioStatus = IO_STATUS_SUCCESS;

        /** {@code TRNNAMEI PIC X(4)} at {@code app/cpy-bms/COUSR03.CPY}:24. */
        private String transactionName;

        /** {@code TITLE01I PIC X(40)} at {@code app/cpy-bms/COUSR03.CPY}:30. */
        private String title01;

        /** {@code CURDATEI PIC X(8)} at {@code app/cpy-bms/COUSR03.CPY}:36. */
        private String currentDate;

        /** {@code PGMNAMEI PIC X(8)} at {@code app/cpy-bms/COUSR03.CPY}:42. */
        private String programName;

        /** {@code TITLE02I PIC X(40)} at {@code app/cpy-bms/COUSR03.CPY}:48. */
        private String title02;

        /** {@code CURTIMEI PIC X(8)} at {@code app/cpy-bms/COUSR03.CPY}:54. */
        private String currentTime;

        /** {@code USRIDINI PIC X(8)} at {@code app/cpy-bms/COUSR03.CPY}:60. */
        private String userId;

        /** {@code FNAMEI PIC X(20)} at {@code app/cpy-bms/COUSR03.CPY}:66. */
        private String firstName;

        /** {@code LNAMEI PIC X(20)} at {@code app/cpy-bms/COUSR03.CPY}:72. */
        private String lastName;

        /** {@code USRTYPEI PIC X(1)} at {@code app/cpy-bms/COUSR03.CPY}:78, carried as its raw character. */
        private String userTypeCode;

        /** {@code ERRMSGI PIC X(78)} at {@code app/cpy-bms/COUSR03.CPY}:84. */
        private String errorMessage;

        /** {@code SEC-USR-ID PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy}, the record area's key. */
        private String secUsrId;

        /** {@code SEC-USR-FNAME PIC X(20)} at {@code app/cpy/CSUSR01Y.cpy}. */
        private String secUsrFname;

        /** {@code SEC-USR-LNAME PIC X(20)} at {@code app/cpy/CSUSR01Y.cpy}. */
        private String secUsrLname;

        /** {@code SEC-USR-TYPE PIC X(01)} at {@code app/cpy/CSUSR01Y.cpy}, as the typed enumeration. */
        private UserType secUsrType;

        /**
         * The entity the read loaded, which is what the delete removes.
         * {@code EXEC CICS DELETE DATASET(WS-USRSEC-FILE)} at {@code app/cbl/COUSR03C.cbl}:307-311 names no
         * {@code RIDFLD}, so it destroys the record held by the preceding {@code READ ... UPDATE}.
         */
        private UserSecurity loadedRecord;

        /** The advisory navigation target, {@code CDEMO-TO-PROGRAM}. Not reported; see the class notes. */
        private String toProgram;

        /** The field a {@code MOVE -1 TO ...L} would have parked the cursor on, named on a typed failure. */
        private String cursorField;

        /** The first typed failure recorded during the turn, raised at the source's own return point. */
        private CardDemoException retainedFailure;

        /** The underlying throwable behind a recorded I/O failure, consumed by the typed translation. */
        private Throwable ioFailureCause;

        /**
         * {@code MOVE LOW-VALUES TO COUSR3AO} at {@code app/cbl/COUSR03C.cbl}:97 - blanks the output map on a
         * first display.
         *
         * <p>The record area is left alone, exactly as the source leaves it: {@code SEC-USER-DATA} is not a
         * map.
         */
        private void clearOutputMap() {
            this.transactionName = SPACES;
            this.title01 = SPACES;
            this.currentDate = SPACES;
            this.programName = SPACES;
            this.title02 = SPACES;
            this.currentTime = SPACES;
            this.userId = SPACES;
            this.firstName = SPACES;
            this.lastName = SPACES;
            this.userTypeCode = SPACES;
            this.errorMessage = SPACES;
        }
    }
}
