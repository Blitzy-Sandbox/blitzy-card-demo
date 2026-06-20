package com.carddemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CardDemo Java application bootstrap and runtime entry point.
 *
 * <p>This is the single executable entry point for the Java 25 LTS and Spring Boot 3.x
 * re-platforming of the AWS CardDemo mainframe application (COBOL / CICS / VSAM / JCL / BMS).
 * Launching this class starts the entire runtime in one process: the REST online flows, the
 * Spring Batch processing pipeline, and the supporting AWS and observability infrastructure.</p>
 *
 * <p><strong>COBOL entry-context lineage (reference only).</strong> This bootstrap replaces the
 * CICS transaction-entry mechanism of the signon program {@code COSGN00C} (transaction
 * {@code CC00}) from source commit {@code 27d6c6f} ({@code CardDemo_v1.0-15-g27d6c6f-68}). Only
 * the role of "application entry point" is carried across the migration; the original sign-on
 * and authentication behaviour is implemented by the dedicated authentication service,
 * authentication controller, and Spring Security configuration rather than in this class.</p>
 *
 * <p>Because the class is declared in the base package {@code com.carddemo}, the default
 * component scan contributed by {@link SpringBootApplication} discovers every application
 * subpackage automatically: {@code config}, {@code model}, {@code repository}, {@code service},
 * {@code controller}, {@code batch}, {@code exception}, and {@code observability}. Cross-cutting
 * concerns (the Spring Batch job and step registry, JPA entity scanning and auditing,
 * scheduling, and externalized configuration properties) are intentionally owned by the
 * dedicated configuration classes, keeping this bootstrap at its canonical, minimal shape.</p>
 */
@SpringBootApplication
public class CardDemoApplication {

    /**
     * Boots the CardDemo Spring Boot application context.
     *
     * @param args command-line arguments forwarded to
     *             {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        SpringApplication.run(CardDemoApplication.class, args);
    }
}
