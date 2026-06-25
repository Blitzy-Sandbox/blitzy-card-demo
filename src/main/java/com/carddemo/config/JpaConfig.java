/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.config;

import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * JPA persistence configuration.
 *
 * <p>Enables Spring Data JPA auditing so that entities annotated with
 * {@code @EntityListeners(AuditingEntityListener.class)} can populate the
 * {@code @CreatedBy}/{@code @LastModifiedBy} (and matching {@code @CreatedDate}/
 * {@code @LastModifiedDate}) columns automatically.
 *
 * <p>The relational schema is owned by Flyway and validated by Hibernate
 * ({@code spring.jpa.hibernate.ddl-auto: validate}); the transaction manager and the
 * Spring Data repositories are auto-configured by Spring Boot for the
 * {@code com.carddemo} base package. This class contributes auditing support only.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
public class JpaConfig {

    /**
     * Identity used for audit columns when no end user is authenticated, such as
     * application startup or Spring Batch processing.
     */
    private static final String SYSTEM_AUDITOR = "system";

    /**
     * Supplies the current auditor (user id) for JPA auditing columns.
     *
     * <p>Resolves the authenticated principal from the Spring Security context and
     * returns its name (the JWT subject, i.e. the {@code SEC-USR-ID}). When there is no
     * authenticated principal — application startup, Spring Batch jobs, or anonymous
     * access — it falls back to {@link #SYSTEM_AUDITOR} so that audit columns are always
     * populated and never {@code null}.
     *
     * @return an {@link AuditorAware} that always yields a non-empty auditor value
     */
    @Bean
    public AuditorAware<String> auditorProvider() {
        return () -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null
                    || !authentication.isAuthenticated()
                    || authentication instanceof AnonymousAuthenticationToken) {
                return Optional.of(SYSTEM_AUDITOR);
            }
            return Optional.of(authentication.getName());
        };
    }
}
