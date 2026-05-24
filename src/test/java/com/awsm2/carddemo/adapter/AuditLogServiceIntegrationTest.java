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
package com.awsm2.carddemo.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.apache.http.HttpHost;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opensearch.client.RestClient;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers-backed integration coverage for {@link AuditLogService}.
 *
 * <p><b>// Replaces: COBOL operational DISPLAY/audit emission</b> with
 * OpenSearch-indexed audit records and Micrometer counters. This test
 * drives the service through the real {@link OpenSearchIndexer} instead
 * of Mockito-only verification.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("AuditLogService — Testcontainers OpenSearch integration")
class AuditLogServiceIntegrationTest {

    private static final DockerImageName OPENSEARCH_IMAGE =
            DockerImageName.parse("opensearchproject/opensearch:2.18.0");

    @Container
    static final GenericContainer<?> OPENSEARCH = new GenericContainer<>(OPENSEARCH_IMAGE)
            .withExposedPorts(9200)
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms256m -Xmx256m")
            .waitingFor(Wait.forHttp("/").forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(3));

    private static RestClient restClient;
    private static OpenSearchTransport transport;
    private static OpenSearchClient openSearchClient;
    private static OpenSearchIndexer indexer;
    private static SimpleMeterRegistry meterRegistry;

    @BeforeAll
    static void setUp() {
        restClient = RestClient.builder(HttpHost.create(
                "http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200)))
                .build();
        transport = new RestClientTransport(
                restClient,
                new JacksonJsonpMapper(new ObjectMapper()));
        openSearchClient = new OpenSearchClient(transport);
        indexer = new OpenSearchIndexer(openSearchClient);
        meterRegistry = new SimpleMeterRegistry();
    }

    @AfterAll
    static void tearDown() throws IOException {
        if (transport != null) {
            transport.close();
        } else if (restClient != null) {
            restClient.close();
        }
        if (meterRegistry != null) {
            meterRegistry.close();
        }
    }

    @Test
    @DisplayName("logAuditEvent indexes a searchable audit document and increments metric")
    void logAuditEventIndexesDocumentAndIncrementsMetric() throws Exception {
        String indexName = "carddemo-audit-service-it-"
                + UUID.randomUUID().toString().substring(0, 8);
        AuditLogService auditLogService = new AuditLogService(indexer, meterRegistry);
        ReflectionTestUtils.setField(auditLogService, "auditIndexName", indexName);

        auditLogService.logAuditEvent(
                "BATCH_JOB_COMPLETED",
                "BATCH_JOB",
                "transactionReportJob",
                "it-operator",
                Map.of("status", "COMPLETED"),
                "corr-audit-it");
        openSearchClient.indices().refresh(builder -> builder.index(indexName));

        List<Map<String, Object>> documents = indexer.search(indexName, null, 10);
        assertThat(documents)
                .anySatisfy(doc -> {
                    assertThat(doc).containsEntry("event_type", "BATCH_JOB_COMPLETED");
                    assertThat(doc).containsEntry("resource_id", "transactionReportJob");
                    assertThat(doc).containsEntry("status", "COMPLETED");
                });
        assertThat(meterRegistry.counter(
                "carddemo.audit.event",
                "event_type",
                "BATCH_JOB_COMPLETED").count()).isEqualTo(1.0d);
    }
}