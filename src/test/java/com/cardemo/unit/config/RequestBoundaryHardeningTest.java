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

import com.cardemo.config.WebConfig;
import com.fasterxml.jackson.core.StreamReadConstraints;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

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
        final Class<?> type = Class.forName(FILTER_CLASS_NAME);
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

    @Nested
    @DisplayName("the byte bound, applied before authentication")
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
                        byte past the limit, so the refusal happens before authentication and before any \
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
}
