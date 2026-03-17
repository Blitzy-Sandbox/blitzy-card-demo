/*
 * JpaConfig.java — JPA/Hibernate Infrastructure Configuration
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 *
 * COBOL Source Reference: aws-samples/carddemo commit 27d6c6f
 *
 * This configuration class replaces VSAM DEFINE CLUSTER specifications and VSAM
 * SHAREOPTIONS from the following JCL provisioning jobs:
 *
 *   ACCTFILE.jcl  — Account VSAM KSDS   (KEYS 11,0  RECSIZE 300  SHAREOPTIONS 2,3)
 *   CARDFILE.jcl  — Card VSAM KSDS      (KEYS 16,0  RECSIZE 150  SHAREOPTIONS 2,3)
 *   CUSTFILE.jcl  — Customer VSAM KSDS  (KEYS 9,0   RECSIZE 500  SHAREOPTIONS 2,3)
 *   XREFFILE.jcl  — Cross-Ref VSAM KSDS (KEYS 16,0  RECSIZE 50   SHAREOPTIONS 2,3)
 *   TRANFILE.jcl  — Transaction VSAM KSDS (KEYS 16,0 RECSIZE 350  SHAREOPTIONS 2,3)
 *   DUSRSECJ.jcl  — User Security VSAM  (KEYS 8,0   RECSIZE 80   REUSE)
 *
 * VSAM SHAREOPTIONS(2 3) semantics (multiple readers, single writer) are
 * translated to JPA optimistic locking via @Version on entity classes.
 * VSAM DEFINE CLUSTER KEYS map to @Id / @EmbeddedId on JPA entities.
 * VSAM alternate indexes (TRANFILE AIX, XREFFILE AIX, CARDFILE AIX) map to
 * JPA @Query or derived query methods on repository interfaces.
 *
 * Schema management is handled by Flyway migrations (V1__create_schema.sql,
 * V2__create_indexes.sql, V3__seed_data.sql); Hibernate only validates
 * entity-table alignment (ddl-auto=validate in application.yml).
 *
 * All JPA properties (dialect, batch_size, open-in-view, naming strategy) are
 * configured in application.yml. This class provides:
 *   1. @EnableJpaRepositories — scans com.cardemo.repository for all 11 JPA
 *      repository interfaces (replacing VSAM FILE-CONTROL SELECT/ASSIGN)
 *   2. @EnableJpaAuditing — enables @CreatedDate / @LastModifiedDate on entities
 *      (adding data mutation observability absent from COBOL VSAM)
 *
 * Decision Log References:
 *   D-001: BigDecimal for all COMP-3/COMP fields (entity precision)
 *   D-005: Spring Batch shares the JPA transaction manager
 */
package com.cardemo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA/Hibernate infrastructure configuration for the CardDemo application.
 *
 * <p>This configuration class enables two critical JPA features:
 *
 * <ul>
 *   <li><strong>Repository Scanning</strong> — {@code @EnableJpaRepositories} activates
 *       Spring Data JPA proxy generation for all 11 repository interfaces in the
 *       {@code com.cardemo.repository} package. This replaces the COBOL VSAM
 *       FILE-CONTROL SELECT/ASSIGN statements that bound logical file names
 *       (ACCTDAT, CARDDAT, CUSTDAT, CARDXREF, TRANSACT, USRSEC, TCATBALF,
 *       DISCGRP, TRANTYPE, TRANCATG, DALYTRAN) to physical VSAM cluster
 *       definitions in JCL provisioning jobs.</li>
 *   <li><strong>JPA Auditing</strong> — {@code @EnableJpaAuditing} enables automatic
 *       population of {@code @CreatedDate} and {@code @LastModifiedDate} fields on
 *       entity classes. COBOL VSAM had no equivalent automatic timestamping for
 *       data mutations; this adds observability to every INSERT and UPDATE
 *       operation across all 11 entity tables.</li>
 * </ul>
 *
 * <p><strong>Configuration properties</strong> are externalized in
 * {@code application.yml} and profile-specific overrides:
 * <ul>
 *   <li>{@code spring.jpa.hibernate.ddl-auto=validate} — Flyway manages schema;
 *       Hibernate validates entity mappings only</li>
 *   <li>{@code spring.jpa.open-in-view=false} — OSIV disabled for REST API
 *       best practice (forces explicit fetching in service layer)</li>
 *   <li>{@code spring.jpa.properties.hibernate.jdbc.batch_size=50} — batch
 *       insert optimization mapping from VSAM FREESPACE/CISZ tuning in
 *       DUSRSECJ.jcl (FREESPACE 10,15 / CISZ 8192)</li>
 *   <li>Spring Boot default {@code CamelCaseToUnderscoresNamingStrategy} maps
 *       Java camelCase field names to PostgreSQL snake_case column names,
 *       bridging COBOL field names (ACCT-ID, ACCT-CURR-BAL) through the
 *       Java intermediary</li>
 * </ul>
 *
 * <p><strong>Transaction management</strong> is auto-configured by Spring Boot
 * via {@code JpaTransactionManager}. The same transaction manager is shared with
 * Spring Batch (configured in {@code BatchConfig}), ensuring that batch step
 * commit boundaries align with JPA persistence operations. This replaces the
 * COBOL CICS SYNCPOINT ROLLBACK semantics used in COACTUPC.cbl for dual-dataset
 * (ACCTDAT + CUSTDAT) transactional updates.
 *
 * @see org.springframework.data.jpa.repository.config.EnableJpaRepositories
 * @see org.springframework.data.jpa.repository.config.EnableJpaAuditing
 */
@Configuration
@EnableJpaRepositories(basePackages = "com.cardemo.repository")
@EnableJpaAuditing
public class JpaConfig {
    // All JPA/Hibernate properties are configured externally in application.yml.
    // Spring Boot auto-configures:
    //   - DataSource (HikariCP connection pool from spring.datasource.*)
    //   - EntityManagerFactory (Hibernate 6.x with PostgreSQL dialect)
    //   - JpaTransactionManager (shared with Spring Batch)
    //   - CamelCaseToUnderscoresNamingStrategy (camelCase → snake_case)
    //
    // No additional @Bean definitions are required because:
    //   1. Physical naming strategy (camelCase → snake_case) is the Spring Boot
    //      default and does not need explicit bean registration.
    //   2. Transaction manager auto-configuration covers both JPA and Spring
    //      Batch needs without customization.
    //   3. Open-in-view is disabled via application.yml property, not
    //      programmatic configuration.
    //   4. Hibernate DDL validation mode is set in application.yml, ensuring
    //      Flyway-managed schema integrity.
    //   5. Batch insert settings (batch_size, order_inserts, order_updates)
    //      are configured in application.yml for VSAM FREESPACE/CISZ tuning
    //      equivalence.
}
