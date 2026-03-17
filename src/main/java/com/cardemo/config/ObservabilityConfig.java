/*
 * ObservabilityConfig.java — Tracing, Metrics, and Correlation ID Configuration
 *
 * Central observability infrastructure for the CardDemo application. The original
 * COBOL source has ZERO observability (no logging framework, no metrics, no tracing,
 * no health checks), so this is built entirely from scratch.
 *
 * Per AAP §0.7.1 and §0.8.6 ("Observability" rule): observability ships with the
 * initial implementation, not as a follow-up.
 *
 * Provides:
 * - Correlation ID filter: injects UUID-based correlation IDs into SLF4J MDC and
 *   HTTP response headers for end-to-end request tracing across all service and
 *   batch layers.
 * - Metrics common tags: adds application and environment tags to ALL Micrometer
 *   metrics for Prometheus/Grafana filtering across environments.
 *
 * Distributed tracing (Micrometer Tracing + OpenTelemetry bridge) and Prometheus
 * metrics endpoints are auto-configured by Spring Boot Actuator from dependencies
 * declared in pom.xml (micrometer-tracing-bridge-otel, micrometer-registry-prometheus).
 * OTLP exporter and actuator endpoint configuration resides in application.yml.
 *
 * Logback structured JSON logging configuration resides in logback-spring.xml.
 *
 * Custom business metrics (registered by individual service/batch classes):
 * - carddemo.batch.records.processed  (counter, per job)   — batch processor classes
 * - carddemo.batch.records.rejected   (counter, reason tag) — RejectWriter
 * - carddemo.auth.attempts            (counter, result tag) — AuthenticationService
 * - carddemo.transaction.amount.total (distribution summary) — TransactionAddService
 *
 * Health/readiness checks (/actuator/health, /actuator/health/liveness,
 * /actuator/health/readiness) are auto-configured by Spring Boot Actuator
 * with composite indicators for PostgreSQL, S3, and SQS. Custom health
 * indicators are provided by HealthIndicators.java in the observability package.
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.cardemo.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring {@link Configuration} class providing observability infrastructure for
 * the CardDemo application. Replaces the COBOL zero-observability baseline with
 * structured logging correlation, distributed tracing, and metrics tagging.
 *
 * <p>This configuration class declares one bean:
 * <ul>
 *   <li>{@link #metricsCommonTags()} — a registry customizer that tags all
 *       Micrometer metrics with application name and environment</li>
 * </ul>
 *
 * <p>The correlation ID filter is provided by
 * {@link com.cardemo.observability.CorrelationIdFilter}, which is a
 * {@code @Component}-annotated {@link jakarta.servlet.Filter} registered
 * at {@code HIGHEST_PRECEDENCE} ordering.</p>
 *
 * <p>All other observability features (distributed tracing via OpenTelemetry,
 * Prometheus metrics endpoint, health indicators) are auto-configured by
 * Spring Boot from the dependencies declared in {@code pom.xml} and properties
 * in {@code application.yml}.
 */
@Configuration
public class ObservabilityConfig {

    private static final Logger logger = LoggerFactory.getLogger(ObservabilityConfig.class);

    /**
     * Active Spring profile injected at runtime. Used as the {@code environment}
     * common tag on all Micrometer metrics, enabling Prometheus/Grafana filtering
     * across local, test, and production environments.
     */
    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    /**
     * Creates a {@link MeterRegistryCustomizer} that adds common tags to
     * <strong>all</strong> Micrometer metrics automatically.
     *
     * <p>Common tags applied:
     * <ul>
     *   <li>{@code application=carddemo} — identifies this application in
     *       multi-service Prometheus/Grafana dashboards</li>
     *   <li>{@code environment=<active-profile>} — distinguishes metrics from
     *       local, test, and production environments</li>
     * </ul>
     *
     * <p>These tags are applied globally and are visible on every metric exposed
     * at {@code /actuator/prometheus}, enabling dashboard-level filtering without
     * per-metric tagging in business code.
     *
     * <p>Individual services register their own custom business metrics directly
     * with the {@link MeterRegistry} (see class-level Javadoc for the canonical
     * metric names).
     *
     * @return a {@link MeterRegistryCustomizer} that adds application-wide tags
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        logger.info("Configuring Micrometer common tags: application=carddemo, environment={}",
                activeProfile);
        return registry -> registry.config()
                .commonTags("application", "carddemo", "environment", activeProfile);
    }
}
