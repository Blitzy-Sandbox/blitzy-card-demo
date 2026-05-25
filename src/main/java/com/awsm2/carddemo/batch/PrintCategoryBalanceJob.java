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
package com.awsm2.carddemo.batch;

import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.adapter.S3OutputService;
import com.awsm2.carddemo.domain.TransactionCategoryBalance;
import com.awsm2.carddemo.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import com.awsm2.carddemo.repository.TransactionCategoryBalanceRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring Batch {@code @Configuration} class for the
 * {@code printCategoryBalanceJob}.
 *
 * <p><b>// Replaces: app/jcl/PRTCATBL.jcl</b> &mdash; a pure DFSORT
 * utility job (<em>no</em> COBOL source program backs this job) that
 * dumped the {@code TCATBALF VSAM KSDS} cluster in a printable,
 * fixed-format ASCII report sorted by the natural composite key
 * {@code (TRANCAT-ACCT-ID, TRANCAT-TYPE-CD, TRANCAT-CD)} with the
 * running balance edited via DFSORT's {@code EDIT=(TTTTTTTTT.TT)} mask
 * (9 leading-zero-suppressed integer digits + decimal point + 2
 * fraction digits).</p>
 *
 * <h2>Source Lineage</h2>
 *
 * <p>The original {@code PRTCATBL.jcl} JCL job consisted of three
 * utility steps:</p>
 * <ol>
 *   <li>{@code DELDEF EXEC PGM=IEFBR14} — pre-delete the output dataset
 *       {@code AWS.M2.CARDDEMO.TCATBALF.REPT} so the subsequent
 *       allocation succeeds. Replaced operationally by S3 object
 *       versioning ({@code aws s3api put-object} replaces the prior
 *       version automatically; lifecycle policies clean up old
 *       versions per AAP &sect;0.6.2).</li>
 *   <li>{@code STEP05R EXEC PROC=REPROC} — IDCAMS {@code REPRO} that
 *       unloaded the {@code TCATBALF VSAM KSDS} to a sequential
 *       backup {@code TCATBALF.BKUP(+1)}. Replaced by the Spring Data
 *       JPA {@code findAll(Sort)} on
 *       {@link TransactionCategoryBalanceRepository} which streams
 *       the table contents from RDS PostgreSQL in the same composite-
 *       key order (per AAP &sect;0.6.2 "VSAM cluster → JPA entity
 *       with index on natural key").</li>
 *   <li>{@code STEP10R EXEC PGM=SORT} — DFSORT job with
 *       {@code SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A)}
 *       and {@code OUTREC FIELDS=(TRANCAT-ACCT-ID,X,TRANCAT-TYPE-CD,X,
 *       TRANCAT-CD,X,TRAN-CAT-BAL,EDIT=(TTTTTTTTT.TT),9X)} writing
 *       to the sequential dataset {@code TCATBALF.REPT}
 *       ({@code LRECL=40}, {@code RECFM=FB}). Replaced by this
 *       Spring Batch tasklet which (a) applies the same composite-key
 *       sort via a Java {@link Comparator} chain (belt-and-suspenders
 *       atop the repository's {@code Sort.by(...)} clause), (b)
 *       renders each balance row in a fixed 40-byte ASCII format
 *       matching the DFSORT {@code OUTREC} layout, and (c) writes the
 *       assembled byte buffer to S3 via
 *       {@link S3OutputService#writeReport(String, byte[])} replacing
 *       the {@code TCATBALF.REPT} sequential dataset per AAP
 *       &sect;0.4.1.</li>
 * </ol>
 *
 * <h2>Output Format (40 bytes per line + LF separator)</h2>
 *
 * <p>Each {@link TransactionCategoryBalance} row is rendered as a
 * 40-byte fixed-width ASCII line matching the byte positions of the
 * DFSORT {@code OUTREC} produced by the original
 * {@code PRTCATBL.jcl STEP10R}:</p>
 *
 * <pre>
 *   pos  1-11  TRANCAT-ACCT-ID   PIC 9(11)        11 digits, zero-padded
 *   pos    12  filler            X                1 space
 *   pos 13-14  TRANCAT-TYPE-CD   PIC X(02)        2 chars
 *   pos    15  filler            X                1 space
 *   pos 16-19  TRANCAT-CD        PIC 9(04)        4 digits, zero-padded
 *   pos    20  filler            X                1 space
 *   pos 21-32  TRAN-CAT-BAL      EDIT=(TTTTTTTTT.TT)
 *                                                 12 chars, right-justified,
 *                                                 leading zeros suppressed
 *                                                 to spaces, fixed decimal
 *                                                 point at position 30
 *   pos 33-40  trailing filler   8 X              8 spaces (DFSORT {@code 9X}
 *                                                 truncated by LRECL=40
 *                                                 per the original DCB)
 * </pre>
 *
 * <p>Lines are separated by a single LF byte ({@code 0x0A}) per the
 * RECFM=FB convention as flattened into ASCII for S3 storage.</p>
 *
 * <h2>Java/Spring Batch Replacement</h2>
 *
 * <p>The {@code printCategoryBalanceJob} is composed of a single
 * orchestration step that:</p>
 * <ol>
 *   <li>Streams every row of {@link TransactionCategoryBalance} from
 *       the {@code tran_cat_bal} table via
 *       {@link TransactionCategoryBalanceRepository#findAll(Sort)}
 *       with the canonical composite-key sort
 *       ({@code id.trancatAcctId}, {@code id.trancatTypeCd},
 *       {@code id.trancatCd}). This replaces the DFSORT
 *       {@code SORTIN} read of {@code TCATBALF.BKUP(+1)} plus the
 *       implicit composite-key sort produced by
 *       {@code SORT FIELDS=(...,A,...,A,...,A)}.</li>
 *   <li>Applies a defensive in-memory Java {@link Comparator} chain
 *       on the same three sub-fields to guarantee the output ordering
 *       even if the repository sort is ever omitted or perturbed by
 *       schema evolution. The Comparator is the canonical
 *       byte-ordering used by VSAM and is preserved here verbatim
 *       per AAP &sect;0.7.3 ("Minimal Change Clause").</li>
 *   <li>Renders each row to a 40-byte ASCII line via
 *       {@link #formatTcatBalLine(TransactionCategoryBalance)} (helper
 *       method exposed package-private for direct unit testing).</li>
 *   <li>Writes the assembled byte buffer to S3 via
 *       {@link S3OutputService#writeReport(String, byte[])} with a
 *       category-balance-specific {@code reportId} (the
 *       {@code batchRunId} prefixed by {@code "tcatbal-"}). The S3
 *       object lands under the {@code tranrept/} key prefix with the
 *       {@code .rpt} extension &mdash; replacing the sequential
 *       dataset {@code AWS.M2.CARDDEMO.TCATBALF.REPT} per AAP
 *       &sect;0.6.2 (sequential PS → versioned S3 object).</li>
 *   <li>Emits the {@code COMPLETED} batch lifecycle audit event via
 *       {@link AuditLogService#logBatchJobLifecycle(String, String,
 *       String, Long, Map, String)} with the row count and S3 byte
 *       length so operators can verify the dump landed.</li>
 * </ol>
 *
 * <p><b>Why a tasklet (not chunk-oriented)?</b> The
 * {@code TransactionCategoryBalance} table is reference-shaped —
 * O(accounts &times; types &times; categories) rows — which is small
 * enough (typically &lt; 10&times;10&sup3; rows in production) that
 * loading every row into memory and assembling a single byte buffer
 * for one S3 PUT is correct and matches the COBOL/DFSORT semantic of
 * producing a single output dataset per job invocation. A chunk-
 * oriented step would impose intermediate commit boundaries and a
 * multi-part S3 upload protocol that do not correspond to anything
 * in the source mainframe execution model.</p>
 *
 * <h2>End-of-Day Pipeline Position</h2>
 *
 * <p>{@code PRTCATBL.jcl} is invoked operationally as an on-demand
 * report request &mdash; it is NOT part of the linear end-of-day
 * pipeline orchestrated by
 * {@code src/main/resources/stepfunctions/eod-batch-pipeline.asl.json}
 * (which sequences POSTTRAN → INTCALC → COMBTRAN → Parallel {
 * CREASTMT, TRANREPT } per AAP &sect;0.6.3). This job is registered
 * as a stand-alone AWS Batch job definition
 * ({@code aws_batch_job_definition.print_category_balance} in
 * {@code infrastructure/terraform/batch.tf}) so operators can submit
 * an ad-hoc category-balance dump on demand.</p>
 *
 * <h2>Job Parameters</h2>
 * <ul>
 *   <li>{@code batchRunId} (REQUIRED) &mdash; unique batch run
 *       identifier used as the audit-trail correlation key and the
 *       S3 report file's per-run identifier. Validated by the shared
 *       {@link BatchJobConfig#standardJobParametersValidator()
 *       standardJobParametersValidator}.</li>
 *   <li>{@code parmDate} (OPTIONAL) &mdash; ISO-8601 {@code yyyy-MM-dd}
 *       date used purely for the audit trail (the job itself reads
 *       the current state of the {@code tran_cat_bal} table; the
 *       parameter is recorded in the audit event so operators can
 *       correlate the dump with a logical reporting period).</li>
 *   <li>{@code correlationId} (OPTIONAL) &mdash; for audit-trail
 *       correlation across services. If absent, the
 *       {@link AuditLogService} synthesises a correlation key.</li>
 * </ul>
 *
 * @see TransactionCategoryBalanceRepository
 * @see TransactionCategoryBalance
 * @see S3OutputService#writeReport(String, byte[])
 * @see BatchJobConfig
 */
// COBOL: PRTCATBL.jcl (pure DFSORT utility — no COBOL program backs this job)
// Replaces: app/jcl/PRTCATBL.jcl STEP05R IDCAMS REPRO + STEP10R DFSORT
@Configuration("printCategoryBalanceJobConfiguration")
public class PrintCategoryBalanceJob {

    /**
     * SLF4J logger for the job lifecycle.
     */
    private static final Logger LOG = LoggerFactory.getLogger(PrintCategoryBalanceJob.class);

    /**
     * The Spring Batch {@link Job} bean name.
     *
     * <p>AWS Batch / Step Functions resolve and launch the job by this
     * exact name; do not rename without updating
     * {@code infrastructure/terraform/batch.tf} where
     * {@code aws_batch_job_definition.print_category_balance} declares
     * {@code command = ["java", "-jar", "/app/carddemo.jar",
     * "--spring.batch.job.name=printCategoryBalanceJob"]}.</p>
     */
    public static final String JOB_NAME = "printCategoryBalanceJob";

    /**
     * The Spring Batch {@link Step} bean name.
     */
    public static final String STEP_NAME = "printCategoryBalanceStep";

    /**
     * {@link org.springframework.batch.core.JobParameters} key for the
     * required batch run identifier (validated by the shared
     * {@link BatchJobConfig#standardJobParametersValidator()}).
     */
    public static final String PARAM_BATCH_RUN_ID = "batchRunId";

    /**
     * {@link org.springframework.batch.core.JobParameters} key for the
     * optional ISO-8601 {@code parmDate} (recorded in audit trail).
     */
    public static final String PARAM_PARM_DATE = "parmDate";

    /**
     * Execution-context key for the count of rows dumped.
     */
    public static final String CTX_ROW_COUNT = "rowCount";

    /**
     * Execution-context key for the length (in bytes) of the assembled
     * report buffer written to S3.
     */
    public static final String CTX_REPORT_BYTES = "reportBytes";

    /**
     * Execution-context key for the S3 {@code reportId} used by the
     * call to {@link S3OutputService#writeReport(String, byte[])}.
     */
    public static final String CTX_REPORT_ID = "reportId";

    /**
     * S3 {@code reportId} prefix that distinguishes a print-category-
     * balance dump from the {@code tranrept} (transaction report) and
     * statement objects which all share the {@code tranrept/} S3 key
     * prefix per {@link S3OutputService#writeReport(String, byte[])}.
     */
    static final String REPORT_ID_PREFIX = "tcatbal-";

    /**
     * Exact byte width of one rendered line per the DFSORT
     * {@code OUTREC} layout (truncated to {@code LRECL=40} by the
     * original DCB).
     */
    static final int LINE_WIDTH_BYTES = 40;

    /**
     * Width of the {@code TRAN-CAT-BAL} field after the
     * {@code EDIT=(TTTTTTTTT.TT)} mask is applied:
     * 9 integer digits (with leading-zero suppression to spaces) + 1
     * decimal point + 2 fraction digits = 12 chars.
     */
    static final int EDIT_FIELD_WIDTH = 12;

    /**
     * Canonical {@link Comparator} chain on the composite key. Exposed
     * package-private so tests can verify the sort order independently
     * of the repository implementation.
     *
     * <p>// COBOL: SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,
     * TRANCAT-CD,A) — preserved verbatim as a Java Comparator chain.</p>
     */
    static final Comparator<TransactionCategoryBalance> CANONICAL_ORDER =
            Comparator
                    .comparing(
                            (TransactionCategoryBalance b) -> b.getId() != null
                                    ? b.getId().getTrancatAcctId() : null,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(
                            b -> b.getId() != null
                                    ? b.getId().getTrancatTypeCd() : null,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(
                            b -> b.getId() != null
                                    ? b.getId().getTrancatCd() : null,
                            Comparator.nullsLast(Comparator.naturalOrder()));

    // =========================================================================
    // Constructor-injected collaborators (AAP §0.7.1 — constructor injection
    // for loose coupling).
    // =========================================================================

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;
    private final S3OutputService s3OutputService;
    private final AuditLogService auditLogService;

    /**
     * Constructs the {@code printCategoryBalanceJob} configuration with
     * all required collaborators via constructor injection (per AAP
     * &sect;0.7.1 "Dependency injection for loose coupling").
     *
     * @param jobRepository                          Spring Batch metadata
     *                                               repository
     * @param transactionManager                     JPA transaction
     *                                               manager (the
     *                                               {@code JpaTransactionManager}
     *                                               from {@code JpaConfig})
     * @param transactionCategoryBalanceRepository   the JPA repository
     *                                               backing the
     *                                               {@code tran_cat_bal}
     *                                               table (replaces the
     *                                               source VSAM
     *                                               {@code TCATBALF}
     *                                               cluster)
     * @param s3OutputService                        the AWS S3 output
     *                                               adapter that writes
     *                                               the assembled report
     *                                               bytes (replaces the
     *                                               {@code TCATBALF.REPT}
     *                                               sequential dataset)
     * @param auditLogService                        the audit-log adapter
     *                                               that emits the
     *                                               batch lifecycle
     *                                               event (replaces JES
     *                                               SYSPRINT)
     */
    public PrintCategoryBalanceJob(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            TransactionCategoryBalanceRepository transactionCategoryBalanceRepository,
            S3OutputService s3OutputService,
            AuditLogService auditLogService) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.transactionCategoryBalanceRepository = transactionCategoryBalanceRepository;
        this.s3OutputService = s3OutputService;
        this.auditLogService = auditLogService;
    }

    // =========================================================================
    // Bean: Job
    // =========================================================================

    /**
     * Defines the {@code printCategoryBalanceJob} Spring Batch
     * {@link Job} bean.
     *
     * <p>// Replaces: app/jcl/PRTCATBL.jcl entire job stream
     * (DELDEF + STEP05R IDCAMS REPRO + STEP10R DFSORT).</p>
     *
     * <p>Parameter validation is enforced via the shared
     * {@code standardJobParametersValidator} bean defined in
     * {@link BatchJobConfig#standardJobParametersValidator()}. This
     * validator rejects any launch attempt that omits the mandatory
     * {@code batchRunId} JobParameter &mdash; preventing untraceable
     * batch executions (AAP &sect;0.7.1 audit-traceability rule).</p>
     *
     * @param sharedAuditJobExecutionListener the shared lifecycle audit
     *                                        listener bean from
     *                                        {@link BatchJobConfig}
     *                                        (emits
     *                                        {@code BATCH_JOB_STARTED}
     *                                        and
     *                                        {@code BATCH_JOB_COMPLETED}/
     *                                        {@code BATCH_JOB_FAILED}
     *                                        audit events)
     * @param standardJobParametersValidator  the shared JobParameters
     *                                        validator bean from
     *                                        {@link BatchJobConfig}
     * @return the configured {@link Job} bean &mdash; registered in
     *         the {@code ApplicationContext} under the name
     *         {@value #JOB_NAME}
     */
    @Bean
    public Job printCategoryBalanceJob(
            JobExecutionListener sharedAuditJobExecutionListener,
            JobParametersValidator standardJobParametersValidator) {
        // Replaces: app/jcl/PRTCATBL.jcl — entire job stream (no COBOL program
        // backs this; it was a pure DFSORT utility in the source).
        return new JobBuilder(JOB_NAME, jobRepository)
                .validator(standardJobParametersValidator)
                .listener(sharedAuditJobExecutionListener)
                .start(printCategoryBalanceStep())
                .build();
    }

    // =========================================================================
    // Bean: Step
    // =========================================================================

    /**
     * Defines the orchestration tasklet step that drives the
     * read → sort → render → S3-write pipeline.
     *
     * <p>// Replaces: app/jcl/PRTCATBL.jcl STEP05R IDCAMS REPRO +
     * STEP10R DFSORT.</p>
     *
     * @return the configured {@link Step} bean
     */
    @Bean
    public Step printCategoryBalanceStep() {
        return new StepBuilder(STEP_NAME, jobRepository)
                .tasklet(printCategoryBalanceTasklet(), transactionManager)
                .listener(new PrintCategoryBalanceStepExitStatusListener())
                .build();
    }

    // =========================================================================
    // Bean: Tasklet
    // =========================================================================

    /**
     * Defines the {@link Tasklet} that:
     * <ol>
     *   <li>Reads every {@link TransactionCategoryBalance} from
     *       {@link TransactionCategoryBalanceRepository#findAll(Sort)}
     *       in canonical composite-key order (replaces DFSORT
     *       {@code SORTIN} read of {@code TCATBALF.BKUP}).</li>
     *   <li>Sorts in-memory via {@link #CANONICAL_ORDER} as a defensive
     *       belt-and-suspenders measure (replaces DFSORT
     *       {@code SORT FIELDS=...}).</li>
     *   <li>Renders each row to a 40-byte ASCII line via
     *       {@link #formatTcatBalLine(TransactionCategoryBalance)}
     *       (replaces DFSORT
     *       {@code OUTREC FIELDS=(...,EDIT=(TTTTTTTTT.TT),9X)}).</li>
     *   <li>Assembles all lines into a single byte buffer separated by
     *       LF (matches the {@code RECFM=FB LRECL=40} byte stream
     *       flattened to ASCII).</li>
     *   <li>Writes the buffer to S3 via
     *       {@link S3OutputService#writeReport(String, byte[])}
     *       (replaces the {@code TCATBALF.REPT} DD allocation).</li>
     *   <li>Publishes counts to the {@link StepExecution}'s execution
     *       context so Step Functions and operational tooling can
     *       inspect them.</li>
     *   <li>Emits the {@code COMPLETED} batch lifecycle audit event.</li>
     * </ol>
     *
     * @return a {@link Tasklet} returning {@link RepeatStatus#FINISHED}
     *         after a single read → sort → render → write cycle
     */
    @Bean
    public Tasklet printCategoryBalanceTasklet() {
        return (StepContribution contribution, ChunkContext chunkContext) -> {
            // Replaces: app/jcl/PRTCATBL.jcl STEP05R IDCAMS REPRO +
            // STEP10R DFSORT — a pure utility pipeline with no backing
            // COBOL program in the source repository.
            final StepExecution stepExecution =
                    chunkContext.getStepContext().getStepExecution();

            // -----------------------------------------------------------------
            // Job parameter extraction + validation (Tasklet-internal —
            // batchRunId already validated by the shared validator).
            // -----------------------------------------------------------------
            final String batchRunId =
                    stepExecution.getJobParameters().getString(PARAM_BATCH_RUN_ID);
            final String parmDateStr =
                    stepExecution.getJobParameters().getString(PARAM_PARM_DATE);

            // parmDate is OPTIONAL — if supplied, parse to surface a
            // structured value in the audit trail (operators can
            // correlate the dump with a logical reporting period).
            final LocalDate parmDate;
            if (parmDateStr != null && !parmDateStr.isBlank()) {
                try {
                    parmDate = LocalDate.parse(parmDateStr);
                } catch (RuntimeException pex) {
                    throw new IllegalArgumentException(
                            "Optional job parameter '" + PARAM_PARM_DATE
                                    + "' is not a valid ISO-8601 date: '"
                                    + parmDateStr + "'", pex);
                }
            } else {
                parmDate = null;
            }

            LOG.info(
                    "PrintCategoryBalanceJob: starting batchRunId={}, parmDate={}",
                    batchRunId, parmDate);

            // -----------------------------------------------------------------
            // Read + sort (replaces DFSORT SORTIN + SORT FIELDS=...).
            // -----------------------------------------------------------------
            // Repository-level sort: produces composite-key ordering
            // from the underlying PostgreSQL B-tree without an
            // in-memory materialization step.
            final Sort canonicalSort = Sort.by(Sort.Order.asc("id.trancatAcctId"),
                    Sort.Order.asc("id.trancatTypeCd"),
                    Sort.Order.asc("id.trancatCd"));
            final List<TransactionCategoryBalance> rows =
                    transactionCategoryBalanceRepository.findAll(canonicalSort);

            // Defensive in-memory sort — guarantees the output ordering
            // even if a future repository refactor drops the sort
            // clause or a schema change perturbs the natural ordering.
            // The Comparator chain is byte-equivalent to the COBOL
            // SORT FIELDS=(...,A,...,A,...,A) directive.
            // COBOL: SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A)
            rows.sort(CANONICAL_ORDER);

            // -----------------------------------------------------------------
            // Render (replaces DFSORT OUTREC FIELDS=(...,EDIT=(...),9X)).
            // -----------------------------------------------------------------
            // Pre-size the byte buffer: rows × (40 bytes + LF) +
            // defensive 1-byte trailing LF tolerance.
            final int bufSize = rows.size() * (LINE_WIDTH_BYTES + 1) + 1;
            final StringBuilder out = new StringBuilder(bufSize);
            for (TransactionCategoryBalance row : rows) {
                // COBOL: OUTREC FIELDS=(TRANCAT-ACCT-ID,X,TRANCAT-TYPE-CD,X,
                //        TRANCAT-CD,X,TRAN-CAT-BAL,EDIT=(TTTTTTTTT.TT),9X)
                out.append(formatTcatBalLine(row));
                out.append('\n');
            }
            final byte[] reportBytes =
                    out.toString().getBytes(StandardCharsets.US_ASCII);

            // -----------------------------------------------------------------
            // Write to S3 (replaces the TCATBALF.REPT sequential DD
            // allocation).
            // -----------------------------------------------------------------
            // S3 reportId — the batchRunId is the per-run identifier;
            // the REPORT_ID_PREFIX distinguishes this dump from the
            // CBTRN03C transaction report and CBSTM03A statement which
            // share the same S3 key prefix per writeReport()'s
            // implementation.
            final String reportId = REPORT_ID_PREFIX + batchRunId;
            // Note: writeReport() rejects empty byte[] inputs (AAP
            // §0.7.1 defensive contract on the adapter). When the
            // TCATBAL table is empty (rows.isEmpty()), we skip the S3
            // write and emit a structured audit-only event so the
            // operator can detect the empty-state condition without a
            // useless S3 PUT.
            if (reportBytes.length > 0) {
                // Replaces: TCATBALF.REPT DD allocation in PRTCATBL.jcl
                s3OutputService.writeReport(reportId, reportBytes);
            } else {
                LOG.info(
                        "PrintCategoryBalanceJob: TCATBAL table is empty — "
                                + "skipping S3 write (no rows to dump)");
            }

            // -----------------------------------------------------------------
            // Step execution context — surface the counts so Step
            // Functions and operational tooling can inspect them.
            // -----------------------------------------------------------------
            stepExecution.getExecutionContext().putInt(CTX_ROW_COUNT, rows.size());
            stepExecution.getExecutionContext()
                    .putInt(CTX_REPORT_BYTES, reportBytes.length);
            stepExecution.getExecutionContext()
                    .putString(CTX_REPORT_ID, reportId);

            contribution.incrementReadCount();
            contribution.incrementWriteCount(rows.size());

            // -----------------------------------------------------------------
            // Audit (replaces JES SYSPRINT + RETURN-CODE emission).
            // -----------------------------------------------------------------
            final Map<String, Object> auditFields = new LinkedHashMap<>();
            auditFields.put("batchRunId", batchRunId);
            if (parmDate != null) {
                auditFields.put("parmDate", parmDate.toString());
            }
            auditFields.put("rowCount", rows.size());
            auditFields.put("reportBytes", reportBytes.length);
            auditFields.put("reportId", reportId);
            auditLogService.logBatchJobLifecycle(
                    JOB_NAME,
                    String.valueOf(stepExecution.getJobExecutionId()),
                    "COMPLETED",
                    null,
                    auditFields,
                    stepExecution.getJobParameters().getString("correlationId"));

            LOG.info(
                    "PrintCategoryBalanceJob: completed batchRunId={}, "
                            + "rowCount={}, reportBytes={}, reportId={}",
                    batchRunId, rows.size(), reportBytes.length, reportId);

            return RepeatStatus.FINISHED;
        };
    }

    // =========================================================================
    // Formatting helpers — exposed package-private for direct unit testing.
    // =========================================================================

    /**
     * Renders a single {@link TransactionCategoryBalance} as a 40-byte
     * ASCII line matching the DFSORT {@code OUTREC} layout from
     * {@code PRTCATBL.jcl STEP10R}.
     *
     * <p>Layout (40 bytes total):</p>
     *
     * <pre>
     *   pos  1-11  TRANCAT-ACCT-ID     11 digits, zero-padded
     *   pos    12  filler              1 space
     *   pos 13-14  TRANCAT-TYPE-CD     2 chars (padded with trailing
     *                                  space if &lt; 2 chars)
     *   pos    15  filler              1 space
     *   pos 16-19  TRANCAT-CD          4 digits, zero-padded
     *   pos    20  filler              1 space
     *   pos 21-32  TRAN-CAT-BAL        12 chars, right-justified with
     *                                  leading spaces; fixed decimal
     *                                  point at position 30
     *   pos 33-40  trailing filler     8 spaces (DFSORT {@code 9X}
     *                                  truncated by LRECL=40)
     * </pre>
     *
     * <p>// COBOL: OUTREC FIELDS=(TRANCAT-ACCT-ID,X,TRANCAT-TYPE-CD,X,
     * TRANCAT-CD,X,TRAN-CAT-BAL,EDIT=(TTTTTTTTT.TT),9X)</p>
     *
     * @param row the {@link TransactionCategoryBalance} to render (must
     *            not be {@code null})
     * @return a 40-byte ASCII string (no trailing newline; the caller
     *         appends the LF separator)
     */
    static String formatTcatBalLine(TransactionCategoryBalance row) {
        if (row == null) {
            throw new IllegalArgumentException(
                    "TransactionCategoryBalance must not be null");
        }
        final TransactionCategoryBalanceId id = row.getId();

        // Composite-key sub-field extraction with null-defensive
        // defaults. Persisted rows always have non-null id components
        // (composite primary key) but we defend against test fixtures
        // and partially-initialized in-memory aggregates.
        final long acctId = id != null && id.getTrancatAcctId() != null
                ? id.getTrancatAcctId() : 0L;
        final String typeCdRaw = id != null && id.getTrancatTypeCd() != null
                ? id.getTrancatTypeCd() : "";
        final int catCd = id != null && id.getTrancatCd() != null
                ? id.getTrancatCd() : 0;

        // PIC 9(11): 11 digits, zero-padded. Format.format("%011d", ...)
        // emits exactly 11 digits for non-negative longs; defensive
        // negative values (which should never occur for an account ID
        // per the source PIC clause) would emit a leading '-' and
        // exceed the field width — we Math.abs() to defend.
        final String acctIdField = String.format("%011d", Math.abs(acctId));

        // PIC X(02): 2 chars. Pad with trailing spaces if shorter; for
        // safety, truncate to 2 chars if somehow longer (production
        // data should never violate the PIC clause but the JPA column
        // is declared CHAR(2) by Hibernate, so this is a defensive
        // guard for test fixtures).
        final String typeCdField =
                typeCdRaw.length() >= 2
                        ? typeCdRaw.substring(0, 2)
                        : String.format("%-2s", typeCdRaw);

        // PIC 9(04): 4 digits, zero-padded.
        final String catCdField = String.format("%04d", Math.abs(catCd));

        // EDIT=(TTTTTTTTT.TT): 12 chars, right-justified, leading
        // zeros suppressed to spaces, fixed decimal point at the
        // 10th character position (9 integer digits + period + 2
        // fraction digits).
        final BigDecimal bal = row.getTranCatBal() != null
                ? row.getTranCatBal() : BigDecimal.ZERO;
        final String balField = editTranCatBal(bal);

        // Assemble per the OUTREC layout:
        //   ACCT(11) + X(1) + TYPE(2) + X(1) + CAT(4) + X(1) + BAL(12) + 8X
        // = 11 + 1 + 2 + 1 + 4 + 1 + 12 + 8 = 40 bytes total
        final StringBuilder line = new StringBuilder(LINE_WIDTH_BYTES);
        line.append(acctIdField);   // 11 bytes
        line.append(' ');           // 1 byte
        line.append(typeCdField);   // 2 bytes
        line.append(' ');           // 1 byte
        line.append(catCdField);    // 4 bytes
        line.append(' ');           // 1 byte
        line.append(balField);      // 12 bytes
        line.append("        ");    // 8 bytes (DFSORT 9X truncated by LRECL=40)

        // Defensive assertion: any drift in field widths must fail
        // loudly. The COBOL DFSORT OUTREC layout is the contract per
        // AAP §0.7.2 ("regulatory output formats must remain identical")
        // even though PRTCATBL output is not in the 9-fixture golden
        // diff suite.
        if (line.length() != LINE_WIDTH_BYTES) {
            throw new IllegalStateException(
                    "PrintCategoryBalanceJob line width drift: "
                            + "expected " + LINE_WIDTH_BYTES
                            + " bytes, got " + line.length());
        }
        return line.toString();
    }

    /**
     * Applies the DFSORT {@code EDIT=(TTTTTTTTT.TT)} numeric edit mask
     * to a {@link BigDecimal} value. Produces a 12-character string
     * right-justified with leading spaces:
     *
     * <ul>
     *   <li>{@code 12345.67}  → {@code "    12345.67"}</li>
     *   <li>{@code 0.50}      → {@code "        0.50"}</li>
     *   <li>{@code 0.00}      → {@code "        0.00"}</li>
     *   <li>{@code 1.00}      → {@code "        1.00"}</li>
     *   <li>{@code -12345.67} → {@code "   -12345.67"}</li>
     * </ul>
     *
     * <p>// COBOL: EDIT=(TTTTTTTTT.TT) — fixed-width 9-digit integer +
     * decimal point + 2-digit fraction edit mask. Leading zeros
     * suppress to spaces; a non-zero value with fewer than 9 integer
     * digits right-justifies within the field.</p>
     *
     * @param bal the {@link BigDecimal} to format. {@code null} is
     *            normalised to {@link BigDecimal#ZERO}. The value is
     *            rounded to scale=2 via
     *            {@link RoundingMode#HALF_EVEN} per AAP &sect;0.6.1.
     * @return exactly 12 characters in the EDIT mask layout
     * @throws IllegalArgumentException if the rounded value's plain
     *         string representation exceeds 12 characters (which
     *         requires a value greater in magnitude than
     *         {@code 999999999.99} — outside the source
     *         {@code PIC S9(09)V99} domain)
     */
    static String editTranCatBal(BigDecimal bal) {
        // AAP §0.6.1: BigDecimal arithmetic with RoundingMode.HALF_EVEN
        // and explicit scale=2 — no implicit precision drift.
        final BigDecimal scaled = (bal != null ? bal : BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_EVEN);
        final String plain = scaled.toPlainString();
        if (plain.length() > EDIT_FIELD_WIDTH) {
            // Source PIC S9(09)V99 supports values in
            // [-999999999.99, 999999999.99]. Anything wider is an
            // arithmetic overflow that should never reach this
            // formatter; throwing here is loud-fail-fast defensive
            // discipline per AAP §0.6.1 ("ON SIZE ERROR" parity).
            throw new IllegalArgumentException(
                    "TRAN-CAT-BAL value '" + plain + "' exceeds "
                            + EDIT_FIELD_WIDTH + "-char EDIT mask width — "
                            + "this is an arithmetic overflow beyond the source "
                            + "PIC S9(09)V99 domain [-999999999.99, 999999999.99]");
        }
        // Right-justify in a 12-char field with leading spaces. This
        // produces the same byte layout as DFSORT's T-with-leading-
        // zero-suppression for all positive values in the source
        // domain (the decimal point lands at a position that depends
        // on the magnitude of the integer part, exactly as DFSORT
        // would emit it).
        return String.format("%" + EDIT_FIELD_WIDTH + "s", plain);
    }

    // =========================================================================
    // Inner class: StepExecutionListener — RETURN-CODE → ExitStatus mapping.
    // =========================================================================

    /**
     * {@link StepExecutionListener} for the
     * {@code printCategoryBalanceStep}.
     *
     * <p>Maps any thrown exception to {@link ExitStatus#FAILED} (COBOL
     * RETURN-CODE = 8); on clean completion the default
     * {@link ExitStatus#COMPLETED} (RETURN-CODE = 0) is preserved.
     * {@code PRTCATBL.jcl} has no "completed with rejects" semantic
     * (every row is dumped or the step fails), so there is no
     * {@code COMPLETED_WITH_REJECTS} branch — matching the simpler
     * pattern from
     * {@link InterestCalculationJob} rather than the rejects-aware
     * pattern from
     * {@link DailyTransactionPostingJob}.</p>
     */
    private static final class PrintCategoryBalanceStepExitStatusListener
            implements StepExecutionListener {

        @Override
        public void beforeStep(StepExecution stepExecution) {
            // No-op — initial exit status is COMPLETED by default;
            // afterStep below preserves any failure status that arose
            // during the tasklet's execution.
        }

        @Override
        public ExitStatus afterStep(StepExecution stepExecution) {
            final ExitStatus existing = stepExecution.getExitStatus();
            // Preserve any non-COMPLETED status (e.g., FAILED from an
            // unhandled exception, STOPPED from an external stop
            // signal). PRTCATBL has no rejects concept so clean
            // completions remain COMPLETED.
            if (existing != null
                    && !ExitStatus.COMPLETED.getExitCode()
                            .equals(existing.getExitCode())) {
                LOG.warn(
                        "PrintCategoryBalanceJob step exitStatus={} preserved",
                        existing.getExitCode());
                return existing;
            }
            return ExitStatus.COMPLETED;
        }
    }
}
