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
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;

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
}
