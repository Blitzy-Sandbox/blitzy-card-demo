package com.carddemo.account;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CardDemo Account Service &mdash; typed-stub account bounded context.
 *
 * <p>Spring Boot entrypoint and <strong>component-scan root</strong> for
 * {@code account-svc}. Because this class sits directly in the base package
 * {@code com.carddemo.account}, the default Spring Boot component scan automatically
 * discovers the sibling sub-packages {@code com.carddemo.account.web} (the typed-stub
 * controller implementing the generated API interface) and
 * {@code com.carddemo.account.config} (the correlation-id filter and OpenAPI metadata),
 * as well as the OpenAPI-generated {@code com.carddemo.account.api} /
 * {@code com.carddemo.account.model} packages emitted under
 * {@code target/generated-sources/openapi} at build time. No explicit scan
 * configuration is therefore required, so a plain {@code @SpringBootApplication}
 * (no attributes) is used.</p>
 *
 * <p><strong>[DEFERRED]:</strong> every account operation is a typed stub that returns a
 * typed placeholder DTO &mdash; there is NO live account persistence and NO business
 * logic in this walking-skeleton run. The service is nonetheless a request-serving,
 * health-gated, long-running container (NOT health-exempt): container readiness is
 * reported by Spring Boot Actuator at {@code /actuator/health}. Datasource
 * auto-configuration is left intact and driven entirely by the sibling
 * {@code src/main/resources/application.yml} and environment variables injected by
 * docker-compose.</p>
 *
 * <p>Provenance: [SRC: COACTVWC/COACTUPC | ACCTDAT] &mdash; app/csd/CARDDEMO.CSD
 * (transaction CAVW -&gt; program COACTVWC "Accept and process Account View request",
 * transaction CAUP -&gt; program COACTUPC "Accept and process ACCOUNT UPDATE"), both
 * operating over the ACCTDAT VSAM KSDS
 * (DSNAME AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS; record layout app/cpy/CVACT01Y.cpy).</p>
 */
// Plain @SpringBootApplication: base-package placement lets the default component
// scan reach the web, config, and generated api/model packages with no extra config.
@SpringBootApplication
public class AccountApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccountApplication.class, args);
    }
}
