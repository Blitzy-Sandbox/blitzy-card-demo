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

import org.junit.jupiter.api.Test;
import org.springframework.cloud.context.config.annotation.RefreshScope;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reflection-based assertions that {@link KafkaConfig#producerFactory()},
 * {@link KafkaConfig#kafkaTemplate(org.springframework.kafka.core.ProducerFactory)},
 * and {@link KafkaConfig#consumerFactory()} are annotated
 * {@link RefreshScope @RefreshScope} per AAP &sect;0.6.4 (Secrets Rotation
 * Without Restart) and Code Review CP7 (MAJOR &mdash; Kafka credential
 * rotation must not require JVM restart).
 *
 * <h2>Background</h2>
 * <p>The Code Review CP7 final report identified that {@code producerFactory()}
 * and {@code consumerFactory()} were not {@code @RefreshScope}, meaning rotated
 * MSK SASL/IAM credentials would not propagate to running producers/consumers
 * until the ECS task was restarted. AAP &sect;0.6.4 explicitly requires Kafka
 * producer/consumer configurations to refresh without restart.</p>
 *
 * <h2>What this test asserts</h2>
 * <p>For each of the three factory bean methods, this test confirms via
 * Java reflection that the {@link RefreshScope @RefreshScope} annotation is
 * present on the declared method. This guards against silent removal of the
 * annotation during future edits &mdash; without the annotation, the bean
 * would be a singleton and would not be destroyed and recreated on
 * {@link org.springframework.cloud.context.refresh.ContextRefresher#refresh()}
 * invocations from {@link SecretsManagerConfig}.</p>
 *
 * <h2>Why a reflection test rather than a full Spring context test</h2>
 * <p>Spring's {@code @RefreshScope} is honoured only when a
 * {@link org.springframework.cloud.context.scope.refresh.RefreshScope} bean
 * is registered in the application context. Loading the full
 * {@link KafkaConfig} would require a running Kafka broker connection (Spring
 * Boot's {@link org.springframework.boot.autoconfigure.kafka.KafkaProperties}
 * binding triggers credential resolution at bean-creation time). Reflection
 * provides a deterministic, broker-independent check that the annotation is
 * present where AAP &sect;0.6.4 mandates.</p>
 *
 * <p>The full end-to-end rotation flow (SQS &rarr; {@code ContextRefresher}
 * &rarr; bean rebuild &rarr; rotated credentials) is exercised by integration
 * tests under {@code src/test/java/com/awsm2/carddemo/integration/} that run
 * against LocalStack-emulated Secrets Manager + a {@code testcontainers}
 * Kafka broker.</p>
 */
class KafkaConfigRefreshScopeTest {

    /**
     * Confirms {@link KafkaConfig#producerFactory()} carries
     * {@link RefreshScope @RefreshScope} so that a Secrets Manager rotation
     * event rebuilds the producer factory and picks up rotated MSK
     * SASL/IAM credentials.
     */
    @Test
    void producerFactoryIsRefreshScoped() throws NoSuchMethodException {
        Method method = KafkaConfig.class.getDeclaredMethod("producerFactory");

        RefreshScope refreshScope = method.getAnnotation(RefreshScope.class);

        assertThat(refreshScope)
                .as("producerFactory() must be @RefreshScope per AAP §0.6.4 "
                        + "(Code Review CP7 MAJOR Kafka credential rotation) "
                        + "so that ContextRefresher.refresh() destroys and "
                        + "recreates the producer factory after a Secrets "
                        + "Manager rotation event")
                .isNotNull();
    }

    /**
     * Confirms {@link KafkaConfig#kafkaTemplate(
     * org.springframework.kafka.core.ProducerFactory)} carries
     * {@link RefreshScope @RefreshScope}. The template holds a strong
     * reference to its {@code ProducerFactory}, so refreshing only the
     * factory would leave the template's cached reference pointing at
     * the old factory.
     */
    @Test
    void kafkaTemplateIsRefreshScoped() throws NoSuchMethodException {
        Method method = KafkaConfig.class.getDeclaredMethod(
                "kafkaTemplate",
                org.springframework.kafka.core.ProducerFactory.class);

        RefreshScope refreshScope = method.getAnnotation(RefreshScope.class);

        assertThat(refreshScope)
                .as("kafkaTemplate(ProducerFactory) must be @RefreshScope "
                        + "per AAP §0.6.4 so it picks up the rebuilt "
                        + "ProducerFactory on rotation")
                .isNotNull();
    }

    /**
     * Confirms {@link KafkaConfig#consumerFactory()} carries
     * {@link RefreshScope @RefreshScope} so that a Secrets Manager rotation
     * event rebuilds the consumer factory and picks up rotated MSK
     * SASL/IAM credentials on the next listener container reconnect cycle.
     */
    @Test
    void consumerFactoryIsRefreshScoped() throws NoSuchMethodException {
        Method method = KafkaConfig.class.getDeclaredMethod("consumerFactory");

        RefreshScope refreshScope = method.getAnnotation(RefreshScope.class);

        assertThat(refreshScope)
                .as("consumerFactory() must be @RefreshScope per AAP §0.6.4 "
                        + "(Code Review CP7 MAJOR Kafka credential rotation) "
                        + "so that ContextRefresher.refresh() destroys and "
                        + "recreates the consumer factory after a Secrets "
                        + "Manager rotation event")
                .isNotNull();
    }
}
