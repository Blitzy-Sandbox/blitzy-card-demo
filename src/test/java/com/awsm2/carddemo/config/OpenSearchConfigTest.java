/*
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
 * language governing permissions and limitations under the License.
 */
package com.awsm2.carddemo.config;

// Replaces: per-record audit emission failures in the COBOL→Java port — the
// BUG #2 fix (QA CP5) registers JavaTimeModule on the OpenSearch typed
// client's ObjectMapper so java.time.LocalDateTime / LocalDate audit fields
// (e.g. Transaction.tranProcTs, postedTs) can be serialized to the
// canonical OpenSearch date format. Without this fix every emission from
// AuditLogService.logTransactionEvent and logBatchJobLifecycle throws
// InvalidDefinitionException, violating AAP §0.6.5 / §0.7.2 audit-trail
// requirements.

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.client.json.JsonpMapper;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.rest_client.RestClientTransport;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@link OpenSearchConfig#openSearchTransport(RestHighLevelClient)}
 * bean factory, with explicit focus on the <strong>BUG #2 fix (QA CP5)</strong>:
 * registration of the Jackson {@code JavaTimeModule} on the {@link ObjectMapper}
 * used by the OpenSearch typed-client's {@link JacksonJsonpMapper}.
 *
 * <p>The original {@code openSearchTransport} method instantiated
 * {@code new JacksonJsonpMapper(new ObjectMapper())} with a default
 * {@link ObjectMapper} that did <em>not</em> register the
 * {@code jackson-datatype-jsr310} {@code JavaTimeModule}. As a result, every
 * audit emission containing a {@link LocalDateTime} or {@link LocalDate}
 * field (typically {@code tranProcTs} on {@code Transaction} and
 * {@code postedTs} on lifecycle events) threw
 * {@code com.fasterxml.jackson.databind.exc.InvalidDefinitionException}
 * with the canonical message
 * <i>"Java 8 date/time type `java.time.LocalDateTime` not supported by default"</i>,
 * silently breaking the AAP &sect;0.6.5 / &sect;0.7.2 audit-trail mandate
 * that OpenSearch indexes transaction logs and lifecycle events for
 * regulatory queries and fraud investigation.</p>
 *
 * <h2>Test scope</h2>
 *
 * <ul>
 *   <li>Verifies that the produced {@link OpenSearchTransport} is a
 *       {@link RestClientTransport} (sanity check on the wiring).</li>
 *   <li>Verifies that the underlying {@link JsonpMapper} is a
 *       {@link JacksonJsonpMapper} (sanity check on the BUG #2 fix
 *       changing the mapper type but NOT the surface contract).</li>
 *   <li>Verifies the underlying {@link ObjectMapper} has the
 *       {@code JavaTimeModule} registered (the core BUG #2 fix).</li>
 *   <li>Verifies the {@link SerializationFeature#WRITE_DATES_AS_TIMESTAMPS}
 *       flag is disabled so dates serialize as ISO-8601 strings rather
 *       than epoch-millis numbers (canonical OpenSearch date format).</li>
 *   <li>Performs a positive serialization smoke test: an audit-shaped
 *       document containing both {@link LocalDateTime} and {@link LocalDate}
 *       fields serializes to JSON without throwing &mdash; demonstrating
 *       the runtime defect that originally surfaced via
 *       {@code InvalidDefinitionException} is now resolved.</li>
 * </ul>
 *
 * <h2>Mocking strategy</h2>
 *
 * <p>This is a pure Mockito unit test &mdash; no Spring context is loaded.
 * {@link MockitoExtension} (strict-stubbing mode) wires the
 * {@code @Mock RestHighLevelClient} and stubs its {@code getLowLevelClient()}
 * call to return a mocked {@link RestClient}, which is sufficient input for
 * the {@code openSearchTransport} bean factory under test. No real
 * OpenSearch endpoint or network IO is involved.</p>
 *
 * @see OpenSearchConfig
 * @see com.awsm2.carddemo.adapter.AuditLogService
 * @see com.awsm2.carddemo.adapter.OpenSearchIndexer
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OpenSearchConfig — BUG #2 fix: JavaTimeModule registered on transport's ObjectMapper")
class OpenSearchConfigTest {

    /**
     * Mocked {@link RestHighLevelClient} whose {@code getLowLevelClient()}
     * is stubbed to return the {@link #lowLevelClient} mock. The
     * {@code openSearchTransport} bean factory passes this client through
     * directly &mdash; the only call against it is to obtain the low-level
     * REST client.
     */
    @Mock
    private RestHighLevelClient restHighLevelClient;

    /**
     * Mocked low-level {@link RestClient} returned by
     * {@code restHighLevelClient.getLowLevelClient()}. The
     * {@link RestClientTransport} constructor accepts this client without
     * exercising any IO during construction, so the mock surface is
     * minimal (no further stubbing required).
     */
    @Mock
    private RestClient lowLevelClient;

    /**
     * The configuration class under test. Instantiated directly &mdash;
     * the bean factory under test ({@code openSearchTransport}) takes its
     * sole argument as a method parameter and does not depend on any
     * {@code @Value}-injected field on the config class itself.
     */
    private OpenSearchConfig config;

    @BeforeEach
    void setUp() {
        // The production constructor takes an Optional<AwsCredentialsProvider>
        // — Optional.empty() simulates the `local` profile where SigV4 is
        // disabled and the credentials provider bean is absent. This unit
        // test only exercises the openSearchTransport() bean factory which
        // does not depend on credentials in any path.
        config = new OpenSearchConfig(Optional.<AwsCredentialsProvider>empty());
        // Stub the only method called against the mocked
        // RestHighLevelClient: getLowLevelClient(). All other interactions
        // happen against the low-level client during transport construction.
        when(restHighLevelClient.getLowLevelClient()).thenReturn(lowLevelClient);
    }

    @Nested
    @DisplayName("Transport / Mapper wiring")
    class TransportWiring {

        @Test
        @DisplayName("returns a RestClientTransport (not a raw OpenSearchTransport stub)")
        void openSearchTransport_returnsRestClientTransport() {
            OpenSearchTransport transport = config.openSearchTransport(restHighLevelClient);

            assertNotNull(transport, "openSearchTransport must not be null");
            assertInstanceOf(RestClientTransport.class, transport,
                    "OpenSearchTransport bean must be a RestClientTransport so "
                            + "shared connection pooling and signing interceptors "
                            + "carry across legacy and typed client APIs");
        }

        @Test
        @DisplayName("uses a JacksonJsonpMapper as the JsonpMapper")
        void openSearchTransport_usesJacksonJsonpMapper() {
            OpenSearchTransport transport = config.openSearchTransport(restHighLevelClient);
            JsonpMapper mapper = ((RestClientTransport) transport).jsonpMapper();

            assertNotNull(mapper, "JsonpMapper must be initialised");
            assertInstanceOf(JacksonJsonpMapper.class, mapper,
                    "JsonpMapper must be the Jackson variant — the BUG #2 fix "
                            + "operates on the underlying ObjectMapper that the "
                            + "Jackson variant wraps");
        }
    }

    @Nested
    @DisplayName("BUG #2 fix: ObjectMapper module registration and date-handling flags")
    class ObjectMapperConfiguration {

        @Test
        @DisplayName("ObjectMapper has the Jackson JSR-310 JavaTimeModule registered")
        void openSearchTransport_objectMapperRegistersJavaTimeModule() {
            OpenSearchTransport transport = config.openSearchTransport(restHighLevelClient);
            JacksonJsonpMapper jacksonMapper =
                    (JacksonJsonpMapper) ((RestClientTransport) transport).jsonpMapper();
            ObjectMapper objectMapper = jacksonMapper.objectMapper();

            // ObjectMapper.getRegisteredModuleIds() returns the set of
            // module identifiers registered via registerModule. The JSR-310
            // module identifier is the canonical string
            // "jackson-datatype-jsr310" (set by JavaTimeModule.getModuleName()).
            assertTrue(
                    objectMapper.getRegisteredModuleIds()
                            .stream()
                            .anyMatch(id -> id.toString().contains("jackson-datatype-jsr310")),
                    "ObjectMapper MUST have JavaTimeModule (jackson-datatype-jsr310) "
                            + "registered — without it, every audit emission "
                            + "containing LocalDateTime fails with "
                            + "InvalidDefinitionException (BUG #2 from QA CP5)");
        }

        @Test
        @DisplayName("ObjectMapper writes dates as ISO-8601 strings (not epoch-millis)")
        void openSearchTransport_objectMapperDisablesWriteDatesAsTimestamps() {
            OpenSearchTransport transport = config.openSearchTransport(restHighLevelClient);
            JacksonJsonpMapper jacksonMapper =
                    (JacksonJsonpMapper) ((RestClientTransport) transport).jsonpMapper();
            ObjectMapper objectMapper = jacksonMapper.objectMapper();

            // The default state of WRITE_DATES_AS_TIMESTAMPS is enabled;
            // OpenSearchConfig MUST disable it so the serialized form is
            // an ISO-8601 string matching the canonical OpenSearch date
            // format and preserving human-readable audit traces.
            assertFalse(
                    objectMapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS),
                    "WRITE_DATES_AS_TIMESTAMPS MUST be disabled so dates "
                            + "serialize as ISO-8601 strings (e.g. "
                            + "\"2026-05-20T11:30:00\") rather than epoch-millis "
                            + "numbers — matches the canonical OpenSearch date "
                            + "format");
        }
    }

    @Nested
    @DisplayName("End-to-end serialization smoke test (the bug's runtime symptom)")
    class SerializationSmokeTest {

        @Test
        @DisplayName("LocalDateTime audit field serializes to ISO-8601 without InvalidDefinitionException")
        void openSearchTransport_serializesLocalDateTimeAsIso8601() throws Exception {
            OpenSearchTransport transport = config.openSearchTransport(restHighLevelClient);
            ObjectMapper om =
                    ((JacksonJsonpMapper) ((RestClientTransport) transport).jsonpMapper())
                            .objectMapper();

            // Compose an audit-shaped document mirroring the BUG #2
            // reproduction: a LocalDateTime field (tranProcTs) and a
            // LocalDate field (postedDate). Default Jackson without
            // JavaTimeModule throws InvalidDefinitionException at this
            // point; after the BUG #2 fix this serializes cleanly.
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("tranProcTs", LocalDateTime.of(2026, 5, 20, 11, 30, 0));
            audit.put("postedDate", LocalDate.of(2026, 5, 20));
            audit.put("eventType", "BATCH_JOB_STARTED");

            String json = assertDoesNotThrow(
                    () -> om.writeValueAsString(audit),
                    "Audit document containing LocalDateTime / LocalDate "
                            + "fields MUST serialize without "
                            + "InvalidDefinitionException after the BUG #2 fix");

            // ISO-8601 string form (LocalDateTime is rendered without
            // timezone; LocalDate is the canonical YYYY-MM-DD shape).
            // The exact bytes prove dates were NOT serialized as
            // epoch-millis numbers.
            assertTrue(json.contains("\"tranProcTs\":\"2026-05-20T11:30:00\""),
                    "LocalDateTime MUST serialize as ISO-8601 string; "
                            + "actual=" + json);
            assertTrue(json.contains("\"postedDate\":\"2026-05-20\""),
                    "LocalDate MUST serialize as ISO-8601 string; "
                            + "actual=" + json);
        }

        @Test
        @DisplayName("Document round-trips through the configured ObjectMapper")
        void openSearchTransport_objectMapperRoundTripsLocalDateTime()
                throws JsonProcessingException {
            OpenSearchTransport transport = config.openSearchTransport(restHighLevelClient);
            ObjectMapper om =
                    ((JacksonJsonpMapper) ((RestClientTransport) transport).jsonpMapper())
                            .objectMapper();

            // The deserialization path is equally critical for any
            // downstream code that reads audit documents back from
            // OpenSearch — verify it round-trips cleanly. This guards
            // against future regressions that re-register the JavaTimeModule
            // for write but inadvertently break read.
            LocalDateTime expected = LocalDateTime.of(2026, 5, 20, 11, 30, 0);
            String json = om.writeValueAsString(Map.of("ts", expected));
            // Round-trip via a TypeReference avoids the LinkedHashMap vs
            // Map issue; the value's runtime type is what we care about.
            Map<String, LocalDateTime> back = om.readValue(
                    json,
                    om.getTypeFactory().constructMapType(
                            Map.class, String.class, LocalDateTime.class));

            assertEquals(expected, back.get("ts"),
                    "Round-trip serialization of LocalDateTime MUST preserve "
                            + "value identity (write + read via the same "
                            + "configured ObjectMapper)");
        }
    }
}
