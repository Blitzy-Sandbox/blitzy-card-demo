/*
 * CardDemo Application - Spring Boot Main Entry Point
 *
 * Migrated from: COBOL COSGN00C.cbl (CICS Transaction ID CC00)
 * Source commit: 27d6c6f (aws-samples/carddemo)
 *
 * This class serves as the bootstrap entry point for the CardDemo Java
 * application, replacing the COBOL COSGN00C sign-on program that was the
 * initial CICS pseudo-conversational transaction entry point. In the COBOL
 * system, COSGN00C handled user authentication and session initialization
 * via EXEC CICS RETURN TRANSID with COMMAREA. In the Java migration,
 * authentication is delegated to Spring Security (SecurityConfig.java) and
 * the AuthenticationService, while this class solely bootstraps the Spring
 * application context.
 *
 * Component scanning from the com.cardemo base package auto-discovers:
 *   - config/    : @Configuration classes (Security, Batch, AWS, JPA, Web, Observability)
 *   - model/     : @Entity JPA classes, DTOs, enums, composite keys
 *   - repository/: Spring Data JPA @Repository interfaces
 *   - service/   : @Service business logic (auth, account, card, transaction, billing, etc.)
 *   - controller/: @RestController REST API endpoints
 *   - batch/     : Spring Batch jobs, processors, readers, writers
 *   - exception/ : Custom exception hierarchy (CardDemoException, etc.)
 *   - observability/: Correlation ID filter, metrics, health indicators
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.cardemo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Spring Boot main application class for CardDemo.
 *
 * <p>Analogous to the COBOL COSGN00C.cbl program which served as the
 * application entry point via CICS transaction CC00. The COBOL program
 * initialized the COMMAREA ({@code COCOM01Y.cpy}) and presented the sign-on
 * screen. In this Java migration, Spring Boot handles context initialization,
 * dependency injection, and auto-configuration while authentication is
 * managed by Spring Security.</p>
 *
 * <p>The {@code @EnableAsync} annotation enables asynchronous method execution
 * required for the report submission flow (migrated from CORPT00C.cbl CICS
 * TDQ WRITEQ to SQS message publishing).</p>
 *
 * @see org.springframework.boot.autoconfigure.SpringBootApplication
 * @see org.springframework.scheduling.annotation.EnableAsync
 */
@SpringBootApplication
@EnableAsync
public class CardDemoApplication {

    private static final Logger log = LoggerFactory.getLogger(CardDemoApplication.class);

    /**
     * Application entry point — launches the Spring Boot application context.
     *
     * <p>Replaces the COBOL COSGN00C.cbl {@code MAIN-PARA} procedure division
     * entry point. In COBOL, the program executed {@code EXEC CICS RETURN
     * TRANSID('CC00') COMMAREA(CARDDEMO-COMMAREA)} to establish the
     * pseudo-conversational loop. In Java, {@code SpringApplication.run()}
     * initializes the entire application context including:
     * <ul>
     *   <li>Spring Security filter chain for authentication/authorization</li>
     *   <li>Spring Data JPA repositories for PostgreSQL (replacing VSAM)</li>
     *   <li>Spring Batch infrastructure for batch job execution</li>
     *   <li>AWS S3/SQS/SNS clients for cloud integration</li>
     *   <li>Flyway database migrations on startup</li>
     *   <li>Actuator health checks and metrics endpoints</li>
     *   <li>Structured logging with correlation IDs</li>
     * </ul>
     *
     * @param args command-line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(CardDemoApplication.class, args);
        log.info("CardDemo Application started successfully — "
                + "Credit Card Management System initialized "
                + "(migrated from COBOL CardDemo v1.0-15-g27d6c6f-68)");
    }
}
