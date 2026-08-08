/*
 * ******************************************************************
 * Program     : AuthenticationServiceTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/service
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that AuthenticationService reproduces COSGN00C
 *               exactly - six paragraphs mapped one to one, the two
 *               blank guards in the source's order with every literal
 *               byte exact, BOTH the presented identifier and the
 *               presented password folded to upper case, the plaintext
 *               comparison of :L223 served by a BCrypt verification at
 *               strength 10, the three EVALUATE WS-RESP-CD arms
 *               translated into five distinguishable typed outcomes,
 *               the subject and role claims minted by a real provider
 *               and read back through a real decoder, and exactly one
 *               authentication-attempt increment per call on a real
 *               meter registry
 * Source      : app/cbl/COSGN00C.cbl (260 lines, 6 own paragraph labels) @ 7756d89
 * Source      : app/cpy-bms/COSGN00.CPY (11 input fields) @ 7756d89
 * Source      : app/cpy/CSUSR01Y.cpy (80 byte record, KEYS(8,0)) @ 7756d89
 * Source      : app/cpy/COCOM01Y.cpy (CDEMO-USER-ID, CDEMO-USER-TYPE) @ 7756d89
 * Source      : app/cpy/COTTL01Y.cpy (CCDA-TITLE01, CCDA-TITLE02) @ 7756d89
 * Source      : app/csd/CARDDEMO.CSD (TRANSACTION(CC00), FILE(USRSEC)) @ 7756d89
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
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
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.transaction.annotation.Transactional;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.SignOnRequest;
import com.cardemo.model.dto.SignOnResponse;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.observability.MetricsConfig;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.security.CardDemoUserDetailsService;
import com.cardemo.security.JwtTokenProvider;
import com.cardemo.service.auth.AuthenticationService;

/**
 * Unit tests for {@code com.cardemo.service.auth.AuthenticationService}, the Java replacement for
 * {@code app/cbl/COSGN00C.cbl} - 260 lines and 6 paragraphs, the CICS program behind transaction
 * {@code CC00}, and the only unauthenticated entry point in the entire application surface.
 *
 * <h2>What it does</h2>
 *
 * <p>It proves parity against the frozen source rather than against an idea of what the source ought to do.
 * Every assertion cites the paragraph or line it proves, and every citation was verified by direct
 * inspection at commit {@code 7756d89}. Five groups of behaviour carry the weight, and they are the five
 * the code review named as unproved.
 *
 * <ul>
 *   <li><strong>Blank-field precedence.</strong> {@code EVALUATE TRUE} at {@code COSGN00C.cbl}:117-130
 *       tests the identifier at {@code :118} <em>before</em> the password at {@code :123}, so a request that
 *       omits both is reported as a missing identifier and never as a missing password. Both literals are
 *       asserted byte for byte from {@code :120} and {@code :125}. {@code SPACES OR LOW-VALUES} covers three
 *       distinct Java states - absent, all-whitespace and all-{@code NUL} - and a fourth state that mixes
 *       spaces with {@code NUL} bytes is <em>not</em> covered, because such a field is literally neither
 *       {@code SPACES} nor {@code LOW-VALUES} and the source's {@code WHEN OTHER} arm at {@code :128} lets
 *       it through. All four are asserted.</li>
 *   <li><strong>Upper-casing of BOTH operands.</strong> {@code :132-134} folds the identifier and
 *       {@code :135-136} folds the password, two distinct {@code MOVE FUNCTION UPPER-CASE} statements. This
 *       suite proves both <em>through a real delegate and a real BCrypt encoder at strength 10</em>: a
 *       digest computed from the upper-case credential verifies a credential presented in lower case, and a
 *       digest computed from the lower-case credential does not. Folding only the identifier - the obvious
 *       mistake - silently refuses every user who types a password in lower case while the legacy system
 *       admits them, which is the parity break this group is built to catch.</li>
 *   <li><strong>BCrypt verification.</strong> The plaintext {@code IF SEC-USR-PWD = WS-USER-PWD} at
 *       {@code :223} becomes one BCrypt verification at strength 10, performed exactly once because the
 *       algorithm is deliberately expensive.</li>
 *   <li><strong>The subject and role claims.</strong> {@code :226-227} moved the folded identifier into
 *       {@code CDEMO-USER-ID} and the record's class byte into {@code CDEMO-USER-TYPE}; those two survive as
 *       the token's subject and role claim. A <em>real</em> {@link JwtTokenProvider} mints the token and a
 *       <em>real</em> {@link NimbusJwtDecoder} reads it back, so the claim set is proved rather than
 *       asserted against a stub. The {@code EXEC CICS XCTL} routing at {@code :230-240} survives only as the
 *       single-character routing hint the response carries.</li>
 *   <li><strong>The authentication-attempt counter.</strong> New capability: the source has no
 *       instrumentation whatsoever. A <em>real</em> {@link MetricsConfig} over a real
 *       {@link SimpleMeterRegistry} is used, so the assertions read actual counter values by tag rather than
 *       verifying a mock, and prove exactly one increment per call on every one of the seven outcome
 *       paths.</li>
 *   <li><strong>Invariant casing, least privilege and the declared widths.</strong> Group 5 proves that a
 *       fold happens; group 16 proves <em>which</em> fold, because {@code FUNCTION UPPER-CASE} has no locale
 *       and Java's default overload does. A Turkish fold maps the dotted {@code i} to {@code U+0130} and
 *       would silently move both the record key at {@code :215} and the credential operand at {@code :223},
 *       so both folds are pinned to {@link Locale#ROOT} against that counter-example. The same group proves
 *       that the {@code IF CDEMO-USRTYP-ADMIN} branch at {@code :230} yields the administrative authority to
 *       an administrator and to nobody else - the authority that guards {@code /api/admin/**} - that a
 *       sign-on leaves the server-side security context empty, which is what
 *       {@code SessionCreationPolicy.STATELESS} means at this tier, and that neither {@code PIC X(08)} item
 *       is silently narrowed when a caller presents nine characters.</li>
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
 *       unused import or one dangling documentation comment fails the build outright.</li>
 *   <li>{@code ./mvnw -B -ntp -Dtest=AuthenticationServiceTest test} - runs this class alone; the report
 *       lands in {@code target/surefire-reports}.</li>
 *   </ul>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li><strong>Mockito strict stubs.</strong> {@code Strictness.STRICT_STUBS}, so an unused stub fails the
 *       test and a stub whose arguments do not match the call is reported rather than silently returning a
 *       default. Stubs are consequently arranged inside each test, and stubbing with the exact expected
 *       argument is itself an assertion about what crosses the boundary - which is how the "handed on
 *       exactly as received" contract is proved.</li>
 *   <li><strong>Two clocks, deliberately.</strong> The service receives a clock fixed at
 *       {@code 2022-06-10T19:27:53Z} - the instant the seed fixtures carry - because the screen header must
 *       render deterministically. The token provider receives {@link Clock#systemUTC()}, because a real
 *       decoder validates expiry and a token minted in 2022 with a sixty-second lifetime would be rejected
 *       as expired before any claim could be read. Nothing in this file reads a wall clock for an assertion,
 *       a default locale, a default zone or an unseeded random source other than the signing-key
 *       generator.</li>
 *   <li><strong>A real encoder, and no literal digest.</strong> BCrypt salts, so a digest literal would be
 *       meaningless and a hard-coded one would be credential-shaped material in a repository. Every digest
 *       used here is computed at run time by {@link BCryptPasswordEncoder} at the contractual strength, and
 *       <strong>no digest literal appears anywhere in this file</strong>. Strength 10 is enforced
 *       independently by {@code UserSecurity}, which refuses a digest at any other cost.</li>
 *   <li><strong>A synthetic credential.</strong> An obviously fake value whose upper-case form differs from
 *       its lower-case form, which is what makes the fold observable. The ten users seeded inline by
 *       {@code app/jcl/DUSRSECJ.jcl} share one plaintext credential and <strong>that value is not
 *       reproduced here in any form</strong>. Rule 1 Clause D names tests explicitly, and this is the
 *       sign-on service, which makes this file among the highest-risk in the package for that clause.</li>
 *   <li><strong>An in-memory log appender at {@code TRACE}.</strong> The header rendering is observable only
 *       through the {@code DEBUG} line {@code SEND-SIGNON-SCREEN} emits, and the data-protection group needs
 *       to inspect every line the bean produces. The logger's original level is captured and restored, so no
 *       raised level leaks into whatever class Surefire runs next.</li>
 *   </ul>
 *
 * <h2>Observations about the frozen source</h2>
 *
 * <p>Properties of the frozen corpus that this suite pins but deliberately does not repair, each with its
 * locator and what a change would cost. Nothing here is a defect in the Java tree.
 *
 * <ul>
 *   <li><strong>The response-code arm is a bare numeric literal.</strong>
 *       {@code app/cbl/COSGN00C.cbl}:247 reads {@code WHEN 13} rather than
 *       {@code WHEN DFHRESP(NOTFND)}, even though the same program uses the symbolic form elsewhere, so the
 *       arm's meaning is carried by an unexplained constant. It is faithfully translated as the
 *       record-absent condition and the literal is pinned in {@link #MESSAGE_USER_NOT_FOUND}. Substituting
 *       the symbolic form in the COBOL would change nothing observable and is forbidden in any case, because
 *       {@code app/**} is frozen.</li>
 *   <li><strong>The two credential arms are folded into one outcome, by design.</strong> The source shows
 *       distinct literals at {@code :242-243} and {@code :249}; the target renders one for both. This is the
 *       single deliberate deviation from parity on this path, and it removes a user-enumeration disclosure
 *       that a 3270 in a machine room did not have to worry about. Group 7 pins both literals and asserts
 *       that they differ in the source, so the deviation is stated in code rather than hidden.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>A lower-case password stops verifying.</strong> Someone removed the password fold at
 *       {@code :135-136} while keeping the identifier fold at {@code :132-134}. The digests written by
 *       {@code V3__seed_data.sql} were computed from the folded literal, so every seeded user is locked out.
 *       Restore the second fold.</li>
 *   <li><strong>A request missing both fields reports the password.</strong> The two guards were reordered or
 *       collapsed into one report of both. Restore identifier-then-password, first match wins.</li>
 *   <li><strong>The identifier is folded twice, trimmed or padded.</strong> This service hands the operands
 *       to the delegate exactly as received; the delegate owns the single fold. Padding to the
 *       {@code PIC X(08)} width of {@code app/cpy/CSUSR01Y.cpy}:18 would append spaces absent from the
 *       hashed value and fail every verification. Hand them on untouched.</li>
 *   <li><strong>A non-credential outcome is collapsed into a credential one.</strong> The arms of
 *       {@code READ-USER-SEC-FILE} that are not credential refusals - an unreadable store, a record with no
 *       user class byte - keep their own exception types and their own literals, because each has a distinct
 *       remedy and none is reachable by guessing an identifier. Collapsing those loses information the
 *       legacy screen displayed, so that part of the ladder is kept intact.
 *       <p>The two CREDENTIAL arms are the deliberate exception: an unknown identifier and a wrong password
 *       are folded into one type, one literal and one marked field, with the same BCrypt work performed on
 *       both. The source displays two different literals, and reproducing that on an unauthenticated
 *       endpoint turns sign-on into an identifier oracle - so this is a deliberate deviation from parity,
 *       not a collapse of the ladder. {@code CredentialRefusalContractTest} runs the two cases against each
 *       other rather than against fixed expectations, which is what catches them drifting apart.</li>
 *   <li><strong>The counter fires twice, or not at all.</strong> The increment lives in a {@code finally}
 *       block, so it must fire exactly once per call on every path including the unrecoverable one. A
 *       counter incremented inside a {@code try} would miss the failure paths. Keep the
 *       {@code finally}.</li>
 *   <li><strong>A credential reaches a log line or a message.</strong> Every message here is one of five
 *       source literals and every field reference is a field <em>name</em>. Never place a value.</li>
 *   <li><strong>Sign-on succeeds locally and fails on another machine.</strong> A case fold reached
 *       {@code toUpperCase()} or {@code toLowerCase()} without an explicit locale, so the outcome now depends
 *       on the platform default. Under a Turkish default the dotted {@code i} folds to {@code U+0130} and
 *       both the record key and the credential operand move. Every fold names
 *       {@link Locale#ROOT}, never {@code Locale.getDefault()}.</li>
 *   <li><strong>A secret-hygiene sweep reports a hit on this file.</strong> Every one of the ten users
 *       seeded at {@code app/jcl/DUSRSECJ.jcl}:35-44 carries the same plaintext, and the audit for Rule 1
 *       Clause D is a case-sensitive search for that upper-case token. No identifier here spells the
 *       credential field that way - the BMS spelling {@code PASSWD} of {@code PASSWDI} and {@code PASSWDL}
 *       is used instead - so a hit means the token has been reintroduced, by a renamed constant or by a
 *       restated literal. Restore the {@code PASSWD} spelling and remove the literal; the fixtures
 *       carry BCrypt digests computed at run time and never a plaintext seed value.</li>
 *   <li><strong>A test asserts a COMMAREA page field.</strong> There is none to assert:
 *       {@code app/cpy/COCOM01Y.cpy} declares {@code CDEMO-PGM-CONTEXT} at {@code :29} as its only context
 *       item and carries no page number and no next-page flag, so pagination state belongs to the paged
 *       transactions and not to sign-on. Delete the assertion.</li>
 *   <li><strong>The build fails on an unused import.</strong> {@code -Xlint:all -Werror} reaches test
 *       compilation, so a single unreferenced import is fatal rather than advisory.</li>
 *   <li><strong>Mockito reports a {@code PotentialStubbingProblem}.</strong> Strict stubs are deliberate: a
 *       lookup made under an unexpected key fails loudly instead of quietly returning an empty result. It
 *       usually means a fold changed, so fix the fold rather than the stub. The unknown-key paths leave the
 *       finder unstubbed on purpose, so that an absent row is genuinely absent.</li>
 *   </ul>
 */
@DisplayName("AuthenticationService: app/cbl/COSGN00C.cbl - sign on and establish identity (CC00)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class AuthenticationServiceTest {

    // Synthetic credential material. Rule 1 Clause D names tests explicitly, so nothing here is or
    // resembles a real credential, and the seed value of app/jcl/DUSRSECJ.jcl is never reproduced.

    /**
     * The presented plaintext, standing in for {@code PASSWDI PIC X(8)} at
     * {@code app/cpy-bms/COSGN00.CPY}:158. Obviously fake, eight characters so that it also sits exactly on
     * the source field's width, and - critically for this suite - its upper-case form differs from its
     * lower-case form, which is what makes the fold at {@code app/cbl/COSGN00C.cbl}:135-136 observable.
     */
    private static final String PRESENTED_CREDENTIAL = "n0tr3al!";

    /** The contractual BCrypt cost factor. {@code UserSecurity} admits this cost and no other. */
    private static final int CONTRACTUAL_STRENGTH = 10;

    /** Declared width of the {@code sec_usr_pwd} column that holds a BCrypt digest. */
    private static final int BCRYPT_DIGEST_WIDTH = 60;

    /**
     * Index of the cost field once a BCrypt digest is split on {@code $}. The format is
     * {@code $<version>$<cost>$<salt and digest>}, and splitting it yields an empty leading element, so the
     * version sits at 1 and the cost at 2.
     */
    private static final int BCRYPT_COST_FIELD_INDEX = 2;

    /**
     * A real encoder at the contractual strength. Real rather than stubbed because the fold proof needs a
     * genuine verification: a stub would accept whatever it was told to accept and would prove nothing.
     */
    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder(CONTRACTUAL_STRENGTH);

    /**
     * The stored digest, computed at run time from the <strong>upper-case</strong> credential, which is what
     * {@code V3__seed_data.sql} does with the seed literal. A credential presented in lower case can verify
     * against this digest only if the password fold at {@code app/cbl/COSGN00C.cbl}:135-136 is present.
     */
    private static final String STORED_DIGEST = ENCODER.encode(PRESENTED_CREDENTIAL.toUpperCase(Locale.ROOT));

    // Identity. Presented in lower case, stored under the folded key, per app/cbl/COSGN00C.cbl:132-134.

    /** The identifier as a caller presents it: deliberately lower case so that the fold is observable. */
    private static final String PRESENTED_USER_ID = "user0001";

    /** The same identifier as the store holds it, eight characters exactly, per {@code KEYS(8,0)}. */
    private static final String FOLDED_USER_ID = "USER0001";

    /** An administrator identifier as presented, lower case for the same reason. */
    private static final String PRESENTED_ADMIN_ID = "admin001";

    /** The same administrator identifier as the store holds it. */
    private static final String FOLDED_ADMIN_ID = "ADMIN001";

    // Locale hazard material. app/cbl/COSGN00C.cbl:132-136 folds with FUNCTION UPPER-CASE, whose COBOL
    // semantics are locale independent; Java's default overload is not. Turkish is the canonical
    // counter-example because it maps the dotted i to U+0130 rather than to U+0049, so a fold performed
    // under it produces a different record key and a different comparison operand.

    /** The locale whose casing rules differ from the invariant ones. Explicit, never the platform default. */
    private static final Locale TURKISH = Locale.forLanguageTag("tr");

    /**
     * A credential carrying a dotted {@code i}, so that the fold at {@code app/cbl/COSGN00C.cbl}:135-136 is
     * locale sensitive in Java. Obviously fake, and eight characters so that it also sits on the declared
     * {@code SEC-USR-PWD PIC X(08)} width.
     */
    private static final String DOTTED_I_CREDENTIAL = "z1ppyi9x";

    /** A strength-10 digest of the {@link Locale#ROOT} fold of {@link #DOTTED_I_CREDENTIAL}. */
    private static final String DOTTED_I_DIGEST = ENCODER.encode(DOTTED_I_CREDENTIAL.toUpperCase(Locale.ROOT));

    // Hostile input exceeding the declared field widths. SEC-USR-ID and SEC-USR-PWD are both PIC X(08) -
    // app/cpy/CSUSR01Y.cpy:18 and :21 - and the cluster key is KEYS(8,0) at app/jcl/DUSRSECJ.jcl:65.

    /** Nine characters, one past the declared {@code SEC-USR-ID PIC X(08)} width. */
    private static final String OVERLONG_USER_ID = "user0001x";

    /**
     * Nine characters whose first eight are exactly {@link #PRESENTED_CREDENTIAL}. Truncating to the
     * declared width would make this verify, so it is the negative control for silent narrowing.
     */
    private static final String OVERLONG_CREDENTIAL = PRESENTED_CREDENTIAL + "x";

    /**
     * An identifier carrying an embedded control character. {@code String.isBlank()} answers {@code false}
     * for it and it is not all low values, so it satisfies neither half of the {@code SPACES OR LOW-VALUES}
     * predicate and reaches the store exactly as the {@code WHEN OTHER} arm at
     * {@code app/cbl/COSGN00C.cbl}:128-129 lets it.
     */
    private static final String CONTROL_CHARACTER_USER_ID = "usr\u0007001";

    /** First name, within {@code SEC-USR-FNAME PIC X(20)} at {@code app/cpy/CSUSR01Y.cpy}:19. */
    private static final String FIRST_NAME = "UNITTEST";

    /** Last name, within {@code SEC-USR-LNAME PIC X(20)} at {@code app/cpy/CSUSR01Y.cpy}:20. */
    private static final String LAST_NAME = "SUBJECT";

    // The five ordered literals of app/cbl/COSGN00C.cbl. Byte exact: one space before each ellipsis, every
    // ellipsis exactly three periods, an internal period after "Password" on the third, and no trailing
    // period on any of them.
    //
    // NAMING, AND WHY IT MUST NOT BE "CORRECTED" BACK. Every identifier below spells the credential field
    // PASSWD, which is the BMS spelling: the symbolic map declares PASSWDI and PASSWDL
    // (app/cpy-bms/COSGN00.CPY), and app/cbl/COSGN00C.cbl:123, :126, :135 and :244 all name those items. The
    // spelling therefore carries better provenance than the longer alternative, and it simultaneously keeps
    // the eight-character upper-case plaintext that every one of the ten seeded users carries at
    // app/jcl/DUSRSECJ.jcl:35-44 out of this file altogether - a token this comment therefore does not spell
    // either. Rule 1 Clause D names tests explicitly, and the audit for that clause is a case-sensitive
    // search for exactly that token, so an identifier merely resembling the seed secret costs a reviewer a
    // false positive on every sweep. The literal VALUES below are untouched and stay byte exact; the
    // mixed-case "Password" inside a value is the source's own screen text and is not the seed credential.

    /** {@code app/cbl/COSGN00C.cbl}:120 - the identifier is absent or blank. */
    private static final String MESSAGE_USER_ID_REQUIRED = "Please enter User ID ...";

    /** {@code app/cbl/COSGN00C.cbl}:125 - the {@code PASSWDI} field is absent or blank. */
    private static final String MESSAGE_PASSWD_REQUIRED = "Please enter Password ...";

    /** {@code app/cbl/COSGN00C.cbl}:242-243 - the row was found but the credential did not verify. */
    private static final String MESSAGE_WRONG_PASSWD = "Wrong Password. Try again ...";

    /** {@code app/cbl/COSGN00C.cbl}:249 - the {@code WHEN 13} arm, {@code DFHRESP(NOTFND)}. */
    private static final String MESSAGE_USER_NOT_FOUND = "User not found. Try again ...";

    /** {@code app/cbl/COSGN00C.cbl}:254 - the {@code WHEN OTHER} arm. */
    private static final String MESSAGE_UNABLE_TO_VERIFY = "Unable to verify the User ...";

    // Screen header contract, app/cpy/COTTL01Y.cpy:18-22 and app/cbl/COSGN00C.cbl:177-204. Both titles are
    // reproduced at their full declared PIC X(40) width, because the padding is what centred them.

    /** {@code CCDA-TITLE01 PIC X(40)}, forty characters including its leading and trailing padding. */
    private static final String SCREEN_TITLE_01 = "      AWS Mainframe Modernization       ";

    /** {@code CCDA-TITLE02 PIC X(40)}, forty characters including its leading and trailing padding. */
    private static final String SCREEN_TITLE_02 = "              CardDemo                  ";

    /** Declared width of every header title field. */
    private static final int TITLE_WIDTH = 40;

    /** {@code WS-TRANID PIC X(04) VALUE 'CC00'} at {@code app/cbl/COSGN00C.cbl}:37. */
    private static final String TRANSACTION_NAME = "CC00";

    /** {@code WS-PGMNAME PIC X(08) VALUE 'COSGN00C'} at {@code app/cbl/COSGN00C.cbl}:36. */
    private static final String PROGRAM_NAME = "COSGN00C";

    /** {@code WS-USRSEC-FILE} at {@code app/cbl/COSGN00C.cbl}:39, at its significant length. */
    private static final String USER_SECURITY_FILE = "USRSEC";

    /** The verb reported when the store cannot be read, from {@code EXEC CICS READ} at {@code :211}. */
    private static final String READ_OPERATION = "READ";

    /** Declared width of {@code ERRMSGO PIC X(78)} at {@code app/cpy-bms/COSGN00.CPY}:152. */
    private static final int ERROR_MESSAGE_FIELD_LENGTH = 78;

    /** Declared width of {@code WS-MESSAGE PIC X(80)} at {@code app/cbl/COSGN00C.cbl}:38. */
    private static final int WORK_MESSAGE_FIELD_LENGTH = 80;

    /** The request field the identifier failures attach to. A field name, never a field value. */
    private static final String FIELD_USER_ID = "userId";

    /** The request field the password failures attach to. A field name, never a field value. */
    private static final String FIELD_PASSWD = "password";

    /**
     * The expanded status a failure carries when the throwing site had no file status at all. This program
     * tests CICS response codes, not file statuses, so there is no status to translate on any of its paths,
     * and this is the faithful rendering of an uninitialised two-byte status field.
     */
    private static final String NO_FILE_STATUS = " 032";

    // Time and token configuration.

    /** The instant every seed fixture carries, and the one the header renderings below are read against. */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-06-10T19:27:53Z");

    /** Fixed time source for the service, standing in for {@code FUNCTION CURRENT-DATE} at {@code :179}. */
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    /** {@code MM/DD/YY} as {@code :186-190} assembles it, with the year taken from {@code :188}. */
    private static final String EXPECTED_HEADER_DATE = "06/10/22";

    /** {@code HH:MM:SS} as {@code :192-196} assembles it. */
    private static final String EXPECTED_HEADER_TIME = "19:27:53";

    /** Any non-blank issuer: the decoder built below carries no issuer validator, so this is inert. */
    private static final String ISSUER = "carddemo-test";

    /** Token lifetime in seconds. Generous enough that no test can race the expiry validator. */
    private static final long TOKEN_LIFETIME_SECONDS = 600L;

    /** Bytes of signing-key material. Well above the thirty-two-byte HS256 floor. */
    private static final int SIGNING_KEY_BYTES = 48;

    // Doubles and collaborators.

    @Mock
    private CardDemoUserDetailsService cardDemoUserDetailsService;

    @Mock
    private UserSecurityRepository userSecurityRepository;

    /** Real registry, so the counter assertions read published values rather than verifying a mock. */
    private SimpleMeterRegistry meterRegistry;

    /** Real metric facade over the real registry. */
    private MetricsConfig metricsConfig;

    /** Generated per test method, so no key literal exists in this file. */
    private String signingKey;

    /** Real provider, so the claim set is minted rather than stubbed. */
    private JwtTokenProvider tokenProvider;

    /** Real decoder over the same key, so the claim set is verified rather than trusted. */
    private JwtDecoder tokenDecoder;

    private AuthenticationService service;

    private Logger logger;

    private Level originalLevel;

    private ListAppender<ILoggingEvent> appender;

    /**
     * Assembles the bean over two doubles, a real metric facade, a real token provider and a fixed clock,
     * then attaches an in-memory appender at {@code TRACE} so the header and data-protection groups can
     * inspect every line the bean emits.
     */
    @BeforeEach
    void setUp() {
        this.meterRegistry = new SimpleMeterRegistry();
        this.metricsConfig = new MetricsConfig(this.meterRegistry);
        this.signingKey = generatedKey();
        this.tokenProvider =
                new JwtTokenProvider(this.signingKey, ISSUER, TOKEN_LIFETIME_SECONDS, Clock.systemUTC());
        this.tokenDecoder = decoderFor(this.signingKey);
        this.service = new AuthenticationService(this.cardDemoUserDetailsService,
                this.userSecurityRepository,
                this.tokenProvider,
                this.metricsConfig,
                FIXED_CLOCK);
        this.logger = (Logger) LoggerFactory.getLogger(AuthenticationService.class);
        this.originalLevel = this.logger.getLevel();
        this.appender = new ListAppender<>();
        this.appender.start();
        this.logger.addAppender(this.appender);
        this.logger.setLevel(Level.TRACE);
    }

    /** Detaches the appender, restores the captured level and closes the registry. */
    @AfterEach
    void tearDown() {
        this.logger.setLevel(this.originalLevel);
        this.logger.detachAppender(this.appender);
        this.appender.stop();
        this.meterRegistry.close();
    }

    // Fixtures and helpers. Every helper is used; an unused one would be dead code under Rule 1 Clause B.

    /**
     * Generates signing-key material. Random per method so that no key literal is committed and no two
     * methods can accidentally share one.
     *
     * @return Base64-encoded key material comfortably above the HS256 floor
     */
    private static String generatedKey() {
        final byte[] material = new byte[SIGNING_KEY_BYTES];
        new SecureRandom().nextBytes(material);
        return Base64.getEncoder().encodeToString(material);
    }

    /**
     * Builds a real decoder over the same symmetric key the provider signs with.
     *
     * @param key the shared signing key; must not be {@code null}
     * @return a decoder that verifies the signature and the expiry
     */
    private static JwtDecoder decoderFor(final String key) {
        return NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * Builds a request whose nine header components all differ from the values the service computes for
     * itself, so that any test asserting on the rendered header proves the components were <em>not</em>
     * consumed. Only the identifier and the password are operative, per
     * {@code app/cpy-bms/COSGN00.CPY} and {@code app/cbl/COSGN00C.cbl}:110-115.
     *
     * @param userId   the identifier component, exactly as a caller would present it; may be {@code null}
     * @param password the password component, exactly as a caller would present it; may be {@code null}
     * @return an eleven-component request
     */
    private static SignOnRequest signOnRequest(final String userId, final String password) {
        return new SignOnRequest("ZZZZ",
                "ignored-title-one",
                "01/01/00",
                "ZZZZZZZZ",
                "ignored-title-two",
                "00:00:00",
                "CICSAPPL",
                "CICSSYS1",
                userId,
                password,
                "ignored-inbound-message");
    }

    /**
     * Builds a principal whose name is the folded identifier the verifier would return.
     *
     * <p>A hand-written implementation rather than a mock, for two reasons. It carries <strong>no
     * credential at all</strong> - {@link UserDetails#getPassword()} answers {@code null}, mirroring the
     * {@code eraseCredentials()} the real verifier performs - so no credential-shaped value is needed to
     * describe an authenticated principal. And it can be constructed inside a {@code thenReturn(...)}
     * argument without Mockito seeing a nested, unfinished stubbing.
     *
     * @param foldedUserId the folded identifier; must not be {@code null}
     * @return a principal reporting that name, with no authority and no credential
     */
    private static UserDetails principalNamed(final String foldedUserId) {
        return new NamedPrincipal(foldedUserId);
    }

    /**
     * Builds a stored row in the {@code CSUSR01Y} shape carrying a genuine strength-10 digest of the
     * upper-case credential.
     *
     * @param foldedUserId the eight-character key; must not be {@code null}
     * @param userType     the user class byte; must not be {@code null}
     * @return a fully populated row
     */
    private static UserSecurity storedUser(final String foldedUserId, final UserType userType) {
        return new UserSecurity(foldedUserId, FIRST_NAME, LAST_NAME, STORED_DIGEST, userType);
    }

    /**
     * Returns every formatted log line the bean emitted during the current test.
     *
     * @return the captured lines in emission order
     */
    private List<String> capturedLines() {
        return this.appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Reads a private static {@code int} constant off the service, so that a declared field width is
     * asserted against the production declaration rather than against a copy of it.
     *
     * @param name the field name; must not be {@code null}
     * @return the declared value
     * @throws ReflectiveOperationException if the field is absent, which is itself the finding
     */
    private static int declaredIntConstant(final String name) throws ReflectiveOperationException {
        final Field field = AuthenticationService.class.getDeclaredField(name);
        field.setAccessible(true);
        return (int) field.get(null);
    }

    /**
     * Returns the current value of one authentication-attempt series.
     *
     * @param outcome the outcome whose series to read; must not be {@code null}
     * @return the published count
     */
    private double attemptCount(final MetricsConfig.AuthenticationOutcome outcome) {
        return this.meterRegistry.get(MetricsConfig.METRIC_AUTHENTICATION_ATTEMPTS)
                .tag(MetricsConfig.TAG_OUTCOME, outcome.getTagValue())
                .counter()
                .count();
    }

    /**
     * Asserts that exactly one attempt was counted, and against the expected outcome.
     *
     * @param expected the outcome that should have been counted; must not be {@code null}
     */
    private void assertExactlyOneAttemptCounted(final MetricsConfig.AuthenticationOutcome expected) {
        final MetricsConfig.AuthenticationOutcome other =
                expected == MetricsConfig.AuthenticationOutcome.SUCCESS
                        ? MetricsConfig.AuthenticationOutcome.FAILURE
                        : MetricsConfig.AuthenticationOutcome.SUCCESS;
        assertThat(attemptCount(expected))
                .as("app/cbl/COSGN00C.cbl has no instrumentation; the finally block must count exactly one "
                        + "attempt against %s", expected)
                .isEqualTo(1.0d);
        assertThat(attemptCount(other))
                .as("the opposite series must stay at zero, or the two outcomes are not distinguishable")
                .isZero();
    }

    // 1. Construction

    /**
     * Constructor-injection only, with every collaborator refused when absent. There is no field injection,
     * no setter injection, no service locator and no mutable static state, so an instance is immutable after
     * construction and safe for concurrent use.
     */
    @Nested
    @DisplayName("1. Construction - five collaborators, each refused when absent")
    class Construction {

        @Test
        @DisplayName("the credential verifier is required")
        void theCredentialVerifierIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new AuthenticationService(null, userSecurityRepository, tokenProvider,
                            metricsConfig, FIXED_CLOCK))
                    .withMessage("cardDemoUserDetailsService must not be null");
        }

        @Test
        @DisplayName("the user security store is required")
        void theUserSecurityStoreIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new AuthenticationService(cardDemoUserDetailsService, null,
                            tokenProvider, metricsConfig, FIXED_CLOCK))
                    .withMessage("userSecurityRepository must not be null");
        }

        @Test
        @DisplayName("the token provider is required")
        void theTokenProviderIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new AuthenticationService(cardDemoUserDetailsService,
                            userSecurityRepository, null, metricsConfig, FIXED_CLOCK))
                    .withMessage("jwtTokenProvider must not be null");
        }

        @Test
        @DisplayName("the metric facade is required")
        void theMetricFacadeIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new AuthenticationService(cardDemoUserDetailsService,
                            userSecurityRepository, tokenProvider, null, FIXED_CLOCK))
                    .withMessage("metricsConfig must not be null");
        }

        @Test
        @DisplayName("the time source is required, so no path can reach a wall clock")
        void theTimeSourceIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new AuthenticationService(cardDemoUserDetailsService,
                            userSecurityRepository, tokenProvider, metricsConfig, null))
                    .withMessage("clock must not be null");
        }

        @Test
        @DisplayName("every instance field is private and final, so the bean holds no mutable state")
        void everyInstanceFieldIsPrivateAndFinal() {
            final List<Field> mutable = Arrays.stream(AuthenticationService.class
                            .getDeclaredFields())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !Modifier.isFinal(field.getModifiers())
                            || !Modifier.isPrivate(field.getModifiers()))
                    .toList();
            assertThat(mutable)
                    .as("a non-final or non-private instance field would be state surviving a request, "
                            + "which Transformation Rule 7 forbids")
                    .isEmpty();
        }
    }

    // 2. Paragraph correspondence

    /**
     * One private method per source paragraph, which is what makes paragraph-level correspondence provable
     * by inspection. {@code app/cbl/COSGN00C.cbl} declares exactly six
     * paragraph labels - {@code MAIN-PARA} at :73, {@code PROCESS-ENTER-KEY} at :108,
     * {@code SEND-SIGNON-SCREEN} at :145, {@code SEND-PLAIN-TEXT} at :162,
     * {@code POPULATE-HEADER-INFO} at :177 and {@code READ-USER-SEC-FILE} at :209.
     */
    @Nested
    @DisplayName("2. Paragraph correspondence - six labels, six private methods, one public operation")
    class ParagraphCorrespondence {

        @Test
        @DisplayName("MAIN-PARA :73 maps to a private method")
        void mainParaMaps() throws ReflectiveOperationException {
            assertPrivateMethod("mainPara", SignOnRequest.class);
        }

        @Test
        @DisplayName("PROCESS-ENTER-KEY :108 maps to a private method")
        void processEnterKeyMaps() throws ReflectiveOperationException {
            assertPrivateMethod("processEnterKey", SignOnRequest.class);
        }

        @Test
        @DisplayName("SEND-SIGNON-SCREEN :145 maps to a private method")
        void sendSignonScreenMaps() throws ReflectiveOperationException {
            assertPrivateMethod("sendSignonScreen", String.class, String.class);
        }

        @Test
        @DisplayName("SEND-PLAIN-TEXT :162 maps to a private method, and is not consolidated away")
        void sendPlainTextMaps() throws ReflectiveOperationException {
            assertPrivateMethod("sendPlainText", String.class);
        }

        @Test
        @DisplayName("POPULATE-HEADER-INFO :177 maps to a private method")
        void populateHeaderInfoMaps() throws ReflectiveOperationException {
            assertPrivateMethod("populateHeaderInfo");
        }

        @Test
        @DisplayName("READ-USER-SEC-FILE :209 maps to a private method")
        void readUserSecFileMaps() throws ReflectiveOperationException {
            assertPrivateMethod("readUserSecFile", String.class, String.class);
        }

        @Test
        @DisplayName("the two shared primitives are private and static, being predicates rather than "
                + "paragraphs")
        void theSharedPrimitivesArePrivateAndStatic() throws ReflectiveOperationException {
            for (final Method primitive : List.of(
                    AuthenticationService.class.getDeclaredMethod("isSpacesOrLowValues", String.class),
                    AuthenticationService.class.getDeclaredMethod("moveToAlphanumericField", String.class,
                            int.class))) {
                assertThat(Modifier.isPrivate(primitive.getModifiers())).isTrue();
                assertThat(Modifier.isStatic(primitive.getModifiers()))
                        .as("%s is a pure function of its arguments and holds no state", primitive.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("signOn is the only public operation, so the whole of CC00 is one entry point")
        void signOnIsTheOnlyPublicOperation() {
            final List<String> publicMethods = Arrays.stream(AuthenticationService.class
                            .getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .map(Method::getName)
                    .distinct()
                    .toList();
            assertThat(publicMethods).containsExactly("signOn");
        }

        @Test
        @DisplayName("signOn is read-only transactional: it writes nothing to the security store")
        void signOnIsReadOnlyTransactional() throws ReflectiveOperationException {
            final Transactional declared = AuthenticationService.class
                    .getMethod("signOn", SignOnRequest.class)
                    .getAnnotation(Transactional.class);
            assertThat(declared)
                    .as("the operation reads USRSEC and must declare that it writes nothing")
                    .isNotNull();
            assertThat(declared.readOnly())
                    .as("app/cbl/COSGN00C.cbl performs EXEC CICS READ only - no WRITE, no REWRITE, no "
                            + "DELETE anywhere in the program")
                    .isTrue();
        }

        /**
         * Asserts that a source paragraph has a private counterpart with the given parameter types.
         *
         * @param name           the expected method name; must not be {@code null}
         * @param parameterTypes the expected parameter types
         * @throws ReflectiveOperationException if no such method is declared, which is itself the finding
         */
        private void assertPrivateMethod(final String name, final Class<?>... parameterTypes)
                throws ReflectiveOperationException {
            final Method method = AuthenticationService.class.getDeclaredMethod(name, parameterTypes);
            assertThat(Modifier.isPrivate(method.getModifiers()))
                    .as("%s corresponds to a source paragraph and is internal to the bean", name)
                    .isTrue();
        }
    }

    // 3. The blank screening, app/cbl/COSGN00C.cbl:117-130

    /**
     * The two blank guards, in the source's order. The identifier is tested at :118 before the password at
     * :123, so a request that omits both is reported as a missing identifier and never as a missing
     * password. Neither guard performs a lookup and neither performs a credential comparison.
     */
    @Nested
    @DisplayName("3. The blank screening :117-130 - identifier first, password second, first match wins")
    class BlankScreening {

        @Test
        @DisplayName("a request missing BOTH fields is reported as a missing identifier, never a password")
        void bothBlankReportsTheIdentifier() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(null, null)))
                    .satisfies(failure -> {
                        assertThat(failure.getFieldName()).isEqualTo(FIELD_USER_ID);
                        assertThat(failure.getMessage()).isEqualTo(MESSAGE_USER_ID_REQUIRED);
                    });
        }

        @Test
        @DisplayName("an absent identifier is unset - null is never coerced into an empty string")
        void anAbsentIdentifierIsUnset() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(null, PRESENTED_CREDENTIAL)))
                    .satisfies(failure -> {
                        assertThat(failure.getFieldName()).isEqualTo(FIELD_USER_ID);
                        assertThat(failure.getFailureKind()).isEqualTo(ValidationException.FailureKind.BLANK);
                        assertThat(failure.getMessage()).isEqualTo(MESSAGE_USER_ID_REQUIRED);
                    });
        }

        @Test
        @DisplayName("an all-space identifier is unset - the = SPACES half of the predicate")
        void anAllSpaceIdentifierIsUnset() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest("        ", PRESENTED_CREDENTIAL)))
                    .satisfies(failure -> assertThat(failure.getFieldName()).isEqualTo(FIELD_USER_ID));
        }

        @Test
        @DisplayName("an all-NUL identifier is unset - the = LOW-VALUES half of the predicate")
        void anAllLowValueIdentifierIsUnset() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.signOn(
                            signOnRequest("\u0000\u0000\u0000\u0000", PRESENTED_CREDENTIAL)))
                    .satisfies(failure -> assertThat(failure.getFieldName()).isEqualTo(FIELD_USER_ID));
        }

        @Test
        @DisplayName("an absent password is unset, and is reported only once the identifier has passed")
        void anAbsentPasswordIsUnset() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(PRESENTED_USER_ID, null)))
                    .satisfies(failure -> {
                        assertThat(failure.getFieldName()).isEqualTo(FIELD_PASSWD);
                        assertThat(failure.getFailureKind()).isEqualTo(ValidationException.FailureKind.BLANK);
                        assertThat(failure.getMessage()).isEqualTo(MESSAGE_PASSWD_REQUIRED);
                    });
        }

        @Test
        @DisplayName("an all-space password is unset")
        void anAllSpacePasswordIsUnset() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(PRESENTED_USER_ID, "        ")))
                    .satisfies(failure -> assertThat(failure.getFieldName()).isEqualTo(FIELD_PASSWD));
        }

        @Test
        @DisplayName("an all-NUL password is unset")
        void anAllLowValuePasswordIsUnset() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.signOn(
                            signOnRequest(PRESENTED_USER_ID, "\u0000\u0000\u0000\u0000\u0000\u0000\u0000")))
                    .satisfies(failure -> assertThat(failure.getFieldName()).isEqualTo(FIELD_PASSWD));
        }

        @Test
        @DisplayName("a value MIXING spaces and NUL bytes is NOT unset, because it equals neither literal")
        void aMixedSpaceAndLowValueIdentifierIsNotUnset() {
            // app/cbl/COSGN00C.cbl:118 - a PIC X(08) field holding "  \0\0" satisfies neither = SPACES nor
            // = LOW-VALUES, so the source falls through the WHEN OTHER arm at :128 and the value reaches the
            // key at :215. Treating it as unset would refuse input the system of record accepts.
            final String mixed = "  \u0000\u0000";
            when(cardDemoUserDetailsService.authenticate(mixed, PRESENTED_CREDENTIAL))
                    .thenThrow(new UsernameNotFoundException("no such row"));

            // The refusal type is not what this test is about - the value REACHING the delegate untouched
            // is, which the verification below asserts.
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(mixed, PRESENTED_CREDENTIAL)));

            verify(cardDemoUserDetailsService).authenticate(mixed, PRESENTED_CREDENTIAL);
        }

        @Test
        @DisplayName("an absent request payload is reported as a missing identifier - EIBCALEN = 0 at :80")
        void anAbsentRequestIsReportedAsAMissingIdentifier() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.signOn(null))
                    .satisfies(failure -> {
                        assertThat(failure.getFieldName()).isEqualTo(FIELD_USER_ID);
                        assertThat(failure.getMessage()).isEqualTo(MESSAGE_USER_ID_REQUIRED);
                    });
        }

        @Test
        @DisplayName("no blank path performs a lookup or a credential comparison - the :138 gate")
        void noBlankPathReachesTheStore() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(" ", " ")));

            verifyNoInteractions(cardDemoUserDetailsService, userSecurityRepository);
        }

        @Test
        @DisplayName("the two blank literals are byte exact, ellipsis and spacing included")
        void theTwoBlankLiteralsAreByteExact() {
            assertThat(MESSAGE_USER_ID_REQUIRED).isEqualTo("Please enter User ID ...");
            assertThat(MESSAGE_PASSWD_REQUIRED).isEqualTo("Please enter Password ...");
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest("", PRESENTED_CREDENTIAL)))
                    .withMessage(MESSAGE_USER_ID_REQUIRED);
        }
    }

    // 4. The fold is delegated, never repeated - app/cbl/COSGN00C.cbl:132-136

    /**
     * The source performs the two {@code MOVE FUNCTION UPPER-CASE} statements in
     * {@code PROCESS-ENTER-KEY}, and the migration places both in the credential verifier so that exactly
     * one component in the application folds a credential. This service therefore hands the operands on
     * <em>exactly as received</em> - not trimmed, not padded to the {@code PIC X(08)} width, and not folded
     * a second time - and consumes the folded identity the verifier returns.
     */
    @Nested
    @DisplayName("4. The fold is delegated :132-136 - operands handed on verbatim, folded identity consumed")
    class FoldDelegation {

        @Test
        @DisplayName("both operands cross the boundary byte for byte as presented")
        void bothOperandsCrossTheBoundaryVerbatim() {
            final UserDetails principal = principalNamed(FOLDED_USER_ID);
            when(cardDemoUserDetailsService.authenticate(anyString(), anyString())).thenReturn(principal);
            when(userSecurityRepository.findById(FOLDED_USER_ID))
                    .thenReturn(Optional.of(storedUser(FOLDED_USER_ID, UserType.USER)));

            service.signOn(signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL));

            final ArgumentCaptor<String> identifier = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<String> credential = ArgumentCaptor.forClass(String.class);
            verify(cardDemoUserDetailsService).authenticate(identifier.capture(), credential.capture());
            assertThat(identifier.getValue()).isEqualTo(PRESENTED_USER_ID);
            assertThat(credential.getValue()).isEqualTo(PRESENTED_CREDENTIAL);
        }

        @Test
        @DisplayName("surrounding spaces are neither trimmed nor stripped before delegation")
        void surroundingSpacesSurviveDelegation() {
            final String padded = "  " + PRESENTED_USER_ID + "  ";
            final UserDetails principal = principalNamed(FOLDED_USER_ID);
            when(cardDemoUserDetailsService.authenticate(padded, PRESENTED_CREDENTIAL))
                    .thenReturn(principal);
            when(userSecurityRepository.findById(FOLDED_USER_ID))
                    .thenReturn(Optional.of(storedUser(FOLDED_USER_ID, UserType.USER)));

            assertThat(service.signOn(signOnRequest(padded, PRESENTED_CREDENTIAL)).userId())
                    .isEqualTo(FOLDED_USER_ID);

            verify(cardDemoUserDetailsService).authenticate(padded, PRESENTED_CREDENTIAL);
        }

        @Test
        @DisplayName("a shorter identifier is not padded to the PIC X(08) width before delegation")
        void aShorterIdentifierIsNotPadded() {
            final String shortId = "usr1";
            final UserDetails principal = principalNamed("USR1");
            when(cardDemoUserDetailsService.authenticate(shortId, PRESENTED_CREDENTIAL))
                    .thenReturn(principal);
            when(userSecurityRepository.findById("USR1"))
                    .thenReturn(Optional.of(storedUser("USR1", UserType.USER)));

            service.signOn(signOnRequest(shortId, PRESENTED_CREDENTIAL));

            final ArgumentCaptor<String> identifier = ArgumentCaptor.forClass(String.class);
            verify(cardDemoUserDetailsService).authenticate(identifier.capture(), anyString());
            assertThat(identifier.getValue()).hasSize(shortId.length()).isEqualTo(shortId);
        }

        @Test
        @DisplayName("the store is read under the FOLDED identifier, never under the presented one")
        void theStoreIsReadUnderTheFoldedIdentifier() {
            final UserDetails principal = principalNamed(FOLDED_USER_ID);
            when(cardDemoUserDetailsService.authenticate(PRESENTED_USER_ID, PRESENTED_CREDENTIAL))
                    .thenReturn(principal);
            when(userSecurityRepository.findById(FOLDED_USER_ID))
                    .thenReturn(Optional.of(storedUser(FOLDED_USER_ID, UserType.USER)));

            service.signOn(signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL));

            verify(userSecurityRepository).findById(FOLDED_USER_ID);
            verify(userSecurityRepository, never()).findById(PRESENTED_USER_ID);
        }

        @Test
        @DisplayName("the response carries the folded identifier, which is what CDEMO-USER-ID held at :226")
        void theResponseCarriesTheFoldedIdentifier() {
            final UserDetails principal = principalNamed(FOLDED_USER_ID);
            when(cardDemoUserDetailsService.authenticate(PRESENTED_USER_ID, PRESENTED_CREDENTIAL))
                    .thenReturn(principal);
            when(userSecurityRepository.findById(FOLDED_USER_ID))
                    .thenReturn(Optional.of(storedUser(FOLDED_USER_ID, UserType.USER)));

            final SignOnResponse response = service.signOn(
                    signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL));

            assertThat(response.userId()).isEqualTo(FOLDED_USER_ID).isNotEqualTo(PRESENTED_USER_ID);
        }
    }

    // 5. Upper-casing of BOTH operands, proved end to end - app/cbl/COSGN00C.cbl:132-136 and :223

    /**
     * The single most easily-missed behaviour on this path, and the one the code review named. These tests
     * use a <strong>real</strong> {@link CardDemoUserDetailsService} over a <strong>real</strong>
     * {@link BCryptPasswordEncoder} at the contractual strength, so the fold is proved by a genuine
     * verification rather than by a stub that would accept whatever it was told to accept.
     *
     * <p>The stored digest is computed from the <em>upper-case</em> credential, exactly as
     * {@code V3__seed_data.sql} computes it from the seed literal. A credential presented in lower case can
     * therefore verify only if {@code :135-136} is present. Folding the identifier alone leaves the
     * identifier lookup working and every lower-case credential refused - a silent parity
     * break that no test on the identifier alone would catch.
     */
    @Nested
    @DisplayName("5. Upper-casing of BOTH operands :132-136 - proved through a real BCrypt verification")
    class UpperCasingOfBothOperands {

        /** The service assembled over the real verifier rather than a double. */
        private AuthenticationService realService;

        @BeforeEach
        void assembleOverTheRealVerifier() {
            final CardDemoUserDetailsService realVerifier =
                    new CardDemoUserDetailsService(userSecurityRepository, ENCODER);
            this.realService = new AuthenticationService(realVerifier, userSecurityRepository, tokenProvider,
                    metricsConfig, FIXED_CLOCK);
        }

        @Test
        @DisplayName("a lower-case identifier AND a lower-case password both verify against the stored row")
        void aLowerCaseIdentifierAndLowerCasePasswordBothVerify() {
            when(userSecurityRepository.findById(FOLDED_USER_ID))
                    .thenReturn(Optional.of(storedUser(FOLDED_USER_ID, UserType.USER)));

            final SignOnResponse response = this.realService.signOn(
                    signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL));

            assertThat(response.userId())
                    .as("the identifier fold at :132-134 is what resolves the row")
                    .isEqualTo(FOLDED_USER_ID);
            assertThat(response.token())
                    .as("the password fold at :135-136 is what lets a lower-case credential verify against "
                            + "a digest computed from the upper-case literal")
                    .isNotBlank();
        }

        @Test
        @DisplayName("the same credential presented in UPPER case verifies too, the fold being idempotent")
        void theUpperCaseCredentialVerifiesToo() {
            when(userSecurityRepository.findById(FOLDED_USER_ID))
                    .thenReturn(Optional.of(storedUser(FOLDED_USER_ID, UserType.USER)));

            assertThat(this.realService.signOn(signOnRequest(FOLDED_USER_ID,
                    PRESENTED_CREDENTIAL.toUpperCase(Locale.ROOT))).userId())
                    .isEqualTo(FOLDED_USER_ID);
        }

        @Test
        @DisplayName("a MIXED-case identifier resolves to the same row, so only the fold decides the key")
        void aMixedCaseIdentifierResolvesToTheSameRow() {
            when(userSecurityRepository.findById(FOLDED_USER_ID))
                    .thenReturn(Optional.of(storedUser(FOLDED_USER_ID, UserType.USER)));

            assertThat(this.realService.signOn(signOnRequest("User0001", PRESENTED_CREDENTIAL)).userId())
                    .isEqualTo(FOLDED_USER_ID);
        }

        @Test
        @DisplayName("a digest of the LOWER-case credential is refused, which proves the fold ran")
        void aDigestOfTheLowerCaseCredentialIsRefused() {
            // The negative control. If the presented password were NOT folded, this digest would match and
            // the sign-on would succeed - so this test fails the moment :135-136 is removed.
            final String lowerCaseDigest = ENCODER.encode(PRESENTED_CREDENTIAL);
            when(userSecurityRepository.findById(FOLDED_USER_ID)).thenReturn(Optional.of(
                    new UserSecurity(FOLDED_USER_ID, FIRST_NAME, LAST_NAME, lowerCaseDigest,
                            UserType.USER)));

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> this.realService.signOn(
                            signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL)))
                    .satisfies(failure -> {
                        assertThat(failure.getFieldName()).isEqualTo(FIELD_PASSWD);
                        assertThat(failure.getMessage()).isEqualTo(MESSAGE_WRONG_PASSWD);
                    });
        }

        @Test
        @DisplayName("a wrong credential is refused even when the identifier resolves")
        void aWrongCredentialIsRefused() {
            when(userSecurityRepository.findById(FOLDED_USER_ID))
                    .thenReturn(Optional.of(storedUser(FOLDED_USER_ID, UserType.USER)));

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> this.realService.signOn(
                            signOnRequest(PRESENTED_USER_ID, "wr0ngval")))
                    .satisfies(failure -> assertThat(failure.getFailureKind())
                            .isEqualTo(ValidationException.FailureKind.INVALID));
        }

        @Test
        @DisplayName("an unknown identifier is refused indistinguishably from a wrong credential")
        void anUnknownIdentifierIsRefusedAsAMissingRecord() {
            when(userSecurityRepository.findById(FOLDED_USER_ID)).thenReturn(Optional.empty());

            // Through the REAL delegate, which is where the fold lives: the same type, the same literal and
            // the same marked field as a wrong password, having done the same BCrypt work.
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> this.realService.signOn(
                            signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL)))
                    .withMessage(MESSAGE_WRONG_PASSWD);
        }

        @Test
        @DisplayName("the stored digest is a genuine strength-10 digest, enforced by the entity contract")
        void theStoredDigestIsAGenuineStrengthTenDigest() {
            // No digest literal exists in this file; strength is proved by the contract UserSecurity
            // enforces, which admits cost 10 and refuses every other cost.
            assertThat(STORED_DIGEST).hasSize(BCRYPT_DIGEST_WIDTH);
            // The cost factor read straight off the digest's own third field. BCrypt encodes it in the
            // string itself - $<version>$<cost>$<salt+digest> - so this asserts the strength that was
            // actually applied, not merely the strength the encoder was configured with. V3__seed_data.sql
            // writes cost 10 and CardDemoUserDetailsService verifies against it; a digest at any other cost
            // fails every seeded sign-on, which is the mismatch this assertion catches.
            assertThat(STORED_DIGEST.split("\\$")[BCRYPT_COST_FIELD_INDEX])
                    .as("app/jcl/DUSRSECJ.jcl:35-44 seeds ten users whose digests must all carry cost %d",
                            CONTRACTUAL_STRENGTH)
                    .isEqualTo(String.valueOf(CONTRACTUAL_STRENGTH));
            assertThat(ENCODER.matches(PRESENTED_CREDENTIAL.toUpperCase(Locale.ROOT), STORED_DIGEST))
                    .isTrue();
            assertThat(ENCODER.matches(PRESENTED_CREDENTIAL, STORED_DIGEST))
                    .as("BCrypt is case sensitive, which is precisely why the fold is load bearing")
                    .isFalse();
        }
    }

    // 6. The success arm - app/cbl/COSGN00C.cbl:222-240

    /**
     * Identity established, and the two moves of {@code :226-227} carried as token claims. A real provider
     * mints the token and a real decoder reads it back, so the claim set is verified rather than trusted.
     * The {@code EXEC CICS XCTL} at {@code :230-240} survives only as the single-character routing hint.
     */
    @Nested
    @DisplayName("6. The success arm :222-240 - subject, role claim and the surviving routing hint")
    class SuccessArm {

        @Test
        @DisplayName("the token subject is the folded identifier that CDEMO-USER-ID held at :226")
        void theSubjectIsTheFoldedIdentifier() {
            final SignOnResponse response = signOnAs(FOLDED_USER_ID, UserType.USER);

            final Jwt decoded = tokenDecoder.decode(response.token());
            assertThat(decoded.getSubject()).isEqualTo(FOLDED_USER_ID);
            assertThat(tokenProvider.extractUserId(decoded)).isEqualTo(FOLDED_USER_ID);
        }

        @Test
        @DisplayName("an administrator gets the admin role claim, the :230 CDEMO-USRTYP-ADMIN branch")
        void anAdministratorGetsTheAdminRoleClaim() {
            when(cardDemoUserDetailsService.authenticate(PRESENTED_ADMIN_ID, PRESENTED_CREDENTIAL))
                    .thenReturn(principalNamed(FOLDED_ADMIN_ID));
            when(userSecurityRepository.findById(FOLDED_ADMIN_ID))
                    .thenReturn(Optional.of(storedUser(FOLDED_ADMIN_ID, UserType.ADMIN)));

            final SignOnResponse response =
                    service.signOn(signOnRequest(PRESENTED_ADMIN_ID, PRESENTED_CREDENTIAL));

            final Jwt decoded = tokenDecoder.decode(response.token());
            assertThat(decoded.getClaimAsString(JwtTokenProvider.ROLE_CLAIM_NAME))
                    .isEqualTo(JwtTokenProvider.ADMIN_AUTHORITY);
            assertThat(tokenProvider.extractUserType(decoded)).isEqualTo(UserType.ADMIN);
            assertThat(response.userType()).isEqualTo("A");
        }

        @Test
        @DisplayName("a standard user gets the user role claim, the :235 ELSE branch")
        void aStandardUserGetsTheUserRoleClaim() {
            final SignOnResponse response = signOnAs(FOLDED_USER_ID, UserType.USER);

            final Jwt decoded = tokenDecoder.decode(response.token());
            assertThat(decoded.getClaimAsString(JwtTokenProvider.ROLE_CLAIM_NAME))
                    .isEqualTo(JwtTokenProvider.USER_AUTHORITY);
            assertThat(tokenProvider.extractUserType(decoded)).isEqualTo(UserType.USER);
            assertThat(response.userType()).isEqualTo("U");
        }

        @Test
        @DisplayName("exactly one role claim is granted - never both, never a wildcard")
        void exactlyOneRoleClaimIsGranted() {
            final SignOnResponse response = signOnAs(FOLDED_USER_ID, UserType.USER);

            final Jwt decoded = tokenDecoder.decode(response.token());
            assertThat(decoded.getClaimAsString(JwtTokenProvider.ROLE_CLAIM_NAME))
                    .isNotEqualTo(JwtTokenProvider.ADMIN_AUTHORITY)
                    .isEqualTo(JwtTokenProvider.USER_AUTHORITY);
        }

        @Test
        @DisplayName("the routing hint is the single SEC-USR-TYPE character, not a role string")
        void theRoutingHintIsTheSingleUserClassCharacter() {
            assertThat(signOnAs(FOLDED_USER_ID, UserType.USER).userType()).hasSize(1).isEqualTo("U");
        }

        @Test
        @DisplayName("the user class is read from the stored row, never reconstructed from the authority")
        void theUserClassIsReadFromTheStoredRow() {
            // The principal is a standard user by name only; the row says ADMIN, and the row wins, which is
            // what keeps the byte-to-class mapping stated in exactly one place.
            when(cardDemoUserDetailsService.authenticate(PRESENTED_USER_ID, PRESENTED_CREDENTIAL))
                    .thenReturn(principalNamed(FOLDED_USER_ID));
            when(userSecurityRepository.findById(FOLDED_USER_ID))
                    .thenReturn(Optional.of(storedUser(FOLDED_USER_ID, UserType.ADMIN)));

            assertThat(service.signOn(signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL)).userType())
                    .isEqualTo("A");
        }

        @Test
        @DisplayName("the store is read exactly once, so no second round trip is made for the class byte")
        void theStoreIsReadExactlyOnce() {
            signOnAs(FOLDED_USER_ID, UserType.USER);

            verify(userSecurityRepository).findById(FOLDED_USER_ID);
        }

        /**
         * Signs on successfully as the given identity.
         *
         * @param foldedUserId the folded identifier the verifier reports; must not be {@code null}
         * @param userType     the class byte the stored row carries; must not be {@code null}
         * @return the successful response
         */
        private SignOnResponse signOnAs(final String foldedUserId, final UserType userType) {
            when(cardDemoUserDetailsService.authenticate(PRESENTED_USER_ID, PRESENTED_CREDENTIAL))
                    .thenReturn(principalNamed(foldedUserId));
            when(userSecurityRepository.findById(foldedUserId))
                    .thenReturn(Optional.of(storedUser(foldedUserId, userType)));
            return service.signOn(signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL));
        }
    }

    // 7. The WHEN 13 arm - app/cbl/COSGN00C.cbl:247-251

    /**
     * Two distinct routes reach the missing-record outcome, and both must produce it: the verifier reporting
     * no such row, and the class-byte read finding the row gone. Both carry the {@code :249} literal.
     */
    @Nested
    @DisplayName("7. The WHEN 13 arm :247-251 - two routes, one literal, distinguishable keys")
    class UserNotFoundArms {

        @Test
        @DisplayName("the verifier reporting no such row becomes the same credential refusal")
        void theVerifierReportingNoSuchRowBecomesAMissingRecordFailure() {
            final UsernameNotFoundException absent = new UsernameNotFoundException("no such row");
            when(cardDemoUserDetailsService.authenticate(PRESENTED_USER_ID, PRESENTED_CREDENTIAL))
                    .thenThrow(absent);

            // ONE outcome for every credential refusal, on an UNAUTHENTICATED path. A distinguishable type,
            // a distinct literal and an echoed record key each told a caller that the identifier it guessed
            // does or does not exist, which turns the sign-on endpoint into an identifier oracle. The
            // delegate performs the same BCrypt work on both routes, so the timing does not distinguish
            // them either; CredentialRefusalContractTest runs the two cases against EACH OTHER and is the
            // suite that would catch them drifting apart.
            //
            // Nothing is swallowed: the underlying throwable travels on the exception's cause, where an
            // operator reaches it through the log and no caller sees it.
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL)))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(MESSAGE_WRONG_PASSWD);
                        assertThat(failure.getFieldName()).isEqualTo(FIELD_PASSWD);
                        assertThat(failure.getFailureKind())
                                .isEqualTo(ValidationException.FailureKind.INVALID);
                        assertThat(failure.getCause()).isSameAs(absent);
                        assertThat(failure.getMessage())
                                .as("the refusal names no identifier, no dataset and no key")
                                .doesNotContain(PRESENTED_USER_ID)
                                .doesNotContain(USER_SECURITY_FILE);
                    });
        }

        @Test
        @DisplayName("a verified identity whose row is absent becomes the same failure under the folded key")
        void aVerifiedIdentityWhoseRowIsAbsentBecomesTheSameFailure() {
            when(cardDemoUserDetailsService.authenticate(PRESENTED_USER_ID, PRESENTED_CREDENTIAL))
                    .thenReturn(principalNamed(FOLDED_USER_ID));
            when(userSecurityRepository.findById(FOLDED_USER_ID)).thenReturn(Optional.empty());

            // The verifier has just read this row successfully, so an empty result here means it was deleted
            // between the two reads. Reported as the SAME refusal, and deliberately: a distinguishable
            // outcome would tell an unauthenticated caller that the identifier existed a moment ago.
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL)))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(MESSAGE_WRONG_PASSWD);
                        assertThat(failure.getFieldName()).isEqualTo(FIELD_PASSWD);
                        assertThat(failure.getMessage())
                                .as("not even the folded identifier is disclosed")
                                .doesNotContain(FOLDED_USER_ID);
                        assertThat(failure.getCause())
                                .as("no underlying throwable exists on this route, so none is invented")
                                .isNull();
                    });
        }

        @Test
        @DisplayName("the missing-record literal is byte exact and is not the wrong-password literal")
        void theMissingRecordLiteralIsByteExact() {
            assertThat(MESSAGE_USER_NOT_FOUND)
                    .isEqualTo("User not found. Try again ...")
                    .isNotEqualTo(MESSAGE_WRONG_PASSWD);
        }
    }

    // 8. The credential-rejected arm - app/cbl/COSGN00C.cbl:241-246

    /**
     * The row was found and the comparison at {@code :223} failed. Note that this arm sets no error flag,
     * unlike {@code :248} and {@code :253} which both do; the asymmetry is preserved rather than corrected,
     * and it is not observable through a REST response, so what survives is the literal and the field.
     */
    @Nested
    @DisplayName("8. The credential-rejected arm :241-246 - INVALID on the password field, cause preserved")
    class CredentialRejectedArm {

        @Test
        @DisplayName("a rejected credential becomes an invalid-field failure on the password")
        void aRejectedCredentialBecomesAnInvalidFieldFailure() {
            final BadCredentialsException rejected = new BadCredentialsException("refused");
            when(cardDemoUserDetailsService.authenticate(PRESENTED_USER_ID, PRESENTED_CREDENTIAL))
                    .thenThrow(rejected);

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL)))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(MESSAGE_WRONG_PASSWD);
                        assertThat(failure.getFieldName()).isEqualTo(FIELD_PASSWD);
                        assertThat(failure.getFailureKind())
                                .as("a value was supplied and it is wrong, which is INVALID and never BLANK")
                                .isEqualTo(ValidationException.FailureKind.INVALID);
                        assertThat(failure.getCause()).isSameAs(rejected);
                    });
        }

        @Test
        @DisplayName("a rejected credential never reaches the class-byte read or the token provider")
        void aRejectedCredentialNeverReachesTheStore() {
            when(cardDemoUserDetailsService.authenticate(PRESENTED_USER_ID, PRESENTED_CREDENTIAL))
                    .thenThrow(new BadCredentialsException("refused"));

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL)));

            verify(userSecurityRepository, never()).findById(anyString());
        }

        @Test
        @DisplayName("the wrong-password literal is byte exact, internal period included")
        void theWrongPasswordLiteralIsByteExact() {
            assertThat(MESSAGE_WRONG_PASSWD).isEqualTo("Wrong Password. Try again ...");
        }
    }

    // 9. The WHEN OTHER arm, recoverable half - app/cbl/COSGN00C.cbl:252-256

    /**
     * The store itself could not be interrogated. The source repaints the screen, so a further attempt
     * remains possible, and the target reports a recoverable I/O failure naming the logical file and the
     * verb. This program tests response codes rather than file statuses, so no status is invented.
     */
    @Nested
    @DisplayName("9. The WHEN OTHER arm :252-256, recoverable - USRSEC/READ, no invented file status")
    class StoreUnreadableArm {

        @Test
        @DisplayName("a verifier-reported read failure becomes a recoverable I/O failure")
        void aVerifierReportedReadFailureBecomesARecoverableIoFailure() {
            final InternalAuthenticationServiceException unreadable =
                    new InternalAuthenticationServiceException("store unavailable");
            when(cardDemoUserDetailsService.authenticate(PRESENTED_USER_ID, PRESENTED_CREDENTIAL))
                    .thenThrow(unreadable);

            assertThatExceptionOfType(FileAccessException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL)))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(MESSAGE_UNABLE_TO_VERIFY);
                        assertThat(failure.getLogicalFileName()).isEqualTo(USER_SECURITY_FILE);
                        assertThat(failure.getOperation()).isEqualTo(READ_OPERATION);
                        assertThat(failure.getExpandedStatus())
                                .as("EVALUATE WS-RESP-CD tests a response code, so there is no file status "
                                        + "to translate anywhere on this path")
                                .isEqualTo(NO_FILE_STATUS);
                        assertThat(failure.getCause()).isSameAs(unreadable);
                    });
        }

        @Test
        @DisplayName("a data-access failure raised by the verifier reaches the same arm")
        void aDataAccessFailureFromTheVerifierReachesTheSameArm() {
            final QueryTimeoutException timedOut = new QueryTimeoutException("timed out");
            when(cardDemoUserDetailsService.authenticate(PRESENTED_USER_ID, PRESENTED_CREDENTIAL))
                    .thenThrow(timedOut);

            assertThatExceptionOfType(FileAccessException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL)))
                    .satisfies(failure -> assertThat(failure.getCause()).isSameAs(timedOut));
        }

        @Test
        @DisplayName("a data-access failure on the class-byte read reaches the same arm")
        void aDataAccessFailureOnTheClassByteReadReachesTheSameArm() {
            final QueryTimeoutException timedOut = new QueryTimeoutException("timed out");
            when(cardDemoUserDetailsService.authenticate(PRESENTED_USER_ID, PRESENTED_CREDENTIAL))
                    .thenReturn(principalNamed(FOLDED_USER_ID));
            when(userSecurityRepository.findById(FOLDED_USER_ID)).thenThrow(timedOut);

            assertThatExceptionOfType(FileAccessException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL)))
                    .satisfies(failure -> {
                        assertThat(failure.getLogicalFileName()).isEqualTo(USER_SECURITY_FILE);
                        assertThat(failure.getCause()).isSameAs(timedOut);
                    });
        }

        @Test
        @DisplayName("an identifier the key column cannot hold is WHEN 13, not WHEN OTHER")
        void anUnrepresentableIdentifierIsTheNotFoundArm() {
            // The defect this test pins. A NUL inside the identifier is refused by the store itself with
            // SQLSTATE 22021, because sec_usr_id is CHAR(8) and PostgreSQL text types forbid 0x00. That is not
            // "the store could not be read": it means no row with this key can exist, so the read the source
            // would have performed is provably empty - DFHRESP(NOTFND), the WHEN 13 arm at :247-251.
            //
            // Answering WHEN OTHER told an UNAUTHENTICATED caller that the store was broken, which was false
            // and which one byte could provoke at will; worse, it made the outcome DISTINGUISHABLE from a
            // wrong password, which is exactly the disclosure this program's folded arms exist to prevent.
            final DataIntegrityViolationException unrepresentable = new DataIntegrityViolationException(
                    "invalid byte sequence",
                    new SQLException("invalid byte sequence for encoding \"UTF8\": 0x00", "22021"));
            when(cardDemoUserDetailsService.authenticate(anyString(), anyString()))
                    .thenThrow(unrepresentable);

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest("A\u0000B", PRESENTED_CREDENTIAL)))
                    .satisfies(failure -> {
                        assertThat(failure.getFailureKind())
                                .as("the same kind the wrong-password arm reports, so the two are "
                                        + "indistinguishable to a caller")
                                .isEqualTo(ValidationException.FailureKind.INVALID);
                        assertThat(failure.getFieldName()).isEqualTo(FIELD_PASSWD);
                        assertThat(failure.getMessage())
                                .as("the WHEN OTHER literal must NOT be reported for this condition")
                                .isNotEqualTo(MESSAGE_UNABLE_TO_VERIFY);
                        assertThat(failure.getCause()).isSameAs(unrepresentable);
                    });
        }

        @Test
        @DisplayName("a genuine connectivity failure still reaches WHEN OTHER, so 5xx is not lost")
        void aConnectivityFailureStillReachesTheStoreUnreadableArm() {
            final DataAccessResourceFailureException unreachable =
                    new DataAccessResourceFailureException("connection refused",
                            new SQLException("connection refused", "08006"));
            when(cardDemoUserDetailsService.authenticate(PRESENTED_USER_ID, PRESENTED_CREDENTIAL))
                    .thenThrow(unreachable);

            assertThatExceptionOfType(FileAccessException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL)))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(MESSAGE_UNABLE_TO_VERIFY);
                        assertThat(failure.getCause()).isSameAs(unreachable);
                    });
        }

        @Test
        @DisplayName("the unable-to-verify literal is byte exact and shared with the unrecoverable half")
        void theUnableToVerifyLiteralIsByteExact() {
            assertThat(MESSAGE_UNABLE_TO_VERIFY).isEqualTo("Unable to verify the User ...");
        }
    }

    // 10. The WHEN OTHER arm, unrecoverable half - app/cbl/COSGN00C.cbl:252-256 through SEND-PLAIN-TEXT

    /**
     * The same arm, reached when the condition is not a store-access failure at all. The program carries no
     * abend construct of its own - no {@code CALL 'CEE3ABD'}, no {@code EXEC CICS ABEND} and no abend
     * copybook - so no abend code and no abend reason is invented; the culprit is named from
     * {@code WS-PGMNAME} at {@code :36} and the message is the arm's own literal.
     */
    @Nested
    @DisplayName("10. The WHEN OTHER arm, unrecoverable - culprit named, code and reason left uninvented")
    class UnrecoverableArm {

        @Test
        @DisplayName("an unanticipated condition ends the operation, naming COSGN00C as the culprit")
        void anUnanticipatedConditionEndsTheOperation() {
            final IllegalStateException unexpected = new IllegalStateException("unanticipated");
            when(cardDemoUserDetailsService.authenticate(PRESENTED_USER_ID, PRESENTED_CREDENTIAL))
                    .thenThrow(unexpected);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL)))
                    .satisfies(failure -> {
                        assertThat(failure.getAbendCulprit()).isEqualTo(PROGRAM_NAME);
                        assertThat(failure.getAbendMessage()).isEqualTo(MESSAGE_UNABLE_TO_VERIFY);
                        assertThat(failure.getAbendCode())
                                .as("COSGN00C has no abend construct, so no code is invented")
                                .isNull();
                        assertThat(failure.getAbendReason())
                                .as("and no reason is invented either")
                                .isNull();
                        assertThat(failure.getCause()).isSameAs(unexpected);
                    });
        }

        @Test
        @DisplayName("a row carrying no user class byte ends the operation with no cause to preserve")
        void aRowCarryingNoUserClassByteEndsTheOperation() {
            // A row with a null class byte cannot be built through the entity's constructor, which refuses
            // one, so the condition is reproduced with a double: the class byte is absent, not merely wrong.
            final UserSecurity classless = mock(UserSecurity.class);
            when(cardDemoUserDetailsService.authenticate(PRESENTED_USER_ID, PRESENTED_CREDENTIAL))
                    .thenReturn(principalNamed(FOLDED_USER_ID));
            when(userSecurityRepository.findById(FOLDED_USER_ID)).thenReturn(Optional.of(classless));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL)))
                    .satisfies(failure -> {
                        assertThat(failure.getAbendCulprit()).isEqualTo(PROGRAM_NAME);
                        assertThat(failure.getAbendMessage()).isEqualTo(MESSAGE_UNABLE_TO_VERIFY);
                        assertThat(failure.getCause())
                                .as("nothing was thrown on this route, so no cause is fabricated")
                                .isNull();
                    });
        }

        @Test
        @DisplayName("an already-typed failure is rethrown untouched, never wrapped a second time")
        void anAlreadyTypedFailureIsRethrownUntouched() {
            final CardDemoException alreadyTyped = new ValidationException("already typed");
            when(cardDemoUserDetailsService.authenticate(PRESENTED_USER_ID, PRESENTED_CREDENTIAL))
                    .thenThrow(alreadyTyped);

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL)))
                    .satisfies(failure -> assertThat((CardDemoException) failure).isSameAs(alreadyTyped));
        }

        @Test
        @DisplayName("the unrecoverable render is a terminating one, so no repaint is offered")
        void theUnrecoverableRenderIsATerminatingOne() {
            when(cardDemoUserDetailsService.authenticate(PRESENTED_USER_ID, PRESENTED_CREDENTIAL))
                    .thenThrow(new IllegalStateException("unanticipated"));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL)));

            assertThat(capturedLines())
                    .as("SEND-PLAIN-TEXT :162-172 returns with no transaction identifier: the "
                            + "pseudo-conversation is over, and the line reflects that")
                    .anySatisfy(line -> assertThat(line).contains("ended without continuation"));
        }
    }

    // 11. The authentication-attempt counter - new capability, no source counterpart

    /**
     * The source has no instrumentation of any kind: not one {@code DISPLAY} and not one counter. The
     * increment lives in a {@code finally} block, so it must fire exactly once per call on every one of the
     * seven outcome paths, and never twice. A counter placed inside the {@code try} would silently miss
     * every failure.
     */
    @Nested
    @DisplayName("11. The attempt counter - exactly one increment per call on all seven paths")
    class AuthenticationAttemptCounter {

        @Test
        @DisplayName("both series are registered at construction, so neither is absent until first use")
        void bothSeriesAreRegisteredAtConstruction() {
            assertThat(meterRegistry.find(MetricsConfig.METRIC_AUTHENTICATION_ATTEMPTS).counters())
                    .as("the facade resolves both series eagerly, so a dashboard never shows a gap")
                    .hasSize(2);
            assertThat(attemptCount(MetricsConfig.AuthenticationOutcome.SUCCESS)).isZero();
            assertThat(attemptCount(MetricsConfig.AuthenticationOutcome.FAILURE)).isZero();
        }

        @Test
        @DisplayName("a successful sign-on counts one success and no failure")
        void aSuccessfulSignOnCountsOneSuccess() {
            when(cardDemoUserDetailsService.authenticate(PRESENTED_USER_ID, PRESENTED_CREDENTIAL))
                    .thenReturn(principalNamed(FOLDED_USER_ID));
            when(userSecurityRepository.findById(FOLDED_USER_ID))
                    .thenReturn(Optional.of(storedUser(FOLDED_USER_ID, UserType.USER)));

            service.signOn(signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL));

            assertExactlyOneAttemptCounted(MetricsConfig.AuthenticationOutcome.SUCCESS);
        }

        @Test
        @DisplayName("an absent request counts one failure")
        void anAbsentRequestCountsOneFailure() {
            assertThatExceptionOfType(ValidationException.class).isThrownBy(() -> service.signOn(null));

            assertExactlyOneAttemptCounted(MetricsConfig.AuthenticationOutcome.FAILURE);
        }

        @Test
        @DisplayName("a blank identifier counts one failure")
        void aBlankIdentifierCountsOneFailure() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(" ", PRESENTED_CREDENTIAL)));

            assertExactlyOneAttemptCounted(MetricsConfig.AuthenticationOutcome.FAILURE);
        }

        @Test
        @DisplayName("a blank password counts one failure")
        void aBlankPasswordCountsOneFailure() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(PRESENTED_USER_ID, " ")));

            assertExactlyOneAttemptCounted(MetricsConfig.AuthenticationOutcome.FAILURE);
        }

        @Test
        @DisplayName("a rejected credential counts one failure")
        void aRejectedCredentialCountsOneFailure() {
            when(cardDemoUserDetailsService.authenticate(PRESENTED_USER_ID, PRESENTED_CREDENTIAL))
                    .thenThrow(new BadCredentialsException("refused"));

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL)));

            assertExactlyOneAttemptCounted(MetricsConfig.AuthenticationOutcome.FAILURE);
        }

        @Test
        @DisplayName("an unknown identifier counts one failure")
        void aMissingRecordCountsOneFailure() {
            when(cardDemoUserDetailsService.authenticate(PRESENTED_USER_ID, PRESENTED_CREDENTIAL))
                    .thenThrow(new UsernameNotFoundException("no such row"));

            // The refusal is folded into the credential arm; what this test is about is that the counter in
            // the finally block fires exactly once on that path.
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL)));

            assertExactlyOneAttemptCounted(MetricsConfig.AuthenticationOutcome.FAILURE);
        }

        @Test
        @DisplayName("an unreadable store counts one failure")
        void anUnreadableStoreCountsOneFailure() {
            when(cardDemoUserDetailsService.authenticate(PRESENTED_USER_ID, PRESENTED_CREDENTIAL))
                    .thenThrow(new InternalAuthenticationServiceException("store unavailable"));

            assertThatExceptionOfType(FileAccessException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL)));

            assertExactlyOneAttemptCounted(MetricsConfig.AuthenticationOutcome.FAILURE);
        }

        @Test
        @DisplayName("an unrecoverable condition still counts one failure - the finally block reaches it")
        void anUnrecoverableConditionStillCountsOneFailure() {
            when(cardDemoUserDetailsService.authenticate(PRESENTED_USER_ID, PRESENTED_CREDENTIAL))
                    .thenThrow(new IllegalStateException("unanticipated"));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL)));

            assertExactlyOneAttemptCounted(MetricsConfig.AuthenticationOutcome.FAILURE);
        }

        @Test
        @DisplayName("two calls count two attempts, so the increment is per call and not per instance")
        void twoCallsCountTwoAttempts() {
            when(cardDemoUserDetailsService.authenticate(PRESENTED_USER_ID, PRESENTED_CREDENTIAL))
                    .thenThrow(new BadCredentialsException("refused"));

            for (int attempt = 0; attempt < 2; attempt++) {
                assertThatExceptionOfType(ValidationException.class).isThrownBy(
                        () -> service.signOn(signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL)));
            }

            assertThat(attemptCount(MetricsConfig.AuthenticationOutcome.FAILURE)).isEqualTo(2.0d);
            assertThat(attemptCount(MetricsConfig.AuthenticationOutcome.SUCCESS)).isZero();
        }

        @Test
        @DisplayName("no identity reaches the counter - the series carries the outcome tag and nothing else")
        void noIdentityReachesTheCounter() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(" ", PRESENTED_CREDENTIAL)));

            assertThat(meterRegistry.find(MetricsConfig.METRIC_AUTHENTICATION_ATTEMPTS).counters())
                    .allSatisfy(counter -> assertThat(counter.getId().getTags())
                            .as("an identifier in a tag would be unbounded cardinality and identity in "
                                    + "telemetry, breaching Rule 1 Clauses A and D at once")
                            .hasSize(1)
                            .allSatisfy(tag -> assertThat(tag.getKey())
                                    .isEqualTo(MetricsConfig.TAG_OUTCOME)));
        }
    }

    // 12. POPULATE-HEADER-INFO - app/cbl/COSGN00C.cbl:177-204

    /**
     * Six header values in source order at their declared widths, over an injected clock read exactly once.
     * {@code EXEC CICS ASSIGN APPLID} at {@code :198-200} and {@code ASSIGN SYSID} at {@code :202-204} have
     * no counterpart, and the request's own header metadata is not consumed.
     */
    @Nested
    @DisplayName("12. POPULATE-HEADER-INFO :177-204 - six values, one clock read, no region interrogation")
    class ScreenHeader {

        @Test
        @DisplayName("the header renders all six values in source order")
        void theHeaderRendersAllSixValuesInSourceOrder() {
            final String expected = String.format(Locale.ROOT,
                    "title01=%s title02=%s tranid=%s pgmname=%s date=%s time=%s",
                    SCREEN_TITLE_01, SCREEN_TITLE_02, TRANSACTION_NAME, PROGRAM_NAME,
                    EXPECTED_HEADER_DATE, EXPECTED_HEADER_TIME);

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(null, PRESENTED_CREDENTIAL)));

            assertThat(capturedLines()).anySatisfy(line ->
                    assertThat(line).contains("header [" + expected + "]"));
        }

        @Test
        @DisplayName("both titles are byte exact at their declared PIC X(40) width, padding included")
        void bothTitlesAreByteExactAtFortyCharacters() {
            assertThat(SCREEN_TITLE_01).hasSize(TITLE_WIDTH).contains("AWS Mainframe Modernization");
            assertThat(SCREEN_TITLE_02).hasSize(TITLE_WIDTH).contains("CardDemo");
        }

        @Test
        @DisplayName("the request's own header metadata is not consumed - the values are computed, not echoed")
        void theRequestHeaderMetadataIsNotConsumed() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(null, PRESENTED_CREDENTIAL)));

            assertThat(capturedLines()).noneSatisfy(line -> assertThat(line)
                    .as("the eleven request components include a transaction name, two titles, a date, a "
                            + "time, a program name, an application id and a system id, and this operation "
                            + "consumes none of them")
                    .contains("ZZZZ", "ignored-title-one", "CICSAPPL", "CICSSYS1"));
        }

        @Test
        @DisplayName("the clock is read exactly once, so the date and the time cannot straddle a boundary")
        void theClockIsReadExactlyOncePerHeader() {
            // Starting one second before midnight and advancing an hour per read: a second read would move
            // the date to the eleventh and the time into the small hours, and the two would disagree.
            final AdvancingClock advancing = new AdvancingClock(Instant.parse("2022-06-10T23:59:59Z"),
                    Duration.ofHours(1), ZoneOffset.UTC);
            final AuthenticationService advancingService = new AuthenticationService(
                    cardDemoUserDetailsService, userSecurityRepository, tokenProvider, metricsConfig,
                    advancing);

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> advancingService.signOn(signOnRequest(null, PRESENTED_CREDENTIAL)));

            assertThat(advancing.reads())
                    .as("MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA at :179 happens once per paragraph")
                    .isEqualTo(1);
            assertThat(capturedLines()).anySatisfy(line ->
                    assertThat(line).contains("date=06/10/22 time=23:59:59"));
        }

        @Test
        @DisplayName("the two-digit year is taken from the last two digits, per :188")
        void theTwoDigitYearIsTakenFromTheLastTwoDigits() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(null, PRESENTED_CREDENTIAL)));

            assertThat(capturedLines()).anySatisfy(line -> assertThat(line)
                    .contains("date=" + EXPECTED_HEADER_DATE)
                    .doesNotContain("date=06/10/2022"));
        }

        @Test
        @DisplayName("the header names the transaction and the program, not the request's claims about them")
        void theHeaderNamesTheTransactionAndTheProgram() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(null, PRESENTED_CREDENTIAL)));

            assertThat(capturedLines()).anySatisfy(line -> assertThat(line)
                    .contains("tranid=" + TRANSACTION_NAME)
                    .contains("pgmname=" + PROGRAM_NAME));
        }
    }

    // 13. Field widths and the alphanumeric MOVE - app/cbl/COSGN00C.cbl:149 and :166

    /**
     * Truncation on the right is observable and is reproduced; right-hand space padding is an artefact of the
     * field rather than of the value and is not. The two receiving widths are read off the production
     * declarations rather than copied, so a change to either is caught here.
     */
    @Nested
    @DisplayName("13. Field widths :149 and :166 - truncation reproduced, padding deliberately not")
    class MessageFieldWidths {

        @Test
        @DisplayName("the screen message field is the declared ERRMSGO PIC X(78)")
        void theScreenMessageFieldIsSeventyEight() throws ReflectiveOperationException {
            assertThat(declaredIntConstant("ERROR_MESSAGE_FIELD_LENGTH"))
                    .isEqualTo(ERROR_MESSAGE_FIELD_LENGTH);
        }

        @Test
        @DisplayName("the working message field is the declared WS-MESSAGE PIC X(80)")
        void theWorkingMessageFieldIsEighty() throws ReflectiveOperationException {
            assertThat(declaredIntConstant("WORK_MESSAGE_FIELD_LENGTH"))
                    .isEqualTo(WORK_MESSAGE_FIELD_LENGTH);
        }

        @Test
        @DisplayName("all five source literals fit inside both widths, so the narrowing never fires for them")
        void allFiveLiteralsFitInsideBothWidths() {
            assertThat(List.of(MESSAGE_USER_ID_REQUIRED, MESSAGE_PASSWD_REQUIRED, MESSAGE_WRONG_PASSWD,
                    MESSAGE_USER_NOT_FOUND, MESSAGE_UNABLE_TO_VERIFY))
                    .allSatisfy(literal -> assertThat(literal.length())
                            .isLessThanOrEqualTo(ERROR_MESSAGE_FIELD_LENGTH));
        }

        @Test
        @DisplayName("a rendered message is carried at its significant length, never space padded to 78")
        void aRenderedMessageIsNotSpacePadded() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(null, PRESENTED_CREDENTIAL)))
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .isEqualTo(MESSAGE_USER_ID_REQUIRED)
                            .hasSize(MESSAGE_USER_ID_REQUIRED.length()));
        }

        @Test
        @DisplayName("the MOVE primitive truncates on the right and pads on neither side")
        void theMovePrimitiveTruncatesOnTheRight() throws ReflectiveOperationException {
            final Method move = AuthenticationService.class.getDeclaredMethod("moveToAlphanumericField",
                    String.class, int.class);
            move.setAccessible(true);

            assertThat((String) move.invoke(null, new Object[] {null, ERROR_MESSAGE_FIELD_LENGTH}))
                    .as("an absent value is the empty field, and is never coerced into a null string")
                    .isEmpty();
            assertThat((String) move.invoke(null, new Object[] {"abc", 3}))
                    .isEqualTo("abc");
            assertThat((String) move.invoke(null, new Object[] {"abcdef", 3}))
                    .as("a move into a shorter field truncates on the right, which is observable")
                    .isEqualTo("abc");
            assertThat((String) move.invoke(null, new Object[] {"abc", 6}))
                    .as("a move into a longer field pads with spaces, which is an artefact of the field")
                    .isEqualTo("abc");
        }

        @Test
        @DisplayName("the SPACES OR LOW-VALUES predicate answers for all four presented states")
        void theBlankPredicateAnswersForAllFourStates() throws ReflectiveOperationException {
            final Method predicate = AuthenticationService.class.getDeclaredMethod("isSpacesOrLowValues",
                    String.class);
            predicate.setAccessible(true);

            assertThat((boolean) predicate.invoke(null, new Object[] {null})).isTrue();
            assertThat((boolean) predicate.invoke(null, "        ")).isTrue();
            assertThat((boolean) predicate.invoke(null, "\u0000\u0000\u0000")).isTrue();
            assertThat((boolean) predicate.invoke(null, "  \u0000\u0000"))
                    .as("a field mixing spaces with NUL bytes equals neither literal, so the WHEN OTHER arm "
                            + "at :128 lets it through")
                    .isFalse();
            assertThat((boolean) predicate.invoke(null, PRESENTED_USER_ID)).isFalse();
        }
    }

    // 14. Data protection - Rule 1 Clause D

    /**
     * No credential, no digest and no issued token may reach a log record, an exception message or a
     * diagnostic field. This is the sign-on service, so the clause bites hardest here.
     */
    @Nested
    @DisplayName("14. Data protection - no credential, digest or token in any message or log line")
    class DataProtection {

        @Test
        @DisplayName("a successful sign-on logs neither the credential, the digest nor the token")
        void aSuccessfulSignOnLogsNothingSensitive() {
            when(cardDemoUserDetailsService.authenticate(PRESENTED_USER_ID, PRESENTED_CREDENTIAL))
                    .thenReturn(principalNamed(FOLDED_USER_ID));
            when(userSecurityRepository.findById(FOLDED_USER_ID))
                    .thenReturn(Optional.of(storedUser(FOLDED_USER_ID, UserType.USER)));

            final SignOnResponse response =
                    service.signOn(signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL));

            assertThat(capturedLines()).allSatisfy(line -> assertThat(line)
                    .doesNotContain(PRESENTED_CREDENTIAL)
                    .doesNotContain(PRESENTED_CREDENTIAL.toUpperCase(Locale.ROOT))
                    .doesNotContain(STORED_DIGEST)
                    .doesNotContain(response.token()));
        }

        @Test
        @DisplayName("a rejected credential is reported by field NAME, never by value")
        void aRejectedCredentialIsReportedByFieldName() {
            when(cardDemoUserDetailsService.authenticate(PRESENTED_USER_ID, PRESENTED_CREDENTIAL))
                    .thenThrow(new BadCredentialsException("refused"));

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL)))
                    .satisfies(failure -> {
                        assertThat(failure.getFieldName()).isEqualTo(FIELD_PASSWD);
                        assertThat(failure.getMessage()).doesNotContain(PRESENTED_CREDENTIAL);
                    });
            assertThat(capturedLines()).allSatisfy(line ->
                    assertThat(line).doesNotContain(PRESENTED_CREDENTIAL));
        }

        @Test
        @DisplayName("no failure message on any of the seven paths quotes a presented value")
        void noFailureMessageQuotesAPresentedValue() {
            when(cardDemoUserDetailsService.authenticate(PRESENTED_USER_ID, PRESENTED_CREDENTIAL))
                    .thenThrow(new IllegalStateException("unanticipated"));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL)))
                    .satisfies(failure -> assertThat(failure.getAbendMessage())
                            .doesNotContain(PRESENTED_CREDENTIAL)
                            .doesNotContain(PRESENTED_USER_ID));
        }

        @Test
        @DisplayName("the stored digest never appears in the response")
        void theStoredDigestNeverAppearsInTheResponse() {
            when(cardDemoUserDetailsService.authenticate(PRESENTED_USER_ID, PRESENTED_CREDENTIAL))
                    .thenReturn(principalNamed(FOLDED_USER_ID));
            when(userSecurityRepository.findById(FOLDED_USER_ID))
                    .thenReturn(Optional.of(storedUser(FOLDED_USER_ID, UserType.USER)));

            final SignOnResponse response =
                    service.signOn(signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL));

            assertThat(response.token()).doesNotContain(STORED_DIGEST);
            assertThat(response.userId()).isEqualTo(FOLDED_USER_ID);
            assertThat(response.userType()).hasSize(1);
        }
    }

    // 15. Statelessness - Transformation Rule 7

    /**
     * {@code RETURN TRANSID ... COMMAREA} becomes stateless REST plus token claims, so nothing survives a
     * call. The pseudo-conversational enter-versus-re-enter flag has no counterpart at all.
     */
    @Nested
    @DisplayName("15. Statelessness - nothing survives a call, and no static state exists")
    class Statelessness {

        @Test
        @DisplayName("a failed call leaves no residue that changes the next one")
        void aFailedCallLeavesNoResidue() {
            when(cardDemoUserDetailsService.authenticate(PRESENTED_USER_ID, PRESENTED_CREDENTIAL))
                    .thenReturn(principalNamed(FOLDED_USER_ID));
            when(userSecurityRepository.findById(FOLDED_USER_ID))
                    .thenReturn(Optional.of(storedUser(FOLDED_USER_ID, UserType.USER)));

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.signOn(signOnRequest(null, PRESENTED_CREDENTIAL)));

            assertThat(service.signOn(signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL)).userId())
                    .as("the second call must behave as though the first never happened")
                    .isEqualTo(FOLDED_USER_ID);
        }

        @Test
        @DisplayName("every static field is final, so no call can mutate class-level state")
        void everyStaticFieldIsFinal() {
            final List<Field> mutableStatics = Arrays.stream(AuthenticationService.class.getDeclaredFields())
                    .filter(field -> Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !Modifier.isFinal(field.getModifiers()))
                    .toList();
            assertThat(mutableStatics)
                    .as("the source's WORKING-STORAGE became method-local state, not static state")
                    .isEmpty();
        }

        @Test
        @DisplayName("two independent identities can sign on through one instance")
        void twoIndependentIdentitiesCanSignOnThroughOneInstance() {
            when(cardDemoUserDetailsService.authenticate(PRESENTED_USER_ID, PRESENTED_CREDENTIAL))
                    .thenReturn(principalNamed(FOLDED_USER_ID));
            when(userSecurityRepository.findById(FOLDED_USER_ID))
                    .thenReturn(Optional.of(storedUser(FOLDED_USER_ID, UserType.USER)));
            when(cardDemoUserDetailsService.authenticate(PRESENTED_ADMIN_ID, PRESENTED_CREDENTIAL))
                    .thenReturn(principalNamed(FOLDED_ADMIN_ID));
            when(userSecurityRepository.findById(FOLDED_ADMIN_ID))
                    .thenReturn(Optional.of(storedUser(FOLDED_ADMIN_ID, UserType.ADMIN)));

            assertThat(service.signOn(signOnRequest(PRESENTED_USER_ID, PRESENTED_CREDENTIAL)).userType())
                    .isEqualTo("U");
            assertThat(service.signOn(signOnRequest(PRESENTED_ADMIN_ID, PRESENTED_CREDENTIAL)).userType())
                    .isEqualTo("A");
        }
    }

    // 16. Locale.ROOT, least privilege and the PIC X(08) hostile boundary

    /**
     * Four concerns the preceding groups establish only indirectly, each of which fails silently if it is
     * wrong.
     *
     * <ul>
     *   <li><b>The fold is invariant.</b> {@code FUNCTION UPPER-CASE} at
     *       {@code app/cbl/COSGN00C.cbl}:132-136 has no locale to be sensitive to; Java's default
     *       {@code toUpperCase()} overload does. Under a Turkish default the dotted {@code i} folds to
     *       {@code U+0130}, which corrupts both the record key at {@code :215} and the credential operand at
     *       {@code :223} - so the entire authentication decision turns on the invariant locale being named
     *       explicitly. Group 5 proves the fold happens; this group proves <em>which</em> fold.</li>
     *   <li><b>The authority is least privilege.</b> {@code IF CDEMO-USRTYP-ADMIN} at {@code :230} routed an
     *       administrator to {@code COADM01C} and everyone else to {@code COMEN01C}. Routing is URL-based
     *       now, so that branch decides authorisation instead, and the administrative namespace
     *       {@code /api/admin/**} is guarded by a single authority. Granting it to a standard user would be
     *       privilege escalation that no functional test notices.</li>
     *   <li><b>Nothing is retained server side.</b> Transformation Rule 7 replaces
     *       {@code RETURN TRANSID ... COMMAREA} with stateless request handling, so a sign-on must leave the
     *       server-side security context untouched and hand the identity back as a bearer token instead.</li>
     *   <li><b>The declared widths are boundaries, not suggestions.</b> Both credential items are
     *       {@code PIC X(08)} - {@code app/cpy/CSUSR01Y.cpy}:18 and :21, with {@code KEYS(8,0)} at
     *       {@code app/jcl/DUSRSECJ.jcl}:65 - and silently narrowing an over-long input to eight characters
     *       would admit a credential the system of record refuses.</li>
     * </ul>
     */
    @Nested
    @DisplayName("16. Locale.ROOT, least privilege and the PIC X(08) hostile boundary")
    class LocaleLeastPrivilegeAndWidthBoundary {

        @AfterEach
        void clearAnySecurityContext() {
            // Restores the thread-local the statelessness assertion reads, so no residue can reach another
            // test through it. The assertion itself never populates it; this only guarantees that.
            SecurityContextHolder.clearContext();
        }

        @Test
        @DisplayName("the identifier fold is Locale.ROOT, so a dotted i cannot move the record key")
        void theIdentifierFoldIsLocaleRoot() {
            // The hazard is real before it is excluded: under Turkish rules this identifier folds to a
            // DIFFERENT key, so if the production fold were locale sensitive the lookup below would be made
            // under "ADMIN\u0130001" and STRICT_STUBS would reject the call outright.
            assertThat(PRESENTED_ADMIN_ID.toUpperCase(TURKISH))
                    .as("the dotted i is what makes app/cbl/COSGN00C.cbl:132-134 locale sensitive in Java")
                    .isNotEqualTo(FOLDED_ADMIN_ID);
            when(userSecurityRepository.findById(FOLDED_ADMIN_ID))
                    .thenReturn(Optional.of(storedUser(FOLDED_ADMIN_ID, UserType.ADMIN)));

            final SignOnResponse response =
                    serviceOverTheRealVerifier().signOn(signOnRequest(PRESENTED_ADMIN_ID, PRESENTED_CREDENTIAL));

            assertThat(response.userId())
                    .as("only the invariant fold resolves the row that KEYS(8,0) actually holds")
                    .isEqualTo(FOLDED_ADMIN_ID);
            // Every key the store is asked for, captured rather than counted. The success path reads twice -
            // once inside the verifier and once for the class byte - and asserting the SET of keys rather
            // than the number of reads is what makes this a statement about the fold instead of a statement
            // about call counts, which group 6 already owns.
            final ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
            verify(userSecurityRepository, atLeastOnce()).findById(keys.capture());
            assertThat(keys.getAllValues())
                    .as("no read may be made under the Turkish fold %s",
                            PRESENTED_ADMIN_ID.toUpperCase(TURKISH))
                    .isNotEmpty()
                    .containsOnly(FOLDED_ADMIN_ID);
        }

        @Test
        @DisplayName("the credential fold is Locale.ROOT, so a dotted i cannot break the verification")
        void theCredentialFoldIsLocaleRoot() {
            // The negative control first: the Turkish fold of this credential does NOT match the stored
            // digest, so a locale-sensitive fold at :135-136 would refuse a correct credential outright.
            assertThat(ENCODER.matches(DOTTED_I_CREDENTIAL.toUpperCase(TURKISH), DOTTED_I_DIGEST))
                    .as("a Turkish fold produces U+0130 and BCrypt is byte sensitive, so it cannot match")
                    .isFalse();
            when(userSecurityRepository.findById(FOLDED_USER_ID)).thenReturn(Optional.of(
                    new UserSecurity(FOLDED_USER_ID, FIRST_NAME, LAST_NAME, DOTTED_I_DIGEST,
                            UserType.USER)));

            final SignOnResponse response =
                    serviceOverTheRealVerifier().signOn(signOnRequest(PRESENTED_USER_ID, DOTTED_I_CREDENTIAL));

            assertThat(response.token())
                    .as("the invariant fold at :135-136 is the only one that verifies this credential")
                    .isNotBlank();
        }

        @Test
        @DisplayName("only an administrator carries the authority guarding /api/admin/**")
        void onlyAnAdministratorCarriesTheAdminNamespaceAuthority() {
            final Jwt decoded = tokenDecoder.decode(signOnThroughTheDouble(FOLDED_ADMIN_ID,
                    PRESENTED_ADMIN_ID, UserType.ADMIN).token());

            assertThat(decoded.getClaimAsString(JwtTokenProvider.ROLE_CLAIM_NAME))
                    .as("com.cardemo.config.SecurityConfig guards /api/admin/** with exactly this authority")
                    .isEqualTo(JwtTokenProvider.ADMIN_AUTHORITY)
                    .isEqualTo(tokenProvider.authorityFor(UserType.ADMIN));
        }

        @Test
        @DisplayName("a standard user is refused that authority, so /api/admin/** stays closed to them")
        void aStandardUserIsRefusedTheAdminNamespaceAuthority() {
            final Jwt decoded = tokenDecoder.decode(signOnThroughTheDouble(FOLDED_USER_ID,
                    PRESENTED_USER_ID, UserType.USER).token());

            assertThat(decoded.getClaimAsString(JwtTokenProvider.ROLE_CLAIM_NAME))
                    .as("the :235 ELSE branch grants the standard authority and never the administrative one")
                    .isEqualTo(JwtTokenProvider.USER_AUTHORITY)
                    .isEqualTo(tokenProvider.authorityFor(UserType.USER))
                    .isNotEqualTo(JwtTokenProvider.ADMIN_AUTHORITY);
        }

        @Test
        @DisplayName("a successful sign-on populates no server-side security context - STATELESS")
        void aSuccessfulSignOnPopulatesNoServerSideSecurityContext() {
            assertThat(SecurityContextHolder.getContext().getAuthentication())
                    .as("nothing is authenticated before the call, so the check below measures this call")
                    .isNull();

            final SignOnResponse response =
                    signOnThroughTheDouble(FOLDED_USER_ID, PRESENTED_USER_ID, UserType.USER);

            assertThat(SecurityContextHolder.getContext().getAuthentication())
                    .as("SessionCreationPolicy.STATELESS: identity leaves as a bearer token, not as "
                            + "server-side state, so RETURN TRANSID ... COMMAREA has no residue here")
                    .isNull();
            assertThat(response.token())
                    .as("the token is the whole of the identity handed back")
                    .isNotBlank();
        }

        @Test
        @DisplayName("an identifier wider than PIC X(08) is neither narrowed nor accepted")
        void anIdentifierWiderThanTheDeclaredWidthIsNeitherNarrowedNorAccepted() {
            // findById is deliberately left unstubbed: the folded nine-character key matches no row, which
            // is the WHEN 13 arm at :247-251, and stubbing it would assert a row that KEYS(8,0) cannot hold.
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> serviceOverTheRealVerifier().signOn(
                            signOnRequest(OVERLONG_USER_ID, PRESENTED_CREDENTIAL)))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo(MESSAGE_WRONG_PASSWD);
                        assertThat(failure.getFieldName()).isEqualTo(FIELD_PASSWD);
                    });

            verify(userSecurityRepository).findById(OVERLONG_USER_ID.toUpperCase(Locale.ROOT));
        }

        @Test
        @DisplayName("a credential wider than PIC X(08) is not narrowed to eight and so cannot verify")
        void aCredentialWiderThanTheDeclaredWidthIsNotNarrowedToEight() {
            // The first eight characters of OVERLONG_CREDENTIAL are exactly the correct credential. Were the
            // presented value narrowed to the declared width before verification, this would succeed - which
            // is why this test is the guard against silent narrowing rather than a restatement of group 8.
            assertThat(OVERLONG_CREDENTIAL)
                    .startsWith(PRESENTED_CREDENTIAL)
                    .hasSize(PRESENTED_CREDENTIAL.length() + 1);
            when(userSecurityRepository.findById(FOLDED_USER_ID))
                    .thenReturn(Optional.of(storedUser(FOLDED_USER_ID, UserType.USER)));

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> serviceOverTheRealVerifier().signOn(
                            signOnRequest(PRESENTED_USER_ID, OVERLONG_CREDENTIAL)))
                    .withMessage(MESSAGE_WRONG_PASSWD);
        }

        @Test
        @DisplayName("an identifier carrying control characters is not unset, is refused, and is not echoed")
        void anIdentifierCarryingControlCharactersIsRefusedAndNotEchoed() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> serviceOverTheRealVerifier().signOn(
                            signOnRequest(CONTROL_CHARACTER_USER_ID, PRESENTED_CREDENTIAL)))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage())
                                .as("it is neither SPACES nor LOW-VALUES, so :128-129 lets it through to "
                                        + "the store and it fails as a credential refusal, not as a blank")
                                .isEqualTo(MESSAGE_WRONG_PASSWD)
                                .doesNotContain(CONTROL_CHARACTER_USER_ID)
                                .doesNotContain("\u0007");
                    });

            verify(userSecurityRepository).findById(CONTROL_CHARACTER_USER_ID.toUpperCase(Locale.ROOT));
            assertThat(capturedLines())
                    .as("untrusted input must not be echoed into telemetry, Rule 1 Clause A")
                    .noneMatch(line -> line.contains(CONTROL_CHARACTER_USER_ID) || line.contains("\u0007"));
        }

        /**
         * Assembles the bean over the <em>real</em> credential verifier, so that a real fold and a real
         * strength-10 digest are exercised rather than described.
         *
         * <p>Built on demand rather than in a {@code @BeforeEach}: constructing the verifier performs one
         * BCrypt encode at cost 10 to build its constant-work digest, and three of the tests below - the two
         * authority concerns and the statelessness concern - do not depend on a real fold at all. Charging
         * them for that encode would be the obvious inefficiency Rule 1 Clause A asks to be avoided.
         *
         * @return a service whose credential verification is genuine
         */
        private AuthenticationService serviceOverTheRealVerifier() {
            final CardDemoUserDetailsService realVerifier =
                    new CardDemoUserDetailsService(userSecurityRepository, ENCODER);
            return new AuthenticationService(realVerifier, userSecurityRepository, tokenProvider,
                    metricsConfig, FIXED_CLOCK);
        }

        /**
         * Signs on successfully through the mocked verifier, which is enough for the claim and statelessness
         * concerns because neither depends on a real fold.
         *
         * @param foldedUserId    the folded identifier the verifier reports; must not be {@code null}
         * @param presentedUserId the identifier as presented; must not be {@code null}
         * @param userType        the class byte the stored row carries; must not be {@code null}
         * @return the successful response
         */
        private SignOnResponse signOnThroughTheDouble(final String foldedUserId,
                final String presentedUserId, final UserType userType) {
            when(cardDemoUserDetailsService.authenticate(presentedUserId, PRESENTED_CREDENTIAL))
                    .thenReturn(principalNamed(foldedUserId));
            when(userSecurityRepository.findById(foldedUserId))
                    .thenReturn(Optional.of(storedUser(foldedUserId, userType)));
            return service.signOn(signOnRequest(presentedUserId, PRESENTED_CREDENTIAL));
        }
    }

    /**
     * The authenticated principal the credential verifier returns: a name, no authority and no credential.
     *
     * <p>The real verifier calls {@code eraseCredentials()} on the principal before returning it, so an
     * authenticated principal genuinely carries no password by the time this service sees one. Reproducing
     * that here means the suite never needs a credential-shaped value to describe a signed-on user, which is
     * Rule 1 Clause D applied to the test tier rather than only to production.
     */
    private static final class NamedPrincipal implements UserDetails {

        /** {@link UserDetails} extends {@link java.io.Serializable}, so the contract asks for one. */
        private static final long serialVersionUID = 1L;

        /** The folded identifier this principal reports. */
        private final String username;

        /**
         * Creates a principal.
         *
         * @param username the folded identifier; must not be {@code null}
         */
        NamedPrincipal(final String username) {
            this.username = username;
        }

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return List.of();
        }

        @Override
        public String getPassword() {
            return null;
        }

        @Override
        public String getUsername() {
            return this.username;
        }

        @Override
        public boolean isAccountNonExpired() {
            return true;
        }

        @Override
        public boolean isAccountNonLocked() {
            return true;
        }

        @Override
        public boolean isCredentialsNonExpired() {
            return true;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }

    /**
     * A clock that advances on every read, used to prove that the header paragraph reads the time exactly
     * once. {@code app/cbl/COSGN00C.cbl}:179 performs a single
     * {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA}, so the date and the time on one header can
     * never straddle a boundary; a second read would let them.
     */
    private static final class AdvancingClock extends Clock {

        /** The zone this instance reports. */
        private final ZoneId zone;

        /** How far each read advances the next. */
        private final Duration step;

        /** The instant the next read will return. */
        private Instant next;

        /** How many reads have occurred. */
        private int reads;

        /**
         * Creates an advancing clock.
         *
         * @param start the instant the first read returns; must not be {@code null}
         * @param step  how far each read advances the next; must not be {@code null}
         * @param zone  the zone to report; must not be {@code null}
         */
        AdvancingClock(final Instant start, final Duration step, final ZoneId zone) {
            this.next = start;
            this.step = step;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return this.zone;
        }

        @Override
        public Clock withZone(final ZoneId target) {
            return new AdvancingClock(this.next, this.step, target);
        }

        @Override
        public Instant instant() {
            this.reads++;
            final Instant current = this.next;
            this.next = this.next.plus(this.step);
            return current;
        }

        /**
         * Returns how many times the clock was read.
         *
         * @return the read count
         */
        int reads() {
            return this.reads;
        }
    }
}
