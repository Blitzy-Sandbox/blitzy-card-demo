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

import com.cardemo.security.JwtAuthenticationFilter;
import com.cardemo.security.JwtTokenProvider;
import jakarta.servlet.DispatcherType;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

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
 * </ul>
 *
 * <p>It additionally <strong>supersedes {@code app/jcl/CBADMCDJ.jcl}</strong>, the DFHCSDUP job that
 * installed those resource definitions into the region - {@code EXEC PGM=DFHCSDUP,REGION=0M} at
 * {@code :L27} and {@code SYSIN DD *,SYMBOLS=JCLONLY} at {@code :L33}. That member has
 * <strong>no Java analogue other than this class</strong>: there is no separate resource-definition step in
 * the target, because the authorisation surface is declared in code and applied at startup. It is recorded
 * here so that the traceability matrix can account for the member rather than leaving it unmapped.
 *
 * <p>Three beans are published and nothing else:
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
 *       The two decisions are related but distinct, and restating the list here would let the pair drift.</li>
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
 *   <li><strong>No {@code java.time.Clock} bean.</strong> See the findings register: it is a real gap, but it
 *       is not this file's to fill.</li>
 * </ul>
 *
 * <h2>The authorisation surface: exactly seventeen transactions</h2>
 *
 * <p>Every rule below traces to one {@code DEFINE TRANSACTION} entry in {@code app/csd/CARDDEMO.CSD}, cited
 * with its line number, and to the controller method that now serves it. The seventeen are
 * {@code CC00 CM00 CA00 CAVW CAUP CCLI CCDL CCUP CT00 CT01 CT02 CB00 CR00 CU00 CU01 CU02 CU03}.
 *
 * <dl>
 *   <dt>{@code CC00} at {@code :L378} - {@code COSGN00C}, sign-on</dt>
 *   <dd>{@code POST} under {@value #PATH_SIGN_ON}, and the <strong>only unauthenticated operation in the
 *       application</strong>. The transaction identifier is declared by the program itself at
 *       {@code app/cbl/COSGN00C.cbl:L37}, {@code WS-TRANID PIC X(04) VALUE 'CC00'}.</dd>
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
 * </dl>
 *
 * <p>Nothing else in the application is reachable. The final rule is
 * {@code anyRequest().denyAll()}, so a path that no rule names is refused rather than served, and a new
 * controller added without a rule fails closed. That direction is deliberate: the opposite default turns
 * every future omission into an unauthenticated endpoint.
 *
 * <h2>The eighteenth transaction: {@code Not available}</h2>
 *
 * <p>{@code app/csd/CARDDEMO.CSD} defines eighteen transactions, not seventeen. The eighteenth is
 * {@code CDV1} at {@code :L388}, whose body at {@code :L390} names {@code PROGRAM(COCRDSEC)}, and which the
 * definition itself describes as a developer transaction.
 *
 * <p><strong>The program source for that transaction is {@code Not available}.</strong> A repository-wide
 * search at {@code 7756d89} finds {@code COCRDSEC} in exactly two places, both inside the resource
 * definition file itself - {@code app/csd/CARDDEMO.CSD:L211}, the {@code DEFINE PROGRAM} entry, and
 * {@code :L390}, inside the transaction body - and finds {@code CDV1} in exactly one place, {@code :L388}.
 * There is no program member for it anywhere under {@code app/}.
 *
 * <p><em>What would be needed:</em> either a {@code COCRDSEC} source member added to {@code app/cbl}, or a
 * change to the resource definitions removing the dangling entry. Neither is available and neither may be
 * fabricated. <strong>Severity Low</strong> - it costs one line of the transaction inventory and nothing
 * else, because a transaction with no program could not have been dispatched in the legacy region either.
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
 * <p><strong>Accepted consequence, severity Low.</strong> Because nothing is re-read, a change to a user's
 * type takes effect only when their current token expires, bounded by
 * {@code carddemo.security.jwt.expiration-seconds}, which defaults to 3600. A revocation check would close
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
 * <p><strong>Severity High, and it fails silently.</strong> If the claim name or an authority spelling used
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
 * </ol>
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
 * {@code Cache-Control}, {@code Pragma} and {@code Expires}. Those values were confirmed on a running
 * instance rather than assumed. Two rules follow, and they pull in opposite directions only in appearance:
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
 * </ul>
 *
 * <p>The one header this class does cause is the bearer challenge: a rejected request carries
 * {@code WWW-Authenticate: Bearer} with no realm and no error parameters, which is the bare RFC 6750 form
 * the configured entry point emits when no token was presented at all.
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
 *       which makes this policy untestable in isolation. That is not a hypothetical - it was observed while
 *       validating this class, as a hard startup failure reading "a bean named
 *       {@code mvcHandlerMappingIntrospector} ... is required".</li>
 *   <li>Matcher selection would depend on classpath contents rather than on a decision recorded in source,
 *       which is the opposite of the explicit behaviour Clause A asks for.</li>
 *   <li>The inferred matcher additionally resolves servlet mappings while configuring, so a second
 *       registered servlet turns a working configuration into an ambiguous-mapping failure.</li>
 * </ul>
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
 *       {@code Ordered.HIGHEST_PRECEDENCE}. Boot registers the whole security chain at
 *       {@code SecurityProperties.DEFAULT_FILTER_ORDER}, which is {@code -100}, so the correlation filter
 *       already runs <em>before</em> the entire chain - including before a 401 or a 403 - and every request
 *       is correlated whatever its outcome. <strong>It is therefore not added here.</strong> Adding it would
 *       register the same filter a second time, which is the duplication Clause C forbids and the cause of
 *       the doubled log lines that filter's own troubleshooting notes describe.</li>
 *   <li>{@code com.cardemo.security.JwtAuthenticationFilter} is inserted <em>inside</em> the chain,
 *       immediately after {@code SecurityContextHolderFilter}. The direction matters: that filter publishes
 *       its servlet-level precedence as {@code DEFAULT_FILTER_ORDER + 10} deliberately, so that its
 *       auto-registered servlet-level copy sits <em>after</em> the chain and is skipped, since it extends
 *       {@code OncePerRequestFilter}. Were the authentication established outside and ahead of the chain,
 *       the context-holder filter would replace it with an empty context and every request would come back
 *       401. Inserting after that filter is what makes the established authentication survive to the
 *       authorisation filter.</li>
 * </ul>
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
 *       unresolvable and startup fails before a port is opened. That is the required behaviour: the previous
 *       migration attempt hardcoded this value, a <strong>High</strong>-severity defect this file closes. The
 *       key must be at least {@value #MINIMUM_SIGNING_KEY_BYTES} bytes when encoded as UTF-8.</dd>
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
 * </dl>
 *
 * <p>Two further keys are bound by {@code com.cardemo.security.JwtTokenProvider} and deliberately not
 * restated here: the token lifetime in seconds, and the issuer it stamps. The issuer is the one value both
 * sides must agree on, and both reach it through the same property key.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Build and unit-test with {@code ./mvnw -B -ntp clean verify}. This file compiles under
 * {@code -Xlint:all -Werror} with {@code failOnWarning} set, so any warning it introduced would fail the
 * build rather than be reported.
 *
 * <p>To run the application, supply {@value #SIGNING_KEY_VARIABLE} in the environment - the repository ships
 * {@code .env.example} documenting the variable with no value - and start the dependency stack with
 * {@code docker compose up -d}. Anything that exercises HTTP needs that variable present; there is no
 * developer-convenience default, by design.
 *
 * <p>To verify the policy by hand once the application is up: a sign-on {@code POST} succeeds without a
 * token; the same call to any other path returns 401 with a {@code WWW-Authenticate: Bearer} challenge; a
 * standard user's token on an administration path returns 403 rather than 401, because the caller is
 * authenticated but not entitled; and {@code /actuator/health}, {@code /actuator/info} and
 * {@code /actuator/prometheus} answer anonymously.
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
 *   <dd><strong>The remedy is fixed by agreement so that it is applied the same way every time: remove the
 *       duplicate from the {@code com.cardemo.security} package, because this class is the designated
 *       owner of both.</strong> Two decoders are worse than a startup failure when they happen not to
 *       collide, because they can verify against different keys.</dd>
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
 *   <dd>Both are anonymous by necessity - the image's health check issues a bare request with no
 *       {@code Authorization} header, and the scrape configuration carries no credential - so the five
 *       management paths permitted below, being the three exposed endpoints plus the two paths the health
 *       group definitions create, must stay anonymous unless both callers are changed with them.</dd>
 *   <dt>A session cookie appears in a response</dt>
 *   <dd>Something reintroduced a session-backed request cache or a stateful configurer. The null request
 *       cache configured here is what prevents it; see the statelessness section for why removing it is not
 *       a simplification.</dd>
 * </dl>
 *
 * <h2>Deferred hardening and residual risk</h2>
 *
 * <p>Each item is disclosed rather than silently omitted, per Clause F, and each is recorded in
 * {@code DECISION_LOG.md} so that none is an untracked deferral under Clause B:
 *
 * <ul>
 *   <li><strong>No transport security here.</strong> Termination is an infrastructure concern in this
 *       topology; a bearer token on a plaintext hop is replayable, so a real deployment must terminate TLS
 *       in front of the application. Severity Medium, deferred by scope.</li>
 *   <li><strong>No rate limiting.</strong> The sign-on path is anonymous by necessity and therefore
 *       brute-forceable; BCrypt's cost is the only throttle present. Severity Medium, deferred by scope.</li>
 *   <li><strong>No token revocation.</strong> Discussed above; bounded by the token lifetime. Severity
 *       Low.</li>
 *   <li><strong>Anonymous management endpoints.</strong> The metrics endpoint discloses the exact runtime
 *       build through its JVM meters. The health bodies are status-only and the info body is empty, so
 *       nothing else leaks. Severity Low; the alternative - authenticating the scrape - would require the
 *       scrape configuration and the image health check to carry a credential, which is a larger change than
 *       the disclosure warrants.</li>
 *   <li><strong>No user-enumeration difference, deliberately.</strong> The legacy screen distinguished
 *       {@code 'User not found. Try again ...'} at {@code app/cbl/COSGN00C.cbl:L247-L251} from
 *       {@code 'Wrong Password. Try again ...'} at {@code :L241-L246}. The REST surface does not differentiate
 *       externally; the distinction survives as a typed exception and a structured log without the
 *       credential. This is a labelled deviation from parity, severity Medium, owned by
 *       {@code com.cardemo.service.auth.AuthenticationService} and recorded in {@code DECISION_LOG.md}.</li>
 * </ul>
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
 *   <dt>High - the two unauthored controllers this policy already names</dt>
 *   <dd>{@code com.cardemo.controller.AuthController} and {@code com.cardemo.controller.AdminController} are
 *       <strong>{@code Not available}</strong> at this commit: no such file exists in the tree, so their base
 *       paths could not be read from code the way the other six were. The rules for
 *       {@value #PATH_SIGN_ON} and {@value #PATH_ADMIN} are therefore asserted contracts rather than
 *       verified facts. They are not guesses: the administration base path is fixed by the migration plan,
 *       and the sign-on namespace is the convention the tree states of itself in
 *       {@code src/main/java/com/cardemo/controller/BillingController.java:309}, which names
 *       {@code /api/menu} for the menu transactions and {@code /api/auth} for sign-on alongside its own
 *       {@code /api/billing}. <em>What is needed:</em> those two files, mounted at those two base paths. If
 *       either lands elsewhere, the symptom is unmistakable - sign-on returns 401, or the administration
 *       surface is reachable by a standard user - and the remediation is to make the two agree, by
 *       preference by moving the controller onto the convention the other six already follow. The other six
 *       controllers' twelve endpoints were read directly from their mappings, and twelve plus one plus four
 *       is exactly the seventeen the resource definitions declare.</dd>
 *   <dt>Medium - {@code EIBTRNID} has no citable locator</dt>
 *   <dd>The CICS transaction identifier usually described as the legacy per-request thread of identity is
 *       <strong>{@code Not available}</strong> in this corpus: it is supplied by the monitor, not declared in
 *       application source, and it has zero occurrences repository-wide at {@code 7756d89} - the complete
 *       field census of {@code app/cbl} being {@code EIBCALEN} and {@code EIBAID} only. No
 *       {@code app/...:Lnnn} locator is fabricated for it, because a false citation is itself an evidence
 *       defect. The citable per-request identity evidence used instead is {@code app/csd/CARDDEMO.CSD},
 *       {@code app/cbl/COSGN00C.cbl:L37} and {@code app/cbl/COCRDLIC.cbl:L295}. <em>What is needed:</em> a
 *       monitor definition this repository does not contain. It costs a citation, not a behaviour.</dd>
 *   <dt>Medium - the signing-key variable name</dt>
 *   <dd>This file's own specification named the variable {@code JWT_SIGNING_KEY}. The repository had already
 *       standardised on {@value #SIGNING_KEY_VARIABLE}: it is what {@code application.yml} indirects the
 *       property to, what {@code .env.example} ships empty and required, and what
 *       {@code com.cardemo.security.JwtTokenProvider} names in its own failure messages. Introducing a
 *       second name would be the duplication Clause C forbids and would half-configure the application in a
 *       way no single failure message could explain. The property key is unchanged; only the variable name
 *       follows the repository. <em>Remediation if the repository later standardises the other way:</em>
 *       rename in {@code application.yml}, in the three sibling profiles, in {@code .env.example} and in the
 *       two Java constants together, never one alone.</dd>
 *   <dt>Medium - no {@code java.time.Clock} bean exists</dt>
 *   <dd>{@code com.cardemo.security.JwtTokenProvider} and several services require a {@code Clock} by
 *       constructor, and <strong>no configuration class in the tree declares one</strong>. It is
 *       deliberately not declared here: this file's bean set is fixed at three, and a second declaration
 *       elsewhere would produce an ambiguous dependency at startup - a worse failure than the missing one,
 *       because it is order-dependent. <em>What is needed:</em> exactly one {@code Clock} bean, published by
 *       the configuration class that owns the observability and time surface.</dd>
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
 * </dl>
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

    // =============================================================================================
    // Configuration keys. Each spelling exists exactly once in this file, so that a property rename
    // is a one-line change and a failure message can always name the key it is complaining about.
    // =============================================================================================

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
     */
    private static final String SIGNING_KEY_VARIABLE = "JWT_SECRET";

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

    // =============================================================================================
    // Cryptographic invariants. These match com.cardemo.security.JwtTokenProvider exactly; a
    // divergence in either value would make every issued token unverifiable.
    // =============================================================================================

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

    // =============================================================================================
    // Request paths. One constant per CICS transaction group, named for what it serves rather than for
    // the transaction identifier, with the identifier and its CSD line number in the documentation.
    // =============================================================================================

    /** Sign-on, transaction {@code CC00} at {@code app/csd/CARDDEMO.CSD:L378} - {@code COSGN00C}. */
    private static final String PATH_SIGN_ON = "/api/auth/**";

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

    // =============================================================================================
    // Management paths. Exactly the three endpoints application.yml exposes, plus the two health group
    // paths its group definitions create. Listed as separate constants rather than as an array, so that
    // no mutable static state exists anywhere in this class.
    // =============================================================================================

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

    /** Metrics scrape target, requested every fifteen seconds by the monitoring stack. */
    private static final String PATH_PROMETHEUS = "/actuator/prometheus";

    // =============================================================================================
    // Bound state. Three immutable values, all supplied by constructor injection. There is no field
    // @Autowired, no setter, no static mutable field and no service lookup anywhere in this class.
    // =============================================================================================

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
     * @throws IllegalStateException if the signing key is absent, empty, whitespace only or too short; if
     *                               the issuer is absent or blank; or if the BCrypt cost is anything other
     *                               than the required value - in every case aborting startup, and in no case
     *                               reporting any part of the signing key
     */
    public SecurityConfig(
            @Value("${" + KEY_SIGNING_KEY + "}") final String signingKey,
            @Value("${" + KEY_ISSUER + "}") final String issuer,
            @Value("${" + KEY_BCRYPT_STRENGTH + "}") final int bcryptStrength) {
        this.verificationKey = verificationKeyFrom(signingKey);
        this.issuer = validatedIssuer(issuer);
        this.bcryptStrength = validatedBcryptStrength(bcryptStrength);
    }

    /**
     * Declares the one security filter chain: stateless, deny-by-default, and authorising exactly the
     * seventeen sourced CICS transactions.
     *
     * <h2>What each clause is for</h2>
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
     * </dl>
     *
     * <h2>Why the rules are in this order</h2>
     *
     * <p>Matchers are evaluated in declaration order and the first match wins, so the order is part of the
     * behaviour rather than a formatting choice. Non-overlapping paths come first, then the two
     * administrator rules, then the eleven ordinary business rules, then the deny-all. The one genuine
     * overlap - {@value #PATH_ADMIN} against nothing else, and {@value #PATH_MENU_ADMIN} against
     * {@value #PATH_MENU_MAIN} - is resolved by placing the administrator rules ahead of the business
     * rules. No rule depends on a set's iteration order, satisfying Rule 1 Clause A's determinism
     * requirement.
     *
     * <h2>Where the filter goes</h2>
     *
     * <p>{@code com.cardemo.security.JwtAuthenticationFilter} is inserted immediately after
     * {@code SecurityContextHolderFilter}. Earlier is not merely suboptimal but wrong: the context-holder
     * filter installs a fresh, empty security context at the start of every request, so an authentication
     * established before it runs is discarded and every authorised call fails with {@code 401}. The filter
     * is injected, never instantiated here - it is a {@code @Component} that owns its own construction, and
     * a second instance would double every token parse.
     *
     * <p>{@code com.cardemo.observability.CorrelationIdFilter} is deliberately <strong>not</strong> added.
     * It registers at the servlet container's highest precedence, ahead of the entire security chain, so
     * unauthenticated and rejected requests are already correlated. Adding it here would run it twice and
     * produce two correlation identifiers for one request.
     *
     * @param http                     the builder Spring Security supplies for this chain
     * @param jwtAuthenticationFilter  the token-validation filter, injected rather than constructed
     * @return the single chain governing every request this application serves
     * @throws Exception if the builder cannot assemble the chain, which is a configuration defect and
     *                   therefore correctly fatal at startup; the cause is propagated untouched rather than
     *                   wrapped, because Spring's own report names the offending configurer
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            final HttpSecurity http,
            final JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {

        // Every matcher below is built explicitly rather than from a bare pattern string. See the
        // "How requests are matched" section of this class's documentation for why that is deliberate.
        final PathPatternRequestMatcher.Builder path = PathPatternRequestMatcher.withDefaults();

        return http
                .csrf(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint())
                        .accessDeniedHandler(new BearerTokenAccessDeniedHandler()))
                .addFilterAfter(jwtAuthenticationFilter, SecurityContextHolderFilter.class)
                .authorizeHttpRequests(authorize -> authorize

                        // Container-internal error rendering, not a client request.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()

                        // Operational surface. Anonymous by necessity, read-only by method, and limited to
                        // exactly the endpoints application.yml exposes plus the two health group paths its
                        // group definitions create. The container health check and the metrics scrape both
                        // arrive without a credential, so a rule requiring one would report a permanently
                        // unhealthy container and a permanently absent metrics target.
                        .requestMatchers(
                                path.matcher(HttpMethod.GET, PATH_HEALTH),
                                path.matcher(HttpMethod.GET, PATH_HEALTH_LIVENESS),
                                path.matcher(HttpMethod.GET, PATH_HEALTH_READINESS),
                                path.matcher(HttpMethod.GET, PATH_INFO),
                                path.matcher(HttpMethod.GET, PATH_PROMETHEUS)).permitAll()

                        // CC00 - app/csd/CARDDEMO.CSD:L378 - app/cbl/COSGN00C.cbl. The only unauthenticated
                        // business operation, because it is the operation that establishes identity. Bounded
                        // to POST so no sign-on data can ever be placed in a query string.
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

    // =============================================================================================
    // Validation helpers. All private and all static, so the constructor cannot leak a partially
    // constructed instance and no subclass can weaken a check. Each one names the property it is
    // complaining about and the remedy; none of them reports a value that could be sensitive.
    // =============================================================================================

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
}
