package com.carddemo.transaction.config;

import org.springframework.context.annotation.Configuration;

/**
 * Application configuration placeholder for the CardDemo Transaction Service.
 *
 * <p>This is the {@code transaction} bounded context of the CardDemo walking skeleton
 * (Spring Boot 3.5.16, Java 21). The class is intentionally a minimal, dependency-free
 * configuration seam: the walking skeleton needs the seam to exist, not behavior. All
 * transaction operations (list / view / add) are {@code [DEFERRED]} typed stubs.
 *
 * <p><strong>API documentation source of truth.</strong> Despite its historical name, this
 * class declares no runtime API-documentation or API-UI beans, and none are on the
 * classpath. The service's API is defined exclusively by the frozen OpenAPI 3.1 contract
 * {@code contracts/transaction-svc.openapi.yaml} &mdash; the single source of truth from
 * which the Spring server interfaces are generated at build time &mdash; and not by any
 * runtime documentation endpoint. The sibling {@code pom.xml} runs the OpenAPI generator
 * with {@code documentationProvider=none} and {@code annotationLibrary=none}, so no
 * API-documentation library is on the classpath; declaring such beans here would fail
 * compilation. This mirrors the empty configuration seam used by {@code auth-svc}.
 *
 * <p>This {@code @Configuration} is auto-detected by Spring component scanning from the
 * {@code com.carddemo.transaction} base package declared on {@code TransactionApplication};
 * no explicit component-scan annotation is required.
 *
 * <p>Provenance: [SRC: COTRN00C/COTRN01C/COTRN02C | TRANSACT] &mdash; the legacy CICS
 * transactions {@code CT00 -> COTRN00C} (list), {@code CT01 -> COTRN01C} (view) and
 * {@code CT02 -> COTRN02C} (add) over the {@code TRANSACT} VSAM KSDS registered in
 * {@code app/csd/CARDDEMO.CSD}. The legacy reference is topology/provenance only and does
 * not dictate this code.
 */
@Configuration
public class OpenApiConfig {
    // Intentionally empty: the frozen OpenAPI 3.1 contract is the API single source of
    // truth; this class declares no beans and adds no dependency to the service.
}
