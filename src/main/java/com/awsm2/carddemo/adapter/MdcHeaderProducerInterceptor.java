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

// Replaces: implicit CICS task context propagation — in the source mainframe,
// every CICS task carried its own TWA / EIB area populated with terminal-,
// transaction-, and user-context fields visible to every called subprogram
// without explicit parameter passing. The Java target uses SLF4J MDC for the
// same purpose (per-thread context map populated by inbound HTTP request
// filters, batch step listeners, etc.); this interceptor copies the MDC
// values onto outbound Kafka record headers so that downstream consumers
// can reconstruct the originating request's tracing context for end-to-end
// correlation in CloudWatch / OpenSearch (AAP §0.6.6).

import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Headers;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Apache Kafka {@link ProducerInterceptor} that propagates SLF4J
 * {@link MDC} entries onto outbound Kafka record headers so downstream
 * consumers can reconstruct the originating request's tracing context.
 *
 * <p><b>Replaces:</b> implicit CICS task-context propagation. In the
 * source z/OS CardDemo system, every CICS task carried its own TWA
 * (Transaction Work Area) and EIB (Exec Interface Block) populated with
 * terminal-, transaction-, and user-context fields that were visible to
 * every called subprogram without explicit parameter passing. The Java
 * target uses SLF4J's per-thread {@link MDC} for the same purpose
 * (populated by inbound HTTP request filters, batch step listeners, and
 * the {@link com.awsm2.carddemo.exception.GlobalExceptionHandler} on
 * exception paths); this interceptor copies the MDC values onto
 * outbound Kafka record headers so that downstream consumers see the
 * same correlation tokens in their own log lines for end-to-end audit
 * correlation in CloudWatch / OpenSearch (AAP &sect;0.6.6).</p>
 *
 * <p>Per AAP &sect;0.7.1: "Isolate all AWS service integrations in
 * dedicated adapter classes &mdash; never inline AWS SDK calls in
 * business logic". This class lives in {@code adapter/} alongside the
 * other Kafka adapters ({@link KafkaEventPublisher},
 * {@link KafkaEventConsumer}) and is wired in via the producer factory's
 * {@code interceptor.classes} property (see
 * {@code KafkaConfig#producerFactory}) so business logic never
 * references it directly.</p>
 *
 * <h2>Header keys propagated</h2>
 * <ul>
 *   <li>{@code correlationId} &mdash; primary request-correlation
 *       identifier issued by the inbound HTTP filter or batch step
 *       listener. Visible in CloudWatch log lines via the Logback MDC
 *       converter and on the JSON error response envelope (see
 *       {@link com.awsm2.carddemo.exception.GlobalExceptionHandler}).</li>
 *   <li>{@code traceId} &mdash; OpenTelemetry / AWS X-Ray distributed
 *       trace identifier. When Spring Boot's Observation API is wired
 *       to OpenTelemetry / X-Ray, the per-span trace identifier is
 *       written to MDC under this key and propagated to consumer
 *       services for cross-span correlation.</li>
 *   <li>{@code tenant} &mdash; multi-tenant discriminator. The
 *       CardDemo target runs as a single-tenant application today
 *       (AAP &sect;0.1.1), but reserving the header key here enables
 *       future multi-tenant deployments to propagate the discriminator
 *       without producer-side code changes.</li>
 * </ul>
 *
 * <h2>Behavior</h2>
 * <p>If a given MDC key is not present (i.e., {@link MDC#get(String)}
 * returns {@code null} or the empty string) the corresponding header
 * is NOT attached &mdash; this preserves backward-wire-compatibility
 * with consumers that historically did not see these headers and
 * prevents the header set from polluting Kafka topic logs with empty
 * sentinels. The interceptor is safe to chain with other
 * {@link ProducerInterceptor} implementations &mdash; Kafka invokes
 * them in {@code interceptor.classes} declaration order on every send.</p>
 *
 * <h2>QA Final-CP7 Finding F-MINOR-01 (MINOR) fix</h2>
 * <p>The QA Final-CP7 review noted that produced Kafka records carried
 * NO application-level headers when the checkpoint test plan referenced
 * {@code correlationId}, {@code traceId}, and {@code tenant} as
 * expected propagation tokens. AAP &sect;0.6.5 does not explicitly
 * mandate these headers, but the test plan named them as a
 * forward-compatibility nice-to-have; this interceptor closes that gap
 * minimally by reading from the SLF4J MDC and writing to record
 * headers only when MDC entries exist.</p>
 *
 * <p><b>Performance note:</b> {@link MDC#getCopyOfContextMap()} returns
 * a snapshot of the per-thread map (or {@code null} if no entries
 * exist), so the interceptor avoids the cost of three discrete
 * {@link MDC#get(String)} calls when MDC is empty (the common
 * batch-thread case before any inbound request enters). On the
 * inbound-request path the snapshot cost is negligible relative to
 * the Kafka send itself.</p>
 *
 * @see KafkaEventPublisher
 * @see KafkaEventConsumer
 */
public class MdcHeaderProducerInterceptor implements ProducerInterceptor<String, Object> {

    /**
     * MDC key holding the primary request-correlation identifier
     * issued by the inbound HTTP filter or by
     * {@link com.awsm2.carddemo.exception.GlobalExceptionHandler}.
     */
    private static final String MDC_KEY_CORRELATION_ID = "correlationId";

    /**
     * MDC key holding the OpenTelemetry / AWS X-Ray distributed trace
     * identifier propagated end-to-end across service boundaries.
     */
    private static final String MDC_KEY_TRACE_ID = "traceId";

    /**
     * MDC key holding the multi-tenant discriminator. Reserved for
     * future multi-tenant deployments; emitted only when present in
     * the MDC of the producing thread.
     */
    private static final String MDC_KEY_TENANT = "tenant";

    /**
     * Intercepts each outbound {@link ProducerRecord} and copies the
     * MDC entries for {@value #MDC_KEY_CORRELATION_ID},
     * {@value #MDC_KEY_TRACE_ID}, and {@value #MDC_KEY_TENANT} (when
     * non-null and non-empty) onto the record's header set so the
     * consumer receives them as Kafka headers.
     *
     * <p>Per the Kafka {@link ProducerInterceptor} contract, this
     * method MUST NOT mutate the {@code key} or {@code value}
     * components of the record &mdash; it must only return either the
     * original record (unmodified) or a new {@link ProducerRecord}
     * with augmented headers. The implementation here mutates the
     * existing {@link Headers} instance, which is the Kafka-recommended
     * pattern for header-only enrichment.</p>
     *
     * @param record the outbound producer record about to be sent
     * @return the (possibly header-augmented) producer record; never
     *         {@code null}
     */
    @Override
    public ProducerRecord<String, Object> onSend(ProducerRecord<String, Object> record) {
        // Defensive null-guard — Kafka should never invoke onSend(null)
        // per the ProducerInterceptor contract, but a stray test
        // harness or future refactor might. Returning null would cause
        // the Kafka producer to NPE further down the pipeline.
        if (record == null) {
            return null;
        }

        // Snapshot the MDC once — cheap on the empty-MDC fast path
        // (returns null when the thread's MDC is empty) and avoids
        // repeated lookup overhead on the populated path.
        final Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();
        if (mdcSnapshot == null || mdcSnapshot.isEmpty()) {
            // Empty MDC — no correlation context to propagate; return the
            // record unmodified so headers remain unchanged on the wire.
            // This is the common batch-thread case before any inbound
            // request enters and is intentionally a no-op.
            return record;
        }

        final Headers headers = record.headers();
        addHeaderIfPresent(headers, mdcSnapshot, MDC_KEY_CORRELATION_ID);
        addHeaderIfPresent(headers, mdcSnapshot, MDC_KEY_TRACE_ID);
        addHeaderIfPresent(headers, mdcSnapshot, MDC_KEY_TENANT);
        return record;
    }

    /**
     * Adds a header to the record headers only when the MDC value is
     * non-null and non-empty. Empty values are dropped to avoid
     * polluting downstream consumer logs with placeholder sentinels.
     *
     * @param headers     the record's mutable header set
     * @param mdcSnapshot a snapshot of the per-thread MDC map
     * @param key         the MDC key (also used as the Kafka header
     *                    name); never {@code null}
     */
    private void addHeaderIfPresent(Headers headers,
                                    Map<String, String> mdcSnapshot,
                                    String key) {
        final String value = mdcSnapshot.get(key);
        if (value == null || value.isEmpty()) {
            // Absent or empty — do not emit a placeholder header.
            return;
        }
        // Kafka headers carry UTF-8 byte values per the Apache Kafka
        // protocol spec; SLF4J MDC values are Java Strings, so the
        // encoding step is the canonical String -> byte[] conversion.
        headers.add(key, value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * No-op acknowledgement callback. The Kafka
     * {@link ProducerInterceptor} contract invokes this method after
     * the broker acknowledges (or rejects) each record; this
     * interceptor has no after-send work to perform &mdash; its sole
     * responsibility is header propagation on {@link #onSend}.
     *
     * <p>Subclasses or sibling interceptors may use this hook for
     * after-send telemetry; this implementation deliberately leaves
     * it empty to keep the producer hot path overhead at a
     * minimum.</p>
     *
     * @param metadata  record metadata returned by the broker; may be
     *                  {@code null} when {@code exception} is non-null
     * @param exception the underlying send failure, or {@code null} on
     *                  success
     */
    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
        // Intentionally no-op — header propagation completed during
        // onSend; after-send telemetry is handled by
        // KafkaEventPublisher#handleSendResult and the Micrometer
        // Observation API integration on KafkaTemplate.
    }

    /**
     * No-op close hook. The Kafka {@link ProducerInterceptor}
     * contract calls this when the producer is shut down; this
     * implementation holds no per-instance resources (the SLF4J MDC
     * is owned by the SLF4J runtime, not by the interceptor) and so
     * has nothing to release.
     */
    @Override
    public void close() {
        // Intentionally no-op — no resources held.
    }

    /**
     * No-op configuration hook. The Kafka {@link ProducerInterceptor}
     * contract calls this immediately after construction with the
     * producer's configuration map; this implementation needs no
     * configuration parameters and so ignores the supplied map.
     *
     * @param configs the producer's configuration map (ignored)
     */
    @Override
    public void configure(Map<String, ?> configs) {
        // Intentionally no-op — interceptor needs no configuration.
    }
}
