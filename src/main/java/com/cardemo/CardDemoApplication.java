/*
 * ******************************************************************
 * Program     : CardDemoApplication.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 application entry point
 * Function    : Single entry point replacing the CICS region and the
 *               JES2 initiators.
 * Source      : app/csd/CARDDEMO.CSD - the CICS region definition:
 *               18 DEFINE TRANSACTION, 18 DEFINE PROGRAM,
 *               8 DEFINE FILE, 17 DEFINE MAPSET and the single
 *               DEFINE TDQUEUE(JOBS) at L499-L505 - together with
 *               the JES2-executed JCL stream app/jcl/POSTTRAN.jcl,
 *               app/jcl/INTCALC.jcl, app/jcl/TRANREPT.jcl,
 *               app/jcl/COMBTRAN.jcl and app/jcl/CREASTMT.JCL
 *               (uppercase extension) @ 7756d89
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
package com.cardemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Sole Spring Boot entry point of the CardDemo Java target: one process that replaces both halves of the
 * legacy z/OS runtime at once.
 *
 * <p>This type is deliberately the smallest class in the tree. It bootstraps and delegates; it configures
 * nothing. Bean definitions live in the {@code com.cardemo.config} classes - four authored today of a
 * target six - and the exhaustive package narrative lives in {@code com.cardemo.package-info.java}, which
 * this summary does not restate.
 *
 * <p><strong>What it does.</strong> It replaces the <em>CICS region</em> - 17 online transaction programs
 * driven from 3270 terminals in pseudo-conversational mode, with screen state held in the COMMAREA and
 * navigation performed by {@code EXEC CICS XCTL PROGRAM(...)} at {@code app/cbl/COMEN01C.cbl:L153} - and the
 * <em>JES2 initiators</em>, which executed a 29-member JCL stream of DFSORT, IDCAMS, IEBGENER and IEFBR14
 * utility steps gated by {@code COND=(0,NE)} return-code tests. The region itself was declared in
 * {@code app/csd/CARDDEMO.CSD}: 18 {@code DEFINE TRANSACTION}, 18 {@code DEFINE PROGRAM},
 * 8 {@code DEFINE FILE} and 17 {@code DEFINE MAPSET}. Seventeen of those transactions have both a source
 * program and a mapset; the eighteenth, {@code CDV1}, names {@code PROGRAM(COCRDSEC)} at
 * {@code app/csd/CARDDEMO.CSD:L390}, and {@code COCRDSEC} has no source file anywhere in the repository, so
 * the REST surface exposes exactly 17 operations rather than 18 and no endpoint is invented for it.
 *
 * <p><strong>Deployment shape: a single deployable JAR - a modular monolith, explicitly not
 * microservices.</strong> Atomicity is the decisive constraint, not preference.
 * {@code EXEC CICS SYNCPOINT ROLLBACK} at {@code app/cbl/COACTUPC.cbl:L4098-L4102} spans an account write and
 * a customer write inside one unit of work, and {@code 2000-POST-TRANSACTION} commits the ordered chain
 * {@code 2700-UPDATE-TCATBAL} then {@code 2800-UPDATE-ACCOUNT-REC} then
 * {@code 2900-WRITE-TRANSACTION-FILE} together at {@code app/cbl/CBTRN02C.cbl:L425-L443}. Distributing those
 * writes across service boundaries would require compensating transactions and would forfeit the behavioural
 * parity that is this migration's acceptance contract.
 *
 * <p>Component scanning is rooted at {@code com.cardemo} - the default of {@code @SpringBootApplication},
 * which is why no {@code scanBasePackages} attribute appears below - so it reaches whatever is authored,
 * without enumeration. The subpackage counts are stated as <strong>present / target</strong> wherever the two
 * differ, so that a planned type is never read as a delivered one: {@code config} 6, {@code security} 4,
 * {@code model} (entity 11, key 3, enums 4, dto 26), {@code repository} 11, {@code service} 21 across nine
 * leaves, {@code controller} <strong>8</strong> exposing <strong>17 operations</strong>, {@code batch}
 * (jobs <strong>3 / 6</strong>, processors 5, readers <strong>6 / 7</strong>, writers 3), {@code exception} 9
 * and {@code observability} 3.
 *
 * <p><strong>What is still to be authored</strong>, and nothing else: two batch jobs -
 * {@code CombineTransactionsJob} and {@code BatchPipelineOrchestrator}. Each is named individually in the leaf
 * document for its package. The <strong>controller layer is complete</strong>: all eight controllers are
 * authored and all seventeen operations of {@code app/csd/CARDDEMO.CSD} are exposed. The <strong>reader layer
 * is likewise complete</strong>: all seven batch readers are authored.
 *
 * <p><strong>Finding M-09, severity Medium, RESOLVED.</strong> This paragraph previously reported six of eight
 * controllers, twelve of seventeen operations, one of six batch jobs, five of seven readers, and whole-tree
 * totals of 143 files and 119 types plus 24 package documents. Every one of those had been overtaken by the
 * work, so the census understated what was delivered - which is the more damaging direction for an evidence
 * artefact to be wrong in, because a reader concludes that authored, tested code does not exist. The values
 * above are the measured ones, and the coupled sentence naming what remains was corrected in the same edit so
 * that no two statements here can disagree.
 *
 * <p>The whole-tree file totals are <strong>deliberately no longer restated</strong>. They change with every
 * file added anywhere in the tree, they were the first figures to go stale, and a reader who needs them is
 * better served by the command that produces them than by a number that was true once:
 * {@code find src/main/java -name '*.java' | wc -l} for the total, and the same with
 * {@code -name 'package-info.java'} for the package documents. The per-package counts above are kept because
 * they are stable, they orient a reader in the tree, and {@code InventoryCountGateTest} fails the build if
 * they drift. The authoritative dated inventory is section 0.4.5.1 of
 * {@code docs/technical-specifications.md}. Dependencies are supplied by constructor injection throughout;
 * field and setter injection are not used anywhere.
 *
 * <p><strong>Batch jobs do not run at startup.</strong> {@code spring.batch.job.enabled} is {@code false},
 * because the framework default of {@code true} would run every job on every boot. Launching is explicit.
 * The two intended launch paths are the planned {@code com.cardemo.batch.jobs.BatchPipelineOrchestrator} and the SQS
 * listener that replaces the JES2 internal reader behind {@code DEFINE TDQUEUE(JOBS) TYPE(EXTRA)
 * DDNAME(INREADER) TYPEFILE(OUTPUT) RECORDSIZE(80) RECORDFORMAT(FIXED) DISPOSITION(MOD)} at
 * {@code app/csd/CARDDEMO.CSD:L499-L505}; <strong>both are planned rather than authored</strong>, so a job is
 * currently driven from a test or by launching its {@code Job} bean directly.
 *
 * <p><strong>How to build, run and test.</strong> Build with the pinned wrapper: {@code ./mvnw clean verify}.
 * {@code maven-enforcer-plugin:3.5.0} floors the toolchain at Java {@code [25,)} and Maven
 * {@code [3.9.11,)}, so a wrong JDK fails the build rather than producing surprising bytecode. Compilation is
 * warning-fatal - {@code maven-compiler-plugin:3.14.1} runs {@code -Xlint:all} with {@code -Werror} and
 * {@code failOnWarning}, at {@code release} 25 with no preview features. Coverage is gated by JaCoCo 0.8.12 at
 * 80% LINE. Tests run under Surefire 3.5.4 (unit) and Failsafe 3.5.4 (integration and end-to-end, bound to
 * {@code verify}), with Testcontainers pinned to 2.0.3 using only the four prefixed module coordinates,
 * because the bare 1.x artefact ids do not exist on the 2.x line.
 *
 * <p>Run the packaged artefact with {@code java -jar target/carddemo-1.0.0.jar}, or in place with
 * {@code ./mvnw spring-boot:run}, against the local topology brought up by {@code docker compose up}
 * (PostgreSQL 16, LocalStack, Jaeger, Prometheus and Grafana). Three profiles exist: {@code local},
 * {@code test} and {@code prod}. The AWS endpoint override is present only in {@code local} and {@code test};
 * the base profile and {@code prod} carry none, so a silent fall-back to a live AWS endpoint is structurally
 * impossible. There are no credential defaults anywhere, and every AWS interaction targets LocalStack with
 * zero live credentials.
 *
 * <p><strong>Key configuration and defaults.</strong> {@code src/main/resources/application.yml} is the
 * authority and carries the full rationale per property; the startup-critical few are:
 *
 * <ul>
 *   <li>{@code carddemo.security.jwt.signing-key} resolves from {@code ${JWT_SIGNING_KEY}} with <em>no
 *       default, no example, no fallback and no committed value</em>. An absent variable leaves the placeholder
 *       unresolvable, which fails property resolution and therefore fails startup - the required behaviour,
 *       since a committed default would let the application boot with a key an attacker already knows. The
 *       variable is {@code JWT_SIGNING_KEY}: {@code .env.example} ships that name empty and marked required,
 *       and one repository-wide rename made it the single name used everywhere, so the superseded
 *       {@code JWT_SECRET} is not an accepted alternative spelling. An environment still exporting the old
 *       name produces the fail-fast below, and the remedy is to rename the variable rather than to reintroduce
 *       a second name for one secret. The correction is registered in {@code application.yml}.</li>
 *   <li>{@code carddemo.security.jwt.issuer} defaults to {@code carddemo} and
 *       {@code carddemo.security.jwt.expiration-minutes} to 30, bound from {@code ${JWT_EXPIRATION_MINUTES}}
 *       and accepted only within 1..1440. Both are non-secret metadata, so documented defaults are acceptable
 *       where the signing key admits none. The lifetime is expressed in <em>minutes</em>: it was previously
 *       {@code expiration-seconds} with a 3,600-second default, which is double the approved bearer window,
 *       and a bearer token cannot be revoked before it expires.</li>
 *   <li>{@code spring.batch.job.enabled} is {@code false}; {@code spring.jpa.hibernate.ddl-auto} is
 *       {@code validate} in every profile, never {@code update} or {@code create};
 *       {@code spring.jpa.open-in-view} is {@code false}; the Hibernate JDBC time zone is UTC.</li>
 *   <li>Flyway applies exactly three migrations from {@code classpath:db/migration}, of which {@code V1}
 *       creates exactly 11 business tables. The Spring Batch {@code BATCH_*} metadata tables come from the
 *       framework's own {@code schema-postgresql.sql} and never from a fourth migration.
 *       {@code validate-on-migrate} and {@code clean-disabled} are both {@code true}.</li>
 *   <li>Actuator exposes exactly {@code health}, {@code info} and {@code prometheus}, with separate liveness
 *       and readiness groups: liveness is {@code livenessState}, readiness is {@code readinessState} plus the
 *       {@code db} contributor. Object-storage and queue contributors join the readiness group in the same
 *       change that introduces {@code com.cardemo.observability.HealthIndicators} - Spring Cloud AWS 3.3.0
 *       ships none, and because health group-membership validation is left at its secure default, naming a
 *       contributor before it exists would make the application unbootable; see the object-storage and
 *       queue-readiness entry in the residual-risk commentary of
 *       {@code src/main/resources/application.yml}.</li>
 *   <li>BCrypt strength is 10; {@code /api/admin/*} is restricted to the administrator role; sign-on is the
 *       only unauthenticated operation; the session policy is {@code STATELESS}, since the COMMAREA has no
 *       server-side successor.</li>
 *   </ul>
 *
 * <p><strong>Common failure modes and troubleshooting.</strong>
 *
 * <ul>
 *   <li><em>Absent or invalid {@code JWT_SIGNING_KEY}</em> - a deliberate fail-fast during context refresh. The
 *       reported cause names both the variable and the property that needs it: a placeholder resolution
 *       failure for {@code JWT_SIGNING_KEY} within {@code carddemo.security.jwt.signing-key}. Remedy: export
 *       the variable from the environment with at least 32 bytes of entropy, which HS256 requires. Adding a
 *       default is not an available remedy, and neither is exporting the superseded {@code JWT_SECRET}.</li>
 *   <li><em>Flyway validation failure</em> - schema drift, or an attempt to introduce migrations or tables
 *       beyond the three-migration, 11-table contract. Because {@code clean-disabled} is {@code true},
 *       recovery is by correcting the migration, never by cleaning the schema.</li>
 *   <li><em>Database or LocalStack unreachable</em> - readiness reports down while liveness stays up. Check
 *       {@code docker compose ps}, then re-run the idempotent {@code localstack-init/init-aws.sh}, which
 *       converges rather than failing on resources that already exist.</li>
 *   <li><em>{@code @EnableBatchProcessing} added to this class</em> - the single most likely regression in
 *       this file, which is why it is called out here. On Spring Boot 3.x that annotation <em>disables</em>
 *       Boot's Spring Batch auto-configuration, so the {@code JobRepository}, {@code JobLauncher} and
 *       {@code JobExplorer} beans that the authored batch layer needs - and that the authored
 *       {@code com.cardemo.config.BatchConfig} and the planned
 *       {@code com.cardemo.batch.jobs.BatchPipelineOrchestrator} will depend on - silently disappear.
 *       Remedy: remove
 *       it and rely on auto-configuration. This class therefore carries {@code @SpringBootApplication} and
 *       nothing else.</li>
 *   <li><em>Batch exit codes are contracts, not diagnostics.</em> Return code 4 is set if and only if the
 *       reject count exceeds zero ({@code app/cbl/CBTRN02C.cbl:L202-L234}); return code 8 is failure; and an
 *       unexpected {@code FILE STATUS} abends with code 999 and return code 12
 *       ({@code app/cbl/CBTRN02C.cbl:L707-L710}). They are surfaced by the batch layer, never by this
 *       bootstrap.</li>
 *   <li><em>Diagnosis.</em> Structured JSON logs carry {@code traceId}, {@code spanId} and
 *       {@code correlationId} in the MDC, the last populated by
 *       {@code com.cardemo.observability.CorrelationIdFilter}. That filter is <strong>additive
 *       request-correlation capability, not a translation of source code</strong>: the frozen corpus has no
 *       per-request identifier to translate, and {@code EIBTRNID} - a CICS-supplied field - occurs
 *       <strong>zero times anywhere under {@code app/**}</strong>. It fills the role CICS played implicitly,
 *       which is a different claim from replacing a construct that exists in the source. Batch events
 *       additionally carry the job instance identifier, which is what makes a run's logs and its
 *       object-storage prefixes correlatable.</li>
 *   <li><em>Container-runtime prerequisite.</em> The Testcontainers tiers and {@code docker compose up}
 *       require a running daemon and an accessible socket. Where that evidence cannot be produced the gate is
 *       reported as "Not available" together with the prerequisite, never as an untested pass.</li>
 *   </ul>
 *
 * <p>No throughput, latency or availability target is asserted here or anywhere else: the legacy corpus
 * publishes no service-level objective, so the performance gate records a measured baseline rather than an
 * improvement target.
 */
@SpringBootApplication
public class CardDemoApplication {

    /**
     * Creates the bootstrap type. Declared explicitly, and documented, for two reasons that are easy to
     * regress.
     *
     * <p>First, {@code @SpringBootApplication} carries {@code @Configuration}, whose bean methods are
     * CGLIB-proxied, so the class must not be {@code final} and must expose a non-private no-argument
     * constructor - proxy creation fails otherwise. Second, an implicit default constructor carries no
     * comment, which {@code -Xdoclint} reports as a warning; that is now a build failure rather than a
     * remark, because {@code pom.xml} binds a {@code maven-javadoc-plugin} execution to {@code verify}
     * with {@code doclint} set to {@code all} and {@code failOnWarnings} true. It would additionally
     * become a javac error were a {@code module-info.java} introduced, since that makes javac's
     * {@code missing-explicit-ctor} lint fire under the build's {@code -Werror} setting.
     *
     * <p>The constructor takes no arguments and holds no state: this type has no fields, and every dependency
     * in the tree is supplied by constructor injection into the beans themselves, never into the bootstrap.
     * Spring instantiates it while building the configuration class model; application code has no reason to.
     */
    public CardDemoApplication() {
        // Intentionally empty: the bootstrap holds no state. See the constructor Javadoc above for why this
        // constructor is declared explicitly rather than left implicit.
    }

    /**
     * Boots the Spring application context and hands control to the framework.
     *
     * <p>This method delegates and does nothing else, so that startup behaviour is entirely determined by
     * {@code application.yml}, the active profile and the auto-configuration report rather than by logic
     * hidden in the bootstrap.
     *
     * <p><strong>Side effects.</strong> Refreshing the context starts the embedded servlet container,
     * applies the Flyway migrations, opens the connection pool, validates the entity mapping against the
     * migrated schema, registers the SQS listener that replaces the JES2 internal reader and publishes the
     * Actuator endpoints. No Spring Batch job is executed, because {@code spring.batch.job.enabled} is
     * {@code false}.
     *
     * <p><strong>Error modes.</strong> Any startup failure - an unresolvable {@code JWT_SIGNING_KEY} placeholder,
     * a Flyway validation mismatch, an unreachable datasource or a bean wiring defect - propagates out of
     * this method unhandled, so the JVM terminates with a non-zero status and the framework's failure
     * analysis reaches the operator intact. The call is deliberately not wrapped in {@code try}/{@code catch}:
     * swallowing the exception would hide the root cause and defeat the fail-fast contract that protects the
     * signing key. Shutdown is likewise owned by the Spring lifecycle, so this method never calls
     * {@code System.exit}.
     *
     * @param args the process command-line arguments, forwarded verbatim to Spring for property-source
     *     binding. They are treated as untrusted and are never parsed, echoed or logged here, because a
     *     command line can legitimately carry the signing key or database credentials.
     */
    public static void main(String[] args) {
        SpringApplication.run(CardDemoApplication.class, args);
    }
}
