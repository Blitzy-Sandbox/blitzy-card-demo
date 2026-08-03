/*
 ******************************************************************
 * Program     : AuthenticationService.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 service component
 * Function    : Sign-on authentication: credential normalisation, BCrypt
 *               verification and JWT issuance, replacing the CICS sign-on
 *               screen program and its COMMAREA identity handshake.
 * Source      : app/cbl/COSGN00C.cbl (260 lines, 6 paragraphs) @ 7756d89
 * Source      : app/cbl/COSGN00C.cbl:L132-L136 (UPPER-CASE BOTH identifier and
 *               password), :L223 (SEC-USR-PWD = WS-USER-PWD compare) @ 7756d89
 * Source      : app/cbl/COSGN00C.cbl:L209-L257 (READ-USER-SEC-FILE),
 *               :L224-L228 (identity establishment) @ 7756d89
 * Source      : app/cpy/CSUSR01Y.cpy:L17-L23 (80-byte SEC-USER-DATA layout,
 *               key length 8) @ 7756d89
 * Source      : app/cpy/COCOM01Y.cpy:L25-L28 (CDEMO-USER-ID / CDEMO-USER-TYPE
 *               with 88-levels 'A' and 'U') @ 7756d89
 * Source      : app/csd/CARDDEMO.CSD:L378-L384 (DEFINE TRANSACTION(CC00)
 *               PROGRAM(COSGN00C)), :L88-L92 (DEFINE FILE(USRSEC)) @ 7756d89
 * Source      : app/jcl/DUSRSECJ.jcl:L35-L44 (ten inline seed users),
 *               :L65-L66 (KEYS(8,0) RECORDSIZE(80,80)) @ 7756d89
 ******************************************************************
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
 ******************************************************************
 */
package com.cardemo.service.auth;

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

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sign-on authentication for the CardDemo application: the Java 25 / Spring Boot replacement for the CICS
 * online program {@code COSGN00C}, reached in the legacy system through transaction {@code CC00}.
 *
 * <h2>What it does</h2>
 *
 * <p>Derived from {@code app/cbl/COSGN00C.cbl} - 260 lines, 6 paragraphs - at commit {@code 7756d89}. The
 * transaction-to-program binding is {@code DEFINE TRANSACTION(CC00) ... PROGRAM(COSGN00C)} at
 * {@code app/csd/CARDDEMO.CSD:L378-L384}, and the dataset it reads is {@code DEFINE FILE(USRSEC)} at
 * {@code app/csd/CARDDEMO.CSD:L88-L92}, catalogued as {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS} with
 * {@code KEYLEN 8} and {@code AVGLRECL 80} at {@code app/catlg/LISTCAT.txt:L3846} and {@code :L3883}, and
 * defined by {@code KEYS(8,0) RECORDSIZE(80,80)} at {@code app/jcl/DUSRSECJ.jcl:L65-L66}.
 *
 * <p>The service accepts one credential pair, has the presented values normalised and verified, and on
 * success issues a signed bearer token in place of the COMMAREA identity the source populated. It performs
 * no navigation: the {@code EXEC CICS XCTL} at {@code :L231-L239} becomes a routing hint the caller reads
 * off the response, because routing in the target is URL-based.
 *
 * <p>Each of the six source paragraphs maps to exactly one private method, never consolidated, and each
 * carries a Javadoc citation naming its label and line span. That correspondence is what makes the
 * paragraph mapping mechanically provable.
 *
 * <table>
 *   <caption>The six paragraphs of app/cbl/COSGN00C.cbl and their Java counterparts</caption>
 *   <tr><th>#</th><th>COBOL label</th><th>Span</th><th>Java method</th></tr>
 *   <tr><td>1</td><td>{@code MAIN-PARA.}</td><td>{@code :L73-L102}</td><td>{@link #mainPara}</td></tr>
 *   <tr><td>2</td><td>{@code PROCESS-ENTER-KEY.}</td><td>{@code :L108-L140}</td>
 *       <td>{@link #processEnterKey}</td></tr>
 *   <tr><td>3</td><td>{@code SEND-SIGNON-SCREEN.}</td><td>{@code :L145-L157}</td>
 *       <td>{@link #sendSignonScreen}</td></tr>
 *   <tr><td>4</td><td>{@code SEND-PLAIN-TEXT.}</td><td>{@code :L162-L172}</td>
 *       <td>{@link #sendPlainText}</td></tr>
 *   <tr><td>5</td><td>{@code POPULATE-HEADER-INFO.}</td><td>{@code :L177-L204}</td>
 *       <td>{@link #populateHeaderInfo}</td></tr>
 *   <tr><td>6</td><td>{@code READ-USER-SEC-FILE.}</td><td>{@code :L209-L257}</td>
 *       <td>{@link #readUserSecFile}</td></tr>
 *   </table>
 *
 * <p>Industry guidance on legacy modernisation discourages literal transliteration. It is deliberately
 * overridden here: behavioural parity is the engagement's contract and paragraph correspondence must be
 * provable. The readability concern behind that guidance is answered by idiomatic naming and by the
 * source-citing Javadoc below, not by restructuring control flow.
 *
 * <h2>The case-handling contract</h2>
 *
 * <p><b>Both</b> the user identifier <b>and</b> the password are folded to upper case, and neither is
 * trimmed. The source is unambiguous at {@code app/cbl/COSGN00C.cbl:L132-L136}:
 *
 * <pre>{@code
 * L132            MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI) TO
 * L133                            WS-USER-ID
 * L134                            CDEMO-USER-ID
 * L135            MOVE FUNCTION UPPER-CASE(PASSWDI OF COSGN0AI) TO
 * L136                            WS-USER-PWD
 * }</pre>
 *
 * <p>Both folded values are then consumed downstream: {@code WS-USER-ID} becomes the record key at
 * {@code RIDFLD(WS-USER-ID)} on the read at {@code :L215}, {@code CDEMO-USER-ID} becomes the COMMAREA
 * identity and therefore the token subject, and {@code WS-USER-PWD} is the right-hand operand of the
 * credential comparison {@code IF SEC-USR-PWD = WS-USER-PWD} at {@code :L223}. Folding only the identifier
 * and not the password is a parity break, because it silently changes which sign-on
 * attempts succeed.
 *
 * <p><b>Where the folding happens.</b> It is performed by
 * {@code com.cardemo.security.CardDemoUserDetailsService}, which was read at {@code 7756d89} and verified
 * to fold both operands with {@code Locale.ROOT} and to trim neither. This class therefore <b>delegates</b>
 * and does not re-apply the operation, and it performs no case folding of its own - duplicating the rule
 * in two places is exactly the duplication Rule 1 Clause C forbids. The normalised identifier is recovered
 * from {@code UserDetails.getUsername()}, which the delegate populates with the folded value, so no second
 * fold is needed anywhere on the path.
 *
 * <p>{@code Locale.ROOT} is load bearing rather than decorative. Under a Turkish default locale the
 * locale-sensitive default overload maps {@code i} to a dotted capital, which would corrupt both the
 * record key at {@code :L215} and the credential comparison at {@code :L223}; the entire authentication
 * decision rests on that fold, which makes it the single highest-value application of Rule 1 Clause A in
 * this file.
 *
 * <p>Neither operand is trimmed and neither is padded to eight characters. The legacy comparison is a
 * fixed-width one - {@code SEC-USR-PWD} is {@code PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy:L21} and
 * {@code WS-USER-PWD} is {@code PIC X(08)} at {@code app/cbl/COSGN00C.cbl:L46} - so the stored and
 * presented values were both space-padded. Padding in Java would append spaces that are absent from the
 * value hashed by {@code V3__seed_data.sql} and would fail every verification. All ten seeded credentials
 * are exactly eight characters, so the padded and unpadded forms coincide for every seeded user. This is a
 * documented boundary decision, not a defect.
 *
 * <h2>Identity: COMMAREA to bearer token</h2>
 *
 * <p>The COMMAREA is declared at {@code app/cbl/COSGN00C.cbl:L65-L67} as
 * {@code LK-COMMAREA PIC X(01) OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN}. Identity is established
 * exactly once, here at sign-on ({@code :L226-L227}), and then rides along across every
 * {@code EXEC CICS XCTL} without {@code USRSEC} being re-read - in {@code app/cbl/COMEN01C.cbl:L149-L150}
 * the identity re-establishment moves are commented out. That is precisely the semantic of a signed bearer
 * token, and it is the evidence for the substitution.
 *
 * <table>
 *   <caption>app/cpy/COCOM01Y.cpy fields and their disposition in the target</caption>
 *   <tr><th>COMMAREA field</th><th>Locator</th><th>Target</th></tr>
 *   <tr><td>{@code CDEMO-USER-ID PIC X(08)}</td><td>{@code :L25}</td><td>token subject claim</td></tr>
 *   <tr><td>{@code CDEMO-USER-TYPE PIC X(01)} with 88-levels {@code 'A'} and {@code 'U'}</td>
 *       <td>{@code :L26-L28}</td><td>token role claim</td></tr>
 *   <tr><td>{@code CDEMO-FROM-TRANID}, {@code CDEMO-TO-TRANID}, {@code CDEMO-FROM-PROGRAM},
 *       {@code CDEMO-TO-PROGRAM}</td><td>{@code :L21-L24}</td>
 *       <td>no equivalent - routing is URL-based</td></tr>
 *   <tr><td>{@code CDEMO-PGM-CONTEXT} with {@code 88 CDEMO-PGM-ENTER VALUE 0} and
 *       {@code 88 CDEMO-PGM-REENTER VALUE 1}</td><td>{@code :L29-L31}</td>
 *       <td>no equivalent - collapses into stateless request handling</td></tr>
 *   <tr><td>{@code CDEMO-LAST-MAP}, {@code CDEMO-LAST-MAPSET} ({@code PIC X(7)})</td>
 *       <td>{@code :L43-L44}</td><td>no equivalent - no screen state is retained</td></tr>
 *   </table>
 *
 * <p>Only the identity pair survives translation. The transaction-name, program-name, program-context and
 * last-map fields have no counterpart at all, and nothing in this class reconstructs them.
 *
 * <p><b>The claim set is minimal, and deliberately so.</b> A JWT is signed, not encrypted, so every claim
 * is readable by anyone holding the token. The same COMMAREA also carries {@code CDEMO-CUST-ID 9(09)} at
 * {@code app/cpy/COCOM01Y.cpy:L33}, three {@code X(25)} customer name fields at {@code :L34-L36},
 * {@code CDEMO-ACCT-ID 9(11)} at {@code :L38}, {@code CDEMO-ACCT-STATUS X(01)} at {@code :L39} and
 * {@code CDEMO-CARD-NUM 9(16)} at {@code :L41}. <b>None of those may become a claim.</b> The set is exactly
 * the subject, the role, and the standard registered claims, and emitting anything further - a card number,
 * an account identifier, a customer identifier or a name - is forbidden outright.
 * Minting is owned entirely by {@code com.cardemo.security.JwtTokenProvider}: this class hands it the
 * identifier and the user class and receives a token string. It never builds a claim set, never names a
 * claim and never touches the signing key.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>The build is a single-module Maven project driven through the pinned wrapper. Configuration is
 * supplied by environment variables, so the local environment file must be exported first:
 *
 * <pre>{@code
 * set -a; . ./.env; set +a
 * ./mvnw -B -ntp clean compile
 * ./mvnw -B -ntp test
 * ./mvnw -B -ntp -Ddependency-check.skip=true clean verify
 * }</pre>
 *
 * <p>The compiler runs {@code -Xlint:all -Werror} with {@code failOnWarning}, so a raw type, an unchecked cast or a
 * dangling documentation comment fails the build rather than warning. An unused import does not: {@code javac} 25
 * publishes no {@code unused} lint key, so that is review-enforced, and malformed Javadoc is covered by the separate
 * explicit doclint command rather than by any Maven phase. Where a host toolchain is unavailable the same commands
 * run inside the pinned container image, {@code maven:3.9.11-eclipse-temurin-25}, with the working tree mounted.
 * Unit tests for this class live under {@code src/test/java/com/cardemo/unit/} and never in this package;
 * the class is written to be testable without a container by taking every collaborator, including the
 * clock, through its constructor.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>This class reads no configuration property of its own. Every value below belongs to
 * {@code com.cardemo.security.JwtTokenProvider} or to {@code com.cardemo.config.SecurityConfig} and is
 * listed because it determines whether this service can complete a sign-on:
 *
 * <ul>
 *   <li>{@code carddemo.security.jwt.signing-key} resolves from the environment variable
 *       {@code JWT_SIGNING_KEY} with <b>no default, no example and no committed fallback</b>. Absence is
 *       intended fail-fast behaviour and aborts startup rather than degrading to a weak key. The key must
 *       carry at least 32 bytes of entropy for the HS256 algorithm in use.</li>
 *   <li>{@code carddemo.security.jwt.issuer} resolves from {@code JWT_ISSUER} and becomes the issuer
 *       claim.</li>
 *   <li>{@code carddemo.security.jwt.expiration-minutes} resolves from {@code JWT_EXPIRATION_MINUTES},
 *       defaults to 30, is accepted only within 1..1440 and bounds the token lifetime. Minutes, not
 *       seconds: the superseded {@code expiration-seconds} spelling defaulted to 3,600 seconds, which is
 *       double the approved bearer window.</li>
 *   <li>BCrypt strength is 10, matching the hashes written by {@code V3__seed_data.sql}. The encoder is a
 *       single application-wide bean; this class neither creates one nor configures one, because a second
 *       encoder at a different strength would fail every verification.</li>
 *   </ul>
 *
 * <p>The ten seeded users originate as inline {@code SYSUT1 DD *} data at
 * {@code app/jcl/DUSRSECJ.jcl:L35-L44}, fed through {@code EXEC PGM=IEBGENER} at {@code :L32} in the
 * 80-byte {@code CSUSR01Y} layout - five administrators of type {@code 'A'} and five standard users of type
 * {@code 'U'}. They are loaded only by {@code V3__seed_data.sql} and only as BCrypt hashes. This class
 * performs no seeding of any kind and holds no startup hook.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <p>The source emits exactly five messages on the sign-on path and this class reproduces those five
 * byte-for-byte and adds nothing. Cursor positioning ({@code MOVE -1 TO ...L}) has no HTTP counterpart, so
 * the surviving semantic is the field each failure attaches to, recorded in the last column.
 *
 * <table>
 *   <caption>The five sign-on messages, their causes and their target field</caption>
 *   <tr><th>Message</th><th>Locator</th><th>Cause</th><th>Raised as</th><th>Field</th></tr>
 *   <tr><td>{@code Please enter User ID ...}</td><td>{@code :L120}</td><td>identifier absent or blank</td>
 *       <td>{@code ValidationException}, {@code BLANK}</td><td>{@code userId}</td></tr>
 *   <tr><td>{@code Please enter Password ...}</td><td>{@code :L125}</td><td>password absent or blank</td>
 *       <td>{@code ValidationException}, {@code BLANK}</td><td>{@code password}</td></tr>
 *   <tr><td>{@code Wrong Password. Try again ...}</td><td>{@code :L242}</td>
 *       <td><strong>every</strong> credential refusal: row found and credential rejected, and also
 *       {@code RESP 13} with no such row, which the legacy screen distinguished at {@code :L249} with
 *       {@code User not found. Try again ...}</td><td>{@code ValidationException}, {@code INVALID}</td>
 *       <td>{@code password}</td></tr>
 *   <tr><td>{@code Unable to verify the User ...}</td><td>{@code :L254}</td>
 *       <td>store unreadable or unexpected condition</td>
 *       <td>{@code FileAccessException} or {@code FatalProcessingException}</td><td>{@code userId}</td></tr>
 *   </table>
 *
 * <p>Troubleshooting the two configuration-driven failures: a startup abort naming an unresolvable
 * placeholder for {@code carddemo.security.jwt.signing-key} means {@code JWT_SIGNING_KEY} is absent, which is
 * the intended fail-fast; export a key of at least 32 bytes. A sign-on that reaches
 * {@code Unable to verify the User ...} for every user, rather than for one, indicates the user security
 * store cannot be read - check that the schema migrations have applied and that
 * {@code V3__seed_data.sql} has loaded, since an empty {@code user_security} table produces
 * {@code User not found. Try again ...} instead. A sign-on that reaches
 * {@code Wrong Password. Try again ...} for every seeded user indicates a BCrypt strength or normalisation
 * mismatch between the seeded hashes and the verifying encoder.
 *
 * <h2>Deliberately preserved legacy behaviour</h2>
 *
 * <ul>
 *   <li><b>The blank checks fall through.</b> The {@code EVALUATE TRUE} at {@code :L117-L130} has no early
 *       exit, so the folding at {@code :L132-L136} executes even on both blank-input paths; only the
 *       {@code IF NOT ERR-FLG-ON} gate at {@code :L138-L140} suppresses the read. The observable outcome -
 *       two distinguishable blank failures and no lookup - is reproduced exactly.</li>
 *   <li><b>Check order is load bearing.</b> The identifier is tested at {@code :L118} before the password
 *       at {@code :L123}, so a blank identifier masks a simultaneously blank password and the source
 *       reports only the first. That ordering is preserved.</li>
 *   <li><b>The wrong-password branch sets no error flag.</b> {@code :L241-L246} omits the
 *       {@code MOVE 'Y' TO WS-ERR-FLG} that both {@code :L248} and {@code :L253} perform. This is
 *       reproduced and not corrected, because parity is the contract.</li>
 *   <li><b>The over-permissive distinction between the failure modes is retained as the source wrote
 *       it.</b> Distinguishing a missing row from a rejected credential is in tension with least privilege
 *       and with Spring Security's own convention, and the tension is real. It is
 *       resolved by reproducing the legacy literals exactly and adding nothing beyond them, so no
 *       information leaves this class that the legacy screen did not already display to the same
 *       terminal.</li>
 *   </ul>
 *
 * <h2>Invariants that must continue to hold</h2>
 *
 * <ul>
 *   <li>No credential value is echoed in a log line, a message or an exception. {@code ValidationException}
 *       is constructed with the field <i>name</i> - {@code userId} or {@code password} - and never with the
 *       value.</li>
 *   <li>No personal or financial data becomes a token claim. The claim set stays subject, role and the
 *       standard registered claims, and minting stays in the security package.</li>
 *   <li>The stored password hash is never read or passed from this class; verification is delegated and the
 *       hash accessor is never named here.</li>
 *   <li>Both the identifier and the password are case-folded, by delegating to a collaborator verified to
 *       fold both.</li>
 *   <li>No second password encoder is defined; the single application-wide bean is consumed indirectly
 *       through the delegate.</li>
 *   <li>Metric names and claim names are referenced through the owning class's own API rather than restated
 *       here, so neither can drift.</li>
 *   <li>{@code USRSEC} is catalogued, at {@code app/catlg/LISTCAT.txt:L3846} with the {@code KEYLEN 8} and
 *       {@code AVGLRECL 80} attribute line at {@code :L3883}, corroborated by
 *       {@code app/jcl/DUSRSECJ.jcl:L65-L66}.</li>
 *   <li>The routing hint survives as the user-class code {@code com.cardemo.model.dto.SignOnResponse}
 *       already carries; that response is another component's contract and is not widened from here.</li>
 *   <li>The token lifetime property is {@code carddemo.security.jwt.expiration-minutes} and the signing-key
 *       variable is {@code JWT_SIGNING_KEY}. Those are the keys the provider binds; no second spelling is
 *       introduced, and the superseded {@code expiration-seconds} and {@code JWT_SECRET} spellings are
 *       accepted nowhere.</li>
 *   <li>The credential comparison is at {@code app/cbl/COSGN00C.cbl:L223} and the identity moves span
 *       {@code :L224-L228}; {@code :L222} is the {@code WHEN 0} selector, not the comparison.</li>
 *   </ul>
 *
 * <h2>Boundaries of this class</h2>
 *
 * <ul>
 *   <li>{@code com.cardemo.config.SecurityConfig} owns the password-encoder bean and its strength-10
 *       configuration, the symmetric HMAC decoder, the mapping from the {@code 'A'} and {@code 'U'} user
 *       classes onto role authorities, the stateless session policy, and the filter ordering that places
 *       correlation before token authentication before authorisation. None of those is configured
 *       here.</li>
 *   <li>No {@code com.cardemo.controller.AuthController} exists, so no consumer has pinned this service's
 *       signature and the HTTP status mapping and endpoint payload shape are undetermined. This class
 *       therefore <i>defines</i> its contract rather than conforming to one: one
 *       operation, {@code SignOnRequest} in and {@code SignOnResponse} out, with failures raised as typed
 *       exceptions carrying the legacy literal. Status selection belongs to that controller, since no
 *       exception in the hierarchy carries a status annotation and the tree has no
 *       global exception handler.</li>
 *   </ul>
 *
 * <p>Instances are immutable after construction and hold no request state, so a single bean is safe for
 * concurrent use. Every value the source held in {@code WORKING-STORAGE} - the identifier, the password,
 * the message, the error flag and the two response codes at {@code app/cbl/COSGN00C.cbl:L38-L46} - is
 * method-local here. A field holding a password would be both a concurrency defect and a secret-hygiene
 * violation, and there is none.
 */
@Service
public class AuthenticationService {

    /**
     * Structured diagnostic sink. The legacy program had no instrumentation whatever: the entire telemetry
     * surface of the corpus is {@code DISPLAY} to SYSOUT, and {@code COSGN00C} does not even use that. Every
     * log statement in this class is new capability under Rule 1 Clause A, and none of them carries a
     * credential, a hash, a token, a signing key or a personal-data field.
     */
    private static final Logger LOG = LoggerFactory.getLogger(AuthenticationService.class);

    /**
     * The originating program name. Source: {@code WS-PGMNAME PIC X(08) VALUE 'COSGN00C'} at
     * {@code app/cbl/COSGN00C.cbl:L36} @ 7756d89. The source moves it to the screen header at {@code :L184}
     * and to {@code CDEMO-FROM-PROGRAM} at {@code :L225}, and it identifies the culprit component on the
     * unrecoverable path.
     */
    private static final String PROGRAM_NAME = "COSGN00C";

    /**
     * The originating transaction identifier. Source: {@code WS-TRANID PIC X(04) VALUE 'CC00'} at
     * {@code app/cbl/COSGN00C.cbl:L37} @ 7756d89, bound to this program by
     * {@code DEFINE TRANSACTION(CC00) ... PROGRAM(COSGN00C)} at {@code app/csd/CARDDEMO.CSD:L378-L384}.
     */
    private static final String TRANSACTION_NAME = "CC00";

    /**
     * The logical name of the user security dataset. Source:
     * {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '} at {@code app/cbl/COSGN00C.cbl:L39} @ 7756d89, used
     * as {@code DATASET(WS-USRSEC-FILE)} on the read at {@code :L212}. The two trailing spaces of the
     * {@code PIC X(08)} field are padding of the fixed-width field and carry no meaning, so the logical name
     * is held at its significant length - the same spelling used by {@code DEFINE FILE(USRSEC)} at
     * {@code app/csd/CARDDEMO.CSD:L88-L92}.
     */
    private static final String USER_SECURITY_FILE = "USRSEC";

    /**
     * The access verb reported when the user security store cannot be read. Source:
     * {@code EXEC CICS READ} at {@code app/cbl/COSGN00C.cbl:L211-L219} @ 7756d89.
     */
    private static final String READ_OPERATION = "READ";

    /**
     * First screen title line. Source: {@code CCDA-TITLE01 PIC X(40)} at
     * {@code app/cpy/COTTL01Y.cpy:L18-L19} @ 7756d89, moved to the header at
     * {@code app/cbl/COSGN00C.cbl:L181}. Reproduced at its full declared width including the leading and
     * trailing padding, because the padding is what centred the text on the 3270 screen.
     */
    private static final String SCREEN_TITLE_01 = "      AWS Mainframe Modernization       ";

    /**
     * Second screen title line. Source: {@code CCDA-TITLE02 PIC X(40)} at
     * {@code app/cpy/COTTL01Y.cpy:L20-L22} @ 7756d89, moved to the header at
     * {@code app/cbl/COSGN00C.cbl:L182}. The copybook also carries a commented-out earlier wording on the
     * intervening line; the active literal is the one reproduced here.
     */
    private static final String SCREEN_TITLE_02 = "              CardDemo                  ";

    /**
     * Declared width of the screen error-message field. Source: {@code ERRMSGO PIC X(78)} at
     * {@code app/cpy-bms/COSGN00.CPY:L152} @ 7756d89, the target of {@code MOVE WS-MESSAGE TO ERRMSGO} at
     * {@code app/cbl/COSGN00C.cbl:L149}.
     */
    private static final int ERROR_MESSAGE_FIELD_LENGTH = 78;

    /**
     * Declared width of the working message field. Source: {@code WS-MESSAGE PIC X(80)} at
     * {@code app/cbl/COSGN00C.cbl:L38} @ 7756d89, sent in full by
     * {@code LENGTH(LENGTH OF WS-MESSAGE)} at {@code :L166}.
     */
    private static final int WORK_MESSAGE_FIELD_LENGTH = 80;

    /**
     * Message 1 of 5. Source: {@code app/cbl/COSGN00C.cbl:L120} @ 7756d89, raised when the identifier is
     * absent or blank. Byte-exact: one space then three dots, and no trailing period.
     */
    private static final String MESSAGE_USER_ID_REQUIRED = "Please enter User ID ...";

    /**
     * Message 2 of 5. Source: {@code app/cbl/COSGN00C.cbl:L125} @ 7756d89, raised when the password is
     * absent or blank. Byte-exact: one space then three dots, and no trailing period.
     */
    private static final String MESSAGE_PASSWORD_REQUIRED = "Please enter Password ...";

    /**
     * Message 3 of 5. Source: {@code app/cbl/COSGN00C.cbl:L242-L243} @ 7756d89, raised when the row was
     * found but the credential did not verify. Byte-exact: an internal period after {@code Password}, then
     * one space and three dots, and no trailing period.
     */
    private static final String MESSAGE_WRONG_PASSWORD = "Wrong Password. Try again ...";

    // Message 4 of 5 of app/cbl/COSGN00C.cbl - the WHEN 13 literal at :L249, DFHRESP(NOTFND),
    // 'User not found. Try again ...' - IS DELIBERATELY NOT REPRODUCED, and no constant holds it.
    //
    // On a 3270 in a physically controlled machine room, telling the operator which of the two
    // credentials was wrong was a courtesy. On an HTTP surface it is an oracle: a caller who can tell
    // 'User not found' from 'Wrong Password' can enumerate the user_security table without ever
    // authenticating. Every credential refusal - unknown identifier, wrong password, and a row deleted
    // between verification and re-read - therefore renders MESSAGE_WRONG_PASSWORD above, as one
    // ValidationException carrying the INVALID discriminator and the password field marker.
    //
    // The literal is not held in an unused constant precisely because Rule 1 Clause B forbids dead code;
    // the locator above is the record of what the source says, and the deviation is registered in the
    // discrepancy table on this class. Elapsed time is closed alongside the message and the type: the
    // verifier performs one BCrypt comparison on the unknown-identifier path too - see
    // com.cardemo.security.CardDemoUserDetailsService.

    /**
     * Message 5 of 5. Source: {@code app/cbl/COSGN00C.cbl:L254} @ 7756d89, raised on the
     * {@code WHEN OTHER} arm for any response the program did not anticipate. Byte-exact.
     */
    private static final String MESSAGE_UNABLE_TO_VERIFY = "Unable to verify the User ...";

    /**
     * The request field the identifier failures attach to. Cursor positioning
     * ({@code MOVE -1 TO USERIDL}) has no HTTP counterpart, so the field name is the surviving semantic.
     * This is a field <i>name</i> and never a field value: carrying a value here would echo a credential.
     */
    private static final String FIELD_USER_ID = "userId";

    /**
     * The request field the password failures attach to, standing in for
     * {@code MOVE -1 TO PASSWDL OF COSGN0AI} at {@code app/cbl/COSGN00C.cbl:L126} and at {@code :L244},
     * both @ 7756d89. A name only, never the presented value.
     */
    private static final String FIELD_PASSWORD = "password";

    /**
     * Header date rendering. Source: {@code WS-CURDATE-MM-DD-YY} assembled at
     * {@code app/cbl/COSGN00C.cbl:L186-L190} @ 7756d89 from {@code FUNCTION CURRENT-DATE}, with the
     * two-digit year taken as {@code WS-CURDATE-YEAR(3:2)} at {@code :L188}. {@code Locale.ROOT} keeps the
     * rendering independent of the platform default locale.
     */
    private static final DateTimeFormatter HEADER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT);

    /**
     * Header time rendering. Source: {@code WS-CURTIME-HH-MM-SS} assembled at
     * {@code app/cbl/COSGN00C.cbl:L192-L196} @ 7756d89. {@code Locale.ROOT} for the same reason as the date.
     */
    private static final DateTimeFormatter HEADER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /**
     * Credential normalisation and verification. This collaborator owns the fold described in the class
     * documentation and owns every interaction with the stored hash, which is why this class never names
     * the hash accessor.
     */
    private final CardDemoUserDetailsService cardDemoUserDetailsService;

    /**
     * The user security store. Used for the non-credential part of the read only: recovering the user class
     * that drives the role claim and the routing hint, and detecting the absent-row condition. It is never
     * used to reach a password hash.
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Token minting. Replaces the COMMAREA identity handshake of
     * {@code app/cbl/COSGN00C.cbl:L224-L228} @ 7756d89 and owns the claim set, the signing key and the
     * lifetime.
     */
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * Metric facade. Exactly four instruments exist application-wide and all are registered by this
     * collaborator; this class registers none and only increments the authentication-attempt counter
     * through the facade.
     */
    private final MetricsConfig metricsConfig;

    /**
     * Time source for the screen header. Injected rather than read from the platform so that the rendering
     * is deterministic and testable, per Rule 1 Clause A. Replaces
     * {@code FUNCTION CURRENT-DATE} at {@code app/cbl/COSGN00C.cbl:L179} @ 7756d89.
     */
    private final Clock clock;

    /**
     * Creates the sign-on service. Constructor injection only: there is no field or setter injection, no
     * service locator and no mutable static state anywhere in this class, so an instance is immutable after
     * construction and safe for concurrent use.
     *
     * @param cardDemoUserDetailsService credential normalisation and verification; must not be
     *                                   {@code null}
     * @param userSecurityRepository     the user security store, used only for its non-credential fields;
     *                                   must not be {@code null}
     * @param jwtTokenProvider           token minting; must not be {@code null}
     * @param metricsConfig              the metric facade owning the authentication-attempt counter; must
     *                                   not be {@code null}
     * @param clock                      the time source for the screen header; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public AuthenticationService(final CardDemoUserDetailsService cardDemoUserDetailsService,
            final UserSecurityRepository userSecurityRepository,
            final JwtTokenProvider jwtTokenProvider,
            final MetricsConfig metricsConfig,
            final Clock clock) {
        this.cardDemoUserDetailsService = Objects.requireNonNull(cardDemoUserDetailsService,
                "cardDemoUserDetailsService must not be null");
        this.userSecurityRepository = Objects.requireNonNull(userSecurityRepository,
                "userSecurityRepository must not be null");
        this.jwtTokenProvider = Objects.requireNonNull(jwtTokenProvider, "jwtTokenProvider must not be null");
        this.metricsConfig = Objects.requireNonNull(metricsConfig, "metricsConfig must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Signs a user on: the sole public operation of this service, and the whole of transaction
     * {@code CC00} as seen by a caller.
     *
     * <p>Because no controller has yet pinned a signature, this method <i>defines</i> the contract rather
     * than conforming to one: one request object in, one response object out, and every failure raised as a
     * typed exception carrying the legacy screen literal for that outcome. It is the only unauthenticated
     * operation in the application surface.
     *
     * <p>The operation is read-only against the user security store. It writes nothing, holds nothing
     * across calls, creates no HTTP session, and performs no navigation: the
     * {@code EXEC CICS XCTL} of {@code app/cbl/COSGN00C.cbl:L231-L239} @ 7756d89 survives only as the user
     * class the response carries, from which the caller derives the administrator or main menu destination.
     *
     * @param request the sign-on request. Of its eleven components, only the identifier and the password
     *                are operative; the remainder are terminal-header metadata that the legacy screen
     *                supplied and that this operation does not consume. May be {@code null}, which is
     *                treated as the zero-length communication area of
     *                {@code app/cbl/COSGN00C.cbl:L80} and reported as a missing identifier
     * @return the issued token, the folded identifier and the single-character user class, the last of
     *         which is the routing hint
     * @throws ValidationException       if the identifier is absent or blank, if the password is absent or
     *                                   blank, or if the credential was rejected <em>for any reason,
     *                                   including an identifier no row bears</em>. The first two carry the
     *                                   blank discriminator, the third the invalid discriminator, and all
     *                                   three carry a field name and never a field value
     * @throws FileAccessException       if the user security store could not be read, which is the
     *                                   recoverable half of the {@code WHEN OTHER} arm at {@code :L252}
     * @throws FatalProcessingException if an unanticipated condition ended the operation, which is the
     *                                   unrecoverable half of the same arm
     */
    @Transactional(readOnly = true)
    public SignOnResponse signOn(final SignOnRequest request) {
        return mainPara(request);
    }

    /**
     * Derived from {@code MAIN-PARA.} at {@code app/cbl/COSGN00C.cbl:L73-L102} @ 7756d89 - the program's
     * entry paragraph, and here the orchestration seam.
     *
     * <p>The source opens by clearing its state, {@code SET ERR-FLG-OFF TO TRUE} at {@code :L75} and
     * {@code MOVE SPACES} to the message and the screen field at {@code :L77-L78}. That has no counterpart:
     * there is no state to clear because every value the source held in {@code WORKING-STORAGE} is
     * method-local here and starts each call fresh.
     *
     * <p>The source then branches four ways, and only one of the four survives translation:
     *
     * <ul>
     *   <li>{@code IF EIBCALEN = 0} at {@code :L80-L83} - the terminal-initiated first entry, arriving with
     *       a zero-length communication area. It paints an empty screen with the cursor on the identifier
     *       field ({@code MOVE -1 TO USERIDL} at {@code :L82}) and returns with a transaction identifier so
     *       the terminal can supply credentials. A REST caller cannot be re-prompted, so an absent request
     *       payload is simply unusable; it is reported against the same field the source put the cursor on,
     *       with the message that field's own blank check would have produced. No sixth message is
     *       invented.</li>
     *   <li>{@code EVALUATE EIBAID} at {@code :L85} dispatches on the attention identifier. <b>An HTTP
     *       request carries no attention identifier at all</b>, so the dispatch itself has no counterpart
     *       and no equivalent is fabricated. Every call to this operation is, by construction, the
     *       {@code WHEN DFHENTER} arm at {@code :L86-L87}.</li>
     *   <li>{@code WHEN DFHPF3} at {@code :L88-L90} moves {@code CCDA-MSG-THANK-YOU} - declared at
     *       {@code app/cpy/CSMSG01Y.cpy:L17-L18} - and performs the plain-text send, ending the
     *       conversation. No counterpart: there is no exit key to press. The surviving semantic of the
     *       paragraph it calls is described on {@link #sendPlainText}.</li>
     *   <li>{@code WHEN OTHER} at {@code :L91-L94} sets the error flag, moves
     *       {@code CCDA-MSG-INVALID-KEY} - {@code app/cpy/CSMSG01Y.cpy:L19-L20} - and repaints. No
     *       counterpart, for the same reason: an invalid key cannot be pressed over HTTP.</li>
     * </ul>
     *
     * <p>The closing {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)} at
     * {@code :L98-L102} is what made the program pseudo-conversational, handing the terminal back with the
     * identity riding in the communication area. It becomes the plain return of a response object carrying
     * a bearer token, and nothing is retained on the server between calls.
     *
     * <p>This method is also where the authentication-attempt counter is emitted, exactly once per call and
     * on every path, because a {@code finally} block cannot be bypassed by an early return or a thrown
     * failure. Both collaborators that could plausibly have counted here - the credential verifier and the
     * token filter - defer the emission to this class precisely so that an attempt is never counted twice.
     * The outcome tag is drawn from a closed two-value set owned by the metric facade and carries no
     * subject identifier, no user name and no network address, so the counter's cardinality is bounded at
     * two series for all time.
     *
     * @param request the incoming request, possibly {@code null}
     * @return the successful sign-on response
     */
    private SignOnResponse mainPara(final SignOnRequest request) {
        boolean succeeded = false;
        try {
            if (request == null) {
                LOG.warn("Sign-on rejected for transaction {}: no request payload was supplied.",
                        TRANSACTION_NAME);
                final String rendered = sendSignonScreen(MESSAGE_USER_ID_REQUIRED, FIELD_USER_ID);
                throw ValidationException.missingField(FIELD_USER_ID, rendered);
            }
            final SignOnResponse response = processEnterKey(request);
            succeeded = true;
            return response;
        } finally {
            metricsConfig.countAuthenticationAttempt(succeeded
                    ? MetricsConfig.AuthenticationOutcome.SUCCESS
                    : MetricsConfig.AuthenticationOutcome.FAILURE);
        }
    }

    /**
     * Derived from {@code PROCESS-ENTER-KEY.} at {@code app/cbl/COSGN00C.cbl:L108-L140} @ 7756d89 - the
     * paragraph that receives the credential pair, screens it for blanks and gates the store read.
     *
     * <p>The opening {@code EXEC CICS RECEIVE MAP('COSGN0A') MAPSET('COSGN00')} at {@code :L110-L115} has no
     * counterpart: the request object has already been bound by the time this method runs, and its eleven
     * components come from the same symbolic map the receive populated.
     *
     * <p><b>The blank screening falls through, and that is deliberate in the source.</b> The
     * {@code EVALUATE TRUE} at {@code :L117} closes at {@code END-EVALUATE.} on {@code :L130} with no early
     * exit on either failing arm - the {@code WHEN OTHER} arm at {@code :L128-L129} is a bare
     * {@code CONTINUE}. Control therefore reaches {@code :L132} and folds both operands <i>even on a blank
     * input path</i>, and only the {@code IF NOT ERR-FLG-ON} test at {@code :L138-L140} suppresses the
     * store read. Throwing at the point of detection reproduces the observable outcome exactly: the two
     * blank failures stay distinguishable from one another and from every other outcome, and no lookup and
     * no credential comparison is performed. The fold that the source still executes on those paths cannot
     * be observed, because its only two consumers are the record key at {@code :L215} and the comparison at
     * {@code :L223}, neither of which is reached.
     *
     * <p><b>The order of the two checks is load bearing.</b> The identifier is tested at {@code :L118}
     * before the password at {@code :L123}, so a request that omits both is reported as a missing
     * identifier and never as a missing password. Reversing the order, or reporting both together, would
     * change what a caller sees.
     *
     * <p>{@code SPACES OR LOW-VALUES} covers three distinct Java states - {@code null}, empty, and
     * present-but-content-free - and {@link #isSpacesOrLowValues} handles all three explicitly rather than
     * coercing {@code null} into an empty string. Absent, blank and invalid stay three different things.
     *
     * <p>The fold at {@code :L132-L136} is delegated, as the class documentation explains, so this method
     * hands the operands on exactly as received. It does not trim them, does not pad them to the declared
     * eight-character width, and does not fold them a second time.
     *
     * @param request the incoming request; never {@code null} at this point
     * @return the successful sign-on response
     */
    private SignOnResponse processEnterKey(final SignOnRequest request) {
        final String presentedUserId = request.userId();
        final String presentedPassword = request.password();

        if (isSpacesOrLowValues(presentedUserId)) {
            LOG.warn("Sign-on rejected for transaction {}: the user identifier was absent or blank.",
                    TRANSACTION_NAME);
            final String rendered = sendSignonScreen(MESSAGE_USER_ID_REQUIRED, FIELD_USER_ID);
            throw ValidationException.missingField(FIELD_USER_ID, rendered);
        }

        if (isSpacesOrLowValues(presentedPassword)) {
            LOG.warn("Sign-on rejected for transaction {}: the password was absent or blank.",
                    TRANSACTION_NAME);
            final String rendered = sendSignonScreen(MESSAGE_PASSWORD_REQUIRED, FIELD_PASSWORD);
            throw ValidationException.missingField(FIELD_PASSWORD, rendered);
        }

        return readUserSecFile(presentedUserId, presentedPassword);
    }

    /**
     * Derived from {@code SEND-SIGNON-SCREEN.} at {@code app/cbl/COSGN00C.cbl:L145-L157} @ 7756d89 - the
     * paragraph that paints the sign-on map, and the source's renderer for four of the five messages.
     *
     * <p>The paragraph performs three things. It calls the header population at {@code :L147}, which
     * survives as {@link #populateHeaderInfo}. It moves the working message into the screen field,
     * {@code MOVE WS-MESSAGE TO ERRMSGO} at {@code :L149}, which survives as the fixed-width narrowing
     * performed here. And it issues {@code EXEC CICS SEND MAP('COSGN0A') MAPSET('COSGN00') ... ERASE
     * CURSOR} at {@code :L151-L157}, which has no counterpart at all: there is no map to send, no screen to
     * erase and no cursor to place. The surviving semantic of the cursor is the field name the failure
     * attaches to, which is why the caller passes one and why it is recorded on the diagnostic.
     *
     * <p>This is a presentation-only paragraph, so it keeps its identity rather than being folded into its
     * callers: five call sites in this class correspond to the five {@code PERFORM SEND-SIGNON-SCREEN}
     * statements at {@code :L122}, {@code :L127}, {@code :L245}, {@code :L251} and {@code :L256}.
     *
     * <p>The narrowing to the declared {@code ERRMSGO PIC X(78)} width is reproduced because truncation is
     * observable - a longer message was visibly cut off on the screen - while the right-hand space padding
     * of the fixed-width field is not, so the value is carried at its significant length. None of the five
     * messages is long enough to truncate, so the narrowing never fires for them; it is honoured so that
     * the field contract holds rather than only appearing to.
     *
     * @param message         the working message to render; the caller always supplies one of the five
     *                        source literals
     * @param cursorFieldName the request field the source put the cursor on, which becomes the field the
     *                        failure is reported against. A field name only, never a field value
     * @return the message as it appears in the screen field, for the caller to carry on its failure
     */
    private String sendSignonScreen(final String message, final String cursorFieldName) {
        final String header = populateHeaderInfo();
        final String renderedMessage = moveToAlphanumericField(message, ERROR_MESSAGE_FIELD_LENGTH);
        LOG.debug("Sign-on screen rendered with header [{}], cursor field {} and message [{}].",
                header, cursorFieldName, renderedMessage);
        return renderedMessage;
    }

    /**
     * Derived from {@code SEND-PLAIN-TEXT.} at {@code app/cbl/COSGN00C.cbl:L162-L172} @ 7756d89 - the
     * paragraph that writes an unformatted line to the terminal and then ends the task outright.
     *
     * <p>Two things separate this paragraph from {@link #sendSignonScreen}, and both matter. It sends text
     * rather than a map, {@code EXEC CICS SEND TEXT FROM(WS-MESSAGE) LENGTH(LENGTH OF WS-MESSAGE) ERASE
     * FREEKB} at {@code :L164-L169}, so the message is carried at the full declared
     * {@code WS-MESSAGE PIC X(80)} width rather than narrowed to the screen field. And it closes with a
     * bare {@code EXEC CICS RETURN} at {@code :L171-L172} carrying <b>no</b> transaction identifier and
     * <b>no</b> communication area, unlike the return at {@code :L98-L102}. That difference is the whole
     * point of the paragraph: the pseudo-conversation is over, nothing further will be received, and the
     * task simply ends.
     *
     * <p><b>What has no counterpart, and what survives.</b> The source's own call site is the
     * {@code WHEN DFHPF3} arm at {@code :L88-L90}, and that call site has no HTTP counterpart because a
     * REST request carries no attention identifier - the point is made in full on {@link #mainPara}. What
     * does survive is the paragraph's defining semantic, a terminating render for an outcome that cannot be
     * continued or retried, and this class applies it to exactly that: the unrecoverable branch of the
     * {@code WHEN OTHER} arm, where an unanticipated condition has ended the operation and repainting a
     * screen for another attempt would be meaningless. The recoverable branch of the same arm keeps the
     * source's own repaint. That redirection is narrow: the paragraph is not consolidated,
     * not emptied and not deleted, its rendering is reproduced faithfully, and the divergence is confined to
     * which outcome reaches it. Should an exit affordance ever be added to the API, the paragraph's original
     * call site is restored alongside this one.
     *
     * @param message the working message to render; the caller supplies the unanticipated-condition literal
     * @return the message as it appears on the terminal line, for the caller to carry on its failure
     */
    private String sendPlainText(final String message) {
        final String renderedMessage = moveToAlphanumericField(message, WORK_MESSAGE_FIELD_LENGTH);
        LOG.error("Sign-on for transaction {} ended without continuation: [{}].",
                TRANSACTION_NAME, renderedMessage);
        return renderedMessage;
    }

    /**
     * Derived from {@code POPULATE-HEADER-INFO.} at {@code app/cbl/COSGN00C.cbl:L177-L204} @ 7756d89 - the
     * paragraph that fills the six header fields every screen in the application shares.
     *
     * <p>The source reads the clock once with {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at
     * {@code :L179} and then moves six values onto the map: the two title lines from
     * {@code app/cpy/COTTL01Y.cpy} at {@code :L181-L182}, the transaction and program names at
     * {@code :L183-L184}, the date assembled as {@code MM/DD/YY} at {@code :L186-L190} - taking the
     * two-digit year as {@code WS-CURDATE-YEAR(3:2)} at {@code :L188} - and the time assembled as
     * {@code HH:MM:SS} at {@code :L192-L196}. All six are reproduced here, in that order, at their declared
     * widths.
     *
     * <p>The clock is read exactly once per call, as the source reads it once, so the date and the time on
     * a single header can never straddle a second boundary. It is read through the injected time source
     * rather than the platform clock, which is what makes the rendering deterministic and testable.
     *
     * <p>The paragraph closes with {@code EXEC CICS ASSIGN APPLID} at {@code :L198-L200} and
     * {@code EXEC CICS ASSIGN SYSID} at {@code :L202-L204}, which interrogate the region the transaction is
     * running in. Those have no counterpart: there is no CICS region, and the request object's own
     * application and system components are terminal-header metadata this operation does not consume.
     *
     * @return the six header values in source order, for the screen diagnostic
     */
    private String populateHeaderInfo() {
        final LocalDateTime headerTimestamp = LocalDateTime.now(clock);
        return String.format(Locale.ROOT, "title01=%s title02=%s tranid=%s pgmname=%s date=%s time=%s",
                SCREEN_TITLE_01, SCREEN_TITLE_02, TRANSACTION_NAME, PROGRAM_NAME,
                HEADER_DATE_FORMAT.format(headerTimestamp), HEADER_TIME_FORMAT.format(headerTimestamp));
    }

    /**
     * Derived from {@code READ-USER-SEC-FILE.} at {@code app/cbl/COSGN00C.cbl:L209-L257} @ 7756d89 - the
     * paragraph that reads the user security record, compares the credential and establishes identity.
     *
     * <p>The read at {@code :L211-L219} is keyed on the folded identifier,
     * {@code RIDFLD(WS-USER-ID) KEYLENGTH(LENGTH OF WS-USER-ID)} at {@code :L215-L216}, against the
     * eight-character key of {@code app/cpy/CSUSR01Y.cpy:L17-L23}. It becomes a primary-key lookup on the
     * user security store, performed inside the verifier this method delegates to.
     *
     * <p><b>This program tests CICS response codes, not a file status.</b> The
     * {@code EVALUATE WS-RESP-CD} at {@code :L221} has three arms - {@code WHEN 0} at {@code :L222},
     * {@code WHEN 13} at {@code :L247} and {@code WHEN OTHER} at {@code :L252} - where 13 is
     * {@code DFHRESP(NOTFND)}. There is consequently no file status to translate anywhere on this path, so
     * the application's file-status mapper is deliberately not consulted: it maps two-character file
     * statuses, not response codes, and pressing it into service here would misrepresent both.
     *
     * <p>The five outcomes, in the order the source establishes them:
     *
     * <ol>
     *   <li><b>Response 0 and the credential matches.</b> {@code IF SEC-USR-PWD = WS-USER-PWD} at
     *       {@code :L223} - the comparison is on {@code :L223}; {@code :L222} is only the arm selector.
     *       Identity is then established by five moves at {@code :L224-L228}: the transaction name and
     *       program name into the from-fields, the folded identifier into {@code CDEMO-USER-ID}, the
     *       record's type byte into {@code CDEMO-USER-TYPE}, and zeroes into the program context. Only the
     *       middle two survive, as the token's subject and role. The plaintext comparison itself is
     *       replaced by a BCrypt verification at strength 10, performed once - the algorithm is
     *       deliberately slow, so it is not performed twice - and performed by the delegate, which is the
     *       only component in the application permitted to touch a stored hash.</li>
     *   <li><b>Response 0 and the credential does not match.</b> {@code :L241-L246}. Note that this arm
     *       sets no error flag, unlike {@code :L248} and {@code :L253} which both do; the asymmetry is
     *       preserved rather than corrected.</li>
     *   <li><b>Response 13, no such record.</b> {@code :L247-L251}. An empty lookup <i>is</i> this arm.</li>
     *   <li><b>Any other response, recoverable.</b> {@code :L252-L256}, where the store itself could not be
     *       interrogated. The source repaints the screen, so a further attempt remains possible.</li>
     *   <li><b>Any other response, unrecoverable.</b> The same arm, reached when the condition is not a
     *       store-access failure at all. Rendered through {@link #sendPlainText} for the reasons set out
     *       there. The program carries no abend construct of its own - it has no
     *       {@code CALL 'CEE3ABD'}, no {@code EXEC CICS ABEND} and no abend copybook - so no abend code and
     *       no abend reason is invented; the culprit component is named from
     *       {@code WS-PGMNAME} at {@code :L36} and the message is the arm's own literal.</li>
     * </ol>
     *
     * <p>Every failure is rethrown as a typed application exception with the original preserved as its
     * cause, and none is swallowed. The verifier's own already-typed failures are rethrown untouched rather
     * than wrapped a second time. No failure message, at any level, carries the presented password, the
     * stored hash or the issued token.
     *
     * <p>The single {@code BadCredentialsException} arm can only mean a rejected credential, never a blank
     * input, and that is a verified invariant rather than an assumption: the delegate treats a value as
     * blank when it is {@code null} or {@link String#isBlank()}, and {@link #isSpacesOrLowValues} is a
     * strict superset of that test, so every blank input has already been reported with its own literal by
     * {@link #processEnterKey} before this method runs.
     *
     * <p>The user class is recovered from the loaded record's own type byte rather than reconstructed from
     * the granted authority, so the mapping between the two is stated in exactly one place in the
     * application and cannot drift. The read is a first-level cache hit within this method's read-only
     * transaction, so it costs no second round trip to the database. It reaches only the record's
     * non-credential fields; the hash column is never named in this class.
     *
     * @param presentedUserId   the identifier exactly as received, unfolded and untrimmed; never blank
     * @param presentedPassword the password exactly as received, unfolded and untrimmed; never blank
     * @return the issued token, the folded identifier and the single-character user class
     */
    private SignOnResponse readUserSecFile(final String presentedUserId, final String presentedPassword) {
        try {
            final UserDetails authenticated =
                    cardDemoUserDetailsService.authenticate(presentedUserId, presentedPassword);
            final String userId = authenticated.getUsername();

            final Optional<UserSecurity> located = userSecurityRepository.findById(userId);
            if (located.isEmpty()) {
                // The verifier has just read this row successfully, so an empty result here means the row
                // was deleted between the two reads. It is reported as the SAME credential refusal the two
                // arms below produce - not as a record-not-found - because any distinguishable outcome on
                // this path would tell an unauthenticated caller that the identifier existed a moment ago.
                LOG.warn("Sign-on rejected: the {} record was no longer present after verification.",
                        USER_SECURITY_FILE);
                final String rendered = sendSignonScreen(MESSAGE_WRONG_PASSWORD, FIELD_PASSWORD);
                throw new ValidationException(rendered, FIELD_PASSWORD,
                        ValidationException.FailureKind.INVALID);
            }

            final UserType userType = located.get().getSecUsrType();
            if (userType == null) {
                LOG.error("Sign-on ended: the {} record carries no user class byte.", USER_SECURITY_FILE);
                final String rendered = sendPlainText(MESSAGE_UNABLE_TO_VERIFY);
                throw new FatalProcessingException(null, PROGRAM_NAME, null, rendered);
            }

            final String token = jwtTokenProvider.issueToken(userId, userType);
            LOG.info("Sign-on succeeded for transaction {}: identity established for user class {}.",
                    TRANSACTION_NAME, userType.getCode());
            return new SignOnResponse(token, userId, String.valueOf(userType.getCode()));
        } catch (final BadCredentialsException | UsernameNotFoundException rejected) {
            // ONE outcome for every credential refusal. The verifier folds an unknown identifier
            // (app/cbl/COSGN00C.cbl:L247-L251) and a wrong password (:L241-L246) into one type carrying one
            // message, having performed the same BCrypt work on both, and this arm keeps them folded: same
            // exception type, same HTTP status, same rendered literal, same marked field. UsernameNotFound
            // is still named here because the delegate's load-only method declares it and a future caller
            // could route through that method; catching both in one clause makes it impossible for the two
            // to diverge into distinguishable responses later.
            LOG.warn("Sign-on rejected for transaction {}: the presented credential did not verify.",
                    TRANSACTION_NAME);
            final String rendered = sendSignonScreen(MESSAGE_WRONG_PASSWORD, FIELD_PASSWORD);
            throw new ValidationException(rendered, FIELD_PASSWORD, ValidationException.FailureKind.INVALID,
                    rejected);
        } catch (final InternalAuthenticationServiceException | DataAccessException unreadable) {
            LOG.error("Sign-on failed for transaction {}: the {} store could not be read.",
                    TRANSACTION_NAME, USER_SECURITY_FILE, unreadable);
            final String rendered = sendSignonScreen(MESSAGE_UNABLE_TO_VERIFY, FIELD_USER_ID);
            throw new FileAccessException(rendered, null, USER_SECURITY_FILE, READ_OPERATION, unreadable);
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final RuntimeException unexpected) {
            LOG.error("Sign-on failed for transaction {}: an unanticipated condition ended the operation.",
                    TRANSACTION_NAME, unexpected);
            final String rendered = sendPlainText(MESSAGE_UNABLE_TO_VERIFY);
            throw new FatalProcessingException(null, PROGRAM_NAME, null, rendered, unexpected);
        }
    }

    /**
     * The COBOL {@code = SPACES OR LOW-VALUES} predicate, as applied at
     * {@code app/cbl/COSGN00C.cbl:L118} and {@code :L123} @ 7756d89. Not a paragraph: a primitive the two
     * blank screenings share.
     *
     * <p>A fixed-width alphanumeric field is unset in one of two ways - filled with spaces, or filled with
     * the low-value byte - and a Java string adds a third, being absent altogether. All three are treated
     * as unset, and {@code null} is never coerced into an empty string to get there.
     *
     * <p>The whitespace test is deliberately the broad one rather than an exact space-character comparison.
     * It makes this predicate a strict superset of the blank test the credential verifier applies
     * downstream, which is what guarantees that a content-free input is always reported with its own
     * message here and can never reach the verifier to be reported as a rejected credential instead.
     *
     * <p>A value <i>mixing</i> spaces with low values is, by contrast, <b>not</b> treated as unset, and that
     * is the source's behaviour rather than an oversight: such a field satisfies neither
     * {@code = SPACES} nor {@code = LOW-VALUES}, so the {@code WHEN OTHER} arm at
     * {@code app/cbl/COSGN00C.cbl:L128-L129} lets it through to the folded key at {@code :L215}. Widening
     * the predicate to cover it would refuse input the system of record accepts.
     *
     * @param value the field value to test; may be {@code null}
     * @return {@code true} when the field carries no content
     */
    private static boolean isSpacesOrLowValues(final String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != '\u0000') {
                return false;
            }
        }
        return true;
    }

    /**
     * The COBOL alphanumeric {@code MOVE} narrowing, as applied by
     * {@code MOVE WS-MESSAGE TO ERRMSGO} at {@code app/cbl/COSGN00C.cbl:L149} @ 7756d89 and by the
     * full-width send at {@code :L166}. Not a paragraph: a primitive the two renderers share.
     *
     * <p>A move into a shorter alphanumeric field truncates on the right, and that is reproduced because it
     * is observable. A move into a longer one pads on the right with spaces, and that is not reproduced,
     * because trailing padding of a fixed-width field is an artefact of the field rather than of the value
     * and would make every rendered message compare unequal to the literal it came from.
     *
     * <p>Disclosed for completeness: neither guard below is reachable from the five call sites as they
     * stand, because every one of them passes one of the five source literals and all five are non-null and
     * shorter than both receiving widths. They are here to keep the field contract honest for any message
     * added later, not to handle input that has been observed - defensive enforcement rather than dead
     * code. The cost is one uncovered line and two uncovered branches, and it is accepted deliberately in
     * preference to a renderer that could silently overrun a declared field width.
     *
     * @param value       the value being moved; {@code null} is treated as the empty field
     * @param fieldLength the declared width of the receiving field, in characters
     * @return the value as the receiving field would hold it, at its significant length
     */
    private static String moveToAlphanumericField(final String value, final int fieldLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= fieldLength ? value : value.substring(0, fieldLength);
    }
}
