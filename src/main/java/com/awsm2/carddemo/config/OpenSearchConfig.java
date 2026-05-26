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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpCoreContext;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Spring {@code @Configuration} wiring the Amazon OpenSearch
 * {@link RestHighLevelClient} per AAP &sect;0.6.6 "Audit, Observability, and
 * PCI-DSS."
 *
 * <p><b>Replaces:</b> COBOL audit trail file writes (sequential PS files and
 * VSAM audit clusters in the source mainframe). Audit events, transaction logs,
 * and CloudTrail event mirrors are now indexed into Amazon OpenSearch via the
 * {@code OpenSearchIndexer} adapter for regulatory queries and fraud
 * investigation (AAP &sect;0.6.6).</p>
 *
 * <h2>Two authentication modes, selected by Spring profile</h2>
 * <ul>
 *   <li><b>local</b> &mdash; HTTP basic auth (or no auth) against
 *       LocalStack-emulated OpenSearch or a plain OpenSearch dev container.</li>
 *   <li><b>dev / prod</b> &mdash; AWS Signature V4 (SigV4) signing via AWS SDK
 *       v2 {@link Aws4Signer} + a custom Apache
 *       {@link HttpRequestInterceptor} (the private static inner class
 *       {@link AwsSigningInterceptor}). IAM credentials are sourced from the
 *       shared {@link AwsCredentialsProvider} bean produced by
 *       {@code AwsSdkConfig}, which itself resolves via the ECS task role on
 *       Fargate (AAP &sect;0.6.6 PCI-DSS IAM controls).</li>
 * </ul>
 *
 * <h2>TLS</h2>
 * <p>Per AAP &sect;0.6.6, all in-transit OpenSearch traffic uses TLS 1.2+
 * (managed Amazon OpenSearch domains enforce HTTPS by default). The
 * {@code carddemo.opensearch.endpoint} property MUST be a full URI including
 * scheme &mdash; for production this is HTTPS; for LocalStack/local dev the URI
 * starts with {@code http://}.</p>
 *
 * <h2>Beans produced</h2>
 * <ul>
 *   <li>{@link #openSearchClient()} &rarr; {@link RestHighLevelClient}
 *       (the primary bean named {@code openSearchClient}). This is the
 *       legacy Elasticsearch-compatible REST high-level client per AAP
 *       &sect;0.5.1 Maven coordinate
 *       {@code org.opensearch.client:opensearch-rest-high-level-client}.</li>
 *   <li>{@link #openSearchTransport(RestHighLevelClient)} &rarr;
 *       {@link OpenSearchTransport} built from the low-level {@link RestClient}
 *       inside the {@link RestHighLevelClient}. Reuses the same configured
 *       HTTP pipeline (timeouts + SigV4 / basic auth interceptors).</li>
 *   <li>{@link #openSearchTypedClient(OpenSearchTransport)} &rarr; modern typed
 *       {@link OpenSearchClient} (from {@code opensearch-java}). Consumed by
 *       {@code OpenSearchIndexer} for compile-time-checked request/response
 *       types. Sharing the underlying {@link RestClient} guarantees a single
 *       connection pool and signing policy across both legacy and typed APIs.</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * <p>The primary bean uses {@code destroyMethod = "close"} so Spring invokes
 * {@link RestHighLevelClient#close()} on context shutdown, releasing the
 * underlying Apache async HTTP client and its connection pool. The transport
 * bean similarly closes during shutdown; the typed client is closed via its
 * transport.</p>
 *
 * <h2>No business logic</h2>
 * <p>Per AAP &sect;0.7.1 ("Adapter pattern &mdash; never inline AWS SDK calls
 * in business logic"), this class is pure infrastructure wiring. The only
 * non-bean code is the {@link AwsSigningInterceptor} inner class, which
 * implements the canonical AWS-published pattern for SigV4-signing OpenSearch
 * REST requests with AWS SDK v2 (no built-in helper exists in the SDK).</p>
 *
 * @see com.awsm2.carddemo.adapter.OpenSearchIndexer
 * @see com.awsm2.carddemo.adapter.AuditLogService
 * @see com.awsm2.carddemo.config.AwsSdkConfig
 */
@Configuration
public class OpenSearchConfig {

    /**
     * Logger used during bean construction to surface configuration choices
     * (endpoint, region, auth mode). Routed through the Logback JSON encoder
     * per AAP &sect;0.6.6 ("structured JSON logging shipped to CloudWatch
     * Logs"). No transaction-level secrets are ever logged.
     */
    private static final Logger LOG = LoggerFactory.getLogger(OpenSearchConfig.class);

    /**
     * Header name required by AWS SigV4 signing for body-content hashing.
     * The OpenSearch service rejects unsigned content-hash claims, so we
     * always add this header after invoking {@link Aws4Signer#sign}.
     */
    private static final String HEADER_X_AMZ_CONTENT_SHA256 = "X-Amz-Content-Sha256";

    /**
     * Headers that are managed by the Apache HTTP client and must NOT be
     * copied from the signed request back to the Apache request, since the
     * Apache client will recompute them and a stale value would cause the
     * server-side signature verification to fail.
     */
    private static final String HEADER_CONTENT_LENGTH = "Content-Length";

    /**
     * OpenSearch domain endpoint URI. MUST include scheme.
     * <ul>
     *   <li>local: {@code http://localhost:4566} (LocalStack) or
     *       {@code http://localhost:9200} (local OpenSearch container)</li>
     *   <li>dev/prod: {@code https://search-carddemo-XXX.us-east-1.es.amazonaws.com}</li>
     * </ul>
     * <p>Property fallback chain: {@code carddemo.opensearch.endpoint}
     * (the agent-prompt-canonical key) &rarr;
     * {@code carddemo.aws.opensearch.endpoint} (the existing
     * {@code application.yml} key) &rarr; {@code OPENSEARCH_ENDPOINT}
     * environment variable per AAP &sect;0.7.2 required env vars &rarr;
     * the {@code http://localhost:9200} default for unconfigured dev
     * containers.</p>
     */
    @Value("${carddemo.opensearch.endpoint:${carddemo.aws.opensearch.endpoint:${OPENSEARCH_ENDPOINT:http://localhost:9200}}}")
    private String endpoint;

    /**
     * AWS region for SigV4 signing. Defaults to {@code ${AWS_REGION}} per
     * AAP &sect;0.7.2 required env vars. Only used when SigV4 mode is active.
     */
    @Value("${carddemo.opensearch.region:${carddemo.aws.region:${AWS_REGION:us-east-1}}}")
    private String region;

    /**
     * OpenSearch service name for SigV4 signing &mdash; {@code "es"} for
     * Amazon OpenSearch Service (managed domains), {@code "aoss"} for
     * Amazon OpenSearch Serverless. Default is {@code "es"} since AAP
     * &sect;0.4.1 references managed OpenSearch for audit storage.
     */
    @Value("${carddemo.opensearch.service-name:es}")
    private String serviceName;

    /**
     * Whether SigV4 signing is enabled.
     * <ul>
     *   <li>{@code true} (dev/prod): SigV4 via IAM credentials from
     *       {@link AwsCredentialsProvider}</li>
     *   <li>{@code false} (local): HTTP basic auth or no-auth against
     *       LocalStack-emulated OpenSearch</li>
     * </ul>
     * <p>Property fallback: also honours the existing
     * {@code carddemo.aws.opensearch.use-iam-auth} for backward
     * compatibility with the prior YAML schema.</p>
     */
    @Value("${carddemo.opensearch.sigv4-enabled:${carddemo.aws.opensearch.use-iam-auth:false}}")
    private boolean sigv4Enabled;

    /**
     * HTTP basic auth username (local profile only). Empty when basic auth
     * is not in use; the no-auth fallback then applies.
     */
    @Value("${carddemo.opensearch.username:}")
    private String username;

    /**
     * HTTP basic auth password (local profile only). Empty when basic auth
     * is not in use.
     */
    @Value("${carddemo.opensearch.password:}")
    private String password;

    /** Connect timeout in milliseconds for the OpenSearch REST client. */
    @Value("${carddemo.opensearch.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    /** Socket read timeout in milliseconds for the OpenSearch REST client. */
    @Value("${carddemo.opensearch.socket-timeout-ms:60000}")
    private int socketTimeoutMs;

    /**
     * AWS credentials provider &mdash; required only when
     * {@link #sigv4Enabled} is {@code true}. Provided lazily via
     * {@link Optional} so the bean can be created in the {@code local}
     * profile even when the {@code AwsSdkConfig} provider bean is absent
     * (Spring 4.3+ Optional bean injection).
     */
    private final Optional<AwsCredentialsProvider> credentialsProvider;

    /**
     * Constructor injection per AAP &sect;0.3.3 Dependency Injection
     * pattern.
     *
     * @param credentialsProvider AWS credentials provider injected from
     *                            {@link AwsSdkConfig#awsCredentialsProvider()}
     *                            &mdash; wrapped in {@link Optional} so this
     *                            config remains valid in the {@code local}
     *                            profile where SigV4 is disabled and the
     *                            provider bean may not be present
     */
    public OpenSearchConfig(Optional<AwsCredentialsProvider> credentialsProvider) {
        // Replaces: RACF identity propagation that authenticated mainframe
        // batch jobs to VSAM datasets. SigV4 + IAM now authenticates the
        // ECS task to OpenSearch on each request.
        this.credentialsProvider = credentialsProvider;
    }

    /**
     * Creates the {@link RestHighLevelClient} configured for the active
     * profile.
     *
     * <p>Construction steps:</p>
     * <ol>
     *   <li>Parse {@link #endpoint} into an Apache {@link HttpHost}</li>
     *   <li>Build a {@link RestClientBuilder} targeting that host</li>
     *   <li>Apply connect / socket timeouts via
     *       {@link RestClientBuilder#setRequestConfigCallback}</li>
     *   <li>Attach an authentication layer:
     *     <ul>
     *       <li>SigV4 via {@link AwsSigningInterceptor} when
     *           {@link #sigv4Enabled} is {@code true}</li>
     *       <li>HTTP basic auth via {@link BasicCredentialsProvider} when
     *           {@link #username} is non-blank</li>
     *       <li>No auth otherwise (acceptable only in local LocalStack mode
     *           where the emulated endpoint accepts unauthenticated requests)</li>
     *     </ul>
     *   </li>
     *   <li>Wrap the builder in {@link RestHighLevelClient}</li>
     * </ol>
     *
     * @return the configured OpenSearch REST high-level client
     * @throws IllegalStateException if {@link #sigv4Enabled} is {@code true}
     *                               but no {@link AwsCredentialsProvider}
     *                               bean is available
     */
    // Suppress AWS SDK v2 deprecation warnings: Aws4Signer and Aws4SignerParams
    // are scheduled for replacement in a future SDK release, but they remain
    // the canonical AWS-published pattern for signing OpenSearch REST requests
    // (per the AWS OpenSearch Service developer guide referenced in
    // AwsSigningInterceptor's javadoc). The agent prompt explicitly names
    // these classes in the external_imports list. This @SuppressWarnings
    // applies to the bean construction site that wires the signer instance.
    @SuppressWarnings("deprecation")
    @Bean(destroyMethod = "close")
    public RestHighLevelClient openSearchClient() {
        // Replaces: COBOL audit trail file writes (sequential PS, GDG) and
        // VSAM audit clusters. All audit events now flow to OpenSearch via
        // this client for regulatory queries and fraud investigation.
        final HttpHost host = parseEndpoint(endpoint);
        final RestClientBuilder builder = RestClient.builder(host);

        // Apply connection / socket timeouts to the underlying Apache async
        // HTTP client.
        builder.setRequestConfigCallback(requestConfigBuilder ->
                requestConfigBuilder
                        .setConnectTimeout(connectTimeoutMs)
                        .setSocketTimeout(socketTimeoutMs));

        if (sigv4Enabled) {
            // dev/prod path: AWS SigV4 signing via IAM (AAP §0.6.6).
            final AwsCredentialsProvider provider = credentialsProvider.orElseThrow(() ->
                    new IllegalStateException(
                            "OpenSearch SigV4 signing enabled (carddemo.opensearch.sigv4-enabled=true) "
                                    + "but no AwsCredentialsProvider bean is available. "
                                    + "Verify AwsSdkConfig is active for the current Spring profile."));
            final Aws4Signer signer = Aws4Signer.create();
            final HttpRequestInterceptor interceptor =
                    new AwsSigningInterceptor(serviceName, Region.of(region), provider, signer);

            builder.setHttpClientConfigCallback(httpClientBuilder ->
                    httpClientBuilder.addInterceptorLast(interceptor));
            LOG.info("OpenSearch REST client configured: host={} mode=SIGV4 service={} region={}",
                    host.toHostString(), serviceName, region);
        } else if (username != null && !username.isBlank()) {
            // local profile with HTTP basic auth (managed OpenSearch with
            // fine-grained access, or dev container with credentials).
            final BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
            credsProvider.setCredentials(
                    AuthScope.ANY,
                    new UsernamePasswordCredentials(username, password));
            builder.setHttpClientConfigCallback(httpClientBuilder ->
                    httpClientBuilder.setDefaultCredentialsProvider(credsProvider));
            LOG.info("OpenSearch REST client configured: host={} mode=BASIC_AUTH",
                    host.toHostString());
        } else {
            // No-auth fallback (LocalStack or plain dev OpenSearch container).
            LOG.info("OpenSearch REST client configured: host={} mode=NO_AUTH",
                    host.toHostString());
        }

        return new RestHighLevelClient(builder);
    }

    /**
     * Builds an {@link OpenSearchTransport} on top of the low-level
     * {@link RestClient} held inside the {@link RestHighLevelClient}.
     *
     * <p>Sharing the underlying {@link RestClient} guarantees a single
     * connection pool and a single SigV4 / basic-auth interceptor pipeline
     * across both the legacy {@link RestHighLevelClient} and the modern
     * typed {@link OpenSearchClient} APIs.</p>
     *
     * <p>The Apache {@link RestClient} is closed by Spring via the
     * {@link RestHighLevelClient}'s {@code destroyMethod="close"}. To avoid
     * double-close, this transport bean does NOT declare its own
     * {@code destroyMethod} &mdash; {@link RestClientTransport#close()}
     * delegates to the same underlying client. Marking this bean's destroy
     * to a no-op is intentional (the transport is a thin wrapper).</p>
     *
     * @param restHighLevelClient the primary client bean produced by
     *                            {@link #openSearchClient()}
     * @return the transport wrapping the same low-level {@link RestClient}
     */
    @Bean
    public OpenSearchTransport openSearchTransport(RestHighLevelClient restHighLevelClient) {
        // Replaces: implicit batch-job-level VSAM dataset binding. The same
        // signed connection serves all OpenSearch-bound operations across
        // both legacy (RestHighLevelClient) and typed (OpenSearchClient) APIs.
        final RestClient lowLevel = restHighLevelClient.getLowLevelClient();
        //
        // BUG #2 fix (QA CP5): Register JavaTimeModule on the ObjectMapper
        // used by the OpenSearch typed client's JSON-P mapper. Without this
        // module, the typed client serializes audit documents containing
        // java.time.LocalDateTime / LocalDate fields (e.g., tranProcTs,
        // postedTs) via Jackson default rules, which raise
        // InvalidDefinitionException: "Java 8 date/time type
        // `java.time.LocalDateTime` not supported by default". Every audit
        // emission from AuditLogService.logTransactionEvent and
        // logBatchJobLifecycle therefore fails, violating the AAP §0.6.5
        // / §0.7.2 audit-trail requirement that OpenSearch indexes
        // transaction logs and lifecycle events for regulatory queries.
        //
        // Disabling WRITE_DATES_AS_TIMESTAMPS additionally ensures the
        // serialized form is an ISO-8601 string (e.g.
        // "2026-05-20T11:30:00") rather than an epoch-millis number,
        // matching the canonical OpenSearch date format and preserving
        // human-readable audit traces.
        final ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new RestClientTransport(lowLevel, new JacksonJsonpMapper(objectMapper));
    }

    /**
     * Modern typed {@link OpenSearchClient}, built on top of the shared
     * {@link OpenSearchTransport}. Consumed by
     * {@code com.awsm2.carddemo.adapter.OpenSearchIndexer} for
     * compile-time-checked request/response types per AAP &sect;0.6.6.
     *
     * <p>The bean name is {@code openSearchTypedClient} (not
     * {@code openSearchClient}) to avoid collision with the primary
     * {@link RestHighLevelClient} bean. Spring type-based injection
     * resolves the {@link OpenSearchClient} dependency for
     * {@code OpenSearchIndexer} without needing the bean name.</p>
     *
     * @param transport the shared {@link OpenSearchTransport}
     * @return the typed OpenSearch client
     */
    @Bean(name = "openSearchTypedClient")
    public OpenSearchClient openSearchTypedClient(OpenSearchTransport transport) {
        return new OpenSearchClient(transport);
    }

    /**
     * Parses the endpoint URI into an Apache {@link HttpHost} for the
     * REST client builder.
     *
     * <p>Scheme resolution:</p>
     * <ul>
     *   <li>If the URI specifies a scheme, it is used as-is</li>
     *   <li>If no scheme is present, defaults to {@code https} (production
     *       safety &mdash; managed OpenSearch is always TLS)</li>
     *   <li>Port defaults: 443 for https, 80 for http if not specified</li>
     * </ul>
     *
     * @param endpointUri the configured endpoint URI string
     * @return the {@link HttpHost} pointing at the OpenSearch domain
     * @throws IllegalArgumentException if the endpoint is not a valid URI
     *                                  or the host component is missing
     */
    private static HttpHost parseEndpoint(String endpointUri) {
        if (endpointUri == null || endpointUri.isBlank()) {
            throw new IllegalArgumentException(
                    "OpenSearch endpoint is null or blank. "
                            + "Set carddemo.opensearch.endpoint or OPENSEARCH_ENDPOINT.");
        }
        try {
            final URI uri = new URI(endpointUri.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || scheme.isBlank()) {
                // Bare hostname form, e.g.,
                // "search-carddemo.us-east-1.es.amazonaws.com" — default to
                // HTTPS for production safety.
                scheme = "https";
                if (host == null || host.isBlank()) {
                    // Fall back to using the raw value as the host when URI
                    // parsing did not identify one (e.g., user supplied
                    // just "localhost:9200" without a scheme).
                    host = stripPortFromBareHost(endpointUri.trim());
                }
            }
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException(
                        "OpenSearch endpoint URI has no host component: " + endpointUri);
            }
            int port = uri.getPort();
            if (port == -1) {
                port = "https".equalsIgnoreCase(scheme) ? 443 : 80;
                // For bare "host:port" without scheme, try to read the port
                // off the raw input.
                if (uri.getScheme() == null) {
                    int barePort = parseBarePort(endpointUri.trim());
                    if (barePort > 0) {
                        port = barePort;
                    }
                }
            }
            return new HttpHost(host, port, scheme);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(
                    "Invalid OpenSearch endpoint URI: " + endpointUri, e);
        }
    }

    /**
     * Strips an optional {@code :port} suffix from a bare host string used
     * when the user supplies an endpoint without a scheme.
     *
     * @param bareHost the bare host string (no scheme)
     * @return the host portion only
     */
    private static String stripPortFromBareHost(String bareHost) {
        final int colon = bareHost.indexOf(':');
        if (colon > 0) {
            return bareHost.substring(0, colon);
        }
        // Slash-stripping (defensive — should not occur given URI parsing).
        final int slash = bareHost.indexOf('/');
        if (slash > 0) {
            return bareHost.substring(0, slash);
        }
        return bareHost;
    }

    /**
     * Extracts a port number from a bare {@code host:port} string when no
     * URI scheme was supplied.
     *
     * @param bareHost the bare host string
     * @return the parsed port, or {@code -1} if none / unparseable
     */
    private static int parseBarePort(String bareHost) {
        final int colon = bareHost.indexOf(':');
        if (colon <= 0 || colon >= bareHost.length() - 1) {
            return -1;
        }
        try {
            int slash = bareHost.indexOf('/', colon + 1);
            final String portStr = slash > 0
                    ? bareHost.substring(colon + 1, slash)
                    : bareHost.substring(colon + 1);
            return Integer.parseInt(portStr);
        } catch (NumberFormatException nfe) {
            return -1;
        }
    }

    /**
     * Apache {@link HttpRequestInterceptor} that signs each outbound
     * OpenSearch request with AWS Signature V4 using AWS SDK v2
     * {@link Aws4Signer}.
     *
     * <p>This is the canonical pattern for connecting the OpenSearch REST
     * client to an IAM-protected managed OpenSearch domain when using
     * AWS SDK v2, which does not ship a built-in OpenSearch HTTP-client
     * signing interceptor &mdash; the implementation here mirrors the
     * AWS-published sample at
     * <a href="https://docs.aws.amazon.com/opensearch-service/latest/developerguide/request-signing.html#request-signing-java">
     * Amazon OpenSearch Service developer guide &mdash; Signing HTTP
     * requests to OpenSearch (Java)</a>.</p>
     *
     * <p>Signing steps (per
     * {@link HttpRequestInterceptor#process(HttpRequest, HttpContext)}):</p>
     * <ol>
     *   <li>Extract the request method, URI, headers, and (if present)
     *       entity body from the Apache {@link HttpRequest}.</li>
     *   <li>Build an AWS SDK v2 {@link SdkHttpFullRequest} that mirrors
     *       the Apache request, including the body as a
     *       {@link ContentStreamProvider}.</li>
     *   <li>Invoke {@link Aws4Signer#sign(SdkHttpFullRequest, Aws4SignerParams)}
     *       with the configured service name ({@code "es"}), region, and
     *       resolved AWS credentials.</li>
     *   <li>Copy the signed headers ({@code Authorization},
     *       {@code X-Amz-Date}, {@code X-Amz-Security-Token},
     *       {@code Host}) back onto the Apache {@link HttpRequest}.</li>
     *   <li>Add the {@code X-Amz-Content-Sha256} header required by
     *       OpenSearch for body-content integrity.</li>
     * </ol>
     *
     * <p>This class is {@code static final} so the JVM can apply standard
     * inlining and the absence of an outer-class reference avoids accidental
     * capture of {@link OpenSearchConfig} state during the per-request hot
     * path.</p>
     */
    @SuppressWarnings("deprecation")
    static final class AwsSigningInterceptor implements HttpRequestInterceptor {

        /**
         * AWS service name for SigV4 signing (e.g., {@code "es"} for
         * managed OpenSearch Service).
         */
        private final String serviceName;

        /** AWS region for SigV4 signing. */
        private final Region region;

        /**
         * AWS credentials provider &mdash; resolved per-request so that
         * rotated ECS task-role credentials apply without restart.
         */
        private final AwsCredentialsProvider credentialsProvider;

        /**
         * SigV4 signer (thread-safe, can be shared across requests). The
         * AWS SDK v2 {@link Aws4Signer} class is deprecated in favour of
         * a newer signer abstraction in upcoming SDK releases, but it
         * remains the canonical pattern named in the agent prompt and the
         * AWS OpenSearch developer guide. The {@code @SuppressWarnings} on
         * the enclosing class scopes the deprecation suppression to this
         * helper.
         */
        private final Aws4Signer signer;

        /**
         * Constructs the interceptor with all required signing state.
         *
         * @param serviceName         AWS service name (e.g., "es")
         * @param region              AWS region for signing
         * @param credentialsProvider AWS credentials provider
         * @param signer              the {@link Aws4Signer} instance
         */
        AwsSigningInterceptor(String serviceName,
                              Region region,
                              AwsCredentialsProvider credentialsProvider,
                              Aws4Signer signer) {
            this.serviceName = serviceName;
            this.region = region;
            this.credentialsProvider = credentialsProvider;
            this.signer = signer;
        }

        /**
         * Signs the outbound Apache {@link HttpRequest} with AWS SigV4 in
         * place. The Apache request is mutated to add the signed
         * {@code Authorization}, {@code X-Amz-Date}, and (if a session
         * token is present) {@code X-Amz-Security-Token} headers, plus the
         * {@code X-Amz-Content-Sha256} header.
         *
         * @param request the outbound Apache HTTP request
         * @param context the HTTP context (used to read the target host)
         * @throws IOException if reading the request body fails
         */
        @Override
        public void process(HttpRequest request, HttpContext context) throws IOException {
            // ----- 1. Extract method + URI from the Apache request -----
            final String method = request.getRequestLine().getMethod();
            final String rawUri = request.getRequestLine().getUri();
            final HttpHost targetHost = resolveTargetHost(context);
            final URI absoluteUri = buildAbsoluteUri(targetHost, rawUri);

            // ----- 2. Extract headers (excluding any that the signer will rewrite) -----
            final Map<String, List<String>> headers = collectHeaders(request);

            // ----- 3. Extract the body, if any -----
            final byte[] body = extractBody(request);

            // ----- 4. Build the SDK request representation -----
            final SdkHttpFullRequest.Builder sdkRequestBuilder = SdkHttpFullRequest.builder()
                    .method(SdkHttpMethod.fromValue(method))
                    .uri(absoluteUri);
            // Set headers individually so we control overwrite semantics.
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                sdkRequestBuilder.putHeader(e.getKey(), e.getValue());
            }
            if (body != null && body.length > 0) {
                sdkRequestBuilder.contentStreamProvider(ContentStreamProvider.fromByteArray(body));
            }
            final SdkHttpFullRequest sdkRequest = sdkRequestBuilder.build();

            // ----- 5. Resolve credentials and build signer params -----
            final AwsCredentials resolved = credentialsProvider.resolveCredentials();
            final Aws4SignerParams params = Aws4SignerParams.builder()
                    .awsCredentials(resolved)
                    .signingName(serviceName)
                    .signingRegion(region)
                    .build();

            // ----- 6. Sign the request -----
            final SdkHttpFullRequest signedRequest = signer.sign(sdkRequest, params);

            // ----- 7. Copy the signed headers back to the Apache request -----
            copySignedHeadersToApacheRequest(signedRequest, request);
        }

        /**
         * Resolves the target {@link HttpHost} from the HTTP context.
         * The OpenSearch REST client populates the {@code HTTP_TARGET_HOST}
         * attribute on the context before invoking interceptors.
         *
         * @param context the HTTP context
         * @return the target host
         * @throws IOException if the target host cannot be resolved
         */
        private static HttpHost resolveTargetHost(HttpContext context) throws IOException {
            final HttpCoreContext coreContext = HttpCoreContext.adapt(context);
            final HttpHost targetHost = coreContext.getTargetHost();
            if (targetHost == null) {
                throw new IOException(
                        "Unable to resolve target host from HttpContext for OpenSearch SigV4 signing.");
            }
            return targetHost;
        }

        /**
         * Builds an absolute URI from a target {@link HttpHost} and a raw
         * request URI (which may be path-only or already absolute).
         *
         * @param targetHost the resolved target host
         * @param rawUri     the request line URI (path or absolute)
         * @return the absolute URI
         * @throws IOException if URI construction fails
         */
        private static URI buildAbsoluteUri(HttpHost targetHost, String rawUri) throws IOException {
            try {
                final URI parsed = new URI(rawUri);
                if (parsed.isAbsolute()) {
                    return parsed;
                }
                final StringBuilder sb = new StringBuilder();
                sb.append(targetHost.getSchemeName())
                        .append("://")
                        .append(targetHost.getHostName());
                if (targetHost.getPort() > 0) {
                    sb.append(':').append(targetHost.getPort());
                }
                if (!rawUri.startsWith("/")) {
                    sb.append('/');
                }
                sb.append(rawUri);
                return new URI(sb.toString());
            } catch (URISyntaxException use) {
                throw new IOException("Failed to build absolute URI for OpenSearch SigV4 signing: "
                        + rawUri, use);
            }
        }

        /**
         * Collects the Apache request headers into a multi-valued map
         * suitable for {@link SdkHttpFullRequest.Builder#putHeader(String, List)}.
         *
         * <p>Headers that the signer recomputes (e.g., {@code Authorization},
         * {@code X-Amz-Date}) are not present here and so are not
         * overwritten when the signed copy is applied.</p>
         *
         * @param request the Apache HTTP request
         * @return mutable, order-preserving multi-valued header map
         */
        private static Map<String, List<String>> collectHeaders(HttpRequest request) {
            final Map<String, List<String>> headers = new LinkedHashMap<>();
            for (Header header : request.getAllHeaders()) {
                final String name = header.getName();
                // Skip Content-Length — the Apache client recomputes it and
                // the SDK signer's value would conflict, breaking the
                // server-side signature check.
                if (HEADER_CONTENT_LENGTH.equalsIgnoreCase(name)) {
                    continue;
                }
                headers.computeIfAbsent(name, k -> new ArrayList<>()).add(header.getValue());
            }
            return headers;
        }

        /**
         * Drains the entity body from an Apache {@link HttpRequest} into a
         * byte array suitable for SigV4 content hashing.
         *
         * <p>For non-entity requests (GET, HEAD, DELETE without body) this
         * returns {@code null}. The entity stream is fully consumed and
         * cached in memory; OpenSearch payloads are typically small JSON
         * bodies (< 1 MB) so in-memory buffering is acceptable. For very
         * large payloads, a streaming-aware signer would be required.</p>
         *
         * @param request the Apache HTTP request
         * @return the body bytes, or {@code null} if the request has no
         *         entity
         * @throws IOException if reading the entity fails
         */
        private static byte[] extractBody(HttpRequest request) throws IOException {
            if (!(request instanceof HttpEntityEnclosingRequest)) {
                return null;
            }
            final HttpEntityEnclosingRequest enclosing = (HttpEntityEnclosingRequest) request;
            final HttpEntity entity = enclosing.getEntity();
            if (entity == null) {
                return null;
            }
            try (InputStream input = entity.getContent();
                 ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                if (input == null) {
                    return null;
                }
                final byte[] chunk = new byte[8192];
                int read;
                while ((read = input.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                }
                final byte[] bodyBytes = buffer.toByteArray();
                // Replace the entity with a repeatable in-memory copy so the
                // Apache client can re-send the body after signing (signing
                // consumed the original stream).
                enclosing.setEntity(new ByteArrayHttpEntity(bodyBytes,
                        entity.getContentType() != null ? entity.getContentType().getValue() : null));
                return bodyBytes;
            }
        }

        /**
         * Copies the SigV4-signed headers from the SDK request back onto
         * the Apache request, plus adds the {@code X-Amz-Content-Sha256}
         * header required by OpenSearch.
         *
         * @param signed        the SDK request after {@link Aws4Signer#sign}
         * @param apacheRequest the Apache request to mutate
         */
        private static void copySignedHeadersToApacheRequest(SdkHttpFullRequest signed,
                                                             HttpRequest apacheRequest) {
            final Map<String, List<String>> signedHeaders = signed.headers();
            for (Map.Entry<String, List<String>> entry : signedHeaders.entrySet()) {
                final String name = entry.getKey();
                // Apache manages Content-Length itself; never overwrite it
                // from the signed copy.
                if (HEADER_CONTENT_LENGTH.equalsIgnoreCase(name)) {
                    continue;
                }
                // Remove any prior value and apply the signed one. Using
                // removeHeaders + addHeader preserves multi-valued semantics.
                apacheRequest.removeHeaders(name);
                for (String value : entry.getValue()) {
                    apacheRequest.addHeader(name, value);
                }
            }
            // Ensure X-Amz-Content-Sha256 is present (signed.headers() also
            // contains it after signing — guard with a case-insensitive
            // lookup so we don't double-set it).
            if (!hasHeaderIgnoreCase(apacheRequest, HEADER_X_AMZ_CONTENT_SHA256)
                    && signedHeaders.keySet().stream()
                    .noneMatch(h -> h.equalsIgnoreCase(HEADER_X_AMZ_CONTENT_SHA256))) {
                // SigV4 always computes the content hash internally; an
                // explicit absence here is exceptional. UNSIGNED-PAYLOAD is
                // the documented fallback for streaming signing modes only
                // and is intentionally NOT used here, since OpenSearch
                // requires a real content hash.
                apacheRequest.addHeader(HEADER_X_AMZ_CONTENT_SHA256, "UNSIGNED-PAYLOAD");
            }
        }

        /**
         * Case-insensitive presence check for an Apache request header.
         *
         * @param request    the Apache HTTP request
         * @param headerName the header name to look for
         * @return {@code true} if the request already carries the header
         */
        private static boolean hasHeaderIgnoreCase(HttpRequest request, String headerName) {
            for (Header h : request.getAllHeaders()) {
                if (headerName.equalsIgnoreCase(h.getName())) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Minimal {@link HttpEntity} implementation that holds a byte array
     * in-memory and exposes it as a repeatable stream. Used by
     * {@link AwsSigningInterceptor#extractBody(HttpRequest)} to replace
     * the original (now-consumed) request entity with a repeatable copy
     * so that the Apache HTTP client can re-send the body after signing.
     */
    private static final class ByteArrayHttpEntity implements HttpEntity {

        /** Bytes backing this entity. */
        private final byte[] content;

        /** Optional content-type header value (e.g., "application/json"). */
        private final String contentTypeValue;

        /**
         * @param content          the entity bytes
         * @param contentTypeValue optional content-type header value, may
         *                         be {@code null}
         */
        ByteArrayHttpEntity(byte[] content, String contentTypeValue) {
            this.content = content;
            this.contentTypeValue = contentTypeValue;
        }

        @Override
        public boolean isRepeatable() {
            return true;
        }

        @Override
        public boolean isChunked() {
            return false;
        }

        @Override
        public long getContentLength() {
            return content.length;
        }

        @Override
        public Header getContentType() {
            if (contentTypeValue == null) {
                return null;
            }
            return new org.apache.http.message.BasicHeader("Content-Type", contentTypeValue);
        }

        @Override
        public Header getContentEncoding() {
            return null;
        }

        @Override
        public InputStream getContent() {
            return new java.io.ByteArrayInputStream(content);
        }

        @Override
        public void writeTo(java.io.OutputStream outStream) throws IOException {
            outStream.write(content);
            outStream.flush();
        }

        @Override
        public boolean isStreaming() {
            return false;
        }

        @Override
        @SuppressWarnings("deprecation")
        public void consumeContent() {
            // No-op: the byte array is in-memory and does not need explicit
            // consumption. Provided for backward compatibility with the
            // deprecated HttpEntity interface contract.
        }

        /**
         * String form for debugging/log output. The body itself is not
         * included to avoid logging payloads that may contain PII per AAP
         * &sect;0.6.6 PCI-DSS discipline.
         *
         * @return a short, payload-free identification of this entity
         */
        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "ByteArrayHttpEntity[length=%d, contentType=%s]",
                    content.length,
                    contentTypeValue == null ? "<none>" : contentTypeValue);
        }
    }
}
