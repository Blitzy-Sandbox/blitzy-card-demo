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
package com.carddemo.observability;

import java.sql.Connection;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.ListQueuesRequest;

/**
 * Custom Spring Boot Actuator health indicators for the three backing services
 * (PostgreSQL, AWS S3, AWS SQS), surfaced at {@code /actuator/health}.
 *
 * <p>Each {@code @Bean} method name is the registered health component key once
 * Actuator strips the {@code HealthIndicator} suffix: {@code s3}, {@code sqs},
 * and {@code database}. These keys are a stable contract.
 *
 * <p>Failures are logged in full at WARN for operators, while the HTTP response
 * exposes only a sanitized exception class name — never credentials, endpoints,
 * or connection strings.
 */
@Configuration
public class HealthIndicators {

    private static final Logger log = LoggerFactory.getLogger(HealthIndicators.class);

    @Bean
    public HealthIndicator s3HealthIndicator(S3Client s3Client) {
        return () -> {
            try {
                s3Client.listBuckets();
                return Health.up().withDetail("service", "s3").build();
            } catch (Exception e) {
                log.warn("S3 health check failed", e);
                return Health.down()
                        .withDetail("service", "s3")
                        .withDetail("error", e.getClass().getSimpleName())
                        .build();
            }
        };
    }

    @Bean
    public HealthIndicator sqsHealthIndicator(SqsAsyncClient sqsAsyncClient) {
        return () -> {
            try {
                sqsAsyncClient.listQueues(ListQueuesRequest.builder().maxResults(1).build())
                        .get(2, TimeUnit.SECONDS);
                return Health.up().withDetail("service", "sqs").build();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("SQS health check failed", e);
                return Health.down()
                        .withDetail("service", "sqs")
                        .withDetail("error", e.getClass().getSimpleName())
                        .build();
            } catch (Exception e) {
                log.warn("SQS health check failed", e);
                return Health.down()
                        .withDetail("service", "sqs")
                        .withDetail("error", e.getClass().getSimpleName())
                        .build();
            }
        };
    }

    @Bean
    public HealthIndicator databaseHealthIndicator(DataSource dataSource) {
        return () -> {
            try (Connection connection = dataSource.getConnection()) {
                boolean valid = connection.isValid(2);
                return valid
                        ? Health.up().withDetail("database", "PostgreSQL").build()
                        : Health.down().withDetail("database", "PostgreSQL").build();
            } catch (Exception e) {
                log.warn("Database health check failed", e);
                return Health.down()
                        .withDetail("database", "PostgreSQL")
                        .withDetail("error", e.getClass().getSimpleName())
                        .build();
            }
        };
    }
}
