package com.carddemo.reporting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * reporting-svc — CardDemo walking skeleton.
 *
 * <p>ASYNC JOB STUB — HEALTH-EXEMPT. This service builds green and exposes
 * invokable job entrypoints (the {@code jobs/*JobHandler} beans discovered by the
 * component scan rooted here), but it is deliberately NOT held to start-and-serve,
 * is NOT in the docker-compose {@code service_healthy} chain, and publishes no host
 * port. Every operation is a typed stub ([DEFERRED]); no TRANSACT reads, no report
 * or statement calculation, no file output.</p>
 *
 * <p>This class lives at the package root {@code com.carddemo.reporting} so the default
 * Spring Boot component scan covers the {@code jobs} and {@code config} subpackages
 * without explicit {@code @ComponentScan} / {@code @EntityScan} /
 * {@code @EnableJpaRepositories} declarations. There are no JPA entities in this
 * service.</p>
 *
 * <p>Provenance: [SRC: CORPT00C, CBSTM03A/B | TRANSACT] — app/csd/CARDDEMO.CSD
 * (CR00 -&gt; CORPT00C over file TRANSACT; batch statements CBSTM03A/B).</p>
 */
@SpringBootApplication
public class ReportingApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReportingApplication.class, args);
    }
}
