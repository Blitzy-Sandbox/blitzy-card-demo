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
package com.awsm2.carddemo.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Regression coverage for QA Final-CP7 Finding F-CRITICAL-01 (CRITICAL):
 * Jackson deserialization of {@link TransactionAddDto} via Spring Kafka's
 * {@link JsonDeserializer}.
 *
 * <p><b>Replaces:</b> a mock-based test gap. The existing
 * {@code KafkaEventConsumerIntegrationTest} bypasses the Jackson
 * deserialization layer entirely by calling each {@code @KafkaListener}
 * method with a pre-constructed DTO. That gap allowed F-CRITICAL-01 to
 * ship to production-readiness review: every {@code transaction.posted}
 * record produced by a running application was routed straight to
 * {@code transaction.posted.DLT} via
 * {@code DeadLetterPublishingRecoverer} because the
 * {@link JsonDeserializer} could not build a property-based Creator for
 * the record (one constructor parameter was annotated
 * {@code @JsonProperty(... access = READ_ONLY)} which suppressed the
 * property name on the deserialization path).</p>
 *
 * <p>These tests exercise the EXACT same code path that Spring Kafka
 * runs inside the consumer: {@link JsonSerializer} on the producer side,
 * {@link JsonDeserializer} on the consumer side, configured with the
 * same trusted-packages and value-default-type settings as
 * {@code com.awsm2.carddemo.config.KafkaConfig#consumerFactory()}. A
 * failure of the property-based Creator factory walk (the symptom of
 * F-CRITICAL-01) surfaces here as a thrown
 * {@code com.fasterxml.jackson.databind.exc.InvalidDefinitionException}
 * during {@link JsonDeserializer#deserialize(String, byte[])}, exactly
 * matching the production DLT exception trace.</p>
 *
 * <p><b>Importantly:</b> this test does NOT require Docker or any
 * Testcontainers infrastructure &mdash; it instantiates
 * {@link JsonSerializer} / {@link JsonDeserializer} directly with the
 * same property tree that {@code KafkaConfig} applies. This makes the
 * regression check fast (sub-100ms), runnable in every developer's
 * pre-commit hook, and impossible to silently skip on CI.</p>
 *
 * <p>AAP &sect;0.6.5 / &sect;0.6.6 references the PCI-DSS audit trail
 * requirement that {@code transaction.posted} events must be searchable
 * in OpenSearch. F-CRITICAL-01 broke that requirement; this test
 * defends against future regressions.</p>
 */
@DisplayName("TransactionAddDto Jackson round-trip via Spring Kafka serializer")
class TransactionAddDtoJacksonRoundTripTest {

    /**
     * Match the producer-side topic name used by
     * {@link com.awsm2.carddemo.adapter.KafkaEventPublisher} so the
     * {@link JsonDeserializer} sees a realistic input.
     */
    private static final String TOPIC = "transaction.posted";

    /**
     * Trusted-packages whitelist mirroring
     * {@code com.awsm2.carddemo.config.KafkaConfig#consumerFactory()}.
     * Any divergence between this value and the production
     * {@code application.yml#spring.kafka.consumer.properties.spring.json.trusted.packages}
     * is a wiring defect &mdash; if the test fixture uses a wider
     * whitelist than production, false-positive passes leak through.
     */
    private static final String TRUSTED_PACKAGES =
            "com.awsm2.carddemo.dto,com.awsm2.carddemo.domain";

    /**
     * Builds a fully-populated 15-component {@link TransactionAddDto}
     * mirroring the outbound payload that
     * {@code TransactionAddService} publishes to MSK after a successful
     * persist. The {@code transactionId} component is non-null
     * &mdash; this is the field that previously broke Jackson's
     * record-property-based Creator under
     * {@code access = JsonProperty.Access.READ_ONLY}.
     *
     * @return a fresh, valid {@code TransactionAddDto} for the
     *         producer-side input fixture
     */
    private static TransactionAddDto sampleOutboundTransaction() {
        return new TransactionAddDto(
                "00000012345",                                    // accountId
                "4111111111111111",                               // cardNumber (Visa test PAN)
                "01",                                             // transactionType
                Integer.valueOf(5411),                            // transactionCategory
                "ONLINE",                                         // source
                "GROCERY STORE PURCHASE",                         // description
                new BigDecimal("123.45"),                         // amount
                LocalDateTime.of(2026, 5, 20, 12, 30, 0),         // originationTimestamp
                LocalDateTime.of(2026, 5, 20, 12, 30, 0),         // processingTimestamp
                Long.valueOf(100000001L),                         // merchantId
                "ACME GROCERY",                                   // merchantName
                "SEATTLE",                                        // merchantCity
                "98101",                                          // merchantZip
                "Y",                                              // confirm
                "0000000000000023");                              // transactionId (server-generated)
    }

    /**
     * Configures a fresh {@link JsonDeserializer} for
     * {@link TransactionAddDto} that mirrors the production listener
     * configuration on {@code KafkaEventConsumer#onTransactionPosted}.
     *
     * <p>The deserializer:</p>
     * <ul>
     *   <li>Sets the target type to {@link TransactionAddDto} via
     *       {@link JsonDeserializer#JsonDeserializer(Class)} &mdash;
     *       equivalent to the per-listener property
     *       {@code spring.json.value.default.type=com.awsm2.carddemo.dto.TransactionAddDto}.</li>
     *   <li>Honors the trusted-packages whitelist matching
     *       {@code application.yml#spring.json.trusted.packages}.</li>
     *   <li>Disables type-info headers, matching
     *       {@code spring.json.use.type.headers=false} on the consumer
     *       and {@code spring.json.add.type.headers=false} on the
     *       producer.</li>
     * </ul>
     *
     * @return a configured {@link JsonDeserializer} ready to
     *         deserialize {@code TransactionAddDto} bytes
     */
    private static JsonDeserializer<TransactionAddDto> txnDeserializer() {
        JsonDeserializer<TransactionAddDto> deserializer =
                new JsonDeserializer<>(TransactionAddDto.class);
        deserializer.addTrustedPackages(TRUSTED_PACKAGES.split(","));
        deserializer.setUseTypeHeaders(false);
        return deserializer;
    }

    /**
     * Configures a fresh {@link JsonSerializer} that mirrors the
     * production {@code KafkaConfig#producerFactory()} settings.
     *
     * <p>{@code addTypeInfo=false} matches
     * {@code spring.json.add.type.headers=false} on the producer
     * &mdash; CardDemo routes events by topic, not by Java class.</p>
     *
     * @return a configured {@link JsonSerializer} ready to serialize
     *         {@code TransactionAddDto} instances
     */
    private static JsonSerializer<TransactionAddDto> txnSerializer() {
        JsonSerializer<TransactionAddDto> serializer = new JsonSerializer<>();
        serializer.setAddTypeInfo(false);
        return serializer;
    }

    @Nested
    @DisplayName("Round-trip — producer JsonSerializer -> consumer JsonDeserializer")
    class RoundTrip {

        /**
         * Direct regression check for F-CRITICAL-01: serialize a
         * fully-populated 15-component {@link TransactionAddDto} on the
         * producer side, then deserialize the resulting bytes through
         * the same {@link JsonDeserializer} configuration the consumer
         * applies. Pre-fix this throws {@code InvalidDefinitionException}
         * at {@code BasicDeserializerFactory._validateNamedPropertyParameter}
         * because the {@code transactionId} parameter had no usable
         * property name; post-fix it returns a value-equal DTO.
         */
        @Test
        @DisplayName("15-arg DTO with non-null transactionId survives round-trip (F-CRITICAL-01 regression)")
        void fifteenArgRoundTripPreservesTransactionId() {
            TransactionAddDto outbound = sampleOutboundTransaction();

            byte[] bytes;
            try (JsonSerializer<TransactionAddDto> serializer = txnSerializer()) {
                bytes = serializer.serialize(TOPIC, outbound);
            }
            assertThat(bytes).isNotNull().isNotEmpty();

            // Sanity check — payload contains the transactionId field so
            // we can confirm the deserialiser is actually exercising the
            // 15-component constructor (not silently dropping it).
            String json = new String(bytes, StandardCharsets.UTF_8);
            assertThat(json).contains("\"transactionId\":\"0000000000000023\"");

            TransactionAddDto inbound;
            try (JsonDeserializer<TransactionAddDto> deserializer = txnDeserializer()) {
                inbound = deserializer.deserialize(TOPIC, bytes);
            }

            assertThat(inbound)
                    .as("F-CRITICAL-01 regression — Jackson MUST be able to build a "
                            + "property-based Creator for TransactionAddDto without "
                            + "throwing InvalidDefinitionException on transactionId")
                    .isNotNull()
                    .isEqualTo(outbound);
            assertThat(inbound.transactionId()).isEqualTo("0000000000000023");
        }

        /**
         * Verifies that the deserializer still works when the inbound
         * JSON omits the {@code transactionId} field entirely (e.g., a
         * legacy producer or a test fixture using the 14-arg helper
         * constructor on the wire). Jackson should bind a {@code null}
         * to the field and the record construction should succeed.
         */
        @Test
        @DisplayName("14-arg DTO without transactionId on the wire deserializes with transactionId=null")
        void fourteenArgRoundTripLeavesTransactionIdNull() {
            // Build a 14-arg DTO (transactionId implicit null) and
            // serialize it; this is the historical shape produced by
            // call sites that never populated transactionId.
            TransactionAddDto outbound = new TransactionAddDto(
                    "00000012345",
                    "4111111111111111",
                    "01",
                    5411,
                    "ONLINE",
                    "GROCERY STORE PURCHASE",
                    new BigDecimal("123.45"),
                    LocalDateTime.of(2026, 5, 20, 12, 30, 0),
                    LocalDateTime.of(2026, 5, 20, 12, 30, 0),
                    100000001L,
                    "ACME GROCERY",
                    "SEATTLE",
                    "98101",
                    "Y");
            assertThat(outbound.transactionId()).isNull();

            byte[] bytes;
            try (JsonSerializer<TransactionAddDto> serializer = txnSerializer()) {
                bytes = serializer.serialize(TOPIC, outbound);
            }

            TransactionAddDto inbound;
            try (JsonDeserializer<TransactionAddDto> deserializer = txnDeserializer()) {
                inbound = deserializer.deserialize(TOPIC, bytes);
            }
            assertThat(inbound).isEqualTo(outbound);
            assertThat(inbound.transactionId())
                    .as("null transactionId on the wire must remain null after deserialization")
                    .isNull();
        }

        /**
         * Hardens the regression against the specific symptom the QA
         * trace observed: deserialization of a JSON payload that
         * arrived via the producer-without-type-headers path (the
         * production wire shape, NOT a hand-crafted JSON document). If
         * the @JsonCreator factory walk regresses for any future
         * record-property addition, this assertion fails with a clear
         * Jackson exception trace matching the QA report verbatim.
         */
        @Test
        @DisplayName("Producer payload without __TypeId__ header deserializes cleanly (no DLT route)")
        void producerPayloadWithoutTypeHeaderDoesNotThrow() {
            TransactionAddDto outbound = sampleOutboundTransaction();
            try (JsonSerializer<TransactionAddDto> serializer = txnSerializer();
                 JsonDeserializer<TransactionAddDto> deserializer = txnDeserializer()) {
                byte[] bytes = serializer.serialize(TOPIC, outbound);
                assertThatNoException()
                        .as("Pre-fix this threw InvalidDefinitionException at "
                                + "BasicDeserializerFactory._validateNamedPropertyParameter "
                                + "for argument #14 (transactionId).")
                        .isThrownBy(() -> deserializer.deserialize(TOPIC, bytes));
            }
        }
    }

    @Nested
    @DisplayName("Constructor-creator name exposure — every record parameter has a property name")
    class ConstructorParameters {

        /**
         * Self-contained probe that exercises Jackson's factory walk for
         * {@link TransactionAddDto}'s {@code @JsonCreator} constructor.
         * If any future change introduces an {@code @JsonProperty}
         * annotation that suppresses the property name on one of the
         * constructor parameters (the exact pattern that triggered
         * F-CRITICAL-01), this probe throws
         * {@code InvalidDefinitionException} at deserialization time
         * and surfaces a clear assertion failure long before the issue
         * reaches a running MSK consumer.
         *
         * <p>The probe uses an empty JSON object (which provides no
         * field values, forcing Jackson to walk every constructor
         * parameter and validate that each one has a usable property
         * name) and asserts the resulting DTO has all-null fields
         * &mdash; the expected behaviour when the property-based
         * Creator is correctly configured.</p>
         */
        @Test
        @DisplayName("Empty JSON object instantiates a fully-null DTO without InvalidDefinitionException")
        void emptyJsonInstantiatesAllNullDto() {
            byte[] emptyJson = "{}".getBytes(StandardCharsets.UTF_8);

            TransactionAddDto inbound;
            try (JsonDeserializer<TransactionAddDto> deserializer = txnDeserializer()) {
                inbound = deserializer.deserialize(TOPIC, emptyJson);
            }
            assertThat(inbound)
                    .as("Empty JSON must instantiate a DTO with every field null — "
                            + "if this throws InvalidDefinitionException the F-CRITICAL-01 "
                            + "regression has returned.")
                    .isNotNull();
            assertThat(inbound.accountId()).isNull();
            assertThat(inbound.cardNumber()).isNull();
            assertThat(inbound.transactionType()).isNull();
            assertThat(inbound.transactionCategory()).isNull();
            assertThat(inbound.source()).isNull();
            assertThat(inbound.description()).isNull();
            assertThat(inbound.amount()).isNull();
            assertThat(inbound.originationTimestamp()).isNull();
            assertThat(inbound.processingTimestamp()).isNull();
            assertThat(inbound.merchantId()).isNull();
            assertThat(inbound.merchantName()).isNull();
            assertThat(inbound.merchantCity()).isNull();
            assertThat(inbound.merchantZip()).isNull();
            assertThat(inbound.confirm()).isNull();
            assertThat(inbound.transactionId()).isNull();
        }
    }
}
