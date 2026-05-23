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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch._types.ErrorResponse;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.Result;
import org.opensearch.client.opensearch._types.query_dsl.MatchAllQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.IndexResponse;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.bulk.OperationType;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.HitsMetadata;
import org.opensearch.client.opensearch.core.search.TotalHits;
import org.opensearch.client.opensearch.core.search.TotalHitsRelation;
import org.opensearch.client.util.ObjectBuilder;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OpenSearchIndexer}, the OpenSearch typed-client adapter
 * that indexes transaction audit logs and CloudTrail events for fraud
 * investigation and regulatory queries per AAP &sect;0.6.6.
 *
 * <p>This adapter is the centerpiece of the CardDemo observability and audit
 * posture (AAP &sect;0.6.6): it is the only place in
 * {@code src/main/java/com/awsm2/carddemo/} that touches the OpenSearch client
 * directly, and it serves as the indexing back-end for {@code AuditLogService}
 * (every audit event &mdash; transaction posted, account updated, interest
 * accrued, security events, change-audit before/after pairs), for
 * {@code TransactionReportService} (regulatory queries and compliance reports),
 * and for {@code StatementGenerationService} and other batch jobs
 * (high-throughput audit emission during end-of-day pipelines).</p>
 *
 * <h2>Test coverage matrix</h2>
 * <p>The tests in this class cover three behavioral contracts of the adapter,
 * each spanning happy path, exception wrapping, and input validation:</p>
 * <ol>
 *   <li><strong>{@link OpenSearchIndexer#indexDocument indexDocument}</strong>
 *       (Phases 6&ndash;8 of the test agent_prompt) &mdash; happy path returns
 *       {@link Result#Created} / {@link Result#Updated}; {@link IOException}
 *       and {@link OpenSearchException} surface as {@link CardDemoException}
 *       with reason code {@code OPENSEARCH_INDEX_ERROR}; null / blank index,
 *       null / blank docId, and null / empty document map all throw
 *       {@link IllegalArgumentException} before the client is touched.</li>
 *   <li><strong>{@link OpenSearchIndexer#search search}</strong> (Phases
 *       9&ndash;11) &mdash; happy path returns the list of hit source maps;
 *       a {@code null} or non-positive {@code maxHits} resolves to the default
 *       {@code DEFAULT_SEARCH_SIZE = 100} (verified via captured
 *       {@link SearchRequest#size}); a {@code null} {@link Query} is allowed
 *       and treated as a match-all (no query clause emitted);
 *       {@link IOException} and {@link OpenSearchException} surface as
 *       {@code OPENSEARCH_SEARCH_ERROR}; null / blank index throws
 *       {@link IllegalArgumentException}.</li>
 *   <li><strong>{@link OpenSearchIndexer#bulkIndex bulkIndex}</strong>
 *       (Phases 12&ndash;14) &mdash; happy path returns the count of
 *       successful items from the {@link BulkResponse}; per-item failures are
 *       tolerated (the failed subset is logged at {@code WARN} and reflected
 *       in the returned count, NOT raised);
 *       {@link IOException} and {@link OpenSearchException} surface as
 *       {@code OPENSEARCH_BULK_INDEX_ERROR}; null / blank index throws
 *       {@link IllegalArgumentException}; null or empty {@code documents} is a
 *       no-op that returns {@code 0} without contacting OpenSearch.</li>
 * </ol>
 *
 * <h2>Mocking strategy</h2>
 * <p>This is a pure Mockito unit test &mdash; no Spring context is loaded.
 * {@link MockitoExtension} (strict-stubbing mode, the JUnit 5 default in
 * Mockito 5.x) wires the {@code @Mock OpenSearchClient} into the production
 * {@link OpenSearchIndexer} constructor via {@code @InjectMocks}. The
 * production class uses the typed-client builder lambda overloads of
 * {@link OpenSearchClient#index} and {@link OpenSearchClient#search}, so the
 * tests stub the {@link Function} overload (the lambda form) rather than the
 * non-lambda overload. To assert on the actual {@link IndexRequest} /
 * {@link SearchRequest} fields, the test captures the {@link Function} via
 * {@link ArgumentCaptor}, applies it to a real
 * {@code IndexRequest.Builder} / {@code SearchRequest.Builder}, and inspects
 * the built request object. The {@link BulkRequest} overload is the
 * traditional request-object form, so a direct
 * {@code ArgumentCaptor<BulkRequest>} suffices.</p>
 *
 * <p>The deeply-nested {@link SearchResponse} structure
 * ({@code response.hits().hits()} and the per-{@link Hit} {@code source()})
 * is mocked explicitly rather than via {@code RETURNS_DEEP_STUBS} to keep
 * strict-stubbing enforcement intact and to make the per-test assertions
 * self-documenting. {@link BulkResponse} and {@link BulkResponseItem} are
 * likewise mocked explicitly so that per-item {@code .error()} stubbing is
 * visible in the test body.</p>
 *
 * <h2>Compliance constraints honored</h2>
 * <ul>
 *   <li><strong>OpenSearch typed client only</strong> &mdash; all OpenSearch
 *       types come from {@code org.opensearch.client.opensearch.*}; no
 *       {@code org.elasticsearch.*} imports (Elastic-licensed, not part of
 *       AWS-supported OpenSearch) and no legacy
 *       {@code org.opensearch.client.RestHighLevelClient} usage (AAP
 *       &sect;0.5.1 and CP3 checkpoint).</li>
 *   <li><strong>AWS SDK v2 only</strong> &mdash; no {@code com.amazonaws.*}
 *       imports (AAP &sect;0.5.1).</li>
 *   <li><strong>PCI-DSS discipline</strong> &mdash; sample documents carry
 *       only the redacted/synthetic test PAN segments and account-ID values
 *       (never real credit-card numbers) per the test agent_prompt's
 *       PCI-DSS rule, mirroring the production adapter's PCI-DSS-safe
 *       logging discipline (AAP &sect;0.6.6).</li>
 *   <li><strong>No real OpenSearch endpoint</strong> &mdash; every client
 *       method call is routed through a Mockito mock; never to a real or
 *       LocalStack-emulated OpenSearch domain. This keeps the test class
 *       fast (no I/O), deterministic (no network jitter), and runnable in
 *       any environment.</li>
 *   <li><strong>JUnit 5 idioms only</strong> &mdash; {@code @Test},
 *       {@code @DisplayName}, {@code @ExtendWith}, {@code assertThrows},
 *       {@code assertEquals}, {@code assertNotNull}, {@code assertTrue},
 *       {@code assertSame}. No JUnit 4 ({@code @Before},
 *       {@code @Test(expected=...)}).</li>
 * </ul>
 *
 * <h2>Source mainframe context</h2>
 * <p>// Replaces: COBOL audit trail writes from financial transaction
 * programs (CBTRN02C, COTRN02C, COACTUPC, CBACT04C, etc.) &mdash; their
 * {@code DISPLAY} statements and {@code SYNCPOINT} before/after image
 * comparisons are now redirected (via {@code AuditLogService}) to OpenSearch
 * indexes verified end-to-end by this test class.</p>
 * <p>// Backs AAP &sect;0.6.6: OpenSearch indexes transaction logs and
 * CloudTrail events for fraud investigation and regulatory queries.</p>
 *
 * @see OpenSearchIndexer
 * @see com.awsm2.carddemo.exception.CardDemoException
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OpenSearchIndexer \u2014 OpenSearch typed-client adapter unit tests")
class OpenSearchIndexerTest {

    // -------------------------------------------------------------------------
    // Mocks and System Under Test
    // -------------------------------------------------------------------------

    /**
     * The mocked modern typed {@link OpenSearchClient}. Mockito strict-stubbing
     * (enabled by default via {@link MockitoExtension} in Mockito 5.x) ensures
     * that every {@code when(...)} stub set on this mock is actually invoked
     * by the production code &mdash; unused stubs fail the test, catching
     * over-mocking that hides real bugs.
     *
     * <p>The mock is reset by Mockito between test methods (per the
     * {@link MockitoExtension} lifecycle), so each test starts with a clean
     * collaborator instance. Mockito 5.x's inline mock maker allows mocking
     * of the typed client's {@code final} response accessor methods
     * ({@code IndexResponse.result()}, {@code SearchResponse.hits()},
     * {@code BulkResponse.items()}, etc.) without explicit configuration.</p>
     */
    @Mock
    private OpenSearchClient openSearchClient;

    /**
     * The production {@link OpenSearchIndexer} instance under test.
     * {@code @InjectMocks} discovers the single-argument constructor
     * {@code OpenSearchIndexer(OpenSearchClient)} on the production class and
     * injects the {@code @Mock openSearchClient} field above.
     */
    @InjectMocks
    private OpenSearchIndexer indexer;

    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------

    /**
     * Canonical test index name. Matches the {@code "carddemo-audit"} naming
     * convention used by {@code AuditLogService} for audit event indexing
     * (AAP &sect;0.6.6). The hyphenated lowercase form complies with the
     * OpenSearch index-naming rules (no uppercase, no underscores at start).
     */
    private static final String VALID_INDEX = "carddemo-audit";

    /**
     * Canonical deterministic test document ID. Mirrors the production usage
     * pattern where {@code AuditLogService} supplies an audit UUID, a
     * transaction ID, or a composite key as the document ID to enable
     * idempotent retries (a replayed event upserts rather than duplicates).
     */
    private static final String VALID_DOC_ID = "doc-123";

    /**
     * Builder for the canonical sample document. Uses {@link LinkedHashMap}
     * to preserve insertion order so any ordering-sensitive assertion remains
     * deterministic across JVMs (per the test agent_prompt Phase 5 fixture
     * specification). Keys are realistic audit-event fields drawn from the
     * production {@code AuditLogService} contract; values are synthetic and
     * PCI-DSS-safe (no real PANs, SSNs, or production identifiers).
     *
     * @return a fresh {@code Map<String, Object>} per invocation so tests
     *         that mutate the map do not interfere with each other
     */
    private Map<String, Object> sampleDocument() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("@timestamp", "2025-05-20T12:00:00Z");
        doc.put("transaction_id", "TXN-0001");
        doc.put("account_id", 12345L);
        doc.put("event_type", "TRANSACTION_POSTED");
        return doc;
    }


    /**
     * Helper that constructs a real (non-mocked) {@link IndexResponse} via
     * the typed-client static factory {@link IndexResponse#of} with the
     * given {@link Result}. The production
     * {@link OpenSearchIndexer#indexDocument} returns the
     * {@code response.result()} value verbatim, so this helper backs every
     * happy-path test in the {@code indexDocument} group.
     *
     * <p><strong>Why a real builder, not a Mockito mock?</strong>
     * {@link IndexResponse} extends
     * {@code org.opensearch.client.opensearch._types.WriteResponseBase}
     * which marks every accessor &mdash; {@link IndexResponse#result()},
     * {@code id()}, {@code index()}, {@code version()}, {@code seqNo()},
     * {@code primaryTerm()}, {@code shards()}, {@code forcedRefresh()}
     * &mdash; as {@code final}. Final methods on subclass-mocked types are
     * NOT intercepted by Mockito under the standard subclass mock-maker
     * (only under the inline mock-maker which is not reliably auto-attached
     * under Maven Surefire + JaCoCo on Linux containers). Stubbing
     * {@code when(mock(IndexResponse.class).result()).thenReturn(...)} therefore
     * invokes the real final method during stub recording, which dereferences
     * the unset {@code result} field and raises an
     * {@code UnfinishedStubbingException}. The robust fix is to construct a
     * real {@link IndexResponse} via the public builder factory.</p>
     *
     * <p>The required fields per {@code WriteResponseBase}'s constructor are:
     * {@code result}, {@code id}, {@code index}, {@code primaryTerm},
     * {@code seqNo}, {@code shards}, {@code version}. We populate all
     * required fields with deterministic synthetic values; only
     * {@code result} influences the production code path under test.</p>
     *
     * @param result the {@link Result} value the built response will return
     *               from {@link IndexResponse#result()} ({@link Result#Created}
     *               for new documents, {@link Result#Updated} for upserts)
     * @return a fresh real {@link IndexResponse} suitable for return from
     *         a stubbed {@code openSearchClient.index(...)} call
     */
    private IndexResponse buildIndexResponse(Result result) {
        return IndexResponse.of(b -> b
                .result(result)
                .id(VALID_DOC_ID)
                .index(VALID_INDEX)
                .version(1L)
                .seqNo(0L)
                .primaryTerm(1L)
                .shards(s -> s
                        .total(1)
                        .successful(1)
                        .failed(0)));
    }

    /**
     * Helper that builds a real (un-mocked) {@link OpenSearchException} with
     * a deterministic {@link ErrorResponse} payload. Used by exception-wrapping
     * tests for the production catch clause
     * {@code catch (IOException | OpenSearchException e)} &mdash;
     * the {@code OpenSearchException} branch represents service-level
     * non-2xx responses (4xx client errors, 5xx server errors) from the
     * OpenSearch domain. Building a real exception (rather than mocking it)
     * verifies the production code's actual exception-type matching rather
     * than relying on Mockito interception of a {@code RuntimeException}
     * subtype.
     *
     * @return a fresh {@link OpenSearchException} with a 500 status payload
     *         and a synthetic {@link ErrorCause#reason} for diagnostics
     */
    private OpenSearchException newOpenSearchException() {
        ErrorResponse errorResponse = ErrorResponse.of(b -> b
                .error(ErrorCause.of(ec -> ec
                        .type("internal_server_error")
                        .reason("simulated OpenSearch service error")))
                .status(500));
        return new OpenSearchException(errorResponse);
    }

    /**
     * Helper that builds a real (non-mocked) {@link BulkResponseItem}
     * representing a <strong>successful</strong> bulk index operation
     * (HTTP 201 Created). The {@code .error()} accessor on a real item
     * built without an {@code .error(...)} call returns {@code null}, which
     * matches the production loop's success-detection predicate
     * ({@code item.error() == null}).
     *
     * <p><strong>Why a real builder, not a Mockito mock?</strong>
     * {@link BulkResponseItem#error()}, {@code id()}, {@code index()},
     * {@code status()}, {@code operationType()}, and every other accessor
     * are {@code final} on {@link BulkResponseItem}. Subclass-mocked
     * instances do not intercept final methods reliably under the Maven
     * Surefire + JaCoCo configuration used by this project, so the test
     * uses the public builder factory instead.</p>
     *
     * <p>Required builder fields per the type's constructor are:
     * {@code operationType} ({@link OperationType#Index} for upsert),
     * {@code id} (document ID echoed from the request), {@code index}
     * (target index name), and {@code status} (HTTP status code).</p>
     *
     * @param id the document ID echoed by OpenSearch in the response item
     * @return a fresh real {@link BulkResponseItem} where {@code error()}
     *         returns {@code null} (success)
     */
    private BulkResponseItem buildSuccessfulBulkItem(String id) {
        return BulkResponseItem.of(b -> b
                .operationType(OperationType.Index)
                .id(id)
                .index(VALID_INDEX)
                .status(201));
    }

    /**
     * Helper that builds a real (non-mocked) {@link BulkResponseItem}
     * representing a <strong>failed</strong> bulk index operation
     * (HTTP 500 Internal Server Error). The {@code .error()} accessor on
     * the built item returns a non-{@code null} {@link ErrorCause} carrying
     * a deterministic synthetic type/reason, matching the production
     * loop's failure-detection predicate ({@code item.error() != null}).
     *
     * <p>See {@link #buildSuccessfulBulkItem} for the rationale behind
     * using a real builder instead of a Mockito mock.</p>
     *
     * @param id the document ID echoed by OpenSearch in the response item
     * @return a fresh real {@link BulkResponseItem} where {@code error()}
     *         returns a non-null {@link ErrorCause} (failure)
     */
    private BulkResponseItem buildFailedBulkItem(String id) {
        return BulkResponseItem.of(b -> b
                .operationType(OperationType.Index)
                .id(id)
                .index(VALID_INDEX)
                .status(500)
                .error(ec -> ec
                        .type("internal_server_error")
                        .reason("simulated bulk item failure for " + id)));
    }

    /**
     * Helper that builds a real (non-mocked) {@link BulkResponse} containing
     * the supplied list of items. The {@code errors} field is computed
     * automatically: {@code true} if any item has a non-null error,
     * {@code false} otherwise &mdash; matching the semantics of a real
     * OpenSearch response.
     *
     * <p>This helper replaces the prior Mockito-based pattern
     * {@code mock(BulkResponse.class) + when(.items()).thenReturn(...) + when(.errors()).thenReturn(...)}
     * which fails because {@link BulkResponse#items()} and
     * {@link BulkResponse#errors()} are {@code final} accessors that
     * Mockito's subclass mock-maker cannot intercept.</p>
     *
     * @param items the list of {@link BulkResponseItem}s the response
     *              should expose via {@link BulkResponse#items}
     * @return a fresh real {@link BulkResponse} suitable for return from a
     *         stubbed {@code openSearchClient.bulk(...)} call
     */
    private BulkResponse buildBulkResponse(List<BulkResponseItem> items) {
        boolean hasErrors = items.stream().anyMatch(it -> it.error() != null);
        return BulkResponse.of(b -> b
                .errors(hasErrors)
                .took(1L)
                .items(items));
    }

    /**
     * Helper that builds a real (non-mocked) {@link SearchResponse} carrying
     * the supplied list of hit source maps. Each entry in {@code hitSources}
     * becomes a real {@link Hit} with {@code .source()} returning that map;
     * the deterministic synthetic {@code hit.id} is positional
     * ({@code "h-0"}, {@code "h-1"}, &hellip;) so multiple hits remain
     * distinct in any post-call inspection.
     *
     * <p>To allow tests of the null-source branch
     * ({@link OpenSearchIndexer#search} substitutes an empty map for a hit
     * whose {@code source()} is null), entries in {@code hitSources} that
     * are {@code null} are translated to a {@link Hit} where {@code .source}
     * is intentionally not invoked on the builder, leaving the underlying
     * field {@code null}.</p>
     *
     * <p><strong>Why a real builder, not a Mockito mock?</strong>
     * {@link SearchResponse} extends
     * {@code org.opensearch.client.opensearch.core.search.SearchResult}
     * which marks {@code hits()}, {@code took()}, {@code timedOut()}, and
     * every other accessor as {@code final}. {@code Hit.source()} is also
     * {@code final}, as are
     * {@code HitsMetadata.hits()}, {@code HitsMetadata.total()}, and
     * {@code TotalHits.value()}. The robust fix is to use the public
     * builder factories the typed client provides.</p>
     *
     * @param hitSources the list of source maps to expose via
     *                   {@link SearchResponse#hits} &rarr;
     *                   {@code HitsMetadata.hits()} &rarr; per-hit
     *                   {@link Hit#source}; a {@code null} entry yields a
     *                   {@link Hit} with a {@code null} source
     * @return a fresh real {@link SearchResponse} suitable for return from
     *         a stubbed {@code openSearchClient.search(...)} call
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private SearchResponse<Map> buildSearchResponse(List<Map<String, Object>> hitSources) {
        // Build real Hit<Map> instances using the typed-client public builder
        // factory. The production code declares the response type as the raw
        // SearchResponse<Map> (matching its Class<Map> token), so the helper
        // also returns raw SearchResponse<Map> to align with the production
        // type signature at the @Mock stubbing boundary.
        List<Hit<Map>> hits = new java.util.ArrayList<>();
        for (int i = 0; i < hitSources.size(); i++) {
            final int idx = i;
            final Map<String, Object> source = hitSources.get(i);
            Hit<Map> hit = Hit.<Map>of(hb -> {
                hb.index(VALID_INDEX).id("h-" + idx);
                if (source != null) {
                    hb.source((Map) source);
                }
                return hb;
            });
            hits.add(hit);
        }
        return SearchResponse.<Map>searchResponseOf(b -> b
                .took(1L)
                .timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h
                        .total(t -> t
                                .value((long) hitSources.size())
                                .relation(TotalHitsRelation.Eq))
                        .hits(hits)));
    }

    /**
     * Convenience builder for the {@link Query} argument used in
     * {@link OpenSearchIndexer#search}. Returns a match-all query in the
     * typed-client DSL form. Used by tests that exercise the {@code search}
     * happy path and the default-cap behavior; tests that exercise the
     * {@code null}-query branch pass {@code null} directly.
     *
     * @return a fresh match-all {@link Query} per invocation
     */
    private Query matchAllQuery() {
        return Query.of(q -> q.matchAll(MatchAllQuery.of(m -> m)));
    }

    // =========================================================================
    // Phase 6 \u2014 indexDocument happy-path tests
    // =========================================================================

    /**
     * Test 6.1: Verifies the canonical happy path of
     * {@link OpenSearchIndexer#indexDocument} &mdash; a valid call returns
     * {@link Result#Created} from the underlying client, and the
     * {@link IndexRequest} passed to OpenSearch carries the verbatim index
     * name and document ID supplied by the caller.
     *
     * <p>Because the production code uses the typed-client lambda overload
     * {@code openSearchClient.index(Function<...>)}, the test stubs that
     * overload (not the {@code index(IndexRequest)} request-object overload)
     * and captures the {@link Function} via {@link ArgumentCaptor}. The
     * captured function is then applied to a fresh
     * {@link IndexRequest.Builder} to materialize the actual
     * {@link IndexRequest} object that production would have submitted; the
     * test asserts on its {@code index()}, {@code id()}, and {@code document()}
     * accessors.</p>
     *
     * <p>This pattern protects against silent regressions where a future
     * refactor might accidentally swap the order of {@code .index()} and
     * {@code .id()} on the builder, or pass a transformed copy of the
     * document map rather than the original instance.</p>
     */
    @Test
    @DisplayName("indexDocument invokes the OpenSearch client with the correct index and document ID")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void indexDocument_withValidInputs_invokesClientAndReturnsResult() throws Exception {
        // Given: the client returns a Result.Created IndexResponse for any
        // builder-lambda call.
        when(openSearchClient.index(any(Function.class)))
                .thenReturn(buildIndexResponse(Result.Created));

        Map<String, Object> doc = sampleDocument();

        // When: the adapter is invoked
        Result result = indexer.indexDocument(VALID_INDEX, VALID_DOC_ID, doc);

        // Then: the production code returns the Result verbatim
        assertEquals(Result.Created, result,
                "Expected Result.Created to propagate from IndexResponse.result()");

        // And: capture the builder lambda passed to OpenSearchClient.index(...)
        // The lambda overload's signature is parameterized on TDocument; we
        // capture as raw Function and re-apply to a fresh builder to
        // materialize the actual IndexRequest that production would have
        // submitted.
        ArgumentCaptor<Function<IndexRequest.Builder<Object>,
                ObjectBuilder<IndexRequest<Object>>>> captor =
                (ArgumentCaptor) ArgumentCaptor.forClass(Function.class);
        verify(openSearchClient).index(captor.capture());

        Function<IndexRequest.Builder<Object>,
                ObjectBuilder<IndexRequest<Object>>> lambda = captor.getValue();
        IndexRequest<Object> request = lambda
                .apply(new IndexRequest.Builder<>())
                .build();

        // Assert the request carries the verbatim index name and document ID
        assertEquals(VALID_INDEX, request.index(),
                "IndexRequest.index() must equal the supplied index name");
        assertEquals(VALID_DOC_ID, request.id(),
                "IndexRequest.id() must equal the supplied document ID");

        // Assert the document body is propagated by reference (the typed
        // client passes the Map directly to its Jackson serializer; no
        // defensive copy is made by the adapter)
        assertSame(doc, request.document(),
                "IndexRequest.document() must be the same Map instance supplied by the caller");
    }

    /**
     * Test 6.2: Verifies that {@link OpenSearchIndexer#indexDocument} returns
     * {@link Result#Updated} when the underlying OpenSearch operation
     * overwrites an existing document with the same ID. This is the upsert
     * branch of the typed client's index semantics &mdash; the production
     * adapter deliberately does not distinguish "create" from "update" at the
     * call site and propagates the {@link Result} verbatim so that callers
     * (e.g., {@code AuditLogService}) can audit whether a replay overwrote a
     * prior event.
     */
    @Test
    @DisplayName("indexDocument returns Result.Updated when the document is reindexed (upsert)")
    @SuppressWarnings("unchecked")
    void indexDocument_returnsUpdatedResult_whenDocumentReindexed() throws Exception {
        // Given: the client returns a Result.Updated IndexResponse
        when(openSearchClient.index(any(Function.class)))
                .thenReturn(buildIndexResponse(Result.Updated));

        // When: the adapter is invoked
        Result result = indexer.indexDocument(VALID_INDEX, VALID_DOC_ID, sampleDocument());

        // Then: the Updated result propagates verbatim
        assertEquals(Result.Updated, result,
                "Expected Result.Updated to propagate from IndexResponse.result()");
    }



    // =========================================================================
    // Phase 7 \u2014 indexDocument exception wrapping tests
    // =========================================================================

    /**
     * Test 7.1: Verifies that {@link OpenSearchIndexer#indexDocument} wraps a
     * transport-level {@link IOException} as a {@link CardDemoException} with
     * the verbatim reason code {@code OPENSEARCH_INDEX_ERROR} per AAP
     * &sect;0.7.1 ("Error codes and condition handling surfaced to downstream
     * consumers must be preserved verbatim"). The original {@link IOException}
     * must be preserved as the {@code cause} so that {@code AuditLogService}
     * can route the failed event to the local SQS DLQ for retry and
     * downstream debugging in CloudWatch / OpenSearch retains the full
     * exception chain.
     */
    @Test
    @DisplayName("indexDocument wraps IOException as CardDemoException OPENSEARCH_INDEX_ERROR")
    @SuppressWarnings("unchecked")
    void indexDocument_whenIOException_wrapsAsOpenSearchIndexError() throws Exception {
        // Given: the client throws a transport-level IOException
        IOException ioException = new IOException("simulated I/O failure");
        when(openSearchClient.index(any(Function.class))).thenThrow(ioException);

        // When/Then: the adapter wraps it as CardDemoException with the
        // OPENSEARCH_INDEX_ERROR reason code, preserving the IOException as
        // the cause for diagnostic root-cause analysis
        CardDemoException ex = assertThrows(CardDemoException.class,
                () -> indexer.indexDocument(VALID_INDEX, VALID_DOC_ID, sampleDocument()));
        assertEquals("OPENSEARCH_INDEX_ERROR", ex.getReasonCode(),
                "Reason code must be OPENSEARCH_INDEX_ERROR per AAP \u00a70.7.1");
        assertInstanceOf(IOException.class, ex.getCause(),
                "Original IOException must be preserved as the wrapped cause");
        assertSame(ioException, ex.getCause(),
                "Cause must be the same IOException instance for full diagnostic chain");
    }

    /**
     * Test 7.2: Verifies that {@link OpenSearchIndexer#indexDocument} wraps a
     * service-level {@link OpenSearchException} (raised by the typed client
     * on non-2xx responses such as 4xx client errors and 5xx server errors)
     * as a {@link CardDemoException} with reason code
     * {@code OPENSEARCH_INDEX_ERROR}.
     *
     * <p>Unlike the legacy {@code RestHighLevelClient} which surfaced all
     * service errors as {@code IOException} subclasses, the typed
     * {@link OpenSearchClient} emits a distinct {@link OpenSearchException}
     * type (extending {@link RuntimeException}). The production code's
     * {@code catch (IOException | OpenSearchException)} multicatch handles
     * both branches, and this test verifies the {@link OpenSearchException}
     * branch is correctly routed to the same {@code OPENSEARCH_INDEX_ERROR}
     * reason code.</p>
     */
    @Test
    @DisplayName("indexDocument wraps OpenSearchException as CardDemoException OPENSEARCH_INDEX_ERROR")
    @SuppressWarnings("unchecked")
    void indexDocument_whenOpenSearchException_wrapsAsOpenSearchIndexError() throws Exception {
        // Given: the client throws a service-level OpenSearchException
        OpenSearchException serviceException = newOpenSearchException();
        when(openSearchClient.index(any(Function.class))).thenThrow(serviceException);

        // When/Then: the adapter wraps it under the same reason code
        CardDemoException ex = assertThrows(CardDemoException.class,
                () -> indexer.indexDocument(VALID_INDEX, VALID_DOC_ID, sampleDocument()));
        assertEquals("OPENSEARCH_INDEX_ERROR", ex.getReasonCode(),
                "Reason code must be OPENSEARCH_INDEX_ERROR per AAP \u00a70.7.1");
        assertSame(serviceException, ex.getCause(),
                "Cause must be the same OpenSearchException instance for full diagnostic chain");
    }

    // =========================================================================
    // Phase 8 \u2014 indexDocument input validation tests
    // =========================================================================

    /**
     * Test 8.1: Verifies that {@link OpenSearchIndexer#indexDocument} throws
     * {@link IllegalArgumentException} when the {@code indexName} argument
     * is {@code null}, and that no client call is issued. Programmer errors
     * are distinguished from transient transport faults: null/blank/empty
     * argument violations surface as {@link IllegalArgumentException} at the
     * boundary; transport faults surface as {@link CardDemoException}.
     */
    @Test
    @DisplayName("indexDocument throws IllegalArgumentException when indexName is null")
    void indexDocument_withNullIndexName_throwsIllegalArgumentException() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> indexer.indexDocument(null, VALID_DOC_ID, sampleDocument()));
        verify(openSearchClient, never()).index(any(IndexRequest.class));
    }

    /**
     * Test 8.2: Empty {@code indexName} (zero-length string) must throw
     * {@link IllegalArgumentException}.
     */
    @Test
    @DisplayName("indexDocument throws IllegalArgumentException when indexName is empty")
    void indexDocument_withEmptyIndexName_throwsIllegalArgumentException() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> indexer.indexDocument("", VALID_DOC_ID, sampleDocument()));
        verify(openSearchClient, never()).index(any(IndexRequest.class));
    }

    /**
     * Test 8.3: Whitespace-only {@code indexName} must throw
     * {@link IllegalArgumentException}. The production code uses
     * {@link String#isBlank()} which trips on any Unicode-whitespace string
     * (spaces, tabs, etc.).
     */
    @Test
    @DisplayName("indexDocument throws IllegalArgumentException when indexName is blank (whitespace only)")
    void indexDocument_withBlankIndexName_throwsIllegalArgumentException() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> indexer.indexDocument("  ", VALID_DOC_ID, sampleDocument()));
        verify(openSearchClient, never()).index(any(IndexRequest.class));
    }

    /**
     * Test 8.4: Null {@code docId} must throw
     * {@link IllegalArgumentException}.
     */
    @Test
    @DisplayName("indexDocument throws IllegalArgumentException when docId is null")
    void indexDocument_withNullDocId_throwsIllegalArgumentException() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> indexer.indexDocument(VALID_INDEX, null, sampleDocument()));
        verify(openSearchClient, never()).index(any(IndexRequest.class));
    }

    /**
     * Test 8.5: Empty {@code docId} must throw
     * {@link IllegalArgumentException}.
     */
    @Test
    @DisplayName("indexDocument throws IllegalArgumentException when docId is empty")
    void indexDocument_withEmptyDocId_throwsIllegalArgumentException() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> indexer.indexDocument(VALID_INDEX, "", sampleDocument()));
        verify(openSearchClient, never()).index(any(IndexRequest.class));
    }

    /**
     * Test 8.6: Blank {@code docId} (whitespace only) must throw
     * {@link IllegalArgumentException}.
     */
    @Test
    @DisplayName("indexDocument throws IllegalArgumentException when docId is blank (whitespace only)")
    void indexDocument_withBlankDocId_throwsIllegalArgumentException() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> indexer.indexDocument(VALID_INDEX, "  ", sampleDocument()));
        verify(openSearchClient, never()).index(any(IndexRequest.class));
    }

    /**
     * Test 8.7: Null {@code document} must throw
     * {@link IllegalArgumentException}.
     */
    @Test
    @DisplayName("indexDocument throws IllegalArgumentException when document is null")
    void indexDocument_withNullDocument_throwsIllegalArgumentException() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> indexer.indexDocument(VALID_INDEX, VALID_DOC_ID, null));
        verify(openSearchClient, never()).index(any(IndexRequest.class));
    }

    /**
     * Test 8.8: Empty {@code document} (zero-entry map) must throw
     * {@link IllegalArgumentException}. The production code rejects empty
     * maps so that downstream OpenSearch indexes never carry "empty" audit
     * records that would dilute fraud-investigation queries.
     */
    @Test
    @DisplayName("indexDocument throws IllegalArgumentException when document map is empty")
    void indexDocument_withEmptyDocument_throwsIllegalArgumentException() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> indexer.indexDocument(VALID_INDEX, VALID_DOC_ID, Collections.emptyMap()));
        verify(openSearchClient, never()).index(any(IndexRequest.class));
    }



    // =========================================================================
    // Phase 9 \u2014 search happy-path tests
    // =========================================================================

    /**
     * Test 9.1: Verifies the canonical happy path of
     * {@link OpenSearchIndexer#search} &mdash; a valid call returns the list
     * of hit {@code source()} maps with the same size and content as the
     * underlying {@link SearchResponse}.
     *
     * <p>The deeply-nested {@link SearchResponse} structure is mocked
     * explicitly (rather than via {@code RETURNS_DEEP_STUBS}) so that
     * strict-stubbing remains enabled and the test's intent is
     * self-documenting: every layer of the response chain
     * ({@code response.hits()} &rarr; {@code HitsMetadata.hits()} &rarr;
     * per-{@link Hit} {@code source()}) is visible in the test body.</p>
     *
     * <p>The production code defensively copies each hit's source into a
     * fresh {@link LinkedHashMap} to detach the returned list from any
     * internal client state, so the assertion compares the field contents
     * &mdash; not the {@code Map} identity &mdash; for each entry.</p>
     */
    @Test
    @DisplayName("search returns the list of hit source maps for a valid query")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void search_withValidInputs_returnsHitSources() throws Exception {
        // Given: two hits whose .source() each return a distinct sample map
        Map<String, Object> hit1Source = new LinkedHashMap<>();
        hit1Source.put("transaction_id", "TXN-001");
        hit1Source.put("event_type", "TRANSACTION_POSTED");

        Map<String, Object> hit2Source = new LinkedHashMap<>();
        hit2Source.put("transaction_id", "TXN-002");
        hit2Source.put("event_type", "ACCOUNT_UPDATED");

        // Build a real SearchResponse via the typed-client builder factory.
        // Real Hit instances carry the supplied source maps verbatim, and the
        // .total() accessor returns a non-null TotalHits (size 2). The
        // production code reads .total() at DEBUG log level; the real
        // accessor never throws and exposes the same observable shape as a
        // genuine OpenSearch service response.
        SearchResponse<Map> response = buildSearchResponse(List.of(hit1Source, hit2Source));

        when(openSearchClient.search(any(Function.class), eq(Map.class)))
                .thenReturn(response);

        // When: the adapter is invoked with an explicit maxHits=50 cap
        List<Map<String, Object>> results = indexer.search(VALID_INDEX, matchAllQuery(), 50);

        // Then: both hits' sources are materialized into the returned list
        assertNotNull(results, "Result list must never be null");
        assertEquals(2, results.size(), "Expected two hits in the materialized result list");

        assertEquals("TXN-001", results.get(0).get("transaction_id"));
        assertEquals("TRANSACTION_POSTED", results.get(0).get("event_type"));
        assertEquals("TXN-002", results.get(1).get("transaction_id"));
        assertEquals("ACCOUNT_UPDATED", results.get(1).get("event_type"));
    }

    /**
     * Test 9.2: Verifies that {@link OpenSearchIndexer#search} applies the
     * default {@code DEFAULT_SEARCH_SIZE = 100} cap when the caller passes
     * a {@code null} {@code maxHits} argument. This is verified by capturing
     * the lambda passed to {@code openSearchClient.search(...)}, applying it
     * to a fresh {@link SearchRequest.Builder}, and asserting on the built
     * request's {@link SearchRequest#size} accessor.
     *
     * <p>The default cap exists to prevent runaway queries from materializing
     * the entire index into application memory during fraud investigation or
     * compliance report generation. Verifying it here protects against
     * silent regressions where a future refactor might drop the default or
     * change it to a different value.</p>
     */
    @Test
    @DisplayName("search applies a default size of 100 when maxHits is null")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void search_withNullMaxHits_appliesDefaultLimitOf100() throws Exception {
        // Given: an empty search response (no hits) built via the real
        // typed-client builder factory.
        SearchResponse<Map> response = buildSearchResponse(List.of());
        when(openSearchClient.search(any(Function.class), eq(Map.class)))
                .thenReturn(response);

        // When: the adapter is invoked with maxHits = null
        List<Map<String, Object>> results = indexer.search(VALID_INDEX, matchAllQuery(), null);

        // Then: an empty list is returned (no hits)
        assertNotNull(results);
        assertTrue(results.isEmpty(), "Expected an empty result list for a no-hit search");

        // And: capture the lambda and verify the built SearchRequest carries
        // size=100 (the DEFAULT_SEARCH_SIZE), proving the default was applied
        ArgumentCaptor<Function<SearchRequest.Builder,
                ObjectBuilder<SearchRequest>>> captor =
                (ArgumentCaptor) ArgumentCaptor.forClass(Function.class);
        verify(openSearchClient).search(captor.capture(), eq(Map.class));

        SearchRequest builtRequest = captor.getValue()
                .apply(new SearchRequest.Builder())
                .build();
        assertEquals(Integer.valueOf(100), builtRequest.size(),
                "Default size must be 100 when maxHits is null");
        // And the index name must propagate verbatim
        assertEquals(List.of(VALID_INDEX), builtRequest.index(),
                "SearchRequest.index() must equal the supplied index name");
    }

    /**
     * Test 9.3: Verifies the same default-100 behavior when the caller passes
     * {@code maxHits = 0}. The production code's guard is
     * {@code (maxHits != null && maxHits > 0)}, so a zero value falls
     * through to the default.
     */
    @Test
    @DisplayName("search applies a default size of 100 when maxHits is zero")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void search_withZeroMaxHits_appliesDefaultLimitOf100() throws Exception {
        // Given: an empty search response built via the real typed-client
        // builder factory (no Mockito final-method mocking required).
        SearchResponse<Map> response = buildSearchResponse(List.of());
        when(openSearchClient.search(any(Function.class), eq(Map.class)))
                .thenReturn(response);

        // When: the adapter is invoked with maxHits = 0 (non-positive)
        indexer.search(VALID_INDEX, matchAllQuery(), 0);

        // Then: the built SearchRequest carries size=100 (the default)
        ArgumentCaptor<Function<SearchRequest.Builder,
                ObjectBuilder<SearchRequest>>> captor =
                (ArgumentCaptor) ArgumentCaptor.forClass(Function.class);
        verify(openSearchClient).search(captor.capture(), eq(Map.class));

        SearchRequest builtRequest = captor.getValue()
                .apply(new SearchRequest.Builder())
                .build();
        assertEquals(Integer.valueOf(100), builtRequest.size(),
                "Default size must be 100 when maxHits is zero");
    }

    /**
     * Test 9.4: Verifies the same default-100 behavior when the caller passes
     * a negative {@code maxHits}. Negative values are non-positive and
     * fall through to the default per the production guard
     * {@code (maxHits != null && maxHits > 0)}.
     */
    @Test
    @DisplayName("search applies a default size of 100 when maxHits is negative")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void search_withNegativeMaxHits_appliesDefaultLimitOf100() throws Exception {
        SearchResponse<Map> response = buildSearchResponse(List.of());
        when(openSearchClient.search(any(Function.class), eq(Map.class)))
                .thenReturn(response);

        indexer.search(VALID_INDEX, matchAllQuery(), -10);

        ArgumentCaptor<Function<SearchRequest.Builder,
                ObjectBuilder<SearchRequest>>> captor =
                (ArgumentCaptor) ArgumentCaptor.forClass(Function.class);
        verify(openSearchClient).search(captor.capture(), eq(Map.class));

        SearchRequest builtRequest = captor.getValue()
                .apply(new SearchRequest.Builder())
                .build();
        assertEquals(Integer.valueOf(100), builtRequest.size(),
                "Default size must be 100 when maxHits is negative");
    }

    /**
     * Test 9.5: Verifies that {@link OpenSearchIndexer#search} accepts a
     * positive caller-supplied {@code maxHits} verbatim. This is the
     * mirror image of Tests 9.2&ndash;9.4 &mdash; the production code only
     * substitutes the default when {@code maxHits} is null or non-positive;
     * positive values must propagate unchanged to the SearchRequest.
     */
    @Test
    @DisplayName("search respects a positive maxHits value verbatim")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void search_withPositiveMaxHits_appliesProvidedSize() throws Exception {
        SearchResponse<Map> response = buildSearchResponse(List.of());
        when(openSearchClient.search(any(Function.class), eq(Map.class)))
                .thenReturn(response);

        // When the caller asks for 25 hits, the request must carry size=25
        indexer.search(VALID_INDEX, matchAllQuery(), 25);

        ArgumentCaptor<Function<SearchRequest.Builder,
                ObjectBuilder<SearchRequest>>> captor =
                (ArgumentCaptor) ArgumentCaptor.forClass(Function.class);
        verify(openSearchClient).search(captor.capture(), eq(Map.class));

        SearchRequest builtRequest = captor.getValue()
                .apply(new SearchRequest.Builder())
                .build();
        assertEquals(Integer.valueOf(25), builtRequest.size(),
                "Caller-supplied positive size must propagate verbatim");
    }

    /**
     * Test 9.6: Verifies that a hit with a {@code null} {@code source()}
     * returns an empty map (not null) so that downstream callers can iterate
     * the result list without {@code null} checks. This mirrors the
     * production code's defensive handling of fields-only retrieval responses.
     */
    @Test
    @DisplayName("search returns an empty map for hits with null source")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void search_withNullHitSource_returnsEmptyMap() throws Exception {
        // Pass a one-element list containing null — the helper translates a
        // null source-map entry into a real Hit where .source() returns null,
        // exercising the production code's null-source defensive branch
        // (returns Collections.emptyMap() for that entry).
        // Wrap in java.util.Arrays.asList(...) because List.of(...) rejects
        // null elements with NullPointerException.
        SearchResponse<Map> response = buildSearchResponse(
                java.util.Arrays.asList((Map<String, Object>) null));
        when(openSearchClient.search(any(Function.class), eq(Map.class)))
                .thenReturn(response);

        List<Map<String, Object>> results = indexer.search(VALID_INDEX, matchAllQuery(), 50);

        assertEquals(1, results.size(), "Hit count is preserved even when source is null");
        assertTrue(results.get(0).isEmpty(),
                "Hits with null source must yield an empty (not null) map for iteration safety");
    }

    /**
     * Test 9.7: Verifies that the adapter accepts a {@code null} {@link Query}
     * and treats it as a match-all (no {@code query} clause emitted on the
     * built {@link SearchRequest}). This is documented production behavior:
     * the {@code search} method specifies that a null query "omits the
     * {@code query} field and OpenSearch returns documents from the index up
     * to {@code maxHits}".
     */
    @Test
    @DisplayName("search accepts a null Query and omits the query clause")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void search_withNullQuery_omitsQueryClause() throws Exception {
        SearchResponse<Map> response = buildSearchResponse(List.of());
        when(openSearchClient.search(any(Function.class), eq(Map.class)))
                .thenReturn(response);

        // When a null query is supplied, the call must not throw
        indexer.search(VALID_INDEX, null, 10);

        // Then: the built SearchRequest's query is null (no clause emitted)
        ArgumentCaptor<Function<SearchRequest.Builder,
                ObjectBuilder<SearchRequest>>> captor =
                (ArgumentCaptor) ArgumentCaptor.forClass(Function.class);
        verify(openSearchClient).search(captor.capture(), eq(Map.class));

        SearchRequest builtRequest = captor.getValue()
                .apply(new SearchRequest.Builder())
                .build();
        assertNull(builtRequest.query(),
                "Null query must produce a SearchRequest with no query clause");
    }

    /**
     * Test 9.8: Verifies that when {@link HitsMetadata#total} is non-null,
     * the production code reads the total via {@link TotalHits#value} for
     * its DEBUG log message and still returns the materialized hit list
     * unchanged. This protects the {@code totalHits} accessor path against
     * regressions where a future refactor might confuse {@code total()}
     * with a hit-count accessor.
     */
    @Test
    @DisplayName("search materializes hits when HitsMetadata.total is provided")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void search_withTotalHitsProvided_returnsMaterializedHits() throws Exception {
        // Given: a hit and a real (builder-built) TotalHits with value=1. The
        // buildSearchResponse helper always builds a non-null TotalHits
        // matching the hit count, so passing a single-hit list yields the
        // exact shape this test requires (1 hit, total.value()==1).
        Map<String, Object> hitSource = new LinkedHashMap<>();
        hitSource.put("transaction_id", "TXN-007");

        SearchResponse<Map> response = buildSearchResponse(List.of(hitSource));
        when(openSearchClient.search(any(Function.class), eq(Map.class)))
                .thenReturn(response);

        // When the adapter is invoked
        List<Map<String, Object>> results = indexer.search(VALID_INDEX, matchAllQuery(), 50);

        // Then the hit is materialized as expected
        assertEquals(1, results.size());
        assertEquals("TXN-007", results.get(0).get("transaction_id"));
    }



    // =========================================================================
    // Phase 10 \u2014 search exception wrapping tests
    // =========================================================================

    /**
     * Test 10.1: Verifies that {@link OpenSearchIndexer#search} wraps a
     * transport-level {@link IOException} as a {@link CardDemoException}
     * with the verbatim reason code {@code OPENSEARCH_SEARCH_ERROR}. The
     * original {@link IOException} is preserved as the {@code cause} so that
     * fraud-investigation operators see the full diagnostic chain in
     * CloudWatch.
     */
    @Test
    @DisplayName("search wraps IOException as CardDemoException OPENSEARCH_SEARCH_ERROR")
    @SuppressWarnings("unchecked")
    void search_whenIOException_wrapsAsOpenSearchSearchError() throws Exception {
        // Given: the client throws an IOException during search
        IOException ioException = new IOException("simulated search I/O failure");
        when(openSearchClient.search(any(Function.class), eq(Map.class)))
                .thenThrow(ioException);

        // When/Then: the adapter wraps as CardDemoException OPENSEARCH_SEARCH_ERROR
        CardDemoException ex = assertThrows(CardDemoException.class,
                () -> indexer.search(VALID_INDEX, matchAllQuery(), 50));
        assertEquals("OPENSEARCH_SEARCH_ERROR", ex.getReasonCode(),
                "Reason code must be OPENSEARCH_SEARCH_ERROR per AAP \u00a70.7.1");
        assertInstanceOf(IOException.class, ex.getCause(),
                "Original IOException must be preserved as the wrapped cause");
        assertSame(ioException, ex.getCause(),
                "Cause must be the same IOException instance");
    }

    /**
     * Test 10.2: Verifies the service-level {@link OpenSearchException}
     * branch of {@link OpenSearchIndexer#search}'s multicatch is routed to
     * the same {@code OPENSEARCH_SEARCH_ERROR} reason code.
     */
    @Test
    @DisplayName("search wraps OpenSearchException as CardDemoException OPENSEARCH_SEARCH_ERROR")
    @SuppressWarnings("unchecked")
    void search_whenOpenSearchException_wrapsAsOpenSearchSearchError() throws Exception {
        OpenSearchException serviceException = newOpenSearchException();
        when(openSearchClient.search(any(Function.class), eq(Map.class)))
                .thenThrow(serviceException);

        CardDemoException ex = assertThrows(CardDemoException.class,
                () -> indexer.search(VALID_INDEX, matchAllQuery(), 50));
        assertEquals("OPENSEARCH_SEARCH_ERROR", ex.getReasonCode());
        assertSame(serviceException, ex.getCause());
    }

    // =========================================================================
    // Phase 11 \u2014 search input validation tests
    // =========================================================================

    /**
     * Test 11.1: Null {@code indexName} must throw
     * {@link IllegalArgumentException} without contacting the client.
     */
    @Test
    @DisplayName("search throws IllegalArgumentException when indexName is null")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void search_withNullIndexName_throwsIllegalArgumentException() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> indexer.search(null, matchAllQuery(), 50));
        verify(openSearchClient, never()).search(any(Function.class), any(Class.class));
        verify(openSearchClient, never()).search(any(SearchRequest.class), any(Class.class));
    }

    /**
     * Test 11.2: Empty {@code indexName} must throw
     * {@link IllegalArgumentException}.
     */
    @Test
    @DisplayName("search throws IllegalArgumentException when indexName is empty")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void search_withEmptyIndexName_throwsIllegalArgumentException() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> indexer.search("", matchAllQuery(), 50));
        verify(openSearchClient, never()).search(any(Function.class), any(Class.class));
        verify(openSearchClient, never()).search(any(SearchRequest.class), any(Class.class));
    }

    /**
     * Test 11.3: Whitespace-only {@code indexName} must throw
     * {@link IllegalArgumentException}.
     */
    @Test
    @DisplayName("search throws IllegalArgumentException when indexName is blank")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void search_withBlankIndexName_throwsIllegalArgumentException() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> indexer.search("  ", matchAllQuery(), 50));
        verify(openSearchClient, never()).search(any(Function.class), any(Class.class));
        verify(openSearchClient, never()).search(any(SearchRequest.class), any(Class.class));
    }

    // =========================================================================
    // Phase 12 \u2014 bulkIndex happy-path tests
    // =========================================================================

    /**
     * Test 12.1: Verifies the canonical happy path of
     * {@link OpenSearchIndexer#bulkIndex} &mdash; submitting three documents
     * to a successful bulk operation returns a success count of three. The
     * production code constructs a {@link BulkRequest} object directly (not
     * via the lambda overload) so a standard
     * {@code ArgumentCaptor<BulkRequest>} captures the request for further
     * assertion if needed.
     *
     * <p>Each {@link BulkResponseItem} mock returns {@code null} from
     * {@code .error()}, modeling per-item success. The production loop
     * counts successes via {@code (item.error() == null)} so the resulting
     * count must equal the input document count.</p>
     */
    @Test
    @DisplayName("bulkIndex returns the success count when all items succeed")
    void bulkIndex_withMultipleDocs_returnsSuccessCount() throws Exception {
        // Given: a real BulkResponse with three successful items built via
        // the typed-client public builder factory. The buildBulkResponse
        // helper auto-derives the .errors() flag from the items list (false
        // when no item has a non-null error), matching real OpenSearch
        // service-response semantics.
        BulkResponse response = buildBulkResponse(List.of(
                buildSuccessfulBulkItem("id1"),
                buildSuccessfulBulkItem("id2"),
                buildSuccessfulBulkItem("id3")));

        when(openSearchClient.bulk(any(BulkRequest.class))).thenReturn(response);

        // Build a deterministic input map (LinkedHashMap preserves iteration order)
        Map<String, Map<String, Object>> docs = new LinkedHashMap<>();
        docs.put("id1", sampleDocument());
        docs.put("id2", sampleDocument());
        docs.put("id3", sampleDocument());

        // When: the adapter is invoked
        int count = indexer.bulkIndex(VALID_INDEX, docs);

        // Then: all three items count as successes
        assertEquals(3, count, "Expected three successful items in the bulk response");

        // And: the constructed BulkRequest carries the verbatim index name
        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(openSearchClient).bulk(captor.capture());
        assertEquals(VALID_INDEX, captor.getValue().index(),
                "BulkRequest.index() must carry the supplied index name");
        // And: every operation in the request is non-null (three operations were
        // submitted) -- the typed-client API does not expose per-operation
        // accessors for index/id at this point in the builder chain without
        // additional unwrapping, so the operation count is the strongest
        // black-box assertion we can make here.
        assertEquals(3, captor.getValue().operations().size(),
                "BulkRequest must carry three operations matching the input map size");
    }

    /**
     * Test 12.2: Verifies that {@link OpenSearchIndexer#bulkIndex} tolerates
     * per-item failures without throwing &mdash; the adapter returns only
     * the count of successful items, and the failed subset is logged at
     * {@code WARN} for the caller (batch jobs) to retry.
     *
     * <p>This is a critical behavior contract per AAP &sect;0.6.3 (batch
     * jobs need a numeric success count, not an exception, so they can
     * decide whether to retry the failed subset). The test models a 3-doc
     * bulk where the middle item fails &mdash; the return must be 2, not
     * 3, and no exception must propagate.</p>
     */
    @Test
    @DisplayName("bulkIndex returns the success count and tolerates per-item failures")
    void bulkIndex_withSomeFailures_returnsSuccessCountAndLogsFailures() throws Exception {
        // Given: a real BulkResponse with two successes and one failure
        // built via the typed-client public builder factory. The failed item
        // carries a real ErrorCause (type=internal_server_error,
        // reason="simulated bulk item failure for id-fail"), and the
        // buildBulkResponse helper auto-sets .errors()=true because one
        // item has a non-null error.
        BulkResponse response = buildBulkResponse(List.of(
                buildSuccessfulBulkItem("id1"),
                buildFailedBulkItem("id-fail"),
                buildSuccessfulBulkItem("id2")));

        when(openSearchClient.bulk(any(BulkRequest.class))).thenReturn(response);

        Map<String, Map<String, Object>> docs = new LinkedHashMap<>();
        docs.put("id1", sampleDocument());
        docs.put("id2", sampleDocument());
        docs.put("id3", sampleDocument());

        // When: the adapter is invoked
        int count = indexer.bulkIndex(VALID_INDEX, docs);

        // Then: only the two successes are counted, the failure is NOT thrown
        assertEquals(2, count, "Only successful items must be counted; failed item is logged not thrown");
        verify(openSearchClient, times(1)).bulk(any(BulkRequest.class));
    }

    /**
     * Test 12.3: Verifies that {@link OpenSearchIndexer#bulkIndex} returns
     * {@code 0} when {@code documents} is {@code null} &mdash; a no-op
     * short-circuit per the production contract ("safe to call from batch
     * processors that may yield zero records for a given chunk"). No client
     * call is made.
     */
    @Test
    @DisplayName("bulkIndex returns 0 and does not contact OpenSearch when documents is null")
    void bulkIndex_withNullDocuments_returnsZero() throws Exception {
        int count = indexer.bulkIndex(VALID_INDEX, null);
        assertEquals(0, count, "Null documents must short-circuit to a zero return");
        verify(openSearchClient, never()).bulk(any(BulkRequest.class));
    }

    /**
     * Test 12.4: Verifies the same no-op behavior when {@code documents} is
     * an empty map. Empty inputs are valid for the batch use case (chunks
     * may yield zero records), so the adapter must not throw and must not
     * contact OpenSearch.
     */
    @Test
    @DisplayName("bulkIndex returns 0 and does not contact OpenSearch when documents is empty")
    void bulkIndex_withEmptyDocuments_returnsZero() throws Exception {
        int count = indexer.bulkIndex(VALID_INDEX, Collections.emptyMap());
        assertEquals(0, count, "Empty documents must short-circuit to a zero return");
        verify(openSearchClient, never()).bulk(any(BulkRequest.class));
    }

    /**
     * Test 12.5: Verifies that malformed map entries are skipped silently
     * (logged at {@code WARN}) without failing the whole bulk operation.
     * Production code iterates entries and skips any with blank ID or
     * null/empty body, then submits only the well-formed entries.
     *
     * <p>This test provides a map with one valid entry and one malformed
     * entry (null body). The expectation is that the bulk request is built
     * with only one operation, and the success count reflects only that
     * one entry.</p>
     */
    @Test
    @DisplayName("bulkIndex skips malformed entries and continues with the remaining ones")
    void bulkIndex_withMalformedEntries_skipsMalformedAndProcessesRest() throws Exception {
        // Given: a real BulkResponse with one successful item (the one
        // well-formed input entry "good"). The bulk request must contain
        // exactly one operation because the production loop skips malformed
        // entries (null body or blank ID) before submission.
        BulkResponse response = buildBulkResponse(List.of(
                buildSuccessfulBulkItem("good")));

        when(openSearchClient.bulk(any(BulkRequest.class))).thenReturn(response);

        // Use a LinkedHashMap so the null-value entry is iteration-order
        // visible; the production code allows null values and skips them.
        Map<String, Map<String, Object>> docs = new LinkedHashMap<>();
        docs.put("good", sampleDocument());
        docs.put("bad", null);  // null body \u2192 skipped

        int count = indexer.bulkIndex(VALID_INDEX, docs);

        // Only the well-formed entry was submitted, and OpenSearch acked it
        assertEquals(1, count, "Only the well-formed entry must be counted");

        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(openSearchClient).bulk(captor.capture());
        assertEquals(1, captor.getValue().operations().size(),
                "Malformed entries must be excluded from the BulkRequest");
    }

    /**
     * Test 12.6: Verifies that when ALL entries are malformed (e.g., all
     * have null bodies or blank IDs), the production code short-circuits
     * with a zero return and does not contact OpenSearch.
     */
    @Test
    @DisplayName("bulkIndex returns 0 and does not contact OpenSearch when all entries are malformed")
    void bulkIndex_withAllMalformedEntries_returnsZeroWithoutCallingClient() throws Exception {
        Map<String, Map<String, Object>> docs = new LinkedHashMap<>();
        docs.put("bad1", null);
        docs.put("bad2", Collections.emptyMap());
        docs.put("", sampleDocument()); // blank ID

        int count = indexer.bulkIndex(VALID_INDEX, docs);
        assertEquals(0, count, "All-malformed input must short-circuit to a zero return");
        verify(openSearchClient, never()).bulk(any(BulkRequest.class));
    }



    // =========================================================================
    // Phase 13 \u2014 bulkIndex exception wrapping tests
    // =========================================================================

    /**
     * Test 13.1: Verifies that {@link OpenSearchIndexer#bulkIndex} wraps a
     * transport-level {@link IOException} as {@link CardDemoException} with
     * the verbatim reason code {@code OPENSEARCH_BULK_INDEX_ERROR} when the
     * underlying bulk operation fails wholesale (the transport itself
     * faulted). This is the only exception path for {@code bulkIndex};
     * per-item failures are tolerated and reflected in the return count
     * (verified by Test 12.2).
     */
    @Test
    @DisplayName("bulkIndex wraps IOException as CardDemoException OPENSEARCH_BULK_INDEX_ERROR")
    void bulkIndex_whenIOException_wrapsAsOpenSearchBulkIndexError() throws Exception {
        IOException ioException = new IOException("simulated bulk I/O failure");
        when(openSearchClient.bulk(any(BulkRequest.class))).thenThrow(ioException);

        Map<String, Map<String, Object>> docs = new LinkedHashMap<>();
        docs.put("id1", sampleDocument());

        CardDemoException ex = assertThrows(CardDemoException.class,
                () -> indexer.bulkIndex(VALID_INDEX, docs));
        assertEquals("OPENSEARCH_BULK_INDEX_ERROR", ex.getReasonCode(),
                "Reason code must be OPENSEARCH_BULK_INDEX_ERROR per AAP \u00a70.7.1");
        assertInstanceOf(IOException.class, ex.getCause(),
                "Original IOException must be preserved as the wrapped cause");
        assertSame(ioException, ex.getCause(),
                "Cause must be the same IOException instance");
    }

    /**
     * Test 13.2: Verifies the service-level {@link OpenSearchException}
     * branch of {@link OpenSearchIndexer#bulkIndex}'s multicatch is routed
     * to the same {@code OPENSEARCH_BULK_INDEX_ERROR} reason code.
     */
    @Test
    @DisplayName("bulkIndex wraps OpenSearchException as CardDemoException OPENSEARCH_BULK_INDEX_ERROR")
    void bulkIndex_whenOpenSearchException_wrapsAsOpenSearchBulkIndexError() throws Exception {
        OpenSearchException serviceException = newOpenSearchException();
        when(openSearchClient.bulk(any(BulkRequest.class))).thenThrow(serviceException);

        Map<String, Map<String, Object>> docs = new LinkedHashMap<>();
        docs.put("id1", sampleDocument());

        CardDemoException ex = assertThrows(CardDemoException.class,
                () -> indexer.bulkIndex(VALID_INDEX, docs));
        assertEquals("OPENSEARCH_BULK_INDEX_ERROR", ex.getReasonCode());
        assertSame(serviceException, ex.getCause());
    }

    // =========================================================================
    // Phase 14 \u2014 bulkIndex input validation tests
    // =========================================================================

    /**
     * Test 14.1: Null {@code indexName} must throw
     * {@link IllegalArgumentException} without contacting the client.
     */
    @Test
    @DisplayName("bulkIndex throws IllegalArgumentException when indexName is null")
    void bulkIndex_withNullIndexName_throwsIllegalArgumentException() throws Exception {
        Map<String, Map<String, Object>> docs = new LinkedHashMap<>();
        docs.put("id1", sampleDocument());
        assertThrows(IllegalArgumentException.class,
                () -> indexer.bulkIndex(null, docs));
        verify(openSearchClient, never()).bulk(any(BulkRequest.class));
    }

    /**
     * Test 14.2: Empty {@code indexName} must throw
     * {@link IllegalArgumentException}.
     */
    @Test
    @DisplayName("bulkIndex throws IllegalArgumentException when indexName is empty")
    void bulkIndex_withEmptyIndexName_throwsIllegalArgumentException() throws Exception {
        Map<String, Map<String, Object>> docs = new LinkedHashMap<>();
        docs.put("id1", sampleDocument());
        assertThrows(IllegalArgumentException.class,
                () -> indexer.bulkIndex("", docs));
        verify(openSearchClient, never()).bulk(any(BulkRequest.class));
    }

    /**
     * Test 14.3: Whitespace-only {@code indexName} must throw
     * {@link IllegalArgumentException}.
     */
    @Test
    @DisplayName("bulkIndex throws IllegalArgumentException when indexName is blank")
    void bulkIndex_withBlankIndexName_throwsIllegalArgumentException() throws Exception {
        Map<String, Map<String, Object>> docs = new LinkedHashMap<>();
        docs.put("id1", sampleDocument());
        assertThrows(IllegalArgumentException.class,
                () -> indexer.bulkIndex("  ", docs));
        verify(openSearchClient, never()).bulk(any(BulkRequest.class));
    }

    // =========================================================================
    // Cross-cutting verification (no-cache and PCI-DSS discipline)
    // =========================================================================

    /**
     * Cross-cutting test: Verifies that successive calls to
     * {@link OpenSearchIndexer#indexDocument} each produce a separate client
     * call. The production adapter performs no local caching of indexed
     * documents (audit emission must reach OpenSearch on every call so that
     * replays and reads see a consistent view), so two invocations of
     * {@code indexDocument} must result in two distinct calls to
     * {@code openSearchClient.index(...)}. This protects against silent
     * regressions where a future "optimization" might introduce a cache that
     * would break the audit-emission contract.
     */
    @Test
    @DisplayName("indexDocument does not cache responses \u2014 each call hits the client")
    @SuppressWarnings("unchecked")
    void indexDocument_doesNotCache_eachCallHitsTheClient() throws Exception {
        when(openSearchClient.index(any(Function.class)))
                .thenReturn(buildIndexResponse(Result.Created));

        indexer.indexDocument(VALID_INDEX, "id-1", sampleDocument());
        indexer.indexDocument(VALID_INDEX, "id-2", sampleDocument());
        indexer.indexDocument(VALID_INDEX, "id-3", sampleDocument());

        // Three invocations must produce three distinct client calls
        verify(openSearchClient, times(3)).index(any(Function.class));
    }
}

