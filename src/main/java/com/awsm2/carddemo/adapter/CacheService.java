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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * ElastiCache (Redis) cache-aside adapter — the sole entry point for every
 * cache read/write in the CardDemo Java target.
 *
 * <p>Per AAP &sect;0.7.1 ("ElastiCache (Redis) used for account balance
 * caching &mdash; cache-aside pattern with TTL aligned to transaction
 * frequency") and AAP &sect;0.3.3 (Cache-Aside design pattern), this adapter
 * implements the classic <em>read-through / write-around</em> cache-aside
 * topology against Amazon ElastiCache Redis (or a local Redis 7 instance
 * under {@code docker-compose up} in the local profile):</p>
 *
 * <ol>
 *   <li><b>Read:</b> the service calls {@link #getAccount(long, Supplier)}
 *       with a {@link Supplier} that knows how to load the account from RDS
 *       on miss. If the key is present in Redis, the cached value is
 *       returned directly. If absent (cache miss), the {@code Supplier} is
 *       invoked, its result is stored in Redis with the configured TTL,
 *       and returned.</li>
 *   <li><b>Write (eviction):</b> on every successful account write in the
 *       service layer (account update, balance debit/credit), the caller
 *       invokes {@link #evictAccount(long)} so the next read repopulates
 *       from the database-of-record. This is the canonical
 *       <em>write-around</em> variant of cache-aside &mdash; the database
 *       is the source of truth, the cache only mirrors it.</li>
 * </ol>
 *
 * <h2>Replaces (AAP &sect;0.6.5 cache wiring)</h2>
 * <p>Net-new capability &mdash; the COBOL source had no caching layer
 * (every VSAM read traversed the file directly). High-frequency account
 * balance reads on the AWS target would saturate RDS Multi-AZ read I/O
 * without a near-cache; ElastiCache absorbs those reads.</p>
 *
 * <h2>Key format</h2>
 * <p>Deterministic key format: {@link #ACCOUNT_KEY_FORMAT} =
 * {@code "acct:%011d"}. Account IDs are zero-padded to 11 digits to match
 * the COBOL {@code PIC 9(11)} {@code ACCT-ID} format defined in
 * {@code app/cpy/CVACT01Y.cpy:L6} and the Kafka partition key produced by
 * {@link KafkaEventPublisher}, ensuring a single canonical representation
 * across the entire stack.</p>
 *
 * <h2>Fail-open behavior (AAP &sect;0.7.1)</h2>
 * <p>Redis outages MUST NOT block business flows. The adapter wraps every
 * Redis call in a try/catch and on {@link RuntimeException}:</p>
 * <ul>
 *   <li>Logs the failure at {@code WARN} (never {@code ERROR}) with cause
 *       and key metadata.</li>
 *   <li>For reads: returns {@code null} (or the {@link Supplier} fallback
 *       in the cache-aside method) so the caller can fall through to the
 *       database-of-record.</li>
 *   <li>For evictions: silently absorbs the exception &mdash; the next
 *       read will still see fresh data because the TTL eventually expires
 *       the stale entry, and the service that wrote the new value already
 *       updated the database-of-record.</li>
 * </ul>
 * <p>This fail-open posture is mandatory: a degraded cache is preferable
 * to a cascading failure that takes down account services.</p>
 *
 * <h2>Thread safety</h2>
 * <p>{@link RedisTemplate} is thread-safe and intended to be shared. The
 * adapter holds a single template reference and exposes only stateless
 * methods.</p>
 *
 * @see com.awsm2.carddemo.config.RedisConfig
 */
@Component
public class CacheService {

    private static final Logger LOG = LoggerFactory.getLogger(CacheService.class);

    /**
     * Deterministic Redis key format for account aggregates. {@code "acct:"}
     * is the namespace prefix; {@code %011d} matches the COBOL {@code PIC
     * 9(11)} {@code ACCT-ID} format ({@code app/cpy/CVACT01Y.cpy:L6}) and the
     * 11-digit zero-padded account ID used as the Kafka partition key.
     */
    static final String ACCOUNT_KEY_FORMAT = "acct:%011d";

    /**
     * Spring's high-level Redis abstraction. The bean is produced by
     * {@code com.awsm2.carddemo.config.RedisConfig} and configured with
     * Lettuce as the underlying connection factory, Jackson polymorphic
     * JSON serialization for values, and conditional SSL (production +
     * staging) / plaintext (local) transport.
     */
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Default TTL for account-balance cache entries. Sourced from
     * {@code carddemo.cache.account-balance-ttl-seconds} (with a fallback
     * to {@code carddemo.cache.ttl-seconds}); 300 seconds (5 minutes)
     * matches the transaction-frequency window per AAP &sect;0.7.1 ("TTL
     * aligned to transaction frequency").
     */
    private final Duration accountTtl;

    /**
     * Constructor injection only &mdash; Spring sets {@code redisTemplate}
     * once at startup; the bean is then immutable.
     *
     * @param redisTemplate      shared {@link RedisTemplate} bean from
     *                           {@code RedisConfig}; never {@code null}
     * @param accountTtlSeconds  TTL in seconds for account entries
     */
    public CacheService(RedisTemplate<String, Object> redisTemplate,
                        @Value("${carddemo.cache.account-balance-ttl-seconds:300}")
                        long accountTtlSeconds) {
        // Replaces: nothing — net-new capability per AAP §0.7.1.
        this.redisTemplate = Objects.requireNonNull(redisTemplate,
                "redisTemplate must not be null");
        // A non-positive TTL is treated as 300s (the AAP default) to prevent
        // a misconfiguration from creating non-expiring cache entries that
        // would silently drift from the database-of-record indefinitely.
        long ttl = (accountTtlSeconds > 0) ? accountTtlSeconds : 300L;
        this.accountTtl = Duration.ofSeconds(ttl);
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /**
     * Reads the cached account aggregate for the supplied {@code accountId},
     * loading and populating from the supplied {@link Supplier} on miss.
     *
     * <p>This is the canonical cache-aside read entry point. The behavior is:</p>
     * <ol>
     *   <li>Attempt {@code GET acct:%011d}; on hit, return the cached value.</li>
     *   <li>On miss, invoke {@code loader.get()} (which typically issues an
     *       RDS lookup via {@code AccountRepository.findById}).</li>
     *   <li>If the loader returns a non-null value, {@code SET acct:%011d}
     *       with the configured TTL.</li>
     *   <li>Return the loader's result regardless of cache-store success
     *       (a Redis write failure does not invalidate a successful DB read).</li>
     * </ol>
     *
     * <p>On any Redis transport failure, the method logs at {@code WARN} and
     * falls through to the loader; the caller cannot distinguish a cache
     * miss from a cache failure, which is the desired fail-open semantics.</p>
     *
     * @param accountId 11-digit COBOL {@code ACCT-ID}
     * @param loader    function that loads the account from the source of
     *                  truth on miss; must not be {@code null}; may return
     *                  {@code null} if the account does not exist (in which
     *                  case nothing is cached)
     * @return the account aggregate, or {@code null} if the loader returns
     *         {@code null} (account not found)
     * @throws NullPointerException if {@code loader} is {@code null}
     */
    public Object getAccount(long accountId, Supplier<Object> loader) {
        Objects.requireNonNull(loader, "loader must not be null");
        String key = accountKey(accountId);

        // Step 1 — try the cache. Any failure here falls through to the loader.
        Object cached = null;
        try {
            cached = redisTemplate.opsForValue().get(key);
        } catch (RuntimeException e) {
            // Fail-open: log + fall through. Never propagate Redis exceptions
            // up to the business flow per AAP §0.7.1.
            LOG.warn("Redis GET failed; falling back to source of truth key={} cause={}",
                    key, e.getMessage());
        }
        if (cached != null) {
            LOG.debug("Redis GET HIT key={}", key);
            return cached;
        }

        // Step 2 — cache miss (or failure): invoke the loader.
        LOG.debug("Redis GET MISS key={} — invoking loader", key);
        Object loaded = loader.get();

        // Step 3 — populate on hit-from-source. Loader-null (record not
        // found) is deliberately NOT cached: caching null values would
        // require negative-cache semantics and TTL tuning that the COBOL
        // source did not have, and is outside the Minimal Change Clause.
        if (loaded != null) {
            try {
                redisTemplate.opsForValue().set(key, loaded, accountTtl);
                LOG.debug("Redis SET key={} ttlSeconds={}",
                        key, accountTtl.getSeconds());
            } catch (RuntimeException e) {
                // Fail-open on writes too — a cache-write failure does not
                // invalidate a successful DB read.
                LOG.warn("Redis SET failed; cache will repopulate on next read key={} cause={}",
                        key, e.getMessage());
            }
        }
        return loaded;
    }

    /**
     * Variant of {@link #getAccount(long, Supplier)} that returns
     * {@link Optional}, for callers preferring fluent null-handling.
     *
     * @param accountId 11-digit COBOL {@code ACCT-ID}
     * @param loader    function that loads the account on miss
     * @return {@link Optional} wrapping the value, or empty if the loader
     *         returned {@code null}
     */
    public Optional<Object> findAccount(long accountId, Supplier<Object> loader) {
        return Optional.ofNullable(getAccount(accountId, loader));
    }

    /**
     * Evicts the cached account aggregate for {@code accountId}.
     *
     * <p>Callers MUST invoke this method after every successful account
     * mutation (balance debit/credit, account update, optimistic lock
     * resolution) so the next read repopulates from the database-of-record.
     * Failure to call {@code evictAccount} after a write produces stale
     * reads up to {@code accountTtl}.</p>
     *
     * <p>A Redis transport failure during eviction is logged at {@code WARN}
     * and silently absorbed &mdash; per the fail-open contract, a degraded
     * cache must never block the calling service from acknowledging the
     * successful write.</p>
     *
     * @param accountId 11-digit COBOL {@code ACCT-ID}
     */
    public void evictAccount(long accountId) {
        String key = accountKey(accountId);
        try {
            Boolean deleted = redisTemplate.delete(key);
            LOG.debug("Redis DEL key={} existed={}", key, deleted);
        } catch (RuntimeException e) {
            // Fail-open: an eviction failure is non-fatal because the entry
            // will eventually expire via TTL and the caller has already
            // successfully updated the database-of-record.
            LOG.warn("Redis DEL failed; entry will expire via TTL key={} cause={}",
                    key, e.getMessage());
        }
    }

    /**
     * Direct {@code SET} for callers that already hold the canonical value
     * (e.g., immediately after a successful database write).
     *
     * @param accountId 11-digit COBOL {@code ACCT-ID}
     * @param value     value to cache; must not be {@code null}
     */
    public void putAccount(long accountId, Object value) {
        Objects.requireNonNull(value, "value must not be null");
        String key = accountKey(accountId);
        try {
            redisTemplate.opsForValue().set(key, value, accountTtl);
            LOG.debug("Redis SET (explicit) key={} ttlSeconds={}",
                    key, accountTtl.getSeconds());
        } catch (RuntimeException e) {
            // Fail-open on writes: never block the caller for a cache write.
            LOG.warn("Redis SET (explicit) failed key={} cause={}",
                    key, e.getMessage());
        }
    }

    /**
     * Builds the deterministic Redis key for the supplied account ID. The
     * key shape matches {@link #ACCOUNT_KEY_FORMAT} and is consistent with
     * the partition key generated by {@link KafkaEventPublisher}, ensuring
     * the same canonical 11-digit zero-padded representation is used across
     * the cache, the Kafka topic, and the application logs.
     *
     * @param accountId 11-digit COBOL {@code ACCT-ID}
     * @return the formatted Redis key (e.g., {@code "acct:00000012345"})
     */
    public static String accountKey(long accountId) {
        return String.format(ACCOUNT_KEY_FORMAT, accountId);
    }
}
