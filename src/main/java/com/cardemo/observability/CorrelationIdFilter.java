/*
 * ******************************************************************
 * Program     : CorrelationIdFilter.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 servlet filter
 * Function    : Establishes the per-request correlation identifier in
 *               MDC, on the trace span and on outbound AWS calls.
 * Capability  : NEW - additive request correlation, not a translation.
 *               The frozen corpus has no per-request identifier to
 *               translate: EIBTRNID is CICS-supplied and occurs ZERO
 *               times anywhere under app/**. This filter fills the role
 *               CICS played implicitly, which is a different claim from
 *               replacing a construct present in the source. Mandated by
 *               Rule 1 Clause A, not derived from a COBOL paragraph.
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
 * and it contains no intentional no-op.
 *
 * <p>No global tally of retained no-ops is stated here. An earlier revision named "the three documented
 * sites"; that count is withdrawn, because several files each maintained their own tally by hand and they did
 * not agree - one said three and another five - which is what a hand-maintained census in a comment always
 * decays into. Severity of what that left in place: <strong>High</strong>. The governing rule instead is
 * per-artefact: <strong>a retained no-op is justified at its own declaration</strong>, where it must carry its
 * COBOL locator, a proof of reachability, an explicit intentional-no-op marker, and an acknowledgement that it
 * is owed an entry in the planned {@code DECISION_LOG.md}. The only claim this class makes is the local one:
 * nothing in {@code com.cardemo.observability} carries such a marker, so anything here resembling dead code is
 * dead code.
 *
 * <h2>Evidence: this is additive capability, and one citation that is Not available</h2>
 *
 * <p><strong>Correlation is new request-correlation capability, not a translation of anything in the frozen
 * corpus.</strong> That is the accurate description and the one used consistently throughout this class.
 *
 * <p>The specification motivates the filter by analogy with {@code EIBTRNID}, "the only per-request identity
 * the legacy system had". The analogy is useful but it is <em>not</em> a translation relationship, and this
 * documentation does not assert one. {@code EIBTRNID} is the field of the CICS EXEC Interface Block that
 * conventionally carries the running transaction identifier. It is <strong>supplied by the transaction
 * monitor rather than declared in application source</strong>, and it is consequently
 * <strong>not referenced anywhere in the frozen corpus</strong>. There is therefore no source construct
 * being replaced here - only a role that CICS filled implicitly and that must now be filled explicitly.
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
 *   </ul>
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
 *       <td>{@code com.cardemo.config.ObservabilityConfig}, which holds the documented wiring contract and the
 *       single production time source. Nothing here depends on it: the three classes in this package are
 *       self-registering, which is why it declares no duplicate of any of them.</td></tr>
 *   <tr><td>Log encoder, appender, JSON field set, MDC selection and masking</td>
 *       <td>{@code src/main/resources/logback-spring.xml}</td></tr>
 *   <tr><td>Per-logger operational verbosity, tracing sampling, actuator exposure</td>
 *       <td>{@code src/main/resources/application.yml}</td></tr>
 *   <tr><td>AWS client construction and execution interceptors</td>
 *       <td>{@code com.cardemo.config.AwsConfig}</td></tr>
 *   <tr><td>Placing the batch job instance identifier into MDC</td>
 *       <td>{@code com.cardemo.batch}, which the <strong>planned</strong>
 *       {@code com.cardemo.config.BatchConfig} will wire</td></tr>
 *   </table>
 *
 * <p>Nothing above is restated here, because Rule 1 Clause C requires that duplication be avoided. This
 * class registers itself as a component and declares its own precedence; it composes no chain, builds no
 * client, and configures no appender.
 *
 * <h2>Ordering: after the observation filter, before security</h2>
 *
 * <p>The intended chain is Spring Boot's server observation filter, then this filter, then
 * {@code com.cardemo.security.JwtAuthenticationFilter}, then role-based authorisation. This class declares
 * {@link #ORDER}, which is {@link Ordered#HIGHEST_PRECEDENCE} plus two. That value is published as a constant
 * precisely so that {@code com.cardemo.config.SecurityConfig} can align without guesswork.
 *
 * <p>The choice is not stylistic, and getting it wrong is silent. Spring Boot registers the entire Spring
 * Security filter chain at {@code SecurityProperties.DEFAULT_FILTER_ORDER}, which is {@code -100}: any value
 * above that would let an authentication failure be rejected <em>before</em> a correlation identifier existed,
 * and the resulting 401 or 403 would be logged without one. But the ceiling is only half the constraint. Boot
 * also registers {@code ServerHttpObservationFilter} at {@code Ordered.HIGHEST_PRECEDENCE + 1}, and that filter
 * is what opens the server observation and with it the span this class tags. Running at
 * {@link Ordered#HIGHEST_PRECEDENCE} - as an earlier revision did - therefore ran <em>before</em> the span
 * existed, so {@link Tracer#currentSpan()} returned {@code null} on every request, no span was ever tagged and
 * the trace and span identifiers never reached the diagnostic context. Nothing failed and nothing was logged
 * about it, because a null span is a legitimate state this class handles deliberately; the symptom was an
 * always-empty {@code traceId} that read as "tracing is off". Severity: <strong>High</strong>. The window
 * between the two registrations is exactly one order value wide, and {@link #ORDER} sits inside it.
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
 *   </ul>
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
 * <p><strong>That guarantee stops at a thread boundary, and at a process boundary it never held at all.</strong>
 * Across an executor hand-off, an asynchronous dispatch, a parallel stream or a Spring Batch worker thread the
 * context does not propagate automatically; and an outbound HTTP request carries no diagnostic context
 * whatsoever, because a thread-local is not a wire format. An earlier revision named the second of these as
 * something the AWS configuration would do and provided nothing for it to do it with, which left the identifier
 * ending at the edge of this process. Severity: <strong>High</strong>.
 *
 * <p>Two published methods close both boundaries, and they are the whole propagation surface:
 *
 * <ul>
 *   <li>{@link #currentCorrelationId()} - reads the identifier in scope, validated, or {@code null}.
 *       {@code com.cardemo.config.AwsConfig} registers an execution interceptor that calls it and copies the
 *       result onto every outbound cloud request as {@value #CORRELATION_ID_HEADER}, and
 *       {@code com.cardemo.service.report.ReportSubmissionService} calls it to carry the identifier as a
 *       message header on the queue publish that replaces {@code EXEC CICS WRITEQ TD QUEUE('JOBS')}.</li>
 *   <li>{@link #propagate(String)} - establishes or clears the identifier on the calling thread and returns
 *       what it replaced, so a receiving thread can adopt a value and then put things back exactly as they
 *       were. This is what a hand-off uses, and what batch code uses to establish its own identifier without
 *       destroying an outer one.</li>
 *   </ul>
 *
 * <p>Consistent with the ownership table above, this class still constructs no AWS client and registers no
 * execution interceptor - it publishes the value and the validation, and
 * {@code com.cardemo.config.AwsConfig} owns the interceptor that consumes them. All AWS interaction targets
 * LocalStack with zero live credentials, and no code path in this class reaches any AWS endpoint, live or
 * emulated.
 *
 * <h2>Batch events: the job instance identifier contract</h2>
 *
 * <p>This filter is HTTP-scoped, so batch execution does not pass through it at all. The MDC key names
 * declared here are nevertheless the shared contract for the whole application, and batch events carry one
 * additional key: {@link #MDC_KEY_JOB_INSTANCE_ID}. It is published on this class as the single point of
 * definition, and <strong>the batch layer is responsible for putting it into MDC</strong> -
 * {@code com.cardemo.batch}, which the <strong>planned</strong> {@code com.cardemo.config.BatchConfig} will
 * wire. Deliberately, no {@code JobExecutionListener}, scheduler or other batch component is added to this
 * file.
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
 * build, targeting Java 25 with no preview features.
 * <p>
 * The unit test for this class lives at
 * {@code src/test/java/com/cardemo/unit/infrastructure/CorrelationIdFilterTest.java}. Re-derive with
 * {@code grep -rl CorrelationIdFilter src/test}. It drives the filter through the public
 * {@code doFilter(ServletRequest, ServletResponse, FilterChain)} entry point - the same one the container
 * uses - with {@code MockHttpServletRequest}, {@code MockHttpServletResponse} and {@code MockFilterChain},
 * and supplies a stubbed {@link Tracer}. The class holds no static mutable state and depends on no clock,
 * locale, charset or time zone, so those tests need no fixture beyond the mocks.
 * <p>
 * Two groups there are load-bearing rather than routine. The restoration group asserts that the diagnostic
 * context is handed back exactly as it was found - on the normal path, on the exception path, and when the
 * request thread already carried an outer context - because this filter runs on a pooled thread and a
 * surviving entry would mis-attribute every log line the next request writes. The rejection group asserts
 * that a blank, over-length or metacharacter-bearing caller value is <em>replaced</em> rather than echoed,
 * because the value reaches both a response header and the log stream.
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
     * The precedence at which this filter runs: {@link Ordered#HIGHEST_PRECEDENCE} plus two.
     *
     * <p>Published as a constant so that {@code com.cardemo.config.SecurityConfig} can position the
     * authentication filter relative to this one without duplicating a magic number.
     *
     * <p><strong>The offset of two is load-bearing and was measured, not guessed.</strong> The value has to
     * sit inside a window bounded at both ends:
     *
     * <ul>
     *   <li><strong>After Spring Boot's server observation filter</strong>, which
     *       {@code WebMvcObservationAutoConfiguration} registers at {@code Ordered.HIGHEST_PRECEDENCE + 1} -
     *       the literal {@code -2147483647}, read out of the compiled auto-configuration rather than taken
     *       from documentation. That filter is what opens the server observation, and therefore the span.
     *       At {@link Ordered#HIGHEST_PRECEDENCE} this filter ran <em>before</em> it, so
     *       {@link Tracer#currentSpan()} was {@code null} on every single request: the span tag was never
     *       applied and {@value #MDC_KEY_TRACE_ID} and {@value #MDC_KEY_SPAN_ID} were never populated. The
     *       code handled a null span correctly and the outcome looked like "tracing is disabled" rather than
     *       like a defect, which is precisely why it survived. Severity: <strong>High</strong>.</li>
     *   <li><strong>Before the Spring Security chain</strong>, which Spring Boot registers at
     *       {@code SecurityProperties.DEFAULT_FILTER_ORDER}, {@code -100}. A request rejected by
     *       authentication or authorisation must already carry a correlation identifier, or the rejection is
     *       logged uncorrelated - which is the one log record an operator most wants to join to a caller.</li>
     * </ul>
     *
     * <p>Two, rather than one, so that nothing has to share an order with the observation filter: relative
     * order between two registrations at the same value is unspecified, and a coin toss is not a contract.
     */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 2;

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
     * {@code com.cardemo.batch}, which the <strong>planned</strong> {@code com.cardemo.config.BatchConfig}
     * will wire. It is a published
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
     * The W3C Trace Context header name, {@value} - lowercase, as that specification requires.
     *
     * <p><strong>Finding M-07, severity Medium.</strong> Outbound propagation used to carry only this
     * application's own {@value #CORRELATION_ID_HEADER}, plus a pair of bespoke {@code X-Trace-Id} and
     * {@code X-Span-Id} message headers on the report publish. Those three name identifiers; none of them
     * <em>establishes parentage</em>, because no consumer outside this repository knows to look for them. A
     * downstream that receives them starts a fresh, unparented trace, so the very hop the tracing exists to
     * show - the request that produced a queue message, joined to the batch run that consumed it - is the one
     * hop that could not be reconstructed. {@code traceparent} is the interoperable form: every OpenTelemetry
     * and Micrometer Tracing consumer extracts it without being told to.
     *
     * <p>It is a published contract constant, declared once here alongside the diagnostic-context keys it is
     * composed from, because two producers write it - the outbound cloud-request interceptor in
     * {@code com.cardemo.config.AwsConfig} and the message headers in
     * {@code com.cardemo.service.report.ReportSubmissionService} - and a divergence between them would be
     * invisible.
     */
    public static final String TRACE_PARENT_HEADER = "traceparent";

    /**
     * Maximum accepted length of an inbound correlation identifier, in characters: {@value}.
     *
     * <p>Generous enough for a UUID (36 characters) or a typical upstream identifier, small enough that an
     * unbounded or padded value cannot bloat every log record. Checked before the pattern is applied, so an
     * oversized value is rejected without being scanned.
     */
    public static final int MAX_CORRELATION_ID_LENGTH = 64;

    /**
     * Maximum length of a job instance identifier accepted by
     * {@link #propagateJobInstanceId(String)}.
     *
     * <p>Twenty characters: nineteen digits, the widest a signed 64-bit value needs, plus a sign. A Spring Batch
     * instance identifier is a {@code long}, so no legitimate value can exceed this; the bound exists so that an
     * oversized value is refused without being scanned, the same ordering {@link #MAX_CORRELATION_ID_LENGTH}
     * establishes for the correlation identifier.
     */
    public static final int MAX_JOB_INSTANCE_ID_LENGTH = 20;

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

    /** The only {@value #TRACE_PARENT_HEADER} version this application emits, {@value}. */
    private static final String TRACE_PARENT_VERSION = "00";

    /**
     * The trace-flags octet emitted with every {@value #TRACE_PARENT_HEADER}, {@value} - the sampled bit set.
     *
     * <p>The diagnostic context carries identifiers and not the sampling decision, so the flag is asserted
     * rather than read. Asserting <em>sampled</em> is the only defensible direction: the identifiers are present
     * only when this process has an active span it is exporting, and emitting {@code 00} would invite the next
     * hop to discard its half of a trace whose first half is already on its way to the collector - producing a
     * broken trace, which is worse than none.
     */
    private static final String TRACE_PARENT_FLAGS_SAMPLED = "01";

    /** Length in hexadecimal characters of a W3C trace identifier, {@value}. */
    private static final int TRACE_ID_HEX_LENGTH = 32;

    /** Length in hexadecimal characters of a W3C parent (span) identifier, {@value}. */
    private static final int SPAN_ID_HEX_LENGTH = 16;

    /**
     * The compact 64-bit trace-identifier width some tracing bridges emit, {@value} hexadecimal characters.
     *
     * <p>A value of this width is left-padded with zeros to {@value #TRACE_ID_HEX_LENGTH}, which is the
     * conversion the specification prescribes and the same one the OpenTelemetry and B3 bridges perform.
     */
    private static final int COMPACT_TRACE_ID_HEX_LENGTH = 16;

    /**
     * The single validation pattern for one hexadecimal identifier field, compiled once.
     *
     * <p>Lowercase only, because the specification requires the lowercase form and a consumer is entitled to
     * compare the field byte-wise. Bounded and anchored with {@code \A} and {@code \z} on exactly the reasoning
     * given for {@link #CORRELATION_ID_PATTERN}: an identifier composed into a text protocol must not be able to
     * carry a line terminator.
     */
    private static final Pattern LOWERCASE_HEX_PATTERN =
            Pattern.compile("\\A[0-9a-f]{" + COMPACT_TRACE_ID_HEX_LENGTH + "," + TRACE_ID_HEX_LENGTH + "}\\z");

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
     * Returns the correlation identifier in scope on the calling thread, or {@code null} when there is none.
     *
     * <p><strong>This is the propagation surface, and it exists because a diagnostic context does not cross a
     * boundary by itself.</strong> The context is thread-local: it is established here for a request thread and
     * restored when the request ends, which covers logging on that thread and nothing else. Two boundaries need
     * the value explicitly, and both now read it from here:
     *
     * <ul>
     *   <li><strong>A process boundary.</strong> {@code com.cardemo.config.AwsConfig} registers an execution
     *       interceptor that copies this value onto every outbound cloud request as
     *       {@value #CORRELATION_ID_HEADER}, and {@code com.cardemo.service.report.ReportSubmissionService}
     *       carries it as a message header on the queue publish that replaces
     *       {@code EXEC CICS WRITEQ TD QUEUE('JOBS')}. Without that the thread of identity stopped at the edge
     *       of this process.</li>
     *   <li><strong>A thread boundary.</strong> Batch work never passes through this filter at all, so a job
     *       establishes its own value under {@value #MDC_KEY_CORRELATION_ID}; a step that hands work to another
     *       thread copies it across with {@link #propagate(String)} on the receiving thread and undoes that by
     *       calling the same method again with the value it returned.</li>
     * </ul>
     *
     * <p>The value is validated before it is returned, so a caller writing it into a text protocol - a header,
     * a message attribute - cannot be handed a value carrying a carriage return or a line feed. A malformed
     * entry yields {@code null}, which every caller treats as "no correlation available" rather than as an
     * error: a missing correlation is a diagnostic gap, whereas a corrupted header would be an injection.
     *
     * <p>Side effects: none. It reads the diagnostic context and nothing else.
     *
     * @return the well-formed correlation identifier in scope, or {@code null} when none is set or the entry
     *         present is not well-formed
     */
    public static String currentCorrelationId() {
        return usableCorrelationId(MDC.get(MDC_KEY_CORRELATION_ID));
    }

    /**
     * Returns a candidate correlation identifier when it is safe to propagate, and {@code null} when it is not.
     *
     * <p>The counterpart to {@link #currentCorrelationId()} for a value that did <em>not</em> come from this
     * thread's diagnostic context. One caller needs exactly that:
     * {@code com.cardemo.config.AwsConfig} recovers the identifier from a queue message's own attributes when a
     * publish is completed on a client thread that never had the diagnostic context - and that value is about
     * to be written into an HTTP header, so it must pass the same grammar as any other.
     *
     * <p><strong>This method, not a re-declared pattern, is how a caller validates.</strong> The bound of
     * {@value #MAX_CORRELATION_ID_LENGTH} characters and the {@code [A-Za-z0-9_-]} character set are owned here
     * and nowhere else, which is what makes "no carriage return, no line feed, no unbounded value can reach a
     * text protocol" a property of one method rather than a convention several files are trusted to remember.
     *
     * <p>A rejected candidate yields {@code null} rather than an exception or a substitute, because every
     * caller treats that as "no correlation available": a missing correlation is a diagnostic gap, whereas a
     * corrupted header would be a request-splitting vector and an invented one would be a lie.
     *
     * <p>Side effects: none. It reads no thread state, mutates nothing, and never logs - so it is safe to call
     * from an SDK client thread mid-request.
     *
     * @param candidate the identifier to check, which may be {@code null}, blank, oversized or malformed
     * @return the candidate unchanged when it is well-formed, otherwise {@code null}
     */
    public static String usableCorrelationId(final String candidate) {
        return isWellFormed(candidate) ? candidate : null;
    }

    /**
     * Composes the W3C Trace Context value for the trace identity in scope on the calling thread, or
     * {@code null} when there is none to propagate.
     *
     * <p><strong>Finding M-07, severity Medium.</strong> This is the interoperable propagation the two process
     * boundaries emit as {@value #TRACE_PARENT_HEADER}; see that constant for why naming identifiers in bespoke
     * headers did not establish parentage. The value is read from {@value #MDC_KEY_TRACE_ID} and
     * {@value #MDC_KEY_SPAN_ID}, which {@link #applyTraceContext(String)} sourced from the injected
     * {@link Tracer} - so the identity emitted is the one the log records already carry, and the trace, the
     * logs and the downstream hop all agree without any second source of truth.
     *
     * <p>Both fields are validated before composition and a field that fails is a {@code null} result, never a
     * partially corrected one. A malformed {@code traceparent} is worse than an absent one: a consumer that
     * rejects it starts a fresh trace anyway, and a consumer that accepts it records parentage onto a trace that
     * does not exist. The failure mode is therefore a missing header.
     *
     * <p>Side effects: none. It reads the diagnostic context and nothing else.
     *
     * @return a well-formed {@value #TRACE_PARENT_HEADER} value, or {@code null} when no usable trace identity
     *         is in scope
     */
    public static String currentTraceParent() {
        return traceParent(MDC.get(MDC_KEY_TRACE_ID), MDC.get(MDC_KEY_SPAN_ID));
    }

    /**
     * Composes a W3C Trace Context value from an explicit trace and span identifier pair.
     *
     * <p>The counterpart to {@link #currentTraceParent()} for a caller that holds a span directly rather than
     * reading the ambient diagnostic context - a publisher that opened a child span for one hop wants that
     * span's identifiers, so that a consumer parents onto the publish and not onto the request containing it.
     *
     * <p>A trace identifier of {@value #COMPACT_TRACE_ID_HEX_LENGTH} characters is left-padded with zeros to
     * {@value #TRACE_ID_HEX_LENGTH}, which is what the specification prescribes for a 64-bit identifier. An
     * all-zero identifier in either field is refused: the specification declares both invalid, and they are what
     * a no-op tracer reports.
     *
     * <p>A pure function of its arguments, with no side effects.
     *
     * @param traceId the trace identifier, possibly {@code null}
     * @param spanId  the span identifier that becomes the parent field, possibly {@code null}
     * @return a well-formed {@value #TRACE_PARENT_HEADER} value, or {@code null} when either field is unusable
     */
    public static String traceParent(final String traceId, final String spanId) {
        final String trace = normalisedTraceField(traceId, TRACE_ID_HEX_LENGTH);
        final String parent = normalisedTraceField(spanId, SPAN_ID_HEX_LENGTH);
        if (trace == null || parent == null) {
            return null;
        }
        return TRACE_PARENT_VERSION + '-' + trace + '-' + parent + '-' + TRACE_PARENT_FLAGS_SAMPLED;
    }

    /**
     * Validates one identifier field and returns it at its required width, or {@code null} if it is unusable.
     *
     * @param candidate the identifier as the tracing bridge reported it, possibly {@code null}
     * @param width     the width the field must occupy: {@value #TRACE_ID_HEX_LENGTH} or
     *                  {@value #SPAN_ID_HEX_LENGTH}
     * @return the field at exactly {@code width} lowercase hexadecimal characters, or {@code null}
     */
    private static String normalisedTraceField(final String candidate, final int width) {
        if (candidate == null || !LOWERCASE_HEX_PATTERN.matcher(candidate).matches()) {
            return null;
        }
        final String padded = candidate.length() == COMPACT_TRACE_ID_HEX_LENGTH && width == TRACE_ID_HEX_LENGTH
                ? "0".repeat(TRACE_ID_HEX_LENGTH - COMPACT_TRACE_ID_HEX_LENGTH) + candidate
                : candidate;
        if (padded.length() != width) {
            return null;
        }
        // An all-zero field is invalid per the specification, and is what a no-op tracer reports; treating it
        // as usable would publish parentage onto a trace that does not exist.
        return padded.chars().allMatch(character -> character == '0') ? null : padded;
    }

    /**
     * Establishes, or clears, the correlation identifier on the calling thread, returning the value it replaced.
     *
     * <p>The counterpart to {@link #currentCorrelationId()} for a <em>thread</em> boundary: a caller that hands
     * work to another thread calls this with the value from the originating thread, and the receiving thread
     * calls it again with the returned value to put things back exactly as they were. Passing {@code null}
     * removes the entry, which is what restores a thread that had none.
     *
     * <p>Restoring rather than blindly removing is the same discipline this filter applies to its own cleanup:
     * an entry that did not exist is removed, so nothing leaks onto the next task to borrow a pooled thread,
     * and an entry that did exist is put back, so context owned by an outer scope is not destroyed.
     *
     * <p>An argument that is not well-formed is rejected rather than stored, for the reason given on
     * {@link #currentCorrelationId()}: the diagnostic context feeds a text-based log format, so an unvalidated
     * value would be a log-injection vector.
     *
     * <p>Side effects: mutates one diagnostic context entry on the calling thread.
     *
     * @param correlationId the identifier to establish, or {@code null} to clear the entry
     * @return the well-formed value the entry held before this call, or {@code null} if it held none
     * @throws IllegalArgumentException if {@code correlationId} is non-null and not well-formed - longer than
     *         {@value #MAX_CORRELATION_ID_LENGTH} characters, or carrying anything outside ASCII letters,
     *         digits, {@code -} and {@code _}
     */
    public static String propagate(final String correlationId) {
        final String previous = currentCorrelationId();
        if (correlationId == null) {
            MDC.remove(MDC_KEY_CORRELATION_ID);
            return previous;
        }
        if (!isWellFormed(correlationId)) {
            throw new IllegalArgumentException("A propagated correlation identifier must be at most "
                    + MAX_CORRELATION_ID_LENGTH + " characters of ASCII letters, digits, '-' and '_'. The "
                    + "rejected value is withheld from this message.");
        }
        MDC.put(MDC_KEY_CORRELATION_ID, correlationId);
        return previous;
    }

    /**
     * Establishes, or clears, the batch job instance identifier on the calling thread, returning the value it
     * replaced.
     *
     * <p>The {@link #MDC_KEY_JOB_INSTANCE_ID} counterpart to {@link #propagate(String)}, and it lives here for
     * the same reason the constant does: the class that owns a diagnostic context key owns the discipline for
     * mutating it. Batch work never passes through this filter, so a batch listener has to establish the entry
     * itself - and a listener that establishes an entry owes the thread a <em>restore</em>, not a removal.
     * Spring Batch runs jobs on pooled threads, so a blanket {@code MDC.remove} in an {@code afterJob} destroys
     * an entry an outer scope owned: a job launched from inside a request, or a partitioned step whose parent
     * already labelled the thread. That is the asymmetry this method exists to close.
     *
     * <p>Passing {@code null} removes the entry, which is what restores a thread that had none.
     *
     * <p>The value is validated as an optionally negative run of decimal digits - exactly what
     * {@link Long#toString(long)} produces - for the reason given on {@link #propagate(String)}: the diagnostic
     * context feeds a text-based log format, so an unvalidated value is a log-injection vector.
     *
     * <p>The value this method reports as the previous one is validated by the same rule, so a malformed
     * inherited entry is reported as {@code null} and is therefore cleared rather than put back. That is
     * deliberate and matches {@link #currentCorrelationId()}: a restore must never itself fail, and a value that
     * could inject into the log format is not something to preserve. Without that rule a caller round-tripping
     * the returned value could be handed a value this method would then reject.
     *
     * <p>Side effects: mutates one diagnostic context entry on the calling thread.
     *
     * @param jobInstanceId the identifier to establish, or {@code null} to clear the entry
     * @return the well-formed value the entry held before this call, or {@code null} if it held none or held a
     *         value outside the permitted grammar
     * @throws IllegalArgumentException if {@code jobInstanceId} is non-null and is not an optionally negative
     *         run of decimal digits
     */
    public static String propagateJobInstanceId(final String jobInstanceId) {
        final String candidate = MDC.get(MDC_KEY_JOB_INSTANCE_ID);
        final String previous = isDecimalNumber(candidate) ? candidate : null;
        if (jobInstanceId == null) {
            MDC.remove(MDC_KEY_JOB_INSTANCE_ID);
            return previous;
        }
        if (!isDecimalNumber(jobInstanceId)) {
            throw new IllegalArgumentException("A propagated job instance identifier must be an optionally "
                    + "negative run of at most " + MAX_JOB_INSTANCE_ID_LENGTH + " decimal digits, as "
                    + "Long.toString produces. The rejected value is withheld from this message.");
        }
        MDC.put(MDC_KEY_JOB_INSTANCE_ID, jobInstanceId);
        return previous;
    }

    /**
     * Reports whether a candidate job instance identifier is an optionally negative run of decimal digits.
     *
     * <p>Checked with an explicit loop rather than {@link Character#isDigit(char)}, which accepts every Unicode
     * decimal digit - Arabic-Indic, Devanagari and dozens more - none of which {@link Long#parseLong(String)}
     * would accept and any of which would reach the log format. The length bound is applied before the scan so
     * an oversized value is rejected without being walked; nineteen digits plus a sign is the widest a signed
     * 64-bit value needs.
     *
     * @param candidate the candidate identifier, which may be {@code null}
     * @return {@code true} only when the candidate is non-null, non-empty, at most
     *         {@value #MAX_JOB_INSTANCE_ID_LENGTH} characters, and composed of ASCII digits with at most a
     *         single leading {@code -} that is itself followed by at least one digit
     */
    private static boolean isDecimalNumber(final String candidate) {
        if (candidate == null || candidate.isEmpty() || candidate.length() > MAX_JOB_INSTANCE_ID_LENGTH) {
            return false;
        }
        final int firstDigit = candidate.charAt(0) == '-' ? 1 : 0;
        if (candidate.length() == firstDigit) {
            return false;
        }
        for (int index = firstDigit; index < candidate.length(); index++) {
            final char digit = candidate.charAt(index);
            if (digit < '0' || digit > '9') {
                return false;
            }
        }
        return true;
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
