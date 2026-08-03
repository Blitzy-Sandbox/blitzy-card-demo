/*
 * ******************************************************************
 * Program     : package-info.java
 * Package     : com.cardemo.config
 * Application : CardDemo
 * Type        : Java package documentation (Spring configuration layer)
 * Function    : Documents the com.cardemo.config package, which holds the
 *               Spring @Configuration classes that replace the mainframe
 *               substrate declarations: the VSAM catalogue and IDCAMS
 *               DEFINE CLUSTER jobs, the CICS CSD security and file
 *               control table, the BMS navigation/AID state, and the GDG
 *               plus transient-data-queue integration points.
 * Source      : app/catlg/LISTCAT.txt (10 clusters, 3 AIX, 3 paths)
 *               + app/csd/CARDDEMO.CSD (18 DEFINE TRANSACTION, 8 DEFINE
 *                 FILE, DEFINE TDQUEUE(JOBS) RECORDSIZE(80))
 *               + app/cpy/COCOM01Y.cpy (CDEMO-USER-TYPE 'A'/'U')
 *               + app/cpy/CVCRD01Y.cpy (CC-WORK-AREAS, CCARD-AID X(5))
 *               + app/jcl/DEFGDGB.jcl + DALYREJS.jcl + REPTFILE.jcl
 *               @ 7756d89
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

/**
 * Spring {@code @Configuration} classes that replace the z/OS substrate declarations of the frozen CardDemo
 * corpus: the VSAM catalogue, the CICS resource definitions, the BMS navigation state and the cloud
 * integration points that stand in for generation data groups and the transient data queue.
 *
 * <h2>What it does</h2>
 *
 * <p>This package contributes no business logic. Every class here is declarative wiring, and the behaviour
 * each one configures is specified by a frozen mainframe artefact rather than by preference. Nothing in this
 * package performs I/O at construction time beyond what the Spring container itself drives.
 *
 * <p><strong>Four classes are present in this package today.</strong> Each is named with the corpus artefact
 * it derives from:
 *
 * <ul>
 *   <li>{@link com.cardemo.config.JpaConfig} - the relational substrate. Entity scanning, naming strategy and
 *       transaction management replacing {@code app/catlg/LISTCAT.txt} and the twelve IDCAMS
 *       {@code DEFINE CLUSTER} jobs. This is where the decimal precisions that parity depends on are
 *       asserted, including the two that differ from the common case: {@code TRAN-CAT-BAL} at
 *       {@code NUMERIC(11,2)} and {@code DIS-INT-RATE} at {@code NUMERIC(6,2)}.</li>
 *   <li>{@link com.cardemo.config.SecurityConfig} - the stateless bearer-token filter chain, role-based
 *       authorisation derived from {@code app/csd/CARDDEMO.CSD}, and BCrypt encoding at strength 10. The
 *       {@code CDEMO-USER-TYPE} 88-levels {@code 'A'} and {@code 'U'} of {@code app/cpy/COCOM01Y.cpy} become
 *       the two authorities.</li>
 *   <li>{@link com.cardemo.config.WebConfig} - URL routing and message conversion replacing the
 *       navigation and attention-identifier state of {@code app/cpy/CVCRD01Y.cpy}, plus the <em>two
 *       distinct</em> COBOL numeric parsers and the edited-amount formatter. The two parsers are not
 *       interchangeable and must not be unified; see that class for why.</li>
 *   <li>{@link com.cardemo.config.AwsConfig} - S3, SQS FIFO and SNS clients pointed at LocalStack, replacing
 *       the seven GDG bases of {@code app/jcl/DEFGDGB.jcl}, {@code DALYREJS.jcl} and {@code REPTFILE.jcl},
 *       and the {@code DEFINE TDQUEUE(JOBS)} contract of the CSD. No code path reaches a live AWS
 *       endpoint and no live credential appears anywhere.</li>
 *   </ul>
 *
 * <h2>Current contents versus the target set</h2>
 *
 * <p>The Agent Action Plan specifies <strong>six</strong> configuration classes, and <strong>all six now
 * exist</strong>. The two that an earlier revision of this document recorded as absent have since been
 * authored, so the absence note is withdrawn rather than left to mislead:
 *
 * <ul>
 *   <li>{@link com.cardemo.config.BatchConfig} carries the {@code Job}, {@code Step} and {@code Flow}
 *       topology, the chunk sizes, and the {@code JobExecutionDecider} that replaces JCL
 *       {@code COND=(0,NE)} gating. Derived from {@code app/jcl/POSTTRAN.jcl},
 *       {@code app/jcl/INTCALC.jcl}, {@code app/jcl/TRANREPT.jcl}, {@code app/jcl/COMBTRAN.jcl} and
 *       {@code app/jcl/CREASTMT.JCL}.</li>
 *   <li>{@link com.cardemo.config.ObservabilityConfig} carries tracing and metric registration and
 *       publishes the application's single {@link java.time.Clock} bean, which every time-dependent
 *       service and processor injects. The three classes in {@code com.cardemo.observability} remain
 *       self-registering alongside it.</li>
 *   </ul>
 *
 * <p>Counts elsewhere in this documentation set are deliberately not restated here. The authoritative dated
 * inventory is section 0.4.5.1 of {@code docs/technical-specifications.md}, and duplicating it in a Javadoc
 * comment is exactly how it drifts.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Compile with {@code ./mvnw -B -ntp clean compile}; run the unit tier with {@code ./mvnw -B -ntp test};
 * run the whole gate with {@code ./mvnw -B -ntp -Ddependency-check.skip=true clean verify}. The
 * {@code -Ddependency-check.skip=true} flag skips only the OWASP vulnerability scan, which needs network
 * access to the vulnerability feed and takes roughly half an hour on a cold cache; drop the flag when the
 * scan is wanted. Compilation is Java 25 at {@code maven.compiler.release} 25 with no preview features, and
 * runs {@code -Xlint:all -Werror} with {@code failOnWarning}, so one raw type, unchecked cast or dangling
 * documentation comment in this package fails the build outright. An unused import does <em>not</em>, because
 * {@code javac} 25 publishes no {@code unused} lint key; Rule 1 Clause B's prohibition on one is enforced at
 * review instead.
 *
 * <p>Prerequisites are capabilities rather than paths: a JDK 25 toolchain on {@code PATH} with
 * {@code JAVA_HOME} set, however the host provides it, and Maven supplied by the pinned repository wrapper.
 * The repository's own contract is the git-ignored {@code .env} file plus {@code ./mvnw}; load it with
 * {@code set -a; . ./.env; set +a} before invoking Maven, because {@code carddemo.security.jwt.signing-key}
 * has no default and a context without it fails fast by design.
 *
 * <p>Running the application needs the {@code local} profile and the Compose topology, which supplies
 * PostgreSQL 16 and the LocalStack endpoint the S3, SQS and SNS clients are pointed at. Bring it up with
 * {@code docker compose up -d}; {@code localstack-init/init-aws.sh} provisions the three buckets, the FIFO
 * queue and the notification topic idempotently, so repeated cycles converge rather than failing on
 * already-existing resources.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>Properties are cited by <strong>key</strong> and never by line number in {@code application.yml}: keys
 * are stable identifiers, whereas line numbers drift on every edit to that file and silently become wrong.
 *
 * <ul>
 *   <li>{@code carddemo.security.jwt.signing-key} - bound from {@code JWT_SIGNING_KEY}. <strong>No
 *       default.</strong> A missing value fails startup, which is the intended behaviour under Rule 1
 *       Clause D rather than a defect to paper over. HS256 requires at least 32 bytes.</li>
 *   <li>{@code carddemo.security.jwt.issuer} defaults to {@code carddemo}, and
 *       {@code carddemo.security.jwt.expiration-minutes} to 30. Both are non-secret metadata, so a
 *       documented default is acceptable where the signing key admits none.</li>
 *   <li>{@code spring.jpa.hibernate.ddl-auto} is {@code validate} in every profile. The schema is owned by
 *       the Flyway migrations, so a column mismatch fails context startup rather than silently migrating.
 *       {@code spring.jpa.open-in-view} is {@code false}.</li>
 *   <li>{@code spring.flyway.baseline-on-migrate} is {@code false} and
 *       {@code spring.flyway.validate-on-migrate} is {@code true}.</li>
 *   <li>{@code spring.batch.job.enabled} is {@code false}. Jobs must never auto-launch on startup; launching
 *       is explicit, which reproduces the fact that the legacy job stream was submitted rather than
 *       triggered by the online region coming up.</li>
 *   <li>{@code carddemo.aws.s3.batch-input-bucket}, {@code carddemo.aws.s3.batch-output-bucket} and
 *       {@code carddemo.aws.s3.statements-bucket} - all three <strong>without defaults</strong>, bound from
 *       {@code CARDDEMO_S3_BATCH_INPUT_BUCKET}, {@code CARDDEMO_S3_BATCH_OUTPUT_BUCKET} and
 *       {@code CARDDEMO_S3_STATEMENTS_BUCKET}. Object versioning is applied to the <em>output</em> bucket
 *       only, which is what {@code localstack-init/init-aws.sh} actually does.</li>
 *   <li>{@code carddemo.aws.sqs.report-queue} and {@code carddemo.aws.sns.notification-topic} - likewise
 *       without defaults.</li>
 *   <li>{@code spring.cloud.aws.region.static} reads {@code ${AWS_REGION:${AWS_DEFAULT_REGION}}}, so either
 *       spelling satisfies it.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>Context fails to start naming {@code carddemo.security.jwt.signing-key}</dt>
 *   <dd>{@code JWT_SIGNING_KEY} is unset or shorter than 32 bytes. This is the fail-fast path working as
 *       designed. Load {@code .env} as shown above; do not add a literal default to any profile, and do not
 *       introduce a second variable name - {@code JWT_SIGNING_KEY} is the single spelling used across the
 *       repository.</dd>
 *
 *   <dt>{@code Schema-validation: missing column} or a wrong-type failure at startup</dt>
 *   <dd>{@code ddl-auto} is {@code validate}, so the entity mapping and the migrated schema have diverged.
 *       Fix the migration or the entity - never switch {@code ddl-auto} to {@code update}, which would let
 *       Hibernate invent a column width and silently break the fixed-width field contract that parity
 *       depends on.</dd>
 *
 *   <dt>A placeholder-resolution failure naming a bucket, queue or topic variable</dt>
 *   <dd>The named environment variable is unset. All three bucket properties, the queue and the topic are
 *       deliberately default-free so that a run cannot write into whatever bucket happens to exist.</dd>
 *
 *   <dt>S3, SQS or SNS calls time out or resolve to a real AWS endpoint</dt>
 *   <dd>The {@code local} profile's LocalStack endpoint override is not active. Confirm the profile and that
 *       the Compose stack is up. Reaching a live endpoint is a defect, not a fallback: this project uses
 *       LocalStack exclusively and carries no live credentials.</dd>
 *
 *   <dt>Every request returns 401, or {@code /api/admin/*} returns 403 for an administrator</dt>
 *   <dd>Sign-on is the only unauthenticated operation, and the session policy is {@code STATELESS} because
 *       the COMMAREA has no server-side successor. For the 403, check that the token's role claim carries
 *       the authority derived from {@code CDEMO-USER-TYPE} {@code 'A'}.</dd>
 *
 *   <dt>A job runs at application startup</dt>
 *   <dd>{@code spring.batch.job.enabled} has been set to {@code true}. Restore {@code false}; auto-launching
 *       every job on every boot has no legacy analogue.</dd>
 *
 *   <dt>{@code /actuator/health/readiness} returns 404, or the context will not boot after adding a health
 *       contributor</dt>
 *   <dd>Actuator exposure is limited to {@code health,info,prometheus} by
 *       {@code management.endpoints.web.exposure.include}, and
 *       {@code management.endpoint.health.validate-group-membership} is left at its secure default of
 *       {@code true} - so naming a contributor that does not exist yet makes the application unbootable.
 *       Add the contributor and its group membership in the same change.</dd>
 *   </dl>
 *
 * <h2>Package level constraints</h2>
 *
 * <ul>
 *   <li>No business logic, no entity, no DTO and no repository is declared here. This package wires; it does
 *       not decide.</li>
 *   <li>No secret literal may appear in any class in this package. Every credential and every endpoint is
 *       resolved from the environment through a property key.</li>
 *   <li>No class here may relax a strict setting to make something pass: not {@code ddl-auto}, not
 *       {@code validate-on-migrate}, not the Actuator exposure list, and not the compiler's
 *       {@code failOnWarning}.</li>
 *   <li>Field widths, decimal precisions and record lengths configured here derive from the copybooks and
 *       the catalogue, never from convenience. Silent truncation or widening breaks parity in a way no test
 *       catches unless the contract is asserted.</li>
 *   </ul>
 *
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License, Version 2.0</a>
 */

package com.cardemo.config;
