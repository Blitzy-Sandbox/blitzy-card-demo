/*
 * ****************************************************************************
 * Test        : CorrelationPropagationTest
 * Application : CardDemo
 * Type        : Java unit test - outbound correlation propagation
 * Function    : Assert that the correlation identifier the inbound filter mints
 *               is carried onto outbound S3, SQS and SNS requests, and that a
 *               missing or malformed identifier degrades to omission rather
 *               than to a failed call.
 * Source      : src/main/java/com/cardemo/config/AwsConfig.java - the three
 *               per-service client customizers and the shared interceptor
 *               src/main/java/com/cardemo/observability/CorrelationIdFilter.java
 *               app/csd/CARDDEMO.CSD DEFINE TDQUEUE(JOBS) - the submission the
 *               queue client replaces, whose only legacy trace was the queue
 *               record itself
 * ****************************************************************************
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cardemo.unit.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.config.AwsConfig;
import com.cardemo.observability.CorrelationIdFilter;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * Unit tests for the outbound half of the correlation contract.
 *
 * <p>{@link CorrelationIdFilter} gives one request a single identifier and puts it in {@link MDC} and in the
 * response. That covers this application's own logs and nothing else. A review recorded the remaining gap
 * precisely: outbound AWS calls carried no correlation metadata, so an object written to the reject bucket or
 * a message placed on the report queue arrived carrying nothing that tied it to the request or batch run that
 * produced it - leaving the one question an integration failure prompts, "which run wrote this", unanswerable
 * from the request itself.
 *
 * <p>The interceptor closes that gap by copying the identifier onto the outbound HTTP request as the same
 * header the inbound filter reads. These tests exercise it directly, because that is where the behaviour
 * lives: the customizers are three one-line lambdas whose only job is to register it, and registration is
 * asserted separately by checking each bean is present and distinct.
 *
 * <p>Three properties are asserted, and the second and third matter as much as the first:
 *
 * <ol>
 *   <li>a well-formed identifier in {@link MDC} reaches the wire under the published header name;</li>
 *   <li>no identifier means no header, not a failed request - batch threads never pass through the servlet
 *       filter, so absence is the normal case on every batch write, and an interceptor that threw would turn
 *       a missing diagnostic into a failed posting run;</li>
 *   <li>a malformed identifier is dropped rather than forwarded, because a header carrying {@code CR} or
 *       {@code LF} is a request-splitting vector and this is a different trust boundary from the filter -
 *       the batch layer writes {@link MDC} too.</li>
 *   </ol>
 */
@DisplayName("Correlation propagation - the identifier reaches outbound S3, SQS and SNS requests")
final class CorrelationPropagationTest {

    /** A well-formed identifier, of the shape the filter mints and admits. */
    private static final String CORRELATION_ID = "7f3c1b9a-4de2-11ee-be56-0242ac120002";

    /** The interceptor under test, obtained the same way the SDK obtains it. */
    private ExecutionInterceptor interceptor;

    @BeforeEach
    void setUp() {
        MDC.clear();
        this.interceptor = new AwsConfig.CorrelationIdExecutionInterceptor();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    /**
     * Builds a representative outbound request for one of the three services.
     *
     * @param host the service host the SDK would target
     * @param path the request path
     * @return an unsigned request of the shape the SDK hands to an interceptor
     */
    private static SdkHttpRequest outboundRequest(final String host, final String path) {
        return SdkHttpRequest.builder()
                .method(SdkHttpMethod.POST)
                .uri(URI.create("http://" + host + ":4566" + path))
                .build();
    }

    /**
     * Runs the interceptor over a request and returns the result.
     *
     * @param request the request to decorate
     * @return the request the interceptor produced
     */
    private SdkHttpRequest intercept(final SdkHttpRequest request) {
        return this.interceptor.modifyHttpRequest(new Context.ModifyHttpRequest() {
            @Override
            public SdkHttpRequest httpRequest() {
                return request;
            }

            @Override
            public software.amazon.awssdk.core.SdkRequest request() {
                throw new UnsupportedOperationException(
                        "the interceptor must decorate the HTTP request only, and must not reach for the "
                                + "modelled SDK request: doing so would couple it to individual operations "
                                + "instead of applying uniformly to all of them");
            }

            @Override
            public Optional<software.amazon.awssdk.core.async.AsyncRequestBody> asyncRequestBody() {
                return Optional.empty();
            }

            @Override
            public Optional<software.amazon.awssdk.core.sync.RequestBody> requestBody() {
                return Optional.empty();
            }
        }, new ExecutionAttributes());
    }

    /**
     * Reads the correlation header from a request.
     *
     * @param request the request to inspect
     * @return the header values, empty when the header is absent
     */
    private static List<String> correlationHeader(final SdkHttpRequest request) {
        return request.headers()
                .getOrDefault(CorrelationIdFilter.CORRELATION_ID_HEADER, List.of());
    }

    /**
     * Reads the W3C trace-context header from a request.
     *
     * @param request the request to inspect
     * @return the header values, empty when the header is absent
     */
    private static List<String> traceParentHeader(final SdkHttpRequest request) {
        return request.headers()
                .getOrDefault(CorrelationIdFilter.TRACE_PARENT_HEADER, List.of());
    }

    /**
     * Interoperable trace context, finding M-07.
     *
     * <p>The correlation header names an identifier only this repository knows how to read, so on its own it
     * correlated <em>logs</em> across the process boundary without establishing trace <em>parentage</em>
     * anywhere: a downstream receiving it starts a fresh, unparented trace. These tests pin the standard header
     * that every OpenTelemetry and Micrometer Tracing consumer extracts unprompted.
     */
    @Nested
    @DisplayName("W3C trace context reaches the wire alongside the correlation identifier")
    final class TraceContextIsPropagated {

        /** Sole constructor, invoked by the test framework. This group holds no state. */
        TraceContextIsPropagated() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("the trace identity in the diagnostic context becomes a traceparent header")
        void traceIdentityBecomesATraceParentHeader() {
            MDC.put(CorrelationIdFilter.MDC_KEY_TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736");
            MDC.put(CorrelationIdFilter.MDC_KEY_SPAN_ID, "00f067aa0ba902b7");

            assertThat(traceParentHeader(intercept(outboundRequest("s3.localhost.localstack.cloud", "/b/k"))))
                    .as("version 00, the trace identifier, the span identifier as the parent field and the "
                            + "sampled flag - the specification's own example values, composed by the one "
                            + "owner of the rule")
                    .containsExactly("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        }

        @Test
        @DisplayName("a 64-bit trace identifier is left-padded to the 128-bit field the standard requires")
        void compactTraceIdentifierIsPadded() {
            MDC.put(CorrelationIdFilter.MDC_KEY_TRACE_ID, "a3ce929d0e0e4736");
            MDC.put(CorrelationIdFilter.MDC_KEY_SPAN_ID, "00f067aa0ba902b7");

            assertThat(traceParentHeader(intercept(outboundRequest("sqs.localhost.localstack.cloud", "/q"))))
                    .as("some tracing bridges report a 64-bit trace identifier; the specification's own "
                            + "conversion is a zero left-pad, not a rejection")
                    .containsExactly("00-0000000000000000a3ce929d0e0e4736-00f067aa0ba902b7-01");
        }

        @Test
        @DisplayName("the two headers are independent: either may travel without the other")
        void theTwoHeadersAreIndependent() {
            MDC.put(CorrelationIdFilter.MDC_KEY_CORRELATION_ID, CORRELATION_ID);

            final SdkHttpRequest correlationOnly =
                    intercept(outboundRequest("sns.localhost.localstack.cloud", "/t"));

            assertThat(correlationHeader(correlationOnly)).containsExactly(CORRELATION_ID);
            assertThat(traceParentHeader(correlationOnly))
                    .as("tracing may not be configured at all, and that must not suppress the correlation "
                            + "identifier")
                    .isEmpty();

            MDC.clear();
            MDC.put(CorrelationIdFilter.MDC_KEY_TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736");
            MDC.put(CorrelationIdFilter.MDC_KEY_SPAN_ID, "00f067aa0ba902b7");

            final SdkHttpRequest traceOnly = intercept(outboundRequest("s3.localhost.localstack.cloud", "/b"));

            assertThat(traceParentHeader(traceOnly)).hasSize(1);
            assertThat(correlationHeader(traceOnly))
                    .as("batch work never passes through the servlet filter, so it carries trace context and "
                            + "no correlation identifier - which must still propagate")
                    .isEmpty();
        }

        @ParameterizedTest(name = "no header for trace=[{0}]")
        @ValueSource(strings = {
            "not-hex",
            "4BF92F3577B34DA6A3CE929D0E0E4736",
            "00000000000000000000000000000000",
            "4bf92f3577b34da",
            "4bf92f3577b34da6a3ce929d0e0e47361",
        })
        @DisplayName("an unusable trace identifier produces no header rather than a malformed one")
        void anUnusableTraceIdentifierProducesNoHeader(final String hostile) {
            MDC.put(CorrelationIdFilter.MDC_KEY_TRACE_ID, hostile);
            MDC.put(CorrelationIdFilter.MDC_KEY_SPAN_ID, "00f067aa0ba902b7");

            assertThat(traceParentHeader(intercept(outboundRequest("s3.localhost.localstack.cloud", "/b"))))
                    .as("a malformed traceparent is worse than an absent one: a consumer that accepts it "
                            + "records parentage onto a trace that does not exist. [%s] must be dropped, "
                            + "never repaired - uppercase included, since the field is defined lowercase; an "
                            + "all-zero field is invalid by definition; and a width that is neither 16 nor 32 "
                            + "cannot be padded to either", hostile)
                    .isEmpty();
        }

        @Test
        @DisplayName("finding M-03: an unsampled decision reaches the wire as -00, not as -01")
        void anUnsampledDecisionReachesTheWire() {
            MDC.put(CorrelationIdFilter.MDC_KEY_TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736");
            MDC.put(CorrelationIdFilter.MDC_KEY_SPAN_ID, "00f067aa0ba902b7");
            MDC.put(CorrelationIdFilter.MDC_KEY_TRACE_FLAGS, "00");

            assertThat(traceParentHeader(intercept(outboundRequest("s3.localhost.localstack.cloud", "/b/k"))))
                    .as("the octet was hard-coded to 01, so with the production sampling probability of one in "
                            + "ten this header told nine consumers in ten to record a child of a trace this "
                            + "process had already dropped")
                    .containsExactly("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00");
        }

        @Test
        @DisplayName("a sampled decision reaches the wire as -01, so both arms are exercised")
        void aSampledDecisionReachesTheWire() {
            MDC.put(CorrelationIdFilter.MDC_KEY_TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736");
            MDC.put(CorrelationIdFilter.MDC_KEY_SPAN_ID, "00f067aa0ba902b7");
            MDC.put(CorrelationIdFilter.MDC_KEY_TRACE_FLAGS, "01");

            assertThat(traceParentHeader(intercept(outboundRequest("sqs.localhost.localstack.cloud", "/q"))))
                    .as("a flag that only ever reads 01 is indistinguishable from a hard-coded one, which is "
                            + "why the negative case above is asserted beside this one")
                    .containsExactly("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        }

        @Test
        @DisplayName("an absent decision falls back to sampled, the deferred case being documented")
        void anAbsentDecisionFallsBackToSampled() {
            MDC.put(CorrelationIdFilter.MDC_KEY_TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736");
            MDC.put(CorrelationIdFilter.MDC_KEY_SPAN_ID, "00f067aa0ba902b7");

            assertThat(traceParentHeader(intercept(outboundRequest("sns.localhost.localstack.cloud", "/t"))))
                    .as("batch work reaches this interceptor without ever passing through the servlet filter, "
                            + "so the entry can be absent; that is the deferred case, and it is reported as "
                            + "sampled rather than suppressing the header")
                    .containsExactly("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        }

        @Test
        @DisplayName("an absent span identifier produces no header, a trace alone being unusable")
        void anAbsentSpanIdentifierProducesNoHeader() {
            MDC.put(CorrelationIdFilter.MDC_KEY_TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736");

            assertThat(traceParentHeader(intercept(outboundRequest("s3.localhost.localstack.cloud", "/b"))))
                    .as("the parent field is not optional in the standard, so a trace identifier with no "
                            + "span to parent onto carries nothing")
                    .isEmpty();
        }
    }

    /** The identifier reaches the wire. */
    @Nested
    @DisplayName("a well-formed identifier reaches the wire")
    final class IdentifierIsPropagated {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "s3.localhost.localstack.cloud",
            "sqs.localhost.localstack.cloud",
            "sns.localhost.localstack.cloud",
        })
        @DisplayName("every service host receives the header, since one interceptor serves all three")
        void allThreeServicesReceiveTheHeader(final String host) {
            MDC.put(CorrelationIdFilter.MDC_KEY_CORRELATION_ID, CORRELATION_ID);

            final SdkHttpRequest decorated = intercept(outboundRequest(host, "/carddemo"));

            assertThat(correlationHeader(decorated))
                    .as("the interceptor is registered on the S3, SQS and SNS clients alike, so a request "
                            + "to %s must carry the identifier. A header present on some surfaces and "
                            + "absent on others is worse than none, because it reads as complete", host)
                    .containsExactly(CORRELATION_ID);
        }

        @Test
        @DisplayName("the header name is the same spelling the inbound filter publishes")
        void headerNameMatchesTheInboundContract() {
            MDC.put(CorrelationIdFilter.MDC_KEY_CORRELATION_ID, CORRELATION_ID);

            final SdkHttpRequest decorated =
                    intercept(outboundRequest("s3.localhost.localstack.cloud", "/bucket/key"));

            assertThat(decorated.headers())
                    .as("one identifier must name the same unit of work inbound and outbound; a second "
                            + "spelling would silently create two identifiers for one request")
                    .containsKey(CorrelationIdFilter.CORRELATION_ID_HEADER);
            assertThat(CorrelationIdFilter.CORRELATION_ID_HEADER).isEqualTo("X-Correlation-Id");
        }

        @Test
        @DisplayName("nothing else about the request is altered")
        void requestIsOtherwiseUntouched() {
            MDC.put(CorrelationIdFilter.MDC_KEY_CORRELATION_ID, CORRELATION_ID);
            final SdkHttpRequest original =
                    outboundRequest("s3.localhost.localstack.cloud", "/bucket/reject-file");

            final SdkHttpRequest decorated = intercept(original);

            assertThat(decorated.method()).isEqualTo(original.method());
            assertThat(decorated.encodedPath()).isEqualTo(original.encodedPath());
            assertThat(decorated.host()).isEqualTo(original.host());
            assertThat(decorated.port()).isEqualTo(original.port());
        }

        @Test
        @DisplayName("an identifier at the maximum accepted length is still forwarded")
        void identifierAtTheBoundIsForwarded() {
            final String atBound = "a".repeat(CorrelationIdFilter.MAX_CORRELATION_ID_LENGTH);
            MDC.put(CorrelationIdFilter.MDC_KEY_CORRELATION_ID, atBound);

            assertThat(correlationHeader(
                    intercept(outboundRequest("sqs.localhost.localstack.cloud", "/queue"))))
                    .containsExactly(atBound);
        }
    }

    /** Absence degrades to omission. */
    @Nested
    @DisplayName("absence degrades to omission, never to a failed call")
    final class AbsenceIsTolerated {

        @Test
        @DisplayName("with no MDC entry the request is returned unchanged and no header is added")
        void noEntryMeansNoHeader() {
            final SdkHttpRequest original =
                    outboundRequest("s3.localhost.localstack.cloud", "/bucket/key");

            final SdkHttpRequest decorated = intercept(original);

            assertThat(correlationHeader(decorated))
                    .as("a batch step writing a reject file has never passed through the servlet filter, "
                            + "so it carries no identifier. That is the normal case, not an error: the "
                            + "write must succeed")
                    .isEmpty();
            assertThat(decorated).isSameAs(original);
        }

        @Test
        @DisplayName("the interceptor never throws, whatever MDC holds")
        void interceptorNeverThrows() {
            for (final String candidate : new String[] {null, "", " ", "\r\n", "x".repeat(500)}) {
                MDC.clear();
                if (candidate != null) {
                    MDC.put(CorrelationIdFilter.MDC_KEY_CORRELATION_ID, candidate);
                }

                assertThat(intercept(outboundRequest("sns.localhost.localstack.cloud", "/topic")))
                        .as("an observability concern must never be the reason a write fails; MDC held "
                                + "[%s]", candidate)
                        .isNotNull();
            }
        }
    }

    /** Malformed values are dropped. */
    @Nested
    @DisplayName("a malformed identifier is dropped rather than forwarded")
    final class MalformedValuesAreDropped {

        @ParameterizedTest(name = "not forwarded: [{0}]")
        @ValueSource(strings = {
            "\r\nX-Injected: value",
            "line\nbreak",
            "carriage\rreturn",
            "space separated",
            "semi;colon",
            "colon:value",
            "",
            " ",
        })
        @DisplayName("a value outside the accepted shape produces no header at all")
        void malformedValuesProduceNoHeader(final String hostile) {
            MDC.put(CorrelationIdFilter.MDC_KEY_CORRELATION_ID, hostile);

            final SdkHttpRequest decorated =
                    intercept(outboundRequest("sqs.localhost.localstack.cloud", "/queue"));

            assertThat(correlationHeader(decorated))
                    .as("a header value carrying CR or LF is a request-splitting vector, so [%s] must be "
                            + "dropped and not escaped. This is a second trust boundary: the batch layer "
                            + "writes MDC too, so the filter's own validation cannot be relied on here",
                            hostile)
                    .isEmpty();
        }

        @Test
        @DisplayName("a value one character past the bound is dropped, matching the filter's own limit")
        void overlongValueIsDropped() {
            MDC.put(CorrelationIdFilter.MDC_KEY_CORRELATION_ID,
                    "a".repeat(CorrelationIdFilter.MAX_CORRELATION_ID_LENGTH + 1));

            assertThat(correlationHeader(
                    intercept(outboundRequest("s3.localhost.localstack.cloud", "/bucket/key"))))
                    .as("the outbound bound must equal the inbound bound, or one boundary would admit what "
                            + "the other refuses")
                    .isEmpty();
        }
    }

    /** Registration. */
    @Nested
    @DisplayName("the interceptor is registered on all three clients")
    final class Registration {

        @Test
        @DisplayName("AwsConfig declares one customizer bean per service, each a distinct type")
        void oneCustomizerPerService() {
            final List<String> beanMethods = java.util.Arrays.stream(AwsConfig.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(
                            org.springframework.context.annotation.Bean.class))
                    .map(java.lang.reflect.Method::getName)
                    .filter(name -> name.startsWith("correlationId"))
                    .sorted()
                    .toList();

            assertThat(beanMethods)
                    .as("the three integration surfaces are served by three separate per-service "
                            + "customizer interfaces, because the global sync and async customizers expose "
                            + "only httpClient and cannot reach overrideConfiguration. Dropping any one of "
                            + "these beans leaves that surface unstamped with no compile error to say so")
                    .containsExactly(
                            "correlationIdS3ClientCustomizer",
                            "correlationIdSnsClientCustomizer",
                            "correlationIdSqsAsyncClientCustomizer");
        }

        @Test
        @DisplayName("the interceptor is stateless, so one shared instance is safe across clients")
        void interceptorIsStateless() {
            assertThat(AwsConfig.CorrelationIdExecutionInterceptor.class.getDeclaredFields())
                    .as("one instance is shared by three clients and every concurrent request, so any "
                            + "mutable instance field would be a data race")
                    .allSatisfy(field -> assertThat(java.lang.reflect.Modifier.isStatic(
                            field.getModifiers())).isTrue());
        }
    }

    /**
     * The queue-send recovery interceptor, which covers the one call the uniform interceptor cannot reach.
     *
     * <p>Runtime testing through a logging proxy found the exact gap: on the first publish of a process,
     * {@code SqsTemplate} resolves the queue URL and attributes and chains the send onto that resolution, so
     * {@code GetQueueUrl} is issued from the request thread and carries the header while the chained
     * {@code SendMessage} is issued from an SDK completion thread that never passed through the servlet filter
     * and therefore carries none. Thread context cannot be conjured on that thread, and pushing it there would
     * mean writing one request's identity onto a pooled SDK thread where the next request could read it.
     *
     * <p>The identifier is already on the request, as the message attribute the publisher set from the very
     * same validated context, so it is recovered from there: no thread state, no shared mutable state, correct
     * on any thread, and impossible for two concurrent publishes to confuse because each value travels inside
     * its own request object.
     *
     * <p>Four properties are asserted: it recovers the identifier when the request has no header; it never
     * replaces a header that is already there, so the two interceptors cannot disagree in either order; it
     * drops a malformed attribute exactly as the uniform interceptor drops a malformed context entry; and it
     * touches nothing but a send, so the queue-resolution calls - one per process, shared by every later
     * request - are not attributed to whichever request happened to trigger them.
     */
    @Nested
    @DisplayName("the queue-send recovery interceptor stamps a send issued without thread context")
    final class SendRecoveryFromMessageAttributes {

        /** The interceptor under test, obtained the same way the SDK obtains it. */
        private final ExecutionInterceptor recovery =
                new AwsConfig.SqsSendCorrelationRecoveryInterceptor();

        /**
         * Builds the send request the SDK would hand the interceptor.
         *
         * @param correlationId the value to carry as the correlation message attribute, or null for none
         * @return a send request carrying the queue-publish shape the application produces
         */
        private SendMessageRequest sendRequest(final String correlationId) {
            final SendMessageRequest.Builder builder = SendMessageRequest.builder()
                    .queueUrl("http://localhost:4566/000000000000/carddemo-report-jobs.fifo")
                    .messageGroupId("carddemo-report-jobs")
                    .messageBody("{\"reportName\":\"Monthly\"}");
            if (correlationId != null) {
                builder.messageAttributes(Map.of(
                        CorrelationIdFilter.CORRELATION_ID_HEADER,
                        MessageAttributeValue.builder().dataType("String").stringValue(correlationId).build(),
                        "X-Carddemo-Transaction",
                        MessageAttributeValue.builder().dataType("String").stringValue("CR00").build()));
            }
            return builder.build();
        }

        /**
         * Runs the recovery interceptor over a request.
         *
         * @param httpRequest   the HTTP request as it stands when the interceptor is called
         * @param modelled the modelled SDK request the SDK is about to transmit
         * @return the request the interceptor produced
         */
        private SdkHttpRequest intercept(final SdkHttpRequest httpRequest, final SdkRequest modelled) {
            return this.recovery.modifyHttpRequest(new Context.ModifyHttpRequest() {
                @Override
                public SdkHttpRequest httpRequest() {
                    return httpRequest;
                }

                @Override
                public SdkRequest request() {
                    return modelled;
                }

                @Override
                public Optional<software.amazon.awssdk.core.async.AsyncRequestBody> asyncRequestBody() {
                    return Optional.empty();
                }

                @Override
                public Optional<software.amazon.awssdk.core.sync.RequestBody> requestBody() {
                    return Optional.empty();
                }
            }, new ExecutionAttributes());
        }

        @Test
        @DisplayName("with no thread context, the identifier is recovered from the message attribute")
        void theIdentifierIsRecoveredFromTheMessageAttribute() {
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID))
                    .as("the case under test is precisely an SDK thread that holds no context")
                    .isNull();

            final SdkHttpRequest stamped = intercept(
                    outboundRequest("sqs.us-east-1.localhost.localstack.cloud", "/"),
                    sendRequest(CORRELATION_ID));

            assertThat(correlationHeader(stamped))
                    .as("the publish reaches the wire correlated even though the sending thread had no "
                            + "diagnostic context, which is the gap the proxy capture found open")
                    .containsExactly(CORRELATION_ID);
        }

        @Test
        @DisplayName("a header already stamped from the diagnostic context is never replaced")
        void anExistingHeaderIsNeverReplaced() {
            final SdkHttpRequest alreadyStamped =
                    outboundRequest("sqs.us-east-1.localhost.localstack.cloud", "/").toBuilder()
                            .putHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, CORRELATION_ID)
                            .build();

            final SdkHttpRequest result = intercept(alreadyStamped, sendRequest("some-other-value"));

            assertThat(correlationHeader(result))
                    .as("the two interceptors run on the same client, so the second must be a no-op when the "
                            + "first has acted - otherwise the order between them would be observable")
                    .containsExactly(CORRELATION_ID);
        }

        @ParameterizedTest
        @ValueSource(strings = {"bad value", "with\rcarriage", "with\nlinefeed", "semi;colon", "",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
        @DisplayName("a malformed attribute value is dropped rather than written to a header")
        void aMalformedAttributeValueIsDropped(final String malformed) {
            final SdkHttpRequest result = intercept(
                    outboundRequest("sqs.us-east-1.localhost.localstack.cloud", "/"),
                    sendRequest(malformed));

            assertThat(correlationHeader(result))
                    .as("a header is a text protocol: an unbounded value or one carrying CR or LF would be a "
                            + "request-splitting vector, and the grammar is owned by CorrelationIdFilter")
                    .isEmpty();
        }

        @Test
        @DisplayName("a send with no correlation attribute is returned unchanged")
        void aSendWithoutTheAttributeIsUnchanged() {
            final SdkHttpRequest request = outboundRequest("sqs.us-east-1.localhost.localstack.cloud", "/");

            final SdkHttpRequest result = intercept(request, sendRequest(null));

            assertThat(correlationHeader(result)).isEmpty();
            assertThat(result.headers()).isEqualTo(request.headers());
        }

        @Test
        @DisplayName("queue-resolution calls are left alone, because their result is shared by every request")
        void queueResolutionCallsAreLeftAlone() {
            final SdkHttpRequest result = intercept(
                    outboundRequest("sqs.us-east-1.localhost.localstack.cloud", "/"),
                    software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest.builder()
                            .queueName("carddemo-report-jobs.fifo").build());

            assertThat(correlationHeader(result))
                    .as("GetQueueUrl and GetQueueAttributes resolve a queue once per process and their result "
                            + "serves every later publish, so attributing them to one request would be a "
                            + "misleading correlation rather than a missing one")
                    .isEmpty();
        }

        @Test
        @DisplayName("the interceptor never throws, whatever the request shape")
        void theInterceptorNeverThrows() {
            assertThat(intercept(outboundRequest("sqs.us-east-1.localhost.localstack.cloud", "/"), null))
                    .as("a diagnostic must never be able to fail a publish")
                    .isNotNull();
        }

        @Test
        @DisplayName("the interceptor is stateless, so one instance serves every concurrent publish")
        void theInterceptorIsStateless() {
            assertThat(AwsConfig.SqsSendCorrelationRecoveryInterceptor.class.getDeclaredFields())
                    .as("it reads the identifier from the request it is handed, so it needs no field at all - "
                            + "and any mutable one would let two publishes borrow each other's identity")
                    .allSatisfy(field -> assertThat(java.lang.reflect.Modifier.isStatic(
                            field.getModifiers())).isTrue());
        }
    }
}
