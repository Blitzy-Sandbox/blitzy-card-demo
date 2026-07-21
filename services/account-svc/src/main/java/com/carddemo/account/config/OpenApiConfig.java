package com.carddemo.account.config;

import org.springframework.context.annotation.Configuration;

/**
 * Application configuration placeholder for the CardDemo Account Service.
 *
 * <p>This is the {@code account} bounded context of the CardDemo walking skeleton
 * (Spring Boot 3.5.16, Java 21). The class is intentionally a minimal, dependency-free
 * configuration seam: the walking skeleton needs the seam to exist, not behavior. All
 * account operations (view / update) are {@code [DEFERRED]} typed stubs.
 *
 * <p><strong>API documentation source of truth.</strong> Despite its historical name, this
 * class declares no runtime API-documentation or API-UI beans, and none are on the
 * classpath. The service's API is defined exclusively by the frozen OpenAPI 3.1 contract
 * {@code contracts/account-svc.openapi.yaml} &mdash; the single source of truth from which
 * the Spring server interfaces ({@code com.carddemo.account.api.*}) and DTO models
 * ({@code com.carddemo.account.model.*}) are generated at build time by the
 * {@code openapi-generator-maven-plugin} (generatorName {@code spring},
 * {@code interfaceOnly=true}) &mdash; and not by any runtime documentation endpoint. The
 * sibling {@code pom.xml} deliberately does not depend on springdoc-openapi or the swagger
 * model library, so declaring OpenAPI/Swagger beans here would fail compilation. This
 * mirrors the empty configuration seam used by {@code auth-svc} and {@code transaction-svc}.
 *
 * <p>This {@code @Configuration} is auto-detected by Spring component scanning from the
 * {@code com.carddemo.account} base package declared on {@code AccountApplication}; no
 * explicit component-scan annotation is required.
 *
 * <p>Provenance: [SRC: COACTVWC/COACTUPC | ACCTDAT] &mdash; app/csd/CARDDEMO.CSD
 * (CAVW -&gt; COACTVWC Account View and CAUP -&gt; COACTUPC Account Update, both over the
 * {@code ACCTDAT} VSAM KSDS). The legacy reference is topology/provenance only and does not
 * dictate this code.
 */
@Configuration
public class OpenApiConfig {
    // Intentionally empty: the frozen OpenAPI 3.1 contract is the API single source of
    // truth; this class declares no beans and adds no dependency to the service.
}
