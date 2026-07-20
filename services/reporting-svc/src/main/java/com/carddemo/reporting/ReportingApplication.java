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
 * <p>INVOCATION (finding P4-m04). Because the service serves no HTTP surface, the job
 * stubs are triggered by a real, non-host-serving mechanism: the
 * {@link com.carddemo.reporting.runner.JobInvocationRunner} — a Spring Boot
 * {@code ApplicationRunner} gated by {@code carddemo.reporting.invoke-jobs-on-startup=true}.
 * When that flag is set, the runner invokes every {@code jobs/*JobHandler} entrypoint once at
 * startup and logs the typed acknowledgements, so final acceptance can invoke the batch stubs
 * without any host port, scheduler, or queue. The flag is OFF by default, so a plain start (and
 * the build-green context-load test) has no side effects. The job roster covers the online
 * reports ({@code CORPT00C}) and statement ({@code CBSTM03A/B}) submissions plus the deferred
 * batch programs daily posting ({@code CBTRN02C}), interest calculation ({@code CBACT04C}), and
 * the batch transaction report ({@code CBTRN03C}).</p>
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
