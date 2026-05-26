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
package com.awsm2.carddemo.glue;

import com.awsm2.carddemo.exception.CardDemoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.GetJobRunRequest;
import software.amazon.awssdk.services.glue.model.GetJobRunResponse;
import software.amazon.awssdk.services.glue.model.GlueException;
import software.amazon.awssdk.services.glue.model.JobRunState;
import software.amazon.awssdk.services.glue.model.StartJobRunRequest;
import software.amazon.awssdk.services.glue.model.StartJobRunResponse;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * AWS Glue ETL configuration for CardDemo flat-file pipelines.
 *
 * <p><b>Replaces:</b> GDG-driven flat-file pipelines defined by the following
 * JCL members:</p>
 * <ul>
 *   <li>{@code app/jcl/REPTFILE.jcl} &mdash; IDCAMS DEFINE
 *       GENERATIONDATAGROUP NAME(AWS.M2.CARDDEMO.TRANREPT) LIMIT(10) for the
 *       transaction report output GDG.</li>
 *   <li>{@code app/jcl/DEFGDGB.jcl} &mdash; six GDG bases (LIMIT=5, SCRATCH):
 *       TRANSACT.BKUP, TRANSACT.DALY, TRANREPT, TCATBALF.BKUP, SYSTRAN, and
 *       TRANSACT.COMBINED.</li>
 *   <li>{@code app/jcl/DALYREJS.jcl} &mdash; IDCAMS DEFINE
 *       GENERATIONDATAGROUP NAME(AWS.M2.CARDDEMO.DALYREJS) LIMIT(5) SCRATCH
 *       for the daily-rejection GDG.</li>
 * </ul>
 *
 * <h2>GDG-to-S3 mapping (AAP &sect;0.6.2)</h2>
 * <p>Per AAP &sect;0.6.2 ("GDG generations map to S3 object versioning +
 * lifecycle policies"): GDG {@code LIMIT(N)} semantics map to S3 lifecycle
 * rules that transition older object versions to Glacier and expire beyond
 * N versions. The GDG {@code SCRATCH} flag (physical deletion of rolled-off
 * generations) maps to S3 lifecycle "Expiration" actions. These lifecycle
 * configurations live in {@code infrastructure/terraform/s3.tf} and are
 * NOT managed by this class &mdash; this class only holds the runtime
 * parameter beans for invoking the corresponding Glue Spark jobs.</p>
 *
 * <p>Per AAP &sect;0.6.2 ("Bulk fact data &mdash; Account, Card, Customer,
 * Cross-Reference &mdash; is loaded by AWS Glue Spark jobs reading from S3
 * where the ASCII fixtures are staged"), this class also exposes a
 * {@link #startBulkLoadJob(String)} entry point for the seed-data Glue jobs
 * that load reference fact tables from {@code app/data/ASCII/} (staged on
 * S3 by the LocalStack init scripts and by CI pipeline upload steps in
 * dev/prod profiles).</p>
 *
 * <h2>Role within the AWS-native target stack</h2>
 * <p>This class is a Spring {@link Configuration} that declares runtime-
 * callable bindings for {@link StartJobRunRequest} invocations against the
 * AWS SDK v2 {@link GlueClient}. The Glue job definitions themselves (job
 * name, IAM role, Spark script S3 URI, GlueVersion, max concurrency,
 * retries, timeout) are provisioned in
 * {@code infrastructure/terraform/glue.tf} &mdash; this class only holds the
 * runtime parameter beans and the single SDK-v2 invocation point.</p>
 *
 * <p>Spark scripts for each Glue job live in S3 at
 * {@code s3://${S3_OUTPUT_BUCKET}/glue-scripts/{job-name}.py} and are
 * deployed via Terraform; this Java class only references them indirectly
 * through the Terraform-registered job names that callers supply via the
 * configured property bindings ({@code carddemo.glue.jobs.*}).</p>
 *
 * <h2>Single AWS SDK invocation point (AAP &sect;0.7.1)</h2>
 * <p>Per AAP &sect;0.7.1 ("Isolate all AWS service integrations in
 * dedicated adapter classes &mdash; never inline AWS SDK calls in business
 * logic"), this class is the ONLY place in
 * {@code src/main/java/com/awsm2/carddemo/} that directly interacts with
 * the AWS SDK v2 {@link GlueClient}. Callers in {@code service/},
 * {@code batch/}, and {@code adapter/StepFunctionsOrchestrator} MUST use
 * the helper methods on this class rather than inlining
 * {@link GlueClient} calls. The private {@link #startJobRun(String, Map)}
 * helper centralises every {@code StartJobRun} invocation; the public
 * {@link #getJobRunStatus(String, String)} method is the single
 * {@code GetJobRun} invocation point.</p>
 *
 * <h2>AWS SDK version</h2>
 * <p>Per AAP &sect;0.5.1, only AWS SDK v2 ({@code software.amazon.awssdk.*})
 * is used. The deprecated v1 ({@code com.amazonaws.*}) is NEVER referenced
 * in this codebase. SDK v2 clients are thread-safe and intended to be
 * shared across the application; the {@link GlueClient} bean is produced
 * exactly once by {@code AwsSdkConfig#glueClient()} and injected here via
 * constructor for clean dependency-injection semantics (AAP &sect;0.7.1
 * "Dependency injection for loose coupling").</p>
 *
 * <h2>AWS Glue version</h2>
 * <p>This configuration is compatible with AWS Glue 4.x (Spark 3.3, Python
 * 3.10). The script's GlueVersion is set in
 * {@code infrastructure/terraform/glue.tf}; the application code here is
 * version-agnostic &mdash; it submits the job name and arguments and lets
 * the Glue service apply the configured version.</p>
 *
 * <h2>Security and PCI-DSS discipline</h2>
 * <ul>
 *   <li><b>No credentials in code</b>: the injected {@link GlueClient}
 *       resolves credentials via {@code DefaultCredentialsProvider} (or
 *       LocalStack {@code StaticCredentialsProvider} in the {@code local}
 *       profile) configured by
 *       {@code config/AwsSdkConfig#awsCredentialsProvider()}.</li>
 *   <li><b>No secrets in job arguments</b>: Glue job arguments appear in
 *       CloudWatch logs and the AWS Console. NEVER pass passwords, API
 *       keys, PAN, or PII as {@code --key value} Glue arguments. Sensitive
 *       values must be fetched by the Spark script directly from AWS
 *       Secrets Manager at runtime.</li>
 *   <li><b>No PAN / CVV / SSN in log statements</b>: every log statement
 *       emits the job name, the run ID, and the argument <em>KEY names</em>
 *       only &mdash; never the argument <em>values</em>.</li>
 *   <li><b>TLS in transit</b>: the {@link GlueClient} defaults to HTTPS;
 *       endpoint overrides are confined to the {@code local} profile
 *       (LocalStack on {@code http://localhost:4566}) inside
 *       {@code AwsSdkConfig}.</li>
 *   <li><b>Encryption at rest</b>: Glue job output S3 objects use SSE-KMS
 *       at the bucket level via
 *       {@code infrastructure/terraform/s3.tf}; this class does NOT
 *       directly handle encryption.</li>
 * </ul>
 *
 * <h2>Error-propagation contract</h2>
 * <ul>
 *   <li>{@link GlueException} (the AWS SDK v2 base for every Glue service
 *       error) is caught and wrapped as {@link CardDemoException} with the
 *       reason code {@code "GLUE_START_JOB_RUN_ERROR"} (for
 *       {@link #startJobRun(String, Map)} failures) or
 *       {@code "GLUE_GET_JOB_RUN_ERROR"} (for
 *       {@link #getJobRunStatus(String, String)} failures). These verbatim
 *       reason codes preserve the COBOL {@code RETURN-CODE} surface area
 *       per AAP &sect;0.7.2 and are propagated by
 *       {@code GlobalExceptionHandler} into the {@code ApiResponse.code}
 *       field of the standardised JSON error envelope (AAP
 *       &sect;0.3.4).</li>
 *   <li>{@link IllegalArgumentException} is thrown for null / blank inputs
 *       (job name, run ID, entity name). These are programmer errors, not
 *       runtime AWS failures, and propagate unwrapped.</li>
 *   <li>All other exceptions (network, transient) propagate unwrapped;
 *       the AWS SDK v2 default retry policy ({@code RetryMode.STANDARD},
 *       configured at the {@link GlueClient} bean level by
 *       {@code AwsSdkConfig}) handles transient 5xx and throttling errors
 *       with exponential backoff and full jitter before surfacing.</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.config.AwsSdkConfig
 * @see com.awsm2.carddemo.adapter.StepFunctionsOrchestrator
 * @see com.awsm2.carddemo.exception.CardDemoException
 */
@Configuration
public class GlueETLConfig {

    /**
     * Class logger. Structured JSON output is produced by Logback +
     * logstash-logback-encoder per AAP &sect;0.6.6 observability
     * requirements; messages are shipped to CloudWatch Logs from ECS tasks.
     */
    private static final Logger LOG = LoggerFactory.getLogger(GlueETLConfig.class);

    // -----------------------------------------------------------------------
    // GDG-base name constants — published via the gdgToGlueJobNameMap @Bean
    // to support operational runbooks and observability tooling that
    // continues to reference the original mainframe GDG names during the
    // parallel-run window (AAP §0.6.2 traceability).
    // -----------------------------------------------------------------------

    /** REPTFILE.jcl GDG: AWS.M2.CARDDEMO.TRANREPT (LIMIT=10). */
    static final String GDG_TRANREPT = "AWS.M2.CARDDEMO.TRANREPT";

    /** DALYREJS.jcl GDG: AWS.M2.CARDDEMO.DALYREJS (LIMIT=5, SCRATCH). */
    static final String GDG_DALYREJS = "AWS.M2.CARDDEMO.DALYREJS";

    /** DEFGDGB.jcl GDG: AWS.M2.CARDDEMO.TRANSACT.BKUP (LIMIT=5, SCRATCH). */
    static final String GDG_TRANSACT_BKUP = "AWS.M2.CARDDEMO.TRANSACT.BKUP";

    /** DEFGDGB.jcl GDG: AWS.M2.CARDDEMO.TRANSACT.DALY (LIMIT=5, SCRATCH). */
    static final String GDG_TRANSACT_DALY = "AWS.M2.CARDDEMO.TRANSACT.DALY";

    /** DEFGDGB.jcl GDG: AWS.M2.CARDDEMO.TCATBALF.BKUP (LIMIT=5, SCRATCH). */
    static final String GDG_TCATBALF_BKUP = "AWS.M2.CARDDEMO.TCATBALF.BKUP";

    /** DEFGDGB.jcl GDG: AWS.M2.CARDDEMO.SYSTRAN (LIMIT=5, SCRATCH). */
    static final String GDG_SYSTRAN = "AWS.M2.CARDDEMO.SYSTRAN";

    /** DEFGDGB.jcl GDG: AWS.M2.CARDDEMO.TRANSACT.COMBINED (LIMIT=5, SCRATCH). */
    static final String GDG_TRANSACT_COMBINED = "AWS.M2.CARDDEMO.TRANSACT.COMBINED";

    // -----------------------------------------------------------------------
    // Externalised configuration — Glue job names (one per GDG base + bulk)
    //
    // Sourced from Spring property resolution chain:
    //   1. AWS Systems Manager Parameter Store (loaded by Spring Cloud AWS)
    //   2. AWS Secrets Manager (rotation-aware via @RefreshScope siblings)
    //   3. application-{profile}.yml overrides
    //   4. application.yml defaults
    //   5. literal default in @Value (sensible fallback for tests)
    //
    // The defaults below intentionally match the names registered in
    // infrastructure/terraform/glue.tf — if Terraform job names change, the
    // overrides under carddemo.glue.jobs.* MUST be updated in lockstep.
    // -----------------------------------------------------------------------

    /** Glue job name for transaction report ETL &mdash; replaces REPTFILE.jcl GDG TRANREPT(LIMIT=10). */
    @Value("${carddemo.glue.jobs.transaction-report:carddemo-transaction-report-etl}")
    private String transactionReportJobName;

    /** Glue job name for daily rejection records ETL &mdash; replaces DALYREJS.jcl GDG DALYREJS(LIMIT=5, SCRATCH). */
    @Value("${carddemo.glue.jobs.daily-rejections:carddemo-dalyrejs-etl}")
    private String dailyRejectionsJobName;

    /** Glue job name for transaction backup ETL &mdash; replaces DEFGDGB.jcl GDG TRANSACT.BKUP(LIMIT=5, SCRATCH). */
    @Value("${carddemo.glue.jobs.transact-backup:carddemo-transact-backup-etl}")
    private String transactBackupJobName;

    /** Glue job name for daily transaction ETL &mdash; replaces DEFGDGB.jcl GDG TRANSACT.DALY(LIMIT=5, SCRATCH). */
    @Value("${carddemo.glue.jobs.transact-daily:carddemo-transact-daily-etl}")
    private String transactDailyJobName;

    /** Glue job name for category balance backup ETL &mdash; replaces DEFGDGB.jcl GDG TCATBALF.BKUP(LIMIT=5, SCRATCH). */
    @Value("${carddemo.glue.jobs.tcatbalf-backup:carddemo-tcatbalf-backup-etl}")
    private String tcatBalfBackupJobName;

    /** Glue job name for system-generated transactions ETL &mdash; replaces DEFGDGB.jcl GDG SYSTRAN(LIMIT=5, SCRATCH). */
    @Value("${carddemo.glue.jobs.systran:carddemo-systran-etl}")
    private String systranJobName;

    /** Glue job name for combined transaction stream ETL &mdash; replaces DEFGDGB.jcl GDG TRANSACT.COMBINED(LIMIT=5, SCRATCH). */
    @Value("${carddemo.glue.jobs.transact-combined:carddemo-transact-combined-etl}")
    private String transactCombinedJobName;

    /** Glue job name for bulk fact data load (Account, Card, Customer, Cross-Reference). Per AAP &sect;0.6.2. */
    @Value("${carddemo.glue.jobs.bulk-load:carddemo-bulk-load-etl}")
    private String bulkLoadJobName;

    // -----------------------------------------------------------------------
    // Externalised configuration — S3 layout
    // -----------------------------------------------------------------------

    /**
     * S3 bucket where Glue Spark scripts and input/output objects live
     * (per AAP &sect;0.7.2 required environment variable
     * {@code S3_OUTPUT_BUCKET}). Defaults to {@code carddemo-output} to
     * match the convention in {@code application.yml} so the class can
     * compile and load in test contexts without an explicit override.
     */
    @Value("${carddemo.s3.output-bucket:${S3_OUTPUT_BUCKET:carddemo-output}}")
    private String outputBucket;

    /**
     * S3 prefix for Glue script artifacts (Terraform-managed). Default
     * {@code glue-scripts} matches the prefix used by
     * {@code infrastructure/terraform/glue.tf}.
     */
    @Value("${carddemo.glue.script-prefix:glue-scripts}")
    private String scriptPrefix;

    // -----------------------------------------------------------------------
    // Externalised configuration — concurrency and timeout
    // -----------------------------------------------------------------------

    /**
     * Maximum number of concurrent Glue job runs per definition. Matches
     * the Terraform-configured {@code max_concurrent_runs} attribute on
     * the corresponding Glue job. Default {@code 5} is conservative; raise
     * via override when the EOD pipeline genuinely needs parallel runs of
     * the same definition.
     */
    @Value("${carddemo.glue.max-concurrent-runs:5}")
    private int maxConcurrentRuns;

    /**
     * Job-run timeout in minutes. Matches the Terraform-configured
     * timeout; the default {@code 60} minutes aligns with the EOD batch
     * SLA window documented in AAP &sect;0.7.2. Glue uses the timeout to
     * abort runs that exceed the configured budget.
     */
    @Value("${carddemo.glue.timeout-minutes:60}")
    private int timeoutMinutes;

    // -----------------------------------------------------------------------
    // Injected collaborators
    // -----------------------------------------------------------------------

    /**
     * AWS SDK v2 Glue client bean. Provided by
     * {@code config/AwsSdkConfig#glueClient()} per AAP &sect;0.7.1; this
     * class never instantiates a {@link GlueClient} directly.
     */
    private final GlueClient glueClient;

    /**
     * Constructor injection of the AWS SDK v2 Glue client (AAP &sect;0.7.1
     * "Dependency injection for loose coupling"). Field injection is NOT
     * used.
     *
     * @param glueClient the singleton {@link GlueClient} bean produced by
     *                   {@code AwsSdkConfig}; must not be {@code null} in
     *                   any wired Spring context
     */
    public GlueETLConfig(GlueClient glueClient) {
        this.glueClient = glueClient;
    }

    // =======================================================================
    // Public API — one start*Job(...) per GDG base + a bulk-load entry point
    // =======================================================================

    /**
     * Start the transaction report ETL Glue job.
     *
     * <p>Replaces: {@code REPTFILE.jcl} {@code IDCAMS DEFINE
     * GENERATIONDATAGROUP NAME(AWS.M2.CARDDEMO.TRANREPT) LIMIT(10)}. The
     * Glue Spark job reads the consolidated transaction data and writes a
     * versioned report object to
     * {@code s3://${S3_OUTPUT_BUCKET}/tranrept/{date}/{run-id}.rpt}.</p>
     *
     * @param reportDate ISO-8601 date ({@code YYYY-MM-DD}) for which to
     *                   generate the report; passed to the Spark script as
     *                   {@code --report-date}
     * @return the Glue job run ID &mdash; callers log and track this for
     *         subsequent status queries via
     *         {@link #getJobRunStatus(String, String)}
     * @throws CardDemoException with reason {@code GLUE_START_JOB_RUN_ERROR}
     *                           if the Glue {@code StartJobRun} API call
     *                           fails
     */
    public String startTransactionReportJob(String reportDate) {
        Map<String, String> args = new HashMap<>();
        args.put("--report-date", reportDate);
        args.put("--output-bucket", outputBucket);
        args.put("--output-prefix", "tranrept");
        // Replaces: REPTFILE.jcl GDG (+1) generation suffix → S3 versioned object
        return startJobRun(transactionReportJobName, args);
    }

    /**
     * Start the daily rejection records ETL Glue job.
     *
     * <p>Replaces: {@code DALYREJS.jcl} {@code IDCAMS DEFINE
     * GENERATIONDATAGROUP NAME(AWS.M2.CARDDEMO.DALYREJS) LIMIT(5) SCRATCH}.
     * The Glue Spark job consolidates daily rejection records (originally
     * written by {@code CBTRN02C} paragraph {@code 2500-WRITE-REJECT-REC}
     * to the {@code DALYREJS} DD) into a versioned S3 object under
     * {@code s3://${S3_OUTPUT_BUCKET}/dalyrejs/{date}/}.</p>
     *
     * @param batchDate ISO-8601 date ({@code YYYY-MM-DD}) for the batch run
     * @return the Glue job run ID
     * @throws CardDemoException with reason {@code GLUE_START_JOB_RUN_ERROR}
     *                           if the Glue {@code StartJobRun} API call
     *                           fails
     */
    public String startDailyRejectionsJob(String batchDate) {
        Map<String, String> args = new HashMap<>();
        args.put("--batch-date", batchDate);
        args.put("--output-bucket", outputBucket);
        args.put("--output-prefix", "dalyrejs");
        // Replaces: DALYREJS.jcl GDG SCRATCH semantics → S3 lifecycle expiration (Terraform-managed)
        return startJobRun(dailyRejectionsJobName, args);
    }

    /**
     * Start the transaction backup ETL Glue job.
     *
     * <p>Replaces: {@code DEFGDGB.jcl} GDG
     * {@code TRANSACT.BKUP(LIMIT=5, SCRATCH)}. The Glue Spark job streams
     * the current Transaction JPA table to a versioned S3 object
     * &mdash; the AWS-native replacement for the
     * {@code TRANREPT.jcl} {@code STEP05R} {@code IDCAMS REPRO} write to
     * {@code TRANSACT.BKUP(+1)}.</p>
     *
     * @param backupDate ISO-8601 date for the backup snapshot
     * @return the Glue job run ID
     * @throws CardDemoException with reason {@code GLUE_START_JOB_RUN_ERROR}
     *                           if the Glue {@code StartJobRun} API call
     *                           fails
     */
    public String startTransactBackupJob(String backupDate) {
        Map<String, String> args = new HashMap<>();
        args.put("--backup-date", backupDate);
        args.put("--output-bucket", outputBucket);
        args.put("--output-prefix", "transact-bkup");
        // Replaces: DEFGDGB.jcl TRANSACT.BKUP GDG → S3 versioned object with lifecycle
        return startJobRun(transactBackupJobName, args);
    }

    /**
     * Start the daily transaction ETL Glue job.
     *
     * <p>Replaces: {@code DEFGDGB.jcl} GDG
     * {@code TRANSACT.DALY(LIMIT=5, SCRATCH)}. The Glue Spark job processes
     * the daily transaction stream (originally written by {@code CBTRN02C}
     * to the daily DD) and produces a normalised S3 object for downstream
     * consumers.</p>
     *
     * @param batchDate ISO-8601 date for the daily run
     * @return the Glue job run ID
     * @throws CardDemoException with reason {@code GLUE_START_JOB_RUN_ERROR}
     *                           if the Glue {@code StartJobRun} API call
     *                           fails
     */
    public String startTransactDailyJob(String batchDate) {
        Map<String, String> args = new HashMap<>();
        args.put("--batch-date", batchDate);
        args.put("--output-bucket", outputBucket);
        args.put("--output-prefix", "transact-daly");
        // Replaces: DEFGDGB.jcl TRANSACT.DALY GDG
        return startJobRun(transactDailyJobName, args);
    }

    /**
     * Start the transaction category balance backup ETL Glue job.
     *
     * <p>Replaces: {@code DEFGDGB.jcl} GDG
     * {@code TCATBALF.BKUP(LIMIT=5, SCRATCH)}. The Glue Spark job snapshots
     * the {@code TransactionCategoryBalance} JPA table to a versioned S3
     * object &mdash; the AWS-native replacement for the COBOL TCATBALF VSAM
     * cluster backup flow.</p>
     *
     * @param backupDate ISO-8601 date for the backup snapshot
     * @return the Glue job run ID
     * @throws CardDemoException with reason {@code GLUE_START_JOB_RUN_ERROR}
     *                           if the Glue {@code StartJobRun} API call
     *                           fails
     */
    public String startTcatBalfBackupJob(String backupDate) {
        Map<String, String> args = new HashMap<>();
        args.put("--backup-date", backupDate);
        args.put("--output-bucket", outputBucket);
        args.put("--output-prefix", "tcatbalf-bkup");
        // Replaces: DEFGDGB.jcl TCATBALF.BKUP GDG
        return startJobRun(tcatBalfBackupJobName, args);
    }

    /**
     * Start the system-generated transactions ETL Glue job.
     *
     * <p>Replaces: {@code DEFGDGB.jcl} GDG
     * {@code SYSTRAN(LIMIT=5, SCRATCH)}. The Glue Spark job processes
     * system-generated transactions (e.g., interest postings written by
     * {@code CBACT04C} to the {@code SYSTRAN} DD in {@code INTCALC.jcl})
     * and emits a versioned S3 object.</p>
     *
     * @param batchDate ISO-8601 date for the run
     * @return the Glue job run ID
     * @throws CardDemoException with reason {@code GLUE_START_JOB_RUN_ERROR}
     *                           if the Glue {@code StartJobRun} API call
     *                           fails
     */
    public String startSystranJob(String batchDate) {
        Map<String, String> args = new HashMap<>();
        args.put("--batch-date", batchDate);
        args.put("--output-bucket", outputBucket);
        args.put("--output-prefix", "systran");
        // Replaces: INTCALC.jcl SYSTRAN DD allocation + DEFGDGB.jcl SYSTRAN GDG
        return startJobRun(systranJobName, args);
    }

    /**
     * Start the combined transaction stream ETL Glue job.
     *
     * <p>Replaces: {@code DEFGDGB.jcl} GDG
     * {@code TRANSACT.COMBINED(LIMIT=5, SCRATCH)} and the
     * {@code COMBTRAN.jcl} {@code DFSORT + IDCAMS REPRO} utility step
     * (per AAP &sect;0.6.3, {@code COMBTRAN.jcl} is purely a utility stage
     * with no COBOL program &mdash; it becomes a Glue/Spark-based sort and
     * bulk write).</p>
     *
     * @param batchDate ISO-8601 date for the run
     * @return the Glue job run ID
     * @throws CardDemoException with reason {@code GLUE_START_JOB_RUN_ERROR}
     *                           if the Glue {@code StartJobRun} API call
     *                           fails
     */
    public String startTransactCombinedJob(String batchDate) {
        Map<String, String> args = new HashMap<>();
        args.put("--batch-date", batchDate);
        args.put("--output-bucket", outputBucket);
        args.put("--output-prefix", "transact-combined");
        // Replaces: COMBTRAN.jcl DFSORT + IDCAMS REPRO utility (AAP §0.6.3)
        return startJobRun(transactCombinedJobName, args);
    }

    /**
     * Start the bulk fact data load ETL Glue job.
     *
     * <p>Per AAP &sect;0.6.2 ("Bulk fact data &mdash; Account, Card,
     * Customer, Cross-Reference &mdash; is loaded by AWS Glue Spark jobs
     * reading from S3 where the ASCII fixtures are staged"). The Glue Spark
     * job reads ASCII fixtures from
     * {@code s3://${S3_OUTPUT_BUCKET}/seed-data/{entity}/} and writes to
     * RDS via the Glue PostgreSQL JDBC connection.</p>
     *
     * <p>Valid {@code entityName} values include (but are not limited to):
     * {@code "account"}, {@code "card"}, {@code "customer"},
     * {@code "cardxref"}. The Spark script switches on this argument to
     * resolve the fixed-width parser, the RDS target table, and the JDBC
     * upsert strategy.</p>
     *
     * @param entityName the entity identifier; one of {@code account},
     *                   {@code card}, {@code customer}, {@code cardxref};
     *                   must not be {@code null} or blank
     * @return the Glue job run ID
     * @throws IllegalArgumentException if {@code entityName} is
     *                                  {@code null} or blank
     * @throws CardDemoException        with reason
     *                                  {@code GLUE_START_JOB_RUN_ERROR} if
     *                                  the Glue {@code StartJobRun} API
     *                                  call fails
     */
    public String startBulkLoadJob(String entityName) {
        if (entityName == null || entityName.isBlank()) {
            throw new IllegalArgumentException("entityName must not be null/blank");
        }
        Map<String, String> args = new HashMap<>();
        args.put("--entity-name", entityName);
        args.put("--source-bucket", outputBucket);
        args.put("--source-prefix", "seed-data/" + entityName);
        // Replaces: ACCTFILE.jcl / CARDFILE.jcl / CUSTFILE.jcl / XREFFILE.jcl IDCAMS REPRO loads
        return startJobRun(bulkLoadJobName, args);
    }

    /**
     * Query the status of a previously started Glue job run.
     *
     * <p>Replaces: SDSF status display for the originating JCL job.</p>
     *
     * <p>The returned {@link JobRunState} drives caller-side polling and
     * Step Functions {@code Choice}-state branching. Possible values
     * include (per the AWS SDK v2 model): {@code STARTING},
     * {@code RUNNING}, {@code STOPPING}, {@code STOPPED},
     * {@code SUCCEEDED}, {@code FAILED}, {@code TIMEOUT}, and the
     * forward-compatibility sentinel {@code UNKNOWN_TO_SDK_VERSION}.</p>
     *
     * @param jobName Glue job definition name (typically the value returned
     *                from one of the {@code startXxxJob} methods'
     *                originating job-name constant). Must not be
     *                {@code null} or blank.
     * @param runId   Glue job run ID returned by
     *                {@link #startJobRun(String, Map)}. Must not be
     *                {@code null} or blank.
     * @return the {@link JobRunState} reported by Glue for the requested
     *         run
     * @throws IllegalArgumentException if {@code jobName} or {@code runId}
     *                                  is {@code null} or blank
     * @throws CardDemoException        with reason
     *                                  {@code GLUE_GET_JOB_RUN_ERROR} if
     *                                  the Glue {@code GetJobRun} API call
     *                                  fails
     */
    public JobRunState getJobRunStatus(String jobName, String runId) {
        if (jobName == null || jobName.isBlank()) {
            throw new IllegalArgumentException("jobName must not be null/blank");
        }
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be null/blank");
        }
        GetJobRunRequest request = GetJobRunRequest.builder()
                .jobName(jobName)
                .runId(runId)
                .build();
        try {
            GetJobRunResponse response = glueClient.getJobRun(request);
            JobRunState state = response.jobRun().jobRunState();
            LOG.debug("Glue job run status jobName={} runId={} state={}", jobName, runId, state);
            return state;
        } catch (GlueException e) {
            // AAP §0.7.2: preserve verbatim error codes; log full context for ops triage.
            LOG.error("Glue getJobRun FAILED jobName={} runId={} cause={}",
                    jobName, runId,
                    e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage(),
                    e);
            throw new CardDemoException("GLUE_GET_JOB_RUN_ERROR",
                    "Failed to query Glue job run status for jobName=" + jobName + " runId=" + runId, e);
        }
    }

    // =======================================================================
    // Private helper — the single AWS SDK v2 StartJobRun invocation point
    // =======================================================================

    /**
     * Central method for starting a Glue job run. All public
     * {@code startXxxJob} methods delegate here.
     *
     * <p>Per AAP &sect;0.7.1: this is the single AWS SDK v2
     * {@link GlueClient} {@code StartJobRun} invocation point in the
     * {@code glue/} package. Centralising the call here guarantees uniform
     * logging, error-handling, and timeout semantics across every Glue ETL
     * pipeline.</p>
     *
     * <p>Argument map keys follow the Glue Spark convention of
     * {@code --key} prefixes (read at runtime via
     * {@code awsglue.utils.getResolvedOptions} in the Python Spark script).
     * Per AAP &sect;0.6.6, ONLY argument KEY names are logged; argument
     * VALUES are never written to logs because they appear in CloudWatch
     * Logs and the AWS Console.</p>
     *
     * @param jobName the Glue job definition name (Terraform-provisioned)
     * @param jobArgs the arguments to pass to the Spark script as
     *                {@code --key value} pairs; may be {@code null} or
     *                empty when the job needs no parameters
     * @return the Glue job run ID returned by the AWS service
     * @throws IllegalArgumentException if {@code jobName} is {@code null}
     *                                  or blank
     * @throws CardDemoException        with reason
     *                                  {@code GLUE_START_JOB_RUN_ERROR} if
     *                                  the Glue {@code StartJobRun} API
     *                                  call fails
     */
    private String startJobRun(String jobName, Map<String, String> jobArgs) {
        if (jobName == null || jobName.isBlank()) {
            throw new IllegalArgumentException("jobName must not be null/blank");
        }
        // AAP §0.7.1: single AWS SDK v2 GlueClient invocation point in the package.
        StartJobRunRequest.Builder builder = StartJobRunRequest.builder()
                .jobName(jobName)
                .timeout(timeoutMinutes);
        if (jobArgs != null && !jobArgs.isEmpty()) {
            builder.arguments(jobArgs);
        }
        StartJobRunRequest request = builder.build();
        try {
            StartJobRunResponse response = glueClient.startJobRun(request);
            String runId = response.jobRunId();
            // PCI-DSS discipline: log only argument KEY names — never argument VALUES.
            LOG.info("Glue job started jobName={} runId={} timeout={}min args={}",
                    jobName, runId, timeoutMinutes,
                    jobArgs != null ? jobArgs.keySet() : Collections.emptySet());
            return runId;
        } catch (GlueException e) {
            // AAP §0.7.2: preserve verbatim error codes; log full context for ops triage.
            LOG.error("Glue startJobRun FAILED jobName={} cause={}",
                    jobName,
                    e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage(),
                    e);
            throw new CardDemoException("GLUE_START_JOB_RUN_ERROR",
                    "Failed to start Glue job: " + jobName, e);
        }
    }

    // =======================================================================
    // Spring @Bean — GDG → Glue job name traceability map
    // =======================================================================

    /**
     * Bean publishing a map from the original mainframe GDG base name to
     * the target Glue job name.
     *
     * <p>Per AAP &sect;0.6.2: the mapping enables observability tools and
     * operational runbooks to trace each mainframe GDG to its AWS-native
     * replacement. When an operator sees a log message that references a
     * legacy GDG name (for example
     * {@code AWS.M2.CARDDEMO.TRANSACT.BKUP}), they can consult this map
     * to find the corresponding Glue job to inspect (in this case
     * {@code carddemo-transact-backup-etl}).</p>
     *
     * <p>The map contains <em>seven</em> unique entries (TRANREPT is
     * defined in BOTH {@code REPTFILE.jcl} and {@code DEFGDGB.jcl}; the
     * second definition is a duplicate, so the unique union is 7):</p>
     * <ol>
     *   <li>{@code AWS.M2.CARDDEMO.TRANREPT} &rarr;
     *       {@code carddemo-transaction-report-etl} (from REPTFILE.jcl;
     *       DEFGDGB.jcl duplicate definition resolves to the same target)</li>
     *   <li>{@code AWS.M2.CARDDEMO.DALYREJS} &rarr;
     *       {@code carddemo-dalyrejs-etl} (from DALYREJS.jcl)</li>
     *   <li>{@code AWS.M2.CARDDEMO.TRANSACT.BKUP} &rarr;
     *       {@code carddemo-transact-backup-etl} (from DEFGDGB.jcl)</li>
     *   <li>{@code AWS.M2.CARDDEMO.TRANSACT.DALY} &rarr;
     *       {@code carddemo-transact-daily-etl} (from DEFGDGB.jcl)</li>
     *   <li>{@code AWS.M2.CARDDEMO.TCATBALF.BKUP} &rarr;
     *       {@code carddemo-tcatbalf-backup-etl} (from DEFGDGB.jcl)</li>
     *   <li>{@code AWS.M2.CARDDEMO.SYSTRAN} &rarr;
     *       {@code carddemo-systran-etl} (from DEFGDGB.jcl)</li>
     *   <li>{@code AWS.M2.CARDDEMO.TRANSACT.COMBINED} &rarr;
     *       {@code carddemo-transact-combined-etl} (from DEFGDGB.jcl)</li>
     * </ol>
     *
     * <p>The returned map is {@link Collections#unmodifiableMap(Map)
     * unmodifiable}: any attempt to {@code put}, {@code remove}, or
     * {@code clear} entries throws {@link UnsupportedOperationException}.
     * This protects the traceability registry from accidental mutation by
     * downstream consumers.</p>
     *
     * <p>The bulk-load job is intentionally NOT in this map &mdash; it
     * does not correspond to any original GDG; it is an AWS-native
     * addition required by AAP &sect;0.6.2's bulk-fact-data requirement.</p>
     *
     * @return immutable map of GDG base name &rarr; Glue job name
     */
    @Bean(name = "gdgToGlueJobNameMap")
    public Map<String, String> gdgToGlueJobNameMap() {
        // AAP §0.6.2: traceability mapping from mainframe GDG to AWS Glue job.
        Map<String, String> mapping = new HashMap<>();
        // Replaces: REPTFILE.jcl GDG → carddemo-transaction-report-etl
        mapping.put(GDG_TRANREPT, transactionReportJobName);
        // Replaces: DALYREJS.jcl GDG → carddemo-dalyrejs-etl
        mapping.put(GDG_DALYREJS, dailyRejectionsJobName);
        // Replaces: DEFGDGB.jcl 6 GDG bases → 6 Glue jobs (TRANREPT is a duplicate of REPTFILE entry)
        mapping.put(GDG_TRANSACT_BKUP, transactBackupJobName);
        mapping.put(GDG_TRANSACT_DALY, transactDailyJobName);
        mapping.put(GDG_TCATBALF_BKUP, tcatBalfBackupJobName);
        mapping.put(GDG_SYSTRAN, systranJobName);
        mapping.put(GDG_TRANSACT_COMBINED, transactCombinedJobName);
        return Collections.unmodifiableMap(mapping);
    }
}
