/*
 * ******************************************************************
 * Program     : CardDemoUserDetailsService.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 security component
 * Function    : Loads and verifies CardDemo sign-on credentials,
 *               reproducing COSGN00C exactly.
 * Source      : app/cbl/COSGN00C.cbl:L132-L136 (UPPER-CASE BOTH id and
 *               password), :L223 (SEC-USR-PWD = WS-USER-PWD compare)
 *               @ 7756d89
 * Source      : app/cbl/COSGN00C.cbl:L209-L257 (READ-USER-SEC-FILE),
 *               :L226-L227 (identity establishment) @ 7756d89
 * Source      : app/cpy/CSUSR01Y.cpy:L17-L23 (80-byte SEC-USER-DATA
 *               layout, key 8) @ 7756d89
 * Source      : app/jcl/DUSRSECJ.jcl:L35-L44 (10 inline seed users),
 *               :L64-L66 (KEYS(8,0) RECORDSIZE(80,80)) @ 7756d89
 * Source      : app/csd/CARDDEMO.CSD:L88-L92 (DEFINE FILE(USRSEC)),
 *               :L378 (DEFINE TRANSACTION(CC00)) @ 7756d89
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

import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads and verifies CardDemo sign-on credentials: the relational, stateless replacement for the
 * {@code READ-USER-SEC-FILE} paragraph of the CICS sign-on program
 * {@code app/cbl/COSGN00C.cbl:L209-L257}, whose transaction {@code CC00} is defined at
 * {@code app/csd/CARDDEMO.CSD:L378}.
 *
 * <p>Two responsibilities, and deliberately no third. It <strong>loads</strong> a user by identifier,
 * which is the {@code EXEC CICS READ} of {@code :L211-L219}, and it <strong>verifies</strong> a
 * presented password against the stored credential, which is the comparison at {@code :L223}. It issues
 * no token, configures no filter chain, authenticates no HTTP request, writes no row and registers no
 * meter. Those belong to {@link JwtTokenProvider}, to {@code com.cardemo.config.SecurityConfig}, to
 * {@code com.cardemo.security.JwtAuthenticationFilter} and to
 * {@code com.cardemo.observability.MetricsConfig} respectively.
 *
 * <h2>&gt;&gt;&gt; THE INVARIANT THIS CLASS EXISTS TO PROTECT: UPPER-CASE BOTH, TRIM NEITHER &lt;&lt;&lt;</h2>
 *
 * <p>The legacy program folds <strong>both</strong> credentials to upper case before either is used.
 * {@code app/cbl/COSGN00C.cbl:L132-L136}, verbatim:
 *
 * <pre>
 * L132        MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI) TO
 * L133                        WS-USER-ID
 * L134                        CDEMO-USER-ID
 * L135        MOVE FUNCTION UPPER-CASE(PASSWDI OF COSGN0AI) TO
 * L136                        WS-USER-PWD
 * </pre>
 *
 * <p>and the comparison those two folded values feed, at {@code app/cbl/COSGN00C.cbl:L223}:
 *
 * <pre>
 * L221     EVALUATE WS-RESP-CD
 * L222         WHEN 0
 * L223             IF SEC-USR-PWD = WS-USER-PWD
 * </pre>
 *
 * <p>Note that {@code :L132-L134} is a <em>single</em> {@code MOVE} with <em>two</em> receiving fields,
 * so the folded identifier reaches both {@code WS-USER-ID} and {@code CDEMO-USER-ID}; and that the
 * comparison is at {@code :L223}, not at {@code :L222}, which is only the {@code WHEN 0} selector.
 *
 * <p><b>High severity.</b> Upper-casing only the identifier is the obvious mistake, and it changes which
 * credentials are accepted: every user who types a password in lower case would be refused, even though
 * the legacy system admits them. There are exactly two folding sites in this class - one per credential,
 * each mapping one-to-one onto one of the two {@code MOVE} statements above - and
 * {@link #normaliseUserId(String)} and {@link #normalisePresentedPassword(String)} are they. Both use
 * {@link Locale#ROOT}; neither trims; neither pads.
 *
 * <p>Passing {@link Locale#ROOT} explicitly, rather than using the no-argument overload that folds with
 * the platform default locale, is load bearing rather than pedantic. Under a Turkish default locale a
 * lower-case {@code i} folds to the dotted capital {@code U+0130} instead of to {@code I}, which would
 * corrupt the key lookup and the password comparison simultaneously, on that host only, silently. Rule 1
 * clause A requires determinism, and clause C forbids environment-specific assumptions; naming a fixed
 * locale at every folding site discharges both.
 *
 * <h2>&gt;&gt;&gt; THIS CLASS IS THE SINGLE NORMALISATION AUTHORITY &lt;&lt;&lt;</h2>
 *
 * <p><strong>{@code com.cardemo.service.auth.AuthenticationService} must delegate to this class and must
 * not re-apply the normalisation.</strong> It should call {@link #authenticate(String, String)} with the
 * credentials exactly as received from the request body, fold nothing itself, and then hand the returned
 * principal's name and authority to {@link JwtTokenProvider} for issuance. Four reasons this class owns
 * the folding:
 *
 * <ol>
 *   <li>{@code WS-USER-ID} and {@code WS-USER-PWD} are consumed at exactly two sites, and both are inside
 *       {@code READ-USER-SEC-FILE}: as the record key at {@code app/cbl/COSGN00C.cbl:L215}
 *       ({@code RIDFLD (WS-USER-ID)}) and in the comparison at {@code :L223}. The folding belongs where
 *       the read and the compare happen, which is here.</li>
 *   <li>{@link #loadUserByUsername(String)} is framework-invoked with an arbitrary caller-supplied
 *       string. Folding only in the service layer would let every other caller bypass it.</li>
 *   <li>folding to upper case with a fixed locale is idempotent, so a defensive second application would
 *       be harmless - but duplicating it violates Rule 1 clause C and creates two places to get wrong.</li>
 *   <li>{@link JwtTokenProvider} already records the same boundary from the other side: it states that
 *       case folding is owned by this class and that repeating it there would be the duplication clause C
 *       forbids. Two files, one agreed owner.</li>
 *   </ol>
 *
 * <h2>Why an explicit verification method exists, rather than DaoAuthenticationProvider</h2>
 *
 * <p>This is the subtlest point in the class and the reason {@link #authenticate(String, String)} is
 * <em>required</em> rather than merely convenient. Spring Security's {@code DaoAuthenticationProvider}
 * verifies by calling {@code PasswordEncoder.matches} with the <strong>raw</strong> presented password -
 * it performs no case folding of its own and offers no hook to insert any. So a design that implemented
 * only {@link #loadUserByUsername(String)} and delegated verification to that provider would silently
 * never apply {@code :L135-L136}: the password fold would have no home at all, because the paragraph
 * above forbids the service layer from providing one. Every lower-case password would be refused and the
 * parity break would be invisible in the code, because the missing step would be a step nobody wrote.
 *
 * <p>{@link #authenticate(String, String)} therefore takes the <strong>raw</strong> presented password,
 * folds it internally, and calls {@link PasswordEncoder#matches(CharSequence, String)} exactly once. A
 * caller cannot forget the fold because a caller is never given the opportunity to perform it.
 *
 * <h2>The fixed-width trailing-space boundary: a documented decision, not an oversight</h2>
 *
 * <p>{@code SEC-USR-PWD} at {@code app/cpy/CSUSR01Y.cpy:L21} and {@code WS-USER-PWD} at
 * {@code app/cbl/COSGN00C.cbl:L46} are both {@code PIC X(08)}, so the comparison at {@code :L223} is a
 * space-padded, fixed-width one: COBOL compares eight bytes against eight bytes.
 *
 * <p><strong>The convention here is: fold with {@link Locale#ROOT}; do not trim; do not pad to 8.</strong>
 *
 * <p>Padding the presented password to eight characters would append spaces that are not part of the
 * value {@code V3__seed_data.sql} hashed, so <em>every</em> verification would fail - a self-inflicted
 * total outage. Trimming would silently accept a password that differs from the stored one by whitespace.
 * And the divergence from the legacy fixed-width comparison is unobservable against the seed set anyway,
 * because all ten seeded credentials are exactly eight characters long, so the padded and unpadded forms
 * coincide for every one of them. <b>Low severity</b>, and recorded here precisely so that a later reader
 * does not "fix" it by adding a pad and taking sign-on down.
 *
 * <p>The identifier is left unpadded for the same reason and one more: {@code sec_usr_id} is
 * {@code CHAR(8)}, and PostgreSQL's blank-padded character comparison already ignores trailing spaces, so
 * the key lookup reproduces the legacy fixed-width key match without any padding in Java.
 *
 * <h2>The verified 80-byte record</h2>
 *
 * <p>{@code app/cpy/CSUSR01Y.cpy:L17-L23} declares {@code SEC-USER-DATA} as five fields:
 *
 * <pre>
 * COBOL field (CSUSR01Y.cpy)   PIC    Bytes   Use here
 * ---------------------------  -----  ------  --------------------------------------------
 * SEC-USR-ID      (:L18)       X(08)   1-8    the lookup key; the principal's name
 * SEC-USR-FNAME   (:L19)       X(20)   9-28   personal data - deliberately ignored
 * SEC-USR-LNAME   (:L20)       X(20)  29-48   personal data - deliberately ignored
 * SEC-USR-PWD     (:L21)       X(08)  49-56   now a BCrypt hash; compared, never emitted
 * SEC-USR-TYPE    (:L22)       X(01)     57   'A' or 'U' - becomes the granted authority
 * SEC-USR-FILLER  (:L23)       X(23)  58-80   trailing filler - not modelled
 * </pre>
 *
 * <p>Eight plus twenty plus twenty plus eight plus one is 57 populated bytes, plus 23 of filler, giving
 * the 80-byte record; and the key is the leading eight bytes. Both facts are corroborated independently
 * three times over, which is why the specification's claim that {@code USRSEC} is "defined in JCL rather
 * than catalogued" is a <b>Medium</b>-severity documentary error worth correcting rather than repeating -
 * it <em>is</em> catalogued:
 *
 * <ul>
 *   <li>{@code app/catlg/LISTCAT.txt:L3846} names the cluster and {@code :L3883} reports
 *       {@code KEYLEN 8} with {@code AVGLRECL 80};</li>
 *   <li>{@code app/jcl/DUSRSECJ.jcl:L64-L66} declares {@code DEFINE CLUSTER} with {@code KEYS(8,0)} and
 *       {@code RECORDSIZE(80,80)}, and {@code :L48} sets {@code LRECL=80} on the sequential feed;</li>
 *   <li>{@code app/csd/CARDDEMO.CSD:L88-L92} gives the cluster its online definition, which is what makes
 *       this table part of the request path rather than batch-only reference data.</li>
 *   </ul>
 *
 * <p>Only three of the five fields are used here. The identifier is the key, the credential is compared,
 * and the type byte becomes the authority. The two name fields are personal data and their only
 * legitimate use in this class is being ignored: they enter no authority, no claim, no log line and no
 * exception message.
 *
 * <h2>Credential verification</h2>
 *
 * <p>BCrypt at strength 10 replaces the plaintext comparison of {@code app/cbl/COSGN00C.cbl:L223}.
 * The {@link PasswordEncoder} is <strong>consumed by constructor injection, never defined here</strong>:
 * this class declares no bean-factory method, constructs no encoder and holds no encoder singleton.
 * Defining a second encoder would be a <b>High</b>-severity duplication defect under clause C and would
 * risk a strength mismatch against the digests written by {@code V3__seed_data.sql}, which would refuse
 * every seeded credential.
 *
 * <p>Verification is one call to {@link PasswordEncoder#matches(CharSequence, String)}. Hash strings are
 * never compared with {@code equals} and the presented value is never re-hashed for comparison; both
 * would defeat BCrypt's per-credential salt. {@code matches} is called exactly once per attempt, because
 * BCrypt is deliberately expensive.
 *
 * <p>The ten users seeded by {@code V3__seed_data.sql} from the inline records at
 * {@code app/jcl/DUSRSECJ.jcl:L35-L44} - five administrators of type {@code 'A'} then five standard users
 * of type {@code 'U'} - are what make the security gate's "every password BCrypt-hashed" assertion
 * provable rather than merely asserted. The shared legacy inline plaintext literal they carry is referred
 * to by that locator only; it is transcribed nowhere in this file, nowhere in this class's tests, and
 * nowhere else beneath {@code src/}.
 *
 * <h2>The four legacy outcome branches, and what each becomes</h2>
 *
 * <table border="1">
 *   <caption>{@code READ-USER-SEC-FILE} outcomes, {@code app/cbl/COSGN00C.cbl:L221-L256}</caption>
 *   <tr><th>Legacy branch</th><th>Locator</th><th>Target</th></tr>
 *   <tr>
 *     <td>{@code WHEN 0} and the password matches</td>
 *     <td>{@code :L222-L240}</td>
 *     <td>a successful load. The legacy program establishes {@code CDEMO-USER-ID} at {@code :L226} and
 *         {@code CDEMO-USER-TYPE} at {@code :L227}, zeroes {@code CDEMO-PGM-CONTEXT} at {@code :L228},
 *         then transfers to {@code COADM01C} for an administrator at {@code :L231-L234} or to
 *         {@code COMEN01C} for a standard user at {@code :L236-L239}. Here the identity and the authority
 *         are returned; navigation is URL-based, so the branch no longer routes, and the token is issued
 *         by {@link JwtTokenProvider}.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code WHEN 0} and the password does not match</td>
 *     <td>{@code :L241-L246}</td>
 *     <td>{@link BadCredentialsException}. The legacy branch shows {@code 'Wrong Password. Try again ...'}
 *         and repositions the cursor. Note the verified asymmetry: this branch alone does <em>not</em>
 *         set {@code WS-ERR-FLG}, whereas {@code :L248} and {@code :L253} both do. <b>Low</b> severity;
 *         recorded, not corrected.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code WHEN 13}</td>
 *     <td>{@code :L247-L251}</td>
 *     <td>From {@link #authenticate(String, String)}, the <em>same</em> {@link BadCredentialsException}
 *         with the <em>same</em> message that a password mismatch produces, after the same BCrypt work has
 *         been performed - see the enumeration section below. {@link UsernameNotFoundException} survives on
 *         {@link #loadUserByUsername(String)} alone, because the {@code UserDetailsService} contract
 *         requires it there and that method verifies no credential. The legacy branch shows
 *         {@code 'User not found. Try again ...'}.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code WHEN OTHER}</td>
 *     <td>{@code :L252-L256}</td>
 *     <td>{@link InternalAuthenticationServiceException} wrapping the {@link DataAccessException} that
 *         caused it, so the root cause is preserved rather than swallowed, as clause B requires. The
 *         legacy branch shows {@code 'Unable to verify the User ...'}.</td>
 *   </tr>
 * </table>
 *
 * <h2>User enumeration: a deliberate, labelled deviation</h2>
 *
 * <p>The legacy 3270 screen told the operator <em>which</em> of the two failures had occurred - a
 * different message for an unknown identifier and for a wrong password. Reproducing that on a REST
 * surface would hand an unauthenticated caller an oracle for which identifiers exist, so it is
 * <strong>deliberately not reproduced</strong>. <b>Medium</b> severity, justified by clause D's
 * "principle of least privilege for tokens/credentials/config".
 *
 * <p>The distinction survives where it is useful and disappears where it is dangerous. Internally each
 * outcome logs its own branch at debug level, so an operator and a test can still tell them apart.
 * Externally the two are indistinguishable in <strong>all three</strong> observable channels, and each is
 * closed structurally rather than by convention:
 *
 * <ul>
 *   <li><strong>Message.</strong> One constant, {@link #MESSAGE_CREDENTIALS_REJECTED}, used by both throw
 *       sites, so the two texts cannot drift apart in a later edit.</li>
 *   <li><strong>Exception type.</strong> {@link #authenticate(String, String)} throws
 *       {@link BadCredentialsException} for an unknown identifier as well as for a wrong password. An
 *       earlier form threw {@link UsernameNotFoundException} for the first, and because the caller maps
 *       exception types onto HTTP outcomes, the <em>type</em> was itself the oracle - the message being
 *       identical did not help. {@link UsernameNotFoundException} is therefore confined to
 *       {@link #loadUserByUsername(String)}, whose contract requires it and which verifies no
 *       credential.</li>
 *   <li><strong>Elapsed time.</strong> When no row bears the identifier, this class still performs one
 *       BCrypt verification - against {@link #dummyCredentialDigest}, a cost-10 digest computed at
 *       construction - before refusing. Without it the absent-user path skipped the deliberately expensive
 *       key schedule and returned in microseconds while a present-user path took tens of milliseconds,
 *       which is a timing oracle that reveals exactly what the identical message was hiding. Both paths now
 *       run the same key schedule exactly once.</li>
 *   </ul>
 *
 * <p>Missing input is treated differently on purpose, and safely. A blank identifier and a blank password
 * are distinguishable failures here, reproducing {@code :L118-L121} and {@code :L123-L126}, because
 * neither message reveals anything about which users exist.
 *
 * <h2>A legacy control-flow detail reproduced accurately</h2>
 *
 * <p>The blank-input {@code EVALUATE} at {@code app/cbl/COSGN00C.cbl:L117-L130} does <strong>not</strong>
 * exit the paragraph. Control falls straight through to {@code :L132}, so the upper-casing runs even on
 * the blank-input error path, and only {@code :L138} - {@code IF NOT ERR-FLG-ON PERFORM
 * READ-USER-SEC-FILE} - gates the file read. The observable consequences are what matter and both are
 * reproduced: the identifier is checked before the password, because {@code EVALUATE TRUE} takes the
 * first matching branch and {@code :L118} precedes {@code :L123}; and no lookup occurs when either is
 * blank. The fall-through itself has no observable effect, since folding a blank value yields a blank
 * value, so it is documented rather than modelled.
 *
 * <h2>Secrecy: what never leaves this class</h2>
 *
 * <p>The stored BCrypt hash reaches exactly one consumer, and this class is it. It is obtained from the
 * fully-loaded {@code UserSecurity} aggregate through {@code getPasswordHash()} and never from a narrowed
 * projection - the repository deliberately declares none. From here it goes to
 * {@link PasswordEncoder#matches(CharSequence, String)} and nowhere else. It is never logged at any
 * level, never placed in an exception message, never returned to an application caller, never serialized
 * and never used as a metric tag or span attribute. The presented password is treated identically.
 *
 * <p>The one nuance worth stating plainly, because it looks like an exception to the rule and is not:
 * {@link #loadUserByUsername(String)} returns a {@link UserDetails} whose {@link UserDetails#getPassword()}
 * is the stored hash. That is not a leak, it is the framework contract - {@code UserDetailsService} exists
 * precisely to supply the stored credential to an authentication provider, and returning a placeholder
 * there would be a lie that fails closed in a way no reader could diagnose. The application sign-on path
 * does not rely on it: {@link #authenticate(String, String)} erases the credential from the principal it
 * returns, so the hash does not travel onwards to the service layer, to a token claim or to a response
 * body. Exposing the stored hash beyond this class would be a <b>Blocker</b>.
 *
 * <p>Log masking in {@code logback-spring.xml} is a second line of defence. Not producing the value in
 * the first place is the first, and that is this class's job.
 *
 * <h2>Observability</h2>
 *
 * <p>This class registers no meter. The authentication-attempts counter is one of exactly four instruments
 * in the target and is owned by {@code com.cardemo.observability.MetricsConfig}; registering a fifth would
 * be a defect.
 *
 * <p>It also deliberately does <strong>not emit</strong> that counter, for two reasons. Incrementing it
 * here would double-count every sign-on, because {@code com.cardemo.service.auth.AuthenticationService}
 * is the attempt boundary and emits it there; and {@link #loadUserByUsername(String)} is also invoked
 * outside sign-on, so counting loads as attempts would corrupt the series with events that are not
 * attempts. {@link JwtTokenProvider} omits the same emission for the same reason, so omission is this
 * package's established pattern rather than a local choice.
 *
 * <p>What this class does emit is debug-level records of the <em>outcome</em> - which branch of
 * {@code :L221-L256} was taken - carrying no identifier, no name, no password and no hash. The outcome is
 * the diagnostic; the credential never is.
 *
 * <h2>Configuration and defaults</h2>
 *
 * <p>This class reads no configuration property of its own. It consults no environment variable and no JVM
 * system property, and it carries no property-injection annotation, so nothing about its behaviour varies
 * with the environment. Its two collaborators are configured elsewhere:
 *
 * <ul>
 *   <li>the {@link PasswordEncoder}, including its strength, belongs to
 *       {@code com.cardemo.config.SecurityConfig};</li>
 *   <li>the ten seeded rows, and the BCrypt digests they are stored as, belong to
 *       {@code V3__seed_data.sql};</li>
 *   <li>{@code spring.jpa.open-in-view} is {@code false} in every profile, which is why both public
 *       methods declare a read-only transaction rather than relying on an open session.</li>
 *   </ul>
 *
 * <h2>The encoder contract, now verified against code</h2>
 *
 * <p>Three assertions about the encoder are verified by reading
 * {@code src/main/java/com/cardemo/config/SecurityConfig.java}, which is present - the configuration package
 * holds four classes rather than only {@code JpaConfig} and {@code WebConfig}, so none of the three needs to
 * be disclosed as unverified:
 *
 * <ol>
 *   <li>a {@link PasswordEncoder} bean <em>is</em> published, by {@code SecurityConfig.passwordEncoder()};</li>
 *   <li>it <em>is</em> BCrypt at strength <strong>10</strong> - {@code REQUIRED_BCRYPT_STRENGTH} is 10, the
 *       {@code carddemo.security.bcrypt.strength} property is 10, and start-up aborts on any other value -
 *       matching the digests in {@code V3__seed_data.sql};</li>
 *   <li>the authorisation rules <em>do</em> consume the authorities this class grants: every
 *       {@code requestMatchers} rule binds to {@code JwtTokenProvider.ADMIN_AUTHORITY} and
 *       {@code JwtTokenProvider.USER_AUTHORITY} rather than to its own string literals, which is the same
 *       pair of constants this class grants from.</li>
 *   </ol>
 *
 * <p>What has <em>not</em> changed is the design decision behind them. This class still declines to
 * construct an encoder of its own: if the bean were ever removed the context must fail to refresh loudly
 * rather than fall back to a locally chosen strength, because a silent strength mismatch fails every
 * verification while looking healthy. That is what clause C requires.
 *
 * <h2>Build, run and test</h2>
 *
 * <p>Verified on 2 August 2026 with OpenJDK 25.0.3 and Apache Maven 3.9.11:
 * {@code ./mvnw -B -ntp clean compile} compiles the module under {@code -Xlint:all -Werror}, and
 * {@code ./mvnw -B -ntp test} runs the unit tier. This class's own tests live under
 * {@code src/test/java/com/cardemo/unit/}, not in this package.
 *
 * <h2>Troubleshooting</h2>
 *
 * <ul>
 *   <li><b>Every sign-on fails although the ten seeded users are present.</b> Almost always one of two
 *       causes: the presented password was not folded to upper case before reaching the encoder - the
 *       invariant at the top of this document - or the {@link PasswordEncoder} strength diverges from the
 *       10 that {@code V3__seed_data.sql} used. Check the fold first; it is the cheaper test.</li>
 *   <li><b>A user whose password is typed in lower case is refused, others succeed.</b> The password fold
 *       is missing or was applied after {@code matches}. See {@link #normalisePresentedPassword(String)}.</li>
 *   <li><b>Sign-on fails only on one host.</b> Suspect a case operation without {@link Locale#ROOT}
 *       somewhere on the path; under a Turkish default locale a dotless {@code i} folds to {@code U+0130}.</li>
 *   <li><b>The context will not refresh, reporting no {@link PasswordEncoder} bean.</b> The bean is
 *       published by {@code com.cardemo.config.SecurityConfig}, so suspect that its
 *       {@code @Configuration} class is outside the component-scan root or that
 *       {@code carddemo.security.bcrypt.strength} is absent, which aborts start-up by design.</li>
 *   <li><b>Authorisation refuses every request although sign-on succeeds.</b> The authority strings and
 *       the claim reader have diverged. This class binds to {@link JwtTokenProvider#ADMIN_AUTHORITY} and
 *       {@link JwtTokenProvider#USER_AUTHORITY} precisely so that they cannot; check that the filter and
 *       the security configuration bind to the same constants rather than to their own literals.</li>
 *   </ul>
 *
 * <p>Thread safety: instances hold two immutable collaborator references and no mutable state, so a
 * single bean is safely shared across request threads.
 *
 * @see JwtTokenProvider
 * @see UserDetailsService
 */
@Service
public class CardDemoUserDetailsService implements UserDetailsService {

    /**
     * Logger for outcome records. Only which branch of {@code app/cbl/COSGN00C.cbl:L221-L256} was taken is
     * ever written - never an identifier, a name, a presented password or a stored hash.
     */
    private static final Logger LOG = LoggerFactory.getLogger(CardDemoUserDetailsService.class);

    /**
     * The single message carried by both credential-rejection outcomes, so that the unknown-identifier
     * branch at {@code app/cbl/COSGN00C.cbl:L247-L251} and the wrong-password branch at {@code :L241-L246}
     * are indistinguishable to an unauthenticated caller.
     *
     * <p>One constant rather than two equal literals is the point: it makes the non-differentiation
     * structural, so a later edit cannot let the two messages drift apart and reintroduce the enumeration
     * oracle described in the class documentation.
     */
    private static final String MESSAGE_CREDENTIALS_REJECTED =
            "Sign-on failed. Check the user identifier and password and try again.";

    /**
     * Message for an absent or blank identifier, reproducing the outcome of
     * {@code app/cbl/COSGN00C.cbl:L118-L121} ({@code 'Please enter User ID ...'}). Safe to distinguish
     * from the password equivalent, because it discloses nothing about which identifiers exist.
     */
    private static final String MESSAGE_USER_ID_REQUIRED = "Please enter a user identifier.";

    /**
     * Length of the random pre-image behind {@link #dummyCredentialDigest}, in bytes.
     *
     * <p>Thirty-two bytes is well beyond guessing range and beyond BCrypt's own 72-byte input ceiling once
     * Base64-encoded to 44 characters, so the whole value participates in the digest.
     */
    private static final int DUMMY_CREDENTIAL_BYTES = 32;

    /**
     * Message for an absent or blank password, reproducing the outcome of
     * {@code app/cbl/COSGN00C.cbl:L123-L126} ({@code 'Please enter Password ...'}).
     */
    private static final String MESSAGE_PASSWORD_REQUIRED = "Please enter a password.";

    /**
     * Read access to {@code user_security}, the relational form of the VSAM cluster that
     * {@code app/cbl/COSGN00C.cbl:L211-L219} reads and {@code app/csd/CARDDEMO.CSD:L88-L92} defines.
     *
     * <p>Used for exactly one operation, {@code findById}, which is the target of the keyed
     * {@code EXEC CICS READ}. This class never writes through it.
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * BCrypt verifier replacing the plaintext comparison at {@code app/cbl/COSGN00C.cbl:L223}. Injected,
     * never constructed here; see the class documentation for why, and for the disclosure that its
     * strength is asserted rather than verified.
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * A digest that no account can ever present, verified against on the unknown-identifier path so that
     * path costs the same as the known-identifier path.
     *
     * <p><strong>This is not a credential and it is not a secret.</strong> It is produced at construction by
     * asking the injected {@link PasswordEncoder} to encode {@value #DUMMY_CREDENTIAL_BYTES} bytes of
     * freshly generated randomness, which is then discarded unread, so no value exists anywhere that
     * verifies against it and it is worthless to an attacker who reads it out of memory. Nothing is
     * committed: unlike a literal digest in source, this one differs on every start.
     *
     * <p>It is produced <em>through the injected encoder</em> rather than written as a constant on purpose.
     * A literal {@code $2a$10$...} string would be rejected as an unrecognised format by, for example, a
     * delegating encoder, and {@link PasswordEncoder#matches(CharSequence, String)} would then return
     * {@code false} immediately without running any key schedule - reintroducing the timing oracle while
     * appearing to close it. Encoding through the same object that will later verify guarantees the format
     * matches and the work is genuinely performed.
     */
    private final String dummyCredentialDigest;

    /**
     * Creates the sign-on credential loader.
     *
     * <p>Side effects: no query is issued and no row is read during construction. One BCrypt encode is
     * performed, to build {@link #dummyCredentialDigest}; at strength 10 that costs a few tens of
     * milliseconds once, at start-up, and it buys a constant-work unknown-identifier path for the life of
     * the application.
     *
     * @param userSecurityRepository read access to {@code user_security}; must not be {@code null}
     * @param passwordEncoder        the BCrypt verifier published by
     *                               {@code com.cardemo.config.SecurityConfig}, expected to be strength 10
     *                               to match {@code V3__seed_data.sql}; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}, because a missing collaborator must
     *                              fail loudly at start-up rather than at the first sign-on attempt
     * @throws IllegalStateException if the supplied encoder produces no digest, which would leave the
     *                               unknown-identifier path with nothing to verify against and would
     *                               silently restore the timing oracle
     */
    public CardDemoUserDetailsService(final UserSecurityRepository userSecurityRepository,
            final PasswordEncoder passwordEncoder) {
        this.userSecurityRepository =
                Objects.requireNonNull(userSecurityRepository, "userSecurityRepository must not be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
        this.dummyCredentialDigest = unmatchableDigest(this.passwordEncoder);
    }

    /**
     * Builds the constant-work digest described on {@link #dummyCredentialDigest}.
     *
     * <p>The random material is generated, encoded and dropped inside this method, so the only surviving
     * artefact is a digest whose pre-image was never retained by anything.
     *
     * @param encoder the encoder that will also perform verification
     * @return a digest in the encoder's own format, never {@code null} or blank
     * @throws IllegalStateException if the encoder returns nothing usable
     */
    private static String unmatchableDigest(final PasswordEncoder encoder) {
        final byte[] material = new byte[DUMMY_CREDENTIAL_BYTES];
        new SecureRandom().nextBytes(material);
        final String digest = encoder.encode(Base64.getEncoder().encodeToString(material));
        Arrays.fill(material, (byte) 0);
        if (digest == null || digest.isBlank()) {
            throw new IllegalStateException(
                    "The configured PasswordEncoder produced no digest, so the unknown-identifier sign-on "
                            + "path would have nothing to verify against and would return measurably faster "
                            + "than a known-identifier path. Publish a BCrypt encoder of strength "
                            + "10 from com.cardemo.config.SecurityConfig.");
        }
        return digest;
    }

    /**
     * Loads a user by identifier: the keyed read of {@code app/cbl/COSGN00C.cbl:L211-L219}, whose
     * {@code RIDFLD (WS-USER-ID)} at {@code :L215} is the upper-cased identifier this method reproduces.
     *
     * <p>The identifier is folded to upper case with {@link Locale#ROOT} before the lookup, reproducing
     * {@code app/cbl/COSGN00C.cbl:L132-L134}, and is neither trimmed nor padded. It is <strong>not</strong>
     * lower-cased, abbreviated or otherwise rewritten.
     *
     * <p>This method verifies nothing. Spring Security's {@code UserDetailsService} contract is load-only,
     * and the credential decision belongs to {@link #authenticate(String, String)}. Accordingly the
     * returned principal carries the stored BCrypt hash as its password, which is the contract's designated
     * channel for it; callers on the application sign-on path should use
     * {@link #authenticate(String, String)} instead and receive a principal with the credential erased.
     *
     * <p>The principal's name is the <em>folded presented identifier</em> rather than the stored
     * {@code sec_usr_id}. That reproduces {@code app/cbl/COSGN00C.cbl:L226}, which moves {@code WS-USER-ID}
     * - the folded input - into {@code CDEMO-USER-ID}, and pointedly not {@code SEC-USR-ID} from the record
     * just read. It also keeps the trailing blanks that a {@code CHAR(8)} column returns out of the identity
     * that downstream token claims are built from.
     *
     * <p>Side effects: none. One read-only query is issued; nothing is written, created or enqueued.
     *
     * @param username the identifier as supplied by the caller, in any case; must not be {@code null},
     *                 empty or blank
     * @return the loaded principal, carrying the folded identifier as its name, the stored BCrypt hash as
     *         its password and exactly one of {@code ROLE_ADMIN} or {@code ROLE_USER} as its authority;
     *         never {@code null}
     * @throws UsernameNotFoundException           if {@code username} is {@code null} or blank, or if no
     *                                             row bears that identifier - the analogue of the
     *                                             {@code WHEN 13} branch at
     *                                             {@code app/cbl/COSGN00C.cbl:L247-L251}
     * @throws InternalAuthenticationServiceException if the read fails - the analogue of the
     *                                             {@code WHEN OTHER} branch at {@code :L252-L256}; the
     *                                             causing {@link DataAccessException} is preserved
     * @throws IllegalStateException               if the loaded row carries no user class, because an
     *                                             absent class must never be defaulted to an authority
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(final String username) {
        if (isBlank(username)) {
            LOG.debug("Sign-on load refused: no user identifier was presented "
                    + "(app/cbl/COSGN00C.cbl:L118-L121).");
            throw new UsernameNotFoundException(MESSAGE_USER_ID_REQUIRED);
        }

        final String normalisedUserId = normaliseUserId(username);
        final UserSecurity userSecurity = loadUserSecurity(normalisedUserId);
        return toUserDetails(normalisedUserId, userSecurity);
    }

    /**
     * Verifies a presented identifier and password together: the whole of {@code READ-USER-SEC-FILE},
     * {@code app/cbl/COSGN00C.cbl:L209-L257}, and the sign-on entry point that
     * {@code com.cardemo.service.auth.AuthenticationService} must call.
     *
     * <p><strong>Both arguments are expected raw, exactly as received from the request.</strong> This
     * method folds both to upper case internally with {@link Locale#ROOT} - the identifier per
     * {@code app/cbl/COSGN00C.cbl:L132-L134} and the password per {@code :L135-L136} - so that a caller
     * cannot forget to. A caller must <strong>not</strong> fold, trim or pad either value beforehand;
     * doing so would duplicate the normalisation this class owns.
     *
     * <p>Order of operations, reproducing the source exactly. The identifier is checked for emptiness
     * before the password, because {@code EVALUATE TRUE} at {@code :L117} takes the first matching branch
     * and {@code :L118} precedes {@code :L123}. No lookup is performed when either is blank, which is the
     * effect of the {@code IF NOT ERR-FLG-ON} gate at {@code :L138}. Only then is the row read and the
     * credential compared, once, at {@code :L223}.
     *
     * <p>The returned principal has its credential <strong>erased</strong>, so the stored hash does not
     * travel beyond this class on the sign-on path. Its name and single authority are what
     * {@link JwtTokenProvider} needs to issue a token, and nothing more is exposed. The two name fields of
     * {@code app/cpy/CSUSR01Y.cpy:L19-L20} are personal data and are not carried.
     *
     * <p>Side effects: none. One read-only query and <strong>exactly one</strong> BCrypt comparison on
     * every path that reaches the store - against the stored digest when a row was found, and against
     * {@link #dummyCredentialDigest} when none was, so that the two cost the same. Nothing is written and
     * no counter is incremented here; see the class documentation on why the attempts counter is emitted by
     * the service layer instead.
     *
     * @param userId           the identifier as presented, in any case, unfolded and untrimmed; must not
     *                         be {@code null} or blank
     * @param presentedPassword the password as presented, in any case, unfolded and untrimmed; must not be
     *                         {@code null} or blank. It is never logged and never leaves this method other
     *                         than as an argument to {@link PasswordEncoder#matches(CharSequence, String)}
     * @return the authenticated principal, with the folded identifier as its name, exactly one of
     *         {@code ROLE_ADMIN} or {@code ROLE_USER} as its authority and <em>no</em> credential; never
     *         {@code null}
     * @throws BadCredentialsException             on every credential refusal this method makes: either
     *                                             value {@code null} or blank, reproducing
     *                                             {@code app/cbl/COSGN00C.cbl:L118-L121} and
     *                                             {@code :L123-L126}; a password that does not match,
     *                                             the {@code :L241-L246} branch; and <strong>an
     *                                             identifier no row bears</strong>, the
     *                                             {@code :L247-L251} branch. The last two carry one
     *                                             identical message and one identical type and have
     *                                             performed identical work, so a caller cannot tell them
     *                                             apart by body, by status or by elapsed time
     * @throws InternalAuthenticationServiceException if the read fails, the {@code :L252-L256} branch
     * @throws IllegalStateException               if the loaded row carries no user class
     */
    @Transactional(readOnly = true)
    public UserDetails authenticate(final String userId, final String presentedPassword) {
        // app/cbl/COSGN00C.cbl:L117-L130 - the identifier is tested first, then the password, and the
        // read at :L211-L219 is reached only when neither is blank (the :L138 gate).
        if (isBlank(userId)) {
            LOG.debug("Sign-on refused: no user identifier was presented "
                    + "(app/cbl/COSGN00C.cbl:L118-L121).");
            throw new BadCredentialsException(MESSAGE_USER_ID_REQUIRED);
        }
        if (isBlank(presentedPassword)) {
            LOG.debug("Sign-on refused: no password was presented (app/cbl/COSGN00C.cbl:L123-L126).");
            throw new BadCredentialsException(MESSAGE_PASSWORD_REQUIRED);
        }

        final String normalisedUserId = normaliseUserId(userId);
        final String normalisedPassword = normalisePresentedPassword(presentedPassword);
        final Optional<UserSecurity> located = findUserSecurity(normalisedUserId);

        if (located.isEmpty()) {
            // app/cbl/COSGN00C.cbl:L247-L251 - WHEN 13, 'User not found. Try again ...'.
            //
            // The verification below is performed even though there is nothing to verify against, and it is
            // NOT dead work: it is what makes this path cost what the path below costs. Skipping it would
            // let a caller distinguish a known identifier from an unknown one by elapsed time alone, which
            // is the same disclosure the shared message exists to prevent. The digest is unmatchable by
            // construction, so the result is always false and is deliberately discarded.
            passwordEncoder.matches(normalisedPassword, this.dummyCredentialDigest);

            LOG.debug("Sign-on refused: the presented user identifier resolved to no row "
                    + "(app/cbl/COSGN00C.cbl:L247-L251).");
            // The SAME type and the SAME message as the mismatch below. The type is part of the contract
            // here: com.cardemo.service.auth.AuthenticationService maps exception types onto HTTP outcomes,
            // so a distinct type would be an enumeration oracle however identical the message.
            throw new BadCredentialsException(MESSAGE_CREDENTIALS_REJECTED);
        }
        final UserSecurity userSecurity = located.get();

        // app/cbl/COSGN00C.cbl:L223 - IF SEC-USR-PWD = WS-USER-PWD. Both operands were folded to upper
        // case at :L132-L136; the plaintext equality of the source is now a BCrypt verification. Called
        // exactly once, because BCrypt is deliberately expensive.
        if (!passwordEncoder.matches(normalisedPassword, userSecurity.getPasswordHash())) {
            LOG.debug("Sign-on refused: the presented password did not match the stored credential "
                    + "(app/cbl/COSGN00C.cbl:L241-L246).");
            throw new BadCredentialsException(MESSAGE_CREDENTIALS_REJECTED);
        }

        // app/cbl/COSGN00C.cbl:L226-L227 - identity and user class established. :L228 zeroes
        // CDEMO-PGM-CONTEXT and :L231-L239 transfers to the admin or main menu; neither has a counterpart
        // here, because the enter/re-enter flag has no stateless equivalent and navigation is URL-based.
        final User principal = toUserDetails(normalisedUserId, userSecurity);
        principal.eraseCredentials();

        LOG.debug("Sign-on accepted (app/cbl/COSGN00C.cbl:L222-L240).");
        return principal;
    }

    /**
     * Folds the presented <strong>user identifier</strong> to upper case, reproducing the first of the two
     * {@code MOVE FUNCTION UPPER-CASE} statements - {@code app/cbl/COSGN00C.cbl:L132-L134}, whose single
     * {@code MOVE} feeds both {@code WS-USER-ID} and {@code CDEMO-USER-ID}.
     *
     * <p>{@link Locale#ROOT} is mandatory, not stylistic: the platform-default overload would fold a
     * dotless {@code i} to {@code U+0130} under a Turkish locale and break the key lookup on that host
     * alone. The value is <strong>not trimmed and not padded</strong> - see the class documentation for why
     * padding to the source's {@code PIC X(08)} width would be wrong here.
     *
     * <p>This is one of exactly two folding sites in this class, and it is deliberately separate from
     * {@link #normalisePresentedPassword(String)} rather than shared with it. The two are not duplication:
     * the source performs two distinct {@code MOVE} statements over two distinct fields, and one method per
     * statement keeps each traceable to its own locator, which is what the paragraph-level traceability
     * requirement asks for. Sharing one helper would collapse two cited behaviours into one and make it
     * impossible to tell by inspection that both credentials are folded.
     *
     * <p>Side effects: none; a pure function of its argument.
     *
     * @param userId the identifier as presented; must not be {@code null}, which its callers have already
     *               established
     * @return the identifier folded to upper case, never trimmed and never padded
     */
    private static String normaliseUserId(final String userId) {
        return userId.toUpperCase(Locale.ROOT);
    }

    /**
     * Folds the presented <strong>password</strong> to upper case, reproducing the second of the two
     * {@code MOVE FUNCTION UPPER-CASE} statements - {@code app/cbl/COSGN00C.cbl:L135-L136} - so that the
     * value compared at {@code :L223} is the folded one, exactly as {@code WS-USER-PWD} holds it.
     *
     * <p><strong>This is the single most easily-missed behaviour in the security package.</strong> Folding
     * the identifier but not the password is the obvious mistake and a <b>High</b>-severity parity break:
     * it silently refuses every user who types a password in lower case, while the legacy system admits
     * them. The digests written by {@code V3__seed_data.sql} were computed from the source literal, so this
     * fold is precisely what allows a lower-case entry to verify.
     *
     * <p>{@link Locale#ROOT} for the same reason as the identifier. <strong>Not trimmed and not padded</strong>:
     * padding to the {@code PIC X(08)} width declared at {@code app/cpy/CSUSR01Y.cpy:L21} would append
     * spaces absent from the hashed value and fail every verification.
     *
     * <p>Side effects: none; a pure function of its argument. The returned value is never logged and is
     * passed only to {@link PasswordEncoder#matches(CharSequence, String)}.
     *
     * @param presentedPassword the password as presented; must not be {@code null}, which its caller has
     *                          already established
     * @return the password folded to upper case, never trimmed and never padded
     */
    private static String normalisePresentedPassword(final String presentedPassword) {
        return presentedPassword.toUpperCase(Locale.ROOT);
    }

    /**
     * Reports whether a presented credential value is absent or contains no non-whitespace character: the
     * analogue of the COBOL {@code = SPACES OR LOW-VALUES} tests at {@code app/cbl/COSGN00C.cbl:L118} and
     * {@code :L123}.
     *
     * <p>{@link String#isBlank()} is the right predicate rather than {@code isEmpty()}, because a
     * fixed-width COBOL field holding only spaces satisfies {@code = SPACES}, and an all-whitespace
     * submission is the closest stateless equivalent. Note that this only <em>detects</em> whitespace; it
     * never removes any, so the no-trim convention is intact.
     *
     * <p>Side effects: none; a pure function of its argument.
     *
     * @param value the value to test; may be {@code null}
     * @return {@code true} when {@code value} is {@code null} or blank, {@code false} otherwise
     */
    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }

    /**
     * Reads one row by key: the {@code EXEC CICS READ} of {@code app/cbl/COSGN00C.cbl:L211-L219}, with
     * {@code RIDFLD (WS-USER-ID)} at {@code :L215} and {@code KEYLENGTH} of 8 at {@code :L216} matching the
     * {@code KEYS(8,0)} of {@code app/jcl/DUSRSECJ.jcl:L65}.
     *
     * <p>Maps the three non-success outcomes of the {@code EVALUATE WS-RESP-CD} at {@code :L221} onto typed
     * exceptions. An empty result is the {@code WHEN 13} branch of {@code :L247-L251} and becomes
     * {@link UsernameNotFoundException}; a failure of the read itself is the {@code WHEN OTHER} branch of
     * {@code :L252-L256} and becomes {@link InternalAuthenticationServiceException} <em>wrapping</em> the
     * cause, so nothing is swallowed and the root cause survives, as clause B requires.
     *
     * <p>The {@link Optional} is examined explicitly: its absent case is tested and thrown on before the
     * value is ever unwrapped, and it is never flattened into a null default. An absent row is a
     * first-class sign-on outcome, not a null to be propagated into the caller.
     *
     * <p>The rejection message is the shared {@link #MESSAGE_CREDENTIALS_REJECTED}, identical to the
     * password-mismatch message, so that the caller cannot distinguish an unknown identifier from a wrong
     * password. Neither the identifier nor any other input is placed in the message or the log record.
     *
     * <p>Side effects: none. One read-only query; no write, no upsert, no lazy association to traverse -
     * the aggregate has none, which is why a read-only transaction fully satisfies it even with
     * {@code spring.jpa.open-in-view} disabled.
     *
     * @param normalisedUserId the already-folded identifier to read by; must not be {@code null}
     * @return the loaded row, never {@code null}
     * @throws UsernameNotFoundException           if no row bears that identifier
     * @throws InternalAuthenticationServiceException if the read fails
     */
    private UserSecurity loadUserSecurity(final String normalisedUserId) {
        final Optional<UserSecurity> found = findUserSecurity(normalisedUserId);

        if (found.isEmpty()) {
            // app/cbl/COSGN00C.cbl:L247-L251 - WHEN 13, 'User not found. Try again ...'. The message is
            // deliberately the same one a password mismatch produces.
            //
            // This throw site is reached from loadUserByUsername only, which the framework invokes and
            // which verifies no credential, so UsernameNotFoundException is both required by the
            // UserDetailsService contract and harmless here. The application sign-on path does NOT come
            // through here: authenticate() handles an absent row itself, so that it can perform the
            // constant-work verification first and refuse with the same type a mismatch produces.
            LOG.debug("Sign-on refused: the presented user identifier resolved to no row "
                    + "(app/cbl/COSGN00C.cbl:L247-L251).");
            throw new UsernameNotFoundException(MESSAGE_CREDENTIALS_REJECTED);
        }
        return found.get();
    }

    /**
     * Performs the keyed read of {@code app/cbl/COSGN00C.cbl:L211-L219} and reports its outcome as an
     * {@link Optional}, mapping only the {@code WHEN OTHER} store failure of {@code :L252-L256} onto an
     * exception.
     *
     * <p>Extracted so that the two callers can treat an absent row differently <em>without</em> duplicating
     * the read or its failure mapping: {@link #loadUserByUsername(String)} needs the
     * {@link UsernameNotFoundException} its framework contract specifies, while
     * {@link #authenticate(String, String)} must first spend the same BCrypt work a found row would have
     * cost and then refuse indistinguishably. One read, one failure mapping, two documented outcomes.
     *
     * <p>Side effects: none. One read-only query; no write, no upsert, no lazy association to traverse -
     * the aggregate has none, which is why a read-only transaction fully satisfies it even with
     * {@code spring.jpa.open-in-view} disabled.
     *
     * @param normalisedUserId the already-folded identifier to read by; must not be {@code null}
     * @return the loaded row, or empty when no row bears that identifier
     * @throws InternalAuthenticationServiceException if the read itself fails
     */
    private Optional<UserSecurity> findUserSecurity(final String normalisedUserId) {
        try {
            return userSecurityRepository.findById(normalisedUserId);
        } catch (final DataAccessException cause) {
            // app/cbl/COSGN00C.cbl:L252-L256 - WHEN OTHER, 'Unable to verify the User ...'. The cause is
            // preserved; no credential, hash or identifier is placed in the message.
            LOG.warn("Sign-on could not be completed: the user security store could not be read "
                    + "(app/cbl/COSGN00C.cbl:L252-L256).", cause);
            throw new InternalAuthenticationServiceException(
                    "The user security store could not be read while verifying a sign-on request.", cause);
        }
    }

    /**
     * Adapts a loaded row to a Spring Security principal, reproducing the identity establishment of
     * {@code app/cbl/COSGN00C.cbl:L226-L227}.
     *
     * <p>{@code UserSecurity} deliberately does not implement {@code UserDetails} - it is a pure data
     * holder that imports nothing from Spring - so an adaptation is required. It is performed with
     * {@link User}, the framework's own implementation, rather than with a bespoke principal type: this
     * package is contracted to exactly four files, so introducing a fifth would be a defect, and
     * {@link User} already supplies everything needed.
     *
     * <p>The principal's name is the <em>folded presented identifier</em>, not the stored
     * {@code sec_usr_id}. {@code :L226} moves {@code WS-USER-ID} - the folded input - into
     * {@code CDEMO-USER-ID}, so this is the faithful reading; it also avoids carrying the trailing blanks
     * that a {@code CHAR(8)} column returns into a downstream token subject.
     *
     * <p>The 80-byte record has no activation, expiry or lock field, so all four of {@link User}'s
     * boolean states are true - which is what the three-argument constructor supplies. Adding an account
     * state that the source does not have would be an invention. The two name fields are not carried:
     * they are personal data with no role in authentication.
     *
     * <p>The returned principal carries the stored hash as its password, because {@link User} requires a
     * non-null credential and because that is the {@code UserDetailsService} contract's designated channel.
     * {@link #authenticate(String, String)} erases it before returning to an application caller.
     *
     * <p>Side effects: none; a pure function of its arguments.
     *
     * @param normalisedUserId the folded identifier to use as the principal's name; must not be
     *                         {@code null}
     * @param userSecurity     the loaded row supplying the credential and the user class; must not be
     *                         {@code null}
     * @return a fresh, mutable-credential principal, never {@code null}
     * @throws IllegalStateException if the row carries no user class
     */
    private static User toUserDetails(final String normalisedUserId, final UserSecurity userSecurity) {
        return new User(normalisedUserId, userSecurity.getPasswordHash(),
                authoritiesOf(userSecurity.getSecUsrType()));
    }

    /**
     * Derives the granted authority from the user class byte: the target of the {@code CDEMO-USRTYP-ADMIN}
     * branch at {@code app/cbl/COSGN00C.cbl:L230-L240}, whose two outcomes are the {@code 'A'} and
     * {@code 'U'} condition names declared against {@code CDEMO-USER-TYPE} at
     * {@code app/cpy/COCOM01Y.cpy:L26-L28} and stored in {@code SEC-USR-TYPE} at
     * {@code app/cpy/CSUSR01Y.cpy:L22}.
     *
     * <p>In the legacy program that branch <em>routed</em>, transferring an administrator to
     * {@code COADM01C} and a standard user to {@code COMEN01C}. Here navigation is URL-based, so the
     * derived authority no longer routes anything; it only decides authorisation. Exactly one authority is
     * granted - never both, never a wildcard and never an additional privileged authority - which is
     * clause D's least-privilege requirement applied to this mapping.
     *
     * <p>The authority strings are taken from {@link JwtTokenProvider#ADMIN_AUTHORITY} and
     * {@link JwtTokenProvider#USER_AUTHORITY} rather than written as literals here. That class is the
     * canonical home of the mapping, and the divergence it guards against is <b>High</b> severity and
     * fails silently: were the authority granted here and the authority expected by the filter chain ever
     * to differ, the token would still verify, nothing would be logged, and every authorised request would
     * simply be refused. Binding both sides to one pair of compile-time constants removes that failure mode
     * by construction and satisfies clause C's prohibition on duplication.
     *
     * <p>Binding to the constants rather than injecting {@link JwtTokenProvider} and calling its mapping
     * method is deliberate. The constants are compile-time values, so this introduces no runtime coupling
     * whatsoever; injecting the provider would instead make credential loading depend on a configured JWT
     * signing key, inverting the layering so that no user could be looked up until token issuance was
     * configured.
     *
     * <p>The {@code switch} is an exhaustive expression over the two-constant enum with <strong>no default
     * branch</strong>, which is load bearing twice over: there is no fall-through for the compiler to
     * reject under {@code -Xlint:all -Werror}, and should a third user class ever be added this method
     * stops compiling instead of silently granting a wrong authority.
     *
     * <p>Side effects: none; a pure function of its argument.
     *
     * @param userType the user class read from {@code sec_usr_type}; must not be {@code null}
     * @return an immutable single-element authority collection, never {@code null} and never empty
     * @throws IllegalStateException if {@code userType} is {@code null}. A stored row without a user class
     *                               must fail rather than default: defaulting would grant access on the
     *                               strength of missing information, which is the unsafe default clause A
     *                               forbids. {@link IllegalStateException} rather than an argument
     *                               exception because the value comes from persisted state, not from the
     *                               caller, and the column is {@code NOT NULL} with a check constraint -
     *                               so reaching this branch means the schema invariant was bypassed
     */
    private static Collection<GrantedAuthority> authoritiesOf(final UserType userType) {
        if (userType == null) {
            throw new IllegalStateException(
                    "A user_security row carries no user class, so no authority can be derived. "
                            + "app/cpy/COCOM01Y.cpy:L26-L28 defines exactly two - 'A' for an administrator "
                            + "and 'U' for a standard user - and there is deliberately no permissive "
                            + "default. Check that sec_usr_type is populated and satisfies the check "
                            + "constraint declared by V1__create_schema.sql.");
        }
        final String authority = switch (userType) {
            case ADMIN -> JwtTokenProvider.ADMIN_AUTHORITY;
            case USER -> JwtTokenProvider.USER_AUTHORITY;
        };
        return List.of(new SimpleGrantedAuthority(authority));
    }
}
