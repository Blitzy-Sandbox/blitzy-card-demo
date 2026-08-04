/*
 * ******************************************************************
 * Program     : AwsCorrelationIdPropagationIntegrationTest.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 AWS integration test (Failsafe tier)
 * Function    : Verifies correlation-id generation/acceptance, MDC placement, span
 *               attachment, finally-cleanup and propagation on outbound S3 and SQS
 *               calls.
 * Source      : app/csd/CARDDEMO.CSD @ 7756d89 (18 DEFINE TRANSACTION entries, 17
 *               sourced; CDV1 -> COCRDSEC has no source)
 * Source      : app/cbl/COCRDLIC.cbl:295 @ 7756d89 (OCCURS 1 TO 32767 TIMES
 *               DEPENDING ON EIBCALEN - the COMMAREA)
 * Source      : app/cpy/CSSTRPFY.cpy:22 @ 7756d89 (WHEN EIBAID IS EQUAL TO DFHENTER
 *               ... - AID dispatch)
 * Source      : app/cpy/COCOM01Y.cpy @ 7756d89 (COMMAREA identity and
 *               CDEMO-PGM-CONTEXT)
 * Source      : app/cbl/COMEN01C.cbl:153 @ 7756d89 (EXEC CICS XCTL PROGRAM(...) -
 *               state carried across transfers)
 * Replaces    : the CICS per-request thread of identity; EIBTRNID citation is Not
 *               available (see class Javadoc)
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
package com.cardemo.integration.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.config.AwsConfig;
import com.cardemo.observability.CorrelationIdFilter;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelSpan;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.ReadableSpan;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.services.s3.model.ListBucketsRequest;

/**
 * Proves that the correlation identifier - the Java replacement for the CICS per-request thread of identity -
 * is generated or accepted, placed in the diagnostic context under three exact keys, attached to the active
 * span, echoed once on the response, removed in a {@code finally} block, and carried onto outbound object-store
 * and queue calls.
 *
 * <h2>1. What it does</h2>
 *
 * <p>The frozen corpus has no per-request identity an application declares. Navigation state travelled in the
 * communication area, declared as {@code OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN} at
 * {@code app/cbl/COCRDLIC.cbl:295} and shaped by {@code app/cpy/COCOM01Y.cpy}; it was handed from program to
 * program by {@code EXEC CICS XCTL PROGRAM(...)} at {@code app/cbl/COMEN01C.cbl:153}; the key that arrived with
 * each turn was dispatched by {@code WHEN EIBAID IS EQUAL TO DFHENTER} at {@code app/cpy/CSSTRPFY.cpy:22}; and
 * the endpoint inventory those turns served is {@code app/csd/CARDDEMO.CSD}, which declares eighteen
 * {@code DEFINE TRANSACTION} entries of which seventeen have source - {@code CAUP CAVW CA00 CB00 CCDL CCLI CCUP
 * CC00 CM00 CR00 CT00 CT01 CT02 CU00 CU01 CU02 CU03} - the eighteenth, {@code CDV1} naming
 * {@code PROGRAM(COCRDSEC)}, having no source anywhere in the repository.
 *
 * <p>Everything asserted here is therefore <strong>new capability</strong> mandated by Rule 1 Clause A's
 * observability requirement, never behaviour preserved for parity: the entire telemetry surface of 19,254 lines
 * of COBOL is 322 {@code DISPLAY} statements, and a search of {@code app/} for any metrics, tracing, health or
 * actuator vocabulary returns nothing. The one legacy emission this class does protect is the rendering of
 * {@code 9910-DISPLAY-IO-STATUS} at {@code app/cbl/CBTRN02C.cbl:714-727}: both branches, at {@code :721} and
 * {@code :725}, execute {@code DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04}, and {@code IO-STATUS-04} is
 * {@code PIC 9} followed by {@code PIC 999} at {@code :138-140}, so exactly four characters. COBOL concatenates
 * a literal and an identifier with no separator and the literal already carries the placeholder text, so file
 * status {@code '23'} renders {@code FILE STATUS IS: NNNN0023}. The stray {@code NNNN} is a preserved legacy
 * quirk; this class asserts that the logging pipeline emits it verbatim and unmasked.
 *
 * <p><strong>{@code EIBTRNID}: Not available. Severity Medium.</strong> The construct this filter replaces
 * cannot be cited, and no locator for it is invented here. A census across the whole repository returns zero
 * occurrences of {@code EIBTRNID}; the complete exec-interface-block census in {@code app/cbl} is
 * {@code EIBCALEN} forty-nine times and {@code EIBAID} sixteen times, with no {@code EIBDATE},
 * {@code EIBTIME} or {@code EIBTRNID} anywhere. <em>What is needed:</em> the CICS-supplied exec-interface-block
 * copybook, which the transaction monitor provides and which is not part of this repository. <em>Remediation:</em>
 * cite the five real anchors named above instead, which is what this class and its banner do.
 *
 * <p>Two further mandatory disclosures, recorded here because this class is in the evidence path for both:
 *
 * <ul>
 *   <li><strong>The end-to-end parity baseline is Not available. Severity Medium.</strong> An exhaustive search
 *       for {@code expected}, {@code baseline}, {@code golden}, {@code .out}, {@code sysout},
 *       {@code DALYREJS}, {@code TRANREPT}, {@code STMTFILE} and {@code HTMLFILE} artefacts returns dataset
 *       <em>definition</em> job control only and zero captured data. <em>What is needed:</em> a captured
 *       {@code DALYREJS} 430-byte reject dataset plus the resulting {@code TRANSACT}, {@code ACCTDATA} and
 *       {@code TCATBALF} images from a real {@code POSTTRAN} run at a known input state. <em>Remediation:</em>
 *       create no baseline file and fabricate no expected bytes - a baseline produced by running this Java
 *       implementation would be circular and is forbidden. This class asserts no parity baseline.</li>
 *   <li><strong>File status {@code '35'}, file unavailable, is Not available. Severity Medium.</strong> The
 *       census across {@code app/cbl} finds the literal {@code '35'} zero times and
 *       {@code DFHRESP(NOTOPEN)} zero times. <em>What is needed:</em> a source occurrence to translate.
 *       <em>Remediation:</em> invent no test for it; nothing here asserts that path.</li>
 * </ul>
 *
 * <h2>2. How to run, build and test</h2>
 *
 * <p>Build and run the whole gate with {@code ./mvnw clean verify}. This class belongs to the Failsafe tier:
 * {@code maven-failsafe-plugin} 3.5.4 is bound to {@code src/test/java/com/cardemo/integration/**} and
 * {@code .../e2e/**} and runs it at {@code integration-test} and {@code verify}, even though the class name ends
 * in {@code Test}; {@code maven-surefire-plugin} 3.5.4 covers {@code .../unit/**} only and excludes these trees.
 * Moving or renaming this file so that it matches neither include set would leave it collected by neither
 * plugin - a green build in which nothing ran - so the path is part of the contract. Run it alone with
 * {@code ./mvnw -Dit.test=AwsCorrelationIdPropagationIntegrationTest verify} and confirm the Failsafe report
 * names the class.
 *
 * <p><strong>A reachable container runtime is a prerequisite.</strong> The harness starts a digest-pinned
 * PostgreSQL 16 container and a version-pinned {@code localstack/localstack:4.14.0} container, so an absent
 * daemon or socket blocks this tier outright; the correct report in that case is that the gate is blocked,
 * never an untested pass. Where the host toolchain is incomplete, run the build in the pinned image -
 * {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q verify} - and record the
 * command, the tool versions and the exit code.
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <p>Everything about the runtime environment is inherited from
 * {@code com.cardemo.integration.aws.AbstractAwsIntegrationTest}, which owns both containers, the
 * dynamic property-source wiring, the {@code test} profile and the fixed clock at
 * {@code 2022-06-10T19:27:53Z}. This class declares no container, no profile and no property source, and holds
 * no mutable static state; the only class-level annotation it adds is {@code @AutoConfigureMockMvc}, because
 * the harness runs a mock web environment in which no port is listening, so a mock dispatch through the real
 * filter chain is the only way to exercise the HTTP path without changing the harness.
 *
 * <p>The three diagnostic-context keys are exactly {@code correlationId}, {@code traceId} and {@code spanId},
 * with {@code jobInstanceId} added by the batch layer only. {@code src/main/resources/logback-spring.xml}
 * consumes those spellings literally through a pattern provider whose {@code :-} default yields an empty
 * string, so the fields are always present and a spelling drift empties them silently rather than failing.
 *
 * <p>The {@code test} profile <strong>deliberately neutralises trace export</strong> by excluding the OTLP
 * tracing auto-configuration, while {@code management.tracing.enabled} stays true and the sampling probability
 * stays at one. Spans therefore exist and carry usable identifiers, but nothing leaves the process. Accordingly
 * this class asserts that {@code correlationId} is always present, and that {@code traceId} and {@code spanId}
 * are present when a span is active and absent without error when none is.
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>No Docker socket.</strong> Container startup fails in the harness with an explicit message.
 *       Start a daemon; there is no in-memory substitute.</li>
 *   <li><strong>Testcontainers coordinate trap. Severity Blocker.</strong> At 2.0.3 the module artefacts were
 *       renamed, so the bare {@code postgresql}, {@code localstack} and {@code junit-jupiter} identifiers under
 *       {@code org.testcontainers} do not exist. <em>Remediation, both halves required:</em> override the
 *       managed version through a property rather than importing a second bill of materials, and use only the
 *       prefixed coordinates {@code testcontainers}, {@code testcontainers-postgresql},
 *       {@code testcontainers-localstack} and {@code testcontainers-junit-jupiter}. The root
 *       {@code pom.xml} already does both and is not this file's to edit.</li>
 *   <li><strong>An unused import, a raw type or a redundant cast fails the build,</strong> because compilation
 *       runs with {@code -Xlint:all -Werror} and {@code failOnWarning}.</li>
 *   <li><strong>A diagnostic-context key spelling drift. Severity High.</strong> The JSON field empties with no
 *       error and no output. The assertions here pin all four spellings against the published constants.</li>
 *   <li><strong>A doubly-registered filter</strong> emits two response header values, because a bean
 *       implementing the servlet filter interface auto-registers into the chain and a security configuration
 *       may add it again. The base class is the once-per-request filter and the header is set rather than
 *       added; both are asserted.</li>
 *   <li><strong>A missing {@code finally}. Severity Blocker.</strong> A pooled thread then leaks one request's
 *       identifier into the next request's log records. Asserted directly, and again as an absence of bleed
 *       between two requests carrying different identifiers.</li>
 *   <li><strong>A context that will not start for want of a signing key. Severity High</strong> against
 *       {@code src/main/resources/application-test.yml}, which is owned elsewhere: the harness registers a
 *       non-secret test value dynamically, so this tier starts. Report it, never patch it here.</li>
 *   <li><strong>Four verified prior-run open defects, all Severity High and all closed root-side:</strong> a
 *       hardcoded signing key, an absent production profile, an absent continuous-integration workflow and an
 *       unexecuted vulnerability scan. <strong>Severity Low, out of scope:</strong> the inaccurate service type
 *       in {@code catalog-info.yaml} and the {@code //OEPNFIL} job-name typo at
 *       {@code app/jcl/OPENFIL.jcl:L1}.</li>
 * </ul>
 *
 * <p>One concern per class, as Rule 1 Clause A requires: correlation-identifier lifecycle and outbound
 * propagation. Counters, actuator exposure and health groups belong to the observability and health class of
 * this package; bucket layout, generation keys and the first-in-first-out contract belong to the object-store
 * and queue classes. The queue contract is relied on here and deliberately not re-asserted.
 *
 * <p>The diagnostic context is thread-local and does <strong>not</strong> cross an executor, an asynchronous
 * dispatch or a reactive boundary by itself. Where this class asserts a hand-off it asserts the explicit
 * mechanism - {@code com.cardemo.observability.CorrelationIdFilter#propagate(String)} - and nothing else.
 */
@AutoConfigureMockMvc
@DisplayName("Correlation identifier lifecycle, and its propagation onto outbound cloud calls")
class AwsCorrelationIdPropagationIntegrationTest extends AbstractAwsIntegrationTest {

    // =================================================================================================
    // Contract constants. Every one is deeply immutable and final: no mutable static state exists in this
    // class, per Rule 1 Clause B. The diagnostic-context keys and the header name are declared here and
    // asserted against the published constants of com.cardemo.observability.CorrelationIdFilter, so a
    // rename on either side fails a test rather than emptying a JSON field in silence.
    // =================================================================================================

    /** The correlation key, exactly as {@code logback-spring.xml} reads it. */
    private static final String MDC_KEY_CORRELATION_ID = "correlationId";

    /** The trace key, placed by Micrometer tracing bridged to OpenTelemetry. */
    private static final String MDC_KEY_TRACE_ID = "traceId";

    /** The span key, placed by the same bridge. */
    private static final String MDC_KEY_SPAN_ID = "spanId";

    /**
     * The batch key. This filter is HTTP-scoped and never places it; the batch layer does. Only its spelling
     * and its effect on the log schema are asserted here - no listener, scheduler or job is introduced.
     */
    private static final String MDC_KEY_JOB_INSTANCE_ID = "jobInstanceId";

    /** The inbound and outbound header name. Untrusted on the way in, published on the way out. */
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /** The low-cardinality span tag the filter writes when a span is active. */
    private static final String SPAN_TAG_CORRELATION_ID = "correlation.id";

    /** The published length bound. A value longer than this is replaced, never truncated and kept. */
    private static final int MAX_CORRELATION_ID_LENGTH = 64;

    /**
     * The grammar a published identifier must satisfy: ASCII letters, digits, hyphen and underscore, bounded.
     *
     * <p>Anchored with {@code \A} and {@code \z} and free of nested quantifiers, so it cannot backtrack
     * catastrophically, and compiled once. An unanchored or backtracking pattern in a test is itself a defect.
     */
    private static final Pattern WELL_FORMED_CORRELATION_ID =
            Pattern.compile("\\A[A-Za-z0-9_-]{1," + MAX_CORRELATION_ID_LENGTH + "}\\z");

    /**
     * The standard-user authority.
     *
     * <p>The spelling is the published contract of {@code com.cardemo.security.JwtTokenProvider}, which derives
     * it from the {@code CDEMO-USER-TYPE} {@code 'U'} condition name in {@code app/cpy/COCOM01Y.cpy}. It is
     * restated rather than imported because that class is not a declared dependency of this file.
     */
    private static final String USER_AUTHORITY = "ROLE_USER";

    /** A signed-on identifier in the eight-character shape of {@code app/cpy/CSUSR01Y.cpy}. */
    private static final String SIGNED_ON_USER_ID = "USER0001";

    /** A deterministic well-formed identifier. Determinism first: nothing here asserts a random value. */
    private static final String SUPPLIED_ID = "carddemo-it-correlation-0001";

    /** A second, different well-formed identifier, used to prove that one request cannot bleed into another. */
    private static final String SECOND_SUPPLIED_ID = "carddemo-it-correlation-0002";

    /** A third, used for the outbound legs so a failure names the leg it came from. */
    private static final String OUTBOUND_ID = "carddemo-it-correlation-0003";

    /** Whitespace only. The filter must generate a fresh identifier rather than publish this or a null. */
    private static final String BLANK_HEADER_VALUE = "   ";

    /**
     * A carriage-return and line-feed payload: log injection, and header-injection if it were echoed. The
     * distinctive marker is asserted absent from the response and from captured log output.
     */
    private static final String HOSTILE_CRLF_VALUE = "abc\r\nX-Injected-Header: injected";

    /** The marker that must never appear anywhere once the value above has been refused. */
    private static final String HOSTILE_CRLF_MARKER = "X-Injected-Header";

    /** An escape-sequence payload, which a terminal would interpret rather than print. */
    private static final String HOSTILE_CONTROL_VALUE = "abc\u001b[31mred\u0007def";

    /** A value one character past the bound, which must be replaced rather than shortened. */
    private static final String HOSTILE_OVER_LONG_VALUE = "A".repeat(MAX_CORRELATION_ID_LENGTH + 1);

    /** A value outside the permitted character set. */
    private static final String HOSTILE_OUT_OF_CHARSET_VALUE = "abc def/ghi=jkl;mno";

    /** A protected route, used where an unauthenticated request must still be correlated. */
    private static final String PROTECTED_PATH = "/api/transactions";

    /** An administrator-only route, used where an authenticated non-administrator must still be correlated. */
    private static final String ADMIN_ONLY_PATH = "/api/admin/users";

    /** The report-submission route, which is the one online path that publishes to the queue. */
    private static final String REPORT_SUBMISSION_PATH = "/api/reports";

    /**
     * The smallest body that reaches the publish.
     *
     * <p>Two of the seventeen fields of {@code app/cpy-bms/CORPT00.CPY} are populated: the monthly selector,
     * which {@code PROCESS-ENTER-KEY} evaluates first at {@code app/cbl/CORPT00C.cbl:212-236}, and the
     * confirmation gate at {@code :464}. The period is then derived from the injected fixed clock, so the
     * published message is byte-identical on every run. Sent as a literal rather than built from the request
     * type, which is not a declared dependency of this file.
     */
    private static final String MONTHLY_REPORT_REQUEST_BODY =
            "{\"monthlySelected\":\"Y\",\"confirmation\":\"Y\"}";

    /**
     * The preserved legacy rendering of {@code 9910-DISPLAY-IO-STATUS} for file status {@code '23'}.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:721} and {@code :725} both emit
     * {@code DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04}, and the {@code ELSE} arm moves {@code '0000'} into
     * the four-character field and then the two status characters into positions three and four. Nothing in the
     * logging pipeline may reformat, wrap, truncate or mask it.
     */
    private static final String PARITY_FILE_STATUS_LINE = "FILE STATUS IS: NNNN0023";

    /** A synthetic, non-real credential value, used only to prove that a labelled value is redacted. */
    private static final String SYNTHETIC_CREDENTIAL_VALUE = "not-a-real-credential-0000";

    /**
     * A synthetic value in the shape of a BCrypt hash: the prefix, a two-digit cost and fifty-three characters
     * of salt and digest. It hashes nothing and verifies nothing; only its shape matters.
     */
    private static final String SYNTHETIC_BCRYPT_SHAPED_VALUE =
            "$2a$10$AbCdEfGhIjKlMnOpQrStUv0123456789abcdefghijABCDEFGHIJK";

    /** A synthetic value in the dashed nine-digit shape the customer layout carries at {@code CUST-SSN}. */
    private static final String SYNTHETIC_SSN_SHAPED_VALUE = "123-45-6789";

    /**
     * A synthetic value in compact bearer-token shape, anchored on the header prefix a real one starts with.
     * It carries no claims and no signature, so presenting it can only be refused.
     */
    private static final String SYNTHETIC_BEARER_CREDENTIAL =
            "eyJ0ZXN0aGVhZGVyMDAw.c3ludGhldGljMDAw.bm90YXNpZ25hdHVyZQ";

    /** The redaction marker for a labelled credential. */
    private static final String REDACTION = "[REDACTED]";

    /** The redaction marker for a hash. */
    private static final String REDACTION_BCRYPT = "[REDACTED_BCRYPT_HASH]";

    /** The redaction marker for a social security number. */
    private static final String REDACTION_SSN = "[REDACTED_SSN]";

    /** The redaction marker for a bearer token. */
    private static final String REDACTION_JWT = "[REDACTED_JWT]";

    /** The appender name {@code logback-spring.xml} declares, and the only one it declares. */
    private static final String CONSOLE_APPENDER_NAME = "CONSOLE";

    /** The logger the production code of this application logs through, pinned at info by configuration. */
    private static final String APPLICATION_LOGGER_NAME = "com.cardemo";

    /** A distinct logger for encoder probes, so a probe event is never confused with a production one. */
    private static final String PROBE_LOGGER_NAME = "com.cardemo.integration.aws.correlation-probe";

    /**
     * A reserved, unresolvable host for the synthetic outbound request the interceptor is invoked against.
     *
     * <p>It names no environment and is never contacted: the request object is handed to the interceptor and
     * discarded. Rule 1 Clause C forbids an environment-specific literal, and the emulator address is the
     * harness's to inject, never this file's to write down.
     */
    private static final String SYNTHETIC_OUTBOUND_HOST = "carddemo-emulator.invalid";

    /** The bounded wait for a queue receive. Bounded rather than spinning, per Rule 1 Clause A. */
    private static final Duration RECEIVE_POLL_TIMEOUT = Duration.ofSeconds(10);

    /** How many messages one receive may drain, so a stale message cannot hide the one under test. */
    private static final int RECEIVE_BATCH_SIZE = 10;

    /** How many bounded receives are attempted before the assertion fails. */
    private static final int RECEIVE_ATTEMPTS = 3;

    /** The bounded join for the one deliberate thread hand-off. */
    private static final long HANDOFF_JOIN_TIMEOUT_MILLIS = 10_000L;

    /** The payload of the outbound object-store probe, small and fixed. */
    private static final String OBJECT_PROBE_PAYLOAD = "correlation-propagation-probe";

    // =================================================================================================
    // Collaborators. Injected, never constructed: com.cardemo.config.AwsConfig owns the cloud clients and
    // the emulator endpoint override, and nothing here supplies a region, an endpoint or a credential.
    // =================================================================================================

    /** Dispatches through the real servlet filter chain in the harness's mock web environment. */
    @Autowired
    private MockMvc mockMvc;

    /** The filter under test, as the container registered it. */
    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    /** Every bean of the filter's type, so double registration is provable rather than assumed. */
    @Autowired
    private Map<String, CorrelationIdFilter> correlationIdFilterBeans;

    /** The tracer the filter reads. Injected so the test and the filter agree on one tracing implementation. */
    @Autowired
    private Tracer tracer;

    /** Every security chain, so the filter's absence from them is provable. */
    @Autowired
    private List<SecurityFilterChain> securityFilterChains;


    // =================================================================================================
    // Shared helpers. Each one is small, explicit and free of hidden state. None of them swallows an
    // exception: where a helper cannot do its job it fails the assertion that called it, with the reason.
    // =================================================================================================

    /**
     * Takes an immutable snapshot of the calling thread's diagnostic context.
     *
     * @return the entries in scope, empty when none are; never {@code null}
     */
    private static Map<String, String> diagnosticContextSnapshot() {
        final Map<String, String> inScope = MDC.getCopyOfContextMap();
        return inScope == null ? Map.of() : Map.copyOf(inScope);
    }

    /**
     * Asserts that none of the three correlation keys is in scope on the calling thread.
     *
     * <p>Absence of the three specific keys is asserted rather than emptiness of the whole map, so that a
     * legitimate unrelated entry placed by some other component cannot fail this check.
     *
     * @param stage what the caller was doing, so a failure says when the leak happened
     */
    private static void assertCorrelationKeysAbsent(final String stage) {
        final Map<String, String> inScope = diagnosticContextSnapshot();
        assertThat(inScope)
                .as("%s: the filter clears the correlation trio in a finally block. An entry surviving here "
                        + "would leak one request's identity into the next request that runs on this pooled "
                        + "thread, which is the failure the finally block exists to prevent", stage)
                .doesNotContainKeys(MDC_KEY_CORRELATION_ID, MDC_KEY_TRACE_ID, MDC_KEY_SPAN_ID);
    }

    /**
     * Attaches a fresh in-process list appender to a logger and returns it.
     *
     * <p>In-process capture is the only mechanism used anywhere in this class: no log file is written and no
     * container log is read.
     *
     * @param loggerName the logger to observe
     * @return the started appender, which the caller must detach in a {@code finally} block
     */
    private static ListAppender<ILoggingEvent> attachAppender(final String loggerName) {
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setName("correlation-capture-" + loggerName);
        appender.setContext(logbackLogger(loggerName).getLoggerContext());
        appender.start();
        logbackLogger(loggerName).addAppender(appender);
        return appender;
    }

    /**
     * Detaches and stops an appender attached by {@link #attachAppender(String)}.
     *
     * @param loggerName the logger it was attached to
     * @param appender   the appender to remove
     */
    private static void detachAppender(final String loggerName, final Appender<ILoggingEvent> appender) {
        logbackLogger(loggerName).detachAppender(appender);
        appender.stop();
    }

    /**
     * Resolves a logger to its implementation type so that an appender can be attached to it.
     *
     * @param loggerName the logger name
     * @return the implementation-typed logger
     */
    private static Logger logbackLogger(final String loggerName) {
        final org.slf4j.Logger candidate = LoggerFactory.getLogger(loggerName);
        assertThat(candidate)
                .as("the logging backend must be the one logback-spring.xml configures, because the masking "
                        + "rules and the field set under test live in its encoder")
                .isInstanceOf(Logger.class);
        return (Logger) candidate;
    }

    /**
     * Renders one captured event through the encoder the running application actually uses.
     *
     * <p>This matters for correctness, not convenience. The masking layer of
     * {@code src/main/resources/logback-spring.xml} is a JSON generator decorator on the console appender's
     * encoder, so it is invisible to a list appender, which sees the raw event. Encoding a captured event with
     * the production encoder is therefore the only way to assert what an operator would actually read.
     *
     * @param event the captured event
     * @return the encoded line
     */
    private static String encodeWithProductionEncoder(final ILoggingEvent event) {
        final Appender<ILoggingEvent> console =
                logbackLogger(Logger.ROOT_LOGGER_NAME).getAppender(CONSOLE_APPENDER_NAME);
        assertThat(console)
                .as("logback-spring.xml declares exactly one appender, named %s, and attaches it to the root "
                        + "logger. Its absence means the configuration was not applied to this context, so "
                        + "nothing downstream of it - the field set or the masking rules - can be asserted",
                        CONSOLE_APPENDER_NAME)
                .isInstanceOf(OutputStreamAppender.class);

        final Encoder<ILoggingEvent> encoder = ((OutputStreamAppender<ILoggingEvent>) console).getEncoder();
        assertThat(encoder).as("the console appender must carry the structured encoder").isNotNull();
        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }

    /**
     * Returns the single event a probe appender captured.
     *
     * @param appender the appender the probe logged through
     * @return the one captured event
     */
    private static ILoggingEvent singleCapturedEvent(final ListAppender<ILoggingEvent> appender) {
        assertThat(appender.list)
                .as("the probe must have produced exactly one event; a different count means the logger level "
                        + "or the appender attachment is not what this assertion assumes")
                .hasSize(1);
        return appender.list.get(0);
    }

    /**
     * Logs one probe message at info level and returns the event, encoded by the production encoder.
     *
     * @param message the message to log, always a synthetic value assembled by this class
     * @return the encoded line an operator would read
     */
    private static String encodedProbeLine(final String message) {
        final ListAppender<ILoggingEvent> appender = attachAppender(PROBE_LOGGER_NAME);
        try {
            final Logger probe = logbackLogger(PROBE_LOGGER_NAME);
            assertThat(probe.isEnabledFor(Level.INFO))
                    .as("the probe logger inherits info from logging.level.com.cardemo, so an info event must "
                            + "be enabled for it")
                    .isTrue();
            probe.info(message);
            return encodeWithProductionEncoder(singleCapturedEvent(appender));
        } finally {
            detachAppender(PROBE_LOGGER_NAME, appender);
        }
    }

    /**
     * Collects the formatted messages of every captured event.
     *
     * @param appender the appender that captured them
     * @return one string per event
     */
    private static List<String> formattedMessagesOf(final ListAppender<ILoggingEvent> appender) {
        final List<String> messages = new ArrayList<>(appender.list.size());
        for (final ILoggingEvent event : appender.list) {
            messages.add(event.getFormattedMessage());
        }
        return messages;
    }

    /**
     * Collects the correlation identifiers the captured events carry in their diagnostic-context snapshots.
     *
     * <p>An event's snapshot is taken when the event is created, so this is evidence of what was in scope
     * <em>during</em> the request rather than after it.
     *
     * @param appender the appender that captured them
     * @return one entry per event, with {@code "<absent>"} where the event carried none
     */
    private static List<String> correlationIdsOf(final ListAppender<ILoggingEvent> appender) {
        final List<String> identifiers = new ArrayList<>(appender.list.size());
        for (final ILoggingEvent event : appender.list) {
            final String identifier = event.getMDCPropertyMap().get(MDC_KEY_CORRELATION_ID);
            identifiers.add(identifier == null ? "<absent>" : identifier);
        }
        return identifiers;
    }

    /**
     * Drives the filter directly and returns the diagnostic context as it stood inside the chain.
     *
     * <p>A direct drive is used only where a mock dispatch cannot reach the case at all. The servlet
     * observation filter opens a span ahead of this one on every mock dispatch, so the no-active-span path -
     * which must not throw and must not place a trace entry - is unreachable through the dispatcher and
     * reachable here.
     *
     * @param request  the request to filter
     * @param response the response to filter onto
     * @return the entries in scope at the moment the chain was invoked
     * @throws Exception if the filter itself fails, which is a failure this test does not absorb
     */
    private Map<String, String> filterAndCaptureContext(final MockHttpServletRequest request,
            final MockHttpServletResponse response) throws Exception {

        final List<Map<String, String>> captured = new ArrayList<>(1);
        correlationIdFilter.doFilter(request, response,
                (chainRequest, chainResponse) -> captured.add(diagnosticContextSnapshot()));

        assertThat(captured)
                .as("the filter must invoke the rest of the chain exactly once; it is a once-per-request "
                        + "filter and a second pass would be the double-registration defect")
                .hasSize(1);
        return captured.get(0);
    }

    /**
     * Builds a request carrying the inbound correlation header, or none when the value is {@code null}.
     *
     * @param suppliedHeaderValue the untrusted header value, or {@code null} to omit the header entirely
     * @return the request
     */
    private static MockHttpServletRequest requestWithSuppliedHeader(final String suppliedHeaderValue) {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", PROTECTED_PATH);
        if (suppliedHeaderValue != null) {
            request.addHeader(CORRELATION_ID_HEADER, suppliedHeaderValue);
        }
        return request;
    }

    /**
     * Locates the correlation interceptor among the interceptors a built client actually carries.
     *
     * <p>The list is read from the client the container injected. No client is constructed here and no
     * interceptor is registered here: {@code com.cardemo.config.AwsConfig} owns both, and this only observes
     * the result.
     *
     * @param interceptors      the interceptors registered on the client
     * @param clientDescription the client, for the failure message
     * @return the registered interceptor instance, so its behaviour is asserted on the very object that will
     *         run on a real call
     */
    private static AwsConfig.CorrelationIdExecutionInterceptor correlationInterceptorOf(
            final List<ExecutionInterceptor> interceptors, final String clientDescription) {

        final List<AwsConfig.CorrelationIdExecutionInterceptor> matches = new ArrayList<>(1);
        for (final ExecutionInterceptor interceptor : interceptors) {
            if (interceptor instanceof AwsConfig.CorrelationIdExecutionInterceptor correlationInterceptor) {
                matches.add(correlationInterceptor);
            }
        }

        assertThat(matches)
                .as("the %s must carry exactly one correlation interceptor. Without it the thread of identity "
                        + "stops at the edge of this process and an object write or a queue publish carries no "
                        + "identity at all; with two, the header would be written twice", clientDescription)
                .hasSize(1);
        return matches.get(0);
    }

    /**
     * Builds a synthetic outbound request for the interceptor to modify.
     *
     * <p>The host is a reserved, unresolvable name and the object is never sent: it exists so that the
     * interceptor's own contract - add the header when an identifier is in scope, change nothing otherwise -
     * can be asserted on the registered instance without reaching any endpoint.
     *
     * @return the synthetic request
     */
    private static SdkHttpRequest synthesiseOutboundRequest() {
        return SdkHttpRequest.builder()
                .method(SdkHttpMethod.PUT)
                .protocol("http")
                .host(SYNTHETIC_OUTBOUND_HOST)
                .port(1)
                .encodedPath("/")
                .build();
    }

    /**
     * Wraps a synthetic request in the callback context the interceptor is handed.
     *
     * <p>Implemented explicitly rather than mocked, so that what the interceptor sees is stated in full and no
     * stubbing framework stands between the assertion and the behaviour.
     *
     * @param httpRequest the request the interceptor may modify
     * @return the context
     */
    private static Context.ModifyHttpRequest interceptorContextFor(final SdkHttpRequest httpRequest) {
        return new Context.ModifyHttpRequest() {

            @Override
            public SdkRequest request() {
                return ListBucketsRequest.builder().build();
            }

            @Override
            public SdkHttpRequest httpRequest() {
                return httpRequest;
            }

            @Override
            public Optional<RequestBody> requestBody() {
                return Optional.empty();
            }

            @Override
            public Optional<AsyncRequestBody> asyncRequestBody() {
                return Optional.empty();
            }
        };
    }

    /**
     * Receives from the report queue until a message carrying the given identifier is found.
     *
     * <p>Bounded on both axes - a fixed number of receives, each with a fixed poll timeout - so a queue that
     * never delivers fails the assertion instead of hanging, and nothing spins. Draining rather than peeking is
     * deliberate: a message left by an earlier assertion must not be able to hide the one under test.
     *
     * @param correlationId the identifier the message must carry
     * @return the matching message, or empty when none arrived within the bound
     */
    private Optional<Message<?>> receiveMessageCorrelatedWith(final String correlationId) {
        for (int attempt = 0; attempt < RECEIVE_ATTEMPTS; attempt++) {
            final Collection<Message<?>> batch = sqsTemplate().receiveMany(options -> options
                    .queue(reportQueueName())
                    .maxNumberOfMessages(RECEIVE_BATCH_SIZE)
                    .pollTimeout(RECEIVE_POLL_TIMEOUT));

            for (final Message<?> message : batch) {
                if (correlationId.equals(headerValueOf(message, CORRELATION_ID_HEADER))) {
                    return Optional.of(message);
                }
            }
            if (batch.isEmpty()) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Reads one header from a received message.
     *
     * @param message    the received message
     * @param headerName the header to read
     * @return the value, or {@code null} when the header is absent
     */
    private static String headerValueOf(final Message<?> message, final String headerName) {
        final Object value = message.getHeaders().get(headerName);
        return value == null ? null : value.toString();
    }


    /**
     * Lowers a logger's threshold so that production lines written below info can be observed, returning the
     * previous setting.
     *
     * <p>Used only to make a negative assertion meaningful: a value that is never logged at all is not proof
     * that it would not be logged at a more verbose level, and the primary defence being asserted is precisely
     * that production code does not write the value in the first place.
     *
     * @param loggerName the logger to widen
     * @param level      the level to widen it to
     * @return the previous explicit level, possibly {@code null} where the level was inherited
     */
    private static Level widenLevelOf(final String loggerName, final Level level) {
        final Logger logger = logbackLogger(loggerName);
        final Level previous = logger.getLevel();
        logger.setLevel(level);
        return previous;
    }

    /**
     * Restores a logger's threshold, including restoring inheritance when the previous value was {@code null}.
     *
     * @param loggerName the logger to restore
     * @param previous   the value {@link #widenLevelOf(String, Level)} returned
     */
    private static void restoreLevelOf(final String loggerName, final Level previous) {
        logbackLogger(loggerName).setLevel(previous);
    }

    /**
     * Builds the authentication a signed-on standard user would carry.
     *
     * <p>Shaped exactly as {@code com.cardemo.security.JwtAuthenticationFilter} shapes it on a successful bearer
     * verification: an authenticated principal named by the eight-character user identifier of
     * {@code app/cpy/CSUSR01Y.cpy}, carrying one role authority derived from the {@code CDEMO-USER-TYPE}
     * condition names of {@code app/cpy/COCOM01Y.cpy}.
     *
     * <p>It is supplied through the test post-processor rather than as a signed bearer token for a reason worth
     * recording. {@code com.cardemo.security.JwtTokenProvider} stamps the issued-at and expiry claims from the
     * injected clock, which this harness fixes at {@code 2022-06-10T19:27:53Z}, while
     * {@code com.cardemo.config.SecurityConfig} validates timestamps against the wall clock. A token minted by
     * the bean is therefore always expired, and every request carrying one would answer 401 - which would make
     * the 403 case unreachable and prove nothing about correlation. Bearer verification itself is asserted by
     * the security unit tier; this class asserts correlation, so it establishes the principal by the shortest
     * honest route. The session policy is stateless, so the post-processor stores the context in a request
     * attribute and no session is created; that is asserted rather than assumed.
     *
     * @return the authentication for a standard, non-administrator user
     */
    private static UsernamePasswordAuthenticationToken standardUser() {
        return UsernamePasswordAuthenticationToken.authenticated(SIGNED_ON_USER_ID, null,
                List.of(new SimpleGrantedAuthority(USER_AUTHORITY)));
    }

    // =================================================================================================
    // The published contract: the four key spellings, the header name, the bound, and single registration.
    // =================================================================================================

    /** The literals the whole application shares, and the proof that the filter runs once per request. */
    @Nested
    @DisplayName("the published contract of the correlation filter")
    class PublishedContract {

        @Test
        @DisplayName("the diagnostic-context keys are exactly correlationId, traceId and spanId")
        void theKeySpellingsAreExact() {
            assertThat(CorrelationIdFilter.MDC_KEY_CORRELATION_ID)
                    .as("logback-spring.xml reads %s literally through a pattern provider whose default yields "
                            + "an empty string, so a rename empties the JSON field with no error and no "
                            + "output rather than failing. Severity High", MDC_KEY_CORRELATION_ID)
                    .isEqualTo(MDC_KEY_CORRELATION_ID);
            assertThat(CorrelationIdFilter.MDC_KEY_TRACE_ID).isEqualTo(MDC_KEY_TRACE_ID);
            assertThat(CorrelationIdFilter.MDC_KEY_SPAN_ID).isEqualTo(MDC_KEY_SPAN_ID);
        }

        @Test
        @DisplayName("the batch job-instance key is published here and left to the batch layer to place")
        void theBatchKeyIsPublishedButNotPlacedHere() {
            assertThat(CorrelationIdFilter.MDC_KEY_JOB_INSTANCE_ID)
                    .as("the batch layer places this key, and the same number names the per-run object prefix "
                            + "that replaces a relative generation reference, which is what makes a run's log "
                            + "and its output objects correlatable. This filter is HTTP-scoped and never "
                            + "places it, so only the spelling is a contract here")
                    .isEqualTo(MDC_KEY_JOB_INSTANCE_ID);

            assertCorrelationKeysAbsent("before any request has run");
            assertThat(diagnosticContextSnapshot())
                    .as("no batch key may be in scope in this tier: no job execution listener, scheduler or "
                            + "listener of any kind is introduced by this class")
                    .doesNotContainKey(MDC_KEY_JOB_INSTANCE_ID);
        }

        @Test
        @DisplayName("the header name, the length bound and the span tag are the published contract")
        void theHeaderAndBoundAreTheContract() {
            assertThat(CorrelationIdFilter.CORRELATION_ID_HEADER).isEqualTo(CORRELATION_ID_HEADER);
            assertThat(CorrelationIdFilter.MAX_CORRELATION_ID_LENGTH)
                    .as("the identifier is bounded so a hostile value cannot grow a log line or a message "
                            + "attribute without limit")
                    .isEqualTo(MAX_CORRELATION_ID_LENGTH);
            assertThat(CorrelationIdFilter.SPAN_TAG_CORRELATION_ID)
                    .as("a fixed tag name keeps the key low-cardinality in the tracing backend")
                    .isEqualTo(SPAN_TAG_CORRELATION_ID);
        }

        @Test
        @DisplayName("exactly one filter bean exists and no security chain registers it a second time")
        void theFilterIsRegisteredExactlyOnce() {
            assertThat(correlationIdFilterBeans)
                    .as("a bean implementing the servlet filter interface auto-registers into the chain. A "
                            + "second bean, or a second registration, would run the filter twice per request")
                    .hasSize(1);

            for (final SecurityFilterChain chain : securityFilterChains) {
                assertThat(chain.getFilters())
                        .as("the security configuration must not add the correlation filter to its own chain: "
                                + "it is already in the servlet chain ahead of security, which is what keeps a "
                                + "refused request correlated. Adding it again is the double-registration "
                                + "defect that the once-per-request base class exists to survive")
                        .noneMatch(CorrelationIdFilter.class::isInstance);
            }
        }
    }

    // =================================================================================================
    // The lifecycle: placed for the duration of the request, removed in a finally block, never bleeding.
    // =================================================================================================

    /** Placement, removal and the absence of bleed between requests on one thread. */
    @Nested
    @DisplayName("the diagnostic-context lifecycle")
    class DiagnosticContextLifecycle {

        @Test
        @DisplayName("the identifier is in scope inside the chain and gone once the filter returns")
        void theIdentifierIsScopedToTheRequest() throws Exception {
            assertCorrelationKeysAbsent("before the request");

            final MockHttpServletResponse response = new MockHttpServletResponse();
            final Map<String, String> insideChain =
                    filterAndCaptureContext(requestWithSuppliedHeader(SUPPLIED_ID), response);

            assertThat(insideChain)
                    .as("everything downstream of the filter - the controllers, the services and the cloud "
                            + "clients - reads the identifier from the diagnostic context, so it has to be in "
                            + "scope for the whole of the chain")
                    .containsEntry(MDC_KEY_CORRELATION_ID, SUPPLIED_ID);
            assertThat(response.getHeader(CORRELATION_ID_HEADER))
                    .as("the identifier is published on the response before the chain runs, so a caller can "
                            + "quote it even when the request is refused downstream")
                    .isEqualTo(SUPPLIED_ID);

            assertCorrelationKeysAbsent("after the filter returned");
        }

        @Test
        @DisplayName("all three keys are removed on the same thread once the request ends")
        void everyKeyIsRemovedAfterTheRequest() throws Exception {
            mockMvc.perform(get(PROTECTED_PATH).header(CORRELATION_ID_HEADER, SUPPLIED_ID));

            assertCorrelationKeysAbsent("after a dispatch through the whole filter chain");
        }

        @Test
        @DisplayName("one request cannot bleed its identifier into the next on the same thread")
        void oneRequestCannotBleedIntoTheNext() throws Exception {
            final MockHttpServletResponse firstResponse = new MockHttpServletResponse();
            final Map<String, String> firstContext =
                    filterAndCaptureContext(requestWithSuppliedHeader(SUPPLIED_ID), firstResponse);
            final MockHttpServletResponse secondResponse = new MockHttpServletResponse();
            final Map<String, String> secondContext =
                    filterAndCaptureContext(requestWithSuppliedHeader(SECOND_SUPPLIED_ID), secondResponse);

            assertThat(firstContext).containsEntry(MDC_KEY_CORRELATION_ID, SUPPLIED_ID);
            assertThat(secondContext)
                    .as("the second request must see only its own identifier. Seeing the first request's is "
                            + "the leak a missing finally block produces on a pooled thread, and it makes "
                            + "every log line after the first request attribute work to the wrong caller")
                    .containsEntry(MDC_KEY_CORRELATION_ID, SECOND_SUPPLIED_ID);
            assertThat(secondResponse.getHeader(CORRELATION_ID_HEADER)).isEqualTo(SECOND_SUPPLIED_ID);
            assertCorrelationKeysAbsent("after two consecutive requests");
        }

        @Test
        @DisplayName("a thread hand-off carries the identifier only through the published mechanism")
        void aThreadHandOffNeedsTheExplicitMechanism() throws Exception {
            final List<String> seenWithoutMechanism = new ArrayList<>(1);
            final List<String> seenWithMechanism = new ArrayList<>(1);

            final String previous = CorrelationIdFilter.propagate(SUPPLIED_ID);
            try {
                final Thread withoutMechanism = new Thread(
                        () -> seenWithoutMechanism.add(String.valueOf(CorrelationIdFilter.currentCorrelationId())),
                        "carddemo-handoff-without-mechanism");
                withoutMechanism.start();
                withoutMechanism.join(HANDOFF_JOIN_TIMEOUT_MILLIS);

                final Thread withMechanism = new Thread(() -> {
                    final String adopted = CorrelationIdFilter.propagate(SUPPLIED_ID);
                    try {
                        seenWithMechanism.add(String.valueOf(CorrelationIdFilter.currentCorrelationId()));
                    } finally {
                        CorrelationIdFilter.propagate(adopted);
                    }
                }, "carddemo-handoff-with-mechanism");
                withMechanism.start();
                withMechanism.join(HANDOFF_JOIN_TIMEOUT_MILLIS);

                assertThat(withoutMechanism.isAlive()).as("the hand-off must finish inside its bound").isFalse();
                assertThat(withMechanism.isAlive()).as("the hand-off must finish inside its bound").isFalse();

                assertThat(seenWithoutMechanism)
                        .as("the diagnostic context is thread-local and is not inherited, so a hand-off that "
                                + "does nothing explicit sees nothing. This is the documented limit of the "
                                + "mechanism, not a defect")
                        .containsExactly("null");
                assertThat(seenWithMechanism)
                        .as("the published propagation method is the whole of the cross-thread surface, and it "
                                + "returns what it replaced so the receiving thread can put things back")
                        .containsExactly(SUPPLIED_ID);
                assertThat(CorrelationIdFilter.currentCorrelationId())
                        .as("neither hand-off may disturb the thread that started them")
                        .isEqualTo(SUPPLIED_ID);
            } finally {
                CorrelationIdFilter.propagate(previous);
            }

            assertCorrelationKeysAbsent("after the hand-off scope was restored");
        }
    }


    // =================================================================================================
    // The inbound header is untrusted. Four arrival shapes, and four distinct attacks inside the third.
    // =================================================================================================

    /** Absent, blank, malformed and well formed, with every malformed value discarded rather than repaired. */
    @Nested
    @DisplayName("the untrusted inbound correlation header")
    class UntrustedInboundHeader {

        /**
         * Drives the filter with one supplied header value and returns the identifier it published.
         *
         * <p>Also asserts the two invariants that hold for every arrival shape: whatever is published is what
         * the rest of the chain sees, and whatever is published satisfies the grammar. A value that failed the
         * grammar would be a value an operator could not trust in a log line or a message attribute.
         *
         * @param suppliedHeaderValue the untrusted value, or {@code null} to omit the header
         * @return the published identifier
         * @throws Exception if the filter fails, which would itself be the defect
         */
        private String publishedIdentifierFor(final String suppliedHeaderValue) throws Exception {
            final MockHttpServletResponse response = new MockHttpServletResponse();
            final Map<String, String> insideChain =
                    filterAndCaptureContext(requestWithSuppliedHeader(suppliedHeaderValue), response);

            final String published = response.getHeader(CORRELATION_ID_HEADER);
            assertThat(published)
                    .as("an identifier is published on every response, whatever arrived: a request with no "
                            + "identity is the one an operator cannot trace")
                    .isNotNull();
            assertThat(published)
                    .as("the published identifier must satisfy the bounded, anchored grammar of ASCII letters, "
                            + "digits, hyphen and underscore, because it is written into a header, a log line "
                            + "and a message attribute, all of which are text protocols")
                    .matches(WELL_FORMED_CORRELATION_ID);
            assertThat(insideChain)
                    .as("the value in scope for the chain and the value on the response must be the same one")
                    .containsEntry(MDC_KEY_CORRELATION_ID, published);
            return published;
        }

        @Test
        @DisplayName("an absent header yields a freshly generated identifier")
        void anAbsentHeaderYieldsAFreshIdentifier() throws Exception {
            final String published = publishedIdentifierFor(null);

            assertThat(published)
                    .as("a caller that supplies nothing still has to be traceable, so the filter generates")
                    .isNotBlank();
        }

        @Test
        @DisplayName("a whitespace-only header yields a freshly generated identifier, not a blank and not a null")
        void aBlankHeaderYieldsAFreshIdentifier() throws Exception {
            final String published = publishedIdentifierFor(BLANK_HEADER_VALUE);

            assertThat(published)
                    .as("a blank value is the empty-input boundary case, and publishing it would leave every "
                            + "log record for the request carrying a present-but-meaningless identifier")
                    .isNotBlank()
                    .isNotEqualTo(BLANK_HEADER_VALUE);
        }

        @Test
        @DisplayName("a well-formed header is accepted and propagated unchanged")
        void aWellFormedHeaderIsAcceptedUnchanged() throws Exception {
            final String published = publishedIdentifierFor(SUPPLIED_ID);

            assertThat(published)
                    .as("accepting a well-formed inbound identifier unchanged is what lets a caller join its "
                            + "own records to this application's")
                    .isEqualTo(SUPPLIED_ID);
        }

        @Test
        @DisplayName("a value carrying a carriage return and line feed is discarded, never sanitised and kept")
        void aCarriageReturnAndLineFeedValueIsDiscarded() throws Exception {
            final String published = publishedIdentifierFor(HOSTILE_CRLF_VALUE);

            assertThat(published)
                    .as("a carriage return in an echoed header is response splitting, and in a log line it is "
                            + "log injection: the whole value is refused and a fresh one generated, so nothing "
                            + "derived from it survives")
                    .doesNotContain("\r")
                    .doesNotContain("\n")
                    .doesNotContain(HOSTILE_CRLF_MARKER);
            assertThat(published.length())
                    .as("the published identifier is bounded")
                    .isLessThanOrEqualTo(MAX_CORRELATION_ID_LENGTH);
        }

        @Test
        @DisplayName("a value carrying escape and control characters is discarded")
        void anEscapeSequenceValueIsDiscarded() throws Exception {
            final String published = publishedIdentifierFor(HOSTILE_CONTROL_VALUE);

            for (int index = 0; index < published.length(); index++) {
                assertThat((int) published.charAt(index))
                        .as("no control character may reach a terminal that renders this log, so the grammar "
                                + "admits none and the whole value is replaced rather than stripped")
                        .isGreaterThan(0x1F);
            }
        }

        @Test
        @DisplayName("a value longer than the bound is replaced, not truncated and kept")
        void anOverLongValueIsReplacedRatherThanTruncated() throws Exception {
            final String published = publishedIdentifierFor(HOSTILE_OVER_LONG_VALUE);

            assertThat(published.length()).isLessThanOrEqualTo(MAX_CORRELATION_ID_LENGTH);
            assertThat(published)
                    .as("a truncated identifier is worse than a fresh one: it looks as though it correlates "
                            + "and correlates to nothing, so the bound is a refusal and not a shortening")
                    .isNotEqualTo(HOSTILE_OVER_LONG_VALUE.substring(0, MAX_CORRELATION_ID_LENGTH));
        }

        @Test
        @DisplayName("a value outside the permitted character set is discarded")
        void anOutOfCharsetValueIsDiscarded() throws Exception {
            final String published = publishedIdentifierFor(HOSTILE_OUT_OF_CHARSET_VALUE);

            assertThat(published)
                    .as("the character set is an allow list of ASCII letters, digits, hyphen and underscore, "
                            + "so a value carrying a space, a slash, an equals sign or a semicolon is refused "
                            + "whole rather than filtered down to its acceptable characters")
                    .doesNotContain(" ")
                    .doesNotContain("/")
                    .doesNotContain("=")
                    .doesNotContain(";");
        }

        @Test
        @DisplayName("a hostile value is neither echoed on the response nor written to any log record")
        void noHostileValueIsEchoedOrLogged() throws Exception {
            final Level previousLevel = widenLevelOf(APPLICATION_LOGGER_NAME, Level.TRACE);
            final ListAppender<ILoggingEvent> captured = attachAppender(APPLICATION_LOGGER_NAME);
            try {
                final MvcResult result = mockMvc.perform(get(PROTECTED_PATH)
                                .header(CORRELATION_ID_HEADER, HOSTILE_CRLF_VALUE))
                        .andReturn();

                assertThat(result.getResponse().getStatus())
                        .as("a hostile correlation header must never fail the request: it is replaced, and the "
                                + "request is then answered on its own merits, which here is a refusal for "
                                + "want of a credential")
                        .isEqualTo(401);
                assertThat(result.getResponse().getHeaderValues(CORRELATION_ID_HEADER))
                        .as("exactly one header value, set rather than added")
                        .hasSize(1);
                assertThat(result.getResponse().getHeader(CORRELATION_ID_HEADER))
                        .matches(WELL_FORMED_CORRELATION_ID);

                assertThat(formattedMessagesOf(captured))
                        .as("the raw inbound value is never written anywhere, which is the primary defence. "
                                + "Masking is the second line and is asserted separately; a value that is "
                                + "never emitted needs no masking at all")
                        .noneMatch(message -> message.contains(HOSTILE_CRLF_MARKER));
            } finally {
                detachAppender(APPLICATION_LOGGER_NAME, captured);
                restoreLevelOf(APPLICATION_LOGGER_NAME, previousLevel);
            }
        }
    }

    // =================================================================================================
    // A refused request is the one that most needs an identifier, and it must create no server-side state.
    // =================================================================================================

    /** The 401 and 403 arms of the chain, and the stateless session policy. */
    @Nested
    @DisplayName("refused requests, and the stateless session policy")
    class RefusedRequestsAndSessionPolicy {

        @Test
        @DisplayName("an unauthenticated request is refused with 401 and is still correlated")
        void anUnauthenticatedRequestIsStillCorrelated() throws Exception {
            final MvcResult result = mockMvc.perform(get(PROTECTED_PATH)
                            .header(CORRELATION_ID_HEADER, SUPPLIED_ID))
                    .andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("every route but sign-on requires a credential")
                    .isEqualTo(401);
            assertThat(result.getResponse().getHeaderValues(CORRELATION_ID_HEADER))
                    .as("the correlation filter is ordered ahead of the security chain, so a request refused "
                            + "by security has already been given an identity. Order it after security and the "
                            + "requests an operator most needs to trace are the ones with none")
                    .containsExactly(SUPPLIED_ID);
        }

        @Test
        @DisplayName("an authenticated non-administrator is refused with 403 and is still correlated")
        void anAuthenticatedNonAdministratorIsStillCorrelated() throws Exception {
            final MvcResult result = mockMvc.perform(get(ADMIN_ONLY_PATH)
                            .with(authentication(standardUser()))
                            .header(CORRELATION_ID_HEADER, SECOND_SUPPLIED_ID))
                    .andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("the administration surface is restricted to the administrator authority, which the "
                            + "'A' condition name of app/cpy/COCOM01Y.cpy distinguishes from 'U'")
                    .isEqualTo(403);
            assertThat(result.getResponse().getHeaderValues(CORRELATION_ID_HEADER))
                    .as("an authorisation refusal is correlated for the same reason an authentication refusal "
                            + "is")
                    .containsExactly(SECOND_SUPPLIED_ID);
        }

        @Test
        @DisplayName("no session is created, on either the refused or the authorised path")
        void noSessionIsCreated() throws Exception {
            final MvcResult refused = mockMvc.perform(get(PROTECTED_PATH)
                            .header(CORRELATION_ID_HEADER, SUPPLIED_ID))
                    .andReturn();
            final MvcResult authorised = mockMvc.perform(get(ADMIN_ONLY_PATH)
                            .with(authentication(standardUser()))
                            .header(CORRELATION_ID_HEADER, SECOND_SUPPLIED_ID))
                    .andReturn();

            assertThat(refused.getRequest().getSession(false))
                    .as("the session policy is stateless. The pseudo-conversational enter-versus-re-enter flag "
                            + "CDEMO-PGM-CONTEXT of app/cpy/COCOM01Y.cpy has no Java counterpart and collapses "
                            + "into stateless request handling, so a session here would be state the design "
                            + "says does not exist")
                    .isNull();
            assertThat(authorised.getRequest().getSession(false))
                    .as("an authenticated request creates no session either: the principal is carried per "
                            + "request, never stored on the server")
                    .isNull();
        }

        @Test
        @DisplayName("a refused bearer credential is never written to any log record")
        void aRefusedBearerCredentialIsNeverLogged() throws Exception {
            final Level previousLevel = widenLevelOf(APPLICATION_LOGGER_NAME, Level.TRACE);
            final ListAppender<ILoggingEvent> captured = attachAppender(APPLICATION_LOGGER_NAME);
            try {
                final MvcResult result = mockMvc.perform(get(PROTECTED_PATH)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + SYNTHETIC_BEARER_CREDENTIAL)
                                .header(CORRELATION_ID_HEADER, SUPPLIED_ID))
                        .andReturn();

                assertThat(result.getResponse().getStatus())
                        .as("a credential that cannot be verified leaves the request unauthenticated")
                        .isEqualTo(401);
                assertThat(result.getResponse().getHeaderValues(CORRELATION_ID_HEADER))
                        .containsExactly(SUPPLIED_ID);

                final List<String> messages = formattedMessagesOf(captured);
                assertThat(messages)
                        .as("the credential is refused without being echoed: the production code names the "
                                + "exception class and nothing else. No secret reaches a log record in the "
                                + "first place, which is the defence that matters; masking is the backstop")
                        .noneMatch(message -> message.contains(SYNTHETIC_BEARER_CREDENTIAL));
                assertThat(messages)
                        .as("nor may the bearer scheme plus credential survive as a whole header value")
                        .noneMatch(message -> message.contains("Bearer " + SYNTHETIC_BEARER_CREDENTIAL));
            } finally {
                detachAppender(APPLICATION_LOGGER_NAME, captured);
                restoreLevelOf(APPLICATION_LOGGER_NAME, previousLevel);
            }
        }
    }


    // =================================================================================================
    // The trace context. Present when a span is active, absent without error when none is, because the
    // test profile deliberately neutralises trace export while leaving tracing itself switched on.
    // =================================================================================================

    /** The two states the trace entries can legitimately be in, and the absence of a failure in either. */
    @Nested
    @DisplayName("the trace context, which exists only while a span does")
    class TraceContextPlacement {

        @Test
        @DisplayName("with a span in scope the trace and span identifiers reach the diagnostic context")
        void withASpanInScopeTheTraceEntriesArePlaced() throws Exception {
            final Span span = tracer.nextSpan().name("carddemo-correlation-scope").start();
            final Tracer.SpanInScope scope = tracer.withSpan(span);
            try {
                assertThat(span.context().traceId())
                        .as("precondition: tracing is enabled and the sampling probability is one, so the span "
                                + "this test opened must carry a usable trace identifier. An all-zero value "
                                + "would mean the tracer is a no-op and the assertions below would prove "
                                + "nothing")
                        .isNotBlank()
                        .containsPattern("[^0]");

                final Map<String, String> insideChain =
                        filterAndCaptureContext(requestWithSuppliedHeader(SUPPLIED_ID),
                                new MockHttpServletResponse());

                assertThat(insideChain)
                        .as("the trace and span identifiers come from the injected tracer, never from a "
                                + "request header and never generated by the filter, so what lands in the "
                                + "diagnostic context is exactly what the active span reports")
                        .containsEntry(MDC_KEY_TRACE_ID, span.context().traceId())
                        .containsEntry(MDC_KEY_SPAN_ID, span.context().spanId())
                        .containsEntry(MDC_KEY_CORRELATION_ID, SUPPLIED_ID);

                final io.opentelemetry.api.trace.Span underlyingSpan = OtelSpan.toOtel(span);
                assertThat(underlyingSpan)
                        .as("precondition: the bridged span must be a recording span for its attributes to be "
                                + "readable at all")
                        .isInstanceOf(ReadableSpan.class);
                assertThat(((ReadableSpan) underlyingSpan).toSpanData().getAttributes()
                                .get(AttributeKey.stringKey(SPAN_TAG_CORRELATION_ID)))
                        .as("the identifier is attached to the active span as well as to the log context, so a "
                                + "trace in the backend and a log record can be joined from either side")
                        .isEqualTo(SUPPLIED_ID);
            } finally {
                scope.close();
                span.end();
            }

            assertCorrelationKeysAbsent("after the span scope was closed");
        }

        @Test
        @DisplayName("with no span in scope the trace entries are absent, and nothing throws")
        void withNoSpanTheTraceEntriesAreAbsentWithoutError() {
            assertThat(tracer.currentSpan())
                    .as("precondition: this thread must have no span in scope, which is the case a mock "
                            + "dispatch cannot reach because the servlet observation filter opens one ahead of "
                            + "the correlation filter on every request")
                    .isNull();

            final List<Map<String, String>> captured = new ArrayList<>(1);
            assertThatCode(() -> captured.add(filterAndCaptureContext(requestWithSuppliedHeader(SUPPLIED_ID),
                    new MockHttpServletResponse())))
                    .as("the no-active-span path must neither throw nor dereference a null span. Batch code "
                            + "and any non-traced entry point take this path, and a failure here would turn a "
                            + "missing trace into a failed request")
                    .doesNotThrowAnyException();

            assertThat(captured.get(0))
                    .as("the correlation identifier is always present, because it does not depend on tracing "
                            + "at all; the trace entries are simply absent, which keeps the JSON schema valid "
                            + "through the encoder's empty-string default rather than dropping the fields")
                    .containsEntry(MDC_KEY_CORRELATION_ID, SUPPLIED_ID)
                    .doesNotContainKeys(MDC_KEY_TRACE_ID, MDC_KEY_SPAN_ID);
        }
    }

    // =================================================================================================
    // Propagation across the process boundary: the execution interceptor on every cloud client, and the
    // message header on the queue publish that replaces EXEC CICS WRITEQ TD QUEUE('JOBS').
    // =================================================================================================

    /** The outbound half of the thread of identity, asserted against the real emulator-backed clients. */
    @Nested
    @DisplayName("propagation onto outbound object-store and queue calls")
    class OutboundPropagation {

        /**
         * Reads the interceptors the injected object-store client carries.
         *
         * @return the registered interceptors
         */
        private List<ExecutionInterceptor> objectStoreInterceptors() {
            return s3Client().serviceClientConfiguration().overrideConfiguration().executionInterceptors();
        }

        /**
         * Reads the interceptors the injected queue client carries.
         *
         * @return the registered interceptors
         */
        private List<ExecutionInterceptor> queueInterceptors() {
            return sqsAsyncClient().serviceClientConfiguration().overrideConfiguration().executionInterceptors();
        }

        @Test
        @DisplayName("the correlation interceptor is registered on both the object-store and the queue client")
        void theInterceptorIsRegisteredOnBothClients() {
            assertThat(correlationInterceptorOf(objectStoreInterceptors(), "object-store client"))
                    .as("registration is what makes propagation happen on a real call; the behaviour asserted "
                            + "below would be unreachable without it")
                    .isNotNull();
            assertThat(correlationInterceptorOf(queueInterceptors(), "queue client"))
                    .isNotNull();
        }

        @Test
        @DisplayName("the registered interceptor copies the identifier onto an outbound request")
        void theInterceptorCopiesTheIdentifierOntoAnOutboundRequest() {
            final AwsConfig.CorrelationIdExecutionInterceptor interceptor =
                    correlationInterceptorOf(objectStoreInterceptors(), "object-store client");

            final String previous = CorrelationIdFilter.propagate(OUTBOUND_ID);
            try {
                final SdkHttpRequest modified = interceptor.modifyHttpRequest(
                        interceptorContextFor(synthesiseOutboundRequest()), new ExecutionAttributes());

                assertThat(modified.firstMatchingHeader(CORRELATION_ID_HEADER))
                        .as("an object written or a message published has to be joinable to the request that "
                                + "caused it. The hook runs before the request is signed, so the added header "
                                + "is covered by the signature rather than invalidating it")
                        .contains(OUTBOUND_ID);
            } finally {
                CorrelationIdFilter.propagate(previous);
            }
        }

        @Test
        @DisplayName("the registered interceptor changes nothing when no identifier is in scope")
        void theInterceptorChangesNothingWithoutAScope() {
            assertCorrelationKeysAbsent("before the unscoped outbound probe");
            final AwsConfig.CorrelationIdExecutionInterceptor interceptor =
                    correlationInterceptorOf(queueInterceptors(), "queue client");
            final SdkHttpRequest original = synthesiseOutboundRequest();

            final SdkHttpRequest modified =
                    interceptor.modifyHttpRequest(interceptorContextFor(original), new ExecutionAttributes());

            assertThat(modified)
                    .as("with nothing in scope the request is returned untouched: a missing correlation is a "
                            + "diagnostic gap, whereas a fabricated or malformed one would be a corrupted "
                            + "request")
                    .isSameAs(original);
            assertThat(modified.firstMatchingHeader(CORRELATION_ID_HEADER)).isEmpty();
        }

        @Test
        @DisplayName("a real object-store write inside a correlation scope succeeds and is logged correlated")
        void aRealObjectStoreWriteInsideAScopeIsCorrelated() throws Exception {
            final String bucket = createBucket(scopedResourceName("propagation"));
            final String objectKey = "correlation/" + OUTBOUND_ID + "/probe.txt";
            final ListAppender<ILoggingEvent> captured = attachAppender(PROBE_LOGGER_NAME);
            final String previous = CorrelationIdFilter.propagate(OUTBOUND_ID);
            try {
                s3Template().upload(bucket, objectKey, new ByteArrayInputStream(
                        OBJECT_PROBE_PAYLOAD.getBytes(StandardCharsets.UTF_8)));

                // The cloud loggers are pinned to warn by logback-spring.xml, so a successful write emits no
                // production line at all by design. This line stands in for one, on the same thread and inside
                // the same scope, which is what the assertion below is about: anything logged around an
                // outbound call carries the identifier, because the context is ambient on the thread.
                logbackLogger(PROBE_LOGGER_NAME).info("object-store probe completed");

                try (InputStream stored = s3Template().download(bucket, objectKey).getInputStream()) {
                    assertThat(new String(stored.readAllBytes(), StandardCharsets.UTF_8))
                            .as("the round trip has to succeed with the correlation header in place. A header "
                                    + "added after signing would have invalidated the signature and the "
                                    + "emulator would have refused the call, so this is the evidence that "
                                    + "propagation does not break the transport")
                            .isEqualTo(OBJECT_PROBE_PAYLOAD);
                }
            } finally {
                CorrelationIdFilter.propagate(previous);
                detachAppender(PROBE_LOGGER_NAME, captured);
            }

            assertThat(correlationIdsOf(captured))
                    .as("the record emitted around the outbound call carries the identifier in its own "
                            + "diagnostic-context snapshot, captured in process rather than read from a file "
                            + "or a container log")
                    .containsExactly(OUTBOUND_ID);
            assertThat(encodeWithProductionEncoder(singleCapturedEvent(captured)))
                    .as("and the encoder an operator actually reads renders it into the correlation field")
                    .containsPattern("\"" + MDC_KEY_CORRELATION_ID + "\"\\s*:\\s*\""
                            + Pattern.quote(OUTBOUND_ID) + "\"");
        }

        @Test
        @DisplayName("a report submission carries the request's identifier onto the queue message")
        void aReportSubmissionCarriesTheIdentifierOntoTheQueueMessage() throws Exception {
            final ListAppender<ILoggingEvent> captured = attachAppender(APPLICATION_LOGGER_NAME);
            try {
                final MvcResult result = mockMvc.perform(post(REPORT_SUBMISSION_PATH)
                                .with(authentication(standardUser()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(MONTHLY_REPORT_REQUEST_BODY)
                                .header(CORRELATION_ID_HEADER, OUTBOUND_ID))
                        .andReturn();

                assertThat(result.getResponse().getStatus())
                        .as("the confirmed monthly submission publishes and is accepted for asynchronous "
                                + "processing, which is what replaces EXEC CICS WRITEQ TD QUEUE('JOBS') at "
                                + "app/cbl/CORPT00C.cbl:515")
                        .isEqualTo(202);
                assertThat(result.getResponse().getHeaderValues(CORRELATION_ID_HEADER))
                        .containsExactly(OUTBOUND_ID);

                final Optional<Message<?>> delivered = receiveMessageCorrelatedWith(OUTBOUND_ID);
                assertThat(delivered)
                        .as("the message must actually arrive carrying the identifier of the request that "
                                + "caused it. Without that, a batch run triggered by a submission could not be "
                                + "traced back to the caller who submitted it. The queue's own contract - "
                                + "first-in-first-out with a fixed message group - is asserted by the queue "
                                + "class of this package and deliberately not repeated here")
                        .isPresent();
                assertThat(headerValueOf(delivered.orElseThrow(), CORRELATION_ID_HEADER))
                        .isEqualTo(OUTBOUND_ID);
                assertThat(delivered.orElseThrow().getHeaders())
                        .as("the publish also carries its own trace and span identifiers, so a consumer can "
                                + "parent onto the publish hop rather than onto the request span containing it")
                        .containsKey("X-Trace-Id")
                        .containsKey("X-Span-Id");

                assertThat(correlationIdsOf(captured))
                        .as("the production records emitted around the publish carry the same identifier, and "
                                + "no other request's")
                        .contains(OUTBOUND_ID)
                        .doesNotContain(SUPPLIED_ID, SECOND_SUPPLIED_ID);
            } finally {
                detachAppender(APPLICATION_LOGGER_NAME, captured);
            }

            assertCorrelationKeysAbsent("after the report submission");
        }
    }


    // =================================================================================================
    // The record an operator actually reads. Masking lives in the encoder rather than in the event, so
    // every assertion here renders a captured event through the very encoder the application runs.
    // =================================================================================================

    /** Field population, the empty-rather-than-missing default, the preserved legacy literal, and masking. */
    @Nested
    @DisplayName("the structured log record, rendered by the production encoder")
    class StructuredLogFidelity {

        @Test
        @DisplayName("the correlation field carries the identifier in scope")
        void theCorrelationFieldCarriesTheIdentifierInScope() {
            final String previous = CorrelationIdFilter.propagate(SUPPLIED_ID);
            final String encoded;
            try {
                encoded = encodedProbeLine("correlation field probe");
            } finally {
                CorrelationIdFilter.propagate(previous);
            }

            assertThat(encoded)
                    .as("the field is what makes a log record joinable to a request, a span and an object key. "
                            + "A key-spelling drift on either side empties it with no error and no output, "
                            + "which is why the spellings are pinned as well as the value. Severity High")
                    .containsPattern("\"" + MDC_KEY_CORRELATION_ID + "\"\\s*:\\s*\""
                            + Pattern.quote(SUPPLIED_ID) + "\"");
        }

        @Test
        @DisplayName("the trio is present but empty when nothing is in scope, rather than missing")
        void theTrioIsPresentButEmptyWhenNothingIsInScope() {
            assertCorrelationKeysAbsent("before the empty-context probe");

            final String encoded = encodedProbeLine("empty context probe");

            assertThat(encoded)
                    .as("the encoder defaults each of the three to an empty string, so a consumer's schema "
                            + "stays valid when tracing is not configured or no identifier was set. This is the "
                            + "absent-without-error case, and it must not fail and must not drop the keys")
                    .containsPattern("\"" + MDC_KEY_CORRELATION_ID + "\"\\s*:\\s*\"\"")
                    .containsPattern("\"" + MDC_KEY_TRACE_ID + "\"\\s*:\\s*\"\"")
                    .containsPattern("\"" + MDC_KEY_SPAN_ID + "\"\\s*:\\s*\"\"");
        }

        @Test
        @DisplayName("the batch job-instance field appears only when that key is in scope")
        void theBatchFieldAppearsOnlyWhenItsKeyIsInScope() {
            final String withoutKey = encodedProbeLine("batch key absent probe");
            assertThat(withoutKey)
                    .as("the batch key is emitted only when present, so its presence is itself the signal that "
                            + "a record came from a job rather than from a request")
                    .doesNotContain(MDC_KEY_JOB_INSTANCE_ID);

            final String previous = CorrelationIdFilter.propagateJobInstanceId("42");
            final String withKey;
            try {
                withKey = encodedProbeLine("batch key present probe");
            } finally {
                CorrelationIdFilter.propagateJobInstanceId(previous);
            }

            assertThat(withKey)
                    .as("the same number that names a per-run object prefix tags the run's log records, which "
                            + "is what makes the two correlatable. The key is placed by the batch layer; this "
                            + "assertion covers the spelling and the schema only, and introduces no listener")
                    .containsPattern("\"" + MDC_KEY_JOB_INSTANCE_ID + "\"\\s*:\\s*\"42\"");
        }

        @Test
        @DisplayName("the legacy file-status rendering passes through unmodified and unmasked")
        void theLegacyFileStatusRenderingIsVerbatim() {
            final String encoded = encodedProbeLine(PARITY_FILE_STATUS_LINE);

            assertThat(encoded)
                    .as("app/cbl/CBTRN02C.cbl:721 and :725 both emit DISPLAY 'FILE STATUS IS: NNNN' "
                            + "IO-STATUS-04, and the literal already carries the placeholder text, so status "
                            + "'23' renders with the stray NNNN in front of the four digits. The stray text is "
                            + "a preserved legacy quirk and is never fixed: reformatting, wrapping, truncating "
                            + "or masking it would change output a parity comparison is measured against. "
                            + "Severity Blocker")
                    .contains(PARITY_FILE_STATUS_LINE)
                    .doesNotContain(REDACTION)
                    .doesNotContain(REDACTION_SSN);
        }

        @Test
        @DisplayName("a labelled credential value is redacted")
        void aLabelledCredentialValueIsRedacted() {
            final String encoded = encodedProbeLine("credential probe password=" + SYNTHETIC_CREDENTIAL_VALUE);

            assertThat(encoded)
                    .as("masking covers credentials, password hashes and social security numbers, and nothing "
                            + "else. It is the second line of defence: the primary defence is that production "
                            + "code does not emit the value at all, which is asserted separately")
                    .contains(REDACTION)
                    .doesNotContain(SYNTHETIC_CREDENTIAL_VALUE);
        }

        @Test
        @DisplayName("a value in hash shape is redacted")
        void aValueInHashShapeIsRedacted() {
            final String encoded = encodedProbeLine("hash probe " + SYNTHETIC_BCRYPT_SHAPED_VALUE);

            assertThat(encoded)
                    .as("the ten seeded users of app/jcl/DUSRSECJ.jcl are stored only as hashes, so a hash is "
                            + "the shape that could realistically reach a log line through an entity rendering")
                    .contains(REDACTION_BCRYPT)
                    .doesNotContain(SYNTHETIC_BCRYPT_SHAPED_VALUE);
        }

        @Test
        @DisplayName("a value in social-security-number shape is redacted")
        void aValueInSocialSecurityNumberShapeIsRedacted() {
            final String encoded = encodedProbeLine("customer probe " + SYNTHETIC_SSN_SHAPED_VALUE);

            assertThat(encoded)
                    .as("the customer layout carries a nine-digit government identifier, so its dashed "
                            + "presentation is masked wherever it appears")
                    .contains(REDACTION_SSN)
                    .doesNotContain(SYNTHETIC_SSN_SHAPED_VALUE);
        }

        @Test
        @DisplayName("a value in bearer-credential shape is redacted")
        void aValueInBearerCredentialShapeIsRedacted() {
            final String encoded = encodedProbeLine("token probe " + SYNTHETIC_BEARER_CREDENTIAL);

            assertThat(encoded)
                    .as("the backstop has to hold for a credential too, because a library on the runtime "
                            + "classpath could render an authorization header at a verbose level")
                    .contains(REDACTION_JWT)
                    .doesNotContain(SYNTHETIC_BEARER_CREDENTIAL);
        }
    }
}

