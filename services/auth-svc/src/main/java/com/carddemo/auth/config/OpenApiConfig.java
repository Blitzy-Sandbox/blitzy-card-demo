package com.carddemo.auth.config;

import org.springframework.context.annotation.Configuration;

/**
 * Application configuration placeholder for the CardDemo Auth Service (auth-svc).
 *
 * <p>Despite the historical name {@code OpenApiConfig}, this class deliberately declares
 * <strong>no</strong> runtime API-documentation beans. This service's API documentation is
 * defined exclusively by the frozen OpenAPI 3.1 contract
 * {@code contracts/auth-svc.openapi.yaml} &mdash; the single source of truth (SSoT) from which
 * the Java server interfaces ({@code com.carddemo.auth.api.*}) and DTO models
 * ({@code com.carddemo.auth.model.*}) are generated at build time by the
 * {@code openapi-generator-maven-plugin}. There is no runtime API-docs UI endpoint for this
 * service, and none is intended.</p>
 *
 * <p><strong>Why there are no documentation-library beans here:</strong> the sibling build
 * ({@code services/auth-svc/pom.xml}) runs the generator with {@code documentationProvider=none}
 * and {@code annotationLibrary=none}, so no runtime documentation-provider or annotation-library
 * types are present on the classpath. Referencing any such type would fail compilation.
 * Consequently this class imports only
 * {@link org.springframework.context.annotation.Configuration} and defines no beans.</p>
 *
 * <p><strong>Role in the walking skeleton:</strong> this is a minimal, dependency-free
 * configuration seam that exists so the {@code config} package has a place to hold small
 * application-level configuration should it ever be needed. In the walking skeleton it is
 * intentionally empty &mdash; the seam must exist, but no behavior is required. It is
 * auto-detected and registered by the sibling {@code AuthApplication}
 * ({@code @SpringBootApplication} at base package {@code com.carddemo.auth}) via component
 * scanning; no explicit {@code @ComponentScan} or bean registration is necessary.</p>
 *
 * <p>Provenance: {@code [SRC: COSGN00C | COSGN00.bms]} &mdash; the legacy CICS Sign-On
 * transaction {@code CC00 -> program COSGN00C} reading the {@code USRSEC} VSAM dataset.
 * auth-svc is the modern permissive-authentication bounded context. The legacy artifact is
 * provenance only; it is never modified and does not dictate this code.</p>
 */
@Configuration
public class OpenApiConfig {
    // Intentionally empty: the frozen OpenAPI 3.1 contract (contracts/auth-svc.openapi.yaml)
    // is the API single source of truth. No runtime API-documentation beans are declared, and
    // no such types are on the classpath (documentationProvider=none, annotationLibrary=none
    // in pom.xml).
}
