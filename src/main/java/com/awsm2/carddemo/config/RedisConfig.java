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

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Spring {@code @Configuration} that wires the connection factory,
 * {@link RedisTemplate}, and Spring Cache {@link CacheManager} for Amazon
 * ElastiCache Redis (cache-aside pattern) per AAP &sect;0.7.1.
 *
 * <p><b>Replaces:</b> No COBOL equivalent &mdash; caching is an additive
 * optimization introduced by the migration. Account balance reads from RDS
 * (formerly VSAM KSDS reads) are cached for the TTL window to reduce
 * database load on hot accounts (AAP &sect;0.7.2 performance NFR
 * "ElastiCache Redis reduces RDS read load by caching high-frequency
 * account balance lookups").</p>
 *
 * <h2>Architectural role (per AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><b>Stateless infrastructure wiring</b> &mdash; no business logic;
 *       three {@code @Bean} factory methods only.</li>
 *   <li><b>Profile-aware</b> &mdash; {@code local} / {@code dev} / {@code prod}
 *       use different Redis endpoints; TLS enabled in {@code dev}/{@code prod}
 *       (mandatory for PCI-DSS encryption-in-transit per AAP &sect;0.6.6),
 *       disabled in {@code local} (LocalStack/docker-compose plaintext Redis).</li>
 *   <li><b>Cache-aside pattern</b> &mdash; the {@link CacheManager} bean returned
 *       by {@link #cacheManager(RedisConnectionFactory)} enables Spring's
 *       {@code @Cacheable} / {@code @CacheEvict} / {@code @CachePut} annotations
 *       on service methods (e.g., {@code AccountService.findById(...)} cached
 *       for the configured TTL).</li>
 *   <li><b>{@code allkeys-lru} eviction policy</b> &mdash; applied at the
 *       ElastiCache cluster level via Terraform
 *       ({@code infrastructure/terraform/elasticache.tf} per AAP &sect;0.7.1);
 *       NOT configured here. The client side only specifies per-entry TTL.</li>
 *   <li><b>JSON serializer for values</b> &mdash; enables cross-language
 *       consumption and Redis CLI inspectability (a plain {@code GET}
 *       returns human-readable JSON rather than Java-serialized bytes).</li>
 *   <li><b>Key prefix {@code carddemo:}</b> &mdash; namespaces cache entries
 *       within a shared Redis instance, preventing collision with other
 *       applications that might share the cluster.</li>
 * </ul>
 *
 * <h2>TLS in transit (AAP &sect;0.6.6)</h2>
 * <p>Per AAP &sect;0.6.6 ("TLS 1.2+ in transit is mandatory in production"),
 * the {@code application-prod.yml} overlay sets
 * {@code spring.data.redis.ssl.enabled=true}. This config honors that flag by
 * invoking {@link LettuceClientConfiguration.LettuceClientConfigurationBuilder#useSsl()}
 * on the Lettuce client configuration. ElastiCache encryption-in-transit must
 * also be enabled at the cluster level via Terraform; the two settings together
 * provide end-to-end TLS between the ECS task and the cache.</p>
 *
 * <h2>TTL "aligned with transaction frequency" (AAP &sect;0.7.1)</h2>
 * <p>The default cache entry TTL is {@code spring.cache.redis.time-to-live}
 * (300000ms = 5 min). This horizon is short enough that an account balance
 * read after a recent transaction sees the database-of-record value (RDS)
 * rather than a stale cache entry, but long enough to amortize the cache
 * hit rate for read-heavy workloads (account lookups during transaction
 * authorization). Per-cache overrides may be added later via
 * {@code RedisCacheManager.Builder.withCacheConfiguration(cacheName, config)};
 * for the initial migration scope, all caches share the same default.</p>
 *
 * <h2>Negative caching (financial-system safety)</h2>
 * <p>Per AAP &sect;0.7.1, {@code disableCachingNullValues} is the default. If
 * {@code accountService.findById(999)} returns {@code null} (no such account),
 * the null is NOT cached; the next request still hits the database. For
 * financial systems this prevents a brief inconsistency window during account
 * provisioning from being persisted into the cache for the full TTL.</p>
 *
 * <h2>Cluster mode</h2>
 * <p>For the CardDemo scope the ElastiCache cluster is configured as a
 * single-shard primary with one Multi-AZ replica (AAP &sect;0.6.5 /
 * &sect;0.7.1), which is correctly modeled by
 * {@link RedisStandaloneConfiguration}. If production scales to multi-shard
 * cluster mode in the future, switch to {@code RedisClusterConfiguration} and
 * use the Lettuce cluster client.</p>
 *
 * <h2>{@code @EnableCaching} placement</h2>
 * <p>Per the agent prompt (and to avoid duplication), {@code @EnableCaching}
 * is declared on {@code CardDemoApplication} (the application entry point),
 * NOT on this configuration class. Duplicating it would be harmless but is
 * unnecessary; placing it on the application class ensures it is processed
 * regardless of whether this configuration is excluded from a specific
 * test slice.</p>
 *
 * <h2>Property bindings</h2>
 * <table border="1" cellpadding="3" summary="Property bindings consumed by this class">
 *   <tr><th>Property</th><th>Default</th><th>Source</th></tr>
 *   <tr><td>{@code spring.data.redis.host}</td><td>{@code localhost}</td><td>profile overlay</td></tr>
 *   <tr><td>{@code spring.data.redis.port}</td><td>{@code 6379}</td><td>{@code application.yml}</td></tr>
 *   <tr><td>{@code spring.data.redis.timeout}</td><td>{@code 2s}</td><td>{@code application.yml}</td></tr>
 *   <tr><td>{@code spring.data.redis.ssl.enabled}</td><td>{@code false}</td><td>profile overlay (true in dev/prod)</td></tr>
 *   <tr><td>{@code spring.cache.redis.time-to-live}</td><td>{@code 300000} (5 min)</td><td>{@code application.yml}</td></tr>
 *   <tr><td>{@code spring.cache.redis.cache-null-values}</td><td>{@code false}</td><td>{@code application.yml}</td></tr>
 *   <tr><td>{@code spring.cache.redis.use-key-prefix}</td><td>{@code true}</td><td>{@code application.yml}</td></tr>
 *   <tr><td>{@code spring.cache.redis.key-prefix}</td><td>{@code carddemo:}</td><td>{@code application.yml}</td></tr>
 * </table>
 *
 * @see com.awsm2.carddemo.adapter.CacheService
 */
@Configuration
public class RedisConfig {

    // -----------------------------------------------------------------------
    // Configuration properties (injected from application*.yml profile overlays)
    //
    // All @Value bindings carry sensible defaults so this class compiles and
    // runs without an application.yml present (e.g., during ad-hoc bean tests).
    // -----------------------------------------------------------------------

    /**
     * Redis server hostname. Sourced from {@code spring.data.redis.host} which
     * itself defers to the {@code SPRING_DATA_REDIS_HOST} environment variable
     * per {@code application.yml}.
     *
     * <ul>
     *   <li><b>local</b> profile: {@code localhost} (docker-compose redis container)</li>
     *   <li><b>dev / prod</b> profile: ElastiCache primary endpoint
     *       (e.g., {@code carddemo-redis.xxxxxx.use1.cache.amazonaws.com})</li>
     * </ul>
     */
    @Value("${spring.data.redis.host:localhost}")
    private String host;

    /**
     * Redis server port. Default {@code 6379} (the standard Redis port).
     * Sourced from {@code spring.data.redis.port}.
     */
    @Value("${spring.data.redis.port:6379}")
    private int port;

    /**
     * Lettuce command timeout. Sourced from {@code spring.data.redis.timeout}.
     * Default {@code 2s} (two seconds) &mdash; aggressive enough to fail-fast
     * on slow cache calls so that the cache-aside pattern degrades gracefully
     * to a direct RDS read rather than blocking the request thread.
     *
     * <p>Spring's {@code ConversionService} automatically parses the
     * human-readable Duration format ({@code 2s}, {@code 500ms}, {@code PT2S}).</p>
     */
    @Value("${spring.data.redis.timeout:2s}")
    private Duration commandTimeout;

    /**
     * TLS in transit toggle. {@code true} in {@code dev} and {@code prod}
     * profiles (ElastiCache encryption-in-transit enabled per AAP &sect;0.6.6);
     * {@code false} in {@code local} profile (docker-compose Redis without
     * TLS). Sourced from {@code spring.data.redis.ssl.enabled}.
     */
    @Value("${spring.data.redis.ssl.enabled:false}")
    private boolean sslEnabled;

    /**
     * Default cache entry TTL in milliseconds. {@code 300000} = 5 minutes
     * &mdash; "aligned to transaction frequency" per AAP &sect;0.7.1.
     * Sourced from {@code spring.cache.redis.time-to-live}.
     */
    @Value("${spring.cache.redis.time-to-live:300000}")
    private long defaultTtlMillis;

    /**
     * Whether to cache {@code null} return values. {@code false} (default)
     * disables negative caching per AAP &sect;0.7.1, so a {@code null} result
     * from a service method is NOT cached and the next request re-queries
     * the database. Sourced from {@code spring.cache.redis.cache-null-values}.
     */
    @Value("${spring.cache.redis.cache-null-values:false}")
    private boolean cacheNullValues;

    /**
     * Whether to prefix every cache key with {@link #keyPrefix}. {@code true}
     * (default) namespaces CardDemo entries within a shared Redis instance.
     * Sourced from {@code spring.cache.redis.use-key-prefix}.
     */
    @Value("${spring.cache.redis.use-key-prefix:true}")
    private boolean useKeyPrefix;

    /**
     * Key prefix string. Default {@code carddemo:} per the AAP-aligned
     * {@code application.yml}. Sourced from {@code spring.cache.redis.key-prefix}.
     */
    @Value("${spring.cache.redis.key-prefix:carddemo:}")
    private String keyPrefix;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Default no-arg constructor. All configuration state is supplied via
     * {@code @Value}-injected fields after Spring instantiates the bean.
     */
    public RedisConfig() {
        // no-op — Spring populates @Value fields after construction
    }

    // -----------------------------------------------------------------------
    // @Bean: RedisConnectionFactory (Lettuce)
    // -----------------------------------------------------------------------

    /**
     * Creates the {@link LettuceConnectionFactory} that backs every Redis
     * operation in the application &mdash; both the {@link RedisTemplate}
     * (manual operations) and the {@link CacheManager} (annotation-driven
     * cache-aside per AAP &sect;0.7.1).
     *
     * <p><b>Replaces:</b> no COBOL equivalent &mdash; caching is additive
     * (AAP &sect;0.7.1 "ElastiCache (Redis) used for account balance caching").
     * The original mainframe relied on physical I/O against VSAM KSDS clusters
     * for every read; the migrated stack inserts ElastiCache between the
     * service layer and RDS PostgreSQL to absorb hot-account read load.</p>
     *
     * <p><b>Cluster mode:</b> uses {@link RedisStandaloneConfiguration}
     * (single shard with optional Multi-AZ replica). This matches the
     * default ElastiCache deployment model for the migration scope; for
     * multi-shard cluster mode, switch to {@code RedisClusterConfiguration}.</p>
     *
     * <p><b>TLS in transit (AAP &sect;0.6.6):</b> when
     * {@link #sslEnabled} is {@code true} (set by the {@code prod} / {@code dev}
     * profile), the Lettuce client opts into TLS via
     * {@link LettuceClientConfiguration.LettuceClientConfigurationBuilder#useSsl()}.
     * The call also mutates the underlying builder so that
     * {@link LettuceClientConfiguration.LettuceClientConfigurationBuilder#build()}
     * produces an SSL-enabled configuration. ElastiCache encryption-in-transit
     * must also be enabled at the cluster level via Terraform; without TLS
     * on both sides, the connection handshake fails.</p>
     *
     * <p><b>Command timeout:</b> {@link #commandTimeout} (default 2s)
     * fail-fast on slow Redis calls. The cache-aside pattern degrades
     * gracefully &mdash; on cache failure or timeout, the service falls
     * back to a direct RDS read (the {@code CacheService} adapter swallows
     * all Redis exceptions per AAP &sect;0.7.1).</p>
     *
     * @return the configured Lettuce-based connection factory (singleton scope)
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        // Replaces: no COBOL source — additive cache layer per AAP §0.7.1
        RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration();
        redisConfig.setHostName(host);
        redisConfig.setPort(port);

        LettuceClientConfiguration.LettuceClientConfigurationBuilder builder =
                LettuceClientConfiguration.builder()
                        .commandTimeout(commandTimeout);

        if (sslEnabled) {
            // TLS in transit per AAP §0.6.6 — required for production ElastiCache.
            // useSsl() mutates the underlying useSsl=true flag on the builder;
            // the SSL-specific return value is intentionally discarded since
            // we do not need to chain SSL-specific options (disablePeerVerification,
            // startTls). The subsequent build() call honors the useSsl flag.
            builder.useSsl();
        }

        return new LettuceConnectionFactory(redisConfig, builder.build());
    }

    // -----------------------------------------------------------------------
    // @Bean: RedisTemplate<String, Object>
    // -----------------------------------------------------------------------

    /**
     * Creates the primary {@link RedisTemplate} used by
     * {@code com.awsm2.carddemo.adapter.CacheService} for manual cache
     * get/set/delete operations that fall outside the annotation-driven
     * {@code @Cacheable} flow handled by {@link #cacheManager(RedisConnectionFactory)}.
     *
     * <p><b>Replaces:</b> no COBOL equivalent &mdash; caching is additive
     * (AAP &sect;0.7.1). Adapter classes encapsulate every infrastructure
     * call (per AAP &sect;0.7.1 "never inline AWS SDK calls in business
     * logic"), and {@code CacheService} is the adapter that fronts manual
     * Redis access.</p>
     *
     * <h3>Serializer choices</h3>
     * <ul>
     *   <li><b>Key serializer:</b> {@link StringRedisSerializer} &mdash;
     *       plain UTF-8 strings. Redis keys are human-readable and the
     *       {@code carddemo:} prefix is visible in the Redis CLI for
     *       operational debugging.</li>
     *   <li><b>Value serializer:</b> {@link GenericJackson2JsonRedisSerializer}
     *       configured by {@link #buildCacheObjectMapper()}. JSON is chosen
     *       over Java serialization for:
     *       <ul>
     *         <li>Cross-language readability (Redis CLI {@code GET} returns
     *             human-readable JSON, not opaque Java bytes)</li>
     *         <li>Schema evolution safety (Jackson tolerates added fields
     *             without breaking deserialization of older entries)</li>
     *         <li>Inter-service interoperability (other services or operational
     *             tools can read cache entries directly)</li>
     *       </ul></li>
     *   <li>The same serializer pair is applied to both the value and the
     *       hash value, since Redis hash structures store the same kind of
     *       payload as plain values for our use cases.</li>
     * </ul>
     *
     * @param connectionFactory the {@link RedisConnectionFactory} bean
     *                          produced by {@link #redisConnectionFactory()}
     * @return the configured RedisTemplate (singleton scope, fully initialized)
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        // Replaces: no COBOL source — additive adapter-layer infrastructure
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Key serializer: plain UTF-8 strings for human-readable keys
        StringRedisSerializer keySerializer = new StringRedisSerializer();
        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);

        // Value serializer: Jackson JSON with safe polymorphic typing
        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(buildCacheObjectMapper());
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        // afterPropertiesSet() finalizes the template's internal serializer
        // wiring; required when the template is constructed manually (rather
        // than auto-wired by Spring Data's RedisTemplateAutoConfiguration).
        template.afterPropertiesSet();
        return template;
    }

    // -----------------------------------------------------------------------
    // @Bean: CacheManager (Spring Cache abstraction)
    // -----------------------------------------------------------------------

    /**
     * Builds the Spring {@link CacheManager} backed by Redis, enabling the
     * cache-aside pattern (AAP &sect;0.7.1) via Spring's {@code @Cacheable},
     * {@code @CacheEvict}, and {@code @CachePut} annotations on service
     * methods.
     *
     * <p><b>Replaces:</b> no COBOL equivalent &mdash; caching is additive
     * (AAP &sect;0.7.1). Account balance lookups during transaction
     * authorization are the primary beneficiary; per AAP &sect;0.7.2, this
     * "reduces RDS read load by caching high-frequency account balance
     * lookups".</p>
     *
     * <h3>Default cache configuration</h3>
     * <ul>
     *   <li><b>TTL:</b> {@link #defaultTtlMillis} (default 300000 ms = 5 min)
     *       &mdash; aligned with transaction frequency per AAP &sect;0.7.1.</li>
     *   <li><b>Null value caching:</b> controlled by {@link #cacheNullValues}
     *       (default {@code false}). Per AAP &sect;0.7.1, financial
     *       systems do not cache misses so that newly-provisioned accounts
     *       are immediately visible to subsequent reads.</li>
     *   <li><b>Key prefix:</b> {@link #keyPrefix} ({@code carddemo:})
     *       applied per-cache-name when {@link #useKeyPrefix} is true.
     *       Cache keys end up as {@code carddemo:<cacheName>::<keyValue>}.</li>
     *   <li><b>Key serializer:</b> {@link StringRedisSerializer}.</li>
     *   <li><b>Value serializer:</b> {@link GenericJackson2JsonRedisSerializer}
     *       sharing the same {@link ObjectMapper} configuration as
     *       {@link #redisTemplate(RedisConnectionFactory)}.</li>
     * </ul>
     *
     * <h3>{@code @Primary} rationale</h3>
     * <p>Marked {@code @Primary} so Spring resolves this bean by default
     * when a {@code CacheManager} dependency is autowired without an
     * explicit qualifier &mdash; e.g., when {@code @Cacheable} on a service
     * method searches for the {@code CacheManager} during method-interceptor
     * setup. If additional cache backends are introduced later (e.g., a
     * {@code Caffeine} L1 cache fronting Redis), this annotation must be
     * reconsidered.</p>
     *
     * <h3>Per-cache overrides</h3>
     * <p>For the initial migration scope all caches share the default TTL.
     * Per-cache overrides may later be added via
     * {@code RedisCacheManager.builder(...).withCacheConfiguration(name, cfg)}.
     * The {@code application.yml} property tree already declares
     * per-domain TTL hints
     * ({@code carddemo.cache.account-balance-ttl-seconds},
     * {@code carddemo.cache.card-detail-ttl-seconds},
     * {@code carddemo.cache.reference-data-ttl-seconds}) that future
     * iterations can wire into per-cache configurations.</p>
     *
     * @param connectionFactory the {@link RedisConnectionFactory} bean produced
     *                          by {@link #redisConnectionFactory()}
     * @return the configured cache manager (singleton scope, {@code @Primary})
     */
    @Bean
    @Primary
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // Cache-aside pattern per AAP §0.7.1 — TTL aligned to transaction frequency
        RedisCacheConfiguration cacheConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMillis(defaultTtlMillis))
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer(buildCacheObjectMapper())));

        if (!cacheNullValues) {
            // Per AAP §0.7.1 — financial systems do not cache misses
            cacheConfig = cacheConfig.disableCachingNullValues();
        }
        if (useKeyPrefix) {
            // Namespaces cache entries within a shared Redis instance
            cacheConfig = cacheConfig.prefixCacheNameWith(keyPrefix);
        }

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(cacheConfig)
                .build();
    }

    // -----------------------------------------------------------------------
    // Private helper: ObjectMapper for cache value (de)serialization
    // -----------------------------------------------------------------------

    /**
     * Builds a Jackson {@link ObjectMapper} configured for safe Redis cache
     * (de)serialization of CardDemo domain entities and DTOs.
     *
     * <p><b>Replaces:</b> no COBOL equivalent &mdash; the cache layer is
     * additive (AAP &sect;0.7.1). Domain object marshalling on the
     * mainframe was implicit in COBOL record layouts (fixed-position byte
     * arrays); the migrated stack uses JSON over Redis for cache value
     * representation.</p>
     *
     * <h3>Configuration</h3>
     * <ul>
     *   <li><b>{@link JavaTimeModule}:</b> registers serializers for
     *       {@code java.time.*} (LocalDate, LocalDateTime, Instant,
     *       OffsetDateTime). Essential for caching JPA entities like
     *       {@code Account}, {@code Card}, {@code Transaction} whose
     *       business date fields (cardExpirationDate, accountOpenDate)
     *       use {@code java.time.LocalDate} per AAP &sect;0.6.1
     *       ("{@code java.time} replaces COBOL LE CEEDAYS").</li>
     *   <li><b>Disable {@link SerializationFeature#WRITE_DATES_AS_TIMESTAMPS}:</b>
     *       emit ISO-8601 strings (e.g., {@code "2026-05-20"}) instead of
     *       epoch timestamps. ISO-8601 is human-readable in the Redis CLI
     *       and avoids the ambiguity of epoch units (seconds vs.
     *       milliseconds).</li>
     *   <li><b>Visibility {@code ANY} on all property accessors:</b> permits
     *       serialization of private fields on DTOs and entities without
     *       requiring getters. Lombok-free POJOs and {@code record}-style
     *       types both serialize cleanly.</li>
     *   <li><b>Safe polymorphic typing via {@link BasicPolymorphicTypeValidator}:</b>
     *       activates Jackson default typing so that polymorphic domain
     *       objects (interface fields, abstract base classes) deserialize
     *       to the correct concrete type. The validator is a strict
     *       subtype-only whitelist restricted to
     *       {@code com.awsm2.carddemo.*}, {@code java.util.*},
     *       {@code java.lang.*}, {@code java.math.*}, and {@code java.time.*}
     *       &mdash; NOT the legacy unsafe {@code LaissezFaireSubTypeValidator}.
     *       <p>An explicit
     *       {@link BasicPolymorphicTypeValidator.Builder#denyForExactBaseType(Class)
     *       denyForExactBaseType(Object.class)} rule blocks any attempt to
     *       deserialize a JSON value whose nominal base type is the raw
     *       {@link Object} class &mdash; this is the attack surface the
     *       FasterXML team explicitly documents for shutting down Jackson
     *       RCE gadgets (CVE-2017-7525 / CVE-2017-15095 family). The
     *       previous configuration that called {@code allowIfBaseType(Object.class)}
     *       defeated the subtype whitelist because any class assignment-
     *       compatible with {@link Object} (i.e. every Java type) would
     *       be admitted; the current configuration relies solely on
     *       {@code allowIfSubType} for positive matches and on
     *       {@code denyForExactBaseType} as belt-and-braces protection
     *       against polymorphism rooted at {@link Object}.
     *       This prevents deserialization gadgets in arbitrary classpath
     *       packages (RCE protection per OWASP A08:2021).</li>
     *   <li><b>{@link com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping#NON_FINAL}:</b>
     *       embeds a type tag for non-final types only (final types like
     *       {@link String}, {@link Integer}, primitive wrappers do not
     *       need the hint).</li>
     *   <li><b>{@link JsonTypeInfo.As#PROPERTY}:</b> embeds the type tag as
     *       a JSON property ({@code "@class": "..."}) for round-trip safety.</li>
     * </ul>
     *
     * @return a fully configured, thread-safe {@link ObjectMapper}
     */
    private ObjectMapper buildCacheObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);

        // Whitelist-based polymorphic typing — prevents Jackson RCE gadgets
        // while permitting safe deserialization of CardDemo domain types and
        // common JDK collection / number / time types.
        //
        // SECURITY NOTE: per Jackson `BasicPolymorphicTypeValidator` semantics,
        // `allowIfBaseType(Object.class)` would admit ANY legal subtype for
        // the `Object` base type — defeating the subtype whitelist below and
        // re-opening the deserialization-gadget attack surface that the
        // explicit `allowIfSubType(...)` rules are designed to close. This
        // configuration therefore avoids `allowIfBaseType(Object.class)` and
        // relies solely on `allowIfSubType` for positive matches.
        //
        // `denyForExactBaseType(Object.class)` is added explicitly per
        // FasterXML guidance to block any JSON payload whose nominal base
        // type is the raw `Object` class — the classic Jackson RCE gadget
        // pattern (CVE-2017-7525 / CVE-2017-15095 family) embeds an
        // `@class` hint under a base type of `Object` to coerce the
        // deserializer into instantiating arbitrary classes. This deny
        // rule short-circuits that pattern.
        BasicPolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .denyForExactBaseType(Object.class)
                .allowIfSubType("com.awsm2.carddemo.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.lang.")
                .allowIfSubType("java.math.")
                .allowIfSubType("java.time.")
                .build();
        mapper.activateDefaultTyping(
                ptv,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);

        return mapper;
    }
}

