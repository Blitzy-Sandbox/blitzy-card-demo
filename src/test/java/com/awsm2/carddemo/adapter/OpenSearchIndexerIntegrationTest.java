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

import org.apache.http.HttpHost;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.Result;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.opensearch.client.RestClient;
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
 * Testcontainers-backed integration coverage for {@link OpenSearchIndexer}.
 *
 * <p><b>// Enables: CloudTrail + application audit-log indexing</b> per
 * AAP &sect;0.6.6. The test exercises the typed OpenSearch client against
 * a real OpenSearch node instead of a Mockito-only transport.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("OpenSearchIndexer — Testcontainers OpenSearch integration")
class OpenSearchIndexerIntegrationTest {

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
    }

    @AfterAll
    static void tearDown() throws IOException {
        if (transport != null) {
            transport.close();
        } else if (restClient != null) {
            restClient.close();
        }
    }

    @Test
    @DisplayName("indexDocument and search round-trip a PCI-safe audit document")
    void indexDocumentAndSearchRoundTripAuditDocument() throws Exception {
        String indexName = "carddemo-audit-it-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> auditDocument = Map.of(
                "eventType", "BATCH_JOB_COMPLETED",
                "jobName", "transactionReportJob",
                "maskedAccountId", "*******1234");

        Result result = indexer.indexDocument(indexName, "doc-1", auditDocument);
        openSearchClient.indices().refresh(builder -> builder.index(indexName));

        List<Map<String, Object>> results = indexer.search(indexName, null, 10);

        assertThat(result).isIn(Result.Created, Result.Updated);
        assertThat(results)
                .anySatisfy(doc -> {
                    assertThat(doc).containsEntry("eventType", "BATCH_JOB_COMPLETED");
                    assertThat(doc).containsEntry("maskedAccountId", "*******1234");
                });
    }
}