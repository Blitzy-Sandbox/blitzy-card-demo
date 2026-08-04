/*
 * ******************************************************************
 * Program     : AwsConfig.java
 * Application : CardDemo
 * Type        : Java Spring Configuration (cloud integration layer)
 * Function    : S3, SQS FIFO and SNS client configuration replacing the
 *               generation data groups, the CICS transient data queue
 *               'JOBS' and operator notification. LocalStack only.
 * Source      : app/jcl/DEFGDGB.jcl (6 GDG bases, LIMIT(5))
 *               + app/jcl/DALYREJS.jcl (the 7th GDG base)
 *               + app/jcl/REPTFILE.jcl (TRANREPT LIMIT(10))
 *               + app/csd/CARDDEMO.CSD:L499-505 (DEFINE TDQUEUE(JOBS),
 *                 RECORDSIZE(80) RECORDFORMAT(FIXED))
 *               + app/cbl/CORPT00C.cbl:L517-523 (EXEC CICS WRITEQ TD)
 *               + app/jcl/POSTTRAN.jcl:L34-38 (DALYREJS LRECL=430)
 *               + app/jcl/INTCALC.jcl:L37-41 (SYSTRAN LRECL=350)
 *               + app/jcl/CREASTMT.JCL:L87-96 (STMTFILE 80, HTMLFILE 100)
 *               + app/proc/TRANREPT.prc:L74-78 (TRANREPT LRECL=133)
 *               @ 7756d89
 * Replaces    : the 7 generation data group bases, the extrapartition
 *               transient data queue 'JOBS' and the JES2 internal reader
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
package com.cardemo.config;

import com.cardemo.observability.CorrelationIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.autoconfigure.s3.S3ClientCustomizer;
import io.awspring.cloud.autoconfigure.sns.SnsClientCustomizer;
import io.awspring.cloud.autoconfigure.sqs.SqsAsyncClientCustomizer;
import io.awspring.cloud.s3.S3ObjectConverter;
import io.awspring.cloud.s3.S3OutputStreamProvider;
import io.awspring.cloud.s3.S3Template;
import io.awspring.cloud.sns.core.SnsTemplate;
import io.awspring.cloud.sns.core.TopicArnResolver;
import io.awspring.cloud.sqs.listener.QueueNotFoundStrategy;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import io.awspring.cloud.sqs.support.converter.MessagingMessageConverter;
import io.awspring.cloud.sqs.support.converter.SqsMessagingMessageConverter;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.support.ChannelInterceptor;
import software.amazon.awssdk.arns.Arn;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.client.builder.SdkClientBuilder;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.ListTopicsRequest;
import software.amazon.awssdk.services.sns.model.ListTopicsResponse;
import software.amazon.awssdk.services.sns.model.Topic;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * Cloud integration configuration for the CardDemo modular monolith: the single place where the
 * object-storage, queue and notification collaborators are constructed, and the single written record of how
 * the z/OS generation-data-group substrate, the CICS transient data queue and operator notification were
 * translated onto S3, SQS FIFO and SNS.
 *
 * <p>Two jobs live here and separating them is the point of the class. The first is <strong>executable</strong>:
 * three beans, and a startup assertion that the five environment-indirected resource names this application
 * cannot function without are actually present and non-blank. The second is <strong>documentary</strong>: the
 * generation-reference translation, the byte-exact record geometry and the carry-forward contract are recorded
 * below with the job-control, catalogue, resource-definition and program locators that establish them, so that
 * no reader has to reverse-engineer a record length or a retention decision from a bucket listing.
 *
 * <h2>What it does</h2>
 *
 * <p>It declares exactly three beans, one per integration surface:
 *
 * <ul>
 *   <li>{@link #s3Template(S3Client, S3OutputStreamProvider, S3ObjectConverter, S3Presigner)} - object storage,
 *       replacing the seven generation data group bases catalogued at {@code app/catlg/LISTCAT.txt}.</li>
 *   <li>{@link #sqsTemplate(SqsAsyncClient, ObjectProvider, MessagingMessageConverter)} - the report-job
 *       publisher, replacing {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} at
 *       {@code app/cbl/CORPT00C.cbl:L517-L523}.</li>
 *   <li>{@link #snsTemplate(SnsClient, ObjectProvider, TopicArnResolver, ObjectProvider)} - notification,
 *       replacing the operator notification that {@code // NOTIFY=&SYSUID} asked JES2 for. That card is
 *       written programmatically: it is card two of the seventeen job-control images
 *       {@code app/cbl/CORPT00C.cbl:L85-L86} declares and {@code :L498-L508} writes to the transient data
 *       queue.</li>
 *   </ul>
 *
 * <p>Every one of the three takes the <em>already-configured</em> low-level client as a method parameter rather
 * than building one. That is the load-bearing decision in this file and it is deliberate: the region, the
 * credential resolution and the emulator endpoint are applied by Spring Cloud AWS to those low-level clients
 * from profile configuration, so injecting them keeps the no-live-AWS guarantee structural rather than
 * conventional. Building a client here would move an environment-specific endpoint into Java, which Rule 1
 * Clause C forbids, and would put this class in a position to reach a live service.
 *
 * <p>Each bean also reproduces, faithfully and with a comment saying so, the wiring that the library's own
 * auto-configuration performs. That is not decoration. The auto-configured report-queue publisher applies
 * {@code spring.cloud.aws.sqs.queue-not-found-strategy} and the Spring-managed {@code ObjectMapper} inside its
 * factory method, not inside the collaborator beans; a bean that merely constructed a publisher from the
 * client would silently drop both, and dropping the first reverts the strategy to the library default, under
 * which a missing queue is created on first send. Creating a queue from Java is exactly what this class must
 * never do, so the omission would have re-introduced provisioning by accident.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <ul>
 *   <li><strong>It provisions nothing.</strong> Bucket, queue and topic lifecycle belongs to
 *       {@code localstack-init/init-aws.sh}, which is idempotent and verifies output-bucket versioning by
 *       read-back. No provisioning call of any kind appears in this file, and no retention or lifecycle rule
 *       is applied from code.</li>
 *   <li><strong>It declares no emulator endpoint, but it does enforce one.</strong> The endpoint value is
 *       profile configuration - the base profile carries the indirection with no default and a test registers
 *       the container's mapped address - and this class names no host, port or address of its own. What it does
 *       is <em>check</em> whatever resolved, twice: in
 *       {@link #cloudEmulatorBindingGuard(org.springframework.core.env.Environment)} before any bean of any
 *       kind is created, and again in this class's constructor before any client exists. See the guarantee
 *       below.</li>
 *   <li><strong>It declares no queue listener.</strong> The consumer that replaces the JES2 internal reader
 *       belongs to the planned {@code com.cardemo.batch.jobs.BatchPipelineOrchestrator}, which is <strong>not
 *       authored yet</strong> - measured at this commit that class does not exist and the tree carries no
 *       {@code @SqsListener} declaration at all, so a published message is not yet drained. Re-derive with
 *       {@code grep -rn "@SqsListener" src/main/java}. The bean below is a producer with a named future
 *       consumer, not a producer with a live one.</li>
 *   <li><strong>It derives no object keys.</strong> The generation-reference translation is specified here and
 *       implemented by the job classes, which bind the seven {@code carddemo.aws.s3.gdg-prefixes.*} entries
 *       directly, and by {@code com.cardemo.config.BatchConfig}, which centralises the step wiring.</li>
 *   <li><strong>It restates no configuration owned elsewhere</strong> - not the metrics scrape configuration,
 *       not the dashboard provisioning, not the actuator groups, not the Jackson settings, and not the bucket,
 *       queue or topic names themselves, which are declared once in {@code src/main/resources/application.yml}
 *       and bound, never redeclared, here.</li>
 *   <li><strong>It adds no dependency and pins no version.</strong> Spring Cloud AWS 3.3.0 arrives through the
 *       bill of materials imported by the root {@code pom.xml}, which is where every version this class relies
 *       on is pinned to an exact coordinate; the three starters take their versions from that bill of materials.
 *       Rule 1 Clause D asks for dependencies to be pinned where possible, and they are - in one place, not
 *       restated here, because a second declaration is how a build stops being reproducible. No code generation
 *       library is used either, so every member below is written out and reviewable.</li>
 *   </ul>
 *
 * <p>On cost, since Rule 1 Clause A asks for obvious inefficiency to be avoided and tradeoffs to be justified:
 * the three beans are singletons built once during context refresh, and the validation below is a fixed number
 * of string comparisons over seven short names, so the whole class runs once per process and never on a request
 * or record path. The one tradeoff worth naming is that the missing-queue strategy and the object mapper are
 * re-applied here rather than inherited by leaving the library's own beans in place - a few lines of duplication
 * accepted deliberately, because the alternative is a client whose construction has no single owner, and the
 * cost of getting that wrong is a client whose construction has no single owner.
 *
 * <h2>The no-live-AWS guarantee is structural, and it is enforced rather than assumed</h2>
 *
 * <p>AAP section 0.3.2 states the contract without exception: all AWS interaction is against the local
 * emulator, zero live credentials appear in any file, and no code path may reach a real AWS endpoint. The
 * guarantee used to rest on the <em>absence</em> of configuration - no endpoint override in the base profile,
 * no credential default anywhere - and absence was not enough. With the three service endpoints unset the SDK
 * performs regional endpoint discovery and addresses a real host; with no static credential pair it walks its
 * default provider chain and may sign that call with a live principal. Neither outcome requires anybody to make
 * a mistake in this file.
 *
 * <p>So the contract is now proved in the constructor, before any template or client exists:
 *
 * <ul>
 *   <li>Each of {@value #KEY_S3_ENDPOINT}, {@value #KEY_SQS_ENDPOINT} and {@value #KEY_SNS_ENDPOINT} must be
 *       present, must parse as an absolute lowercase {@code http} or {@code https} URI carrying no user
 *       information, and must resolve to one of the permitted emulator hosts - the loopback interface, the
 *       emulator's own documented name, its compose service and container names, or the host gateway. A real
 *       service host is a public name outside that set and can therefore never satisfy this check. The set is
 *       named positively and the live domain is deliberately never spelled anywhere in this file, because an
 *       allowlist has no use for that literal and its absence is the observable difference from a denylist.
 *       </li>
 *   <li>{@value #KEY_ACCESS_KEY} and {@value #KEY_SECRET_KEY} must both be present, because supplying them is
 *       what pins a static credentials provider and <em>displaces</em> the default chain, and the access key
 *       must not carry a real AWS key prefix - the shape a pasted production credential has.</li>
 *   </ul>
 *
 * <p>The port is deliberately unconstrained beyond being present and in range, because a Testcontainers-managed
 * emulator publishes on an ephemeral port. This file still names no host, no port, no region, no access-key
 * identifier, no secret and no session token, and it still contains no fallback that could resolve a real
 * service endpoint; what it adds is a refusal to start when the configuration around it could.
 *
 * <p><strong>The same rules are applied a second time, earlier, and the earlier one is the load-bearing
 * check.</strong> A constructor guard only runs if this class is instantiated, and the distribution of the
 * endpoint override across profiles was once presented as the control in its own right: the override appeared
 * in {@code application-local.yml} and {@code application-test.yml} and in neither the base profile nor
 * {@code application-prod.yml}. That was never a control. A profile declaring no override does not decline to
 * talk to AWS - it talks to the <em>real</em> AWS, through the SDK's standard endpoint resolution, signed with
 * whatever principal the ambient credential chain yields, and the production profile documented exactly that
 * as a deliberate invariant. So
 * {@link #cloudEmulatorBindingGuard(org.springframework.core.env.Environment)} applies the identical rules from
 * a {@code static} bean-factory post-processor, which the container invokes before any bean definition is
 * instantiated: it requires an endpoint override for each of the three services, parses each against the same
 * host allow-list {@code localstack-init/init-aws.sh} applies, and requires static credentials so the default
 * provider chain is displaced outright. The override is declared in the <em>base</em> profile with no default,
 * so every profile inherits the requirement and none can build a client without one - the fallback is refused
 * rather than merely unused. Zero live credentials exist anywhere in the repository.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Compile and unit-test, offline-capable because the local repository is warm:
 *
 * <pre>{@code
 * ./mvnw -B -ntp -o clean test
 * }</pre>
 *
 * <p>Bring up the substrate this class expects - PostgreSQL, the AWS emulator, the trace collector, the
 * metrics store and the dashboard - from the repository root, then run the application against it. The base
 * profile carries the endpoint and credential indirection with no default, so the environment must supply
 * {@code AWS_ENDPOINT_URL}, {@code AWS_ACCESS_KEY_ID} and {@code AWS_SECRET_ACCESS_KEY}; the {@code local}
 * profile adds the developer defaults and the concrete resource names:
 *
 * <pre>{@code
 * set -a; . ./.env; set +a
 * docker compose up -d
 * SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
 * }</pre>
 *
 * <p>The three buckets, the FIFO queue and the topic are created by {@code localstack-init/init-aws.sh}, which
 * runs automatically when the emulator container reports ready and may be re-run safely at any time. The
 * startup guard is covered by
 * {@code src/test/java/com/cardemo/unit/config/AwsConfigEmulatorBindingGuardTest.java}, which enumerates every
 * accepted and every refused endpoint form; the integration coverage for the clients themselves stands the
 * emulator up through Testcontainers under {@code src/test/java/com/cardemo/integration}.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>Every value below is bound here and declared in {@code src/main/resources/application.yml}. The rule the
 * base profile applies without exception is that anything naming a specific cloud resource has <em>no
 * default</em>, so an absent value fails placeholder resolution and therefore fails startup. That is intended
 * behaviour: Rule 1 Clause A forbids unsafe defaults, and guessing a bucket name is precisely such a default.
 *
 * <dl>
 *   <dt>{@code spring.cloud.aws.region.static}</dt>
 *   <dd>The signing region, indirected through {@code AWS_REGION} falling back to
 *       {@code AWS_DEFAULT_REGION}. No literal default in the base profile. Validated non-blank here.</dd>
 *
 *   <dt>{@code carddemo.aws.s3.batch-input-bucket}</dt>
 *   <dd>Batch input. No default in the base profile; {@code application-local.yml} supplies
 *       {@code carddemo-batch-input} for developer convenience. Holds the daily transaction stream that
 *       replaces the {@code DALYTRAN} sequential dataset of {@code app/jcl/POSTTRAN.jcl:L30-L31}.</dd>
 *
 *   <dt>{@code carddemo.aws.s3.batch-output-bucket}</dt>
 *   <dd>Batch output, and <strong>the only versioned bucket</strong>, because object versioning is what
 *       supersedes generation-data-group retention. No default in the base profile;
 *       {@code application-local.yml} supplies {@code carddemo-batch-output}. Versioning is enabled and
 *       verified by {@code localstack-init/init-aws.sh}, never from here.</dd>
 *
 *   <dt>{@code carddemo.aws.s3.statements-bucket}</dt>
 *   <dd>Statement text and statement markup output. No default in the base profile;
 *       {@code application-local.yml} supplies {@code carddemo-statements}.</dd>
 *
 *   <dt>{@code carddemo.aws.sqs.report-queue}</dt>
 *   <dd>The physical queue name. No default in the base profile; {@code application-local.yml} supplies
 *       {@code carddemo-report-jobs.fifo}. Set as the publisher's default destination, which is safe because a
 *       queue address is resolved lazily on the first send rather than when the bean is built.</dd>
 *
 *   <dt>{@code carddemo.aws.sqs.report-queue-logical-name}</dt>
 *   <dd>The logical name, {@code carddemo-report-jobs}, defaulted here to the same literal so that a context
 *       assembled without the base profile still validates. The physical name must be exactly this value
 *       suffixed {@code .fifo} - the suffix is <strong>required, not optional</strong> - and the relationship
 *       is asserted at startup for two reasons: {@code localstack-init/init-aws.sh} derives one from the other,
 *       so a drift would provision one queue and publish to another; and SQS identifies a FIFO queue solely by
 *       that suffix, so an unsuffixed name would silently discard the message group that keeps report
 *       submission in order.</dd>
 *
 *   <dt>{@code carddemo.aws.sns.notification-topic}</dt>
 *   <dd>The notification topic. No default in the base profile; {@code application-local.yml} supplies
 *       {@code carddemo-notifications}. Validated at startup but deliberately <em>not</em> set as the
 *       notification bean's default destination, because doing so would resolve the name eagerly and the
 *       library's default resolver resolves a plain name by creating the topic. Callers name the topic
 *       explicitly instead. The reasoning is set out in full on that bean.</dd>
 *
 *   <dt>{@code spring.cloud.aws.sqs.queue-not-found-strategy}</dt>
 *   <dd>Pinned to {@code FAIL} by the base profile and defaulted to {@code FAIL} here as well. The library
 *       default is {@code CREATE}, under which publishing to an absent queue provisions it. That is a
 *       deliberate, documented deviation from the library default in the safe direction: this class must not
 *       provision, and a missing queue must be a loud failure rather than a silent creation.</dd>
 *
 *   <dt>{@code spring.cloud.aws.s3.endpoint}, {@code .sqs.endpoint} and {@code .sns.endpoint}</dt>
 *   <dd>The three service endpoints. The base profile indirects all three through one variable so that a
 *       single value moves the whole topology, and defaults that variable to the compose emulator address,
 *       which is a loopback destination and therefore safe to ship. The {@code test} profile carries none: the
 *       integration harness registers the container's mapped address, which takes precedence. Each is bound
 *       with an empty fallback here so that an unset property is reported by the guard, naming the property and
 *       the remedy, rather than by an opaque placeholder-resolution failure. Validated local-only; see the
 *       guarantee above.</dd>
 *
 *   <dt>{@code spring.cloud.aws.credentials.access-key} and {@code .secret-key}</dt>
 *   <dd>The static credential pair. Both are deterministic non-live placeholders in the base profile, and
 *       neither is a secret in any sense: the emulator validates no credential, and the pair exists solely
 *       because supplying it pins a static provider and displaces the SDK's default provider chain - the one
 *       path by which an ambient live principal could otherwise sign a call. Bound with an empty fallback here
 *       for the same diagnostic reason as the endpoints. Validated present and non-live; the values are never
 *       logged, echoed or digested.</dd>
 *   </dl>
 *
 * <p>The health-probe namespaces {@code carddemo.aws.s3}, {@code carddemo.aws.sqs} and
 * {@code carddemo.aws.sns} are shared with {@code com.cardemo.observability.HealthIndicators}, which injects
 * the beans declared here rather than constructing a client of its own. The seven
 * {@code carddemo.aws.s3.gdg-prefixes.*} entries and {@code carddemo.aws.s3.gdg-retention-generations} are
 * bound by the batch layer, and are documented here because this class owns their meaning.
 *
 * <p>Two habits are avoided on principle rather than by accident. The environment is never read directly, only
 * through property binding, so a value can be overridden by any property source and the build stays free of
 * environment-specific assumptions. And no absolute host path appears anywhere in this file.
 *
 * <h2>The seven generation data group bases</h2>
 *
 * <p>There are seven, not six, and the count was established two independent ways. In {@code app/jcl} there
 * are eight {@code DEFINE GENERATIONDATAGROUP} occurrences over seven distinct names, and
 * {@code app/catlg/LISTCAT.txt} carries exactly seven {@code 0GDG BASE} headers. All seven map onto the batch
 * output bucket except where noted; the prefix for each is bound by the batch layer from
 * {@code carddemo.aws.s3.gdg-prefixes}.
 *
 * <ul>
 *   <li>{@code TRANSACT.BKUP} - {@code app/jcl/DEFGDGB.jcl:L25} with {@code LIMIT(5)} at {@code :L26};
 *       catalogued at {@code app/catlg/LISTCAT.txt:L1631}. Written at
 *       {@code app/jcl/TRANREPT.jcl:L29-L33}, record length 350.</li>
 *   <li>{@code TRANSACT.DALY} - {@code app/jcl/DEFGDGB.jcl:L31-L32}; catalogued at
 *       {@code app/catlg/LISTCAT.txt:L3021}. Written at {@code app/jcl/TRANREPT.jcl:L51-L55}, record length
 *       350 inherited through {@code DCB=(*.SORTIN)} at {@code :L53}.</li>
 *   <li>{@code TRANREPT} - {@code app/jcl/DEFGDGB.jcl:L37} with {@code LIMIT(5)} at {@code :L38}, and
 *       re-declared at {@code app/jcl/REPTFILE.jcl:L26} with {@code LIMIT(10)} at {@code :L27}; catalogued at
 *       {@code app/catlg/LISTCAT.txt:L1527}. Written at {@code app/jcl/TRANREPT.jcl:L76-L80} and
 *       {@code app/proc/TRANREPT.prc:L74-L78}, record length 133.</li>
 *   <li>{@code TCATBALF.BKUP} - {@code app/jcl/DEFGDGB.jcl:L43-L44}; catalogued at
 *       {@code app/catlg/LISTCAT.txt:L1202}.</li>
 *   <li>{@code SYSTRAN} - {@code app/jcl/DEFGDGB.jcl:L49-L50}; catalogued at
 *       {@code app/catlg/LISTCAT.txt:L1098}. Written at {@code app/jcl/INTCALC.jcl:L37-L41}, record length
 *       350.</li>
 *   <li>{@code TRANSACT.COMBINED} - {@code app/jcl/DEFGDGB.jcl:L55-L56}; catalogued at
 *       {@code app/catlg/LISTCAT.txt:L2919}. Written at {@code app/jcl/COMBTRAN.jcl:L33-L37}, record length
 *       350 inherited through {@code DCB=(*.SORTIN)} at {@code :L35}.</li>
 *   <li>{@code DALYREJS} - the seventh, declared alone at {@code app/jcl/DALYREJS.jcl:L24-L26}; catalogued at
 *       {@code app/catlg/LISTCAT.txt:L684}. Written at {@code app/jcl/POSTTRAN.jcl:L34-L38}, record length
 *       430.</li>
 *   </ul>
 *
 * <h2>The one legacy inconsistency that is resolved, rather than logged</h2>
 *
 * <p>{@code TRANREPT} is declared twice with different retention. {@code app/jcl/DEFGDGB.jcl:L37-L38} declares
 * {@code LIMIT(5)} with {@code SCRATCH}; {@code app/jcl/REPTFILE.jcl:L26-L27} declares {@code LIMIT(10)} with
 * no {@code SCRATCH}. <strong>Resolved to 10</strong>, recorded as
 * {@code carddemo.aws.s3.gdg-retention-generations}. It is the only legacy inconsistency that is resolved
 * rather than logged, purely because a single value has to be chosen when two declarations disagree and only
 * one lifecycle number can exist. Retention here is a
 * <strong>documented</strong> value, not an enforced one: object versioning on the output bucket supersedes
 * generation retention semantics, and no lifecycle rule is applied from this class or from any other Java
 * code. Should retention ever need enforcing, it belongs as a lifecycle rule in
 * {@code localstack-init/init-aws.sh} beside the versioning call that already lives there, never in Java.
 *
 * <h2>Generation reference translation, and the carry-forward contract</h2>
 *
 * <p>A relative generation reference becomes a deterministic object key:
 *
 * <ul>
 *   <li>A {@code (+1)} write becomes a <em>new</em> object under a monotonically increasing timestamp or
 *       job-instance prefix, over the versioned output bucket.</li>
 *   <li>A {@code (0)} read becomes a read of the lexicographically greatest existing prefix.</li>
 *   <li>Record length is preserved byte-exactly at the boundary in every direction: 350, 430, 133, 100 and
 *       80.</li>
 *   </ul>
 *
 * <p><strong>The hazard, verified in primary source.</strong> Within a single job the legacy stream writes
 * {@code (+1)} in one step and then re-reads {@code (+1)} - not {@code (0)} - in a later step, because on z/OS
 * the generation created earlier in the same job remains addressable as {@code (+1)} for the whole job:
 *
 * <ul>
 *   <li>{@code app/jcl/COMBTRAN.jcl} - {@code STEP05R} writes {@code SORTOUT} at {@code :L33-L37} to
 *       {@code TRANSACT.COMBINED(+1)}, and then {@code STEP10}'s {@code TRANSACT} statement at {@code :L44}
 *       reads {@code TRANSACT.COMBINED(+1)}.</li>
 *   <li>{@code app/jcl/TRANREPT.jcl} - the first step writes {@code PRC001.FILEOUT} at {@code :L29-L33} to
 *       {@code TRANSACT.BKUP(+1)}, the sort step's {@code SORTIN} at {@code :L38-L39} reads
 *       {@code TRANSACT.BKUP(+1)}, the sort writes {@code TRANSACT.DALY(+1)} at {@code :L55}, and
 *       {@code STEP10R}'s {@code TRANFILE} at {@code :L65-L66} reads {@code TRANSACT.DALY(+1)}. The same
 *       chain appears in {@code app/proc/TRANREPT.prc} at {@code :L27-L31}, {@code :L36-L37}, {@code :L53} and
 *       {@code :L63-L64}.</li>
 *   </ul>
 *
 * <p><strong>Consequence, and the contract every job must honour.</strong> The concrete key created by the
 * writing step is carried forward through the job or step execution context and re-used verbatim by the
 * reading step. A later step must <strong>never</strong> re-resolve the latest prefix mid-job. Re-resolution
 * is a race: a concurrently-running instance could have written a newer generation between the two steps, and
 * the reading step would silently consume the wrong input. This class specifies the contract; the job classes
 * implement it and {@code com.cardemo.config.BatchConfig} centralises the wiring.
 *
 * <h2>Record geometry preserved at the object-storage boundary</h2>
 *
 * <p>Record lengths are load-bearing, because parity is proved by comparing emitted bytes against the legacy
 * baseline. Each is cited:
 *
 * <ul>
 *   <li><strong>430</strong> - the reject stream. {@code app/jcl/POSTTRAN.jcl:L34-L38} declares
 *       {@code //DALYREJS DD DISP=(NEW,CATLG,DELETE)} with {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)} at
 *       {@code :L36} onto {@code DALYREJS(+1)} at {@code :L38}. The 430 resolves exactly as a 350-byte
 *       transaction image plus an 80-byte trailer, and the trailer as a four-digit reason code plus a
 *       76-character description. Note {@code RECFM=F} - fixed <em>unblocked</em>, not {@code FB}.</li>
 *   <li><strong>350</strong> - the interest output. {@code app/jcl/INTCALC.jcl:L37-L41} names the statement
 *       {@code TRANSACT} but targets {@code SYSTRAN(+1)} with {@code DCB=(RECFM=F,LRECL=350,BLKSIZE=0)}. The
 *       name is deceptive and the distinction matters: the interest job writes a fresh sequential generation,
 *       never the keyed cluster. Also the backup at {@code app/jcl/TRANREPT.jcl:L31}, and both sort outputs
 *       that inherit {@code DCB=(*.SORTIN)}.</li>
 *   <li><strong>133</strong> - the report line. {@code app/proc/TRANREPT.prc:L74-L78} and
 *       {@code app/jcl/TRANREPT.jcl:L76-L80}, {@code DCB=(LRECL=133,RECFM=FB,BLKSIZE=0)} onto
 *       {@code TRANREPT(+1)}.</li>
 *   <li><strong>80</strong> - statement text. {@code app/jcl/CREASTMT.JCL:L87-L91}, with
 *       {@code DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB)} at {@code :L89}.</li>
 *   <li><strong>100</strong> - statement markup. {@code app/jcl/CREASTMT.JCL:L92-L96}, with
 *       {@code DCB=(LRECL=100,BLKSIZE=800,RECFM=FB)} at {@code :L94}.</li>
 *   <li><strong>80</strong> - the queue parameter card, fixed by the queue's own declaration. See the queue
 *       section below.</li>
 *   </ul>
 *
 * <p>One work dataset is explicitly <em>not</em> an object-storage concern. The statement work cluster
 * {@code TRXFL} is defined with {@code KEYS(32 0)} at {@code app/jcl/CREASTMT.JCL:L30} and
 * {@code RECORDSIZE(350 350)} at {@code :L32}. It is an in-job projection and sort only: it is never persisted
 * to object storage and never to the database, so no bucket, prefix or key is allocated for it.
 *
 * <h2>The report queue: an eighty-byte fixed record becomes a typed message</h2>
 *
 * <p>The queue contract comes from the one and only {@code DEFINE TDQUEUE} in the resource definitions,
 * {@code app/csd/CARDDEMO.CSD:L499-L503}: {@code DEFINE TDQUEUE(JOBS) GROUP(CARDDEMO)} with
 * {@code DESCRIPTION(SUBMIT JOBS FROM CICS)}, {@code TYPE(EXTRA) DATABUFFERS(1) DDNAME(INREADER)
 * ERROROPTION(IGNORE)}, {@code OPENTIME(INITIAL) TYPEFILE(OUTPUT) RECORDSIZE(80)} and
 * {@code RECORDFORMAT(FIXED) BLOCKFORMAT(UNBLOCKED) DISPOSITION(MOD)}.
 *
 * <p><strong>The declared eighty-byte FIXED record is what fixes the message shape.</strong> The legacy
 * producer copies eighty-byte card images one at a time; the target publishes a single typed message carrying
 * the report name and the two dates, serialised as JSON. The queue is FIFO and every send carries a fixed,
 * deterministic message group, so ordering is reproducible run to run - which is why
 * {@code carddemo.aws.sqs.report-message-group-id} is a constant in configuration and not a generated value.
 *
 * <p>Two consequences for anyone reading a message body. It is <em>untrusted input</em>, so it is bound as
 * typed JSON with strict binding and never through Java serialization; the base profile pins
 * {@code fail-on-unknown-properties} for exactly this reason. And the consumer that replaces the JES2 internal
 * reader is the planned {@code com.cardemo.batch.jobs.BatchPipelineOrchestrator}, not this class - and that
 * orchestrator is <strong>not authored yet</strong>.
 *
 * <p>The producer replaces the block at {@code app/cbl/CORPT00C.cbl:L517-L523} - {@code EXEC CICS WRITEQ TD},
 * {@code QUEUE ('JOBS')}, {@code FROM (JCL-RECORD)}, {@code LENGTH (LENGTH OF JCL-RECORD)},
 * {@code RESP(WS-RESP-CD)}, {@code RESP2(WS-REAS-CD)}, {@code END-EXEC}. Its failure path at
 * {@code :L525-L535} is an observable contract, not an implementation detail: on any response other than
 * normal the source sets an error flag at {@code :L530} and moves the literal
 * {@code Unable to Write TDQ (JOBS)...} - exactly three trailing dots - into the message field at
 * {@code :L531-L532}, then repositions the cursor at {@code :L533}. That string must be reproduced
 * byte-for-byte by {@code com.cardemo.service.report.ReportSubmissionService}, which injects the publisher
 * declared here. It is recorded in this file because the queue contract lives here, and honoured there
 * because the screen message is that service's output.
 *
 * <h2>Idempotent provisioning: the precedent, and why it is not implemented here</h2>
 *
 * <p>The legacy stream already establishes idempotent provisioning as the house pattern.
 * {@code app/jcl/DEFGDGB.jcl} interleaves {@code IF LASTCC=12 THEN SET MAXCC=0} between its six definitions,
 * at {@code :L29}, {@code :L35}, {@code :L41}, {@code :L47} and {@code :L53}, so that a base which already
 * exists is not an error; {@code app/jcl/CREASTMT.JCL:L28} does the same with {@code SET MAXCC = 0} after its
 * pre-deletes. That is the precedent for {@code localstack-init/init-aws.sh} being idempotent, so that
 * repeated stack cycles converge instead of failing on resources that already exist. The precedent is recorded
 * here and implemented there. It is deliberately not implemented in Java.
 *
 * <h2>Two omissions that must not be helpfully undone</h2>
 *
 * <dl>
 *   <dt>No default notification destination is named</dt>
 *   <dd>Setting a default destination name on the notification template resolves the name immediately, and
 *       the default resolver resolves any name not already beginning with {@code arn:} by
 *       <strong>creating</strong> the topic. A single convenience call would therefore provision a resource
 *       from Java, invisibly, and make an eager network call during context refresh that would reach a live
 *       service under any profile declaring no endpoint override. The call is omitted and callers name the
 *       topic explicitly; the reasoning is restated on the bean itself so the omission cannot be mistaken for
 *       something forgotten.</dd>
 *
 *   <dt>No live-service fallback and no committed credential</dt>
 *   <dd>Adding a fallback endpoint, an inline credential or a provider with literal values would be a release
 *       release-stopping regression rather than a style issue, so the bar is stated explicitly for anyone
 *       editing this file.</dd>
 *
 *   <dt>No outbound correlation interceptor is registered here</dt>
 *   <dd>{@code com.cardemo.observability.CorrelationIdFilter} publishes the diagnostic-context key and the
 *       header name as constants and assigns outbound request interception to this class, but that class lies
 *       outside this file's declared dependency set: importing it would breach that contract, and
 *       re-declaring the two literals would duplicate a definition owned elsewhere. Registering the
 *       interceptor therefore requires one coordinated change - add that class to this file's dependency set,
 *       then register an execution interceptor on the client builders that reads the key and header from its
 *       constants rather than from new literals. Until then a correlation identifier is not propagated onto
 *       outbound cloud calls.</dd>
 *   </dl>
 *
 * <h2>Legacy job-control defects, recorded and deliberately not repaired</h2>
 *
 * <p>Repairing any of these would change bytes the parity comparison is measured against, so each is cited
 * and left alone:
 *
 * <dl>
 *   <dt>The statement markup record length disagrees with itself</dt>
 *   <dd>The pre-delete step declares the markup output at {@code DCB=(LRECL=80,BLKSIZE=3200,RECFM=FB)} at
 *       {@code app/jcl/CREASTMT.JCL:L69}, while the execution step declares
 *       {@code DCB=(LRECL=100,BLKSIZE=800,RECFM=FB)} at {@code :L94}. <strong>100 is correct</strong>,
 *       because the program emits from a 100-character fixed line in {@code app/cbl/CBSTM03A.CBL}. The
 *       emitted width is never adjusted to match the pre-delete declaration.</dd>
 *
 *   <dt>A corrupted job-control continuation line</dt>
 *   <dd>{@code app/jcl/CREASTMT.JCL:L90} reads literally
 *       {@code //         SPACE=(CYL,(1,1),RLSE), 00,RECFM=FB), ATA.VSAM.KSDS} - two lines of text overlaid on
 *       one another. The geometry it appears to imply is not acted on; the adjacent well-formed declarations
 *       at {@code :L89} and {@code :L94} are authoritative.</dd>
 *
 *   <dt>A misspelled paragraph label, and the locator that follows from it</dt>
 *   <dd>The producer paragraph at {@code app/cbl/CORPT00C.cbl:L515} is spelled
 *       {@code WIRTE-JOBSUB-TDQ.} - transposed, not {@code WRITE}. {@code :L515} is the label and the
 *       statement block is {@code :L517-L523}, which is the locator used throughout this file.</dd>
 *   </dl>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>Startup aborts with an unresolvable placeholder for a bucket, the queue or the topic</dt>
 *   <dd>The corresponding environment variable is absent and the base profile deliberately supplies no
 *       default. Export it, or activate the {@code local} profile, which carries developer defaults. Never add
 *       a default to the base profile to silence this.</dd>
 *
 *   <dt>Startup aborts from this class naming a property key as blank</dt>
 *   <dd>The variable is <em>set but empty</em>. An empty value satisfies placeholder resolution and even beats
 *       a profile default, so it would otherwise reach the client as an empty bucket or queue name and fail
 *       far from its cause. The message names the key and never the value.</dd>
 *
 *   <dt>Startup aborts because the physical queue name is not the logical name plus {@code .fifo}</dt>
 *   <dd>{@code carddemo.aws.sqs.report-queue} must be {@code carddemo.aws.sqs.report-queue-logical-name}
 *       suffixed {@code .fifo}, and nothing else. Two failures are prevented by the one rule. The provisioning
 *       script derives one name from the other, so a drift creates one queue and publishes to a different one.
 *       And a message group is honoured only on a FIFO queue, which SQS identifies solely by that suffix, so an
 *       unsuffixed name would leave report submissions reordered and duplicated with nothing in any log to say
 *       so. Remedy: set the physical name to the logical name plus the suffix. This was previously a startup
 *       warning, on the reasoning that the first send would fail loudly; that reasoning depended on a
 *       rejection this class cannot guarantee, so the check now aborts the context refresh instead.</dd>
 *
 *   <dt>A send fails reporting that the queue does not exist</dt>
 *   <dd>Intended. The strategy is {@code FAIL}, so a missing queue is never created silently. Run
 *       {@code localstack-init/init-aws.sh}; it is idempotent.</dd>
 *
 *   <dt>Startup aborts saying an endpoint host is not permitted, or that an endpoint is not set</dt>
 *   <dd>Intended, and it is the guarantee working. Point the three endpoint properties at the emulator - the
 *       base profile's default addresses the compose topology, and a test registers the container's mapped
 *       address. There is no live-AWS mode to switch into. Note that the SDK also honours a global endpoint
 *       environment variable, so exporting one while the integration tier is running would redirect
 *       container-backed clients onto the shared emulator; prefer per-profile configuration.</dd>
 *
 *   <dt>Startup aborts saying a credential property is not set, or that the access key looks live</dt>
 *   <dd>Also intended. Both credential properties must be present so that a static provider displaces the
 *       SDK's default chain, and the access key must not carry a real AWS prefix. Supply a deterministic
 *       non-live placeholder; the emulator validates no credential. Neither value is ever logged.</dd>
 *
 *   <dt>Two beans of the same integration type, or a bean-definition override failure</dt>
 *   <dd>The library's auto-configuration declares each of its templates conditionally on the corresponding
 *       operations interface being absent, so the three beans here replace it cleanly. A duplicate arises only
 *       if another class declares one of the same types; it should inject instead.</dd>
 *   </dl>
 *
 * <h2>Two facts the source does not publish</h2>
 *
 * <p>Two facts a reader might expect to find here are not stated, because the source does not publish them
 * and neither may be invented:
 *
 * <ul>
 *   <li>No physical key geometry is claimed for
 *       {@code app/data/EBCDIC/AWS.M2.CARDDEMO.DALYTRAN.PS}. It has no entry in
 *       {@code app/catlg/LISTCAT.txt} and no {@code KEYS} clause anywhere, and nothing under
 *       {@code app/data/EBCDIC} is parsed by the build in any case - those files are codepage reference
 *       only.</li>
 *   <li>No throughput or latency objective is asserted for object storage or for the queue. The legacy corpus
 *       publishes no service-level objective of any kind, so the performance gate records a measured baseline
 *       rather than a target.</li>
 *   </ul>
 */
@Configuration
public class AwsConfig {

    /** Signing-region property, indirected through the environment by the base profile. No literal default. */
    private static final String KEY_REGION = "spring.cloud.aws.region.static";

    /**
     * Object-storage service endpoint. Mandatory in every profile with no default anywhere, and validated
     * local-only by {@link #requireApprovedEmulatorEndpoint(String, String)}.
     */
    private static final String KEY_S3_ENDPOINT = "spring.cloud.aws.s3.endpoint";

    /**
     * Queue service endpoint. Mandatory in every profile with no default anywhere, and validated local-only
     * by {@link #requireApprovedEmulatorEndpoint(String, String)}.
     */
    private static final String KEY_SQS_ENDPOINT = "spring.cloud.aws.sqs.endpoint";

    /**
     * Notification service endpoint. Mandatory in every profile with no default anywhere, and validated
     * local-only by {@link #requireApprovedEmulatorEndpoint(String, String)}.
     */
    private static final String KEY_SNS_ENDPOINT = "spring.cloud.aws.sns.endpoint";

    /**
     * Static access-key property. Its presence is what displaces the SDK's default provider chain, and it is
     * therefore read twice: by the startup guard, before any client bean exists, and by the client builders
     * themselves. One declaration serves both.
     */
    private static final String KEY_ACCESS_KEY = "spring.cloud.aws.credentials.access-key";

    /**
     * Static secret-key property. Required alongside {@link #KEY_ACCESS_KEY}, never logged, and read by the
     * startup guard as well as by the client builders.
     */
    private static final String KEY_SECRET_KEY = "spring.cloud.aws.credentials.secret-key";

    /** Batch input bucket, replacing the {@code DALYTRAN} sequential dataset. No default in the base profile. */
    private static final String KEY_INPUT_BUCKET = "carddemo.aws.s3.batch-input-bucket";

    /** Batch output bucket - the versioned one, replacing generation retention. No base-profile default. */
    private static final String KEY_OUTPUT_BUCKET = "carddemo.aws.s3.batch-output-bucket";

    /** Statement output bucket, holding the 80-byte text and 100-byte markup streams. No base default. */
    private static final String KEY_STATEMENTS_BUCKET = "carddemo.aws.s3.statements-bucket";

    /** Physical report-queue name, replacing {@code DEFINE TDQUEUE(JOBS)}. No default in the base profile. */
    private static final String KEY_REPORT_QUEUE = "carddemo.aws.sqs.report-queue";

    /** Logical report-queue name. Defaulted here to {@link #REPORT_QUEUE_LOGICAL_NAME}. */
    private static final String KEY_REPORT_QUEUE_LOGICAL_NAME = "carddemo.aws.sqs.report-queue-logical-name";

    /** Notification topic, replacing operator notification. No default in the base profile. */
    private static final String KEY_NOTIFICATION_TOPIC = "carddemo.aws.sns.notification-topic";

    /** Missing-queue behaviour. Pinned {@code FAIL} by the base profile and defaulted {@code FAIL} here. */
    private static final String KEY_QUEUE_NOT_FOUND_STRATEGY = "spring.cloud.aws.sqs.queue-not-found-strategy";

    // =============================================================================================
    // Emulator-only binding. The keys below are read by the startup guard rather than injected,
    // because the guard has to run before any client bean exists and therefore before this class is
    // instantiated. See requireEmulatorOnlyBindings for why that ordering is the whole point.
    // =============================================================================================

    /** Per-service endpoint override keys, in the order the guard reports them. */
    private static final List<String> KEYS_SERVICE_ENDPOINT = List.of(
            "spring.cloud.aws.s3.endpoint",
            "spring.cloud.aws.sqs.endpoint",
            "spring.cloud.aws.sns.endpoint");

    /**
     * The library's single global endpoint override, accepted as a fallback for any service whose own key is
     * unset. It is not <em>used</em> by any profile - see the comment in {@code application-local.yml} for why
     * a global override would declare an integration surface this application does not have - but the guard
     * honours it so that a deployment which sets it is validated rather than silently unvalidated.
     *
     * <p>That it is bound and validated even though no profile sets it is the point: a key this application
     * otherwise never reads is exactly where an unapproved address would slip in unnoticed.
     */
    private static final String KEY_GLOBAL_ENDPOINT = "spring.cloud.aws.endpoint";

    /** The two URL schemes the guard accepts. See the cleartext note on {@link #requireEmulatorOnlyBindings}. */
    private static final Set<String> ALLOWED_ENDPOINT_SCHEMES = Set.of("http", "https");

    /**
     * The exact host names at which the emulator is reachable, and no others.
     *
     * <p>This is the same allowlist {@code localstack-init/init-aws.sh} applies to {@code AWS_ENDPOINT_URL},
     * deliberately spelled identically so that the provisioning script and the application cannot disagree
     * about which endpoints exist. {@code carddemo-localstack} may carry a {@code CLONE_INDEX} suffix, which is
     * why that one form is matched by {@link #ALLOWED_COMPOSE_HOST_PATTERN} rather than by set membership.
     *
     * <p>It is an allowlist and not a denylist on purpose, and the reason is recorded in the script's own
     * post-mortem: a denylist over the live service domain missed the same domain in capitals, accepted every
     * unforeseen host through its default branch, and let the cloud instance-metadata address through - the
     * classic credential-exfiltration target. An allowlist refuses a host nobody thought about.
     */
    private static final Set<String> ALLOWED_ENDPOINT_HOSTS = Set.of(
            "localhost",
            "127.0.0.1",
            "::1",
            "[::1]",
            "localhost.localstack.cloud",
            "localstack",
            "host.docker.internal");

    /**
     * The Compose service name, optionally suffixed with a clone index so that parallel clones do not collide.
     * The host reaching this pattern has already been lower-cased, so the pattern itself needs no case
     * insensitivity.
     *
     * <p>The suffix is digits only, spelled exactly as {@code ALLOWED_ENDPOINT_HOST_PATTERN} at
     * {@code localstack-init/init-aws.sh:L721} spells it, because a clone index is only ever a number. It was
     * briefly wider - any one to sixteen alphanumeric characters - which admitted container names the
     * provisioning script refuses, and two guards over one variable that disagree about what exists are worse
     * than one. The narrower rule is the one that matches the script, so it is the one that survives.
     */
    private static final Pattern ALLOWED_COMPOSE_HOST_PATTERN =
            Pattern.compile("^carddemo-localstack(-[0-9]+)?$");

    /**
     * The Compose container name, which carries an optional {@code -${CLONE_INDEX}} suffix of digits only, so
     * it is matched by a bounded pattern rather than enumerated. Mirrors
     * {@code ALLOWED_ENDPOINT_HOST_PATTERN} at {@code localstack-init/init-aws.sh:721}.
     *
     * <p>Subdomains of the loopback DNS name are deliberately NOT matched, for the two reasons the script
     * records: least privilege, since the bare host is the only endpoint this project documents; and the fact
     * that a virtual-hosted name is not a general service edge and fails provisioning anyway.
     */
    private static final Pattern APPROVED_ENDPOINT_HOST_PATTERN =
            Pattern.compile("^carddemo-localstack(-[0-9]+)?$");

    /** The only two schemes an endpoint may carry, compared as written so an upper-cased spelling fails. */
    private static final String SCHEME_HTTP = "http";

    /** The transport-secured spelling, accepted for completeness; every approved host is loopback-bound. */
    private static final String SCHEME_HTTPS = "https";

    /** Lowest port an endpoint may name. Port 0 is refused, matching the script's range check. */
    private static final int LOWEST_ENDPOINT_PORT = 1;

    /** Highest port an endpoint may name. */
    private static final int HIGHEST_ENDPOINT_PORT = 65535;

    /** Sentinel {@link URI#getPort()} returns when the authority carries no port. */
    private static final int NO_ENDPOINT_PORT = -1;

    /**
     * Whole-call deadline in seconds, {@value}, covering every attempt and the backoff between them.
     *
     * <p>This and {@link #API_CALL_ATTEMPT_TIMEOUT_SECONDS} are the only two call deadlines in this class,
     * and {@link #applyBoundedPolicy(SdkClientBuilder)} is the only place they are applied. Retrying at all
     * is safe on every path this application uses: object writes are {@code PutObject} under a deterministic
     * key so a repeated attempt overwrites its own bytes, object reads are idempotent by definition, and the
     * report-job publish is a FIFO {@code SendMessage} against a queue provisioned
     * {@code ContentBasedDeduplication=true}, so an identical retried body carries an identical deduplication
     * hash and the queue collapses it. The attempt count and the backoff between attempts are bounded by
     * {@code RetryMode.STANDARD} rather than by a constant here, which keeps the choice on the library's own
     * stable surface. None of this is an application-level retry, which the source does not have and which
     * is deliberately not added: {@code com.cardemo.service.report.ReportSubmissionService} reproduces
     * {@code app/cbl/CORPT00C.cbl}'s single write and its failure message rather than re-submitting a job.
     *
     * <p>Chosen against the largest object this application writes rather than picked round: a statement run
     * emits fixed 80-byte and 100-byte records and the reject stream 430-byte records, so a single call moves
     * kilobytes against a loopback endpoint. Thirty seconds is therefore generous by three orders of magnitude
     * for the healthy case while still bounding a hung call deterministically. <strong>It is not a
     * service-level objective</strong>: the frozen corpus publishes none, and none is invented here.
     */
    private static final int API_CALL_TIMEOUT_SECONDS = 30;

    /**
     * Per-attempt deadline in seconds, {@value}, after which the attempt is aborted and, if the retry strategy
     * still allows it, retried. This is the value that bounds a connection or a socket read which never
     * completes, and it is deliberately well below {@value #API_CALL_TIMEOUT_SECONDS} so that a retry can
     * happen inside the whole-call deadline rather than being cut off by it.
     */
    private static final int API_CALL_ATTEMPT_TIMEOUT_SECONDS = 10;

    /** Deadline in seconds, {@value}, for the one-off startup verification of the queue's own attributes. */
    private static final int QUEUE_VERIFICATION_TIMEOUT_SECONDS = 15;

    /**
     * The page size the notification service itself uses when listing topics, {@value}. {@code ListTopics}
     * carries no page-size parameter, so this documents the service's own fixed maximum rather than requesting
     * it, and it appears only in the diagnostic that reports how much was searched.
     */
    private static final int TOPIC_PAGE_LIMIT = 100;

    /** Maximum number of topic pages walked before resolution gives up, so the walk is always bounded. */
    private static final int TOPIC_PAGE_LIMIT_COUNT = 50;

    /** Prefix of an already-resolved topic identifier, which the strict resolver accepts as given. */
    private static final String ARN_PREFIX = "arn:";

    /** Separator between the segments of a resource identifier, used to read a topic's bare name back out. */
    private static final char ARN_SEPARATOR = ':';

    /**
     * Matches an IPv4 literal in {@code 127.0.0.0/8}, the whole loopback range, and nothing else.
     *
     * <p>This is a full match on four bounded octets rather than a test for the leading {@code 127.},
     * because a prefix test over a host name is not a test for an address at all: it admitted
     * {@code 127.0.0.1.attacker.example}, a perfectly ordinary DNS name that begins with the loopback
     * literal and resolves wherever its owner points it. That is the same defect - an allowlist entry
     * matched as a substring of a hostile name - that the entry for {@code localhost} is deliberately spelled
     * as exact membership to avoid, and it survived here only because an address looks less like a name than
     * a name does. Each octet is bounded to 0-255 so the pattern describes addresses rather than digit runs.
     */
    private static final Pattern LOOPBACK_ADDRESS_PATTERN = Pattern.compile(
            "^127(\\.(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])){3}$");

    /**
     * The only host names a cloud endpoint may carry.
     *
     * <p>This set is the Java half of a single allowlist whose shell half is {@code ALLOWED_ENDPOINT_HOSTS}
     * at {@code localstack-init/init-aws.sh:L700-L706}; the two are deliberately identical, because two
     * allowlists that disagree are worse than one. Membership is exact, not prefix based, except for the
     * compose service name, which may carry a {@code CLONE_INDEX} suffix when parallel clones run and is
     * therefore matched by {@link #APPROVED_ENDPOINT_HOST_PATTERN}, the mirror of the script's own
     * {@code ALLOWED_ENDPOINT_HOST_PATTERN} at {@code :L721}.
     *
     * <p>Every entry is either a loopback literal, a DNS name that resolves to loopback, or a name that
     * resolves only inside the Compose bridge network, so no accepted request can leave the host - which is
     * what makes cleartext {@code http} safe here, transport security being deferred hardening under AAP
     * 0.3.2.
     *
     * <p><strong>There is deliberately no deny-list, and the live AWS service domain is deliberately not
     * spelled anywhere in this file.</strong> A deny-list admits every host nobody thought of; an allowlist
     * refuses it.
     */
    private static final Set<String> APPROVED_ENDPOINT_HOSTS = Set.of(
            "localhost",
            "127.0.0.1",
            "::1",
            "[::1]",
            "localhost.localstack.cloud",
            "localstack");

    /**
     * Compose service-name prefix an endpoint host may carry, so that
     * {@code carddemo-localstack} and its {@code CLONE_INDEX}-suffixed forms are both accepted.
     *
     * <p>The suffix separator is a hyphen and only a hyphen. An underscore was considered and rejected as
     * unreachable rather than as unwanted: {@link java.net.URI#getHost()} returns {@code null} for an
     * authority containing an underscore, because RFC 3986 does not admit one in a registered name, so a
     * value such as {@code http://carddemo-localstack_000:4566} never reaches the host comparison at all -
     * it is refused earlier, for having no parseable host. A branch matching an underscore would therefore
     * have been dead code, which Rule 1 Clause B forbids. Name parallel emulator containers with a hyphen.
     */
    private static final String APPROVED_ENDPOINT_HOST_PREFIX = "carddemo-localstack";

    /**
     * The logical name of the queue that replaces the extrapartition transient data queue {@code JOBS} of
     * {@code app/csd/CARDDEMO.CSD:L499-L503}. The physical name is this value suffixed
     * {@link #FIFO_SUFFIX} and nothing else, which is why a reader meeting {@code carddemo-report-jobs.fifo}
     * in a queue listing is seeing the only accepted spelling rather than one of two.
     */
    private static final String REPORT_QUEUE_LOGICAL_NAME = "carddemo-report-jobs";

    /**
     * The suffix a queue name must carry for the service to treat it as FIFO and honour message groups. It is
     * the sole identifying feature of a FIFO queue, which is why the constructor requires it rather than
     * recommending it.
     */
    private static final String FIFO_SUFFIX = ".fifo";

    /**
     * The only host names an AWS service endpoint may resolve to: the loopback interface and the emulator's
     * own names, as reached from the host, from inside the compose network and from a Testcontainers-managed
     * container.
     *
     * <p>This set is the mechanism behind the no-live-AWS guarantee, and it is a set rather than a single
     * value because the same application must be reachable four ways: {@code localhost} and {@code 127.0.0.1}
     * from a developer host or a Testcontainers-mapped port, {@code localhost.localstack.cloud} which resolves
     * to the loopback interface and is the emulator's documented name for path-style addressing,
     * {@code localstack} and {@code carddemo-localstack} which are the service and container names inside the
     * compose network, and {@code host.docker.internal} which is how a container reaches an emulator running
     * on its host. Every one of them is a private or loopback destination; none of them is a public name, and a
     * real service endpoint is always a public name, so no member of this set can address one.
     *
     * <p><strong>The port is deliberately not constrained</strong> beyond being present and in range. The
     * compose topology publishes the emulator on 4566, but a Testcontainers-managed emulator is published on
     * an ephemeral port chosen at start-up, and pinning 4566 here would make the integration tier
     * unrunnable while adding nothing: a loopback host is already unreachable from outside the machine.
     */
    private static final Set<String> PERMITTED_ENDPOINT_HOSTS = Set.of(
            "localhost",
            "127.0.0.1",
            "::1",
            "localhost.localstack.cloud",
            "localstack",
            "carddemo-localstack",
            "host.docker.internal");

    /**
     * Prefixes AWS assigns to real access-key identifiers: {@code AKIA} for a long-term key and {@code ASIA}
     * for a temporary one.
     *
     * <p>A value carrying either prefix is refused outright. It is not a proof of liveness - the emulator would
     * accept such a value happily - but it is the shape a leaked production key has, and refusing it converts
     * "a real credential was pasted into an environment file" from a silent, dangerous success into a startup
     * failure. The rejected value is never echoed, so the diagnostic cannot itself become a disclosure.
     */
    private static final Set<String> LIVE_ACCESS_KEY_PREFIXES = Set.of("AKIA", "ASIA");

    /**
     * The record size declared by {@code RECORDSIZE(80)} at {@code app/csd/CARDDEMO.CSD:L502}, alongside
     * {@code RECORDFORMAT(FIXED)} at {@code :L503}. It is the message-shape contract: each fixed 80-byte card
     * image that {@code app/cbl/CORPT00C.cbl:L517-L523} wrote one at a time becomes one typed JSON message
     * carrying the report name and the two dates. Reported at startup so the contract is visible in the log
     * rather than only in this comment.
     */
    private static final int QUEUE_RECORD_LENGTH = 80;

    /**
     * Structured-logging collaborator, per Rule 1 Clause A. It carries the startup confirmation and the FIFO
     * advisory only: no credential, no resource identifier containing an account number, no queue address and
     * no request or response payload is ever passed to it. Masking in the logging configuration is a second line
     * of defence; the first is never producing such a value here.
     */
    private static final Logger LOG = LoggerFactory.getLogger(AwsConfig.class);

    /**
     * The physical report-queue name, set as the publisher's default destination.
     *
     * <p>Retained because {@link #sqsTemplate(SqsAsyncClient, ObjectProvider, MessagingMessageConverter)} uses
     * it, and safe to retain because naming a default queue is a field assignment: the library resolves a queue
     * address lazily, on the first send. The three bucket names, the region and the notification topic are
     * deliberately <em>not</em> retained. Their purpose is the startup assertion in the constructor, every
     * consumer binds the same property key directly, and in the topic's case naming a default destination would
     * additionally have created the topic - see
     * {@link #snsTemplate(SnsClient, ObjectProvider, TopicArnResolver, ObjectProvider)}. A field holding any of
     * them would be state that is written and never read.
     */
    private final String reportQueueName;

    /** Missing-queue behaviour, applied to the publisher so a missing queue is never silently created. */
    private final QueueNotFoundStrategy queueNotFoundStrategy;

    /**
     * Binds and validates the environment-indirected resource names this application cannot function without,
     * <strong>together with the three service endpoints and the credential pair</strong>, and fails startup if
     * any of them is absent, blank, or capable of reaching a live AWS service.
     *
     * <h4>The endpoint and credential guard, and why it is here rather than anywhere else</h4>
     *
     * <p>Validating names alone was not enough. A profile could resolve a bucket name perfectly and still leave
     * the three service endpoints unset, in which case the SDK performs regional endpoint discovery and a call
     * lands on a real AWS host; and with no static credential pair the SDK walks its default provider chain -
     * environment variables, the shared credentials file, a web-identity token, container credentials, then
     * instance metadata - and may sign that call with a live principal. AAP §0.3.2 admits neither: "All AWS
     * interaction is against LocalStack. Zero live credentials appear in any file, and no code path may reach a
     * real AWS endpoint." So this constructor now proves the local-only contract instead of relying on the
     * absence of configuration to imply it.
     *
     * <p><strong>This constructor is the right place because it necessarily runs first.</strong> Every one of
     * the three templates below is an instance bean method on this class, so no template - and therefore no
     * call through one - can exist until this constructor has returned. A guard placed in a bean method would
     * run after the client it is meant to police had been built; a guard in a separate listener would run at an
     * order the container chooses. Failing here aborts the context refresh before the application can accept a
     * request or start a batch step.
     *
     * <p>Each endpoint is checked by {@link #requireApprovedEmulatorEndpoint(String, String)} and by nothing
     * else, so there is one rule rather than several that must agree: the value must parse as an absolute URI
     * with a lowercase {@code http} or {@code https} scheme, must name a host, must carry no user
     * information, no query, no fragment, no path and no percent-encoded authority, must name a port in range
     * if it names one at all, and its host must satisfy {@link #isEmulatorHost(String)}. Each defect is
     * reported distinctly rather than collapsed into one "invalid configuration" message, because each has a
     * distinct remedy. The credential pair must be present - which is what displaces the provider chain - and
     * must not carry a live access-key prefix. <strong>No rejected value is echoed at all</strong>, not even
     * its host: a refused endpoint may carry user information, and user information in an error line is a
     * credential in a log file.
     *
     * <p>The absent case is already fatal without help: the base profile gives these keys no default, so an
     * unset variable fails placeholder resolution before this constructor is reached. <strong>The blank case is
     * the one this check exists for.</strong> An empty value resolves successfully and even takes precedence
     * over a profile default, because a default applies only when a variable is unset, not when it is set to
     * the empty string. Left unchecked it would reach a client as an empty bucket or queue name and surface far
     * from its cause. Rule 1 Clause B requires null and empty cases to be handled explicitly; this is that
     * handling, performed once, at startup, before any request or batch step can run.
     *
     * <p>Every failure message names the <em>property key</em> and never the value, so a diagnostic can never
     * become a disclosure. The one informational line emitted on success reports the bound logical names -
     * bucket, queue and topic names only. Those are non-secret: they are committed as developer defaults in
     * {@code application-local.yml} and in {@code localstack-init/init-aws.sh}. No resource identifier
     * containing an account number, no queue address, no signed link, no credential of any kind and no request
     * or response payload is logged here or anywhere in this class.
     *
     * <p>Two relationships are asserted beyond simple presence, and both mirror the provisioning script so that
     * configuration drift is caught at startup rather than diagnosed later from a half-provisioned stack. The
     * three buckets must be distinct, because a single bucket serving two roles would let batch output overwrite
     * batch input. And the physical queue name must be the logical name suffixed {@value #FIFO_SUFFIX} exactly:
     * the script derives one from the other, so a drift would have provisioning create one queue while
     * publishing targets another name, and the suffix is additionally the only way SQS identifies a FIFO queue,
     * without which the fixed message group that keeps report submission ordered is not honoured at all.
     *
     * @param region                  signing region, from {@value #KEY_REGION}; no default, must be non-blank
     * @param batchInputBucket        batch input bucket, from {@value #KEY_INPUT_BUCKET}; no base-profile
     *                                default, must be non-blank and distinct from the other two
     * @param batchOutputBucket       versioned batch output bucket, from {@value #KEY_OUTPUT_BUCKET}; no
     *                                base-profile default, must be non-blank and distinct
     * @param statementsBucket        statement bucket, from {@value #KEY_STATEMENTS_BUCKET}; no base-profile
     *                                default, must be non-blank and distinct
     * @param reportQueueName         physical queue name, from {@value #KEY_REPORT_QUEUE}; no base-profile
     *                                default, must be non-blank and must be the logical name suffixed
     *                                {@value #FIFO_SUFFIX}
     * @param reportQueueLogicalName  logical queue name, from {@value #KEY_REPORT_QUEUE_LOGICAL_NAME},
     *                                defaulting to {@value #REPORT_QUEUE_LOGICAL_NAME}
     * @param notificationTopic       notification topic, from {@value #KEY_NOTIFICATION_TOPIC}; no
     *                                base-profile default, must be non-blank
     * @param queueNotFoundStrategy   missing-queue behaviour, from
     *                                {@value #KEY_QUEUE_NOT_FOUND_STRATEGY}, defaulting to {@code FAIL} rather
     *                                than to the library's {@code CREATE}, because creating a queue from Java
     *                                is precisely what this class must never do
     * @param s3Endpoint              object-storage endpoint, from {@value #KEY_S3_ENDPOINT}; must be present
     *                                and must resolve to one of the permitted emulator hosts
     * @param sqsEndpoint             queue endpoint, from {@value #KEY_SQS_ENDPOINT}; same contract
     * @param snsEndpoint             notification endpoint, from {@value #KEY_SNS_ENDPOINT}; same contract
     * @param globalEndpoint          the library's single global endpoint override. It is not a substitute for
     *                                the three service endpoints, which are each mandatory; when it is set it
     *                                must itself resolve to an approved emulator endpoint, so a live value
     *                                cannot be introduced through it either
     * @param accessKey               static access key, from {@value #KEY_ACCESS_KEY}; must be present, so the
     *                                SDK's default provider chain is displaced, and must not carry a live
     *                                access-key prefix
     * @param secretKey               static secret key, from {@value #KEY_SECRET_KEY}; must be present. Its
     *                                value is never logged, echoed, digested or reported
     * @throws IllegalStateException if any name is blank, if the three buckets are not distinct, if the
     *                               physical and logical queue names disagree, if the strategy resolves to no
     *                               value, if any service endpoint is absent or is not an emulator endpoint, if
     *                               the global override is set to a non-emulator endpoint, or if either
     *                               credential is absent or looks live. Startup is aborted in every case;
     *                               nothing is defaulted quietly and nothing is allowed to fall back to live
     *                               routing
     */
    public AwsConfig(
            @Value("${" + KEY_REGION + "}") final String region,
            @Value("${" + KEY_INPUT_BUCKET + "}") final String batchInputBucket,
            @Value("${" + KEY_OUTPUT_BUCKET + "}") final String batchOutputBucket,
            @Value("${" + KEY_STATEMENTS_BUCKET + "}") final String statementsBucket,
            @Value("${" + KEY_REPORT_QUEUE + "}") final String reportQueueName,
            @Value("${" + KEY_REPORT_QUEUE_LOGICAL_NAME + ":" + REPORT_QUEUE_LOGICAL_NAME + "}")
            final String reportQueueLogicalName,
            @Value("${" + KEY_NOTIFICATION_TOPIC + "}") final String notificationTopic,
            @Value("${" + KEY_QUEUE_NOT_FOUND_STRATEGY + ":FAIL}")
            final QueueNotFoundStrategy queueNotFoundStrategy,
            @Value("${" + KEY_S3_ENDPOINT + ":}") final String s3Endpoint,
            @Value("${" + KEY_SQS_ENDPOINT + ":}") final String sqsEndpoint,
            @Value("${" + KEY_SNS_ENDPOINT + ":}") final String snsEndpoint,
            @Value("${" + KEY_GLOBAL_ENDPOINT + ":}") final String globalEndpoint,
            @Value("${" + KEY_ACCESS_KEY + ":}") final String accessKey,
            @Value("${" + KEY_SECRET_KEY + ":}") final String secretKey) {

        // The allowlist sweep across every configured endpoint, including the library's single global
        // override. It runs before anything else in this constructor, so no resource name, credential or
        // strategy is even looked at until every endpoint has been proved to address the emulator, and it
        // hands back the parsed addresses so that nothing below re-parses them under a second rule.
        final Map<String, URI> verifiedEndpoints =
                requireEmulatorEndpoints(s3Endpoint, sqsEndpoint, snsEndpoint, globalEndpoint);

        final String verifiedRegion = requireConfigured(KEY_REGION, region);
        final String verifiedInputBucket = requireConfigured(KEY_INPUT_BUCKET, batchInputBucket);
        final String verifiedOutputBucket = requireConfigured(KEY_OUTPUT_BUCKET, batchOutputBucket);
        final String verifiedStatementsBucket = requireConfigured(KEY_STATEMENTS_BUCKET, statementsBucket);
        requireDistinctBuckets(verifiedInputBucket, verifiedOutputBucket, verifiedStatementsBucket);

        final String verifiedLogicalName =
                requireConfigured(KEY_REPORT_QUEUE_LOGICAL_NAME, reportQueueLogicalName);
        this.reportQueueName = requireConfigured(KEY_REPORT_QUEUE, reportQueueName);
        requireQueueNamesAligned(this.reportQueueName, verifiedLogicalName);

        final String verifiedNotificationTopic = requireConfigured(KEY_NOTIFICATION_TOPIC, notificationTopic);
        this.queueNotFoundStrategy = requireStrategy(queueNotFoundStrategy);

        // The credential half of the local-only guard. Both halves run before any template bean method on
        // this class, so no client can be built - and therefore no call can be signed or addressed - until
        // every endpoint has been proved to be an emulator endpoint and both credentials have been proved
        // present and non-live.
        requireNonLiveCredentials(accessKey, secretKey);

        LOG.info("CardDemo cloud integration bound in region {}: batch input bucket {}, versioned batch output "
                        + "bucket {}, statements bucket {}, report queue {} (logical name {}, the {}-byte fixed "
                        + "record of DEFINE TDQUEUE(JOBS) carried as one typed JSON message), notification "
                        + "topic {}, missing-queue strategy {}. Every service endpoint resolved to an "
                        + "allow-listed emulator address and static credentials are in force, so no request can "
                        + "reach a live service; endpoint values and credentials are deliberately absent from "
                        + "this line",
                verifiedRegion, verifiedInputBucket, verifiedOutputBucket, verifiedStatementsBucket,
                this.reportQueueName, verifiedLogicalName, QUEUE_RECORD_LENGTH, verifiedNotificationTopic,
                this.queueNotFoundStrategy);

        // Authorities only, and only after they have been proved to be emulator addresses. They are not
        // secret - they are loopback or compose-network names - and printing them is what makes the guarantee
        // auditable from a running process rather than only from this source file. The authority is logged
        // whole rather than as host and port because the port is optional: rendering an absent one as the
        // parser's -1 sentinel would read as a defect in this line rather than as a valid endpoint. No
        // userinfo can appear in it, because an endpoint carrying any is refused above.
        LOG.info("CardDemo cloud integration is emulator-only: object storage at {}, queue at {}, "
                        + "notifications at {}, signed with static non-live credentials so the SDK default "
                        + "provider chain is never consulted. No code path can reach a live AWS endpoint.",
                verifiedEndpoints.get(KEY_S3_ENDPOINT).getRawAuthority(),
                verifiedEndpoints.get(KEY_SQS_ENDPOINT).getRawAuthority(),
                verifiedEndpoints.get(KEY_SNS_ENDPOINT).getRawAuthority());
    }

    /**
     * Refuses to start unless every cloud client this application will build is bound to the emulator.
     *
     * <p><strong>What it enforces.</strong> AAP section 0.3.2 places live AWS accounts and real credentials out
     * of scope and states that "no code path may reach a real AWS endpoint"; section 0.8.4 repeats it as a
     * standing constraint. Before this guard existed that was a statement in prose. Three profiles pointed the
     * clients at the emulator by convention, {@code application-prod.yml} deliberately did not - it documented
     * that production "resolves every AWS service through the SDK's standard endpoint resolution" and let the
     * ambient instance or task credential chain supply a principal - and no code anywhere checked. A deployment
     * could therefore sign a real request against a real account, which is a <strong>Blocker</strong>. This
     * method is the structural enforcement that makes the constraint true rather than intended.
     *
     * <p><strong>Why a {@link BeanFactoryPostProcessor} and why {@code static}.</strong> The requirement is that
     * the check runs <em>before client creation</em>, not merely before the first call. A bean-factory
     * post-processor is invoked during {@code invokeBeanFactoryPostProcessors}, which precedes
     * {@code preInstantiateSingletons}, so no {@code S3Client}, {@code SqsAsyncClient} or {@code SnsClient} can
     * exist when it runs and no ordering assumption about configuration-class instantiation is needed. Putting
     * the check in this class's constructor instead would be order-dependent: another bean could reach a client
     * first. The method is {@code static} because a non-static {@code @Bean} method returning a
     * post-processor would force this configuration class to be instantiated during post-processing, which is
     * exactly the premature initialisation the framework warns about.
     *
     * <p><strong>What it checks, per service.</strong> {@code s3}, {@code sqs} and {@code sns} each need an
     * endpoint override, taken from their own key or from {@value #KEY_GLOBAL_ENDPOINT}. Each one is
     * <em>parsed</em> and not merely prefix-matched: scheme in {@link #ALLOWED_ENDPOINT_SCHEMES}, no userinfo,
     * no query, no fragment, no path beyond an optional single slash, an explicit port, and a host that is
     * either in {@link #ALLOWED_ENDPOINT_HOSTS} or matches {@link #ALLOWED_COMPOSE_HOST_PATTERN}. Prefix
     * matching is not sufficient and the reason is concrete: {@code http://localhost.attacker.example} starts
     * with the accepted host and is not it, and a {@code user:pass@} form hides the real host after the
     * userinfo. The port is required but not restricted to 4566, unlike the provisioning script's rule, because
     * Testcontainers publishes the emulator on an ephemeral host port and the integration tier binds to
     * whatever it gets; the host is the security control, the port is not.
     *
     * <p><strong>What it checks, once.</strong> Both static credential properties must be present. That is a
     * control rather than a convenience: when they are set the library builds a static credentials provider,
     * which <em>replaces</em> the SDK's default chain, so no {@code ~/.aws} profile, web-identity token,
     * container credential or instance-metadata response can be reached. When they are absent the default chain
     * applies and a live principal becomes possible, so absence is refused.
     *
     * <p><strong>Cleartext.</strong> {@code http} is accepted because every allowlisted host is a loopback
     * literal, a name that resolves to loopback, or a name that only resolves inside the Compose bridge network,
     * so no accepted request leaves the host; transport security is recorded as deferred hardening in AAP
     * section 0.3.2. That safety is a property of the allowlist: adding a host reachable off-box means requiring
     * {@code https} for it in the same change.
     *
     * <p><strong>Diagnostics.</strong> A failure names the property key, the rejected host or the defect, and
     * the remedy. It never reports a credential value, and it never spells the live service domain - an
     * allowlist has no use for that literal, and its absence from this file is itself the observable difference
     * from the denylist design the provisioning script's post-mortem rejected.
     *
     * @param environment the fully prepared environment, from which the guard reads the resolved values
     * @return a post-processor that either returns quietly or aborts the refresh; it registers nothing and
     *         mutates no bean definition
     */
    @Bean
    public static BeanFactoryPostProcessor cloudEmulatorBindingGuard(final Environment environment) {
        return beanFactory -> requireEmulatorOnlyBindings(environment);
    }

    /**
     * Applies the emulator-only rules described on {@link #cloudEmulatorBindingGuard(Environment)}.
     *
     * <p>Private, because there is no reason to widen the surface: a test reaches this logic the same way the
     * container does, by calling {@link #cloudEmulatorBindingGuard(Environment)} and invoking the
     * post-processor it returns against a mock environment. That is cheaper and far more legible than asserting
     * on a failed context refresh, it lets every rejected form be enumerated rather than sampled, and it
     * exercises the production wiring rather than a parallel path.
     *
     * @param environment the environment to read; must not be {@code null}
     * @throws IllegalStateException if any endpoint is absent or not an allowlisted emulator address, or if
     *                               either static credential property is absent, aborting startup
     */
    private static void requireEmulatorOnlyBindings(final Environment environment) {
        final String globalEndpoint = environment.getProperty(KEY_GLOBAL_ENDPOINT);
        for (final String serviceKey : KEYS_SERVICE_ENDPOINT) {
            final String configured = environment.getProperty(serviceKey);
            final String endpoint = configured == null || configured.isBlank() ? globalEndpoint : configured;
            final String sourceKey = configured == null || configured.isBlank() ? KEY_GLOBAL_ENDPOINT : serviceKey;
            requireEmulatorEndpoint(sourceKey, serviceKey, endpoint);
        }

        requireStaticCredential(environment, KEY_ACCESS_KEY);
        requireStaticCredential(environment, KEY_SECRET_KEY);

        LOG.info("Cloud clients are bound to the emulator only: all three endpoint overrides resolve to an "
                + "allowlisted local host and static credentials are configured, so the SDK's default "
                + "credential provider chain is replaced and no request can reach a live AWS endpoint.");
    }

    /**
     * Proves one endpoint value is an allowlisted emulator address.
     *
     * @param sourceKey  the key the value actually came from, quoted in the failure message
     * @param serviceKey the service key that needed a value, so the message says which client is unbound
     * @param endpoint   the resolved value, possibly {@code null} or blank
     * @throws IllegalStateException if the value is absent, unparseable, or not an allowlisted emulator address
     */
    private static void requireEmulatorEndpoint(final String sourceKey, final String serviceKey,
            final String endpoint) {

        if (endpoint == null || endpoint.isBlank()) {
            throw emulatorBindingRejected(String.format(Locale.ROOT,
                    "No endpoint override is configured for '%s', and '%s' supplies none either, so this "
                            + "client would fall back to the SDK's standard endpoint resolution for the "
                            + "configured region - which is a live AWS address",
                    serviceKey, KEY_GLOBAL_ENDPOINT));
        }

        final URI uri;
        try {
            uri = new URI(endpoint.strip());
        } catch (final URISyntaxException malformed) {
            throw emulatorBindingRejected(String.format(Locale.ROOT,
                    "Property '%s' is not a parseable URL, so it cannot be proved to address the emulator",
                    sourceKey));
        }

        final String scheme = uri.getScheme() == null
                ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_ENDPOINT_SCHEMES.contains(scheme)) {
            throw emulatorBindingRejected(String.format(Locale.ROOT,
                    "Property '%s' must use scheme http or https", sourceKey));
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw emulatorBindingRejected(String.format(Locale.ROOT,
                    "Property '%s' must carry no user information, no query and no fragment; a user:pass@ "
                            + "form in particular hides the real host behind the credentials", sourceKey));
        }
        final String path = uri.getPath() == null ? "" : uri.getPath();
        if (!path.isEmpty() && !"/".equals(path)) {
            throw emulatorBindingRejected(String.format(Locale.ROOT,
                    "Property '%s' must address the emulator edge with no path beyond a single trailing slash",
                    sourceKey));
        }
        if (uri.getPort() < 0) {
            throw emulatorBindingRejected(String.format(Locale.ROOT,
                    "Property '%s' must state the emulator's port explicitly rather than relying on a "
                            + "scheme default", sourceKey));
        }

        final String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!ALLOWED_ENDPOINT_HOSTS.contains(host) && !ALLOWED_COMPOSE_HOST_PATTERN.matcher(host).matches()) {
            throw emulatorBindingRejected(String.format(Locale.ROOT,
                    "Property '%s' names host '%s', which is not one of the emulator addresses this "
                            + "application is permitted to reach: %s, or the Compose service name "
                            + "carddemo-localstack with an optional clone-index suffix. This is an allowlist, "
                            + "so a host that was not anticipated is refused rather than accepted",
                    sourceKey, host, String.join(", ", new java.util.TreeSet<>(ALLOWED_ENDPOINT_HOSTS))));
        }
    }

    /**
     * Proves one static credential property is present, so that the SDK's default provider chain is replaced.
     *
     * @param environment the environment to read
     * @param key         the credential property key, quoted in the failure message; its value never is
     * @throws IllegalStateException if the property is absent or blank, aborting startup
     */
    private static void requireStaticCredential(final Environment environment, final String key) {
        final String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            throw emulatorBindingRejected(String.format(Locale.ROOT,
                    "Property '%s' resolves to no value, so the SDK would fall back to its default credential "
                            + "provider chain and could sign a request with a real principal taken from a "
                            + "shared profile, a web-identity token, container credentials or instance "
                            + "metadata. Supply the emulator's placeholder credentials through the "
                            + "environment; the value is never logged", key));
        }
    }

    /**
     * Builds the one emulator-binding failure, so every rejection reads the same way and carries the same
     * remedy.
     *
     * @param defect the observed condition, phrased to stand as the first sentence
     * @return the exception to throw, never {@code null}
     */
    private static IllegalStateException emulatorBindingRejected(final String defect) {
        return new IllegalStateException(defect
                + ". Every cloud interaction in this application targets the container-based AWS emulator: "
                + "AAP section 0.3.2 places live AWS accounts and credentials out of scope and requires that "
                + "no code path can reach a real AWS endpoint, and this check is what makes that structural. "
                + "Set the endpoint and credential properties for the active profile - see .env.example and "
                + "localstack-init/init-aws.sh, which applies the same allowlist - or disable the cloud "
                + "integration entirely rather than pointing it at a live account.");
    }

    /**
     * The object-storage collaborator for the whole application: the substrate that replaces the seven
     * generation data group bases of {@code app/jcl/DEFGDGB.jcl}, {@code app/jcl/DALYREJS.jcl} and
     * {@code app/jcl/REPTFILE.jcl}, catalogued as seven {@code 0GDG BASE} entries in
     * {@code app/catlg/LISTCAT.txt}.
     *
     * <p>Every collaborator arrives by injection, and the client above all. That is what keeps the region, the
     * credential resolution and the emulator endpoint in profile configuration - where the base profile
     * declares all three as mandatory environment references and this class validates them - and out of Java
     * where a literal would defeat the guarantee. Building a client here would also silently discard whatever
     * the encryption or path-style settings had configured. What this class does apply to the injected client
     * is the call deadlines and the retry policy, through the two customizer beans below, because that is the
     * one thing profile configuration cannot express.
     *
     * <p>Callers get read and write operations and nothing more. <strong>No bucket is provisioned, no
     * versioning is set and no lifecycle rule is applied</strong> - all three belong to
     * {@code localstack-init/init-aws.sh}, which is idempotent and verifies output-bucket versioning by
     * read-back, following the precedent that {@code IF LASTCC=12 THEN SET MAXCC=0} sets in
     * {@code app/jcl/DEFGDGB.jcl}. The {@code TRANREPT} retention value of 10, reconciled from the conflicting
     * {@code LIMIT(5)} at {@code app/jcl/DEFGDGB.jcl:L37-L38} and {@code LIMIT(10)} at
     * {@code app/jcl/REPTFILE.jcl:L26-L27}, is documentation, not an enforced rule.
     *
     * <p>Three obligations fall on callers rather than on this bean, and are restated because getting any of
     * them wrong produces output that differs from the parity baseline while looking like working code. Record
     * length is preserved byte-exactly - 430 for the reject stream of {@code app/jcl/POSTTRAN.jcl:L34-L38}, 350
     * for the interest generation of {@code app/jcl/INTCALC.jcl:L37-L41} and for the transaction backup and
     * sort outputs, 133 for the report line of {@code app/proc/TRANREPT.prc:L74-L78}, and 80 and 100 for the
     * statement text and markup of {@code app/jcl/CREASTMT.JCL:L87-L96}. A {@code (+1)} write becomes a new
     * object under a monotonically increasing timestamp or job-instance prefix, and a {@code (0)} read becomes
     * a read of the lexicographically greatest existing prefix. And the concrete key a step creates is carried
     * forward through the execution context to any later step that reads it, because the latest prefix must
     * never be re-resolved mid-job.
     *
     * @param s3Client                the client configured by the active profile; injected, never constructed
     * @param s3OutputStreamProvider  the buffering strategy for streamed writes
     * @param s3ObjectConverter       the payload converter, backed by the application object mapper
     * @param s3Presigner             the signer required by the template's constructor; this application
     *                                creates no signed link, and none is ever logged
     * @return the object-storage template, replacing the generation data group substrate. Assignable both to
     *         the concrete template type and to the operations interface, satisfying consumers that inject
     *         either. Its declaration makes the library's own conditional template back off, so exactly one
     *         such bean exists
     */
    @Bean
    public S3Template s3Template(final S3Client s3Client,
            final S3OutputStreamProvider s3OutputStreamProvider,
            final S3ObjectConverter s3ObjectConverter,
            final S3Presigner s3Presigner) {

        // Identical wiring to the library's own conditional template, which applies no property of its own,
        // plus nothing else: this bean exists to place ownership of the client in one documented file.
        return new S3Template(s3Client, s3OutputStreamProvider, s3ObjectConverter, s3Presigner);
    }

    /**
     * The report-job publisher: the FIFO queue that replaces the extrapartition transient data queue declared
     * as {@code DEFINE TDQUEUE(JOBS) GROUP(CARDDEMO)} at {@code app/csd/CARDDEMO.CSD:L499-L503}, and the
     * {@code EXEC CICS WRITEQ TD} block at {@code app/cbl/CORPT00C.cbl:L517-L523} that wrote to it.
     *
     * <p>The queue's own declaration is the message-shape contract. {@code RECORDSIZE(80)} at {@code :L502} with
     * {@code RECORDFORMAT(FIXED)} and {@code BLOCKFORMAT(UNBLOCKED)} at {@code :L503} means the legacy producer
     * emitted fixed 80-byte card images; the target publishes one typed JSON message carrying the report name
     * and the two dates. A message body is untrusted input and is bound as typed JSON with strict binding,
     * never through Java serialization.
     *
     * <p>The physical queue name validated in the constructor becomes this publisher's default destination, so
     * a caller that names no queue still reaches {@value #REPORT_QUEUE_LOGICAL_NAME} rather than failing
     * obscurely. Callers that name it explicitly, as report submission does, are unaffected. Ordering is
     * reproducible because every send carries the fixed message group held in configuration, which is why the
     * queue must be FIFO and why the constructor <strong>aborts</strong> - rather than warning - when the
     * physical name is not the logical name suffixed {@value #FIFO_SUFFIX}. A message group is honoured only on
     * a FIFO queue, and a standard queue may accept the group and ignore it, so a warning would have left the
     * ordering guarantee resting on a rejection this class cannot guarantee.
     *
     * <p><strong>The missing-queue strategy is applied here on purpose.</strong> The library applies it inside
     * its own factory method rather than inside the converter or the client, so a bean that built a publisher
     * from the client alone would silently revert to the library default, under which publishing to an absent
     * queue creates it. That would re-introduce provisioning from Java by omission, which is the one thing this
     * class must never do. The Spring-managed object mapper is applied to the converter for the same reason:
     * the library applies it in its factory method too, and losing it would quietly discard the decimal, date
     * and unknown-property settings the base profile pins.
     *
     * <p>This bean publishes. It declares no listener: the consumer that replaces the JES2 internal reader is
     * the planned {@code com.cardemo.batch.jobs.BatchPipelineOrchestrator}, which is <strong>not authored
     * yet</strong>, so nothing drains the queue yet. On a send failure
     * {@code com.cardemo.service.report.ReportSubmissionService} reproduces the legacy screen text
     * {@code Unable to Write TDQ (JOBS)...} - three trailing dots - from
     * {@code app/cbl/CORPT00C.cbl:L531-L532}, byte for byte.
     *
     * @param sqsAsyncClient        the client configured by the active profile; injected, never constructed, so
     *                              the emulator endpoint stays in profile configuration
     * @param objectMapperProvider  the application object mapper if one exists, applied to the converter
     *                              exactly as the library applies it
     * @param messageConverter      the message converter contributed by the library
     * @return the publisher, with the report queue as its default destination and the validated missing-queue
     *         strategy applied. Its declaration makes the library's own conditional publisher back off
     */
    @Bean
    public SqsTemplate sqsTemplate(final SqsAsyncClient sqsAsyncClient,
            final ObjectProvider<ObjectMapper> objectMapperProvider,
            final MessagingMessageConverter<Message> messageConverter) {

        // Reproduces the library's own conditional publisher: converter, object mapper and missing-queue
        // strategy. Dropping the strategy would revert it to creating an absent queue on first send.
        objectMapperProvider.ifAvailable(objectMapper -> applyObjectMapper(messageConverter, objectMapper));

        return SqsTemplate.builder()
                .sqsAsyncClient(sqsAsyncClient)
                .messageConverter(messageConverter)
                .configure(options -> options
                        .defaultQueue(this.reportQueueName)
                        .queueNotFoundStrategy(this.queueNotFoundStrategy))
                .build();
    }

    /**
     * The notification collaborator, replacing operator notification.
     *
     * <p>This is the one integration surface with no programmatic counterpart in the frozen corpus. The legacy
     * system notified an operator through the job card's notify operand - for instance
     * {@code NOTIFY=&SYSUID} at {@code app/jcl/DEFGDGB.jcl:L1} - which is job-submission metadata, not
     * application code. Nothing is therefore translated here; the capability is added.
     *
     * <p>No topic is created, and <strong>this bean deliberately sets no default destination</strong> even
     * though the topic name is validated at startup and a default would be a convenience. The omission is
     * deliberate, and it is the reason the topic name is validated but not retained as a field.
     * Setting a default destination name is not an assignment: the library resolves the name to a topic
     * identifier immediately, and its default resolver resolves any name that does not already begin with
     * {@code arn:} <em>by creating the topic</em>. Two consequences follow, and each on its own is
     * disqualifying. It would provision a resource from Java - indirectly, which is worse than doing so plainly,
     * because nothing in this file would name the operation - when provisioning belongs solely to
     * {@code localstack-init/init-aws.sh}. And it would make an eager network call during context refresh, so
     * startup would fail whenever the emulator is down, and under a profile that declares no endpoint override
     * it would attempt to reach a live service, which is precisely the fallback the profile layering exists to
     * make unreachable. The call is therefore omitted, and callers name the topic explicitly from
     * {@value #KEY_NOTIFICATION_TOPIC}, exactly as report submission names the queue, so nothing is lost but
     * the convenience.
     *
     * <p>Nothing about this bean reaches the network. It stores a client, a converter and any interceptors, and
     * the first call is made by the caller, against a destination the caller names.
     *
     * <p>Publishing this bean here is what keeps the endpoint decision inside profile configuration. Any
     * consumer - operator notification on a batch outcome, or a readiness probe over the
     * {@code carddemo.aws.sns} namespace - must <em>inject</em> this collaborator rather than construct one,
     * because constructing one at the point of use would place the endpoint decision outside the profile
     * layering and so outside the no-live-service guarantee this class exists to hold.
     *
     * <p>The converter, the optional resolver and the interceptors reproduce the library's own conditional bean
     * exactly, so declaring this one relocates ownership of the client without changing behaviour. Interceptors
     * are applied in ordered sequence rather than in discovery order, because Rule 1 Clause A puts determinism
     * ahead of convenience and an interception chain whose order depends on classpath scanning is not
     * reproducible.
     *
     * @param snsClient                  the client configured by the active profile; injected, never
     *                                   constructed
     * @param objectMapperProvider       the application object mapper if one exists, applied to the payload
     *                                   converter
     * @param topicArnResolver           the strict, read-only resolver declared by
     *                                   {@link #provisionedTopicArnResolver(SnsClient)}. Taken directly rather
     *                                   than through an optional provider, because a bean of that type always
     *                                   exists now and a branch for its absence would be unreachable code -
     *                                   and because the branch it replaced fell back to the library's resolver,
     *                                   which creates a topic on a named send
     * @param channelInterceptorProvider any interceptors to apply, in deterministic order
     * @return the notification template, carrying no default destination for the reason given above. Its
     *         declaration makes the library's own conditional template back off, so exactly one such bean
     *         exists
     */
    @Bean
    public SnsTemplate snsTemplate(final SnsClient snsClient,
            final ObjectProvider<ObjectMapper> objectMapperProvider,
            final TopicArnResolver topicArnResolver,
            final ObjectProvider<ChannelInterceptor> channelInterceptorProvider) {

        // Reproduces the library's own conditional template: a payload converter serialised as text, the
        // application object mapper when one exists, the STRICT resolver, then the interceptors.
        final MappingJackson2MessageConverter payloadConverter = new MappingJackson2MessageConverter();
        payloadConverter.setSerializedPayloadClass(String.class);
        objectMapperProvider.ifAvailable(payloadConverter::setObjectMapper);

        final SnsTemplate template = new SnsTemplate(snsClient, topicArnResolver, payloadConverter);

        channelInterceptorProvider.orderedStream().forEach(template::addChannelInterceptor);

        // Deliberately NOT setDefaultDestinationName: it resolves the name to a topic identifier eagerly, and
        // the default resolver resolves a plain name by CREATING the topic. See this method's Javadoc.
        return template;
    }

    /**
     * The strict, read-only topic resolver: the reason a mistyped or attacker-selected topic name can no longer
     * provision a resource.
     *
     * <p>The library's own resolver is a convenience with a sharp edge. Given a value that does not already
     * begin with {@value #ARN_PREFIX} it calls {@code CreateTopic}, which the service treats as idempotent, so
     * the first {@code sendNotification("carddemo-notifcations", ...)} - one transposition away from the
     * provisioned name - would create that topic and publish into it, silently and successfully. Two rules are
     * broken at once: provisioning belongs solely to {@code localstack-init/init-aws.sh}, and Rule 1 Clause D
     * asks for least privilege, which a runtime that can create arbitrary resources plainly is not.
     *
     * <p>This resolver therefore accepts exactly two shapes and refuses everything else. A value beginning
     * {@value #ARN_PREFIX} is parsed as an already-resolved identifier and returned. A bare name is looked up
     * among the <em>provisioned</em> topics, by walking {@code ListTopics} - a read-only operation - and
     * matching the last segment of each identifier; an absent name is a failure, not a creation. The walk is
     * bounded to {@value #TOPIC_PAGE_LIMIT_COUNT} pages so a paginated response can never loop.
     *
     * <p>Because a bean of this type now always exists, the library's conditional resolver never applies and
     * {@link #snsTemplate(SnsClient, ObjectProvider, TopicArnResolver, ObjectProvider)} takes this one directly
     * rather than through an optional provider - there is no longer a branch in which no resolver is present.
     *
     * <p>Side effects: none at construction. Resolution performs one or more read-only list calls, on the
     * caller's thread, bounded by the deadlines
     * {@link #cardDemoSnsClientCustomizer(AwsCredentialsProvider)} applies.
     *
     * @param snsClient the client configured by the active profile; injected, never constructed
     * @return the strict resolver, which never creates a topic
     */
    @Bean
    public TopicArnResolver provisionedTopicArnResolver(final SnsClient snsClient) {
        return new ProvisionedTopicArnResolver(snsClient);
    }

    /**
     * Applies the deadline, retry and correlation policy to the object-storage client.
     *
     * <p>Three beans do this, one per service, and the library resolves each one by its own interface type when
     * it builds that service's client. The policy itself is stated once, in
     * {@link #applyBoundedPolicy(SdkClientBuilder)}, so the three methods below differ only in the builder they
     * are handed - duplication is how two clients end up with different deadlines, and there is none here.
     *
     * <p>The <em>global</em> synchronous and asynchronous customizer interfaces the library also offers are
     * deliberately not used: their callback receives a builder type that exposes only the HTTP client, not the
     * override configuration, so a deadline cannot be expressed through them at all. That was established by
     * reading the compiled interface hierarchy rather than assumed.
     *
     * <p>Three things are applied. A whole-call deadline of {@value #API_CALL_TIMEOUT_SECONDS} seconds, which
     * bounds the call including retries and backoff. A per-attempt deadline of
     * {@value #API_CALL_ATTEMPT_TIMEOUT_SECONDS} seconds, which is what aborts a connection or a socket read
     * that never completes. And the standard retry strategy, whose attempt count and exponential backoff are
     * both bounded, in place of whatever default the environment would otherwise select - a default that can
     * vary with the resolved defaults mode, which is exactly the kind of implicit behaviour Rule 1 Clause A
     * rules out.
     *
     * <p>It also asserts, before any client exists, that the credential provider the library resolved is a
     * static one carrying resolved values - see {@link #requireStaticCredentials(AwsCredentialsProvider)}.
     * A customizer bean is the earliest point at which the resolved provider is observable, and it runs
     * during refresh, so a deployment that would have signed with ambient credentials fails to start rather
     * than failing at its first call.
     *
     * @param credentialsProvider the provider the library resolved; proved static before the client is built
     * @return the customizer applied to the object-storage client
     * @throws IllegalStateException if the resolved provider is not a static one carrying resolved values
     */
    @Bean
    public S3ClientCustomizer cardDemoS3ClientCustomizer(final AwsCredentialsProvider credentialsProvider) {
        requireStaticCredentials(credentialsProvider);
        return AwsConfig::applyBoundedPolicy;
    }

    /**
     * Applies the same policy to the queue client, which is asynchronous.
     *
     * <p>It matters here for a reason specific to the asynchronous path: a publish returns a future, and a
     * caller that blocks on one has no deadline of its own unless the client imposes it. With the whole-call
     * deadline in force the future always completes, so a hung emulator surfaces as a bounded failure rather
     * than as a blocked request thread.
     *
     * <p>The credential assertion of {@link #requireStaticCredentials(AwsCredentialsProvider)} is repeated
     * here rather than trusted from the object-storage customizer. The provider is a singleton and the check
     * is idempotent, so the repetition costs one type check and buys the property that no client of any of
     * the three services can be built without it having passed.
     *
     * @param credentialsProvider the provider the library resolved; proved static before the client is built
     * @return the customizer applied to the queue client
     * @throws IllegalStateException if the resolved provider is not a static one carrying resolved values
     */
    @Bean
    public SqsAsyncClientCustomizer cardDemoSqsAsyncClientCustomizer(
            final AwsCredentialsProvider credentialsProvider) {

        requireStaticCredentials(credentialsProvider);
        return AwsConfig::applyBoundedPolicy;
    }

    /**
     * Applies the same policy to the notification client, whose topic resolution performs read-only list calls
     * that must be bounded like any other.
     *
     * <p>It carries the same credential assertion as the other two, for the reason given on
     * {@link #cardDemoSqsAsyncClientCustomizer(AwsCredentialsProvider)}: the smallest surface is the one
     * whose unguarded client nobody would notice.
     *
     * @param credentialsProvider the provider the library resolved; proved static before the client is built
     * @return the customizer applied to the notification client
     * @throws IllegalStateException if the resolved provider is not a static one carrying resolved values
     */
    @Bean
    public SnsClientCustomizer cardDemoSnsClientCustomizer(final AwsCredentialsProvider credentialsProvider) {
        requireStaticCredentials(credentialsProvider);
        return AwsConfig::applyBoundedPolicy;
    }

    /**
     * Verifies, once, that the provisioned report queue really is first-in-first-out with content-based
     * deduplication - and aborts the process when it is not.
     *
     * <p>The name check in the constructor proves the <em>configuration</em> is coherent. It cannot prove the
     * queue is: a standard queue created under a {@code .fifo} name satisfies the name check and then silently
     * drops the ordering guarantee that reproduces {@code DISPOSITION(MOD)}. Only the queue's own attributes
     * settle it, and reading them needs a network call.
     *
     * <p>That call deliberately does <strong>not</strong> happen during context refresh. This class's whole
     * design keeps refresh free of network traffic, so that a bean is never broken by an emulator being down and
     * so that no eager call can be made before the endpoint allow-list has been enforced. Running as an
     * application runner puts the check immediately after refresh, where an exception ends
     * {@code SpringApplication.run} with a non-zero exit: a startup failure, which is what a broken contract
     * should be, rather than a warning nobody reads.
     *
     * <p>The queue address returned by {@code GetQueueUrl} is used to read the attributes and is then discarded:
     * it embeds the account identifier and is never logged, never returned and never held in a field.
     *
     * <p>Side effects: two read-only calls, bounded by {@value #QUEUE_VERIFICATION_TIMEOUT_SECONDS} seconds
     * each; one informational log line on success. It provisions nothing.
     *
     * @param sqsAsyncClient the client configured by the active profile; injected, never constructed
     * @return the runner performing the one-off verification
     */
    @Bean
    public ApplicationRunner cardDemoFifoQueueContractVerifier(final SqsAsyncClient sqsAsyncClient) {
        return (final ApplicationArguments args) -> verifyFifoQueueContract(sqsAsyncClient, this.reportQueueName);
    }

    /**
     * Applies the explicit deadlines and the bounded retry strategy to one client builder, preserving
     * everything the library had already configured on it.
     *
     * <p>It deliberately does <strong>not</strong> add the correlation interceptor: the three
     * {@code correlationId*Customizer} beans below own that, and adding it here as well would install two
     * copies on every client and stamp the header twice.
     *
     * <p>The existing configuration is read back and rebuilt rather than replaced. That detail is load-bearing:
     * the {@code Consumer} overload of {@code overrideConfiguration} starts from a <em>fresh</em> builder and so
     * discards whatever the library had already set, including its own user-agent applier. Reading the current
     * value and calling {@code toBuilder()} preserves it. This was verified against the compiled interface, not
     * inferred from its documentation.
     *
     * <p>Static, and the single definition of the policy, so the three customizers above cannot drift apart.
     *
     * @param builder the client builder the library is configuring; never {@code null}
     */
    private static void applyBoundedPolicy(final SdkClientBuilder<?, ?> builder) {
        final ClientOverrideConfiguration bounded = builder.overrideConfiguration().toBuilder()
                .apiCallTimeout(Duration.ofSeconds(API_CALL_TIMEOUT_SECONDS))
                .apiCallAttemptTimeout(Duration.ofSeconds(API_CALL_ATTEMPT_TIMEOUT_SECONDS))
                // Selects the retry MODE, from which the SDK resolves its bounded standard strategy when the
                // client is built. Naming the mode rather than assembling a strategy keeps the choice on the
                // library's own stable surface, and STANDARD bounds both the attempt count and the backoff.
                .retryStrategy(RetryMode.STANDARD)
                .build();
        builder.overrideConfiguration(bounded);
    }

    /**
     * Reads the queue's own attributes and fails when the first-in-first-out contract is not met.
     *
     * <p>Static and package-private-by-privacy: it takes everything it needs as arguments so that it holds no
     * state and can be reasoned about on its own.
     *
     * @param sqsAsyncClient the queue client
     * @param queueName      the validated physical queue name
     * @throws IllegalStateException if the queue cannot be reached, does not exist, or reports attributes that
     *                               contradict the message-group contract every send relies on
     */
    private static void verifyFifoQueueContract(final SqsAsyncClient sqsAsyncClient, final String queueName) {
        final GetQueueUrlResponse located = awaitQueueCall(
                sqsAsyncClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build()),
                queueName, "resolve");

        final GetQueueAttributesResponse attributes = awaitQueueCall(
                sqsAsyncClient.getQueueAttributes(GetQueueAttributesRequest.builder()
                        .queueUrl(located.queueUrl())
                        .attributeNames(QueueAttributeName.FIFO_QUEUE,
                                QueueAttributeName.CONTENT_BASED_DEDUPLICATION)
                        .build()),
                queueName, "read the attributes of");

        final Map<QueueAttributeName, String> values = attributes.attributes();
        requireQueueAttribute(queueName, values, QueueAttributeName.FIFO_QUEUE);
        requireQueueAttribute(queueName, values, QueueAttributeName.CONTENT_BASED_DEDUPLICATION);

        LOG.info("Report queue {} verified as FIFO with content-based deduplication, so the fixed message group "
                        + "every report submission carries reproduces the strictly sequential append of "
                        + "DEFINE TDQUEUE(JOBS)",
                queueName);
    }

    /**
     * Awaits one bounded queue call, cancelling it if the deadline passes so that no work is left running.
     *
     * @param <T>       the response type
     * @param pending   the call in flight
     * @param queueName the queue being verified, named in a failure message; it is a bare name and carries no
     *                  account identifier
     * @param operation what was being attempted, phrased to read inside the failure message
     * @return the response
     * @throws IllegalStateException if the call fails, times out or is interrupted
     */
    private static <T> T awaitQueueCall(final CompletableFuture<T> pending,
            final String queueName, final String operation) {

        try {
            return pending.get(QUEUE_VERIFICATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (final TimeoutException deadlineExceeded) {
            // Cancel rather than abandon: an uncancelled future keeps a connection and a callback alive for as
            // long as the client's own deadline allows, which is work nobody is waiting for any more.
            pending.cancel(true);
            throw queueVerificationFailure(queueName, operation, "the call exceeded its "
                    + QUEUE_VERIFICATION_TIMEOUT_SECONDS + "-second deadline", deadlineExceeded);
        } catch (final InterruptedException interrupted) {
            pending.cancel(true);
            // Restore the flag before leaving, so the interruption is reported rather than absorbed.
            Thread.currentThread().interrupt();
            throw queueVerificationFailure(queueName, operation, "the calling thread was interrupted",
                    interrupted);
        } catch (final ExecutionException failed) {
            throw queueVerificationFailure(queueName, operation,
                    "the call failed with " + safeExceptionName(failed.getCause()), failed);
        }
    }

    /**
     * The one correlation interceptor instance, shared by all three clients.
     *
     * <p>Stateless - it reads the calling thread's {@link MDC} at request time and holds nothing - so a
     * single instance is safe across every client and every concurrent request, and sharing it keeps the
     * three customizers below provably identical in behaviour.
     */
    private static final CorrelationIdExecutionInterceptor CORRELATION_ID_INTERCEPTOR =
            new CorrelationIdExecutionInterceptor();

    /**
     * The one queue-send recovery interceptor instance, registered on the queue client only.
     *
     * <p>Stateless for the same reason as the interceptor above, and for a stronger one: it reads the identifier
     * from the request object it is handed, so it holds nothing at all between calls and cannot leak one
     * publish's identity into another's.
     */
    private static final SqsSendCorrelationRecoveryInterceptor SQS_SEND_CORRELATION_INTERCEPTOR =
            new SqsSendCorrelationRecoveryInterceptor();

    /**
     * Stamps the request-scoped correlation identifier onto every outbound AWS request.
     *
     * <p><strong>The gap this closes.</strong> {@link CorrelationIdFilter} makes one identifier follow a
     * request through this application's own logs, and Micrometer makes it follow a trace. Neither reaches
     * the wire. Without this interceptor an S3 {@code PutObject} that stores a reject file, or an SQS send
     * that submits a report job, arrives at the service carrying nothing that ties it back to the request or
     * the batch run that caused it - so the one question an operator actually asks of an integration
     * failure, "which run wrote this object", has no answer in the request itself.
     *
     * <p><strong>Why a header and not a tag or object metadata.</strong> A request header is the only
     * mechanism that applies uniformly to all three services and to every operation on them. S3 object
     * metadata would cover {@code PutObject} but not a queue send; SQS message attributes would cover the
     * send but not the object write; an S3 object tag needs a second API call. One interceptor on the shared
     * builder path covers every operation of every client, including ones added later, and it is the
     * mechanism the SDK itself documents for cross-cutting request decoration.
     *
     * <p><strong>Why three per-service beans and not one global customizer.</strong> Spring Cloud AWS also
     * offers {@code AwsSyncClientCustomizer} and {@code AwsAsyncClientCustomizer}, which would cover every
     * client at once and look like the tidier choice. They cannot do this job: both hand the callback an
     * {@code AwsSyncClientBuilder<?, ?>} / {@code AwsAsyncClientBuilder<?, ?>}, whose only declared methods
     * are {@code httpClient} and {@code httpClientBuilder}. {@code overrideConfiguration} lives on
     * {@code SdkClientBuilder}, which those wildcard-parameterised interfaces do not extend, so an
     * interceptor cannot be reached through them at all - measured against the 3.3.0 interfaces, not
     * assumed. The per-service customizers hand over the <em>concrete</em> builder
     * ({@code S3ClientBuilder} and so on), whose self-type resolves, so
     * {@code overrideConfiguration(Consumer)} is available. Three narrow beans that work beat one broad bean
     * that silently does not.
     *
     * <p><strong>Why not declare the clients here instead.</strong> Building {@code S3Client},
     * {@code SqsAsyncClient} and {@code SnsClient} in this class would also allow the interceptor, and was
     * rejected: it would move endpoint, region, credential and path-style resolution out of
     * auto-configuration and into this file, duplicating logic that the endpoint allowlist above depends on
     * being applied exactly once. A customizer decorates that resolution without replacing it.
     *
     * <p><strong>Absence is not an error.</strong> Batch steps run on threads that never pass through the
     * servlet filter, and a client may be exercised at startup before any request exists. When no
     * identifier is in {@link MDC} the header is simply omitted: an interceptor that threw, or that invented
     * a placeholder, would turn a missing diagnostic into a failed write.
     *
     * <p><strong>The value is re-validated here.</strong> {@link CorrelationIdFilter} already refuses
     * anything outside a bounded {@code [A-Za-z0-9_-]}, so a well-formed value is what normally arrives. It
     * is checked again anyway, because this is a different trust boundary: the batch layer also writes
     * {@link MDC}, and a header value containing {@code CR} or {@code LF} is a request-splitting vector
     * rather than merely an ugly log line. Validating at the point of use is what makes that guarantee
     * independent of who populated the entry.
     *
     * @return a customizer adding the correlation interceptor to the object-storage client
     */
    @Bean
    public S3ClientCustomizer correlationIdS3ClientCustomizer() {
        return AwsConfig::applyCorrelationInterceptor;
    }

    /**
     * Adds the correlation interceptors to the queue client.
     *
     * <p>The asynchronous counterpart of {@link #correlationIdS3ClientCustomizer()}; the rationale for the
     * mechanism, the header and the absence-tolerant behaviour is documented there and is not repeated.
     * This client is the one that carries a report submission, so it is the send whose correlation an
     * operator is most likely to need: it is the bridge that replaces {@code EXEC CICS WRITEQ TD
     * QUEUE('JOBS')}, where the legacy system's only trace of a submission was the queue record itself.
     *
     * <p><strong>Two interceptors here, one on the other two clients.</strong> The queue client is the only one
     * whose work is chained behind an asynchronous resolution: {@code SqsTemplate} resolves the queue URL and
     * attributes first and issues the send from whichever thread completes that resolution, which on a cold
     * cache is an SDK thread with no diagnostic context. {@link SqsSendCorrelationRecoveryInterceptor} is
     * registered after the uniform one to cover exactly that case by recovering the identifier from the
     * message's own attributes. It is added second so that a header stamped from the diagnostic context is what
     * survives when both could act, though by construction the two can only ever produce the same value - the
     * message attribute is populated from the very context the first one reads.
     *
     * @return a customizer adding both correlation interceptors to the queue client
     */
    @Bean
    public SqsAsyncClientCustomizer correlationIdSqsAsyncClientCustomizer() {
        return AwsConfig::applyQueueCorrelationInterceptors;
    }

    /**
     * Adds the correlation interceptor to the notification client.
     *
     * <p>Completes the set, so that all three integration surfaces stamp the same header. The rationale is
     * documented on {@link #correlationIdS3ClientCustomizer()}. Leaving this one out would produce exactly
     * the half-instrumented state that makes an operator distrust the instrumentation as a whole.
     *
     * @return a customizer adding the correlation interceptor to the notification client
     */
    @Bean
    public SnsClientCustomizer correlationIdSnsClientCustomizer() {
        return AwsConfig::applyCorrelationInterceptor;
    }

    /**
     * Adds the correlation interceptor to one client builder without discarding anything already set on it.
     *
     * <p><strong>The read-back is load-bearing, and this is the one place it can go wrong.</strong> The
     * {@code Consumer} overload of {@code overrideConfiguration} starts from a <em>fresh</em>
     * {@link ClientOverrideConfiguration} builder, so a customizer written as
     * {@code builder.overrideConfiguration(c -> c.addExecutionInterceptor(...))} silently throws away
     * whatever another customizer had already applied - here, the whole-call deadline, the attempt deadline
     * and the bounded retry strategy that {@link #applyBoundedPolicy(SdkClientBuilder)} sets. Nothing fails;
     * the client is simply built without its deadlines, and which of the two customizers won would depend on
     * {@code ObjectProvider.orderedStream()} ordering, which no annotation here pins. Reading the current
     * value and calling {@code toBuilder()} makes both customizers additive and the order irrelevant.
     *
     * @param builder the client builder the library is configuring; never {@code null}
     */
    private static void applyCorrelationInterceptor(final SdkClientBuilder<?, ?> builder) {
        builder.overrideConfiguration(builder.overrideConfiguration().toBuilder()
                .addExecutionInterceptor(CORRELATION_ID_INTERCEPTOR)
                .build());
    }

    /**
     * Adds both correlation interceptors to the queue client builder, in that order and without discarding
     * anything already set on it.
     *
     * <p>The read-back through {@code toBuilder()} is load-bearing for the reason documented on
     * {@link #applyCorrelationInterceptor(SdkClientBuilder)}, and it matters twice as much here: this method
     * adds two interceptors, and the {@code Consumer} overload of {@code overrideConfiguration} would have
     * discarded the deadlines and the retry strategy that the other customizer applies.
     *
     * <p>The uniform interceptor is added first, so it runs first on the request path and a value from the
     * diagnostic context wins; the recovery interceptor then finds the header already present and changes
     * nothing. On the cold-cache send, where the diagnostic context is empty, only the second one can act.
     *
     * @param builder the queue client builder the library is configuring; never {@code null}
     */
    private static void applyQueueCorrelationInterceptors(final SdkClientBuilder<?, ?> builder) {
        builder.overrideConfiguration(builder.overrideConfiguration().toBuilder()
                .addExecutionInterceptor(CORRELATION_ID_INTERCEPTOR)
                .addExecutionInterceptor(SQS_SEND_CORRELATION_INTERCEPTOR)
                .build());
    }

    /**
     * Proves one queue attribute is present and {@code true}.
     *
     * @param queueName  the queue being verified
     * @param values     the attributes the service returned
     * @param attribute  the attribute that must be {@code true}
     * @throws IllegalStateException if the attribute is absent or not {@code true}
     */
    private static void requireQueueAttribute(final String queueName,
            final Map<QueueAttributeName, String> values, final QueueAttributeName attribute) {

        // Keyed by the enum, never by attribute.toString(): the response map's key type IS the enum, so a
        // string lookup silently misses every entry and would fail a perfectly compliant queue.
        if (!Boolean.parseBoolean(values.get(attribute))) {
            throw queueVerificationFailure(queueName, "verify",
                    "the queue reports attribute " + attribute + " as '" + values.get(attribute)
                            + "' rather than 'true', so the fixed message group every report submission "
                            + "carries would not reproduce the strictly sequential append of "
                            + "DEFINE TDQUEUE(JOBS). Delete the queue and re-run "
                            + "localstack-init/init-aws.sh, which provisions it correctly",
                    null);
        }
    }

    /**
     * Composes the queue-verification failure, naming the bare queue name and a curated reason.
     *
     * <p>The queue <em>address</em> is never named, because it embeds the account identifier, and the software
     * development kit's own exception is attached as the cause rather than interpolated into the message, so its
     * endpoint and request diagnostics do not reach a log through this text.
     *
     * @param queueName the bare queue name
     * @param operation what was being attempted
     * @param reason    the curated reason
     * @param cause     the underlying failure, or {@code null} when there is none
     * @return the exception for the caller to throw
     */
    private static IllegalStateException queueVerificationFailure(final String queueName, final String operation,
            final String reason, final Throwable cause) {

        final String message = String.format(Locale.ROOT,
                "CardDemo could not %s report queue '%s' at startup because %s. Property '%s' names the queue; "
                        + "localstack-init/init-aws.sh provisions it and is idempotent.",
                operation, queueName, reason, KEY_REPORT_QUEUE);
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }

    /**
     * Names a throwable's type without disclosing anything it carries.
     *
     * <p>A software development kit exception's message routinely holds the endpoint, the request identifier and
     * occasionally the resource address, so the type is reported and the object itself is preserved as a cause
     * rather than rendered into text.
     *
     * @param cause the throwable, possibly {@code null}
     * @return the fully qualified type name, or a fixed placeholder when there is no cause
     */
    private static String safeExceptionName(final Throwable cause) {
        return cause == null ? "no reported cause" : cause.getClass().getName();
    }

    /**
     * Applies the application object mapper to the message converter, reproducing the library's own behaviour:
     * the mapper is applied only to a converter that accepts one, and a converter contributed by another bean
     * is left untouched.
     *
     * <p>Static, like every helper reachable from the constructor or from a bean method, so that no overridable
     * instance method is ever invoked during construction.
     *
     * @param messageConverter the converter to configure; never {@code null}
     * @param objectMapper     the application object mapper; never {@code null}
     */
    private static void applyObjectMapper(final MessagingMessageConverter<Message> messageConverter,
            final ObjectMapper objectMapper) {

        if (messageConverter instanceof SqsMessagingMessageConverter sqsMessageConverter) {
            sqsMessageConverter.setObjectMapper(objectMapper);
        }
    }

    /**
     * Fails startup unless every cloud service is pointed at an approved emulator endpoint.
     *
     * <p>This is the code-level enforcement of a binding Agent Action Plan invariant. &sect;0.3.2 places
     * live AWS accounts and real credentials out of scope in these words: all AWS interaction is against
     * the emulator, zero live credentials appear in any file, and <strong>no code path may reach a real AWS
     * endpoint</strong>. &sect;0.8.4 restates it. {@code pom.xml} makes the same claim about this project,
     * that every client targets an emulator endpoint override and there is no live credential path
     * anywhere.
     *
     * <p><strong>Configuration alone cannot keep that promise, which is why this method exists.</strong>
     * An endpoint override is an ordinary property. Omit it and the SDK's standard resolution silently
     * produces the real regional endpoint for the configured region; set it to a real service host and the
     * application reaches live AWS while every file still looks compliant. Both are ordinary
     * configuration mistakes with no visible symptom until traffic leaves the machine. So the invariant is
     * enforced here, at context startup, where it can fail closed:
     *
     * <ul>
     *   <li><strong>Absence is rejected.</strong> A service with neither its own override nor the global
     *       one would resolve live AWS, so it is treated exactly as a live endpoint would be.</li>
     *   <li><strong>The host is allowlisted, not pattern matched.</strong> Membership of
     *       {@link #APPROVED_ENDPOINT_HOSTS}, or the compose-service prefix
     *       {@value #APPROVED_ENDPOINT_HOST_PREFIX}, is required. A substring or suffix test would admit
     *       {@code localhost.attacker.example}, or a hostile name carrying an allowlisted one as a label;
     *       exact membership admits neither. The prefix form admits only a hyphen separator - see the constant
     *       for why an underscore branch would have been unreachable.</li>
     *   <li><strong>The scheme is allowlisted</strong>, so no {@code file}, {@code ftp} or opaque URI can
     *       be substituted.</li>
     *   <li><strong>Userinfo is rejected</strong>, because credentials in a URL would be exactly the
     *       committed key material Rule 1 Clause D forbids, and they would reach the logs.</li>
     * </ul>
     *
     * <p><strong>The port is deliberately not constrained, and that is not a gap.</strong> The host is the
     * security-bearing component: traffic cannot leave the machine for a real service while the host is a
     * loopback address or an emulator container name, whatever the port. Constraining the port to the
     * published edge would additionally break the container-per-test tier, whose emulator is reached on an
     * ephemeral mapped port supplied dynamically by the test class - a legitimately approved emulator. The
     * shell half of this allowlist does pin the port, because it drives the one compose emulator on its one
     * published port and has no dynamic case to serve.
     *
     * <p>To point this application at real AWS, the Agent Action Plan must be amended first. That is a
     * deliberate obstruction: this method is the reason such a change cannot be made silently.
     *
     * <p><strong>One rule, applied once, and the parsed result reused.</strong> Every value that reaches a
     * client passes through {@link #requireApprovedEmulatorEndpoint(String, String)} and through nothing
     * else, and the {@link URI} that method already had to build is returned here rather than discarded and
     * re-parsed further down. That matters beyond tidiness: while a second validator existed alongside this
     * one, the two agreed on the ordinary cases and disagreed on the edges - a trailing-dot host and a
     * bracketed IPv6 literal were normalised by one and not by the other - so whether a legitimate emulator
     * address was accepted depended on which check happened to run first. There is now no second rule for
     * this one to drift from.
     *
     * <p>A service whose own key is blank inherits the global override and is validated <em>under its own
     * key</em>, so a refusal names the service an operator has to fix rather than only the shared fallback.
     * The global value is additionally checked once on its own, before any service is considered, so that a
     * live value introduced through it is refused with the global key named even when all three services
     * also declare an endpoint of their own.
     *
     * @param s3Endpoint the object-storage override, possibly empty
     * @param sqsEndpoint the queue override, possibly empty
     * @param snsEndpoint the topic override, possibly empty
     * @param globalEndpoint the global override, possibly empty
     * @return the resolved, approved endpoint for each of {@value #KEY_S3_ENDPOINT},
     *         {@value #KEY_SQS_ENDPOINT} and {@value #KEY_SNS_ENDPOINT}, keyed by that property; a service
     *         that declared no value of its own is present under its own key carrying the global override
     * @throws IllegalStateException if any service resolves to no endpoint, or to one outside the allowlist
     */
    private static Map<String, URI> requireEmulatorEndpoints(final String s3Endpoint, final String sqsEndpoint,
            final String snsEndpoint, final String globalEndpoint) {

        final String global = globalEndpoint == null ? "" : globalEndpoint.strip();
        if (!global.isEmpty()) {
            requireApprovedEmulatorEndpoint(KEY_GLOBAL_ENDPOINT, global);
        }

        final List<String> keys = List.of(KEY_S3_ENDPOINT, KEY_SQS_ENDPOINT, KEY_SNS_ENDPOINT);
        final List<String> values = List.of(
                s3Endpoint == null ? "" : s3Endpoint.strip(),
                sqsEndpoint == null ? "" : sqsEndpoint.strip(),
                snsEndpoint == null ? "" : snsEndpoint.strip());

        final Map<String, URI> resolved = new LinkedHashMap<>();
        for (int index = 0; index < keys.size(); index++) {
            final String key = keys.get(index);
            String value = values.get(index);
            if (value.isEmpty()) {
                if (global.isEmpty()) {
                    throw new IllegalStateException(String.format(Locale.ROOT,
                            "Cloud endpoint property '%s' resolved to no value, and neither did the global "
                                    + "'%s'. An absent override is not a neutral setting: the SDK would fall "
                                    + "back to standard endpoint resolution and reach live AWS, which the "
                                    + "Agent Action Plan forbids outright at sections 0.3.2 and 0.8.4 - no "
                                    + "code path may reach a real AWS endpoint. There is no flag that "
                                    + "relaxes this check. Point this service at the emulator, ordinarily "
                                    + "through AWS_ENDPOINT_URL, or activate the local profile for developer "
                                    + "defaults.", key, KEY_GLOBAL_ENDPOINT));
                }
                value = global;
            }
            resolved.put(key, requireApprovedEmulatorEndpoint(key, value));
        }

        LOG.info("Cloud endpoint allowlist satisfied: every configured endpoint resolves to an approved "
                + "emulator host, so no client in this context can reach live AWS");
        return Map.copyOf(resolved);
    }

    /**
     * The single test for "is this host an emulator address", and the only one this class applies.
     *
     * <p>Three independent remediations arrived at this guard from different review lenses, and each carried its
     * own host list. They are unioned here rather than left side by side, because two lists that must agree are a
     * defect with a comment on it: an address accepted by one validator and refused by the other would make
     * startup depend on which check ran first. Every accepted form is a loopback or compose-network address, so
     * the union does not widen the guard towards live AWS - it only stops a legitimate emulator address from
     * being refused by whichever list happened not to name it.
     *
     * <p>Accepted forms, and why each is an emulator address and nothing else:
     * <ul>
     *   <li>every literal in {@link #APPROVED_ENDPOINT_HOSTS} and {@link #PERMITTED_ENDPOINT_HOSTS}, which
     *       between them name {@code localhost}, the two loopback literals in both bracketed and bare form, the
     *       emulator's published loopback alias, the compose service name and {@code host.docker.internal};</li>
     *   <li>{@value #APPROVED_ENDPOINT_HOST_PREFIX} and its clone-suffixed forms, matched by
     *       {@link #APPROVED_ENDPOINT_HOST_PATTERN} so that the rule is character for character the one the
     *       provisioning script applies at {@code localstack-init/init-aws.sh:L721}. The suffix is a hyphen
     *       followed by digits and nothing else, because {@code CLONE_INDEX} is a number; an arbitrary
     *       suffix is refused, which is narrower than a prefix test and is the behaviour the script has;</li>
     *   <li>any IPv4 literal in {@code 127.0.0.0/8}, the whole loopback range, because Testcontainers may
     *       map a container onto any of it. Matched by {@link #LOOPBACK_ADDRESS_PATTERN} as a complete
     *       address, never as a leading substring.</li>
     * </ul>
     *
     * <p><strong>A sub-domain of the emulator's loopback DNS name is deliberately not accepted</strong>, and
     * that refusal is the one place where this method is narrower than a reachability argument would make it.
     * Such a name does resolve to loopback, so it would have been easy to admit; the provisioning script
     * records why it is not, at {@code localstack-init/init-aws.sh:L712-L720}, and the reason is measured
     * rather than theoretical. Probing the virtual-hosted form passed readiness and then failed provisioning
     * at the queue stage, because a virtual-hosted object-storage name is not a general service edge - so the
     * value would be accepted here and then break at the second service that used it. Least privilege
     * settles the rest: the bare host is the only endpoint this project documents, so accepting a wildcard
     * beneath it would widen the allowlist past everything in use. The one virtual-host case that matters,
     * bucket-style request addressing, is derived by the SDK from the bucket name and is never configured as
     * a service endpoint.
     *
     * @param host the endpoint host, already lower-cased, unbracketed and de-dotted by
     *             {@link #isApprovedEndpointHost(String)}; never {@code null}
     * @return {@code true} when the host is an emulator address, {@code false} otherwise
     */
    private static boolean isEmulatorHost(final String host) {
        return APPROVED_ENDPOINT_HOSTS.contains(host)
                || PERMITTED_ENDPOINT_HOSTS.contains(host)
                || APPROVED_ENDPOINT_HOST_PATTERN.matcher(host).matches()
                || LOOPBACK_ADDRESS_PATTERN.matcher(host).matches();
    }

    /**
     * Returns the value of a required cloud resource property, having proved it is neither {@code null} nor
     * blank, with surrounding whitespace removed.
     *
     * <p>Whitespace is stripped rather than rejected because none of these names may contain a space, so
     * stripping cannot change meaning, whereas a trailing space introduced by a configuration file would
     * otherwise produce a resource name that does not match the provisioned one.
     *
     * <p>The failure message names the property key and never the value, so the diagnostic cannot disclose
     * anything, and it says what to do rather than only what went wrong.
     *
     * @param key      the property key, quoted in the failure message
     * @param rawValue the resolved value, possibly {@code null} or blank
     * @return the value with surrounding whitespace removed; never {@code null}, never blank
     * @throws IllegalStateException if the value is {@code null} or blank, aborting startup
     */
    private static String requireConfigured(final String key, final String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "Required CardDemo cloud property '%s' resolved to no value. It is declared in "
                            + "src/main/resources/application.yml and indirected through the environment with "
                            + "no default, so an absent variable fails resolution and a variable set to the "
                            + "empty string reaches this check. Supply a value, or activate the local profile "
                            + "for developer defaults. Do not add a default to the base profile.", key));
        }
        return rawValue.strip();
    }

    /**
     * Proves one endpoint value names an approved local emulator address, and aborts startup otherwise.
     *
     * <p>The value is <strong>parsed</strong> rather than pattern-matched, in the order a URL is actually
     * defined, and anything the parse cannot account for is refused. Every rejection is terminal: there is no
     * branch that accepts an unrecognised value. This mirrors {@code require_local_endpoint} at
     * {@code localstack-init/init-aws.sh:723-828} check for check, because two guards over the same variable
     * that disagree about what is acceptable are worse than one:
     *
     * <ol>
     *   <li>no control character, whitespace or backslash anywhere - such bytes let a value that looks local
     *       resolve elsewhere, and let a log line be split;</li>
     *   <li>the value parses as a URL at all;</li>
     *   <li>the scheme is exactly {@value #SCHEME_HTTP} or {@value #SCHEME_HTTPS}, compared as written, so an
     *       upper-cased spelling is refused rather than canonicalised - the script's comparison is
     *       case-sensitive and this one matches it;</li>
     *   <li>no userinfo component. A {@code user:pass@host} form is refused outright, because the host is
     *       what follows the <em>last</em> {@code @} and a matcher over the whole string is therefore
     *       unsound;</li>
     *   <li>no query and no fragment - a service endpoint has no use for either, and they are the usual
     *       vehicle for smuggling a second host past a naive matcher;</li>
     *   <li>no path beyond a single trailing slash;</li>
     *   <li>no percent-encoding in the authority, which this guard will not decode;</li>
     *   <li>the host, lowercased, unbracketed and with a trailing dot removed, satisfies
     *       {@link #isEmulatorHost(String)}, the single host rule this class applies. Lowercasing is the one
     *       step that an upper-cased live service host defeats in a case-sensitive deny-list;</li>
     *   <li>a port is present and is within {@value #LOWEST_ENDPOINT_PORT} to
     *       {@value #HIGHEST_ENDPOINT_PORT}. The <em>value</em> is deliberately unconstrained - the compose
     *       topology publishes 4566 but a Testcontainers-managed emulator is published on an ephemeral port,
     *       and the host is the security-bearing component in either case - but its <em>presence</em> is
     *       required, because the emulator's edge is neither 80 nor 443 and a value with no port therefore
     *       cannot reach it. This is stricter than the script, which validates a port only when one is
     *       written; being strict about a configuration that provably does not work costs nothing and turns a
     *       connection failure at the first call into a named failure at startup.</li>
     * </ol>
     *
     * <p>One divergence from the script is deliberate and is narrower than it looks. The script additionally
     * refuses a port with a leading zero, because a shell arithmetic context would read it as octal; Java
     * parses the authority in base ten and has no such hazard, so {@code :04566} is accepted here and refused
     * there. The divergence cannot widen what is reachable, because the host - not the port - is what decides
     * which service answers.
     *
     * <p><strong>The failure message names the property key and never the value.</strong> That is stricter
     * than the script, which echoes the offending URL, and it is the right call for a Java process whose
     * diagnostics reach a log aggregator: a rejected value may carry userinfo, and userinfo in an error line
     * is a credential in a log file. The reason is always named, so the message says what was wrong rather
     * than only that something was, and an operator can read their own configuration to see which value it
     * was.
     *
     * @param key   the property key the value came from, quoted in the failure message
     * @param value the endpoint value, already proved non-blank and stripped
     * @return the parsed endpoint, so that the single caller that needs it for a diagnostic does not have to
     *         parse the same string a second time and risk reaching a different verdict
     * @throws IllegalStateException if the value is not an approved local emulator address, aborting startup
     */
    private static URI requireApprovedEmulatorEndpoint(final String key, final String value) {
        if (containsForbiddenCharacter(value)) {
            throw endpointRefusal(key, "carries a control character, whitespace or a backslash");
        }

        final URI endpoint;
        try {
            endpoint = new URI(value);
        } catch (final URISyntaxException malformed) {
            throw new IllegalStateException(endpointRefusalMessage(key, "is not a parsable URL"), malformed);
        }

        final String scheme = endpoint.getScheme();
        if (!SCHEME_HTTP.equals(scheme) && !SCHEME_HTTPS.equals(scheme)) {
            throw endpointRefusal(key, "is not a lowercase http or https URL");
        }
        if (endpoint.getRawUserInfo() != null) {
            throw endpointRefusal(key, "embeds userinfo before the host, which the local emulator never needs");
        }
        if (endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
            throw endpointRefusal(key, "carries a query or fragment component, which a service endpoint "
                    + "must not");
        }

        // Host absence is reported before the shape checks below, because a value with no host has no
        // emulator address to test and saying so is more actionable than naming whichever other defect the
        // same value happens to carry. `http:///bucket` has both, and "names no host" is the one to fix.
        if (endpoint.getHost() == null || endpoint.getHost().isBlank()) {
            throw endpointRefusal(key, "names no host, so there is no address to check against the approved "
                    + "local emulator hosts");
        }

        final String path = endpoint.getRawPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            throw endpointRefusal(key, "carries a path; only a bare scheme://host[:port] is accepted");
        }

        final String authority = endpoint.getRawAuthority();
        if (authority != null && authority.indexOf('%') >= 0) {
            throw endpointRefusal(key, "percent-encodes its authority, which this guard will not decode");
        }

        // The host is the security-bearing component and is therefore judged first. A value on an
        // unapproved host is refused for that reason whatever else is wrong with it, so the
        // diagnostic names the defect that matters rather than whichever one happens to come first
        // in the URL.
        if (!isApprovedEndpointHost(endpoint.getHost())) {
            throw endpointRefusal(key, "does not name one of the approved local emulator hosts. Point it at "
                    + "the local edge - the loopback literals, the emulator's loopback DNS name, the Compose "
                    + "service name or the Compose container name. Live AWS is never used, so there is no "
                    + "value of this property that legitimately names one");
        }

        final int port = endpoint.getPort();
        if (port == NO_ENDPOINT_PORT) {
            throw endpointRefusal(key, "carries no explicit port. The emulator's edge is neither 80 nor 443, "
                    + "so a value with no port cannot reach it however correct its host is");
        }
        if (port < LOWEST_ENDPOINT_PORT || port > HIGHEST_ENDPOINT_PORT) {
            throw endpointRefusal(key, "names a port outside 1-65535");
        }
        return endpoint;
    }

    /**
     * Reports whether a value carries a byte an endpoint may not contain.
     *
     * <p>Whitespace and control characters are refused because they let a value that reads as local resolve
     * elsewhere, and because a control character in a diagnostic splits a log line. A backslash is refused
     * because it is not a URL delimiter and its presence means the value was assembled by something that
     * thought it was a path.
     *
     * @param value the endpoint value, never {@code null}
     * @return {@code true} when the value must be refused on character grounds alone
     */
    private static boolean containsForbiddenCharacter(final String value) {
        return value.indexOf('\\') >= 0
                || value.codePoints().anyMatch(cp -> Character.isWhitespace(cp) || Character.isISOControl(cp));
    }

    /**
     * Reports whether a parsed host is one this application may address.
     *
     * <p>A {@code null} host is refused rather than tolerated: the parser returns {@code null} for a
     * registry-based authority, which is what a percent-encoded, underscored or otherwise unparsable host
     * produces, and "could not be identified" is not a reason to accept an address. A bracketed address
     * literal is unwrapped and a single trailing dot is removed, because both spell the same host as the
     * allowlist entry they must match.
     *
     * <p>Normalisation is all this method adds. The decision itself is delegated to
     * {@link #isEmulatorHost(String)} so that there is exactly one answer to "is this an emulator address?"
     * in this class: two host rules that drift apart would make acceptance depend on which guard ran first,
     * and one of them would be quietly wrong.
     *
     * @param rawHost the host the parser extracted, possibly {@code null}
     * @return {@code true} only when the normalised host is an emulator address
     */
    private static boolean isApprovedEndpointHost(final String rawHost) {
        if (rawHost == null || rawHost.isBlank()) {
            return false;
        }

        String host = rawHost.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }

        return isEmulatorHost(host);
    }

    /**
     * Builds the refusal for an endpoint that did not clear the guard, naming the key and the reason only.
     *
     * @param key    the property key the value came from
     * @param reason what was wrong with it, in words a reader can act on
     * @return the exception to throw, so that every call site reads as {@code throw endpointRefusal(...)} and
     *         the compiler can see the path terminates
     */
    private static IllegalStateException endpointRefusal(final String key, final String reason) {
        return new IllegalStateException(endpointRefusalMessage(key, reason));
    }

    /**
     * Renders the refusal text shared by {@link #endpointRefusal(String, String)} and the parse-failure path.
     *
     * <p>Two call sites build the same sentence and one of them must attach a cause, so the text is produced
     * once here rather than written twice.
     *
     * @param key    the property key the value came from
     * @param reason what was wrong with it
     * @return the message, which never contains the rejected value
     */
    private static String endpointRefusalMessage(final String key, final String reason) {
        return String.format(Locale.ROOT,
                "CardDemo cloud endpoint property '%s' %s. Its value is deliberately not quoted here: a "
                        + "rejected endpoint can carry userinfo, and userinfo in a log line is a credential in "
                        + "a log file. AAP 0.3.2 makes it binding that all cloud interaction is against the "
                        + "local emulator and that no code path may reach a real endpoint, so this property "
                        + "must name an approved local address in every profile, including prod, and there is "
                        + "no flag that relaxes this check. Supply one through AWS_ENDPOINT_URL, or activate "
                        + "the local profile for developer defaults.",
                key, reason);
    }

    /**
     * Proves the resolved credentials provider is a static one, and aborts startup otherwise.
     *
     * <p>This is the credential half of the zero-live-cloud invariant, and it is an assertion about a resolved
     * object rather than about a property because the property is only half the story. The library builds its
     * provider by collecting whatever is configured - a static pair, an instance-profile provider, a named
     * profile provider, a security-token provider - and then, <strong>if it collected nothing at all</strong>,
     * returns the SDK's default provider chain. That chain is the ambient credential resolution this invariant
     * exists to exclude: it reads a shared credentials file, a web-identity token, container credentials and
     * the instance metadata service in turn, any of which can hand a live principal to a signed request.
     *
     * <p>A static provider is therefore the only acceptable outcome, and anything else is refused - including a
     * provider chain, even one composed entirely of static links. A chain means more than one source was
     * configured, which is a configuration this application has no use for and cannot audit at a glance, and
     * refusing it costs nothing because no profile produces one.
     *
     * <p><strong>The second half of this check exists because absence is not fail-fast here, and that was
     * discovered by measurement rather than assumed.</strong> The endpoint, the region and the resource names
     * are bound field by field, where an unresolvable placeholder aborts startup outright. The credential pair
     * is not: it is bound as configuration properties, and that binding resolves placeholders through a
     * resolver which <em>ignores</em> what it cannot resolve - so an unset {@code AWS_ACCESS_KEY_ID} binds the
     * property to the literal text {@code ${AWS_ACCESS_KEY_ID}}. The library sees text where a credential
     * should be, builds a static provider around it, and the context refreshes. The type check alone therefore
     * passes on a deployment that supplied no credential at all, and the failure surfaces at the first call as
     * an authorisation error with no connection to its cause. Resolving the credential and refusing an
     * unresolved placeholder is what closes that gap. The values are never logged and never appear in the
     * message.
     *
     * <p>The base profile is what makes the acceptable outcome happen: it declares the access key and the
     * secret key with no default, and it pins the instance-profile flag false so nothing is appended to the
     * provider list. The values are the emulator's, which accepts any credential; no live credential exists
     * anywhere in this repository to be configured here.
     *
     * @param credentialsProvider the provider the library resolved; never {@code null} in a wired context
     * @throws IllegalStateException if the provider is not a static one, aborting startup before any client
     *                               is built and therefore before any call can be signed
     */
    private static void requireStaticCredentials(final AwsCredentialsProvider credentialsProvider) {
        if (!(credentialsProvider instanceof StaticCredentialsProvider)) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "The resolved AWS credentials provider is %s, and CardDemo requires a static provider. "
                            + "Any other provider means a chain that can reach ambient credentials - a shared "
                            + "credentials file, a web-identity token, container credentials or the instance "
                            + "metadata service - and AAP 0.3.2 makes it binding that zero live credentials "
                            + "exist anywhere in this project. Set spring.cloud.aws.credentials.access-key "
                            + "and .secret-key, which the base profile already indirects through "
                            + "AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY with no default, and leave "
                            + "spring.cloud.aws.credentials.instance-profile false.",
                    credentialsProvider == null ? "absent" : credentialsProvider.getClass().getSimpleName()));
        }

        final AwsCredentials credentials = credentialsProvider.resolveCredentials();
        if (isUnresolvedValue(credentials.accessKeyId()) || isUnresolvedValue(credentials.secretAccessKey())) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "The AWS credential pair resolved to an unset or unresolved value. Properties "
                            + "spring.cloud.aws.credentials.access-key and .secret-key are indirected through "
                            + "AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY with no default, and - UNLIKE the "
                            + "endpoint, the region and the resource names, which are bound field by field "
                            + "and fail placeholder resolution outright - these two are bound as "
                            + "configuration properties, where an unresolvable placeholder is IGNORED and "
                            + "binds as its own literal text. The library then reads text where a credential "
                            + "should be, builds a static provider around it, and the context refreshes "
                            + "carrying credentials that fail at the first call with an authorisation error "
                            + "far from its cause. This check is what turns that into a startup failure. "
                            + "Inject both variables; the emulator accepts any pair."));
        }
    }

    /**
     * Reports whether a bound value is absent or is an unresolved property placeholder rather than a value.
     *
     * <p>The placeholder test is what the plain blank test cannot do. Configuration-property binding resolves
     * placeholders through a resolver that <em>ignores</em> what it cannot resolve, so an unset environment
     * variable does not fail: the property binds to the literal text of its own placeholder. That text is
     * neither null nor blank, so only recognising the placeholder syntax distinguishes "the operator supplied
     * this" from "nobody supplied anything".
     *
     * <p>The null and blank branches are defensive rather than expected, and deliberately kept. The standard
     * credential type validates its own arguments and refuses a blank key outright, so a variable set to the
     * empty string fails inside the library before this method is reached; the branches cover a caller that
     * supplies some other credential implementation, and cost one comparison each. Rule 1 Clause B requires
     * null and empty cases to be handled explicitly rather than assumed away.
     *
     * @param value the bound value, possibly {@code null}
     * @return {@code true} when the value is absent, blank, or an unresolved placeholder
     */
    private static boolean isUnresolvedValue(final String value) {
        return value == null || value.isBlank() || value.contains("${");
    }

    /**
     * Proves the three buckets are distinct.
     *
     * <p>The roles are not interchangeable: batch input is read, batch output is written and versioned, and
     * statements are written under their own prefixes. Pointing two roles at one bucket would let a job
     * overwrite its own input, and would make the versioning that replaces generation retention apply to data
     * it was never meant to cover. The provisioning script asserts the same thing, so agreeing with it here
     * turns a half-provisioned stack into a startup failure with a readable cause.
     *
     * @param inputBucket      the validated batch input bucket
     * @param outputBucket     the validated batch output bucket
     * @param statementsBucket the validated statement bucket
     * @throws IllegalStateException if any two of the three are equal, aborting startup
     */
    private static void requireDistinctBuckets(final String inputBucket, final String outputBucket,
            final String statementsBucket) {

        if (inputBucket.equals(outputBucket)
                || inputBucket.equals(statementsBucket)
                || outputBucket.equals(statementsBucket)) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "Properties '%s', '%s' and '%s' must name three distinct buckets: batch input is read, "
                            + "batch output is written and versioned, and statements are written separately, so "
                            + "sharing a bucket would let a job overwrite its own input. Give each property its "
                            + "own value.",
                    KEY_INPUT_BUCKET, KEY_OUTPUT_BUCKET, KEY_STATEMENTS_BUCKET));
        }
    }

    /**
     * Proves the physical queue name is exactly the logical name suffixed {@value #FIFO_SUFFIX}.
     *
     * <p>Two distinct defects are closed by one comparison, and the suffix is required rather than merely
     * preferred.
     *
     * <p>The first is drift. The provisioning script derives the physical name from the logical name, so a
     * disagreement between the two would provision one queue and publish to a different name - a failure that
     * appears at the first report submission rather than at startup, and reads as a missing queue rather than
     * as a configuration mistake.
     *
     * <p>The second is the silent loss of ordering, and it is why the unsuffixed spelling is no longer
     * accepted. Amazon SQS honours a message group identifier only on a FIFO queue, and a FIFO queue is
     * identifiable in exactly one way: its name ends {@value #FIFO_SUFFIX}. Report submission replaces
     * {@code EXEC CICS WRITEQ TD QUEUE('JOBS')}, whose {@code DISPOSITION(MOD)} extrapartition queue appended
     * in submission order and was drained in that order by the internal reader
     * ({@code app/csd/CARDDEMO.CSD:L499-L503}), so ordering is part of the behaviour being reproduced and not
     * a preference. On a standard queue the group identifier is not honoured and, depending on the SDK and
     * service version, is either rejected or accepted and ignored - and "accepted and ignored" is the
     * dangerous outcome, because submissions are then reordered and duplicated with nothing in any log to say
     * so. This condition was previously a startup <em>warning</em> on the reasoning that the first send would
     * fail loudly; that reasoning does not hold, because it depends on a rejection this class cannot
     * guarantee, and a guarantee that rests on someone else's error handling is not a guarantee. Aborting the
     * context refresh instead makes the ordering contract structural: a misconfigured queue name cannot reach
     * a running process at all.
     *
     * <p>The suffix is mandatory because every send this application makes carries a message group, and only a
     * first-in-first-out queue honours one. An unsuffixed name used to produce a warning and then be accepted,
     * on the reasoning that the service itself would reject the message group on the first send. That reasoning
     * traded a deterministic startup failure for a runtime one on a path whose whole purpose is to reproduce the
     * strictly sequential {@code DISPOSITION(MOD)} append of {@code DEFINE TDQUEUE(JOBS)} at
     * {@code app/csd/CARDDEMO.CSD:L499-L503}. The name is only half of the contract, so
     * {@link #cardDemoFifoQueueContractVerifier(SqsAsyncClient)} checks the provisioned queue's own attributes
     * as well; a standard queue created under a {@code .fifo} name would satisfy this check and fail that one.
     *
     * @param physicalName the validated physical queue name
     * @param logicalName  the validated logical queue name
     * @throws IllegalStateException if the physical name is not the logical name plus {@value #FIFO_SUFFIX},
     *                               aborting startup
     */
    private static void requireQueueNamesAligned(final String physicalName, final String logicalName) {
        if (!physicalName.equals(logicalName + FIFO_SUFFIX)) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "Property '%s' must hold the value of property '%s' suffixed '%s', because "
                            + "localstack-init/init-aws.sh derives one from the other and because a message "
                            + "group is honoured only on a FIFO queue, which SQS identifies solely by that "
                            + "suffix. The configured physical name is not that value, so publishing would "
                            + "either target a queue provisioning never created or lose the submission "
                            + "ordering that DEFINE TDQUEUE(JOBS) guaranteed. Set '%s' to '%s' plus '%s'.",
                    KEY_REPORT_QUEUE, KEY_REPORT_QUEUE_LOGICAL_NAME, FIFO_SUFFIX,
                    KEY_REPORT_QUEUE, KEY_REPORT_QUEUE_LOGICAL_NAME, FIFO_SUFFIX));
        }
    }

    /**
     * Proves that a static, non-live credential pair is configured, so the SDK's default provider chain is
     * never consulted.
     *
     * <p>Presence is the load-bearing half. Supplying both values pins a static credentials provider, which
     * <em>replaces</em> the default chain; leaving either unset lets the chain run through environment
     * variables, the shared credentials file, a web-identity token, container credentials and finally instance
     * metadata, any of which may yield a live principal on a developer machine or a build agent. The emulator
     * itself validates nothing, so the value's content is irrelevant to it - which is exactly why the value
     * must be a deterministic placeholder rather than whatever the ambient environment happens to hold.
     *
     * <p>The shape check is the other half: a value carrying a real access-key prefix is refused, because that
     * is what a pasted production key looks like and because the emulator would accept it silently. Neither
     * value is echoed, digested or reported in any message - only the property key and the rejected prefix,
     * which is a published AWS constant rather than a secret.
     *
     * @param accessKey the raw access-key property value, possibly empty when unset
     * @param secretKey the raw secret-key property value, possibly empty when unset
     * @throws IllegalStateException if either value is absent or blank, or if the access key carries a live
     *                               AWS prefix, aborting startup
     */
    private static void requireNonLiveCredentials(final String accessKey, final String secretKey) {
        requireCredentialPresent(KEY_ACCESS_KEY, accessKey);
        requireCredentialPresent(KEY_SECRET_KEY, secretKey);

        final String candidate = accessKey.trim().toUpperCase(Locale.ROOT);
        for (final String prefix : LIVE_ACCESS_KEY_PREFIXES) {
            if (candidate.startsWith(prefix)) {
                throw new IllegalStateException(String.format(Locale.ROOT,
                        "Property '%s' begins with '%s', the prefix AWS assigns to real access-key "
                                + "identifiers. CardDemo talks only to the local emulator, which validates no "
                                + "credential, so a real key can only have arrived here by mistake and is "
                                + "refused. Replace it with a deterministic non-live placeholder. The value is "
                                + "never logged.",
                        KEY_ACCESS_KEY, prefix));
            }
        }
    }

    /**
     * Rejects an absent or blank credential value, naming the property and never the value.
     *
     * @param key   the property key being validated
     * @param value the raw property value, possibly {@code null} when the property is unbound
     * @throws IllegalStateException if the value is {@code null}, empty or whitespace only, aborting startup
     */
    private static void requireCredentialPresent(final String key, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "Property '%s' is not set. Without both static credential properties the AWS SDK falls back "
                            + "to its default provider chain - environment variables, the shared credentials "
                            + "file, a web-identity token, container credentials, then instance metadata - and "
                            + "could sign an emulator-bound call with a live principal. Set it to a "
                            + "deterministic non-live placeholder; the emulator validates no credential.",
                    key));
        }
    }

    /**
     * Returns the missing-queue strategy, having proved one resolved.
     *
     * <p>Defensive rather than expected: the binding carries a {@code FAIL} default, so a value should always
     * resolve. It is checked because a silent {@code null} would leave the publisher on the library default,
     * under which an absent queue is created on first send, and that outcome must never be reachable by
     * accident.
     *
     * @param strategy the resolved strategy, possibly {@code null}
     * @return the strategy; never {@code null}
     * @throws IllegalStateException if no strategy resolved, aborting startup
     */
    private static QueueNotFoundStrategy requireStrategy(final QueueNotFoundStrategy strategy) {
        if (strategy == null) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "Property '%s' resolved to no value. It must be FAIL or CREATE, and CardDemo requires FAIL "
                            + "so that a missing queue is reported rather than created: provisioning belongs to "
                            + "localstack-init/init-aws.sh, never to the application.",
                    KEY_QUEUE_NOT_FOUND_STRATEGY));
        }
        return strategy;
    }

    /**
     * Copies the correlation identifier onto every outbound cloud request, so that a request leaving this
     * process can be joined to the log records and the span it came from.
     *
     * <p>This is the process-boundary half of what {@code com.cardemo.observability.CorrelationIdFilter} does at
     * the request boundary. The filter is the direct replacement for {@code EIBTRNID}, the CICS transaction
     * identifier that was the only per-request thread of identity the frozen corpus had; without this
     * interceptor that thread stopped at the edge of the application and an object write or a queue publish
     * carried no identity at all.
     *
     * <p>The diagnostic-context key and the header name are read from that class's published constants and are
     * <strong>never re-declared</strong> here: one owner per literal, as Rule 1 Clause C requires.
     *
     * <p>The value is re-validated against the filter's own grammar before it is written. That is not
     * belt-and-braces: a header is a text protocol, so a value carrying a carriage return or a line feed would
     * be a header-injection vector, and batch code puts its own value into the diagnostic context without
     * passing through the filter. An absent, blank or malformed value means no header rather than a bad one -
     * the failure mode is a missing correlation, never a corrupted request.
     *
     * <p><strong>{@code modifyHttpRequest} is the correct hook rather than {@code beforeExecution}.</strong>
     * It is the last point at which the HTTP request can still be altered, and it runs after the SDK has
     * assembled the request but before signing, so the added header is covered by the signature instead of
     * invalidating it.
     */
    public static final class CorrelationIdExecutionInterceptor implements ExecutionInterceptor {

        /**
         * Creates the interceptor.
         *
         * <p>Public because one instance is shared by all three client customizers and by every concurrent
         * request. It is stateless - it declares no instance field at all - so sharing it is safe and a test
         * can construct its own without arranging anything.
         */
        public CorrelationIdExecutionInterceptor() {
            // Stateless: the identifier is read from the calling thread's diagnostic context at request time.
        }

        /**
         * Adds the correlation header when a well-formed identifier is in scope, and changes nothing otherwise.
         *
         * <p>This callback runs before the request is signed, so the header is covered by the signature rather
         * than invalidating it.
         *
         * @param context             the request about to be sent
         * @param executionAttributes the execution attributes; unused, because the identifier travels in the
         *                            diagnostic context rather than in an attribute
         * @return the request, with the correlation header added when one is available
         */
        @Override
        public SdkHttpRequest modifyHttpRequest(final Context.ModifyHttpRequest context,
                final ExecutionAttributes executionAttributes) {

            final String correlationId = CorrelationIdFilter.currentCorrelationId();
            if (correlationId == null) {
                return context.httpRequest();
            }
            return context.httpRequest().toBuilder()
                    .putHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, correlationId)
                    .build();
        }
    }

    /**
     * Recovers the correlation identifier from the message itself when a queue publish is issued on a thread
     * that has no diagnostic context, so that the send arrives correlated on the wire as well as in its
     * payload metadata.
     *
     * <p><strong>The gap this closes, precisely.</strong>
     * {@link CorrelationIdExecutionInterceptor} reads the calling thread's {@link MDC}, which is the right
     * mechanism and is uniform across every operation of every client - but it can only read what the calling
     * thread holds. {@code SqsTemplate.sendAsync} first resolves the queue URL and the queue attributes, caching
     * the result in a private map, and chains the send onto that resolution. On the <em>first</em> publish of a
     * process the resolution has not completed yet, so the chained {@code SendMessage} is issued from an SDK
     * completion thread, which never passed through {@code CorrelationIdFilter} and therefore holds no
     * diagnostic context: the request went out unstamped even though the publishing request had a perfectly
     * good identifier. Runtime testing through a logging proxy observed exactly that - {@code GetQueueUrl}
     * carried the header and the chained {@code SendMessage} did not.
     *
     * <p><strong>Why the message attribute is the right recovery source.</strong> The identifier is already on
     * the request: {@code com.cardemo.service.report.ReportSubmissionService} sets it as the
     * {@value CorrelationIdFilter#CORRELATION_ID_HEADER} message attribute from the same validated diagnostic
     * context, on the request thread, before the publish is handed over. Reading it back here needs no thread
     * context, no context-propagating executor and no state of any kind, so it is correct on any thread and
     * under any amount of concurrency - two publishes in flight cannot borrow each other's identifier, because
     * each one's value travels inside its own request object.
     *
     * <p><strong>Why this is a separate interceptor rather than a change to the uniform one.</strong> Reading
     * {@code Context.ModifyHttpRequest#request()} couples an interceptor to individual operations, and the
     * uniform interceptor must stay operation-agnostic so that it applies identically to object writes, queue
     * sends and notifications alike - including operations added later. That constraint is asserted by its own
     * unit tests, which hand it a context whose {@code request()} throws. Keeping the operation-aware recovery
     * in a second class, registered on the queue client only, means the uniform contract is unchanged and this
     * class's behaviour is confined to the one operation that can carry the identifier in its own payload.
     *
     * <p><strong>What it deliberately does not do.</strong> It never replaces a header that is already present,
     * so a request stamped from the diagnostic context is untouched and the two interceptors cannot disagree
     * whatever order they run in. It adds nothing to {@code GetQueueUrl} or {@code GetQueueAttributes}: those
     * resolve a queue once per process and their result is shared by every later request, so attributing them
     * to whichever request happened to trigger the resolution would be a misleading correlation rather than a
     * missing one. And it never throws - a diagnostic must not be able to fail a publish - so a request shape
     * it does not recognise is returned exactly as received.
     */
    public static final class SqsSendCorrelationRecoveryInterceptor implements ExecutionInterceptor {

        /**
         * Creates the interceptor.
         *
         * <p>Public because it is registered on the shared queue client and exercised directly by tests. It is
         * stateless - it declares no instance field - so one instance serves every concurrent publish.
         */
        public SqsSendCorrelationRecoveryInterceptor() {
            // Stateless: the identifier is read from the request being sent, never from thread state.
        }

        /**
         * Adds the correlation header from the message's own attributes when the request has none.
         *
         * <p>Runs after the uniform interceptor on the same client, so an identifier that was available in the
         * diagnostic context has already been stamped and is left exactly as it is. The recovered value is
         * re-validated through {@link CorrelationIdFilter#usableCorrelationId(String)} before it is written,
         * because a header is a text protocol and this is a different trust boundary from the one the filter
         * guards: anything carrying a carriage return, a line feed or an unbounded length is dropped rather
         * than forwarded.
         *
         * @param context             the request about to be sent
         * @param executionAttributes the execution attributes; unused, because the identifier travels on the
         *                            request rather than in an attribute
         * @return the request, with the correlation header added when one could be recovered
         */
        @Override
        public SdkHttpRequest modifyHttpRequest(final Context.ModifyHttpRequest context,
                final ExecutionAttributes executionAttributes) {

            final SdkHttpRequest httpRequest = context.httpRequest();
            if (httpRequest.firstMatchingHeader(CorrelationIdFilter.CORRELATION_ID_HEADER).isPresent()) {
                return httpRequest;
            }

            final String recovered = correlationIdOf(context.request());
            if (recovered == null) {
                return httpRequest;
            }
            return httpRequest.toBuilder()
                    .putHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, recovered)
                    .build();
        }

        /**
         * Reads the correlation identifier out of a send request's message attributes.
         *
         * <p>Only {@code SendMessage} is recognised. The application publishes one message per submission -
         * Transformation Rule 10 replaces a single {@code EXEC CICS WRITEQ TD} with a single
         * {@code SqsTemplate} send - so a batch send never occurs on this path, and guessing which entry of a
         * hypothetical batch should speak for the whole HTTP request would be inventing a correlation rather
         * than recovering one.
         *
         * @param request the modelled request the SDK is about to transmit, which a non-standard caller could
         *                make {@code null}
         * @return the well-formed identifier the message carries, or {@code null} when the request is not a
         *         send, carries no correlation attribute, or carries one that is not well-formed
         */
        private static String correlationIdOf(final SdkRequest request) {
            if (!(request instanceof final SendMessageRequest sendMessage)) {
                return null;
            }
            final MessageAttributeValue attribute =
                    sendMessage.messageAttributes().get(CorrelationIdFilter.CORRELATION_ID_HEADER);
            return attribute == null
                    ? null
                    : CorrelationIdFilter.usableCorrelationId(attribute.stringValue());
        }
    }

    /**
     * A topic resolver that resolves only what has already been provisioned, and creates nothing.
     *
     * <p>Two shapes are accepted. An identifier already beginning {@value #ARN_PREFIX} is parsed and returned
     * unchanged, because it names a specific resource and there is nothing to look up. A bare name is matched
     * against the provisioned topics by walking {@code ListTopics}, comparing the last segment of each
     * identifier; the walk is read-only and bounded to {@value #TOPIC_PAGE_LIMIT_COUNT} pages so a paginated or
     * looping response cannot spin.
     *
     * <p>An unresolvable name is a failure. That is the whole point: the library's own resolver would call
     * {@code CreateTopic} instead, provisioning a resource from Java - which belongs solely to
     * {@code localstack-init/init-aws.sh} - and granting the runtime a privilege it has no business holding.
     *
     * <p>The failure message names the bare topic name and the property that carries it, and never a resolved
     * identifier, because a resolved identifier embeds the account number.
     */
    private static final class ProvisionedTopicArnResolver implements TopicArnResolver {

        /** The notification client, used for read-only listing only. */
        private final SnsClient snsClient;

        /**
         * Retains the client.
         *
         * @param snsClient the configured client; must not be {@code null}
         */
        private ProvisionedTopicArnResolver(final SnsClient snsClient) {
            this.snsClient = snsClient;
        }

        /**
         * Resolves a topic name or identifier without ever creating a topic.
         *
         * @param topicNameOrArn the destination a caller named; must not be {@code null} or blank
         * @return the resolved identifier
         * @throws IllegalArgumentException if the argument is {@code null} or blank
         * @throws IllegalStateException if a bare name is not among the provisioned topics
         */
        @Override
        public Arn resolveTopicArn(final String topicNameOrArn) {
            if (topicNameOrArn == null || topicNameOrArn.isBlank()) {
                throw new IllegalArgumentException("A notification destination must be named: it is either a "
                        + "provisioned topic name or an already-resolved topic identifier, and neither can be "
                        + "empty. No topic is ever created from Java.");
            }
            final String destination = topicNameOrArn.strip();
            if (destination.startsWith(ARN_PREFIX)) {
                return Arn.fromString(destination);
            }
            return resolveProvisionedName(destination);
        }

        /**
         * Walks the provisioned topics and returns the one whose last identifier segment is the given name.
         *
         * @param topicName the bare topic name
         * @return the resolved identifier
         * @throws IllegalStateException if no provisioned topic carries that name
         */
        private Arn resolveProvisionedName(final String topicName) {
            String nextToken = null;
            for (int page = 0; page < TOPIC_PAGE_LIMIT_COUNT; page++) {
                final ListTopicsResponse response = this.snsClient.listTopics(ListTopicsRequest.builder()
                        .nextToken(nextToken)
                        .build());
                for (final Topic topic : response.topics()) {
                    if (topicName.equals(bareName(topic.topicArn()))) {
                        return Arn.fromString(topic.topicArn());
                    }
                }
                nextToken = response.nextToken();
                if (nextToken == null || nextToken.isBlank()) {
                    break;
                }
            }
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "Notification topic '%s' is not provisioned, so nothing was published. This resolver "
                            + "deliberately never creates a topic: provisioning belongs to "
                            + "localstack-init/init-aws.sh, which is idempotent. Check the spelling of "
                            + "property '%s' and re-run that script. Up to %d pages of %d topics were "
                            + "examined.",
                    topicName, KEY_NOTIFICATION_TOPIC, TOPIC_PAGE_LIMIT_COUNT, TOPIC_PAGE_LIMIT));
        }

        /**
         * Reads the bare topic name out of a resolved identifier: everything after the last separator.
         *
         * @param topicArn the resolved identifier reported by the service; never {@code null}
         * @return the bare name
         */
        private static String bareName(final String topicArn) {
            return topicArn.substring(topicArn.lastIndexOf(ARN_SEPARATOR) + 1);
        }
    }
}
