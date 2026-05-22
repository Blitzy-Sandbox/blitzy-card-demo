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

import com.awsm2.carddemo.exception.CardDemoException;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.Result;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.IndexResponse;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.search.Hit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Amazon OpenSearch indexer adapter for the CardDemo audit, transaction, and
 * compliance pipelines.
 *
 * <p>This adapter is the <strong>only</strong> place in
 * {@code src/main/java/com/awsm2/carddemo/} that touches the OpenSearch client
 * directly. Per AAP &sect;0.7.1 ("Isolate all AWS service integrations in
 * dedicated adapter classes &mdash; never inline AWS SDK calls in business
 * logic"), all OpenSearch interactions must go through the three public
 * methods exposed here: {@link #indexDocument(String, String, Map)},
 * {@link #search(String, Query, Integer)}, and
 * {@link #bulkIndex(String, Map)}.</p>
 *
 * <h2>Replaces (AAP &sect;0.6.6)</h2>
 * <p>Net-new capability &mdash; <em>no direct COBOL equivalent</em>. OpenSearch
 * provides:</p>
 * <ul>
 *   <li>Searchable retention of CloudTrail events for fraud investigation
 *       (replaces JES SYSPRINT inspection and ad-hoc COBOL {@code DISPLAY}
 *       output review).</li>
 *   <li>Indexing of the application audit log (consumed by {@code AuditLogService}).</li>
 *   <li>Indexing of transaction-log records for regulatory queries and
 *       compliance reports (consumed by {@code TransactionReportService}).</li>
 * </ul>
 *
 * <p>Specifically, this adapter replaces the auditable diagnostics emitted by
 * the following batch and online COBOL programs &mdash; whose
 * {@code DISPLAY} statements and {@code SYNCPOINT} before/after image
 * comparisons are now redirected (via {@code AuditLogService}) to OpenSearch
 * indexes:</p>
 * <ul>
 *   <li>{@code app/cbl/CBTRN02C.cbl} &mdash; daily transaction posting:
 *       {@code DISPLAY} of validation failures (line 194 program start,
 *       line 227 transaction counts, line 247/265/284 file open errors), and
 *       {@code WS-VALIDATION-FAIL-REASON} reject codes 100&ndash;109.</li>
 *   <li>{@code app/cbl/COACTUPC.cbl} &mdash; account update: before/after
 *       image comparison auditing surrounding the
 *       {@code SYNCPOINT}/{@code SYNCPOINT ROLLBACK} pair (lines 953 and
 *       4100), now captured as a change-audit document indexed per request.</li>
 *   <li>{@code app/cbl/CBACT04C.cbl} &mdash; interest posting diagnostics:
 *       {@code DISPLAY} of transaction-category balance records (line 193),
 *       program lifecycle markers (lines 181/230), and category/transaction
 *       code traces (lines 207&ndash;209).</li>
 * </ul>
 *
 * <h2>AAP authority</h2>
 * <ul>
 *   <li>AAP &sect;0.3.1 / &sect;0.4.1 &mdash; "OpenSearchIndexer &mdash;
 *       OpenSearch typed Java client for indexing transaction logs and
 *       audit events" (one of eight adapter classes in the target).</li>
 *   <li>AAP &sect;0.6.6 &mdash; Cross-Cutting Audit, Observability, and
 *       PCI-DSS (PRIMARY): "Amazon OpenSearch indexes both CloudTrail events
 *       and application-emitted audit logs &hellip; The
 *       {@code AuditLogService} writes to OpenSearch via the REST client;
 *       failures are buffered to a local SQS DLQ for retry."</li>
 *   <li>AAP &sect;0.5.1 &mdash; dependency:
 *       {@code org.opensearch.client:opensearch-java} 2.18.0 (modern typed
 *       client resolved in {@code pom.xml}).</li>
 *   <li>AAP &sect;0.7.1 &mdash; rule: "Isolate all AWS service integrations
 *       in dedicated adapter classes (S3OutputService, CacheService,
 *       AuditLogService, StepFunctionsOrchestrator, KafkaEventPublisher,
 *       SecretsManagerService, OpenSearchIndexer) &mdash; never inline AWS
 *       SDK calls in business logic."</li>
 *   <li>CP3 checkpoint &mdash; explicitly forbids
 *       {@code RestHighLevelClient} usage; this adapter consumes the typed
 *       {@link OpenSearchClient} produced by
 *       {@code com.awsm2.carddemo.config.OpenSearchConfig}.</li>
 * </ul>
 *
 * <h2>Callers</h2>
 * <ul>
 *   <li>{@code AuditLogService} &mdash; primary consumer of
 *       {@link #indexDocument} for every audit event (transaction posted,
 *       account updated, interest accrued, security events, change-audit
 *       before/after pairs).</li>
 *   <li>{@code TransactionReportService} &mdash; consumer of
 *       {@link #search} for regulatory queries and compliance reports.</li>
 *   <li>{@code StatementGenerationService} and other batch jobs &mdash;
 *       consumer of {@link #bulkIndex} for high-throughput audit emission
 *       during end-of-day pipelines.</li>
 * </ul>
 *
 * <h2>Client choice (AAP &sect;0.5.1, CP3 checkpoint)</h2>
 * <p>This adapter consumes the <strong>modern typed</strong>
 * {@link OpenSearchClient} resolved by {@code pom.xml} as
 * {@code org.opensearch.client:opensearch-java:2.18.0}. The typed client is
 * selected (rather than the legacy
 * {@code org.opensearch.client:opensearch-rest-high-level-client}) to satisfy
 * the CP3 checkpoint requirement, to align with the bean type produced by
 * {@code com.awsm2.carddemo.config.OpenSearchConfig}, and to gain compile-time
 * safety on request/response shapes. The class deliberately avoids any
 * {@code org.elasticsearch.*} imports &mdash; AWS supports the OpenSearch
 * fork only &mdash; and no {@code org.opensearch.action.*},
 * {@code org.opensearch.client.RestHighLevelClient}, or
 * {@code org.opensearch.client.RequestOptions} imports are used.</p>
 *
 * <h2>Transport configuration (AAP &sect;0.6.6, &sect;0.7.2)</h2>
 * <p>The {@link OpenSearchClient} bean is built by
 * {@code com.awsm2.carddemo.config.OpenSearchConfig} on top of
 * {@code org.opensearch.client.transport.aws.AwsSdk2Transport} with:</p>
 * <ul>
 *   <li>Endpoint sourced from the {@code OPENSEARCH_ENDPOINT} environment
 *       variable (per AAP &sect;0.7.2 required environment variables).</li>
 *   <li>TLS 1.2+ enforced (per AAP &sect;0.6.6 "TLS 1.2+ is enforced on the
 *       ALB &hellip; MSK &hellip; RDS &hellip; and ElastiCache").</li>
 *   <li>SigV4 signing via AWS SDK credentials provider in production; HTTP
 *       basic auth in the local profile against the LocalStack stand-in.</li>
 * </ul>
 * <p>This adapter is intentionally transport-agnostic &mdash; it consumes the
 * injected client without configuring it, allowing the same code path to
 * exercise both production and local profiles.</p>
 *
 * <h2>Exception model (AAP &sect;0.7.1)</h2>
 * <p>Two failure classes are wrapped as {@link CardDemoException} with a
 * verbatim reason code so that callers ({@code AuditLogService},
 * {@code TransactionReportService}) need not depend on the OpenSearch client
 * type tree:</p>
 * <ul>
 *   <li>Transport-level {@link IOException}s &mdash; raised by the
 *       {@code AwsSdk2Transport} on network/SigV4 failures.</li>
 *   <li>Service-level {@link OpenSearchException}s &mdash; raised by the
 *       typed client on non-2xx responses from the OpenSearch domain (4xx
 *       client errors, 5xx server errors). Unlike the legacy high-level
 *       REST client, the typed client surfaces these as a single typed
 *       exception type rather than an {@code IOException} subclass.</li>
 * </ul>
 * <p>The reason codes emitted by this adapter are:</p>
 * <ul>
 *   <li>{@code OPENSEARCH_INDEX_ERROR} &mdash; single-document index failed.</li>
 *   <li>{@code OPENSEARCH_SEARCH_ERROR} &mdash; query execution failed.</li>
 *   <li>{@code OPENSEARCH_BULK_INDEX_ERROR} &mdash; bulk index failed.</li>
 * </ul>
 * <p>Argument-level violations ({@code null} or blank index name, missing
 * document ID, empty document body) surface as
 * {@link IllegalArgumentException} to make programming errors fail fast at
 * the boundary &mdash; these are distinct from runtime transport failures
 * which are wrapped as {@link CardDemoException}.</p>
 *
 * <h2>PCI-DSS discipline (AAP &sect;0.6.6, &sect;0.7.2)</h2>
 * <p>This adapter does <strong>not</strong> sanitize document contents; the
 * caller (predominantly {@code AuditLogService}) is responsible for scrubbing
 * PII and primary account numbers before passing the document map. Two
 * defense-in-depth controls run in parallel and do not need to be duplicated
 * here:</p>
 * <ul>
 *   <li>Amazon Macie continuously scans the underlying S3 storage backing the
 *       OpenSearch domain for PII / financial data leakage (AAP &sect;0.6.6).</li>
 *   <li>CloudWatch log filter rules detect plaintext PAN-like sequences in
 *       application logs (AAP &sect;0.6.6).</li>
 * </ul>
 * <p>Log messages emitted by this class deliberately include only operational
 * metadata (index name, doc ID, hit counts, version IDs, exception causes)
 * &mdash; never document field values &mdash; consistent with the AAP
 * &sect;0.6.6 PCI-DSS logging discipline.</p>
 *
 * <h2>Thread safety</h2>
 * <p>This adapter is thread-safe because (a) the injected
 * {@link OpenSearchClient} is itself thread-safe (the underlying
 * {@code AwsSdk2Transport} manages its own connection pool), (b) the adapter
 * carries no mutable state, and (c) every public method creates its own
 * request payload via builder lambdas and does not share local state across
 * invocations. Multiple Spring-managed callers may invoke any method
 * concurrently.</p>
 *
 * @see com.awsm2.carddemo.exception.CardDemoException
 * @see org.opensearch.client.opensearch.OpenSearchClient
 */
// Net-new capability — replaces COBOL DISPLAY + JES SYSPRINT for searchable retention (AAP §0.6.6)
@Component
public class OpenSearchIndexer {

    /**
     * SLF4J logger emitting structured operational log messages with index
     * names, doc IDs, hit counts, version IDs, and exception causes per AAP
     * &sect;0.6.6 PCI-DSS logging discipline (never log secret/PII values; log
     * only operational metadata).
     */
    private static final Logger LOG = LoggerFactory.getLogger(OpenSearchIndexer.class);

    /**
     * Default maximum hits returned when callers pass {@code null} or a
     * non-positive {@code maxHits} argument to {@link #search}. Caps prevent
     * runaway queries from materializing the entire index into application
     * memory during fraud investigation or compliance report generation.
     */
    private static final int DEFAULT_SEARCH_SIZE = 100;

    /**
     * Reason code emitted when a single-document index operation fails.
     * Propagated to callers via {@link CardDemoException#getReasonCode()} so
     * that {@code AuditLogService} can buffer the failed event to its local
     * SQS DLQ for retry per AAP &sect;0.6.6.
     */
    private static final String REASON_CODE_INDEX_ERROR = "OPENSEARCH_INDEX_ERROR";

    /**
     * Reason code emitted when a search query fails. Propagated to callers
     * via {@link CardDemoException#getReasonCode()} so that
     * {@code TransactionReportService} and fraud-investigation tools can
     * present a typed error to operators.
     */
    private static final String REASON_CODE_SEARCH_ERROR = "OPENSEARCH_SEARCH_ERROR";

    /**
     * Reason code emitted when a bulk index operation fails wholesale (i.e.,
     * the transport itself faulted). Partial item-level failures are
     * <em>not</em> elevated to this code &mdash; they are reflected in the
     * return value of {@link #bulkIndex} as the difference between submitted
     * and successfully indexed documents and logged at {@code WARN} level.
     */
    private static final String REASON_CODE_BULK_INDEX_ERROR = "OPENSEARCH_BULK_INDEX_ERROR";

    /**
     * The injected modern typed OpenSearch client. Bean produced by
     * {@code com.awsm2.carddemo.config.OpenSearchConfig} on top of
     * {@code AwsSdk2Transport} with endpoint, TLS, and SigV4 (or HTTP basic
     * auth in local profile) configuration. Held as {@code final} to make
     * the field's thread-safety contract explicit: the reference is set once
     * at construction by Spring's DI container and never reassigned.
     */
    private final OpenSearchClient openSearchClient;

    /**
     * Constructor injection only &mdash; per AAP &sect;0.3.3 Dependency
     * Injection pattern. The {@link OpenSearchClient} bean is supplied by
     * {@code com.awsm2.carddemo.config.OpenSearchConfig}; Spring's container
     * resolves the dependency at startup. {@code final} field assignment
     * guarantees the client reference is immutable after construction.
     *
     * @param openSearchClient the modern typed OpenSearch client bean; must
     *                         not be {@code null}
     * @throws NullPointerException if {@code openSearchClient} is {@code null}
     *                              (defensive check; Spring DI normally
     *                              ensures a non-null instance, but explicit
     *                              validation here protects unit tests and
     *                              ad-hoc instantiations)
     */
    public OpenSearchIndexer(OpenSearchClient openSearchClient) {
        this.openSearchClient = Objects.requireNonNull(openSearchClient,
                "openSearchClient must not be null");
    }

    // ============================================================================
    // Public API
    // ============================================================================

    /**
     * Index a single structured document into the named OpenSearch index.
     *
     * <p>Per AAP &sect;0.6.6 the failure mode is best-effort with retry:
     * transport-level {@link IOException} and service-level
     * {@link OpenSearchException} are wrapped as a typed
     * {@link CardDemoException} so that {@code AuditLogService} can catch it
     * and buffer the failed event to a local SQS dead-letter queue for
     * subsequent retry, without the OpenSearch client type tree leaking into
     * the service layer.</p>
     *
     * <p>The supplied {@code docId} should be deterministic (e.g., transaction
     * ID, audit UUID, or composite key) so that retries are idempotent and a
     * replayed event does not generate a duplicate document. OpenSearch
     * upserts on matching IDs &mdash; the {@link Result} return value
     * distinguishes {@link Result#Created} (new document) from
     * {@link Result#Updated} (overwrite).</p>
     *
     * <p>Document contents are serialized to JSON by the OpenSearch client
     * via the underlying Jackson mapper. Field values may be primitives,
     * strings, dates (via {@code java.time}), or nested {@link Map}s. The
     * caller is responsible for scrubbing PII / primary account numbers from
     * the map per AAP &sect;0.6.6 PCI-DSS discipline; this adapter performs
     * no sanitization.</p>
     *
     * @param indexName target OpenSearch index (e.g., {@code "carddemo-audit"},
     *                  {@code "carddemo-transactions"}). Must not be
     *                  {@code null} or blank.
     * @param docId     deterministic document ID (transaction ID, audit
     *                  UUID, etc.) enabling idempotent retries. Must not be
     *                  {@code null} or blank.
     * @param document  structured fields as a {@code Map<String, Object>}
     *                  (will be JSON-serialized by the client). Must not be
     *                  {@code null} or empty.
     * @return the {@link Result} of the operation
     *         ({@link Result#Created}, {@link Result#Updated},
     *         {@link Result#Deleted}, {@link Result#NoOp}, or
     *         {@link Result#NotFound}). Never {@code null} on successful
     *         return.
     * @throws IllegalArgumentException if any argument is {@code null}, blank,
     *                                  or empty (programmer error, not a
     *                                  transient transport fault)
     * @throws CardDemoException with reason code
     *                           {@code OPENSEARCH_INDEX_ERROR} if the
     *                           OpenSearch transport throws an
     *                           {@link IOException} or the service returns
     *                           an {@link OpenSearchException}
     */
    // Replaces: DISPLAY statements in CBTRN02C, COACTUPC, CBACT04C — now indexed for fraud investigation per AAP §0.6.6
    public Result indexDocument(String indexName,
                                String docId,
                                Map<String, Object> document) {
        // Validate arguments at the boundary so programmer errors fail fast and
        // are distinguishable from transient transport faults.
        if (indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("indexName must not be null or blank");
        }
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("docId must not be null or blank");
        }
        if (document == null || document.isEmpty()) {
            throw new IllegalArgumentException("document must not be null or empty");
        }

        try {
            // Use the typed-client builder lambda form. The .document() setter
            // hands the Map directly to the client's Jackson serializer; the
            // typed client serializes the map to JSON internally — no explicit
            // XContentType is required (legacy API artifact).
            IndexResponse response = openSearchClient.index(i -> i
                    .index(indexName)
                    .id(docId)
                    .document(document));

            Result result = response.result();

            LOG.debug("OpenSearch index OK index={} id={} result={} version={}",
                    indexName, docId, result, response.version());

            return result;
        } catch (IOException | OpenSearchException e) {
            // PCI-DSS-safe logging: include index/id/cause only — never the
            // document body, which may carry PII (AAP §0.6.6).
            LOG.error("OpenSearch index FAILED index={} id={} cause={}",
                    indexName, docId, e.getMessage(), e);

            // Wrap as CardDemoException so callers (AuditLogService) catch and
            // route the failed event to the DLQ without depending on the
            // OpenSearch client type tree (AAP §0.7.1).
            throw new CardDemoException(
                    REASON_CODE_INDEX_ERROR,
                    "Failed to index document into OpenSearch index '" + indexName
                            + "' with docId '" + docId + "'",
                    e);
        }
    }

    /**
     * Execute a search query against the named OpenSearch index and return
     * the matching documents as a list of
     * {@code Map<String, Object>} source maps.
     *
     * <p>Per AAP &sect;0.6.6 OpenSearch indexes both CloudTrail events and
     * application audit logs, supporting fraud investigation, regulatory
     * queries, and compliance reports. This method is the sole entry point
     * for application-side search.</p>
     *
     * <p>The {@code maxHits} parameter caps the result set size for safety:
     * if {@code null} or non-positive, the cap defaults to
     * {@value #DEFAULT_SEARCH_SIZE}. Callers requiring paginated retrieval
     * beyond the cap should use OpenSearch's scroll or point-in-time APIs
     * directly (not yet exposed through this adapter), but the typical
     * fraud-investigation and compliance-report flows operate within the
     * default cap.</p>
     *
     * <p>The {@link Query} argument is the typed-client DSL representation of
     * a query (constructed via builder factories such as
     * {@code Query.of(q -> q.match(...))}, {@code Query.of(q -> q.term(...))},
     * etc.). When {@code null}, the request is sent without a query clause
     * &mdash; OpenSearch returns documents from the index up to the
     * resolved cap.</p>
     *
     * @param indexName index to search. Must not be {@code null} or blank.
     * @param query     OpenSearch DSL {@link Query} (may be {@code null} for
     *                  a match-all behavior &mdash; in that case the request
     *                  omits the {@code query} field and OpenSearch returns
     *                  documents from the index up to {@code maxHits}).
     * @param maxHits   maximum number of hits to return. If {@code null} or
     *                  non-positive, defaults to
     *                  {@value #DEFAULT_SEARCH_SIZE}.
     * @return a {@link List} of source-document maps; never {@code null};
     *         may be empty if no documents match. The list is mutable
     *         (callers may freely modify), but mutation does not affect the
     *         underlying index.
     * @throws IllegalArgumentException if {@code indexName} is {@code null}
     *                                  or blank
     * @throws CardDemoException with reason code
     *                           {@code OPENSEARCH_SEARCH_ERROR} if the
     *                           OpenSearch transport throws an
     *                           {@link IOException} or the service returns
     *                           an {@link OpenSearchException}
     */
    // Enables: regulatory queries, fraud investigation, compliance reports per AAP §0.6.6
    @SuppressWarnings({"rawtypes", "unchecked"})
    public List<Map<String, Object>> search(String indexName,
                                            Query query,
                                            Integer maxHits) {
        if (indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("indexName must not be null or blank");
        }

        // Resolve the requested cap: positive caller value, otherwise the
        // module default. This is a safety cap, not a hard limit — callers
        // requiring larger result sets should use the scroll API.
        final int size = (maxHits != null && maxHits.intValue() > 0)
                ? maxHits.intValue()
                : DEFAULT_SEARCH_SIZE;

        try {
            // Use the typed-client builder lambda form. We parameterize the
            // response type on Map.class — the typed client deserializes
            // each hit's source JSON into a Map<String, Object> via its
            // Jackson mapper. The Class<Map> token resolves the TDocument
            // type parameter to raw Map; the raw-type usage is intentional
            // and covered by the method-level @SuppressWarnings above
            // because Java's reflective Class<T> tokens cannot carry the
            // value type parameter of a generic Map.
            SearchResponse<Map> response = openSearchClient.search(s -> {
                s.index(indexName).size(size);
                if (query != null) {
                    s.query(query);
                }
                return s;
            }, Map.class);

            List<Hit<Map>> hits = response.hits().hits();

            // Materialize the source maps into a mutable, growable list using
            // ArrayList — the external_imports schema explicitly lists this
            // collection type for accumulating search-hit results.
            List<Map<String, Object>> results = new ArrayList<>(hits.size());
            for (Hit<Map> hit : hits) {
                Map<String, Object> source = hit.source();
                if (source != null) {
                    // Preserve insertion order on a per-document basis so
                    // downstream code (audit-log review, report generation)
                    // sees fields in a consistent order. A LinkedHashMap copy
                    // also detaches the returned map from any internal client
                    // state.
                    results.add(new LinkedHashMap<>(source));
                } else {
                    // A hit without a source (rare — fields-only retrieval)
                    // returns an empty map so callers can still iterate the
                    // results collection without null checks.
                    results.add(Collections.emptyMap());
                }
            }

            // The typed client's HitsMetadata.total() may be null if the
            // search response omits the totals block. Guard for null and
            // surface -1 as the "unknown" sentinel to keep log parsing
            // straightforward.
            long totalHits = (response.hits().total() != null)
                    ? response.hits().total().value()
                    : -1L;
            LOG.debug("OpenSearch search OK index={} totalHits={} returned={}",
                    indexName, totalHits, results.size());

            return results;
        } catch (IOException | OpenSearchException e) {
            LOG.error("OpenSearch search FAILED index={} cause={}",
                    indexName, e.getMessage(), e);
            throw new CardDemoException(
                    REASON_CODE_SEARCH_ERROR,
                    "Failed to search OpenSearch index '" + indexName + "'",
                    e);
        }
    }

    /**
     * Bulk-index multiple documents in a single round-trip to OpenSearch.
     *
     * <p>Used by batch jobs ({@code TransactionReportService},
     * {@code StatementGenerationService}) for high-volume audit emission
     * during end-of-day pipelines (per AAP &sect;0.6.3 the end-of-day batch
     * pipeline runs POSTTRAN &rarr; INTCALC &rarr; COMBTRAN &rarr; Parallel{
     * CREASTMT, TRANREPT } and each step emits audit records).</p>
     *
     * <p>Per AAP &sect;0.6.6 partial-failure semantics: this method does
     * <strong>not</strong> elevate item-level failures to a thrown exception
     * because (a) batch jobs need to know how many succeeded so they can
     * retry only the failed subset, and (b) the OpenSearch bulk API returns
     * a structured response with per-item success / error indication. Only a
     * transport-level {@link IOException} or service-level
     * {@link OpenSearchException} (the entire round-trip faulted) surfaces
     * as {@link CardDemoException}. Partial failures are reflected in the
     * {@code int} return value and logged at {@code WARN} level.</p>
     *
     * <p>An empty or {@code null} {@code documents} map is a no-op &mdash;
     * the method returns {@code 0} without contacting OpenSearch. This makes
     * the call safe to invoke from batch processors that may produce zero
     * records for a given chunk.</p>
     *
     * @param indexName target index. Must not be {@code null} or blank.
     * @param documents map of document ID to document body
     *                  ({@code Map<String, Object>}). May be {@code null} or
     *                  empty (returns {@code 0}). Iteration order of the
     *                  outer map drives the order of the bulk operations,
     *                  which OpenSearch preserves within a single bulk
     *                  request.
     * @return the count of successfully indexed documents in the bulk batch.
     *         Always &ge; 0. May be less than {@code documents.size()} if
     *         OpenSearch reports per-item failures (in which case the count
     *         of failures is logged at {@code WARN} level).
     * @throws IllegalArgumentException if {@code indexName} is {@code null}
     *                                  or blank
     * @throws CardDemoException with reason code
     *                           {@code OPENSEARCH_BULK_INDEX_ERROR} if the
     *                           OpenSearch transport throws an
     *                           {@link IOException} or the service returns
     *                           an {@link OpenSearchException}
     */
    // Used by: batch jobs (TransactionReportService, StatementGenerationService) for high-volume audit emission
    public int bulkIndex(String indexName, Map<String, Map<String, Object>> documents) {
        if (indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("indexName must not be null or blank");
        }
        if (documents == null || documents.isEmpty()) {
            // Empty input is a no-op — safe to call from batch processors
            // that may yield zero records for a given chunk.
            return 0;
        }

        // Pre-build the list of typed BulkOperation objects. Each entry is
        // an "index" operation with its own id() and source document. The
        // outer index() (set on the BulkRequest builder below) is not
        // strictly required when each IndexOperation specifies its own
        // index(), but we set it on both to keep the request explicit and
        // resilient to per-entry omissions in callers.
        List<BulkOperation> operations = new ArrayList<>(documents.size());
        for (Map.Entry<String, Map<String, Object>> entry : documents.entrySet()) {
            String id = entry.getKey();
            Map<String, Object> doc = entry.getValue();

            // Skip entries with blank IDs or null/empty document bodies
            // rather than failing the whole bulk: malformed entries are
            // logged and excluded from the count returned to the caller.
            if (id == null || id.isBlank() || doc == null || doc.isEmpty()) {
                LOG.warn("OpenSearch bulk index skipping malformed entry index={} id={}",
                        indexName, id);
                continue;
            }

            // Capture the entry values in effectively-final locals for use
            // inside the nested IndexOperation builder lambda. The typed
            // client's BulkOperation.Builder.index(Function<IndexOperation
            // .Builder<TDocument>, ObjectBuilder<IndexOperation<TDocument>>>)
            // overload accepts a lambda that configures the index operation.
            final String docId = id;
            final Map<String, Object> docBody = doc;

            BulkOperation op = new BulkOperation.Builder()
                    .index(io -> io
                            .index(indexName)
                            .id(docId)
                            .document(docBody))
                    .build();
            operations.add(op);
        }

        // If every entry was malformed, short-circuit without contacting
        // OpenSearch — same observable contract as an empty input.
        if (operations.isEmpty()) {
            LOG.warn("OpenSearch bulk index all entries malformed index={} count=0", indexName);
            return 0;
        }

        try {
            BulkRequest bulkRequest = new BulkRequest.Builder()
                    .index(indexName)
                    .operations(operations)
                    .build();

            BulkResponse bulkResponse = openSearchClient.bulk(bulkRequest);

            // BulkResponse.items() returns a non-null List<BulkResponseItem>
            // per the typed-client contract. Iterate to count successes
            // (item.error() == null) vs failures (item.error() != null).
            List<BulkResponseItem> items = bulkResponse.items();
            int successCount = 0;
            int errorCount = 0;
            for (BulkResponseItem item : items) {
                if (item.error() != null) {
                    errorCount++;
                } else {
                    successCount++;
                }
            }

            if (errorCount > 0 || bulkResponse.errors()) {
                // Surface partial failure at WARN — the caller (batch job)
                // will inspect the return count and decide whether to retry
                // the failed subset. We do not throw here because batch jobs
                // need a numeric success count, not an exception.
                LOG.warn("OpenSearch bulk index partial failure index={} success={} errors={} hasFailures={}",
                        indexName, successCount, errorCount, bulkResponse.errors());
            } else {
                LOG.debug("OpenSearch bulk index OK index={} count={}", indexName, successCount);
            }

            return successCount;
        } catch (IOException | OpenSearchException e) {
            // PCI-DSS-safe logging: include only counts and the exception
            // message — never document bodies (AAP §0.6.6).
            LOG.error("OpenSearch bulk index FAILED index={} count={} cause={}",
                    indexName, documents.size(), e.getMessage(), e);
            throw new CardDemoException(
                    REASON_CODE_BULK_INDEX_ERROR,
                    "Failed to bulk index " + documents.size()
                            + " documents into OpenSearch index '" + indexName + "'",
                    e);
        }
    }
}
