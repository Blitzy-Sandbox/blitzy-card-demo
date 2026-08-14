/*
 * ******************************************************************
 * Program     : HealthIndicators.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 health indicators
 * Function    : Composite readiness/liveness health over PostgreSQL, S3
 *               object storage and the SQS report queue.
 * Replaces    : app/jcl/OPENFIL.jcl + app/jcl/CLOSEFIL.jcl (CEMT SET FIL
 *               for TRANSACT, CCXREF, ACCTDAT, CXACAIX, USRSEC) @ 7756d89
 * Source      : app/jcl/OPENFIL.jcl:L26-L30 @ 7756d89
 * Source      : app/jcl/CLOSEFIL.jcl:L26-L30 @ 7756d89
 * Source      : app/csd/CARDDEMO.CSD:L1,L13,L25,L37,L50,L63,L76,L88 @ 7756d89
 *               (8 DEFINE FILE entries)
 * Source      : app/csd/CARDDEMO.CSD:L499-L505 @ 7756d89
 *               (DEFINE TDQUEUE(JOBS) RECORDSIZE(80) -> SQS FIFO)
 * Note        : No COBOL analogue for health probing exists; new capability
 *               per Rule 1 Clause A.
 * ******************************************************************
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
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.observability;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.ListTopicsRequest;
import software.amazon.awssdk.services.sns.model.ListTopicsResponse;
import software.amazon.awssdk.services.sns.model.Topic;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

/**
 * Supplies every dependency contributor of the composite readiness probe that replaces the legacy
 * CICS file-availability jobs.
 *
 * <h2>What it does</h2>
 *
 * <p>This class contributes exactly <strong>four</strong> Spring Boot Actuator {@link HealthIndicator}
 * beans - one for Amazon S3 object storage, one for the relational store, one for the SQS FIFO report
 * queue and one for the SNS operator notification topic - and nothing else. Together they form the
 * complete composite readiness signal the migration requires: <em>database plus object storage plus
 * queue plus notifications</em>.
 *
 * <p><strong>Two corrections are recorded here rather than made silently, because both were
 * published claims.</strong> This paragraph previously said "exactly two ... and nothing else" and
 * attributed the {@code db} component to "the framework's own auto-configured contributor". Neither
 * was true when written: {@link #dbHealthIndicator()} already existed and already replaced the
 * framework's unbounded probe on that key, so the count was three rather than two and the {@code db}
 * attribution named the wrong owner. The count is now four because the notification contributor was
 * added in the same change - it was the one required cloud dependency readiness said nothing about,
 * so an instance whose topic had never been provisioned reported itself ready and failed only at the
 * first report submission.
 *
 * <h2>Why it exists: new capability, not a translation</h2>
 *
 * <p>The legacy system has <strong>no structured instrumentation</strong>. Its entire telemetry
 * surface is {@code DISPLAY} to SYSOUT across {@code app/cbl/**}, plus the four-character
 * file-status renderer at {@code app/cbl/CBTRN02C.cbl:L714-L727}. A search of the frozen corpus for
 * {@code prometheus}, {@code micrometer}, {@code opentelemetry}, {@code healthcheck} or
 * {@code actuator} matches nothing at all, and there is no metric, trace, health probe or service
 * level objective anywhere in it.
 *
 * <p>This file is therefore <strong>not</strong> a translation of existing behaviour and nothing in
 * it may be justified as "preserved for parity". It exists solely because Rule 1 Clause A requires
 * <em>"Observability: structured logs, meaningful errors, and measurable behavior (metrics/tracing
 * where relevant)"</em>, and it ships with the initial implementation rather than being deferred.
 *
 * <h2>Legacy provenance: exactly five CEMT-managed files</h2>
 *
 * <p>The Java analogue of {@code app/jcl/OPENFIL.jcl} and {@code app/jcl/CLOSEFIL.jcl}, whose
 * purpose was to make datasets available to the online CICS region. Each member issues five
 * operator commands through {@code EXEC PGM=SDSF}, one per file, at {@code :L26-L30} - {@code OPE}
 * in the first member and {@code CLO} in the second:
 *
 * <ul>
 *   <li>{@code TRANSACT} - the transaction cluster</li>
 *   <li>{@code CCXREF} - the card-to-account cross-reference</li>
 *   <li>{@code ACCTDAT} - the account cluster</li>
 *   <li>{@code CXACAIX} - the cross-reference alternate-index path</li>
 *   <li>{@code USRSEC} - the user-security cluster</li>
 * </ul>
 *
 * <p>All five are VSAM datasets, so all five migrate to the relational substrate and are covered by
 * the single {@code db} contributor - which is {@link #dbHealthIndicator()}, this class's bounded
 * replacement for the framework's unbounded one, rather than the framework's own. That is why the
 * five CEMT-managed files need no per-dataset probe here: one relational contributor covers all of
 * them, and the remaining three contributors cover the substrates that have no relational analogue.
 *
 * <p>Those five are a strict <em>subset</em> of the eight files the CICS resource definitions
 * declare - {@code ACCTDAT} at {@code app/csd/CARDDEMO.CSD:L1}, {@code CARDAIX} at {@code :L13},
 * {@code CARDDAT} at {@code :L25}, {@code CCXREF} at {@code :L37}, {@code CUSTDAT} at {@code :L50},
 * {@code CXACAIX} at {@code :L63}, {@code TRANSACT} at {@code :L76} and {@code USRSEC} at
 * {@code :L88} - which is the evidence that availability management was scoped to the online
 * region's critical file set rather than to every dataset. Conversely {@code TCATBALF},
 * {@code DISCGRP}, {@code TRANCATG} and {@code TRANTYPE} appear in <strong>no</strong>
 * {@code DEFINE FILE} entry at all: they are batch-only datasets, which is a second reason online
 * readiness is a database-level concern in the target rather than a per-dataset one.
 *
 * <p>The queue half descends from a different construct. {@code app/csd/CARDDEMO.CSD:L499-L505}
 * defines the online-to-batch bridge {@code DEFINE TDQUEUE(JOBS) GROUP(CARDDEMO)} with
 * {@code DESCRIPTION(SUBMIT JOBS FROM CICS)}, {@code TYPE(EXTRA) DATABUFFERS(1) DDNAME(INREADER)
 * ERROROPTION(IGNORE)}, {@code OPENTIME(INITIAL) TYPEFILE(OUTPUT) RECORDSIZE(80)} and
 * {@code RECORDFORMAT(FIXED) BLOCKFORMAT(UNBLOCKED) DISPOSITION(MOD)}. That transient data queue
 * becomes the SQS FIFO queue whose reachability the second bean probes. The {@code RECORDSIZE(80)}
 * geometry constrains nothing here - it survives as the queue-parameter record length in
 * configuration - and message-group ordering is a producer concern owned by
 * {@code com.cardemo.service.report.ReportSubmissionService}, never a health concern.
 *
 * <h2>Published bean names and the health component keys they become</h2>
 *
 * <p>Actuator derives a health component key by stripping the {@code HealthIndicator} suffix from
 * the bean name, so the four bean names below fix the four keys that
 * {@code management.endpoint.health.group.readiness.include} must list in
 * {@code src/main/resources/application.yml}:
 *
 * <table>
 *   <caption>Bean name to health component key mapping</caption>
 *   <thead>
 *     <tr><th>Bean name</th><th>Component key</th><th>Constant</th><th>Probes</th></tr>
 *   </thead>
 *   <tbody>
 *     <tr>
 *       <td>{@code s3HealthIndicator}</td>
 *       <td>{@code s3}</td>
 *       <td>{@link #S3_HEALTH_COMPONENT_NAME}</td>
 *       <td>The three configured buckets, by {@code HeadBucket}; then the batch-output bucket's
 *           versioning state, by {@code GetBucketVersioning}</td>
 *     </tr>
 *     <tr>
 *       <td>{@code dbHealthIndicator}</td>
 *       <td>{@code db}</td>
 *       <td>{@link #DB_HEALTH_COMPONENT_NAME}</td>
 *       <td>Pool acquisition and {@code SELECT 1}, on a bounded probe thread that replaces the
 *           framework's unbounded contributor on the same key</td>
 *     </tr>
 *     <tr>
 *       <td>{@code sqsHealthIndicator}</td>
 *       <td>{@code sqs}</td>
 *       <td>{@link #SQS_HEALTH_COMPONENT_NAME}</td>
 *       <td>The report queue, by {@code GetQueueUrl}; then its {@code FifoQueue} and
 *           {@code ContentBasedDeduplication} attributes, by {@code GetQueueAttributes}</td>
 *     </tr>
 *     <tr>
 *       <td>{@code snsHealthIndicator}</td>
 *       <td>{@code sns}</td>
 *       <td>{@link #SNS_HEALTH_COMPONENT_NAME}</td>
 *       <td>The operator notification topic, by a deadline-bounded read-only {@code ListTopics} walk
 *           matching the configured bare name against each listed identifier's last segment</td>
 *     </tr>
 *   </tbody>
 * </table>
 *
 * <p>The keys are deliberately lowercase and hyphen-free so a YAML {@code include} list can name
 * them without quoting. <strong>Renaming any of these beans silently drops that contributor from the readiness
 * group.</strong> That is because
 * {@code management.endpoint.health.validate-group-membership} rejects an unknown name at startup
 * but says nothing about a contributor that is merely no longer referenced. Rename in both places
 * or in neither. The constants above exist so a dependent cites a symbol rather than retyping a
 * literal.
 *
 * <h2>The liveness and readiness split, and its container binding</h2>
 *
 * <p>Group composition is <em>configuration</em> and lives in {@code application.yml}
 * ({@code management.endpoint.health.probes.enabled}, {@code group.liveness.include},
 * {@code group.readiness.include}). It is deliberately not attempted in Java, so that the
 * membership of a probe is readable in one place. This class only supplies correctly named beans
 * for those lists to reference.
 *
 * <p><strong>Liveness must never include an external dependency, and none of these beans may be
 * added to it.</strong> Liveness answers only "is this JVM still able to serve?". A liveness probe
 * that failed because the object-storage emulator was momentarily unreachable would cause an
 * orchestrator to kill an otherwise healthy process, converting a recoverable dependency blip into
 * a restart loop. Readiness is the correct place for a dependency: it removes the instance from
 * rotation and puts it back when the dependency returns.
 *
 * <p><strong>The container binding is load bearing.</strong> The {@code Dockerfile} declares
 * {@code HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=4} issuing
 * {@code GET /actuator/health/readiness} and grepping the body for {@code "status":"UP"}. The group
 * name {@code readiness}, and therefore that path, is load bearing: renaming the group on either
 * side breaks container startup ordering and with it the integration sign-off gate. The 5 second
 * container timeout is also what fixes the probe deadline documented on
 * {@link #sqsHealthIndicator()}.
 *
 * <h2>No live-AWS fallback, ever</h2>
 *
 * <p>All AWS interaction targets LocalStack, with zero live credentials anywhere in the repository.
 * This class upholds that structurally rather than by convention:
 *
 * <ul>
 *   <li>it <strong>constructs no client</strong> - all three SDK clients are injected, and their
 *       construction is owned by {@code com.cardemo.config.AwsConfig} together with the Spring
 *       Cloud AWS auto-configuration;</li>
 *   <li>it names <strong>no endpoint</strong>, calls no endpoint-override API and contains no host,
 *       port or service-domain literal;</li>
 *   <li>it supplies <strong>no</strong> region, access key, secret key or credentials provider, and
 *       no default for any of them;</li>
 *   <li>it performs <strong>no retry against a different endpoint</strong>. If the emulator is
 *       down, the correct and only behaviour is to report {@code DOWN}.</li>
 *   </ul>
 *
 * <p>The endpoint override is mandatory in <em>every</em> profile: {@code application.yml} declares
 * {@code spring.cloud.aws.{s3,sqs,sns}.endpoint} and both static credential keys with no default, so
 * an unset value fails startup, and {@code com.cardemo.config.AwsConfig} additionally refuses at
 * refresh time any endpoint whose host is not an emulator host. The compose stack reaches the
 * emulator on a LocalStack edge address of the form {@code http://localhost.localstack.cloud:4566}.
 * That address appears in this Javadoc as prose and nowhere in the code, and this class neither
 * reads nor validates it - the enforcement point is {@code AwsConfig}, deliberately, so that a
 * health probe cannot become a second place where endpoint policy is decided.
 *
 * <h2>Report, never throw; and no sensitive diagnostics</h2>
 *
 * <p>No indicator ever propagates an exception. Actuator would convert a thrown exception into
 * {@code DOWN} on its own, but the message it then surfaces is outside this class's control and can
 * carry an endpoint, a request id or a raw SDK payload. Each remote call is therefore wrapped, and
 * every failure is mapped to a status with a curated reason drawn from the closed vocabulary
 * {@link #REASON_NOT_CONFIGURED}, {@link #REASON_INVALID_NAME}, {@link #REASON_MISSING},
 * {@link #REASON_UNREACHABLE}, {@link #REASON_ATTRIBUTE_MISMATCH}, {@link #REASON_TIMEOUT},
 * {@link #REASON_INTERRUPTED} and {@link #REASON_ERROR}.
 *
 * <p><strong>The log is a curated channel too, not a diagnostic escape hatch.</strong> A raw SDK
 * throwable is never handed to the logger. Its {@code getMessage()} routinely carries the resolved
 * endpoint, the full queue URL with its twelve-digit account segment, a request id and a service
 * error payload, and its stack frames carry the same values inside frame arguments; a positional
 * account row inside a rendered stack trace cannot be redacted by a field-path masking rule, so the
 * only reliable control is not to emit it. Every {@code WARN} this class writes therefore consists
 * of a symbolic reason from the closed vocabulary, the failing bucket or logical queue name, and a
 * curated failure descriptor produced by {@link #describeFailure(Throwable)} - an exception
 * <em>class name</em> plus, when the service answered, its numeric HTTP status. A class name and a
 * status code are structurally incapable of carrying an endpoint, an account id, a credential or
 * customer data.
 *
 * <p><strong>This is reporting, not swallowing.</strong> Rule 1 Clause B forbids swallowing an
 * exception and requires the root cause to be preserved. It is preserved, as a classification rather
 * than as a rendering: the reason vocabulary separates every remedy an operator can act on
 * (provision the resource, start the emulator, fix the property, widen the deadline, correct the
 * attribute), and the descriptor names the exact SDK type and service status behind it. The tradeoff
 * is stated plainly because Rule 1 Clause A requires it: a rendered stack trace would localise a
 * fault inside the SDK marginally faster, and that is knowingly given up because the same rendering
 * is the one channel through which an account id provably escapes. Nothing is discarded silently -
 * the throwable is caught, classified and reported on both channels in the form each channel can
 * safely carry. Because {@link CorrelationIdFilter} has already populated the diagnostic context for
 * the actuator request, every such {@code WARN} is automatically correlated by
 * {@code correlationId}, {@code traceId} and {@code spanId} without this class touching
 * {@link org.slf4j.MDC} itself.
 *
 * <p>A health detail must <strong>never</strong> surface a JDBC connection string or URL, a
 * username or password, an access key or secret, a bucket ARN or a queue URL containing an AWS
 * account id, a presigned URL, a stack trace, a raw SDK error payload, or any personally
 * identifiable data. Two consequences are worth stating outright, because both are easy to
 * introduce by accident:
 *
 * <ul>
 *   <li>{@code Health.down(Throwable)} is <strong>not</strong> used anywhere in this class. It
 *       serialises the exception into the details map, which is precisely the leak this section
 *       forbids. The no-argument {@code Health.down()} builder is used instead.</li>
 *   <li>the SQS probe issues {@code GetQueueUrl} and <strong>discards the returned URL</strong>. A
 *       resolved queue URL embeds the AWS account id, so surfacing or logging it would disclose that
 *       identifier. Only the account-id-free logical name
 *       {@code carddemo-report-jobs} reaches a detail.</li>
 *   <li>the SNS probe walks {@code ListTopics} and <strong>discards every identifier it reads</strong>.
 *       A resolved topic identifier embeds the same account segment a queue URL does, so listed
 *       identifiers are reduced to their last segment purely for comparison and only the configured
 *       bare name {@code carddemo-notifications} reaches a detail. Pagination tokens are consumed and
 *       never published either.</li>
 *   </ul>
 *
 * <p>The details that <em>are</em> emitted are a symbolic component name, a symbolic reason, an
 * elapsed-milliseconds figure, a probed-bucket count, a listing-page count, a configuration
 * <em>property key</em> (never its value), S3 bucket, SQS queue or SNS topic <em>names</em>, and three
 * verification tokens that are all
 * compile-time literals - {@link #DETAIL_ATTRIBUTE}, {@link #DETAIL_VERSIONING} and
 * {@link #DETAIL_FIFO_CONTRACT}. A bucket, queue or topic name is a plain resource
 * identifier: AWS constrains it to a short unqualified token, so it can hold no account id, ARN,
 * credential or URL. That is nevertheless not taken on trust - see
 * {@link #REASON_INVALID_NAME}. The verification tokens carry no observed value at all: an attribute
 * that does not hold is reported by <em>naming the attribute</em>, never by echoing what the service
 * returned. Independently, {@code management.endpoint.health.show-details} is
 * {@code never} and {@code show-components} is {@code never}, so an anonymous caller receives a
 * status-only body; the curation above is the first line of defence and that setting the second.
 *
 * <h2>Deliberate non-responsibilities</h2>
 *
 * <p>Every item below is owned elsewhere, and duplicating it here would violate Rule 1 Clause C:
 *
 * <ul>
 *   <li><strong>a SECOND database probe.</strong> Spring Boot auto-configures a {@code db}
 *       contributor from the {@code DataSource}, and this class <em>replaces</em> it on the same key
 *       rather than joining it - {@code management.health.db.enabled: false} switches the framework's
 *       off, because the framework's has no deadline of its own and a measured readiness probe with the
 *       database stopped took 30.05 s. Replacement keeps Rule 1 Clause C's no-duplication requirement
 *       satisfied: there is exactly one {@code db} contributor, and its query is {@code SELECT 1}, so
 *       no business row is selected and no bind parameter is logged. Adding a second one under any
 *       other key would be the duplication this bullet forbids;</li>
 *   <li><strong>health-group composition and endpoint exposure</strong> - {@code application.yml};</li>
 *   <li><strong>AWS client construction</strong> - {@code com.cardemo.config.AwsConfig};</li>
 *   <li><strong>tracing configuration and the wiring of this package</strong> -
 *       {@code com.cardemo.config.ObservabilityConfig}, which is the declared wiring owner and the
 *       single declaration site of the application {@link java.time.Clock}; the three classes in this
 *       package register themselves and it re-declares none of them;</li>
 *   <li><strong>the business counters</strong> - {@link MetricsConfig};</li>
 *   <li><strong>correlation identity</strong> - {@link CorrelationIdFilter};</li>
 *   <li><strong>the Prometheus scrape configuration</strong> - {@code observability/prometheus.yml};</li>
 *   <li><strong>log encoding and masking</strong> - {@code logback-spring.xml}.</li>
 * </ul>
 *
 * <p>There is also no custom actuator endpoint here. Both beans implement the blocking
 * {@link HealthIndicator} contract, which is correct for this servlet stack; no {@code @Endpoint},
 * {@code @WebEndpoint}, scheduler, listener, interceptor or exception handler is declared.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>Values arrive exclusively through Spring property binding. There is no {@code System.getenv}
 * call, no absolute path, no host or port literal, and no locale-, charset- or timezone-sensitive
 * operation anywhere in this class:
 *
 * <ul>
 *   <li>{@code carddemo.aws.s3.batch-input-bucket}</li>
 *   <li>{@code carddemo.aws.s3.batch-output-bucket}</li>
 *   <li>{@code carddemo.aws.s3.statements-bucket}</li>
 *   <li>{@code carddemo.aws.sqs.report-queue}</li>
 *   <li>{@code carddemo.aws.sqs.report-queue-logical-name}</li>
 *   <li>{@code carddemo.aws.sns.notification-topic}</li>
 * </ul>
 *
 * <p>Each is bound with an <em>empty</em> placeholder default. That default is not a fallback for a
 * missing environment variable: {@code application.yml} declares all six keys, and all but the
 * logical queue name indirect to a variable with no default of their own, so an absent variable still fails
 * placeholder resolution at startup exactly as the base profile intends. The empty default covers
 * only the case where the property key is absent from the environment altogether - a pared-down or
 * unit-test context - and in that case the probe reports a deterministic {@code DOWN} carrying
 * {@link #REASON_NOT_CONFIGURED} and the offending property key rather than throwing a
 * {@link NullPointerException}.
 *
 * <p>Two binding details are easy to get wrong and are therefore stated explicitly:
 *
 * <ul>
 *   <li>The bucket property keys are {@code carddemo.aws.s3.batch-input-bucket} and
 *       {@code carddemo.aws.s3.batch-output-bucket}, indirecting to
 *       {@code CARDDEMO_S3_BATCH_INPUT_BUCKET} and {@code CARDDEMO_S3_BATCH_OUTPUT_BUCKET}. The shorter
 *       {@code CARDDEMO_S3_INPUT_BUCKET} and {@code CARDDEMO_S3_OUTPUT_BUCKET} spellings do not exist, and
 *       binding them would resolve to the empty default and pin readiness to a permanent
 *       {@link #REASON_NOT_CONFIGURED} {@code DOWN}.</li>
 *   <li>The readiness {@code HEALTHCHECK} is declared in the {@code Dockerfile}, not in
 *       {@code docker-compose.yml}, which defines no application service at all - only PostgreSQL,
 *       LocalStack, Jaeger, Prometheus and Grafana, the first two carrying healthchecks of their own.</li>
 *   </ul>
 *
 * <h2>Three constraints on how a probe may behave, and why each holds</h2>
 *
 * <ul>
 *   <li><strong>Every remote call is bounded and cancellable.</strong> The shared clients' timeouts are
 *       sized for batch uploads and are an order of magnitude longer than a readiness probe may take,
 *       so each contributor carries its own budget, draws a request-level deadline from what remains of
 *       it on every call, refuses to call at all once the budget is spent, and calls
 *       {@code cancel(true)} on every abandonment path. An uncaptured future would otherwise leave one
 *       orphaned request, connection and response buffer per readiness poll against a slow substrate.</li>
 *   <li><strong>A raw SDK throwable is never handed to the logger.</strong>
 *       {@link #describeFailure(Throwable)} reduces the cause to an exception class name plus, when the
 *       service answered, its numeric status. The fix has to be at source: an SDK message carries the
 *       resolved endpoint and the queue URL with its account segment, and a rendered stack carries the
 *       same values as frame arguments, where no field-path masking rule in {@code logback-spring.xml}
 *       can reach them.</li>
 *   <li><strong>Existence alone is not {@code UP}.</strong> Object versioning on the batch-output
 *       bucket, and FIFO plus content-based deduplication on the report queue, can neither be added
 *       after creation nor observed by an existence check, so a misprovisioned substrate would pass
 *       readiness and fail later as silently lost generations or reordered report jobs. One
 *       {@code GetBucketVersioning} and one two-attribute {@code GetQueueAttributes} therefore run
 *       inside the same budget, reported as {@link #REASON_ATTRIBUTE_MISMATCH}.</li>
 *   </ul>
 *
 * <h2>Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>Readiness {@code DOWN} with an S3 reason under the {@code local} profile.</strong>
 *       The LocalStack container is not up. Run {@code docker compose up -d localstack}, wait for
 *       it to report healthy, then re-check {@code /actuator/health/readiness}.</li>
 *   <li><strong>Readiness {@code DOWN} on SQS.</strong> {@code localstack-init/init-aws.sh} has not
 *       created {@code carddemo-report-jobs}. The script is idempotent, so re-running it is safe.
 *       Note that the physical queue name keeps the {@code .fifo} suffix AWS requires while the
 *       logical name does not.</li>
 *   <li><strong>{@link #REASON_MISSING} on S3 with the emulator plainly up.</strong> The bucket
 *       named by the reported property key does not exist. Either the initialisation script has not
 *       run or a bucket variable has been renamed on one side only.</li>
 *   <li><strong>{@link #REASON_NOT_CONFIGURED}.</strong> The reported property key resolved to an
 *       empty value. Supply the corresponding variable; the published contract is in
 *       {@code .env.example}.</li>
 *   <li><strong>{@link #REASON_TIMEOUT} on either component.</strong> The emulator accepted the
 *       connection but did not answer within the contributor's remaining budget. Check emulator load
 *       and host contention. Because the budget is shared across a contributor's calls, a timeout
 *       reported against the last call can mean the earlier ones were merely slow rather than that
 *       this particular resource is at fault - {@link #DETAIL_ELAPSED_MILLIS} distinguishes the two,
 *       since a value at the budget means the whole contributor ran out rather than one call
 *       stalling.</li>
 *   <li><strong>{@link #REASON_ATTRIBUTE_MISMATCH} with {@link #DETAIL_ATTRIBUTE}
 *       {@code Versioning}.</strong> The batch-output bucket exists without object versioning, so
 *       generation keys would overwrite rather than version. Re-run
 *       {@code localstack-init/init-aws.sh}, which enables versioning idempotently; a bucket created
 *       by hand or by an older revision of that script will not have it.</li>
 *   <li><strong>{@link #REASON_ATTRIBUTE_MISMATCH} with {@link #DETAIL_ATTRIBUTE}
 *       {@code FifoQueue} or {@code ContentBasedDeduplication}.</strong> A standard queue exists under
 *       the configured name, or a FIFO queue still carries content-based deduplication. Re-run
 *       {@code localstack-init/init-aws.sh}, which converges the mutable deduplication attribute; a
 *       queue that is not FIFO at all cannot be converted and must be deleted and recreated. A queue created without the {@code .fifo} suffix is the
 *       usual cause, because SQS rejects FIFO attributes on a name that lacks it.</li>
 *   <li><strong>{@code /actuator/health/readiness} returns 404.</strong> The readiness group was
 *       removed or renamed, or {@code probes.enabled} was turned off. See the container binding
 *       above.</li>
 *   <li><strong>The context fails to start naming an unknown health contributor.</strong> The
 *       readiness group lists {@code s3} or {@code sqs} while this class is absent from the
 *       component scan. Beans and group membership must land in the same change.</li>
 *   </ul>
 *
 * <h2>How to build and test</h2>
 *
 * <p>{@code ./mvnw -B -ntp clean compile} compiles this class under Java 25 with
 * {@code -Xlint:all -Werror}. {@code ./mvnw -B -ntp test} runs the unit suite. Both probes are unit
 * tested without a network or a container in
 * {@code src/test/java/com/cardemo/unit/observability/HealthIndicatorsTest.java}, which constructs
 * this class over Mockito doubles and asserts each of the four properties this class claims:
 *
 * <ul>
 *   <li>every request carries a request-level call and attempt deadline, and an abandoned
 *       asynchronous call is left {@code isCancelled()};</li>
 *   <li>no detail value and no {@code WARN} message carries an endpoint, a resolved queue URL, an
 *       account id, an access key or an exception message, and the logged event's throwable proxy is
 *       {@code null} - which is what proves no stack is rendered;</li>
 *   <li>{@code UP} requires versioning on the batch-output bucket and both FIFO attributes on the
 *       queue, and a mismatch names the attribute without echoing the observed value;</li>
 *   <li>an unconfigured or ARN-shaped name is refused before any call is issued.</li>
 * </ul>
 *
 * <p>Container-backed variants belong under {@code src/test/java/com/cardemo/integration/aws/**} and
 * do not belong in this file.
 *
 * <h2>Preserved legacy artefacts, cited and never repaired</h2>
 *
 * <ul>
 *   <li>{@code app/jcl/OPENFIL.jcl:L1} reads
 *       {@code //OEPNFIL JOB 'Open files in CICS'} - the job name transposes the E and the P while
 *       the member is {@code OPENFIL.jcl}. {@code app/jcl/CLOSEFIL.jcl:L1} is correct. The
 *       {@code app/} tree is frozen byte-for-byte and is simultaneously the parity oracle, the
 *       field-contract source and the commit-keyed traceability anchor, so the typo is recorded
 *       here and never corrected there.</li>
 *   <li>{@code com.cardemo.service.shared.FileStatusMapper} emits the literal
 *       {@code FILE STATUS IS: NNNN} immediately followed by a four-character status, from
 *       {@code app/cbl/CBTRN02C.cbl:L721} and {@code :L725}. COBOL {@code DISPLAY} concatenates a
 *       literal and an identifier with no separator and the literal already contains the
 *       placeholder text, so status {@code '23'} emits {@code FILE STATUS IS: NNNN0023}. The stray
 *       {@code NNNN} is a preserved legacy quirk. Nothing in this class reformats, wraps, escapes,
 *       re-renders, truncates or masks that literal; this class emits no file status at all.</li>
 *   </ul>
 *
 * <p>The opposite posture is worth naming once, for the reader who wonders why a probe reports
 * rather than aborts. The legacy batch response to an unexpected file status is termination, not
 * degradation: {@code app/cbl/CBTRN02C.cbl:L708} displays {@code 'ABENDING PROGRAM'},
 * {@code :L710} moves {@code 999} into the abend code and {@code :L711} calls {@code 'CEE3ABD'},
 * yielding abend code 999 with process return code 12. A health probe is deliberately the
 * inverse - it degrades a signal so an orchestrator can act, and terminates nothing.
 *
 * <h2>What a probe can and cannot demonstrate</h2>
 *
 * <p>Both probes are provable without infrastructure: they compile under the zero-warning gate and are
 * asserted against mocked clients. A live {@code UP} reading from {@code /actuator/health/readiness} with
 * all three components present is a different claim, and it needs a container runtime with an accessible
 * socket, the compose stack up and the migrations applied - so it belongs to the integration sign-off gate
 * rather than to this class.
 *
 * <p>No service-level objective exists anywhere in the COBOL corpus, so no latency threshold is
 * asserted for a probe. The elapsed-milliseconds detail is a measurement, not a budget.
 *
 * <h2>Thread safety</h2>
 *
 * <p>This class and both nested indicators are immutable after construction and hold no static
 * mutable state: every static member is {@code final} and references an immutable value. The
 * injected SDK clients are thread-safe by contract. Health probes may therefore run concurrently on
 * arbitrary request threads. No result is cached, so a probe always reports the substrate's current
 * state rather than a stale snapshot.
 *
 * @see MetricsConfig
 * @see CorrelationIdFilter
 * @see HealthIndicator
 */
@Configuration
public class HealthIndicators {

    // Published name registry.
    //
    // These are public because they are a cross-file contract rather than an implementation detail:
    // application.yml names the two component keys in its readiness group, and a test asserting the
    // health contract should cite a symbol instead of retyping a literal that must not drift. Every
    // constant is a final reference to an immutable String, so the class still holds no static
    // mutable state.

    /**
     * Bean name of the S3 indicator, and therefore the source of the {@code s3} health component
     * key. Actuator strips the {@code HealthIndicator} suffix to derive the key.
     */
    public static final String S3_HEALTH_INDICATOR_BEAN_NAME = "s3HealthIndicator";

    /**
     * Health component key of the S3 indicator, as it appears in a health response and in
     * {@code management.endpoint.health.group.readiness.include}. Lowercase and hyphen-free so a
     * YAML {@code include} list needs no quoting.
     */
    public static final String S3_HEALTH_COMPONENT_NAME = "s3";

    /**
     * Bean name of the relational-store indicator, and therefore the source of the {@code db} health
     * component key. Actuator strips the {@code HealthIndicator} suffix to derive the key, so this
     * spelling is what keeps {@code management.endpoint.health.group.readiness.include}'s {@code db}
     * entry resolving after {@code management.health.db.enabled: false} switched the framework's own
     * unbounded contributor off.
     */
    public static final String DB_HEALTH_INDICATOR_BEAN_NAME = "dbHealthIndicator";

    /**
     * Health component key of the relational-store indicator, as it appears in a health response and
     * in {@code management.endpoint.health.group.readiness.include}. Deliberately the same key the
     * framework contributor used, so the readiness group, the container health check and every
     * published gate reading keep the spelling they already had.
     */
    public static final String DB_HEALTH_COMPONENT_NAME = "db";

    /**
     * Bean name of the SQS indicator, and therefore the source of the {@code sqs} health component
     * key. Actuator strips the {@code HealthIndicator} suffix to derive the key.
     */
    public static final String SQS_HEALTH_INDICATOR_BEAN_NAME = "sqsHealthIndicator";

    /**
     * Health component key of the SQS indicator, as it appears in a health response and in
     * {@code management.endpoint.health.group.readiness.include}.
     */
    public static final String SQS_HEALTH_COMPONENT_NAME = "sqs";

    /**
     * Bean name of the SNS indicator, and therefore the source of the {@code sns} health component
     * key. Actuator strips the {@code HealthIndicator} suffix to derive the key.
     */
    public static final String SNS_HEALTH_INDICATOR_BEAN_NAME = "snsHealthIndicator";

    /**
     * Health component key of the SNS indicator, as it appears in a health response and in
     * {@code management.endpoint.health.group.readiness.include}.
     */
    public static final String SNS_HEALTH_COMPONENT_NAME = "sns";

    // Detail keys. The complete, closed set of keys any indicator can place in a health body.

    /** Detail key carrying the symbolic component name, {@code s3}, {@code db}, {@code sqs} or {@code sns}. */
    public static final String DETAIL_COMPONENT = "component";

    /**
     * Detail key carrying the number of S3 buckets the probe actually attempted. On success this
     * equals the number configured; on failure it is the count reached before the first failure,
     * which localises the fault without naming anything sensitive.
     */
    public static final String DETAIL_BUCKETS_PROBED = "bucketsProbed";

    /** Detail key carrying the name of the single S3 bucket whose probe failed. */
    public static final String DETAIL_BUCKET = "bucket";

    /**
     * Detail key carrying the account-id-free logical name of the report queue, for example
     * {@code carddemo-report-jobs}. Never a resolved queue URL, which would embed an account id.
     */
    public static final String DETAIL_QUEUE = "queue";

    /**
     * Detail key carrying the bare, account-id-free name of the operator notification topic, for
     * example {@code carddemo-notifications}.
     *
     * <p><strong>Never a topic ARN.</strong> A resolved topic identifier embeds the twelve-digit AWS
     * account segment, exactly as a queue URL does, so the probe resolves ARNs in-process to compare
     * them and publishes only the configured bare name - the same discipline
     * {@link #DETAIL_QUEUE} applies to the queue.
     */
    public static final String DETAIL_TOPIC = "topic";

    /**
     * Detail key carrying how many pages of the provisioned-topic listing the probe examined before
     * it reached a verdict.
     *
     * <p>Published on both outcomes because it is the only way to tell "the topic is not provisioned"
     * from "the listing was longer than the probe was allowed to walk", and those have different
     * remedies: re-run the provisioning script, versus reduce the number of topics in the account or
     * widen the budget. It is a count, never a page token, so it can carry no identifier.
     */
    public static final String DETAIL_TOPIC_PAGES_EXAMINED = "topicPagesExamined";

    /**
     * Detail key carrying a symbolic failure reason from the closed vocabulary of
     * {@link #REASON_NOT_CONFIGURED}, {@link #REASON_INVALID_NAME}, {@link #REASON_MISSING},
     * {@link #REASON_UNREACHABLE}, {@link #REASON_ATTRIBUTE_MISMATCH}, {@link #REASON_TIMEOUT},
     * {@link #REASON_INTERRUPTED} and {@link #REASON_ERROR}. Present only on a failure.
     */
    public static final String DETAIL_REASON = "reason";

    /**
     * Detail key naming the single resource attribute whose verified value did not satisfy the
     * contract the substrate must uphold, accompanying {@link #REASON_ATTRIBUTE_MISMATCH}.
     *
     * <p>The published value is always one of three compile-time literals - {@code Versioning},
     * {@code FifoQueue} or {@code ContentBasedDeduplication} - so this detail names <em>which</em>
     * guarantee is absent without ever carrying the observed value, the resource ARN or the resolved
     * URL that was inspected to discover it.
     */
    public static final String DETAIL_ATTRIBUTE = "attribute";

    /**
     * Detail key carrying the verified versioning state of the batch-output bucket, present only on
     * {@code UP}. Its value is the literal {@code Enabled}, because any other state is reported as
     * {@link #REASON_ATTRIBUTE_MISMATCH} instead of {@code UP}.
     *
     * <p>Published because the guarantee it records is load-bearing rather than cosmetic: object
     * versioning is what replaces generation-data-group generations, so an operator reading a green
     * readiness body needs to see that the replacement is actually in force and not merely that the
     * bucket answered.
     */
    public static final String DETAIL_VERSIONING = "versioning";

    /**
     * Detail key carrying the outcome of the report queue's FIFO-contract verification, present only
     * on {@code UP}. Its value is the literal {@code verified}, because a queue that is not a FIFO
     * queue is reported as {@link #REASON_ATTRIBUTE_MISMATCH} instead of {@code UP}. The deduplication
     * attribute no longer contributes to that verdict - finding H-08 - and is published on its own key,
     * {@link #DETAIL_CONTENT_DEDUPLICATION}.
     *
     * <p>A single symbolic token rather than an observed attribute value, so that this detail cannot
     * drift into republishing service output.
     */
    public static final String DETAIL_FIFO_CONTRACT = "fifoContract";

    /**
     * Detail key carrying the queue's live {@code ContentBasedDeduplication} value.
     *
     * <p>Finding H-08, severity High. The attribute is expected to be disabled, but it does not gate
     * readiness: every submission carries its own {@code MessageDeduplicationId} and an explicit
     * identifier takes precedence over the body hash, so an enabled hash impairs nothing. It is
     * published instead of asserted, because it is mutable by any holder of the queue and a probe that
     * merely refused would say less than one that reports what it saw.
     *
     * <p>Publishing the observed boolean is safe in a way that republishing service output generally is
     * not: the value is one of two service-rendered literals, it is fixed vocabulary rather than
     * caller-influenced text, and it names no account, address or credential.
     */
    public static final String DETAIL_CONTENT_DEDUPLICATION = "contentDeduplication";

    /**
     * Detail key carrying the configuration <em>property key</em> that is misconfigured - never its
     * value. A property key is a compile-time-fixed identifier such as
     * {@code carddemo.aws.sqs.report-queue} and can hold no secret, which is what makes it safe to
     * publish and useful for troubleshooting.
     */
    public static final String DETAIL_PROPERTY = "property";

    /**
     * Detail key carrying the wall-clock duration of the probe in milliseconds, measured with a
     * monotonic clock. A measurement, not a budget: no service-level objective exists in the source
     * system to compare it against.
     */
    public static final String DETAIL_ELAPSED_MILLIS = "elapsedMillis";

    // Closed reason vocabulary. Public because these values are observable output, so a test should
    // assert against a symbol. Every value is a short symbolic token that can carry no diagnostic
    // payload by construction.

    /**
     * The configured bucket or queue name resolved to an empty value, so there is nothing to probe.
     * Reported with {@link #DETAIL_PROPERTY} naming the key at fault. Deterministic by design: an
     * absent or blank name is a configuration failure, never a
     * {@link NullPointerException} and never a silently skipped probe.
     */
    public static final String REASON_NOT_CONFIGURED = "not-configured";

    /**
     * The configured name contained a character that a legitimate S3 bucket name or SQS queue name
     * cannot contain - a colon, a solidus or a commercial at - which means the value is an ARN, a
     * URL or otherwise not a bare resource name. The probe is refused and <strong>the value is not
     * echoed</strong>, because an ARN or URL can embed an AWS account id. Treating a configured
     * value as untrusted is Rule 1 Clause A's "security by default" applied to configuration.
     */
    public static final String REASON_INVALID_NAME = "invalid-name";

    /**
     * The service answered but the bucket or queue does not exist. Distinguished from
     * {@link #REASON_UNREACHABLE} because the remedy differs: this one means provisioning has not
     * run, that one means the endpoint is unavailable.
     */
    public static final String REASON_MISSING = "missing";

    /**
     * The SDK could not complete the call - connection refused, DNS failure, a transport error or a
     * service-side error. Never accompanied by the SDK's own message, which can carry an endpoint
     * or a request payload; the message reaches the {@code WARN} log instead.
     */
    public static final String REASON_UNREACHABLE = "unreachable";

    /**
     * The resource exists and answered, but an attribute the substrate contract requires does not
     * hold: the batch-output bucket does not have versioning enabled, or the report queue is not a
     * FIFO queue, or its deduplication attribute contradicts what the send path requires.
     *
     * <p>Distinguished from {@link #REASON_MISSING} because existence and correctness are different
     * facts with different remedies. A bucket without versioning silently loses the generation
     * semantics that {@code app/jcl/DEFGDGB.jcl} relies on - a second write to a generation key
     * overwrites the first instead of creating a version - and a non-FIFO queue silently loses the
     * ordering the {@code JOBS} transient-data-queue bridge depends on. Both faults are invisible to
     * an existence check and would surface only as corrupted batch output, so readiness reports
     * {@code DOWN} rather than admitting traffic to a substrate that answers but cannot honour the
     * contract. {@link #DETAIL_ATTRIBUTE} names which attribute failed.
     */
    public static final String REASON_ATTRIBUTE_MISMATCH = "attribute-mismatch";

    /**
     * The probe did not complete within its budget - either a call exceeded its per-request deadline
     * or the remaining budget was already exhausted before the call could be issued. In both cases
     * any work still in flight is cancelled rather than left running, so a timed-out probe consumes
     * no further capacity after it has reported.
     */
    public static final String REASON_TIMEOUT = "timeout";

    /**
     * The probing thread was interrupted while awaiting the asynchronous result. The interrupt flag
     * is restored before returning, so the interruption is never lost.
     */
    public static final String REASON_INTERRUPTED = "interrupted";

    /**
     * An unexpected runtime failure that is none of the above. Present so that "never throw out of
     * an indicator" holds unconditionally rather than only for the failures anticipated here.
     */
    public static final String REASON_ERROR = "error";

    // Property keys, held as constants so the value bound and the key reported on a misconfiguration
    // can never drift apart.

    /** Property key supplying the batch input bucket, which replaces the DALYTRAN staging dataset. */
    private static final String PROPERTY_BATCH_INPUT_BUCKET = "carddemo.aws.s3.batch-input-bucket";

    /**
     * Property key supplying the batch output bucket. This is the versioned bucket over which
     * generation-data-group references become deterministic key prefixes.
     */
    private static final String PROPERTY_BATCH_OUTPUT_BUCKET = "carddemo.aws.s3.batch-output-bucket";

    /** Property key supplying the statements bucket, which holds the 80- and 100-byte outputs. */
    private static final String PROPERTY_STATEMENTS_BUCKET = "carddemo.aws.s3.statements-bucket";

    /**
     * Property key supplying the physical report-queue name. The physical name keeps the
     * {@code .fifo} suffix AWS requires for a FIFO queue; only this value is sent to the service.
     */
    private static final String PROPERTY_REPORT_QUEUE = "carddemo.aws.sqs.report-queue";

    /**
     * Property key supplying the logical report-queue name. This is the value published in a health
     * detail, because it is a literal in {@code application.yml} rather than an environment-derived
     * one and is therefore provably free of any account identifier.
     */
    private static final String PROPERTY_REPORT_QUEUE_LOGICAL_NAME =
            "carddemo.aws.sqs.report-queue-logical-name";

    /**
     * Property key supplying the operator notification topic. The value is a bare topic name, never an
     * ARN, and it is the value published in a health detail for exactly that reason.
     */
    private static final String PROPERTY_NOTIFICATION_TOPIC = "carddemo.aws.sns.notification-topic";

    // Probe budgets. Every remote call either completes inside the budget of the contributor that
    // issued it or is abandoned; no call is unbounded, and no call can borrow time from another
    // contributor. The four figures below are derived from container evidence, not chosen.
    //
    // The Dockerfile's single HEALTHCHECK instruction declares `--timeout=7s` on the readiness probe.
    // Cited by instruction rather than by line number, because the line moves. That 7 second budget must
    // cover, in one HTTP round trip: this class's four contributors, Actuator's own aggregation and the
    // round trip itself. Actuator evaluates the members of a health group SEQUENTIALLY, so the
    // contributors' budgets ADD - which is what makes the arithmetic below load bearing rather than
    // decorative. Four contributors at 1.5 s cap the aggregate worst case at 6 s and leave 1 s for
    // aggregation and transport, which is the split that keeps a slow-but-alive substrate reporting
    // DOWN inside the container deadline instead of being killed as unresponsive.
    //
    // The container deadline was 5 s while this class contributed three probes, and was raised to 7 s
    // in the same change that added the fourth. It is recorded rather than adjusted silently because
    // the alternative was considered and rejected: shrinking all four budgets to 1 s would have kept
    // the aggregate at 4 s under a 5 s deadline, but 1 s leaves the relational probe no room for pool
    // acquisition plus its validation query on a memory-pressured host, and a probe that reports DOWN
    // because it was rushed is a false negative that removes a healthy instance from rotation. Raising
    // the deadline costs at most two additional seconds before a genuinely wedged instance is failed,
    // and `--retries=4` at `--interval=15s` means detection latency is dominated by the retry budget
    // rather than by the per-attempt timeout, so the cost is close to nil.
    //
    // A budget is a *total* per contributor, not a per-call allowance: each call is issued with the
    // budget that remains at the moment it is issued, so adding a verification call can never extend
    // the contributor's worst case. This is what makes the M-08 attribute checks free in wall-clock
    // terms - they consume slack, never additional budget.

    /**
     * Total budget for one invocation of the object-storage contributor, in milliseconds, shared
     * across its three {@code HeadBucket} calls and its one {@code GetBucketVersioning} call.
     */
    private static final long S3_PROBE_BUDGET_MILLIS = 1_500L;

    /**
     * Total budget for one invocation of the queue contributor, in milliseconds, shared across its
     * {@code GetQueueUrl} call and its {@code GetQueueAttributes} call.
     */
    private static final long SQS_PROBE_BUDGET_MILLIS = 1_500L;

    /**
     * Total budget for one invocation of the notification contributor, in milliseconds, shared across
     * every page of the {@code ListTopics} walk it performs.
     *
     * <p>Identical to the other three so that no contributor is privileged, and a total rather than a
     * per-page allowance: each page request is issued with whatever remains at that moment, so a
     * long topic listing consumes slack and can never extend this contributor's worst case. Exhausting
     * the budget mid-walk reports {@link #REASON_TIMEOUT}, which is deliberately distinguishable from
     * {@link #REASON_MISSING} - the first means the listing outran the probe, the second means the
     * topic is genuinely not provisioned, and the remedies differ.
     */
    private static final long SNS_PROBE_BUDGET_MILLIS = 1_500L;

    /**
     * Total budget for one invocation of the relational-store contributor, in milliseconds, covering
     * pool acquisition <em>and</em> the validation query together.
     *
     * <p><strong>Finding, severity Minor - remediated by this constant existing at all.</strong> The
     * framework's {@code DataSourceHealthIndicator} has no deadline of its own: it asks the pool for a
     * connection and therefore inherits {@code spring.datasource.hikari.connection-timeout}, which is
     * 30 000 ms. With the database stopped, {@code /actuator/health/readiness} measured 30.05 s and
     * the application logged {@code Health contributor ... DataSourceHealthIndicator (db) took
     * 30009ms} - twenty times the budget this class already applied to its own AWS contributors, and
     * four times the {@code --timeout=7s} the container health check declares. Under any orchestrator
     * that treats a readiness timeout as a failure, a brief database blip therefore became a restart
     * loop. Lowering the pool's {@code connection-timeout} was rejected as the remedy: that value
     * governs the data path for every request, its untuned state is a recorded decision, and pool
     * tuning is explicitly out of scope for this migration.
     *
     * <p>1 500 ms so that every contributor shares one deadline rather than one being privileged:
     * four contributors at 1.5 s cap the aggregate worst case at 6 s and leave 1 s of the container's
     * 7 s for Actuator's aggregation and the round trip. The container deadline was 5 s while this class
     * contributed three probes and was raised to 7 s in the change that added the fourth; the reasoning,
     * including the shrink-the-budgets alternative that was rejected, is recorded on the budget note
     * above this constant.
     */
    private static final long DB_PROBE_BUDGET_MILLIS = 1_500L;

    /**
     * The validation query, and the reason it is a literal rather than a configured value.
     *
     * <p>{@code SELECT 1} is answerable by any PostgreSQL connection with no privilege on any table,
     * so a probe cannot fail because a grant changed, cannot read a row and cannot be affected by
     * row-level security. The application binds a least-privilege role, and a probe that needed a
     * table grant would report the substrate down when only an authorisation had moved.
     */
    private static final String DB_VALIDATION_QUERY = "SELECT 1";

    /**
     * Name of the single daemon thread the relational-store probe runs on.
     *
     * <p>Named rather than default because it appears in a thread dump, and an operator reading one
     * during a database stall needs to see which subsystem is blocked.
     */
    private static final String DB_PROBE_THREAD_NAME = "carddemo-db-health-probe";

    /**
     * Seconds to wait for the probe thread to finish during shutdown before abandoning it.
     *
     * <p>Short on purpose: the thread is a daemon, so it can never hold the JVM open, and shutdown
     * must not be delayed by a probe that is already stalled on a substrate that has gone away.
     */
    private static final long DB_PROBE_SHUTDOWN_GRACE_SECONDS = 2L;

    /**
     * Upper bound on a single network attempt, in milliseconds, applied as the SDK's
     * {@code apiCallAttemptTimeout} alongside a whole-call {@code apiCallTimeout} taken from the
     * remaining budget.
     *
     * <p>Two deadlines rather than one because they fail differently: the attempt deadline aborts a
     * connection that has stalled mid-attempt and allows the SDK's retry policy one further try
     * inside the remaining budget, while the call deadline caps the total including retries. A single
     * deadline would either forbid retrying or allow retries to consume the contributor's whole
     * budget. The emulator is in-network, so an attempt that has not answered in 500 ms is not going
     * to.
     */
    private static final long PROBE_ATTEMPT_TIMEOUT_MILLIS = 500L;

    /**
     * Smallest remaining budget worth issuing a call with, in milliseconds. Below this the probe
     * reports {@link #REASON_TIMEOUT} without issuing the call.
     *
     * <p>Required rather than defensive: the SDK rejects a non-positive
     * {@code apiCallTimeout}, so a call must never be issued once the budget has run out, and a
     * deadline of a few milliseconds could only ever expire in flight - paying for a connection to
     * learn nothing.
     */
    private static final long MINIMUM_CALL_BUDGET_MILLIS = 50L;

    /**
     * Maximum number of {@code ListTopics} pages the notification probe walks before it reports the
     * topic absent, {@value}.
     *
     * <p><strong>This value must equal the page ceiling in
     * {@code com.cardemo.config.AwsConfig.ProvisionedTopicArnResolver}, and a unit test asserts that it
     * does.</strong> The two walks answer the same question - "is this bare topic name among the
     * provisioned topics?" - and a readiness probe that searched fewer pages than the publish path
     * would report {@code DOWN} for a topic the publish path resolves perfectly well, taking a healthy
     * instance out of rotation on the strength of its own shorter search. Tying the ceilings is what
     * makes the probe's verdict and the send path's verdict the same verdict.
     *
     * <p>The value is declared here rather than read from that class because a health probe importing
     * configuration internals would invert the dependency the package layering establishes, and
     * reflecting into a private constant from production code would be worse than either. The tie is
     * therefore asserted by a test, which is how this repository already binds literals that must agree
     * across files.
     *
     * <p>In practice the ceiling is never approached: this application provisions exactly one topic, so
     * the first page settles it. {@link #SNS_PROBE_BUDGET_MILLIS} is the bound that actually applies on
     * a slow substrate, and it reports {@link #REASON_TIMEOUT} rather than {@link #REASON_MISSING} when
     * it is what stopped the walk.
     */
    private static final int TOPIC_PAGE_LIMIT_COUNT = 50;

    /**
     * Separator between the segments of a resolved topic identifier. The bare topic name is everything
     * after the last one, which is how the probe compares a configured name against a listing entry
     * without ever publishing or logging the identifier that carries the account segment.
     */
    private static final char TOPIC_ARN_SEPARATOR = ':';

    /**
     * The two queue attributes this probe reads, in the order they are examined. Immutable, so this
     * class still holds no static mutable state.
     *
     * <p>Both are read, but they are not treated alike, and only the first gates the verdict.
     * {@code FifoQueue} is what makes message-group ordering exist at all and cannot be changed after
     * the queue is created, so requiring it is both necessary and stable - see
     * {@link #REQUIRED_QUEUE_ATTRIBUTE_VALUES}. {@code ContentBasedDeduplication} governs whether the
     * queue collapses two messages carrying the same body; it is read so that its live value can be
     * published as {@link #DETAIL_CONTENT_DEDUPLICATION}, because it is mutable and drift in it is
     * worth seeing, but it does not fail readiness. Both are provisioned by
     * {@code localstack-init/init-aws.sh}.
     */
    private static final List<QueueAttributeName> REQUESTED_QUEUE_ATTRIBUTES =
            List.of(QueueAttributeName.FIFO_QUEUE, QueueAttributeName.CONTENT_BASED_DEDUPLICATION);

    /**
     * The value each <em>gating</em> queue attribute must hold for readiness.
     *
     * <p>Finding H-08, severity High. Requiring {@code ContentBasedDeduplication} to
     * be {@code true} for readiness, on the reading that it is what lets the publisher send without computing
     * a deduplication identifier, has it backwards: the body of a report message is the report name
     * and two dates, so content-based deduplication collapses two legitimate submissions of the same
     * period, while {@code DEFINE TDQUEUE(JOBS) ... DISPOSITION(MOD)} in {@code app/csd/CARDDEMO.CSD}
     * appends every write.
     *
     * <p>Inverting the requirement and refusing readiness unless the
     * attribute is {@code false} is not right either, and the reason is what readiness is
     * <em>for</em>: it answers whether traffic should be routed here. The publisher supplies an
     * explicit {@code MessageDeduplicationId} on every submission, and an explicit identifier takes
     * precedence over the body hash - confirmed against the emulator, where two identical bodies sent
     * with distinct identifiers onto a queue reporting {@code true} both arrived. Submission is
     * therefore correct whatever this attribute says, so failing readiness over it would withdraw a
     * healthy instance from service for a condition that impairs nothing.
     *
     * <p>Only {@code FifoQueue} gates readiness, and it is the one that should: message-group ordering
     * does not exist without it, and it is fixed when the queue is created so the verdict cannot go
     * stale. {@code ContentBasedDeduplication} is still read and still published, as
     * {@link #DETAIL_CONTENT_DEDUPLICATION}, so live drift stays visible on every probe without being
     * mistaken for an outage.
     */
    private static final Map<QueueAttributeName, String> REQUIRED_QUEUE_ATTRIBUTE_VALUES = Map.of(
            QueueAttributeName.FIFO_QUEUE, "true");

    /**
     * Symbolic label published as {@link #DETAIL_ATTRIBUTE} when the batch-output bucket's versioning
     * state is not {@code Enabled}. A compile-time literal naming the S3 sub-resource, chosen so the
     * detail never carries the observed status value.
     */
    private static final String ATTRIBUTE_LABEL_VERSIONING = "Versioning";

    /** Value published as {@link #DETAIL_FIFO_CONTRACT} once every gating attribute has verified. */
    private static final String FIFO_CONTRACT_VERIFIED = "verified";

    /**
     * Descriptor published in place of an exception class name when a failure is reported with no
     * throwable behind it - budget exhaustion detected before a call was issued.
     */
    private static final String FAILURE_DESCRIPTOR_NONE = "none";

    /**
     * Characters that a legitimate S3 bucket name or SQS queue name can never contain, and whose
     * presence therefore means the configured value is an ARN, a URL or a credential-bearing string
     * rather than a bare resource name. Used by {@link #isUnsafeResourceName(String)}.
     */
    private static final String UNSAFE_NAME_CHARACTERS = ":/@";

    /** Ordered map of property key to configured bucket name, unmodifiable and never null-valued. */
    private final Map<String, String> s3Buckets;

    /** The physical report-queue name sent to the service. Never blank once validated. */
    private final String reportQueueName;

    /** The account-id-free logical report-queue name published in a health detail. */
    private final String reportQueueLogicalName;

    /** The injected, thread-safe synchronous S3 client. Never constructed by this class. */
    private final S3Client s3Client;

    /** The injected, thread-safe asynchronous SQS client. Never constructed by this class. */
    private final SqsAsyncClient sqsAsyncClient;

    /**
     * The injected application {@link DataSource}. Never constructed here, and deliberately the same
     * pool the request path uses: a probe against a second pool would report on connectivity the
     * application does not actually rely on.
     */
    private final DataSource dataSource;

    /**
     * The injected synchronous notification client. Never constructed here, for the same reason as the
     * other two: construction is owned by {@code com.cardemo.config.AwsConfig}, which is the single
     * place an endpoint or a credential may be named.
     *
     * <p>Synchronous rather than asynchronous because that is the client the publish path itself uses -
     * {@code AwsConfig} builds its {@code SnsTemplate} and its {@code TopicArnResolver} over an
     * {@code SnsClient} - so the probe exercises the same client, the same interceptors and the same
     * endpoint override that a real notification would.
     */
    private final SnsClient snsClient;

    /**
     * The bare notification topic name, normalised. Bare rather than an ARN, which is what makes it
     * publishable in a health detail.
     */
    private final String notificationTopic;

    /**
     * Creates the indicator factory from injected collaborators and bound configuration.
     *
     * <p>Constructor injection only: this class declares no field or setter injection, no service
     * locator and no static holder. Both SDK clients are <em>injected</em>, never built here, which
     * is what structurally prevents this class from naming an endpoint or a credential.
     *
     * <p>Every bound name is normalised once, here, by trimming surrounding whitespace and mapping
     * {@code null} to the empty string, so each probe sees a non-null value and the
     * {@link #REASON_NOT_CONFIGURED} decision is made against a single canonical form. The three
     * bucket entries are held in insertion order so that probe order, and therefore the reported
     * {@link #DETAIL_BUCKETS_PROBED} count, is deterministic.
     *
     * <p>Side effects: none. No remote call, no log statement and no mutation of shared state
     * occurs during construction.
     *
     * @param s3Client               the auto-configured synchronous S3 client; must not be
     *                               {@code null}
     * @param sqsAsyncClient         the auto-configured asynchronous SQS client; must not be
     *                               {@code null}
     * @param snsClient              the auto-configured synchronous SNS client, the same one the
     *                               publish path resolves topics through; must not be {@code null}
     * @param dataSource             the auto-configured application datasource, whose pool the
     *                               relational-store probe borrows a connection from; must not be
     *                               {@code null}
     * @param batchInputBucket       value of {@code carddemo.aws.s3.batch-input-bucket}; empty when
     *                               the key is absent from the environment
     * @param batchOutputBucket      value of {@code carddemo.aws.s3.batch-output-bucket}; empty
     *                               when the key is absent from the environment
     * @param statementsBucket       value of {@code carddemo.aws.s3.statements-bucket}; empty when
     *                               the key is absent from the environment
     * @param reportQueue            value of {@code carddemo.aws.sqs.report-queue}, the physical
     *                               FIFO queue name; empty when the key is absent
     * @param reportQueueLogicalName value of {@code carddemo.aws.sqs.report-queue-logical-name},
     *                               the account-id-free name published in a health detail; empty
     *                               when the key is absent
     * @param notificationTopic      value of {@code carddemo.aws.sns.notification-topic}, the bare
     *                               operator notification topic name; empty when the key is absent
     * @throws NullPointerException if any injected client is {@code null}, which can only happen
     *                              in a hand-built context and is a programming error rather than a
     *                              runtime condition, so it is signalled at construction instead of
     *                              being deferred into a probe
     */
    public HealthIndicators(
            S3Client s3Client,
            SqsAsyncClient sqsAsyncClient,
            SnsClient snsClient,
            DataSource dataSource,
            @Value("${" + PROPERTY_BATCH_INPUT_BUCKET + ":}") String batchInputBucket,
            @Value("${" + PROPERTY_BATCH_OUTPUT_BUCKET + ":}") String batchOutputBucket,
            @Value("${" + PROPERTY_STATEMENTS_BUCKET + ":}") String statementsBucket,
            @Value("${" + PROPERTY_REPORT_QUEUE + ":}") String reportQueue,
            @Value("${" + PROPERTY_REPORT_QUEUE_LOGICAL_NAME + ":}") String reportQueueLogicalName,
            @Value("${" + PROPERTY_NOTIFICATION_TOPIC + ":}") String notificationTopic) {

        this.s3Client = Objects.requireNonNull(s3Client, "s3Client must not be null");
        this.sqsAsyncClient = Objects.requireNonNull(sqsAsyncClient, "sqsAsyncClient must not be null");
        this.snsClient = Objects.requireNonNull(snsClient, "snsClient must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");

        Map<String, String> buckets = new LinkedHashMap<>();
        buckets.put(PROPERTY_BATCH_INPUT_BUCKET, normalise(batchInputBucket));
        buckets.put(PROPERTY_BATCH_OUTPUT_BUCKET, normalise(batchOutputBucket));
        buckets.put(PROPERTY_STATEMENTS_BUCKET, normalise(statementsBucket));
        this.s3Buckets = Collections.unmodifiableMap(buckets);

        this.reportQueueName = normalise(reportQueue);
        this.reportQueueLogicalName = normalise(reportQueueLogicalName);
        this.notificationTopic = normalise(notificationTopic);
    }

    /**
     * Registers the object-storage readiness contributor under the health component key
     * {@link #S3_HEALTH_COMPONENT_NAME}.
     *
     * <p><strong>Purpose.</strong> Confirms that the object-storage layer which replaces the seven
     * generation-data-group bases is reachable and that all three configured buckets exist, so that
     * a batch job is not admitted to a substrate it cannot write its fixed-width outputs to.
     *
     * <p><strong>Operation.</strong> One {@code HeadBucket} request per configured bucket, in
     * declaration order, stopping at the first failure; then one {@code GetBucketVersioning} against
     * the batch-output bucket. Both are metadata-only: they transfer no object body and enumerate no
     * keys. A {@code PutObject}, a {@code DeleteObject} and a full {@code ListObjectsV2} enumeration
     * are each excluded deliberately - the first two because a probe must not mutate, the third
     * because Rule 1 Clause A forbids the obvious inefficiency of listing a bucket to learn only that
     * it answers.
     *
     * <p><strong>Correctness, not merely existence.</strong> A bucket that answers is not yet a
     * usable substrate. Object versioning is the mechanism that replaces generation-data-group
     * generations, so a batch-output bucket without it overwrites a generation key instead of adding
     * a version - a silent loss with no error on any path and no way to detect it from
     * {@code HeadBucket}. Readiness therefore requires {@link BucketVersioningStatus#ENABLED} on that
     * one bucket and reports {@link #REASON_ATTRIBUTE_MISMATCH} with
     * {@link #DETAIL_ATTRIBUTE} otherwise. The input and statements buckets are not checked: the
     * first is only read from, and the second is written with keys that already carry an account and
     * month segment, so neither can suffer a generation collision.
     *
     * <p><strong>Tradeoff, stated because Rule 1 Clause A requires tradeoffs to be justified.</strong>
     * Three requests per probe rather than one. A single request would leave a missing statements
     * bucket undetected until a statement job failed mid-run, and each request is a metadata-only
     * round trip against an in-network endpoint, so completeness is worth the two extra calls.
     * Probing stops at the first failure, so a broken substrate costs one call, not three.
     *
     * <p><strong>Configuration and defaults.</strong> Bucket names come from
     * {@code carddemo.aws.s3.batch-input-bucket}, {@code carddemo.aws.s3.batch-output-bucket} and
     * {@code carddemo.aws.s3.statements-bucket}. Each binds with an empty default, so a key that is
     * absent from the environment yields {@link #REASON_NOT_CONFIGURED} naming that key rather than
     * a {@link NullPointerException}. There is no default bucket name, no default region and no
     * default credential.
     *
     * <p><strong>Boundedness.</strong> Every call is bounded, and bounded within one budget rather
     * than per call: {@link #S3_PROBE_BUDGET_MILLIS} is the total for the whole contributor, and each
     * request is issued with an {@code apiCallTimeout} equal to whatever remains at that moment plus
     * an {@code apiCallAttemptTimeout} of {@link #PROBE_ATTEMPT_TIMEOUT_MILLIS}, applied as a
     * <em>request-level</em> override so the shared client's far longer deadlines - owned by
     * {@code com.cardemo.config.AwsConfig} for the batch writers that use the same client - are
     * untouched. Once the remaining budget falls below {@link #MINIMUM_CALL_BUDGET_MILLIS} the probe
     * reports {@link #REASON_TIMEOUT} without issuing a call at all, because the SDK rejects a
     * non-positive deadline and a call with milliseconds left could only expire in flight. No executor
     * and no additional client is introduced: a request override needs neither, so this class still
     * owns no thread pool and no lifecycle.
     *
     * <p><strong>Side effects.</strong> None beyond the read-only remote calls and, on failure, a
     * single {@code WARN} log carrying a symbolic reason, the bucket name and a curated exception
     * descriptor - never the throwable itself. Nothing is written, cached or mutated.
     *
     * <p><strong>Failure modes.</strong> An empty configured name yields
     * {@link #REASON_NOT_CONFIGURED}; a name that is an ARN or URL rather than a bare bucket name
     * yields {@link #REASON_INVALID_NAME} without echoing the value; an absent bucket yields
     * {@link #REASON_MISSING}; an unreachable endpoint or a service error yields
     * {@link #REASON_UNREACHABLE}; a bucket that answers without versioning enabled yields
     * {@link #REASON_ATTRIBUTE_MISMATCH}; an exceeded deadline or an exhausted budget yields
     * {@link #REASON_TIMEOUT}; anything else yields {@link #REASON_ERROR}. Every one of them is
     * returned as a {@code DOWN} status - the indicator never throws.
     *
     * <p><strong>Troubleshooting.</strong> Readiness {@code DOWN} with an S3 reason under the
     * {@code local} profile almost always means the emulator container is not up: run
     * {@code docker compose up -d localstack}, wait for it to report healthy, then re-check
     * {@code /actuator/health/readiness}. {@link #REASON_MISSING} with the emulator up instead means
     * {@code localstack-init/init-aws.sh} has not created the bucket named by the reported property
     * key, or that key has been renamed on one side only.
     *
     * @return the object-storage health contributor; never {@code null}
     */
    @Bean(S3_HEALTH_INDICATOR_BEAN_NAME)
    public HealthIndicator s3HealthIndicator() {
        return new S3BucketHealthIndicator(this.s3Client, this.s3Buckets);
    }

    /**
     * Registers the relational-store readiness contributor under the health component key
     * {@link #DB_HEALTH_COMPONENT_NAME}, <strong>replacing</strong> the framework's own.
     *
     * <p><strong>Purpose.</strong> Confirms that the relational substrate which replaces the ten VSAM
     * clusters is reachable, and confirms it <em>inside a deadline</em>. The first half is what the
     * framework contributor already did; the second half is why this one exists.
     *
     * <p><strong>Why it replaces rather than joins.</strong> Boot's {@code DataSourceHealthIndicator}
     * asks the pool for a connection with no deadline of its own, so it inherits
     * {@code spring.datasource.hikari.connection-timeout} - 30 000 ms - and a measured readiness probe
     * with the database stopped took 30.05 s while this class's own AWS contributors answered inside
     * 1 500 ms each. {@code management.health.db.enabled: false} in {@code application.yml} switches
     * the framework contributor off and this bean takes the same {@code db} key, so the readiness
     * group, the container health check and every published gate reading keep the spelling they had
     * and gain the deadline they did not.
     *
     * <p><strong>Operation.</strong> One {@code SELECT 1} on a borrowed connection, executed on a
     * single dedicated daemon thread and awaited for at most {@link #DB_PROBE_BUDGET_MILLIS}. The
     * query needs no privilege on any table, so a moved grant cannot make the substrate look down; the
     * statement additionally carries a JDBC query timeout derived from the budget that remains after
     * acquisition, so a connection that is handed over but then stalls is bounded too.
     *
     * <p><strong>Why a thread is required here and nowhere else in this class.</strong> The two AWS
     * probes bound themselves with a request-level SDK override, which needs no thread. JDBC offers no
     * equivalent: {@code DataSource.getConnection()} exposes no per-call deadline, and the wait that
     * has to be bounded is pool <em>acquisition</em>, which happens before any statement exists to set
     * a timeout on. Awaiting the call on another thread is therefore the only mechanism that bounds the
     * <em>answer</em>. Exactly one thread is used, it is a daemon so it can never hold the JVM open,
     * and it is shut down when the context closes. A queued probe whose budget has already elapsed
     * returns without touching the pool, so a stalled substrate cannot make probes pile up on it.
     *
     * <p><strong>Tradeoff, stated because Rule 1 Clause A requires tradeoffs to be justified.</strong>
     * An abandoned probe leaves its acquisition attempt running until the pool's own timeout expires:
     * the deadline bounds the <em>response</em>, not the orphaned attempt, because no JDBC API can
     * cancel an acquisition. That is strictly better than the previous behaviour, in which the
     * response was not bounded either. The alternative - a second, short-timeout pool dedicated to the
     * probe - was rejected: it would open connections the application does not otherwise use and would
     * report on a pool the request path never touches.
     *
     * <p><strong>Side effects.</strong> One borrowed and immediately returned connection, one
     * read-only statement, and on failure a single {@code WARN} carrying a symbolic reason and a
     * curated exception descriptor. Nothing is written, and no {@code SET}, no temporary table and no
     * transaction is left behind - the connection is closed by try-with-resources on every path.
     *
     * <p><strong>Failure modes.</strong> An exceeded budget yields {@link #REASON_TIMEOUT}; a refused
     * or unreachable server yields {@link #REASON_UNREACHABLE}; a query that answers with anything
     * other than 1 yields {@link #REASON_ATTRIBUTE_MISMATCH}; an interrupted wait yields
     * {@link #REASON_INTERRUPTED}; anything else yields {@link #REASON_ERROR}. Every one is returned
     * as {@code DOWN} - the indicator never throws, and it never publishes a JDBC URL, a role name, a
     * host, a port or an exception message.
     *
     * <p><strong>Troubleshooting.</strong> Readiness {@code DOWN} with
     * {@code component=db reason=unreachable} under the {@code local} profile almost always means the
     * database container is not up: run {@code docker compose up -d postgres}, wait for it to report
     * healthy, then re-check {@code /actuator/health/readiness}. {@link #REASON_TIMEOUT} with the
     * container up instead means acquisition is slow rather than impossible - inspect the pool.
     *
     * @return the relational-store health contributor; never {@code null}
     */
    @Bean(name = DB_HEALTH_INDICATOR_BEAN_NAME, destroyMethod = "close")
    public HealthIndicator dbHealthIndicator() {
        return new BoundedDataSourceHealthIndicator(this.dataSource);
    }

    /**
     * Registers the queue readiness contributor under the health component key
     * {@link #SQS_HEALTH_COMPONENT_NAME}.
     *
     * <p><strong>Purpose.</strong> Confirms that the SQS FIFO queue replacing
     * {@code DEFINE TDQUEUE(JOBS)} at {@code app/csd/CARDDEMO.CSD:L499-L505} is reachable, so that
     * the report-submission path is not admitted before the bridge it publishes to exists.
     *
     * <p><strong>Operation.</strong> A single {@code GetQueueUrl} request, which resolves a queue
     * name to its URL and is read-only. <strong>The response is discarded.</strong> A resolved queue
     * URL embeds the AWS account id, so surfacing or logging it would disclose that identifier; only the
     * reachability outcome is retained.
     *
     * <p><strong>What this probe must never do.</strong> It never sends, receives or deletes a
     * message. This is a correctness constraint, not a preference: receiving from the report queue
     * would make a health check silently consume real work, and sending would inject a spurious job
     * into the batch stream. Because the queue is FIFO, message-group ordering is a producer concern
     * owned by {@code com.cardemo.service.report.ReportSubmissionService} and is not observable
     * from, or affected by, this probe.
     *
     * <p><strong>Configuration and defaults.</strong> The physical queue name comes from
     * {@code carddemo.aws.sqs.report-queue} and retains the {@code .fifo} suffix AWS requires; it is
     * the only value sent to the service. The health detail instead publishes
     * {@code carddemo.aws.sqs.report-queue-logical-name}, normally
     * {@code carddemo-report-jobs}, because that value is a literal in {@code application.yml} and
     * so is provably free of any account identifier. When the logical name is absent the physical
     * name is published instead, having already been validated as a bare resource name. Both keys
     * bind with an empty default and neither has a fallback value.
     *
     * <p><strong>Boundedness, and cancellation.</strong> {@link #SQS_PROBE_BUDGET_MILLIS} is the total
     * for both calls, not an allowance per call: each is issued with an {@code apiCallTimeout} equal
     * to whatever remains at that moment plus an {@code apiCallAttemptTimeout} of
     * {@link #PROBE_ATTEMPT_TIMEOUT_MILLIS}, applied as a request-level override so the shared
     * client's own deadlines are untouched, and the future is then awaited for that same remaining
     * budget. Adding the attribute call therefore cannot extend this contributor's worst case. Once
     * the remainder falls below {@link #MINIMUM_CALL_BUDGET_MILLIS} the probe reports
     * {@link #REASON_TIMEOUT} without calling.
     *
     * <p>On every abandonment path - deadline exceeded, interrupt, or an unexpected runtime failure -
     * the in-flight future is <strong>cancelled with interruption</strong>. This matters more than it
     * appears: an abandoned-but-uncancelled request keeps an SDK thread, a connection and a response
     * buffer for as long as the transport allows, so a merely slow substrate would accumulate one
     * orphaned request per readiness poll and turn a soft failure into a resource leak.
     *
     * <p><strong>Side effects.</strong> None beyond the read-only remote calls and, on failure, a
     * single {@code WARN} log carrying a symbolic reason, the logical queue name and a curated
     * exception descriptor - never the throwable itself. If the awaiting thread is interrupted the
     * interrupt flag is restored before returning, so the interruption is reported rather than
     * discarded.
     *
     * <p><strong>Failure modes.</strong> An empty queue name yields
     * {@link #REASON_NOT_CONFIGURED}; an ARN or URL in place of a name yields
     * {@link #REASON_INVALID_NAME} without echoing the value; an absent queue yields
     * {@link #REASON_MISSING}; an unreachable endpoint or service error yields
     * {@link #REASON_UNREACHABLE}; a queue that resolves but is not FIFO, or whose deduplication
     * attribute contradicts the send path, yields {@link #REASON_ATTRIBUTE_MISMATCH}; exceeding the deadline or exhausting
     * the budget yields {@link #REASON_TIMEOUT}; an interrupt yields {@link #REASON_INTERRUPTED};
     * anything else yields {@link #REASON_ERROR}. All are returned as {@code DOWN} - the indicator
     * never throws.
     *
     * <p><strong>Troubleshooting.</strong> Readiness {@code DOWN} on SQS normally means
     * {@code localstack-init/init-aws.sh} has not created {@code carddemo-report-jobs}; the script
     * is idempotent, so re-running it is safe. Remember that the physical name carries the
     * {@code .fifo} suffix while the logical name does not, so a {@link #REASON_MISSING} while the
     * queue visibly exists usually means the suffix was dropped from the physical name.
     *
     * @return the queue health contributor; never {@code null}
     */
    @Bean(SQS_HEALTH_INDICATOR_BEAN_NAME)
    public HealthIndicator sqsHealthIndicator() {
        return new SqsQueueHealthIndicator(
                this.sqsAsyncClient, this.reportQueueName, this.reportQueueLogicalName);
    }

    /**
     * Registers the notification readiness contributor under the health component key
     * {@link #SNS_HEALTH_COMPONENT_NAME}.
     *
     * <p><strong>Purpose, and the finding it closes.</strong> Confirms that the operator notification
     * topic named by {@code carddemo.aws.sns.notification-topic} is actually provisioned, so the report
     * path is not admitted before the destination it notifies exists.
     *
     * <p>Before this contributor existed, notification was the one required cloud dependency that
     * readiness said nothing about. Object storage and the queue were each probed; the topic was not.
     * An instance configured with a topic name that had never been provisioned - a renamed variable, a
     * provisioning script that had not run, a typo - started cleanly, answered
     * {@code GET /actuator/health/readiness} with {@code 200 {"status":"UP"}}, passed the container
     * health check and took traffic. The failure surfaced only when a report was submitted, as an
     * {@code IllegalStateException} out of the topic resolver on the request thread. That is a
     * <strong>Major</strong> defect of exactly the kind readiness exists to prevent: an instance that
     * cannot perform one of its functions was reported ready to perform all of them.
     *
     * <p><strong>Operation, and why it is this call.</strong> A read-only {@code ListTopics} walk,
     * comparing the configured bare name against the last segment of each listed identifier - the same
     * resolution {@code com.cardemo.config.AwsConfig.ProvisionedTopicArnResolver} performs on the
     * publish path, so readiness asserts precisely what a send needs and nothing more. There is no
     * "get topic by name" operation in the notification API: {@code GetTopicAttributes} requires an
     * ARN, and constructing one would require the account identifier this class must never handle.
     * {@code CreateTopic} is idempotent and would return the ARN directly, and is <strong>excluded
     * absolutely</strong> - a probe must not provision, and topic creation belongs to
     * {@code localstack-init/init-aws.sh}, which is where the resolver deliberately leaves it.
     *
     * <p><strong>Why not simply call the resolver bean.</strong> Delegating to
     * {@code TopicArnResolver} would guarantee agreement with the publish path by construction, and was
     * rejected for one reason: that walk issues its requests with no deadline, so it inherits the shared
     * client's timeouts, which are sized for publishing rather than for probing. A readiness probe that
     * can block for the client's full timeout breaks the boundedness constraint every contributor in
     * this class upholds. The walk is therefore reimplemented here with a request-level deadline drawn
     * from {@link #SNS_PROBE_BUDGET_MILLIS}, and the one value the two implementations must share - the
     * page ceiling - is tied by {@link #TOPIC_PAGE_LIMIT_COUNT} and asserted by test.
     *
     * <p><strong>What this probe must never do.</strong> It publishes nothing, subscribes to nothing,
     * creates nothing and deletes nothing. Publishing from a health check would inject a spurious
     * operator notification on every readiness poll - once every fifteen seconds under the container
     * health check - which is worse than having no probe at all.
     *
     * <p><strong>No identifier ever leaves this probe.</strong> A resolved topic ARN embeds the
     * twelve-digit AWS account segment, exactly as a queue URL does. Listed identifiers are compared
     * in-process and discarded; the only name that reaches a detail or a log line is the configured bare
     * name, and the only other published value is a page count. Pagination tokens are consumed and never
     * published.
     *
     * <p><strong>Boundedness.</strong> {@link #SNS_PROBE_BUDGET_MILLIS} is the total for the whole walk,
     * not an allowance per page. Each page request carries an {@code apiCallTimeout} equal to what
     * remains at that moment plus an {@code apiCallAttemptTimeout} of
     * {@link #PROBE_ATTEMPT_TIMEOUT_MILLIS}, applied as a request-level override so the shared client's
     * own deadlines are untouched. Once the remainder falls below {@link #MINIMUM_CALL_BUDGET_MILLIS}
     * the probe reports {@link #REASON_TIMEOUT} without calling. The client is synchronous, so there is
     * no future to abandon and nothing to cancel - the SDK's own call deadline is the cancellation
     * mechanism, which is the same arrangement {@link S3BucketHealthIndicator} uses.
     *
     * <p><strong>Side effects.</strong> None beyond the read-only listing calls and, on failure, a
     * single {@code WARN} carrying a symbolic reason, the bare topic name and a curated exception
     * descriptor - never the throwable itself.
     *
     * <p><strong>Failure modes.</strong> An empty topic name yields {@link #REASON_NOT_CONFIGURED}; an
     * ARN or URL supplied in place of a bare name yields {@link #REASON_INVALID_NAME} without echoing
     * the value; a topic absent from the whole listing yields {@link #REASON_MISSING}; an unreachable
     * endpoint or service error yields {@link #REASON_UNREACHABLE}; exhausting the budget or the SDK
     * deadline yields {@link #REASON_TIMEOUT}; anything else yields {@link #REASON_ERROR}. All are
     * returned as {@code DOWN} - the indicator never throws.
     *
     * <p><strong>Troubleshooting.</strong> {@link #REASON_MISSING} with the emulator plainly up means
     * {@code localstack-init/init-aws.sh} has not created {@code carddemo-notifications}, or the topic
     * variable has been renamed on one side only. The script is idempotent, so re-running it is safe.
     * Unlike the report queue the topic name carries no suffix, so a name that "looks right" and still
     * reports missing is usually a spelling difference rather than a suffix that was dropped.
     *
     * @return the notification health contributor; never {@code null}
     */
    @Bean(SNS_HEALTH_INDICATOR_BEAN_NAME)
    public HealthIndicator snsHealthIndicator() {
        return new SnsTopicHealthIndicator(this.snsClient, this.notificationTopic);
    }

    // Shared private static helpers. Static so that neither the constructor nor a nested indicator
    // can invoke an overridable instance method, which keeps the class free of any `this` escape and
    // keeps every helper a pure function of its arguments.

    /**
     * Canonicalises a bound configuration value so that every downstream decision is made against
     * one form.
     *
     * <p>Maps {@code null} to the empty string and strips surrounding whitespace, because a YAML or
     * environment value can legitimately arrive padded and a padded name is not a different name.
     * The result is never {@code null}, which is what lets each probe test emptiness rather than
     * nullity.
     *
     * @param value the raw bound value, possibly {@code null}
     * @return the trimmed value, or the empty string when the input was {@code null} or blank
     */
    private static String normalise(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * Reports whether a configured resource name is unsafe to send to a service or to publish in a
     * health detail.
     *
     * <p>A legitimate S3 bucket name is a short unqualified token of lowercase alphanumerics, dots
     * and hyphens, and a legitimate SQS queue name is alphanumerics, hyphens, underscores and an
     * optional {@code .fifo} suffix. Neither can contain a colon, a solidus or a commercial at, so
     * the presence of any of those means the value is an ARN, a URL or a credential-bearing string
     * rather than a bare name. Such a value is refused rather than probed, and is never echoed into
     * a detail or a log, because an ARN or URL can embed an AWS account id.
     *
     * <p>Configuration is treated as untrusted input here on purpose. It is Rule 1 Clause A's
     * "security by default" applied to the one input this class has, and it is the guard that makes
     * publishing a bucket or queue name in a health body defensible rather than merely conventional.
     *
     * @param name a normalised, non-{@code null} configured name
     * @return {@code true} when the name must not be used, {@code false} when it is a bare resource
     *         name
     */
    private static boolean isUnsafeResourceName(String name) {
        for (int index = 0; index < name.length(); index++) {
            if (UNSAFE_NAME_CHARACTERS.indexOf(name.charAt(index)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Converts a monotonic start reading into an elapsed duration in whole milliseconds.
     *
     * <p>Uses {@link System#nanoTime()} rather than wall-clock time so the measurement is immune to
     * a clock adjustment mid-probe, and reports whole milliseconds because sub-millisecond precision
     * is meaningless for a network round trip and would only add noise to a dashboard.
     *
     * @param startedAtNanos the {@link System#nanoTime()} reading taken when the probe began
     * @return the elapsed time in milliseconds, never negative
     */
    private static long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    /**
     * Reports how much of a contributor's budget remains, never negative.
     *
     * <p>Clamped at zero rather than allowed to go negative so that the single call site
     * {@link #hasCallBudget(long)} has one comparison to make and {@link #callDeadline(long)} can
     * never be handed a value the SDK would reject.
     *
     * @param startedAtNanos the monotonic reading taken when the probe began
     * @param budgetMillis   the contributor's total budget in milliseconds
     * @return the remaining budget in milliseconds, zero once exhausted
     */
    private static long remainingBudgetMillis(long startedAtNanos, long budgetMillis) {
        return Math.max(budgetMillis - elapsedMillis(startedAtNanos), 0L);
    }

    /**
     * Reports whether the remaining budget is large enough to be worth issuing a call with.
     *
     * @param remainingMillis the remaining budget from {@link #remainingBudgetMillis(long, long)}
     * @return {@code true} when a call may be issued, {@code false} when the probe must report
     *         {@link #REASON_TIMEOUT} without calling
     */
    private static boolean hasCallBudget(long remainingMillis) {
        return remainingMillis >= MINIMUM_CALL_BUDGET_MILLIS;
    }

    /**
     * Builds the per-request deadline that bounds one probe call inside the remaining budget.
     *
     * <p>A <em>request-level</em> override rather than a client-level one, deliberately: the injected
     * clients are shared with the batch writers and the report publisher, whose deadlines are set
     * once by {@code com.cardemo.config.AwsConfig} and are correctly far longer than a health probe's.
     * A request override narrows the deadline for this call only and leaves every other caller of the
     * same client untouched, which is why no separate client and no executor is introduced here.
     *
     * @param remainingMillis the remaining budget; must satisfy {@link #hasCallBudget(long)}
     * @return the request override carrying a whole-call and a per-attempt deadline
     */
    private static AwsRequestOverrideConfiguration callDeadline(long remainingMillis) {
        return AwsRequestOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofMillis(remainingMillis))
                .apiCallAttemptTimeout(
                        Duration.ofMillis(Math.min(remainingMillis, PROBE_ATTEMPT_TIMEOUT_MILLIS)))
                .build();
    }

    /**
     * Reduces a failure to the curated descriptor that is safe to log.
     *
     * <p>This is the whole of what any indicator ever tells the log about a throwable. The
     * exception's message, its suppressed exceptions and its stack trace are all excluded by
     * construction, because an SDK message routinely carries the resolved endpoint and a queue URL
     * with its twelve-digit account segment, and a rendered stack frame carries the same values as
     * frame arguments where no field-path masking rule can reach them. A class name is a compile-time
     * symbol and an HTTP status is a three-digit integer, so neither can carry an endpoint, an
     * account id, a credential or customer data no matter what the service returned.
     *
     * <p>An {@link ExecutionException} is unwrapped to its cause first, because for an asynchronous
     * SDK call the wrapper is never the interesting type.
     *
     * @param failure the throwable to describe, or {@code null} when the fault is budget exhaustion
     *                detected before any call was issued
     * @return the class name, suffixed with the service status code in parentheses when the service
     *         answered with one; never {@code null} and never derived from the exception message
     */
    private static String describeFailure(Throwable failure) {
        if (failure == null) {
            return FAILURE_DESCRIPTOR_NONE;
        }

        Throwable root = failure;
        if (failure instanceof ExecutionException && failure.getCause() != null) {
            root = failure.getCause();
        }

        // getSimpleName() is empty for an anonymous class, which an SDK can produce; the fully
        // qualified name is the fallback and is equally free of payload.
        String simpleName = root.getClass().getSimpleName();
        String className = simpleName.isEmpty() ? root.getClass().getName() : simpleName;

        if (root instanceof SdkServiceException serviceFailure) {
            return className + "(" + serviceFailure.statusCode() + ")";
        }
        return className;
    }

    /**
     * Cancels a probe call that is still in flight, interrupting it if it has already started.
     *
     * <p>Called on every abandonment path. Without it a timed-out probe leaves its request running on
     * an SDK thread, holding a connection and a response buffer for as long as the transport allows -
     * so a substrate that is merely slow accumulates one orphaned request per readiness poll, which
     * turns a soft failure into a resource leak. Cancelling a future that has already completed is a
     * documented no-op, so this needs no completion test and is safe to call unconditionally.
     *
     * @param pending the future to abandon, or {@code null} if the call was never issued
     */
    private static void cancelQuietly(CompletableFuture<?> pending) {
        if (pending != null) {
            pending.cancel(true);
        }
    }

    /**
     * Object-storage readiness probe, produced by {@link HealthIndicators#s3HealthIndicator()}.
     *
     * <p>Nested and {@code private static final} on purpose. It stays inside the one permitted
     * top-level type of this file; being {@code static} it captures no enclosing instance; and being
     * {@code final} it can never be subclassed, so no constructor here can leak a partially
     * initialised {@code this}. The class is immutable after construction and therefore safe to
     * probe from concurrent request threads.
     */
    private static final class S3BucketHealthIndicator implements HealthIndicator {

        /**
         * HTTP status the S3 service returns for an absent bucket. Declared as a constant rather
         * than imported so this class pulls in no HTTP or servlet type for a single integer, and so
         * the 404-to-{@link HealthIndicators#REASON_MISSING} mapping is named where it is used.
         */
        private static final int HTTP_STATUS_NOT_FOUND = 404;

        /** Structured logger, named for the nested indicator rather than the enclosing class. */
        private static final Logger LOG = LoggerFactory.getLogger(S3BucketHealthIndicator.class);

        /** The client each probe issues its head-bucket call through. */
        private final S3Client s3Client;

        /**
         * Logical name to physical bucket name, one entry per bucket the batch stream uses. The logical
         * name is what a report names, so a failure can be attributed to the dataset role rather than only
         * to the bucket.
         */
        private final Map<String, String> buckets;

        /**
         * Binds the probe to its client and to the logical-to-physical bucket map it reports against.
         *
         * @param s3Client the client each head-bucket call is issued through
         * @param buckets logical name to physical bucket name, so a failure names the dataset role
         */
        private S3BucketHealthIndicator(S3Client s3Client, Map<String, String> buckets) {
            this.s3Client = s3Client;
            this.buckets = buckets;
        }

        /**
         * Probes every configured bucket with a metadata-only {@code HeadBucket}, verifies that the
         * batch-output bucket has versioning enabled, and reports the outcome.
         *
         * <p>Iterates in declaration order and returns at the first failure, so a broken substrate
         * costs one round trip rather than three. Configuration is validated before any request is
         * issued, which is what makes an unset or malformed name a deterministic {@code DOWN}
         * instead of a request that could not have succeeded. The versioning check runs only once
         * every bucket has answered, so a missing bucket is still reported in one round trip. Every
         * call carries a deadline drawn from the contributor's remaining budget.
         *
         * @return {@code UP} with the probed-bucket count, the verified versioning state and elapsed
         *         milliseconds when every bucket answered and versioning is enabled, otherwise
         *         {@code DOWN} with a symbolic reason and no diagnostic payload; never {@code null}
         *         and never thrown out of
         */
        @Override
        public Health health() {
            long startedAtNanos = System.nanoTime();
            int bucketsProbed = 0;

            for (Map.Entry<String, String> configuredBucket : this.buckets.entrySet()) {
                String propertyKey = configuredBucket.getKey();
                String bucketName = configuredBucket.getValue();

                if (bucketName.isEmpty()) {
                    return misconfigured(propertyKey, REASON_NOT_CONFIGURED, bucketsProbed,
                            startedAtNanos);
                }
                if (isUnsafeResourceName(bucketName)) {
                    return misconfigured(propertyKey, REASON_INVALID_NAME, bucketsProbed,
                            startedAtNanos);
                }

                long remainingMillis = remainingBudgetMillis(startedAtNanos, S3_PROBE_BUDGET_MILLIS);
                if (!hasCallBudget(remainingMillis)) {
                    // Report without calling: the SDK rejects a non-positive deadline, and a call
                    // issued with a few milliseconds left could only expire in flight.
                    return unreachable(bucketName, REASON_TIMEOUT, bucketsProbed, startedAtNanos,
                            null);
                }

                bucketsProbed++;
                try {
                    this.s3Client.headBucket(HeadBucketRequest.builder()
                            .bucket(bucketName)
                            .overrideConfiguration(callDeadline(remainingMillis))
                            .build());
                } catch (NoSuchBucketException absentBucket) {
                    return unreachable(bucketName, REASON_MISSING, bucketsProbed, startedAtNanos,
                            absentBucket);
                } catch (ApiCallTimeoutException | ApiCallAttemptTimeoutException deadlineExceeded) {
                    // Caught ahead of SdkException so an exhausted budget is reported as a timeout
                    // rather than as an unreachable endpoint: the remedies differ, because a timeout
                    // means the substrate is slow and unreachable means it is absent.
                    return unreachable(bucketName, REASON_TIMEOUT, bucketsProbed, startedAtNanos,
                            deadlineExceeded);
                } catch (SdkException sdkFailure) {
                    // A HEAD response carries no error body, so some SDK releases surface an absent
                    // bucket as a plain 404 service exception rather than as NoSuchBucketException.
                    // Both spellings must map to the same reason, or an operator would be told the
                    // endpoint is unreachable when provisioning is simply incomplete.
                    String reason = isNotFound(sdkFailure) ? REASON_MISSING : REASON_UNREACHABLE;
                    return unreachable(bucketName, reason, bucketsProbed, startedAtNanos, sdkFailure);
                } catch (RuntimeException unexpected) {
                    return unreachable(bucketName, REASON_ERROR, bucketsProbed, startedAtNanos,
                            unexpected);
                }
            }

            // Existence is necessary but not sufficient. The batch-output bucket is the object-storage
            // replacement for the generation data groups of app/jcl/DEFGDGB.jcl, and it is versioning
            // that supplies the generation semantics: without it a second write to the same generation
            // key overwrites the first instead of creating a version, silently losing a generation
            // with no error on any path. That fault is invisible to HeadBucket, so it is verified here.
            Health versioningFault = verifyOutputBucketVersioning(bucketsProbed, startedAtNanos);
            if (versioningFault != null) {
                return versioningFault;
            }

            return Health.up()
                    .withDetail(DETAIL_COMPONENT, S3_HEALTH_COMPONENT_NAME)
                    .withDetail(DETAIL_BUCKETS_PROBED, bucketsProbed)
                    .withDetail(DETAIL_VERSIONING, BucketVersioningStatus.ENABLED.toString())
                    .withDetail(DETAIL_ELAPSED_MILLIS, elapsedMillis(startedAtNanos))
                    .build();
        }

        /**
         * Verifies that the batch-output bucket has object versioning enabled.
         *
         * <p>Only the batch-output bucket is checked. The input bucket is read from, so a lost
         * version there is not a correctness fault, and the statements bucket is written with keys
         * that already carry an account and month segment, so a generation collision cannot arise.
         * Probing all three would triple the request cost to learn two facts that cannot fail -
         * exactly the obvious inefficiency Rule 1 Clause A forbids.
         *
         * <p>Returns {@code null} on success rather than a {@code Health}, so the caller keeps one
         * success path and the reader can see at the call site that a non-null return is a fault.
         *
         * @param bucketsProbed  how many buckets were probed for existence, carried into the result
         * @param startedAtNanos the probe start reading
         * @return {@code null} when versioning is enabled, otherwise the curated {@code DOWN} result
         */
        private Health verifyOutputBucketVersioning(int bucketsProbed, long startedAtNanos) {
            String bucketName = this.buckets.get(PROPERTY_BATCH_OUTPUT_BUCKET);
            if (bucketName == null || bucketName.isEmpty()) {
                // Unreachable in practice, because an empty value returns REASON_NOT_CONFIGURED from
                // the existence loop before this runs. Retained so that the method is correct in
                // isolation rather than only in the order its caller happens to use.
                return misconfigured(PROPERTY_BATCH_OUTPUT_BUCKET, REASON_NOT_CONFIGURED,
                        bucketsProbed, startedAtNanos);
            }

            long remainingMillis = remainingBudgetMillis(startedAtNanos, S3_PROBE_BUDGET_MILLIS);
            if (!hasCallBudget(remainingMillis)) {
                return unreachable(bucketName, REASON_TIMEOUT, bucketsProbed, startedAtNanos, null);
            }

            BucketVersioningStatus status;
            try {
                GetBucketVersioningResponse versioning =
                        this.s3Client.getBucketVersioning(GetBucketVersioningRequest.builder()
                                .bucket(bucketName)
                                .overrideConfiguration(callDeadline(remainingMillis))
                                .build());
                status = versioning.status();
            } catch (ApiCallTimeoutException | ApiCallAttemptTimeoutException deadlineExceeded) {
                return unreachable(bucketName, REASON_TIMEOUT, bucketsProbed, startedAtNanos,
                        deadlineExceeded);
            } catch (SdkException sdkFailure) {
                String reason = isNotFound(sdkFailure) ? REASON_MISSING : REASON_UNREACHABLE;
                return unreachable(bucketName, reason, bucketsProbed, startedAtNanos, sdkFailure);
            } catch (RuntimeException unexpected) {
                return unreachable(bucketName, REASON_ERROR, bucketsProbed, startedAtNanos,
                        unexpected);
            }

            // A bucket that has never had versioning configured answers with no status at all, so an
            // absent status is a mismatch rather than an error: the guarantee is simply not in force.
            if (status != BucketVersioningStatus.ENABLED) {
                return attributeMismatch(bucketName, ATTRIBUTE_LABEL_VERSIONING, bucketsProbed,
                        startedAtNanos);
            }
            return null;
        }

        /**
         * Reports whether an SDK failure was a not-found service response.
         *
         * @param failure the SDK exception to classify; never {@code null}
         * @return {@code true} when the service answered with a 404 status
         */
        private static boolean isNotFound(SdkException failure) {
            return failure instanceof SdkServiceException serviceFailure
                    && serviceFailure.statusCode() == HTTP_STATUS_NOT_FOUND;
        }

        /**
         * Builds the {@code DOWN} result for a configuration fault, naming the offending property
         * key and never its value.
         *
         * <p>Logged at {@code WARN} without a throwable, because there is none: a misconfiguration
         * is a state, not an exception, and inventing one to satisfy a log signature would
         * fabricate a stack trace that points at this class rather than at the fault.
         *
         * @param propertyKey     the configuration key at fault
         * @param reason          {@link HealthIndicators#REASON_NOT_CONFIGURED} or
         *                        {@link HealthIndicators#REASON_INVALID_NAME}
         * @param bucketsProbed   how many buckets had been probed before the fault was found
         * @param startedAtNanos  the probe start reading
         * @return the curated {@code DOWN} result
         */
        private static Health misconfigured(String propertyKey, String reason, int bucketsProbed,
                long startedAtNanos) {
            LOG.warn("S3 readiness probe refused: property={} reason={}", propertyKey, reason);
            return Health.down()
                    .withDetail(DETAIL_COMPONENT, S3_HEALTH_COMPONENT_NAME)
                    .withDetail(DETAIL_PROPERTY, propertyKey)
                    .withDetail(DETAIL_REASON, reason)
                    .withDetail(DETAIL_BUCKETS_PROBED, bucketsProbed)
                    .withDetail(DETAIL_ELAPSED_MILLIS, elapsedMillis(startedAtNanos))
                    .build();
        }

        /**
         * Builds the {@code DOWN} result for a bucket that exists but does not satisfy a required
         * attribute.
         *
         * <p>Logged without a throwable because there is none: an attribute that is not enabled is a
         * state the service reported successfully, and manufacturing an exception for it would
         * fabricate a stack trace pointing at this class rather than at the misprovisioned bucket.
         *
         * @param bucketName     the bare bucket name whose attribute did not satisfy the contract
         * @param attribute      the symbolic attribute label, a compile-time literal
         * @param bucketsProbed  how many buckets were probed for existence
         * @param startedAtNanos the probe start reading
         * @return the curated {@code DOWN} result
         */
        private static Health attributeMismatch(String bucketName, String attribute,
                int bucketsProbed, long startedAtNanos) {
            LOG.warn("S3 readiness probe refused: bucket={} attribute={} reason={}", bucketName,
                    attribute, REASON_ATTRIBUTE_MISMATCH);
            return Health.down()
                    .withDetail(DETAIL_COMPONENT, S3_HEALTH_COMPONENT_NAME)
                    .withDetail(DETAIL_BUCKET, bucketName)
                    .withDetail(DETAIL_ATTRIBUTE, attribute)
                    .withDetail(DETAIL_REASON, REASON_ATTRIBUTE_MISMATCH)
                    .withDetail(DETAIL_BUCKETS_PROBED, bucketsProbed)
                    .withDetail(DETAIL_ELAPSED_MILLIS, elapsedMillis(startedAtNanos))
                    .build();
        }

        /**
         * Builds the {@code DOWN} result for a failed remote call.
         *
         * <p>The throwable is <strong>not</strong> handed to the logger. It is classified instead:
         * the symbolic reason says what an operator must do about it and
         * {@link HealthIndicators#describeFailure(Throwable)} names the exact SDK type and, when the
         * service answered, its HTTP status. This is not a swallow - the cause is caught, classified
         * and reported on both channels - but it is deliberately not a rendering, because an SDK
         * message and its stack frames carry the resolved endpoint and the account-bearing URL that
         * neither the log nor the health body may publish. {@code Health.down(Throwable)} is likewise
         * never used, because it would serialise the exception into the details map.
         *
         * @param bucketName      the bare bucket name whose probe failed
         * @param reason          the symbolic reason from the closed vocabulary
         * @param bucketsProbed   how many buckets had been probed, including this one
         * @param startedAtNanos  the probe start reading
         * @param failure         the cause to classify, or {@code null} when the budget was exhausted
         *                        before the call could be issued
         * @return the curated {@code DOWN} result
         */
        private static Health unreachable(String bucketName, String reason, int bucketsProbed,
                long startedAtNanos, Throwable failure) {
            LOG.warn("S3 readiness probe failed: bucket={} reason={} exception={}", bucketName,
                    reason, describeFailure(failure));
            return Health.down()
                    .withDetail(DETAIL_COMPONENT, S3_HEALTH_COMPONENT_NAME)
                    .withDetail(DETAIL_BUCKET, bucketName)
                    .withDetail(DETAIL_REASON, reason)
                    .withDetail(DETAIL_BUCKETS_PROBED, bucketsProbed)
                    .withDetail(DETAIL_ELAPSED_MILLIS, elapsedMillis(startedAtNanos))
                    .build();
        }
    }

    /**
     * Relational-store readiness probe, produced by {@link HealthIndicators#dbHealthIndicator()}.
     *
     * <p>Nested and {@code private static final} for the same reasons as
     * {@link S3BucketHealthIndicator}: one top-level type per file, no captured enclosing instance and
     * no subclass. Unlike the other two it owns one resource - a single-thread executor - so it is
     * {@link AutoCloseable} and the bean declares that method as its destroy method.
     */
    private static final class BoundedDataSourceHealthIndicator implements HealthIndicator,
            AutoCloseable {

        /**
         * Logger for this probe, named for this class so an operator can raise its level without
         * raising the other three contributors' - each indicator owns its own, as the sibling classes do.
         */
        private static final Logger LOG =
                LoggerFactory.getLogger(BoundedDataSourceHealthIndicator.class);

        /** The pool a probe borrows one connection from. Injected, never built here. */
        private final DataSource dataSource;

        /**
         * The one thread every probe runs on, so that the waiting caller can abandon it on a deadline.
         *
         * <p>Single-threaded and daemon: single-threaded because at most one readiness probe should
         * ever be touching the pool, and daemon so a probe stalled on a substrate that has gone away
         * can never hold the JVM open.
         */
        private final ExecutorService probeExecutor;

        /**
         * Creates the probe.
         *
         * @param dataSource the pool to borrow from; never null
         */
        private BoundedDataSourceHealthIndicator(DataSource dataSource) {
            this.dataSource = dataSource;
            ThreadFactory daemonFactory = runnable -> {
                Thread thread = new Thread(runnable, DB_PROBE_THREAD_NAME);
                thread.setDaemon(true);
                return thread;
            };
            this.probeExecutor = Executors.newSingleThreadExecutor(daemonFactory);
        }

        /**
         * Probes the relational store inside {@link #DB_PROBE_BUDGET_MILLIS} and reports the outcome.
         *
         * <p>Side effects: borrows and returns one pooled connection and executes one read-only
         * statement; on failure writes one {@code WARN}. Never throws, and never publishes a JDBC URL,
         * a host, a port, a role name or an exception message.
         *
         * @return {@code UP} with the elapsed time, or {@code DOWN} with a symbolic reason; never null
         */
        @Override
        public Health health() {
            long startedAtNanos = System.nanoTime();
            CompletableFuture<Integer> pending = null;
            try {
                pending = CompletableFuture.supplyAsync(
                        () -> probe(startedAtNanos), this.probeExecutor);
                Integer answer = pending.get(DB_PROBE_BUDGET_MILLIS, TimeUnit.MILLISECONDS);
                if (answer == null) {
                    // The queued task found its budget already spent and declined to touch the pool.
                    return down(REASON_TIMEOUT, startedAtNanos, null);
                }
                if (answer != 1) {
                    return down(REASON_ATTRIBUTE_MISMATCH, startedAtNanos, null);
                }
                return Health.up()
                        .withDetail(DETAIL_COMPONENT, DB_HEALTH_COMPONENT_NAME)
                        .withDetail(DETAIL_ELAPSED_MILLIS, elapsedMillis(startedAtNanos))
                        .build();
            } catch (TimeoutException expired) {
                return down(REASON_TIMEOUT, startedAtNanos, expired);
            } catch (InterruptedException interrupted) {
                // Restore the flag rather than swallowing it: the wait was abandoned by something that
                // is entitled to stop this thread, and a health probe must not hide that.
                Thread.currentThread().interrupt();
                return down(REASON_INTERRUPTED, startedAtNanos, interrupted);
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                String reason = cause instanceof SQLException ? REASON_UNREACHABLE : REASON_ERROR;
                return down(reason, startedAtNanos, failed);
            } finally {
                // Abandoning the wait must also abandon the work, for the same reason the AWS probes
                // cancel theirs: an orphaned probe per poll turns a slow substrate into a leak. The
                // acquisition itself cannot be cancelled - JDBC offers no mechanism - but the
                // interrupt reaches the statement once a connection is in hand.
                cancelQuietly(pending);
            }
        }

        /**
         * Runs the validation query on the probe thread, bounded by whatever budget remains.
         *
         * @param startedAtNanos the monotonic reading taken when the probe began
         * @return the single integer the query answered, or {@code null} when the budget was already
         *         spent before the pool was touched
         */
        private Integer probe(long startedAtNanos) {
            long remainingMillis = remainingBudgetMillis(startedAtNanos, DB_PROBE_BUDGET_MILLIS);
            if (!hasCallBudget(remainingMillis)) {
                return null;
            }
            try (Connection connection = this.dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                // Ceiling division, so a sub-second remainder becomes 1 rather than 0: JDBC reads 0 as
                // "no limit", which would reintroduce exactly the unbounded wait this class removes.
                long afterAcquisitionMillis =
                        remainingBudgetMillis(startedAtNanos, DB_PROBE_BUDGET_MILLIS);
                statement.setQueryTimeout((int) Math.max(1L, (afterAcquisitionMillis + 999L) / 1000L));
                try (var answer = statement.executeQuery(DB_VALIDATION_QUERY)) {
                    return answer.next() ? answer.getInt(1) : 0;
                }
            } catch (SQLException unavailable) {
                // Wrapped so that health() classifies it from the cause; the message never escapes
                // this frame, because CompletionException.toString would carry it into a log.
                throw new java.util.concurrent.CompletionException(unavailable);
            }
        }

        /**
         * Builds the curated {@code DOWN} result and writes the one log record that accompanies it.
         *
         * @param reason         the symbolic reason from the closed vocabulary
         * @param startedAtNanos the probe start reading
         * @param failure        the cause to classify, or {@code null} when the budget was exhausted
         *                       before the pool was touched
         * @return the curated {@code DOWN} result
         */
        private static Health down(String reason, long startedAtNanos, Throwable failure) {
            LOG.warn("Database readiness probe failed: component={} reason={} exception={}",
                    DB_HEALTH_COMPONENT_NAME, reason, describeFailure(failure));
            return Health.down()
                    .withDetail(DETAIL_COMPONENT, DB_HEALTH_COMPONENT_NAME)
                    .withDetail(DETAIL_REASON, reason)
                    .withDetail(DETAIL_ELAPSED_MILLIS, elapsedMillis(startedAtNanos))
                    .build();
        }

        /**
         * Shuts the probe thread down when the application context closes.
         *
         * <p>Declared as the bean's destroy method. A stalled probe is not waited on beyond
         * {@link #DB_PROBE_SHUTDOWN_GRACE_SECONDS}, because the thread is a daemon and shutdown must
         * not be held up by a substrate that has already gone away.
         */
        @Override
        public void close() {
            this.probeExecutor.shutdownNow();
            try {
                if (!this.probeExecutor.awaitTermination(
                        DB_PROBE_SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                    LOG.debug("Database readiness probe thread did not finish within {}s of shutdown;"
                            + " it is a daemon, so it cannot hold the JVM open",
                            DB_PROBE_SHUTDOWN_GRACE_SECONDS);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Queue readiness probe, produced by {@link HealthIndicators#sqsHealthIndicator()}.
     *
     * <p>Nested and {@code private static final} for the same reasons as
     * {@link S3BucketHealthIndicator}: one top-level type per file, no captured enclosing instance,
     * no subclass and therefore no {@code this} escape, and immutability after construction.
     */
    private static final class SqsQueueHealthIndicator implements HealthIndicator {

        /** Structured logger, named for the nested indicator rather than the enclosing class. */
        private static final Logger LOG = LoggerFactory.getLogger(SqsQueueHealthIndicator.class);

        /** The client each probe resolves the queue URL through. */
        private final SqsAsyncClient sqsAsyncClient;

        /** The physical queue name, including the FIFO suffix the queue is actually created with. */
        private final String queueName;

        /**
         * The logical name the report attributes a failure to, standing for the transient data queue
         * {@code JOBS} of {@code app/csd/CARDDEMO.CSD:L499-L503} that this queue replaces.
         */
        private final String queueLogicalName;

        /**
         * Binds the probe to its client and to both names of the queue it reports against.
         *
         * @param sqsAsyncClient the client the queue URL is resolved through
         * @param queueName the physical queue name, including its FIFO suffix
         * @param queueLogicalName the logical name a failure is attributed to, standing for the transient
         *     data queue {@code JOBS} of {@code app/csd/CARDDEMO.CSD:L499-L503}
         */
        private SqsQueueHealthIndicator(SqsAsyncClient sqsAsyncClient, String queueName,
                String queueLogicalName) {
            this.sqsAsyncClient = sqsAsyncClient;
            this.queueName = queueName;
            this.queueLogicalName = queueLogicalName;
        }

        /**
         * Probes the report queue with a read-only {@code GetQueueUrl}, verifies its FIFO contract
         * with a read-only {@code GetQueueAttributes}, and reports the outcome.
         *
         * <p>The resolved URL is used only as the input the attribute call requires and is never
         * logged, published or retained, because it embeds the AWS account id. No message is sent,
         * received or deleted at any point. Both calls run inside one budget, and an abandoned call is
         * always cancelled.
         *
         * @return {@code UP} with the logical queue name, the verified FIFO-contract token and elapsed
         *         milliseconds when the queue resolved and both attributes hold, otherwise
         *         {@code DOWN} with a symbolic reason and no diagnostic payload; never {@code null}
         *         and never thrown out of
         */
        @Override
        public Health health() {
            long startedAtNanos = System.nanoTime();

            if (this.queueName.isEmpty()) {
                return misconfigured(PROPERTY_REPORT_QUEUE, REASON_NOT_CONFIGURED, startedAtNanos);
            }
            if (isUnsafeResourceName(this.queueName)) {
                return misconfigured(PROPERTY_REPORT_QUEUE, REASON_INVALID_NAME, startedAtNanos);
            }
            if (!this.queueLogicalName.isEmpty() && isUnsafeResourceName(this.queueLogicalName)) {
                // Validated separately from the physical name because this is the value that would
                // be published in the health body, and an ARN or URL here would leak an account id
                // even though the probe itself would have succeeded.
                return misconfigured(PROPERTY_REPORT_QUEUE_LOGICAL_NAME, REASON_INVALID_NAME,
                        startedAtNanos);
            }

            // Prefer the logical name for publication: it is a literal in application.yml rather
            // than an environment-derived value, so it is provably account-id free. The physical
            // name stands in only when the logical one is absent, and it has already been validated
            // as a bare resource name above.
            String publishedName =
                    this.queueLogicalName.isEmpty() ? this.queueName : this.queueLogicalName;

            // Held outside the try so that every abandonment path can cancel whichever call is still
            // in flight. Cancelling an already-completed future is a no-op, so both are cancelled
            // unconditionally rather than tracked with a completion flag.
            CompletableFuture<GetQueueUrlResponse> pendingUrl = null;
            CompletableFuture<GetQueueAttributesResponse> pendingAttributes = null;

            // Declared out here for the same reason as the futures: the response itself is scoped to the
            // try, but the observed deduplication value is published on the UP path below. Finding H-08.
            String observedDeduplication;
            try {
                long remainingMillis = remainingBudgetMillis(startedAtNanos, SQS_PROBE_BUDGET_MILLIS);
                if (!hasCallBudget(remainingMillis)) {
                    return unreachable(publishedName, REASON_TIMEOUT, startedAtNanos, null);
                }

                // Read-only. The resolved URL is consumed in-process only, as the required input to
                // GetQueueAttributes below: SQS identifies a queue by URL for every attribute call.
                // It is never logged, never published in a detail and never retained, because
                // queueUrl() embeds the AWS account id.
                pendingUrl = this.sqsAsyncClient.getQueueUrl(GetQueueUrlRequest.builder()
                        .queueName(this.queueName)
                        .overrideConfiguration(callDeadline(remainingMillis))
                        .build());
                String queueUrl = pendingUrl.get(remainingMillis, TimeUnit.MILLISECONDS).queueUrl();

                remainingMillis = remainingBudgetMillis(startedAtNanos, SQS_PROBE_BUDGET_MILLIS);
                if (!hasCallBudget(remainingMillis)) {
                    return unreachable(publishedName, REASON_TIMEOUT, startedAtNanos, null);
                }

                // Read-only, and the only two attributes that are read: an unqualified attribute
                // request would return the queue ARN and every policy document with it, which is
                // precisely the payload this probe must not obtain.
                pendingAttributes =
                        this.sqsAsyncClient.getQueueAttributes(GetQueueAttributesRequest.builder()
                                .queueUrl(queueUrl)
                                .attributeNames(REQUESTED_QUEUE_ATTRIBUTES)
                                .overrideConfiguration(callDeadline(remainingMillis))
                                .build());
                GetQueueAttributesResponse attributes =
                        pendingAttributes.get(remainingMillis, TimeUnit.MILLISECONDS);

                QueueAttributeName unsatisfied = firstUnsatisfiedAttribute(attributes);
                if (unsatisfied != null) {
                    return attributeMismatch(publishedName, unsatisfied.toString(), startedAtNanos);
                }

                // Omission is how the service renders "off", so an absent attribute is normalised to
                // false rather than published as null.
                String reported =
                        attributes.attributes().get(QueueAttributeName.CONTENT_BASED_DEDUPLICATION);
                observedDeduplication = reported == null ? "false" : reported.trim();
            } catch (TimeoutException deadlineExceeded) {
                cancelQuietly(pendingUrl);
                cancelQuietly(pendingAttributes);
                return unreachable(publishedName, REASON_TIMEOUT, startedAtNanos, deadlineExceeded);
            } catch (InterruptedException interrupted) {
                cancelQuietly(pendingUrl);
                cancelQuietly(pendingAttributes);
                // Restore the flag before returning so the interruption is not lost to the caller;
                // a health probe must report it, not absorb it.
                Thread.currentThread().interrupt();
                return unreachable(publishedName, REASON_INTERRUPTED, startedAtNanos, interrupted);
            } catch (ExecutionException completedExceptionally) {
                return unreachable(publishedName, reasonFor(completedExceptionally.getCause()),
                        startedAtNanos, completedExceptionally);
            } catch (RuntimeException unexpected) {
                cancelQuietly(pendingUrl);
                cancelQuietly(pendingAttributes);
                return unreachable(publishedName, REASON_ERROR, startedAtNanos, unexpected);
            }

            return Health.up()
                    .withDetail(DETAIL_COMPONENT, SQS_HEALTH_COMPONENT_NAME)
                    .withDetail(DETAIL_QUEUE, publishedName)
                    .withDetail(DETAIL_FIFO_CONTRACT, FIFO_CONTRACT_VERIFIED)
                    .withDetail(DETAIL_CONTENT_DEDUPLICATION, observedDeduplication)
                    .withDetail(DETAIL_ELAPSED_MILLIS, elapsedMillis(startedAtNanos))
                    .build();
        }

        /**
         * Returns the first required queue attribute whose value does not satisfy the FIFO contract.
         *
         * <p>Walked in {@link HealthIndicators#REQUESTED_QUEUE_ATTRIBUTES} order so the reported
         * attribute is deterministic rather than dependent on map iteration. An attribute that
         * {@link HealthIndicators#REQUIRED_QUEUE_ATTRIBUTE_VALUES} does not name is read but not
         * gating and is skipped here; finding H-08 records why {@code ContentBasedDeduplication} is in
         * that position. An attribute the service did not return is treated exactly as one returned
         * {@code false}, which is what omission means, so an absent {@code FifoQueue} is a fault.
         * Comparison is case-insensitive because the value is a service-rendered boolean and its
         * casing is not part of any contract this project owns.
         *
         * @param attributes the attribute response, never {@code null}
         * @return the first unsatisfied attribute, or {@code null} when both are satisfied
         */
        private static QueueAttributeName firstUnsatisfiedAttribute(
                GetQueueAttributesResponse attributes) {
            Map<QueueAttributeName, String> values = attributes.attributes();
            for (QueueAttributeName requested : REQUESTED_QUEUE_ATTRIBUTES) {
                String gatingValue = REQUIRED_QUEUE_ATTRIBUTE_VALUES.get(requested);
                if (gatingValue == null) {
                    // Read and published, but not gating: see REQUIRED_QUEUE_ATTRIBUTE_VALUES for why
                    // ContentBasedDeduplication cannot legitimately fail readiness.
                    continue;
                }
                String value = values.get(requested);
                String observed = value == null ? "false" : value.trim();
                if (!gatingValue.equalsIgnoreCase(observed)) {
                    return requested;
                }
            }
            return null;
        }

        /**
         * Classifies the cause an {@link ExecutionException} wrapped into a symbolic reason.
         *
         * <p>An asynchronous SDK call reports failure through the future, so the meaningful type is
         * the cause rather than the wrapper. An absent queue is separated from a transport failure
         * because the remedies differ: run the provisioning script, versus start the emulator.
         *
         * @param cause the wrapped cause, possibly {@code null} if the future failed without one
         * @return the symbolic reason from the closed vocabulary
         */
        private static String reasonFor(Throwable cause) {
            if (cause instanceof QueueDoesNotExistException) {
                return REASON_MISSING;
            }
            if (cause instanceof SdkException) {
                return REASON_UNREACHABLE;
            }
            return REASON_ERROR;
        }

        /**
         * Builds the {@code DOWN} result for a configuration fault, naming the offending property
         * key and never its value.
         *
         * @param propertyKey    the configuration key at fault
         * @param reason         {@link HealthIndicators#REASON_NOT_CONFIGURED} or
         *                       {@link HealthIndicators#REASON_INVALID_NAME}
         * @param startedAtNanos the probe start reading
         * @return the curated {@code DOWN} result
         */
        private static Health misconfigured(String propertyKey, String reason, long startedAtNanos) {
            LOG.warn("SQS readiness probe refused: property={} reason={}", propertyKey, reason);
            return Health.down()
                    .withDetail(DETAIL_COMPONENT, SQS_HEALTH_COMPONENT_NAME)
                    .withDetail(DETAIL_PROPERTY, propertyKey)
                    .withDetail(DETAIL_REASON, reason)
                    .withDetail(DETAIL_ELAPSED_MILLIS, elapsedMillis(startedAtNanos))
                    .build();
        }

        /**
         * Builds the {@code DOWN} result for a queue that exists but does not satisfy a required
         * attribute.
         *
         * <p>Logged without a throwable because there is none: the service answered successfully and
         * reported an attribute value that does not meet the contract.
         *
         * @param publishedName  the account-id-free queue name safe to publish
         * @param attribute      the symbolic attribute name, an SDK enum rendering
         * @param startedAtNanos the probe start reading
         * @return the curated {@code DOWN} result
         */
        private static Health attributeMismatch(String publishedName, String attribute,
                long startedAtNanos) {
            LOG.warn("SQS readiness probe refused: queue={} attribute={} reason={}", publishedName,
                    attribute, REASON_ATTRIBUTE_MISMATCH);
            return Health.down()
                    .withDetail(DETAIL_COMPONENT, SQS_HEALTH_COMPONENT_NAME)
                    .withDetail(DETAIL_QUEUE, publishedName)
                    .withDetail(DETAIL_ATTRIBUTE, attribute)
                    .withDetail(DETAIL_REASON, REASON_ATTRIBUTE_MISMATCH)
                    .withDetail(DETAIL_ELAPSED_MILLIS, elapsedMillis(startedAtNanos))
                    .build();
        }

        /**
         * Builds the {@code DOWN} result for a failed remote call.
         *
         * <p>Same classification-not-rendering contract as {@link S3BucketHealthIndicator}: the
         * throwable is never handed to the logger, and both channels receive the symbolic reason plus
         * - on the log only - the curated descriptor from
         * {@link HealthIndicators#describeFailure(Throwable)}. For an asynchronous call the wrapper is
         * an {@link ExecutionException}, so the descriptor unwraps to the cause, which is where the
         * SDK type actually is.
         *
         * @param publishedName  the account-id-free queue name safe to publish
         * @param reason         the symbolic reason from the closed vocabulary
         * @param startedAtNanos the probe start reading
         * @param failure        the cause to classify, or {@code null} when the budget was exhausted
         *                       before the call could be issued
         * @return the curated {@code DOWN} result
         */
        private static Health unreachable(String publishedName, String reason, long startedAtNanos,
                Throwable failure) {
            LOG.warn("SQS readiness probe failed: queue={} reason={} exception={}", publishedName,
                    reason, describeFailure(failure));
            return Health.down()
                    .withDetail(DETAIL_COMPONENT, SQS_HEALTH_COMPONENT_NAME)
                    .withDetail(DETAIL_QUEUE, publishedName)
                    .withDetail(DETAIL_REASON, reason)
                    .withDetail(DETAIL_ELAPSED_MILLIS, elapsedMillis(startedAtNanos))
                    .build();
        }
    }

    /**
     * Reports whether the operator notification topic that {@code app/cbl/CORPT00C.cbl} and the report
     * path depend on is actually provisioned.
     *
     * <p>Nested and private for the same reasons as the other three indicators: it holds no state beyond
     * its injected client and one bound name, it is reachable only through
     * {@link HealthIndicators#snsHealthIndicator()}, and keeping it package-private-invisible means no
     * caller can construct one with an endpoint or a credential of its own choosing.
     *
     * <p>The walk mirrors {@code com.cardemo.config.AwsConfig.ProvisionedTopicArnResolver} so that
     * readiness and the publish path reach the same verdict, with one deliberate difference: every
     * request here carries a deadline drawn from the contributor's remaining budget, because a readiness
     * probe may not block for the shared client's publish-sized timeouts.
     */
    private static final class SnsTopicHealthIndicator implements HealthIndicator {

        /** Structured logger, named for the nested indicator rather than the enclosing class. */
        private static final Logger LOG = LoggerFactory.getLogger(SnsTopicHealthIndicator.class);

        /** The client each probe issues its read-only listing calls through. */
        private final SnsClient snsClient;

        /**
         * The bare notification topic name. Bare rather than an ARN, which is both what the publish path
         * configures and what makes the value publishable in a health detail.
         */
        private final String topicName;

        /**
         * Binds the probe to its client and to the topic name it reports against.
         *
         * @param snsClient the client the provisioned-topic listing is read through
         * @param topicName the bare topic name, already normalised
         */
        private SnsTopicHealthIndicator(SnsClient snsClient, String topicName) {
            this.snsClient = snsClient;
            this.topicName = topicName;
        }

        /**
         * Probes the notification topic with a read-only, deadline-bounded {@code ListTopics} walk and
         * reports the outcome.
         *
         * <p>Configuration is validated before any request is issued, so an unset or ARN-shaped name is
         * a deterministic {@code DOWN} rather than a request that could not have succeeded. Listed
         * identifiers are compared in-process and discarded: none is published or logged, because each
         * embeds the AWS account segment. Nothing is published to the topic at any point.
         *
         * @return {@code UP} with the bare topic name, the number of listing pages examined and elapsed
         *         milliseconds when the topic is among the provisioned topics, otherwise {@code DOWN}
         *         with a symbolic reason and no diagnostic payload; never {@code null} and never thrown
         *         out of
         */
        @Override
        public Health health() {
            long startedAtNanos = System.nanoTime();

            if (this.topicName.isEmpty()) {
                return misconfigured(PROPERTY_NOTIFICATION_TOPIC, REASON_NOT_CONFIGURED, startedAtNanos);
            }
            if (isUnsafeResourceName(this.topicName)) {
                // An ARN or a URL in this property is a configuration mistake, not a usable name: the
                // publish path resolves a bare name by listing, and an ARN-shaped value here would also
                // put an account identifier into a health detail. Refused without echoing the value.
                return misconfigured(PROPERTY_NOTIFICATION_TOPIC, REASON_INVALID_NAME, startedAtNanos);
            }

            int pagesExamined = 0;
            String nextToken = null;
            for (int page = 0; page < TOPIC_PAGE_LIMIT_COUNT; page++) {
                long remainingMillis = remainingBudgetMillis(startedAtNanos, SNS_PROBE_BUDGET_MILLIS);
                if (!hasCallBudget(remainingMillis)) {
                    // Report without calling, for the same reason the other probes do: the SDK rejects a
                    // non-positive deadline, and a call issued with a few milliseconds left could only
                    // expire in flight. TIMEOUT rather than MISSING, because the walk was cut short
                    // rather than completed - the topic may well exist on a page never reached.
                    return unreachable(REASON_TIMEOUT, pagesExamined, startedAtNanos, null);
                }

                ListTopicsResponse response;
                try {
                    // Read-only, and the only operation issued. The response's identifiers are consumed
                    // in-process below and never retained, published or logged.
                    response = this.snsClient.listTopics(ListTopicsRequest.builder()
                            .nextToken(nextToken)
                            .overrideConfiguration(callDeadline(remainingMillis))
                            .build());
                } catch (ApiCallTimeoutException | ApiCallAttemptTimeoutException deadlineExceeded) {
                    return unreachable(REASON_TIMEOUT, pagesExamined, startedAtNanos, deadlineExceeded);
                } catch (SdkException sdkFailure) {
                    return unreachable(REASON_UNREACHABLE, pagesExamined, startedAtNanos, sdkFailure);
                } catch (RuntimeException unexpected) {
                    return unreachable(REASON_ERROR, pagesExamined, startedAtNanos, unexpected);
                }
                pagesExamined++;

                for (Topic listed : response.topics()) {
                    if (this.topicName.equals(bareName(listed.topicArn()))) {
                        return Health.up()
                                .withDetail(DETAIL_COMPONENT, SNS_HEALTH_COMPONENT_NAME)
                                .withDetail(DETAIL_TOPIC, this.topicName)
                                .withDetail(DETAIL_TOPIC_PAGES_EXAMINED, pagesExamined)
                                .withDetail(DETAIL_ELAPSED_MILLIS, elapsedMillis(startedAtNanos))
                                .build();
                    }
                }

                nextToken = response.nextToken();
                if (nextToken == null || nextToken.isBlank()) {
                    break;
                }
            }

            // The listing was walked to its end, or to the shared page ceiling, without the configured
            // name appearing. MISSING rather than UNREACHABLE: the service answered every call, so the
            // remedy is to provision the topic rather than to fix connectivity.
            return missing(pagesExamined, startedAtNanos);
        }

        /**
         * Reads the bare topic name out of a resolved identifier: everything after the last separator.
         *
         * <p>Identical to the reduction {@code ProvisionedTopicArnResolver} performs, and the reason both
         * exist is that the listing reports identifiers while configuration holds names. The result is
         * used only for comparison; the identifier it came from is discarded, because it carries the
         * account segment.
         *
         * @param topicArn the resolved identifier reported by the service; never {@code null}
         * @return the bare name
         */
        private static String bareName(String topicArn) {
            return topicArn.substring(topicArn.lastIndexOf(TOPIC_ARN_SEPARATOR) + 1);
        }

        /**
         * Builds the {@code DOWN} result for a configuration fault, naming the offending property key
         * and never its value.
         *
         * <p>Logged at {@code WARN} without a throwable, because there is none: a misconfiguration is a
         * state rather than an exception.
         *
         * @param propertyKey    the configuration key at fault
         * @param reason         {@link HealthIndicators#REASON_NOT_CONFIGURED} or
         *                       {@link HealthIndicators#REASON_INVALID_NAME}
         * @param startedAtNanos the probe start reading
         * @return the curated {@code DOWN} result
         */
        private static Health misconfigured(String propertyKey, String reason, long startedAtNanos) {
            LOG.warn("SNS readiness probe refused: property={} reason={}", propertyKey, reason);
            return Health.down()
                    .withDetail(DETAIL_COMPONENT, SNS_HEALTH_COMPONENT_NAME)
                    .withDetail(DETAIL_PROPERTY, propertyKey)
                    .withDetail(DETAIL_REASON, reason)
                    .withDetail(DETAIL_ELAPSED_MILLIS, elapsedMillis(startedAtNanos))
                    .build();
        }

        /**
         * Builds the {@code DOWN} result for a topic the service does not list.
         *
         * <p>Logged without a throwable because there is none: every call succeeded and the answer was
         * simply that no provisioned topic carries the configured name. The page count is published so
         * the reader can tell a complete search from a ceiling-limited one.
         *
         * @param pagesExamined  how many listing pages were examined
         * @param startedAtNanos the probe start reading
         * @return the curated {@code DOWN} result
         */
        private Health missing(int pagesExamined, long startedAtNanos) {
            LOG.warn("SNS readiness probe refused: topic={} pagesExamined={} reason={}", this.topicName,
                    pagesExamined, REASON_MISSING);
            return Health.down()
                    .withDetail(DETAIL_COMPONENT, SNS_HEALTH_COMPONENT_NAME)
                    .withDetail(DETAIL_TOPIC, this.topicName)
                    .withDetail(DETAIL_TOPIC_PAGES_EXAMINED, pagesExamined)
                    .withDetail(DETAIL_REASON, REASON_MISSING)
                    .withDetail(DETAIL_ELAPSED_MILLIS, elapsedMillis(startedAtNanos))
                    .build();
        }

        /**
         * Builds the {@code DOWN} result for a failed remote call.
         *
         * <p>Same classification-not-rendering contract as the other three indicators: the throwable is
         * never handed to the logger, and both channels receive the symbolic reason plus - on the log
         * only - the curated descriptor from {@link HealthIndicators#describeFailure(Throwable)}.
         *
         * @param reason         the symbolic reason from the closed vocabulary
         * @param pagesExamined  how many listing pages were examined before the failure
         * @param startedAtNanos the probe start reading
         * @param failure        the cause to classify, or {@code null} when the budget was exhausted
         *                       before the call could be issued
         * @return the curated {@code DOWN} result
         */
        private Health unreachable(String reason, int pagesExamined, long startedAtNanos,
                Throwable failure) {
            LOG.warn("SNS readiness probe failed: topic={} pagesExamined={} reason={} exception={}",
                    this.topicName, pagesExamined, reason, describeFailure(failure));
            return Health.down()
                    .withDetail(DETAIL_COMPONENT, SNS_HEALTH_COMPONENT_NAME)
                    .withDetail(DETAIL_TOPIC, this.topicName)
                    .withDetail(DETAIL_TOPIC_PAGES_EXAMINED, pagesExamined)
                    .withDetail(DETAIL_REASON, reason)
                    .withDetail(DETAIL_ELAPSED_MILLIS, elapsedMillis(startedAtNanos))
                    .build();
        }
    }
}
