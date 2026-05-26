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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers-backed integration coverage for
 * {@link KafkaHealthIndicator}, verifying the UP path (a reachable Kafka
 * cluster reports {@link Status#UP} with cluster details) that the
 * unit-level {@link KafkaHealthIndicatorTest} cannot exercise.
 *
 * <p><b>QA CP11 Finding M-3 fix:</b> the integration test confirms that
 * once a real Kafka broker is reachable, the custom health indicator
 * registered under the {@code kafka} component key surfaces UP with the
 * cluster identity, broker count, and controller ID as health details
 * &mdash; values that operators can inspect on
 * {@code /actuator/health} when {@code show-details=when-authorized}.</p>
 *
 * <p><b>Container:</b> {@code confluentinc/cp-kafka:7.5.0} via
 * {@link ConfluentKafkaContainer} (same image used by the
 * {@code KafkaEventPublisherIntegrationTest} for consistency across the
 * integration test suite).</p>
 *
 * @see KafkaHealthIndicator
 * @see KafkaHealthIndicatorTest
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("KafkaHealthIndicator — Testcontainers integration (UP path)")
class KafkaHealthIndicatorIntegrationTest {

    /**
     * Single shared Kafka container for the whole test class. The
     * {@link Container} annotation lets Testcontainers start the broker
     * once and reuse it across every {@code @Test} method.
     */
    @Container
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    private static KafkaHealthIndicator indicator;

    @BeforeAll
    static void setUp() {
        // Bind the container's bootstrap-server address into a real
        // KafkaProperties bean — exactly the same shape Spring Boot
        // would bind from application*.yml. The indicator does not
        // require any other property (no SASL, no SSL in this test).
        KafkaProperties kafkaProperties = new KafkaProperties();
        kafkaProperties.setBootstrapServers(Collections.singletonList(KAFKA.getBootstrapServers()));
        indicator = new KafkaHealthIndicator(kafkaProperties);
    }

    @AfterAll
    static void tearDown() {
        // No explicit teardown — the indicator is stateless and the
        // Kafka container is closed by Testcontainers.
    }

    /**
     * The canonical UP-path coverage: a reachable Kafka cluster reports
     * UP with cluster identity, broker count, and (optionally)
     * controller ID. This is the behaviour the ALB target-group
     * readiness probe relies on to decide that the task is ready to
     * receive traffic.
     */
    @Test
    @DisplayName("Probe against a reachable Kafka cluster reports Health.UP with details")
    void probe_againstReachableBroker_reportsUp() {
        Health health = indicator.health();

        // Status must be UP.
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        // Cluster ID must be present and non-blank — every Kafka cluster
        // has a self-assigned UUID-like identifier.
        assertThat(health.getDetails())
                .containsKey("clusterId");
        assertThat(health.getDetails().get("clusterId"))
                .isInstanceOf(String.class);
        assertThat((String) health.getDetails().get("clusterId"))
                .isNotBlank();
        // Brokers count — Testcontainers spins up a single-broker
        // cluster by default, so the count is 1.
        assertThat(health.getDetails())
                .containsKey("brokers");
        assertThat(health.getDetails().get("brokers"))
                .isInstanceOf(Integer.class);
        assertThat((Integer) health.getDetails().get("brokers"))
                .isGreaterThanOrEqualTo(1);
        // Controller — never null, may be -1 if controller resolution
        // raced the probe envelope; in practice, by the time
        // describeCluster.controller().get() returns, the controller is
        // settled.
        assertThat(health.getDetails())
                .containsKey("controller");
        assertThat(health.getDetails().get("controller"))
                .isInstanceOf(Integer.class);
    }

    /**
     * Repeated probes against the same reachable cluster all report UP
     * &mdash; mirrors the Actuator's polling contract for ECS health
     * checks (every 30s by default) and ALB target group health checks
     * (every 30s).
     */
    @Test
    @DisplayName("Multiple consecutive probes against reachable broker all report UP")
    void multipleProbes_againstReachableBroker_allReportUp() {
        for (int i = 0; i < 3; i++) {
            Health health = indicator.health();
            assertThat(health.getStatus())
                    .as("Probe %d must report UP", i + 1)
                    .isEqualTo(Status.UP);
        }
    }
}
