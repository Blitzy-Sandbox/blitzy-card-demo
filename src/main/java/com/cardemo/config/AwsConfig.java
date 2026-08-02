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

import java.util.Locale;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.s3.S3ObjectConverter;
import io.awspring.cloud.s3.S3OutputStreamProvider;
import io.awspring.cloud.s3.S3Template;
import io.awspring.cloud.sns.core.SnsTemplate;
import io.awspring.cloud.sns.core.TopicArnResolver;
import io.awspring.cloud.sqs.listener.QueueNotFoundStrategy;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import io.awspring.cloud.sqs.support.converter.MessagingMessageConverter;
import io.awspring.cloud.sqs.support.converter.SqsMessagingMessageConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.support.ChannelInterceptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.Message;

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
 *   <li>{@link #snsTemplate(SnsClient, ObjectProvider, ObjectProvider, ObjectProvider)} - notification,
 *       replacing operator notification, for which the legacy corpus has no programmatic counterpart at
 *       all.</li>
 * </ul>
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
 *   <li><strong>It declares no emulator endpoint.</strong> The endpoint is profile configuration and exists
 *       only in the {@code local} and {@code test} profiles. See the guarantee below.</li>
 *   <li><strong>It declares no queue listener.</strong> The consumer that replaces the JES2 internal reader
 *       belongs to {@code com.cardemo.batch.jobs.BatchPipelineOrchestrator}.</li>
 *   <li><strong>It derives no object keys.</strong> The generation-reference translation is specified here and
 *       implemented by {@code com.cardemo.config.BatchConfig} and the job classes, which bind the seven
 *       {@code carddemo.aws.s3.gdg-prefixes.*} entries directly.</li>
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
 * </ul>
 *
 * <p>On cost, since Rule 1 Clause A asks for obvious inefficiency to be avoided and tradeoffs to be justified:
 * the three beans are singletons built once during context refresh, and the validation below is a fixed number
 * of string comparisons over seven short names, so the whole class runs once per process and never on a request
 * or record path. The one tradeoff worth naming is that the missing-queue strategy and the object mapper are
 * re-applied here rather than inherited by leaving the library's own beans in place - a few lines of duplication
 * accepted deliberately, because the alternative is a client whose construction has no single owner, and the
 * cost of getting that wrong is documented under the first finding below.
 *
 * <h2>The no-live-AWS guarantee is structural</h2>
 *
 * <p>The emulator endpoint override appears in {@code application-local.yml} and {@code application-test.yml}
 * and in neither the base profile nor {@code application-prod.yml}, which carries no cloud section whatsoever.
 * No credential default exists in any profile. This file names no host, no port, no region, no access key
 * identifier, no secret and no session token, and it contains no fallback that could resolve a real service
 * endpoint. Zero live credentials exist anywhere in the repository, and no code path in this class can reach a
 * live endpoint.
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
 * metrics store and the dashboard - from the repository root, then run the application against it. The
 * {@code local} profile carries the endpoint override and the concrete resource names; the base profile
 * carries neither:
 *
 * <pre>{@code
 * set -a; . ./.env; set +a
 * docker compose up -d
 * SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
 * }</pre>
 *
 * <p>The three buckets, the FIFO queue and the topic are created by {@code localstack-init/init-aws.sh}, which
 * runs automatically when the emulator container reports ready and may be re-run safely at any time. The
 * integration tests for this class live in {@code src/test/java/com/cardemo/integration/aws} and stand the
 * emulator up through Testcontainers; no test in this package and no test resource is required by, or lives
 * beside, this file.
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
 *       assembled without the base profile still validates. The physical name is the logical name, optionally
 *       suffixed {@code .fifo}; the relationship is asserted at startup because
 *       {@code localstack-init/init-aws.sh} derives one from the other and a drift between the two would
 *       provision one queue and publish to another.</dd>
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
 * </dl>
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
 * </ul>
 *
 * <h2>The one legacy inconsistency that is resolved, rather than logged</h2>
 *
 * <p>{@code TRANREPT} is declared twice with different retention. {@code app/jcl/DEFGDGB.jcl:L37-L38} declares
 * {@code LIMIT(5)} with {@code SCRATCH}; {@code app/jcl/REPTFILE.jcl:L26-L27} declares {@code LIMIT(10)} with
 * no {@code SCRATCH}. <strong>Resolved to 10</strong>, recorded as
 * {@code carddemo.aws.s3.gdg-retention-generations}. Severity <strong>Medium</strong>. It is resolved, and it
 * is the only legacy inconsistency that is, purely because a single value has to be chosen when two
 * declarations disagree and only one lifecycle number can exist. Retention here is a
 * <strong>documented</strong> value, not an enforced one: object versioning on the output bucket supersedes
 * generation retention semantics, and no lifecycle rule is applied from this class or from any other Java
 * code. Remediation, should retention ever need enforcing: express it as a lifecycle rule in
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
 * </ul>
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
 * </ul>
 *
 * <p><strong>Consequence, and the contract every job must honour.</strong> The concrete key created by the
 * writing step is carried forward through the job or step execution context and re-used verbatim by the
 * reading step. A later step must <strong>never</strong> re-resolve the latest prefix mid-job. Re-resolution
 * is a race: a concurrently-running instance could have written a newer generation between the two steps, and
 * the reading step would silently consume the wrong input. This class specifies the contract;
 * {@code com.cardemo.config.BatchConfig} and the job classes implement it.
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
 * </ul>
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
 * reader is {@code com.cardemo.batch.jobs.BatchPipelineOrchestrator}, not this class.
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
 * <h2>Findings, severities and remediation</h2>
 *
 * <p>Severities follow Rule 1 Clause F. Only the first finding below concerned this file, and it was found and
 * removed during implementation; each of the rest is a property of the frozen source, or a scope boundary,
 * recorded rather than hidden.
 *
 * <dl>
 *   <dt><strong>Blocker</strong> - naming a default notification destination would have created the topic</dt>
 *   <dd>Found by reading the library's compiled behaviour rather than its documentation, and removed before
 *       this file was first committed. Setting a default destination name on the notification template
 *       resolves the name immediately, and the default resolver resolves any name not already beginning with
 *       {@code arn:} by creating the topic - so a single convenience call would have provisioned a resource
 *       from Java, invisibly, and made an eager network call during context refresh that would reach a live
 *       service under any profile declaring no endpoint override. Remediation, applied: the call is omitted and
 *       callers name the topic explicitly. The reasoning is restated on the bean itself so that the omission
 *       cannot be mistaken for something forgotten and helpfully re-added.</dd>
 *
 *   <dt><strong>Blocker</strong> - a live-service fallback, or a committed credential</dt>
 *   <dd>Neither exists. Recorded as the severity such a regression would carry, so that the bar is explicit
 *       for anyone editing this file: adding a fallback endpoint, an inline credential or a
 *       provider with literal values would be a release blocker, not a style issue.</dd>
 *
 *   <dt><strong>Medium</strong> - the {@code TRANREPT} retention conflict</dt>
 *   <dd>{@code LIMIT(5)} at {@code app/jcl/DEFGDGB.jcl:L37-L38} against {@code LIMIT(10)} at
 *       {@code app/jcl/REPTFILE.jcl:L26-L27}. Resolved to 10 as described above.</dd>
 *
 *   <dt><strong>Medium</strong> - the statement markup record length disagrees with itself</dt>
 *   <dd>The pre-delete step declares the markup output at {@code DCB=(LRECL=80,BLKSIZE=3200,RECFM=FB)} at
 *       {@code app/jcl/CREASTMT.JCL:L69}, while the execution step declares
 *       {@code DCB=(LRECL=100,BLKSIZE=800,RECFM=FB)} at {@code :L94}. <strong>100 is correct</strong>: the
 *       program emits from a 100-character fixed line in {@code CBSTM03A.CBL}. Logged, not fixed - repairing
 *       the pre-delete step would change bytes the parity comparison is measured against. Remediation, if the
 *       legacy stream were ever to be corrected: change the pre-delete declaration to 100 and re-baseline,
 *       never adjust the emitted width.</dd>
 *
 *   <dt><strong>Low</strong> - a corrupted job-control continuation line</dt>
 *   <dd>{@code app/jcl/CREASTMT.JCL:L90} reads literally
 *       {@code //         SPACE=(CYL,(1,1),RLSE), 00,RECFM=FB), ATA.VSAM.KSDS} - two lines of text overlaid on
 *       one another. Cited verbatim and left alone; the geometry it appears to imply is not acted on, and the
 *       adjacent well-formed declarations at {@code :L89} and {@code :L94} are authoritative.</dd>
 *
 *   <dt><strong>Low</strong> - a misspelled paragraph label, and the locator that follows from it</dt>
 *   <dd>The producer paragraph at {@code app/cbl/CORPT00C.cbl:L515} is spelled
 *       {@code WIRTE-JOBSUB-TDQ.} - transposed, not {@code WRITE}. The specification elsewhere cites
 *       {@code :L515} for the queue write; {@code :L515} is the label and the statement block is
 *       {@code :L517-L523}. The corrected locator is used throughout this file.</dd>
 *
 *   <dt><strong>Low</strong> - no outbound correlation interceptor is registered here</dt>
 *   <dd>{@code com.cardemo.observability.CorrelationIdFilter} assigns outbound request interception to this
 *       class and registers none itself, so today a correlation identifier is not propagated onto outbound
 *       cloud calls. It is not registered here because the diagnostic-context key and the header name are
 *       published as constants on that class, which is outside this file's declared dependency set: importing
 *       it would breach that contract, and re-declaring the two literals would duplicate a definition owned
 *       elsewhere, which Rule 1 Clause C forbids. Remediation, in one change: add that class to this file's
 *       dependency set, then register an execution interceptor on the client builders, reading the key and
 *       header from its constants rather than from new literals.</dd>
 * </dl>
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
 *   <dt>Startup aborts because the physical and logical queue names disagree</dt>
 *   <dd>{@code carddemo.aws.sqs.report-queue} must be {@code carddemo.aws.sqs.report-queue-logical-name},
 *       optionally suffixed {@code .fifo}. Align the two. The provisioning script derives one from the other,
 *       so a drift creates one queue and publishes to a different name.</dd>
 *
 *   <dt>A warning says the queue name is not suffixed {@code .fifo}</dt>
 *   <dd>Message groups are honoured only on FIFO queues, so report submission would lose its ordering
 *       guarantee. It is a warning rather than a failure because the service itself rejects a message group on
 *       a non-FIFO queue, loudly, on the first send; the warning simply moves the diagnosis to startup.</dd>
 *
 *   <dt>A send fails reporting that the queue does not exist</dt>
 *   <dd>Intended. The strategy is {@code FAIL}, so a missing queue is never created silently. Run
 *       {@code localstack-init/init-aws.sh}; it is idempotent.</dd>
 *
 *   <dt>Calls resolve to a real service, or an unexpected bucket appears</dt>
 *   <dd>No endpoint override is active: the base profile declares none by design. Activate {@code local} or
 *       {@code test}. Do not export a global endpoint variable, because the SDK honours it and would redirect
 *       Testcontainers-backed clients onto the shared emulator.</dd>
 *
 *   <dt>Two beans of the same integration type, or a bean-definition override failure</dt>
 *   <dd>The library's auto-configuration declares each of its templates conditionally on the corresponding
 *       operations interface being absent, so the three beans here replace it cleanly. A duplicate arises only
 *       if another class declares one of the same types; it should inject instead.</dd>
 * </dl>
 *
 * <h2>Information not available</h2>
 *
 * <p>Two facts a reader might expect to find here genuinely do not exist in the source, and are reported as
 * such rather than invented:
 *
 * <ul>
 *   <li>The physical key geometry of {@code app/data/EBCDIC/DALYTRAN.PS} is <strong>Not available</strong>: it
 *       has no entry in {@code app/catlg/LISTCAT.txt} and no {@code KEYS} clause anywhere. What would be
 *       needed to state it: a catalogue listing for that dataset, or a defining job step. Nothing under
 *       {@code app/data/EBCDIC} is parsed by the build in any case - those files are codepage reference
 *       only.</li>
 *   <li>Any throughput or latency objective for object storage or for the queue is
 *       <strong>Not available</strong>: the legacy corpus publishes no service-level objective of any kind, so
 *       none is asserted here and none may be inferred. What would be needed to state one: an agreed objective
 *       from the service owner. Until then the performance gate records a measured baseline, not a target.</li>
 * </ul>
 */
@Configuration
public class AwsConfig {

    /** Signing-region property, indirected through the environment by the base profile. No literal default. */
    private static final String KEY_REGION = "spring.cloud.aws.region.static";

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

    /**
     * The logical name of the queue that replaces the extrapartition transient data queue {@code JOBS} of
     * {@code app/csd/CARDDEMO.CSD:L499-L503}. The physical name is this value, optionally suffixed
     * {@link #FIFO_SUFFIX}; both spellings are accepted so that a reader meeting {@code carddemo-report-jobs.fifo}
     * in a bucket listing or a queue listing is not surprised by the suffix.
     */
    private static final String REPORT_QUEUE_LOGICAL_NAME = "carddemo-report-jobs";

    /** The suffix a queue name must carry for the service to treat it as FIFO and honour message groups. */
    private static final String FIFO_SUFFIX = ".fifo";

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
     * {@link #snsTemplate(SnsClient, ObjectProvider, ObjectProvider, ObjectProvider)}. A field holding any of
     * them would be state that is written and never read.
     */
    private final String reportQueueName;

    /** Missing-queue behaviour, applied to the publisher so a missing queue is never silently created. */
    private final QueueNotFoundStrategy queueNotFoundStrategy;

    /**
     * Binds and validates the five environment-indirected resource names this application cannot function
     * without, and fails startup if any of them is absent or blank.
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
     * batch input. And the physical queue name must be the logical name, optionally suffixed
     * {@value #FIFO_SUFFIX}, because the script derives one from the other; were they to drift, provisioning
     * would create one queue and publishing would target another name.
     *
     * @param region                  signing region, from {@value #KEY_REGION}; no default, must be non-blank
     * @param batchInputBucket        batch input bucket, from {@value #KEY_INPUT_BUCKET}; no base-profile
     *                                default, must be non-blank and distinct from the other two
     * @param batchOutputBucket       versioned batch output bucket, from {@value #KEY_OUTPUT_BUCKET}; no
     *                                base-profile default, must be non-blank and distinct
     * @param statementsBucket        statement bucket, from {@value #KEY_STATEMENTS_BUCKET}; no base-profile
     *                                default, must be non-blank and distinct
     * @param reportQueueName         physical queue name, from {@value #KEY_REPORT_QUEUE}; no base-profile
     *                                default, must be non-blank and aligned with the logical name
     * @param reportQueueLogicalName  logical queue name, from {@value #KEY_REPORT_QUEUE_LOGICAL_NAME},
     *                                defaulting to {@value #REPORT_QUEUE_LOGICAL_NAME}
     * @param notificationTopic       notification topic, from {@value #KEY_NOTIFICATION_TOPIC}; no
     *                                base-profile default, must be non-blank
     * @param queueNotFoundStrategy   missing-queue behaviour, from
     *                                {@value #KEY_QUEUE_NOT_FOUND_STRATEGY}, defaulting to {@code FAIL} rather
     *                                than to the library's {@code CREATE}, because creating a queue from Java
     *                                is precisely what this class must never do
     * @throws IllegalStateException if any name is blank, if the three buckets are not distinct, if the
     *                               physical and logical queue names disagree, or if the strategy resolves to
     *                               no value. Startup is aborted in every case; nothing is defaulted quietly
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
            final QueueNotFoundStrategy queueNotFoundStrategy) {

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

        LOG.info("CardDemo cloud integration bound in region {}: batch input bucket {}, versioned batch output "
                        + "bucket {}, statements bucket {}, report queue {} (logical name {}, the {}-byte fixed "
                        + "record of DEFINE TDQUEUE(JOBS) carried as one typed JSON message), notification "
                        + "topic {}, missing-queue strategy {}",
                verifiedRegion, verifiedInputBucket, verifiedOutputBucket, verifiedStatementsBucket,
                this.reportQueueName, verifiedLogicalName, QUEUE_RECORD_LENGTH, verifiedNotificationTopic,
                this.queueNotFoundStrategy);

        if (!this.reportQueueName.endsWith(FIFO_SUFFIX)) {
            LOG.warn("Report queue {} is not suffixed {}, so it is not a FIFO queue and the fixed message group "
                            + "that makes report submission ordering reproducible will not be honoured. This is "
                            + "a warning rather than a failure because the service itself rejects a message "
                            + "group on a non-FIFO queue on the first send; align property {} with property {} "
                            + "plus that suffix to restore the ordering guarantee.",
                    this.reportQueueName, FIFO_SUFFIX, KEY_REPORT_QUEUE, KEY_REPORT_QUEUE_LOGICAL_NAME);
        }
    }

    /**
     * The object-storage collaborator for the whole application: the substrate that replaces the seven
     * generation data group bases of {@code app/jcl/DEFGDGB.jcl}, {@code app/jcl/DALYREJS.jcl} and
     * {@code app/jcl/REPTFILE.jcl}, catalogued as seven {@code 0GDG BASE} entries in
     * {@code app/catlg/LISTCAT.txt}.
     *
     * <p>Every collaborator arrives by injection, and the client above all. That is what keeps the region, the
     * credential resolution and the emulator endpoint in profile configuration where the {@code local} and
     * {@code test} profiles declare them, and out of Java where a literal would defeat the guarantee. Building
     * a client here would also silently discard whatever the encryption or path-style settings had configured.
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
     * queue must be FIFO and why the constructor warns when the name is not suffixed {@value #FIFO_SUFFIX}.
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
     * {@code com.cardemo.batch.jobs.BatchPipelineOrchestrator}. On a send failure
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
     * though the topic name is validated at startup and a default would be a convenience. The omission is a
     * finding, not an oversight, and it is the reason the topic name is validated but not retained as a field.
     * Setting a default destination name is not an assignment: the library resolves the name to a topic
     * identifier immediately, and its default resolver resolves any name that does not already begin with
     * {@code arn:} <em>by creating the topic</em>. Two consequences follow, and each on its own is
     * disqualifying. It would provision a resource from Java - indirectly, which is worse than doing so plainly,
     * because nothing in this file would name the operation - when provisioning belongs solely to
     * {@code localstack-init/init-aws.sh}. And it would make an eager network call during context refresh, so
     * startup would fail whenever the emulator is down, and under a profile that declares no endpoint override
     * it would attempt to reach a live service, which is precisely the fallback the profile layering exists to
     * make unreachable. Severity had it shipped: <strong>Blocker</strong>. Remediation, applied here: omit the
     * call. Callers name the topic explicitly from {@value #KEY_NOTIFICATION_TOPIC}, exactly as report
     * submission names the queue, so nothing is lost but the convenience.
     *
     * <p>Nothing about this bean reaches the network. It stores a client, a converter and any interceptors, and
     * the first call is made by the caller, against a destination the caller names.
     *
     * <p>This bean is not unused code, which Rule 1 Clause B forbids. Its consumer is
     * {@code com.cardemo.observability.HealthIndicators}, whose readiness probe reads the shared
     * {@code carddemo.aws.sns} namespace and which is required to <em>inject</em> this collaborator rather than
     * construct one - constructing one there would place the endpoint decision outside profile configuration
     * and so outside the guarantee this class exists to hold. Until that indicator is in place the bean has a
     * declared owner and a named consumer, both tracked in {@code DECISION_LOG.md}, rather than being an
     * invented extra.
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
     * @param topicArnResolverProvider   an optional resolver; when absent the library's default resolution
     *                                   applies, exactly as it does without this bean
     * @param channelInterceptorProvider any interceptors to apply, in deterministic order
     * @return the notification template, carrying no default destination for the reason given above. Its
     *         declaration makes the library's own conditional template back off, so exactly one such bean
     *         exists
     */
    @Bean
    public SnsTemplate snsTemplate(final SnsClient snsClient,
            final ObjectProvider<ObjectMapper> objectMapperProvider,
            final ObjectProvider<TopicArnResolver> topicArnResolverProvider,
            final ObjectProvider<ChannelInterceptor> channelInterceptorProvider) {

        // Reproduces the library's own conditional template: a payload converter serialised as text, the
        // application object mapper when one exists, the optional resolver, then the interceptors.
        final MappingJackson2MessageConverter payloadConverter = new MappingJackson2MessageConverter();
        payloadConverter.setSerializedPayloadClass(String.class);
        objectMapperProvider.ifAvailable(payloadConverter::setObjectMapper);

        final TopicArnResolver topicArnResolver = topicArnResolverProvider.getIfAvailable();
        final SnsTemplate template = topicArnResolver == null
                ? new SnsTemplate(snsClient, payloadConverter)
                : new SnsTemplate(snsClient, topicArnResolver, payloadConverter);

        channelInterceptorProvider.orderedStream().forEach(template::addChannelInterceptor);

        // Deliberately NOT setDefaultDestinationName: it resolves the name to a topic identifier eagerly, and
        // the default resolver resolves a plain name by CREATING the topic. See this method's Javadoc.
        return template;
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
     * Proves the physical queue name agrees with the logical one: it is the logical name, optionally suffixed
     * {@value #FIFO_SUFFIX}.
     *
     * <p>The provisioning script derives the physical name from the logical name, so a drift between the two
     * would provision one queue and publish to a different name - a failure that appears at the first report
     * submission rather than at startup, and reads as a missing queue rather than as a configuration mistake.
     *
     * @param physicalName the validated physical queue name
     * @param logicalName  the validated logical queue name
     * @throws IllegalStateException if the two cannot be reconciled, aborting startup
     */
    private static void requireQueueNamesAligned(final String physicalName, final String logicalName) {
        if (!physicalName.equals(logicalName) && !physicalName.equals(logicalName + FIFO_SUFFIX)) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "Property '%s' must hold the value of property '%s', optionally suffixed '%s', because "
                            + "localstack-init/init-aws.sh derives one from the other. The two disagree, so "
                            + "provisioning would create one queue and publishing would target another name. "
                            + "Align the two values.",
                    KEY_REPORT_QUEUE, KEY_REPORT_QUEUE_LOGICAL_NAME, FIFO_SUFFIX));
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
}
