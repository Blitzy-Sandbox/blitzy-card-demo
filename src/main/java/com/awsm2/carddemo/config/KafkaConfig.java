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

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring {@code @Configuration} wiring Amazon MSK (Apache Kafka) producer
 * and consumer factories per AAP &sect;0.6.5 "MSK Topic Ordering Guarantees".
 *
 * <p><b>Replaces:</b> CICS Transient Data Queue (TDQ) JOBS &mdash; the only
 * online-to-batch bridge in the source mainframe ({@code CORPT00C} &rarr;
 * {@code EXEC CICS WRITEQ TD QUEUE(JOBS)} &rarr; JES submission). The
 * cloud-native equivalent is:
 * {@code ReportSubmissionService} &rarr; {@code KafkaTemplate.send("report.requested")}
 * &rarr; Lambda trigger &rarr; Step Functions {@code SubmitReportJob}.</p>
 *
 * <h2>Topics consumed/produced (AAP &sect;0.6.5)</h2>
 * <ol>
 *   <li>{@code transaction.posted} &mdash; produced by
 *       {@code TransactionAddService}, {@code BillPaymentService}</li>
 *   <li>{@code account.updated} &mdash; produced by
 *       {@code AccountUpdateService}, {@code BillPaymentService},
 *       {@code InterestCalculationService}</li>
 *   <li>{@code ledger.balanced} &mdash; produced by end-of-day reconciliation</li>
 *   <li>{@code report.requested} &mdash; produced by
 *       {@code ReportSubmissionService}; consumed by Step Functions trigger
 *       Lambda</li>
 * </ol>
 *
 * <h2>Producer guarantees (AAP &sect;0.6.5)</h2>
 * <ul>
 *   <li>{@code acks=all} &mdash; full in-sync-replica acknowledgment before
 *       producer return (strongest durability guarantee).</li>
 *   <li>{@code enable.idempotence=true} &mdash; eliminates duplicates within
 *       a producer session via broker-side sequence-number tracking.</li>
 *   <li>{@code max.in.flight.requests.per.connection=5} &mdash; Kafka still
 *       preserves per-partition ordering with idempotence enabled.</li>
 *   <li>{@code retries=Integer.MAX_VALUE} &mdash; infinite retries within
 *       {@code delivery.timeout.ms} (defaults to 120s in application.yml).</li>
 * </ul>
 *
 * <h2>Consumer guarantees (AAP &sect;0.6.5)</h2>
 * <ul>
 *   <li>{@code enable.auto.commit=false} &mdash; manual offset commit only.
 *       Listeners must call {@code Acknowledgment.acknowledge()} after
 *       successful processing.</li>
 *   <li>{@code isolation.level=read_committed} &mdash; consumer only sees
 *       records from committed transactional producer batches; uncommitted
 *       records are skipped.</li>
 *   <li>{@link ContainerProperties.AckMode#MANUAL} &mdash; listener container
 *       hands acknowledgment control to application code.</li>
 *   <li>{@link DefaultErrorHandler} + {@link DeadLetterPublishingRecoverer}
 *       implement poison-message routing to {@code &lt;topic&gt;.DLT} after
 *       3 retry attempts with 1-second back-off.</li>
 * </ul>
 *
 * <h2>Per-account ordering invariant</h2>
 * <p>Producers MUST use {@code account-id} (zero-padded 11-digit string) as
 * the partition key on every event for {@code transaction.posted},
 * {@code account.updated}, and {@code ledger.balanced}. The default Kafka
 * partitioner hashes the key bytes with murmur2, guaranteeing all events
 * for a given account land on a single partition, preserving the strict
 * ordering invariant required by financial transaction processing. This
 * invariant is enforced upstream in {@code KafkaEventPublisher}.</p>
 *
 * <h2>Security &mdash; MSK IAM authentication (AAP &sect;0.6.6)</h2>
 * <p>Production uses SASL_SSL with the AWS_MSK_IAM mechanism, configured
 * declaratively via {@code spring.kafka.properties.*} entries in
 * {@code application-prod.yml}. The {@code software.amazon.msk:aws-msk-iam-auth}
 * Maven coordinate provides the {@code IAMLoginModule} and
 * {@code IAMClientCallbackHandler} classes referenced by the JAAS config;
 * this configuration class deliberately does NOT import any AWS SDK
 * classes &mdash; MSK transport security is handled by classpath-resident
 * JAAS providers, NOT by code paths inside this configuration.</p>
 *
 * <h2>Profile-aware wiring</h2>
 * <p>This class delegates the full property-to-Kafka-property mapping to
 * Spring Boot's {@link KafkaProperties} auto-configuration. Each active
 * profile ({@code local}, {@code dev}, {@code prod}, {@code test}) supplies
 * its own bootstrap-servers, security.protocol, sasl.mechanism, and JAAS
 * configuration via {@code spring.kafka.*} property overlays. Defensive
 * overrides forced inside the bean factories below ensure the AAP
 * &sect;0.6.5 invariants hold regardless of profile-specific YAML drift.</p>
 *
 * @see com.awsm2.carddemo.adapter.KafkaEventPublisher
 * @see com.awsm2.carddemo.adapter.KafkaEventConsumer
 * @see KafkaProperties
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    // ------------------------------------------------------------------------
    //  Constants
    // ------------------------------------------------------------------------

    /**
     * Dead-letter topic suffix appended to the source topic for
     * poison-message routing.
     *
     * <p>Example: a failure-during-processing of a record on
     * {@code transaction.posted} (after exhausting the retry policy) is
     * republished to {@code transaction.posted.DLT}. The DLT topics must
     * be pre-created in MSK by Terraform (see
     * {@code infrastructure/terraform/msk.tf} per AAP &sect;0.4.1).</p>
     */
    private static final String DLT_SUFFIX = ".DLT";

    /**
     * Retry back-off interval (milliseconds) used by the
     * {@link DefaultErrorHandler} before publishing a record to the
     * dead-letter topic.
     *
     * <p>1-second fixed back-off gives transient broker / network failures
     * a chance to recover without blocking the consumer thread for an
     * extended period. Combined with {@link #ERROR_HANDLER_MAX_ATTEMPTS},
     * yields a total worst-case latency of approximately 3 seconds before
     * a poison message is shunted to its DLT.</p>
     */
    private static final long ERROR_HANDLER_INTERVAL_MS = 1_000L;

    /**
     * Maximum retry attempts before routing a record to its dead-letter
     * topic via {@link DeadLetterPublishingRecoverer}.
     *
     * <p>Three attempts is the conservative default chosen per the agent
     * prompt Phase 12 &sect;8 &mdash; sufficient to absorb transient
     * broker / network errors without exhausting consumer threads on
     * persistently failing messages.</p>
     */
    private static final long ERROR_HANDLER_MAX_ATTEMPTS = 3L;

    // ------------------------------------------------------------------------
    //  Configuration fields
    // ------------------------------------------------------------------------

    /**
     * Spring Boot's auto-configured {@link KafkaProperties} &mdash; populated
     * from {@code spring.kafka.*} properties in {@code application.yml} and
     * the active profile overlay ({@code application-local.yml},
     * {@code application-dev.yml}, or {@code application-prod.yml}).
     *
     * <p>This config injects {@link KafkaProperties} rather than re-reading
     * each property individually, letting Spring Boot handle the full
     * property-to-Kafka-property mapping &mdash; bootstrap-servers,
     * security.protocol, sasl.mechanism, sasl.jaas.config,
     * sasl.client.callback.handler.class, type mappings, the entire
     * {@code spring.kafka.producer.properties.*} and
     * {@code spring.kafka.consumer.properties.*} trees, and Spring's own
     * {@code spring.json.*} key/value namespace conventions.</p>
     */
    private final KafkaProperties kafkaProperties;

    /**
     * CardDemo-specific consumer group identifier.
     *
     * <p>Resolved from {@code carddemo.kafka.consumer.group-id}
     * (centralised under the {@code carddemo} namespace per
     * {@code application.yml} Phase 15). Profile overlays may set
     * distinct values such as {@code carddemo-local}, {@code carddemo-dev},
     * {@code carddemo-prod} to prevent cross-environment offset collisions
     * if a developer accidentally points a local container at a non-local
     * broker.</p>
     *
     * <p>Applied via {@code putIfAbsent} on top of Spring Boot's
     * {@code spring.kafka.consumer.group-id} binding so that a profile
     * overlay can still win when explicitly set.</p>
     */
    @Value("${carddemo.kafka.consumer.group-id:carddemo-consumer}")
    private String consumerGroupId;

    /**
     * Listener container concurrency &mdash; the number of consumer
     * threads created per {@code @KafkaListener} method.
     *
     * <p>Resolved from {@code spring.kafka.listener.concurrency}; defaults
     * to 3 for local/dev and is typically raised to match topic partition
     * count in production (6 per the {@code prod} profile). Each thread
     * is assigned at least one partition by the consumer-group coordinator;
     * because per-account ordering is preserved by the partition-key
     * hashing scheme, raising concurrency increases throughput without
     * disrupting ordering.</p>
     */
    @Value("${spring.kafka.listener.concurrency:3}")
    private int listenerConcurrency;

    /**
     * Listener auto-startup flag bound from
     * {@code spring.kafka.listener.auto-startup}.
     *
     * <p>Production profiles keep the Spring Kafka default ({@code true}) so
     * MSK consumers start with the application. The test profile sets this
     * value to {@code false}; Kafka-specific tests explicitly start only the
     * listener container they exercise, while full-context tests avoid opening
     * placeholder broker connections during JVM shutdown.</p>
     */
    @Value("${spring.kafka.listener.auto-startup:true}")
    private boolean listenerAutoStartup;

    /**
     * Trusted-packages whitelist for the {@link JsonDeserializer}.
     *
     * <p>Security-hardening property &mdash; the deserializer rejects any
     * inbound {@code __TypeId__} header pointing to a class outside this
     * comma-separated package list. PREVENTS arbitrary-class deserialisation
     * gadget attacks (CVE-class vulnerability), which is mandatory under
     * AAP &sect;0.6.6 (PCI-DSS &mdash; no untrusted-type deserialisation).</p>
     *
     * <p>Default restricts to {@code com.awsm2.carddemo.dto,com.awsm2.carddemo.domain}
     * &mdash; the only packages whose types are legitimately produced and
     * consumed by CardDemo's own services.</p>
     */
    @Value("${spring.kafka.consumer.properties.spring.json.trusted.packages:com.awsm2.carddemo.dto,com.awsm2.carddemo.domain}")
    private String trustedPackages;

    // ------------------------------------------------------------------------
    //  Constructor
    // ------------------------------------------------------------------------

    /**
     * Constructor injection of Spring Boot's {@link KafkaProperties}.
     *
     * <p>Constructor injection (as opposed to field injection) keeps the
     * bean immutable after construction and makes the dependency explicit
     * for unit testing &mdash; tests can instantiate {@code KafkaConfig}
     * directly with a {@link KafkaProperties} stub.</p>
     *
     * @param kafkaProperties the Spring Boot Kafka properties holder bound
     *                        from {@code spring.kafka.*} in the active
     *                        profile's YAML configuration
     */
    public KafkaConfig(KafkaProperties kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
    }

    // ------------------------------------------------------------------------
    //  Producer factory + KafkaTemplate
    // ------------------------------------------------------------------------

    /**
     * Creates the {@link ProducerFactory} for all CardDemo Kafka producers
     * with idempotent send semantics per AAP &sect;0.6.5.
     *
     * <p>The factory inherits the full {@code spring.kafka.producer.*}
     * property tree from Spring Boot's {@link KafkaProperties} binding
     * (including bootstrap-servers, security.protocol, SASL_SSL + AWS_MSK_IAM
     * JAAS configuration in dev/prod, compression-type=lz4, linger.ms=50,
     * delivery.timeout.ms=120000, and the {@code spring.json.add.type.headers}
     * convention). On top of those auto-bound defaults, this method forces
     * a small set of mandatory overrides corresponding to the AAP
     * &sect;0.6.5 contract:</p>
     *
     * <ul>
     *   <li>{@code key.serializer=StringSerializer} &mdash; the partition
     *       key is the zero-padded 11-digit account ID, kept as a UTF-8
     *       string for stable cross-language murmur2 hashing.</li>
     *   <li>{@code value.serializer=JsonSerializer} &mdash; events are
     *       marshalled as JSON for human inspectability in Kafka topic
     *       browsers and to support polyglot consumers.</li>
     *   <li>{@code acks=all} &mdash; await acknowledgment from every
     *       in-sync replica before treating a send as successful.</li>
     *   <li>{@code enable.idempotence=true} &mdash; producer sequence
     *       numbers + broker-side de-duplication eliminate within-session
     *       duplicates.</li>
     *   <li>{@code max.in.flight.requests.per.connection=5} &mdash; the
     *       Kafka-enforced upper bound that still permits idempotent
     *       producer ordering guarantees.</li>
     *   <li>{@code retries=Integer.MAX_VALUE} &mdash; the broker decides
     *       when to surface failure via {@code delivery.timeout.ms},
     *       not the producer's retry counter.</li>
     * </ul>
     *
     * <p>The {@code null} argument passed to
     * {@link KafkaProperties#buildProducerProperties(org.springframework.boot.ssl.SslBundles)}
     * indicates that no Spring {@code SslBundles} is required &mdash; MSK
     * IAM auth uses SASL_SSL natively (the {@code IAMClientCallbackHandler}
     * supplies the TLS context); a Spring SSL bundle is unnecessary.</p>
     *
     * @return the configured producer factory, ready to instantiate
     *         {@code KafkaProducer} instances
     */
    // -------------------------------------------------------------------------
    // Code Review CP7 (MAJOR — Secrets Rotation / AAP §0.6.4):
    // {@link RefreshScope @RefreshScope} causes this bean to be destroyed and
    // re-instantiated whenever {@link
    // org.springframework.cloud.context.refresh.ContextRefresher#refresh()} is
    // invoked by {@link com.awsm2.carddemo.config.SecretsManagerConfig} on
    // receiving an AWS Secrets Manager rotation event. Re-instantiation rebuilds
    // the producer property map from {@code kafkaProperties.buildProducerProperties(...)}
    // — which itself reads from the (now-refreshed) Spring Environment, so any
    // rotated MSK SASL/IAM credential or bootstrap-server endpoint is picked up
    // without restarting the JVM.
    //
    // The downstream {@link #kafkaTemplate(ProducerFactory)} bean is also
    // annotated {@code @RefreshScope} so it picks up the rebuilt
    // {@link ProducerFactory} on rotation; existing in-flight sends drain via
    // the producer's own {@code delivery.timeout.ms} envelope.
    // -------------------------------------------------------------------------
    @Bean
    @RefreshScope
    public ProducerFactory<String, Object> producerFactory() {
        // Replaces: CICS TDQ producer semantics (EXEC CICS WRITEQ TD)
        //           + sequential PS / GDG file output (DALYREJS, SYSTRAN, TRANREPT)
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties(null));

        // -----------------------------------------------------------------
        // Defensive overrides per AAP §0.6.5 — guarantee these properties
        // hold even if the active profile's YAML omits them or overrides
        // them to incompatible values. `putIfAbsent` lets profile overlays
        // legitimately customise serializers; `put` forces the durability
        // and idempotence guarantees that are non-negotiable.
        // -----------------------------------------------------------------
        props.putIfAbsent(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.putIfAbsent(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.putIfAbsent(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);

        // -----------------------------------------------------------------
        // QA Final-CP7 Finding F-MINOR-01 (MINOR) fix: propagate SLF4J MDC
        // entries (correlationId, traceId, tenant) onto outbound Kafka
        // record headers via the MdcHeaderProducerInterceptor below.
        // Headers are populated only when the per-thread MDC carries the
        // corresponding key — absent MDC entries do not emit empty
        // placeholder headers. See
        // {@link com.awsm2.carddemo.adapter.MdcHeaderProducerInterceptor}
        // for the full propagation contract.
        //
        // `putIfAbsent` lets profile overlays chain additional
        // interceptors (e.g., a security-headers interceptor) without
        // overriding the MDC propagator; the Kafka client invokes
        // interceptors in the comma-separated declaration order.
        // -----------------------------------------------------------------
        props.putIfAbsent(
                ProducerConfig.INTERCEPTOR_CLASSES_CONFIG,
                "com.awsm2.carddemo.adapter.MdcHeaderProducerInterceptor");

        return new DefaultKafkaProducerFactory<>(props);
    }

    /**
     * The {@link KafkaTemplate} used by
     * {@code com.awsm2.carddemo.adapter.KafkaEventPublisher} to publish
     * domain events and by {@link DeadLetterPublishingRecoverer} (wired in
     * {@link #kafkaListenerContainerFactory(ConsumerFactory, KafkaTemplate)})
     * to republish poison messages to dead-letter topics.
     *
     * <p>Spring Kafka 3.x enables Micrometer Observation API integration on
     * the template by default for distributed-tracing spans &mdash; no
     * additional configuration is required here for OpenTelemetry / X-Ray
     * propagation to work end-to-end.</p>
     *
     * @param producerFactory the producer factory bean from
     *                        {@link #producerFactory()}; injected by name
     *                        through Spring's bean container
     * @return the configured {@code KafkaTemplate} singleton
     */
    // -------------------------------------------------------------------------
    // Code Review CP7 (MAJOR — Secrets Rotation / AAP §0.6.4):
    // The template holds a strong reference to its {@link ProducerFactory}, so
    // refreshing only the factory bean would leave the template's cached
    // reference pointing at the old factory. Annotating this bean
    // {@code @RefreshScope} forces it to be re-instantiated alongside the
    // factory, ensuring rotated SASL/IAM credentials propagate end-to-end.
    // -------------------------------------------------------------------------
    @Bean
    @RefreshScope
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        // Replaces: CICS EXEC CICS WRITEQ TD QUEUE(JOBS)
        return new KafkaTemplate<>(producerFactory);
    }

    // ------------------------------------------------------------------------
    //  Consumer factory
    // ------------------------------------------------------------------------

    /**
     * Creates the {@link ConsumerFactory} for all CardDemo Kafka consumers
     * with read-committed isolation and manual offset commit per AAP
     * &sect;0.6.5.
     *
     * <p>As with {@link #producerFactory()}, the consumer factory inherits
     * the full {@code spring.kafka.consumer.*} property tree from Spring
     * Boot's {@link KafkaProperties} binding (bootstrap-servers,
     * security.protocol, SASL_SSL + AWS_MSK_IAM JAAS configuration,
     * max-poll-records, auto-offset-reset, session.timeout.ms, etc.).
     * The defensive overrides below enforce the AAP &sect;0.6.5 contract:</p>
     *
     * <ul>
     *   <li>{@code enable.auto.commit=false} &mdash; the consumer must
     *       acknowledge each record explicitly after successful processing;
     *       Spring's listener container drives this via
     *       {@link ContainerProperties.AckMode#MANUAL} configured in
     *       {@link #kafkaListenerContainerFactory(ConsumerFactory, KafkaTemplate)}.</li>
     *   <li>{@code isolation.level=read_committed} &mdash; even when no
     *       transactional producer is currently in use, this is the safer
     *       default; it future-proofs the consumer for any subsequent
     *       transactional producer rollout.</li>
     *   <li>{@code group.id} &mdash; sourced from
     *       {@code carddemo.kafka.consumer.group-id} via {@code putIfAbsent}
     *       so that a profile-supplied {@code spring.kafka.consumer.group-id}
     *       still wins when explicitly set.</li>
     *   <li>Both key and value deserializers are wrapped in
     *       {@link ErrorHandlingDeserializer}. Wrapping prevents a single
     *       poison message from crashing the listener container: instead,
     *       the inner deserialization exception is captured into a
     *       {@link DeserializationException} record header, allowing the
     *       {@link DefaultErrorHandler} to short-circuit retry and route
     *       the record to its DLT immediately.</li>
     *   <li>{@link JsonDeserializer#TRUSTED_PACKAGES} restricts polymorphic
     *       JSON deserialisation to CardDemo's own DTO and domain packages
     *       &mdash; defends against gadget-class instantiation attacks.</li>
     *   <li>{@link JsonDeserializer#USE_TYPE_INFO_HEADERS} permits the
     *       {@code __TypeId__} header (when present) to select the
     *       deserialiser's target type; when absent, the deserialiser
     *       falls back to the {@code @KafkaListener}-declared target type
     *       on the handler method.</li>
     * </ul>
     *
     * @return the configured consumer factory, ready to instantiate
     *         {@code KafkaConsumer} instances inside the listener container
     */
    // -------------------------------------------------------------------------
    // Code Review CP7 (MAJOR — Secrets Rotation / AAP §0.6.4):
    // Symmetric to {@link #producerFactory()}: refresh-scoped so that rotated
    // MSK SASL/IAM credentials are picked up on the next listener container
    // restart cycle without restarting the JVM. The downstream
    // {@link #kafkaListenerContainerFactory(ConsumerFactory, KafkaTemplate)}
    // is also annotated {@code @RefreshScope} so it picks up the rebuilt
    // {@link ConsumerFactory}.
    //
    // Note on consumer rotation semantics: refreshing the
    // {@link ConsumerFactory} does NOT immediately disconnect existing
    // KafkaConsumer instances already polling inside running listener
    // containers — those continue to use the credentials they were configured
    // with at creation time. When MSK's IAM credential expires (which it does
    // continuously under SASL_SSL+IAM auth — the AWS MSK IAM client refreshes
    // credentials transparently from the ECS task role on each authentication
    // round-trip), the next reconnect cycle uses the rotated configuration.
    // For a forced consumer rebuild, operators can invoke the
    // {@code KafkaListenerEndpointRegistry} bean to stop and restart the
    // containers after rotation; the recommended pattern is to rely on the
    // continuous credential refresh that MSK IAM auth provides.
    // -------------------------------------------------------------------------
    @Bean
    @RefreshScope
    public ConsumerFactory<String, Object> consumerFactory() {
        // Replaces: CICS EXEC CICS READQ TD QUEUE(JOBS)
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(null));

        // -----------------------------------------------------------------
        // Defensive overrides per AAP §0.6.5 — non-negotiable consumer
        // contract for the financial transaction stream.
        // -----------------------------------------------------------------
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.putIfAbsent(ConsumerConfig.GROUP_ID_CONFIG, consumerGroupId);

        // -----------------------------------------------------------------
        // Error-handling deserializers wrap the real serializers so that
        // poison messages become DeserializationException records routed
        // to their DLT instead of crashing the consumer thread. The inner
        // deserializer classes are configured via the
        // ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS /
        // VALUE_DESERIALIZER_CLASS property keys.
        // -----------------------------------------------------------------
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);

        // -----------------------------------------------------------------
        // JsonDeserializer security + type-resolution hardening (AAP §0.6.6).
        // -----------------------------------------------------------------
        props.put(JsonDeserializer.TRUSTED_PACKAGES, trustedPackages);
        // Type-resolution contract (CP4 QA Issue #4): the producer side is
        // explicitly configured with `spring.json.add.type.headers=false`
        // (see application.yml L263 producer block) because events are
        // routed by topic, not by Java class. Requiring `__TypeId__`
        // headers on the consumer side here would mismatch with that
        // producer contract and silently route 100% of events to the DLT
        // with "No type information in headers and no default type
        // provided" — exactly the breakage the QA observed. Therefore we
        // disable USE_TYPE_INFO_HEADERS and rely on each `@KafkaListener`
        // method to declare its target type via the `@Payload` parameter
        // (the listener's container factory + payload type wins when no
        // header is present), which preserves strong per-listener type
        // safety without coupling producer and consumer to a shared class
        // name.
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        // Explicit safety net (AAP §0.6.5): if a future listener forgets
        // to declare its `@Payload` type (e.g., a `ConsumerRecord<?,?>` or
        // raw `Object` payload), JSON bodies should still resolve to a
        // generic LinkedHashMap rather than throwing
        // IllegalStateException. JsonDeserializer applies the default
        // type only when no header is present AND no listener-declared
        // type can be inferred from the @KafkaListener method signature.
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE,
                "java.util.LinkedHashMap");

        return new DefaultKafkaConsumerFactory<>(props);
    }

    // ------------------------------------------------------------------------
    //  Listener container factory + error handler
    // ------------------------------------------------------------------------

    /**
     * Creates the {@link ConcurrentKafkaListenerContainerFactory} that
     * Spring Kafka uses to instantiate listener containers for every
     * {@code @KafkaListener}-annotated method in the application.
     *
     * <h2>Configuration</h2>
     * <ul>
     *   <li><b>Concurrency:</b> sourced from
     *       {@code spring.kafka.listener.concurrency} (default 3 for local
     *       / dev, 6 for prod per {@code application-prod.yml}). Each
     *       listener thread is assigned at least one partition by the
     *       broker-side consumer-group coordinator.</li>
     *   <li><b>Ack mode:</b> {@link ContainerProperties.AckMode#MANUAL}
     *       per AAP &sect;0.6.5 &mdash; the listener method MUST call
     *       {@code Acknowledgment.acknowledge()} (or {@code nack(...)} on
     *       failure) before the container will commit the corresponding
     *       offset to the broker.</li>
     *   <li><b>Common error handler:</b> {@link DefaultErrorHandler} backed
     *       by a {@link FixedBackOff} of
     *       {@value #ERROR_HANDLER_INTERVAL_MS}ms &times;
     *       {@value #ERROR_HANDLER_MAX_ATTEMPTS} attempts, with a
     *       {@link DeadLetterPublishingRecoverer} routing the final-failure
     *       record to {@code &lt;source-topic&gt;.DLT} on the same partition
     *       number to preserve partition affinity for downstream DLQ
     *       inspectors.</li>
     *   <li><b>Non-retryable exceptions:</b>
     *       {@link DeserializationException} is registered as non-retryable
     *       &mdash; a malformed record will always fail to deserialize, so
     *       retrying simply wastes consumer thread time before the eventual
     *       DLT publish. The error handler short-circuits straight to the
     *       recoverer for any record whose underlying exception is a
     *       {@code DeserializationException} (typically produced by the
     *       {@link ErrorHandlingDeserializer} wrappers configured on the
     *       consumer factory).</li>
     * </ul>
     *
     * <h2>Dead-letter topic naming convention</h2>
     * <p>Each source topic has a corresponding DLT named
     * {@code &lt;source-topic&gt;.DLT}. For example, a poison record on
     * {@code transaction.posted} (after exhausting retries) is republished
     * to {@code transaction.posted.DLT} on the same partition number.
     * The DLT topics are Terraform-managed and pre-created in MSK; the
     * application never auto-creates them at runtime.</p>
     *
     * @param consumerFactory the consumer factory bean from
     *                        {@link #consumerFactory()}
     * @param kafkaTemplate the producer template from
     *                      {@link #kafkaTemplate(ProducerFactory)} used by
     *                      the {@link DeadLetterPublishingRecoverer} to
     *                      publish poison records to the dead-letter topic
     * @return the configured listener container factory; Spring Kafka picks
     *         it up automatically because its bean name matches the
     *         convention {@code kafkaListenerContainerFactory}
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            KafkaTemplate<String, Object> kafkaTemplate) {
        // Replaces: pseudo-conversational CICS task dispatch (RETURN TRANSID)
        //           + JES batch initiator polling
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(listenerConcurrency);
        factory.setAutoStartup(listenerAutoStartup);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);

        // -----------------------------------------------------------------
        // Poison-message routing — retry 3 times with 1-second back-off,
        // then publish to <topic>.DLT on the same partition number to
        // preserve partition affinity for downstream forensic tooling.
        // -----------------------------------------------------------------
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) ->
                        new TopicPartition(record.topic() + DLT_SUFFIX, record.partition())
        );

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(ERROR_HANDLER_INTERVAL_MS, ERROR_HANDLER_MAX_ATTEMPTS)
        );
        // -----------------------------------------------------------------
        // Do NOT retry deserialization failures — they will always fail.
        // The ErrorHandlingDeserializer captures the original exception
        // into a DeserializationException record header; the error handler
        // short-circuits straight to the recoverer for these records.
        // -----------------------------------------------------------------
        errorHandler.addNotRetryableExceptions(DeserializationException.class);
        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }
}
