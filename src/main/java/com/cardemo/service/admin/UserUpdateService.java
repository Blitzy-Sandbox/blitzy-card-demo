/*
 * ******************************************************************
 * Component   : UserUpdateService
 * Application : CardDemo
 * Type        : Spring @Service (admin, user update)
 * Function    : Update a user in USRSEC file
 * Source      : app/cbl/COUSR02C.cbl (414 lines, 11 own paragraph labels) @ 7756d89
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.OptimisticLockException;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.ConcurrentUpdateException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.dto.UserUpdateRequest;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.service.shared.FileStatusMapper;

/**
 * The user-update screen: one keyed read of the {@code USRSEC} security file, five operator fields validated in
 * one fixed order, four field-by-field change predicates, and a single conditional rewrite. This is the Java
 * replacement for {@code app/cbl/COUSR02C.cbl} (414 lines, 11 own paragraph labels), the CICS program behind transaction
 * {@code CU02} - {@code DEFINE TRANSACTION(CU02)} at {@code app/csd/CARDDEMO.CSD}:469-470 naming
 * {@code PROGRAM(COUSR02C)}, whose own definition is at {@code :292} - painting mapset {@code COUSR02}
 * ({@code :165}) whose generated symbolic map is {@code app/cpy-bms/COUSR02.CPY}.
 *
 * <p>It is <strong>the most quirk-laden of the four services</strong> in {@code com.cardemo.service.admin}, and
 * three of its behaviours look like defects in Java while being the contract: an exit-shaped key that saves, a
 * validation order that differs from the sibling add screen's, and a password comparison that cannot be written
 * with {@code equals}. All three are set out below, and each is cited to the line that proves it.
 *
 * <h2>What it does</h2>
 *
 * <p>Four things. It reads one security record by its eight-character key and reports its three readable fields.
 * It validates five presented fields in the source's order - <strong>identifier first</strong> - and stops at the
 * first one that is empty, reporting that field and the source's exact message. It compares four presented values
 * against the freshly read record and rewrites the record only when at least one genuinely differs, reporting the
 * source's own no-change message when none does. And it reports the three outcomes of each file operation -
 * found, not found, or the operation failed - with the source's exact message text in every case.
 *
 * <p>It is surfaced over HTTP by {@code com.cardemo.controller.AdminController} beneath
 * {@code /api/admin/*}, which is authored, so this service has an HTTP entry point rather than none.
 * {@code com.cardemo.config.SecurityConfig} restricts
 * {@code /api/admin/*} to the ADMIN role, so the rule is in place ahead of the route - the {@code 'A'} against
 * {@code 'U'} distinction of {@code CDEMO-USER-TYPE} at {@code app/cpy/COCOM01Y.cpy}:27-28, surfaced as
 * {@code com.cardemo.model.enums.UserType}.
 *
 * <p>It deliberately does <em>not</em> list, add or delete a user, does not authenticate, does not seed users -
 * {@code src/main/resources/db/migration/V3__seed_data.sql} owns the ten rows that
 * {@code app/jcl/DUSRSECJ.jcl}:35-44 supplies inline - and does not configure security, persistence, HTTP or
 * metrics. It publishes no password encoder, no transaction manager and no HTTP status mapping; it consumes them.
 *
 * <h2>How to build and test</h2>
 *
 * <p>The toolchain, the plugin versions and the zero-warning compiler settings are the project's, and
 * are stated once in {@code pom.xml}; what follows is only what is specific to this file.
 *
 * <ul>
 *   <li>{@code ./mvnw -B -ntp clean compile} - compiles this file. {@code maven-compiler-plugin:3.14.1} runs
 *       {@code -Xlint:all -Werror} with {@code failOnWarning}, so any warning {@code javac} emits fails the
 *       build. Two things it does <em>not</em> emit: an unused import, for which {@code javac} 25 publishes no
 *       lint key, and malformed Javadoc, which no Maven phase checks because no Javadoc plugin is bound in
 *       {@code pom.xml} - both are covered by review and by the explicit doclint command in
 *       {@code docs/technical-specifications.md}.</li>
 *   <li>{@code ./mvnw -B -ntp test} - runs the unit tier through {@code maven-surefire-plugin:3.5.4}. This
 *       bean's tests belong in {@code src/test/java/com/cardemo/unit/**} and never in this package, which holds
 *       exactly four source files and no {@code package-info.java}.</li>
 *   <li>{@code ./mvnw -B -ntp verify} - adds {@code maven-failsafe-plugin:3.5.4} and the
 *       {@code jacoco-maven-plugin:0.8.12} check, which enforces 80% LINE coverage with no exclusion for this
 *       package. Constructor injection and the complete absence of bean-held state are what make that reachable:
 *       every method below is exercisable by handing the constructor a fixed {@code Clock}, a stubbed repository
 *       and a real {@code BCryptPasswordEncoder}, with no database and no Spring context.</li>
 *   <li>The single most important test of this file asserts that <strong>resubmitting the same password does not
 *       register as a change</strong>. It is the regression guarding the substitution described under the
 *       password predicate below, and it fails loudly the moment anyone reaches for {@code equals}.</li>
 *   </ul>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li><strong>BCrypt strength 10</strong> - the {@code PasswordEncoder} bean is owned by
 *       {@code com.cardemo.config.SecurityConfig} and injected here. This class never constructs an encoder,
 *       never names a strength and carries neither {@code @EnableWebSecurity} nor
 *       {@code @EnableTransactionManagement}. Strength 10 is nonetheless a hard contract downstream:
 *       {@code com.cardemo.model.entity.UserSecurity} accepts only a digest whose cost factor is 10 and rejects
 *       anything else, so an encoder configured at another strength fails every password change rather than
 *       silently persisting an off-contract digest.</li>
 *   <li><strong>Record layout</strong> - {@code app/cpy/CSUSR01Y.cpy}:17-23, exactly 80 bytes:
 *       {@code SEC-USR-ID PIC X(08)} at bytes 1-8, {@code SEC-USR-FNAME PIC X(20)} at 9-28,
 *       {@code SEC-USR-LNAME PIC X(20)} at 29-48, {@code SEC-USR-PWD PIC X(08)} at 49-56,
 *       {@code SEC-USR-TYPE PIC X(01)} at 57 and {@code SEC-USR-FILLER PIC X(23)} at 58-80. The eight-character
 *       plaintext password field becomes a 60-character BCrypt digest column; the filler is not modelled.</li>
 *   <li><strong>Key length 8</strong> - from {@code KEYS(8,0)} with {@code RECORDSIZE(80,80) REUSE INDEXED} on
 *       {@code DEFINE CLUSTER(NAME(AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS)...)} at {@code app/jcl/DUSRSECJ.jcl}:65-66,
 *       matching {@code KEYLENGTH(LENGTH OF SEC-USR-ID)} on the read at {@code app/cbl/COUSR02C.cbl}:327.</li>
 *   <li><strong>The five-check order is identifier-first</strong> - user identifier, first name, last name,
 *       password, user type, from {@code app/cbl/COUSR02C.cbl}:180, {@code :186}, {@code :192}, {@code :198} and
 *       {@code :204}. It is a <strong>parity contract, not a preference</strong>, and it is
 *       <strong>not the sibling's order</strong>: see the troubleshooting entry below.</li>
 *   <li><strong>Three-layer concurrency control</strong> - a pessimistic write lock held from the read to the
 *       rewrite, which is what the source's {@code READ ... UPDATE} did, plus a business-level field-by-field
 *       snapshot comparison, plus the provider's stale-write guard. No layer substitutes for another; see the
 *       mechanism note below.</li>
 *   <li><strong>Screen field widths</strong> - taken from {@code app/cpy-bms/COUSR02.CPY} through the constants
 *       of {@code com.cardemo.model.dto.UserSecurityDto} rather than restated here, so the field contract has
 *       one owner: {@code TRNNAMEI PIC X(4)}:24, {@code TITLE01I PIC X(40)}:30, {@code CURDATEI PIC X(8)}:36,
 *       {@code PGMNAMEI PIC X(8)}:42, {@code TITLE02I PIC X(40)}:48, {@code CURTIMEI PIC X(8)}:54,
 *       {@code USRIDINI PIC X(8)}:60, {@code FNAMEI PIC X(20)}:66, {@code LNAMEI PIC X(20)}:72,
 *       {@code PASSWDI PIC X(8)}:78, {@code USRTYPEI PIC X(1)}:84 and {@code ERRMSGI PIC X(78)}:90.</li>
 *   <li><strong>Clock</strong> - the header date and time are read from an injected {@code java.time.Clock},
 *       standing in for {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at
 *       {@code app/cbl/COUSR02C.cbl}:298. Nothing here calls {@code LocalDateTime.now()} with no clock, so the
 *       rendering is deterministic and testable. The formats are {@code MM/DD/YY} and {@code HH:MM:SS}, both
 *       eight characters, from {@code app/cpy/CSDAT01Y.cpy}, and both applied with {@code Locale.ROOT}.</li>
 *   <li><strong>Route</strong> - ADMIN only. Least privilege is enforced by the security configuration, not
 *       here; this bean neither reads a token nor inspects a role.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>Every single request reports that the password changed.</strong> The password predicate was
 *       written with {@code equals}, {@code ==} or a re-encode-and-compare. An eight-character plaintext can
 *       never equal a sixty-character digest, and BCrypt is salted so re-encoding the same plaintext yields a
 *       different string every time; either mistake makes the predicate unconditionally true. Remedy: the
 *       predicate is {@code !passwordEncoder.matches(presented, storedDigest)} and nothing else.</li>
 *   <li><strong>A request with several empty fields reports the wrong field.</strong> Either the five checks were
 *       reordered, or the sibling's order was copied in, or the ordering was delegated to
 *       {@code jakarta.validation}, whose constraint evaluation order is unspecified. This program checks the
 *       <strong>identifier first and the first name second</strong>; {@code app/cbl/COUSR01C.cbl} checks the
 *       first name first and the identifier third, at {@code :120}, {@code :126} and {@code :132}. The two orders
 *       are genuinely different and must not be harmonised in either direction. Remedy: keep the explicit ordered
 *       chain exactly as written.</li>
 *   <li><strong>A stale write silently wins.</strong> The read stopped going through
 *       {@link UserSecurityRepository#findByIdForUpdate(String)} and went back to the unlocked {@code findById},
 *       or the snapshot comparison was dropped. The three layers detect different things over different windows
 *       and all three are required; see the mechanism note below. Remedy: restore whichever layer was
 *       removed.</li>
 *   <li><strong>A conflict is reported as an undifferentiated 409.</strong> The five outcomes of
 *       {@code com.cardemo.exception.ConcurrentUpdateException} were collapsed. This service raises exactly one
 *       of them, {@code DATA_CHANGED_BEFORE_UPDATE}, and keeps its two routes apart by cause. Remedy: keep the
 *       outcome and the cause; the controller owns the status.</li>
 *   <li><strong>A conflict eventually succeeds.</strong> Something retried, merged, re-read or applied
 *       last-writer-wins. There is no retry, no backoff, no {@code @Retryable}, no merge and no re-read: the
 *       source abandons the write and so does this. Remedy: remove it.</li>
 *   <li><strong>The exit key stopped saving.</strong> Someone "fixed" the quirk. It is the contract; see the
 *       preserved-quirk note below. Remedy: revert.</li>
 *   <li><strong>A password or digest appears in a log line, a response or an exception message.</strong> It
 *       cannot come from here. The plaintext is a parameter only, the digest is written and never read back onto
 *       a response, the screen record declares no password component at all, and the
 *       {@code ValidationException} raised for an empty password carries the field <em>name</em> only. Remedy:
 *       keep all four properties true, and treat the masking rules of {@code logback-spring.xml} as a backstop
 *       rather than a licence.</li>
 *   <li><strong>The stored password came back on the response.</strong> Someone reproduced
 *       {@code app/cbl/COUSR02C.cbl}:169; see the labelled deviation below. Remedy: revert, and do not substitute
 *       a masked or placeholder field either.</li>
 *   <li><strong>A message no longer matches the baseline.</strong> A literal was normalised. All five empty-field
 *       messages read {@code can NOT} with a capital N, O and T; every ellipsis in this program is exactly three
 *       periods; and {@code "Please modify to update ..."}, {@code "Press PF5 key to save your updates ..."} and
 *       {@code " has been updated ..."} each carry a space before those periods while
 *       {@code "User ID NOT found..."}, {@code "Unable to lookup User..."} and {@code "Unable to Update User..."}
 *       do not. Remedy: never re-case, re-space or re-punctuate them; the parity gates compare them byte for
 *       byte.</li>
 *   <li><strong>{@code "Unable to Update User..."} was corrected on a delete path.</strong> Not this file's
 *       concern, but the reason a reader might touch it: the verb is <em>correct here</em>, on the rewrite
 *       failure at {@code app/cbl/COUSR02C.cbl}:386. The sibling delete program carries the identical literal on
 *       a delete failure at {@code app/cbl/COUSR03C.cbl}:332, where the verb is wrong. Remedy: leave both
 *       alone.</li>
 *   <li><strong>A missing user is treated as success.</strong> One of the three batch-side
 *       "record not found is success" carve-outs was imported. All three are scoped to batch - the
 *       {@code TCATBAL} upsert at {@code app/cbl/CBTRN02C.cbl}:481, the first {@code DISCGRP} {@code DEFAULT}
 *       fallback at {@code app/cbl/CBACT04C.cbl}:422 and {@code :436}, and {@code CBSTM03B}'s acceptance of
 *       {@code '04'}. Here a missing user is an <strong>error</strong>, reported with the message of
 *       {@code :342}. Remedy: none of {@code FileStatusMapper}'s carve-out methods may be called from this file,
 *       and none is.</li>
 *   <li><strong>Startup fails naming {@code PasswordEncoder} or {@code Clock}.</strong> Both are constructor
 *       arguments and neither is published here. Remedy: publish the encoder from
 *       {@code com.cardemo.config.SecurityConfig} at strength 10 and a {@code Clock} bean for the tree.</li>
 *   <li><strong>Every password change fails complaining about the digest shape.</strong> The injected encoder is
 *       not BCrypt at strength 10. Remedy: fix the encoder bean; do not widen the entity's contract.</li>
 *   <li><strong>A half-applied update survives a failure.</strong> The read, the comparison and the rewrite are
 *       not inside one boundary. Remedy: keep {@code @Transactional(rollbackFor = Exception.class)} on the entry
 *       points.</li>
 *   </ul>
 *
 * <h2>Paragraph correspondence, and one disclosed limitation</h2>
 *
 * <p>All eleven paragraph labels of {@code app/cbl/COUSR02C.cbl} are
 * present one-to-one and none is consolidated: {@code MAIN-PARA}:82, {@code PROCESS-ENTER-KEY}:143,
 * {@code UPDATE-USER-INFO}:177, {@code RETURN-TO-PREV-SCREEN}:250, {@code SEND-USRUPD-SCREEN}:266,
 * {@code RECEIVE-USRUPD-SCREEN}:283, {@code POPULATE-HEADER-INFO}:296, {@code READ-USER-SEC-FILE}:320,
 * {@code UPDATE-USER-SEC-FILE}:358, {@code CLEAR-CURRENT-SCREEN}:395 and {@code INITIALIZE-ALL-FIELDS}:403. The
 * source-citing Javadoc on each is the evidence the scope-coverage gate reads. Mapping fewer than eleven
 * private methods breaks that correspondence.
 *
 * <p>One limitation is disclosed rather than closed:
 * {@code com.cardemo.model.entity.UserSecurity} carries <strong>no version column</strong> - its own
 * documentation records that it has no optimistic-locking counter, unlike the account, card, customer and
 * transaction entities, and the first migration creates none for this table. The store-level layer is therefore
 * implemented as a stale-write guard rather than as a version comparison; see the concurrency mechanism note for
 * exactly what that does and does not detect. Adding a counter is not this file's to do: the entity and the
 * migration are separate artefacts with their own owners.
 *
 * <h3>Low</h3>
 *
 * <ul>
 *   <li>{@code WHEN OTHER} at {@code app/cbl/COUSR02C.cbl}:210-212 parks the cursor on the first-name field and
 *       then does nothing - a literal {@code CONTINUE}. It is the success path of the {@code EVALUATE} and is
 *       retained as the cursor assignment plus a comment. The same shape recurs at {@code :152-154} in
 *       {@code PROCESS-ENTER-KEY}.</li>
 *   <li>A second literal {@code CONTINUE} sits at {@code :335}, on the {@code DFHRESP(NORMAL)} arm of the read,
 *       immediately before the statement that sets the hint message. It is inert and retained as a comment.</li>
 *   <li>{@code RETURN-TO-PREV-SCREEN} sets four communication-area fields that have no counterpart at all under
 *       the stateless mandate. The label is mapped anyway; see that method's documentation.</li>
 *   <li>{@code CLEAR-CURRENT-SCREEN} and {@code INITIALIZE-ALL-FIELDS} clear a screen that no longer exists.
 *       Both labels are mapped anyway, as reset semantics collapsing into a fresh empty response.</li>
 *   <li>The plan's general note on the symbolic maps records {@code CURTIME} as {@code X(9)}. Both
 *       {@code app/cpy-bms/COUSR02.CPY}:54 and {@code app/cpy/CSDAT01Y.cpy} say eight. The source governs, so
 *       eight is used. Citation correction only.</li>
 *   <li>A blank password is <strong>rejected, not interpreted as "leave the stored credential
 *       unchanged"</strong>: {@code :198} tests the field for emptiness and {@code :200} reports
 *       {@code "Password can NOT be empty..."}. The source governs. Leaving the digest untouched is instead
 *       what happens when a password is supplied and <em>matches</em> the stored one, which is the ordinary
 *       no-change outcome. {@code com.cardemo.model.dto.UserUpdateRequest} previously documented the opposite
 *       and now documents this; the divergence between the two files is closed, and this entry is retained so
 *       that a reader who remembers the old claim finds it addressed rather than silently gone.</li>
 *   </ul>
 *
 * <h2>Preserved legacy quirk: the exit-shaped key saves</h2>
 *
 * <p>{@code app/cbl/COUSR02C.cbl}:111-112 reads {@code WHEN DFHPF3} then {@code PERFORM UPDATE-USER-INFO}. The
 * paragraph it performs is {@code UPDATE-USER-INFO} at {@code :177}, the paragraph that rewrites the record. The
 * arm then resolves a target program at {@code :113-118} and transfers at {@code :119}, so <strong>PF3 commits
 * the pending changes and only then navigates away</strong>. The second call site of the same paragraph is
 * {@code :123}, under {@code WHEN DFHPF5}, which saves and stays.
 *
 * <p>PF3 is the back or cancel key everywhere else in the corpus, so this is genuinely anomalous rather than
 * conventional. Four independent facts prove it:
 *
 * <ol>
 *   <li>{@code app/cbl/COUSR02C.cbl}:336 - the hint the program itself displays after a successful read reads
 *       {@code "Press PF5 key to save your updates ..."}, naming <strong>PF5</strong> and not PF3. The program's
 *       own operator guidance contradicts its own dispatch.</li>
 *   <li>{@code app/cbl/COUSR02C.cbl}:124-126 - {@code WHEN DFHPF12} moves {@code 'COADM01C'} and transfers
 *       <strong>without</strong> performing the update paragraph, so the program does contain a
 *       leave-without-saving path; PF3 simply is not it.</li>
 *   <li>{@code app/cbl/COUSR03C.cbl}:111-118 - the sibling delete program uses PF3
 *       <strong>conventionally</strong>: it resolves the target program and transfers, with no update
 *       paragraph.</li>
 *   <li>{@code app/cbl/COUSR00C.cbl}:125-127 and {@code app/cbl/COUSR01C.cbl}:93-95 - the list and add programs
 *       likewise use PF3 conventionally, each moving {@code 'COADM01C'} and transferring.</li>
 *   </ol>
 *
 * <p><strong>It is preserved exactly.</strong> The Java surface exposes the same behaviour: the exit-shaped
 * action commits. It is not relocated, not flag-gated, not turned into a discard-on-exit and not
 * "corrected", because behavioural parity is the contract and this is behaviour a caller can observe. Both call
 * sites are mapped and the dispatch is not collapsed. It is surprising, but deterministic and
 * behaviour-bearing, and it is a preserved legacy quirk rather than an oversight.
 *
 * <h2>Labelled deviation: the stored password is not echoed back</h2>
 *
 * <p>{@code app/cbl/COUSR02C.cbl}:169 reads {@code MOVE SEC-USR-PWD TO PASSWDI OF COUSR2AI}. After a successful
 * read the source moves the <strong>stored plaintext password</strong> into a screen field and sends it to the
 * terminal, so the operator sees the current credential of the user being edited.
 *
 * <p><strong>That behaviour is not reproduced.</strong> It cannot be and it may not be. It cannot be because the
 * target stores a BCrypt digest, which is one-way by construction and holds no recoverable plaintext to echo. It
 * may not be because Rule 1 Clause D forbids returning a secret at all. This is the one place in the corpus where
 * the system of record itself discloses a credential.
 *
 * <p>The enclosing behaviour is mapped one-to-one - the read runs, the other three fields are reported, and the
 * hint of {@code :336} is emitted - but <strong>the password is omitted from the response entirely</strong>. The
 * screen record declares no password component at all, rather than declaring one and leaving it null or masked,
 * so returning a credential is structurally impossible instead of merely unlikely. No placeholder that looks like
 * a password is fabricated, the digest is never returned, and no masked-but-present field exists.
 *
 * <p><strong>What changes for the caller.</strong> The current password is not readable, so a caller cannot
 * pre-fill it. The update request must therefore supply the password whenever the record is to be rewritten, and
 * supplying the same password again is the way to leave the stored digest untouched - which the change predicate
 * recognises correctly, as the password-predicate note explains. This is a labelled deviation,
 * <strong>not</strong> parity.
 *
 * <h2>Labelled mechanism substitution: the password predicate is {@code matches}, never {@code equals}</h2>
 *
 * <p>{@code app/cbl/COUSR02C.cbl}:227 reads {@code IF PASSWDI OF COUSR2AI NOT = SEC-USR-PWD}. Both operands are
 * {@code PIC X(08)} plaintext, so the source compares a presented password against a stored password directly.
 *
 * <p>The target stores a sixty-character BCrypt digest. A mechanical translation to
 * {@code !presented.equals(storedDigest)} compiles, runs, and is <strong>unconditionally true</strong>: an
 * eight-character plaintext can never equal a sixty-character digest. Every request would report a password
 * change and rewrite the row. Re-encoding the presented value and comparing the results fails the same way for a
 * different reason - BCrypt embeds a random salt, so encoding one plaintext twice yields two different strings.
 *
 * <p>The correct substitution is {@code !passwordEncoder.matches(presented, storedDigest)}, which asks the
 * encoder the question the source was asking: is this the same password? This is the direct analogue of the
 * date-offset trap in the account-update program, where a naive whole-string comparison would likewise have
 * reported a change on every single request. It is a <strong>mechanism substitution, not a behaviour
 * change</strong>: the predicate answers identically to the source's for every input the source could hold.
 *
 * <h2>Labelled mechanism note: three-layer concurrency control</h2>
 *
 * <p>{@code app/csd/CARDDEMO.CSD}:88 defines {@code FILE(USRSEC)} with
 * {@code DSNAME(AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS)}, {@code BROWSE(YES) DELETE(YES) READ(YES) UPDATE(YES)
 * ADD(YES)} and {@code UPDATEMODEL(LOCKING)}, with {@code RECOVERY(NONE)}. The read at {@code :322-331} carries
 * {@code UPDATE}, so CICS held an exclusive record lock from the read until the rewrite at {@code :360-366} or
 * the end of the task. Part of that span - the terminal conversation between the two pseudo-conversational
 * tasks - has no counterpart on a stateless surface. The rest of it does, and the guarantee is rebuilt from
 * three layers rather than two.
 *
 * <ul>
 *   <li><strong>Store layer, primary - a pessimistic write lock, which is what the source did.</strong> The read
 *       goes through {@link UserSecurityRepository#findByIdForUpdate(String)}, so the row is held exclusively
 *       from the read until the enclosing transaction ends. This is the direct analogue of {@code READ ... UPDATE}
 *       under {@code UPDATEMODEL(LOCKING)}, and it is what makes the read-compare-rewrite sequence indivisible:
 *       a second administrator's transaction blocks at its own read rather than interleaving with this one.
 *       <strong>Finding, MEDIUM severity, resolved:</strong> this layer was absent, and without it the read and
 *       the rewrite were an unguarded read-modify-write in which the second of two concurrent updates silently
 *       discarded the first.</li>
 *   <li><strong>Business layer - an explicit field-by-field snapshot comparison.</strong> The caller states what
 *       it was last shown, and each stated value is compared against the record as freshly read inside the
 *       transaction. A disagreement means the record moved between the caller's read and its write, and the
 *       write is abandoned. This is what {@code app/cbl/COACTUPC.cbl}:669-756 does for the account screen, and it
 *       is what neither a lock nor a version counter can do: it detects <em>which field values</em> differ, over
 *       a window wider than one transaction.</li>
 *   <li><strong>Store layer, secondary - a stale-write guard.</strong> A write that the persistence provider
 *       finds no longer applies to the row it read is reported rather than reapplied. This still matters under a
 *       lock, because the row can be deleted by a transaction that committed before this one took its lock.</li>
 *   </ul>
 *
 * <p><strong>No layer substitutes for another.</strong> The lock serialises concurrent writers but cannot know
 * that a caller composed its submission from a view that is now stale; the snapshot comparison detects exactly
 * that, but only for the fields the caller states; and a concurrent delete is caught by neither and is reported
 * by the provider. They are three different guarantees over three different windows, and relying on any one
 * alone breaks the guarantee.
 *
 * <p>Both routes raise {@code com.cardemo.exception.ConcurrentUpdateException} with the same outcome,
 * {@code DATA_CHANGED_BEFORE_UPDATE}, because that is the one of the type's five outcomes this program's
 * condition is. They stay <strong>distinguishable by cause</strong>: the store-layer route carries the
 * provider's own failure as the cause, and the business-layer route carries none. The exception names the
 * dataset and, where a field is at fault, the field <em>name</em>; it never carries a field value, and never a
 * password or digest. There is <strong>no retry, no backoff, no merge, no re-read and no last-writer-wins</strong>
 * - the source abandons the write, and so does this.
 *
 * <p>The no-change outcome of {@code :239}, {@code "Please modify to update ..."}, is
 * <strong>not</strong> a concurrency conflict and is not reported as one: it is the source's own answer to a
 * submission that changed nothing, and it is a distinct outcome carrying no exception at all.
 *
 * <h2>Labelled mechanism note: the declarative transaction boundary</h2>
 *
 * <p>The source has no explicit unit-of-work statement in this program: there is no {@code SYNCPOINT} and no
 * {@code SYNCPOINT ROLLBACK} anywhere in the 414 lines. The read at {@code :322-331} takes a lock, the rewrite at
 * {@code :360-366} applies one change, and CICS commits with the task. Scoping the read, the comparison and the
 * rewrite in one {@code @Transactional(rollbackFor = Exception.class)} method reproduces the same all-or-nothing
 * outcome <strong>by scoping rather than by conditional logic</strong>: every failure path returns or throws
 * before the commit point, so nothing needs to be made conditional. This is a <strong>mechanism substitution, not
 * a behaviour change</strong>, and transaction management itself is owned by
 * {@code com.cardemo.config.JpaConfig}.
 *
 * <h2>Behaviour the source does not define, and which is therefore not invented</h2>
 *
 * <ul>
 *   <li><strong>Latency and throughput objectives.</strong> No service level is published anywhere
 *       in the source, and none is invented; the performance gate records a measured baseline, never a
 *       target.</li>
 *   <li><strong>Password strength, complexity, minimum length, character classes, expiry, reuse, history and
 *       lockout policy.</strong> {@code app/cbl/COUSR02C.cbl}:198 tests emptiness and nothing
 *       else, and {@code SEC-USR-PWD} is a bare {@code PIC X(08)}. No rule is invented here.</li>
 *   <li><strong>A retry or conflict-resolution policy.</strong> The source simply abandons the
 *       write and redisplays; there is no retry interval, no attempt count and no merge strategy anywhere in the
 *       corpus to reproduce.</li>
 *   <li><strong>An audit trail for a user change.</strong> The source rewrites one record and
 *       records nothing about who rewrote it - the two moves that would have carried the acting user's identity
 *       are commented out in the sibling add program and absent here. None is invented.</li>
 *   <li><strong>A membership test on the user type.</strong> Not a source rule:
 *       {@code app/cbl/COUSR02C.cbl}:204 checks only that the field is non-empty and {@code :232} is a plain
 *       one-character move, so the source would store any character at all. The target's type is a closed
 *       enumeration, so an unmappable code is rejected instead of stored; that narrowing is a labelled
 *       deviation rather than parity.</li>
 *   </ul>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Immutable and stateless, therefore safe for the shared singleton it is. Its four collaborators are assigned
 * once in the constructor and never reassigned; there is no static mutable field; and every working-storage item
 * of the source - {@code WS-ERR-FLG} at {@code :40}, {@code WS-MESSAGE} at {@code :38},
 * {@code WS-USR-MODIFIED} at {@code :45}, {@code WS-RESP-CD} and {@code WS-REAS-CD} at {@code :43-44} and the
 * whole of {@code SEC-USER-DATA} - lives in a per-invocation work area rather than on the bean. That is a
 * correctness requirement twice over: a shared message field would interleave between concurrent requests, and a
 * shared credential field would be a disclosure that outlived the request.
 */
@Service
public class UserUpdateService {

    /**
     * Diagnostic sink. Used only for the coarse-grained outcome of a lookup or an update, and only ever handed
     * the eight-character identifier - never a name, never the presented password, never a digest and never any
     * other component of the record.
     */
    private static final Logger LOG = LoggerFactory.getLogger(UserUpdateService.class);

    /**
     * {@code WS-PGMNAME PIC X(08) VALUE 'COUSR02C'}, {@code app/cbl/COUSR02C.cbl}:36. Painted onto the header at
     * {@code :303} and carried as the abend culprit on a fatal condition.
     */
    private static final String PROGRAM_NAME = "COUSR02C";

    /**
     * {@code WS-TRANID PIC X(04) VALUE 'CU02'}, {@code app/cbl/COUSR02C.cbl}:37, and the transaction
     * {@code app/csd/CARDDEMO.CSD}:469-470 maps onto {@code PROGRAM(COUSR02C)}. Painted onto the header at
     * {@code :302}.
     */
    private static final String TRANSACTION_ID = "CU02";

    /**
     * {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '}, {@code app/cbl/COUSR02C.cbl}:39, named on the read at
     * {@code :323} and the rewrite at {@code :361}. Held unpadded because it is used as an identity on a typed
     * failure and never as a fixed-width field.
     */
    private static final String USRSEC_FILE = "USRSEC";

    /** The operation named on a typed failure raised by the read of {@code app/cbl/COUSR02C.cbl}:322-331. */
    private static final String READ_OPERATION = "READ";

    /** The operation named on a typed failure raised by the rewrite of {@code app/cbl/COUSR02C.cbl}:360-366. */
    private static final String REWRITE_OPERATION = "REWRITE";

    /**
     * The administrative menu, moved into {@code CDEMO-TO-PROGRAM} on the PF3 arm at
     * {@code app/cbl/COUSR02C.cbl}:114 and the PF12 arm at {@code :125}.
     */
    private static final String ADMIN_MENU_PROGRAM = "COADM01C";

    /**
     * The sign-on program, moved into {@code CDEMO-TO-PROGRAM} when the program is reached with no communication
     * area at {@code app/cbl/COUSR02C.cbl}:91, and substituted by the defaulting guard at {@code :252-254}.
     */
    private static final String SIGN_ON_PROGRAM = "COSGN00C";

    /**
     * {@code CCDA-TITLE01}, {@code app/cpy/COTTL01Y.cpy}. Exactly forty characters including its leading and
     * trailing padding, because the field is {@code PIC X(40)} and the padding is part of the value the screen
     * displayed.
     */
    private static final String SCREEN_TITLE_01 = "      AWS Mainframe Modernization       ";

    /**
     * {@code CCDA-TITLE02}, {@code app/cpy/COTTL01Y.cpy}. Exactly forty characters. The copybook carries a
     * commented-out alternative reading {@code Credit Card Demo Application (CCDA)} directly above it; the
     * active value is the one below and the comment is not reproduced.
     */
    private static final String SCREEN_TITLE_02 = "              CardDemo                  ";

    /**
     * {@code CCDA-MSG-INVALID-KEY}, {@code app/cpy/CSMSG01Y.cpy}, moved into {@code WS-MESSAGE} on the
     * {@code WHEN OTHER} arm at {@code app/cbl/COUSR02C.cbl}:129.
     *
     * <p>The copybook declares it {@code PIC X(50)} with nine trailing spaces of padding. The significant text
     * ends at the third period, and {@code WS-MESSAGE} is itself {@code PIC X(80)}, so the padding is not part
     * of the message the operator read and is not reproduced. The three periods are.
     */
    private static final String INVALID_KEY_MESSAGE = "Invalid key pressed. Please see below...";

    /**
     * {@code app/cbl/COUSR02C.cbl}:182, the <strong>first</strong> of the five checks in this program, and
     * {@code :148} in {@code PROCESS-ENTER-KEY}, which raises the identical literal. Note {@code can NOT} with a
     * capital N, O and T, and the three-period ellipsis with no space before it.
     */
    private static final String USER_ID_REQUIRED_MESSAGE = "User ID can NOT be empty...";

    /** {@code app/cbl/COUSR02C.cbl}:188, the second of the five checks. */
    private static final String FIRST_NAME_REQUIRED_MESSAGE = "First Name can NOT be empty...";

    /** {@code app/cbl/COUSR02C.cbl}:194, the third of the five checks. */
    private static final String LAST_NAME_REQUIRED_MESSAGE = "Last Name can NOT be empty...";

    /** {@code app/cbl/COUSR02C.cbl}:200, the fourth of the five checks. */
    private static final String PASSWORD_REQUIRED_MESSAGE = "Password can NOT be empty...";

    /** {@code app/cbl/COUSR02C.cbl}:206, the fifth of the five checks. */
    private static final String USER_TYPE_REQUIRED_MESSAGE = "User Type can NOT be empty...";

    /**
     * {@code app/cbl/COUSR02C.cbl}:239, emitted when none of the four change predicates matched. Note the
     * <strong>space before</strong> the three periods, which the five required-field messages do not have.
     */
    private static final String NO_CHANGE_MESSAGE = "Please modify to update ...";

    /**
     * {@code app/cbl/COUSR02C.cbl}:336, emitted on the {@code DFHRESP(NORMAL)} arm of the read.
     *
     * <p>This literal names <strong>PF5</strong>. The dispatch at {@code :111-112} nonetheless also saves on
     * PF3, and the disagreement between the two is the primary evidence for the preserved quirk documented on
     * this class. The literal is reproduced exactly as written, space before the ellipsis included, and is not
     * amended to mention PF3.
     */
    private static final String SAVE_HINT_MESSAGE = "Press PF5 key to save your updates ...";

    /**
     * {@code app/cbl/COUSR02C.cbl}:342 on the read's {@code DFHRESP(NOTFND)} arm and {@code :379} on the
     * rewrite's. One literal, two sites, and no space before the ellipsis.
     */
    private static final String USER_NOT_FOUND_MESSAGE = "User ID NOT found...";

    /** {@code app/cbl/COUSR02C.cbl}:349, the read's {@code WHEN OTHER} arm. */
    private static final String UNABLE_TO_LOOKUP_MESSAGE = "Unable to lookup User...";

    /**
     * {@code app/cbl/COUSR02C.cbl}:386, the rewrite's {@code WHEN OTHER} arm.
     *
     * <p><strong>The verb is correct here.</strong> This is an update failure on an update path. The sibling
     * delete program carries the identical literal on a delete failure at {@code app/cbl/COUSR03C.cbl}:332,
     * where the verb is wrong. Neither is amended, and the two must not be harmonised.
     */
    private static final String UNABLE_TO_UPDATE_MESSAGE = "Unable to Update User...";

    /**
     * The first operand of the {@code STRING} at {@code app/cbl/COUSR02C.cbl}:372, which is
     * {@code DELIMITED BY SIZE} and so contributes in full, trailing space included.
     */
    private static final String UPDATED_MESSAGE_PREFIX = "User ";

    /**
     * The third operand of the {@code STRING} at {@code app/cbl/COUSR02C.cbl}:374, also
     * {@code DELIMITED BY SIZE}. Note the leading space and the space before the three periods.
     */
    private static final String UPDATED_MESSAGE_SUFFIX = " has been updated ...";

    /**
     * {@code DFHGREEN}, moved into {@code ERRMSGC} on the successful rewrite at
     * {@code app/cbl/COUSR02C.cbl}:371. Carried as the attribute name rather than as the byte value, because a
     * JSON caller has no terminal attribute byte to interpret.
     */
    private static final String MESSAGE_COLOUR_GREEN = "DFHGREEN";

    /**
     * {@code DFHRED}, moved into {@code ERRMSGC} on the no-change arm at {@code app/cbl/COUSR02C.cbl}:241.
     */
    private static final String MESSAGE_COLOUR_RED = "DFHRED";

    /**
     * {@code DFHNEUTR}, moved into {@code ERRMSGC} on the read's {@code DFHRESP(NORMAL)} arm at
     * {@code app/cbl/COUSR02C.cbl}:338.
     */
    private static final String MESSAGE_COLOUR_NEUTRAL = "DFHNEUTR";

    /**
     * The Java reading of {@code MOVE SPACES} and of {@code LOW-VALUES} for a screen field. A COBOL
     * {@code PIC X(n)} field cleared with either renders as nothing on a terminal, so the empty string is the
     * faithful equivalent on a response and space padding is never fabricated.
     */
    private static final String SPACES = "";

    /**
     * {@code WS-CURDATE-MM-DD-YY} of {@code app/cpy/CSDAT01Y.cpy}: {@code 9(02)}, {@code '/'}, {@code 9(02)},
     * {@code '/'}, {@code 9(02)}, which is {@code MM/DD/YY} and exactly eight characters. Assembled at
     * {@code app/cbl/COUSR02C.cbl}:305-307 and moved onto the header at {@code :309}, where {@code :307} takes
     * {@code WS-CURDATE-YEAR(3:2)} - the <strong>last two digits</strong> of the four-digit year, which is what
     * the two-digit pattern below reproduces. Bound with {@code Locale.ROOT} so the rendering cannot shift with
     * a host default.
     */
    private static final DateTimeFormatter HEADER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT);

    /**
     * {@code WS-CURTIME-HH-MM-SS} of {@code app/cpy/CSDAT01Y.cpy}: {@code 9(02)}, {@code ':'}, {@code 9(02)},
     * {@code ':'}, {@code 9(02)}, which is {@code HH:MM:SS} and exactly eight characters. Assembled at
     * {@code app/cbl/COUSR02C.cbl}:311-313 and moved onto the header at {@code :315}. The copybook's
     * {@code WS-CURTIME-MILSEC} is not part of this field and is not rendered. Bound with {@code Locale.ROOT}.
     */
    private static final DateTimeFormatter HEADER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /**
     * The field {@code MOVE -1 TO USRIDINL} parks the cursor on, at {@code app/cbl/COUSR02C.cbl}:98,
     * {@code :150}, {@code :153}, {@code :184}, {@code :344}, {@code :381} and {@code :405}. Named for the
     * symbolic-map field {@code USRIDINI} of {@code app/cpy-bms/COUSR02.CPY}:60 - note the spelling, which this
     * map, the list map and the delete map share while the add map alone spells it {@code USERIDI}.
     */
    private static final String CURSOR_FIELD_USER_ID = "USRIDIN";

    /**
     * The field {@code MOVE -1 TO FNAMEL} parks the cursor on, at {@code app/cbl/COUSR02C.cbl}:190,
     * {@code :211}, {@code :351} and {@code :388}.
     */
    private static final String CURSOR_FIELD_FIRST_NAME = "FNAME";

    /** The field {@code MOVE -1 TO LNAMEL} parks the cursor on, at {@code app/cbl/COUSR02C.cbl}:196. */
    private static final String CURSOR_FIELD_LAST_NAME = "LNAME";

    /** The field {@code MOVE -1 TO PASSWDL} parks the cursor on, at {@code app/cbl/COUSR02C.cbl}:202. */
    private static final String CURSOR_FIELD_PASSWORD = "PASSWD";

    /** The field {@code MOVE -1 TO USRTYPEL} parks the cursor on, at {@code app/cbl/COUSR02C.cbl}:208. */
    private static final String CURSOR_FIELD_USER_TYPE = "USRTYPE";

    /** The name carried on a typed failure about {@code USRIDINI}. A field name only, never a field value. */
    private static final String FIELD_USER_ID = "userId";

    /** The name carried on a typed failure about {@code FNAMEI}. */
    private static final String FIELD_FIRST_NAME = "firstName";

    /** The name carried on a typed failure about {@code LNAMEI}. */
    private static final String FIELD_LAST_NAME = "lastName";

    /**
     * The name carried on a typed failure about {@code PASSWDI}. <strong>Only ever the name.</strong> The value
     * is a credential and is never placed on an exception, a log record or a response.
     */
    private static final String FIELD_PASSWORD = "password";

    /** The name carried on a typed failure about {@code USRTYPEI}. */
    private static final String FIELD_USER_TYPE = "userType";

    /**
     * The width of the presented password field, {@code PASSWDI PIC X(8)} at
     * {@code app/cpy-bms/COUSR02.CPY}:78, which is also the width of {@code SEC-USR-PWD PIC X(08)} at
     * {@code app/cpy/CSUSR01Y.cpy}:21.
     *
     * <p>It is declared here rather than taken from {@code UserSecurityDto}, because that record deliberately
     * carries no password component and therefore no password width; borrowing the identifier width, which
     * happens to be eight as well, would tie the two together by coincidence.
     *
     * <p>The width bounds what the 3270 map could physically accept and nothing more. It is not a
     * password-strength policy: the source defines none, and none is invented here.
     */
    private static final int PRESENTED_PASSWORD_WIDTH = 8;

    /** {@code DFHRESP(NORMAL)}, evaluated at {@code app/cbl/COUSR02C.cbl}:334 and {@code :369}. */
    private static final int CICS_RESP_NORMAL = 0;

    /** {@code DFHRESP(NOTFND)}, evaluated at {@code app/cbl/COUSR02C.cbl}:340 and {@code :377}. */
    private static final int CICS_RESP_NOTFND = 13;

    /**
     * {@code DFHRESP(IOERR)}, one of the conditions the {@code WHEN OTHER} arms at
     * {@code app/cbl/COUSR02C.cbl}:346 and {@code :383} absorb.
     */
    private static final int CICS_RESP_IOERR = 17;

    /**
     * File status {@code '00'}, the sole success value. Recorded alongside the CICS condition so that the
     * response evaluation can read like the source's {@code EVALUATE WS-RESP-CD} while failure translation still
     * goes through the one component that owns file-status mapping.
     */
    private static final String IO_STATUS_SUCCESS = "00";

    /**
     * File status {@code '23'}, the batch-side equivalent of {@code DFHRESP(NOTFND)}.
     *
     * <p>Recorded for completeness of the two vocabularies, and deliberately <strong>not</strong> routed through
     * the shared mapper here: the source answers a missing user with its own message at
     * {@code app/cbl/COUSR02C.cbl}:342 and {@code :379}, so the not-found arms construct that outcome directly
     * with the source's text. None of the three batch-side "not found is success" carve-outs applies on this
     * path.
     */
    private static final String IO_STATUS_RECORD_NOT_FOUND = "23";

    /**
     * File status {@code '90'}, a representative of the {@code '9x'} physical-or-logical-error family that the
     * {@code WHEN OTHER} arms absorb. It is what the shared mapper is handed.
     */
    private static final String IO_STATUS_IO_ERROR = "90";

    /**
     * The {@code USRSEC} cluster, reached through its {@code JpaRepository} contract: one keyed read and one
     * conditional rewrite.
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * The BCrypt encoder published by {@code com.cardemo.config.SecurityConfig} at strength 10. Consumed, never
     * redeclared, and never asked for its strength. Both of its uses matter here: {@code matches} answers the
     * change predicate of {@code app/cbl/COUSR02C.cbl}:227, and {@code encode} produces the digest that replaces
     * the plaintext move at {@code :228}.
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * The single owner of the file-status-to-exception decision and of the {@code FILE STATUS IS: NNNN}
     * rendering. Neither is reimplemented here, and no status is re-rendered.
     */
    private final FileStatusMapper fileStatusMapper;

    /**
     * The time source behind {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at
     * {@code app/cbl/COUSR02C.cbl}:298.
     */
    private final Clock clock;

    /**
     * Constructs the bean. Constructor injection only: there is no field {@code @Autowired}, no setter injection
     * and no service locator, which is what keeps the bean immutable, thread safe and directly unit testable
     * without a Spring context.
     *
     * @param userSecurityRepository the {@code USRSEC} cluster; must not be {@code null}
     * @param passwordEncoder        the BCrypt encoder owned by {@code com.cardemo.config.SecurityConfig} at
     *                               strength 10; must not be {@code null}. It is consumed as the
     *                               {@code PasswordEncoder} abstraction so that this bean neither names an
     *                               implementation nor pins a cost factor
     * @param fileStatusMapper       the shared file-status-to-exception mapper; must not be {@code null}
     * @param clock                  the time source for the screen header; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public UserUpdateService(final UserSecurityRepository userSecurityRepository,
            final PasswordEncoder passwordEncoder,
            final FileStatusMapper fileStatusMapper,
            final Clock clock) {

        this.userSecurityRepository =
                Objects.requireNonNull(userSecurityRepository, "userSecurityRepository must not be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
        this.fileStatusMapper = Objects.requireNonNull(fileStatusMapper, "fileStatusMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    // Public surface. Five entry points, one per way the source can be reached: with no communication
    // area, on a first display, on the ENTER arm, on the save arm, and on a submitted screen with any
    // attention identifier at all - which is the only entry point through which the preserved PF3-save
    // quirk is reachable.

    /**
     * Reproduces the source reached with no communication area at all: {@code IF EIBCALEN = 0} at
     * {@code app/cbl/COUSR02C.cbl}:90-92, which moves {@code 'COSGN00C'} into {@code CDEMO-TO-PROGRAM} and
     * transfers to the sign-on program without touching the security file.
     *
     * <p>Reading and writing nothing is the whole point of the path, so the returned screen carries no field
     * content at all and {@code 'COSGN00C'} as its advisory navigation target. Nothing is thrown, because the
     * source raises no
     * condition here, and no header is painted, because the source transfers before
     * {@code SEND-USRUPD-SCREEN} is ever performed on this arm.
     *
     * <p>Side effects: none. No query is issued and no row is written.
     *
     * @return a screen whose only meaningful content is the navigation target; never {@code null}
     */
    public UserUpdateScreen openWithoutContext() {
        return mainPara(false, false, AttentionIdentifier.ENTER, null, null, null);
    }

    /**
     * Opens the update screen: the first-display arm at {@code app/cbl/COUSR02C.cbl}:95-105, where
     * {@code CDEMO-PGM-REENTER} is not yet set, the output map is blanked with
     * {@code MOVE LOW-VALUES TO COUSR2AO} at {@code :97}, the cursor is parked on the identifier field at
     * {@code :98}, and the screen is sent at {@code :105}.
     *
     * <p><strong>The pre-selection hand-off.</strong> {@code :99-104} tests
     * {@code CDEMO-CU02-USR-SELECTED NOT = SPACES AND LOW-VALUES} and, when a user was selected on the list
     * screen, moves that identifier into {@code USRIDINI} and performs {@code PROCESS-ENTER-KEY} immediately, so
     * the screen opens already populated with that user's record. Passing {@code null} or a blank value
     * reproduces the arm where no user was pre-selected and the screen opens empty.
     *
     * <p><strong>Side effects.</strong> When an identifier is supplied, one keyed read is issued; nothing is ever
     * written on this path. The method is transactional because that read is the source's
     * {@code READ ... UPDATE}.
     *
     * @param preselectedUserId {@code CDEMO-CU02-USR-SELECTED PIC X(08)}, declared at
     *                          {@code app/cbl/COUSR02C.cbl}:58 and handed over by the user-list screen. May be
     *                          {@code null}, empty or blank, all three of which reproduce the arm where the test
     *                          at {@code :99-100} fails and no read is issued
     * @return the assembled screen: blank when no identifier was supplied, otherwise populated with the record's
     *         three readable fields and the hint of {@code :336}; never {@code null}
     * @throws com.cardemo.exception.ValidationException      if the supplied identifier is wider than the
     *                                                        eight-character screen field
     * @throws com.cardemo.exception.RecordNotFoundException  if the identifier is not present, which is the
     *                                                        {@code DFHRESP(NOTFND)} arm at {@code :340-345}
     * @throws com.cardemo.exception.FileAccessException      if the read fails for any other reason, the
     *                                                        {@code WHEN OTHER} arm at {@code :346-352}
     * @throws com.cardemo.exception.FatalProcessingException if the condition is genuinely unexpected
     */
    @Transactional(rollbackFor = Exception.class)
    public UserUpdateScreen openScreen(final String preselectedUserId) {
        return mainPara(true, false, AttentionIdentifier.ENTER, preselectedUserId, null, null);
    }

    /**
     * Looks a user up: the {@code DFHENTER} arm at {@code app/cbl/COUSR02C.cbl}:109-110, which performs
     * {@code PROCESS-ENTER-KEY} - the paragraph that validates the identifier at {@code :146}, blanks the four
     * editable fields at {@code :158-161}, reads the record at {@code :163} and reports its readable fields at
     * {@code :167-170}.
     *
     * <p>{@code userId} is the received map's {@code USRIDINI}. On this arm it is the only field of the map the
     * source reads, and the four editable fields are blanked before the read regardless of what they held, which
     * is why no full screen need be supplied.
     *
     * <p><strong>The stored password is not returned.</strong> {@code :169} moved it onto the screen field; that
     * is the labelled deviation documented on this class, and the returned record declares no
     * password component at all.
     *
     * <p><strong>Side effects.</strong> One keyed read. Nothing is written; the method is transactional because
     * the source's read carries {@code UPDATE}.
     *
     * @param userId {@code USRIDINI PIC X(8)} at {@code app/cpy-bms/COUSR02.CPY}:60. May be {@code null}, empty
     *               or blank, all three of which take the empty-identifier arm at
     *               {@code app/cbl/COUSR02C.cbl}:146 exactly as the source does
     * @return the assembled screen carrying the record's first name, last name and user type, and the hint of
     *         {@code :336}; never {@code null}
     * @throws com.cardemo.exception.ValidationException      if the identifier is empty - the {@code BLANK}
     *                                                        state, message from {@code :148} - or wider than
     *                                                        its eight-character screen field
     * @throws com.cardemo.exception.RecordNotFoundException  if the identifier is not present, message from
     *                                                        {@code :342}
     * @throws com.cardemo.exception.FileAccessException      if the read fails for any other reason, message from
     *                                                        {@code :349}
     * @throws com.cardemo.exception.FatalProcessingException if the condition is genuinely unexpected
     */
    @Transactional(rollbackFor = Exception.class)
    public UserUpdateScreen lookupUser(final String userId) {
        return mainPara(true, true, AttentionIdentifier.ENTER, userId, null, null);
    }

    /**
     * Updates a user: the {@code DFHPF5} arm at {@code app/cbl/COUSR02C.cbl}:122-123, which performs
     * {@code UPDATE-USER-INFO} and stays on the screen. This is the arm the program's own hint at {@code :336}
     * tells the operator to use.
     *
     * <p><strong>The five checks run in this program's order and stop at the first failure</strong> - user
     * identifier, first name, last name, password, user type, from {@code :180}, {@code :186}, {@code :192},
     * {@code :198} and {@code :204}. That order is <strong>identifier-first</strong> and is
     * <strong>not</strong> the sibling add program's, which checks the first name first and the identifier third.
     *
     * <p><strong>Then four change predicates decide whether anything is written at all</strong> - first name at
     * {@code :219}, last name at {@code :223}, password at {@code :227} and user type at {@code :231}. When none
     * of the four differs, the source emits {@code "Please modify to update ..."} at {@code :239} and abandons
     * the write; that outcome is returned as a screen, not raised as an exception, because it is not an error and
     * not a conflict.
     *
     * <p><strong>The password.</strong> Supplying the same password again is not a change: the predicate asks the
     * encoder, so a resubmitted identical credential leaves the stored digest byte-identical. Supplying a
     * different one replaces the digest. Supplying none at all is rejected by the check at {@code :198}, exactly
     * as the source rejects it.
     *
     * <p><strong>Side effects.</strong> One keyed read, then at most one rewrite. On any failure nothing is
     * written: the method is transactional and rolls back for every exception, checked or unchecked.
     *
     * @param request  the submitted screen - the twelve fields of {@code app/cpy-bms/COUSR02.CPY}, whose
     *                 password component is write-only. Must not be {@code null}; every component is treated as
     *                 untrusted, and a value wider than the screen field it transcribes is rejected rather than
     *                 truncated
     * @param expected what the caller was last shown for the three readable fields, for the business-level
     *                 concurrency layer. May be {@code null}, which reproduces the source's own submission,
     *                 which carried no snapshot beyond the screen itself; when supplied and disagreeing with the
     *                 record as freshly read, the write is abandoned
     * @return the assembled screen: on success its message is {@code "User "} plus the trimmed identifier plus
     *         {@code " has been updated ..."} and its message colour is {@code DFHGREEN}; never {@code null}
     * @throws NullPointerException                             if {@code request} is {@code null}
     * @throws com.cardemo.exception.ValidationException        if one of the five fields is empty - the
     *                                                          {@code BLANK} state - or if a field is wider than
     *                                                          its screen field, or if the user type is outside
     *                                                          {@code 'A'} and {@code 'U'} - both the
     *                                                          {@code INVALID} state. The field name is always
     *                                                          carried; a field value never is
     * @throws com.cardemo.exception.ConcurrentUpdateException  if the record moved underneath the caller, with
     *                                                          outcome {@code DATA_CHANGED_BEFORE_UPDATE}
     * @throws com.cardemo.exception.RecordNotFoundException    if the identifier is not present on the read at
     *                                                          {@code :340-345} or on the rewrite at
     *                                                          {@code :377-382}
     * @throws com.cardemo.exception.FileAccessException        if the read fails at {@code :346-352} or the
     *                                                          rewrite at {@code :383-389}
     * @throws com.cardemo.exception.FatalProcessingException   if the condition is genuinely unexpected - for
     *                                                          instance an encoder that is not BCrypt at
     *                                                          strength 10, which the entity refuses
     */
    @Transactional(rollbackFor = Exception.class)
    public UserUpdateScreen updateUser(final UserUpdateRequest request, final UserSnapshot expected) {
        Objects.requireNonNull(request, "request must not be null");
        return mainPara(true, true, AttentionIdentifier.PF5, null, request, expected);
    }

    /**
     * Re-enters the update screen with a submitted screen, which is the pseudo-conversational turn the source
     * takes at {@code app/cbl/COUSR02C.cbl}:106-131: receive the map, then dispatch on {@code EIBAID}.
     *
     * <p>Because the target is stateless, everything the source recovered from the communication area at
     * {@code :94} travels on {@code request} instead. {@code CDEMO-FROM-TRANID}, {@code CDEMO-TO-TRANID},
     * {@code CDEMO-FROM-PROGRAM}, {@code CDEMO-TO-PROGRAM}, {@code CDEMO-PGM-CONTEXT}, {@code CDEMO-LAST-MAP}
     * and {@code CDEMO-LAST-MAPSET} have no counterpart at all; only {@code CDEMO-USER-ID} and
     * {@code CDEMO-USER-TYPE} survive, and the security layer supplies both, not this bean.
     *
     * <p><strong>PF3 SAVES HERE.</strong> {@code :111-112} performs {@code UPDATE-USER-INFO} and only then, at
     * {@code :113-119}, resolves a target program and transfers. The exit-shaped key therefore commits the
     * pending changes before leaving. This is the program's documented quirk, it is deliberately preserved, and
     * the class documentation cites the four independent facts that prove it is anomalous rather than
     * conventional. {@code PF12} at {@code :124-126} is the arm that leaves <strong>without</strong> saving.
     *
     * <p><strong>Side effects by arm.</strong> {@code ENTER} reads. {@code PF3} and {@code PF5} read and may
     * rewrite - {@code PF3} additionally reports a navigation target. {@code PF4} and {@code PF12} touch the file
     * not at all, and neither does an unrecognised key. The method is nonetheless transactional, so that the two
     * arms which write are covered by a single boundary rather than by a conditional one.
     *
     * @param aid      which key the operator pressed. {@code ENTER} looks the record up; {@code PF3}
     *                 <strong>saves and then</strong> returns to the administrative menu; {@code PF4} clears the
     *                 screen; {@code PF5} saves and stays; {@code PF12} returns to the administrative menu
     *                 without saving; anything else is reported as an invalid key. Must not be {@code null}
     * @param request  the submitted screen. Must not be {@code null} when {@code aid} is {@code ENTER},
     *                 {@code PF3} or {@code PF5}, and may be {@code null} on the three arms that read no input
     *                 field
     * @param expected what the caller was last shown, for the business-level concurrency layer; read only on the
     *                 two arms that write, and permitted to be {@code null} as {@code updateUser} describes
     * @return the assembled screen; never {@code null}
     * @throws NullPointerException                            if {@code aid} is {@code null}, or if
     *                                                         {@code request} is {@code null} while {@code aid}
     *                                                         is one of {@code ENTER}, {@code PF3} or
     *                                                         {@code PF5}
     * @throws com.cardemo.exception.ValidationException       on the reading and writing arms, as
     *                                                         {@code updateUser} describes
     * @throws com.cardemo.exception.ConcurrentUpdateException on the writing arms, as {@code updateUser}
     *                                                         describes
     * @throws com.cardemo.exception.RecordNotFoundException   on the reading and writing arms, as
     *                                                         {@code updateUser} describes
     * @throws com.cardemo.exception.FileAccessException       on the reading and writing arms, as
     *                                                         {@code updateUser} describes
     * @throws com.cardemo.exception.FatalProcessingException  on the reading and writing arms, as
     *                                                         {@code updateUser} describes
     */
    @Transactional(rollbackFor = Exception.class)
    public UserUpdateScreen submitScreen(final AttentionIdentifier aid, final UserUpdateRequest request,
            final UserSnapshot expected) {

        Objects.requireNonNull(aid, "aid must not be null");
        if (aid == AttentionIdentifier.ENTER || aid == AttentionIdentifier.PF3
                || aid == AttentionIdentifier.PF5) {
            Objects.requireNonNull(request,
                    "request must not be null when the attention identifier reads or writes the record");
        }
        return mainPara(true, true, aid, null, request, expected);
    }

    // The eleven paragraphs of app/cbl/COUSR02C.cbl, mapped one to one, in source order, and never
    // consolidated - including the paths that are empty, that are unreachable in Java, and that carry a
    // preserved quirk. The label correspondence is the evidence the scope-coverage gate reads, so a
    // twelfth private method that merged two labels, or a tenth that dropped one, would break it.

    /**
     * {@code app/cbl/COUSR02C.cbl}:82 {@code MAIN-PARA} - the entry paragraph, which resets the two flags and
     * the message, tests {@code EIBCALEN}, and then either paints a first display or receives a submitted map
     * and dispatches on {@code EIBAID}.
     *
     * <p><strong>PF3 saves here. This is the preserved quirk.</strong> {@code :111-112} performs
     * {@code UPDATE-USER-INFO} - the same paragraph the conventional save key reaches at {@code :122-123} - and
     * only afterwards, at {@code :113-119}, resolves a target program and transfers away. Both call sites are
     * mapped; the dispatch is not collapsed. Four independent facts establish that this is anomalous rather
     * than conventional, and all four are cited on the class so that a reader cannot mistake it for a defect
     * introduced in translation:
     *
     * <ol>
     *   <li>{@code app/cbl/COUSR02C.cbl}:336 - this program's own on-screen hint reads
     *       {@code "Press PF5 key to save your updates ..."}, naming PF5 and not PF3.</li>
     *   <li>{@code app/cbl/COUSR02C.cbl}:124-126 - {@code WHEN DFHPF12} leaves without saving, so an
     *       exit-without-save arm does exist in this very program.</li>
     *   <li>{@code app/cbl/COUSR03C.cbl}:111-118 - the sibling delete program uses PF3 conventionally, to
     *       exit.</li>
     *   <li>{@code app/cbl/COUSR00C.cbl}:125-127 and {@code app/cbl/COUSR01C.cbl}:93-95 - the list and add
     *       programs also use PF3 conventionally.</li>
     * </ol>
     *
     * <p>It is not removed, not relocated, not gated behind a flag and not turned into a discard-on-exit.
     * Parity is the contract, and this behaviour is deterministic even though it is surprising. It is a
     * preserved legacy quirk.
     *
     * <p><strong>One retained artefact on the PF3 arm.</strong> The guard at {@code :113-118} tests
     * {@code CDEMO-FROM-PROGRAM}, a communication-area field with no counterpart under the stateless mandate,
     * so its {@code ELSE} arm at {@code :116-117} is unreachable in Java and the target is always
     * {@code 'COADM01C'}; that is why only the {@code :114} outcome is expressed. It is commented at its line
     * rather than deleted.
     *
     * <p><strong>A validation failure does not short-circuit the arm.</strong> The source performs
     * {@code :113-119} unconditionally, so a failed {@code UPDATE-USER-INFO} still resolved a target and
     * transferred; the error flag merely suppressed the write. That is reproduced exactly: a failure detected
     * inside a paragraph is recorded on the work area, the flag suppresses everything the source's flag
     * suppresses, the remaining bookkeeping of the arm runs, and the recorded failure surfaces at the source's
     * own return point. Failures raised on the I/O paths are the one exception and their paragraphs explain why.
     *
     * <p>The recovery of the communication area at {@code :94}, and the enter-versus-re-enter flag at
     * {@code :95-96}, have no Java counterpart: the caller's choice of entry point carries the first, and the
     * security layer plus the request body carry the second. Neither is silently dropped; both are commented
     * at their line.
     *
     * @param commAreaPresent {@code false} models {@code EIBCALEN = 0} at {@code :90}
     * @param reenter         {@code false} models {@code NOT CDEMO-PGM-REENTER} at {@code :95}
     * @param aid             the {@code EIBAID} value the {@code EVALUATE} at {@code :108} dispatches on
     * @param selectedUserId  on the first display, {@code CDEMO-CU02-USR-SELECTED} as tested at {@code :99-100};
     *                        on re-entry, the received map's {@code USRIDINI} when the caller supplied only an
     *                        identifier. {@code null} on either path means the field was not populated
     * @param request         the received map, bound by {@code RECEIVE-USRUPD-SCREEN} at {@code :107}
     * @param expected        the caller's snapshot for the business-level concurrency layer
     * @return the assembled screen, which stands in for {@code EXEC CICS RETURN} at {@code :135-138}
     */
    private UserUpdateScreen mainPara(final boolean commAreaPresent, final boolean reenter,
            final AttentionIdentifier aid, final String selectedUserId, final UserUpdateRequest request,
            final UserSnapshot expected) {

        final ScreenWorkArea work = new ScreenWorkArea();
        work.errFlgOn = false;                             // :84 SET ERR-FLG-OFF     TO TRUE
        work.userModified = false;                         // :85 SET USR-MODIFIED-NO TO TRUE
        work.message = SPACES;                             // :87 MOVE SPACES TO WS-MESSAGE
        work.errorMessage = SPACES;                        // :88                       ERRMSGO OF COUSR2AO

        if (!commAreaPresent) {                            // :90 IF EIBCALEN = 0
            work.toProgram = SIGN_ON_PROGRAM;              // :91 MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
            returnToPrevScreen(work);                      // :92 PERFORM RETURN-TO-PREV-SCREEN
            return concludeTurn(work);                     // :135-138 EXEC CICS RETURN
        }

        // :94 MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA - no counterpart. The identity fields the
        // communication area carried are supplied by the security layer; the rest are gone. Tracked.

        if (!reenter) {                                    // :95 IF NOT CDEMO-PGM-REENTER
            // :96 SET CDEMO-PGM-REENTER TO TRUE - no counterpart; the caller's entry point carries it.
            work.clearOutputMap();                         // :97 MOVE LOW-VALUES          TO COUSR2AO
            work.cursorField = CURSOR_FIELD_USER_ID;       // :98 MOVE -1       TO USRIDINL OF COUSR2AI
            if (!isBlankOrUnset(selectedUserId)) {         // :99-100 IF CDEMO-CU02-USR-SELECTED NOT = ...
                work.userId = requireWidth(selectedUserId, // :101-102 MOVE ... TO USRIDINI OF COUSR2AI
                        UserSecurityDto.USER_ID_WIDTH, FIELD_USER_ID);
                processEnterKey(work);                     // :103 PERFORM PROCESS-ENTER-KEY
            }                                              // :104 END-IF
            sendUsrupdScreen(work);                        // :105 PERFORM SEND-USRUPD-SCREEN
            return concludeTurn(work);                     // :135-138 EXEC CICS RETURN
        }

        receiveUsrupdScreen(work, request, selectedUserId); // :107 PERFORM RECEIVE-USRUPD-SCREEN
        switch (aid) {                                     // :108 EVALUATE EIBAID
            case ENTER ->                                  // :109 WHEN DFHENTER
                processEnterKey(work);                     // :110 PERFORM PROCESS-ENTER-KEY
            case PF3 -> {                                  // :111 WHEN DFHPF3 - THE PRESERVED QUIRK
                updateUserInfo(work, expected);            // :112 PERFORM UPDATE-USER-INFO  <-- saves
                // :113 IF CDEMO-FROM-PROGRAM = SPACES OR LOW-VALUES - the from-program has no counterpart,
                // so the test always succeeds and the :116-117 ELSE arm is unreachable here. Tracked.
                work.toProgram = ADMIN_MENU_PROGRAM;       // :114 MOVE 'COADM01C' TO CDEMO-TO-PROGRAM
                returnToPrevScreen(work);                  // :119 PERFORM RETURN-TO-PREV-SCREEN
            }
            case PF4 ->                                    // :120 WHEN DFHPF4
                clearCurrentScreen(work);                  // :121 PERFORM CLEAR-CURRENT-SCREEN
            case PF5 ->                                    // :122 WHEN DFHPF5
                updateUserInfo(work, expected);            // :123 PERFORM UPDATE-USER-INFO
            case PF12 -> {                                 // :124 WHEN DFHPF12 - leaves WITHOUT saving
                work.toProgram = ADMIN_MENU_PROGRAM;       // :125 MOVE 'COADM01C' TO CDEMO-TO-PROGRAM
                returnToPrevScreen(work);                  // :126 PERFORM RETURN-TO-PREV-SCREEN
            }
            case OTHER -> {                                // :127 WHEN OTHER
                work.errFlgOn = true;                      // :128 MOVE 'Y'                  TO WS-ERR-FLG
                work.message = INVALID_KEY_MESSAGE;        // :129 MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE
                sendUsrupdScreen(work);                    // :130 PERFORM SEND-USRUPD-SCREEN
            }
        }                                                  // :131 END-EVALUATE
        return concludeTurn(work);                         // :135-138 EXEC CICS RETURN
    }

    /**
     * {@code app/cbl/COUSR02C.cbl}:143 {@code PROCESS-ENTER-KEY} - validates the identifier, blanks the four
     * editable fields, reads the record and reports its readable fields.
     *
     * <p>Exactly one field is checked here, the identifier, at {@code :146} against
     * {@code SPACES OR LOW-VALUES}; the message at {@code :148} is reproduced byte for byte and the cursor
     * outcome at {@code :150} becomes the field name carried on the raised failure. The
     * {@code WHEN OTHER} arm at {@code :152-154} parks the cursor and then does nothing else, which is why the
     * only thing it contributes here is the cursor field - a retained no-op, commented at its line and tracked
     * at its line rather than deleted to please a linter.
     *
     * <p>{@code :158-161} blanks the first name, last name, password and user type <em>before</em> the read,
     * which is what makes the lookup entry point able to accept nothing but an identifier: whatever those four
     * fields held is discarded at this point in the source too.
     *
     * <p><strong>The stored password is not reported.</strong> {@code :169} moves {@code SEC-USR-PWD} onto the
     * screen field, echoing the stored plaintext credential back to the terminal. That is not reproduced - see
     * the labelled deviation on this class - and no password value, digest or placeholder is written into the
     * work area at all.
     *
     * @param work the method-local work area standing in for the program's working storage
     */
    private void processEnterKey(final ScreenWorkArea work) {
        if (isBlankOrUnset(work.userId)) {                 // :145-146 EVALUATE TRUE / WHEN USRIDINI = ...
            work.errFlgOn = true;                          // :147 MOVE 'Y' TO WS-ERR-FLG
            work.message = USER_ID_REQUIRED_MESSAGE;       // :148 MOVE 'User ID can NOT be empty...'
            work.cursorField = CURSOR_FIELD_USER_ID;       // :150 MOVE -1 TO USRIDINL OF COUSR2AI
            sendUsrupdScreen(work);                        // :151 PERFORM SEND-USRUPD-SCREEN
            retainFailure(work, ValidationException.missingField(FIELD_USER_ID,
                    USER_ID_REQUIRED_MESSAGE));
        } else {                                           // :152 WHEN OTHER
            // A retained no-op beyond parking the cursor; kept, commented and tracked, never deleted.
            work.cursorField = CURSOR_FIELD_USER_ID;       // :153 MOVE -1 TO USRIDINL OF COUSR2AI
                                                           // :154 CONTINUE
        }                                                  // :155 END-EVALUATE
        if (!work.errFlgOn) {                              // :157 IF NOT ERR-FLG-ON
            work.firstName = SPACES;                       // :158 MOVE SPACES TO FNAMEI    OF COUSR2AI
            work.lastName = SPACES;                         // :159                 LNAMEI   OF COUSR2AI
            work.presentedPassword = SPACES;                // :160                 PASSWDI  OF COUSR2AI
            work.userType = SPACES;                         // :161                 USRTYPEI OF COUSR2AI
            work.secUsrId = work.userId;                    // :162 MOVE USRIDINI TO SEC-USR-ID
            readUserSecFile(work);                          // :163 PERFORM READ-USER-SEC-FILE
        }
        if (!work.errFlgOn) {                               // :166 IF NOT ERR-FLG-ON
            work.firstName = work.secUsrFname;              // :167 MOVE SEC-USR-FNAME TO FNAMEI
            work.lastName = work.secUsrLname;               // :168 MOVE SEC-USR-LNAME TO LNAMEI
            // :169 MOVE SEC-USR-PWD TO PASSWDI OF COUSR2AI - DELIBERATELY NOT REPRODUCED. The stored
            // credential is a one-way BCrypt digest and Rule 1 Clause D forbids returning a secret, so no
            // password is written here and the response record declares no password component at all.
            // Labelled deviation, not parity.
            work.userType = work.secUsrType == null ? SPACES
                    : String.valueOf(work.secUsrType.getCode());
                                                            // :170 MOVE SEC-USR-TYPE TO USRTYPEI
            sendUsrupdScreen(work);                         // :171 PERFORM SEND-USRUPD-SCREEN
        }
    }

    /**
     * {@code app/cbl/COUSR02C.cbl}:177 {@code UPDATE-USER-INFO} - five ordered empty-field checks, then a read,
     * then four change predicates, then either the rewrite or the no-change message.
     *
     * <p><strong>The order of the five checks is this program's, and it is not the sibling add program's.</strong>
     * Here it is identifier first: {@code USRIDINI} at {@code :180}, {@code FNAMEI} at {@code :186},
     * {@code LNAMEI} at {@code :192}, {@code PASSWDI} at {@code :198}, {@code USRTYPEI} at {@code :204}.
     * {@code app/cbl/COUSR01C.cbl} checks the first name first at {@code :120} and the identifier third at
     * {@code :132}. The two are deliberately left divergent; harmonising them in either direction would break
     * parity for one of the two programs. Reordering here is classified High.
     *
     * <p>Evaluation stops at the first failure, exactly as the {@code EVALUATE TRUE} does, and each arm carries
     * its own cursor outcome - {@code :184}, {@code :190}, {@code :196}, {@code :202}, {@code :208} - which
     * becomes the field name on the raised failure, because naming the offending field is the stateless
     * equivalent of parking the cursor on it. The five messages are reproduced byte for byte from their lines,
     * including the {@code can NOT} casing and the three-period ellipsis. The checks are written out as ordered
     * statements rather than delegated to bean validation, whose constraint evaluation order is not specified.
     *
     * <p>The {@code WHEN OTHER} arm at {@code :210-212} parks the cursor on the first-name field and then
     * continues; it is retained and commented at its line rather than deleted, because the source reaches it.
     *
     * <p><strong>The four change predicates.</strong> First name at {@code :219}, last name at {@code :223},
     * password at {@code :227}, user type at {@code :231}. Each compares the submitted field against the record
     * as freshly read, applies no case function whatsoever - the source applies none, so none is added - and
     * honours the fixed-width semantics of the {@code X(20)}, {@code X(20)} and {@code X(01)} fields by padding
     * both sides to the declared width before comparing, which is precisely how a COBOL alphanumeric comparison
     * behaves. When none of the four differs, {@code :236-243} takes the {@code ELSE} arm, emits
     * {@code "Please modify to update ..."} in red and abandons the write; that is returned as its own screen
     * outcome and is emphatically not a concurrency conflict and not an error.
     *
     * <p><strong>The password predicate is the trap.</strong> {@code :227} compares plaintext with plaintext,
     * because {@code SEC-USR-PWD} is a plaintext {@code X(08)} field. The store here holds a salted one-way
     * BCrypt digest, so an {@code equals} comparison against it can never be false and the predicate would fire
     * on every single request, reporting a password change even when the operator retyped the same credential -
     * and rewriting the digest each time. The predicate therefore asks the encoder instead. Re-encoding the
     * presented value and comparing the two digests is equally wrong, because BCrypt salts every encoding.
     * Labelled mechanism substitution, not a behaviour change.
     *
     * <p><strong>Two Java-only guards, both labelled.</strong> A field wider than the screen field it
     * transcribes is rejected rather than truncated, because the 3270 map made over-length input physically
     * impossible and silent truncation would corrupt the record. And a user type outside {@code 'A'} and
     * {@code 'U'} is rejected, which the source does not do - it checks only that the field is non-empty. That
     * second guard is forced by the typed enumeration the entity's setter accepts and is a labelled
     * deviation rather than parity.
     *
     * <p><strong>The business-level concurrency layer sits between the read and the predicates.</strong> When
     * the caller supplies what it was last shown and that disagrees with the record as freshly read, the write
     * is abandoned. The source held a record lock from its read to its rewrite - {@code app/csd/CARDDEMO.CSD}
     * defines {@code FILE(USRSEC)} with {@code UPDATEMODEL(LOCKING)} - and the read this method performs takes
     * that same lock through {@link UserSecurityRepository#findByIdForUpdate(String)}. What a stateless surface
     * cannot hold is the part of the span that crossed the terminal conversation, and the snapshot travels on the
     * request to cover exactly that part. No retry, no merge, no re-read and no last-writer-wins: the
     * source abandons the write and so does this.
     *
     * @param work     the method-local work area
     * @param expected what the caller was last shown, or {@code null} to reproduce the source's own submission,
     *                 which carried no snapshot beyond the screen itself
     */
    private void updateUserInfo(final ScreenWorkArea work, final UserSnapshot expected) {
        if (isBlankOrUnset(work.userId)) {                 // :179-180 EVALUATE TRUE / WHEN USRIDINI = ...
            work.errFlgOn = true;                          // :181 MOVE 'Y' TO WS-ERR-FLG
            work.message = USER_ID_REQUIRED_MESSAGE;       // :182 'User ID can NOT be empty...'
            work.cursorField = CURSOR_FIELD_USER_ID;       // :184 MOVE -1 TO USRIDINL OF COUSR2AI
            sendUsrupdScreen(work);                        // :185 PERFORM SEND-USRUPD-SCREEN
            retainFailure(work, ValidationException.missingField(FIELD_USER_ID,
                    USER_ID_REQUIRED_MESSAGE));
        } else if (isBlankOrUnset(work.firstName)) {       // :186 WHEN FNAMEI = SPACES OR LOW-VALUES
            work.errFlgOn = true;                          // :187 MOVE 'Y' TO WS-ERR-FLG
            work.message = FIRST_NAME_REQUIRED_MESSAGE;     // :188 'First Name can NOT be empty...'
            work.cursorField = CURSOR_FIELD_FIRST_NAME;     // :190 MOVE -1 TO FNAMEL OF COUSR2AI
            sendUsrupdScreen(work);                         // :191 PERFORM SEND-USRUPD-SCREEN
            retainFailure(work, ValidationException.missingField(FIELD_FIRST_NAME,
                    FIRST_NAME_REQUIRED_MESSAGE));
        } else if (isBlankOrUnset(work.lastName)) {         // :192 WHEN LNAMEI = SPACES OR LOW-VALUES
            work.errFlgOn = true;                           // :193 MOVE 'Y' TO WS-ERR-FLG
            work.message = LAST_NAME_REQUIRED_MESSAGE;       // :194 'Last Name can NOT be empty...'
            work.cursorField = CURSOR_FIELD_LAST_NAME;       // :196 MOVE -1 TO LNAMEL OF COUSR2AI
            sendUsrupdScreen(work);                          // :197 PERFORM SEND-USRUPD-SCREEN
            retainFailure(work, ValidationException.missingField(FIELD_LAST_NAME,
                    LAST_NAME_REQUIRED_MESSAGE));
        } else if (isBlankOrUnset(work.presentedPassword)) { // :198 WHEN PASSWDI = SPACES OR LOW-VALUES
            work.errFlgOn = true;                            // :199 MOVE 'Y' TO WS-ERR-FLG
            work.message = PASSWORD_REQUIRED_MESSAGE;         // :200 'Password can NOT be empty...'
            work.cursorField = CURSOR_FIELD_PASSWORD;         // :202 MOVE -1 TO PASSWDL OF COUSR2AI
            sendUsrupdScreen(work);                           // :203 PERFORM SEND-USRUPD-SCREEN
            // The failure names the field and never carries the value, so no credential reaches the message.
            retainFailure(work, ValidationException.missingField(FIELD_PASSWORD,
                    PASSWORD_REQUIRED_MESSAGE));
        } else if (isBlankOrUnset(work.userType)) {           // :204 WHEN USRTYPEI = SPACES OR LOW-VALUES
            work.errFlgOn = true;                             // :205 MOVE 'Y' TO WS-ERR-FLG
            work.message = USER_TYPE_REQUIRED_MESSAGE;         // :206 'User Type can NOT be empty...'
            work.cursorField = CURSOR_FIELD_USER_TYPE;         // :208 MOVE -1 TO USRTYPEL OF COUSR2AI
            sendUsrupdScreen(work);                            // :209 PERFORM SEND-USRUPD-SCREEN
            retainFailure(work, ValidationException.missingField(FIELD_USER_TYPE,
                    USER_TYPE_REQUIRED_MESSAGE));
        } else {                                               // :210 WHEN OTHER
            work.cursorField = CURSOR_FIELD_FIRST_NAME;        // :211 MOVE -1 TO FNAMEL OF COUSR2AI
                                                               // :212 CONTINUE - retained no-op.
        }                                                      // :213 END-EVALUATE

        if (!work.errFlgOn) {                                  // :215 IF NOT ERR-FLG-ON
            // Java-only width guard: the 3270 map made over-length input impossible, so reject, never truncate.
            work.userId = requireWidth(work.userId, UserSecurityDto.USER_ID_WIDTH, FIELD_USER_ID);
            work.firstName = requireWidth(work.firstName, UserSecurityDto.NAME_WIDTH, FIELD_FIRST_NAME);
            work.lastName = requireWidth(work.lastName, UserSecurityDto.NAME_WIDTH, FIELD_LAST_NAME);
            work.presentedPassword = requireWidth(work.presentedPassword, PRESENTED_PASSWORD_WIDTH,
                    FIELD_PASSWORD);
            work.userType = requireWidth(work.userType, UserSecurityDto.USER_TYPE_WIDTH, FIELD_USER_TYPE);

            work.secUsrId = work.userId;                       // :216 MOVE USRIDINI TO SEC-USR-ID
            readUserSecFile(work);                             // :217 PERFORM READ-USER-SEC-FILE

            // Business-level concurrency layer: compare what the caller was last shown against the record as
            // freshly read. The source's conversation-long record lock has no stateless counterpart.
            assertSnapshotUnchanged(work, expected);

            final UserType submittedType = resolveUserType(work.userType);

            if (!fixedWidthEquals(work.firstName, work.secUsrFname, UserSecurityDto.NAME_WIDTH)) {
                                                               // :219 IF FNAMEI NOT = SEC-USR-FNAME
                work.secUsrFname = work.firstName;             // :220 MOVE FNAMEI TO SEC-USR-FNAME
                work.userModified = true;                      // :221 SET USR-MODIFIED-YES TO TRUE
            }                                                  // :222 END-IF
            if (!fixedWidthEquals(work.lastName, work.secUsrLname, UserSecurityDto.NAME_WIDTH)) {
                                                               // :223 IF LNAMEI NOT = SEC-USR-LNAME
                work.secUsrLname = work.lastName;              // :224 MOVE LNAMEI TO SEC-USR-LNAME
                work.userModified = true;                      // :225 SET USR-MODIFIED-YES TO TRUE
            }                                                  // :226 END-IF
            if (!this.passwordEncoder.matches(work.presentedPassword, work.passwordHash)) {
                                                               // :227 IF PASSWDI NOT = SEC-USR-PWD
                // matches, never equals: the store holds a salted one-way digest. See the class documentation.
                work.passwordHash = hashPresentedPassword(work.presentedPassword);
                                                               // :228 MOVE PASSWDI TO SEC-USR-PWD
                work.userModified = true;                      // :229 SET USR-MODIFIED-YES TO TRUE
            }                                                  // :230 END-IF
            if (work.secUsrType != submittedType) {            // :231 IF USRTYPEI NOT = SEC-USR-TYPE
                work.secUsrType = submittedType;               // :232 MOVE USRTYPEI TO SEC-USR-TYPE
                work.userModified = true;                      // :233 SET USR-MODIFIED-YES TO TRUE
            }                                                  // :234 END-IF

            if (work.userModified) {                           // :236 IF USR-MODIFIED-YES
                updateUserSecFile(work);                       // :237 PERFORM UPDATE-USER-SEC-FILE
            } else {                                           // :238 ELSE
                work.message = NO_CHANGE_MESSAGE;              // :239 'Please modify to update ...'
                work.messageColour = MESSAGE_COLOUR_RED;       // :241 MOVE DFHRED TO ERRMSGC OF COUSR2AO
                sendUsrupdScreen(work);                        // :242 PERFORM SEND-USRUPD-SCREEN
            }                                                  // :243 END-IF
        }                                                      // :245 END-IF
    }

    /**
     * {@code app/cbl/COUSR02C.cbl}:250 {@code RETURN-TO-PREV-SCREEN} - defaults the target program, stamps four
     * communication-area fields and transfers control with {@code EXEC CICS XCTL}.
     *
     * <p><strong>Every field this paragraph assigns is gone under the stateless mandate, and the label is mapped
     * one to one anyway.</strong> {@code CDEMO-FROM-TRANID} at {@code :255}, {@code CDEMO-FROM-PROGRAM} at
     * {@code :256} and {@code CDEMO-PGM-CONTEXT} at {@code :257} have no counterpart at all, and neither do
     * {@code CDEMO-TO-TRANID}, {@code CDEMO-LAST-MAP} or {@code CDEMO-LAST-MAPSET}: navigation collapses into
     * URL-based routing, and only {@code CDEMO-USER-ID} and {@code CDEMO-USER-TYPE} survive, both supplied by the
     * security layer. What does survive is the default guard at {@code :252-254}, because the resolved target is
     * still worth reporting to the caller as advisory routing information, and the transfer itself, which becomes
     * that advisory value rather than a control transfer.
     *
     * <p>The body is byte-identical across all four {@code COUSR0*C} programs. It is retained rather than
     * deleted because deleting it would break the paragraph correspondence the scope-coverage gate reads;
     * the assignments that have no counterpart are commented at their line.
     *
     * @param work the method-local work area
     */
    private void returnToPrevScreen(final ScreenWorkArea work) {
        if (isBlankOrUnset(work.toProgram)) {              // :252 IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES
            work.toProgram = SIGN_ON_PROGRAM;              // :253 MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
        }                                                  // :254 END-IF
        // :255 MOVE WS-TRANID  TO CDEMO-FROM-TRANID   - no counterpart. Tracked.
        // :256 MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM  - no counterpart. Tracked.
        // :257 MOVE ZEROS      TO CDEMO-PGM-CONTEXT   - no counterpart. Tracked.
        // :258-261 EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA) - the transfer becomes
        // the advisory navigation target reported on the response; no control transfer happens here.
        work.transferRequested = true;
    }

    /**
     * {@code app/cbl/COUSR02C.cbl}:266 {@code SEND-USRUPD-SCREEN} - paints the header, copies the working message
     * onto the map's message field and sends the map with {@code ERASE CURSOR}.
     *
     * <p>In Java this is the response-assembly half of the REST boundary. The header is repainted on every send,
     * exactly as {@code :268} performs {@code POPULATE-HEADER-INFO} unconditionally, so a caller that sees a
     * message also sees a header stamped at the moment of that send. The map name {@code 'COUSR2A'} and the
     * mapset {@code 'COUSR02'} at {@code :273-274} have no counterpart beyond the route itself; the mapset is
     * the one {@code app/csd/CARDDEMO.CSD} defines for this program.
     *
     * <p>The source could send several times in one turn - the read path sends its hint at {@code :339} and the
     * rewrite path then sends its outcome at {@code :376} - and the terminal only ever showed the last one.
     * A single response can carry only one message, so the last send wins here too, which is the same observable
     * result.
     *
     * @param work the method-local work area
     */
    private void sendUsrupdScreen(final ScreenWorkArea work) {
        populateHeaderInfo(work);                          // :268 PERFORM POPULATE-HEADER-INFO
        work.errorMessage = work.message;                  // :270 MOVE WS-MESSAGE TO ERRMSGO OF COUSR2AO
        // :272-278 EXEC CICS SEND MAP('COUSR2A') MAPSET('COUSR02') FROM(COUSR2AO) ERASE CURSOR - the send
        // becomes the assembled response the caller receives; there is no terminal to erase and no cursor to
        // place, so the two options collapse into the cursor field already recorded on the work area.
    }

    /**
     * {@code app/cbl/COUSR02C.cbl}:283 {@code RECEIVE-USRUPD-SCREEN} - receives the map into the input area,
     * capturing the response and reason codes.
     *
     * <p>In Java this is the request-binding half of the REST boundary. The twelve fields of
     * {@code app/cpy-bms/COUSR02.CPY} arrive on the request record and are copied into the work area here, which
     * is the only place they enter it. The six header fields are received and then overwritten by
     * {@code POPULATE-HEADER-INFO} on the way out, exactly as the source overwrites them, so an inbound header
     * value can never influence the response.
     *
     * <p>When the caller supplied only an identifier - the lookup shape - the identifier is bound and the
     * remaining fields stay unset, which faithfully models a map on which the operator typed nothing else. The
     * source blanks those four fields at {@code :158-161} before it reads, so the distinction cannot affect the
     * outcome on that path.
     *
     * <p>{@code RESP(WS-RESP-CD)} and {@code RESP2(WS-REAS-CD)} at {@code :289-290} are captured but never tested
     * on this paragraph in the source, so nothing branches on them here either; they are reset so that a stale
     * value from an earlier operation cannot be read later in the turn.
     *
     * @param work           the method-local work area
     * @param request        the received map, or {@code null} on the paths that receive no input fields
     * @param selectedUserId the single populated field of the received map on the lookup shape, or {@code null}
     */
    private void receiveUsrupdScreen(final ScreenWorkArea work, final UserUpdateRequest request,
            final String selectedUserId) {

        // :285-291 EXEC CICS RECEIVE MAP('COUSR2A') MAPSET('COUSR02') INTO(COUSR2AI) RESP/RESP2
        recordResponse(work, CICS_RESP_NORMAL, IO_STATUS_SUCCESS);
        if (request != null) {
            work.transactionName = request.transactionName();
            work.title01 = request.title01();
            work.currentDate = request.currentDate();
            work.programName = request.programName();
            work.title02 = request.title02();
            work.currentTime = request.currentTime();
            work.userId = request.userId();
            work.firstName = request.firstName();
            work.lastName = request.lastName();
            work.presentedPassword = request.password();
            work.userType = request.userType();
            work.errorMessage = request.errorMessage();
        }
        if (selectedUserId != null) {
            work.userId = selectedUserId;
        }
    }

    /**
     * {@code app/cbl/COUSR02C.cbl}:296 {@code POPULATE-HEADER-INFO} - stamps the two titles, the transaction
     * identifier, the program name, the date and the time onto the output map.
     *
     * <p>The two titles come from {@code app/cpy/COTTL01Y.cpy} as forty-character constants and are reproduced
     * byte for byte, trailing spaces included, because they are screen literals rather than prose.
     *
     * <p><strong>The date is a two-digit year.</strong> {@code :307} moves {@code WS-CURDATE-YEAR(3:2)}, the last
     * two digits, so the assembled value is {@code MM/DD/YY} in eight characters, per
     * {@code app/cpy/CSDAT01Y.cpy}. The time is {@code HH:MM:SS} in eight characters from the same copybook.
     * Note that the specification's field inventory records the time field as nine characters wide; the symbolic
     * map at {@code app/cpy-bms/COUSR02.CPY}:66 declares {@code CURTIMEI PIC X(8)} and {@code CSDAT01Y}
     * assembles eight characters, so the source governs and the wider figure is a citation error.
     *
     * <p>The instant comes from the injected clock rather than from a direct call to the system clock, so that
     * the header is deterministic under test. Both formatters are built with {@code Locale.ROOT}, so a host
     * locale can never reshape the separators or the digits.
     *
     * @param work the method-local work area
     */
    private void populateHeaderInfo(final ScreenWorkArea work) {
        final LocalDateTime now = LocalDateTime.now(this.clock);
                                                           // :298 MOVE FUNCTION CURRENT-DATE TO ...
        work.title01 = SCREEN_TITLE_01;                    // :300 MOVE CCDA-TITLE01 TO TITLE01O
        work.title02 = SCREEN_TITLE_02;                    // :301 MOVE CCDA-TITLE02 TO TITLE02O
        work.transactionName = TRANSACTION_ID;             // :302 MOVE WS-TRANID    TO TRNNAMEO
        work.programName = PROGRAM_NAME;                   // :303 MOVE WS-PGMNAME   TO PGMNAMEO
        // :305 MOVE WS-CURDATE-MONTH     TO WS-CURDATE-MM
        // :306 MOVE WS-CURDATE-DAY       TO WS-CURDATE-DD
        // :307 MOVE WS-CURDATE-YEAR(3:2) TO WS-CURDATE-YY   - the last two digits of the year, hence 'yy'.
        work.currentDate = HEADER_DATE_FORMAT.format(now); // :309 MOVE WS-CURDATE-MM-DD-YY TO CURDATEO
        // :311 MOVE WS-CURTIME-HOURS  TO WS-CURTIME-HH
        // :312 MOVE WS-CURTIME-MINUTE TO WS-CURTIME-MM
        // :313 MOVE WS-CURTIME-SECOND TO WS-CURTIME-SS
        work.currentTime = HEADER_TIME_FORMAT.format(now); // :315 MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO
    }

    /**
     * {@code app/cbl/COUSR02C.cbl}:320 {@code READ-USER-SEC-FILE} - reads the security record for update and
     * branches three ways on the response code.
     *
     * <table border="1">
     *   <caption>The three branches of the {@code EVALUATE} at {@code :333}</caption>
     *   <tr><th>Branch</th><th>Line</th><th>Outcome</th></tr>
     *   <tr><td>{@code DFHRESP(NORMAL)}</td><td>{@code :334-339}</td>
     *       <td>the record's fields are available and the hint
     *       {@code "Press PF5 key to save your updates ..."} is emitted in neutral</td></tr>
     *   <tr><td>{@code DFHRESP(NOTFND)}</td><td>{@code :340-345}</td>
     *       <td>{@code "User ID NOT found..."}, raised as a not-found failure</td></tr>
     *   <tr><td>{@code WHEN OTHER}</td><td>{@code :346-352}</td>
     *       <td>{@code "Unable to lookup User..."}, raised as a file-access failure and escalated to a fatal
     *       failure when the condition is genuinely unexpected</td></tr>
     * </table>
     *
     * <p><strong>A not-found user here is an error.</strong> The three scoped carve-outs in which the corpus
     * treats a not-found status as success are all batch-side - the category-balance upsert in
     * {@code app/cbl/CBTRN02C.cbl}, the first disclosure-group default lookup in {@code app/cbl/CBACT04C.cbl},
     * and the file subprogram's acceptance of its secondary status - and none of them applies to an online keyed
     * read of a user. Importing one would be classified High.
     *
     * <p><strong>The hint names PF5, and that is the quirk evidence.</strong> {@code :336} tells the operator to
     * press PF5 to save, while {@code :111-112} also saves on PF3. The mismatch between the advice and the
     * dispatch is the first of the four facts cited on this class. The literal is reproduced byte for byte,
     * including its three-period ellipsis.
     *
     * <p><strong>{@code :335} is a literal {@code CONTINUE}</strong> standing at the head of the normal branch,
     * ahead of the three statements that follow it. It is a retained no-op, commented at its line and tracked in
     * at its line rather than deleted.
     *
     * <p><strong>The record lock is reproduced, not replaced.</strong> The source's read carries {@code UPDATE}
     * at {@code :328} and {@code app/csd/CARDDEMO.CSD:L88-L89} defines the file with
     * {@code UPDATEMODEL(LOCKING)}, so the source held the row exclusively from the read until the rewrite. This
     * read therefore goes through {@link UserSecurityRepository#findByIdForUpdate(String)}, which issues the
     * same acquisition as a pessimistic write lock, and the enclosing
     * {@code @Transactional(rollbackFor = Exception.class)} method is what releases it - exactly where the
     * source's unit of work released it.
     *
     * <p><strong>Finding, MEDIUM severity, resolved.</strong> This read used the unlocked {@code findById} and
     * the rewrite followed it with nothing in between, so two administrators could each read the same row and
     * the second write would silently discard the first, losing a role change, a name change or a password
     * digest. What the source held across a <em>terminal conversation</em> cannot be held across HTTP requests -
     * that part genuinely has no counterpart - but the conversation's two halves have become one request here,
     * and within that request the lock is available and is now taken. The business-level snapshot comparison of
     * {@code 9700}-style change detection remains the second layer and is unchanged.
     *
     * <p>{@code :347} is a live {@code DISPLAY} of the response and reason codes - unlike the sibling add
     * program, where the equivalent statement is commented out - so it is reproduced as a structured log line.
     * It carries diagnostic codes only, never a field value, so no credential can reach the log through it.
     *
     * @param work the method-local work area
     */
    private void readUserSecFile(final ScreenWorkArea work) {
        Optional<UserSecurity> located = Optional.empty();
        try {
            located = this.userSecurityRepository.findByIdForUpdate(work.secUsrId);
                                                           // :322-331 EXEC CICS READ ... UPDATE RESP/RESP2 -
                                                           // the UPDATE option at :328 is the pessimistic write
                                                           // lock this finder acquires, held to the end of the
                                                           // enclosing transaction.
            if (located.isPresent()) {
                final UserSecurity securityRecord = located.get();
                work.loadedRecord = securityRecord;        // :324 INTO (SEC-USER-DATA)
                work.secUsrId = securityRecord.getSecUsrId();
                work.secUsrFname = securityRecord.getSecUsrFname();
                work.secUsrLname = securityRecord.getSecUsrLname();
                work.passwordHash = securityRecord.getPasswordHash();
                work.secUsrType = securityRecord.getSecUsrType();
                recordResponse(work, CICS_RESP_NORMAL, IO_STATUS_SUCCESS);
            } else {
                recordResponse(work, CICS_RESP_NOTFND, IO_STATUS_RECORD_NOT_FOUND);
            }
        } catch (final DataAccessException cause) {
            recordResponse(work, CICS_RESP_IOERR, IO_STATUS_IO_ERROR);
            work.ioFailureCause = cause;
        }

        switch (work.responseCode) {                        // :333 EVALUATE WS-RESP-CD
            case CICS_RESP_NORMAL -> {                      // :334 WHEN DFHRESP(NORMAL)
                                                            // :335 CONTINUE - retained no-op.
                work.message = SAVE_HINT_MESSAGE;           // :336 'Press PF5 key to save your updates ...'
                work.messageColour = MESSAGE_COLOUR_NEUTRAL; // :338 MOVE DFHNEUTR TO ERRMSGC OF COUSR2AO
                sendUsrupdScreen(work);                     // :339 PERFORM SEND-USRUPD-SCREEN
            }
            case CICS_RESP_NOTFND -> {                      // :340 WHEN DFHRESP(NOTFND)
                work.errFlgOn = true;                       // :341 MOVE 'Y' TO WS-ERR-FLG
                work.message = USER_NOT_FOUND_MESSAGE;      // :342 'User ID NOT found...'
                work.cursorField = CURSOR_FIELD_USER_ID;    // :344 MOVE -1 TO USRIDINL OF COUSR2AI
                sendUsrupdScreen(work);                     // :345 PERFORM SEND-USRUPD-SCREEN
                final RecordNotFoundException absent = new RecordNotFoundException(
                        USER_NOT_FOUND_MESSAGE, USRSEC_FILE, work.secUsrId);
                retainFailure(work, absent);
                throw absent;
            }
            default -> {                                    // :346 WHEN OTHER
                LOG.error("RESP:{} REAS:{}", work.responseCode, work.reasonCode);
                                                            // :347 DISPLAY 'RESP:' ... 'REAS:' ...
                work.errFlgOn = true;                       // :348 MOVE 'Y' TO WS-ERR-FLG
                work.message = UNABLE_TO_LOOKUP_MESSAGE;    // :349 'Unable to lookup User...'
                work.cursorField = CURSOR_FIELD_FIRST_NAME; // :351 MOVE -1 TO FNAMEL OF COUSR2AI
                sendUsrupdScreen(work);                     // :352 PERFORM SEND-USRUPD-SCREEN
                final CardDemoException failure = classify(work, READ_OPERATION, UNABLE_TO_LOOKUP_MESSAGE);
                retainFailure(work, failure);
                throw failure;
            }
        }                                                   // :353 END-EVALUATE
    }

    /**
     * {@code app/cbl/COUSR02C.cbl}:358 {@code UPDATE-USER-SEC-FILE} - rewrites the security record and branches
     * three ways on the response code.
     *
     * <table border="1">
     *   <caption>The three branches of the {@code EVALUATE} at {@code :368}</caption>
     *   <tr><th>Branch</th><th>Line</th><th>Outcome</th></tr>
     *   <tr><td>{@code DFHRESP(NORMAL)}</td><td>{@code :369-376}</td>
     *       <td>{@code "User "} plus the identifier plus {@code " has been updated ..."}, in green</td></tr>
     *   <tr><td>{@code DFHRESP(NOTFND)}</td><td>{@code :377-382}</td>
     *       <td>{@code "User ID NOT found..."}, raised as a not-found failure</td></tr>
     *   <tr><td>{@code WHEN OTHER}</td><td>{@code :383-389}</td>
     *       <td>{@code "Unable to Update User..."}, raised as a file-access failure</td></tr>
     * </table>
     *
     * <p><strong>The {@code DFHRESP(NOTFND)} branch is retained but cannot be reached through this
     * translation.</strong> {@code EXEC CICS REWRITE} could report a vanished record as a response code, whereas
     * a flush cannot: a row that has gone surfaces as a data-access failure and is recorded as the {@code '90'}
     * status, so control lands in the {@code WHEN OTHER} arm instead. The branch is mapped anyway because
     * deleting it would break the branch-for-branch correspondence the scope-coverage gate reads, and because
     * the response-code switch is the shape of the source. It is therefore an intentionally retained parity
     * artefact rather than abandoned code, and it is expected to show as uncovered in the coverage report.
     *
     * <p><strong>The verb in the failure literal at {@code :386} is correct here.</strong> This is an update
     * program and the message reports an update failure. The sibling delete program carries the very same
     * literal on a delete-failure path at {@code app/cbl/COUSR03C.cbl}:332, where the verb is wrong. Neither is
     * altered: the one here because it is right, the one there because parity is the contract. The contrast is
     * recorded so that a reader comparing the two files does not harmonise them.
     *
     * <p>The success message is assembled exactly as the {@code STRING} at {@code :372-375} assembles it, with
     * the identifier delimited by its first space, so a shorter identifier does not carry the field's trailing
     * padding into the sentence.
     *
     * <p><strong>The secondary store-level layer lives here.</strong> A stale-state failure raised while
     * flushing becomes the same {@code DATA_CHANGED_BEFORE_UPDATE} outcome the business-level comparison raises,
     * but it carries the provider's exception as its cause, which is what keeps the two distinguishable. The
     * entity deliberately declares no version column - {@code V1__create_schema.sql} gives
     * {@code user_security} exactly five, {@code ddl-auto: validate} makes that mandatory and the migration set
     * is fixed at three members - so this layer is reached through the provider's stale-state detection rather
     * than through a version counter. It is the <em>secondary</em> guard: the primary one is the pessimistic
     * write lock the read took, which is why a concurrent update can no longer interleave with this rewrite at
     * all. What remains for this layer is the case a lock cannot cover, a row deleted by a transaction that
     * committed before this one read.
     *
     * <p>No retry, no merge, no re-read and no last-writer-wins. The source abandons the write on any failure
     * and so does this; because the whole read-compare-rewrite sequence runs inside one declarative transaction
     * that rolls back for every exception, nothing partial can survive, and that is achieved by scoping rather
     * than by conditional logic.
     *
     * @param work the method-local work area
     */
    private void updateUserSecFile(final ScreenWorkArea work) {
        try {
            final UserSecurity securityRecord = work.loadedRecord;
            securityRecord.setSecUsrFname(work.secUsrFname); // :362 FROM (SEC-USER-DATA) - the record area
            securityRecord.setSecUsrLname(work.secUsrLname); //       carries the values moved at :220-232
            securityRecord.setPasswordHash(work.passwordHash);
            securityRecord.setSecUsrType(work.secUsrType);
            this.userSecurityRepository.saveAndFlush(securityRecord);
                                                             // :360-366 EXEC CICS REWRITE ... RESP/RESP2
            recordResponse(work, CICS_RESP_NORMAL, IO_STATUS_SUCCESS);
        } catch (final OptimisticLockException | OptimisticLockingFailureException cause) {
            // Store-level layer of the two-layer design. Same outcome as the business-level comparison, but it
            // carries the provider's failure as its cause, which is what tells the two apart.
            final ConcurrentUpdateException conflict = new ConcurrentUpdateException(
                    ConcurrentUpdateException.Outcome.DATA_CHANGED_BEFORE_UPDATE,
                    ConcurrentUpdateException.Outcome.DATA_CHANGED_BEFORE_UPDATE.getLegacyMessage(),
                    USRSEC_FILE, cause);
            retainFailure(work, conflict);
            throw conflict;
        } catch (final DataAccessException cause) {
            recordResponse(work, CICS_RESP_IOERR, IO_STATUS_IO_ERROR);
            work.ioFailureCause = cause;
        }

        switch (work.responseCode) {                          // :368 EVALUATE WS-RESP-CD
            case CICS_RESP_NORMAL -> {                        // :369 WHEN DFHRESP(NORMAL)
                work.message = SPACES;                        // :370 MOVE SPACES   TO WS-MESSAGE
                work.messageColour = MESSAGE_COLOUR_GREEN;    // :371 MOVE DFHGREEN TO ERRMSGC OF COUSR2AO
                work.message = UPDATED_MESSAGE_PREFIX         // :372 STRING 'User '    DELIMITED BY SIZE
                        + delimitedBySpace(work.secUsrId)     // :373     SEC-USR-ID     DELIMITED BY SPACE
                        + UPDATED_MESSAGE_SUFFIX;             // :374 ' has been updated ...' DELIMITED BY SIZE
                                                              // :375   INTO WS-MESSAGE
                sendUsrupdScreen(work);                       // :376 PERFORM SEND-USRUPD-SCREEN
            }
            case CICS_RESP_NOTFND -> {                        // :377 WHEN DFHRESP(NOTFND)
                work.errFlgOn = true;                         // :378 MOVE 'Y' TO WS-ERR-FLG
                work.message = USER_NOT_FOUND_MESSAGE;        // :379 'User ID NOT found...'
                work.cursorField = CURSOR_FIELD_USER_ID;      // :381 MOVE -1 TO USRIDINL OF COUSR2AI
                sendUsrupdScreen(work);                       // :382 PERFORM SEND-USRUPD-SCREEN
                final RecordNotFoundException absent = new RecordNotFoundException(
                        USER_NOT_FOUND_MESSAGE, USRSEC_FILE, work.secUsrId);
                retainFailure(work, absent);
                throw absent;
            }
            default -> {                                      // :383 WHEN OTHER
                LOG.error("RESP:{} REAS:{}", work.responseCode, work.reasonCode);
                                                              // :384 DISPLAY 'RESP:' ... 'REAS:' ...
                work.errFlgOn = true;                         // :385 MOVE 'Y' TO WS-ERR-FLG
                work.message = UNABLE_TO_UPDATE_MESSAGE;      // :386 'Unable to Update User...'
                work.cursorField = CURSOR_FIELD_FIRST_NAME;   // :388 MOVE -1 TO FNAMEL OF COUSR2AI
                sendUsrupdScreen(work);                       // :389 PERFORM SEND-USRUPD-SCREEN
                final CardDemoException failure = classify(work, REWRITE_OPERATION,
                        UNABLE_TO_UPDATE_MESSAGE);
                retainFailure(work, failure);
                throw failure;
            }
        }                                                     // :390 END-EVALUATE
    }

    /**
     * {@code app/cbl/COUSR02C.cbl}:395 {@code CLEAR-CURRENT-SCREEN} - clears every field and repaints the screen.
     *
     * <p>Reached from the {@code DFHPF4} arm at {@code :120-121}. There is no terminal to clear, so in Java the
     * pair of statements produces a fresh, empty response and nothing more. <strong>No cleared state is retained
     * anywhere</strong>: the work area is created per call and discarded when the call returns, so a subsequent
     * caller can never observe what this one cleared. It is kept because the paragraph
     * correspondence is the evidence the scope-coverage gate reads.
     *
     * @param work the method-local work area
     */
    private void clearCurrentScreen(final ScreenWorkArea work) {
        initializeAllFields(work);                            // :397 PERFORM INITIALIZE-ALL-FIELDS.
        sendUsrupdScreen(work);                               // :398 PERFORM SEND-USRUPD-SCREEN.
    }

    /**
     * {@code app/cbl/COUSR02C.cbl}:403 {@code INITIALIZE-ALL-FIELDS} - parks the cursor on the identifier field
     * and blanks the five editable fields and the message.
     *
     * <p>The blanking at {@code :409} covers the password field, so this paragraph also discards whatever
     * credential the submitted map carried. That is honoured here: the presented password is overwritten along
     * with everything else, which means a cleared turn cannot carry a credential into the assembled response.
     *
     * @param work the method-local work area
     */
    private void initializeAllFields(final ScreenWorkArea work) {
        work.cursorField = CURSOR_FIELD_USER_ID;              // :405 MOVE -1     TO USRIDINL OF COUSR2AI
        work.userId = SPACES;                                 // :406 MOVE SPACES TO USRIDINI OF COUSR2AI
        work.firstName = SPACES;                              // :407                 FNAMEI   OF COUSR2AI
        work.lastName = SPACES;                               // :408                 LNAMEI   OF COUSR2AI
        work.presentedPassword = SPACES;                      // :409                 PASSWDI  OF COUSR2AI
        work.userType = SPACES;                               // :410                 USRTYPEI OF COUSR2AI
        work.message = SPACES;                                // :411                 WS-MESSAGE.
    }

    // Mechanism helpers. None of these corresponds to a source paragraph: each stands in for a COBOL
    // language or CICS mechanism that has no paragraph of its own - a fixed-width comparison, a
    // response-code capture, an intrinsic phrase, a typed status translation. They are helpers rather
    // than a separate class on purpose: Rule 1 Clause C fixes this package at four files, and the
    // change-detection comparison in particular must live inside this bean.

    /**
     * Concludes the turn at the source's own return point, {@code app/cbl/COUSR02C.cbl}:135-138
     * {@code EXEC CICS RETURN}.
     *
     * <p>The source cannot throw. Each error arm sets {@code WS-ERR-FLG}, records a message, parks the cursor and
     * sends the screen, and the flag then suppresses the work that follows - which is why the arms record their
     * failure rather than short-circuit. The recorded failure surfaces here, at the point the source returns to
     * the terminal, so every statement the source genuinely executes after the failure has executed.
     *
     * @param work the method-local work area
     * @return the assembled screen when the turn succeeded
     * @throws com.cardemo.exception.CardDemoException the first failure recorded during the turn, if any
     */
    private static UserUpdateScreen concludeTurn(final ScreenWorkArea work) {
        if (work.retainedFailure != null) {
            throw work.retainedFailure;
        }
        return buildScreen(work);
    }

    /**
     * Assembles the response from the work area, standing in for the map the source sends.
     *
     * <p><strong>No password is copied, in either direction.</strong> Neither the presented value nor the stored
     * digest has a component on the response record to be copied into, which is what makes the omission of
     * {@code app/cbl/COUSR02C.cbl}:169 structural rather than a matter of remembering to null a field.
     *
     * @param work the method-local work area
     * @return the assembled screen; never {@code null}
     */
    private static UserUpdateScreen buildScreen(final ScreenWorkArea work) {
        return new UserUpdateScreen(work.transactionName, work.title01, work.currentDate, work.programName,
                work.title02, work.currentTime, work.userId, work.firstName, work.lastName, work.userType,
                work.errorMessage, work.messageColour, work.cursorField, work.toProgram,
                work.transferRequested, work.userModified);
    }

    /**
     * Records the outcome of an I/O attempt, standing in for {@code RESP(WS-RESP-CD)} and
     * {@code RESP2(WS-REAS-CD)} on the {@code EXEC CICS} commands at {@code app/cbl/COUSR02C.cbl}:329-330 and
     * {@code :364-365}.
     *
     * <p>{@code WS-REAS-CD}, declared at {@code :44}, receives the secondary reason code, which has no
     * counterpart outside CICS. It is reported as zero so that the two live {@code DISPLAY} statements at
     * {@code :347} and {@code :384} keep their shape, and it is never inferred from anything else.
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
     * Hashes the presented password, standing in for {@code MOVE PASSWDI TO SEC-USR-PWD} at
     * {@code app/cbl/COUSR02C.cbl}:228.
     *
     * <p>The source moved the eight-character plaintext straight into {@code SEC-USR-PWD}. Storing a recoverable
     * credential is not reproducible under Rule 1 Clause D, so the digest replaces the plaintext at the exact
     * statement the source moved it. The encoder is BCrypt at strength ten, owned by {@code SecurityConfig} and
     * consumed here; it is never redeclared. The plaintext's lifetime is this call and the parameter that
     * reached it, and neither the plaintext nor the digest is ever logged or returned.
     *
     * <p>Called only when the change predicate at {@code :227} has already established that the presented value
     * does not match the stored digest, so an unchanged credential never produces a fresh hash and never
     * rewrites the column.
     *
     * @param presentedPassword the value of {@code PASSWDI PIC X(8)}, already proven non-blank at {@code :198}
     * @return the sixty-character BCrypt digest to persist
     * @throws FatalProcessingException if the encoder returns nothing to store, which no correctly configured
     *                                  encoder does and which must not be allowed to persist an empty credential
     */
    private String hashPresentedPassword(final String presentedPassword) {
        final String digest = this.passwordEncoder.encode(presentedPassword);
        if (digest == null || digest.isEmpty()) {
            throw new FatalProcessingException(String.valueOf(FatalProcessingException.BATCH_ABEND_CODE),
                    PROGRAM_NAME, "PASSWORD ENCODER RETURNED NO DIGEST",
                    FatalProcessingException.DEFAULT_ABEND_MESSAGE);
        }
        return digest;
    }

    /**
     * Maps the single character of {@code USRTYPEI} onto the typed user type, standing in for
     * {@code MOVE USRTYPEI TO SEC-USR-TYPE} at {@code app/cbl/COUSR02C.cbl}:232.
     *
     * <p><strong>Labelled deviation, not parity.</strong> The source checks
     * only that the field is non-empty, at {@code :204}. It never checks membership of {@code 'A'} or
     * {@code 'U'}, so the {@code MOVE} would write any character at all into the one-byte
     * {@code SEC-USR-TYPE}, and the resulting user would match neither {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'}
     * nor {@code 88 CDEMO-USRTYP-USER VALUE 'U'} of {@code app/cpy/COCOM01Y.cpy}:27-28 - authorising nothing
     * while occupying the identifier. The target's user type is a typed enumeration, so an unmappable character
     * has nowhere to go; widening the column to a raw character instead would propagate the defect into the
     * schema. The same decision is taken by the sibling add service, so the two remain consistent.
     *
     * <p>The rejection carries the {@code INVALID} state, distinct from the {@code BLANK} state the five
     * emptiness checks raise, so a caller can tell "you gave me nothing" from "you gave me something I cannot
     * use". The message names the field and never the value: echoing unvalidated input is how a message becomes
     * an injection vector.
     *
     * <p><strong>Where the deviation is published.</strong> Labelling it here is necessary and was not
     * sufficient: a caller reads the contract, not the source. It is therefore also published in
     * {@code docs/api-contracts.md} at the heading <em>Labelled deviation: userType is restricted to A and
     * U</em>, referenced from both the add-user and the update-user operations, together with the two facts
     * that make removing the guard pointless as well as unwise - the schema already declares
     * {@code CONSTRAINT ck_user_security_type CHECK (sec_usr_type IN ('A', 'U'))} from those same two
     * condition names, so relaxing this check would convert a {@code 400} that names the field into a
     * {@code 409} that names nothing; and the seeded population is five {@code 'A'} rows and five
     * {@code 'U'} rows [{@code app/jcl/DUSRSECJ.jcl}], so no legacy behaviour depends on a third value being
     * storable.
     *
     * <p>The blank check is untouched and still runs FIRST, so a caller who omits the field is told it is
     * empty rather than told about the domain. That ordering is the source's.
     *
     * @param userType the value of {@code USRTYPEI PIC X(1)}, already proven non-blank at {@code :204}
     * @return the mapped user type
     * @throws ValidationException if the character is neither {@code 'A'} nor {@code 'U'}
     */
    private static UserType resolveUserType(final String userType) {
        return UserType.fromCode(userType).orElseThrow(() -> ValidationException.invalidField(
                FIELD_USER_TYPE, "User Type must be A for an administrator or U for a regular user"));
    }

    /**
     * Compares two alphanumeric fields the way COBOL compares them, for the change predicates at
     * {@code app/cbl/COUSR02C.cbl}:219, {@code :223} and {@code :231}.
     *
     * <p>A COBOL comparison of two alphanumeric operands pads the shorter with spaces before comparing, so
     * {@code 'JOHN'} and {@code 'JOHN'} followed by sixteen spaces are equal in an {@code X(20)} field. Both
     * sides are therefore padded to the declared width here. An unset operand is treated as all spaces, which is
     * what {@code LOW-VALUES} and {@code SPACES} both amount to for this purpose.
     *
     * <p><strong>No case function is applied.</strong> The source applies none to these three fields - unlike the
     * account update program, which deliberately lower-cases one group of fields and upper-cases another - so
     * adding one here would change which submissions register as a change.
     *
     * @param left  one operand, permitted to be {@code null}
     * @param right the other operand, permitted to be {@code null}
     * @param width the declared width of the field being compared
     * @return {@code true} when the two are equal under fixed-width semantics
     */
    private static boolean fixedWidthEquals(final String left, final String right, final int width) {
        return padToWidth(left, width).equals(padToWidth(right, width));
    }

    /**
     * Right-pads a value with spaces to a declared width, which is how a value occupies a fixed-width COBOL
     * field.
     *
     * <p>A value already at or beyond the width is returned unchanged rather than truncated, so that an
     * over-length value compares as different rather than silently matching a shorter one. Over-length input is
     * rejected before it reaches a comparison in any case, by {@link #requireWidth}.
     *
     * @param value the value, permitted to be {@code null}, which is treated as an empty field
     * @param width the declared width
     * @return the value padded to the width; never {@code null}
     */
    private static String padToWidth(final String value, final int width) {
        final String text = value == null ? SPACES : value;
        if (text.length() >= width) {
            return text;
        }
        return text + " ".repeat(width - text.length());
    }

    /**
     * The business-level layer of the two-layer optimistic concurrency design: compares what the caller was last
     * shown against the record as freshly read, and abandons the write when they disagree.
     *
     * <p><strong>Why a version column is not enough.</strong> {@code app/csd/CARDDEMO.CSD} defines
     * {@code FILE(USRSEC)} with {@code UPDATEMODEL(LOCKING)}, so the source held the row from the read at
     * {@code app/cbl/COUSR02C.cbl}:322-331 through the rewrite at {@code :360-366} - across a terminal
     * conversation. A stateless surface cannot hold a lock across requests. A version counter detects
     * <em>that</em> a row changed; this comparison detects <em>which field values</em> differ, which is a
     * different guarantee: a concurrent write that restored a field to its original value passes this check and
     * fails a version check. Neither layer substitutes for the other, which is why both are present.
     *
     * <p>Three fields are compared, and they are exactly the three the caller can have been shown: the first
     * name, the last name and the user type. The password is not among them and cannot be - the caller was never
     * shown it, because the echo at {@code :169} is deliberately not reproduced - so a concurrent password change
     * is caught by the store-level layer alone. That limitation is disclosed rather than papered over.
     *
     * <p>Passing no snapshot reproduces the source's own submission, which carried nothing beyond the screen
     * itself, and leaves the store-level layer as the only guard. <strong>No retry, no merge, no re-read, no
     * last-writer-wins.</strong> The source abandons the write and so does this.
     *
     * @param work     the method-local work area, already populated by the read
     * @param expected what the caller was last shown, or {@code null} to skip the business-level layer
     * @throws ConcurrentUpdateException with outcome {@code DATA_CHANGED_BEFORE_UPDATE} and no cause, which is
     *                                   what distinguishes it from the store-level layer's conflict
     */
    private static void assertSnapshotUnchanged(final ScreenWorkArea work, final UserSnapshot expected) {
        if (expected == null) {
            return;
        }
        final String storedType = work.secUsrType == null ? SPACES : String.valueOf(work.secUsrType.getCode());
        final boolean unchanged =
                fixedWidthEquals(expected.firstName(), work.secUsrFname, UserSecurityDto.NAME_WIDTH)
                && fixedWidthEquals(expected.lastName(), work.secUsrLname, UserSecurityDto.NAME_WIDTH)
                && fixedWidthEquals(expected.userType(), storedType, UserSecurityDto.USER_TYPE_WIDTH);
        if (!unchanged) {
            final ConcurrentUpdateException conflict = new ConcurrentUpdateException(
                    ConcurrentUpdateException.Outcome.DATA_CHANGED_BEFORE_UPDATE,
                    ConcurrentUpdateException.Outcome.DATA_CHANGED_BEFORE_UPDATE.getLegacyMessage(),
                    USRSEC_FILE, null);
            retainFailure(work, conflict);
            throw conflict;
        }
    }

    /**
     * Translates a recorded file status into the typed exception that stands for it, by delegating to the
     * constructor-injected status mapper.
     *
     * <p>The mapping is never reimplemented here and the status is never re-rendered: the twenty-character
     * literal {@code FILE STATUS IS: NNNN} belongs to the file-status enumeration, and the mapper is the one
     * component that owns the translation - including the escalation to a fatal failure for a status it considers
     * genuinely unexpected. This method supplies only the context the mapper needs, the logical file and the
     * operation, and hands over the cause so the root cause survives.
     *
     * <p>The mapper declines to produce an exception for a status it does not consider a failure. That cannot
     * arise from the two call sites, which reach this method only on a {@code WHEN OTHER} arm, but a translation
     * that silently returned nothing would swallow the failure, so the decline becomes a file-access failure
     * carrying the source's own message rather than being left to become {@code null}.
     *
     * <p>The recorded cause is consumed - cleared as it is read - so it cannot be attached twice.
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
        // The mapper owns the SUBTYPE; the source owns the MESSAGE. The composed diagnostic message names
        // the operation, the file and the expanded status, and it is not the caption the program latches on
        // its failure arms - which is compared byte for byte as observable contract. The status, the file and
        // the operation still travel in the exception's structured fields; only the message changes. The same
        // correction is applied on the delete and add paths, which shared this defect.
        final Optional<CardDemoException> mapped = this.fileStatusMapper.toExceptionWithLegacyMessage(
                work.ioStatus, USRSEC_FILE, operation, cause, fallbackMessage);
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
     * {@code app/cbl/COUSR02C.cbl}:145-155 and {@code :179-213}, each of which takes exactly one arm. A later arm
     * cannot overwrite an earlier one. Because both structures take one arm only, no single turn records twice,
     * so the overwrite-suppressing branch shows as uncovered by design; it is kept because it is what makes
     * "first failure wins" a property of the code rather than of the call order.
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
     * Reproduces {@code DELIMITED BY SPACE} for the {@code STRING} at {@code app/cbl/COUSR02C.cbl}:372-375.
     *
     * <p>{@code SEC-USR-ID} is an {@code X(08)} field, so a shorter identifier is space-padded.
     * {@code DELIMITED BY SPACE} stops the transfer at the first space, which drops that padding, while the two
     * surrounding literals are {@code DELIMITED BY SIZE} and contribute in full. Truncating at the first space -
     * not trimming both ends - is what the phrase means, and it is what this method does.
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
     * {@code app/cbl/COUSR02C.cbl}:146, {@code :180}, {@code :186}, {@code :192}, {@code :198}, {@code :204} and
     * {@code :252}.
     *
     * <p>Both figurative constants are covered, and so is the {@code null} a JSON body can deliver where a
     * fixed-width field could only ever have been blank. {@code SPACES} is any run of whitespace;
     * {@code LOW-VALUES} is a run of binary zeros, which arrive as ISO control characters, so a field of those is
     * treated as unset too rather than as content. A field is unset when every character is one or the other.
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
     * <p>The 3270 map made over-length input physically impossible: a field declared {@code PIC X(20)} in
     * {@code app/cpy-bms/COUSR02.CPY} could not receive a twenty-first character. A JSON body has no such limit,
     * and the record it lands in is still the eighty-byte layout of {@code app/cpy/CSUSR01Y.cpy}, so an
     * over-length value has to be refused. <strong>It is refused, never truncated</strong>: silent truncation
     * would write a corrupted name or identifier and no comparison would ever reveal it.
     *
     * <p>The rejection carries the {@code INVALID} state and names the field, never the value.
     *
     * <p>The width is a count of Unicode code points rather than of {@code char} values, and the unit is
     * load-bearing. A {@code PIC X(n)} clause declares n character positions and the {@code CHAR(n)} column
     * it maps to pads to n characters, while a Java {@code String} measures itself in UTF-16 code units; the
     * two disagree for any supplementary-plane character. Counting code units let a value that had been
     * accepted, stored and padded fail when it was read back and offered here again. A code point count never
     * exceeds a code unit count, so this is the same bound the write path applies rather than a looser one.
     * Held as {@code DL-MS-05} in {@code DECISION_LOG.md}.
     *
     * @param value     the submitted value, permitted to be {@code null}
     * @param maxLength the declared width of the field
     * @param fieldName the field's name, for the rejection
     * @return the value unchanged when it fits
     * @throws ValidationException when the value is wider than the field
     */
    private static String requireWidth(final String value, final int maxLength, final String fieldName) {
        if (value == null) {
            return null;
        }
        final int characterPositions = value.codePointCount(0, value.length());
        if (characterPositions > maxLength) {
            throw ValidationException.invalidField(fieldName,
                    fieldName + " must be at most " + maxLength + " characters");
        }
        return value;
    }

    // Nested types. Declared here rather than as files of their own because Rule 1 Clause C fixes this
    // package at four source files; none of the three public types is referenced outside this service
    // and its controller, and the work area is an implementation detail of a single turn.

    /**
     * The attention identifier the operator pressed, standing in for {@code EIBAID} as the
     * {@code EVALUATE} at {@code app/cbl/COUSR02C.cbl}:108 tests it.
     *
     * <p>Exactly the five keys the source recognises are named, plus the catch-all its {@code WHEN OTHER} arm
     * covers. <strong>{@code PF3} saves before it leaves</strong>, which is this program's documented quirk and is
     * preserved; {@code PF12} is the arm that leaves without saving. The distinction is deliberately visible in
     * this enumeration's documentation rather than buried in the dispatch, because a caller choosing between the
     * two is choosing between committing and discarding.
     */
    public enum AttentionIdentifier {

        /** {@code DFHENTER} at {@code app/cbl/COUSR02C.cbl}:109 - looks the record up. Writes nothing. */
        ENTER,

        /**
         * {@code DFHPF3} at {@code app/cbl/COUSR02C.cbl}:111 - <strong>saves, then returns to the
         * administrative menu</strong>. Conventionally an exit key everywhere else in the corpus; here
         * {@code :112} performs the update before {@code :119} navigates away. Preserved deliberately.
         */
        PF3,

        /** {@code DFHPF4} at {@code app/cbl/COUSR02C.cbl}:120 - clears the screen. Writes nothing. */
        PF4,

        /**
         * {@code DFHPF5} at {@code app/cbl/COUSR02C.cbl}:122 - saves and stays. The key the program's own hint
         * at {@code :336} names.
         */
        PF5,

        /**
         * {@code DFHPF12} at {@code app/cbl/COUSR02C.cbl}:124 - returns to the administrative menu
         * <strong>without</strong> saving.
         */
        PF12,

        /**
         * The {@code WHEN OTHER} arm at {@code app/cbl/COUSR02C.cbl}:127 - reports
         * {@code "Invalid key pressed. Please see below..."} and writes nothing.
         */
        OTHER
    }

    /**
     * What the caller was last shown, for the business-level layer of the two-layer optimistic concurrency
     * design.
     *
     * <p>The source needed no such type: it held the row locked from the read at
     * {@code app/cbl/COUSR02C.cbl}:322-331 through the rewrite at {@code :360-366}, because
     * {@code app/csd/CARDDEMO.CSD} defines {@code FILE(USRSEC)} with {@code UPDATEMODEL(LOCKING)}. A stateless
     * surface cannot hold a lock across requests, so the snapshot travels on the request instead.
     *
     * <p><strong>There is deliberately no password component.</strong> The caller was never shown the stored
     * credential, because the echo at {@code :169} is not reproduced, so it cannot meaningfully assert what that
     * credential was - and a snapshot that carried one would be a secret travelling on a request for no benefit.
     * A concurrent password change is therefore caught by the store-level layer alone, which is disclosed rather
     * than hidden.
     *
     * @param firstName what the caller was shown for {@code SEC-USR-FNAME PIC X(20)}, compared at {@code :219}
     * @param lastName  what the caller was shown for {@code SEC-USR-LNAME PIC X(20)}, compared at {@code :223}
     * @param userType  what the caller was shown for {@code SEC-USR-TYPE PIC X(01)}, compared at {@code :231}
     */
    public record UserSnapshot(String firstName, String lastName, String userType) {
    }

    /**
     * The assembled screen, standing in for the output map {@code COUSR2AO} that
     * {@code app/cbl/COUSR02C.cbl}:266-278 sends.
     *
     * <p>The eleven readable fields of {@code app/cpy-bms/COUSR02.CPY} are carried at their source widths, with
     * the message colour, the cursor field and the resolved navigation target added because a stateless response
     * has to report what a 3270 attribute byte and an {@code XCTL} conveyed in-band.
     *
     * <p><strong>There is no password component, by construction.</strong> {@code :169} moved the stored
     * credential onto the screen field; that is the labelled deviation this class documents, and it
     * is honoured by the record's shape rather than by remembering to blank a field. Nothing here can carry a
     * credential, a digest, a placeholder that resembles one, or a masked-but-present value.
     *
     * @param transactionName   {@code TRNNAMEO PIC X(4)}, stamped at {@code :302}
     * @param title01           {@code TITLE01O PIC X(40)}, stamped at {@code :300}
     * @param currentDate       {@code CURDATEO PIC X(8)} as {@code MM/DD/YY}, stamped at {@code :309}
     * @param programName       {@code PGMNAMEO PIC X(8)}, stamped at {@code :303}
     * @param title02           {@code TITLE02O PIC X(40)}, stamped at {@code :301}
     * @param currentTime       {@code CURTIMEO PIC X(8)} as {@code HH:MM:SS}, stamped at {@code :315}
     * @param userId            {@code USRIDINI PIC X(8)}
     * @param firstName         {@code FNAMEI PIC X(20)}
     * @param lastName          {@code LNAMEI PIC X(20)}
     * @param userType          {@code USRTYPEI PIC X(1)}
     * @param errorMessage      {@code ERRMSGO PIC X(78)}, the working message copied at {@code :270}
     * @param messageColour     the attribute moved to {@code ERRMSGC} - {@code DFHGREEN} at {@code :371},
     *                          {@code DFHRED} at {@code :241}, {@code DFHNEUTR} at {@code :338}
     * @param cursorField       the field the source parked the cursor on, which is how it named the field at
     *                          fault; {@code null} when no field was singled out
     * @param navigationTarget  the program {@code CDEMO-TO-PROGRAM} resolved to at {@code :252-253},
     *                          {@code :114} or {@code :125}; advisory only, since routing is URL-based
     * @param transferRequested whether the turn reached {@code RETURN-TO-PREV-SCREEN} at {@code :250}
     * @param userModified      whether {@code WS-USR-MODIFIED} was set at {@code :221}, {@code :225},
     *                          {@code :229} or {@code :233}, which is what decided the write at {@code :236}
     */
    public record UserUpdateScreen(String transactionName, String title01, String currentDate,
            String programName, String title02, String currentTime, String userId, String firstName,
            String lastName, String userType, String errorMessage, String messageColour, String cursorField,
            String navigationTarget, boolean transferRequested, boolean userModified) {
    }

    /**
     * The working storage of one turn, standing in for the {@code WORKING-STORAGE SECTION} at
     * {@code app/cbl/COUSR02C.cbl}:35-47 together with the two map areas and the record area.
     *
     * <p><strong>It is created per call and never held on the bean.</strong> That is not a style preference: a
     * bean field holding {@code WS-MESSAGE} would be a concurrency defect, and a bean field holding the presented
     * password would be a concurrency defect <em>and</em> a secret-hygiene violation at once. The bean itself is
     * stateless and thread-safe because everything mutable lives here, for the lifetime of a single call.
     *
     * <p>The presented password is the one field on this type that is sensitive. It is never logged, never copied
     * into the assembled screen, never placed in an exception message and never written to the store as given -
     * only its digest is - and {@code toString} is deliberately not overridden to render it.
     */
    private static final class ScreenWorkArea {
        /**
         * Creates the work area with every member at its post-{@code INITIALIZE} value, which is the state
         * the legacy {@code WORKING-STORAGE SECTION} begins each task in. Declared explicitly rather than
         * left implicit so the surface is documented; it takes no argument and performs no work.
         */
        private ScreenWorkArea() {
            // Every member carries its initial value in its own declaration above, exactly as a COBOL
            // VALUE clause does, so there is nothing for this constructor to assign.
        }

        /** {@code WS-ERR-FLG PIC X(01)} at {@code :40}, with its two condition names at {@code :41-42}. */
        private boolean errFlgOn;

        /** {@code WS-USR-MODIFIED PIC X(01)} at {@code :45}, with its condition names at {@code :46-47}. */
        private boolean userModified;

        /** {@code WS-MESSAGE PIC X(80)} at {@code :38}. */
        private String message = SPACES;

        /** {@code WS-RESP-CD PIC S9(09) COMP} at {@code :43}, receiving {@code RESP}. */
        private int responseCode;

        /** {@code WS-REAS-CD PIC S9(09) COMP} at {@code :44}, receiving {@code RESP2}. */
        private int reasonCode;

        /** The file status the recorded response maps to, for the typed translation. */
        private String ioStatus = IO_STATUS_SUCCESS;

        /** {@code TRNNAMEI} and {@code TRNNAMEO}. */
        private String transactionName;

        /** {@code TITLE01I} and {@code TITLE01O}. */
        private String title01;

        /** {@code CURDATEI} and {@code CURDATEO}. */
        private String currentDate;

        /** {@code PGMNAMEI} and {@code PGMNAMEO}. */
        private String programName;

        /** {@code TITLE02I} and {@code TITLE02O}. */
        private String title02;

        /** {@code CURTIMEI} and {@code CURTIMEO}. */
        private String currentTime;

        /** {@code USRIDINI PIC X(8)}. */
        private String userId;

        /** {@code FNAMEI PIC X(20)}. */
        private String firstName;

        /** {@code LNAMEI PIC X(20)}. */
        private String lastName;

        /**
         * {@code PASSWDI PIC X(8)}. Sensitive: it reaches the encoder and nothing else, and it is blanked by
         * {@code INITIALIZE-ALL-FIELDS} at {@code :409} exactly as the source blanks it.
         */
        private String presentedPassword;

        /** {@code USRTYPEI PIC X(1)}. */
        private String userType;

        /** {@code ERRMSGI} and {@code ERRMSGO PIC X(78)}. */
        private String errorMessage;

        /** {@code ERRMSGC}, the attribute byte carrying the message colour. */
        private String messageColour;

        /** The field the source parked the cursor on with {@code MOVE -1 TO <field>L}. */
        private String cursorField;

        /** {@code SEC-USR-ID X(08)} at {@code app/cpy/CSUSR01Y.cpy}:17, the eight-byte key. */
        private String secUsrId;

        /** {@code SEC-USR-FNAME X(20)} at {@code app/cpy/CSUSR01Y.cpy}:18. */
        private String secUsrFname;

        /** {@code SEC-USR-LNAME X(20)} at {@code app/cpy/CSUSR01Y.cpy}:19. */
        private String secUsrLname;

        /**
         * The stored credential, {@code SEC-USR-PWD X(08)} at {@code app/cpy/CSUSR01Y.cpy}:21 - widened to a
         * sixty-character BCrypt digest. Sensitive: it is compared through the encoder and never rendered.
         */
        private String passwordHash;

        /** {@code SEC-USR-TYPE X(01)} at {@code app/cpy/CSUSR01Y.cpy}:22, as the typed enumeration. */
        private UserType secUsrType;

        /** The row the read loaded, which the rewrite at {@code :360-366} writes back. */
        private UserSecurity loadedRecord;

        /** {@code CDEMO-TO-PROGRAM X(08)}, the only communication-area routing field with any residue. */
        private String toProgram;

        /** Whether the turn reached {@code RETURN-TO-PREV-SCREEN} at {@code :250}. */
        private boolean transferRequested;

        /** The first typed failure of the turn, surfaced at the source's return point by the conclusion step. */
        private CardDemoException retainedFailure;

        /** The underlying throwable behind a recorded I/O failure, consumed by the typed translation. */
        private Throwable ioFailureCause;

        /**
         * Reproduces {@code MOVE LOW-VALUES TO COUSR2AO} at {@code app/cbl/COUSR02C.cbl}:97.
         *
         * <p>Every screen field is cleared, including the message and its colour, so a first display cannot
         * inherit anything. This work area holds one field per logical field rather than a separate input and
         * output area, so clearing the output map clears them all - which is strictly safer than the source, whose
         * statement targets {@code COUSR2AO} alone and leaves the corresponding input field standing.
         *
         * <p>The record area is left alone, exactly as the source leaves it: {@code SEC-USER-DATA} is not a map.
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
            this.presentedPassword = SPACES;
            this.userType = SPACES;
            this.errorMessage = SPACES;
            this.messageColour = SPACES;
        }
    }
}
