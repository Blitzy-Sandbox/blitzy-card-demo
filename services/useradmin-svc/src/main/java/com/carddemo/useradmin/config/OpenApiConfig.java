package com.carddemo.useradmin.config;

import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI configuration anchor for {@code useradmin-svc} (CardDemo walking skeleton) —
 * the User Administration + Admin Menu bounded context.
 *
 * <p>This is an intentionally <strong>empty, dependency-free</strong>
 * {@link Configuration} class. It defines <em>no</em> beans and imports no
 * springdoc / swagger types.</p>
 *
 * <p><strong>Contract is the single source of truth (SSoT).</strong> The frozen
 * OpenAPI 3.1 contract at {@code /contracts/useradmin-svc.openapi.yaml}
 * ({@code info.title = "User Admin Service API"}, {@code info.version = "1.0.0"}) is
 * the SSoT for this service's HTTP surface. At build time the
 * {@code openapi-generator-maven-plugin} (generatorName {@code spring},
 * {@code interfaceOnly=true}, {@code useTags=true}) generates the API interfaces into
 * {@code com.carddemo.useradmin.api.*} and the DTO models into
 * {@code com.carddemo.useradmin.model.*}. Those generated sources are NEVER
 * hand-edited; to change the API, edit the frozen contract and regenerate.</p>
 *
 * <p><strong>Why no springdoc/swagger bean?</strong> The already-created
 * {@code services/useradmin-svc/pom.xml} runs the generator with
 * {@code documentationProvider=none}, {@code annotationLibrary=none}, and
 * {@code openApiNullable=false}, and it does <em>not</em> declare
 * {@code org.springdoc:springdoc-openapi-starter-webmvc-ui}. Consequently the swagger
 * <em>models</em> package ({@code io.swagger.v3.oas.models}) is not on the classpath
 * (verified: it appears nowhere on the module dependency tree), so declaring an
 * {@code io.swagger.v3.oas.models.OpenAPI} metadata bean here would fail the green
 * build with "package io.swagger.v3.oas.models does not exist". This class therefore
 * follows the skeleton-wide convention shared by the sibling {@code auth-svc},
 * {@code card-svc}, {@code reporting-svc}, and {@code transaction-svc}
 * {@code OpenApiConfig} classes (an empty {@code @Configuration}); the frozen
 * dependency inventory (AAP 0.3) lists no springdoc for this service.
 * ({@code payment-svc} is the sole service that opts into springdoc.) Health is served
 * by Spring Boot Actuator at {@code /actuator/health}, backing the docker-compose
 * {@code depends_on: condition: service_healthy} gate.</p>
 *
 * <p>Auto-detected by the component scan of the sibling entrypoint
 * {@code com.carddemo.useradmin.UserAdminApplication} (a plain
 * {@code @SpringBootApplication}).</p>
 *
 * <p>Provenance: [SRC: COUSR00C-03C, COADM01C | USRSEC] &mdash; app/csd/CARDDEMO.CSD
 * (CU00 -&gt; COUSR00C User List; CU01/CU02/CU03 -&gt; COUSR01C/02C/03C User
 * Add/Update/Delete; CA00 -&gt; COADM01C Admin Menu; over the USRSEC VSAM KSDS).
 * Topology only; this is an infrastructure class carrying no legacy business logic.</p>
 */
@Configuration
public class OpenApiConfig {
}
