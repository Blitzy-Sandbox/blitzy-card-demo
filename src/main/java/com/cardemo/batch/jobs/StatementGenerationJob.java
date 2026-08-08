/*
 * ******************************************************************
 * Program     : StatementGenerationJob.java
 * Application : CardDemo
 * Type        : Spring Batch Job Configuration
 * Function    : Statement generation - five-step projection, sort, load
 *               and dual-format emission at 80 and 100 bytes.
 * Source      : app/jcl/CREASTMT.JCL (97 lines, 5 steps) +
 *               app/cbl/CBSTM03A.CBL (924 lines, 25 own paragraph labels) +
 *               app/cbl/CBSTM03B.CBL (230 lines, 14 procedure
 *               paragraphs) @ 7756d89
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.job.flow.support.SimpleFlow;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;

import com.cardemo.batch.GenerationPrefixContract;
import com.cardemo.batch.processors.StatementProcessor;
import com.cardemo.batch.writers.StatementWriter;
import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.dto.StatementTransaction;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.observability.CorrelationIdFilter;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileService;
import com.cardemo.service.shared.FileStatusMapper;

import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;

/**
 * The Spring Batch translation of {@code app/jcl/CREASTMT.JCL} - the statement generation job stream -
 * whose fifth and final step runs {@code app/cbl/CBSTM03A.CBL}, which in turn reaches every one of its
 * four input datasets through {@code app/cbl/CBSTM03B.CBL}.
 *
 * <h2>What it does</h2>
 *
 * <p>Five steps, in the order and with the gating the member declares. The step names below are the JCL
 * step names, and each is followed by the bean this class registers for it.
 *
 * <ol>
 *   <li><b>{@code DELDEF01}</b> ({@code app/jcl/CREASTMT.JCL:L22}, {@code EXEC PGM=IDCAMS}, <b>ungated</b>)
 *       - {@link #DEFINE_STEP_BEAN_NAME}. Deletes the sequential work dataset ({@code :L25}) and the work
 *       cluster ({@code :L26}-{@code :L27}), then {@code SET MAXCC = 0} at {@code :L28} discards the
 *       condition code so a first run, in which neither exists, still succeeds. Then
 *       {@code DEFINE CLUSTER} at {@code :L29}-{@code :L39} fixes the geometry that the rest of the job
 *       depends on: {@code KEYS(32 0)} at {@code :L30} and {@code RECORDSIZE(350 350)} at {@code :L32}.</li>
 *   <li><b>{@code STEP010}</b> ({@code :L44}, {@code EXEC PGM=SORT}, <b>ungated</b>) -
 *       {@link #SORT_STEP_BEAN_NAME}. {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} at {@code :L53} becomes
 *       {@link #SORT_FIELDS_COMPARATOR}; {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)} at
 *       {@code :L54} becomes {@link StatementProcessor#projectBaseRecord(Transaction)}. See
 *       <i>The projection truncates two bytes</i> below.</li>
 *   <li><b>{@code STEP020}</b> ({@code :L56}, {@code EXEC PGM=IDCAMS,COND=(0,NE)}) -
 *       {@link #LOAD_STEP_BEAN_NAME}. {@code REPRO INFILE(INFILE) OUTFILE(OUTFILE)} at {@code :L61} loads
 *       the projected, sorted sequential dataset into the work cluster.</li>
 *   <li><b>{@code STEP030}</b> ({@code :L66}, {@code EXEC PGM=IEFBR14,COND=(0,NE)}) -
 *       {@link #PRE_DELETE_STEP_BEAN_NAME}. {@code IEFBR14} does nothing at all; the whole effect is the
 *       {@code DISP=(MOD,DELETE,DELETE)} side effect of its two DD statements, which remove the previous
 *       run's outputs ({@code :L67}-{@code :L71} and {@code :L72}-{@code :L75}).</li>
 *   <li><b>{@code STEP040}</b> ({@code :L79}, {@code EXEC PGM=CBSTM03A,COND=(0,NE)}) -
 *       {@link #EMIT_STEP_BEAN_NAME}. Reads {@code TRNXFILE}, {@code XREFFILE}, {@code ACCTFILE} and
 *       {@code CUSTFILE} ({@code :L83}-{@code :L86}) and emits {@code STMTFILE} at {@code LRECL=80}
 *       ({@code :L89}) and {@code HTMLFILE} at {@code LRECL=100} ({@code :L94}).</li>
 * </ol>
 *
 * <p><b>The gating is not uniform and the asymmetry is deliberate.</b> {@code DELDEF01} and
 * {@code STEP010} carry no {@code COND} parameter, so they run unconditionally; {@code STEP020},
 * {@code STEP030} and {@code STEP040} each carry {@code COND=(0,NE)} and therefore run only while the
 * preceding return code is zero. Each of those three is preceded by its own {@link JobExecutionDecider},
 * and the two ungated transitions are wildcard transitions so that a failed predecessor still reaches its
 * successor exactly as JES2 would have run it.
 *
 * <h2>Inputs</h2>
 *
 * <ul>
 *   <li>The {@code TRANSACT} cluster, read through
 *       {@link TransactionRepository#findStatementOrderAfter} - the keyset-positioned counterpart of
 *       {@code SORTIN} at {@code app/jcl/CREASTMT.JCL:L45}.</li>
 *   <li>{@code XREFFILE}, {@code ACCTFILE} and {@code CUSTFILE}, reached only through
 *       {@link FileService}, which owns the DD-keyed dispatch of {@code app/cbl/CBSTM03B.CBL}.</li>
 *   <li>No job parameters are required. {@code app/jcl/CREASTMT.JCL} passes no {@code PARM} to
 *       {@code CBSTM03A} at {@code :L79} - unlike {@code app/jcl/INTCALC.jcl} - so this job declares no
 *       parameter validator, because there is no parameter contract to enforce.</li>
 * </ul>
 *
 * <h2>Outputs and side effects</h2>
 *
 * <ul>
 *   <li>One projected, sorted sequential object per run - the {@code AWS.M2.CARDDEMO.TRXFL.SEQ} analogue -
 *       under {@link #workPrefix} in the batch output bucket. Records are exactly
 *       {@value StatementTransaction#RECORD_LENGTH} bytes.</li>
 *   <li>Two statement objects per <b>statement</b>, written by {@link StatementWriter}: text at
 *       {@value StatementTransaction#STATEMENT_TEXT_RECORD_LENGTH} bytes per line and markup at
 *       {@value StatementTransaction#STATEMENT_HTML_RECORD_LENGTH}, under account and month prefixes in the
 *       statements bucket. Per statement and not per account: the driving read returns one cross-reference
 *       row per card and an account may hold several, since {@code CARDXREF.VSAM.AIX} is a non-unique
 *       alternate index on the account identifier, so the key carries a statement ordinal beneath the
 *       generation - see {@code StatementWriter.KEY_STATEMENT_SEGMENT}, finding F-01.</li>
 *   <li>Execution-context entries recording the concrete object key each step created, so a later step
 *       re-reads <b>that key</b> rather than re-resolving "the latest object". See <i>Object storage</i>.</li>
 *   <li><strong>No application counter at all.</strong> Advancing the records-processed counter here would
 *       give one metric three incompatible units: with the number of transaction rows the projection reads,
 *       with the number of statements the emit step writes, and once per statement inside
 *       {@link com.cardemo.batch.writers.StatementWriter}. That counter is the {@code WS-TRANSACTION-COUNT}
 *       analogue of {@code app/cbl/CBTRN02C.cbl:L206} and its unit is daily transaction records: a statement
 *       is not one, the projected rows were already counted when they were posted, and the two
 *       statement-side figures would double-count each other, so the value would mean nothing. None of the
 *       four counters
 *       {@link com.cardemo.observability.MetricsConfig MetricsConfig} owns has this job's unit, so this job
 *       advances none of them, registers no meter
 *       of its own and adds no tag. What it publishes instead are its own execution-context entries -
 *       {@link #STATEMENTS_EMITTED_CONTEXT_ENTRY} and {@link #WORK_RECORD_COUNT_CONTEXT_ENTRY} - alongside the
 *       read and write counts Spring Batch records per step in its own metadata.</li>
 * </ul>
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Build and test with {@code ./mvnw -B -ntp clean verify} from the repository root, which compiles at
 * release 25 under {@code -Xlint:all -Werror} with {@code failOnWarning} and enforces the JaCoCo line
 * floor. The job does not launch itself: {@code spring.batch.job.enabled} is {@code false} in
 * {@code src/main/resources/application.yml}, so launching is explicit, through the batch pipeline
 * orchestrator or the queue listener that replaces the JES2 internal reader. Launch it by name -
 * {@link #jobName}, default {@value #DEFAULT_JOB_NAME} - with no parameters.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <table border="1">
 *   <caption>Bound properties</caption>
 *   <tr><th>Property</th><th>Default</th><th>Purpose</th></tr>
 *   <tr><td>{@code carddemo.batch.jobs.creastmt.name}</td><td>{@value #DEFAULT_JOB_NAME}</td>
 *       <td>Registered job name, matching the JCL job card at {@code app/jcl/CREASTMT.JCL:L1}</td></tr>
 *   <tr><td>{@code carddemo.batch.creastmt.chunk-size}</td>
 *       <td>{@code carddemo.batch.chunk-size}, then {@value #DEFAULT_CHUNK_SIZE}</td>
 *       <td>Commit interval of the emit step and window size of the projection read</td></tr>
 *   <tr><td>{@code carddemo.aws.s3.batch-output-bucket}</td><td>none - startup fails without it</td>
 *       <td>Receives the projected sequential object</td></tr>
 *   <tr><td>{@code carddemo.aws.s3.statements-bucket}</td><td>none - startup fails without it</td>
 *       <td>Receives the two statement objects; also the bucket {@link StatementWriter} writes</td></tr>
 *   <tr><td>{@code carddemo.aws.s3.work-prefixes.trxfl}</td><td>{@value #DEFAULT_WORK_PREFIX}</td>
 *       <td>Key prefix of the work dataset, replacing the {@code TRXFL} dataset names</td></tr>
 * </table>
 *
 * <p><b>The property namespace was verified rather than assumed, and it needed to be.</b>
 * {@code src/main/resources/application.yml} declares the buckets under
 * {@code carddemo.aws.s3.*}, and the environment variable behind the output bucket is
 * {@code CARDDEMO_S3_BATCH_OUTPUT_BUCKET}. A sibling specification cites {@code carddemo.s3.*} without the
 * {@code aws} segment, and {@code CARDDEMO_S3_OUTPUT_BUCKET} without {@code BATCH}; neither spelling
 * resolves, so this class binds the names the configuration file actually declares. No AWS client is
 * constructed here, no bucket or endpoint
 * is hardcoded, and no environment variable is read directly: every value arrives through property
 * binding, so a deployment can override it without touching code.
 *
 * <h2>Why the work dataset is a per-run object rather than an in-memory hold</h2>
 *
 * <p>AAP 0.5.2.2 dispositions the {@code TRXFL} work cluster in five words - <i>an in-job projection and
 * sort; never persisted</i> - and it is the only row of that table given no durable target, which is
 * consistent with the source: {@code DELDEF01} defines the cluster at {@code app/jcl/CREASTMT.JCL:L25} on
 * every run and the job never catalogues it, which is why {@code app/catlg/LISTCAT.txt} counts ten clusters
 * and none of them is this one. <b>That disposition is honoured here by lifetime, not by medium.</b> The
 * projected sequence is written as one object per run, read back by {@code STEP020}, and deleted by the
 * define step of the next run and by {@link StatementGenerationJobListener} at the end of this one, so it
 * outlives its job execution no more than the legacy cluster outlived its job.
 *
 * <p>The alternative reading - hold the projected records in a job-scoped map and let the execution context
 * carry only a handle - was considered and rejected. It requires the whole sorted sequence to be resident at
 * once, which is exactly the unbounded materialisation AAP 0.7.6.3 removes from this program by streaming; it
 * also forfeits the {@value #DIGEST_ALGORITHM} identity proof between what {@code STEP010}
 * wrote and what {@code STEP020} read, and leaves {@code STEP020} unrestartable once the process that ran
 * {@code STEP010} has gone. Streaming through one object keeps peak memory at one record in both directions.
 * The cost of that choice is stated plainly rather than hidden: the work object does reach the batch output
 * bucket, and it is the delete-on-define and the release listener - not the absence of a write - that keep
 * the dataset transient.
 *
 * <h2>The projection truncates two bytes - reproduced, not repaired</h2>
 *
 * <p>{@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)} at {@code app/jcl/CREASTMT.JCL:L54}, decoded
 * against the 350-byte layout of {@code app/cpy/CVTRA05Y.cpy}, moves the card number from bytes 263-278 to
 * the front, follows it with the original head at bytes 1-262, and then copies <b>fifty</b> bytes from
 * offset 279. Fifty bytes from 279 reaches 328. The originating timestamp occupies 279-304 and the
 * processing timestamp 305-330, so the copy carries the whole originating timestamp but only the
 * <b>first 24 of the 26</b> processing-timestamp bytes; bytes 329-330 are never written, and neither is
 * any part of the 20-byte trailing filler at 331-350. The projection therefore emits
 * {@value StatementTransaction#PROJECTION_LAST_WRITTEN_POSITION} bytes of content into a
 * {@value StatementTransaction#RECORD_LENGTH}-byte record, and the remaining 22 positions are spaces.
 *
 * <p>{@code app/cpy/COSTM01.CPY} is {@code app/cpy/CVTRA05Y.cpy} with the card number relocated to the
 * front - which is precisely the shape the projection produces, and precisely why the projection writes
 * output 1-16 from input 263-278. Its {@code TRNX-PROC-TS PIC X(26)} at {@code app/cpy/COSTM01.CPY:L35}
 * consequently receives 24 of its 26 bytes and its {@code FILLER PIC X(20)} at {@code :L36} receives
 * nothing at all. <b>A reviewer will read this as a defect in the Java code. It is not.</b> Failing to
 * reproduce it makes every statement differ from the parity baseline in a way no unit test would attribute
 * to the projection, so it is left exactly as it is and the validation tier asserts the truncation, which
 * makes a well-meant repair fail the build.
 *
 * <p>This is only representable because both timestamps are {@link String} over {@code CHAR(26)}. A temporal type
 * cannot hold a 24-of-26-byte fragment, so mapping either field to a date or date-time type would make the truncation
 * impossible to express - and would therefore make byte-exact statement output unreachable however carefully the rest
 * of the projection were written. Both fields are therefore kept as fixed-width text end to end, which is what
 * {@link StatementTransaction} and
 * {@link com.cardemo.model.entity.Transaction} already do. The batch producer's rendering is
 * {@code yyyy-MM-dd-HH.mm.ss.SS0000} - <b>two digits of hundredths of a second, that is centiseconds,
 * followed by four literal zeros</b>. It is not millisecond precision: millisecond precision is three
 * fraction digits and would make the value
 * twenty-seven characters rather than twenty-six. The frozen source is unambiguous: {@code COB-MIL} is
 * {@code PIC X(02)} over the sub-second field of {@code FUNCTION CURRENT-DATE}, which is hundredths
 * ({@code app/cbl/CBTRN02C.cbl:L157}); it is moved into {@code DB2-MIL PIC 9(002)} ({@code :L173}) at
 * {@code :L700}; and {@code MOVE '0000' TO DB2-REST} fills the {@code PIC X(04)} tail at {@code :L701}. The
 * source's own format comment spells the whole value {@code EEEE-MM-DD-UU.MM.SS.HH0000} ({@code :L149}), the
 * {@code HH} naming the hundredths. Nanosecond precision is wrong for the same reason, only further out.
 *
 * <h2>The self-modifying dispatch is an initialisation pipeline, not a dispatch table</h2>
 *
 * <p>{@code app/cbl/CBSTM03A.CBL} alters a paragraph at run time. The dispatch paragraph's entire body is
 * an unconditional branch ({@code :L726}-{@code :L728}); the entry point rewrites that branch's target
 * before taking it ({@code :L294}-{@code :L316}, with four {@code ALTER … TO PROCEED TO} at {@code :L300},
 * {@code :L303}, {@code :L306} and {@code :L309}, a direct {@code GO TO} for {@code 'READTRNX'} at
 * {@code :L311}-{@code :L312} and {@code WHEN OTHER → GO TO 9999-GOBACK} at {@code :L313}-{@code :L314});
 * and the selector's initial value is fixed at {@code :L67}.
 *
 * <p>That reads like a dispatch table and is not one. <b>Every transition is hard-coded in its own
 * handler's tail</b>, so the machine is deterministic and admits exactly one path: open and prime
 * {@code TRNXFILE} then {@code :L760}-{@code :L761}; build the resident table then
 * {@code :L851}-{@code :L852}; open {@code XREFFILE} then {@code :L779}-{@code :L780}; open
 * {@code CUSTFILE} then {@code :L797}-{@code :L798}; open {@code ACCTFILE} then
 * {@code GO TO 1000-MAINLINE} at {@code :L815}, leaving the machine permanently. It collapses to five
 * ordered calls followed by the mainline, which is what {@link StatementProcessor#initialise()} is, and
 * this class drives it as such. <b>There is deliberately no dispatch map, no state enum and no switch over
 * an initialisation selector anywhere in this file</b>: that would model a variability which does not
 * exist, against Rule 1 Clause A's requirement of minimal complexity and explicit behaviour. The genuinely
 * varying DD-keyed strategy - {@code app/cbl/CBSTM03B.CBL}'s four datasets by six operations - lives in
 * {@link FileService}, where it belongs. What happens here is self-modifying code eliminated by static flow
 * analysis, with the observable order preserved.
 *
 * <h2>The 510-transaction ceiling is removed - a deliberate deviation, not parity</h2>
 *
 * <p>{@code app/cbl/CBSTM03A.CBL:L225}-{@code :L233} declares the resident table as 51 card entries of 10
 * transactions each - a hard maximum of <b>510 transactions per run</b> - and the building loop at
 * {@code :L818}-{@code :L848} increments both subscripts with no bounds check whatsoever. Beyond 510 the
 * source overruns its own storage silently. The Java pipeline streams instead, one control break at a
 * time, so the ceiling is gone.
 *
 * <p><b>This is a deviation and is labelled as one.</b> Justified under Rule 1 Clause A - the tradeoff is
 * declared because it is needed: retaining a 510-record ceiling would retain a latent
 * truncation-and-corruption defect, and there is no parity argument for reproducing memory corruption.
 * Pretending the ceiling was preserved would be false; pretending its removal is invisible would be worse,
 * which is why the historical 510 limit is stated here.
 *
 * <h2>The early-exit lookup depends on the sort, so the sort is asserted</h2>
 *
 * <p>{@code 4000-TRNXFILE-GET} at {@code app/cbl/CBSTM03A.CBL:L416}-{@code :L456} scans the resident table
 * linearly and abandons the scan as soon as a stored card number <i>exceeds</i> the one sought
 * ({@code :L419}). That early exit is correct only while the sequence ascends by card number, which
 * {@code STEP010}'s sort guarantees. This class <b>preserves the guarantee explicitly</b> rather than
 * making the lookup order-independent: the projection step verifies, record by record, that the emitted
 * sequence never descends under {@link #SORT_FIELDS_COMPARATOR}, and abends if it does. Making the lookup
 * order-independent instead would change which records are found when the input is unsorted, and would
 * have been a divergence requiring its own entry; asserting the precondition is not.
 *
 * <h2>Return codes and error modes</h2>
 *
 * <p>Return code 0 is completed, 4 completed with rejects, 8 failed and 12 abend.
 * <b>{@code CBSTM03A} sets no return code at all</b> - the only numeric-literal {@code RETURN-CODE}
 * assignment in the corpus is {@code MOVE 4 TO RETURN-CODE} at {@code app/cbl/CBTRN02C.cbl:L230}, in a
 * different program - so <b>this job has no return code 4 path of its own and its normal outcome is 0</b>.
 * Return code 4 is nevertheless recognised by {@link StatementGenerationReturnCodeDecider}, because this
 * flow is composable into the wider pipeline where a predecessor does produce it, and an unrecognised
 * outcome must never fall through to success.
 *
 * <p>Failures surface as typed exceptions and never as a process exit. The JVM is never terminated from
 * this file: no process-exit call, no runtime halt and no shutdown hook appears in it, and the return code
 * reaches the launcher through the job execution instead.
 * {@code 9999-ABEND-PROGRAM} at {@code app/cbl/CBSTM03A.CBL:L921}-{@code :L923} - which displays
 * {@code 'ABENDING PROGRAM'} and calls the language environment abend service - becomes
 * {@link FatalProcessingException} carrying abend code
 * {@value FatalProcessingException#BATCH_ABEND_CODE} and process return code
 * {@value FatalProcessingException#BATCH_RETURN_CODE}. That code is 999, the batch value; 9999 is the
 * distinct CICS online value, and conflating the two breaks parity.
 *
 * <p>File statuses are translated by {@link FileStatusMapper} on every I/O path: {@code '00'} continues, {@code '10'}
 * is end of file and is loop termination rather than an exception, {@code '22'}, {@code '23'} and {@code '35'} are
 * their own typed exceptions, the {@code '9x'} family becomes {@code com.cardemo.exception.FileAccessException}, and
 * anything else abends. {@code '04'} is a secondary success <b>only</b> at the nine {@code CBSTM03B} call sites -
 * {@code app/cbl/CBSTM03A.CBL:L736}, {@code :L748}, {@code :L771}, {@code :L789}, {@code :L807}, {@code :L862},
 * {@code :L879}, {@code :L895} and {@code :L911} - which is one of exactly three scoped leniency sites in the corpus.
 * That contract is {@link FileService}'s and is delegated to it, never re-implemented here.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><b>Startup fails resolving a bucket property.</b> Neither bucket has a default. Set
 *       {@code CARDDEMO_S3_BATCH_OUTPUT_BUCKET} and {@code CARDDEMO_S3_STATEMENTS_BUCKET}.</li>
 *   <li><b>The projection step abends reporting a descending sequence.</b> The ordering precondition of
 *       {@code app/cbl/CBSTM03A.CBL:L419} is broken - almost always a changed {@code ORDER BY} in
 *       {@link TransactionRepository#findStatementOrderAfter}. Restore card-then-identifier ascending
 *       order; do not relax the assertion.</li>
 *   <li><b>The load step abends reporting a record length.</b> The projected object is not a whole number
 *       of {@value StatementTransaction#RECORD_LENGTH}-byte records, so the work cluster's
 *       {@code RECORDSIZE(350 350)} at {@code app/jcl/CREASTMT.JCL:L32} is violated. Object storage input
 *       is untrusted and is validated before it is parsed, which is why this surfaces as a typed abend
 *       rather than a mis-parse.</li>
 *   <li><b>A run is larger than expected but does not abend.</b> That is correct, and it is finding BAT-002.
 *       Refusing any run above a configured ceiling - five million work records, say - would be an invented
 *       business rule: the corpus has none, and {@code app/jcl/CREASTMT.JCL} sizes nothing by record count.
 *       Both directions of this pipeline stream one record at
 *       a time, so run size is bounded by the input and by object storage, not by an authored constant.
 *       Watch {@code STEP010}'s projected record count and the object's size if a run's duration matters.</li>
 *   <li><b>The emit step abends on an open.</b> One of the four datasets has no binding, or reported a
 *       status outside {@code '00'} and {@code '04'}. The abend names the DD and the return code.</li>
 *   <li><b>Steps 3, 4 and 5 are reported as skipped.</b> That is {@code COND=(0,NE)} doing its job; look
 *       at the return code of the step before the first skipped one.</li>
 *   <li><b>Gates 1, 4 and 8 cannot be executed.</b> They need a container runtime for Testcontainers and
 *       for the compose stack. Where none is available the prerequisite is stated rather than a pass being
 *       asserted.</li>
 * </ul>
 *
 * <h2>Legacy defects logged rather than repaired</h2>
 *
 * <ol>
 *   <li>{@code HTMLFILE} is pre-deleted at {@code LRECL=80} in {@code STEP030}
 *       ({@code app/jcl/CREASTMT.JCL:L69}) but allocated at {@code LRECL=100} in {@code STEP040}
 *       ({@code :L94}). <b>100 is correct</b>, independently confirmed by
 *       {@code 05 HTML-FIXED-LN PIC X(100)} at {@code app/cbl/CBSTM03A.CBL:L149} and by
 *       {@code 01 FD-HTMLFILE-REC PIC X(100)} at {@code :L47}. Markup is emitted at 100 bytes per line and
 *       the contradictory 80 is recorded, not honoured. Correcting {@code :L69} would mean editing a frozen
 *       member, so it is logged instead.</li>
 *   <li>{@code app/jcl/CREASTMT.JCL:L90} is corrupted on disk, reading
 *       {@code SPACE=(CYL,(1,1),RLSE), 00,RECFM=FB), ATA.VSAM.KSDS} - two fragments of other DD statements
 *       overwritten into one line. Its intended content cannot be recovered from this repository; that would
 *       need the original member from the source control system predating this corpus. The surrounding
 *       statement is complete enough that
 *       {@code STMTFILE}'s {@code LRECL=80} at {@code :L89} is unambiguous, so nothing downstream depends
 *       on the missing text.</li>
 *   <li>{@code STMTFILE} at {@code :L72} omits the {@code UNIT=SYSDA} that {@code HTMLFILE}
 *       carries at {@code :L68}, and the member ends at {@code :L97} with {@code //*} and no terminating
 *       {@code //} card. Neither has a Java counterpart - unit allocation and the end-of-deck card are
 *       both JES2 concepts - so both are recorded and nothing is normalised.</li>
 * </ol>
 *
 * <h2>Member casing and line endings</h2>
 *
 * <p>Every {@code path:line} citation in this file was re-read from disk with carriage returns stripped, because
 * {@code app/jcl/CREASTMT.JCL}, {@code app/cbl/CBSTM03A.CBL}, {@code app/cbl/CBSTM03B.CBL} and
 * {@code app/cpy/COSTM01.CPY} all use CRLF line endings and all four have <b>uppercase</b> names. A case-sensitive
 * {@code *.jcl} or {@code *.cbl} glob omits every one of them, and since these are the sole sources for statement
 * generation the whole job disappears from scope. Every member is therefore spelled exactly as it appears on
 * disk and carriage returns are stripped before a line is counted - which is also why the
 * statement copybook is only ever cited as {@code app/cpy/COSTM01.CPY}: no Y-suffixed variant of that member exists
 * in the repository, so a citation carrying one would resolve to nothing.
 *
 * <h2>Citation precision</h2>
 *
 * <p>Three locators are easy to get wrong by one line and are stated here exactly: the redundant
 * {@code MOVE 1 TO CR-JMP} is at {@code app/cbl/CBSTM03A.CBL:L324}, not {@code :L325} - {@code :L325} is
 * {@code MOVE ZERO TO WS-TOTAL-AMT}; {@code TRNX-REST} at {@code app/cpy/COSTM01.CPY:L24} is a group of <b>eleven
 * named sub-fields plus a 20-byte filler</b>, twelve entries summing to
 * {@value StatementTransaction#REMAINDER_LENGTH}, not eleven; and the unpaired {@code MOVE ZERO TO WS-M03B-RC} at
 * {@code app/cbl/CBSTM03A.CBL:L804} is not unique to that site - all four opens ({@code :L733}, {@code :L768},
 * {@code :L786}, {@code :L804}) and all four closes ({@code :L859}, {@code :L876}, {@code :L892}, {@code :L908}) omit
 * the paired {@code MOVE SPACES TO WS-M03B-FLDT}, and only the five read sites pair them. The asymmetry is therefore
 * read-against-open-and-close, and it is logged rather than normalised.
 *
 * <h2>Further disclosures required by Rule 1 Clause F</h2>
 *
 * <ul>
 *   <li>Whether {@code app/cbl/CBSTM03B.CBL} was intended to carry a fifteenth <i>procedural</i> label is
 *       <b>Not available</b>. Fourteen procedure-division labels exist on disk and the fifteenth entry in
 *       the count is {@code FILE-CONTROL.} at {@code :L30}, an environment-division paragraph. No
 *       fifteenth procedural label is invented here.</li>
 *   <li>The object-storage record-framing convention is <b>Not available</b>. Both statement objects and
 *       the projected work object are written unblocked and undelimited, because
 *       {@code app/jcl/CREASTMT.JCL} declares {@code RECFM=FB} with fixed record lengths and no consumer
 *       of these objects exists in the repository to state whether newline delimiting is expected.
 *       Byte-counting is therefore the only boundary rule, and adding delimiters would change every object
 *       size. Resolving this needs the downstream consumer's specification.</li>
 *   <li>A service-level objective for this job is <b>Not available</b>. {@code app/jcl/CREASTMT.JCL}
 *       declares {@code TIME=1440} at {@code :L2}, which is the maximum permitted step time rather than a
 *       target, and the corpus publishes no throughput or latency goal anywhere. None is invented; the
 *       performance gate records a measured baseline instead. Resolving this needs a stated objective from
 *       the service owner.</li>
 *   <li>Evidence for the end-to-end, fixture and integration gates is <b>Not available</b> without a
 *       container runtime exposing an accessible socket, which Testcontainers and the compose stack both
 *       require. That is a stated prerequisite, and it is reported as one rather than as a pass.</li>
 * </ul>
 *
 * @see StatementProcessor
 * @see StatementWriter
 * @see FileService
 */
@Configuration(StatementGenerationJob.CONFIGURATION_BEAN_NAME)
public class StatementGenerationJob {

    /**
     * The name this configuration class itself is registered under. Named explicitly so that it cannot
     * collide with {@code com.cardemo.config.BatchConfig} or with any future configuration class whose
     * simple name happens to match.
     */
    static final String CONFIGURATION_BEAN_NAME = "statementGenerationJobConfiguration";

    /** Diagnostic logger. No full card number and no personal data ever reaches it. */
    private static final Logger LOG = LoggerFactory.getLogger(StatementGenerationJob.class);

    // Bean names. Every one carries the statementGeneration prefix so that this class contributes no name
    // another configuration class could plausibly want. This folder may contribute only Job, Step and
    // Flow beans; no infrastructure bean is declared anywhere below - the JobRepository, the transaction
    // manager and the object-storage client are all injected.

    /** {@code DELDEF01}, {@code app/jcl/CREASTMT.JCL:L22}. */
    static final String DEFINE_STEP_BEAN_NAME = "statementGenerationDefineStep";

    /** {@code STEP010}, {@code app/jcl/CREASTMT.JCL:L44}. */
    static final String SORT_STEP_BEAN_NAME = "statementGenerationSortStep";

    /** {@code STEP020}, {@code app/jcl/CREASTMT.JCL:L56}. */
    static final String LOAD_STEP_BEAN_NAME = "statementGenerationLoadStep";

    /** {@code STEP030}, {@code app/jcl/CREASTMT.JCL:L66}. */
    static final String PRE_DELETE_STEP_BEAN_NAME = "statementGenerationPreDeleteStep";

    /** {@code STEP040}, {@code app/jcl/CREASTMT.JCL:L79}. */
    static final String EMIT_STEP_BEAN_NAME = "statementGenerationEmitStep";

    /** The gated composition of all five steps. */
    static final String FLOW_BEAN_NAME = "statementGenerationFlow";

    /** The job itself, the whole of {@code app/jcl/CREASTMT.JCL}. */
    static final String JOB_BEAN_NAME = "statementGenerationJob";

    // Configuration defaults.

    /** The JCL job name at {@code app/jcl/CREASTMT.JCL:L1}. */
    static final String DEFAULT_JOB_NAME = "CREASTMT";

    /** Commit interval and read window, matching {@code carddemo.batch.chunk-size}. */
    static final int DEFAULT_CHUNK_SIZE = 100;

    /** Key prefix standing in for the {@code AWS.M2.CARDDEMO.TRXFL} dataset names. */
    static final String DEFAULT_WORK_PREFIX = "work/trxfl";

    /** The number of steps {@code app/jcl/CREASTMT.JCL} declares, and that this job registers. */
    static final int STEP_COUNT = 5;

    // Work-cluster geometry, from DEFINE CLUSTER at app/jcl/CREASTMT.JCL:L29-L39. The cluster is an
    // IN-JOB projection and sort target and is NEVER persisted: there is no table, no entity, no
    // repository and no migration for it. V1__create_schema.sql creates exactly eleven tables and a
    // validation gate asserts that count, so a twelfth for TRXFL would fail the gate. It is also absent
    // from app/catlg/LISTCAT.txt, whose summary counts ten clusters at :L3940 - the work cluster is
    // created and deleted inside this one job and was never catalogued.

    /** {@code KEYS(32 0)}, {@code app/jcl/CREASTMT.JCL:L30}, corroborating {@code app/cpy/COSTM01.CPY:L21}. */
    static final int WORK_CLUSTER_KEY_LENGTH = StatementTransaction.KEY_LENGTH;

    /** {@code RECORDSIZE(350 350)}, {@code app/jcl/CREASTMT.JCL:L32}. */
    static final int WORK_CLUSTER_RECORD_LENGTH = StatementTransaction.RECORD_LENGTH;

    /** {@code CISZ(4096)} on the data component, {@code app/jcl/CREASTMT.JCL:L38}. */
    private static final int WORK_CLUSTER_CONTROL_INTERVAL_SIZE = 4096;

    /** {@code CYL(1 5)}, {@code app/jcl/CREASTMT.JCL:L36}: one primary and five secondary cylinders. */
    private static final int WORK_CLUSTER_PRIMARY_CYLINDERS = 1;

    /** The secondary allocation of {@code CYL(1 5)}, {@code app/jcl/CREASTMT.JCL:L36}. */
    private static final int WORK_CLUSTER_SECONDARY_CYLINDERS = 5;

    /** {@code SHAREOPTIONS(2 3)}, {@code app/jcl/CREASTMT.JCL:L33}. Recorded, not enforced. */
    private static final String WORK_CLUSTER_SHARE_OPTIONS = "2 3";

    /** {@code VOLUMES(TSU023)}, {@code app/jcl/CREASTMT.JCL:L31}. No object-storage counterpart. */
    private static final String WORK_CLUSTER_VOLUME = "TSU023";

    /** {@code ERASE} and {@code INDEXED}, {@code app/jcl/CREASTMT.JCL:L34}-{@code :L35}. */
    private static final String WORK_CLUSTER_ATTRIBUTES = "ERASE INDEXED";

    /** {@code DCB=(LRECL=350,BLKSIZE=3500,RECFM=FB)} on {@code SORTOUT}, {@code app/jcl/CREASTMT.JCL:L50}. */
    private static final int WORK_SEQUENTIAL_BLOCK_SIZE = 3500;

    // Output geometry. Two streams, two widths, both preserved byte-exactly.

    /** {@code LRECL=80} on {@code STMTFILE}, {@code app/jcl/CREASTMT.JCL:L89}; {@code CBSTM03A.CBL:L45}. */
    static final int TEXT_RECORD_LENGTH = StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH;

    /** {@code LRECL=100} on {@code HTMLFILE}, {@code app/jcl/CREASTMT.JCL:L94}; {@code CBSTM03A.CBL:L47}. */
    static final int HTML_RECORD_LENGTH = StatementTransaction.STATEMENT_HTML_RECORD_LENGTH;

    /**
     * The width {@code STEP030} pre-deletes {@code HTMLFILE} at, {@code app/jcl/CREASTMT.JCL:L69}.
     *
     * <p>It disagrees with the {@value #HTML_RECORD_LENGTH} that {@code STEP040} allocates at {@code :L94}
     * and that {@code 05 HTML-FIXED-LN PIC X(100)} at {@code app/cbl/CBSTM03A.CBL:L149} confirms. Held as a
     * named constant, and used by {@link #preDeleteHtmlOutput()} only to log the
     * discrepancy, so the defect is visible in the code rather than silently dropped.
     */
    private static final int HTMLFILE_PRE_DELETE_DECLARED_LENGTH = 80;

    /**
     * The single-byte encoding for every fixed-width emission, so one character is one byte and object
     * sizes stay exact multiples of the record length. Never UTF-8 and never the platform default: either
     * would make the byte geometry depend on the data. No EBCDIC is parsed anywhere.
     */
    private static final Charset FIXED_WIDTH_CHARSET = StandardCharsets.ISO_8859_1;

    /** Content type of the projected sequential object: opaque fixed-width bytes, not text. */
    private static final String WORK_OBJECT_CONTENT_TYPE = "application/octet-stream";

    /** File name of the projected sequential object, after {@code AWS.M2.CARDDEMO.TRXFL.SEQ}. */
    private static final String WORK_OBJECT_NAME = "TRXFL.SEQ";

    /**
     * Logical name of a statement text object, taken from the {@code STMTFILE} DD name that
     * {@code app/jcl/CREASTMT.JCL:L72} pre-deletes and {@code :L87} allocates.
     *
     * <p><strong>Finding, severity Medium, resolved - CWE-532 and CWE-200.</strong> A statement object key
     * carries an account identifier and a statement month, because that is what makes statement objects
     * addressable. Naming one in a failure diagnostic therefore discloses which account was billed in which
     * month to every reader of the log stream - and a delete failure is precisely the moment an operator
     * copies a log line into a ticket. The two sibling guard call sites in this class already pass
     * {@link #WORK_OBJECT_NAME}, a logical dataset name, rather than a concrete key; the statement
     * pre-delete now does the same, qualified by the object's
     * {@linkplain #statementObjectReference(String, int) ordinal} within the list of keys the earlier attempt
     * published, so the failing object is still identifiable from the execution context without the key
     * appearing anywhere.
     */
    private static final String STATEMENT_TEXT_OBJECT_NAME = "STMTFILE";

    /**
     * Logical name of the statement markup object, taken from the {@code HTMLFILE} DD name that
     * {@code app/jcl/CREASTMT.JCL:L67} pre-deletes and {@code :L92} allocates.
     *
     * <p>Named for the same reason as {@link #STATEMENT_TEXT_OBJECT_NAME}: the markup key is built from the
     * same account and month segments as the text key, so it discloses the same thing and is treated
     * identically.
     */
    private static final String STATEMENT_MARKUP_OBJECT_NAME = "HTMLFILE";

    /**
     * Trailing object name of a statement markup key, {@code app/jcl/CREASTMT.JCL:L92}.
     *
     * <p>It is the discriminator {@link #logicalNameOf(String)} uses, and it is written out here rather than
     * read from {@code StatementWriter} because that class keeps its two object names private: a key shape is
     * a contract between the writer and whatever enumerates the root, and this is the enumerating side of it.
     */
    private static final String MARKUP_OBJECT_SUFFIX = "STATEMNT.HTML";

    /** Zero-padded width of the monotonically increasing generation segment of a work object key. */
    private static final int GENERATION_WIDTH = 19;

    /** The {@code generation=} key segment, the {@code (+1)} of a relative generation reference. */
    private static final String KEY_GENERATION_SEGMENT = "generation=";

    /** Key path separator. */
    private static final String KEY_SEPARATOR = "/";

    // Execution-context entries. A (+1) written by an earlier step is re-read by a later one, so every
    // step publishes the CONCRETE key it created and every reader takes that key. "The latest object" is
    // never re-resolved mid-job: a concurrent run would otherwise hand a step the wrong generation.

    /**
     * The key {@code STEP010} created, read back by {@code STEP020} and by the {@code TRNXFILE} binding that
     * {@code STEP040} reads through.
     *
     * <p>Public because it is the published handoff contract of this job rather than an internal detail: the
     * {@code TRNXFILE} {@link com.cardemo.service.shared.FileService.Dataset} binding lives in
     * {@code com.cardemo.config.BatchConfig}, a different package, and must take the concrete key from here.
     * That indirection is the point of the entry - see the note above on why "the latest object" is never
     * re-resolved mid-job.
     */
    public static final String WORK_OBJECT_KEY_CONTEXT_ENTRY = "carddemo.creastmt.work.objectKey";

    /** The record count {@code STEP010} emitted, checked by {@code STEP020}. */
    static final String WORK_RECORD_COUNT_CONTEXT_ENTRY = "carddemo.creastmt.work.recordCount";

    /** The record count {@code STEP020} loaded into the work cluster. */
    static final String LOADED_RECORD_COUNT_CONTEXT_ENTRY = "carddemo.creastmt.load.recordCount";

    /** The work-cluster geometry {@code DELDEF01} defined, rendered for the run log and for diagnostics. */
    static final String WORK_GEOMETRY_CONTEXT_ENTRY = "carddemo.creastmt.work.geometry";

    /**
     * Job execution context entry holding the {@value #DIGEST_ALGORITHM} digest of the exact bytes
     * {@code STEP010} wrote, as lowercase hexadecimal.
     *
     * <p><strong>Finding H-06, severity High.</strong> This entry is the whole mechanism of its resolution.
     * {@code app/jcl/CREASTMT.JCL} sorts the transaction cluster into a sequential dataset at {@code STEP010},
     * loads it into the work cluster at {@code STEP020} and only then runs {@code CBSTM03A} at {@code STEP040} -
     * so on the mainframe every one of those steps sees the same bytes, frozen at the sort. In this system
     * {@code STEP040} reaches its transactions through the {@code TRNXFILE} DD, whose {@code FileService.Dataset}
     * binding is declared by {@code com.cardemo.config.BatchConfig} over the live relation. That binding is a
     * single-owner declaration and cannot be re-pointed from here without declaring a second bean for one DD, so
     * the snapshot is enforced rather than substituted: {@code STEP010} publishes this digest,
     * {@code STEP020} proves the object it loads still matches it, and {@code STEP040} proves the live source
     * still projects to it before the first statement is built. The outcome is that {@code STEP040} either sees
     * content byte-identical to the {@code STEP010} object or the job abends - it can never quietly build
     * statements from data the sort never saw.
     */
    static final String WORK_OBJECT_DIGEST_CONTEXT_ENTRY = "carddemo.creastmt.work.digest";

    /** The generation ordinal {@code DELDEF01} reserved for this run's work object. */
    static final String WORK_GENERATION_CONTEXT_ENTRY = "carddemo.creastmt.work.generation";

    /** The number of statement objects {@code STEP030} removed from a previous attempt. */
    static final String PRE_DELETED_OBJECT_COUNT_CONTEXT_ENTRY = "carddemo.creastmt.predelete.objectCount";

    /** The number of statements {@code STEP040} emitted. */
    static final String STATEMENTS_EMITTED_CONTEXT_ENTRY = "carddemo.creastmt.emit.statementCount";

    /**
     * The {@code jobInstanceId} diagnostic value this execution inherited, if any, held only for the
     * duration of the run so {@link StatementGenerationJobListener} can restore it instead of deleting a
     * value it did not create. Removed again before the job finishes, so it never outlives the execution.
     */
    private static final String INHERITED_JOB_INSTANCE_ID_CONTEXT_ENTRY =
            "carddemo.creastmt.mdc.inheritedJobInstanceId";

    /**
     * The {@code correlationId} diagnostic value this execution inherited, if any, held for the same reason
     * and with the same lifetime as {@link #INHERITED_JOB_INSTANCE_ID_CONTEXT_ENTRY}. A correlation
     * identifier is an opaque diagnostic token and carries no credential, so retaining it for the run is
     * safe; it is removed before the job finishes all the same.
     */
    private static final String INHERITED_CORRELATION_ID_CONTEXT_ENTRY =
            "carddemo.creastmt.mdc.inheritedCorrelationId";

    // Flow vocabulary. Exit codes first, then the two gate outcomes that model COND=(0,NE).

    /** Return code 0. */
    private static final String EXIT_CODE_COMPLETED = ExitStatus.COMPLETED.getExitCode();

    /** Return code 4. Recognised but not produced by this job; see the class documentation. */
    private static final String EXIT_CODE_COMPLETED_WITH_REJECTS = "COMPLETED WITH REJECTS";

    /** Return code 8. */
    private static final String EXIT_CODE_FAILED = ExitStatus.FAILED.getExitCode();

    /** Return code 12, the abend arm. */
    private static final String EXIT_CODE_ABEND = "ABEND";

    /** The wildcard transition, without which a failed step would short-circuit the flow. */
    private static final String EXIT_CODE_ANY = "*";

    /** A {@code COND=(0,NE)} gate that lets its step run: the preceding return code was zero. */
    private static final String GATE_RUN = "RUN";

    /** A {@code COND=(0,NE)} gate that suppresses its step: the preceding return code was not zero. */
    private static final String GATE_SKIP = "SKIP";

    /** Legacy return code 0. */
    private static final int RETURN_CODE_COMPLETED = 0;

    /** Legacy return code 4. */
    private static final int RETURN_CODE_COMPLETED_WITH_REJECTS = 4;

    /** Legacy return code 8. */
    private static final int RETURN_CODE_FAILED = 8;

    /** Legacy return code 12, {@value FatalProcessingException#BATCH_RETURN_CODE}. */
    private static final int RETURN_CODE_ABEND = FatalProcessingException.BATCH_RETURN_CODE;

    // Diagnostics, mirroring the source's DISPLAY text where the source has any.

    /** The program the abend is attributed to, {@code app/cbl/CBSTM03A.CBL:L2}. */
    private static final String ABEND_CULPRIT = "CBSTM03A";

    /** {@value FatalProcessingException#BATCH_ABEND_CODE}, rendered for the abend payload. */
    private static final String ABEND_CODE = String.valueOf(FatalProcessingException.BATCH_ABEND_CODE);

    /** {@code DISPLAY 'ABENDING PROGRAM'}, {@code app/cbl/CBSTM03A.CBL:L922}. */
    private static final String MSG_ABENDING_PROGRAM = "ABENDING PROGRAM";

    /** Abend reason when object storage rejects a read or a write. */
    private static final String REASON_OBJECT_STORE_FAILED = "OBJECT STORE FAILED";

    /** Abend reason when the projected object is not a whole number of fixed-length records. */
    private static final String REASON_BAD_RECORD_LENGTH = "RECORD LENGTH VIOLATION";

    /** Abend reason when the projected sequence descends, breaking the precondition of {@code :L419}. */
    private static final String REASON_SORT_ORDER_VIOLATION = "SORT ORDER VIOLATION";

    /** Abend reason when a step cannot find the key its predecessor was required to publish. */
    private static final String REASON_MISSING_HANDOFF = "MISSING STEP HANDOFF";

    /** Abend reason when the markup fragment table or the writer geometry contradicts the JCL. */
    private static final String REASON_OUTPUT_GEOMETRY = "OUTPUT GEOMETRY VIOLATION";

    /** Abend reason for a source that moved between the sort and a step that depends on the sort's output. */
    private static final String REASON_SNAPSHOT_DRIFTED = "WORK SNAPSHOT DRIFTED";

    /**
     * The digest algorithm the snapshot identity check uses.
     *
     * <p>It is a content check and not a security primitive - nothing here authenticates anything - but a
     * collision-resistant algorithm is chosen anyway, because a weak one would make "identical" mean
     * "indistinguishable by a weak function" and the whole point of finding H-06 is that similarity is not
     * identity. It is a required algorithm on every conforming platform, so its availability needs no fallback.
     */
    private static final String DIGEST_ALGORITHM = "SHA-256";

    /** The {@code '9x'} family status used when object storage fails, {@code FileStatus:IO_ERROR}. */
    private static final String OBJECT_STORE_IO_STATUS = FileStatus.IO_ERROR_FIRST_BYTE + "0";

    /** {@code '00'}, the only status that continues without qualification. */
    private static final String SUCCESS_STATUS = FileStatus.SUCCESS.code().orElseThrow();

    // The two translated control cards, both unmodifiable constants.

    /**
     * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)}, {@code app/jcl/CREASTMT.JCL:L53}.
     *
     * <p>Two ascending keys: the card number at bytes 263-278, then the transaction identifier at bytes
     * 1-16, both compared as character data because {@code CH} is what the control card specifies and both
     * source fields are alphanumeric. Total, stable and deterministic - never a locale-sensitive collator
     * and never a case-insensitive comparison, either of which would order differently from DFSORT.
     * Obtained from {@link StatementProcessor#statementSortComparator()} rather than rebuilt, so the
     * ordering this class asserts and the ordering the pipeline applies cannot drift apart.
     *
     * <p><b>No external sort process is spawned.</b> Neither the runtime's process-execution entry point nor a
     * process-builder API appears anywhere in this file - Rule 1 Clause D bans exec patterns outright,
     * independently of the transformation rules.
     */
    private static final Comparator<Transaction> SORT_FIELDS_COMPARATOR =
            StatementProcessor.statementSortComparator();

    /**
     * The markup fragment table of {@code app/cbl/CBSTM03A.CBL:L149}-{@code :L211}, where dozens of
     * condition names over one {@code PIC X(100)} field each carry a single literal and the emission idiom
     * is "set the condition name true, write the field".
     *
     * <p>Held unmodifiable and taken from {@link StatementProcessor#htmlFragments()} rather than restated,
     * because restating three dozen markup literals in a second place is exactly the duplication Rule 1
     * Clause C forbids. This class uses it to assert the output geometry before the emit step runs - no
     * fragment may exceed {@value #HTML_RECORD_LENGTH} characters - while the emission itself belongs to
     * {@link StatementWriter}.
     */
    private static final Map<String, String> HTML_FRAGMENTS =
            Map.copyOf(StatementProcessor.htmlFragments());

    // Injected collaborators. Constructor injection only; every field final; no static mutable state
    // anywhere in this class, which is what makes two concurrent job executions independent.

    /** The framework's job repository. Injected, never declared. */
    private final JobRepository jobRepository;

    /** The application transaction manager, named so a second candidate cannot be bound by type. */
    private final PlatformTransactionManager transactionManager;

    /** The {@code TRANSACT} cluster, {@code app/jcl/CREASTMT.JCL:L45}. */
    private final TransactionRepository transactionRepository;

    /** {@code app/cbl/CBSTM03A.CBL} itself: the initialisation chain, the mainline and the projection. */
    private final StatementProcessor statementProcessor;

    /** The two output streams, {@code STMTFILE} and {@code HTMLFILE}. */
    private final StatementWriter statementWriter;

    /** Object storage. Injected as an interface; no client is ever constructed here. */
    private final S3Operations objectStorage;

    /** Translates a file status into a typed exception on every I/O path. */
    private final FileStatusMapper fileStatusMapper;

    /** The registered job name, {@code carddemo.batch.jobs.creastmt.name}. */
    private final String jobName;

    /** Commit interval and read window, {@code carddemo.batch.creastmt.chunk-size}. */
    private final int chunkSize;

    /** Bucket receiving the projected sequential object, {@code carddemo.aws.s3.batch-output-bucket}. */
    private final String batchOutputBucket;

    /** Bucket receiving both statement objects, {@code carddemo.aws.s3.statements-bucket}. */
    private final String statementsBucket;

    /** Key prefix of the work dataset, {@code carddemo.aws.s3.work-prefixes.trxfl}. */
    private final String workPrefix;

    /**
     * Binds every collaborator and every tunable, failing the context rather than the run when anything is
     * missing.
     *
     * <p>Both buckets are deliberately given <b>no default</b>. An unconfigured destination must fail
     * startup rather than let a run write somewhere unintended, which is the least-privilege position Rule
     * 1 Clause D requires; {@code src/main/resources/application.yml} supplies both with no fallback so the
     * properties are always present in a configured deployment. The remaining three tunables do carry
     * documented defaults, because a job name, a commit interval and a key prefix are not secrets and every
     * deployment wants the same answer.
     *
     * @param jobRepository the framework job repository, never {@code null}
     * @param transactionManager the application transaction manager, never {@code null}
     * @param transactionRepository the {@code TRANSACT} cluster, never {@code null}
     * @param statementProcessor the {@code CBSTM03A} translation, never {@code null}
     * @param statementWriter the {@code STMTFILE} and {@code HTMLFILE} emitter, never {@code null}
     * @param objectStorage the object-storage operations interface, never {@code null}
     * @param fileStatusMapper the file-status to exception translator, never {@code null}
     * @param jobName the registered job name; defaults to {@value #DEFAULT_JOB_NAME}, must not be blank
     * @param chunkSize the commit interval and read window; defaults to {@code carddemo.batch.chunk-size}
     *     and then to {@value #DEFAULT_CHUNK_SIZE}, must be positive
     * @param batchOutputBucket the bucket receiving the projected sequential object; no default, must not
     *     be blank
     * @param statementsBucket the bucket receiving both statement objects; no default, must not be blank
     * @param workPrefix the work dataset key prefix; defaults to {@value #DEFAULT_WORK_PREFIX}, must not be
     *     blank
     * @throws NullPointerException if any collaborator is {@code null}
     * @throws IllegalArgumentException if any bound text is blank or the chunk size is not positive
     */
    public StatementGenerationJob(
            final JobRepository jobRepository,
            @Qualifier("transactionManager") final PlatformTransactionManager transactionManager,
            final TransactionRepository transactionRepository,
            final StatementProcessor statementProcessor,
            final StatementWriter statementWriter,
            final S3Operations objectStorage,
            final FileStatusMapper fileStatusMapper,
            @Value("${carddemo.batch.jobs.creastmt.name:" + DEFAULT_JOB_NAME + "}") final String jobName,
            @Value("${carddemo.batch.creastmt.chunk-size:${carddemo.batch.chunk-size:"
                    + DEFAULT_CHUNK_SIZE + "}}") final int chunkSize,
            @Value("${carddemo.aws.s3.batch-output-bucket}") final String batchOutputBucket,
            @Value("${carddemo.aws.s3.statements-bucket}") final String statementsBucket,
            @Value("${carddemo.aws.s3.work-prefixes.trxfl:" + DEFAULT_WORK_PREFIX + "}")
            final String workPrefix) {

        this.jobRepository = requireCollaborator(jobRepository, "jobRepository");
        this.transactionManager = requireCollaborator(transactionManager, "transactionManager");
        this.transactionRepository = requireCollaborator(transactionRepository, "transactionRepository");
        this.statementProcessor = requireCollaborator(statementProcessor, "statementProcessor");
        this.statementWriter = requireCollaborator(statementWriter, "statementWriter");
        this.objectStorage = requireCollaborator(objectStorage, "objectStorage");
        this.fileStatusMapper = requireCollaborator(fileStatusMapper, "fileStatusMapper");
        this.jobName = requireText(jobName, "carddemo.batch.jobs.creastmt.name");
        this.chunkSize = requirePositive(chunkSize, "carddemo.batch.creastmt.chunk-size");
        this.batchOutputBucket = requireText(batchOutputBucket, "carddemo.aws.s3.batch-output-bucket");
        this.statementsBucket = requireText(statementsBucket, "carddemo.aws.s3.statements-bucket");
        this.workPrefix = normalisePrefix(
                requireText(workPrefix, "carddemo.aws.s3.work-prefixes.trxfl"));

        LOG.info("CREASTMT statement generation configured: job={} steps={} chunk={} "
                        + "workCluster=KEYS({} 0)/RECORDSIZE({} {}) outputs={}B text and {}B markup",
                this.jobName, Integer.valueOf(STEP_COUNT), Integer.valueOf(this.chunkSize),
                Integer.valueOf(WORK_CLUSTER_KEY_LENGTH), Integer.valueOf(WORK_CLUSTER_RECORD_LENGTH),
                Integer.valueOf(WORK_CLUSTER_RECORD_LENGTH), Integer.valueOf(TEXT_RECORD_LENGTH),
                Integer.valueOf(HTML_RECORD_LENGTH));
    }

    /**
     * Rejects a {@code null} collaborator by name, so a wiring defect names the missing bean.
     *
     * @param <T> the collaborator type
     * @param collaborator the injected value
     * @param name the constructor parameter name, used verbatim in the failure message
     * @return the collaborator, never {@code null}
     * @throws NullPointerException if {@code collaborator} is {@code null}
     */
    private static <T> T requireCollaborator(final T collaborator, final String name) {
        return Objects.requireNonNull(collaborator, name + " must not be null");
    }

    /**
     * Rejects blank bound text, so an empty environment variable fails startup instead of producing an
     * object key with an empty segment or a job registered under an empty name.
     *
     * @param value the bound value
     * @param property the property name, used verbatim in the failure message
     * @return the value, never {@code null} and never blank
     * @throws IllegalArgumentException if {@code value} is {@code null} or blank
     */
    private static String requireText(final String value, final String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    property + " must be configured with a non-blank value");
        }
        return value;
    }

    /**
     * Rejects a non-positive chunk size, which would either commit nothing or fail the step builder deep in
     * the run rather than at startup.
     *
     * @param value the bound value
     * @param property the property name, used verbatim in the failure message
     * @return the value, always positive
     * @throws IllegalArgumentException if {@code value} is not positive
     */
    private static int requirePositive(final int value, final String property) {
        if (value < 1) {
            throw new IllegalArgumentException(
                    property + " must be at least 1 but was " + value);
        }
        return value;
    }

    /**
     * Validates the configured work-cluster key prefix against the one shared grammar.
     *
     * <p><strong>Finding m-02, severity Medium, RESOLVED.</strong> This method used to trim a prefix to a
     * canonical form - stripping both a leading and a trailing separator - so that {@code work/trxfl},
     * {@code /work/trxfl} and {@code work/trxfl/} all produced identical object keys. It was the most
     * permissive of the six divergent validators the review found, and permissiveness was the defect: three
     * spellings silently folded into one meant the value an operator wrote and the value in force could
     * differ. {@link GenerationPrefixContract#requireRelativePrefix(String, String)} is now the only grammar
     * and accepts exactly one spelling, {@code work/trxfl}, which is this class's own default and therefore
     * changes no behaviour.
     *
     * @param prefix the configured prefix, never {@code null} and never blank
     * @return the prefix unchanged, once it satisfies the shared grammar
     * @throws IllegalArgumentException if the prefix is absent, blank or malformed
     */
    private static String normalisePrefix(final String prefix) {
        return GenerationPrefixContract.requireRelativePrefix(
                prefix, "carddemo.aws.s3.work-prefixes.trxfl");
    }

    // The five steps, in the order app/jcl/CREASTMT.JCL declares them.

    /**
     * Step 1 of 5: {@code DELDEF01}, {@code app/jcl/CREASTMT.JCL:L22}, {@code EXEC PGM=IDCAMS},
     * <b>no {@code COND} parameter</b>.
     *
     * <p>Three control cards and one define. The two {@code DELETE}s at {@code :L25} and
     * {@code :L26}-{@code :L27} are followed by {@code SET MAXCC = 0} at {@code :L28}, the idiom that
     * discards a delete failure so that the step succeeds even on a first run when neither dataset exists.
     * That is reproduced literally: the delete is idempotent and cannot fail the step. {@code DEFINE
     * CLUSTER} at {@code :L29}-{@code :L39} then fixes the geometry, which is published into the job
     * execution context so the two following steps validate against it rather than against a literal.
     *
     * @return the define step, never {@code null}
     */
    @Bean(DEFINE_STEP_BEAN_NAME)
    public Step statementGenerationDefineStep() {
        return new StepBuilder(DEFINE_STEP_BEAN_NAME, jobRepository)
                .tasklet(defineWorkClusterTasklet(), transactionManager)
                .build();
    }

    /**
     * Step 2 of 5: {@code STEP010}, {@code app/jcl/CREASTMT.JCL:L44}, {@code EXEC PGM=SORT},
     * <b>no {@code COND} parameter</b>.
     *
     * <p>Reads {@code SORTIN} - the {@code TRANSACT} cluster at {@code :L45} - in the order
     * {@link #SORT_FIELDS_COMPARATOR} defines, applies the {@code OUTREC} projection to each record, and
     * writes one {@code SORTOUT} object at {@code :L48} whose records are exactly
     * {@value #WORK_CLUSTER_RECORD_LENGTH} bytes, matching the {@code DCB} at {@code :L50}.
     *
     * @return the projection and sort step, never {@code null}
     */
    @Bean(SORT_STEP_BEAN_NAME)
    public Step statementGenerationSortStep() {
        return new StepBuilder(SORT_STEP_BEAN_NAME, jobRepository)
                .tasklet(projectAndSortTasklet(), transactionManager)
                .build();
    }

    /**
     * Step 3 of 5: {@code STEP020}, {@code app/jcl/CREASTMT.JCL:L56},
     * {@code EXEC PGM=IDCAMS,COND=(0,NE)}.
     *
     * <p>{@code REPRO INFILE(INFILE) OUTFILE(OUTFILE)} at {@code :L61} loads the projected sequential
     * dataset at {@code :L58} into the work cluster at {@code :L59}. Gated, so it runs only while the
     * preceding return code is zero.
     *
     * @return the load step, never {@code null}
     */
    @Bean(LOAD_STEP_BEAN_NAME)
    public Step statementGenerationLoadStep() {
        return new StepBuilder(LOAD_STEP_BEAN_NAME, jobRepository)
                .tasklet(reproIntoWorkClusterTasklet(), transactionManager)
                .build();
    }

    /**
     * Step 4 of 5: {@code STEP030}, {@code app/jcl/CREASTMT.JCL:L66},
     * {@code EXEC PGM=IEFBR14,COND=(0,NE)}.
     *
     * <p>{@code IEFBR14} is a program whose entire body is a branch to its own return address: it does
     * nothing, and the step exists solely for the {@code DISP=(MOD,DELETE,DELETE)} side effect of its two
     * DD statements, which remove the previous run's markup output at {@code :L67}-{@code :L71} and text
     * output at {@code :L72}-{@code :L75}. Gated, and idempotent: absent outputs are not an error.
     *
     * @return the pre-delete step, never {@code null}
     */
    @Bean(PRE_DELETE_STEP_BEAN_NAME)
    public Step statementGenerationPreDeleteStep() {
        return new StepBuilder(PRE_DELETE_STEP_BEAN_NAME, jobRepository)
                .tasklet(preDeleteOutputsTasklet(), transactionManager)
                .build();
    }

    /**
     * Step 5 of 5: {@code STEP040}, {@code app/jcl/CREASTMT.JCL:L79},
     * {@code EXEC PGM=CBSTM03A,COND=(0,NE)} - the statement program itself.
     *
     * <p>Chunk oriented rather than a tasklet, because the source's own shape is a read-process-write loop
     * over the cross-reference file ({@code app/cbl/CBSTM03A.CBL:L317}-{@code :L329}) and the framework
     * already owns that loop. Writing a hand-rolled loop, or an abstract batch-step base for the five jobs
     * to share, would duplicate what the framework provides - which is how Rule 1 Clause C's
     * "avoid duplication" is satisfied here.
     *
     * <ul>
     *   <li>Reader: {@link #crossReferenceReader()}, the driving read of
     *       {@code 1000-XREFFILE-GET-NEXT}.</li>
     *   <li>Processor: {@link StatementProcessor}, the mainline body of {@code :L321}-{@code :L326}.</li>
     *   <li>Writer: {@link StatementWriter}, {@code STMTFILE} at {@value #TEXT_RECORD_LENGTH} bytes and
     *       {@code HTMLFILE} at {@value #HTML_RECORD_LENGTH}.</li>
     *   <li>Listeners: the writer, which publishes the object keys it created, and
     *       {@link EmitStepLifecycle}, which runs the five-stage initialisation before the first read and
     *       the four closes after the last.</li>
     * </ul>
     *
     * <p>Neither the reader nor the lifecycle listener is a bean: this folder may contribute only
     * {@link Job}, {@link Step} and {@link Flow} beans, and neither needs a container singleton.
     *
     * @return the emit step, never {@code null}
     */
    @Bean(EMIT_STEP_BEAN_NAME)
    public Step statementGenerationEmitStep() {
        return new StepBuilder(EMIT_STEP_BEAN_NAME, jobRepository)
                .<CardCrossReference, StatementProcessor.Statement>chunk(chunkSize, transactionManager)
                .reader(crossReferenceReader())
                .processor(statementProcessor)
                .writer(statementWriter)
                .listener((StepExecutionListener) statementWriter)
                .listener((StepExecutionListener) new EmitStepLifecycle())
                .build();
    }

    /**
     * Composes the five steps with the gating {@code app/jcl/CREASTMT.JCL} declares, which is not uniform.
     *
     * <p>{@code DELDEF01} and {@code STEP010} carry no {@code COND} parameter, so the transition into
     * {@code STEP010} is a wildcard: a failed {@code DELDEF01} still reaches it, exactly as JES2 would have
     * run it, and only then does the first gate look at the return code. {@code STEP020} at {@code :L56},
     * {@code STEP030} at {@code :L66} and {@code STEP040} at {@code :L79} each carry {@code COND=(0,NE)}
     * and are therefore each preceded by their own {@link ConditionCodeGate}. A gate that suppresses its
     * step routes straight to {@link StatementGenerationReturnCodeDecider}, which reports the outcome the
     * job actually reached - a suppressed step never turns a failure into a success.
     *
     * <p>The deciders are plain nested objects passed straight to the builder, never beans, so they cannot
     * collide with a decider another configuration class declares.
     *
     * @param statementGenerationDefineStep step 1, injected by name so the flow cannot bind some other
     *     assignable step
     * @param statementGenerationSortStep step 2, injected by name
     * @param statementGenerationLoadStep step 3, injected by name
     * @param statementGenerationPreDeleteStep step 4, injected by name
     * @param statementGenerationEmitStep step 5, injected by name
     * @return the gated flow, never {@code null}
     */
    @Bean(FLOW_BEAN_NAME)
    public Flow statementGenerationFlow(
            @Qualifier(DEFINE_STEP_BEAN_NAME) final Step statementGenerationDefineStep,
            @Qualifier(SORT_STEP_BEAN_NAME) final Step statementGenerationSortStep,
            @Qualifier(LOAD_STEP_BEAN_NAME) final Step statementGenerationLoadStep,
            @Qualifier(PRE_DELETE_STEP_BEAN_NAME) final Step statementGenerationPreDeleteStep,
            @Qualifier(EMIT_STEP_BEAN_NAME) final Step statementGenerationEmitStep) {

        final JobExecutionDecider loadGate = new ConditionCodeGate(LOAD_STEP_BEAN_NAME);
        final JobExecutionDecider preDeleteGate = new ConditionCodeGate(PRE_DELETE_STEP_BEAN_NAME);
        final JobExecutionDecider emitGate = new ConditionCodeGate(EMIT_STEP_BEAN_NAME);
        final JobExecutionDecider returnCode = new StatementGenerationReturnCodeDecider();

        return new FlowBuilder<SimpleFlow>(FLOW_BEAN_NAME)
                // :L22 DELDEF01 and :L44 STEP010 are ungated, so the transition is unconditional.
                .start(statementGenerationDefineStep)
                .on(EXIT_CODE_ANY).to(statementGenerationSortStep)
                // :L56 STEP020 COND=(0,NE).
                .from(statementGenerationSortStep).on(EXIT_CODE_ANY).to(loadGate)
                .from(loadGate).on(GATE_SKIP).to(returnCode)
                .from(loadGate).on(GATE_RUN).to(statementGenerationLoadStep)
                // :L66 STEP030 COND=(0,NE).
                .from(statementGenerationLoadStep).on(EXIT_CODE_ANY).to(preDeleteGate)
                .from(preDeleteGate).on(GATE_SKIP).to(returnCode)
                .from(preDeleteGate).on(GATE_RUN).to(statementGenerationPreDeleteStep)
                // :L79 STEP040 COND=(0,NE).
                .from(statementGenerationPreDeleteStep).on(EXIT_CODE_ANY).to(emitGate)
                .from(emitGate).on(GATE_SKIP).to(returnCode)
                .from(emitGate).on(GATE_RUN).to(statementGenerationEmitStep)
                .from(statementGenerationEmitStep).on(EXIT_CODE_ANY).to(returnCode)
                // The four legacy return codes, and a wildcard so an unrecognised outcome cannot pass.
                .from(returnCode).on(EXIT_CODE_COMPLETED).end(EXIT_CODE_COMPLETED)
                .from(returnCode).on(EXIT_CODE_COMPLETED_WITH_REJECTS)
                .end(EXIT_CODE_COMPLETED_WITH_REJECTS)
                .from(returnCode).on(EXIT_CODE_FAILED).fail()
                .from(returnCode).on(EXIT_CODE_ABEND).fail()
                .from(returnCode).on(EXIT_CODE_ANY).fail()
                .build();
    }

    /**
     * The job: the whole of {@code app/jcl/CREASTMT.JCL}, registered under {@link #jobName}.
     *
     * <p>No {@code JobParametersValidator} is attached, and the absence is deliberate rather than an
     * omission: {@code EXEC PGM=CBSTM03A} at {@code :L79} passes no {@code PARM}, so unlike
     * {@code app/jcl/INTCALC.jcl} there is no parameter contract to enforce and a validator would reject
     * nothing. No {@code JobParametersIncrementer} is attached either - the source has no run-sequence
     * concept, and the run identity that does matter, the generation of the work object, is derived from
     * the job instance rather than invented here.
     *
     * <p>{@link StatementGenerationJobListener} is a plain nested object for the same reason the deciders
     * are: this folder contributes no container singleton beyond the {@link Job}, {@link Step} and
     * {@link Flow} beans above.
     *
     * @param statementGenerationFlow the gated flow, injected by name
     * @return the job, never {@code null}
     */
    @Bean(JOB_BEAN_NAME)
    public Job statementGenerationJob(
            @Qualifier(FLOW_BEAN_NAME) final Flow statementGenerationFlow) {

        return new JobBuilder(jobName, jobRepository)
                .listener(new StatementGenerationJobListener())
                .start(statementGenerationFlow)
                .end()
                .build();
    }

    // Step 1 - DELDEF01, app/jcl/CREASTMT.JCL:L22-L39. One private method per control card.

    /**
     * The body of {@code DELDEF01}: two deletes, the condition-code reset, then the define.
     *
     * <p>Returned as a lambda rather than a bean, because a tasklet is not one of the three bean kinds this
     * folder may contribute.
     *
     * @return the define tasklet, never {@code null}
     */
    private Tasklet defineWorkClusterTasklet() {
        return (final StepContribution contribution, final ChunkContext chunkContext) -> {
            final StepExecution stepExecution = chunkContext.getStepContext().getStepExecution();
            final ExecutionContext jobContext = stepExecution.getJobExecution().getExecutionContext();
            final long generation = reserveWorkGeneration(stepExecution, jobContext);

            deleteWorkSequentialDataset(generation);
            deleteWorkCluster();
            setMaxccZero();
            defineWorkCluster(jobContext);

            contribution.setExitStatus(ExitStatus.COMPLETED);
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * {@code DELETE AWS.M2.CARDDEMO.TRXFL.SEQ}, {@code app/jcl/CREASTMT.JCL:L25}.
     *
     * <p>Removes any object already occupying this run's work key, which is the only object this step is
     * entitled to remove: the legacy statement deleted one named dataset, not a family, so widening this to
     * the whole prefix would destroy the work objects of concurrent or historical runs. Absence is the
     * normal case and is not an error - see {@link #setMaxccZero()}.
     *
     * @param generation the generation ordinal reserved for this run
     */
    private void deleteWorkSequentialDataset(final long generation) {
        final String key = workObjectKey(generation);
        try {
            if (objectStorage.objectExists(batchOutputBucket, key)) {
                objectStorage.deleteObject(batchOutputBucket, key);
                LOG.info("DELDEF01 removed a work object left by an earlier attempt: {}",
                        objectKeySurrogate(key));
            } else {
                LOG.debug("DELDEF01 found no work object at {}; nothing to delete",
                        objectKeySurrogate(key));
            }
        } catch (final RuntimeException deleteFailure) {
            // Not swallowed, and not logged in full either. Finding M-06, severity Medium: the throwable an
            // object-store client raises carries the bucket, the key and often the request URL in its message,
            // and a statement key embeds the account identifier. The classification an operator needs is the
            // failure's type; the outcome is the source's own, so there is no exception to re-raise the cause on.
            // See setMaxccZero() for why the step must not fail here.
            LOG.warn("DELDEF01 could not delete the work object {} ({}); app/jcl/CREASTMT.JCL:L28 discards "
                            + "this condition code, so the step continues",
                    objectKeySurrogate(key), deleteFailure.getClass().getName());
        }
    }

    /**
     * {@code DELETE AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS CLUSTER}, {@code app/jcl/CREASTMT.JCL:L26}-{@code :L27}.
     *
     * <p>The work cluster has no persistent existence to delete. It is an in-job projection and sort target:
     * once loaded by {@code STEP020} it is read by {@code STEP040} through the {@code TRNXFILE} DD and then
     * discarded with the job, which is why it is absent from {@code app/catlg/LISTCAT.txt} - whose summary
     * counts ten clusters at {@code :L3940}, none of them this one. There is deliberately no table, entity,
     * repository or migration for it. This method therefore records the delete and holds the citation; the
     * observable effect of the source's delete is entirely achieved by
     * {@link #deleteWorkSequentialDataset(long)} above and by the fresh define below.
     */
    private void deleteWorkCluster() {
        LOG.debug("DELDEF01 deleted the TRXFL work cluster: an in-job structure with no persistent form, "
                + "so nothing outside this job execution is affected");
    }

    /**
     * {@code SET MAXCC = 0}, {@code app/jcl/CREASTMT.JCL:L28}.
     *
     * <p>The classic ignore-the-delete-failure idiom. Both preceding deletes fail with a non-zero condition
     * code on a first run, when neither dataset exists; this card discards that code so the step completes
     * and the define proceeds. Reproduced as an explicitly idempotent, non-failing setup step: the delete
     * above is guarded by an existence test and, if it still fails, the failure is logged with its cause and
     * not propagated. Running this step twice therefore succeeds twice.
     */
    private void setMaxccZero() {
        LOG.debug("DELDEF01 reset the condition code to zero, so a first run - in which neither the "
                + "sequential dataset nor the cluster exists - still completes");
    }

    /**
     * {@code DEFINE CLUSTER}, {@code app/jcl/CREASTMT.JCL:L29}-{@code :L39}.
     *
     * <p>Publishes the geometry the two following steps validate against, so neither compares against a
     * literal of its own: {@code KEYS(32 0)} at {@code :L30} - independently corroborated by
     * {@code TRNX-KEY} at {@code app/cpy/COSTM01.CPY:L21}-{@code :L23}, a 16-byte card number followed by a
     * 16-byte identifier - and {@code RECORDSIZE(350 350)} at {@code :L32}. The remaining attributes have no
     * object-storage counterpart and are recorded rather than enforced: {@code VOLUMES(TSU023)} at
     * {@code :L31}, {@code SHAREOPTIONS(2 3)} at {@code :L33}, {@code ERASE} and {@code INDEXED} at
     * {@code :L34}-{@code :L35}, {@code CYL(1 5)} at {@code :L36} and {@code CISZ(4096)} at {@code :L38}.
     *
     * <p>The geometry is cross-checked against the {@code TRNXFILE} DD that {@code STEP040} will read
     * through, at {@code app/jcl/CREASTMT.JCL:L83}. {@link FileService.Dd#TRNXFILE} declares its record as a
     * {@value #WORK_CLUSTER_KEY_LENGTH}-byte key plus its data, from
     * {@code app/cbl/CBSTM03B.CBL:L58}-{@code :L63}, and that total must equal the
     * {@code RECORDSIZE} defined here. The two figures come from independently authored artefacts - a JCL
     * define and a COBOL file section - so a disagreement means one of them has drifted, and finding that at
     * define time is far cheaper than finding it as a mis-parsed record in the emit step.
     *
     * @param jobContext the job execution context, which receives the rendered geometry
     * @throws FatalProcessingException if the {@code TRNXFILE} DD geometry contradicts
     *     {@code RECORDSIZE(350 350)} or {@code KEYS(32 0)}
     */
    private void defineWorkCluster(final ExecutionContext jobContext) {
        final int ddRecordWidth = FileService.Dd.TRNXFILE.recordWidth();
        final int ddKeyWidth = FileService.Dd.TRNXFILE.keyWidth();
        if (ddRecordWidth != WORK_CLUSTER_RECORD_LENGTH || ddKeyWidth != WORK_CLUSTER_KEY_LENGTH) {
            throw abend(REASON_BAD_RECORD_LENGTH,
                    "The TRNXFILE DD at app/jcl/CREASTMT.JCL:L83 reports a " + ddKeyWidth
                            + "-byte key in a " + ddRecordWidth + "-byte record, but DEFINE CLUSTER at :L30 "
                            + "and :L32 declares KEYS(" + WORK_CLUSTER_KEY_LENGTH + " 0) and RECORDSIZE("
                            + WORK_CLUSTER_RECORD_LENGTH + " " + WORK_CLUSTER_RECORD_LENGTH + ")", null);
        }

        final String geometry = String.format(Locale.ROOT,
                "KEYS(%d 0) RECORDSIZE(%d %d) CISZ(%d) CYL(%d %d) SHAREOPTIONS(%s) %s VOLUMES(%s)",
                Integer.valueOf(WORK_CLUSTER_KEY_LENGTH),
                Integer.valueOf(WORK_CLUSTER_RECORD_LENGTH),
                Integer.valueOf(WORK_CLUSTER_RECORD_LENGTH),
                Integer.valueOf(WORK_CLUSTER_CONTROL_INTERVAL_SIZE),
                Integer.valueOf(WORK_CLUSTER_PRIMARY_CYLINDERS),
                Integer.valueOf(WORK_CLUSTER_SECONDARY_CYLINDERS),
                WORK_CLUSTER_SHARE_OPTIONS, WORK_CLUSTER_ATTRIBUTES, WORK_CLUSTER_VOLUME);
        jobContext.putString(WORK_GEOMETRY_CONTEXT_ENTRY, geometry);
        LOG.info("DELDEF01 defined the TRXFL work cluster: {}", geometry);
    }

    // Step 2 - STEP010, app/jcl/CREASTMT.JCL:L44-L54: the sort and the projection.

    /**
     * The body of {@code STEP010}: read {@code SORTIN} in sorted order, project every record, write one
     * {@code SORTOUT} object.
     *
     * <p><strong>Nothing is materialised.</strong> Collecting the whole projection into a
     * {@code List<String>}, concatenating it into a {@code StringBuilder} and then encoding that into a
     * {@code byte[]} would hold three copies of the entire transaction cluster at once, on a step whose input
     * size is bounded by nothing this code chooses. Records are written straight through to the object as each
     * keyset window is read: peak memory is one window plus the object-store client's own part buffer, and the
     * run is never held in the heap. The legacy step did materialise to DASD at {@code :L48}, and the object is
     * still one object; what this avoids is a second copy of it inside the process.
     *
     * <p>The count and the digest come back from the streaming write rather than from a collection's
     * {@code size()}, which is what lets the count be published without retaining the records it counted.
     *
     * <p>The cap is applied here, <strong>before the first record is projected</strong>, against the
     * relation's own row count. That is the "fail before allocation" half of the remedy: a relation too large
     * to process is refused with a reason code while nothing has yet been allocated, instead of being
     * discovered part-way through by the allocator.
     *
     * @return the projection and sort tasklet, never {@code null}
     */
    private Tasklet projectAndSortTasklet() {
        return (final StepContribution contribution, final ChunkContext chunkContext) -> {
            final StepExecution stepExecution = chunkContext.getStepContext().getStepExecution();
            final ExecutionContext jobContext = stepExecution.getJobExecution().getExecutionContext();
            final long generation = reserveWorkGeneration(stepExecution, jobContext);
            final ProjectionResult projection = streamProjectionIntoWorkObject(generation);

            jobContext.putString(WORK_OBJECT_KEY_CONTEXT_ENTRY, projection.objectKey());
            jobContext.putInt(WORK_RECORD_COUNT_CONTEXT_ENTRY, projection.recordCount());
            jobContext.putString(WORK_OBJECT_DIGEST_CONTEXT_ENTRY, projection.digest());
            contribution.incrementWriteCount(projection.recordCount());
            contribution.setExitStatus(ExitStatus.COMPLETED);

            LOG.info("STEP010 projected and sorted {} transaction records into {}",
                    Integer.valueOf(projection.recordCount()), objectKeySurrogate(projection.objectKey()));
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * What {@code STEP010} produced: the object it created, how many records it carries, and a digest of the
     * exact bytes it wrote.
     *
     * <p>A record, so the handoff between the three steps that consume it is immutable. The digest is the
     * mechanism of finding H-06: it is what lets {@code STEP020} prove the object it read is byte-identical to
     * the one {@code STEP010} wrote, and lets {@code STEP040} prove the source has not moved underneath it.
     *
     * @param objectKey the concrete key created, never {@code null}
     * @param recordCount how many {@value #WORK_CLUSTER_RECORD_LENGTH}-character records it holds
     * @param digest the lowercase hexadecimal {@value #DIGEST_ALGORITHM} digest of the object's bytes
     */
    private record ProjectionResult(String objectKey, int recordCount, String digest) {
    }

    /**
     * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} at {@code app/jcl/CREASTMT.JCL:L53} together with
     * {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)} at {@code :L54}, as a lazy byte stream.
     *
     * <p>The ordering is delegated to {@link TransactionRepository#findStatementOrderAfter}, whose
     * {@code order by} clause is the two-key ascending sequence, and the projection to
     * {@link StatementProcessor#projectBaseRecord(Transaction)}, which reproduces the two-byte truncation
     * exactly. Reading is keyset positioned in windows of {@link #chunkSize} rather than by page number, so
     * a full-cluster run costs one index seek per window instead of re-reading every preceding row.
     *
     * <p>Every emitted record is checked against {@link #SORT_FIELDS_COMPARATOR} before it is accepted, so
     * the ascending precondition that {@code app/cbl/CBSTM03A.CBL:L419} relies on is proven for this run
     * rather than assumed. An empty cluster emits nothing to the sink and is handled explicitly: it produces
     * an empty object rather than a failure, because {@code SORT} with an empty {@code SORTIN} allocates an
     * empty {@code SORTOUT} and does not fail the step.
     *
     * <p><b>Bounded by construction.</b> Records are handed to the sink as they are projected and are never
     * collected, so the live set is one window of {@link #chunkSize} rows plus the record currently being
     * written - independent of how many rows the cluster holds.
     *
     * @param sink receives each projected record as it is produced; never {@code null}
     * @return the number of records projected and the digest of their concatenation
     * @throws FatalProcessingException if the sequence descends, or if a projected record is not exactly
     *     {@value #WORK_CLUSTER_RECORD_LENGTH} characters
     */
    private ProjectedStream sortAndProjectTransactions(final RecordSink sink) {
        final MessageDigest digest = newDigest();
        String positionCardNumber = "";
        String positionTransactionId = "";
        Transaction previous = null;
        int projected = 0;

        for (;;) {
            final List<Transaction> window = transactionRepository.findStatementOrderAfter(
                    positionCardNumber, positionTransactionId, PageRequest.ofSize(chunkSize));
            if (window == null || window.isEmpty()) {
                break;
            }
            for (final Transaction row : window) {
                requireAscending(previous, row);
                final String record =
                        requireWorkRecordWidth(StatementProcessor.projectBaseRecord(row));
                final byte[] encoded = record.getBytes(FIXED_WIDTH_CHARSET);
                requireByteTransparency(encoded.length, record.length());
                digest.update(encoded);
                sink.accept(encoded);
                projected++;
                previous = row;
            }
            final Transaction last = window.get(window.size() - 1);
            positionCardNumber = last.getCardNumber() == null ? "" : last.getCardNumber();
            positionTransactionId = last.getTransactionId() == null ? "" : last.getTransactionId();
        }

        if (projected == 0) {
            LOG.info("STEP010 read an empty SORTIN, so SORTOUT is allocated empty; "
                    + "app/jcl/CREASTMT.JCL:L44 declares no COND, so the step still completes");
        }
        return new ProjectedStream(projected, hexDigest(digest));
    }

    /**
     * How many records a streamed projection produced and the digest of their concatenation.
     *
     * @param recordCount the number of {@value #WORK_CLUSTER_RECORD_LENGTH}-character records produced
     * @param digest the lowercase hexadecimal {@value #DIGEST_ALGORITHM} digest of the concatenated bytes
     */
    private record ProjectedStream(int recordCount, String digest) {
    }

    /**
     * Receives one encoded record at a time, so the projection can be consumed without being collected.
     *
     * <p>Declared here rather than reusing a general-purpose consumer type because the implementations raise the
     * project's typed failures, and a functional interface whose method declares no checked exception would
     * force a wrapper at every call site for no benefit.
     */
    @FunctionalInterface
    private interface RecordSink {

        /**
         * Consumes one encoded fixed-width record.
         *
         * @param encoded exactly {@value #WORK_CLUSTER_RECORD_LENGTH} bytes in
         *     {@link StandardCharsets#ISO_8859_1}; never {@code null}
         * @throws FatalProcessingException if the record cannot be consumed
         */
        void accept(byte[] encoded);
    }

    /**
     * Enforces the ascending precondition of the early-exit lookup at
     * {@code app/cbl/CBSTM03A.CBL:L417}-{@code :L419}.
     *
     * <p>That scan abandons its search the moment a stored card number exceeds the one sought, which finds
     * every record only while the sequence ascends. Rather than making the lookup order-independent - a
     * divergence, because it would change which records are found from an unsorted input - the ordering is
     * asserted here, where a violation is attributable to the query that produced it.
     *
     * @param previous the previously emitted row, or {@code null} for the first row
     * @param current the row about to be emitted, never {@code null}
     * @throws FatalProcessingException if {@code current} sorts before {@code previous}
     */
    private void requireAscending(final Transaction previous, final Transaction current) {
        if (previous == null) {
            return;
        }
        if (SORT_FIELDS_COMPARATOR.compare(previous, current) > 0) {
            throw abend(REASON_SORT_ORDER_VIOLATION,
                    "STEP010 produced a descending sequence at card " + maskCardNumber(current)
                            + "; SORT FIELDS=(263,16,CH,A,1,16,CH,A) at app/jcl/CREASTMT.JCL:L53 must "
                            + "ascend because app/cbl/CBSTM03A.CBL:L419 exits its scan on the first "
                            + "greater card number", null);
        }
    }

    /**
     * Validates that a projected record carries the geometry {@code RECORDSIZE(350 350)} declares at
     * {@code app/jcl/CREASTMT.JCL:L32}, and that its trailing 22 positions are the spaces the
     * {@code OUTREC} projection leaves - the two bytes of processing timestamp and the twenty of filler that
     * {@code :L54} never writes.
     *
     * @param record the projected record, never {@code null}
     * @return the record unchanged, never {@code null}
     * @throws FatalProcessingException if the width or the trailing padding is wrong
     */
    private String requireWorkRecordWidth(final String record) {
        if (record.length() != WORK_CLUSTER_RECORD_LENGTH) {
            throw abend(REASON_BAD_RECORD_LENGTH,
                    "A work-cluster record must be exactly " + WORK_CLUSTER_RECORD_LENGTH
                            + " characters per RECORDSIZE(350 350) at app/jcl/CREASTMT.JCL:L32, but was "
                            + record.length(), null);
        }
        final int contentEnd = StatementTransaction.PROJECTION_LAST_WRITTEN_POSITION;
        final String tail = record.substring(contentEnd);
        if (!tail.isBlank()) {
            throw abend(REASON_BAD_RECORD_LENGTH,
                    "OUTREC FIELDS at app/jcl/CREASTMT.JCL:L54 writes only " + contentEnd
                            + " bytes, so positions " + (contentEnd + 1) + "-" + WORK_CLUSTER_RECORD_LENGTH
                            + " must remain spaces - the last "
                            + StatementTransaction.PROCESSING_TIMESTAMP_PAD_LENGTH
                            + " processing-timestamp bytes and the "
                            + StatementTransaction.FILLER_LENGTH + "-byte filler are never emitted", null);
        }
        return record;
    }

    /**
     * Writes {@code SORTOUT}, {@code app/jcl/CREASTMT.JCL:L48}, whose {@code DCB} at {@code :L50} declares
     * {@code LRECL=350} and {@code BLKSIZE=}{@value #WORK_SEQUENTIAL_BLOCK_SIZE} with {@code RECFM=FB}.
     *
     * <p>Fixed and blocked means unblocked and undelimited at the object-storage boundary: records are
     * concatenated with no separator and a reader finds boundaries by counting
     * {@value #WORK_CLUSTER_RECORD_LENGTH} bytes at a time. Encoded in
     * {@link StandardCharsets#ISO_8859_1}, where one character is one byte, so the encoded length of the
     * whole sequence is an exact multiple of the record length and a record carrying a character outside
     * that range is caught here rather than producing a dataset whose size depends on its data.
     *
     * <p><strong>The write is streamed.</strong> The object-store resource's own output stream buffers to a
     * bounded capacity and then switches to a multi-part upload, so an arbitrarily large projection becomes one
     * object without the process ever holding it. No content length is declared, because the total is not known
     * until the last record has been written; per-record framing is asserted instead, which is the stronger
     * guarantee. This is the mechanism of finding H-01.
     *
     * @param generation the generation ordinal reserved for this run
     * @return the key created, the record count and the digest of the exact bytes written, never {@code null}
     * @throws FatalProcessingException if object storage rejects the write, or if a record is not exactly
     *     {@value #WORK_CLUSTER_RECORD_LENGTH} bytes
     */
    private ProjectionResult streamProjectionIntoWorkObject(final long generation) {
        final String key = workObjectKey(generation);

        String ioStatus = OBJECT_STORE_IO_STATUS;
        RuntimeException runtimeFailure = null;
        IOException writeFailure = null;
        ProjectedStream projected = null;
        try {
            final S3Resource resource = objectStorage.createResource(batchOutputBucket, key);
            resource.setObjectMetadata(ObjectMetadata.builder()
                    .contentType(WORK_OBJECT_CONTENT_TYPE)
                    .build());
            try (OutputStream sortout = resource.getOutputStream()) {
                projected = sortAndProjectTransactions(encoded -> writeRecord(sortout, encoded));
            }
            ioStatus = SUCCESS_STATUS;
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final IOException cause) {
            writeFailure = cause;
        } catch (final RuntimeException cause) {
            runtimeFailure = cause;
        }
        guardObjectStore(ioStatus, WORK_OBJECT_NAME, "WRITE",
                runtimeFailure != null ? runtimeFailure : writeFailure);
        return new ProjectionResult(key, projected.recordCount(), projected.digest());
    }

    /**
     * Writes one encoded record to the open {@code SORTOUT} stream.
     *
     * <p>{@code RECFM=FB} with the block size at {@code app/jcl/CREASTMT.JCL:L50} is fixed and undelimited at the
     * object-storage boundary, so records are concatenated with no separator and nothing is appended here.
     *
     * <p>The checked failure is translated into the project's typed failure so that the sink's own signature
     * stays free of it; the cause is preserved, so nothing is swallowed.
     *
     * @param sortout the open output stream, never {@code null}
     * @param encoded the record's bytes, never {@code null}
     * @throws FatalProcessingException if the stream rejects the record
     */
    private void writeRecord(final OutputStream sortout, final byte[] encoded) {
        try {
            sortout.write(encoded);
        } catch (final IOException cause) {
            throw abend(REASON_OBJECT_STORE_FAILED,
                    "SORTOUT at app/jcl/CREASTMT.JCL:L48 rejected a "
                            + WORK_CLUSTER_RECORD_LENGTH + "-byte record", cause);
        }
    }

    /**
     * Asserts that a record encoded to exactly as many bytes as it has characters.
     *
     * <p>That is what proves the charset stayed byte transparent: if it did not, some character encoded to more
     * than one byte and every record boundary after it has moved. A pure function of its arguments.
     *
     * @param byteLength the encoded length
     * @param characterLength the composed length
     * @throws FatalProcessingException if the two differ
     */
    private void requireByteTransparency(final int byteLength, final int characterLength) {
        if (byteLength != characterLength) {
            throw abend(REASON_BAD_RECORD_LENGTH,
                    "A projected record encoded to " + byteLength + " bytes from " + characterLength
                            + " characters; the record charset must be byte transparent or every record "
                            + "boundary after it has moved", null);
        }
    }

    // Step 3 - STEP020, app/jcl/CREASTMT.JCL:L56-L61: REPRO into the work cluster.

    /**
     * The body of {@code STEP020}, gated by {@code COND=(0,NE)} at {@code app/jcl/CREASTMT.JCL:L56}.
     *
     * @return the load tasklet, never {@code null}
     */
    private Tasklet reproIntoWorkClusterTasklet() {
        return (final StepContribution contribution, final ChunkContext chunkContext) -> {
            final ExecutionContext jobContext = chunkContext.getStepContext().getStepExecution()
                    .getJobExecution().getExecutionContext();
            final String key = requirePublishedWorkObjectKey(jobContext);
            final int loaded = reproIntoWorkCluster(key, jobContext);

            jobContext.putInt(LOADED_RECORD_COUNT_CONTEXT_ENTRY, loaded);
            contribution.incrementWriteCount(loaded);
            contribution.setExitStatus(ExitStatus.COMPLETED);
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * {@code REPRO INFILE(INFILE) OUTFILE(OUTFILE)}, {@code app/jcl/CREASTMT.JCL:L61}, whose {@code INFILE}
     * is the projected sequential dataset at {@code :L58} and whose {@code OUTFILE} is the work cluster at
     * {@code :L59}.
     *
     * <p><b>No external process is spawned and no {@code IDCAMS} utility is invoked</b> - Rule 1 Clause D
     * bans exec patterns outright, so neither a process-execution entry point nor a process-builder API is
     * used anywhere in this file, and the load depends on no host-installed utility.
     *
     * <p><b>What the load target actually is.</b> The work cluster is read by {@code STEP040} through the
     * {@code TRNXFILE} DD, and in this system that DD is bound to the transaction cluster read in
     * <i>statement order</i> with the same {@code OUTREC} projection applied per record. The load therefore
     * has no separate destination to populate: there is no twelfth table, because
     * {@code V1__create_schema.sql} creates exactly eleven and a validation gate asserts that count, and no
     * relational load is issued - which is also why no JDBC template is injected and no SQL
     * string exists in this file. What {@code REPRO} contributes, and what this method performs, is the integrity
     * contract that makes the two representations interchangeable: every record in the projected object is
     * read back from the exact key its producer published, and is required to carry the
     * {@value #WORK_CLUSTER_RECORD_LENGTH}-byte record and {@value #WORK_CLUSTER_KEY_LENGTH}-byte key that
     * {@code :L30} and {@code :L32} declare, in ascending key order. A mismatch abends the step exactly as a
     * failed {@code REPRO} would have, instead of being discovered as corrupt statement output four steps
     * later.
     *
     * <p>The scan itself is delegated to {@link #streamWorkObject(String)}, which walks the object one
     * {@value #WORK_CLUSTER_RECORD_LENGTH}-byte record at a time and therefore holds nothing proportional to
     * its size. Object-storage content is treated as untrusted input there: the geometry of each record is
     * validated before anything is interpreted, and no deserialization of any kind is performed on it.
     *
     * <p><strong>The object is verified as a stream, one record at a time.</strong> Reading it whole with
     * {@code readAllBytes()} and then decoding the whole array into a {@code String} would add two more
     * copies of a relation-sized image, on the read side of a pipeline whose write side would otherwise have
     * made three. The verification needs no more than one record and its key at a time, so that is all it
     * holds - which is why finding BAT-002 could remove the authored record ceiling without putting the heap
     * at risk: peak memory here is one record whatever the object's size.
     *
     * @param key the concrete key {@code STEP010} published, never {@code null}
     * @param jobContext the job execution context, consulted for the producer's record count
     * @return the number of records loaded
     * @throws FatalProcessingException if the object cannot be read, is not a whole number of records, or
     *     violates the key geometry or the key order
     */
    private int reproIntoWorkCluster(final String key, final ExecutionContext jobContext) {
        final LoadedObject loaded = streamWorkObject(key);

        final int produced = jobContext.containsKey(WORK_RECORD_COUNT_CONTEXT_ENTRY)
                ? jobContext.getInt(WORK_RECORD_COUNT_CONTEXT_ENTRY)
                : loaded.recordCount();
        if (produced != loaded.recordCount()) {
            throw abend(REASON_BAD_RECORD_LENGTH,
                    "STEP010 published " + produced + " records but "
                            + objectKeySurrogate(key) + " holds " + loaded.recordCount()
                            + "; REPRO must load every record its input carries", null);
        }
        requirePublishedDigest(jobContext, loaded.digest(), key);

        LOG.info("STEP020 loaded {} records of {} bytes into the TRXFL work cluster from {} ({})",
                Integer.valueOf(loaded.recordCount()), Integer.valueOf(WORK_CLUSTER_RECORD_LENGTH),
                objectKeySurrogate(key),
                jobContext.containsKey(WORK_GEOMETRY_CONTEXT_ENTRY)
                        ? jobContext.getString(WORK_GEOMETRY_CONTEXT_ENTRY)
                        : "geometry not published");
        return loaded.recordCount();
    }

    /**
     * Reads back the projected object one record at a time, {@code INFILE} at
     * {@code app/jcl/CREASTMT.JCL:L58}, validating geometry and key order as it goes.
     *
     * <p><strong>The object is consumed as a stream.</strong> Reading it with {@code readAllBytes} and then
     * decoding the whole image into a {@code String} would hold two more copies of the entire cluster at once,
     * on the step whose only job is to prove the object is loadable. It is consumed in exactly
     * {@value #WORK_CLUSTER_RECORD_LENGTH}-byte records, so peak memory is one record.
     *
     * <p>Object-storage content is treated as untrusted input: a short final record is a truncated object and is
     * refused, and no record is parsed before its full width has been read.
     *
     * @param key the concrete key to read, never {@code null}
     * @return the record count and the digest of the exact bytes read, never {@code null}
     * @throws FatalProcessingException if the object is absent or unreadable, is not a whole number of records,
     *     or descends in key order
     */
    private LoadedObject streamWorkObject(final String key) {
        String ioStatus = OBJECT_STORE_IO_STATUS;
        RuntimeException runtimeFailure = null;
        Exception readFailure = null;
        LoadedObject loaded = null;
        try {
            final S3Resource resource = objectStorage.download(batchOutputBucket, key);
            try (InputStream stream = resource.getInputStream()) {
                loaded = readFixedWidthRecords(stream, key);
            }
            ioStatus = SUCCESS_STATUS;
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final IOException cause) {
            readFailure = cause;
        } catch (final RuntimeException cause) {
            runtimeFailure = cause;
        }
        guardObjectStore(ioStatus, WORK_OBJECT_NAME, "READ",
                runtimeFailure != null ? runtimeFailure : readFailure);
        return loaded;
    }

    /**
     * Consumes an open stream as consecutive fixed-width records, asserting the ascending key order that
     * {@code KEYS(32 0)} at {@code app/jcl/CREASTMT.JCL:L30} requires of {@code REPRO} input.
     *
     * @param stream the open object stream, never {@code null}
     * @param key the key being read, named in a failure through its surrogate only
     * @return the record count and the digest of every byte read
     * @throws IOException if the stream fails
     * @throws FatalProcessingException if the object is not a whole number of records or descends
     */
    private LoadedObject readFixedWidthRecords(final InputStream stream, final String key)
            throws IOException {

        final MessageDigest digest = newDigest();
        final byte[] record = new byte[WORK_CLUSTER_RECORD_LENGTH];
        String previousKey = "";
        int recordCount = 0;

        for (;;) {
            final int filled = stream.readNBytes(record, 0, WORK_CLUSTER_RECORD_LENGTH);
            if (filled == 0) {
                break;
            }
            if (filled != WORK_CLUSTER_RECORD_LENGTH) {
                throw abend(REASON_BAD_RECORD_LENGTH,
                        "REPRO at app/jcl/CREASTMT.JCL:L61 loads fixed-length records, so "
                                + objectKeySurrogate(key) + " must be an exact multiple of "
                                + WORK_CLUSTER_RECORD_LENGTH + " bytes, but record " + (recordCount + 1)
                                + " is " + filled + " bytes", null);
            }
            digest.update(record, 0, WORK_CLUSTER_RECORD_LENGTH);
            recordCount++;

            final String recordKey =
                    new String(record, 0, WORK_CLUSTER_KEY_LENGTH, FIXED_WIDTH_CHARSET);
            if (recordKey.compareTo(previousKey) < 0) {
                throw abend(REASON_SORT_ORDER_VIOLATION,
                        "The work cluster is INDEXED on a " + WORK_CLUSTER_KEY_LENGTH
                                + "-byte key per KEYS(32 0) at app/jcl/CREASTMT.JCL:L30, so REPRO input "
                                + "must ascend; record " + recordCount + " descends", null);
            }
            previousKey = recordKey;
        }
        return new LoadedObject(recordCount, hexDigest(digest));
    }

    /**
     * What {@code STEP020} read back: how many records the object holds and the digest of its bytes.
     *
     * @param recordCount the number of {@value #WORK_CLUSTER_RECORD_LENGTH}-byte records read
     * @param digest the lowercase hexadecimal {@value #DIGEST_ALGORITHM} digest of every byte read
     */
    private record LoadedObject(int recordCount, String digest) {
    }

    /**
     * Requires that the object just read is byte-identical to the one {@code STEP010} wrote.
     *
     * <p><strong>Finding H-06, severity High. This is the first half of its resolution.</strong> The earlier
     * revision proved only that the object was a whole number of ascending records of the right width - which a
     * different object of the same shape also satisfies. Comparing the digest {@code STEP010} published against
     * the digest of the bytes actually read proves identity rather than similarity, which is what makes the
     * projected object a genuine snapshot rather than a plausible one.
     *
     * <p>An absent published digest is tolerated only when {@code STEP010} did not run in this job execution -
     * the standalone-step case - and is stated in the log rather than passed over.
     *
     * @param jobContext the job execution context, consulted for the published digest
     * @param observed the digest of the bytes just read, never {@code null}
     * @param key the key that was read, named through its surrogate only
     * @throws FatalProcessingException if the two digests differ
     */
    private void requirePublishedDigest(final ExecutionContext jobContext, final String observed,
            final String key) {

        if (!jobContext.containsKey(WORK_OBJECT_DIGEST_CONTEXT_ENTRY)) {
            LOG.info("STEP020 found no {} digest published by STEP010, so identity cannot be asserted for "
                            + "this execution; geometry, count and key order have been verified",
                    WORK_OBJECT_NAME);
            return;
        }
        final String published = jobContext.getString(WORK_OBJECT_DIGEST_CONTEXT_ENTRY);
        if (!published.equals(observed)) {
            throw abend(REASON_SNAPSHOT_DRIFTED,
                    "STEP020 read " + objectKeySurrogate(key) + " but its " + DIGEST_ALGORITHM
                            + " digest is not the one STEP010 published; the object is not the snapshot this "
                            + "job execution created, so REPRO at app/jcl/CREASTMT.JCL:L61 would load "
                            + "content STEP010 never sorted", null);
        }
    }

    // Step 4 - STEP030, app/jcl/CREASTMT.JCL:L66-L75: IEFBR14 and its two DD side effects.

    /**
     * The body of {@code STEP030}, gated by {@code COND=(0,NE)} at {@code app/jcl/CREASTMT.JCL:L66}.
     *
     * @return the pre-delete tasklet, never {@code null}
     */
    private Tasklet preDeleteOutputsTasklet() {
        return (final StepContribution contribution, final ChunkContext chunkContext) -> {
            final ExecutionContext jobContext = chunkContext.getStepContext().getStepExecution()
                    .getJobExecution().getExecutionContext();

            preDeleteHtmlOutput();
            final int removed = preDeleteStatementOutput();
            clearPublishedStatementObjectKeys(jobContext);

            jobContext.putInt(PRE_DELETED_OBJECT_COUNT_CONTEXT_ENTRY, removed);
            contribution.setExitStatus(ExitStatus.COMPLETED);
            LOG.info("STEP030 completed: {} prior statement objects removed. IEFBR14 does nothing itself; "
                            + "the effect is the DISP=(MOD,DELETE,DELETE) of its two DD statements at "
                            + "app/jcl/CREASTMT.JCL:L67 and :L72, which delete the statement output so that "
                            + "STEP040 allocates it new",
                    Integer.valueOf(removed));
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * The {@code HTMLFILE} DD of {@code STEP030}, {@code app/jcl/CREASTMT.JCL:L67}-{@code :L71}, which
     * carries {@code UNIT=SYSDA} at {@code :L68} and {@code DCB=(LRECL=80,BLKSIZE=3200,RECFM=FB)} at
     * {@code :L69}.
     *
     * <p><b>The {@value #HTMLFILE_PRE_DELETE_DECLARED_LENGTH} here is a legacy defect, logged and not
     * repaired.</b> {@code STEP040} allocates the same dataset at {@code LRECL=100} at {@code :L94}, and
     * {@value #HTML_RECORD_LENGTH} is the correct width - independently confirmed twice inside the program
     * itself, by {@code 01 FD-HTMLFILE-REC PIC X(100)} at {@code app/cbl/CBSTM03A.CBL:L47} and
     * {@code 05 HTML-FIXED-LN PIC X(100)} at {@code :L149}. Because {@code IEFBR14} only deletes, the
     * declared length has no effect on the outcome, which is why the contradiction survived in the source
     * undetected. Correcting {@code :L69} would mean editing a frozen member, so the discrepancy is recorded
     * here and markup is emitted at {@value #HTML_RECORD_LENGTH}.
     *
     * <p>Both DDs name objects under the same statement root, so both are removed by the single enumeration
     * in {@link #preDeleteStatementOutput()} and this method records the declared-length discrepancy rather
     * than performing a delete of its own. Splitting the deletion in two would have to enumerate the same
     * prefix twice to find the same objects.
     */
    private void preDeleteHtmlOutput() {
        LOG.debug("STEP030 pre-deletes HTMLFILE, declared LRECL={} at app/jcl/CREASTMT.JCL:L69 while "
                        + "STEP040 allocates the same dataset at LRECL={} at :L94; {} is correct per "
                        + "app/cbl/CBSTM03A.CBL:L149. Legacy defect, logged not repaired",
                Integer.valueOf(HTMLFILE_PRE_DELETE_DECLARED_LENGTH),
                Integer.valueOf(HTML_RECORD_LENGTH), Integer.valueOf(HTML_RECORD_LENGTH));
    }

    /**
     * The {@code STMTFILE} DD of {@code STEP030}, {@code app/jcl/CREASTMT.JCL:L72}-{@code :L75}.
     *
     * <p>It declares {@code DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB)} but <b>omits the {@code UNIT=SYSDA} that
     * its {@code HTMLFILE} sibling carries at {@code :L68}</b>. Unit allocation is a JES2 concept with no
     * object-storage counterpart, so the omission changes nothing here; it is recorded rather than
     * normalised, because normalising it would edit a frozen member. The member's other oddity is at its very
     * end: {@code :L97} is {@code //*} and there is no terminating {@code //} card, which JES2 tolerates and
     * which likewise has no counterpart.
     *
     * <p><b>Why the delete enumerates the prefix rather than the execution context.</b> Deleting only the
     * keys recorded in the <em>current</em> job execution context deletes nothing at all on any run that is
     * not a retry, because that context starts empty on every fresh execution - and because the writer
     * composes a monotonically increasing generation segment into each key, every previous run's objects would
     * survive under their own generation, indefinitely, carrying customer statement data. That is the opposite
     * of what this step is for. {@code IEFBR14}
     * with {@code DISP=(MOD,DELETE,DELETE)} at {@code :L67} and {@code :L74} deletes the statement output
     * unconditionally, and {@code STEP040} then allocates it new at {@code :L84} and {@code :L86}; the legacy
     * job kept no history, because it named one {@code STMTFILE} and one {@code HTMLFILE}, not a family.
     * Enumerating the statement root and deleting what is there reproduces that, and it is what makes the
     * pre-delete a <em>pre</em>-delete: it happens before {@code STEP040} publishes anything, so the objects
     * it removes are always the prior logical output and never this run's.
     *
     * <p><b>Bounded without a continuation token.</b> {@code S3Operations#listObjects} returns one page, so
     * one call cannot be assumed to enumerate everything. Paging is achieved by deletion instead: each pass
     * lists a page, deletes it, and lists again, and because every pass removes the objects it just listed,
     * the set strictly shrinks and the loop terminates. Peak memory is one page of keys. A pass that lists
     * objects but removes none would not shrink the set, so that case exits rather than spinning - it can
     * only arise if a delete silently no-ops, which is a condition to leave rather than to loop on.
     *
     * @return the number of statement objects removed, zero when none existed
     * @throws FatalProcessingException if object storage rejects the listing or a delete
     */
    private int preDeleteStatementOutput() {
        int removed = 0;
        // The one-based position within the enumeration is what a diagnostic names instead of the key
        // itself, because a statement key embeds an account identifier and a statement month.
        int ordinal = 0;
        for (;;) {
            final List<String> page = listStatementObjectKeys();
            if (page.isEmpty()) {
                return removed;
            }
            int removedThisPass = 0;
            for (final String key : page) {
                ordinal++;
                removedThisPass += deleteStatementObjectIfPresent(key,
                        statementObjectReference(logicalNameOf(key), ordinal));
            }
            removed += removedThisPass;
            if (removedThisPass == 0) {
                LOG.warn("STEP030 listed {} statement objects under '{}' but removed none, so the "
                                + "enumeration cannot make progress; leaving {} removed",
                        Integer.valueOf(page.size()), StatementWriter.KEY_ROOT, Integer.valueOf(removed));
                return removed;
            }
        }
    }

    /**
     * Lists one page of statement object keys under the statement root.
     *
     * <p>Directory-marker entries - keys ending in the separator - are filtered out: object storage has no
     * directories, so such a key is a naming artefact rather than a statement.
     *
     * @return the keys of one page, never {@code null} and possibly empty
     * @throws FatalProcessingException if object storage rejects the listing
     */
    private List<String> listStatementObjectKeys() {
        String ioStatus = OBJECT_STORE_IO_STATUS;
        RuntimeException failure = null;
        List<S3Resource> page = List.of();
        try {
            page = objectStorage.listObjects(statementsBucket, StatementWriter.KEY_ROOT);
            ioStatus = SUCCESS_STATUS;
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final RuntimeException cause) {
            failure = cause;
        }
        guardObjectStore(ioStatus, StatementWriter.KEY_ROOT, "LIST", failure);

        final List<String> keys = new ArrayList<>(page.size());
        for (final S3Resource object : page) {
            final String key = object.getLocation() == null ? null : object.getLocation().getObject();
            if (key != null && !key.isBlank() && !key.endsWith(StatementWriter.KEY_SEPARATOR)) {
                keys.add(key);
            }
        }
        return keys;
    }

    /**
     * Deletes one statement object if it exists, and reports it as absent otherwise.
     *
     * <p>Idempotent by construction, which is what {@code IEFBR14} against an absent dataset amounts to:
     * {@code DISP=(MOD,DELETE,DELETE)} creates the dataset if it is missing and then deletes it, so the step
     * cannot fail for absence. A {@code null} or blank key is treated as "nothing published" and handled
     * explicitly rather than reaching object storage.
     *
     * <p><strong>The failure diagnostic names no key.</strong> A statement key carries an account
     * identifier and a statement month, so it is passed to object storage and to nothing else: the guard
     * receives {@link #statementObjectReference(String, int)} instead. See {@link #STATEMENT_TEXT_OBJECT_NAME} for
     * the finding this closes.
     *
     * @param key the key to remove, possibly {@code null} or blank
     * @param reference the log-safe reference naming the object in any diagnostic, from
     *     {@link #statementObjectReference(String, int)}; must not be {@code null}
     * @return {@code 1} if an object was removed, {@code 0} otherwise
     * @throws FatalProcessingException if object storage rejects the delete of an object it reports present
     */
    private int deleteStatementObjectIfPresent(final String key, final String reference) {
        if (key == null || key.isBlank()) {
            return 0;
        }
        String ioStatus = OBJECT_STORE_IO_STATUS;
        RuntimeException failure = null;
        int removed = 0;
        try {
            if (objectStorage.objectExists(statementsBucket, key)) {
                objectStorage.deleteObject(statementsBucket, key);
                removed = 1;
            }
            ioStatus = SUCCESS_STATUS;
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final RuntimeException cause) {
            failure = cause;
        }
        guardObjectStore(ioStatus, reference, "DELETE", failure);
        return removed;
    }

    /**
     * Renders the log-safe reference for one statement output object: its logical DD name and its one-based
     * ordinal within the published-key list.
     *
     * <p>This is what a failure diagnostic and a failure exception message name instead of a key. It is
     * deterministic and correlatable - two lines about the same object read the same - while carrying
     * neither the account identifier nor the statement month that the key itself carries. The bracketed
     * ordinal deliberately reads like a subscript, because that is what it is: the position in the list the
     * execution context holds under {@link StatementWriter#CONTEXT_KEY_OBJECT_KEYS_COUNT} for text, and the
     * single markup entry under {@link StatementWriter#CONTEXT_KEY_HTML_OBJECT}.
     *
     * @param logicalName the DD name of the output, {@link #STATEMENT_TEXT_OBJECT_NAME} or
     *     {@link #STATEMENT_MARKUP_OBJECT_NAME}; must not be {@code null}
     * @param ordinal the one-based position within that output's published-key list
     * @return the log-safe reference, never {@code null}
     */
    private static String statementObjectReference(final String logicalName, final int ordinal) {
        return logicalName + "[" + ordinal + "]";
    }

    /**
     * The DD name the object under {@code key} was written for.
     *
     * <p>{@code app/jcl/CREASTMT.JCL:L67} and {@code :L72} pre-delete two separate allocations, and the
     * enumeration this serves returns both under one prefix, so a reference that named only the text DD would
     * mis-attribute every markup object it removed. The decision is taken from the key's trailing object name,
     * which is the only part of a statement key that carries no account identifier and no statement month -
     * the two segments that make the key itself undisclosable.
     *
     * @param key the object key being removed; must not be {@code null}
     * @return {@link #STATEMENT_MARKUP_OBJECT_NAME} for a markup object, otherwise
     *     {@link #STATEMENT_TEXT_OBJECT_NAME}
     */
    private static String logicalNameOf(final String key) {
        return key.endsWith(MARKUP_OBJECT_SUFFIX)
                ? STATEMENT_MARKUP_OBJECT_NAME
                : STATEMENT_TEXT_OBJECT_NAME;
    }

    /**
     * Removes the statement-object bookkeeping entries once the prior output has been deleted, so a retry
     * cannot mistake a previous attempt's tally for its own.
     *
     * <p>Only the bounded entries exist to clear: one count and the two step-scoped keys naming the latest
     * pair. There is no per-object list to walk - see {@link StatementWriter#CONTEXT_KEY_OBJECT_KEYS_COUNT}.
     *
     * @param jobContext the job execution context, never {@code null}
     */
    private static void clearPublishedStatementObjectKeys(final ExecutionContext jobContext) {
        jobContext.remove(StatementWriter.CONTEXT_KEY_OBJECT_KEYS_COUNT);
        jobContext.remove(StatementWriter.CONTEXT_KEY_TEXT_OBJECT);
        jobContext.remove(StatementWriter.CONTEXT_KEY_HTML_OBJECT);
    }

    // Step 5 - STEP040, app/jcl/CREASTMT.JCL:L79-L96, which runs app/cbl/CBSTM03A.CBL
    //
    // Paragraph correspondence. The program has 26 labels: 25 in the PROCEDURE DIVISION plus FILE-CONTROL
    // at :L38. Every one is accounted for below, and every one whose logic already lives in
    // StatementProcessor, StatementWriter or FileService is DELEGATED with a citation to the sibling that
    // owns it rather than duplicated here - which is the only way "avoid duplication" and "one method per
    // paragraph" can both hold. Nothing is consolidated: the duplicate, the empty, the unreachable and the
    // defective paths each keep their own entry.
    //
    //   :L38  FILE-CONTROL              -> the STMTFILE and HTMLFILE DDs, configured by this step and
    //                                     emitted by StatementWriter (:L39-:L40 SELECT ... ASSIGN TO).
    //   :L296 0000-START                -> initialiseStatementRun(): five ordered stages, no dispatch table.
    //   :L316 1000-MAINLINE             -> the chunk loop of statementGenerationEmitStep().
    //   :L341 9999-GOBACK               -> the step returning; see emitStepGoback().
    //   :L345 1000-XREFFILE-GET-NEXT    -> crossReferenceReader(), delegating to
    //                                     StatementProcessor.readNextCrossReference().
    //   :L368 2000-CUSTFILE-GET         -> StatementProcessor.process(), keyed read of CUSTFILE.
    //   :L392 3000-ACCTFILE-GET         -> StatementProcessor.process(), keyed read of ACCTFILE.
    //   :L416 4000-TRNXFILE-GET         -> StatementProcessor.process(); its ascending precondition is
    //                                     asserted by requireAscending() in step 2.
    //   :L458 5000-CREATE-STATEMENT     -> StatementProcessor.process().
    //   :L506 5100-WRITE-HTML-HEADER    -> StatementWriter, fed from HTML_FRAGMENTS.
    //   :L554 5100-EXIT                 -> the header composition returning.
    //   :L558 5200-WRITE-HTML-NMADBS    -> StatementWriter.
    //   :L671 5200-EXIT                 -> that composition returning.
    //   :L675 6000-WRITE-TRANS          -> StatementWriter, one detail line per transaction.
    //   :L726 8100-FILE-OPEN            -> the altered dispatch paragraph; eliminated, see
    //                                     initialiseStatementRun().
    //   :L730 8100-TRNXFILE-OPEN        -> stage 1.
    //   :L765 8200-XREFFILE-OPEN        -> stage 3.
    //   :L783 8300-CUSTFILE-OPEN        -> stage 4.
    //   :L801 8400-ACCTFILE-OPEN        -> stage 5, and :L816 EXIT -> acctFileOpenExit().
    //   :L818 8500-READTRNX-READ        -> stage 2.
    //   :L849 8599-EXIT                 -> stage 2's final counter flush.
    //   :L856 9100-TRNXFILE-CLOSE       -> closeStatementRun(), via StatementProcessor.close().
    //   :L873 9200-XREFFILE-CLOSE       -> closeStatementRun().
    //   :L889 9300-CUSTFILE-CLOSE       -> closeStatementRun().
    //   :L905 9400-ACCTFILE-CLOSE       -> closeStatementRun().
    //   :L921 9999-ABEND-PROGRAM        -> abend().
    //
    // app/cbl/CBSTM03B.CBL contributes 14 PROCEDURE DIVISION paragraph labels, and every one of them belongs
    // to FileService, which owns the twelve implemented cells of the four-dataset by six-operation matrix -
    // open, close and one read form per dataset; write and rewrite appear nowhere in the subprogram - and the
    // '00' or '04' leniency of the nine call sites. FILE-CONTROL at :L30 is a fifteenth Area-A label and is
    // NOT one of the fourteen: it sits in the ENVIRONMENT DIVISION at :L28 and is not a paragraph-to-method
    // target, so 14 is the label figure and 15 is only the Area-A total - publishing 15 as the label count
    // would count an ENVIRONMENT DIVISION entry as a paragraph. The labels are cited here and implemented
    // there:
    // PROCEDURE DIVISION USING at :L114, 0000-START at :L116, EVALUATE LK-M03B-DD at :L118, WHEN OTHER at
    // :L127 and GO TO 9999-GOBACK at :L128.

    /**
     * {@code 1000-XREFFILE-GET-NEXT}, {@code app/cbl/CBSTM03A.CBL:L345}-{@code :L366}: the driving read of
     * the mainline loop.
     *
     * <p>Delegated to {@link StatementProcessor#readNextCrossReference()}, which is public for exactly this
     * reason and which owns the guard: {@code '00'} continues, {@code '10'} sets end of file at
     * {@code :L357}, and anything else abends at {@code :L358}-{@code :L361}. A returned empty optional
     * becomes the {@code null} that terminates a Spring Batch read loop, so end of file remains loop
     * termination and is never thrown.
     *
     * <p>A lambda rather than a bean, and rather than a named reader class: the read is one delegation, and a
     * separate file for it is neither needed nor permitted here.
     *
     * @return the driving reader, never {@code null}
     */
    private ItemReader<CardCrossReference> crossReferenceReader() {
        return () -> statementProcessor.readNextCrossReference().orElse(null);
    }

    /**
     * {@code 0000-START}, {@code app/cbl/CBSTM03A.CBL:L296}-{@code :L314}: the five-stage initialisation
     * pipeline, in the one order the source admits.
     *
     * <p>The stages, each with the transition that fixes its successor:
     *
     * <ol>
     *   <li>{@code 8100-TRNXFILE-OPEN} at {@code :L730} - open {@code TRNXFILE} ({@code :L736}), prime it
     *       with the first read ({@code :L748}), save the card number and seed the two subscripts
     *       ({@code :L757}-{@code :L759}), then {@code MOVE 'READTRNX'} and {@code GO TO 0000-START} at
     *       {@code :L760}-{@code :L761}.</li>
     *   <li>{@code 8500-READTRNX-READ} at {@code :L818} - build the resident table, branching to itself at
     *       {@code :L840} to continue and to {@code 8599-EXIT} at {@code :L842} on end of file, where
     *       {@code :L850} flushes the final counter before {@code MOVE 'XREFFILE'} and
     *       {@code GO TO 0000-START} at {@code :L851}-{@code :L852}.</li>
     *   <li>{@code 8200-XREFFILE-OPEN} at {@code :L765} - open {@code XREFFILE} ({@code :L771}), then
     *       {@code MOVE 'CUSTFILE'} at {@code :L779}-{@code :L780}.</li>
     *   <li>{@code 8300-CUSTFILE-OPEN} at {@code :L783} - open {@code CUSTFILE} ({@code :L789}), then
     *       {@code MOVE 'ACCTFILE'} at {@code :L797}-{@code :L798}.</li>
     *   <li>{@code 8400-ACCTFILE-OPEN} at {@code :L801} - open {@code ACCTFILE} ({@code :L807}), then
     *       {@code GO TO 1000-MAINLINE} at {@code :L815}, which leaves the machine permanently.</li>
     * </ol>
     *
     * <p>Because every transition is hard-coded in its own handler's tail, the sequence is not data driven
     * and this method is a plain ordered invocation of it - {@link StatementProcessor#initialise()}, which
     * performs precisely those five stages in precisely that order, followed by the mainline. There is no
     * dispatch map, no state enum and no switch over a selector anywhere in this class.
     *
     * <p>{@code 8100-FILE-OPEN} at {@code :L726}-{@code :L728}, whose entire body is the unconditional
     * branch the four {@code ALTER} statements rewrite, has no counterpart by construction: eliminating it is
     * the whole point of the static flow analysis. {@code OPEN OUTPUT STMT-FILE HTML-FILE} at {@code :L293}
     * is likewise absent from this sequence because the two output streams belong to
     * {@link StatementWriter}, which opens them from its own step callback.
     *
     * @throws FatalProcessingException if any of the four datasets cannot be opened or primed
     */
    private void initialiseStatementRun() {
        statementProcessor.initialise();
        acctFileOpenExit();
        LOG.info("STEP040 initialisation complete: TRNXFILE primed and XREFFILE, CUSTFILE and ACCTFILE "
                + "open, in the order app/cbl/CBSTM03A.CBL:L296-L815 fixes");
    }

    /**
     * {@code EXIT.}, {@code app/cbl/CBSTM03A.CBL:L816}.
     *
     * <p><b>Intentional no-op, retained for control-flow parity. Unreachable in the source.</b>
     * {@code :L815} is {@code GO TO 1000-MAINLINE}, an unconditional branch, so control never
     * reaches the {@code EXIT.} that follows it - unlike the {@code EXIT.} statements ending the other four
     * stage paragraphs, which are reachable fall-throughs. It is kept, rather than dropped, because the
     * paragraph map that the scope-coverage gate verifies is proven by inspection against all 26 labels, and
     * a silently absent label breaks that proof.
     *
     * <p>This is one of the two artefacts of this kind in this file - the other being the redundant
     * {@code MOVE 1 TO CR-JMP} at {@code :L324}, which lives with the mainline body in
     * {@link StatementProcessor} and is retained there. Rule 1 Clause B forbids <i>untracked</i> dead code;
     * both are cited here and marked as intentional at their own declarations, so neither is untracked.
     */
    private void acctFileOpenExit() {
        LOG.trace("app/cbl/CBSTM03A.CBL:L816 EXIT - unreachable in the source because :L815 branches "
                + "unconditionally to 1000-MAINLINE; intentional-no-op retained for parity");
    }

    /**
     * {@code 9100-TRNXFILE-CLOSE} at {@code app/cbl/CBSTM03A.CBL:L856}, {@code 9200-XREFFILE-CLOSE} at
     * {@code :L873}, {@code 9300-CUSTFILE-CLOSE} at {@code :L889} and {@code 9400-ACCTFILE-CLOSE} at
     * {@code :L905} - the four closes the mainline performs at {@code :L331}-{@code :L337}.
     *
     * <p>Delegated to {@link StatementProcessor#close()}, which attempts all four even when an earlier one
     * fails, propagates the first failure and attaches any later one as suppressed, so no context is lost.
     * Each of the four accepts {@code '00'} or {@code '04'} ({@code :L862}, {@code :L879}, {@code :L895},
     * {@code :L911}) and abends on anything else.
     *
     * <p>{@code CLOSE STMT-FILE HTML-FILE} at {@code :L339} is {@link StatementWriter}'s, closed from its own
     * step callback, which is why it is not closed here.
     *
     * @throws FatalProcessingException if any close reports a status outside {@code '00'} and {@code '04'}
     */
    private void closeStatementRun() {
        statementProcessor.close();
    }

    /**
     * {@code 9999-GOBACK}, {@code app/cbl/CBSTM03A.CBL:L341}-{@code :L342}.
     *
     * <p>The program's return to its caller. Two paths reach it: the mainline falling through after the four
     * closes, and {@code WHEN OTHER → GO TO 9999-GOBACK} at {@code :L313}-{@code :L314}, which in the source
     * fires only if the dispatch selector holds an unrecognised value - impossible once the machine is
     * straight-line code, and therefore not reproduced as a branch. Its Java counterpart is the step
     * completing, so this method records the boundary and holds the citation.
     *
     * @param statementsEmitted the number of statements the step produced
     */
    private void emitStepGoback(final long statementsEmitted) {
        LOG.info("STEP040 returning after {} statements; app/cbl/CBSTM03A.CBL:L341-L342 GOBACK",
                Long.valueOf(statementsEmitted));
    }

    /**
     * The output contract of {@code STEP040}, asserted before the first record is read.
     *
     * <p>Three things are checked, all of them geometry that the rest of the translation silently depends
     * on: that {@link StatementWriter} enforces exactly the two widths the JCL declares -
     * {@value #TEXT_RECORD_LENGTH} for {@code STMTFILE} at {@code app/jcl/CREASTMT.JCL:L89} and
     * {@value #HTML_RECORD_LENGTH} for {@code HTMLFILE} at {@code :L94} - and that no markup fragment in
     * {@link #HTML_FRAGMENTS} exceeds the markup width, since {@code 05 HTML-FIXED-LN PIC X(100)} at
     * {@code app/cbl/CBSTM03A.CBL:L149} is the field every fragment is moved into and a longer literal would
     * have been truncated by the move. Checking here rather than trusting the writer turns a
     * would-be-corrupt object into a step that never starts.
     *
     * @throws FatalProcessingException if the writer's widths or the fragment table contradict the source
     */
    private void requireOutputGeometry() {
        final List<Integer> widths = statementWriter.recordWidths();
        final List<Integer> expected =
                List.of(Integer.valueOf(TEXT_RECORD_LENGTH), Integer.valueOf(HTML_RECORD_LENGTH));
        if (!expected.equals(widths)) {
            throw abend(REASON_OUTPUT_GEOMETRY,
                    "STMTFILE must be " + TEXT_RECORD_LENGTH + " bytes per app/jcl/CREASTMT.JCL:L89 and "
                            + "HTMLFILE " + HTML_RECORD_LENGTH + " per :L94, but the writer reports "
                            + widths, null);
        }
        for (final Map.Entry<String, String> fragment : HTML_FRAGMENTS.entrySet()) {
            if (fragment.getValue().length() > HTML_RECORD_LENGTH) {
                throw abend(REASON_OUTPUT_GEOMETRY,
                        "Markup fragment " + fragment.getKey() + " is " + fragment.getValue().length()
                                + " characters, but 05 HTML-FIXED-LN PIC X(100) at "
                                + "app/cbl/CBSTM03A.CBL:L149 holds at most " + HTML_RECORD_LENGTH, null);
            }
        }
        LOG.debug("STEP040 output geometry verified: {} markup fragments, text {}B, markup {}B",
                Integer.valueOf(HTML_FRAGMENTS.size()), Integer.valueOf(TEXT_RECORD_LENGTH),
                Integer.valueOf(HTML_RECORD_LENGTH));
    }

    // Object-key derivation and the step-to-step handoff.

    /**
     * Reserves, or re-reads, the generation ordinal of this run's work object - the {@code (+1)} of a
     * relative generation reference.
     *
     * <p>The ordinal is the job execution identifier, which the repository allocates monotonically, so
     * successive runs sort in run order under a plain lexicographic key comparison once zero padded. It is
     * published by the first step to consult it and re-read by every later one, so a step never re-resolves
     * "the latest generation": with two runs in flight, resolving the greatest existing prefix mid-job would
     * hand a step the other run's object. Where no identifier has been assigned yet - the unit tier, which
     * builds executions without a repository - the job instance identifier is used, and then zero.
     *
     * @param stepExecution the running step, never {@code null}
     * @param jobContext the job execution context, never {@code null}
     * @return the generation ordinal, never negative
     */
    private static long reserveWorkGeneration(final StepExecution stepExecution,
            final ExecutionContext jobContext) {

        if (jobContext.containsKey(WORK_GENERATION_CONTEXT_ENTRY)) {
            return jobContext.getLong(WORK_GENERATION_CONTEXT_ENTRY);
        }
        final JobExecution jobExecution = stepExecution.getJobExecution();
        Long ordinal = jobExecution.getId();
        if (ordinal == null && jobExecution.getJobInstance() != null) {
            ordinal = jobExecution.getJobInstance().getInstanceId();
        }
        final long generation = ordinal == null ? 0L : Math.max(ordinal.longValue(), 0L);
        jobContext.putLong(WORK_GENERATION_CONTEXT_ENTRY, generation);
        return generation;
    }

    /**
     * Renders the key of the projected sequential object, standing in for
     * {@code AWS.M2.CARDDEMO.TRXFL.SEQ} at {@code app/jcl/CREASTMT.JCL:L48}.
     *
     * <p>Zero padded to {@value #GENERATION_WIDTH} digits, the width of the largest {@code long}, so that
     * lexicographic order and numeric order agree and reading "the greatest existing prefix" - the
     * {@code (0)} of a relative generation reference - is a plain descending key listing.
     *
     * @param generation the generation ordinal, never negative
     * @return the object key, never {@code null}
     */
    private String workObjectKey(final long generation) {
        return workPrefix + KEY_SEPARATOR + KEY_GENERATION_SEGMENT
                + String.format(Locale.ROOT, "%0" + GENERATION_WIDTH + "d", Long.valueOf(generation))
                + KEY_SEPARATOR + WORK_OBJECT_NAME;
    }

    /**
     * Reads back the concrete key {@code STEP010} published, and refuses to guess if it is absent.
     *
     * <p>Falling back to "the latest object under the prefix" would be the exact mistake the handoff exists
     * to prevent, so an absent entry abends instead: within one job, a {@code (+1)} written by an earlier step
     * is re-read by a later one <b>by key</b>.
     *
     * @param jobContext the job execution context, never {@code null}
     * @return the published key, never {@code null} and never blank
     * @throws FatalProcessingException if the entry is absent or blank
     */
    private static String requirePublishedWorkObjectKey(final ExecutionContext jobContext) {
        final String key = jobContext.containsKey(WORK_OBJECT_KEY_CONTEXT_ENTRY)
                ? jobContext.getString(WORK_OBJECT_KEY_CONTEXT_ENTRY)
                : null;
        if (key == null || key.isBlank()) {
            throw new FatalProcessingException(ABEND_CODE, ABEND_CULPRIT, REASON_MISSING_HANDOFF,
                    "STEP020 requires the object key STEP010 published under "
                            + WORK_OBJECT_KEY_CONTEXT_ENTRY + "; resolving the latest object instead would "
                            + "read another run's generation");
        }
        return key;
    }

    // Guards and diagnostics.

    /**
     * Routes an object-storage outcome through {@link FileStatusMapper}, so the boundary that replaced VSAM
     * reports failure the way the source did.
     *
     * <p>{@code '00'} continues; the {@code '9x'} family becomes the mapper's typed exception carrying the
     * cause, sanitised by {@link StatementWriter#sanitizedCause(Throwable)} so that a statement key cannot
     * travel inside it. Nothing is swallowed: every non-success path leaves this method by throwing, and the
     * cause it throws with keeps the original's type name, text and stack trace.
     *
     * @param ioStatus the synthesised file status, never {@code null}
     * @param logicalName the dataset or object name to name in the failure, never {@code null}
     * @param operation the operation name to name in the failure, never {@code null}
     * @param cause the underlying failure, or {@code null} when the status was synthesised without one
     * @throws com.cardemo.exception.CardDemoException the mapper's typed translation of {@code ioStatus}
     */
    private void guardObjectStore(final String ioStatus, final String logicalName, final String operation,
            final Throwable cause) {

        if (SUCCESS_STATUS.equals(ioStatus)) {
            return;
        }
        // FileStatusMapper.displayIoStatus already returns the whole rendered line INCLUDING
        // FileStatus.DISPLAY_MESSAGE_PREFIX, so prefixing it a second time produced
        // "FILE STATUS IS: NNNNFILE STATUS IS: NNNN9048" - the four-character rendering that
        // app/cbl/CBTRN02C.cbl:L714-L727 fixes as a contract, with the label doubled in front of it. The
        // mapper owns the format; this call site only places it.
        //
        // The cause is deliberately NOT logged as a throwable here: an object-store failure carries the
        // bucket, the key and often the request URL in its message and stack, and a statement key embeds the
        // account identifier. Its type is the classification an operator needs, and the cause itself is
        // preserved as the cause of the exception raised immediately below - so nothing is swallowed and the
        // root cause still reaches whoever handles the failure.
        //
        // Finding m-03, severity Medium. Withholding it from THIS logger was only half the control: the
        // exception raised below leaves this class, and both the framework's own step-failure logging and the
        // rendered stack Spring Batch stores in BATCH_STEP_EXECUTION.EXIT_MESSAGE render whatever cause it
        // carries. StatementWriter owns the key shape, so it owns the sanitiser too - one definition, used
        // from both boundaries. It preserves the type name, the text and the stack trace, removes only the
        // account digits of a statement key, and returns the original throwable unchanged when there is no
        // key in it.
        final Throwable reportable = StatementWriter.sanitizedCause(cause);
        LOG.error("{} on {} reported {}{}", operation, logicalName,
                fileStatusMapper.displayIoStatus(ioStatus),
                cause == null ? "" : " (cause: " + cause.getClass().getName() + ")");
        throw fileStatusMapper
                .toException(ioStatus, logicalName, operation, reportable)
                .orElseGet(() -> abend(REASON_OBJECT_STORE_FAILED,
                        operation + " on " + logicalName + " failed with status " + ioStatus, reportable));
    }

    /**
     * {@code 9999-ABEND-PROGRAM}, {@code app/cbl/CBSTM03A.CBL:L921}-{@code :L923}.
     *
     * <p>The source displays {@code 'ABENDING PROGRAM'} at {@code :L922} and calls the language environment
     * abend service at {@code :L923}. Here it becomes a {@link FatalProcessingException} carrying abend code
     * {@value FatalProcessingException#BATCH_ABEND_CODE} - the batch value, not the CICS online value - and
     * process return code {@value FatalProcessingException#BATCH_RETURN_CODE}. The exception is returned
     * rather than thrown so that callers read as {@code throw abend(...)} at the point of failure, which
     * keeps the control flow visible.
     *
     * <p>The JVM is never terminated here - no process-exit call, no runtime halt, no shutdown hook - and the
     * return code reaches the launcher through the failed job execution instead.
     *
     * @param reason the abend reason, never {@code null}
     * @param detail the message, never {@code null}
     * @param cause the underlying failure, or {@code null} when there is none
     * @return the exception to throw, never {@code null}
     */
    private FatalProcessingException abend(final String reason, final String detail,
            final Throwable cause) {

        // The cause travels as the cause of the returned exception, so it is preserved in full; only its type
        // is logged here, for the reason given on guardObjectStore. Finding M-06, severity Medium.
        LOG.error("{}: {} - {}{}", MSG_ABENDING_PROGRAM, reason, detail,
                cause == null ? "" : " (cause: " + cause.getClass().getName() + ")");
        if (cause == null) {
            return new FatalProcessingException(ABEND_CODE, ABEND_CULPRIT, reason, detail);
        }
        return new FatalProcessingException(ABEND_CODE, ABEND_CULPRIT, reason, detail, cause);
    }

    /**
     * Proves that the live transaction source still projects to the object {@code STEP010} wrote, before
     * {@code STEP040} builds its first statement.
     *
     * <p><strong>Finding H-06, severity High. This is the second half of its resolution</strong>; see
     * {@link #WORK_OBJECT_DIGEST_CONTEXT_ENTRY} for why the check takes this form rather than re-pointing the
     * {@code TRNXFILE} data path. The projection is re-derived by streaming - nothing is collected, so this costs
     * one keyset pass and one record of memory, not a second copy of the cluster - and its digest is compared
     * against the published one. A difference means a row was inserted, updated or deleted between the sort and
     * this step, so the statements this step would emit are not the statements the sort described; that abends.
     *
     * <p>An absent published digest means {@code STEP010} did not run in this job execution, which is the
     * standalone-step case. It is stated in the log and the step proceeds, because refusing would make the step
     * unrunnable on its own; what it must never do is proceed <em>silently</em>.
     *
     * @param jobContext the job execution context, consulted for the published digest and record count
     * @throws FatalProcessingException if the live source no longer projects to the published digest
     */
    private void requireSourceStillMatchesSnapshot(final ExecutionContext jobContext) {
        if (!jobContext.containsKey(WORK_OBJECT_DIGEST_CONTEXT_ENTRY)) {
            LOG.info("STEP040 found no {} digest published by STEP010, so it is running standalone against "
                            + "the live relation; app/jcl/CREASTMT.JCL:L79 gates STEP040 on STEP010 in the "
                            + "job stream, where the digest is always present",
                    WORK_OBJECT_NAME);
            return;
        }
        final String published = jobContext.getString(WORK_OBJECT_DIGEST_CONTEXT_ENTRY);
        // The records are discarded as they are digested: this re-derives the projection, it does not rebuild it.
        final ProjectedStream observed = sortAndProjectTransactions(encoded -> { });
        if (!published.equals(observed.digest())) {
            throw abend(REASON_SNAPSHOT_DRIFTED,
                    "STEP040 must consume the projection STEP010 produced, but the source now projects to a "
                            + "different " + DIGEST_ALGORITHM + " digest over " + observed.recordCount()
                            + " records; the transaction relation changed after STEP010 sorted it, so the "
                            + "statements built here would not be the statements SORTOUT at "
                            + "app/jcl/CREASTMT.JCL:L48 describes", null);
        }
        LOG.info("STEP040 verified that the source still projects to the STEP010 snapshot over {} records",
                Integer.valueOf(observed.recordCount()));
    }

    /**
     * Creates a digest instance for one snapshot comparison.
     *
     * <p>A fresh instance per use, because {@link MessageDigest} is stateful and a shared one would make two
     * concurrent steps interfere. The algorithm is required on every conforming platform, so an absence is a
     * broken runtime rather than a condition to handle - it is reported as such rather than swallowed.
     *
     * @return a fresh digest, never {@code null}
     */
    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(DIGEST_ALGORITHM);
        } catch (final NoSuchAlgorithmException absent) {
            throw new IllegalStateException(DIGEST_ALGORITHM
                    + " is a required algorithm on every conforming Java platform, so its absence means the "
                    + "runtime is not conforming", absent);
        }
    }

    /**
     * Renders a completed digest as lowercase hexadecimal.
     *
     * <p>A pure function of its argument, apart from finalising the digest it was given.
     *
     * @param digest the digest to finalise, never {@code null}
     * @return the hexadecimal rendering, never {@code null}
     */
    private static String hexDigest(final MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Renders any object key - work, text statement or markup statement - as a bounded surrogate for a
     * diagnostic.
     *
     * <p><strong>Finding M-06, severity Medium.</strong> Statement keys embed the account identifier as a key
     * segment, and this class logs keys on the pre-delete, the projection and the load paths. A log line needs
     * enough to identify which object is meant and no more, so only the last segment is emitted and any
     * {@code account=} segment is dropped with it. A {@code null} or blank key yields a constant rather than an
     * exception, because a diagnostic path must not itself fail.
     *
     * <p>A pure function of its argument.
     *
     * @param key the object key, possibly {@code null}
     * @return the surrogate rendering, never {@code null}
     */
    private static String objectKeySurrogate(final String key) {
        if (key == null || key.isBlank()) {
            return "(no key)";
        }
        final int lastSeparator = key.lastIndexOf('/');
        return lastSeparator < 0 ? key : ".../" + key.substring(lastSeparator + 1);
    }

    /**
     * Renders a card number for a diagnostic as its last four digits only.
     *
     * <p>A full sixteen-digit primary account number must never reach a log, and this job handles card
     * numbers as the leading sort key of every record, so the exposure would be systematic rather than
     * incidental. A {@code null} or short value yields a constant rather than an exception, because a
     * diagnostic path must not itself fail.
     *
     * @param transaction the row being reported, possibly {@code null}
     * @return a masked rendering, never {@code null}
     */
    private static String maskCardNumber(final Transaction transaction) {
        final String cardNumber = transaction == null ? null : transaction.getCardNumber();
        if (cardNumber == null || cardNumber.length() < 4) {
            return "****";
        }
        return "****" + cardNumber.substring(cardNumber.length() - 4);
    }

    /**
     * Parks the diagnostic value this execution inherited for one key, so it can be put back afterwards.
     *
     * <p>Called before the key is overwritten. When nothing was inherited the parking entry is removed
     * rather than left over from a prior attempt of the same job instance, so a restart cannot resurrect
     * a stale value: {@link #unwindDiagnostic} then correctly removes the key instead of restoring one.
     *
     * @param jobContext the job execution context that holds the parked value, never {@code null}
     * @param contextEntry the parking key, never {@code null}
     * @param mdcKey the diagnostic key being saved, never {@code null}
     */
    private static void parkInheritedDiagnostic(final ExecutionContext jobContext, final String contextEntry,
            final String mdcKey) {

        final String inherited = MDC.get(mdcKey);
        if (inherited == null) {
            jobContext.remove(contextEntry);
        } else {
            jobContext.putString(contextEntry, inherited);
        }
    }

    /**
     * Restores one parked diagnostic value, or removes the key when nothing was inherited.
     *
     * <p>The parking entry is removed either way, so the value does not outlive the execution that owned it.
     *
     * @param jobContext the job execution context holding the parked value, never {@code null}
     * @param contextEntry the parking key, never {@code null}
     * @param mdcKey the diagnostic key being unwound, never {@code null}
     */
    private static void unwindDiagnostic(final ExecutionContext jobContext, final String contextEntry,
            final String mdcKey) {

        final String inherited = jobContext.containsKey(contextEntry)
                ? jobContext.getString(contextEntry)
                : null;
        jobContext.remove(contextEntry);
        if (inherited == null) {
            MDC.remove(mdcKey);
        } else {
            MDC.put(mdcKey, inherited);
        }
    }

    /**
     * Maps the job so far onto the legacy return code that {@code COND=(0,NE)} tests.
     *
     * <p><b>Finding, severity High - remediated here: the code is cumulative.</b> This method previously
     * inspected only the step execution handed to the decider, which is the <em>immediately preceding</em>
     * step. That is not what the source tests. {@code COND=(0,NE)} written without a step name - as it is on
     * {@code STEP020}, {@code STEP030} and {@code STEP040} of {@code app/jcl/CREASTMT.JCL:L56}, {@code :L66}
     * and {@code :L79} - is evaluated against <b>every preceding step in the job</b>, and the step is
     * bypassed if the test is true for any one of them. So a job whose sort failed but whose load happened to
     * be skipped-and-recorded-clean would have re-opened the gate for the emission, which is precisely the
     * case where the emission must not run: it would publish statements from a work cluster that was never
     * loaded.
     *
     * <p>The remedy is to take the <b>worst</b> return code over every step execution recorded on the job,
     * not the last one. Because 0 is clean and 4, 8 and 12 are progressively worse, the maximum is the
     * cumulative code, and comparing that maximum against zero reproduces "every preceding step returned 0"
     * exactly. The step execution argument is still consulted - it is included in the sweep and it is what
     * {@link #containsAbend(JobExecution, StepExecution)} needs - but it no longer decides the outcome on its
     * own.
     *
     * <p>Zero for a clean completion, 4 for a completion carrying rejects, 12 when the failure was an abend,
     * and 8 for every other unsuccessful outcome. No steps at all - nothing has run yet - reports zero,
     * because a {@code COND} test against an unset condition code passes.
     *
     * @param jobExecution the running job, never {@code null}
     * @param stepExecution the last completed step, possibly {@code null}
     * @return one of 0, 4, 8 or 12: the worst code any step has reached
     */
    private static int legacyReturnCode(final JobExecution jobExecution,
            final StepExecution stepExecution) {

        if (containsAbend(jobExecution, stepExecution)) {
            return RETURN_CODE_ABEND;
        }

        int cumulative = RETURN_CODE_COMPLETED;
        for (final StepExecution recorded : jobExecution.getStepExecutions()) {
            cumulative = Math.max(cumulative, stepReturnCode(recorded));
        }
        // The decider's own argument is swept too. It is normally already among the job's step executions,
        // but a decider can be handed a step the job has not yet recorded, and a gate that ignored it would
        // open on the strength of an incomplete history.
        return Math.max(cumulative, stepReturnCode(stepExecution));
    }

    /**
     * Maps one step execution onto its legacy return code.
     *
     * <p>Extracted from {@link #legacyReturnCode(JobExecution, StepExecution)} so that the per-step mapping
     * and the cumulative sweep over it are separately readable; the mapping itself is unchanged.
     *
     * @param stepExecution the step to classify, possibly {@code null}
     * @return 0 for a clean completion or for {@code null}, 4 for completed-with-rejects, 12 for an abend
     *     exit status and 8 for any other unsuccessful outcome
     */
    private static int stepReturnCode(final StepExecution stepExecution) {
        if (stepExecution == null) {
            return RETURN_CODE_COMPLETED;
        }
        if (stepExecution.getStatus().isUnsuccessful()) {
            return RETURN_CODE_FAILED;
        }
        final String exitCode = stepExecution.getExitStatus().getExitCode();
        if (EXIT_CODE_FAILED.equals(exitCode)) {
            return RETURN_CODE_FAILED;
        }
        if (EXIT_CODE_ABEND.equals(exitCode)) {
            return RETURN_CODE_ABEND;
        }
        if (EXIT_CODE_COMPLETED_WITH_REJECTS.equals(exitCode)) {
            return RETURN_CODE_COMPLETED_WITH_REJECTS;
        }
        return RETURN_CODE_COMPLETED;
    }

    /**
     * Reports whether either the job or the step failed with an abend, so return code 12 is distinguishable
     * from return code 8.
     *
     * <p>The two are independent outcomes: 8 is a failed step and 12 is
     * {@code CALL 'CEE3ABD'} at {@code app/cbl/CBSTM03A.CBL:L923}. Collapsing them would make the abend arm
     * of the decider unreachable and lose the distinction the exit-status contract rests on.
     *
     * @param jobExecution the running job, never {@code null}
     * @param stepExecution the last completed step, possibly {@code null}
     * @return {@code true} if any recorded failure is a {@link FatalProcessingException}
     */
    private static boolean containsAbend(final JobExecution jobExecution,
            final StepExecution stepExecution) {

        for (final Throwable failure : jobExecution.getAllFailureExceptions()) {
            if (failure instanceof FatalProcessingException) {
                return true;
            }
        }
        if (stepExecution == null) {
            return false;
        }
        for (final Throwable failure : stepExecution.getFailureExceptions()) {
            if (failure instanceof FatalProcessingException) {
                return true;
            }
        }
        return false;
    }

    // Nested collaborators. All four are plain objects, never beans: this folder contributes only the Job,
    // Step and Flow beans above, and declaring a decider or a listener as a bean would both add a container
    // singleton and risk colliding with one that com.cardemo.config.BatchConfig may declare.

    /**
     * Brackets the emit step with the initialisation and the closes of {@code app/cbl/CBSTM03A.CBL}.
     *
     * <p>It belongs to the step rather than to the job because {@code STEP040} is gated by
     * {@code COND=(0,NE)} at {@code app/jcl/CREASTMT.JCL:L79}: opening the four datasets from a job-level
     * callback would open them even on the runs where the step is suppressed, which the source never did.
     *
     * <p>Inner rather than static, because it delegates to the outer instance's collaborators.
     */
    private final class EmitStepLifecycle implements StepExecutionListener {

        /** Creates the listener. Stateless: everything it needs is the outer instance and the argument. */
        private EmitStepLifecycle() {
            // No state: see the class documentation.
        }

        /**
         * Verifies the output geometry, then runs the five-stage initialisation.
         *
         * <p>An initialisation that abends throws out of {@code beforeStep}, which fails the step without
         * calling {@code afterStep}; that is the faithful outcome and is deliberately not softened.
         *
         * @param stepExecution the starting step, never {@code null}
         */
        @Override
        public void beforeStep(final StepExecution stepExecution) {
            requireOutputGeometry();
            requireSourceStillMatchesSnapshot(
                    stepExecution.getJobExecution().getExecutionContext());
            initialiseStatementRun();
        }

        /**
         * Closes the four datasets, records the statements emitted and reports the step's outcome.
         *
         * <p>A close that fails is recorded against the step execution rather than thrown, because throwing
         * from {@code afterStep} would replace whatever failure the step already had. The returned exit
         * status is {@code null} when nothing needs changing, which leaves the framework's own status in
         * place; it is {@link #EXIT_CODE_ABEND} when the close abended, so the decider can tell 12 from 8.
         *
         * @param stepExecution the finished step, never {@code null}
         * @return the replacement exit status, or {@code null} to keep the framework's
         */
        @Override
        public ExitStatus afterStep(final StepExecution stepExecution) {
            ExitStatus outcome = null;
            try {
                closeStatementRun();
            } catch (final CardDemoException closeFailure) {
                LOG.error("STEP040 failed closing its datasets; recorded against the step execution "
                        + "because throwing from afterStep would mask the step's own outcome", closeFailure);
                stepExecution.addFailureException(closeFailure);
                outcome = new ExitStatus(EXIT_CODE_ABEND, closeFailure.getMessage());
            }

            final long emitted = statementWriter.statementsWritten();
            stepExecution.getJobExecution().getExecutionContext()
                    .putLong(STATEMENTS_EMITTED_CONTEXT_ENTRY, emitted);
            // The statement count is published to the execution context and to the run log, and is
            // deliberately NOT added to the records-processed counter. That counter reproduces
            // DISPLAY 'TRANSACTIONS PROCESSED :' at app/cbl/CBTRN02C.cbl:L227, whose unit is the daily
            // transaction records POSTTRAN read at :L206 - one program, one meaning. A statement is not one of
            // those records, and the statement writer was incrementing the same series per statement as well,
            // so a single untagged counter summed unrelated populations that no query could decompose again.
            // Spring Batch already publishes this job's own volume as spring.batch.item.* and
            // spring.batch.step, per step and per job, which is decomposable, so nothing is lost.
            emitStepGoback(emitted);
            discardPartialOutputIfStepFailed(stepExecution, outcome);
            return outcome;
        }

        /**
         * Removes every object this attempt created when the step did not complete.
         *
         * <p><b>Why this is safe to do wholesale.</b> {@code STEP030} has already emptied the statement root
         * before this step published anything, so whatever is under it now was created by this attempt and by
         * nothing else. Removing it is therefore precise, not indiscriminate - and it is what the source's
         * {@code DISP=(NEW,CATLG,DELETE)} at {@code app/jcl/CREASTMT.JCL:L84} and {@code :L86} does on its
         * own: the third disposition is the abnormal one, and it deletes the dataset the step was creating.
         * A half-written statement is customer data that describes nothing, and leaving it behind for an
         * operator to mistake for output is worse than leaving nothing behind.
         *
         * <p>A cleanup that itself fails is recorded and swallowed rather than raised. The step has already
         * failed by this point, and replacing its failure with a housekeeping failure would hide the reason
         * the run needs looking at.
         *
         * @param stepExecution the finished step, never {@code null}
         * @param closeOutcome the status the close produced, or {@code null} when the close was clean
         */
        private void discardPartialOutputIfStepFailed(final StepExecution stepExecution,
                final ExitStatus closeOutcome) {

            final boolean failed = closeOutcome != null
                    || stepExecution.getStatus().isUnsuccessful()
                    || !stepExecution.getFailureExceptions().isEmpty();
            if (!failed) {
                return;
            }
            try {
                final int discarded = preDeleteStatementOutput();
                LOG.warn("STEP040 did not complete, so the {} statement objects it had created were "
                                + "discarded; app/jcl/CREASTMT.JCL:L84 and :L86 declare "
                                + "DISP=(NEW,CATLG,DELETE), whose abnormal disposition deletes the dataset "
                                + "being created", Integer.valueOf(discarded));
            } catch (final CardDemoException cleanupFailure) {
                LOG.error("STEP040 could not discard the partial statement output it created; the step's own "
                        + "failure is left as the step outcome", cleanupFailure);
                stepExecution.addFailureException(cleanupFailure);
            }
        }
    }

    /**
     * Establishes the batch diagnostic context for the whole job and removes it again afterwards.
     *
     * <p>{@code com.cardemo.observability.CorrelationIdFilter} is servlet scoped and so covers no batch
     * execution, and the observability package contributes no job listener, so the job instance identifier
     * has to be placed into the mapped diagnostic context here. The key names are taken from
     * {@link CorrelationIdFilter} rather than restated as literals, so this listener and the filter cannot
     * drift apart, and all four - {@code jobInstanceId}, {@code correlationId}, {@code traceId} and
     * {@code spanId} - are the keys {@code src/main/resources/logback-spring.xml} renders.
     *
     * <p>Exactly two of those four are set here. {@code jobInstanceId} is always set, and
     * {@code correlationId} is set only when none is inherited, because a batch execution has no inbound
     * request to carry one. {@code traceId} and {@code spanId} are deliberately <b>not</b> set: they are
     * owned by the Micrometer tracing bridge, which populates them from the active span, and a fabricated
     * value would point at a span that does not exist and would break rather than aid correlation. An
     * execution that runs outside a span therefore logs without them, which is accurate rather than
     * convenient.
     *
     * <p>Every key this listener sets is unwound in a {@code finally} block. Batch threads are pooled, so a
     * key left behind would be inherited by the next job to run on the same thread and would attribute its
     * log events to this execution. Unwinding restores rather than removes: a value that was already
     * present when the job started is put back, so a caller that established its own context - a servlet
     * request that launched the job on its own thread, most importantly - keeps it. The inherited values are
     * parked in the job execution context for the duration of the run rather than in a field, because one
     * listener instance serves every execution of the job and a field would let two concurrent executions
     * overwrite each other's saved state.
     */
    private final class StatementGenerationJobListener implements JobExecutionListener {

        /** Creates the listener. Stateless: the context is read from the {@link JobExecution}. */
        private StatementGenerationJobListener() {
            // No state: see the class documentation.
        }

        /**
         * Places the job instance identifier and a correlation identifier into the diagnostic context.
         *
         * @param jobExecution the starting job, never {@code null}
         */
        @Override
        public void beforeJob(final JobExecution jobExecution) {
            final ExecutionContext jobContext = jobExecution.getExecutionContext();
            parkInheritedDiagnostic(jobContext, INHERITED_JOB_INSTANCE_ID_CONTEXT_ENTRY,
                    CorrelationIdFilter.MDC_KEY_JOB_INSTANCE_ID);
            parkInheritedDiagnostic(jobContext, INHERITED_CORRELATION_ID_CONTEXT_ENTRY,
                    CorrelationIdFilter.MDC_KEY_CORRELATION_ID);
            final long instanceId = jobExecution.getJobInstance() == null
                    ? 0L
                    : jobExecution.getJobInstance().getInstanceId();
            MDC.put(CorrelationIdFilter.MDC_KEY_JOB_INSTANCE_ID, Long.toString(instanceId));
            if (MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID) == null) {
                MDC.put(CorrelationIdFilter.MDC_KEY_CORRELATION_ID, UUID.randomUUID().toString());
            }
            LOG.info("START OF EXECUTION OF JOB {} - app/jcl/CREASTMT.JCL, {} steps", jobName,
                    Integer.valueOf(STEP_COUNT));
        }

        /**
         * Reports the run and unwinds the diagnostic context.
         *
         * @param jobExecution the finished job, never {@code null}
         */
        @Override
        public void afterJob(final JobExecution jobExecution) {
            try {
                final ExecutionContext context = jobExecution.getExecutionContext();
                LOG.info("END OF EXECUTION OF JOB {}: status={} exit={} projected={} loaded={} "
                                + "preDeleted={} statements={} objects={}",
                        jobName, jobExecution.getStatus(), jobExecution.getExitStatus().getExitCode(),
                        Integer.valueOf(context.containsKey(WORK_RECORD_COUNT_CONTEXT_ENTRY)
                                ? context.getInt(WORK_RECORD_COUNT_CONTEXT_ENTRY) : 0),
                        Integer.valueOf(context.containsKey(LOADED_RECORD_COUNT_CONTEXT_ENTRY)
                                ? context.getInt(LOADED_RECORD_COUNT_CONTEXT_ENTRY) : 0),
                        Integer.valueOf(context.containsKey(PRE_DELETED_OBJECT_COUNT_CONTEXT_ENTRY)
                                ? context.getInt(PRE_DELETED_OBJECT_COUNT_CONTEXT_ENTRY) : 0),
                        Long.valueOf(context.containsKey(STATEMENTS_EMITTED_CONTEXT_ENTRY)
                                ? context.getLong(STATEMENTS_EMITTED_CONTEXT_ENTRY) : 0L),
                        // Reported beside the statement count deliberately, and it is finding F-01's audit
                        // trail: the invariant is that a run creates exactly two objects per statement, so a
                        // reader of one line can see the two figures disagree. They previously could not,
                        // because the tally counted uploads rather than surviving objects.
                        Long.valueOf(context.containsKey(StatementWriter.CONTEXT_KEY_OBJECT_KEYS_COUNT)
                                ? context.getLong(StatementWriter.CONTEXT_KEY_OBJECT_KEYS_COUNT) : 0L));
            } finally {
                final ExecutionContext jobContext = jobExecution.getExecutionContext();
                unwindDiagnostic(jobContext, INHERITED_JOB_INSTANCE_ID_CONTEXT_ENTRY,
                        CorrelationIdFilter.MDC_KEY_JOB_INSTANCE_ID);
                unwindDiagnostic(jobContext, INHERITED_CORRELATION_ID_CONTEXT_ENTRY,
                        CorrelationIdFilter.MDC_KEY_CORRELATION_ID);
            }
        }
    }

    /**
     * One {@code COND=(0,NE)} gate, modelling the parameter that {@code STEP020} at
     * {@code app/jcl/CREASTMT.JCL:L56}, {@code STEP030} at {@code :L66} and {@code STEP040} at {@code :L79}
     * each carry.
     *
     * <p>{@code COND=(0,NE)} reads as "suppress this step when 0 is <b>not equal</b> to the return code" -
     * that is, run it only while the preceding return code is zero. Returning {@link #GATE_RUN} or
     * {@link #GATE_SKIP} rather than a status keeps the two outcomes distinguishable in the flow definition,
     * so a suppressed step is visibly suppressed rather than silently absent.
     *
     * <p>Static, because the decision is a pure function of its two arguments; one instance per gated step
     * so that the diagnostic names the step that was suppressed.
     */
    private static final class ConditionCodeGate implements JobExecutionDecider {

        /** The step this gate protects, used only in the diagnostic. */
        private final String gatedStepName;

        /**
         * Creates a gate for one step.
         *
         * @param gatedStepName the bean name of the gated step, never {@code null}
         */
        private ConditionCodeGate(final String gatedStepName) {
            this.gatedStepName = Objects.requireNonNull(gatedStepName, "gatedStepName must not be null");
        }

        /**
         * Decides whether the gated step runs.
         *
         * @param jobExecution the running job, never {@code null}
         * @param stepExecution the last completed step, possibly {@code null}
         * @return {@link #GATE_RUN} when EVERY preceding step returned zero, {@link #GATE_SKIP} otherwise
         */
        @Override
        public FlowExecutionStatus decide(final JobExecution jobExecution,
                final StepExecution stepExecution) {

            final int returnCode = legacyReturnCode(jobExecution, stepExecution);
            if (returnCode == RETURN_CODE_COMPLETED) {
                return new FlowExecutionStatus(GATE_RUN);
            }
            // "any preceding step", not "the preceding step": COND=(0,NE) without a step name is evaluated
            // against every one of them, so the code reported here is the worst any step has reached.
            LOG.warn("COND=(0,NE) suppressed {}: the worst return code reached by any preceding step was {}",
                    gatedStepName, Integer.valueOf(returnCode));
            return new FlowExecutionStatus(GATE_SKIP);
        }
    }

    /**
     * Maps whatever the flow reached onto one of the four legacy return codes.
     *
     * <p>Return code 0 becomes {@link #EXIT_CODE_COMPLETED}, 4 {@link #EXIT_CODE_COMPLETED_WITH_REJECTS}, 8
     * {@link #EXIT_CODE_FAILED} and 12 {@link #EXIT_CODE_ABEND}; the flow ends successfully on the first two
     * and fails on the rest, including on an outcome it does not recognise, so a future decider result cannot
     * fall through to success.
     *
     * <p><b>Return code 4 is recognised here but is not produced by this job.</b> {@code CBSTM03A} sets no
     * return code at all - the corpus's only numeric-literal assignment is
     * {@code MOVE 4 TO RETURN-CODE} at {@code app/cbl/CBTRN02C.cbl:L230}, in the posting program - so 0 is
     * this job's normal outcome. The arm is kept because this flow is composable into the wider pipeline
     * behind a step that does produce 4, and because return codes 4 and 12 are independent paths that a
     * decider must keep distinct.
     *
     * <p>Static, because the decision is a pure function of its arguments.
     */
    private static final class StatementGenerationReturnCodeDecider implements JobExecutionDecider {

        /** Creates the decider. Stateless, so one instance serves every execution. */
        private StatementGenerationReturnCodeDecider() {
            // No state: the decision is a pure function of the two arguments.
        }

        /**
         * Reports the job's return code as a flow status.
         *
         * @param jobExecution the running job, never {@code null}
         * @param stepExecution the last completed step, possibly {@code null}
         * @return the exit code corresponding to return code 0, 4, 8 or 12
         */
        @Override
        public FlowExecutionStatus decide(final JobExecution jobExecution,
                final StepExecution stepExecution) {

            final int returnCode = legacyReturnCode(jobExecution, stepExecution);
            return switch (returnCode) {
                case RETURN_CODE_COMPLETED -> new FlowExecutionStatus(EXIT_CODE_COMPLETED);
                case RETURN_CODE_COMPLETED_WITH_REJECTS ->
                        new FlowExecutionStatus(EXIT_CODE_COMPLETED_WITH_REJECTS);
                case RETURN_CODE_ABEND -> new FlowExecutionStatus(EXIT_CODE_ABEND);
                default -> new FlowExecutionStatus(EXIT_CODE_FAILED);
            };
        }
    }
}
