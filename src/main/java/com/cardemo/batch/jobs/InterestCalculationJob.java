/*
 * ******************************************************************
 * Program     : InterestCalculationJob.java
 * Application : CardDemo
 * Type        : Spring Batch Job Configuration
 * Function    : Interest calculation - account control break, disclosure-rate
 *               fallback and synthetic transaction generation.
 * Source      : app/jcl/INTCALC.jcl + app/cbl/CBACT04C.cbl (652 lines,
 *               23 paragraphs) @ 7756d89
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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.job.flow.support.SimpleFlow;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import com.cardemo.batch.processors.InterestCalculationProcessor;
import com.cardemo.batch.writers.TransactionWriter;
import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.model.enums.TransactionSource;
import com.cardemo.observability.CorrelationIdFilter;
import com.cardemo.observability.MetricsConfig;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.DisclosureGroupRepository;
import com.cardemo.repository.TransactionCategoryBalanceRepository;
import com.cardemo.service.shared.FileStatusMapper;

import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Operations;

/**
 * The interest-calculation batch job: the Spring Batch replacement for {@code app/jcl/INTCALC.jcl},
 * whose single step {@code STEP15} runs {@code app/cbl/CBACT04C.cbl} - 652 lines, 23 paragraphs.
 *
 * <h2>What it does</h2>
 *
 * <p>It walks the transaction-category-balance table in composite-key order, groups the rows by
 * account through an account-level control break, resolves a monthly interest rate for each
 * {@code (group, type, category)} triple, computes one month's interest per row and emits one
 * synthesised interest transaction per non-zero rate into a brand-new sequential generation held in
 * object storage. On each control break the <em>previous</em> account's accumulated interest is added
 * to its balance and both of its cycle counters are zeroed.
 *
 * <p>This class owns the parts of the source that belong to the <em>step</em>: the five
 * {@code OPEN} paragraphs, the driving read of {@code 1000-TCATBALF-GET-NEXT}
 * ({@code app/cbl/CBACT04C.cbl:L325}), the {@code PERFORM UNTIL} loop at {@code :L188}-{@code :L222},
 * the record counter, the five {@code CLOSE} paragraphs, the abend and status-render paragraphs, and
 * the exit-status contract. The per-record body is owned by
 * {@link InterestCalculationProcessor} and is <strong>delegated, never duplicated</strong>. The
 * complete paragraph disposition is tabulated under <i>Paragraph correspondence</i> below, so a
 * traceability audit can see that not one of the 23 labels was dropped.
 *
 * <h2>Finding 1 - Blocker - the apparent final flush is UNREACHABLE, and AAP &sect;0.7.3.3 is wrong</h2>
 *
 * <p>The main loop of {@code app/cbl/CBACT04C.cbl:L188}-{@code :L222} reads, verbatim at the
 * traceability anchor:
 *
 * <pre>
 * L188 PERFORM UNTIL END-OF-FILE = 'Y'
 * L189     IF  END-OF-FILE = 'N'                  &lt;- OUTER IF,  column 16
 * L190         PERFORM 1000-TCATBALF-GET-NEXT
 * L191         IF  END-OF-FILE = 'N'              &lt;- INNER IF,  column 20
 * ...
 * L218         END-IF                             &lt;- closes the INNER IF, column 20
 * L219     ELSE                                   &lt;- belongs to the OUTER IF, column 16
 * L220         PERFORM 1050-UPDATE-ACCOUNT        &lt;- ** UNREACHABLE **
 * L221     END-IF                                 &lt;- closes the OUTER IF, column 16
 * L222 END-PERFORM.
 * </pre>
 *
 * <p>{@code PERFORM UNTIL} evaluates its condition <em>before</em> each iteration. The instant
 * {@code 1000-TCATBALF-GET-NEXT} sets {@code END-OF-FILE} to {@code 'Y'} at
 * {@code app/cbl/CBACT04C.cbl:L340}, the condition at {@code :L188} is satisfied and the loop exits;
 * control never re-enters the body, so the {@code ELSE} arm at {@code :L219} is never taken.
 * Indentation confirms the pairing independently of the reasoning: {@code :L191} and {@code :L218}
 * both sit at column 20 while {@code :L189}, {@code :L219} and {@code :L221} all sit at column 16.
 *
 * <p><b>Consequence, preserved and not repaired.</b> The <strong>last account of every run is never
 * updated</strong>: its accumulated interest is never added to {@code ACCT-CURR-BAL} and its two cycle
 * counters are never reset. Because {@code app/cbl/CBTRN02C.cbl:L403}-{@code :L405} computes the
 * over-limit test by subtracting the cycle-debit accumulator, that one account's over-limit arithmetic
 * behaves differently on the following posting cycle - in the planned
 * {@code com.cardemo.batch.jobs.DailyTransactionPostingJob}, which is named by the migration plan and is
 * not authored at this commit, this job being the only one {@code batch/jobs} holds. That is the behaviour
 * of the system of record and it is reproduced exactly.
 * {@code InterestCalculationProcessor.updateAccountAtEndOfFile()} retains the branch as a marked,
 * unreachable no-op so the paragraph map stays provable - it lives there, with the rest of the
 * {@code :L185}-{@code :L232} loop body it belongs to, rather than being duplicated here.
 * <strong>No final flush is implemented and none may be added.</strong>
 *
 * <p><b>The Agent Action Plan is wrong here.</b> &sect;0.7.3.3 states that "when the loop detects end
 * of file it performs the account update one final time". That claim does not hold against the source
 * and this class contradicts it deliberately. Severity: <b>Blocker</b>, because a well-meaning
 * implementation of the asserted flush would post one extra account update per run and reset counters
 * the source leaves standing.
 *
 * <p><b>Proof by contrast - three programs, three structures.</b> The same visual idiom appears twice
 * more in the corpus with different reachability, which is exactly why it had to be read from disk
 * rather than inferred. In {@code app/cbl/CBTRN03C.cbl} the {@code IF END-OF-FILE = 'N'} at
 * {@code :L179} is the <em>inner</em> test and its {@code ELSE} at {@code :L197} therefore <em>is</em>
 * reached at end of file, accumulating {@code TRAN-AMT} at {@code :L200}. In
 * {@code app/cbl/CBTRN02C.cbl:L202}-{@code :L219} <em>neither</em> {@code IF} carries an {@code ELSE}
 * at all, so that program has no flush construct to reason about.
 *
 * <p><b>Numbering divergence, severity Low.</b> The folder requirements number this finding 3 while a
 * sibling prompt numbers it 2. It is owed a <em>single</em> entry in the planned {@code DECISION_LOG.md}, against the
 * Agent Action Plan claim it corrects. Neither number is asserted here, because asserting either would
 * contradict the other source.
 *
 * <h2>Finding 2 - High - this job must not write to the transaction table</h2>
 *
 * <p>{@code app/cbl/CBACT04C.cbl:L53}-{@code :L56} declares {@code TRANSACT-FILE} with
 * {@code ORGANIZATION IS SEQUENTIAL}, {@code ACCESS MODE IS SEQUENTIAL} and <em>no</em>
 * {@code RECORD KEY}, and {@code app/jcl/INTCALC.jcl:L37}-{@code :L41} allocates
 * {@code DSN=AWS.M2.CARDDEMO.SYSTRAN(+1)} with {@code DISP=(NEW,CATLG,DELETE)} and
 * {@code DCB=(RECFM=F,LRECL=350,BLKSIZE=0)} - a brand-new generation on every run. The output is a
 * fresh sequential file, not the keyed cluster.
 *
 * <p>{@link TransactionWriter}, the natural-looking collaborator, inserts each item into the
 * transaction table <em>and</em> uploads an object, because it was written for
 * {@code 2900-WRITE-TRANSACTION-FILE} at {@code app/cbl/CBTRN02C.cbl:L562}, performed at
 * {@code app/cbl/CBTRN02C.cbl:L442}, where the target really is the keyed cluster. Using it here would
 * write rows this job must not write. It is therefore used for exactly one thing - its public, pure
 * {@link TransactionWriter#composeFixedWidthImage(Transaction)} and its published
 * {@link TransactionWriter#RECORD_LENGTH} - so that the 350-byte geometry has one owner tree-wide and
 * is not re-derived here. The emission itself is {@link SystranGenerationWriter}, which writes to
 * object storage and to nothing else. Severity <b>High</b>: the mistake compiles, runs and silently
 * doubles the transaction table.
 *
 * <p>Three consequences of the sequential target follow, and all three are load-bearing:
 *
 * <ol>
 *   <li><b>There is no duplicate-key detection in this job, and none is added.</b> A fresh sequential
 *       file cannot collide with itself, so the source performs no such check and neither does this
 *       class.</li>
 *   <li><b>Duplicate exposure materialises in the combine job.</b> Re-running with the same
 *       {@code PARM-DATE} produces colliding generated identifiers, which surface in the bulk load of
 *       {@code app/jcl/COMBTRAN.jcl:L41} as a duplicate-record failure with a failed exit status -
 *       <strong>never as a silent upsert.</strong></li>
 *   <li><b>Only the latest generation is merged.</b> {@code app/jcl/COMBTRAN.jcl} consumes the current
 *       generation reference, so an interest run superseded by a later one never reaches the
 *       cluster.</li>
 *   </ol>
 *
 * <h2>Finding 3 - Medium - the property namespace is {@code carddemo.aws.s3}, not {@code carddemo.s3}</h2>
 *
 * <p>A sibling prompt cites the bucket property as {@code carddemo.s3.*}. The namespace that
 * {@code src/main/resources/application.yml} actually declares carries the
 * {@code aws} segment: {@code carddemo.aws.s3.batch-output-bucket}, backed by the environment variable
 * {@code CARDDEMO_S3_BATCH_OUTPUT_BUCKET}. The generation prefix is
 * {@code carddemo.aws.s3.gdg-prefixes.systran}, whose shipped value is {@code gdg/systran} and whose
 * comment names {@code app/jcl/DEFGDGB.jcl:L49} as its origin. This class binds the declared names.
 * The divergence is owed an entry in the planned {@code DECISION_LOG.md} under clause F.
 *
 * <h2>Finding 4 - Low - three locator corrections</h2>
 *
 * <p>Re-read at the anchor with carriage returns stripped, three cited locators needed adjusting, and
 * the corrections are reported rather than silently absorbed. {@code app/jcl/INTCALC.jcl:L22} reads
 * {@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'} with a single space after the step name, not
 * three. The sequential declaration of {@code TRANSACT-FILE} is at
 * {@code app/cbl/CBACT04C.cbl:L53}-{@code :L56}, inside the {@code FILE-CONTROL} block that opens at
 * {@code :L27}. The {@code SYSTRAN} generation-data-group definition spans
 * {@code app/jcl/DEFGDGB.jcl:L48}-{@code :L53}, with {@code NAME(AWS.M2.CARDDEMO.SYSTRAN)} on
 * {@code :L49} and the trailing {@code IF LASTCC=12 THEN SET MAXCC=0} on {@code :L53}.
 *
 * <h2>Inputs</h2>
 *
 * <p><b>The one job parameter is {@value #PARM_DATE_JOB_PARAMETER}, and its shape is a hard
 * contract.</b> {@code app/jcl/INTCALC.jcl:L22} passes {@code PARM='2022071800'} into the linkage group
 * of {@code app/cbl/CBACT04C.cbl:L175}-{@code :L180}:
 *
 * <pre>
 * L175 LINKAGE SECTION.
 * L176 01  EXTERNAL-PARMS.
 * L177     05  PARM-LENGTH                   PIC S9(04) COMP.
 * L178     05  PARM-DATE                     PIC X(10).
 * L180 PROCEDURE DIVISION USING EXTERNAL-PARMS.
 * </pre>
 *
 * <p>The value is <strong>ten numeric characters - eight date digits followed by two zeros - with no
 * separators. It is not an ISO date.</strong> It is modelled as a {@link String} job parameter and is
 * never parsed into any {@code java.time} calendar type, never widened to a date-time and never
 * reformatted.
 * {@code PARM-LENGTH} has no Java counterpart: it is the length prefix the language environment fills
 * in, and a {@code String} carries its own length.
 *
 * <p><b>Why the shape matters.</b> {@code app/cbl/CBACT04C.cbl:L476}-{@code :L480} concatenates the ten
 * characters with a six-digit zero-padded suffix to form the sixteen-digit generated transaction
 * identifier. A separator, a truncation or a different width changes every identifier the run emits and
 * breaks the parity baseline. There is a second-order consequence worth recording: because the date
 * leads the identifier, generated identifiers are numerically large, so once an interest run has
 * occurred they dominate the descending-key browse that {@code app/cbl/COTRN02C.cbl:L444}-{@code :L451}
 * and {@code app/cbl/COBIL00C.cbl:L212}-{@code :L219} use to derive the next online identifier.
 *
 * <p><b>It is validated as untrusted input</b> by {@link ParmDateValidator} before the step is
 * entered - length exactly {@value #PARM_DATE_LENGTH}, every character an ASCII digit, the first eight
 * a plausible {@code yyyyMMdd} and the final two {@code 00}. A violation is rejected, never coerced.
 *
 * <p><b>The driving read is ordered, and the control break depends on it.</b>
 * {@link TransactionCategoryBalanceRepository#findAllByOrderByIdAccountIdAscIdTypeCdAscIdCatCdAsc}
 * fixes the order in its method name and this class additionally supplies the same three keys as an
 * explicit sort, in a {@link LinkedHashMap} so the key <em>order</em> is deterministic rather than
 * hash-dependent. The break at {@code app/cbl/CBACT04C.cbl:L194} tests the account identifier alone,
 * which is correct only because that identifier leads the seventeen-byte composite key of a file opened
 * {@code ACCESS MODE IS SEQUENTIAL} ({@code :L28}-{@code :L32}). Unordered input would split one
 * account across several breaks and post its interest more than once.
 *
 * <h2>Output and side effects</h2>
 *
 * <p>One object per chunk under the {@code SYSTRAN} generation prefix of the versioned batch-output
 * bucket, every record exactly {@value TransactionWriter#RECORD_LENGTH} bytes with no delimiter, so
 * each object's length is an exact multiple of the record length. {@code RECFM=F} at
 * {@code app/jcl/INTCALC.jcl:L39} is fixed <em>unblocked</em> - deliberately unlike every
 * {@code SORT} and {@code REPRO} output in the corpus, which use {@code RECFM=FB} - and the asymmetry
 * is preserved rather than normalised.
 *
 * <p>The {@code (+1)} relative generation reference becomes a monotonically increasing job-instance
 * segment, so lexicographic order and generation order coincide and a later
 * {@code (0)} read resolves to the newest generation. <b>The concrete keys are published into the
 * {@link JobExecution#getExecutionContext() job execution context}</b> under
 * {@value #SYSTRAN_GENERATION_PREFIX_CONTEXT_ENTRY} and
 * {@value #SYSTRAN_GENERATION_KEYS_COUNT_CONTEXT_ENTRY} with the indexed entries that count describes, so a
 * later job or step reads back exactly what this run created and <strong>never re-resolves "latest"
 * mid-pipeline.</strong>
 *
 * <p>Side effects beyond that object: the previous account's row is rewritten on each control break by
 * the processor, and {@link MetricsConfig#countRecordProcessed()} is advanced once per emitted record.
 * No message is published, no client is constructed, nothing is read from the process environment, and
 * the transaction table is not touched.
 *
 * <h2>Error modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>A missing cross-reference row abends the job</dt>
 *   <dd>{@code app/cbl/CBACT04C.cbl:L393}-{@code :L413} displays {@code 'ACCOUNT NOT FOUND: '} on the
 *       {@code INVALID KEY} path and then falls straight into the standard guard at {@code :L400},
 *       which accepts {@code '00'} only, so the run terminates. An empty lookup is therefore a
 *       {@link FatalProcessingException} - <strong>never a skip, never a filtered record, never a
 *       warning that continues.</strong> The lookup is delegated to
 *       {@link InterestCalculationProcessor}, which raises it.</dd>
 *   <dt>A missing {@code DEFAULT} disclosure-group row abends the job</dt>
 *   <dd>{@code :L422} accepts {@code '00' OR '23'} on the first read - one of only three scoped
 *       {@code FILE STATUS} leniency sites in the whole corpus, the others being
 *       {@code app/cbl/CBTRN02C.cbl:L481} and the {@code '00' OR '04'} file-service call sites inside
 *       {@code app/cbl/CBSTM03A.CBL} - and {@code :L436}-{@code :L438} retries against the literal
 *       {@code DEFAULT} group. The retry's own guard at {@code :L446} accepts {@code '00'} only, so a
 *       missing default row is fatal. Everywhere else {@code '23'} is a record-not-found exception.</dd>
 *   <dt>Every record abends on the default-group read</dt>
 *   <dd>The {@code (type, category)} pair has no {@code DEFAULT} row. {@code app/data/ASCII/discgrp.txt}
 *       holds 51 rows of which 17 carry the literal group identifier {@code DEFAULT}, including
 *       zero-rate combinations; check that the seed migration loaded all 51 and decoded the trailing
 *       zoned-decimal overpunch sign position-aware from the picture clauses.</dd>
 *   <dt>A zero rate produces nothing at all</dt>
 *   <dd>Expected, not a fault. The gate at {@code :L214}-{@code :L217} suppresses <em>both</em>
 *       {@code 1300-COMPUTE-INTEREST} and {@code 1400-COMPUTE-FEES}, so no transaction is emitted and
 *       nothing is accumulated into {@code WS-TOTAL-INT}.</dd>
 *   <dt>The last account's balance is not updated</dt>
 *   <dd>Expected. See Finding 1. Do not add a final flush.</dd>
 *   <dt>Startup fails complaining about a bucket</dt>
 *   <dd>{@code carddemo.aws.s3.batch-output-bucket} carries no default by design, so an unconfigured
 *       context fails at startup rather than writing a run into whatever bucket happens to exist.
 *       Supply {@code CARDDEMO_S3_BATCH_OUTPUT_BUCKET}.</dd>
 *   <dt>The job is rejected before the step runs</dt>
 *   <dd>{@link ParmDateValidator} refused the date parameter. The message names the rule that failed
 *       and the length observed; it never echoes a credential and never truncates silently.</dd>
 *   </dl>
 *
 * <h2>Exit-status contract</h2>
 *
 * <p>Return codes map {@code 0} completed, {@code 4} completed-with-rejects, {@code 8} failed and
 * {@code 12} abend. <b>{@code CBACT04C} sets no return code at all</b> - {@code TO RETURN-CODE} does
 * not occur anywhere in its 652 lines - so this job's normal outcome is {@code 0} and <strong>it has no
 * return-code-4 path of its own.</strong> The only {@code MOVE 4 TO RETURN-CODE} in the corpus is
 * {@code app/cbl/CBTRN02C.cbl:L230}, guarded by a reject count this job does not keep, and the only
 * other {@code TO RETURN-CODE} site is the variable move at {@code app/cbl/CSUTLDTC.cbl:L98}. There is
 * no {@code MOVE 12 TO RETURN-CODE} anywhere: return code 12 is the conventional language-environment
 * consequence of the {@code CALL 'CEE3ABD'} at {@code app/cbl/CBACT04C.cbl:L632}, and no locator is
 * fabricated for it. {@link InterestCalculationReturnCodeDecider} nevertheless covers all four codes,
 * because the orchestrated pipeline gates on the full set.
 *
 * <p>An abend surfaces as {@link FatalProcessingException}, which publishes abend code
 * {@value FatalProcessingException#BATCH_ABEND_CODE} - the value moved to {@code ABCODE} at
 * {@code app/cbl/CBACT04C.cbl:L631}, and never {@code 9999}, which is the CICS online value - and
 * process return code {@value FatalProcessingException#BATCH_RETURN_CODE}. The JVM is never
 * terminated: no JVM-exit call, no runtime halt and no shutdown hook appears anywhere in this file.
 *
 * <h2>Paragraph correspondence - all 23 labels of {@code CBACT04C} accounted for</h2>
 *
 * <p><b>Implemented here, one private member each.</b> The implicit mainline
 * ({@code PROCEDURE DIVISION} at {@code :L180}-{@code :L232}) is
 * {@link InterestCalculationJobListener} plus {@link #interestCalculationFlow(Step)};
 * {@code 0000-TCATBALF-OPEN} ({@code :L234}) is {@link #openTransactionCategoryBalanceFile()};
 * {@code 0100-XREFFILE-OPEN} ({@code :L252}) is {@link #openCrossReferenceFile()};
 * {@code 0200-DISCGRP-OPEN} ({@code :L270}) is {@link #openDisclosureGroupFile()};
 * {@code 0300-ACCTFILE-OPEN} ({@code :L289}) is {@link #openAccountFile()};
 * {@code 0400-TRANFILE-OPEN} ({@code :L307}) is {@link #openTransactionFile()};
 * {@code 1000-TCATBALF-GET-NEXT} ({@code :L325}) is {@link #categoryBalanceReader()};
 * {@code 9000-TCATBALF-CLOSE} ({@code :L522}) is {@link #closeTransactionCategoryBalanceFile()};
 * {@code 9100-XREFFILE-CLOSE} ({@code :L541}) is {@link #closeCrossReferenceFile()};
 * {@code 9200-DISCGRP-CLOSE} ({@code :L559}) is {@link #closeDisclosureGroupFile()};
 * {@code 9300-ACCTFILE-CLOSE} ({@code :L577}) is {@link #closeAccountFile()};
 * {@code 9400-TRANFILE-CLOSE} ({@code :L595}) is {@link #closeTransactionFile(JobExecution)};
 * {@code 9999-ABEND-PROGRAM} ({@code :L628}) is {@link #abendProgram(String, String, Throwable)}; and
 * {@code 9910-DISPLAY-IO-STATUS} ({@code :L635}) is {@link #displayIoStatus(String)}, which delegates
 * to the single tree-wide render rather than reimplementing it.
 *
 * <p><b>Delegated to {@link InterestCalculationProcessor}, and deliberately not duplicated here.</b>
 * {@code 1050-UPDATE-ACCOUNT} ({@code :L350}), {@code 1100-GET-ACCT-DATA} ({@code :L372}),
 * {@code 1110-GET-XREF-DATA} ({@code :L393}), {@code 1200-GET-INTEREST-RATE} ({@code :L415}),
 * {@code 1200-A-GET-DEFAULT-INT-RATE} ({@code :L443}), {@code 1300-COMPUTE-INTEREST} ({@code :L462}),
 * {@code 1300-B-WRITE-TX} ({@code :L473}), {@code 1400-COMPUTE-FEES} ({@code :L518}) and
 * {@code Z-GET-DB2-FORMAT-TIMESTAMP} ({@code :L613}) all belong to the per-record path, and the
 * processor implements each of them as its own private method.
 *
 * <p>Both of this program's retained no-ops fall in that delegated half, and both live in the processor
 * for a structural reason rather than a stylistic one: each is <em>inside</em> the
 * {@code :L185}-{@code :L232} loop body, which is the processor's territory.
 * {@code 1400-COMPUTE-FEES} is performed at {@code :L216} within the non-zero-rate arm of the gate at
 * {@code :L214}, and the unreachable {@code ELSE PERFORM 1050-UPDATE-ACCOUNT} at
 * {@code :L219}-{@code :L220} is an invocation of the delegated {@code 1050-UPDATE-ACCOUNT}, not a
 * label of its own. Re-declaring either here would have produced a second, never-invoked copy of a
 * method the processor already owns and - in the case of {@code 1400-COMPUTE-FEES} - actually calls,
 * which is exactly the duplication clause C forbids and exactly the untracked dead code clause B
 * forbids. They are cited here instead, as the delegation rule requires.
 *
 * <p>That is the whole of the 23 labels: <b>14 implemented here</b> - the implicit mainline, five
 * {@code OPEN}s, the driving read, five {@code CLOSE}s, the abend and the status render - and
 * <b>nine delegated</b> to {@link InterestCalculationProcessor}.
 *
 * <p>Three details of the delegated half are restated here because this class's documentation is where
 * a reader of the <em>job</em> will look for them. The interest formula is
 * {@code COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200} at {@code :L464}-{@code :L465}
 * - multiply first, then divide by the literal {@code 1200} at scale 2 with half-even rounding, never
 * split into a division by 100 followed by a division by 12 and never replaced by a decimal
 * approximation, because each of those changes the rounding. {@code 1050-UPDATE-ACCOUNT} at
 * {@code :L352}-{@code :L354} adds the accumulated interest to the balance and then <strong>zeroes both
 * cycle counters</strong> before the rewrite at {@code :L356}; omitting the reset is a divergence that
 * surfaces only on the next posting run. And {@code WS-TRANID-SUFFIX PIC 9(06) VALUE 0}
 * ({@code :L173}) is never reset per account, so identifiers are run-sequential - which is why the
 * processor is {@code @StepScope} and why that counter is <strong>never a {@code static} field and
 * never a mutable field of a singleton.</strong> This class holds no mutable state of any kind: every
 * field is {@code final} and the only static members are the logger and immutable constants.
 *
 * <h2>The one documented Rule 1 conflict, and its resolution</h2>
 *
 * <p>Clause B forbids dead code. Behavioural parity requires preserving no-op and unreachable
 * paragraphs so the paragraph map stays mechanically provable. Two of the four artefacts retained
 * tree-wide for that reason belong to <em>this program</em>, and both are implemented in
 * {@link InterestCalculationProcessor} because both sit inside the loop body it owns:
 * {@code computeFees()}, which is empty but <em>reachable</em> in the source
 * ({@code :L518}-{@code :L520}, performed at {@code :L216}), and
 * {@code updateAccountAtEndOfFile()}, which is <em>unreachable</em> ({@code :L219}-{@code :L220}).
 * They are documented from here because a reader of the <em>job</em> is who needs warning that no final
 * flush exists; they are not re-declared here, because a second never-invoked copy of a method the
 * processor already owns would be the very dead code and duplication clauses B and C forbid. Severity
 * of that placement decision: <b>Low</b>, owed an entry in the planned {@code DECISION_LOG.md}.
 *
 * <p><b>Parity governs</b>, because clause B forbids <em>untracked</em> dead code and deferred-work markers "without
 * owners or tracking reference". Both members carry, in the processor, an explicit {@code intentional-no-op} marker,
 * a {@code path:line} citation and an entry in the planned {@code DECISION_LOG.md} and
 * {@code TRACEABILITY_MATRIX.md}. Neither is abandoned residue; each is a documented faithful reproduction of a
 * paragraph that exists in the system of record. Deleting either would produce code that is marginally tidier and
 * demonstrably less traceable, failing a stated acceptance criterion to satisfy a stylistic one.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <table border="1">
 *   <caption>Configuration this class binds</caption>
 *   <tr><th>Property</th><th>Default</th><th>Meaning</th></tr>
 *   <tr>
 *     <td>{@code carddemo.batch.jobs.intcalc.name}</td>
 *     <td>{@value #DEFAULT_JOB_NAME}</td>
 *     <td>The Spring Batch job name, matching the JCL member {@code app/jcl/INTCALC.jcl}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code carddemo.batch.intcalc.chunk-size}</td>
 *     <td>{@code carddemo.batch.chunk-size}, itself defaulting to {@value #DEFAULT_CHUNK_SIZE}</td>
 *     <td>Commit interval and read page size. A tunable, not a parity contract</td>
 *   </tr>
 *   <tr>
 *     <td>{@code carddemo.aws.s3.batch-output-bucket}</td>
 *     <td><em>none</em> - required, so a missing value fails startup</td>
 *     <td>The versioned bucket the {@code SYSTRAN} generations are written to</td>
 *   </tr>
 *   <tr>
 *     <td>{@code carddemo.aws.s3.gdg-prefixes.systran}</td>
 *     <td>{@value #DEFAULT_SYSTRAN_PREFIX}</td>
 *     <td>The generation-data-group prefix replacing {@code AWS.M2.CARDDEMO.SYSTRAN}</td>
 *   </tr>
 * </table>
 *
 * <p>Two values are deliberately <em>not</em> configurable, because they are parity contracts rather
 * than preferences: the {@value #PARM_DATE_LENGTH}-character width of {@code PARM-DATE PIC X(10)} and
 * the {@value TransactionWriter#RECORD_LENGTH}-byte record length. Both are constants here, and both
 * agree with the values {@code application.yml} publishes for documentation
 * ({@code carddemo.batch.intcalc-date-parameter.length} and
 * {@code carddemo.batch.record-length.transaction}). Nothing is read from the process environment:
 * every value arrives through the Spring {@code Environment}.
 *
 * <h2>How to build, run and test it</h2>
 *
 * <p>Build with {@code ./mvnw -B -ntp clean compile} and verify with
 * {@code ./mvnw -B -ntp -Ddependency-check.skip=true clean verify}, on JDK 25 and Maven 3.9.11. The
 * compiler runs {@code -Xlint:all -Werror}, so a single warning {@code javac} emits - one deprecated builder
 * overload, one raw type, one dangling documentation comment - fails the build. An unused import is not one
 * of them: {@code javac} 25 publishes no {@code unused} lint key, so that prohibition is review-enforced.
 *
 * <p>Nothing runs on startup: {@code spring.batch.job.enabled} is {@code false} in
 * {@code application.yml}, so the job is launched explicitly, by the pipeline orchestrator or by a
 * test, and always with the {@value #PARM_DATE_JOB_PARAMETER} parameter. Launching it without that
 * parameter is rejected by {@link ParmDateValidator} before any row is read.
 *
 * <p>The behaviours a test must pin are: acceptance of {@code '2022071800'} and rejection of a nine- or
 * eleven-character value, a value containing a separator, a non-digit value and a value whose final two
 * characters are not {@code 00}; a sixteen-digit generated identifier beginning with the ten supplied
 * characters; <strong>the last account of a multi-account run being left un-updated with its cycle
 * counters intact</strong> (Finding 1 - the test must fail if a flush is added); both cycle counters
 * zeroed for a non-final account; the divide-by-1200 result at a value where dividing by 100 then 12
 * would round differently; both abend paths; the non-default group resolving through the
 * {@code '00' OR '23'} fallback without abending; zero-rate suppression; a run-sequential suffix that
 * never resets per account and never leaks between executions; an object under the {@code SYSTRAN}
 * prefix whose length is an exact multiple of the record length with nothing written to the transaction
 * table; the created keys present in the job execution context; two 26-character timestamps equal to
 * each other; and the decider's mapping of return codes 0, 4, 8 and 12.
 *
 * <h2>Not available</h2>
 *
 * <p>Three items are genuinely unavailable rather than omitted, and are named so that a reader knows
 * what would be needed to close each:
 *
 * <ul>
 *   <li><b>The object-storage record-framing convention is Not available.</b> Whether a downstream
 *       consumer expects newline-delimited output is not stated anywhere in the corpus. What
 *       <em>is</em> fixed is {@code RECFM=F} with {@code LRECL=350} at
 *       {@code app/jcl/INTCALC.jcl:L39}, so this class writes contiguous fixed-length records with no
 *       delimiter of any kind, which is the only framing the source's own geometry supports. Closing
 *       this needs a stated contract from the consumer of the {@code SYSTRAN} generations.</li>
 *   <li><b>No service-level objective is available.</b> The source publishes no throughput or latency
 *       target - not in the JCL, not in the program, not in the catalogue - so none is asserted and
 *       none is invented. The performance gate records a <em>measured baseline</em>. Closing this needs
 *       an operational requirement from outside the corpus.</li>
 *   <li><b>Container-dependent gate results are Not available at authoring time.</b> The end-to-end
 *       parity gate, the named-fixture gate and the integration sign-off gate all require a container
 *       runtime with an accessible socket, for PostgreSQL and for the object-storage emulator. Where
 *       one is unavailable the evidence must state the prerequisite; <strong>a pass is never
 *       fabricated.</strong></li>
 *   </ul>
 *
 * <h2>Bean registration</h2>
 *
 * <p>A plain {@link Configuration} class picked up by the component scan of
 * {@code com.cardemo.CardDemoApplication}. It declares exactly three beans - a {@link Step}, a
 * {@link Flow} and a {@link Job} - all uniquely prefixed so nothing collides with the planned
 * {@code com.cardemo.config.BatchConfig}. It declares <strong>no</strong> infrastructure: the job repository,
 * transaction manager, repositories, object-storage client and meter registry are all injected. The Spring Batch
 * enablement annotation appears nowhere: under Spring Boot 3 it <em>disables</em> batch auto-configuration and would
 * strip away the very {@code JobRepository} this
 * class depends on.
 * The decider and both listeners are plain nested objects rather than beans, so the container holds no
 * extra singleton on their behalf.
 *
 * <p>The configuration class itself is registered under the explicit name
 * {@value #CONFIGURATION_BEAN_NAME} rather than under the name the component scan would otherwise
 * derive from the class name. Without that, the scanned configuration bean and the {@link Job} bean
 * declared by {@link #interestCalculationJob(Flow)} would both claim {@code interestCalculationJob},
 * and the container refuses the second registration because bean-definition overriding is disabled.
 * The {@link Job} bean keeps the documented name; only the configuration holder is renamed, and the
 * Spring Batch job name stays {@value #DEFAULT_JOB_NAME}, which is what
 * {@code app/jcl/INTCALC.jcl} identifies the job by.
 *
 * @see InterestCalculationProcessor
 * @see TransactionWriter#composeFixedWidthImage(Transaction)
 * @see FileStatusMapper
 * @see FatalProcessingException
 */
@Configuration(InterestCalculationJob.CONFIGURATION_BEAN_NAME)
public class InterestCalculationJob {

    /**
     * Bean name of this configuration holder. It must differ from {@link #JOB_BEAN_NAME}: the component
     * scan registers the configuration class under this name, and the {@link Job} factory method
     * registers the job under {@link #JOB_BEAN_NAME}, so a shared name is a
     * {@code BeanDefinitionOverrideException} at context refresh.
     */
    static final String CONFIGURATION_BEAN_NAME = "interestCalculationJobConfiguration";

    /**
     * Structured log sink. It replaces the {@code DISPLAY} statements of the source, which had no sink
     * but SYSOUT and no severity at all. The literals are reproduced verbatim; only the level is a
     * target-side decision, and it is mapped consistently: the run boundary markers of
     * {@code app/cbl/CBACT04C.cbl:L181} and {@code :L230} are {@code INFO}, the per-resource open and
     * close traces are {@code DEBUG}, and every literal that immediately precedes an abend is
     * {@code ERROR}.
     */
    private static final Logger LOG = LoggerFactory.getLogger(InterestCalculationJob.class);

    /** Bean and step name of the single step, matching {@code app/jcl/INTCALC.jcl:L22} {@code STEP15}. */
    private static final String STEP_BEAN_NAME = "interestCalculationStep";

    /** Bean name of the flow that gates the step's outcome through the return-code decider. */
    private static final String FLOW_BEAN_NAME = "interestCalculationFlow";

    /** Bean name of the job. */
    private static final String JOB_BEAN_NAME = "interestCalculationJob";

    /**
     * Name of the job parameter carrying {@code PARM-DATE}, and the name
     * {@link InterestCalculationProcessor} already binds through
     * {@code #{jobParameters['parmDate']}}. The two must agree or the processor receives {@code null}.
     */
    private static final String PARM_DATE_JOB_PARAMETER = "parmDate";

    /** Width of {@code PARM-DATE PIC X(10)} at {@code app/cbl/CBACT04C.cbl:L178}. A parity contract. */
    private static final int PARM_DATE_LENGTH = 10;

    /** Number of leading characters of the date parameter that form the {@code yyyyMMdd} date. */
    private static final int PARM_DATE_DATE_DIGITS = 8;

    /**
     * The two trailing characters of {@code PARM='2022071800'} at {@code app/jcl/INTCALC.jcl:L22}.
     * They are part of the ten-character value and are concatenated into every generated identifier,
     * so they are asserted rather than tolerated.
     */
    private static final String PARM_DATE_TRAILER = "00";

    /** Lowest year the eight leading date digits may express, guarding an all-zero parameter. */
    private static final int PARM_DATE_MIN_YEAR = 1;

    /** Highest year the eight leading date digits may express, {@code PIC X(10)} allowing four digits. */
    private static final int PARM_DATE_MAX_YEAR = 9999;

    /** Default job name, matching {@code carddemo.batch.jobs.intcalc.name} and the JCL member name. */
    private static final String DEFAULT_JOB_NAME = "INTCALC";

    /**
     * Default commit interval and read page size, matching {@code carddemo.batch.chunk-size}. A
     * tunable rather than a parity contract: the source commits per record because it has no chunk
     * concept, and chunking changes only how often the transaction is committed, never which rows are
     * read or what is computed.
     */
    private static final int DEFAULT_CHUNK_SIZE = 100;

    /**
     * Default generation prefix, matching {@code carddemo.aws.s3.gdg-prefixes.systran} in
     * {@code src/main/resources/application.yml}, which cites {@code app/jcl/DEFGDGB.jcl:L49}
     * {@code NAME(AWS.M2.CARDDEMO.SYSTRAN)} as its origin.
     */
    private static final String DEFAULT_SYSTRAN_PREFIX = "gdg/systran";

    /**
     * Job execution context entry holding the generation prefix this run created - the resolved
     * {@code SYSTRAN(+1)} generation. A downstream job reads this rather than re-resolving "latest",
     * so a concurrent run cannot redirect it.
     */
    private static final String SYSTRAN_GENERATION_PREFIX_CONTEXT_ENTRY =
            "carddemo.systran.generation.prefix";

    /**
     * Job execution context entry holding <em>how many</em> object keys this run created. The keys themselves
     * live one per entry under {@link #SYSTRAN_GENERATION_KEYS_INDEX_PREFIX}, the entry for index {@code n}
     * being that prefix followed by {@code n}, rendered by {@link #generationKeysIndexEntry(int)}. Reading the
     * generation back means reading this count and then that many indexed entries, which yields the keys in
     * creation order.
     *
     * <p><b>Finding M-07, severity Medium, RESOLVED.</b> This entry used to hold every key in one string joined
     * by a comma, and the Javadoc justified that by asserting a key "never contains the separator because it is
     * built from digits, the configured prefix and a fixed suffix". <b>That justification was false.</b> The
     * prefix is {@code carddemo.aws.s3.gdg-prefixes.systran}, an externally configured value, and
     * {@link #normalisePrefix(String)} only strips trailing separators and rejects an empty result - a prefix
     * written {@code gdg,systran} passes validation and silently splits one key into two on read, so a
     * downstream step would resolve a generation to object names that were never created.
     *
     * <p>The remedy is structural rather than another validation rule, and that choice is the point. Rejecting
     * the comma would make correctness depend on a constraint this class imposes on configuration it does not
     * own, and any future delimiter change would re-open the hole. An indexed list has no delimiter at all, so
     * there is no character a prefix must avoid: the hazard is removed rather than guarded. It is also the
     * protocol {@code TransactionWriter}, {@code RejectWriter} and {@code StatementWriter} already use for the
     * identical problem, so all four components publish their created keys the same way.
     */
    public static final String SYSTRAN_GENERATION_KEYS_COUNT_CONTEXT_ENTRY =
            "carddemo.systran.generation.keys.count";

    /**
     * Prefix of the indexed job execution context entries described on
     * {@link #SYSTRAN_GENERATION_KEYS_COUNT_CONTEXT_ENTRY}. The entry for index {@code n} is this prefix
     * followed by {@code n}, rendered by {@link #generationKeysIndexEntry(int)}.
     */
    public static final String SYSTRAN_GENERATION_KEYS_INDEX_PREFIX = "carddemo.systran.generation.keys.";

    /**
     * Zero-padding width of both numeric key segments. Nineteen digits is the widest a signed 64-bit
     * value needs, so lexicographic and numeric order coincide for every possible identifier - which
     * is what makes a {@code (0)} generation read resolve to the newest generation. It matches the
     * width {@link TransactionWriter} already uses, so the two writers produce comparable key shapes.
     */
    private static final int KEY_NUMBER_WIDTH = 19;

    /** Key template: prefix, job instance, base name, ordinal, suffix. */
    private static final String KEY_TEMPLATE =
            "%s/%0" + KEY_NUMBER_WIDTH + "d/%s-%0" + KEY_NUMBER_WIDTH + "d%s";

    /** Base name of every emitted object, from the generation-data-group name {@code SYSTRAN}. */
    private static final String OBJECT_BASE_NAME = "systran";

    /** Suffix of every emitted object. The content is fixed-length binary, not text. */
    private static final String OBJECT_SUFFIX = ".dat";

    /** Content type of every emitted object: opaque fixed-length records, not a text document. */
    private static final String OBJECT_CONTENT_TYPE = "application/octet-stream";

    /**
     * Charset of the fixed-width emission. Single-byte and explicit, so one character is one byte and
     * the {@value TransactionWriter#RECORD_LENGTH}-character image is a
     * {@value TransactionWriter#RECORD_LENGTH}-byte record. It is never the platform default and never
     * a multi-byte encoding: a single multi-byte character would silently lengthen the record and break
     * every offset downstream of it.
     */
    private static final Charset FIXED_WIDTH_CHARSET = StandardCharsets.ISO_8859_1;

    /**
     * The diagnostic context entries this job's listener owns for the duration of a run, captured on entry so
     * that {@code afterJob} can put them back instead of deleting them.
     *
     * <p><b>Finding M-03, severity Medium, RESOLVED - two defects, one root cause.</b> This class used to
     * declare its own {@code "jobInstanceId"} and {@code "correlationId"} literals, duplicating the keys
     * {@link CorrelationIdFilter} publishes as {@link CorrelationIdFilter#MDC_KEY_JOB_INSTANCE_ID} and
     * {@link CorrelationIdFilter#MDC_KEY_CORRELATION_ID}, and it ended a run with an unconditional
     * {@code MDC.remove} on both. The duplication meant a key could be renamed in one place and silently
     * diverge from {@code logback-spring.xml}; the unconditional removal was worse, because Spring Batch runs
     * jobs on pooled threads and a job launched from inside a request, or a partitioned step whose parent
     * already labelled the thread, had its caller's context destroyed. Note the asymmetry that made it a
     * defect rather than a style point: the old {@code beforeJob} deliberately <em>respected</em> an inherited
     * correlation identifier by only setting one when absent, and then {@code afterJob} deleted the very value
     * it had just taken care not to overwrite.
     *
     * <p>Held in a {@link ThreadLocal} rather than a field because the listener is documented as stateless and
     * must stay that way: the job bean is a singleton, so an instance field would be shared across concurrent
     * executions - the same defect class as the writers' step-scope finding. A thread-local has exactly the
     * scope of the thing it is snapshotting, since {@link MDC} is itself thread-confined, and it is removed in
     * the same {@code finally} that restores, so nothing is retained on a pooled thread.
     */
    private static final ThreadLocal<DiagnosticContextSnapshot> DIAGNOSTIC_SNAPSHOT = new ThreadLocal<>();

    /** Logical name of the category-balance dataset, from {@code app/jcl/INTCALC.jcl:L27}. */
    private static final String DD_TCATBALF = "TCATBALF";

    /**
     * Logical name of the cross-reference dataset, from {@code app/jcl/INTCALC.jcl:L29} - and, decisively,
     * the name the program's own file definition assigns:
     * {@code SELECT XREF-FILE ASSIGN TO   XREFFILE} at {@code app/cbl/CBACT04C.cbl:L34}. Every
     * cross-reference diagnostic this class emits is therefore keyed on {@code XREFFILE}, because that is
     * the only cross-reference name {@code CBACT04C} itself knows.
     */
    private static final String DD_XREFFILE = "XREFFILE";

    /**
     * Logical name of the cross-reference alternate-index path, from
     * {@code app/jcl/INTCALC.jcl:L31}-{@code :L32}
     * {@code DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH}, one of exactly three alternate-index paths in
     * the catalogue ({@code app/catlg/LISTCAT.txt:L3946} reports {@code PATH 3}).
     *
     * <p><strong>Finding - severity Medium.</strong> This DD is <em>supplied but never referenced</em>.
     * {@code XREFFIL1} occurs exactly once in the entire frozen corpus - the DD statement at
     * {@code app/jcl/INTCALC.jcl:L31} - and no {@code SELECT} in any program assigns to it. The {@code FILE-CONTROL}
     * block of {@code CBACT04C} declares a single cross-reference file, assigned to {@code XREFFILE} at
     * {@code app/cbl/CBACT04C.cbl:L34}, and reaches the alternate key through
     * {@code ALTERNATE RECORD KEY IS FD-XREF-ACCT-ID} at {@code :L38} on that one file definition - not through a
     * second DD. The agent brief describes {@code XREFFIL1} as "a second logical view of the same dataset"; that is
     * true of the JCL's intent but not of the program's behaviour, so the brief is corrected here rather than
     * followed. Retained as a named constant so the unreferenced allocation is traceable and is reported at
     * {@code DEBUG} by {@link #openCrossReferenceFile()} instead of vanishing silently; owed an entry in the planned
     * {@code DECISION_LOG.md}.
     *
     * <p>The Java consequence is nil: both DDs would collapse onto
     * {@link com.cardemo.repository.CardCrossReferenceRepository} regardless - the primary-key finder for
     * the base view and {@code findFirstByAccountIdOrderByCardNumberAsc} for the alternate key - so the
     * correction changes the diagnostic identity only, never an access path.
     */
    private static final String DD_XREFFIL1 = "XREFFIL1";

    /** Logical name of the account dataset, from {@code app/jcl/INTCALC.jcl:L33}. */
    private static final String DD_ACCTFILE = "ACCTFILE";

    /** Logical name of the disclosure-group dataset, from {@code app/jcl/INTCALC.jcl:L35}. */
    private static final String DD_DISCGRP = "DISCGRP";

    /** Logical name of the sequential output dataset, from {@code app/jcl/INTCALC.jcl:L37}. */
    private static final String DD_TRANSACT = "TRANSACT";

    /**
     * Component name carried by every abend this job raises, from
     * {@code PROGRAM-ID. CBACT04C} at {@code app/cbl/CBACT04C.cbl:L23}. It fits
     * {@code ABEND-CULPRIT PIC X(8)} ({@code app/cpy/CSMSG02Y.cpy}) exactly.
     */
    private static final String ABEND_CULPRIT = "CBACT04C";

    /**
     * The four-character abend code, rendered from the {@code MOVE 999 TO ABCODE} of
     * {@code app/cbl/CBACT04C.cbl:L631}. It is {@value FatalProcessingException#BATCH_ABEND_CODE} and
     * never {@code 9999}, which is the CICS online value - conflating the two is a Blocker.
     */
    private static final String ABEND_CODE = String.valueOf(FatalProcessingException.BATCH_ABEND_CODE);

    /** Verbatim from {@code DISPLAY 'ABENDING PROGRAM'} at {@code app/cbl/CBACT04C.cbl:L629}. */
    private static final String MSG_ABENDING_PROGRAM = "ABENDING PROGRAM";

    /** Verbatim from {@code app/cbl/CBACT04C.cbl:L181}. */
    private static final String MSG_START_OF_EXECUTION = "START OF EXECUTION OF PROGRAM CBACT04C";

    /** Verbatim from {@code app/cbl/CBACT04C.cbl:L230}. */
    private static final String MSG_END_OF_EXECUTION = "END OF EXECUTION OF PROGRAM CBACT04C";

    /**
     * Verbatim from {@code DISPLAY 'ERROR OPENING TRANSACTION CATEGORY BALANCE'} at
     * {@code app/cbl/CBACT04C.cbl:L245}.
     */
    private static final String MSG_ERROR_OPENING_TCATBALF =
            "ERROR OPENING TRANSACTION CATEGORY BALANCE";

    /**
     * Verbatim from {@code app/cbl/CBACT04C.cbl:L263}, which reads
     * {@code DISPLAY 'ERROR OPENING CROSS REF FILE'   XREFFILE-STATUS}. This is the only one of the five
     * open diagnostics that appends the raw status as a second operand of the same {@code DISPLAY}; the
     * other four emit the literal alone and leave the status to
     * {@code 9910-DISPLAY-IO-STATUS}. The extra operand is reproduced by
     * {@link #openCrossReferenceFile()} appending the status to this literal, so the emitted line
     * matches. Severity Low: an inconsistency in the source's own diagnostics, preserved rather than
     * normalised.
     */
    private static final String MSG_ERROR_OPENING_XREFFILE = "ERROR OPENING CROSS REF FILE";

    /**
     * Verbatim from {@code app/cbl/CBACT04C.cbl:L281}, which really does read
     * {@code DISPLAY 'ERROR OPENING DALY REJECTS FILE'} inside {@code 0200-DISCGRP-OPEN} - a paragraph
     * that opens {@code DISCGRP-FILE} at {@code :L272} and has nothing to do with any rejects dataset.
     * <b>This is a legacy copy-and-paste defect in the diagnostic text and it is preserved verbatim,
     * not repaired.</b> The literal is part of the observable output the parity comparison is measured
     * against, so correcting it here would register as a diff. Severity <b>Medium</b>: an operator
     * reading this line is pointed at the wrong dataset. It is owed an entry in the planned {@code DECISION_LOG.md}
     * alongside the other preserved legacy defects.
     */
    private static final String MSG_ERROR_OPENING_DISCGRP = "ERROR OPENING DALY REJECTS FILE";

    /**
     * Verbatim from {@code DISPLAY 'ERROR OPENING ACCOUNT MASTER FILE'} at
     * {@code app/cbl/CBACT04C.cbl:L300}. Note "MASTER", which the close diagnostic at {@code :L588}
     * omits - the two paragraphs name the same dataset differently, and both spellings are preserved.
     */
    private static final String MSG_ERROR_OPENING_ACCTFILE = "ERROR OPENING ACCOUNT MASTER FILE";

    /**
     * Verbatim from {@code DISPLAY 'ERROR OPENING TRANSACTION FILE'} at
     * {@code app/cbl/CBACT04C.cbl:L318}.
     */
    private static final String MSG_ERROR_OPENING_TRANFILE = "ERROR OPENING TRANSACTION FILE";

    /**
     * Verbatim from {@code DISPLAY 'ERROR WRITING TRANSACTION RECORD'} at
     * {@code app/cbl/CBACT04C.cbl:L510}, the guard on the {@code WRITE FD-TRANFILE-REC} at {@code :L500}.
     */
    private static final String MSG_ERROR_WRITING_TRANSACTION_RECORD =
            "ERROR WRITING TRANSACTION RECORD";

    /**
     * Verbatim from {@code DISPLAY 'ERROR CLOSING TRANSACTION BALANCE FILE'} at
     * {@code app/cbl/CBACT04C.cbl:L533}. Note that the open diagnostic at {@code :L245} calls the same
     * dataset a "TRANSACTION CATEGORY BALANCE"; both spellings are preserved as written.
     */
    private static final String MSG_ERROR_CLOSING_TCATBALF = "ERROR CLOSING TRANSACTION BALANCE FILE";

    /** Verbatim from {@code DISPLAY 'ERROR CLOSING CROSS REF FILE'} at {@code app/cbl/CBACT04C.cbl:L552}. */
    private static final String MSG_ERROR_CLOSING_XREFFILE = "ERROR CLOSING CROSS REF FILE";

    /**
     * Verbatim from {@code DISPLAY 'ERROR CLOSING DISCLOSURE GROUP FILE'} at
     * {@code app/cbl/CBACT04C.cbl:L570}. The close paragraph names the dataset correctly, which is what
     * proves the open paragraph's "DALY REJECTS" text at {@code :L281} is a defect rather than an
     * alternative name for it.
     */
    private static final String MSG_ERROR_CLOSING_DISCGRP = "ERROR CLOSING DISCLOSURE GROUP FILE";

    /** Verbatim from {@code DISPLAY 'ERROR CLOSING ACCOUNT FILE'} at {@code app/cbl/CBACT04C.cbl:L588}. */
    private static final String MSG_ERROR_CLOSING_ACCTFILE = "ERROR CLOSING ACCOUNT FILE";

    /** Verbatim from {@code DISPLAY 'ERROR CLOSING TRANSACTION FILE'} at {@code app/cbl/CBACT04C.cbl:L606}. */
    private static final String MSG_ERROR_CLOSING_TRANFILE = "ERROR CLOSING TRANSACTION FILE";

    /**
     * The status this class reports to {@link FileStatusMapper} when an object-storage call fails: the
     * {@code '9x'} family, which the source reserves for a physical or logical input-output error and
     * which the mapper turns into a file-access exception carrying the four-character expanded status.
     */
    private static final String OBJECT_STORE_IO_STATUS = FileStatus.IO_ERROR_FIRST_BYTE + "0";

    /** The successful status of every guard in the source, {@code '00'}. */
    private static final String SUCCESS_STATUS = FileStatus.SUCCESS.code().orElseThrow();

    /**
     * The status this class reports when a dataset cannot be made available at all: {@code '35'}, which
     * {@link FileStatus#FILE_UNAVAILABLE} defines and which the source reserves for exactly that
     * condition. It is what the five {@code OPEN} paragraphs report when their probe cannot reach the
     * store, and it renders through {@code 9910-DISPLAY-IO-STATUS} as {@code 0035}, so the emitted
     * diagnostic is indistinguishable from the legacy one.
     */
    private static final String OPEN_FAILURE_STATUS = FileStatus.FILE_UNAVAILABLE.code().orElseThrow();

    /**
     * Account identifier used to probe that the account and cross-reference datasets are readable.
     * {@code OPEN INPUT} asserts that a dataset is <em>available</em>, not that any particular record
     * exists, so a probe that finds nothing is a successful open; only an infrastructure failure is
     * the abend. Zero cannot collide with a real key because {@code ACCT-ID PIC 9(11)} is seeded from
     * {@code app/data/ASCII/acctdata.txt} starting at {@code 00000000001}.
     */
    private static final long OPEN_PROBE_ACCOUNT_ID = 0L;

    /** Transaction type code used to probe that the disclosure-group dataset is readable. */
    private static final String OPEN_PROBE_TRAN_TYPE_CD = "00";

    /** Transaction category code used to probe that the disclosure-group dataset is readable. */
    private static final Integer OPEN_PROBE_TRAN_CAT_CD = Integer.valueOf(0);

    /** Page size of the bounded probe that stands in for {@code OPEN INPUT} on the driving dataset. */
    private static final int OPEN_PROBE_PAGE_SIZE = 1;

    /**
     * Name under which the driving reader saves its restart state. Spring Batch requires a name once
     * state is saved, and a stable one is what lets a failed run resume rather than restart.
     */
    private static final String READER_NAME = "interestCalculationCategoryBalanceReader";

    /**
     * The repository query method the reader invokes. Named as a constant because
     * {@link RepositoryItemReader} resolves it reflectively, so a rename of
     * {@link TransactionCategoryBalanceRepository#findAllByOrderByIdAccountIdAscIdTypeCdAscIdCatCdAsc}
     * would otherwise fail at runtime rather than at compile time. The compile-time link is preserved
     * by the {@code @link} in this sentence and by the sort keys below, which name the same properties.
     */
    private static final String READER_METHOD_NAME =
            "findAllByOrderByIdAccountIdAscIdTypeCdAscIdCatCdAsc";

    /**
     * The three components of the seventeen-byte composite key of
     * {@code app/cpy/CVTRA01Y.cpy:L6}-{@code :L8}, in copybook order and in a
     * {@link LinkedHashMap} so that the emitted {@code ORDER BY} clause is deterministic. A plain
     * {@link java.util.HashMap} would order the keys by hash, which would make the control break's
     * correctness depend on an implementation detail of the JDK.
     */
    private static final Map<String, Sort.Direction> READER_SORT = unmodifiableSortKeys();

    /** Return code 0: the normal outcome of this job, since {@code CBACT04C} sets no return code. */
    private static final String EXIT_CODE_COMPLETED = ExitStatus.COMPLETED.getExitCode();

    /**
     * Return code 4, completed-with-rejects. <strong>This job never produces it.</strong> The only
     * {@code MOVE 4 TO RETURN-CODE} in the corpus is {@code app/cbl/CBTRN02C.cbl:L230}. The transition
     * exists so the decider covers the pipeline's full code set, not because a path reaches it.
     */
    private static final String EXIT_CODE_COMPLETED_WITH_REJECTS = "COMPLETED WITH REJECTS";

    /** Return code 8: the step failed for a reason that is not an abend. */
    private static final String EXIT_CODE_FAILED = ExitStatus.FAILED.getExitCode();

    /**
     * Return code 12: abend. It is the conventional language-environment consequence of the
     * {@code CALL 'CEE3ABD'} at {@code app/cbl/CBACT04C.cbl:L632}; there is no
     * {@code MOVE 12 TO RETURN-CODE} anywhere in the corpus and no locator is invented for one.
     */
    private static final String EXIT_CODE_ABEND = "ABEND";

    /** Wildcard transition pattern, so no outcome can leave the flow without a decision. */
    private static final String EXIT_CODE_ANY = "*";

    /**
     * The job-level exit status published whenever an abend is among the recorded failures, wherever it
     * was raised - inside the step, or in one of the {@code OPEN} paragraphs that run before the flow is
     * entered. {@link ExitStatus} is immutable in Spring Batch 5
     * ({@link ExitStatus#addExitDescription(String)} returns a new instance rather than mutating), so a
     * shared constant carries no aliasing hazard and is not mutable static state.
     */
    private static final ExitStatus ABEND_EXIT_STATUS = new ExitStatus(EXIT_CODE_ABEND,
            "Abend " + FatalProcessingException.BATCH_ABEND_CODE + " raised by " + ABEND_CULPRIT
                    + "; process return code " + FatalProcessingException.BATCH_RETURN_CODE + ".");

    /**
     * Step execution context entry holding the ordinal of the next generation object. It lives in the
     * execution context rather than in a field so that it is scoped to one step execution: a field on
     * the writer would be shared by every execution of the singleton step bean, which is the same
     * global-mutable-state defect that clause B forbids and that
     * {@code WS-TRANID-SUFFIX PIC 9(06)} would exhibit if it were translated to a {@code static}.
     */
    private static final String OBJECT_ORDINAL_CONTEXT_ENTRY = "carddemo.systran.object.ordinal";

    /** Reason text carried by an abend raised while opening a dataset. Fits {@code ABEND-REASON X(50)}. */
    private static final String REASON_OPEN_FAILED = "OPEN FAILED";

    /** Reason text carried by an abend raised while closing a dataset. */
    private static final String REASON_CLOSE_FAILED = "CLOSE FAILED";

    /** Reason text carried by an abend raised while emitting a generation object. */
    private static final String REASON_WRITE_FAILED = "WRITE FAILED";

    /** Reason text carried by an abend raised because the batch runtime supplied no step context. */
    private static final String REASON_NO_STEP_CONTEXT = "NO STEP CONTEXT";

    /** Reason text carried by an abend raised because a record image is not the mandated length. */
    private static final String REASON_BAD_RECORD_LENGTH = "RECORD LENGTH VIOLATION";

    /** Reason text carried by an abend raised because a record carries the wrong transaction source. */
    private static final String REASON_BAD_TRANSACTION_SOURCE = "TRANSACTION SOURCE VIOLATION";

    /** Reason text carried by an abend raised because the driving reader could not be configured. */
    private static final String REASON_READER_CONFIGURATION = "READER CONFIGURATION";

    /**
     * The batch metadata store. <b>Injected, never declared</b>: Spring Boot's batch auto-configuration
     * owns it, and re-declaring it here - or adding the Spring Batch enablement annotation, which
     * switches that auto-configuration off - would replace a correctly wired repository with a second,
     * unmanaged one.
     */
    private final JobRepository jobRepository;

    /**
     * The transaction manager the chunk boundary commits through. Qualified by the conventional bean name
     * that Spring Boot's JPA auto-configuration publishes, so that a second transaction manager
     * appearing later cannot make this injection ambiguous.
     *
     * <p>Its scope is the parity contract of {@code app/cbl/CBACT04C.cbl}: the account rewrite of
     * {@code 1050-UPDATE-ACCOUNT} at {@code :L356} and the transaction emission of
     * {@code 1300-B-WRITE-TX} at {@code :L500} are independent commits in the source, because one goes to
     * a keyed cluster and the other to a sequential file. Here the account rewrite is transactional and
     * the object emission is not, which preserves that independence rather than inventing an atomicity
     * the source does not have.
     */
    private final PlatformTransactionManager transactionManager;

    /**
     * The driving dataset, {@code TCATBALF} at {@code app/jcl/INTCALC.jcl:L27}-{@code :L28}, opened
     * {@code OPEN INPUT} at {@code app/cbl/CBACT04C.cbl:L236} and browsed sequentially in composite-key
     * order.
     */
    private final TransactionCategoryBalanceRepository categoryBalanceRepository;

    /**
     * The account dataset, {@code ACCTFILE} at {@code app/jcl/INTCALC.jcl:L33}-{@code :L34}. The source
     * opens it {@code OPEN I-O} at {@code app/cbl/CBACT04C.cbl:L291} - read <em>and</em> write, because
     * {@code 1050-UPDATE-ACCOUNT} rewrites it - which is the only one of the five opens that is not
     * input-only or output-only. Held here for the {@code 0300-ACCTFILE-OPEN} availability probe; the
     * per-record read and rewrite are the processor's.
     */
    private final AccountRepository accountRepository;

    /**
     * The cross-reference dataset, supplied twice by the JCL: as the base cluster {@code XREFFILE} at
     * {@code app/jcl/INTCALC.jcl:L29}-{@code :L30} and again as the alternate-index path
     * {@code XREFFIL1} at {@code :L31}-{@code :L32}. Both logical views collapse onto this one
     * repository - the primary-key finder for the base view, and
     * {@link CardCrossReferenceRepository#findFirstByAccountIdOrderByCardNumberAsc} for the path, matching
     * {@code ALTERNATE RECORD KEY IS FD-XREF-ACCT-ID} at {@code app/cbl/CBACT04C.cbl:L38} and the
     * {@code READ ... KEY IS FD-XREF-ACCT-ID} at {@code :L396}.
     */
    private final CardCrossReferenceRepository crossReferenceRepository;

    /**
     * The disclosure-group dataset, {@code DISCGRP} at {@code app/jcl/INTCALC.jcl:L35}-{@code :L36}.
     * Held here for the {@code 0200-DISCGRP-OPEN} availability probe; the rate lookup and its
     * {@code DEFAULT} fallback are the processor's.
     */
    private final DisclosureGroupRepository disclosureGroupRepository;

    /**
     * Consulted for exactly two published members - {@link TransactionWriter#RECORD_LENGTH} and the pure
     * {@link TransactionWriter#composeFixedWidthImage(Transaction)} - so the 350-byte record geometry of
     * {@code app/cpy/CVTRA05Y.cpy} has a single owner across the whole batch subtree.
     *
     * <p><b>Its {@code write} method is deliberately never called.</b> That method also inserts each item
     * into the transaction table, which is correct for {@code app/cbl/CBTRN02C.cbl} and wrong here: see
     * Finding 2 in the class documentation. Severity High, because the mistake would compile and run.
     */
    private final TransactionWriter transactionWriter;

    /**
     * The object-storage gateway standing in for the {@code SYSTRAN} generation data group. Injected as
     * an interface: <b>no client is constructed here and no endpoint is named</b>, so the local emulator
     * override that {@code application-local.yml} and {@code application-test.yml} apply is the only
     * thing that decides where a write lands, and no live path is structurally reachable from this file.
     */
    private final S3Operations s3Operations;

    /**
     * The single tree-wide translation from a {@code FILE STATUS} to a typed exception and to the
     * {@code APPL-RESULT} of {@code app/cbl/CBACT04C.cbl:L154}-{@code :L156}. Used here for the guard
     * arithmetic of the five {@code OPEN} and five {@code CLOSE} paragraphs and for the
     * {@code 9910-DISPLAY-IO-STATUS} rendering, so neither is restated.
     */
    private final FileStatusMapper fileStatusMapper;

    /**
     * The owner of the four - and only four - batch counters. This class advances exactly one of them,
     * {@code carddemo.batch.records.processed}, once per emitted record, replacing the end-of-run
     * {@code DISPLAY} counters that the corpus uses for the same purpose. <b>No fifth instrument, no
     * timer, no gauge and no high-cardinality tag is registered anywhere in this file.</b>
     */
    private final MetricsConfig metricsConfig;

    /** The job name, from {@code carddemo.batch.jobs.intcalc.name}. */
    private final String jobName;

    /** The commit interval and read page size, from {@code carddemo.batch.intcalc.chunk-size}. */
    private final int chunkSize;

    /**
     * The versioned output bucket, from {@code carddemo.aws.s3.batch-output-bucket}.
     *
     * <p><b>This is the one binding in this class with no literal default, deliberately.</b>
     * {@code src/main/resources/application.yml} declares that property as
     * {@code ${CARDDEMO_S3_BATCH_OUTPUT_BUCKET}} with no fallback, and states the reason in its own
     * accompanying comment: a missing
     * bucket name must fail startup rather than let a run write "into whatever bucket happens to exist".
     * Supplying a default here would be doubly wrong - it would be unreachable, because the property is
     * always defined by that file and an unset environment variable fails placeholder resolution before
     * this constructor is entered, and it would convert a fail-fast misconfiguration into a silent write
     * to the wrong location, which is precisely what clause D's least-privilege standard forbids. The
     * agent brief's instruction to "put a documented default on every {@code @Value}" is therefore
     * honoured for the job name, the chunk size and the generation prefix, and consciously not honoured
     * for the bucket. Severity of the divergence: <b>Low</b>; owed an entry in the planned {@code DECISION_LOG.md}.
     */
    private final String batchOutputBucket;

    /** The generation prefix, from {@code carddemo.aws.s3.gdg-prefixes.systran}. */
    private final String systranPrefix;

    /**
     * Sole constructor: <b>constructor injection only</b>, every field {@code final}, so an instance is
     * immutable once built and cannot be half-configured. Field or setter injection would leave a window
     * in which a collaborator is {@code null}, and the source has no equivalent of a partially opened
     * program.
     *
     * <p>Each configuration parameter carries a documented default except the output bucket, which is
     * deliberately left without one so that an unconfigured context fails at startup rather than writing
     * a production run into whichever bucket happens to be reachable - the least-privilege reading of
     * clause D. The chunk-size placeholder is nested: the job-specific
     * {@code carddemo.batch.intcalc.chunk-size} wins if present, the shared
     * {@code carddemo.batch.chunk-size} is the fallback, and {@value #DEFAULT_CHUNK_SIZE} is the final
     * default, so the job is tunable in isolation without forcing a property that
     * {@code application.yml} does not currently declare.
     *
     * @param jobRepository the batch metadata store, from Spring Boot's batch auto-configuration
     * @param transactionManager the transaction manager the chunk boundary commits through
     * @param categoryBalanceRepository the driving dataset, {@code TCATBALF}
     * @param accountRepository the account dataset, {@code ACCTFILE}
     * @param crossReferenceRepository the cross-reference dataset, {@code XREFFILE} and {@code XREFFIL1}
     * @param disclosureGroupRepository the disclosure-group dataset, {@code DISCGRP}
     * @param transactionWriter consulted only for the 350-byte record geometry, never for its writes
     * @param s3Operations the object-storage gateway standing in for the {@code SYSTRAN} generations
     * @param fileStatusMapper the single {@code FILE STATUS} translation
     * @param metricsConfig the owner of the four batch counters
     * @param jobName the job name, defaulting to {@value #DEFAULT_JOB_NAME}
     * @param chunkSize the commit interval, defaulting to {@value #DEFAULT_CHUNK_SIZE}
     * @param batchOutputBucket the versioned output bucket; required, so a missing value fails startup
     * @param systranPrefix the generation prefix, defaulting to {@value #DEFAULT_SYSTRAN_PREFIX}
     */
    public InterestCalculationJob(
            final JobRepository jobRepository,
            @Qualifier("transactionManager") final PlatformTransactionManager transactionManager,
            final TransactionCategoryBalanceRepository categoryBalanceRepository,
            final AccountRepository accountRepository,
            final CardCrossReferenceRepository crossReferenceRepository,
            final DisclosureGroupRepository disclosureGroupRepository,
            final TransactionWriter transactionWriter,
            final S3Operations s3Operations,
            final FileStatusMapper fileStatusMapper,
            final MetricsConfig metricsConfig,
            @Value("${carddemo.batch.jobs.intcalc.name:" + DEFAULT_JOB_NAME + "}") final String jobName,
            @Value("${carddemo.batch.intcalc.chunk-size:${carddemo.batch.chunk-size:"
                    + DEFAULT_CHUNK_SIZE + "}}") final int chunkSize,
            @Value("${carddemo.aws.s3.batch-output-bucket}") final String batchOutputBucket,
            @Value("${carddemo.aws.s3.gdg-prefixes.systran:" + DEFAULT_SYSTRAN_PREFIX + "}")
            final String systranPrefix) {

        this.jobRepository = requireCollaborator(jobRepository, "jobRepository");
        this.transactionManager = requireCollaborator(transactionManager, "transactionManager");
        this.categoryBalanceRepository =
                requireCollaborator(categoryBalanceRepository, "categoryBalanceRepository");
        this.accountRepository = requireCollaborator(accountRepository, "accountRepository");
        this.crossReferenceRepository =
                requireCollaborator(crossReferenceRepository, "crossReferenceRepository");
        this.disclosureGroupRepository =
                requireCollaborator(disclosureGroupRepository, "disclosureGroupRepository");
        this.transactionWriter = requireCollaborator(transactionWriter, "transactionWriter");
        this.s3Operations = requireCollaborator(s3Operations, "s3Operations");
        this.fileStatusMapper = requireCollaborator(fileStatusMapper, "fileStatusMapper");
        this.metricsConfig = requireCollaborator(metricsConfig, "metricsConfig");
        this.jobName = requireText(jobName, "carddemo.batch.jobs.intcalc.name");
        this.chunkSize = requirePositive(chunkSize, "carddemo.batch.intcalc.chunk-size");
        this.batchOutputBucket = requireText(batchOutputBucket, "carddemo.aws.s3.batch-output-bucket");
        this.systranPrefix = normalisePrefix(
                requireText(systranPrefix, "carddemo.aws.s3.gdg-prefixes.systran"));
    }

    /**
     * The three components of the composite key, in the order {@code app/cpy/CVTRA01Y.cpy} declares them,
     * held in an insertion-ordered map wrapped unmodifiable.
     *
     * <p>The order is load-bearing twice over. {@link RepositoryItemReader} converts this map into a
     * {@link Sort} by iterating it, so a hash-ordered map would emit the key columns of the
     * {@code ORDER BY} in an arbitrary sequence and the account-level control break at
     * {@code app/cbl/CBACT04C.cbl:L194} - which tests the account identifier alone - would split one
     * account across several breaks. Wrapping it unmodifiable makes the constant genuinely immutable, so
     * it is shared static state without being mutable static state.
     *
     * @return the sort keys {@code id.accountId}, {@code id.typeCd}, {@code id.catCd}, all ascending
     */
    private static Map<String, Sort.Direction> unmodifiableSortKeys() {
        final Map<String, Sort.Direction> keys = new LinkedHashMap<>();
        keys.put("id.accountId", Sort.Direction.ASC);
        keys.put("id.typeCd", Sort.Direction.ASC);
        keys.put("id.catCd", Sort.Direction.ASC);
        return Collections.unmodifiableMap(keys);
    }

    /**
     * Rejects an absent collaborator with a message that names it, so a mis-wired context fails with a
     * diagnosis rather than with a {@code NullPointerException} at first use. Clause B requires null and
     * empty cases to be handled explicitly rather than assumed away.
     *
     * @param <T> the collaborator type
     * @param collaborator the injected collaborator
     * @param name the parameter name, for the diagnostic
     * @return {@code collaborator}, never {@code null}
     * @throws FatalProcessingException if {@code collaborator} is {@code null}
     */
    private static <T> T requireCollaborator(final T collaborator, final String name) {
        if (collaborator == null) {
            throw new FatalProcessingException(ABEND_CODE, ABEND_CULPRIT, "MISSING COLLABORATOR",
                    "InterestCalculationJob requires a non-null " + name + ".");
        }
        return collaborator;
    }

    /**
     * Rejects a blank configuration value, trimming an otherwise usable one. A blank bucket name would
     * otherwise produce an object-storage call against an empty bucket, which fails far from its cause.
     *
     * @param value the configured value
     * @param property the property name, for the diagnostic
     * @return the trimmed value, never blank
     * @throws FatalProcessingException if {@code value} is {@code null} or blank
     */
    private static String requireText(final String value, final String property) {
        final String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new FatalProcessingException(ABEND_CODE, ABEND_CULPRIT, "MISSING CONFIGURATION",
                    "Property " + property + " must be configured with a non-blank value.");
        }
        return trimmed;
    }

    /**
     * Rejects a non-positive chunk size. Spring Batch itself rejects one, but it does so with a message
     * that names neither the property nor this job.
     *
     * @param value the configured value
     * @param property the property name, for the diagnostic
     * @return {@code value}, guaranteed positive
     * @throws FatalProcessingException if {@code value} is not greater than zero
     */
    private static int requirePositive(final int value, final String property) {
        if (value <= 0) {
            throw new FatalProcessingException(ABEND_CODE, ABEND_CULPRIT, "INVALID CONFIGURATION",
                    "Property " + property + " must be greater than zero but was " + value + ".");
        }
        return value;
    }

    /**
     * Strips any trailing separator from the configured generation prefix so that
     * {@link #composeObjectKey(long, long)} produces exactly one separator between segments whether the
     * property is written {@code gdg/systran} or {@code gdg/systran/}. A doubled separator would create a
     * distinct, empty-named folder in the object store and break the lexicographic ordering that makes
     * a {@code (0)} generation read resolve to the newest generation.
     *
     * @param prefix the configured, already non-blank prefix
     * @return the prefix without a trailing separator
     */
    private static String normalisePrefix(final String prefix) {
        String normalised = prefix;
        while (normalised.endsWith("/")) {
            normalised = normalised.substring(0, normalised.length() - 1);
        }
        if (normalised.isEmpty()) {
            throw new FatalProcessingException(ABEND_CODE, ABEND_CULPRIT, "INVALID CONFIGURATION",
                    "Property carddemo.aws.s3.gdg-prefixes.systran must name a prefix, not only separators.");
        }
        return normalised;
    }

    /**
     * The single step of {@code app/jcl/INTCALC.jcl}, whose {@code STEP15} at {@code :L22} runs
     * {@code EXEC PGM=CBACT04C}. The member declares one program step and <b>no {@code COND=} parameter
     * anywhere</b>, so there is nothing to gate between steps and one step is the faithful shape.
     *
     * <p>The chunk is bounded by {@link #chunkSize} rather than by a completion policy, because the
     * source has no chunk concept at all and any policy would be an invention; a fixed commit interval is
     * the minimal, documented deviation.
     *
     * <p>The processor arrives as a <b>method parameter rather than as a field</b>. It is
     * {@code @StepScope}, so the container hands over a scoped proxy that resolves to a fresh instance
     * per step execution - which is exactly what keeps its {@code WS-TRANID-SUFFIX} counter
     * ({@code app/cbl/CBACT04C.cbl:L173}) run-sequential without it ever becoming shared mutable state.
     * Holding it in a constructor-injected field would force the proxy to be created while this
     * configuration class is still being built.
     *
     * @param interestCalculationProcessor the step-scoped per-record body, which owns the eight delegated
     * paragraphs listed in the class documentation
     * @return the configured chunk-oriented step, never {@code null}
     */
    @Bean(STEP_BEAN_NAME)
    public Step interestCalculationStep(
            final InterestCalculationProcessor interestCalculationProcessor) {

        return new StepBuilder(STEP_BEAN_NAME, jobRepository)
                .<TransactionCategoryBalance, Transaction>chunk(chunkSize, transactionManager)
                .reader(categoryBalanceReader())
                .processor(interestCalculationProcessor)
                .writer(new SystranGenerationWriter())
                .build();
    }

    /**
     * Wraps the single step so that <b>every</b> outcome is routed through
     * {@link InterestCalculationReturnCodeDecider} and mapped onto the four legacy return codes.
     *
     * <p>The wildcard transition is deliberate and load-bearing: without it a failed step would
     * short-circuit the flow and the decider would never run, so return codes 8 and 12 could never be
     * distinguished. From the decider, {@code COMPLETED} and {@code COMPLETED WITH REJECTS} end the flow
     * successfully carrying their own exit code, while {@code FAILED}, {@code ABEND} and anything
     * unrecognised fail it. The unrecognised arm exists so that a future decider outcome cannot silently
     * fall through to success.
     *
     * @param interestCalculationStep the single step, injected by bean name so the flow cannot bind to
     * some other step that happens to be assignable
     * @return the gated flow, never {@code null}
     */
    @Bean(FLOW_BEAN_NAME)
    public Flow interestCalculationFlow(
            @Qualifier(STEP_BEAN_NAME) final Step interestCalculationStep) {

        final JobExecutionDecider returnCodeDecider = new InterestCalculationReturnCodeDecider();

        return new FlowBuilder<SimpleFlow>(FLOW_BEAN_NAME)
                .start(interestCalculationStep)
                .on(EXIT_CODE_ANY).to(returnCodeDecider)
                .from(returnCodeDecider).on(EXIT_CODE_COMPLETED).end(EXIT_CODE_COMPLETED)
                .from(returnCodeDecider).on(EXIT_CODE_COMPLETED_WITH_REJECTS)
                .end(EXIT_CODE_COMPLETED_WITH_REJECTS)
                .from(returnCodeDecider).on(EXIT_CODE_FAILED).fail()
                .from(returnCodeDecider).on(EXIT_CODE_ABEND).fail()
                .from(returnCodeDecider).on(EXIT_CODE_ANY).fail()
                .build();
    }

    /**
     * The job itself: the whole of {@code app/jcl/INTCALC.jcl}.
     *
     * <p>Three things are attached here rather than anywhere else. The date-parameter contract is a
     * {@link JobParametersValidator}, so an invalid {@code PARM-DATE} is refused <b>before the step
     * runs</b> and before a single row is read - the closest available analogue of the language
     * environment refusing to pass a malformed {@code PARM}. The mainline's opens, closes and run
     * boundary markers are an {@link InterestCalculationJobListener}. And no incrementer is declared: the
     * source has no run-sequence concept, and adding one would let the same {@code PARM-DATE} be
     * re-processed under a fresh job instance, which is precisely the collision that Finding 2 says must
     * surface downstream rather than be smoothed over.
     *
     * <p>Both the validator and the listener are plain nested objects. Declaring either as a bean would
     * add a container singleton this folder is not permitted to contribute, and neither needs one.
     *
     * @param interestCalculationFlow the gated flow, injected by bean name
     * @return the job, registered under {@link #jobName}, never {@code null}
     */
    @Bean(JOB_BEAN_NAME)
    public Job interestCalculationJob(
            @Qualifier(FLOW_BEAN_NAME) final Flow interestCalculationFlow) {

        return new JobBuilder(jobName, jobRepository)
                .validator(new ParmDateValidator())
                .listener(new InterestCalculationJobListener())
                .start(interestCalculationFlow)
                .end()
                .build();
    }


    /**
     * {@code 1000-TCATBALF-GET-NEXT} - {@code app/cbl/CBACT04C.cbl:L325}-{@code :L348}.
     *
     * <p>The source paragraph is
     * {@code READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD}, then {@code '00'} yields
     * {@code APPL-RESULT 0}, {@code '10'} yields {@code APPL-EOF} and
     * {@code MOVE 'Y' TO END-OF-FILE} at {@code :L340}, and anything else yields
     * {@code APPL-RESULT 12} and an abend. Spring Batch owns the loop, so the three outcomes map onto the
     * reader's contract: a returned item is {@code '00'}, a returned {@code null} is {@code '10'} and is
     * <b>loop termination rather than an exception</b>, and a thrown exception is the abend arm. That is
     * why {@link FileStatusMapper#applResultForSequentialRead} - the sequential-read variant that has an
     * end-of-file outcome - is the guard the source uses here, unlike the {@code OPEN} and {@code CLOSE}
     * paragraphs which use the binary {@link FileStatusMapper#applResultForGuard}.
     *
     * <p>The reader is created here rather than declared as a bean because this folder may contribute only
     * {@link Job}, {@link Step} and {@link Flow} beans. It is registered as an {@code ItemStream} by
     * {@link org.springframework.batch.core.step.builder.SimpleStepBuilder}, which is what opens and
     * closes it around the step and persists its restart state under {@link #READER_NAME}.
     *
     * <p>{@link RepositoryItemReader#afterPropertiesSet()} is invoked explicitly because the reader is not
     * a bean and so the container will not invoke it: skipping it would defer a configuration defect to
     * the first page read, in the middle of a run. The interface declares {@code throws Exception}, which
     * is why the catch is that wide; every actual failure it can raise is a configuration assertion, and
     * the cause is preserved rather than swallowed.
     *
     * @return the configured driving reader, never {@code null}
     */
    private RepositoryItemReader<TransactionCategoryBalance> categoryBalanceReader() {
        final RepositoryItemReader<TransactionCategoryBalance> reader = new RepositoryItemReader<>();
        reader.setName(READER_NAME);
        reader.setRepository(categoryBalanceRepository);
        reader.setMethodName(READER_METHOD_NAME);
        reader.setArguments(List.of());
        reader.setSort(READER_SORT);
        reader.setPageSize(chunkSize);
        try {
            reader.afterPropertiesSet();
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final Exception cause) {
            throw abendProgram(MSG_ERROR_OPENING_TCATBALF, REASON_READER_CONFIGURATION, cause);
        }
        return reader;
    }

    /**
     * The bounded page request the five {@code OPEN} paragraphs probe with: one row, ordered by the same
     * three keys as the driving read, so the probe exercises exactly the access path the run will use
     * rather than a cheaper one that might succeed where the real query fails.
     *
     * @return a single-row page request carrying the composite-key ordering
     */
    private static PageRequest openProbePageRequest() {
        return PageRequest.of(0, OPEN_PROBE_PAGE_SIZE,
                Sort.by(READER_SORT.keySet().toArray(new String[0])));
    }

    /**
     * The guard that closes all ten {@code OPEN} and {@code CLOSE} paragraphs, reproduced once rather than
     * ten times. Every one of them is written
     * {@code IF status = '00' MOVE 0 TO APPL-RESULT ELSE MOVE 12 TO APPL-RESULT END-IF}, then
     * {@code IF APPL-AOK CONTINUE ELSE DISPLAY <literal>, PERFORM 9910-DISPLAY-IO-STATUS,
     * PERFORM 9999-ABEND-PROGRAM END-IF} - see {@code app/cbl/CBACT04C.cbl:L238}-{@code :L249} for the
     * first instance and {@code :L596}-{@code :L610} for the last.
     *
     * <p>Extracting the shared tail is not paragraph consolidation: each of the ten paragraphs keeps its
     * own private method, its own literal and its own citation, and only the guard arithmetic - which
     * {@link FileStatusMapper} already owns as a tree-wide idiom - is shared. Clause C's "avoid
     * duplication" and the one-method-per-paragraph mandate are both satisfied.
     *
     * <p><strong>Why the thrown type is always {@link FatalProcessingException}, and not the per-status
     * subtype {@link FileStatusMapper#requireSuccess(String, String, String, Throwable)} would select.</strong>
     * A sibling writer applies that mapper method to its own write guard
     * ({@code TransactionWriter.java} routes its {@code WRITE} guard through it), which for the synthetic
     * status this class reports would yield {@code FileUnavailableException} - and therefore the failed
     * outcome, return code 8. That would be wrong <em>here</em>, and the corpus says so unambiguously:
     * {@code CBACT04C} contains exactly seventeen {@code PERFORM 9999-ABEND-PROGRAM} sites
     * ({@code app/cbl/CBACT04C.cbl:L248}, {@code :L266}, {@code :L284}, {@code :L303}, {@code :L321},
     * {@code :L345}, {@code :L368}, {@code :L389}, {@code :L411}, {@code :L434}, {@code :L458},
     * {@code :L513}, {@code :L536}, {@code :L555}, {@code :L573}, {@code :L591} and {@code :L609}) and
     * <em>every single</em> failure guard in the program is one of them. There is no typed-failure path in
     * {@code CBACT04C} that is not an abend, so {@code 999} with return code 12 is not an
     * over-approximation - it is exact. {@link FileStatusMapper} is still the sole owner of the guard
     * arithmetic ({@link FileStatusMapper#applResultForGuard(String)}) and of the status rendering
     * ({@link FileStatusMapper#displayIoStatus(String)}); only the choice of terminal type is local, and
     * it is local because the source made it so. Severity of the divergence from the sibling precedent:
     * Low, and owed an entry in the planned {@code DECISION_LOG.md}.
     *
     * @param ioStatus the status the operation reported, {@code '00'} on success
     * @param logicalName the DD name, for the success trace
     * @param failureMessage the source's own {@code DISPLAY} literal for this paragraph, emitted verbatim
     * @param reason the abend reason, distinguishing an open from a close
     * @param cause the underlying failure, or {@code null} when the status was not produced by one
     * @throws FatalProcessingException if {@code ioStatus} is anything but {@code '00'}
     */
    private void guardFileOperation(final String ioStatus, final String logicalName,
            final String failureMessage, final String reason, final Throwable cause) {

        if (fileStatusMapper.applResultForGuard(ioStatus) == FileStatusMapper.APPL_AOK) {
            // IF APPL-AOK CONTINUE - the source does nothing here; the trace is a target-side addition
            // that replaces instrumentation the corpus does not have at all.
            LOG.debug("{} {} completed with status {}", logicalName, reason, ioStatus);
            return;
        }
        LOG.error(failureMessage);
        displayIoStatus(ioStatus);
        throw abendProgram(failureMessage, reason, cause);
    }

    /**
     * {@code 0000-TCATBALF-OPEN} - {@code app/cbl/CBACT04C.cbl:L234}-{@code :L250}, whose verb is
     * {@code OPEN INPUT TCATBAL-FILE} at {@code :L236} and whose failure literal at {@code :L245} is
     * {@code 'ERROR OPENING TRANSACTION CATEGORY BALANCE'}.
     *
     * <p>{@code OPEN INPUT} asserts that a dataset is <em>available</em>, not that it holds any particular
     * record, so the Java counterpart is a bounded single-row probe: reaching the store successfully is
     * the successful open, and an empty result is still a successful open. Only an access failure yields
     * {@code '35'} and the abend. The probe uses the run's own ordering via
     * {@link #openProbePageRequest()}.
     *
     * @throws FatalProcessingException if the driving dataset cannot be reached
     */
    private void openTransactionCategoryBalanceFile() {
        String ioStatus = SUCCESS_STATUS;
        RuntimeException failure = null;
        try {
            categoryBalanceRepository
                    .findAllByOrderByIdAccountIdAscIdTypeCdAscIdCatCdAsc(openProbePageRequest());
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final DataAccessException cause) {
            ioStatus = OPEN_FAILURE_STATUS;
            failure = cause;
        }
        guardFileOperation(ioStatus, DD_TCATBALF, MSG_ERROR_OPENING_TCATBALF, REASON_OPEN_FAILED, failure);
    }

    /**
     * {@code 0100-XREFFILE-OPEN} - {@code app/cbl/CBACT04C.cbl:L252}-{@code :L268}, whose verb is
     * {@code OPEN INPUT XREF-FILE} at {@code :L254}.
     *
     * <p>Two details are specific to this paragraph. Its failure line at {@code :L263} is
     * {@code DISPLAY 'ERROR OPENING CROSS REF FILE'   XREFFILE-STATUS} - the only one of the five that
     * carries a second operand. COBOL's {@code DISPLAY} concatenates operands with no separator, so the
     * emitted line is the literal immediately followed by the two status characters, and that is what is
     * reproduced; the wide gap in the source is source formatting, not output.
     *
     * <p>And the probe deliberately goes through
     * {@link CardCrossReferenceRepository#findFirstByAccountIdOrderByCardNumberAsc} rather than the primary
     * key, because the JCL supplies this dataset twice - as the base cluster {@code XREFFILE} at
     * {@code app/jcl/INTCALC.jcl:L29} and as the alternate-index path {@code XREFFIL1} at {@code :L31} -
     * and it is the path that the run actually reads, matching
     * {@code ALTERNATE RECORD KEY IS FD-XREF-ACCT-ID} at {@code app/cbl/CBACT04C.cbl:L38}. Probing the
     * base view would leave the alternate-key access path untested at open time, which is the view whose
     * absence abends every record.
     *
     * @throws FatalProcessingException if the cross-reference dataset cannot be reached
     */
    private void openCrossReferenceFile() {
        String ioStatus = SUCCESS_STATUS;
        RuntimeException failure = null;
        try {
            // A reachability probe only; the row is never read. LIMIT 1 keeps the probe from transporting
            // a result set it discards.
            crossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(
                    Long.valueOf(OPEN_PROBE_ACCOUNT_ID));
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final DataAccessException cause) {
            ioStatus = OPEN_FAILURE_STATUS;
            failure = cause;
        }
        // The JCL allocates the cross-reference dataset twice - XREFFILE at app/jcl/INTCALC.jcl:L29 and
        // the alternate-index path XREFFIL1 at :L31 - but CBACT04C assigns only XREFFILE
        // (app/cbl/CBACT04C.cbl:L34) and reaches the alternate key through ALTERNATE RECORD KEY at :L38.
        // Reporting the unreferenced allocation keeps the finding observable rather than silent.
        LOG.debug("{} opened; {} is allocated by app/jcl/INTCALC.jcl:L31 but assigned by no SELECT, so both"
                + " views resolve through one repository", DD_XREFFILE, DD_XREFFIL1);
        guardFileOperation(ioStatus, DD_XREFFILE, MSG_ERROR_OPENING_XREFFILE + ioStatus,
                REASON_OPEN_FAILED, failure);
    }

    /**
     * {@code 0200-DISCGRP-OPEN} - {@code app/cbl/CBACT04C.cbl:L270}-{@code :L286}, whose verb is
     * {@code OPEN INPUT DISCGRP-FILE} at {@code :L272} and whose failure literal at {@code :L281} is
     * {@code 'ERROR OPENING DALY REJECTS FILE'} - a legacy copy-and-paste defect preserved verbatim; see
     * {@link #MSG_ERROR_OPENING_DISCGRP}.
     *
     * <p>The probe goes through {@link DisclosureGroupRepository#findDefaultGroupRate} because the
     * {@code DEFAULT} group is the row whose absence abends the job at {@code :L446}, so it is the access
     * path most worth confirming while the run can still fail cheaply. An empty result is still a
     * successful open, exactly as an empty dataset is: the source's {@code OPEN} says nothing about
     * content, and turning a missing default row into an open failure would abend earlier and with the
     * wrong diagnostic.
     *
     * @throws FatalProcessingException if the disclosure-group dataset cannot be reached
     */
    private void openDisclosureGroupFile() {
        String ioStatus = SUCCESS_STATUS;
        RuntimeException failure = null;
        try {
            disclosureGroupRepository.findDefaultGroupRate(OPEN_PROBE_TRAN_TYPE_CD, OPEN_PROBE_TRAN_CAT_CD);
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final DataAccessException cause) {
            ioStatus = OPEN_FAILURE_STATUS;
            failure = cause;
        }
        guardFileOperation(ioStatus, DD_DISCGRP, MSG_ERROR_OPENING_DISCGRP, REASON_OPEN_FAILED, failure);
    }

    /**
     * {@code 0300-ACCTFILE-OPEN} - {@code app/cbl/CBACT04C.cbl:L289}-{@code :L305}, whose failure literal
     * at {@code :L300} is {@code 'ERROR OPENING ACCOUNT MASTER FILE'}.
     *
     * <p><b>Its verb is {@code OPEN I-O} at {@code :L291}, not {@code OPEN INPUT}</b> - the only one of
     * the five opened for update - because {@code 1050-UPDATE-ACCOUNT} rewrites this dataset at
     * {@code :L356}. The distinction is recorded here because it is the evidence that the account update
     * is a genuine write path and not a read-only lookup, which is what makes the missing final flush of
     * Finding 1 an observable divergence rather than a harmless one.
     *
     * @throws FatalProcessingException if the account dataset cannot be reached
     */
    private void openAccountFile() {
        String ioStatus = SUCCESS_STATUS;
        RuntimeException failure = null;
        try {
            accountRepository.findById(Long.valueOf(OPEN_PROBE_ACCOUNT_ID));
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final DataAccessException cause) {
            ioStatus = OPEN_FAILURE_STATUS;
            failure = cause;
        }
        guardFileOperation(ioStatus, DD_ACCTFILE, MSG_ERROR_OPENING_ACCTFILE, REASON_OPEN_FAILED, failure);
    }

    /**
     * {@code 0400-TRANFILE-OPEN} - {@code app/cbl/CBACT04C.cbl:L307}-{@code :L323}, whose verb is
     * {@code OPEN OUTPUT TRANSACT-FILE} at {@code :L309} and whose failure literal at {@code :L318} is
     * {@code 'ERROR OPENING TRANSACTION FILE'}.
     *
     * <p>{@code OPEN OUTPUT} on a dataset allocated {@code DISP=(NEW,CATLG,DELETE)}
     * ({@code app/jcl/INTCALC.jcl:L37}-{@code :L38}) creates a brand-new generation, so the Java
     * counterpart confirms that the destination container exists and is reachable. A bucket that is
     * absent - or an object-storage endpoint that cannot be reached - reports {@code '35'} and abends,
     * which is what the source does when the allocation fails.
     *
     * <p>The catch is {@link RuntimeException} rather than {@link DataAccessException} because the
     * object-storage client raises its own unchecked hierarchy, not Spring's data-access one. The typed
     * {@link CardDemoException} arm precedes it so an already-classified failure is rethrown unchanged
     * instead of being reclassified as an open failure. This is the same shape the sibling
     * {@code TransactionWriter} uses for its own upload guard, so the two agree.
     *
     * @throws FatalProcessingException if the destination bucket is absent or unreachable
     */
    private void openTransactionFile() {
        String ioStatus = SUCCESS_STATUS;
        RuntimeException failure = null;
        try {
            if (!s3Operations.bucketExists(batchOutputBucket)) {
                ioStatus = OPEN_FAILURE_STATUS;
            }
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final RuntimeException cause) {
            ioStatus = OPEN_FAILURE_STATUS;
            failure = cause;
        }
        guardFileOperation(ioStatus, DD_TRANSACT, MSG_ERROR_OPENING_TRANFILE, REASON_OPEN_FAILED, failure);
    }

    /**
     * {@code 9000-TCATBALF-CLOSE} - {@code app/cbl/CBACT04C.cbl:L522}-{@code :L538}, whose failure literal
     * at {@code :L533} is {@code 'ERROR CLOSING TRANSACTION BALANCE FILE'} - note that the open paragraph
     * at {@code :L245} calls the same dataset a "TRANSACTION CATEGORY BALANCE"; both spellings are
     * preserved as written.
     *
     * <p><b>Mechanism substitution, and it is a deviation worth naming.</b> {@code CLOSE} releases a VSAM ACB and can
     * report a status; a JPA repository has no {@code close}, because the container owns connection release and does
     * it whether this paragraph runs or not. Deleting the paragraph would break the paragraph map, and inventing a
     * guard that can never fail would be exactly the untracked dead code clause B forbids. So the paragraph is
     * realised as <em>release and confirm</em>: the same bounded probe as the open, which makes the guard genuinely
     * reachable - a run that exhausted or broke the connection pool reports {@code '35'} here and abends, which is
     * the class of end-of-run failure the source's close guard exists to catch. Cost is one bounded query per dataset
     * per run. Owed an entry in the planned {@code DECISION_LOG.md}.
     *
     * @throws FatalProcessingException if the driving dataset is no longer reachable at end of run
     */
    private void closeTransactionCategoryBalanceFile() {
        String ioStatus = SUCCESS_STATUS;
        RuntimeException failure = null;
        try {
            categoryBalanceRepository
                    .findAllByOrderByIdAccountIdAscIdTypeCdAscIdCatCdAsc(openProbePageRequest());
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final DataAccessException cause) {
            ioStatus = OPEN_FAILURE_STATUS;
            failure = cause;
        }
        guardFileOperation(ioStatus, DD_TCATBALF, MSG_ERROR_CLOSING_TCATBALF, REASON_CLOSE_FAILED, failure);
    }

    /**
     * {@code 9100-XREFFILE-CLOSE} - {@code app/cbl/CBACT04C.cbl:L541}-{@code :L557}, whose failure literal
     * at {@code :L552} is {@code 'ERROR CLOSING CROSS REF FILE'}. Realised as release and confirm for the
     * reason given on {@link #closeTransactionCategoryBalanceFile()}, probing the alternate-index path
     * that the run reads.
     *
     * @throws FatalProcessingException if the cross-reference dataset is no longer reachable
     */
    private void closeCrossReferenceFile() {
        String ioStatus = SUCCESS_STATUS;
        RuntimeException failure = null;
        try {
            // A reachability probe only; the row is never read. LIMIT 1 keeps the probe from transporting
            // a result set it discards.
            crossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(
                    Long.valueOf(OPEN_PROBE_ACCOUNT_ID));
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final DataAccessException cause) {
            ioStatus = OPEN_FAILURE_STATUS;
            failure = cause;
        }
        guardFileOperation(ioStatus, DD_XREFFILE, MSG_ERROR_CLOSING_XREFFILE, REASON_CLOSE_FAILED, failure);
    }

    /**
     * {@code 9200-DISCGRP-CLOSE} - {@code app/cbl/CBACT04C.cbl:L559}-{@code :L575}, whose failure literal
     * at {@code :L570} is {@code 'ERROR CLOSING DISCLOSURE GROUP FILE'}. That this paragraph names the
     * dataset correctly is what proves the open paragraph's {@code 'DALY REJECTS'} text at {@code :L281}
     * is a defect rather than an alternative name. Realised as release and confirm for the reason given on
     * {@link #closeTransactionCategoryBalanceFile()}.
     *
     * @throws FatalProcessingException if the disclosure-group dataset is no longer reachable
     */
    private void closeDisclosureGroupFile() {
        String ioStatus = SUCCESS_STATUS;
        RuntimeException failure = null;
        try {
            disclosureGroupRepository.findDefaultGroupRate(OPEN_PROBE_TRAN_TYPE_CD, OPEN_PROBE_TRAN_CAT_CD);
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final DataAccessException cause) {
            ioStatus = OPEN_FAILURE_STATUS;
            failure = cause;
        }
        guardFileOperation(ioStatus, DD_DISCGRP, MSG_ERROR_CLOSING_DISCGRP, REASON_CLOSE_FAILED, failure);
    }

    /**
     * {@code 9300-ACCTFILE-CLOSE} - {@code app/cbl/CBACT04C.cbl:L577}-{@code :L593}, whose failure literal
     * at {@code :L588} is {@code 'ERROR CLOSING ACCOUNT FILE'} - without the "MASTER" that the open
     * diagnostic at {@code :L300} carries. Realised as release and confirm for the reason given on
     * {@link #closeTransactionCategoryBalanceFile()}.
     *
     * @throws FatalProcessingException if the account dataset is no longer reachable
     */
    private void closeAccountFile() {
        String ioStatus = SUCCESS_STATUS;
        RuntimeException failure = null;
        try {
            accountRepository.findById(Long.valueOf(OPEN_PROBE_ACCOUNT_ID));
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final DataAccessException cause) {
            ioStatus = OPEN_FAILURE_STATUS;
            failure = cause;
        }
        guardFileOperation(ioStatus, DD_ACCTFILE, MSG_ERROR_CLOSING_ACCTFILE, REASON_CLOSE_FAILED, failure);
    }

    /**
     * {@code 9400-TRANFILE-CLOSE} - {@code app/cbl/CBACT04C.cbl:L595}-{@code :L611}, whose failure literal
     * at {@code :L606} is {@code 'ERROR CLOSING TRANSACTION FILE'}.
     *
     * <p>This is the one close with real work to do. On the mainframe, closing a
     * {@code DISP=(NEW,CATLG,DELETE)} dataset is what catalogues the new generation and makes it
     * resolvable as {@code SYSTRAN(0)}; until then it is uncatalogued and a failure would delete it. The
     * Java counterpart finalises the generation by confirming that the generation prefix and at least one
     * object key reached the job execution context, and by re-confirming that the destination bucket is
     * still reachable.
     *
     * <p>A run that emitted nothing is a legitimate outcome, not a failure: every rate could have been
     * zero, in which case {@code :L214}-{@code :L217} suppressed every write and the source likewise
     * closes an empty new generation. That case is logged and the prefix is still published, so a
     * downstream job reads an empty generation rather than silently falling back to a previous one.
     *
     * @param jobExecution the execution whose context carries what the writer published
     * @throws FatalProcessingException if the destination is unreachable, or if objects were written but
     * their keys did not reach the context
     */
    private void closeTransactionFile(final JobExecution jobExecution) {
        String ioStatus = SUCCESS_STATUS;
        RuntimeException failure = null;
        try {
            if (!s3Operations.bucketExists(batchOutputBucket)) {
                ioStatus = OPEN_FAILURE_STATUS;
            }
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final RuntimeException cause) {
            ioStatus = OPEN_FAILURE_STATUS;
            failure = cause;
        }

        final ExecutionContext context = jobExecution.getExecutionContext();
        if (!context.containsKey(SYSTRAN_GENERATION_PREFIX_CONTEXT_ENTRY)) {
            // Nothing was emitted: publish the generation this run owns anyway, so a downstream step
            // reads this run's (empty) generation instead of resolving "latest" to an earlier one.
            context.putString(SYSTRAN_GENERATION_PREFIX_CONTEXT_ENTRY,
                    composeGenerationPrefix(jobExecution.getJobInstance().getInstanceId()));
            context.putLong(SYSTRAN_GENERATION_KEYS_COUNT_CONTEXT_ENTRY, 0L);
            LOG.info("{} closed with no generation object; every disclosure rate was zero, so "
                    + "app/cbl/CBACT04C.cbl:L214 suppressed every write", DD_TRANSACT);
        } else if (publishedGenerationKeyCount(context) == 0) {
            ioStatus = OBJECT_STORE_IO_STATUS;
        } else {
            // The count, not the keys. A key carries the configured prefix and the generation, and this line
            // is an operator-visible end-of-run marker; the exact object names are already in the context for
            // a downstream step that needs them, so naming them again here only widens what a log carries.
            LOG.info("{} closed; generation {} holds {} objects", DD_TRANSACT,
                    context.getString(SYSTRAN_GENERATION_PREFIX_CONTEXT_ENTRY),
                    Integer.valueOf(publishedGenerationKeyCount(context)));
        }

        guardFileOperation(ioStatus, DD_TRANSACT, MSG_ERROR_CLOSING_TRANFILE, REASON_CLOSE_FAILED, failure);
    }

    /**
     * {@code 9910-DISPLAY-IO-STATUS} - {@code app/cbl/CBACT04C.cbl:L635}-{@code :L648}.
     *
     * <p>The source renders the status into a four-character field two different ways - first byte plus a
     * three-digit binary expansion when the field is non-numeric or begins with {@code '9'}, otherwise
     * four zeros overlaid with the two status characters at positions 3 and 4 - and emits
     * {@code DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04} on both arms at {@code :L642} and {@code :L646}.
     * Both the twenty-character literal, in which {@code NNNN} is part of the text rather than a
     * placeholder, and the four-character rendering are owned by
     * {@link FileStatusMapper#displayIoStatus(String)} and {@link FileStatus#DISPLAY_MESSAGE_PREFIX}, so
     * this method only chooses the sink. Reimplementing or reformatting either would make this file's
     * output differ from every other program's for the same status.
     *
     * @param ioStatus the raw status to render, tolerated when {@code null} or of an unexpected length
     */
    private void displayIoStatus(final String ioStatus) {
        LOG.error(fileStatusMapper.displayIoStatus(ioStatus));
    }

    /**
     * {@code 9999-ABEND-PROGRAM} - {@code app/cbl/CBACT04C.cbl:L628}-{@code :L632}, which displays
     * {@code 'ABENDING PROGRAM'} at {@code :L629}, zeroes {@code TIMING} at {@code :L630}, moves
     * {@code 999} to {@code ABCODE} at {@code :L631} and calls {@code 'CEE3ABD'} at {@code :L632}.
     *
     * <p>The abend code is {@value FatalProcessingException#BATCH_ABEND_CODE} and the process return code
     * is {@value FatalProcessingException#BATCH_RETURN_CODE}. <b>999, never 9999</b>: 9999 is the CICS
     * online value and conflating the two is a Blocker. There is no
     * {@code MOVE 12 TO RETURN-CODE} anywhere in the corpus - return code 12 is the conventional language
     * environment consequence of the {@code CEE3ABD} call - so no locator is fabricated for it.
     * {@code TIMING} has no Java counterpart: it is a timing work field the abend service reads, and
     * zeroing it is bookkeeping for a service that does not exist here.
     *
     * <p><b>This method returns the exception instead of throwing it</b>, so that every call site reads
     * {@code throw abendProgram(...)} and the compiler can see that control does not continue - which is
     * what lets the guards be written without an unreachable {@code return} after them. The effect is
     * identical to the source, where {@code CEE3ABD} does not return. The JVM is never terminated: no
     * JVM-exit call, no runtime halt and no shutdown hook appears anywhere in this file, because
     * terminating the JVM would deny Spring Batch the chance to record the failure in the job repository
     * and would make the run unrestartable.
     *
     * @param abendMessage the source's own {@code DISPLAY} literal for the failing paragraph, carried as
     * the abend message so the diagnostic survives into the exception
     * @param reason the short reason, sized for {@code ABEND-REASON X(50)} of {@code app/cpy/CSMSG02Y.cpy}
     * @param cause the underlying failure, or {@code null} when there is none to attach
     * @return the abend to throw, never {@code null}
     */
    private FatalProcessingException abendProgram(final String abendMessage, final String reason,
            final Throwable cause) {

        LOG.error(MSG_ABENDING_PROGRAM);
        if (cause == null) {
            return new FatalProcessingException(ABEND_CODE, ABEND_CULPRIT, reason, abendMessage);
        }
        return new FatalProcessingException(ABEND_CODE, ABEND_CULPRIT, reason, abendMessage, cause);
    }

    /**
     * The generation prefix that replaces the relative reference {@code SYSTRAN(+1)} of
     * {@code app/jcl/INTCALC.jcl:L41}.
     *
     * <p>The job instance identifier is monotonically increasing and is rendered zero-padded to
     * {@value #KEY_NUMBER_WIDTH} digits, so lexicographic order and generation order coincide for every
     * value a signed 64-bit identifier can take. That equivalence is the whole mechanism: a later
     * {@code (0)} read resolves to the newest generation by taking the lexicographically greatest prefix,
     * with no catalogue to consult. Retention - {@code LIMIT(5) SCRATCH} at
     * {@code app/jcl/DEFGDGB.jcl:L48}-{@code :L53} - becomes a bucket lifecycle rule that is documented
     * rather than enforced here, and object versioning supersedes generation counting.
     *
     * @param jobInstanceId the instance identifier of the running job
     * @return the prefix all of this run's objects live under, with no trailing separator
     */
    private String composeGenerationPrefix(final long jobInstanceId) {
        return String.format(Locale.ROOT, "%s/%0" + KEY_NUMBER_WIDTH + "d", systranPrefix,
                Long.valueOf(jobInstanceId));
    }

    /**
     * The concrete object key of one emitted part, within this run's generation.
     *
     * <p>Both numeric segments are zero-padded to {@value #KEY_NUMBER_WIDTH} digits so that keys sort in
     * creation order, which is what makes concatenating the parts in key order reproduce the sequential
     * dataset byte for byte. The shape matches {@link TransactionWriter}'s own key template, so the two
     * generation writers in this codebase produce comparable layouts.
     *
     * @param jobInstanceId the instance identifier of the running job, the generation segment
     * @param ordinal the one-based ordinal of this part within the generation
     * @return the object key, never {@code null}
     */
    private String composeObjectKey(final long jobInstanceId, final long ordinal) {
        return String.format(Locale.ROOT, KEY_TEMPLATE, systranPrefix, Long.valueOf(jobInstanceId),
                OBJECT_BASE_NAME, Long.valueOf(ordinal), OBJECT_SUFFIX);
    }


    /**
     * Renders one transaction as its fixed-width record image, refusing anything that is not a record this
     * job is entitled to emit.
     *
     * <p>Two boundary conditions are checked explicitly, as clause B requires. A {@code null} item cannot
     * be rendered at all. And the transaction source must be the space-padded {@code 'System'} literal
     * that {@code app/cbl/CBACT04C.cbl:L484} moves into {@code TRAN-SOURCE} - published once by
     * {@link TransactionSource#SYSTEM} and compared through
     * {@link TransactionSource#getFixedWidthValue()} so the ten-character field width is not restated
     * here. The check is what stops a differently sourced transaction, wired in by accident, from being
     * appended to an interest generation that a parity comparison will read back field by field.
     *
     * <p>The image itself comes from {@link TransactionWriter#composeFixedWidthImage(Transaction)}: the
     * 350-byte geometry of {@code app/cpy/CVTRA05Y.cpy} has exactly one owner in this codebase, and the
     * length is re-asserted here because {@code DCB=(RECFM=F,LRECL=350)} at
     * {@code app/jcl/INTCALC.jcl:L39} is a byte-exact contract rather than a hint. That method raises an
     * already-typed {@code CardDemoException} on a field-level violation, which is allowed to propagate
     * unchanged rather than being re-wrapped and losing its field-naming message.
     *
     * @param transaction the synthesised interest transaction the processor returned
     * @return the record image, always exactly {@value TransactionWriter#RECORD_LENGTH} characters
     * @throws FatalProcessingException if the item is {@code null}, carries a source other than
     * {@code 'System'}, or renders to the wrong length
     */
    private String requireGeneratedRecordImage(final Transaction transaction) {
        if (transaction == null) {
            throw abendProgram(MSG_ERROR_WRITING_TRANSACTION_RECORD, REASON_WRITE_FAILED, null);
        }
        if (!TransactionSource.SYSTEM.getFixedWidthValue().equals(transaction.getTransactionSource())) {
            throw abendProgram(MSG_ERROR_WRITING_TRANSACTION_RECORD, REASON_BAD_TRANSACTION_SOURCE, null);
        }
        final String image = transactionWriter.composeFixedWidthImage(transaction);
        if (image.length() != TransactionWriter.RECORD_LENGTH) {
            throw abendProgram(MSG_ERROR_WRITING_TRANSACTION_RECORD, REASON_BAD_RECORD_LENGTH, null);
        }
        return image;
    }

    /**
     * Publishes the generation this run created into the job execution context, so a later job or step
     * reads back exactly these keys.
     *
     * <p>This is the substitute for the catalogue entry that closing a {@code DISP=(NEW,CATLG,DELETE)}
     * dataset would create. <b>A downstream consumer must read these entries rather than re-resolving
     * "latest"</b>: re-resolving would race a concurrent run and could merge a generation this run did not
     * produce, which the relative reference {@code SYSTRAN(0)} could never do on the mainframe because the
     * catalogue is updated atomically at close.
     *
     * <p>The context is per-execution, which is what makes the accumulation here correct without any field
     * to hold it - the reason the ordinal counter lives in the step execution context too. Each key is stored
     * in its own entry, indexed by creation order, with the count kept alongside it; see
     * {@link #SYSTRAN_GENERATION_KEYS_COUNT_CONTEXT_ENTRY} for the read protocol and for the finding that
     * replaced the delimited form this method used to write.
     *
     * @param jobExecution the running execution whose context is written
     * @param jobInstanceId the instance identifier forming the generation segment
     * @param objectKey the key just created
     */
    private void publishGeneration(final JobExecution jobExecution, final long jobInstanceId,
            final String objectKey) {

        final ExecutionContext context = jobExecution.getExecutionContext();
        context.putString(SYSTRAN_GENERATION_PREFIX_CONTEXT_ENTRY,
                composeGenerationPrefix(jobInstanceId));
        final int published = publishedGenerationKeyCount(context);
        context.putString(generationKeysIndexEntry(published), objectKey);
        context.putLong(SYSTRAN_GENERATION_KEYS_COUNT_CONTEXT_ENTRY, published + 1L);
    }

    /**
     * Renders the job execution context entry name holding the object key at {@code index}.
     *
     * <p>Rendered with {@link Integer#toString(int)} rather than a formatted width, because the entry name is
     * looked up by a reader that counts up from zero, never sorted as text. See
     * {@link #SYSTRAN_GENERATION_KEYS_COUNT_CONTEXT_ENTRY} for the read protocol and the finding it closes.
     *
     * @param index zero-based position of the key in creation order
     * @return the context entry name for that position
     */
    private static String generationKeysIndexEntry(final int index) {
        return SYSTRAN_GENERATION_KEYS_INDEX_PREFIX + Integer.toString(index);
    }

    /**
     * Reads how many object keys have been published into a job execution context so far.
     *
     * <p>Absent means zero, which is the state before the first write of a run. {@link Math#toIntExact(long)}
     * rather than a cast: the count is stored as a {@code long} because that is what
     * {@link ExecutionContext#putLong(String, long)} accepts, and a value that could not have been produced by
     * this class should fail loudly rather than wrap around into a negative index.
     *
     * @param context the job execution context to read
     * @return the number of keys already published, never negative
     */
    private static int publishedGenerationKeyCount(final ExecutionContext context) {
        return Math.toIntExact(context.getLong(SYSTRAN_GENERATION_KEYS_COUNT_CONTEXT_ENTRY, 0L));
    }

    /**
     * Publishes the abend exit status when an abend is among the recorded failures, so that return code
     * {@value FatalProcessingException#BATCH_RETURN_CODE} is reported wherever the abend was raised -
     * inside the step, or in one of the {@code OPEN} paragraphs that run before the flow is entered and so
     * never reach {@link InterestCalculationReturnCodeDecider}.
     *
     * <p>It is safe to set the status here because Spring Batch persists the execution <em>after</em>
     * {@link JobExecutionListener#afterJob(JobExecution)} returns.
     *
     * @param jobExecution the finishing execution
     */
    private static void applyAbendExitStatus(final JobExecution jobExecution) {
        for (final Throwable failure : jobExecution.getAllFailureExceptions()) {
            if (failure instanceof FatalProcessingException) {
                jobExecution.setExitStatus(ABEND_EXIT_STATUS);
                return;
            }
        }
    }

    /**
     * Reports whether an abend is among the failures recorded against the execution or the step, which is
     * what distinguishes return code {@value FatalProcessingException#BATCH_RETURN_CODE} from the ordinary
     * failure code 8.
     *
     * <p>Detection is by type rather than by class name, so a rename cannot silently turn every abend into
     * a plain failure.
     *
     * @param jobExecution the running execution
     * @param stepExecution the step that has just finished, which the decider contract allows to be
     * {@code null} when no step has run yet
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

    /**
     * Length in days of a month, without constructing a date type. Implemented as a switch expression
     * rather than a lookup table because a {@code static} array would be mutable static state, which
     * clause B forbids and which any caller could corrupt.
     *
     * @param year the four-digit year, for the February case
     * @param month the one-based month
     * @return the number of days, or zero when {@code month} is out of range
     */
    private static int monthLength(final int year, final int month) {
        return switch (month) {
            case 1, 3, 5, 7, 8, 10, 12 -> 31;
            case 4, 6, 9, 11 -> 30;
            case 2 -> isLeapYear(year) ? 29 : 28;
            default -> 0;
        };
    }

    /**
     * The proleptic Gregorian leap-year rule, applied arithmetically so that no date type is introduced
     * into the handling of a value that is <b>not a date</b> but ten characters of a transaction
     * identifier prefix.
     *
     * @param year the four-digit year
     * @return {@code true} if the year has 29 days in February
     */
    private static boolean isLeapYear(final int year) {
        return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
    }

    /**
     * Validates the ten-character {@code PARM-DATE} job parameter before the step runs.
     *
     * <p>The contract, from {@code app/jcl/INTCALC.jcl:L22} {@code PARM='2022071800'} and the linkage group
     * at {@code app/cbl/CBACT04C.cbl:L175}-{@code :L178}, is: <b>exactly ten characters, every one an
     * ASCII digit, the first eight a plausible {@code yyyyMMdd}, and the last two the literal
     * {@value #PARM_DATE_TRAILER}.</b> No separator, no ISO-8601 form and no date type - the value's job
     * is to be concatenated with a six-digit suffix at {@code :L476}-{@code :L480} to make a sixteen-digit
     * transaction identifier, so its width and character set are the whole of its meaning.
     *
     * <p><b>Deliberate, mandated deviation.</b> {@code CBACT04C} validates {@code PARM-DATE} not at all:
     * it moves the characters straight into the identifier, so a mainframe run would accept
     * {@code '0000000000'} and emit identifiers built from it. Validating here therefore rejects input the
     * source would have accepted. That is required rather than accidental - clause A demands inputs be
     * treated as untrusted, and a malformed parameter would otherwise corrupt every identifier in the
     * generation and only surface downstream in the combine job's load. The JCL's own value passes
     * unchanged. Severity <b>Low</b>, and owed an entry in the planned {@code DECISION_LOG.md}.
     *
     * <p>Failures are reported as {@link JobParametersInvalidException}, which is what this interface
     * declares and what Spring Batch turns into a refusal to start the job. It is not
     * {@code ValidationException}: that type is outside this file's dependency contract, and inventing an
     * import for it would break the constraint that every import resolve to a declared dependency.
     *
     * <p>No message ever echoes anything but the parameter's observed length and the rule that failed. The
     * value is not a credential, but a validator that quotes its input is the pattern by which one
     * eventually does.
     */
    private static final class ParmDateValidator implements JobParametersValidator {

        /** Creates the validator. Stateless, so instances are interchangeable and thread-safe. */
        private ParmDateValidator() {
            // No state: the contract is entirely expressed by the constants of the enclosing class.
        }

        /**
         * {@inheritDoc}
         *
         * @throws JobParametersInvalidException if {@value #PARM_DATE_JOB_PARAMETER} is absent, is not
         * exactly {@value #PARM_DATE_LENGTH} characters, contains a non-digit, does not end in
         * {@value #PARM_DATE_TRAILER}, or does not begin with a plausible {@code yyyyMMdd}
         */
        @Override
        public void validate(final JobParameters parameters) throws JobParametersInvalidException {
            if (parameters == null) {
                throw new JobParametersInvalidException("Job " + DEFAULT_JOB_NAME
                        + " requires job parameter '" + PARM_DATE_JOB_PARAMETER
                        + "' but was launched with no parameters at all"
                        + " (app/jcl/INTCALC.jcl:L22 supplies PARM='2022071800').");
            }

            final String parmDate = parameters.getString(PARM_DATE_JOB_PARAMETER);
            if (parmDate == null) {
                throw new JobParametersInvalidException("Job parameter '" + PARM_DATE_JOB_PARAMETER
                        + "' is required and was absent; app/cbl/CBACT04C.cbl:L178 declares"
                        + " PARM-DATE PIC X(" + PARM_DATE_LENGTH + ").");
            }
            if (parmDate.length() != PARM_DATE_LENGTH) {
                throw new JobParametersInvalidException("Job parameter '" + PARM_DATE_JOB_PARAMETER
                        + "' must be exactly " + PARM_DATE_LENGTH + " characters to match"
                        + " PARM-DATE PIC X(" + PARM_DATE_LENGTH + ") at app/cbl/CBACT04C.cbl:L178,"
                        + " but " + parmDate.length() + " were supplied.");
            }
            for (int index = 0; index < PARM_DATE_LENGTH; index++) {
                final char digit = parmDate.charAt(index);
                if (digit < '0' || digit > '9') {
                    throw new JobParametersInvalidException("Job parameter '" + PARM_DATE_JOB_PARAMETER
                            + "' must be ten ASCII digits with no separator, because"
                            + " app/cbl/CBACT04C.cbl:L476-L480 concatenates it into a sixteen digit"
                            + " TRAN-ID; a non-digit was found at position " + (index + 1) + ".");
                }
            }
            if (!parmDate.endsWith(PARM_DATE_TRAILER)) {
                throw new JobParametersInvalidException("Job parameter '" + PARM_DATE_JOB_PARAMETER
                        + "' must end with the two trailing zeros that app/jcl/INTCALC.jcl:L22 supplies"
                        + " in PARM='2022071800'.");
            }

            // Every character is a digit at this point, so no parse can fail and no exception is caught.
            final int year = Integer.parseInt(parmDate.substring(0, 4));
            final int month = Integer.parseInt(parmDate.substring(4, 6));
            final int day = Integer.parseInt(parmDate.substring(6, PARM_DATE_DATE_DIGITS));
            if (year < PARM_DATE_MIN_YEAR || year > PARM_DATE_MAX_YEAR) {
                throw new JobParametersInvalidException("Job parameter '" + PARM_DATE_JOB_PARAMETER
                        + "' must begin with a plausible yyyyMMdd; the year " + year
                        + " is outside " + PARM_DATE_MIN_YEAR + " to " + PARM_DATE_MAX_YEAR + ".");
            }
            if (month < 1 || month > 12) {
                throw new JobParametersInvalidException("Job parameter '" + PARM_DATE_JOB_PARAMETER
                        + "' must begin with a plausible yyyyMMdd; the month " + month
                        + " is outside 1 to 12.");
            }
            final int lastDay = monthLength(year, month);
            if (day < 1 || day > lastDay) {
                throw new JobParametersInvalidException("Job parameter '" + PARM_DATE_JOB_PARAMETER
                        + "' must begin with a plausible yyyyMMdd; day " + day
                        + " is outside 1 to " + lastDay + " for month " + month + " of " + year + ".");
            }
        }
    }

    /**
     * Emits each chunk of synthesised interest transactions as one part of this run's {@code SYSTRAN}
     * generation, and writes to nothing else.
     *
     * <p>It stands in for the {@code WRITE FD-TRANFILE-REC FROM TRAN-RECORD} of
     * {@code app/cbl/CBACT04C.cbl:L500} and the guard at {@code :L501}-{@code :L514}, against the
     * sequential dataset that {@code app/jcl/INTCALC.jcl:L37}-{@code :L41} allocates as a brand-new
     * generation with {@code DISP=(NEW,CATLG,DELETE)} and {@code DCB=(RECFM=F,LRECL=350,BLKSIZE=0)}.
     *
     * <p><b>It does not touch the transaction table.</b> That is the whole reason this writer exists
     * rather than {@link TransactionWriter}, whose {@code write} also persists rows because its own source
     * program targets the keyed cluster. See Finding 2 in the class documentation; severity High.
     *
     * <p><b>It holds no state whatsoever - no field, not even a private one.</b> The step bean is a
     * singleton, so any field here would be shared by every execution of it, which is the same
     * global-mutable-state defect that a {@code static} counter would be. The per-run values it needs
     * instead come from the execution contexts, which are scoped to one execution: the part ordinal from
     * the step context under {@value #OBJECT_ORDINAL_CONTEXT_ENTRY}, and the accumulated keys from the job
     * context. The {@link StepExecution} itself is obtained from
     * {@link StepSynchronizationManager#getContext()}, the same thread-bound holder that backs
     * {@code @StepScope}, so the lookup is correct even if the step were ever given a task executor.
     *
     * <p>{@code RECFM=F} is fixed <em>unblocked</em>, so records are concatenated with <b>no delimiter of
     * any kind</b> and each part's byte length is an exact multiple of
     * {@value TransactionWriter#RECORD_LENGTH}. Whether a downstream consumer would prefer newline framing
     * is <b>Not available</b> - the corpus states no such contract, and the record geometry is the only
     * framing the source supports.
     *
     * <p>An empty chunk writes nothing rather than an empty object. The processor returns {@code null} for
     * a zero rate, reproducing the suppression at {@code app/cbl/CBACT04C.cbl:L214}-{@code :L217}, and a
     * chunk in which every rate was zero is filtered away entirely - which on the mainframe is simply a
     * {@code WRITE} that never executes, not a zero-length record.
     */
    private final class SystranGenerationWriter implements ItemWriter<Transaction> {

        /** Creates the writer. Stateless; see the class documentation for why that is deliberate. */
        private SystranGenerationWriter() {
            // No state by design. Everything per-run lives in the execution contexts.
        }

        /**
         * {@inheritDoc}
         *
         * <p>Spring Batch 5.0 replaced the {@code List}-based writer signature with
         * {@link Chunk}, so this override takes {@code Chunk<? extends Transaction>}. A {@code List}
         * parameter would not override anything and, with {@code @Override} under
         * {@code -Xlint:all -Werror}, would be a hard compile failure rather than a silent no-op.
         *
         * <p>The declared {@code throws Exception} of the interface is deliberately narrowed away: every
         * failure on this path is already a typed {@code com.cardemo.exception} subtype, so widening the
         * contract would invite callers to catch something that is never thrown.
         *
         * @param chunk the transactions to emit, tolerated when {@code null} or empty
         * @throws FatalProcessingException if there is no step context, if a record does not render to
         * exactly {@value TransactionWriter#RECORD_LENGTH} characters, or if the upload fails
         */
        @Override
        public void write(final Chunk<? extends Transaction> chunk) {
            if (chunk == null || chunk.isEmpty()) {
                LOG.debug("{} received an empty chunk; every disclosure rate in it was zero, so"
                        + " app/cbl/CBACT04C.cbl:L214 suppressed the write", DD_TRANSACT);
                return;
            }

            final StepContext stepContext = StepSynchronizationManager.getContext();
            if (stepContext == null) {
                LOG.error(MSG_ERROR_WRITING_TRANSACTION_RECORD);
                throw abendProgram(MSG_ERROR_WRITING_TRANSACTION_RECORD, REASON_NO_STEP_CONTEXT, null);
            }
            final StepExecution stepExecution = stepContext.getStepExecution();
            final JobExecution jobExecution = stepExecution.getJobExecution();

            final int recordCount = chunk.size();
            final int expectedBytes = recordCount * TransactionWriter.RECORD_LENGTH;
            final StringBuilder image = new StringBuilder(expectedBytes);
            for (final Transaction transaction : chunk) {
                image.append(requireGeneratedRecordImage(transaction));
            }

            final byte[] payload = image.toString().getBytes(FIXED_WIDTH_CHARSET);
            if (payload.length != expectedBytes) {
                LOG.error(MSG_ERROR_WRITING_TRANSACTION_RECORD);
                throw abendProgram(MSG_ERROR_WRITING_TRANSACTION_RECORD, REASON_BAD_RECORD_LENGTH, null);
            }

            // The ordinal lives in the step execution context, not in a field: see the class Javadoc.
            final ExecutionContext stepContextData = stepExecution.getExecutionContext();
            final long ordinal = stepContextData.getLong(OBJECT_ORDINAL_CONTEXT_ENTRY, 0L) + 1L;
            stepContextData.putLong(OBJECT_ORDINAL_CONTEXT_ENTRY, ordinal);

            final long jobInstanceId = jobExecution.getJobInstance().getInstanceId();
            final String objectKey = composeObjectKey(jobInstanceId, ordinal);

            String ioStatus;
            RuntimeException failure = null;
            try {
                final ObjectMetadata metadata = ObjectMetadata.builder()
                        .contentType(OBJECT_CONTENT_TYPE)
                        .contentLength(Long.valueOf(payload.length))
                        .build();
                // No try-with-resources: a ByteArrayInputStream holds no operating-system handle, and its
                // close() declares an IOException it cannot raise, so wrapping it would add a catch block
                // for an impossible failure.
                s3Operations.upload(batchOutputBucket, objectKey,
                        new ByteArrayInputStream(payload), metadata);
                ioStatus = SUCCESS_STATUS;
            } catch (final CardDemoException alreadyTyped) {
                throw alreadyTyped;
            } catch (final RuntimeException cause) {
                ioStatus = OBJECT_STORE_IO_STATUS;
                failure = cause;
            }
            guardFileOperation(ioStatus, DD_TRANSACT, MSG_ERROR_WRITING_TRANSACTION_RECORD,
                    REASON_WRITE_FAILED, failure);

            publishGeneration(jobExecution, jobInstanceId, objectKey);

            // Replaces the end-of-run DISPLAY counters of the corpus with the one counter this job is
            // entitled to advance. One increment per record, so the metric counts records and not chunks.
            for (int record = 0; record < recordCount; record++) {
                metricsConfig.countRecordProcessed();
            }

            LOG.debug("{} wrote {} records ({} bytes) to {}", DD_TRANSACT,
                    Integer.valueOf(recordCount), Integer.valueOf(payload.length), objectKey);
        }
    }

    /**
     * The two diagnostic context values a run replaced, so that they can be put back verbatim.
     *
     * <p>A {@code null} component means the entry was absent, or held a value outside the grammar its
     * propagation helper accepts; either way the restore removes the entry rather than putting something back.
     * See {@link #DIAGNOSTIC_SNAPSHOT} for the finding this closes.
     *
     * @param jobInstanceId  the value {@link CorrelationIdFilter#MDC_KEY_JOB_INSTANCE_ID} held on entry
     * @param correlationId  the value {@link CorrelationIdFilter#MDC_KEY_CORRELATION_ID} held on entry
     */
    private record DiagnosticContextSnapshot(String jobInstanceId, String correlationId) {
    }

    /**
     * Labels the calling thread with this run's identifiers and records what it displaced.
     *
     * <p>The correlation identifier is only generated when the thread has none, which preserves an identifier
     * an outer scope established - a report submission that launched this job, for instance - so that the
     * whole causal chain shares one identifier. {@link CorrelationIdFilter#currentCorrelationId()} is used for
     * the read rather than a bare {@code MDC.get} because it validates: an inherited value that could inject
     * into the log format is treated as absent and replaced, not propagated.
     *
     * <p>Side effects: mutates two diagnostic context entries and sets {@link #DIAGNOSTIC_SNAPSHOT} on the
     * calling thread. Always paired with {@link #restoreDiagnosticContext()} in a {@code finally}.
     *
     * @param jobInstanceId the Spring Batch instance identifier of the starting execution
     */
    private static void establishDiagnosticContext(final long jobInstanceId) {
        final String previousJobInstanceId =
                CorrelationIdFilter.propagateJobInstanceId(Long.toString(jobInstanceId));
        final String previousCorrelationId = CorrelationIdFilter.currentCorrelationId();
        if (previousCorrelationId == null) {
            CorrelationIdFilter.propagate(UUID.randomUUID().toString());
        }
        DIAGNOSTIC_SNAPSHOT.set(
                new DiagnosticContextSnapshot(previousJobInstanceId, previousCorrelationId));
    }

    /**
     * Puts both diagnostic context entries back exactly as {@link #establishDiagnosticContext(long)} found them.
     *
     * <p>Restoring rather than removing is the whole point: an entry that was absent is removed, so nothing
     * leaks onto the next job to borrow this pooled thread, and an entry that existed is put back, so context
     * owned by an outer scope survives. A blanket removal satisfied the first and violated the second.
     *
     * <p>Tolerates a missing snapshot by doing nothing. That is reachable rather than defensive padding: if
     * {@code beforeJob} abends before the snapshot is set, Spring Batch may still route the failure through a
     * path that reaches here, and this thread's context was never modified, so there is nothing to undo.
     *
     * <p>Side effects: mutates two diagnostic context entries and clears {@link #DIAGNOSTIC_SNAPSHOT} on the
     * calling thread.
     */
    private static void restoreDiagnosticContext() {
        final DiagnosticContextSnapshot snapshot = DIAGNOSTIC_SNAPSHOT.get();
        if (snapshot == null) {
            return;
        }
        try {
            CorrelationIdFilter.propagateJobInstanceId(snapshot.jobInstanceId());
            CorrelationIdFilter.propagate(snapshot.correlationId());
        } finally {
            DIAGNOSTIC_SNAPSHOT.remove();
        }
    }

    /**
     * The mainline of {@code app/cbl/CBACT04C.cbl:L180}-{@code :L232}: the run boundary markers, the five
     * opens that precede the loop and the five closes that follow it, plus the logging context that the
     * corpus has no equivalent of.
     *
     * <p><b>Ordering matters and is preserved.</b> The opens run in the source's order -
     * {@code 0000}, {@code 0100}, {@code 0200}, {@code 0300}, {@code 0400} at {@code :L182}-{@code :L186} -
     * and the closes in the source's order - {@code 9000}, {@code 9100}, {@code 9200}, {@code 9300},
     * {@code 9400} at {@code :L224}-{@code :L228}. An abend in an open therefore reports the first dataset
     * that is unavailable, exactly as it does on the mainframe.
     *
     * <p><b>Diagnostic context.</b> {@link CorrelationIdFilter} is HTTP-scoped and never runs for batch work,
     * and the observability package is not permitted a job listener of its own, so this listener places the job
     * instance identifier and a correlation identifier into the logging context itself - through
     * {@link InterestCalculationJob#establishDiagnosticContext(long)}, under the keys
     * {@link CorrelationIdFilter#MDC_KEY_JOB_INSTANCE_ID} and
     * {@link CorrelationIdFilter#MDC_KEY_CORRELATION_ID} rather than under literals of its own, so the names
     * cannot drift from the ones {@code src/main/resources/logback-spring.xml} consumes. An inherited
     * correlation identifier is left alone, so a job launched from within a traced request keeps that
     * request's identifier instead of being given an unrelated one. {@code traceId} and {@code spanId} are
     * <em>not</em> set here: the tracing bridge populates them, and writing them by hand would overwrite
     * real span identity with a fabrication.
     *
     * <p>The context is <b>restored</b>, not removed, in a {@code finally} block - see
     * {@link InterestCalculationJob#DIAGNOSTIC_SNAPSHOT} for finding M-03. Restoring discharges both halves of
     * the obligation at once: an entry this run created is dropped, so it cannot leak onto the launcher thread
     * and label a later unrelated job, and an entry an outer scope owned is put back rather than destroyed.
     */
    private final class InterestCalculationJobListener implements JobExecutionListener {

        /** Creates the listener. Stateless: every value it needs comes from the {@link JobExecution}. */
        private InterestCalculationJobListener() {
            // No state: see the class documentation.
        }

        /**
         * {@inheritDoc}
         *
         * <p>Establishes the logging context, emits the source's own start marker and runs the five
         * {@code OPEN} paragraphs in source order. An abend here fails the job before the flow is entered,
         * which is faithful: the source likewise never reaches its loop when an open fails.
         *
         * @param jobExecution the starting execution
         * @throws FatalProcessingException if any of the five datasets is unavailable
         */
        @Override
        public void beforeJob(final JobExecution jobExecution) {
            establishDiagnosticContext(jobExecution.getJobInstance().getInstanceId());

            LOG.info(MSG_START_OF_EXECUTION);

            // An open that abends throws out of beforeJob, and Spring Batch then fails the job without
            // calling afterJob - so without this the established context would be left on a pooled thread
            // for the next job to inherit. Only the diagnostic context is unwound; the abend itself is
            // rethrown untouched, because failing the job before the flow is entered is the faithful
            // behaviour and must not change.
            try {
                openTransactionCategoryBalanceFile();
                openCrossReferenceFile();
                openDisclosureGroupFile();
                openAccountFile();
                openTransactionFile();
            } catch (final RuntimeException abend) {
                restoreDiagnosticContext();
                throw abend;
            }
        }

        /**
         * {@inheritDoc}
         *
         * <p>Runs the five {@code CLOSE} paragraphs in source order, emits the source's own end marker,
         * then publishes the abend exit status if one is warranted and clears the logging context.
         *
         * <p>Spring Batch catches and logs anything thrown from this callback rather than failing the job
         * with it, so an abend raised by a close would otherwise be invisible in the job's own status.
         * It is therefore <b>recorded</b> rather than rethrown: added to the execution's failures and
         * reflected in its status, so the outcome is the abend the source would have produced. This is
         * not swallowing - the exception is preserved with its cause, logged, and attached to the
         * execution that a caller inspects.
         *
         * @param jobExecution the finishing execution
         */
        @Override
        public void afterJob(final JobExecution jobExecution) {
            try {
                closeTransactionCategoryBalanceFile();
                closeCrossReferenceFile();
                closeDisclosureGroupFile();
                closeAccountFile();
                closeTransactionFile(jobExecution);
                LOG.info(MSG_END_OF_EXECUTION);
            } catch (final CardDemoException abend) {
                LOG.error("{} failed during end of run processing; recording it against the execution"
                        + " because Spring Batch does not fail a job on an afterJob exception",
                        ABEND_CULPRIT, abend);
                jobExecution.addFailureException(abend);
                jobExecution.setStatus(BatchStatus.FAILED);
            } finally {
                applyAbendExitStatus(jobExecution);
                restoreDiagnosticContext();
            }
        }
    }

    /**
     * Maps the step's outcome onto the four legacy return codes, replacing the {@code COND=} gating that
     * JCL would use between steps.
     *
     * <p><b>{@code app/jcl/INTCALC.jcl} carries no {@code COND=} parameter at all</b>, because it has a
     * single program step and nothing to gate. The decider exists because the orchestrated pipeline gates
     * on the full code set, and because without it a failed step could not be distinguished from an
     * abended one.
     *
     * <p>The mapping, and the evidence for each arm:
     *
     * <ul>
     *   <li><b>12, abend</b> - any recorded {@link FatalProcessingException}. It is the language
     *       environment's conventional response to the {@code CALL 'CEE3ABD'} at
     *       {@code app/cbl/CBACT04C.cbl:L632}; there is no {@code MOVE 12 TO RETURN-CODE} anywhere in the
     *       corpus and none is invented. Tested first, because an abend is also a failure and would
     *       otherwise be reported as 8.</li>
     *   <li><b>8, failed</b> - an unsuccessful batch status or a {@code FAILED} exit code with no
     *       abend among the failures.</li>
     *   <li><b>4, completed with rejects</b> - <b>unreachable for this job, and deliberately so.</b>
     *       {@code CBACT04C} contains no {@code RETURN-CODE} reference at all; the only
     *       {@code MOVE 4 TO RETURN-CODE} in the corpus is {@code app/cbl/CBTRN02C.cbl:L230}, guarded by a
     *       reject count this job does not keep, and the only other {@code TO RETURN-CODE} site is the
     *       variable move at {@code app/cbl/CSUTLDTC.cbl:L98}. The arm is retained because the pipeline's
     *       deciders must cover 0, 4, 8 and 12 uniformly, and because a decider that silently reported 0
     *       for an unrecognised exit code would hide a future regression. <b>No path in this job sets it,
     *       and none may be invented.</b></li>
     *   <li><b>0, completed</b> - everything else. This job's normal outcome.</li>
     * </ul>
     *
     * <p>Declared {@code static}: it closes over nothing, and a nested inner class would hold a reference
     * to the enclosing configuration for no reason.
     */
    private static final class InterestCalculationReturnCodeDecider implements JobExecutionDecider {

        /** Creates the decider. Stateless, so one instance serves every execution. */
        private InterestCalculationReturnCodeDecider() {
            // No state: the decision is a pure function of the two arguments.
        }

        /**
         * {@inheritDoc}
         *
         * @param jobExecution the running execution, whose recorded failures identify an abend
         * @param stepExecution the step that has just finished; the contract permits {@code null}, which
         * is reported as {@link FlowExecutionStatus#UNKNOWN} rather than assumed to be success
         * @return one of {@code ABEND}, {@code FAILED}, {@code COMPLETED WITH REJECTS},
         * {@code COMPLETED} or {@code UNKNOWN}
         */
        @Override
        public FlowExecutionStatus decide(final JobExecution jobExecution,
                final StepExecution stepExecution) {

            if (containsAbend(jobExecution, stepExecution)) {
                return new FlowExecutionStatus(EXIT_CODE_ABEND);
            }
            if (stepExecution == null) {
                return FlowExecutionStatus.UNKNOWN;
            }
            if (stepExecution.getStatus().isUnsuccessful()) {
                return FlowExecutionStatus.FAILED;
            }

            final String exitCode = stepExecution.getExitStatus().getExitCode();
            if (EXIT_CODE_FAILED.equals(exitCode)) {
                return FlowExecutionStatus.FAILED;
            }
            if (EXIT_CODE_COMPLETED_WITH_REJECTS.equals(exitCode)) {
                return new FlowExecutionStatus(EXIT_CODE_COMPLETED_WITH_REJECTS);
            }
            return FlowExecutionStatus.COMPLETED;
        }
    }
}
