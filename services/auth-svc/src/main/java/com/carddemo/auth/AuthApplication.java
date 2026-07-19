package com.carddemo.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * auth-svc &mdash; CardDemo walking skeleton.
 *
 * <p>CardDemo Auth Service &mdash; permissive sign-on stub. It issues a REAL bearer
 * token and participates in the REAL correlation-ID hop (UI -&gt; BFF -&gt; auth-svc),
 * but performs NO real credential validation. This entrypoint encodes NO behavior; it
 * only bootstraps the Spring context. The permissive-stub request handling lives in
 * the sibling {@code com.carddemo.auth.web.AuthController}; the correlation hop lives
 * in {@code com.carddemo.auth.config.CorrelationIdFilter}.</p>
 *
 * <p>This class lives at the package root {@code com.carddemo.auth} so the default
 * Spring Boot component scan naturally covers the sibling subpackages
 * {@code com.carddemo.auth.web} and {@code com.carddemo.auth.config}, AND the
 * OpenAPI-generated packages {@code com.carddemo.auth.api} /
 * {@code com.carddemo.auth.model} (emitted under
 * {@code target/generated-sources/openapi}). Therefore no {@code @ComponentScan} /
 * {@code scanBasePackages} declaration is needed. This service declares no JPA
 * entities and owns no schema, so no {@code @EntityScan} /
 * {@code @EnableJpaRepositories} is required either; auto-configuration is left intact
 * so the env-injected Oracle datasource wiring remains available at runtime.</p>
 *
 * <p>Provenance: [SRC: COSGN00C | COSGN00.bms] &mdash; app/csd/CARDDEMO.CSD
 * (transaction CC00 -&gt; program COSGN00C, DESCRIPTION "LOGIN", the legacy sign-on
 * over the USRSEC VSAM KSDS).</p>
 */
@SpringBootApplication
public class AuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
