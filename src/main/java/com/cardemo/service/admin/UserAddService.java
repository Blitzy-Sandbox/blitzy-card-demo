/*
 * ****************************************************************************
 * Component   : UserAddService
 * Application : CardDemo
 * Type        : Spring @Service (admin, user add)
 * Function    : Add a new Regular/Admin user to USRSEC file
 * Source      : app/cbl/COUSR01C.cbl (299 lines, 9 paragraphs) @ 7756d89
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.UserCreateRequest;
import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.service.shared.FileStatusMapper;

/**
 * The user-add screen: five operator fields validated in one fixed order, then a single keyed insert into the
 * {@code USRSEC} security file. This is the Java replacement for {@code app/cbl/COUSR01C.cbl} (299 lines, 9
 * paragraphs), the CICS program behind transaction {@code CU01} - {@code DEFINE TRANSACTION(CU01)} at
 * {@code app/csd/CARDDEMO.CSD}:459-460 naming {@code PROGRAM(COUSR01C)}, whose own definition is at
 * {@code :285} - painting mapset {@code COUSR01} ({@code :161}) whose generated symbolic map is
 * {@code app/cpy-bms/COUSR01.CPY}.
 *
 * <p>It is the <strong>first write path</strong> in {@code com.cardemo.service.admin} and the primary
 * consumer of {@code com.cardemo.exception.DuplicateRecordException}: {@code app/cbl/COUSR01C.cbl}:260 is the
 * canonical {@code DFHRESP(DUPKEY)} site of the whole corpus, the other two being
 * {@code app/cbl/COTRN02C.cbl}:735 and {@code app/cbl/COBIL00C.cbl}:533.
 *
 * <h2>What it does</h2>
 *
 * <p>Three things. It validates five presented fields in the source's order and stops at the first one that
 * is empty, reporting that field and the source's exact message. It BCrypt-hashes the presented password and
 * inserts one 80-byte security record. It reports the three outcomes the source reports - added, identifier
 * already taken, or the write failed - with the source's exact message text in each case.
 *
 * <p>It is surfaced over HTTP by {@code com.cardemo.controller.AdminController} beneath
 * {@code /api/admin/*}, which {@code com.cardemo.config.SecurityConfig} restricts to the ADMIN role - the
 * {@code 'A'} against {@code 'U'} distinction of {@code CDEMO-USER-TYPE} at
 * {@code app/cpy/COCOM01Y.cpy}:27-28, surfaced as {@code com.cardemo.model.enums.UserType}.
 *
 * <p>It deliberately does <em>not</em> list, update or delete a user, does not authenticate, does not seed
 * users - {@code src/main/resources/db/migration/V3__seed_data.sql} owns the ten rows that
 * {@code app/jcl/DUSRSECJ.jcl}:35-44 supplies inline - and does not configure security, persistence, HTTP or
 * metrics. It publishes no password encoder, no transaction manager and no HTTP status mapping; it consumes
 * them.
 *
 * <h2>How to build and test</h2>
 *
 * <p>Java 25 ({@code maven.compiler.release} 25, no preview features) and Maven 3.9.11, under parent
 * {@code spring-boot-starter-parent:3.5.11}, with the toolchain floor asserted by
 * {@code maven-enforcer-plugin:3.5.0}.
 *
 * <ul>
 *   <li>{@code ./mvnw -B -ntp clean compile} - compiles this file. {@code maven-compiler-plugin:3.14.1} runs
 *       {@code -Xlint:all -Werror} with {@code failOnWarning}, so any warning at all fails the build, an
 *       unused import fails the build, and malformed Javadoc fails JDK 25 doclint.</li>
 *   <li>{@code ./mvnw -B -ntp test} - runs the unit tier through {@code maven-surefire-plugin:3.5.4}. This
 *       bean's tests belong in {@code src/test/java/com/cardemo/unit/**} and never in this package, which
 *       holds exactly four source files and no {@code package-info.java}.</li>
 *   <li>{@code ./mvnw -B -ntp verify} - adds {@code maven-failsafe-plugin:3.5.4} and the
 *       {@code jacoco-maven-plugin:0.8.12} check, which enforces 80% LINE coverage with no exclusion for
 *       this package. Constructor injection and the complete absence of bean-held state are what make that
 *       reachable: every method below is exercisable by handing the constructor a fixed {@code Clock}, a
 *       stubbed repository and a real {@code BCryptPasswordEncoder}, with no database and no Spring
 *       context.</li>
 * </ul>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li><strong>BCrypt strength 10</strong> - the {@code PasswordEncoder} bean is owned by
 *       {@code com.cardemo.config.SecurityConfig} and injected here. This class never constructs an encoder,
 *       never names a strength and carries neither {@code @EnableWebSecurity} nor
 *       {@code @EnableTransactionManagement}. Strength 10 is nonetheless a hard contract downstream:
 *       {@code com.cardemo.model.entity.UserSecurity} accepts only a digest whose cost factor is 10 and
 *       rejects anything else, so an encoder configured at another strength fails every add rather than
 *       silently persisting an off-contract digest.</li>
 *   <li><strong>Record layout</strong> - {@code app/cpy/CSUSR01Y.cpy}:17-23, exactly 80 bytes:
 *       {@code SEC-USR-ID PIC X(08)}, {@code SEC-USR-FNAME PIC X(20)}, {@code SEC-USR-LNAME PIC X(20)},
 *       {@code SEC-USR-PWD PIC X(08)}, {@code SEC-USR-TYPE PIC X(01)} and {@code SEC-USR-FILLER PIC X(23)}.
 *       The eight-character plaintext password field becomes a 60-character BCrypt digest column; the filler
 *       is not modelled.</li>
 *   <li><strong>Key length 8</strong> - from {@code KEYS(8,0)} with {@code RECORDSIZE(80,80) REUSE INDEXED}
 *       on {@code DEFINE CLUSTER(NAME(AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS)...)} at
 *       {@code app/jcl/DUSRSECJ.jcl}:65-66, matching {@code KEYLENGTH(LENGTH OF SEC-USR-ID)} on the write at
 *       {@code app/cbl/COUSR01C.cbl}:245.</li>
 *   <li><strong>The five-check order</strong> - first name, last name, user identifier, password, user type,
 *       from {@code app/cbl/COUSR01C.cbl}:118, :124, :130, :136 and :142. It is a
 *       <strong>parity contract, not a preference</strong>: it decides which message a request with several
 *       empty fields receives.</li>
 *   <li><strong>Screen field widths</strong> - taken from {@code app/cpy-bms/COUSR01.CPY} through the
 *       constants of {@code com.cardemo.model.dto.UserSecurityDto} rather than restated here, so the field
 *       contract has one owner: {@code TRNNAMEI PIC X(4)}:24, {@code TITLE01I PIC X(40)}:30,
 *       {@code CURDATEI PIC X(8)}:36, {@code PGMNAMEI PIC X(8)}:42, {@code TITLE02I PIC X(40)}:48,
 *       {@code CURTIMEI PIC X(8)}:54, {@code FNAMEI PIC X(20)}:60, {@code LNAMEI PIC X(20)}:66,
 *       {@code USERIDI PIC X(8)}:72, {@code PASSWDI PIC X(8)}:78, {@code USRTYPEI PIC X(1)}:84 and
 *       {@code ERRMSGI PIC X(78)}:90.</li>
 *   <li><strong>Clock</strong> - the header date and time are read from an injected {@code java.time.Clock},
 *       standing in for {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at
 *       {@code app/cbl/COUSR01C.cbl}:216. Nothing here calls {@code LocalDateTime.now()} with no clock, so
 *       the rendering is deterministic and testable. The formats are {@code MM/DD/YY} and {@code HH:MM:SS},
 *       both eight characters, from {@code app/cpy/CSDAT01Y.cpy}, and both applied with
 *       {@code Locale.ROOT}.</li>
 *   <li><strong>Route</strong> - ADMIN only. Least privilege is enforced by the security configuration, not
 *       here; this bean neither reads a token nor inspects a role.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>A request with several empty fields reports the wrong field.</strong> The five checks were
 *       reordered, or their ordering was delegated to {@code jakarta.validation}, whose constraint evaluation
 *       order is unspecified. Remedy: keep the explicit ordered chain of {@code processEnterKey} exactly as
 *       written and never annotate the five fields into a declarative cascade. Severity:
 *       <strong>High</strong>.</li>
 *   <li><strong>A message no longer matches the baseline.</strong> A literal was normalised. All five empty
 *       field messages read {@code can NOT} with a capital N, O and T, and every ellipsis in this program is
 *       exactly three periods. Remedy: never re-case, re-space or re-punctuate them; the parity gates compare
 *       them byte for byte.</li>
 *   <li><strong>{@code "User ID already exist..."} was corrected to "exists".</strong> The missing {@code s}
 *       is a grammatical defect in the system of record at {@code app/cbl/COUSR01C.cbl}:263 and is reproduced
 *       deliberately. Remedy: revert the correction. Severity: <strong>Low</strong> as a defect,
 *       <strong>High</strong> as a parity break.</li>
 *   <li><strong>Two distinct duplicate outcomes appear.</strong> {@code DFHRESP(DUPKEY)} at
 *       {@code app/cbl/COUSR01C.cbl}:260 and {@code DFHRESP(DUPREC)} at {@code :261} are adjacent
 *       {@code WHEN} clauses sharing one body, so they are one outcome, not two. Remedy: keep the single
 *       branch. Severity: <strong>Medium</strong>.</li>
 *   <li><strong>A duplicate identifier eventually succeeds.</strong> Something retried. There is no retry, no
 *       backoff, no identifier regeneration, no {@code @Retryable}, no upsert and no database sequence: a
 *       collision is terminal and reportable, exactly as the source reports it. Remedy: remove the retry.
 *       Severity: <strong>High</strong>.</li>
 *   <li><strong>A password or digest appears in a log line, a response or an exception message.</strong> It
 *       cannot come from here. The plaintext is read once, hashed immediately, and never assigned to a field,
 *       never logged, never returned and never placed in a message; {@code UserAddScreen} declares no
 *       password component at all; and the {@code ValidationException} raised for an empty password carries
 *       the field <em>name</em> only. Remedy: keep all four properties true, and treat the masking rules of
 *       {@code logback-spring.xml} as a backstop rather than a licence. Severity:
 *       <strong>High</strong>.</li>
 *   <li><strong>The commented-out diagnostic at {@code app/cbl/COUSR01C.cbl}:268 was enabled.</strong>
 *       Enabling it changes observable output. Remedy: leave it commented, as it is below. Severity:
 *       <strong>Low</strong>.</li>
 *   <li><strong>Startup fails naming {@code PasswordEncoder} or {@code Clock}.</strong> Both are constructor
 *       arguments and neither is published here. Remedy: publish the encoder from
 *       {@code com.cardemo.config.SecurityConfig} at strength 10 and a {@code Clock} bean for the tree.</li>
 *   <li><strong>Every add fails complaining about the digest shape.</strong> The injected encoder is not
 *       BCrypt at strength 10. Remedy: fix the encoder bean; do not widen the entity's contract.</li>
 *   <li><strong>A partially written user survives a failure.</strong> The write is not inside the
 *       transaction. Remedy: keep {@code @Transactional(rollbackFor = Exception.class)} on the entry points
 *       that write.</li>
 * </ul>
 *
 * <h2>Findings carried from the translation, by severity</h2>
 *
 * <h3>Blocker</h3>
 *
 * <p>Mapping fewer than nine private methods. All nine paragraph labels of {@code app/cbl/COUSR01C.cbl} are
 * present one-to-one and none is consolidated: {@code MAIN-PARA}:71, {@code PROCESS-ENTER-KEY}:115,
 * {@code RETURN-TO-PREV-SCREEN}:165, {@code SEND-USRADD-SCREEN}:184, {@code RECEIVE-USRADD-SCREEN}:201,
 * {@code POPULATE-HEADER-INFO}:214, {@code WRITE-USER-SEC-FILE}:238, {@code CLEAR-CURRENT-SCREEN}:279 and
 * {@code INITIALIZE-ALL-FIELDS}:287. The source-citing Javadoc on each is the evidence the scope-coverage
 * gate reads, and {@code TRACEABILITY_MATRIX.md} is proved against exactly this correspondence.
 *
 * <h3>High</h3>
 *
 * <p>Four ways to break parity, all avoided above and all listed under troubleshooting: reordering the five
 * validations; returning, logging or storing the presented password or its digest; placing the password value
 * in an exception message; and retrying or upserting on a duplicate key. A fifth belongs here too - importing
 * one of the three batch-side "record not found is success" carve-outs, namely the {@code TCATBAL} upsert at
 * {@code app/cbl/CBTRN02C.cbl}:481, the first {@code DISCGRP} {@code DEFAULT} fallback at
 * {@code app/cbl/CBACT04C.cbl}:422 and {@code :436}, and {@code CBSTM03B}'s acceptance of {@code '04'}. All
 * three are scoped to batch; none of {@code FileStatusMapper}'s carve-out methods is called from this file.
 *
 * <h3>Medium</h3>
 *
 * <p>Treating the two duplicate conditions as separate branches; relying on {@code jakarta.validation}
 * ordering for the five checks. Both avoided.
 *
 * <p>One Medium finding is <strong>not</strong> avoided and is disclosed instead: this bean rejects a user
 * type outside {@code 'A'} and {@code 'U'}, which the source does not do. See the labelled deviation below.
 *
 * <h3>Low</h3>
 *
 * <ul>
 *   <li>The {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} diagnostic on the {@code WHEN OTHER} arm
 *       of the write is commented out at {@code app/cbl/COUSR01C.cbl}:268. It stays commented out here.</li>
 *   <li>{@code MOVE WS-USER-ID TO CDEMO-USER-ID} and {@code MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE} are
 *       commented out at {@code app/cbl/COUSR01C.cbl}:172-173. That is the only textual occurrence of
 *       {@code CDEMO-USER-ID} in any of the four {@code COUSR0*C} programs, and it is inert - part of the
 *       evidence that no self-delete guard exists anywhere in this package. Both stay commented out
 *       here.</li>
 *   <li>{@code "User ID already exist..."} is grammatically wrong in the source and is reproduced as
 *       written.</li>
 *   <li>The plan's general note on the symbolic maps records {@code CURTIME} as {@code X(9)}. Both
 *       {@code app/cpy-bms/COUSR01.CPY}:54 and {@code app/cpy/CSDAT01Y.cpy} say eight. The source governs, so
 *       eight is used. Citation correction only.</li>
 * </ul>
 *
 * <h2>Labelled deviation: the user type is validated against its domain</h2>
 *
 * <p>{@code MOVE USRTYPEI OF COUSR1AI TO SEC-USR-TYPE} at {@code app/cbl/COUSR01C.cbl}:158 is a plain
 * alphanumeric move. The source checks only that the field is non-empty, at {@code :142}, and would happily
 * store {@code 'Z'}. The target cannot: {@code SEC-USR-TYPE} is modelled as
 * {@code com.cardemo.model.enums.UserType}, whose domain is closed to the two codes
 * {@code app/cpy/COCOM01Y.cpy}:27-28 defines, and the entity refuses a null type.
 *
 * <p>An unmappable code is therefore rejected with a {@code ValidationException} in the
 * <em>supplied-but-invalid</em> state rather than persisted. This is a deviation, stated as one: it rejects
 * input the source accepts. It is the narrower of the two available deviations - the alternative is to widen
 * the column back to a free-form character, which would let a meaningless type reach the authorisation model
 * that {@code SecurityConfig} builds on {@code 'A'} against {@code 'U'}. Severity: Medium; tracked in
 * DECISION_LOG.md.
 *
 * <h2>Labelled mechanism note: the presented password is a separate argument</h2>
 *
 * <p>{@code com.cardemo.model.dto.UserCreateRequest} carries all twelve fields of
 * {@code app/cpy-bms/COUSR01.CPY} but publishes a read path for only eleven: the credential is write-only by
 * deliberate design, with no accessor of any visibility, so that no caller, serializer or reflective bean
 * mapper can read it back out. That design is load-bearing and is not weakened here.
 *
 * <p>The presented password is consequently handed to this bean as an <em>explicit separate argument</em>
 * alongside the request. The request supplies the eleven readable presented fields; the credential travels on
 * its own, is read exactly once, is hashed immediately, and is never stored on this bean, never assigned to a
 * field of the work area, and never returned. Tracked in DECISION_LOG.md.
 *
 * <h2>Labelled mechanism note: the declarative transaction boundary</h2>
 *
 * <p>The source has no explicit unit-of-work statement in this program: {@code EXEC CICS WRITE} at
 * {@code app/cbl/COUSR01C.cbl}:240-248 is a single record insert that CICS commits with the task, and there
 * is no {@code SYNCPOINT} and no {@code SYNCPOINT ROLLBACK} anywhere in the 299 lines. Scoping the write in
 * one {@code @Transactional(rollbackFor = Exception.class)} method reproduces the same all-or-nothing outcome
 * without any conditional logic. This is a <strong>mechanism substitution, not a behaviour change</strong>,
 * and transaction management itself is owned by {@code com.cardemo.config.JpaConfig}. Tracked in
 * DECISION_LOG.md.
 *
 * <h2>Preserve the contrast with the sibling update program</h2>
 *
 * <p>{@code PF3} here is conventional: {@code app/cbl/COUSR01C.cbl}:93-95 moves {@code 'COADM01C'} into
 * {@code CDEMO-TO-PROGRAM} and transfers, so it <strong>exits without saving</strong>. The sibling
 * {@code app/cbl/COUSR02C.cbl}:111-112 instead performs its update paragraph on {@code DFHPF3}, so
 * {@code PF3} <strong>saves</strong> there. The two behaviours are genuinely different and the conventional
 * one here is part of the evidence that the sibling's is the quirk. A reviewer comparing the two must
 * <em>not</em> harmonise them.
 *
 * <h2>Not available</h2>
 *
 * <ul>
 *   <li><strong>Latency and throughput objectives.</strong> Not available. No service level is published
 *       anywhere in the source, and none is invented; the performance gate records a measured baseline, never
 *       a target. What would be needed is a stated objective that does not exist.</li>
 *   <li><strong>Password strength, complexity, minimum length, expiry, reuse, history and lockout
 *       policy.</strong> Not available. {@code app/cbl/COUSR01C.cbl}:136 tests emptiness and nothing else,
 *       and {@code SEC-USR-PWD} is a bare {@code PIC X(08)}. No rule is invented here. What would be needed
 *       is a source rule that does not exist.</li>
 *   <li><strong>An audit trail for user creation.</strong> Not available. The source writes one record and
 *       records nothing about who wrote it - indeed the two moves that would have carried the acting user's
 *       identity are commented out at {@code :172-173}. None is invented.</li>
 *   <li><strong>A literal {@code FILE STATUS '22'} test in the corpus.</strong> Not available: a census of
 *       {@code app/cbl} finds zero of them, so {@code '22'} is reachable only through the batch guard's
 *       {@code ELSE} branch. It is cited as evidence, not as a source locator.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Immutable and stateless, therefore safe for the shared singleton it is. Its four collaborators are
 * assigned once in the constructor and never reassigned; there is no static mutable field; and every
 * working-storage item of the source - {@code WS-ERR-FLG}, {@code WS-MESSAGE}, {@code WS-RESP-CD},
 * {@code WS-REAS-CD} and the whole of {@code SEC-USER-DATA} - lives in a per-invocation
 * {@code ScreenWorkArea} rather than on the bean. That is a correctness requirement twice over: a shared
 * message field would interleave between concurrent requests, and a shared credential field would be a
 * disclosure.
 */
@Service
public class UserAddService {

    /**
     * Diagnostic sink. Used only for the coarse-grained outcome of an add, and only ever handed the
     * eight-character identifier - never a name, never the presented password, never a digest and never any
     * other component of the record.
     */
    private static final Logger LOG = LoggerFactory.getLogger(UserAddService.class);

    /**
     * {@code WS-PGMNAME PIC X(08) VALUE 'COUSR01C'}, {@code app/cbl/COUSR01C.cbl}:36. Rendered onto
     * {@code PGMNAMEO} by {@code POPULATE-HEADER-INFO} at {@code :221}.
     */
    private static final String PROGRAM_NAME = "COUSR01C";

    /**
     * {@code WS-TRANID PIC X(04) VALUE 'CU01'}, {@code app/cbl/COUSR01C.cbl}:37. The CICS transaction
     * identifier of {@code DEFINE TRANSACTION(CU01) ... PROGRAM(COUSR01C)} at
     * {@code app/csd/CARDDEMO.CSD}:459-460, rendered onto {@code TRNNAMEO} at {@code :220} and carried on
     * {@code EXEC CICS RETURN TRANSID(WS-TRANID)} at {@code :108}.
     */
    private static final String TRANSACTION_ID = "CU01";

    /**
     * {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '}, {@code app/cbl/COUSR01C.cbl}:39, the
     * {@code DATASET} operand of the write at {@code :241}. Held trimmed because it is used as an identity in
     * diagnostics and in typed failures, never as a fixed-width field.
     */
    private static final String USRSEC_FILE = "USRSEC";

    /**
     * Operation label for {@code EXEC CICS WRITE}, {@code app/cbl/COUSR01C.cbl}:240. Passed to
     * {@code FileStatusMapper} so that a failure names the operation which produced it.
     */
    private static final String WRITE_OPERATION = "WRITE";

    /**
     * {@code MOVE 'COADM01C' TO CDEMO-TO-PROGRAM}, the administrative menu {@code PF3} returns to at
     * {@code app/cbl/COUSR01C.cbl}:94. Reported as advisory navigation; nothing is invoked.
     */
    private static final String ADMIN_MENU_PROGRAM = "COADM01C";

    /**
     * {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM}, the sign-on program the source falls back to at
     * {@code app/cbl/COUSR01C.cbl}:79 when it is entered with no communication area, and again at
     * {@code :168} when no target was set. Reported as advisory navigation.
     */
    private static final String SIGN_ON_PROGRAM = "COSGN00C";

    /**
     * {@code CCDA-TITLE01 PIC X(40)} from {@code app/cpy/COTTL01Y.cpy}, moved onto {@code TITLE01O} at
     * {@code app/cbl/COUSR01C.cbl}:218. Reproduced with its exact leading and trailing blanks, because the
     * literal is what the screen displayed.
     */
    private static final String SCREEN_TITLE_01 = "      AWS Mainframe Modernization       ";

    /**
     * {@code CCDA-TITLE02 PIC X(40)} from {@code app/cpy/COTTL01Y.cpy}, moved onto {@code TITLE02O} at
     * {@code app/cbl/COUSR01C.cbl}:219. The copybook carries a commented-out alternative reading
     * {@code '  Credit Card Demo Application (CCDA)   '}; the active literal is the one below.
     */
    private static final String SCREEN_TITLE_02 = "              CardDemo                  ";

    /**
     * {@code CCDA-MSG-INVALID-KEY PIC X(50)} from {@code app/cpy/CSMSG01Y.cpy}, moved into
     * {@code WS-MESSAGE} on the {@code WHEN OTHER} arm of the key dispatch at
     * {@code app/cbl/COUSR01C.cbl}:101. The copybook literal is blank-padded to fifty; the padding is field
     * width rather than message text and is not reproduced, matching the sibling services.
     */
    private static final String INVALID_KEY_MESSAGE = "Invalid key pressed. Please see below...";

    /**
     * {@code app/cbl/COUSR01C.cbl}:120, the first of the five ordered empty-field messages. Byte-exact:
     * {@code can NOT} carries a capital N, O and T, and the ellipsis is exactly three periods.
     */
    private static final String FIRST_NAME_REQUIRED_MESSAGE = "First Name can NOT be empty...";

    /**
     * {@code app/cbl/COUSR01C.cbl}:126, the second of the five ordered empty-field messages.
     */
    private static final String LAST_NAME_REQUIRED_MESSAGE = "Last Name can NOT be empty...";

    /**
     * {@code app/cbl/COUSR01C.cbl}:132, the third of the five ordered empty-field messages.
     */
    private static final String USER_ID_REQUIRED_MESSAGE = "User ID can NOT be empty...";

    /**
     * {@code app/cbl/COUSR01C.cbl}:138, the fourth of the five ordered empty-field messages. The message
     * names the field and never reproduces the value, which is a credential.
     */
    private static final String PASSWORD_REQUIRED_MESSAGE = "Password can NOT be empty...";

    /**
     * {@code app/cbl/COUSR01C.cbl}:144, the fifth of the five ordered empty-field messages.
     */
    private static final String USER_TYPE_REQUIRED_MESSAGE = "User Type can NOT be empty...";

    /**
     * {@code app/cbl/COUSR01C.cbl}:263, shared by the {@code DFHRESP(DUPKEY)} arm at {@code :260} and the
     * {@code DFHRESP(DUPREC)} arm at {@code :261}, which are adjacent {@code WHEN} clauses over one body.
     *
     * <p><strong>Reproduced with its defect.</strong> The verb reads {@code exist} where English wants
     * {@code exists}. Correcting it is a parity break, so it is not corrected.
     */
    private static final String DUPLICATE_USER_ID_MESSAGE = "User ID already exist...";

    /**
     * {@code app/cbl/COUSR01C.cbl}:270, the {@code WHEN OTHER} arm of the write. Here the verb is correct;
     * the contrast with the message above is in the source, not in the transcription.
     */
    private static final String UNABLE_TO_ADD_MESSAGE = "Unable to Add User...";

    /**
     * The first operand of the confirmation {@code STRING} at {@code app/cbl/COUSR01C.cbl}:255,
     * {@code 'User ' DELIMITED BY SIZE}, so it contributes in full including its trailing blank.
     */
    private static final String ADDED_MESSAGE_PREFIX = "User ";

    /**
     * The third operand of the confirmation {@code STRING} at {@code app/cbl/COUSR01C.cbl}:257,
     * {@code ' has been added ...' DELIMITED BY SIZE}: a leading blank, then {@code has been added}, then a
     * blank, then exactly three periods. It contributes in full.
     */
    private static final String ADDED_MESSAGE_SUFFIX = " has been added ...";

    /**
     * {@code MOVE DFHGREEN TO ERRMSGC OF COUSR1AO} at {@code app/cbl/COUSR01C.cbl}:254. The colour attribute
     * is set on exactly one arm - the successful write - and on no other, so it is reported rather than
     * dropped, which keeps the distinction observable to a caller that has no terminal.
     */
    private static final String MESSAGE_COLOUR_GREEN = "DFHGREEN";

    /**
     * {@code MOVE SPACES TO ...}. The empty string models a blanked alphanumeric field: the store pads to the
     * column width, and no Java code here relies on trailing blanks.
     */
    private static final String SPACES = "";

    /**
     * {@code WS-CURDATE-MM-DD-YY} from {@code app/cpy/CSDAT01Y.cpy}: {@code 9(02)} slash {@code 9(02)} slash
     * {@code 9(02)}, so {@code MM/DD/YY} in eight characters, with the year taken from
     * {@code WS-CURDATE-YEAR(3:2)} - the last two digits - at {@code app/cbl/COUSR01C.cbl}:225. Applied with
     * {@code Locale.ROOT} so the rendering cannot follow a host default.
     */
    private static final DateTimeFormatter HEADER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT);

    /**
     * {@code WS-CURTIME-HH-MM-SS} from {@code app/cpy/CSDAT01Y.cpy}: {@code 9(02)} colon {@code 9(02)} colon
     * {@code 9(02)}, so {@code HH:MM:SS} in eight characters, assembled at
     * {@code app/cbl/COUSR01C.cbl}:229-233. Twenty-four hour, and applied with {@code Locale.ROOT}.
     */
    private static final DateTimeFormatter HEADER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /**
     * {@code MOVE -1 TO FNAMEL OF COUSR1AI}, the cursor the source parks on the first-name field at
     * {@code app/cbl/COUSR01C.cbl}:86, :100, :122, :149, :272 and :289 - by far the most common cursor
     * position in the program, and the one every non-specific path falls back to.
     */
    private static final String CURSOR_FIELD_FIRST_NAME = "FNAME";

    /**
     * {@code MOVE -1 TO LNAMEL OF COUSR1AI} at {@code app/cbl/COUSR01C.cbl}:128.
     */
    private static final String CURSOR_FIELD_LAST_NAME = "LNAME";

    /**
     * {@code MOVE -1 TO USERIDL OF COUSR1AI} at {@code app/cbl/COUSR01C.cbl}:134 and again on the duplicate
     * arm of the write at {@code :265}.
     */
    private static final String CURSOR_FIELD_USER_ID = "USERID";

    /**
     * {@code MOVE -1 TO PASSWDL OF COUSR1AI} at {@code app/cbl/COUSR01C.cbl}:140. The cursor names the
     * field; nothing about the value it holds is carried anywhere.
     */
    private static final String CURSOR_FIELD_PASSWORD = "PASSWD";

    /**
     * {@code MOVE -1 TO USRTYPEL OF COUSR1AI} at {@code app/cbl/COUSR01C.cbl}:146.
     */
    private static final String CURSOR_FIELD_USER_TYPE = "USRTYPE";

    /**
     * The name reported for {@code FNAMEI PIC X(20)} in a typed validation failure. It is the request
     * property name, so a caller can attach the failure to what it sent.
     */
    private static final String FIELD_FIRST_NAME = "firstName";

    /**
     * The name reported for {@code LNAMEI PIC X(20)}.
     */
    private static final String FIELD_LAST_NAME = "lastName";

    /**
     * The name reported for {@code USERIDI PIC X(8)}. Note the spelling: this map declares {@code USERIDI}
     * whereas {@code app/cpy-bms/COUSR02.CPY} and {@code app/cpy-bms/COUSR03.CPY} declare
     * {@code USRIDINI}. The name this program's own map uses is the one honoured here.
     */
    private static final String FIELD_USER_ID = "userId";

    /**
     * The name reported for {@code PASSWDI PIC X(8)}. Only ever the name - the value is a credential and
     * never reaches a message.
     */
    private static final String FIELD_PASSWORD = "password";

    /**
     * The name reported for {@code USRTYPEI PIC X(1)}.
     */
    private static final String FIELD_USER_TYPE = "userType";

    /**
     * {@code DFHRESP(NORMAL)}, ordinal 0, the first arm of the write's dispatch at
     * {@code app/cbl/COUSR01C.cbl}:251.
     */
    private static final int CICS_RESP_NORMAL = 0;

    /**
     * {@code DFHRESP(DUPREC)}, ordinal 14, the second of the two adjacent duplicate arms at
     * {@code app/cbl/COUSR01C.cbl}:261.
     */
    private static final int CICS_RESP_DUPREC = 14;

    /**
     * {@code DFHRESP(IOERR)}, ordinal 17. The source does not name it - the write falls into
     * {@code WHEN OTHER} at {@code app/cbl/COUSR01C.cbl}:267 - but a store failure has to be reported as some
     * condition, and this is the one a physical write failure raises.
     */
    private static final int CICS_RESP_IOERR = 17;

    /**
     * File status {@code '00'}, the equivalent of {@code DFHRESP(NORMAL)} in the vocabulary
     * {@code FileStatusMapper} speaks. This program is a CICS program and tests {@code RESP} rather than
     * {@code FILE STATUS}, so the CICS condition is translated into its file-status equivalent at the one
     * point the mapper is consulted, exactly as the sibling list service does.
     */
    private static final String IO_STATUS_SUCCESS = "00";

    /**
     * File status {@code '22'}, the equivalent of the two duplicate conditions.
     *
     * <p>Recorded for completeness of the vocabulary boundary. The duplicate arm does <em>not</em> route
     * through {@code FileStatusMapper}, because the mapper would supply its own message and the arm has to
     * carry the source's exact literal from {@code app/cbl/COUSR01C.cbl}:263. A census of {@code app/cbl}
     * finds no literal {@code '22'} test anywhere, so this value has no source locator to cite - see the
     * "Not available" section on the class.
     */
    private static final String IO_STATUS_DUPLICATE_KEY = "22";

    /**
     * File status {@code '90'}, the {@code '9x'} family member used for a physical write failure, which is
     * what the {@code WHEN OTHER} arm at {@code app/cbl/COUSR01C.cbl}:267 receives. This is the one status
     * this file hands to {@code FileStatusMapper}.
     */
    private static final String IO_STATUS_IO_ERROR = "90";

    /**
     * The {@code USRSEC} cluster, reached through its {@code JpaRepository} contract: one existence probe on
     * the eight-character primary key, and one insert.
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * The BCrypt encoder published by {@code com.cardemo.config.SecurityConfig} at strength 10. Consumed,
     * never redeclared, and never asked for its strength.
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * The single owner of the file-status-to-exception decision and of the {@code FILE STATUS IS: NNNN}
     * rendering. Neither is reimplemented here, and no status is re-rendered.
     */
    private final FileStatusMapper fileStatusMapper;

    /**
     * The time source behind {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at
     * {@code app/cbl/COUSR01C.cbl}:216.
     */
    private final Clock clock;

    /**
     * Constructs the bean. Constructor injection only: there is no field {@code @Autowired}, no setter
     * injection and no service locator, which is what keeps the bean immutable, thread safe and directly unit
     * testable without a Spring context.
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
    public UserAddService(final UserSecurityRepository userSecurityRepository,
            final PasswordEncoder passwordEncoder,
            final FileStatusMapper fileStatusMapper,
            final Clock clock) {

        this.userSecurityRepository =
                Objects.requireNonNull(userSecurityRepository, "userSecurityRepository must not be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
        this.fileStatusMapper = Objects.requireNonNull(fileStatusMapper, "fileStatusMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    // ------------------------------------------------------------------------------------------------
    // Public surface. Four entry points, one per way the source can be reached: with no communication
    // area, on a first display, on a submitted screen, and on the ENTER arm of a submitted screen.
    // ------------------------------------------------------------------------------------------------

    /**
     * Reproduces the source reached with no communication area at all: {@code IF EIBCALEN = 0} at
     * {@code app/cbl/COUSR01C.cbl}:78-80, which moves {@code 'COSGN00C'} into {@code CDEMO-TO-PROGRAM} and
     * transfers to the sign-on program without touching the security file.
     *
     * <p>Reading and writing nothing is the whole point of the path, so the returned screen carries blank
     * fields and {@code 'COSGN00C'} as its advisory navigation target. Nothing is thrown, because the source
     * raises no condition here, and no header is painted, because the source transfers before
     * {@code SEND-USRADD-SCREEN} is ever performed on this arm.
     *
     * <p>Side effects: none. No query is issued and no row is written.
     *
     * @return a screen whose only meaningful content is the navigation target; never {@code null}
     */
    public UserAddScreen openWithoutContext() {
        return mainPara(false, false, AttentionIdentifier.ENTER, null, null);
    }

    /**
     * Opens an empty add screen: the first-display arm at {@code app/cbl/COUSR01C.cbl}:83-87, where
     * {@code CDEMO-PGM-REENTER} is not yet set, the output map is blanked with
     * {@code MOVE LOW-VALUES TO COUSR1AO}, the cursor is parked on the first-name field and the screen is
     * sent.
     *
     * <p>Side effects: none. Nothing is written, nothing is read, and no state survives the call - the header
     * date and time come from the injected clock and are recomputed on every call.
     *
     * @return a blank add screen with the header painted and the cursor on the first-name field; never
     *         {@code null}
     */
    public UserAddScreen openScreen() {
        return mainPara(true, false, AttentionIdentifier.ENTER, null, null);
    }

    /**
     * Adds a user: the {@code DFHENTER} arm at {@code app/cbl/COUSR01C.cbl}:91-92, which performs
     * {@code PROCESS-ENTER-KEY} and, when no field was empty, {@code WRITE-USER-SEC-FILE}.
     *
     * <p><strong>The five checks run in the source's order and stop at the first failure</strong> - first
     * name, last name, user identifier, password, user type, from {@code :118}, {@code :124}, {@code :130},
     * {@code :136} and {@code :142}. Each raises a {@code ValidationException} that names the offending field
     * and carries the source's exact message, so a request with several empty fields is reported exactly as
     * the screen reported it: one message, for the first field in that order.
     *
     * <p><strong>Side effects.</strong> On success exactly one row is inserted into the security table and
     * the presented password is replaced by a BCrypt digest before it reaches the row. On any failure nothing
     * is written: the method is transactional and rolls back for every exception, checked or unchecked.
     *
     * <p><strong>The credential.</strong> {@code presentedPassword} is read once, hashed immediately, and
     * never stored on this bean, assigned to a work-area field, logged, echoed, returned or placed in an
     * exception message. See the mechanism note on this class for why it arrives as a separate argument.
     *
     * @param request           the eleven readable presented fields of {@code app/cpy-bms/COUSR01.CPY}. Must
     *                          not be {@code null}; every component is treated as untrusted, and a value
     *                          wider than the screen field it transcribes is rejected rather than truncated
     * @param presentedPassword the value of {@code PASSWDI PIC X(8)} at {@code app/cpy-bms/COUSR01.CPY}:78.
     *                          May be {@code null}, empty or blank, each of which takes the empty-password
     *                          arm at {@code app/cbl/COUSR01C.cbl}:136 exactly as the source does
     * @return the assembled screen: on success its five input fields are blank, its message is
     *         {@code "User "} plus the trimmed identifier plus {@code " has been added ..."}, and its message
     *         colour is {@code DFHGREEN}; never
     *         {@code null}
     * @throws NullPointerException                          if {@code request} is {@code null}
     * @throws com.cardemo.exception.ValidationException     if one of the five fields is empty - the
     *                                                       {@code BLANK} state - or if a field is wider than
     *                                                       its screen field, or if the user type is outside
     *                                                       {@code 'A'} and {@code 'U'} - both the
     *                                                       {@code INVALID} state. The field name is always
     *                                                       carried; a field value never is
     * @throws com.cardemo.exception.DuplicateRecordException if the identifier already exists, which is the
     *                                                       {@code DFHRESP(DUPKEY)} and
     *                                                       {@code DFHRESP(DUPREC)} arm at
     *                                                       {@code app/cbl/COUSR01C.cbl}:260-266. It carries
     *                                                       the message of {@code :263}, the logical file and
     *                                                       the colliding key, and nothing else
     * @throws com.cardemo.exception.FileAccessException     if the insert fails for any other reason, the
     *                                                       {@code WHEN OTHER} arm at {@code :267-273}
     * @throws com.cardemo.exception.FatalProcessingException if the condition is genuinely unexpected - for
     *                                                       instance an encoder that is not BCrypt at
     *                                                       strength 10, which the entity refuses
     */
    @Transactional(rollbackFor = Exception.class)
    public UserAddScreen addUser(final UserCreateRequest request, final String presentedPassword) {
        Objects.requireNonNull(request, "request must not be null");
        return mainPara(true, true, AttentionIdentifier.ENTER, request, presentedPassword);
    }

    /**
     * Re-enters the add screen with a submitted screen, which is the pseudo-conversational turn the source
     * takes at {@code app/cbl/COUSR01C.cbl}:88-104: receive the map, then dispatch on {@code EIBAID}.
     *
     * <p>Because the target is stateless, everything the source recovered from the communication area at
     * {@code :82} travels on {@code request} instead. {@code CDEMO-FROM-TRANID}, {@code CDEMO-TO-TRANID},
     * {@code CDEMO-FROM-PROGRAM}, {@code CDEMO-TO-PROGRAM}, {@code CDEMO-PGM-CONTEXT},
     * {@code CDEMO-LAST-MAP} and {@code CDEMO-LAST-MAPSET} have no counterpart at all; only
     * {@code CDEMO-USER-ID} and {@code CDEMO-USER-TYPE} survive, and the security layer supplies both, not
     * this bean - which is precisely why the two moves that would have populated them are commented out at
     * {@code :172-173}.
     *
     * <p><strong>{@code PF3} exits without saving here.</strong> {@code :93-95} moves {@code 'COADM01C'} into
     * {@code CDEMO-TO-PROGRAM} and transfers. The sibling {@code app/cbl/COUSR02C.cbl}:111-112 instead saves
     * on {@code PF3}. Do not harmonise the two.
     *
     * <p><strong>Side effects.</strong> Only the {@code ENTER} arm writes. {@code PF3} and {@code PF4} write
     * nothing, and neither does an unrecognised key. The method is nonetheless transactional, so that the one
     * arm which writes is covered by a single boundary rather than by a conditional one.
     *
     * @param aid               which key the operator pressed. {@code ENTER} validates and adds; {@code PF3}
     *                          exits to the administrative menu without saving; {@code PF4} clears the
     *                          screen; anything else is reported as an invalid key. Must not be {@code null}
     * @param request           the submitted screen. Must not be {@code null} when {@code aid} is
     *                          {@code ENTER}, and may be {@code null} on the other three arms, none of which
     *                          reads an input field
     * @param presentedPassword the value of {@code PASSWDI PIC X(8)}, read only on the {@code ENTER} arm
     * @return the assembled screen; never {@code null}
     * @throws NullPointerException                          if {@code aid} is {@code null}, or if
     *                                                       {@code request} is {@code null} while {@code aid}
     *                                                       is {@code ENTER}
     * @throws com.cardemo.exception.ValidationException     on the {@code ENTER} arm, as {@code addUser}
     *                                                       describes
     * @throws com.cardemo.exception.DuplicateRecordException on the {@code ENTER} arm, as {@code addUser}
     *                                                       describes
     * @throws com.cardemo.exception.FileAccessException     on the {@code ENTER} arm, as {@code addUser}
     *                                                       describes
     * @throws com.cardemo.exception.FatalProcessingException on the {@code ENTER} arm, as {@code addUser}
     *                                                       describes
     */
    @Transactional(rollbackFor = Exception.class)
    public UserAddScreen submitScreen(final AttentionIdentifier aid, final UserCreateRequest request,
            final String presentedPassword) {

        Objects.requireNonNull(aid, "aid must not be null");
        if (aid == AttentionIdentifier.ENTER) {
            Objects.requireNonNull(request, "request must not be null when the attention identifier is ENTER");
        }
        return mainPara(true, true, aid, request, presentedPassword);
    }

    // ------------------------------------------------------------------------------------------------
    // Source-mapped paragraphs. Nine labels, nine private methods, in source order.
    // Never consolidate: TRACEABILITY_MATRIX.md is proved against this correspondence and Gate 7 reads
    // exactly it. Mapping fewer than nine is a Blocker.
    // ------------------------------------------------------------------------------------------------

    /**
     * {@code app/cbl/COUSR01C.cbl:71 MAIN-PARA} - the entry paragraph. Clears the error switch and the
     * message, then dispatches on {@code EIBCALEN} and, on a re-entry, on {@code EIBAID}.
     *
     * <p><strong>Statelessness.</strong> {@code MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA} at
     * {@code :82} has no counterpart: the request carries what the communication area carried.
     * {@code CDEMO-PGM-CONTEXT}, the pseudo-conversational enter-versus-re-enter flag tested at {@code :83}
     * and set at {@code :84}, likewise collapses into stateless request handling: the caller states which arm
     * it wants through {@code reenter}, so the flag itself has no counterpart and nothing whatever is retained
     * between requests. {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)} at
     * {@code :107-110} has no counterpart either: returning the assembled screen is the whole of it, and no
     * transaction identifier is retained.
     *
     * <p><strong>Where the typed failure is raised.</strong> The source cannot throw. Every error arm sets
     * {@code WS-ERR-FLG}, records a message, parks the cursor, sends the screen, and lets {@code :153}
     * {@code IF NOT ERR-FLG-ON} skip the write. That flow is reproduced exactly - the failure is retained,
     * the screen is assembled, the write is skipped - and the retained failure is rethrown here, at the
     * point the source would have returned to CICS. It is rethrown <em>before</em> the response record is
     * materialised, so no object is built only to be discarded.
     *
     * @param aid               the attention identifier evaluated at {@code :90-103}; ignored when
     *                          {@code reenter} is {@code false}, exactly as the source ignores {@code EIBAID}
     *                          on that arm
     * @param commAreaPresent   {@code false} models {@code EIBCALEN = 0} at {@code :78}
     * @param reenter           {@code false} models the first-display arm at {@code :83-87}, {@code true} the
     *                          received-map arm at {@code :88-104}
     * @param request           the submitted screen, standing in for the restored communication area and the
     *                          received map; {@code null} on the arms that read no input field
     * @param presentedPassword the value of {@code PASSWDI PIC X(8)}, passed straight through to the
     *                          {@code ENTER} arm and never assigned to a field of the work area
     * @return the assembled screen
     * @throws com.cardemo.exception.CardDemoException the typed failure retained while the screen was built
     */
    private UserAddScreen mainPara(final boolean commAreaPresent, final boolean reenter,
            final AttentionIdentifier aid, final UserCreateRequest request, final String presentedPassword) {

        final ScreenWorkArea work = new ScreenWorkArea();

        work.errFlgOn = false;                              // :73 SET ERR-FLG-OFF TO TRUE
        work.message = SPACES;                              // :75-76 MOVE SPACES TO WS-MESSAGE / ERRMSGO

        if (!commAreaPresent) {
            // :78-80 IF EIBCALEN = 0 / MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM / PERFORM RETURN-TO-PREV-SCREEN
            work.toProgram = SIGN_ON_PROGRAM;
            returnToPrevScreen(work);
            return buildScreen(work);
        }

        // :82 MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA - no counterpart; the request carries it.
        if (!reenter) {
            // :83 IF NOT CDEMO-PGM-REENTER
            // :84 SET CDEMO-PGM-REENTER TO TRUE - no counterpart; the caller chooses the arm.
            work.clearOutputMap();                          // :85 MOVE LOW-VALUES TO COUSR1AO
            work.cursorField = CURSOR_FIELD_FIRST_NAME;     // :86 MOVE -1 TO FNAMEL OF COUSR1AI
            sendUsraddScreen(work);                         // :87 PERFORM SEND-USRADD-SCREEN
        } else {
            receiveUsraddScreen(work, request);             // :89 PERFORM RECEIVE-USRADD-SCREEN
            switch (aid) {                                  // :90 EVALUATE EIBAID
                case ENTER ->
                    processEnterKey(work, presentedPassword);   // :91-92 WHEN DFHENTER
                case PF3 -> {                                   // :93 WHEN DFHPF3
                    work.toProgram = ADMIN_MENU_PROGRAM;        // :94 MOVE 'COADM01C' TO CDEMO-TO-PROGRAM
                    returnToPrevScreen(work);                   // :95 PERFORM RETURN-TO-PREV-SCREEN
                }
                case PF4 ->
                    clearCurrentScreen(work);                   // :96-97 WHEN DFHPF4
                case OTHER -> {                                 // :98 WHEN OTHER
                    work.errFlgOn = true;                       // :99 MOVE 'Y' TO WS-ERR-FLG
                    work.cursorField = CURSOR_FIELD_FIRST_NAME; // :100 MOVE -1 TO FNAMEL OF COUSR1AI
                    work.message = INVALID_KEY_MESSAGE;         // :101 MOVE CCDA-MSG-INVALID-KEY
                    sendUsraddScreen(work);                     // :102 PERFORM SEND-USRADD-SCREEN
                }
            }
        }

        // :107-110 EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)
        if (work.retainedFailure != null) {
            throw work.retainedFailure;
        }
        return buildScreen(work);
    }

    /**
     * {@code app/cbl/COUSR01C.cbl:115 PROCESS-ENTER-KEY} - <strong>the five ordered empty-field validations,
     * then the write.</strong> This is the core of the program.
     *
     * <p>The {@code EVALUATE TRUE} at {@code :117-151} tests five fields in one fixed order and takes the
     * first arm that matches, so <strong>validation stops at the first failure</strong> and a request with
     * several empty fields yields exactly one message:
     *
     * <ol>
     *   <li>{@code FNAMEI} at {@code :118}, message at {@code :120}, cursor {@code FNAMEL} at {@code :122}
     *       </li>
     *   <li>{@code LNAMEI} at {@code :124}, message at {@code :126}, cursor {@code LNAMEL} at {@code :128}
     *       </li>
     *   <li>{@code USERIDI} at {@code :130}, message at {@code :132}, cursor {@code USERIDL} at {@code :134}
     *       </li>
     *   <li>{@code PASSWDI} at {@code :136}, message at {@code :138}, cursor {@code PASSWDL} at {@code :140}
     *       </li>
     *   <li>{@code USRTYPEI} at {@code :142}, message at {@code :144}, cursor {@code USRTYPEL} at
     *       {@code :146}</li>
     * </ol>
     *
     * <p><strong>Reordering these is a parity break of High severity</strong>, because the order decides which
     * message a multi-field-empty request receives. The chain is written out explicitly for exactly that
     * reason: {@code jakarta.validation} does not guarantee constraint evaluation order, so the five checks
     * are never delegated to it. The cursor that {@code MOVE -1} parks on a field's length item becomes the
     * field named on
     * the typed failure and on the screen, which is how a caller with no terminal learns where the operator's
     * attention belonged.
     *
     * <p><strong>Retained parity artefact: {@code WHEN OTHER} at {@code :148-150}.</strong> The final arm
     * parks the cursor on the first-name field and then does nothing - a literal {@code CONTINUE}. It is the
     * success path, and it is reproduced as the cursor assignment plus a comment rather than deleted, because
     * the arm's existence is what makes the {@code EVALUATE} exhaustive. Severity: Low; tracked in
     * DECISION_LOG.md.
     *
     * <p><strong>Then the record is populated in the source's order</strong> - {@code :154} identifier,
     * {@code :155} first name, {@code :156} last name, {@code :157} password, {@code :158} type - and
     * {@code WRITE-USER-SEC-FILE} is performed once at {@code :159}, guarded by {@code :153}
     * {@code IF NOT ERR-FLG-ON}.
     *
     * <p><strong>The credential.</strong> {@code MOVE PASSWDI TO SEC-USR-PWD} at {@code :157} moved plaintext
     * into the record. Here the plaintext is hashed at that exact point and only the digest is assigned, so
     * the work area never holds a password at any instant. The plaintext exists solely as this method's
     * parameter.
     *
     * @param work              the per-invocation work area
     * @param presentedPassword the value of {@code PASSWDI PIC X(8)}; may be {@code null}, empty or blank,
     *                          all three of which take the arm at {@code :136}
     */
    private void processEnterKey(final ScreenWorkArea work, final String presentedPassword) {

        // :117 EVALUATE TRUE - five ordered arms, first match wins.
        if (isBlankOrUnset(work.firstName)) {                       // :118 WHEN FNAMEI = SPACES OR LOW-VALUES
            work.errFlgOn = true;                                   // :119 MOVE 'Y' TO WS-ERR-FLG
            work.message = FIRST_NAME_REQUIRED_MESSAGE;             // :120-121
            work.cursorField = CURSOR_FIELD_FIRST_NAME;             // :122 MOVE -1 TO FNAMEL
            retainFailure(work,
                    ValidationException.missingField(FIELD_FIRST_NAME, FIRST_NAME_REQUIRED_MESSAGE));
            sendUsraddScreen(work);                                 // :123
        } else if (isBlankOrUnset(work.lastName)) {                 // :124 WHEN LNAMEI = SPACES OR LOW-VALUES
            work.errFlgOn = true;                                   // :125
            work.message = LAST_NAME_REQUIRED_MESSAGE;              // :126-127
            work.cursorField = CURSOR_FIELD_LAST_NAME;              // :128 MOVE -1 TO LNAMEL
            retainFailure(work,
                    ValidationException.missingField(FIELD_LAST_NAME, LAST_NAME_REQUIRED_MESSAGE));
            sendUsraddScreen(work);                                 // :129
        } else if (isBlankOrUnset(work.userId)) {                   // :130 WHEN USERIDI = SPACES OR LOW-VALUES
            work.errFlgOn = true;                                   // :131
            work.message = USER_ID_REQUIRED_MESSAGE;                // :132-133
            work.cursorField = CURSOR_FIELD_USER_ID;                // :134 MOVE -1 TO USERIDL
            retainFailure(work,
                    ValidationException.missingField(FIELD_USER_ID, USER_ID_REQUIRED_MESSAGE));
            sendUsraddScreen(work);                                 // :135
        } else if (isBlankOrUnset(presentedPassword)) {             // :136 WHEN PASSWDI = SPACES OR LOW-VALUES
            work.errFlgOn = true;                                   // :137
            work.message = PASSWORD_REQUIRED_MESSAGE;               // :138-139
            work.cursorField = CURSOR_FIELD_PASSWORD;               // :140 MOVE -1 TO PASSWDL
            // The failure names the field and never the value, which is a credential.
            retainFailure(work,
                    ValidationException.missingField(FIELD_PASSWORD, PASSWORD_REQUIRED_MESSAGE));
            sendUsraddScreen(work);                                 // :141
        } else if (isBlankOrUnset(work.userType)) {                 // :142 WHEN USRTYPEI = SPACES OR LOW-VALUES
            work.errFlgOn = true;                                   // :143
            work.message = USER_TYPE_REQUIRED_MESSAGE;              // :144-145
            work.cursorField = CURSOR_FIELD_USER_TYPE;              // :146 MOVE -1 TO USRTYPEL
            retainFailure(work,
                    ValidationException.missingField(FIELD_USER_TYPE, USER_TYPE_REQUIRED_MESSAGE));
            sendUsraddScreen(work);                                 // :147
        } else {
            // :148 WHEN OTHER
            work.cursorField = CURSOR_FIELD_FIRST_NAME;             // :149 MOVE -1 TO FNAMEL
            // :150 CONTINUE - retained parity artefact: a deliberate no-op on the success path.
        }

        if (!work.errFlgOn) {                                       // :153 IF NOT ERR-FLG-ON
            work.secUsrId = work.userId;                            // :154 MOVE USERIDI  TO SEC-USR-ID
            work.secUsrFname = work.firstName;                      // :155 MOVE FNAMEI   TO SEC-USR-FNAME
            work.secUsrLname = work.lastName;                       // :156 MOVE LNAMEI   TO SEC-USR-LNAME
            work.passwordHash = hashPresentedPassword(presentedPassword);
                                                                    // :157 MOVE PASSWDI  TO SEC-USR-PWD
            work.secUsrType = resolveUserType(work.userType);       // :158 MOVE USRTYPEI TO SEC-USR-TYPE
            writeUserSecFile(work);                                 // :159 PERFORM WRITE-USER-SEC-FILE
        }
    }

    /**
     * {@code app/cbl/COUSR01C.cbl:165 RETURN-TO-PREV-SCREEN} - navigation. Defaults the target program,
     * records where the transfer came from, zeroes the context flag and issues
     * {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)} at {@code :175-178}.
     *
     * <p><strong>Every field this paragraph sets is an intentional no-op here</strong>, and the label is
     * nonetheless mapped one-to-one because {@code TRACEABILITY_MATRIX.md} is proved against paragraph
     * correspondence. Under the stateless mandate navigation collapses into URL-based routing:
     * {@code CDEMO-FROM-TRANID} at {@code :170}, {@code CDEMO-FROM-PROGRAM} at {@code :171},
     * {@code CDEMO-PGM-CONTEXT} at {@code :174} and the control transfer itself have no counterpart, and
     * neither do {@code CDEMO-TO-TRANID}, {@code CDEMO-LAST-MAP} or {@code CDEMO-LAST-MAPSET}. Only
     * {@code CDEMO-USER-ID} and {@code CDEMO-USER-TYPE} survive into the target, as the token subject and the
     * token role, and the security layer supplies both. The one field that does survive as data is the target
     * program name, reported as advisory navigation so a caller can route; nothing is invoked.
     *
     * <p><strong>Retained parity artefact: the defaulting guard at {@code :167-169}.</strong>
     * {@code IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES} substitutes {@code COSGN00C}, and it is
     * <em>defensive in the source too</em>: both of this paragraph's call sites already assign the field
     * first - {@code :79} moves {@code COSGN00C} before performing it, and {@code :94} moves
     * {@code COADM01C} - so no path within this program can reach the guard with the field unset. It is
     * reproduced rather than optimised away because it is a real statement of the paragraph and Gate 7 reads
     * line-level correspondence. It therefore shows as an uncovered branch by design. Severity: Low; tracked
     * in DECISION_LOG.md.
     *
     * <p><strong>Retained parity artefacts: the two commented-out moves at {@code :172-173}.</strong> The
     * source carries {@code MOVE WS-USER-ID TO CDEMO-USER-ID} and
     * {@code MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE} as comments. That is the only textual occurrence of
     * {@code CDEMO-USER-ID} in any of the four {@code COUSR0*C} programs, and it is inert - which is part of
     * the evidence that no self-delete guard exists anywhere in this package, since no program here ever
     * compares a target identifier against the signed-on one. Both lines are retained below as comments and
     * neither is enabled. Severity: Low; tracked in DECISION_LOG.md.
     *
     * @param work the per-invocation work area; {@code work.toProgram} carries the requested target
     */
    private void returnToPrevScreen(final ScreenWorkArea work) {
        if (isBlankOrUnset(work.toProgram)) {
            // :167-169 IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES / MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
            work.toProgram = SIGN_ON_PROGRAM;
        }
        // :170 MOVE WS-TRANID  TO CDEMO-FROM-TRANID   - no counterpart; routing is URL-based.
        // :171 MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM  - no counterpart; routing is URL-based.
        // :172 *    MOVE WS-USER-ID   TO CDEMO-USER-ID    <- commented out in the source; retained, not
        // :173 *    MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE     enabled. See this method's Javadoc.
        // :174 MOVE ZEROS TO CDEMO-PGM-CONTEXT       - no counterpart; the re-entry flag is stateless.
        // :175-178 EXEC CICS XCTL - no counterpart; the target is reported, never invoked.
        work.transferRequested = true;
    }

    /**
     * {@code app/cbl/COUSR01C.cbl:184 SEND-USRADD-SCREEN} - response assembly. Performs
     * {@code POPULATE-HEADER-INFO} at {@code :186}, moves {@code WS-MESSAGE} onto {@code ERRMSGO} at
     * {@code :188}, and issues
     * {@code EXEC CICS SEND MAP('COUSR1A') MAPSET('COUSR01') FROM(COUSR1AO) ERASE CURSOR} at {@code :190-196}.
     *
     * <p>In Java the send is the act of making the fields available on the returned record. Addressing a
     * terminal has no counterpart, so {@code MAP('COUSR1A')} and {@code MAPSET('COUSR01')} translate to
     * nothing; {@code ERASE} is already satisfied by the fields the arms have blanked; and {@code CURSOR} is
     * the cursor field carried on the response. What remains that does translate is the header and the
     * message, and those are what this method assigns.
     *
     * <p>The paragraph is performed on eight distinct arms of the source, at most one of which runs per
     * request. It is idempotent over the work area in any case, so an arm that reached it twice would produce
     * the same screen.
     *
     * @param work the per-invocation work area; its header fields and error message are populated in place
     */
    private void sendUsraddScreen(final ScreenWorkArea work) {
        populateHeaderInfo(work);                       // :186 PERFORM POPULATE-HEADER-INFO
        work.errorMessage = work.message;               // :188 MOVE WS-MESSAGE TO ERRMSGO OF COUSR1AO
        // :190-196 EXEC CICS SEND MAP('COUSR1A') MAPSET('COUSR01') FROM(COUSR1AO) ERASE CURSOR
        //          No counterpart: the map and mapset address a terminal, ERASE is the blanked fields the
        //          arms already assigned, and CURSOR is work.cursorField on the returned record.
    }

    /**
     * {@code app/cbl/COUSR01C.cbl:201 RECEIVE-USRADD-SCREEN} - request binding.
     * {@code EXEC CICS RECEIVE MAP('COUSR1A') MAPSET('COUSR01') INTO(COUSR1AI) RESP(WS-RESP-CD)
     * RESP2(WS-REAS-CD)} at {@code :203-209} becomes the binding of the submitted request onto the work
     * area's map fields. The map and mapset names have no counterpart as addressing. Neither do the response
     * codes captured at {@code :207-208}: the source requests {@code RESP} and {@code RESP2} here and then
     * never tests either one, so there is nothing to reproduce beyond recording that fact.
     *
     * <p>Every field is treated as untrusted. A value wider than the screen field it transcribes is
     * <strong>rejected, never truncated</strong>: quietly cutting it would accept input the eighty-byte record
     * cannot carry. The widths come from {@code app/cpy-bms/COUSR01.CPY} by way of the constants of
     * {@code com.cardemo.model.dto.UserSecurityDto}, so the field contract has a single owner.
     *
     * <p><strong>The credential is deliberately not bound.</strong> {@code INTO(COUSR1AI)} did place
     * {@code PASSWDI} into the map area, but nothing here needs it to live anywhere except on the stack of
     * {@code processEnterKey}, so it is not copied onto the work area. That is a hardening over the source's
     * own storage, and it costs nothing: the emptiness test and the hashing both happen at the points the
     * source performs them.
     *
     * @param work    the per-invocation work area
     * @param request the submitted screen; {@code null} is accepted and binds nothing, which is what the
     *                arms that read no input field require
     * @throws ValidationException if any presented field is wider than the screen field it transcribes
     */
    private void receiveUsraddScreen(final ScreenWorkArea work, final UserCreateRequest request) {
        // :204-205 MAP('COUSR1A') MAPSET('COUSR01') - no counterpart; both address a terminal.
        // :207-208 RESP(WS-RESP-CD) RESP2(WS-REAS-CD) - no counterpart; the source never tests either here.

        if (request == null) {
            return;
        }

        // :206 INTO(COUSR1AI) - eleven readable fields; the twelfth is the write-only credential.
        work.transactionName = requireWidth(request.getTransactionName(),
                UserSecurityDto.TRANSACTION_NAME_WIDTH, "transactionName");
        work.title01 = requireWidth(request.getTitle01(), UserSecurityDto.TITLE_WIDTH, "title01");
        work.currentDate = requireWidth(request.getCurrentDate(), UserSecurityDto.DATE_WIDTH, "currentDate");
        work.programName = requireWidth(request.getProgramName(),
                UserSecurityDto.PROGRAM_NAME_WIDTH, "programName");
        work.title02 = requireWidth(request.getTitle02(), UserSecurityDto.TITLE_WIDTH, "title02");
        work.currentTime = requireWidth(request.getCurrentTime(), UserSecurityDto.TIME_WIDTH, "currentTime");
        work.firstName = requireWidth(request.getFirstName(), UserSecurityDto.NAME_WIDTH, FIELD_FIRST_NAME);
        work.lastName = requireWidth(request.getLastName(), UserSecurityDto.NAME_WIDTH, FIELD_LAST_NAME);
        work.userId = requireWidth(request.getUserId(), UserSecurityDto.USER_ID_WIDTH, FIELD_USER_ID);
        work.userType = requireWidth(request.getUserType(), UserSecurityDto.USER_TYPE_WIDTH, FIELD_USER_TYPE);
        work.errorMessage = requireWidth(request.getErrorMessage(),
                UserSecurityDto.ERROR_MESSAGE_WIDTH, "errorMessage");
    }

    /**
     * {@code app/cbl/COUSR01C.cbl:214 POPULATE-HEADER-INFO} - the six recurring header fields.
     * {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at {@code :216} becomes a read of the injected
     * clock, so the rendering is deterministic and testable rather than dependent on the host wall clock.
     *
     * <p>The titles at {@code :218-219} are the literals of {@code app/cpy/COTTL01Y.cpy}; the transaction and
     * program names at {@code :220-221} are {@code WS-TRANID} and {@code WS-PGMNAME}. The date is assembled at
     * {@code :223-227} as {@code MM/DD/YY} in eight characters, its year taken from
     * {@code WS-CURDATE-YEAR(3:2)} - the last two digits - and the time at {@code :229-233} as
     * {@code HH:MM:SS}, also eight. Both formatters carry {@code Locale.ROOT}.
     *
     * <p><strong>Citation correction, severity Low.</strong> The plan's general note on the symbolic maps
     * records {@code CURTIME} as {@code X(9)}. Both {@code app/cpy-bms/COUSR01.CPY}:54 and
     * {@code app/cpy/CSDAT01Y.cpy} say eight, and the source governs.
     *
     * @param work the per-invocation work area; its six header fields are populated in place
     */
    private void populateHeaderInfo(final ScreenWorkArea work) {
        final LocalDateTime now = LocalDateTime.now(this.clock);   // :216 FUNCTION CURRENT-DATE
        work.title01 = SCREEN_TITLE_01;                            // :218 MOVE CCDA-TITLE01 TO TITLE01O
        work.title02 = SCREEN_TITLE_02;                            // :219 MOVE CCDA-TITLE02 TO TITLE02O
        work.transactionName = TRANSACTION_ID;                     // :220 MOVE WS-TRANID  TO TRNNAMEO
        work.programName = PROGRAM_NAME;                           // :221 MOVE WS-PGMNAME TO PGMNAMEO
        work.currentDate = HEADER_DATE_FORMAT.format(now);         // :223-227 MM/DD/YY
        work.currentTime = HEADER_TIME_FORMAT.format(now);         // :229-233 HH:MM:SS
    }

    /**
     * {@code app/cbl/COUSR01C.cbl:238 WRITE-USER-SEC-FILE} - the insert and its three response branches.
     *
     * <p>The source issues
     * {@code EXEC CICS WRITE DATASET(WS-USRSEC-FILE) FROM(SEC-USER-DATA) LENGTH(LENGTH OF SEC-USER-DATA)
     * RIDFLD(SEC-USR-ID) KEYLENGTH(LENGTH OF SEC-USR-ID) RESP(WS-RESP-CD) RESP2(WS-REAS-CD)} at
     * {@code :240-248}, writing the eighty-byte {@code CSUSR01Y} record under its eight-byte key, and then
     * evaluates the response at {@code :250-274}:
     *
     * <ul>
     *   <li><strong>{@code DFHRESP(NORMAL)} at {@code :251}.</strong> Performs {@code INITIALIZE-ALL-FIELDS}
     *       at {@code :252}, clears the message at {@code :253}, sets the message colour to
     *       {@code DFHGREEN} at {@code :254}, builds the confirmation with the {@code STRING} at
     *       {@code :255-258} and sends at {@code :259}.</li>
     *   <li><strong>{@code DFHRESP(DUPKEY)} at {@code :260} and {@code DFHRESP(DUPREC)} at {@code :261}.</strong>
     *       The two {@code WHEN} clauses are adjacent and <strong>share one body</strong> at {@code :262-266}.
     *       Splitting them into separate branches is a Medium-severity parity break.</li>
     *   <li><strong>{@code WHEN OTHER} at {@code :267}.</strong> Message at {@code :270}, cursor
     *       {@code FNAMEL} at {@code :272}.</li>
     * </ul>
     *
     * <p><strong>The {@code STRING} at {@code :255-258} strips the key's trailing spaces.</strong>
     * {@code 'User '} and {@code ' has been added ...'} are {@code DELIMITED BY SIZE} and contribute in full,
     * while {@code SEC-USR-ID} is {@code DELIMITED BY SPACE}, so the padding of the {@code X(08)} field is
     * dropped. The result is {@code "User " + trimmedId + " has been added ..."} - note the suffix's leading
     * space and its three-period ellipsis.
     *
     * <p><strong>{@code INITIALIZE-ALL-FIELDS} does not clear {@code SEC-USR-ID}.</strong> It blanks the map's
     * input fields only, which is precisely why the {@code STRING} that follows it at {@code :256} can still
     * read the identifier. The work area therefore keeps {@code secUsrId} distinct from the map field
     * {@code userId}, and the confirmation is built from the former.
     *
     * <p><strong>Duplicate handling.</strong> {@code app/cbl/COUSR01C.cbl:260} is the canonical
     * {@code DFHRESP(DUPKEY)} site of the whole corpus and this method is the primary producer of
     * {@code com.cardemo.exception.DuplicateRecordException}. The exception carries the logical file and the
     * eight-character key and <strong>never the record</strong>, explicitly because the record holds
     * {@code SEC-USR-PWD}. A collision is terminal and reportable exactly as in the source: there is no retry,
     * no backoff, no regeneration, no upsert and no database-sequence substitution.
     *
     * <p><strong>Retained parity artefact: the commented-out {@code DISPLAY} at {@code :268}.</strong> The
     * source carries {@code *            DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} as a comment on the
     * {@code WHEN OTHER} arm. It is retained below as a comment and <strong>not</strong> enabled: enabling it
     * would add observable output the source does not produce, and the response codes are carried on the typed
     * failure instead. Severity: Low; tracked in DECISION_LOG.md.
     *
     * @param work the per-invocation work area, carrying the populated record fields and the digest
     * @throws com.cardemo.exception.DuplicateRecordException if the identifier is already present
     * @throws com.cardemo.exception.FileAccessException      on a data-access failure
     * @throws com.cardemo.exception.FatalProcessingException on a genuinely unexpected condition
     */
    private void writeUserSecFile(final ScreenWorkArea work) {

        // :240-248 EXEC CICS WRITE DATASET(WS-USRSEC-FILE) FROM(SEC-USER-DATA) RIDFLD(SEC-USR-ID)
        insertUserSecurityRecord(work);

        switch (work.responseCode) {                             // :250 EVALUATE WS-RESP-CD
            case CICS_RESP_NORMAL -> {                           // :251 WHEN DFHRESP(NORMAL)
                initializeAllFields(work);                       // :252 PERFORM INITIALIZE-ALL-FIELDS
                work.message = SPACES;                           // :253 MOVE SPACES TO WS-MESSAGE
                work.messageColour = MESSAGE_COLOUR_GREEN;       // :254 MOVE DFHGREEN TO ERRMSGC
                // :255-258 STRING 'User ' DELIMITED BY SIZE / SEC-USR-ID DELIMITED BY SPACE
                //                 ' has been added ...' DELIMITED BY SIZE INTO WS-MESSAGE
                work.message = ADDED_MESSAGE_PREFIX + delimitedBySpace(work.secUsrId) + ADDED_MESSAGE_SUFFIX;
                sendUsraddScreen(work);                          // :259 PERFORM SEND-USRADD-SCREEN
            }
            // :260 WHEN DFHRESP(DUPKEY)
            // :261 WHEN DFHRESP(DUPREC)  <- adjacent clauses, ONE shared body. Never split them.
            case CICS_RESP_DUPREC -> {
                work.errFlgOn = true;                            // :262 MOVE 'Y' TO WS-ERR-FLG
                work.message = DUPLICATE_USER_ID_MESSAGE;        // :263-264 'User ID already exist...'
                work.cursorField = CURSOR_FIELD_USER_ID;         // :265 MOVE -1 TO USERIDL
                retainFailure(work, new DuplicateRecordException(DUPLICATE_USER_ID_MESSAGE,
                        USRSEC_FILE, work.secUsrId, work.ioFailureCause));
                work.ioFailureCause = null;
                sendUsraddScreen(work);                          // :266 PERFORM SEND-USRADD-SCREEN
            }
            default -> {                                         // :267 WHEN OTHER
                // :268 *            DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD
                //      Retained parity artefact: commented out in the source, deliberately not enabled.
                work.errFlgOn = true;                            // :269 MOVE 'Y' TO WS-ERR-FLG
                work.message = UNABLE_TO_ADD_MESSAGE;            // :270-271 'Unable to Add User...'
                work.cursorField = CURSOR_FIELD_FIRST_NAME;      // :272 MOVE -1 TO FNAMEL
                retainFailure(work, classify(work, work.ioStatus, WRITE_OPERATION));
                sendUsraddScreen(work);                          // :273 PERFORM SEND-USRADD-SCREEN
            }
        }
    }

    /**
     * {@code app/cbl/COUSR01C.cbl:279 CLEAR-CURRENT-SCREEN} - reset. Performs {@code INITIALIZE-ALL-FIELDS} at
     * {@code :281} and then {@code SEND-USRADD-SCREEN} at {@code :282}, which is the whole of the paragraph.
     *
     * <p>The label is mapped one-to-one even though there is no terminal to clear: the two performs are
     * genuinely reproduced, and together they mean the {@code PF4} arm returns a freshly blanked screen. The
     * cleared state is never retained anywhere - it lives on the per-invocation work area and is discarded
     * with it, so a subsequent request starts from nothing regardless of what this one cleared.
     *
     * @param work the per-invocation work area
     */
    private void clearCurrentScreen(final ScreenWorkArea work) {
        initializeAllFields(work);      // :281 PERFORM INITIALIZE-ALL-FIELDS
        sendUsraddScreen(work);         // :282 PERFORM SEND-USRADD-SCREEN
    }

    /**
     * {@code app/cbl/COUSR01C.cbl:287 INITIALIZE-ALL-FIELDS} - field clear. Parks the cursor on the first-name
     * field at {@code :289} and blanks {@code USERIDI}, {@code FNAMEI}, {@code LNAMEI}, {@code PASSWDI},
     * {@code USRTYPEI} and {@code WS-MESSAGE} at {@code :290-295}.
     *
     * <p><strong>It does not clear {@code SEC-USR-ID}</strong>, and that omission is load-bearing rather than
     * incidental: {@code WRITE-USER-SEC-FILE} performs this paragraph at {@code :252} and then reads
     * {@code SEC-USR-ID} at {@code :256} to build the confirmation. Blanking the record identifier here would
     * produce {@code "User  has been added ..."}. The work area keeps the two apart for exactly this reason.
     *
     * <p>The password field is blanked as the source blanks it, which costs nothing because the work area
     * never held the plaintext in the first place - only this method's counterpart in the source did.
     *
     * @param work the per-invocation work area
     */
    private void initializeAllFields(final ScreenWorkArea work) {
        work.cursorField = CURSOR_FIELD_FIRST_NAME;     // :289 MOVE -1 TO FNAMEL OF COUSR1AI
        work.userId = SPACES;                           // :290 MOVE SPACES TO USERIDI  OF COUSR1AI
        work.firstName = SPACES;                        // :291 MOVE SPACES TO FNAMEI   OF COUSR1AI
        work.lastName = SPACES;                         // :292 MOVE SPACES TO LNAMEI   OF COUSR1AI
        // :293 MOVE SPACES TO PASSWDI OF COUSR1AI - the work area holds no credential to blank.
        work.userType = SPACES;                         // :294 MOVE SPACES TO USRTYPEI OF COUSR1AI
        work.message = SPACES;                          // :295 MOVE SPACES TO WS-MESSAGE
        // SEC-USR-ID is deliberately NOT cleared here. See this method's Javadoc.
    }

    // ------------------------------------------------------------------------------------------------
    // Mechanism helpers. These carry no paragraph of their own: each one is the Java mechanism that
    // stands in for a CICS or Language Environment facility the source could assume.
    // ------------------------------------------------------------------------------------------------

    /**
     * Performs the write of {@code app/cbl/COUSR01C.cbl}:240-248 and records the condition it raised, so that
     * {@link #writeUserSecFile} can evaluate exactly the three response arms the source evaluates.
     *
     * <p><strong>Why an existence probe precedes the insert.</strong> {@code EXEC CICS WRITE} fails on a
     * key that is already present; {@code JpaRepository.save} does not - it would treat the row as detached
     * and issue an update, silently overwriting a user and their credential. One
     * {@code existsById} therefore reproduces the condition the source relies on. That is the whole of the
     * cost: one probe and one insert, no loop and no poll.
     *
     * <p>The probe is not a substitute for the constraint. Between the probe and the flush a concurrent
     * request can insert the same key, so the flush is guarded too and the resulting integrity violation
     * takes <strong>the same single branch</strong>, carrying the original as its cause. Both routes are the
     * one shared body of {@code :262-266}, which is what {@code DFHRESP(DUPKEY)} at {@code :260} and
     * {@code DFHRESP(DUPREC)} at {@code :261} share in the source.
     *
     * <p>{@code saveAndFlush} rather than {@code save} is deliberate: the violation must surface inside this
     * method, where the condition can be classified, rather than at commit time where it would escape the
     * response evaluation entirely.
     *
     * <p><strong>An unmappable digest is an abend, not a response code.</strong> The entity rejects any
     * password hash that is not BCrypt at strength ten. That can only happen if the injected encoder is
     * misconfigured, which no {@code RESP} value models, so it escalates directly to
     * {@code FatalProcessingException} carrying abend code {@code 999} - the contract of
     * {@code CALL 'CEE3ABD'} - with the original as cause. The entity's own messages never reproduce the
     * digest, so propagating the cause discloses nothing.
     *
     * @param work the per-invocation work area, carrying the populated record fields and the digest
     * @throws FatalProcessingException if the digest the injected encoder produced is not BCrypt strength ten
     */
    private void insertUserSecurityRecord(final ScreenWorkArea work) {
        if (this.userSecurityRepository.existsById(work.secUsrId)) {
            LOG.debug("USRSEC write rejected: user identifier already present");
            recordResponse(work, CICS_RESP_DUPREC, IO_STATUS_DUPLICATE_KEY);
            return;
        }

        final UserSecurity record;
        try {
            record = new UserSecurity(work.secUsrId, work.secUsrFname, work.secUsrLname,
                    work.passwordHash, work.secUsrType);
        } catch (final IllegalArgumentException cause) {
            throw new FatalProcessingException(String.valueOf(FatalProcessingException.BATCH_ABEND_CODE),
                    PROGRAM_NAME, "USRSEC RECORD REJECTED BY ITS OWN FIELD CONTRACT",
                    FatalProcessingException.DEFAULT_ABEND_MESSAGE, cause);
        }

        try {
            this.userSecurityRepository.saveAndFlush(record);
            recordResponse(work, CICS_RESP_NORMAL, IO_STATUS_SUCCESS);
        } catch (final DataIntegrityViolationException cause) {
            // A concurrent insert of the same key. Same condition, same single branch as the probe above.
            LOG.debug("USRSEC write rejected: the user identifier was inserted concurrently");
            work.ioFailureCause = cause;
            recordResponse(work, CICS_RESP_DUPREC, IO_STATUS_DUPLICATE_KEY);
        } catch (final DataAccessException cause) {
            LOG.warn("USRSEC write failed with a data-access error", cause);
            work.ioFailureCause = cause;
            recordResponse(work, CICS_RESP_IOERR, IO_STATUS_IO_ERROR);
        }
    }

    /**
     * Records the condition a data-access attempt raised, in both vocabularies the program needs: the CICS
     * {@code RESP} value that {@link #writeUserSecFile} evaluates, and the file status that
     * {@code FileStatusMapper} speaks.
     *
     * <p>Keeping both is what lets the response evaluation read exactly like the source's
     * {@code EVALUATE WS-RESP-CD} while the failure translation still goes through the one component that
     * owns file-status mapping.
     *
     * @param work         the per-invocation work area
     * @param responseCode the CICS condition, one of the {@code CICS_RESP_*} constants
     * @param ioStatus     the equivalent file status, one of the {@code IO_STATUS_*} constants
     */
    private static void recordResponse(final ScreenWorkArea work, final int responseCode,
            final String ioStatus) {
        work.responseCode = responseCode;
        work.ioStatus = ioStatus;
    }

    /**
     * Hashes the presented password with the injected encoder, standing in for
     * {@code MOVE PASSWDI TO SEC-USR-PWD} at {@code app/cbl/COUSR01C.cbl}:157.
     *
     * <p>This is the single deliberate behavioural change in the file and it is not optional: the source
     * stored the eight-character plaintext into {@code SEC-USR-PWD}, and storing a recoverable credential is
     * not reproducible under Rule 1 Clause D. The encoder is BCrypt at strength ten, owned by
     * {@code SecurityConfig} and consumed here. Verifying the digest is the sign-on program's concern, not
     * this one's.
     *
     * <p>The digest replaces the plaintext at the exact statement the source moved it, so the plaintext's
     * lifetime is this call and the parameter that reached it. Neither value is logged, and the digest is
     * never returned on a response.
     *
     * @param presentedPassword the value of {@code PASSWDI PIC X(8)}; already proven non-blank by the check at
     *                          {@code :136}
     * @return the sixty-character BCrypt digest to persist
     * @throws FatalProcessingException if the encoder returns nothing to store
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
     * Maps the single character of {@code USRTYPEI} onto {@code UserType}, standing in for
     * {@code MOVE USRTYPEI TO SEC-USR-TYPE} at {@code app/cbl/COUSR01C.cbl}:158.
     *
     * <p><strong>Labelled deviation, severity Medium, tracked in DECISION_LOG.md.</strong> The source
     * validates only that the field is non-empty, at {@code :142}. It never checks membership of
     * {@code 'A'} or {@code 'U'}, so {@code MOVE} would write any character at all into the one-byte
     * {@code SEC-USR-TYPE} and the resulting user would match neither
     * {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} nor {@code 88 CDEMO-USRTYP-USER VALUE 'U'} of
     * {@code app/cpy/COCOM01Y.cpy}:27-28 - authorising nothing while occupying the identifier.
     *
     * <p>The target's user type is a typed enumeration, so an unmappable character has nowhere to go. It is
     * rejected as a {@code ValidationException} in the {@code INVALID} state - distinct from the
     * {@code BLANK} state the five emptiness checks raise, so a caller can tell "you gave me nothing" from
     * "you gave me something I cannot use". The alternative, widening the column to a raw character, would
     * propagate the defect into the schema.
     *
     * <p>The message names the field and never the value: the value is attacker-supplied, and echoing
     * unvalidated input is how a message becomes an injection vector.
     *
     * @param userType the value of {@code USRTYPEI PIC X(1)}; already proven non-blank by the check at
     *                 {@code :142}
     * @return the mapped user type
     * @throws ValidationException if the character is neither {@code 'A'} nor {@code 'U'}
     */
    private static UserType resolveUserType(final String userType) {
        return UserType.fromCode(userType).orElseThrow(() -> ValidationException.invalidField(
                FIELD_USER_TYPE, "User Type must be A for an administrator or U for a regular user"));
    }

    /**
     * Translates a recorded file status into the typed exception that stands for it, by delegating to the
     * constructor-injected {@code FileStatusMapper}.
     *
     * <p>The mapping itself is never reimplemented here and the status is never re-rendered: the twenty
     * character literal {@code FILE STATUS IS: NNNN} belongs to {@code FileStatus}, and
     * {@code FileStatusMapper} is the one component that owns the translation. This method only supplies the
     * context the mapper needs - the logical file and the operation - and hands over the cause so the root
     * cause survives.
     *
     * <p>The mapper declines to produce an exception for a status it does not consider a failure. That cannot
     * arise from the one call site, which reaches this method only on the {@code WHEN OTHER} arm, but a
     * translation that silently returned nothing would swallow the failure, so the decline is turned into a
     * {@code FileAccessException} carrying the same context rather than left to become {@code null}.
     *
     * <p>The recorded cause is consumed: it is cleared once read, so it cannot be attached twice.
     *
     * @param work      the per-invocation work area, carrying any recorded cause
     * @param ioStatus  the recorded file status
     * @param operation the operation attempted, for the exception's context
     * @return the typed exception standing for that status; never {@code null}
     */
    private CardDemoException classify(final ScreenWorkArea work, final String ioStatus,
            final String operation) {
        final Throwable cause = work.ioFailureCause;
        work.ioFailureCause = null;
        final Optional<CardDemoException> mapped =
                this.fileStatusMapper.toException(ioStatus, USRSEC_FILE, operation, cause);
        if (mapped.isPresent()) {
            return mapped.get();
        }
        return new FileAccessException(UNABLE_TO_ADD_MESSAGE, ioStatus, USRSEC_FILE, operation, cause);
    }

    /**
     * Retains the first typed failure of an invocation so that the screen is assembled before it surfaces.
     *
     * <p>The source cannot throw. Each error arm sets {@code WS-ERR-FLG}, records a message, parks the cursor
     * and performs {@code SEND-USRADD-SCREEN}, and the flag then suppresses the write at {@code :153}. Raising
     * an exception at the point of detection would skip the header painting the source genuinely performs, so
     * the failure waits here until {@link #mainPara} reaches the source's return point.
     *
     * <p><strong>First failure wins</strong>, mirroring the {@code EVALUATE TRUE} of {@code :117-151}, which
     * takes one arm and no more. A later arm cannot overwrite an earlier one. The guard is defensive: because
     * both {@code EVALUATE} structures this method serves take exactly one arm, no single invocation retains
     * twice, so the overwrite-suppressing branch shows as uncovered by design. It is kept because it is what
     * makes "first failure wins" a property of the code rather than of the call order.
     *
     * @param work    the per-invocation work area
     * @param failure the typed failure to retain
     */
    private static void retainFailure(final ScreenWorkArea work, final CardDemoException failure) {
        if (work.retainedFailure == null) {
            work.retainedFailure = failure;
        }
    }

    /**
     * Reproduces {@code DELIMITED BY SPACE} for the {@code STRING} at {@code app/cbl/COUSR01C.cbl}:255-258.
     *
     * <p>{@code SEC-USR-ID} is an {@code X(08)} field, so a shorter identifier is space-padded.
     * {@code DELIMITED BY SPACE} stops the transfer at the first space, which drops that padding, while the
     * two surrounding literals are {@code DELIMITED BY SIZE} and contribute in full. Truncating at the first
     * space - not trimming both ends - is what the phrase means, and it is what this method does.
     *
     * @param value the identifier to transfer; never {@code null} at the one call site
     * @return the value up to but excluding its first space
     */
    private static String delimitedBySpace(final String value) {
        final int firstSpace = value.indexOf(' ');
        return firstSpace < 0 ? value : value.substring(0, firstSpace);
    }

    /**
     * Reproduces the COBOL test {@code = SPACES OR LOW-VALUES} used by all five checks of
     * {@code app/cbl/COUSR01C.cbl}:118, {@code :124}, {@code :130}, {@code :136} and {@code :142}.
     *
     * <p>A screen field arrives space-filled when the operator types nothing and low-value-filled when the map
     * was never populated, and the source treats <em>both</em> as empty. Three Java values therefore count as
     * empty: {@code null} and {@code ""}, which are the natural readings of an unpopulated field, and a value
     * whose every character is whitespace, which is {@code SPACES}.
     *
     * <p><strong>A fourth case matters and {@code String.isBlank()} alone would miss it.</strong>
     * {@code LOW-VALUES} is a run of {@code 0x00}, and {@code Character.isWhitespace('\u005cu0000')} is
     * {@code false}, so a caller who sends NUL or other control bytes would pass an {@code isBlank()} test and
     * have those bytes written into a fixed-width {@code X(n)} field. Control characters are consequently
     * treated as empty alongside whitespace. This is the literal reading of {@code LOW-VALUES} and the correct
     * handling of untrusted input, which is free to contain anything.
     *
     * @param value the presented field; {@code null} is accepted
     * @return {@code true} when the field counts as empty under {@code = SPACES OR LOW-VALUES}
     */
    private static boolean isBlankOrUnset(final String value) {
        if (value == null) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            final char character = value.charAt(i);
            if (!Character.isWhitespace(character) && !Character.isISOControl(character)) {
                return false;
            }
        }
        // Reached for "", for SPACES, and for a run of LOW-VALUES or other control bytes.
        return true;
    }

    /**
     * Enforces the width of a presented field against the screen field it transcribes.
     *
     * <p>The widths come from {@code app/cpy-bms/COUSR01.CPY} by way of the constants of
     * {@code UserSecurityDto}, so the field contract has exactly one owner. An over-long value is
     * <strong>rejected, never truncated</strong>: the record of {@code app/cpy/CSUSR01Y.cpy} is eighty bytes
     * with fixed fields, and quietly cutting a value would persist something the caller did not send while
     * reporting success. {@code null} passes through untouched so that the emptiness checks, which run later
     * and in the source's order, are the arms that report a missing field.
     *
     * @param value     the presented value; {@code null} is returned unchanged
     * @param maxLength the width of the screen field
     * @param fieldName the field's name, for the failure; the value is never included
     * @return the value, unchanged
     * @throws ValidationException if the value is longer than the screen field
     */
    private static String requireWidth(final String value, final int maxLength, final String fieldName) {
        if (value != null && value.length() > maxLength) {
            throw ValidationException.invalidField(fieldName,
                    "Value exceeds the " + maxLength + " character width of this field");
        }
        return value;
    }

    /**
     * Materialises the response from the work area, standing in for the output map {@code COUSR1AO}.
     *
     * <p>Only fields that have a counterpart are carried. The navigation target is reported when
     * {@code RETURN-TO-PREV-SCREEN} ran, as advisory routing that invokes nothing, and is absent otherwise.
     * <strong>No password component exists to populate</strong>, which is the structural half of the
     * guarantee that no credential can leave this service.
     *
     * @param work the per-invocation work area
     * @return the assembled screen; never {@code null}
     */
    private static UserAddScreen buildScreen(final ScreenWorkArea work) {
        return new UserAddScreen(work.transactionName, work.title01, work.currentDate, work.programName,
                work.title02, work.currentTime, work.firstName, work.lastName, work.userId, work.userType,
                work.errorMessage, work.messageColour, work.cursorField,
                work.transferRequested ? work.toProgram : null);
    }

    // ------------------------------------------------------------------------------------------------
    // Nested types. Declared inside the service so that the fixed four-file budget of
    // com.cardemo.service.admin is unaffected: UserListService, UserAddService, UserUpdateService,
    // UserDeleteService, and no package-info.java.
    // ------------------------------------------------------------------------------------------------

    /**
     * The attention identifier evaluated by {@code EVALUATE EIBAID} at {@code app/cbl/COUSR01C.cbl}:90-103.
     *
     * <p>Only the three keys the source names have their own constant; every other key takes the source's
     * {@code WHEN OTHER} arm and is represented by {@link #OTHER}, which is why no constant exists for any
     * further function key.
     */
    public enum AttentionIdentifier {

        /** {@code DFHENTER} at {@code app/cbl/COUSR01C.cbl}:91 - performs {@code PROCESS-ENTER-KEY}. */
        ENTER,

        /**
         * {@code DFHPF3} at {@code app/cbl/COUSR01C.cbl}:93-95 - sets {@code COADM01C} as the target and
         * returns to it.
         *
         * <p><strong>PF3 here is conventional: it exits without saving.</strong> Note the contrast with the
         * sibling {@code UserUpdateService}, whose source performs {@code UPDATE-USER-INFO} on
         * {@code DFHPF3} at {@code app/cbl/COUSR02C.cbl}:111-112 and therefore <em>saves</em> on the key that
         * normally cancels. This program's conventional behaviour is part of the evidence that the sibling's
         * is a quirk, so the two must not be harmonised in either direction.
         */
        PF3,

        /** {@code DFHPF4} at {@code app/cbl/COUSR01C.cbl}:96-97 - performs {@code CLEAR-CURRENT-SCREEN}. */
        PF4,

        /**
         * Every other key: {@code WHEN OTHER} at {@code app/cbl/COUSR01C.cbl}:98-102, which reports
         * {@code CCDA-MSG-INVALID-KEY} and redisplays.
         */
        OTHER
    }

    /**
     * The output map {@code COUSR1AO} of {@code app/cpy-bms/COUSR01.CPY}, as a response.
     *
     * <p>The first eleven components are the readable fields of the symbolic map, at the widths the map
     * declares: six recurring header fields, then the four editable fields, then the error message. The last
     * three carry what {@code EXEC CICS SEND ... CURSOR} and the attribute byte conveyed, plus the advisory
     * navigation target.
     *
     * <p><strong>There is deliberately no password component.</strong> {@code PASSWDI} is an input field of
     * the map and the source never sent it back; declaring nothing to hold it is what makes returning a
     * credential impossible rather than merely unlikely.
     *
     * @param transactionName  {@code TRNNAMEI X(4)}, {@code app/cpy-bms/COUSR01.CPY}:24 - always {@code CU01}
     * @param title01          {@code TITLE01I X(40)}, {@code :30} - {@code CCDA-TITLE01}
     * @param currentDate      {@code CURDATEI X(8)}, {@code :36} - {@code MM/DD/YY} from the injected clock
     * @param programName      {@code PGMNAMEI X(8)}, {@code :42} - always {@code COUSR01C}
     * @param title02          {@code TITLE02I X(40)}, {@code :48} - {@code CCDA-TITLE02}
     * @param currentTime      {@code CURTIMEI X(8)}, {@code :54} - {@code HH:MM:SS} from the injected clock.
     *                         Eight characters, not the nine the plan's general note records
     * @param firstName        {@code FNAMEI X(20)}, {@code :60}
     * @param lastName         {@code LNAMEI X(20)}, {@code :66}
     * @param userId           {@code USERIDI X(8)}, {@code :72}. Note the divergence across the package: this
     *                         map spells the field {@code USERIDI} where {@code COUSR02.CPY} and
     *                         {@code COUSR03.CPY} spell it {@code USRIDINI}
     * @param userType         {@code USRTYPEI X(1)}, {@code :84} - {@code 'A'} or {@code 'U'}
     * @param errorMessage     {@code ERRMSGI X(78)}, {@code :90} - {@code WS-MESSAGE} as moved at
     *                         {@code app/cbl/COUSR01C.cbl}:188
     * @param messageColour    {@code ERRMSGC}, the attribute byte set to {@code DFHGREEN} on success at
     *                         {@code app/cbl/COUSR01C.cbl}:254 and left unset on every other arm
     * @param cursorField      the field whose length item {@code MOVE -1} parked the cursor on; the
     *                         stateless equivalent of {@code SEND ... CURSOR}
     * @param navigationTarget the target of {@code EXEC CICS XCTL} at {@code app/cbl/COUSR01C.cbl}:175-178
     *                         when {@code RETURN-TO-PREV-SCREEN} ran, {@code null} otherwise. Advisory only:
     *                         nothing is invoked
     */
    public record UserAddScreen(
            String transactionName,
            String title01,
            String currentDate,
            String programName,
            String title02,
            String currentTime,
            String firstName,
            String lastName,
            String userId,
            String userType,
            String errorMessage,
            String messageColour,
            String cursorField,
            String navigationTarget) {
    }

    /**
     * The working storage of {@code app/cbl/COUSR01C.cbl}:36-44 together with the map areas
     * {@code COUSR1AI} and {@code COUSR1AO} and the record {@code SEC-USER-DATA} of
     * {@code app/cpy/CSUSR01Y.cpy}, for the duration of one invocation.
     *
     * <p><strong>Why this exists at all.</strong> The source's state is module-level: {@code WS-ERR-FLG},
     * {@code WS-MESSAGE} and the record are addressable from every paragraph. Reproducing that as bean fields
     * would be wrong twice over - two concurrent requests would interleave their messages, and a field holding
     * a credential would be a disclosure that outlived the request. One instance per invocation gives the
     * paragraphs the shared state they genuinely need while leaving the bean immutable and thread-safe.
     *
     * <p><strong>The credential is absent by construction.</strong> There is no field for
     * {@code PASSWDI}: the plaintext lives only as a parameter, and only {@link #passwordHash} - the digest -
     * is ever held. {@code MOVE SPACES TO PASSWDI} at {@code :293} therefore has nothing to blank.
     */
    private static final class ScreenWorkArea {

        /**
         * Creates the work area for one invocation, with every item at the value the source's
         * {@code WORKING-STORAGE SECTION} initialises it to. Exactly one instance exists per call and it is
         * discarded when the call returns, which is what keeps the enclosing bean stateless.
         */
        private ScreenWorkArea() {
            // Every field carries its own initialiser; see the declarations below.
        }

        /** {@code WS-ERR-FLG PIC X(01)}, {@code app/cbl/COUSR01C.cbl}:41 - {@code 'Y'} suppresses the write. */
        private boolean errFlgOn;

        /** {@code WS-MESSAGE PIC X(80)}, {@code app/cbl/COUSR01C.cbl}:39. */
        private String message = SPACES;

        /** {@code WS-RESP-CD PIC S9(09) COMP}, {@code app/cbl/COUSR01C.cbl}:43, as recorded by the write. */
        private int responseCode = CICS_RESP_NORMAL;

        /** The same condition as a file status, in the vocabulary {@code FileStatusMapper} speaks. */
        private String ioStatus = IO_STATUS_SUCCESS;

        /** {@code TRNNAMEO} of {@code COUSR1AO}. */
        private String transactionName;

        /** {@code TITLE01O} of {@code COUSR1AO}. */
        private String title01;

        /** {@code CURDATEO} of {@code COUSR1AO}. */
        private String currentDate;

        /** {@code PGMNAMEO} of {@code COUSR1AO}. */
        private String programName;

        /** {@code TITLE02O} of {@code COUSR1AO}. */
        private String title02;

        /** {@code CURTIMEO} of {@code COUSR1AO}. */
        private String currentTime;

        /** {@code FNAMEI} of {@code COUSR1AI}. */
        private String firstName;

        /** {@code LNAMEI} of {@code COUSR1AI}. */
        private String lastName;

        /** {@code USERIDI} of {@code COUSR1AI}. */
        private String userId;

        /** {@code USRTYPEI} of {@code COUSR1AI}. */
        private String userType;

        /** {@code ERRMSGO} of {@code COUSR1AO}, {@code app/cbl/COUSR01C.cbl}:188. */
        private String errorMessage;

        /** {@code ERRMSGC} of {@code COUSR1AO}, {@code app/cbl/COUSR01C.cbl}:254. */
        private String messageColour;

        /** The field whose length item {@code MOVE -1} parked the cursor on. */
        private String cursorField;

        /**
         * {@code SEC-USR-ID X(08)}, {@code app/cpy/CSUSR01Y.cpy}:18, the eight-byte key of
         * {@code KEYS(8,0)} at {@code app/jcl/DUSRSECJ.jcl}:65.
         *
         * <p>Held separately from {@link #userId} because {@code INITIALIZE-ALL-FIELDS} blanks the map field
         * at {@code app/cbl/COUSR01C.cbl}:290 but leaves the record field intact, which is what lets the
         * confirmation at {@code :256} still read the identifier after the clear at {@code :252}.
         */
        private String secUsrId;

        /** {@code SEC-USR-FNAME X(20)}, {@code app/cpy/CSUSR01Y.cpy}:19. */
        private String secUsrFname;

        /** {@code SEC-USR-LNAME X(20)}, {@code app/cpy/CSUSR01Y.cpy}:20. */
        private String secUsrLname;

        /**
         * The BCrypt digest that replaces {@code SEC-USR-PWD X(08)} of {@code app/cpy/CSUSR01Y.cpy}:21.
         * Sixty characters, never the plaintext, and never carried onto a response.
         */
        private String passwordHash;

        /** {@code SEC-USR-TYPE X(01)}, {@code app/cpy/CSUSR01Y.cpy}:22, as a typed value. */
        private UserType secUsrType;

        /** {@code CDEMO-TO-PROGRAM}, the target of {@code app/cbl/COUSR01C.cbl}:167-169 and {@code :94}. */
        private String toProgram;

        /** Whether {@code RETURN-TO-PREV-SCREEN} ran, so the target is reported only when it did. */
        private boolean transferRequested;

        /** The first typed failure of the invocation, rethrown by {@link UserAddService#mainPara}. */
        private CardDemoException retainedFailure;

        /** The underlying data-access failure, so the root cause survives onto the typed exception. */
        private Throwable ioFailureCause;

        /**
         * {@code MOVE LOW-VALUES TO COUSR1AO} at {@code app/cbl/COUSR01C.cbl}:85 - blanks the output map on
         * first display. Low values render as nothing on a terminal, so the Java equivalent is the empty
         * string rather than a space-filled field.
         */
        private void clearOutputMap() {
            this.transactionName = SPACES;
            this.title01 = SPACES;
            this.currentDate = SPACES;
            this.programName = SPACES;
            this.title02 = SPACES;
            this.currentTime = SPACES;
            this.firstName = SPACES;
            this.lastName = SPACES;
            this.userId = SPACES;
            this.userType = SPACES;
            this.errorMessage = SPACES;
        }
    }
}
