package com.carddemo.useradmin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CardDemo User Admin Service — User Administration + Admin Menu, typed stub.
 *
 * <p>Spring Boot application entrypoint for the {@code useradmin-svc} bounded context of the
 * CardDemo walking skeleton. This is a request-serving, health-gated, long-running service whose
 * user-administration behavior is deliberately {@code [DEFERRED]} — it exposes its contract and
 * returns typed stub responses only (no persistence, no real user CRUD). All REST behavior
 * lives in the sibling {@code web} package ({@code UserController}, {@code AdminController}); the
 * correlation-ID hop and OpenAPI metadata live in the sibling {@code config} package
 * ({@code CorrelationIdFilter}, {@code OpenApiConfig}).
 *
 * <p>Because this class resides directly in the base package {@code com.carddemo.useradmin}, it is
 * the Spring component-scan root for the whole service: the default {@code @SpringBootApplication}
 * component scan automatically discovers the sibling sub-packages {@code com.carddemo.useradmin.web}
 * and {@code com.carddemo.useradmin.config}, as well as the OpenAPI-generated
 * {@code com.carddemo.useradmin.api} / {@code com.carddemo.useradmin.model} packages emitted under
 * {@code target/generated-sources/openapi} at build time. No explicit component-scan configuration
 * (no base-package override) is required or permitted.
 *
 * <p>Provenance: {@code [SRC: COUSR00C-03C, COADM01C | USRSEC]} — the legacy CICS
 * User-Administration transactions ({@code CU00} → {@code COUSR00C} user list, {@code CU01} →
 * {@code COUSR01C} user add, {@code CU02} → {@code COUSR02C} user update, {@code CU03} →
 * {@code COUSR03C} user delete) and the Admin-Menu transaction ({@code CA00} → {@code COADM01C}),
 * all registered over the {@code USRSEC} VSAM KSDS in {@code app/csd/CARDDEMO.CSD}.
 */
// Plain @SpringBootApplication (no attributes): the base-package location above makes the default
// component scan cover the web/, config/, and generated api/model packages, so no explicit
// component-scan or base-package override is needed. Datasource/JPA/Actuator/correlation settings
// all come from application.yml; this class intentionally carries no behavior, beans, JPA, or
// security configuration.
@SpringBootApplication
public class UserAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserAdminApplication.class, args);
    }
}
