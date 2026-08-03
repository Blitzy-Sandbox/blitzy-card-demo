/*
 * ******************************************************************
 * Program     : JwtAuthenticationFilter.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 security component
 * Function    : Authenticates each request from a bearer token, replacing COMMAREA propagation across XCTL.
 * Source      : app/cbl/COMEN01C.cbl:L149-L150 (identity MOVEs
 *               commented out - identity persists in the COMMAREA),
 *               :L152-L155 (XCTL + COMMAREA) @ 7756d89
 * Source      : app/cbl/COSGN00C.cbl:L65-L67 (DFHCOMMAREA /
 *               EIBCALEN), :L98-L102 (RETURN TRANSID ... COMMAREA)
 *               @ 7756d89
 * Source      : app/cpy/COCOM01Y.cpy:L29-L31 (CDEMO-PGM-CONTEXT - no
 *               equivalent), :L43-L44 (map state - no equivalent)
 *               @ 7756d89
 * Source      : app/csd/CARDDEMO.CSD:L378 (DEFINE TRANSACTION(CC00) -
 *               the only unauthenticated operation), :L388-L391
 *               (CDV1 -> COCRDSEC, no source, no endpoint invented)
 *               @ 7756d89
 * Note        : EIBTRNID is Not available in this corpus (zero
 *               occurrences repository-wide at 7756d89); it is
 *               CICS-monitor-supplied. Severity Medium.
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
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns a presented bearer token into a request-local {@link SecurityContext}, and nothing else.
 *
 * <p>This is the Java replacement for COMMAREA propagation across {@code EXEC CICS XCTL}. In the legacy
 * system identity travelled inside the COMMAREA on every program transfer: {@code app/cbl/COMEN01C.cbl}
 * issues {@code XCTL PROGRAM(...) COMMAREA(CARDDEMO-COMMAREA)} at {@code :L152-L155}, and
 * {@code app/cbl/COSGN00C.cbl:L98-L102} returns to the terminal with
 * {@code RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA) LENGTH(LENGTH OF CARDDEMO-COMMAREA)}. The
 * COMMAREA is declared in LINKAGE as a variable-length byte array at {@code app/cbl/COSGN00C.cbl:L65-L67},
 * {@code 05 LK-COMMAREA PIC X(01) OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN}. Per the transformation
 * rule, {@code RETURN TRANSID ... COMMAREA} becomes stateless REST plus JWT claims, so there is
 * <strong>no server-side session state</strong>; pagination state moves to request parameters and response
 * metadata rather than to a session.
 *
 * <h2>Why a self-describing bearer token is the faithful translation</h2>
 *
 * <p>The decisive evidence sits immediately above that transfer. At {@code app/cbl/COMEN01C.cbl:L147-L148}
 * the menu program moves the originating transaction and program into the COMMAREA, but the two identity
 * moves at {@code :L149-L150} are <strong>commented out</strong>:
 *
 * <pre>
 * L147                   MOVE WS-TRANID    TO CDEMO-FROM-TRANID
 * L148                   MOVE WS-PGMNAME   TO CDEMO-FROM-PROGRAM
 * L149       *            MOVE WS-USER-ID   TO CDEMO-USER-ID
 * L150       *            MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
 * </pre>
 *
 * <p>So the legacy system did <strong>not</strong> re-derive identity, and did <strong>not</strong> re-read
 * the {@code USRSEC} security file, on each transfer. Identity was established exactly once, at sign-on
 * ({@code app/cbl/COSGN00C.cbl:L226-L227}, where the same two moves are live rather than commented), and
 * thereafter simply rode along in the COMMAREA. That is precisely the semantic of a self-describing signed
 * bearer token, which is why one is used here.
 *
 * <h2>COMMAREA fields with no Java counterpart</h2>
 *
 * <p>The COMMAREA carried routing and screen state as well as identity. Those fields are deliberately
 * <em>not</em> reproduced as claims, and no claim is read for them:
 *
 * <table>
 *   <caption>COMMAREA fields deliberately without a Java counterpart</caption>
 *   <tr><th>COMMAREA field and width</th><th>Locator</th><th>Disposition</th></tr>
 *   <tr><td>{@code CDEMO-FROM-TRANID PIC X(04)}, {@code CDEMO-FROM-PROGRAM PIC X(08)},
 *           {@code CDEMO-TO-TRANID PIC X(04)}, {@code CDEMO-TO-PROGRAM PIC X(08)}</td>
 *       <td>{@code app/cpy/COCOM01Y.cpy:L21-L24}</td>
 *       <td><strong>No equivalent</strong> - routing is URL-based</td></tr>
 *   <tr><td>{@code CDEMO-PGM-CONTEXT PIC 9(01)} with {@code 88 CDEMO-PGM-ENTER VALUE 0} and
 *           {@code 88 CDEMO-PGM-REENTER VALUE 1}</td>
 *       <td>{@code app/cpy/COCOM01Y.cpy:L29-L31}</td>
 *       <td><strong>No equivalent</strong> - the pseudo-conversational enter-versus-re-enter flag
 *           collapses into stateless request handling</td></tr>
 *   <tr><td>{@code CDEMO-LAST-MAP PIC X(7)}, {@code CDEMO-LAST-MAPSET PIC X(7)} - note the width is
 *           seven, not eight</td>
 *       <td>{@code app/cpy/COCOM01Y.cpy:L43-L44}</td>
 *       <td><strong>No equivalent</strong> - no screen state is retained</td></tr>
 *   </table>
 *
 * <h2>The claim contract</h2>
 *
 * <p>Exactly two claims are read, and both come from the general-information group of
 * {@code app/cpy/COCOM01Y.cpy}:
 *
 * <ul>
 *   <li><strong>Subject</strong> - {@code CDEMO-USER-ID PIC X(08)} at {@code app/cpy/COCOM01Y.cpy:L25},
 *       read through {@link JwtTokenProvider#extractUserId(Jwt)} and used as the authentication
 *       principal.</li>
 *   <li><strong>Role</strong> - {@code CDEMO-USER-TYPE PIC X(01)} at {@code app/cpy/COCOM01Y.cpy:L26}
 *       with {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} at {@code :L27} and
 *       {@code 88 CDEMO-USRTYP-USER VALUE 'U'} at {@code :L28}. It is read through
 *       {@link JwtTokenProvider#extractUserType(Jwt)} and mapped to exactly one authority by
 *       {@link JwtTokenProvider#authorityFor(UserType)}: {@code 'A'} becomes
 *       {@value JwtTokenProvider#ADMIN_AUTHORITY} and {@code 'U'} becomes
 *       {@value JwtTokenProvider#USER_AUTHORITY}.</li>
 *   </ul>
 *
 * <p>The claim <em>name</em> is never spelled here. It is
 * {@link JwtTokenProvider#ROLE_CLAIM_NAME}, and it is reached only through that constant, shared in intent
 * with {@link JwtTokenProvider} and {@code com.cardemo.config.SecurityConfig}. A drift between the name
 * used to issue and the name used to read is <strong>High</strong> severity: the token still verifies, the
 * authority set comes out empty, and the symptom is a silent authorisation denial rather than an error.
 * Referencing the issuer's own constant is what makes the drift impossible rather than merely unlikely.
 *
 * <p>No routing, re-entry or map-state claim exists, so none is read. Authority is limited to exactly one
 * of the two values above: there is no wildcard authority and <strong>no default role on a missing or
 * unrecognised claim</strong>. {@link JwtTokenProvider#extractUserType(Jwt)} treats an absent or
 * unrecognised claim as a hard failure, and this filter leaves the context empty rather than substituting a
 * role, which is Rule 1 Clause A's prohibition on unsafe defaults and Clause D's least-privilege
 * requirement applied at the point they matter most.
 *
 * <h2>Authorities come from the role claim: no per-request database read</h2>
 *
 * <p>This filter performs <strong>no repository lookup and no {@code UserDetailsService} call</strong> per
 * request, and consequently has <strong>no dependency on {@link CardDemoUserDetailsService}</strong>. That
 * collaborator loads and verifies a user at sign-on; it is deliberately absent from this constructor. Three
 * reasons, each independently sufficient:
 *
 * <ol>
 *   <li><strong>Statelessness.</strong> The transformation rule is that
 *       {@code RETURN TRANSID ... COMMAREA} becomes stateless REST plus JWT claims with no server-side
 *       session state. Re-deriving identity per request would reintroduce exactly the server-side
 *       dependency the token exists to remove.</li>
 *   <li><strong>Parity.</strong> {@code app/cbl/COMEN01C.cbl:L149-L150} proves the legacy system did not
 *       re-read {@code USRSEC} on each {@code XCTL}. Re-reading per request would be a behaviour change,
 *       and behaviour changes are forbidden because parity is the acceptance contract.</li>
 *   <li><strong>Efficiency.</strong> Rule 1 Clause A requires that obvious inefficiencies be avoided. A
 *       database round trip on every authenticated request, to recover data the signed token already
 *       carries verifiably, is the textbook example of one.</li>
 *   </ol>
 *
 * <p>The accepted consequence, stated honestly: a role change takes effect only when the caller's current
 * token expires, bounded by the configured token lifetime that {@link JwtTokenProvider} owns. Severity
 * <strong>Low</strong>, and it is the legacy behaviour rather than a regression - the COMMAREA carried the
 * user type established at sign-on for the whole of the signed-on session too. Remediation, should a
 * deployment ever need immediate revocation, is a shorter lifetime or a revocation list in
 * {@code com.cardemo.config.SecurityConfig}, not a lookup here.
 *
 * <h2>Extending {@link OncePerRequestFilter}: the double-registration defence</h2>
 *
 * <p>A Spring bean that implements {@code jakarta.servlet.Filter} is auto-registered into the servlet
 * filter chain, and {@code com.cardemo.config.SecurityConfig} may <em>also</em> insert this same bean into
 * the Spring Security chain with {@code addFilterBefore(...)}. Naively that runs the filter twice per
 * request: doubled log lines, and - worst - a second authentication attempt against an already-populated
 * context. Severity <strong>High</strong>.
 *
 * <p>Extending {@link OncePerRequestFilter} is the defence, and it is mandatory rather than a preference:
 * it records an already-filtered request attribute derived from the filter name and skips any second
 * invocation for the same request, so a single bean reachable through two chains still executes exactly
 * once. {@code com.cardemo.observability.CorrelationIdFilter} uses the same defence, so the two filters are
 * consistent. Two further properties make the outcome robust regardless: this filter writes
 * <strong>no response header at all</strong>, so no header can be duplicated - the "set, do not add"
 * requirement is satisfied in its strongest form, by writing nothing - and it short-circuits cleanly when
 * the context already carries an authentication rather than replacing it.
 *
 * <h2>Ordering in the chain</h2>
 *
 * <p>The intended chain is {@code com.cardemo.observability.CorrelationIdFilter}, then this filter, then
 * role-based authorisation. {@code com.cardemo.config.SecurityConfig} <strong>composes</strong> the chain
 * and <strong>owns</strong> the ordering; this class only makes itself orderable into that position. It
 * composes no chain, declares no authorisation rule and configures no session policy.
 *
 * <p>{@link #ORDER} is published as a constant so that configuration can align without guesswork, and its
 * value is load bearing. Spring Boot registers the entire Spring Security chain at
 * {@link SecurityProperties#DEFAULT_FILTER_ORDER}, which is {@code -100}, and the authoritative execution
 * site for this filter is <em>inside</em> that chain, where {@code SecurityConfig} inserts it. This class
 * therefore orders its own auto-registered servlet-level copy <em>after</em> the security chain rather than
 * before it. The reason is specific: Spring Security's {@code SecurityContextHolderFilter} runs at the head
 * of the security chain and installs a context supplied by its repository, which under a stateless policy
 * is empty. Had the servlet-level copy authenticated first, it would have set the already-filtered
 * attribute, the in-chain copy would have been skipped, and the authentication it established would then
 * have been replaced by that empty context - producing a 401 on <em>every</em> request while looking like a
 * token defect. Ordering after {@code -100} makes the in-chain copy the one that executes and the
 * servlet-level copy the one that is skipped. Severity of getting this backwards: <strong>High</strong>.
 *
 * <p>The value still sits well after {@code CorrelationIdFilter}, which runs at the highest precedence
 * available. That ordering is required, not incidental: a correlation identifier must <em>already</em> be
 * in the diagnostic context when this filter declines to authenticate, so that the resulting 401 or 403 is
 * traceable.
 *
 * <h2>Diagnostic context is read, never written</h2>
 *
 * <p>{@code com.cardemo.observability.CorrelationIdFilter} owns the diagnostic context and places the
 * correlation identifier under {@code correlationId}, alongside {@code traceId} and {@code spanId} - the
 * three exact spellings that {@code src/main/resources/logback-spring.xml} consumes. This filter
 * <strong>adds, renames, overwrites and removes none of them</strong>, and never calls a blanket context
 * clear, which would destroy context owned by that sibling and by the tracing infrastructure. It
 * contributes no diagnostic key of its own: every log record it emits inherits correlation from the sibling
 * filter, which is exactly why the ordering above matters. There is therefore no diagnostic key for this
 * class to remove in a {@code finally} block, because it adds none.
 *
 * <h2>The four input paths</h2>
 *
 * <p>The {@code Authorization} header is untrusted input, and so is the token inside it. Rule 1 Clause A
 * requires inputs be treated as untrusted with no unsafe defaults, and Clause B requires that null and
 * empty cases be handled explicitly. All four paths are enumerated, mutually exclusive and separately
 * observable:
 *
 * <ol>
 *   <li><strong>Absent</strong> - no {@code Authorization} header, a blank one, or one that is not a
 *       {@code Bearer} credential. Nothing is authenticated, nothing is thrown, and the chain continues so
 *       that the authorisation layer produces the 401 or 403. This is the path that keeps sign-on
 *       reachable: {@code CC00} at {@code app/csd/CARDDEMO.CSD:L378} is the only unauthenticated
 *       operation, and it is reached with no credential presented.</li>
 *   <li><strong>Malformed</strong> - present and well-shaped but not decodable, or its signature does not
 *       verify. Recognised as a {@link JwtException} that is not a {@link JwtValidationException}. The
 *       context is left empty and the chain continues.</li>
 *   <li><strong>Expired</strong> - decoded with a valid signature but rejected by a claim validator, of
 *       which lifetime is the common case. Recognised as a {@link JwtValidationException}, which is a
 *       distinct subtype, so this outcome is distinguished from malformed <em>structurally</em> rather
 *       than by inspecting message text.</li>
 *   <li><strong>Valid</strong> - decoded, verified, and carrying both required claims. A request-local
 *       {@link SecurityContext} is populated with the subject as principal and exactly one authority.</li>
 *   </ol>
 *
 * <p>A fifth condition is folded into the second and third rather than left implicit: a token that decodes
 * and verifies but whose subject or role claim is absent or unrecognised. {@link JwtTokenProvider} reports
 * that as an {@link IllegalArgumentException}, which is caught here, logged as its own outcome, and leaves
 * the context empty.
 *
 * <p>Further untrusted-input hygiene: the header length is bounded <em>before</em> any parsing, so an
 * implausibly long value is rejected rather than fed to the decoder; the scheme is matched
 * case-insensitively per RFC 7235 by a single pre-compiled anchored pattern whose quantifiers are neither
 * nested nor overlapping, so it cannot backtrack catastrophically; and surrounding whitespace is stripped
 * explicitly rather than tolerated by a loose pattern.
 *
 * <h2>Zero server-side state, and mandatory thread-local cleanup</h2>
 *
 * <p>This class holds no state. It never reaches for the servlet session - it neither retrieves nor
 * creates one, and it reads and writes no session attribute - it persists no security context through any
 * repository, it declares no thread-local of its own, and it keeps no field, static map or cache holding
 * request data. Its only two fields are final collaborators. The stateless session policy and the
 * request-forgery configuration are owned by {@code com.cardemo.config.SecurityConfig} and are neither
 * restated nor contradicted here.
 *
 * <p>The one piece of thread-local state it touches, the security context, is cleared in a
 * {@code finally} block unconditionally, on every path including the exception path. This is not
 * defensive decoration. Servlet threads are pooled, so a context left behind would be inherited by
 * whichever request landed on that thread next, and that request would run as the previous caller.
 * Severity <strong>Blocker</strong>. Note that the cleanup runs <em>after</em> the downstream chain has
 * completed, which is why the ordering inside {@link #doFilterInternal} is itself load bearing: clearing
 * before the chain would destroy the authentication it had just established.
 *
 * <h2>Secrecy</h2>
 *
 * <p>Rule 1 Clause D requires no secrets in code, logs, tests or configuration, and this filter reads the
 * single most sensitive request header there is. It therefore never logs, echoes, serialises or places into
 * an exception message, a metric tag or a diagnostic value: the presented token whole or truncated - a
 * truncated prefix is still a secret fragment - the {@code Authorization} header, the bearer credential,
 * the signing key, a stored password hash, a presented password, or any claim carrying personal data. Not
 * at any level, including trace and debug. Every log record it emits is a compile-time constant string with
 * <strong>no interpolated request-derived value whatsoever</strong>, which is the strongest available
 * guarantee: not even the request method or URI is logged, because a URI in this application carries
 * account identifiers and card numbers. The one interpolated value anywhere is a framework exception's
 * simple class name, which is compile-time bounded and carries no credential.
 *
 * <p>That single interpolation is how Clause B's "preserve root cause" and Clause D's "no secrets" are
 * reconciled. A validation failure is neither swallowed nor rethrown with the offending value attached:
 * the diagnostic category survives as a type name, the outcome is recorded distinguishably, and the
 * credential never reaches a sink. Interpolating a framework message verbatim would risk both a secret
 * fragment and, in any sink storing one event per line, a forged record via an embedded carriage return.
 * The masking rules in {@code logback-spring.xml} are a second line of defence; not producing the value is
 * the first, and that is this filter's job.
 *
 * <p>The principal placed in the context is the subject identifier only, and the credentials slot is left
 * null deliberately, so the presented token never enters the security context. No authentication details
 * object is attached either: the standard web details source captures the caller's network address and
 * touches the session, and both are unwanted here.
 *
 * <h2>Observability</h2>
 *
 * <p>Outcome-level logging plus the inherited correlation identifier is the whole of this filter's
 * instrumentation, and that is a deliberate choice rather than an omission. The authentication-attempts
 * counter is one of exactly four instruments and is owned by
 * {@code com.cardemo.observability.MetricsConfig}. <strong>This filter deliberately does not emit
 * it.</strong> That instrument's legacy counterpart is {@code app/cbl/COSGN00C.cbl}, the sign-on program,
 * and its outcome dimension is credential-oriented - credentials accepted, or credentials rejected.
 * Verifying a bearer token on a subsequent request is a different population entirely: incrementing the
 * counter here would double-count against {@code com.cardemo.service.auth.AuthenticationService}, which
 * owns sign-on, and would inflate the series by the whole volume of authenticated traffic, distorting the
 * measured performance baseline. No fifth instrument is registered and no per-subject tag is emitted, a
 * tag by user identifier being both an identity leak and an unbounded-cardinality defect.
 *
 * <h2>Configuration, defaults and ownership</h2>
 *
 * <p>This class owns <strong>no</strong> configuration property and reads none. It reads nothing from the
 * process environment and nothing from a system property, so it makes no environment-specific assumption
 * and nothing about it varies between profiles. It also relies on no default charset, locale or time zone,
 * and hardcodes no host, port or filesystem path. Its two collaborators carry everything configurable:
 *
 * <ul>
 *   <li>The {@link JwtDecoder} - the symmetric-HMAC decoder built from the externalised signing key - is
 *       <strong>injected, never constructed</strong>. This class defines no decoder bean and no password
 *       encoder bean; {@code com.cardemo.config.SecurityConfig} owns both. A duplicate decoder is
 *       <strong>High</strong> severity, because it either fails startup on an ambiguous bean or silently
 *       verifies against a different key. Correspondingly, the resource-server key-location properties are
 *       deliberately unset, because the decoder is built in Java from the symmetric key; requesting them
 *       would add dead configuration.</li>
 *   <li>{@link JwtTokenProvider} owns the claim names, the authority spellings and the token lifetime.</li>
 * </ul>
 *
 * <p>Building and verifying this component needs no special step: {@code ./mvnw -B -ntp clean verify}
 * compiles it under {@code -Xlint:all -Werror} and runs its unit suite,
 * {@code com.cardemo.unit.security.JwtAuthenticationFilterTest}, which covers all four input paths, the
 * cleanup on every path including the path where the downstream chain throws, the single-execution
 * guarantee, and the rule that no default role is ever substituted. That suite needs no database, no
 * container and no running service, precisely because this filter reaches none of them.
 *
 * <h2>Troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>A 401 on every request although the token looks valid.</strong> Most often a role-claim
 *       name drift, or a decoder built from a different signing key than the issuer used. Check that both
 *       sides reach the claim name through {@link JwtTokenProvider#ROLE_CLAIM_NAME} and that exactly one
 *       {@link JwtDecoder} bean exists. Third possibility, if this filter is not inserted inside the
 *       Spring Security chain: see the ordering section above, where an authentication established before
 *       the chain is replaced by an empty context.</li>
 *   <li><strong>Doubled log lines for one request.</strong> The filter was registered twice and
 *       {@link OncePerRequestFilter} was not extended. The resolution is in
 *       {@code com.cardemo.config.SecurityConfig}, not here.</li>
 *   <li><strong>A 401 with no log record at all.</strong> The request never reached this filter; look
 *       earlier in the chain.</li>
 *   <li><strong>An expired-token outcome immediately after issue.</strong> Clock skew between issuer and
 *       verifier, not a defect in this filter, which performs no time arithmetic of its own.</li>
 *   </ul>
 *
 * <h2>Information not available</h2>
 *
 * <p>Two disclosures, per Rule 1 Clause F:
 *
 * <ul>
 *   <li>{@code EIBTRNID}, the CICS transaction identifier normally described as the legacy per-request
 *       thread of identity, is <strong>Not available</strong> in this corpus: it has zero occurrences
 *       repository-wide at {@code 7756d89}, the frozen corpus referencing only {@code EIBCALEN} and
 *       {@code EIBAID}. It is supplied by the CICS monitor, so no source locator for it exists and none is
 *       fabricated here. What would be needed is a CICS monitor definition that this repository does not
 *       contain. Severity <strong>Medium</strong> - it costs a citation, not a behaviour. The citable
 *       per-request identity evidence used instead is
 *       {@code app/cbl/COSGN00C.cbl:L37} ({@code WS-TRANID PIC X(04) VALUE 'CC00'}) together with the
 *       transaction definitions in {@code app/csd/CARDDEMO.CSD}.</li>
 *   <li><strong>Withdrawn: {@code com.cardemo.config.SecurityConfig} is present.</strong> An earlier
 *       revision of this bullet recorded it as unavailable, said {@code src/main/java/com/cardemo/config}
 *       contained no such file, and said no {@link JwtDecoder} bean was declared anywhere in the tree.
 *       All three are false and are withdrawn. That class publishes exactly one symmetric-HMAC
 *       {@code JwtDecoder} bean built on {@code NimbusJwtDecoder}, composes the
 *       {@code SecurityFilterChain} that inserts this filter, and declares the stateless session policy,
 *       the sign-on exemption and the administrator-only rule for the administration paths, binding every
 *       authorisation rule to {@code JwtTokenProvider.ADMIN_AUTHORITY} and
 *       {@code JwtTokenProvider.USER_AUTHORITY} rather than to its own literals. The decoder construction,
 *       the filter insertion point, the stateless policy and the authorisation rules described above are
 *       therefore facts verified against code rather than contracts asserted from the specification.</li>
 *   </ul>
 *
 * <p>Nothing is invented for the eighteenth transaction definition. {@code CDV1} at
 * {@code app/csd/CARDDEMO.CSD:L388-L391} names {@code COCRDSEC}, which has no source anywhere in the
 * repository, so no endpoint, role or authorisation rule is created for it. The authorisation surface is
 * the seventeen sourced transactions and nothing more.
 *
 * <p>This class is thread-safe and immutable: both fields are final references to thread-safe
 * collaborators, and all per-request data lives in method locals or in the thread-local security context
 * that is cleared before the thread is released.
 */
@Component
@Order(JwtAuthenticationFilter.ORDER)
public final class JwtAuthenticationFilter extends OncePerRequestFilter {

    // =============================================================================================
    // Published contract. These three values exist so that com.cardemo.config.SecurityConfig and the
    // unit suite can align to them by reference rather than by repeating a literal.
    // =============================================================================================

    /**
     * Servlet-level precedence of this filter, published so that configuration need not guess it.
     *
     * <p>The value is {@link SecurityProperties#DEFAULT_FILTER_ORDER} plus ten, which places the
     * auto-registered servlet-level copy of this filter <em>after</em> the whole Spring Security chain.
     * That direction is deliberate and is explained in the class documentation: the authoritative
     * execution site is inside the security chain, and ordering the servlet-level copy ahead of it would
     * let {@code SecurityContextHolderFilter} replace the established authentication with an empty
     * context, producing a 401 on every request.
     *
     * <p>It remains far behind {@code com.cardemo.observability.CorrelationIdFilter}, which runs at the
     * highest precedence available, so a correlation identifier is always in the diagnostic context before
     * this filter can decline a request.
     */
    public static final int ORDER = SecurityProperties.DEFAULT_FILTER_ORDER + 10;

    /**
     * The single authentication scheme this filter accepts, per RFC 6750.
     *
     * <p>Matched case-insensitively as RFC 7235 requires of a scheme token. The comparison is
     * ASCII-only and performs no case conversion of its own, so no locale can influence whether a header
     * is recognised.
     */
    public static final String BEARER_SCHEME = "Bearer";

    /**
     * Upper bound, in characters, on an {@code Authorization} header this filter is willing to parse.
     *
     * <p>Enforced <em>before</em> any parsing so that an implausibly long value is discarded rather than
     * handed to the decoder. A compact token carrying this application's two claims runs to a few hundred
     * characters, so the bound is generous by an order of magnitude while still being a bound.
     */
    public static final int MAX_AUTHORIZATION_HEADER_LENGTH = 4096;

    // =============================================================================================
    // Internals. Every static below is final and immutable; this class holds no mutable global state.
    // =============================================================================================

    /** Logger for outcome records. Every message it receives is a constant with no credential in it. */
    private static final Logger LOG = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    /**
     * Recognises a bearer credential and captures it, compiled exactly once at class initialisation.
     *
     * <p>The expression is anchored at both ends and its two quantifiers are neither nested nor applied to
     * overlapping character sets, so matching is linear in the header length and cannot backtrack
     * catastrophically. {@link Pattern#CASE_INSENSITIVE} is applied without
     * {@code Pattern#UNICODE_CASE}, keeping the fold ASCII-only and therefore locale-independent.
     *
     * <p>The credential class admits the base64url alphabet and the dot separator only. This is a cheap
     * shape and charset guard rather than a parser: a value that passes it is still fully decoded and
     * verified by the injected {@link JwtDecoder}, which is the only component that decides whether a
     * token is genuine.
     */
    private static final Pattern BEARER_CREDENTIAL =
            Pattern.compile("^" + BEARER_SCHEME + "[ \\t]+([A-Za-z0-9._-]+)$", Pattern.CASE_INSENSITIVE);

    /** Index of the capturing group in {@link #BEARER_CREDENTIAL} that holds the credential. */
    private static final int CREDENTIAL_GROUP = 1;

    /** Decodes and verifies a presented credential. Injected, never constructed here. */
    private final JwtDecoder jwtDecoder;

    /** Supplies the claim contract: subject extraction, role extraction and authority mapping. */
    private final JwtTokenProvider tokenProvider;

    /**
     * Creates the filter from its two collaborators.
     *
     * <p>Constructor injection only: there is no field or setter injection, no service locator and no
     * property read, so the filter cannot be constructed in a partially configured state. Neither
     * collaborator is created here - in particular this class defines no {@link JwtDecoder} bean and no
     * password encoder bean, both of which are owned by {@code com.cardemo.config.SecurityConfig}.
     *
     * @param jwtDecoder    the decoder that verifies a presented credential's signature and claims; must
     *                      not be {@code null}. Exactly one such bean is expected to exist
     * @param tokenProvider the issuer-side component whose claim names and authority spellings this filter
     *                      reads back, so that the two cannot drift; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public JwtAuthenticationFilter(final JwtDecoder jwtDecoder, final JwtTokenProvider tokenProvider) {
        this.jwtDecoder = Objects.requireNonNull(jwtDecoder, "jwtDecoder must not be null");
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider must not be null");
    }

    /**
     * Authenticates the request from its bearer credential, if one is presented, then continues the chain.
     *
     * <p>Runs exactly once per request dispatch however many times this bean is registered, because
     * {@link OncePerRequestFilter} records an already-filtered attribute for the request.
     *
     * <p>Side effects, and only these: on the valid path a request-local {@link SecurityContext} carrying
     * the subject and exactly one role authority is installed for the duration of the downstream chain, and
     * on <strong>every</strong> path that context is cleared again before this method returns. No session
     * is touched, no request attribute is added, no response header is written and no request or response
     * body is read.
     *
     * <p>The ordering of the two statements inside the {@code try} and the placement of the cleanup in
     * {@code finally} are load bearing. The cleanup must run after the downstream chain has completed:
     * clearing before it would destroy the authentication just established, and not clearing at all would
     * leave the caller's identity on a pooled servlet thread for the next request to inherit, which is a
     * Blocker-severity defect. The {@code finally} also covers the case where the downstream chain throws.
     *
     * <p>Error modes: this method never rejects a request itself and never throws on account of a
     * credential. An absent, malformed or expired credential leaves the context empty and lets the
     * authorisation layer decide, which is what keeps the sign-on operation reachable with no credential at
     * all. Anything the downstream chain throws propagates unchanged, with the root cause intact.
     *
     * @param request     the request whose {@code Authorization} header is inspected; never {@code null}
     * @param response    the response, passed downstream untouched; never {@code null}
     * @param filterChain the remainder of the chain, always invoked exactly once; never {@code null}
     * @throws ServletException if the downstream chain raises one
     * @throws IOException      if the downstream chain raises one
     */
    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
            final FilterChain filterChain) throws ServletException, IOException {
        try {
            authenticate(request);
            filterChain.doFilter(request, response);
        } finally {
            // Unconditional, on every path including the exception path: servlet threads are pooled, and a
            // context left behind would be inherited by the next request to land on this thread.
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * Resolves the request to one of the four outcomes, populating the security context only when valid.
     *
     * <p>The outcomes are mutually exclusive and each is recorded exactly once, so a request produces one
     * outcome record and never two. Nothing is thrown: an unusable credential is an expected client
     * condition rather than a server fault.
     *
     * @param request the request to authenticate; never {@code null}
     */
    private void authenticate(final HttpServletRequest request) {
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            // Short-circuit rather than authenticate a second time over an already-populated context. This
            // is the state a second registration of this bean would otherwise reach.
            LOG.debug("Request already carries an authentication; no bearer credential is examined.");
            return;
        }

        final String credential = bearerCredential(request);
        if (credential == null) {
            // Paths 1 and 2. The specific outcome was already recorded by bearerCredential, so nothing is
            // logged again here; the context stays empty and the authorisation layer produces the status.
            return;
        }

        try {
            final Jwt verified = jwtDecoder.decode(credential);
            final UserType userType = tokenProvider.extractUserType(verified);
            final String userId = tokenProvider.extractUserId(verified);
            authenticateAs(userId, tokenProvider.authorityFor(userType));
            // Path 4. Neither the subject nor the authority is logged: the subject identifies a person and
            // correlation is already in the diagnostic context for anyone tracing this request.
            LOG.debug("Bearer credential accepted; the request is authenticated with one role authority.");
        } catch (final JwtValidationException rejectedClaims) {
            // Path 3. A distinct subtype, so expiry and its sibling claim rejections are distinguished from
            // an undecodable credential structurally rather than by reading message text. Only the
            // exception's type name is recorded - never its message, which could carry claim values.
            LOG.debug("Bearer credential verified but rejected by claim validation ({}); "
                    + "the request continues unauthenticated.", rejectedClaims.getClass().getSimpleName());
        } catch (final JwtException undecodable) {
            // Path 2. Not decodable, or the signature did not verify. Anomalous rather than routine, so it
            // is recorded at a level an operator sees by default.
            LOG.warn("Bearer credential could not be decoded or its signature did not verify ({}); "
                    + "the request continues unauthenticated.", undecodable.getClass().getSimpleName());
        } catch (final IllegalArgumentException unusableClaims) {
            // Decoded and verified, but missing the subject or carrying no recognised role. No role is
            // substituted: an unreadable role must never be defaulted, in either direction.
            LOG.warn("Bearer credential decoded but carries no usable subject or role claim ({}); "
                    + "the request continues unauthenticated.", unusableClaims.getClass().getSimpleName());
        }
    }

    /**
     * Installs a request-local security context for the given subject and single authority.
     *
     * <p>A fresh empty context is created rather than mutating whatever the holder currently returns, so
     * nothing shared is modified. The credentials slot is left {@code null} deliberately: the presented
     * credential must never enter the security context, which is also why the token-bearing authentication
     * type from the resource-server module is not used here. No authentication details are attached either,
     * because the standard web details source captures the caller's network address and touches the
     * session.
     *
     * @param userId    the subject claim, used as the authentication principal; never {@code null} or blank
     * @param authority the single granted authority, exactly one of the two the claim contract allows;
     *                  never {@code null}
     */
    private static void authenticateAs(final String userId, final String authority) {
        final UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken
                .authenticated(userId, null, List.of(new SimpleGrantedAuthority(authority)));
        final SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    /**
     * Extracts the bearer credential from the request, or returns {@code null} when there is none to use.
     *
     * <p>Every rejection is recorded here, distinguishably, so that the caller need not log again. An
     * absent or non-bearer header is routine and recorded at debug level; an over-long header or a
     * malformed credential is anomalous and recorded at warning level. No branch echoes the header or any
     * part of it, which rules out both credential disclosure and log forging through an embedded control
     * character.
     *
     * @param request the request whose {@code Authorization} header is read; never {@code null}
     * @return the captured credential, or {@code null} if no usable bearer credential was presented
     */
    private static String bearerCredential(final HttpServletRequest request) {
        final String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.isBlank()) {
            // Path 1, and the ordinary case for the sign-on operation, which presents no credential.
            LOG.debug("No Authorization header presented; the request continues unauthenticated.");
            return null;
        }
        if (header.length() > MAX_AUTHORIZATION_HEADER_LENGTH) {
            // Bounded before parsing: an over-long value is discarded rather than handed to the decoder.
            LOG.warn("Authorization header exceeds the permitted length bound; rejected before parsing.");
            return null;
        }

        // Locale-independent by construction: strip removes surrounding whitespace without case folding.
        final String presented = header.strip();
        if (!isBearerScheme(presented)) {
            // Path 1. Some other authentication scheme, which this filter does not implement and must not
            // guess at. The absence of a bearer credential never grants access.
            LOG.debug("Authorization header does not use the bearer scheme; "
                    + "the request continues unauthenticated.");
            return null;
        }

        final Matcher matcher = BEARER_CREDENTIAL.matcher(presented);
        if (!matcher.matches()) {
            // Path 2. The bearer scheme with an empty or ill-shaped credential.
            LOG.warn("Bearer Authorization header presented with no well-formed credential; "
                    + "the request continues unauthenticated.");
            return null;
        }
        return matcher.group(CREDENTIAL_GROUP);
    }

    /**
     * Reports whether the header uses the bearer scheme, without converting the case of anything.
     *
     * <p>{@link String#regionMatches(boolean, int, String, int, int)} compares the scheme token
     * character by character, so no locale participates and no intermediate string is allocated. A header
     * consisting of the bare scheme with no credential is accepted here and rejected a moment later by the
     * credential pattern, which keeps "not our scheme" and "our scheme, unusable credential" as two
     * separate observable outcomes rather than one.
     *
     * @param presented the stripped header value; never {@code null}
     * @return {@code true} if the value begins with the bearer scheme as a complete token
     */
    private static boolean isBearerScheme(final String presented) {
        if (!presented.regionMatches(true, 0, BEARER_SCHEME, 0, BEARER_SCHEME.length())) {
            return false;
        }
        if (presented.length() == BEARER_SCHEME.length()) {
            return true;
        }
        final char separator = presented.charAt(BEARER_SCHEME.length());
        return separator == ' ' || separator == '\t';
    }
}
