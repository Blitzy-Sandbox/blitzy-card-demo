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
package com.awsm2.carddemo.adapter;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MdcHeaderProducerInterceptor} validating the
 * SLF4J MDC -&gt; Kafka record header propagation introduced for
 * QA Final-CP7 Finding F-MINOR-01 (MINOR).
 *
 * <p>The interceptor is the sole mechanism by which the producer-side
 * tracing context (correlationId, traceId, tenant) is propagated onto
 * outbound Kafka records. The tests in this class confirm:</p>
 * <ul>
 *   <li>Each supported MDC key is attached as a Kafka record header
 *       when present.</li>
 *   <li>Empty / absent MDC entries do NOT emit placeholder headers
 *       (would pollute downstream consumer logs with empty
 *       sentinels).</li>
 *   <li>An empty MDC short-circuits before the headers are touched
 *       &mdash; the batch-thread fast path.</li>
 *   <li>Pre-existing headers on the record are preserved (interceptor
 *       does not clobber other producer interceptors).</li>
 * </ul>
 */
@DisplayName("MdcHeaderProducerInterceptor — MDC propagation on outbound records")
class MdcHeaderProducerInterceptorTest {

    private MdcHeaderProducerInterceptor interceptor;

    @BeforeEach
    void resetState() {
        // Defensive clear — interceptor reads from the thread's MDC, so
        // any cross-test bleed of MDC entries would compromise assertions.
        MDC.clear();
        interceptor = new MdcHeaderProducerInterceptor();
        // Exercise the no-op configure() hook for completeness — verifies
        // the interceptor does not NPE when called with the standard
        // empty config map.
        interceptor.configure(Collections.emptyMap());
    }

    @AfterEach
    void clearMdcAfterEach() {
        // Match the BeforeEach cleanup so even mid-test exceptions
        // cannot leak MDC entries into subsequent tests.
        MDC.clear();
    }

    /**
     * Helper that synthesises a {@link ProducerRecord} matching the
     * publisher-side shape used by
     * {@link KafkaEventPublisher} (String key, Object value).
     *
     * @param topic the Kafka topic name
     * @return an empty-headered producer record ready for the
     *         interceptor to enrich
     */
    private ProducerRecord<String, Object> emptyRecord(String topic) {
        return new ProducerRecord<>(topic, "00000012345", "{\"sample\":true}");
    }

    /**
     * Inspect the headers on a ProducerRecord for a specific header name
     * and return the decoded UTF-8 string value, or {@code null} if the
     * header is absent.
     *
     * @param record the producer record after interceptor invocation
     * @param name   the header name to retrieve
     * @return the decoded string value, or {@code null} if the header is
     *         absent
     */
    private String headerValue(ProducerRecord<String, Object> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("Header propagation")
    class HeaderPropagation {

        @Test
        @DisplayName("correlationId in MDC -> correlationId header on record")
        void correlationIdPropagated() {
            MDC.put("correlationId", "corr-abc-123");
            ProducerRecord<String, Object> record = interceptor.onSend(
                    emptyRecord("transaction.posted"));

            assertThat(headerValue(record, "correlationId")).isEqualTo("corr-abc-123");
        }

        @Test
        @DisplayName("traceId in MDC -> traceId header on record")
        void traceIdPropagated() {
            MDC.put("traceId", "1-5e1b4151-5ac6c58dc39d4f4e8e44d24d");
            ProducerRecord<String, Object> record = interceptor.onSend(
                    emptyRecord("transaction.posted"));

            assertThat(headerValue(record, "traceId"))
                    .isEqualTo("1-5e1b4151-5ac6c58dc39d4f4e8e44d24d");
        }

        @Test
        @DisplayName("tenant in MDC -> tenant header on record")
        void tenantPropagated() {
            MDC.put("tenant", "carddemo-prod");
            ProducerRecord<String, Object> record = interceptor.onSend(
                    emptyRecord("account.updated"));

            assertThat(headerValue(record, "tenant")).isEqualTo("carddemo-prod");
        }

        @Test
        @DisplayName("All three keys present -> all three headers populated")
        void allKeysPropagatedTogether() {
            MDC.put("correlationId", "corr-full");
            MDC.put("traceId", "trace-full");
            MDC.put("tenant", "tenant-full");

            ProducerRecord<String, Object> record = interceptor.onSend(
                    emptyRecord("transaction.posted"));

            assertThat(headerValue(record, "correlationId")).isEqualTo("corr-full");
            assertThat(headerValue(record, "traceId")).isEqualTo("trace-full");
            assertThat(headerValue(record, "tenant")).isEqualTo("tenant-full");
        }
    }

    @Nested
    @DisplayName("Negative space — absent / empty entries do NOT emit headers")
    class NegativePropagation {

        @Test
        @DisplayName("Empty MDC -> no headers added (batch-thread fast path)")
        void emptyMdcAddsNoHeaders() {
            // No MDC entries — interceptor must take the short-circuit
            // branch and leave headers untouched.
            ProducerRecord<String, Object> record = interceptor.onSend(
                    emptyRecord("ledger.balanced"));

            assertThat(record.headers()).isEmpty();
        }

        @Test
        @DisplayName("Empty-string MDC value -> header NOT emitted (no placeholder sentinel)")
        void emptyValueIsDropped() {
            MDC.put("correlationId", "");
            MDC.put("traceId", "trace-real");

            ProducerRecord<String, Object> record = interceptor.onSend(
                    emptyRecord("transaction.posted"));

            assertThat(headerValue(record, "correlationId"))
                    .as("Empty-string MDC values must not emit placeholder headers")
                    .isNull();
            assertThat(headerValue(record, "traceId")).isEqualTo("trace-real");
        }

        @Test
        @DisplayName("Subset of keys present -> only present keys emit headers")
        void subsetPropagation() {
            // Only correlationId present — traceId and tenant must NOT
            // surface as headers.
            MDC.put("correlationId", "corr-subset");

            ProducerRecord<String, Object> record = interceptor.onSend(
                    emptyRecord("report.requested"));

            assertThat(headerValue(record, "correlationId")).isEqualTo("corr-subset");
            assertThat(headerValue(record, "traceId")).isNull();
            assertThat(headerValue(record, "tenant")).isNull();
        }
    }

    @Nested
    @DisplayName("Defensive contract")
    class DefensiveContract {

        @Test
        @DisplayName("Null record returns null without NPE")
        void nullRecordReturnsNull() {
            ProducerRecord<String, Object> result = interceptor.onSend(null);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Pre-existing headers on record are preserved")
        void preExistingHeadersPreserved() {
            MDC.put("correlationId", "corr-after-prior");

            ProducerRecord<String, Object> record = emptyRecord("transaction.posted");
            // Simulate a prior interceptor having attached a header
            // before MdcHeaderProducerInterceptor runs.
            record.headers().add("prior-header", "prior".getBytes(StandardCharsets.UTF_8));

            ProducerRecord<String, Object> after = interceptor.onSend(record);

            assertThat(headerValue(after, "prior-header"))
                    .as("Prior interceptor headers must NOT be clobbered")
                    .isEqualTo("prior");
            assertThat(headerValue(after, "correlationId")).isEqualTo("corr-after-prior");
        }

        @Test
        @DisplayName("onAcknowledgement and close hooks are no-op and safe")
        void onAcknowledgementAndCloseAreNoOp() {
            // No assertion needed — these are no-op overrides on the
            // ProducerInterceptor contract. The test exists to confirm
            // they do not throw under the standard producer lifecycle.
            interceptor.onAcknowledgement(null, null);
            interceptor.onAcknowledgement(null, new RuntimeException("simulated send failure"));
            interceptor.close();
        }
    }
}
