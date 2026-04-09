package com.cardemo.batch.jobs;

import com.cardemo.batch.processors.StatementProcessor;
import com.cardemo.batch.writers.StatementWriter;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.repository.CardCrossReferenceRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Collections;

/**
 * Spring Batch job configuration for statement generation — Pipeline Stage 4a.
 *
 * <p>Migrated from {@code CREASTMT.JCL} (98 lines, 5 steps) plus
 * {@code CBSTM03A.CBL} (924 lines, main orchestrator) and
 * {@code CBSTM03B.CBL} (230 lines, file-service subroutine).
 *
 * <h3>Original JCL Pipeline (5 steps):</h3>
 * <ol>
 *   <li>DELDEF01 — Delete and redefine work VSAM
 *       (→ not needed; PostgreSQL tables are persistent)</li>
 *   <li>STEP010  — SORT transactions by card number + transaction ID
 *       (→ handled by JPA ORDER BY in {@link StatementProcessor})</li>
 *   <li>STEP020  — REPRO sorted sequential into work VSAM
 *       (→ not needed; JPA queries replace VSAM REPRO)</li>
 *   <li>STEP030  — Delete previous statement outputs
 *       (→ S3 versioning replaces delete-before-write pattern)</li>
 *   <li>STEP040  — Run CBSTM03A (statement generation program)
 *       (→ Spring Batch chunk step: reader → processor → writer)</li>
 * </ol>
 *
 * <h3>Spring Batch Architecture:</h3>
 * <pre>
 *   RepositoryItemReader&lt;CardCrossReference&gt;   (← 1000-XREFFILE-GET-NEXT)
 *        ↓
 *   StatementProcessor                           (← CBSTM03A + CBSTM03B logic)
 *        ↓  produces StatementWriter.StatementOutput (text + HTML per card)
 *   StatementWriter                              (← WRITE FD-STMTFILE / FD-HTMLFILE → S3)
 * </pre>
 *
 * <h3>COBOL-to-Java Paragraph Mapping:</h3>
 * <table>
 *   <tr><td>CBSTM03A main loop + 1000-XREFFILE-GET-NEXT</td>
 *       <td>RepositoryItemReader&lt;CardCrossReference&gt;</td></tr>
 *   <tr><td>2000-CUSTFILE-GET (via CBSTM03B CALL)</td>
 *       <td>StatementProcessor → CustomerRepository.findById()</td></tr>
 *   <tr><td>3000-ACCTFILE-GET (via CBSTM03B CALL)</td>
 *       <td>StatementProcessor → AccountRepository.findById()</td></tr>
 *   <tr><td>5000-CREATE-STATEMENT</td>
 *       <td>StatementProcessor: generate text + HTML header</td></tr>
 *   <tr><td>4000-TRNXFILE-GET + 6000-WRITE-TRANS</td>
 *       <td>StatementProcessor: iterate transactions, format lines</td></tr>
 *   <tr><td>5100-WRITE-HTML-HEADER + 5200-WRITE-HTML-NMADBS</td>
 *       <td>StatementProcessor: HTML boilerplate + customer/account info</td></tr>
 *   <tr><td>WRITE FD-STMTFILE-REC / FD-HTMLFILE-REC</td>
 *       <td>StatementWriter: S3 upload (text + HTML)</td></tr>
 *   <tr><td>CBSTM03B file operations (O/C/R/K/W/Z)</td>
 *       <td>JPA Repository calls (abstracted by Spring Data)</td></tr>
 * </table>
 *
 * <p>This stage runs IN PARALLEL with Stage 4b ({@code TransactionReportJob})
 * after Stage 3 ({@code CombineTransactionsJob}) completes, wired via
 * {@code BatchPipelineOrchestrator}'s {@code FlowBuilder.split()}.
 *
 * <p>Chunk size is configurable via {@code carddemo.batch.chunk-size.statement}
 * (default 10) — smaller than standard chunk sizes because each item triggers
 * multiple database reads (customer, account, transactions) and dual-format
 * statement generation, making per-item processing heavy.
 *
 * @see StatementProcessor
 * @see StatementWriter
 * @see CardCrossReference
 * @see CardCrossReferenceRepository
 */
@Configuration("statementGenerationJobConfig")
public class StatementGenerationJob {

    private static final Logger log = LoggerFactory.getLogger(StatementGenerationJob.class);

    /**
     * Configurable chunk commit interval for statement generation.
     *
     * <p>Defaults to 10 — intentionally smaller than the typical batch chunk size
     * (50–100) because each {@link CardCrossReference} item triggers:
     * <ul>
     *   <li>1 CustomerRepository lookup (2000-CUSTFILE-GET)</li>
     *   <li>1 AccountRepository lookup (3000-ACCTFILE-GET)</li>
     *   <li>N TransactionRepository lookups (4000-TRNXFILE-GET per card)</li>
     *   <li>Dual-format statement generation (text LRECL=80 + HTML LRECL=100)</li>
     * </ul>
     * Keeping the chunk small limits memory pressure and transaction scope.
     */
    @Value("${carddemo.batch.chunk-size.statement:10}")
    private int chunkSize;

    // -----------------------------------------------------------------------
    // Reader Bean — Card Cross-Reference Sequential Read
    // (replaces CBSTM03A 1000-XREFFILE-GET-NEXT via CBSTM03B 'R' operation)
    // -----------------------------------------------------------------------

    /**
     * Configures a {@link RepositoryItemReader} that sequentially reads all
     * {@link CardCrossReference} records sorted by card number ascending.
     *
     * <p>This bean replaces the COBOL main loop in {@code CBSTM03A.CBL}
     * (paragraph {@code 1000-XREFFILE-GET-NEXT}) where {@code CBSTM03B} performs
     * sequential reads on {@code XREFFILE} via the 'R' (Read Next) operation.
     * Each cross-reference record maps one card to its customer and account,
     * driving one complete statement generation cycle in the processor.
     *
     * <p>Configuration details:
     * <ul>
     *   <li>Repository: {@link CardCrossReferenceRepository}</li>
     *   <li>Method: {@code findAll} — reads all cross-reference records</li>
     *   <li>Sort: {@code xrefCardNum ASC} — matching COBOL sequential KSDS read</li>
     *   <li>Page size: 50 — balanced between memory and DB round-trip efficiency</li>
     * </ul>
     *
     * @param cardCrossReferenceRepository the JPA repository for card cross-references
     * @return configured reader producing {@link CardCrossReference} items
     */
    @Bean("statementXrefReader")
    public RepositoryItemReader<CardCrossReference> statementXrefReader(
            CardCrossReferenceRepository cardCrossReferenceRepository) {

        log.debug("Configuring statement XREF reader — sequential ascending by card number "
                + "(CBSTM03A 1000-XREFFILE-GET-NEXT equivalent)");

        return new RepositoryItemReaderBuilder<CardCrossReference>()
                .name("statementXrefReader")
                .repository(cardCrossReferenceRepository)
                .methodName("findAll")
                .pageSize(50)
                .sorts(Collections.singletonMap("xrefCardNum", Sort.Direction.ASC))
                .build();
    }

    // -----------------------------------------------------------------------
    // Job Bean — Statement Generation Job (CREASTMT.JCL equivalent)
    // -----------------------------------------------------------------------

    /**
     * Defines the top-level Spring Batch {@link Job} for statement generation.
     *
     * <p>Maps to the complete {@code CREASTMT.JCL} job definition.
     * The original JCL had 5 steps (DELDEF01, STEP010, STEP020, STEP030, STEP040),
     * but in the Java/Spring Batch migration:
     * <ul>
     *   <li>Steps 1–3 (VSAM prep, SORT, REPRO) are unnecessary — JPA handles
     *       data access and sorting transparently</li>
     *   <li>Step 4 (delete previous outputs) is handled by S3 versioning —
     *       new statement uploads do not require prior deletion</li>
     *   <li>Step 5 (run CBSTM03A) maps to the single chunk-based
     *       {@code statementGenerationStep}</li>
     * </ul>
     *
     * <p>The job uses {@link RunIdIncrementer} to support re-execution with
     * unique job instance IDs, enabling multiple runs (e.g., re-generation
     * after corrections) without Spring Batch's "already complete" guard.
     *
     * @param jobRepository          batch metadata persistence
     * @param statementGenerationStep the single chunk step for statement processing
     * @return fully configured statement generation job
     */
    @Bean("statementGenerationJob")
    public Job statementGenerationJob(
            JobRepository jobRepository,
            @Qualifier("statementGenerationStep") Step statementGenerationStep) {

        log.info("Starting statement generation job (CREASTMT equivalent)");

        return new JobBuilder("statementGenerationJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(statementGenerationStep)
                .build();
    }

    // -----------------------------------------------------------------------
    // Step Bean — Chunk-Based Statement Processing (CREASTMT STEP040 equivalent)
    // -----------------------------------------------------------------------

    /**
     * Defines the chunk-based step that processes card cross-reference records
     * into dual-format account statements.
     *
     * <p>Item type flow:
     * <pre>
     *   CardCrossReference → StatementProcessor → StatementWriter.StatementOutput → StatementWriter
     * </pre>
     *
     * <p>This step encapsulates the entire business logic of {@code CBSTM03A.CBL}
     * (924 lines) and {@code CBSTM03B.CBL} (230 lines, file-service subroutine):
     * <ul>
     *   <li><b>Reader:</b> Sequential card cross-reference iteration
     *       (1000-XREFFILE-GET-NEXT)</li>
     *   <li><b>Processor:</b> Customer/account/transaction lookups + dual-format
     *       statement generation (paragraphs 2000–6000)</li>
     *   <li><b>Writer:</b> S3 upload of text and HTML statement files
     *       (replacing WRITE FD-STMTFILE-REC / FD-HTMLFILE-REC)</li>
     * </ul>
     *
     * <p>The COBOL 2-D array {@code WS-TRNX-TABLE} (51 cards × 10 transactions)
     * is replaced by JPA queries per card in the processor, eliminating the
     * fixed-size constraint. {@code WS-TOTAL-AMT} (PIC S9(9)V99 COMP-3)
     * accumulation uses {@code BigDecimal} in the processor with
     * {@code RoundingMode.HALF_EVEN} for banker's rounding.
     *
     * @param jobRepository       batch metadata persistence
     * @param transactionManager  transaction manager for chunk commit boundaries
     * @param statementXrefReader reader producing {@link CardCrossReference} items
     * @param statementProcessor  processor generating dual-format statements
     * @param statementWriter     writer uploading statements to S3
     * @return configured statement generation step
     */
    @Bean("statementGenerationStep")
    public Step statementGenerationStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            @Qualifier("statementXrefReader")
            RepositoryItemReader<CardCrossReference> statementXrefReader,
            StatementProcessor statementProcessor,
            StatementWriter statementWriter) {

        log.info("Configuring statement generation step — chunk size {}, "
                + "CREASTMT STEP040 CBSTM03A equivalent", chunkSize);

        return new StepBuilder("statementGenerationStep", jobRepository)
                .<CardCrossReference, StatementWriter.StatementOutput>chunk(
                        chunkSize, transactionManager)
                .reader(statementXrefReader)
                .processor(statementProcessor)
                .writer(statementWriter)
                .build();
    }
}
