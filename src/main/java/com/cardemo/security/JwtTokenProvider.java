/*
 * ******************************************************************
 * Program     : JwtTokenProvider.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 security component
 * Function    : Issues the signed JWT that replaces the CICS COMMAREA
 *               as the carrier of identity.
 * Source      : app/cpy/COCOM01Y.cpy:L25 (CDEMO-USER-ID -> subject),
 *               :L26-L28 (CDEMO-USER-TYPE 'A'/'U' -> role) @ 7756d89
 * Source      : app/cbl/COSGN00C.cbl:L226-L228 (claim establishment),
 *               :L231-L239 (user-type XCTL routing) @ 7756d89
 * Source      : app/cbl/COMEN01C.cbl:L149-L150 (identity MOVEs
 *               commented out - identity persists in the COMMAREA),
 *               :L152-L155 (XCTL + COMMAREA) @ 7756d89
 * Source      : app/csd/CARDDEMO.CSD:L378 (DEFINE TRANSACTION(CC00)
 *               -> COSGN00C) @ 7756d89
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
package com.cardemo.security;

import com.cardemo.model.enums.UserType;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * Issues the signed JSON Web Token that replaces the CICS COMMAREA as the carrier of identity between
 * requests, by translating the two identity fields of {@code 01 CARDDEMO-COMMAREA} into token claims.
 *
 * <h2>Why a bearer token is the faithful replacement, not merely a convenient one</h2>
 *
 * <p>The legacy application is pseudo-conversational CICS: screen and identity state travel in the
 * COMMAREA, declared in the sign-on program's linkage section as a variable length byte array at
 * {@code app/cbl/COSGN00C.cbl:L65-L67} - {@code LK-COMMAREA PIC X(01) OCCURS 1 TO 32767 TIMES DEPENDING ON
 * EIBCALEN} - and handed back to the terminal by the {@code EXEC CICS RETURN} carrying {@code TRANSID},
 * {@code COMMAREA} and {@code LENGTH} at {@code :L98-L102}.
 *
 * <p>The decisive evidence for this design sits in {@code app/cbl/COMEN01C.cbl}. At {@code :L149-L150} the
 * two MOVEs that would re-establish the signed-on user on each program transfer are
 * <strong>commented out</strong>, while the MOVEs that surround them at {@code :L147-L148} and {@code :L151}
 * are live. Identity is therefore established exactly once, on the successful sign-on branch at
 * {@code app/cbl/COSGN00C.cbl:L226-L227}, and thereafter simply rides along inside the COMMAREA across every
 * {@code EXEC CICS XCTL} - there are four such sites, at {@code app/cbl/COSGN00C.cbl:L231} and {@code :L236}
 * and at {@code app/cbl/COMEN01C.cbl:L153} and {@code :L176} - without being re-derived and
 * <strong>without re-reading the {@code USRSEC} security file</strong>.
 *
 * <p>That is precisely the semantic of a signed bearer token: authenticate once against the security file,
 * then carry a self-describing, tamper-evident credential that every later request presents. The token is
 * consequently not an approximation of the COMMAREA handshake but its exact behavioural analogue, and this
 * class is the one place the credential is minted.
 *
 * <h2>The COMMAREA identity split</h2>
 *
 * <p>{@code app/cpy/COCOM01Y.cpy} is 47 lines; its Apache banner occupies {@code L1-L18} and the structure
 * {@code L19-L44}. Only two of its fields become claims. Every other field either has no counterpart in a
 * stateless protocol or is prohibited from a token outright.
 *
 * <table>
 *   <caption>Disposition of every {@code 01 CARDDEMO-COMMAREA} field</caption>
 *   <tr><th>COMMAREA field</th><th>Locator</th><th>Target</th></tr>
 *   <tr>
 *     <td>{@code CDEMO-USER-ID PIC X(08)}</td>
 *     <td>{@code COCOM01Y.cpy:L25}</td>
 *     <td>token <strong>subject</strong> claim</td>
 *   </tr>
 *   <tr>
 *     <td>{@code CDEMO-USER-TYPE PIC X(01)}, with {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} and
 *         {@code 88 CDEMO-USRTYP-USER VALUE 'U'}</td>
 *     <td>{@code COCOM01Y.cpy:L26-L28}</td>
 *     <td><strong>role</strong> claim driving access control: {@code 'A'} becomes {@value #ADMIN_AUTHORITY}
 *         and {@code 'U'} becomes {@value #USER_AUTHORITY}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code CDEMO-FROM-TRANID}, {@code CDEMO-FROM-PROGRAM}, {@code CDEMO-TO-TRANID},
 *         {@code CDEMO-TO-PROGRAM}</td>
 *     <td>{@code :L21-L24}</td>
 *     <td><strong>No equivalent.</strong> Routing is URL based, so no claim carries a transaction or program
 *         name</td>
 *   </tr>
 *   <tr>
 *     <td>{@code CDEMO-PGM-CONTEXT PIC 9(01)}, with {@code 88 CDEMO-PGM-ENTER VALUE 0} and
 *         {@code 88 CDEMO-PGM-REENTER VALUE 1}</td>
 *     <td>{@code :L29-L31}</td>
 *     <td><strong>No equivalent.</strong> The pseudo-conversational enter versus re-enter flag collapses into
 *         stateless request handling; {@code app/cbl/COSGN00C.cbl:L228} resets it, and that reset has no Java
 *         counterpart</td>
 *   </tr>
 *   <tr>
 *     <td>{@code CDEMO-CUST-ID}, {@code CDEMO-CUST-FNAME}, {@code CDEMO-CUST-MNAME},
 *         {@code CDEMO-CUST-LNAME}, {@code CDEMO-ACCT-ID}, {@code CDEMO-ACCT-STATUS},
 *         {@code CDEMO-CARD-NUM}</td>
 *     <td>{@code :L33-L41}</td>
 *     <td><strong>Prohibited as claims</strong> - see the minimal claim set below. Their designated target is
 *         the request and response types of {@code com.cardemo.model.dto}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code CDEMO-LAST-MAP PIC X(7)}, {@code CDEMO-LAST-MAPSET PIC X(7)}</td>
 *     <td>{@code :L43-L44}</td>
 *     <td><strong>No equivalent.</strong> No screen state is retained</td>
 *   </tr>
 * </table>
 *
 * <p>The copybook's five groups are exactly {@code CDEMO-GENERAL-INFO} at {@code L20-L31},
 * {@code CDEMO-CUSTOMER-INFO} at {@code L32-L36}, {@code CDEMO-ACCOUNT-INFO} at {@code L37-L39},
 * {@code CDEMO-CARD-INFO} at {@code L40-L41} and {@code CDEMO-MORE-INFO} at {@code L42-L44}. It holds
 * <strong>no page number field and no next-page flag</strong>: pagination is a separate concern owned by
 * {@code com.cardemo.model.dto.PageResponse} and is deliberately not represented here.
 *
 * <h2>Where the two claim values come from in the source</h2>
 *
 * <p>Both are established inside {@code READ-USER-SEC-FILE} at {@code app/cbl/COSGN00C.cbl:L209-L257}, on the
 * branch where the security file read returned zero and the stored password matched.
 *
 * <ul>
 *   <li><strong>Subject</strong>, {@code :L226}. The value moved is {@code WS-USER-ID}, which already holds
 *       the <strong>upper-cased</strong> identifier: {@code :L132-L134} is a single
 *       {@code MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI)} with <em>two</em> receiving fields, so the
 *       folded identifier lands in {@code CDEMO-USER-ID} at {@code :L134} and again at {@code :L226}. The
 *       subject claim is therefore the upper-cased identifier. <strong>This class does not fold case.</strong>
 *       That folding is owned by {@code com.cardemo.security.CardDemoUserDetailsService}, which reproduces
 *       {@code :L132-L136} by upper-casing both the identifier and the password before comparison; repeating
 *       it here would be the duplication Rule 1 Clause C forbids. A consequence worth stating plainly: this
 *       class performs no case conversion and no {@code String.format}, so it contains no locale sensitive
 *       operation of any kind and its output cannot vary with the platform default locale, charset or time
 *       zone.</li>
 *   <li><strong>Role</strong>, {@code :L227}, taken straight from {@code SEC-USR-TYPE} -
 *       {@code app/cpy/CSUSR01Y.cpy:L22}, {@code PIC X(01)}, byte 57 of the 80 byte record.</li>
 *   <li><strong>The role claim's purpose</strong>, {@code :L230-L240}. The legacy program branches on
 *       {@code CDEMO-USRTYP-ADMIN} and transfers an administrator to {@code COADM01C}, the admin menu, and a
 *       standard user to {@code COMEN01C}, the main menu. In the target that navigation is URL based; the
 *       role claim no longer routes, it only <em>authorises</em>.</li>
 *   </ul>
 *
 * <h2>The minimal claim set - a Blocker level constraint</h2>
 *
 * <p><strong>A JWT is signed, not encrypted.</strong> Its payload is base64url and is readable by anyone who
 * holds the token, and tokens travel through proxies, access logs, browser storage and error reports. The
 * COMMAREA carries personally identifiable and financially sensitive data at {@code COCOM01Y.cpy:L33-L41} -
 * a customer identifier, three customer name fields, an account identifier, an account status and a
 * {@code PIC 9(16)} card number. <strong>None of them may ever become a claim.</strong>
 *
 * <p>The claim set this class emits is exactly, and only:
 *
 * <ol>
 *   <li>the <strong>subject</strong>, from {@code CDEMO-USER-ID} at {@code COCOM01Y.cpy:L25};</li>
 *   <li>the <strong>role</strong>, from {@code CDEMO-USER-TYPE} at {@code :L26-L28}, under the claim name
 *       {@value #ROLE_CLAIM_NAME};</li>
 *   <li>the registered claims that make the token valid and expiring: <strong>issuer</strong>,
 *       <strong>issued-at</strong> and <strong>expiry</strong>.</li>
 *   </ol>
 *
 * <p>Five claims, no more. Enriching the token with a card number, an account identifier, a customer
 * identifier or a customer name is a <strong>Blocker</strong> under Rule 1 Clause D, which forbids secrets in
 * code, logs, tests or configuration and requires least privilege for tokens: it would publish regulated data
 * to every party that ever sees the token, for the whole of its lifetime, with no way to retract it. A future
 * contributor reading this paragraph should treat the prohibition as absolute rather than as a default to be
 * relaxed.
 *
 * <h2>The role claim representation, and the drift hazard it carries</h2>
 *
 * <p>One claim, named {@value #ROLE_CLAIM_NAME}, whose value is the granted authority string itself -
 * {@value #ADMIN_AUTHORITY} or {@value #USER_AUTHORITY}. The spelling of the claim name and of both authority
 * values exists in exactly one place repository-wide: the constants {@link #ROLE_CLAIM_NAME},
 * {@link #ADMIN_AUTHORITY} and {@link #USER_AUTHORITY} on this class. They are deliberately {@code public}
 * for that reason. A private constant would keep the spelling in one place only <em>within this file</em>,
 * which is not where the hazard lives.
 *
 * <p>The hazard is worth naming precisely, because it is <strong>High</strong> severity and it fails
 * silently. If the claim name written here and the claim name read by {@code com.cardemo.config.SecurityConfig}
 * or {@code com.cardemo.security.JwtAuthenticationFilter} ever diverge, the token still verifies, no
 * exception is raised and nothing is logged; the authority set simply comes out empty and every authorised
 * request is refused. Binding those two collaborators to these constants, rather than to their own string
 * literals, removes the failure mode by construction.
 *
 * <p>{@code com.cardemo.model.enums.UserType} deliberately exposes no authority naming of its own - its own
 * test suite asserts that it exposes no {@code ROLE_} prefixed member - so this class is the canonical and
 * only home of the mapping from the one character COMMAREA code to a Spring Security authority.
 *
 * <h2>Ownership boundary - what this class must not duplicate</h2>
 *
 * <p>This class owns <strong>issuance</strong>, and offers read helpers that operate on an
 * <em>already decoded</em> token. It deliberately does not own, and must never redefine:
 *
 * <ul>
 *   <li>the {@code JwtDecoder}. {@code com.cardemo.config.SecurityConfig} builds the symmetric HMAC decoder
 *       from the same signing key property. Defining a competing decoder bean here is a
 *       <strong>High</strong> severity defect: at best the context fails to refresh with a duplicate bean
 *       definition, at worst two decoders disagree about the verification key. If such a failure is ever
 *       observed, the duplicate is removed from <em>this</em> file, because the configuration package is the
 *       designated decoder owner;</li>
 *   <li>the {@code PasswordEncoder}, the security filter chain composition, the role based authorisation
 *       rules and the stateless session policy - all four belong to
 *       {@code com.cardemo.config.SecurityConfig};</li>
 *   <li>credential loading and verification, which belongs to
 *       {@code com.cardemo.security.CardDemoUserDetailsService};</li>
 *   <li>per request authentication, which belongs to
 *       {@code com.cardemo.security.JwtAuthenticationFilter}.</li>
 *   </ul>
 *
 * <p>Four responsibilities, four owners, no overlap - which is Rule 1 Clause A's separation of concerns
 * applied to this package.
 *
 * <h2>Observability</h2>
 *
 * <p>This class registers no meter and emits no log record. The authentication attempts counter is one of
 * exactly four instruments in the target and is owned by {@code com.cardemo.observability.MetricsConfig};
 * incrementing it here would both duplicate the emission performed by
 * {@code com.cardemo.service.auth.AuthenticationService} and undercount, because issuance is reached only on
 * the success path and never on a rejected sign-on. It is therefore deliberately omitted. No logger is
 * declared either: the values in scope here are a signing key, a subject and a token, none of which may be
 * emitted at any level, so the safest instrument count is zero.
 *
 * <h2>Configuration, and the exact spellings this class binds</h2>
 *
 * <p>Three properties are bound, by {@code @Value} on constructor parameters. No other source of
 * configuration is consulted: there is no {@code System.getenv}, no {@code System.getProperty}, no file, no
 * classpath resource and no URL anywhere in this class, because Spring property binding is the only path that
 * is profile aware, testable and free of the environment specific assumptions Rule 1 Clause C forbids.
 *
 * <table>
 *   <caption>Bound properties, as published by {@code src/main/resources/application.yml}</caption>
 *   <tr><th>Property</th><th>Environment variable</th><th>Default</th></tr>
 *   <tr>
 *     <td>{@code carddemo.security.jwt.signing-key}</td>
 *     <td>{@code JWT_SIGNING_KEY}</td>
 *     <td><strong>None. No example, no fallback, no committed literal.</strong></td>
 *   </tr>
 *   <tr>
 *     <td>{@code carddemo.security.jwt.issuer}</td>
 *     <td>{@code JWT_ISSUER}</td>
 *     <td>{@code carddemo} - non secret metadata, so a documented default is acceptable</td>
 *   </tr>
 *   <tr>
 *     <td>{@code carddemo.security.jwt.expiration-minutes}</td>
 *     <td>{@code JWT_EXPIRATION_MINUTES}</td>
 *     <td>{@code 30}</td>
 *   </tr>
 * </table>
 *
 * <p>Both of those spellings once differed from the mandated ones. This class first bound
 * {@code JWT_SECRET} and {@code carddemo.security.jwt.expiration-seconds} with a 3,600-second default,
 * because that is what {@code src/main/resources/application.yml} published at the time and binding anything
 * else would have been an unresolvable placeholder aborting every context refresh. The drift has since been
 * closed at its root: the published file now reads {@code ${JWT_SIGNING_KEY}} with no default and
 * {@code expiration-minutes: ${JWT_EXPIRATION_MINUTES:30}}, and the rename was carried through this class,
 * {@code com.cardemo.config.SecurityConfig}, the environment template, the container image documentation, the
 * build file, the vulnerability-scan suppressions and the unit test that asserts the variable name, in one
 * change. Severity of the original discrepancy: <strong>High</strong>, because the unit was not cosmetic -
 * 3,600 seconds is double the approved thirty-minute bearer window, and a bearer token cannot be revoked
 * before it expires.
 *
 * <p>Note also that {@code src/main/resources} deliberately sets neither
 * {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri} nor the corresponding public key location,
 * precisely because a symmetric HMAC key has no built-in Boot property and the decoder is constructed in
 * Java. Emitting either would be unreachable configuration, which Rule 1 Clause B forbids as dead code.
 *
 * <h2>Fail-fast on the signing key</h2>
 *
 * <p>The signing key is environment indirected with no default of any kind, and this class refuses to be
 * constructed when the supplied value cannot securely sign a token. Because it is a singleton bean, that
 * refusal aborts the context refresh: the application does not start. Four cases are distinguished so that an
 * operator is told what to fix rather than merely that something is wrong - {@code null}, empty,
 * whitespace-only, and shorter than the 32 bytes that {@link MacAlgorithm#HS256} requires.
 *
 * <p>Validating the length here, rather than letting the MAC implementation reject it, is the whole point of
 * the fourth case: the algorithm's own check does not fire until a token is actually signed, which is the
 * first sign-on attempt - in production, potentially long after deployment. Checking in the constructor
 * converts a latent runtime failure into a deterministic startup failure.
 *
 * <p><strong>No failure message contains any part of the key</strong> - not the value, not a prefix, not a
 * suffix, not the observed length and not a digest of it. Each message names the property, names the
 * environment variable and states the remedy, and nothing else. A missing {@code JWT_SIGNING_KEY} is therefore an
 * expected and documented startup failure rather than a bug; the operator facing remedy is summarised for the
 * whole package in {@code package-info.java}.
 *
 * <p>This closes a <strong>High</strong> severity defect recorded against the previous migration attempt,
 * which hardcoded the signing key. <strong>High (closed).</strong> The token lifetime is likewise bounded
 * rather than trusted: a non positive value is rejected, and so is one above 1440 minutes, which is 24
 * hours, because Rule 1 Clause D's least privilege requirement is what makes "short lived" enforceable
 * instead of merely aspirational rather than a comment nobody checks.
 *
 * <h2>Not available</h2>
 *
 * <p>Rule 1 Clause F requires that missing information be stated rather than guessed. At the time this class
 * was written {@code src/main/java/com/cardemo/config} did not exist, so the following are contracts asserted
 * from the specification and <strong>not</strong> verified against code:
 *
 * <ul>
 *   <li>the construction of the symmetric HMAC {@code JwtDecoder} from
 *       {@code carddemo.security.jwt.signing-key};</li>
 *   <li>the converter configuration that turns the {@value #ROLE_CLAIM_NAME} claim into granted authorities -
 *       specifically, that the authorities claim name is set to {@value #ROLE_CLAIM_NAME} and the authority
 *       prefix to the empty string, so that the claim value is used verbatim rather than being prefixed a
 *       second time;</li>
 *   <li>the {@code PasswordEncoder} strength and the stateless session policy.</li>
 * </ul>
 *
 * <p>What is needed to confirm them: {@code src/main/java/com/cardemo/config/SecurityConfig.java}. Until it
 * exists, the constants on this class are the contract, and the collaborator is expected to bind to them.
 *
 * <h2>Severity register</h2>
 *
 * <p>Rule 1 Clause F requires findings to be classified. The ones that bear on this file are:
 *
 * <table>
 *   <caption>Severity of the failure modes this class is designed to prevent</caption>
 *   <tr><th>Severity</th><th>Finding</th><th>Status</th></tr>
 *   <tr>
 *     <td><strong>Blocker</strong></td>
 *     <td>Emitting any of the personally identifiable or card holder fields at
 *         {@code app/cpy/COCOM01Y.cpy:L33-L41} as a token claim</td>
 *     <td>Prevented: the claim set is fixed at five and is asserted by test</td>
 *   </tr>
 *   <tr>
 *     <td><strong>Blocker</strong></td>
 *     <td>A committed, defaulted or fallback signing key</td>
 *     <td>Prevented: the property carries no default and the constructor refuses four invalid shapes</td>
 *   </tr>
 *   <tr>
 *     <td><strong>High</strong></td>
 *     <td>The signing key hardcoded, as in the previous migration attempt</td>
 *     <td><strong>Closed</strong> by environment indirection plus fail-fast</td>
 *   </tr>
 *   <tr>
 *     <td><strong>High</strong></td>
 *     <td>Role claim name drift between this class and its two collaborators, which denies authorisation
 *         silently</td>
 *     <td>Mitigated: one public constant is the single spelling for the whole repository</td>
 *   </tr>
 *   <tr>
 *     <td><strong>High</strong></td>
 *     <td>A competing {@code JwtDecoder} bean defined in this class</td>
 *     <td>Prevented: no bean method is declared here at all</td>
 *   </tr>
 *   <tr>
 *     <td><strong>Medium</strong></td>
 *     <td>Two property spellings in this file's generation instructions that do not match the published
 *         {@code application.yml}</td>
 *     <td>Resolved in favour of the published file and recorded above</td>
 *   </tr>
 *   <tr>
 *     <td><strong>Low</strong></td>
 *     <td>A consumer reading the issuer through an accessor that coerces it to a {@code java.net.URL}, when
 *         the default issuer is an opaque identifier</td>
 *     <td>Documented under troubleshooting</td>
 *   </tr>
 * </table>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>The context refuses to refresh, reporting an unresolvable placeholder for
 *       {@code carddemo.security.jwt.signing-key} or for {@code JWT_SIGNING_KEY}.</em> The variable is
 *       absent.
 *       This is intended behaviour. Export a key of at least 32 bytes of entropy; {@code .env.example}
 *       documents how to generate one. Never add a default to silence it.</li>
 *   <li><em>The context refuses to refresh with a message about a blank or too short signing key.</em> The
 *       variable is present but empty, whitespace, or below the algorithm minimum. Same remedy.</li>
 *   <li><em>Every request returns 401 even though the token verifies and looks correct.</em> The classic
 *       cause is a role claim name drift between this class and {@code com.cardemo.config.SecurityConfig} or
 *       {@code com.cardemo.security.JwtAuthenticationFilter}: the authority set comes out empty and nothing
 *       is logged. Confirm both collaborators reference {@link #ROLE_CLAIM_NAME} rather than a literal.</li>
 *   <li><em>Authorisation admits a standard user to an administrator endpoint, or refuses an administrator.</em>
 *       Confirm the authority prefix on the collaborator's converter is empty. If it prefixes the value
 *       again, the granted authority becomes a doubled string and matches nothing.</li>
 *   <li><em>The context fails with a duplicate bean definition for {@code JwtDecoder}.</em> Something has
 *       added a decoder here; remove it, because the configuration package owns it.</li>
 *   <li><em>A consumer sees a malformed URL error when reading the issuer.</em> The default issuer is an
 *       opaque identifier, not a URL. {@code org.springframework.security.oauth2.jwt.JwtClaimAccessor} coerces
 *       the issuer claim to a {@code java.net.URL}, so the issuer must be compared as a raw string
 *       instead.</li>
 *   </ul>
 *
 * <p>Build and test with {@code ./mvnw -B -ntp clean verify}; the unit tests for this class live under
 * {@code src/test/java/com/cardemo/unit}, never in this package, and construct it with a locally generated
 * key rather than a committed one.
 *
 * <p>Instances are immutable once constructed and hold no mutable state, static or otherwise, so they are
 * inherently thread safe. The encoder is built once in the constructor and reused for every issuance.
 *
 * @see #issueToken(String, UserType)
 * @see com.cardemo.model.enums.UserType
 */
@Component
public final class JwtTokenProvider {

    /**
     * Name of the one custom claim this class emits: the role, derived from {@code CDEMO-USER-TYPE} at
     * {@code app/cpy/COCOM01Y.cpy:L26-L28}.
     *
     * <p>Deliberately {@code public}, so that the spelling exists in exactly one place across the whole
     * repository. {@code com.cardemo.config.SecurityConfig} must configure its authorities converter with
     * this claim name and an empty authority prefix, and
     * {@code com.cardemo.security.JwtAuthenticationFilter} must read this claim name, both by referencing
     * this constant rather than a literal of their own. A divergence between the two spellings is a
     * <strong>High</strong> severity defect that manifests as a silent authorisation denial with no error and
     * no log record.
     */
    public static final String ROLE_CLAIM_NAME = "role";

    /**
     * The granted authority carried by the {@value #ROLE_CLAIM_NAME} claim for an administrator, that is for
     * {@code CDEMO-USER-TYPE} holding {@code 'A'} per the condition name {@code CDEMO-USRTYP-ADMIN} at
     * {@code app/cpy/COCOM01Y.cpy:L27}.
     *
     * <p>This is the authority string in full, prefix included, because the claim value is used verbatim as
     * the granted authority. It corresponds to the legacy branch at {@code app/cbl/COSGN00C.cbl:L230-L234},
     * which transferred an administrator to {@code COADM01C}.
     */
    public static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    /**
     * The granted authority carried by the {@value #ROLE_CLAIM_NAME} claim for a standard user, that is for
     * {@code CDEMO-USER-TYPE} holding {@code 'U'} per the condition name {@code CDEMO-USRTYP-USER} at
     * {@code app/cpy/COCOM01Y.cpy:L28}.
     *
     * <p>It corresponds to the legacy else branch at {@code app/cbl/COSGN00C.cbl:L235-L239}, which
     * transferred a standard user to {@code COMEN01C}, and to the admin-only menu guard at
     * {@code app/cbl/COMEN01C.cbl:L136-L143}.
     */
    public static final String USER_AUTHORITY = "ROLE_USER";

    /**
     * Property key holding the HMAC signing key. Published by {@code src/main/resources/application.yml} as
     * an indirection to the environment with no default of any kind.
     */
    private static final String SIGNING_KEY_PROPERTY = "carddemo.security.jwt.signing-key";

    /**
     * The one environment variable name used repository-wide for the signing key. Named in failure messages
     * so that an operator is told what to set; its value is never named anywhere.
     *
     * <p>This is the only spelling honoured. No alias is read, and {@link #SIGNING_KEY_PROPERTY} carries no
     * default in any profile, so a deployment that sets some other variable fails to start rather than
     * starting on an unintended key.
     */
    private static final String SIGNING_KEY_VARIABLE = "JWT_SIGNING_KEY";

    /** Property key holding the token issuer, an opaque identifier rather than a URL. */
    private static final String ISSUER_PROPERTY = "carddemo.security.jwt.issuer";

    /** Environment variable behind {@link #ISSUER_PROPERTY}, which does carry a documented default. */
    private static final String ISSUER_VARIABLE = "JWT_ISSUER";

    /**
     * Property key holding the token lifetime <strong>in minutes</strong>.
     *
     * <p>Minutes, not seconds, and the unit is part of the contract rather than a presentation choice: the
     * approved bearer lifetime is thirty minutes, and expressing it in seconds is how a 3,600-second default
     * came to double it. The value is converted to a {@link Duration} exactly once, in
     * {@link #validatedLifetime(long)}, so no other member of this class handles a raw number.
     */
    private static final String LIFETIME_PROPERTY = "carddemo.security.jwt.expiration-minutes";

    /** Environment variable behind {@link #LIFETIME_PROPERTY}, which does carry a documented default. */
    private static final String LIFETIME_VARIABLE = "JWT_EXPIRATION_MINUTES";

    /**
     * The JWS algorithm. HS256 is chosen because both {@code .env.example} and
     * {@code src/main/resources/application.yml} document the required key strength as at least 32 bytes,
     * which is exactly this algorithm's 256 bit floor; a stronger MAC would silently invalidate every key
     * generated to the documented minimum.
     */
    private static final MacAlgorithm SIGNATURE_ALGORITHM = MacAlgorithm.HS256;

    /** Minimum key length in bytes that {@link #SIGNATURE_ALGORITHM} requires: 256 bits. */
    private static final int MINIMUM_SIGNING_KEY_BYTES = 32;

    /** A token must outlive its own issuance by at least one minute to be usable at all. */
    private static final long MINIMUM_LIFETIME_MINUTES = 1L;

    /**
     * Ceiling on the configured lifetime, 24 hours expressed in minutes. A deliberate least privilege policy
     * under Rule 1 Clause D: a bearer token cannot be revoked before it expires, so an unbounded lifetime
     * would turn a single leaked token into indefinite access. The approved default is thirty minutes; this
     * is the outer bound an operator may deliberately choose, not a recommendation.
     */
    private static final long MAXIMUM_LIFETIME_MINUTES = 1_440L;

    /**
     * Maximum length of the subject claim, from {@code CDEMO-USER-ID PIC X(08)} at
     * {@code app/cpy/COCOM01Y.cpy:L25} and the matching {@code SEC-USR-ID PIC X(08)} at
     * {@code app/cpy/CSUSR01Y.cpy}. Enforcing it preserves the field contract in both directions: a longer
     * identifier is not representable in the legacy record, so accepting one would let the target diverge
     * from its own oracle.
     */
    private static final int MAXIMUM_SUBJECT_LENGTH = 8;

    /** Signs every issued token. Built once, in the constructor, and reused for every issuance. */
    private final JwtEncoder jwtEncoder;

    /** Value of the issuer claim, validated non blank at construction. */
    private final String issuer;

    /** Configured token lifetime, validated at construction and added to the issued-at instant. */
    private final Duration tokenLifetime;

    /** Injected time source, so that the issued-at and expiry claims are deterministic under test. */
    private final Clock clock;

    /**
     * Validates the security configuration and builds the signer, refusing construction when the signing key
     * cannot securely sign a token.
     *
     * <p>Because this is a singleton bean, a refusal here aborts the context refresh and the application does
     * not start. That is the intended behaviour and closes the <strong>High</strong> severity defect of the
     * previous migration attempt, which hardcoded the key.
     *
     * <p>The key is consumed and discarded: it is encoded to UTF-8, handed to the signer, and the derived
     * byte array is then overwritten with zeroes. The raw {@code String} is never retained in a field, never
     * exposed by an accessor and never appears in a message. Overwriting the array removes the one copy this
     * constructor creates - the bound {@code String} still lives in the Spring environment, so this is
     * defence in depth rather than a guarantee, and it is honest to say so.
     *
     * <p>Side effects: none beyond constructing the signer. Nothing is logged, no meter is registered, no
     * I/O is performed and no static state is touched.
     *
     * @param signingKey      the HMAC signing key, bound from {@code carddemo.security.jwt.signing-key},
     *                        which resolves {@code JWT_SIGNING_KEY} with no default
     * @param issuer          the issuer claim value, bound from {@code carddemo.security.jwt.issuer}, which
     *                        resolves {@code JWT_ISSUER} and defaults to {@code carddemo}
     * @param lifetimeMinutes the token lifetime in minutes, bound from
     *                        {@code carddemo.security.jwt.expiration-minutes}, which resolves
     *                        {@code JWT_EXPIRATION_MINUTES} and defaults to {@code 30}
     * @param clock           the time source for the issued-at and expiry claims, injected from the one
     *                        production definition in the tree,
     *                        {@code com.cardemo.config.ObservabilityConfig#clock()}, which publishes
     *                        {@code Clock.systemDefaultZone()}. The zone is the deployment's rather than UTC
     *                        because the legacy region rendered local civil time and Gate 1 compares the
     *                        rendered values byte-for-byte; the claims themselves are instants, so this
     *                        class's own output is unaffected by the choice. A test injects
     *                        {@code Clock.fixed(...)} to make expiry deterministic
     * @throws IllegalStateException if the signing key is {@code null}, empty, whitespace only, or shorter
     *                               than 32 bytes; if the issuer is {@code null} or blank; if the lifetime is
     *                               below one minute or above 1440 minutes; or if no clock was supplied. No
     *                               such message reveals any part of the signing key
     */
    public JwtTokenProvider(
            @Value("${" + SIGNING_KEY_PROPERTY + "}") final String signingKey,
            @Value("${" + ISSUER_PROPERTY + "}") final String issuer,
            @Value("${" + LIFETIME_PROPERTY + "}") final long lifetimeMinutes,
            final Clock clock) {

        this.jwtEncoder = macSigner(signingKey);
        this.issuer = validatedIssuer(issuer);
        this.tokenLifetime = validatedLifetime(lifetimeMinutes);
        this.clock = validatedClock(clock);
    }

    /**
     * Issues a signed token carrying the two COMMAREA identity values, replacing the
     * {@code EXEC CICS RETURN ... COMMAREA} handshake of {@code app/cbl/COSGN00C.cbl:L98-L102} with a
     * self-describing bearer credential.
     *
     * <p>This is the Java counterpart of the successful sign-on branch at
     * {@code app/cbl/COSGN00C.cbl:L221-L228}, inside {@code READ-USER-SEC-FILE} at {@code :L209-L257}: where
     * the legacy program moved the identifier and the user type into the COMMAREA and transferred control, this
     * method places the same two values into a signed token and returns it.
     *
     * <p>The emitted claim set is exactly five, and no mechanism here can widen it: the issuer, the subject,
     * the issued-at instant, the expiry instant, and {@value #ROLE_CLAIM_NAME}. Nothing derived from
     * {@code CDEMO-CUST-ID}, {@code CDEMO-CUST-FNAME}, {@code CDEMO-CUST-MNAME}, {@code CDEMO-CUST-LNAME},
     * {@code CDEMO-ACCT-ID}, {@code CDEMO-ACCT-STATUS} or {@code CDEMO-CARD-NUM} is present, because a signed
     * token is readable by anyone holding it.
     *
     * <p>The identifier is expected to arrive <strong>already upper-cased</strong>, as
     * {@code app/cbl/COSGN00C.cbl:L132-L134} produces it and as
     * {@code com.cardemo.security.CardDemoUserDetailsService} reproduces it. This method does not fold case,
     * so it neither duplicates that behaviour nor depends on the platform default locale.
     *
     * <p>Side effects: none. Nothing is persisted, nothing is logged and no state is mutated; the only result
     * is the returned string. Configuration consumed: the issuer, lifetime and signing key bound at
     * construction. The returned value is the raw compact serialisation with no {@code Bearer} prefix, since
     * scheme prefixing is the transport's concern.
     *
     * <p>Troubleshooting: if a caller reports that a token is valid yet every request is refused, the usual
     * cause is a role claim name drift in a collaborator rather than a defect here - see
     * {@link #ROLE_CLAIM_NAME}.
     *
     * @param userId   the authenticated user identifier that becomes the subject claim, already upper-cased,
     *                 not blank, and no longer than the 8 characters
     *                 {@code CDEMO-USER-ID PIC X(08)} can hold
     * @param userType the authenticated user class that becomes the role claim, from
     *                 {@code CDEMO-USER-TYPE} at {@code app/cpy/COCOM01Y.cpy:L26-L28}
     * @return the signed token in compact serialised form, never {@code null} and never blank
     * @throws IllegalArgumentException if {@code userId} is {@code null}, blank, or longer than 8 characters,
     *                                 or if {@code userType} is {@code null}. The messages describe the defect
     *                                 without echoing the offending value, so that neither an identity nor a
     *                                 control character can reach a log sink through them
     * @throws IllegalStateException    if signing fails; the cause is preserved and neither the key nor the
     *                                  token appears in the message
     */
    public String issueToken(final String userId, final UserType userType) {
        final String subject = validatedSubject(userId);
        final String authority = authorityFor(userType);

        final Instant issuedAt = this.clock.instant();
        final Instant expiresAt = issuedAt.plus(this.tokenLifetime);

        final JwsHeader header = JwsHeader.with(SIGNATURE_ALGORITHM).build();
        final JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(this.issuer)
                .subject(subject)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim(ROLE_CLAIM_NAME, authority)
                .build();

        try {
            return this.jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        } catch (final JwtEncodingException cause) {
            throw new IllegalStateException(
                    "Failed to sign a " + SIGNATURE_ALGORITHM.getName() + " token for an authenticated "
                            + "sign-on. The signing key, the claim values and the partially built token are "
                            + "deliberately withheld from this message; the preserved cause carries the "
                            + "technical detail.",
                    cause);
        }
    }

    /**
     * Maps a user class onto the granted authority that the {@value #ROLE_CLAIM_NAME} claim carries.
     *
     * <p>This is the target of the legacy user-type branch at {@code app/cbl/COSGN00C.cbl:L230-L240}, which
     * tested the condition name {@code CDEMO-USRTYP-ADMIN} to choose between the admin menu program and the
     * main menu program. In the target that navigation is URL based, so the mapping no longer routes anything;
     * it only supplies the value authorisation is decided on.
     *
     * <p>This class is the canonical home of the mapping. {@code com.cardemo.model.enums.UserType}
     * deliberately exposes no authority naming of its own, which is what keeps that enum free of any Spring
     * dependency, so callers needing the authority for a user class should call this method rather than
     * assembling the string themselves.
     *
     * <p>The implementation is an exhaustive switch over the enum with <strong>no default branch</strong>.
     * That is deliberate and load bearing: {@code app/cpy/COCOM01Y.cpy:L26-L28} declares exactly two condition
     * names, and should a third constant ever be added to the enum this method stops compiling instead of
     * silently granting a wrong authority. There is likewise no permissive fallback for an unknown class.
     *
     * <p>Side effects: none. This is a pure function apart from the exception it may raise.
     *
     * @param userType the user class to map, from {@code CDEMO-USER-TYPE}
     * @return {@value #ADMIN_AUTHORITY} for an administrator or {@value #USER_AUTHORITY} for a standard user,
     *         never {@code null}
     * @throws IllegalArgumentException if {@code userType} is {@code null}, because an absent user class must
     *                                 never default to either authority
     */
    public String authorityFor(final UserType userType) {
        if (userType == null) {
            throw new IllegalArgumentException(
                    "No user class was supplied, so no " + ROLE_CLAIM_NAME + " claim can be derived. "
                            + "app/cpy/COCOM01Y.cpy:L26-L28 declares exactly two condition names on "
                            + "CDEMO-USER-TYPE - 'A' for an administrator and 'U' for a standard user - and "
                            + "there is deliberately no permissive default, because defaulting an unknown "
                            + "user class to either authority would grant access on the strength of missing "
                            + "information.");
        }
        return switch (userType) {
            case ADMIN -> ADMIN_AUTHORITY;
            case USER -> USER_AUTHORITY;
        };
    }

    /**
     * Reads the authenticated user identifier back out of a token that has <strong>already been decoded and
     * verified</strong>, so that a caller need not know the subject claim's name.
     *
     * <p>This is the read side of the identity that {@code app/cbl/COMEN01C.cbl:L149-L150} shows the legacy
     * system never re-derived: the value simply travelled forward. It constructs no decoder and performs no
     * signature check - verification is complete before a {@code Jwt} exists, and the decoder belongs to
     * {@code com.cardemo.config.SecurityConfig}.
     *
     * <p>Side effects: none. Nothing is logged, so a subject value cannot leak into telemetry through this
     * method.
     *
     * @param token a decoded and verified token
     * @return the subject claim, which is the upper-cased user identifier, never {@code null} and never blank
     * @throws IllegalArgumentException if {@code token} is {@code null}, or carries no usable subject claim.
     *                                 The message does not echo the token or any claim value
     */
    public String extractUserId(final Jwt token) {
        if (token == null) {
            throw new IllegalArgumentException(
                    "No decoded token was supplied, so no subject claim can be read. Decode and verify the "
                            + "token first; this method never verifies a signature and never constructs a "
                            + "decoder.");
        }
        final String subject = token.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException(
                    "The decoded token carries no usable subject claim, so the caller cannot be identified. "
                            + "Every token this application issues sets it from CDEMO-USER-ID at "
                            + "app/cpy/COCOM01Y.cpy:L25, so a token without one was not issued by "
                            + "JwtTokenProvider.");
        }
        return subject;
    }

    /**
     * Reads the user class back out of a token that has <strong>already been decoded and verified</strong>,
     * inverting {@link #authorityFor(UserType)}.
     *
     * <p>Intended for {@code com.cardemo.security.JwtAuthenticationFilter}, which needs the caller's class to
     * build an authentication. It constructs no decoder and performs no signature check.
     *
     * <p>An absent or unrecognised claim is a hard failure rather than a defaulted role. That is Rule 1
     * Clause A's prohibition on unsafe defaults applied at the point it matters most: quietly treating an
     * unreadable role as a standard user would grant access on the strength of missing information, and
     * quietly treating it as an administrator would be worse.
     *
     * <p>Side effects: none. The rejected claim value is deliberately not echoed. A claim reaches this method
     * from outside the process, so interpolating it verbatim would let a carriage return or line feed inside
     * it terminate the current record and forge a following one in any sink that stores one event per line.
     * Naming the two values that <em>are</em> accepted is strictly more useful to a diagnostician anyway.
     *
     * @param token a decoded and verified token
     * @return the user class the role claim denotes, never {@code null}
     * @throws IllegalArgumentException if {@code token} is {@code null}, or if its
     *                                 {@value #ROLE_CLAIM_NAME} claim is absent or is not one of the two
     *                                 authorities this class issues
     */
    public UserType extractUserType(final Jwt token) {
        if (token == null) {
            throw new IllegalArgumentException(
                    "No decoded token was supplied, so no " + ROLE_CLAIM_NAME + " claim can be read. Decode "
                            + "and verify the token first; this method never verifies a signature and never "
                            + "constructs a decoder.");
        }
        final String authority = token.getClaimAsString(ROLE_CLAIM_NAME);
        if (ADMIN_AUTHORITY.equals(authority)) {
            return UserType.ADMIN;
        }
        if (USER_AUTHORITY.equals(authority)) {
            return UserType.USER;
        }
        throw new IllegalArgumentException(
                "The decoded token carries no recognised \"" + ROLE_CLAIM_NAME + "\" claim. The only values "
                        + "this application issues are " + ADMIN_AUTHORITY + " and " + USER_AUTHORITY
                        + ", derived from the two condition names on CDEMO-USER-TYPE at "
                        + "app/cpy/COCOM01Y.cpy:L26-L28. The rejected value is withheld from this message so "
                        + "that a control character in a claim cannot forge a log entry, and there is "
                        + "deliberately no default role.");
    }

    /**
     * Validates the signing key and builds the signer from it, then erases the derived key material.
     *
     * <p>Four rejection cases are distinguished on purpose, so that an operator reading a startup failure is
     * told which of them applies. The length check exists because the MAC implementation would not reject a
     * short key until a token was actually signed - the first sign-on attempt - which would turn a
     * configuration mistake into an intermittent production failure instead of a startup one.
     *
     * @param signingKey the bound signing key
     * @return a signer for {@link #SIGNATURE_ALGORITHM}, never {@code null}
     * @throws IllegalStateException if the key is {@code null}, empty, whitespace only, or shorter than
     *                               {@link #MINIMUM_SIGNING_KEY_BYTES} bytes when encoded as UTF-8
     */
    private static JwtEncoder macSigner(final String signingKey) {
        if (signingKey == null) {
            throw signingKeyRejected("resolved to null");
        }
        if (signingKey.isEmpty()) {
            throw signingKeyRejected("resolved to an empty value");
        }
        if (signingKey.isBlank()) {
            throw signingKeyRejected("resolved to whitespace only");
        }

        // Derived here and erased in the finally block below. ImmutableSecret base64url-encodes the array
        // immediately rather than retaining it, so erasing afterwards cannot corrupt the signer.
        final byte[] keyMaterial = signingKey.getBytes(StandardCharsets.UTF_8);
        try {
            if (keyMaterial.length < MINIMUM_SIGNING_KEY_BYTES) {
                throw signingKeyRejected("is shorter than the " + MINIMUM_SIGNING_KEY_BYTES
                        + " bytes that " + SIGNATURE_ALGORITHM.getName() + " requires");
            }
            return new NimbusJwtEncoder(new ImmutableSecret<>(keyMaterial));
        } finally {
            Arrays.fill(keyMaterial, (byte) 0);
        }
    }

    /**
     * Builds the one rejection message shape used for every signing key defect.
     *
     * <p>It names the property, names the environment variable and states the remedy. It reports
     * <strong>no part of the supplied value</strong> - not the value, not a prefix or suffix, not the observed
     * length and not a digest - because a startup failure is frequently the most widely captured log line a
     * system produces.
     *
     * @param problem a short phrase describing which of the four defects applies, containing nothing derived
     *                from the key itself
     * @return the exception to throw, never {@code null}
     */
    private static IllegalStateException signingKeyRejected(final String problem) {
        return new IllegalStateException(
                "Property " + SIGNING_KEY_PROPERTY + " " + problem + ". Set the environment variable "
                        + SIGNING_KEY_VARIABLE + " to at least " + MINIMUM_SIGNING_KEY_BYTES
                        + " bytes of entropy before starting the application. There is deliberately no "
                        + "default, no example and no fallback value, and no part of the supplied value is "
                        + "reported here.");
    }

    /**
     * Validates the issuer claim value.
     *
     * @param issuer the bound issuer
     * @return the same value, once known to be usable
     * @throws IllegalStateException if the issuer is {@code null} or blank, since a blank issuer would make
     *                               the issued token unattributable
     */
    private static String validatedIssuer(final String issuer) {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException(
                    "Property " + ISSUER_PROPERTY + " resolved to "
                            + (issuer == null ? "null" : "a blank value")
                            + ", so issued tokens would carry no attributable issuer claim. Set the "
                            + "environment variable " + ISSUER_VARIABLE + " to a non blank identifier, or "
                            + "leave it unset to accept the documented default.");
        }
        return issuer;
    }

    /**
     * Validates the configured token lifetime and converts it to a duration.
     *
     * <p>The value is non secret configuration, so it is safe to quote back in a failure message, which makes
     * the message directly actionable.
     *
     * <p><strong>This is the one and only place a lifetime number becomes a {@link Duration}.</strong> The
     * property is expressed in minutes and nothing downstream sees the raw number: the field it produces is a
     * {@code Duration}, and the issuance path adds that duration to the issued-at instant. Converting in one
     * place is what makes the unit unambiguous - the earlier seconds-based property with a 3,600-second
     * default silently doubled the approved thirty-minute window, which is exactly the class of mistake a
     * single typed conversion prevents.
     *
     * @param lifetimeMinutes the bound lifetime in minutes
     * @return the equivalent duration, never {@code null}
     * @throws IllegalStateException if the lifetime is below {@link #MINIMUM_LIFETIME_MINUTES} or above
     *                               {@link #MAXIMUM_LIFETIME_MINUTES}
     */
    private static Duration validatedLifetime(final long lifetimeMinutes) {
        if (lifetimeMinutes < MINIMUM_LIFETIME_MINUTES) {
            throw new IllegalStateException(
                    "Property " + LIFETIME_PROPERTY + " is " + lifetimeMinutes + " minutes, which would "
                            + "issue tokens that have already expired at the instant they are signed. Set "
                            + "the environment variable " + LIFETIME_VARIABLE + " to at least "
                            + MINIMUM_LIFETIME_MINUTES + ", or leave it unset to accept the documented "
                            + "default of 30 minutes.");
        }
        if (lifetimeMinutes > MAXIMUM_LIFETIME_MINUTES) {
            throw new IllegalStateException(
                    "Property " + LIFETIME_PROPERTY + " is " + lifetimeMinutes + " minutes, which exceeds "
                            + "the " + MAXIMUM_LIFETIME_MINUTES + " minute ceiling this application imposes. "
                            + "A bearer token cannot be revoked before it expires, so an unbounded lifetime "
                            + "would turn one leaked token into indefinite access. Set the environment "
                            + "variable " + LIFETIME_VARIABLE + " to " + MAXIMUM_LIFETIME_MINUTES
                            + " or below; the approved value is 30.");
        }
        return Duration.ofMinutes(lifetimeMinutes);
    }

    /**
     * Validates the injected time source.
     *
     * <p>Under Spring, a missing bean is reported before this constructor runs; this check covers direct
     * instantiation, which is how the unit tests drive the class with a fixed clock.
     *
     * @param clock the injected time source
     * @return the same clock, once known to be present
     * @throws IllegalStateException if no clock was supplied, since the issued-at and expiry claims cannot be
     *                               derived without one
     */
    private static Clock validatedClock(final Clock clock) {
        if (clock == null) {
            throw new IllegalStateException(
                    "No java.time.Clock was supplied, so the issued-at and expiry claims cannot be derived. "
                            + "com.cardemo.config.ObservabilityConfig.clock() publishes the application's only "
                            + "Clock bean, as Clock.systemDefaultZone(); a unit test should pass a fixed clock "
                            + "so that expiry is deterministic.");
        }
        return clock;
    }

    /**
     * Validates the value destined for the subject claim.
     *
     * <p>The width bound is a field contract rather than a stylistic limit: {@code CDEMO-USER-ID} is
     * {@code PIC X(08)} at {@code app/cpy/COCOM01Y.cpy:L25} and {@code SEC-USR-ID} is {@code PIC X(08)} in
     * the 80 byte security record, so a longer identifier could not be represented in the system of record.
     *
     * <p>No message echoes the value. A user identifier is an identity, it arrives from a request, and Rule 1
     * Clause D's least privilege requirement extends to what reaches a log sink.
     *
     * @param userId the candidate subject value
     * @return the same value, once known to be usable, with case untouched
     * @throws IllegalArgumentException if the value is {@code null}, blank, or wider than
     *                                 {@link #MAXIMUM_SUBJECT_LENGTH} characters
     */
    private static String validatedSubject(final String userId) {
        if (userId == null) {
            throw new IllegalArgumentException(
                    "No user identifier was supplied, so no subject claim can be set. The value comes from "
                            + "CDEMO-USER-ID at app/cpy/COCOM01Y.cpy:L25 and is required.");
        }
        if (userId.isBlank()) {
            throw new IllegalArgumentException(
                    "The user identifier is blank, so no subject claim can be set. app/cbl/COSGN00C.cbl:L118 "
                            + "rejects a blank identifier before the security file is ever read, and the "
                            + "value is withheld from this message rather than echoed.");
        }
        if (userId.length() > MAXIMUM_SUBJECT_LENGTH) {
            throw new IllegalArgumentException(
                    "The user identifier is wider than the " + MAXIMUM_SUBJECT_LENGTH + " characters that "
                            + "CDEMO-USER-ID PIC X(08) at app/cpy/COCOM01Y.cpy:L25 can hold, so it is not "
                            + "representable in the system of record and is rejected rather than truncated. "
                            + "The value is withheld from this message rather than echoed.");
        }
        return userId;
    }
}
