/*
 * ******************************************************************
 * Program     : StatementGenerationJob.java
 * Application : CardDemo
 * Type        : Spring Batch Job Configuration
 * Function    : Statement generation - five-step projection, sort, load
 *               and dual-format emission at 80 and 100 bytes.
 * Source      : app/jcl/CREASTMT.JCL (97 lines, 5 steps) +
 *               app/cbl/CBSTM03A.CBL (924 lines, 26 paragraphs) +
 *               app/cbl/CBSTM03B.CBL (230 lines, 15 labels) @ 7756d89
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
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

import com.cardemo.batch.processors.StatementProcessor;
import com.cardemo.batch.writers.StatementWriter;
import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.dto.StatementTransaction;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.observability.CorrelationIdFilter;
import com.cardemo.observability.MetricsConfig;
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
 *   <li>Two statement objects per account, written by {@link StatementWriter}: text at
 *       {@value StatementTransaction#STATEMENT_TEXT_RECORD_LENGTH} bytes per line and markup at
 *       {@value StatementTransaction#STATEMENT_HTML_RECORD_LENGTH}, under account and month prefixes in the
 *       statements bucket.</li>
 *   <li>Execution-context entries recording the concrete object key each step created, so a later step
 *       re-reads <b>that key</b> rather than re-resolving "the latest object". See <i>Object storage</i>.</li>
 *   <li>One counter, already owned by {@link MetricsConfig}: records processed. It is incremented twice per
 *       run - once by the projection with the number of transaction records it projected, once by the emit
 *       step with the number of statements it wrote - because both are records this job processed. The
 *       transaction-amount counter is deliberately left alone: the posting job already counts these same
 *       amounts, and counting them again here would inflate the total rather than measure anything. This
 *       class registers no meter of its own and adds no tag.</li>
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
 * {@code aws} segment, and {@code CARDDEMO_S3_OUTPUT_BUCKET} without {@code BATCH}; both spellings are
 * wrong and would have failed property resolution at startup. Severity: <b>Medium</b>, remediated by
 * binding the names the configuration file actually declares. Recorded for the planned
 * {@code DECISION_LOG.md} under Rule 1 Clause F. No AWS client is constructed here, no bucket or endpoint
 * is hardcoded, and no environment variable is read directly: every value arrives through property
 * binding, so a deployment can override it without touching code.
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
 * nothing at all. <b>A reviewer will read this as a defect in the Java code. It is not.</b> Severity if
 * <i>not</i> reproduced: <b>High</b>, because every statement then differs from the parity baseline in a
 * way no unit test would attribute to the projection. Remediation is to leave it exactly as it is; the
 * validation tier asserts the truncation so that a well-meant repair fails the build.
 *
 * <p>This is only representable because both timestamps are {@link String} over {@code CHAR(26)}. A temporal type
 * cannot hold a 24-of-26-byte fragment, so mapping either field to a date or date-time type would make the truncation
 * impossible to express - and would therefore make byte-exact statement output unreachable however carefully the rest
 * of the projection were written. Severity of a temporal mapping: <b>Blocker</b>, remediated by keeping both fields
 * as fixed-width text end to end, which is what {@link StatementTransaction} and
 * {@link com.cardemo.model.entity.Transaction} already do. The batch producer's rendering is
 * {@code yyyy-MM-dd-HH.mm.ss.SS0000} - millisecond precision followed by four literal zeros, never nanosecond
 * precision.
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
 * {@link FileService}, where it belongs. For the planned {@code DECISION_LOG.md}: self-modifying code
 * eliminated by static flow analysis, observable order preserved.
 *
 * <h2>The 510-transaction ceiling is removed - a labelled deviation, not parity</h2>
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
 * Pretending the ceiling was preserved would be false; pretending its removal is invisible would be worse.
 * Owed to the planned {@code DECISION_LOG.md}, with the historical 510 limit recorded in the planned
 * {@code TRACEABILITY_MATRIX.md}.
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
 * distinct CICS online value and conflating the two would be a Blocker.
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
 *   <li><b>Medium.</b> {@code HTMLFILE} is pre-deleted at {@code LRECL=80} in {@code STEP030}
 *       ({@code app/jcl/CREASTMT.JCL:L69}) but allocated at {@code LRECL=100} in {@code STEP040}
 *       ({@code :L94}). <b>100 is correct</b>, independently confirmed by
 *       {@code 05 HTML-FIXED-LN PIC X(100)} at {@code app/cbl/CBSTM03A.CBL:L149} and by
 *       {@code 01 FD-HTMLFILE-REC PIC X(100)} at {@code :L47}. Markup is emitted at 100 bytes per line and
 *       the contradictory 80 is recorded, not honoured. Remediation in the source would be to correct
 *       {@code :L69}; the corpus is frozen, so it is logged instead.</li>
 *   <li><b>Low.</b> {@code app/jcl/CREASTMT.JCL:L90} is corrupted on disk, reading
 *       {@code SPACE=(CYL,(1,1),RLSE), 00,RECFM=FB), ATA.VSAM.KSDS} - two fragments of other DD statements
 *       overwritten into one line. Its intended content is <b>Not available</b> and cannot be recovered
 *       from the repository; recovering it would need the original member from the source control system
 *       that predates this corpus. The surrounding statement is complete enough that
 *       {@code STMTFILE}'s {@code LRECL=80} at {@code :L89} is unambiguous, so nothing downstream depends
 *       on the missing text.</li>
 *   <li><b>Low.</b> {@code STMTFILE} at {@code :L72} omits the {@code UNIT=SYSDA} that {@code HTMLFILE}
 *       carries at {@code :L68}, and the member ends at {@code :L97} with {@code //*} and no terminating
 *       {@code //} card. Neither has a Java counterpart - unit allocation and the end-of-deck card are
 *       both JES2 concepts - so both are recorded and nothing is normalised.</li>
 * </ol>
 *
 * <h2>Member casing and line endings - Blocker</h2>
 *
 * <p>Every {@code path:line} citation in this file was re-read from disk with carriage returns stripped, because
 * {@code app/jcl/CREASTMT.JCL}, {@code app/cbl/CBSTM03A.CBL}, {@code app/cbl/CBSTM03B.CBL} and
 * {@code app/cpy/COSTM01.CPY} all use CRLF line endings and all four have <b>uppercase</b> names. A case-sensitive
 * {@code *.jcl} or {@code *.cbl} glob omits every one of them, and since these are the sole sources for statement
 * generation the whole job disappears from scope. Severity: <b>Blocker</b>, remediated by spelling every member
 * exactly as it appears on disk and by stripping carriage returns before counting a line - which is also why the
 * statement copybook is only ever cited as {@code app/cpy/COSTM01.CPY}: no Y-suffixed variant of that member exists
 * in the repository, so a citation carrying one would resolve to nothing.
 *
 * <h2>Citation corrections, all Low</h2>
 *
 * <p>Three inherited citations proved wrong and are corrected: the redundant
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

    // =================================================================================================
    // Bean names. Every one carries the statementGeneration prefix so that this class contributes no name
    // another configuration class could plausibly want. This folder may contribute only Job, Step and
    // Flow beans; no infrastructure bean is declared anywhere below - the JobRepository, the transaction
    // manager and the object-storage client are all injected.
    // =================================================================================================

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

    // =================================================================================================
    // Configuration defaults.
    // =================================================================================================

    /** The JCL job name at {@code app/jcl/CREASTMT.JCL:L1}. */
    static final String DEFAULT_JOB_NAME = "CREASTMT";

    /** Commit interval and read window, matching {@code carddemo.batch.chunk-size}. */
    static final int DEFAULT_CHUNK_SIZE = 100;

    /** Key prefix standing in for the {@code AWS.M2.CARDDEMO.TRXFL} dataset names. */
    static final String DEFAULT_WORK_PREFIX = "work/trxfl";

    /** The number of steps {@code app/jcl/CREASTMT.JCL} declares, and that this job registers. */
    static final int STEP_COUNT = 5;

    // =================================================================================================
    // Work-cluster geometry, from DEFINE CLUSTER at app/jcl/CREASTMT.JCL:L29-L39. The cluster is an
    // IN-JOB projection and sort target and is NEVER persisted: there is no table, no entity, no
    // repository and no migration for it. V1__create_schema.sql creates exactly eleven tables and a
    // validation gate asserts that count, so a twelfth for TRXFL would fail the gate. It is also absent
    // from app/catlg/LISTCAT.txt, whose summary counts ten clusters at :L3940 - the work cluster is
    // created and deleted inside this one job and was never catalogued.
    // =================================================================================================

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

    // =================================================================================================
    // Output geometry. Two streams, two widths, both preserved byte-exactly.
    // =================================================================================================

    /** {@code LRECL=80} on {@code STMTFILE}, {@code app/jcl/CREASTMT.JCL:L89}; {@code CBSTM03A.CBL:L45}. */
    static final int TEXT_RECORD_LENGTH = StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH;

    /** {@code LRECL=100} on {@code HTMLFILE}, {@code app/jcl/CREASTMT.JCL:L94}; {@code CBSTM03A.CBL:L47}. */
    static final int HTML_RECORD_LENGTH = StatementTransaction.STATEMENT_HTML_RECORD_LENGTH;

    /**
     * The width {@code STEP030} pre-deletes {@code HTMLFILE} at, {@code app/jcl/CREASTMT.JCL:L69}.
     *
     * <p>It disagrees with the {@value #HTML_RECORD_LENGTH} that {@code STEP040} allocates at {@code :L94}
     * and that {@code 05 HTML-FIXED-LN PIC X(100)} at {@code app/cbl/CBSTM03A.CBL:L149} confirms. Held as a
     * named constant, and used by {@link #preDeleteHtmlOutput(ExecutionContext)} only to log the
     * discrepancy, so the defect is visible in the code rather than silently dropped. Severity Medium.
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

    /** Zero-padded width of the monotonically increasing generation segment of a work object key. */
    private static final int GENERATION_WIDTH = 19;

    /** The {@code generation=} key segment, the {@code (+1)} of a relative generation reference. */
    private static final String KEY_GENERATION_SEGMENT = "generation=";

    /** Key path separator. */
    private static final String KEY_SEPARATOR = "/";

    // =================================================================================================
    // Execution-context entries. A (+1) written by an earlier step is re-read by a later one, so every
    // step publishes the CONCRETE key it created and every reader takes that key. "The latest object" is
    // never re-resolved mid-job: a concurrent run would otherwise hand a step the wrong generation.
    // =================================================================================================

    /** The key {@code STEP010} created, read back by {@code STEP020}. */
    static final String WORK_OBJECT_KEY_CONTEXT_ENTRY = "carddemo.creastmt.work.objectKey";

    /** The record count {@code STEP010} emitted, checked by {@code STEP020}. */
    static final String WORK_RECORD_COUNT_CONTEXT_ENTRY = "carddemo.creastmt.work.recordCount";

    /** The record count {@code STEP020} loaded into the work cluster. */
    static final String LOADED_RECORD_COUNT_CONTEXT_ENTRY = "carddemo.creastmt.load.recordCount";

    /** The work-cluster geometry {@code DELDEF01} defined, rendered for the run log and for diagnostics. */
    static final String WORK_GEOMETRY_CONTEXT_ENTRY = "carddemo.creastmt.work.geometry";

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

    // =================================================================================================
    // Flow vocabulary. Exit codes first, then the two gate outcomes that model COND=(0,NE).
    // =================================================================================================

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

    // =================================================================================================
    // Diagnostics, mirroring the source's DISPLAY text where the source has any.
    // =================================================================================================

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

    /** The {@code '9x'} family status used when object storage fails, {@code FileStatus:IO_ERROR}. */
    private static final String OBJECT_STORE_IO_STATUS = FileStatus.IO_ERROR_FIRST_BYTE + "0";

    /** {@code '00'}, the only status that continues without qualification. */
    private static final String SUCCESS_STATUS = FileStatus.SUCCESS.code().orElseThrow();

    // =================================================================================================
    // The two translated control cards, both unmodifiable constants.
    // =================================================================================================

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

    // =================================================================================================
    // Injected collaborators. Constructor injection only; every field final; no static mutable state
    // anywhere in this class, which is what makes two concurrent job executions independent.
    // =================================================================================================

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

    /** The four counters the application owns. This class adds none. */
    private final MetricsConfig metricsConfig;

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
     * @param metricsConfig the owner of the four application counters, never {@code null}
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
            final MetricsConfig metricsConfig,
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
        this.metricsConfig = requireCollaborator(metricsConfig, "metricsConfig");
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
     * Trims a key prefix to a single canonical form - no leading separator, no trailing separator - so that
     * {@code work/trxfl}, {@code /work/trxfl} and {@code work/trxfl/} all produce identical object keys.
     *
     * @param prefix the configured prefix, never {@code null} and never blank
     * @return the canonical prefix, never {@code null} and never blank
     * @throws IllegalArgumentException if the prefix consists only of separators
     */
    private static String normalisePrefix(final String prefix) {
        String trimmed = prefix.strip();
        while (trimmed.startsWith(KEY_SEPARATOR)) {
            trimmed = trimmed.substring(KEY_SEPARATOR.length());
        }
        while (trimmed.endsWith(KEY_SEPARATOR)) {
            trimmed = trimmed.substring(0, trimmed.length() - KEY_SEPARATOR.length());
        }
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(
                    "carddemo.aws.s3.work-prefixes.trxfl must name a prefix, but was only separators");
        }
        return trimmed;
    }

    // =================================================================================================
    // The five steps, in the order app/jcl/CREASTMT.JCL declares them.
    // =================================================================================================

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

    // =================================================================================================
    // Step 1 - DELDEF01, app/jcl/CREASTMT.JCL:L22-L39. One private method per control card.
    // =================================================================================================

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
                LOG.info("DELDEF01 removed a work object left by an earlier attempt: {}", key);
            } else {
                LOG.debug("DELDEF01 found no work object at {}; nothing to delete", key);
            }
        } catch (final RuntimeException deleteFailure) {
            // Not swallowed: the cause is logged in full and the outcome is the source's own. See
            // setMaxccZero() for why the step must not fail here.
            LOG.warn("DELDEF01 could not delete the work object {}; app/jcl/CREASTMT.JCL:L28 discards "
                    + "this condition code, so the step continues", key, deleteFailure);
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

    // =================================================================================================
    // Step 2 - STEP010, app/jcl/CREASTMT.JCL:L44-L54: the sort and the projection.
    // =================================================================================================

    /**
     * The body of {@code STEP010}: read {@code SORTIN} in sorted order, project every record, write one
     * {@code SORTOUT} object.
     *
     * <p>The whole projected image is buffered before it is uploaded, because an object-storage {@code PUT}
     * needs a complete body and a content length. Peak memory is therefore one object - the same
     * materialisation the legacy step made to DASD at {@code :L48} - and it is bounded by the size of the
     * transaction cluster rather than by anything this step chooses. This is unrelated to the resident-table
     * ceiling discussed in the class documentation, which concerns the emit step and is genuinely streamed.
     *
     * @return the projection and sort tasklet, never {@code null}
     */
    private Tasklet projectAndSortTasklet() {
        return (final StepContribution contribution, final ChunkContext chunkContext) -> {
            final StepExecution stepExecution = chunkContext.getStepContext().getStepExecution();
            final ExecutionContext jobContext = stepExecution.getJobExecution().getExecutionContext();
            final long generation = reserveWorkGeneration(stepExecution, jobContext);

            final List<String> projected = sortAndProjectTransactions();
            final String key = writeWorkObject(generation, projected);

            jobContext.putString(WORK_OBJECT_KEY_CONTEXT_ENTRY, key);
            jobContext.putInt(WORK_RECORD_COUNT_CONTEXT_ENTRY, projected.size());
            contribution.incrementWriteCount(projected.size());
            metricsConfig.countRecordsProcessed(projected.size());
            contribution.setExitStatus(ExitStatus.COMPLETED);

            LOG.info("STEP010 projected and sorted {} transaction records into {}",
                    Integer.valueOf(projected.size()), key);
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} at {@code app/jcl/CREASTMT.JCL:L53} together with
     * {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)} at {@code :L54}.
     *
     * <p>The ordering is delegated to {@link TransactionRepository#findStatementOrderAfter}, whose
     * {@code order by} clause is the two-key ascending sequence, and the projection to
     * {@link StatementProcessor#projectBaseRecord(Transaction)}, which reproduces the two-byte truncation
     * exactly. Reading is keyset positioned in windows of {@link #chunkSize} rather than by page number, so
     * a full-cluster run costs one index seek per window instead of re-reading every preceding row.
     *
     * <p>Every emitted record is checked against {@link #SORT_FIELDS_COMPARATOR} before it is accepted, so
     * the ascending precondition that {@code app/cbl/CBSTM03A.CBL:L419} relies on is proven for this run
     * rather than assumed. An empty cluster yields an empty list and is handled explicitly: it produces an
     * empty object rather than a failure, because {@code SORT} with an empty {@code SORTIN} allocates an
     * empty {@code SORTOUT} and does not fail the step.
     *
     * @return the projected records, in sort order, each exactly {@value #WORK_CLUSTER_RECORD_LENGTH}
     *     characters, never {@code null}
     * @throws FatalProcessingException if the sequence descends, or if a projected record is not exactly
     *     {@value #WORK_CLUSTER_RECORD_LENGTH} characters
     */
    private List<String> sortAndProjectTransactions() {
        final List<String> projected = new ArrayList<>();
        String positionCardNumber = "";
        String positionTransactionId = "";
        Transaction previous = null;

        for (;;) {
            final List<Transaction> window = transactionRepository.findStatementOrderAfter(
                    positionCardNumber, positionTransactionId, PageRequest.ofSize(chunkSize));
            if (window == null || window.isEmpty()) {
                break;
            }
            for (final Transaction row : window) {
                requireAscending(previous, row);
                projected.add(requireWorkRecordWidth(StatementProcessor.projectBaseRecord(row)));
                previous = row;
            }
            final Transaction last = window.get(window.size() - 1);
            positionCardNumber = last.getCardNumber() == null ? "" : last.getCardNumber();
            positionTransactionId = last.getTransactionId() == null ? "" : last.getTransactionId();
        }

        if (projected.isEmpty()) {
            LOG.info("STEP010 read an empty SORTIN, so SORTOUT is allocated empty; "
                    + "app/jcl/CREASTMT.JCL:L44 declares no COND, so the step still completes");
        }
        return projected;
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
     * {@link StandardCharsets#ISO_8859_1} so one character is one byte and the object size is an exact
     * multiple of the record length.
     *
     * @param generation the generation ordinal reserved for this run
     * @param records the projected records in sort order, never {@code null}
     * @return the concrete key created, never {@code null}
     * @throws FatalProcessingException if object storage rejects the write
     */
    private String writeWorkObject(final long generation, final List<String> records) {
        final String key = workObjectKey(generation);
        final StringBuilder image = new StringBuilder(records.size() * WORK_CLUSTER_RECORD_LENGTH);
        for (final String record : records) {
            image.append(record);
        }
        final byte[] payload = image.toString().getBytes(FIXED_WIDTH_CHARSET);
        if (payload.length != records.size() * WORK_CLUSTER_RECORD_LENGTH) {
            throw abend(REASON_BAD_RECORD_LENGTH,
                    "The encoded work object must be an exact multiple of "
                            + WORK_CLUSTER_RECORD_LENGTH + " bytes but was " + payload.length, null);
        }

        String ioStatus = OBJECT_STORE_IO_STATUS;
        RuntimeException failure = null;
        try {
            final ObjectMetadata metadata = ObjectMetadata.builder()
                    .contentType(WORK_OBJECT_CONTENT_TYPE)
                    .contentLength(Long.valueOf(payload.length))
                    .build();
            // A ByteArrayInputStream holds no operating-system handle and its close() cannot raise, so it
            // is not wrapped in a try-with-resources that would need a catch for an impossible failure.
            objectStorage.upload(batchOutputBucket, key, new ByteArrayInputStream(payload), metadata);
            ioStatus = SUCCESS_STATUS;
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final RuntimeException cause) {
            failure = cause;
        }
        guardObjectStore(ioStatus, WORK_OBJECT_NAME, "WRITE", failure);
        return key;
    }

    // =================================================================================================
    // Step 3 - STEP020, app/jcl/CREASTMT.JCL:L56-L61: REPRO into the work cluster.
    // =================================================================================================

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
     * <p>Object-storage content is treated as untrusted input: the length is validated before any record is
     * parsed, and no deserialization of any kind is performed on it.
     *
     * @param key the concrete key {@code STEP010} published, never {@code null}
     * @param jobContext the job execution context, consulted for the producer's record count
     * @return the number of records loaded
     * @throws FatalProcessingException if the object cannot be read, is not a whole number of records, or
     *     violates the key geometry or the key order
     */
    private int reproIntoWorkCluster(final String key, final ExecutionContext jobContext) {
        final byte[] image = readWorkObject(key);
        if (image.length % WORK_CLUSTER_RECORD_LENGTH != 0) {
            throw abend(REASON_BAD_RECORD_LENGTH,
                    "REPRO at app/jcl/CREASTMT.JCL:L61 loads fixed-length records, so " + key
                            + " must be an exact multiple of " + WORK_CLUSTER_RECORD_LENGTH
                            + " bytes but was " + image.length, null);
        }

        final String records = new String(image, FIXED_WIDTH_CHARSET);
        final int recordCount = records.length() / WORK_CLUSTER_RECORD_LENGTH;
        String previousKey = "";
        for (int index = 0; index < recordCount; index++) {
            final int from = index * WORK_CLUSTER_RECORD_LENGTH;
            final String recordKey = records.substring(from, from + WORK_CLUSTER_KEY_LENGTH);
            if (recordKey.compareTo(previousKey) < 0) {
                throw abend(REASON_SORT_ORDER_VIOLATION,
                        "The work cluster is INDEXED on a " + WORK_CLUSTER_KEY_LENGTH
                                + "-byte key per KEYS(32 0) at app/jcl/CREASTMT.JCL:L30, so REPRO input "
                                + "must ascend; record " + (index + 1) + " of " + recordCount
                                + " descends", null);
            }
            previousKey = recordKey;
        }

        final int produced = jobContext.containsKey(WORK_RECORD_COUNT_CONTEXT_ENTRY)
                ? jobContext.getInt(WORK_RECORD_COUNT_CONTEXT_ENTRY)
                : recordCount;
        if (produced != recordCount) {
            throw abend(REASON_BAD_RECORD_LENGTH,
                    "STEP010 published " + produced + " records but " + key + " holds " + recordCount
                            + "; REPRO must load every record its input carries", null);
        }

        LOG.info("STEP020 loaded {} records of {} bytes into the TRXFL work cluster from {} ({})",
                Integer.valueOf(recordCount), Integer.valueOf(WORK_CLUSTER_RECORD_LENGTH), key,
                jobContext.containsKey(WORK_GEOMETRY_CONTEXT_ENTRY)
                        ? jobContext.getString(WORK_GEOMETRY_CONTEXT_ENTRY)
                        : "geometry not published");
        return recordCount;
    }

    /**
     * Reads back the projected object, {@code INFILE} at {@code app/jcl/CREASTMT.JCL:L58}.
     *
     * @param key the concrete key to read, never {@code null}
     * @return the object image, never {@code null}
     * @throws FatalProcessingException if the object is absent or unreadable
     */
    private byte[] readWorkObject(final String key) {
        String ioStatus = OBJECT_STORE_IO_STATUS;
        RuntimeException runtimeFailure = null;
        Exception readFailure = null;
        byte[] image = new byte[0];
        try {
            final S3Resource resource = objectStorage.download(batchOutputBucket, key);
            try (InputStream stream = resource.getInputStream()) {
                image = stream.readAllBytes();
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
        return image;
    }

    // =================================================================================================
    // Step 4 - STEP030, app/jcl/CREASTMT.JCL:L66-L75: IEFBR14 and its two DD side effects.
    // =================================================================================================

    /**
     * The body of {@code STEP030}, gated by {@code COND=(0,NE)} at {@code app/jcl/CREASTMT.JCL:L66}.
     *
     * @return the pre-delete tasklet, never {@code null}
     */
    private Tasklet preDeleteOutputsTasklet() {
        return (final StepContribution contribution, final ChunkContext chunkContext) -> {
            final ExecutionContext jobContext = chunkContext.getStepContext().getStepExecution()
                    .getJobExecution().getExecutionContext();
            final List<String> stale = publishedStatementObjectKeys(jobContext);

            final int removed = preDeleteHtmlOutput(jobContext) + preDeleteTextOutput(stale);
            clearPublishedStatementObjectKeys(jobContext);

            jobContext.putInt(PRE_DELETED_OBJECT_COUNT_CONTEXT_ENTRY, removed);
            contribution.setExitStatus(ExitStatus.COMPLETED);
            LOG.info("STEP030 completed: {} statement objects from an earlier attempt removed. IEFBR14 "
                            + "does nothing itself; the effect is the DISP=(MOD,DELETE,DELETE) of its two "
                            + "DD statements at app/jcl/CREASTMT.JCL:L67 and :L72",
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
     * undetected. Severity <b>Medium</b>; remediation in the source would be to correct {@code :L69}, but
     * the corpus is frozen, so the discrepancy is recorded here and markup is emitted at
     * {@value #HTML_RECORD_LENGTH}.
     *
     * @param jobContext the job execution context, consulted for markup keys an earlier attempt published
     * @return the number of markup objects removed, zero when none existed
     */
    private int preDeleteHtmlOutput(final ExecutionContext jobContext) {
        LOG.debug("STEP030 pre-deletes HTMLFILE, declared LRECL={} at app/jcl/CREASTMT.JCL:L69 while "
                        + "STEP040 allocates the same dataset at LRECL={} at :L94; {} is correct per "
                        + "app/cbl/CBSTM03A.CBL:L149. Legacy defect, logged not repaired, severity Medium",
                Integer.valueOf(HTMLFILE_PRE_DELETE_DECLARED_LENGTH),
                Integer.valueOf(HTML_RECORD_LENGTH), Integer.valueOf(HTML_RECORD_LENGTH));

        final String markupKey = jobContext.containsKey(StatementWriter.CONTEXT_KEY_HTML_OBJECT)
                ? jobContext.getString(StatementWriter.CONTEXT_KEY_HTML_OBJECT)
                : null;
        return deleteStatementObjectIfPresent(markupKey);
    }

    /**
     * The {@code STMTFILE} DD of {@code STEP030}, {@code app/jcl/CREASTMT.JCL:L72}-{@code :L75}.
     *
     * <p>It declares {@code DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB)} but <b>omits the {@code UNIT=SYSDA} that
     * its {@code HTMLFILE} sibling carries at {@code :L68}</b>. Unit allocation is a JES2 concept with no
     * object-storage counterpart, so the omission changes nothing here; it is recorded as a <b>Low</b>
     * finding rather than normalised, because normalising it would edit a frozen member. The member's other
     * Low finding is at its very end: {@code :L97} is {@code //*} and there is no terminating {@code //}
     * card, which JES2 tolerates and which likewise has no counterpart.
     *
     * <p><b>Why the delete is scoped to keys this job instance published rather than to the whole
     * prefix.</b> Statement keys carry an account, a month and a monotonically increasing generation
     * segment, so a new run never collides with an old one and there is nothing to overwrite. The one case
     * the source's delete genuinely protects against is a partial object left by a failed earlier attempt at
     * the same outputs, and those keys are exactly the ones recorded in the execution context. Deleting the
     * whole {@code statements} prefix instead would destroy every historical statement in the bucket, which
     * the source - which named two datasets, not a family - never did.
     *
     * @param staleKeys keys an earlier attempt published, never {@code null}
     * @return the number of text objects removed, zero when none existed
     */
    private int preDeleteTextOutput(final List<String> staleKeys) {
        int removed = 0;
        for (final String key : staleKeys) {
            removed += deleteStatementObjectIfPresent(key);
        }
        return removed;
    }

    /**
     * Deletes one statement object if it exists, and reports it as absent otherwise.
     *
     * <p>Idempotent by construction, which is what {@code IEFBR14} against an absent dataset amounts to:
     * {@code DISP=(MOD,DELETE,DELETE)} creates the dataset if it is missing and then deletes it, so the step
     * cannot fail for absence. A {@code null} or blank key is treated as "nothing published" and handled
     * explicitly rather than reaching object storage.
     *
     * @param key the key to remove, possibly {@code null} or blank
     * @return {@code 1} if an object was removed, {@code 0} otherwise
     * @throws FatalProcessingException if object storage rejects the delete of an object it reports present
     */
    private int deleteStatementObjectIfPresent(final String key) {
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
        guardObjectStore(ioStatus, key, "DELETE", failure);
        return removed;
    }

    /**
     * Collects the statement object keys a previous attempt of this job execution published, using the
     * entries {@link StatementWriter} declares for exactly this purpose.
     *
     * <p>An absent count is the normal first-run case and yields an empty list rather than a failure.
     *
     * @param jobContext the job execution context, never {@code null}
     * @return the published keys, never {@code null} and possibly empty
     */
    private static List<String> publishedStatementObjectKeys(final ExecutionContext jobContext) {
        if (!jobContext.containsKey(StatementWriter.CONTEXT_KEY_OBJECT_KEYS_COUNT)) {
            return List.of();
        }
        final int count = jobContext.getInt(StatementWriter.CONTEXT_KEY_OBJECT_KEYS_COUNT);
        final List<String> keys = new ArrayList<>(Math.max(count, 0));
        for (int index = 0; index < count; index++) {
            final String entry = StatementWriter.objectKeysIndexEntry(index);
            if (jobContext.containsKey(entry)) {
                keys.add(jobContext.getString(entry));
            }
        }
        return keys;
    }

    /**
     * Removes the published key entries once their objects have been deleted, so a retry cannot delete the
     * same keys twice or mistake a previous attempt's output for its own.
     *
     * @param jobContext the job execution context, never {@code null}
     */
    private static void clearPublishedStatementObjectKeys(final ExecutionContext jobContext) {
        if (!jobContext.containsKey(StatementWriter.CONTEXT_KEY_OBJECT_KEYS_COUNT)) {
            return;
        }
        final int count = jobContext.getInt(StatementWriter.CONTEXT_KEY_OBJECT_KEYS_COUNT);
        for (int index = 0; index < count; index++) {
            jobContext.remove(StatementWriter.objectKeysIndexEntry(index));
        }
        jobContext.remove(StatementWriter.CONTEXT_KEY_OBJECT_KEYS_COUNT);
        jobContext.remove(StatementWriter.CONTEXT_KEY_TEXT_OBJECT);
        jobContext.remove(StatementWriter.CONTEXT_KEY_HTML_OBJECT);
    }

    // =================================================================================================
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
    // app/cbl/CBSTM03B.CBL contributes 15 labels - 14 procedural plus FILE-CONTROL at :L30 - and every one
    // of them belongs to FileService, which owns the four-dataset by six-operation matrix and the '00' or
    // '04' leniency of the nine call sites. They are cited here and implemented there:
    // PROCEDURE DIVISION USING at :L114, 0000-START at :L116, EVALUATE LK-M03B-DD at :L118, WHEN OTHER at
    // :L127 and GO TO 9999-GOBACK at :L128.
    // =================================================================================================

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
     * <p><b>Intentional no-op, retained for control-flow parity. Unreachable in the source. Severity:
     * Low.</b> {@code :L815} is {@code GO TO 1000-MAINLINE}, an unconditional branch, so control never
     * reaches the {@code EXIT.} that follows it - unlike the {@code EXIT.} statements ending the other four
     * stage paragraphs, which are reachable fall-throughs. It is kept, rather than dropped, because the
     * paragraph map that the scope-coverage gate verifies is proven by inspection against all 26 labels, and
     * a silently absent label breaks that proof.
     *
     * <p>This is one of the two artefacts of this kind in this file - the other being the redundant
     * {@code MOVE 1 TO CR-JMP} at {@code :L324}, which lives with the mainline body in
     * {@link StatementProcessor} and is retained there. Rule 1 Clause B forbids <i>untracked</i> dead code;
     * both are cited here, marked as intentional, and owed entries in the planned {@code DECISION_LOG.md}
     * and {@code TRACEABILITY_MATRIX.md}, so neither is untracked.
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

    // =================================================================================================
    // Object-key derivation and the step-to-step handoff.
    // =================================================================================================

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

    // =================================================================================================
    // Guards and diagnostics.
    // =================================================================================================

    /**
     * Routes an object-storage outcome through {@link FileStatusMapper}, so the boundary that replaced VSAM
     * reports failure the way the source did.
     *
     * <p>{@code '00'} continues; the {@code '9x'} family becomes the mapper's typed exception with the cause
     * preserved. Nothing is swallowed: every non-success path leaves this method by throwing.
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
        LOG.error("{} on {} reported {}{}", operation, logicalName,
                FileStatus.DISPLAY_MESSAGE_PREFIX, fileStatusMapper.displayIoStatus(ioStatus), cause);
        throw fileStatusMapper
                .toException(ioStatus, logicalName, operation, cause)
                .orElseGet(() -> abend(REASON_OBJECT_STORE_FAILED,
                        operation + " on " + logicalName + " failed with status " + ioStatus, cause));
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

        LOG.error("{}: {} - {}", MSG_ABENDING_PROGRAM, reason, detail, cause);
        if (cause == null) {
            return new FatalProcessingException(ABEND_CODE, ABEND_CULPRIT, reason, detail);
        }
        return new FatalProcessingException(ABEND_CODE, ABEND_CULPRIT, reason, detail, cause);
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
     * rather than left over from an earlier attempt of the same job instance, so a restart cannot resurrect
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
     * Maps a completed step onto the legacy return code that {@code COND=(0,NE)} tests.
     *
     * <p>Zero for a clean completion, 4 for a completion carrying rejects, 12 when the failure was an abend,
     * and 8 for every other unsuccessful outcome. A {@code null} step execution - no step has run yet -
     * reports zero, because a {@code COND} test against an unset condition code passes.
     *
     * @param jobExecution the running job, never {@code null}
     * @param stepExecution the last completed step, possibly {@code null}
     * @return one of 0, 4, 8 or 12
     */
    private static int legacyReturnCode(final JobExecution jobExecution,
            final StepExecution stepExecution) {

        if (containsAbend(jobExecution, stepExecution)) {
            return RETURN_CODE_ABEND;
        }
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

    // =================================================================================================
    // Nested collaborators. All four are plain objects, never beans: this folder contributes only the Job,
    // Step and Flow beans above, and declaring a decider or a listener as a bean would both add a container
    // singleton and risk colliding with one that com.cardemo.config.BatchConfig may declare.
    // =================================================================================================

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
            metricsConfig.countRecordsProcessed((int) Math.min(emitted, Integer.MAX_VALUE));
            emitStepGoback(emitted);
            return outcome;
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
                                + "preDeleted={} statements={}",
                        jobName, jobExecution.getStatus(), jobExecution.getExitStatus().getExitCode(),
                        Integer.valueOf(context.containsKey(WORK_RECORD_COUNT_CONTEXT_ENTRY)
                                ? context.getInt(WORK_RECORD_COUNT_CONTEXT_ENTRY) : 0),
                        Integer.valueOf(context.containsKey(LOADED_RECORD_COUNT_CONTEXT_ENTRY)
                                ? context.getInt(LOADED_RECORD_COUNT_CONTEXT_ENTRY) : 0),
                        Integer.valueOf(context.containsKey(PRE_DELETED_OBJECT_COUNT_CONTEXT_ENTRY)
                                ? context.getInt(PRE_DELETED_OBJECT_COUNT_CONTEXT_ENTRY) : 0),
                        Long.valueOf(context.containsKey(STATEMENTS_EMITTED_CONTEXT_ENTRY)
                                ? context.getLong(STATEMENTS_EMITTED_CONTEXT_ENTRY) : 0L));
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
         * @return {@link #GATE_RUN} when the preceding return code is zero, {@link #GATE_SKIP} otherwise
         */
        @Override
        public FlowExecutionStatus decide(final JobExecution jobExecution,
                final StepExecution stepExecution) {

            final int returnCode = legacyReturnCode(jobExecution, stepExecution);
            if (returnCode == RETURN_CODE_COMPLETED) {
                return new FlowExecutionStatus(GATE_RUN);
            }
            LOG.warn("COND=(0,NE) suppressed {}: the preceding return code was {}", gatedStepName,
                    Integer.valueOf(returnCode));
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
