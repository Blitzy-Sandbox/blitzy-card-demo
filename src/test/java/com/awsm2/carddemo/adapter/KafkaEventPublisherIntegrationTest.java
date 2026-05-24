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

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Testcontainers-backed integration coverage for
 * {@link KafkaEventPublisher}.
 *
 * <p><b>// Replaces: CICS TDQ and inter-step batch events</b> with Kafka
 * topics partitioned by account ID. This test verifies an actual broker
 * send and confirms that the account ID becomes the zero-padded Kafka
 * record key used for partition ordering.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("KafkaEventPublisher — Testcontainers Kafka integration")
class KafkaEventPublisherIntegrationTest {

    private static final String LEDGER_TOPIC = "ledger.balanced";

    @Container
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    private static KafkaTemplate<String, Object> kafkaTemplate;
    private static KafkaEventPublisher publisher;

    @BeforeAll
    static void setUp() throws Exception {
        Map<String, Object> producerConfig = new HashMap<>();
        producerConfig.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        producerConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        producerConfig.put(ProducerConfig.ACKS_CONFIG, "all");
        producerConfig.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        producerConfig.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        producerConfig.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);

        kafkaTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerConfig));
        publisher = new KafkaEventPublisher(
                kafkaTemplate,
                "transaction.posted",
                "account.updated",
                LEDGER_TOPIC,
                "report.requested");

        try (AdminClient adminClient = AdminClient.create(
                Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            adminClient.createTopics(Collections.singletonList(
                    new NewTopic(LEDGER_TOPIC, 3, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
    }

    @AfterAll
    static void tearDown() {
        if (kafkaTemplate != null) {
            kafkaTemplate.destroy();
        }
    }

    @Test
    @DisplayName("publishLedgerBalanced sends record keyed by zero-padded account ID")
    void publishLedgerBalancedUsesAccountIdPartitionKey() throws Exception {
        String expectedKey = "00000012345";
        AtomicReference<ConsumerRecord<String, String>> consumedRecord = new AtomicReference<>();

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties())) {
            consumer.subscribe(Collections.singletonList(LEDGER_TOPIC));

            SendResult<String, Object> sendResult = publisher
                    .publishLedgerBalanced(12345L, Map.of("sequence", 1, "batchRunId", "kafka-it"))
                    .get(30, TimeUnit.SECONDS);

            assertThat(sendResult.getProducerRecord().key()).isEqualTo(expectedKey);
            assertThat(sendResult.getRecordMetadata().topic()).isEqualTo(LEDGER_TOPIC);

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(250))
                        .forEach(record -> {
                            if (expectedKey.equals(record.key())) {
                                consumedRecord.compareAndSet(null, record);
                            }
                        });
                assertThat(consumedRecord.get()).isNotNull();
            });
        }

        assertThat(consumedRecord.get().key()).isEqualTo(expectedKey);
        assertThat(consumedRecord.get().value()).contains("kafka-it");
    }

    private static Properties consumerProperties() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG,
                "carddemo-adapter-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        return props;
    }
}