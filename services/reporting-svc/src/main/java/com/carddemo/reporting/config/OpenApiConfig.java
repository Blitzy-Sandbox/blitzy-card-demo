package com.carddemo.reporting.config;

import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI configuration anchor for {@code reporting-svc} (CardDemo walking skeleton).
 *
 * <p>This is an intentionally <strong>empty, dependency-free</strong>
 * {@link Configuration} class. It defines <em>no</em> beans and imports no
 * springdoc / swagger types.</p>
 *
 * <p><strong>Contract is the single source of truth (SSoT).</strong> The frozen
 * OpenAPI 3.1 contract at {@code /contracts/reporting-svc.openapi.yaml} is the SSoT for
 * this service's HTTP surface. At build time the {@code openapi-generator-maven-plugin}
 * (generatorName {@code spring}, {@code interfaceOnly=true}) generates the API
 * interfaces into {@code com.carddemo.reporting.api.*} and the DTO models into
 * {@code com.carddemo.reporting.model.*}. Those generated sources are NEVER hand-edited;
 * to change the API, edit the frozen contract and regenerate.</p>
 *
 * <p><strong>Why no springdoc/swagger bean?</strong> springdoc-openapi is not part of the
 * dependency inventory and the swagger <em>models</em> package
 * ({@code io.swagger.v3.oas.models}) is not on the classpath, so declaring an
 * {@code io.swagger.v3.oas.models.OpenAPI} bean here would fail the green build.</p>
 *
 * <p><strong>Health-exempt.</strong> reporting-svc is an async job stub: there is no
 * {@code /actuator/health} endpoint and no {@code management} wiring; it is not part of
 * the docker-compose {@code service_healthy} chain.</p>
 *
 * <p>Provenance: [SRC: CORPT00C, CBSTM03A/B | TRANSACT] &mdash; app/csd/CARDDEMO.CSD
 * (CR00 -&gt; CORPT00C over file TRANSACT; batch statements CBSTM03A/B).</p>
 */
@Configuration
public class OpenApiConfig {
}
