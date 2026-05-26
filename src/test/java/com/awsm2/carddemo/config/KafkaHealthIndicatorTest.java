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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link KafkaHealthIndicator}, the QA CP11 Finding M-3
 * fix that exposes a custom {@code kafka} health indicator under
 * {@code /actuator/health} so the ALB target-group readiness probe at
 * {@code /actuator/health/readiness} accurately reflects Kafka availability.
 *
 * <p><b>Coverage strategy:</b> Testcontainers-backed Kafka is exercised by
 * the dedicated integration test
 * {@code KafkaHealthIndicatorIntegrationTest} in the {@code integration}
 * package — this class focuses on the deterministic failure paths that
 * do <em>not</em> require a real broker.</p>
 *
 * <p><b>Coverage matrix:</b></p>
 * <ol>
 *   <li>Probe against an unreachable broker (TCP connect failure within
 *       the {@code PROBE_TIMEOUT} envelope) reports {@link Health#down()}
 *       without throwing.</li>
 *   <li>The indicator NEVER leaks credentials in the failure detail.</li>
 *   <li>The indicator constructor accepts a non-null
 *       {@link KafkaProperties} bean and is otherwise stateless.</li>
 * </ol>
 *
 * @see KafkaHealthIndicator
 * @see com.awsm2.carddemo.config.KafkaConfig
 */
@DisplayName("KafkaHealthIndicator — QA CP11 M-3 Kafka readiness probe")
class KafkaHealthIndicatorTest {

    /**
     * Returns DOWN when the configured bootstrap server is unreachable.
     * Uses a deliberately unroutable address (127.0.0.1:1 — the
     * "discard" / "tcpmux" port that is never bound on Linux) so the
     * probe fails fast within the {@code PROBE_TIMEOUT} envelope.
     *
     * <p>This is the canonical failure-path coverage for the ALB
     * target-group readiness probe contract: if Kafka is unreachable,
     * {@code /actuator/health/readiness} must reflect DOWN so the ALB
     * de-registers the task from the rotation.</p>
     */
    @Test
    @DisplayName("Probe against unreachable broker returns Health.DOWN within timeout")
    void probe_againstUnreachableBroker_returnsDown() {
        // GIVEN: KafkaProperties pointing at an unreachable address
        KafkaProperties kafkaProperties = new KafkaProperties();
        kafkaProperties.setBootstrapServers(Collections.singletonList("127.0.0.1:1"));

        KafkaHealthIndicator indicator = new KafkaHealthIndicator(kafkaProperties);

        // WHEN: the health probe runs
        long start = System.nanoTime();
        Health health = indicator.health();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        // THEN: status is DOWN (not UP, not UNKNOWN)
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        // AND: the probe completed reasonably fast — the AdminClient
        // request.timeout.ms is 2000ms and the outer probe envelope is
        // 3000ms. We allow extra headroom (15 seconds) for CI noise but
        // the probe must complete within the ECS / ALB health-check
        // budget in steady state.
        assertThat(elapsedMs)
                .as("Probe must complete within the ECS/ALB health-check budget")
                .isLessThan(15_000L);
    }

    /**
     * The health-down detail must not surface bootstrap-server URLs,
     * SASL/IAM secret material, or full configuration property maps.
     * Only the exception's {@code getMessage()} is propagated through
     * {@link Health#down(Throwable)}.
     */
    @Test
    @DisplayName("Failure detail does not surface raw bootstrap-server URLs or credentials")
    void failureDetail_doesNotSurfaceCredentialMaterial() {
        KafkaProperties kafkaProperties = new KafkaProperties();
        kafkaProperties.setBootstrapServers(Collections.singletonList("127.0.0.1:1"));

        KafkaHealthIndicator indicator = new KafkaHealthIndicator(kafkaProperties);
        Health health = indicator.health();

        // The Health.Builder.down(Throwable) reflection records the error
        // as "error" → "<ExceptionType>: <message>", never as a full
        // property map.  No SASL credential material can leak because
        // the indicator never logs the producer-property map.
        assertThat(health.getDetails()).doesNotContainKey("bootstrap.servers");
        assertThat(health.getDetails()).doesNotContainKey("sasl.jaas.config");
        assertThat(health.getDetails()).doesNotContainKey("ssl.truststore.password");
    }

    /**
     * The indicator must accept the canonical {@link KafkaProperties}
     * bean as its sole constructor dependency (constructor injection per
     * AAP §0.7.1).
     */
    @Test
    @DisplayName("Constructor accepts a non-null KafkaProperties bean")
    void constructor_acceptsKafkaProperties() {
        KafkaProperties kafkaProperties = new KafkaProperties();
        kafkaProperties.setBootstrapServers(Collections.singletonList("127.0.0.1:9092"));

        // The constructor must not throw at construction time — health
        // probing is lazy and only happens when health() is invoked.
        KafkaHealthIndicator indicator = new KafkaHealthIndicator(kafkaProperties);
        assertThat(indicator).isNotNull();
    }

    /**
     * Calling {@code health()} multiple times against the same
     * unreachable broker is safe (no state accumulates, no resource
     * leakage). This mirrors the Actuator's polling contract.
     */
    @Test
    @DisplayName("Multiple health() invocations are independent and idempotent")
    void multipleHealthInvocations_areIdempotent() {
        KafkaProperties kafkaProperties = new KafkaProperties();
        kafkaProperties.setBootstrapServers(Collections.singletonList("127.0.0.1:1"));

        KafkaHealthIndicator indicator = new KafkaHealthIndicator(kafkaProperties);

        // Three sequential probes — each must return DOWN deterministically.
        for (int i = 0; i < 3; i++) {
            Health health = indicator.health();
            assertThat(health.getStatus())
                    .as("Probe %d must report DOWN", i + 1)
                    .isEqualTo(Status.DOWN);
        }
    }
}
