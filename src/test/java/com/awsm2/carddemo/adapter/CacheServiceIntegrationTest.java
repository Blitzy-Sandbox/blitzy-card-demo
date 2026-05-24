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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Testcontainers-backed integration coverage for {@link CacheService}.
 *
 * <p><b>// Enables: ElastiCache Redis cache-aside</b> per AAP &sect;0.7.1.
 * The test exercises the adapter against a real Redis container so TTL
 * and eviction semantics are verified beyond Mockito-only interactions.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("CacheService — Testcontainers Redis integration")
class CacheServiceIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static CacheService cacheService;

    @BeforeAll
    static void setUp() {
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(),
                REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();

        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
        GenericJackson2JsonRedisSerializer jsonSerializer =
                GenericJackson2JsonRedisSerializer.builder()
                        .typeHintPropertyName("@class")
                        .defaultTyping(true)
                        .build();
        redisTemplate.setValueSerializer(jsonSerializer);
        redisTemplate.setHashValueSerializer(jsonSerializer);
        redisTemplate.afterPropertiesSet();

        cacheService = new CacheService(redisTemplate, 2);
    }

    @AfterAll
    static void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    @DisplayName("put/get/evict round-trip through Redis")
    void putGetAndEvictRoundTripThroughRedis() {
        BalanceSnapshot balanceSnapshot = new BalanceSnapshot("100.00");

        cacheService.put("account", "00000012345",
                balanceSnapshot, Duration.ofSeconds(5));

        Optional<BalanceSnapshot> cached = cacheService.get(
                "account", "00000012345", BalanceSnapshot.class);
        assertThat(cached).isPresent();
        assertThat(cached.orElseThrow().getBalance()).isEqualTo("100.00");

        cacheService.evict("account", "00000012345");
        assertThat(cacheService.get(
                "account", "00000012345", BalanceSnapshot.class)).isEmpty();
    }

    @Test
    @DisplayName("put honors explicit TTL and expires the cached entry")
    void putHonorsExplicitTtl() {
        cacheService.put("account", "ttl-check", "cached-value", Duration.ofMillis(500));

        assertThat(cacheService.get("account", "ttl-check", String.class))
                .contains("cached-value");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(cacheService.get("account", "ttl-check", String.class)).isEmpty());
    }

    public static class BalanceSnapshot {
        private String balance;

        public BalanceSnapshot() {
            // Jackson constructor for Redis JSON deserialization.
        }

        public BalanceSnapshot(String balance) {
            this.balance = balance;
        }

        public String getBalance() {
            return balance;
        }

        public void setBalance(String balance) {
            this.balance = balance;
        }
    }
}