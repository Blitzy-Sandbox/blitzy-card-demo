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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * ElastiCache (Redis) cache-aside adapter for CardDemo — the sole entry
 * point in {@code src/main/java/com/awsm2/carddemo/} that interacts with
 * the Spring Data Redis {@link RedisTemplate} API directly.
 *
 * <p><b>Replaces:</b> No direct COBOL source — this is a <em>net-new</em>
 * capability introduced by the AWS-native target. The reference COBOL
 * programs ({@code app/cbl/COACTVWC.cbl}, {@code app/cbl/COCRDSLC.cbl},
 * {@code app/cbl/CBACT04C.cbl}) all issue high-frequency, read-only
 * lookups against {@code ACCTDAT}/{@code CARDDAT} VSAM KSDS clusters
 * with no caching whatsoever. This adapter introduces an Amazon
 * ElastiCache Redis cache-aside layer in front of the equivalent RDS
 * PostgreSQL tables ({@code Account}, {@code Card}) to absorb hot-account
 * read load on the AWS target. New capability — no source COBOL
 * equivalent. ElastiCache Redis introduces cache-aside per AAP &sect;0.7.1.</p>
 *
 * <h2>AAP authority sections</h2>
 * <ul>
 *   <li><b>AAP &sect;0.7.1</b> &mdash; "ElastiCache (Redis) used for account
 *       balance caching &mdash; cache-aside pattern with TTL aligned to
 *       transaction frequency".</li>
 *   <li><b>AAP &sect;0.6.6</b> &mdash; "ElastiCache Redis reduces RDS read
 *       load by caching high-frequency account balance lookups";
 *       encryption-in-transit via TLS 1.2+ is configured in
 *       {@link com.awsm2.carddemo.config.RedisConfig} ({@code spring.data.redis.ssl.enabled}
 *       in {@code dev}/{@code prod} overlays). Encryption-at-rest is
 *       configured at the ElastiCache cluster level via the Terraform
 *       module ({@code infrastructure/terraform/elasticache.tf}) using
 *       a KMS customer-managed key (CMK).</li>
 *   <li><b>AAP &sect;0.3.3</b> &mdash; design pattern "Cache-Aside:
 *       ElastiCache Redis with TTL aligned to transaction frequency;
 *       {@code allkeys-lru} eviction policy" (eviction policy is set on
 *       the cluster, not the client).</li>
 *   <li><b>AAP &sect;0.7.2</b> &mdash; PCI-DSS: NO plaintext PAN, CVV,
 *       password hash, or unmasked SSN may be cached. Callers are
 *       responsible for sanitisation; this adapter does NOT enforce it
 *       (see the {@code put(...)} JavaDoc warning).</li>
 *   <li><b>AAP &sect;0.7.3</b> &mdash; adapter-isolation rule: AWS service
 *       integrations live in {@code adapter/} classes; business logic
 *       must never call {@code RedisTemplate} directly.</li>
 * </ul>
 *
 * <h2>Cache-aside protocol</h2>
 * <p>The cache-aside (lazy-loading) protocol implemented by this adapter
 * is the standard:</p>
 * <ol>
 *   <li><b>Read path</b> &mdash; caller invokes {@link #get(String, String, Class)};
 *       on hit, the cached value is returned wrapped in {@link Optional};
 *       on miss (or any Redis failure), {@link Optional#empty()} is returned
 *       and the caller is expected to load from the source-of-truth (RDS)
 *       and then call {@link #put(String, String, Object, Duration)} to
 *       populate the cache.</li>
 *   <li><b>Write path</b> &mdash; on every successful write to the
 *       source-of-truth, the caller invokes {@link #evict(String, String)}
 *       to remove the now-stale cache entry. The <em>next</em> read
 *       repopulates from the database. Write-through (writer also writes
 *       the cache) is intentionally NOT used: it can leave the cache and
 *       database inconsistent if the cache write fails after the DB
 *       commit, and it is not part of the AAP &sect;0.7.1 cache-aside
 *       specification.</li>
 * </ol>
 *
 * <h2>Fail-open semantics (AAP &sect;0.7.1)</h2>
 * <p>Cache failures MUST NOT propagate to the calling business flow. The
 * adapter wraps every Redis call in a try/catch and on any
 * {@link RuntimeException}:</p>
 * <ul>
 *   <li>{@link #get} returns {@link Optional#empty()}, so the caller
 *       falls back to the database-of-record on what appears to be a
 *       cache miss.</li>
 *   <li>{@link #put} logs the failure and returns normally — a cache
 *       write failure must never block a successful business flow.</li>
 *   <li>{@link #evict} logs the failure and returns normally — the entry
 *       will eventually expire via TTL.</li>
 *   <li>{@link #evictAll} logs the failure and returns normally — same
 *       rationale as {@link #evict}.</li>
 * </ul>
 *
 * <h2>Key format</h2>
 * <p>Every public method delegates key construction to
 * {@link #buildKey(String, String)}, which produces
 * {@code "carddemo:<namespace>:<key>"}. The {@code carddemo:} prefix
 * isolates this application from any other tenant on a shared Redis
 * cluster.</p>
 *
 * <h2>Conventional namespaces (documentation, NOT enforced as enum)</h2>
 * <p>Per AAP-aligned conventions, services pass these namespace strings:</p>
 * <ul>
 *   <li>{@code account} &mdash; {@code Account} entity by zero-padded ID
 *       ({@code %011d}); TTL 5 min (high-frequency balance reads).</li>
 *   <li>{@code card} &mdash; {@code Card} entity by masked PAN; TTL 5 min.</li>
 *   <li>{@code discgrp} &mdash; {@code DisclosureGroup} lookup by
 *       composite key; TTL 1 hr (mostly static reference data).</li>
 *   <li>{@code tranType} &mdash; {@code TransactionType} (7 rows); TTL 1 hr.</li>
 *   <li>{@code tranCatg} &mdash; {@code TransactionCategory} (18 rows); TTL 1 hr.</li>
 *   <li>{@code xref} &mdash; {@code CardCrossReference} by card number;
 *       TTL 15 min.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <p>{@link RedisTemplate} is thread-safe and intended to be shared. The
 * adapter holds a single template reference and exposes only stateless
 * methods. The injected {@code defaultTtlSeconds} field is set once at
 * Spring startup and never mutated.</p>
 *
 * <h2>PCI-DSS compliance reminder (AAP &sect;0.6.6, &sect;0.7.2)</h2>
 * <p>Callers of this adapter MUST sanitise values BEFORE invoking
 * {@link #put}:</p>
 * <ul>
 *   <li><b>NEVER</b> cache the full Primary Account Number (PAN) — always
 *       cache a masked form ({@code ************XXXX}) or omit the field.</li>
 *   <li><b>NEVER</b> cache the Card Verification Value (CVV).</li>
 *   <li><b>NEVER</b> cache plaintext passwords or BCrypt hashes.</li>
 *   <li><b>NEVER</b> cache unmasked Social Security Numbers (SSNs).</li>
 * </ul>
 * <p>This adapter does NOT enforce sanitisation; code review is responsible
 * for catching violations. The {@link #put} JavaDoc carries an explicit
 * warning.</p>
 *
 * @see com.awsm2.carddemo.config.RedisConfig
 */
@Service
public class CacheService {

    /**
     * SLF4J logger for trace-level cache hit/miss tracking and WARN-level
     * cache-failure logging. Per AAP &sect;0.7.1 the cache-aside contract
     * degrades to a database miss on any Redis failure; those failures are
     * logged at {@code WARN} (never propagated and never escalated to
     * {@code ERROR} because they do not represent a business failure).
     */
    private static final Logger LOG = LoggerFactory.getLogger(CacheService.class);

    /**
     * The Redis key prefix that namespaces every entry produced by this
     * adapter. Format: {@code "carddemo:<namespace>:<key>"}. The prefix
     * is hard-coded (NOT externalised) so the on-the-wire key shape is a
     * stable invariant that operations can rely on for Redis CLI debugging.
     */
    private static final String KEY_PREFIX = "carddemo:";

    /**
     * Micrometer counter name for cache hits.
     *
     * <p>Per AAP &sect;0.7.2 "Spring Actuator health and metrics endpoints
     * exported to CloudWatch via Micrometer" and AAP &sect;0.7.1
     * "ElastiCache (Redis) used for account balance caching", the
     * application MUST emit observable cache-hit/miss counters so that
     * CloudWatch dashboards can graph cache effectiveness in real time.
     * The counter is tagged with the cache {@code namespace} so the
     * hit ratio can be sliced by cache scope ({@code account},
     * {@code card}, {@code discgrp}, {@code tranType}, etc.) and used to
     * tune per-scope TTLs.</p>
     *
     * <p><b>QA CP11 Finding M-2 fix:</b> the previous implementation
     * relied on programmatic {@link RedisTemplate} access without
     * registering Micrometer counters, leaving CloudWatch dashboards
     * unable to graph the cache hit ratio. This counter, paired with
     * {@link #METRIC_CACHE_MISSES} below, closes that observability gap
     * without changing the cache-aside semantics.</p>
     */
    private static final String METRIC_CACHE_HITS = "carddemo.cache.hits";

    /**
     * Micrometer counter name for cache misses. See
     * {@link #METRIC_CACHE_HITS} for the rationale.
     *
     * <p>A cache miss can arise from three distinct conditions, each of
     * which increments this counter via the same tag set:</p>
     * <ul>
     *   <li><b>Cold miss</b> &mdash; the entry was never populated, or
     *       was already evicted. This is the common case under normal
     *       cache-aside operation.</li>
     *   <li><b>Type mismatch</b> &mdash; the cached value's runtime
     *       type does not match the caller's requested type. Treated as
     *       a miss for consistency with the production
     *       {@link CacheService#get(String, String, Class)} semantics
     *       (production already treats type mismatch as a miss and
     *       returns {@link Optional#empty()}).</li>
     *   <li><b>Cache-error miss</b> &mdash; an underlying Redis
     *       {@link RuntimeException} (driver fault, connection failure,
     *       timeout) causes the cache-aside fail-open path to be
     *       exercised, returning {@link Optional#empty()} so the caller
     *       falls back to the database-of-record. Treated as a miss for
     *       consistency. A separate WARN log is emitted in this case so
     *       persistent Redis failures remain visible in CloudWatch Logs.</li>
     * </ul>
     */
    private static final String METRIC_CACHE_MISSES = "carddemo.cache.misses";

    /**
     * Micrometer tag name used to slice cache hits/misses by cache
     * namespace. Examples: {@code account}, {@code card},
     * {@code discgrp}, {@code tranType}, {@code tranCatg}, {@code xref}.
     */
    private static final String METRIC_TAG_NAMESPACE = "namespace";

    /**
     * The Spring Data Redis high-level template injected via constructor.
     * Provided by {@link com.awsm2.carddemo.config.RedisConfig#redisTemplate(org.springframework.data.redis.connection.RedisConnectionFactory)}
     * &mdash; Lettuce connection factory + Jackson JSON value serializer +
     * UTF-8 String key serializer.
     *
     * <p>The generic type {@code <String, Object>} matches the RedisConfig
     * bean exactly; callers supply a {@link Class} on read so the adapter
     * can verify the deserialised type at runtime.</p>
     */
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Default cache entry TTL (in seconds), externalised via {@code @Value}.
     * Sourced from {@code carddemo.cache.default-ttl-seconds} with a
     * default of 300 (5 minutes) per AAP &sect;0.7.1 ("TTL aligned to
     * transaction frequency" &mdash; short for high-frequency balances).
     *
     * <p>Applied by {@link #put} when the caller passes {@code null}, a
     * zero, or a negative {@link Duration}. A non-positive value would
     * otherwise produce a non-expiring cache entry, which would silently
     * drift from the database-of-record &mdash; an unacceptable failure
     * mode for a financial system.</p>
     */
    private final long defaultTtlSeconds;

    /**
     * Micrometer {@link MeterRegistry} used to register hit/miss counters
     * per cache namespace. Wired by Spring DI from the bean published by
     * {@link com.awsm2.carddemo.config.CloudWatchConfig} (which uses the
     * {@link io.micrometer.cloudwatch2.CloudWatchMeterRegistry} in
     * {@code prod}/{@code dev} and the Boot-autoconfigured
     * {@code SimpleMeterRegistry} in {@code local}/{@code test}).
     *
     * <p>Per the Micrometer best-practice, {@link Counter} handles are
     * obtained on demand via {@link Counter#builder} and the registry
     * caches them by name + tag set so subsequent calls return the same
     * meter instance without allocating duplicates.</p>
     *
     * <p>QA CP11 Finding M-2 fix: enables emission of
     * {@link #METRIC_CACHE_HITS} and {@link #METRIC_CACHE_MISSES} so
     * CloudWatch dashboards can graph cache effectiveness.</p>
     */
    private final MeterRegistry meterRegistry;

    /**
     * Constructor injection — Spring sets the {@link RedisTemplate}, the
     * default TTL, and the {@link MeterRegistry} once at startup; the
     * bean is immutable thereafter.
     *
     * <p>This adapter is registered as {@code @Service} per AAP &sect;0.7.3
     * adapter-isolation rule (AWS service integrations live in {@code adapter/}
     * classes). The {@code @Service} stereotype is a Spring-managed singleton
     * bean equivalent to {@code @Component} but semantically marks this as
     * an infrastructure-service component.</p>
     *
     * @param redisTemplate      the shared {@link RedisTemplate} bean
     *                           produced by
     *                           {@link com.awsm2.carddemo.config.RedisConfig};
     *                           never {@code null}.
     * @param defaultTtlSeconds  default cache TTL (seconds); externalised
     *                           via {@code carddemo.cache.default-ttl-seconds};
     *                           default {@code 300}.
     * @param meterRegistry      Micrometer registry for hit/miss counters
     *                           (QA CP11 M-2 fix); wired by
     *                           {@link com.awsm2.carddemo.config.CloudWatchConfig}
     *                           and never {@code null}.
     */
    public CacheService(
            RedisTemplate<String, Object> redisTemplate,
            @Value("${carddemo.cache.default-ttl-seconds:300}") long defaultTtlSeconds,
            MeterRegistry meterRegistry) {
        // Constructor injection only (AAP §0.7.3 — adapter isolation rule).
        // Net-new capability — no source COBOL equivalent. ElastiCache Redis
        // introduces cache-aside per AAP §0.7.1.
        this.redisTemplate = redisTemplate;
        // Guard against misconfiguration: a non-positive default would yield
        // non-expiring entries and silent drift from the database-of-record.
        this.defaultTtlSeconds = (defaultTtlSeconds > 0L) ? defaultTtlSeconds : 300L;
        // QA CP11 M-2 fix: MeterRegistry wired for cache hit/miss counter
        // emission. Counter.builder().register(meterRegistry) is idempotent
        // — the registry caches by name+tag — so call-site allocation is
        // safe in the hot read path.
        this.meterRegistry = meterRegistry;
    }

    // ---------------------------------------------------------------------
    // Public API — cache-aside operations
    //
    // Cache-aside (AAP §0.3.3): write path = evict; read path = get (miss)
    //                          → db → put
    // ---------------------------------------------------------------------

    /**
     * Retrieve a value from the cache.
     *
     * <p>Returns {@link Optional#empty()} on miss, on cached {@code null},
     * on type mismatch, or on any Redis driver failure (cache-aside
     * pattern: failures degrade gracefully to a database miss).</p>
     *
     * <p><b>Cache-aside contract (AAP &sect;0.3.3):</b> on
     * {@link Optional#empty()} the caller is expected to load from the
     * database-of-record and then invoke
     * {@link #put(String, String, Object, Duration)} to populate the
     * cache for subsequent reads.</p>
     *
     * <p>The {@code type} argument supplies the expected runtime type and
     * enables both compile-time generic inference and runtime
     * {@code instanceof} verification — a cache entry whose
     * deserialised type does not match {@code type} returns
     * {@link Optional#empty()} rather than throwing
     * {@link ClassCastException}.</p>
     *
     * @param namespace logical scope (e.g., {@code "account"},
     *                  {@code "card"}, {@code "discgrp"}). Must not be
     *                  {@code null} or blank.
     * @param key       business key (e.g., zero-padded account ID). Must
     *                  not be {@code null} or blank.
     * @param type      expected runtime type for safe casting; must not be
     *                  {@code null}.
     * @param <T>       value type
     * @return {@link Optional} containing the cached value, or
     *         {@link Optional#empty()} on miss, type mismatch, or error.
     * @throws IllegalArgumentException if {@code namespace}, {@code key},
     *         or {@code type} is null/blank.
     */
    public <T> Optional<T> get(String namespace, String key, Class<T> type) {
        // Cache-aside (AAP §0.3.3): write path = evict; read path = get (miss)
        //                          → db → put
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        String redisKey = buildKey(namespace, key);
        try {
            ValueOperations<String, Object> ops = redisTemplate.opsForValue();
            Object cached = ops.get(redisKey);
            if (cached == null) {
                LOG.trace("Cache miss key={}", redisKey);
                // QA CP11 M-2 fix: emit carddemo.cache.misses tagged by
                // namespace so CloudWatch dashboards can graph the cold-miss
                // rate per cache scope (AAP §0.7.2 observability).
                incrementMissCounter(namespace);
                return Optional.empty();
            }
            if (!type.isInstance(cached)) {
                // Defensive: a type mismatch suggests a serializer or schema
                // change. Treat as miss so the caller refreshes from the DB,
                // and surface a WARN for ops investigation.
                LOG.warn("Cache hit with type mismatch key={} expected={} actual={}",
                        redisKey, type.getSimpleName(), cached.getClass().getSimpleName());
                // QA CP11 M-2 fix: type-mismatch behaves as a miss for the
                // caller; emit the miss counter so dashboards reflect the
                // observed cache-effectiveness from the caller's
                // perspective (AAP §0.7.2 observability).
                incrementMissCounter(namespace);
                return Optional.empty();
            }
            LOG.trace("Cache hit key={}", redisKey);
            // QA CP11 M-2 fix: emit carddemo.cache.hits tagged by namespace
            // so CloudWatch dashboards can graph the per-scope hit ratio.
            incrementHitCounter(namespace);
            return Optional.of(type.cast(cached));
        } catch (RuntimeException e) {
            // AAP §0.7.1: cache failures degrade to database miss; never
            // propagate Redis exceptions to the business flow.
            LOG.warn("Cache get failed key={} cause={} — degrading to database miss",
                    redisKey, e.getMessage());
            // QA CP11 M-2 fix: a cache-error degrade is observed as a miss
            // by the caller (returns Optional.empty()) so it is counted as
            // a miss for consistency with the caller-observed semantics.
            // Persistent Redis failures remain visible via the WARN log
            // emitted above and via the standard Spring Boot Redis health
            // indicator.
            incrementMissCounter(namespace);
            return Optional.empty();
        }
    }

    /**
     * Store a value in the cache with a TTL.
     *
     * <p>Failures are logged at {@code WARN} but do NOT propagate
     * (cache-aside fail-open per AAP &sect;0.7.1: cache-write failures
     * must never block the calling business flow).</p>
     *
     * <p><b>PCI-DSS warning (AAP &sect;0.7.2):</b> callers MUST sanitise
     * {@code value} before invocation. NEVER cache:</p>
     * <ul>
     *   <li>The full Primary Account Number (PAN) — cache only a masked
     *       form ({@code ************XXXX}) or omit the field entirely.</li>
     *   <li>The Card Verification Value (CVV / CVV2 / CVC2 / CID). Note
     *       that CVV is no longer persisted on the
     *       {@link com.awsm2.carddemo.domain.Card} entity as of QA
     *       finding DB1 (PCI-DSS v4.0 Requirement 3.2), so it should
     *       never appear in cacheable payloads in practice; this
     *       guard-rail is retained as defense-in-depth.</li>
     *   <li>Plaintext passwords or BCrypt password hashes.</li>
     *   <li>Unmasked Social Security Numbers (SSNs).</li>
     * </ul>
     * <p>This adapter does NOT enforce sanitisation. Code review is
     * responsible for catching violations.</p>
     *
     * <p>If {@code ttl} is {@code null}, zero, or negative, the configured
     * default ({@code carddemo.cache.default-ttl-seconds:300}) is applied.
     * The fallback prevents a misconfiguration from producing non-expiring
     * cache entries that would silently drift from the database-of-record.</p>
     *
     * @param namespace logical scope. Must not be {@code null} or blank.
     * @param key       business key. Must not be {@code null} or blank.
     * @param value     value to cache. MUST NOT contain full PAN, CVV, or
     *                  unmasked SSN per AAP &sect;0.7.2 (PCI-DSS).
     * @param ttl       time-to-live; if {@code null}, zero, or negative,
     *                  the default TTL is applied.
     * @param <T>       value type
     * @throws IllegalArgumentException if {@code namespace} or {@code key}
     *         is null/blank.
     */
    public <T> void put(String namespace, String key, T value, Duration ttl) {
        // Cache-aside (AAP §0.3.3): write path = evict; read path = get (miss)
        //                          → db → put
        // AAP §0.7.2: callers MUST NOT cache full PAN, CVV, or unmasked
        //              SSN (PCI-DSS). This adapter does not enforce
        //              sanitisation — code review is responsible.
        String redisKey = buildKey(namespace, key);
        Duration effectiveTtl = (ttl == null || ttl.isZero() || ttl.isNegative())
                ? Duration.ofSeconds(defaultTtlSeconds)
                : ttl;
        try {
            ValueOperations<String, Object> ops = redisTemplate.opsForValue();
            ops.set(redisKey, value, effectiveTtl);
            LOG.trace("Cache put key={} ttl={}", redisKey, effectiveTtl);
        } catch (RuntimeException e) {
            // Fail-open: never propagate Redis exceptions per AAP §0.7.1.
            // A cache write failure does not invalidate a successful DB write.
            LOG.warn("Cache put failed key={} cause={} — request continues without caching",
                    redisKey, e.getMessage());
            // do NOT throw — cache failures must not break the calling flow.
        }
    }

    /**
     * Remove a cached entry.
     *
     * <p>Used by writers (e.g., {@code AccountUpdateService},
     * {@code CardUpdateService}, {@code BillPaymentService}) to invalidate
     * stale cached values immediately after a successful database write —
     * see AAP &sect;0.3.3 cache-aside pattern: writer evicts, next reader
     * re-populates.</p>
     *
     * <p>Failures are logged at {@code WARN} but do NOT propagate. A
     * failed eviction is non-fatal because:</p>
     * <ol>
     *   <li>The caller has already successfully written the
     *       database-of-record (which is the authoritative store).</li>
     *   <li>The cache entry will eventually expire via its TTL.</li>
     * </ol>
     *
     * @param namespace logical scope. Must not be {@code null} or blank.
     * @param key       business key. Must not be {@code null} or blank.
     * @throws IllegalArgumentException if {@code namespace} or {@code key}
     *         is null/blank.
     */
    public void evict(String namespace, String key) {
        // Cache-aside (AAP §0.3.3): write path = evict; read path = get (miss)
        //                          → db → put
        String redisKey = buildKey(namespace, key);
        try {
            Boolean deleted = redisTemplate.delete(redisKey);
            LOG.trace("Cache evict key={} deleted={}", redisKey, deleted);
        } catch (RuntimeException e) {
            // Fail-open: an eviction failure is non-fatal because the entry
            // will eventually expire via TTL and the database-of-record is
            // already updated by the caller.
            LOG.warn("Cache evict failed key={} cause={}", redisKey, e.getMessage());
        }
    }

    /**
     * Evict <em>every</em> key in a namespace. Implemented via the
     * Spring Data Redis {@code keys(pattern)} API for simplicity.
     *
     * <p><b>NOT for high-frequency use:</b> the primary use case is admin
     * or test reset. Production code paths should use {@link #evict} on
     * specific keys rather than wholesale namespace eviction. The
     * implementation calls {@code redisTemplate.keys("<namespace>:*")} —
     * which on large Redis databases can be expensive — followed by
     * {@code delete(keys)}.</p>
     *
     * <p>For a future hardening pass, this method may be enhanced to use
     * a {@code SCAN}-based cursor via {@code redisTemplate.execute(...)};
     * the AAP &sect;0.7.3 Minimal Change Clause permits the simpler
     * {@code keys()} here because the method is admin-only and not in
     * the hot path.</p>
     *
     * <p>Failures are logged at {@code WARN} but do NOT propagate
     * (cache-aside fail-open per AAP &sect;0.7.1).</p>
     *
     * @param namespace logical scope to evict (e.g., {@code "account"}).
     *                  Must not be {@code null} or blank.
     * @throws IllegalArgumentException if {@code namespace} is null/blank.
     */
    public void evictAll(String namespace) {
        // Cache-aside (AAP §0.3.3): admin-only convenience for namespace-wide
        // invalidation (e.g., reference-data reload after Flyway migration).
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be null/blank");
        }
        String pattern = KEY_PREFIX + namespace + ":*";
        try {
            // SCAN-based deletion would be preferable in production but the
            // Minimal Change Clause permits the simpler keys() here since
            // this method is admin/test-only and not in the hot read path.
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                LOG.info("Cache namespace-wide evict namespace={} count={}",
                        namespace, keys.size());
            } else {
                LOG.trace("Cache namespace-wide evict namespace={} count=0", namespace);
            }
        } catch (RuntimeException e) {
            // Fail-open: never propagate Redis exceptions per AAP §0.7.1.
            LOG.warn("Cache evictAll failed namespace={} cause={}",
                    namespace, e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------

    /**
     * Increment the {@value #METRIC_CACHE_HITS} Micrometer counter for the
     * supplied cache namespace.
     *
     * <p>QA CP11 Finding M-2 fix. Emitted on every successful cache read in
     * {@link #get(String, String, Class)} where the cached value matches the
     * requested type. Tagged by {@code namespace} so CloudWatch dashboards
     * can compute per-scope hit ratios (e.g., {@code account} vs.
     * {@code discgrp}) and operations can tune per-scope TTLs.</p>
     *
     * <p>The counter is registered on demand &mdash; {@link Counter#builder}
     * + {@link Counter.Builder#register(MeterRegistry)} returns the existing
     * counter when one with the same name and tag set is already
     * registered, so call-site allocation in the hot read path remains
     * O(1) lookup after the first registration per namespace.</p>
     *
     * <p>The method swallows any unexpected {@link RuntimeException} from
     * the metrics layer so a meter-registry fault never propagates to the
     * caller (cache-aside fail-open per AAP &sect;0.7.1).</p>
     *
     * @param namespace the cache namespace tag value; never {@code null} or
     *                  blank in normal operation (already validated by
     *                  {@link #buildKey} earlier in the call chain)
     */
    private void incrementHitCounter(String namespace) {
        try {
            // Counter.builder().register(meterRegistry).increment() is the
            // canonical idiom; the registry caches counters by name + tag
            // so the lookup is O(1) after the first invocation per
            // namespace. No need to cache the Counter reference in a field.
            Counter.builder(METRIC_CACHE_HITS)
                    .tag(METRIC_TAG_NAMESPACE, namespace)
                    .description("Cache hits per namespace (QA CP11 M-2; AAP §0.7.2 observability)")
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException e) {
            // Meter-registry faults must never propagate to the business
            // flow. Logged at TRACE because metrics emission is best-effort
            // and any persistent failure would already surface via
            // /actuator/health.
            LOG.trace("Cache hit metric emission failed namespace={} cause={}",
                    namespace, e.getMessage());
        }
    }

    /**
     * Increment the {@value #METRIC_CACHE_MISSES} Micrometer counter for the
     * supplied cache namespace.
     *
     * <p>QA CP11 Finding M-2 fix. Emitted from
     * {@link #get(String, String, Class)} on three distinct miss conditions:
     * a cold miss (no cached value), a type-mismatch miss (cached value's
     * type does not match the caller's requested type), and a cache-error
     * miss (Redis driver fault forcing fail-open). All three increment the
     * same counter for consistency with the caller-observed semantics
     * (each returns {@link Optional#empty()} to the caller).</p>
     *
     * <p>See {@link #incrementHitCounter(String)} for the rationale on
     * call-site Counter.builder() allocation and exception swallowing.</p>
     *
     * @param namespace the cache namespace tag value; never {@code null} or
     *                  blank in normal operation (already validated by
     *                  {@link #buildKey} earlier in the call chain)
     */
    private void incrementMissCounter(String namespace) {
        try {
            Counter.builder(METRIC_CACHE_MISSES)
                    .tag(METRIC_TAG_NAMESPACE, namespace)
                    .description("Cache misses per namespace (QA CP11 M-2; AAP §0.7.2 observability)")
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException e) {
            LOG.trace("Cache miss metric emission failed namespace={} cause={}",
                    namespace, e.getMessage());
        }
    }

    /**
     * Construct a namespaced Redis key.
     *
     * <p>Format: {@code "carddemo:<namespace>:<key>"}. The
     * {@code "carddemo:"} prefix isolates this application from any other
     * tenant on a shared Redis cluster. The namespace separates logical
     * scopes (e.g., {@code account}, {@code card}, {@code discgrp}); the
     * business key uniquely identifies the entry within that scope.</p>
     *
     * <p>Both {@code namespace} and {@code key} are validated for
     * null/blank — a blank value would produce an ambiguous key
     * ({@code "carddemo::<key>"} or {@code "carddemo:<namespace>:"}) that
     * could collide with other entries.</p>
     *
     * @param namespace logical scope
     * @param key       business key
     * @return the formatted Redis key
     * @throws IllegalArgumentException if {@code namespace} or {@code key}
     *         is null/blank.
     */
    private String buildKey(String namespace, String key) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be null/blank");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be null/blank");
        }
        // KEY_PREFIX = "carddemo:" — namespaces CardDemo cache entries
        // within a shared Redis cluster (AAP §0.7.1).
        return KEY_PREFIX + namespace + ":" + key;
    }
}
