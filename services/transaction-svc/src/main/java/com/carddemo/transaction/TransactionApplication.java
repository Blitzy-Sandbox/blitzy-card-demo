package com.carddemo.transaction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CardDemo Transaction Service &mdash; typed-stub transaction bounded context of
 * the CardDemo walking skeleton.
 *
 * <p>This is the Spring Boot application entrypoint and the Spring
 * <strong>component-scan root</strong> for {@code transaction-svc}. It encodes NO
 * behavior; it only bootstraps the Spring context.</p>
 *
 * <p>{@code transaction-svc} is a request-serving, health-gated, long-running
 * container whose operations are all typed stubs ([DEFERRED]): every endpoint
 * returns a typed placeholder DTO, with NO live transaction persistence and NO
 * business logic. Readiness is reported by Spring Boot Actuator at
 * {@code /actuator/health} for the docker-compose {@code service_healthy} gate.
 * The typed-stub controller lives in {@code com.carddemo.transaction.web}; the
 * correlation-ID hop and the OpenAPI config placeholder live in
 * {@code com.carddemo.transaction.config}.</p>
 *
 * <p>Rationale for a plain {@code @SpringBootApplication}: because this class sits
 * at the base package {@code com.carddemo.transaction}, the default component scan
 * automatically covers the sibling sub-packages {@code com.carddemo.transaction.web}
 * and {@code com.carddemo.transaction.config}, as well as the OpenAPI-generated
 * {@code com.carddemo.transaction.api} and {@code com.carddemo.transaction.model}
 * packages emitted under {@code target/generated-sources/openapi} at build time.
 * Therefore no {@code scanBasePackages} / {@code @ComponentScan} configuration is
 * needed or permitted.</p>
 *
 * <p>Provenance: [SRC: COTRN00C/COTRN01C/COTRN02C | TRANSACT] &mdash;
 * app/csd/CARDDEMO.CSD registers the legacy CICS Transaction List
 * (CT00 -&gt; COTRN00C, "List Transactions from TRANSACT file"), Transaction View
 * (CT01 -&gt; COTRN01C, "View a Transaction from TRANSACT file") and Transaction Add
 * (CT02 -&gt; COTRN02C, "Add a new Transaction to TRANSACT file") programs over the
 * TRANSACT VSAM KSDS; record layout app/cpy/CVTRA05Y.cpy.</p>
 */
@SpringBootApplication
public class TransactionApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransactionApplication.class, args);
    }
}
