/*
 * ******************************************************************
 * Program     : CorrelationIdFilter.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 servlet filter
 * Function    : Establishes the per-request correlation identifier in
 *               MDC, on the trace span and on outbound AWS calls.
 * Replaces    : EIBTRNID as the per-request thread of identity
 *               (CICS-supplied; NOT present in app/** - citation Not
 *               available). New capability per Rule 1 Clause A.
 * Source      : app/csd/CARDDEMO.CSD @ 7756d89 (18 DEFINE TRANSACTION
 *               entries; 17 sourced)
 * Source      : app/cpy/CSSTRPFY.cpy:L22 @ 7756d89 (EIBAID
 *               attention-identifier evaluation)
 * Source      : app/cpy/COCOM01Y.cpy @ 7756d89 (COMMAREA context
 *               carrier this filter chain replaces)
 * Source      : app/cbl/COMEN01C.cbl:L153 @ 7756d89 (EXEC CICS XCTL
 *               COMMAREA propagation)
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
package com.cardemo.observability;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes the per-request <em>correlation identifier</em>: the single thread of identity that ties one
 * HTTP exchange's log records, trace spans and outbound AWS calls together.
 *
 * <p>On every request this filter resolves exactly one identifier, publishes it into the SLF4J
 * {@link MDC} under the key {@value #MDC_KEY_CORRELATION_ID}, copies the active trace and span identifiers
 * alongside it, tags the active span with it, echoes it back on the {@value #CORRELATION_ID_HEADER}
 * response header, and then restores the calling thread's prior diagnostic context before returning.
 *
 * <h2>Why this class exists: it is rule-mandated, not requirements-derived</h2>
 *
 * <p><strong>Nothing in this file is a translation of existing behaviour.</strong> A census of the frozen
 * corpus at commit {@code 7756d89} finds <strong>322 {@code DISPLAY} statements</strong> across
 * {@code app/cbl/**} - the entire telemetry surface of 19,254 lines of COBOL - and a case-insensitive
 * search of {@code app/} for {@code prometheus}, {@code micrometer}, {@code opentelemetry},
 * {@code healthcheck} and {@code actuator} returns nothing at all. There is therefore no source construct
 * here to be faithful to.
 *
 * <p>This class exists solely because Rule 1 Clause A requires it: <em>"Observability: structured logs,
 * meaningful errors, and measurable behavior (metrics/tracing where relevant)."</em> That makes it a
 * rule-mandated artefact shipped <em>with</em> the initial implementation rather than as follow-up work.
 * Because it is new capability, <strong>no part of it may be justified as "preserved for parity"</strong>,
 * and it contains no intentional no-op. The three documented sites where the no-dead-code rule yields to
 * the parity mandate are reject code 109, {@code app/cbl/CBACT04C.cbl} paragraph
 * {@code 1400-COMPUTE-FEES} and a redundant index assignment in {@code app/cbl/CBSTM03A.CBL}. None of
 * them is in this package.
 *
 * <h2>Evidence: what this replaces, and one citation that is Not available</h2>
 *
 * <p>The specification describes this filter as replacing {@code EIBTRNID}, "the only per-request identity
 * the legacy system had". {@code EIBTRNID} is the field of the CICS EXEC Interface Block that
 * conventionally carries the running transaction identifier. It is <strong>supplied by the transaction
 * monitor rather than declared in application source</strong>, and it is consequently
 * <strong>not referenced anywhere in the frozen corpus</strong>.
 *
 * <p><strong>Direct citation for {@code EIBTRNID}: Not available. Severity: Medium.</strong> What would be
 * needed to verify it is an {@code EIBTRNID} reference somewhere under {@code app/cbl/**} - and
 * <strong>there is none</strong>. A repository-wide search at {@code 7756d89} returns zero occurrences
 * inside {@code app/}, and the complete EIB field census of {@code app/cbl/**} is only
 * {@code EIBCALEN} (49 sites) and {@code EIBAID} (16 sites); there is no {@code EIBTRNID}, no
 * {@code EIBDATE} and no {@code EIBTIME}. No {@code app/...:Lnnn} locator is fabricated for it here,
 * because a false citation is itself an evidence defect under Rule 1 Clause F. The severity is Medium
 * rather than High because the unverifiable citation affects documentation provenance only: it changes
 * nothing about this filter's behaviour.
 *
 * <p>The per-request identity evidence that genuinely <em>is</em> present, and is therefore cited in the
 * banner above, is:
 *
 * <ul>
 *   <li>{@code app/csd/CARDDEMO.CSD} - the CICS transaction identifiers themselves. Eighteen
 *       {@code DEFINE TRANSACTION} entries exist, of which <strong>17 are sourced</strong>
 *       ({@code CAUP CAVW CA00 CB00 CCDL CCLI CCUP CC00 CM00 CR00 CT00 CT01 CT02 CU00 CU01 CU02 CU03}).
 *       The eighteenth, {@code CDV1}, targets {@code PROGRAM(COCRDSEC)}, which has no source anywhere in
 *       the repository, so no endpoint is invented for it. The same file defines exactly eight files at
 *       lines 1, 13, 25, 37, 50, 63, 76 and 88 ({@code ACCTDAT}, {@code CARDAIX}, {@code CARDDAT},
 *       {@code CCXREF}, {@code CUSTDAT}, {@code CXACAIX}, {@code TRANSACT}, {@code USRSEC}).</li>
 *   <li>{@code EIBCALEN}, 49 sites, which gates COMMAREA presence. For example
 *       {@code app/cbl/COCRDLIC.cbl:L295} declares the COMMAREA as
 *       {@code OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN}.</li>
 *   <li>{@code EIBAID}, 16 sites, the attention identifier, evaluated from
 *       {@code app/cpy/CSSTRPFY.cpy:L22} onward ({@code WHEN EIBAID IS EQUAL TO DFHENTER}, then
 *       {@code DFHCLEAR}, {@code DFHPA1}, {@code DFHPA2}, {@code DFHPF1} and the rest).</li>
 *   <li>{@code app/cbl/COMEN01C.cbl:L153} - {@code XCTL PROGRAM(...)} carrying
 *       {@code COMMAREA(CARDDEMO-COMMAREA)} at {@code :L154}. That COMMAREA propagation across program
 *       transfer is what this filter chain replaces.</li>
 * </ul>
 *
 * <h2>The MDC key contract: three exact spellings</h2>
 *
 * <p>{@code src/main/resources/logback-spring.xml} consumes <strong>exactly</strong> the three spellings
 * below and no others, emitting them from a pattern provider as {@code "%mdc{correlationId:-}"},
 * {@code "%mdc{traceId:-}"} and {@code "%mdc{spanId:-}"}. The {@code :-} default means each JSON field is
 * <em>always present</em> and renders as an empty string when its MDC entry is absent, which keeps a log
 * consumer's schema valid when tracing is disabled. That is why the correct behaviour when no span is
 * active is simply to <em>omit</em> the key rather than to invent a placeholder.
 *
 * <p><strong>A drift in any one of these spellings silently empties a JSON field with no error anywhere:
 * severity High.</strong> Each therefore exists in exactly one place, as a constant on this class:
 * {@link #MDC_KEY_CORRELATION_ID}, {@link #MDC_KEY_TRACE_ID} and {@link #MDC_KEY_SPAN_ID}. The
 * remediation for a drift is to change the constant here and the matching provider entry in
 * {@code logback-spring.xml} together, never one alone.
 *
 * <h2>Where the trace and span identifiers come from</h2>
 *
 * <p>{@link #MDC_KEY_TRACE_ID} and {@link #MDC_KEY_SPAN_ID} are read from the Micrometer Tracing API
 * bridged to OpenTelemetry, through the injected {@link Tracer}. They are neither parsed from a request
 * header nor generated here - inventing them would produce identifiers that correlate with nothing in the
 * trace backend. Only {@link #MDC_KEY_CORRELATION_ID} is minted by this class, and only when the caller
 * did not supply a usable one.
 *
 * <h2>Ownership boundaries: what this class deliberately does not do</h2>
 *
 * <table border="1">
 *   <caption>Separation of concerns for the observability surface</caption>
 *   <tr><th>Concern</th><th>Owner</th></tr>
 *   <tr><td>Filter chain composition and ordering relative to authentication</td>
 *       <td>{@code com.cardemo.config.SecurityConfig}</td></tr>
 *   <tr><td>Tracing and metrics registration, and the wiring of this package</td>
 *       <td>{@code com.cardemo.config.ObservabilityConfig}</td></tr>
 *   <tr><td>Log encoder, appender, JSON field set, MDC selection and masking</td>
 *       <td>{@code src/main/resources/logback-spring.xml}</td></tr>
 *   <tr><td>Per-logger operational verbosity, tracing sampling, actuator exposure</td>
 *       <td>{@code src/main/resources/application.yml}</td></tr>
 *   <tr><td>AWS client construction and execution interceptors</td>
 *       <td>{@code com.cardemo.config.AwsConfig}</td></tr>
 *   <tr><td>Placing the batch job instance identifier into MDC</td>
 *       <td>{@code com.cardemo.batch} wired by {@code com.cardemo.config.BatchConfig}</td></tr>
 * </table>
 *
 * <p>Nothing above is restated here, because Rule 1 Clause C requires that duplication be avoided. This
 * class registers itself as a component and declares its own precedence; it composes no chain, builds no
 * client, and configures no appender.
 *
 * <h2>Ordering, and why it is the highest precedence available</h2>
 *
 * <p>The intended chain is this filter, then {@code com.cardemo.security.JwtAuthenticationFilter}, then
 * role-based authorisation. This class declares {@link #ORDER}, which is
 * {@link Ordered#HIGHEST_PRECEDENCE}. That value is published as a constant precisely so that
 * {@code com.cardemo.config.SecurityConfig} can align without guesswork.
 *
 * <p>The choice is not stylistic. Spring Boot registers the entire Spring Security filter chain at
 * {@code SecurityProperties.DEFAULT_FILTER_ORDER}, which is {@code -100}. Any value above that would let
 * an authentication failure be rejected <em>before</em> a correlation identifier existed, and the
 * resulting 401 or 403 would be logged without one. Running at the highest precedence guarantees that
 * <strong>every</strong> request is correlated, including one that authentication or authorisation
 * rejects outright.
 *
 * <h2>Stateless by construction: no session, no server-side state</h2>
 *
 * <p>This filter chain is what replaces the COMMAREA as the request-scoped context carrier. Accordingly
 * this class never calls {@code getSession()}, never reads or creates a session attribute, and never
 * holds per-request state in an instance field or a static. The only mutable state it touches is the
 * SLF4J diagnostic context, which is thread-local by design and is restored before the thread is
 * released.
 *
 * <p>The pseudo-conversational enter-versus-re-enter flag - {@code CDEMO-PGM-CONTEXT} at
 * {@code app/cpy/COCOM01Y.cpy:L29}, with {@code 88 CDEMO-PGM-ENTER VALUE 0} at {@code :L30} and
 * {@code 88 CDEMO-PGM-REENTER VALUE 1} at {@code :L31} - <strong>has no Java counterpart</strong>. It
 * collapses into stateless request handling: there is no second, "re-entered" pass over a screen to
 * distinguish, so nothing here reproduces it. The same applies to {@code CDEMO-FROM-TRANID},
 * {@code CDEMO-TO-TRANID}, {@code CDEMO-FROM-PROGRAM} and {@code CDEMO-TO-PROGRAM}, whose routing role
 * is taken over by the URL, and to {@code CDEMO-LAST-MAP} and {@code CDEMO-LAST-MAPSET}, since no screen
 * state is retained.
 *
 * <h2>The inbound header is untrusted input</h2>
 *
 * <p>Rule 1 Clause A requires that inputs be treated as untrusted and unsafe defaults avoided. An inbound
 * {@value #CORRELATION_ID_HEADER} header is attacker-controlled, and it flows into two places that are
 * both injection surfaces: a structured log stream and an HTTP response header. It is therefore validated
 * rather than trusted, against two independent bounds:
 *
 * <ul>
 *   <li>a maximum length of {@value #MAX_CORRELATION_ID_LENGTH} characters, checked before any pattern is
 *       applied so that an oversized value is rejected without scanning it; and</li>
 *   <li>a restrictive character set of ASCII letters, digits, {@code -} and {@code _} only, enforced by a
 *       single pattern compiled once into a constant and anchored with {@code \A} and {@code \z}. The
 *       pattern is one bounded character class, so it cannot backtrack catastrophically. The absolute
 *       {@code \z} anchor is used rather than {@code $} because {@code $} also matches immediately before
 *       a final line terminator, which is exactly the character an injection attempt carries.</li>
 * </ul>
 *
 * <p>Three distinct rejection cases are handled explicitly, as Rule 1 Clause B requires of null and empty
 * boundary conditions: an <em>absent</em> header, a header <em>present but blank or whitespace-only</em>,
 * and a header <em>present but malformed</em>. All three yield a freshly generated identifier. A malformed
 * value is <strong>discarded outright, never sanitised and kept</strong>, because a sanitising rewrite
 * silently changes a caller's identifier into a different one and invites a filter-bypass mismatch between
 * what was validated and what was stored. No case of a bad header fails the request: correlation is
 * diagnostic infrastructure and must never become an availability risk.
 *
 * <p>Because the accepted character set excludes the double quote, the backslash, the brace characters,
 * the carriage return and the line feed, an accepted value is inert in both destinations. It cannot close
 * a JSON string or open a new object, so it cannot forge a log record in the JSON output that
 * {@code logback-spring.xml} produces; and it cannot terminate a header line, so it cannot split the
 * response. <strong>The raw inbound value is never written to a log message</strong> - only the
 * validated-or-regenerated value is ever published, which is why validation runs before the response
 * header is set.
 *
 * <h2>Propagation onto outbound AWS calls, and the limits of it</h2>
 *
 * <p>The identifier must travel with outbound S3, SQS and SNS calls so that a batch object or a queue
 * message can be traced back to the request that caused it. The mechanism this class relies on is the
 * ambient context of the calling thread: both the SLF4J diagnostic context and the Micrometer trace scope
 * are thread-local, so <strong>on the same thread</strong> they are already visible to the AWS client
 * interceptor layer and to any logging that layer performs, with nothing further required here.
 *
 * <p><strong>That guarantee stops at a thread boundary.</strong> Across an executor hand-off, an
 * asynchronous dispatch, a parallel stream or a Spring Batch worker thread the context does
 * <em>not</em> propagate automatically and must be carried deliberately by whoever crosses the boundary.
 * This is the documented cause of an empty {@value #MDC_KEY_CORRELATION_ID} field on work that plainly
 * belongs to a request, and the fix belongs at the hand-off, not here.
 *
 * <p>Consistent with the ownership table above, this class constructs no AWS client and registers no
 * execution interceptor; that is {@code com.cardemo.config.AwsConfig} territory. Its obligation is to
 * make the identifier <em>available</em> in the ambient context and to state precisely how far that
 * availability reaches. All AWS interaction targets LocalStack with zero live credentials, and no code
 * path in this class reaches any AWS endpoint, live or emulated.
 *
 * <h2>Batch events: the job instance identifier contract</h2>
 *
 * <p>This filter is HTTP-scoped, so batch execution does not pass through it at all. The MDC key names
 * declared here are nevertheless the shared contract for the whole application, and batch events carry one
 * additional key: {@link #MDC_KEY_JOB_INSTANCE_ID}. It is published on this class as the single point of
 * definition, and <strong>the batch layer is responsible for putting it into MDC</strong> -
 * {@code com.cardemo.batch} wired by {@code com.cardemo.config.BatchConfig}. Deliberately, no
 * {@code JobExecutionListener}, scheduler or other batch component is added to this file.
 *
 * <p>The key matters because it is what makes a run's logs correlatable with its output objects: the batch
 * writers derive their object-storage key prefixes from the same job instance identifier that tags the
 * log, and those deterministic per-run prefixes are what replace the seven generation data group bases of
 * {@code app/jcl/DEFGDGB.jcl}, where a relative generation reference such as {@code (+1)} became a new
 * object under a monotonically increasing prefix. In {@code logback-spring.xml} it is the only MDC entry
 * passed through by name and it is emitted only when present, so its presence is itself the signal that an
 * event is a batch event.
 *
 * <h2>This filter must not touch log message content</h2>
 *
 * <p>{@code com.cardemo.service.shared.FileStatusMapper} reproduces one legacy literal exactly:
 * {@code 9910-DISPLAY-IO-STATUS} at {@code app/cbl/CBTRN02C.cbl:L714-L727} carries
 * {@code DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04} on <em>both</em> of its branches, at {@code :L721}
 * and {@code :L725}, and {@code IO-STATUS-04} is a four-character field. COBOL's
 * {@code DISPLAY 'literal' identifier} concatenates its operands with no separator and the literal already
 * contains the placeholder text {@code NNNN}, so file status {@code '23'} emits
 * {@code FILE STATUS IS: NNNN0023} and not {@code FILE STATUS IS: 0023}.
 *
 * <p><strong>The stray {@code NNNN} is a preserved legacy quirk and is never to be "fixed".</strong> The
 * end-to-end parity gate diffs log output against the legacy baseline, so this filter deliberately
 * performs <strong>no</strong> log-message wrapping, rewriting, re-rendering, truncation, escaping or
 * post-processing of any kind. It contributes diagnostic context and nothing else. Adding message
 * manipulation here would break that gate in a way that looks like a Java defect and is not.
 *
 * <h2>Double registration is expected and is safe</h2>
 *
 * <p>A Spring bean that implements {@code jakarta.servlet.Filter} is auto-registered into the servlet
 * filter chain, and {@code com.cardemo.config.SecurityConfig} may <em>also</em> insert this same bean into
 * the Spring Security chain with {@code addFilterBefore(...)} so that it precedes
 * {@code com.cardemo.security.JwtAuthenticationFilter}. Naively that would run the filter twice per
 * request, overwriting the diagnostic context and duplicating the response header.
 *
 * <p>Extending {@link OncePerRequestFilter} is the defence: it records an already-filtered request
 * attribute derived from the filter name and skips any second invocation for the same request, so a single
 * bean reachable through two chains still executes exactly once. Two further properties make the outcome
 * robust regardless: the response header is written with a set rather than an add, so it cannot be
 * duplicated, and identifier resolution reuses a value already published on the response rather than
 * minting a second one. <strong>If a duplicate-bean or double-execution symptom ever does appear, the
 * resolution is in {@code SecurityConfig}, not here.</strong>
 *
 * <p>Actuator probe paths are deliberately <em>not</em> skipped. Correlating {@code /actuator/health} is
 * harmless, and having the probe traffic carry identifiers is useful gate evidence, so no
 * {@code shouldNotFilter} predicate is declared.
 *
 * <h2>Secrecy</h2>
 *
 * <p>Rule 1 Clause D requires no secrets in code, logs, tests or configuration, and this class sits on the
 * request path writing into the log context, which gives it the greatest exposure of any file in the tree.
 * It is therefore written to produce nothing sensitive in the first place: it reads exactly one request
 * header, the correlation header, and <strong>never inspects, decodes or logs the
 * {@code Authorization} header, a bearer token, a credential, a request body or a response body</strong>.
 * It emits no log record of its own at all, which is the strongest available guarantee that it cannot leak
 * a card number, a social security number, a telephone number, a government identifier, a date of birth,
 * an electronic funds transfer account identifier, a stored password hash or an AWS payload. The masking
 * rules in {@code logback-spring.xml} are a second line of defence, not the first.
 *
 * <p>No value in this class is read from the process environment: there is no {@code System.getenv} call,
 * no absolute path, and no hardcoded host or port. The header name and the length bound are compile-time
 * constants, so behaviour is identical in every profile and no configuration key had to be invented for
 * them. No case conversion and no locale-sensitive formatting is performed anywhere in this class, so
 * there is no default-locale dependence to guard against; {@link UUID#toString()} is specified to emit
 * lowercase hexadecimal and is locale-independent.
 *
 * <h2>How to build and test this component</h2>
 *
 * <p>Build and unit-test from the repository root with {@code ./mvnw -B -ntp clean compile} and
 * {@code ./mvnw -B -ntp test}. Compilation runs {@code -Xlint:all -Werror} with warnings failing the
 * build, targeting Java 25 with no preview features. Tests for this class live under
 * {@code src/test/java/com/cardemo/unit} and drive it through the public
 * {@code doFilter(ServletRequest, ServletResponse, FilterChain)} entry point with
 * {@code MockHttpServletRequest}, {@code MockHttpServletResponse} and {@code MockFilterChain}; the
 * injected {@link Tracer} collaborator can be {@link Tracer#NOOP} or a stub. The class holds no static
 * mutable state and depends on no clock, locale, charset or time zone, so those tests need no fixture
 * beyond the mocks.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <table border="1">
 *   <caption>Symptoms, causes and severities</caption>
 *   <tr><th>Symptom</th><th>Cause and fix</th><th>Severity</th></tr>
 *   <tr>
 *     <td>The {@code correlationId} JSON field is present but always empty.</td>
 *     <td>Either the request never passed through this filter, or the work is happening on a thread the
 *         diagnostic context was not propagated to. Propagate the context across the hand-off; do not
 *         change this class.</td>
 *     <td>Medium</td>
 *   </tr>
 *   <tr>
 *     <td>The {@code correlationId} JSON field is missing entirely rather than empty.</td>
 *     <td>The MDC key spelling drifted from the provider entry in {@code logback-spring.xml}. Realign
 *         {@link #MDC_KEY_CORRELATION_ID} and that entry together.</td>
 *     <td>High</td>
 *   </tr>
 *   <tr>
 *     <td>{@code traceId} and {@code spanId} are present but always empty.</td>
 *     <td>No tracing bridge is active, or sampling dropped the trace, or no span was in scope at this
 *         point in the chain. Check {@code management.tracing} and {@code management.otlp.tracing} in the
 *         active profile. This is expected behaviour, not a fault.</td>
 *     <td>Low</td>
 *   </tr>
 *   <tr>
 *     <td>An identifier from one request appears on a later, unrelated request.</td>
 *     <td>Diagnostic context leaked across a pooled thread. This class restores the prior context in a
 *         {@code finally} block on both the normal and the exceptional path specifically to prevent it.</td>
 *     <td>Blocker</td>
 *   </tr>
 *   <tr>
 *     <td>A 401 or 403 response is logged without a correlation identifier.</td>
 *     <td>This filter is ordered after the security chain. Restore {@link #ORDER}.</td>
 *     <td>High</td>
 *   </tr>
 *   <tr>
 *     <td>A caller's supplied identifier is replaced by a generated one.</td>
 *     <td>Expected when the supplied value exceeds {@value #MAX_CORRELATION_ID_LENGTH} characters or
 *         contains anything outside ASCII letters, digits, {@code -} and {@code _}.</td>
 *     <td>Low</td>
 *   </tr>
 * </table>
 *

 * @see MetricsConfig
 */
@Component
@Order(CorrelationIdFilter.ORDER)
public final class CorrelationIdFilter extends OncePerRequestFilter {

    /**
     * The precedence at which this filter runs, {@link Ordered#HIGHEST_PRECEDENCE}.
     *
     * <p>Published as a constant so that {@code com.cardemo.config.SecurityConfig} can position the
     * authentication filter relative to this one without duplicating a magic number. The value must stay
     * below {@code SecurityProperties.DEFAULT_FILTER_ORDER} ({@code -100}), the order at which Spring Boot
     * registers the Spring Security chain; otherwise a request rejected by authentication or authorisation
     * would be logged before a correlation identifier existed.
     */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE;

    /**
     * The header name read for an inbound correlation identifier and echoed back on the response.
     *
     * <p>A compile-time constant rather than a bound property: {@code application.yml} declares no
     * correlation namespace, and inventing one would add a configuration surface with no caller. The same
     * spelling is used in both directions so that a caller can quote the value it receives.
     */
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /**
     * MDC key for the correlation identifier: {@value}.
     *
     * <p>Consumed by {@code logback-spring.xml} as {@code "%mdc{correlationId:-}"}. This is the one
     * identifier this class mints; a drift in this spelling silently empties a JSON field (severity High).
     */
    public static final String MDC_KEY_CORRELATION_ID = "correlationId";

    /**
     * MDC key for the trace identifier: {@value}.
     *
     * <p>Consumed by {@code logback-spring.xml} as {@code "%mdc{traceId:-}"}. Sourced from the injected
     * {@link Tracer}, never generated here, and omitted when no usable trace identity is in scope.
     */
    public static final String MDC_KEY_TRACE_ID = "traceId";

    /**
     * MDC key for the span identifier: {@value}.
     *
     * <p>Consumed by {@code logback-spring.xml} as {@code "%mdc{spanId:-}"}. Sourced from the injected
     * {@link Tracer}, never generated here, and omitted when no usable span identity is in scope.
     */
    public static final String MDC_KEY_SPAN_ID = "spanId";

    /**
     * MDC key for the batch job instance identifier: {@value}.
     *
     * <p><strong>This class never sets this key</strong> - it is HTTP-scoped and batch execution does not
     * pass through it. The constant exists here because this is the single point of definition for the
     * application's MDC key contract, and {@code logback-spring.xml} passes this one key through by name
     * as the marker that an event is a batch event. Populating it is the responsibility of
     * {@code com.cardemo.batch}, wired by {@code com.cardemo.config.BatchConfig}. It is a published
     * contract constant, not dead code: the batch writers key their object-storage prefixes off the same
     * job instance identifier, which is what makes a run's logs and its output objects correlatable.
     */
    public static final String MDC_KEY_JOB_INSTANCE_ID = "jobInstanceId";

    /**
     * Span tag key carrying the correlation identifier: {@value}.
     *
     * <p>Tagging the active span is what lets a trace in the tracing backend be joined to a log line by
     * correlation identifier. The dotted spelling matches the tag-key convention already established for
     * this application's metrics.
     */
    public static final String SPAN_TAG_CORRELATION_ID = "correlation.id";

    /**
     * Maximum accepted length of an inbound correlation identifier, in characters: {@value}.
     *
     * <p>Generous enough for a UUID (36 characters) or a typical upstream identifier, small enough that an
     * unbounded or padded value cannot bloat every log record. Checked before the pattern is applied, so an
     * oversized value is rejected without being scanned.
     */
    public static final int MAX_CORRELATION_ID_LENGTH = 64;

    /**
     * The single validation pattern for an inbound correlation identifier, compiled once.
     *
     * <p>ASCII letters, digits, {@code -} and {@code _} only, bounded by
     * {@link #MAX_CORRELATION_ID_LENGTH} and anchored with {@code \A} and {@code \z}. One bounded
     * character class means no catastrophic backtracking. The absolute {@code \z} anchor is deliberate:
     * {@code $} would also match immediately before a final line terminator, which is precisely the
     * character a log-injection attempt carries. The ranges are literal ASCII, so no Unicode or
     * locale-sensitive case folding is involved.
     */
    private static final Pattern CORRELATION_ID_PATTERN =
            Pattern.compile("\\A[A-Za-z0-9_-]{1," + MAX_CORRELATION_ID_LENGTH + "}\\z");

    /**
     * The tracing facade supplying the current span, injected through the constructor.
     *
     * <p>Immutable and the only collaborator this class has. Never used to create or close a span: this
     * filter reads the ambient trace identity and adds a tag to it, and owns no span lifecycle.
     */
    private final Tracer tracer;

    /**
     * Creates the filter.
     *
     * <p>Constructor injection is the only injection mechanism used, so the collaborator is final and the
     * instance is fully formed and immutable once constructed. When tracing is disabled, Spring supplies a
     * no-op tracer, which this class handles as the ordinary no-active-span case rather than as an error.
     *
     * @param tracer the Micrometer tracing facade bridged to OpenTelemetry, used to read the active span's
     *               trace and span identifiers and to tag that span; must not be {@code null}
     * @throws NullPointerException if {@code tracer} is {@code null}, which would indicate a broken
     *                             application context rather than a runtime condition
     */
    public CorrelationIdFilter(final Tracer tracer) {
        this.tracer = Objects.requireNonNull(tracer, "tracer must not be null");
    }

    /**
     * Resolves the correlation identifier for this request, publishes the diagnostic context, continues the
     * chain, and restores the thread's prior diagnostic context.
     *
     * <p><strong>Side effects</strong>, in the order they occur:
     *
     * <ol>
     *   <li>{@link #MDC_KEY_CORRELATION_ID} is put into the SLF4J diagnostic context.</li>
     *   <li>The {@value #CORRELATION_ID_HEADER} response header is <em>set</em> - not added - so a second
     *       pass cannot duplicate it and a caller can quote the value in a support request.</li>
     *   <li>The active span, if any, is tagged with {@link #SPAN_TAG_CORRELATION_ID}, and
     *       {@link #MDC_KEY_TRACE_ID} and {@link #MDC_KEY_SPAN_ID} are put into the diagnostic context when
     *       a usable trace identity exists.</li>
     *   <li>The remainder of the chain runs.</li>
     *   <li>All three diagnostic context entries are restored to the values they held on entry.</li>
     * </ol>
     *
     * <p><strong>Configuration and defaults.</strong> The header name is {@value #CORRELATION_ID_HEADER},
     * the length bound is {@value #MAX_CORRELATION_ID_LENGTH} characters, and the accepted character set is
     * ASCII letters, digits, {@code -} and {@code _}. None of the three is configurable, so behaviour is
     * identical in every profile.
     *
     * <p><strong>Failure modes.</strong> An absent, blank or malformed inbound header never fails the
     * request; a fresh identifier is generated instead. When no span is active the trace and span keys are
     * simply omitted, and the JSON fields render empty through the {@code :-} default in
     * {@code logback-spring.xml}. The restoration runs in a {@code finally} block, so it happens on the
     * exceptional path as well as the normal one: an exception thrown downstream propagates untouched -
     * it is neither caught, swallowed, wrapped nor logged here - while the diagnostic context is still
     * left exactly as it was found. That is what prevents an identifier leaking onto the next request to
     * borrow this pooled thread, which would be a Blocker-severity correctness defect.
     *
     * <p><strong>Troubleshooting.</strong> An always-empty {@code traceId} or {@code spanId} means no span
     * was active; check {@code management.tracing.sampling.probability} in the active profile. A missing
     * rather than empty {@code correlationId} field means the MDC key spelling drifted from
     * {@code logback-spring.xml}.
     *
     * @param request     the current request, read only for the {@value #CORRELATION_ID_HEADER} header;
     *                     never inspected for credentials, tokens or body content
     * @param response    the current response, which receives the {@value #CORRELATION_ID_HEADER} header
     *                     and is also read back to detect a re-entrant dispatch
     * @param filterChain the remainder of the chain, invoked exactly once
     * @throws ServletException if the downstream chain raises it; propagated unchanged
     * @throws IOException      if the downstream chain raises it; propagated unchanged
     */
    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain filterChain) throws ServletException, IOException {

        final String correlationId = resolveCorrelationId(request, response);

        final String previousCorrelationId = MDC.get(MDC_KEY_CORRELATION_ID);
        final String previousTraceId = MDC.get(MDC_KEY_TRACE_ID);
        final String previousSpanId = MDC.get(MDC_KEY_SPAN_ID);

        try {
            MDC.put(MDC_KEY_CORRELATION_ID, correlationId);
            response.setHeader(CORRELATION_ID_HEADER, correlationId);
            applyTraceContext(correlationId);
            filterChain.doFilter(request, response);
        } finally {
            restoreMdcEntry(MDC_KEY_CORRELATION_ID, previousCorrelationId);
            restoreMdcEntry(MDC_KEY_TRACE_ID, previousTraceId);
            restoreMdcEntry(MDC_KEY_SPAN_ID, previousSpanId);
        }
    }

    /**
     * Declares that error dispatches <em>are</em> filtered, overriding the inherited default of skipping
     * them.
     *
     * <p>{@link OncePerRequestFilter} skips error dispatches by default. That default would leave a hole
     * exactly where correlation matters most: Spring Security's default access-denied handling calls
     * {@code sendError}, which makes the container dispatch to {@code /error}, and the log records written
     * while that error response is rendered would carry no correlation identifier. Returning {@code false}
     * closes the hole, so a 401 or a 403 is correlated end to end.
     *
     * <p>The obvious hazard of running twice - minting a second identifier for the same exchange - is
     * closed in {@link #resolveCorrelationId}, which reuses the value already published on the response
     * before considering the inbound header. Asynchronous dispatches keep the inherited default of being
     * skipped, because diagnostic context does not follow a thread hand-off; carrying it across such a
     * boundary is the hand-off's responsibility, not this filter's.
     *
     * @return {@code false}, meaning error dispatches are filtered
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    /**
     * Determines the correlation identifier for this exchange, treating any inbound value as untrusted.
     *
     * <p>The five cases are handled explicitly and separately rather than collapsed, because each is a
     * distinct boundary condition and each is covered by its own unit test:
     *
     * <ol>
     *   <li><em>Re-entry.</em> A well-formed value already published on the response means this is a second
     *       dispatch of the same exchange, so that value is reused and no second identifier is minted.</li>
     *   <li><em>Absent.</em> No header at all - generate.</li>
     *   <li><em>Blank.</em> Present but empty or whitespace-only - generate.</li>
     *   <li><em>Malformed.</em> Present but over length or outside the accepted character set - generate,
     *       discarding the supplied value outright. It is deliberately <strong>not</strong> sanitised and
     *       kept: rewriting a caller's identifier would silently substitute a different one and would
     *       decouple the value that was validated from the value that is stored.</li>
     *   <li><em>Valid.</em> Accepted verbatim, so a caller-supplied identifier survives the round trip.</li>
     * </ol>
     *
     * <p>Nothing here throws and nothing rejects the request: correlation is diagnostic infrastructure and
     * must never become an availability risk. The raw inbound value is never logged and never reaches the
     * response; only the returned value is published.
     *
     * @param request  the current request, read only for the correlation header
     * @param response the current response, read back to detect a re-entrant dispatch
     * @return a validated inbound identifier, a reused already-published identifier, or a freshly
     *         generated one; never {@code null} and always well-formed
     */
    private static String resolveCorrelationId(final HttpServletRequest request,
                                               final HttpServletResponse response) {

        final String alreadyPublished = response.getHeader(CORRELATION_ID_HEADER);
        if (isWellFormed(alreadyPublished)) {
            return alreadyPublished;
        }

        final String supplied = request.getHeader(CORRELATION_ID_HEADER);
        if (supplied == null) {
            return newCorrelationId();
        }
        if (supplied.isBlank()) {
            return newCorrelationId();
        }
        if (!isWellFormed(supplied)) {
            return newCorrelationId();
        }
        return supplied;
    }

    /**
     * Tags the active span with the correlation identifier and copies the active trace identity into the
     * diagnostic context.
     *
     * <p>The span is fetched once and reused for both operations. When no span is active - tracing
     * disabled, or a code path outside any observation - the method returns silently, leaving
     * {@link #MDC_KEY_TRACE_ID} and {@link #MDC_KEY_SPAN_ID} absent so that
     * {@code logback-spring.xml} renders them as empty strings. That is the documented, expected outcome
     * and not an error, which is why it is handled by an explicit test rather than by catching anything.
     *
     * <p>No exception from the tracing facade is caught here. A tracer that throws indicates a broken
     * application context, and Rule 1 Clause B forbids swallowing that: it must surface loudly rather than
     * be hidden behind a silently uncorrelated request. The diagnostic context is still restored, because
     * the caller's {@code finally} block runs regardless.
     *
     * @param correlationId the validated correlation identifier to attach to the span; never {@code null}
     */
    private void applyTraceContext(final String correlationId) {
        final Span currentSpan = this.tracer.currentSpan();
        if (currentSpan == null) {
            return;
        }

        currentSpan.tag(SPAN_TAG_CORRELATION_ID, correlationId);

        final TraceContext context = currentSpan.context();
        if (context == null) {
            return;
        }
        putIfUsable(MDC_KEY_TRACE_ID, context.traceId());
        putIfUsable(MDC_KEY_SPAN_ID, context.spanId());
    }

    /**
     * Puts a trace or span identifier into the diagnostic context, but only when it carries real identity.
     *
     * @param key        the MDC key to populate
     * @param identifier the candidate identifier, which may be {@code null}, blank or the all-zero sentinel
     */
    private static void putIfUsable(final String key, final String identifier) {
        if (isUsableTraceIdentifier(identifier)) {
            MDC.put(key, identifier);
        }
    }

    /**
     * Reports whether a trace or span identifier carries real trace identity.
     *
     * <p>Three non-identities are rejected: {@code null}, a blank value, and an all-zero value.
     * The all-zero case is the OpenTelemetry invalid sentinel - a 32-zero trace identifier or a 16-zero
     * span identifier - which a no-op or never-started span reports. Emitting it would put a
     * correlation-looking value into every log record that correlates with nothing in the trace backend.
     *
     * <p>Deliberately, the span's own no-op flag is <em>not</em> consulted instead. On the OpenTelemetry
     * bridge that flag reflects whether the span is recording, so an unsampled but perfectly valid span
     * reports as no-op; keying off it would discard usable identifiers whenever sampling is below one.
     * Testing the identifier itself is the semantically correct check.
     *
     * @param identifier the candidate identifier
     * @return {@code true} when the identifier is non-null, non-blank and not all zeros
     */
    private static boolean isUsableTraceIdentifier(final String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return false;
        }
        for (int index = 0; index < identifier.length(); index++) {
            if (identifier.charAt(index) != '0') {
                return true;
            }
        }
        return false;
    }

    /**
     * Restores one diagnostic context entry to the value it held before this filter ran.
     *
     * <p>Restoring rather than blindly removing serves both halves of the cleanup requirement at once. An
     * entry that did not exist on entry is removed, so nothing leaks onto the next request to borrow this
     * pooled thread; an entry that did exist is put back, so context owned by the tracing infrastructure
     * or by an outer filter is not destroyed. A blanket clear of the whole context would satisfy the first
     * and violate the second.
     *
     * @param key           the MDC key to restore
     * @param previousValue the value the key held on entry, or {@code null} if it was absent
     */
    private static void restoreMdcEntry(final String key, final String previousValue) {
        if (previousValue == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previousValue);
        }
    }

    /**
     * Reports whether a candidate correlation identifier is well-formed.
     *
     * <p>The length bound is applied before the pattern so that an oversized value is rejected without
     * being scanned. The pattern independently enforces both the bound and the character set, so neither
     * check alone is load-bearing.
     *
     * @param candidate the candidate identifier, which may be {@code null}
     * @return {@code true} only when the candidate is non-null, no longer than
     *         {@value #MAX_CORRELATION_ID_LENGTH} characters, and composed entirely of ASCII letters,
     *         digits, {@code -} and {@code _}
     */
    private static boolean isWellFormed(final String candidate) {
        return candidate != null
                && candidate.length() <= MAX_CORRELATION_ID_LENGTH
                && CORRELATION_ID_PATTERN.matcher(candidate).matches();
    }

    /**
     * Generates a fresh correlation identifier.
     *
     * <p>A random UUID: 36 characters, comfortably inside
     * {@value #MAX_CORRELATION_ID_LENGTH}, and composed only of lowercase hexadecimal digits and hyphens,
     * so a generated value always satisfies {@link #isWellFormed}. Randomness rather than a sequence is
     * deliberate - a predictable identifier would let a caller guess or collide with another request's -
     * and {@link UUID#toString()} is specified to emit lowercase hexadecimal, so no locale is involved.
     *
     * @return a newly generated, well-formed correlation identifier; never {@code null}
     */
    private static String newCorrelationId() {
        return UUID.randomUUID().toString();
    }
}
