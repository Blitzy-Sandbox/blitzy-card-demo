/*
 ******************************************************************
 * Program     : SecurityConfig.java
 * Application : CardDemo
 * Type        : Java Spring Configuration (security layer)
 * Function    : Stateless bearer-token security chain, role-based
 *               authorisation over the 17 sourced CICS transactions,
 *               BCrypt password encoding and the single symmetric
 *               JWT decoder.
 * Source      : app/csd/CARDDEMO.CSD (505 lines; 18 DEFINE TRANSACTION,
 *               18 DEFINE PROGRAM, 8 DEFINE FILE, 17 DEFINE MAPSET)
 *               + app/cpy/COCOM01Y.cpy:L25-L28 (CDEMO-USER-ID X(08),
 *                 CDEMO-USER-TYPE X(01) with 88-levels 'A' and 'U')
 *               + app/cbl/COSGN00C.cbl (260 lines; sign-on)
 *               + app/cbl/COMEN01C.cbl:L149-L150 (identity MOVEs
 *                 commented out - the bearer-token semantic)
 *               + app/cpy/CSUSR01Y.cpy (80-byte SEC-USER-DATA)
 *               @ 7756d89
 * Replaces    : the CICS region's transaction security, COMMAREA
 *               identity propagation across EXEC CICS XCTL, and the
 *               DFHCSDUP CSD installation job app/jcl/CBADMCDJ.jcl
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
package com.cardemo.config;

import com.cardemo.observability.CorrelationIdFilter;
import com.cardemo.security.JwtAuthenticationFilter;
import com.cardemo.security.JwtTokenProvider;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The application's single security policy: a stateless bearer-token filter chain, deny-by-default
 * authorisation over exactly the seventeen sourced CICS transactions, the BCrypt password encoder and the
 * one symmetric HMAC token decoder.
 *
 * <h2>What it does</h2>
 *
 * <p>This class is the Java replacement for three separate legacy mechanisms, and it is the only place any
 * of them survives:
 *
 * <ul>
 *   <li><strong>The CICS transaction security table.</strong> {@code app/csd/CARDDEMO.CSD} is 505 lines and
 *       declares 18 {@code DEFINE TRANSACTION} entries, 18 {@code DEFINE PROGRAM} entries, 8
 *       {@code DEFINE FILE} entries and 17 {@code DEFINE MAPSET} entries. Each transaction identifier was
 *       the unit of access control in the region; each becomes an HTTP request matcher below.</li>
 *   <li><strong>COMMAREA identity propagation.</strong> Identity travelled from program to program inside
 *       the communication area across {@code EXEC CICS XCTL}. It now travels inside a signed token, and the
 *       chain assembled here is what validates it.</li>
 *   <li><strong>The plaintext password comparison.</strong> {@code app/cbl/COSGN00C.cbl:L223} compares
 *       {@code SEC-USR-PWD} with {@code WS-USER-PWD} byte for byte. The BCrypt encoder published here is
 *       what {@code com.cardemo.security.CardDemoUserDetailsService} uses instead.</li>
 *   </ul>
 *
 * <p>It additionally <strong>supersedes {@code app/jcl/CBADMCDJ.jcl}</strong>, the DFHCSDUP job that
 * installed those resource definitions into the region - {@code EXEC PGM=DFHCSDUP,REGION=0M} at
 * {@code :L27} and {@code SYSIN DD *,SYMBOLS=JCLONLY} at {@code :L33}. That member has
 * <strong>no Java analogue other than this class</strong>: there is no separate resource-definition step in
 * the target, because the authorisation surface is declared in code and applied at startup. It is recorded
 * here so that the traceability matrix can account for the member rather than leaving it unmapped.
 *
 * <p>Four beans are published and nothing else:
 *
 * <ol>
 *   <li>{@link #securityFilterChain(HttpSecurity, JwtAuthenticationFilter)} - the chain, the session policy
 *       and the authorisation rules;</li>
 *   <li>{@link #passwordEncoder()} - BCrypt at the strength the seed migration used;</li>
 *   <li>{@link #jwtDecoder()} - the one symmetric HMAC decoder in the application.</li>
 * </ol>
 *
 * <h2>What it deliberately does not declare</h2>
 *
 * <p>Rule 1 Clause C requires that duplication be avoided, and Clause B forbids dead code. Each omission
 * below is therefore deliberate and is owned elsewhere:
 *
 * <ul>
 *   <li><strong>Neither filter is re-declared.</strong> {@code com.cardemo.observability.CorrelationIdFilter}
 *       and {@code com.cardemo.security.JwtAuthenticationFilter} are components in their own packages. The
 *       authentication filter is <em>injected and positioned</em>; the correlation filter is not touched at
 *       all, for the ordering reason set out below.</li>
 *   <li><strong>No resource-server key location.</strong> The two Boot properties that would point the
 *       starter at a remote key set or at a public key file on disk are deliberately absent from
 *       {@code src/main/resources} and are not named anywhere in this class either. A symmetric HMAC key has
 *       no such property - there is no endpoint to fetch and no certificate to read - so the decoder is
 *       built here in Java from the externalised key. Setting either property would name a location that
 *       does not exist, which is unreachable configuration.</li>
 *   <li><strong>No separate token library.</strong> The Nimbus path reached through
 *       {@code spring-boot-starter-oauth2-resource-server}, which the parent manages at 3.5.11, is the only
 *       one used. None of the three third-party token libraries in common use is added, and this file adds
 *       no dependency of any kind; the build file is the single place dependencies are declared and
 *       pinned.</li>
 *   <li><strong>No actuator exposure list.</strong> Which management endpoints exist is owned by
 *       {@code src/main/resources/application.yml}; this class only decides who may reach the ones that do.
 *       The two decisions are related but distinct, and restating the list here would let the pair drift.
 *       Exposure is not access: all three exposed endpoints exist, and exactly one of them - the metrics
 *       scrape - requires a credential.</li>
 *   <li><strong>No second {@code UserDetailsService} bean.</strong> The metrics scrape principal is built
 *       inline inside {@code metricsScrapeFilterChain} and confined to that chain's own authentication
 *       manager, so it cannot authenticate against any business rule. Publishing it as a bean would collide
 *       with {@code CardDemoUserDetailsService} under
 *       {@code spring.main.allow-bean-definition-overriding=false}, or silently widen Boot's default
 *       authentication manager.</li>
 *   <li><strong>No cross-origin configuration.</strong> No {@code CorsConfigurationSource} bean exists in
 *       the application, so no CORS filter is added to the chain. A wildcard origin on a bearer API is the
 *       unsafe default Clause A forbids, and no requirement asks for a browser origin to be trusted.</li>
 *   <li><strong>No method security.</strong> {@code @EnableMethodSecurity} is absent because no
 *       {@code @PreAuthorize}, {@code @PostAuthorize} or {@code @Secured} annotation exists in the tree. An
 *       enabler with nothing to enable is dead configuration. Authorisation is expressed once, as URL rules,
 *       which is also the closest analogue of a transaction-level CICS check.</li>
 *   <li><strong>No exception advice.</strong> There is no {@code @ControllerAdvice}, no
 *       {@code @ExceptionHandler} and no {@code @ResponseStatus} here; the two framework handlers wired into
 *       the chain produce the 401 and the 403, and controllers own their own status selection.</li>
 *   <li><strong>No user-identifier or password normalisation.</strong> {@code app/cbl/COSGN00C.cbl:L132-L136}
 *       upper-cases <em>both</em> the identifier and the password, trimming neither and padding neither, and
 *       {@code com.cardemo.security.CardDemoUserDetailsService} is the single authority that reproduces it.
 *       Folding case here as well would give two places to disagree.</li>
 *   <li><strong>No {@code java.time.Clock} bean.</strong> This class publishes exactly three beans, and a
 *       second declaration of a shared {@code Clock} would make the dependency ambiguous at startup. The
 *       time surface belongs to the configuration class that owns observability.</li>
 *   </ul>
 *
 * <p>This class does <strong>not</strong> publish the application's {@link java.time.Clock}, although an
 * earlier revision did. {@code com.cardemo.config.ObservabilityConfig} is the single owner of the time
 * surface, which is where the migration plan places it and where the parity argument for the zone belongs.
 * The token lifetime this surface enforces is measured against that same injected clock, so issuance,
 * verification and expiry cannot drift onto different time sources.
 *
 * <h2>The authorisation surface: exactly seventeen transactions</h2>
 *
 * <p>Every rule below traces to one {@code DEFINE TRANSACTION} entry in {@code app/csd/CARDDEMO.CSD}, cited
 * with its line number, and to the controller method that now serves it. The seventeen are
 * {@code CC00 CM00 CA00 CAVW CAUP CCLI CCDL CCUP CT00 CT01 CT02 CB00 CR00 CU00 CU01 CU02 CU03}.
 *
 * <dl>
 *   <dt>{@code CC00} at {@code :L378} - {@code COSGN00C}, sign-on</dt>
 *   <dd>{@code POST} to {@value #PATH_SIGN_ON} exactly - one concrete route, not a prefix - and the
 *       <strong>only unauthenticated operation in the application</strong>. The transaction identifier is
 *       declared by the program itself at {@code app/cbl/COSGN00C.cbl:L37},
 *       {@code WS-TRANID PIC X(04) VALUE 'CC00'}. Every other path beneath {@code /api/auth} falls through
 *       to {@code anyRequest().denyAll()}, so no future authentication operation can become anonymous
 *       without an explicit rule being added here.</dd>
 *   <dt>{@code CM00} at {@code :L399} - {@code COMEN01C}, main menu</dt>
 *   <dd>{@code GET} {@value #PATH_MENU_MAIN}, either role.</dd>
 *   <dt>{@code CA00} at {@code :L327} - {@code COADM01C}, admin menu</dt>
 *   <dd>{@code GET} {@value #PATH_MENU_ADMIN}, <strong>administrator only</strong>. The program declares
 *       {@code WS-TRANID PIC X(04) VALUE 'CA00'} at {@code app/cbl/COADM01C.cbl:L37}, and the legacy
 *       user-type gate is what this restriction reproduces.</dd>
 *   <dt>{@code CAVW} at {@code :L317} - {@code COACTVWC}, account view</dt>
 *   <dd>{@code GET} {@value #PATH_ACCOUNT_BY_ID}, either role.</dd>
 *   <dt>{@code CAUP} at {@code :L306} - {@code COACTUPC}, account update</dt>
 *   <dd>{@code PUT} {@value #PATH_ACCOUNTS}, either role.</dd>
 *   <dt>{@code CCLI} at {@code :L357} - {@code COCRDLIC}, card list</dt>
 *   <dd>{@code GET} {@value #PATH_CARDS}, either role.</dd>
 *   <dt>{@code CCDL} at {@code :L347} - {@code COCRDSLC}, card detail</dt>
 *   <dd>{@code GET} {@value #PATH_CARD_DETAIL}, either role.</dd>
 *   <dt>{@code CCUP} at {@code :L367} - {@code COCRDUPC}, card update</dt>
 *   <dd>{@code PUT} {@value #PATH_CARDS}, either role.</dd>
 *   <dt>{@code CT00} at {@code :L419} - {@code COTRN00C}, transaction list</dt>
 *   <dd>{@code GET} {@value #PATH_TRANSACTIONS}, either role.</dd>
 *   <dt>{@code CT01} at {@code :L429} - {@code COTRN01C}, transaction detail</dt>
 *   <dd>{@code GET} {@value #PATH_TRANSACTION_DETAIL}, either role.</dd>
 *   <dt>{@code CT02} at {@code :L439} - {@code COTRN02C}, transaction add</dt>
 *   <dd>{@code POST} {@value #PATH_TRANSACTIONS}, either role.</dd>
 *   <dt>{@code CB00} at {@code :L337} - {@code COBIL00C}, bill payment</dt>
 *   <dd>{@code POST} {@value #PATH_BILL_PAYMENTS}, either role.</dd>
 *   <dt>{@code CR00} at {@code :L409} - {@code CORPT00C}, report submission</dt>
 *   <dd>{@code POST} {@value #PATH_REPORTS}, either role.</dd>
 *   <dt>{@code CU00} at {@code :L449}, {@code CU01} at {@code :L459}, {@code CU02} at {@code :L469} and
 *       {@code CU03} at {@code :L479} - {@code COUSR00C}, {@code COUSR01C}, {@code COUSR02C} and
 *       {@code COUSR03C}, user administration</dt>
 *   <dd>Everything under {@value #PATH_ADMIN}, <strong>administrator only</strong>. All four are one rule
 *       because all four are one resource group and one privilege level.</dd>
 *   </dl>
 *
 * <p>Nothing else in the application is reachable. The final rule is
 * {@code anyRequest().denyAll()}, so a path that no rule names is refused rather than served, and a new
 * controller added without a rule fails closed. That direction is deliberate: the opposite default turns
 * every future omission into an unauthenticated endpoint.
 *
 * <h2>What these rules do NOT do: there is no object-level authorization</h2>
 *
 * <p><strong>Every rule above authorises by ROLE. None scopes a request to the caller's own records.</strong>
 * Read the ten {@code either role} entries literally: an authenticated standard user may retrieve or update
 * <em>any</em> account, browse and update <em>any</em> card, read and create <em>any</em> transaction, and
 * submit a bill payment or a report against an account that has nothing to do with them, by naming its
 * identifier. Authentication establishes <em>that</em> the caller is an operator and <em>which kind</em>; it
 * does not establish <em>whose</em> data they may touch, because nothing here decides that.
 *
 * <p>This is stated at length rather than left to be inferred from the absence of a check, because a reader
 * who sees a security configuration this explicit about seventeen transactions will reasonably assume it is
 * equally explicit about scope. It is not, and the gap is deliberate rather than overlooked.
 *
 * <p><strong>It is faithful.</strong> The source has no such check either: {@code app/cbl/COACTVWC.cbl} never
 * consults {@code CDEMO-USER-ID}, so a signed-on 3270 operator viewed any account by typing its number, and
 * the resource definition file gates transactions by nothing finer than the transaction identifier.
 *
 * <p><strong>And it is not implementable from the source.</strong> {@code app/cpy/CSUSR01Y.cpy} is an
 * eighty-byte record of exactly six fields - identifier, first name, last name, password, a one-character
 * type and twenty-three bytes of filler - and <em>not one of them references an account, a card or a
 * customer</em>. There is therefore no user-to-resource relation anywhere in the frozen corpus to enforce,
 * and inventing one would encode a business rule nobody stated: the ten seeded users are back-office
 * operators, five of them administrators, and the customer file is the data they operate <em>on</em> rather
 * than a directory of who they are.
 *
 * <p><strong>The consequence for deployment.</strong> Treat every authenticated principal as trusted with the
 * entire data set. This is safe exactly where the legacy system was safe - a closed internal network with
 * operator accounts issued by an administrator - and it must not be exposed to end customers, or to any
 * population in which one authenticated user must not see another's data, until the control is built.
 * <strong>Do not add a scoping predicate here on your own judgement.</strong> Four questions must be answered
 * first - the authoritative user-to-resource relation, whether an administrator keeps unrestricted scope,
 * whether an out-of-scope identifier answers {@code 403} or {@code 404}, and how the report and payment
 * operations are scoped - and they are set out with their owners as {@code DL-RR-10} in
 * {@code DECISION_LOG.md} and as {@code H-6} in {@code docs/validation-gates.md}.
 *
 * <h2>The eighteenth transaction has no program and therefore no rule</h2>
 *
 * <p>{@code app/csd/CARDDEMO.CSD} defines eighteen transactions, not seventeen. The eighteenth is
 * {@code CDV1} at {@code :L388}, whose body at {@code :L390} names {@code PROGRAM(COCRDSEC)}, and which the
 * definition itself describes as a developer transaction. {@code COCRDSEC} occurs in exactly two places
 * repository-wide, both inside the resource definition file - {@code app/csd/CARDDEMO.CSD:L211}, the
 * {@code DEFINE PROGRAM} entry, and {@code :L390} - and there is no program member for it anywhere under
 * {@code app/}. It is a dangling legacy definition with nothing to translate, and a transaction with no
 * program could not have been dispatched in the legacy region either.
 *
 * <p>Accordingly <strong>no rule, no matcher, no {@code permitAll}, no {@code denyAll}, no authority and no
 * placeholder exists for it in this file</strong>. Being unnamed, it falls to the deny-by-default rule like
 * any other unmapped path, which is the correct outcome reached by the correct route: absence of evidence
 * produces absence of surface, not an invented one.
 *
 * <h2>The four batch-only datasets have no surface at all</h2>
 *
 * <p>The same file defines exactly eight files - {@code ACCTDAT} at {@code :L1}, {@code CARDAIX} at
 * {@code :L13}, {@code CARDDAT} at {@code :L25}, {@code CCXREF} at {@code :L37}, {@code CUSTDAT} at
 * {@code :L50}, {@code CXACAIX} at {@code :L63}, {@code TRANSACT} at {@code :L76} and {@code USRSEC} at
 * {@code :L88}. The transaction category balance, disclosure group, transaction category and transaction
 * type datasets are <strong>absent from that list</strong>, and that absence is the evidence that they were
 * never reachable from the online region at all: they are batch-only.
 *
 * <p>This shapes the authorisation model directly. There is no controller, no endpoint and therefore no rule
 * for any of them, and none may be added: an online surface over a batch-only dataset would be new
 * behaviour, not migrated behaviour. Their Java counterparts are reached only by the Spring Batch jobs.
 *
 * <h2>Why a bearer token is the faithful replacement for the COMMAREA</h2>
 *
 * <p>The decisive evidence is at {@code app/cbl/COMEN01C.cbl:L145-L155}. Inside the option-dispatch branch
 * the MOVEs at {@code :L147}, {@code :L148} and {@code :L151} are live, but the two identity MOVEs at
 * {@code :L149} and {@code :L150} - {@code CDEMO-USER-ID} and {@code CDEMO-USER-TYPE} - are
 * <strong>commented out</strong>. Identity is therefore established exactly once, on the successful sign-on
 * branch at {@code app/cbl/COSGN00C.cbl:L226-L227}, and thereafter rides along in the communication area
 * across every {@code EXEC CICS XCTL} - four sites in total, at {@code app/cbl/COSGN00C.cbl:L231} and
 * {@code :L236} and at {@code app/cbl/COMEN01C.cbl:L153} and {@code :L176} - without being re-derived and
 * <strong>without re-reading the {@code USRSEC} file</strong>.
 *
 * <p>That is the semantic of a signed bearer token: authenticate once, then carry a self-describing,
 * tamper-evident credential. It is also the justification for the authentication filter performing
 * <strong>no per-request database read</strong>, which Clause A's performance principle would otherwise
 * require a defence of. The legacy system did not re-read the security file per screen, and neither does
 * this one.
 *
 * <p><strong>Accepted consequence.</strong> Because nothing is re-read, a change to a user's
 * type takes effect only when their current token expires, bounded by
 * {@code carddemo.security.jwt.expiration-minutes}, which defaults to 30. A revocation check would close
 * that window at the cost of reintroducing exactly the per-request read the source does not perform, so it
 * is deliberately not added. The window is documented rather than eliminated.
 *
 * <h2>Authority mapping: two values, one owner</h2>
 *
 * <p>{@code app/cpy/COCOM01Y.cpy} declares the identity pair the token carries:
 *
 * <ul>
 *   <li>{@code :L25} - {@code 10 CDEMO-USER-ID PIC X(08)}, which becomes the token subject;</li>
 *   <li>{@code :L26} - {@code 10 CDEMO-USER-TYPE PIC X(01)}, which becomes the role claim;</li>
 *   <li>{@code :L27} - {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'}, which becomes {@code ROLE_ADMIN};</li>
 *   <li>{@code :L28} - {@code 88 CDEMO-USRTYP-USER VALUE 'U'}, which becomes {@code ROLE_USER}.</li>
 * </ul>
 *
 * <p>Those are the only two values the field may hold, mirrored by
 * {@code com.cardemo.model.enums.UserType}, which has exactly two constants, and by the check constraint
 * that {@code V1__create_schema.sql} places on the user type column. The one-character code reaches an
 * authority string in exactly one place repository-wide: the constants
 * {@code com.cardemo.security.JwtTokenProvider.ADMIN_AUTHORITY} and {@code USER_AUTHORITY}. This class
 * <strong>reuses those constants and declares no second spelling of its own</strong>, and it reads the role
 * claim through {@code JwtTokenProvider.ROLE_CLAIM_NAME} rather than through a literal.
 *
 * <p><strong>This drift fails silently, which is why it is bound rather than re-spelled.</strong> If the
 * claim name or an authority spelling used
 * here ever diverged from the one the token was minted with, the token would still verify, no exception
 * would be raised and nothing would be logged - the authority set would simply come out empty and every
 * authorised request would be refused with no diagnostic. Binding to the published constants removes that
 * failure mode by construction, which is why no string literal for either value appears in the code below.
 *
 * <h2>Statelessness, and the two places it would leak</h2>
 *
 * <p>Transformation rule 7 replaces {@code EXEC CICS RETURN TRANSID ... COMMAREA}
 * ({@code app/cbl/COSGN00C.cbl:L98-L102}) with stateless REST plus token claims, and requires
 * <strong>no server-side session state</strong>. Three settings enforce that, and the third is the one that
 * is easy to miss:
 *
 * <ol>
 *   <li>{@code SessionCreationPolicy.STATELESS}, so the framework neither creates nor consults a session to
 *       carry the security context;</li>
 *   <li>CSRF token state disabled, because a CSRF token is server-side state whose purpose is to defend
 *       <em>cookie-borne</em> credentials. This API has none: the credential is an {@code Authorization}
 *       header that a foreign origin cannot cause a browser to attach. Leaving the protection on would add
 *       a session per unauthenticated caller and reject every legitimate write, which is a correctness
 *       defect masquerading as caution rather than security by default;</li>
 *   <li>an explicit {@code NullRequestCache}. <strong>This is not belt-and-braces, it is load bearing.</strong>
 *       The framework's request-cache configurer installs an {@code HttpSessionRequestCache} unconditionally -
 *       there is no stateless branch in it - and the exception-translation filter saves the current request
 *       into that cache <em>before</em> invoking the authentication entry point. Saving creates an HTTP
 *       session. The default matcher that would suppress the save drops its GET-only clause precisely when
 *       CSRF is disabled, so a plain unauthenticated request that does not ask for JSON would have created
 *       a session on the way to its own 401. Substituting the null cache is what actually keeps the
 *       stateless promise, and it is why no {@code spring.session} configuration is needed to keep it.</li>
 *   </ol>
 *
 * <p>Sign-off is likewise stateless: the framework's logout support is disabled, because a signed bearer
 * token cannot be invalidated server-side without the revocation store this design deliberately omits, there
 * is no session to invalidate, and no transaction in the resource definitions corresponds to a sign-off
 * operation. Leaving it enabled would publish a {@code /logout} endpoint ahead of authorisation that no
 * legacy transaction sanctions, which is invented surface.
 *
 * <h2>Response headers left to the framework defaults</h2>
 *
 * <p>This class never touches the header-writing part of the DSL, and that omission is deliberate rather
 * than an oversight. The framework's defaults are already the conservative choice for an API, so every
 * response - including the rejected ones - carries {@code X-Content-Type-Options: nosniff},
 * {@code X-Frame-Options: DENY}, {@code X-XSS-Protection: 0} and a no-store cache triple of
 * {@code Cache-Control}, {@code Pragma} and {@code Expires}. Two rules follow, and they pull in opposite
 * directions only in appearance:
 *
 * <ul>
 *   <li><strong>Do not disable them.</strong> Turning the defaults off, or relaxing the frame policy to
 *       accommodate a browser front end, would weaken the posture for a surface that has no browser front
 *       end to accommodate - the presentation layer is JSON over HTTP, and the legacy 3270 and BMS screens
 *       are consumed here only as field contracts.</li>
 *   <li><strong>Do not re-declare them either.</strong> Writing a header block that restates what the
 *       framework already emits adds a second place to maintain the same decision and reads as configuration
 *       that is doing work when it is not. That is exactly the dead configuration Clause B prohibits, so the
 *       correct expression of "keep the defaults" is to say so here and write no code.</li>
 *   </ul>
 *
 * <p>The one header this class does cause is the bearer challenge: a rejected request carries
 * {@code WWW-Authenticate: Bearer} with no realm and no error parameters, which is the bare RFC 6750 form
 * the configured entry point emits when no token was presented at all. The two headers a serialised refusal
 * adds beside it - {@code Content-Type: application/problem+json} and {@code Content-Length} - describe the
 * body this class now writes rather than the security posture, and are set by
 * {@link #writeProblemDetail(jakarta.servlet.http.HttpServletResponse, org.springframework.http.HttpStatus,
 * String, String, String)} alone.
 *
 * <h2>Every refusal carries the same body as every controller</h2>
 *
 * <p>A rejected request is answered with an RFC 9457 problem document carrying exactly the members a
 * {@code com.cardemo.controller} handler publishes: {@code type}, {@code title}, {@code status},
 * {@code detail}, {@code errorCode} and {@code correlationId}. Three conditions are serialised here because
 * they are refused before any handler is reached - an unauthenticated request, an authenticated but
 * unentitled one, and one whose body exceeds the byte bound - and each publishes a fixed title, a fixed
 * detail and a stable code that name the condition and nothing about the request. Before this, all three
 * answered with a status line and no body at all, so a client met two different error shapes on one API and a
 * rejected request could not be tied back to its own log line; that was a High-severity contract defect and
 * it is closed here rather than in a controller, because no controller runs on these paths.
 *
 * <p>The metrics scrape chain is deliberately not part of that: it answers an HTTP Basic challenge to a
 * Prometheus scraper rather than a JSON contract to an API client, so its refusal remains the framework's
 * own and is documented as such.
 *
 * <h2>How requests are matched</h2>
 *
 * <p>Every rule is built from an explicit {@code PathPatternRequestMatcher} rather than from a bare pattern
 * string. Handing the DSL a string looks tidier and is the more common spelling, but it makes the framework
 * <em>infer</em> a matcher implementation from what happens to be on the classpath: with Spring MVC present
 * it produces an MVC matcher, which requires a {@code HandlerMappingIntrospector} bean to exist at the
 * moment this chain is assembled. Three consequences follow, and all three are avoided by naming the matcher:
 *
 * <ul>
 *   <li>The chain could not be built at all in any context that does not carry the full MVC infrastructure,
 *       which makes this policy untestable in isolation: assembling it without an
 *       {@code mvcHandlerMappingIntrospector} bean present is a hard startup failure.</li>
 *   <li>Matcher selection would depend on classpath contents rather than on a decision recorded in source,
 *       which is the opposite of the explicit behaviour Clause A asks for.</li>
 *   <li>The inferred matcher additionally resolves servlet mappings while configuring, so a second
 *       registered servlet turns a working configuration into an ambiguous-mapping failure.</li>
 *   </ul>
 *
 * <p>The behaviour of the rules is unchanged by this choice. Spring MVC parses its own mappings with the same
 * pattern engine in this framework generation, so the same requests match either way; what changes is that
 * the coupling and the ambiguity disappear. Pattern semantics are therefore ordinary path-pattern semantics:
 * {@code /api/accounts/&#123;accountId&#125;} matches exactly one path segment, and {@code /api/admin/**}
 * matches the whole subtree - which is why an administration path added later is restricted by default rather
 * than by remembering to add a rule.
 *
 * <h2>Filter order</h2>
 *
 * <p>The required order is correlation, then token authentication, then role-based authorisation, then the
 * dispatcher servlet. It is achieved without registering anything twice:
 *
 * <ul>
 *   <li>{@code com.cardemo.observability.CorrelationIdFilter} publishes its own precedence as
 *       {@code CorrelationIdFilter.ORDER}, which is {@code Ordered.HIGHEST_PRECEDENCE + 2}. Boot registers the
 *       whole security chain at {@code SecurityProperties.DEFAULT_FILTER_ORDER}, which is {@code -100}, so the
 *       correlation filter still runs <em>before</em> the entire chain - including before a 401 or a 403 - and
 *       every request is correlated whatever its outcome. The offset of two exists so that it runs
 *       <em>after</em> Boot's {@code ServerHttpObservationFilter} at
 *       {@code Ordered.HIGHEST_PRECEDENCE + 1}, which is what opens the span that filter tags; its own
 *       Javadoc records why. <strong>It is therefore not added here.</strong> Adding it would register the same
 *       filter a second time, which is the duplication Clause C forbids and the cause of the doubled log lines
 *       that filter's own troubleshooting notes describe.</li>
 *   <li>{@code com.cardemo.security.JwtAuthenticationFilter} is inserted <em>inside</em> the chain,
 *       immediately after {@code SecurityContextHolderFilter}. The direction matters: that filter publishes
 *       its servlet-level precedence as {@code DEFAULT_FILTER_ORDER + 10} deliberately, so that its
 *       auto-registered servlet-level copy sits <em>after</em> the chain and is skipped, since it extends
 *       {@code OncePerRequestFilter}. Were the authentication established outside and ahead of the chain,
 *       the context-holder filter would replace it with an empty context and every request would come back
 *       401. Inserting after that filter is what makes the established authentication survive to the
 *       authorisation filter.</li>
 *   </ul>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>Three properties are bound, all by {@code @Value} on a constructor parameter. Every one arrives through
 * Spring's property resolution and nothing else: this class never reaches into the process environment or
 * the system properties directly, so the build and the runtime carry no environment-specific assumption of
 * the kind Clause C forbids, and every value stays profile-aware and overridable in a test. Locale-sensitive
 * formatting is likewise pinned to {@code Locale.ROOT} in every message, so a failure reads the same on a
 * developer's machine as it does in a container with a different default locale.
 *
 * <dl>
 *   <dt>{@value #KEY_SIGNING_KEY}</dt>
 *   <dd>The symmetric HMAC key. <strong>There is no default, no example and no fallback.</strong>
 *       {@code src/main/resources/application.yml} indirects it to the environment variable
 *       {@value #SIGNING_KEY_VARIABLE} with no default value, so an absent variable leaves the placeholder
 *       unresolvable and startup fails before a port is opened. That is the required behaviour: a committed
 *       default would be a shared, source-visible signing key. The key must be at least
 *       {@value #MINIMUM_SIGNING_KEY_BYTES} bytes when encoded as UTF-8.</dd>
 *   <dt>{@value #KEY_ISSUER}</dt>
 *   <dd>The issuer claim, an opaque identifier rather than a URL. Non-secret, so it carries a documented
 *       default of {@code carddemo}. Bound here so that the decoder <em>verifies</em> the issuer rather than
 *       merely accepting whatever a token asserts.</dd>
 *   <dt>{@value #KEY_BCRYPT_STRENGTH}</dt>
 *   <dd>The BCrypt cost. Published as {@value #REQUIRED_BCRYPT_STRENGTH} and, per transformation rule 15,
 *       <strong>fixed rather than tunable</strong>: {@code V3__seed_data.sql} wrote the ten seeded users at
 *       that exact cost, and a different value here would hash newly created users at a cost the seeded rows
 *       do not share. The constructor therefore rejects any other value at startup rather than accepting a
 *       silent split.</dd>
 *   </dl>
 *
 * <p>Two further keys are bound by {@code com.cardemo.security.JwtTokenProvider} and deliberately not
 * restated here: the token lifetime in seconds, and the issuer it stamps. The issuer is the one value both
 * sides must agree on, and both reach it through the same property key.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Build and unit-test with {@code ./mvnw -B -ntp clean verify}. This file compiles under
 * {@code -Xlint:all -Werror} with {@code failOnWarning} set, so any warning it introduced would fail the
 * build rather than be reported - though note that an <em>unused import</em> would not, because
 * {@code javac} 25 publishes no {@code unused} lint key, and malformed Javadoc would not either, because no
 * Javadoc plugin is bound in {@code pom.xml}; doclint is a separate explicit gate.
 *
 * <p>Measured gate results, test counts, coverage and the measured toolchain are deliberately
 * <strong>not</strong> restated in this file. They are published once, with the commands that reproduce
 * them, in section 0.4.5.1 of {@code docs/technical-specifications.md}; a figure copied into a Javadoc
 * comment is wrong the moment the tree changes.
 *
 * <p>To run the application, supply {@value #SIGNING_KEY_VARIABLE} in the environment - the repository ships
 * {@code .env.example} documenting the variable with no value - and start the dependency stack with
 * {@code docker compose up -d}. Anything that exercises HTTP needs that variable present; there is no
 * developer-convenience default, by design.
 *
 * <p>To verify the policy by hand once the application is up: a sign-on {@code POST} succeeds without a
 * token; the same call to any other path returns 401 with a {@code WWW-Authenticate: Bearer} challenge; a
 * standard user's token on an administration path returns 403 rather than 401, because the caller is
 * authenticated but not entitled; {@code /actuator/health} and {@code /actuator/info} answer anonymously;
 * and {@code /actuator/prometheus} answers 401 anonymously but 200 when the scrape credential is presented
 * as HTTP Basic, which {@code curl -u "$METRICS_SCRAPE_USERNAME:$METRICS_SCRAPE_PASSWORD"} demonstrates.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>Startup aborts on an unresolvable placeholder for the signing key</dt>
 *   <dd><strong>This is correct, intended behaviour</strong> and it is what proves the fail-fast contract.
 *       Set {@value #SIGNING_KEY_VARIABLE}. Never add a default to quiet it: a committed default is the one
 *       defect Clause D calls out by name.</dd>
 *   <dt>Startup aborts saying the signing key is an unresolved property placeholder</dt>
 *   <dd>The placeholder text itself was injected, which means the context resolves placeholders leniently
 *       instead of strictly. Set {@value #SIGNING_KEY_VARIABLE}, and restore the strict placeholder
 *       configurer: a lenient one turns every unset property in the application into a silently accepted
 *       literal, and for this property that literal would become a shared, source-visible signing key.</dd>
 *   <dt>Startup aborts saying the signing key is shorter than the required byte count</dt>
 *   <dd>The variable is set but too weak for the chosen MAC. Supply at least
 *       {@value #MINIMUM_SIGNING_KEY_BYTES} bytes of entropy. The check runs at startup rather than at first
 *       sign-on so that a weak key is a boot failure and not an intermittent one.</dd>
 *   <dt>Startup aborts on the BCrypt strength</dt>
 *   <dd>{@value #KEY_BCRYPT_STRENGTH} was overridden. Restore {@value #REQUIRED_BCRYPT_STRENGTH}; the value
 *       is pinned to the cost the seed migration used.</dd>
 *   <dt>Startup fails with a duplicate bean definition for the decoder or the password encoder</dt>
 *   <dd>Remove the duplicate from the {@code com.cardemo.security} package: this class is the designated
 *       owner of both beans. Two decoders are worse than a startup failure when they happen not to collide,
 *       because they can verify against different keys.</dd>
 *   <dt>Every request returns 401 although the token looks valid</dt>
 *   <dd>Three candidates, in order of likelihood: a role-claim name or authority spelling that drifted from
 *       {@code JwtTokenProvider}'s constants; a decoder built from a different key than the issuer signed
 *       with, which happens when two processes read different environments; or an authentication filter
 *       inserted outside the chain, where the context-holder filter overwrites the context.</dd>
 *   <dt>A request returns 403 rather than 401</dt>
 *   <dd>That is the designed distinction, not a defect: 401 means no usable credential was presented, 403
 *       means a valid one was presented by a principal that is not entitled. A standard user reaching an
 *       administration path is the ordinary case.</dd>
 *   <dt>A path that plainly exists returns 401 or 403 for everyone</dt>
 *   <dd>No rule names it, so deny-by-default refused it. Add the rule; do not relax the final rule.</dd>
 *   <dt>The container health probe or the metrics scrape starts failing with 401</dt>
 *   <dd>The two callers are governed by different chains, so a 401 means a different thing on each and the
 *       remedies are not interchangeable. <strong>The health probe is anonymous by necessity.</strong> The
 *       image's {@code HEALTHCHECK} issues a bare {@code GET /actuator/health/readiness} with no
 *       {@code Authorization} header, so the four management paths permitted below - the two anonymously
 *       exposed endpoints, {@value #PATH_HEALTH} and {@value #PATH_INFO}, plus the two paths the health
 *       group definitions create - must stay anonymous <em>for {@code GET}</em>, and a 401 on any of them
 *       means a rule was added or narrowed above them. The method qualifier is not pedantry: those four
 *       permits are declared with a {@code GET} matcher, so a probe rewritten to use {@code HEAD} - which
 *       looks equivalent and is what {@code curl -I} sends - matches no permit, falls through to the bearer
 *       chain and is refused 401 with a {@code WWW-Authenticate: Bearer} challenge. Measured on the shipped
 *       image: {@code GET /actuator/health} 200, {@code HEAD /actuator/health} 401. A probe that started
 *       failing after being "simplified" to {@code HEAD} is this, not a policy change. <strong>The metrics scrape is not anonymous, and is not in that list.</strong>
 *       {@value #PATH_PROMETHEUS} is governed by {@code metricsScrapeFilterChain}, which requires HTTP Basic
 *       credentials carrying {@value #SCRAPE_AUTHORITY}, so a 401 there is the designed answer to a caller
 *       that presented no usable credential rather than a defect - {@code curl} without {@code -u} is
 *       expected to receive one, together with a {@code WWW-Authenticate: Basic realm="carddemo-metrics-scrape"}
 *       challenge. Fix it on the caller side: {@code observability/prometheus.yml} configures
 *       {@code basic_auth} with a username and a {@code password_file} that {@code docker-compose.yml}
 *       materialises from the deployment's scrape password, so a 401 to Prometheus itself means that file
 *       and this application's {@value #KEY_SCRAPE_PASSWORD} disagree, or that
 *       {@value #KEY_SCRAPE_USERNAME} and {@value #KEY_SCRAPE_PASSWORD} are unset and the endpoint is
 *       therefore refusing every caller. The warning {@code ScrapeAuthenticationEntryPoint} logs separates
 *       exactly those two cases in words, because credentials that do not match and a deployment with no
 *       scrape principal at all call for opposite actions.</dd>
 *   <dt>A session cookie appears in a response</dt>
 *   <dd>Something reintroduced a session-backed request cache or a stateful configurer. The null request
 *       cache configured here is what prevents it; see the statelessness section for why removing it is not
 *       a simplification.</dd>
 *   </dl>
 *
 * <h2>Known limitations of this policy</h2>
 *
 * <p>Each is a deliberate scope boundary rather than an oversight, and each is disclosed so that a reader
 * does not mistake it for an omission:
 *
 * <ul>
 *   <li><strong>No transport security here.</strong> Termination is an infrastructure concern in this
 *       topology; a bearer token on a plaintext hop is replayable, so a real deployment must terminate TLS
 *       in front of the application.</li>
 *   <li><strong>No rate limiting.</strong> The sign-on path is anonymous by necessity and therefore
 *       brute-forceable; BCrypt's cost is the only throttle present.</li>
 *   <li><strong>No token revocation.</strong> A change to a user's type takes effect only when their current
 *       token expires, for the reason given under the COMMAREA section above.</li>
 *   <li><strong>Four anonymous management paths, and a scrape that is not one of them.</strong>
 *       {@value #PATH_HEALTH}, {@value #PATH_HEALTH_LIVENESS}, {@value #PATH_HEALTH_READINESS} and
 *       {@value #PATH_INFO} answer an anonymous {@code GET} without a credential, because the image's
 *       {@code HEALTHCHECK} probes readiness with no {@code Authorization} header and a rule requiring one
 *       would report a permanently unhealthy container. The permit is <strong>method-scoped to
 *       {@code GET}</strong>, so the anonymous surface is four path-and-method pairs rather than four whole
 *       paths: {@code HEAD} and every other method on the same four paths match no permit and are refused by
 *       the bearer chain, measured 401. Their bodies are status-only and the info body is empty, so what they
 *       disclose is that the application exists and whether it is up - an unauthenticated liveness oracle,
 *       which is the residual accepted here. <strong>{@value #PATH_PROMETHEUS} is authenticated:</strong> it is
 *       governed by {@code metricsScrapeFilterChain}, which is ordered ahead of the business chain, declares
 *       a security matcher for that one path, requires HTTP Basic credentials carrying
 *       {@value #SCRAPE_AUTHORITY} and denies every other request; measured, it answers 401 anonymously and
 *       200 to the scrape credential. The residual that survives is narrower than an open endpoint and is
 *       stated as what it is: the scrape body renders business series and discloses the exact runtime build
 *       through its JVM meters <em>to whoever holds that one credential</em>, which is a single shared
 *       static secret with no per-scraper identity and no rotation mechanism in this class, so the
 *       disclosure boundary is exactly one credential wide. <strong>An earlier revision of this entry read
 *       "Anonymous management endpoints ... Authenticating the scrape would require both the scrape
 *       configuration and the image health check to carry a credential", and that reading is withdrawn as
 *       measurably false in both halves:</strong> the scrape has been authenticated since
 *       {@code metricsScrapeFilterChain} was introduced, and {@code observability/prometheus.yml} already
 *       carries {@code basic_auth}, so neither change is outstanding. The already-correct statements under
 *       "No actuator exposure list" above and in the verify-by-hand paragraph are what this entry now
 *       agrees with.</li>
 *   <li><strong>No user-enumeration difference, deliberately.</strong> The legacy screen distinguished
 *       {@code 'User not found. Try again ...'} at {@code app/cbl/COSGN00C.cbl:L247-L251} from
 *       {@code 'Wrong Password. Try again ...'} at {@code :L241-L246}. The REST surface does not differentiate
 *       externally; the distinction survives as a typed exception and a structured log without the
 *       credential. This is a labelled deviation from parity, severity Medium, owned by
 *       {@code com.cardemo.service.auth.AuthenticationService} and recorded as boundary (2) of
 *       {@code DL-DV-10} in {@code DECISION_LOG.md}.</li>
 *   </ul>
 *
 * <h2>Findings register</h2>
 *
 * <p>Classified per Clause F, each with the remediation that closes it:
 *
 * <dl>
 *   <dt>Blocker - widening the token claim set</dt>
 *   <dd>The token carries exactly the subject, the role, the issuer, the issued-at and the expiry. A JWT is
 *       signed, not encrypted, so every claim is readable by whoever holds it. The customer identifier at
 *       {@code app/cpy/COCOM01Y.cpy:L33}, the three customer name fields at {@code :L34-L36}, the account
 *       identifier at {@code :L38}, the account status at {@code :L39} and above all the card number at
 *       {@code :L41} must never become claims. {@code JwtTokenProvider} enforces this on issuance; this
 *       class does not widen it on validation, and in particular maps no claim other than the role onto an
 *       authority. Remediation if it is ever widened: remove the claim, and treat every token already issued
 *       as disclosed.</dd>
 *   <dt>High - the previously hardcoded signing key</dt>
 *   <dd>Closed here. The key is environment-indirected with no default and validated fail-fast, and no part
 *       of its value appears in any message this class produces.</dd>
 *   <dt>High - claim-name and authority drift</dt>
 *   <dd>Closed by reusing {@code JwtTokenProvider}'s published constants instead of re-spelling them, since
 *       the failure mode is a silent authorisation denial.</dd>
 *   <dt>Closed - the two controllers this policy names are now authored</dt>
 *   <dd>{@code com.cardemo.controller.AuthController} and {@code com.cardemo.controller.AdminController} both
 *       exist, so the rules for {@value #PATH_SIGN_ON} and {@value #PATH_ADMIN} are
 *       verified against real base paths rather than asserted ahead of them, and neither is
 *       {@code Not available}. The evidence is the classes themselves: the sign-on route
 *       {@code POST /api/auth/signon} asserted at
 *       {@code src/test/java/com/cardemo/unit/config/SecurityConfigTest.java}, on the namespace convention
 *       the tree states of itself in
 *       the {@code BASE_PATH} declaration of
 *       {@code src/main/java/com/cardemo/controller/BillingController.java}, so no rule needed moving.
 *       The endpoint arithmetic is now read end to end from the mappings rather than partly assumed: the
 *       eight controllers publish two, four, one, one, three, two, one and three routes, which is exactly the
 *       seventeen the resource definitions declare - eighteen transactions less {@code CDV1}, whose
 *       {@code COCRDSEC} has no source in this repository. Both rules are exercised against the assembled
 *       filter chain by {@code src/test/java/com/cardemo/unit/config/SecurityConfigTest.java}, which drives
 *       the exact sign-on route anonymously and the administration routes as an anonymous caller, a standard
 *       user and an administrator in turn. The two base paths those rules match are pinned from the other
 *       side as well, by {@code src/test/java/com/cardemo/unit/controller/AuthControllerTest.java} and
 *       {@code src/test/java/com/cardemo/unit/controller/AdminControllerTest.java}: each asserts its
 *       controller's class-level mapping and route set, and each asserts that its controller carries no
 *       authorisation annotation of its own and reads no principal, so neither can acquire a second
 *       enforcement point that disagrees with this one. The residual failure mode is unchanged and still
 *       unmistakable if a base path is ever moved without moving its rule - sign-on returns 401, or the
 *       administration surface becomes reachable by a standard user.</dd>
 *   <dt>Medium - {@code EIBTRNID} has no citable locator</dt>
 *   <dd>The CICS transaction identifier usually described as the legacy per-request thread of identity is
 *       <strong>{@code Not available}</strong> in this corpus: it is supplied by the monitor, not declared in
 *       application source, and it has zero occurrences repository-wide at {@code 7756d89} - the complete
 *       field census of {@code app/cbl} being {@code EIBCALEN} and {@code EIBAID} only. No
 *       {@code app/...:Lnnn} locator is fabricated for it, because a false citation is itself an evidence
 *       defect. The citable per-request identity evidence used instead is {@code app/csd/CARDDEMO.CSD},
 *       {@code app/cbl/COSGN00C.cbl:L37} and {@code app/cbl/COCRDLIC.cbl:L295}. <em>What is needed:</em> a
 *       monitor definition this repository does not contain. It costs a citation, not a behaviour.</dd>
 *   <dt>High - the signing-key variable name and the token lifetime</dt>
 *   <dd>Closed. This file's own specification named the variable {@value #SIGNING_KEY_VARIABLE} and fixed the
 *       bearer lifetime at thirty minutes. Both had drifted: the tree had settled on {@code JWT_SECRET}, and
 *       the lifetime was expressed in seconds with a 3,600-second default - <em>double</em> the approved
 *       window, which matters because a bearer token cannot be revoked before it expires, so the lifetime is
 *       the exposure window of a leaked token. Keeping the drift was defensible only as long as it was
 *       uniform; the correct resolution is a rename carried through every site at once, and that is what was
 *       done: {@code application.yml} (both the key and the units, now
 *       {@code expiration-minutes} defaulting to 30), the two Java constants here and in
 *       {@code com.cardemo.security.JwtTokenProvider}, the environment template, the container image
 *       documentation, the build file, the vulnerability-scan suppressions, the sibling profiles' prose and
 *       the unit test that asserts the variable name. <em>The rule for any future rename is the same:</em>
 *       all sites in one change, never one alone, because a partial rename produces a failure message naming
 *       a variable the operator has already set.</dd>
 *   <dt>Blocker - no {@code java.time.Clock} bean existed, so the application could not start</dt>
 *   <dd>Closed, and <strong>not here</strong>. {@code com.cardemo.security.JwtTokenProvider} and the
 *       services and batch processors that stamp a record require a {@code Clock} by constructor, and while
 *       no configuration class declared one a packaged run failed context refresh with {@code No qualifying
 *       bean of type java.time.Clock}; components that worked around it by constructing
 *       {@code Clock.systemDefaultZone()} themselves were reading an ambient time source rather than a
 *       configured one. The severity was <strong>Blocker</strong> and not Medium, because the effect was
 *       total startup failure rather than a documentation gap. An interim revision of this class published
 *       the bean, on the reasoning that a bean nothing declares is a startup failure whereas a bean two
 *       classes declare is only an ambiguity. Both halves of that are true, and the conclusion still has to
 *       be <em>one</em> owner rather than <em>two</em>: {@code com.cardemo.config.ObservabilityConfig} is
 *       the designated owner of the time surface and publishes {@code Clock.systemDefaultZone()}, so the
 *       declaration here was removed in favour of it - twice, because a later revision reinstated it and
 *       the duplicate had to be taken out again. <em>Region-local rather than UTC</em> is itself a
 *       parity decision, argued at that bean: the legacy region rendered local civil time, so a UTC clock
 *       would shift every {@code CURDATE}, {@code CURTIME} and generated 26-character timestamp that Gate 1
 *       compares byte for byte, while changing nothing about what is stored - the persistence layer converts
 *       every instant to UTC on its own. The zone is still <em>pinnable</em>, by {@code carddemo.time.zone},
 *       and an unrecognised value still aborts startup with the offending text named; that capability moved
 *       to the owning class along with the bean rather than being dropped with it. <em>Invariant a reviewer
 *       can check mechanically:</em>
 *       {@code grep -rn -A2 "@Bean" src/main/java | grep "Clock clock"} returns exactly one line. Any
 *       second production declaration must delete that one in the same commit rather than sit beside it,
 *       because {@code spring.main.allow-bean-definition-overriding} is {@code false} in the base profile
 *       and a duplicate is a startup failure, not a silent last-one-wins.</dd>
 *   <dt>Low - locator correction</dt>
 *   <dd>The password comparison in {@code app/cbl/COSGN00C.cbl} is at {@code :L223}, inside the
 *       {@code WHEN 0} branch that begins at {@code :L222}, and not at {@code :L222} itself as earlier
 *       documentation stated. Verified by direct inspection at {@code 7756d89}.</dd>
 *   <dt>Low - a legacy asymmetry, preserved</dt>
 *   <dd>The wrong-password branch at {@code app/cbl/COSGN00C.cbl:L241-L246} does <strong>not</strong> set
 *       {@code WS-ERR-FLG}, whereas the user-not-found branch at {@code :L248} and the catch-all at
 *       {@code :L253} both do. It is a real asymmetry in the source, it is preserved rather than corrected
 *       by the service that owns sign-on, and it is recorded here only so that a reader of this policy does
 *       not mistake the uniform 401 for a loss of it.</dd>
 *   </dl>
 *
 * <h2>Thread safety and state</h2>
 *
 * <p>This class holds no mutable state. Its three fields are {@code final} and are assigned once, by the
 * constructor, from injected property values; every constant is {@code static final} and of an immutable
 * type; and there is no static field that is not a constant, no field injection, no setter and no service
 * lookup. Each bean method is therefore a pure function of already-validated state, a single instance is
 * safe for concurrent use, and Rule 1 Clause B's prohibition on global mutable state holds by construction
 * rather than by convention.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // Configuration keys. Each spelling exists exactly once in this file, so that a property rename
    // is a one-line change and a failure message can always name the key it is complaining about.

    /**
     * Property key holding the symmetric HMAC signing key.
     *
     * <p>Published by {@code src/main/resources/application.yml} as an indirection to the environment with
     * <strong>no default of any kind</strong>. The same key is bound by
     * {@code com.cardemo.security.JwtTokenProvider} for issuance, so both sides of the token necessarily
     * agree.
     */
    private static final String KEY_SIGNING_KEY = "carddemo.security.jwt.signing-key";

    /**
     * The one environment variable name used repository-wide for the signing key.
     *
     * <p>Named in failure messages so that an operator is told exactly what to set. Its
     * <strong>value is never named anywhere</strong> - not logged, not echoed, not summarised and not
     * digested.
     *
     * <p>The spelling is the mandated one, and it is one spelling everywhere: the base profile,
     * {@code com.cardemo.security.JwtTokenProvider}, the environment template,
     * the container image documentation, the build file, the vulnerability-scan suppressions and the unit
     * test that asserts the name all read {@code JWT_SIGNING_KEY} rather than {@code JWT_SECRET}. A partial
     * rename is the one outcome to avoid, because a half-configured
     * application fails with a message that names a variable the operator has already set.
     */
    private static final String SIGNING_KEY_VARIABLE = "JWT_SIGNING_KEY";

    /**
     * Property key holding the token issuer, an opaque identifier rather than a URL.
     *
     * <p>Bound so that the decoder <em>verifies</em> the issuer claim instead of accepting whatever a
     * presented token asserts. It carries a documented default because an issuer name is not a secret.
     */
    private static final String KEY_ISSUER = "carddemo.security.jwt.issuer";

    /**
     * Property key holding the BCrypt cost.
     *
     * <p>Published as {@value #REQUIRED_BCRYPT_STRENGTH} and pinned there by transformation rule 15; see
     * {@link #passwordEncoder()} for why it is validated rather than merely read.
     */
    private static final String KEY_BCRYPT_STRENGTH = "carddemo.security.bcrypt.strength";

    // Cryptographic invariants. These match com.cardemo.security.JwtTokenProvider exactly; a
    // divergence in either value would make every issued token unverifiable.

    /**
     * The JWS algorithm, HS256, matching the algorithm the issuer signs with.
     *
     * <p>HS256 is the correct choice rather than a stronger MAC because the documented key strength is at
     * least {@value #MINIMUM_SIGNING_KEY_BYTES} bytes, which is exactly this algorithm's 256-bit floor; a
     * stronger MAC would reject every key generated to the documented minimum.
     */
    private static final MacAlgorithm MAC_ALGORITHM = MacAlgorithm.HS256;

    /** Standard JCA name for the secret-key specification the MAC verifier is built from. */
    private static final String MAC_KEY_ALGORITHM = "HmacSHA256";

    /** Minimum signing-key length in bytes, being the 256-bit floor of {@link #MAC_ALGORITHM}. */
    private static final int MINIMUM_SIGNING_KEY_BYTES = 32;

    /**
     * Opening delimiter of a Spring property placeholder, used to detect one that was never resolved.
     *
     * <p>This guard exists because of what an unresolved placeholder would otherwise become. Spring Boot
     * registers a strict placeholder resolver, so in a normal application an unset variable aborts startup
     * before this class is constructed - which is the intended behaviour and is documented as such. But a
     * context configured to ignore unresolvable placeholders instead injects the placeholder <em>text</em>,
     * and that text is {@value #MINIMUM_SIGNING_KEY_BYTES} bytes or more, so it would satisfy every other
     * check here and be accepted as the signing key. Every such deployment would then share one key whose
     * value is readable in this source file - which is precisely the hardcoded-key defect this class exists
     * to close, arrived at by a different route. Detecting it costs one comparison.
     */
    private static final String UNRESOLVED_PLACEHOLDER_PREFIX = "${";

    /**
     * The one BCrypt cost this application accepts, per transformation rule 15.
     *
     * <p>It is the cost {@code V3__seed_data.sql} used for the ten seeded users, so it is a compatibility
     * constant rather than a tuning knob.
     */
    private static final int REQUIRED_BCRYPT_STRENGTH = 10;

    // Request paths. One constant per CICS transaction group, named for what it serves rather than for
    // the transaction identifier, with the identifier and its CSD line number in the documentation.

    /**
     * Sign-on, transaction {@code CC00} at {@code app/csd/CARDDEMO.CSD:L378} - {@code COSGN00C}.
     *
     * <p><strong>The exact route, not a prefix.</strong> An earlier form of this constant was
     * {@code /api/auth/**}, which granted anonymous access to every path under the namespace - including
     * ones no resource definition sanctions and none that exist yet, such as a token refresh, a password
     * reset or an enumeration helper. One transaction is defined at {@code CC00} and it is one endpoint, so
     * the matcher names that endpoint. Anything else the namespace later acquires is caught by
     * {@code anyRequest().denyAll()} until an authorisation rule is written for it deliberately, which is
     * the fail-closed direction to be wrong in.
     */
    private static final String PATH_SIGN_ON = "/api/auth/signon";

    /** Main menu, transaction {@code CM00} at {@code app/csd/CARDDEMO.CSD:L399} - {@code COMEN01C}. */
    private static final String PATH_MENU_MAIN = "/api/menu/main";

    /** Admin menu, transaction {@code CA00} at {@code app/csd/CARDDEMO.CSD:L327} - {@code COADM01C}. */
    private static final String PATH_MENU_ADMIN = "/api/menu/admin";

    /** Account view, transaction {@code CAVW} at {@code app/csd/CARDDEMO.CSD:L317} - {@code COACTVWC}. */
    private static final String PATH_ACCOUNT_BY_ID = "/api/accounts/{accountId}";

    /** Account update, transaction {@code CAUP} at {@code app/csd/CARDDEMO.CSD:L306} - {@code COACTUPC}. */
    private static final String PATH_ACCOUNTS = "/api/accounts";

    /**
     * Card list and card update - transaction {@code CCLI} at {@code app/csd/CARDDEMO.CSD:L357}
     * ({@code COCRDLIC}) on {@code GET}, transaction {@code CCUP} at {@code :L367} ({@code COCRDUPC}) on
     * {@code PUT}. One path, two transactions, distinguished by method.
     */
    private static final String PATH_CARDS = "/api/cards";

    /** Card detail, transaction {@code CCDL} at {@code app/csd/CARDDEMO.CSD:L347} - {@code COCRDSLC}. */
    private static final String PATH_CARD_DETAIL = "/api/cards/detail";

    /**
     * Transaction list and transaction add - {@code CT00} at {@code app/csd/CARDDEMO.CSD:L419}
     * ({@code COTRN00C}) on {@code GET}, {@code CT02} at {@code :L439} ({@code COTRN02C}) on {@code POST}.
     */
    private static final String PATH_TRANSACTIONS = "/api/transactions";

    /**
     * Transaction detail, transaction {@code CT01} at {@code app/csd/CARDDEMO.CSD:L429} -
     * {@code COTRN01C}.
     */
    private static final String PATH_TRANSACTION_DETAIL = "/api/transactions/detail";

    /** Bill payment, transaction {@code CB00} at {@code app/csd/CARDDEMO.CSD:L337} - {@code COBIL00C}. */
    private static final String PATH_BILL_PAYMENTS = "/api/billing/payments";

    /**
     * Report submission, transaction {@code CR00} at {@code app/csd/CARDDEMO.CSD:L409} -
     * {@code CORPT00C}.
     */
    private static final String PATH_REPORTS = "/api/reports";

    /**
     * User administration - transactions {@code CU00} at {@code app/csd/CARDDEMO.CSD:L449}, {@code CU01} at
     * {@code :L459}, {@code CU02} at {@code :L469} and {@code CU03} at {@code :L479}, fronting
     * {@code COUSR00C}, {@code COUSR01C}, {@code COUSR02C} and {@code COUSR03C}.
     *
     * <p>A prefix rather than four exact paths, because all four are one resource group at one privilege
     * level: an administration path added later is then restricted by default rather than by remembering to
     * add a rule.
     */
    private static final String PATH_ADMIN = "/api/admin/**";

    // Management paths. Exactly the three endpoints application.yml exposes, plus the two health group
    // paths its group definitions create. Listed as separate constants rather than as an array, so that
    // no mutable static state exists anywhere in this class.

    /** Aggregate health, answering status only because {@code show-details} is {@code never}. */
    private static final String PATH_HEALTH = "/actuator/health";

    /** Liveness probe, created by the health group whose only member is the liveness state. */
    private static final String PATH_HEALTH_LIVENESS = "/actuator/health/liveness";

    /**
     * Readiness probe, created by the health group covering the readiness state, the database, object
     * storage and the queue. This is the path the container image's health check requests.
     */
    private static final String PATH_HEALTH_READINESS = "/actuator/health/readiness";

    /** Build and environment information, which this application publishes as an empty document. */
    private static final String PATH_INFO = "/actuator/info";

    /**
     * Metrics scrape target, requested every fifteen seconds by the monitoring stack.
     *
     * <p><strong>This path is the one management path that is NOT anonymous.</strong> It is governed by
     * {@link #metricsScrapeFilterChain(HttpSecurity)}, a separate chain declared ahead of the business
     * chain, and it requires HTTP Basic credentials carrying {@value #SCRAPE_AUTHORITY}. The three health
     * and info paths above remain anonymous because a container orchestrator and a load balancer both
     * probe them without a credential; the scrape body, by contrast, renders business series - transaction
     * volumes, reject counts by reject code and authentication attempt counts - and is therefore a
     * disclosure surface rather than a liveness signal.
     */
    private static final String PATH_PROMETHEUS = "/actuator/prometheus";

    // Metrics scrape credential. Bound like every other secret in this class: from configuration, with
    // no committed default and no literal anywhere in the repository.

    /**
     * Property naming the principal a metrics scraper presents.
     *
     * <p>Bound with an empty default deliberately. An absent user name does not abort startup, because
     * metrics collection is not a precondition for serving traffic the way a signing key is; instead the
     * scrape chain <strong>fails closed</strong> - see {@link #scrapePrincipal}.
     */
    private static final String KEY_SCRAPE_USERNAME = "carddemo.observability.metrics.scrape.username";

    /**
     * Property naming the credential a metrics scraper presents.
     *
     * <p>Bound with an empty default for the same reason as {@link #KEY_SCRAPE_USERNAME}, and never
     * retained in plaintext: the raw value is BCrypt-encoded at construction and the encoded form is what
     * the in-memory principal holds, so no heap dump and no log line can echo it.
     */
    private static final String KEY_SCRAPE_PASSWORD = "carddemo.observability.metrics.scrape.password";

    /**
     * The single authority a metrics scraper carries.
     *
     * <p>Deliberately <em>not</em> one of {@code JwtTokenProvider}'s two business authorities. A scraper
     * is not an administrator and not a user; giving it a private authority means that even if the scrape
     * chain were ever widened by mistake, the credential still authorises nothing a business rule accepts,
     * because every business rule names an administrator or user authority explicitly.
     */
    private static final String SCRAPE_AUTHORITY = "SCRAPE";

    // The shared error envelope. Every refusal this class serialises carries the same members, in the
    // same order, as the body each @ExceptionHandler in com.cardemo.controller produces, so that one
    // client-side error handler covers the whole surface rather than one shape per rejection layer.

    /**
     * The logger this class refuses through.
     *
     * <p>Every refusal serialised below is logged once, at {@code WARN}, naming the condition and never the
     * credential, the token, the header set or any body byte. The logger is the only static field on this
     * class that is not a plain constant; it is immutable and thread-safe, so the no-global-mutable-state
     * property of Rule 1 Clause B is unaffected.
     */
    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * The problem type every serialised body carries, {@value}.
     *
     * <p>Matches what {@code org.springframework.http.ProblemDetail#forStatus(HttpStatus)} produces for a
     * controller body, so a client that switches on the {@code type} member cannot tell a rejection written
     * here from one written by a controller. No application-specific type URI is minted: none is published,
     * and a URI that does not resolve is worse than the RFC 9457 default.
     */
    private static final String PROBLEM_TYPE = "about:blank";

    /** The title every authentication refusal carries, {@value}. */
    private static final String AUTHENTICATION_PROBLEM_TITLE = "Authentication required";

    /**
     * The fixed detail every authentication refusal carries, {@value}.
     *
     * <p>It names the remedy and nothing about the request. It does not say whether a token was absent,
     * malformed, expired, signed by the wrong key or issued by the wrong issuer, because each of those is a
     * probe an unauthenticated caller can run at will; the distinction survives in the structured log and in
     * the {@code WWW-Authenticate} parameters the framework's own entry point writes.
     */
    private static final String AUTHENTICATION_PROBLEM_DETAIL =
            "The request did not carry a usable bearer token. Obtain one from the sign-on operation and "
                    + "present it in the Authorization header.";

    /** The stable machine-readable code every authentication refusal carries, {@value}. */
    private static final String ERROR_CODE_AUTHENTICATION_REQUIRED = "CARDDEMO-AUTHENTICATION-REQUIRED";

    /**
     * The detail the metrics scrape refusal carries, {@value}.
     *
     * <p>It is deliberately <em>not</em> {@link #AUTHENTICATION_PROBLEM_DETAIL}. That detail directs a caller
     * to the sign-on operation and to a bearer token, and doing so here would be actively misleading: this
     * chain accepts neither. A bearer token - even a valid administrator's - is refused, because the two
     * credential stores are isolated on purpose.
     *
     * <p>It names what the endpoint expects and nothing about why this particular attempt failed. In
     * particular it does not disclose whether a scrape principal is configured at all, which is deployment
     * state an anonymous caller has no claim on; that distinction is written to the log instead, where the
     * operator who needs it is looking.
     */
    private static final String SCRAPE_AUTHENTICATION_PROBLEM_DETAIL =
            "This endpoint requires HTTP Basic credentials carrying the metrics scrape authority. A bearer "
                    + "token is not accepted here.";

    /**
     * The realm the metrics scrape challenge names, {@value}.
     *
     * <p>The framework's default realm is the literal {@code Realm}, which tells an operator debugging a
     * failing scrape nothing about which credential is being asked for. Naming it after the endpoint's
     * purpose costs nothing and discloses nothing - the realm is a label, not a secret.
     */
    private static final String SCRAPE_REALM = "carddemo-metrics-scrape";

    /**
     * The detail a metrics scrape refusal after authentication carries, {@value}.
     *
     * <p>Again not the shared {@link #AUTHORIZATION_PROBLEM_DETAIL}, which speaks of a presented <em>token</em>
     * - a word with no meaning on a chain that authenticates HTTP Basic credentials. It covers both routes to
     * this handler, the method restriction and the authority requirement, and like its sibling it names
     * neither the authority nor the accepted method set.
     */
    private static final String SCRAPE_AUTHORIZATION_PROBLEM_DETAIL =
            "The presented credentials do not carry the authority this endpoint requires, or the request "
                    + "method is not one it serves.";

    /** The title every authorisation refusal carries, {@value}. */
    private static final String AUTHORIZATION_PROBLEM_TITLE = "Authorization denied";

    /**
     * The fixed detail every authorisation refusal carries, {@value}.
     *
     * <p>It states the condition without naming the authority that was required, because naming it would
     * describe the authorisation model to a principal that has just been refused by it.
     */
    private static final String AUTHORIZATION_PROBLEM_DETAIL =
            "The presented token is valid but does not carry the authority this operation requires.";

    /** The stable machine-readable code every authorisation refusal carries, {@value}. */
    private static final String ERROR_CODE_AUTHORIZATION_DENIED = "CARDDEMO-AUTHORIZATION-DENIED";

    /** The title every oversized-body refusal carries, {@value}. */
    private static final String PAYLOAD_PROBLEM_TITLE = "Request body too large";

    /**
     * The fixed detail every oversized-body refusal carries, {@value}.
     *
     * <p>The bound itself is deliberately not published. A caller cannot act on the number - the largest
     * legitimate body is four times smaller - and stating it tells an attacker exactly how much of the
     * allowance a single request may consume.
     */
    private static final String PAYLOAD_PROBLEM_DETAIL =
            "The request body exceeds the number of bytes this application will read from one request.";

    /** The stable machine-readable code every oversized-body refusal carries, {@value}. */
    private static final String ERROR_CODE_PAYLOAD_TOO_LARGE = "CARDDEMO-REQUEST-BODY-TOO-LARGE";

    /** The title every unusable-media-type refusal carries, {@value}. */
    private static final String MEDIA_TYPE_PROBLEM_TITLE = "Unsupported media type";

    /**
     * The fixed detail every unusable-media-type refusal carries, {@value}.
     *
     * <p>It names the one media type the seventeen operations accept and nothing about what arrived. The
     * offending header is written to the {@code WARN} log instead, where it is subject to the masking
     * configuration and reachable from the correlation identifier, so no attacker-chosen text is echoed
     * back on the response.
     */
    private static final String MEDIA_TYPE_PROBLEM_DETAIL =
            "This operation reads only application/json request bodies.";

    /** The stable machine-readable code every unusable-media-type refusal carries, {@value}. */
    private static final String ERROR_CODE_UNSUPPORTED_MEDIA_TYPE = "CARDDEMO-UNSUPPORTED-MEDIA-TYPE";

    /** The title every unreadable-body refusal carries, {@value}. */
    private static final String MALFORMED_BODY_PROBLEM_TITLE = "Request body could not be read";

    /**
     * The fixed detail every unreadable-body refusal carries, {@value}.
     *
     * <p>Deliberately identical in shape to the controllers' own unreadable-body sentence: a caller that
     * framed its body wrongly gets the same class of answer whether the failure surfaced while the transfer
     * encoding was being decoded or while the JSON was being parsed.
     */
    private static final String MALFORMED_BODY_PROBLEM_DETAIL =
            "The request body could not be read. Send a complete, correctly framed body and retry.";

    /** The stable machine-readable code every unreadable-body refusal carries, {@value}. */
    private static final String ERROR_CODE_MALFORMED_BODY = "CARDDEMO-MALFORMED-REQUEST-BODY";

    /**
     * The value published for the correlation identifier when the request carries none, {@value}.
     *
     * <p>The same literal the controllers publish, so the member is always present and a client never has to
     * branch on its absence. It can only be reached when a refusal is written outside
     * {@code com.cardemo.observability.CorrelationIdFilter}, which registers far ahead of this chain.
     */
    private static final String CORRELATION_ID_UNAVAILABLE = "unavailable";

    /**
     * The request methods this application never reads a body from.
     *
     * <p>Used by the body bound to decide whether an undeclared length is worth reading ahead of the chain.
     * Compared case-sensitively, because HTTP method names are case-sensitive: a lower-case {@code get} is
     * not the {@code GET} method and is treated as a method that may carry a body, which is the safe
     * direction.
     */
    private static final Set<String> BODYLESS_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    // Bound state. Three immutable values, all supplied by constructor injection. There is no field
    // @Autowired, no setter, no static mutable field and no service lookup anywhere in this class.

    /**
     * The verification key, derived once at construction.
     *
     * <p>The raw signing key <strong>is deliberately not retained as a string</strong>: it is validated,
     * converted to key material, copied into an immutable specification and then erased from the working
     * array. Holding the character data for the process lifetime would put the secret in every heap dump
     * for no benefit, since only the key material is ever used.
     */
    private final SecretKey verificationKey;

    /** The issuer claim value every presented token must carry, already known to be non-blank. */
    private final String issuer;

    /** The BCrypt cost, already known to equal {@value #REQUIRED_BCRYPT_STRENGTH}. */
    private final int bcryptStrength;

    /**
     * The metrics scrape principal, or {@code null} when no credential is configured.
     *
     * <p><strong>Absence means fail closed, not fail open.</strong> When either the user name or the
     * credential is blank this field is {@code null}, the scrape chain's authentication manager holds zero
     * principals, and every scrape attempt - credentialled or not - is answered {@code 401}. That is the
     * deliberate direction: a deployment that forgets to configure the scraper loses its metrics, which is
     * visible in the monitoring stack within one scrape interval, instead of publishing business series to
     * anyone who can reach the port, which is invisible until someone reads them. Rule 1 Clause D,
     * principle of least privilege.
     *
     * <p>The stored credential is a BCrypt hash produced at construction from the configured value. The
     * raw value is never retained in a field.
     */
    private final UserDetails scrapePrincipal;

    /**
     * Binds and validates the three security properties, failing startup rather than deferring a
     * misconfiguration to first use.
     *
     * <p>Every check happens here, at context refresh, and not on a request path. That placement is the
     * point: a weak or absent signing key discovered at the first sign-on attempt is an intermittent
     * production incident, whereas the same defect discovered at startup is a deployment that never goes
     * live. Rule 1 Clause B's requirement to validate inputs and boundary conditions is therefore
     * discharged before the application can accept traffic.
     *
     * @param signingKey     the symmetric HMAC key, bound from {@value #KEY_SIGNING_KEY} with no default;
     *                       must be non-blank and at least {@value #MINIMUM_SIGNING_KEY_BYTES} bytes as
     *                       UTF-8
     * @param issuer         the issuer claim, bound from {@value #KEY_ISSUER}; must be non-blank
     * @param bcryptStrength the BCrypt cost, bound from {@value #KEY_BCRYPT_STRENGTH}; must equal
     *                       {@value #REQUIRED_BCRYPT_STRENGTH}
     * @param scrapeUsername the metrics scrape principal, bound from {@value #KEY_SCRAPE_USERNAME} with an
     *                       empty default; blank is permitted and means the scrape chain fails closed
     * @param scrapePassword the metrics scrape credential, bound from {@value #KEY_SCRAPE_PASSWORD} with an
     *                       empty default; blank is permitted and means the scrape chain fails closed. The
     *                       value is BCrypt-encoded here and never retained in plaintext
     * @throws IllegalStateException if the signing key is absent, empty, whitespace only or too short; if
     *                               the issuer is absent or blank; or if the BCrypt cost is anything other
     *                               than the required value - in every case aborting startup, and in no case
     *                               reporting any part of the signing key
     */
    public SecurityConfig(
            @Value("${" + KEY_SIGNING_KEY + "}") final String signingKey,
            @Value("${" + KEY_ISSUER + "}") final String issuer,
            @Value("${" + KEY_BCRYPT_STRENGTH + "}") final int bcryptStrength,
            @Value("${" + KEY_SCRAPE_USERNAME + ":}") final String scrapeUsername,
            @Value("${" + KEY_SCRAPE_PASSWORD + ":}") final String scrapePassword) {
        this.verificationKey = verificationKeyFrom(signingKey);
        this.issuer = validatedIssuer(issuer);
        this.bcryptStrength = validatedBcryptStrength(bcryptStrength);
        this.scrapePrincipal = scrapePrincipalFrom(scrapeUsername, scrapePassword, this.bcryptStrength);
    }

    /**
     * Builds the metrics scrape principal, or returns {@code null} when no credential is configured.
     *
     * <p>Both halves must be present. A user name without a credential, or a credential without a user
     * name, is a half-applied configuration and is treated exactly as absence is - closed - rather than
     * being completed with a guess.
     *
     * <p>The credential is encoded here rather than stored raw, using the same cost the business password
     * encoder uses, so that the two credential stores are indistinguishable in strength. Encoding once at
     * construction rather than per request also keeps the fifteen-second scrape interval off the BCrypt
     * cost curve for anything but the single verification the provider performs.
     *
     * @param username the configured user name, possibly blank; must not be {@code null}
     * @param password the configured credential, possibly blank; must not be {@code null}
     * @param strength the BCrypt cost, already validated
     * @return the principal, or {@code null} when either half is blank
     */
    private static UserDetails scrapePrincipalFrom(
            final String username, final String password, final int strength) {
        if (username.isBlank() || password.isBlank()) {
            return null;
        }
        return User.withUsername(username.strip())
                .password(new BCryptPasswordEncoder(strength).encode(password))
                .authorities(new SimpleGrantedAuthority(SCRAPE_AUTHORITY))
                .build();
    }

    /**
     * Declares the metrics scrape chain: HTTP Basic over exactly one path, isolated from business identity.
     *
     * <p><strong>Why a second chain rather than one more rule on the business chain.</strong> The business
     * chain authenticates bearer tokens. Adding HTTP Basic to it would make Basic credentials acceptable
     * everywhere that chain matches, so a scrape credential would become a second way to reach business
     * endpoints - the opposite of least privilege. A separate chain with its own
     * {@link org.springframework.security.authentication.AuthenticationManager} confines the scrape
     * credential to the scrape path: the business chain has no knowledge of it, and this chain has no
     * knowledge of the token decoder or of {@code CardDemoUserDetailsService}.
     *
     * <p><strong>Why the principal store is built inline and not published as a bean.</strong>
     * {@code CardDemoUserDetailsService} is already the application's one
     * {@code UserDetailsService} bean. Publishing a second would either collide by type - the base profile
     * sets {@code spring.main.allow-bean-definition-overriding} to {@code false}, making that a startup
     * failure - or, worse, be picked up by Boot's default authentication manager and let the scrape
     * credential authenticate against business rules.
     *
     * <p><strong>Ordered ahead of the business chain.</strong> The business chain declares no security
     * matcher and therefore matches every request; only declaration order decides which chain serves the
     * scrape path. This one is {@code @Order(1)}, the business chain {@code @Order(2)}.
     *
     * <p>Session policy, forgery state, logout and request cache mirror the business chain for the same
     * reasons documented there: a scrape is a stateless {@code GET} and must never mint a session.
     *
     * @param http the builder Spring Security supplies for this chain
     * @return the chain governing {@value #PATH_PROMETHEUS}, never {@code null}
     * @throws Exception if the builder cannot assemble the chain, which is a configuration defect and
     *                   therefore correctly fatal at startup
     */
    @Bean
    @Order(1)
    public SecurityFilterChain metricsScrapeFilterChain(final HttpSecurity http) throws Exception {
        final PathPatternRequestMatcher.Builder path = PathPatternRequestMatcher.withDefaults();

        // Zero principals when nothing is configured. InMemoryUserDetailsManager then reports every user
        // name as unknown, the provider translates that to a bad-credentials failure, and the entry point
        // answers 401 - which is the fail-closed outcome documented on the scrapePrincipal field.
        final InMemoryUserDetailsManager principals = scrapePrincipal == null
                ? new InMemoryUserDetailsManager()
                : new InMemoryUserDetailsManager(scrapePrincipal);

        final DaoAuthenticationProvider provider = new DaoAuthenticationProvider(principals);
        provider.setPasswordEncoder(new BCryptPasswordEncoder(bcryptStrength));

        return http
                .securityMatcher(path.matcher(PATH_PROMETHEUS))
                .csrf(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .authenticationManager(new ProviderManager(provider))
                // The challenge carries the same problem envelope every other refusal on this service
                // carries. Before this, it answered Spring's default body - no errorCode, no
                // correlationId - so a failing scrape produced a 401 that named no cause and could not be
                // joined to a log record. That is what made this endpoint look unreachable rather than
                // unconfigured. See ScrapeAuthenticationEntryPoint.
                .httpBasic(basic -> basic.authenticationEntryPoint(
                        new ScrapeAuthenticationEntryPoint(scrapePrincipal != null)))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(
                                new ScrapeAuthenticationEntryPoint(scrapePrincipal != null))
                        .accessDeniedHandler(new ScrapeAccessDeniedHandler()))
                // GET only. A scrape never writes, and leaving other methods to the deny-all below keeps
                // the reachable surface equal to the documented surface.
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(path.matcher(HttpMethod.GET, PATH_PROMETHEUS))
                                .hasAuthority(SCRAPE_AUTHORITY)
                        .anyRequest().denyAll())
                .build();
    }

    /**
     * Declares the one security filter chain: stateless, deny-by-default, and authorising exactly the
     * seventeen sourced CICS transactions.
     *
     * <h4>What each clause is for</h4>
     *
     * <dl>
     *   <dt>Cross-site request forgery state disabled</dt>
     *   <dd>A forgery token defends a credential the browser attaches automatically - a cookie. This API
     *       has no cookie and no session, so the caller must present a bearer token that a foreign origin
     *       cannot read and therefore cannot replay. Keeping the token machinery would add a synchroniser
     *       every client must fetch, defending nothing. Rule 1 Clause A's "avoid unsafe defaults" is served
     *       by removing the session, not by layering a session-shaped defence on top of one.</dd>
     *
     *   <dt>Logout disabled</dt>
     *   <dd>Spring Security applies logout to a user-declared chain by default, which would publish a
     *       {@code POST /logout} endpoint ahead of the authorisation filter. Nothing can be logged out of a
     *       stateless chain - the token remains valid until it expires - so that endpoint would be an
     *       unauthenticated route answering success while doing nothing. Removing it keeps the reachable
     *       surface equal to the documented surface.</dd>
     *
     *   <dt>Stateless session policy</dt>
     *   <dd>Transformation rule 7. The pseudo-conversational return at
     *       {@code app/cbl/COSGN00C.cbl:L98-L102} is the construct being replaced, and replacing it with a
     *       server-side session would reproduce the coupling rather than remove it.</dd>
     *
     *   <dt>Null request cache</dt>
     *   <dd>Not decoration, and not dead configuration. The exception-translation filter asks the request
     *       cache to save the request <em>before</em> it invokes the entry point, and the default cache
     *       saves into an HTTP session - which creates one. Because forgery state is disabled, the default
     *       cache also stops restricting itself to {@code GET}, so an unauthenticated call would mint a
     *       session on its way to its own {@code 401}. A null cache removes the only remaining path by
     *       which this chain could create session state, and it is set here so the entry point below reuses
     *       the same instance.</dd>
     *
     *   <dt>Bearer entry point and access-denied handler</dt>
     *   <dd>An unauthenticated call receives {@code 401} with a {@code WWW-Authenticate: Bearer} challenge;
     *       an authenticated call lacking the authority receives {@code 403}. Both are framework classes
     *       from the resource-server module, so the distinction the legacy screens drew between "not
     *       signed on" and "not permitted" survives with no new file.</dd>
     *
     *   <dt>Error dispatch permitted</dt>
     *   <dd>A container-internal error dispatch is not a client request. Without this clause the final
     *       deny-all rule would refuse the very forward that renders the error body, converting a clean
     *       {@code 400} into an opaque {@code 403}.</dd>
     *
     *   <dt>Eager response-header writing</dt>
     *   <dd>This is the published workaround for <strong>CVE-2026-22732</strong> (CVSS 9.1), and it is
     *       configuration rather than commentary. The advisory's condition is that when an application sets
     *       HTTP response headers itself and Spring Security writes its own headers <em>lazily</em> - the
     *       default - the security headers may never be written at all. Versions 6.5.0 through 6.5.8 are
     *       affected and 6.5.9 carries the fix; eager header writing is the documented mitigation for anyone
     *       who cannot move version.
     *       <p><strong>This application is not on an affected version.</strong> Reading it as one that
     *       "cannot move version" - on the grounds that {@code spring-boot-starter-parent} 3.5.11 resolves
     *       Spring Security 6.5.8 and that AAP section
     *       0.8.4 forbids advancing a pinned coordinate unilaterally - is measurably wrong:
     *       {@code pom.xml} declares {@code <spring-security.version>6.5.11</spring-security.version>}
     *       as a deliberate forward override of the 6.5.8 the parent manages - three releases past the fix,
     *       and recorded as a remediation in {@code owasp-suppressions.xml} ("CVE-2026-22732 9.1
     *       spring-security 6.5.8 -&gt; 6.5.11"). Section 0.8.4 governs the <em>pinned</em> coordinate, which
     *       is the parent;
     *       overriding a version the parent merely manages, in order to close a published advisory, is the
     *       remedy that section asks for rather than a violation of it.
     *       <p><strong>The configuration is kept, and its justification changes from mitigation to
     *       defence in depth.</strong> The {@code HeaderWriterFilter} the headers configurer builds is
     *       post-processed with {@code setShouldWriteHeadersEagerly(true)}, which writes the headers on the way
     *       in rather than on the way out, before any handler can commit the response. On 6.5.11 that is no
     *       longer load-bearing for this advisory; it is retained because it makes the property the advisory
     *       was about - security headers are on the response before any handler can commit it - hold
     *       independently of which version is resolved, so a future downgrade or a transitive resolution back
     *       onto an affected version cannot silently reopen the exposure. It is also the behaviour this
     *       application wants on its own merits, since {@link #writeProblemDetail} commits refusals directly.
     *       <p>The post-processor is an anonymous class and not a lambda on purpose - the composite
     *       post-processor selects by resolved generic type, and a lambda erases it, so a lambda would
     *       silently never run. It is verified rather than asserted:
     *       {@code src/test/java/com/cardemo/unit/config/SecurityConfigTest.java} drives a request through the
     *       assembled chain and fails if any of the header writers has not run by the time the chain is
     *       entered.
     *       <p><em>Residual risk, disclosed:</em> none for this advisory. The corresponding
     *       {@code owasp-suppressions.xml} entry has been removed, which is what makes the version pin
     *       load-bearing: anyone who reverts {@code spring-security.version} gets a scan finding rather than a
     *       suppression that absorbs it. The remaining obligation is a monitoring one - Spring Boot 3.5 reaches
     *       end of open-source support in mid-2026, so the forward override has to be re-derived against a
     *       later parent when that upgrade happens, and that is an AAP amendment owned by the plan owner.</dd>
     * </dl>
     *
     * <h4>Why the rules are in this order</h4>
     *
     * <p>Matchers are evaluated in declaration order and the first match wins, so the order is part of the
     * behaviour rather than a formatting choice. Non-overlapping paths come first, then the two
     * administrator rules, then the eleven ordinary business rules, then the deny-all. The one genuine
     * overlap - {@value #PATH_ADMIN} against nothing else, and {@value #PATH_MENU_ADMIN} against
     * {@value #PATH_MENU_MAIN} - is resolved by placing the administrator rules ahead of the business
     * rules. No rule depends on a set's iteration order, satisfying Rule 1 Clause A's determinism
     * requirement.
     *
     * <h4>Where the filter goes</h4>
     *
     * <p>{@code com.cardemo.security.JwtAuthenticationFilter} is inserted immediately after
     * {@code SecurityContextHolderFilter}. Earlier is not merely suboptimal but wrong: the context-holder
     * filter installs a fresh, empty security context at the start of every request, so an authentication
     * established before it runs is discarded and every authorised call fails with {@code 401}. The filter
     * is injected, never instantiated here - it is a {@code @Component} that owns its own construction, and
     * a second instance would double every token parse.
     *
     * <p>{@code com.cardemo.observability.CorrelationIdFilter} is deliberately <strong>not</strong> added.
     * It registers at {@code Ordered.HIGHEST_PRECEDENCE + 2} - immediately after Boot's server observation
     * filter and far ahead of the entire security chain at {@code -100} - so unauthenticated and rejected
     * requests are already correlated. Adding it here would run it twice and produce two correlation
     * identifiers for one request.
     *
     * @param http                     the builder Spring Security supplies for this chain
     * @param jwtAuthenticationFilter  the token-validation filter, injected rather than constructed
     * @return the single chain governing every request this application serves
     * @throws Exception if the builder cannot assemble the chain, which is a configuration defect and
     *                   therefore correctly fatal at startup; the cause is propagated untouched rather than
     *                   wrapped, because Spring's own report names the offending configurer
     */
    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(
            final HttpSecurity http,
            final JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {

        // Every matcher below is built explicitly rather than from a bare pattern string. See the
        // "How requests are matched" section of this class's documentation for why that is deliberate.
        final PathPatternRequestMatcher.Builder path = PathPatternRequestMatcher.withDefaults();

        return http
                .csrf(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)

                // CVE-2026-22732 defence in depth, no longer a mitigation. Spring Security 6.5.0 through
                // 6.5.8 write response headers lazily, and the advisory's condition is that an application
                // which sets headers of its own can then leave the security headers unwritten. This build
                // resolves 6.5.11 - pom.xml overrides the 6.5.8 the parent manages - so it is not on an
                // affected version and eager writing is not what closes the advisory; the version is. It is
                // kept because it makes the property hold whichever version resolves, so a downgrade or a
                // transitive resolution back onto an affected version cannot silently reopen the exposure, and
                // because writeProblemDetail commits refusals directly. An anonymous class rather than a
                // lambda: the composite post-processor selects by resolved generic type, which a lambda
                // erases, so a lambda would compile, register and never run. See the class documentation.
                .headers(headers -> headers.addObjectPostProcessor(
                        new ObjectPostProcessor<HeaderWriterFilter>() {
                            @Override
                            public <O extends HeaderWriterFilter> O postProcess(final O headerWriterFilter) {
                                headerWriterFilter.setShouldWriteHeadersEagerly(true);
                                return headerWriterFilter;
                            }
                        }))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                // Both refusals are serialised through this class's own writers, which delegate to the
                // framework's bearer components for the status and the challenge header and then add the
                // problem body those components omit. See writeProblemDetail for why the empty answers they
                // produce on their own were a High-severity contract defect.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new ProblemDetailAuthenticationEntryPoint())
                        .accessDeniedHandler(new ProblemDetailAccessDeniedHandler()))

                // FINDING LOW-004, severity Low, RESOLVED. The two filters below are placed AFTER the header
                // writer so a refusal still carries the security headers, and BEFORE AUTHORIZATION AND MVC
                // BINDING so they apply to every request including an anonymous one. The measured chain is
                // DisableEncodeUrl, WebAsyncManagerIntegration, SecurityContextHolder, JwtAuthentication,
                // HeaderWriter, RequestBodyLimit, RequestMediaType, RequestCacheAware,
                // SecurityContextHolderAwareRequest, AnonymousAuthentication, SessionManagement,
                // ExceptionTranslation, Authorization - so these two run AFTER the bearer filter, not before
                // it, which is what these comments used to claim.
                //
                // The purpose is unaffected and the position is still the whole point. The bearer filter does
                // not REFUSE anything: it populates the security context when a token is present and passes
                // an anonymous request straight through. Refusal for want of authorization happens last, at
                // AuthorizationFilter, and MVC argument resolution happens after the chain entirely. Both
                // bounds therefore still screen an unauthenticated caller - the sign-on route being the one
                // that most needs it - and still screen it before any body is deserialized.
                .addFilterAfter(new RequestBodyLimitFilter(), HeaderWriterFilter.class)
                // The media-type screen, placed AFTER the body bound so the two refusals are ordered
                // size-then-shape. See RequestMediaTypeFilter for why screening here is what stops an
                // unusable Content-Type reaching argument resolution.
                .addFilterAfter(new RequestMediaTypeFilter(), RequestBodyLimitFilter.class)
                .addFilterAfter(jwtAuthenticationFilter, SecurityContextHolderFilter.class)
                .authorizeHttpRequests(authorize -> authorize

                        // Container-internal error rendering, not a client request.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()

                        // Operational surface. Anonymous by necessity, read-only by method, and limited to
                        // the liveness and readiness signals plus build identity. The container health
                        // check arrives without a credential - the image's HEALTHCHECK requests
                        // /actuator/health/readiness - so a rule requiring one would report a permanently
                        // unhealthy container.
                        //
                        // PATH_PROMETHEUS IS DELIBERATELY ABSENT FROM THIS LIST. The scrape body renders
                        // business series, so it is governed by metricsScrapeFilterChain, which requires
                        // HTTP Basic credentials carrying the SCRAPE authority. It is not merely omitted
                        // here: that chain declares a security matcher for the path and is ordered ahead of
                        // this one, so this chain never sees a scrape request at all.
                        .requestMatchers(
                                path.matcher(HttpMethod.GET, PATH_HEALTH),
                                path.matcher(HttpMethod.GET, PATH_HEALTH_LIVENESS),
                                path.matcher(HttpMethod.GET, PATH_HEALTH_READINESS),
                                path.matcher(HttpMethod.GET, PATH_INFO)).permitAll()

                        // CC00 - app/csd/CARDDEMO.CSD:L378 - app/cbl/COSGN00C.cbl. The only unauthenticated
                        // business operation, because it is the operation that establishes identity. Bounded
                        // to POST so no sign-on data can ever be placed in a query string, and bounded to
                        // the EXACT route rather than to the /api/auth namespace: one transaction is defined
                        // and one endpoint is opened. Every other path under that namespace, now or later,
                        // falls through to anyRequest().denyAll() below.
                        .requestMatchers(path.matcher(HttpMethod.POST, PATH_SIGN_ON)).permitAll()

                        // CU00, CU01, CU02, CU03 - app/csd/CARDDEMO.CSD:L449, :L459, :L469, :L479.
                        // Administrator only, mirroring the user-type gate at
                        // app/cbl/COSGN00C.cbl:L230 that routed only type 'A' to COADM01C.
                        .requestMatchers(path.matcher(PATH_ADMIN)).hasAuthority(JwtTokenProvider.ADMIN_AUTHORITY)

                        // CA00 - app/csd/CARDDEMO.CSD:L327 - app/cbl/COADM01C.cbl. The administrative menu
                        // is itself administrative; declared before the main menu so the more specific path
                        // is matched first.
                        .requestMatchers(path.matcher(HttpMethod.GET, PATH_MENU_ADMIN))
                                .hasAuthority(JwtTokenProvider.ADMIN_AUTHORITY)

                        // The eleven remaining sourced transactions. Both user types reach them, exactly as
                        // both types reached the main menu at app/cbl/COSGN00C.cbl:L236; authority is
                        // required, so an absent or invalid token yields 401 rather than access.

                        // CM00 - :L399 - app/cbl/COMEN01C.cbl
                        .requestMatchers(path.matcher(HttpMethod.GET, PATH_MENU_MAIN)).hasAnyAuthority(
                                JwtTokenProvider.ADMIN_AUTHORITY, JwtTokenProvider.USER_AUTHORITY)

                        // CAVW - :L317 - app/cbl/COACTVWC.cbl
                        .requestMatchers(path.matcher(HttpMethod.GET, PATH_ACCOUNT_BY_ID)).hasAnyAuthority(
                                JwtTokenProvider.ADMIN_AUTHORITY, JwtTokenProvider.USER_AUTHORITY)

                        // CAUP - :L306 - app/cbl/COACTUPC.cbl
                        .requestMatchers(path.matcher(HttpMethod.PUT, PATH_ACCOUNTS)).hasAnyAuthority(
                                JwtTokenProvider.ADMIN_AUTHORITY, JwtTokenProvider.USER_AUTHORITY)

                        // CCLI - :L357 - app/cbl/COCRDLIC.cbl
                        .requestMatchers(path.matcher(HttpMethod.GET, PATH_CARDS)).hasAnyAuthority(
                                JwtTokenProvider.ADMIN_AUTHORITY, JwtTokenProvider.USER_AUTHORITY)

                        // CCDL - :L347 - app/cbl/COCRDSLC.cbl
                        .requestMatchers(path.matcher(HttpMethod.GET, PATH_CARD_DETAIL)).hasAnyAuthority(
                                JwtTokenProvider.ADMIN_AUTHORITY, JwtTokenProvider.USER_AUTHORITY)

                        // CCUP - :L367 - app/cbl/COCRDUPC.cbl
                        .requestMatchers(path.matcher(HttpMethod.PUT, PATH_CARDS)).hasAnyAuthority(
                                JwtTokenProvider.ADMIN_AUTHORITY, JwtTokenProvider.USER_AUTHORITY)

                        // CT00 - :L419 - app/cbl/COTRN00C.cbl
                        .requestMatchers(path.matcher(HttpMethod.GET, PATH_TRANSACTIONS)).hasAnyAuthority(
                                JwtTokenProvider.ADMIN_AUTHORITY, JwtTokenProvider.USER_AUTHORITY)

                        // CT01 - :L429 - app/cbl/COTRN01C.cbl
                        .requestMatchers(path.matcher(HttpMethod.GET, PATH_TRANSACTION_DETAIL)).hasAnyAuthority(
                                JwtTokenProvider.ADMIN_AUTHORITY, JwtTokenProvider.USER_AUTHORITY)

                        // CT02 - :L439 - app/cbl/COTRN02C.cbl
                        .requestMatchers(path.matcher(HttpMethod.POST, PATH_TRANSACTIONS)).hasAnyAuthority(
                                JwtTokenProvider.ADMIN_AUTHORITY, JwtTokenProvider.USER_AUTHORITY)

                        // CB00 - :L337 - app/cbl/COBIL00C.cbl
                        .requestMatchers(path.matcher(HttpMethod.POST, PATH_BILL_PAYMENTS)).hasAnyAuthority(
                                JwtTokenProvider.ADMIN_AUTHORITY, JwtTokenProvider.USER_AUTHORITY)

                        // CR00 - :L409 - app/cbl/CORPT00C.cbl
                        .requestMatchers(path.matcher(HttpMethod.POST, PATH_REPORTS)).hasAnyAuthority(
                                JwtTokenProvider.ADMIN_AUTHORITY, JwtTokenProvider.USER_AUTHORITY)

                        // Everything else. Deny, never permit: an endpoint added without a rule must be
                        // unreachable rather than unprotected. There is deliberately no rule here for
                        // transaction CDV1 at app/csd/CARDDEMO.CSD:L388 or for the four batch-only
                        // datasets; see the class documentation.
                        .anyRequest().denyAll())
                .build();
    }

    /**
     * Declares the one password encoder: BCrypt at cost {@value #REQUIRED_BCRYPT_STRENGTH}.
     *
     * <p>This bean is what closes transformation rule 15. The legacy comparison at
     * {@code app/cbl/COSGN00C.cbl:L223} was {@code IF SEC-USR-PWD = WS-USER-PWD} - a plaintext equality
     * test against the eight-byte field at {@code app/cpy/CSUSR01Y.cpy:L21}. The ten seeded users all
     * carried one shared plaintext value, which is why nothing in the Java tree stores or transports a
     * plaintext password: {@code V3__seed_data.sql} writes only hashes, and this encoder is the only thing
     * that can compare against them.
     *
     * <p>The cost is <em>validated</em> rather than merely read, in the constructor, because it is a
     * compatibility constant and not a tuning knob. A newly created user hashed at a different cost would
     * still authenticate - BCrypt reads the cost from the stored hash - so the divergence would be
     * invisible until an audit compared the seeded rows against the created ones. Failing startup makes an
     * intentional change a two-line change made deliberately, in this class and in the seed migration
     * together, rather than a silent drift.
     *
     * <p>Consumers: {@code com.cardemo.security.CardDemoUserDetailsService} verifies with it, and the two
     * administrative services that create and update users encode with it. This is the only declaration in
     * the application; if startup ever reports two candidates, the duplicate is removed from the
     * {@code com.cardemo.security} package, because this class is the designated owner.
     *
     * @return a BCrypt encoder at the one accepted cost, safe to share across threads
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(this.bcryptStrength);
    }

    /**
     * Declares the one JWT decoder: symmetric HS256, issuer-verified, built from the environment-supplied
     * signing key.
     *
     * <p>This class builds the decoder itself rather than letting the resource-server starter derive one,
     * because the deployment has no key set to fetch and no public key to read. Configuring either
     * property would name an endpoint that does not exist, which Rule 1 Clause B forbids as dead
     * configuration. The verification key is instead the same symmetric secret
     * {@code com.cardemo.security.JwtTokenProvider} signs with, so verification and issuance cannot drift.
     *
     * <p>Two validators apply. The default set checks the timestamp claims, so an expired token is rejected
     * on presentation - which is what bounds the role-change latency the class documentation records.
     * Issuer verification is added on top, so a token minted by any other issuer holding the same secret is
     * refused rather than trusted. Note what is deliberately absent: no token-type validator, because the
     * issuer emits no type header, and adding one would reject every token this application produces.
     *
     * <p>The decoder performs no database read and no network call. That is not an optimisation but the
     * faithful behaviour: the commented-out identity moves at {@code app/cbl/COMEN01C.cbl:L149-L150} prove
     * the legacy system re-derived identity nowhere after sign-on, and re-reading the security file on every
     * request would be a behaviour the source does not have.
     *
     * <p>This is the only declaration in the application. If startup reports two candidates, the duplicate
     * is removed from the {@code com.cardemo.security} package, because this class is the designated owner.
     *
     * @return the sole decoder, shared and thread-safe, verifying signature, timestamps and issuer
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        final NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(this.verificationKey)
                .macAlgorithm(MAC_ALGORITHM)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(this.issuer));
        return decoder;
    }

    // Validation helpers. All private and all static, so the constructor cannot leak a partially
    // constructed instance and no subclass can weaken a check. Each one names the property it is
    // complaining about and the remedy; none of them reports a value that could be sensitive.

    /**
     * Validates the signing key and converts it to an immutable verification key, erasing the working copy.
     *
     * <p>The five rejected cases are distinguished deliberately rather than collapsed into one, because they
     * have five different remedies: a null value means the property is not bound at all, an empty value means
     * the variable is exported but unset, a whitespace value means it was quoted wrongly, an unresolved
     * placeholder means the context resolves placeholders leniently (see
     * {@link #UNRESOLVED_PLACEHOLDER_PREFIX} for why that case is dangerous rather than merely wrong), and a
     * short value means it was generated too small. A single "invalid signing key" message would leave an
     * operator guessing between five different fixes.
     *
     * <p>The working byte array is zeroed in a {@code finally} block. That is safe precisely because the
     * secret-key specification copies the array it is given rather than retaining the reference, so the
     * returned key is unaffected; the erasure removes the one extra copy this method itself created. The
     * character data is never assigned to a field, so the only lasting copy of the key material is the one
     * the decoder genuinely needs.
     *
     * @param signingKey the raw property value, which may be {@code null} when the property is unbound
     * @return an immutable HMAC verification key
     * @throws IllegalStateException if the value is {@code null}, empty, whitespace only, or shorter than
     *                               {@value #MINIMUM_SIGNING_KEY_BYTES} bytes as UTF-8 - aborting startup,
     *                               and never naming any part of the value
     */
    private static SecretKey verificationKeyFrom(final String signingKey) {
        if (signingKey == null) {
            throw signingKeyRejected("is not bound to any value");
        }
        if (signingKey.isEmpty()) {
            throw signingKeyRejected("is bound to an empty value");
        }
        if (signingKey.isBlank()) {
            throw signingKeyRejected("is bound to a value consisting only of whitespace");
        }
        if (signingKey.contains(UNRESOLVED_PLACEHOLDER_PREFIX)) {
            throw signingKeyRejected("is bound to an unresolved property placeholder rather than to a key");
        }

        final byte[] keyMaterial = signingKey.getBytes(StandardCharsets.UTF_8);
        try {
            if (keyMaterial.length < MINIMUM_SIGNING_KEY_BYTES) {
                throw signingKeyRejected(String.format(
                        Locale.ROOT,
                        "is bound to a value of %d UTF-8 bytes, which is shorter than the %d bytes "
                                + "the HS256 message authentication code requires",
                        keyMaterial.length,
                        MINIMUM_SIGNING_KEY_BYTES));
            }
            return new SecretKeySpec(keyMaterial, MAC_KEY_ALGORITHM);
        } finally {
            Arrays.fill(keyMaterial, (byte) 0);
        }
    }

    /**
     * Builds the one signing-key failure message, so every rejection path words the remedy identically.
     *
     * <p>The message names the property key and the environment variable and stops there. It reports no
     * part of the value, no length beyond the one case where the length <em>is</em> the defect, and no
     * digest - a digest of a short secret is itself a lead. Rule 1 Clause D forbids secrets in logs, and a
     * startup failure is written to the log like anything else.
     *
     * @param defect the condition observed, phrased to complete the sentence "the property ... {defect}"
     * @return the exception to throw, never {@code null}
     */
    private static IllegalStateException signingKeyRejected(final String defect) {
        return new IllegalStateException(String.format(
                Locale.ROOT,
                "Property '%s' %s. Set environment variable %s to a value of at least %d bytes and restart. "
                        + "There is deliberately no default: a shipped signing key would let anyone holding "
                        + "the source mint a valid administrator token. The value itself is never logged.",
                KEY_SIGNING_KEY,
                defect,
                SIGNING_KEY_VARIABLE,
                MINIMUM_SIGNING_KEY_BYTES));
    }

    /**
     * Validates the issuer claim value.
     *
     * <p>A blank issuer would make issuer verification vacuous while appearing configured, which is the
     * worst of both outcomes. Because the property carries a documented default, reaching this failure means
     * the default was overridden with an empty value - so the message says to unset the variable rather
     * than to invent a value. The issuer is not a secret and is therefore safe to report.
     *
     * @param issuer the raw property value, which may be {@code null} when the property is unbound
     * @return the issuer, unchanged and known to be non-blank
     * @throws IllegalStateException if the issuer is absent or blank, aborting startup
     */
    private static String validatedIssuer(final String issuer) {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException(String.format(
                    Locale.ROOT,
                    "Property '%s' must name a non-blank issuer, because every presented token's issuer "
                            + "claim is verified against it. Unset the overriding environment variable to "
                            + "fall back to the documented default rather than supplying a blank value.",
                    KEY_ISSUER));
        }
        return issuer;
    }

    /**
     * Validates the BCrypt cost against the one value the seeded hashes were written at.
     *
     * <p>The message states both halves of the remedy, because changing the cost correctly is not a
     * one-file change: the constant here and the hashes in the seed migration have to move together, and
     * any user row created before the change keeps its old cost.
     *
     * @param strength the raw property value
     * @return the cost, known to equal {@value #REQUIRED_BCRYPT_STRENGTH}
     * @throws IllegalStateException if the cost differs from the required value, aborting startup
     */
    private static int validatedBcryptStrength(final int strength) {
        if (strength != REQUIRED_BCRYPT_STRENGTH) {
            throw new IllegalStateException(String.format(
                    Locale.ROOT,
                    "Property '%s' is %d but must be %d. That cost is fixed by transformation rule 15 and "
                            + "matches the hashes the seed migration wrote for the ten seeded users; it is "
                            + "not a tunable. To change it deliberately, change this class and re-hash every "
                            + "seeded row in the same commit.",
                    KEY_BCRYPT_STRENGTH,
                    strength,
                    REQUIRED_BCRYPT_STRENGTH));
        }
        return strength;
    }

    /**
     * Serialises one refusal as the same problem body a controller would have produced.
     *
     * <p><strong>Finding, severity High - remediated by this method.</strong> The framework's bearer entry
     * point and access-denied handler set a status and a challenge header and write <em>no body at all</em>,
     * and the body bound below previously did the same. A caller therefore met three different shapes on one
     * API: a populated problem document from any controller, and an empty document from the authentication,
     * authorisation and body-limit layers. A client cannot write one error handler against that, and the
     * empty answers carry no correlation identifier, so a rejected request could not be tied back to its own
     * log line. This method is the single place all three are now written, which is what makes them identical
     * by construction rather than by three implementations that happen to agree.
     *
     * <p><strong>The body is composed rather than serialised, deliberately.</strong> Every interpolated value
     * is either a compile-time constant declared in this class or the correlation identifier, and the
     * identifier is read through {@code CorrelationIdFilter#currentCorrelationId()}, which returns a value
     * only when it matches that filter's published grammar - at most sixty-four characters of
     * {@code [A-Za-z0-9_-]} - and {@code null} otherwise. There is consequently no character in the result
     * that JSON would need escaped, and no attacker-controlled text reaches the body: composing it here
     * needs neither an injected {@code ObjectMapper} nor an escaper, and it cannot fail on a
     * serialisation path where a thrown exception would leave the caller with an empty 500. The member order
     * matches Spring's own {@code ProblemDetail} rendering - type, title, status, detail, then the
     * application's own properties - so the two are byte-comparable.
     *
     * <p><strong>Nothing about the request is disclosed.</strong> No token, no header, no body byte, no
     * exception message, no exception class, no required authority and no dataset name reaches the body: the
     * title, the detail and the code are fixed per condition, which is exactly what the
     * {@code server.error.include-*} settings ask of every other error path.
     *
     * <p><strong>A committed response is reported, never overwritten.</strong> If something has already begun
     * writing, the status line and part of the body are already on the wire and there is nothing correct left
     * to do; the condition is logged at {@code WARN} so it is visible rather than silent. It cannot arise on
     * the three paths that call this method - each runs before any handler has written - and is guarded
     * because the alternative is an {@code IllegalStateException} thrown from an error path.
     *
     * @param response the response to write, never {@code null}
     * @param status the status to publish, which is always the status already set on the response by the
     * framework component that refused the request, so the body can never contradict the status line
     * @param title the fixed, condition-specific title
     * @param detail the fixed, condition-specific detail, which names no value from the request
     * @param errorCode the stable machine-readable code for the condition
     * @throws IOException if the body cannot be written to the response stream, which the servlet container
     * translates; it is propagated rather than swallowed so a broken connection is not reported as a
     * successful refusal
     */
    private static void writeProblemDetail(final HttpServletResponse response, final HttpStatus status,
            final String title, final String detail, final String errorCode) throws IOException {

        if (response.isCommitted()) {
            LOG.warn("Refused a request with {} but the response was already committed, so the {} problem "
                    + "envelope could not be written", status.value(), errorCode);
            return;
        }

        final String correlationId = CorrelationIdFilter.currentCorrelationId();
        final byte[] body = String.format(
                Locale.ROOT,
                "{\"type\":\"%s\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\"errorCode\":\"%s\","
                        + "\"correlationId\":\"%s\"}",
                PROBLEM_TYPE,
                title,
                status.value(),
                detail,
                errorCode,
                correlationId == null ? CORRELATION_ID_UNAVAILABLE : correlationId)
                .getBytes(StandardCharsets.UTF_8);

        response.setStatus(status.value());
        // No charset parameter, because that is what Spring emits for a ResponseEntity<ProblemDetail>: the
        // two Content-Type headers must match or the bodies are not interchangeable to a client that
        // negotiates on it. JSON is UTF-8 by specification, and the bytes above are encoded as such.
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        // Committing here is deliberate. The header writer runs ahead of every caller of this method and is
        // configured to write the default security headers eagerly, so they are already on the response;
        // flushing then guarantees the refusal reaches the client as written rather than depending on what
        // the rest of the chain does with an uncommitted buffer.
        response.flushBuffer();
    }

    /**
     * Resolves the status already set on a response, falling back when it is not a status this framework
     * generation knows.
     *
     * <p>Reading the status back rather than assuming one is what keeps the serialised body honest. The
     * framework's bearer entry point answers {@code 401} for an absent or invalid token but {@code 400} for a
     * malformed request and {@code 403} for insufficient scope, choosing from the RFC 6750 mapping; hardcoding
     * {@code 401} here would publish a body whose {@code status} member disagreed with the status line for
     * those cases, which is exactly the inconsistency this remediation exists to remove.
     *
     * @param response the response whose status was set by the component that refused the request
     * @param fallback the status to publish when the response carries one this framework generation does not
     * enumerate, which no configured component can produce
     * @return the status to publish, never {@code null}
     */
    private static HttpStatus statusOf(final HttpServletResponse response, final HttpStatus fallback) {
        final HttpStatus resolved = HttpStatus.resolve(response.getStatus());
        return resolved == null ? fallback : resolved;
    }

    /**
     * Answers an unauthenticated request with the bearer challenge <em>and</em> the shared problem body.
     *
     * <p>It does not reimplement the challenge, it delegates it. {@code BearerTokenAuthenticationEntryPoint}
     * owns the RFC 6750 behaviour - the {@code WWW-Authenticate: Bearer} header, the error parameters when the
     * failure is an OAuth2 one, and the status that mapping selects - and every one of those is preserved
     * exactly by calling it first and adding only the body it omits. Replacing it with hand-written header
     * logic would put a security header contract in a place that is easy to get subtly wrong.
     *
     * <p>The body is written unconditionally after the delegate returns, because the delegate sets a status
     * and a header and never commits the response.
     */
    private static final class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

        /**
         * The framework entry point that owns the challenge header and the status.
         *
         * <p>Held rather than extended so that this class cannot accidentally suppress part of the delegate's
         * behaviour by overriding it.
         */
        private final BearerTokenAuthenticationEntryPoint challenge =
                new BearerTokenAuthenticationEntryPoint();

        /**
         * Creates the entry point.
         *
         * <p>Stated explicitly because the build's Javadoc gate treats an undocumented default constructor as
         * a warning and escalates every warning to a failure. It holds no configuration: the realm is
         * deliberately unset, which is what makes the challenge the bare {@code Bearer} form.
         */
        private ProblemDetailAuthenticationEntryPoint() {
            super();
        }

        @Override
        public void commence(final HttpServletRequest request, final HttpServletResponse response,
                final AuthenticationException authenticationException) throws IOException {

            this.challenge.commence(request, response, authenticationException);

            // FINDING C-01, severity BLOCKER. The request URI used to be logged here. This entry point is
            // reached by definition BEFORE authentication, so the path and query string are text an anonymous
            // caller chooses, and the masking in src/main/resources/logback-spring.xml redacts labelled values
            // only - a bare card number or government identifier in a path segment survived verbatim. The
            // request line belongs in the container access log, under its own retention, not in the
            // application log; the correlation identifier is the join key between the two, and the caller
            // received the same value in the refusal envelope written just below.
            LOG.warn("Refused an unauthenticated request with {}: errorCode {}, correlationId {}. The reason"
                    + " is not disclosed to the caller", response.getStatus(),
                    ERROR_CODE_AUTHENTICATION_REQUIRED, CorrelationIdFilter.currentCorrelationId());

            writeProblemDetail(response, statusOf(response, HttpStatus.UNAUTHORIZED),
                    AUTHENTICATION_PROBLEM_TITLE, AUTHENTICATION_PROBLEM_DETAIL,
                    ERROR_CODE_AUTHENTICATION_REQUIRED);
        }
    }

    /**
     * The metrics scrape challenge: an HTTP Basic challenge that answers inside the problem envelope.
     *
     * <p><strong>Finding, severity Medium - remediated here.</strong> {@code /actuator/prometheus} answered
     * {@code 401} to every caller in an environment where no scrape credential is configured, which is the
     * correct, fail-closed outcome. What was wrong was the <em>answer</em>: Spring's default body, carrying
     * {@code timestamp}, {@code status}, {@code error} and {@code path} and none of {@code errorCode},
     * {@code correlationId} or a usable detail, under {@code Content-Type: application/json} rather than
     * {@code application/problem+json}. A reviewer reading that body could not tell a missing matcher from a
     * missing credential, and concluded the endpoint had no route at all.
     *
     * <p><strong>Why the framework's Basic entry point is NOT delegated to, when the bearer one is.</strong>
     * {@code BearerTokenAuthenticationEntryPoint} sets the status and the {@code WWW-Authenticate} header
     * directly, which leaves the body free for {@link #writeProblemDetail} to write - that is why
     * {@link ProblemDetailAuthenticationEntryPoint} can delegate. {@code BasicAuthenticationEntryPoint}
     * instead calls {@code HttpServletResponse.sendError}, and {@code sendError} inside a Boot application
     * forwards to the registered error page: {@code BasicErrorController} then renders
     * {@code timestamp status error path} and that is the body the caller receives, whatever is written
     * afterwards. Delegating here therefore cannot work, and appeared to work while the body was being
     * silently discarded.
     *
     * <p>So the status and the header are set on the response directly. The header is not decoration - it is
     * what makes a Basic challenge a Basic challenge, and clients including Prometheus key their
     * credential-presenting behaviour on it - so it is written to the byte, in the {@code Basic realm="..."}
     * form RFC 7617 fixes, rather than approximated.
     *
     * <p>This is the same trap as the request-rejection handler, which produced a zero-length body for the
     * same reason. The general rule it yields: <em>on any path that owes a response body, never call
     * {@code sendError}</em>.
     *
     * <p><strong>What it discloses, and what it refuses to.</strong> The caller is told what the endpoint
     * expects. The caller is <em>not</em> told whether a scrape principal exists, because that is deployment
     * state and an anonymous request has no claim on it. The operator who does need it gets it from the log
     * line below, which states plainly whether the credential is configured - the single fact that separates
     * "your credentials are wrong" from "this deployment has no scrape credential at all", and the fact whose
     * absence turned a configuration gap into a suspected defect.
     */
    private static final class ScrapeAuthenticationEntryPoint implements AuthenticationEntryPoint {

        /**
         * The challenge header, in the {@code Basic realm="..."} form of RFC 7617 section 2.
         *
         * <p>Built once, from {@link #SCRAPE_REALM}, so the realm has a single spelling. The realm is quoted
         * because RFC 7617 requires a quoted-string, and {@link #SCRAPE_REALM} is a literal containing
         * neither a quote nor a backslash, so no escaping is needed and none is performed.
         */
        private static final String CHALLENGE_HEADER_VALUE = "Basic realm=\"" + SCRAPE_REALM + "\"";

        /** Whether a scrape principal is configured, reported to the log and never to the caller. */
        private final boolean principalConfigured;

        /**
         * Builds the challenge for a chain that either has a principal or has none.
         *
         * @param principalConfigured whether {@link #scrapePrincipalFrom} produced a principal
         */
        private ScrapeAuthenticationEntryPoint(final boolean principalConfigured) {
            super();
            this.principalConfigured = principalConfigured;
        }

        @Override
        public void commence(final HttpServletRequest request, final HttpServletResponse response,
                final AuthenticationException authenticationException) throws IOException {

            // setStatus, never sendError. See the class documentation: sendError forwards to the error page
            // and the body written below would be replaced by the container's own.
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE_HEADER_VALUE);

            // FINDING C-01, severity BLOCKER. request.getRequestURI() used to supply the first placeholder.
            // It is caller-chosen text on a pre-authentication boundary, so it could carry a protected value
            // past a masking layer that redacts labelled values only. The constant below names the one route
            // this chain governs, which is strictly more informative than the raw URI and cannot be shaped by
            // a caller: the chain's securityMatcher admits nothing else.
            if (this.principalConfigured) {
                LOG.warn("Refused a metrics scrape of {} with {}. A scrape principal IS configured, so the"
                        + " presented credentials did not match it", PATH_PROMETHEUS,
                        response.getStatus());
            } else {
                LOG.warn("Refused a metrics scrape of {} with {}. NO scrape principal is configured, so this"
                        + " endpoint refuses every caller until both {} and {} are set", PATH_PROMETHEUS,
                        response.getStatus(), KEY_SCRAPE_USERNAME, KEY_SCRAPE_PASSWORD);
            }

            writeProblemDetail(response, statusOf(response, HttpStatus.UNAUTHORIZED),
                    AUTHENTICATION_PROBLEM_TITLE, SCRAPE_AUTHENTICATION_PROBLEM_DETAIL,
                    ERROR_CODE_AUTHENTICATION_REQUIRED);
        }
    }

    /**
     * The metrics scrape refusal after authentication: {@code 403}, enveloped, and silent about bearer tokens.
     *
     * <p>{@link ProblemDetailAccessDeniedHandler} cannot serve this chain, for two reasons that both matter.
     * It delegates to {@code BearerTokenAccessDeniedHandler}, which writes {@code WWW-Authenticate: Bearer} -
     * so a caller refused by an endpoint that accepts <em>only</em> HTTP Basic was being told to present a
     * bearer token. And its detail speaks of "the presented token", a word with no meaning here. Both are the
     * same class of defect as the one this whole chain was fixed for: a refusal that describes a mechanism the
     * endpoint does not use is worse than a refusal that says nothing, because it sends the reader somewhere
     * false.
     *
     * <p>No {@code WWW-Authenticate} header is written. RFC 7235 defines that header for {@code 401}; the
     * bearer variant on a {@code 403} is an RFC 6750 extension for {@code insufficient_scope}, and HTTP Basic
     * has no counterpart. Omitting it is the correct answer rather than an omission.
     *
     * <p>The status is set directly for the same reason as in {@link ScrapeAuthenticationEntryPoint}: on any
     * path that owes a response body, {@code sendError} forwards to the error page and the body is replaced.
     */
    private static final class ScrapeAccessDeniedHandler implements AccessDeniedHandler {

        /** Required so the enclosing class controls instantiation. */
        private ScrapeAccessDeniedHandler() {
            super();
        }

        @Override
        public void handle(final HttpServletRequest request, final HttpServletResponse response,
                final AccessDeniedException accessDeniedException) throws IOException {

            response.setStatus(HttpStatus.FORBIDDEN.value());

            // FINDING C-01, severity BLOCKER. The method and the raw URI used to supply the first two
            // placeholders. Both are caller-chosen; the constant below names the one route this chain governs
            // and cannot be shaped by a caller. See the entry point above for the full reasoning.
            LOG.warn("Refused an authenticated metrics scrape of {} with {}. Neither the required"
                    + " authority nor the served method set is disclosed to the caller",
                    PATH_PROMETHEUS, response.getStatus());

            writeProblemDetail(response, statusOf(response, HttpStatus.FORBIDDEN),
                    AUTHORIZATION_PROBLEM_TITLE, SCRAPE_AUTHORIZATION_PROBLEM_DETAIL,
                    ERROR_CODE_AUTHORIZATION_DENIED);
        }
    }

    /**
     * Answers an unauthorised request with the framework's own headers <em>and</em> the shared problem body.
     *
     * <p>The counterpart of {@link ProblemDetailAuthenticationEntryPoint} for a principal that authenticated
     * and is not entitled: {@code BearerTokenAccessDeniedHandler} is delegated to first, so the {@code 403}
     * status and any {@code WWW-Authenticate} parameters it writes are preserved, and only the body it omits
     * is added.
     */
    private static final class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

        /** The framework handler that owns the status and any challenge parameters. */
        private final BearerTokenAccessDeniedHandler challenge = new BearerTokenAccessDeniedHandler();

        /**
         * Creates the handler.
         *
         * <p>Stated explicitly for the same reason as the sibling entry point's constructor. It holds no
         * configuration.
         */
        private ProblemDetailAccessDeniedHandler() {
            super();
        }

        @Override
        public void handle(final HttpServletRequest request, final HttpServletResponse response,
                final AccessDeniedException accessDeniedException) throws IOException, ServletException {

            this.challenge.handle(request, response, accessDeniedException);

            // FINDING C-01, severity BLOCKER. The request URI used to be logged here. Authentication has
            // succeeded by this point, but the path and query string are still caller-chosen text, and an
            // authenticated standard user probing an administrator route is exactly the caller most likely to
            // put a protected value into one. Same substitution as the entry point above: the correlation
            // identifier is the join key, and the request line stays in the container access log.
            LOG.warn("Refused an authenticated but unentitled request with {}: errorCode {},"
                    + " correlationId {}. The required authority is not disclosed to the caller",
                    response.getStatus(), ERROR_CODE_AUTHORIZATION_DENIED,
                    CorrelationIdFilter.currentCorrelationId());

            writeProblemDetail(response, statusOf(response, HttpStatus.FORBIDDEN),
                    AUTHORIZATION_PROBLEM_TITLE, AUTHORIZATION_PROBLEM_DETAIL,
                    ERROR_CODE_AUTHORIZATION_DENIED);
        }
    }

    /**
     * Bounds the number of request-body bytes any caller may make this application read.
     *
     * <p><strong>Finding, severity High - remediated by this filter.</strong> Exactly one business endpoint is
     * anonymous, the sign-on route, and it accepts a JSON body. That body was deserialized into a
     * {@code SignOnRequest} before any {@code @Size} constraint ran, because Bean Validation runs on an
     * already-constructed object: Jackson allocates first and is validated second. So an unauthenticated
     * caller could make the application read and materialise an arbitrarily large document, and the field
     * contract that bounds the sign-on fields to a few dozen characters each never entered into it.
     *
     * <p><strong>Why no property could have fixed this.</strong> The obvious remedy,
     * {@code server.tomcat.max-http-form-post-size}, does not apply: that setting bounds Tomcat's own parsing
     * of {@code application/x-www-form-urlencoded} parameters, and a JSON body is never parsed by that code
     * path - it is read by a message converter straight from the input stream. {@code max-swallow-size} is not
     * a request bound either; it caps how much of an <em>abandoned</em> body Tomcat will discard. Both are set
     * in {@code application.yml} for their own reasons and neither closes this gap, which is precisely why an
     * explicit filter is required rather than a configuration line.
     *
     * <p><strong>Both shapes are bounded, and both are refused identically.</strong> A request that declares
     * {@code Content-Length} is refused on the declaration alone, before a byte is read. A request that
     * declares no length at all - the chunked shape, which is the one an attacker would choose - has its body
     * read here, ahead of the chain, up to one byte past the bound; over the bound it is refused with the same
     * status and the same body as the declared case, and within the bound the captured bytes are replayed to
     * the chain. Checking only {@code Content-Length} would leave the more dangerous case open.
     *
     * <p><strong>Finding, severity Medium - remediated by that up-front read.</strong> Previously the
     * length-less shape was merely wrapped, and the bound was enforced lazily as the body was consumed by
     * throwing an {@link IOException} from the stream. That produced a different answer for the same
     * violation: Spring's {@code AbstractMessageConverterMethodArgumentResolver} catches an
     * {@code IOException} raised while reading a body and rethrows it as
     * {@code HttpMessageNotReadableException}, so an oversized chunked body was answered {@code 400 Bad
     * Request} - "your body was malformed", which it was not - while an oversized declared body was answered
     * {@code 413}. The status a caller received therefore depended on the transfer encoding it happened to
     * choose. <strong>Translating that exception instead would not have fixed it deterministically</strong>,
     * which is why the read moved rather than the exception being caught: a checked {@code IOException} never
     * escapes to this filter at all because the resolver has already converted it, and an unchecked variant
     * only arrives after {@code FrameworkServlet} has rewrapped it, by which time the answer depends on
     * whether any resolver claimed it and whether the response is still uncommitted. Refusing before the chain
     * runs removes every one of those conditions: the two shapes are now indistinguishable to a caller, and
     * neither reaches a handler.
     *
     * <p>The cost of the up-front read is bounded by the bound itself - at most 16 KB is buffered, for
     * requests whose method may carry a body and which declared no length - and it buys the property that
     * nothing downstream runs for either oversized shape. {@code GET}, {@code HEAD}, {@code OPTIONS} and
     * {@code TRACE} are left untouched, because no route in this application reads a body from them, so no
     * pair of equivalent violations exists there to diverge.
     *
     * <p><strong>Position in the chain is load-bearing.</strong> Registered after
     * {@link org.springframework.security.web.header.HeaderWriterFilter} so a refusal still carries
     * {@code X-Content-Type-Options} and {@code X-Frame-Options}, and before
     * {@link org.springframework.security.web.access.intercept.AuthorizationFilter} and MVC binding so it
     * governs every request - anonymous ones included, those being the only ones that can reach the sign-on
     * route. Moving it after authorization, or leaving the bound to argument resolution, would exempt exactly
     * the caller it exists to bound.
     *
     * <p><strong>Finding LOW-004, severity Low, resolved:</strong> this paragraph said "before
     * {@code SecurityContextHolderFilter} and the bearer filter". It is not. The measured order is
     * {@code SecurityContextHolderFilter}, {@code JwtAuthenticationFilter}, {@code HeaderWriterFilter}, this
     * filter, {@link RequestMediaTypeFilter}, and {@code AuthorizationFilter} last - so this filter runs
     * <em>after</em> the bearer filter. The guarantee survives the correction because the bearer filter
     * refuses nothing: it populates the security context when a token is present and passes an anonymous
     * request through untouched, and the refusal for want of authorization is the last filter in the chain.
     * What the position actually buys is therefore stated as what it is.
     *
     * <p>The response is {@code 413 Payload Too Large} carrying the shared problem envelope that
     * {@link SecurityConfig#writeProblemDetail} composes - the same shape every controller and both security
     * refusals produce, and no more disclosive than the {@code server.error} settings allow, since the detail
     * is fixed and names neither the bound nor anything about the request. Nothing about the rejected request
     * is logged at request scope beyond its declared length, so an oversized body cannot be used to write
     * attacker-chosen text into the log.
     */
    private static final class RequestBodyLimitFilter extends OncePerRequestFilter {

        /**
         * The greatest number of body bytes this application will read from one request, namely 16384.
         *
         * <p>Derived from the field contracts rather than chosen for roundness. The largest request type in
         * this application is {@code AccountUpdateRequest}, which carries both the new values and the
         * pre-image snapshot the change-detection comparison of {@code app/cbl/COACTUPC.cbl:L669-L756}
         * requires: 118 length-constrained fields totalling 1,567 characters of field data. Adding the JSON
         * punctuation and its property names at their actual lengths puts a fully populated instance at
         * roughly 3.9 KB, so this bound leaves better than four times headroom over the largest legitimate
         * body while remaining four orders of magnitude below what an unbounded read permits.
         */
        private static final long MAX_BODY_BYTES = 16L * 1024L;

        /**
         * The transfer buffer used by the up-front read, namely 8192 bytes.
         *
         * <p>A copy buffer size and nothing more: it bounds how much is moved per {@code read} call, never how
         * much is accepted, which is {@link #MAX_BODY_BYTES} alone. Two of these fit inside the bound, so a
         * legitimate body is captured in at most two passes.
         */
        private static final int COPY_BUFFER_BYTES = 8192;

        /**
         * Creates the filter.
         *
         * <p>Stated explicitly rather than left implicit: the build's Javadoc gate treats an undocumented
         * default constructor as a warning and escalates every warning to a failure, and a filter registered
         * on the security chain is exactly the kind of type whose construction a reader should not have to
         * infer. It holds no state, so there is nothing to inject and nothing to configure - the bound is a
         * compile-time constant derived from the field contracts, not a property.
         */
        private RequestBodyLimitFilter() {
            super();
        }

        @Override
        protected void doFilterInternal(
                final HttpServletRequest request,
                final HttpServletResponse response,
                final FilterChain filterChain) throws ServletException, IOException {

            final long declaredLength = request.getContentLengthLong();

            if (declaredLength > MAX_BODY_BYTES) {
                refuse(response, declaredLength);
                return;
            }

            if (declaredLength < 0L && mayCarryBody(request)) {
                final byte[] captured;
                try {
                    // One byte past the bound is read on purpose: it is what distinguishes a body that is
                    // exactly at the bound, which is admitted, from one that is over it, which is refused.
                    // Nothing beyond that byte is ever read, so the allocation is bounded whatever the caller
                    // sends.
                    captured = readAtMost(request.getInputStream(), MAX_BODY_BYTES + 1L);
                } catch (final IOException unreadable) {
                    // A length-less body is a transfer-encoded one, so this read is where the container
                    // decodes the chunked framing - the chunk sizes, the terminating zero chunk and any
                    // trailer fields. Reading here rather than letting a message converter read is what
                    // makes the bound apply before the chain runs, but it also means a framing failure
                    // surfaces INSIDE the security chain, where no @ExceptionHandler can see it: without
                    // this arm it escaped to the container and was answered with the framework's default
                    // error body, carrying neither the error code nor the correlation identifier, on a
                    // route that may be reached anonymously. Refusing here keeps a malformed body a client
                    // error with the shared envelope, and keeps the diagnostic on a log stream the caller
                    // cannot read.
                    refuseUnreadable(response, unreadable);
                    return;
                }
                if (captured.length > MAX_BODY_BYTES) {
                    refuse(response, declaredLength);
                    return;
                }
                filterChain.doFilter(new BufferedBodyRequest(request, captured), response);
                return;
            }

            filterChain.doFilter(new BoundedBodyRequest(request, MAX_BODY_BYTES), response);
        }

        /**
         * Whether a request's method admits a body this application would read.
         *
         * <p>The four methods excluded have no body semantics on any route here, so reading ahead for them
         * would buy nothing and would touch the most common verb on the surface. A method this application
         * does not recognise at all is treated as one that may carry a body, which is the direction that keeps
         * the bound applied; an absent method - which a servlet container does not produce - takes the same
         * arm as a body-less one and is still bounded lazily by the wrapper below.
         *
         * @param request the request being screened, never {@code null}
         * @return {@code true} when the method may carry a body this application reads
         */
        private static boolean mayCarryBody(final HttpServletRequest request) {
            final String method = request.getMethod();
            return method != null && !BODYLESS_METHODS.contains(method);
        }

        /**
         * Reads at most a fixed number of bytes from a request body, stopping at the bound rather than at the
         * end of the stream.
         *
         * <p>The stream is never drained past {@code limit}, so an unbounded body cannot make this method
         * allocate without bound - which is the whole purpose of reading here rather than letting a message
         * converter read. A zero-length return is treated as the end of the stream: a blocking servlet input
         * stream returns zero only when asked for zero bytes, which the arithmetic below cannot request, so
         * the branch cannot truncate a legitimate body, and treating it as end of stream is what makes the loop
         * provably terminate.
         *
         * @param source the request body stream, never {@code null}
         * @param limit the greatest number of bytes to read, which the caller sets one byte past the bound it
         * is enforcing
         * @return the bytes read, never {@code null} and never longer than {@code limit}
         * @throws IOException if the body cannot be read, which is propagated rather than translated because
         * a broken connection is not an oversized body
         */
        private static byte[] readAtMost(final InputStream source, final long limit) throws IOException {
            final ByteArrayOutputStream captured = new ByteArrayOutputStream();
            final byte[] buffer = new byte[COPY_BUFFER_BYTES];
            long remaining = limit;

            while (remaining > 0L) {
                final int read = source.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read <= 0) {
                    break;
                }
                captured.write(buffer, 0, read);
                remaining -= read;
            }
            return captured.toByteArray();
        }

        /**
         * Refuses one oversized body with the shared problem envelope.
         *
         * <p>Both arms of {@link #doFilterInternal} call this method, which is what makes the declared-length
         * and the length-less refusals identical rather than merely similar. The declared length is logged so
         * an operator can tell the two apart; {@code -1} means the request declared none and the bound was
         * reached while reading it.
         *
         * @param response the response to refuse on, never {@code null}
         * @param declaredLength the length the request declared, or {@code -1} when it declared none
         * @throws IOException if the refusal cannot be written
         */
        private static void refuse(final HttpServletResponse response, final long declaredLength)
                throws IOException {

            LOG.warn("Refused a request body with {}: declared length {} against a bound of {} bytes",
                    HttpStatus.PAYLOAD_TOO_LARGE.value(), declaredLength, MAX_BODY_BYTES);

            writeProblemDetail(response, HttpStatus.PAYLOAD_TOO_LARGE, PAYLOAD_PROBLEM_TITLE,
                    PAYLOAD_PROBLEM_DETAIL, ERROR_CODE_PAYLOAD_TOO_LARGE);
        }

        /**
         * Refuses one body that could not be read with the shared problem envelope.
         *
         * <p>{@code 400 Bad Request} rather than {@code 500}: the request never reached an operation, and
         * what went wrong is the caller's framing of its own body - a chunk length that does not match the
         * bytes that followed, a connection that ended mid-body, or a trailer section the container could
         * not parse. Answering it as a server failure both misinforms the caller and lets an unauthenticated
         * request drive server-error records, which is precisely the condition this arm removes.
         *
         * <p>The cause is logged at {@code WARN} with its type and message and never relayed to the caller,
         * for the same reason the controllers keep parser text out of their bodies: a framing diagnostic can
         * quote the fragment it stopped on, and these routes carry credentials and personal data.
         *
         * @param response the response to refuse on, never {@code null}
         * @param unreadable the read failure, retained as the log's cause so no root cause is discarded
         * @throws IOException if the refusal cannot be written
         */
        private static void refuseUnreadable(final HttpServletResponse response, final IOException unreadable)
                throws IOException {

            LOG.warn("Refused a request body with {}: it could not be read. Read failure {}",
                    HttpStatus.BAD_REQUEST.value(), unreadable.getClass().getSimpleName());
            LOG.debug("Cause chain of the unreadable request body", unreadable);

            writeProblemDetail(response, HttpStatus.BAD_REQUEST, MALFORMED_BODY_PROBLEM_TITLE,
                    MALFORMED_BODY_PROBLEM_DETAIL, ERROR_CODE_MALFORMED_BODY);
        }
    }

    /**
     * Screens the {@code Content-Type} of every request that may carry a body, and refuses anything the
     * seventeen operations cannot read before the request reaches argument resolution.
     *
     * <p><strong>Why this exists.</strong> All eight body-accepting operations consume {@code application/json}
     * and nothing else. Three shapes of {@code Content-Type} used to be answered wrongly:</p>
     * <ul>
     *   <li>A <strong>wildcard</strong> type or subtype - {@code *&#47;*}, {@code application&#47;*} - reached
     *       {@code org.springframework.http.HttpHeaders#setContentType}, which rejects a wildcard with an
     *       {@code IllegalArgumentException}. That was thrown during argument resolution, before the mapped
     *       method was entered and before any bean validation ran, and no {@code @ExceptionHandler} in the
     *       controller package claims {@code IllegalArgumentException}, so it escaped to the container as a
     *       {@code 500} carrying the framework's default error body - on eight routes, one of which is the
     *       anonymous sign-on.</li>
     *   <li>A <strong>media type with no JSON reader</strong> - {@code multipart/form-data},
     *       {@code multipart/mixed} - reached the same code path with the same outcome.</li>
     *   <li>A <strong>parsable but unreadable</strong> type - {@code text/plain}, {@code application/xml} -
     *       produced the correct {@code 415} but in the framework's default body rather than this
     *       application's envelope, so no client-side handler written against the envelope could read it.</li>
     * </ul>
     *
     * <p>Screening here answers all three identically and correctly: {@code 415 Unsupported Media Type} in the
     * shared problem envelope that {@link SecurityConfig#writeProblemDetail} composes, with the error code and
     * the correlation identifier a caller needs and none of the request echoed back.
     *
     * <p><strong>Why a filter rather than an exception handler.</strong> Two reasons, both structural. First,
     * a refusal written here happens inside the original dispatch, so the correlation identifier
     * {@code com.cardemo.observability.CorrelationIdFilter} placed in the logging context is still present -
     * whereas a failure that escapes to the container is rendered on an {@code ERROR} dispatch after that
     * context has been cleared, which is why the previous {@code 500} records carried an empty correlation
     * identifier while the response itself carried a real one. Second, the condition is a property of the
     * request rather than of any one operation: one screen covers all eight routes and cannot be forgotten on
     * a ninth.
     *
     * <p><strong>What it deliberately does not do.</strong> A request that carries <em>no body content</em> is
     * passed through whatever it declares or omits: a body-less {@code DELETE} legitimately sends no
     * {@code Content-Type}, so refusing on the header's absence alone would break the user deletion operation,
     * and a {@code POST} with no body at all is already answered by the controller's own missing-body refusal.
     * Only a request that sends bytes while describing them as nothing is refused - see
     * {@link #carriesBodyContent(HttpServletRequest)}. The four methods this application never reads a body
     * from are skipped for the same reason they are skipped by the body bound. Nothing about the response body
     * of a successful request is affected: content negotiation on the way out is unchanged.
     *
     * <p><strong>Position in the chain is load-bearing</strong>, exactly as it is for the body bound.
     * Registered after {@link RequestBodyLimitFilter} so an oversized body is still refused as oversized
     * rather than as an unusable media type, after
     * {@link org.springframework.security.web.header.HeaderWriterFilter} so a refusal still carries the
     * default security headers, and before
     * {@link org.springframework.security.web.access.intercept.AuthorizationFilter} and MVC binding so it
     * governs anonymous requests too - the sign-on route being the one that most needs it. Finding LOW-004:
     * this said "before the bearer filter", which the measured chain contradicts; see the corresponding
     * paragraph on {@link RequestBodyLimitFilter} for the order and for why the guarantee is unchanged.
     */
    private static final class RequestMediaTypeFilter extends OncePerRequestFilter {

        /**
         * Creates the filter.
         *
         * <p>Stated explicitly rather than left implicit, for the same reason
         * {@link RequestBodyLimitFilter#RequestBodyLimitFilter()} is: the build's Javadoc gate treats an
         * undocumented default constructor as a warning and escalates every warning to a failure. It holds no
         * state and has nothing to configure - the one media type this application reads is a compile-time
         * constant of the framework.
         */
        private RequestMediaTypeFilter() {
            super();
        }

        @Override
        protected void doFilterInternal(
                final HttpServletRequest request,
                final HttpServletResponse response,
                final FilterChain filterChain) throws ServletException, IOException {

            final String declared = request.getContentType();
            final boolean absent = declared == null || declared.isBlank();

            if (mayCarryBody(request) && (absent ? carriesBodyContent(request) : !isReadable(declared))) {
                // FINDING C-01, severity BLOCKER. The raw header value used to be logged here, on the
                // reasoning that caller-supplied text belongs on a masked stream rather than in a response
                // body. That reasoning is wrong in one decisive respect: this filter runs BEFORE the bearer
                // filter, so an entirely unauthenticated caller chooses the bytes, and Content-Type is a
                // free-text header. A caller can therefore place a card number, a password, a customer name
                // or a government identifier in it, and the masking in
                // src/main/resources/logback-spring.xml only redacts LABELLED values - a bare protected value
                // carries no label and survives verbatim. JSON encoding stops a forged record; it does not
                // stop disclosure.
                //
                // What is logged instead is a closed set of values, every one of them a constant of this
                // class: the status, the error code the caller also receives, and the bounded classification
                // that isReadable(String) already computes. The correlation identifier is logged too, because
                // it is the join key between this record and the envelope the caller received - and it is
                // validated to [A-Za-z0-9_-] by CorrelationIdFilter, so it cannot itself carry a payload.
                // The header is neither logged nor relayed: an operator who needs the exact bytes captures
                // them at the edge, where a capture is an explicit, auditable act.
                LOG.warn("Refused a request with {}: the declared media type is not one this application "
                                + "reads. Classification {}, errorCode {}, correlationId {}",
                        HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(), mediaTypeRefusal(declared, absent),
                        ERROR_CODE_UNSUPPORTED_MEDIA_TYPE, CorrelationIdFilter.currentCorrelationId());

                writeProblemDetail(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, MEDIA_TYPE_PROBLEM_TITLE,
                        MEDIA_TYPE_PROBLEM_DETAIL, ERROR_CODE_UNSUPPORTED_MEDIA_TYPE);
                return;
            }

            filterChain.doFilter(request, response);
        }

        /**
         * Whether a request's method admits a body this application would read.
         *
         * <p>The same rule the body bound applies, and for the same reason: the four excluded methods have no
         * body semantics on any route here, and an unrecognised method is treated as one that may carry a
         * body, which is the direction that keeps the screen applied.
         *
         * @param request the request being screened, never {@code null}
         * @return {@code true} when the method may carry a body this application reads
         */
        private static boolean mayCarryBody(final HttpServletRequest request) {
            final String method = request.getMethod();
            return method != null && !BODYLESS_METHODS.contains(method);
        }

        /**
         * Whether a request actually carries body content, used only when it declared no media type.
         *
         * <p>This is what keeps the screen from breaking the one operation that legitimately sends no
         * {@code Content-Type}: the user deletion route is a {@code DELETE} carrying no body, and a
         * body-less request has nothing to interpret, so there is no media type to require. A request that
         * <em>does</em> send bytes while stating nothing about them is refused instead of guessed at, which is
         * the same posture the three refusals of {@link #isReadable(String)} take.
         *
         * <p>Both framings are recognised: a declared length above zero, and a transfer-encoded body, which
         * declares no length at all. A declared length of zero is a body-less request whatever the method.
         *
         * @param request the request being screened, never {@code null}
         * @return {@code true} when the request carries bytes it did not describe
         */
        private static boolean carriesBodyContent(final HttpServletRequest request) {
            return request.getContentLengthLong() > 0L
                    || request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null;
        }

        /**
         * Whether a declared media type is one this application can read a request body as.
         *
         * <p>Three refusals, in order. A header the media-type grammar cannot parse at all - {@code
         * application/} is the shortest example - is refused rather than guessed at. A header carrying a
         * wildcard in its type or subtype is refused because a request states what it <em>is</em> sending,
         * not what it would accept, and because that is exactly the shape the framework's header accessor
         * throws on. Anything else is admitted only when the registered JSON reader would claim it: type
         * {@code application} with subtype {@code json} or with a {@code +json} structured suffix, which is
         * precisely the {@code application/*+json} range that reader publishes.
         *
         * <p>Parameters are not consulted, so a charset or a version parameter neither admits nor refuses a
         * type on its own. A comma-separated list is refused, because a request has exactly one body and
         * therefore exactly one media type; the grammar rejects the comma, so the first refusal claims it.
         *
         * @param declared the raw header value, never {@code null} and never blank
         * @return {@code true} when a body of that media type can be read by one of the operations
         */
        private static boolean isReadable(final String declared) {
            return classify(declared) == MediaTypeVerdict.READABLE;
        }

        /**
         * Reduces a declared media type to one of a closed set of verdicts.
         *
         * <p><strong>Finding C-01, severity Blocker.</strong> This method exists so that the refusal record
         * can name <em>why</em> a request was refused without naming <em>what</em> the request declared. The
         * three refusals of {@link #isReadable(String)} were already distinct decisions; making them a
         * returned value rather than three early {@code false} returns means the log line and the screen share
         * one decision instead of the log re-deriving it - the duplication Rule 1 Clause C forbids - and means
         * the logged value is drawn from an enum with four constants that no caller can widen.
         *
         * <p>Pure: it reads its argument and touches nothing else.
         *
         * @param declared the raw header value, never {@code null} and never blank
         * @return the verdict for that value, never {@code null} and never
         *     {@link MediaTypeVerdict#NO_MEDIA_TYPE_DECLARED}, which describes a request that sent no header
         *     at all and therefore has no value to classify
         */
        private static MediaTypeVerdict classify(final String declared) {
            final MediaType parsed;
            try {
                parsed = MediaType.parseMediaType(declared);
            } catch (final InvalidMediaTypeException unparsable) {
                return MediaTypeVerdict.UNPARSABLE;
            }
            if (parsed.isWildcardType() || parsed.isWildcardSubtype()) {
                return MediaTypeVerdict.WILDCARD;
            }
            if (!MediaType.APPLICATION_JSON.getType().equalsIgnoreCase(parsed.getType())) {
                return MediaTypeVerdict.NO_JSON_READER;
            }
            final String subtype = parsed.getSubtype().toLowerCase(Locale.ROOT);
            final boolean readable = subtype.equals(MediaType.APPLICATION_JSON.getSubtype())
                    || subtype.endsWith("+" + MediaType.APPLICATION_JSON.getSubtype());
            return readable ? MediaTypeVerdict.READABLE : MediaTypeVerdict.NO_JSON_READER;
        }

        /**
         * The bounded reason a media-type refusal happened, as it appears in the refusal record.
         *
         * <p>A request that declared nothing has nothing to classify, so it is answered by its own constant
         * rather than by parsing the empty string.
         *
         * @param declared the raw header value, which may be {@code null} or blank
         * @param absent   whether the request declared no media type at all
         * @return the classification to log, never {@code null}
         */
        private static MediaTypeVerdict mediaTypeRefusal(final String declared, final boolean absent) {
            return absent ? MediaTypeVerdict.NO_MEDIA_TYPE_DECLARED : classify(declared);
        }
    }

    /**
     * The closed set of outcomes {@link RequestMediaTypeFilter} reaches for one declared media type.
     *
     * <p><strong>Finding C-01, severity Blocker.</strong> An enum rather than a set of string constants,
     * because the point of the remediation is that the logged value cannot be attacker-shaped: the refusal
     * record carries one of exactly five names, all of them fixed at compile time, in place of a free-text
     * header a wholly unauthenticated caller chooses. Cardinality is bounded by the type, not by discipline.
     *
     * <p>Declared at configuration level rather than inside the filter because a nested type may not be
     * declared inside a nested class that is itself {@code static final} in this file's established shape, and
     * because the verdict is part of this configuration's refusal contract rather than private detail of one
     * method.
     */
    private enum MediaTypeVerdict {

        /** A media type one of the eight body operations can read: {@code application/json} or {@code +json}. */
        READABLE,

        /** The request sent body bytes while declaring no media type at all. */
        NO_MEDIA_TYPE_DECLARED,

        /** The header cannot be parsed by the media-type grammar - {@code application/} is the shortest case. */
        UNPARSABLE,

        /** The header carries a wildcard in its type or its subtype, which states no concrete body type. */
        WILDCARD,

        /** A well-formed media type for which no registered reader would claim the body. */
        NO_JSON_READER
    }

    /**
     * Replays an already-captured request body to the rest of the chain.
     *
     * <p>This is what makes the up-front read transparent. The body bound reads a length-less body itself in
     * order to answer an oversized one before the chain runs; a request whose body has been consumed would
     * otherwise reach the handler empty, so the captured bytes are handed on through this wrapper instead. It
     * is used only for requests that declared no length and stayed within the bound, so the array it holds is
     * never larger than the bound.
     *
     * <p>Both body accessors are overridden, and each call yields a fresh stream over the same bytes, so a
     * reader that asks twice is answered twice rather than being handed an exhausted stream.
     */
    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {

        /** The captured body, never longer than the bound the filter enforces. */
        private final byte[] body;

        /**
         * Wraps one request around its already-captured body.
         *
         * @param request the request whose body was read ahead of the chain
         * @param body the captured bytes, copied on the way in so the wrapper cannot be mutated afterwards
         */
        private BufferedBodyRequest(final HttpServletRequest request, final byte[] body) {
            super(request);
            this.body = body.clone();
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CapturedServletInputStream(new ByteArrayInputStream(this.body));
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    /**
     * A servlet input stream over bytes already held in memory.
     *
     * <p>The stream half of {@link BufferedBodyRequest}. It adds no bound of its own, because the bytes it
     * serves were bounded when they were captured; it exists only because a servlet request must hand out a
     * {@link ServletInputStream} and the standard library's byte-array stream is not one.
     */
    private static final class CapturedServletInputStream extends ServletInputStream {

        /** The captured bytes being served. */
        private final ByteArrayInputStream captured;

        /**
         * Wraps one in-memory body.
         *
         * @param captured the bytes to serve, never {@code null}
         */
        private CapturedServletInputStream(final ByteArrayInputStream captured) {
            this.captured = captured;
        }

        @Override
        public int read() {
            return this.captured.read();
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) {
            return this.captured.read(buffer, offset, length);
        }

        @Override
        public int available() {
            return this.captured.available();
        }

        @Override
        public boolean isFinished() {
            return this.captured.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(final ReadListener readListener) {
            // The specification permits a read listener only on an upgraded or asynchronous request, and no
            // route in this application enters either mode. Refusing states that rather than accepting a
            // listener that would never be called, which would be a silently broken contract.
            throw new IllegalStateException(
                    "A read listener cannot be set on a request whose body was captured by the request-body"
                            + " bound, because this application serves no asynchronous or upgraded request");
        }

        @Override
        public void close() throws IOException {
            this.captured.close();
        }
    }

    /**
     * Wraps a request so its body cannot yield more than a fixed number of bytes.
     *
     * <p><strong>This is defence in depth for a request that declared a length within the bound.</strong> A
     * declaration is a claim, and a client that under-declares it would otherwise be free to send more; the
     * stream counts what it hands out and fails once the bound is exceeded, so an over-long body stops being
     * read rather than continuing to allocate. In practice the container enforces the declaration first - it
     * delivers no more bytes than {@code Content-Length} announced - so this bound is a second line that is
     * expected never to fire, and is kept because "expected never to fire" is not the same as "cannot".
     *
     * <p>It is <strong>not</strong> what bounds a request that declared no length: those are read and refused
     * up front by {@link RequestBodyLimitFilter}, precisely because a lazy refusal from inside a body read
     * cannot produce a deterministic status. See that filter's Medium-severity finding for why.
     *
     * <p>The failure is an {@link IOException} rather than a custom exception because that is what a servlet
     * input stream is permitted to throw, and what every reader up the stack - including Jackson - already
     * handles. Spring converts it into {@code HttpMessageNotReadableException}, which the controllers answer
     * {@code 400} through the shared problem envelope: the honest answer for a body that disagreed with its
     * own declaration, and no longer the answer any oversized body receives.
     */
    private static final class BoundedBodyRequest extends HttpServletRequestWrapper {

        /** The bound, in bytes, applied to this request's body. */
        private final long maxBodyBytes;

        /**
         * Wraps one request.
         *
         * @param request the request whose body is to be bounded
         * @param maxBodyBytes the greatest number of bytes the body may yield
         */
        private BoundedBodyRequest(final HttpServletRequest request, final long maxBodyBytes) {
            super(request);
            this.maxBodyBytes = maxBodyBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new BoundedServletInputStream(super.getInputStream(), this.maxBodyBytes);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    /**
     * A servlet input stream that refuses to yield more than a fixed number of bytes.
     *
     * <p>Both {@code read} overloads are overridden. Overriding only the single-byte form would leave the bulk
     * form unbounded, and the bulk form is the one every buffered reader actually calls - so a partial
     * override would look correct and bound nothing.
     */
    private static final class BoundedServletInputStream extends ServletInputStream {

        /** The stream being bounded. */
        private final ServletInputStream delegate;

        /** The greatest number of bytes this stream may yield in total. */
        private final long maxBodyBytes;

        /** How many bytes have been yielded so far. */
        private long consumed;

        /**
         * Wraps one stream.
         *
         * @param delegate the stream to bound
         * @param maxBodyBytes the greatest number of bytes it may yield
         */
        private BoundedServletInputStream(final ServletInputStream delegate, final long maxBodyBytes) {
            this.delegate = delegate;
            this.maxBodyBytes = maxBodyBytes;
        }

        @Override
        public int read() throws IOException {
            final int value = this.delegate.read();
            if (value != -1) {
                countOrRefuse(1);
            }
            return value;
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) throws IOException {
            final int count = this.delegate.read(buffer, offset, length);
            if (count > 0) {
                countOrRefuse(count);
            }
            return count;
        }

        /**
         * Records bytes yielded and refuses once the bound is passed.
         *
         * @param count how many bytes were just yielded
         * @throws IOException when the total exceeds the bound
         */
        private void countOrRefuse(final int count) throws IOException {
            this.consumed += count;
            if (this.consumed > this.maxBodyBytes) {
                throw new IOException(String.format(
                        Locale.ROOT,
                        "Request body exceeds the %d byte limit this application accepts.",
                        this.maxBodyBytes));
            }
        }

        @Override
        public boolean isFinished() {
            return this.delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return this.delegate.isReady();
        }

        @Override
        public void setReadListener(final ReadListener readListener) {
            this.delegate.setReadListener(readListener);
        }

        @Override
        public int available() throws IOException {
            return this.delegate.available();
        }

        @Override
        public void close() throws IOException {
            this.delegate.close();
        }
    }
}
