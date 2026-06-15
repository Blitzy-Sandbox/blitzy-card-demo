package com.carddemo.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA configuration: entity scanning, repository scanning, and date-only JPA auditing.
 * Re-platforms the CardDemo VSAM datasets (e.g. TRANFILE/XREFFILE, source commit 27d6c6f,
 * REFERENCE ONLY) to PostgreSQL via Spring Data JPA. Owns {@code @EnableJpaAuditing}
 * (intentionally deferred from CardDemoApplication).
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing
@EntityScan("com.carddemo.model.entity")
@EnableJpaRepositories("com.carddemo.repository")
public class JpaConfig {
}
