/*
 * ******************************************************************
 * Program     : UserUpdateServiceTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/service
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that UserUpdateService reproduces COUSR02C
 *               exactly - eleven paragraphs mapped one to one, the six
 *               attention identifiers including the PRESERVED QUIRK that
 *               PF3 saves before leaving while PF12 leaves without
 *               saving, the five emptiness guards in the source's order
 *               with every literal byte exact, change detection that
 *               compares the credential through PasswordEncoder.matches
 *               and never by string equality, the two-layer concurrency
 *               guard, the four read-and-rewrite response arms with
 *               their four distinct literals and three message colours,
 *               and a response record that declares NO credential
 *               component at all
 * Source      : app/cbl/COUSR02C.cbl (414 lines, 11 paragraphs) @ 7756d89
 * Source      : app/cpy-bms/COUSR02.CPY (12 input fields) @ 7756d89
 * Source      : app/cpy/CSUSR01Y.cpy (80 byte record, KEYS(8,0)) @ 7756d89
 * Source      : app/cpy/CSMSG01Y.cpy (CCDA-MSG-INVALID-KEY) @ 7756d89
 * Source      : app/cpy/COTTL01Y.cpy (CCDA-TITLE01, CCDA-TITLE02) @ 7756d89
 * Source      : app/csd/CARDDEMO.CSD (TRANSACTION(CU02), FILE(USRSEC)) @ 7756d89
 * Source      : app/jcl/DUSRSECJ.jcl (KEYS(8,0), 10 seeded users) @ 7756d89
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
package com.cardemo.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import jakarta.persistence.OptimisticLockException;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.dto.UserUpdateRequest;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.service.admin.UserUpdateService;
import com.cardemo.service.admin.UserUpdateService.AttentionIdentifier;
import com.cardemo.service.admin.UserUpdateService.UserSnapshot;
import com.cardemo.service.admin.UserUpdateService.UserUpdateScreen;
import com.cardemo.service.shared.FileStatusMapper;

/**
 * Unit tests for {@code com.cardemo.service.admin.UserUpdateService}, the Java replacement for
 * {@code app/cbl/COUSR02C.cbl} - 414 lines and 11 paragraphs, the CICS program behind transaction
 * {@code CU02}, which updates one row of the {@code USRSEC} security file.
 *
 * <h2>What it does</h2>
 *
 * <p>It proves parity against the frozen source rather than against an idea of what the source ought to do.
 * Every assertion cites the paragraph or line it proves, and the citations were verified by direct inspection
 * at commit {@code 7756d89}. Six groups of behaviour carry the weight.
 *
 * <ul>
 *   <li><strong>PF3 SAVES.</strong> {@code app/cbl/COUSR02C.cbl}:111-119 performs
 *       {@code UPDATE-USER-INFO} and <em>then</em> transfers to the admin menu, whereas {@code :124-126}
 *       transfers without performing it. So the key a user presses to leave writes the record and the key
 *       documented as cancel does not. This is a legacy quirk, not a defect to repair, and it is asserted in
 *       both directions - PF3 reaching the store and PF12 never touching it.</li>
 *   <li><strong>The five emptiness guards, in order.</strong> {@code :179-213}: identifier, first name, last
 *       name, credential, user type, with a {@code WHEN OTHER} arm that only parks the cursor. First match
 *       wins, so a form empty in several fields reports only the earliest. Each literal is asserted byte for
 *       byte, capital {@code N}, {@code O} and {@code T} of {@code can NOT} included.</li>
 *   <li><strong>The credential is compared through the encoder, never by string equality.</strong>
 *       {@code :227} was {@code IF PASSWDI NOT = SEC-USR-PWD}, a plaintext comparison. The store now holds a
 *       salted one-way digest, so equality is meaningless and
 *       {@link PasswordEncoder#matches(CharSequence, String)} is the only correct translation. This suite
 *       proves the encoder is consulted, that a matching credential is <em>not</em> counted as a change, and
 *       that a non-matching one is re-encoded before the write.</li>
 *   <li><strong>Two layers of concurrency control.</strong> The source held a record lock for the whole
 *       conversation, which a stateless target cannot. A caller-supplied snapshot of what was last displayed
 *       is compared field by field at business level, and the provider's own optimistic failure is caught at
 *       store level; both produce {@code DATA_CHANGED_BEFORE_UPDATE}, and the cause is what tells them
 *       apart.</li>
 *   <li><strong>Four response arms, four literals, three colours.</strong> The read at {@code :333-353} and
 *       the rewrite at {@code :368-390} each branch three ways, producing
 *       {@code Press PF5 key to save your updates ...} in neutral, {@code User ID NOT found...},
 *       {@code Unable to lookup User...}, {@code Unable to Update User...},
 *       {@code Please modify to update ...} in red, and the assembled
 *       {@code User <id> has been updated ...} in green.</li>
 *   <li><strong>No credential is ever returned.</strong> {@code :169} moved {@code SEC-USR-PWD} into the
 *       output map - it painted the stored password onto the screen. That is deliberately <em>not</em>
 *       reproduced: the response record declares no credential component at all, which this suite asserts
 *       reflectively so that adding one breaks the build rather than leaking a secret.</li>
 *   </ul>
 *
 * <h2>How to build and test</h2>
 *
 * <ul>
 *   <li>{@code ./mvnw -B -ntp test} - runs this class under {@code maven-surefire-plugin:3.5.4}. Residence
 *       in the {@code unit} tree is load-bearing: a class outside it matches neither Surefire's nor
 *       Failsafe's include set and would silently never run.</li>
 *   <li>{@code ./mvnw -B -ntp test-compile} - {@code -Xlint:all -Werror} with {@code failOnWarning} reaches
 *       test compilation, so one unused import is fatal.</li>
 *   <li>{@code ./mvnw -B -ntp -Dtest=UserUpdateServiceTest test} - runs this class alone.</li>
 * </ul>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li><strong>A stubbed encoder, deliberately.</strong> BCrypt salts, so a real encoder is
 *       non-deterministic and a digest literal would be meaningless. The encoder is a double, and
 *       <strong>no digest literal appears anywhere in this file</strong>: the values it returns are
 *       assembled at run time from their parts, and {@code UserSecurity} independently enforces strength 10
 *       on anything persisted.</li>
 *   <li><strong>A real file-status mapper.</strong> {@code FileStatusMapper} is a pure function with a
 *       no-argument constructor, so the real one is used except where a test needs to observe exactly what
 *       crosses into it.</li>
 *   <li><strong>A fixed clock.</strong> {@code Clock.fixed} at a parsed instant in UTC, standing in for
 *       {@code MOVE FUNCTION CURRENT-DATE} at {@code :298}. Nothing here reads a wall clock, a default
 *       locale, a default zone or an unseeded random source.</li>
 *   <li><strong>A synthetic credential.</strong> Obviously fake, eight characters so that it sits exactly on
 *       the source field width. The ten users seeded inline by {@code app/jcl/DUSRSECJ.jcl} share one
 *       plaintext credential and <strong>that value is not reproduced here in any form</strong>.</li>
 *   <li><strong>Mockito strict stubs.</strong> An unused stub fails the test, so stubs are arranged inside
 *       each test and stubbing with the exact expected argument is itself an assertion.</li>
 *   </ul>
 *
 * <h2>Two inefficiencies retained deliberately, and why</h2>
 *
 * <p>The source does two things twice that a Java author would naturally do once, and both are kept. They
 * are named here because an unexplained duplicate looks like a defect introduced in translation, and because
 * an optimisation that removed either would be a behaviour change wearing the clothes of a cleanup.
 *
 * <ul>
 *   <li><strong>A save reads the record again.</strong> {@code app/cbl/COUSR02C.cbl}:163 reads on the
 *       {@code ENTER} arm and {@code :217} reads again inside {@code UPDATE-USER-INFO}, so a
 *       display-then-save conversation issues two reads. The second one is not redundant: it is what the
 *       four change predicates at {@code :219-233} compare against, and comparing against a value cached
 *       from the first read would compare against a stale record instead. The suite therefore asserts the
 *       second read explicitly, in order, rather than allowing it to be collapsed away.</li>
 *   <li><strong>A successful lookup sends the screen twice.</strong> The {@code CONTINUE} at {@code :335}
 *       terminates nothing, so {@code :336-339} all execute and the read itself sends the screen; then
 *       {@code :171} sends it again. Reading that {@code CONTINUE} as ending its branch is the single easiest
 *       way to lose the save hint altogether, which is why the hint's text and its neutral marker are both
 *       asserted. The identical shape recurs at {@code app/cbl/COUSR00C.cbl}:601.</li>
 *   </ul>
 *
 * <p>Neither is an oversight and neither is optimised away: behavioural parity is the contract, both are
 * observable, and each is owed an entry in the planned {@code DECISION_LOG.md}.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>PF3 stops saving.</strong> Someone "fixed" the quirk. The system of record writes on that
 *       key; a target that does not silently discards the user's edits. Remedy: restore the
 *       {@code UPDATE-USER-INFO} performed at {@code :112}, and leave the surprise visible. Severity:
 *       <strong>High</strong>.</li>
 *   <li><strong>The credential is compared with {@code equals}.</strong> The stored value is a salted
 *       digest, so equality can never hold and every submission would be counted as a change and re-hashed.
 *       Remedy: {@code matches}. Severity: <strong>High</strong>.</li>
 *   <li><strong>A password appears in a response.</strong> Someone reproduced {@code :169}. Remedy: remove
 *       it; the response record must declare no such component. Severity: <strong>Blocker</strong>.</li>
 *   <li><strong>An unchanged submission writes anyway.</strong> The four change tests were collapsed or the
 *       modified flag was set unconditionally, so {@code :238-243} never runs and
 *       {@code Please modify to update ...} is never seen. Remedy: restore the four tests. Severity:
 *       <strong>Medium</strong>.</li>
 *   <li><strong>Trailing spaces count as a change.</strong> The source compared fixed-width fields, so
 *       {@code "AJITH"} and {@code "AJITH               "} are the same value. Remedy: pad both sides to the
 *       declared width before comparing. Severity: <strong>Medium</strong>.</li>
 *   <li><strong>Two outcomes become indistinguishable.</strong> Four literals and three colours are
 *       observable state. Remedy: keep the arms distinct. Severity: <strong>Medium</strong>.</li>
 *   <li><strong>A non-administrator reaches the screen.</strong> This bean performs no authorisation and
 *       cannot: it reads no role, takes no principal and carries only {@code @Service}. A user able to
 *       change a user class could raise its own account to administrator. Remedy: the {@code /api/admin/**}
 *       rule belongs to the filter chain, and {@code src/test/java/com/cardemo/unit/config/SecurityConfigTest.java}
 *       owns it; what this suite asserts is that nothing here can weaken it. Severity:
 *       <strong>Blocker</strong>.</li>
 *   <li><strong>A snapshot comparison is made mandatory.</strong> The comparison at {@code :219-233} reads
 *       the record as freshly re-read at {@code :217}, not a request-carried snapshot, so a caller that
 *       supplies none must still be able to write. Remedy: keep the snapshot optional. Severity:
 *       <strong>High</strong>.</li>
 *   <li><strong>A validation rule the source lacks is added.</strong> Upper-casing, a credential policy, a
 *       length floor or a user-class membership test at the input gate all reject input the system of record
 *       accepts. Remedy: emptiness only, exactly as {@code :146} and {@code :179-213} test it. Severity:
 *       <strong>High</strong>.</li>
 *   <li><strong>A duplicate-record outcome appears on the write path.</strong> The rewrite at
 *       {@code :360-366} carries no {@code RIDFLD} and therefore has no duplicate-key arm to translate.
 *       Remedy: delete the invented branch. Severity: <strong>Medium</strong>.</li>
 *   <li><strong>The unrecognised-key message is re-declared locally.</strong> It is the one message here
 *       that is shared corpus-wide - {@code CCDA-MSG-INVALID-KEY} of {@code app/cpy/CSMSG01Y.cpy} - and this
 *       suite reads it off the production declaration rather than transcribing it. Severity:
 *       <strong>Medium</strong>.</li>
 *   <li><strong>The five guards are shared with the add screen.</strong> The literals are identical and the
 *       precedence is not: this program leads with the identifier, {@code app/cbl/COUSR01C.cbl} leads with
 *       the first name. Sharing an implementation silently changes which message wins. Remedy: keep the two
 *       chains apart, as {@code src/test/java/com/cardemo/unit/service/UserAddServiceTest.java} keeps its
 *       own. Severity: <strong>Medium</strong>.</li>
 *   <li><strong>A message literal is re-spaced.</strong> Three messages carry a space before their ellipsis
 *       and three do not; the ellipsis is always exactly three periods. Severity: <strong>Low</strong>.</li>
 *   <li><strong>The no-change refusal is recoloured.</strong> {@code :241} marks it {@code DFHRED}, so a
 *       submission that changed nothing is reported as an error rather than as a benign outcome. It looks
 *       wrong and is the contract. Severity: <strong>Low</strong>.</li>
 *   <li><strong>A retained no-op is deleted.</strong> The {@code CONTINUE} at {@code :154} and at
 *       {@code :212} terminate nothing, and the one at {@code :335} is why every successful read emits the
 *       save hint at all. Severity: <strong>Low</strong>.</li>
 *   <li><strong>The vestigial block is treated as live.</strong> Of {@code :51-58} only
 *       {@code CDEMO-CU02-USR-SELECTED} is read; the page number, next-page flag and first-and-last keys are
 *       a list screen's block cloned onto a single-record screen. Severity: <strong>Low</strong>.</li>
 *   <li><strong>The dataset literal is trimmed of its padding.</strong> {@code :39} declares
 *       {@code 'USRSEC  '} with two trailing spaces; the bean holds it unpadded because it is used as an
 *       identity on a typed failure and never as a fixed-width field. Severity: <strong>Low</strong>.</li>
 *   </ul>
 *
 * <h2>What is settled here, and what is not</h2>
 *
 * <p>Two questions about this path can only be answered by reading the artefacts that own them, so both were
 * read rather than assumed, and both turn out to be settled.
 *
 * <ul>
 *   <li><strong>Optimistic version column: settled - there is none.</strong>
 *       {@code com.cardemo.model.entity.UserSecurity} declares five persistent fields and no version
 *       counter, and the first migration creates no such column for this table. The store-level concurrency
 *       layer is therefore a stale-write guard rather than a version comparison, which is why this suite
 *       drives it with the provider's own optimistic failures instead of by moving a counter. No snapshot
 *       comparison is introduced on account of it either way, because the source has none.</li>
 *   <li><strong>Eight-character credential truncation: settled - the value is refused, not truncated.</strong>
 *       The bean bounds the presented credential at the source's eight characters and rejects anything wider
 *       with a field-named refusal, so no silent truncation exists to preserve. The stored column is sixty
 *       characters because it holds a digest, which is the one field on this record that is deliberately not
 *       byte-parity with {@code app/cpy/CSUSR01Y.cpy}.</li>
 *   </ul>
 *
 * <p>Three things are genuinely <strong>Not available</strong> from this tier, and each is named rather than
 * papered over:
 *
 * <ul>
 *   <li><strong>Cursor placement on any arm that raises: Not available.</strong> Seven {@code MOVE -1}
 *       statements park the cursor on a field at fault - {@code :196}, {@code :202}, {@code :208},
 *       {@code :344}, {@code :351}, {@code :381} and {@code :388} - and every one of those arms ends in a
 *       typed failure, which carries no screen. The placement is therefore asserted where it <em>is</em>
 *       observable: on the arms that return a screen, at {@code :98}, {@code :153}, {@code :211} and
 *       {@code :405}, and through the field name a {@code ValidationException} carries everywhere else.
 *       <em>What is needed:</em> a field name on the file-access failure type, which is a decision for the
 *       exception hierarchy and not for a test.</li>
 *   <li><strong>The 80-into-78 narrowing as a live truncation: Not available.</strong> The narrowing is real
 *       and is asserted as a width, but no literal this program emits is longer than the channel, so nothing
 *       is actually cut. <em>What is needed:</em> a reachable message longer than seventy-eight characters;
 *       none exists, and one must not be invented to manufacture the observation.</li>
 *   <li><strong>Any latency or throughput figure: Not available.</strong> The source publishes no service
 *       level anywhere, so none is invented and none is asserted. <em>What is needed:</em> a stated
 *       objective, which only a stakeholder can supply; the performance gate records a measured baseline
 *       instead of a target for exactly this reason.</li>
 *   <li><strong>The exit arm returning to its originating program: Not available.</strong> {@code :113-118}
 *       resolves {@code CDEMO-FROM-PROGRAM} when one was recorded and falls back to {@code 'COADM01C'}
 *       otherwise, but that field is communication-area state with no counterpart under the stateless
 *       mandate, so only the {@code :114} outcome is reachable and the {@code ELSE} at {@code :116-117}
 *       cannot be exercised at all. What <em>is</em> asserted is that both leaving arms resolve to the
 *       administrative menu, and that the exit arm writes first while the cancel arm does not.
 *       <em>What is needed:</em> a caller-supplied originating context on the request, which is a routing
 *       decision for the controller and not for a test to invent.</li>
 *   </ul>
 *
 * <p>Every deliberately preserved oddity above is owed an entry in the planned {@code DECISION_LOG.md} and a
 * row in the planned {@code TRACEABILITY_MATRIX.md}; the citations in this file are what those entries will
 * be written from. Three contracts this path touches are deliberately <em>not</em> re-asserted here, because
 * duplicating them would let the two copies drift:
 * {@code src/test/java/com/cardemo/unit/model/UserUpdateRequestTest.java} owns the map area's twelve-field
 * shape and its declared widths, {@code src/test/java/com/cardemo/unit/model/UserSecurityTest.java} owns the
 * record's own invariants, and
 * {@code src/test/java/com/cardemo/unit/model/FatalProcessingExceptionTest.java} together with
 * {@code src/test/java/com/cardemo/unit/model/ConcurrentUpdateExceptionTest.java} own the payloads of the two
 * failure types this bean raises but does not define.
 */
@DisplayName("UserUpdateService: app/cbl/COUSR02C.cbl - update one USRSEC row (CU02)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class UserUpdateServiceTest {

    // ----------------------------------------------------------------------------------------------------
    // Synthetic credential material. Rule 1 Clause D names tests explicitly, so nothing here is or
    // resembles a real credential, and the seed value of app/jcl/DUSRSECJ.jcl is never reproduced.
    // ----------------------------------------------------------------------------------------------------

    /** The presented plaintext, standing in for {@code PASSWDI PIC X(8)}. Obviously fake, eight characters. */
    private static final String PRESENTED_CREDENTIAL = "n0tr3al!";

    /** A second, different plaintext, for the change-detected path. */
    private static final String REPLACEMENT_CREDENTIAL = "al5ofake";

    /** The {@code $} separator, held as a character so no BCrypt prefix literal exists in this file. */
    private static final char DIGEST_FIELD_MARKER = '$';

    /** A version tag {@code UserSecurity} admits. */
    private static final String DIGEST_VERSION_TAG = "2a";

    /** The contractual cost factor; {@code UserSecurity} admits this and no other. */
    private static final String CONTRACTUAL_COST_FACTOR = "10";

    /** Twenty-two characters from BCrypt's radix-64 alphabet, standing where a salt would sit. */
    private static final String SYNTHETIC_SALT = "SyntheticUnitTestSalt0";

    /** Thirty-one characters standing where a digest body would sit. */
    private static final String SYNTHETIC_BODY = "NotARealCredentialPlaceholder00";

    /** A second body, so a re-hash is observably different from the value it replaced. */
    private static final String REPLACEMENT_BODY = "NotARealCredentialPlaceholder11";

    // ----------------------------------------------------------------------------------------------------
    // Identity and record contents. Widths from app/cpy/CSUSR01Y.cpy:18-22.
    // ----------------------------------------------------------------------------------------------------

    /** The eight-character key, from {@code SEC-USR-ID PIC X(08)}. */
    private static final String USER_ID = "USER0002";

    /** The stored first name, within {@code SEC-USR-FNAME PIC X(20)}. */
    private static final String STORED_FIRST_NAME = "AJITH";

    /** The stored last name, within {@code SEC-USR-LNAME PIC X(20)}. */
    private static final String STORED_LAST_NAME = "KUMAR";

    /** A different first name, for the change-detected path. */
    private static final String NEW_FIRST_NAME = "AJITHA";

    /** A different last name, for the change-detected path. */
    private static final String NEW_LAST_NAME = "KUMARI";

    /** The stored user class as the screen carries it. */
    private static final String STORED_USER_TYPE = "U";

    /** The other user class. */
    private static final String OTHER_USER_TYPE = "A";

    // ----------------------------------------------------------------------------------------------------
    // Screen identity, app/cbl/COUSR02C.cbl:302-303 and app/cpy/COTTL01Y.cpy:18-22.
    // ----------------------------------------------------------------------------------------------------

    /** {@code WS-TRANID PIC X(04) VALUE 'CU02'}. */
    private static final String TRANSACTION_ID = "CU02";

    /** {@code WS-PGMNAME PIC X(08) VALUE 'COUSR02C'}. */
    private static final String PROGRAM_NAME = "COUSR02C";

    /** The {@code EIBCALEN = 0} destination at {@code :90-92}. */
    private static final String SIGN_ON_PROGRAM = "COSGN00C";

    /** The PF3 and PF12 destination at {@code :114} and {@code :125}. */
    private static final String ADMIN_MENU_PROGRAM = "COADM01C";

    /** {@code CCDA-TITLE01 PIC X(40)}, forty characters including its padding. */
    private static final String SCREEN_TITLE_01 = "      AWS Mainframe Modernization       ";

    /** {@code CCDA-TITLE02 PIC X(40)}, forty characters including its padding. */
    private static final String SCREEN_TITLE_02 = "              CardDemo                  ";

    /** The logical file name, {@code WS-USRSEC-FILE}. */
    private static final String USRSEC_FILE = "USRSEC";

    // ----------------------------------------------------------------------------------------------------
    // Cursor fields: the symbolic-map length fields that received MOVE -1. Only the two that land on an arm
    // returning a screen are named here. The placements at :196, :202, :208, :344, :351, :381 and :388 all
    // sit on arms that raise a typed failure, and a raised failure carries no screen, so the Java-side
    // marker for those is the field NAME the failure carries - which is what those tests assert. Naming a
    // constant that no assertion can reach would be dead code under Rule 1 Clause B.
    // ----------------------------------------------------------------------------------------------------

    /** {@code USRIDINL}, from {@code :98}, {@code :153} and {@code :405} - all arms that return a screen. */
    private static final String CURSOR_USER_ID = "USRIDIN";

    /** {@code FNAMEL}, from the {@code WHEN OTHER} arm at {@code :211}, which precedes the no-change screen. */
    private static final String CURSOR_FIRST_NAME = "FNAME";

    // ----------------------------------------------------------------------------------------------------
    // Literals. Byte exact: "can NOT" with capital N O T, every ellipsis exactly three periods, and the
    // no-change and save-hint messages each carrying a space before their ellipsis where the empties do not.
    // ----------------------------------------------------------------------------------------------------

    /** {@code CCDA-MSG-INVALID-KEY} from {@code app/cpy/CSMSG01Y.cpy}, used at {@code :127-130}. */
    private static final String INVALID_KEY_MESSAGE = "Invalid key pressed. Please see below...";

    /** {@code :148} and {@code :182}. */
    private static final String USER_ID_REQUIRED_MESSAGE = "User ID can NOT be empty...";

    /** {@code :188}. */
    private static final String FIRST_NAME_REQUIRED_MESSAGE = "First Name can NOT be empty...";

    /** {@code :194}. */
    private static final String LAST_NAME_REQUIRED_MESSAGE = "Last Name can NOT be empty...";

    /** {@code :200}. */
    private static final String CREDENTIAL_REQUIRED_MESSAGE = "Password can NOT be empty...";

    /** {@code :206}. */
    private static final String USER_TYPE_REQUIRED_MESSAGE = "User Type can NOT be empty...";

    /** {@code :239}, with a space before the ellipsis. */
    private static final String NO_CHANGE_MESSAGE = "Please modify to update ...";

    /** {@code :336}, with a space before the ellipsis. */
    private static final String SAVE_HINT_MESSAGE = "Press PF5 key to save your updates ...";

    /** {@code :342} and {@code :379}. */
    private static final String USER_NOT_FOUND_MESSAGE = "User ID NOT found...";

    /** {@code :349}. */
    private static final String UNABLE_TO_LOOKUP_MESSAGE = "Unable to lookup User...";

    /** {@code :386}. */
    private static final String UNABLE_TO_UPDATE_MESSAGE = "Unable to Update User...";

    /** {@code :372}, the first {@code STRING} fragment. */
    private static final String UPDATED_MESSAGE_PREFIX = "User ";

    /** {@code :374}, the third {@code STRING} fragment, with a space before the ellipsis. */
    private static final String UPDATED_MESSAGE_SUFFIX = " has been updated ...";

    /** {@code :371} - the one place this program marks a message as a success rather than an error. */
    private static final String COLOUR_GREEN = "DFHGREEN";

    /** {@code :241}. */
    private static final String COLOUR_RED = "DFHRED";

    /** {@code :338}. */
    private static final String COLOUR_NEUTRAL = "DFHNEUTR";

    /** The message the invalid user-type code produces, which has no source literal and is target-only. */
    private static final String USER_TYPE_DOMAIN_MESSAGE =
            "User Type must be A for an administrator or U for a regular user";

    // ----------------------------------------------------------------------------------------------------
    // Concrete failure types, named rather than imported. Three of the nine members of
    // com.cardemo.exception are reachable from this bean but sit outside this suite's declared dependency
    // set, so each is asserted through the com.cardemo.exception.CardDemoException supertype plus its
    // simple name. That keeps the import set inside the declared boundary and, more usefully, keeps the
    // payload contracts of those types where they belong: their own suites own the field-level
    // assertions, and repeating them here would be duplication that Rule 1 Clause C forbids.
    // ----------------------------------------------------------------------------------------------------

    /** Raised on both concurrency routes; see {@code app/cbl/COUSR02C.cbl}:322-331 for the lock it replaces. */
    private static final String CONCURRENT_UPDATE_TYPE = "ConcurrentUpdateException";

    /** Raised when a status has no typed translation - the {@code WHEN OTHER} arms at {@code :346} and {@code :383}. */
    private static final String FILE_ACCESS_TYPE = "FileAccessException";

    /** Raised when the encoder yields no digest, which is an abend rather than a rejected field. */
    private static final String FATAL_PROCESSING_TYPE = "FatalProcessingException";

    /**
     * The message both concurrency routes carry. It is the legacy text of the one outcome this program's
     * condition is, and it is asserted here as an observable message rather than through the outcome
     * enumeration, whose own constants are asserted by the suite that owns that type.
     */
    private static final String DATA_CHANGED_MESSAGE = "Record changed by some one else. Please review";

    /** The tail of the abend reason recorded when the encoder yields nothing, from the production bean. */
    private static final String ENCODER_ABEND_REASON_TAIL = "ENCODER RETURNED NO DIGEST";

    // ----------------------------------------------------------------------------------------------------
    // Record and channel geometry, from app/cpy/CSUSR01Y.cpy:17-23 and app/cbl/COUSR02C.cbl:38.
    // ----------------------------------------------------------------------------------------------------

    /** {@code WS-MESSAGE PIC X(80)} at {@code app/cbl/COUSR02C.cbl}:38 - the work area, not the channel. */
    private static final int WORK_AREA_MESSAGE_WIDTH = 80;

    /** {@code SEC-USR-PWD PIC X(08)} - the source credential field, and the presented value's bound. */
    private static final int SOURCE_CREDENTIAL_WIDTH = 8;

    /** {@code SEC-USR-FILLER PIC X(23)} - declared, unused by any program, and not modelled as a column. */
    private static final int SOURCE_FILLER_WIDTH = 23;

    /**
     * {@code RECORDSIZE(80,80)} on the cluster, which is what {@code LENGTH OF SEC-USER-DATA} resolves to on
     * the read at {@code :325} and the rewrite at {@code :363}.
     */
    private static final int USRSEC_RECORD_LENGTH = 80;

    /** The digest column's width - the one field that is deliberately not byte-parity with the source. */
    private static final int DIGEST_COLUMN_WIDTH = 60;

    /** A seven-character identifier: shorter than the field, which the source bounds only from above. */
    private static final String SHORT_USER_ID = "USER000";

    /** A single-character credential: {@code :198} tests emptiness, so one character clears it. */
    private static final String MINIMAL_CREDENTIAL = "q";

    /** A user class outside {@code 'A'} and {@code 'U'} - non-empty, so all five guards pass. */
    private static final String UNMAPPABLE_USER_TYPE = "X";

    /** An eight-character identifier carrying relational metacharacters, to prove parameter binding. */
    private static final String HOSTILE_USER_ID = "A' OR '1";

    /** Tokens that would betray query text assembled inside the bean; none appears in any source literal. */
    private static final List<String> QUERY_TOKENS = List.of("select", "from", "where", ";", "--");

    // ----------------------------------------------------------------------------------------------------
    // Time.
    // ----------------------------------------------------------------------------------------------------

    /** The instant every seed fixture carries. */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-06-10T19:27:53Z");

    /** {@code MM/DD/YY} as {@code :305-309} assembles it, the year taken from {@code WS-CURDATE-YEAR(3:2)}. */
    private static final String EXPECTED_HEADER_DATE = "06/10/22";

    /** {@code HH:MM:SS} as {@code :311-315} assembles it. */
    private static final String EXPECTED_HEADER_TIME = "19:27:53";

    // ----------------------------------------------------------------------------------------------------
    // Collaborators.
    // ----------------------------------------------------------------------------------------------------

    @Mock
    private UserSecurityRepository userSecurityRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    /** Real: a pure function with a no-argument constructor, so a double would prove less. */
    private FileStatusMapper fileStatusMapper;

    private UserUpdateService service;

    /** Assembles the bean over two doubles, the real status mapper and a fixed clock. */
    @BeforeEach
    void setUp() {
        this.fileStatusMapper = new FileStatusMapper();
        this.service = new UserUpdateService(this.userSecurityRepository, this.passwordEncoder,
                this.fileStatusMapper, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
    }

    // -----------------------------------------------------------------------------------------------------
    // Fixtures and helpers. Every helper is used; an unused one would be dead code under Rule 1 Clause B.
    // -----------------------------------------------------------------------------------------------------

    /**
     * Assembles a synthetic BCrypt-shaped digest at the contractual cost, so that no digest literal exists in
     * this file and {@code UserSecurity}'s own strength guard still accepts what is persisted.
     *
     * @param body thirty-one characters standing where a digest body would sit; must not be {@code null}
     * @return a sixty-character value the entity contract admits
     */
    private static String syntheticDigest(final String body) {
        return DIGEST_FIELD_MARKER + DIGEST_VERSION_TAG + DIGEST_FIELD_MARKER + CONTRACTUAL_COST_FACTOR
                + DIGEST_FIELD_MARKER + SYNTHETIC_SALT + body;
    }

    /**
     * The digest the store is taken to hold.
     *
     * @return the stored digest
     */
    private static String storedDigest() {
        return syntheticDigest(SYNTHETIC_BODY);
    }

    /**
     * The digest the encoder is taken to return for a changed credential.
     *
     * @return the replacement digest
     */
    private static String replacementDigest() {
        return syntheticDigest(REPLACEMENT_BODY);
    }

    /**
     * Builds a stored row in the {@code CSUSR01Y} shape.
     *
     * @param firstName the first name; must not be {@code null}
     * @param lastName  the last name; must not be {@code null}
     * @param userType  the user class; must not be {@code null}
     * @return the row
     */
    private static UserSecurity storedUser(final String firstName, final String lastName,
            final UserType userType) {
        return new UserSecurity(USER_ID, firstName, lastName, storedDigest(), userType);
    }

    /**
     * The stored row as every test that does not vary it expects to find it.
     *
     * @return the row
     */
    private static UserSecurity storedUser() {
        return storedUser(STORED_FIRST_NAME, STORED_LAST_NAME, UserType.USER);
    }

    /**
     * Builds a twelve-component map area. The six header components carry values that differ from what the
     * service computes, so any assertion on a returned header proves they were overwritten.
     *
     * @param userId    the identifier component; may be {@code null}
     * @param firstName the first-name component; may be {@code null}
     * @param lastName  the last-name component; may be {@code null}
     * @param password  the credential component; may be {@code null}
     * @param userType  the user-class component; may be {@code null}
     * @return the map area
     */
    private static UserUpdateRequest request(final String userId, final String firstName,
            final String lastName, final String password, final String userType) {
        return new UserUpdateRequest("ZZZZ", "submitted-title-one", "01/01/00", "ZZZZZZZZ",
                "submitted-title-two", "00:00:00", userId, firstName, lastName, password, userType,
                "submitted-message");
    }

    /**
     * A map area that resubmits the stored values unchanged, with the credential the store's digest matches.
     *
     * @return the map area
     */
    private static UserUpdateRequest unchangedRequest() {
        return request(USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                STORED_USER_TYPE);
    }

    /**
     * The snapshot of what a caller was last shown, matching the stored row.
     *
     * @return the snapshot
     */
    private static UserSnapshot matchingSnapshot() {
        return new UserSnapshot(STORED_FIRST_NAME, STORED_LAST_NAME, STORED_USER_TYPE);
    }

    /**
     * Arranges the read to find the row as stored.
     */
    private void arrangeStoredRow() {
        when(this.userSecurityRepository.findById(USER_ID)).thenReturn(Optional.of(storedUser()));
    }

    /**
     * Arranges the encoder to report that the presented credential matches the stored digest, which is the
     * unchanged-credential path of {@code :227}.
     */
    private void arrangeCredentialMatches() {
        when(this.passwordEncoder.matches(PRESENTED_CREDENTIAL, storedDigest())).thenReturn(true);
    }

    /**
     * Reads a private static constant off the service, so a declared value is asserted against the
     * production declaration rather than a copy of it.
     *
     * @param name the field name; must not be {@code null}
     * @return the declared value
     * @throws ReflectiveOperationException if the field is absent, which is itself the finding
     */
    private static Object declaredConstant(final String name) throws ReflectiveOperationException {
        final Field field = UserUpdateService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    /**
     * Names the concrete failure type raised, so a subtype can be asserted precisely while the import set
     * stays inside this suite's declared dependency boundary.
     *
     * @param failure the raised failure; must not be {@code null}
     * @return the simple name of its runtime class
     */
    private static String typeOf(final CardDemoException failure) {
        return failure.getClass().getSimpleName();
    }

    /**
     * Reads an abend field off a failure raised on the encoder path. The accessor is declared by a subtype
     * outside this suite's dependency boundary, so it is reached by name; the field-level contract of that
     * subtype is asserted by the suite that owns it, and only its arrival here is asserted.
     *
     * @param failure  the raised failure; must not be {@code null}
     * @param accessor the accessor name; must not be {@code null}
     * @return the value the accessor returns
     * @throws ReflectiveOperationException if the accessor is absent, which is itself the finding
     */
    private static Object abendField(final CardDemoException failure, final String accessor)
            throws ReflectiveOperationException {
        final Method method = failure.getClass().getMethod(accessor);
        return method.invoke(failure);
    }

    /**
     * The component names a record declares, in declaration order.
     *
     * @param carrier the record type; must not be {@code null}
     * @return the component names
     */
    private static List<String> componentNames(final Class<?> carrier) {
        return Arrays.stream(carrier.getRecordComponents()).map(component -> component.getName()).toList();
    }

    /**
     * Every value of every {@code String} constant the service declares, so a structural claim is made
     * against the production declarations rather than against a transcription of them.
     *
     * @return the declared string constants
     */
    private static List<String> declaredStringConstants() {
        return Arrays.stream(UserUpdateService.class.getDeclaredFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .filter(field -> field.getType() == String.class)
                .map(field -> {
                    field.setAccessible(true);
                    try {
                        return (String) field.get(null);
                    } catch (final IllegalAccessException unreachable) {
                        throw new AssertionError("a static field made accessible refused to be read",
                                unreachable);
                    }
                })
                .filter(value -> value != null)
                .toList();
    }

    /**
     * The simple names of the annotations a member carries, so an absence can be asserted without importing
     * the annotation types whose absence is the point.
     *
     * @param annotations the annotations present; must not be {@code null}
     * @return their simple names
     */
    private static List<String> annotationNames(final Annotation[] annotations) {
        return Arrays.stream(annotations).map(a -> a.annotationType().getSimpleName()).toList();
    }

    /**
     * Locates a public entry point by name.
     *
     * @param name the method name; must not be {@code null}
     * @return the declared method
     */
    private static Method entryPoint(final String name) {
        return Arrays.stream(UserUpdateService.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no public entry point named " + name));
    }

    /**
     * Arranges the store to accept a rewrite and hand back the row it was given, which is what a positioned
     * {@code REWRITE} at {@code app/cbl/COUSR02C.cbl}:360-366 does.
     */
    private void arrangeRewriteAccepted() {
        when(this.userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /**
     * Captures the row handed to the store by the rewrite.
     *
     * @return the persisted row
     */
    private UserSecurity capturePersistedRow() {
        final ArgumentCaptor<UserSecurity> written = ArgumentCaptor.forClass(UserSecurity.class);
        verify(this.userSecurityRepository).saveAndFlush(written.capture());
        return written.getValue();
    }

    // =====================================================================================================
    // 1. Construction
    // =====================================================================================================

    /** Constructor injection only, with all four collaborators refused when absent. */
    @Nested
    @DisplayName("1. Construction - four collaborators, each refused when absent")
    class Construction {

        @Test
        @DisplayName("the security store is required")
        void theSecurityStoreIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new UserUpdateService(null, passwordEncoder, fileStatusMapper,
                            Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)))
                    .withMessage("userSecurityRepository must not be null");
        }

        @Test
        @DisplayName("the encoder is required, because the credential comparison cannot run without it")
        void theEncoderIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new UserUpdateService(userSecurityRepository, null, fileStatusMapper,
                            Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)))
                    .withMessage("passwordEncoder must not be null");
        }

        @Test
        @DisplayName("the status mapper is required")
        void theStatusMapperIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new UserUpdateService(userSecurityRepository, passwordEncoder, null,
                            Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)))
                    .withMessage("fileStatusMapper must not be null");
        }

        @Test
        @DisplayName("the time source is required, so no path can reach a wall clock")
        void theTimeSourceIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new UserUpdateService(userSecurityRepository, passwordEncoder,
                            fileStatusMapper, null))
                    .withMessage("clock must not be null");
        }

        @Test
        @DisplayName("every instance field is private and final, so no turn leaves residue for the next")
        void everyInstanceFieldIsPrivateAndFinal() {
            final List<Field> mutable = Arrays.stream(UserUpdateService.class.getDeclaredFields())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !Modifier.isFinal(field.getModifiers())
                            || !Modifier.isPrivate(field.getModifiers()))
                    .toList();
            assertThat(mutable)
                    .as("WS-ERR-FLG, WS-MESSAGE and the record area became a per-turn work object, not state")
                    .isEmpty();
        }
    }

    // =====================================================================================================
    // 2. Paragraph correspondence and transaction boundaries
    // =====================================================================================================

    /**
     * Eleven paragraph labels, eleven private methods. {@code app/cbl/COUSR02C.cbl} declares
     * {@code MAIN-PARA} at :82, {@code PROCESS-ENTER-KEY} at :143, {@code UPDATE-USER-INFO} at :177,
     * {@code RETURN-TO-PREV-SCREEN} at :250, {@code SEND-USRUPD-SCREEN} at :266,
     * {@code RECEIVE-USRUPD-SCREEN} at :283, {@code POPULATE-HEADER-INFO} at :296,
     * {@code READ-USER-SEC-FILE} at :320, {@code UPDATE-USER-SEC-FILE} at :358,
     * {@code CLEAR-CURRENT-SCREEN} at :395 and {@code INITIALIZE-ALL-FIELDS} at :403.
     */
    @Nested
    @DisplayName("2. Paragraph correspondence - eleven labels, and the write boundaries that back them")
    class ParagraphCorrespondence {

        @Test
        @DisplayName("all eleven paragraphs have a private counterpart, none consolidated away")
        void allElevenParagraphsHaveAPrivateCounterpart() {
            final List<String> expected = List.of("mainPara", "processEnterKey", "updateUserInfo",
                    "returnToPrevScreen", "sendUsrupdScreen", "receiveUsrupdScreen", "populateHeaderInfo",
                    "readUserSecFile", "updateUserSecFile", "clearCurrentScreen", "initializeAllFields");
            final List<String> declared = Arrays.stream(UserUpdateService.class.getDeclaredMethods())
                    .filter(method -> Modifier.isPrivate(method.getModifiers()))
                    .map(Method::getName)
                    .distinct()
                    .toList();
            assertThat(declared).containsAll(expected);
        }

        @Test
        @DisplayName("five public entry points exist, one per way the source can be reached")
        void fivePublicEntryPointsExist() {
            final List<String> publicMethods = Arrays.stream(UserUpdateService.class.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .map(Method::getName)
                    .distinct()
                    .sorted()
                    .toList();
            assertThat(publicMethods).containsExactly("lookupUser", "openScreen", "openWithoutContext",
                    "submitScreen", "updateUser");
        }

        @ParameterizedTest(name = "{0} declares @Transactional(rollbackFor = Exception.class)")
        @DisplayName("every entry point that can write declares the rollback-for-anything boundary")
        @CsvSource({"openScreen", "lookupUser", "updateUser", "submitScreen"})
        void everyWriteCapableEntryPointDeclaresTheBoundary(final String methodName) {
            final Method entryPoint = Arrays.stream(UserUpdateService.class.getDeclaredMethods())
                    .filter(method -> method.getName().equals(methodName))
                    .findFirst()
                    .orElseThrow();
            final org.springframework.transaction.annotation.Transactional declared =
                    entryPoint.getAnnotation(org.springframework.transaction.annotation.Transactional.class);

            assertThat(declared)
                    .as("the read-for-update and the rewrite must sit in one unit of work")
                    .isNotNull();
            assertThat(declared.rollbackFor())
                    .as("Transformation Rule 13: a checked failure must back the write out too")
                    .containsExactly(Exception.class);
        }

        @Test
        @DisplayName("the attention identifier has exactly six constants, matching EVALUATE EIBAID")
        void theAttentionIdentifierHasExactlySixConstants() {
            assertThat(AttentionIdentifier.values())
                    .as("app/cbl/COUSR02C.cbl:108-131 has six arms: ENTER, PF3, PF4, PF5, PF12 and OTHER")
                    .containsExactly(AttentionIdentifier.ENTER, AttentionIdentifier.PF3,
                            AttentionIdentifier.PF4, AttentionIdentifier.PF5, AttentionIdentifier.PF12,
                            AttentionIdentifier.OTHER);
        }
    }

    // =====================================================================================================
    // 3. The attention-identifier arms - app/cbl/COUSR02C.cbl:108-131, INCLUDING THE PF3 QUIRK
    // =====================================================================================================

    /**
     * Six arms. The one that matters most is {@code PF3}: it performs {@code UPDATE-USER-INFO} at {@code :112}
     * and only then transfers, so the key a user presses to leave <em>writes the record</em>. {@code PF12} at
     * {@code :124-126} transfers without performing it.
     */
    @Nested
    @DisplayName("3. The EIBAID arms :108-131 - PF3 SAVES before leaving, PF12 leaves without saving")
    class AttentionIdentifierArms {

        @Test
        @DisplayName("PF3 WRITES THE RECORD and then reports the admin menu - the preserved quirk")
        void pf3WritesTheRecordAndThenLeaves() {
            arrangeStoredRow();
            when(passwordEncoder.matches(REPLACEMENT_CREDENTIAL, storedDigest())).thenReturn(false);
            when(passwordEncoder.encode(REPLACEMENT_CREDENTIAL)).thenReturn(replacementDigest());
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            final UserUpdateScreen screen = service.submitScreen(AttentionIdentifier.PF3,
                    request(USER_ID, NEW_FIRST_NAME, STORED_LAST_NAME, REPLACEMENT_CREDENTIAL,
                            STORED_USER_TYPE),
                    null);

            verify(userSecurityRepository).saveAndFlush(any(UserSecurity.class));
            assertThat(screen.navigationTarget())
                    .as(":113-119 - the transfer happens after the write, not instead of it")
                    .isEqualTo(ADMIN_MENU_PROGRAM);
            assertThat(screen.transferRequested()).isTrue();
            assertThat(screen.userModified()).isTrue();
        }

        @Test
        @DisplayName("both leaving arms resolve to the admin menu, :116-117 having no counterpart")
        void bothLeavingArmsResolveToTheAdminMenu() {
            arrangeStoredRow();
            arrangeCredentialMatches();

            final UserUpdateScreen afterExit = service.submitScreen(AttentionIdentifier.PF3,
                    unchangedRequest(), null);
            final UserUpdateScreen afterCancel = service.submitScreen(AttentionIdentifier.PF12,
                    unchangedRequest(), null);

            assertThat(afterExit.navigationTarget())
                    .as(":113-118 tests CDEMO-FROM-PROGRAM, a communication-area field with no counterpart "
                            + "under the stateless mandate, so only the :114 outcome is reachable and the "
                            + "ELSE at :116-117 cannot be exercised")
                    .isEqualTo(ADMIN_MENU_PROGRAM);
            assertThat(afterCancel.navigationTarget())
                    .as(":125 moves 'COADM01C' unconditionally, with no guard of any kind")
                    .isEqualTo(ADMIN_MENU_PROGRAM);
        }

        @Test
        @DisplayName("PF12 leaves WITHOUT writing, which is the arm that behaves as a cancel")
        void pf12LeavesWithoutWriting() {
            final UserUpdateScreen screen = service.submitScreen(AttentionIdentifier.PF12,
                    request(USER_ID, NEW_FIRST_NAME, NEW_LAST_NAME, REPLACEMENT_CREDENTIAL,
                            OTHER_USER_TYPE),
                    null);

            verifyNoInteractions(userSecurityRepository, passwordEncoder);
            assertThat(screen.navigationTarget()).isEqualTo(ADMIN_MENU_PROGRAM);
            assertThat(screen.transferRequested()).isTrue();
            assertThat(screen.userModified())
                    .as(":124-126 performs no UPDATE-USER-INFO at all")
                    .isFalse();
        }

        @Test
        @DisplayName("PF3 and PF12 differ in exactly one observable respect: whether the store was reached")
        void pf3AndPf12DifferOnlyInReachingTheStore() {
            arrangeStoredRow();
            arrangeCredentialMatches();

            final UserUpdateScreen viaPf3 =
                    service.submitScreen(AttentionIdentifier.PF3, unchangedRequest(), null);
            final UserUpdateScreen viaPf12 =
                    service.submitScreen(AttentionIdentifier.PF12, unchangedRequest(), null);

            verify(userSecurityRepository).findById(USER_ID);
            assertThat(viaPf3.navigationTarget()).isEqualTo(viaPf12.navigationTarget());
            assertThat(viaPf3.errorMessage())
                    .as("PF3 ran the whole update path and found nothing changed")
                    .isEqualTo(NO_CHANGE_MESSAGE);
            assertThat(viaPf12.errorMessage())
                    .as("PF12 ran nothing, so the message field carries what the caller submitted")
                    .isEqualTo("submitted-message");
        }

        @Test
        @DisplayName("PF4 clears every input field and parks the cursor on the identifier")
        void pf4ClearsEveryInputField() {
            final UserUpdateScreen screen = service.submitScreen(AttentionIdentifier.PF4,
                    request(USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                            STORED_USER_TYPE),
                    null);

            assertThat(screen.userId()).isEmpty();
            assertThat(screen.firstName()).isEmpty();
            assertThat(screen.lastName()).isEmpty();
            assertThat(screen.userType()).isEmpty();
            assertThat(screen.errorMessage()).isEmpty();
            assertThat(screen.cursorField()).isEqualTo(CURSOR_USER_ID);
            assertThat(screen.navigationTarget()).isNull();
            verifyNoInteractions(userSecurityRepository, passwordEncoder);
        }

        @Test
        @DisplayName("PF5 writes and stays, reporting no navigation target")
        void pf5WritesAndStays() {
            arrangeStoredRow();
            when(passwordEncoder.matches(PRESENTED_CREDENTIAL, storedDigest())).thenReturn(true);
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            final UserUpdateScreen screen = service.submitScreen(AttentionIdentifier.PF5,
                    request(USER_ID, NEW_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                            STORED_USER_TYPE),
                    null);

            verify(userSecurityRepository).saveAndFlush(any(UserSecurity.class));
            assertThat(screen.navigationTarget())
                    .as(":122-123 performs the update and nothing else")
                    .isNull();
            assertThat(screen.transferRequested()).isFalse();
        }

        @Test
        @DisplayName("ENTER looks the record up and reports the save hint, writing nothing")
        void enterLooksUpAndReportsTheSaveHint() {
            arrangeStoredRow();

            final UserUpdateScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, request(USER_ID, null, null, null, null),
                            null);

            verify(userSecurityRepository).findById(USER_ID);
            verify(userSecurityRepository, never()).saveAndFlush(any(UserSecurity.class));
            assertThat(screen.errorMessage()).isEqualTo(SAVE_HINT_MESSAGE);
            assertThat(screen.messageColour()).isEqualTo(COLOUR_NEUTRAL);
        }

        @Test
        @DisplayName("the ENTER arm paints the record's names and class but NEVER its credential")
        void theEnterArmPaintsNamesButNeverTheCredential() {
            arrangeStoredRow();

            final UserUpdateScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, request(USER_ID, null, null, null, null),
                            null);

            assertThat(screen.firstName()).isEqualTo(STORED_FIRST_NAME);
            assertThat(screen.lastName()).isEqualTo(STORED_LAST_NAME);
            assertThat(screen.userType()).isEqualTo(STORED_USER_TYPE);
            assertThat(Arrays.stream(UserUpdateScreen.class.getRecordComponents())
                    .map(component -> component.getName())
                    .toList())
                    .as(":169 MOVE SEC-USR-PWD TO PASSWDI is deliberately NOT reproduced, so the response "
                            + "record must declare no credential component at all")
                    .doesNotContain("password", "passwordHash", "secUsrPwd", "credential");
        }

        @Test
        @DisplayName("any other key produces the shared invalid-key message and writes nothing")
        void anyOtherKeyProducesTheSharedInvalidKeyMessage() {
            final UserUpdateScreen screen = service.submitScreen(AttentionIdentifier.OTHER,
                    request(USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                            STORED_USER_TYPE),
                    null);

            assertThat(screen.errorMessage()).isEqualTo(INVALID_KEY_MESSAGE);
            assertThat(screen.navigationTarget()).isNull();
            verifyNoInteractions(userSecurityRepository, passwordEncoder);
        }

        @Test
        @DisplayName("that message is the SHARED corpus constant of CSMSG01Y, not a literal invented here")
        void theInvalidKeyMessageComesFromTheSharedConstant() throws ReflectiveOperationException {
            final UserUpdateScreen screen = service.submitScreen(AttentionIdentifier.OTHER,
                    request(USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                            STORED_USER_TYPE),
                    null);

            assertThat(screen.errorMessage())
                    .as(":129 moves CCDA-MSG-INVALID-KEY, a constant of app/cpy/CSMSG01Y.cpy that is shared "
                            + "corpus-wide, whereas every other message on this screen is a literal of this "
                            + "program alone. Asserting it against the production declaration rather than "
                            + "against a copy is what stops the two drifting apart")
                    .isEqualTo(declaredConstant("INVALID_KEY_MESSAGE"));
        }

        @Test
        @DisplayName("the attention identifier is required")
        void theAttentionIdentifierIsRequired() {
            final UserUpdateRequest submitted = unchangedRequest();
            assertThatNullPointerException()
                    .isThrownBy(() -> service.submitScreen(null, submitted, null))
                    .withMessage("aid must not be null");
        }

        @ParameterizedTest(name = "{0} requires a map area")
        @DisplayName("the three arms that read or write the record require a map area")
        @CsvSource({"ENTER", "PF3", "PF5"})
        void theReadingAndWritingArmsRequireAMapArea(final String aidName) {
            final AttentionIdentifier aid = AttentionIdentifier.valueOf(aidName);
            assertThatNullPointerException()
                    .isThrownBy(() -> service.submitScreen(aid, null, null))
                    .withMessage("request must not be null when the attention identifier reads or writes "
                            + "the record");
        }

        @ParameterizedTest(name = "{0} tolerates an absent map area")
        @DisplayName("the two arms that neither read nor write tolerate an absent map area")
        @CsvSource({"PF4", "PF12"})
        void theOtherArmsTolerateAnAbsentMapArea(final String aidName) {
            final AttentionIdentifier aid = AttentionIdentifier.valueOf(aidName);

            final UserUpdateScreen screen = service.submitScreen(aid, null, null);

            assertThat(screen).isNotNull();
            verifyNoInteractions(userSecurityRepository, passwordEncoder);
        }
    }

    // =====================================================================================================
    // 4. Entry modes - app/cbl/COUSR02C.cbl:90-105
    // =====================================================================================================

    /** The no-communication-area arm and the first-display arm, with and without a preselected identifier. */
    @Nested
    @DisplayName("4. Entry modes :90-105 - no commarea, first display, and the preselected identifier")
    class EntryModes {

        @Test
        @DisplayName("an absent communication area leaves for the sign-on program")
        void anAbsentCommunicationAreaLeavesForSignOn() {
            final UserUpdateScreen screen = service.openWithoutContext();

            assertThat(screen.navigationTarget())
                    .as(":90-92 MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM")
                    .isEqualTo(SIGN_ON_PROGRAM);
            assertThat(screen.transferRequested()).isTrue();
            verifyNoInteractions(userSecurityRepository, passwordEncoder);
        }

        @Test
        @DisplayName("a first display with no preselected identifier paints an empty form and reads nothing")
        void aFirstDisplayWithNoPreselectionReadsNothing() {
            final UserUpdateScreen screen = service.openScreen(null);

            assertThat(screen.cursorField())
                    .as(":98 MOVE -1 TO USRIDINL")
                    .isEqualTo(CURSOR_USER_ID);
            assertThat(screen.userId())
                    .as(":97 MOVE LOW-VALUES TO COUSR2AO clears the whole output map, and a cleared "
                            + "fixed-width field carries no content")
                    .isEmpty();
            assertThat(screen.firstName()).isEmpty();
            assertThat(screen.lastName()).isEmpty();
            assertThat(screen.userType()).isEmpty();
            assertThat(screen.errorMessage()).isEmpty();
            assertThat(screen.navigationTarget()).isNull();
            verifyNoInteractions(userSecurityRepository, passwordEncoder);
        }

        @Test
        @DisplayName("a first display with a preselected identifier performs the lookup at :103")
        void aFirstDisplayWithAPreselectionPerformsTheLookup() {
            arrangeStoredRow();

            final UserUpdateScreen screen = service.openScreen(USER_ID);

            verify(userSecurityRepository).findById(USER_ID);
            assertThat(screen.userId()).isEqualTo(USER_ID);
            assertThat(screen.firstName()).isEqualTo(STORED_FIRST_NAME);
            assertThat(screen.errorMessage()).isEqualTo(SAVE_HINT_MESSAGE);
        }

        @ParameterizedTest(name = "a preselection of [{0}] is treated as absent")
        @DisplayName("a blank or unset preselection does not trigger the lookup")
        @ValueSource(strings = {"", " ", "   ", "\u0000"})
        void aBlankPreselectionDoesNotTriggerTheLookup(final String preselection) {
            final UserUpdateScreen screen = service.openScreen(preselection);

            assertThat(screen).isNotNull();
            verifyNoInteractions(userSecurityRepository);
        }

        @Test
        @DisplayName("the lookup entry point reaches the same read as the ENTER arm")
        void theLookupEntryPointReachesTheSameRead() {
            arrangeStoredRow();

            final UserUpdateScreen screen = service.lookupUser(USER_ID);

            verify(userSecurityRepository).findById(USER_ID);
            assertThat(screen.errorMessage()).isEqualTo(SAVE_HINT_MESSAGE);
            assertThat(screen.messageColour()).isEqualTo(COLOUR_NEUTRAL);
        }

        @Test
        @DisplayName("the update entry point requires a map area")
        void theUpdateEntryPointRequiresAMapArea() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.updateUser(null, null))
                    .withMessage("request must not be null");
        }
    }

    // =====================================================================================================
    // 5. The five emptiness guards - app/cbl/COUSR02C.cbl:179-213
    // =====================================================================================================

    /** {@code EVALUATE TRUE} with five arms in a fixed order, and a {@code WHEN OTHER} that parks the cursor. */
    @Nested
    @DisplayName("5. The five emptiness guards :179-213 - source order, first match wins, no content checks")
    class OrderedEmptinessGuards {

        @Test
        @DisplayName("a wholly empty form reports the IDENTIFIER, the first arm")
        void aWhollyEmptyFormReportsTheIdentifier() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.updateUser(request(null, null, null, null, null), null))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(USER_ID_REQUIRED_MESSAGE);
                        assertThat(failure.getFieldName()).isEqualTo("userId");
                        assertThat(failure.getFailureKind())
                                .isEqualTo(ValidationException.FailureKind.BLANK);
                    });
            verifyNoInteractions(userSecurityRepository, passwordEncoder);
        }

        @Test
        @DisplayName("the first name is reported second")
        void theFirstNameIsReportedSecond() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.updateUser(request(USER_ID, null, null, null, null), null))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(FIRST_NAME_REQUIRED_MESSAGE);
                        assertThat(failure.getFieldName()).isEqualTo("firstName");
                    });
        }

        @Test
        @DisplayName("the last name is reported third")
        void theLastNameIsReportedThird() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.updateUser(
                            request(USER_ID, STORED_FIRST_NAME, null, null, null), null))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(LAST_NAME_REQUIRED_MESSAGE);
                        assertThat(failure.getFieldName()).isEqualTo("lastName");
                    });
        }

        @Test
        @DisplayName("the credential is reported fourth, by field NAME and never by value")
        void theCredentialIsReportedFourth() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.updateUser(
                            request(USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME, null, null), null))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(CREDENTIAL_REQUIRED_MESSAGE);
                        assertThat(failure.getFieldName()).isEqualTo("password");
                        assertThat(failure.getMessage()).doesNotContain(PRESENTED_CREDENTIAL);
                    });
        }

        @Test
        @DisplayName("the user type is reported fifth and last")
        void theUserTypeIsReportedFifth() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.updateUser(request(USER_ID, STORED_FIRST_NAME,
                            STORED_LAST_NAME, PRESENTED_CREDENTIAL, null), null))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(USER_TYPE_REQUIRED_MESSAGE);
                        assertThat(failure.getFieldName()).isEqualTo("userType");
                    });
        }

        @ParameterizedTest(name = "a field of [{0}] counts as empty")
        @DisplayName("SPACES, LOW-VALUES and an absent value are all empty; nothing else is")
        @ValueSource(strings = {"", " ", "    ", "\u0000", "\u0000\u0000"})
        void spacesLowValuesAndAbsentAreAllEmpty(final String value) {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.updateUser(
                            request(USER_ID, value, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                                    STORED_USER_TYPE), null))
                    .withMessage(FIRST_NAME_REQUIRED_MESSAGE);
        }

        @Test
        @DisplayName("the guards test emptiness only - no content validation of any kind is added")
        void theGuardsTestEmptinessOnly() {
            arrangeStoredRow();
            when(passwordEncoder.matches("!", storedDigest())).thenReturn(false);
            when(passwordEncoder.encode("!")).thenReturn(replacementDigest());
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // A one-character name and a one-character credential are accepted: the source checks that the
            // fields are not empty and checks nothing else whatsoever.
            final UserUpdateScreen screen = service.updateUser(
                    request(USER_ID, "X", "Y", "!", STORED_USER_TYPE), null);

            assertThat(screen.userModified()).isTrue();
        }

        @Test
        @DisplayName("the five literals are byte exact, capital N O T of can NOT included")
        void theFiveLiteralsAreByteExact() {
            assertThat(USER_ID_REQUIRED_MESSAGE).isEqualTo("User ID can NOT be empty...");
            assertThat(FIRST_NAME_REQUIRED_MESSAGE).isEqualTo("First Name can NOT be empty...");
            assertThat(LAST_NAME_REQUIRED_MESSAGE).isEqualTo("Last Name can NOT be empty...");
            assertThat(CREDENTIAL_REQUIRED_MESSAGE).isEqualTo("Password can NOT be empty...");
            assertThat(USER_TYPE_REQUIRED_MESSAGE).isEqualTo("User Type can NOT be empty...");
        }

        @Test
        @DisplayName("the ENTER arm applies only the identifier guard, the other four being blanked first")
        void theEnterArmAppliesOnlyTheIdentifierGuard() {
            // :158-161 blanks the four other fields before the read, so PROCESS-ENTER-KEY has one guard.
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER,
                            request(null, STORED_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                                    STORED_USER_TYPE), null))
                    .withMessage(USER_ID_REQUIRED_MESSAGE);
        }
    }

    // =====================================================================================================
    // 6. Width enforcement - a Java-only guard, because the 3270 map made over-length input impossible
    // =====================================================================================================

    /**
     * The symbolic map physically could not deliver more characters than the field declared. An HTTP caller
     * can, so the values are rejected rather than truncated - truncation would silently store a different
     * value than the caller supplied.
     */
    @Nested
    @DisplayName("6. Width enforcement - reject over-length input, never truncate it")
    class WidthEnforcement {

        @ParameterizedTest(name = "an over-length {0} is rejected")
        @DisplayName("each of the five fields is rejected when it exceeds its declared width")
        @CsvSource({
            "userId,    9,  8",
            "firstName, 21, 20",
            "lastName,  21, 20",
            "password,  9,  8",
            "userType,  2,  1"})
        void eachFieldIsRejectedWhenTooLong(final String fieldName, final int suppliedLength,
                final int declaredWidth) {
            final String tooLong = "X".repeat(suppliedLength);
            final UserUpdateRequest overLength = switch (fieldName) {
                case "userId" -> request(tooLong, STORED_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                        STORED_USER_TYPE);
                case "firstName" -> request(USER_ID, tooLong, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                        STORED_USER_TYPE);
                case "lastName" -> request(USER_ID, STORED_FIRST_NAME, tooLong, PRESENTED_CREDENTIAL,
                        STORED_USER_TYPE);
                case "password" -> request(USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME, tooLong,
                        STORED_USER_TYPE);
                default -> request(USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                        tooLong);
            };

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.updateUser(overLength, null))
                    .satisfies(failure -> {
                        assertThat(failure.getFieldName()).isEqualTo(fieldName);
                        assertThat(failure.getMessage())
                                .isEqualTo(fieldName + " must be at most " + declaredWidth + " characters");
                        assertThat(failure.getFailureKind())
                                .isEqualTo(ValidationException.FailureKind.INVALID);
                    });
            verifyNoInteractions(userSecurityRepository);
        }

        @Test
        @DisplayName("a value exactly on its declared width is accepted, the guard being exclusive")
        void aValueExactlyOnItsWidthIsAccepted() {
            arrangeStoredRow();
            final String exactlyTwenty = "X".repeat(20);
            when(passwordEncoder.matches(PRESENTED_CREDENTIAL, storedDigest())).thenReturn(true);
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            final UserUpdateScreen screen = service.updateUser(
                    request(USER_ID, exactlyTwenty, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                            STORED_USER_TYPE), null);

            assertThat(screen.firstName()).isEqualTo(exactlyTwenty);
        }
    }

    // =====================================================================================================
    // 7. The two-layer concurrency guard
    // =====================================================================================================

    /**
     * The source held the record locked for the whole conversation with {@code EXEC CICS READ ... UPDATE}. A
     * stateless target cannot, so a caller-supplied snapshot is compared at business level and the provider's
     * own optimistic failure is caught at store level. Both report the same outcome; the cause distinguishes
     * them.
     */
    @Nested
    @DisplayName("7. Concurrency - a business-level snapshot and a store-level version, both reporting one "
            + "outcome")
    class ConcurrencyGuard {

        @Test
        @DisplayName("a first name that changed under the caller is refused before any write")
        void aChangedFirstNameIsRefused() {
            arrangeStoredRow();

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.updateUser(unchangedRequest(),
                            new UserSnapshot("STALE", STORED_LAST_NAME, STORED_USER_TYPE)))
                    .satisfies(failure -> {
                        assertThat(typeOf(failure)).isEqualTo(CONCURRENT_UPDATE_TYPE);
                        assertThat(failure.getMessage()).isEqualTo(DATA_CHANGED_MESSAGE);
                        assertThat(failure.getCause())
                                .as("the business-level layer has no provider failure to carry")
                                .isNull();
                    });
            verify(userSecurityRepository, never()).saveAndFlush(any(UserSecurity.class));
        }

        @Test
        @DisplayName("a last name that changed under the caller is refused")
        void aChangedLastNameIsRefused() {
            arrangeStoredRow();

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.updateUser(unchangedRequest(),
                            new UserSnapshot(STORED_FIRST_NAME, "STALE", STORED_USER_TYPE)))
                    .satisfies(failure -> assertThat(typeOf(failure))
                            .isEqualTo(CONCURRENT_UPDATE_TYPE));
        }

        @Test
        @DisplayName("a user class that changed under the caller is refused")
        void aChangedUserClassIsRefused() {
            arrangeStoredRow();

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.updateUser(unchangedRequest(),
                            new UserSnapshot(STORED_FIRST_NAME, STORED_LAST_NAME, OTHER_USER_TYPE)))
                    .satisfies(failure -> assertThat(typeOf(failure))
                            .isEqualTo(CONCURRENT_UPDATE_TYPE));
        }

        @Test
        @DisplayName("a matching snapshot proceeds, so the guard admits the unchanged case")
        void aMatchingSnapshotProceeds() {
            arrangeStoredRow();
            arrangeCredentialMatches();

            final UserUpdateScreen screen = service.updateUser(unchangedRequest(), matchingSnapshot());

            assertThat(screen.errorMessage()).isEqualTo(NO_CHANGE_MESSAGE);
        }

        @Test
        @DisplayName("a snapshot compares on fixed width, so trailing spaces are not a conflict")
        void aSnapshotComparesOnFixedWidth() {
            arrangeStoredRow();
            arrangeCredentialMatches();

            final UserUpdateScreen screen = service.updateUser(unchangedRequest(),
                    new UserSnapshot(STORED_FIRST_NAME + "               ",
                            STORED_LAST_NAME + "               ", STORED_USER_TYPE));

            assertThat(screen.errorMessage())
                    .as("SEC-USR-FNAME is PIC X(20), so the padded and unpadded forms are one value")
                    .isEqualTo(NO_CHANGE_MESSAGE);
        }

        @Test
        @DisplayName("an absent snapshot skips the business-level layer, leaving only the store-level one")
        void anAbsentSnapshotSkipsTheBusinessLevelLayer() {
            arrangeStoredRow();
            arrangeCredentialMatches();

            final UserUpdateScreen screen = service.updateUser(unchangedRequest(), null);

            assertThat(screen.errorMessage()).isEqualTo(NO_CHANGE_MESSAGE);
        }

        @Test
        @DisplayName("a provider optimistic failure reports the same outcome but carries the cause")
        void aProviderOptimisticFailureCarriesTheCause() {
            arrangeStoredRow();
            final OptimisticLockException collision = new OptimisticLockException("version moved");
            when(passwordEncoder.matches(PRESENTED_CREDENTIAL, storedDigest())).thenReturn(true);
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class))).thenThrow(collision);

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.updateUser(
                            request(USER_ID, NEW_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                                    STORED_USER_TYPE), null))
                    .satisfies(failure -> {
                        assertThat(typeOf(failure)).isEqualTo(CONCURRENT_UPDATE_TYPE);
                        assertThat(failure.getMessage()).isEqualTo(DATA_CHANGED_MESSAGE);
                        assertThat(failure.getCause())
                                .as("the cause is what tells the store-level layer from the business one")
                                .isSameAs(collision);
                    });
        }

        @Test
        @DisplayName("a Spring optimistic failure reaches the same arm")
        void aSpringOptimisticFailureReachesTheSameArm() {
            arrangeStoredRow();
            final OptimisticLockingFailureException collision =
                    new OptimisticLockingFailureException("version moved");
            when(passwordEncoder.matches(PRESENTED_CREDENTIAL, storedDigest())).thenReturn(true);
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class))).thenThrow(collision);

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.updateUser(
                            request(USER_ID, NEW_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                                    STORED_USER_TYPE), null))
                    .satisfies(failure -> {
                        assertThat(typeOf(failure)).isEqualTo(CONCURRENT_UPDATE_TYPE);
                        assertThat(failure.getMessage()).isEqualTo(DATA_CHANGED_MESSAGE);
                        assertThat(failure.getCause()).isSameAs(collision);
                    });
        }
    }

    // =====================================================================================================
    // 8. Change detection - app/cbl/COUSR02C.cbl:219-234
    // =====================================================================================================

    /**
     * Four independent tests, each setting the modified flag. The credential test is the one that could not
     * survive translation unchanged: {@code :227} compared plaintext, and the store now holds a salted
     * one-way digest.
     */
    @Nested
    @DisplayName("8. Change detection :219-234 - four tests, and the credential compared through matches()")
    class ChangeDetection {

        @Test
        @DisplayName("the credential is compared through PasswordEncoder.matches, never by string equality")
        void theCredentialIsComparedThroughMatches() {
            arrangeStoredRow();
            arrangeCredentialMatches();

            service.updateUser(unchangedRequest(), null);

            verify(passwordEncoder).matches(PRESENTED_CREDENTIAL, storedDigest());
            verify(passwordEncoder, never()).encode(anyString());
        }

        @Test
        @DisplayName("a credential the encoder accepts is NOT a change, so nothing is re-hashed")
        void aMatchingCredentialIsNotAChange() {
            arrangeStoredRow();
            arrangeCredentialMatches();

            final UserUpdateScreen screen = service.updateUser(unchangedRequest(), null);

            assertThat(screen.userModified()).isFalse();
            assertThat(screen.errorMessage()).isEqualTo(NO_CHANGE_MESSAGE);
            verify(userSecurityRepository, never()).saveAndFlush(any(UserSecurity.class));
        }

        @Test
        @DisplayName("a credential the encoder refuses IS a change, and the new value is re-hashed")
        void aNonMatchingCredentialIsAChangeAndIsReHashed() {
            arrangeStoredRow();
            when(passwordEncoder.matches(REPLACEMENT_CREDENTIAL, storedDigest())).thenReturn(false);
            when(passwordEncoder.encode(REPLACEMENT_CREDENTIAL)).thenReturn(replacementDigest());
            final ArgumentCaptor<UserSecurity> persisted = ArgumentCaptor.forClass(UserSecurity.class);
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            final UserUpdateScreen screen = service.updateUser(
                    request(USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME, REPLACEMENT_CREDENTIAL,
                            STORED_USER_TYPE), null);

            verify(passwordEncoder).encode(REPLACEMENT_CREDENTIAL);
            verify(userSecurityRepository).saveAndFlush(persisted.capture());
            assertThat(persisted.getValue().getPasswordHash())
                    .as("what is stored is what the encoder returned, never the plaintext")
                    .isEqualTo(replacementDigest())
                    .isNotEqualTo(REPLACEMENT_CREDENTIAL);
            assertThat(screen.userModified()).isTrue();
        }

        @Test
        @DisplayName("a changed first name is detected and persisted")
        void aChangedFirstNameIsPersisted() {
            arrangeStoredRow();
            arrangeCredentialMatches();
            final ArgumentCaptor<UserSecurity> persisted = ArgumentCaptor.forClass(UserSecurity.class);
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            service.updateUser(request(USER_ID, NEW_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                    STORED_USER_TYPE), null);

            verify(userSecurityRepository).saveAndFlush(persisted.capture());
            assertThat(persisted.getValue().getSecUsrFname()).isEqualTo(NEW_FIRST_NAME);
            assertThat(persisted.getValue().getSecUsrLname()).isEqualTo(STORED_LAST_NAME);
        }

        @Test
        @DisplayName("a changed last name is detected and persisted")
        void aChangedLastNameIsPersisted() {
            arrangeStoredRow();
            arrangeCredentialMatches();
            final ArgumentCaptor<UserSecurity> persisted = ArgumentCaptor.forClass(UserSecurity.class);
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            service.updateUser(request(USER_ID, STORED_FIRST_NAME, NEW_LAST_NAME, PRESENTED_CREDENTIAL,
                    STORED_USER_TYPE), null);

            verify(userSecurityRepository).saveAndFlush(persisted.capture());
            assertThat(persisted.getValue().getSecUsrLname()).isEqualTo(NEW_LAST_NAME);
        }

        @Test
        @DisplayName("a changed user class is detected and persisted")
        void aChangedUserClassIsPersisted() {
            arrangeStoredRow();
            arrangeCredentialMatches();
            final ArgumentCaptor<UserSecurity> persisted = ArgumentCaptor.forClass(UserSecurity.class);
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            service.updateUser(request(USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                    OTHER_USER_TYPE), null);

            verify(userSecurityRepository).saveAndFlush(persisted.capture());
            assertThat(persisted.getValue().getSecUsrType()).isEqualTo(UserType.ADMIN);
        }

        @Test
        @DisplayName("names are compared on fixed width, so trailing spaces are not a change")
        void namesAreComparedOnFixedWidth() {
            arrangeStoredRow();
            arrangeCredentialMatches();

            final UserUpdateScreen screen = service.updateUser(
                    request(USER_ID, STORED_FIRST_NAME + "     ", STORED_LAST_NAME + "     ",
                            PRESENTED_CREDENTIAL, STORED_USER_TYPE), null);

            assertThat(screen.userModified())
                    .as("SEC-USR-FNAME is PIC X(20), so 'AJITH' and 'AJITH     ' are one value")
                    .isFalse();
            verify(userSecurityRepository, never()).saveAndFlush(any(UserSecurity.class));
        }

        @Test
        @DisplayName("nothing changed produces the red no-change message and no write at all")
        void nothingChangedProducesTheRedNoChangeMessage() {
            arrangeStoredRow();
            arrangeCredentialMatches();

            final UserUpdateScreen screen = service.updateUser(unchangedRequest(), null);

            assertThat(screen.errorMessage()).isEqualTo(NO_CHANGE_MESSAGE);
            assertThat(screen.messageColour())
                    .as(":241 MOVE DFHRED TO ERRMSGC")
                    .isEqualTo(COLOUR_RED);
            assertThat(screen.userModified()).isFalse();
            verify(userSecurityRepository, never()).saveAndFlush(any(UserSecurity.class));
        }

        @Test
        @DisplayName("the no-change screen parks the cursor on the first name, per the WHEN OTHER arm at :211")
        void theNoChangeScreenParksTheCursorOnTheFirstName() {
            arrangeStoredRow();
            arrangeCredentialMatches();

            final UserUpdateScreen screen = service.updateUser(unchangedRequest(), null);

            assertThat(screen.cursorField())
                    .as("the arm at :210-212 moves -1 into FNAMEL and then executes a CONTINUE that "
                            + "terminates nothing; the cursor placement is the only observable trace of a "
                            + "retained no-op, which is why it is asserted rather than assumed")
                    .isEqualTo(CURSOR_FIRST_NAME);
        }

        @Test
        @DisplayName("several changes at once produce one write, not one per field")
        void severalChangesProduceOneWrite() {
            arrangeStoredRow();
            when(passwordEncoder.matches(REPLACEMENT_CREDENTIAL, storedDigest())).thenReturn(false);
            when(passwordEncoder.encode(REPLACEMENT_CREDENTIAL)).thenReturn(replacementDigest());
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            service.updateUser(request(USER_ID, NEW_FIRST_NAME, NEW_LAST_NAME, REPLACEMENT_CREDENTIAL,
                    OTHER_USER_TYPE), null);

            verify(userSecurityRepository).saveAndFlush(any(UserSecurity.class));
        }

        @Test
        @DisplayName("an unrecognised user class is refused with the target's own domain message")
        void anUnrecognisedUserClassIsRefused() {
            arrangeStoredRow();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.updateUser(request(USER_ID, STORED_FIRST_NAME,
                            STORED_LAST_NAME, PRESENTED_CREDENTIAL, "X"), null))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(USER_TYPE_DOMAIN_MESSAGE);
                        assertThat(failure.getFieldName()).isEqualTo("userType");
                    });
        }

        @ParameterizedTest(name = "a user class of [{0}] is refused, the lookup being case sensitive")
        @DisplayName("the user class is case sensitive, exactly as the 88-level comparison was")
        @ValueSource(strings = {"a", "u"})
        void theUserClassIsCaseSensitive(final String lowerCase) {
            arrangeStoredRow();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.updateUser(request(USER_ID, STORED_FIRST_NAME,
                            STORED_LAST_NAME, PRESENTED_CREDENTIAL, lowerCase), null))
                    .withMessage(USER_TYPE_DOMAIN_MESSAGE);
        }
    }

    // =====================================================================================================
    // 9. The successful rewrite - app/cbl/COUSR02C.cbl:369-376
    // =====================================================================================================

    /** The message is assembled with {@code DELIMITED BY SPACE} around the record's own identifier. */
    @Nested
    @DisplayName("9. The successful rewrite :369-376 - assembled message, green attribute")
    class SuccessfulRewrite {

        @Test
        @DisplayName("the confirmation names the record's identifier and reads exactly as :372-375 builds it")
        void theConfirmationNamesTheRecordIdentifier() {
            arrangeStoredRow();
            arrangeCredentialMatches();
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            final UserUpdateScreen screen = service.updateUser(
                    request(USER_ID, NEW_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                            STORED_USER_TYPE), null);

            assertThat(screen.errorMessage())
                    .isEqualTo(UPDATED_MESSAGE_PREFIX + USER_ID + UPDATED_MESSAGE_SUFFIX)
                    .isEqualTo("User USER0002 has been updated ...");
        }

        @Test
        @DisplayName("the confirmation stops at the first space, DELIMITED BY SPACE at :373")
        void theConfirmationStopsAtTheFirstSpace() {
            final String paddedId = "USER1   ";
            when(userSecurityRepository.findById(paddedId))
                    .thenReturn(Optional.of(new UserSecurity(paddedId, STORED_FIRST_NAME, STORED_LAST_NAME,
                            storedDigest(), UserType.USER)));
            when(passwordEncoder.matches(PRESENTED_CREDENTIAL, storedDigest())).thenReturn(true);
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            final UserUpdateScreen screen = service.updateUser(
                    request(paddedId, NEW_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                            STORED_USER_TYPE), null);

            assertThat(screen.errorMessage())
                    .as("the fixed-width key's trailing blanks are not part of the reported identifier")
                    .isEqualTo("User USER1 has been updated ...");
        }

        @Test
        @DisplayName("the success screen carries the green attribute, the one success marker in the program")
        void theSuccessScreenCarriesTheGreenAttribute() {
            arrangeStoredRow();
            arrangeCredentialMatches();
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            final UserUpdateScreen screen = service.updateUser(
                    request(USER_ID, NEW_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                            STORED_USER_TYPE), null);

            assertThat(screen.messageColour()).isEqualTo(COLOUR_GREEN);
            assertThat(screen.userModified()).isTrue();
        }

        @Test
        @DisplayName("the three message colours are distinct, so the three outcomes stay distinguishable")
        void theThreeMessageColoursAreDistinct() {
            assertThat(List.of(COLOUR_GREEN, COLOUR_RED, COLOUR_NEUTRAL)).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the write goes through the loaded record, so the identifier and version are preserved")
        void theWriteGoesThroughTheLoadedRecord() {
            final UserSecurity loaded = storedUser();
            when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.of(loaded));
            arrangeCredentialMatches();
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            final ArgumentCaptor<UserSecurity> persisted = ArgumentCaptor.forClass(UserSecurity.class);

            service.updateUser(request(USER_ID, NEW_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                    STORED_USER_TYPE), null);

            verify(userSecurityRepository).saveAndFlush(persisted.capture());
            assertThat(persisted.getValue())
                    .as(":362 FROM (SEC-USER-DATA) - the record area that was read, not a fresh instance")
                    .isSameAs(loaded);
        }
    }

    // =====================================================================================================
    // 10. The read and rewrite response arms - app/cbl/COUSR02C.cbl:333-353 and :368-390
    // =====================================================================================================

    /** Three arms each, with four distinct literals between them, and never a swallowed failure. */
    @Nested
    @DisplayName("10. The response arms :333-353 and :368-390 - four literals, none swallowed")
    class ResponseArms {

        @Test
        @DisplayName("a missing row on the read reports User ID NOT found and names the key")
        void aMissingRowOnTheReadIsReported() {
            when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> service.updateUser(unchangedRequest(), null))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(USER_NOT_FOUND_MESSAGE);
                        assertThat(failure.recordType()).contains(USRSEC_FILE);
                        assertThat(failure.recordKey()).contains(USER_ID);
                    });
        }

        @Test
        @DisplayName("a missing row on the ENTER path reports the same literal")
        void aMissingRowOnTheEnterPathIsReported() {
            when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> service.lookupUser(USER_ID))
                    .withMessage(USER_NOT_FOUND_MESSAGE);
        }

        @Test
        @DisplayName("an unreadable store reports Unable to lookup User and preserves the cause")
        void anUnreadableStoreIsReported() {
            final QueryTimeoutException timedOut = new QueryTimeoutException("timed out");
            when(userSecurityRepository.findById(USER_ID)).thenThrow(timedOut);

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.updateUser(unchangedRequest(), null))
                    .satisfies(failure -> {
                        assertThat(typeOf(failure))
                                .as("the '9x' family maps to the file-access type, :346-352")
                                .isEqualTo(FILE_ACCESS_TYPE);
                        assertThat(failure.getMessage())
                                .as("the dataset of :323 and the verb of :322 both reach the message")
                                .contains(USRSEC_FILE)
                                .contains("READ");
                        assertThat(failure.getCause())
                                .as("the provider's failure is carried, never swallowed")
                                .isSameAs(timedOut);
                    });
            verify(userSecurityRepository, never()).saveAndFlush(any(UserSecurity.class));
        }

        @Test
        @DisplayName("an unwritable store reports Unable to Update User and preserves the cause")
        void anUnwritableStoreIsReported() {
            arrangeStoredRow();
            final QueryTimeoutException timedOut = new QueryTimeoutException("timed out");
            arrangeCredentialMatches();
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class))).thenThrow(timedOut);

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.updateUser(
                            request(USER_ID, NEW_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                                    STORED_USER_TYPE), null))
                    .satisfies(failure -> {
                        assertThat(typeOf(failure)).isEqualTo(FILE_ACCESS_TYPE);
                        assertThat(failure.getMessage())
                                .as("the dataset of :361 and the verb of :360 both reach the message")
                                .contains(USRSEC_FILE)
                                .contains("REWRITE");
                        assertThat(failure.getCause()).isSameAs(timedOut);
                    });
        }

        @Test
        @DisplayName("the four failure literals are byte exact and mutually distinct")
        void theFourFailureLiteralsAreByteExactAndDistinct() {
            assertThat(USER_NOT_FOUND_MESSAGE).isEqualTo("User ID NOT found...");
            assertThat(UNABLE_TO_LOOKUP_MESSAGE).isEqualTo("Unable to lookup User...");
            assertThat(UNABLE_TO_UPDATE_MESSAGE).isEqualTo("Unable to Update User...");
            assertThat(NO_CHANGE_MESSAGE).isEqualTo("Please modify to update ...");
            assertThat(List.of(USER_NOT_FOUND_MESSAGE, UNABLE_TO_LOOKUP_MESSAGE, UNABLE_TO_UPDATE_MESSAGE,
                    NO_CHANGE_MESSAGE, SAVE_HINT_MESSAGE)).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the lookup and update failure literals differ, so the two operations stay apart")
        void theLookupAndUpdateLiteralsDiffer() throws ReflectiveOperationException {
            assertThat(declaredConstant("UNABLE_TO_LOOKUP_MESSAGE"))
                    .isEqualTo(UNABLE_TO_LOOKUP_MESSAGE)
                    .isNotEqualTo(declaredConstant("UNABLE_TO_UPDATE_MESSAGE"));
        }

        @Test
        @DisplayName("the successful read reports the save hint in neutral, not in red or green")
        void theSuccessfulReadReportsTheSaveHintInNeutral() {
            arrangeStoredRow();

            final UserUpdateScreen screen = service.lookupUser(USER_ID);

            assertThat(screen.errorMessage()).isEqualTo(SAVE_HINT_MESSAGE);
            assertThat(screen.messageColour())
                    .isEqualTo(COLOUR_NEUTRAL)
                    .isNotEqualTo(COLOUR_RED)
                    .isNotEqualTo(COLOUR_GREEN);
        }

        @Test
        @DisplayName("an unclassifiable read status falls back to the literal of :349, byte exactly")
        void anUnclassifiableReadStatusFallsBackToTheReadLiteral() {
            final FileStatusMapper silent = mock(FileStatusMapper.class);
            when(silent.toException(anyString(), anyString(), anyString(), any()))
                    .thenReturn(Optional.empty());
            final UserUpdateService withSilentMapper = new UserUpdateService(userSecurityRepository,
                    passwordEncoder, silent, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
            final QueryTimeoutException timedOut = new QueryTimeoutException("timed out");
            when(userSecurityRepository.findById(USER_ID)).thenThrow(timedOut);

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> withSilentMapper.lookupUser(USER_ID))
                    .satisfies(failure -> {
                        assertThat(typeOf(failure)).isEqualTo(FILE_ACCESS_TYPE);
                        assertThat(failure.getMessage())
                                .as("the WHEN OTHER arm at :346-352 moves this literal into WS-MESSAGE, and "
                                        + "an unclassifiable status must not lose it")
                                .isEqualTo(UNABLE_TO_LOOKUP_MESSAGE);
                        assertThat(failure.getCause()).isSameAs(timedOut);
                    });
        }

        @Test
        @DisplayName("an unclassifiable rewrite status falls back to the literal of :386, byte exactly")
        void anUnclassifiableRewriteStatusFallsBackToTheUpdateLiteral() {
            final FileStatusMapper silent = mock(FileStatusMapper.class);
            when(silent.toException(anyString(), anyString(), anyString(), any()))
                    .thenReturn(Optional.empty());
            final UserUpdateService withSilentMapper = new UserUpdateService(userSecurityRepository,
                    passwordEncoder, silent, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
            final QueryTimeoutException timedOut = new QueryTimeoutException("timed out");
            arrangeStoredRow();
            arrangeCredentialMatches();
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class))).thenThrow(timedOut);

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> withSilentMapper.updateUser(
                            request(USER_ID, NEW_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                                    STORED_USER_TYPE), null))
                    .satisfies(failure -> {
                        assertThat(typeOf(failure)).isEqualTo(FILE_ACCESS_TYPE);
                        assertThat(failure.getMessage())
                                .as("the verb is correct on this path: an update failure on an update path, "
                                        + ":386, and it differs from the read literal of :349")
                                .isEqualTo(UNABLE_TO_UPDATE_MESSAGE)
                                .isNotEqualTo(UNABLE_TO_LOOKUP_MESSAGE);
                        assertThat(failure.getCause()).isSameAs(timedOut);
                    });
        }
    }

    // =====================================================================================================
    // 11. Credential protection - Rule 1 Clause D, and the deliberate deviation from :169
    // =====================================================================================================

    /**
     * {@code :169} moved the stored credential into the output map - it painted the password onto the screen.
     * That is deliberately not reproduced, and the response record declares no component that could carry it.
     */
    @Nested
    @DisplayName("11. Credential protection - :169 not reproduced, and no message carries a secret")
    class CredentialProtection {

        @Test
        @DisplayName("the response record declares no credential component, so :169 cannot be reintroduced")
        void theResponseRecordDeclaresNoCredentialComponent() {
            final List<String> components = Arrays.stream(UserUpdateScreen.class.getRecordComponents())
                    .map(component -> component.getName())
                    .toList();

            assertThat(components)
                    .hasSize(16)
                    .doesNotContain("password", "passwordHash", "secUsrPwd", "credential", "digest");
        }

        @Test
        @DisplayName("no failure message on any path carries the presented credential")
        void noFailureMessageCarriesThePresentedCredential() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.updateUser(
                            request(USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME, null, null), null))
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .doesNotContain(PRESENTED_CREDENTIAL)
                            .doesNotContain(storedDigest()));
        }

        @Test
        @DisplayName("no successful outcome carries the credential or the digest")
        void noSuccessfulOutcomeCarriesTheCredential() {
            arrangeStoredRow();
            arrangeCredentialMatches();
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            final UserUpdateScreen screen = service.updateUser(
                    request(USER_ID, NEW_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                            STORED_USER_TYPE), null);

            assertThat(screen.errorMessage()).doesNotContain(PRESENTED_CREDENTIAL, storedDigest());
            assertThat(screen.toString()).doesNotContain(PRESENTED_CREDENTIAL, storedDigest());
        }

        @Test
        @DisplayName("an encoder returning nothing ends the operation rather than storing an empty digest")
        void anEncoderReturningNothingEndsTheOperation() {
            arrangeStoredRow();
            when(passwordEncoder.matches(REPLACEMENT_CREDENTIAL, storedDigest())).thenReturn(false);
            when(passwordEncoder.encode(REPLACEMENT_CREDENTIAL)).thenReturn(null);

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.updateUser(
                            request(USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME, REPLACEMENT_CREDENTIAL,
                                    STORED_USER_TYPE), null))
                    .satisfies(failure -> {
                        assertThat(typeOf(failure)).isEqualTo(FATAL_PROCESSING_TYPE);
                        assertThat(abendField(failure, "getAbendCulprit"))
                                .as("the abend names the program that raised it, :36")
                                .isEqualTo(PROGRAM_NAME);
                        assertThat((String) abendField(failure, "getAbendReason"))
                                .as("the reason names the encoder, and carries no credential material")
                                .endsWith(ENCODER_ABEND_REASON_TAIL)
                                .doesNotContain(REPLACEMENT_CREDENTIAL);
                    });
            verify(userSecurityRepository, never()).saveAndFlush(any(UserSecurity.class));
        }

        @Test
        @DisplayName("an encoder returning an empty digest ends the operation on the same arm")
        void anEncoderReturningAnEmptyDigestEndsTheOperation() {
            arrangeStoredRow();
            when(passwordEncoder.matches(REPLACEMENT_CREDENTIAL, storedDigest())).thenReturn(false);
            when(passwordEncoder.encode(REPLACEMENT_CREDENTIAL)).thenReturn("");

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.updateUser(
                            request(USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME, REPLACEMENT_CREDENTIAL,
                                    STORED_USER_TYPE), null))
                    .satisfies(failure -> {
                        assertThat(typeOf(failure)).isEqualTo(FATAL_PROCESSING_TYPE);
                        assertThat((String) abendField(failure, "getAbendReason"))
                                .endsWith(ENCODER_ABEND_REASON_TAIL);
                    });
        }

        @Test
        @DisplayName("what is persisted is a strength-10 digest, enforced by the entity contract itself")
        void whatIsPersistedIsAStrengthTenDigest() {
            arrangeStoredRow();
            when(passwordEncoder.matches(REPLACEMENT_CREDENTIAL, storedDigest())).thenReturn(false);
            when(passwordEncoder.encode(REPLACEMENT_CREDENTIAL)).thenReturn(replacementDigest());
            final ArgumentCaptor<UserSecurity> persisted = ArgumentCaptor.forClass(UserSecurity.class);
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            service.updateUser(request(USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME,
                    REPLACEMENT_CREDENTIAL, STORED_USER_TYPE), null);

            verify(userSecurityRepository).saveAndFlush(persisted.capture());
            assertThat(persisted.getValue().getPasswordHash())
                    .as("UserSecurity admits cost 10 and refuses every other cost, so the strength is "
                            + "proved by the contract rather than by a literal")
                    .hasSize(60);
        }
    }

    // =====================================================================================================
    // 12. Header, clear and statelessness - app/cbl/COUSR02C.cbl:296-315 and :403-411
    // =====================================================================================================

    /** Six header values computed rather than echoed, a clear that touches six fields, and no residue. */
    @Nested
    @DisplayName("12. Header and clear :296-315 and :403-411 - computed values, cleared fields, no residue")
    class PresentationAndStatelessness {

        @Test
        @DisplayName("the submitted header components are overwritten, never echoed back")
        void theSubmittedHeaderComponentsAreOverwritten() {
            arrangeStoredRow();

            final UserUpdateScreen screen = service.lookupUser(USER_ID);

            assertThat(screen.transactionName()).isEqualTo(TRANSACTION_ID);
            assertThat(screen.programName()).isEqualTo(PROGRAM_NAME);
            assertThat(screen.title01()).isEqualTo(SCREEN_TITLE_01);
            assertThat(screen.title02()).isEqualTo(SCREEN_TITLE_02);
            assertThat(screen.currentDate()).isEqualTo(EXPECTED_HEADER_DATE);
            assertThat(screen.currentTime()).isEqualTo(EXPECTED_HEADER_TIME);
        }

        @Test
        @DisplayName("both titles are byte exact at their declared PIC X(40) width, padding included")
        void bothTitlesAreByteExactAtFortyCharacters() {
            assertThat(SCREEN_TITLE_01).hasSize(40).contains("AWS Mainframe Modernization");
            assertThat(SCREEN_TITLE_02).hasSize(40).contains("CardDemo");
        }

        @Test
        @DisplayName("the two-digit year comes from the last two digits, per :307")
        void theTwoDigitYearComesFromTheLastTwoDigits() {
            arrangeStoredRow();

            final UserUpdateScreen screen = service.lookupUser(USER_ID);

            assertThat(screen.currentDate()).isEqualTo(EXPECTED_HEADER_DATE).isNotEqualTo("06/10/2022");
        }

        @Test
        @DisplayName("two turns on one instance are independent, the bean holding nothing between them")
        void twoTurnsOnOneInstanceAreIndependent() {
            arrangeStoredRow();
            arrangeCredentialMatches();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.updateUser(request(null, null, null, null, null), null));

            final UserUpdateScreen second = service.updateUser(unchangedRequest(), null);

            assertThat(second.errorMessage())
                    .as("the second turn must behave as though the first never happened")
                    .isEqualTo(NO_CHANGE_MESSAGE);
        }

        @Test
        @DisplayName("every static field is final, so no turn can mutate class-level state")
        void everyStaticFieldIsFinal() {
            final List<Field> mutableStatics = Arrays.stream(UserUpdateService.class.getDeclaredFields())
                    .filter(field -> Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !Modifier.isFinal(field.getModifiers()))
                    .toList();
            assertThat(mutableStatics).isEmpty();
        }

        @Test
        @DisplayName("the map area declares twelve components, matching app/cpy-bms/COUSR02.CPY")
        void theMapAreaDeclaresTwelveComponents() {
            assertThat(UserUpdateRequest.class.getRecordComponents())
                    .as("COUSR02.CPY declares twelve input fields")
                    .hasSize(12);
        }

        @Test
        @DisplayName("the snapshot declares exactly the three fields the comparison reads")
        void theSnapshotDeclaresExactlyThreeFields() {
            assertThat(Arrays.stream(UserSnapshot.class.getRecordComponents())
                    .map(component -> component.getName())
                    .toList())
                    .as("the credential is deliberately absent: a caller cannot be asked to echo a digest")
                    .containsExactly("firstName", "lastName", "userType");
        }

        @Test
        @DisplayName("the recorded identity constants match the CSD definition of transaction CU02")
        void theRecordedIdentityConstantsMatchTheCsd() throws ReflectiveOperationException {
            assertThat(declaredConstant("TRANSACTION_ID")).isEqualTo(TRANSACTION_ID);
            assertThat(declaredConstant("PROGRAM_NAME")).isEqualTo(PROGRAM_NAME);
            assertThat(declaredConstant("USRSEC_FILE")).isEqualTo(USRSEC_FILE);
        }

        @Test
        @DisplayName("the read and rewrite verbs are recorded, being what a failure reports")
        void theReadAndRewriteVerbsAreRecorded() throws ReflectiveOperationException {
            assertThat(declaredConstant("READ_OPERATION")).isEqualTo("READ");
            assertThat(declaredConstant("REWRITE_OPERATION")).isEqualTo("REWRITE");
        }

        @Test
        @DisplayName("the status mapper is consulted with the USRSEC file name on a failing read")
        void theStatusMapperIsConsultedWithTheFileName() {
            final FileStatusMapper watched = org.mockito.Mockito.spy(new FileStatusMapper());
            final UserUpdateService watchedService = new UserUpdateService(userSecurityRepository,
                    passwordEncoder, watched, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
            when(userSecurityRepository.findById(USER_ID))
                    .thenThrow(new QueryTimeoutException("timed out"));

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> watchedService.updateUser(unchangedRequest(), null));

            verify(watched).toException(anyString(), eq(USRSEC_FILE), eq("READ"), any());
        }
    }

    // =====================================================================================================
    // 13. The message channel and the record geometry
    // =====================================================================================================

    /**
     * The two widths the source states and one it does not. {@code WS-MESSAGE} is {@code PIC X(80)} at
     * {@code app/cbl/COUSR02C.cbl}:38 while the map field it feeds is {@code PIC X(78)}, so the
     * {@code MOVE} at {@code :270} narrowed by two bytes. The record is exactly eighty bytes, which is what
     * {@code LENGTH OF SEC-USER-DATA} at {@code :325} and {@code :363} resolves to.
     */
    @Nested
    @DisplayName("13. Widths - the 80-into-78 narrowing at :270, and the 80-byte record of CSUSR01Y")
    class WidthsAndRecordGeometry {

        @Test
        @DisplayName("the channel is 78 characters, two narrower than the work area that feeds it")
        void theChannelIsTwoNarrowerThanTheWorkArea() {
            assertThat(UserSecurityDto.ERROR_MESSAGE_WIDTH)
                    .as("ERRMSGI and ERRMSGO are PIC X(78); the MOVE at :270 takes WS-MESSAGE PIC X(80)")
                    .isEqualTo(78);
            assertThat(WORK_AREA_MESSAGE_WIDTH - UserSecurityDto.ERROR_MESSAGE_WIDTH)
                    .as("a two-byte narrowing, asserted rather than widened away")
                    .isEqualTo(2);
        }

        @ParameterizedTest(name = "[{0}] fits the channel")
        @DisplayName("every literal this program can emit fits the 78-character channel uncut")
        @ValueSource(strings = {
            "Invalid key pressed. Please see below...",
            "User ID can NOT be empty...",
            "First Name can NOT be empty...",
            "Last Name can NOT be empty...",
            "Password can NOT be empty...",
            "User Type can NOT be empty...",
            "Please modify to update ...",
            "Press PF5 key to save your updates ...",
            "User ID NOT found...",
            "Unable to lookup User...",
            "Unable to Update User..."})
        void everyEmittedLiteralFitsTheChannel(final String literal) {
            assertThat(literal.length())
                    .as("a literal longer than the channel would lose its tail at :270")
                    .isLessThanOrEqualTo(UserSecurityDto.ERROR_MESSAGE_WIDTH);
        }

        @Test
        @DisplayName("the assembled confirmation fits the channel even at the widest possible identifier")
        void theAssembledConfirmationFitsTheChannelAtFullWidth() {
            final String widest = UPDATED_MESSAGE_PREFIX + "X".repeat(UserSecurityDto.USER_ID_WIDTH)
                    + UPDATED_MESSAGE_SUFFIX;

            assertThat(widest.length())
                    .as("the STRING at :372-375 over an eight-character SEC-USR-ID is the longest message "
                            + "this program can build")
                    .isLessThanOrEqualTo(UserSecurityDto.ERROR_MESSAGE_WIDTH);
        }

        @Test
        @DisplayName("the arm that sends no screen echoes the submitted message, neither padded nor cut")
        void theNonSendingArmEchoesTheSubmittedMessageUnchanged() {
            final String submitted = "x".repeat(UserSecurityDto.ERROR_MESSAGE_WIDTH);

            final UserUpdateScreen screen = service.submitScreen(AttentionIdentifier.PF12,
                    new UserUpdateRequest("ZZZZ", "t1", "01/01/00", "ZZZZZZZZ", "t2", "00:00:00", USER_ID,
                            STORED_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL, STORED_USER_TYPE,
                            submitted),
                    null);

            assertThat(screen.errorMessage())
                    .as(":124-126 never performs SEND-USRUPD-SCREEN, so the received field survives the turn")
                    .isEqualTo(submitted)
                    .hasSize(UserSecurityDto.ERROR_MESSAGE_WIDTH);
            verifyNoInteractions(userSecurityRepository);
        }

        @Test
        @DisplayName("the record is 80 bytes: 8 + 20 + 20 + 8 + 1 + 23, the LENGTH the rewrite declares")
        void theRecordIsEightyBytes() {
            final int assembled = UserSecurityDto.USER_ID_WIDTH
                    + UserSecurityDto.NAME_WIDTH
                    + UserSecurityDto.NAME_WIDTH
                    + SOURCE_CREDENTIAL_WIDTH
                    + UserSecurityDto.USER_TYPE_WIDTH
                    + SOURCE_FILLER_WIDTH;

            assertThat(assembled)
                    .as("app/cpy/CSUSR01Y.cpy:17-23 and RECORDSIZE(80,80) on the cluster")
                    .isEqualTo(USRSEC_RECORD_LENGTH);
        }

        @Test
        @DisplayName("a credential exactly on the source's eight-character width is accepted")
        void aCredentialExactlyOnTheSourceWidthIsAccepted() {
            assertThat(PRESENTED_CREDENTIAL)
                    .as("the fixture sits exactly on the width the source field declares")
                    .hasSize(SOURCE_CREDENTIAL_WIDTH);
            arrangeStoredRow();
            arrangeRewriteAccepted();
            when(passwordEncoder.matches(PRESENTED_CREDENTIAL, storedDigest())).thenReturn(false);
            when(passwordEncoder.encode(PRESENTED_CREDENTIAL)).thenReturn(replacementDigest());

            final UserUpdateScreen screen = service.updateUser(unchangedRequest(), null);

            assertThat(screen.userModified())
                    .as("SEC-USR-PWD is PIC X(08), so eight characters is the widest value the source could "
                            + "hold and must be accepted rather than refused")
                    .isTrue();
        }

        @Test
        @DisplayName("the stored credential column is 60, the one field deliberately not byte-parity")
        void theStoredCredentialColumnIsSixty() {
            assertThat(storedDigest())
                    .as("the eight-byte plaintext field becomes a sixty-character digest column")
                    .hasSize(DIGEST_COLUMN_WIDTH);
        }
    }

    // =====================================================================================================
    // 14. Stateless handling, the vestigial clone, and the branches that are NOT invented
    // =====================================================================================================

    /**
     * Three absences, each asserted rather than assumed. The pseudo-conversational re-entry of
     * {@code app/cbl/COUSR02C.cbl}:135-138 has no counterpart. The pagination block appended at
     * {@code :49-58} to a single-record screen is vestigial but for its last member. And the rewrite at
     * {@code :360-366} carries no {@code RIDFLD}, so it has no duplicate-key arm to translate.
     */
    @Nested
    @DisplayName("14. Absences - stateless at :135-138, vestigial at :49-58, no duplicate arm at :360-366")
    class StatelessHandlingAndAbsentMechanisms {

        @Test
        @DisplayName("the screen declares no re-entry, context or last-map component")
        void theScreenDeclaresNoPseudoConversationalState() {
            assertThat(componentNames(UserUpdateScreen.class))
                    .as("CDEMO-PGM-CONTEXT, CDEMO-LAST-MAP and CDEMO-LAST-MAPSET have no counterpart; the "
                            + "enter-versus-re-enter flag of :95-96 collapses into stateless handling")
                    .noneSatisfy(name -> assertThat(name.toLowerCase(Locale.ROOT))
                            .containsAnyOf("context", "reenter", "lastmap", "mapset", "session", "commarea"));
        }

        @Test
        @DisplayName("no page number, next-page flag or first-and-last key is carried anywhere")
        void noPaginationStateIsCarried() {
            final List<String> everyComponent = List.of(
                    String.join(",", componentNames(UserUpdateScreen.class)).toLowerCase(Locale.ROOT),
                    String.join(",", componentNames(UserSnapshot.class)).toLowerCase(Locale.ROOT),
                    String.join(",", componentNames(UserUpdateRequest.class)).toLowerCase(Locale.ROOT));

            assertThat(everyComponent)
                    .as("CDEMO-CU02-USRID-FIRST, -USRID-LAST, -PAGE-NUM, -NEXT-PAGE-FLG and -USR-SEL-FLG at "
                            + ":51-57 clone a list screen's block onto a single-record screen; only "
                            + "-USR-SELECTED at :58 is live, and the rest stay vestigial")
                    .allSatisfy(joined -> assertThat(joined)
                            .doesNotContain("page")
                            .doesNotContain("useridfirst")
                            .doesNotContain("useridlast")
                            .doesNotContain("firstkey")
                            .doesNotContain("lastkey")
                            .doesNotContain("selectionflag")
                            .doesNotContain("selflg"));
        }

        @Test
        @DisplayName("exactly one value crosses from the list screen: the selected identifier of :101-102")
        void onlyTheSelectedIdentifierCrossesOver() {
            assertThat(entryPoint("openScreen").getParameterTypes())
                    .as("CDEMO-CU02-USR-SELECTED is the only live member of the block at :50-58")
                    .containsExactly(String.class);
        }

        @Test
        @DisplayName("the bean declares no HTTP, session or security collaborator, so no state can survive")
        void theBeanDeclaresNoSessionOrSecurityCollaborator() {
            final List<String> fieldTypes = Arrays.stream(UserUpdateService.class.getDeclaredFields())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .map(field -> field.getType().getName())
                    .toList();

            assertThat(fieldTypes)
                    .as("four collaborators and nothing else: no request, no session, no principal")
                    .allSatisfy(name -> assertThat(name)
                            .doesNotContain("servlet")
                            .doesNotContain("Session")
                            .doesNotContain("security.core")
                            .doesNotContain("Authentication"));
        }

        @Test
        @DisplayName("the bean carries no method-security annotation, so least privilege is enforced upstream")
        void theBeanCarriesNoMethodSecurityAnnotation() {
            assertThat(annotationNames(UserUpdateService.class.getAnnotations()))
                    .as("the ADMIN-only rule over /api/admin/** belongs to the filter chain, whose own suite "
                            + "owns it; this bean neither reads a role nor can relax one")
                    .containsExactly("Service");

            for (final String name : List.of("openWithoutContext", "openScreen", "lookupUser", "updateUser",
                    "submitScreen")) {
                assertThat(annotationNames(entryPoint(name).getAnnotations()))
                        .as("%s must carry no authorisation annotation of its own", name)
                        .doesNotContain("PreAuthorize", "Secured", "RolesAllowed", "PostAuthorize");
            }
        }

        @Test
        @DisplayName("no entry point accepts a role, principal or authentication argument")
        void noEntryPointAcceptsAPrincipal() {
            for (final String name : List.of("openScreen", "lookupUser", "updateUser", "submitScreen")) {
                assertThat(Arrays.stream(entryPoint(name).getParameterTypes())
                        .map(Class::getName)
                        .toList())
                        .as("%s must take screen input only, never an identity", name)
                        .allSatisfy(type -> assertThat(type)
                                .doesNotContain("Authentication")
                                .doesNotContain("Principal")
                                .doesNotContain("UserDetails")
                                .doesNotContain("GrantedAuthority"));
            }
        }

        @Test
        @DisplayName("a user class can only ever become one of the two the corpus defines")
        void aUserClassCanOnlyBecomeOneOfTwo() {
            assertThat(UserType.values())
                    .as("the 88-levels of app/cpy/COCOM01Y.cpy admit 'A' and 'U' and nothing else, which is "
                            + "what bounds an elevation this screen could otherwise perform")
                    .containsExactly(UserType.ADMIN, UserType.USER);
        }

        @Test
        @DisplayName("a duplicate-shaped store failure on the rewrite is NOT reported as a duplicate record")
        void aDuplicateShapedFailureIsNotADuplicateRecord() {
            arrangeStoredRow();
            arrangeCredentialMatches();
            final DataIntegrityViolationException collision =
                    new DataIntegrityViolationException("key already present");
            when(userSecurityRepository.saveAndFlush(any(UserSecurity.class))).thenThrow(collision);

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.updateUser(
                            request(USER_ID, NEW_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                                    STORED_USER_TYPE), null))
                    .satisfies(failure -> {
                        assertThat(typeOf(failure))
                                .as("the REWRITE at :360-366 carries no RIDFLD and so has no DUPKEY or "
                                        + "DUPREC arm; inventing one would add an outcome the source cannot "
                                        + "produce")
                                .isEqualTo(FILE_ACCESS_TYPE)
                                .isNotEqualTo("DuplicateRecordException");
                        assertThat(failure.getCause()).isSameAs(collision);
                    });
        }

        @Test
        @DisplayName("no duplicate-key file status is declared on the bean at all")
        void noDuplicateKeyStatusIsDeclared() {
            assertThat(declaredStringConstants())
                    .as("statuses 00, 23 and 9x are the three this program can reach; '22' is not one")
                    .doesNotContain("22");
        }

        @Test
        @DisplayName("the map area declares no snapshot or old-details group, the source having neither")
        void theMapAreaDeclaresNoSnapshotGroup() {
            assertThat(componentNames(UserUpdateRequest.class))
                    .as("the comparison at :219-233 reads the FRESHLY re-read record of :217, not a "
                            + "request-carried snapshot; COACTUPC carries one and this program does not")
                    .allSatisfy(name -> assertThat(name.toLowerCase(Locale.ROOT))
                            .doesNotContain("old")
                            .doesNotContain("snapshot")
                            .doesNotContain("expected")
                            .doesNotContain("details"));
        }

        @Test
        @DisplayName("the write proceeds with no snapshot at all, which is the source's own shape")
        void theWriteProceedsWithNoSnapshotAtAll() {
            arrangeStoredRow();
            arrangeRewriteAccepted();
            when(passwordEncoder.matches(PRESENTED_CREDENTIAL, storedDigest())).thenReturn(true);

            final UserUpdateScreen screen = service.updateUser(
                    request(USER_ID, NEW_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                            STORED_USER_TYPE),
                    null);

            assertThat(screen.userModified())
                    .as("no concurrency guard exists at :215-245, so an absent snapshot must not block the "
                            + "write the source would have performed")
                    .isTrue();
        }
    }

    // =====================================================================================================
    // 15. Hostile input, and the normalisations the source does NOT perform
    // =====================================================================================================

    /**
     * Untrusted input taken at its word. The guards at {@code app/cbl/COUSR02C.cbl}:146 and
     * {@code :179-213} test emptiness and nothing else - no length floor, no character set, no credential
     * policy and no case folding anywhere - so anything narrower than that would reject input the system of
     * record accepts.
     */
    @Nested
    @DisplayName("15. Hostile input - emptiness only at :146 and :179-213, no folding, no policy")
    class HostileInputAndAbsentNormalisations {

        @Test
        @DisplayName("a seven-character identifier is accepted, the field being bounded only from above")
        void aShorterIdentifierIsAccepted() {
            when(userSecurityRepository.findById(SHORT_USER_ID))
                    .thenReturn(Optional.of(new UserSecurity(SHORT_USER_ID, STORED_FIRST_NAME,
                            STORED_LAST_NAME, storedDigest(), UserType.USER)));

            final UserUpdateScreen screen = service.lookupUser(SHORT_USER_ID);

            assertThat(screen.errorMessage())
                    .as("no length floor exists at :146, so a short key reaches the read unchanged")
                    .isEqualTo(SAVE_HINT_MESSAGE);
        }

        @Test
        @DisplayName("a single-character credential is accepted: :198 tests emptiness, not strength")
        void aSingleCharacterCredentialIsAccepted() {
            arrangeStoredRow();
            arrangeRewriteAccepted();
            when(passwordEncoder.matches(MINIMAL_CREDENTIAL, storedDigest())).thenReturn(false);
            when(passwordEncoder.encode(MINIMAL_CREDENTIAL)).thenReturn(replacementDigest());

            final UserUpdateScreen screen = service.updateUser(
                    request(USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME, MINIMAL_CREDENTIAL,
                            STORED_USER_TYPE),
                    null);

            assertThat(screen.userModified())
                    .as("no minimum length, no character class and no history exists in the source")
                    .isTrue();
        }

        @Test
        @DisplayName("no field is upper-cased: a lower-case name is stored exactly as presented")
        void noFieldIsUpperCased() {
            arrangeStoredRow();
            arrangeRewriteAccepted();
            arrangeCredentialMatches();
            final String lowerCase = "ajitha";

            service.updateUser(request(USER_ID, lowerCase, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                    STORED_USER_TYPE), null);

            assertThat(capturePersistedRow().getSecUsrFname())
                    .as("the MOVE at :220 transcribes the field; COACTUPC applies FUNCTION UPPER-CASE and "
                            + "this program applies nothing")
                    .isEqualTo(lowerCase);
        }

        @Test
        @DisplayName("a case-only difference IS a change, proving the comparison folds nothing either")
        void aCaseOnlyDifferenceIsAChange() {
            arrangeStoredRow();
            arrangeRewriteAccepted();
            arrangeCredentialMatches();

            final UserUpdateScreen screen = service.updateUser(
                    request(USER_ID, STORED_FIRST_NAME.toLowerCase(Locale.ROOT),
                            STORED_LAST_NAME, PRESENTED_CREDENTIAL, STORED_USER_TYPE),
                    null);

            assertThat(screen.userModified())
                    .as("IF FNAMEI NOT = SEC-USR-FNAME at :219 is a plain comparison with no case function")
                    .isTrue();
        }

        @Test
        @DisplayName("an unmappable user class clears all five guards and is refused as INVALID, not BLANK")
        void anUnmappableUserClassClearsTheEmptinessGuards() {
            arrangeStoredRow();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.updateUser(
                            request(USER_ID, STORED_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                                    UNMAPPABLE_USER_TYPE), null))
                    .satisfies(failure -> {
                        assertThat(failure.getFailureKind())
                                .as("a non-empty code clears :204, so the refusal is INVALID and emphatically "
                                        + "not the BLANK arm of the five emptiness guards")
                                .isEqualTo(ValidationException.FailureKind.INVALID)
                                .isNotEqualTo(ValidationException.FailureKind.BLANK);
                        assertThat(failure.getFieldName()).isEqualTo("userType");
                        assertThat(failure.getMessage())
                                .as("the narrowing to a closed enumeration is target-only, so the message is "
                                        + "the target's own and none of the five source literals")
                                .isEqualTo(USER_TYPE_DOMAIN_MESSAGE)
                                .isNotEqualTo(USER_TYPE_REQUIRED_MESSAGE);
                    });
            verify(userSecurityRepository, never()).saveAndFlush(any(UserSecurity.class));
        }

        @Test
        @DisplayName("the lookup issues exactly one unlocked read and reaches no other store method")
        void theLookupIssuesExactlyOneUnlockedRead() {
            arrangeStoredRow();

            service.lookupUser(USER_ID);

            verify(userSecurityRepository, times(1)).findById(USER_ID);
            verifyNoMoreInteractions(userSecurityRepository);
        }

        @Test
        @DisplayName("the save issues its own read first, in order, reproducing :217 before :237")
        void theSaveIssuesItsOwnReadFirst() {
            arrangeStoredRow();
            arrangeRewriteAccepted();
            arrangeCredentialMatches();

            service.updateUser(request(USER_ID, NEW_FIRST_NAME, STORED_LAST_NAME, PRESENTED_CREDENTIAL,
                    STORED_USER_TYPE), null);

            final InOrder sequence = inOrder(userSecurityRepository);
            sequence.verify(userSecurityRepository).findById(USER_ID);
            sequence.verify(userSecurityRepository).saveAndFlush(any(UserSecurity.class));
            sequence.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("a hostile identifier crosses as a bound parameter, verbatim and unescaped")
        void aHostileIdentifierCrossesAsABoundParameter() {
            when(userSecurityRepository.findById(HOSTILE_USER_ID)).thenReturn(Optional.empty());

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> service.lookupUser(HOSTILE_USER_ID));

            final ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(userSecurityRepository).findById(key.capture());
            assertThat(key.getValue())
                    .as("the key reaches a derived finder as one bound argument, so relational "
                            + "metacharacters are data and the outcome is an ordinary not-found")
                    .isEqualTo(HOSTILE_USER_ID);
        }

        @Test
        @DisplayName("the bean declares no query text, so nothing can be concatenated into one")
        void theBeanDeclaresNoQueryText() {
            for (final String token : QUERY_TOKENS) {
                assertThat(declaredStringConstants())
                        .as("a constant containing [%s] would be the beginning of assembled query text", token)
                        .allSatisfy(value -> assertThat(value.toLowerCase(Locale.ROOT))
                                .doesNotContain(token));
            }
        }
    }
}
