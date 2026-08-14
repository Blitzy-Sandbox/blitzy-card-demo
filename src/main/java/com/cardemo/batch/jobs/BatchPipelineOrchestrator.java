/*
 * ******************************************************************
 * Program     : BatchPipelineOrchestrator.java
 * Application : CardDemo
 * Type        : Spring Batch Flow Composition
 * Function    : End-to-end batch pipeline — sequential and parallel
 *               flow composition with return-code gating.
 * Source      : app/jcl/POSTTRAN.jcl + INTCALC.jcl + COMBTRAN.jcl +
 *               CREASTMT.JCL + TRANREPT.jcl + app/proc/TRANREPT.prc +
 *               app/csd/CARDDEMO.CSD (TDQUEUE JOBS, RECORDSIZE 80)
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
package com.cardemo.batch.jobs;

import com.cardemo.batch.readers.CombinedTransactionReader;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.job.flow.support.SimpleFlow;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

import com.cardemo.batch.readers.CombinedTransactionReader;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.ValidationException;
import com.cardemo.observability.BatchJobSpanNamingConvention;
import com.cardemo.observability.CorrelationIdFilter;

/**
 * The composition root of the batch stream: the Java replacement for the JES2 job stream itself, rather than
 * for any one job within it.
 *
 * <h2>What it does</h2>
 *
 * <p>Five stages, four of them sequential and the last a two-way parallel split:
 *
 * <ol>
 *   <li><b>POSTTRAN</b> - daily transaction posting. {@code app/jcl/POSTTRAN.jcl:L23}
 *       {@code //STEP15 EXEC PGM=CBTRN02C}, a single step with <b>no {@code COND=} anywhere in the
 *       member</b>. Launched through {@link DailyTransactionPostingJob}.</li>
 *   <li><b>INTCALC</b> - interest calculation. {@code app/jcl/INTCALC.jcl:L22}
 *       {@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'}, again with no {@code COND=}. Launched through
 *       {@link InterestCalculationJob}.</li>
 *   <li><b>COMBTRAN</b> - combine transactions. {@code app/jcl/COMBTRAN.jcl:L22}
 *       {@code //STEP05R  EXEC PGM=SORT} then {@code :L41} {@code //STEP10 EXEC PGM=IDCAMS}, the second step
 *       <b>ungated</b>. No COBOL program exists for this job. Launched through
 *       {@link CombineTransactionsJob}.</li>
 *   <li><b>CREASTMT</b> and <b>TRANREPT</b> - statement generation and the transaction report,
 *       <b>in parallel</b>. {@code app/jcl/CREASTMT.JCL} (note the upper-case extension) and
 *       {@code app/proc/TRANREPT.prc}. Launched through {@link StatementGenerationJob} and
 *       {@link TransactionReportJob}.</li>
 * </ol>
 *
 * <p><b>Why stages 1, 2 and 3 are sequential is a data dependency, not a convention.</b> INTCALC writes
 * {@code AWS.M2.CARDDEMO.SYSTRAN(+1)} at {@code app/jcl/INTCALC.jcl:L37-L41}, and COMBTRAN reads
 * {@code AWS.M2.CARDDEMO.SYSTRAN(0)} as the second half of its concatenated {@code SORTIN} at
 * {@code app/jcl/COMBTRAN.jcl:L25-L26}. Parallelising those two would read a generation before it was
 * written.
 *
 * <p><b>Why stage 4 is a split is the absence of any such dependency.</b> Statement generation consumes the
 * transaction cluster through its own projected work cluster
 * ({@code app/jcl/CREASTMT.JCL:L45} {@code SORTIN} and {@code :L83} {@code TRNXFILE}) and writes
 * {@code STATEMNT.PS} and {@code STATEMNT.HTML} at {@code :L91} and {@code :L96}. The transaction report
 * consumes its own backup generation ({@code app/proc/TRANREPT.prc:L37}) and writes
 * {@code AWS.M2.CARDDEMO.TRANREPT(+1)} at {@code :L78}. <b>Neither member references the other and neither
 * output is the other's input</b>, so ordering them would be an invented constraint. That absence of a
 * cross-reference is the whole justification for the one performance tradeoff this class makes, and it is
 * stated rather than assumed.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Build and gate the module with {@code ./mvnw -B -ntp clean verify}, which
 * compiles at release 25 under {@code -Xlint:all -Werror} and runs the doclint gate. Run the unit tier alone
 * with {@code ./mvnw -B -ntp test}; the integration tier that exercises this class end to end lives under
 * {@code src/test/java/com/cardemo/integration/batch} and runs in the {@code verify} phase.
 *
 * <p><b>Nothing here runs on startup.</b> {@code spring.batch.job.enabled} is {@code false} in the base,
 * {@code test} and {@code prod} profiles of {@code src/main/resources/application.yml}, whose own comment
 * names this orchestrator and the queue listener as the only two launchers. There are exactly two legitimate
 * launch paths:
 *
 * <ul>
 *   <li><b>An explicit call to {@link #launchPipeline(Job, String, String, String)}</b>, by a test or by any
 *       caller that already holds the {@value #JOB_BEAN_NAME} bean. This class declares <b>no runner of its
 *       own</b>: an operator submission - the modern equivalent of submitting the deck through TSO SUBMIT or
 *       SDSF - goes through the framework's own {@code JobLauncherApplicationRunner}, switched on for that
 *       one process with {@code --spring.batch.job.enabled=true --spring.batch.job.name=POSTTRAN}, or any
 *       other of the job NAMES that command names - never a bean name, because the runner matches
 *       {@code Job.getName()}. The whole command, why the value is a name rather than a bean, and why an
 *       authored runner was removed rather than kept, are set out in full above
 *       {@link #launchPipeline(Job, String, String, String)}. Seven job beans are namable that way: the whole
 *       stream, any one of its five stages, and the read-only dataset verification job, which
 *       {@code com.cardemo.config.BatchConfig} composes from the four steps that translate
 *       {@code app/jcl/READACCT.jcl}, {@code READCARD.jcl}, {@code READXREF.jcl} and
 *       {@code READCUST.jcl}.</li>
 *   <li><b>The queue listener that replaces the JES2 internal reader.</b> The legacy path is
 *       {@code app/cbl/CORPT00C.cbl:L88-L100}, an eighteen-card job deck held as {@code PIC X(80)} literals
 *       whose {@code :L94} card is {@code "//STEP10 EXEC PROC=TRANREPT"} - the sole
 *       {@code EXEC PROC=TRANREPT} in the corpus and the only place the online side submits any job in this
 *       stream. Those cards were written to the transient data queue defined at
 *       {@code app/csd/CARDDEMO.CSD:L499-L505}: {@code DEFINE TDQUEUE(JOBS)} with {@code TYPE(EXTRA)},
 *       {@code DDNAME(INREADER)}, {@code :L502} {@code OPENTIME(INITIAL) TYPEFILE(OUTPUT) RECORDSIZE(80)} and
 *       {@code :L503} {@code RECORDFORMAT(FIXED) BLOCKFORMAT(UNBLOCKED) DISPOSITION(MOD)}. The modern form is
 *       a typed JSON message on the {@code carddemo-report-jobs} FIFO queue carrying the report name and the
 *       start and end dates, which is the 80-byte parameter record's contract expressed as a payload.
 *       <b>That listener is deliberately not declared here</b>: it belongs to the report submission surface,
 *       and this class only exposes the entry point it calls.</li>
 * </ul>
 *
 * <p>There is <b>no time trigger</b>. The legacy stream is operator-driven and queue-driven, so no
 * {@code @Scheduled} method, no cron expression and no task scheduler appears anywhere in this class;
 * inventing one would be a behaviour change.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>One property, and one only. {@code carddemo.batch.jobs.pipeline.name} sets the registered job name and
 * defaults to {@value #DEFAULT_JOB_NAME}. It is absent from every profile in
 * {@code src/main/resources/application.yml}, which deliberately deletes keys that nothing binds, so the
 * documented default governs unless a deployment supplies the key.
 *
 * <p>Everything else this class needs is <b>injected, never declared</b>: the five sibling {@link Job} beans,
 * the {@link JobRepository}, the {@link JobLauncher} and the transaction manager. No
 * {@code JobRepository}, {@code JobLauncher}, {@code JobExplorer}, {@code JobOperator},
 * {@code PlatformTransactionManager}, {@code DataSource}, {@code TaskExecutor}, {@code JdbcTemplate},
 * object-store client or queue template bean is contributed, and {@code @EnableBatchProcessing} appears
 * nowhere - under Spring Boot 3.x it <em>disables</em> batch auto-configuration and would remove the very
 * infrastructure this class depends on. The buckets are not bound either: this class touches no object
 * store, so binding {@code carddemo.aws.s3.batch-output-bucket} would be state nothing reads.
 *
 * <h2>The return-code contract</h2>
 *
 * <p>The whole gating rule descends from five lines. {@code app/cbl/CBTRN02C.cbl:L227-L231} reads
 * {@code DISPLAY 'TRANSACTIONS PROCESSED :'}, {@code DISPLAY 'TRANSACTIONS REJECTED  :'}, then
 * {@code IF WS-REJECT-COUNT > 0}, {@code MOVE 4 TO RETURN-CODE}, {@code END-IF}. The move at {@code :L230}
 * is the <b>only numeric-literal {@code RETURN-CODE} assignment in the entire corpus</b>; the only other
 * {@code TO RETURN-CODE} site anywhere is the variable move at {@code app/cbl/CSUTLDTC.cbl:L98}.
 *
 * <table border="1">
 *   <caption>The four outcomes, their evidence and what the pipeline does</caption>
 *   <tr><th>RC</th><th>Meaning</th><th>Determined by</th><th>Pipeline behaviour</th></tr>
 *   <tr><td>0</td><td>completed</td><td>the fall-through of {@code app/cbl/CBTRN02C.cbl:L229}</td>
 *       <td>proceed to the next stage</td></tr>
 *   <tr><td>4</td><td>completed with rejects</td>
 *       <td>the reject count exceeded zero, and <b>nothing else</b>:
 *       {@code app/cbl/CBTRN02C.cbl:L229-L231}</td>
 *       <td><b>proceed to the next stage.</b> RC 4 is <b>not</b> a failure</td></tr>
 *   <tr><td>8</td><td>failed</td><td>an unsuccessful child status with no abend recorded</td>
 *       <td>halt the dependent chain and end failed</td></tr>
 *   <tr><td>12</td><td>abend</td><td>a {@link FatalProcessingException} among the child's failures</td>
 *       <td>halt and report an abend, exit code {@value #EXIT_CODE_ABEND}</td></tr>
 * </table>
 *
 * <p><b>RC 4 must proceed.</b> A pipeline that halted on it would diverge from the legacy stream, because the
 * stream's only step gating is the {@code COND=(0,NE)} carried by {@code app/jcl/CREASTMT.JCL:L56},
 * {@code :L66} and {@code :L79} - and that gating is <b>internal to the statement job</b>, which owns it. It
 * is not duplicated here, and no gating the source lacks is invented between the five stages;
 * {@code app/jcl/COMBTRAN.jcl:L41} runs {@code STEP10} ungated and is the clearest evidence of that.
 *
 * <p><b>RC 4 and RC 12 are independent paths</b>, not thresholds on one integer: a run may reject records and
 * still complete, and a run may abend having rejected none. {@link StageGateDecider} therefore returns four
 * distinct outcomes and the flow routes two of them to the same successor, which is what makes
 * "RC 4 proceeds" visible in the topology rather than buried in a comparison. Covering all four is the named
 * test obligation under Rule 1 clause B, and the integration tier under
 * {@code src/test/java/com/cardemo/integration/batch} is where it is discharged - no test file is added to
 * this package to do it.
 *
 * <p><b>RC 12 is not a cited literal and none is invented.</b> There is no {@code MOVE 12 TO RETURN-CODE}
 * anywhere in the corpus. Twelve is the conventional language-environment consequence of the
 * {@code CALL 'CEE3ABD'} at {@code app/cbl/CBTRN02C.cbl:L711}, immediately preceded by
 * {@code MOVE 999 TO ABCODE} at {@code :L710}. The abend code is therefore <b>999</b>, rendered four
 * characters wide as {@link FatalProcessingException#BATCH_ABEND_CODE} dictates, and never the CICS online
 * value.
 *
 * <p><b>The pipeline reports status; it never terminates the process.</b> No {@code System.exit}, no runtime
 * halt, no shutdown hook and no other JVM termination appears in this class. That is the single most tempting
 * shortcut in an orchestrator and it is refused outright.
 *
 * <h2>The two ten-character date parameters, which are not the same shape</h2>
 *
 * <p>Job parameters are untrusted input and are validated before anything is launched. The pipeline carries
 * two date formats and <b>does not unify them</b>:
 *
 * <ul>
 *   <li>{@value #PARM_DATE_JOB_PARAMETER} - <b>ten digits with no separator</b>, from
 *       {@code app/jcl/INTCALC.jcl:L22} {@code PARM='2022071800'}: eight date digits followed by two zeros.
 *       It is not an ISO date, and the distinction is load-bearing because
 *       {@link InterestCalculationJob} concatenates it ahead of a six-digit suffix to form a sixteen-digit
 *       generated transaction identifier.</li>
 *   <li>{@value #START_DATE_JOB_PARAMETER} and {@value #END_DATE_JOB_PARAMETER} - <b>{@code yyyy-MM-dd} with
 *       dashes</b>, from the sort symbols at {@code app/proc/TRANREPT.prc:L38-L42}, where {@code :L41} is
 *       {@code PARM-START-DATE,C'2022-01-01'} and {@code :L42} is {@code PARM-END-DATE,C'2022-07-06'}.</li>
 * </ul>
 *
 * <p>Supplying one where the other is expected is refused with a {@link ValidationException} naming the
 * parameter, before any child job is launched. No timestamp is parsed, reformatted or normalised anywhere in
 * this class: the transaction timestamps are fixed 26-character images and passing them through untouched is
 * the only correct handling.
 *
 * <h2>The generation-key handoff between stages 2 and 3</h2>
 *
 * <p>A relative generation reference becomes an object key: {@code (+1)} is a new object under a
 * monotonically increasing prefix over a versioned bucket, and {@code (0)} is the lexicographically greatest
 * existing prefix. The legacy stream does not re-resolve "latest" between dependent steps - it re-references
 * the generation it just created, at {@code app/jcl/COMBTRAN.jcl:L43-L44} for
 * {@code TRANSACT.COMBINED(+1)} and at {@code app/proc/TRANREPT.prc:L37} and {@code :L64} for
 * {@code TRANSACT.DALY(+1)} - so neither may this pipeline.
 *
 * <p>The handoff this orchestrator owns is the one that crosses a job boundary: stage 2 writes
 * {@code SYSTRAN(+1)} and stage 3 reads {@code SYSTRAN(0)}. {@link InterestCalculationJob} publishes the
 * generation prefix it allocated and every key it created into its own job execution context; this class
 * copies the keys into the pipeline's context under {@value #SYSTRAN_KEY_COUNT_CONTEXT_ENTRY} and
 * {@value #SYSTRAN_KEY_INDEX_CONTEXT_PREFIX}, pins the <b>generation</b> under
 * {@value #SYSTRAN_GENERATION_CONTEXT_ENTRY}, and <b>hands that generation to stage 3 as an identifying job
 * parameter before stage 3 launches</b> - see {@link #stageParameters}. Only then does it verify, as defence
 * in depth, that the key stage 3 opened lies within the pinned generation; a divergence is an abend naming
 * both, not a silent substitution.
 *
 * <p>The pinned value is the generation prefix and not one key of it, because stage 2 emits one object per
 * chunk: pinning a key would hand stage 3 a fraction of {@code SYSTRAN(0)} and report success. A generation
 * that holds no key at all is still pinned and still read - as zero records - which is the object-store
 * counterpart of {@code DISP=(NEW,CATLG,DELETE)} at {@code app/jcl/INTCALC.jcl:L37-L41} cataloguing an empty
 * dataset when {@code app/cbl/CBACT04C.cbl:L214} suppressed every write. Only a stage 2 that recorded no
 * generation prefix at all is {@value #HANDOFF_ABSENT}, and in that case no parameter is handed over and
 * stage 3 resolves {@code SYSTRAN(0)} for itself - failing allocation if nothing is catalogued, exactly as
 * {@code DISP=SHR} does.
 *
 * <p>The seven generation bases are {@code DALYREJS}, {@code SYSTRAN}, {@code TCATBALF.BKUP},
 * {@code TRANREPT}, {@code TRANSACT.BKUP}, {@code TRANSACT.COMBINED} and {@code TRANSACT.DALY}. Six are
 * defined at {@code app/jcl/DEFGDGB.jcl:L24-L59}, each {@code LIMIT(5) SCRATCH} followed by the idempotency
 * idiom {@code IF LASTCC=12 THEN SET MAXCC=0} - the documented precedent for an idempotent provisioning
 * script. The seventh, {@code DALYREJS}, is defined at {@code app/jcl/DALYREJS.jcl:L24-L28} with no such
 * guard. Record lengths are preserved byte-exactly at the object-store boundary and are owned by the stages,
 * not by this class: {@code DALYTRAN} 350, {@code DALYREJS} 430 as 350 plus an 80-byte trailer,
 * {@code TRANREPT} 133, the five 350-byte transaction generations, {@code STMTFILE} 80,
 * {@code HTMLFILE} 100, the queue card 80, and the {@code TRXFL} work cluster 350 with a 32-byte key that is
 * never persisted. Retention is documented rather than enforced; the {@code TRANREPT} conflict between
 * {@code app/jcl/DEFGDGB.jcl:L38} {@code LIMIT(5)} and {@code app/jcl/REPTFILE.jcl:L27} {@code LIMIT(10)} is
 * resolved by {@link TransactionReportJob}, which owns it, and is cross-referenced here rather than restated.
 *
 * <h2>The three members of the stream with no Java analogue, and one orphan</h2>
 *
 * <ul>
 *   <li>{@code app/jcl/CBADMCDJ.jcl:L27} {@code //STEP1   EXEC PGM=DFHCSDUP,REGION=0M,} installs the CICS
 *       resource definitions. Superseded by {@code com.cardemo.config.SecurityConfig}, which derives endpoint
 *       authorisation from the same {@code app/csd/CARDDEMO.CSD}. No Java analogue, and none is invented.</li>
 *   <li>{@code app/jcl/OPENFIL.jcl} and {@code app/jcl/CLOSEFIL.jcl} issue
 *       {@code CEMT SET FIL(...) OPE} and {@code CLO} for exactly five files - {@code TRANSACT},
 *       {@code CCXREF}, {@code ACCTDAT}, {@code CXACAIX} and {@code USRSEC} - at {@code :L26-L30} in each,
 *       under the {@code //ISFIN  DD *} of {@code app/jcl/OPENFIL.jcl:L25}. Superseded by
 *       {@code com.cardemo.observability.HealthIndicators}. No Java analogue.</li>
 *   <li>{@code app/jcl/DEFCUST.jcl} defines the orphan cluster {@code AWS.CUSTDATA.CLUSTER} at {@code :L35}
 *       with {@code KEYS(10 0)} at {@code :L37} and {@code RECORDSIZE(500 500)} at {@code :L38}. <b>No
 *       program opens it.</b> A finding only; nothing is modelled from it.</li>
 * </ul>
 *
 * <h2>Observability</h2>
 *
 * <p>Every pipeline log event carries {@link CorrelationIdFilter#MDC_KEY_JOB_INSTANCE_ID} and
 * {@link CorrelationIdFilter#MDC_KEY_CORRELATION_ID} in the diagnostic context, alongside the
 * {@link CorrelationIdFilter#MDC_KEY_TRACE_ID} and {@link CorrelationIdFilter#MDC_KEY_SPAN_ID} entries the
 * tracing bridge publishes from real span identity - which this class never writes by hand, because a
 * fabricated trace identifier names a trace no backend holds. Those four key names are the ones
 * {@code src/main/resources/logback-spring.xml} consumes, and they are read from
 * {@link CorrelationIdFilter} rather than re-spelled here. The HTTP correlation filter is request-scoped and
 * does not reach batch, and the observability package is not permitted a job listener, so
 * {@link BatchPipelineJobListener} establishes the context here through
 * {@link CorrelationIdFilter#enterBatchScope(long, String)} and restores it in a {@code finally} through
 * {@link CorrelationIdFilter#exitBatchScope()}. A <b>single stable pipeline correlation identifier</b> spans all five stages: it is
 * {@value #CORRELATION_ID_PREFIX} followed by the pipeline execution identifier, minted only when no outer
 * scope already owns one, and every child job inherits it because each sibling listener mints its own only
 * when the entry is empty.
 *
 * <p><b>The diagnostic context is thread-local and stage 4 runs on two other threads.</b> The split executor
 * therefore decorates each branch, copying the launching thread's context onto the branch thread and clearing
 * it there afterwards. Without that decoration every event from either branch would carry empty identifiers,
 * which is the failure this design exists to prevent rather than a theoretical risk.
 *
 * <p>No instrument is registered and no counter is advanced here. The four counters
 * {@code com.cardemo.observability.MetricsConfig} owns - records processed, records rejected tagged by
 * reject code, authentication attempts and the transaction amount total - replace the end-of-run
 * {@code DISPLAY} statements at {@code app/cbl/CBTRN02C.cbl:L227-L228}, and they are advanced by the stages
 * that read the records. Advancing them again from the composition root would double-count every record, so
 * there is no fifth instrument, no timer, no gauge, no distribution summary and no per-run or per-card tag.
 * No card number, verification value, social security number, credential or password hash reaches any event
 * this class emits: it logs job names, execution identifiers, return codes and object keys, and nothing else.
 *
 * <h2>Findings, by severity</h2>
 *
 * <ul>
 *   <li><b>Blocker</b> - filename casing in {@code app/jcl/} is load-bearing. The directory holds
 *       <b>29</b> members: twenty-eight lower-case {@code .jcl} plus {@code app/jcl/CREASTMT.JCL}. A
 *       case-sensitive {@code *.jcl} glob silently drops it, which would delete stage 4's statement branch
 *       from this pipeline altogether. The same applies to {@code app/cbl/CBSTM03A.CBL},
 *       {@code app/cbl/CBSTM03B.CBL} and {@code app/cpy/COSTM01.CPY}, whose member name is {@code COSTM01}.
 *       Every citation in this file uses the exact on-disk casing.</li>
 *   <li><b>Blocker</b> - {@code app/cbl/CBACT04C.cbl:L219-L220}. The final-flush {@code ELSE} branch that
 *       would update the last account is <b>unreachable</b>, because {@code PERFORM UNTIL} tests its
 *       condition before each iteration and the loop leaves as soon as end of file is detected. The
 *       pipeline-level consequence is real and belongs here: stage 2 leaves one account's cycle counters
 *       unreset, which changes stage 1's over-limit arithmetic on the following run. Nothing is repaired -
 *       {@link InterestCalculationJob} reproduces the source - and the finding is recorded as
 *       {@code DL-LD-09} in {@code DECISION_LOG.md}, with its rows in
 *       {@code TRACEABILITY_MATRIX.md}.</li>
 *   <li><b>Blocker</b> - a transaction's originating and processing timestamps are 26-character images, not
 *       temporal objects, and the batch producer formats them to <b>hundredths-of-a-second</b> precision
 *       followed by four literal zeros, never millisecond precision,
 *       because {@code app/cbl/CBTRN02C.cbl:L159-L174} declares the fraction as {@code DB2-MIL PIC 9(002)}
 *       plus {@code DB2-REST PIC X(04)}, six characters and not seven. This class passes them through
 *       untouched and parses none.</li>
 *   <li><b>High</b> - {@code app/jcl/TRANREPT.jcl} carries the step name {@code STEP05R} <b>twice</b>, at
 *       {@code :L23} and {@code :L37}. The clean three-step authority is {@code app/proc/TRANREPT.prc}
 *       ({@code STEP01R} at {@code :L21}, {@code STEP05R} at {@code :L35}, {@code STEP10R} at {@code :L57},
 *       {@code // PEND} at {@code :L79}), which the standalone member reaches through
 *       {@code app/jcl/TRANREPT.jcl:L19} {@code //JOBLIB JCLLIB ORDER=('AWS.M2.CARDDEMO.PROC')}. Stage 4's
 *       report branch follows the procedure, not the defective member.</li>
 *   <li><b>Medium</b> - the {@code TRANREPT} retention conflict described above. Resolved to ten and owned
 *       by {@link TransactionReportJob}.</li>
 *   <li><b>Low</b> - {@code app/jcl/OPENFIL.jcl:L1} spells the job name {@code //OEPNFIL JOB}, a
 *       transposition in the frozen corpus that is cited as it stands.</li>
 *   <li><b>Low</b> - {@code app/jcl/COMBTRAN.jcl} is <b>52</b> lines. A sibling brief states 53; 52 is the
 *       measured figure.</li>
 *   <li><b>Low</b> - the object-store namespace is {@code carddemo.aws.s3.*} with
 *       {@code batch-input-bucket}, {@code batch-output-bucket} and {@code statements-bucket}, fed by
 *       {@code CARDDEMO_S3_BATCH_INPUT_BUCKET}, {@code CARDDEMO_S3_BATCH_OUTPUT_BUCKET} and
 *       {@code CARDDEMO_S3_STATEMENTS_BUCKET}. A brief citing {@code carddemo.s3.*}, or the shorter
 *       environment variable names, is wrong on both counts. This class binds none of them, so the
 *       divergence is a note for whoever does; it is registered as {@code CIT-PROPERTY-NAMES} under
 *       {@code DL-CR-09} in {@code DECISION_LOG.md}.</li>
 *   <li><b>Low</b> - the guard in {@code src/test/java/com/cardemo/unit/model/EvidenceHonestyTest.java} that
 *       requires every reference to this class to be qualified as planned becomes conservative once the class
 *       exists. The remedy is the one that guard already documents for two configuration classes: move the
 *       entry into its list of formerly unauthored classes. Nothing in this file writes the fully qualified
 *       form that the guard matches, so the suite stays green either way.</li>
 * </ul>
 *
 * <h2>Not available</h2>
 *
 * <ul>
 *   <li><b>A container runtime is a prerequisite</b> for the boundary-parity, named-fixture and
 *       integration sign-off gates. Where one is absent those gates cannot be executed and no result may be
 *       asserted for them; what is needed is a reachable daemon socket.</li>
 *   <li><b>No service-level objective exists anywhere in the corpus.</b> The legacy system publishes no
 *       throughput figure and no latency target, so none is invented: the performance gate records a
 *       <em>measured baseline</em> and never a threshold. A fabricated pass is worse than a recorded
 *       absence.</li>
 *   <li><b>The object-store record-framing convention is undetermined</b> - whether a consumer expects
 *       newline-delimited records or a single fixed-width blob is not stated anywhere in the corpus. The
 *       stages own the framing they emit; this class asserts nothing about it. What is needed is a stated
 *       consumer contract.</li>
 *   <li><b>The generation-base-to-prefix mapping for the posted-transaction object is undetermined.</b>
 *       {@code app/jcl/POSTTRAN.jcl} names {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS} at {@code :L29}, a
 *       cluster rather than a generation base, so no {@code (+1)} exists for it to map. What is needed is a
 *       decision on whether posted transactions are also archived under a generation prefix.</li>
 *   <li><b>Seeding a child job's step execution context from an outer job is not a supported framework
 *       operation.</b> That is why the generation handoff is enforced by verification rather than by
 *       injection. What is needed to make it an injection is for the combined-transaction reader to accept
 *       the resolved key as a job parameter; until then a mismatch is detected and reported rather than
 *       prevented, which is strictly better than a silent latest-wins.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><b>{@link ValidationException} before anything launches</b> - a date parameter has the wrong shape.
 *       Check that {@value #PARM_DATE_JOB_PARAMETER} is ten digits ending in two zeros and that
 *       {@value #START_DATE_JOB_PARAMETER} and {@value #END_DATE_JOB_PARAMETER} are {@code yyyy-MM-dd}. The
 *       message names the offending parameter.</li>
 *   <li><b>{@link ValidationException} naming an already-complete instance</b> - the pipeline was launched
 *       twice with identical parameters. That refusal is deliberate: no incrementer is declared, because
 *       re-posting an unchanged daily transaction file must surface as a collision rather than be smoothed
 *       over. Vary a parameter, or investigate why the same run is being repeated.</li>
 *   <li><b>{@link FatalProcessingException} carrying abend code {@code 0999} and the culprit
 *       {@value #ABEND_CULPRIT}</b> - either the pipeline or a child was already running, a restart was
 *       refused, the launcher failed for some other reason, or the generation handoff did not verify. The
 *       message names which. The code is the four-digit rendering of
 *       {@link FatalProcessingException#BATCH_ABEND_CODE}, which is {@code MOVE 999 TO ABCODE} at
 *       {@code app/cbl/CBTRN02C.cbl:L710}, never the CICS online value.</li>
 *   <li><b>A stage ends {@value #EXIT_CODE_COMPLETED_WITH_REJECTS} and the pipeline keeps going</b> - that
 *       is correct, not a defect. See the return-code contract above.</li>
 *   <li><b>The pipeline ends failed with one split branch complete</b> - the branches are independent by
 *       design, so one may succeed while the other fails. Both branch outcomes are recorded in the pipeline
 *       execution context and the failing branch's cause is carried on the pipeline execution.</li>
 *   <li><b>Log events from a split branch carry empty identifiers</b> - the branch decoration was bypassed.
 *       The split must be given this class's own executor and no other.</li>
 * </ul>
 */
@Configuration(BatchPipelineOrchestrator.CONFIGURATION_BEAN_NAME)
public class BatchPipelineOrchestrator {

    /** This class's own logger. The only permitted static mutable-looking member, and it is immutable. */
    private static final Logger LOG = LoggerFactory.getLogger(BatchPipelineOrchestrator.class);

    /**
     * Bean name of this configuration class, spelled explicitly so it cannot collide with a sibling.
     *
     * <p>Package private rather than private because the integration tier asserts the bean exists.
     */
    static final String CONFIGURATION_BEAN_NAME = "batchPipelineOrchestratorConfiguration";

    /** Bean name of the pipeline job. Uniquely prefixed; no sibling and no configuration class uses it. */
    static final String JOB_BEAN_NAME = "batchPipelineJob";

    /** Bean name of the pipeline flow, which composes the five stages and the split. */
    static final String FLOW_BEAN_NAME = "batchPipelineFlow";

    /** Bean name of the stage-4 split flow, the parallel statement-and-report branch pair. */
    static final String SPLIT_FLOW_BEAN_NAME = "batchPipelineStatementReportSplitFlow";

    /** Bean name of the stage-1 launcher step, {@code app/jcl/POSTTRAN.jcl:L23}. */
    static final String POSTTRAN_STEP_BEAN_NAME = "batchPipelinePostTranStep";

    /** Bean name of the stage-2 launcher step, {@code app/jcl/INTCALC.jcl:L22}. */
    static final String INTCALC_STEP_BEAN_NAME = "batchPipelineIntCalcStep";

    /** Bean name of the stage-3 launcher step, {@code app/jcl/COMBTRAN.jcl:L22} and {@code :L41}. */
    static final String COMBTRAN_STEP_BEAN_NAME = "batchPipelineCombTranStep";

    /** Bean name of the stage-4 statement branch's launcher step, {@code app/jcl/CREASTMT.JCL}. */
    static final String CREASTMT_STEP_BEAN_NAME = "batchPipelineCreaStmtStep";

    /** Bean name of the stage-4 report branch's launcher step, {@code app/proc/TRANREPT.prc}. */
    static final String TRANREPT_STEP_BEAN_NAME = "batchPipelineTranReptStep";

    /** Flow name of the statement branch inside the split. Not a bean: the split flow owns it. */
    private static final String CREASTMT_BRANCH_FLOW_NAME = "batchPipelineCreaStmtBranchFlow";

    /** Flow name of the report branch inside the split. Not a bean: the split flow owns it. */
    private static final String TRANREPT_BRANCH_FLOW_NAME = "batchPipelineTranReptBranchFlow";

    // The five sibling job bean names. Each is taken from the file that publishes it, never guessed.
    // Two siblings declare their own constant privately, so the literal is repeated here with the
    // declaring line cited; the other three expose a constant and it is referenced instead.

    /** Stage 1's job bean, published by {@link DailyTransactionPostingJob} as a public constant. */
    private static final String POSTTRAN_JOB_BEAN_NAME = DailyTransactionPostingJob.JOB_BEAN_NAME;

    /**
     * Stage 2's job bean.
     *
     * <p>{@link InterestCalculationJob} declares this name privately, so the literal is repeated rather than
     * referenced. It is the value of that file's own {@code JOB_BEAN_NAME} field and the two must agree.
     */
    private static final String INTCALC_JOB_BEAN_NAME = "interestCalculationJob";

    /** Stage 3's job bean, published by {@link CombineTransactionsJob} as a package-private constant. */
    private static final String COMBTRAN_JOB_BEAN_NAME = CombineTransactionsJob.JOB_BEAN_NAME;

    /** Stage 4's statement-branch job bean, published by {@link StatementGenerationJob}. */
    private static final String CREASTMT_JOB_BEAN_NAME = StatementGenerationJob.JOB_BEAN_NAME;

    /**
     * Stage 4's report-branch job bean.
     *
     * <p>{@link TransactionReportJob} declares this name privately, so the literal is repeated for the same
     * reason as {@link #INTCALC_JOB_BEAN_NAME}.
     */
    private static final String TRANREPT_JOB_BEAN_NAME = "transactionReportJob";

    // Job parameters. Three, and every stage receives all three unchanged. Two of the names are owned
    // elsewhere and are mirrored here because this package may not import the processor package.

    /**
     * The interest date parameter, {@value}.
     *
     * <p>The same name {@link InterestCalculationJob} binds. Its shape is ten digits with no separator, from
     * {@code app/jcl/INTCALC.jcl:L22} {@code PARM='2022071800'}.
     */
    static final String PARM_DATE_JOB_PARAMETER = "parmDate";

    /**
     * The report start date, {@value}.
     *
     * <p>Owned by {@code com.cardemo.batch.processors.TransactionReportProcessor}, which publishes it as
     * {@code START_DATE_JOB_PARAMETER}. Its shape is {@code yyyy-MM-dd}, from
     * {@code app/proc/TRANREPT.prc:L41} {@code PARM-START-DATE,C'2022-01-01'}.
     */
    static final String START_DATE_JOB_PARAMETER = "startDate";

    /**
     * The report end date, {@value}.
     *
     * <p>Owned by the same processor as {@link #START_DATE_JOB_PARAMETER} and documented there. From
     * {@code app/proc/TRANREPT.prc:L42} {@code PARM-END-DATE,C'2022-07-06'}.
     */
    static final String END_DATE_JOB_PARAMETER = "endDate";

    /** Exact character count of {@code PARM-DATE}, from {@code app/jcl/INTCALC.jcl:L22}. */
    private static final int PARM_DATE_LENGTH = 10;

    /** Number of leading characters of {@link #PARM_DATE_JOB_PARAMETER} that form the {@code yyyyMMdd}. */
    private static final int PARM_DATE_DATE_DIGITS = 8;

    /** The two trailing characters of {@code PARM='2022071800'}. Part of the value, not decoration. */
    private static final String PARM_DATE_TRAILER = "00";

    /** Exact character count of a report date, from {@code app/proc/TRANREPT.prc:L41}. */
    private static final int REPORT_DATE_LENGTH = 10;

    /** Index of the first dash in {@code yyyy-MM-dd}. */
    private static final int REPORT_DATE_FIRST_DASH = 4;

    /** Index of the second dash in {@code yyyy-MM-dd}. */
    private static final int REPORT_DATE_SECOND_DASH = 7;

    /** Lowest year either date shape may express, which rejects an all-zero value. */
    private static final int MIN_YEAR = 1;

    /** Highest year either date shape may express, four digits being the whole width available. */
    private static final int MAX_YEAR = 9999;

    /** Lowest month number. */
    private static final int MIN_MONTH = 1;

    /** Highest month number. */
    private static final int MAX_MONTH = 12;

    /** One-based ordinal of February, the only month whose length varies. */
    private static final int FEBRUARY = 2;

    /** Days in February in a leap year. */
    private static final int FEBRUARY_LEAP_LENGTH = 29;

    // Flow vocabulary. The four gate outcomes first, then the exit codes the pipeline publishes, then
    // the four return codes those outcomes descend from.

    /** Gate outcome for return code 0: the stage completed and the pipeline proceeds. */
    private static final String GATE_PROCEED = "PROCEED";

    /**
     * Gate outcome for return code 4: the stage completed with rejects and the pipeline <b>still</b>
     * proceeds.
     *
     * <p>A separate outcome rather than a synonym of {@link #GATE_PROCEED}, so that the flow carries two
     * visible arrows into the next stage and "RC 4 is not a failure" is a property of the topology instead of
     * a comparison hidden inside a decider.
     */
    private static final String GATE_PROCEED_WITH_REJECTS = "PROCEED WITH REJECTS";

    /** Gate outcome for return code 8: the dependent chain halts and the pipeline ends failed. */
    private static final String GATE_HALT = "HALT";

    /** Gate outcome for return code 12: the pipeline halts and reports an abend. */
    private static final String GATE_ABEND = "ABEND";

    /** The wildcard transition pattern, matching any status a state can produce. */
    private static final String EXIT_CODE_ANY = "*";

    /** The framework's own completed exit code, reused so the pipeline publishes the ordinary value. */
    private static final String EXIT_CODE_COMPLETED = ExitStatus.COMPLETED.getExitCode();

    /**
     * The completed-with-rejects exit code, {@value}.
     *
     * <p>Spelled identically to the value every sibling job publishes, so a caller reading either sees one
     * vocabulary rather than two.
     */
    private static final String EXIT_CODE_COMPLETED_WITH_REJECTS = "COMPLETED WITH REJECTS";

    /** The framework's own failed exit code. */
    private static final String EXIT_CODE_FAILED = ExitStatus.FAILED.getExitCode();

    /** The abend exit code, {@value}, matching the value the sibling jobs publish for the same outcome. */
    private static final String EXIT_CODE_ABEND = "ABEND";

    /** Return code 0. The fall-through of {@code app/cbl/CBTRN02C.cbl:L229}. */
    private static final int RETURN_CODE_COMPLETED = 0;

    /** Return code 4. {@code MOVE 4 TO RETURN-CODE} at {@code app/cbl/CBTRN02C.cbl:L230}. */
    private static final int RETURN_CODE_COMPLETED_WITH_REJECTS = 4;

    /** Return code 8. The conventional job-control failure code; no COBOL literal exists for it. */
    private static final int RETURN_CODE_FAILED = 8;

    /**
     * Attempts allowed for one stage launch, {@value}, counting the first.
     *
     * <p>This bounds the retry described on {@link #launchStage(Job, String, JobParameters)} and exists only
     * because {@code spring.batch.jdbc.isolation-level-for-create} is {@code SERIALIZABLE}. Under that
     * isolation level PostgreSQL is entitled to abort one of two concurrently-created job executions with a
     * serialization failure, and the two branches of the {@code CREASTMT || TRANREPT} split create theirs at
     * the same instant by design. Five attempts is not a guess: with two contending writers a serialization
     * failure retried under a fresh snapshot succeeds on the next attempt in the overwhelming majority of
     * cases, and four spare attempts leave headroom without letting a genuinely stuck launch spin.
     *
     * <p>It is deliberately small. A large bound would turn a real, persistent contention problem into a slow
     * one instead of surfacing it.
     */
    private static final int MAX_STAGE_LAUNCH_ATTEMPTS = 5;

    /**
     * Base backoff between stage-launch attempts in milliseconds, {@value}.
     *
     * <p>Multiplied by the number of attempts already made, so the waits are 50 ms, 100 ms, 150 ms and
     * 200 ms - 500 ms in total across the four retries. Any backoff at all is what matters: retrying
     * immediately would very likely re-collide with the same concurrent transaction, because the competing
     * branch is created within microseconds of this one.
     */
    private static final long LAUNCH_RETRY_BASE_BACKOFF_MILLIS = 50L;

    /** SQLSTATE {@value} - {@code serialization_failure}, the {@code SERIALIZABLE} conflict abort. */
    private static final String SQL_STATE_SERIALIZATION_FAILURE = "40001";

    /** SQLSTATE {@value} - {@code deadlock_detected}, the other transient class-40 abort. */
    private static final String SQL_STATE_DEADLOCK_DETECTED = "40P01";

    /** Cause-chain walk bound, {@value}, so a self-referential cause cannot loop forever. */
    private static final int CAUSE_CHAIN_LIMIT = 32;

    /**
     * Return code 12.
     *
     * <p>Taken from {@link FatalProcessingException#BATCH_RETURN_CODE} rather than written as a literal,
     * because there is no {@code MOVE 12 TO RETURN-CODE} anywhere in the corpus to cite: twelve is the
     * conventional language-environment consequence of the {@code CALL 'CEE3ABD'} at
     * {@code app/cbl/CBTRN02C.cbl:L711}.
     */
    private static final int RETURN_CODE_ABEND = FatalProcessingException.BATCH_RETURN_CODE;

    /**
     * The abend code, four characters wide as {@code ABEND-CODE PIC X(4)} of
     * {@code app/cpy/CSMSG02Y.cpy} requires, rendered from
     * {@link FatalProcessingException#BATCH_ABEND_CODE} so the two cannot drift.
     *
     * <p>{@code MOVE 999 TO ABCODE} at {@code app/cbl/CBTRN02C.cbl:L710} is the source of the number. The
     * CICS online value is a different number and is never used here.
     */
    private static final String ABEND_CODE =
            String.format(Locale.ROOT, "%04d", Integer.valueOf(FatalProcessingException.BATCH_ABEND_CODE));

    /**
     * The abend culprit, {@value}, exactly eight characters as {@code ABEND-CULPRIT PIC X(8)} allows.
     *
     * <p>It names the stream rather than a program because <b>the stream has no COBOL program</b>: this class
     * replaces the job deck, not a compilation unit.
     */
    private static final String ABEND_CULPRIT = "PIPELINE";

    // Diagnostic context. FINDING M-02, severity Medium: four private literals stood here, re-spelling the
    // key names that com.cardemo.observability.CorrelationIdFilter already publishes as public constants,
    // under a comment asserting that "this package may not import" that class. The assertion was false -
    // four sibling job classes in this very package import it - and the duplication had already cost what
    // duplication costs: the two classes carrying their own literals also carried their own lifecycle, and
    // both lifecycles diverged from the other four (findings H-02 and H-03). The constants and the
    // park-and-restore contract now have exactly one definition, in the class that owns the key names.

    /**
     * Prefix of the pipeline correlation identifier, {@value}.
     *
     * <p>Followed by the pipeline execution identifier, so the value is derived rather than random and can be
     * recomputed to decide whether an entry is this listener's to remove.
     */
    private static final String CORRELATION_ID_PREFIX = "pipeline-";

    /** Thread-name prefix for the two stage-4 branch threads, so a log line names the branch it came from. */
    private static final String SPLIT_THREAD_NAME_PREFIX = "carddemo-pipeline-split-";

    /** Branch count of the stage-4 split, and therefore the split executor's concurrency limit. */
    private static final int SPLIT_BRANCH_COUNT = 2;

    // Pipeline execution-context keys. Every one is prefixed so it cannot collide with a stage's own
    // entries, and every one is read by either a decider or the integration tier.

    /** Common prefix of every entry this class publishes. */
    private static final String CONTEXT_PREFIX = "carddemo.pipeline.";

    /** Suffix of a per-stage return-code entry. */
    private static final String RETURN_CODE_SUFFIX = ".returnCode";

    /** Suffix of a per-stage exit-code entry. */
    private static final String EXIT_CODE_SUFFIX = ".exitCode";

    /** Suffix of a per-stage child execution-identifier entry. */
    private static final String EXECUTION_ID_SUFFIX = ".executionId";

    /**
     * The running aggregate return code, {@value}.
     *
     * <p>The highest code any completed stage has reported. Every gate reads this rather than a single
     * stage's code, so a stage that rejected records is still remembered as such once a later stage has
     * completed cleanly.
     */
    static final String PIPELINE_RETURN_CODE_CONTEXT_ENTRY = CONTEXT_PREFIX + "returnCode";

    /** The aggregate outcome name, {@value}: one of the four exit codes this class publishes. */
    static final String PIPELINE_OUTCOME_CONTEXT_ENTRY = CONTEXT_PREFIX + "outcome";

    /** Count of {@code SYSTRAN} generation keys stage 2 published, {@value}. */
    static final String SYSTRAN_KEY_COUNT_CONTEXT_ENTRY = CONTEXT_PREFIX + "systran.generation.keys.count";

    /**
     * Prefix of the indexed {@code SYSTRAN} generation keys, {@value}.
     *
     * <p>The entry for index {@code n} is this prefix followed by {@code n}, for {@code n} from zero up to
     * one less than {@link #SYSTRAN_KEY_COUNT_CONTEXT_ENTRY}.
     */
    static final String SYSTRAN_KEY_INDEX_CONTEXT_PREFIX = CONTEXT_PREFIX + "systran.generation.keys.";

    /**
     * The exact {@code SYSTRAN} generation stage 3 must read, {@value}.
     *
     * <p>The generation prefix stage 2 published for the generation <em>it</em> created, which is the
     * object-store equivalent of the catalogue entry {@code app/jcl/INTCALC.jcl:L37-L41} writes at close and
     * {@code app/jcl/COMBTRAN.jcl:L25-L26} then resolves as {@code SYSTRAN(0)}. It is a generation and not a
     * single object key, because stage 2 emits one object per chunk under one generation, so pinning one key
     * would drop the rest.
     *
     * <p>This value is handed to stage 3 <strong>before it launches</strong>, as the job parameter
     * {@value com.cardemo.batch.readers.CombinedTransactionReader#SYSTRAN_GENERATION_JOB_PARAMETER}, which is
     * what makes the handoff a pre-launch contract rather than an after-the-fact check. It carries
     * {@value #HANDOFF_ABSENT} only when stage 2 published no generation at all.
     */
    static final String SYSTRAN_GENERATION_CONTEXT_ENTRY = CONTEXT_PREFIX + "systran.generation";

    /** Outcome of the generation handoff check, {@value}. */
    static final String SYSTRAN_HANDOFF_CONTEXT_ENTRY = CONTEXT_PREFIX + "systran.handoff";

    /** The key stage 3 actually resolved, {@value}, recorded whether or not it matched. */
    static final String SYSTRAN_RESOLVED_CONTEXT_ENTRY = CONTEXT_PREFIX + "systran.resolved";

    /** Handoff outcome when stage 3 read exactly the key stage 2 created, {@value}. */
    static final String HANDOFF_VERIFIED = "VERIFIED";

    /** Handoff outcome when stage 2 published no generation at all, {@value}. A legitimate empty run. */
    static final String HANDOFF_ABSENT = "ABSENT";

    /**
     * Handoff outcome when stage 3 recorded no resolved key, {@value}.
     *
     * <p>The combined-transaction reader records a key only when it reads from the object store; on the
     * relational substrate there is no generation to resolve and nothing to compare.
     */
    static final String HANDOFF_NOT_APPLICABLE = "NOT APPLICABLE";

    /** Handoff outcome when stage 3 read a different key from the one stage 2 created, {@value}. */
    static final String HANDOFF_MISMATCH = "MISMATCH";

    /**
     * The step execution-context key the combined-transaction reader records its resolved
     * {@code SYSTRAN(0)} key under, {@value}.
     *
     * <p>{@code com.cardemo.batch.readers.CombinedTransactionReader} declares this name privately, so the
     * literal is mirrored here. It is the only way to observe which generation stage 3 actually read.
     */
    private static final String READER_SYSTRAN_OBJECT_KEY_ENTRY = "carddemo.gdg.systran.objectKey";

    /** The marker the reader records when it carried an absent generation forward, {@value}. */
    private static final String READER_ABSENT_GENERATION_MARKER = "<absent>";

    /**
     * The property selecting stage 3's reader's input substrate, {@value}.
     *
     * <p>{@code com.cardemo.batch.readers.CombinedTransactionReader} declares this name privately, so the
     * literal is mirrored here, and {@link #requireObjectStorageSubstrate()} states what this pipeline
     * requires of it.
     */
    private static final String READER_SOURCE_PROPERTY =
            "carddemo.batch.combined-transaction-reader.source";

    /** The only substrate value stage 3 may run on inside this pipeline, {@value}. */
    private static final String READER_SOURCE_OBJECT_STORAGE = "object-storage";

    // Stage identity. One constant per JCL artefact of the stream, each citing the line it replaces, so
    // the mapping stays provable for the stream as a whole rather than only for the five COBOL programs.

    /** Stage 1's display name, {@value}, and the {@code app/jcl/POSTTRAN.jcl} member it replaces. */
    private static final String STAGE_POSTTRAN = "POSTTRAN";

    /** Stage 2's display name, {@value}, and the {@code app/jcl/INTCALC.jcl} member it replaces. */
    private static final String STAGE_INTCALC = "INTCALC";

    /** Stage 3's display name, {@value}, and the {@code app/jcl/COMBTRAN.jcl} member it replaces. */
    private static final String STAGE_COMBTRAN = "COMBTRAN";

    /** Stage 4's statement branch, {@value}, and the {@code app/jcl/CREASTMT.JCL} member it replaces. */
    private static final String STAGE_CREASTMT = "CREASTMT";

    /** Stage 4's report branch, {@value}, driven by {@code app/proc/TRANREPT.prc}. */
    private static final String STAGE_TRANREPT = "TRANREPT";

    /** Execution-context infix for stage 1. Lower case so the key set is uniform. */
    private static final String INFIX_POSTTRAN = "posttran";

    /** Execution-context infix for stage 2. */
    private static final String INFIX_INTCALC = "intcalc";

    /** Execution-context infix for stage 3. */
    private static final String INFIX_COMBTRAN = "combtran";

    /** Execution-context infix for stage 4's statement branch. */
    private static final String INFIX_CREASTMT = "creastmt";

    /** Execution-context infix for stage 4's report branch. */
    private static final String INFIX_TRANREPT = "tranrept";

    /**
     * All five stage infixes, in stream order.
     *
     * <p>The set every gate scans when it reconstructs what this instance has already done, which is what makes
     * a halt survive a restart even though the launcher step that recorded it is skipped on the second
     * execution. Declared once so a sixth stage cannot be added to the flow and forgotten here: a stage whose
     * infix is absent from this list is a stage whose halt a gate cannot see.
     *
     * <p>Immutable, and the declaration order is the stream order rather than alphabetical, so reading it
     * alongside {@link #batchPipelineFlow(Step, Step, Step, Flow)} shows the same sequence twice.
     */
    private static final List<String> STAGE_INFIXES = List.of(
            INFIX_POSTTRAN, INFIX_INTCALC, INFIX_COMBTRAN, INFIX_CREASTMT, INFIX_TRANREPT);

    /** Locator of the step stage 1 replaces, {@value}. */
    private static final String LOCATOR_POSTTRAN = "app/jcl/POSTTRAN.jcl:L23";

    /** Locator of the step stage 2 replaces, {@value}. */
    private static final String LOCATOR_INTCALC = "app/jcl/INTCALC.jcl:L22";

    /** Locators of the two steps stage 3 replaces, {@value}. */
    private static final String LOCATOR_COMBTRAN = "app/jcl/COMBTRAN.jcl:L22 and :L41";

    /** Locators of the five steps stage 4's statement branch replaces, {@value}. */
    private static final String LOCATOR_CREASTMT =
            "app/jcl/CREASTMT.JCL:L22, :L44, :L56, :L66 and :L79";

    /** Locators of the three steps stage 4's report branch replaces, {@value}. */
    private static final String LOCATOR_TRANREPT = "app/proc/TRANREPT.prc:L21, :L35 and :L57";

    /**
     * The one member of the stream that installs the CICS resource definitions, {@value}.
     *
     * <p>Superseded by {@code com.cardemo.config.SecurityConfig} and given no stage, because there is no
     * batch work to do. Held as a constant so the mapping is complete by inspection.
     */
    private static final String NO_ANALOGUE_CSD_INSTALL = "app/jcl/CBADMCDJ.jcl:L27";

    /**
     * The two members that opened and closed the online files, {@value}.
     *
     * <p>Superseded by {@code com.cardemo.observability.HealthIndicators} and given no stage.
     */
    private static final String NO_ANALOGUE_FILE_AVAILABILITY =
            "app/jcl/OPENFIL.jcl:L26-L30 and app/jcl/CLOSEFIL.jcl:L26-L30";

    /**
     * The orphan cluster definition nothing opens, {@value}.
     *
     * <p>A finding only; no entity, repository or stage is derived from it.
     */
    private static final String NO_ANALOGUE_ORPHAN_CLUSTER = "app/jcl/DEFCUST.jcl:L35-L38";

    /**
     * The transient data queue that bridged the online side to this stream, {@value}.
     *
     * <p>The queue's 80-byte fixed record is the parameter contract the modern queue message reproduces. The
     * listener that consumes it is not declared here.
     */
    private static final String TDQ_DEFINITION = "app/csd/CARDDEMO.CSD:L499-L505";

    /** Default registered job name when {@code carddemo.batch.jobs.pipeline.name} is not configured, {@value}. */
    private static final String DEFAULT_JOB_NAME = "CARDDEMO-PIPELINE";


    // Injected collaborators. Constructor injection only, every field final, and not one of them a bean
    // this class declares: the five stages come from their own files and the four infrastructure beans
    // from the container.

    /** Stage 1's job, {@code app/jcl/POSTTRAN.jcl}. Injected by bean name, never declared here. */
    private final Job dailyTransactionPostingJob;

    /** Stage 2's job, {@code app/jcl/INTCALC.jcl}. */
    private final Job interestCalculationJob;

    /** Stage 3's job, {@code app/jcl/COMBTRAN.jcl}. */
    private final Job combineTransactionsJob;

    /** Stage 4's statement-branch job, {@code app/jcl/CREASTMT.JCL}. */
    private final Job statementGenerationJob;

    /** Stage 4's report-branch job, {@code app/proc/TRANREPT.prc}. */
    private final Job transactionReportJob;

    /** The container's job repository, used to build steps. Injected: this class declares no repository. */
    private final JobRepository jobRepository;

    /**
     * The container's job launcher, used to launch each stage's job.
     *
     * <p>The launcher is what gives this class the child {@link JobExecution}, and therefore the child's exit
     * status, its failure exceptions, its execution context and its step contexts. A nested job step would
     * have launched the same jobs but surfaced only a cause-less wrapper and no context at all, which would
     * defeat both the root-cause requirement and the generation handoff.
     */
    private final JobLauncher jobLauncher;

    /**
     * The container's transaction manager, required to build a tasklet step.
     *
     * <p>Each launcher step carries {@link TransactionDefinition#PROPAGATION_NOT_SUPPORTED}, so no
     * transaction is open while a child job runs. That is not a preference: a tasklet's default
     * {@code PROPAGATION_REQUIRED} would make every one of the child's own required-propagation steps
     * <em>join</em> the outer transaction, turning each of the child's commits into a no-op and holding one
     * pooled connection for the whole job.
     */
    private final PlatformTransactionManager transactionManager;

    /** The registered job name, from {@code carddemo.batch.jobs.pipeline.name}. */
    private final String jobName;

    /**
     * The configured input substrate of stage 3's reader, as bound from
     * {@value #READER_SOURCE_PROPERTY}. See {@link #requireObjectStorageSubstrate()}.
     */
    private final String readerSource;

    /**
     * Creates the orchestrator.
     *
     * @param dailyTransactionPostingJob stage 1's job, {@code app/jcl/POSTTRAN.jcl:L23}
     * @param interestCalculationJob stage 2's job, {@code app/jcl/INTCALC.jcl:L22}
     * @param combineTransactionsJob stage 3's job, {@code app/jcl/COMBTRAN.jcl:L22} and {@code :L41}
     * @param statementGenerationJob stage 4's statement branch, {@code app/jcl/CREASTMT.JCL}
     * @param transactionReportJob stage 4's report branch, {@code app/proc/TRANREPT.prc}
     * @param jobRepository the container's job repository
     * @param jobLauncher the container's job launcher
     * @param transactionManager the container's transaction manager
     * @param configuredJobName the registered job name, from {@code carddemo.batch.jobs.pipeline.name},
     *     defaulting to {@value #DEFAULT_JOB_NAME}
     * @param readerSource the configured input substrate of stage 3's reader, from
     *     {@value #READER_SOURCE_PROPERTY}; stage 3 is refused on any value other than
     *     {@value #READER_SOURCE_OBJECT_STORAGE}, per {@link #requireObjectStorageSubstrate()}
     * @throws IllegalArgumentException if any collaborator is {@code null} or the job name is blank
     */
    public BatchPipelineOrchestrator(
            @Qualifier(POSTTRAN_JOB_BEAN_NAME) final Job dailyTransactionPostingJob,
            @Qualifier(INTCALC_JOB_BEAN_NAME) final Job interestCalculationJob,
            @Qualifier(COMBTRAN_JOB_BEAN_NAME) final Job combineTransactionsJob,
            @Qualifier(CREASTMT_JOB_BEAN_NAME) final Job statementGenerationJob,
            @Qualifier(TRANREPT_JOB_BEAN_NAME) final Job transactionReportJob,
            final JobRepository jobRepository,
            final JobLauncher jobLauncher,
            @Qualifier("transactionManager") final PlatformTransactionManager transactionManager,
            @Value("${carddemo.batch.jobs.pipeline.name:" + DEFAULT_JOB_NAME + "}")
                    final String configuredJobName,
            @Value("${" + READER_SOURCE_PROPERTY + ":repository}") final String readerSource) {

        this.dailyTransactionPostingJob = requireCollaborator(dailyTransactionPostingJob, STAGE_POSTTRAN);
        this.interestCalculationJob = requireCollaborator(interestCalculationJob, STAGE_INTCALC);
        this.combineTransactionsJob = requireCollaborator(combineTransactionsJob, STAGE_COMBTRAN);
        this.statementGenerationJob = requireCollaborator(statementGenerationJob, STAGE_CREASTMT);
        this.transactionReportJob = requireCollaborator(transactionReportJob, STAGE_TRANREPT);
        this.jobRepository = requireCollaborator(jobRepository, "jobRepository");
        this.jobLauncher = requireCollaborator(jobLauncher, "jobLauncher");
        this.transactionManager = requireCollaborator(transactionManager, "transactionManager");
        this.jobName = requireConfiguredText(configuredJobName, "carddemo.batch.jobs.pipeline.name");
        // Not validated into an enum here: the reader owns the property's grammar, and this class only needs
        // to know whether the value is the one its stage 3 requires. An unrecognised value is the reader's
        // rejection to make, and it makes it at construction.
        this.readerSource = readerSource == null ? "" : readerSource.strip();
    }

    // The five launcher steps. One per JCL member of the stream, each citing the step it replaces. None
    // declares reader, processor or writer logic: the stage it launches owns all of that.

    /**
     * Stage 1: {@code app/jcl/POSTTRAN.jcl:L23} {@code //STEP15 EXEC PGM=CBTRN02C}.
     *
     * <p>A single program step with no {@code COND=} anywhere in the member, so nothing gates it and nothing
     * here pretends otherwise. The step launches {@link DailyTransactionPostingJob}, which folds
     * {@code app/cbl/CBTRN01C.cbl} in as its read-only pre-flight.
     *
     * @return the launcher step, never {@code null}
     */
    @Bean(POSTTRAN_STEP_BEAN_NAME)
    public Step batchPipelinePostTranStep() {
        return launcherStep(POSTTRAN_STEP_BEAN_NAME, this::runPostTranStage);
    }

    /**
     * Stage 2: {@code app/jcl/INTCALC.jcl:L22} {@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'}.
     *
     * <p>Also ungated, and also a single program step. Its output is the new generation of
     * {@code AWS.M2.CARDDEMO.SYSTRAN} allocated at {@code app/jcl/INTCALC.jcl:L37-L41}, which is what makes
     * stage 3 depend on it.
     *
     * @return the launcher step, never {@code null}
     */
    @Bean(INTCALC_STEP_BEAN_NAME)
    public Step batchPipelineIntCalcStep() {
        return launcherStep(INTCALC_STEP_BEAN_NAME, this::runIntCalcStage);
    }

    /**
     * Stage 3: {@code app/jcl/COMBTRAN.jcl:L22} {@code //STEP05R  EXEC PGM=SORT} then {@code :L41}
     * {@code //STEP10 EXEC PGM=IDCAMS}.
     *
     * <p><b>No COBOL program exists for this job</b>, so the JCL member is the source of truth. The sort
     * reads a concatenated {@code SORTIN} of {@code TRANSACT.BKUP(0)} at {@code :L23-L24} and
     * {@code SYSTRAN(0)} at {@code :L25-L26}; the load step runs <b>ungated</b>, which is the clearest
     * evidence in the corpus that no inter-step gating may be invented.
     *
     * <p><b>Stage 3 archives and empties the master itself, and does not depend on stage 4's backup.</b>
     * Stating it plainly here because these facts sit adjacent to the report branch's {@code STEP01R}
     * documentation below, and reading them together invites the conclusion that stage 3 depends on stage 4's
     * output. It does not. {@code app/jcl/TRANBKP.jcl} is the mainframe's producer for the combine's first
     * leg, and it runs <b>inside</b> this stage's job as its archive-and-reset step, instructed by
     * {@link CombineTransactionsJob#JOB_PARAMETER_ARCHIVE_MASTER} which {@link #stageParameters} sets. That
     * step copies the master to {@code TRANSACT.BKUP(+1)} and then empties it, so the sort's first leg is this
     * run's own archive and {@code :L48} loads into an empty target - which is what makes the pipeline
     * repeatable (finding C-06).
     *
     * <p><b>An absent required current generation is still a failure, not an empty read.</b>
     * {@code app/jcl/COMBTRAN.jcl:L23-L26} carries {@code DISP=SHR} on <em>both</em> legs, and an uncatalogued
     * {@code DISP=SHR} dataset fails <b>allocation</b>, before {@code SORT} is given control; the absence of a
     * {@code COND=} parameter on {@code :L22} or {@code :L41} says nothing about that, because {@code COND=}
     * gates step <em>execution</em> and never allocation. {@code CombinedTransactionReader} therefore refuses
     * an absent required current generation rather than reporting a successful empty leg (finding F-005).
     * Inside this pipeline the archive step above is what guarantees the first leg exists, so the two
     * guarantees are complementary: one creates the generation, the other refuses to invent one.
     *
     * <p>A generation that exists and holds nothing is a different case and is legitimately empty: it is the
     * object-store counterpart of {@code DISP=(NEW,CATLG,DELETE)} cataloguing a dataset to which nothing was
     * written. That is read as zero records rather than refused.
     *
     * <p><b>This stage requires the object-storage input substrate, and it now enforces that rather than
     * documenting it.</b> {@code CombinedTransactionReader} can take its first leg either from an
     * object-storage generation or from the ordered transaction relation, selected by
     * {@value #READER_SOURCE_PROPERTY}. Only {@code object-storage} is correct inside this pipeline, and the
     * reason is arithmetic rather than preference. On the repository substrate the first leg <em>is</em> the
     * live relation, so the combined generation this stage writes is a snapshot of the very table
     * {@code :L48} then loads it into - a measured 262 relation rows plus 50 interest records, every one of
     * the 262 already present - and the load correctly refuses the first repeated identifier with
     * {@code DuplicateRecordException} and return code 8.
     *
     * <p>{@link #requireObjectStorageSubstrate()} refuses the stage on any other value, and
     * {@code src/main/resources/application.yml} declares the property explicitly. Previously this
     * documentation asked an operator to set it and left the default alone - which meant the pipeline's
     * normal path was the wrong one, since a prerequisite nothing checks is not a prerequisite. That was
     * finding M-01.
     *
     * <p>Verified end to end on a reset database and an empty output bucket: all five stages completed, the
     * aggregate was return code 4 from the posting stage's rejects, and the relation ended with 312 rows of
     * which 50 were the interest transactions.
     *
     * <p>On the mainframe the collision described above cannot arise at all, because
     * {@code app/jcl/TRANBKP.jcl} deletes and redefines the cluster between the backup and the combine,
     * leaving {@code REPRO} an empty target. <b>That member is now reproduced</b> as this stage's job's
     * archive-and-reset step, so the target is emptied here too; requiring the object-storage substrate
     * remains necessary and is a separate guarantee, since it is what makes the first leg a generation rather
     * than the load target itself.
     *
     * @return the launcher step, never {@code null}
     */
    @Bean(COMBTRAN_STEP_BEAN_NAME)
    public Step batchPipelineCombTranStep() {
        return launcherStep(COMBTRAN_STEP_BEAN_NAME, this::runCombTranStage);
    }

    /**
     * Stage 4's statement branch: the five steps of {@code app/jcl/CREASTMT.JCL}.
     *
     * <p>{@code DELDEF01} at {@code :L22} and {@code STEP010} at {@code :L44} are ungated;
     * {@code STEP020} at {@code :L56}, {@code STEP030} at {@code :L66} and {@code STEP040} at {@code :L79}
     * each carry {@code COND=(0,NE)} - the only step gating in the entire batch stream, and it is owned by
     * {@link StatementGenerationJob} rather than reproduced here.
     *
     * @return the launcher step, never {@code null}
     */
    @Bean(CREASTMT_STEP_BEAN_NAME)
    public Step batchPipelineCreaStmtStep() {
        return launcherStep(CREASTMT_STEP_BEAN_NAME, this::runCreaStmtStage);
    }

    /**
     * Stage 4's report branch: the three steps of {@code app/proc/TRANREPT.prc}.
     *
     * <p>{@code STEP01R} at {@code :L21} backs the cluster up to {@code TRANSACT.BKUP(+1)},
     * {@code STEP05R} at {@code :L35} sorts by card number with the inclusive date filter of
     * {@code :L45-L46}, and {@code STEP10R} at {@code :L57} runs the report. The procedure, not
     * {@code app/jcl/TRANREPT.jcl}, is the authority: the standalone member carries {@code STEP05R} twice.
     *
     * <p><b>{@code STEP01R} produces a {@code TRANSACT.BKUP} generation here in stage 4 - downstream of the
     * stage 3 combine that reads that base.</b> This ordering is the source's, not an inversion to repair:
     * the mainframe's producer for the combine's first leg is {@code app/jcl/TRANBKP.jcl}, which runs inside
     * stage 3's own job as its archive-and-reset step. Stage 3 therefore does not and must not depend on
     * <em>this</em> backup; see {@link #batchPipelineCombTranStep()} and {@link CombineTransactionsJob} for
     * the full reasoning. Do not "fix" the pipeline by moving this branch ahead of stage 3 - that would
     * reorder the stream the source defines. Both producers write the same key convention so that the later
     * generation is always the one {@code (0)} resolves to.
     *
     * @return the launcher step, never {@code null}
     */
    @Bean(TRANREPT_STEP_BEAN_NAME)
    public Step batchPipelineTranReptStep() {
        return launcherStep(TRANREPT_STEP_BEAN_NAME, this::runTranReptStage);
    }

    /**
     * Stage 4: the two independent branches, run in parallel.
     *
     * <p>The executor is created here and handed straight to the split. It is deliberately <b>not</b> a bean:
     * publishing one would contribute container infrastructure this class is not permitted to own, and
     * borrowing a shared pool could starve one branch while the other held its only thread.
     *
     * @param statementStep the statement branch's launcher step
     * @param reportStep the report branch's launcher step
     * @return the split flow, never {@code null}
     */
    @Bean(SPLIT_FLOW_BEAN_NAME)
    public Flow batchPipelineStatementReportSplitFlow(
            @Qualifier(CREASTMT_STEP_BEAN_NAME) final Step statementStep,
            @Qualifier(TRANREPT_STEP_BEAN_NAME) final Step reportStep) {

        // Each branch ends on ANY exit code, and the transition is stated rather than left to the builder's
        // default. A branch with no declared transition gets "COMPLETED" to its end state and "*" to its
        // FAILED state, and because every launcher step exits with a gate name - PROCEED, HALT and so on -
        // never the literal COMPLETED, the wildcard would fire on a perfectly good branch and drive the whole
        // split, and then the pipeline's exit status, to FAILED while its batch status stayed COMPLETED.
        //
        // Ending unconditionally is safe, not lax, for two reasons. The gate after the split is the sole
        // arbiter of the outcome and it reads the recorded return codes, which every branch writes before it
        // ends. And that gate independently inspects every step execution, so a branch whose launcher step
        // failed outright still halts the pipeline. Ending here is also what lets both branches record their
        // outcome when only one of them fails, which is the behaviour app/jcl/CREASTMT.JCL and
        // app/proc/TRANREPT.prc have by virtue of being unrelated jobs.
        final Flow statementBranch = new FlowBuilder<SimpleFlow>(CREASTMT_BRANCH_FLOW_NAME)
                .start(statementStep)
                .on(EXIT_CODE_ANY).end(EXIT_CODE_COMPLETED)
                .build();
        final Flow reportBranch = new FlowBuilder<SimpleFlow>(TRANREPT_BRANCH_FLOW_NAME)
                .start(reportStep)
                .on(EXIT_CODE_ANY).end(EXIT_CODE_COMPLETED)
                .build();

        return new FlowBuilder<SimpleFlow>(SPLIT_FLOW_BEAN_NAME)
                .split(branchExecutor())
                .add(statementBranch, reportBranch)
                .build();
    }

    /**
     * The whole stream: four sequential stages and then the split, with a gate after each.
     *
     * <p>Four distinct {@link StageGateDecider} instances are used rather than one shared object, because the
     * flow builder keys a decision state on the decider instance: a single object would collapse all four
     * decision points into one state and the transitions would contend. Each is a plain nested object and
     * <b>none is a bean</b> - a bean would collide with any decider a configuration class declares and with
     * the inline deciders the sibling jobs already use.
     *
     * <p>Every gate publishes all four outcomes and every outcome has a transition. {@link #GATE_PROCEED} and
     * {@link #GATE_PROCEED_WITH_REJECTS} both lead to the next stage, which is how the topology itself states
     * that return code 4 is not a failure. {@link #EXIT_CODE_ANY} is wired to a failure at every gate so an
     * unforeseen status cannot slip through as success.
     *
     * @param postTranStep stage 1
     * @param intCalcStep stage 2
     * @param combTranStep stage 3
     * @param statementReportSplitFlow stage 4, the parallel pair
     * @return the pipeline flow, never {@code null}
     */
    @Bean(FLOW_BEAN_NAME)
    public Flow batchPipelineFlow(
            @Qualifier(POSTTRAN_STEP_BEAN_NAME) final Step postTranStep,
            @Qualifier(INTCALC_STEP_BEAN_NAME) final Step intCalcStep,
            @Qualifier(COMBTRAN_STEP_BEAN_NAME) final Step combTranStep,
            @Qualifier(SPLIT_FLOW_BEAN_NAME) final Flow statementReportSplitFlow) {

        final JobExecutionDecider afterPostTran = new StageGateDecider(STAGE_POSTTRAN, LOCATOR_POSTTRAN,
                List.of(new GatedStage(STAGE_POSTTRAN, INFIX_POSTTRAN)));
        final JobExecutionDecider afterIntCalc = new StageGateDecider(STAGE_INTCALC, LOCATOR_INTCALC,
                List.of(new GatedStage(STAGE_INTCALC, INFIX_INTCALC)));
        final JobExecutionDecider afterCombTran = new StageGateDecider(STAGE_COMBTRAN, LOCATOR_COMBTRAN,
                List.of(new GatedStage(STAGE_COMBTRAN, INFIX_COMBTRAN)));
        final JobExecutionDecider afterSplit = new StageGateDecider(
                STAGE_CREASTMT + " || " + STAGE_TRANREPT, LOCATOR_CREASTMT + " and " + LOCATOR_TRANREPT,
                List.of(new GatedStage(STAGE_CREASTMT, INFIX_CREASTMT),
                        new GatedStage(STAGE_TRANREPT, INFIX_TRANREPT)));

        return new FlowBuilder<SimpleFlow>(FLOW_BEAN_NAME)
                .start(postTranStep)
                .on(EXIT_CODE_ANY).to(afterPostTran)
                .from(afterPostTran).on(GATE_PROCEED).to(intCalcStep)
                .from(afterPostTran).on(GATE_PROCEED_WITH_REJECTS).to(intCalcStep)
                .from(afterPostTran).on(GATE_HALT).fail()
                .from(afterPostTran).on(GATE_ABEND).fail()
                .from(afterPostTran).on(EXIT_CODE_ANY).fail()

                .from(intCalcStep).on(EXIT_CODE_ANY).to(afterIntCalc)
                .from(afterIntCalc).on(GATE_PROCEED).to(combTranStep)
                .from(afterIntCalc).on(GATE_PROCEED_WITH_REJECTS).to(combTranStep)
                .from(afterIntCalc).on(GATE_HALT).fail()
                .from(afterIntCalc).on(GATE_ABEND).fail()
                .from(afterIntCalc).on(EXIT_CODE_ANY).fail()

                .from(combTranStep).on(EXIT_CODE_ANY).to(afterCombTran)
                .from(afterCombTran).on(GATE_PROCEED).to(statementReportSplitFlow)
                .from(afterCombTran).on(GATE_PROCEED_WITH_REJECTS).to(statementReportSplitFlow)
                .from(afterCombTran).on(GATE_HALT).fail()
                .from(afterCombTran).on(GATE_ABEND).fail()
                .from(afterCombTran).on(EXIT_CODE_ANY).fail()

                .from(statementReportSplitFlow).on(EXIT_CODE_ANY).to(afterSplit)
                .from(afterSplit).on(GATE_PROCEED).end(EXIT_CODE_COMPLETED)
                .from(afterSplit).on(GATE_PROCEED_WITH_REJECTS).end(EXIT_CODE_COMPLETED_WITH_REJECTS)
                .from(afterSplit).on(GATE_HALT).fail()
                .from(afterSplit).on(GATE_ABEND).fail()
                .from(afterSplit).on(EXIT_CODE_ANY).fail()
                .build();
    }

    /**
     * The pipeline job.
     *
     * <p><b>No incrementer is declared, deliberately.</b> The stream has no run-sequence concept, and adding
     * one would let the same daily transaction content be posted twice under a fresh instance - exactly the
     * duplicate-key collision that must surface rather than be smoothed over. Relaunching with identical
     * parameters is therefore refused by the job repository, and
     * {@link #launchPipeline(Job, String, String, String)} translates that refusal into a
     * {@link ValidationException} that says so.
     *
     * <p>{@link BatchPipelineJobListener} is attached here rather than to any step, because the diagnostic
     * context it owns spans all five stages. It is a plain nested object for the same reason the deciders are.
     *
     * @param batchPipelineFlow the composed flow, injected by bean name
     * @return the job, registered under {@code carddemo.batch.jobs.pipeline.name}, never {@code null}
     */
    @Bean(JOB_BEAN_NAME)
    public Job batchPipelineJob(@Qualifier(FLOW_BEAN_NAME) final Flow batchPipelineFlow) {
        return new JobBuilder(jobName, jobRepository)
                // Finding B-12: without this the framework hands the ALL-CAPS job name to
                // Micrometer Tracing, whose SpanNameUtil.toLowerHyphen hyphenates every
                // upper-case character, so the run reached the trace store under a name no
                // operator could search for. Registered per builder because Spring Batch
                // resolves no convention bean from the context.
                .observationConvention(BatchJobSpanNamingConvention.INSTANCE)
                .validator(this::validateJobParameters)
                .listener(new BatchPipelineJobListener())
                .start(batchPipelineFlow)
                .end()
                .build();
    }

    // The launch surface. Two public methods: one that turns three untrusted strings into the exact
    // parameter set the stream requires, and one that launches with them. Neither runs on startup, and
    // no listener, schedule or trigger is declared anywhere in this class.

    /**
     * Builds and validates the parameter set every stage of the pipeline receives.
     *
     * <p>All three parameters are identifying, so the job repository keys the pipeline instance on them - and
     * therefore keys each child instance on them too, because each child is launched with the same set. That
     * is what makes a relaunch with unchanged parameters a refusal rather than a silent re-post.
     *
     * <p>Both date shapes are checked here, before anything is launched, because a job parameter is untrusted
     * input. The two shapes are <b>not</b> interchangeable: see the class documentation.
     *
     * @param parmDate the interest date, ten digits with no separator ending in two zeros, from
     *     {@code app/jcl/INTCALC.jcl:L22}
     * @param startDate the report start date in {@code yyyy-MM-dd}, from {@code app/proc/TRANREPT.prc:L41}
     * @param endDate the report end date in {@code yyyy-MM-dd}, from {@code app/proc/TRANREPT.prc:L42}
     * @return the parameter set, never {@code null}
     * @throws ValidationException if any value is absent, blank, the wrong shape, not a calendar date, or if
     *     {@code startDate} is later than {@code endDate}
     */
    public JobParameters pipelineParameters(final String parmDate, final String startDate,
            final String endDate) {

        final String checkedParmDate = requireInterestDate(parmDate);
        final String checkedStartDate = requireReportDate(startDate, START_DATE_JOB_PARAMETER);
        final String checkedEndDate = requireReportDate(endDate, END_DATE_JOB_PARAMETER);
        if (checkedStartDate.compareTo(checkedEndDate) > 0) {
            throw ValidationException.invalidField(START_DATE_JOB_PARAMETER,
                    START_DATE_JOB_PARAMETER + " must not be later than " + END_DATE_JOB_PARAMETER
                            + "; app/proc/TRANREPT.prc:L45-L46 filters an inclusive range");
        }

        return new JobParametersBuilder()
                .addString(PARM_DATE_JOB_PARAMETER, checkedParmDate)
                .addString(START_DATE_JOB_PARAMETER, checkedStartDate)
                .addString(END_DATE_JOB_PARAMETER, checkedEndDate)
                .toJobParameters();
    }

    /**
     * Launches the whole stream, explicitly, once.
     *
     * <p>The job is a parameter rather than an injected field because this class <em>declares</em> it: a
     * constructor that injected {@value #JOB_BEAN_NAME} would be a self-reference. A caller already holds the
     * bean, so passing it is both honest and free of a lazy proxy.
     *
     * <p>The callers this exists for are a test invoking it directly and any caller that already holds the
     * pipeline bean. It is deliberately <em>not</em> wired to a runner: an operator submission goes through
     * the framework's own launcher, for the reasons set out in the block comment below. The queue listener
     * that replaces the JES2 internal reader is <em>not</em> one of its callers either,
     * and the distinction is worth keeping straight: a report submission names one report period, so
     * {@code com.cardemo.config.BatchConfig} launches {@code transactionReportJob} from it, not the whole
     * five-stage stream. <b>This method never terminates the process</b>: it reports an outcome through the
     * returned execution and through typed exceptions, and nothing else.
     *
     * @param pipelineJob the {@value #JOB_BEAN_NAME} bean this class declares
     * @param parmDate the interest date, ten digits with no separator
     * @param startDate the report start date in {@code yyyy-MM-dd}
     * @param endDate the report end date in {@code yyyy-MM-dd}
     * @return the finished pipeline execution, whose exit status is one of the four documented codes and
     *     whose execution context carries the per-stage return codes, never {@code null}
     * @throws ValidationException if any parameter is unusable, or if an instance with these parameters has
     *     already completed - the deliberate refusal that keeps a duplicate post visible
     * @throws FatalProcessingException if the pipeline or a child is already running, if a restart is
     *     refused, or if the launcher fails for any other reason
     */
    public JobExecution launchPipeline(final Job pipelineJob, final String parmDate, final String startDate,
            final String endDate) {

        if (pipelineJob == null) {
            throw ValidationException.missingField("pipelineJob",
                    "the " + JOB_BEAN_NAME + " bean is required to launch the pipeline");
        }
        final JobParameters parameters = pipelineParameters(parmDate, startDate, endDate);
        LOG.info("Launching the batch pipeline {} with {}={} {}={} {}={}",
                jobName,
                PARM_DATE_JOB_PARAMETER, parameters.getString(PARM_DATE_JOB_PARAMETER),
                START_DATE_JOB_PARAMETER, parameters.getString(START_DATE_JOB_PARAMETER),
                END_DATE_JOB_PARAMETER, parameters.getString(END_DATE_JOB_PARAMETER));
        try {
            return jobLauncher.run(pipelineJob, parameters);
        } catch (final JobParametersInvalidException refused) {
            throw new ValidationException(
                    "The batch pipeline " + jobName + " refused its parameters: " + refused.getMessage(),
                    refused);
        } catch (final JobInstanceAlreadyCompleteException refused) {
            throw new ValidationException(
                    "The batch pipeline " + jobName + " has already completed with these parameters. No"
                            + " incrementer is declared, deliberately, so a repeat of an unchanged run is"
                            + " refused rather than allowed to post the same daily transaction file twice.",
                    refused);
        } catch (final JobExecutionAlreadyRunningException refused) {
            throw new FatalProcessingException(ABEND_CODE, ABEND_CULPRIT, "PIPELINE ALREADY RUNNING",
                    "The batch pipeline " + jobName + " is already running.", refused);
        } catch (final JobRestartException refused) {
            throw new FatalProcessingException(ABEND_CODE, ABEND_CULPRIT, "PIPELINE RESTART REFUSED",
                    "The batch pipeline " + jobName + " cannot be restarted.", refused);
        } catch (final RuntimeException unexpected) {
            throw new FatalProcessingException(ABEND_CODE, ABEND_CULPRIT, "PIPELINE LAUNCH FAILED",
                    "The batch pipeline " + jobName + " could not be launched.", unexpected);
        }
    }

    // HOW A JOB IS STARTED, AND WHY NO RUNNER IS DECLARED HERE.
    //
    // Finding CFG-001, severity High. No property-gated ApplicationRunner may be declared here - a
    // carddemo.batch.launch runner launching a named job from inside application startup, say. That is a
    // boot-time launch however narrowly it is gated: the runner executes during
    // SpringApplication.run, before the application is serving anything, and the AAP's contract for this
    // class is that nothing here runs on startup. A gate makes the bean conditional, not the launch
    // deliberate.
    //
    // The two sanctioned paths both remain, and neither is declared by this class:
    //
    //   * THE OPERATOR SUBMISSION - what replaced "submit the deck from TSO or SDSF" - is the framework's
    //     own JobLauncherApplicationRunner, which the base profile keeps switched off with
    //     spring.batch.job.enabled=false and which an operator turns on for one process:
    //
    //       java -jar carddemo-1.0.0.jar --spring.main.web-application-type=none \
    //            --spring.batch.job.enabled=true --spring.batch.job.name=CARDDEMO-PIPELINE \
    //            parmDate=2022071800 startDate=2022-01-01 endDate=2022-07-06
    //
    //     The value is the JOB NAME and not the bean name, which matters because they differ here by design:
    //     JobLauncherApplicationRunner compares spring.batch.job.name with Job.getName() - verified against
    //     the compiled 3.5.11 class, not inferred - and every job in this stream is named after the JCL
    //     member it replaces. So substitute POSTTRAN, INTCALC, COMBTRAN, CREASTMT or TRANREPT to run one
    //     stage, exactly as submitting one member ran one program, and note that OMITTING the property runs
    //     every job rather than none. The names are settable through carddemo.batch.pipeline.name and
    //     carddemo.batch.jobs.<member>.name; the defaults above are what an unconfigured deployment answers
    //     to. Non-option arguments of the form name=value become job parameters, which is how the JCL
    //     parameter cards arrive, and the three names are PARM_DATE_JOB_PARAMETER,
    //     START_DATE_JOB_PARAMETER and END_DATE_JOB_PARAMETER as declared above - camel case, not the
    //     hyphenated spelling the JCL suggests. This comment carried the hyphenated form until a review
    //     ran it: INTCALC refused to start with JobParametersInvalidException naming parmDate, so the
    //     command as written could not have launched the stage it documented.
    //     --spring.main.web-application-type=none is part of the command rather than
    //     an extra: it is what makes the process end when the job ends, the way a submitted job freed its
    //     initiator, and without it the embedded servlet container holds the JVM open afterwards.
    //
    //     Nothing is lost by using the framework's runner instead of an authored one. Every parameter this
    //     stream takes is validated by the job it belongs to - validateJobParameters below is attached to
    //     this pipeline through JobBuilder.validator, and each stage job declares its own validator - so the
    //     refusals are identical whichever launcher calls JobLauncher.run. The framework maps a COMPLETED
    //     execution to exit status 0 and an unsuccessful one to non-zero, so return code 4 with rejects
    //     still exits 0, which is what app/cbl/CBTRN02C.cbl:L202-L234 means by it.
    //
    //   * THE QUEUE-TRIGGERED PATH - what replaced the online tier writing to the JOBS transient data queue
    //     for the JES2 internal reader - is the listener in com.cardemo.config.BatchConfig, which launches
    //     the report job when a submission message arrives. It withdraws itself from a process that is
    //     serving an operator submission, so the submitting and reading roles stay separate exactly as two
    //     address spaces did.
    //
    // launchPipeline above is the explicit entry point for a caller that already holds the pipeline bean -
    // the integration tier launches every scenario through it - and it stays public for that reason. It is
    // called, never scheduled.

    /**
     * The job's own parameter validator, so the same rules apply however the pipeline is launched.
     *
     * <p>{@link #launchPipeline(Job, String, String, String)} validates before it launches, but a caller that
     * builds its own parameters and uses the container's launcher directly would bypass that. Attaching the
     * identical check to the job closes the gap.
     *
     * @param parameters the parameters the launcher was given; may be {@code null}
     * @throws ValidationException if any of the three required parameters is absent or malformed
     */
    private void validateJobParameters(final JobParameters parameters) {
        if (parameters == null) {
            throw new ValidationException("The batch pipeline " + jobName
                    + " requires " + PARM_DATE_JOB_PARAMETER + ", " + START_DATE_JOB_PARAMETER + " and "
                    + END_DATE_JOB_PARAMETER + " but was launched with no parameters at all");
        }
        pipelineParameters(
                parameters.getString(PARM_DATE_JOB_PARAMETER),
                parameters.getString(START_DATE_JOB_PARAMETER),
                parameters.getString(END_DATE_JOB_PARAMETER));
    }

    /**
     * Validates the interest date parameter: ten digits, no separator, ending in two zeros.
     *
     * <p>{@code app/jcl/INTCALC.jcl:L22} supplies {@code PARM='2022071800'}, and
     * {@link InterestCalculationJob} concatenates the value ahead of a six-digit suffix to form a
     * sixteen-digit transaction identifier, so a dashed date here would silently produce identifiers of the
     * wrong shape. The authoritative validator is the one the stage attaches to its own job; this is the
     * fail-fast copy that runs before anything is launched.
     *
     * @param value the candidate value; may be {@code null} or blank
     * @return the value unchanged
     * @throws ValidationException if the value is absent, is not exactly ten digits, does not end in the two
     *     trailing zeros, or does not begin with a plausible {@code yyyyMMdd}
     */
    private static String requireInterestDate(final String value) {
        if (value == null || value.isBlank()) {
            throw ValidationException.missingField(PARM_DATE_JOB_PARAMETER,
                    PARM_DATE_JOB_PARAMETER + " is required; app/jcl/INTCALC.jcl:L22 supplies"
                            + " PARM='2022071800'");
        }
        if (value.length() != PARM_DATE_LENGTH) {
            throw ValidationException.invalidField(PARM_DATE_JOB_PARAMETER,
                    PARM_DATE_JOB_PARAMETER + " must be exactly " + PARM_DATE_LENGTH
                            + " characters but " + value.length() + " were supplied");
        }
        for (int index = 0; index < PARM_DATE_LENGTH; index++) {
            final char digit = value.charAt(index);
            if (digit < '0' || digit > '9') {
                throw ValidationException.invalidField(PARM_DATE_JOB_PARAMETER,
                        PARM_DATE_JOB_PARAMETER + " must be ten ASCII digits with no separator; a non-digit"
                                + " was found at position " + (index + 1)
                                + ". A yyyy-MM-dd value belongs to " + START_DATE_JOB_PARAMETER + " and "
                                + END_DATE_JOB_PARAMETER + ", not here");
            }
        }
        if (!value.endsWith(PARM_DATE_TRAILER)) {
            throw ValidationException.invalidField(PARM_DATE_JOB_PARAMETER,
                    PARM_DATE_JOB_PARAMETER + " must end with the two trailing zeros that"
                            + " app/jcl/INTCALC.jcl:L22 supplies in PARM='2022071800'");
        }
        requireCalendarDate(
                PARM_DATE_JOB_PARAMETER,
                Integer.parseInt(value.substring(0, 4)),
                Integer.parseInt(value.substring(4, 6)),
                Integer.parseInt(value.substring(6, PARM_DATE_DATE_DIGITS)));
        return value;
    }

    /**
     * Validates one report date parameter: exactly {@code yyyy-MM-dd}, dashes included.
     *
     * <p>{@code app/proc/TRANREPT.prc:L41} and {@code :L42} declare the sort symbols
     * {@code PARM-START-DATE,C'2022-01-01'} and {@code PARM-END-DATE,C'2022-07-06'}, and {@code :L40} places
     * the processing date at offset 305 for ten characters, so the dashes are part of the compared value
     * rather than presentation.
     *
     * @param value the candidate value; may be {@code null} or blank
     * @param parameterName the exact parameter name, used in every diagnostic
     * @return the value unchanged
     * @throws ValidationException if the value is absent, is not the exact ten-character digit-and-dash
     *     shape, or is not a calendar date
     */
    private static String requireReportDate(final String value, final String parameterName) {
        if (value == null || value.isBlank()) {
            throw ValidationException.missingField(parameterName,
                    parameterName + " is required in yyyy-MM-dd form; app/proc/TRANREPT.prc:L41 supplies"
                            + " C'2022-01-01'");
        }
        if (value.length() != REPORT_DATE_LENGTH
                || value.charAt(REPORT_DATE_FIRST_DASH) != '-'
                || value.charAt(REPORT_DATE_SECOND_DASH) != '-') {
            throw ValidationException.invalidField(parameterName,
                    parameterName + " must have the exact yyyy-MM-dd shape, dashes included. A ten-digit"
                            + " value with no separator belongs to " + PARM_DATE_JOB_PARAMETER
                            + ", not here");
        }
        for (int index = 0; index < REPORT_DATE_LENGTH; index++) {
            if (index == REPORT_DATE_FIRST_DASH || index == REPORT_DATE_SECOND_DASH) {
                continue;
            }
            final char digit = value.charAt(index);
            if (digit < '0' || digit > '9') {
                throw ValidationException.invalidField(parameterName,
                        parameterName + " must be yyyy-MM-dd; a non-digit was found at position "
                                + (index + 1));
            }
        }
        requireCalendarDate(
                parameterName,
                Integer.parseInt(value.substring(0, REPORT_DATE_FIRST_DASH)),
                Integer.parseInt(value.substring(REPORT_DATE_FIRST_DASH + 1, REPORT_DATE_SECOND_DASH)),
                Integer.parseInt(value.substring(REPORT_DATE_SECOND_DASH + 1)));
        return value;
    }

    /**
     * Rejects a year, month and day triple that is not a real calendar date.
     *
     * <p>The arithmetic is done by hand rather than through a temporal type, so the check depends on no
     * default locale, no default time zone and no formatter, and so that nothing in this class can be
     * mistaken for parsing a timestamp.
     *
     * @param parameterName the parameter being validated, for the diagnostic
     * @param year the four-digit year
     * @param month the one-based month
     * @param day the one-based day of month
     * @throws ValidationException if the triple is not a calendar date
     */
    private static void requireCalendarDate(final String parameterName, final int year, final int month,
            final int day) {

        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw ValidationException.invalidField(parameterName,
                    parameterName + " must begin with a plausible year; " + year + " is outside "
                            + MIN_YEAR + " to " + MAX_YEAR);
        }
        if (month < MIN_MONTH || month > MAX_MONTH) {
            throw ValidationException.invalidField(parameterName,
                    parameterName + " must name a month between " + MIN_MONTH + " and " + MAX_MONTH
                            + " but was " + month);
        }
        final int lastDay = monthLength(year, month);
        if (day < 1 || day > lastDay) {
            throw ValidationException.invalidField(parameterName,
                    parameterName + " must name a day between 1 and " + lastDay + " for month " + month
                            + " of " + year + " but was " + day);
        }
    }

    /**
     * Days in one month, honouring the Gregorian leap rule.
     *
     * <p>The lengths are a switch rather than a lookup array because an array field would be the one mutable
     * static in this class: {@code final} fixes the reference, not the elements. Every static member here is
     * an immutable value, which is what makes "no global mutable state" checkable by reading the field list.
     *
     * @param year the four-digit year
     * @param month the one-based month, already known to be between {@value #MIN_MONTH} and
     *     {@value #MAX_MONTH}
     * @return the number of days in that month
     */
    private static int monthLength(final int year, final int month) {
        if (month == FEBRUARY) {
            final boolean leap = year % 4 == 0 && year % 100 != 0 || year % 400 == 0;
            return leap ? FEBRUARY_LEAP_LENGTH : 28;
        }
        return switch (month) {
            case 4, 6, 9, 11 -> 30;
            default -> 31;
        };
    }


    // Step construction and the parallel branch executor.

    /**
     * Builds one launcher step.
     *
     * <p>The transaction attribute is the load-bearing part. A tasklet's default
     * {@code PROPAGATION_REQUIRED} would open a transaction on the injected manager and every
     * required-propagation step inside the child job would join it, so the child's commits would not commit
     * and one pooled connection would be held for the whole job.
     * {@link TransactionDefinition#PROPAGATION_NOT_SUPPORTED} leaves the child to own its own transactions,
     * which is what the framework's own nested job step does by not being transactional at all.
     *
     * @param stepName the bean name, which is also the step name and the transaction attribute's name
     * @param tasklet the stage runner
     * @return the step, never {@code null}
     */
    private Step launcherStep(final String stepName, final Tasklet tasklet) {
        final DefaultTransactionAttribute attribute =
                new DefaultTransactionAttribute(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        attribute.setName(stepName);
        return new StepBuilder(stepName, jobRepository)
                .tasklet(tasklet, transactionManager)
                .transactionAttribute(attribute)
                .build();
    }

    /**
     * The executor the stage-4 split runs its two branches on.
     *
     * <p>One thread per branch, created on demand and never pooled, so neither branch can be starved by a
     * shared pool that is busy elsewhere. The concurrency limit is the branch count rather than a tunable,
     * because there are exactly two branches and there is nothing to tune.
     *
     * <p>The decorator is what makes the diagnostic context survive the thread hop, and it is applied on the
     * submitting thread so the launching context is the one captured.
     *
     * @return the executor, never {@code null} and never published as a bean
     */
    private static SimpleAsyncTaskExecutor branchExecutor() {
        final SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor(SPLIT_THREAD_NAME_PREFIX);
        executor.setConcurrencyLimit(SPLIT_BRANCH_COUNT);
        executor.setTaskDecorator(BatchPipelineOrchestrator::carryDiagnosticContext);
        return executor;
    }

    /**
     * Wraps one split branch so it runs with the launching thread's diagnostic context and leaves none
     * behind.
     *
     * <p>The context is thread-local, so without this the two branches would emit every event with empty
     * {@link CorrelationIdFilter#MDC_KEY_JOB_INSTANCE_ID},
     * {@link CorrelationIdFilter#MDC_KEY_CORRELATION_ID}, {@link CorrelationIdFilter#MDC_KEY_TRACE_ID} and
     * {@link CorrelationIdFilter#MDC_KEY_SPAN_ID} entries - a whole third of the run untraceable. The whole
     * map is copied rather than four named keys, because the branch must also inherit anything the tracing
     * bridge published, and the branch clears the whole map rather than restoring it because a branch thread
     * belongs to the pipeline's own executor and had no context of its own to preserve. The copy is taken here,
     * on the submitting thread, because that is where the decorator is invoked; it is applied and then
     * cleared inside the branch, in a {@code finally}, so a thread that is somehow reused cannot inherit
     * another run's identity.
     *
     * @param branch the branch task the split submitted
     * @return the decorated task, never {@code null}
     */
    private static Runnable carryDiagnosticContext(final Runnable branch) {
        final Map<String, String> launchingContext = MDC.getCopyOfContextMap();
        return () -> {
            if (launchingContext == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(launchingContext);
            }
            try {
                branch.run();
            } finally {
                MDC.clear();
            }
        };
    }

    // The five stage runners. One per JCL member, each a thin body that names what it replaces and then
    // defers to the shared launch-and-record sequence. Stage 2 publishes the generation it created and
    // stage 3 verifies it; the other three have nothing to hand on.

    /**
     * Stage 1, replacing {@code app/jcl/POSTTRAN.jcl:L23} {@code //STEP15 EXEC PGM=CBTRN02C}.
     *
     * <p>The member declares six data definitions and no {@code COND=}. Its reject output is
     * {@code AWS.M2.CARDDEMO.DALYREJS(+1)} at {@code :L34-L38} with {@code LRECL=430} at {@code :L36}; the
     * stage owns that geometry, and this runner owns only the launch and the outcome.
     *
     * @param contribution the step's contribution, whose exit status becomes the gate outcome
     * @param chunkContext the chunk context, from which the pipeline execution is reached
     * @return {@link RepeatStatus#FINISHED}: the stage runs once
     */
    private RepeatStatus runPostTranStage(final StepContribution contribution,
            final ChunkContext chunkContext) {

        return runStage(dailyTransactionPostingJob, STAGE_POSTTRAN, LOCATOR_POSTTRAN, INFIX_POSTTRAN,
                contribution, chunkContext);
    }

    /**
     * Stage 2, replacing {@code app/jcl/INTCALC.jcl:L22}
     * {@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'}.
     *
     * <p>After the stage completes, the generation keys it created are copied out of its own execution context
     * and pinned for stage 3. That copy is the whole point of launching through the launcher rather than
     * through a nested job step: the child execution, and therefore its context, is reachable.
     *
     * <p>The keys are published whatever the outcome, because a stage that rejected records still wrote the
     * generations it wrote and stage 3 must read the same ones.
     *
     * @param contribution the step's contribution
     * @param chunkContext the chunk context
     * @return {@link RepeatStatus#FINISHED}
     */
    private RepeatStatus runIntCalcStage(final StepContribution contribution,
            final ChunkContext chunkContext) {

        return runStage(interestCalculationJob, STAGE_INTCALC, LOCATOR_INTCALC, INFIX_INTCALC,
                contribution, chunkContext);
    }

    /**
     * Stage 3, replacing {@code app/jcl/COMBTRAN.jcl:L22} and {@code :L41}.
     *
     * <p>The sort's concatenated input is {@code TRANSACT.BKUP(0)} at {@code :L23-L24} followed by
     * {@code SYSTRAN(0)} at {@code :L25-L26}, and it is that second half which stage 2 wrote. Once the stage
     * has run, the key it actually resolved is compared with the key stage 2 published; a mismatch is an
     * abend that names both, never a substitution.
     *
     * @param contribution the step's contribution
     * @param chunkContext the chunk context
     * @return {@link RepeatStatus#FINISHED}
     */
    private RepeatStatus runCombTranStage(final StepContribution contribution,
            final ChunkContext chunkContext) {

        requireObjectStorageSubstrate();
        return runStage(combineTransactionsJob, STAGE_COMBTRAN, LOCATOR_COMBTRAN, INFIX_COMBTRAN,
                contribution, chunkContext);
    }

    /**
     * Refuses to run stage 3 on the relational substrate.
     *
     * <p><b>Finding M-01, severity High.</b> Stage 3's reader takes its first leg either from a
     * {@code TRANSACT.BKUP} generation or from the live transaction relation, selected by
     * {@value #READER_SOURCE_PROPERTY}, whose default is {@code repository}. Only
     * {@value #READER_SOURCE_OBJECT_STORAGE} is correct here, and the reason is arithmetic rather than
     * preference: on the relational substrate the first leg <em>is</em> the table that
     * {@code app/jcl/COMBTRAN.jcl:L41-L48} then loads into, so the stage reads the rows it is about to
     * re-insert.
     *
     * <p>This was previously documented as an operator prerequisite - the property was left at its default
     * and a note said an operator driving the pipeline should override it. A prerequisite that nothing checks
     * is not a prerequisite: the default was never overridden, so the pipeline's normal path was the wrong
     * one, and the only thing standing between it and a corrupt load was the duplicate-identifier refusal
     * happening to fire. Refusing here converts that into a stated, enforced precondition, which is the
     * conservative half of the fix; {@code src/main/resources/application.yml} now also declares the
     * property explicitly rather than leaving the value implicit.
     *
     * <p>It refuses rather than overriding the substrate per run. The substrate is a property of how this
     * deployment stores the datasets, not a per-run choice, and a pipeline that silently switched it would
     * make a misconfigured deployment undetectable - the failure mode being fixed.
     *
     * @throws ValidationException if the configured substrate is anything other than object storage
     */
    private void requireObjectStorageSubstrate() {
        if (READER_SOURCE_OBJECT_STORAGE.equals(readerSource)) {
            return;
        }
        throw new ValidationException("Pipeline stage " + STAGE_COMBTRAN + " requires "
                + READER_SOURCE_PROPERTY + "=" + READER_SOURCE_OBJECT_STORAGE + " but it is '"
                + readerSource + "'. On the relational substrate the concatenated SORTIN of"
                + " app/jcl/COMBTRAN.jcl:L23-L26 would take its first leg from the very transaction"
                + " relation that :L41-L48 then loads into, so the stage would read the rows it is about"
                + " to re-insert. Set " + READER_SOURCE_PROPERTY + "="
                + READER_SOURCE_OBJECT_STORAGE + " for any run of this pipeline.");
    }

    /**
     * Stage 4's statement branch, replacing the five steps of {@code app/jcl/CREASTMT.JCL}.
     *
     * <p>Runs on a branch thread, in parallel with {@link #runTranReptStage(StepContribution, ChunkContext)}.
     * The {@code COND=(0,NE)} gates at {@code :L56}, {@code :L66} and {@code :L79} are internal to the stage
     * and are not reproduced here.
     *
     * @param contribution the step's contribution
     * @param chunkContext the chunk context
     * @return {@link RepeatStatus#FINISHED}
     */
    private RepeatStatus runCreaStmtStage(final StepContribution contribution,
            final ChunkContext chunkContext) {

        return runStage(statementGenerationJob, STAGE_CREASTMT, LOCATOR_CREASTMT, INFIX_CREASTMT,
                contribution, chunkContext);
    }

    /**
     * Stage 4's report branch, replacing the three steps of {@code app/proc/TRANREPT.prc}.
     *
     * <p>Runs on the other branch thread. It consumes its own backup generation and writes its own report
     * generation, sharing neither input nor output with the statement branch, which is why the two are
     * unordered.
     *
     * @param contribution the step's contribution
     * @param chunkContext the chunk context
     * @return {@link RepeatStatus#FINISHED}
     */
    private RepeatStatus runTranReptStage(final StepContribution contribution,
            final ChunkContext chunkContext) {

        return runStage(transactionReportJob, STAGE_TRANREPT, LOCATOR_TRANREPT, INFIX_TRANREPT,
                contribution, chunkContext);
    }

    /**
     * Launches one stage, maps its outcome onto a return code, records it and reports a gate outcome.
     *
     * <p>The sequence is fixed and is the same for every stage: launch with the pipeline's own parameters,
     * carry the child's failures onto the pipeline so no cause is lost, hand on or verify the generation key
     * where the stage requires it, record the return code, and set the step's exit status to the gate the
     * flow will act on.
     *
     * <p><b>A failed or abended stage is not thrown from here.</b> Return codes 4, 8 and 12 are business
     * outcomes of the stream, exactly as a reject code is a business outcome of a record, and they drive the
     * flow through {@link StageGateDecider} rather than through the exception path. Nothing is swallowed: the
     * child's own throwables are attached to the pipeline execution and logged with their stack traces, and
     * the pipeline ends failed.
     *
     * @param stage the sibling job to launch
     * @param stageName the stage's display name
     * @param locator the {@code path:line} of the JCL step this stage replaces
     * @param infix the execution-context infix under which this stage's outcome is recorded
     * @param contribution the step's contribution, whose exit status becomes the gate outcome
     * @param chunkContext the chunk context, from which the pipeline execution is reached
     * @return {@link RepeatStatus#FINISHED}
     */
    private RepeatStatus runStage(final Job stage, final String stageName, final String locator,
            final String infix, final StepContribution contribution, final ChunkContext chunkContext) {

        final JobExecution pipeline = chunkContext.getStepContext().getStepExecution().getJobExecution();
        LOG.info("START OF PIPELINE STAGE {} - {}", stageName, locator);

        final JobExecution child =
                launchStage(stage, stageName, stageParameters(pipeline, infix, stageName));
        int returnCode = stageReturnCode(child);
        promoteFailures(pipeline, child, stageName);

        if (INFIX_INTCALC.equals(infix)) {
            publishSystranGeneration(pipeline, child);
        } else if (INFIX_COMBTRAN.equals(infix)) {
            returnCode = Math.max(returnCode, verifySystranHandoff(pipeline, child));
        }

        recordStage(pipeline, infix, returnCode, child);
        final String gate = gateFor(returnCode);
        contribution.setExitStatus(new ExitStatus(gate));
        LOG.info("END OF PIPELINE STAGE {} - return code {}, gate {}, child exit status {}",
                stageName, Integer.valueOf(returnCode), gate, child.getExitStatus().getExitCode());
        return RepeatStatus.FINISHED;
    }

    /**
     * Builds the parameter set one stage is launched with.
     *
     * <p>Every stage receives all three pipeline parameters unchanged, and stage 3 additionally receives the
     * <strong>exact {@code SYSTRAN} generation stage 2 created</strong> under
     * {@value com.cardemo.batch.readers.CombinedTransactionReader#SYSTRAN_GENERATION_JOB_PARAMETER}.
     *
     * <p><strong>Why a job parameter and why before the launch.</strong> {@code app/jcl/COMBTRAN.jcl:L25-L26}
     * reads {@code SYSTRAN(0)}, and on the mainframe that resolves through a catalogue the preceding step
     * updated atomically at close, so it can only ever mean "the generation this stream just produced". Two
     * separate Spring Batch jobs share no catalogue, so the identity has to be handed over explicitly, and
     * the handover has to happen <em>before</em> the child starts or the child has nothing to obey. An earlier
     * revision pinned the generation in the pipeline's own execution context and compared it with what stage 3
     * had resolved <em>afterwards</em>, on the reasoning that seeding a child's execution context is not a
     * supported framework operation. That reasoning is sound about execution contexts and led to the wrong
     * conclusion: a job parameter is supported, is read by a step-scoped bean through
     * {@code #{jobParameters[...]}}, and is fixed before the first step runs. The check remains, but it is now
     * defence in depth behind a contract rather than the only guard - the difference matters because a check
     * that runs after the load has committed cannot prevent the wrong generation being loaded.
     *
     * <p>The added parameter is identifying, like the three it joins. That is correct rather than incidental:
     * the generation is part of stage 3's input identity, so two pipeline runs over the same dates but
     * different interest generations are two distinct stage-3 instances. The pipeline's own instance is keyed
     * on the three original parameters alone, so a repeat of an unchanged pipeline is still refused, and it is
     * refused before any stage launches.
     *
     * @param pipeline the running pipeline execution, carrying its own parameters and the pinned generation
     * @param infix the execution-context infix identifying the stage
     * <p><b>Two stages also carry a per-stage instruction</b>, each reproducing a JCL member that sits
     * beside the one the stage replaces: stage 3 is told to run the {@code app/jcl/TRANBKP.jcl} archive
     * and reset, and the report branch is told to run the {@code app/jcl/PRTCATBL.jcl} unload and print.
     * Both are instructed rather than defaulted on, so that a standalone run of either job is exactly the
     * member it replaces and nothing more, and both are non-identifying for the same reason the pinned
     * key above is identifying only where instance identity genuinely depends on it.
     *
     * @param stageName the stage's display name, for the diagnostic
     * @return the parameter set to launch this stage with, never {@code null}
     */
    private static JobParameters stageParameters(final JobExecution pipeline, final String infix,
            final String stageName) {

        final JobParameters inherited = pipeline.getJobParameters();

        // The report branch is instructed to run the app/jcl/PRTCATBL.jcl unload and print. PRTCATBL and
        // TRANREPT are both REPROC-plus-SORT report members over clusters the posting stage has just
        // updated, and neither writes anything the other reads, so the report branch is where it belongs.
        // Non-identifying, so a stage's instance identity stays keyed on the pipeline's own parameters.
        if (INFIX_TRANREPT.equals(infix)) {
            LOG.info("Pipeline instructed stage {} to run the app/jcl/PRTCATBL.jcl unload and print as a"
                    + " non-identifying parameter", stageName);
            return new JobParametersBuilder(inherited)
                    .addString(TransactionReportJob.JOB_PARAMETER_PRINT_CATEGORY_BALANCES,
                            Boolean.TRUE.toString(), false)
                    .toJobParameters();
        }

        if (!INFIX_COMBTRAN.equals(infix)) {
            return inherited;
        }

        // app/jcl/TRANBKP.jcl precedes app/jcl/COMBTRAN.jcl in the operator's stream, so inside the pipeline
        // the archive-and-reset runs: :L23-L33 copies the master to TRANSACT.BKUP(+1) and :L37-L67 deletes
        // and redefines the cluster, which is what leaves :L41-L48 an empty target and makes the stream
        // repeatable. It is instructed here rather than defaulted on in the job, because a standalone
        // combine is not the operator's stream and must not empty anybody's master.
        final JobParametersBuilder builder = new JobParametersBuilder(inherited)
                .addString(CombineTransactionsJob.JOB_PARAMETER_ARCHIVE_MASTER,
                        Boolean.TRUE.toString(), false);

        final String pinned = pipeline.getExecutionContext()
                .getString(SYSTRAN_GENERATION_CONTEXT_ENTRY, "");
        if (pinned.isEmpty() || HANDOFF_ABSENT.equals(pinned)) {
            LOG.warn("Pipeline stage {} launches without a pinned SYSTRAN generation because stage {}"
                            + " published none; app/jcl/COMBTRAN.jcl:L25-L26 will resolve SYSTRAN(0) by"
                            + " listing, which cannot exclude a concurrent run's generation. The"
                            + " app/jcl/TRANBKP.jcl archive-and-reset is still instructed.",
                    stageName, STAGE_INTCALC);
            return builder.toJobParameters();
        }

        LOG.info("Pipeline hands stage {} the exact generation {} under job parameter {} before launch, and"
                        + " instructs the app/jcl/TRANBKP.jcl archive-and-reset",
                stageName, pinned, CombinedTransactionReader.SYSTRAN_GENERATION_JOB_PARAMETER);
        return builder
                .addString(CombinedTransactionReader.SYSTRAN_GENERATION_JOB_PARAMETER, pinned, true)
                .toJobParameters();
    }

    /**
     * Launches one sibling job with the parameter set {@link #stageParameters} built for it.
     *
     * <h4>Why the launch is retried, and only the launch</h4>
     *
     * <p>{@code spring.batch.jdbc.isolation-level-for-create} is {@code SERIALIZABLE}, which is what stops two
     * launches of the same job instance from both believing they created it. The price is that PostgreSQL may
     * abort one of two <em>genuinely distinct</em> concurrent creations with SQLSTATE
     * {@value #SQL_STATE_SERIALIZATION_FAILURE}, and the {@code CREASTMT || TRANREPT} split creates two
     * distinct child executions at the same instant by design. Left unhandled, that abort fails a branch for a
     * reason that has nothing to do with the batch data - a flake, and one that only ever appears in the
     * parallel half of the stream.
     *
     * <p>The retry is bounded to {@value #MAX_STAGE_LAUNCH_ATTEMPTS} attempts with a growing backoff, and it
     * is confined to the <b>creation phase</b> by construction rather than by inspection of a message.
     * {@link JobLauncher#run(Job, JobParameters)} creates the execution row and only then hands control to the
     * job; once the job is running, a step failure is recorded <em>on the returned execution</em> and does not
     * propagate out of {@code run}. A transient data-access failure escaping {@code run} therefore cannot have
     * come from a step - it can only have come from
     * {@code JobRepository.createJobExecution}, before any business work happened. Retrying it repeats nothing
     * and re-posts nothing.
     *
     * <p><b>Two things this deliberately does not do.</b> It does not weaken
     * {@code isolation-level-for-create}, because that setting is the only thing preventing a double launch of
     * one instance. And it does not serialise the two branches, because the parallelism is the behaviour under
     * test: {@code app/jcl/CREASTMT.JCL} and {@code app/proc/TRANREPT.prc} have no ordering dependency, and
     * collapsing them would stop the split being exercised at all.
     *
     * <p>Every other failure is still fatal on the first occurrence. A parameter refusal, an
     * already-complete instance, an already-running instance and a restart refusal are all deterministic:
     * retrying them would produce the identical outcome more slowly.
     *
     * @param stage the sibling job
     * @param stageName the stage's display name, for the diagnostics
     * @param parameters the parameter set for this stage
     * @return the finished child execution, never {@code null}
     * @throws ValidationException if a child validator refused the parameters, or if the child instance has
     *     already completed with them
     * @throws FatalProcessingException if the child was already running, a restart was refused, the launch
     *     kept failing to serialize after {@value #MAX_STAGE_LAUNCH_ATTEMPTS} attempts, or the launcher failed
     *     for any other reason
     */
    private JobExecution launchStage(final Job stage, final String stageName,
            final JobParameters parameters) {

        RuntimeException lastSerializationFailure = null;
        for (int attempt = 1; attempt <= MAX_STAGE_LAUNCH_ATTEMPTS; attempt++) {
            try {
                return launchStageOnce(stage, stageName, parameters);
            } catch (final TransientDataAccessException contended) {
                final String sqlState = transientConflictState(contended);
                if (sqlState == null) {
                    throw new FatalProcessingException(ABEND_CODE, ABEND_CULPRIT, "STAGE LAUNCH FAILED",
                            "Pipeline stage " + stageName + " could not be launched.", contended);
                }
                lastSerializationFailure = contended;
                LOG.warn("PIPELINE STAGE {} LAUNCH DID NOT SERIALIZE - SQLSTATE {}, attempt {} of {}."
                                + " The job execution row was never created, so nothing is repeated by"
                                + " retrying; this is the SERIALIZABLE creation isolation contending with"
                                + " the concurrent {} || {} branch.",
                        stageName, sqlState, Integer.valueOf(attempt),
                        Integer.valueOf(MAX_STAGE_LAUNCH_ATTEMPTS), STAGE_CREASTMT, STAGE_TRANREPT);
                if (attempt < MAX_STAGE_LAUNCH_ATTEMPTS) {
                    backOffBeforeRelaunch(stageName, attempt);
                }
            }
        }

        throw new FatalProcessingException(ABEND_CODE, ABEND_CULPRIT, "STAGE LAUNCH DID NOT SERIALIZE",
                "Pipeline stage " + stageName + " could not create its job execution in "
                        + MAX_STAGE_LAUNCH_ATTEMPTS + " attempts; every attempt was aborted by the database"
                        + " as a serialization or deadlock failure. This is persistent contention rather than"
                        + " a transient collision, so it is reported rather than retried further.",
                lastSerializationFailure);
    }

    /**
     * Sleeps between two stage-launch attempts, preserving interruption.
     *
     * <p>Inputs: the stage name for the diagnostic and the one-based count of attempts already made. Output:
     * none. Side effects: parks the calling thread for {@code attemptsMade} multiples of
     * {@value #LAUNCH_RETRY_BASE_BACKOFF_MILLIS} milliseconds.
     *
     * <p>The interrupt flag is restored before throwing, because the caller is a branch thread of
     * {@link #branchExecutor()} and swallowing an interrupt there would leave a shutdown request unanswered.
     *
     * @param stageName the stage being launched, for the message
     * @param attemptsMade one-based count of attempts already made
     * @throws FatalProcessingException if the wait is interrupted; the interrupt flag is set first
     */
    private static void backOffBeforeRelaunch(final String stageName, final int attemptsMade) {
        try {
            Thread.sleep(LAUNCH_RETRY_BASE_BACKOFF_MILLIS * attemptsMade);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new FatalProcessingException(ABEND_CODE, ABEND_CULPRIT, "STAGE LAUNCH INTERRUPTED",
                    "Pipeline stage " + stageName + " was interrupted while waiting to retry its launch.",
                    interrupted);
        }
    }

    /**
     * Returns the SQLSTATE of a transient class-40 conflict in this throwable's cause chain.
     *
     * <p><b>Why the SQLSTATE decides, and not the exception class.</b> The Spring exception class is too
     * coarse in the one direction that matters. Every candidate supertype that is not itself deprecated -
     * {@code PessimisticLockingFailureException}, {@code ConcurrencyFailureException} - also covers conditions
     * that must <em>not</em> be retried here, {@code OptimisticLockingFailureException} among them. The
     * SQLSTATE is the opposite: {@value #SQL_STATE_SERIALIZATION_FAILURE} and
     * {@value #SQL_STATE_DEADLOCK_DETECTED} name exactly the two class-40 aborts that a fresh snapshot can
     * clear, and nothing else. Both translator chains Spring ships pass the originating {@link SQLException}
     * through as the cause, so walking the chain finds it whether the top-level type was recognised or
     * uncategorised.
     *
     * <p><b>Fail closed.</b> When no SQLSTATE is found the answer is {@code null} and the caller aborts. A
     * transient failure this method cannot positively identify is treated as fatal rather than retried on
     * suspicion, because a wrong retry decision here would re-enter a launch whose real problem is elsewhere.
     *
     * <p>Inputs: the throwable to inspect, which may be {@code null}. Output: the matched SQLSTATE, or
     * {@code null}. Side effects: none.
     *
     * @param thrown the throwable to inspect
     * @return {@value #SQL_STATE_SERIALIZATION_FAILURE}, {@value #SQL_STATE_DEADLOCK_DETECTED}, or
     *     {@code null} when neither appears in the chain
     */
    private static String transientConflictState(final Throwable thrown) {
        Throwable current = thrown;
        for (int depth = 0; current != null && depth < CAUSE_CHAIN_LIMIT; depth++) {
            if (current instanceof final SQLException sqlFailure) {
                final String state = sqlFailure.getSQLState();
                if (SQL_STATE_SERIALIZATION_FAILURE.equals(state)
                        || SQL_STATE_DEADLOCK_DETECTED.equals(state)) {
                    return state;
                }
            }
            final Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return null;
    }

    /**
     * Performs exactly one launch attempt, mapping the launcher's checked failures onto the typed hierarchy.
     *
     * <p>Inputs: the sibling job, its display name and the pipeline's parameters. Output: the finished child
     * execution. Side effects: creates one job execution and runs the child job to completion.
     *
     * <p>A {@link TransientDataAccessException} is deliberately <b>not</b> caught here. It is the one failure
     * the caller can act on, so it is allowed to propagate to
     * {@link #launchStage(Job, String, JobParameters)} unwrapped; wrapping it would destroy the SQLSTATE the
     * retry decision reads.
     *
     * @param stage the sibling job
     * @param stageName the stage's display name, for the diagnostics
     * @param parameters the pipeline's own parameters
     * @return the finished child execution, never {@code null}
     * @throws ValidationException if a child validator refused the parameters, or if the child instance has
     *     already completed with them
     * @throws FatalProcessingException if the child was already running, or a restart was refused
     * @throws TransientDataAccessException if the execution row could not be created because the creating
     *     transaction did not serialize; propagated for the caller to retry
     */
    private JobExecution launchStageOnce(final Job stage, final String stageName,
            final JobParameters parameters) {

        try {
            return jobLauncher.run(stage, parameters);
        } catch (final JobParametersInvalidException refused) {
            throw new ValidationException("Pipeline stage " + stageName + " refused its parameters: "
                    + refused.getMessage(), refused);
        } catch (final JobInstanceAlreadyCompleteException refused) {
            throw new ValidationException("Pipeline stage " + stageName
                    + " has already completed with these parameters; no stage declares an incrementer, so a"
                    + " repeat of an unchanged run is refused rather than silently repeated.", refused);
        } catch (final JobExecutionAlreadyRunningException refused) {
            throw new FatalProcessingException(ABEND_CODE, ABEND_CULPRIT, "STAGE ALREADY RUNNING",
                    "Pipeline stage " + stageName + " is already running.", refused);
        } catch (final JobRestartException refused) {
            throw new FatalProcessingException(ABEND_CODE, ABEND_CULPRIT, "STAGE RESTART REFUSED",
                    "Pipeline stage " + stageName + " cannot be restarted.", refused);
        } catch (final TransientDataAccessException contended) {
            // Rethrown untouched so launchStage can read its SQLSTATE and decide. Listed before the general
            // RuntimeException arm because it IS a RuntimeException: without this arm the general one would
            // wrap it as a fatal abend and the bounded retry above would be unreachable.
            throw contended;
        } catch (final RuntimeException unexpected) {
            throw new FatalProcessingException(ABEND_CODE, ABEND_CULPRIT, "STAGE LAUNCH FAILED",
                    "Pipeline stage " + stageName + " could not be launched.", unexpected);
        }
    }

    /**
     * Maps one child execution onto one of the four return codes.
     *
     * <p>The order of the tests is the contract, not a convenience:
     *
     * <ul>
     *   <li><b>12 first.</b> An abend is also a failure, so testing it second would report every abend as an
     *       8. The test is for a {@link FatalProcessingException} among the child's failures, which works for
     *       all five stages; only two of them relabel their own exit status to
     *       {@value #EXIT_CODE_ABEND}, so the exit code alone would miss the other three.</li>
     *   <li><b>8 next.</b> An unsuccessful batch status, or a failed exit code.</li>
     *   <li><b>4 next.</b> The reject count exceeded zero, read from the entry the posting stage publishes,
     *       which is the direct realisation of {@code app/cbl/CBTRN02C.cbl:L229-L231}. The published exit code
     *       corroborates it; the count is preferred because it is the condition the source actually tests.</li>
     *   <li><b>0 otherwise.</b> The normal outcome.</li>
     * </ul>
     *
     * @param child the finished child execution
     * @return 0, 4, 8 or 12
     */
    private static int stageReturnCode(final JobExecution child) {
        final String exitCode = child.getExitStatus().getExitCode();
        if (containsAbend(child) || EXIT_CODE_ABEND.equals(exitCode)) {
            return RETURN_CODE_ABEND;
        }
        if (child.getStatus().isUnsuccessful() || EXIT_CODE_FAILED.equals(exitCode)) {
            return RETURN_CODE_FAILED;
        }
        if (rejectCount(child) > 0L || EXIT_CODE_COMPLETED_WITH_REJECTS.equals(exitCode)) {
            return RETURN_CODE_COMPLETED_WITH_REJECTS;
        }
        return RETURN_CODE_COMPLETED;
    }

    /**
     * Reports whether an abend was recorded anywhere in a child execution.
     *
     * <p>Both the job's own failures and every step's are inspected, because Spring Batch records a step
     * failure on the step and, depending on how it propagated, may or may not also record it on the job.
     *
     * @param child the finished child execution
     * @return {@code true} when a {@link FatalProcessingException} appears among any of those failures
     */
    private static boolean containsAbend(final JobExecution child) {
        for (final Throwable failure : child.getAllFailureExceptions()) {
            if (failure instanceof FatalProcessingException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the reject count a child published, or zero when it published none.
     *
     * <p>Only the posting stage publishes the entry, and only it can reject a record, so the absence of the
     * entry in the other four is the correct answer rather than a gap.
     *
     * @param child the finished child execution
     * @return the reject count, never negative
     */
    private static long rejectCount(final JobExecution child) {
        return child.getExecutionContext()
                .getLong(DailyTransactionPostingJob.REJECT_COUNT_CONTEXT_ENTRY, 0L);
    }

    /**
     * Carries every failure a child recorded onto the pipeline execution, and logs it with its stack trace.
     *
     * <p>This is what keeps a failed stage's root cause visible from the pipeline rather than flattened into
     * a return code. The framework's nested job step would have surfaced a cause-less wrapper instead, which
     * is the specific reason this class launches through the launcher.
     *
     * @param pipeline the pipeline execution
     * @param child the finished child execution
     * @param stageName the stage's display name, for the diagnostics
     */
    private static void promoteFailures(final JobExecution pipeline, final JobExecution child,
            final String stageName) {

        for (final Throwable failure : child.getAllFailureExceptions()) {
            LOG.error("Pipeline stage {} recorded a failure", stageName, failure);
            pipeline.addFailureException(failure);
        }
    }

    /**
     * Records one stage's outcome and updates the running aggregate.
     *
     * <p>The aggregate is the highest return code any stage has reported, so a stage that rejected records is
     * still remembered once a later stage has completed cleanly, and a failure is never masked by a success
     * that follows it. Every gate reads the aggregate, which is why the final gate can distinguish all four
     * outcomes without any stage having to repeat itself.
     *
     * <p><b>Thread safety.</b> Stage 4's two branches call this concurrently, so the update is guarded. See
     * the comment on the synchronised block for why an unguarded read-modify-write would lose a failure.
     *
     * @param pipeline the pipeline execution
     * @param infix the execution-context infix for this stage
     * @param returnCode this stage's return code
     * @param child the finished child execution
     */
    private static void recordStage(final JobExecution pipeline, final String infix, final int returnCode,
            final JobExecution child) {

        final ExecutionContext context = pipeline.getExecutionContext();

        // The two stage-4 branches call this from different threads, so raising the aggregate is a
        // read-modify-write that two threads can interleave: both could read the same prior value and the
        // later write would erase the higher of the two return codes, silently turning a failed branch into a
        // clean pipeline. Holding the shared context's own monitor makes the whole update atomic. The monitor
        // is the right one because every stage of a run - sequential or parallel - records against this exact
        // instance, and the framework itself guards its end states the same way.
        synchronized (context) {
            context.putInt(CONTEXT_PREFIX + infix + RETURN_CODE_SUFFIX, returnCode);
            context.putString(CONTEXT_PREFIX + infix + EXIT_CODE_SUFFIX, child.getExitStatus().getExitCode());
            context.putLong(CONTEXT_PREFIX + infix + EXECUTION_ID_SUFFIX,
                    child.getId() == null ? 0L : child.getId().longValue());

            final int aggregate = Math.max(readAggregateReturnCode(context), returnCode);
            context.putInt(PIPELINE_RETURN_CODE_CONTEXT_ENTRY, aggregate);
            context.putString(PIPELINE_OUTCOME_CONTEXT_ENTRY, outcomeFor(aggregate));
        }
    }

    /**
     * Reads the aggregate return code out of a pipeline execution context under a defined policy.
     *
     * <p>Every read of {@value #PIPELINE_RETURN_CODE_CONTEXT_ENTRY} goes through here, so the three cases the
     * entry can be in have one answer each rather than one answer per call site.
     *
     * <ul>
     *   <li><b>Absent</b> - {@value #RETURN_CODE_COMPLETED}. The entry is written by
     *       {@link #recordStage(JobExecution, String, int, JobExecution)} as each stage finishes, so its
     *       absence means no stage has finished yet, which is a clean run so far and not an unknown one.</li>
     *   <li><b>Present and an integer</b> - returned unchanged, <em>including</em> a value that is not one of
     *       the four canonical codes. Interpreting it is {@link #gateFor(int)}'s job and not this method's;
     *       coercing it to a canonical code here would discard the very information the banding needs.</li>
     *   <li><b>Present and not an integer</b> - an abend. A context entry of the wrong type cannot be banded,
     *       and the two alternatives are both wrong: {@code getInt} raises a bare
     *       {@link ClassCastException} from inside a listener, where it surfaces without naming the key or the
     *       pipeline, and defaulting to {@value #RETURN_CODE_COMPLETED} would report a clean run over a
     *       context that has been corrupted or written by something that is not this class. Only this class
     *       writes the entry, so reaching this arm means the execution context is not the one this pipeline
     *       built - a manipulated restart, or a second writer - and that is exactly an unexpected state, which
     *       the corpus answers with the {@link #ABEND_CODE} abend and return code 12.</li>
     * </ul>
     *
     * @param context the pipeline execution context; must not be {@code null}
     * @return the aggregate return code, 0 when no stage has recorded one yet
     * @throws FatalProcessingException if the entry is present but is not an integer
     */
    private static int readAggregateReturnCode(final ExecutionContext context) {
        final Object recorded = context.get(PIPELINE_RETURN_CODE_CONTEXT_ENTRY);
        if (recorded == null) {
            return RETURN_CODE_COMPLETED;
        }
        if (recorded instanceof Integer aggregate) {
            return aggregate.intValue();
        }
        throw new FatalProcessingException(ABEND_CODE, ABEND_CULPRIT, "CORRUPT PIPELINE RETURN CODE",
                "The pipeline execution context holds a non-integer under "
                        + PIPELINE_RETURN_CODE_CONTEXT_ENTRY + ": " + recorded.getClass().getName()
                        + ". Only this class writes that entry, so the context is not the one this run built.");
    }

    /**
     * The worst return code any stage of this pipeline has ever recorded for itself, across every execution of
     * the instance.
     *
     * <p><strong>Why this exists: the restart that walked through a halt (finding P5-01, Critical).</strong> A
     * pipeline that halted at {@code POSTTRAN} with return code 8, or abended at {@code INTCALC} with 12, ends
     * in a failed flow state. Restarting the same instance produces a fresh {@link JobExecution} whose
     * execution context the job repository copies forward from the previous one - so the halt is on the new
     * execution from the outset - and two things then conspired to erase it. The listener seeded the aggregate
     * unconditionally, overwriting the carried value with zero; and the launcher step that had already
     * completed was skipped by the framework, so {@link #recordStage(JobExecution, String, int, JobExecution)}
     * never ran again and never re-derived it. Both gate inputs therefore read a clean run, the first gate said
     * {@value #GATE_PROCEED}, and every downstream stage launched over data that had already been posted. The
     * observed damage was interest applied a second time and a set of duplicate transaction identifiers.
     *
     * <p><strong>Why the per-stage entries are the durable record.</strong> They are written by the same
     * synchronised block that maintains the aggregate and are never rewritten by the listener, so they survive
     * a restart intact even where the aggregate does not. Reading them makes a gate's decision a function of
     * <em>what every stage of this instance actually did</em> rather than of what the current execution
     * happened to re-run. A completed stage's own outcome cannot be lost by its step being skipped, which is
     * exactly the hole the finding fell through.
     *
     * <p>A missing entry contributes nothing - it means that stage has not run yet on any execution, which is a
     * clean run so far and not an unknown one. A non-integer entry contributes nothing here either, and is left
     * to {@link #readAggregateReturnCode(ExecutionContext)} to abend on, so a corrupt context produces one
     * report rather than two.
     *
     * @param context the pipeline execution context, read under the caller's hold on its monitor; must not be
     *     {@code null}
     * @return the highest per-stage return code present, {@value #RETURN_CODE_COMPLETED} when none is
     */
    private static int recordedStageReturnCode(final ExecutionContext context) {
        int worst = RETURN_CODE_COMPLETED;
        for (final String infix : STAGE_INFIXES) {
            final Object recorded = context.get(CONTEXT_PREFIX + infix + RETURN_CODE_SUFFIX);
            if (recorded instanceof Integer stageCode) {
                worst = Math.max(worst, stageCode.intValue());
            }
        }
        return worst;
    }

    /**
     * Maps a return code onto the gate outcome the flow transitions on.
     *
     * <p><b>The mapping is a monotone severity band, not a lookup of four values, and that is deliberate.</b>
     * The four codes the stages produce are 0, 4, 8 and 12, but the aggregate is a {@link Math#max} over
     * whatever every stage recorded, and a restarted or externally seeded execution context can carry a value
     * between them. Each test is therefore {@code >=} against the floor of a band, which gives every integer
     * a defined outcome:
     *
     * <ul>
     *   <li>12 and above - abend. A code worse than the worst named one is not better than it.</li>
     *   <li>8 to 11 - halt. This is the band a value of 9, 10 or 11 lands in.</li>
     *   <li>4 to 7 - proceed with rejects. This is the band a value of 5, 6 or 7 lands in.</li>
     *   <li>Below 4, negatives included - proceed.</li>
     * </ul>
     *
     * <p>This is the same shape as the job control it replaces: {@code COND=(0,NE)} tests a <em>threshold</em>
     * against a step's completion code rather than enumerating the codes a program is known to set, so an
     * unforeseen code is gated by where it falls rather than by whether it was anticipated. Banding upward -
     * treating 5 as an 8, say - would be the unsafe direction in the other sense: it would halt a stream the
     * source would have continued. Banding downward within a severity level, which is what this does, keeps
     * the meaning of the named code that the value has reached and no more.
     *
     * @param returnCode any integer; the four canonical values are 0, 4, 8 and 12
     * @return one of {@value #GATE_PROCEED}, {@value #GATE_PROCEED_WITH_REJECTS}, {@value #GATE_HALT} or
     *     {@value #GATE_ABEND}
     */
    private static String gateFor(final int returnCode) {
        if (returnCode >= RETURN_CODE_ABEND) {
            return GATE_ABEND;
        }
        if (returnCode >= RETURN_CODE_FAILED) {
            return GATE_HALT;
        }
        if (returnCode >= RETURN_CODE_COMPLETED_WITH_REJECTS) {
            return GATE_PROCEED_WITH_REJECTS;
        }
        return GATE_PROCEED;
    }

    /**
     * Maps a return code onto the exit code the pipeline publishes for it.
     *
     * <p>Banded on the same thresholds and for the same reasons as {@link #gateFor(int)}, so the gate the flow
     * transitions on and the exit status the run publishes can never disagree about a value between two
     * canonical codes.
     *
     * @param returnCode any integer; the four canonical values are 0, 4, 8 and 12
     * @return one of {@code COMPLETED}, {@value #EXIT_CODE_COMPLETED_WITH_REJECTS}, {@code FAILED} or
     *     {@value #EXIT_CODE_ABEND}
     */
    private static String outcomeFor(final int returnCode) {
        if (returnCode >= RETURN_CODE_ABEND) {
            return EXIT_CODE_ABEND;
        }
        if (returnCode >= RETURN_CODE_FAILED) {
            return EXIT_CODE_FAILED;
        }
        if (returnCode >= RETURN_CODE_COMPLETED_WITH_REJECTS) {
            return EXIT_CODE_COMPLETED_WITH_REJECTS;
        }
        return EXIT_CODE_COMPLETED;
    }


    // The generation handoff between stages 2 and 3. app/jcl/INTCALC.jcl:L37-L41 writes SYSTRAN(+1) and
    // app/jcl/COMBTRAN.jcl:L25-L26 reads SYSTRAN(0); because those are two separate jobs here, the
    // generation is pinned explicitly, handed over as a job parameter before stage 3 launches, and only
    // then checked - never re-resolved by stage 3 as "whatever is newest".

    /**
     * Copies the {@code SYSTRAN} generation keys stage 2 created into the pipeline execution context and pins
     * the generation stage 3 must read.
     *
     * <p>{@link InterestCalculationJob} publishes the generation prefix it allocated, a count, and one indexed
     * entry per key it wrote beneath that prefix. What stage 3 must read is the equivalent of
     * {@code SYSTRAN(0)} - the current <em>generation</em>, which the object-store convention defines as the
     * greatest existing generation prefix. The prefix stage 2 recorded is taken verbatim, so no comparison,
     * collator or locale is involved at all and no fraction of the dataset can be pinned by mistake: stage 2
     * emits one object per chunk, and a single key is one chunk of the generation rather than the generation.
     *
     * <p>Publishing zero keys is a legitimate outcome: a run in which every applicable rate was zero generates
     * no interest transaction and therefore no object. The generation prefix is still recorded and still
     * pinned, and stage 3 reads it as zero records - the object-store counterpart of
     * {@code DISP=(NEW,CATLG,DELETE)} cataloguing an empty dataset. Only a stage 2 that recorded no prefix at
     * all is {@value #HANDOFF_ABSENT}, in which case nothing is handed over and stage 3 resolves
     * {@code SYSTRAN(0)} for itself, failing allocation when nothing is catalogued.
     *
     * @param pipeline the pipeline execution
     * @param child stage 2's finished execution
     */
    private static void publishSystranGeneration(final JobExecution pipeline, final JobExecution child) {
        final ExecutionContext childContext = child.getExecutionContext();
        final int published = Math.toIntExact(childContext.getLong(
                InterestCalculationJob.SYSTRAN_GENERATION_KEYS_COUNT_CONTEXT_ENTRY, 0L));

        final List<String> keys = new ArrayList<>(Math.max(published, 0));
        for (int index = 0; index < published; index++) {
            final String key = childContext.getString(
                    InterestCalculationJob.SYSTRAN_GENERATION_KEYS_INDEX_PREFIX + index, "");
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }

        final ExecutionContext context = pipeline.getExecutionContext();
        context.putInt(SYSTRAN_KEY_COUNT_CONTEXT_ENTRY, keys.size());
        for (int index = 0; index < keys.size(); index++) {
            context.putString(SYSTRAN_KEY_INDEX_CONTEXT_PREFIX + index, keys.get(index));
        }

        // The generation, not the greatest key. Stage 2 emits one object per chunk under one generation
        // prefix, so the prefix is the whole dataset and a single key is one chunk of it: pinning a key would
        // hand stage 3 a fraction of SYSTRAN(0) and report success. Stage 2 publishes the prefix it owns even
        // when it emitted nothing, which is the counterpart of DISP=(NEW,CATLG,DELETE) cataloguing an empty
        // dataset, so an empty generation is still pinned and still read - as zero records.
        final String generation = childContext.getString(
                InterestCalculationJob.SYSTRAN_GENERATION_PREFIX_CONTEXT_ENTRY, "");
        if (generation.isEmpty()) {
            context.putString(SYSTRAN_GENERATION_CONTEXT_ENTRY, HANDOFF_ABSENT);
            LOG.warn("Pipeline stage {} published no SYSTRAN generation prefix; app/jcl/COMBTRAN.jcl:L25-L26"
                    + " will have to resolve SYSTRAN(0) by listing", STAGE_INTCALC);
            return;
        }
        context.putString(SYSTRAN_GENERATION_CONTEXT_ENTRY, generation);
        LOG.info("Pipeline pinned the SYSTRAN generation {} holding {} object(s) created by stage {} for"
                        + " stage {} to read",
                generation, Integer.valueOf(keys.size()), STAGE_INTCALC, STAGE_COMBTRAN);
    }

    /**
     * Verifies, as defence in depth, that stage 3 read the exact generation stage 2 created.
     *
     * <p><strong>This is no longer the only guard, and that is the point.</strong> The generation is handed to
     * stage 3 as a job parameter before it launches - see {@link #stageParameters} - so stage 3 cannot resolve
     * a different one. This check confirms that the contract was obeyed and turns any divergence into a named
     * abend rather than a silent latest-wins, but a check that runs after the load has committed could never
     * have prevented the wrong generation being loaded, which is why the pre-launch parameter exists.
     *
     * <p>The comparison is generation against generation. Stage 3 records the concrete object key it opened
     * first, and its generation segment is that key's leading component beneath the base prefix, so the pinned
     * generation is a prefix of the resolved key exactly when the contract held.
     *
     * <p>Three outcomes are not failures and are recorded as such. {@value #HANDOFF_ABSENT} means stage 2
     * created no generation. {@value #HANDOFF_NOT_APPLICABLE} means stage 3 recorded no resolved key, which
     * is what happens when it reads the relational substrate instead of the object store, or when the reader
     * carried an absence forward under its own marker. {@value #HANDOFF_VERIFIED} is the expected outcome.
     *
     * @param pipeline the pipeline execution
     * @param child stage 3's finished execution
     * @return {@value #RETURN_CODE_ABEND} on a mismatch, and {@value #RETURN_CODE_COMPLETED} otherwise, so
     *     the caller can raise the stage's return code without lowering it
     */
    private static int verifySystranHandoff(final JobExecution pipeline, final JobExecution child) {
        final ExecutionContext context = pipeline.getExecutionContext();
        final String pinned = context.getString(SYSTRAN_GENERATION_CONTEXT_ENTRY, "");
        final String resolved = resolvedSystranKey(child);
        context.putString(SYSTRAN_RESOLVED_CONTEXT_ENTRY, resolved == null ? "" : resolved);

        if (pinned.isEmpty() || HANDOFF_ABSENT.equals(pinned)) {
            // An instructed absence is CHECKED, not merely accepted. Stage 3 was handed the reader's absent
            // marker, so the outcome it must record is that same absence; anything else means it read a
            // generation - necessarily one this pipeline did not produce - and that is the latest-wins
            // substitution the pinning exists to prevent. Accepting this arm unconditionally, as the previous
            // implementation did, made the one case where a stale generation could be read the one case that
            // was never verified.
            if (resolved != null && !resolved.isEmpty()
                    && !READER_ABSENT_GENERATION_MARKER.equals(resolved)) {
                context.putString(SYSTRAN_HANDOFF_CONTEXT_ENTRY, HANDOFF_MISMATCH);
                final FatalProcessingException substituted = new FatalProcessingException(
                        ABEND_CODE, ABEND_CULPRIT, "SYSTRAN GENERATION HANDOFF MISMATCH",
                        "Stage " + STAGE_INTCALC + " catalogued no SYSTRAN generation, so stage "
                                + STAGE_COMBTRAN + " was instructed to read none, but it read " + resolved
                                + "; app/jcl/COMBTRAN.jcl:L25-L26 must read the generation the preceding"
                                + " stage wrote, and reading an earlier one would load transactions this"
                                + " pipeline did not generate.");
                LOG.error("Pipeline generation handoff {}", HANDOFF_MISMATCH, substituted);
                pipeline.addFailureException(substituted);
                return RETURN_CODE_ABEND;
            }
            context.putString(SYSTRAN_HANDOFF_CONTEXT_ENTRY, HANDOFF_ABSENT);
            LOG.info("Pipeline generation handoff {}: stage {} created no SYSTRAN generation, and stage {}"
                            + " read none - the absence was instructed and observed",
                    HANDOFF_ABSENT, STAGE_INTCALC, STAGE_COMBTRAN);
            return RETURN_CODE_COMPLETED;
        }
        if (resolved == null || resolved.isEmpty() || READER_ABSENT_GENERATION_MARKER.equals(resolved)) {
            context.putString(SYSTRAN_HANDOFF_CONTEXT_ENTRY, HANDOFF_NOT_APPLICABLE);
            LOG.info("Pipeline generation handoff {}: stage {} recorded no resolved SYSTRAN key, so the"
                    + " pinned key {} could not be compared", HANDOFF_NOT_APPLICABLE, STAGE_COMBTRAN, pinned);
            return RETURN_CODE_COMPLETED;
        }
        if (!resolved.startsWith(pinned)) {
            context.putString(SYSTRAN_HANDOFF_CONTEXT_ENTRY, HANDOFF_MISMATCH);
            final FatalProcessingException mismatch = new FatalProcessingException(
                    ABEND_CODE, ABEND_CULPRIT, "SYSTRAN GENERATION HANDOFF MISMATCH",
                    "Stage " + STAGE_INTCALC + " created SYSTRAN generation " + pinned + " but stage "
                            + STAGE_COMBTRAN + " read " + resolved
                            + "; app/jcl/COMBTRAN.jcl:L25-L26 must read the generation the preceding stage"
                            + " wrote, exactly as app/jcl/COMBTRAN.jcl:L43-L44 re-references the generation"
                            + " its own preceding step created.");
            LOG.error("Pipeline generation handoff {}", HANDOFF_MISMATCH, mismatch);
            pipeline.addFailureException(mismatch);
            return RETURN_CODE_ABEND;
        }
        context.putString(SYSTRAN_HANDOFF_CONTEXT_ENTRY, HANDOFF_VERIFIED);
        LOG.info("Pipeline generation handoff {}: stage {} read the generation {} that stage {} created",
                HANDOFF_VERIFIED, STAGE_COMBTRAN, resolved, STAGE_INTCALC);
        return RETURN_CODE_COMPLETED;
    }

    /**
     * Finds the {@code SYSTRAN} key stage 3 recorded that it resolved.
     *
     * <p>The combined-transaction reader records it in the step execution context of whichever step opened
     * it. Step executions are visited in creation order, which is deterministic, and the first recorded value
     * is returned because one stage has one such reader.
     *
     * @param child stage 3's finished execution
     * @return the recorded key, or {@code null} when no step recorded one
     */
    private static String resolvedSystranKey(final JobExecution child) {
        for (final StepExecution stepExecution : child.getStepExecutions()) {
            final ExecutionContext stepContext = stepExecution.getExecutionContext();
            if (stepContext.containsKey(READER_SYSTRAN_OBJECT_KEY_ENTRY)) {
                return stepContext.getString(READER_SYSTRAN_OBJECT_KEY_ENTRY, "");
            }
        }
        return null;
    }

    // Constructor guards.

    /**
     * Rejects a null collaborator at construction time, so a mis-wired context fails at startup rather than
     * at the first launch.
     *
     * @param <T> the collaborator's type
     * @param value the injected collaborator
     * @param name the collaborator's name, for the diagnostic
     * @return {@code value} unchanged
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    private static <T> T requireCollaborator(final T value, final String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must be injected and was null");
        }
        return value;
    }

    /**
     * Rejects a blank configured value.
     *
     * @param value the injected value
     * @param property the property name, for the diagnostic
     * @return {@code value} unchanged
     * @throws IllegalArgumentException if {@code value} is {@code null} or blank
     */
    private static String requireConfiguredText(final String value, final String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(property + " must be configured with a non-blank value");
        }
        return value;
    }

    // The gate. One class, four instances, four outcomes each - which is what makes the return-code
    // contract of app/cbl/CBTRN02C.cbl:L229-L231 a property of the topology.

    /**
     * One stage whose own recorded outcome a gate reports in its diagnostic.
     *
     * <p>Pairs the display name a stage line spells with the execution-context infix
     * {@link BatchPipelineOrchestrator#recordStage(JobExecution, String, int, JobExecution)} writes that
     * stage's outcome under, so a gate can quote the stage's own return code instead of the pipeline
     * aggregate. Carrying both explicitly rather than deriving one from the other keeps the two spellings
     * independent: the infix is a persisted key that must never change silently, and the name is prose.
     *
     * @param name the stage's display name, spelled exactly as its {@code PIPELINE STAGE} log lines spell it
     * @param infix the execution-context infix that stage's outcome is recorded under
     */
    private record GatedStage(String name, String infix) {
    }

    /**
     * The replacement for the {@code COND=} gating a JCL job stream would use between steps.
     *
     * <p>It reads the running aggregate return code and returns one of four outcomes. Two of them lead to the
     * next stage, so <b>return code 4 proceeds</b>; the other two lead to a failure end state.
     *
     * <p>A plain nested object rather than a bean, deliberately. A bean here would collide with any decider
     * a configuration class declares and with the inline deciders the sibling jobs already hold, and it would
     * add a container singleton this package is not permitted to contribute. Four instances are constructed
     * rather than one shared object because the flow builder keys a decision state on the decider instance,
     * so one object would merge all four decision points into a single state.
     *
     * <p>Static and stateless apart from two immutable labels, so it cannot accumulate anything across runs.
     */
    private static final class StageGateDecider implements JobExecutionDecider {

        /** The stage, or stage pair, this gate sits after. Used only in the diagnostic. */
        private final String afterStage;

        /** The {@code path:line} of the JCL step or steps that stage replaces. Used only in the diagnostic. */
        private final String locator;

        /**
         * The stages whose own recorded return codes this gate reports. Used only in the diagnostic.
         *
         * <p>One entry for the three sequential gates and two for the gate after the stage-4 split, in the
         * order the split declares its branches, so the sentence a gate logs names the same stages in the
         * same order as the stage lines immediately above it.
         */
        private final List<GatedStage> gatedStages;

        /**
         * Creates a gate.
         *
         * @param afterStage the stage, or stage pair, this gate sits after
         * @param locator the {@code path:line} of the JCL step or steps that stage replaces
         * @param gatedStages the stages whose own recorded return codes this gate reports, in declaration
         *     order; copied defensively, so the caller may pass a mutable list
         */
        private StageGateDecider(final String afterStage, final String locator,
                final List<GatedStage> gatedStages) {
            this.afterStage = afterStage;
            this.locator = locator;
            this.gatedStages = List.copyOf(gatedStages);
        }

        /**
         * Decides what the pipeline does next.
         *
         * <p>The aggregate is read from {@value BatchPipelineOrchestrator#PIPELINE_RETURN_CODE_CONTEXT_ENTRY}
         * rather than from the step's exit status, because the exit status of a split is an aggregation the
         * framework performs by its own ordering rules and those rules are incidental rather than
         * contractual. Reading the recorded code makes every outcome independent of them.
         *
         * <p>The recorded code alone is not sufficient, though, because it is written by the stage runner: a
         * launcher step that failed before reaching it would leave it untouched and this gate would read a
         * clean run. So the step executions are inspected too, and the higher of the two codes wins. That is
         * what makes it impossible for any gate to proceed over a failed step, whatever the failure was.
         *
         * <p><strong>What the diagnostic reports, and why it reports two numbers.</strong> The decision is
         * the aggregate and must stay the aggregate for the reason just given, but the aggregate is a
         * property of <em>the run so far</em>, not of the stage this gate is named after. Reporting only the
         * aggregate told an operator that {@code INTCALC}, {@code COMBTRAN}, {@code CREASTMT} and
         * {@code TRANREPT} had produced rejects on a run where only {@code POSTTRAN} can - it is the sole
         * stage with a reject concept at all, per {@code app/cbl/CBTRN02C.cbl:L229-L231} - and it contradicted
         * the {@code END OF PIPELINE STAGE} line printed moments earlier on the same thread. So both numbers
         * are logged and each is attributed to what it actually describes: the stage's own recorded return
         * code, read back from the entry {@link #recordStage(JobExecution, String, int, JobExecution)} wrote
         * for it, and the pipeline aggregate that produced the decision. Nothing about the control flow
         * changes; only the sentence does.
         *
         * @param jobExecution the running pipeline execution, never {@code null}
         * @param stepExecution the step that has just finished; may be {@code null} after a split
         * @return the gate outcome, never {@code null}
         */
        @Override
        public FlowExecutionStatus decide(final JobExecution jobExecution,
                final StepExecution stepExecution) {

            final ExecutionContext context = jobExecution.getExecutionContext();
            final int aggregate;
            final String stageReturnCodes;
            synchronized (context) {
                final int recorded = readAggregateReturnCode(context);
                final int implied = failedStepReturnCode(jobExecution);
                // The third input, and the one that makes a halt survive a restart. A stage that completed on
                // an earlier execution of this instance has its own recorded return code in the carried-forward
                // context, but its launcher step is skipped on the restart so neither of the two inputs above
                // can see it: the aggregate is only as good as whatever last wrote it, and the step scan only
                // sees the current execution's steps. Folding the durable per-stage entries in is what closes
                // finding P5-01 - see recordedStageReturnCode for the damage that hole caused.
                final int persisted = recordedStageReturnCode(context);
                aggregate = Math.max(Math.max(recorded, implied), persisted);
                if (aggregate > recorded) {
                    // Two ways to get here, and the sentence names which.
                    //  - A launcher step that died before it could record its stage - an unusable transaction
                    //    manager, an interrupted thread, an error thrown outside the stage runner - leaves the
                    //    recorded aggregate untouched, so without this a gate would read a clean run and
                    //    proceed over a failed step.
                    //  - A restart of an instance that had already halted, where the durable stage entries are
                    //    worse than whatever the aggregate now holds.
                    // Either way the raised value is written back, so every later gate and the closing listener
                    // read the same figure rather than each rediscovering it.
                    context.putInt(PIPELINE_RETURN_CODE_CONTEXT_ENTRY, aggregate);
                    context.putString(PIPELINE_OUTCOME_CONTEXT_ENTRY, outcomeFor(aggregate));
                    LOG.error("Pipeline gate after {} ({}) raised the aggregate return code from {} to {}:"
                            + " worst failed launcher step {}, worst persisted stage outcome {}",
                            afterStage, locator, Integer.valueOf(recorded), Integer.valueOf(aggregate),
                            Integer.valueOf(implied), Integer.valueOf(persisted));
                }
                stageReturnCodes = renderStageReturnCodes(context);
            }
            final String gate = gateFor(aggregate);
            LOG.info("Pipeline gate after {} ({}) read stage return code {} and pipeline aggregate return"
                    + " code {}, and decided {}",
                    afterStage, locator, stageReturnCodes, Integer.valueOf(aggregate), gate);
            return new FlowExecutionStatus(gate);
        }

        /**
         * Renders the return codes the gated stages recorded for themselves.
         *
         * <p>Read under the caller's hold on the context monitor, for the same reason the aggregate is: the
         * two stage-4 branches record concurrently, so an unguarded read could see one branch's entry and
         * miss the other's and report a half-finished split as though it were the whole of it.
         *
         * <p>A single gated stage renders as the bare number, because naming the stage twice in one sentence
         * reads worse than not naming it at all. The stage pair renders as {@code NAME=code} per branch so the
         * two are distinguishable. A stage whose entry is missing renders as {@code absent} rather than as a
         * fabricated zero - the only way the entry can be missing is the launcher-step failure the surrounding
         * block has just escalated, and reporting that as a clean stage is precisely the misattribution this
         * method exists to remove.
         *
         * @param context the pipeline execution context, already held by the caller
         * @return the rendering, never {@code null} and never blank
         */
        private String renderStageReturnCodes(final ExecutionContext context) {
            final List<String> rendered = new ArrayList<>(gatedStages.size());
            for (final GatedStage stage : gatedStages) {
                final Object recorded = context.get(CONTEXT_PREFIX + stage.infix() + RETURN_CODE_SUFFIX);
                final String code = recorded instanceof Integer stageCode ? stageCode.toString() : "absent";
                rendered.add(gatedStages.size() == 1 ? code : stage.name() + "=" + code);
            }
            return String.join(", ", rendered);
        }

        /**
         * The return code implied by any launcher step of this pipeline that did not succeed.
         *
         * <p>Scanning every step execution rather than only the one just finished is deliberate: after
         * {@code app/jcl/CREASTMT.JCL} and {@code app/proc/TRANREPT.prc} have run as the stage-4 split, the
         * decider is handed no single step, so the two branch steps are only reachable this way. It is also
         * cheap and monotonic, since a pipeline has exactly five step executions.
         *
         * @param pipeline the running pipeline execution
         * @return {@value #RETURN_CODE_ABEND} if a failed step carries a {@link FatalProcessingException},
         *     {@value #RETURN_CODE_FAILED} if any step failed or was stopped, otherwise
         *     {@value #RETURN_CODE_COMPLETED}
         */
        private static int failedStepReturnCode(final JobExecution pipeline) {
            int worst = RETURN_CODE_COMPLETED;
            for (final StepExecution step : pipeline.getStepExecutions()) {
                final BatchStatus status = step.getStatus();
                if (!status.isUnsuccessful() && status != BatchStatus.STOPPED) {
                    continue;
                }
                int implied = RETURN_CODE_FAILED;
                for (final Throwable failure : step.getFailureExceptions()) {
                    if (failure instanceof FatalProcessingException) {
                        implied = RETURN_CODE_ABEND;
                        break;
                    }
                }
                worst = Math.max(worst, implied);
            }
            return worst;
        }
    }

    // The diagnostic context. The HTTP correlation filter is request scoped and never sees a batch run,
    // and the observability package is not permitted a job listener, so the identifiers are established
    // here and released here.

    /**
     * Establishes the pipeline's diagnostic context, seeds the aggregate, and publishes the abend exit code.
     *
     * <p>An inner class rather than a static one because it logs {@link BatchPipelineOrchestrator#jobName},
     * and a plain object rather than a bean for the same reasons as {@link StageGateDecider}. It holds no
     * state of its own: every value it needs comes from the execution, and the two diagnostic values it
     * displaces are remembered by {@link CorrelationIdFilter#enterBatchScope(long, String)} on the thread
     * that displaced them - the only scope that can correctly own them - rather than in a field of this
     * listener or an entry of the execution context, so nothing survives a run and nothing about a caller's
     * identity is persisted into the batch metastore.
     */
    private final class BatchPipelineJobListener implements JobExecutionListener {

        /** Creates the listener. Stateless: see the class documentation. */
        private BatchPipelineJobListener() {
            // No state, by construction.
        }

        /**
         * {@inheritDoc}
         *
         * <p>Opens a batch scope, which parks whatever diagnostic values an outer scope had established and
         * puts the pipeline's own in their place, then seeds the aggregate return code so the first gate reads
         * a value rather than a default.
         *
         * <p><strong>The seed is written only when the entry is absent (finding P5-01, Critical).</strong> It
         * used to be written unconditionally, and on a first execution that is indistinguishable - the entry is
         * absent, so seeding it with {@value #RETURN_CODE_COMPLETED} is exactly right. On a
         * <em>restart</em> it was not: the job repository copies the previous execution's context forward, so
         * the halt this instance had already recorded was present on the new execution from the outset, and
         * overwriting it with zero told the first gate the run was clean. Combined with the framework skipping
         * the launcher step that had recorded the halt, that let every downstream stage launch over data that
         * had already been posted - interest applied twice and a set of duplicate transaction identifiers.
         *
         * <p>So the persisted aggregate is authoritative and this method never lowers it. A halted instance
         * that is restarted now re-reads its own halt at the first gate and stops there without launching
         * anything, which is the correct outcome: nothing downstream ran the first time and nothing downstream
         * runs now. Recovering from a halt means fixing the data and submitting a <em>new</em> instance, and
         * because {@link #batchPipelineJob(Flow)} deliberately declares no incrementer, doing that is an
         * explicit act with a different parameter set rather than a silent re-run of the same one.
         *
         * <p>The outcome label is seeded on the same condition and for the same reason. Writing a clean label
         * over a carried halt would leave the two entries disagreeing, and the closing log line reads the label.
         *
         * <p>The correlation identifier is minted <b>only</b> when no outer scope owns one, which is what
         * makes a queue-driven run share one identifier with the message that triggered it. Every stage
         * inherits it for the same reason: each sibling listener opens a scope of its own, and a scope mints
         * only into an empty entry.
         *
         * @param jobExecution the starting pipeline execution
         */
        @Override
        public void beforeJob(final JobExecution jobExecution) {
            CorrelationIdFilter.enterBatchScope(
                    instanceIdOf(jobExecution), pipelineCorrelationId(jobExecution));

            final ExecutionContext context = jobExecution.getExecutionContext();
            final boolean restarted;
            final int carried;
            // Guarded on the same monitor every stage and gate uses, so a seed decision cannot interleave with
            // a stage recording its outcome.
            synchronized (context) {
                restarted = context.containsKey(PIPELINE_RETURN_CODE_CONTEXT_ENTRY);
                if (restarted) {
                    // Read through the shared policy, so a context carrying a non-integer abends here with the
                    // key named rather than surfacing as a bare ClassCastException from inside a gate.
                    carried = readAggregateReturnCode(context);
                } else {
                    carried = RETURN_CODE_COMPLETED;
                    context.putInt(PIPELINE_RETURN_CODE_CONTEXT_ENTRY, RETURN_CODE_COMPLETED);
                    context.putString(PIPELINE_OUTCOME_CONTEXT_ENTRY, EXIT_CODE_COMPLETED);
                }
            }
            LOG.info("START OF EXECUTION OF THE CARDDEMO BATCH PIPELINE {} - five stages replacing"
                    + " app/jcl/POSTTRAN.jcl, app/jcl/INTCALC.jcl, app/jcl/COMBTRAN.jcl,"
                    + " app/jcl/CREASTMT.JCL and app/proc/TRANREPT.prc, submitted on the legacy side"
                    + " through {}", jobName, TDQ_DEFINITION);
            if (restarted) {
                LOG.warn("Pipeline {} is a RESTART of an existing instance: the aggregate return code {}"
                        + " recorded by an earlier execution is authoritative and is NOT reset. A stage whose"
                        + " gate already halted will halt again without launching anything",
                        jobName, Integer.valueOf(carried));
            }
        }

        /**
         * {@inheritDoc}
         *
         * <p>Relabels the exit status when the run abended, so the four outcomes are all visible on the
         * execution: an abend keeps its unsuccessful batch status and gains the exit code
         * {@value #EXIT_CODE_ABEND}, which a plain failure does not. Then closes the batch scope in a
         * {@code finally}, so the displaced values are put back even if the relabelling or the logging throws.
         *
         * <p>Nothing here terminates the process. The exit status and the execution context are the report.
         *
         * @param jobExecution the finishing pipeline execution
         */
        @Override
        public void afterJob(final JobExecution jobExecution) {
            try {
                final ExecutionContext context = jobExecution.getExecutionContext();
                final int aggregate = readAggregateReturnCode(context);
                if (aggregate >= RETURN_CODE_ABEND) {
                    jobExecution.setExitStatus(new ExitStatus(EXIT_CODE_ABEND,
                            "Abend code " + ABEND_CODE + " raised by " + ABEND_CULPRIT));
                }
                LOG.info("END OF EXECUTION OF THE CARDDEMO BATCH PIPELINE {} - aggregate return code {},"
                        + " outcome {}, exit status {}",
                        jobName,
                        Integer.valueOf(aggregate),
                        context.getString(PIPELINE_OUTCOME_CONTEXT_ENTRY, EXIT_CODE_COMPLETED),
                        jobExecution.getExitStatus().getExitCode());
            } finally {
                CorrelationIdFilter.exitBatchScope();
            }
        }

        /**
         * The deterministic correlation identifier for one pipeline run.
         *
         * <p>Derived from the execution identifier rather than random, so it can be recomputed by anyone
         * reading a log line and so it is stable for the whole run and for all five stages.
         *
         * @param jobExecution the pipeline execution
         * @return the identifier, never {@code null}
         */
        private String pipelineCorrelationId(final JobExecution jobExecution) {
            return CORRELATION_ID_PREFIX
                    + (jobExecution.getId() == null ? "0" : jobExecution.getId().toString());
        }

        /**
         * The job instance identifier, or zero when the execution carries no instance.
         *
         * @param jobExecution the pipeline execution
         * @return the instance identifier
         */
        private long instanceIdOf(final JobExecution jobExecution) {
            return jobExecution.getJobInstance() == null
                    ? 0L
                    : jobExecution.getJobInstance().getInstanceId();
        }
    }



}
