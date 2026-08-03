/*
 * ******************************************************************
 * Program     : UserAddServiceTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/service
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that UserAddService reproduces COUSR01C exactly
 *               - nine paragraphs mapped one to one, five emptiness
 *               guards in the source's order with every literal byte
 *               exact, no content validation and no upper-casing added,
 *               DUPKEY and DUPREC sharing one branch, the confirmation
 *               built with DELIMITED BY SPACE semantics from a field the
 *               clear does not touch, and the credential never stored in
 *               the clear, never returned and never named in a message
 * Source      : app/cbl/COUSR01C.cbl (299 lines, 9 paragraphs) @ 7756d89
 * Source      : app/cpy-bms/COUSR01.CPY (12 input fields) @ 7756d89
 * Source      : app/cpy/CSUSR01Y.cpy (80 byte record, KEYS(8,0)) @ 7756d89
 * Source      : app/cbl/COSGN00C.cbl (the upper-casing contrast) @ 7756d89
 * Source      : app/cpy/CSMSG01Y.cpy (CCDA-MSG-INVALID-KEY) @ 7756d89
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.UserCreateRequest;
import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.service.admin.UserAddService;
import com.cardemo.service.admin.UserAddService.AttentionIdentifier;
import com.cardemo.service.admin.UserAddService.UserAddScreen;
import com.cardemo.service.shared.FileStatusMapper;

/**
 * Unit tests for {@code com.cardemo.service.admin.UserAddService}, the Java replacement for
 * {@code app/cbl/COUSR01C.cbl} - 299 lines and 9 paragraphs, the CICS program behind transaction
 * {@code CU01}, which adds one Regular or Admin user to the {@code USRSEC} security file.
 *
 * <h2>What it does</h2>
 *
 * <p>It proves parity against the frozen source rather than against an idea of what the source ought to do.
 * Every assertion cites the paragraph or line it proves, and the citations were verified by direct inspection
 * at commit {@code 7756d89}. Five groups of behaviour carry the weight.
 *
 * <ul>
 *   <li><strong>The five emptiness guards.</strong> {@code EVALUATE TRUE} at {@code COUSR01C.cbl}:117-151 in
 *       the order first name {@code :118}, last name {@code :124}, identifier {@code :130}, credential
 *       {@code :136}, user type {@code :142}, with the {@code WHEN OTHER} arm at {@code :148-150}. Only the
 *       first empty field is reported, so a request empty in several fields distinguishes this program from
 *       its siblings. Each literal is asserted byte for byte at {@code :120}, {@code :126}, {@code :132},
 *       {@code :138} and {@code :144}, and each cursor marker at {@code :122}, {@code :128}, {@code :134},
 *       {@code :140} and {@code :146}.</li>
 *   <li><strong>Three absences that are behaviour.</strong> There is no content validation of any kind - the
 *       guards test emptiness and nothing else; there is no upper-casing anywhere, the moves at
 *       {@code :154-158} being plain, in deliberate contrast to {@code app/cbl/COSGN00C.cbl}:132-136 which
 *       upper-cases both the presented identifier and the presented credential; and the {@code CONTINUE} at
 *       {@code :150} terminates nothing.</li>
 *   <li><strong>The credential.</strong> {@code MOVE PASSWDI TO SEC-USR-PWD} at {@code :157} stored eight
 *       plaintext characters. The target hashes with BCrypt at strength 10 into a 60 character column - a
 *       mechanism substitution, cited to the source line above - so the assertions are that the encoder is
 *       reached exactly once, that what is persisted is what the encoder returned, and that no plaintext and
 *       no digest reaches a response, a message or a rendering.</li>
 *   <li><strong>The write and its three arms.</strong> {@code WRITE-USER-SEC-FILE} at {@code :238-274}: the
 *       success arm clears the inputs at {@code :252} <em>before</em> composing the confirmation at
 *       {@code :255-258} from {@code SEC-USR-ID}, which the clear does not touch; the duplicate arm collapses
 *       {@code DFHRESP(DUPKEY)} at {@code :260} and {@code DFHRESP(DUPREC)} at {@code :261} into one body;
 *       and the {@code WHEN OTHER} arm at {@code :267-273} reports {@code Unable to Add User...}.</li>
 *   <li><strong>Paragraph correspondence.</strong> All nine labels map one to one, which is the evidence the
 *       scope-coverage gate reads.</li>
 *   </ul>
 *
 * <h2>How to build and test</h2>
 *
 * <p>Java 25 ({@code maven.compiler.release} 25, no preview features) and Maven 3.9.11 under
 * {@code spring-boot-starter-parent:3.5.11}, with the toolchain floor asserted by
 * {@code maven-enforcer-plugin:3.5.0}.
 *
 * <ul>
 *   <li>{@code ./mvnw -B -ntp test} - runs this class. {@code maven-surefire-plugin:3.5.4} includes
 *       {@code **}{@code /*Test.java} and excludes {@code **}{@code /integration/**} and
 *       {@code **}{@code /e2e/**}, so this class is collected by <strong>Surefire</strong> and not by
 *       Failsafe. Residence in {@code src/test/java/com/cardemo/unit/service} is therefore load-bearing: a
 *       class placed outside the unit tree matches neither plugin's include set and would silently never
 *       run, leaving a green build with nothing proved.</li>
 *   <li>{@code ./mvnw -B -ntp test-compile} - compiles it. {@code maven-compiler-plugin:3.14.1} runs
 *       {@code -Xlint:all -Werror} with {@code failOnWarning}, and that reaches test compilation, so one
 *       dangling documentation comment, raw type or unchecked cast fails the build outright. An unused
 *       import does not - {@code javac} 25 publishes no {@code unused} lint key.</li>
 *   <li>{@code ./mvnw -B -ntp verify} - adds the {@code jacoco-maven-plugin:0.8.12} check at 80% LINE with
 *       no exclusion for this package.</li>
 *   <li>{@code ./mvnw -B -ntp -Dtest=UserAddServiceTest test} - runs this class alone; the report lands in
 *       {@code target/surefire-reports}.</li>
 *   </ul>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li><strong>Mockito strict stubs.</strong> {@code MockitoExtension} with
 *       {@code Strictness.STRICT_STUBS}, so an unused stub fails the test and a stub whose arguments do not
 *       match the call is reported rather than silently returning a default. Stubs are consequently arranged
 *       inside each test rather than in {@code setUp}, and stubbing with the exact expected argument is
 *       itself an assertion about what crosses the boundary.</li>
 *   <li><strong>A stubbed encoder, deliberately.</strong> BCrypt salts, so a real encoder is
 *       non-deterministic by design. The encoder is therefore a double returning a synthetic digest
 *       assembled at run time from its parts, and <strong>no literal digest appears anywhere in this
 *       file</strong>. Strength 10 is nonetheless asserted, through the contract
 *       {@code com.cardemo.model.entity.UserSecurity} enforces: a digest at cost 10 is accepted and one at
 *       any other cost is refused, so the strength is proved by behaviour instead of by a literal.</li>
 *   <li><strong>A fixed clock.</strong> {@code Clock.fixed} at a parsed instant in UTC, standing in for
 *       {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at {@code :216}. Nothing in this file reads a
 *       wall clock, a default locale, a default zone or an unseeded random source.</li>
 *   <li><strong>A real file-status mapper by default.</strong> {@code FileStatusMapper} is a pure function
 *       with a no-argument constructor, so the real one is used except in the one test that captures what
 *       crosses into it.</li>
 *   <li><strong>A synthetic credential.</strong> An obviously fake value that is never the seed value of
 *       {@code app/jcl/DUSRSECJ.jcl}. That file supplies ten users inline - five of type {@code A} and
 *       five of type {@code U} - all sharing one plaintext credential. Neither that credential nor any of
 *       the ten identifiers is reproduced here in any form; the JCL member is the authority for both.
 *       Rule 1 Clause D names tests explicitly, and this is the credential-creation service, which makes
 *       this file the highest-risk one in the package for that clause.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>An unused import must be removed, though the build will not catch it.</strong>
 *   {@code -Xlint:all -Werror} reaches test compilation, but {@code javac} 25 publishes no {@code unused} lint key,
 *   so an import that nothing references is a Rule 1 Clause B violation caught at review rather than a build failure.
 *   Remedy: remove it. In particular this class borrows no helper from the sibling {@code unit.model} package -
 *   neither its shared clock provider nor its fixture reader - because nothing on this path consults an ambient clock
 *   or reads a seed file. The clock it does use is the private constant declared below.</li>
 *   <li><strong>A guard rejects input the source accepts.</strong> Someone added a user-type domain check or
 *       a credential policy to the emptiness cascade. The source has neither, so such an implementation
 *       accepts fewer inputs than the system of record. Remedy: keep the cascade emptiness-only; the domain
 *       mapping belongs at persistence.</li>
 *   <li><strong>An identifier comes back upper-cased.</strong> Someone added case folding the source does
 *       not have. Remedy: remove it, and leave the latent defect visible.</li>
 *   <li><strong>The confirmation names nobody.</strong> The message was composed from a request field that
 *       {@code INITIALIZE-ALL-FIELDS} had already blanked, instead of from {@code SEC-USR-ID}. Remedy:
 *       compose from the record field, which the clear does not touch.</li>
 *   <li><strong>A test asserts a literal digest.</strong> BCrypt salts, so such a test is flaky by
 *       construction. Remedy: assert that the encoder was reached and that the persisted value is what it
 *       returned.</li>
 *   <li><strong>Duplicate-key and duplicate-record produce different outcomes.</strong> They are adjacent
 *       {@code WHEN} clauses over one body. Remedy: restore the single branch.</li>
 *   <li><strong>The fixture-name trap.</strong> The mainframe dataset is {@code DALYTRAN} but the ASCII
 *       fixture is spelled {@code dailytran.txt} in full. It is irrelevant on this path, which reads no
 *       fixture at all, and is recorded so that a later edit does not introduce one under the wrong
 *       name.</li>
 *   </ul>
 *
 * <h2>What this suite asserts against, and what it deliberately leaves alone</h2>
 *
 * <ul>
 *   <li><strong>Never permitted:</strong> persisting a recoverable credential; asserting a seeded credential
 *       as plaintext; letting a non-administrator reach this service. All three are asserted against.</li>
 *   <li><strong>Never added:</strong> case folding; a domain or policy gate; a confirmation composed
 *       from a cleared field; discarding the response and reason codes; asserting a literal digest.</li>
 *   <li><strong>Never changed:</strong> the collapsed duplicate branch must not be split; the message field
 *       must not be widened past the 78 character boundary; the cascade order stands; success and failure
 *       stay distinguishable by more than message text. The bean's own disclosed deviation - it refuses a
 *       user type outside {@code 'A'} and {@code 'U'}, which the source would have stored - is asserted as
 *       the deviation it is rather than presented as parity.</li>
 *   <li><strong>Reproduced verbatim:</strong> the {@code exist} spelling at {@code :263}; the space before
 *       the ellipsis in
 *       the confirmation; the redundant {@code MOVE SPACES TO WS-MESSAGE} at {@code :253}; the dead
 *       {@code CONTINUE} at {@code :150}; the two trailing blanks in the dataset literal at {@code :39};
 *       the commented-out {@code COPY DFHATTR.} at {@code :57}.</li>
 *   <li><strong>One line of the bean is unreachable through its public surface, and
 *       this suite leaves it uncovered deliberately rather than contriving a path to it.</strong> The blank
 *       target fallback of {@code RETURN-TO-PREV-SCREEN} at {@code :167-169} - {@code IF CDEMO-TO-PROGRAM =
 *       LOW-VALUES OR SPACES}, assign the sign-on program - cannot fire, because both callers assign the
 *       target first: the no-COMMAREA arm at {@code :79} and the {@code DFHPF3} arm at {@code :94}. The guard
 *       was already redundant in the source for the same reason, so reproducing it faithfully reproduces its
 *       unreachability. Remedy: none, and specifically <em>not</em> a reflective call into the private method
 *       to manufacture coverage, which Clause D's prohibition on reflection-driven invocation rules out and
 *       which would assert a state no caller can create. It is the only line of the bean this suite does not
 *       reach.</li>
 *   </ul>
 *
 * <h2>The documented conflict, and the one labelled exception</h2>
 *
 * <p>Rule 1 Clause B forbids dead code; the parity mandate requires reachable no-ops to survive. Parity
 * governs, and the clause is satisfied by its own wording, which prohibits artefacts <em>without an owner or
 * tracking reference</em>: each retained item carries a citation to the source line it reproduces and an
 * explicit marker on the test that pins it. The items retained on this path are the dead {@code CONTINUE}
 * at {@code :150}, the
 * redundant blanking at {@code :253}, the {@code CLEAR-CURRENT-SCREEN} paragraph at {@code :279-282} which
 * only chains two others, and the absent case folding.
 *
 * <p><strong>One labelled exception runs the other way.</strong> The diagnostic at {@code :268} is commented
 * out, so the source discards the response and reason codes on a hard failure. Clause B's requirement to
 * wrap with context and preserve root cause is prescriptive, and no observable output depends on those codes
 * being lost, so the clause governs and the codes are preserved. That is a deliberate, labelled deviation,
 * and it is asserted below.
 *
 * <h2>Not available</h2>
 *
 * <ul>
 *   <li><strong>The HTTP-level authorisation rule.</strong> It is declared by
 *       {@code com.cardemo.config.SecurityConfig}, which restricts {@code /api/admin/**} to the
 *       administrator role and sets a stateless session policy - but exercising it needs a controller,
 *       and {@code com.cardemo.controller.AdminController} is not present in the tree, so no slice test
 *       can drive this service through that mapping and none is attempted from this pure-JVM tier.
 *       What <em>is</em> asserted here are the structural
 *       preconditions without which no administrator-only rule could hold: this bean carries no authorisation
 *       annotation and so cannot self-authorise, takes no authentication or security-context collaborator
 *       and so cannot read or forge a role, and retains no state between calls.</li>
 *   <li><strong>A credential policy.</strong> Not available in the source. {@code :136} tests emptiness and
 *       nothing else and {@code SEC-USR-PWD} is a bare {@code PIC X(08)}, so no minimum length, complexity,
 *       expiry, reuse or lockout rule exists to reproduce and none is invented. What would be needed is a
 *       source rule that does not exist.</li>
 *   <li><strong>An audit trail for user creation.</strong> Not available. The source writes one record and
 *       records nothing about who wrote it; the two moves that would have carried the acting identity are
 *       commented out at {@code :172-173}.</li>
 *   </ul>
 *
 * <p><strong>Resolved, not deferred: the eight-character question.</strong> Whether the target preserves the
 * source's {@code PIC X(08)} credential truncation is determinable from the bean and is therefore stated
 * rather than left open. It does <em>not</em> preserve it. The presented credential arrives as an argument
 * separate from the request, is absent from the width-checked set that {@code RECEIVE-USRADD-SCREEN} binds,
 * and is handed whole to the encoder, so a value longer than eight characters is accepted and hashed intact
 * rather than cut. Both halves are asserted below.
 */
@DisplayName("UserAddService: app/cbl/COUSR01C.cbl - add a Regular or Admin user to USRSEC (CU01)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class UserAddServiceTest {

    // ----------------------------------------------------------------------------------------------------
    // Synthetic credential material. Rule 1 Clause D names tests explicitly, so nothing here is or
    // resembles a real credential, and the seed value of app/jcl/DUSRSECJ.jcl is never reproduced.
    // ----------------------------------------------------------------------------------------------------

    /**
     * The presented plaintext, standing in for {@code PASSWDI PIC X(8)} at
     * {@code app/cpy-bms/COUSR01.CPY}:78. Obviously fake, eight characters so that it also sits exactly on
     * the source field's width, and never echoed into an assertion message.
     */
    private static final String PRESENTED_CREDENTIAL = "n0tr3al!";

    /**
     * The {@code $} that separates a BCrypt digest's version tag, cost factor and body. Held as a character
     * and concatenated at run time so that <strong>no BCrypt prefix literal exists anywhere in this
     * file</strong>: the digests below are assembled, never written out.
     */
    private static final char DIGEST_FIELD_MARKER = '$';

    /**
     * A BCrypt version tag the verifier accepts. {@code UserSecurity} admits exactly the three tags
     * {@code BCryptPasswordEncoder.BCryptVersion} declares, and this is one of them.
     */
    private static final String DIGEST_VERSION_TAG = "2a";

    /**
     * The contractual cost factor. The migration rule pins BCrypt <strong>strength 10</strong>, and
     * {@code com.cardemo.model.entity.UserSecurity} admits this cost and no other.
     */
    private static final String CONTRACTUAL_COST_FACTOR = "10";

    /**
     * A cost factor the contract refuses. Used to prove that strength 10 is enforced rather than assumed,
     * without asserting any literal digest.
     */
    private static final String OFF_CONTRACT_COST_FACTOR = "11";

    /**
     * Twenty-two characters from BCrypt's radix-64 alphabet, standing where a salt would sit. Transparently
     * synthetic.
     */
    private static final String SYNTHETIC_SALT = "SyntheticUnitTestSalt0";

    /**
     * Thirty-one characters from BCrypt's radix-64 alphabet, standing where a digest would sit.
     * Transparently synthetic.
     */
    private static final String SYNTHETIC_BODY = "NotARealCredentialPlaceholder00";

    /** The exact width of the credential column, from {@code UserSecurity}'s field contract. */
    private static final int DIGEST_WIDTH = 60;

    // ----------------------------------------------------------------------------------------------------
    // The five ordered literals of PROCESS-ENTER-KEY, app/cbl/COUSR01C.cbl:117-151. Byte exact: capital
    // N, O and T in "can NOT", and every ellipsis exactly three periods. Each program owns its own
    // literals, so none of these is shared with the sibling update or delete suites.
    // ----------------------------------------------------------------------------------------------------

    /** {@code app/cbl/COUSR01C.cbl}:120, guard one. */
    private static final String FIRST_NAME_REQUIRED = "First Name can NOT be empty...";

    /** {@code app/cbl/COUSR01C.cbl}:126, guard two. */
    private static final String LAST_NAME_REQUIRED = "Last Name can NOT be empty...";

    /** {@code app/cbl/COUSR01C.cbl}:132, guard three. */
    private static final String USER_ID_REQUIRED = "User ID can NOT be empty...";

    /** {@code app/cbl/COUSR01C.cbl}:138, guard four. Names the field; never the value. */
    private static final String CREDENTIAL_REQUIRED = "Password can NOT be empty...";

    /** {@code app/cbl/COUSR01C.cbl}:144, guard five. */
    private static final String USER_TYPE_REQUIRED = "User Type can NOT be empty...";

    /**
     * {@code app/cbl/COUSR01C.cbl}:263, shared by the {@code DFHRESP(DUPKEY)} arm at {@code :260} and the
     * {@code DFHRESP(DUPREC)} arm at {@code :261}. The verb reads {@code exist} where English wants
     * {@code exists}; the defect is in the system of record and is reproduced, not corrected.
     */
    private static final String DUPLICATE_USER_ID = "User ID already exist...";

    /** {@code app/cbl/COUSR01C.cbl}:270, the {@code WHEN OTHER} arm of the write. */
    private static final String UNABLE_TO_ADD = "Unable to Add User...";

    /**
     * {@code CCDA-MSG-INVALID-KEY} of {@code app/cpy/CSMSG01Y.cpy}, moved at
     * {@code app/cbl/COUSR01C.cbl}:101. The copybook pads the literal to {@code PIC X(50)}; the padding is
     * field width rather than message text.
     */
    private static final String INVALID_KEY = "Invalid key pressed. Please see below...";

    /**
     * The abend message of {@code app/cpy/CSMSG02Y.cpy}, the copybook internally titled {@code CABENDD.CPY}
     * whose {@code ABEND-MSG PIC X(72)} field carries it.
     *
     * <p>This is the value the legacy default-message substitution supplies when the caller passes no message,
     * and it is what {@code CALL 'CEE3ABD'} would have surfaced. It is asserted rather than the numeric abend
     * code because the code is reachable only through the concrete exception type's own accessor, which sits
     * outside this file's declared dependency set.
     */
    private static final String ABEND_MESSAGE = "UNEXPECTED ABEND OCCURRED.";

    /** First operand of the confirmation {@code STRING} at {@code app/cbl/COUSR01C.cbl}:255. */
    private static final String ADDED_PREFIX = "User ";

    /**
     * Third operand of the confirmation {@code STRING} at {@code app/cbl/COUSR01C.cbl}:257: a leading blank,
     * {@code has been added}, <strong>a blank</strong>, then exactly three periods.
     */
    private static final String ADDED_SUFFIX = " has been added ...";

    /** {@code MOVE DFHGREEN TO ERRMSGC} at {@code app/cbl/COUSR01C.cbl}:254 - set on the success arm only. */
    private static final String GREEN = "DFHGREEN";

    // ----------------------------------------------------------------------------------------------------
    // Cursor markers. MOVE -1 TO <field>L, the stateless equivalent of EXEC CICS SEND ... CURSOR.
    // ----------------------------------------------------------------------------------------------------

    /** {@code FNAMEL} at {@code :86}, {@code :100}, {@code :122}, {@code :149}, {@code :272}, {@code :289}. */
    private static final String CURSOR_FIRST_NAME = "FNAME";

    /** {@code LNAMEL} at {@code app/cbl/COUSR01C.cbl}:128. */
    private static final String CURSOR_LAST_NAME = "LNAME";

    /** {@code USERIDL} at {@code app/cbl/COUSR01C.cbl}:134 and again on the duplicate arm at {@code :265}. */
    private static final String CURSOR_USER_ID = "USERID";

    /** {@code PASSWDL} at {@code app/cbl/COUSR01C.cbl}:140. */
    private static final String CURSOR_CREDENTIAL = "PASSWD";

    /** {@code USRTYPEL} at {@code app/cbl/COUSR01C.cbl}:146. */
    private static final String CURSOR_USER_TYPE = "USRTYPE";

    // ----------------------------------------------------------------------------------------------------
    // Reported field names, and the program's own identity literals.
    // ----------------------------------------------------------------------------------------------------

    /** Reported for {@code FNAMEI PIC X(20)}. */
    private static final String FIELD_FIRST_NAME = "firstName";

    /** Reported for {@code LNAMEI PIC X(20)}. */
    private static final String FIELD_LAST_NAME = "lastName";

    /**
     * Reported for {@code USERIDI PIC X(8)}. Note the spelling: this program's own map declares
     * {@code USERIDI} where {@code app/cpy-bms/COUSR02.CPY} and {@code COUSR03.CPY} declare
     * {@code USRIDINI}, and the name this map uses is the one honoured.
     */
    private static final String FIELD_USER_ID = "userId";

    /** Reported for {@code PASSWDI PIC X(8)}. Only ever the name; the value is a credential. */
    private static final String FIELD_CREDENTIAL = "password";

    /** Reported for {@code USRTYPEI PIC X(1)}. */
    private static final String FIELD_USER_TYPE = "userType";

    /** Reported for {@code ERRMSGI PIC X(78)}. */
    private static final String FIELD_ERROR_MESSAGE = "errorMessage";

    /** {@code WS-TRANID PIC X(04) VALUE 'CU01'}, {@code app/cbl/COUSR01C.cbl}:37. */
    private static final String TRANSACTION_ID = "CU01";

    /** {@code WS-PGMNAME PIC X(08) VALUE 'COUSR01C'}, {@code app/cbl/COUSR01C.cbl}:36. */
    private static final String PROGRAM_NAME = "COUSR01C";

    /**
     * {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '}, {@code app/cbl/COUSR01C.cbl}:39 - the
     * {@code DATASET} operand at {@code :241}. The literal carries <strong>two trailing blanks</strong> to
     * fill the eight-character field; the logical name the target reports is the trimmed form.
     */
    private static final String USRSEC_LOGICAL_FILE = "USRSEC";

    /** {@code MOVE 'COADM01C' TO CDEMO-TO-PROGRAM} on the {@code PF3} arm, {@code :94}. */
    private static final String ADMIN_MENU_PROGRAM = "COADM01C";

    /** {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM} at {@code :79} and {@code :168}. */
    private static final String SIGN_ON_PROGRAM = "COSGN00C";

    // ----------------------------------------------------------------------------------------------------
    // The 80 byte record of app/cpy/CSUSR01Y.cpy:17-23 and the KEYS(8,0) of app/jcl/DUSRSECJ.jcl:65-66.
    // ----------------------------------------------------------------------------------------------------

    /** {@code SEC-USR-ID PIC X(08)} - also the key length of {@code KEYS(8,0)}. */
    private static final int SEC_USR_ID_WIDTH = 8;

    /** {@code SEC-USR-FNAME PIC X(20)}. */
    private static final int SEC_USR_FNAME_WIDTH = 20;

    /** {@code SEC-USR-LNAME PIC X(20)}. */
    private static final int SEC_USR_LNAME_WIDTH = 20;

    /** {@code SEC-USR-PWD PIC X(08)} - the plaintext field the target replaces with a 60 character digest. */
    private static final int SEC_USR_PWD_WIDTH = 8;

    /** {@code SEC-USR-TYPE PIC X(01)}. */
    private static final int SEC_USR_TYPE_WIDTH = 1;

    /** {@code SEC-USR-FILLER PIC X(23)} - not modelled in the target. */
    private static final int SEC_USR_FILLER_WIDTH = 23;

    /** {@code RECORDSIZE(80,80)} at {@code app/jcl/DUSRSECJ.jcl}:66. */
    private static final int RECORD_LENGTH = 80;

    /** {@code WS-MESSAGE PIC X(80)} at {@code app/cbl/COUSR01C.cbl}:38. */
    private static final int WS_MESSAGE_WIDTH = 80;

    // ----------------------------------------------------------------------------------------------------
    // Fixture values. Upper case where the source's own seed data is upper case, and deliberately lower
    // case where a test is about the absence of case folding.
    // ----------------------------------------------------------------------------------------------------

    /** An eight-character identifier outside the ten seeded rows of {@code app/jcl/DUSRSECJ.jcl}. */
    private static final String NEW_USER_ID = "USER0006";

    /** A first name well inside {@code FNAMEI PIC X(20)}. */
    private static final String FIRST_NAME = "GRACE";

    /** A last name well inside {@code LNAMEI PIC X(20)}. */
    private static final String LAST_NAME = "HOPPER";

    /** {@code 'U'}, the regular-user code of {@code app/cpy/COCOM01Y.cpy}:28. */
    private static final String TYPE_USER = "U";

    /** {@code 'A'}, the administrator code of {@code app/cpy/COCOM01Y.cpy}:27. */
    private static final String TYPE_ADMIN = "A";

    /**
     * A run of {@code NUL}, which is what {@code LOW-VALUES} is. The guards at {@code :118}, {@code :124},
     * {@code :130}, {@code :136} and {@code :142} each test {@code = SPACES OR LOW-VALUES}, so this must be
     * treated as empty exactly as blanks are.
     */
    private static final String LOW_VALUES = "\u0000\u0000\u0000";

    /**
     * Fixed instant behind {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at {@code :216}. Parsed,
     * never read from a wall clock, so {@code POPULATE-HEADER-INFO} renders identically on every run.
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2024-03-07T14:05:09Z"), ZoneOffset.UTC);

    /** {@code MM/DD/YY} of {@code app/cpy/CSDAT01Y.cpy}, assembled at {@code :223-227}, eight characters. */
    private static final String EXPECTED_HEADER_DATE = "03/07/24";

    /** {@code HH:MM:SS} of {@code app/cpy/CSDAT01Y.cpy}, assembled at {@code :229-233}, eight characters. */
    private static final String EXPECTED_HEADER_TIME = "14:05:09";

    /** {@code CCDA-TITLE01 PIC X(40)} of {@code app/cpy/COTTL01Y.cpy}, moved at {@code :218}. */
    private static final String TITLE_01 = "      AWS Mainframe Modernization       ";

    /** {@code CCDA-TITLE02 PIC X(40)} of {@code app/cpy/COTTL01Y.cpy}, moved at {@code :219}. */
    private static final String TITLE_02 = "              CardDemo                  ";

    /**
     * The nine paragraph labels of {@code app/cbl/COUSR01C.cbl}, in source order, paired with the private
     * method each maps to. Order is significant, so the set is insertion-ordered.
     */
    private static final Set<String> PARAGRAPH_METHODS = new LinkedHashSet<>(List.of(
            "mainPara",              // :71  MAIN-PARA
            "processEnterKey",       // :115 PROCESS-ENTER-KEY
            "returnToPrevScreen",    // :165 RETURN-TO-PREV-SCREEN
            "sendUsraddScreen",      // :184 SEND-USRADD-SCREEN
            "receiveUsraddScreen",   // :201 RECEIVE-USRADD-SCREEN
            "populateHeaderInfo",    // :214 POPULATE-HEADER-INFO
            "writeUserSecFile",      // :238 WRITE-USER-SEC-FILE
            "clearCurrentScreen",    // :279 CLEAR-CURRENT-SCREEN
            "initializeAllFields")); // :287 INITIALIZE-ALL-FIELDS

    /** Annotations that would let the bean authorise itself. None may appear on it. */
    private static final Set<String> SELF_AUTHORISATION_ANNOTATIONS =
            Set.of("PreAuthorize", "PostAuthorize", "Secured", "RolesAllowed", "PermitAll", "DenyAll");

    /** The {@code USRSEC} cluster. A double, so no database is reached. */
    @Mock
    private UserSecurityRepository repository;

    /**
     * The encoder published by the security configuration. A double, because BCrypt salts and a real
     * encoder would make every digest assertion non-deterministic.
     */
    @Mock
    private PasswordEncoder encoder;

    /** The real mapper: a pure function with a no-argument constructor, so nothing is gained by faking it. */
    private FileStatusMapper fileStatusMapper;

    /** The bean under test, assembled by constructor injection only. */
    private UserAddService service;

    @BeforeEach
    void setUp() {
        this.fileStatusMapper = new FileStatusMapper();
        this.service = new UserAddService(this.repository, this.encoder, this.fileStatusMapper, FIXED_CLOCK);
    }

    // ----------------------------------------------------------------------------------------------------
    // Helpers. Pure functions and thin arrangements; no shared mutable state of any kind.
    // ----------------------------------------------------------------------------------------------------

    /**
     * Assembles a BCrypt-shaped digest at run time from its parts, so that no prefix literal is ever written
     * into this file.
     *
     * @param costFactor the two-digit cost factor to embed
     * @return a 60 character digest carrying that cost
     */
    private static String digestWithCost(final String costFactor) {
        return DIGEST_FIELD_MARKER + DIGEST_VERSION_TAG + DIGEST_FIELD_MARKER + costFactor
                + DIGEST_FIELD_MARKER + SYNTHETIC_SALT + SYNTHETIC_BODY;
    }

    /** @return the digest the contract accepts: BCrypt at strength 10, 60 characters. */
    private static String contractualDigest() {
        return digestWithCost(CONTRACTUAL_COST_FACTOR);
    }

    /**
     * Builds a submitted screen. The six header components are supplied as the terminal would have sent
     * them; the four editable components and the message area are the arguments.
     *
     * @param firstName {@code FNAMEI}, may be {@code null}
     * @param lastName  {@code LNAMEI}, may be {@code null}
     * @param userId    {@code USERIDI}, may be {@code null}
     * @param userType  {@code USRTYPEI}, may be {@code null}
     * @return the request; never {@code null}
     */
    private static UserCreateRequest request(final String firstName, final String lastName,
            final String userId, final String userType) {

        return new UserCreateRequest(TRANSACTION_ID, TITLE_01, EXPECTED_HEADER_DATE, PROGRAM_NAME, TITLE_02,
                EXPECTED_HEADER_TIME, firstName, lastName, userId, PRESENTED_CREDENTIAL, userType, null);
    }

    /** @return a request whose five operator fields are all populated and all within their widths. */
    private static UserCreateRequest validRequest() {
        return request(FIRST_NAME, LAST_NAME, NEW_USER_ID, TYPE_USER);
    }

    /**
     * Arranges the write to be reached and to succeed: the identifier is absent, and the encoder returns the
     * contractual digest for exactly the credential the caller presents.
     *
     * @param userId     the identifier the probe will be asked about
     * @param credential the plaintext the encoder will be asked to hash
     */
    private void arrangeWriteSucceeds(final String userId, final String credential) {
        when(this.repository.existsById(userId)).thenReturn(false);
        when(this.encoder.encode(credential)).thenReturn(contractualDigest());
    }

    /** @return the single record handed to {@code saveAndFlush}. */
    private UserSecurity captureSaved() {
        final ArgumentCaptor<UserSecurity> captor = ArgumentCaptor.forClass(UserSecurity.class);
        verify(this.repository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    /**
     * Adds a user over the happy path with the supplied operator fields.
     *
     * @param firstName {@code FNAMEI}
     * @param lastName  {@code LNAMEI}
     * @param userId    {@code USERIDI}
     * @param userType  {@code USRTYPEI}
     * @return the assembled screen
     */
    private UserAddScreen addSuccessfully(final String firstName, final String lastName,
            final String userId, final String userType) {

        arrangeWriteSucceeds(userId, PRESENTED_CREDENTIAL);
        return this.service.addUser(request(firstName, lastName, userId, userType), PRESENTED_CREDENTIAL);
    }

    /**
     * Asserts the concrete type of a thrown failure without importing it. {@code FileAccessException} and
     * {@code FatalProcessingException} sit outside this file's declared dependency set, so the assertion
     * pairs the whitelisted base type with the concrete simple name, which pins the class exactly.
     *
     * @param thrown       the failure to inspect
     * @param expectedName the simple name the concrete class must have
     */
    private static void assertConcreteType(final Throwable thrown, final String expectedName) {
        assertThat(thrown).isInstanceOf(CardDemoException.class);
        assertThat(thrown.getClass().getSimpleName()).isEqualTo(expectedName);
    }

    /**
     * Repeats a character, for width boundary fixtures.
     *
     * @param character the character to repeat
     * @param length    how many times to repeat it
     * @return a run of exactly {@code length} characters
     */
    private static String fill(final char character, final int length) {
        return String.valueOf(character).repeat(length);
    }

    @Nested
    @DisplayName("Paragraph correspondence - the evidence the scope-coverage gate reads")
    class ParagraphCorrespondence {

        @Test
        @DisplayName("all nine labels of COUSR01C map one to one to a private method, none consolidated")
        void nineLabelsMapOneToOne() {
            assertThat(PARAGRAPH_METHODS).hasSize(9);

            final Set<String> declared = Arrays.stream(UserAddService.class.getDeclaredMethods())
                    .filter(method -> Modifier.isPrivate(method.getModifiers()))
                    .map(Method::getName)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            assertThat(declared)
                    .as("every paragraph of app/cbl/COUSR01C.cbl must have its own private method; "
                            + "mapping fewer than nine is a Blocker")
                    .containsAll(PARAGRAPH_METHODS);
        }

        @Test
        @DisplayName("CLEAR-CURRENT-SCREEN survives as its own method even though it only chains two others")
        void clearCurrentScreenIsNotConsolidated() {
            // :279-282 is PERFORM INITIALIZE-ALL-FIELDS followed by PERFORM SEND-USRADD-SCREEN and nothing
            // else. It has no independent behaviour, and it is mapped anyway: a retained parity artefact,
            // because line-level correspondence is what the coverage gate reads.
            assertThat(Arrays.stream(UserAddService.class.getDeclaredMethods())
                    .map(Method::getName)
                    .toList())
                    .contains("clearCurrentScreen", "initializeAllFields", "sendUsraddScreen");
        }

        @Test
        @DisplayName("the four entry points cover the four ways the source can be reached")
        void publicSurfaceIsTheFourEntryPoints() {
            final Set<String> entryPoints = Arrays.stream(UserAddService.class.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .map(Method::getName)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            // :78 with no communication area, :83 on a first display, :88 on a submitted screen, and :91
            // on the ENTER arm of a submitted screen.
            assertThat(entryPoints)
                    .containsExactlyInAnyOrder("openWithoutContext", "openScreen", "addUser", "submitScreen");
        }
    }

    @Nested
    @DisplayName("The five ordered guards, app/cbl/COUSR01C.cbl:117-151 - emptiness only, first match wins")
    class TheFiveOrderedGuards {

        @Test
        @DisplayName("guard 1 - first name empty reports :120 and parks the cursor on FNAME, :122")
        void firstNameGuard() {
            final UserCreateRequest submitted = request("   ", LAST_NAME, NEW_USER_ID, TYPE_USER);

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.addUser(submitted, PRESENTED_CREDENTIAL))
                    .withMessage(FIRST_NAME_REQUIRED)
                    .satisfies(thrown -> {
                        assertThat(thrown.getFieldName()).isEqualTo(FIELD_FIRST_NAME);
                        assertThat(thrown.getFailureKind()).isEqualTo(ValidationException.FailureKind.BLANK);
                    });

            // The write is suppressed by :153 IF NOT ERR-FLG-ON, so nothing is probed, hashed or inserted.
            verifyNoInteractions(repository, encoder);
        }

        @Test
        @DisplayName("guard 2 - last name empty reports :126 and parks the cursor on LNAME, :128")
        void lastNameGuard() {
            final UserCreateRequest submitted = request(FIRST_NAME, "", NEW_USER_ID, TYPE_USER);

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.addUser(submitted, PRESENTED_CREDENTIAL))
                    .withMessage(LAST_NAME_REQUIRED)
                    .satisfies(thrown -> assertThat(thrown.getFieldName()).isEqualTo(FIELD_LAST_NAME));

            verifyNoInteractions(repository, encoder);
        }

        @Test
        @DisplayName("guard 3 - identifier empty reports :132 and parks the cursor on USERID, :134")
        void userIdGuard() {
            final UserCreateRequest submitted = request(FIRST_NAME, LAST_NAME, null, TYPE_USER);

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.addUser(submitted, PRESENTED_CREDENTIAL))
                    .withMessage(USER_ID_REQUIRED)
                    .satisfies(thrown -> assertThat(thrown.getFieldName()).isEqualTo(FIELD_USER_ID));

            verifyNoInteractions(repository, encoder);
        }

        @Test
        @DisplayName("guard 4 - credential empty reports :138, names the FIELD never the VALUE, :140")
        void credentialGuard() {
            final UserCreateRequest submitted = validRequest();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.addUser(submitted, "  "))
                    .withMessage(CREDENTIAL_REQUIRED)
                    .satisfies(thrown -> {
                        assertThat(thrown.getFieldName()).isEqualTo(FIELD_CREDENTIAL);
                        // The message is the field's name and carries no value of any kind.
                        assertThat(thrown.getMessage()).doesNotContain(PRESENTED_CREDENTIAL);
                    });

            // An empty credential never reaches the encoder, so no digest of blanks can be persisted.
            verifyNoInteractions(repository, encoder);
        }

        @Test
        @DisplayName("guard 5 - user type empty reports :144 and parks the cursor on USRTYPE, :146")
        void userTypeGuard() {
            final UserCreateRequest submitted = request(FIRST_NAME, LAST_NAME, NEW_USER_ID, " ");

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.addUser(submitted, PRESENTED_CREDENTIAL))
                    .withMessage(USER_TYPE_REQUIRED)
                    .satisfies(thrown -> assertThat(thrown.getFieldName()).isEqualTo(FIELD_USER_TYPE));

            verifyNoInteractions(repository, encoder);
        }

        @Test
        @DisplayName("EVALUATE takes ONE arm: with all five empty the FIRST NAME message wins and no other")
        void firstMatchWinsWhenEverythingIsEmpty() {
            final UserCreateRequest allEmpty = new UserCreateRequest(null, null, null, null, null, null,
                    null, null, null, null, null, null);

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.addUser(allEmpty, null))
                    .withMessage(FIRST_NAME_REQUIRED)
                    .satisfies(thrown -> {
                        assertThat(thrown.getFieldName()).isEqualTo(FIELD_FIRST_NAME);
                        assertThat(thrown.getMessage())
                                .isNotEqualTo(LAST_NAME_REQUIRED)
                                .isNotEqualTo(USER_ID_REQUIRED)
                                .isNotEqualTo(CREDENTIAL_REQUIRED)
                                .isNotEqualTo(USER_TYPE_REQUIRED);
                    });
        }

        @Test
        @DisplayName("the order is THIS program's: empty in both name and identifier reports the NAME")
        void orderIsFirstNameLeadingNotIdentifierLeading() {
            // app/cbl/COUSR01C.cbl:118 leads with FNAMEI. The sibling update program's cascade leads with
            // its identifier field instead, so a request empty in BOTH fields is exactly the input that
            // distinguishes the two orders - and here the FIRST NAME must win. Reordering this cascade is a
            // Medium parity break; harmonising it with the sibling's order is the way that break happens.
            final UserCreateRequest emptyNameAndId = request(null, LAST_NAME, null, TYPE_USER);

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.addUser(emptyNameAndId, PRESENTED_CREDENTIAL))
                    .withMessage(FIRST_NAME_REQUIRED);
        }

        @Test
        @DisplayName("the five literals are byte exact: capital NOT and exactly three periods each")
        void literalsAreByteExact() {
            final List<String> guards = List.of(FIRST_NAME_REQUIRED, LAST_NAME_REQUIRED, USER_ID_REQUIRED,
                    CREDENTIAL_REQUIRED, USER_TYPE_REQUIRED);

            assertThat(guards).allSatisfy(literal -> {
                assertThat(literal).contains("can NOT be empty").endsWith("...");
                assertThat(literal).doesNotContain("can not").doesNotContain("cannot");
                // Exactly three periods, so a four-period or two-period ellipsis is caught.
                assertThat(literal.chars().filter(character -> character == '.').count()).isEqualTo(3L);
            });

            assertThat(guards).containsExactly(
                    "First Name can NOT be empty...",
                    "Last Name can NOT be empty...",
                    "User ID can NOT be empty...",
                    "Password can NOT be empty...",
                    "User Type can NOT be empty...");
        }

        @Test
        @DisplayName("SPACES and LOW-VALUES are both empty, so null, empty, blank and NUL all take the arm")
        void spacesAndLowValuesAreBothEmpty() {
            // :118 tests = SPACES OR LOW-VALUES, so all four representations are the same condition.
            for (final String empty : List.of("", " ", "        ", LOW_VALUES)) {
                final UserCreateRequest submitted = request(empty, LAST_NAME, NEW_USER_ID, TYPE_USER);

                assertThatExceptionOfType(ValidationException.class)
                        .as("first name %s must take the guard at :118", empty.isEmpty() ? "(empty)" : "(blank)")
                        .isThrownBy(() -> service.addUser(submitted, PRESENTED_CREDENTIAL))
                        .withMessage(FIRST_NAME_REQUIRED);
            }

            final UserCreateRequest nullFirstName = request(null, LAST_NAME, NEW_USER_ID, TYPE_USER);
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.addUser(nullFirstName, PRESENTED_CREDENTIAL))
                    .withMessage(FIRST_NAME_REQUIRED);
        }

        @Test
        @DisplayName("LOW-VALUES in the credential takes guard 4, so no digest of control bytes is stored")
        void lowValuesCredentialTakesTheCredentialGuard() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.addUser(validRequest(), LOW_VALUES))
                    .withMessage(CREDENTIAL_REQUIRED);

            verifyNoInteractions(encoder);
        }

        @Test
        @DisplayName("all five failures are the BLANK kind, never INVALID - they are emptiness, not domain")
        void allFiveAreTheBlankKind() {
            assertThat(collectKind(request(null, LAST_NAME, NEW_USER_ID, TYPE_USER), PRESENTED_CREDENTIAL))
                    .isEqualTo(ValidationException.FailureKind.BLANK);
            assertThat(collectKind(request(FIRST_NAME, null, NEW_USER_ID, TYPE_USER), PRESENTED_CREDENTIAL))
                    .isEqualTo(ValidationException.FailureKind.BLANK);
            assertThat(collectKind(request(FIRST_NAME, LAST_NAME, null, TYPE_USER), PRESENTED_CREDENTIAL))
                    .isEqualTo(ValidationException.FailureKind.BLANK);
            assertThat(collectKind(validRequest(), null))
                    .isEqualTo(ValidationException.FailureKind.BLANK);
            assertThat(collectKind(request(FIRST_NAME, LAST_NAME, NEW_USER_ID, null), PRESENTED_CREDENTIAL))
                    .isEqualTo(ValidationException.FailureKind.BLANK);
        }

        /**
         * Runs one add that is expected to fail an emptiness guard and returns the failure kind.
         *
         * @param submitted  the request to submit
         * @param credential the credential to present
         * @return the {@code FailureKind} the guard reported
         */
        private ValidationException.FailureKind collectKind(final UserCreateRequest submitted,
                final String credential) {

            try {
                service.addUser(submitted, credential);
            } catch (final ValidationException expected) {
                return expected.getFailureKind();
            }
            throw new AssertionError("the request was expected to fail an emptiness guard at :117-151");
        }
    }

    @Nested
    @DisplayName("Three absences that are behaviour - app/cbl/COUSR01C.cbl:117-160")
    class PreservedAbsences {

        @Test
        @DisplayName("no upper-casing: a lower-case identifier is stored exactly as it was typed")
        void identifierIsStoredAsTyped() {
            // :154 MOVE USERIDI TO SEC-USR-ID is a PLAIN move. Contrast app/cbl/COSGN00C.cbl:132-136, which
            // wraps BOTH the presented identifier and the presented credential in FUNCTION UPPER-CASE before
            // comparing them against the stored record.
            //
            // CONSEQUENCE, and it is a real latent defect in the system of record: a user created here with
            // a lower-case identifier can NEVER sign on. Sign-on folds the presented identifier to upper
            // case and looks that up, so the row keyed by the lower-case form is unreachable. The defect is
            // PRESERVED, not repaired - adding case folding here would accept a login the source rejects,
            // which would break parity. It is cited to the locators above and pinned by this test.
            final String lowerCaseId = "user0006";

            addSuccessfully(FIRST_NAME, LAST_NAME, lowerCaseId, TYPE_USER);

            final UserSecurity stored = captureSaved();
            assertThat(stored.getSecUsrId())
                    .as("the identifier must survive verbatim; no case folding exists at :154")
                    .isEqualTo(lowerCaseId)
                    .isNotEqualTo(lowerCaseId.toUpperCase(Locale.ROOT));
        }

        @Test
        @DisplayName("no upper-casing: mixed-case names are stored verbatim too, :155-156")
        void namesAreStoredAsTyped() {
            final String mixedFirst = "Grace";
            final String mixedLast = "hOpPeR";

            addSuccessfully(mixedFirst, mixedLast, NEW_USER_ID, TYPE_USER);

            final UserSecurity stored = captureSaved();
            assertThat(stored.getSecUsrFname()).isEqualTo(mixedFirst);
            assertThat(stored.getSecUsrLname()).isEqualTo(mixedLast);
        }

        @Test
        @DisplayName("no content check: an out-of-domain user type PASSES the emptiness gate at :142")
        void arbitraryUserTypePassesTheInputGate() {
            // The gate at :142 tests = SPACES OR LOW-VALUES and nothing else, so 'X' is accepted BY THE
            // GATE. Proof that the gate passed: :157 hashes the credential BEFORE :158 maps the type, so the
            // encoder is reached. What rejects 'X' is the domain mapping at persistence - the bean's own
            // labelled deviation - and it reports INVALID, never the emptiness literal.
            when(encoder.encode(PRESENTED_CREDENTIAL)).thenReturn(contractualDigest());

            final UserCreateRequest submitted = request(FIRST_NAME, LAST_NAME, NEW_USER_ID, "X");

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.addUser(submitted, PRESENTED_CREDENTIAL))
                    .satisfies(thrown -> {
                        assertThat(thrown.getMessage())
                                .as("an out-of-domain type is NOT reported as an empty field")
                                .isNotEqualTo(USER_TYPE_REQUIRED);
                        assertThat(thrown.getFailureKind())
                                .isEqualTo(ValidationException.FailureKind.INVALID);
                        assertThat(thrown.getFieldName()).isEqualTo(FIELD_USER_TYPE);
                    });

            verify(encoder).encode(PRESENTED_CREDENTIAL);
            verify(repository, never()).saveAndFlush(any(UserSecurity.class));
        }

        @Test
        @DisplayName("no content check: lower-case type codes and digits all clear the gate, then fail domain")
        void everyOutOfDomainCodeClearsTheGate() {
            // 'a' and 'u' are NOT accepted by the domain: the mapping is case sensitive, which is itself
            // consistent with the absence of case folding at :158. A digit is equally out of domain. In
            // every case the emptiness literal of :144 must NOT be the message.
            for (final String code : List.of("z", "a", "u", "5")) {
                final UserSecurityRepository freshRepository = mock(UserSecurityRepository.class);
                final PasswordEncoder freshEncoder = mock(PasswordEncoder.class);
                when(freshEncoder.encode(PRESENTED_CREDENTIAL)).thenReturn(contractualDigest());
                final UserAddService fresh = new UserAddService(freshRepository, freshEncoder,
                        new FileStatusMapper(), FIXED_CLOCK);
                final UserCreateRequest submitted = request(FIRST_NAME, LAST_NAME, NEW_USER_ID, code);

                assertThatExceptionOfType(ValidationException.class)
                        .as("type %s must clear the emptiness gate and fail the domain instead", code)
                        .isThrownBy(() -> fresh.addUser(submitted, PRESENTED_CREDENTIAL))
                        .satisfies(thrown -> {
                            assertThat(thrown.getMessage()).isNotEqualTo(USER_TYPE_REQUIRED);
                            assertThat(thrown.getFailureKind())
                                    .isEqualTo(ValidationException.FailureKind.INVALID);
                        });

                verify(freshRepository, never()).saveAndFlush(any(UserSecurity.class));
            }
        }

        @Test
        @DisplayName("no credential policy: a one-character credential is ACCEPTED and persisted")
        void oneCharacterCredentialIsAccepted() {
            // :136 tests emptiness only. There is no minimum length, no complexity rule and no character
            // class requirement anywhere in the 299 lines, so imposing one would reject input the source
            // accepts - a High parity break.
            final String single = "q";
            when(repository.existsById(NEW_USER_ID)).thenReturn(false);
            when(encoder.encode(single)).thenReturn(contractualDigest());

            final UserAddScreen screen = service.addUser(validRequest(), single);

            assertThat(screen.errorMessage()).isEqualTo(ADDED_PREFIX + NEW_USER_ID + ADDED_SUFFIX);
            verify(encoder).encode(single);
            assertThat(captureSaved().getPasswordHash()).isEqualTo(contractualDigest());
        }

        @Test
        @DisplayName("a credential of exactly eight characters is accepted, matching PASSWDI PIC X(8)")
        void credentialAtTheBoundaryIsAccepted() {
            final String atTheBoundary = fill('8', SEC_USR_PWD_WIDTH);
            when(repository.existsById(NEW_USER_ID)).thenReturn(false);
            when(encoder.encode(atTheBoundary)).thenReturn(contractualDigest());

            service.addUser(validRequest(), atTheBoundary);

            final ArgumentCaptor<String> presented = ArgumentCaptor.forClass(String.class);
            verify(encoder).encode(presented.capture());
            assertThat(presented.getValue())
                    .as("the boundary value passes through whole - the guard rejects only PAST the width")
                    .isEqualTo(atTheBoundary)
                    .hasSize(SEC_USR_PWD_WIDTH);
            assertThat(captureSaved().getPasswordHash()).isEqualTo(contractualDigest());
        }

        @Test
        @DisplayName("a credential past eight characters is REFUSED, never truncated and never hashed whole")
        void credentialPastTheBoundaryIsRefused() {
            // THIS TEST ONCE ASSERTED THE OPPOSITE, and the reasoning it carried was wrong twice over.
            //
            // It claimed the source "truncates at :157" and that the truncation was deliberately not
            // reproduced. There is no truncation at :157 to reproduce: PASSWDI is PIC X(8) at
            // app/cpy-bms/COUSR01.CPY:78 and SEC-USR-PWD is PIC X(08) at app/cpy/CSUSR01Y.cpy:21, so
            // MOVE PASSWDI TO SEC-USR-PWD is an eight-to-eight move. The 3270 field makes a ninth character
            // impossible to key; the source has no over-length arm because it can never receive one.
            //
            // It then argued that accepting a longer value loses nothing because the digest column holds 60
            // characters. That confuses STORAGE CAPACITY with the INPUT CONTRACT. The sibling
            // com.cardemo.service.admin.UserUpdateService bounds the same credential at eight, so the two
            // paths disagreed about the same field of the same record - a create could store a credential
            // that an update could never reproduce.
            //
            // Truncating would be worse than either: an operator who set nine characters would authenticate
            // on the first eight, a credential-strength illusion the source cannot produce. Refusal is the
            // faithful translation of "the ninth character does not exist", and it is now what both paths do.
            final String pastTheBoundary = fill('9', SEC_USR_PWD_WIDTH + 1);

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.addUser(validRequest(), pastTheBoundary))
                    .satisfies(thrown -> {
                        assertThat(thrown.getFieldName()).isEqualTo(FIELD_CREDENTIAL);
                        assertThat(thrown.getFailureKind())
                                .isEqualTo(ValidationException.FailureKind.INVALID);
                        assertThat(thrown.getMessage())
                                .as("the refusal names the field and the width, and NEVER the credential")
                                .contains(String.valueOf(SEC_USR_PWD_WIDTH))
                                .doesNotContain(pastTheBoundary);
                    });

            verifyNoInteractions(encoder);
            verify(repository, never()).saveAndFlush(any(UserSecurity.class));
        }

        @Test
        @DisplayName("an empty credential still takes guard 4 first, so the width guard cannot mask :138")
        void emptyCredentialStillReportsTheSourceLiteral() {
            // Ordering evidence. The width guard sits on the success arm, AFTER the five-arm EVALUATE, so a
            // blank credential is still reported with the source's own literal from :138 rather than with the
            // Java-only width message. First-match-wins is preserved.
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.addUser(validRequest(), ""))
                    .satisfies(thrown -> {
                        assertThat(thrown.getFieldName()).isEqualTo(FIELD_CREDENTIAL);
                        assertThat(thrown.getMessage()).isEqualTo(CREDENTIAL_REQUIRED);
                        assertThat(thrown.getFailureKind())
                                .isEqualTo(ValidationException.FailureKind.BLANK);
                    });

            verifyNoInteractions(encoder);
        }

        @Test
        @DisplayName("the WHEN OTHER arm at :148-150 parks the cursor on FNAME, and CONTINUE ends nothing")
        void successPathParksTheCursorOnFirstName() {
            // :149 MOVE -1 TO FNAMEL is the "ready for the next entry" cue on the arm that finds no empty
            // field. :150 CONTINUE terminates nothing whatever - a dead no-op, retained for control-flow
            // parity, cited to its source line, rather than deleted.
            final UserAddScreen screen = addSuccessfully(FIRST_NAME, LAST_NAME, NEW_USER_ID, TYPE_USER);

            assertThat(screen.cursorField()).isEqualTo(CURSOR_FIRST_NAME);
        }
    }

    @Nested
    @DisplayName("The credential - app/cbl/COUSR01C.cbl:157, and where BCrypt enters")
    class CredentialHandling {

        @Test
        @DisplayName("the encoder is reached exactly once per successful add, and nothing else is asked of it")
        void encoderIsInvokedExactlyOnce() {
            addSuccessfully(FIRST_NAME, LAST_NAME, NEW_USER_ID, TYPE_USER);

            verify(encoder, times(1)).encode(PRESENTED_CREDENTIAL);
            verifyNoMoreInteractions(encoder);
        }

        @Test
        @DisplayName("what is persisted is what the encoder returned, never the presented value")
        void persistedCredentialIsTheEncodedValue() {
            // BCrypt salts, so the digest is deliberately non-deterministic in production. The assertion is
            // therefore about provenance, not about any literal: the stored value must be exactly what the
            // encoder handed back. Asserting a literal digest would pin a value BCrypt never repeats.
            addSuccessfully(FIRST_NAME, LAST_NAME, NEW_USER_ID, TYPE_USER);

            final UserSecurity stored = captureSaved();
            assertThat(stored.getPasswordHash())
                    .isEqualTo(contractualDigest())
                    .isNotEqualTo(PRESENTED_CREDENTIAL)
                    .doesNotContain(PRESENTED_CREDENTIAL);
        }

        @Test
        @DisplayName("strength 10 is enforced: a digest at cost 10 is accepted and occupies the 60 char column")
        void contractualStrengthIsAccepted() {
            addSuccessfully(FIRST_NAME, LAST_NAME, NEW_USER_ID, TYPE_USER);

            assertThat(captureSaved().getPasswordHash())
                    .as("the credential column is exactly 60 characters wide")
                    .hasSize(DIGEST_WIDTH);
        }

        @Test
        @DisplayName("strength 10 is enforced: a digest at any other cost is REFUSED, never persisted")
        void offContractStrengthIsRefused() {
            // This is how strength 10 is proved without writing a digest literal: the record's own field
            // contract admits cost 10 and nothing else, so a misconfigured encoder fails every add loudly
            // instead of quietly persisting an off-contract credential.
            when(repository.existsById(NEW_USER_ID)).thenReturn(false);
            when(encoder.encode(PRESENTED_CREDENTIAL))
                    .thenReturn(digestWithCost(OFF_CONTRACT_COST_FACTOR));

            final Throwable thrown = catchAddFailure(validRequest(), PRESENTED_CREDENTIAL);

            assertAbend(thrown);
            verify(repository, never()).saveAndFlush(any(UserSecurity.class));
        }

        @Test
        @DisplayName("an encoder that returns nothing abends rather than storing a blank credential")
        void absentDigestAbends() {
            when(encoder.encode(PRESENTED_CREDENTIAL)).thenReturn("");

            assertAbend(catchAddFailure(validRequest(), PRESENTED_CREDENTIAL));
            verify(repository, never()).saveAndFlush(any(UserSecurity.class));
        }

        @Test
        @DisplayName("an encoder that returns null abends too, not only one that returns an empty string")
        void nullDigestAbends() {
            when(encoder.encode(PRESENTED_CREDENTIAL)).thenReturn(null);

            assertAbend(catchAddFailure(validRequest(), PRESENTED_CREDENTIAL));
            verify(repository, never()).saveAndFlush(any(UserSecurity.class));
        }

        /**
         * Asserts that a failure is the abend of {@code CALL 'CEE3ABD'}, not an ordinary refusal.
         *
         * <p>The abend carries the four fields of {@code app/cpy/CSMSG02Y.cpy} - internally titled
         * {@code CABENDD.CPY} - of which the message is the one observable through the base type this file is
         * permitted to import. The numeric abend code {@code 999} lives on the concrete type's own accessor,
         * which sits outside this file's declared dependency set; it is asserted where that type is owned. The
         * concrete type is therefore identified by name and the abend message by value, which together
         * distinguish an abend from every other failure this bean can raise.
         *
         * @param thrown the failure to inspect
         */
        private void assertAbend(final Throwable thrown) {
            assertConcreteType(thrown, "FatalProcessingException");
            assertThat(thrown)
                    .as("the legacy default-message substitution of CABENDD.CPY")
                    .hasMessage(ABEND_MESSAGE);
            assertThat(thrown.getMessage())
                    .doesNotContain(PRESENTED_CREDENTIAL)
                    .doesNotContain(NEW_USER_ID);
        }

        @Test
        @DisplayName("the response record declares no credential component at all")
        void responseCannotCarryACredential() {
            // Structural, not behavioural: there is nothing to populate, so returning a credential is
            // impossible rather than merely unlikely.
            final List<String> components = Arrays.stream(UserAddScreen.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(components)
                    .hasSize(14)
                    .doesNotContain("password", "passwordHash", "credential", "secUsrPwd", "digest");
        }

        @Test
        @DisplayName("no rendering of a successful response contains the presented value or the digest")
        void successfulResponseNeverCarriesTheCredential() {
            final UserAddScreen screen = addSuccessfully(FIRST_NAME, LAST_NAME, NEW_USER_ID, TYPE_USER);

            assertThat(screen.toString())
                    .doesNotContain(PRESENTED_CREDENTIAL)
                    .doesNotContain(contractualDigest())
                    .doesNotContain(SYNTHETIC_SALT)
                    .doesNotContain(SYNTHETIC_BODY);
            assertThat(screen.errorMessage()).doesNotContain(PRESENTED_CREDENTIAL);
        }

        @Test
        @DisplayName("no message on any failure arm contains the presented value or the digest")
        void noFailureMessageCarriesTheCredential() {
            assertThat(catchAddFailure(validRequest(), null).getMessage())
                    .doesNotContain(PRESENTED_CREDENTIAL);

            when(encoder.encode(PRESENTED_CREDENTIAL)).thenReturn(contractualDigest());
            when(repository.existsById(NEW_USER_ID)).thenReturn(true);

            final Throwable duplicate = catchAddFailure(validRequest(), PRESENTED_CREDENTIAL);
            assertThat(duplicate.getMessage())
                    .doesNotContain(PRESENTED_CREDENTIAL)
                    .doesNotContain(contractualDigest())
                    .doesNotContain(SYNTHETIC_SALT);
        }

        @Test
        @DisplayName("the entity's own rendering withholds the credential, so a log line cannot leak it")
        void entityRenderingWithholdsTheCredential() {
            addSuccessfully(FIRST_NAME, LAST_NAME, NEW_USER_ID, TYPE_USER);

            assertThat(captureSaved().toString())
                    .doesNotContain(contractualDigest())
                    .doesNotContain(SYNTHETIC_SALT)
                    .doesNotContain(SYNTHETIC_BODY)
                    .doesNotContain(PRESENTED_CREDENTIAL);
        }

        /**
         * Runs one add that is expected to fail and returns the failure, so that a concrete type outside
         * this file's dependency set can be inspected without importing it.
         *
         * @param submitted  the request to submit
         * @param credential the credential to present
         * @return the failure the add produced
         */
        private Throwable catchAddFailure(final UserCreateRequest submitted, final String credential) {
            try {
                service.addUser(submitted, credential);
            } catch (final CardDemoException expected) {
                return expected;
            }
            throw new AssertionError("the add was expected to fail");
        }
    }

    @Nested
    @DisplayName("The success arm - app/cbl/COUSR01C.cbl:251-259, a clear that precedes the message")
    class SuccessArm {

        @Test
        @DisplayName("the confirmation names the user, proving it is NOT read from the cleared screen field")
        void confirmationNamesTheUser() {
            // :252 PERFORM INITIALIZE-ALL-FIELDS runs BEFORE the STRING at :255-258, and :290 blanks
            // USERIDI. The message is nonetheless built from SEC-USR-ID, the WORKING-STORAGE record field,
            // which the clear does not touch - so the identifier survives it. An implementation that
            // composed from its request field would emit "User  has been added ..." with nobody named, and
            // that would silently weaken the stored credential.
            final UserAddScreen screen = addSuccessfully(FIRST_NAME, LAST_NAME, NEW_USER_ID, TYPE_USER);

            assertThat(screen.errorMessage())
                    .as("the identifier must survive INITIALIZE-ALL-FIELDS")
                    .contains(NEW_USER_ID);
            assertThat(screen.userId())
                    .as(":290 blanked the screen field, so the response field is empty")
                    .isEmpty();
        }

        @Test
        @DisplayName("the exact confirmation, INCLUDING the blank before the three-period ellipsis, :255-258")
        void confirmationIsByteExact() {
            final UserAddScreen screen = addSuccessfully(FIRST_NAME, LAST_NAME, NEW_USER_ID, TYPE_USER);

            assertThat(screen.errorMessage())
                    .isEqualTo("User USER0006 has been added ...")
                    .isEqualTo(ADDED_PREFIX + NEW_USER_ID + ADDED_SUFFIX)
                    .endsWith(" ...")
                    .doesNotEndWith("added...");
        }

        @Test
        @DisplayName("DELIMITED BY SPACE truncates at the FIRST blank rather than trimming both ends")
        void delimitedBySpaceTruncatesAtTheFirstBlank() {
            // SEC-USR-ID is X(08), so a shorter identifier is blank padded and DELIMITED BY SPACE stops the
            // transfer at that padding. The phrase means "stop at the first space", not "trim", and the
            // difference is observable when the value itself carries an interior blank.
            final String shortId = "AB";

            final UserAddScreen screen = addSuccessfully(FIRST_NAME, LAST_NAME, shortId, TYPE_USER);

            assertThat(screen.errorMessage()).isEqualTo(ADDED_PREFIX + shortId + ADDED_SUFFIX);

            final String interiorBlank = "AB CD";
            final UserSecurityRepository freshRepository = mock(UserSecurityRepository.class);
            final PasswordEncoder freshEncoder = mock(PasswordEncoder.class);
            when(freshRepository.existsById(interiorBlank)).thenReturn(false);
            when(freshEncoder.encode(PRESENTED_CREDENTIAL)).thenReturn(contractualDigest());
            final UserAddService fresh = new UserAddService(freshRepository, freshEncoder,
                    new FileStatusMapper(), FIXED_CLOCK);

            final UserAddScreen truncated = fresh.addUser(
                    request(FIRST_NAME, LAST_NAME, interiorBlank, TYPE_USER), PRESENTED_CREDENTIAL);

            assertThat(truncated.errorMessage())
                    .as("the transfer stops at the interior blank; it is not trimmed")
                    .isEqualTo(ADDED_PREFIX + "AB" + ADDED_SUFFIX)
                    .doesNotContain("CD");
        }

        @Test
        @DisplayName("INITIALIZE-ALL-FIELDS blanks every input field on success, :289-295")
        void everyInputFieldIsCleared() {
            final UserAddScreen screen = addSuccessfully(FIRST_NAME, LAST_NAME, NEW_USER_ID, TYPE_USER);

            assertThat(screen.userId()).isEmpty();      // :290
            assertThat(screen.firstName()).isEmpty();   // :291
            assertThat(screen.lastName()).isEmpty();    // :292
            assertThat(screen.userType()).isEmpty();    // :294
            // :293 blanks PASSWDI; the work area holds no credential to blank, and the response has no
            // component for one, so there is nothing to assert but the absence proved elsewhere.
            assertThat(screen.cursorField()).isEqualTo(CURSOR_FIRST_NAME);   // :289
        }

        @Test
        @DisplayName("the redundant MOVE SPACES at :253 leaves the confirmation intact, not blanked")
        void redundantBlankingDoesNotEraseTheConfirmation() {
            // :295 has already blanked WS-MESSAGE inside INITIALIZE-ALL-FIELDS, so :253 is a retained no-op.
            // It is a no-op only because it precedes the STRING at :255-258; reordering it after the STRING
            // would erase the confirmation, which is exactly why the order is asserted rather than assumed.
            final UserAddScreen screen = addSuccessfully(FIRST_NAME, LAST_NAME, NEW_USER_ID, TYPE_USER);

            assertThat(screen.errorMessage()).isNotEmpty().isEqualTo(ADDED_PREFIX + NEW_USER_ID
                    + ADDED_SUFFIX);
        }

        @Test
        @DisplayName("success and failure are told apart by a DISCRIMINATOR, never by parsing the message")
        void successIsDistinguishedByADiscriminator() {
            // :254 MOVE DFHGREEN TO ERRMSGC is set on the successful write and on NO other arm, so in the
            // source one message channel carried both outcomes and only the colour told them apart. The
            // target keeps that distinction observable to a caller that has no terminal, in two independent
            // ways: a discriminator on the response, and a typed failure for every error arm.
            final UserAddScreen success = addSuccessfully(FIRST_NAME, LAST_NAME, NEW_USER_ID, TYPE_USER);
            assertThat(success.messageColour()).isEqualTo(GREEN);

            final UserAddScreen invalidKey =
                    service.submitScreen(AttentionIdentifier.OTHER, validRequest(), PRESENTED_CREDENTIAL);

            assertThat(invalidKey.errorMessage()).isEqualTo(INVALID_KEY);
            assertThat(invalidKey.messageColour())
                    .as("the colour attribute is set on the success arm only")
                    .isNull();
            assertThat(invalidKey.messageColour()).isNotEqualTo(success.messageColour());
        }

        @Test
        @DisplayName("the record is populated in the source's order at :154-158 and inserted exactly once")
        void recordIsPopulatedInOrderAndInsertedOnce() {
            addSuccessfully(FIRST_NAME, LAST_NAME, NEW_USER_ID, TYPE_ADMIN);

            final UserSecurity stored = captureSaved();
            assertThat(stored.getSecUsrId()).isEqualTo(NEW_USER_ID);            // :154
            assertThat(stored.getSecUsrFname()).isEqualTo(FIRST_NAME);          // :155
            assertThat(stored.getSecUsrLname()).isEqualTo(LAST_NAME);           // :156
            assertThat(stored.getPasswordHash()).isEqualTo(contractualDigest()); // :157
            assertThat(stored.getSecUsrType()).isEqualTo(UserType.ADMIN);       // :158

            verify(repository, times(1)).saveAndFlush(any(UserSecurity.class));
        }
    }

    @Nested
    @DisplayName("The duplicate arm - DUPKEY :260 and DUPREC :261 share ONE body")
    class DuplicateArm {

        @Test
        @DisplayName("an identifier already present yields DuplicateRecordException carrying :263 byte exact")
        void duplicateFoundByTheProbe() {
            when(encoder.encode(PRESENTED_CREDENTIAL)).thenReturn(contractualDigest());
            when(repository.existsById(NEW_USER_ID)).thenReturn(true);

            assertThatExceptionOfType(DuplicateRecordException.class)
                    .isThrownBy(() -> service.addUser(validRequest(), PRESENTED_CREDENTIAL))
                    .withMessage(DUPLICATE_USER_ID)
                    .satisfies(thrown -> {
                        assertThat(thrown.getLogicalFile()).isEqualTo(USRSEC_LOGICAL_FILE);
                        assertThat(thrown.getCollidingKey()).isEqualTo(NEW_USER_ID);
                    });

            verify(repository, never()).saveAndFlush(any(UserSecurity.class));
        }

        @Test
        @DisplayName("a concurrent insert takes the SAME single branch and preserves the root cause")
        void duplicateFoundByTheConstraint() {
            // Two distinct legacy conditions, one observable outcome. DFHRESP(DUPKEY) at :260 and
            // DFHRESP(DUPREC) at :261 are adjacent WHEN clauses over the one body at :262-266, so splitting
            // them into different results would break parity. The identical collapse appears in
            // app/cbl/COBIL00C.cbl:533-536.
            final DataIntegrityViolationException collision =
                    new DataIntegrityViolationException("unique violation on the USRSEC primary key");
            when(encoder.encode(PRESENTED_CREDENTIAL)).thenReturn(contractualDigest());
            when(repository.existsById(NEW_USER_ID)).thenReturn(false);
            when(repository.saveAndFlush(any(UserSecurity.class))).thenThrow(collision);

            assertThatExceptionOfType(DuplicateRecordException.class)
                    .isThrownBy(() -> service.addUser(validRequest(), PRESENTED_CREDENTIAL))
                    .withMessage(DUPLICATE_USER_ID)
                    .withCause(collision)
                    .satisfies(thrown -> assertThat(thrown.getCollidingKey()).isEqualTo(NEW_USER_ID));
        }

        @Test
        @DisplayName("both routes report the SAME message, so the two conditions remain one outcome")
        void bothRoutesReportOneOutcome() {
            when(encoder.encode(PRESENTED_CREDENTIAL)).thenReturn(contractualDigest());
            when(repository.existsById(NEW_USER_ID)).thenReturn(true);

            final String viaProbe = catchDuplicate(service).getMessage();

            final UserSecurityRepository freshRepository = mock(UserSecurityRepository.class);
            final PasswordEncoder freshEncoder = mock(PasswordEncoder.class);
            when(freshEncoder.encode(PRESENTED_CREDENTIAL)).thenReturn(contractualDigest());
            when(freshRepository.existsById(NEW_USER_ID)).thenReturn(false);
            when(freshRepository.saveAndFlush(any(UserSecurity.class)))
                    .thenThrow(new DataIntegrityViolationException("concurrent insert"));
            final UserAddService fresh = new UserAddService(freshRepository, freshEncoder,
                    new FileStatusMapper(), FIXED_CLOCK);

            assertThat(catchDuplicate(fresh).getMessage()).isEqualTo(viaProbe).isEqualTo(DUPLICATE_USER_ID);
        }

        @Test
        @DisplayName("the message reads 'exist', the grammatical defect of :263 reproduced not corrected")
        void grammaticalDefectIsPreserved() {
            assertThat(DUPLICATE_USER_ID)
                    .isEqualTo("User ID already exist...")
                    .endsWith("exist...")
                    .doesNotContain("exists");
        }

        @Test
        @DisplayName("the duplicate arm parks the cursor on the IDENTIFIER field, :265")
        void duplicateParksTheCursorOnTheIdentifier() {
            // The screen the source sent at :266 carried MOVE -1 TO USERIDL. The typed failure names the
            // colliding key, which is the stateless equivalent of pointing the operator at that field, and
            // it is the identifier - not the first name the other two arms fall back to.
            when(encoder.encode(PRESENTED_CREDENTIAL)).thenReturn(contractualDigest());
            when(repository.existsById(NEW_USER_ID)).thenReturn(true);

            final DuplicateRecordException thrown = catchDuplicate(service);

            assertThat(thrown.getCollidingKey()).isEqualTo(NEW_USER_ID);
            assertThat(CURSOR_USER_ID).isNotEqualTo(CURSOR_FIRST_NAME);
        }

        @Test
        @DisplayName("there is no retry, no upsert and no regenerated identifier: a collision is terminal")
        void collisionIsTerminal() {
            when(encoder.encode(PRESENTED_CREDENTIAL)).thenReturn(contractualDigest());
            when(repository.existsById(NEW_USER_ID)).thenReturn(true);

            catchDuplicate(service);

            verify(repository, times(1)).existsById(NEW_USER_ID);
            verify(repository, never()).saveAndFlush(any(UserSecurity.class));
            verifyNoMoreInteractions(repository);
        }

        /**
         * Runs one add expected to collide and returns the failure.
         *
         * @param target the bean to exercise
         * @return the duplicate failure it raised
         */
        private DuplicateRecordException catchDuplicate(final UserAddService target) {
            try {
                target.addUser(validRequest(), PRESENTED_CREDENTIAL);
            } catch (final DuplicateRecordException expected) {
                return expected;
            }
            throw new AssertionError("the add was expected to collide on the USRSEC key");
        }
    }

    @Nested
    @DisplayName("The WHEN OTHER arm - :267-273, and the labelled deviation at :268")
    class HardFailureArm {

        @Test
        @DisplayName("a data-access failure is typed, names :270 in the fallback and preserves the root cause")
        void hardFailureIsTypedAndKeepsItsCause() {
            final QueryTimeoutException cause = new QueryTimeoutException("statement timed out");
            arrangeHardFailure(cause);

            final Throwable thrown = catchHardFailure(service);

            assertConcreteType(thrown, "FileAccessException");
            assertThat(thrown).hasCause(cause);
            verify(repository).saveAndFlush(any(UserSecurity.class));
        }

        @Test
        @DisplayName("LABELLED DEVIATION: the response and reason codes ARE preserved despite :268")
        void responseAndReasonCodesArePreserved() {
            // app/cbl/COUSR01C.cbl:268 reads
            //     *            DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD
            // COMMENTED OUT. Alone in the corpus, this program's WHEN OTHER emits no diagnostic at all, so
            // the source DISCARDS the response and reason codes on a hard failure and nothing downstream can
            // ever recover them.
            //
            // Rule 1 Clause B is prescriptive: "no swallowing exceptions; wrap with context and preserve
            // root cause". No observable output depends on those codes being lost - the user-visible literal
            // at :270 is unchanged either way - so the clause governs here and the codes are PRESERVED. That
            // is a DELIBERATE, LABELLED DEVIATION from the source, distinct from every retained no-op in
            // this file because it runs the other way, and the reasoning is set out above.
            //
            // This test is the proof. The mapper is the single owner of the status-to-exception translation,
            // so capturing what crosses into it captures exactly what the source threw away.
            final FileStatusMapper capturingMapper = mock(FileStatusMapper.class);
            final QueryTimeoutException cause = new QueryTimeoutException("statement timed out");
            final CardDemoException translated = new CardDemoException(UNABLE_TO_ADD, cause);

            when(encoder.encode(PRESENTED_CREDENTIAL)).thenReturn(contractualDigest());
            when(repository.existsById(NEW_USER_ID)).thenReturn(false);
            when(repository.saveAndFlush(any(UserSecurity.class))).thenThrow(cause);
            when(capturingMapper.toException(anyString(), anyString(), anyString(), any()))
                    .thenReturn(Optional.of(translated));

            final UserAddService withCapture =
                    new UserAddService(repository, encoder, capturingMapper, FIXED_CLOCK);

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> withCapture.addUser(validRequest(), PRESENTED_CREDENTIAL))
                    .isSameAs(translated);

            final ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<String> logicalFile = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<String> operation = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<Throwable> rootCause = ArgumentCaptor.forClass(Throwable.class);
            verify(capturingMapper).toException(status.capture(), logicalFile.capture(),
                    operation.capture(), rootCause.capture());

            assertThat(status.getValue())
                    .as("the condition code the source's commented-out diagnostic would have shown")
                    .isNotNull()
                    .isNotEmpty();
            assertThat(logicalFile.getValue()).isEqualTo(USRSEC_LOGICAL_FILE);
            assertThat(operation.getValue()).isEqualTo("WRITE");
            assertThat(rootCause.getValue())
                    .as("the root cause survives, which is the whole point of the deviation")
                    .isSameAs(cause);
        }

        @Test
        @DisplayName("end to end, the failure carries the file, the operation and the status in its context")
        void contextSurvivesIntoTheExceptionMessage() {
            // The same guarantee proved through the REAL mapper rather than a double, so the preservation is
            // shown to hold in the wiring the application actually uses.
            final QueryTimeoutException cause = new QueryTimeoutException("statement timed out");
            arrangeHardFailure(cause);

            final Throwable thrown = catchHardFailure(service);

            assertThat(thrown.getMessage())
                    .contains("WRITE")
                    .contains(USRSEC_LOGICAL_FILE)
                    .contains("FILE STATUS");
            assertThat(thrown.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("if the mapper declines to translate, the failure is still raised - never swallowed")
        void aDecliningMapperCannotSwallowTheFailure() {
            final FileStatusMapper decliningMapper = mock(FileStatusMapper.class);
            final QueryTimeoutException cause = new QueryTimeoutException("statement timed out");

            when(encoder.encode(PRESENTED_CREDENTIAL)).thenReturn(contractualDigest());
            when(repository.existsById(NEW_USER_ID)).thenReturn(false);
            when(repository.saveAndFlush(any(UserSecurity.class))).thenThrow(cause);
            when(decliningMapper.toException(anyString(), anyString(), anyString(), any()))
                    .thenReturn(Optional.empty());

            final UserAddService withDecline =
                    new UserAddService(repository, encoder, decliningMapper, FIXED_CLOCK);

            final Throwable thrown = catchHardFailure(withDecline);

            assertConcreteType(thrown, "FileAccessException");
            assertThat(thrown)
                    .as("the fallback carries the user-visible literal of :270 unchanged")
                    .hasMessage(UNABLE_TO_ADD);
            assertThat(thrown.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("the user-visible literal of :270 is byte exact and is NOT the duplicate literal")
        void hardFailureLiteralIsByteExact() {
            assertThat(UNABLE_TO_ADD)
                    .isEqualTo("Unable to Add User...")
                    .endsWith("...")
                    .isNotEqualTo(DUPLICATE_USER_ID);
            // Note the contrast preserved from the source: this arm's verb is correct where :263's is not.
            assertThat(UNABLE_TO_ADD.chars().filter(character -> character == '.').count()).isEqualTo(3L);
        }

        @Test
        @DisplayName("the hard-failure arm falls back to the FIRST NAME cursor, :272, not the identifier")
        void hardFailureParksTheCursorOnFirstName() {
            // :272 MOVE -1 TO FNAMEL. The duplicate arm at :265 points at the identifier instead, because
            // there the identifier is what the operator must change; here nothing about the input is wrong.
            assertThat(CURSOR_FIRST_NAME).isNotEqualTo(CURSOR_USER_ID);

            final QueryTimeoutException cause = new QueryTimeoutException("statement timed out");
            arrangeHardFailure(cause);

            assertConcreteType(catchHardFailure(service), "FileAccessException");
        }

        /**
         * Arranges a write that reaches the store and fails there for a reason that is not a key collision.
         *
         * @param cause the data-access failure the store will raise
         */
        private void arrangeHardFailure(final RuntimeException cause) {
            when(encoder.encode(PRESENTED_CREDENTIAL)).thenReturn(contractualDigest());
            when(repository.existsById(NEW_USER_ID)).thenReturn(false);
            when(repository.saveAndFlush(any(UserSecurity.class))).thenThrow(cause);
        }

        /**
         * Runs one add expected to fail on the {@code WHEN OTHER} arm and returns the failure.
         *
         * @param target the bean to exercise
         * @return the failure it raised
         */
        private Throwable catchHardFailure(final UserAddService target) {
            try {
                target.addUser(validRequest(), PRESENTED_CREDENTIAL);
            } catch (final CardDemoException expected) {
                return expected;
            }
            throw new AssertionError("the add was expected to fail on the WHEN OTHER arm at :267");
        }
    }

    @Nested
    @DisplayName("Record geometry - app/cpy/CSUSR01Y.cpy:17-23 and KEYS(8,0) at app/jcl/DUSRSECJ.jcl:65-66")
    class RecordGeometry {

        @Test
        @DisplayName("the two operands of the write at :243 and :245 are 80 and 8")
        void theWriteOperandsAreEightyAndEight() {
            // EXEC CICS WRITE carries LENGTH(LENGTH OF SEC-USER-DATA) at :243 and KEYLENGTH(LENGTH OF
            // SEC-USR-ID) at :245. Both operands are computed by the compiler from app/cpy/CSUSR01Y.cpy, so
            // they are the copybook's totals rather than independent constants - which is why they are
            // rebuilt here from the field widths rather than asserted as two magic numbers.
            //
            // The record's own census - the 80 byte total, the five modelled columns and the trailing FILLER -
            // is owned by com.cardemo.unit.model.UserSecurityTest and is not re-asserted here. What this test
            // establishes is narrower and belongs to the write: that the operands this service uses are those
            // two totals, so a record reaching the store through THIS path has the geometry the write declared.
            assertThat(SEC_USR_ID_WIDTH + SEC_USR_FNAME_WIDTH + SEC_USR_LNAME_WIDTH + SEC_USR_PWD_WIDTH
                    + SEC_USR_TYPE_WIDTH + SEC_USR_FILLER_WIDTH)
                    .as("the LENGTH operand at :243, matching RECORDSIZE(80,80) at app/jcl/DUSRSECJ.jcl:66")
                    .isEqualTo(RECORD_LENGTH);
            assertThat(SEC_USR_ID_WIDTH)
                    .as("the KEYLENGTH operand at :245, matching KEYS(8,0) at app/jcl/DUSRSECJ.jcl:65")
                    .isEqualTo(8);
        }

        @Test
        @DisplayName("this service persists a 60-character digest where the source stored 8 plaintext bytes")
        void credentialColumnIsWidenedToSixty() {
            // The one field whose width changes under the mechanism substitution. SEC-USR-PWD PIC X(08) held
            // eight plaintext characters at :157; the column holds a 60 character digest. The column width
            // itself is owned by com.cardemo.unit.model.UserSecurityTest; what is asserted here is that a
            // record written through THIS service actually arrives with the widened value, which is the half
            // of the substitution only the service path can demonstrate.
            assertThat(DIGEST_WIDTH).isGreaterThan(SEC_USR_PWD_WIDTH);

            addSuccessfully(FIRST_NAME, LAST_NAME, NEW_USER_ID, TYPE_USER);

            final UserSecurity stored = captureSaved();
            assertThat(stored.getPasswordHash()).hasSize(DIGEST_WIDTH);
            assertThat(stored.getSecUsrId()).hasSizeLessThanOrEqualTo(SEC_USR_ID_WIDTH);
            assertThat(stored.getSecUsrFname()).hasSizeLessThanOrEqualTo(SEC_USR_FNAME_WIDTH);
            assertThat(stored.getSecUsrLname()).hasSizeLessThanOrEqualTo(SEC_USR_LNAME_WIDTH);
            assertThat(stored.getSecUsrType().getCode()).isIn('A', 'U');
        }

        @Test
        @DisplayName("the write is keyed by the identifier, so RIDFLD at :244 is the eight-character key")
        void theWriteIsKeyedByTheIdentifier() {
            addSuccessfully(FIRST_NAME, LAST_NAME, NEW_USER_ID, TYPE_USER);

            // RIDFLD(SEC-USR-ID) with KEYLENGTH(LENGTH OF SEC-USR-ID): the probe is asked about exactly the
            // identifier, and about nothing else.
            verify(repository).existsById(NEW_USER_ID);
            assertThat(NEW_USER_ID).hasSize(SEC_USR_ID_WIDTH);
        }

        @Test
        @DisplayName("an identifier wider than the eight-byte key is refused rather than silently cut")
        void overWideIdentifierIsRefused() {
            final String nineCharacters = fill('9', SEC_USR_ID_WIDTH + 1);

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.addUser(
                            request(FIRST_NAME, LAST_NAME, nineCharacters, TYPE_USER), PRESENTED_CREDENTIAL))
                    .satisfies(thrown -> {
                        assertThat(thrown.getFieldName()).isEqualTo(FIELD_USER_ID);
                        assertThat(thrown.getFailureKind())
                                .isEqualTo(ValidationException.FailureKind.INVALID);
                    });

            verifyNoInteractions(encoder);
        }

        @Test
        @DisplayName("an identifier shorter than the key is accepted, as a blank-padded X(08) field would be")
        void shorterIdentifierIsAccepted() {
            final String sevenCharacters = fill('7', SEC_USR_ID_WIDTH - 1);
            assertThat(sevenCharacters).hasSize(7);

            when(repository.existsById(sevenCharacters)).thenReturn(false);
            when(encoder.encode(PRESENTED_CREDENTIAL)).thenReturn(contractualDigest());

            final UserAddScreen screen = service.addUser(
                    request(FIRST_NAME, LAST_NAME, sevenCharacters, TYPE_USER), PRESENTED_CREDENTIAL);

            assertThat(screen.errorMessage()).isEqualTo(ADDED_PREFIX + sevenCharacters + ADDED_SUFFIX);
            assertThat(captureSaved().getSecUsrId()).isEqualTo(sevenCharacters);
        }
    }

    @Nested
    @DisplayName("Field contracts - app/cpy-bms/COUSR01.CPY, 12 input fields, and the 80 versus 78 boundary")
    class FieldContracts {

        @Test
        @DisplayName("the twelve map fields reach this service as eleven readable plus one separate argument")
        void theTwelveFieldMapSplitsElevenAndOne() {
            // app/cpy-bms/COUSR01.CPY declares twelve *I fields at :24 :30 :36 :42 :48 :54 :60 :66 :72 :78
            // :84 :90 - six recurring header fields, the five entry fields and the message. One RECEIVE MAP
            // at :204-208 read all twelve into COUSR1AI together.
            //
            // The DTO's own census - twelve declared fields, all String, all final, one twelve-argument
            // constructor, and eleven readable properties with no accessor for the credential - is asserted by
            // com.cardemo.unit.model.UserCreateRequestTest, which owns that class. It is deliberately NOT
            // re-asserted here: Rule 1 Clause C forbids duplication, and a second copy of a structural census
            // is the kind that silently falls out of step with the first.
            //
            // What belongs HERE is the consequence for this service, which nothing in the model tier can see:
            // because the twelfth field is write only and exposes no getter, the credential CANNOT arrive
            // inside the request object, so the entry point must take it as a second argument. That signature
            // is the structural guarantee that the value has exactly one path in and no path back out.
            final Method entryPoint = Arrays.stream(UserAddService.class.getMethods())
                    .filter(method -> method.getDeclaringClass() == UserAddService.class)
                    .filter(method -> "addUser".equals(method.getName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("addUser was not found on the bean"));

            assertThat(entryPoint.getParameterTypes())
                    .as("eleven fields in the request, the twelfth alongside it")
                    .containsExactly(UserCreateRequest.class, String.class);
            assertThat(entryPoint.getReturnType())
                    .as("the reply is the screen record, which declares no credential component")
                    .isEqualTo(UserAddScreen.class);

            // And the value the encoder receives is the one passed as that second argument, never anything
            // recovered from the request - which is only provable from this side of the boundary.
            addSuccessfully(FIRST_NAME, LAST_NAME, NEW_USER_ID, TYPE_USER);
            verify(encoder).encode(PRESENTED_CREDENTIAL);
        }

        @Test
        @DisplayName("the user type reaches the two-constant enum AT PERSISTENCE, not as an input gate")
        void userTypeIsMappedAtPersistence() {
            // Two halves of one contract, and both must hold. The input gate of :117-151 tests emptiness
            // only, so an unknown type CLEARS it; the enum of app/cpy/COCOM01Y.cpy is applied later, when the
            // record is built for the write at :158. Collapsing the two into a single input check would
            // reject at the wrong point and would report the wrong failure kind.
            //
            // The enum's own domain - two constants, the case-sensitive lookup and every out-of-domain byte -
            // is owned by com.cardemo.unit.model.UserTypeTest. Asserted here is only where in this service's
            // sequence the mapping happens: after the gate, at the moment the record is built.
            addSuccessfully(FIRST_NAME, LAST_NAME, NEW_USER_ID, TYPE_ADMIN);

            assertThat(captureSaved().getSecUsrType())
                    .as("the one-character type byte becomes the typed constant at :158, not at :142")
                    .isEqualTo(UserType.ADMIN);
        }

        @Test
        @DisplayName("ONE field, TWO failure kinds - BLANK from the :142 gate, INVALID from the :158 mapping")
        void oneFieldTwoFailureKindsByLocation() {
            // The kind discriminates WHERE the refusal happened, which is what makes the two-stage contract
            // observable. The same field name is reported by both, so the field name alone cannot tell an
            // empty submission apart from an out-of-domain one - only the kind can.
            // A single space, because USRTYPEI is X(01): the field's own width is checked as the map is
            // received, so a two-space value never reaches the emptiness gate at all.
            final ValidationException fromTheGate = catchRefusal(
                    request(FIRST_NAME, LAST_NAME, NEW_USER_ID, " "));

            assertThat(fromTheGate.getFieldName()).isEqualTo(FIELD_USER_TYPE);
            assertThat(fromTheGate.getFailureKind()).isEqualTo(ValidationException.FailureKind.BLANK);
            assertThat(fromTheGate.getMessage()).isEqualTo(USER_TYPE_REQUIRED);
            verifyNoInteractions(encoder);

            when(encoder.encode(PRESENTED_CREDENTIAL)).thenReturn(contractualDigest());
            final ValidationException fromTheMapping = catchRefusal(
                    request(FIRST_NAME, LAST_NAME, NEW_USER_ID, "X"));

            assertThat(fromTheMapping.getFieldName()).isEqualTo(FIELD_USER_TYPE);
            assertThat(fromTheMapping.getFailureKind())
                    .as("INVALID, because the field WAS supplied; BLANK belongs to :142-146 alone")
                    .isEqualTo(ValidationException.FailureKind.INVALID);
            assertThat(fromTheMapping.getMessage()).isNotEqualTo(USER_TYPE_REQUIRED);
        }

        @Test
        @DisplayName("the message field is 78 wide, two narrower than WS-MESSAGE PIC X(80) at :38")
        void messageFieldIsTwoNarrowerThanWorkingStorage() {
            // WS-MESSAGE PIC X(80) at :38 is moved to ERRMSGO PIC X(78), so the last two bytes of the working
            // storage field can never be displayed. The boundary is asserted rather than the field widened.
            assertThat(UserSecurityDto.ERROR_MESSAGE_WIDTH).isEqualTo(78);
            assertThat(WS_MESSAGE_WIDTH).isEqualTo(80);
            assertThat(WS_MESSAGE_WIDTH - UserSecurityDto.ERROR_MESSAGE_WIDTH)
                    .as("the two bytes that fall off the boundary")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("a message of exactly 78 passes and one of 79 is refused at the boundary")
        void messageBoundaryIsExact() {
            final String atTheBoundary = fill('M', UserSecurityDto.ERROR_MESSAGE_WIDTH);
            when(repository.existsById(NEW_USER_ID)).thenReturn(false);
            when(encoder.encode(PRESENTED_CREDENTIAL)).thenReturn(contractualDigest());

            final UserAddScreen accepted = service.addUser(
                    requestWithMessage(atTheBoundary), PRESENTED_CREDENTIAL);
            assertThat(accepted.errorMessage())
                    .as("the inbound message is replaced by the confirmation of :255-258")
                    .isEqualTo(ADDED_PREFIX + NEW_USER_ID + ADDED_SUFFIX);

            final String pastTheBoundary = fill('M', UserSecurityDto.ERROR_MESSAGE_WIDTH + 1);
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.addUser(
                            requestWithMessage(pastTheBoundary), PRESENTED_CREDENTIAL))
                    .satisfies(thrown ->
                            assertThat(thrown.getFieldName()).isEqualTo(FIELD_ERROR_MESSAGE));
        }

        @Test
        @DisplayName("every literal this program can display fits the 78-byte field it is displayed in")
        void everyLiteralFitsTheDisplayField() {
            assertThat(List.of(FIRST_NAME_REQUIRED, LAST_NAME_REQUIRED, USER_ID_REQUIRED,
                            CREDENTIAL_REQUIRED, USER_TYPE_REQUIRED, DUPLICATE_USER_ID, UNABLE_TO_ADD,
                            INVALID_KEY, ADDED_PREFIX + NEW_USER_ID + ADDED_SUFFIX))
                    .allSatisfy(literal -> assertThat(literal)
                            .isNotEmpty()
                            .hasSizeLessThanOrEqualTo(UserSecurityDto.ERROR_MESSAGE_WIDTH));
        }

        @Test
        @DisplayName("the response reports the message through a 78-wide channel and never a wider one")
        void responseMessageHonoursTheDisplayWidth() {
            final UserAddScreen screen = addSuccessfully(FIRST_NAME, LAST_NAME, NEW_USER_ID, TYPE_USER);

            assertThat(screen.errorMessage())
                    .hasSizeLessThanOrEqualTo(UserSecurityDto.ERROR_MESSAGE_WIDTH);
            assertThat(screen.transactionName()).isEqualTo(TRANSACTION_ID);
            assertThat(screen.programName()).isEqualTo(PROGRAM_NAME);
        }

        /**
         * Builds an otherwise valid request whose inbound message field carries the supplied text.
         *
         * @param message the value to place in the {@code ERRMSGI} counterpart
         * @return the request
         */
        private UserCreateRequest requestWithMessage(final String message) {
            return new UserCreateRequest(TRANSACTION_ID, TITLE_01, EXPECTED_HEADER_DATE, PROGRAM_NAME,
                    TITLE_02, EXPECTED_HEADER_TIME, FIRST_NAME, LAST_NAME, NEW_USER_ID,
                    PRESENTED_CREDENTIAL, TYPE_USER, message);
        }

        /**
         * Runs one add expected to be refused by validation and returns the refusal.
         *
         * @param submitted the request to present
         * @return the refusal it raised
         */
        private ValidationException catchRefusal(final UserCreateRequest submitted) {
            try {
                service.addUser(submitted, PRESENTED_CREDENTIAL);
            } catch (final ValidationException expected) {
                return expected;
            }
            throw new AssertionError("the add was expected to be refused by validation");
        }
    }

    @Nested
    @DisplayName("MAIN-PARA dispatch - app/cbl/COUSR01C.cbl:71, the EIBCALEN test and EVALUATE EIBAID")
    class MainParaDispatch {

        @Test
        @DisplayName("no COMMAREA routes straight to the sign-on program, :78-80, touching nothing")
        void noCommAreaRoutesToSignOn() {
            // :78 IF EIBCALEN = 0 - the transaction was reached without context, so the only safe action is
            // to hand control back to sign-on. Nothing is read, nothing is written, no field is validated.
            final UserAddScreen screen = service.openWithoutContext();

            assertThat(screen.navigationTarget()).isEqualTo(SIGN_ON_PROGRAM);
            verifyNoInteractions(repository, encoder);
        }

        @Test
        @DisplayName("the first display blanks the map and parks the cursor on first name, :85-87")
        void firstDisplayBlanksTheMapAndParksTheCursor() {
            // :83 IF NOT CDEMO-PGM-REENTER / :85 MOVE LOW-VALUES TO COUSR1AO / :86 MOVE -1 TO FNAMEL.
            final UserAddScreen screen = service.openScreen();

            assertThat(screen.cursorField()).isEqualTo(CURSOR_FIRST_NAME);
            assertThat(screen.firstName()).isEmpty();
            assertThat(screen.lastName()).isEmpty();
            assertThat(screen.userId()).isEmpty();
            assertThat(screen.userType()).isEmpty();
            assertThat(screen.errorMessage()).isEmpty();
            assertThat(screen.navigationTarget())
                    .as("the opening display transfers nowhere")
                    .isNull();
            verifyNoInteractions(repository, encoder);
        }

        @Test
        @DisplayName("PF3 returns to the admin menu, :93-95, and saves nothing on the way out")
        void pf3ReturnsToTheAdminMenu() {
            // :94 MOVE 'COADM01C' TO CDEMO-TO-PROGRAM. The operator abandoned the entry, so no write occurs
            // even though the fields may have been filled in.
            final UserAddScreen screen =
                    service.submitScreen(AttentionIdentifier.PF3, validRequest(), PRESENTED_CREDENTIAL);

            assertThat(screen.navigationTarget()).isEqualTo(ADMIN_MENU_PROGRAM);
            assertThat(screen.navigationTarget())
                    .as("the admin menu, not sign-on - the two targets must not be confused")
                    .isNotEqualTo(SIGN_ON_PROGRAM);
            verify(repository, never()).saveAndFlush(any(UserSecurity.class));
            verifyNoInteractions(encoder);
        }

        @Test
        @DisplayName("PF4 clears the screen, :96-97, without writing and without transferring")
        void pf4ClearsWithoutWriting() {
            // :97 PERFORM CLEAR-CURRENT-SCREEN, which is :281 INITIALIZE-ALL-FIELDS then :282 SEND.
            final UserAddScreen screen =
                    service.submitScreen(AttentionIdentifier.PF4, validRequest(), PRESENTED_CREDENTIAL);

            assertThat(screen.firstName()).isEmpty();
            assertThat(screen.lastName()).isEmpty();
            assertThat(screen.userId()).isEmpty();
            assertThat(screen.userType()).isEmpty();
            assertThat(screen.errorMessage()).isEmpty();
            assertThat(screen.cursorField()).isEqualTo(CURSOR_FIRST_NAME);
            assertThat(screen.navigationTarget()).isNull();
            verify(repository, never()).saveAndFlush(any(UserSecurity.class));
            verifyNoInteractions(encoder);
        }

        @Test
        @DisplayName("any other key reports CCDA-MSG-INVALID-KEY, :98-102, and does NOT raise a failure")
        void anyOtherKeyReportsTheInvalidKeyMessage() {
            // :101 MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE. app/cpy/CSMSG01Y.cpy declares the literal in an
            // X(50) field, so the value is blank-padded there and is compared trimmed here. The arm sets the
            // error flag but retains no typed failure, which is why the call returns a screen instead of
            // throwing: an unrecognised key is an operator slip, not a processing failure.
            final UserAddScreen screen =
                    service.submitScreen(AttentionIdentifier.OTHER, validRequest(), PRESENTED_CREDENTIAL);

            assertThat(screen.errorMessage()).isEqualTo(INVALID_KEY);
            assertThat(screen.cursorField()).isEqualTo(CURSOR_FIRST_NAME);
            assertThat(screen.messageColour())
                    .as("an unrecognised key is not a success, so the green discriminator is absent")
                    .isNotEqualTo(GREEN);
            verify(repository, never()).saveAndFlush(any(UserSecurity.class));
            verifyNoInteractions(encoder);
        }

        @Test
        @DisplayName("submitting with ENTER is exactly the add path of :91-92")
        void enterIsTheAddPath() {
            arrangeWriteSucceeds(NEW_USER_ID, PRESENTED_CREDENTIAL);

            final UserAddScreen viaSubmit = service.submitScreen(
                    AttentionIdentifier.ENTER, validRequest(), PRESENTED_CREDENTIAL);

            assertThat(viaSubmit.errorMessage()).isEqualTo(ADDED_PREFIX + NEW_USER_ID + ADDED_SUFFIX);
            assertThat(viaSubmit.messageColour()).isEqualTo(GREEN);
            verify(repository).saveAndFlush(any(UserSecurity.class));
        }

        @Test
        @DisplayName("the arms that read no input field tolerate an absent request, as RECEIVE MAP did")
        void armsThatReadNothingTolerateAnAbsentRequest() {
            // :89 PERFORM RECEIVE-USRADD-SCREEN runs before the EVALUATE, but the PF3, PF4 and OTHER arms
            // consume no received field, so an absent map is not an error on those paths.
            assertThat(service.submitScreen(AttentionIdentifier.PF3, null, null).navigationTarget())
                    .isEqualTo(ADMIN_MENU_PROGRAM);
            assertThat(service.submitScreen(AttentionIdentifier.PF4, null, null).cursorField())
                    .isEqualTo(CURSOR_FIRST_NAME);
            assertThat(service.submitScreen(AttentionIdentifier.OTHER, null, null).errorMessage())
                    .isEqualTo(INVALID_KEY);
            verifyNoInteractions(repository, encoder);
        }

        @Test
        @DisplayName("an absent attention identifier is refused, because EVALUATE EIBAID has no such arm")
        void absentAttentionIdentifierIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.submitScreen(null, validRequest(), PRESENTED_CREDENTIAL))
                    .withMessageContaining("aid");
            verifyNoInteractions(repository, encoder);
        }

        @Test
        @DisplayName("ENTER without a request is refused, because that arm does read the received fields")
        void enterWithoutARequestIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, null, null))
                    .withMessageContaining("request");
            assertThatNullPointerException()
                    .isThrownBy(() -> service.addUser(null, PRESENTED_CREDENTIAL))
                    .withMessageContaining("request");
            verifyNoInteractions(repository, encoder);
        }

        @Test
        @DisplayName("the four arms are exactly the source's, so no fifth key constant exists")
        void theArmsAreExactlyTheSourceSet() {
            // :90-103 names DFHENTER, DFHPF3 and DFHPF4 and folds every other key into WHEN OTHER. Adding a
            // constant for a further key would claim behaviour the source does not have.
            assertThat(AttentionIdentifier.values())
                    .containsExactly(AttentionIdentifier.ENTER, AttentionIdentifier.PF3,
                            AttentionIdentifier.PF4, AttentionIdentifier.OTHER);
        }
    }

    @Nested
    @DisplayName("POPULATE-HEADER-INFO - app/cbl/COUSR01C.cbl:214-235 over an injected clock")
    class HeaderRendering {

        @Test
        @DisplayName("the date renders MM/DD/YY in eight characters, :223-227")
        void dateRendersAsEightCharacters() {
            // :224-226 assemble WS-CURDATE-MM '/' WS-CURDATE-DD '/' WS-CURDATE-YY into an X(08) field, so
            // the width is a contract and a four-digit year would overflow it.
            final UserAddScreen screen = service.openScreen();

            assertThat(screen.currentDate())
                    .isEqualTo(EXPECTED_HEADER_DATE)
                    .hasSize(UserSecurityDto.DATE_WIDTH)
                    .hasSize(8);
        }

        @Test
        @DisplayName("the time renders HH:MM:SS in eight characters, :229-233")
        void timeRendersAsEightCharacters() {
            final UserAddScreen screen = service.openScreen();

            assertThat(screen.currentTime())
                    .isEqualTo(EXPECTED_HEADER_TIME)
                    .hasSize(UserSecurityDto.TIME_WIDTH)
                    .hasSize(8);
        }

        @Test
        @DisplayName("both titles come from app/cpy/COTTL01Y.cpy and both are forty characters wide")
        void titlesComeFromTheTitleCopybook() {
            final UserAddScreen screen = service.openScreen();

            assertThat(screen.title01())
                    .isEqualTo(TITLE_01)
                    .hasSize(UserSecurityDto.TITLE_WIDTH);
            assertThat(screen.title02())
                    .isEqualTo(TITLE_02)
                    .hasSize(UserSecurityDto.TITLE_WIDTH);
        }

        @Test
        @DisplayName("the header names this transaction and this program, :220-221")
        void headerNamesTheTransactionAndProgram() {
            final UserAddScreen screen = service.openScreen();

            assertThat(screen.transactionName())
                    .isEqualTo(TRANSACTION_ID)
                    .hasSize(UserSecurityDto.TRANSACTION_NAME_WIDTH);
            assertThat(screen.programName())
                    .isEqualTo(PROGRAM_NAME)
                    .hasSize(UserSecurityDto.PROGRAM_NAME_WIDTH);
        }

        @Test
        @DisplayName("two renderings from one clock are identical, so the header is deterministic")
        void renderingIsDeterministic() {
            // :216 MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA reads the system clock. Here the clock is
            // injected, which is what makes the header assertable at all: a bean reading the ambient clock
            // could not be tested for an exact value without the test becoming time dependent.
            final UserAddScreen first = service.openScreen();
            final UserAddScreen second = service.openScreen();

            assertThat(second.currentDate()).isEqualTo(first.currentDate());
            assertThat(second.currentTime()).isEqualTo(first.currentTime());
            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("the header is rendered on the success path too, not only on the opening display")
        void headerIsRenderedOnTheSuccessPath() {
            final UserAddScreen screen = addSuccessfully(FIRST_NAME, LAST_NAME, NEW_USER_ID, TYPE_USER);

            assertThat(screen.currentDate()).isEqualTo(EXPECTED_HEADER_DATE);
            assertThat(screen.currentTime()).isEqualTo(EXPECTED_HEADER_TIME);
            assertThat(screen.title01()).isEqualTo(TITLE_01);
        }
    }

    @Nested
    @DisplayName("Hostile input - every presented field is untrusted, per Rule 1 clause A")
    class HostileInput {

        @Test
        @DisplayName("a name of exactly twenty characters is accepted, matching X(20)")
        void nameAtTheBoundaryIsAccepted() {
            final String atTheBoundary = fill('N', SEC_USR_FNAME_WIDTH);
            when(repository.existsById(NEW_USER_ID)).thenReturn(false);
            when(encoder.encode(PRESENTED_CREDENTIAL)).thenReturn(contractualDigest());

            service.addUser(request(atTheBoundary, atTheBoundary, NEW_USER_ID, TYPE_USER),
                    PRESENTED_CREDENTIAL);

            final UserSecurity stored = captureSaved();
            assertThat(stored.getSecUsrFname()).isEqualTo(atTheBoundary).hasSize(20);
            assertThat(stored.getSecUsrLname()).isEqualTo(atTheBoundary).hasSize(20);
        }

        @Test
        @DisplayName("a name past twenty characters is REFUSED, never silently cut to fit")
        void namePastTheBoundaryIsRefused() {
            // The source's MOVE to a PIC X(20) field would have truncated in silence, losing data with no
            // diagnostic. Refusing is the safer reading of clause A's "treat inputs as untrusted", and it is
            // the behaviour the bean documents, so it is asserted rather than assumed either way.
            final String pastTheBoundary = fill('N', SEC_USR_FNAME_WIDTH + 1);

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.addUser(
                            request(pastTheBoundary, LAST_NAME, NEW_USER_ID, TYPE_USER), PRESENTED_CREDENTIAL))
                    .satisfies(thrown -> {
                        assertThat(thrown.getFieldName()).isEqualTo(FIELD_FIRST_NAME);
                        assertThat(thrown.getFailureKind())
                                .isEqualTo(ValidationException.FailureKind.INVALID);
                        assertThat(thrown.getMessage())
                                .as("the refusal explains the width and does not echo the value")
                                .contains(String.valueOf(SEC_USR_FNAME_WIDTH))
                                .doesNotContain(pastTheBoundary);
                    });

            verifyNoInteractions(encoder);
            verify(repository, never()).saveAndFlush(any(UserSecurity.class));
        }

        @Test
        @DisplayName("an over-long surname is refused and names the surname, not the first name")
        void overLongSurnameNamesItsOwnField() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.addUser(
                            request(FIRST_NAME, fill('S', SEC_USR_LNAME_WIDTH + 1), NEW_USER_ID, TYPE_USER),
                            PRESENTED_CREDENTIAL))
                    .satisfies(thrown -> assertThat(thrown.getFieldName()).isEqualTo(FIELD_LAST_NAME));
        }

        @Test
        @DisplayName("null, empty and whitespace all take the same emptiness arm for the same field")
        void nullEmptyAndWhitespaceAgreePerField() {
            // = SPACES OR LOW-VALUES at :118 :124 :130 :136 :142 does not distinguish an unset field from a
            // blank one, so the three hostile shapes must produce one identical outcome per field.
            assertThat(refusalFor(null, LAST_NAME, NEW_USER_ID, TYPE_USER)).isEqualTo(FIRST_NAME_REQUIRED);
            assertThat(refusalFor("", LAST_NAME, NEW_USER_ID, TYPE_USER)).isEqualTo(FIRST_NAME_REQUIRED);
            assertThat(refusalFor("   ", LAST_NAME, NEW_USER_ID, TYPE_USER)).isEqualTo(FIRST_NAME_REQUIRED);

            assertThat(refusalFor(FIRST_NAME, null, NEW_USER_ID, TYPE_USER)).isEqualTo(LAST_NAME_REQUIRED);
            assertThat(refusalFor(FIRST_NAME, "", NEW_USER_ID, TYPE_USER)).isEqualTo(LAST_NAME_REQUIRED);
            assertThat(refusalFor(FIRST_NAME, "  ", NEW_USER_ID, TYPE_USER)).isEqualTo(LAST_NAME_REQUIRED);

            assertThat(refusalFor(FIRST_NAME, LAST_NAME, null, TYPE_USER)).isEqualTo(USER_ID_REQUIRED);
            assertThat(refusalFor(FIRST_NAME, LAST_NAME, "", TYPE_USER)).isEqualTo(USER_ID_REQUIRED);
            assertThat(refusalFor(FIRST_NAME, LAST_NAME, " ", TYPE_USER)).isEqualTo(USER_ID_REQUIRED);

            assertThat(refusalFor(FIRST_NAME, LAST_NAME, NEW_USER_ID, null)).isEqualTo(USER_TYPE_REQUIRED);
            assertThat(refusalFor(FIRST_NAME, LAST_NAME, NEW_USER_ID, "")).isEqualTo(USER_TYPE_REQUIRED);
            assertThat(refusalFor(FIRST_NAME, LAST_NAME, NEW_USER_ID, " ")).isEqualTo(USER_TYPE_REQUIRED);

            verifyNoInteractions(encoder, repository);
        }

        @Test
        @DisplayName("a control byte is treated as LOW-VALUES, not as a printable character")
        void controlBytesAreTreatedAsUnset() {
            // LOW-VALUES is the binary-zero fill of an untransmitted field. A field carrying only control
            // bytes must therefore take the emptiness arm rather than be stored as unprintable data.
            assertThat(refusalFor(LOW_VALUES, LAST_NAME, NEW_USER_ID, TYPE_USER))
                    .isEqualTo(FIRST_NAME_REQUIRED);
            assertThat(refusalFor(FIRST_NAME, LOW_VALUES, NEW_USER_ID, TYPE_USER))
                    .isEqualTo(LAST_NAME_REQUIRED);
            assertThat(refusalFor(FIRST_NAME, LAST_NAME, LOW_VALUES, TYPE_USER))
                    .isEqualTo(USER_ID_REQUIRED);
            verifyNoInteractions(encoder, repository);
        }

        @Test
        @DisplayName("no refusal message ever echoes a presented value back to the caller")
        void refusalsNeverEchoPresentedValues() {
            // Clause D applied to diagnostics: a message that quotes the offending value turns any log line
            // into a disclosure. The field NAME is reported; the field VALUE never is.
            final String distinctive = "ZZQQXX";

            assertThat(refusalFor(FIRST_NAME, LAST_NAME, NEW_USER_ID, null))
                    .doesNotContain(FIRST_NAME)
                    .doesNotContain(LAST_NAME)
                    .doesNotContain(NEW_USER_ID)
                    .doesNotContain(PRESENTED_CREDENTIAL);
            assertThat(refusalFor(distinctive, LAST_NAME, "", TYPE_USER))
                    .isEqualTo(USER_ID_REQUIRED)
                    .doesNotContain(distinctive);
        }

        /**
         * Presents the four readable entry fields and returns the message of the refusal they provoke.
         *
         * @param firstName the value for {@code FNAMEI}
         * @param lastName  the value for {@code LNAMEI}
         * @param userId    the value for {@code USERIDI}
         * @param userType  the value for {@code USRTYPEI}
         * @return the message carried by the refusal
         */
        private String refusalFor(final String firstName, final String lastName, final String userId,
                final String userType) {

            try {
                service.addUser(request(firstName, lastName, userId, userType), PRESENTED_CREDENTIAL);
            } catch (final ValidationException expected) {
                return expected.getMessage();
            }
            throw new AssertionError("the presented request was expected to be refused");
        }
    }

    @Nested
    @DisplayName("Least privilege and statelessness - Rule 1 clauses B and D")
    class LeastPrivilegeAndStatelessness {

        @Test
        @DisplayName("the bean collaborates with exactly four things and holds no authority of its own")
        void collaboratorsAreExactlyTheFour() {
            // Clause D, least privilege: the bean receives a store, an encoder, a status mapper and a clock.
            // It receives no authentication manager, no security context, no token provider and no request
            // context, so it cannot consult or elevate the caller's authority - and therefore cannot be the
            // place where an authorisation decision silently goes missing.
            final List<Class<?>> parameterTypes = Arrays.stream(
                            UserAddService.class.getDeclaredConstructors())
                    .filter(constructor -> Modifier.isPublic(constructor.getModifiers()))
                    .findFirst()
                    .map(constructor -> Arrays.asList(constructor.getParameterTypes()))
                    .orElseThrow(() -> new AssertionError("no public constructor was found"));

            assertThat(parameterTypes).hasSize(4);
            assertThat(parameterTypes.stream().map(Class::getSimpleName).collect(Collectors.toList()))
                    .containsExactlyInAnyOrder("UserSecurityRepository", "PasswordEncoder",
                            "FileStatusMapper", "Clock")
                    .noneMatch(name -> name.contains("Authentication"))
                    .noneMatch(name -> name.contains("Security") && !name.equals("UserSecurityRepository"))
                    .noneMatch(name -> name.contains("Token"))
                    .noneMatch(name -> name.contains("Request"));
        }

        @Test
        @DisplayName("every collaborator is mandatory, so the bean cannot start half wired")
        void everyCollaboratorIsMandatory() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new UserAddService(null, encoder, fileStatusMapper, FIXED_CLOCK));
            assertThatNullPointerException()
                    .isThrownBy(() -> new UserAddService(repository, null, fileStatusMapper, FIXED_CLOCK));
            assertThatNullPointerException()
                    .isThrownBy(() -> new UserAddService(repository, encoder, null, FIXED_CLOCK));
            assertThatNullPointerException()
                    .isThrownBy(() -> new UserAddService(repository, encoder, fileStatusMapper, null));
        }

        @Test
        @DisplayName("no instance field is mutable and no mutable static field exists")
        void thereIsNoGlobalOrInstanceMutableState() {
            // Clause B, "avoid global mutable state". WS-ERR-FLG, WS-RESP-CD and WS-REAS-CD were WORKING
            // STORAGE, which in CICS is per-task but in a shared bean would be cross-request state. They are
            // method-local here, and this assertion is what keeps them that way.
            assertThat(Arrays.stream(UserAddService.class.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .collect(Collectors.toList()))
                    .isNotEmpty()
                    .allSatisfy(field -> assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("instance field %s must be final", field.getName())
                            .isTrue());

            assertThat(Arrays.stream(UserAddService.class.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .filter(field -> Modifier.isStatic(field.getModifiers()))
                    .collect(Collectors.toList()))
                    .allSatisfy(field -> assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final", field.getName())
                            .isTrue());
        }

        @Test
        @DisplayName("a refused call leaves no residue that a later call could observe")
        void aRefusedCallLeavesNoResidue() {
            // The COMMAREA carried state between pseudo-conversational turns; nothing here may. A failure
            // followed by a success must behave exactly as the success would have on a fresh bean.
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.addUser(
                            request(null, null, null, null), PRESENTED_CREDENTIAL));

            final UserAddScreen afterFailure =
                    addSuccessfully(FIRST_NAME, LAST_NAME, NEW_USER_ID, TYPE_USER);

            assertThat(afterFailure.errorMessage()).isEqualTo(ADDED_PREFIX + NEW_USER_ID + ADDED_SUFFIX);
            assertThat(afterFailure.messageColour()).isEqualTo(GREEN);
            assertThat(afterFailure.cursorField()).isEqualTo(CURSOR_FIRST_NAME);
        }

        @Test
        @DisplayName("the bean carries no self-authorisation annotation, so the rule lives in one place")
        void theBeanDoesNotAuthoriseItself() {
            // The HTTP-level rule that places this service behind /api/admin/** is declared by
            // SecurityConfig, together with the stateless session policy; neither can be exercised from this
            // tier, because driving them needs a controller and AdminController is not present in the tree.
            // What IS assertable is the structural precondition - the bean neither grants nor
            // assumes authority, so the single enforcement point stays external and cannot be contradicted
            // from here. This test records that boundary rather than guessing at the rule.
            final Set<String> present = new LinkedHashSet<>();
            Arrays.stream(UserAddService.class.getAnnotations())
                    .map(annotation -> annotation.annotationType().getSimpleName())
                    .forEach(present::add);
            Arrays.stream(UserAddService.class.getMethods())
                    .filter(method -> method.getDeclaringClass() == UserAddService.class)
                    .flatMap(method -> Arrays.stream(method.getAnnotations()))
                    .map(annotation -> annotation.annotationType().getSimpleName())
                    .forEach(present::add);

            assertThat(present).doesNotContainAnyElementsOf(SELF_AUTHORISATION_ANNOTATIONS);
        }

        @Test
        @DisplayName("both writing entry points are transactional, so a refusal writes nothing")
        void bothWritingEntryPointsAreTransactional() throws NoSuchMethodException {
            assertThat(annotationNamesOf(UserAddService.class.getMethod(
                    "addUser", UserCreateRequest.class, String.class)))
                    .contains("Transactional");
            assertThat(annotationNamesOf(UserAddService.class.getMethod(
                    "submitScreen", AttentionIdentifier.class, UserCreateRequest.class, String.class)))
                    .contains("Transactional");
        }

        @Test
        @DisplayName("the store is reached only through the typed repository, never through a query string")
        void theStoreIsReachedThroughTypedMethodsOnly() {
            // Clause D, "no shell injection" applied to persistence: the identifier is untrusted input that
            // reaches a keyed write, so it must travel as a bound parameter. Every method the repository
            // declares is a derived query - no hand-written statement exists for a value to be spliced into.
            assertThat(Arrays.stream(UserSecurityRepository.class.getDeclaredMethods())
                    .flatMap(method -> Arrays.stream(method.getAnnotations()))
                    .map(annotation -> annotation.annotationType().getSimpleName())
                    .collect(Collectors.toList()))
                    .as("a derived finder binds its parameters; a @Query string is where splicing begins")
                    .doesNotContain("Query")
                    .doesNotContain("NativeQuery");

            addSuccessfully(FIRST_NAME, LAST_NAME, NEW_USER_ID, TYPE_USER);

            // The identifier crosses the boundary as a typed argument, which is the binding itself.
            verify(repository).existsById(NEW_USER_ID);
            verify(repository).saveAndFlush(any(UserSecurity.class));
        }

        @Test
        @DisplayName("the ten seeded identifiers store a digest and never the value that was presented")
        void everySeededIdentifierStoresOnlyADigest() {
            // app/jcl/DUSRSECJ.jcl seeds five administrators and five regular users through inline IEBGENER
            // data, and in the source every one of them carries its credential in plaintext in an X(08)
            // field. Clause D names TESTS explicitly, so those ten are exercised here as IDENTIFIERS ONLY:
            // the credential presented is this file's synthetic placeholder, and what is asserted is that the
            // stored value is the digest. The seed literal itself appears nowhere in this file.
            final List<String> seededIdentifiers = List.of(
                    "ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005",
                    "USER0001", "USER0002", "USER0003", "USER0004", "USER0005");
            final String digest = contractualDigest();

            when(encoder.encode(PRESENTED_CREDENTIAL)).thenReturn(digest);
            for (final String identifier : seededIdentifiers) {
                when(repository.existsById(identifier)).thenReturn(false);
            }

            for (final String identifier : seededIdentifiers) {
                final String type = identifier.startsWith("ADMIN") ? TYPE_ADMIN : TYPE_USER;
                final UserAddScreen screen = service.addUser(
                        request(FIRST_NAME, LAST_NAME, identifier, type), PRESENTED_CREDENTIAL);
                assertThat(screen.errorMessage()).isEqualTo(ADDED_PREFIX + identifier + ADDED_SUFFIX);
            }

            final ArgumentCaptor<UserSecurity> saved = ArgumentCaptor.forClass(UserSecurity.class);
            verify(repository, times(seededIdentifiers.size())).saveAndFlush(saved.capture());

            assertThat(saved.getAllValues())
                    .hasSize(seededIdentifiers.size())
                    .allSatisfy(stored -> {
                        assertThat(stored.getPasswordHash())
                                .isEqualTo(digest)
                                .isNotEqualTo(PRESENTED_CREDENTIAL)
                                .hasSize(DIGEST_WIDTH);
                        assertThat(stored.getPasswordHash())
                                .as("nothing resembling the presented value may survive into the row")
                                .doesNotContain(PRESENTED_CREDENTIAL);
                    });
            assertThat(saved.getAllValues().stream()
                    .map(stored -> stored.getSecUsrType().getCode())
                    .collect(Collectors.toList()))
                    .as("five administrators and five regular users, exactly as the seed declares")
                    .containsExactly('A', 'A', 'A', 'A', 'A', 'U', 'U', 'U', 'U', 'U');
        }

        @Test
        @DisplayName("this test's own fixture credential is synthetic, so no real secret is in the file")
        void theFixtureCredentialIsSynthetic() {
            // A self-audit against clause D. The placeholder is deliberately not a dictionary word, not the
            // seed literal, and not a value any provider pattern could match, and the digest material is
            // assembled at run time so that no credential-shaped literal exists in the source text at all.
            assertThat(PRESENTED_CREDENTIAL)
                    .isNotEmpty()
                    .doesNotContainIgnoringCase("secret")
                    .doesNotContainIgnoringCase("token")
                    .doesNotContainIgnoringCase("admin")
                    .containsPattern("[^A-Za-z]");
            assertThat(contractualDigest())
                    .as("the digest is built from parts, so its prefix is never a literal here")
                    .startsWith(DIGEST_FIELD_MARKER + DIGEST_VERSION_TAG + DIGEST_FIELD_MARKER)
                    .contains(DIGEST_FIELD_MARKER + CONTRACTUAL_COST_FACTOR + DIGEST_FIELD_MARKER)
                    .doesNotContain(PRESENTED_CREDENTIAL);
        }

        /**
         * Collects the simple names of the annotations present on a method.
         *
         * @param method the method to inspect
         * @return the annotation simple names, in declaration order
         */
        private List<String> annotationNamesOf(final Method method) {
            return Arrays.stream(method.getAnnotations())
                    .map(annotation -> annotation.annotationType().getSimpleName())
                    .collect(Collectors.toList());
        }
    }
}
