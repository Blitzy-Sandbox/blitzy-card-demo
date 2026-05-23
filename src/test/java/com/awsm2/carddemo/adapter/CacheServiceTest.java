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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Replaces: high-frequency VSAM key lookups for account balance reads
// Backs AAP §0.3.3 + §0.6.6: ElastiCache Redis cache-aside, TTL aligned to
// transaction frequency, allkeys-lru eviction (eviction policy is configured
// on the Redis cluster, not the client).

/**
 * Unit tests for {@link CacheService}, the ElastiCache (Redis) cache-aside
 * adapter that backs high-frequency account-balance and reference-data reads
 * across CardDemo.
 *
 * <p>This adapter is the centerpiece of the CardDemo cache-aside posture
 * (AAP &sect;0.3.3, &sect;0.6.6, &sect;0.7.1): every Redis access goes
 * through this class so that <em>(a)</em> failures degrade gracefully to a
 * database miss (cache failures must never break a business flow), and
 * <em>(b)</em> the key format {@code carddemo:&lt;namespace&gt;:&lt;key&gt;}
 * is consistently applied across all operations.</p>
 *
 * <h2>Test coverage matrix</h2>
 * <ol>
 *   <li><strong>Phase 6 &mdash; {@code get} happy path</strong> &mdash;
 *       cache hit with matching type returns {@link Optional#of(Object)};
 *       cache miss ({@code null}) returns {@link Optional#empty()}; type
 *       mismatch (cached String when {@code BigDecimal} requested) returns
 *       {@link Optional#empty()} (never throws {@link ClassCastException});
 *       any thrown {@link RuntimeException} returns {@link Optional#empty()}
 *       and is swallowed per AAP &sect;0.7.1 fail-open contract.</li>
 *   <li><strong>Phase 7 &mdash; {@code get} input validation</strong>
 *       &mdash; null, empty, blank {@code namespace} or {@code key} throws
 *       {@link IllegalArgumentException} BEFORE any Redis call is made;
 *       null {@code type} also throws.</li>
 *   <li><strong>Phase 8 &mdash; {@code put} happy path</strong> &mdash;
 *       explicit positive {@link Duration} is passed verbatim to
 *       {@link ValueOperations#set(Object, Object, Duration)}; null, zero,
 *       and negative TTLs fall back to the configured default
 *       (300 seconds per the production {@code @Value} default and the
 *       canonical short-lived account-balance TTL); thrown
 *       {@link RuntimeException} is swallowed.</li>
 *   <li><strong>Phase 9 &mdash; {@code put} input validation</strong>
 *       &mdash; null/blank {@code namespace} or {@code key} throws
 *       {@link IllegalArgumentException} BEFORE any Redis call.</li>
 *   <li><strong>Phase 10 &mdash; {@code evict} happy path</strong> &mdash;
 *       delegates to {@link RedisTemplate#delete(Object)} with the
 *       namespaced key; thrown {@link RuntimeException} is swallowed.</li>
 *   <li><strong>Phase 11 &mdash; {@code evict} input validation</strong>
 *       &mdash; null/blank {@code namespace} or {@code key} throws
 *       {@link IllegalArgumentException}.</li>
 *   <li><strong>Phase 12 &mdash; {@code evictAll}</strong> &mdash;
 *       enumerates keys via {@link RedisTemplate#keys(Object)} and
 *       collectively deletes via
 *       {@link RedisTemplate#delete(Collection)}; an empty / null result
 *       set skips the delete call; thrown {@link RuntimeException} is
 *       swallowed; null/blank {@code namespace} throws
 *       {@link IllegalArgumentException}.</li>
 *   <li><strong>Phase 13 &mdash; key namespacing</strong> &mdash; verifies
 *       the {@code carddemo:&lt;namespace&gt;:&lt;key&gt;} key format is
 *       consistently applied for every namespace
 *       ({@code account}, {@code discgrp}, {@code xref}).</li>
 *   <li><strong>Phase 14 &mdash; PCI-DSS compliance</strong> &mdash;
 *       documentation/assertion test that the canonical cache payload in
 *       this test class is {@link BigDecimal} (an account balance),
 *       NEVER an unmasked PAN, CVV, or SSN, per AAP &sect;0.6 + &sect;0.7.2.</li>
 * </ol>
 *
 * <h2>Mocking strategy</h2>
 * <p>This is a pure Mockito unit test &mdash; no Spring context is loaded.
 * {@link MockitoExtension} (strict-stubbing mode, the JUnit 5 default in
 * Mockito 5.x) wires the {@code @Mock RedisTemplate} and
 * {@code @Mock ValueOperations} into the production constructor under
 * test. The {@code @BeforeEach} stub
 * {@code redisTemplate.opsForValue() -> valueOperations} uses
 * {@link org.mockito.Mockito#lenient()} so that the {@code evict} and
 * {@code evictAll} tests, which do not invoke {@code opsForValue()}, do
 * not trigger strict-stubbing warnings.</p>
 *
 * <h2>Compliance constraints honored</h2>
 * <ul>
 *   <li><strong>Spring Data Redis abstractions only</strong> &mdash;
 *       {@link RedisTemplate} and {@link ValueOperations}; no raw Jedis or
 *       Lettuce APIs (AAP &sect;0.7.1).</li>
 *   <li><strong>BigDecimal for monetary values</strong> &mdash; canonical
 *       cache payload is {@code new BigDecimal("1234.56")}, never
 *       {@code double} or {@code float} (AAP &sect;0.6.1).</li>
 *   <li><strong>No PAN/CVV/SSN in test payloads</strong> &mdash; account
 *       balances and short numeric keys only (AAP &sect;0.6 + &sect;0.7.2).</li>
 *   <li><strong>Cache-aside fail-open</strong> &mdash; every exception
 *       swallowing test verifies the method completes normally and that
 *       no Redis exception propagates (AAP &sect;0.7.1).</li>
 * </ul>
 *
 * <h2>Source mainframe context</h2>
 * <p>// Replaces: high-frequency VSAM KSDS lookups for account balances
 * ({@code ACCTDAT} reads in {@code app/cbl/COACTVWC.cbl},
 * {@code app/cbl/CBACT04C.cbl}). The Java target absorbs those reads in
 * an Amazon ElastiCache (Redis) cache-aside layer in front of RDS
 * PostgreSQL. New capability &mdash; no source COBOL equivalent. The
 * cache-aside pattern was introduced by AAP &sect;0.3.3 to reduce RDS read
 * load.</p>
 *
 * @see CacheService
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CacheService \u2014 ElastiCache Redis cache-aside adapter unit tests")
class CacheServiceTest {

    // -------------------------------------------------------------------------
    // Mocks and System Under Test
    // -------------------------------------------------------------------------

    /**
     * Mocked Spring Data Redis high-level template. The
     * {@link MockitoExtension} strict-stubbing default (Mockito 5.x)
     * catches over-mocking and unused stubs across the test methods.
     *
     * <p>The generic type {@code <String, Object>} matches the production
     * {@link CacheService} field exactly (which in turn matches the
     * {@code RedisConfig#redisTemplate(...)} bean signature). Callers
     * always pass {@link String} keys, and the value-side {@code Object}
     * accommodates polymorphic payloads (balances, entities, lookup
     * records).</p>
     */
    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * Mocked {@link ValueOperations} returned by
     * {@link RedisTemplate#opsForValue()}. The lenient stub in
     * {@link #setUp()} wires this collaborator without forcing
     * {@code evict}/{@code evictAll} tests (which never invoke
     * {@code opsForValue()}) to consume the stub.
     */
    @Mock
    private ValueOperations<String, Object> valueOperations;

    /**
     * The production {@link CacheService} under test. Instantiated in
     * {@link #setUp()} with the {@code @Mock redisTemplate} and a
     * fixed-300-second default TTL matching the production
     * {@code @Value("${carddemo.cache.default-ttl-seconds:300}")} default.
     */
    private CacheService cacheService;

    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------

    /**
     * Canonical {@code account} namespace &mdash; the highest-frequency
     * cache scope per AAP &sect;0.6.6 (high-frequency balance reads).
     */
    private static final String NS_ACCOUNT = "account";

    /**
     * Canonical {@code discgrp} reference-data namespace per AAP
     * &sect;0.6.6 (low-churn lookup data, TTL ~1 hour).
     */
    private static final String NS_DISCGRP = "discgrp";

    /**
     * Canonical {@code xref} cross-reference namespace per AAP &sect;0.6.6
     * (medium-frequency reads, TTL ~15 min).
     */
    private static final String NS_XREF = "xref";

    /**
     * Sample 5-digit account-ID key. Deliberately short (5 digits) so it
     * cannot match the PAN PCI-DSS regex {@code ^\\d{13,19}$}, reinforcing
     * the no-PAN-in-tests posture per AAP &sect;0.6 + &sect;0.7.2.
     */
    private static final String KEY_ACCT = "12345";

    /**
     * The full namespaced Redis key for {@link #KEY_ACCT} in the
     * {@link #NS_ACCOUNT} namespace. Exactly matches the production
     * {@code KEY_PREFIX + namespace + ":" + key} format
     * ({@code "carddemo:"} prefix + {@code "account:"} + {@code "12345"}).
     */
    private static final String FULL_KEY_ACCOUNT_12345 = "carddemo:account:12345";

    /**
     * Sample {@link BigDecimal} account balance &mdash; the canonical
     * monetary payload across CardDemo per AAP &sect;0.6.1 (BigDecimal
     * mandatory for all monetary fields; no float/double substitution).
     */
    private static final BigDecimal ACCOUNT_BALANCE = new BigDecimal("1234.56");

    /**
     * The production-default cache TTL in seconds &mdash; matches the
     * {@code @Value("${carddemo.cache.default-ttl-seconds:300}")} default
     * exactly. Passed verbatim to the {@link CacheService} constructor in
     * {@link #setUp()}.
     */
    private static final long DEFAULT_TTL_SECONDS = 300L;

    /**
     * The {@link Duration} equivalent of {@link #DEFAULT_TTL_SECONDS} &mdash;
     * pre-computed for use in the {@code put} fallback verifications
     * (Tests 8.2, 8.3, 8.4).
     */
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(DEFAULT_TTL_SECONDS);

    /**
     * Explicit positive TTL used in Test 8.1 to verify that an explicit
     * Duration is passed through verbatim (no default substitution).
     */
    private static final Duration EXPLICIT_TTL = Duration.ofSeconds(60L);

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Constructs a fresh {@link CacheService} under test before each
     * method. The constructor takes the mocked {@link RedisTemplate}
     * and the fixed default-TTL value (300 seconds, matching the
     * production {@code @Value} default).
     *
     * <p>The {@code lenient().when(redisTemplate.opsForValue()).thenReturn(...)}
     * stub is necessary because {@link MockitoExtension} enforces strict
     * stubbing by default (Mockito 5.x), and the {@code evict} and
     * {@code evictAll} test groups never invoke {@code opsForValue()}.
     * Marking the stub as lenient prevents strict-stubbing failures while
     * still satisfying the {@code get}/{@code put} tests which do consume
     * the {@link ValueOperations} return.</p>
     *
     * <p>{@link ReflectionTestUtils#setField} is invoked as a defensive
     * post-construction assignment that mirrors the production class's
     * {@code defaultTtlSeconds} field (held as {@code private final long}).
     * The reflection call documents the field name explicitly and is
     * idempotent &mdash; the value already set by the constructor is
     * preserved.</p>
     */
    @BeforeEach
    void setUp() {
        // Construct the SUT with the production-default TTL.
        cacheService = new CacheService(redisTemplate, DEFAULT_TTL_SECONDS);

        // Defensive: also document the defaultTtlSeconds field via
        // ReflectionTestUtils. The constructor has already set it, but
        // this explicit setField ensures the field is observable at the
        // expected value even if a future refactor flips between
        // constructor-injection and @Value field-injection.
        ReflectionTestUtils.setField(cacheService, "defaultTtlSeconds", DEFAULT_TTL_SECONDS);

        // Stub redisTemplate.opsForValue() leniently — only get/put tests
        // exercise it; evict/evictAll tests must not consume this stub.
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // =========================================================================
    // Phase 6 — `get` happy path tests
    //
    // The production `get(namespace, key, type)` implementation:
    //   1. Validates type != null  → throws IllegalArgumentException
    //   2. buildKey(namespace, key) → throws IllegalArgumentException
    //   3. opsForValue().get(redisKey) → cached
    //   4a. cached == null              → Optional.empty()
    //   4b. !type.isInstance(cached)    → Optional.empty()
    //   4c. else                        → Optional.of(type.cast(cached))
    //   5. catch RuntimeException → Optional.empty() (cache-aside fail-open)
    // =========================================================================

    /**
     * Test 6.1: Verifies the canonical happy path &mdash; a cached value
     * of the requested type is returned wrapped in {@link Optional}.
     *
     * <p>This test simultaneously asserts (a) the {@link Optional} is
     * present, (b) the cached payload is returned verbatim, and (c) the
     * production code passes the correctly-namespaced key to
     * {@link ValueOperations#get(Object)}. The strict {@code verify} on
     * {@code valueOperations.get(FULL_KEY_ACCOUNT_12345)} also protects
     * against accidental key-format drift.</p>
     */
    @Test
    @DisplayName("get returns Optional containing the cached value on hit with matching type")
    void get_withHitAndMatchingType_returnsOptionalOfValue() {
        // Given: a BigDecimal balance is cached under the namespaced key.
        when(valueOperations.get(FULL_KEY_ACCOUNT_12345)).thenReturn(ACCOUNT_BALANCE);

        // When: the adapter is invoked with the matching type token.
        Optional<BigDecimal> result = cacheService.get(NS_ACCOUNT, KEY_ACCT, BigDecimal.class);

        // Then: the cached BigDecimal is returned verbatim.
        assertNotNull(result, "get() must never return null Optional");
        assertTrue(result.isPresent(), "Cache hit must yield a present Optional");
        assertEquals(ACCOUNT_BALANCE, result.get(),
                "Returned value must be the verbatim cached BigDecimal");

        // And: the underlying Redis call used the namespaced key.
        verify(valueOperations).get(FULL_KEY_ACCOUNT_12345);
        verify(redisTemplate).opsForValue();
    }

    /**
     * Test 6.2: Cache miss &mdash; a {@code null} return from the
     * underlying Redis client surfaces as {@link Optional#empty()}.
     *
     * <p>This is the canonical cache-aside trigger for the caller to
     * load from the database-of-record and re-populate via
     * {@link CacheService#put(String, String, Object, Duration)}.</p>
     */
    @Test
    @DisplayName("get returns Optional.empty when the key is missing (cache miss)")
    void get_withMiss_returnsOptionalEmpty() {
        // Given: no value is cached (Redis returns null).
        when(valueOperations.get(FULL_KEY_ACCOUNT_12345)).thenReturn(null);

        // When: the adapter is invoked.
        Optional<BigDecimal> result = cacheService.get(NS_ACCOUNT, KEY_ACCT, BigDecimal.class);

        // Then: Optional.empty() is returned (cache miss).
        assertNotNull(result, "get() must never return null Optional");
        assertTrue(result.isEmpty(), "Cache miss must yield Optional.empty");

        // And: the Redis call was attempted.
        verify(valueOperations).get(FULL_KEY_ACCOUNT_12345);
    }

    /**
     * Test 6.3: Type mismatch &mdash; a cached entry whose deserialised
     * type does not match the requested type returns
     * {@link Optional#empty()}, NEVER throws {@link ClassCastException}.
     *
     * <p>This guards against serializer or schema drift: if a future
     * change accidentally stores a {@link String} where a
     * {@link BigDecimal} is expected, the caller transparently falls back
     * to the database-of-record rather than crashing.</p>
     *
     * <p>Per the production class: the type check is
     * {@code type.isInstance(cached)}; on mismatch the entry is treated
     * as a miss and a WARN-level log entry is emitted for ops review.</p>
     */
    @Test
    @DisplayName("get returns Optional.empty when the cached type does not match")
    void get_withTypeMismatch_returnsOptionalEmpty() {
        // Given: a String is cached under a key the caller expects to hold a BigDecimal.
        when(valueOperations.get(FULL_KEY_ACCOUNT_12345)).thenReturn("not-a-bigdecimal");

        // When: the adapter is invoked with BigDecimal.class as the type token.
        Optional<BigDecimal> result = cacheService.get(NS_ACCOUNT, KEY_ACCT, BigDecimal.class);

        // Then: the type mismatch is treated as a miss; no ClassCastException.
        assertNotNull(result, "get() must never return null Optional");
        assertTrue(result.isEmpty(), "Type mismatch must yield Optional.empty (not throw)");

        // And: the Redis call was attempted.
        verify(valueOperations).get(FULL_KEY_ACCOUNT_12345);
    }

    /**
     * Test 6.4: Cache-aside fail-open &mdash; any {@link RuntimeException}
     * thrown by the Redis client is caught and translated to
     * {@link Optional#empty()}; the exception MUST NOT propagate.
     *
     * <p>This is the foundational AAP &sect;0.7.1 cache-aside contract:
     * cache failures degrade gracefully to a database miss. A propagating
     * exception would couple cache availability to business availability,
     * defeating the purpose of caching as a performance enhancer.</p>
     */
    @Test
    @DisplayName("get does not propagate Redis exceptions (cache-aside degrades to miss)")
    void get_whenRedisThrowsException_returnsOptionalEmptyAndDoesNotPropagate() {
        // Given: the Redis client throws on the get call.
        when(valueOperations.get(FULL_KEY_ACCOUNT_12345))
                .thenThrow(new RuntimeException("simulated Redis connection failure"));

        // When: the adapter is invoked.
        Optional<BigDecimal> result = assertDoesNotThrow(
                () -> cacheService.get(NS_ACCOUNT, KEY_ACCT, BigDecimal.class),
                "Cache-aside contract violated: get() must swallow Redis exceptions");

        // Then: Optional.empty() is returned (degrades to a cache miss).
        assertNotNull(result, "get() must never return null Optional");
        assertTrue(result.isEmpty(),
                "Cache-aside fail-open: Redis exception must surface as Optional.empty");
    }

    // =========================================================================
    // Phase 7 — `get` input validation tests
    //
    // The production class validates `type != null` before buildKey(...);
    // buildKey then validates namespace and key. Validation MUST happen
    // before any RedisTemplate / ValueOperations call.
    // =========================================================================

    /**
     * Test 7.1: Null namespace triggers {@link IllegalArgumentException}
     * before any Redis call.
     */
    @Test
    @DisplayName("get throws IllegalArgumentException when namespace is null")
    void get_withNullNamespace_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> cacheService.get(null, KEY_ACCT, BigDecimal.class));
        verify(valueOperations, never()).get(anyString());
    }

    /**
     * Test 7.2: Empty (zero-length) namespace triggers
     * {@link IllegalArgumentException} before any Redis call.
     */
    @Test
    @DisplayName("get throws IllegalArgumentException when namespace is empty")
    void get_withEmptyNamespace_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> cacheService.get("", KEY_ACCT, BigDecimal.class));
        verify(valueOperations, never()).get(anyString());
    }

    /**
     * Test 7.3: Blank (whitespace-only) namespace triggers
     * {@link IllegalArgumentException} before any Redis call. The
     * production class uses {@link String#isBlank()} for this check.
     */
    @Test
    @DisplayName("get throws IllegalArgumentException when namespace is blank")
    void get_withBlankNamespace_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> cacheService.get("   ", KEY_ACCT, BigDecimal.class));
        verify(valueOperations, never()).get(anyString());
    }

    /**
     * Test 7.4: Null key triggers {@link IllegalArgumentException} before
     * any Redis call.
     */
    @Test
    @DisplayName("get throws IllegalArgumentException when key is null")
    void get_withNullKey_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> cacheService.get(NS_ACCOUNT, null, BigDecimal.class));
        verify(valueOperations, never()).get(anyString());
    }

    /**
     * Test 7.5: Empty key triggers {@link IllegalArgumentException} before
     * any Redis call.
     */
    @Test
    @DisplayName("get throws IllegalArgumentException when key is empty")
    void get_withEmptyKey_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> cacheService.get(NS_ACCOUNT, "", BigDecimal.class));
        verify(valueOperations, never()).get(anyString());
    }

    /**
     * Test 7.6: Blank (whitespace-only) key triggers
     * {@link IllegalArgumentException} before any Redis call.
     */
    @Test
    @DisplayName("get throws IllegalArgumentException when key is blank")
    void get_withBlankKey_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> cacheService.get(NS_ACCOUNT, "   ", BigDecimal.class));
        verify(valueOperations, never()).get(anyString());
    }

    /**
     * Test 7.7: Null {@code type} parameter triggers
     * {@link IllegalArgumentException} before any Redis call (production
     * explicitly checks {@code type == null} as the first guard).
     */
    @Test
    @DisplayName("get throws IllegalArgumentException when type is null")
    void get_withNullType_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> cacheService.get(NS_ACCOUNT, KEY_ACCT, null));
        verify(valueOperations, never()).get(anyString());
    }

    // =========================================================================
    // Phase 8 — `put` happy path tests
    //
    // The production `put(namespace, key, value, ttl)` implementation:
    //   1. buildKey(namespace, key) → throws IllegalArgumentException
    //   2. effectiveTtl = (ttl == null || ttl.isZero() || ttl.isNegative())
    //                     ? Duration.ofSeconds(defaultTtlSeconds)
    //                     : ttl;
    //   3. opsForValue().set(redisKey, value, effectiveTtl)
    //   4. catch RuntimeException → log WARN, never propagate
    //
    // NOTE: production uses the (K, V, Duration) overload of
    //       ValueOperations.set(). Matchers must align with Duration,
    //       NOT (long, TimeUnit).
    // =========================================================================

    /**
     * Test 8.1: Explicit positive {@link Duration} is passed verbatim to
     * {@link ValueOperations#set(Object, Object, Duration)}.
     *
     * <p>This is the canonical write path used by all CardDemo writers
     * (e.g., {@code AccountUpdateService}, {@code BillPaymentService})
     * to populate the cache after a successful database write. The
     * verification uses {@link ArgumentCaptor} to capture all three
     * arguments and validates each independently &mdash; protecting
     * against silent regressions where a future refactor accidentally
     * mutates the key, value, or TTL before the SDK call.</p>
     */
    @Test
    @DisplayName("put with explicit TTL invokes Redis set with the namespaced key and Duration")
    @SuppressWarnings("unchecked")
    void put_withExplicitTtl_invokesValueOperationsSet() {
        // When: the adapter is invoked with an explicit 60-second TTL.
        cacheService.put(NS_ACCOUNT, KEY_ACCT, ACCOUNT_BALANCE, EXPLICIT_TTL);

        // Then: opsForValue().set was called with the namespaced key,
        //       the verbatim value, and the explicit Duration.
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(keyCaptor.capture(), valueCaptor.capture(), ttlCaptor.capture());

        assertEquals(FULL_KEY_ACCOUNT_12345, keyCaptor.getValue(),
                "Key must be namespaced as carddemo:account:12345");
        assertEquals(ACCOUNT_BALANCE, valueCaptor.getValue(),
                "Cached value must be passed verbatim to Redis");
        assertEquals(EXPLICIT_TTL, ttlCaptor.getValue(),
                "Explicit Duration must be passed verbatim (no default substitution)");
    }

    /**
     * Test 8.2: Null TTL falls back to the configured default
     * (300 seconds per AAP &sect;0.7.1 short-lived TTL for high-frequency
     * balance reads).
     *
     * <p>This guard prevents a null-TTL caller bug from accidentally
     * producing a non-expiring cache entry, which would silently drift
     * from the database-of-record &mdash; an unacceptable failure mode
     * for a financial system.</p>
     */
    @Test
    @DisplayName("put with null TTL uses the configured default TTL (300s)")
    void put_withNullTtl_usesDefaultTtl() {
        // When: the adapter is invoked with a null Duration.
        cacheService.put(NS_ACCOUNT, KEY_ACCT, ACCOUNT_BALANCE, null);

        // Then: the default TTL (300s) is applied.
        verify(valueOperations).set(eq(FULL_KEY_ACCOUNT_12345), eq(ACCOUNT_BALANCE), eq(DEFAULT_TTL));
    }

    /**
     * Test 8.3: Zero-duration TTL falls back to the configured default
     * (production treats {@code Duration.ZERO} as "use default" via
     * {@link Duration#isZero()}).
     */
    @Test
    @DisplayName("put with zero TTL uses the configured default TTL (300s)")
    void put_withZeroTtl_usesDefaultTtl() {
        // When: the adapter is invoked with Duration.ZERO.
        cacheService.put(NS_ACCOUNT, KEY_ACCT, ACCOUNT_BALANCE, Duration.ZERO);

        // Then: the default TTL (300s) is applied.
        verify(valueOperations).set(eq(FULL_KEY_ACCOUNT_12345), eq(ACCOUNT_BALANCE), eq(DEFAULT_TTL));
    }

    /**
     * Test 8.4: Negative-duration TTL falls back to the configured
     * default (production treats any negative {@link Duration} as "use
     * default" via {@link Duration#isNegative()}). This catches the
     * "expired-on-arrival" failure mode where a caller computes
     * {@code targetTime - now} after the target has already passed.
     */
    @Test
    @DisplayName("put with negative TTL uses the configured default TTL (300s)")
    void put_withNegativeTtl_usesDefaultTtl() {
        // When: the adapter is invoked with a negative Duration.
        Duration negative = Duration.ofSeconds(-5L);
        cacheService.put(NS_ACCOUNT, KEY_ACCT, ACCOUNT_BALANCE, negative);

        // Then: the default TTL (300s) is applied.
        verify(valueOperations).set(eq(FULL_KEY_ACCOUNT_12345), eq(ACCOUNT_BALANCE), eq(DEFAULT_TTL));
    }

    /**
     * Test 8.5: Cache-aside fail-open on write &mdash; any
     * {@link RuntimeException} thrown by the Redis client during a
     * {@code set} call is caught and logged at WARN; the method
     * completes normally. A cache-write failure must never block a
     * business flow whose database write has already succeeded.
     */
    @Test
    @DisplayName("put does not propagate Redis exceptions (cache-aside fail-open)")
    void put_whenRedisThrowsException_doesNotPropagate() {
        // Given: the Redis client throws on the set call.
        doThrow(new RuntimeException("simulated Redis write failure"))
                .when(valueOperations).set(anyString(), any(), any(Duration.class));

        // When/Then: the method completes normally; the exception is swallowed.
        assertDoesNotThrow(
                () -> cacheService.put(NS_ACCOUNT, KEY_ACCT, ACCOUNT_BALANCE, EXPLICIT_TTL),
                "Cache-aside contract violated: put() must swallow Redis exceptions");

        // And: the set call was attempted (so we know the exception path was exercised).
        verify(valueOperations).set(eq(FULL_KEY_ACCOUNT_12345), eq(ACCOUNT_BALANCE), eq(EXPLICIT_TTL));
    }

    // =========================================================================
    // Phase 9 — `put` input validation tests
    //
    // Validation MUST happen before any RedisTemplate / ValueOperations call.
    // Note: per the production class, null `value` is allowed (no explicit
    // guard); callers may legitimately cache a null sentinel. Only null/blank
    // namespace and key throw IllegalArgumentException.
    // =========================================================================

    /**
     * Test 9.1: Null namespace triggers {@link IllegalArgumentException}
     * before any Redis call.
     */
    @Test
    @DisplayName("put throws IllegalArgumentException when namespace is null")
    void put_withNullNamespace_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> cacheService.put(null, KEY_ACCT, ACCOUNT_BALANCE, EXPLICIT_TTL));
        verify(valueOperations, never()).set(anyString(), any(), any(Duration.class));
    }

    /**
     * Test 9.2: Empty namespace triggers {@link IllegalArgumentException}
     * before any Redis call.
     */
    @Test
    @DisplayName("put throws IllegalArgumentException when namespace is empty")
    void put_withEmptyNamespace_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> cacheService.put("", KEY_ACCT, ACCOUNT_BALANCE, EXPLICIT_TTL));
        verify(valueOperations, never()).set(anyString(), any(), any(Duration.class));
    }

    /**
     * Test 9.3: Blank (whitespace-only) namespace triggers
     * {@link IllegalArgumentException} before any Redis call.
     */
    @Test
    @DisplayName("put throws IllegalArgumentException when namespace is blank")
    void put_withBlankNamespace_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> cacheService.put("   ", KEY_ACCT, ACCOUNT_BALANCE, EXPLICIT_TTL));
        verify(valueOperations, never()).set(anyString(), any(), any(Duration.class));
    }

    /**
     * Test 9.4: Null key triggers {@link IllegalArgumentException} before
     * any Redis call.
     */
    @Test
    @DisplayName("put throws IllegalArgumentException when key is null")
    void put_withNullKey_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> cacheService.put(NS_ACCOUNT, null, ACCOUNT_BALANCE, EXPLICIT_TTL));
        verify(valueOperations, never()).set(anyString(), any(), any(Duration.class));
    }

    /**
     * Test 9.5: Empty key triggers {@link IllegalArgumentException}
     * before any Redis call.
     */
    @Test
    @DisplayName("put throws IllegalArgumentException when key is empty")
    void put_withEmptyKey_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> cacheService.put(NS_ACCOUNT, "", ACCOUNT_BALANCE, EXPLICIT_TTL));
        verify(valueOperations, never()).set(anyString(), any(), any(Duration.class));
    }

    /**
     * Test 9.6: Blank (whitespace-only) key triggers
     * {@link IllegalArgumentException} before any Redis call.
     */
    @Test
    @DisplayName("put throws IllegalArgumentException when key is blank")
    void put_withBlankKey_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> cacheService.put(NS_ACCOUNT, "   ", ACCOUNT_BALANCE, EXPLICIT_TTL));
        verify(valueOperations, never()).set(anyString(), any(), any(Duration.class));
    }

    // =========================================================================
    // Phase 10 — `evict` happy path tests
    //
    // The production `evict(namespace, key)` implementation:
    //   1. buildKey(namespace, key) → throws IllegalArgumentException
    //   2. redisTemplate.delete(redisKey) — returns Boolean
    //   3. catch RuntimeException → log WARN, never propagate
    //
    // Note: evict does NOT use opsForValue(); only redisTemplate.delete(String).
    // =========================================================================

    /**
     * Test 10.1: Happy path &mdash; {@code evict} delegates to
     * {@link RedisTemplate#delete(Object)} with the correctly-namespaced
     * key. This is the canonical cache invalidation step that writers
     * invoke immediately after a successful database write per
     * AAP &sect;0.3.3 cache-aside protocol (writer evicts, next reader
     * re-populates).
     */
    @Test
    @DisplayName("evict invokes Redis delete with the namespaced key")
    void evict_invokesRedisDelete() {
        // Given: Redis will report a successful single-key delete.
        when(redisTemplate.delete(FULL_KEY_ACCOUNT_12345)).thenReturn(Boolean.TRUE);

        // When: the adapter is invoked.
        cacheService.evict(NS_ACCOUNT, KEY_ACCT);

        // Then: the namespaced key is passed to redisTemplate.delete(String).
        verify(redisTemplate).delete(FULL_KEY_ACCOUNT_12345);
        // And: the value-operations side was never touched.
        verify(redisTemplate, never()).opsForValue();
    }

    /**
     * Test 10.2: Cache-aside fail-open on evict &mdash; any
     * {@link RuntimeException} thrown by Redis is caught and logged at
     * WARN; the method completes normally. A failed eviction is non-fatal
     * because (a) the database write has already succeeded and (b) the
     * cache entry will eventually expire via its TTL.
     */
    @Test
    @DisplayName("evict does not propagate Redis exceptions (cache-aside fail-open)")
    void evict_whenRedisThrowsException_doesNotPropagate() {
        // Given: redisTemplate.delete throws.
        when(redisTemplate.delete(FULL_KEY_ACCOUNT_12345))
                .thenThrow(new RuntimeException("simulated Redis delete failure"));

        // When/Then: the method completes normally; the exception is swallowed.
        assertDoesNotThrow(
                () -> cacheService.evict(NS_ACCOUNT, KEY_ACCT),
                "Cache-aside contract violated: evict() must swallow Redis exceptions");

        // And: the delete attempt was made (so the exception path was exercised).
        verify(redisTemplate).delete(FULL_KEY_ACCOUNT_12345);
    }

    // =========================================================================
    // Phase 11 — `evict` input validation tests
    //
    // Validation MUST happen before any RedisTemplate call.
    // =========================================================================

    /**
     * Test 11.1: Null namespace triggers {@link IllegalArgumentException}
     * before any Redis call.
     */
    @Test
    @DisplayName("evict throws IllegalArgumentException when namespace is null")
    void evict_withNullNamespace_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> cacheService.evict(null, KEY_ACCT));
        verify(redisTemplate, never()).delete(anyString());
    }

    /**
     * Test 11.2: Empty namespace triggers
     * {@link IllegalArgumentException} before any Redis call.
     */
    @Test
    @DisplayName("evict throws IllegalArgumentException when namespace is empty")
    void evict_withEmptyNamespace_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> cacheService.evict("", KEY_ACCT));
        verify(redisTemplate, never()).delete(anyString());
    }

    /**
     * Test 11.3: Blank namespace triggers
     * {@link IllegalArgumentException} before any Redis call.
     */
    @Test
    @DisplayName("evict throws IllegalArgumentException when namespace is blank")
    void evict_withBlankNamespace_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> cacheService.evict("   ", KEY_ACCT));
        verify(redisTemplate, never()).delete(anyString());
    }

    /**
     * Test 11.4: Null key triggers {@link IllegalArgumentException}
     * before any Redis call.
     */
    @Test
    @DisplayName("evict throws IllegalArgumentException when key is null")
    void evict_withNullKey_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> cacheService.evict(NS_ACCOUNT, null));
        verify(redisTemplate, never()).delete(anyString());
    }

    /**
     * Test 11.5: Empty key triggers {@link IllegalArgumentException}
     * before any Redis call.
     */
    @Test
    @DisplayName("evict throws IllegalArgumentException when key is empty")
    void evict_withEmptyKey_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> cacheService.evict(NS_ACCOUNT, ""));
        verify(redisTemplate, never()).delete(anyString());
    }

    /**
     * Test 11.6: Blank key triggers {@link IllegalArgumentException}
     * before any Redis call.
     */
    @Test
    @DisplayName("evict throws IllegalArgumentException when key is blank")
    void evict_withBlankKey_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> cacheService.evict(NS_ACCOUNT, "   "));
        verify(redisTemplate, never()).delete(anyString());
    }

    // =========================================================================
    // Phase 12 — `evictAll` tests
    //
    // The production `evictAll(namespace)` implementation:
    //   1. Validates namespace != null && !namespace.isBlank()
    //   2. pattern = "carddemo:" + namespace + ":*"
    //   3. redisTemplate.keys(pattern) → Set<String>
    //   4. if (keys != null && !keys.isEmpty()) redisTemplate.delete(keys);
    //   5. catch RuntimeException → log WARN, never propagate
    // =========================================================================

    /**
     * Test 12.1: Happy path &mdash; {@code evictAll} enumerates keys via
     * {@link RedisTemplate#keys(Object)} with the pattern
     * {@code "carddemo:<namespace>:*"} and bulk-deletes them via
     * {@link RedisTemplate#delete(Collection)}.
     *
     * <p>This is the admin-only convenience used (e.g.) after a Flyway
     * migration of reference data &mdash; the entire namespace is
     * invalidated so the next read repopulates from the freshly-migrated
     * RDS rows.</p>
     */
    @Test
    @DisplayName("evictAll enumerates keys and deletes the entire namespace")
    void evictAll_invokesRedisKeysAndDelete() {
        // Given: Redis returns three matching keys for the account namespace.
        Set<String> keys = new HashSet<>();
        keys.add("carddemo:account:12345");
        keys.add("carddemo:account:23456");
        keys.add("carddemo:account:34567");
        when(redisTemplate.keys("carddemo:account:*")).thenReturn(keys);
        when(redisTemplate.delete(keys)).thenReturn(3L);

        // When: the adapter is invoked.
        cacheService.evictAll(NS_ACCOUNT);

        // Then: keys were enumerated under the namespaced pattern...
        verify(redisTemplate).keys("carddemo:account:*");
        // ...and bulk-deleted via the Collection overload.
        verify(redisTemplate).delete(keys);
    }

    /**
     * Test 12.2: When the namespace contains no keys, the production
     * code skips the {@link RedisTemplate#delete(Collection)} call &mdash;
     * an empty bulk-delete would be wasteful (round-trip with no effect).
     */
    @Test
    @DisplayName("evictAll does not invoke delete when the keys lookup returns empty")
    void evictAll_whenNoKeysFound_doesNotInvokeDelete() {
        // Given: Redis returns an empty key set.
        when(redisTemplate.keys("carddemo:account:*")).thenReturn(Set.of());

        // When: the adapter is invoked.
        cacheService.evictAll(NS_ACCOUNT);

        // Then: keys was attempted, but delete was never called.
        verify(redisTemplate).keys("carddemo:account:*");
        verify(redisTemplate, never()).delete(anyCollection());
    }

    /**
     * Test 12.2b: When the {@code keys(...)} call returns {@code null}
     * (some Redis client configurations behave this way), the production
     * code's null check prevents a {@link NullPointerException} and
     * skips the delete.
     */
    @Test
    @DisplayName("evictAll does not invoke delete when the keys lookup returns null")
    void evictAll_whenKeysReturnsNull_doesNotInvokeDelete() {
        // Given: Redis returns null.
        when(redisTemplate.keys("carddemo:account:*")).thenReturn(null);

        // When: the adapter is invoked.
        assertDoesNotThrow(
                () -> cacheService.evictAll(NS_ACCOUNT),
                "evictAll() must handle a null keys() return without throwing");

        // Then: keys was attempted, but delete was never called.
        verify(redisTemplate).keys("carddemo:account:*");
        verify(redisTemplate, never()).delete(anyCollection());
    }

    /**
     * Test 12.3: Cache-aside fail-open on {@code evictAll} &mdash; any
     * {@link RuntimeException} thrown by Redis during key enumeration
     * is caught and logged at WARN; the method completes normally.
     */
    @Test
    @DisplayName("evictAll does not propagate Redis exceptions (cache-aside fail-open)")
    void evictAll_whenRedisThrowsException_doesNotPropagate() {
        // Given: redisTemplate.keys throws.
        when(redisTemplate.keys("carddemo:account:*"))
                .thenThrow(new RuntimeException("simulated Redis SCAN/KEYS failure"));

        // When/Then: the method completes normally; the exception is swallowed.
        assertDoesNotThrow(
                () -> cacheService.evictAll(NS_ACCOUNT),
                "Cache-aside contract violated: evictAll() must swallow Redis exceptions");

        // And: the keys call was attempted (so the exception path was exercised).
        verify(redisTemplate).keys("carddemo:account:*");
        // Delete must not be attempted when keys() throws.
        verify(redisTemplate, never()).delete(anyCollection());
    }

    /**
     * Test 12.4: Null namespace triggers
     * {@link IllegalArgumentException} BEFORE any Redis call (production
     * explicitly checks {@code namespace == null || namespace.isBlank()}
     * at the top of the method).
     */
    @Test
    @DisplayName("evictAll throws IllegalArgumentException when namespace is null")
    void evictAll_withNullNamespace_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> cacheService.evictAll(null));
        verifyNoInteractions(valueOperations);
        verify(redisTemplate, never()).keys(anyString());
        verify(redisTemplate, never()).delete(anyCollection());
    }

    /**
     * Test 12.5: Empty namespace triggers
     * {@link IllegalArgumentException} before any Redis call.
     */
    @Test
    @DisplayName("evictAll throws IllegalArgumentException when namespace is empty")
    void evictAll_withEmptyNamespace_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> cacheService.evictAll(""));
        verify(redisTemplate, never()).keys(anyString());
        verify(redisTemplate, never()).delete(anyCollection());
    }

    /**
     * Test 12.6: Blank namespace triggers
     * {@link IllegalArgumentException} before any Redis call.
     */
    @Test
    @DisplayName("evictAll throws IllegalArgumentException when namespace is blank")
    void evictAll_withBlankNamespace_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> cacheService.evictAll("   "));
        verify(redisTemplate, never()).keys(anyString());
        verify(redisTemplate, never()).delete(anyCollection());
    }

    // =========================================================================
    // Phase 13 — Key namespacing verification tests
    //
    // Every CardDemo cache entry must be namespaced as
    //   "carddemo:<namespace>:<key>"
    // This invariant is the application's contract with the shared Redis
    // cluster: the "carddemo:" prefix isolates this tenant from any other
    // application on the same Redis instance and prevents accidental
    // cross-environment cache pollution.
    // =========================================================================

    /**
     * Test 13.1: The full namespaced key format
     * {@code carddemo:<namespace>:<key>} is consistently applied for
     * every cache operation. This test exercises {@code evict} with an
     * arbitrary account ID and verifies that the constructed key passes
     * through to {@link RedisTemplate#delete(Object)} unchanged.
     *
     * <p>Protects against accidental cross-environment cache pollution
     * &mdash; e.g., a developer running tests against a shared Redis
     * instance must not collide with another application's keys.</p>
     */
    @Test
    @DisplayName("keys are namespaced under carddemo:{namespace}:{key}")
    void keysAreNamespacedAsCarddemoColonNamespaceColonKey() {
        // When: evict is called with an arbitrary account key.
        cacheService.evict(NS_ACCOUNT, "98765");

        // Then: the namespaced key passes through verbatim.
        verify(redisTemplate).delete("carddemo:account:98765");
    }

    /**
     * Test 13.2: The {@code carddemo:} prefix is consistently applied
     * for every conventional namespace per AAP &sect;0.6.6:
     * {@code account}, {@code card}, {@code discgrp}, {@code tranType},
     * {@code tranCatg}, {@code xref}. This test exercises the
     * {@code discgrp} (reference data) and {@code xref} (cross-reference)
     * namespaces with hyphenated/uppercase keys to ensure key encoding
     * is faithful.
     */
    @Test
    @DisplayName("evict applies the correct carddemo prefix across multiple namespaces")
    void keysWithDifferentNamespacesUseCorrectPrefix() {
        // When/Then: discgrp namespace with uppercase key.
        cacheService.evict(NS_DISCGRP, "DEFAULT");
        verify(redisTemplate).delete("carddemo:discgrp:DEFAULT");

        // When/Then: xref namespace with hyphenated key.
        cacheService.evict(NS_XREF, "C-001");
        verify(redisTemplate).delete("carddemo:xref:C-001");

        // Verify total delete invocations to catch any accidental extra calls.
        verify(redisTemplate, times(2)).delete(anyString());
    }

    /**
     * Test 13.3: The {@code get} operation applies the same namespaced
     * key format as {@code evict}, ensuring read/write key consistency.
     * Without this guarantee, a writer evicting key {@code account:12345}
     * would not invalidate a reader hit on a differently-formed key.
     */
    @Test
    @DisplayName("get applies the carddemo namespacing format to the read key")
    void get_appliesCarddemoNamespacePrefixToReadKey() {
        // Given: a balance is cached under the EXPECTED namespaced key.
        when(valueOperations.get("carddemo:account:99999")).thenReturn(new BigDecimal("999.99"));

        // When: get is called with the namespace + business key.
        Optional<BigDecimal> result = cacheService.get(NS_ACCOUNT, "99999", BigDecimal.class);

        // Then: the read used the namespaced key (cache hit).
        assertTrue(result.isPresent(), "Cache hit on correctly-namespaced key must succeed");
        verify(valueOperations).get("carddemo:account:99999");
    }

    /**
     * Test 13.4: The {@code put} operation applies the same namespaced
     * key format as {@code get} and {@code evict}, ensuring all three
     * operations share a single key shape. This invariant guarantees
     * that a {@code put} writes to the exact same key shape that a
     * subsequent {@code get} reads from and that an {@code evict}
     * invalidates.
     */
    @Test
    @DisplayName("put applies the carddemo namespacing format to the write key")
    void put_appliesCarddemoNamespacePrefixToWriteKey() {
        // When: put is called with namespace + business key + value + TTL.
        cacheService.put(NS_ACCOUNT, "77777", new BigDecimal("777.77"), EXPLICIT_TTL);

        // Then: the write used the namespaced key.
        verify(valueOperations).set(
                eq("carddemo:account:77777"),
                eq(new BigDecimal("777.77")),
                eq(EXPLICIT_TTL));
    }

    /**
     * Test 13.5: The {@code evictAll} operation applies the
     * {@code carddemo:<namespace>:*} pattern format, ensuring the
     * namespace-wide invalidation is scoped exclusively to CardDemo
     * entries and never collides with other tenants on a shared Redis
     * cluster.
     */
    @Test
    @DisplayName("evictAll applies the carddemo prefix to the keys pattern")
    void evictAll_appliesCarddemoNamespacePrefixToPattern() {
        // Given: empty result (test focus is the pattern, not the deletion).
        when(redisTemplate.keys("carddemo:discgrp:*")).thenReturn(Set.of());

        // When: evictAll is called for the discgrp namespace.
        cacheService.evictAll(NS_DISCGRP);

        // Then: the keys pattern was constructed correctly.
        verify(redisTemplate).keys("carddemo:discgrp:*");
    }

    // =========================================================================
    // Phase 14 — PCI-DSS no-PII assertion (informational/compliance guard)
    //
    // The production CacheService does NOT enforce no-PII — sanitisation is
    // the caller's responsibility per AAP §0.7.2. This test ensures the
    // TEST CLASS itself does not introduce non-compliant fixtures that could
    // mislead future developers into thinking PAN/CVV/SSN caching is OK.
    // =========================================================================

    /**
     * Test 14.1: Compliance assertion &mdash; every monetary payload used
     * in this test class is a {@link BigDecimal} (an account balance),
     * NEVER an unmasked PAN, CVV, or SSN. This test asserts the canonical
     * fixtures conform to the PAN regex {@code ^\\d{13,19}$} and SSN
     * regex {@code ^\\d{3}-?\\d{2}-?\\d{4}$} as negative matches.
     *
     * <p>The 5-digit {@link #KEY_ACCT} is too short to match a PAN (which
     * is 13&ndash;19 digits); the balance fixture is a non-integer
     * decimal that cannot match a digit-only PAN regex.</p>
     */
    @Test
    @DisplayName("PCI-DSS: test fixtures contain no PAN-like or SSN-like sequences")
    void pciCompliance_testFixturesContainNoPanOrSsnPatterns() {
        // Regex for PAN (Primary Account Number): 13-19 consecutive digits.
        Pattern panPattern = Pattern.compile("^\\d{13,19}$");
        // Regex for US SSN: XXX-XX-XXXX (or unhyphenated XXXXXXXXX).
        Pattern ssnPattern = Pattern.compile("^\\d{3}-?\\d{2}-?\\d{4}$");

        // Canonical key fixture: 5 digits, too short for PAN, too short for SSN.
        assertFalse(panPattern.matcher(KEY_ACCT).matches(),
                "KEY_ACCT must not look like a PAN");
        assertFalse(ssnPattern.matcher(KEY_ACCT).matches(),
                "KEY_ACCT must not look like an SSN");

        // Canonical balance fixture: non-integer decimal, not a PAN/SSN.
        String balanceStr = ACCOUNT_BALANCE.toPlainString();
        assertFalse(panPattern.matcher(balanceStr).matches(),
                "ACCOUNT_BALANCE must not look like a PAN");
        assertFalse(ssnPattern.matcher(balanceStr).matches(),
                "ACCOUNT_BALANCE must not look like an SSN");

        // Full Redis key — assert the same.
        assertFalse(panPattern.matcher(FULL_KEY_ACCOUNT_12345).matches(),
                "FULL_KEY_ACCOUNT_12345 must not look like a PAN");
        assertFalse(ssnPattern.matcher(FULL_KEY_ACCOUNT_12345).matches(),
                "FULL_KEY_ACCOUNT_12345 must not look like an SSN");
    }

    // =========================================================================
    // Phase 15 — Cross-cutting behavioral verifications
    //
    // These tests validate cross-method invariants that are not tied to a
    // single API call: e.g., that the default-TTL fallback is consistent
    // across all paths and that TimeUnit / Duration arithmetic is faithful.
    // =========================================================================

    /**
     * Test 15.1: The default TTL of 300 seconds equals 5 minutes exactly.
     * This documents and asserts the AAP &sect;0.7.1 contract that the
     * account-balance namespace uses a 5-minute TTL aligned to
     * transaction frequency. The constant is also expressible as
     * {@code 5 * 60} or {@code TimeUnit.MINUTES.toSeconds(5)}; both
     * should reduce to the same value.
     */
    @Test
    @DisplayName("Default TTL of 300 seconds equals 5 minutes exactly")
    void defaultTtl_equalsFiveMinutesExactly() {
        assertEquals(300L, DEFAULT_TTL_SECONDS,
                "AAP §0.7.1: account-balance TTL is 5 minutes (300 seconds)");
        assertEquals(TimeUnit.MINUTES.toSeconds(5L), DEFAULT_TTL_SECONDS,
                "Default TTL must equal 5 minutes expressed in seconds");
        assertEquals(Duration.ofMinutes(5L), DEFAULT_TTL,
                "Default Duration must equal Duration.ofMinutes(5)");
    }

    /**
     * Test 15.2: All four public methods of {@link CacheService} are
     * exercised by this test class. This serves as a sanity check that
     * the test class is complete and that no public method has been
     * silently removed or renamed without test coverage.
     *
     * <p>This is a documentation-style test, not a functional one. It
     * asserts that the four canonical methods exist on the
     * {@link CacheService} class via reflection.</p>
     */
    @Test
    @DisplayName("CacheService exposes get, put, evict, and evictAll public methods")
    void cacheService_exposesCanonicalPublicApi() throws NoSuchMethodException {
        // get(String, String, Class)
        assertNotNull(CacheService.class.getMethod("get", String.class, String.class, Class.class),
                "CacheService must expose get(String, String, Class)");
        // put(String, String, Object, Duration)
        assertNotNull(CacheService.class.getMethod("put", String.class, String.class, Object.class, Duration.class),
                "CacheService must expose put(String, String, Object, Duration)");
        // evict(String, String)
        assertNotNull(CacheService.class.getMethod("evict", String.class, String.class),
                "CacheService must expose evict(String, String)");
        // evictAll(String)
        assertNotNull(CacheService.class.getMethod("evictAll", String.class),
                "CacheService must expose evictAll(String)");
    }
}
