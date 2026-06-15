package com.carddemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Application bootstrap and single executable entry point for the CardDemo Java platform.
 *
 * <p>This is the root {@code @SpringBootApplication} class that launches the Java&nbsp;25 +
 * Spring&nbsp;Boot&nbsp;3.x re-platforming of the AWS CardDemo mainframe workload (originally
 * COBOL/CICS/VSAM/JCL/BMS). Booting this class starts the complete runtime in one process: the
 * online REST flows, the Spring Batch processing pipeline, and the AWS/observability
 * infrastructure.</p>
 *
 * <p>Because the class resides in the base package {@code com.carddemo}, the component scan implied
 * by {@link SpringBootApplication @SpringBootApplication} is rooted here and therefore discovers
 * every functional subpackage automatically: {@code config}, {@code model}, {@code repository},
 * {@code service}, {@code controller}, {@code batch}, {@code exception}, and
 * {@code observability}. No explicit {@code scanBasePackages} attribute is required.</p>
 *
 * <p><strong>Lineage.</strong> This bootstrap corresponds to the CICS sign-on program
 * {@code COSGN00C} (transaction {@code CC00}) of the source application
 * {@code CardDemo_v1.0-15-g27d6c6f-68}, commit {@code 27d6c6f}, purely as the historical
 * <em>entry context</em>. It replaces the CICS transaction-entry mechanism with the Spring Boot
 * runtime bootstrap. The sign-on and authentication business logic is migrated separately into the
 * authentication service, the authentication controller, and the security configuration; it is
 * intentionally absent from this class.</p>
 */
@SpringBootApplication
public class CardDemoApplication {

    /**
     * Starts the CardDemo Spring Boot application and bootstraps the Spring
     * {@code ApplicationContext}.
     *
     * @param args command-line arguments forwarded to {@link SpringApplication}
     */
    public static void main(String[] args) {
        SpringApplication.run(CardDemoApplication.class, args);
    }
}
