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
package com.awsm2.carddemo.config;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.aws.AwsSdk2Transport;
import org.opensearch.client.transport.aws.AwsSdk2TransportOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Objects;

/**
 * Wires the typed OpenSearch Java client
 * ({@link org.opensearch.client.opensearch.OpenSearchClient}) used by
 * {@link com.awsm2.carddemo.adapter.OpenSearchIndexer} to index audit
 * events, transaction logs, and CloudTrail events.
 *
 * <p>Replaces (AAP &sect;0.4.1): the legacy
 * {@code org.opensearch.client.RestHighLevelClient}. The CP3 checkpoint
 * mandates the typed client because:</p>
 * <ul>
 *   <li>It is generated from the OpenSearch API specification, so the
 *       request/response types are compile-time-checked.</li>
 *   <li>It supports SigV4-signed requests through
 *       {@link AwsSdk2Transport} without an additional interceptor chain.</li>
 *   <li>The legacy high-level REST client has been deprecated upstream
 *       and is not on the OpenSearch project's long-term support path.</li>
 * </ul>
 *
 * <h2>Wire-up</h2>
 * <p>The client is constructed from three pieces:</p>
 * <ol>
 *   <li>An {@link SdkHttpClient} (the synchronous AWS SDK v2 Apache HTTP
 *       implementation) for TLS-secured HTTPS to the OpenSearch domain
 *       endpoint.</li>
 *   <li>An {@link AwsSdk2TransportOptions} that holds the SigV4 signing
 *       credentials provider (shared with all other AWS SDK calls via
 *       {@link AwsSdkConfig#awsCredentialsProvider()}).</li>
 *   <li>An {@link AwsSdk2Transport} that combines the HTTP client +
 *       signing options + host + region into the
 *       {@link OpenSearchTransport} the typed client consumes.</li>
 * </ol>
 *
 * <p>The host is parsed from {@code carddemo.aws.opensearch.endpoint}:</p>
 * <ul>
 *   <li>{@code https://search-mydomain.us-east-1.es.amazonaws.com} →
 *       host {@code search-mydomain.us-east-1.es.amazonaws.com}</li>
 *   <li>Plain {@code search-mydomain.us-east-1.es.amazonaws.com} → used
 *       as-is</li>
 *   <li>Empty / blank → the {@code @Bean} returns {@code null} via
 *       {@link #openSearchClient(AwsCredentialsProvider)} so the OpenSearch
 *       adapter can stay quiet in local profile.</li>
 * </ul>
 *
 * <h2>SigV4 signing service name</h2>
 * <p>For Amazon OpenSearch Service (managed clusters) the SigV4 service
 * name is {@code "es"}. For Amazon OpenSearch Serverless it is
 * {@code "aoss"}. The default in this config is {@code "es"} (controlled
 * by {@code carddemo.aws.opensearch.signing-service-name}) since AAP
 * &sect;0.4.1 references managed OpenSearch domains for audit storage.</p>
 *
 * <h2>TLS</h2>
 * <p>OpenSearch endpoints are TLS-only (the SDK Apache HTTP client uses
 * the JVM truststore). No additional TLS configuration is required for
 * AWS-managed certificates. Self-signed test endpoints (e.g., a
 * Testcontainers OpenSearch container) require trust-all in test scope
 * only and are handled in {@code application-test.yml} overlays.</p>
 *
 * @see com.awsm2.carddemo.adapter.OpenSearchIndexer
 * @see com.awsm2.carddemo.adapter.AuditLogService
 */
@Configuration
public class OpenSearchConfig {

    private static final Logger LOG = LoggerFactory.getLogger(OpenSearchConfig.class);

    /**
     * Default SigV4 service name for Amazon OpenSearch Service (managed).
     * Override to {@code "aoss"} for OpenSearch Serverless via
     * {@code carddemo.aws.opensearch.signing-service-name}.
     */
    private static final String DEFAULT_SIGNING_SERVICE = "es";

    /** Connection timeout for the Apache HTTP client. */
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(5);
    /** Socket read timeout for the Apache HTTP client. */
    private static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(30);

    private final String endpoint;
    private final String region;
    private final String signingServiceName;

    public OpenSearchConfig(
            @Value("${carddemo.aws.opensearch.endpoint:}") String endpoint,
            @Value("${carddemo.aws.region:us-east-1}") String region,
            @Value("${carddemo.aws.opensearch.signing-service-name:es}") String signingServiceName) {
        // Replaces: legacy RestHighLevelClient transport that did not
        // support SigV4 out-of-the-box — required interceptor wiring.
        this.endpoint = (endpoint == null) ? "" : endpoint.trim();
        this.region = (region == null || region.isBlank()) ? "us-east-1" : region.trim();
        this.signingServiceName = (signingServiceName == null || signingServiceName.isBlank())
                ? DEFAULT_SIGNING_SERVICE
                : signingServiceName.trim();
    }

    /**
     * Synchronous HTTP client used by the OpenSearch transport. Reused
     * across requests via the typed client's internal connection pool.
     *
     * @return the configured Apache HTTP client (sync flavor)
     */
    @Bean(name = "openSearchHttpClient", destroyMethod = "close")
    public SdkHttpClient openSearchHttpClient() {
        return ApacheHttpClient.builder()
                .connectionTimeout(CONNECTION_TIMEOUT)
                .socketTimeout(SOCKET_TIMEOUT)
                .build();
    }

    /**
     * Transport that combines the HTTP client + SigV4 signing options +
     * destination host + region. Returns {@code null} when the endpoint
     * property is blank so local profile doesn't fail startup &mdash;
     * the {@link com.awsm2.carddemo.adapter.OpenSearchIndexer} guards
     * against a null transport / null client.
     *
     * @param httpClient          the SDK HTTP client
     * @param credentialsProvider the shared SigV4 credentials provider
     * @return the OpenSearch transport, or {@code null} when no endpoint
     *         is configured
     */
    @Bean(name = "openSearchTransport", destroyMethod = "close")
    public OpenSearchTransport openSearchTransport(
            SdkHttpClient httpClient,
            AwsCredentialsProvider credentialsProvider) {
        Objects.requireNonNull(httpClient, "httpClient must not be null");
        Objects.requireNonNull(credentialsProvider, "credentialsProvider must not be null");
        if (endpoint.isBlank()) {
            LOG.info("carddemo.aws.opensearch.endpoint is blank; OpenSearch transport disabled");
            return null;
        }
        String host = parseHost(endpoint);
        if (host.isBlank()) {
            LOG.warn("Could not parse OpenSearch host from endpoint='{}'; transport disabled", endpoint);
            return null;
        }
        AwsSdk2TransportOptions options = AwsSdk2TransportOptions.builder()
                .setCredentials(credentialsProvider)
                .build();
        LOG.info("Configuring OpenSearch transport host={} region={} signingService={}",
                host, region, signingServiceName);
        // 5-arg constructor takes (httpClient, host, signingServiceName, region, options)
        return new AwsSdk2Transport(
                httpClient,
                host,
                signingServiceName,
                Region.of(region),
                options);
    }

    /**
     * The typed OpenSearch client. Returns {@code null} when the
     * transport bean is itself null (e.g., local profile with no
     * endpoint configured). The adapter consumer must null-check.
     *
     * @param transport  the OpenSearch transport bean (may be null)
     * @return the typed OpenSearch client, or {@code null}
     */
    @Bean
    public OpenSearchClient openSearchClient(OpenSearchTransport transport) {
        if (transport == null) {
            LOG.info("OpenSearchTransport bean is null; OpenSearchClient will not be created");
            return null;
        }
        return new OpenSearchClient(transport);
    }

    /**
     * Conditionally pass an {@link AwsCredentialsProvider} into
     * {@link #openSearchTransport(SdkHttpClient, AwsCredentialsProvider)};
     * the shared bean from {@link AwsSdkConfig#awsCredentialsProvider()}
     * is the production source of credentials.
     *
     * <p>Note: the bean signature wires the dependency by type; this
     * method is here only as documentation of the wiring intent. The
     * actual provider bean lives in {@code AwsSdkConfig} per AAP
     * &sect;0.3.3 ("Adapter pattern — never inline AWS SDK calls in
     * business logic").</p>
     */

    /**
     * Parses the host portion of an OpenSearch endpoint URL.
     * Accepts either a fully qualified URL (https://&hellip;) or a bare
     * hostname.
     *
     * @param endpointValue the configured endpoint
     * @return the host name (no scheme, no port, no path)
     */
    static String parseHost(String endpointValue) {
        if (endpointValue == null || endpointValue.isBlank()) {
            return "";
        }
        String trimmed = endpointValue.trim();
        // If it does not start with a scheme, treat the value as a bare host.
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            // Strip any trailing path / port
            int slash = trimmed.indexOf('/');
            if (slash > 0) {
                trimmed = trimmed.substring(0, slash);
            }
            return trimmed;
        }
        try {
            URI uri = new URI(trimmed);
            String host = uri.getHost();
            return (host == null) ? "" : host;
        } catch (URISyntaxException e) {
            LOG.warn("Invalid OpenSearch endpoint URI '{}' cause={}", endpointValue, e.getMessage());
            return "";
        }
    }
}
