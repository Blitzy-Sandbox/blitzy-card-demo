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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

/**
 * Supplies the object-storage and queue halves of the composite readiness probe that replaces the
 * legacy CICS file-availability jobs.
 *
 * <h2>What it does</h2>
 *
 * <p>This class contributes exactly two Spring Boot Actuator {@link HealthIndicator} beans - one for
 * Amazon S3 object storage and one for the SQS FIFO report queue - and nothing else. Together with
 * the framework's own auto-configured {@code db} contributor they form the three-way composite
 * readiness signal the migration requires: <em>database plus object storage plus queue</em>.
 *
 * <h2>Why it exists: new capability, not a translation</h2>
 *
 * <p>The legacy system has <strong>no instrumentation whatsoever</strong>. The entire telemetry
 * surface of 19,254 lines of COBOL is 322 {@code DISPLAY} statements across {@code app/cbl/**},
 * plus the four-character file-status renderer at {@code app/cbl/CBTRN02C.cbl:L714-L727}. A search
 * of the frozen corpus for {@code prometheus}, {@code micrometer}, {@code opentelemetry},
 * {@code healthcheck} or {@code actuator} matches nothing at all.
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
 * the auto-configured {@code db} contributor rather than by anything in this class. That is the
 * reason this class probes object storage and the queue only.
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
 * the bean name, so the two bean names below fix the two keys that
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
 *       <td>The three configured buckets, by {@code HeadBucket}</td>
 *     </tr>
 *     <tr>
 *       <td>{@code sqsHealthIndicator}</td>
 *       <td>{@code sqs}</td>
 *       <td>{@link #SQS_HEALTH_COMPONENT_NAME}</td>
 *       <td>The report queue, by {@code GetQueueUrl}</td>
 *     </tr>
 *   </tbody>
 * </table>
 *
 * <p>The keys are deliberately lowercase and hyphen-free so a YAML {@code include} list can name
 * them without quoting. <strong>Severity High:</strong> renaming either bean silently drops that
 * contributor from the readiness group, because
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
 * <p><strong>Liveness must never include an external dependency, and neither of these beans may be
 * added to it.</strong> Liveness answers only "is this JVM still able to serve?". A liveness probe
 * that failed because the object-storage emulator was momentarily unreachable would cause an
 * orchestrator to kill an otherwise healthy process, converting a recoverable dependency blip into
 * a restart loop. Readiness is the correct place for a dependency: it removes the instance from
 * rotation and puts it back when the dependency returns.
 *
 * <p><strong>Severity High:</strong> {@code Dockerfile:495-496} declares
 * {@code HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=4} issuing
 * {@code GET /actuator/health/readiness} and grepping the body for {@code "status":"UP"}. The group
 * name {@code readiness}, and therefore that path, is load bearing: renaming the group on either
 * side breaks container startup ordering and with it the integration sign-off gate. The 5 second
 * container timeout is also what fixes the probe deadline documented on
 * {@link #sqsHealthIndicator()}.
 *
 * <h2>No live-AWS fallback: a Blocker-severity invariant</h2>
 *
 * <p>All AWS interaction targets LocalStack, with zero live credentials anywhere in the repository.
 * This class upholds that structurally rather than by convention:
 *
 * <ul>
 *   <li>it <strong>constructs no client</strong> - both SDK clients are injected, and their
 *       construction is owned by {@code com.cardemo.config.AwsConfig} together with the Spring
 *       Cloud AWS auto-configuration;</li>
 *   <li>it names <strong>no endpoint</strong>, calls no endpoint-override API and contains no host,
 *       port or service-domain literal;</li>
 *   <li>it supplies <strong>no</strong> region, access key, secret key or credentials provider, and
 *       no default for any of them;</li>
 *   <li>it performs <strong>no retry against a different endpoint</strong>. If the emulator is
 *       down, the correct and only behaviour is to report {@code DOWN}.</li>
 * </ul>
 *
 * <p>The endpoint override itself exists only in the {@code local} and {@code test} profiles - the
 * base {@code application.yml} declares neither an override nor a credential - and the compose
 * stack reaches the emulator on a LocalStack edge address of the form
 * {@code http://localhost.localstack.cloud:4566}. That address appears in this Javadoc as prose and
 * nowhere in the code.
 *
 * <h2>Report, never throw; and no sensitive diagnostics</h2>
 *
 * <p>Neither indicator ever propagates an exception. Actuator would convert a thrown exception into
 * {@code DOWN} on its own, but the message it then surfaces is outside this class's control and can
 * carry an endpoint, a request id or a raw SDK payload. Each remote call is therefore wrapped, and
 * every failure is mapped to a status with a curated reason drawn from the closed vocabulary
 * {@link #REASON_NOT_CONFIGURED}, {@link #REASON_INVALID_NAME}, {@link #REASON_MISSING},
 * {@link #REASON_UNREACHABLE}, {@link #REASON_TIMEOUT}, {@link #REASON_INTERRUPTED} and
 * {@link #REASON_ERROR}.
 *
 * <p><strong>This is reporting, not swallowing.</strong> Rule 1 Clause B forbids swallowing an
 * exception and requires the root cause to be preserved, and both hold here by an explicit split of
 * responsibilities: the throwable is logged <em>exactly once</em>, at {@code WARN}, as the final
 * SLF4J argument so the full stack trace and cause chain reach the log; the health response carries
 * only the safe summary. The log is the diagnostic channel and the health body is the public
 * channel, and they deliberately do not carry the same information. Because
 * {@link CorrelationIdFilter} has already populated the diagnostic context for the actuator
 * request, that {@code WARN} is automatically correlated by {@code correlationId}, {@code traceId}
 * and {@code spanId} without this class touching {@link org.slf4j.MDC} itself.
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
 *       resolved queue URL embeds the AWS account id, so surfacing or logging it would be a
 *       <strong>High</strong>-severity disclosure. Only the account-id-free logical name
 *       {@code carddemo-report-jobs} reaches a detail.</li>
 * </ul>
 *
 * <p>The details that <em>are</em> emitted are a symbolic component name, a symbolic reason, an
 * elapsed-milliseconds figure, a probed-bucket count, a configuration <em>property key</em> (never
 * its value), and S3 bucket or SQS queue <em>names</em>. A bucket or queue name is a plain resource
 * identifier: AWS constrains it to a short unqualified token, so it can hold no account id, ARN,
 * credential or URL. That is nevertheless not taken on trust - see
 * {@link #REASON_INVALID_NAME}. Independently, {@code management.endpoint.health.show-details} is
 * {@code never} and {@code show-components} is {@code never}, so an anonymous caller receives a
 * status-only body; the curation above is the first line of defence and that setting the second.
 *
 * <h2>Deliberate non-responsibilities</h2>
 *
 * <p>Every item below is owned elsewhere, and duplicating it here would violate Rule 1 Clause C:
 *
 * <ul>
 *   <li><strong>the database probe.</strong> Spring Boot auto-configures a {@code db} contributor
 *       from the {@code DataSource}. A second one would duplicate the component, and hand-rolling
 *       it would mean issuing a query from a health probe - which must never select a business row
 *       or log a bind parameter. This class provides no {@code DataSource} or JDBC health bean of
 *       any kind;</li>
 *   <li><strong>health-group composition and endpoint exposure</strong> - {@code application.yml};</li>
 *   <li><strong>AWS client construction</strong> - {@code com.cardemo.config.AwsConfig};</li>
 *   <li><strong>tracing and metrics registration</strong>, and the wiring of the three
 *       observability classes - {@code com.cardemo.config.ObservabilityConfig};</li>
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
 * </ul>
 *
 * <p>Each is bound with an <em>empty</em> placeholder default. That default is not a fallback for a
 * missing environment variable: {@code application.yml} declares all five keys, and the first four
 * indirect to a variable with no default of their own, so an absent variable still fails
 * placeholder resolution at startup exactly as the base profile intends. The empty default covers
 * only the case where the property key is absent from the environment altogether - a pared-down or
 * unit-test context - and in that case the probe reports a deterministic {@code DOWN} carrying
 * {@link #REASON_NOT_CONFIGURED} and the offending property key rather than throwing a
 * {@link NullPointerException}.
 *
 * <h2>Verified divergences from the migration plan</h2>
 *
 * <p>Two details of the plan were checked against the authored artefacts and did not match. Both are
 * recorded here because silently following the plan would have produced defects that no compiler and
 * no test would catch.
 *
 * <ul>
 *   <li><strong>Severity Medium</strong> - the bucket property keys are
 *       {@code carddemo.aws.s3.batch-input-bucket} and {@code carddemo.aws.s3.batch-output-bucket},
 *       indirecting to {@code CARDDEMO_S3_BATCH_INPUT_BUCKET} and
 *       {@code CARDDEMO_S3_BATCH_OUTPUT_BUCKET}. The shorter {@code CARDDEMO_S3_INPUT_BUCKET} and
 *       {@code CARDDEMO_S3_OUTPUT_BUCKET} spellings do not exist in {@code application.yml} or
 *       {@code .env.example}. Remediation, applied: bind the keys the resources actually publish.
 *       Binding the shorter spellings would have resolved to the empty default and pinned readiness
 *       to a permanent {@link #REASON_NOT_CONFIGURED} {@code DOWN}.</li>
 *   <li><strong>Severity Low</strong> - the readiness {@code HEALTHCHECK} is declared at
 *       {@code Dockerfile:495-496}, not in {@code docker-compose.yml}, which defines no application
 *       service at all - only PostgreSQL, LocalStack, Jaeger, Prometheus and Grafana, the first two
 *       carrying healthchecks of their own. The binding described above is therefore real, but a
 *       reader must look in the {@code Dockerfile} to find it.</li>
 * </ul>
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
 *   <li><strong>{@link #REASON_TIMEOUT} on SQS.</strong> The emulator accepted the connection but
 *       did not answer within the probe deadline. Check emulator load and host contention.</li>
 *   <li><strong>{@code /actuator/health/readiness} returns 404.</strong> The readiness group was
 *       removed or renamed, or {@code probes.enabled} was turned off. See the container binding
 *       above.</li>
 *   <li><strong>The context fails to start naming an unknown health contributor.</strong> The
 *       readiness group lists {@code s3} or {@code sqs} while this class is absent from the
 *       component scan. Beans and group membership must land in the same change.</li>
 * </ul>
 *
 * <h2>How to build and test</h2>
 *
 * <p>{@code ./mvnw -B -ntp clean compile} compiles this class under Java 25 with
 * {@code -Xlint:all -Werror}. {@code ./mvnw -B -ntp test} runs the unit suite. Both probes are unit
 * testable without a network or a container by constructing this class with mocked SDK clients:
 * assert {@code UP} on success, {@code DOWN} on a thrown SDK exception, {@code DOWN} on an
 * unconfigured name, and that no emitted detail value carries an account id, URL, credential or
 * stack trace. Those tests belong under {@code src/test/java/com/cardemo/unit/**}, and the
 * container-backed variants under {@code src/test/java/com/cardemo/integration/aws/**}; neither
 * belongs in this file.
 *
 * <h2>Preserved legacy artefacts, cited and never repaired</h2>
 *
 * <ul>
 *   <li><strong>Severity Low.</strong> {@code app/jcl/OPENFIL.jcl:L1} reads
 *       {@code //OEPNFIL JOB 'Open files in CICS'} - the job name transposes the E and the P while
 *       the member is {@code OPENFIL.jcl}. {@code app/jcl/CLOSEFIL.jcl:L1} is correct. The
 *       {@code app/} tree is frozen byte-for-byte and is simultaneously the parity oracle, the
 *       field-contract source and the commit-keyed traceability anchor, so the typo is recorded
 *       here and never corrected there. No remediation is proposed.</li>
 *   <li>{@code com.cardemo.service.shared.FileStatusMapper} emits the literal
 *       {@code FILE STATUS IS: NNNN} immediately followed by a four-character status, from
 *       {@code app/cbl/CBTRN02C.cbl:L721} and {@code :L725}. COBOL {@code DISPLAY} concatenates a
 *       literal and an identifier with no separator and the literal already contains the
 *       placeholder text, so status {@code '23'} emits {@code FILE STATUS IS: NNNN0023}. The stray
 *       {@code NNNN} is a preserved legacy quirk. Nothing in this class reformats, wraps, escapes,
 *       re-renders, truncates or masks that literal; this class emits no file status at all.</li>
 * </ul>
 *
 * <p>The opposite posture is worth naming once, for the reader who wonders why a probe reports
 * rather than aborts. The legacy batch response to an unexpected file status is termination, not
 * degradation: {@code app/cbl/CBTRN02C.cbl:L708} displays {@code 'ABENDING PROGRAM'},
 * {@code :L710} moves {@code 999} into the abend code and {@code :L711} calls {@code 'CEE3ABD'},
 * yielding abend code 999 with process return code 12. A health probe is deliberately the
 * inverse - it degrades a signal so an orchestrator can act, and terminates nothing.
 *
 * <h2>Not available</h2>
 *
 * <p>Rule 1 Clause F requires that missing information be stated rather than assumed. A live
 * {@code UP} reading from {@code /actuator/health/readiness} with all three components present
 * <strong>is not asserted here</strong>: the integration sign-off gate needs the whole compose
 * stack up, the three Flyway migrations applied and the application booted, and the application
 * entry point plus several collaborating beans are authored separately in this same single-phase
 * migration. What is needed to demonstrate it: a container runtime with an accessible socket, the
 * compose stack up, and a complete bean graph. Until then the evidence for this class is
 * compilation under the zero-warning gate and unit assertions over mocked clients.
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

    // =============================================================================================
    // Published name registry.
    //
    // These are public because they are a cross-file contract rather than an implementation detail:
    // application.yml names the two component keys in its readiness group, and a test asserting the
    // health contract should cite a symbol instead of retyping a literal that must not drift. Every
    // constant is a final reference to an immutable String, so the class still holds no static
    // mutable state.
    // =============================================================================================

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
     * Bean name of the SQS indicator, and therefore the source of the {@code sqs} health component
     * key. Actuator strips the {@code HealthIndicator} suffix to derive the key.
     */
    public static final String SQS_HEALTH_INDICATOR_BEAN_NAME = "sqsHealthIndicator";

    /**
     * Health component key of the SQS indicator, as it appears in a health response and in
     * {@code management.endpoint.health.group.readiness.include}.
     */
    public static final String SQS_HEALTH_COMPONENT_NAME = "sqs";

    // =============================================================================================
    // Detail keys. The complete, closed set of keys either indicator can place in a health body.
    // =============================================================================================

    /** Detail key carrying the symbolic component name, {@code s3} or {@code sqs}. */
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
     * Detail key carrying a symbolic failure reason from the closed vocabulary of
     * {@link #REASON_NOT_CONFIGURED}, {@link #REASON_INVALID_NAME}, {@link #REASON_MISSING},
     * {@link #REASON_UNREACHABLE}, {@link #REASON_TIMEOUT}, {@link #REASON_INTERRUPTED} and
     * {@link #REASON_ERROR}. Present only on a failure.
     */
    public static final String DETAIL_REASON = "reason";

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

    // =============================================================================================
    // Closed reason vocabulary. Public because these values are observable output, so a test should
    // assert against a symbol. Every value is a short symbolic token that can carry no diagnostic
    // payload by construction.
    // =============================================================================================

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

    /** The call did not complete within the probe deadline. */
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

    // =============================================================================================
    // Property keys, held as constants so the value bound and the key reported on a misconfiguration
    // can never drift apart.
    // =============================================================================================

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
     * Upper bound on how long the asynchronous SQS probe waits, in milliseconds.
     *
     * <p>Derived from evidence rather than chosen: {@code Dockerfile:495} declares
     * {@code --timeout=5s} on the readiness {@code HEALTHCHECK}, and that 5 second budget must also
     * cover the {@code db} contributor, the S3 probe and the HTTP round trip. Two seconds leaves
     * usable headroom while still bounding the wait far below the container deadline. A hung probe
     * is worse than a failing one, because it consumes the whole budget and yields no signal.
     *
     * <p>This bound is defence in depth layered on top of the SDK's own timeouts, not a replacement
     * for them: the injected client's configured API-call and HTTP timeouts apply first.
     */
    private static final long SQS_PROBE_TIMEOUT_MILLIS = 2_000L;

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
     * @throws NullPointerException if either injected client is {@code null}, which can only happen
     *                              in a hand-built context and is a programming error rather than a
     *                              runtime condition, so it is signalled at construction instead of
     *                              being deferred into a probe
     */
    public HealthIndicators(
            S3Client s3Client,
            SqsAsyncClient sqsAsyncClient,
            @Value("${" + PROPERTY_BATCH_INPUT_BUCKET + ":}") String batchInputBucket,
            @Value("${" + PROPERTY_BATCH_OUTPUT_BUCKET + ":}") String batchOutputBucket,
            @Value("${" + PROPERTY_STATEMENTS_BUCKET + ":}") String statementsBucket,
            @Value("${" + PROPERTY_REPORT_QUEUE + ":}") String reportQueue,
            @Value("${" + PROPERTY_REPORT_QUEUE_LOGICAL_NAME + ":}") String reportQueueLogicalName) {

        this.s3Client = Objects.requireNonNull(s3Client, "s3Client must not be null");
        this.sqsAsyncClient = Objects.requireNonNull(sqsAsyncClient, "sqsAsyncClient must not be null");

        Map<String, String> buckets = new LinkedHashMap<>();
        buckets.put(PROPERTY_BATCH_INPUT_BUCKET, normalise(batchInputBucket));
        buckets.put(PROPERTY_BATCH_OUTPUT_BUCKET, normalise(batchOutputBucket));
        buckets.put(PROPERTY_STATEMENTS_BUCKET, normalise(statementsBucket));
        this.s3Buckets = Collections.unmodifiableMap(buckets);

        this.reportQueueName = normalise(reportQueue);
        this.reportQueueLogicalName = normalise(reportQueueLogicalName);
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
     * declaration order, stopping at the first failure. {@code HeadBucket} is metadata-only: it
     * transfers no object body and enumerates no keys. A {@code PutObject}, a {@code DeleteObject}
     * and a full {@code ListObjectsV2} enumeration are each excluded deliberately - the first two
     * because a probe must not mutate, the third because Rule 1 Clause A forbids the obvious
     * inefficiency of listing a bucket to learn only that it answers.
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
     * <p><strong>Boundedness.</strong> {@code HeadBucket} is synchronous, so the deadline is the
     * injected client's own API-call and HTTP timeouts. No executor is introduced to impose a
     * second deadline: this class would then own a thread pool and its lifecycle, which is more
     * machinery and more leak surface than the guarantee is worth for a metadata call. The
     * asynchronous SQS probe, whose API already returns a future, does carry an explicit bound - see
     * {@link #sqsHealthIndicator()}.
     *
     * <p><strong>Side effects.</strong> None beyond the read-only remote calls and, on failure, a
     * single {@code WARN} log carrying the root cause. Nothing is written, cached or mutated.
     *
     * <p><strong>Failure modes.</strong> An empty configured name yields
     * {@link #REASON_NOT_CONFIGURED}; a name that is an ARN or URL rather than a bare bucket name
     * yields {@link #REASON_INVALID_NAME} without echoing the value; an absent bucket yields
     * {@link #REASON_MISSING}; an unreachable endpoint or a service error yields
     * {@link #REASON_UNREACHABLE}; anything else yields {@link #REASON_ERROR}. Every one of them is
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
     * Registers the queue readiness contributor under the health component key
     * {@link #SQS_HEALTH_COMPONENT_NAME}.
     *
     * <p><strong>Purpose.</strong> Confirms that the SQS FIFO queue replacing
     * {@code DEFINE TDQUEUE(JOBS)} at {@code app/csd/CARDDEMO.CSD:L499-L505} is reachable, so that
     * the report-submission path is not admitted before the bridge it publishes to exists.
     *
     * <p><strong>Operation.</strong> A single {@code GetQueueUrl} request, which resolves a queue
     * name to its URL and is read-only. <strong>The response is discarded.</strong> A resolved queue
     * URL embeds the AWS account id, so surfacing or logging it would be a {@code High}-severity
     * disclosure; only the reachability outcome is retained.
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
     * <p><strong>Boundedness.</strong> The future is awaited for at most
     * {@link #SQS_PROBE_TIMEOUT_MILLIS} milliseconds, a bound derived from the 5 second container
     * {@code HEALTHCHECK} timeout at {@code Dockerfile:495} and layered on top of the injected
     * client's own timeouts. A hung probe would consume the whole container budget and produce no
     * signal at all, which is strictly worse than a timely {@code DOWN}.
     *
     * <p><strong>Side effects.</strong> None beyond the read-only remote call and, on failure, a
     * single {@code WARN} log carrying the root cause. If the awaiting thread is interrupted the
     * interrupt flag is restored before returning, so the interruption is reported rather than
     * discarded.
     *
     * <p><strong>Failure modes.</strong> An empty queue name yields
     * {@link #REASON_NOT_CONFIGURED}; an ARN or URL in place of a name yields
     * {@link #REASON_INVALID_NAME} without echoing the value; an absent queue yields
     * {@link #REASON_MISSING}; an unreachable endpoint or service error yields
     * {@link #REASON_UNREACHABLE}; exceeding the deadline yields {@link #REASON_TIMEOUT}; an
     * interrupt yields {@link #REASON_INTERRUPTED}; anything else yields {@link #REASON_ERROR}. All
     * are returned as {@code DOWN} - the indicator never throws.
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

    // =============================================================================================
    // Shared private static helpers. Static so that neither the constructor nor a nested indicator
    // can invoke an overridable instance method, which keeps the class free of any `this` escape and
    // keeps every helper a pure function of its arguments.
    // =============================================================================================

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

        private static final Logger LOG = LoggerFactory.getLogger(S3BucketHealthIndicator.class);

        private final S3Client s3Client;

        private final Map<String, String> buckets;

        private S3BucketHealthIndicator(S3Client s3Client, Map<String, String> buckets) {
            this.s3Client = s3Client;
            this.buckets = buckets;
        }

        /**
         * Probes every configured bucket with a metadata-only {@code HeadBucket} and reports the
         * outcome.
         *
         * <p>Iterates in declaration order and returns at the first failure, so a broken substrate
         * costs one round trip rather than three. Configuration is validated before any request is
         * issued, which is what makes an unset or malformed name a deterministic {@code DOWN}
         * instead of a request that could not have succeeded.
         *
         * @return {@code UP} with the probed-bucket count and elapsed milliseconds when every
         *         bucket answered, otherwise {@code DOWN} with a symbolic reason and no diagnostic
         *         payload; never {@code null} and never thrown out of
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

                bucketsProbed++;
                try {
                    this.s3Client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
                } catch (NoSuchBucketException absentBucket) {
                    return unreachable(bucketName, REASON_MISSING, bucketsProbed, startedAtNanos,
                            absentBucket);
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

            return Health.up()
                    .withDetail(DETAIL_COMPONENT, S3_HEALTH_COMPONENT_NAME)
                    .withDetail(DETAIL_BUCKETS_PROBED, bucketsProbed)
                    .withDetail(DETAIL_ELAPSED_MILLIS, elapsedMillis(startedAtNanos))
                    .build();
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
         * Builds the {@code DOWN} result for a failed remote call.
         *
         * <p>This is the reporting half of the deliberate split that keeps Rule 1 Clause B
         * satisfied: the throwable is passed as the final SLF4J argument so the complete stack trace
         * and cause chain reach the log exactly once, while the returned details carry only a
         * symbolic reason and a bare bucket name. It is not a swallow - the root cause is preserved
         * in full, just not on the channel a health endpoint publishes. {@code Health.down(Throwable)}
         * is deliberately not used, because it would serialise the exception into the details map.
         *
         * @param bucketName      the bare bucket name whose probe failed
         * @param reason          the symbolic reason from the closed vocabulary
         * @param bucketsProbed   how many buckets had been probed, including this one
         * @param startedAtNanos  the probe start reading
         * @param failure         the root cause, logged once and never serialised into the response
         * @return the curated {@code DOWN} result
         */
        private static Health unreachable(String bucketName, String reason, int bucketsProbed,
                long startedAtNanos, Throwable failure) {
            LOG.warn("S3 readiness probe failed: bucket={} reason={}", bucketName, reason, failure);
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
     * Queue readiness probe, produced by {@link HealthIndicators#sqsHealthIndicator()}.
     *
     * <p>Nested and {@code private static final} for the same reasons as
     * {@link S3BucketHealthIndicator}: one top-level type per file, no captured enclosing instance,
     * no subclass and therefore no {@code this} escape, and immutability after construction.
     */
    private static final class SqsQueueHealthIndicator implements HealthIndicator {

        private static final Logger LOG = LoggerFactory.getLogger(SqsQueueHealthIndicator.class);

        private final SqsAsyncClient sqsAsyncClient;

        private final String queueName;

        private final String queueLogicalName;

        private SqsQueueHealthIndicator(SqsAsyncClient sqsAsyncClient, String queueName,
                String queueLogicalName) {
            this.sqsAsyncClient = sqsAsyncClient;
            this.queueName = queueName;
            this.queueLogicalName = queueLogicalName;
        }

        /**
         * Probes the report queue with a read-only {@code GetQueueUrl} and reports the outcome.
         *
         * <p>The resolved URL is discarded rather than inspected, because it embeds the AWS account
         * id. No message is sent, received or deleted at any point.
         *
         * @return {@code UP} with the logical queue name and elapsed milliseconds when the queue
         *         resolved, otherwise {@code DOWN} with a symbolic reason and no diagnostic payload;
         *         never {@code null} and never thrown out of
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

            try {
                // Read-only. The GetQueueUrlResponse is intentionally neither assigned nor
                // inspected: queueUrl() embeds the AWS account id, and reachability is the only
                // fact this probe needs.
                this.sqsAsyncClient
                        .getQueueUrl(GetQueueUrlRequest.builder().queueName(this.queueName).build())
                        .get(SQS_PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException deadlineExceeded) {
                return unreachable(publishedName, REASON_TIMEOUT, startedAtNanos, deadlineExceeded);
            } catch (InterruptedException interrupted) {
                // Restore the flag before returning so the interruption is not lost to the caller;
                // a health probe must report it, not absorb it.
                Thread.currentThread().interrupt();
                return unreachable(publishedName, REASON_INTERRUPTED, startedAtNanos, interrupted);
            } catch (ExecutionException completedExceptionally) {
                return unreachable(publishedName, reasonFor(completedExceptionally.getCause()),
                        startedAtNanos, completedExceptionally);
            } catch (RuntimeException unexpected) {
                return unreachable(publishedName, REASON_ERROR, startedAtNanos, unexpected);
            }

            return Health.up()
                    .withDetail(DETAIL_COMPONENT, SQS_HEALTH_COMPONENT_NAME)
                    .withDetail(DETAIL_QUEUE, publishedName)
                    .withDetail(DETAIL_ELAPSED_MILLIS, elapsedMillis(startedAtNanos))
                    .build();
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
         * Builds the {@code DOWN} result for a failed remote call.
         *
         * <p>Same deliberate split as {@link S3BucketHealthIndicator}: the throwable is logged once
         * at {@code WARN} as the final SLF4J argument so the root cause is preserved in full, and
         * the response carries only a symbolic reason plus the account-id-free queue name.
         *
         * @param publishedName  the account-id-free queue name safe to publish
         * @param reason         the symbolic reason from the closed vocabulary
         * @param startedAtNanos the probe start reading
         * @param failure        the root cause, logged once and never serialised into the response
         * @return the curated {@code DOWN} result
         */
        private static Health unreachable(String publishedName, String reason, long startedAtNanos,
                Throwable failure) {
            LOG.warn("SQS readiness probe failed: queue={} reason={}", publishedName, reason, failure);
            return Health.down()
                    .withDetail(DETAIL_COMPONENT, SQS_HEALTH_COMPONENT_NAME)
                    .withDetail(DETAIL_QUEUE, publishedName)
                    .withDetail(DETAIL_REASON, reason)
                    .withDetail(DETAIL_ELAPSED_MILLIS, elapsedMillis(startedAtNanos))
                    .build();
        }
    }
}
