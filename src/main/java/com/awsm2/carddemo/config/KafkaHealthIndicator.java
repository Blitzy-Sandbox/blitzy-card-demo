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

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Spring Boot Actuator {@link HealthIndicator} that probes the configured
 * Amazon MSK (Apache Kafka) cluster on every {@code /actuator/health}
 * invocation, so that ECS task health checks and ALB target group readiness
 * probes accurately reflect Kafka availability.
 *
 * <p><b>QA CP11 Finding M-3 fix:</b> Spring Boot 3.x does <em>not</em>
 * auto-configure a Kafka {@link HealthIndicator} &mdash; only DB, Redis,
 * disk-space, liveness, readiness, ping, refresh-scope, and a discovery
 * composite are registered by default. The QA review observed that the ALB
 * target-group readiness probe ({@code /actuator/health/readiness}) would
 * therefore NOT detect MSK broker outages: an unreachable Kafka cluster
 * would leave the application marked healthy and continue to receive
 * traffic, while Kafka-published events would be silently dropped or
 * buffered by the producer's local accumulator until the producer's
 * {@code delivery.timeout.ms} envelope expired.</p>
 *
 * <p>This component closes that gap minimally by registering a custom
 * health indicator under the well-known bean name {@code kafka} (Spring
 * Boot Actuator derives the health-detail key by stripping the
 * {@code HealthIndicator} suffix from the bean name). The indicator can
 * then be included in the readiness probe group via
 * {@code management.endpoint.health.group.readiness.include} so that
 * Kafka availability becomes a precondition for ALB traffic routing.</p>
 *
 * <h2>Probe semantics</h2>
 * <ul>
 *   <li>On every invocation, the indicator opens a short-lived
 *       {@link AdminClient} with the application's configured
 *       bootstrap-servers and security overlay (SASL_SSL + AWS_MSK_IAM in
 *       {@code dev}/{@code prod}, PLAINTEXT in {@code local}/{@code test})
 *       and calls
 *       {@link AdminClient#describeCluster(DescribeClusterOptions)}.</li>
 *   <li>The probe is bounded by {@link #PROBE_TIMEOUT} so a slow Kafka
 *       broker does not stall the {@code /actuator/health} endpoint past
 *       the ALB / ECS health-check timeout (10s on ALB, 5s on ECS task).</li>
 *   <li>On success, the indicator reports {@link Health#up()} with the
 *       reported {@code clusterId}, {@code controller.id}, and
 *       {@code brokers} count as health details (only when
 *       {@code show-details=when-authorized} is met and the caller is
 *       authorised; see {@code application.yml}).</li>
 *   <li>On any failure &mdash; {@link InterruptedException},
 *       {@link java.util.concurrent.TimeoutException},
 *       {@link java.util.concurrent.ExecutionException}, or any
 *       {@link RuntimeException} &mdash; the indicator reports
 *       {@link Health#down()} with a sanitised exception message (no
 *       SASL/IAM credential material is exposed because the
 *       {@code AdminClient} configuration is constructed from the
 *       inherited {@link KafkaProperties} bean which already centralises
 *       credential redaction).</li>
 *   <li>The short-lived {@link AdminClient} is closed in a {@code try-
 *       with-resources} block on every invocation to avoid leaking a
 *       Kafka producer/connection per probe. This keeps the probe stateless
 *       and avoids long-lived auth tokens that would otherwise need
 *       refresh handling on rotation.</li>
 * </ul>
 *
 * <h2>Why a fresh AdminClient per probe?</h2>
 * <p>The application's main producer/consumer beans are
 * {@code @RefreshScope}-annotated (see {@link KafkaConfig}) so they pick up
 * rotated SASL/IAM credentials without a JVM restart. The health indicator
 * could share these beans, but doing so would mean a Kafka cluster outage
 * would also surface as a producer/consumer factory bean shutdown, which
 * would in turn complicate the rotation logic. The short-lived AdminClient
 * pattern keeps the probe orthogonal to the rotation lifecycle.</p>
 *
 * <h2>Readiness group inclusion</h2>
 * <p>This bean is wired into the {@code readiness} health group by:</p>
 * <pre>{@code
 * management:
 *   endpoint:
 *     health:
 *       group:
 *         readiness:
 *           include: readinessState,db,redis,kafka
 * }</pre>
 * <p>The {@code kafka} name matches the bean's auto-derived component key
 * (Spring Boot strips the {@code HealthIndicator} suffix from the bean
 * name).</p>
 *
 * @see com.awsm2.carddemo.config.KafkaConfig
 */
@Component("kafkaHealthIndicator")
public class KafkaHealthIndicator implements HealthIndicator {

    /**
     * SLF4J logger for probe-failure logging. Per AAP &sect;0.6.6 the
     * health indicator MUST NOT emit raw bootstrap-server URLs, SASL/IAM
     * credential material, or full producer-property maps; only the
     * Kafka exception's {@code getMessage()} and the request method/URI
     * (managed by Actuator itself, not this indicator) are emitted.
     */
    private static final Logger LOG = LoggerFactory.getLogger(KafkaHealthIndicator.class);

    /**
     * Timeout applied to {@link AdminClient#describeCluster}'s underlying
     * future. Sized to fit comfortably inside the ECS task health-check
     * timeout (5s) and the ALB target-group health-check timeout (10s)
     * with headroom for connection establishment, TLS handshake, and
     * SASL exchange on the first probe of each AdminClient instance.
     */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    /**
     * AdminClient {@code request.timeout.ms} value. Used as the lower
     * bound of the AdminClient's own request timeout so that the outer
     * {@link #PROBE_TIMEOUT}-bounded {@code get(...)} call always wins
     * the race. Set to 2 seconds (one second less than
     * {@link #PROBE_TIMEOUT}) so that the AdminClient surfaces a
     * {@link java.util.concurrent.TimeoutException} before the outer
     * timeout fires, producing a cleaner diagnostic message.
     */
    private static final int ADMIN_REQUEST_TIMEOUT_MS = 2000;

    /**
     * AdminClient {@code default.api.timeout.ms} value. Mirrors
     * {@link #ADMIN_REQUEST_TIMEOUT_MS} for the same reason &mdash;
     * the AdminClient times out before the outer probe envelope.
     */
    private static final int ADMIN_DEFAULT_API_TIMEOUT_MS = 2500;

    /**
     * Upper bound on the {@link AdminClient#close(Duration)} duration.
     * The AdminClient {@code close(Duration.ZERO)} would force-terminate
     * outstanding metadata operations &mdash; that is acceptable for a
     * health probe because the result is already known and the
     * AdminClient instance is single-use per invocation.
     *
     * <p>Bounded close prevents extended waits when the broker is
     * unreachable; a slow close would otherwise push the total probe
     * time past the ECS task health-check timeout (5s) even when the
     * outer {@link #PROBE_TIMEOUT} fires promptly.</p>
     */
    private static final Duration CLOSE_TIMEOUT = Duration.ofMillis(500);

    /**
     * Spring Boot's {@link KafkaProperties} bean &mdash; populated by
     * binding {@code spring.kafka.*} from the active profile's
     * {@code application*.yml}. This indicator inherits the producer
     * properties (bootstrap-servers, security.protocol, SASL JAAS
     * config, SSL settings) so the probe shares the application's
     * authentication posture; if the producer can talk to the brokers,
     * the AdminClient can too.
     */
    private final KafkaProperties kafkaProperties;

    /**
     * Constructor injection per AAP &sect;0.7.1 (Dependency injection
     * for loose coupling). The {@link KafkaProperties} bean is already
     * defined by Spring Boot's Kafka auto-configuration and consumed by
     * {@link KafkaConfig}; this indicator reuses the same bean so its
     * configuration cannot drift from the producer/consumer factories'
     * configuration.
     *
     * @param kafkaProperties Spring Boot Kafka properties; never
     *                        {@code null}
     */
    public KafkaHealthIndicator(KafkaProperties kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
    }

    /**
     * Probes the Kafka cluster and returns the resulting {@link Health}.
     *
     * <p>The probe creates a fresh, short-lived {@link AdminClient},
     * invokes {@link AdminClient#describeCluster(DescribeClusterOptions)},
     * and joins the futures for {@code clusterId()}, {@code controller()},
     * and {@code nodes()} within {@link #PROBE_TIMEOUT}. The AdminClient
     * is closed in a {@code try-with-resources} block on every
     * invocation.</p>
     *
     * @return {@link Health#up()} with cluster details on success;
     *         {@link Health#down()} with the exception's
     *         {@code getMessage()} on any failure. Never {@code null}.
     */
    @Override
    public Health health() {
        // Build the AdminClient configuration from the application's
        // existing producer properties so the probe shares the same
        // bootstrap-servers, TLS, and SASL/IAM posture as the production
        // KafkaTemplate. Bind a unique client.id per probe to disambiguate
        // broker-side telemetry for probe traffic vs. application traffic.
        Map<String, Object> adminProps = new HashMap<>(kafkaProperties.buildProducerProperties(null));
        adminProps.put(AdminClientConfig.CLIENT_ID_CONFIG, "carddemo-health-" + UUID.randomUUID());
        // Force tight timeouts so the probe completes in well under the
        // ECS/ALB health-check timeout window.
        adminProps.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, ADMIN_REQUEST_TIMEOUT_MS);
        adminProps.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, ADMIN_DEFAULT_API_TIMEOUT_MS);

        // Strip producer-specific properties that AdminClient rejects.
        // AdminClient accepts only AdminClientConfig.* + producer-shared
        // properties (e.g., bootstrap.servers, security.protocol, sasl.*,
        // ssl.*). The KafkaProperties.buildProducerProperties map carries
        // producer-only keys (acks, enable.idempotence, key.serializer,
        // etc.) that AdminClient will warn about but tolerate; an explicit
        // strip would be more robust and avoid log noise.
        adminProps.remove("acks");
        adminProps.remove("enable.idempotence");
        adminProps.remove("key.serializer");
        adminProps.remove("value.serializer");
        adminProps.remove("retries");
        adminProps.remove("max.in.flight.requests.per.connection");
        adminProps.remove("interceptor.classes");

        // The AdminClient is constructed lazily and closed in the finally
        // block with a bounded duration to ensure the total probe time
        // fits inside the ECS / ALB health-check budget. try-with-resources
        // is intentionally avoided because the default close() blocks
        // until all outstanding metadata operations complete — which can
        // push the probe past the 5s ECS health-check timeout when the
        // broker is unreachable.
        AdminClient adminClient = null;
        try {
            adminClient = AdminClient.create(adminProps);
            DescribeClusterResult clusterResult = adminClient.describeCluster(
                    new DescribeClusterOptions().timeoutMs((int) PROBE_TIMEOUT.toMillis()));

            // Await all three futures within the outer probe envelope.
            // Using TimeUnit.MILLISECONDS lets the probe envelope be
            // expressed as a Duration without losing precision.
            String clusterId = clusterResult.clusterId()
                    .get(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            int brokerCount = clusterResult.nodes()
                    .get(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).size();
            // Controller may be null on a brand-new cluster that has not
            // yet elected a controller; surface as -1 in that case so the
            // detail map never carries a null value (Health detail values
            // are required to be JSON-serialisable scalars).
            Integer controllerId = null;
            try {
                org.apache.kafka.common.Node controller = clusterResult.controller()
                        .get(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                if (controller != null) {
                    controllerId = controller.id();
                }
            } catch (RuntimeException ignored) {
                // Controller resolution is best-effort; the broker count
                // is the primary indicator of cluster health.
                controllerId = null;
            }

            // QA CP11 M-3 fix: emit UP with details so /actuator/health
            // (when authorised) surfaces the cluster identity for
            // operations triage. The detail map values are intentionally
            // scalars so JSON serialisation cannot recurse into bean
            // graphs that might carry credential material.
            return Health.up()
                    .withDetail("clusterId", clusterId != null ? clusterId : "unknown")
                    .withDetail("brokers", brokerCount)
                    .withDetail("controller", controllerId != null ? controllerId : -1)
                    .build();
        } catch (InterruptedException e) {
            // Interrupted while waiting on the future — restore the
            // interrupt flag per Java concurrency best practice and
            // report DOWN.
            Thread.currentThread().interrupt();
            LOG.warn("Kafka health probe interrupted: {}", e.getMessage());
            return Health.down(e).build();
        } catch (Exception e) {
            // ExecutionException, TimeoutException, KafkaException, any
            // other RuntimeException — all surface as DOWN with the
            // sanitised exception message. The exception type itself is
            // available in the Health detail via Health.down(Throwable).
            LOG.warn("Kafka health probe failed: type={} message={}",
                    e.getClass().getSimpleName(), e.getMessage());
            return Health.down(e).build();
        } finally {
            // Bounded close — release resources promptly so the probe
            // total time stays within the ECS / ALB health-check budget
            // even when the broker is unreachable.
            if (adminClient != null) {
                try {
                    adminClient.close(CLOSE_TIMEOUT);
                } catch (RuntimeException closeException) {
                    LOG.debug("AdminClient close raised an exception (ignored): {}",
                            closeException.getMessage());
                }
            }
        }
    }
}
