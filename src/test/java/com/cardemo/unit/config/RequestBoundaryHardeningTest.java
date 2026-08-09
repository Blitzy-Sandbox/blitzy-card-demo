/*
 * ******************************************************************
 * Program     : RequestBoundaryHardeningTest.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 unit test
 * Function    : Verifies the two bounds applied to an untrusted request
 *               body: the byte bound enforced by the request-body
 *               filter registered in SecurityConfig, and the parser
 *               bounds applied to the Jackson message converters by
 *               WebConfig. Together they close the anonymous sign-on
 *               body that was deserialized before any @Size ran.
 * Source      : app/cbl/COSGN00C.cbl:L117-L130 (the sign-on validation
 *               cascade, reached only after the terminal had already
 *               delivered a FIXED-WIDTH map - the property an HTTP body
 *               does not have) @ 7756d89
 * Source      : app/cpy-bms/COSGN00.CPY:84 (ERRMSGI PIC X(78)) and
 *               app/cpy-bms/COACTUP.CPY (the 54-field map behind the
 *               largest request type) - the field contracts every
 *               bound below is derived from @ 7756d89
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
package com.cardemo.unit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.config.WebConfig;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.util.unit.DataSize;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The bounds an untrusted request body is subject to before anything deserializes it.
 *
 * <p><strong>Finding, severity High - these tests pin the remediation.</strong> Exactly one business endpoint
 * is anonymous, the sign-on route, and it accepts a JSON body. That body was bound to a {@code SignOnRequest}
 * before any {@code @Size} constraint ran, because Bean Validation validates an <em>already constructed</em>
 * object: Jackson allocates first and is checked second. An unauthenticated caller could therefore make the
 * application read and materialise an arbitrarily large document, and the field contract bounding the sign-on
 * fields to a few dozen characters each never entered into it.
 *
 * <p>The legacy program had this property for free and did not have to think about it. {@code COSGN00C}'s
 * validation cascade runs on a COMMAREA the terminal had already delivered as a fixed-width map - eleven
 * fields, every width declared in {@code app/cpy-bms/COSGN00.CPY}. There was no such thing as an oversized
 * sign-on. An HTTP body has no declared width, so the bound has to be reintroduced explicitly, and that is
 * what these two mechanisms are.
 *
 * <p>Two mechanisms, because one does not imply the other:</p>
 * <ul>
 *   <li>The filter bounds how many BYTES a caller can make the application read.</li>
 *   <li>The parser constraints bound what a document of legal size can ask the parser to DO. 16 KB is ample
 *       room for a nesting depth of thousands, and it is depth rather than size that turns parsing into
 *       recursion deep enough to exhaust the stack.</li>
 * </ul>
 *
 * <p>The filter is reached reflectively because it is a private nested class of
 * {@code com.cardemo.config.SecurityConfig}, and it is deliberately private: it is an implementation detail of
 * one filter chain, not API. Widening its visibility so a test could name it would enlarge the documented
 * public surface to suit the test, which is the wrong trade. Reflection reaches the real class instead of a
 * copy, which is what makes these assertions worth having.
 */
@DisplayName("Request boundary - the body bound and the parser bounds that precede deserialization")
class RequestBoundaryHardeningTest {

    /** The bound the filter enforces, restated here so a silent change to it fails a test. */
    private static final int EXPECTED_MAX_BODY_BYTES = 16 * 1024;

    /**
     * The exact refusal body an oversized request receives, whichever shape it took.
     *
     * <p>Written out in full rather than matched loosely, because the point of the remediation is that this
     * body is <em>identical</em> to what a controller produces for a rejection: the same members, in the same
     * order, with a {@code correlationId} that is present even when no correlation is in scope. A loose
     * assertion would pass against a body that had quietly lost a member.
     *
     * <p>{@code unavailable} is the correct correlation value here: these tests drive the filter directly, so
     * {@code com.cardemo.observability.CorrelationIdFilter} - which registers far ahead of the security chain
     * in a running application - has not populated the diagnostic context.
     */
    private static final String EXPECTED_PAYLOAD_TOO_LARGE_BODY =
            "{\"type\":\"about:blank\",\"title\":\"Request body too large\",\"status\":413,"
                    + "\"detail\":\"The request body exceeds the number of bytes this application will read "
                    + "from one request.\",\"errorCode\":\"CARDDEMO-REQUEST-BODY-TOO-LARGE\","
                    + "\"correlationId\":\"unavailable\"}";

    /** The binary name of the filter under test, a private nested class of the security configuration. */
    private static final String FILTER_CLASS_NAME =
            "com.cardemo.config.SecurityConfig$RequestBodyLimitFilter";

    /**
     * The binary name of the media-type screen, the other private nested filter of the same configuration.
     *
     * <p>Reached reflectively for the reason given on {@link #FILTER_CLASS_NAME}: it is an implementation
     * detail of one filter chain, and widening its visibility so a test could name it would enlarge the
     * documented public surface to suit the test.
     */
    private static final String MEDIA_TYPE_FILTER_CLASS_NAME =
            "com.cardemo.config.SecurityConfig$RequestMediaTypeFilter";

    /** The anonymous route the bound exists to protect. */
    private static final String SIGN_ON_URI = "/api/auth/signon";

    /**
     * Instantiates the request-body filter reflectively.
     *
     * @return the real filter, not a reimplementation of it
     * @throws ReflectiveOperationException if the class or its constructor cannot be reached, which means the
     *     filter was renamed or removed and this test must be revisited rather than silently skipped
     */
    private static Filter bodyLimitFilter() throws ReflectiveOperationException {
        return instantiate(FILTER_CLASS_NAME);
    }

    /**
     * Instantiates the media-type screen reflectively.
     *
     * @return the real filter, not a reimplementation of it
     * @throws ReflectiveOperationException if the class or its constructor cannot be reached, which means the
     *     filter was renamed or removed and this test must be revisited rather than silently skipped
     */
    private static Filter mediaTypeFilter() throws ReflectiveOperationException {
        return instantiate(MEDIA_TYPE_FILTER_CLASS_NAME);
    }

    /**
     * Instantiates one of the configuration's private nested filters by binary name.
     *
     * @param binaryName the binary name of the filter class
     * @return the constructed filter
     * @throws ReflectiveOperationException if the class or its no-argument constructor cannot be reached
     */
    private static Filter instantiate(final String binaryName) throws ReflectiveOperationException {
        final Class<?> type = Class.forName(binaryName);
        final Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return (Filter) constructor.newInstance();
    }

    /**
     * Builds a POST to the anonymous sign-on route carrying a body of the given size.
     *
     * @param bodyBytes how many bytes the body should carry
     * @param declareContentLength whether the request declares {@code Content-Length}; false models a chunked
     *     request, which is the shape that declares no length at all
     * @return the prepared request
     */
    private static MockHttpServletRequest signOnRequestOf(
            final int bodyBytes, final boolean declareContentLength) {

        final byte[] body = "x".repeat(bodyBytes).getBytes(StandardCharsets.UTF_8);
        final MockHttpServletRequest request = declareContentLength
                ? new MockHttpServletRequest("POST", SIGN_ON_URI)
                : new MockHttpServletRequest("POST", SIGN_ON_URI) {
                    @Override
                    public long getContentLengthLong() {
                        // A chunked request declares no length. MockHttpServletRequest derives one from the
                        // content it was given, so the absence has to be stated rather than arranged.
                        return -1L;
                    }
                };
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(body);
        return request;
    }

    /**
     * Builds a POST whose declared length is within the bound while its content is not.
     *
     * <p>A client that lies about {@code Content-Length}. A real container refuses to deliver more bytes than
     * the declaration announced, so this shape is not reachable through Tomcat; it is constructed here because
     * it is the one path on which the bounded stream - the filter's second line of defence - is still
     * exercised, and "expected never to fire" is not the same as "cannot fire".
     *
     * @param bodyBytes how many bytes the body actually carries
     * @return the prepared request, declaring a length of {@value #EXPECTED_MAX_BODY_BYTES} regardless
     */
    private static MockHttpServletRequest underDeclaredRequestOf(final int bodyBytes) {
        final byte[] body = "x".repeat(bodyBytes).getBytes(StandardCharsets.UTF_8);
        final MockHttpServletRequest request = new MockHttpServletRequest("POST", SIGN_ON_URI) {
            @Override
            public long getContentLengthLong() {
                return EXPECTED_MAX_BODY_BYTES;
            }
        };
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(body);
        return request;
    }

    /**
     * The byte bound, applied before authorization and before MVC binding.
     *
     * <p>Finding LOW-004, severity Low. This group used to be titled "applied before authentication", which the
     * measured chain contradicts: {@code SecurityContextHolderFilter}, {@code JwtAuthenticationFilter},
     * {@code HeaderWriterFilter}, {@code RequestBodyLimitFilter}, {@code RequestMediaTypeFilter}, and
     * {@code AuthorizationFilter} last. The bound runs <em>after</em> the bearer filter.
     *
     * <p>What the tests prove is unchanged, because the bearer filter refuses nothing - it populates the
     * security context when a token is present and passes an anonymous request through. The refusal for want
     * of authorization is the last filter, and argument resolution is later still, so an oversized body from an
     * entirely unauthenticated caller is still answered {@code 413} without any handler running. That is what
     * these cases assert, and it is asserted by driving the filter directly with no principal at all.
     */
    @Nested
    @DisplayName("the byte bound, applied before authorization and MVC binding")
    class ByteBound {

        @Test
        @DisplayName("a body declaring more than the bound is refused with 413 and never reaches the chain")
        void anOverLongDeclaredBodyIsRefused() throws Exception {
            final MockHttpServletRequest request = signOnRequestOf(EXPECTED_MAX_BODY_BYTES + 1, true);
            final MockHttpServletResponse response = new MockHttpServletResponse();
            final MockFilterChain chain = new MockFilterChain();

            bodyLimitFilter().doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("413 is the status that names the actual problem. A 400 would say the body was "
                            + "malformed, which it was not - it was too large.")
                    .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
            assertThat(chain.getRequest())
                    .as("and nothing downstream ran: the refusal is on the DECLARATION, so not one body byte "
                            + "was read. This is the property that makes the bound cheap.")
                    .isNull();
            assertThat(response.getContentType())
                    .as("the refusal is an RFC 9457 problem document, the same media type every controller "
                            + "answers a rejection with, and with no charset parameter so the two headers are "
                            + "byte-comparable")
                    .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            assertThat(response.getContentAsString())
                    .as("""
                        the body carries the shared envelope. An EMPTY body was the previous behaviour and was \
                        reported as a High-severity contract defect: a client met a populated problem \
                        document from every controller and an empty one from the body bound, so no single \
                        error handler covered the surface and a rejected request could not be tied back to \
                        its own log line.""")
                    .isEqualTo(EXPECTED_PAYLOAD_TOO_LARGE_BODY);
            assertThat(response.getContentAsString())
                    .as("and it discloses nothing about the request, matching the server.error settings that "
                            + "emit no message, no binding detail and no exception class - in particular it "
                            + "names neither the bound nor the declared length")
                    .doesNotContain(String.valueOf(EXPECTED_MAX_BODY_BYTES))
                    .doesNotContain("xxxxx");
        }

        @Test
        @DisplayName("a body at exactly the bound is admitted, so the limit is inclusive")
        void aBodyAtTheBoundIsAdmitted() throws Exception {
            final MockHttpServletRequest request = signOnRequestOf(EXPECTED_MAX_BODY_BYTES, true);
            final MockHttpServletResponse response = new MockHttpServletResponse();
            final MockFilterChain chain = new MockFilterChain();

            bodyLimitFilter().doFilter(request, response, chain);

            assertThat(chain.getRequest())
                    .as("the off-by-one that would refuse a legitimate maximum-size body is excluded")
                    .isNotNull();
            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        }

        @Test
        @DisplayName("a realistic largest legitimate body passes with room to spare")
        void theLargestLegitimateBodyPasses() throws Exception {
            // AccountUpdateRequest is the largest request type: 118 length-constrained fields totalling
            // 1,567 characters of field data, which with its property names and JSON punctuation reaches
            // roughly 3.9 KB fully populated.
            final MockHttpServletRequest request = signOnRequestOf(4096, true);
            final MockHttpServletResponse response = new MockHttpServletResponse();
            final MockFilterChain chain = new MockFilterChain();

            bodyLimitFilter().doFilter(request, response, chain);

            assertThat(chain.getRequest())
                    .as("a bound that refused a legitimate body would be an outage, not a control")
                    .isNotNull();
            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        }

        @Test
        @DisplayName("a chunked body declaring no length is refused up front, not lazily during the read")
        void aChunkedBodyIsRefusedUpFront() throws Exception {
            final MockHttpServletRequest request = signOnRequestOf(EXPECTED_MAX_BODY_BYTES * 2, false);
            final MockHttpServletResponse response = new MockHttpServletResponse();
            final MockFilterChain chain = new MockFilterChain();

            bodyLimitFilter().doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("""
                        413, exactly as for a declared oversize. This is the case that matters: an attacker \
                        chooses the encoding, so the shape that declares no length is the shape they would \
                        send, and it must not buy a different answer.""")
                    .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
            assertThat(chain.getRequest())
                    .as("""
                        and nothing downstream ran. The bound reads the length-less body itself, up to one \
                        byte past the limit, so the refusal happens before authorization and before any \
                        handler - rather than lazily from inside a body read, where Spring converts the \
                        IOException into HttpMessageNotReadableException and the answer becomes 400.""")
                    .isNull();
            assertThat(response.getContentAsString())
                    .as("carrying the same envelope the declared arm carries")
                    .isEqualTo(EXPECTED_PAYLOAD_TOO_LARGE_BODY);
        }

        @Test
        @DisplayName("both oversized shapes produce a byte-identical refusal, whatever the transfer encoding")
        void theTwoOversizedShapesAreIndistinguishable() throws Exception {
            final MockHttpServletResponse declared = new MockHttpServletResponse();
            final MockHttpServletResponse undeclared = new MockHttpServletResponse();

            bodyLimitFilter().doFilter(signOnRequestOf(EXPECTED_MAX_BODY_BYTES + 1, true), declared,
                    new MockFilterChain());
            bodyLimitFilter().doFilter(signOnRequestOf(EXPECTED_MAX_BODY_BYTES + 1, false), undeclared,
                    new MockFilterChain());

            assertThat(undeclared.getStatus())
                    .as("""
                        the Medium-severity finding this pins: equal violations previously produced different \
                        statuses purely because one request declared Content-Length and the other did not, so \
                        a caller could select its own error code by choosing an encoding.""")
                    .isEqualTo(declared.getStatus());
            assertThat(undeclared.getContentType()).isEqualTo(declared.getContentType());
            assertThat(undeclared.getContentAsString())
                    .as("and the bodies agree byte for byte, not merely in shape")
                    .isEqualTo(declared.getContentAsString());
        }

        @Test
        @DisplayName("the bulk read is bounded, not only the single-byte read")
        void theBulkReadIsBounded() throws Exception {
            // A request that UNDER-DECLARES its length: the declaration passes the up-front check, so the
            // request proceeds wrapped, and the wrapper is what refuses the bytes the declaration disowned.
            // That path is defence in depth - a container enforces Content-Length itself - and it is the only
            // one on which the bounded stream is still reachable, which is exactly why it is asserted here.
            final MockHttpServletRequest request = underDeclaredRequestOf(EXPECTED_MAX_BODY_BYTES * 2);
            final MockFilterChain chain = new MockFilterChain();
            bodyLimitFilter().doFilter(request, new MockHttpServletResponse(), chain);

            final HttpServletRequest wrapped = (HttpServletRequest) chain.getRequest();
            final byte[] buffer = new byte[8192];

            assertThatExceptionOfType(IOException.class)
                    .as("every buffered reader calls the bulk form, so bounding only the single-byte form "
                            + "would look correct and bound nothing in practice")
                    .isThrownBy(() -> {
                        try (var stream = wrapped.getInputStream()) {
                            while (stream.read(buffer, 0, buffer.length) > 0) {
                                // Drain until the bound refuses, which is the assertion.
                            }
                        }
                    });
        }

        @Test
        @DisplayName("the single-byte read is bounded too, on the same under-declared path")
        void theSingleByteReadIsBounded() throws Exception {
            final MockFilterChain chain = new MockFilterChain();
            bodyLimitFilter().doFilter(underDeclaredRequestOf(EXPECTED_MAX_BODY_BYTES * 2),
                    new MockHttpServletResponse(), chain);

            final HttpServletRequest wrapped = (HttpServletRequest) chain.getRequest();

            assertThatExceptionOfType(IOException.class)
                    .as("both overloads are overridden, so neither is a way past the bound")
                    .isThrownBy(() -> {
                        try (var stream = wrapped.getInputStream()) {
                            while (stream.read() >= 0) {
                                // Drain one byte at a time until the bound refuses.
                            }
                        }
                    });
        }

        @Test
        @DisplayName("a length-less JSON body within the bound reaches the chain byte for byte")
        void aLengthLessJsonBodyIsReplayedIntact() throws Exception {
            final String document = "{\"userId\":\"USER0001\",\"password\":\"not-a-real-credential\"}";
            final MockHttpServletRequest request = new MockHttpServletRequest("POST", SIGN_ON_URI) {
                @Override
                public long getContentLengthLong() {
                    return -1L;
                }
            };
            request.setContentType(MediaType.APPLICATION_JSON_VALUE);
            request.setContent(document.getBytes(StandardCharsets.UTF_8));

            final MockFilterChain chain = new MockFilterChain();
            bodyLimitFilter().doFilter(request, new MockHttpServletResponse(), chain);

            final HttpServletRequest wrapped = (HttpServletRequest) chain.getRequest();

            assertThat(new String(wrapped.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                    .as("""
                        the up-front read consumes the body, so the wrapper has to hand the captured bytes \
                        on. If it did not, every length-less request would reach its handler with an empty \
                        body - a far worse defect than the one the read exists to fix.""")
                    .isEqualTo(document);
            assertThat(wrapped.getReader().readLine())
                    .as("both accessors replay, and each call yields a fresh stream rather than an exhausted "
                            + "one, because a message converter may reach for either")
                    .isEqualTo(document);
        }

        @Test
        @DisplayName("a chunked body within the bound is readable in full")
        void aChunkedBodyWithinTheBoundIsReadable() throws Exception {
            final MockHttpServletRequest request = signOnRequestOf(1024, false);
            final MockFilterChain chain = new MockFilterChain();
            bodyLimitFilter().doFilter(request, new MockHttpServletResponse(), chain);

            final HttpServletRequest wrapped = (HttpServletRequest) chain.getRequest();

            assertThat(wrapped.getInputStream().readAllBytes())
                    .as("the wrapper must be transparent below the bound, or it would corrupt every "
                            + "legitimate request it touches")
                    .hasSize(1024);
        }
    }

    @Nested
    @DisplayName("the parser bounds, applied to the converters Spring assembled")
    class ParserBounds {

        @Test
        @DisplayName("every Jackson converter receives the constraints, and non-Jackson ones are untouched")
        void everyJacksonConverterIsConstrained() {
            final MappingJackson2HttpMessageConverter jackson =
                    new MappingJackson2HttpMessageConverter();
            final StringHttpMessageConverter strings = new StringHttpMessageConverter();
            final List<HttpMessageConverter<?>> converters = new ArrayList<>(List.of(jackson, strings));

            new WebConfig().extendMessageConverters(converters);

            final StreamReadConstraints applied =
                    jackson.getObjectMapper().getFactory().streamReadConstraints();

            assertThat(applied.getMaxNestingDepth())
                    .as("the deepest structure any request type declares is three - the request, one snapshot "
                            + "group, that group's leaves - so 8 admits every legitimate document while "
                            + "refusing the thousands-deep one whose only purpose is recursion")
                    .isEqualTo(WebConfig.MAX_JSON_NESTING_DEPTH);
            assertThat(applied.getMaxStringLength()).isEqualTo(WebConfig.MAX_JSON_STRING_LENGTH);
            assertThat(applied.getMaxNumberLength()).isEqualTo(WebConfig.MAX_JSON_NUMBER_LENGTH);
            assertThat(applied.getMaxNameLength()).isEqualTo(WebConfig.MAX_JSON_NAME_LENGTH);
            assertThat(applied.getMaxDocumentLength()).isEqualTo(WebConfig.MAX_JSON_DOCUMENT_LENGTH);

            assertThat(converters)
                    .as("the list is not replaced or reordered: Spring owns it, and a converter removed here "
                            + "would silently change what the application can consume")
                    .containsExactly(jackson, strings);
        }

        @Test
        @DisplayName("an empty converter list is handled without error, so ordering cannot break startup")
        void anEmptyConverterListIsSafe() {
            final List<HttpMessageConverter<?>> empty = new ArrayList<>();

            new WebConfig().extendMessageConverters(empty);

            assertThat(empty)
                    .as("this hook can be called before any converter is present depending on "
                            + "configuration order, and a startup failure there would be a hard outage")
                    .isEmpty();
        }

        @Test
        @DisplayName("the document bound equals the filter's byte bound, so the two cannot disagree")
        void theDocumentBoundMatchesTheByteBound() {
            assertThat(WebConfig.MAX_JSON_DOCUMENT_LENGTH)
                    .as("""
                        two bounds on the same thing that differ leave a window in which one permits what \
                        the other refuses, and whichever is larger becomes the real limit while the smaller \
                        one goes on being cited as though it were.""")
                    .isEqualTo(EXPECTED_MAX_BODY_BYTES);
        }

        @Test
        @DisplayName("each bound clears the widest field contract, so no legitimate value is clipped")
        void eachBoundClearsTheFieldContracts() {
            assertThat(WebConfig.MAX_JSON_STRING_LENGTH)
                    .as("the widest PIC X(n) any symbolic map contributes is 80 characters. The margin is "
                            + "deliberate: a value between 80 and this bound must be refused by the field's "
                            + "own @Size, which reports WHICH field was wrong, rather than by an opaque "
                            + "parse failure that reports only that parsing stopped")
                    .isGreaterThan(80);
            assertThat(WebConfig.MAX_JSON_NUMBER_LENGTH)
                    .as("the longest numeric token any contract admits is the 16-digit identifier and the "
                            + "12-character signed amount")
                    .isGreaterThan(WebConfig.IDENTIFIER_MAX_DIGITS);
            assertThat(WebConfig.MAX_JSON_NESTING_DEPTH)
                    .as("three levels are used; the bound must exceed that without approaching Jackson's "
                            + "own default of 1000")
                    .isGreaterThan(3)
                    .isLessThan(1000);
        }
    }

    /**
     * The header bound, which is read before the body and before any filter in the security chain.
     *
     * <p><strong>Finding CFG-004, severity Medium - these tests pin both halves.</strong> The shipped profile
     * declared {@code server.tomcat.max-http-request-header-size}, and nothing binds that key. Spring Boot
     * 3.5.11's own configuration metadata declares {@code server.max-http-request-header-size} as a
     * {@code DataSize} defaulting to 8 KB; under the {@code server.tomcat} prefix it declares only
     * {@code max-http-response-header-size} and {@code max-part-header-size}. {@code ServerProperties} ignores
     * unknown fields, so the misplaced key was accepted in silence and bound nothing.
     *
     * <p>It happened to carry the container's own default, so nothing observable changed - which is precisely
     * what makes an inert key worth a finding rather than a shrug: an operator who <em>lowered</em> it to
     * narrow the pre-authentication surface would have been protected by nothing at all, and no warning
     * anywhere would have said so. The second test is therefore the substantive one: it proves the old
     * spelling really is inert, so the move was necessary rather than cosmetic.
     */
    @Nested
    @DisplayName("the header bound, and the property spelling that actually binds it")
    class TheHeaderBound {

        /** The bound the profile declares, and Tomcat's own default. */
        private static final DataSize EXPECTED_HEADER_BOUND = DataSize.ofKilobytes(8);

        /** A value distinguishable from the default, so a binding either happened or did not. */
        private static final String DISTINGUISHABLE = "17KB";

        /** Binds the framework's own properties class, which is the only authority on what binds. */
        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(ServerPropertiesHolder.class);

        @Test
        @DisplayName("server.max-http-request-header-size binds, and the shipped profile declares it there")
        void theBoundSpellingBindsAndIsWhatTheProfileDeclares() {
            runner.withPropertyValues("server.max-http-request-header-size=" + DISTINGUISHABLE)
                    .run(context -> assertThat(context.getBean(ServerProperties.class)
                            .getMaxHttpRequestHeaderSize())
                            .as("the framework binds this spelling, so a deployment that narrows the "
                                    + "pre-authentication header surface actually narrows it")
                            .isEqualTo(DataSize.parse(DISTINGUISHABLE)));

            final String profile = readProfile();
            assertThat(profile)
                    .as("declared under server:, at the two-space indentation of a server child")
                    .contains("\n  max-http-request-header-size: 8KB");
            assertThat(profile)
                    .as("and not at the four-space indentation of a server.tomcat child, which binds nothing")
                    .doesNotContain("\n    max-http-request-header-size:");
        }

        @Test
        @DisplayName("server.tomcat.max-http-request-header-size binds nothing, which is why the key moved")
        void theOldSpellingBindsNothing() {
            runner.withPropertyValues("server.tomcat.max-http-request-header-size=" + DISTINGUISHABLE)
                    .run(context -> {
                        assertThat(context)
                                .as("an unknown field under a prefix that ignores them is not a startup "
                                        + "failure - it is silence, which is the whole hazard")
                                .hasNotFailed();
                        assertThat(context.getBean(ServerProperties.class).getMaxHttpRequestHeaderSize())
                                .as("the value set under the tomcat prefix reaches nothing: the bound stays "
                                        + "at the framework default of 8 KB")
                                .isEqualTo(EXPECTED_HEADER_BOUND);
                    });
        }

        /**
         * Reads the shipped profile, so the assertion is against the file a deployment actually loads.
         *
         * @return the profile text, never {@code null}
         */
        private String readProfile() {
            try {
                return Files.readString(Path.of("src", "main", "resources", "application.yml"));
            } catch (final IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        }

        /** Enables the framework's own {@code server.*} binding, and contributes nothing else. */
        @EnableConfigurationProperties(ServerProperties.class)
        static class ServerPropertiesHolder {
        }
    }

    /**
     * The control-character screen over a request body, and the boundary it must not cross.
     *
     * <p><strong>Finding F-1, severity High - these tests pin both halves of the remediation.</strong> A
     * {@code U+0000} inside a JSON string travelled unexamined from an inbound body into a character column,
     * where PostgreSQL refused the byte sequence; the refusal surfaced as an input-output failure on
     * {@code PUT /api/admin/users/{userId}} and as an abend on {@code POST /api/admin/users} rather than as the
     * field-level {@code 400} it is.
     *
     * <p><strong>The second test here is the one that matters most, and it exists because the first fix was
     * wrong.</strong> Registering the screen on {@code getObjectMapper()} put it on the application-wide
     * {@code ObjectMapper} bean, which three other consumers share deliberately: the queue boundary, the
     * snapshot token reader and the lookup table loader. None reads an inbound request, and the queue boundary
     * is contracted to carry hostile text by <em>escaping</em> it rather than by refusing it - so the leak broke
     * a round trip that is supposed to succeed. The screen therefore rides on a copy handed back to the
     * converter. A test that only checked the screen fires would have passed against the broken version.
     */
    @Nested
    @DisplayName("the control-character screen, and the mapper boundary it must not cross")
    class TheControlCharacterScreen {

        /** A value carrying a NUL among ordinary characters - the exact shape the finding reported. */
        private static final String MIXED_WITH_NUL = "{\"userId\":\"AB\\u0000CD\"}";

        /** The same document with no control character in it, to prove the screen is not simply refusing. */
        private static final String CLEAN = "{\"userId\":\"ABCD\"}";

        /**
         * The binding target, a map of string to string.
         *
         * <p>Typed rather than raw, and string-valued rather than {@code Object}-valued, because the screen
         * wraps the deserializer Jackson chose for {@code String} - so a target whose values are untyped would
         * be read as {@code TextNode} instances by a different deserializer and would not exercise the screen
         * at all. That is the same reason it fires on a request type's {@code String} field and does not fire
         * when something reads a body as an untyped tree.
         */
        private static final TypeReference<Map<String, String>> STRING_VALUES = new TypeReference<>() { };

        /**
         * Sole constructor, invoked by the test framework.
         */
        TheControlCharacterScreen() {
            // JUnit instantiates a @Nested class per test; no state to establish.
        }

        /**
         * Runs the configuration over one Jackson converter and returns that converter.
         *
         * @return the converter after {@code extendMessageConverters} has been applied to it
         */
        private MappingJackson2HttpMessageConverter configuredConverter() {
            final MappingJackson2HttpMessageConverter jackson =
                    new MappingJackson2HttpMessageConverter();
            final List<HttpMessageConverter<?>> converters = new ArrayList<>(List.of(jackson));
            new WebConfig().extendMessageConverters(converters);
            return jackson;
        }

        @Test
        @DisplayName("a control character mixed into an inbound string value is refused by the parser")
        void aControlCharacterInABodyIsRefused() {
            final ObjectMapper inbound = configuredConverter().getObjectMapper();

            assertThatExceptionOfType(JacksonException.class)
                    .as("""
                        the refusal has to be raised inside the parser, because that is what makes Jackson \
                        decorate it with the property path and Spring turn it into the read failure every \
                        controller already answers with a 400 naming the field.""")
                    .isThrownBy(() -> inbound.readValue(MIXED_WITH_NUL, STRING_VALUES))
                    .withMessageContaining("control characters");
        }

        @Test
        @DisplayName("the screen reaches the converter's mapper and NOT the shared bean it was copied from")
        void theScreenDoesNotLeakOntoTheSharedMapper() throws Exception {
            final MappingJackson2HttpMessageConverter jackson =
                    new MappingJackson2HttpMessageConverter();
            final ObjectMapper shared = jackson.getObjectMapper();
            final List<HttpMessageConverter<?>> converters = new ArrayList<>(List.of(jackson));

            new WebConfig().extendMessageConverters(converters);

            assertThat(jackson.getObjectMapper())
                    .as("the converter must be given the screened copy, or the body screen does not run at all")
                    .isNotSameAs(shared);

            assertThat(shared.readValue(MIXED_WITH_NUL, STRING_VALUES))
                    .as("""
                        the mapper the converter started from must still read hostile text unchanged. The \
                        queue boundary, the snapshot token reader and the lookup loader are handed the shared \
                        bean on purpose, and the queue boundary is documented to ESCAPE hostile text rather \
                        than refuse it - registering the screen on the shared bean broke that round trip.""")
                    .containsEntry("userId", "AB\u0000CD");
        }

        @Test
        @DisplayName("a clean body still binds, and the parser bounds ride on the same copy")
        void aCleanBodyIsUntouchedAndStillBounded() throws Exception {
            final MappingJackson2HttpMessageConverter jackson = configuredConverter();
            final ObjectMapper inbound = jackson.getObjectMapper();

            assertThat(inbound.readValue(CLEAN, STRING_VALUES))
                    .as("the screen must be invisible to every value that carries no control character")
                    .containsEntry("userId", "ABCD");

            assertThat(inbound.getFactory().streamReadConstraints().getMaxNestingDepth())
                    .as("""
                        the copy has to carry the parser bounds too. Relying on the copy constructor to \
                        propagate them would make a bound that can silently lapse on a library upgrade.""")
                    .isEqualTo(WebConfig.MAX_JSON_NESTING_DEPTH);
        }
    }

    @Nested
    @DisplayName("the framework boundary - refusals decided before, or instead of, a controller method")
    class FrameworkBoundary {

        /** The six members every refusal publishes, whichever boundary produced it. */
        private static final List<String> ENVELOPE_MEMBERS =
                List.of("\"type\"", "\"title\"", "\"status\"", "\"detail\"", "\"errorCode\"",
                        "\"correlationId\"");

        /**
         * Renders a refusal through the real resolver and hands back the response it wrote.
         *
         * @param failure the exception to resolve; never null
         * @return the response the resolver wrote onto, or null when it declined the exception
         */
        private MockHttpServletResponse resolve(final Exception failure) {
            final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/transactions");
            final MockHttpServletResponse response = new MockHttpServletResponse();
            final ModelAndView answered = new WebConfig.FrameworkBoundaryExceptionResolver()
                    .resolveException(request, response, null, failure);
            return answered == null ? null : response;
        }

        /**
         * Asserts that a body is the refusal envelope and nothing else.
         *
         * @param body the response body to inspect; never null
         */
        private void assertIsEnvelope(final String body) {
            assertThat(body).as("a refusal must carry the same six members from every boundary")
                    .contains(ENVELOPE_MEMBERS);
            assertThat(body).as("the correlation identifier must be a value, never the empty string")
                    .doesNotContain("\"correlationId\":\"\"");
        }

        @Test
        @DisplayName("a wildcard Content-Type is refused before the body is read, not after")
        void aWildcardContentTypeIsRefusedBeforeTheBodyIsRead() {
            final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/signon");
            request.setContentType("application/*+json");

            assertThatExceptionOfType(HttpMediaTypeNotSupportedException.class)
                    .as("""
                            A wildcard request type SATISFIES a consumes condition, because MediaType.includes \
                            asks whether the mapping's type is compatible with the request's rather than the \
                            other way round. The handler is therefore selected and the failure surfaces inside \
                            the argument resolver as a bare IllegalArgumentException, which is a 500. \
                            Screening it here turns the condition into the 415 it actually is.""")
                    .isThrownBy(() -> new WebConfig.ConcreteContentTypeInterceptor()
                            .preHandle(request, new MockHttpServletResponse(), new Object()));
        }

        @Test
        @DisplayName("every non-concrete shape is refused, including the plus-suffixed wildcard")
        void everyNonConcreteShapeIsRefused() {
            for (final String declared : List.of("*/*", "application/*", "application/*+json", "*/json")) {
                final MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/cards");
                request.setContentType(declared);

                assertThatExceptionOfType(HttpMediaTypeNotSupportedException.class)
                        .as("declared type %s is not concrete", declared)
                        .isThrownBy(() -> new WebConfig.ConcreteContentTypeInterceptor()
                                .preHandle(request, new MockHttpServletResponse(), new Object()));
            }
        }

        @Test
        @DisplayName("a concrete Content-Type is admitted, so the screen cannot break a working request")
        void aConcreteContentTypeIsAdmitted() throws Exception {
            for (final String declared : List.of("application/json", "application/json;charset=UTF-8",
                    "text/plain")) {
                final MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/cards");
                request.setContentType(declared);

                assertThat(new WebConfig.ConcreteContentTypeInterceptor()
                        .preHandle(request, new MockHttpServletResponse(), new Object()))
                        .as("""
                                %s is concrete. Whether the operation can READ it is the mapping's decision, \
                                not this screen's - text/plain is refused one layer earlier, and this screen \
                                must not duplicate that judgement.""", declared)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("an absent or blank Content-Type is not this screen's business")
        void anAbsentContentTypeIsAdmitted() throws Exception {
            final MockHttpServletRequest absent = new MockHttpServletRequest("GET", "/api/cards");

            assertThat(new WebConfig.ConcreteContentTypeInterceptor()
                    .preHandle(absent, new MockHttpServletResponse(), new Object()))
                    .as("a request without content legitimately declares no type; the mapping already "
                            + "refuses an absent type for an operation that needs a body")
                    .isTrue();

            final MockHttpServletRequest blank = new MockHttpServletRequest("GET", "/api/cards");
            blank.addHeader(HttpHeaders.CONTENT_TYPE, "   ");

            assertThat(new WebConfig.ConcreteContentTypeInterceptor()
                    .preHandle(blank, new MockHttpServletResponse(), new Object()))
                    .as("a blank header is indistinguishable from an absent one and is treated the same")
                    .isTrue();
        }

        @Test
        @DisplayName("the screen stands aside on an ERROR dispatch, so a refusal cannot re-enter it")
        void theScreenStandsAsideOnAnErrorDispatch() throws Exception {
            final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/error");
            request.setDispatcherType(DispatcherType.ERROR);
            request.setContentType("*/*");

            assertThat(new WebConfig.ConcreteContentTypeInterceptor()
                    .preHandle(request, new MockHttpServletResponse(), new Object()))
                    .as("the original request's wildcard type is still on the ERROR dispatch; refusing it "
                            + "again while a refusal is being rendered would be circular")
                    .isTrue();
        }

        @Test
        @DisplayName("an unparseable Content-Type becomes a 415 rather than a 500")
        void anUnparseableContentTypeBecomesA415() {
            final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/transactions");
            request.addHeader(HttpHeaders.CONTENT_TYPE, "application/");

            assertThatExceptionOfType(HttpMediaTypeNotSupportedException.class)
                    .as("not reachable through the mapping today, which refuses it first, but the screen "
                            + "must not convert a malformed header into an internal error if it ever is")
                    .isThrownBy(() -> new WebConfig.ConcreteContentTypeInterceptor()
                            .preHandle(request, new MockHttpServletResponse(), new Object()));
        }

        @Test
        @DisplayName("an unreadable media type is answered 415, enveloped, and advertises what would work")
        void anUnreadableMediaTypeIsAnswered415() throws Exception {
            final MockHttpServletResponse response = resolve(new HttpMediaTypeNotSupportedException(
                    MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)));

            assertThat(response).isNotNull();
            assertThat(response.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value());
            assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            assertThat(response.getHeader(HttpHeaders.ACCEPT))
                    .as("RFC 9110 asks a 415 to say what would have been acceptable")
                    .isEqualTo(MediaType.APPLICATION_JSON_VALUE);
            assertThat(response.getContentAsString()).contains("CARDDEMO-UNSUPPORTED-MEDIA-TYPE");
            assertIsEnvelope(response.getContentAsString());
        }

        @Test
    @DisplayName("an unsatisfiable Accept is answered 406 WITH a body, never with an empty one")
        void anUnsatisfiableAcceptIsAnswered406WithABody() throws Exception {
            final MockHttpServletResponse response =
                    resolve(new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)));

            assertThat(response).isNotNull();
            assertThat(response.getStatus()).isEqualTo(HttpStatus.NOT_ACCEPTABLE.value());
            assertThat(response.getContentType())
                    .as("""
                            The reason the 406 was empty is that the error render could not be negotiated \
                            either. Declaring the type explicitly takes negotiation out of the refusal path, \
                            which is the only way the body can exist at all.""")
                    .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            assertThat(response.getContentAsString()).contains("CARDDEMO-NOT-ACCEPTABLE");
            assertIsEnvelope(response.getContentAsString());
        }

        @Test
        @DisplayName("a wrong method is answered 405, enveloped, and keeps the Allow header")
        void aWrongMethodIsAnswered405AndKeepsAllow() throws Exception {
            final MockHttpServletResponse response = resolve(new HttpRequestMethodNotSupportedException(
                    "GET", Set.of(HttpMethod.PUT.name(), HttpMethod.DELETE.name())));

            assertThat(response).isNotNull();
            assertThat(response.getStatus()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value());
            assertThat(response.getHeader(HttpHeaders.ALLOW))
                    .as("the Allow header is the one piece of information that makes a 405 actionable")
                    .contains("PUT")
                    .contains("DELETE");
            assertThat(response.getContentAsString()).contains("CARDDEMO-METHOD-NOT-ALLOWED");
            assertIsEnvelope(response.getContentAsString());
        }

        @Test
        @DisplayName("an unmapped path is answered 404 and enveloped")
        void anUnmappedPathIsAnswered404() throws Exception {
            final MockHttpServletResponse response =
                    resolve(new NoResourceFoundException(HttpMethod.GET, "/api/nosuchthing"));

            assertThat(response).isNotNull();
            assertThat(response.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
            assertThat(response.getContentAsString()).contains("CARDDEMO-RESOURCE-NOT-FOUND");
            assertIsEnvelope(response.getContentAsString());
        }

        @Test
        @DisplayName("the resolver claims exactly four conditions and declines everything else")
        void theResolverClaimsExactlyFourConditions() {
            assertThat(resolve(new IllegalStateException("a programming error")))
                    .as("""
                            This is what keeps the resolver from becoming a general-purpose handler. An \
                            exception a controller owns must reach that controller's own @ExceptionHandler, \
                            and an exception nobody owns must stay a 500 rather than be dressed up as a 4xx.""")
                    .isNull();
            assertThat(resolve(new IllegalArgumentException("a bad argument"))).isNull();
            assertThat(resolve(new RuntimeException("anything at all"))).isNull();
        }

        /**
         * Renders a failure through the real terminal resolver and hands back the response it wrote.
         *
         * @param failure the exception to resolve; never null
         * @return the response the resolver wrote onto, or null when it declined the exception
         */
        private MockHttpServletResponse resolveResidual(final Exception failure) {
            final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/signon");
            final MockHttpServletResponse response = new MockHttpServletResponse();
            final ModelAndView answered = new WebConfig.UnhandledFailureExceptionResolver()
                    .resolveException(request, response, null, failure);
            return answered == null ? null : response;
        }

        @Test
        @DisplayName("an unhandled failure is answered with the 500 envelope, not Spring's default body")
        void anUnhandledFailureIsAnsweredWithTheEnvelope() throws Exception {
            final MockHttpServletResponse response =
                    resolveResidual(new IllegalStateException("the store went away mid-request"));

            assertThat(response)
                    .as("""
                            This is the residual class every other resolver declines. Before it was claimed \
                            the container answered it through Spring Boot's /error page with \
                            {timestamp,status,error,path} - no errorCode, no correlationId and \
                            Content-Type application/json - which is the one failure class an operator most \
                            needs to be able to join to a log record.""")
                    .isNotNull();
            assertThat(response.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
            assertThat(response.getContentType())
                    .as("the announced media type for an error body, matching every other boundary")
                    .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            assertThat(response.getContentAsString()).contains("CARDDEMO-INTERNAL-FAILURE");
            assertIsEnvelope(response.getContentAsString());
            assertThat(response.getContentAsString())
                    .as("""
                            Spring's default attributes must not survive anywhere in the body: an operator \
                            branching on errorCode has to find one shape, and `error` and `path` are \
                            precisely the two members the default body carried instead.""")
                    .doesNotContain("\"error\"")
                    .doesNotContain("\"path\"")
                    .doesNotContain("\"timestamp\"");
        }

        @Test
        @DisplayName("a security denial is declined, so ExceptionTranslationFilter still publishes 401/403")
        void aSecurityDenialIsDeclined() {
            assertThat(resolveResidual(new AccessDeniedException("Access Denied")))
                    .as("""
                            ExceptionTranslationFilter sits OUTSIDE the dispatcher, so an authorization \
                            denial has to keep propagating for SecurityConfig's access-denied handler to \
                            answer 403. Claiming it here would answer a denial with 500 and dismantle the \
                            authorization boundary silently - which is why the exclusion is asserted rather \
                            than trusted.""")
                    .isNull();
            assertThat(resolveResidual(new BadCredentialsException("bad credentials")))
                    .as("and an authentication failure has to reach the entry point that answers 401")
                    .isNull();
        }

        @Test
        @DisplayName("the resolver is inserted after the controller handlers and before the default one")
        void theResolverIsInsertedInThePositionThatPreservesControllerHandlers() {
            final List<HandlerExceptionResolver> resolvers = new ArrayList<>();
            final HandlerExceptionResolver controllerHandlers = (rq, rs, h, e) -> null;
            final HandlerExceptionResolver responseStatus = (rq, rs, h, e) -> null;
            resolvers.add(controllerHandlers);
            resolvers.add(responseStatus);
            resolvers.add(new DefaultHandlerExceptionResolver());

            new WebConfig().extendHandlerExceptionResolvers(resolvers);

            assertThat(resolvers).hasSize(5);
            assertThat(resolvers.get(0))
                    .as("""
                            Position zero is ExceptionHandlerExceptionResolver, which is what consults the \
                            sixty-seven controller-local @ExceptionHandler methods. Inserting ahead of it \
                            would silently disable every one of them, so the insertion point is not a \
                            cosmetic choice.""")
                    .isSameAs(controllerHandlers);
            assertThat(resolvers.get(2))
                    .as("and immediately before the default resolver, which would otherwise have answered "
                            + "first with a body outside the envelope")
                    .isInstanceOf(WebConfig.FrameworkBoundaryExceptionResolver.class);
            assertThat(resolvers.get(3)).isInstanceOf(DefaultHandlerExceptionResolver.class);
            assertThat(resolvers.get(4))
                    .as("""
                            The unhandled-failure resolver is LAST, and the position is the fix rather than \
                            a detail: it must claim only what every other resolver declined. Moving it \
                            ahead of the default resolver would answer a framework-mapped condition with \
                            500, and moving it ahead of position two would answer a controller's own \
                            refusal with 500.""")
                    .isInstanceOf(WebConfig.UnhandledFailureExceptionResolver.class);
        }

        @Test
        @DisplayName("with no default resolver present both resolvers are appended, not dropped")
        void withNoDefaultResolverPresentTheResolverIsAppended() {
            final List<HandlerExceptionResolver> resolvers = new ArrayList<>();
            resolvers.add((rq, rs, h, e) -> null);

            new WebConfig().extendHandlerExceptionResolvers(resolvers);

            assertThat(resolvers).hasSize(3);
            assertThat(resolvers.get(1))
                    .as("nothing downstream can pre-empt it in that shape, so appending is correct; the "
                            + "alternative of not registering at all would reopen the gap silently")
                    .isInstanceOf(WebConfig.FrameworkBoundaryExceptionResolver.class);
            assertThat(resolvers.get(2))
                    .as("and the unhandled-failure resolver stays last even in the degenerate shape, so the "
                            + "residual 500 keeps its envelope whatever the framework's default list does")
                    .isInstanceOf(WebConfig.UnhandledFailureExceptionResolver.class);
        }

        @Test
        @DisplayName("a firewall rejection writes its own body instead of calling sendError")
        void aFirewallRejectionWritesItsOwnBody() throws Exception {
            final MockHttpServletRequest request = new MockHttpServletRequest("TRACE", "/api/accounts");
            final MockHttpServletResponse response = new MockHttpServletResponse();

            new WebConfig.ProblemJsonRequestRejectedHandler()
                    .handle(request, response, new RequestRejectedException("method TRACE is not allowed"));

            assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            assertThat(response.getContentAsString()).contains("CARDDEMO-REQUEST-REJECTED");
            assertIsEnvelope(response.getContentAsString());
            assertThat(response.getErrorMessage())
                    .as("""
                            sendError is what broke this case. The connector refuses TRACE with sendError, \
                            which dispatches to the error page; the firewall then refuses that dispatch too, \
                            and a second sendError inside an error dispatch has nowhere to go, so it commits \
                            a zero-length body. Writing the body directly is the fix.""")
                    .isNull();
        }

        @Test
        @DisplayName("a firewall rejection publishes nothing about why it was rejected")
        void aFirewallRejectionPublishesNothingAboutWhy() throws Exception {
            final String probe = "method PROPFIND is not allowed and neither is %00";
            final MockHttpServletResponse response = new MockHttpServletResponse();

            new WebConfig.ProblemJsonRequestRejectedHandler().handle(
                    new MockHttpServletRequest("PROPFIND", "/api/accounts"), response,
                    new RequestRejectedException(probe));

            assertThat(response.getContentAsString())
                    .as("""
                            The exception message names the offending method or character, which is \
                            attacker-supplied. Echoing it would both reflect input and describe the \
                            firewall's rules to whoever is probing them. It goes to the log instead.""")
                    .doesNotContain("PROPFIND")
                    .doesNotContain("%00")
                    .doesNotContain(probe);
        }

        @Test
        @DisplayName("the envelope declares no charset, so it matches the controllers character for character")
        void theEnvelopeDeclaresNoCharset() {
            final MockHttpServletResponse response =
                    resolve(new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)));

            assertThat(response).isNotNull();
            assertThat(response.getContentType())
                    .as("""
                            A charset parameter would make these refusals differ from the ones the eight \
                            controllers publish, which is the very inconsistency being closed. The document \
                            is ASCII by construction, and RFC 8259 fixes JSON's encoding regardless.""")
                    .isEqualTo("application/problem+json")
                    .doesNotContain("charset");
        }

        @Test
        @DisplayName("a committed response is left alone rather than written over")
        void aCommittedResponseIsLeftAlone() {
            final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cards");
            final MockHttpServletResponse response = new MockHttpServletResponse();
            response.setCommitted(true);

            final ModelAndView answered = new WebConfig.FrameworkBoundaryExceptionResolver()
                    .resolveException(request, response, null,
                            new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)));

            assertThat(answered)
                    .as("the exception is still claimed - the status the client already saw stands - but "
                            + "nothing is written, because nothing can be")
                    .isNotNull();
            assertThat(response.getContentAsByteArray()).isEmpty();
        }
    }

    /**
     * The refusal <em>record</em>, as distinct from the refusal <em>response</em>.
     *
     * <p><strong>Finding C-01, severity Blocker - these tests pin the remediation.</strong> The response body
     * was already proven to echo nothing back; the log record was not, and it was the one carrying the caller's
     * bytes. Four boundary sites logged raw request metadata: the media-type screen logged the declared
     * {@code Content-Type}, the framework-boundary resolver logged the method and the URI, the firewall handler
     * logged the method, the URI and the firewall's own message, and the authentication and authorisation
     * handlers logged the URI.
     *
     * <p>Why that is a disclosure rather than a diagnostic. Every one of those sites is reachable
     * <em>before</em> authentication, so an anonymous caller chooses the bytes. The masking layer in
     * {@code src/main/resources/logback-spring.xml} redacts <em>labelled</em> values - a credential assignment,
     * a hash shape, a nine-digit social-security shape - and a bare card number, password, customer name or
     * government identifier sitting in a header value or a path segment carries no label, so it passed through
     * intact. JSON encoding of the field prevents a <em>forged</em> record; it does nothing about disclosure.
     *
     * <p>Each test below drives one real boundary component with a protected value planted in exactly the
     * field that component used to log, then asserts the value appears in neither the formatted message nor
     * any argument of the emitted event. Both are inspected because a value passed as a placeholder argument
     * would be absent from the pattern and present in the rendered line.
     *
     * <p>The planted values are synthetic and are chosen to look exactly like the things this application
     * actually holds: a sixteen-digit card number, a customer surname, a nine-digit government identifier and
     * a password-shaped token. None is a real credential and none is used to authenticate anything.
     */
    @Nested
    @DisplayName("the refusal record carries no caller-supplied value")
    class RefusalRecordConfidentiality {

        /** A card-number-shaped value, sixteen digits as {@code CARD-NUM PIC X(16)} of CVACT02Y declares. */
        private static final String PAN_SHAPED = "4111111111111111";

        /** A customer-surname-shaped value, of the kind {@code CUST-LAST-NAME} of CVCUS01Y holds. */
        private static final String SURNAME_SHAPED = "Whitmore";

        /** A government-identifier-shaped value, nine digits as {@code CUST-SSN PIC 9(09)} declares. */
        private static final String GOVERNMENT_ID_SHAPED = "123456789";

        /** A password-shaped token. Synthetic, and deliberately not the legacy seed literal. */
        private static final String PASSWORD_SHAPED = "notARealSecret1";

        /** Every planted value, so one assertion covers the whole set at every site. */
        private static final List<String> PLANTED =
                List.of(PAN_SHAPED, SURNAME_SHAPED, GOVERNMENT_ID_SHAPED, PASSWORD_SHAPED);

        /** The logger both configurations publish under, and therefore the one to capture. */
        private static final String APPLICATION_LOGGER_NAME = "com.cardemo";

        /**
         * Runs one boundary interaction with an appender attached, and returns everything it logged.
         *
         * <p>Each event contributes its formatted message and the {@code String} rendering of every argument,
         * so a value smuggled through a placeholder is caught as surely as one embedded in the pattern.
         *
         * @param interaction the boundary interaction to drive
         * @return one entry per event per inspected part; never null
         */
        private List<String> capture(final ThrowingInteraction interaction) {
            final Logger logger = (Logger) LoggerFactory.getLogger(APPLICATION_LOGGER_NAME);
            final ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.setContext(logger.getLoggerContext());
            appender.start();
            logger.addAppender(appender);
            try {
                interaction.run();
            } catch (final Exception unexpected) {
                throw new AssertionError("the boundary interaction must not fail", unexpected);
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }

            assertThat(appender.list)
                    .as("the interaction must have produced at least one record; nothing captured would make "
                            + "every assertion below vacuously true")
                    .isNotEmpty();

            final List<String> parts = new ArrayList<>();
            for (final ILoggingEvent event : appender.list) {
                parts.add(event.getFormattedMessage());
                parts.add(String.valueOf(event.getMessage()));
                if (event.getArgumentArray() != null) {
                    for (final Object argument : event.getArgumentArray()) {
                        parts.add(String.valueOf(argument));
                    }
                }
            }
            return parts;
        }

        /**
         * Asserts that no planted value reached any part of any captured record.
         *
         * @param parts the captured message and argument renderings
         * @param site  the boundary being described, for the failure message
         */
        private void assertNothingPlantedWasLogged(final List<String> parts, final String site) {
            for (final String part : parts) {
                assertThat(part)
                        .as("%s must log fixed codes and a validated correlation identifier only; a protected "
                                + "value reaching this record is a disclosure that no masking rule catches, "
                                + "because a bare value carries no label", site)
                        .doesNotContain(PLANTED);
            }
        }

        @Test
        @DisplayName("the media-type screen logs a bounded classification, never the declared header")
        void theMediaTypeScreenLogsNoHeaderValue() throws Exception {
            // A caller-chosen Content-Type carrying every protected shape at once. The grammar accepts it as
            // parameters, so it reaches the refusal rather than being rejected as unparsable first.
            final String hostile = "text/plain;pan=" + PAN_SHAPED + ";name=" + SURNAME_SHAPED
                    + ";ssn=" + GOVERNMENT_ID_SHAPED + ";pw=" + PASSWORD_SHAPED;
            final MockHttpServletRequest request = new MockHttpServletRequest("POST", SIGN_ON_URI);
            request.setContentType(hostile);
            request.setContent("{}".getBytes(StandardCharsets.UTF_8));
            final MockHttpServletResponse response = new MockHttpServletResponse();

            final List<String> logged =
                    capture(() -> mediaTypeFilter().doFilter(request, response, new MockFilterChain()));

            assertThat(response.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value());
            assertNothingPlantedWasLogged(logged, "the media-type screen");
            assertThat(String.join(" ", logged))
                    .as("what replaces the header is the bounded verdict the screen already computed, so the "
                            + "record still says WHY the request was refused without saying WHAT it declared")
                    .contains("NO_JSON_READER")
                    .contains("CARDDEMO-UNSUPPORTED-MEDIA-TYPE");
        }

        @Test
        @DisplayName("every media-type verdict is one of a closed set of five names")
        void everyMediaTypeVerdictIsBounded() throws Exception {
            final Map<String, String> expected = Map.of(
                    "application/", "UNPARSABLE",
                    "application/*", "WILDCARD",
                    "text/plain", "NO_JSON_READER");

            for (final Map.Entry<String, String> shape : expected.entrySet()) {
                final MockHttpServletRequest request = new MockHttpServletRequest("POST", SIGN_ON_URI);
                request.setContentType(shape.getKey());
                request.setContent("{}".getBytes(StandardCharsets.UTF_8));

                final List<String> logged = capture(() -> mediaTypeFilter()
                        .doFilter(request, new MockHttpServletResponse(), new MockFilterChain()));

                assertThat(String.join(" ", logged))
                        .as("the classification for %s must be a compile-time constant, so the logged "
                                + "cardinality is bounded by the enum rather than by discipline", shape.getKey())
                        .contains(shape.getValue());
            }

            // A request that sent bytes while declaring nothing has no value to classify, and says so.
            final MockHttpServletRequest undeclared = new MockHttpServletRequest("POST", SIGN_ON_URI) {
                @Override
                public long getContentLengthLong() {
                    return 2L;
                }
            };
            undeclared.setContent("{}".getBytes(StandardCharsets.UTF_8));
            assertThat(String.join(" ", capture(() -> mediaTypeFilter()
                    .doFilter(undeclared, new MockHttpServletResponse(), new MockFilterChain()))))
                    .contains("NO_MEDIA_TYPE_DECLARED");
        }

        @Test
        @DisplayName("the framework-boundary resolver logs neither the method nor the URI")
        void theFrameworkBoundaryResolverLogsNoRequestLine() {
            final MockHttpServletRequest request = new MockHttpServletRequest(
                    PASSWORD_SHAPED, "/api/accounts/" + PAN_SHAPED + "/" + SURNAME_SHAPED);
            request.setQueryString("ssn=" + GOVERNMENT_ID_SHAPED);
            final MockHttpServletResponse response = new MockHttpServletResponse();

            final List<String> logged = capture(() -> new WebConfig.FrameworkBoundaryExceptionResolver()
                    .resolveException(request, response, null,
                            new HttpRequestMethodNotSupportedException(PASSWORD_SHAPED)));

            assertThat(response.getStatus()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value());
            assertNothingPlantedWasLogged(logged, "the framework-boundary resolver");
            assertThat(String.join(" ", logged))
                    .as("the condition is named by the framework exception's own type, which is a closed set, "
                            + "and the error code is a constant of the configuration")
                    .contains("HttpRequestMethodNotSupportedException")
                    .contains("CARDDEMO-METHOD-NOT-ALLOWED");
        }

        @Test
        @DisplayName("the terminal resolver logs the correlated failure without the request line")
        void theTerminalResolverLogsNoRequestLine() {
            final MockHttpServletRequest request = new MockHttpServletRequest(
                    PASSWORD_SHAPED, "/api/accounts/" + PAN_SHAPED + "/" + SURNAME_SHAPED);
            request.setQueryString("ssn=" + GOVERNMENT_ID_SHAPED);
            final MockHttpServletResponse response = new MockHttpServletResponse();

            final List<String> logged = capture(() -> new WebConfig.UnhandledFailureExceptionResolver()
                    .resolveException(request, response, null,
                            new IllegalStateException("a failure carrying " + PAN_SHAPED)));

            assertThat(response.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
            assertNothingPlantedWasLogged(logged, "the unhandled-failure resolver");
            assertThat(String.join(" ", logged))
                    .as("the condition is named by the exception's own type and the code is a constant, so "
                            + "the record is actionable without echoing anything the caller chose")
                    .contains("IllegalStateException")
                    .contains("CARDDEMO-INTERNAL-FAILURE");
        }

        @Test
        @DisplayName("the firewall handler logs neither the request line nor the firewall's own message")
        void theFirewallHandlerLogsNoRejectionText() {
            final MockHttpServletRequest request = new MockHttpServletRequest(
                    "GET", "/api/cards/" + PAN_SHAPED);
            final MockHttpServletResponse response = new MockHttpServletResponse();
            // The firewall quotes the offending request back in its message, which is exactly why the message
            // may not be logged: it is the one string guaranteed to contain the caller's bytes.
            final RequestRejectedException rejection = new RequestRejectedException(
                    "The request was rejected because the URL contained " + PAN_SHAPED + " and "
                            + GOVERNMENT_ID_SHAPED);

            final List<String> logged = capture(() -> new WebConfig.ProblemJsonRequestRejectedHandler()
                    .handle(request, response, rejection));

            assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            assertNothingPlantedWasLogged(logged, "the firewall handler");
            assertThat(String.join(" ", logged))
                    .as("the rejection's TYPE replaces its message: one firewall raises it, so the type "
                            + "identifies the class of rejection while carrying no part of the request")
                    .contains("RequestRejectedException")
                    .contains("CARDDEMO-REQUEST-REJECTED");
        }

        @Test
        @DisplayName("the refusal body still discloses nothing either, so both channels stay closed")
        void theRefusalBodyStillDisclosesNothing() throws Exception {
            final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cards/" + PAN_SHAPED);
            final MockHttpServletResponse response = new MockHttpServletResponse();

            new WebConfig.ProblemJsonRequestRejectedHandler().handle(request, response,
                    new RequestRejectedException("rejected: " + PAN_SHAPED));

            assertThat(response.getContentAsString())
                    .as("the response was already free of the request and must stay so: closing the log "
                            + "channel must not have opened the body channel")
                    .doesNotContain(PLANTED);
        }

        /** One boundary interaction, allowed to throw whatever the real component declares. */
        @FunctionalInterface
        private interface ThrowingInteraction {

            /**
             * Drives the interaction.
             *
             * @throws Exception whatever the boundary component declares
             */
            void run() throws Exception;
        }
    }
}
