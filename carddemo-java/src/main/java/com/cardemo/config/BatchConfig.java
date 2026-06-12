package com.cardemo.config;

import org.springframework.context.annotation.Configuration;

/**
 * Foundational Spring Batch infrastructure configuration for the greenfield Java 25 LTS + Spring
 * Boot 3.5.11 migration of the AWS CardDemo COBOL/CICS/VSAM/JCL/BMS mainframe application.
 *
 * <p>This {@code @Configuration} formalizes the batch-runtime policy that backs the
 * <strong>JCL&nbsp;&rarr;&nbsp;Spring&nbsp;Batch</strong> migration (decision <strong>D-005</strong>,
 * tech-spec L915): the legacy 5-stage pipeline
 * {@code POSTTRAN &rarr; INTCALC &rarr; COMBTRAN &rarr; CREASTMT/TRANREPT}. It is component-scanned by
 * {@code CardDemoApplication} (base package {@code com.cardemo}, decision <strong>D-006</strong>
 * &mdash; deliberately <em>not</em> {@code com.carddemo}), which delegates all cross-cutting
 * configuration to the {@code config} package. Like its sibling {@code JpaConfig}, this class is
 * intentionally <strong>lean</strong>: its most important contract is what it does <em>not</em> do
 * (it neither defines jobs/steps nor reimplements the auto-configured batch infrastructure), and it
 * exists primarily to (a) document the JCL&rarr;Spring&nbsp;Batch substitution at its point of change
 * and (b) flag the cross-component coordination the batch runtime depends on. The concrete
 * {@code Job}/{@code Step}/{@code ItemReader}/{@code ItemProcessor}/{@code ItemWriter} beans &mdash;
 * and all sequencing/condition-code logic &mdash; live in {@code com.cardemo.batch.*} (created by
 * other agents) and <em>consume</em> the infrastructure described here; they are explicitly NOT
 * defined in this file (see the "intentionally no beans" note in the class body).
 *
 * <h2>Migration provenance (AAP &sect;0.7.1 / &sect;0.7.2)</h2>
 * <p>This is a brand-new file with <strong>no COBOL source equivalent</strong>; it is a net-new
 * technology-substitution layer with no analogue in the legacy estate. Its conceptual source is the
 * {@code app/jcl/*} job-execution model &mdash; for example {@code app/jcl/POSTTRAN.jcl} runs
 * {@code EXEC PGM=CBTRN02C} with DD allocations ({@code TRANFILE}, {@code DALYTRAN}, {@code XREFFILE},
 * {@code DALYREJS}, {@code ACCTFILE}, {@code TCATBALF}), and {@code app/jcl/INTCALC.jcl} runs
 * {@code EXEC PGM=CBACT04C,PARM='2022071800'} (a processing-date parameter). Each such JCL job is a
 * discrete unit submitted to JES <em>on demand</em>, never auto-started. Behaviour for the wider
 * application is translated from the frozen AWS CardDemo COBOL baseline at commit SHA
 * {@code 27d6c6f}; the COBOL/JCL source is <em>never copied</em> into this repository, and
 * traceability to the legacy baseline is by that commit SHA only.
 *
 * <h2>Technology substitution (AAP &sect;0.7.1 &mdash; documented at the point of change)</h2>
 * <p>The governing transformation contract (tech-spec transformation map, AAP &sect;0.1.2) and the
 * batch pipeline rules (tech-spec &sect;0.8.5, L1067&ndash;L1075; AAP &sect;0.7.6) map the legacy JCL
 * constructs to Spring Batch as follows. Note that this class supplies the runtime these mappings
 * rely on; the mappings themselves are <em>realized in</em> {@code com.cardemo.batch.*}, not here:</p>
 * <dl>
 *   <dt>{@code JCL EXEC PGM + DD statements} &rarr; Spring Batch {@code Job}/{@code Step} beans
 *       (tech-spec L83)</dt>
 *   <dd>Each {@code EXEC PGM} step becomes a {@code @Configuration}-declared {@code Step} composed
 *       into a {@code Job}; DD allocations become typed {@code ItemReader}/{@code ItemWriter} sources
 *       (PostgreSQL via JPA, or S3 via Spring Cloud AWS). Those beans live under
 *       {@code com.cardemo.batch.jobs} / {@code readers} / {@code writers}.</dd>
 *
 *   <dt>{@code JCL COND codes} &rarr; {@code JobExecutionDecider} + {@code FlowBuilder}
 *       (tech-spec L84; AAP &sect;0.7.6)</dt>
 *   <dd>JCL condition-code routing is reproduced with Spring Batch {@code ExitStatus} +
 *       {@code JobExecutionDecider} inside {@code BatchPipelineOrchestrator}; the sequential
 *       dependency chain ({@code POSTTRAN &rarr; INTCALC &rarr; COMBTRAN &rarr; CREASTMT/TRANREPT}) is
 *       expressed there, NOT in this infrastructure class.</dd>
 *
 *   <dt>{@code DFSORT / IDCAMS REPRO} &rarr; {@code Comparator} + bulk insert
 *       (tech-spec L85 &amp; &sect;0.8.5; AAP &sect;0.7.6)</dt>
 *   <dd>{@code COMBTRAN.jcl}'s sort/merge (no COBOL program) becomes a Java {@code Comparator}
 *       matching the {@code SORT FIELDS} spec followed by a JPA {@code saveAll}/{@code JdbcTemplate}
 *       bulk insert inside the combine step's {@code ItemProcessor}/{@code ItemWriter}.</dd>
 *
 *   <dt>Parallel stages 4a/4b ({@code CREASTMT} &#8741; {@code TRANREPT}) &rarr;
 *       {@code FlowBuilder.split(...)} (tech-spec &sect;0.8.5; AAP &sect;0.7.6, L1073)</dt>
 *   <dd>After {@code COMBTRAN} completes, statement generation and the transaction report may run
 *       concurrently. The {@code FlowBuilder.split(TaskExecutor)} that realizes this &mdash; together
 *       with the {@code TaskExecutor} that powers it &mdash; is an <em>orchestration</em> concern and
 *       therefore belongs to the {@code batch/jobs} layer that owns the flow (see the
 *       "intentionally no beans" note below for the rationale on why the executor is not declared
 *       here).</dd>
 * </dl>
 *
 * <h2>Why {@code @EnableBatchProcessing} is intentionally ABSENT (critical Boot-3.x gotcha)</h2>
 * <p>This class is annotated only with {@link Configuration}. It deliberately does <strong>NOT</strong>
 * declare {@code @EnableBatchProcessing}. In Spring Boot&nbsp;3.x, adding {@code @EnableBatchProcessing}
 * anywhere in the context <strong>disables</strong> Boot's batch auto-configuration
 * ({@code BatchAutoConfiguration} backs off when a user-supplied {@code @EnableBatchProcessing} or
 * {@code DefaultBatchConfiguration} is present) &mdash; the exact opposite of the intent. The batch
 * infrastructure (the JDBC {@code JobRepository}, the {@code JobLauncher}, the {@code JobExplorer}, the
 * batch {@code DataSource}/transaction manager) is provided by {@code spring-boot-starter-batch}'s
 * auto-configuration. This class only customises and documents that auto-config; it must never
 * re-enable batch manually. The same prohibition is reaffirmed on {@code CardDemoApplication} ("do NOT
 * use {@code @EnableBatchProcessing} on the app class").</p>
 *
 * <h2>Job-launch policy &mdash; jobs do NOT auto-run on startup</h2>
 * <p>The legacy jobs are JES-submitted on demand and, in the target, the report job is
 * <strong>SQS-triggered</strong> ({@code CORPT00C} &rarr; CICS TDQ {@code JOBS} &rarr; JES becomes an
 * SQS message on {@code carddemo-report-jobs.fifo}) while the pipeline is driven by
 * {@code BatchPipelineOrchestrator}. Jobs must therefore <strong>not</strong> all execute at
 * application startup.</p>
 * <p>By default in Spring Boot&nbsp;3.5.x the {@code JobLauncherApplicationRunner} is active
 * ({@code spring.batch.job.enabled} defaults to {@code true} via {@code matchIfMissing}) and, when no
 * {@code spring.batch.job.name} is set, it launches <em>every</em> {@code Job} bean on startup. That
 * is suppressed declaratively by <strong>{@code spring.batch.job.enabled=false}</strong> in
 * {@code application.yml} (a single source of truth, mirroring how {@code JpaConfig} leaves
 * {@code spring.jpa.hibernate.ddl-auto=validate} in {@code application.yml} rather than in code). This
 * class therefore registers <strong>no</strong> always-on {@code ApplicationRunner}/
 * {@code CommandLineRunner} that launches jobs. On-demand launching (the orchestrator and the
 * SQS-triggered {@code ReportSubmissionService}) is performed through the auto-configured
 * {@code JobLauncher} (a synchronous {@code TaskExecutorJobLauncher}) injected into those consumers; no
 * custom launcher is defined here (see the "intentionally no beans" note for the rationale).</p>
 *
 * <h2>{@code JobRepository} &amp; transaction manager (rely on auto-config)</h2>
 * <p>The pipeline requires <strong>persistent, restartable</strong> batch metadata, so this class
 * relies on Boot's auto-configured <strong>JDBC</strong> {@code JobRepository} (backed by the
 * application {@code DataSource}) and the shared {@code PlatformTransactionManager} (the JPA
 * {@code JpaTransactionManager} contributed by {@code spring-boot-starter-data-jpa}). A
 * {@code Map}/in-memory {@code JobRepository} is deliberately <strong>never</strong> defined: it would
 * lose job/step execution history across restarts and break restartability.</p>
 *
 * <h2>COORDINATION &mdash; Spring Batch metadata tables are Flyway-provisioned (read this)</h2>
 * <p>Because Flyway owns the entire schema and {@code spring.jpa.hibernate.ddl-auto=validate} (set in
 * {@code application.yml}), Hibernate will <strong>not</strong> create any tables &mdash; including the
 * Spring Batch metadata tables ({@code BATCH_JOB_INSTANCE}, {@code BATCH_JOB_EXECUTION},
 * {@code BATCH_JOB_EXECUTION_PARAMS}, {@code BATCH_JOB_EXECUTION_CONTEXT}, {@code BATCH_STEP_EXECUTION},
 * {@code BATCH_STEP_EXECUTION_CONTEXT}, and the {@code BATCH_*_SEQ} sequences). These tables must
 * therefore be provisioned by <strong>Flyway</strong> (DDL added to a {@code db/migration} script
 * &mdash; e.g. appended to {@code V1__create_schema.sql} or a dedicated migration; the canonical DDL is
 * the PostgreSQL {@code schema-postgresql.sql} shipped inside {@code spring-batch-core}). To avoid
 * racing or conflicting with Flyway's ownership, Spring Batch's own schema initializer is turned off
 * via <strong>{@code spring.batch.jdbc.initialize-schema=never}</strong> in {@code application.yml}
 * (the Boot default {@code embedded} already skips a non-embedded PostgreSQL, but {@code never} makes
 * the intent explicit and safe under every profile). <strong>Action for the {@code db/migration}
 * agent:</strong> ensure the {@code BATCH_*} tables/sequences exist in a Flyway migration, or every
 * batch job will fail at runtime with "Table 'BATCH_JOB_INSTANCE' not found". Setting
 * {@code initialize-schema=always} here is explicitly avoided.</p>
 *
 * <h2>Minimal Change Clause &amp; secret policy (AAP &sect;0.7.1 / &sect;0.7.2)</h2>
 * <p>No speculative batch features are introduced: no remote/partitioned steps, no custom batch
 * {@code DataSource} or connection pool beyond what the auto-configuration already provides, and no
 * job sequencing. This class contains <strong>no</strong> credentials, secrets, or {@code @Value}
 * wiring, and emits no {@code System.out} output; every externalized value (datasource, AWS endpoints)
 * is resolved by Boot from {@code application*.yml} {@code ${ENV:default}} placeholders.</p>
 *
 * @see Configuration
 * @see org.springframework.batch.core.repository.JobRepository
 * @see org.springframework.batch.core.launch.JobLauncher
 */
@Configuration
public class BatchConfig {

    /*
     * Intentionally NO beans, fields, or constructor logic.
     *
     * This class is the documented, component-scanned home of the Spring Batch INFRASTRUCTURE policy;
     * it does not itself contribute runtime beans. Each item below is deliberately omitted (AAP §0.7.1
     * Minimal Change Clause); each would either duplicate Spring Boot auto-configuration, add unused
     * infrastructure, or place an orchestration concern in the wrong layer:
     *
     *   - @EnableBatchProcessing : NOT declared. In Spring Boot 3.x it DISABLES Boot's batch
     *     auto-configuration (BatchAutoConfiguration backs off), which would remove the very
     *     JobRepository/JobLauncher/JobExplorer beans the pipeline needs. The starter
     *     (spring-boot-starter-batch) auto-configures all of it; this class must never re-enable batch
     *     manually. (See the class Javadoc "@EnableBatchProcessing is intentionally ABSENT" section.)
     *
     *   - Custom JobRepository : NOT declared. Boot auto-configures a persistent JDBC JobRepository
     *     over the application DataSource, which the 5-stage pipeline requires for restartable
     *     metadata. A Map/in-memory JobRepository is never used (it would lose execution history on
     *     restart). spring.batch.jdbc.initialize-schema=never (application.yml) keeps Flyway the sole
     *     owner of the BATCH_* metadata tables (see the COORDINATION section of the class Javadoc).
     *
     *   - Custom JobLauncher : NOT declared. The auto-configured launcher (a synchronous
     *     TaskExecutorJobLauncher wrapping the auto-configured JobRepository) is sufficient: the
     *     BatchPipelineOrchestrator wants synchronous, sequential stage execution, and the
     *     SQS-triggered ReportSubmissionService launches on the listener thread. Both simply @Autowire
     *     the auto-configured JobLauncher. Declaring an async launcher here would be speculative and
     *     would change global launch semantics (Minimal Change Clause).
     *
     *   - Startup ApplicationRunner / CommandLineRunner that launches jobs : NOT declared. Jobs must
     *     not auto-run on startup; the no-auto-run policy is realized declaratively by
     *     spring.batch.job.enabled=false in application.yml (see the "Job-launch policy" section of
     *     the class Javadoc), not by an always-on runner.
     *
     *   - PlatformTransactionManager : NOT declared. Spring Batch shares the JPA JpaTransactionManager
     *     auto-configured by spring-boot-starter-data-jpa, keeping batch writes and JPA writes under
     *     one transactional resource. Adding a second transaction manager would risk ambiguity.
     *
     *   - Shared split()/parallel TaskExecutor (for stages 4a/4b CREASTMT‖TRANREPT) : NOT declared
     *     here. The split flow and its executor are an ORCHESTRATION concern owned by the batch/jobs
     *     layer (BatchPipelineOrchestrator / StatementGenerationJob), which is also where the pool
     *     sizing for that specific workload is best decided. Additionally, declaring any
     *     Executor/TaskExecutor bean in the context would suppress Boot's auto-configured
     *     `applicationTaskExecutor` (its OnExecutorCondition is @ConditionalOnMissingBean(Executor)),
     *     so the executor is intentionally created next to its consumer rather than as global
     *     infrastructure here. When stages 4a/4b are implemented, define a clearly-named,
     *     bounded ThreadPoolTaskExecutor in that flow's @Configuration and pass it to
     *     FlowBuilder.split(...).
     *
     *   - Job/Step/Reader/Processor/Writer beans and any sequencing/condition-code logic : NOT
     *     declared. These live in com.cardemo.batch.* (jobs/processors/readers/writers) and consume
     *     the infrastructure documented here.
     */
}
